from __future__ import annotations

import json
import math
import pathlib
import struct
import subprocess
import threading
import urllib.request
import wave

from ota_server import create_ota_server


def _write_sine_wav(out_path: pathlib.Path, duration_sec: float = 2.0, sr: int = 16000) -> None:
    n = int(duration_sec * sr)
    samples = [int(0.45 * math.sin(2.0 * math.pi * 440.0 * (i / sr)) * 32767) for i in range(n)]
    with wave.open(str(out_path), "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(sr)
        wf.writeframes(struct.pack(f"<{n}h", *samples))


def _write_test_talking_head_mp4(out_mp4: pathlib.Path, wav_path: pathlib.Path) -> None:
    subprocess.run(
        [
            "ffmpeg",
            "-y",
            "-f",
            "lavfi",
            "-i",
            "color=c=0x10141c:s=360x640:r=15:d=2.0",
            "-i",
            str(wav_path),
            "-shortest",
            "-c:v",
            "libx264",
            "-preset",
            "ultrafast",
            "-pix_fmt",
            "yuv420p",
            "-c:a",
            "aac",
            str(out_mp4),
        ],
        check=True,
        capture_output=True,
    )


def test_core_tts_and_broll_chop_with_and_without_saved_overlays(tmp_path: pathlib.Path) -> None:
    bundles_dir = tmp_path / "bundles"
    bundles_dir.mkdir(parents=True)
    prefs_file = tmp_path / "preferences.jsonl"
    renders_dir = tmp_path / "renders"
    renders_dir.mkdir(parents=True)

    def fake_tts_runner(text: str, out_wav: pathlib.Path) -> dict[str, object]:
        _write_sine_wav(out_wav, duration_sec=2.0)
        return {"text_len": len(text)}

    server = create_ota_server(
        host="127.0.0.1",
        port=0,
        bundles_root=bundles_dir,
        preferences_path=prefs_file,
        renders_dir=renders_dir,
        tts_runner=fake_tts_runner,
        quiet=True,
    )
    host, port = server.server_address[:2]
    t = threading.Thread(target=server.serve_forever, daemon=True)
    t.start()
    try:
        # 1. Stage 1: POST /v1/core/tts
        tts_payload = json.dumps(
            {
                "project_id": "proj_1",
                "clauses": [
                    {"id": "c1", "spoken_text": "Anchor opens the story on camera."},
                    {"id": "c2", "spoken_text": "Now we cut into B-roll footage while speaking."},
                ],
            }
        ).encode("utf-8")
        req = urllib.request.Request(
            f"http://{host}:{port}/v1/core/tts",
            data=tts_payload,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req) as resp:
            tts_res = json.loads(resp.read().decode("utf-8"))

        assert tts_res["duration_ms"] >= 1900
        assert len(tts_res["waveform_peaks"]) == 64
        assert len(tts_res["clauses"]) == 2
        assert tts_res["clauses"][0]["start_ms"] == 0
        assert tts_res["clauses"][1]["end_ms"] == tts_res["duration_ms"]

        # Verify WAV is streamable
        with urllib.request.urlopen(f"http://{host}:{port}{tts_res['audio_url']}") as wav_resp:
            wav_bytes = wav_resp.read()
        assert len(wav_bytes) > 1000

        # Seed a real speech-matched talking-head MP4 in renders_dir
        head_wav = tmp_path / "head_voice.wav"
        head_wav.write_bytes(wav_bytes)
        head_mp4 = renders_dir / "job_head_001.mp4"
        _write_test_talking_head_mp4(head_mp4, head_wav)

        # 2. Stage 4 WITHOUT saved Newsroom overlays (the default path!)
        chop_no_overlay = json.dumps(
            {
                "script_id": "proj_1",
                "title": "Core Story No Overlay",
                "source_job_id": "job_head_001",
                "clauses": [
                    {
                        "id": "c1",
                        "spoken_text": "Anchor opens the story on camera.",
                        "start_ms": 0,
                        "end_ms": 950,
                        "segment_mode": "TALKING_HEAD",
                    },
                    {
                        "id": "c2",
                        "spoken_text": "Now we cut into B-roll footage while speaking.",
                        "start_ms": 950,
                        "end_ms": 2000,
                        "segment_mode": "BROLL_CUTAWAY",
                        "broll_caption": "Subway fiber conduit cutaway",
                    },
                ],
                "overlays": [],
            }
        ).encode("utf-8")
        req2 = urllib.request.Request(
            f"http://{host}:{port}/v1/core/chop-broll",
            data=chop_no_overlay,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req2) as resp2:
            chop_res = json.loads(resp2.read().decode("utf-8"))

        assert chop_res["status"] == "READY"
        assert chop_res["broll_cut_count"] == 1
        assert chop_res["applied_overlay_count"] == 0

        # 3. Stage 4 WITH a saved Newsroom Brief overlay attached
        chop_with_overlay = json.dumps(
            {
                "script_id": "proj_1",
                "title": "News Brief With Saved Overlay",
                "source_job_id": "job_head_001",
                "clauses": [
                    {
                        "id": "c1",
                        "spoken_text": "Anchor opens the story on camera.",
                        "start_ms": 0,
                        "end_ms": 2000,
                        "segment_mode": "BROLL_CUTAWAY",
                        "broll_caption": "Grid telemetry spike",
                    }
                ],
                "overlays": [
                    {
                        "id": "ovl_1",
                        "kind": "STAT_CALLOUT",
                        "headline": "GRID LOAD +42%",
                        "subline": "ERCOT real-time dispatch telemetry",
                        "callout_stat": "+42% PEAK",
                        "duration_sec": 3,
                    }
                ],
            }
        ).encode("utf-8")
        req3 = urllib.request.Request(
            f"http://{host}:{port}/v1/core/chop-broll",
            data=chop_with_overlay,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req3) as resp3:
            chop_ovl_res = json.loads(resp3.read().decode("utf-8"))

        assert chop_ovl_res["status"] == "READY"
        assert chop_ovl_res["broll_cut_count"] == 1
        assert chop_ovl_res["applied_overlay_count"] == 1
    finally:
        server.server_close()


def test_durable_logs_ingest_and_read(tmp_path: pathlib.Path) -> None:
    import logging
    from logging.handlers import RotatingFileHandler

    import ota_server

    bundles_dir = tmp_path / "bundles"
    bundles_dir.mkdir(parents=True)
    prefs_file = tmp_path / "preferences.jsonl"
    renders_dir = tmp_path / "renders"
    renders_dir.mkdir(parents=True)
    server = create_ota_server(
        host="127.0.0.1",
        port=0,
        bundles_root=bundles_dir,
        preferences_path=prefs_file,
        renders_dir=renders_dir,
        quiet=True,
    )
    log_path = ota_server._setup_durable_logging(renders_dir)
    host, port = server.server_address[:2]
    t = threading.Thread(target=server.serve_forever, daemon=True)
    t.start()
    try:
        body = json.dumps(
            {
                "device": "pixel",
                "events": [
                    {"tag": "cut", "msg": "render started", "level": "info"},
                    {"tag": "rig", "msg": "boom", "level": "error"},
                ],
            }
        ).encode("utf-8")
        req = urllib.request.Request(
            f"http://{host}:{port}/v1/logs",
            data=body,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req) as resp:
            assert json.loads(resp.read().decode("utf-8"))["accepted"] == 2
        for h in logging.getLogger().handlers:
            if isinstance(h, RotatingFileHandler) and h.baseFilename == str(log_path):
                h.flush()
        with urllib.request.urlopen(f"http://{host}:{port}/v1/logs?lines=50") as resp:
            data = json.loads(resp.read().decode("utf-8"))
        text = "\n".join(data["lines"])
        assert "app[pixel] cut render started" in text
        assert "app[pixel] rig boom" in text
        assert data["log_path"] == str(log_path)
        # Bounded rotation configured (no unbounded growth the user must manage).
        rot = [h for h in logging.getLogger().handlers if isinstance(h, RotatingFileHandler) and h.baseFilename == str(log_path)]
        assert rot and rot[0].maxBytes > 0 and rot[0].backupCount > 0
    finally:
        server.shutdown()
        server.server_close()
        for h in list(logging.getLogger().handlers):
            if isinstance(h, RotatingFileHandler) and h.baseFilename == str(log_path):
                logging.getLogger().removeHandler(h)
                h.close()


def test_report_async_progress_hook() -> None:
    import ota_server

    # No-op when no async task is bound (synchronous calls must not raise).
    ota_server._report_async_progress(0.5, "ignored")
    captured: list[tuple[float, str]] = []
    token = ota_server._ASYNC_PROGRESS.set(lambda f, d: captured.append((f, d)))
    try:
        ota_server._report_async_progress(0.35, "Generating b-roll")
        ota_server._report_async_progress(0.7, "Rendering studio cut")
    finally:
        ota_server._ASYNC_PROGRESS.reset(token)
    assert captured == [(0.35, "Generating b-roll"), (0.7, "Rendering studio cut")]


def test_async_tts_reports_real_progress_to_ready(tmp_path: pathlib.Path) -> None:
    import time

    bundles_dir = tmp_path / "bundles"
    bundles_dir.mkdir(parents=True)
    prefs_file = tmp_path / "preferences.jsonl"
    renders_dir = tmp_path / "renders"
    renders_dir.mkdir(parents=True)

    def fake_tts_runner(text: str, out_wav: pathlib.Path) -> dict[str, object]:
        _write_sine_wav(out_wav, duration_sec=2.0)
        return {"text_len": len(text)}

    server = create_ota_server(
        host="127.0.0.1",
        port=0,
        bundles_root=bundles_dir,
        preferences_path=prefs_file,
        renders_dir=renders_dir,
        tts_runner=fake_tts_runner,
        quiet=True,
    )
    host, port = server.server_address[:2]
    t = threading.Thread(target=server.serve_forever, daemon=True)
    t.start()
    try:
        payload = json.dumps({"clauses": [{"id": "c1", "spoken_text": "Good evening from the studio."}]}).encode("utf-8")
        req = urllib.request.Request(
            f"http://{host}:{port}/v1/core/tts?async=1",
            data=payload,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req) as resp:
            accepted = json.loads(resp.read().decode("utf-8"))
        task_id = accepted["job_id"]
        deadline = time.time() + 20
        job = None
        while time.time() < deadline:
            with urllib.request.urlopen(f"http://{host}:{port}/v1/jobs/{task_id}") as resp:
                job = json.loads(resp.read().decode("utf-8"))
            if job["status"] in ("READY", "FAILED"):
                break
            time.sleep(0.05)
        assert job is not None and job["status"] == "READY"
        assert job["progress_fraction"] == 1.0
        assert job["stage_detail"] == "Ready"
    finally:
        server.shutdown()
        server.server_close()


def test_rybbit_shipping_gated_and_payload(monkeypatch: pytest.MonkeyPatch) -> None:
    import contextlib
    import io
    import logging

    import ota_server

    # No real network: the flusher's urlopen is stubbed.
    monkeypatch.setattr(
        ota_server.urllib.request,
        "urlopen",
        lambda *a, **k: contextlib.nullcontext(io.BytesIO(b'{"success":true}')),
    )

    # Gated off when env unset.
    monkeypatch.delenv("RYBBIT_URL", raising=False)
    monkeypatch.delenv("RYBBIT_SITE_ID", raising=False)
    before = len(logging.getLogger().handlers)
    ota_server._setup_rybbit_shipping()
    assert len(logging.getLogger().handlers) == before

    # Enabled when URL + site id set.
    monkeypatch.setenv("RYBBIT_URL", "https://logs.example.com")
    monkeypatch.setenv("RYBBIT_SITE_ID", "1")
    monkeypatch.setenv("RYBBIT_API_KEY", "k")
    ota_server._setup_rybbit_shipping()
    handlers = [h for h in logging.getLogger().handlers if isinstance(h, ota_server._RybbitLogHandler)]
    assert handlers
    h = handlers[-1]
    try:
        rec = logging.LogRecord(
            "voicestudio.ota_server", logging.INFO, __file__, 1, "tts ready job=%s", ("abc",), None
        )
        h.emit(rec)
        assert h._queue[-1]["site_id"] == "1"
        assert h._queue[-1]["type"] == "custom_event"
        assert h._queue[-1]["event_name"] == "rig_log"
        assert "tts ready job=abc" in h._queue[-1]["properties"]
        assert h._endpoint == "https://logs.example.com/api/track"

        err = logging.LogRecord("x", logging.ERROR, __file__, 1, "boom", None, None)
        h.emit(err)
        assert h._queue[-1]["type"] == "error"
        assert h._queue[-1]["event_name"] == "rig_error"
        assert "boom" in h._queue[-1]["properties"]
    finally:
        logging.getLogger().removeHandler(h)
