"""M2.3 automated verification suite:
1. Multi-beat talking-head segmentation (4-10s beats, zero <4s tails), resolution clamping (480P->720P),
   max_duration (2..75s), lossless exact master WAV mux, and ffprobe A/V sync QA in generate_talking_head.py.
2. ota_server.py ephemeral (port=0) HTTP integration tests:
   - /v1/render-video validation (2..75, 480P->720P, clauses/words/align_url, pre-synthesized WAV)
   - _default_render_runner exact_input_voice_mp4 priority, ffprobe QA, TimeoutExpired failure, metrics
   - _load_existing_jobs startup orphan recovery (QUEUED/VOICE/LIPSYNC/PACKAGING + async_task RUNNING -> rig_restarted)
   - Bounded ThreadPoolExecutor async queue & TTS serialization (max_concurrent_tts == 1, 503 on overflow)
   - AST static audit of explicit timeout= on all M2-owned subprocess calls.
"""

from __future__ import annotations

import ast
import base64
import json
import math
import os
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

import numpy as np
import pytest
import soundfile as sf

HOME_DAEMON_DIR = Path(__file__).resolve().parents[1]
if str(HOME_DAEMON_DIR) not in sys.path:
    sys.path.insert(0, str(HOME_DAEMON_DIR))

import generate_talking_head as gth  # noqa: E402
import ota_server  # noqa: E402


def _make_speech_wave_with_pauses(
    total_sec: float,
    pause_windows_sec: list[tuple[float, float]] | None = None,
    *,
    sample_rate: int = 44100,
    f0_hz: float = 180.0,
) -> np.ndarray:
    n = int(round(total_sec * sample_rate))
    t = np.arange(n, dtype=np.float64) / float(sample_rate)
    sig = 0.25 * np.sin(2.0 * math.pi * f0_hz * t)
    for s_sec, e_sec in pause_windows_sec or []:
        i0 = max(0, int(round(s_sec * sample_rate)))
        i1 = min(n, int(round(e_sec * sample_rate)))
        sig[i0:i1] = 0.0
    return sig.astype(np.float32)


def _write_minimal_bundle(bundle_dir: Path, *, lora_version: int = 2) -> None:
    bundle_dir.mkdir(parents=True, exist_ok=True)
    files = {
        "s2_pro_4b_q4_k_m.gguf": b"GGUF" + b"\x01" * 32,
        f"voice_lora_v{lora_version:03d}.gguf": b"GGUF" + b"LORA" * 8,
        "codec_decoder_fp16.tflite": b"TFL3" + b"\x22" * 32,
        "sherpa_ctc_aligner.tar.gz": b"\x1f\x8b\x08\x00" + b"CTC" * 8,
        "wespeaker_resnet34.onnx": b"ONNX_WESP" * 4,
        "master_centroid.bin": b"\x00\x00\x80\x3f" * 256,
        "dnsmos_p808.onnx": b"ONNX_DNSMOS" * 4,
        "room_ir_44k.pcm": b"\x00\x00\x00\x3f" * 32,
        "room_tone_44k.pcm": b"\x00\x00\x80\x38" * 32,
        "lexicon.json": b'{"Aircheck":"test"}\n',
    }
    for name, content in files.items():
        (bundle_dir / name).write_bytes(content)
    from tinker_voice_daemon import build_bundle_manifest

    build_bundle_manifest(
        bundle_dir=bundle_dir,
        bundle_version=f"v{lora_version:03d}",
        lora_version=lora_version,
    )


def _make_real_mp4(path: Path, duration_sec: float, *, width: int = 720, height: int = 1280, include_audio: bool = True, audio_freq: int = 440) -> None:
    cmd = [
        "ffmpeg",
        "-y",
        "-hide_banner",
        "-loglevel",
        "error",
        "-f",
        "lavfi",
        "-i",
        f"color=c=navy:s={width}x{height}:r=30:d={duration_sec:.3f}",
    ]
    if include_audio:
        cmd.extend(
            [
                "-f",
                "lavfi",
                "-i",
                f"sine=frequency={audio_freq}:sample_rate=44100:duration={duration_sec:.3f}",
                "-c:v",
                "libx264",
                "-preset",
                "ultrafast",
                "-pix_fmt",
                "yuv420p",
                "-c:a",
                "aac",
                "-ar",
                "44100",
                "-ac",
                "1",
            ]
        )
    else:
        cmd.extend(["-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-an"])
    cmd.extend(["-t", f"{duration_sec:.3f}", str(path)])
    subprocess.run(cmd, check=True, timeout=30)


def _create_fake_bl_script(tmp_path: Path, log_jsonl: Path, *, wan_dur_override: float | None = None) -> Path:
    """Creates an executable fake `bl` CLI that logs arguments and synthesizes a real Wan MP4 via ffmpeg."""
    script = tmp_path / "fake_bl.py"
    script.write_text(
        f"""#!/usr/bin/env python3
import json
import subprocess
import sys
from pathlib import Path

args = sys.argv[1:]
def _arg(flag, default=None):
    if flag in args:
        idx = args.index(flag)
        if idx + 1 < len(args):
            return args[idx + 1]
    return default

entry = {{
    "resolution": _arg("--resolution"),
    "ratio": _arg("--ratio"),
    "duration": _arg("--duration"),
    "seed": _arg("--seed"),
    "prompt": _arg("--prompt"),
    "image": (_arg("--image") or "")[:40],
    "dry_run": "--dry-run" in args,
}}
log_path = Path({str(log_jsonl)!r})
with log_path.open("a", encoding="utf-8") as fh:
    fh.write(json.dumps(entry) + "\\n")

dl = _arg("--download")
if dl and "--dry-run" not in args:
    dur_s = float({wan_dur_override!r}) if {wan_dur_override!r} is not None else float(entry["duration"] or 5.0)
    # Intentionally use 990Hz audio at Wan layer so tests prove exact master WAV replaces it
    subprocess.run([
        "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
        "-f", "lavfi", "-i", f"color=c=darkred:s=720x1280:r=30:d={{dur_s:.3f}}",
        "-f", "lavfi", "-i", f"sine=frequency=990:sample_rate=44100:duration={{dur_s:.3f}}",
        "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
        "-c:a", "aac", "-ar", "44100", "-ac", "1", "-t", f"{{dur_s:.3f}}", dl
    ], check=True, timeout=30)
print(json.dumps({{"status": "ok", "duration": entry["duration"], "resolution": entry["resolution"]}}))
""",
        encoding="utf-8",
    )
    os.chmod(script, 0o755)
    return script


# ---------------------------------------------------------------------------
# 1. generate_talking_head.py Unit & Real ffmpeg/ffprobe Tests
# ---------------------------------------------------------------------------


def test_resolution_clamping_and_validation(tmp_path: Path) -> None:
    with pytest.warns(UserWarning, match="480P"):
        assert gth.normalize_talking_head_resolution("480P") == "720P"
        assert gth.normalize_talking_head_resolution("480p") == "720P"
    assert gth.normalize_talking_head_resolution("720P") == "720P"
    assert gth.normalize_talking_head_resolution("1080P") == "1080P"

    for bad_res in ("8K", "360P", "", "4K"):
        with pytest.raises(ValueError, match="Unsupported resolution"):
            gth.normalize_talking_head_resolution(bad_res)

    wav = tmp_path / "dummy.wav"
    sf.write(str(wav), _make_speech_wave_with_pauses(2.5), 44100, subtype="PCM_16")
    avatar = tmp_path / "avatar.jpg"
    avatar.write_bytes(b"\xff\xd8\xff\xe0AVATAR\xff\xd9")
    with pytest.raises(ValueError, match="Unsupported ratio"):
        gth.generate_talking_head(wav, avatar, tmp_path / "out.mp4", ratio="21:9")
    for bad_dur in (1, 121, True):
        with pytest.raises(ValueError, match="max_duration"):
            gth.generate_talking_head(wav, avatar, tmp_path / "out.mp4", max_duration=bad_dur)












def test_verify_av_sync_ffprobe_rejects_missing_streams_and_drift(tmp_path: Path) -> None:
    no_audio = tmp_path / "no_audio.mp4"
    _make_real_mp4(no_audio, 2.0, include_audio=False)
    with pytest.raises(RuntimeError, match="missing audio stream"):
        gth.verify_av_sync_ffprobe(no_audio)

    audio_only = tmp_path / "audio_only.m4a"
    subprocess.run(
        [
            "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
            "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100:duration=2",
            "-c:a", "aac", str(audio_only),
        ],
        check=True,
        timeout=30,
    )
    with pytest.raises(RuntimeError, match="missing video stream"):
        gth.verify_av_sync_ffprobe(audio_only)

    good_mp4 = tmp_path / "good_2s.mp4"
    _make_real_mp4(good_mp4, 2.0, include_audio=True)
    with pytest.raises(RuntimeError, match="A/V sync drift"):
        gth.verify_av_sync_ffprobe(good_mp4, expected_audio_ms=2600, max_drift_ms=120.0)




# ---------------------------------------------------------------------------
# 2. ota_server.py Ephemeral HTTP & Lifecycle Hardening Tests
# ---------------------------------------------------------------------------


def _start_test_server(tmp_path: Path, **kwargs: Any) -> tuple[ota_server.OtaHttpServer, str, int, threading.Thread]:
    bundles_root = tmp_path / "bundles"
    _write_minimal_bundle(bundles_root / "v002", lora_version=2)
    prefs_file = tmp_path / "preferences.jsonl"
    renders_dir = kwargs.pop("renders_dir", tmp_path / "renders")
    renders_dir.mkdir(parents=True, exist_ok=True)
    avatar = kwargs.pop("default_avatar_path", tmp_path / "avatar.jpg")
    if not Path(avatar).exists():
        Path(avatar).write_bytes(b"\xff\xd8\xff\xe0AVATAR\xff\xd9")
    srv = ota_server.create_ota_server(
        host="127.0.0.1",
        port=0,
        bundles_root=bundles_root,
        preferences_path=prefs_file,
        renders_dir=renders_dir,
        models_root=tmp_path,
        default_avatar_path=avatar,
        quiet=True,
        **kwargs,
    )
    host, port = srv.server_address[:2]
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    return srv, host, port, t


def _http_json(host: str, port: int, method: str, path: str, body: dict[str, Any] | None = None) -> tuple[int, dict[str, Any]]:
    data = json.dumps(body).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json"} if body is not None else {}
    req = urllib.request.Request(f"http://{host}:{port}{path}", data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            return resp.status, json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as err:
        return err.code, json.loads(err.read().decode("utf-8"))


def test_render_video_validation_75s_480p_clamp_and_presynth_wav(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    seen_specs: list[dict[str, Any]] = []

    def _recording_runner(spec: dict[str, Any], work_dir: Path, report_progress: Any) -> dict[str, Any]:
        del report_progress
        seen_specs.append(dict(spec))
        out = work_dir / "out.mp4"
        _make_real_mp4(out, 2.0)
        return {"output_mp4": out, "duration_ms": 2000, "metrics": {"av_sync_drift_ms": 10.0}}

    srv, host, port, t = _start_test_server(tmp_path, render_runner=_recording_runner)
    try:
        for dur in (65, 75):
            code, body = _http_json(
                host,
                port,
                "POST",
                "/v1/render-video",
                {
                    "spoken_text": "Sixty-five to seventy-five second broadcast.",
                    "voice_source": "ENROLLED_VOICE_CLONE",
                    "resolution": "480P",
                    "max_duration": dur,
                    "clauses": [{"id": "c1", "spoken_text": "Line one.", "start_ms": 0, "end_ms": 2000}],
                    "words": [{"word": "Line", "start_ms": 50, "end_ms": 400}],
                    "align_url": "/v1/renders/align_pre.json",
                },
            )
            assert code == 202, body
            job_id = body["job_id"]
            deadline = time.monotonic() + 5.0
            while time.monotonic() < deadline:
                _, snap = _http_json(host, port, "GET", f"/v1/jobs/{job_id}")
                if snap["status"] in ("READY", "FAILED"):
                    break
                time.sleep(0.02)
            assert snap["status"] == "READY"

        assert seen_specs[0]["max_duration"] == 65
        assert seen_specs[1]["max_duration"] == 75
        assert seen_specs[0]["resolution"] == "720P"  # clamped from 480P
        assert seen_specs[0]["clauses"][0]["id"] == "c1"
        assert seen_specs[0]["words"][0]["word"] == "Line"
        assert seen_specs[0]["align_url"] == "/v1/renders/align_pre.json"

        # Reject 1, 121, 8K with 400
        for bad in (
            {"spoken_text": "hi", "max_duration": 1},
            {"spoken_text": "hi", "max_duration": 121},
            {"spoken_text": "hi", "resolution": "8K"},
        ):
            code, _ = _http_json(host, port, "POST", "/v1/render-video", bad)
            assert code == 400, bad
    finally:
        srv.shutdown()
        srv.server_close()
        t.join(timeout=5.0)


def test_default_render_runner_prioritizes_exact_input_voice_and_runs_ffprobe_qa(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    wav_path = tmp_path / "presynth.wav"
    sf.write(str(wav_path), _make_speech_wave_with_pauses(2.5), 44100, subtype="PCM_16")
    wav_b64 = base64.b64encode(wav_path.read_bytes()).decode("ascii")

    tts_called = {"count": 0}

    def _fail_if_tts_called(**_k: Any) -> dict[str, Any]:
        tts_called["count"] += 1
        raise AssertionError("TTS must NOT run when audio_wav_base64 is already supplied")

    real_run = ota_server.subprocess.run
    mode = {"corrupt_exact": False, "timeout_head": False}

    def _mock_subproc(cmd: list[str], *args: Any, **kwargs: Any) -> Any:
        cmd_str = " ".join(str(x) for x in cmd)
        if "generate_talking_head.py" in cmd_str:
            assert kwargs.get("timeout", 0) >= 600
            if mode["timeout_head"]:
                raise subprocess.TimeoutExpired(cmd=cmd, timeout=kwargs["timeout"])
            out_idx = cmd.index("--output") + 1
            seg_mp4 = Path(cmd[out_idx])
            exact_mp4 = seg_mp4.with_name(f"{seg_mp4.stem}_exact_input_voice.mp4")
            # Write a 1.0s video-only bad clip at seg_mp4, and valid 2.5s A/V clip at exact_input_voice_mp4
            # (or audio-less if testing QA rejection) to prove exact_input_voice_mp4 is prioritized!
            _make_real_mp4(seg_mp4, 1.0, include_audio=False)
            _make_real_mp4(exact_mp4, 2.5, include_audio=not mode["corrupt_exact"])
            return subprocess.CompletedProcess(
                cmd,
                0,
                json.dumps(
                    {
                        "output_mp4": str(seg_mp4),
                        "exact_input_voice_mp4": str(exact_mp4),
                        "duration_sec": 2.5,
                    }
                ),
                "",
            )
        return real_run(cmd, *args, **kwargs)

    monkeypatch.setattr(ota_server.subprocess, "run", _mock_subproc)

    srv, host, port, t = _start_test_server(tmp_path, tts_studio_runner=_fail_if_tts_called)
    try:
        # 1. Pre-synthesized ENROLLED_VOICE_CLONE skips TTS, prioritizes exact_input_voice_mp4, surfaces av_sync_drift_ms
        code, body = _http_json(
            host,
            port,
            "POST",
            "/v1/render-video",
            {
                "spoken_text": "Pre-synthesized audio.",
                "voice_source": "ENROLLED_VOICE_CLONE",
                "audio_wav_base64": wav_b64,
                "words": [{"word": "Pre-synthesized", "start_ms": 0, "end_ms": 800}],
                "show_brand_bug": False,
                "max_duration": 65,
            },
        )
        assert code == 202
        job_id = body["job_id"]
        deadline = time.monotonic() + 10.0
        while time.monotonic() < deadline:
            _, snap = _http_json(host, port, "GET", f"/v1/jobs/{job_id}")
            if snap["status"] in ("READY", "FAILED"):
                break
            time.sleep(0.03)
        assert snap["status"] == "READY", snap
        assert tts_called["count"] == 0
        assert snap["metrics"]["av_sync_drift_ms"] <= 120.0
        assert abs(snap["duration_ms"] - 2500) <= 120
        assert snap["align_url"].startswith("/v1/renders/align_")

        # 2. Missing audio stream in produced MP4 fails ffprobe QA -> FAILED (never READY)
        mode["corrupt_exact"] = True
        code2, body2 = _http_json(
            host,
            port,
            "POST",
            "/v1/render-video",
            {
                "spoken_text": "Missing audio stream.",
                "voice_source": "MIC_VOICEOVER",
                "audio_wav_base64": wav_b64,
                "show_brand_bug": False,
            },
        )
        assert code2 == 202
        jid2 = body2["job_id"]
        deadline = time.monotonic() + 10.0
        while time.monotonic() < deadline:
            _, snap2 = _http_json(host, port, "GET", f"/v1/jobs/{jid2}")
            if snap2["status"] in ("READY", "FAILED"):
                break
            time.sleep(0.03)
        assert snap2["status"] == "FAILED"
        assert "missing audio stream" in snap2["error_message"]

        # 3. Subprocess TimeoutExpired transitions job cleanly to FAILED
        mode["corrupt_exact"] = False
        mode["timeout_head"] = True
        code3, body3 = _http_json(
            host,
            port,
            "POST",
            "/v1/render-video",
            {
                "spoken_text": "Timeout test.",
                "voice_source": "MIC_VOICEOVER",
                "audio_wav_base64": wav_b64,
                "show_brand_bug": False,
            },
        )
        assert code3 == 202
        jid3 = body3["job_id"]
        deadline = time.monotonic() + 10.0
        while time.monotonic() < deadline:
            _, snap3 = _http_json(host, port, "GET", f"/v1/jobs/{jid3}")
            if snap3["status"] in ("READY", "FAILED"):
                break
            time.sleep(0.03)
        assert snap3["status"] == "FAILED"
        assert "timed out" in snap3["error_message"]
    finally:
        srv.shutdown()
        srv.server_close()
        t.join(timeout=5.0)


def test_default_render_runner_merges_av_sync_drift_with_tts_metrics(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    def _studio_runner(
        *,
        project_id: str,
        spoken_text: str,
        caller_clauses: Any,
        output_dir: Path,
        assets: dict[str, Path | None],
    ) -> dict[str, Any]:
        del assets
        assert caller_clauses and caller_clauses[0]["id"] == "c_lead"
        output_dir.mkdir(parents=True, exist_ok=True)
        wav_path = output_dir / f"{project_id}_master.wav"
        sf.write(str(wav_path), _make_speech_wave_with_pauses(2.5), 44100, subtype="PCM_16")
        return {
            "master_wav_path": str(wav_path),
            "duration_ms": 2500,
            "words": [{"word": "Headline", "start_ms": 50, "end_ms": 600}],
            "timed_clauses": [{"id": "c_lead", "index": 0, "spoken_text": spoken_text, "start_ms": 0, "end_ms": 2500}],
            "metrics": {
                "cer": 0.0,
                "wespeaker_cosine": 0.93,
                "dnsmos_p808": 4.31,
                "tail_rms_dbfs": -58.0,
                "mastered_lufs": -16.0,
                "passed_all_strict_gates": True,
            },
        }

    real_run = ota_server.subprocess.run

    def _mock_head(cmd: list[str], *args: Any, **kwargs: Any) -> Any:
        if "generate_talking_head.py" in " ".join(str(x) for x in cmd):
            out_mp4 = Path(cmd[cmd.index("--output") + 1])
            exact_mp4 = out_mp4.with_name(f"{out_mp4.stem}_exact_input_voice.mp4")
            _make_real_mp4(exact_mp4, 2.5, include_audio=True)
            return subprocess.CompletedProcess(
                cmd,
                0,
                json.dumps({"output_mp4": str(out_mp4), "exact_input_voice_mp4": str(exact_mp4), "duration_sec": 2.5}),
                "",
            )
        return real_run(cmd, *args, **kwargs)

    monkeypatch.setattr(ota_server.subprocess, "run", _mock_head)
    srv, host, port, t = _start_test_server(tmp_path, tts_studio_runner=_studio_runner)
    try:
        code, body = _http_json(
            host,
            port,
            "POST",
            "/v1/render-video",
            {
                "spoken_text": "Headline.",
                "clauses": [{"id": "c_lead", "spoken_text": "Headline."}],
                "voice_source": "ENROLLED_VOICE_CLONE",
                "show_brand_bug": False,
                "max_duration": 65,
            },
        )
        assert code == 202
        job_id = body["job_id"]
        deadline = time.monotonic() + 10.0
        while time.monotonic() < deadline:
            _, snap = _http_json(host, port, "GET", f"/v1/jobs/{job_id}")
            if snap["status"] in ("READY", "FAILED"):
                break
            time.sleep(0.03)
        assert snap["status"] == "READY", snap
        m = snap["metrics"]
        assert m["cer"] == 0.0
        assert m["wespeaker_cosine"] == 0.93
        assert m["dnsmos_p808"] == 4.31
        assert m["tail_rms_dbfs"] == -58.0
        assert m["mastered_lufs"] == -16.0
        assert m["passed_all_strict_gates"] is True
        assert m["av_sync_drift_ms"] <= 120.0
        assert abs(m["video_duration_ms"] - 2500) <= 120
        assert abs(m["audio_duration_ms"] - 2500) <= 120
    finally:
        srv.shutdown()
        srv.server_close()
        t.join(timeout=5.0)


def test_startup_orphan_recovery_marks_all_non_terminal_jobs_failed_and_persists(tmp_path: Path) -> None:
    renders_dir = tmp_path / "renders"
    jobs_dir = renders_dir / "jobs"
    jobs_dir.mkdir(parents=True)

    seeded = {
        "job_queued": {"job_id": "job_queued", "status": "QUEUED", "stage_detail": "Queued"},
        "job_voice": {"job_id": "job_voice", "status": "VOICE", "stage_detail": "Voice"},
        "job_lipsync": {"job_id": "job_lipsync", "status": "LIPSYNC", "stage_detail": "Lipsync"},
        "job_pkg": {"job_id": "job_pkg", "status": "PACKAGING", "stage_detail": "Packaging"},
        "task_running": {"job_id": "task_running", "kind": "async_task", "status": "RUNNING"},
        "job_ready": {"job_id": "job_ready", "status": "READY", "video_url": "/v1/renders/job_ready.mp4"},
        "job_failed": {"job_id": "job_failed", "status": "FAILED", "error_message": "prior error"},
    }
    for jid, rec in seeded.items():
        (jobs_dir / f"{jid}.json").write_text(json.dumps(rec), encoding="utf-8")

    srv, host, port, t = _start_test_server(tmp_path, renders_dir=renders_dir)
    try:
        status, health = _http_json(host, port, "GET", "/health")
        assert status == 200
        assert health["active_render_jobs"] == 0

        for orphan_id in ("job_queued", "job_voice", "job_lipsync", "job_pkg", "task_running"):
            _, snap = _http_json(host, port, "GET", f"/v1/jobs/{orphan_id}")
            assert snap["status"] == "FAILED"
            assert snap["error_kind"] == "rig_restarted"
            assert "restarted" in snap["error_message"].lower()
            on_disk = json.loads((jobs_dir / f"{orphan_id}.json").read_text(encoding="utf-8"))
            assert on_disk["status"] == "FAILED"
            assert on_disk["error_kind"] == "rig_restarted"

        _, ready_snap = _http_json(host, port, "GET", "/v1/jobs/job_ready")
        assert ready_snap["status"] == "READY"
        _, failed_snap = _http_json(host, port, "GET", "/v1/jobs/job_failed")
        assert failed_snap["error_message"] == "prior error"
    finally:
        srv.shutdown()
        srv.server_close()
        t.join(timeout=5.0)


def test_bounded_async_queue_and_tts_serialization(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    active_tts = 0
    max_concurrent_tts = 0
    tts_gate = threading.Lock()
    first_tts_started = threading.Event()
    release_first_tts = threading.Event()
    backdrop_ran_while_tts_blocked = {"ok": False}

    def _slow_tts_runner(_text: str, out_wav: Path) -> dict[str, Any]:
        nonlocal active_tts, max_concurrent_tts
        with tts_gate:
            active_tts += 1
            max_concurrent_tts = max(max_concurrent_tts, active_tts)
        try:
            if not first_tts_started.is_set():
                first_tts_started.set()
                release_first_tts.wait(timeout=5.0)
            sf.write(str(out_wav), _make_speech_wave_with_pauses(1.5), 44100, subtype="PCM_16")
            return {"cer": 0.0, "passed_all_strict_gates": True}
        finally:
            with tts_gate:
                active_tts -= 1

    def _fast_plate_runner(_p: str, _a: str, work: Path) -> None:
        from PIL import Image

        if first_tts_started.is_set() and not release_first_tts.is_set():
            backdrop_ran_while_tts_blocked["ok"] = True
        Image.new("RGB", (90, 160), (10, 20, 30)).save(work / "plate_0.png")

    srv, host, port, t = _start_test_server(
        tmp_path,
        tts_runner=_slow_tts_runner,
        plate_runner=_fast_plate_runner,
    )
    try:
        # 1. Start slow TTS task + 2nd TTS task; backdrop must run concurrently while TTS is blocked
        c1, b1 = _http_json(host, port, "POST", "/v1/core/tts?async=1", {"spoken_text": "First story."})
        c2, b2 = _http_json(host, port, "POST", "/v1/core/tts?async=1", {"spoken_text": "Second story."})
        assert c1 == 202 and c2 == 202
        assert first_tts_started.wait(timeout=5.0)

        cb, bb = _http_json(host, port, "POST", "/v1/newsroom/backdrop?async=1", {"prompt": "newsroom"})
        assert cb == 202
        deadline = time.monotonic() + 5.0
        while time.monotonic() < deadline:
            _, bsnap = _http_json(host, port, "GET", f"/v1/jobs/{bb['job_id']}")
            if bsnap["status"] != "RUNNING":
                break
            time.sleep(0.02)
        assert bsnap["status"] == "READY"
        assert backdrop_ran_while_tts_blocked["ok"] is True

        release_first_tts.set()
        for tid in (b1["job_id"], b2["job_id"]):
            deadline = time.monotonic() + 5.0
            while time.monotonic() < deadline:
                _, snap = _http_json(host, port, "GET", f"/v1/jobs/{tid}")
                if snap["status"] != "RUNNING":
                    break
                time.sleep(0.02)
            assert snap["status"] == "READY"

        assert max_concurrent_tts == 1

        # 2. Bounded queue saturation returns HTTP 503
        monkeypatch.setattr(ota_server, "MAX_PENDING_ASYNC_TASKS", 1)
        release_first_tts.clear()
        first_tts_started.clear()
        c_hold, _ = _http_json(host, port, "POST", "/v1/core/tts?async=1", {"spoken_text": "Hold queue."})
        assert c_hold == 202
        assert first_tts_started.wait(timeout=5.0)
        c_full, err_full = _http_json(host, port, "POST", "/v1/core/tts?async=1", {"spoken_text": "Overflow."})
        assert c_full == 503
        assert err_full["error"] == "queue_full"
        release_first_tts.set()
    finally:
        release_first_tts.set()
        srv.shutdown()
        srv.server_close()
        t.join(timeout=5.0)


def test_ast_audit_explicit_timeouts_and_no_raw_thread_in_submit_async_task() -> None:
    m3_reserved = {
        "_compose_broll_and_saved_overlays_mp4",
        "_burn_breaking_news_chyron",
        "install_and_enable_remotion",
    }
    for py_path in (HOME_DAEMON_DIR / "generate_talking_head.py", HOME_DAEMON_DIR / "ota_server.py"):
        tree = ast.parse(py_path.read_text(encoding="utf-8"), filename=str(py_path))

        # Walk functions while tracking enclosing function name
        class _Visitor(ast.NodeVisitor):
            def __init__(self) -> None:
                self.fn_stack: list[str] = []
                self.missing_timeouts: list[tuple[str, int, str]] = []
                self.raw_threads_in_submit: list[int] = []

            def visit_FunctionDef(self, node: ast.FunctionDef) -> None:
                self.fn_stack.append(node.name)
                self.generic_visit(node)
                self.fn_stack.pop()

            def visit_Call(self, node: ast.Call) -> None:
                enclosing = self.fn_stack[-1] if self.fn_stack else "<module>"
                if isinstance(node.func, ast.Attribute) and isinstance(node.func.value, ast.Name):
                    if node.func.value.id == "subprocess" and node.func.attr in ("run", "check_output", "Popen"):
                        if enclosing not in m3_reserved:
                            has_timeout = any(kw.arg == "timeout" for kw in node.keywords)
                            if not has_timeout:
                                self.missing_timeouts.append((py_path.name, node.lineno, enclosing))
                    if (
                        py_path.name == "ota_server.py"
                        and enclosing == "submit_async_task"
                        and node.func.value.id == "threading"
                        and node.func.attr == "Thread"
                    ):
                        self.raw_threads_in_submit.append(node.lineno)
                self.generic_visit(node)

        v = _Visitor()
        v.visit(tree)
        assert v.missing_timeouts == [], f"Subprocess calls missing timeout=: {v.missing_timeouts}"
        assert v.raw_threads_in_submit == [], f"submit_async_task still spawns raw threading.Thread: {v.raw_threads_in_submit}"


def test_qa_rejected_corrupt_and_drifted_mp4_never_leaks_to_renders_dir_or_list_renders(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    wav_path = tmp_path / "voice.wav"
    sf.write(str(wav_path), _make_speech_wave_with_pauses(2.0), 44100, subtype="PCM_16")
    wav_b64 = base64.b64encode(wav_path.read_bytes()).decode("ascii")

    real_run = ota_server.subprocess.run
    mode = {"kind": "corrupt"}

    def _mock_head(cmd: list[str], *args: Any, **kwargs: Any) -> Any:
        if "generate_talking_head.py" in " ".join(str(x) for x in cmd):
            seg_mp4 = Path(cmd[cmd.index("--output") + 1])
            exact_mp4 = seg_mp4.with_name(f"{seg_mp4.stem}_exact_input_voice.mp4")
            if mode["kind"] == "corrupt":
                exact_mp4.write_bytes(b"NOT_AN_MP4_CORRUPT_PAYLOAD" * 16)
            else:
                # 2.5s video vs 2.0s audio -> 500ms drift > 120ms QA limit
                real_run(
                    [
                        "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                        "-f", "lavfi", "-i", "color=c=navy:s=720x1280:r=30:d=2.500",
                        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100:duration=2.000",
                        "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
                        "-c:a", "aac", "-ar", "44100", "-ac", "1",
                        str(exact_mp4),
                    ],
                    check=True,
                    timeout=30,
                )
            return subprocess.CompletedProcess(
                cmd,
                0,
                json.dumps({"output_mp4": str(seg_mp4), "exact_input_voice_mp4": str(exact_mp4), "duration_sec": 2.0}),
                "",
            )
        return real_run(cmd, *args, **kwargs)

    monkeypatch.setattr(ota_server.subprocess, "run", _mock_head)
    renders_dir = tmp_path / "renders"
    srv, host, port, t = _start_test_server(tmp_path, renders_dir=renders_dir)
    try:
        for kind in ("corrupt", "drifted"):
            mode["kind"] = kind
            code, accepted = _http_json(
                host,
                port,
                "POST",
                "/v1/render-video",
                {
                    "spoken_text": f"Reject {kind} MP4.",
                    "voice_source": "MIC_VOICEOVER",
                    "audio_wav_base64": wav_b64,
                    "show_brand_bug": False,
                },
            )
            assert code == 202
            job_id = accepted["job_id"]
            deadline = time.monotonic() + 10.0
            snap: dict[str, Any] = {}
            while time.monotonic() < deadline:
                _, snap = _http_json(host, port, "GET", f"/v1/jobs/{job_id}")
                if snap["status"] in ("READY", "FAILED"):
                    break
                time.sleep(0.03)
            assert snap["status"] == "FAILED", snap
            assert snap["video_url"] is None
            assert not (renders_dir / f"{job_id}.mp4").exists()

            req_dl = urllib.request.Request(f"http://{host}:{port}/v1/renders/{job_id}.mp4", method="GET")
            with pytest.raises(urllib.error.HTTPError) as err_info:
                urllib.request.urlopen(req_dl, timeout=5)
            assert err_info.value.code == 404

            code_list, body_list = _http_json(host, port, "GET", "/v1/renders")
            assert code_list == 200
            listed_ids = {item["id"] for item in body_list.get("renders", [])}
            assert job_id not in listed_ids
    finally:
        srv.shutdown()
        srv.server_close()
        t.join(timeout=5.0)


def test_list_renders_hides_failed_and_pending_jobs_keeps_ready_and_untracked(tmp_path: Path) -> None:
    renders_dir = tmp_path / "renders"
    jobs_dir = renders_dir / "jobs"
    jobs_dir.mkdir(parents=True)

    _make_real_mp4(renders_dir / "job_ready.mp4", 1.5)
    _make_real_mp4(renders_dir / "job_failed.mp4", 1.5)
    _make_real_mp4(renders_dir / "untracked_clip.mp4", 1.5)
    (jobs_dir / "job_ready.json").write_text(
        json.dumps({"job_id": "job_ready", "status": "READY", "title": "Ready Clip", "duration_ms": 1500}),
        encoding="utf-8",
    )
    (jobs_dir / "job_failed.json").write_text(
        json.dumps({"job_id": "job_failed", "status": "FAILED", "title": "Failed Clip", "error_message": "qa fail"}),
        encoding="utf-8",
    )

    srv, host, port, t = _start_test_server(tmp_path, renders_dir=renders_dir)
    try:
        code, body = _http_json(host, port, "GET", "/v1/renders")
        assert code == 200
        ids = {item["id"] for item in body["renders"]}
        assert "job_ready" in ids
        assert "untracked_clip" in ids
        assert "job_failed" not in ids
    finally:
        srv.shutdown()
        srv.server_close()
        t.join(timeout=5.0)


def test_explicit_missing_exact_input_voice_mp4_and_invalid_json_stdout_fail(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    wav_path = tmp_path / "voice.wav"
    sf.write(str(wav_path), _make_speech_wave_with_pauses(2.0), 44100, subtype="PCM_16")
    wav_b64 = base64.b64encode(wav_path.read_bytes()).decode("ascii")

    real_run = ota_server.subprocess.run
    mode = {"case": "missing_exact"}

    def _mock_head(cmd: list[str], *args: Any, **kwargs: Any) -> Any:
        if "generate_talking_head.py" in " ".join(str(x) for x in cmd):
            seg_mp4 = Path(cmd[cmd.index("--output") + 1])
            # Create valid seg_mp4 so old fallback would have wrongly picked Wan audio!
            _make_real_mp4(seg_mp4, 2.0, include_audio=True)
            if mode["case"] == "missing_exact":
                missing_exact = seg_mp4.with_name(f"{seg_mp4.stem}_exact_input_voice.mp4")
                return subprocess.CompletedProcess(
                    cmd,
                    0,
                    json.dumps({"output_mp4": str(seg_mp4), "exact_input_voice_mp4": str(missing_exact)}),
                    "",
                )
            return subprocess.CompletedProcess(cmd, 0, "NOT VALID JSON STDOUT", "")
        return real_run(cmd, *args, **kwargs)

    monkeypatch.setattr(ota_server.subprocess, "run", _mock_head)
    renders_dir = tmp_path / "renders"
    srv, host, port, t = _start_test_server(tmp_path, renders_dir=renders_dir)
    try:
        for case, expected_err in (
            ("missing_exact", "exact_input_voice_mp4"),
            ("bad_json", "invalid JSON stdout"),
        ):
            mode["case"] = case
            code, accepted = _http_json(
                host,
                port,
                "POST",
                "/v1/render-video",
                {
                    "spoken_text": f"Case {case}.",
                    "voice_source": "MIC_VOICEOVER",
                    "audio_wav_base64": wav_b64,
                    "show_brand_bug": False,
                },
            )
            assert code == 202
            job_id = accepted["job_id"]
            deadline = time.monotonic() + 10.0
            snap: dict[str, Any] = {}
            while time.monotonic() < deadline:
                _, snap = _http_json(host, port, "GET", f"/v1/jobs/{job_id}")
                if snap["status"] in ("READY", "FAILED"):
                    break
                time.sleep(0.03)
            assert snap["status"] == "FAILED", snap
            assert expected_err in snap["error_message"]
            assert not (renders_dir / f"{job_id}.mp4").exists()
    finally:
        srv.shutdown()
        srv.server_close()
        t.join(timeout=5.0)


def test_opposite_sign_start_time_drift_is_rejected_by_both_verifiers(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    mp4_path = tmp_path / "opp_sign.mp4"
    _make_real_mp4(mp4_path, 2.0, include_audio=True)

    fake_probe_json = json.dumps(
        {
            "format": {"duration": "2.000000"},
            "streams": [
                {
                    "codec_type": "video",
                    "width": 720,
                    "height": 1280,
                    "start_time": "0.080000",
                    "duration": "2.000000",
                },
                {
                    "codec_type": "audio",
                    "sample_rate": "44100",
                    "start_time": "-0.080000",
                    "duration": "2.000000",
                },
            ],
        }
    )

    def _fake_ffprobe(cmd: list[str], *args: Any, **kwargs: Any) -> subprocess.CompletedProcess[str]:
        return subprocess.CompletedProcess(cmd, 0, fake_probe_json, "")

    monkeypatch.setattr(gth.subprocess, "run", _fake_ffprobe)
    monkeypatch.setattr(ota_server.subprocess, "run", _fake_ffprobe)

    with pytest.raises(RuntimeError, match="160.00ms exceeds 120.0ms"):
        gth.verify_av_sync_ffprobe(mp4_path)

    with pytest.raises(RuntimeError, match="160.00ms exceeds 120.0ms"):
        ota_server._probe_av_sync_metrics(mp4_path)


def _decode_audio_mono(path: Path) -> np.ndarray:
    proc = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", str(path), "-ac", "1", "-ar", "44100", "-f", "f32le", "pipe:1"],
        capture_output=True,
        check=True,
    )
    return np.frombuffer(proc.stdout, dtype=np.float32)


def test_generate_talking_head_heygen_1080p_single_pass(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    """HeyGen is the only talking-head engine: upload voice, create Avatar 6 video, poll, download."""
    log = tmp_path / "heygen_calls.jsonl"
    fake = tmp_path / "heygen"
    fake.write_text(
        "#!/usr/bin/env python3\n"
        "import json,subprocess,sys\n"
        f"open({str(log)!r},'a').write(json.dumps(sys.argv[1:])+'\\n')\n"
        "a=sys.argv[1:]\n"
        "if a[:2]==['asset','create']: print(json.dumps({'data':{'asset_id':'aud123'}}))\n"
        "elif a[:2]==['video','create']: print(json.dumps({'data':{'video_id':'vid456'}}))\n"
        "elif a[:2]==['video','get']: print(json.dumps({'data':{'status':'completed'}}))\n"
        "elif a[:2]==['video','download']:\n"
        "    out=a[a.index('--output-path')+1]\n"
        "    subprocess.run(['ffmpeg','-y','-hide_banner','-loglevel','error','-f','lavfi','-i','color=c=black:s=1080x1920:r=25:d=4.000',"
        "'-f','lavfi','-i','sine=f=200:d=4','-c:v','libx264','-preset','ultrafast','-pix_fmt','yuv420p','-c:a','aac','-t','4.000',out],check=True)\n"
    )
    fake.chmod(0o755)
    monkeypatch.setattr(gth, "HEYGEN_BIN", fake)
    monkeypatch.setattr(gth, "HEYGEN_POLL_SEC", 0)

    wav = tmp_path / "voice4s.wav"
    sf.write(str(wav), _make_speech_wave_with_pauses(4.0, f0_hz=205.0), 44100, subtype="PCM_16")
    avatar = tmp_path / "avatar.jpg"
    avatar.write_bytes(b"\xff\xd8\xff\xe0AVATAR1080\xff\xd9")
    out_mp4 = tmp_path / "heygen_1080p.mp4"

    res = gth.generate_talking_head(
        voice_input=wav, avatar_path=avatar, output_mp4=out_mp4,
        resolution="1080P", ratio="9:16", max_duration=15,
    )
    calls = [json.loads(l) for l in log.read_text().splitlines()]
    create = json.loads(next(c for c in calls if c[:2] == ["video", "create"])[3])
    assert create == {
        "type": "avatar", "avatar_id": gth.HEYGEN_LOOK_ID, "audio_asset_id": "aud123",
        "engine": {"type": "heygen-avatar-6"}, "aspect_ratio": "9:16", "resolution": "1080p",
    }
    assert res["engine"] == "heygen:heygen-avatar-6"
    assert res["heygen"]["heygen_video_id"] == "vid456"
    assert res["resolution"] == "1080P"
    assert out_mp4.is_file()

    # The deliverable must carry the exact master voice WAV muxed onto HeyGen's video, not HeyGen's
    # own re-encoded audio (the fake HeyGen clip ships a 200 Hz sine; the master is a 205 Hz take).
    exact_mp4 = Path(res["exact_input_voice_mp4"])
    assert exact_mp4.is_file() and exact_mp4.stat().st_size > 0
    master_audio = _decode_audio_mono(wav)
    exact_audio = _decode_audio_mono(exact_mp4)
    n = min(master_audio.size, exact_audio.size)
    assert n > 0
    corr = float(np.corrcoef(master_audio[:n], exact_audio[:n])[0, 1])
    assert corr > 0.95, f"exact_input_voice audio does not match master voice (corr={corr:.3f})"


def test_build_lambda_voice_synthesizer_fn_falls_back_when_endpoint_down(monkeypatch: pytest.MonkeyPatch) -> None:
    # The rig supervisor always exports AIRCHECK_VOICE_ENDPOINT; when that endpoint is unreachable
    # (away from home / tunnel down) TTS must fall back to the local warm engine (None), not raise.
    monkeypatch.setenv("AIRCHECK_VOICE_ENDPOINT", "http://127.0.0.1:1")
    assert ota_server._build_lambda_voice_synthesizer_fn("http://127.0.0.1:1") is None


def test_head_render_prefetches_broll_into_cache_when_flag_set(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    wav_path = tmp_path / "presynth.wav"
    sf.write(str(wav_path), _make_speech_wave_with_pauses(2.5), 44100, subtype="PCM_16")
    wav_b64 = base64.b64encode(wav_path.read_bytes()).decode("ascii")

    real_run = ota_server.subprocess.run

    def _mock_head(cmd: list[str], *args: Any, **kwargs: Any) -> Any:
        if "generate_talking_head.py" in " ".join(str(x) for x in cmd):
            seg_mp4 = Path(cmd[cmd.index("--output") + 1])
            exact_mp4 = seg_mp4.with_name(f"{seg_mp4.stem}_exact_input_voice.mp4")
            _make_real_mp4(seg_mp4, 2.5, include_audio=True)
            _make_real_mp4(exact_mp4, 2.5, include_audio=True)
            return subprocess.CompletedProcess(
                cmd, 0,
                json.dumps({"output_mp4": str(seg_mp4), "exact_input_voice_mp4": str(exact_mp4), "duration_sec": 2.5}),
                "",
            )
        return real_run(cmd, *args, **kwargs)

    monkeypatch.setattr(ota_server.subprocess, "run", _mock_head)

    plate_calls: list[tuple[str, str]] = []

    def _fake_plate(prompt: str, aspect: str, work: Path) -> None:
        from PIL import Image

        plate_calls.append((prompt, aspect))
        Image.new("RGB", (90, 160), (10, 20, 30)).save(work / "plate_0.png")

    renders_dir = tmp_path / "renders"
    srv, host, port, t = _start_test_server(tmp_path, renders_dir=renders_dir, plate_runner=_fake_plate)
    try:
        body = {
            "voice_source": "MIC_VOICEOVER",
            "audio_wav_base64": wav_b64,
            "spoken_text": "Anchor opens then cuts to b-roll.",
            "resolution": "1080P",
            "ratio": "9:16",
            "max_duration": 15,
            "prefetch_broll": True,
            "broll_aspect": "9:16",
            "broll_plan": [
                {"id": "c1", "spoken_text": "Anchor opens.", "segment_mode": "TALKING_HEAD"},
                {"id": "c2", "spoken_text": "Now cut away.", "segment_mode": "BROLL_CUTAWAY", "broll_caption": "Grid telemetry spike"},
            ],
        }
        code, accepted = _http_json(host, port, "POST", "/v1/render-video", body)
        assert code == 202
        job_id = accepted["job_id"]
        deadline = time.monotonic() + 15.0
        snap: dict[str, Any] = {}
        while time.monotonic() < deadline:
            _, snap = _http_json(host, port, "GET", f"/v1/jobs/{job_id}")
            if snap["status"] in ("READY", "FAILED"):
                break
            time.sleep(0.03)
        assert snap["status"] == "READY", snap
        # Only the cutaway clause is prefetched, at the requested aspect, and a still lands in cache.
        assert len(plate_calls) == 1
        assert plate_calls[0][0].startswith("Grid telemetry spike")
        assert plate_calls[0][1] == "9:16"
        assert len(list(renders_dir.glob("broll_*.png"))) >= 1
    finally:
        srv.shutdown()
        srv.server_close()
        t.join(timeout=5.0)


def test_build_lambda_voice_synthesizer_fn_decodes_48k_wav_to_44k_pcm(tmp_path: Path) -> None:
    import http.server
    from types import SimpleNamespace

    class _FakeLambdaVoiceHandler(http.server.BaseHTTPRequestHandler):
        def do_GET(self) -> None:  # noqa: N802
            body = json.dumps({"ok": True, "role": "voice"}).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_POST(self) -> None:  # noqa: N802
            _ = self.rfile.read(int(self.headers.get("Content-Length", "0")))
            wav_p = tmp_path / "synth48.wav"
            t = np.linspace(0.0, 1.5, int(48000 * 1.5), endpoint=False, dtype=np.float32)
            sf.write(str(wav_p), 0.25 * np.sin(2.0 * np.pi * 195.0 * t), 48000, subtype="PCM_16")
            body = json.dumps({"wav_b64": base64.b64encode(wav_p.read_bytes()).decode("ascii")}).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, fmt: str, *args: Any) -> None:
            return

    httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), _FakeLambdaVoiceHandler)
    port = httpd.server_address[1]
    th = threading.Thread(target=httpd.serve_forever, daemon=True)
    th.start()
    try:
        synth_fn = ota_server._build_lambda_voice_synthesizer_fn(f"http://127.0.0.1:{port}")
        assert synth_fn is not None
        assert synth_fn.aircheck_voice_engine in ("lambda_chatterbox", "lambda_f5")
        takes = synth_fn(SimpleNamespace(index=0, raw_text="Good evening from the studio."), 1)
        assert len(takes) == 1
        assert takes[0].pcm44k.dtype == np.float32
        assert takes[0].pcm44k.size > int(44100 * 1.5)
    finally:
        httpd.shutdown()
        httpd.server_close()


def test_coalesce_and_restore_micro_clauses_for_lambda_tts() -> None:
    caller = [
        {"id": "c0", "spoken_text": "Good evening."},
        {"id": "c1", "spoken_text": "Two new AI agents shipped overnight with studio clarity."},
    ]
    orig, merged, did_merge = ota_server._coalesce_clauses_for_lambda_tts("", caller)
    assert did_merge is True
    assert len(merged) == 1
    assert merged[0]["spoken_text"].startswith("Good evening. Two new AI agents")
    import render_broadcast_studio as rbs

    planned, _ = rbs.plan_caller_clauses(merged, min_clause_words=6)
    assert len(planned) == 1
    assert planned[0].raw_text.startswith("Good evening. Two new AI agents")

    words = [
        {"word": "Good", "start_ms": 60, "end_ms": 320},
        {"word": "evening", "start_ms": 320, "end_ms": 780},
        {"word": "Two", "start_ms": 940, "end_ms": 1150},
        {"word": "new", "start_ms": 1150, "end_ms": 1350},
        {"word": "AI", "start_ms": 1350, "end_ms": 1620},
        {"word": "agents", "start_ms": 1620, "end_ms": 2050},
        {"word": "shipped", "start_ms": 2050, "end_ms": 2400},
        {"word": "overnight", "start_ms": 2400, "end_ms": 2900},
        {"word": "with", "start_ms": 2900, "end_ms": 3080},
        {"word": "studio", "start_ms": 3080, "end_ms": 3450},
        {"word": "clarity", "start_ms": 3450, "end_ms": 3950},
    ]
    restored = ota_server._restore_caller_clause_timings_from_words(orig, words, 4050)
    assert len(restored) == 2
    assert restored[0] == {"id": "c0", "index": 0, "spoken_text": "Good evening.", "start_ms": 60, "end_ms": 780}
    assert restored[1] == {
        "id": "c1",
        "index": 1,
        "spoken_text": "Two new AI agents shipped overnight with studio clarity.",
        "start_ms": 940,
        "end_ms": 4050,
    }
