#!/usr/bin/env python3
"""Closed-Loop On-Policy Self-Distillation (OPSD) + GRPO/DPO LoRA Training (v001 -> v002).

Executes the complete real Aircheck / VoiceStudio preference-optimization loop on Apple Silicon:
1. Phase 1 — On-Policy Rollout Collection & Live `preferences.jsonl` Upload:
   - Synthesizes N=4 on-policy rollouts per prompt across 7 diverse broadcast-style prompts
     (= 28 real rollouts) using `s2_pro_infer.py` + `lora-work-v001/peft_lora_adapter/`
     conditioned on Ryan's cleanest high-WeSpeaker / high-DNSMOS reference turns.
   - Decodes all rollouts via real FP16 LiteRT `codec_decoder_fp16.tflite` and scores every take
     with the real 4-Gate Verifier (`whisper-cli` CER, `WeSpeaker ResNet34` vs `master_centroid.bin`,
     `DNSMOS p808`, cadence `4.2..6.1 syl/s`, 80 ms tail RMS `<= -45 dBFS`) using
     `tinker_voice_daemon.SingleSpeakerAudioEnv.compute_reward`.
   - Builds strict `preference_tuple.schema.json`-conformant records (`chosen_tokens`,
     `rejected_tokens`, `gate_failures`) and POSTs them to the live OTA server
     (`http://127.0.0.1:8443/v1/preferences`), verifying HTTP 200 and on-disk `preferences.jsonl`
     growth.
2. Phase 2 — Real GRPO/DPO + High-Purity OPSD Update (`v001 -> v002`) on MPS BF16:
   - Initializes from `lora-work-v001/peft_lora_adapter/` on base `fishaudio/s2-pro`
     (`Qwen3ForCausalLM`, exact RoPE unpermutation).
   - High-Purity OPSD / SFT anchor: strictly filters `manifest.jsonl` to clips with real
     `master_centroid.bin` WeSpeaker cosine >= 0.78 and DNSMOS p808 >= 3.50, plus Ryan's seed
     anchors (`voice_ref.wav`, `voice_ref_alt.wav`), eliminating noisy lower-DNSMOS clips.
   - Online DPO + GRPO preference optimization (`beta = kl_coef = 0.04`) with exact frozen base
     `s2-pro` reference log-probs (`peft_model.disable_adapter()`), hard-negative penalty on
     `gate_failures` (`cer > 0.0`), and real OPSD top-K distillation from the reference-conditioned
     teacher onto the raw-text student.
   - Saves `/Users/mac/Downloads/voicestudio-models/lora-work-v002/peft_lora_adapter/` and exports
     `/Users/mac/Downloads/voicestudio-models/bundles/v002/voice_lora_v002.gguf` via
     `/Users/mac/llama.cpp/convert_lora_to_gguf.py`.
3. Phase 3 — Rigorous Held-Out A/B Verification & Gate 5 Live Atomic Hot-Swap:
   - Evaluates `voice_lora_v001` vs `voice_lora_v002` on the exact same 3 held-out prompts
     (including the canonical bare-metal Pixel prompt) with identical seeds, reference turns,
     and LiteRT decoder.
   - Measures exact CER, WeSpeaker cosine, DNSMOS p808, cadence, tail RMS, SPEC reward, and
     chosen-token log-probability delta (`logp_v2 - logp_v1`).
   - Runs real `llama-lora-verify` on both adapters AND a live single-context native
     `libllama.dylib` atomic hot-swap test (`v001 -> v002 -> v001`) on `s2-pro.Q4_K_M.gguf`
     confirming 0 errors, 0 leak, exact restoration, and distinct logit deltas.
   - Writes `/Users/mac/Downloads/voicestudio-models/bundles/v002/grpo_v2_report.json` without
     touching `bundles/v001/` or prematurely writing `bundles/v002/bundle_manifest.json`.
"""

from __future__ import annotations

import argparse
import datetime
import json
import math
import os
import random
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Mapping, Sequence

import numpy as np
import soundfile as sf
import torch
import torch.nn.functional as F

_THIS_DIR = Path(__file__).resolve().parent
if str(_THIS_DIR) not in sys.path:
    sys.path.insert(0, str(_THIS_DIR))

# Guard transformers/peft imports before importing train_initial_lora / peft
import train_initial_lora as til  # noqa: E402

from peft import PeftModel  # noqa: E402
from safetensors import safe_open  # noqa: E402
from transformers import AutoTokenizer  # noqa: E402

import enroll_voice_assets as eva  # noqa: E402
import s2_pro_infer  # noqa: E402
import synthesize_and_render as sar  # noqa: E402
import tinker_voice_daemon as tvd  # noqa: E402


# ---------------------------------------------------------------------------
# Paths, Constants, and Prompt Suites
# ---------------------------------------------------------------------------

DEFAULT_MODELS_ROOT = Path("/Users/mac/Downloads/voicestudio-models")
DEFAULT_S2_PRO_HF_DIR = DEFAULT_MODELS_ROOT / "s2-pro-hf"
DEFAULT_BASE_GGUF = DEFAULT_MODELS_ROOT / "s2-pro" / "s2-pro.Q4_K_M.gguf"
DEFAULT_CODEC_PTH = DEFAULT_MODELS_ROOT / "s2-pro" / "codec.pth"
DEFAULT_MANIFEST_JSONL = DEFAULT_MODELS_ROOT / "dataset" / "manifest.jsonl"
DEFAULT_PREFERENCES_JSONL = DEFAULT_MODELS_ROOT / "preferences.jsonl"
DEFAULT_OTA_SERVER_URL = "http://127.0.0.1:8443"

DEFAULT_V001_BUNDLE_DIR = DEFAULT_MODELS_ROOT / "bundles" / "v001"
DEFAULT_V002_BUNDLE_DIR = DEFAULT_MODELS_ROOT / "bundles" / "v002"
DEFAULT_V001_LORA_DIR = DEFAULT_MODELS_ROOT / "lora-work-v001" / "peft_lora_adapter"
DEFAULT_V001_GGUF = DEFAULT_V001_BUNDLE_DIR / "voice_lora_v001.gguf"
DEFAULT_V002_WORK_DIR = DEFAULT_MODELS_ROOT / "lora-work-v002"
DEFAULT_V002_LORA_DIR = DEFAULT_V002_WORK_DIR / "peft_lora_adapter"
DEFAULT_V002_GGUF = DEFAULT_V002_BUNDLE_DIR / "voice_lora_v002.gguf"
DEFAULT_V002_REPORT = DEFAULT_V002_BUNDLE_DIR / "grpo_v2_report.json"

DEFAULT_CODEC_TFLITE = DEFAULT_V001_BUNDLE_DIR / "codec_decoder_fp16.tflite"
DEFAULT_MASTER_CENTROID = DEFAULT_V001_BUNDLE_DIR / "master_centroid.bin"
DEFAULT_LEXICON_JSON = DEFAULT_V001_BUNDLE_DIR / "lexicon.json"
DEFAULT_WESPEAKER_ONNX = eva.DEFAULT_WESPEAKER_ONNX
DEFAULT_DNSMOS_ONNX = eva.DEFAULT_DNSMOS_P808_ONNX
DEFAULT_WHISPER_BIN = sar.DEFAULT_WHISPER_BIN
DEFAULT_WHISPER_MODEL = sar.DEFAULT_WHISPER_MODEL

DEFAULT_LLAMA_CPP_DIR = Path("/Users/mac/llama.cpp")
DEFAULT_HOST_LLAMA_BIN_DIR = DEFAULT_MODELS_ROOT / "llama-build-host" / "bin"
DEFAULT_VERIFY_BIN = DEFAULT_HOST_LLAMA_BIN_DIR / "llama-lora-verify"
DEFAULT_VOICE_REF_WAV = Path("/Users/mac/Downloads/clone/voice/voice_ref.wav")
DEFAULT_VOICE_REF_ALT_WAV = Path("/Users/mac/Downloads/clone/voice/voice_ref_alt.wav")
DEFAULT_VOICE_REF_TRANSCRIPT = (
    "This message is for Matthew, Swish, and Ranya. "
    "Ranya, I've been singing J. Cole all day, thank you. "
    "And Matt, hope you're feeling better, bro. I'm leaving your mail right there. "
    "Ronnie and Matt, I'm actually, I'm deadass about that IoT hackathon tomorrow. "
    "You guys don't have to go, but I'm gonna go. I gotta show up and get a job, so let me know."
)
DEFAULT_VOICE_REF_ALT_TRANSCRIPT = sar.DEFAULT_REF_TEXT

# Cleanest high-WeSpeaker & high-DNSMOS verified Ryan reference turns from manifest.jsonl:
# 1. ryan_20260908_190501_b54b7715_004: master_cos=0.9189 (#1), DNSMOS=3.789, dur=12.14s
# 2. ryan_WIN_20261104_19_11_19_Pro_004: master_cos=0.8736, DNSMOS=3.963, cadence=5.00 syl/s, dur=11.00s
DEFAULT_CLEAN_REFERENCE_TURNS: tuple[tuple[Path, str], ...] = (
    (
        DEFAULT_MODELS_ROOT / "dataset" / "wavs" / "ryan_20260908_190501_b54b7715_004.wav",
        "the model for the specific hardware, like this Dimensity chip in a Huawei 15T "
        "is obviously different than the Pixel Titan chips, which we don't know anything about anyways.",
    ),
    (
        DEFAULT_MODELS_ROOT / "dataset" / "wavs" / "ryan_WIN_20261104_19_11_19_Pro_004.wav",
        "Well, let me say music's pretty good, but it struggles with a couple things that we wanted "
        "to solve today and I'm going to show you. So what I'm going to do right now since we don't "
        "have our Muse Connector live in production, knock, knock.",
    ),
)

# Phase 1: 7 diverse broadcast-style rollout prompts (strictly disjoint from Phase 3 held-out prompts)
DEFAULT_ROLLOUT_PROMPTS: tuple[tuple[str, str], ...] = (
    (
        "rollout_01",
        "This chip runs the entire speech stack locally with zero cloud latency and full hardware privacy.",
    ),
    (
        "rollout_02",
        "Every single take passes four strict audio gates before it ever reaches the broadcast timeline.",
    ),
    (
        "rollout_03",
        "Hot-swapping a fresh nightly voice adapter takes less than a second right inside the Pixel runtime.",
    ),
    (
        "rollout_04",
        "Clean studio pacing keeps every technical walkthrough sharp, natural, and effortless to follow.",
    ),
    (
        "rollout_05",
        "Instead of streaming your voice to remote servers, the model stays locked down on your own device.",
    ),
    (
        "rollout_06",
        "We calibrated the acoustic profile directly from real sessions so the timbre stays consistent.",
    ),
    (
        "rollout_07",
        "Watch how fast the local decoder turns discrete semantic tokens into crisp studio audio.",
    ),
)

# Phase 3: 3 strictly held-out A/B prompts (NEVER seen in Phase 1 rollouts or Phase 2 training)
DEFAULT_HELDOUT_PROMPTS: tuple[tuple[str, str], ...] = (
    (
        "heldout_01",
        "We ripped out the cloud wrapper and ran the entire voice studio bare-metal right on the Pixel.",
    ),
    (
        "heldout_02",
        "Real on-device speech synthesis should sound unmistakably human without sending a single byte upstream.",
    ),
    (
        "heldout_03",
        "Our nightly preference loop sharpens pronunciation, speaker lock, and natural broadcast cadence automatically.",
    ),
)

DEFAULT_ROLLOUT_SEEDS: tuple[int, ...] = (951001, 951002, 951003, 951004)
DEFAULT_HELDOUT_SEEDS: tuple[int, ...] = (948101, 948102, 948103)

DEFAULT_KL_COEF: float = tvd.GRPO_KL_COEF  # 0.04
DEFAULT_PURITY_MIN_WESPEAKER: float = 0.78
DEFAULT_PURITY_MIN_DNSMOS: float = 3.50


# ---------------------------------------------------------------------------
# Pure Algorithmic Helpers (Fully Unit-Testable Without Large Weights)
# ---------------------------------------------------------------------------


def normalize_semantic_vocab_tokens(tokens: Sequence[int]) -> list[int]:
    """Normalize discrete semantic token IDs into s2-pro vocabulary space [151678..155773].

    Accepts either raw codebook-0 indices `[0..4095]` or already-shifted vocab IDs
    `[151678..155773]`, rejecting out-of-range integers cleanly.
    """
    out: list[int] = []
    for raw in tokens:
        val = int(raw)
        if 0 <= val < til.NUM_SEMANTIC_TOKENS:
            out.append(til.SEMANTIC_START_TOKEN_ID + val)
        elif til.SEMANTIC_START_TOKEN_ID <= val <= til.SEMANTIC_END_TOKEN_ID:
            out.append(val)
        else:
            raise ValueError(
                f"Semantic token {val} out of valid codebook-0 [0..{til.NUM_SEMANTIC_TOKENS - 1}] "
                f"or vocab [{til.SEMANTIC_START_TOKEN_ID}..{til.SEMANTIC_END_TOKEN_ID}] range"
            )
    return out


def score_rollout_reward(
    gates: Mapping[str, Any],
    *,
    human_chosen: bool = False,
) -> float:
    """Compute exact SPEC reward using `tinker_voice_daemon.SingleSpeakerAudioEnv.compute_reward`."""
    metrics = tvd.AudioGateMetrics(
        cer=float(gates["cer"]),
        speaker_cosine_sim=float(gates["wespeaker_cosine"]),
        dnsmos_p808=float(gates["dnsmos_p808"]),
        tail_rms_db=float(gates.get("tail_rms_dbfs", -58.0)),
        syllables_per_sec=float(gates.get("syllables_per_sec", 5.0)),
    )
    return float(tvd.SingleSpeakerAudioEnv.compute_reward(metrics, human_chosen=human_chosen))


def compute_rollout_selection_score(gates: Mapping[str, Any]) -> tuple[int, float, float, float]:
    """Rank takes prioritizing CER==0, SPEC reward, in-band cadence, and clean tail RMS."""
    cer = float(gates["cer"])
    spec_reward = score_rollout_reward(gates, human_chosen=False)
    sps = float(gates.get("syllables_per_sec", 5.0))
    tail_db = float(gates.get("tail_rms_dbfs", -58.0))
    in_cadence = (
        sar.GATE_MIN_SYLLABLES_PER_SEC <= sps <= sar.GATE_MAX_SYLLABLES_PER_SEC
    )
    cadence_dist = 0.0 if in_cadence else min(
        abs(sps - sar.GATE_MIN_SYLLABLES_PER_SEC),
        abs(sps - sar.GATE_MAX_SYLLABLES_PER_SEC),
    )
    tail_ok = 1.0 if tail_db <= sar.GATE_MAX_TAIL_RMS_DBFS else 0.0
    # Slight tie-breaker bonus for clean cadence/tail while preserving exact SPEC reward dominance
    effective = spec_reward + (0.04 if in_cadence else -0.02 * cadence_dist) + 0.01 * tail_ok
    return (1 if cer == 0.0 else 0, effective, spec_reward, -cer)


def build_normalized_phoneme_hint(raw_text: str, lexicon: Mapping[str, str]) -> str:
    """Build deterministic IPA phoneme hint string from matched `lexicon.json` entries."""
    matched = tvd._match_lexicon_overrides(raw_text, lexicon)
    if not matched:
        return ""
    return " | ".join(f"{word}:{ipa}" for word, ipa in matched.items())


def build_preference_record_from_rollouts(
    *,
    clause_id: str,
    raw_text: str,
    rollouts: Sequence[Mapping[str, Any]],
    lora_version: int = 1,
    lexicon: Mapping[str, str] | None = None,
    timestamp: int | None = None,
) -> dict[str, Any]:
    """Construct and strictly validate a `preference_tuple.schema.json` record from N rollouts.

    Each rollout dict must contain `seed`, `semantic_tokens` (vocab IDs 151678..155773),
    and `gates` (from `evaluate_candidate_4gates`).
    """
    if len(rollouts) < 2:
        raise ValueError(f"Need at least 2 rollouts to construct preference pair, got {len(rollouts)}")

    ranked = sorted(
        rollouts,
        key=lambda r: compute_rollout_selection_score(r["gates"]),
        reverse=True,
    )
    chosen = ranked[0]
    chosen_tokens = normalize_semantic_vocab_tokens(chosen["semantic_tokens"])
    if not chosen_tokens:
        raise ValueError(f"Chosen rollout for {clause_id} has empty semantic_tokens")

    # Select lowest-scoring rollout with distinct token sequence as rejected_tokens
    rejected = ranked[-1]
    for cand in reversed(ranked[1:]):
        cand_toks = normalize_semantic_vocab_tokens(cand["semantic_tokens"])
        if cand_toks and cand_toks != chosen_tokens:
            rejected = cand
            break
    rejected_tokens = normalize_semantic_vocab_tokens(rejected["semantic_tokens"])

    # Record every non-chosen rollout that failed CER > 0 (or any strict gate) in gate_failures
    gate_failures: list[dict[str, Any]] = []
    for cand in ranked[1:]:
        g = cand["gates"]
        cer_val = float(g["cer"])
        strict_fails = list(g.get("strict_gate_failures", []))
        if cer_val > 0.0:
            reason = "CER_EXCEEDED"
        elif strict_fails:
            reason = str(strict_fails[0])
        else:
            continue
        cand_toks = normalize_semantic_vocab_tokens(cand["semantic_tokens"])
        if not cand_toks:
            continue
        gate_failures.append(
            {
                "seed": int(cand["seed"]),
                "cer": round(cer_val, 5),
                "reason": reason,
                "tokens": cand_toks,
            }
        )

    # Compute realistic gap scale & user_action matching VoiceStudio cadence semantics
    chosen_sps = float(chosen["gates"].get("syllables_per_sec", 5.0))
    if chosen_sps < sar.GATE_MIN_SYLLABLES_PER_SEC:
        gap_scale = max(0.25, min(1.0, round(chosen_sps / 4.8, 3)))
        user_action = "TAKE_SWAP_AND_PACE_EDIT"
    else:
        gap_scale = 1.0
        user_action = "TAKE_SWAP"

    lex_map = lexicon or {}
    norm_phonemes = build_normalized_phoneme_hint(raw_text, lex_map)
    ts = int(timestamp if timestamp is not None else time.time())

    record_dict: dict[str, Any] = {
        "timestamp": ts,
        "clause_id": str(clause_id),
        "lora_version": int(lora_version),
        "raw_text": str(raw_text),
        "normalized_phonemes": norm_phonemes,
        "user_action": user_action,
        "user_gap_scale": float(gap_scale),
        "chosen_tokens": chosen_tokens,
        "rejected_tokens": rejected_tokens,
        "gate_failures": gate_failures,
    }
    # Strictly validate against frozen preference_tuple.schema.json contract
    tvd.validate_preference_record(record_dict)
    return record_dict


def upload_preferences_to_ota_server(
    records: Sequence[Mapping[str, Any]],
    *,
    ota_server_url: str = DEFAULT_OTA_SERVER_URL,
    preferences_path: Path | None = None,
    timeout_sec: float = 15.0,
) -> dict[str, Any]:
    """POST validated NDJSON preference records to live OTA server and verify disk growth."""
    if not records:
        raise ValueError("Cannot upload empty preference records list")

    validated = [tvd.validate_preference_record(r).to_dict() for r in records]
    ndjson_text = "".join(
        json.dumps(rec, separators=(",", ":"), ensure_ascii=False) + "\n"
        for rec in validated
    )
    body = ndjson_text.encode("utf-8")

    before_lines = 0
    if preferences_path is not None and preferences_path.is_file():
        before_lines = sum(
            1 for ln in preferences_path.read_text(encoding="utf-8").splitlines() if ln.strip()
        )

    url = f"{ota_server_url.rstrip('/')}/v1/preferences"
    req = urllib.request.Request(
        url,
        data=body,
        method="POST",
        headers={
            "Content-Type": "application/x-ndjson; charset=utf-8",
            "Content-Length": str(len(body)),
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout_sec) as resp:
            status_code = int(resp.status)
            resp_body = resp.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        err_body = exc.read().decode("utf-8", errors="replace")
        raise RuntimeError(
            f"OTA server POST {url} failed with HTTP {exc.code}: {err_body}"
        ) from exc

    if status_code != 200:
        raise RuntimeError(f"Expected HTTP 200 from {url}, got {status_code}: {resp_body}")

    parsed = json.loads(resp_body)
    if parsed.get("status") != "accepted" or int(parsed.get("accepted_count", -1)) != len(validated):
        raise RuntimeError(f"Unexpected OTA response payload: {parsed}")

    after_lines = before_lines
    if preferences_path is not None:
        if not preferences_path.is_file():
            raise RuntimeError(f"Expected preferences file missing after POST: {preferences_path}")
        after_lines = sum(
            1 for ln in preferences_path.read_text(encoding="utf-8").splitlines() if ln.strip()
        )
        if after_lines < before_lines + len(validated):
            raise RuntimeError(
                f"preferences.jsonl did not grow as expected: before={before_lines}, "
                f"after={after_lines}, uploaded={len(validated)}"
            )

    return {
        "url": url,
        "http_status": status_code,
        "uploaded_count": len(validated),
        "server_response": parsed,
        "preferences_lines_before": before_lines,
        "preferences_lines_after": after_lines,
    }


def filter_high_purity_manifest_clips(
    manifest_path: Path,
    *,
    min_wespeaker_cos: float = DEFAULT_PURITY_MIN_WESPEAKER,
    min_dnsmos_p808: float = DEFAULT_PURITY_MIN_DNSMOS,
    master_cos_by_id: Mapping[str, float] | None = None,
    min_duration_sec: float = 1.5,
    max_duration_sec: float = 45.0,
) -> list[dict[str, Any]]:
    """Filter `manifest.jsonl` strictly to high-purity Ryan clips (WeSpeaker >= 0.78 & DNSMOS >= 3.50)."""
    if not manifest_path.is_file():
        raise FileNotFoundError(f"Missing dataset manifest: {manifest_path}")

    kept: list[dict[str, Any]] = []
    for line_no, raw_line in enumerate(manifest_path.read_text(encoding="utf-8").splitlines(), start=1):
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
        transcript = til.clean_transcript(str(row.get("transcript") or row.get("text") or ""))
        if len(transcript) < 4:
            continue
        dur = float(row.get("duration_sec") or 0.0)
        if dur < min_duration_sec or dur > max_duration_sec:
            continue
        clip_id = str(row.get("id") or f"clip_{line_no:04d}")
        manifest_cos = row.get("wespeaker_cos")
        master_cos = (
            float(master_cos_by_id[clip_id])
            if (master_cos_by_id is not None and clip_id in master_cos_by_id)
            else (float(manifest_cos) if manifest_cos is not None else 0.0)
        )
        dnsmos = float(row.get("dnsmos_p808") or 0.0)
        if master_cos < min_wespeaker_cos or dnsmos < min_dnsmos_p808:
            continue

        # Purity weight emphasizing both high WeSpeaker identity and clean DNSMOS acoustics
        purity_score = 1.5 * master_cos + 0.5 * (dnsmos / 5.0)
        kept.append(
            {
                "clip_id": clip_id,
                "wav_path": str(wav_path),
                "transcript": transcript,
                "duration_sec": round(dur, 4),
                "manifest_wespeaker_cos": float(manifest_cos) if manifest_cos is not None else None,
                "master_wespeaker_cos": round(master_cos, 5),
                "dnsmos_p808": round(dnsmos, 4),
                "purity_score": round(purity_score, 5),
            }
        )

    kept.sort(key=lambda item: (-item["purity_score"], item["clip_id"]))
    return kept


def compute_dpo_preference_loss(
    policy_chosen_logps: torch.Tensor,
    policy_rejected_logps: torch.Tensor,
    ref_chosen_logps: torch.Tensor,
    ref_rejected_logps: torch.Tensor,
    *,
    beta: float = DEFAULT_KL_COEF,
    length_scale: float | None = None,
) -> tuple[torch.Tensor, torch.Tensor, torch.Tensor]:
    """Compute length-normalized DPO loss using exact frozen reference per-token log-probs.

    `policy_*_logps` and `ref_*_logps` are 1D tensors of per-token log-probabilities for the
    active semantic target tokens. Using mean per-token log-ratios scaled by `length_scale`
    (defaulting to the mean length of chosen & rejected) eliminates sequence-length bias while
    preserving exact `beta = 0.04` natural sequence scaling.

    Returns `(loss, chosen_reward, rejected_reward)`.
    """
    if policy_chosen_logps.numel() == 0 or policy_rejected_logps.numel() == 0:
        raise ValueError("DPO requires non-empty chosen and rejected log-probability tensors")

    scale = float(
        length_scale
        if length_scale is not None
        else 0.5 * (policy_chosen_logps.numel() + policy_rejected_logps.numel())
    )
    chosen_ratio = (policy_chosen_logps.mean() - ref_chosen_logps.mean()) * scale
    rejected_ratio = (policy_rejected_logps.mean() - ref_rejected_logps.mean()) * scale
    chosen_reward = beta * chosen_ratio
    rejected_reward = beta * rejected_ratio
    margin = chosen_reward - rejected_reward
    loss = -F.logsigmoid(margin)
    return loss, chosen_reward.detach(), rejected_reward.detach()


def compute_grpo_group_loss(
    policy_token_logps_list: Sequence[torch.Tensor],
    old_token_logps_list: Sequence[torch.Tensor],
    ref_token_logps_list: Sequence[torch.Tensor],
    rewards: Sequence[float],
    *,
    kl_coef: float = DEFAULT_KL_COEF,
    clip_eps: float = 0.2,
    advantage_clip: float = 2.0,
) -> tuple[torch.Tensor, dict[str, float]]:
    """Compute clipped Group-Relative Policy Optimization (GRPO) + Schulman k3 KL loss."""
    n = len(policy_token_logps_list)
    if n < 2:
        raise ValueError(f"GRPO requires at least 2 candidates in a group, got {n}")
    if not (len(old_token_logps_list) == len(ref_token_logps_list) == len(rewards) == n):
        raise ValueError("Mismatched lengths across GRPO candidate lists")

    r_arr = np.asarray(rewards, dtype=np.float64)
    mean_r = float(np.mean(r_arr))
    std_r = float(np.std(r_arr))
    denom = std_r if std_r > 1e-6 else 1.0
    advantages = [
        float(max(-advantage_clip, min(advantage_clip, (float(r) - mean_r) / denom)))
        for r in rewards
    ]

    total_pg = torch.zeros((), device=policy_token_logps_list[0].device, dtype=torch.float32)
    total_kl = torch.zeros((), device=policy_token_logps_list[0].device, dtype=torch.float32)

    for pol_lp, old_lp, ref_lp, adv in zip(
        policy_token_logps_list, old_token_logps_list, ref_token_logps_list, advantages
    ):
        pol_f = pol_lp.float()
        old_f = old_lp.detach().float()
        ref_f = ref_lp.detach().float()

        log_ratio = torch.clamp(pol_f - old_f, min=-10.0, max=10.0)
        ratio = torch.exp(log_ratio)
        clipped = torch.clamp(ratio, 1.0 - clip_eps, 1.0 + clip_eps)
        adv_t = torch.tensor(adv, device=pol_f.device, dtype=torch.float32)
        surr = torch.minimum(ratio * adv_t, clipped * adv_t)
        pg_loss = -surr.mean()

        # Unbiased non-negative Schulman k3 KL estimator: E[exp(log p_ref - log p_theta) - (log p_ref - log p_theta) - 1]
        log_ref_over_pol = torch.clamp(ref_f - pol_f, min=-10.0, max=10.0)
        kl_k3 = (torch.exp(log_ref_over_pol) - log_ref_over_pol - 1.0).mean()

        total_pg = total_pg + pg_loss
        total_kl = total_kl + kl_k3

    mean_pg = total_pg / float(n)
    mean_kl = total_kl / float(n)
    total_loss = mean_pg + float(kl_coef) * mean_kl
    stats = {
        "grpo_pg_loss": float(mean_pg.detach().item()),
        "grpo_kl_k3": float(mean_kl.detach().item()),
        "grpo_mean_reward": mean_r,
        "grpo_std_reward": std_r,
    }
    return total_loss, stats


def compute_opsd_topk_kl_loss(
    student_logits: torch.Tensor,
    teacher_topk_indices: torch.Tensor,
    teacher_topk_logprobs: torch.Tensor,
) -> torch.Tensor:
    """Compute forward KL(p_teacher_topK || p_student) on active semantic positions.

    `student_logits`: `[T_active, vocab_size]`
    `teacher_topk_indices`: `[T_active, K]` long
    `teacher_topk_logprobs`: `[T_active, K]` float (normalized log-probs over top-K support)
    """
    student_log_probs = F.log_softmax(student_logits.float(), dim=-1)
    gathered_student_lp = torch.gather(student_log_probs, dim=-1, index=teacher_topk_indices)
    teacher_probs = torch.exp(teacher_topk_logprobs.float())
    teacher_probs = teacher_probs / torch.clamp(teacher_probs.sum(dim=-1, keepdim=True), min=1e-8)
    teacher_log_p = torch.log(torch.clamp(teacher_probs, min=1e-8))
    kl_per_token = torch.sum(teacher_probs * (teacher_log_p - gathered_student_lp), dim=-1)
    return kl_per_token.mean()


def sequence_target_logps(
    model: torch.nn.Module,
    prompt_ids: Sequence[int],
    target_ids: Sequence[int],
    *,
    device: str,
    return_active_logits: bool = False,
) -> tuple[torch.Tensor, torch.Tensor | None]:
    """Compute exact per-token causal LM log-probabilities over `target_ids` given `prompt_ids`."""
    if not prompt_ids or not target_ids:
        raise ValueError("prompt_ids and target_ids must both be non-empty")
    full_ids = list(prompt_ids) + list(target_ids)
    input_ids = torch.tensor([full_ids], dtype=torch.long, device=device)
    outputs = model(input_ids=input_ids, use_cache=False)
    logits = outputs.logits[0]  # [L, V]

    p_len = len(prompt_ids)
    t_len = len(target_ids)
    # Position (p_len - 1 + i) predicts target_ids[i]
    active_logits = logits[p_len - 1 : p_len - 1 + t_len].float()
    target_tensor = torch.tensor(target_ids, dtype=torch.long, device=device)
    log_probs = F.log_softmax(active_logits, dim=-1)
    token_logps = log_probs.gather(dim=-1, index=target_tensor.unsqueeze(-1)).squeeze(-1)
    return token_logps, (active_logits if return_active_logits else None)


# ---------------------------------------------------------------------------
# Phase 1: Real On-Policy Rollouts & Live preferences.jsonl Upload
# ---------------------------------------------------------------------------


def encode_clean_reference_turns(
    ref_specs: Sequence[tuple[Path, str]],
    *,
    codec_pth: Path,
    device: str = "mps",
) -> tuple[list[tuple[str, np.ndarray]], list[dict[str, Any]]]:
    """Encode Ryan's cleanest reference WAVs into `[10, T_ref]` ModdedDAC codes."""
    ref_turns: list[tuple[str, np.ndarray]] = []
    ref_meta: list[dict[str, Any]] = []
    for wav_path, transcript in ref_specs:
        codes, dur = s2_pro_infer.encode_reference_audio_to_codes(
            Path(wav_path), codec_pth=Path(codec_pth), device=device
        )
        ref_turns.append((transcript, codes))
        ref_meta.append(
            {
                "wav_path": str(wav_path),
                "duration_sec": round(dur, 3),
                "codes_shape": list(codes.shape),
                "transcript": transcript,
            }
        )
    return ref_turns, ref_meta


def run_phase1_rollouts_and_upload(
    *,
    s2_pro_hf_dir: Path = DEFAULT_S2_PRO_HF_DIR,
    lora_v001_dir: Path = DEFAULT_V001_LORA_DIR,
    codec_pth: Path = DEFAULT_CODEC_PTH,
    codec_tflite: Path = DEFAULT_CODEC_TFLITE,
    master_centroid_path: Path = DEFAULT_MASTER_CENTROID,
    lexicon_path: Path = DEFAULT_LEXICON_JSON,
    wespeaker_onnx: Path = DEFAULT_WESPEAKER_ONNX,
    dnsmos_onnx: Path = DEFAULT_DNSMOS_ONNX,
    whisper_bin: Path = DEFAULT_WHISPER_BIN,
    whisper_model: Path = DEFAULT_WHISPER_MODEL,
    work_dir: Path = DEFAULT_V002_WORK_DIR,
    ota_server_url: str = DEFAULT_OTA_SERVER_URL,
    preferences_jsonl: Path = DEFAULT_PREFERENCES_JSONL,
    prompts: Sequence[tuple[str, str]] = DEFAULT_ROLLOUT_PROMPTS,
    seeds: Sequence[int] = DEFAULT_ROLLOUT_SEEDS,
    temperature: float = 0.78,
    top_p: float = 0.82,
    top_k: int = 30,
    max_new_tokens: int = 280,
    device: str = "mps",
    upload_live: bool = True,
) -> dict[str, Any]:
    """Phase 1: Generate N=4 real on-policy rollouts per prompt, 4-gate score, and POST live."""
    work_dir = Path(work_dir)
    rollouts_dir = work_dir / "phase1_rollouts"
    rollouts_dir.mkdir(parents=True, exist_ok=True)

    print(
        f"=== [Phase 1] Encoding {len(DEFAULT_CLEAN_REFERENCE_TURNS)} clean high-purity reference turns ===",
        flush=True,
    )
    ref_turns, ref_meta = encode_clean_reference_turns(
        DEFAULT_CLEAN_REFERENCE_TURNS, codec_pth=codec_pth, device=device
    )
    for rm in ref_meta:
        print(
            f"  [ref] {Path(rm['wav_path']).name}: dur={rm['duration_sec']:.2f}s codes={rm['codes_shape']}",
            flush=True,
        )

    print(
        f"=== [Phase 1] Loading s2-pro Dual-AR + voice_lora_v001 ({lora_v001_dir}) ===",
        flush=True,
    )
    model, tokenizer, lora_stats = s2_pro_infer.load_s2_pro_dual_ar(
        s2_pro_hf_dir, lora_dir=lora_v001_dir, lora_scale=1.0, device=device
    )

    raw_generations: list[dict[str, Any]] = []
    t_gen_start = time.perf_counter()
    for p_idx, (clause_id, text) in enumerate(prompts):
        prompt_tensor = s2_pro_infer.build_s2_pro_prompt_tensor(
            tokenizer, text, ref_turns=ref_turns, prompt_style="multiturn"
        )
        for s_idx, base_seed in enumerate(seeds):
            seed = int(base_seed) + p_idx * 100
            gen_res = s2_pro_infer.generate_dual_ar_codes(
                model,
                prompt_tensor,
                seed=seed,
                max_new_tokens=max_new_tokens,
                temperature=temperature,
                top_p=top_p,
                top_k=top_k,
            )
            codes_path = rollouts_dir / f"{clause_id}_take{s_idx}_seed{seed}_codes.npy"
            np.save(codes_path, gen_res.codes)
            sem_vocab_ids = [
                til.SEMANTIC_START_TOKEN_ID + int(c) for c in gen_res.codes[0].tolist()
            ]
            raw_generations.append(
                {
                    "clause_id": clause_id,
                    "raw_text": text,
                    "take_index": s_idx,
                    "seed": seed,
                    "codes": gen_res.codes,
                    "codes_npy": str(codes_path),
                    "semantic_tokens": sem_vocab_ids,
                    "generated_frames": gen_res.generated_frames,
                    "hit_eos": gen_res.hit_eos,
                    "elapsed_sec": gen_res.elapsed_sec,
                    "frames_per_sec": gen_res.frames_per_sec,
                }
            )
            print(
                f"  [rollout] {clause_id} take={s_idx} seed={seed}: "
                f"frames={gen_res.generated_frames} ({gen_res.generated_frames * 2048 / 44100:.2f}s) "
                f"hit_eos={gen_res.hit_eos} {gen_res.frames_per_sec:.2f} fps",
                flush=True,
            )

    gen_total_sec = time.perf_counter() - t_gen_start
    del model
    if device == "mps" and torch.backends.mps.is_available():
        torch.mps.empty_cache()

    print(
        f"=== [Phase 1] Batch decoding {len(raw_generations)} rollouts via {codec_tflite.name} ===",
        flush=True,
    )
    decoded_batch = s2_pro_infer.decode_codes_batch_tflite(
        [item["codes"] for item in raw_generations],
        tflite_path=codec_tflite,
    )

    print(
        "=== [Phase 1] Scoring all rollouts with REAL 4-Gate Verifier + SingleSpeakerAudioEnv ===",
        flush=True,
    )
    master_centroid = sar.load_master_centroid_bin(master_centroid_path)
    wespeaker = eva.WeSpeakerEngine(wespeaker_onnx)
    dnsmos = eva.DnsmosP808Engine(dnsmos_onnx)
    lexicon_map = tvd.load_lexicon(lexicon_path) if lexicon_path.is_file() else {}

    grouped_rollouts: dict[str, list[dict[str, Any]]] = {}
    all_scored_rollouts: list[dict[str, Any]] = []

    for item, (pcm44k, dec_meta) in zip(raw_generations, decoded_batch):
        wav_path = rollouts_dir / f"{item['clause_id']}_take{item['take_index']}_seed{item['seed']}.wav"
        sf.write(str(wav_path), np.clip(pcm44k, -1.0, 1.0), sar.SAMPLE_RATE_HZ, subtype="PCM_16")
        gates = sar.evaluate_candidate_4gates(
            pcm44k,
            item["raw_text"],
            master_centroid=master_centroid,
            wespeaker=wespeaker,
            dnsmos=dnsmos,
            whisper_bin=whisper_bin,
            whisper_model=whisper_model,
        )
        spec_reward = score_rollout_reward(gates, human_chosen=False)
        scored = {
            "clause_id": item["clause_id"],
            "raw_text": item["raw_text"],
            "take_index": item["take_index"],
            "seed": item["seed"],
            "wav_path": str(wav_path),
            "codes_npy": item["codes_npy"],
            "generated_frames": item["generated_frames"],
            "hit_eos": item["hit_eos"],
            "semantic_tokens": item["semantic_tokens"],
            "decode_ms": dec_meta["decode_ms"],
            "gates": gates,
            "spec_reward": round(spec_reward, 5),
        }
        grouped_rollouts.setdefault(item["clause_id"], []).append(scored)
        all_scored_rollouts.append(scored)
        print(
            f"  [4-gate] {item['clause_id']} seed={item['seed']}: "
            f"CER={gates['cer']:.4f} WeSpeaker={gates['wespeaker_cosine']:.4f} "
            f"DNSMOS={gates['dnsmos_p808']:.3f} cadence={gates['syllables_per_sec']:.2f} "
            f"tail={gates['tail_rms_dbfs']:.1f} reward={spec_reward:+.4f}",
            flush=True,
        )

    preference_records: list[dict[str, Any]] = []
    base_ts = int(time.time())
    for p_idx, (clause_id, raw_text) in enumerate(prompts):
        rec = build_preference_record_from_rollouts(
            clause_id=clause_id,
            raw_text=raw_text,
            rollouts=grouped_rollouts[clause_id],
            lora_version=1,
            lexicon=lexicon_map,
            timestamp=base_ts + p_idx,
        )
        preference_records.append(rec)

    upload_result: dict[str, Any] | None = None
    if upload_live:
        print(
            f"=== [Phase 1] POSTing {len(preference_records)} strict preference tuples to {ota_server_url}/v1/preferences ===",
            flush=True,
        )
        upload_result = upload_preferences_to_ota_server(
            preference_records,
            ota_server_url=ota_server_url,
            preferences_path=preferences_jsonl,
        )
        print(
            f"  [ota] HTTP {upload_result['http_status']} accepted={upload_result['uploaded_count']} "
            f"preferences.jsonl lines: {upload_result['preferences_lines_before']} -> "
            f"{upload_result['preferences_lines_after']}",
            flush=True,
        )

    cers = [r["gates"]["cer"] for r in all_scored_rollouts]
    sims = [r["gates"]["wespeaker_cosine"] for r in all_scored_rollouts]
    dnss = [r["gates"]["dnsmos_p808"] for r in all_scored_rollouts]
    cads = [r["gates"]["syllables_per_sec"] for r in all_scored_rollouts]
    rews = [r["spec_reward"] for r in all_scored_rollouts]
    cer_zero_count = sum(1 for c in cers if c == 0.0)
    gate_fail_cer_count = sum(
        1
        for rec in preference_records
        for gf in rec["gate_failures"]
        if float(gf["cer"]) > 0.0
    )

    phase1_payload = {
        "generated_at_utc": datetime.datetime.now(datetime.timezone.utc)
        .replace(microsecond=0)
        .isoformat()
        .replace("+00:00", "Z"),
        "references": ref_meta,
        "lora_v001_merge": asdict(lora_stats) if lora_stats is not None else None,
        "sampling": {
            "num_prompts": len(prompts),
            "takes_per_prompt": len(seeds),
            "total_rollouts": len(all_scored_rollouts),
            "temperature": temperature,
            "top_p": top_p,
            "top_k": top_k,
            "max_new_tokens": max_new_tokens,
            "generation_elapsed_sec": round(gen_total_sec, 2),
        },
        "summary": {
            "cer_zero_rollouts": cer_zero_count,
            "total_rollouts": len(all_scored_rollouts),
            "mean_cer": round(float(np.mean(cers)), 5),
            "mean_wespeaker": round(float(np.mean(sims)), 5),
            "max_wespeaker": round(float(np.max(sims)), 5),
            "mean_dnsmos": round(float(np.mean(dnss)), 4),
            "max_dnsmos": round(float(np.max(dnss)), 4),
            "mean_cadence": round(float(np.mean(cads)), 3),
            "mean_spec_reward": round(float(np.mean(rews)), 5),
            "preference_tuples_built": len(preference_records),
            "cer_gate_failures_logged": gate_fail_cer_count,
        },
        "ota_upload": upload_result,
        "preference_records": preference_records,
        "rollouts": all_scored_rollouts,
    }
    out_json = work_dir / "phase1_rollouts.json"
    out_json.write_text(json.dumps(phase1_payload, indent=2) + "\n", encoding="utf-8")
    print(f"  [phase1] Wrote {out_json}", flush=True)
    return phase1_payload


# ---------------------------------------------------------------------------
# Phase 2: Real GRPO/DPO + High-Purity OPSD Update (v001 -> v002)
# ---------------------------------------------------------------------------


def _score_manifest_clips_against_master(
    manifest_path: Path,
    master_centroid_path: Path,
    wespeaker_onnx: Path,
) -> dict[str, float]:
    """Compute exact WeSpeaker cosine against `master_centroid.bin` for every manifest clip."""
    centroid = sar.load_master_centroid_bin(master_centroid_path)
    ws = eva.WeSpeakerEngine(wespeaker_onnx)
    scores: dict[str, float] = {}
    for line_no, raw_line in enumerate(manifest_path.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw_line.strip()
        if not line:
            continue
        row = json.loads(line)
        wav_str = row.get("wav_44k") or row.get("wav_path")
        if not wav_str or not Path(wav_str).is_file():
            continue
        cid = str(row.get("id") or f"clip_{line_no:04d}")
        wav16, _ = eva.load_audio_mono(Path(wav_str), target_sr=16_000)
        emb = ws.extract_embedding(wav16)
        scores[cid] = float(eva.cosine_similarity(emb, centroid))
    return scores


def _prepare_high_purity_anchor_sequences(
    *,
    manifest_path: Path,
    master_centroid_path: Path,
    wespeaker_onnx: Path,
    codec_pth: Path,
    tokenizer: Any,
    work_dir: Path,
    device: str = "mps",
    min_wespeaker: float = DEFAULT_PURITY_MIN_WESPEAKER,
    min_dnsmos: float = DEFAULT_PURITY_MIN_DNSMOS,
    max_seq_len: int = 768,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]], dict[str, Any]]:
    """Build high-purity OPSD/SFT anchor sequences from top manifest clips + voice_ref seeds."""
    cache_path = work_dir / "high_purity_anchor_tokens.json"

    master_scores = _score_manifest_clips_against_master(
        manifest_path, master_centroid_path, wespeaker_onnx
    )
    high_purity_clips = filter_high_purity_manifest_clips(
        manifest_path,
        min_wespeaker_cos=min_wespeaker,
        min_dnsmos_p808=min_dnsmos,
        master_cos_by_id=master_scores,
    )
    if not high_purity_clips:
        raise RuntimeError(
            f"No manifest clips met purity thresholds (cos>={min_wespeaker}, dnsmos>={min_dnsmos})"
        )

    # Reuse existing v001 ModdedDAC semantic_tokens_cache.json where available for speed
    v001_cache_path = DEFAULT_MODELS_ROOT / "lora-work-v001" / "semantic_tokens_cache.json"
    v001_token_map: dict[str, list[int]] = {}
    if v001_cache_path.is_file():
        for entry in json.loads(v001_cache_path.read_text(encoding="utf-8")):
            v001_token_map[str(entry["clip_id"])] = [int(c) for c in entry["semantic_codes"]]

    seed_specs: list[tuple[str, Path, str, float, float]] = []
    if DEFAULT_VOICE_REF_ALT_WAV.is_file():
        seed_specs.append(
            (
                "seed_voice_ref_alt",
                DEFAULT_VOICE_REF_ALT_WAV,
                DEFAULT_VOICE_REF_ALT_TRANSCRIPT,
                0.8501,
                3.0226,
            )
        )
    if DEFAULT_VOICE_REF_WAV.is_file():
        seed_specs.append(
            (
                "seed_voice_ref",
                DEFAULT_VOICE_REF_WAV,
                DEFAULT_VOICE_REF_TRANSCRIPT,
                0.8476,
                3.0339,
            )
        )

    # Encode any clips missing from v001 cache (including voice_ref.wav / voice_ref_alt.wav)
    cached_by_id: dict[str, list[int]] = {}
    if cache_path.is_file():
        for item in json.loads(cache_path.read_text(encoding="utf-8")):
            cached_by_id[str(item["clip_id"])] = [int(c) for c in item["semantic_codes"]]

    to_encode: list[til.VoiceClipEntry] = []
    for clip in high_purity_clips:
        cid = clip["clip_id"]
        if cid not in v001_token_map and cid not in cached_by_id:
            to_encode.append(
                til.VoiceClipEntry(
                    clip_id=cid,
                    wav_path=Path(clip["wav_path"]),
                    transcript=clip["transcript"],
                    duration_sec=float(clip["duration_sec"]),
                    wespeaker_cos=float(clip["master_wespeaker_cos"]),
                    dnsmos_p808=float(clip["dnsmos_p808"]),
                )
            )
    for sid, swav, stxt, scos, sdns in seed_specs:
        if sid not in cached_by_id:
            info = sf.info(str(swav))
            to_encode.append(
                til.VoiceClipEntry(
                    clip_id=sid,
                    wav_path=swav,
                    transcript=stxt,
                    duration_sec=float(info.frames) / float(info.samplerate),
                    wespeaker_cos=scos,
                    dnsmos_p808=sdns,
                )
            )

    if to_encode:
        modded_dac_path = til.resolve_modded_dac_path()
        encoded_new = til.encode_clips_to_semantic_tokens(
            entries=to_encode,
            codec_pth=codec_pth,
            modded_dac_path=modded_dac_path,
            device=device,
            cache_path=None,
        )
        for item in encoded_new:
            cached_by_id[str(item["clip_id"])] = [int(c) for c in item["semantic_codes"]]

    combined_records: list[dict[str, Any]] = []
    for clip in high_purity_clips:
        cid = clip["clip_id"]
        codes = cached_by_id.get(cid) or v001_token_map[cid]
        sft = til.build_sft_sequence(
            tokenizer, clip["transcript"], codes, max_seq_len=max_seq_len
        )
        combined_records.append(
            {
                "clip_id": cid,
                "origin": "high_purity_manifest",
                "master_wespeaker_cos": clip["master_wespeaker_cos"],
                "dnsmos_p808": clip["dnsmos_p808"],
                "purity_score": clip["purity_score"],
                "sample_weight": round(
                    max(
                        0.4,
                        min(
                            2.2,
                            0.6
                            + 2.5 * max(0.0, clip["master_wespeaker_cos"] - 0.80)
                            + 1.8 * max(0.0, clip["dnsmos_p808"] - 3.60),
                        ),
                    ),
                    4,
                ),
                "semantic_codes": codes,
                **sft,
            }
        )

    for sid, swav, stxt, scos, sdns in seed_specs:
        codes = cached_by_id[sid]
        sft = til.build_sft_sequence(tokenizer, stxt, codes, max_seq_len=max_seq_len)
        combined_records.append(
            {
                "clip_id": sid,
                "origin": "voice_ref_seed",
                "master_wespeaker_cos": scos,
                "dnsmos_p808": sdns,
                "purity_score": round(1.5 * scos + 0.5 * (sdns / 5.0), 5),
                # Gentle weight for lower-DNSMOS (3.02) seed refs so identity anchors without dragging MOS
                "sample_weight": 0.15,
                "semantic_codes": codes,
                **sft,
            }
        )

    cache_path.parent.mkdir(parents=True, exist_ok=True)
    cache_path.write_text(
        json.dumps(
            [{"clip_id": r["clip_id"], "semantic_codes": r["semantic_codes"]} for r in combined_records],
            indent=2,
        )
        + "\n",
        encoding="utf-8",
    )

    # Deterministic 5-clip held-out high-purity validation split (from manifest clips only)
    manifest_only = [r for r in combined_records if r["origin"] == "high_purity_manifest"]
    seed_only = [r for r in combined_records if r["origin"] == "voice_ref_seed"]
    train_m, val_m = til.split_train_val(manifest_only, val_fraction=0.12, seed=42)
    train_anchors = train_m + seed_only
    val_anchors = val_m

    meta = {
        "total_manifest_clips_scanned": len(master_scores),
        "high_purity_manifest_clips": len(high_purity_clips),
        "seed_ref_clips": len(seed_only),
        "train_anchor_count": len(train_anchors),
        "val_anchor_count": len(val_anchors),
        "min_wespeaker_threshold": min_wespeaker,
        "min_dnsmos_threshold": min_dnsmos,
        "mean_anchor_master_wespeaker": round(
            float(np.mean([r["master_wespeaker_cos"] for r in manifest_only])), 5
        ),
        "mean_anchor_dnsmos": round(
            float(np.mean([r["dnsmos_p808"] for r in manifest_only])), 4
        ),
    }
    return train_anchors, val_anchors, meta


def _build_opsd_teacher_prompt_ids(
    tokenizer: Any,
    raw_text: str,
    *,
    ref_transcript: str,
    ref_semantic_codes: Sequence[int],
    normalized_phonemes: str = "",
    user_gap_scale: float = 1.0,
) -> list[int]:
    """Build privileged teacher prompt conditioned on Ryan's cleanest reference turn + cadence/IPA."""
    ref_sem_vocab = [til.SEMANTIC_START_TOKEN_ID + int(c) for c in ref_semantic_codes]
    sys_extra = "convert the provided text to speech"
    if normalized_phonemes:
        sys_extra += f" (lexicon: {normalized_phonemes}; gap_scale={user_gap_scale:.2f})"

    parts: list[list[int]] = [
        list(tokenizer.encode(f"<|im_start|>system\n{sys_extra}<|im_end|>\n", add_special_tokens=False)),
        list(
            tokenizer.encode(
                f"<|im_start|>user\n<|speaker:0|>{til.clean_transcript(ref_transcript)}<|im_end|>\n",
                add_special_tokens=False,
            )
        ),
        list(tokenizer.encode("<|im_start|>assistant\n<|voice|>", add_special_tokens=False)),
        ref_sem_vocab,
        list(tokenizer.encode("<|im_end|>\n", add_special_tokens=False)),
        list(
            tokenizer.encode(
                f"<|im_start|>user\n<|speaker:0|>{til.clean_transcript(raw_text)}<|im_end|>\n",
                add_special_tokens=False,
            )
        ),
        list(tokenizer.encode("<|im_start|>assistant\n<|voice|>", add_special_tokens=False)),
    ]
    out: list[int] = []
    for seg in parts:
        out.extend(seg)
    return out


def run_phase2_grpo_opsd_training(
    *,
    s2_pro_hf_dir: Path = DEFAULT_S2_PRO_HF_DIR,
    lora_v001_dir: Path = DEFAULT_V001_LORA_DIR,
    codec_pth: Path = DEFAULT_CODEC_PTH,
    manifest_path: Path = DEFAULT_MANIFEST_JSONL,
    master_centroid_path: Path = DEFAULT_MASTER_CENTROID,
    wespeaker_onnx: Path = DEFAULT_WESPEAKER_ONNX,
    preferences_jsonl: Path = DEFAULT_PREFERENCES_JSONL,
    phase1_json: Path | None = None,
    work_dir: Path = DEFAULT_V002_WORK_DIR,
    output_gguf: Path = DEFAULT_V002_GGUF,
    llama_cpp_dir: Path = DEFAULT_LLAMA_CPP_DIR,
    device: str = "mps",
    beta: float = DEFAULT_KL_COEF,
    kl_coef: float = DEFAULT_KL_COEF,
    learning_rate: float = 1.2e-5,
    total_steps: int = 12,
    weight_decay: float = 0.01,
    w_dpo: float = 0.65,
    w_gate_fail: float = 1.0,
    w_grpo: float = 0.30,
    w_opsd_kl: float = 0.35,
    w_chosen_nll: float = 0.25,
    w_purity_anchor: float = 1.10,
    max_target_tokens: int = 256,
    opsd_topk: int = 32,
    seed: int = 2026,
    outtype: str = "f16",
) -> dict[str, Any]:
    """Phase 2: Real MPS BF16 GRPO/DPO + High-Purity OPSD update (`v001 -> v002`) + GGUF export."""
    torch.manual_seed(seed)
    rng = random.Random(seed)
    work_dir = Path(work_dir)
    work_dir.mkdir(parents=True, exist_ok=True)
    phase1_path = Path(phase1_json) if phase1_json is not None else (work_dir / "phase1_rollouts.json")

    if not preferences_jsonl.is_file():
        raise FileNotFoundError(f"Missing live preferences.jsonl: {preferences_jsonl}")
    pref_records = tvd.load_preferences_jsonl(preferences_jsonl)
    if not pref_records:
        raise RuntimeError(f"preferences.jsonl is empty: {preferences_jsonl}")

    opsd_grpo_ds = tvd.build_opsd_and_grpo_dataset(
        pref_records,
        lexicon=DEFAULT_LEXICON_JSON if DEFAULT_LEXICON_JSON.is_file() else None,
    )
    print(
        f"=== [Phase 2] Loaded {len(pref_records)} verified preference records from {preferences_jsonl} "
        f"({len(opsd_grpo_ds.opsd_pairs)} OPSD pairs, {len(opsd_grpo_ds.grpo_groups)} GRPO groups) ===",
        flush=True,
    )

    # Load per-rollout real 4-gate rewards from Phase 1 sidecar if available
    rollouts_by_clause: dict[str, list[dict[str, Any]]] = {}
    if phase1_path.is_file():
        p1_data = json.loads(phase1_path.read_text(encoding="utf-8"))
        for r in p1_data.get("rollouts", []):
            rollouts_by_clause.setdefault(str(r["clause_id"]), []).append(r)

    qwen3_cfg_dir = til.write_hf_qwen3_base_config_dir(
        s2_pro_hf_dir, work_dir / "qwen3_base_config"
    )
    tokenizer = AutoTokenizer.from_pretrained(str(qwen3_cfg_dir))

    print("=== [Phase 2] Preparing High-Purity OPSD/SFT anchor dataset ===", flush=True)
    train_anchors, val_anchors, anchor_meta = _prepare_high_purity_anchor_sequences(
        manifest_path=manifest_path,
        master_centroid_path=master_centroid_path,
        wespeaker_onnx=wespeaker_onnx,
        codec_pth=codec_pth,
        tokenizer=tokenizer,
        work_dir=work_dir,
        device=device,
    )
    print(
        f"  [purity] Selected {anchor_meta['high_purity_manifest_clips']}/{anchor_meta['total_manifest_clips_scanned']} "
        f"manifest clips (mean master_cos={anchor_meta['mean_anchor_master_wespeaker']:.4f}, "
        f"mean DNSMOS={anchor_meta['mean_anchor_dnsmos']:.3f}) + {anchor_meta['seed_ref_clips']} voice_ref anchors",
        flush=True,
    )

    # Load teacher reference semantic codes (cleanest high-DNSMOS anchor clip)
    teacher_ref_wav, teacher_ref_txt = DEFAULT_CLEAN_REFERENCE_TURNS[1]
    teacher_ref_codes, _ = s2_pro_infer.encode_reference_audio_to_codes(
        teacher_ref_wav, codec_pth=codec_pth, device=device
    )
    teacher_ref_sem0 = teacher_ref_codes[0, :180].tolist()  # ~8.3s crisp teacher prefix

    print(
        f"=== [Phase 2] Loading base s2-pro Qwen3ForCausalLM + initializing PEFT LoRA from {lora_v001_dir} ===",
        flush=True,
    )
    base_model, qwen3_cfg_dict = til.load_s2_pro_qwen3_model(
        s2_pro_hf_dir, dtype=torch.bfloat16, device=device
    )
    peft_model = PeftModel.from_pretrained(
        base_model,
        str(lora_v001_dir),
        is_trainable=True,
    )
    peft_model.to(device=device)
    trainable_params = [p for p in peft_model.parameters() if p.requires_grad]
    n_trainable = sum(p.numel() for p in trainable_params)
    if n_trainable <= 0:
        raise RuntimeError("Loaded PEFT LoRA v001 has 0 trainable parameters")
    print(f"  [peft] Loaded v001 Rank-32 LoRA ({n_trainable:,} trainable params on {device})", flush=True)

    # Deduplicate preference records by clause_id (keep latest timestamp per clause)
    latest_by_clause: dict[str, tvd.PreferenceRecord] = {}
    for rec in pref_records:
        prev = latest_by_clause.get(rec.clause_id)
        if prev is None or rec.timestamp >= prev.timestamp:
            latest_by_clause[rec.clause_id] = rec
    active_pref_records = [latest_by_clause[k] for k in sorted(latest_by_clause.keys())]

    def _prep_target(toks: Sequence[int]) -> list[int]:
        norm = normalize_semantic_vocab_tokens(toks)
        if len(norm) > max_target_tokens - 1:
            norm = norm[: max_target_tokens - 1]
        return norm + [til.IM_END_TOKEN_ID]

    print(
        "=== [Phase 2] Caching exact frozen base s2-pro ref log-probs, v001 log-probs & OPSD teacher top-K ===",
        flush=True,
    )
    peft_model.eval()
    prepared_groups: list[dict[str, Any]] = []

    with torch.no_grad():
        for rec in active_pref_records:
            student_prompt_str = til.build_s2_pro_prompt(rec.raw_text)
            student_prompt_ids = list(
                tokenizer.encode(student_prompt_str, add_special_tokens=False)
            )
            chosen_target = _prep_target(rec.chosen_tokens)
            rejected_target = _prep_target(rec.rejected_tokens)

            # Collect all unique candidate token sequences for this prompt group
            group_candidates: list[dict[str, Any]] = []
            clause_rollouts = rollouts_by_clause.get(rec.clause_id, [])
            if len(clause_rollouts) >= 2:
                for cr in clause_rollouts:
                    t_ids = _prep_target(cr["semantic_tokens"])
                    is_chosen = t_ids == chosen_target
                    rew = score_rollout_reward(cr["gates"], human_chosen=is_chosen)
                    group_candidates.append(
                        {
                            "seed": int(cr["seed"]),
                            "target_ids": t_ids,
                            "reward": float(rew),
                            "cer": float(cr["gates"]["cer"]),
                            "is_chosen": is_chosen,
                        }
                    )
            else:
                # Fallback strictly from verified preference_tuple fields (no fabricated metrics)
                group_candidates.append(
                    {
                        "seed": 0,
                        "target_ids": chosen_target,
                        "reward": 1.0,
                        "cer": 0.0,
                        "is_chosen": True,
                    }
                )
                group_candidates.append(
                    {
                        "seed": 1,
                        "target_ids": rejected_target,
                        "reward": 0.0,
                        "cer": 0.0,
                        "is_chosen": False,
                    }
                )
                for gf in rec.gate_failures:
                    if gf.tokens:
                        group_candidates.append(
                            {
                                "seed": int(gf.seed),
                                "target_ids": _prep_target(gf.tokens),
                                "reward": -2.0 if gf.cer > 0.0 else -0.5,
                                "cer": float(gf.cer),
                                "is_chosen": False,
                            }
                        )

            # 1. Exact frozen base s2-pro reference log-probs via disable_adapter()
            with peft_model.disable_adapter():
                ref_chosen_lp, _ = sequence_target_logps(
                    peft_model, student_prompt_ids, chosen_target, device=device
                )
                ref_rejected_lp, _ = sequence_target_logps(
                    peft_model, student_prompt_ids, rejected_target, device=device
                )
                ref_chosen_cpu = ref_chosen_lp.detach().cpu()
                ref_rejected_cpu = ref_rejected_lp.detach().cpu()

                gate_fail_entries: list[dict[str, Any]] = []
                for gf in rec.gate_failures:
                    if not gf.tokens:
                        continue
                    gf_target = _prep_target(gf.tokens)
                    if gf_target == chosen_target:
                        continue
                    ref_gf_lp, _ = sequence_target_logps(
                        peft_model, student_prompt_ids, gf_target, device=device
                    )
                    gate_fail_entries.append(
                        {
                            "seed": int(gf.seed),
                            "cer": float(gf.cer),
                            "reason": gf.reason,
                            "target_ids": gf_target,
                            "ref_logps": ref_gf_lp.detach().cpu(),
                        }
                    )

                for gc in group_candidates:
                    r_lp, _ = sequence_target_logps(
                        peft_model, student_prompt_ids, gc["target_ids"], device=device
                    )
                    gc["ref_logps"] = r_lp.detach().cpu()

            # 2. Pre-update v001 policy log-probs + privileged OPSD teacher top-K distributions
            v1_chosen_lp, _ = sequence_target_logps(
                peft_model, student_prompt_ids, chosen_target, device=device
            )
            v1_rejected_lp, _ = sequence_target_logps(
                peft_model, student_prompt_ids, rejected_target, device=device
            )
            for gc in group_candidates:
                o_lp, _ = sequence_target_logps(
                    peft_model, student_prompt_ids, gc["target_ids"], device=device
                )
                gc["v1_logps"] = o_lp.detach().cpu()

            teacher_prompt_ids = _build_opsd_teacher_prompt_ids(
                tokenizer,
                rec.raw_text,
                ref_transcript=teacher_ref_txt,
                ref_semantic_codes=teacher_ref_sem0,
                normalized_phonemes=rec.normalized_phonemes,
                user_gap_scale=rec.user_gap_scale,
            )
            _, teacher_logits = sequence_target_logps(
                peft_model,
                teacher_prompt_ids,
                chosen_target,
                device=device,
                return_active_logits=True,
            )
            assert teacher_logits is not None
            teacher_lsm = F.log_softmax(teacher_logits.float(), dim=-1)
            tk_vals, tk_idx = torch.topk(teacher_lsm, k=opsd_topk, dim=-1)
            tk_logprobs = F.log_softmax(tk_vals, dim=-1)

            prepared_groups.append(
                {
                    "clause_id": rec.clause_id,
                    "raw_text": rec.raw_text,
                    "user_gap_scale": float(rec.user_gap_scale),
                    "student_prompt_ids": student_prompt_ids,
                    "chosen_target": chosen_target,
                    "rejected_target": rejected_target,
                    "ref_chosen_logps": ref_chosen_cpu,
                    "ref_rejected_logps": ref_rejected_cpu,
                    "v1_chosen_logps": v1_chosen_lp.detach().cpu(),
                    "v1_rejected_logps": v1_rejected_lp.detach().cpu(),
                    "gate_failures": gate_fail_entries,
                    "group_candidates": group_candidates,
                    "teacher_topk_indices": tk_idx.detach().cpu(),
                    "teacher_topk_logprobs": tk_logprobs.detach().cpu(),
                }
            )

    def _eval_preference_snapshot() -> dict[str, Any]:
        was_training = peft_model.training
        peft_model.eval()
        chosen_lps: list[float] = []
        rejected_lps: list[float] = []
        v1_chosen_lps: list[float] = []
        dpo_margins: list[float] = []
        dpo_losses: list[float] = []
        pref_wins = 0
        with torch.no_grad():
            for grp in prepared_groups:
                p_ids = grp["student_prompt_ids"]
                pol_w, _ = sequence_target_logps(peft_model, p_ids, grp["chosen_target"], device=device)
                pol_l, _ = sequence_target_logps(peft_model, p_ids, grp["rejected_target"], device=device)
                ref_w = grp["ref_chosen_logps"].to(device=device)
                ref_l = grp["ref_rejected_logps"].to(device=device)
                d_loss, rw, rl = compute_dpo_preference_loss(
                    pol_w, pol_l, ref_w, ref_l, beta=beta
                )
                chosen_lps.append(float(pol_w.mean().item()))
                rejected_lps.append(float(pol_l.mean().item()))
                v1_chosen_lps.append(float(grp["v1_chosen_logps"].mean().item()))
                margin = float((rw - rl).item())
                dpo_margins.append(margin)
                dpo_losses.append(float(d_loss.item()))
                if margin > 0.0:
                    pref_wins += 1
        val_anchor_loss = til.evaluate_loss(peft_model, val_anchors, device=device) if val_anchors else None
        if was_training:
            peft_model.train()
        return {
            "mean_chosen_logp": round(float(np.mean(chosen_lps)), 6),
            "mean_rejected_logp": round(float(np.mean(rejected_lps)), 6),
            "mean_chosen_logp_delta_vs_v1": round(
                float(np.mean(chosen_lps) - np.mean(v1_chosen_lps)), 6
            ),
            "mean_dpo_margin": round(float(np.mean(dpo_margins)), 6),
            "mean_dpo_loss": round(float(np.mean(dpo_losses)), 6),
            "preference_win_rate": round(pref_wins / max(len(prepared_groups), 1), 4),
            "val_high_purity_nll": round(val_anchor_loss, 6) if val_anchor_loss is not None else None,
        }

    pre_snapshot = _eval_preference_snapshot()
    print(
        f"  [pre-train v001] chosen_logp={pre_snapshot['mean_chosen_logp']:.4f} "
        f"rejected_logp={pre_snapshot['mean_rejected_logp']:.4f} "
        f"dpo_margin={pre_snapshot['mean_dpo_margin']:+.4f} "
        f"val_purity_nll={pre_snapshot['val_high_purity_nll']}",
        flush=True,
    )

    optimizer = torch.optim.AdamW(
        trainable_params,
        lr=learning_rate,
        betas=(0.9, 0.999),
        weight_decay=weight_decay,
    )
    warmup_steps = max(2, total_steps // 6)

    def _lr_schedule(step_idx: int) -> float:
        if step_idx < warmup_steps:
            return learning_rate * float(step_idx + 1) / float(warmup_steps)
        prog = float(step_idx - warmup_steps) / float(max(1, total_steps - warmup_steps))
        prog = min(max(prog, 0.0), 1.0)
        return learning_rate * (0.1 + 0.9 * 0.5 * (1.0 + math.cos(math.pi * prog)))

    peft_model.train()
    step_history: list[dict[str, Any]] = []
    t_train_start = time.perf_counter()

    # Keep micro-steps bounded cleanly so MPS memory stays flat and fast
    anchor_order = list(range(len(train_anchors)))
    rng.shuffle(anchor_order)
    anchor_cursor = 0

    for step in range(total_steps):
        optimizer.zero_grad(set_to_none=True)
        lr_now = _lr_schedule(step)
        for pg in optimizer.param_groups:
            pg["lr"] = lr_now

        # Select 2 preference groups + 2 high-purity anchor clips per optimizer step
        g_indices = [
            (step * 2 + offset) % len(prepared_groups)
            for offset in range(min(2, len(prepared_groups)))
        ]
        n_g = len(g_indices)

        step_dpo_sum = 0.0
        step_gf_sum = 0.0
        step_grpo_sum = 0.0
        step_opsd_sum = 0.0
        step_cnll_sum = 0.0
        step_anchor_sum = 0.0

        for g_idx in g_indices:
            grp = prepared_groups[g_idx]
            p_ids = grp["student_prompt_ids"]

            # Forward on chosen (with active logits for OPSD top-K KL distillation)
            pol_w, active_w_logits = sequence_target_logps(
                peft_model, p_ids, grp["chosen_target"], device=device, return_active_logits=True
            )
            pol_l, _ = sequence_target_logps(
                peft_model, p_ids, grp["rejected_target"], device=device
            )
            ref_w = grp["ref_chosen_logps"].to(device=device)
            ref_l = grp["ref_rejected_logps"].to(device=device)

            dpo_loss, _, _ = compute_dpo_preference_loss(pol_w, pol_l, ref_w, ref_l, beta=beta)
            chosen_nll = -pol_w.mean()
            assert active_w_logits is not None
            opsd_kl = compute_opsd_topk_kl_loss(
                active_w_logits,
                grp["teacher_topk_indices"].to(device=device),
                grp["teacher_topk_logprobs"].to(device=device),
            )

            # Cache per-group forward passes so each unique sequence runs once per step
            seq_cache: dict[tuple[int, ...], torch.Tensor] = {
                tuple(grp["chosen_target"]): pol_w,
                tuple(grp["rejected_target"]): pol_l,
            }

            # Hard-negative gate-failure pairwise DPO penalty
            gf_loss = torch.zeros((), device=device, dtype=torch.float32)
            gf_list = grp["gate_failures"]
            if gf_list:
                # Prioritize CER > 0 hard negatives first, cap at 2 per group for speed
                ordered_gfs = sorted(gf_list, key=lambda x: -float(x["cer"]))[:2]
                for gf in ordered_gfs:
                    gf_key = tuple(gf["target_ids"])
                    pol_gf = seq_cache.get(gf_key)
                    if pol_gf is None:
                        pol_gf, _ = sequence_target_logps(
                            peft_model, p_ids, gf["target_ids"], device=device
                        )
                        seq_cache[gf_key] = pol_gf
                    ref_gf = gf["ref_logps"].to(device=device)
                    pair_gf_loss, _, _ = compute_dpo_preference_loss(
                        pol_w, pol_gf, ref_w, ref_gf, beta=beta
                    )
                    weight_mult = 1.5 if float(gf["cer"]) > 0.0 else 0.75
                    gf_loss = gf_loss + weight_mult * pair_gf_loss
                gf_loss = gf_loss / float(len(ordered_gfs))

            # GRPO group-relative loss across all candidates for this prompt
            grpo_loss = torch.zeros((), device=device, dtype=torch.float32)
            cands = grp["group_candidates"]
            if len(cands) >= 2:
                pol_cands: list[torch.Tensor] = []
                old_cands: list[torch.Tensor] = []
                ref_cands: list[torch.Tensor] = []
                rew_cands: list[float] = []
                for cand in cands:
                    c_key = tuple(cand["target_ids"])
                    c_lp = seq_cache.get(c_key)
                    if c_lp is None:
                        c_lp, _ = sequence_target_logps(
                            peft_model, p_ids, cand["target_ids"], device=device
                        )
                        seq_cache[c_key] = c_lp
                    pol_cands.append(c_lp)
                    old_cands.append(cand["v1_logps"].to(device=device))
                    ref_cands.append(cand["ref_logps"].to(device=device))
                    rew_cands.append(float(cand["reward"]))
                grpo_loss, _ = compute_grpo_group_loss(
                    pol_cands, old_cands, ref_cands, rew_cands, kl_coef=kl_coef
                )

            # Down-weight DPO margin on pace-edited (slow chosen) pairs so cadence stays brisk
            cadence_dpo_scale = 0.5 if float(grp.get("user_gap_scale", 1.0)) < 0.95 else 1.0
            pref_total = (
                (w_dpo * cadence_dpo_scale) * dpo_loss
                + w_gate_fail * gf_loss
                + w_grpo * grpo_loss
                + w_opsd_kl * opsd_kl
                + w_chosen_nll * chosen_nll
            ) / float(n_g)

            if not torch.isfinite(pref_total):
                raise RuntimeError(f"Non-finite preference loss at step={step + 1}")
            pref_total.backward()

            step_dpo_sum += float(dpo_loss.detach().item()) / float(n_g)
            step_gf_sum += float(gf_loss.detach().item()) / float(n_g)
            step_grpo_sum += float(grpo_loss.detach().item()) / float(n_g)
            step_opsd_sum += float(opsd_kl.detach().item()) / float(n_g)
            step_cnll_sum += float(chosen_nll.detach().item()) / float(n_g)

        # High-Purity SFT anchor micro-batch (2 clean high-WeSpeaker/high-DNSMOS Ryan clips)
        n_anchors_step = min(2, len(train_anchors))
        for _ in range(n_anchors_step):
            if anchor_cursor >= len(anchor_order):
                rng.shuffle(anchor_order)
                anchor_cursor = 0
            anchor_ex = train_anchors[anchor_order[anchor_cursor]]
            anchor_cursor += 1
            in_ids = torch.tensor([anchor_ex["input_ids"]], dtype=torch.long, device=device)
            lbls = torch.tensor([anchor_ex["labels"]], dtype=torch.long, device=device)
            out_a = peft_model(input_ids=in_ids, labels=lbls, use_cache=False)
            sw = float(anchor_ex.get("sample_weight", 1.0))
            anchor_loss = (w_purity_anchor * sw / float(n_anchors_step)) * out_a.loss
            if not torch.isfinite(anchor_loss):
                raise RuntimeError(f"Non-finite anchor loss at step={step + 1}")
            anchor_loss.backward()
            step_anchor_sum += float(out_a.loss.detach().float().item()) / float(n_anchors_step)

        grad_norm = float(torch.nn.utils.clip_grad_norm_(trainable_params, max_norm=0.5).item())
        optimizer.step()

        step_entry = {
            "step": step + 1,
            "lr": round(lr_now, 8),
            "grad_norm": round(grad_norm, 4),
            "dpo_loss": round(step_dpo_sum, 5),
            "gate_fail_loss": round(step_gf_sum, 5),
            "grpo_loss": round(step_grpo_sum, 5),
            "opsd_kl": round(step_opsd_sum, 5),
            "chosen_nll": round(step_cnll_sum, 5),
            "purity_anchor_nll": round(step_anchor_sum, 5),
        }
        step_history.append(step_entry)
        if (step + 1) == 1 or (step + 1) % 4 == 0 or (step + 1) == total_steps:
            print(
                f"  [grpo-v2] step {step + 1:2d}/{total_steps} lr={lr_now:.2e} "
                f"dpo={step_dpo_sum:.4f} gf={step_gf_sum:.4f} grpo={step_grpo_sum:+.4f} "
                f"opsd_kl={step_opsd_sum:.4f} chosen_nll={step_cnll_sum:.4f} "
                f"anchor={step_anchor_sum:.4f} gnorm={grad_norm:.3f}",
                flush=True,
            )

    if device == "mps" and torch.backends.mps.is_available():
        torch.mps.synchronize()
    train_elapsed_sec = time.perf_counter() - t_train_start

    post_snapshot = _eval_preference_snapshot()
    print(
        f"  [post-train v002] chosen_logp={post_snapshot['mean_chosen_logp']:.4f} "
        f"(delta_vs_v1={post_snapshot['mean_chosen_logp_delta_vs_v1']:+.4f}) "
        f"dpo_margin={post_snapshot['mean_dpo_margin']:+.4f} "
        f"win_rate={post_snapshot['preference_win_rate']:.2%} "
        f"val_purity_nll={post_snapshot['val_high_purity_nll']}",
        flush=True,
    )

    adapter_v2_dir = work_dir / "peft_lora_adapter"
    adapter_v2_dir.mkdir(parents=True, exist_ok=True)
    peft_model.save_pretrained(str(adapter_v2_dir), safe_serialization=True)

    # Verify saved v002 weights are finite and genuinely updated relative to v001
    v1_st = Path(lora_v001_dir) / "adapter_model.safetensors"
    v2_st = adapter_v2_dir / "adapter_model.safetensors"
    max_param_diff = 0.0
    l2_diffs: list[float] = []
    with safe_open(str(v1_st), framework="pt", device="cpu") as f1, safe_open(
        str(v2_st), framework="pt", device="cpu"
    ) as f2:
        v2_keys = sorted(f2.keys())
        for k in v2_keys:
            t2 = f2.get_tensor(k).float()
            if not torch.isfinite(t2).all():
                raise RuntimeError(f"Non-finite LoRA v002 tensor: {k}")
            t1 = f1.get_tensor(k).float()
            diff = (t2 - t1).abs()
            max_param_diff = max(max_param_diff, float(diff.max().item()))
            l2_diffs.append(float(torch.linalg.norm(t2 - t1).item()))

    if max_param_diff <= 1e-7:
        raise RuntimeError("Saved LoRA v002 is identical to v001 (zero weight update)")

    del peft_model
    del base_model
    if device == "mps" and torch.backends.mps.is_available():
        torch.mps.empty_cache()

    print(
        f"=== [Phase 2] Exporting PEFT LoRA v002 -> {output_gguf} ({outtype}) ===",
        flush=True,
    )
    export_report = til.export_peft_lora_to_gguf(
        adapter_dir=adapter_v2_dir,
        qwen3_base_config_dir=qwen3_cfg_dir,
        llama_cpp_dir=llama_cpp_dir,
        output_gguf=output_gguf,
        outtype=outtype,
    )
    print(
        f"  [export] {export_report['gguf_path']} ({export_report['size_mb']:.2f} MB, "
        f"{export_report['tensor_count']} tensors, SHA-256={export_report['sha256']})",
        flush=True,
    )

    phase2_report = {
        "completed_at_utc": datetime.datetime.now(datetime.timezone.utc)
        .replace(microsecond=0)
        .isoformat()
        .replace("+00:00", "Z"),
        "init_lora_dir": str(lora_v001_dir),
        "saved_lora_dir": str(adapter_v2_dir),
        "preferences_jsonl": str(preferences_jsonl),
        "preference_records_used": len(active_pref_records),
        "high_purity_anchor": anchor_meta,
        "hyperparameters": {
            "beta": beta,
            "kl_coef": kl_coef,
            "learning_rate": learning_rate,
            "total_steps": total_steps,
            "weight_decay": weight_decay,
            "w_dpo": w_dpo,
            "w_gate_fail": w_gate_fail,
            "w_grpo": w_grpo,
            "w_opsd_kl": w_opsd_kl,
            "w_chosen_nll": w_chosen_nll,
            "w_purity_anchor": w_purity_anchor,
            "opsd_topk": opsd_topk,
            "seed": seed,
            "device": device,
        },
        "trainable_params": n_trainable,
        "elapsed_sec": round(train_elapsed_sec, 2),
        "pre_update_v001": pre_snapshot,
        "post_update_v002": post_snapshot,
        "weight_delta_v2_vs_v1": {
            "max_abs_diff": round(max_param_diff, 6),
            "mean_tensor_l2_diff": round(float(np.mean(l2_diffs)), 6),
            "tensors_checked": len(l2_diffs),
        },
        "step_history": step_history,
        "export": export_report,
    }
    (work_dir / "phase2_training_report.json").write_text(
        json.dumps(phase2_report, indent=2) + "\n", encoding="utf-8"
    )
    return phase2_report


# ---------------------------------------------------------------------------
# Phase 3: Held-Out A/B Evaluation & Gate 5 Live Atomic Hot-Swap (v001 <-> v002)
# ---------------------------------------------------------------------------


def _evaluate_adapter_on_heldout(
    *,
    label: str,
    lora_dir: Path,
    ref_turns: Sequence[tuple[str, np.ndarray]],
    prompts: Sequence[tuple[str, str]],
    seeds: Sequence[int],
    s2_pro_hf_dir: Path,
    codec_tflite: Path,
    master_centroid: np.ndarray,
    wespeaker: eva.WeSpeakerEngine,
    dnsmos: eva.DnsmosP808Engine,
    whisper_bin: Path,
    whisper_model: Path,
    out_dir: Path,
    temperature: float = 0.72,
    top_p: float = 0.80,
    top_k: int = 30,
    max_new_tokens: int = 320,
    device: str = "mps",
) -> dict[str, Any]:
    """Synthesize and 4-gate score a LoRA adapter across all held-out prompts & seeds."""
    out_dir.mkdir(parents=True, exist_ok=True)
    model, tokenizer, lora_stats = s2_pro_infer.load_s2_pro_dual_ar(
        s2_pro_hf_dir, lora_dir=lora_dir, lora_scale=1.0, device=device
    )

    raw_takes: list[dict[str, Any]] = []
    for clause_id, text in prompts:
        prompt_tensor = s2_pro_infer.build_s2_pro_prompt_tensor(
            tokenizer, text, ref_turns=ref_turns, prompt_style="multiturn"
        )
        for seed in seeds:
            gen_res = s2_pro_infer.generate_dual_ar_codes(
                model,
                prompt_tensor,
                seed=int(seed),
                max_new_tokens=max_new_tokens,
                temperature=temperature,
                top_p=top_p,
                top_k=top_k,
            )
            codes_npy = out_dir / f"{label}_{clause_id}_seed{seed}_codes.npy"
            np.save(codes_npy, gen_res.codes)
            raw_takes.append(
                {
                    "clause_id": clause_id,
                    "raw_text": text,
                    "seed": int(seed),
                    "codes": gen_res.codes,
                    "codes_npy": str(codes_npy),
                    "semantic_tokens": [
                        til.SEMANTIC_START_TOKEN_ID + int(c) for c in gen_res.codes[0].tolist()
                    ],
                    "generated_frames": gen_res.generated_frames,
                    "hit_eos": gen_res.hit_eos,
                    "elapsed_sec": gen_res.elapsed_sec,
                    "frames_per_sec": gen_res.frames_per_sec,
                }
            )

    del model
    if device == "mps" and torch.backends.mps.is_available():
        torch.mps.empty_cache()

    decoded = s2_pro_infer.decode_codes_batch_tflite(
        [t["codes"] for t in raw_takes], tflite_path=codec_tflite
    )

    takes_out: list[dict[str, Any]] = []
    by_prompt: dict[str, list[dict[str, Any]]] = {}
    for item, (pcm44k, dec_meta) in zip(raw_takes, decoded):
        wav_path = out_dir / f"{label}_{item['clause_id']}_seed{item['seed']}.wav"
        sf.write(str(wav_path), np.clip(pcm44k, -1.0, 1.0), sar.SAMPLE_RATE_HZ, subtype="PCM_16")
        gates = sar.evaluate_candidate_4gates(
            pcm44k,
            item["raw_text"],
            master_centroid=master_centroid,
            wespeaker=wespeaker,
            dnsmos=dnsmos,
            whisper_bin=whisper_bin,
            whisper_model=whisper_model,
        )
        rew = score_rollout_reward(gates, human_chosen=False)
        rec = {
            "clause_id": item["clause_id"],
            "raw_text": item["raw_text"],
            "seed": item["seed"],
            "wav_path": str(wav_path),
            "codes_npy": item["codes_npy"],
            "generated_frames": item["generated_frames"],
            "hit_eos": item["hit_eos"],
            "semantic_tokens": item["semantic_tokens"],
            "decode_ms": dec_meta["decode_ms"],
            "gates": gates,
            "spec_reward": round(rew, 5),
        }
        takes_out.append(rec)
        by_prompt.setdefault(item["clause_id"], []).append(rec)
        print(
            f"  [A/B {label}] {item['clause_id']} seed={item['seed']}: "
            f"CER={gates['cer']:.4f} WeSpeaker={gates['wespeaker_cosine']:.4f} "
            f"DNSMOS={gates['dnsmos_p808']:.3f} cadence={gates['syllables_per_sec']:.2f} "
            f"tail={gates['tail_rms_dbfs']:.1f} rew={rew:+.4f}",
            flush=True,
        )

    best_per_prompt = [
        max(cands, key=lambda r: compute_rollout_selection_score(r["gates"]))
        for _, cands in sorted(by_prompt.items())
    ]

    def _agg(items: Sequence[dict[str, Any]]) -> dict[str, Any]:
        return {
            "count": len(items),
            "cer_zero_count": sum(1 for x in items if float(x["gates"]["cer"]) == 0.0),
            "mean_cer": round(float(np.mean([x["gates"]["cer"] for x in items])), 5),
            "mean_wespeaker": round(
                float(np.mean([x["gates"]["wespeaker_cosine"] for x in items])), 5
            ),
            "max_wespeaker": round(
                float(np.max([x["gates"]["wespeaker_cosine"] for x in items])), 5
            ),
            "mean_dnsmos": round(float(np.mean([x["gates"]["dnsmos_p808"] for x in items])), 4),
            "max_dnsmos": round(float(np.max([x["gates"]["dnsmos_p808"] for x in items])), 4),
            "mean_cadence": round(
                float(np.mean([x["gates"]["syllables_per_sec"] for x in items])), 3
            ),
            "in_band_cadence_count": sum(
                1
                for x in items
                if sar.GATE_MIN_SYLLABLES_PER_SEC
                <= float(x["gates"]["syllables_per_sec"])
                <= sar.GATE_MAX_SYLLABLES_PER_SEC
            ),
            "tail_pass_count": sum(
                1
                for x in items
                if float(x["gates"]["tail_rms_dbfs"]) <= sar.GATE_MAX_TAIL_RMS_DBFS
            ),
            "mean_spec_reward": round(float(np.mean([x["spec_reward"] for x in items])), 5),
        }

    return {
        "label": label,
        "lora_dir": str(lora_dir),
        "lora_merge_stats": asdict(lora_stats) if lora_stats is not None else None,
        "all_takes_summary": _agg(takes_out),
        "best_of_n_summary": _agg(best_per_prompt),
        "best_per_prompt": [
            {
                "clause_id": b["clause_id"],
                "seed": b["seed"],
                "gates": b["gates"],
                "spec_reward": b["spec_reward"],
                "wav_path": b["wav_path"],
            }
            for b in best_per_prompt
        ],
        "takes": takes_out,
    }


def measure_chosen_logp_delta_v1_vs_v2(
    *,
    s2_pro_hf_dir: Path,
    lora_v001_dir: Path,
    lora_v002_dir: Path,
    eval_pairs: Sequence[tuple[str, str, Sequence[int]]],
    device: str = "mps",
) -> dict[str, Any]:
    """Measure exact per-token log-prob delta (`logp_v2 - logp_v1`) on chosen semantic tokens."""
    if not eval_pairs:
        raise ValueError("eval_pairs must not be empty")

    tokenizer = AutoTokenizer.from_pretrained(str(s2_pro_hf_dir))
    base_model, _ = til.load_s2_pro_qwen3_model(
        s2_pro_hf_dir, dtype=torch.bfloat16, device=device
    )
    peft_model = PeftModel.from_pretrained(
        base_model, str(lora_v001_dir), adapter_name="v001", is_trainable=False
    )
    peft_model.load_adapter(str(lora_v002_dir), adapter_name="v002", is_trainable=False)
    peft_model.to(device=device)
    peft_model.eval()

    per_item: list[dict[str, Any]] = []
    with torch.no_grad():
        for clause_id, raw_text, chosen_tokens in eval_pairs:
            p_ids = list(
                tokenizer.encode(til.build_s2_pro_prompt(raw_text), add_special_tokens=False)
            )
            t_ids = normalize_semantic_vocab_tokens(chosen_tokens) + [til.IM_END_TOKEN_ID]

            peft_model.set_adapter("v001")
            lp_v1, _ = sequence_target_logps(peft_model, p_ids, t_ids, device=device)

            peft_model.set_adapter("v002")
            lp_v2, _ = sequence_target_logps(peft_model, p_ids, t_ids, device=device)

            m_v1 = float(lp_v1.mean().item())
            m_v2 = float(lp_v2.mean().item())
            per_item.append(
                {
                    "clause_id": clause_id,
                    "num_tokens": len(t_ids),
                    "logp_v1": round(m_v1, 6),
                    "logp_v2": round(m_v2, 6),
                    "delta_v2_minus_v1": round(m_v2 - m_v1, 6),
                }
            )

    del peft_model
    del base_model
    if device == "mps" and torch.backends.mps.is_available():
        torch.mps.empty_cache()

    mean_v1 = float(np.mean([x["logp_v1"] for x in per_item]))
    mean_v2 = float(np.mean([x["logp_v2"] for x in per_item]))
    return {
        "num_sequences": len(per_item),
        "mean_logp_v1": round(mean_v1, 6),
        "mean_logp_v2": round(mean_v2, 6),
        "mean_delta_v2_minus_v1": round(mean_v2 - mean_v1, 6),
        "improved_count": sum(1 for x in per_item if x["delta_v2_minus_v1"] > 0.0),
        "per_sequence": per_item,
    }


def verify_gate5_atomic_hotswap_v1_v2_v1(
    *,
    verify_bin: Path = DEFAULT_VERIFY_BIN,
    llama_cpp_dir: Path = DEFAULT_LLAMA_CPP_DIR,
    host_llama_bin_dir: Path = DEFAULT_HOST_LLAMA_BIN_DIR,
    base_gguf: Path = DEFAULT_BASE_GGUF,
    lora_v001_gguf: Path = DEFAULT_V001_GGUF,
    lora_v002_gguf: Path = DEFAULT_V002_GGUF,
    prompt_text: str = DEFAULT_HELDOUT_PROMPTS[0][1],
    hotswap_cycles: int = 5,
    n_gpu_layers: int = 99,
) -> dict[str, Any]:
    """Run real `llama-lora-verify` on both GGUFs AND single-context native `v001 -> v002 -> v001` swap."""
    formatted_prompt = til.build_s2_pro_prompt(prompt_text)

    v1_verify = til.verify_llama_cpp_lora_hotswap(
        verify_bin=verify_bin,
        base_gguf=base_gguf,
        lora_gguf=lora_v001_gguf,
        prompt=formatted_prompt,
        scale=1.0,
        hotswap_iters=10,
        n_gpu_layers=n_gpu_layers,
    )
    v2_verify = til.verify_llama_cpp_lora_hotswap(
        verify_bin=verify_bin,
        base_gguf=base_gguf,
        lora_gguf=lora_v002_gguf,
        prompt=formatted_prompt,
        scale=1.0,
        hotswap_iters=10,
        n_gpu_layers=n_gpu_layers,
    )

    # Real single-context atomic v001 -> v002 -> v001 C++ probe linked directly to host libllama.dylib
    cpp_src = r"""
#include "llama.h"
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <mach/mach.h>
#include <string>
#include <vector>

extern "C" {
int32_t llama_set_adapter_lora(struct llama_context * ctx, struct llama_adapter_lora * adapter, float scale);
int32_t llama_rm_adapter_lora(struct llama_context * ctx, struct llama_adapter_lora * adapter);
}

static double CurrentRssMb() {
    mach_task_basic_info info;
    mach_msg_type_number_t count = MACH_TASK_BASIC_INFO_COUNT;
    if (task_info(mach_task_self(), MACH_TASK_BASIC_INFO, reinterpret_cast<task_info_t>(&info), &count) == KERN_SUCCESS) {
        return static_cast<double>(info.resident_size) / (1024.0 * 1024.0);
    }
    return 0.0;
}

static bool EvalLogits(llama_context * ctx, const std::vector<llama_token> & tokens, int n_vocab, std::vector<float> & out) {
    llama_memory_clear(llama_get_memory(ctx), true);
    llama_batch batch = llama_batch_get_one(const_cast<llama_token *>(tokens.data()), static_cast<int32_t>(tokens.size()));
    if (llama_decode(ctx, batch) != 0) return false;
    const float * logits = llama_get_logits_ith(ctx, static_cast<int32_t>(tokens.size()) - 1);
    if (!logits) return false;
    out.assign(logits, logits + n_vocab);
    return true;
}

int main(int argc, char ** argv) {
    if (argc < 6) return 2;
    const char * base_path = argv[1];
    const char * v1_path = argv[2];
    const char * v2_path = argv[3];
    const char * prompt = argv[4];
    int cycles = std::atoi(argv[5]);
    int ngl = (argc >= 7) ? std::atoi(argv[6]) : 99;

    llama_log_set([](enum ggml_log_level, const char *, void *) {}, nullptr);
    llama_backend_init();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = ngl;
    llama_model * model = llama_model_load_from_file(base_path, mparams);
    if (!model) return 3;

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = 512;
    cparams.n_batch = 512;
    llama_context * ctx = llama_init_from_model(model, cparams);
    if (!ctx) { llama_model_free(model); return 4; }

    const llama_vocab * vocab = llama_model_get_vocab(model);
    int n_vocab = llama_vocab_n_tokens(vocab);
    std::vector<llama_token> tokens(256);
    int n_tok = llama_tokenize(vocab, prompt, static_cast<int32_t>(std::strlen(prompt)), tokens.data(), static_cast<int32_t>(tokens.size()), true, true);
    if (n_tok <= 0) return 5;
    tokens.resize(static_cast<size_t>(n_tok));

    llama_adapter_lora * a1 = llama_adapter_lora_init(model, v1_path);
    llama_adapter_lora * a2 = llama_adapter_lora_init(model, v2_path);
    if (!a1 || !a2) return 6;

    std::vector<float> logits_v1_init, logits_v2, logits_v1_restored;
    int swap_errors = 0;

    if (llama_set_adapter_lora(ctx, a1, 1.0f) != 0 || !EvalLogits(ctx, tokens, n_vocab, logits_v1_init)) swap_errors++;
    if (llama_rm_adapter_lora(ctx, a1) != 0) swap_errors++;
    if (llama_set_adapter_lora(ctx, a2, 1.0f) != 0 || !EvalLogits(ctx, tokens, n_vocab, logits_v2)) swap_errors++;
    if (llama_rm_adapter_lora(ctx, a2) != 0) swap_errors++;
    if (llama_set_adapter_lora(ctx, a1, 1.0f) != 0 || !EvalLogits(ctx, tokens, n_vocab, logits_v1_restored)) swap_errors++;
    if (llama_rm_adapter_lora(ctx, a1) != 0) swap_errors++;

    llama_adapter_lora_free(a1);
    llama_adapter_lora_free(a2);

    // Measure RSS delta across repeated full init -> v1 -> v2 -> v1 -> free cycles
    double rss_before = CurrentRssMb();
    for (int i = 0; i < cycles; ++i) {
        llama_adapter_lora * l1 = llama_adapter_lora_init(model, v1_path);
        llama_adapter_lora * l2 = llama_adapter_lora_init(model, v2_path);
        std::vector<float> tmp;
        if (!l1 || !l2) { swap_errors++; continue; }
        if (llama_set_adapter_lora(ctx, l1, 1.0f) != 0 || !EvalLogits(ctx, tokens, n_vocab, tmp)) swap_errors++;
        if (llama_rm_adapter_lora(ctx, l1) != 0) swap_errors++;
        if (llama_set_adapter_lora(ctx, l2, 1.0f) != 0 || !EvalLogits(ctx, tokens, n_vocab, tmp)) swap_errors++;
        if (llama_rm_adapter_lora(ctx, l2) != 0) swap_errors++;
        if (llama_set_adapter_lora(ctx, l1, 1.0f) != 0 || !EvalLogits(ctx, tokens, n_vocab, tmp)) swap_errors++;
        if (llama_rm_adapter_lora(ctx, l1) != 0) swap_errors++;
        llama_adapter_lora_free(l1);
        llama_adapter_lora_free(l2);
    }
    double rss_after = CurrentRssMb();

    double v1_vs_v2_max = 0.0, v1_vs_v2_sem_l2 = 0.0, v1_restore_max = 0.0;
    for (int i = 0; i < n_vocab; ++i) {
        double d12 = std::fabs(static_cast<double>(logits_v2[i]) - static_cast<double>(logits_v1_init[i]));
        if (d12 > v1_vs_v2_max) v1_vs_v2_max = d12;
        if (i >= 151678 && i <= 155773) v1_vs_v2_sem_l2 += d12 * d12;
        double dr = std::fabs(static_cast<double>(logits_v1_restored[i]) - static_cast<double>(logits_v1_init[i]));
        if (dr > v1_restore_max) v1_restore_max = dr;
    }
    v1_vs_v2_sem_l2 = std::sqrt(v1_vs_v2_sem_l2);

    std::printf(
        "{\"cycles\":%d,\"swap_errors\":%d,\"v1_vs_v2_logit_max_abs_diff\":%.6f,"
        "\"v1_vs_v2_semantic_logit_l2_diff\":%.6f,\"v1_restore_max_abs_diff\":%.6f,"
        "\"rss_before_mb\":%.2f,\"rss_after_mb\":%.2f,\"rss_delta_mb\":%.2f}\n",
        cycles, swap_errors, v1_vs_v2_max, v1_vs_v2_sem_l2, v1_restore_max,
        rss_before, rss_after, rss_after - rss_before
    );

    llama_free(ctx);
    llama_model_free(model);
    llama_backend_free();
    return (swap_errors == 0) ? 0 : 7;
}
"""
    atomic_swap_result: dict[str, Any]
    with tempfile.TemporaryDirectory(prefix="llama_v1_v2_swap_") as tmp_str:
        tmp_dir = Path(tmp_str)
        src_file = tmp_dir / "swap_probe.cc"
        bin_file = tmp_dir / "swap_probe"
        src_file.write_text(cpp_src, encoding="utf-8")
        compile_cmd = [
            "clang++",
            "-std=c++17",
            "-O2",
            str(src_file),
            f"-I{llama_cpp_dir / 'include'}",
            f"-I{llama_cpp_dir / 'ggml' / 'include'}",
            f"-L{host_llama_bin_dir}",
            "-lllama",
            "-lggml",
            f"-Wl,-rpath,{host_llama_bin_dir}",
            "-o",
            str(bin_file),
        ]
        c_proc = subprocess.run(compile_cmd, capture_output=True, text=True)
        if c_proc.returncode != 0:
            raise RuntimeError(
                f"Failed to compile v1->v2->v1 swap probe:\n{c_proc.stderr}"
            )
        r_proc = subprocess.run(
            [
                str(bin_file),
                str(base_gguf),
                str(lora_v001_gguf),
                str(lora_v002_gguf),
                formatted_prompt,
                str(hotswap_cycles),
                str(n_gpu_layers),
            ],
            capture_output=True,
            text=True,
        )
        if r_proc.returncode != 0:
            raise RuntimeError(
                f"v1->v2->v1 swap probe failed (rc={r_proc.returncode}):\n"
                f"STDOUT: {r_proc.stdout}\nSTDERR: {r_proc.stderr}"
            )
        atomic_swap_result = json.loads(r_proc.stdout.strip())

    if int(atomic_swap_result["swap_errors"]) != 0:
        raise RuntimeError(f"Atomic v001->v002->v001 swap reported errors: {atomic_swap_result}")
    if float(atomic_swap_result["v1_vs_v2_logit_max_abs_diff"]) <= 1e-4:
        raise RuntimeError(f"v001 and v002 produced identical logits in llama.cpp: {atomic_swap_result}")
    if float(atomic_swap_result["v1_restore_max_abs_diff"]) > 1e-3:
        raise RuntimeError(f"v001->v002->v001 restore drifted: {atomic_swap_result}")

    return {
        "base_gguf": str(base_gguf),
        "lora_v001_gguf": str(lora_v001_gguf),
        "lora_v002_gguf": str(lora_v002_gguf),
        "llama_lora_verify_v001": v1_verify,
        "llama_lora_verify_v002": v2_verify,
        "atomic_swap_v1_v2_v1": atomic_swap_result,
        "distinct_logit_deltas_confirmed": bool(
            float(atomic_swap_result["v1_vs_v2_logit_max_abs_diff"]) > 1e-4
        ),
        "zero_errors_zero_leak_confirmed": bool(
            int(v1_verify["hotswap_errors"]) == 0
            and int(v2_verify["hotswap_errors"]) == 0
            and int(atomic_swap_result["swap_errors"]) == 0
            and float(atomic_swap_result["v1_restore_max_abs_diff"]) <= 1e-3
        ),
    }


def run_phase3_ab_and_hotswap(
    *,
    s2_pro_hf_dir: Path = DEFAULT_S2_PRO_HF_DIR,
    base_gguf: Path = DEFAULT_BASE_GGUF,
    codec_pth: Path = DEFAULT_CODEC_PTH,
    codec_tflite: Path = DEFAULT_CODEC_TFLITE,
    lora_v001_dir: Path = DEFAULT_V001_LORA_DIR,
    lora_v002_dir: Path = DEFAULT_V002_LORA_DIR,
    lora_v001_gguf: Path = DEFAULT_V001_GGUF,
    lora_v002_gguf: Path = DEFAULT_V002_GGUF,
    master_centroid_path: Path = DEFAULT_MASTER_CENTROID,
    wespeaker_onnx: Path = DEFAULT_WESPEAKER_ONNX,
    dnsmos_onnx: Path = DEFAULT_DNSMOS_ONNX,
    whisper_bin: Path = DEFAULT_WHISPER_BIN,
    whisper_model: Path = DEFAULT_WHISPER_MODEL,
    verify_bin: Path = DEFAULT_VERIFY_BIN,
    llama_cpp_dir: Path = DEFAULT_LLAMA_CPP_DIR,
    host_llama_bin_dir: Path = DEFAULT_HOST_LLAMA_BIN_DIR,
    work_dir: Path = DEFAULT_V002_WORK_DIR,
    report_path: Path = DEFAULT_V002_REPORT,
    prompts: Sequence[tuple[str, str]] = DEFAULT_HELDOUT_PROMPTS,
    seeds: Sequence[int] = DEFAULT_HELDOUT_SEEDS,
    device: str = "mps",
) -> dict[str, Any]:
    """Phase 3: Rigorous held-out A/B (`v001` vs `v002`) + chosen log-prob delta + Gate 5 swap."""
    work_dir = Path(work_dir)
    ab_dir = work_dir / "phase3_ab"
    ab_dir.mkdir(parents=True, exist_ok=True)

    print("=== [Phase 3] Encoding shared clean reference turns for apples-to-apples A/B ===", flush=True)
    ref_turns, ref_meta = encode_clean_reference_turns(
        DEFAULT_CLEAN_REFERENCE_TURNS, codec_pth=codec_pth, device=device
    )

    master_centroid = sar.load_master_centroid_bin(master_centroid_path)
    wespeaker = eva.WeSpeakerEngine(wespeaker_onnx)
    dnsmos = eva.DnsmosP808Engine(dnsmos_onnx)

    print("=== [Phase 3] Evaluating voice_lora_v001 on 3 held-out prompts ===", flush=True)
    eval_v1 = _evaluate_adapter_on_heldout(
        label="v001",
        lora_dir=lora_v001_dir,
        ref_turns=ref_turns,
        prompts=prompts,
        seeds=seeds,
        s2_pro_hf_dir=s2_pro_hf_dir,
        codec_tflite=codec_tflite,
        master_centroid=master_centroid,
        wespeaker=wespeaker,
        dnsmos=dnsmos,
        whisper_bin=whisper_bin,
        whisper_model=whisper_model,
        out_dir=ab_dir / "v001",
        device=device,
    )

    print("=== [Phase 3] Evaluating voice_lora_v002 on EXACT SAME 3 held-out prompts & seeds ===", flush=True)
    eval_v2 = _evaluate_adapter_on_heldout(
        label="v002",
        lora_dir=lora_v002_dir,
        ref_turns=ref_turns,
        prompts=prompts,
        seeds=seeds,
        s2_pro_hf_dir=s2_pro_hf_dir,
        codec_tflite=codec_tflite,
        master_centroid=master_centroid,
        wespeaker=wespeaker,
        dnsmos=dnsmos,
        whisper_bin=whisper_bin,
        whisper_model=whisper_model,
        out_dir=ab_dir / "v002",
        device=device,
    )

    # Optional combined v002 stack evaluation: re-decode the exact same v002 held-out codes
    # through bundles/v002/codec_decoder_fp16.tflite (if exported by dac-finetune-v2)
    dac_v2_path = DEFAULT_V002_BUNDLE_DIR / "codec_decoder_fp16.tflite"
    eval_v2_full_stack: dict[str, Any] | None = None
    deltas_full_stack_all: dict[str, Any] | None = None
    deltas_full_stack_best: dict[str, Any] | None = None
    if dac_v2_path.is_file() and dac_v2_path.resolve() != Path(codec_tflite).resolve():
        print(
            f"=== [Phase 3] Bonus Full v002 Stack Eval: decoding exact v002 codes via {dac_v2_path.name} ===",
            flush=True,
        )
        v2_codes_list = [np.load(t["codes_npy"]) for t in eval_v2["takes"]]
        decoded_v2_dac = s2_pro_infer.decode_codes_batch_tflite(
            v2_codes_list, tflite_path=dac_v2_path
        )
        full_dir = ab_dir / "v002_with_dac_v002"
        full_dir.mkdir(parents=True, exist_ok=True)
        full_takes: list[dict[str, Any]] = []
        full_by_prompt: dict[str, list[dict[str, Any]]] = {}
        for orig_take, (pcm44k, dec_meta) in zip(eval_v2["takes"], decoded_v2_dac):
            wav_p = full_dir / f"v002_dacv2_{orig_take['clause_id']}_seed{orig_take['seed']}.wav"
            sf.write(str(wav_p), np.clip(pcm44k, -1.0, 1.0), sar.SAMPLE_RATE_HZ, subtype="PCM_16")
            gates = sar.evaluate_candidate_4gates(
                pcm44k,
                orig_take["raw_text"],
                master_centroid=master_centroid,
                wespeaker=wespeaker,
                dnsmos=dnsmos,
                whisper_bin=whisper_bin,
                whisper_model=whisper_model,
            )
            rew = score_rollout_reward(gates, human_chosen=False)
            rec = {
                **orig_take,
                "wav_path": str(wav_p),
                "decode_ms": dec_meta["decode_ms"],
                "gates": gates,
                "spec_reward": round(rew, 5),
            }
            full_takes.append(rec)
            full_by_prompt.setdefault(orig_take["clause_id"], []).append(rec)
            print(
                f"  [Full v002+DACv2] {orig_take['clause_id']} seed={orig_take['seed']}: "
                f"CER={gates['cer']:.4f} WeSpeaker={gates['wespeaker_cosine']:.4f} "
                f"DNSMOS={gates['dnsmos_p808']:.3f} cadence={gates['syllables_per_sec']:.2f} "
                f"tail={gates['tail_rms_dbfs']:.1f} rew={rew:+.4f}",
                flush=True,
            )
        full_best = [
            max(cands, key=lambda r: compute_rollout_selection_score(r["gates"]))
            for _, cands in sorted(full_by_prompt.items())
        ]

        def _agg_full(items: Sequence[dict[str, Any]]) -> dict[str, Any]:
            return {
                "count": len(items),
                "cer_zero_count": sum(1 for x in items if float(x["gates"]["cer"]) == 0.0),
                "mean_cer": round(float(np.mean([x["gates"]["cer"] for x in items])), 5),
                "mean_wespeaker": round(
                    float(np.mean([x["gates"]["wespeaker_cosine"] for x in items])), 5
                ),
                "max_wespeaker": round(
                    float(np.max([x["gates"]["wespeaker_cosine"] for x in items])), 5
                ),
                "mean_dnsmos": round(
                    float(np.mean([x["gates"]["dnsmos_p808"] for x in items])), 4
                ),
                "max_dnsmos": round(
                    float(np.max([x["gates"]["dnsmos_p808"] for x in items])), 4
                ),
                "mean_cadence": round(
                    float(np.mean([x["gates"]["syllables_per_sec"] for x in items])), 3
                ),
                "in_band_cadence_count": sum(
                    1
                    for x in items
                    if sar.GATE_MIN_SYLLABLES_PER_SEC
                    <= float(x["gates"]["syllables_per_sec"])
                    <= sar.GATE_MAX_SYLLABLES_PER_SEC
                ),
                "tail_pass_count": sum(
                    1
                    for x in items
                    if float(x["gates"]["tail_rms_dbfs"]) <= sar.GATE_MAX_TAIL_RMS_DBFS
                ),
                "mean_spec_reward": round(float(np.mean([x["spec_reward"] for x in items])), 5),
            }

        sf_all = _agg_full(full_takes)
        sf_best = _agg_full(full_best)
        eval_v2_full_stack = {
            "label": "v002_lora_plus_v002_dac",
            "codec_tflite": str(dac_v2_path),
            "all_takes_summary": sf_all,
            "best_of_n_summary": sf_best,
            "best_per_prompt": [
                {
                    "clause_id": b["clause_id"],
                    "seed": b["seed"],
                    "gates": b["gates"],
                    "spec_reward": b["spec_reward"],
                    "wav_path": b["wav_path"],
                }
                for b in full_best
            ],
            "takes": full_takes,
        }
        deltas_full_stack_all = {
            "mean_cer_delta": round(sf_all["mean_cer"] - eval_v1["all_takes_summary"]["mean_cer"], 5),
            "mean_wespeaker_delta": round(
                sf_all["mean_wespeaker"] - eval_v1["all_takes_summary"]["mean_wespeaker"], 5
            ),
            "mean_dnsmos_delta": round(
                sf_all["mean_dnsmos"] - eval_v1["all_takes_summary"]["mean_dnsmos"], 4
            ),
            "mean_cadence_delta": round(
                sf_all["mean_cadence"] - eval_v1["all_takes_summary"]["mean_cadence"], 3
            ),
            "mean_spec_reward_delta": round(
                sf_all["mean_spec_reward"] - eval_v1["all_takes_summary"]["mean_spec_reward"], 5
            ),
        }
        deltas_full_stack_best = {
            "mean_cer_delta": round(
                sf_best["mean_cer"] - eval_v1["best_of_n_summary"]["mean_cer"], 5
            ),
            "mean_wespeaker_delta": round(
                sf_best["mean_wespeaker"] - eval_v1["best_of_n_summary"]["mean_wespeaker"], 5
            ),
            "mean_dnsmos_delta": round(
                sf_best["mean_dnsmos"] - eval_v1["best_of_n_summary"]["mean_dnsmos"], 4
            ),
            "mean_cadence_delta": round(
                sf_best["mean_cadence"] - eval_v1["best_of_n_summary"]["mean_cadence"], 3
            ),
            "mean_spec_reward_delta": round(
                sf_best["mean_spec_reward"] - eval_v1["best_of_n_summary"]["mean_spec_reward"], 5
            ),
        }

    # Measure exact chosen-token log-prob delta (`logp_v2 - logp_v1`) on Phase 1 chosen rollouts
    # AND on held-out winning takes
    p1_path = work_dir / "phase1_rollouts.json"
    p1_chosen_pairs: list[tuple[str, str, Sequence[int]]] = []
    if p1_path.is_file():
        p1_data = json.loads(p1_path.read_text(encoding="utf-8"))
        for rec in p1_data.get("preference_records", []):
            p1_chosen_pairs.append((rec["clause_id"], rec["raw_text"], rec["chosen_tokens"]))

    heldout_best_pairs: list[tuple[str, str, Sequence[int]]] = []
    for cid, text in prompts:
        all_for_p = [t for t in (eval_v1["takes"] + eval_v2["takes"]) if t["clause_id"] == cid]
        best_take = max(all_for_p, key=lambda r: compute_rollout_selection_score(r["gates"]))
        heldout_best_pairs.append((cid, text, best_take["semantic_tokens"]))

    print("=== [Phase 3] Measuring exact chosen-token logp_v2 - logp_v1 deltas ===", flush=True)
    logp_delta_on_policy = (
        measure_chosen_logp_delta_v1_vs_v2(
            s2_pro_hf_dir=s2_pro_hf_dir,
            lora_v001_dir=lora_v001_dir,
            lora_v002_dir=lora_v002_dir,
            eval_pairs=p1_chosen_pairs,
            device=device,
        )
        if p1_chosen_pairs
        else None
    )
    logp_delta_heldout = measure_chosen_logp_delta_v1_vs_v2(
        s2_pro_hf_dir=s2_pro_hf_dir,
        lora_v001_dir=lora_v001_dir,
        lora_v002_dir=lora_v002_dir,
        eval_pairs=heldout_best_pairs,
        device=device,
    )

    print(
        "=== [Phase 3] Verifying Gate 5 live atomic hot-swap v001 -> v002 -> v001 on s2-pro.Q4_K_M.gguf ===",
        flush=True,
    )
    gate5_hotswap = verify_gate5_atomic_hotswap_v1_v2_v1(
        verify_bin=verify_bin,
        llama_cpp_dir=llama_cpp_dir,
        host_llama_bin_dir=host_llama_bin_dir,
        base_gguf=base_gguf,
        lora_v001_gguf=lora_v001_gguf,
        lora_v002_gguf=lora_v002_gguf,
        prompt_text=prompts[0][1],
        hotswap_cycles=5,
        n_gpu_layers=99,
    )
    print(
        f"  [gate5] v1_vs_v2_max_diff={gate5_hotswap['atomic_swap_v1_v2_v1']['v1_vs_v2_logit_max_abs_diff']:.4f} "
        f"v1_vs_v2_sem_l2={gate5_hotswap['atomic_swap_v1_v2_v1']['v1_vs_v2_semantic_logit_l2_diff']:.4f} "
        f"restore_diff={gate5_hotswap['atomic_swap_v1_v2_v1']['v1_restore_max_abs_diff']:.6f} "
        f"swap_errors={gate5_hotswap['atomic_swap_v1_v2_v1']['swap_errors']} "
        f"rss_delta={gate5_hotswap['atomic_swap_v1_v2_v1']['rss_delta_mb']:+.2f} MB",
        flush=True,
    )

    s1_all = eval_v1["all_takes_summary"]
    s2_all = eval_v2["all_takes_summary"]
    s1_best = eval_v1["best_of_n_summary"]
    s2_best = eval_v2["best_of_n_summary"]

    deltas_all = {
        "mean_cer_delta": round(s2_all["mean_cer"] - s1_all["mean_cer"], 5),
        "mean_wespeaker_delta": round(s2_all["mean_wespeaker"] - s1_all["mean_wespeaker"], 5),
        "mean_dnsmos_delta": round(s2_all["mean_dnsmos"] - s1_all["mean_dnsmos"], 4),
        "mean_cadence_delta": round(s2_all["mean_cadence"] - s1_all["mean_cadence"], 3),
        "mean_spec_reward_delta": round(
            s2_all["mean_spec_reward"] - s1_all["mean_spec_reward"], 5
        ),
    }
    deltas_best = {
        "mean_cer_delta": round(s2_best["mean_cer"] - s1_best["mean_cer"], 5),
        "mean_wespeaker_delta": round(s2_best["mean_wespeaker"] - s1_best["mean_wespeaker"], 5),
        "mean_dnsmos_delta": round(s2_best["mean_dnsmos"] - s1_best["mean_dnsmos"], 4),
        "mean_cadence_delta": round(s2_best["mean_cadence"] - s1_best["mean_cadence"], 3),
        "mean_spec_reward_delta": round(
            s2_best["mean_spec_reward"] - s1_best["mean_spec_reward"], 5
        ),
    }

    phase1_summary = (
        json.loads(p1_path.read_text(encoding="utf-8"))["summary"]
        if p1_path.is_file()
        else None
    )
    phase1_ota = (
        json.loads(p1_path.read_text(encoding="utf-8")).get("ota_upload")
        if p1_path.is_file()
        else None
    )
    p2_path = work_dir / "phase2_training_report.json"
    phase2_data = json.loads(p2_path.read_text(encoding="utf-8")) if p2_path.is_file() else None

    full_report = {
        "generated_at_utc": datetime.datetime.now(datetime.timezone.utc)
        .replace(microsecond=0)
        .isoformat()
        .replace("+00:00", "Z"),
        "schema_version": 1,
        "iteration": "v001_to_v002_grpo_opsd",
        "references": ref_meta,
        "heldout_prompts": [{"clause_id": cid, "text": txt} for cid, txt in prompts],
        "heldout_seeds": [int(s) for s in seeds],
        "codec_tflite": str(codec_tflite),
        "phase1_rollouts_summary": phase1_summary,
        "phase1_ota_upload": phase1_ota,
        "phase2_training": phase2_data,
        "heldout_ab": {
            "v001": eval_v1,
            "v002": eval_v2,
            "v002_with_dac_v002": eval_v2_full_stack,
            "delta_v2_minus_v1_all_takes": deltas_all,
            "delta_v2_minus_v1_best_of_n": deltas_best,
            "delta_full_v002_stack_minus_v001_all_takes": deltas_full_stack_all,
            "delta_full_v002_stack_minus_v001_best_of_n": deltas_full_stack_best,
        },
        "chosen_logp_delta": {
            "on_policy_chosen_rollouts": logp_delta_on_policy,
            "heldout_best_takes": logp_delta_heldout,
        },
        "gate5_live_hotswap": gate5_hotswap,
    }

    report_path = Path(report_path)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(full_report, indent=2) + "\n", encoding="utf-8")
    print(f"=== [Phase 3] Wrote complete report: {report_path} ===", flush=True)
    return full_report


# ---------------------------------------------------------------------------
# CLI Entrypoint
# ---------------------------------------------------------------------------


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run Closed-Loop OPSD + GRPO/DPO LoRA v001 -> v002 iteration on Apple Silicon."
    )
    parser.add_argument(
        "--phase",
        type=str,
        default="all",
        choices=["all", "1", "2", "3"],
        help="Which phase(s) to execute.",
    )
    parser.add_argument("--device", type=str, default="mps")
    parser.add_argument("--ota-url", type=str, default=DEFAULT_OTA_SERVER_URL)
    parser.add_argument("--preferences-jsonl", type=Path, default=DEFAULT_PREFERENCES_JSONL)
    parser.add_argument("--work-dir", type=Path, default=DEFAULT_V002_WORK_DIR)
    parser.add_argument("--output-gguf", type=Path, default=DEFAULT_V002_GGUF)
    parser.add_argument("--report-path", type=Path, default=DEFAULT_V002_REPORT)
    parser.add_argument("--codec-tflite", type=Path, default=DEFAULT_CODEC_TFLITE)
    parser.add_argument("--beta", type=float, default=DEFAULT_KL_COEF)
    parser.add_argument("--kl-coef", type=float, default=DEFAULT_KL_COEF)
    parser.add_argument("--lr", type=float, default=1.2e-5)
    parser.add_argument("--steps", type=int, default=12)
    parser.add_argument("--w-dpo", type=float, default=0.65)
    parser.add_argument("--w-gate-fail", type=float, default=1.0)
    parser.add_argument("--w-grpo", type=float, default=0.30)
    parser.add_argument("--w-opsd-kl", type=float, default=0.35)
    parser.add_argument("--w-chosen-nll", type=float, default=0.25)
    parser.add_argument("--w-purity-anchor", type=float, default=1.10)
    parser.add_argument("--skip-upload", action="store_true")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    if args.device == "mps" and not torch.backends.mps.is_available():
        raise RuntimeError("MPS requested but torch.backends.mps.is_available() is False")

    if args.phase in ("all", "1"):
        run_phase1_rollouts_and_upload(
            codec_tflite=args.codec_tflite,
            work_dir=args.work_dir,
            ota_server_url=args.ota_url,
            preferences_jsonl=args.preferences_jsonl,
            device=args.device,
            upload_live=not args.skip_upload,
        )

    if args.phase in ("all", "2"):
        run_phase2_grpo_opsd_training(
            preferences_jsonl=args.preferences_jsonl,
            work_dir=args.work_dir,
            output_gguf=args.output_gguf,
            device=args.device,
            beta=args.beta,
            kl_coef=args.kl_coef,
            learning_rate=args.lr,
            total_steps=args.steps,
            w_dpo=args.w_dpo,
            w_gate_fail=args.w_gate_fail,
            w_grpo=args.w_grpo,
            w_opsd_kl=args.w_opsd_kl,
            w_chosen_nll=args.w_chosen_nll,
            w_purity_anchor=args.w_purity_anchor,
        )

    if args.phase in ("all", "3"):
        run_phase3_ab_and_hotswap(
            codec_tflite=args.codec_tflite,
            lora_v002_dir=args.work_dir / "peft_lora_adapter",
            lora_v002_gguf=args.output_gguf,
            work_dir=args.work_dir,
            report_path=args.report_path,
            device=args.device,
        )

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
