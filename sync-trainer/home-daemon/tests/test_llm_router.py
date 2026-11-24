"""Unit and ephemeral-server integration tests for mixed local & remote LLM routing (F4)."""

from __future__ import annotations

import http.client
import json
import os
import socket
import stat
import sys
import tempfile
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from unittest import mock

import pytest

DAEMON_DIR = Path(__file__).resolve().parents[1]
if str(DAEMON_DIR) not in sys.path:
    sys.path.insert(0, str(DAEMON_DIR))

from llm_router import (  # noqa: E402
    ALL_PROVIDERS,
    ALL_STAGES,
    LlmProviderError,
    LlmRouter,
    LlmUnavailableError,
)
from ota_server import create_ota_server  # noqa: E402
from tinker_voice_daemon import build_bundle_manifest  # noqa: E402


class _MockUpstreamServer:
    """Loopback HTTP server stubbing local/remote LLM provider wire formats on port=0."""

    def __init__(self, handler_fn: Any) -> None:
        self.requests: list[dict[str, Any]] = []
        outer = self

        class _Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, format: str, *args: Any) -> None:
                return

            def _read_json(self) -> Any:
                length = int(self.headers.get("Content-Length") or 0)
                if length <= 0:
                    return None
                raw = self.rfile.read(length).decode("utf-8")
                return json.loads(raw)

            def _reply(self, status: int, body: bytes, content_type: str = "application/json") -> None:
                self.send_response(status)
                self.send_header("Content-Type", content_type)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_GET(self) -> None:
                rec = {
                    "method": "GET",
                    "path": self.path,
                    "headers": {k.lower(): v for k, v in self.headers.items()},
                    "body": None,
                }
                outer.requests.append(rec)
                status, ctype, payload = handler_fn(rec)
                raw = payload if isinstance(payload, bytes) else json.dumps(payload).encode("utf-8")
                self._reply(status, raw, ctype)

            def do_POST(self) -> None:
                rec = {
                    "method": "POST",
                    "path": self.path,
                    "headers": {k.lower(): v for k, v in self.headers.items()},
                    "body": self._read_json(),
                }
                outer.requests.append(rec)
                status, ctype, payload = handler_fn(rec)
                raw = payload if isinstance(payload, bytes) else json.dumps(payload).encode("utf-8")
                self._reply(status, raw, ctype)

        self._httpd = ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
        self._thread = threading.Thread(target=self._httpd.serve_forever, daemon=True)
        self._thread.start()

    @property
    def base_url(self) -> str:
        host, port = self._httpd.server_address[:2]
        return f"http://{host}:{port}"

    def close(self) -> None:
        self._httpd.shutdown()
        self._httpd.server_close()
        self._thread.join(timeout=5.0)


def _unused_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return int(s.getsockname()[1])


def _write_fake_bl(path: Path, script_body: str) -> Path:
    path.write_text("#!/usr/bin/env python3\n" + script_body, encoding="utf-8")
    path.chmod(path.stat().st_mode | stat.S_IXUSR)
    return path


def _http_json(port: int, method: str, path: str, payload: dict[str, Any] | None = None) -> tuple[int, dict[str, Any]]:
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=10.0)
    try:
        if payload is not None:
            raw = json.dumps(payload).encode("utf-8")
            conn.request(method, path, body=raw, headers={"Content-Type": "application/json", "Content-Length": str(len(raw))})
        else:
            conn.request(method, path)
        resp = conn.getresponse()
        data = json.loads(resp.read().decode("utf-8"))
        return resp.status, data
    finally:
        conn.close()


class _RedirectUpstreamServer:
    """Loopback HTTP server returning HTTP 302/307 redirects to verify redirect rejection."""

    def __init__(self, target_url: str, status_code: int = 302) -> None:
        self.requests: list[str] = []
        outer = self

        class _Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, format: str, *args: Any) -> None:
                return

            def _send_redirect(self) -> None:
                outer.requests.append(f"{self.command} {self.path}")
                body = b'{"error":"redirect"}'
                self.send_response(status_code)
                self.send_header("Location", target_url)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_GET(self) -> None:
                self._send_redirect()

            def do_POST(self) -> None:
                length = int(self.headers.get("Content-Length") or 0)
                if length > 0:
                    self.rfile.read(length)
                self._send_redirect()

        self._httpd = ThreadingHTTPServer(("127.0.0.1", 0), _Handler)
        self._thread = threading.Thread(target=self._httpd.serve_forever, daemon=True)
        self._thread.start()

    @property
    def base_url(self) -> str:
        host, port = self._httpd.server_address[:2]
        return f"http://{host}:{port}"

    def close(self) -> None:
        self._httpd.shutdown()
        self._httpd.server_close()
        self._thread.join(timeout=5.0)


def _make_ota_server(tmp_path: Path, **kwargs: Any):
    bundles_dir = tmp_path / "bundles"
    bundles_dir.mkdir(parents=True, exist_ok=True)
    b1 = bundles_dir / "v001"
    b1.mkdir(parents=True, exist_ok=True)
    for name in (
        "s2_pro_4b_q4_k_m.gguf",
        "voice_lora_v001.gguf",
        "codec_decoder_fp16.tflite",
        "sherpa_ctc_aligner_en.tar.gz",
        "wespeaker_resnet34.onnx",
        "master_centroid.bin",
        "dnsmos_p808.onnx",
        "room_ir_44k.pcm",
        "room_tone_44k.pcm",
    ):
        (b1 / name).write_bytes(b"DATA" * 8)
    (b1 / "lexicon.json").write_text('{"Aircheck":"ok"}\n', encoding="utf-8")
    build_bundle_manifest(bundle_dir=b1, bundle_version="v001", lora_version=1)
    prefs = tmp_path / "preferences.jsonl"
    renders = tmp_path / "renders"
    renders.mkdir(parents=True, exist_ok=True)
    return create_ota_server(
        host="127.0.0.1",
        port=0,
        bundles_root=bundles_dir,
        preferences_path=prefs,
        renders_dir=renders,
        quiet=True,
        **kwargs,
    )


def test_all_seven_providers_wire_formats_and_thinking_strip(tmp_path: Path) -> None:
    # 1. local_mlx: /health ok, empty /v1/models, <think>...</think> stripped, explicit max_tokens
    def mlx_handler(rec: dict[str, Any]):
        if rec["method"] == "GET" and rec["path"] == "/health":
            return 200, "application/json", {"status": "ok"}
        if rec["method"] == "GET" and rec["path"] == "/v1/models":
            return 200, "application/json", b""
        assert rec["path"] == "/v1/chat/completions"
        assert rec["body"]["model"] == "default_model"
        assert rec["body"]["max_tokens"] == 1024
        return 200, "application/json", {
            "choices": [{"message": {"content": '<think>reasoning</think>\n{"text":"MLX reply."}'}}]
        }

    # 2. local_ollama: /api/tags + /api/chat
    def ollama_handler(rec: dict[str, Any]):
        if rec["method"] == "GET" and rec["path"] == "/api/tags":
            return 200, "application/json", {"models": [{"name": "qwen2.5-coder"}]}
        assert rec["path"] == "/api/chat"
        assert rec["body"]["stream"] is False
        assert rec["body"]["options"]["num_predict"] == 1024
        return 200, "application/json", {"message": {"content": '{"text":"Ollama reply."}'}}

    # 3. local_llama_cpp: /health ok + /v1/chat/completions
    def llama_handler(rec: dict[str, Any]):
        if rec["method"] == "GET" and rec["path"] == "/health":
            return 200, "application/json", {"status": "ok"}
        assert rec["path"] == "/v1/chat/completions"
        return 200, "application/json", {"choices": [{"message": {"content": '{"text":"LlamaCpp reply."}'}}]}

    # 4. remote_openai: /chat/completions + max_completion_tokens fallback
    openai_calls = {"n": 0}

    def openai_handler(rec: dict[str, Any]):
        assert rec["headers"]["authorization"] == "Bearer sk-test-openai"
        assert rec["path"] == "/v1/chat/completions"
        openai_calls["n"] += 1
        if "max_completion_tokens" in rec["body"] and openai_calls["n"] == 1:
            return 400, "application/json", {"error": {"message": "Unsupported parameter: max_completion_tokens"}}
        return 200, "application/json", {"choices": [{"message": {"content": '{"text":"OpenAI reply."}'}}]}

    # 5. remote_gemini: /v1beta/models/{model}:generateContent?key=...
    def gemini_handler(rec: dict[str, Any]):
        parsed = urllib.parse.urlsplit(rec["path"])
        qs = urllib.parse.parse_qs(parsed.query)
        assert qs["key"] == ["gem-test-key"]
        assert parsed.path == "/v1beta/models/gemini-2.5-flash:generateContent"
        assert rec["body"]["systemInstruction"]["parts"][0]["text"] == "sys"
        return 200, "application/json", {
            "candidates": [{"content": {"parts": [{"text": '{"text":"Gemini reply."}'}]}}]
        }

    # 6. remote_anthropic: /v1/messages + x-api-key + anthropic-version
    def anthropic_handler(rec: dict[str, Any]):
        assert rec["headers"]["x-api-key"] == "ant-test-key"
        assert rec["headers"]["anthropic-version"] == "2023-06-01"
        assert rec["path"] == "/v1/messages"
        return 200, "application/json", {
            "content": [{"type": "text", "text": '{"text":"Anthropic reply."}'}]
        }

    # 7. remote_bailian: fake `bl` binary emitting warning + content
    fake_bl = _write_fake_bl(
        tmp_path / "fake_bl",
        """
import json, sys
print(json.dumps({"content": json.dumps({"text": "Bailian reply."})}))
print(json.dumps({"warning": {"code": "UPDATE_AVAILABLE", "message": "update"}}))
""",
    )
    bailian_cfg = tmp_path / "bailian_config.json"
    bailian_cfg.write_text('{"api_key":"fake"}\n', encoding="utf-8")

    mlx_srv = _MockUpstreamServer(mlx_handler)
    ollama_srv = _MockUpstreamServer(ollama_handler)
    llama_srv = _MockUpstreamServer(llama_handler)
    openai_srv = _MockUpstreamServer(openai_handler)
    gemini_srv = _MockUpstreamServer(gemini_handler)
    anthropic_srv = _MockUpstreamServer(anthropic_handler)

    try:
        env_patch = {
            "OPENAI_API_KEY": "sk-test-openai",
            "GEMINI_API_KEY": "gem-test-key",
            "ANTHROPIC_API_KEY": "ant-test-key",
            "AIRCHECK_MLX_URL": mlx_srv.base_url,
            "AIRCHECK_OLLAMA_URL": ollama_srv.base_url,
            "AIRCHECK_LLAMA_CPP_URL": llama_srv.base_url,
            "AIRCHECK_OPENAI_BASE_URL": f"{openai_srv.base_url}/v1",
            "AIRCHECK_GEMINI_BASE_URL": gemini_srv.base_url,
            "AIRCHECK_ANTHROPIC_BASE_URL": anthropic_srv.base_url,
        }
        with mock.patch.dict(os.environ, env_patch, clear=False):
            router = LlmRouter(
                config_path=tmp_path / "llm_config.json",
                bl_bin=fake_bl,
                bailian_config_path=bailian_cfg,
            )
            listed = router.list_providers()
            assert [p["id"] for p in listed["providers"]] == list(ALL_PROVIDERS)
            assert all(p["available"] for p in listed["providers"])

            expected = {
                "local_mlx": ('{"text":"MLX reply."}', True),
                "local_ollama": ('{"text":"Ollama reply."}', True),
                "local_llama_cpp": ('{"text":"LlamaCpp reply."}', True),
                "remote_bailian": (json.dumps({"text": "Bailian reply."}), False),
                "remote_openai": ('{"text":"OpenAI reply."}', False),
                "remote_gemini": ('{"text":"Gemini reply."}', False),
                "remote_anthropic": ('{"text":"Anthropic reply."}', False),
            }
            for prov, (want_text, want_local) in expected.items():
                res = router.complete("draft.angles", "sys", "usr", llm_provider=prov)
                assert res.text == want_text
                assert res.provider == prov
                assert res.is_local is want_local
    finally:
        for srv in (mlx_srv, ollama_srv, llama_srv, openai_srv, gemini_srv, anthropic_srv):
            srv.close()


def test_probe_rejects_non_json_llama_cpp_and_stays_non_blocking(tmp_path: Path) -> None:
    # Simulate :8081 outrider returning HTTP 200 plain text on /health
    def outrider_handler(_rec: dict[str, Any]):
        return 200, "text/plain", b"No reserver proxy url"

    outrider_srv = _MockUpstreamServer(outrider_handler)
    dead_mlx_port = _unused_port()
    dead_ollama_port = _unused_port()
    try:
        with mock.patch.dict(
            os.environ,
            {
                "AIRCHECK_MLX_URL": f"http://127.0.0.1:{dead_mlx_port}",
                "AIRCHECK_OLLAMA_URL": f"http://127.0.0.1:{dead_ollama_port}",
                "AIRCHECK_LLAMA_CPP_URL": outrider_srv.base_url,
            },
            clear=False,
        ):
            router = LlmRouter(config_path=tmp_path / "llm_config.json", probe_timeout_sec=0.35)
            t0 = time.monotonic()
            listed = router.list_providers()
            elapsed = time.monotonic() - t0
            assert elapsed < 1.2
            by_id = {p["id"]: p for p in listed["providers"]}
            assert by_id["local_mlx"]["available"] is False
            assert by_id["local_ollama"]["available"] is False
            assert by_id["local_llama_cpp"]["available"] is False
            assert "non-JSON" in by_id["local_llama_cpp"]["detail"]
    finally:
        outrider_srv.close()


def test_http_providers_config_persistence_and_mixed_routing(tmp_path: Path) -> None:
    seen_stages: list[tuple[str, str, int]] = []

    def mlx_handler(rec: dict[str, Any]):
        if rec["method"] == "GET" and rec["path"] == "/health":
            return 200, "application/json", {"status": "ok"}
        seen_stages.append(("local_mlx", rec["body"]["model"], rec["body"]["max_tokens"]))
        return 200, "application/json", {
            "choices": [
                {
                    "message": {
                        "content": json.dumps(
                            {
                                "extracted_angles": [
                                    {"id": "ex_1", "label": "Fast local", "preview": "Local MLX handles angles."},
                                    {"id": "ex_2", "label": "Zero queue", "preview": "No cloud wait."},
                                ],
                                "blindspots": [
                                    {"id": "bs_1", "label": "RAM limit", "preview": "Small models need tight prompts."},
                                    {"id": "bs_2", "label": "Battery", "preview": "Sustained decode heats laptops."},
                                    {"id": "bs_3", "label": "Context cap", "preview": "Long scripts need remote fallback."},
                                ],
                            }
                        )
                    }
                }
            ]
        }

    def openai_handler(rec: dict[str, Any]):
        seen_stages.append(("remote_openai", rec["body"]["model"], rec["body"]["max_completion_tokens"]))
        user_msg = rec["body"]["messages"][1]["content"]
        if "Write 3 distinct versions" in user_msg:
            return 200, "application/json", {
                "choices": [
                    {
                        "message": {
                            "content": json.dumps(
                                {
                                    "versions": [
                                        {"posture_label": lbl, "paragraphs": [f"{lbl} p{i}." for i in range(4)]}
                                        for lbl in ("DIRECT", "PUNCHY", "STORY")
                                    ]
                                }
                            )
                        }
                    }
                ]
            }
        return 200, "application/json", {
            "choices": [
                {
                    "message": {
                        "content": json.dumps(
                            {
                                "headline": "LOCAL MODEL LAUNCHES",
                                "subline": "MIXED ROUTER VERIFIED",
                                "segments": ["First anchor line.", "Second anchor line."],
                            }
                        )
                    }
                }
            ]
        }

    mlx_srv = _MockUpstreamServer(mlx_handler)
    openai_srv = _MockUpstreamServer(openai_handler)
    srv = None
    thread = None
    try:
        with mock.patch.dict(
            os.environ,
            {
                "OPENAI_API_KEY": "sk-mixed-test",
                "AIRCHECK_MLX_URL": mlx_srv.base_url,
                "AIRCHECK_OPENAI_BASE_URL": f"{openai_srv.base_url}/v1",
            },
            clear=False,
        ):
            srv = _make_ota_server(tmp_path)
            thread = threading.Thread(target=srv.serve_forever, daemon=True)
            thread.start()
            port = srv.server_address[1]

            # 1. GET /v1/llm/providers
            st, listed = _http_json(port, "GET", "/v1/llm/providers")
            assert st == 200
            assert set(listed["stages"].keys()) == set(ALL_STAGES)

            # 2. Validation errors on POST /v1/llm/config -> 400
            for bad_cfg in (
                {"stages": {"unknown.stage": {"provider": "local_mlx", "model": "default_model"}}},
                {"stages": {"draft.angles": {"provider": "bad_provider", "model": "x"}}},
                {"stages": {"draft.angles": {"provider": "remote_openai", "model": "   "}}},
                {"provider_urls": {"local_mlx": "ftp://127.0.0.1:8080"}},
                {"unknown_top_key": {}},
            ):
                bst, _ = _http_json(port, "POST", "/v1/llm/config", bad_cfg)
                assert bst == 400, f"Expected 400 for {bad_cfg}"

            # 3. Configure mixed local + remote stages
            st, cfg_res = _http_json(
                port,
                "POST",
                "/v1/llm/config",
                {
                    "stages": {
                        "draft.angles": {"provider": "local_mlx", "model": ""},
                        "draft.synthesize_script": {"provider": "remote_openai", "model": "gpt-4.1-mini"},
                        "ai_assist": {"provider": "remote_openai", "model": "gpt-4o-mini"},
                    }
                },
            )
            assert st == 200
            assert cfg_res["stages"]["draft.angles"] == {"provider": "local_mlx", "model": "default_model"}

            # 4. Call /v1/draft/angles (local_mlx)
            st, angles_res = _http_json(
                port,
                "POST",
                "/v1/draft/angles",
                {"seed_transcript": "Local MLX handles short extraction cleanly."},
            )
            assert st == 200
            assert angles_res["llm_meta"] == {"provider": "local_mlx", "model": "default_model", "is_local": True}

            # 5. Call /v1/draft/synthesize-script (remote_openai, max_tokens=2048)
            st, script_res = _http_json(
                port,
                "POST",
                "/v1/draft/synthesize-script",
                {"seed_transcript": "Mixed routing works.", "arc_beats": ["a", "b", "c", "d"]},
            )
            assert st == 200
            assert script_res["llm_meta"] == {"provider": "remote_openai", "model": "gpt-4.1-mini", "is_local": False}

            # 6. Call /v1/ai-assist (remote_openai) and override to local_mlx
            st, ai_res = _http_json(
                port,
                "POST",
                "/v1/ai-assist",
                {"mode": "flesh_out_story", "text": "Local LLM test story."},
            )
            assert st == 200
            assert ai_res["headline"] == "LOCAL MODEL LAUNCHES"
            assert ai_res["llm_meta"] == {"provider": "remote_openai", "model": "gpt-4o-mini", "is_local": False}

            assert ("local_mlx", "default_model", 1024) in seen_stages
            assert ("remote_openai", "gpt-4.1-mini", 2048) in seen_stages
            assert ("remote_openai", "gpt-4o-mini", 1024) in seen_stages

            # 7. Restart server against same renders_dir and verify config persisted
            srv.shutdown()
            srv.server_close()
            thread.join(timeout=5.0)

            srv = _make_ota_server(tmp_path)
            thread = threading.Thread(target=srv.serve_forever, daemon=True)
            thread.start()
            port2 = srv.server_address[1]
            st, listed2 = _http_json(port2, "GET", "/v1/llm/providers")
            assert st == 200
            assert listed2["stages"]["draft.angles"] == {"provider": "local_mlx", "model": "default_model"}
            assert listed2["stages"]["ai_assist"] == {"provider": "remote_openai", "model": "gpt-4o-mini"}
    finally:
        if srv is not None:
            srv.shutdown()
            srv.server_close()
        if thread is not None:
            thread.join(timeout=5.0)
        mlx_srv.close()
        openai_srv.close()


def test_broll_planner_helper_and_explicit_errors(tmp_path: Path) -> None:
    mode = {"behavior": "ok"}

    def openai_handler(_rec: dict[str, Any]):
        if mode["behavior"] == "ok":
            return 200, "application/json", {
                "choices": [
                    {
                        "message": {
                            "content": json.dumps(
                                {
                                    "items": [
                                        {
                                            "clause_id": "c1",
                                            "b_roll_prompt": "Macro shot of server rack fiber optic patch cables in warm studio light",
                                            "broll_caption": "Server rack fiber",
                                            "depth_text_tag": "738ms",
                                        }
                                    ]
                                }
                            )
                        }
                    }
                ]
            }
        return 200, "application/json", {"choices": [{"message": {"content": "not json"}}]}

    openai_srv = _MockUpstreamServer(openai_handler)
    try:
        with mock.patch.dict(
            os.environ,
            {
                "OPENAI_API_KEY": "sk-broll",
                "AIRCHECK_OPENAI_BASE_URL": f"{openai_srv.base_url}/v1",
            },
            clear=False,
        ):
            router = LlmRouter(config_path=tmp_path / "llm_config.json")
            res = router.plan_broll_prompts(
                [{"id": "c1", "spoken_text": "Local Whisper ran in 738ms on Mac."}],
                story_context="Voice latency benchmark",
                llm_provider="remote_openai",
            )
            assert res["llm_meta"]["provider"] == "remote_openai"
            assert res["items"][0]["clause_id"] == "c1"
            assert "fiber optic" in res["items"][0]["b_roll_prompt"]
            assert res["items"][0]["depth_text_tag"] == "738ms"

            mode["behavior"] = "bad_json"
            with pytest.raises(LlmProviderError) as exc_info:
                router.plan_broll_prompts(
                    [{"id": "c1", "spoken_text": "Local Whisper ran in 738ms on Mac."}],
                    llm_provider="remote_openai",
                )
            assert exc_info.value.status_code == 502
            assert exc_info.value.stage == "broll_planner"
            assert exc_info.value.provider == "remote_openai"
    finally:
        openai_srv.close()


def test_explicit_502_503_errors_and_no_flesh_out_story_fallback(tmp_path: Path) -> None:
    behavior = {"kind": "http_500"}

    def flaky_handler(_rec: dict[str, Any]):
        k = behavior["kind"]
        if k == "http_500":
            return 500, "application/json", {"error": {"message": "upstream boom"}}
        if k == "timeout":
            time.sleep(1.5)
            return 200, "application/json", {"choices": [{"message": {"content": "{}"}}]}
        if k == "missing_headline":
            return 200, "application/json", {
                "choices": [{"message": {"content": json.dumps({"headline": "", "subline": "LIVE", "segments": ["Seg 1."]})}}]
            }
        if k == "missing_subline":
            return 200, "application/json", {
                "choices": [{"message": {"content": json.dumps({"headline": "BREAKING NEWS", "subline": "   ", "segments": ["Seg 1."]})}}]
            }
        return 200, "application/json", {"choices": [{"message": {"content": ""}}]}

    flaky_srv = _MockUpstreamServer(flaky_handler)
    dead_port = _unused_port()
    srv = None
    thread = None
    try:
        with mock.patch.dict(
            os.environ,
            {
                "OPENAI_API_KEY": "sk-err-test",
                "ANTHROPIC_API_KEY": "",
                "AIRCHECK_OPENAI_BASE_URL": f"{flaky_srv.base_url}/v1",
                "AIRCHECK_MLX_URL": f"http://127.0.0.1:{dead_port}",
            },
            clear=False,
        ):
            router = LlmRouter(config_path=tmp_path / "llm_config.json", timeout_sec=0.4)
            srv = _make_ota_server(tmp_path, llm_router=router)
            thread = threading.Thread(target=srv.serve_forever, daemon=True)
            thread.start()
            port = srv.server_address[1]

            # 1. Connection refused -> 503
            st, err = _http_json(
                port,
                "POST",
                "/v1/draft/angles",
                {"seed_transcript": "Test.", "llm_provider": "local_mlx"},
            )
            assert st == 503
            assert err["provider"] == "local_mlx"
            assert err["stage"] == "draft.angles"

            # 2. Missing API key -> 503
            st, err = _http_json(
                port,
                "POST",
                "/v1/ai-assist",
                {"mode": "flesh_out_story", "text": "Test.", "llm_provider": "remote_anthropic"},
            )
            assert st == 503
            assert err["provider"] == "remote_anthropic"
            assert err["stage"] == "ai_assist"

            # 3. Upstream HTTP 500 -> 502
            behavior["kind"] = "http_500"
            st, err = _http_json(
                port,
                "POST",
                "/v1/draft/angles",
                {"seed_transcript": "Test.", "llm_provider": "remote_openai"},
            )
            assert st == 502
            assert err["provider"] == "remote_openai"
            assert err["stage"] == "draft.angles"

            # 4. Timeout -> 502
            behavior["kind"] = "timeout"
            st, err = _http_json(
                port,
                "POST",
                "/v1/ai-assist",
                {"mode": "polish_headline", "text": "Test.", "llm_provider": "remote_openai"},
            )
            assert st == 502
            assert err["provider"] == "remote_openai"
            assert err["stage"] == "ai_assist"

            # 5. flesh_out_story missing headline or subline MUST fail 502 (never substitute fallback!)
            behavior["kind"] = "missing_headline"
            st, err = _http_json(
                port,
                "POST",
                "/v1/ai-assist",
                {"mode": "flesh_out_story", "text": "Test idea", "llm_provider": "remote_openai"},
            )
            assert st == 502
            assert err["provider"] == "remote_openai"
            assert err["stage"] == "ai_assist"

            behavior["kind"] = "missing_subline"
            st, err = _http_json(
                port,
                "POST",
                "/v1/ai-assist",
                {"mode": "flesh_out_story", "text": "Test idea", "llm_provider": "remote_openai"},
            )
            assert st == 502
            assert err["provider"] == "remote_openai"
            assert err["stage"] == "ai_assist"
    finally:
        if srv is not None:
            srv.shutdown()
            srv.server_close()
        if thread is not None:
            thread.join(timeout=5.0)
        flaky_srv.close()


def test_ssrf_url_validation_and_redirect_rejection(tmp_path: Path) -> None:
    target_hits: list[dict[str, Any]] = []

    def target_handler(rec: dict[str, Any]):
        target_hits.append(rec)
        return 200, "application/json", {"choices": [{"message": {"content": '{"text":"EXFILTRATED"}'}}]}

    target_srv = _MockUpstreamServer(target_handler)
    redirect_srv = _RedirectUpstreamServer(f"{target_srv.base_url}/v1/chat/completions", status_code=302)
    srv = None
    thread = None
    try:
        with mock.patch.dict(
            os.environ,
            {
                "OPENAI_API_KEY": "sk-secret-do-not-leak",
            },
            clear=False,
        ):
            router = LlmRouter(
                config_path=tmp_path / "llm_config.json",
                probe_timeout_sec=0.4,
                provider_urls={
                    "local_mlx": redirect_srv.base_url,
                    "remote_openai": f"{redirect_srv.base_url}/v1",
                },
            )
            srv = _make_ota_server(tmp_path, llm_router=router)
            thread = threading.Thread(target=srv.serve_forever, daemon=True)
            thread.start()
            port = srv.server_address[1]

            # 1. All adversarial URLs & remote_* provider_urls overrides via POST /v1/llm/config MUST fail 400
            adversarial_configs = [
                {"provider_urls": {"local_mlx": "http://:8080"}},
                {"provider_urls": {"local_mlx": "http://127.0.0.1:8080\r\nX-Injected: 1"}},
                {"provider_urls": {"local_mlx": "http://127.0.0.1:8080?foo=bar#frag"}},
                {"provider_urls": {"local_mlx": "http://user:pass@127.0.0.1:8080"}},
                {"provider_urls": {"local_mlx": "http://169.254.169.254/latest/meta-data"}},
                {"provider_urls": {"remote_openai": "http://evil.attacker.com"}},
                {"provider_urls": {"remote_gemini": "https://evil.attacker.com"}},
                {"provider_urls": {"remote_anthropic": "https://evil.attacker.com"}},
                {"provider_urls": {"remote_bailian": "http://127.0.0.1:8080"}},
                {"provider_urls": {"local_mlx": "http://1.2.3.4:8080"}},
                {"provider_urls": {"local_mlx": "http://evil.attacker.com:8080"}},
                {"provider_urls": {"local_mlx": "http://0.0.0.0:8080"}},
                {"provider_urls": {"local_mlx": "http://[fe80::1]:8080"}},
                {"provider_urls": {"local_mlx": "http://[::ffff:169.254.169.254]:8080"}},
            ]
            for bad in adversarial_configs:
                st, err = _http_json(port, "POST", "/v1/llm/config", bad)
                assert st == 400, f"Expected 400 for {bad}, got {st}: {err}"

            # 2. Valid local network URLs (loopback, RFC1918, Tailscale CGNAT) ARE accepted
            for ok_url in (
                "http://localhost:8080",
                "http://127.0.0.1:8080",
                "http://[::1]:8080",
                "http://10.0.0.5:8080",
                "http://172.16.4.2:8080",
                "http://192.168.1.50:8080",
                "http://100.100.10.20:8080",
            ):
                st, _ = _http_json(port, "POST", "/v1/llm/config", {"provider_urls": {"local_ollama": ok_url}})
                assert st == 200, f"Expected 200 for valid local URL {ok_url}"

            # 3. HTTP 302 redirects MUST be rejected on both local probes and POST completions (never follow!)
            st, listed = _http_json(port, "GET", "/v1/llm/providers")
            assert st == 200
            by_id = {p["id"]: p for p in listed["providers"]}
            assert by_id["local_mlx"]["available"] is False
            assert "redirect" in by_id["local_mlx"]["detail"].lower() or "302" in by_id["local_mlx"]["detail"]

            st, err = _http_json(
                port,
                "POST",
                "/v1/ai-assist",
                {"mode": "polish_headline", "text": "Redirect test.", "llm_provider": "remote_openai"},
            )
            assert st == 502
            assert err["provider"] == "remote_openai"
            assert err["stage"] == "ai_assist"
            assert target_hits == [], f"Redirect target was reached unexpectedly: {target_hits}"
    finally:
        if srv is not None:
            srv.shutdown()
            srv.server_close()
        if thread is not None:
            thread.join(timeout=5.0)
        redirect_srv.close()
        target_srv.close()


def test_draft_engine_honors_injected_router_timeout(tmp_path: Path) -> None:
    def slow_handler(_rec: dict[str, Any]):
        time.sleep(1.5)
        return 200, "application/json", {"choices": [{"message": {"content": "{}"}}]}

    slow_srv = _MockUpstreamServer(slow_handler)
    srv = None
    thread = None
    try:
        router = LlmRouter(
            config_path=tmp_path / "llm_config.json",
            timeout_sec=0.3,
            provider_urls={"local_mlx": slow_srv.base_url},
        )
        srv = _make_ota_server(tmp_path, llm_router=router)
        thread = threading.Thread(target=srv.serve_forever, daemon=True)
        thread.start()
        port = srv.server_address[1]

        t0 = time.monotonic()
        st, err = _http_json(
            port,
            "POST",
            "/v1/draft/angles",
            {"seed_transcript": "Timeout propagation test.", "llm_provider": "local_mlx"},
        )
        elapsed = time.monotonic() - t0
        assert st == 502
        assert elapsed < 1.0, f"DraftPassEngine overrode router timeout_sec (elapsed={elapsed:.2f}s)"
        assert err["error"] == "llm_timeout"
        assert err["provider"] == "local_mlx"
        assert err["stage"] == "draft.angles"
    finally:
        if srv is not None:
            srv.shutdown()
            srv.server_close()
        if thread is not None:
            thread.join(timeout=5.0)
        slow_srv.close()


def test_concurrency_burst_12_threads_no_backlog_drops(tmp_path: Path) -> None:
    srv = _make_ota_server(tmp_path)
    assert srv.request_queue_size >= 128
    thread = threading.Thread(target=srv.serve_forever, daemon=True)
    thread.start()
    port = srv.server_address[1]

    num_threads = 12
    iterations = 20
    barrier = threading.Barrier(num_threads)
    errors: list[tuple[int, int, str, str]] = []
    err_lock = threading.Lock()

    def worker(tid: int) -> None:
        for it in range(iterations):
            barrier.wait(timeout=5.0)
            try:
                if (tid + it) % 2 == 0:
                    st, _ = _http_json(port, "GET", "/v1/llm/providers")
                else:
                    st, _ = _http_json(
                        port,
                        "POST",
                        "/v1/llm/config",
                        {
                            "stages": {
                                "draft.angles": {
                                    "provider": "local_mlx",
                                    "model": f"m_{tid % 3}",
                                }
                            }
                        },
                    )
                if st != 200:
                    with err_lock:
                        errors.append((tid, it, "HTTPStatus", str(st)))
            except Exception as exc:
                with err_lock:
                    errors.append((tid, it, type(exc).__name__, str(exc)))

    try:
        workers = [threading.Thread(target=worker, args=(i,)) for i in range(num_threads)]
        for w in workers:
            w.start()
        for w in workers:
            w.join(timeout=15.0)

        assert errors == [], f"Unexpected concurrency errors: {errors[:5]}"
        cfg_path = srv.llm_config_path
        assert cfg_path.is_file()
        disk_cfg = json.loads(cfg_path.read_text(encoding="utf-8"))
        st, live_cfg = _http_json(port, "GET", "/v1/llm/providers")
        assert st == 200
        assert disk_cfg["stages"]["draft.angles"] == live_cfg["stages"]["draft.angles"]
    finally:
        srv.shutdown()
        srv.server_close()
        thread.join(timeout=5.0)


def test_broll_planner_dedup_and_flesh_out_null_segments(tmp_path: Path) -> None:
    def broll_caller(**_kwargs: Any) -> str:
        # Omit c0 while returning c1 with exact clause_id -> must NOT duplicate c1 onto c0!
        return json.dumps(
            {
                "items": [
                    {
                        "clause_id": "c1",
                        "b_roll_prompt": "Close-up of newsroom teleprompter lens in dark studio",
                        "broll_caption": "Teleprompter",
                        "depth_text_tag": "",
                    }
                ]
            }
        )

    router = LlmRouter(config_path=tmp_path / "llm_config.json", callers={"remote_openai": broll_caller})
    with pytest.raises(LlmProviderError) as exc_info:
        router.plan_broll_prompts(
            [
                {"id": "c0", "spoken_text": "First clause."},
                {"id": "c1", "spoken_text": "Second clause."},
            ],
            llm_provider="remote_openai",
        )
    assert exc_info.value.status_code == 502
    assert "c0" in str(exc_info.value)

    # First entry lacks clause_id, second matches c1 -> c0 claims positional[0], c1 matches c1
    def broll_mixed_caller(**_kwargs: Any) -> str:
        return json.dumps(
            {
                "items": [
                    {
                        "clause_id": "",
                        "b_roll_prompt": "Wide shot of Mac studio desk with audio interface",
                        "broll_caption": "Mac Studio",
                        "depth_text_tag": "",
                    },
                    {
                        "clause_id": "c1",
                        "b_roll_prompt": "Close-up of broadcast microphone pop filter",
                        "broll_caption": "Mic setup",
                        "depth_text_tag": "",
                    },
                ]
            }
        )

    router2 = LlmRouter(config_path=tmp_path / "llm_config2.json", callers={"remote_openai": broll_mixed_caller})
    res = router2.plan_broll_prompts(
        [
            {"id": "c0", "spoken_text": "First clause."},
            {"id": "c1", "spoken_text": "Second clause."},
        ],
        llm_provider="remote_openai",
    )
    assert res["items"][0]["b_roll_prompt"].startswith("Wide shot")
    assert res["items"][1]["b_roll_prompt"].startswith("Close-up")

    # flesh_out_story with [null, "  "] segments must fail 502 instead of coercing null -> "None"
    def null_segs_caller(**_kwargs: Any) -> str:
        return json.dumps({"headline": "BREAKING STORY", "subline": "LIVE UPDATE", "segments": [None, "   "]})

    router3 = LlmRouter(config_path=tmp_path / "llm_config3.json", callers={"remote_openai": null_segs_caller})
    srv = _make_ota_server(tmp_path, llm_router=router3)
    thread = threading.Thread(target=srv.serve_forever, daemon=True)
    thread.start()
    try:
        st, err = _http_json(
            srv.server_address[1],
            "POST",
            "/v1/ai-assist",
            {"mode": "flesh_out_story", "text": "Test null segments.", "llm_provider": "remote_openai"},
        )
        assert st == 502
        assert err["provider"] == "remote_openai"
        assert err["stage"] == "ai_assist"
    finally:
        srv.shutdown()
        srv.server_close()
        thread.join(timeout=5.0)
