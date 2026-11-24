"""Automated unit and HTTP integration tests for the Tactile Mobile Drafting Engine passes.

Covers:
  - Bailian CLI output parsing (both compact ``content`` and OpenAI ``choices[0].message.content``)
  - Whisper model resolution preferring fastest existing ggml model & fail-fast when absent
  - Style constraint prompt injection & plain ASCII sanitization
  - Strict ``<lock>...</lock>`` token conservation and deterministic rejection on violation
  - All 6 HTTP endpoints wired into ``ota_server.py``:
      1. POST /v1/draft/transcribe
      2. POST /v1/draft/angles
      3. POST /v1/draft/synthesize (3-slot fanout + single-slot refresh)
      4. POST /v1/draft/brush-swap
      5. POST /v1/draft/seam-heal
      6. POST /v1/draft/density
  - Strict 400 validation on malformed payloads & 502 error propagation on subprocess failure
  - Full coexistence with existing ``/health``, OTA, renders, default-avatar, and ``/v1/ai-assist`` routes
"""

from __future__ import annotations

import base64
import http.client
import io
import json
import struct
import sys
import tempfile
import threading
import unittest
import wave
from pathlib import Path
from typing import Any, Callable

DAEMON_DIR = Path(__file__).resolve().parents[1]
if str(DAEMON_DIR) not in sys.path:
    sys.path.insert(0, str(DAEMON_DIR))

from drafting_passes import (  # noqa: E402
    DraftPassEngine,
    build_lock_preserving_fallback,
    extract_locked_spans,
    parse_bl_chat_content,
    resolve_whisper_model,
    sanitize_plain_ascii,
    validate_and_measure_wav,
    verify_lock_parity,
)
from ota_server import create_ota_server  # noqa: E402
from tinker_voice_daemon import build_bundle_manifest  # noqa: E402


def _make_pcm_wav(duration_ms: int = 1000, sample_rate: int = 16000) -> bytes:
    nframes = int(round(sample_rate * (duration_ms / 1000.0)))
    buf = io.BytesIO()
    with wave.open(buf, "wb") as wf:
        wf.setnchannels(1)
        wf.setsampwidth(2)
        wf.setframerate(sample_rate)
        # Deterministic quiet 440Hz-ish sawtooth PCM samples (non-empty valid PCM WAV)
        samples = [((i * 137) % 1000) - 500 for i in range(nframes)]
        wf.writeframes(struct.pack(f"<{nframes}h", *samples))
    return buf.getvalue()


def _write_minimal_bundle(bundle_dir: Path) -> None:
    files = {
        "s2_pro_4b_q4_k_m.gguf": b"GGUF" + b"\x01" * 64,
        "voice_lora_v001.gguf": b"GGUF" + b"LORA" * 16,
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
    build_bundle_manifest(bundle_dir=bundle_dir, bundle_version="v001", lora_version=1)


class TestDraftingPassHelpers(unittest.TestCase):
    def test_parse_bl_chat_content_supports_both_envelopes_and_ignores_warnings(self) -> None:
        compact_stdout = (
            '{"content":"{\\"text\\":\\"Plain output.\\"}","finish_reason":"stop"}\n'
            '{"warning":{"message":"Update available"}}\n'
        )
        self.assertEqual(parse_bl_chat_content(compact_stdout), '{"text":"Plain output."}')

        openai_stdout = json.dumps(
            {
                "id": "chatcmpl-123",
                "choices": [
                    {
                        "index": 0,
                        "message": {"role": "assistant", "content": '{"alternatives":["cut","trim"]}'},
                    }
                ],
            }
        )
        self.assertEqual(parse_bl_chat_content(openai_stdout), '{"alternatives":["cut","trim"]}')

        error_stdout = '{"error":{"code":1,"message":"Model not exist."}}'
        with self.assertRaises(RuntimeError) as ctx:
            parse_bl_chat_content(error_stdout)
        self.assertIn("Model not exist", str(ctx.exception))

    def test_resolve_whisper_model_prefers_fastest_existing_and_fails_fast_if_none(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            models_dir = Path(tmp)
            with self.assertRaises(FileNotFoundError):
                resolve_whisper_model(models_dir=models_dir)

            medium = models_dir / "ggml-medium-q5_0.bin"
            medium.write_bytes(b"MEDIUM" * 16)
            self.assertEqual(resolve_whisper_model(models_dir=models_dir), medium)

            base_en = models_dir / "ggml-base.en.bin"
            base_en.write_bytes(b"BASE_EN" * 16)
            self.assertEqual(resolve_whisper_model(models_dir=models_dir), base_en)

    def test_sanitize_plain_ascii_removes_em_dashes_curly_quotes_and_throat_clearing(self) -> None:
        raw = "Here\u2019s the thing: local ASR runs in 700ms \u2014 zero cloud wait \u2013 and costs $0\u2026"
        cleaned = sanitize_plain_ascii(raw)
        self.assertTrue(cleaned.isascii())
        self.assertNotIn("\u2014", cleaned)
        self.assertNotIn("\u2013", cleaned)
        self.assertNotIn("Here's the thing", cleaned)
        self.assertEqual(cleaned, "Local ASR runs in 700ms, zero cloud wait, and costs $0...")

        # Digit ranges keep plain ASCII hyphen
        self.assertEqual(sanitize_plain_ascii("Latency dropped 10\u201320ms."), "Latency dropped 10-20ms.")

    def test_wav_validation_measures_real_duration_and_rejects_corrupt_inputs(self) -> None:
        wav_bytes = _make_pcm_wav(duration_ms=1500, sample_rate=16000)
        self.assertEqual(validate_and_measure_wav(wav_bytes), 1500)

        # Truncated 44-byte header claiming frames without PCM data -> ValueError
        header_only = wav_bytes[:44]
        with self.assertRaises(ValueError):
            validate_and_measure_wav(header_only)

        # Non-WAV bytes -> ValueError
        with self.assertRaises(ValueError):
            validate_and_measure_wav(b"NOT_A_WAV_FILE" * 10)

    def test_lock_parity_verification_and_fallback_conservation(self) -> None:
        marked = "We built <lock>local Whisper</lock> and <lock>zero cloud queues</lock>."
        spans = extract_locked_spans(marked)
        self.assertEqual(spans, ["local Whisper", "zero cloud queues"])

        self.assertTrue(verify_lock_parity(spans, "Now local Whisper ships with zero cloud queues."))
        self.assertTrue(verify_lock_parity(spans, "<lock>local Whisper</lock> beats <lock>zero cloud queues</lock>"))
        # Case alteration or missing span fails parity
        self.assertFalse(verify_lock_parity(spans, "Now local whisper ships with zero cloud queues."))
        self.assertFalse(verify_lock_parity(spans, "Now local Whisper ships with no cloud queues."))
        # Reversed order fails parity
        self.assertFalse(verify_lock_parity(spans, "zero cloud queues before local Whisper."))

        # Fallback preserves locked spans character-for-character even on strike_cut / reorder
        reorder_marked = "Consequently, <lock>local Whisper</lock> beat cloud ASR."
        fallback = build_lock_preserving_fallback(reorder_marked, operation="reorder")
        self.assertEqual(fallback, "local Whisper beat cloud ASR.")
        self.assertTrue(verify_lock_parity(extract_locked_spans(reorder_marked), fallback))

        # Throat-clearing before a locked span is stripped cleanly without leading whitespace
        from drafting_passes import sanitize_preserving_locks
        cleaned_locked = sanitize_preserving_locks(
            "Here's the thing: local Whisper beat cloud ASR.",
            ["local Whisper"],
        )
        self.assertEqual(cleaned_locked, "local Whisper beat cloud ASR.")


class TestDraftingPassesHttpServer(unittest.TestCase):
    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.root = Path(self._tmp.name)
        self.bundles_root = self.root / "bundles"
        self.renders_dir = self.root / "renders"
        self.prefs_path = self.root / "preferences.jsonl"
        _write_minimal_bundle(self.bundles_root / "v001")

        self.default_avatar = self.root / "default_avatar.jpg"
        self.default_avatar.write_bytes(b"\xff\xd8\xff\xe0AVATAR\xff\xd9")

        self.server = None
        self._thread = None

    def tearDown(self) -> None:
        if self.server is not None:
            self.server.shutdown()
            self.server.server_close()
        if self._thread is not None:
            self._thread.join(timeout=5.0)
        self._tmp.cleanup()

    def _start_server(
        self,
        *,
        llm_caller: Callable[[str, str], str] | None = None,
        transcribe_runner: Callable[[Path, Path, Path], str] | None = None,
        max_lock_retries: int = 1,
    ) -> int:
        engine = DraftPassEngine(
            llm_caller=llm_caller,
            transcribe_runner=transcribe_runner,
            max_lock_retries=max_lock_retries,
        )
        self.server = create_ota_server(
            host="127.0.0.1",
            port=0,
            bundles_root=self.bundles_root,
            preferences_path=self.prefs_path,
            renders_dir=self.renders_dir,
            models_root=self.root,
            default_avatar_path=self.default_avatar,
            render_runner=lambda *_: {"output_mp4": None},
            draft_engine=engine,
            quiet=True,
        )
        port = self.server.server_address[1]
        self._thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self._thread.start()
        return port

    def _post_json(
        self, port: int, path: str, payload: dict[str, Any]
    ) -> tuple[int, dict[str, Any]]:
        body = json.dumps(payload).encode("utf-8")
        conn = http.client.HTTPConnection("127.0.0.1", port, timeout=10.0)
        try:
            conn.request(
                "POST",
                path,
                body=body,
                headers={"Content-Type": "application/json", "Content-Length": str(len(body))},
            )
            resp = conn.getresponse()
            raw = resp.read()
            return resp.status, json.loads(raw.decode("utf-8"))
        finally:
            conn.close()

    def _get(self, port: int, path: str) -> tuple[int, bytes]:
        conn = http.client.HTTPConnection("127.0.0.1", port, timeout=10.0)
        try:
            conn.request("GET", path)
            resp = conn.getresponse()
            return resp.status, resp.read()
        finally:
            conn.close()

    def test_transcribe_endpoint_validates_wav_cleans_ascii_and_propagates_errors(self) -> None:
        observed_wav_sizes: list[int] = []

        def fake_whisper(_bin: Path, _model: Path, wav_path: Path) -> str:
            observed_wav_sizes.append(wav_path.stat().st_size)
            return "  [BLANK_AUDIO] We cut latency from 12s to 700ms \u2014 locally.\n"

        port = self._start_server(transcribe_runner=fake_whisper)
        wav_bytes = _make_pcm_wav(duration_ms=2350, sample_rate=16000)

        status, data = self._post_json(
            port,
            "/v1/draft/transcribe",
            {"audio_wav_base64": base64.b64encode(wav_bytes).decode("ascii")},
        )
        self.assertEqual(status, 200)
        self.assertEqual(data["transcript"], "We cut latency from 12s to 700ms, locally.")
        self.assertTrue(data["transcript"].isascii())
        self.assertEqual(data["duration_ms"], 2350)
        self.assertEqual(observed_wav_sizes, [len(wav_bytes)])

        # Invalid/empty WAV inputs -> 400
        bad_payloads = [
            {},
            {"audio_wav_base64": ""},
            {"audio_wav_base64": "!!!not-base64!!!"},
            {"audio_wav_base64": base64.b64encode(b"plain text not wav" * 4).decode("ascii")},
            {"audio_wav_base64": base64.b64encode(wav_bytes[:44]).decode("ascii")},
        ]
        for bad in bad_payloads:
            st, err = self._post_json(port, "/v1/draft/transcribe", bad)
            self.assertEqual(st, 400, f"Expected 400 for {bad}, got {st}: {err}")

    def test_transcribe_subprocess_failure_returns_502_never_fabricates(self) -> None:
        def failing_whisper(_bin: Path, _model: Path, _wav: Path) -> str:
            raise RuntimeError("whisper-cli failed (rc=1): model corrupted")

        port = self._start_server(transcribe_runner=failing_whisper)
        wav_bytes = _make_pcm_wav(duration_ms=500)
        status, data = self._post_json(
            port,
            "/v1/draft/transcribe",
            {"audio_wav_base64": base64.b64encode(wav_bytes).decode("ascii")},
        )
        self.assertEqual(status, 502)
        self.assertIn("whisper-cli failed", data["detail"])

    def test_angles_endpoint_enforces_style_prompt_and_exact_schema(self) -> None:
        captured_prompts: list[tuple[str, str]] = []

        def fake_llm(sys_prompt: str, user_prompt: str) -> str:
            captured_prompts.append((sys_prompt, user_prompt))
            return json.dumps(
                {
                    "extracted_angles": [
                        {
                            "id": "ex_1",
                            "label": "Local Whisper cuts latency",
                            "preview": "Running ggml locally drops round-trip wait below 1 second.",
                        },
                        {
                            "id": "ex_2",
                            "label": "No cloud queue stalls",
                            "preview": "Direct LAN requests avoid third-party rate limits.",
                        },
                    ],
                    "blindspots": [
                        {
                            "id": "bs_1",
                            "label": "Thermal throttle failure",
                            "preview": "Long continuous sessions can heat mobile batteries and degrade capture.",
                        },
                        {
                            "id": "bs_2",
                            "label": "LAN unreachable cost",
                            "preview": "Leaving Wi-Fi breaks direct rig calls without Tailscale fallback.",
                        },
                        {
                            "id": "bs_3",
                            "label": "Invert local speed premise",
                            "preview": "Batching cloud requests can beat local models when uploading multi-minute audio.",
                        },
                    ],
                }
            )

        port = self._start_server(llm_caller=fake_llm)
        status, data = self._post_json(
            port,
            "/v1/draft/angles",
            {
                "seed_transcript": "Running Whisper on our Mac over LAN dropped transcription under one second.",
                "steering_constraints": ["keep it concrete", "no hype"],
            },
        )
        self.assertEqual(status, 200)
        self.assertEqual(set(data.keys()), {"extracted_angles", "blindspots", "llm_meta"})
        self.assertEqual(set(data["llm_meta"].keys()), {"provider", "model", "is_local"})
        self.assertEqual(len(data["extracted_angles"]), 2)
        self.assertEqual(len(data["blindspots"]), 3)
        for item in data["extracted_angles"] + data["blindspots"]:
            self.assertEqual(set(item.keys()), {"id", "label", "preview"})
            self.assertTrue(item["label"].isascii())
            self.assertTrue(item["preview"].isascii())

        # Verify style bans and steering constraints were passed to LLM
        sys_p, user_p = captured_prompts[0]
        self.assertIn("BAN metaphors", sys_p)
        self.assertIn("BAN rhetorical questions", sys_p)
        self.assertIn("BAN em-dashes", sys_p)
        self.assertIn("throat-clearing", sys_p)
        self.assertIn("keep it concrete", user_p)

        # Empty seed -> 400
        st, _ = self._post_json(port, "/v1/draft/angles", {"seed_transcript": "   "})
        self.assertEqual(st, 400)

    def test_synthesize_three_slot_fanout_and_single_slot_distinct_refresh(self) -> None:
        calls: list[str] = []

        def fake_llm(sys_prompt: str, user_prompt: str) -> str:
            calls.append(user_prompt)
            if "Write exactly 3 distinct" in user_prompt:
                return json.dumps(
                    {
                        "candidates": [
                            {
                                "posture_label": "DIRECT",
                                "text": "Here's the thing: local transcription on LAN finishes before the user stops pacing.",
                            },
                            {
                                "posture_label": "RECEIPT",
                                "text": "Measured 738ms on M5 Max Metal for ggml-medium-q5_0 versus 4.2s cloud upload.",
                            },
                            {
                                "posture_label": "CONTRARIAN",
                                "text": "Fast ASR is useless if your drafting UI drops user phrasing during rewrites.",
                            },
                        ]
                    }
                )
            # Single-slot refresh: first attempt repeats existing draft; second attempt returns fresh one.
            if "CORRECTION:" not in user_prompt:
                return json.dumps(
                    {
                        "candidates": [
                            {
                                "posture_label": "RECEIPT",
                                "text": "Measured 738ms on M5 Max Metal for ggml-medium-q5_0 versus 4.2s cloud upload.",
                            }
                        ]
                    }
                )
            return json.dumps(
                {
                    "candidates": [
                        {
                            "posture_label": "RECEIPT",
                            "text": "Zero API egress fees and 539MB on disk gave us sub-second local transcription.",
                        }
                    ]
                }
            )

        port = self._start_server(llm_caller=fake_llm)

        # Fanout with slot=null -> 3 postures
        status, fanout = self._post_json(
            port,
            "/v1/draft/synthesize",
            {
                "seed_transcript": "Local whisper on M5 Max ran in 738ms.",
                "selected_angles": ["Local Whisper cuts latency"],
                "steering_constraints": ["cite numbers"],
                "slot": None,
                "existing_drafts": [],
            },
        )
        self.assertEqual(status, 200)
        self.assertEqual(len(fanout["candidates"]), 3)
        self.assertEqual(
            [c["posture_label"] for c in fanout["candidates"]],
            ["DIRECT", "RECEIPT", "CONTRARIAN"],
        )
        for cand in fanout["candidates"]:
            self.assertLessEqual(len(cand["text"]), 280)
            self.assertTrue(cand["text"].isascii())
            self.assertNotIn("Here's the thing", cand["text"])

        # Slot=1 refresh -> 1 candidate distinct from existing_drafts
        existing = [c["text"] for c in fanout["candidates"]]
        status, single = self._post_json(
            port,
            "/v1/draft/synthesize",
            {
                "seed_transcript": "Local whisper on M5 Max ran in 738ms.",
                "selected_angles": ["Local Whisper cuts latency"],
                "steering_constraints": [],
                "slot": 1,
                "existing_drafts": existing,
            },
        )
        self.assertEqual(status, 200)
        self.assertEqual(len(single["candidates"]), 1)
        self.assertEqual(single["candidates"][0]["posture_label"], "RECEIPT")
        self.assertNotIn(single["candidates"][0]["text"], existing)

        # Invalid slot -> 400
        for bad_slot in (3, -1, True, "0"):
            st, _ = self._post_json(
                port,
                "/v1/draft/synthesize",
                {"seed_transcript": "Valid seed.", "slot": bad_slot},
            )
            self.assertEqual(st, 400, f"Expected 400 for slot={bad_slot!r}")

    def test_brush_swap_returns_two_alternatives_plus_clean_cut_empty_string(self) -> None:
        def fake_llm(_sys: str, _user: str) -> str:
            return json.dumps({"alternatives": ["“cut”", "dropped", "trimmed"]})

        port = self._start_server(llm_caller=fake_llm)
        status, data = self._post_json(
            port,
            "/v1/draft/brush-swap",
            {
                "marked_text": "We <replace>dramatically reduced</replace> wait times on <lock>LAN :8443</lock>.",
                "steering_constraints": ["terse verbs"],
            },
        )
        self.assertEqual(status, 200)
        self.assertEqual(set(data.keys()), {"alternatives", "llm_meta"})
        self.assertEqual(data["alternatives"], ["cut", "dropped", ""])
        self.assertEqual(set(data["llm_meta"].keys()), {"provider", "model", "is_local"})

        # Missing or empty <replace> -> 400
        for bad_marked in (
            "No replace span here.",
            "Empty <replace>   </replace> span.",
            "Two <replace>a</replace> and <replace>b</replace> spans.",
        ):
            st, _ = self._post_json(port, "/v1/draft/brush-swap", {"marked_text": bad_marked})
            self.assertEqual(st, 400, f"Expected 400 for marked_text={bad_marked!r}")

    def test_seam_heal_preserves_locks_and_rejects_model_lock_violations(self) -> None:
        # Case 1: LLM preserves every locked span verbatim
        def obeying_llm(_sys: str, _user: str) -> str:
            return json.dumps(
                {
                    "text": "Local Whisper ran in 738ms \u2014 and zero cloud queues stalled @ryan."
                }
            )

        port = self._start_server(llm_caller=obeying_llm)
        marked = "<lock>Local Whisper</lock> ran in 738ms, and <lock>zero cloud queues</lock> stalled @ryan."
        status, data = self._post_json(
            port,
            "/v1/draft/seam-heal",
            {
                "marked_text": marked,
                "operation": "weave_mention",
                "steering_constraints": [],
            },
        )
        self.assertEqual(status, 200)
        self.assertTrue(data["lock_parity_preserved"])
        self.assertIn("Local Whisper", data["text"])
        self.assertIn("zero cloud queues", data["text"])
        self.assertTrue(data["text"].isascii())
        self.assertNotIn("\u2014", data["text"])

        # Invalid operation -> 400
        st, _ = self._post_json(
            port,
            "/v1/draft/seam-heal",
            {"marked_text": marked, "operation": "invented_op"},
        )
        self.assertEqual(st, 400)
        self.tearDown()

        # Case 2: LLM violates lock parity on every attempt -> deterministically REJECTED
        self.setUp()
        violation_calls = {"count": 0}

        def violating_llm(_sys: str, _user: str) -> str:
            violation_calls["count"] += 1
            return json.dumps(
                {
                    "text": "On-device ASR finished fast with no external queue wait."
                }
            )

        port = self._start_server(llm_caller=violating_llm, max_lock_retries=1)
        strike_marked = "We cut the proxy, and <lock>Local Whisper ran in 738ms</lock>."
        status, data = self._post_json(
            port,
            "/v1/draft/seam-heal",
            {
                "marked_text": strike_marked,
                "operation": "strike_cut",
                "steering_constraints": [],
            },
        )
        self.assertEqual(status, 200)
        self.assertEqual(violation_calls["count"], 2)  # Initial attempt + 1 corrective retry
        self.assertTrue(data["lock_parity_preserved"])
        self.assertIn("Local Whisper ran in 738ms", data["text"])
        self.assertNotIn("On-device ASR", data["text"])

    def test_density_conserves_locks_validates_bounds_and_rejects_violations(self) -> None:
        # Case 1: LLM violates lock on attempt 1, obeys on retry attempt 2
        attempts = {"n": 0}

        def retry_recovering_llm(_sys: str, _user: str) -> str:
            attempts["n"] += 1
            if attempts["n"] == 1:
                return json.dumps({"text": "Our local stack saves money."})  # drops locked span!
            return json.dumps({"text": "Direct LAN calls keep <lock>738ms latency</lock> steady."})

        port = self._start_server(llm_caller=retry_recovering_llm, max_lock_retries=1)
        marked = "Making direct LAN calls to the Mac keeps <lock>738ms latency</lock> very steady every time."
        status, data = self._post_json(
            port,
            "/v1/draft/density",
            {"marked_text": marked, "delta_percent": -20, "steering_constraints": []},
        )
        self.assertEqual(status, 200)
        self.assertEqual(attempts["n"], 2)
        self.assertTrue(data["lock_parity_preserved"])
        self.assertEqual(data["text"], "Direct LAN calls keep 738ms latency steady.")
        self.assertNotIn("<lock>", data["text"])

        # Bounds validation: -25..15 inclusive, reject outside or bool
        for bad_delta in (-26, 16, True, "10", None):
            st, _ = self._post_json(
                port,
                "/v1/draft/density",
                {"marked_text": marked, "delta_percent": bad_delta},
            )
            self.assertEqual(st, 400, f"Expected 400 for delta_percent={bad_delta!r}")

        # Boundary values -25 and 15 accepted
        for ok_delta in (-25, 15):
            attempts["n"] = 1  # Return compliant text on first call
            st, ok_data = self._post_json(
                port,
                "/v1/draft/density",
                {"marked_text": marked, "delta_percent": ok_delta},
            )
            self.assertEqual(st, 200)
            self.assertTrue(ok_data["lock_parity_preserved"])
            self.assertIn("738ms latency", ok_data["text"])

        # Case 2: Persistent violation falls back without mutating locked words
        self.tearDown()
        self.setUp()
        port = self._start_server(
            llm_caller=lambda _s, _u: json.dumps({"text": "Completely rewritten text."}),
            max_lock_retries=1,
        )
        status, data = self._post_json(
            port,
            "/v1/draft/density",
            {
                "marked_text": "Keep <lock>exact user phrasing</lock> intact when tightening.",
                "delta_percent": -15,
            },
        )
        self.assertEqual(status, 200)
        self.assertTrue(data["lock_parity_preserved"])
        self.assertIn("exact user phrasing", data["text"])
        self.assertNotIn("Completely rewritten", data["text"])

    def test_existing_ota_and_render_endpoints_remain_intact(self) -> None:
        port = self._start_server(
            llm_caller=lambda _s, _u: json.dumps({"alternatives": ["trim", "cut"]})
        )

        st, body = self._get(port, "/health")
        self.assertEqual(st, 200)
        health = json.loads(body.decode("utf-8"))
        self.assertEqual(health["status"], "ok")
        self.assertTrue(health["render_bridge"])

        st, body = self._get(port, "/v1/bundles/latest/manifest.json")
        self.assertEqual(st, 200)
        self.assertEqual(json.loads(body.decode("utf-8"))["bundle_version"], "v001")

        st, body = self._get(port, "/v1/renders")
        self.assertEqual(st, 200)
        self.assertEqual(json.loads(body.decode("utf-8")), {"renders": []})

        st, body = self._get(port, "/v1/default-avatar")
        self.assertEqual(st, 200)
        self.assertEqual(body, self.default_avatar.read_bytes())

    def test_rewrite_endpoint_and_llm_overrides(self) -> None:
        def fake_llm(_sys: str, user: str) -> str:
            if "seed_clean" in user or "raw voice/text seed" in user:
                return json.dumps({"text": "Local Whisper ran in 738ms with zero cloud wait."})
            return json.dumps({"text": "Cut <lock>738ms latency</lock> cleanly."})

        port = self._start_server(llm_caller=fake_llm)

        st, data = self._post_json(
            port,
            "/v1/draft/rewrite",
            {
                "text": "um yeah local whisper ran in 738ms with no cloud wait",
                "mode": "seed_clean",
                "llm_provider": "local_mlx",
                "llm_model": "default_model",
            },
        )
        self.assertEqual(st, 200)
        self.assertTrue(data["lock_parity_preserved"])
        self.assertEqual(data["llm_meta"], {"provider": "local_mlx", "model": "default_model", "is_local": True})

        st, data = self._post_json(
            port,
            "/v1/draft/rewrite",
            {
                "text": "Please cut <lock>738ms latency</lock> cleanly right now.",
                "mode": "cut_polish",
                "llm_provider": "remote_openai",
                "llm_model": "gpt-4.1-mini",
            },
        )
        self.assertEqual(st, 200)
        self.assertIn("738ms latency", data["text"])
        self.assertEqual(data["llm_meta"], {"provider": "remote_openai", "model": "gpt-4.1-mini", "is_local": False})

        # Invalid llm_provider / empty llm_model -> 400
        st, _ = self._post_json(
            port,
            "/v1/draft/rewrite",
            {"text": "Hello world.", "llm_provider": "bogus_provider"},
        )
        self.assertEqual(st, 400)
        st, _ = self._post_json(
            port,
            "/v1/draft/angles",
            {"seed_transcript": "Valid seed.", "llm_model": "   "},
        )
        self.assertEqual(st, 400)

    def test_locked_pass_never_masks_empty_or_failed_llm_with_fallback(self) -> None:
        # 1. Empty text on locked seam-heal MUST return 502 (never build_lock_preserving_fallback)
        port = self._start_server(llm_caller=lambda _s, _u: json.dumps({"text": "   "}))
        st, err = self._post_json(
            port,
            "/v1/draft/seam-heal",
            {
                "marked_text": "We cut the rest, and <lock>Local Whisper ran in 738ms</lock>.",
                "operation": "strike_cut",
                "llm_provider": "local_mlx",
            },
        )
        self.assertEqual(st, 502)
        self.assertEqual(err["provider"], "local_mlx")
        self.assertEqual(err["stage"], "draft.seam_heal")
        self.tearDown()

        # 2. Non-ASCII dash only text (sanitizes to empty) on unlocked density MUST return 502 (never fall through)
        self.setUp()
        port = self._start_server(llm_caller=lambda _s, _u: json.dumps({"text": "\u2014"}))
        st, err = self._post_json(
            port,
            "/v1/draft/density",
            {
                "marked_text": "Regular unlocked prose sentence here.",
                "delta_percent": -10,
                "llm_provider": "remote_openai",
            },
        )
        self.assertEqual(st, 502)
        self.assertEqual(err["provider"], "remote_openai")
        self.assertEqual(err["stage"], "draft.density")
        self.tearDown()

        # 3. Non-JSON / provider error on locked rewrite MUST return 502 with provider & stage
        self.setUp()
        port = self._start_server(llm_caller=lambda _s, _u: "not valid json at all")
        st, err = self._post_json(
            port,
            "/v1/draft/rewrite",
            {
                "text": "Keep <lock>exact words</lock> safe.",
                "mode": "cut_polish",
                "llm_provider": "local_ollama",
            },
        )
        self.assertEqual(st, 502)
        self.assertEqual(err["provider"], "local_ollama")
        self.assertEqual(err["stage"], "draft.rewrite")

    def test_locked_emdash_only_returns_502_never_200_fallback(self) -> None:
        for endpoint, payload, expected_stage in (
            (
                "/v1/draft/seam-heal",
                {
                    "marked_text": "Cut here, and <lock>Exact locked claim</lock> stays.",
                    "operation": "strike_cut",
                    "llm_provider": "remote_openai",
                },
                "draft.seam_heal",
            ),
            (
                "/v1/draft/density",
                {
                    "marked_text": "Tighten prose around <lock>Exact locked claim</lock> now.",
                    "delta_percent": -10,
                    "llm_provider": "remote_openai",
                },
                "draft.density",
            ),
            (
                "/v1/draft/rewrite",
                {
                    "text": "Polish around <lock>Exact locked claim</lock> cleanly.",
                    "mode": "cut_polish",
                    "llm_provider": "remote_openai",
                },
                "draft.rewrite",
            ),
        ):
            calls = {"n": 0}

            def emdash_llm(_s: str, _u: str, _calls: dict[str, int] = calls) -> str:
                _calls["n"] += 1
                return json.dumps({"text": "\u2014"})

            port = self._start_server(llm_caller=emdash_llm, max_lock_retries=1)
            st, err = self._post_json(port, endpoint, payload)
            self.assertEqual(st, 502, f"Expected 502 for locked emdash on {endpoint}, got {st}: {err}")
            self.assertEqual(calls["n"], 2)
            self.assertEqual(err["provider"], "remote_openai")
            self.assertEqual(err["stage"], expected_stage)
            self.tearDown()
            self.setUp()

        # Genuine non-empty lock-parity violation still preserves locks cleanly via fallback
        port = self._start_server(
            llm_caller=lambda _s, _u: json.dumps({"text": "Completely replaced without claim."}),
            max_lock_retries=1,
        )
        st, data = self._post_json(
            port,
            "/v1/draft/rewrite",
            {
                "text": "Polish around <lock>Exact locked claim</lock> cleanly.",
                "mode": "cut_polish",
                "llm_provider": "remote_openai",
            },
        )
        self.assertEqual(st, 200)
        self.assertTrue(data["lock_parity_preserved"])
        self.assertIn("Exact locked claim", data["text"])


if __name__ == "__main__":
    unittest.main()
