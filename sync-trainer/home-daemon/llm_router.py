"""Unified Mixed Local & Remote (Hosted) LLM Router for the Mac :8443 rig daemon.

Supports 7 providers across 10 pipeline stages using Python stdlib only:
  Local (is_local=True):
    - local_mlx       : OpenAI-compatible mlx_lm.server (default http://127.0.0.1:8080)
    - local_ollama    : Ollama /api/chat & /api/tags (default http://127.0.0.1:11434)
    - local_llama_cpp : llama.cpp llama-server /v1/chat/completions (default http://127.0.0.1:8081)
  Remote (is_local=False):
    - remote_bailian  : Bailian CLI `bl text chat` (DashScope Qwen)
    - remote_openai   : OpenAI Chat Completions API (OPENAI_API_KEY)
    - remote_gemini   : Google Gemini generateContent API (GEMINI_API_KEY / GOOGLE_API_KEY)
    - remote_anthropic: Anthropic Messages API (ANTHROPIC_API_KEY)

Constitutional guarantees:
  - Zero fabricated, mocked, or silent fallback responses when a provider fails or is unreachable.
  - Explicit LlmUnavailableError (HTTP 503) and LlmProviderError (HTTP 502) carrying provider & stage.
  - Thread-safe atomic config persistence (temp file + fsync + os.replace).
"""

from __future__ import annotations

import concurrent.futures
import copy
import dataclasses
import ipaddress
import json
import os
import re
import shutil
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any, Callable, Sequence

from bailian_quota import (
    active_bailian_quota_block,
    clear_bailian_quota_state,
    default_quota_state_path,
    record_bailian_quota_exhausted,
)

DEFAULT_BL_BIN = Path("/Users/mac/.local/bin/bl")
DEFAULT_BAILIAN_CONFIG_PATH = Path.home() / ".bailian" / "config.json"

DEFAULT_MLX_URL = "http://127.0.0.1:8080"
DEFAULT_OLLAMA_URL = "http://127.0.0.1:11434"
DEFAULT_LLAMA_CPP_URL = "http://127.0.0.1:8081"
DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1"
DEFAULT_GEMINI_BASE_URL = "https://generativelanguage.googleapis.com"
DEFAULT_ANTHROPIC_BASE_URL = "https://api.anthropic.com"

DEFAULT_DRAFT_MODEL = "qwen3.8-flash"
DEFAULT_AI_ASSIST_MODEL = "qwen3.8-max"
DEFAULT_MLX_MODEL = "default_model"
DEFAULT_OLLAMA_MODEL = "qwen2.5-coder"
DEFAULT_LLAMA_CPP_MODEL = "local"
DEFAULT_OPENAI_MODEL = "gpt-4.1-mini"
DEFAULT_GEMINI_MODEL = "gemini-2.5-flash"
DEFAULT_ANTHROPIC_MODEL = "claude-sonnet-4-20250514"

ALL_PROVIDERS: tuple[str, ...] = (
    "local_mlx",
    "local_ollama",
    "local_llama_cpp",
    "remote_bailian",
    "remote_openai",
    "remote_gemini",
    "remote_anthropic",
)

LOCAL_PROVIDERS: frozenset[str] = frozenset(
    {"local_mlx", "local_ollama", "local_llama_cpp"}
)

URL_CONFIGURABLE_PROVIDERS: frozenset[str] = frozenset(
    {
        "local_mlx",
        "local_ollama",
        "local_llama_cpp",
        "remote_openai",
        "remote_gemini",
        "remote_anthropic",
    }
)

ALL_STAGES: tuple[str, ...] = (
    "draft.angles",
    "draft.arc",
    "draft.synthesize",
    "draft.synthesize_script",
    "draft.brush_swap",
    "draft.seam_heal",
    "draft.density",
    "draft.rewrite",
    "ai_assist",
    "broll_planner",
)

STAGE_DEFAULT_MAX_TOKENS: dict[str, int] = {
    "draft.angles": 1024,
    "draft.arc": 1024,
    "draft.synthesize": 1024,
    "draft.synthesize_script": 2048,
    "draft.brush_swap": 512,
    "draft.seam_heal": 1024,
    "draft.density": 1024,
    "draft.rewrite": 1024,
    "ai_assist": 1024,
    "broll_planner": 1536,
}

PROVIDER_LABELS: dict[str, str] = {
    "local_mlx": "Local MLX (Mac)",
    "local_ollama": "Local Ollama",
    "local_llama_cpp": "Local llama.cpp",
    "remote_bailian": "Remote Bailian (DashScope)",
    "remote_openai": "Remote OpenAI",
    "remote_gemini": "Remote Gemini",
    "remote_anthropic": "Remote Anthropic",
}

_THINK_PREFIX_RE = re.compile(r"^\s*<think>.*?</think>\s*", re.DOTALL | re.IGNORECASE)


class LlmProviderError(RuntimeError):
    """Explicit LLM failure mapped to HTTP 502 (or 503 via subclass)."""

    def __init__(
        self,
        message: str,
        *,
        provider: str,
        stage: str,
        model: str | None = None,
        status_code: int = 502,
        error_code: str = "llm_provider_failed",
    ) -> None:
        super().__init__(message)
        self.provider = provider
        self.stage = stage
        self.model = model
        self.status_code = int(status_code)
        self.error_code = error_code


class LlmUnavailableError(LlmProviderError):
    """Provider is unconfigured, missing binary/key, or connection refused (HTTP 503)."""

    def __init__(
        self,
        message: str,
        *,
        provider: str,
        stage: str,
        model: str | None = None,
        error_code: str = "llm_provider_unavailable",
    ) -> None:
        super().__init__(
            message,
            provider=provider,
            stage=stage,
            model=model,
            status_code=503,
            error_code=error_code,
        )


@dataclasses.dataclass(frozen=True)
class LlmResult:
    text: str
    provider: str
    model: str
    is_local: bool
    latency_ms: int = 0

    @property
    def llm_meta(self) -> dict[str, Any]:
        return {
            "provider": self.provider,
            "model": self.model,
            "is_local": self.is_local,
        }


class _NoRedirectHandler(urllib.request.HTTPRedirectHandler):
    """Explicitly reject all HTTP 3xx redirects so API keys & SSRF checks cannot be bypassed."""

    def _reject(
        self,
        req: urllib.request.Request,
        fp: Any,
        code: int,
        msg: str,
        headers: Any,
    ) -> Any:
        raise urllib.error.HTTPError(
            req.full_url,
            code,
            f"HTTP redirect ({code}) rejected",
            headers,
            fp,
        )

    http_error_301 = _reject
    http_error_302 = _reject
    http_error_303 = _reject
    http_error_307 = _reject
    http_error_308 = _reject

    def redirect_request(self, req: urllib.request.Request, fp: Any, code: int, msg: str, headers: Any, newurl: str) -> Any:
        raise urllib.error.HTTPError(
            req.full_url,
            code,
            f"HTTP redirect ({code}) rejected",
            headers,
            fp,
        )


_NO_REDIRECT_OPENER = urllib.request.build_opener(_NoRedirectHandler())

_ALLOWED_LOCAL_NETWORKS: tuple[ipaddress.IPv4Network | ipaddress.IPv6Network, ...] = (
    ipaddress.ip_network("127.0.0.0/8"),
    ipaddress.ip_network("::1/128"),
    ipaddress.ip_network("10.0.0.0/8"),
    ipaddress.ip_network("172.16.0.0/12"),
    ipaddress.ip_network("192.168.0.0/16"),
    ipaddress.ip_network("100.64.0.0/10"),
)


def _strip_think_prefix(text: str) -> str:
    return _THINK_PREFIX_RE.sub("", text).strip()


def _validate_http_url(url: str, *, field_name: str = "base_url") -> str:
    if not isinstance(url, str):
        raise ValueError(f"{field_name} must be a string")
    if any(ord(c) < 32 or ord(c) == 127 for c in url):
        raise ValueError(f"{field_name} must not contain control characters")
    cleaned = url.strip().rstrip("/")
    if not cleaned:
        raise ValueError(f"{field_name} must not be empty")
    if any(c.isspace() or ord(c) < 32 or ord(c) == 127 for c in cleaned):
        raise ValueError(f"{field_name} must not contain whitespace or control characters")
    parsed = urllib.parse.urlsplit(cleaned)
    if parsed.scheme not in ("http", "https") or not parsed.netloc or not parsed.hostname:
        raise ValueError(
            f"{field_name} must be a valid http:// or https:// URL with a host (got {url!r})"
        )
    if parsed.username is not None or parsed.password is not None or "@" in parsed.netloc:
        raise ValueError(f"{field_name} must not include userinfo credentials")
    if parsed.query or "?" in cleaned:
        raise ValueError(f"{field_name} must not include a query string")
    if parsed.fragment or "#" in cleaned:
        raise ValueError(f"{field_name} must not include a URL fragment")
    try:
        _ = parsed.port
    except ValueError as exc:
        raise ValueError(f"{field_name} has an invalid port: {exc}") from exc
    return cleaned


def _validate_local_provider_url(url: str, *, field_name: str = "base_url") -> str:
    cleaned = _validate_http_url(url, field_name=field_name)
    parsed = urllib.parse.urlsplit(cleaned)
    host = (parsed.hostname or "").strip().lower()
    if not host or "%" in host:
        raise ValueError(f"{field_name} has an invalid local host: {url!r}")
    if host == "localhost":
        return cleaned
    try:
        ip = ipaddress.ip_address(host)
    except ValueError as exc:
        raise ValueError(
            f"{field_name} must use localhost, loopback, RFC1918 private IP, or Tailscale 100.64.0.0/10 (got {host!r})"
        ) from exc
    if isinstance(ip, ipaddress.IPv6Address) and ip.ipv4_mapped is not None:
        ip = ip.ipv4_mapped
    if ip.is_unspecified or ip.is_link_local or ip.is_multicast:
        raise ValueError(
            f"{field_name} cannot target link-local, unspecified, or multicast addresses ({ip})"
        )
    if not any(ip in net for net in _ALLOWED_LOCAL_NETWORKS):
        raise ValueError(
            f"{field_name} must target loopback, RFC1918 private IP, or Tailscale 100.64.0.0/10 (got {ip})"
        )
    return cleaned


def _extract_json_objects(raw_text: str) -> list[dict[str, Any]]:
    cleaned = raw_text.strip()
    if cleaned.startswith("```"):
        cleaned = re.sub(r"^```(?:json)?\s*", "", cleaned)
        cleaned = re.sub(r"\s*```$", "", cleaned).strip()
    decoder = json.JSONDecoder()
    objs: list[dict[str, Any]] = []
    pos = 0
    while pos < len(cleaned):
        idx = cleaned.find("{", pos)
        if idx < 0:
            break
        try:
            obj, end = decoder.raw_decode(cleaned, idx)
            if isinstance(obj, dict):
                objs.append(obj)
            pos = end
        except json.JSONDecodeError:
            pos = idx + 1
    return objs


def parse_bl_chat_content(raw_stdout: str) -> str:
    """Extracts assistant text from ``bl text chat --output json`` stdout, ignoring warning objects."""
    objs = _extract_json_objects(raw_stdout)
    if not objs:
        raise RuntimeError(f"bl text chat returned non-JSON output: {raw_stdout.strip()[:240]}")

    for obj in objs:
        if "error" in obj and isinstance(obj["error"], dict):
            msg = obj["error"].get("message") or json.dumps(obj["error"])
            raise RuntimeError(f"bl text chat error: {msg}")

    for obj in objs:
        raw_content = obj.get("content")
        if isinstance(raw_content, str) and raw_content.strip():
            return raw_content.strip()
        choices = obj.get("choices")
        if isinstance(choices, list) and choices:
            first_choice = choices[0]
            if isinstance(first_choice, dict):
                message = first_choice.get("message")
                if isinstance(message, dict):
                    msg_content = message.get("content")
                    if isinstance(msg_content, str) and msg_content.strip():
                        return msg_content.strip()
                    if isinstance(msg_content, list):
                        parts = [
                            str(part.get("text") or "")
                            for part in msg_content
                            if isinstance(part, dict)
                        ]
                        joined = "".join(parts).strip()
                        if joined:
                            return joined

    raise RuntimeError("bl text chat returned empty assistant content.")


def _extract_openai_chat_content(data: Any) -> str:
    if not isinstance(data, dict):
        raise ValueError("Response JSON is not an object")
    if isinstance(data.get("error"), dict):
        msg = data["error"].get("message") or json.dumps(data["error"])
        raise ValueError(f"Upstream error: {msg}")
    choices = data.get("choices")
    if not isinstance(choices, list) or not choices:
        raise ValueError("Response missing choices list")
    first = choices[0]
    if not isinstance(first, dict):
        raise ValueError("Choice entry is not an object")
    msg = first.get("message")
    if not isinstance(msg, dict):
        raise ValueError("Choice missing message object")
    content = msg.get("content")
    if isinstance(content, str):
        return _strip_think_prefix(content)
    if isinstance(content, list):
        parts = [
            str(part.get("text") or "")
            for part in content
            if isinstance(part, dict)
        ]
        return _strip_think_prefix("".join(parts))
    raise ValueError("Message missing text content")


def _summarize_subprocess_error(label: str, proc: subprocess.CompletedProcess[str]) -> str:
    stderr = (proc.stderr or "").strip()
    stdout = (proc.stdout or "").strip()
    detail = stderr if stderr else stdout
    if len(detail) > 900:
        detail = "..." + detail[-900:]
    return (
        f"{label} failed (rc={proc.returncode}): {detail}"
        if detail
        else f"{label} failed (rc={proc.returncode})"
    )


def _default_url_for_provider(provider: str) -> str | None:
    if provider == "local_mlx":
        raw = os.environ.get("AIRCHECK_MLX_URL", "").strip() or DEFAULT_MLX_URL
        return _validate_http_url(raw, field_name="AIRCHECK_MLX_URL")
    if provider == "local_ollama":
        raw = os.environ.get("AIRCHECK_OLLAMA_URL", "").strip() or DEFAULT_OLLAMA_URL
        return _validate_http_url(raw, field_name="AIRCHECK_OLLAMA_URL")
    if provider == "local_llama_cpp":
        raw = os.environ.get("AIRCHECK_LLAMA_CPP_URL", "").strip() or DEFAULT_LLAMA_CPP_URL
        return _validate_http_url(raw, field_name="AIRCHECK_LLAMA_CPP_URL")
    if provider == "remote_openai":
        raw = os.environ.get("AIRCHECK_OPENAI_BASE_URL", "").strip() or DEFAULT_OPENAI_BASE_URL
        return _validate_http_url(raw, field_name="AIRCHECK_OPENAI_BASE_URL")
    if provider == "remote_gemini":
        raw = os.environ.get("AIRCHECK_GEMINI_BASE_URL", "").strip() or DEFAULT_GEMINI_BASE_URL
        return _validate_http_url(raw, field_name="AIRCHECK_GEMINI_BASE_URL")
    if provider == "remote_anthropic":
        raw = os.environ.get("AIRCHECK_ANTHROPIC_BASE_URL", "").strip() or DEFAULT_ANTHROPIC_BASE_URL
        return _validate_http_url(raw, field_name="AIRCHECK_ANTHROPIC_BASE_URL")
    return None


def _default_model_for_provider(
    provider: str,
    stage: str | None = None,
    *,
    default_draft_model: str | None = None,
) -> str:
    if provider == "local_mlx":
        return os.environ.get("AIRCHECK_MLX_MODEL", "").strip() or DEFAULT_MLX_MODEL
    if provider == "local_ollama":
        return os.environ.get("AIRCHECK_OLLAMA_MODEL", "").strip() or DEFAULT_OLLAMA_MODEL
    if provider == "local_llama_cpp":
        return os.environ.get("AIRCHECK_LLAMA_CPP_MODEL", "").strip() or DEFAULT_LLAMA_CPP_MODEL
    if provider == "remote_openai":
        return os.environ.get("AIRCHECK_OPENAI_MODEL", "").strip() or DEFAULT_OPENAI_MODEL
    if provider == "remote_gemini":
        return os.environ.get("AIRCHECK_GEMINI_MODEL", "").strip() or DEFAULT_GEMINI_MODEL
    if provider == "remote_anthropic":
        return os.environ.get("AIRCHECK_ANTHROPIC_MODEL", "").strip() or DEFAULT_ANTHROPIC_MODEL
    # remote_bailian
    if stage is not None and stage.startswith("draft."):
        return (
            default_draft_model
            or os.environ.get("AIRCHECK_DRAFT_LLM_MODEL", "").strip()
            or os.environ.get("AIRCHECK_DEFAULT_LLM_MODEL", "").strip()
            or DEFAULT_DRAFT_MODEL
        )
    return (
        os.environ.get("AIRCHECK_DEFAULT_LLM_MODEL", "").strip()
        or DEFAULT_AI_ASSIST_MODEL
    )


def _build_default_stage_config(*, default_draft_model: str | None = None) -> dict[str, dict[str, str]]:
    env_provider = os.environ.get("AIRCHECK_DEFAULT_LLM_PROVIDER", "").strip()
    if env_provider and env_provider not in ALL_PROVIDERS:
        raise ValueError(f"Invalid AIRCHECK_DEFAULT_LLM_PROVIDER: {env_provider!r}")
    base_provider = env_provider or "remote_bailian"
    env_model = os.environ.get("AIRCHECK_DEFAULT_LLM_MODEL", "").strip()

    stages: dict[str, dict[str, str]] = {}
    for stage in ALL_STAGES:
        stage_env_key = "AIRCHECK_LLM_STAGE_" + stage.upper().replace(".", "_")
        stage_prov = os.environ.get(f"{stage_env_key}_PROVIDER", "").strip() or base_provider
        if stage_prov not in ALL_PROVIDERS:
            raise ValueError(f"Invalid provider in {stage_env_key}_PROVIDER: {stage_prov!r}")
        stage_model = os.environ.get(f"{stage_env_key}_MODEL", "").strip()
        if not stage_model:
            if stage_prov == base_provider and env_model:
                stage_model = env_model
            else:
                stage_model = _default_model_for_provider(
                    stage_prov, stage, default_draft_model=default_draft_model
                )
        stages[stage] = {"provider": stage_prov, "model": stage_model}
    return stages


class LlmRouter:
    """Thread-safe mixed local & remote LLM router with persistent per-stage configuration."""

    def __init__(
        self,
        config_path: Path | str | None = None,
        *,
        bl_bin: Path | str = DEFAULT_BL_BIN,
        bailian_config_path: Path | str = DEFAULT_BAILIAN_CONFIG_PATH,
        bailian_quota_state_path: Path | str | None = None,
        default_draft_model: str | None = None,
        timeout_sec: float = 45.0,
        probe_timeout_sec: float = 0.5,
        callers: dict[str, Callable[..., str]] | None = None,
        provider_urls: dict[str, str] | None = None,
    ) -> None:
        self._lock = threading.RLock()
        env_cfg = os.environ.get("AIRCHECK_LLM_CONFIG_PATH", "").strip()
        resolved_cfg = config_path if config_path is not None else (Path(env_cfg) if env_cfg else None)
        self._config_path: Path | None = Path(resolved_cfg) if resolved_cfg is not None else None
        self._bl_bin = Path(bl_bin)
        self._bailian_config_path = Path(bailian_config_path)
        if bailian_quota_state_path is not None:
            self._bailian_quota_state_path = Path(bailian_quota_state_path)
        elif self._bailian_config_path != DEFAULT_BAILIAN_CONFIG_PATH:
            self._bailian_quota_state_path = self._bailian_config_path.parent / "aircheck_quota_state.json"
        else:
            self._bailian_quota_state_path = default_quota_state_path()
        self._default_draft_model = default_draft_model
        self._timeout_sec = float(timeout_sec)
        self._probe_timeout_sec = float(probe_timeout_sec)
        self._callers = dict(callers) if callers else {}

        validated_ctor_urls: dict[str, str] = {}
        if provider_urls is not None:
            if not isinstance(provider_urls, dict):
                raise ValueError("provider_urls must be a dict")
            for prov, url_val in provider_urls.items():
                if prov not in ALL_PROVIDERS:
                    raise ValueError(f"Unknown provider in provider_urls: {prov!r}")
                if prov not in URL_CONFIGURABLE_PROVIDERS:
                    raise ValueError(f"Provider {prov!r} does not use a base URL")
                if url_val is not None and str(url_val).strip():
                    validated_ctor_urls[prov] = _validate_http_url(
                        url_val, field_name=f"provider_urls.{prov}"
                    )
        self._constructor_provider_urls: dict[str, str] = validated_ctor_urls

        self._stages: dict[str, dict[str, str]] = _build_default_stage_config(
            default_draft_model=default_draft_model
        )
        self._provider_urls: dict[str, str] = {}
        self._load_config()

    @property
    def config_path(self) -> Path | None:
        return self._config_path

    def _load_config(self) -> None:
        if self._config_path is None or not self._config_path.is_file():
            return
        try:
            raw = json.loads(self._config_path.read_text(encoding="utf-8"))
        except Exception:
            return
        if not isinstance(raw, dict):
            return

        raw_urls = raw.get("provider_urls")
        if isinstance(raw_urls, dict):
            for prov, url in raw_urls.items():
                if prov in LOCAL_PROVIDERS and isinstance(url, str) and url.strip():
                    try:
                        self._provider_urls[prov] = _validate_local_provider_url(
                            url, field_name=f"provider_urls.{prov}"
                        )
                    except ValueError:
                        pass

        raw_stages = raw.get("stages")
        if isinstance(raw_stages, dict):
            for stage, entry in raw_stages.items():
                if stage not in ALL_STAGES or not isinstance(entry, dict):
                    continue
                prov = str(entry.get("provider") or "").strip()
                if prov not in ALL_PROVIDERS:
                    continue
                model = entry.get("model")
                if prov == "local_mlx":
                    norm_model = (str(model).strip() if isinstance(model, str) else "") or DEFAULT_MLX_MODEL
                else:
                    if not isinstance(model, str) or not model.strip():
                        continue
                    norm_model = model.strip()
                self._stages[stage] = {"provider": prov, "model": norm_model}

    def _save_config_locked(self) -> None:
        if self._config_path is None:
            return
        self._config_path.parent.mkdir(parents=True, exist_ok=True)
        payload = {
            "stages": self._stages,
            "provider_urls": self._provider_urls,
        }
        encoded = json.dumps(payload, indent=2, sort_keys=True) + "\n"
        fd, tmp_name = tempfile.mkstemp(
            dir=str(self._config_path.parent),
            prefix=f".{self._config_path.name}.",
            suffix=".tmp",
        )
        tmp_path = Path(tmp_name)
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as fh:
                fh.write(encoded)
                fh.flush()
                os.fsync(fh.fileno())
            os.replace(tmp_path, self._config_path)
        finally:
            if tmp_path.exists():
                try:
                    tmp_path.unlink()
                except OSError:
                    pass

    def _effective_url(self, provider: str) -> str | None:
        with self._lock:
            override = self._provider_urls.get(provider) or self._constructor_provider_urls.get(provider)
        if override:
            return override
        return _default_url_for_provider(provider)

    def get_config(self) -> dict[str, Any]:
        with self._lock:
            merged_urls = dict(self._constructor_provider_urls)
            merged_urls.update(self._provider_urls)
            return {
                "stages": copy.deepcopy(self._stages),
                "provider_urls": copy.deepcopy(merged_urls),
            }

    def update_config(self, payload: dict[str, Any]) -> dict[str, Any]:
        if not isinstance(payload, dict):
            raise ValueError("LLM config body must be a JSON object")

        has_stages = "stages" in payload
        has_urls = "provider_urls" in payload
        direct_stage_keys = [k for k in payload if k in ALL_STAGES]
        unknown_keys = [
            k for k in payload if k not in ("stages", "provider_urls") and k not in ALL_STAGES
        ]
        if unknown_keys:
            raise ValueError(f"Unknown config key(s) or stage(s): {', '.join(sorted(unknown_keys))}")
        if not has_stages and not has_urls and not direct_stage_keys:
            raise ValueError("Config update must include 'stages' or 'provider_urls'")

        stage_updates: dict[str, Any] = {}
        if has_stages:
            raw_stages = payload["stages"]
            if not isinstance(raw_stages, dict):
                raise ValueError("'stages' must be a JSON object")
            stage_updates.update(raw_stages)
        for k in direct_stage_keys:
            stage_updates[k] = payload[k]

        validated_stages: dict[str, dict[str, str]] = {}
        for stage, entry in stage_updates.items():
            if stage not in ALL_STAGES:
                raise ValueError(f"Unknown LLM stage: {stage!r}")
            if not isinstance(entry, dict):
                raise ValueError(f"Stage config for {stage!r} must be an object")
            raw_provider = entry.get("provider")
            if not isinstance(raw_provider, str) or raw_provider.strip() not in ALL_PROVIDERS:
                raise ValueError(f"Unknown LLM provider for {stage!r}: {raw_provider!r}")
            provider = raw_provider.strip()

            raw_model = entry.get("model")
            if provider == "local_mlx":
                if raw_model is None:
                    norm_model = DEFAULT_MLX_MODEL
                elif isinstance(raw_model, str):
                    norm_model = raw_model.strip() or DEFAULT_MLX_MODEL
                else:
                    raise ValueError(f"model for stage {stage!r} must be a string")
            else:
                if not isinstance(raw_model, str) or not raw_model.strip():
                    raise ValueError(
                        f"model must be a non-empty string for provider {provider!r} on stage {stage!r}"
                    )
                norm_model = raw_model.strip()

            validated_stages[stage] = {"provider": provider, "model": norm_model}

        validated_urls: dict[str, str | None] = {}
        if has_urls:
            raw_urls = payload["provider_urls"]
            if not isinstance(raw_urls, dict):
                raise ValueError("'provider_urls' must be a JSON object")
            for prov, url_val in raw_urls.items():
                if prov not in ALL_PROVIDERS:
                    raise ValueError(f"Unknown provider in provider_urls: {prov!r}")
                if prov not in LOCAL_PROVIDERS:
                    raise ValueError(
                        f"provider_urls overrides via config API are only permitted for local providers (got {prov!r})"
                    )
                if url_val is None or (isinstance(url_val, str) and not url_val.strip()):
                    validated_urls[prov] = None
                else:
                    validated_urls[prov] = _validate_local_provider_url(
                        url_val, field_name=f"provider_urls.{prov}"
                    )

        with self._lock:
            for stage, cfg in validated_stages.items():
                self._stages[stage] = cfg
            for prov, url in validated_urls.items():
                if url is None:
                    self._provider_urls.pop(prov, None)
                else:
                    self._provider_urls[prov] = url
            self._save_config_locked()
            merged_urls = dict(self._constructor_provider_urls)
            merged_urls.update(self._provider_urls)
            return {
                "status": "ok",
                "stages": copy.deepcopy(self._stages),
                "provider_urls": copy.deepcopy(merged_urls),
            }

    def _http_get_json_probe(self, url: str, timeout: float) -> tuple[int, Any]:
        req = urllib.request.Request(url, method="GET")
        with _NO_REDIRECT_OPENER.open(req, timeout=timeout) as resp:
            status = int(resp.status)
            raw = resp.read().decode("utf-8", errors="replace")
        return status, json.loads(raw)

    def _probe_local_provider(self, provider: str) -> dict[str, Any]:
        base_url = self._effective_url(provider) or ""
        timeout = self._probe_timeout_sec
        if provider == "local_mlx":
            try:
                status, data = self._http_get_json_probe(f"{base_url}/health", timeout)
                if status == 200 and isinstance(data, dict) and data.get("status") == "ok":
                    return {"available": True, "detail": "ok", "models": [DEFAULT_MLX_MODEL]}
                return {"available": False, "detail": f"unexpected /health payload: {data!r}", "models": []}
            except json.JSONDecodeError:
                return {"available": False, "detail": "non-JSON response from /health", "models": []}
            except Exception as exc:
                return {"available": False, "detail": str(exc), "models": []}

        if provider == "local_ollama":
            try:
                status, data = self._http_get_json_probe(f"{base_url}/api/tags", timeout)
                if status == 200 and isinstance(data, dict) and isinstance(data.get("models"), list):
                    names = [
                        str(m.get("name") or "")
                        for m in data["models"]
                        if isinstance(m, dict) and m.get("name")
                    ]
                    return {"available": True, "detail": "ok", "models": names}
                return {"available": False, "detail": "unexpected /api/tags payload", "models": []}
            except json.JSONDecodeError:
                return {"available": False, "detail": "non-JSON response from /api/tags", "models": []}
            except Exception as exc:
                return {"available": False, "detail": str(exc), "models": []}

        if provider == "local_llama_cpp":
            try:
                status, data = self._http_get_json_probe(f"{base_url}/health", timeout)
                if status == 200 and isinstance(data, dict) and data.get("status") == "ok":
                    return {"available": True, "detail": "ok", "models": []}
                return {"available": False, "detail": f"unexpected /health payload: {data!r}", "models": []}
            except urllib.error.HTTPError as exc:
                if exc.code == 404:
                    try:
                        st2, d2 = self._http_get_json_probe(f"{base_url}/v1/models", timeout)
                        if st2 == 200 and isinstance(d2, dict) and isinstance(d2.get("data"), list):
                            names = [
                                str(m.get("id") or "")
                                for m in d2["data"]
                                if isinstance(m, dict) and m.get("id")
                            ]
                            return {"available": True, "detail": "ok", "models": names}
                    except Exception as exc2:
                        return {"available": False, "detail": str(exc2), "models": []}
                return {"available": False, "detail": f"HTTP {exc.code}", "models": []}
            except json.JSONDecodeError:
                return {"available": False, "detail": "non-JSON response from /health", "models": []}
            except Exception as exc:
                return {"available": False, "detail": str(exc), "models": []}

        return {"available": False, "detail": "unknown local provider", "models": []}

    def _resolve_bl_bin(self) -> Path | None:
        if self._bl_bin.is_file():
            return self._bl_bin
        found = shutil.which("bl")
        if found:
            p = Path(found)
            if p.is_file():
                return p
        return None

    def _has_bailian_credentials(self) -> bool:
        if os.environ.get("DASHSCOPE_API_KEY", "").strip() or os.environ.get("BAILIAN_API_KEY", "").strip():
            return True
        if self._bailian_config_path.is_file():
            try:
                return self._bailian_config_path.stat().st_size > 0
            except OSError:
                return False
        return False

    def _probe_remote_provider(self, provider: str) -> dict[str, Any]:
        if provider == "remote_bailian":
            bl = self._resolve_bl_bin()
            if bl is None:
                return {"available": False, "detail": "bl binary not found", "models": []}
            if not self._has_bailian_credentials():
                return {
                    "available": False,
                    "detail": "missing ~/.bailian/config.json and DASHSCOPE_API_KEY/BAILIAN_API_KEY",
                    "models": [],
                }
            quota_block = active_bailian_quota_block(state_path=self._bailian_quota_state_path)
            if quota_block is not None:
                return {
                    "available": False,
                    "detail": str(quota_block.get("detail") or "Bailian quota exhausted (HTTP 429)"),
                    "models": [],
                }
            return {"available": True, "detail": "ok", "models": [DEFAULT_DRAFT_MODEL, DEFAULT_AI_ASSIST_MODEL]}

        if provider == "remote_openai":
            if os.environ.get("OPENAI_API_KEY", "").strip():
                return {"available": True, "detail": "ok", "models": [DEFAULT_OPENAI_MODEL]}
            return {"available": False, "detail": "OPENAI_API_KEY not set", "models": []}

        if provider == "remote_gemini":
            if (os.environ.get("GEMINI_API_KEY") or os.environ.get("GOOGLE_API_KEY") or "").strip():
                return {"available": True, "detail": "ok", "models": [DEFAULT_GEMINI_MODEL]}
            return {"available": False, "detail": "GEMINI_API_KEY / GOOGLE_API_KEY not set", "models": []}

        if provider == "remote_anthropic":
            if os.environ.get("ANTHROPIC_API_KEY", "").strip():
                return {"available": True, "detail": "ok", "models": [DEFAULT_ANTHROPIC_MODEL]}
            return {"available": False, "detail": "ANTHROPIC_API_KEY not set", "models": []}

        return {"available": False, "detail": "unknown provider", "models": []}

    def list_providers(self) -> dict[str, Any]:
        local_list = [p for p in ALL_PROVIDERS if p in LOCAL_PROVIDERS]
        local_probes: dict[str, dict[str, Any]] = {}
        with concurrent.futures.ThreadPoolExecutor(max_workers=len(local_list)) as pool:
            futures = {pool.submit(self._probe_local_provider, p): p for p in local_list}
            for fut, prov in futures.items():
                try:
                    local_probes[prov] = fut.result(timeout=self._probe_timeout_sec + 0.2)
                except Exception as exc:
                    local_probes[prov] = {"available": False, "detail": str(exc), "models": []}

        providers_out: list[dict[str, Any]] = []
        for prov in ALL_PROVIDERS:
            is_local = prov in LOCAL_PROVIDERS
            probe = local_probes[prov] if is_local else self._probe_remote_provider(prov)
            providers_out.append(
                {
                    "id": prov,
                    "label": PROVIDER_LABELS[prov],
                    "is_local": is_local,
                    "available": bool(probe["available"]),
                    "base_url": self._effective_url(prov),
                    "default_model": _default_model_for_provider(
                        prov, default_draft_model=self._default_draft_model
                    ),
                    "detail": probe["detail"],
                    "models": probe.get("models", []),
                }
            )

        cfg = self.get_config()
        return {
            "providers": providers_out,
            "stages": cfg["stages"],
            "provider_urls": cfg["provider_urls"],
        }

    def _post_json_http(
        self,
        url: str,
        payload: dict[str, Any],
        *,
        headers: dict[str, str] | None = None,
        provider: str,
        stage: str,
        model: str,
        timeout: float,
    ) -> Any:
        req_headers = {"Content-Type": "application/json"}
        if headers:
            req_headers.update(headers)
        body = json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(url, data=body, headers=req_headers, method="POST")
        try:
            with _NO_REDIRECT_OPENER.open(req, timeout=timeout) as resp:
                raw = resp.read().decode("utf-8", errors="replace")
        except urllib.error.HTTPError as exc:
            err_body = ""
            try:
                err_body = exc.read().decode("utf-8", errors="replace")[:500]
            except Exception:
                pass
            raise LlmProviderError(
                f"{provider} HTTP {exc.code}: {err_body or exc.reason}",
                provider=provider,
                stage=stage,
                model=model,
                status_code=502,
                error_code="llm_http_error",
            ) from exc
        except urllib.error.URLError as exc:
            if isinstance(exc.reason, (TimeoutError, socket.timeout)):
                raise LlmProviderError(
                    f"{provider} timed out after {timeout:.1f}s",
                    provider=provider,
                    stage=stage,
                    model=model,
                    status_code=502,
                    error_code="llm_timeout",
                ) from exc
            raise LlmUnavailableError(
                f"{provider} unreachable at {url}: {exc.reason}",
                provider=provider,
                stage=stage,
                model=model,
            ) from exc
        except (TimeoutError, socket.timeout) as exc:
            raise LlmProviderError(
                f"{provider} timed out after {timeout:.1f}s",
                provider=provider,
                stage=stage,
                model=model,
                status_code=502,
                error_code="llm_timeout",
            ) from exc
        except OSError as exc:
            raise LlmUnavailableError(
                f"{provider} connection failed at {url}: {exc}",
                provider=provider,
                stage=stage,
                model=model,
            ) from exc

        try:
            return json.loads(raw)
        except json.JSONDecodeError as exc:
            raise LlmProviderError(
                f"{provider} returned non-JSON response: {raw[:240]}",
                provider=provider,
                stage=stage,
                model=model,
                status_code=502,
                error_code="llm_invalid_response",
            ) from exc

    def _invoke_provider(
        self,
        *,
        provider: str,
        model: str,
        stage: str,
        system_prompt: str,
        user_prompt: str,
        temperature: float,
        max_tokens: int,
        timeout: float,
    ) -> str:
        if provider in self._callers:
            text = self._callers[provider](
                stage=stage,
                model=model,
                system_prompt=system_prompt,
                user_prompt=user_prompt,
                max_tokens=max_tokens,
            )
            cleaned = _strip_think_prefix(str(text or ""))
            if not cleaned:
                raise LlmProviderError(
                    f"{provider} returned empty response",
                    provider=provider,
                    stage=stage,
                    model=model,
                    status_code=502,
                    error_code="llm_empty_response",
                )
            return cleaned

        if provider == "local_mlx":
            base_url = self._effective_url("local_mlx") or DEFAULT_MLX_URL
            data = self._post_json_http(
                f"{base_url}/v1/chat/completions",
                {
                    "model": model or DEFAULT_MLX_MODEL,
                    "messages": [
                        {"role": "system", "content": system_prompt},
                        {"role": "user", "content": user_prompt},
                    ],
                    "temperature": temperature,
                    "max_tokens": max_tokens,
                    "stream": False,
                },
                provider=provider,
                stage=stage,
                model=model,
                timeout=timeout,
            )
            try:
                text = _extract_openai_chat_content(data)
            except ValueError as exc:
                raise LlmProviderError(
                    f"local_mlx invalid response: {exc}",
                    provider=provider,
                    stage=stage,
                    model=model,
                    status_code=502,
                    error_code="llm_invalid_response",
                ) from exc
            if not text:
                raise LlmProviderError(
                    "local_mlx returned empty assistant content",
                    provider=provider,
                    stage=stage,
                    model=model,
                    status_code=502,
                    error_code="llm_empty_response",
                )
            return text

        if provider == "local_ollama":
            base_url = self._effective_url("local_ollama") or DEFAULT_OLLAMA_URL
            data = self._post_json_http(
                f"{base_url}/api/chat",
                {
                    "model": model,
                    "messages": [
                        {"role": "system", "content": system_prompt},
                        {"role": "user", "content": user_prompt},
                    ],
                    "stream": False,
                    "options": {"temperature": temperature, "num_predict": max_tokens},
                },
                provider=provider,
                stage=stage,
                model=model,
                timeout=timeout,
            )
            if isinstance(data, dict) and isinstance(data.get("error"), str) and data["error"]:
                raise LlmProviderError(
                    f"local_ollama error: {data['error']}",
                    provider=provider,
                    stage=stage,
                    model=model,
                    status_code=502,
                )
            msg = data.get("message") if isinstance(data, dict) else None
            raw_content = msg.get("content") if isinstance(msg, dict) else None
            text = _strip_think_prefix(raw_content) if isinstance(raw_content, str) else ""
            if not text:
                raise LlmProviderError(
                    "local_ollama returned empty assistant content",
                    provider=provider,
                    stage=stage,
                    model=model,
                    status_code=502,
                    error_code="llm_empty_response",
                )
            return text

        if provider == "local_llama_cpp":
            base_url = self._effective_url("local_llama_cpp") or DEFAULT_LLAMA_CPP_URL
            data = self._post_json_http(
                f"{base_url}/v1/chat/completions",
                {
                    "model": model or DEFAULT_LLAMA_CPP_MODEL,
                    "messages": [
                        {"role": "system", "content": system_prompt},
                        {"role": "user", "content": user_prompt},
                    ],
                    "temperature": temperature,
                    "max_tokens": max_tokens,
                    "stream": False,
                },
                provider=provider,
                stage=stage,
                model=model,
                timeout=timeout,
            )
            try:
                text = _extract_openai_chat_content(data)
            except ValueError as exc:
                raise LlmProviderError(
                    f"local_llama_cpp invalid response: {exc}",
                    provider=provider,
                    stage=stage,
                    model=model,
                    status_code=502,
                    error_code="llm_invalid_response",
                ) from exc
            if not text:
                raise LlmProviderError(
                    "local_llama_cpp returned empty assistant content",
                    provider=provider,
                    stage=stage,
                    model=model,
                    status_code=502,
                    error_code="llm_empty_response",
                )
            return text

        if provider == "remote_bailian":
            resolved_bl = self._resolve_bl_bin()
            if resolved_bl is None:
                raise LlmUnavailableError(
                    "Bailian CLI `bl` is not available on this Mac rig.",
                    provider=provider,
                    stage=stage,
                    model=model,
                )
            if not self._has_bailian_credentials():
                raise LlmUnavailableError(
                    "Bailian credentials missing (~/.bailian/config.json and DASHSCOPE_API_KEY/BAILIAN_API_KEY unset).",
                    provider=provider,
                    stage=stage,
                    model=model,
                )
            try:
                proc = subprocess.run(
                    [
                        str(resolved_bl),
                        "text",
                        "chat",
                        "--model",
                        model,
                        "--enable-thinking",
                        "false",
                        "--enable-search",
                        "false",
                        "--system",
                        system_prompt,
                        "--message",
                        user_prompt,
                        "--output",
                        "json",
                    ],
                    capture_output=True,
                    text=True,
                    timeout=timeout,
                    check=False,
                )
            except subprocess.TimeoutExpired as exc:
                raise LlmProviderError(
                    f"bl text chat timed out after {timeout:.1f}s",
                    provider=provider,
                    stage=stage,
                    model=model,
                    status_code=502,
                    error_code="llm_timeout",
                ) from exc
            except OSError as exc:
                raise LlmUnavailableError(
                    f"Failed to execute bl CLI: {exc}",
                    provider=provider,
                    stage=stage,
                    model=model,
                ) from exc

            if proc.returncode != 0:
                err_summary = _summarize_subprocess_error("bl text chat", proc)
                record_bailian_quota_exhausted(
                    err_summary,
                    state_path=self._bailian_quota_state_path,
                )
                raise LlmProviderError(
                    err_summary,
                    provider=provider,
                    stage=stage,
                    model=model,
                    status_code=502,
                )
            clear_bailian_quota_state(state_path=self._bailian_quota_state_path)
            try:
                text = _strip_think_prefix(parse_bl_chat_content(proc.stdout))
            except RuntimeError as exc:
                raise LlmProviderError(
                    str(exc),
                    provider=provider,
                    stage=stage,
                    model=model,
                    status_code=502,
                    error_code="llm_invalid_response",
                ) from exc
            if not text:
                raise LlmProviderError(
                    "bl text chat returned empty content.",
                    provider=provider,
                    stage=stage,
                    model=model,
                    status_code=502,
                    error_code="llm_empty_response",
                )
            return text

        if provider == "remote_openai":
            api_key = os.environ.get("OPENAI_API_KEY", "").strip()
            if not api_key:
                raise LlmUnavailableError(
                    "OPENAI_API_KEY is not set.",
                    provider=provider,
                    stage=stage,
                    model=model,
                )
            base_url = self._effective_url("remote_openai") or DEFAULT_OPENAI_BASE_URL
            url = f"{base_url}/chat/completions"
            headers = {"Authorization": f"Bearer {api_key}"}
            body_primary = {
                "model": model,
                "messages": [
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": user_prompt},
                ],
                "temperature": temperature,
                "max_completion_tokens": max_tokens,
            }
            try:
                data = self._post_json_http(
                    url,
                    body_primary,
                    headers=headers,
                    provider=provider,
                    stage=stage,
                    model=model,
                    timeout=timeout,
                )
            except LlmProviderError as exc:
                if exc.error_code == "llm_http_error" and "max_completion_tokens" in str(exc):
                    body_fallback = {
                        "model": model,
                        "messages": body_primary["messages"],
                        "temperature": temperature,
                        "max_tokens": max_tokens,
                    }
                    data = self._post_json_http(
                        url,
                        body_fallback,
                        headers=headers,
                        provider=provider,
                        stage=stage,
                        model=model,
                        timeout=timeout,
                    )
                else:
                    raise
            try:
                text = _extract_openai_chat_content(data)
            except ValueError as exc:
                raise LlmProviderError(
                    f"remote_openai invalid response: {exc}",
                    provider=provider,
                    stage=stage,
                    model=model,
                    status_code=502,
                    error_code="llm_invalid_response",
                ) from exc
            if not text:
                raise LlmProviderError(
                    "remote_openai returned empty assistant content",
                    provider=provider,
                    stage=stage,
                    model=model,
                    status_code=502,
                    error_code="llm_empty_response",
                )
            return text

        if provider == "remote_gemini":
            api_key = (
                os.environ.get("GEMINI_API_KEY") or os.environ.get("GOOGLE_API_KEY") or ""
            ).strip()
            if not api_key:
                raise LlmUnavailableError(
                    "GEMINI_API_KEY / GOOGLE_API_KEY is not set.",
                    provider=provider,
                    stage=stage,
                    model=model,
                )
            base_url = self._effective_url("remote_gemini") or DEFAULT_GEMINI_BASE_URL
            encoded_model = urllib.parse.quote(model, safe="")
            encoded_key = urllib.parse.quote(api_key, safe="")
            url = f"{base_url}/v1beta/models/{encoded_model}:generateContent?key={encoded_key}"
            data = self._post_json_http(
                url,
                {
                    "systemInstruction": {"parts": [{"text": system_prompt}]},
                    "contents": [{"role": "user", "parts": [{"text": user_prompt}]}],
                    "generationConfig": {
                        "temperature": temperature,
                        "maxOutputTokens": max_tokens,
                    },
                },
                provider=provider,
                stage=stage,
                model=model,
                timeout=timeout,
            )
            if not isinstance(data, dict):
                raise LlmProviderError(
                    "remote_gemini returned non-object JSON",
                    provider=provider,
                    stage=stage,
                    model=model,
                    status_code=502,
                    error_code="llm_invalid_response",
                )
            feedback = data.get("promptFeedback")
            if isinstance(feedback, dict) and feedback.get("blockReason"):
                raise LlmProviderError(
                    f"remote_gemini blocked prompt: {feedback.get('blockReason')}",
                    provider=provider,
                    stage=stage,
                    model=model,
                    status_code=502,
                )
            candidates = data.get("candidates")
            if not isinstance(candidates, list) or not candidates:
                raise LlmProviderError(
                    "remote_gemini returned no candidates",
                    provider=provider,
                    stage=stage,
                    model=model,
                    status_code=502,
                    error_code="llm_empty_response",
                )
            first = candidates[0] if isinstance(candidates[0], dict) else {}
            content = first.get("content") if isinstance(first.get("content"), dict) else {}
            parts = content.get("parts") if isinstance(content.get("parts"), list) else []
            text = _strip_think_prefix(
                "".join(str(p.get("text") or "") for p in parts if isinstance(p, dict))
            )
            if not text:
                raise LlmProviderError(
                    "remote_gemini returned empty content",
                    provider=provider,
                    stage=stage,
                    model=model,
                    status_code=502,
                    error_code="llm_empty_response",
                )
            return text

        if provider == "remote_anthropic":
            api_key = os.environ.get("ANTHROPIC_API_KEY", "").strip()
            if not api_key:
                raise LlmUnavailableError(
                    "ANTHROPIC_API_KEY is not set.",
                    provider=provider,
                    stage=stage,
                    model=model,
                )
            base_url = self._effective_url("remote_anthropic") or DEFAULT_ANTHROPIC_BASE_URL
            data = self._post_json_http(
                f"{base_url}/v1/messages",
                {
                    "model": model,
                    "max_tokens": max_tokens,
                    "temperature": temperature,
                    "system": system_prompt,
                    "messages": [{"role": "user", "content": user_prompt}],
                },
                headers={
                    "x-api-key": api_key,
                    "anthropic-version": "2023-06-01",
                },
                provider=provider,
                stage=stage,
                model=model,
                timeout=timeout,
            )
            if not isinstance(data, dict):
                raise LlmProviderError(
                    "remote_anthropic returned non-object JSON",
                    provider=provider,
                    stage=stage,
                    model=model,
                    status_code=502,
                    error_code="llm_invalid_response",
                )
            blocks = data.get("content")
            if not isinstance(blocks, list):
                raise LlmProviderError(
                    "remote_anthropic response missing content blocks",
                    provider=provider,
                    stage=stage,
                    model=model,
                    status_code=502,
                    error_code="llm_invalid_response",
                )
            text = _strip_think_prefix(
                "".join(
                    str(blk.get("text") or "")
                    for blk in blocks
                    if isinstance(blk, dict) and blk.get("type") == "text"
                )
            )
            if not text:
                raise LlmProviderError(
                    "remote_anthropic returned empty text content",
                    provider=provider,
                    stage=stage,
                    model=model,
                    status_code=502,
                    error_code="llm_empty_response",
                )
            return text

        raise ValueError(f"Unsupported LLM provider: {provider!r}")

    def complete(
        self,
        stage: str,
        system_prompt: str,
        user_prompt: str,
        *,
        llm_provider: str | None = None,
        llm_model: str | None = None,
        temperature: float = 0.2,
        max_tokens: int | None = None,
        timeout_sec: float | None = None,
    ) -> LlmResult:
        if stage not in ALL_STAGES:
            raise ValueError(f"Unknown LLM stage: {stage!r}")

        if llm_provider is not None:
            if not isinstance(llm_provider, str) or llm_provider.strip() not in ALL_PROVIDERS:
                raise ValueError(f"Unknown LLM provider: {llm_provider!r}")
            override_provider: str | None = llm_provider.strip()
        else:
            override_provider = None

        if llm_model is not None:
            if not isinstance(llm_model, str) or not llm_model.strip():
                raise ValueError("llm_model must be a non-empty string")
            override_model: str | None = llm_model.strip()
        else:
            override_model = None

        with self._lock:
            stage_cfg = dict(self._stages[stage])

        provider = override_provider or stage_cfg["provider"]
        if override_model is not None:
            model = override_model
        elif override_provider is not None and override_provider != stage_cfg["provider"]:
            model = _default_model_for_provider(
                provider, stage, default_draft_model=self._default_draft_model
            )
        else:
            model = stage_cfg["model"]

        if provider == "local_mlx" and not model:
            model = DEFAULT_MLX_MODEL

        effective_max_tokens = int(max_tokens or STAGE_DEFAULT_MAX_TOKENS.get(stage, 1024))
        effective_timeout = float(timeout_sec if timeout_sec is not None else self._timeout_sec)

        t0 = time.monotonic()
        text = self._invoke_provider(
            provider=provider,
            model=model,
            stage=stage,
            system_prompt=system_prompt,
            user_prompt=user_prompt,
            temperature=temperature,
            max_tokens=effective_max_tokens,
            timeout=effective_timeout,
        )
        latency_ms = max(0, int(round((time.monotonic() - t0) * 1000.0)))
        return LlmResult(
            text=text,
            provider=provider,
            model=model,
            is_local=(provider in LOCAL_PROVIDERS),
            latency_ms=latency_ms,
        )

    def plan_broll_prompts(
        self,
        clauses: Sequence[dict[str, Any] | str],
        *,
        story_context: str = "",
        llm_provider: str | None = None,
        llm_model: str | None = None,
    ) -> dict[str, Any]:
        """Generates structured B-roll visual prompts & captions for clauses via stage ``broll_planner``."""
        if not isinstance(clauses, (list, tuple)) or not clauses:
            raise ValueError("clauses must be a non-empty list")

        normalized_inputs: list[dict[str, str]] = []
        for idx, item in enumerate(clauses):
            if isinstance(item, str):
                spoken = item.strip()
                cid = f"c{idx}"
            elif isinstance(item, dict):
                cid = str(item.get("id") or f"c{idx}").strip()
                spoken = str(item.get("spoken_text") or item.get("text") or "").strip()
            else:
                raise ValueError("Each clause entry must be a dict or string")
            if not spoken:
                raise ValueError(f"Clause {cid!r} has empty spoken text")
            normalized_inputs.append({"clause_id": cid, "spoken_text": spoken})

        system_prompt = (
            "You are a broadcast newsroom visual director generating concrete photorealistic "
            "B-roll plate prompts and concise graphic captions for spoken script clauses. "
            "Never include people's faces unless implied by newsroom scenes, avoid text inside image prompts, "
            "and write plain ASCII JSON ONLY matching the exact requested schema."
        )
        clause_lines = "\n".join(
            f"- {entry['clause_id']}: {entry['spoken_text']}" for entry in normalized_inputs
        )
        ctx_block = f"STORY CONTEXT: {story_context.strip()}\n\n" if story_context.strip() else ""
        user_prompt = (
            f"{ctx_block}CLAUSES:\n{clause_lines}\n\n"
            "For each clause, produce:\n"
            "- clause_id: exact id from input\n"
            "- b_roll_prompt: concrete photographic B-roll image generation prompt (10-24 words)\n"
            "- broll_caption: short 2-5 word lower-third/B-roll label\n"
            "- depth_text_tag: short 1-4 word stat/tag callout if a concrete number/fact appears, else \"\"\n\n"
            "Return STRICT JSON ONLY:\n"
            '{"items":[{"clause_id":"...","b_roll_prompt":"...","broll_caption":"...","depth_text_tag":""}]}'
        )

        res = self.complete(
            "broll_planner",
            system_prompt,
            user_prompt,
            llm_provider=llm_provider,
            llm_model=llm_model,
            max_tokens=STAGE_DEFAULT_MAX_TOKENS["broll_planner"],
        )
        objs = _extract_json_objects(res.text)
        parsed: dict[str, Any] | None = None
        for obj in objs:
            if "items" in obj:
                parsed = obj
                break
        if parsed is None:
            raise LlmProviderError(
                f"broll_planner returned invalid JSON: {res.text[:240]}",
                provider=res.provider,
                stage="broll_planner",
                model=res.model,
                status_code=502,
                error_code="llm_invalid_response",
            )
        raw_items = parsed.get("items")
        if not isinstance(raw_items, list) or not raw_items:
            raise LlmProviderError(
                "broll_planner response missing non-empty 'items' list",
                provider=res.provider,
                stage="broll_planner",
                model=res.model,
                status_code=502,
                error_code="llm_invalid_response",
            )

        by_id: dict[str, tuple[int, dict[str, str]]] = {}
        positional: list[dict[str, str]] = []
        for entry in raw_items:
            if not isinstance(entry, dict):
                continue
            cid = str(entry.get("clause_id") or "").strip()
            prompt = str(entry.get("b_roll_prompt") or "").strip()
            caption = str(entry.get("broll_caption") or "").strip()
            depth_tag = str(entry.get("depth_text_tag") or "").strip()
            if not prompt:
                continue
            norm = {
                "clause_id": cid,
                "b_roll_prompt": prompt,
                "broll_caption": caption,
                "depth_text_tag": depth_tag,
            }
            pos_idx = len(positional)
            positional.append(norm)
            if cid and cid not in by_id:
                by_id[cid] = (pos_idx, norm)

        matched: list[dict[str, str] | None] = [None] * len(normalized_inputs)
        claimed_indices: set[int] = set()
        for idx, inp in enumerate(normalized_inputs):
            cid = inp["clause_id"]
            if cid in by_id:
                pos_idx, item_norm = by_id[cid]
                matched[idx] = item_norm
                claimed_indices.add(pos_idx)

        out_items: list[dict[str, str]] = []
        next_unclaimed = 0
        for idx, inp in enumerate(normalized_inputs):
            cid = inp["clause_id"]
            chosen = matched[idx]
            if chosen is None:
                while next_unclaimed < len(positional) and next_unclaimed in claimed_indices:
                    next_unclaimed += 1
                if next_unclaimed < len(positional):
                    chosen = positional[next_unclaimed]
                    claimed_indices.add(next_unclaimed)
                    next_unclaimed += 1
            if chosen is None or not chosen["b_roll_prompt"]:
                raise LlmProviderError(
                    f"broll_planner missing B-roll prompt for clause {cid!r}",
                    provider=res.provider,
                    stage="broll_planner",
                    model=res.model,
                    status_code=502,
                    error_code="llm_invalid_response",
                )
            out_items.append(
                {
                    "clause_id": cid,
                    "b_roll_prompt": chosen["b_roll_prompt"],
                    "broll_caption": chosen["broll_caption"],
                    "depth_text_tag": chosen["depth_text_tag"],
                }
            )

        return {
            "items": out_items,
            "llm_meta": res.llm_meta,
        }
