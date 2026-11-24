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
