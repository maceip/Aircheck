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
    fake_bl = _create_fake_bl_script(tmp_path, tmp_path / "bl.jsonl")

    with pytest.raises(ValueError, match="Unsupported ratio"):
        gth.generate_talking_head(wav, avatar, tmp_path / "out.mp4", ratio="21:9", bl_bin=fake_bl)
    for bad_dur in (1, 76, True):
        with pytest.raises(ValueError, match="max_duration"):
            gth.generate_talking_head(wav, avatar, tmp_path / "out.mp4", max_duration=bad_dur, bl_bin=fake_bl)


@pytest.mark.parametrize(
    "total_ms",
    [3_000, 10_000, 10_500, 11_000, 12_000, 41_000, 60_000, 75_000],
)
def test_plan_talking_head_beats_invariant_no_short_tails(total_ms: int) -> None:
    beats = gth.plan_talking_head_beats(total_ms)
    assert beats, f"Empty beat plan for {total_ms}"
    assert beats[0]["start_ms"] == 0
    assert beats[-1]["end_ms"] == total_ms
    assert sum(b["duration_ms"] for b in beats) == total_ms
    for i in range(len(beats) - 1):
        assert beats[i]["end_ms"] == beats[i + 1]["start_ms"]

    if total_ms <= 10_000:
        assert len(beats) == 1
        assert 4 <= beats[0]["wan_duration_sec"] <= 10
    else:
        for b in beats:
            assert 4_000 <= b["duration_ms"] <= 10_000, f"Beat out of 4-10s bounds for {total_ms}ms: {beats}"
            assert 4 <= b["wan_duration_sec"] <= 10


def test_plan_talking_head_beats_prefers_clauses_words_and_silences(tmp_path: Path) -> None:
    # 1. Clause boundary at 6200ms on an 11.5s clip -> cuts exactly near 6200ms
    clauses = [
        {"id": "c1", "spoken_text": "First clause.", "start_ms": 0, "end_ms": 6100},
        {"id": "c2", "spoken_text": "Second clause.", "start_ms": 6300, "end_ms": 11500},
    ]
    beats_cl = gth.plan_talking_head_beats(11_500, clauses=clauses)
    assert len(beats_cl) == 2
    assert beats_cl[0]["end_ms"] == 6200
    assert beats_cl[1]["duration_ms"] == 5300

    # 2. Word boundary with >=150ms pause at 5400ms on an 11.0s clip
    words = [
        {"word": "Alpha", "start_ms": 100, "end_ms": 500},
        {"word": "pause", "start_ms": 4800, "end_ms": 5250},
        {"word": "next", "start_ms": 5550, "end_ms": 6000},
        {"word": "end", "start_ms": 10200, "end_ms": 10900},
    ]
    beats_w = gth.plan_talking_head_beats(11_000, words=words)
    assert len(beats_w) == 2
    assert beats_w[0]["end_ms"] == 5400

    # 3. Real ffmpeg silencedetect gap on WAV (silence at 5.8..6.4s in 12.0s WAV)
    wav_path = tmp_path / "silences.wav"
    sf.write(
        str(wav_path),
        _make_speech_wave_with_pauses(12.0, [(5.8, 6.4)]),
        44100,
        subtype="PCM_16",
    )
    beats_sil = gth.plan_talking_head_beats(12_000, wav_path=wav_path)
    assert len(beats_sil) == 2
    assert 5_750 <= beats_sil[0]["end_ms"] <= 6_450
    assert all(4_000 <= b["duration_ms"] <= 10_000 for b in beats_sil)


def test_generate_talking_head_multibeat_exact_wav_mux_and_clamping(tmp_path: Path) -> None:
    # 15.0s audio with pauses at 5.5-6.0s and 10.5-11.0s -> splits into 3 coherent beats
    voice_wav = tmp_path / "story_15s.wav"
    pcm = _make_speech_wave_with_pauses(15.0, [(5.5, 6.0), (10.5, 11.0)], f0_hz=195.0)
    sf.write(str(voice_wav), pcm, 44100, subtype="PCM_16")

    avatar = tmp_path / "avatar.jpg"
    avatar.write_bytes(b"\xff\xd8\xff\xe0AVATAR_BYTES\xff\xd9")
    bl_log = tmp_path / "bl_calls.jsonl"
    # Fake bl emits slightly shorter Wan clips (e.g. 4.8s) to prove tpad + exact master WAV mux keeps exact 15.0s
    fake_bl = _create_fake_bl_script(tmp_path, bl_log, wan_dur_override=4.8)
    out_mp4 = tmp_path / "head_out.mp4"

    words = [
        {"word": "Breaking", "start_ms": 100, "end_ms": 600},
        {"word": "update", "start_ms": 650, "end_ms": 1200},
        {"word": "middle", "start_ms": 6200, "end_ms": 6900},
        {"word": "closing", "start_ms": 11200, "end_ms": 12000},
    ]

    with pytest.warns(UserWarning, match="480P"):
        res = gth.generate_talking_head(
            voice_input=voice_wav,
            avatar_path=avatar,
            output_mp4=out_mp4,
            resolution="480P",
            ratio="9:16",
            max_duration=75,
            words=words,
            bl_bin=fake_bl,
            seed=777,
        )

    assert res["resolution"] == "720P"
    assert res["beat_count"] >= 2
    for b in res["beats"]:
        assert 4_000 <= b["duration_ms"] <= 10_000
    assert res["av_sync_drift_ms"] <= gth.MAX_AV_SYNC_DRIFT_MS

    exact_mp4 = Path(res["exact_input_voice_mp4"])
    assert exact_mp4.is_file() and exact_mp4.stat().st_size > 0
    assert Path(res["output_mp4"]).is_file()

    # Verify bl NEVER received 480P, always got 720P, consistent seed, and 4..10s duration
    calls = [json.loads(line) for line in bl_log.read_text(encoding="utf-8").splitlines() if line.strip()]
    assert len(calls) == res["beat_count"]
    for c in calls:
        assert c["resolution"] == "720P"
        assert c["seed"] == "777"
        assert 4 <= int(c["duration"]) <= 10

    # Verify exact_input_voice_mp4 preserves exact 15.0s duration & 44.1kHz audio (NOT 4.8s Wan beats)
    qa = gth.verify_av_sync_ffprobe(
        exact_mp4,
        expected_audio_ms=15_000,
        expected_width=720,
        expected_height=1280,
    )
    assert qa["av_sync_drift_ms"] <= 120.0
    assert abs(qa["audio_duration_ms"] - 15_000) <= 120

    # Verify audio frequency in exact_input_voice_mp4 matches input WAV (195Hz, NOT Wan's 990Hz)
    extracted_wav = tmp_path / "extracted.wav"
    subprocess.run(
        ["ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-i", str(exact_mp4), "-vn", "-ac", "1", "-ar", "44100", str(extracted_wav)],
        check=True,
        timeout=30,
    )
    samples, sr = sf.read(str(extracted_wav), dtype="float32")
    assert sr == 44100
    seg = samples[:44100]
    freqs = np.fft.rfftfreq(len(seg), d=1.0 / sr)
    peak_hz = float(freqs[int(np.argmax(np.abs(np.fft.rfft(seg))))])
    assert abs(peak_hz - 195.0) < 5.0, f"Expected input 195Hz voice, got {peak_hz}Hz"


def test_generate_talking_head_60s_multibeat_end_to_end(tmp_path: Path) -> None:
    pauses = [(8.0, 8.4), (16.5, 16.9), (25.0, 25.4), (33.5, 33.9), (42.0, 42.4), (51.0, 51.4)]
    voice_wav = tmp_path / "story_60s.wav"
    sf.write(str(voice_wav), _make_speech_wave_with_pauses(60.0, pauses, f0_hz=175.0), 44100, subtype="PCM_16")
    avatar = tmp_path / "avatar.jpg"
    avatar.write_bytes(b"\xff\xd8\xff\xe0AVATAR60\xff\xd9")
    bl_log = tmp_path / "bl_60s.jsonl"
    fake_bl = _create_fake_bl_script(tmp_path, bl_log)
    out_mp4 = tmp_path / "head_60s.mp4"

    res = gth.generate_talking_head(
        voice_input=voice_wav,
        avatar_path=avatar,
        output_mp4=out_mp4,
        resolution="720P",
        ratio="9:16",
        max_duration=75,
        bl_bin=fake_bl,
        seed=123,
    )
    assert res["beat_count"] >= 6
    assert all(4_000 <= b["duration_ms"] <= 10_000 for b in res["beats"])
    assert sum(b["duration_ms"] for b in res["beats"]) == 60_000
    assert res["av_sync_drift_ms"] <= gth.MAX_AV_SYNC_DRIFT_MS
    assert abs(res["audio_duration_ms"] - 60_000) <= 120
    assert abs(res["video_duration_ms"] - 60_000) <= 120


def test_generate_talking_head_pcm_input_and_trim_only_when_exceeding_max(tmp_path: Path) -> None:
    # 6.0s float32 PCM input with max_duration=4 -> cleanly trimmed to 4.0s
    pcm_path = tmp_path / "voice.pcm"
    pcm = _make_speech_wave_with_pauses(6.0, f0_hz=210.0)
    pcm.tofile(str(pcm_path))
    avatar = tmp_path / "avatar.jpg"
    avatar.write_bytes(b"\xff\xd8\xff\xe0AVATAR\xff\xd9")
    fake_bl = _create_fake_bl_script(tmp_path, tmp_path / "bl_pcm.jsonl")

    res = gth.generate_talking_head(
        voice_input=pcm_path,
        avatar_path=avatar,
        output_mp4=tmp_path / "trimmed.mp4",
        resolution="720P",
        ratio="9:16",
        max_duration=4,
        bl_bin=fake_bl,
    )
    assert abs(res["duration_ms"] - 4000) <= 80
    assert res["av_sync_drift_ms"] <= 120.0


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


def test_generate_talking_head_timeout_expired_raises_clean_runtime_error(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    wav = tmp_path / "voice.wav"
    sf.write(str(wav), _make_speech_wave_with_pauses(3.0), 44100, subtype="PCM_16")
    avatar = tmp_path / "avatar.jpg"
    avatar.write_bytes(b"\xff\xd8\xff\xe0AVATAR\xff\xd9")
    fake_bl = _create_fake_bl_script(tmp_path, tmp_path / "bl_to.jsonl")

    real_run = gth.subprocess.run

    def _timeout_bl(cmd: list[str], *args: Any, **kwargs: Any) -> Any:
        if str(fake_bl) in " ".join(str(x) for x in cmd):
            raise subprocess.TimeoutExpired(cmd=cmd, timeout=kwargs.get("timeout", 1))
        return real_run(cmd, *args, **kwargs)

    monkeypatch.setattr(gth.subprocess, "run", _timeout_bl)
    with pytest.raises(RuntimeError, match="timed out"):
        gth.generate_talking_head(
            voice_input=wav,
            avatar_path=avatar,
            output_mp4=tmp_path / "timeout.mp4",
            bl_bin=fake_bl,
            bl_timeout_sec=1,
        )


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

        # Reject 1, 76, 8K with 400
        for bad in (
            {"spoken_text": "hi", "max_duration": 1},
            {"spoken_text": "hi", "max_duration": 76},
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


def test_generate_talking_head_lambda_1080p_endpoint_single_pass(tmp_path: Path) -> None:
    import http.server

    seen_reqs: list[dict[str, Any]] = []

    class _FakeLambdaVideoHandler(http.server.BaseHTTPRequestHandler):
        def do_POST(self) -> None:  # noqa: N802
            raw = self.rfile.read(int(self.headers.get("Content-Length", "0")))
            body = json.loads(raw.decode("utf-8"))
            seen_reqs.append(
                {
                    "width": body["width"],
                    "height": body["height"],
                    "motion_mode": body["motion_mode"],
                    "seed": body["seed"],
                }
            )
            wav_in = tmp_path / "srv_in.wav"
            wav_in.write_bytes(base64.b64decode(body["voice_wav_b64"]))
            mp4_out = tmp_path / "srv_out.mp4"
            subprocess.run(
                [
                    "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                    "-f", "lavfi", "-i", f"color=c=black:s={body['width']}x{body['height']}:r=25:d=4.000",
                    "-i", str(wav_in),
                    "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
                    "-c:a", "aac", "-ar", "48000", "-ac", "1", "-t", "4.000",
                    str(mp4_out),
                ],
                check=True,
                timeout=30,
            )
            resp = {
                "mp4_b64": base64.b64encode(mp4_out.read_bytes()).decode("ascii"),
                "identity_qa": {"csim_mean": 0.9775, "csim_min": 0.9593},
            }
            payload = json.dumps(resp).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def log_message(self, fmt: str, *args: Any) -> None:
            return

    httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), _FakeLambdaVideoHandler)
    port = httpd.server_address[1]
    th = threading.Thread(target=httpd.serve_forever, daemon=True)
    th.start()
    try:
        wav = tmp_path / "voice4s.wav"
        sf.write(str(wav), _make_speech_wave_with_pauses(4.0, f0_hz=205.0), 44100, subtype="PCM_16")
        avatar = tmp_path / "avatar.jpg"
        avatar.write_bytes(b"\xff\xd8\xff\xe0AVATAR1080\xff\xd9")
        out_mp4 = tmp_path / "lambda_1080p.mp4"

        res = gth.generate_talking_head(
            voice_input=wav,
            avatar_path=avatar,
            output_mp4=out_mp4,
            resolution="1080P",
            ratio="9:16",
            max_duration=15,
            video_endpoint=f"http://127.0.0.1:{port}",
            seed=99,
        )
        assert res["engine"] == "liveportrait_hf+latentsync_1.6_512"
        assert res["resolution"] == "1080P"
        assert res["beat_count"] == 1
        assert res["identity_qa"]["csim_mean"] == 0.9775
        assert seen_reqs == [{"width": 1080, "height": 1920, "motion_mode": "liveportrait_hf", "seed": 99}]
        assert Path(res["exact_input_voice_mp4"]).is_file()
    finally:
        httpd.shutdown()
        httpd.server_close()
