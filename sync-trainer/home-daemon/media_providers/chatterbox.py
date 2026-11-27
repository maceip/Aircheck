from __future__ import annotations

import contextlib
import importlib
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any

from media_providers.registry import existing_absolute_file, file_sha256, verify_artifacts


VARIANTS = {
    "original": ("chatterbox.tts", "ChatterboxTTS", {"ve.safetensors", "t3_cfg.safetensors", "s3gen.safetensors", "tokenizer.json"}),
    "turbo": ("chatterbox.tts_turbo", "ChatterboxTurboTTS", {"ve.safetensors", "t3_turbo_v1.safetensors", "s3gen_meanflow.safetensors", "tokenizer_config.json", "vocab.json", "merges.txt"}),
    "nano": ("chatterbox.tts_turbo", "ChatterboxTurboTTS", {"ve.safetensors", "t3_nano_v1.safetensors", "s3gen_meanflow.safetensors", "tokenizer_config.json", "vocab.json", "merges.txt"}),
    "multilingual_v3": ("chatterbox.mtl_tts", "ChatterboxMultilingualTTS", {"ve.pt", "t3_mtl23ls_v3.safetensors", "s3gen.pt", "grapheme_mtl_merged_expanded_v1.json"}),
}


def offline_environment() -> dict[str, str]:
    env = dict(os.environ)
    env.update(HF_HUB_OFFLINE="1", TRANSFORMERS_OFFLINE="1", HF_DATASETS_OFFLINE="1", HF_HUB_DISABLE_TELEMETRY="1", PYTHONDONTWRITEBYTECODE="1")
    return env


def validate_config(config: dict[str, Any]) -> tuple[Path, Path, dict[str, Any]]:
    variant = config.get("variant", "turbo")
    if variant not in VARIANTS:
        raise ValueError("Unsupported Chatterbox variant")
    if config.get("device", "auto") not in ("auto", "mps", "cpu"):
        raise ValueError("Local Chatterbox device must be auto, mps or cpu")
    if config.get("reference_consent") is not True:
        raise ValueError("Chatterbox requires explicit reference voice consent")
    reference = existing_absolute_file(config.get("reference_audio"), "reference_audio")
    manifest_path = existing_absolute_file(config.get("artifact_manifest"), "artifact_manifest")
    value = config.get("model_dir")
    if not isinstance(value, str) or not Path(value).is_absolute():
        raise ValueError("model_dir must be an absolute local directory")
    root = Path(value).resolve(strict=True)
    if not root.is_dir():
        raise ValueError("model_dir must be a directory")
    required = set(VARIANTS[variant][2])
    # Optional conditioning and tokenizer files can also be deserialized by upstream.
    required.update(p.relative_to(root).as_posix() for p in root.rglob("*") if p.is_file() and p.suffix in {".pt", ".safetensors", ".json", ".txt", ".model"} and p.resolve() != manifest_path)
    manifest = verify_artifacts(root, manifest_path, provider="chatterbox", required=required)
    if manifest.get("variant") != variant:
        raise ValueError("Artifact manifest variant does not match Chatterbox configuration")
    return root, reference, manifest


def select_device(torch: Any, requested: str) -> str:
    available = torch.backends.mps.is_available()
    if requested == "mps" and not available:
        raise RuntimeError("MPS explicitly requested but unavailable; no silent CPU fallback")
    return ("mps" if available else "cpu") if requested == "auto" else requested


def generate_local(config: dict[str, Any], text: str, count: int, output_dir: Path) -> dict[str, Any]:
    if not isinstance(text, str) or not text.strip() or len(text) > 10000:
        raise ValueError("Chatterbox text must contain 1..10000 characters")
    if type(count) is not int or not 1 <= count <= 8:
        raise ValueError("Chatterbox take count must be 1..8")
    root, reference, manifest = validate_config(config)
    try:
        import numpy as np
        import soundfile as sf
        import torch
    except ImportError as exc:
        raise RuntimeError("Chatterbox runtime is not installed in the configured Python; automatic installation is disabled") from exc
    variant = config.get("variant", "turbo")
    module_name, class_name, _ = VARIANTS[variant]
    try:
        model_class = getattr(importlib.import_module(module_name), class_name)
    except (ImportError, AttributeError) as exc:
        raise RuntimeError(f"Installed Chatterbox does not support {variant}; use the reviewed upstream revision") from exc
    device = select_device(torch, config.get("device", "auto"))
    kwargs: dict[str, Any] = {"device": device}
    if variant in ("turbo", "nano"):
        kwargs["nano"] = variant == "nano"
    elif variant == "multilingual_v3":
        kwargs["t3_model"] = "v3"
    model = model_class.from_local(root, **kwargs)
    model.prepare_conditionals(str(reference))
    output_dir.mkdir(parents=True, exist_ok=True)
    outputs = []
    for index in range(count):
        seed = int.from_bytes(os.urandom(4), "little")
        torch.manual_seed(seed)
        generation = {"language_id": config.get("language_id", "en")} if variant == "multilingual_v3" else {}
        with torch.inference_mode():
            waveform = model.generate(text, **generation).detach().float().cpu().numpy()
        pcm = np.asarray(waveform, dtype=np.float32).reshape(-1)
        if pcm.size == 0 or not np.isfinite(pcm).all():
            raise RuntimeError("Chatterbox returned empty or non-finite audio")
        path = output_dir / f"take_{index}.wav"
        if path.exists():
            raise FileExistsError(path)
        sf.write(str(path), pcm, int(model.sr), subtype="FLOAT")
        outputs.append({"file": path.name, "seed": seed})
    return {
        "takes": outputs,
        "provenance": {
            "provider": "chatterbox", "variant": variant, "device": device,
            "model_revision": manifest["model_revision"], "source_revision": manifest["source_revision"],
            "reference_sha256": file_sha256(reference), "watermark": "upstream PerTh retained; not independently detected",
            "execution": "local", "streaming": False,
        },
    }


class ChatterboxSynthesizer:
    def __init__(self, config: dict[str, Any], python_bin: Path) -> None:
        self.config = dict(config)
        self.python_bin = existing_absolute_file(config.get("python_bin", str(python_bin)), "python_bin", preserve_symlink=True)
        self.provenance: dict[str, Any] | None = None

    def __call__(self, clause: Any, count: int) -> list[Any]:
        import numpy as np
        from render_broadcast_studio import SynthesizedTakeData

        timeout = self.config.get("timeout_seconds", 600)
        if type(timeout) is not int or not 1 <= timeout <= 3600:
            raise ValueError("Chatterbox timeout_seconds must be 1..3600")
        with tempfile.TemporaryDirectory(prefix="aircheck_chatterbox_") as directory:
            root = Path(directory)
            request = {"config": self.config, "text": clause.raw_text, "count": count, "output_dir": str(root)}
            proc = subprocess.run(
                [str(self.python_bin), "-m", "media_providers.chatterbox"],
                input=json.dumps(request), text=True, capture_output=True,
                cwd=str(Path(__file__).resolve().parents[1]), env=offline_environment(),
                timeout=timeout, check=False,
            )
            if proc.returncode:
                raise RuntimeError(f"Local Chatterbox failed: {proc.stderr[-1500:]}")
            response = json.loads(proc.stdout)
            if len(response.get("takes", [])) != count:
                raise RuntimeError("Chatterbox did not produce the requested take count")
            takes = []
            for item in response["takes"]:
                path = (root / item["file"]).resolve(strict=True)
                if not path.is_relative_to(root.resolve()):
                    raise ValueError("Chatterbox output escaped its work directory")
                decoded = subprocess.run(
                    ["ffmpeg", "-v", "error", "-i", str(path), "-ac", "1", "-ar", "44100", "-f", "f32le", "pipe:1"],
                    capture_output=True, timeout=60, check=True,
                )
                pcm = np.frombuffer(decoded.stdout, dtype=np.float32).copy()
                if not pcm.size or not np.isfinite(pcm).all():
                    raise RuntimeError("Chatterbox produced invalid decoded audio")
                takes.append(SynthesizedTakeData(pcm44k=pcm, discrete_audio_tokens=[], seed=item["seed"], temperature=0.8))
            self.provenance = response["provenance"]
            return takes


def main() -> int:
    try:
        request = json.load(sys.stdin)
        from media_providers.offline_runner import deny_network

        sys.addaudithook(deny_network)
        with contextlib.redirect_stdout(sys.stderr):
            result = generate_local(request["config"], request["text"], request["count"], Path(request["output_dir"]))
        print(json.dumps(result))
        return 0
    except Exception as exc:
        print(f"{type(exc).__name__}: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
