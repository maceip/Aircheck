"""Automated test suite for Mac Rig Phone-to-Mac Talking-Head Render Bridge in ota_server.py.

Covers:
  - POST /v1/render-video (ENROLLED_VOICE_CLONE + MIC_VOICEOVER, custom & default avatar, validation)
  - Background worker real persisted state transitions (QUEUED -> VOICE -> LIPSYNC -> READY)
  - GET /v1/jobs/{job_id} polling & persistence on disk
  - GET/HEAD /v1/renders/{filename} full + RFC 9110 Range streaming (200, 206, 416, video/mp4)
  - Strict path-traversal & symlink escape rejection (403 Forbidden)
  - Constitutional Law 2: real subprocess error propagation into FAILED (zero fabricated MP4/READY)
  - Dynamic v002 > v001 LoRA & LiteRT codec asset resolution
"""

from __future__ import annotations

import base64
import http.client
import json
import os
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from typing import Any, Callable

DAEMON_DIR = Path(__file__).resolve().parents[1]
if str(DAEMON_DIR) not in sys.path:
    sys.path.insert(0, str(DAEMON_DIR))

from ota_server import (  # noqa: E402
    RenderJobManager,
    create_ota_server,
    resolve_latest_synthesis_assets,
)
from tinker_voice_daemon import build_bundle_manifest  # noqa: E402


def _write_minimal_bundle(bundle_dir: Path, *, lora_version: int = 1) -> dict[str, Any]:
    files = {
        "s2_pro_4b_q4_k_m.gguf": b"GGUF" + b"\x01" * 64,
        f"voice_lora_v{lora_version:03d}.gguf": b"GGUF" + b"LORA" * 16,
        "codec_decoder_fp16.tflite": b"TFL3" + b"\x22" * 64,
        "sherpa_ctc_aligner_en.tar.gz": b"\x1f\x8b\x08\x00" + b"CTC" * 16,
        "wespeaker_resnet34.onnx": b"ONNX_WESPEAKER" * 8,
        "master_centroid.bin": b"\x00\x00\x80\x3f" * 256,
        "dnsmos_p808.onnx": b"ONNX_DNSMOS" * 8,
        "room_ir_44k.pcm": b"\x00\x00\x00\x3f" * 64,
        "room_tone_44k.pcm": b"\x00\x00\x80\x38" * 64,
        "lexicon.json": b'{"Aircheck":"test"}\n',
    }
    bundle_dir.mkdir(parents=True, exist_ok=True)
    for name, content in files.items():
        (bundle_dir / name).write_bytes(content)
    return build_bundle_manifest(
        bundle_dir=bundle_dir,
        bundle_version=f"v{lora_version:03d}",
        lora_version=lora_version,
    )


def _sample_mp4_bytes(size: int = 4096) -> bytes:
    """Minimal deterministic ISO BMFF ftyp+mdat byte pattern for Range streaming verification."""
    header = b"\x00\x00\x00\x18ftypisom\x00\x00\x02\x00isomiso2mp41"
    payload = bytes((i * 31 + 7) % 256 for i in range(max(0, size - len(header))))
    return header + payload


class TestLatestSynthesisAssetResolution(unittest.TestCase):
    def test_prefers_v002_over_v001_when_present(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            bundles = root / "bundles"
            _write_minimal_bundle(bundles / "v001", lora_version=1)

            lora_v1 = root / "lora-work-v001" / "peft_lora_adapter"
            lora_v1.mkdir(parents=True)
            (lora_v1 / "adapter_config.json").write_text('{"r": 32}\n', encoding="utf-8")
            (lora_v1 / "adapter_model.safetensors").write_bytes(b"SAFETENSORS_V1")

            resolved_v1 = resolve_latest_synthesis_assets(root, bundles)
            self.assertEqual(resolved_v1["lora_dir"], lora_v1.resolve())
            self.assertEqual(
                resolved_v1["codec_tflite"],
                (bundles / "v001" / "codec_decoder_fp16.tflite").resolve(),
            )
            self.assertEqual(
                resolved_v1["lora_gguf"],
                (bundles / "v001" / "voice_lora_v001.gguf").resolve(),
            )

            # Now install v002 adapter + v002 codec & gguf
            lora_v2 = root / "lora-work-v002" / "peft_lora_adapter"
            lora_v2.mkdir(parents=True)
            (lora_v2 / "adapter_config.json").write_text('{"r": 32}\n', encoding="utf-8")
            (lora_v2 / "adapter_model.safetensors").write_bytes(b"SAFETENSORS_V2")

            v2_bundle = bundles / "v002"
            v2_bundle.mkdir(parents=True)
            (v2_bundle / "codec_decoder_fp16.tflite").write_bytes(b"TFL3_V2" * 16)
            (v2_bundle / "voice_lora_v002.gguf").write_bytes(b"GGUF_V2" * 16)

            resolved_v2 = resolve_latest_synthesis_assets(root, bundles)
            self.assertEqual(resolved_v2["lora_dir"], lora_v2.resolve())
            self.assertEqual(
                resolved_v2["codec_tflite"],
                (v2_bundle / "codec_decoder_fp16.tflite").resolve(),
            )
            self.assertEqual(
                resolved_v2["lora_gguf"],
                (v2_bundle / "voice_lora_v002.gguf").resolve(),
            )


class TestRenderBridgeHttpServer(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.bundles_root = self.root / "bundles"
        self.renders_dir = self.root / "renders"
        self.prefs_path = self.root / "preferences.jsonl"
        _write_minimal_bundle(self.bundles_root / "v001", lora_version=1)

        self.default_avatar = self.root / "default_avatar.jpg"
        self.default_avatar.write_bytes(b"\xff\xd8\xff\xe0DEFAULT_AVATAR_JPEG\xff\xd9")
        (self.root / "secret_outside.txt").write_text("DO_NOT_LEAK\n", encoding="utf-8")

        self.server = None
        self._thread = None

    def tearDown(self) -> None:
        if self.server is not None:
            self.server.shutdown()
            self.server.server_close()
        if self._thread is not None:
            self._thread.join(timeout=5.0)
        self._tmp.cleanup()

    def _start_server(self, runner: Callable[..., dict[str, Any]] | None) -> int:
        self.server = create_ota_server(
            host="127.0.0.1",
            port=0,
            bundles_root=self.bundles_root,
            preferences_path=self.prefs_path,
            renders_dir=self.renders_dir,
            models_root=self.root,
            default_avatar_path=self.default_avatar,
            render_runner=runner,
            quiet=True,
        )
        port = self.server.server_address[1]
        self._thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self._thread.start()
        return port

    def _request(
        self,
        port: int,
        method: str,
        path: str,
        *,
        body: bytes | None = None,
        headers: dict[str, str] | None = None,
    ) -> tuple[int, dict[str, str], bytes]:
        conn = http.client.HTTPConnection("127.0.0.1", port, timeout=10.0)
        try:
            req_headers = dict(headers or {})
            if body is not None and "Content-Length" not in req_headers:
                req_headers["Content-Length"] = str(len(body))
            conn.request(method, path, body=body, headers=req_headers)
            resp = conn.getresponse()
            data = resp.read()
            resp_headers = {k.lower(): v for k, v in resp.getheaders()}
            return resp.status, resp_headers, data
        finally:
            conn.close()

    def _wait_for_job_status(
        self, port: int, job_id: str, target_statuses: set[str], timeout_sec: float = 5.0
    ) -> dict[str, Any]:
        deadline = time.monotonic() + timeout_sec
        last: dict[str, Any] = {}
        while time.monotonic() < deadline:
            status, _, body = self._request(port, "GET", f"/v1/jobs/{job_id}")
            self.assertEqual(status, 200)
            last = json.loads(body.decode("utf-8"))
            if last.get("status") in target_statuses:
                return last
            time.sleep(0.02)
        self.fail(f"Timed out waiting for job {job_id} to reach {target_statuses}; last={last}")

    def test_render_video_lifecycle_polling_and_mp4_range_streaming(self) -> None:
        mp4_payload = _sample_mp4_bytes(8192)
        enter_voice = threading.Event()
        release_voice = threading.Event()
        enter_lipsync = threading.Event()
        release_lipsync = threading.Event()
        captured_specs: list[dict[str, Any]] = []

        def staged_runner(
            spec: dict[str, Any], work_dir: Path, report_progress: Callable[..., None]
        ) -> dict[str, Any]:
            captured_specs.append(dict(spec))
            report_progress(
                status="VOICE",
                progress_fraction=0.25,
                stage_detail="Synthesizing voice (lora-work-v001)",
            )
            enter_voice.set()
            assert release_voice.wait(timeout=5.0)

            metrics = {
                "cer": 0.0,
                "wespeaker_cosine": 0.872,
                "dnsmos_p808": 4.18,
                "syllables_per_sec": 4.9,
            }
            report_progress(
                status="LIPSYNC",
                progress_fraction=0.65,
                stage_detail="Rendering talking-head video (wan3.0-video)",
                metrics=metrics,
            )
            enter_lipsync.set()
            assert release_lipsync.wait(timeout=5.0)

            out_mp4 = work_dir / "rendered.mp4"
            out_mp4.write_bytes(mp4_payload)
            return {
                "output_mp4": out_mp4,
                "duration_ms": 6780,
                "metrics": metrics,
            }

        port = self._start_server(staged_runner)

        # /health still backwards-compatible and exposes render_bridge
        status, _, body = self._request(port, "GET", "/health")
        self.assertEqual(status, 200)
        health = json.loads(body.decode("utf-8"))
        self.assertEqual(health["status"], "ok")
        self.assertEqual(health["lora_version"], 1)
        self.assertEqual(health["bundle_version"], "v001")
        self.assertTrue(health["render_bridge"])

        # Enqueue ENROLLED_VOICE_CLONE with custom JPEG photo
        custom_jpeg = b"\xff\xd8\xff\xe0CUSTOM_PHONE_PHOTO\xff\xd9"
        req_payload = {
            "script_id": "proj_abc123",
            "title": "Bare-Metal Render",
            "spoken_text": "We ripped out the cloud wrapper and shipped direct Mac rendering.",
            "voice_source": "ENROLLED_VOICE_CLONE",
            "photo_jpeg_base64": base64.b64encode(custom_jpeg).decode("ascii"),
            "resolution": "720P",
            "ratio": "9:16",
            "max_duration": 10,
        }
        status, _, body = self._request(
            port,
            "POST",
            "/v1/render-video",
            body=json.dumps(req_payload).encode("utf-8"),
            headers={"Content-Type": "application/json"},
        )
        self.assertEqual(status, 202)
        accepted = json.loads(body.decode("utf-8"))
        job_id = accepted["job_id"]
        self.assertEqual(accepted["status"], "QUEUED")

        # Observe VOICE transition
        self.assertTrue(enter_voice.wait(timeout=5.0))
        status, _, body = self._request(port, "GET", f"/v1/jobs/{job_id}")
        self.assertEqual(status, 200)
        voice_snap = json.loads(body.decode("utf-8"))
        self.assertEqual(voice_snap["status"], "VOICE")
        self.assertAlmostEqual(voice_snap["progress_fraction"], 0.25, places=3)
        self.assertIsNone(voice_snap["video_url"])
        self.assertIsNone(voice_snap["error_message"])
        release_voice.set()

        # Observe LIPSYNC transition
        self.assertTrue(enter_lipsync.wait(timeout=5.0))
        status, _, body = self._request(port, "GET", f"/v1/jobs/{job_id}")
        self.assertEqual(status, 200)
        lipsync_snap = json.loads(body.decode("utf-8"))
        self.assertEqual(lipsync_snap["status"], "LIPSYNC")
        self.assertAlmostEqual(lipsync_snap["progress_fraction"], 0.65, places=3)
        self.assertEqual(lipsync_snap["metrics"]["cer"], 0.0)
        release_lipsync.set()

        # Observe READY transition
        ready_snap = self._wait_for_job_status(port, job_id, {"READY"})
        self.assertEqual(ready_snap["status"], "READY")
        self.assertEqual(ready_snap["progress_fraction"], 1.0)
        self.assertEqual(ready_snap["video_url"], f"/v1/renders/{job_id}.mp4")
        self.assertEqual(ready_snap["duration_ms"], 6780)
        self.assertEqual(ready_snap["metrics"]["wespeaker_cosine"], 0.872)
        self.assertIsNone(ready_snap["error_message"])

        # Verify custom JPEG photo was written and passed to runner
        self.assertEqual(len(captured_specs), 1)
        saved_avatar = Path(captured_specs[0]["avatar_path"])
        self.assertNotEqual(saved_avatar, self.default_avatar)
        self.assertEqual(saved_avatar.read_bytes(), custom_jpeg)

        # Verify persisted job JSON on disk matches API
        persisted = json.loads((self.renders_dir / "jobs" / f"{job_id}.json").read_text(encoding="utf-8"))
        self.assertEqual(persisted["status"], "READY")
        self.assertEqual(persisted["video_url"], f"/v1/renders/{job_id}.mp4")

        # Verify full GET + HEAD on /v1/renders/{job_id}.mp4
        video_url = ready_snap["video_url"]
        status, hdrs, body = self._request(port, "HEAD", video_url)
        self.assertEqual(status, 200)
        self.assertEqual(hdrs["content-type"], "video/mp4")
        self.assertEqual(hdrs["accept-ranges"], "bytes")
        self.assertEqual(int(hdrs["content-length"]), len(mp4_payload))
        self.assertEqual(body, b"")

        status, hdrs, body = self._request(port, "GET", video_url)
        self.assertEqual(status, 200)
        self.assertEqual(hdrs["content-type"], "video/mp4")
        self.assertEqual(body, mp4_payload)

        # Verify RFC 9110 Range streaming (ExoPlayer progressive seek)
        status, hdrs, body = self._request(
            port, "GET", video_url, headers={"Range": "bytes=0-1023"}
        )
        self.assertEqual(status, 206)
        self.assertEqual(hdrs["content-type"], "video/mp4")
        self.assertEqual(hdrs["accept-ranges"], "bytes")
        self.assertEqual(hdrs["content-range"], f"bytes 0-1023/{len(mp4_payload)}")
        self.assertEqual(int(hdrs["content-length"]), 1024)
        self.assertEqual(body, mp4_payload[:1024])

        status, hdrs, body = self._request(
            port, "GET", video_url, headers={"Range": "bytes=4096-"}
        )
        self.assertEqual(status, 206)
        self.assertEqual(hdrs["content-range"], f"bytes 4096-{len(mp4_payload) - 1}/{len(mp4_payload)}")
        self.assertEqual(body, mp4_payload[4096:])

        status, hdrs, body = self._request(
            port, "GET", video_url, headers={"Range": "bytes=-64"}
        )
        self.assertEqual(status, 206)
        self.assertEqual(body, mp4_payload[-64:])

        # Out-of-bounds range -> 416
        status, hdrs, _ = self._request(
            port, "GET", video_url, headers={"Range": "bytes=999999-1000000"}
        )
        self.assertEqual(status, 416)
        self.assertEqual(hdrs["content-range"], f"bytes */{len(mp4_payload)}")

    def test_mic_voiceover_uses_uploaded_wav_and_default_avatar_fallback(self) -> None:
        wav_bytes = b"RIFF\x24\x00\x00\x00WAVEfmt " + b"\x00" * 32
        mp4_payload = _sample_mp4_bytes(2048)
        observed: dict[str, Any] = {}

        def mic_runner(
            spec: dict[str, Any], work_dir: Path, report_progress: Callable[..., None]
        ) -> dict[str, Any]:
            observed["voice_source"] = spec["voice_source"]
            observed["avatar_path"] = Path(spec["avatar_path"])
            observed["wav_bytes"] = Path(spec["audio_wav_path"]).read_bytes()
            report_progress(status="LIPSYNC", progress_fraction=0.6, stage_detail="Rendering wan3.0-video")
            out = work_dir / "mic_out.mp4"
            out.write_bytes(mp4_payload)
            return {"output_mp4": out, "duration_ms": 4200, "metrics": None}

        port = self._start_server(mic_runner)
        req_payload = {
            "script_id": "proj_mic_1",
            "title": "Mic Take",
            "spoken_text": "",
            "voice_source": "MIC_VOICEOVER",
            "audio_wav_base64": base64.b64encode(wav_bytes).decode("ascii"),
            "resolution": "720P",
            "ratio": "9:16",
            "max_duration": 10,
        }
        status, _, body = self._request(
            port,
            "POST",
            "/v1/render-video",
            body=json.dumps(req_payload).encode("utf-8"),
            headers={"Content-Type": "application/json"},
        )
        self.assertEqual(status, 202)
        job_id = json.loads(body.decode("utf-8"))["job_id"]
        ready = self._wait_for_job_status(port, job_id, {"READY"})

        self.assertEqual(ready["voice_source"], "MIC_VOICEOVER")
        self.assertEqual(ready["duration_ms"], 4200)
        self.assertIsNone(ready["metrics"])
        self.assertEqual(observed["voice_source"], "MIC_VOICEOVER")
        self.assertEqual(observed["avatar_path"], self.default_avatar)
        self.assertEqual(observed["wav_bytes"], wav_bytes)

    def test_validation_rejects_malformed_render_requests(self) -> None:
        port = self._start_server(lambda *_: {"output_mp4": None})

        invalid_payloads = [
            # Missing spoken_text for ENROLLED_VOICE_CLONE
            {"voice_source": "ENROLLED_VOICE_CLONE", "spoken_text": "   "},
            # Missing audio_wav_base64 for MIC_VOICEOVER
            {"voice_source": "MIC_VOICEOVER", "spoken_text": "hello"},
            # Invalid voice_source
            {"voice_source": "FAKE_CLOUD", "spoken_text": "hello"},
            # Invalid base64 photo
            {"voice_source": "ENROLLED_VOICE_CLONE", "spoken_text": "hello", "photo_jpeg_base64": "!!!not-base64!!!"},
            # Invalid resolution
            {"voice_source": "ENROLLED_VOICE_CLONE", "spoken_text": "hello", "resolution": "8K"},
            # Invalid ratio
            {"voice_source": "ENROLLED_VOICE_CLONE", "spoken_text": "hello", "ratio": "21:9"},
            # Invalid max_duration
            {"voice_source": "ENROLLED_VOICE_CLONE", "spoken_text": "hello", "max_duration": 0},
            {"voice_source": "ENROLLED_VOICE_CLONE", "spoken_text": "hello", "max_duration": True},
        ]
        for payload in invalid_payloads:
            status, _, _ = self._request(
                port,
                "POST",
                "/v1/render-video",
                body=json.dumps(payload).encode("utf-8"),
                headers={"Content-Type": "application/json"},
            )
            self.assertEqual(status, 400, f"Expected 400 for payload={payload}")

        # Unknown job & unknown render return 404
        status, _, _ = self._request(port, "GET", "/v1/jobs/job_does_not_exist")
        self.assertEqual(status, 404)
        status, _, _ = self._request(port, "GET", "/v1/renders/missing_render.mp4")
        self.assertEqual(status, 404)

    def test_path_traversal_and_symlink_escape_rejected_on_renders_and_jobs(self) -> None:
        self.renders_dir.mkdir(parents=True, exist_ok=True)
        # Create symlink inside renders_dir pointing outside renders_dir
        escape_link = self.renders_dir / "escape.mp4"
        os.symlink(self.root / "secret_outside.txt", escape_link)

        port = self._start_server(lambda *_: {"output_mp4": None})

        attack_paths = [
            "/v1/renders/../secret_outside.txt",
            "/v1/renders/%2e%2e%2fsecret_outside.txt",
            "/v1/renders/..%5csecret_outside.txt",
            "/v1/renders/nested/file.mp4",
            "/v1/renders/escape.mp4",
            "/v1/jobs/../secret_outside",
            "/v1/jobs/%2e%2e/secret_outside",
        ]
        for path in attack_paths:
            status, _, body = self._request(port, "GET", path)
            self.assertEqual(status, 403, f"Expected 403 for attack path {path}, got {status}: {body!r}")
            self.assertNotIn(b"DO_NOT_LEAK", body)

    def test_subprocess_error_propagates_to_failed_without_fabricating_video(self) -> None:
        def failing_runner(
            spec: dict[str, Any], work_dir: Path, report_progress: Callable[..., None]
        ) -> dict[str, Any]:
            report_progress(status="VOICE", progress_fraction=0.25, stage_detail="Running synth")
            raise RuntimeError("bl video ref failed (rc=1): upstream quota exhausted")

        port = self._start_server(failing_runner)
        status, _, body = self._request(
            port,
            "POST",
            "/v1/render-video",
            body=json.dumps(
                {
                    "script_id": "proj_err",
                    "title": "Failing Job",
                    "spoken_text": "Trigger real subprocess error propagation.",
                    "voice_source": "ENROLLED_VOICE_CLONE",
                }
            ).encode("utf-8"),
            headers={"Content-Type": "application/json"},
        )
        self.assertEqual(status, 202)
        job_id = json.loads(body.decode("utf-8"))["job_id"]

        failed = self._wait_for_job_status(port, job_id, {"FAILED"})
        self.assertEqual(failed["status"], "FAILED")
        self.assertIn("bl video ref failed", failed["error_message"])
        self.assertIsNone(failed["video_url"])
        self.assertFalse((self.renders_dir / f"{job_id}.mp4").exists())

        status, _, _ = self._request(port, "GET", f"/v1/renders/{job_id}.mp4")
        self.assertEqual(status, 404)

    def test_default_runner_missing_avatar_transitions_to_failed_not_ready(self) -> None:
        # Uses real default runner (render_runner=None) with a missing default avatar
        self.default_avatar.unlink()
        port = self._start_server(None)

        status, _, body = self._request(
            port,
            "POST",
            "/v1/render-video",
            body=json.dumps(
                {
                    "script_id": "proj_missing_avatar",
                    "title": "Missing Avatar",
                    "spoken_text": "Verify missing avatar fails honestly.",
                    "voice_source": "ENROLLED_VOICE_CLONE",
                }
            ).encode("utf-8"),
            headers={"Content-Type": "application/json"},
        )
        self.assertEqual(status, 202)
        job_id = json.loads(body.decode("utf-8"))["job_id"]

        failed = self._wait_for_job_status(port, job_id, {"FAILED"})
        self.assertEqual(failed["status"], "FAILED")
        self.assertIn("Avatar image not found", failed["error_message"])
        self.assertIsNone(failed["video_url"])
        self.assertFalse((self.renders_dir / f"{job_id}.mp4").exists())

    def test_default_avatar_renders_list_and_ai_assist_validation(self) -> None:
        self.renders_dir.mkdir(parents=True, exist_ok=True)
        (self.renders_dir / "breaking_demo.mp4").write_bytes(_sample_mp4_bytes(4096))
        port = self._start_server(lambda *_: {"output_mp4": None})

        # Default avatar serves exact bytes as image/jpeg
        status, hdrs, body = self._request(port, "GET", "/v1/default-avatar")
        self.assertEqual(status, 200)
        self.assertEqual(hdrs["content-type"], "image/jpeg")
        self.assertEqual(body, self.default_avatar.read_bytes())

        # /v1/renders lists existing MP4s with real URLs
        status, _, body = self._request(port, "GET", "/v1/renders")
        self.assertEqual(status, 200)
        listed = json.loads(body.decode("utf-8"))["renders"]
        self.assertEqual(len(listed), 1)
        self.assertEqual(listed[0]["id"], "breaking_demo")
        self.assertEqual(listed[0]["video_url"], "/v1/renders/breaking_demo.mp4")
        self.assertEqual(listed[0]["thumb_url"], "/v1/render-thumbs/breaking_demo.jpg")

        # /v1/ai-assist rejects invalid mode or empty text honestly with 400
        status, _, _ = self._request(
            port,
            "POST",
            "/v1/ai-assist",
            body=json.dumps({"mode": "invalid_mode", "text": "hello"}).encode("utf-8"),
            headers={"Content-Type": "application/json"},
        )
        self.assertEqual(status, 400)
        status, _, _ = self._request(
            port,
            "POST",
            "/v1/ai-assist",
            body=json.dumps({"mode": "flesh_out_story", "text": "   "}).encode("utf-8"),
            headers={"Content-Type": "application/json"},
        )
        self.assertEqual(status, 400)


if __name__ == "__main__":
    unittest.main()
