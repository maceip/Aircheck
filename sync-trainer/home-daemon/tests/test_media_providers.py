from __future__ import annotations

import hashlib
import json
import subprocess
import sys
import threading
import types
import urllib.request
from pathlib import Path
from unittest.mock import Mock

import numpy as np
import pytest

from media_providers.chatterbox import VARIANTS, generate_local, offline_environment, select_device, validate_config
from media_providers.evaluation import compare_contours, timing_metrics
from media_providers.musetalk import avatar_cache_key
from media_providers.registry import ProviderConfig, build_provider_catalog, load_config, verify_artifacts
from media_providers.session import MediaSession, MediaStamp


def _artifact_manifest(tmp_path: Path, provider: str = "chatterbox", variant: str = "turbo") -> tuple[Path, Path, dict]:
    root = tmp_path / "models"
    root.mkdir()
    names = VARIANTS[variant][2] if provider == "chatterbox" else {"weights.bin"}
    for name in names:
        (root / name).write_bytes(b"test-only model fixture")
    data = {
        "provider": provider, "variant": variant, "source_revision": "reviewed-test-revision",
        "model_revision": "test-model-revision", "license_review": "test-only approval",
        "commercial_use_approved": True,
        "files": {name: hashlib.sha256((root / name).read_bytes()).hexdigest() for name in names},
    }
    manifest = tmp_path / "manifest.json"
    manifest.write_text(json.dumps(data))
    return root, manifest, data


def _chatterbox_config(tmp_path: Path, variant: str = "turbo") -> dict:
    root, manifest, _ = _artifact_manifest(tmp_path, variant=variant)
    reference = tmp_path / "reference.wav"
    reference.write_bytes(b"test-only reference fixture")
    return {"variant": variant, "model_dir": str(root), "artifact_manifest": str(manifest), "reference_audio": str(reference), "reference_consent": True, "device": "cpu"}


def test_no_config_preserves_existing_backends():
    cfg = load_config(None)
    assert cfg == ProviderConfig()
    assert cfg.tts_backend == "auto"
    assert cfg.video_backend == "heygen"
    result = build_provider_catalog(
        cfg,
        s2pro_available=True,
        lambda_backends=["chatterbox", "f5"],
        heygen_options={"available": True, "looks": [], "voice": None},
        heygen_engines=[{"id": "heygen-avatar-6", "label": "Avatar 6", "resolutions": ["1080p", "2k"]}],
        default_heygen_look_id="look_1",
        default_heygen_engine="heygen-avatar-6",
        default_heygen_resolution="1080p",
        default_heygen_voice_id="voice_1",
    )
    assert result["tts_backend"] == "auto"
    assert result["video_backend"] == "heygen"
    assert result["automatic_downloads"] is False
    voices = {v["id"]: v for v in result["voice_backends"]}
    videos = {v["id"]: v for v in result["video_backends"]}
    assert voices["chatterbox_local"]["available"] is False
    assert voices["s2pro_v002"]["available"] is True
    assert videos["heygen"]["available"] is True
    assert videos["musetalk"]["available"] is False


@pytest.mark.parametrize("raw", [[], {"schema_version": True}, {"schema_version": 1, "unknown": True}, {"schema_version": 1, "tts_backend": "amphion"}, {"schema_version": 1, "video_backend": "musetalk"}, {"schema_version": 1, "chatterbox": []}])
def test_invalid_config_fails_closed(tmp_path, raw):
    path = tmp_path / "config.json"
    path.write_text(json.dumps(raw))
    with pytest.raises(ValueError):
        load_config(path)


def test_explicit_missing_config_is_not_a_default(tmp_path):
    with pytest.raises(FileNotFoundError):
        load_config(tmp_path / "missing.json")


def test_manifest_verifies_required_local_files(tmp_path):
    root, manifest, data = _artifact_manifest(tmp_path)
    assert verify_artifacts(root, manifest, provider="chatterbox", required=VARIANTS["turbo"][2]) == data
    (root / "ve.safetensors").write_bytes(b"tampered")
    with pytest.raises(ValueError, match="checksum mismatch"):
        verify_artifacts(root, manifest, provider="chatterbox", required=VARIANTS["turbo"][2])


@pytest.mark.parametrize("field,value", [("commercial_use_approved", False), ("license_review", ""), ("model_revision", ""), ("provider", "musetalk")])
def test_manifest_requires_explicit_clearance(tmp_path, field, value):
    root, manifest, data = _artifact_manifest(tmp_path)
    data[field] = value
    manifest.write_text(json.dumps(data))
    with pytest.raises(ValueError):
        verify_artifacts(root, manifest, provider="chatterbox", required=VARIANTS["turbo"][2])


def test_manifest_rejects_traversal_and_escaping_symlink(tmp_path):
    root, manifest, data = _artifact_manifest(tmp_path)
    outside = tmp_path / "outside.bin"
    outside.write_bytes(b"outside")
    data["files"] = {"../outside.bin": hashlib.sha256(b"outside").hexdigest()}
    manifest.write_text(json.dumps(data))
    with pytest.raises(ValueError, match="traversal"):
        verify_artifacts(root, manifest, provider="chatterbox", required=set())
    (root / "outside.bin").symlink_to(outside)
    data["files"] = {"outside.bin": hashlib.sha256(b"outside").hexdigest()}
    manifest.write_text(json.dumps(data))
    with pytest.raises(ValueError, match="escapes"):
        verify_artifacts(root, manifest, provider="chatterbox", required=set())


def test_reference_consent_and_optional_pickle_are_checked(tmp_path):
    config = _chatterbox_config(tmp_path)
    validate_config(config)
    with pytest.raises(ValueError, match="consent"):
        validate_config(dict(config, reference_consent=False))
    (Path(config["model_dir"]) / "conds.pt").write_bytes(b"unreviewed pickle")
    with pytest.raises(ValueError, match="missing required files"):
        validate_config(config)


def test_interpreter_symlink_preserves_virtual_environment(tmp_path):
    from media_providers.chatterbox import ChatterboxSynthesizer

    interpreter = tmp_path / "venv" / "bin" / "python"
    interpreter.parent.mkdir(parents=True)
    interpreter.symlink_to(sys.executable)
    provider = ChatterboxSynthesizer({"python_bin": str(interpreter)}, Path(sys.executable))
    assert provider.python_bin == interpreter
    assert provider.python_bin.is_symlink()


def test_mps_selection_does_not_hide_explicit_failure():
    torch = types.SimpleNamespace(backends=types.SimpleNamespace(mps=types.SimpleNamespace(is_available=lambda: False)))
    assert select_device(torch, "auto") == "cpu"
    with pytest.raises(RuntimeError, match="no silent CPU fallback"):
        select_device(torch, "mps")
    torch.backends.mps.is_available = lambda: True
    assert select_device(torch, "auto") == "mps"
    assert select_device(torch, "cpu") == "cpu"


def test_offline_audit_hook_rejects_python_network_without_connecting():
    worker = Path(__file__).resolve().parents[1] / "media_providers" / "offline_runner.py"
    code = f"import runpy, sys, socket; module = runpy.run_path({str(worker)!r}); sys.addaudithook(module['deny_network']); socket.create_connection(('127.0.0.1', 9))"
    proc = subprocess.run([sys.executable, "-c", code], capture_output=True, text=True, timeout=10)
    assert proc.returncode != 0
    assert "network access is disabled" in proc.stderr


def test_musetalk_only_stages_tracked_sources(monkeypatch, tmp_path):
    import media_providers.musetalk as musetalk

    repo = tmp_path / "repo"
    (repo / "scripts").mkdir(parents=True)
    (repo / "scripts" / "inference.py").write_text("# reviewed fixture")
    (repo / "scripts" / "untracked.py").write_text("raise AssertionError('untrusted')")
    monkeypatch.setattr(musetalk.subprocess, "run", lambda *a, **k: subprocess.CompletedProcess(a[0], 0, "scripts/inference.py\0", ""))
    work = tmp_path / "work"
    musetalk.stage_sources(repo, work)
    assert (work / "scripts" / "inference.py").exists()
    assert not (work / "scripts" / "untracked.py").exists()


def test_child_environment_is_offline_without_mutating_parent(monkeypatch):
    monkeypatch.delenv("HF_HUB_OFFLINE", raising=False)
    import os
    assert offline_environment()["HF_HUB_OFFLINE"] == "1"
    assert "HF_HUB_OFFLINE" not in os.environ


@pytest.mark.parametrize("variant", list(VARIANTS))
def test_chatterbox_local_api_preserves_watermarked_generate(monkeypatch, tmp_path, variant):
    import torch
    import soundfile as sf

    config = _chatterbox_config(tmp_path, variant)
    model = Mock()
    model.sr = 24000
    model.generate.return_value = torch.ones((1, 2400)) * 0.1
    loader = Mock(return_value=model)
    module_name, class_name, _ = VARIANTS[variant]
    monkeypatch.setitem(sys.modules, module_name, types.SimpleNamespace(**{class_name: types.SimpleNamespace(from_local=loader)}))
    result = generate_local(config, "A local test sentence.", 2, tmp_path / "output")
    assert len(result["takes"]) == 2
    model.prepare_conditionals.assert_called_once_with(config["reference_audio"])
    assert model.generate.call_count == 2
    assert loader.call_args.kwargs["device"] == "cpu"
    if variant in ("turbo", "nano"):
        assert loader.call_args.kwargs["nano"] is (variant == "nano")
    if variant == "multilingual_v3":
        assert loader.call_args.kwargs["t3_model"] == "v3"
        assert model.generate.call_args.kwargs == {"language_id": "en"}
    audio, sr = sf.read(tmp_path / "output" / result["takes"][0]["file"])
    assert sr == 24000 and len(audio) == 2400
    assert result["provenance"]["execution"] == "local"


def test_cache_identity_changes_with_presenter_or_weights(tmp_path):
    avatar = tmp_path / "avatar.png"
    avatar.write_bytes(b"one")
    manifest = {"files": {"model": "hash1"}}
    first = avatar_cache_key(avatar, manifest)
    avatar.write_bytes(b"two")
    assert first != avatar_cache_key(avatar, manifest)
    avatar.write_bytes(b"one")
    manifest["files"]["model"] = "hash2"
    assert first != avatar_cache_key(avatar, manifest)


def test_cancelled_and_previous_utterances_cannot_play():
    clock = iter([100, 300])
    session = MediaSession("session", clock=lambda: next(clock))
    first = session.begin("one")
    old = MediaStamp("session", "one", first, 44100, 44100)
    assert session.accepts(old)
    assert old.presentation_time_ns == 1_000_000_000
    session.cancel()
    assert not session.accepts(old)
    second = session.begin("two")
    assert not session.accepts(old)
    assert session.accepts(MediaStamp("session", "two", second, 0, 24000))
    assert not session.accepts(MediaStamp("another", "two", second, 0, 24000))
    assert session.elapsed_ns() == 200


def test_evaluation_reports_measured_values_and_undefined_correlation():
    assert compare_contours([1, 2, 3], [1, 2, 3]) == {"samples": 3, "rmse": 0, "pearson": 1}
    assert compare_contours([1, 1], [2, 2])["pearson"] is None
    assert timing_metrics(elapsed_seconds=2, audio_seconds=4)["real_time_factor"] == 0.5
    with pytest.raises(ValueError):
        compare_contours([1], [float("nan")])
    with pytest.raises(ValueError):
        compare_contours([1], [1, 2])
    with pytest.raises(ValueError):
        timing_metrics(elapsed_seconds=0, audio_seconds=0)


def test_provider_endpoint_does_not_load_models_or_expose_config_paths(monkeypatch, tmp_path):
    import ota_server
    from ota_server import create_ota_server

    monkeypatch.setattr(
        ota_server,
        "list_heygen_account_options",
        lambda: {"available": True, "error": None, "looks": [], "voice": None},
    )
    monkeypatch.setattr(ota_server, "_probe_lambda_voice_health", lambda *a, **k: None)

    config_path = tmp_path / "providers.json"
    config_path.write_text(json.dumps({"schema_version": 1, "tts_backend": "chatterbox", "chatterbox": {"model_dir": "/private/model/path"}}))
    server = create_ota_server("127.0.0.1", 0, tmp_path / "bundles", tmp_path / "prefs.jsonl", renders_dir=tmp_path / "renders", media_config_path=config_path, quiet=True)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{server.server_port}/v1/media/providers") as response:
            body = response.read().decode()
        parsed = json.loads(body)
        assert "/private/model/path" not in body
        assert parsed["tts_backend"] == "chatterbox_local"
        voices = {v["id"]: v for v in parsed["voice_backends"]}
        assert voices["chatterbox_local"]["available"] is True
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


def test_local_tts_uses_existing_strict_verifier_without_legacy_probe(monkeypatch, tmp_path):
    import ota_server
    import render_broadcast_studio as rbs
    from media_providers.chatterbox import ChatterboxSynthesizer

    config = tmp_path / "providers.json"
    config.write_text(json.dumps({"schema_version": 1, "tts_backend": "chatterbox", "chatterbox": {"variant": "turbo"}}))
    manager = ota_server.RenderJobManager(tmp_path / "renders", tmp_path / "bundles", media_config_path=config, python_bin=sys.executable)
    monkeypatch.setattr(ota_server, "_build_lambda_voice_synthesizer_fn", Mock(side_effect=AssertionError("must not probe legacy service")))
    monkeypatch.setattr(manager, "_get_warm_tts_engine", Mock(side_effect=AssertionError("must not load S2-Pro")))
    def render(**kwargs):
        assert isinstance(kwargs["synthesizer_fn"], ChatterboxSynthesizer)
        assert kwargs["require_strict_gates"] is True
        assert kwargs["require_real_alignment"] is True
        assert kwargs["warm_engine"] is None
        return {"metrics": {"all_gates_passed": True}}
    monkeypatch.setattr(rbs, "render_broadcast_studio", render)
    assert manager._run_broadcast_tts_stage(project_id="test", spoken_text="Test sentence.", caller_clauses=None, studio_dir=tmp_path / "studio", assets={})["metrics"]["all_gates_passed"]
    manager.shutdown()


def test_chatterbox_bridge_resamples_real_worker_wav_and_keeps_provenance(monkeypatch, tmp_path):
    import media_providers.chatterbox as chatterbox
    import soundfile as sf

    real_run = subprocess.run
    def run(command, **kwargs):
        if "media_providers.chatterbox" not in command:
            return real_run(command, **kwargs)
        request = json.loads(kwargs["input"])
        assert kwargs["env"]["HF_HUB_OFFLINE"] == "1"
        output = Path(request["output_dir"]) / "take_0.wav"
        sf.write(output, np.sin(np.arange(2400) * 0.1).astype(np.float32) * 0.1, 24000)
        return subprocess.CompletedProcess(command, 0, json.dumps({"takes": [{"file": "take_0.wav", "seed": 123}], "provenance": {"provider": "chatterbox", "device": "cpu"}}), "")
    monkeypatch.setattr(chatterbox.subprocess, "run", run)
    synth = chatterbox.ChatterboxSynthesizer({}, Path(sys.executable))
    takes = synth(types.SimpleNamespace(raw_text="Test sentence."), 1)
    assert len(takes) == 1 and takes[0].seed == 123
    assert len(takes[0].pcm44k) == 4410
    assert takes[0].discrete_audio_tokens == []
    assert synth.provenance == {"provider": "chatterbox", "device": "cpu"}


def test_musetalk_selection_never_falls_back_to_hosted_provider(monkeypatch, tmp_path):
    import generate_talking_head as gth
    import media_providers.musetalk as musetalk

    config = tmp_path / "providers.json"
    config.write_text(json.dumps({"schema_version": 1, "video_backend": "musetalk", "musetalk": {"reference_consent": True}}))
    voice, avatar = tmp_path / "voice.wav", tmp_path / "avatar.png"
    voice.write_bytes(b"test")
    avatar.write_bytes(b"test")
    monkeypatch.setattr(gth, "_prepare_canonical_master_wav", lambda *a, **k: (a[1], 2000))
    monkeypatch.setattr(gth, "_invoke_heygen_avatar", Mock(side_effect=AssertionError("no hosted fallback")))
    monkeypatch.setattr(musetalk, "render", Mock(side_effect=RuntimeError("CUDA unavailable")))
    with pytest.raises(RuntimeError, match="CUDA unavailable"):
        gth.generate_talking_head(voice, avatar, tmp_path / "output.mp4", media_config_path=config)


def test_musetalk_publishes_only_qa_checked_video_with_master_audio(monkeypatch, tmp_path):
    import generate_talking_head as gth
    import media_providers.musetalk as musetalk

    source, audio = tmp_path / "portrait.png", tmp_path / "voice.wav"
    source.write_bytes(b"portrait fixture")
    audio.write_bytes(b"master fixture")
    models = tmp_path / "models"
    models.mkdir()
    (tmp_path / "scripts").mkdir()
    (tmp_path / "musetalk" / "utils" / "face_detection" / "detection" / "sfd").mkdir(parents=True)
    manifest = {"files": {"model": "test"}, "model_revision": "test-model"}
    monkeypatch.setattr(musetalk, "validate_config", lambda cfg: (tmp_path, Path(sys.executable), models, manifest))
    def stage(repo, work):
        (work / "musetalk" / "utils" / "face_detection" / "detection" / "sfd").mkdir(parents=True)
    monkeypatch.setattr(musetalk, "stage_sources", stage)
    commands = []
    def run(command, **kwargs):
        commands.append(command)
        if "scripts.inference" in command:
            root = Path(kwargs["cwd"])
            raw = root / "results" / "v15" / "render.mp4"
            raw.parent.mkdir(parents=True)
            raw.write_bytes(b"rendered video")
            (root / f"{avatar_cache_key(source, manifest)}.pkl").write_bytes(b"coordinates")
        elif command[0] == "ffmpeg":
            Path(command[-1]).write_bytes(b"packaged media")
        return subprocess.CompletedProcess(command, 0, "", "")
    monkeypatch.setattr(musetalk.subprocess, "run", run)
    qa = {"av_sync_drift_ms": 0, "audio_duration_ms": 2000, "video_duration_ms": 2000}
    monkeypatch.setattr(gth, "verify_av_sync_ffprobe", lambda *a, **k: qa)
    output = tmp_path / "result.mp4"
    result = musetalk.render({"cache_dir": str(tmp_path / "cache")}, master_wav=audio, avatar=source, output=output, width=720, height=1280, duration_ms=2000)
    assert output.read_bytes() == b"packaged media"
    assert result["provenance"]["device"] == "cuda"
    assert any("1:a:0" in c for c in commands)
    assert list((tmp_path / "cache").glob("*.pkl"))
    with pytest.raises(FileExistsError):
        musetalk.render({"cache_dir": str(tmp_path / "cache")}, master_wav=audio, avatar=source, output=output, width=720, height=1280, duration_ms=2000)


def test_musetalk_failed_qa_does_not_publish(monkeypatch, tmp_path):
    import generate_talking_head as gth
    import media_providers.musetalk as musetalk

    source, audio = tmp_path / "portrait.png", tmp_path / "voice.wav"
    source.write_bytes(b"portrait fixture")
    audio.write_bytes(b"master fixture")
    models = tmp_path / "models"
    models.mkdir()
    (tmp_path / "scripts").mkdir()
    (tmp_path / "musetalk" / "utils" / "face_detection" / "detection" / "sfd").mkdir(parents=True)
    monkeypatch.setattr(musetalk, "validate_config", lambda cfg: (tmp_path, Path(sys.executable), models, {"files": {}, "model_revision": "test"}))
    def stage(repo, work):
        (work / "musetalk" / "utils" / "face_detection" / "detection" / "sfd").mkdir(parents=True)
    monkeypatch.setattr(musetalk, "stage_sources", stage)
    def run(command, **kwargs):
        if "scripts.inference" in command:
            raw = Path(kwargs["cwd"]) / "results" / "v15" / "render.mp4"
            raw.parent.mkdir(parents=True)
            raw.write_bytes(b"video")
        elif command[0] == "ffmpeg":
            Path(command[-1]).write_bytes(b"media")
        return subprocess.CompletedProcess(command, 0, "", "")
    monkeypatch.setattr(musetalk.subprocess, "run", run)
    monkeypatch.setattr(gth, "verify_av_sync_ffprobe", Mock(side_effect=RuntimeError("duration mismatch")))
    output = tmp_path / "result.mp4"
    with pytest.raises(RuntimeError, match="duration mismatch"):
        musetalk.render({"cache_dir": str(tmp_path / "cache")}, master_wav=audio, avatar=source, output=output, width=720, height=1280, duration_ms=2000)
    assert not output.exists()
