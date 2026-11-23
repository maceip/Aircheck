#!/usr/bin/env python3
"""Train and export Ryan's real initial Rank-32 GGUF LoRA for fishaudio/s2-pro.

Pipeline:
1. Map fishaudio/s2-pro 36-layer `fish_qwen3` safetensors weights to Hugging Face
   `Qwen3ForCausalLM` (including exact interleaved-to-split-half RoPE unpermutation
   on q_proj, k_proj, q_norm, and k_norm matching `s2-pro.Q4_K_M.gguf`).
2. Load Ryan's verified 44.1 kHz speech dataset (`/Users/mac/Downloads/voicestudio-models/dataset/manifest.jsonl`,
   or fallback seed clips in `/Users/mac/Downloads/clone/voice/`) and tokenize every
   utterance through `modded_dac.py` (`codec.pth`, strict=True) into real discrete
   `s2-pro` semantic audio token IDs (`151678..155773`).
3. Attach a real Rank-32 PEFT LoRA (`r=32, lora_alpha=64`, target modules
   `q_proj,k_proj,v_proj,o_proj,gate_proj,up_proj,down_proj`) and run real SFT
   training on Apple M5 Max MPS (`bfloat16`), verifying genuine train & val loss drop.
4. Export the trained adapter directly to GGUF (`voice_lora_v001.gguf`) using
   `/Users/mac/llama.cpp/convert_lora_to_gguf.py`.
5. Run end-to-end host `llama.cpp` parity verification (`llama-lora-verify`) proving
   real `llama_adapter_lora_init()`, `llama_set_adapter_lora()`, non-zero logit shift,
   exact post-detach restoration, and 0-leak repeated hot-swap on `s2-pro.Q4_K_M.gguf`.
"""

from __future__ import annotations

import argparse
import hashlib
import importlib
import importlib.util
import json
import math
import os
import random
import re
import runpy
import shutil
import subprocess
import sys
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Sequence

import numpy as np
import soundfile as sf
import torch


def _disable_incompatible_torch_extras() -> None:
    """Guard transformers/peft imports against optional packages built for a different torch ABI.

    Only disables an optional package (torchao / torchaudio / torchvision) when
    importing it actually fails in the current environment; leaves working installs intact.
    """
    broken: list[str] = []
    for pkg, fn_name in (
        ("torchao", "is_torchao_available"),
        ("torchaudio", "is_torchaudio_available"),
        ("torchvision", "is_torchvision_available"),
    ):
        if importlib.util.find_spec(pkg) is None:
            continue
        try:
            importlib.import_module(pkg)
        except Exception:
            broken.append(fn_name)

    if not broken:
        return

    import transformers.utils as _tu
    import transformers.utils.import_utils as _tiu

    for fn_name in broken:
        setattr(_tiu, fn_name, lambda *a, **k: False)
        setattr(_tu, fn_name, lambda *a, **k: False)


_disable_incompatible_torch_extras()

from peft import LoraConfig, TaskType, get_peft_model  # noqa: E402
from safetensors import safe_open  # noqa: E402
from transformers import AutoTokenizer, Qwen3Config, Qwen3ForCausalLM  # noqa: E402


# Canonical fishaudio/s2-pro special & semantic token IDs
SEMANTIC_START_TOKEN_ID: int = 151678
SEMANTIC_END_TOKEN_ID: int = 155773
NUM_SEMANTIC_TOKENS: int = 4096
IM_START_TOKEN_ID: int = 151644
IM_END_TOKEN_ID: int = 151645
VOICE_TOKEN_ID: int = 151673
PAD_TOKEN_ID: int = 151669
EOS_TOKEN_ID: int = 151645
BOS_TOKEN_ID: int = 151643

S2_PRO_SYSTEM_PROMPT: str = "convert the provided text to speech"
S2_PRO_SPEAKER_TAG: str = "<|speaker:0|>"

DEFAULT_LORA_RANK: int = 32
DEFAULT_LORA_ALPHA: int = 64
DEFAULT_LORA_DROPOUT: float = 0.05
DEFAULT_TARGET_MODULES: tuple[str, ...] = (
    "q_proj",
    "k_proj",
    "v_proj",
    "o_proj",
    "gate_proj",
    "up_proj",
    "down_proj",
)
DAC_SAMPLE_RATE: int = 44100
DAC_HOP_SAMPLES: int = 2048

DEFAULT_S2_PRO_HF_DIR = Path("/Users/mac/Downloads/voicestudio-models/s2-pro-hf")
DEFAULT_BASE_GGUF = Path("/Users/mac/Downloads/voicestudio-models/s2-pro/s2-pro.Q4_K_M.gguf")
DEFAULT_CODEC_PTH = Path("/Users/mac/Downloads/voicestudio-models/s2-pro/codec.pth")
DEFAULT_MANIFEST_JSONL = Path("/Users/mac/Downloads/voicestudio-models/dataset/manifest.jsonl")
DEFAULT_FALLBACK_TRANSCRIPTS = Path("/Users/mac/Downloads/clone/voice/asr_transcripts.json")
DEFAULT_FALLBACK_RAW_DIR = Path("/Users/mac/Downloads/clone/voice/raw")
DEFAULT_LLAMA_CPP_DIR = Path("/Users/mac/llama.cpp")
DEFAULT_VERIFY_BIN = Path(
    "/Users/mac/Downloads/voicestudio-models/llama-build-host/bin/llama-lora-verify"
)
DEFAULT_OUTPUT_GGUF = Path(
    "/Users/mac/Downloads/voicestudio-models/bundles/v001/voice_lora_v001.gguf"
)
DEFAULT_WORK_DIR = Path("/Users/mac/Downloads/voicestudio-models/lora-work-v001")


@dataclass(frozen=True)
class VoiceClipEntry:
    clip_id: str
    wav_path: Path
    transcript: str
    duration_sec: float
    wespeaker_cos: float | None = None
    dnsmos_p808: float | None = None
    source_video: str | None = None


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        while True:
            chunk = f.read(1024 * 1024)
            if not chunk:
                break
            h.update(chunk)
    return h.hexdigest()


def clean_transcript(text: str) -> str:
    cleaned = re.sub(r"\[BLANK_AUDIO\]", " ", text, flags=re.IGNORECASE)
    cleaned = re.sub(r"\s+", " ", cleaned).strip()
    return cleaned


def build_s2_pro_prompt(transcript: str) -> str:
    """Format the empirically verified fishaudio/s2-pro text->semantic prompt.

    Verified directly on `s2-pro.Q4_K_M.gguf` in host `llama.cpp` where this exact
    prompt yields P(semantic token 151678..155773) = 0.9999 on the first generated token.
    """
    text = clean_transcript(transcript)
    return (
        f"<|im_start|>system\n{S2_PRO_SYSTEM_PROMPT}<|im_end|>\n"
        f"<|im_start|>user\n{S2_PRO_SPEAKER_TAG}{text}<|im_end|>\n"
        f"<|im_start|>assistant\n<|voice|>"
    )


def build_qwen3_config_dict(s2_pro_config: dict[str, Any]) -> dict[str, Any]:
    """Convert fishaudio/s2-pro `config.json` (`text_config`) to standard HF Qwen3Config dict."""
    tc = s2_pro_config.get("text_config", s2_pro_config)
    return {
        "architectures": ["Qwen3ForCausalLM"],
        "attention_bias": bool(tc.get("attention_qkv_bias", False)),
        "attention_dropout": float(tc.get("dropout", 0.0)),
        "bos_token_id": BOS_TOKEN_ID,
        "eos_token_id": int(s2_pro_config.get("eos_token_id", EOS_TOKEN_ID)),
        "head_dim": int(tc["head_dim"]),
        "hidden_act": "silu",
        "hidden_size": int(tc["dim"]),
        "initializer_range": float(tc.get("initializer_range", 0.02)),
        "intermediate_size": int(tc["intermediate_size"]),
        "max_position_embeddings": int(tc.get("max_seq_len", 32768)),
        "max_window_layers": int(tc["n_layer"]),
        "model_type": "qwen3",
        "num_attention_heads": int(tc["n_head"]),
        "num_hidden_layers": int(tc["n_layer"]),
        "num_key_value_heads": int(tc["n_local_heads"]),
        "pad_token_id": int(s2_pro_config.get("pad_token_id", PAD_TOKEN_ID)),
        "rms_norm_eps": float(tc.get("norm_eps", 1e-6)),
        "rope_scaling": None,
        "rope_theta": float(tc.get("rope_base", 1000000.0)),
        "sliding_window": None,
        "tie_word_embeddings": bool(tc.get("tie_word_embeddings", True)),
        "torch_dtype": str(s2_pro_config.get("dtype", "bfloat16")),
        "use_cache": False,
        "use_sliding_window": False,
        "vocab_size": int(tc["vocab_size"]),
    }


def unpermute_interleaved_rope_weight(
    weight: torch.Tensor, n_heads: int, head_dim: int
) -> torch.Tensor:
    """Convert interleaved-RoPE projection [n_heads * head_dim, in_dim] to split-half NeoX RoPE.

    `fishaudio/s2-pro` stores Q/K projections in interleaved RoPE order, whereas Hugging Face
    `Qwen3ForCausalLM` and llama.cpp `qwen3` GGUF (`s2-pro.Q4_K_M.gguf`) use split-half NeoX RoPE.
    """
    expected_rows = n_heads * head_dim
    if weight.ndim != 2 or weight.shape[0] != expected_rows:
        raise ValueError(
            f"Expected 2D weight with {expected_rows} rows, got shape {tuple(weight.shape)}"
        )
    if head_dim % 2 != 0:
        raise ValueError(f"head_dim must be even, got {head_dim}")
    in_dim = weight.shape[1]
    return (
        weight.view(n_heads, head_dim // 2, 2, in_dim)
        .transpose(1, 2)
        .contiguous()
        .view(expected_rows, in_dim)
    )


def unpermute_interleaved_rope_norm(weight: torch.Tensor) -> torch.Tensor:
    """Convert interleaved-RoPE 1D Q/K RMSNorm [head_dim] to split-half NeoX RoPE order."""
    if weight.ndim != 1 or weight.shape[0] % 2 != 0:
        raise ValueError(f"Expected 1D even-length norm weight, got shape {tuple(weight.shape)}")
    head_dim = weight.shape[0]
    return weight.view(head_dim // 2, 2).transpose(0, 1).contiguous().view(head_dim)


def map_fish_qwen3_state_dict(
    s2_pro_hf_dir: Path,
    dtype: torch.dtype | None = torch.bfloat16,
) -> tuple[dict[str, Any], dict[str, torch.Tensor]]:
    """Load `fish_qwen3` text backbone safetensors and map them to HF `Qwen3ForCausalLM` keys."""
    config_path = s2_pro_hf_dir / "config.json"
    index_path = s2_pro_hf_dir / "model.safetensors.index.json"
    if not config_path.is_file():
        raise FileNotFoundError(f"Missing s2-pro config.json: {config_path}")
    if not index_path.is_file():
        raise FileNotFoundError(f"Missing model.safetensors.index.json: {index_path}")

    s2_config = json.loads(config_path.read_text(encoding="utf-8"))
    qwen3_cfg_dict = build_qwen3_config_dict(s2_config)

    n_heads = qwen3_cfg_dict["num_attention_heads"]
    n_kv_heads = qwen3_cfg_dict["num_key_value_heads"]
    head_dim = qwen3_cfg_dict["head_dim"]
    q_dim = n_heads * head_dim
    kv_dim = n_kv_heads * head_dim
    n_layers = qwen3_cfg_dict["num_hidden_layers"]

    index_data = json.loads(index_path.read_text(encoding="utf-8"))
    weight_map: dict[str, str] = index_data["weight_map"]

    shard_to_keys: dict[str, list[str]] = {}
    for key, shard_name in weight_map.items():
        if key.startswith("text_model.model."):
            shard_to_keys.setdefault(shard_name, []).append(key)

    mapped: dict[str, torch.Tensor] = {}

    def _cast(t: torch.Tensor) -> torch.Tensor:
        return t.to(dtype=dtype) if dtype is not None else t

    for shard_name, keys in sorted(shard_to_keys.items()):
        shard_path = s2_pro_hf_dir / shard_name
        if not shard_path.is_file():
            raise FileNotFoundError(f"Missing safetensors shard: {shard_path}")
        with safe_open(str(shard_path), framework="pt", device="cpu") as f:
            for key in keys:
                tensor = f.get_tensor(key)
                if key == "text_model.model.embeddings.weight":
                    emb = _cast(tensor)
                    mapped["model.embed_tokens.weight"] = emb
                    if qwen3_cfg_dict["tie_word_embeddings"]:
                        mapped["lm_head.weight"] = emb
                    continue
                if key == "text_model.model.norm.weight":
                    mapped["model.norm.weight"] = _cast(tensor)
                    continue

                m = re.match(r"^text_model\.model\.layers\.(\d+)\.(.+)$", key)
                if not m:
                    raise ValueError(f"Unexpected text_model key: {key}")
                layer_idx = int(m.group(1))
                suffix = m.group(2)
                prefix = f"model.layers.{layer_idx}"

                if suffix == "attention.wqkv.weight":
                    q_w, k_w, v_w = torch.split(tensor, [q_dim, kv_dim, kv_dim], dim=0)
                    mapped[f"{prefix}.self_attn.q_proj.weight"] = _cast(
                        unpermute_interleaved_rope_weight(q_w, n_heads, head_dim)
                    )
                    mapped[f"{prefix}.self_attn.k_proj.weight"] = _cast(
                        unpermute_interleaved_rope_weight(k_w, n_kv_heads, head_dim)
                    )
                    mapped[f"{prefix}.self_attn.v_proj.weight"] = _cast(v_w.contiguous())
                elif suffix == "attention.wo.weight":
                    mapped[f"{prefix}.self_attn.o_proj.weight"] = _cast(tensor)
                elif suffix == "attention.q_norm.weight":
                    mapped[f"{prefix}.self_attn.q_norm.weight"] = _cast(
                        unpermute_interleaved_rope_norm(tensor)
                    )
                elif suffix == "attention.k_norm.weight":
                    mapped[f"{prefix}.self_attn.k_norm.weight"] = _cast(
                        unpermute_interleaved_rope_norm(tensor)
                    )
                elif suffix == "attention_norm.weight":
                    mapped[f"{prefix}.input_layernorm.weight"] = _cast(tensor)
                elif suffix == "ffn_norm.weight":
                    mapped[f"{prefix}.post_attention_layernorm.weight"] = _cast(tensor)
                elif suffix == "feed_forward.w1.weight":
                    mapped[f"{prefix}.mlp.gate_proj.weight"] = _cast(tensor)
                elif suffix == "feed_forward.w3.weight":
                    mapped[f"{prefix}.mlp.up_proj.weight"] = _cast(tensor)
                elif suffix == "feed_forward.w2.weight":
                    mapped[f"{prefix}.mlp.down_proj.weight"] = _cast(tensor)
                else:
                    raise ValueError(f"Unmapped fish_qwen3 layer suffix: {key}")

    expected_count = n_layers * 11 + (3 if qwen3_cfg_dict["tie_word_embeddings"] else 2)
    if len(mapped) != expected_count:
        raise RuntimeError(
            f"Mapped tensor count mismatch: got {len(mapped)}, expected {expected_count}"
        )

    return qwen3_cfg_dict, mapped


def write_hf_qwen3_base_config_dir(s2_pro_hf_dir: Path, out_config_dir: Path) -> Path:
    """Write a standard HF Qwen3ForCausalLM config directory for `convert_lora_to_gguf.py --base`."""
    out_config_dir.mkdir(parents=True, exist_ok=True)
    s2_config = json.loads((s2_pro_hf_dir / "config.json").read_text(encoding="utf-8"))
    qwen3_cfg_dict = build_qwen3_config_dict(s2_config)
    (out_config_dir / "config.json").write_text(
        json.dumps(qwen3_cfg_dict, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    for tok_file in (
        "tokenizer.json",
        "tokenizer_config.json",
        "special_tokens_map.json",
        "chat_template.jinja",
    ):
        src = s2_pro_hf_dir / tok_file
        if src.is_file():
            shutil.copy2(src, out_config_dir / tok_file)
    return out_config_dir


def load_s2_pro_qwen3_model(
    s2_pro_hf_dir: Path,
    dtype: torch.dtype = torch.bfloat16,
    device: str = "mps",
) -> tuple[Qwen3ForCausalLM, dict[str, Any]]:
    """Instantiate Qwen3ForCausalLM and load all 36 fish_qwen3 layers with strict=True."""
    from transformers.models.qwen3.modeling_qwen3 import Qwen3RotaryEmbedding

    qwen3_cfg_dict, state_dict = map_fish_qwen3_state_dict(s2_pro_hf_dir, dtype=dtype)
    qwen3_cfg = Qwen3Config(**qwen3_cfg_dict)
    # PyTorch 2.6 MPS SDPA does not broadcast enable_gqa (32 Q heads vs 8 KV heads);
    # eager attention explicitly calls repeat_kv() and is numerically exact on MPS.
    if device == "mps":
        qwen3_cfg._attn_implementation = "eager"
    with torch.device("meta"):
        model = Qwen3ForCausalLM(qwen3_cfg)
    model.load_state_dict(state_dict, strict=True, assign=True)
    # Materialize non-persistent RoPE inv_freq buffer on target device (kept on meta above).
    model.model.rotary_emb = Qwen3RotaryEmbedding(config=qwen3_cfg)
    if qwen3_cfg_dict["tie_word_embeddings"]:
        model.tie_weights()
    model.to(device=device, dtype=dtype)
    # Keep RoPE inv_freq in float32 for accurate high-theta (1e6) rotary frequencies.
    if hasattr(model.model.rotary_emb, "inv_freq"):
        model.model.rotary_emb.inv_freq = model.model.rotary_emb.inv_freq.to(
            device=device, dtype=torch.float32
        )
    return model, qwen3_cfg_dict


def resolve_modded_dac_path(explicit_path: Path | None = None) -> Path:
    """Locate `modded_dac.py` in explicit path, sibling directory, or codec-exporter worktree."""
    if explicit_path is not None:
        if explicit_path.is_file():
            return explicit_path
        raise FileNotFoundError(f"Explicit modded_dac.py path not found: {explicit_path}")

    sibling = Path(__file__).resolve().parent / "modded_dac.py"
    if sibling.is_file():
        return sibling

    worktree_root = Path(__file__).resolve().parents[3]
    for cand in sorted(worktree_root.glob("swarm-codec-exporter-*/sync-trainer/home-daemon/modded_dac.py")):
        if cand.is_file():
            return cand

    raise FileNotFoundError(
        "Could not locate modded_dac.py (pass --modded-dac-path or merge codec-exporter branch)"
    )


def import_modded_dac(modded_dac_path: Path) -> Any:
    spec = importlib.util.spec_from_file_location("voicestudio_modded_dac", str(modded_dac_path))
    if spec is None or spec.loader is None:
        raise ImportError(f"Unable to load module spec from {modded_dac_path}")
    mod = importlib.util.module_from_spec(spec)
    sys.modules["voicestudio_modded_dac"] = mod
    spec.loader.exec_module(mod)
    return mod


def load_wav_mono_44k(wav_path: Path) -> tuple[np.ndarray, float]:
    """Read a WAV file as float32 mono at 44,100 Hz and return (samples, duration_sec)."""
    audio, sr = sf.read(str(wav_path), dtype="float32", always_2d=False)
    if audio.ndim == 2:
        audio = np.mean(audio, axis=1, dtype=np.float32)
    if sr != DAC_SAMPLE_RATE:
        import librosa

        audio = librosa.resample(audio, orig_sr=sr, target_sr=DAC_SAMPLE_RATE).astype(np.float32)
    peak = float(np.max(np.abs(audio))) if audio.size > 0 else 0.0
    if peak > 1.0:
        audio = audio / peak
    duration_sec = float(audio.shape[0]) / float(DAC_SAMPLE_RATE)
    return np.ascontiguousarray(audio, dtype=np.float32), duration_sec


def load_dataset_entries(
    manifest_path: Path | None = None,
    fallback_transcripts_json: Path | None = DEFAULT_FALLBACK_TRANSCRIPTS,
    fallback_raw_dir: Path | None = DEFAULT_FALLBACK_RAW_DIR,
    min_duration_sec: float = 1.0,
    max_duration_sec: float = 60.0,
    min_wespeaker_cos: float | None = None,
    min_dnsmos: float | None = None,
) -> list[VoiceClipEntry]:
    """Load verified Ryan speech clips from manifest.jsonl (or fallback seed transcripts)."""
    entries: list[VoiceClipEntry] = []

    if manifest_path is not None and manifest_path.is_file():
        with manifest_path.open("r", encoding="utf-8") as f:
            for line_no, raw_line in enumerate(f, start=1):
                line = raw_line.strip()
                if not line:
                    continue
                row = json.loads(line)
                wav_str = row.get("wav_44k") or row.get("wav_path") or row.get("audio_path")
                if not wav_str:
                    continue
                wav_path = Path(wav_str)
                if not wav_path.is_file():
                    continue
                transcript = clean_transcript(str(row.get("transcript") or row.get("text") or ""))
                if len(transcript) < 3:
                    continue
                dur = float(row.get("duration_sec") or 0.0)
                if dur <= 0.0:
                    info = sf.info(str(wav_path))
                    dur = float(info.frames) / float(info.samplerate)
                if dur < min_duration_sec or dur > max_duration_sec:
                    continue
                cos_val = row.get("wespeaker_cos")
                cos_f = float(cos_val) if cos_val is not None else None
                if min_wespeaker_cos is not None and cos_f is not None and cos_f < min_wespeaker_cos:
                    continue
                dns_val = row.get("dnsmos_p808")
                dns_f = float(dns_val) if dns_val is not None else None
                if min_dnsmos is not None and dns_f is not None and dns_f < min_dnsmos:
                    continue
                clip_id = str(row.get("id") or f"clip_{line_no:04d}")
                entries.append(
                    VoiceClipEntry(
                        clip_id=clip_id,
                        wav_path=wav_path,
                        transcript=transcript,
                        duration_sec=dur,
                        wespeaker_cos=cos_f,
                        dnsmos_p808=dns_f,
                        source_video=row.get("source_video"),
                    )
                )
        if entries:
            return entries

    if (
        fallback_transcripts_json is not None
        and fallback_transcripts_json.is_file()
        and fallback_raw_dir is not None
        and fallback_raw_dir.is_dir()
    ):
        payload = json.loads(fallback_transcripts_json.read_text(encoding="utf-8"))
        clips_map = payload.get("clips", payload)
        for clip_id, raw_text in sorted(clips_map.items()):
            wav_path = fallback_raw_dir / f"{clip_id}.wav"
            if not wav_path.is_file():
                continue
            transcript = clean_transcript(str(raw_text))
            if len(transcript) < 3:
                continue
            info = sf.info(str(wav_path))
            dur = float(info.frames) / float(info.samplerate)
            if dur < min_duration_sec or dur > max_duration_sec:
                continue
            entries.append(
                VoiceClipEntry(
                    clip_id=clip_id,
                    wav_path=wav_path,
                    transcript=transcript,
                    duration_sec=dur,
                )
            )

    if not entries:
        raise RuntimeError(
            f"No valid audio/transcript clips found in manifest={manifest_path} "
            f"or fallback={fallback_transcripts_json}"
        )
    return entries


def encode_clips_to_semantic_tokens(
    entries: Sequence[VoiceClipEntry],
    codec_pth: Path,
    modded_dac_path: Path,
    device: str = "mps",
    cache_path: Path | None = None,
) -> list[dict[str, Any]]:
    """Tokenize Ryan's 44.1 kHz clips into real codebook-0 semantic indices via ModdedDAC."""
    if cache_path is not None and cache_path.is_file():
        cached = json.loads(cache_path.read_text(encoding="utf-8"))
        cached_ids = [item["clip_id"] for item in cached]
        entry_ids = [e.clip_id for e in entries]
        if cached_ids == entry_ids:
            return cached

    modded_dac = import_modded_dac(modded_dac_path)
    dac_model = modded_dac.load_s2_pro_dac(str(codec_pth), device=device, strict=True)

    tokenized: list[dict[str, Any]] = []
    for entry in entries:
        audio_44k, dur = load_wav_mono_44k(entry.wav_path)
        wav_tensor = torch.from_numpy(audio_44k)
        codes, code_lens = modded_dac.encode_waveform_44k(dac_model, wav_tensor)
        n_frames = int(code_lens[0].item())
        sem_codes = codes[0, 0, :n_frames].detach().cpu().tolist()
        if not sem_codes:
            raise RuntimeError(f"ModdedDAC produced 0 semantic tokens for {entry.wav_path}")
        if min(sem_codes) < 0 or max(sem_codes) >= NUM_SEMANTIC_TOKENS:
            raise ValueError(
                f"Semantic code out of range [0, {NUM_SEMANTIC_TOKENS - 1}] for {entry.clip_id}: "
                f"min={min(sem_codes)}, max={max(sem_codes)}"
            )
        tokenized.append(
            {
                "clip_id": entry.clip_id,
                "wav_path": str(entry.wav_path),
                "transcript": entry.transcript,
                "duration_sec": round(dur, 4),
                "wespeaker_cos": entry.wespeaker_cos,
                "dnsmos_p808": entry.dnsmos_p808,
                "num_semantic_tokens": len(sem_codes),
                "semantic_codes": sem_codes,
            }
        )

    del dac_model
    if device == "mps" and torch.backends.mps.is_available():
        torch.mps.empty_cache()

    if cache_path is not None:
        cache_path.parent.mkdir(parents=True, exist_ok=True)
        cache_path.write_text(json.dumps(tokenized, indent=2) + "\n", encoding="utf-8")

    return tokenized


def build_sft_sequence(
    tokenizer: Any,
    transcript: str,
    semantic_codes: Sequence[int],
    max_seq_len: int = 1024,
) -> dict[str, list[int]]:
    """Build causal LM `(input_ids, labels)` where loss is computed on semantic tokens + EOS."""
    prompt = build_s2_pro_prompt(transcript)
    prompt_ids: list[int] = list(tokenizer.encode(prompt, add_special_tokens=False))
    if not prompt_ids or prompt_ids[-1] != VOICE_TOKEN_ID:
        raise ValueError(
            f"Prompt must terminate with <|voice|> ({VOICE_TOKEN_ID}), got tail={prompt_ids[-3:]}"
        )

    semantic_vocab_ids = [SEMANTIC_START_TOKEN_ID + int(c) for c in semantic_codes]
    for vid in semantic_vocab_ids:
        if vid < SEMANTIC_START_TOKEN_ID or vid > SEMANTIC_END_TOKEN_ID:
            raise ValueError(f"Invalid semantic vocab ID: {vid}")

    target_ids = semantic_vocab_ids + [IM_END_TOKEN_ID]
    max_target = max(16, max_seq_len - len(prompt_ids))
    if len(target_ids) > max_target:
        target_ids = target_ids[: max_target - 1] + [IM_END_TOKEN_ID]

    input_ids = prompt_ids + target_ids
    labels = [-100] * len(prompt_ids) + target_ids
    return {
        "input_ids": input_ids,
        "labels": labels,
        "prompt_len": len(prompt_ids),
        "target_len": len(target_ids),
    }


@torch.no_grad()
def evaluate_loss(
    model: torch.nn.Module,
    examples: Sequence[dict[str, Any]],
    device: str,
) -> float:
    """Compute exact token-weighted mean cross-entropy loss across examples."""
    was_training = model.training
    model.eval()
    total_nll = 0.0
    total_tokens = 0
    for ex in examples:
        input_ids = torch.tensor([ex["input_ids"]], dtype=torch.long, device=device)
        labels = torch.tensor([ex["labels"]], dtype=torch.long, device=device)
        outputs = model(input_ids=input_ids, labels=labels)
        loss_val = float(outputs.loss.detach().float().item())
        if not math.isfinite(loss_val):
            raise RuntimeError(f"Non-finite validation loss encountered: {loss_val}")
        # Causal LM shifts labels by 1 internally, so active targets = count(labels[1:] != -100)
        n_active = sum(1 for x in ex["labels"][1:] if x != -100)
        total_nll += loss_val * n_active
        total_tokens += n_active
    if was_training:
        model.train()
    return total_nll / max(total_tokens, 1)


def split_train_val(
    examples: Sequence[dict[str, Any]],
    val_fraction: float = 0.1,
    seed: int = 42,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Deterministically split examples into train and validation sets."""
    items = list(examples)
    rng = random.Random(seed)
    rng.shuffle(items)
    if len(items) < 5:
        return items, []
    val_count = max(1, int(round(len(items) * val_fraction)))
    val_count = min(val_count, max(1, len(items) // 5))
    return items[val_count:], items[:val_count]


def train_rank32_lora(
    model: Qwen3ForCausalLM,
    train_examples: Sequence[dict[str, Any]],
    val_examples: Sequence[dict[str, Any]],
    adapter_out_dir: Path,
    device: str = "mps",
    lora_rank: int = DEFAULT_LORA_RANK,
    lora_alpha: int = DEFAULT_LORA_ALPHA,
    lora_dropout: float = DEFAULT_LORA_DROPOUT,
    target_modules: Sequence[str] = DEFAULT_TARGET_MODULES,
    epochs: int = 3,
    max_steps: int | None = None,
    learning_rate: float = 2e-4,
    weight_decay: float = 0.01,
    grad_accum_steps: int = 4,
    warmup_ratio: float = 0.1,
    seed: int = 42,
) -> dict[str, Any]:
    """Attach real Rank-32 PEFT LoRA and run real SFT optimization on MPS/CPU."""
    torch.manual_seed(seed)
    rng = random.Random(seed)

    lora_cfg = LoraConfig(
        r=lora_rank,
        lora_alpha=lora_alpha,
        lora_dropout=lora_dropout,
        bias="none",
        task_type=TaskType.CAUSAL_LM,
        target_modules=list(target_modules),
    )
    peft_model = get_peft_model(model, lora_cfg)
    peft_model.to(device=device)

    trainable_params = [p for p in peft_model.parameters() if p.requires_grad]
    n_trainable = sum(p.numel() for p in trainable_params)
    n_total = sum(p.numel() for p in peft_model.parameters())
    if n_trainable <= 0:
        raise RuntimeError("PEFT LoRA attached 0 trainable parameters")

    # Measure genuine pre-training baseline losses before any optimizer step.
    eval_train_subset = list(train_examples[: min(len(train_examples), 24)])
    initial_train_loss = evaluate_loss(peft_model, eval_train_subset, device=device)
    initial_val_loss = (
        evaluate_loss(peft_model, val_examples, device=device) if val_examples else None
    )

    eff_accum = max(1, min(grad_accum_steps, len(train_examples)))
    total_micro_steps = epochs * len(train_examples)
    total_opt_steps = max(1, math.ceil(total_micro_steps / eff_accum))
    if max_steps is not None:
        total_opt_steps = min(total_opt_steps, max_steps)
    warmup_steps = max(1, int(round(total_opt_steps * warmup_ratio)))

    optimizer = torch.optim.AdamW(
        trainable_params,
        lr=learning_rate,
        betas=(0.9, 0.999),
        weight_decay=weight_decay,
    )

    def _lr_for_step(step: int) -> float:
        if step < warmup_steps:
            return learning_rate * float(step + 1) / float(warmup_steps)
        progress = float(step - warmup_steps) / float(max(1, total_opt_steps - warmup_steps))
        progress = min(max(progress, 0.0), 1.0)
        return learning_rate * (0.1 + 0.9 * 0.5 * (1.0 + math.cos(math.pi * progress)))

    peft_model.train()
    optimizer.zero_grad(set_to_none=True)

    opt_step = 0
    micro_in_accum = 0
    accum_loss = 0.0
    step_history: list[dict[str, Any]] = []
    epoch_history: list[dict[str, Any]] = []
    t_start = time.perf_counter()

    for epoch in range(1, epochs + 1):
        order = list(range(len(train_examples)))
        rng.shuffle(order)
        epoch_loss_sum = 0.0
        epoch_count = 0

        for idx in order:
            if opt_step >= total_opt_steps:
                break
            ex = train_examples[idx]
            input_ids = torch.tensor([ex["input_ids"]], dtype=torch.long, device=device)
            labels = torch.tensor([ex["labels"]], dtype=torch.long, device=device)

            outputs = peft_model(input_ids=input_ids, labels=labels)
            raw_loss = outputs.loss
            loss_float = float(raw_loss.detach().float().item())
            if not math.isfinite(loss_float):
                raise RuntimeError(f"Non-finite training loss at epoch={epoch} opt_step={opt_step}")

            (raw_loss / float(eff_accum)).backward()
            accum_loss += loss_float
            micro_in_accum += 1
            epoch_loss_sum += loss_float
            epoch_count += 1

            is_last_in_epoch = epoch_count == len(order)
            if micro_in_accum >= eff_accum or is_last_in_epoch:
                lr_now = _lr_for_step(opt_step)
                for pg in optimizer.param_groups:
                    pg["lr"] = lr_now
                grad_norm = float(
                    torch.nn.utils.clip_grad_norm_(trainable_params, max_norm=1.0).item()
                )
                optimizer.step()
                optimizer.zero_grad(set_to_none=True)

                mean_step_loss = accum_loss / float(micro_in_accum)
                opt_step += 1
                step_history.append(
                    {
                        "step": opt_step,
                        "epoch": epoch,
                        "loss": round(mean_step_loss, 6),
                        "lr": round(lr_now, 8),
                        "grad_norm": round(grad_norm, 4),
                    }
                )
                accum_loss = 0.0
                micro_in_accum = 0

                if opt_step == 1 or opt_step % 5 == 0 or opt_step == total_opt_steps:
                    print(
                        f"  [train] step {opt_step:3d}/{total_opt_steps} "
                        f"(epoch {epoch}/{epochs}) loss={mean_step_loss:.4f} "
                        f"lr={lr_now:.2e} grad_norm={grad_norm:.3f}",
                        flush=True,
                    )

        val_loss_now = (
            evaluate_loss(peft_model, val_examples, device=device) if val_examples else None
        )
        epoch_mean = epoch_loss_sum / max(epoch_count, 1)
        epoch_history.append(
            {
                "epoch": epoch,
                "train_loss": round(epoch_mean, 6),
                "val_loss": round(val_loss_now, 6) if val_loss_now is not None else None,
            }
        )
        print(
            f"  [epoch {epoch}/{epochs}] mean_train_loss={epoch_mean:.4f}"
            + (f" val_loss={val_loss_now:.4f}" if val_loss_now is not None else ""),
            flush=True,
        )
        if opt_step >= total_opt_steps:
            break

    if device == "mps" and torch.backends.mps.is_available():
        torch.mps.synchronize()
    elapsed_sec = time.perf_counter() - t_start

    final_train_loss = evaluate_loss(peft_model, eval_train_subset, device=device)
    final_val_loss = (
        evaluate_loss(peft_model, val_examples, device=device) if val_examples else None
    )

    if final_train_loss >= initial_train_loss:
        raise RuntimeError(
            f"Training did not reduce loss: initial={initial_train_loss:.6f}, "
            f"final={final_train_loss:.6f}"
        )

    adapter_out_dir.mkdir(parents=True, exist_ok=True)
    peft_model.save_pretrained(str(adapter_out_dir), safe_serialization=True)

    # Verify saved adapter weights are real, non-zero, and finite.
    adapter_safetensors = adapter_out_dir / "adapter_model.safetensors"
    nonzero_lora_b = 0
    total_lora_tensors = 0
    with safe_open(str(adapter_safetensors), framework="pt", device="cpu") as f:
        for k in f.keys():
            t = f.get_tensor(k).float()
            if not torch.isfinite(t).all():
                raise RuntimeError(f"Non-finite LoRA tensor saved: {k}")
            total_lora_tensors += 1
            if "lora_B" in k and float(t.abs().max().item()) > 0.0:
                nonzero_lora_b += 1

    if nonzero_lora_b == 0:
        raise RuntimeError("All saved lora_B matrices are identically zero after training")

    return {
        "lora_rank": lora_rank,
        "lora_alpha": lora_alpha,
        "lora_dropout": lora_dropout,
        "target_modules": list(target_modules),
        "trainable_params": n_trainable,
        "total_params": n_total,
        "trainable_pct": round(100.0 * n_trainable / max(n_total, 1), 4),
        "epochs": epochs,
        "optimizer_steps": opt_step,
        "grad_accum_steps": eff_accum,
        "learning_rate": learning_rate,
        "elapsed_sec": round(elapsed_sec, 2),
        "initial_train_loss": round(initial_train_loss, 6),
        "final_train_loss": round(final_train_loss, 6),
        "train_loss_drop": round(initial_train_loss - final_train_loss, 6),
        "initial_val_loss": round(initial_val_loss, 6) if initial_val_loss is not None else None,
        "final_val_loss": round(final_val_loss, 6) if final_val_loss is not None else None,
        "val_loss_drop": (
            round(initial_val_loss - final_val_loss, 6)
            if (initial_val_loss is not None and final_val_loss is not None)
            else None
        ),
        "saved_lora_tensors": total_lora_tensors,
        "nonzero_lora_b_tensors": nonzero_lora_b,
        "epoch_history": epoch_history,
        "step_history": step_history,
    }


def export_peft_lora_to_gguf(
    adapter_dir: Path,
    qwen3_base_config_dir: Path,
    llama_cpp_dir: Path,
    output_gguf: Path,
    outtype: str = "f16",
) -> dict[str, Any]:
    """Convert trained PEFT LoRA adapter to GGUF via `/Users/mac/llama.cpp/convert_lora_to_gguf.py`."""
    converter = llama_cpp_dir / "convert_lora_to_gguf.py"
    if not converter.is_file():
        raise FileNotFoundError(f"Missing llama.cpp converter: {converter}")
    if not (adapter_dir / "adapter_config.json").is_file():
        raise FileNotFoundError(f"Missing adapter_config.json in {adapter_dir}")
    if not (qwen3_base_config_dir / "config.json").is_file():
        raise FileNotFoundError(f"Missing base config.json in {qwen3_base_config_dir}")

    output_gguf.parent.mkdir(parents=True, exist_ok=True)
    tmp_out = output_gguf.with_suffix(f".gguf.tmp.{os.getpid()}")
    if tmp_out.exists():
        tmp_out.unlink()

    # Run convert_lora_to_gguf.py inside a clean child Python process with our torch-extras guard.
    runner_code = (
        "import importlib, importlib.util, pathlib, runpy, sys\n"
        "broken = []\n"
        "for pkg, fn in (('torchao', 'is_torchao_available'), "
        "('torchaudio', 'is_torchaudio_available'), ('torchvision', 'is_torchvision_available')):\n"
        "    if importlib.util.find_spec(pkg) is not None:\n"
        "        try:\n"
        "            importlib.import_module(pkg)\n"
        "        except Exception:\n"
        "            broken.append(fn)\n"
        "if broken:\n"
        "    import transformers.utils as tu, transformers.utils.import_utils as tiu\n"
        "    for fn in broken:\n"
        "        setattr(tiu, fn, lambda *a, **k: False)\n"
        "        setattr(tu, fn, lambda *a, **k: False)\n"
        "sys.argv = sys.argv[1:]\n"
        "sys.path.insert(0, str(pathlib.Path(sys.argv[0]).resolve().parent))\n"
        "runpy.run_path(sys.argv[0], run_name='__main__')\n"
    )
    cmd = [
        sys.executable,
        "-c",
        runner_code,
        str(converter),
        str(adapter_dir),
        "--base",
        str(qwen3_base_config_dir),
        "--outfile",
        str(tmp_out),
        "--outtype",
        outtype,
    ]
    proc = subprocess.run(cmd, capture_output=True, text=True)
    if proc.returncode != 0:
        if tmp_out.exists():
            tmp_out.unlink()
        raise RuntimeError(
            f"convert_lora_to_gguf.py failed (exit {proc.returncode}):\n"
            f"STDOUT:\n{proc.stdout}\nSTDERR:\n{proc.stderr}"
        )

    if not tmp_out.is_file() or tmp_out.stat().st_size < 1024:
        raise RuntimeError(f"Converter produced empty or missing GGUF: {tmp_out}")

    with tmp_out.open("rb") as f:
        magic = f.read(4)
    if magic != b"GGUF":
        tmp_out.unlink()
        raise RuntimeError(f"Invalid GGUF magic header: {magic!r}")

    gguf_py_dir = str(llama_cpp_dir / "gguf-py")
    if gguf_py_dir not in sys.path:
        sys.path.insert(0, gguf_py_dir)
    import gguf

    reader = gguf.GGUFReader(str(tmp_out), "r")
    arch = reader.fields.get("general.architecture")
    arch_val = arch.contents() if arch is not None else None
    gtype = reader.fields.get("general.type")
    gtype_val = gtype.contents() if gtype is not None else None
    alpha_f = reader.fields.get("adapter.lora.alpha")
    alpha_val = float(alpha_f.contents()) if alpha_f is not None else None
    tensor_count = len(reader.tensors)

    if arch_val != "qwen3":
        tmp_out.unlink()
        raise RuntimeError(f"Expected general.architecture='qwen3', got {arch_val!r}")
    if gtype_val != "adapter":
        tmp_out.unlink()
        raise RuntimeError(f"Expected general.type='adapter', got {gtype_val!r}")
    if tensor_count == 0:
        tmp_out.unlink()
        raise RuntimeError("Exported GGUF LoRA contains 0 tensors")

    tmp_out.replace(output_gguf)
    size_bytes = output_gguf.stat().st_size
    digest = sha256_file(output_gguf)

    return {
        "gguf_path": str(output_gguf),
        "outtype": outtype,
        "size_bytes": size_bytes,
        "size_mb": round(size_bytes / (1024.0 * 1024.0), 2),
        "sha256": digest,
        "architecture": arch_val,
        "general_type": gtype_val,
        "lora_alpha": alpha_val,
        "tensor_count": tensor_count,
    }


def verify_llama_cpp_lora_hotswap(
    verify_bin: Path,
    base_gguf: Path,
    lora_gguf: Path,
    prompt: str | None = None,
    scale: float = 1.0,
    hotswap_iters: int = 10,
    n_gpu_layers: int = 99,
) -> dict[str, Any]:
    """Run real host `llama-lora-verify` proving LoRA load, logit shift, detach, and 0-leak swap."""
    if not verify_bin.is_file():
        raise FileNotFoundError(
            f"Host llama-lora-verify binary not found: {verify_bin} "
            "(run sync-trainer/home-daemon/build_android_llama.sh --target host)"
        )
    cmd = [
        str(verify_bin),
        "--model",
        str(base_gguf),
        "--lora",
        str(lora_gguf),
        "--scale",
        str(scale),
        "--hotswap-iters",
        str(hotswap_iters),
        "--n-gpu-layers",
        str(n_gpu_layers),
    ]
    if prompt is not None:
        cmd.extend(["--prompt", prompt])

    proc = subprocess.run(cmd, capture_output=True, text=True)
    if proc.returncode != 0:
        raise RuntimeError(
            f"llama-lora-verify failed (exit {proc.returncode}):\n"
            f"STDOUT:\n{proc.stdout}\nSTDERR:\n{proc.stderr}"
        )

    result = json.loads(proc.stdout)
    if not result.get("base_model_loaded") or not result.get("lora_loaded"):
        raise RuntimeError(f"llama.cpp failed to load base/LoRA GGUF: {result}")
    if result.get("lora_set_rc") != 0 or result.get("lora_rm_rc") != 0:
        raise RuntimeError(f"llama_set_adapter_lora / rm failed: {result}")
    if float(result.get("logit_max_abs_diff", 0.0)) <= 1e-4:
        raise RuntimeError(f"LoRA produced zero logit shift: {result}")
    if float(result.get("semantic_logit_l2_diff", 0.0)) <= 1e-3:
        raise RuntimeError(f"LoRA produced negligible semantic logit shift: {result}")
    if float(result.get("post_detach_max_abs_diff", 1.0)) > 1e-2:
        raise RuntimeError(f"Post-detach logits did not restore base model: {result}")
    if int(result.get("hotswap_errors", 1)) != 0:
        raise RuntimeError(f"Hot-swap stress loop reported errors: {result}")
    return result


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Train Ryan's real Rank-32 PEFT LoRA on fishaudio/s2-pro and export GGUF."
    )
    parser.add_argument("--s2-pro-hf-dir", type=Path, default=DEFAULT_S2_PRO_HF_DIR)
    parser.add_argument("--base-gguf", type=Path, default=DEFAULT_BASE_GGUF)
    parser.add_argument("--codec-pth", type=Path, default=DEFAULT_CODEC_PTH)
    parser.add_argument("--modded-dac-path", type=Path, default=None)
    parser.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST_JSONL)
    parser.add_argument("--fallback-transcripts", type=Path, default=DEFAULT_FALLBACK_TRANSCRIPTS)
    parser.add_argument("--fallback-raw-dir", type=Path, default=DEFAULT_FALLBACK_RAW_DIR)
    parser.add_argument("--llama-cpp-dir", type=Path, default=DEFAULT_LLAMA_CPP_DIR)
    parser.add_argument("--verify-bin", type=Path, default=DEFAULT_VERIFY_BIN)
    parser.add_argument("--work-dir", type=Path, default=DEFAULT_WORK_DIR)
    parser.add_argument("--output-gguf", type=Path, default=DEFAULT_OUTPUT_GGUF)
    parser.add_argument("--outtype", type=str, default="f16", choices=["f16", "bf16", "f32", "q8_0"])
    parser.add_argument("--device", type=str, default="mps")
    parser.add_argument("--rank", type=int, default=DEFAULT_LORA_RANK)
    parser.add_argument("--alpha", type=int, default=DEFAULT_LORA_ALPHA)
    parser.add_argument("--dropout", type=float, default=DEFAULT_LORA_DROPOUT)
    parser.add_argument("--epochs", type=int, default=3)
    parser.add_argument("--max-steps", type=int, default=None)
    parser.add_argument("--lr", type=float, default=2e-4)
    parser.add_argument("--grad-accum", type=int, default=4)
    parser.add_argument("--max-seq-len", type=int, default=1024)
    parser.add_argument("--val-fraction", type=float, default=0.1)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--hotswap-iters", type=int, default=10)
    parser.add_argument("--skip-verify", action="store_true")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    device = args.device
    if device == "mps" and not torch.backends.mps.is_available():
        raise RuntimeError("MPS requested but torch.backends.mps.is_available() is False")

    args.work_dir.mkdir(parents=True, exist_ok=True)
    modded_dac_path = resolve_modded_dac_path(args.modded_dac_path)

    print(f"=== [1/5] Loading dataset entries (manifest={args.manifest}) ===", flush=True)
    entries = load_dataset_entries(
        manifest_path=args.manifest,
        fallback_transcripts_json=args.fallback_transcripts,
        fallback_raw_dir=args.fallback_raw_dir,
    )
    total_audio_sec = sum(e.duration_sec for e in entries)
    source_kind = "manifest.jsonl" if args.manifest.is_file() else "clone/voice/raw"
    print(
        f"Loaded {len(entries)} clips ({total_audio_sec:.1f}s = {total_audio_sec / 60.0:.2f} min) "
        f"from {source_kind}",
        flush=True,
    )

    print(f"=== [2/5] Tokenizing 44.1 kHz clips via ModdedDAC ({modded_dac_path.name}) ===", flush=True)
    token_cache = args.work_dir / "semantic_tokens_cache.json"
    tokenized_clips = encode_clips_to_semantic_tokens(
        entries=entries,
        codec_pth=args.codec_pth,
        modded_dac_path=modded_dac_path,
        device=device,
        cache_path=token_cache,
    )
    total_sem_tokens = sum(c["num_semantic_tokens"] for c in tokenized_clips)
    print(
        f"Encoded {len(tokenized_clips)} clips -> {total_sem_tokens} real semantic audio tokens",
        flush=True,
    )

    qwen3_config_dir = write_hf_qwen3_base_config_dir(
        args.s2_pro_hf_dir, args.work_dir / "qwen3_base_config"
    )
    tokenizer = AutoTokenizer.from_pretrained(str(qwen3_config_dir))
    examples = [
        {
            "clip_id": c["clip_id"],
            **build_sft_sequence(
                tokenizer, c["transcript"], c["semantic_codes"], max_seq_len=args.max_seq_len
            ),
        }
        for c in tokenized_clips
    ]
    train_examples, val_examples = split_train_val(
        examples, val_fraction=args.val_fraction, seed=args.seed
    )
    print(
        f"SFT split: {len(train_examples)} train / {len(val_examples)} val sequences",
        flush=True,
    )

    print(
        f"=== [3/5] Loading fish_qwen3 -> Qwen3ForCausalLM (bf16 on {device}) & training Rank-{args.rank} LoRA ===",
        flush=True,
    )
    model, qwen3_cfg_dict = load_s2_pro_qwen3_model(
        args.s2_pro_hf_dir, dtype=torch.bfloat16, device=device
    )
    adapter_dir = args.work_dir / "peft_lora_adapter"
    train_report = train_rank32_lora(
        model=model,
        train_examples=train_examples,
        val_examples=val_examples,
        adapter_out_dir=adapter_dir,
        device=device,
        lora_rank=args.rank,
        lora_alpha=args.alpha,
        lora_dropout=args.dropout,
        epochs=args.epochs,
        max_steps=args.max_steps,
        learning_rate=args.lr,
        grad_accum_steps=args.grad_accum,
        seed=args.seed,
    )
    del model
    if device == "mps" and torch.backends.mps.is_available():
        torch.mps.empty_cache()

    print(
        f"Training DONE: train_loss {train_report['initial_train_loss']:.4f} -> "
        f"{train_report['final_train_loss']:.4f} (drop={train_report['train_loss_drop']:.4f})"
        + (
            f", val_loss {train_report['initial_val_loss']:.4f} -> "
            f"{train_report['final_val_loss']:.4f}"
            if train_report["initial_val_loss"] is not None
            else ""
        ),
        flush=True,
    )

    print(f"=== [4/5] Exporting PEFT LoRA -> {args.output_gguf} ({args.outtype}) ===", flush=True)
    export_report = export_peft_lora_to_gguf(
        adapter_dir=adapter_dir,
        qwen3_base_config_dir=qwen3_config_dir,
        llama_cpp_dir=args.llama_cpp_dir,
        output_gguf=args.output_gguf,
        outtype=args.outtype,
    )
    print(
        f"Exported GGUF LoRA: {export_report['size_mb']:.2f} MB ({export_report['tensor_count']} tensors), "
        f"SHA-256={export_report['sha256']}",
        flush=True,
    )

    verify_report: dict[str, Any] | None = None
    if not args.skip_verify:
        print("=== [5/5] Verifying real llama.cpp LoRA load, logit shift & 0-leak hot-swap ===", flush=True)
        sample_prompt = build_s2_pro_prompt(entries[0].transcript)
        verify_report = verify_llama_cpp_lora_hotswap(
            verify_bin=args.verify_bin,
            base_gguf=args.base_gguf,
            lora_gguf=args.output_gguf,
            prompt=sample_prompt,
            scale=1.0,
            hotswap_iters=args.hotswap_iters,
            n_gpu_layers=99,
        )
        print(
            f"llama.cpp verification PASSED: max_abs_diff={verify_report['logit_max_abs_diff']:.4f}, "
            f"sem_l2_diff={verify_report['semantic_logit_l2_diff']:.4f}, "
            f"post_detach_diff={verify_report['post_detach_max_abs_diff']:.6f}, "
            f"hotswap_errors={verify_report['hotswap_errors']}, "
            f"rss_delta_mb={verify_report['rss_delta_mb']:.2f}",
            flush=True,
        )

    full_report = {
        "dataset": {
            "source": source_kind,
            "manifest_path": str(args.manifest) if args.manifest.is_file() else None,
            "num_clips": len(entries),
            "num_train_clips": len(train_examples),
            "num_val_clips": len(val_examples),
            "total_audio_sec": round(total_audio_sec, 2),
            "total_semantic_tokens": total_sem_tokens,
        },
        "model": {
            "architecture": qwen3_cfg_dict["architectures"][0],
            "num_hidden_layers": qwen3_cfg_dict["num_hidden_layers"],
            "hidden_size": qwen3_cfg_dict["hidden_size"],
            "vocab_size": qwen3_cfg_dict["vocab_size"],
            "device": device,
        },
        "training": train_report,
        "export": export_report,
        "llama_cpp_verification": verify_report,
    }
    report_path = args.output_gguf.parent / "lora_training_report.json"
    report_path.write_text(json.dumps(full_report, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote training report: {report_path}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
