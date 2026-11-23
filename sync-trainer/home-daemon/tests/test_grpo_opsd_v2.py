"""Unit and real-artifact verification tests for Closed-Loop OPSD + GRPO/DPO LoRA v002."""

from __future__ import annotations

import json
import sys
import threading
from pathlib import Path
from typing import Any

import numpy as np
import pytest
import soundfile as sf
import torch

HOME_DAEMON_DIR = Path(__file__).resolve().parents[1]
REPO_ROOT = HOME_DAEMON_DIR.parents[1]
if str(HOME_DAEMON_DIR) not in sys.path:
    sys.path.insert(0, str(HOME_DAEMON_DIR))

import run_grpo_opsd_v2 as v2  # noqa: E402
import ota_server  # noqa: E402
import tinker_voice_daemon as tvd  # noqa: E402
import train_initial_lora as til  # noqa: E402

from peft import LoraConfig, TaskType, get_peft_model  # noqa: E402
from transformers import Qwen3Config, Qwen3ForCausalLM  # noqa: E402

LLAMA_CPP_DIR = Path("/Users/mac/llama.cpp")
VOICE_LORA_V001 = Path("/Users/mac/Downloads/voicestudio-models/bundles/v001/voice_lora_v001.gguf")
VOICE_LORA_V002 = Path("/Users/mac/Downloads/voicestudio-models/bundles/v002/voice_lora_v002.gguf")
GRPO_V2_REPORT = Path("/Users/mac/Downloads/voicestudio-models/bundles/v002/grpo_v2_report.json")
PREFERENCES_JSONL = Path("/Users/mac/Downloads/voicestudio-models/preferences.jsonl")


# ---------------------------------------------------------------------------
# Pure Unit Tests (Fast, Deterministic, CPU-Only)
# ---------------------------------------------------------------------------


def test_normalize_semantic_vocab_tokens() -> None:
    raw_codes = [0, 42, 4095]
    vocab_ids = v2.normalize_semantic_vocab_tokens(raw_codes)
    assert vocab_ids == [
        til.SEMANTIC_START_TOKEN_ID,
        til.SEMANTIC_START_TOKEN_ID + 42,
        til.SEMANTIC_END_TOKEN_ID,
    ]
    # Idempotent on already-shifted vocab IDs
    assert v2.normalize_semantic_vocab_tokens(vocab_ids) == vocab_ids

    with pytest.raises(ValueError):
        v2.normalize_semantic_vocab_tokens([-1])
    with pytest.raises(ValueError):
        v2.normalize_semantic_vocab_tokens([4096])
    with pytest.raises(ValueError):
        v2.normalize_semantic_vocab_tokens([til.SEMANTIC_END_TOKEN_ID + 1])


def test_score_rollout_reward_matches_spec_formula() -> None:
    # Hard pronunciation gate: any CER > 0 -> -2.0 regardless of WeSpeaker / DNSMOS / human_chosen
    bad_cer = {
        "cer": 0.04,
        "wespeaker_cosine": 0.95,
        "dnsmos_p808": 4.5,
        "tail_rms_dbfs": -55.0,
        "syllables_per_sec": 5.0,
    }
    assert v2.score_rollout_reward(bad_cer, human_chosen=False) == pytest.approx(-2.0)
    assert v2.score_rollout_reward(bad_cer, human_chosen=True) == pytest.approx(-2.0)

    # Clean pronunciation: 1.5 * sim + 0.5 * (dnsmos / 5.0) + (1.0 if human_chosen else 0.0)
    clean = {
        "cer": 0.0,
        "wespeaker_cosine": 0.84,
        "dnsmos_p808": 4.0,
        "tail_rms_dbfs": -50.0,
        "syllables_per_sec": 4.8,
    }
    expected = 1.5 * 0.84 + 0.5 * (4.0 / 5.0)
    assert v2.score_rollout_reward(clean, human_chosen=False) == pytest.approx(expected)
    assert v2.score_rollout_reward(clean, human_chosen=True) == pytest.approx(expected + 1.0)


def test_build_preference_record_conforms_to_frozen_schema() -> None:
    schema_path = REPO_ROOT / "docs" / "swarm" / "schemas" / "preference_tuple.schema.json"
    schema = json.loads(schema_path.read_text(encoding="utf-8"))

    rollouts = [
        {
            "seed": 951001,
            "semantic_tokens": [til.SEMANTIC_START_TOKEN_ID + 10, til.SEMANTIC_START_TOKEN_ID + 20],
            "gates": {
                "cer": 0.0,
                "wespeaker_cosine": 0.85,
                "dnsmos_p808": 4.05,
                "tail_rms_dbfs": -51.0,
                "syllables_per_sec": 4.9,
                "strict_gate_failures": ["SPEAKER_SIMILARITY_LOW", "DNSMOS_LOW"],
            },
        },
        {
            "seed": 951002,
            "semantic_tokens": [til.SEMANTIC_START_TOKEN_ID + 11, til.SEMANTIC_START_TOKEN_ID + 21],
            "gates": {
                "cer": 0.0,
                "wespeaker_cosine": 0.74,
                "dnsmos_p808": 3.60,
                "tail_rms_dbfs": -48.0,
                "syllables_per_sec": 3.7,
                "strict_gate_failures": ["SPEAKER_SIMILARITY_LOW", "DNSMOS_LOW", "CADENCE_OUT_OF_BAND"],
            },
        },
        {
            "seed": 951003,
            "semantic_tokens": [til.SEMANTIC_START_TOKEN_ID + 12, til.SEMANTIC_START_TOKEN_ID + 22],
            "gates": {
                "cer": 0.125,
                "wespeaker_cosine": 0.88,
                "dnsmos_p808": 4.15,
                "tail_rms_dbfs": -52.0,
                "syllables_per_sec": 5.1,
                "strict_gate_failures": ["CER_EXCEEDED"],
            },
        },
    ]

    rec = v2.build_preference_record_from_rollouts(
        clause_id="rollout_test_01",
        raw_text="Pixel runtime swaps Aircheck LoRA fast.",
        rollouts=rollouts,
        lora_version=1,
        lexicon={"Pixel": "p ˈɪ k s ə l", "Aircheck": "ˈɛɚ tʃ ɛ k"},
        timestamp=1795450000,
    )

    assert set(rec.keys()) == set(schema["required"])
    assert rec["user_action"] == "TAKE_SWAP"
    assert rec["user_gap_scale"] == pytest.approx(1.0)
    assert rec["chosen_tokens"] == rollouts[0]["semantic_tokens"]
    assert rec["rejected_tokens"] == rollouts[2]["semantic_tokens"]
    assert "Aircheck:" in rec["normalized_phonemes"]
    assert "Pixel:" in rec["normalized_phonemes"]
    # CER > 0 rollout MUST be logged with exact CER and reason="CER_EXCEEDED"
    cer_fails = [gf for gf in rec["gate_failures"] if gf["cer"] > 0.0]
    assert len(cer_fails) == 1
    assert cer_fails[0]["seed"] == 951003
    assert cer_fails[0]["reason"] == "CER_EXCEEDED"
    # Strictly passes frozen daemon validator
    validated = tvd.validate_preference_record(rec)
    assert validated.clause_id == "rollout_test_01"


def test_upload_preferences_to_live_local_ota_server(tmp_path: Path) -> None:
    bundles_dir = tmp_path / "bundles"
    bundles_dir.mkdir()
    prefs_path = tmp_path / "preferences.jsonl"

    srv = ota_server.create_ota_server(
        host="127.0.0.1",
        port=0,
        bundles_root=bundles_dir,
        preferences_path=prefs_path,
        quiet=True,
    )
    port = srv.server_address[1]
    thread = threading.Thread(target=srv.serve_forever, daemon=True)
    thread.start()
    try:
        records = [
            {
                "timestamp": 1795450100 + idx,
                "clause_id": f"c_live_{idx:02d}",
                "lora_version": 1,
                "raw_text": f"Live OTA preference upload test sentence {idx}.",
                "normalized_phonemes": "t ˈɛ s t",
                "user_action": "TAKE_SWAP",
                "user_gap_scale": 1.0,
                "chosen_tokens": [til.SEMANTIC_START_TOKEN_ID + idx, til.SEMANTIC_START_TOKEN_ID + 5],
                "rejected_tokens": [til.SEMANTIC_START_TOKEN_ID + idx, til.SEMANTIC_START_TOKEN_ID + 9],
                "gate_failures": [
                    {
                        "seed": 951004 + idx,
                        "cer": 0.05,
                        "reason": "CER_EXCEEDED",
                        "tokens": [til.SEMANTIC_START_TOKEN_ID + 7, til.SEMANTIC_START_TOKEN_ID + 8],
                    }
                ],
            }
            for idx in range(3)
        ]

        res = v2.upload_preferences_to_ota_server(
            records,
            ota_server_url=f"http://127.0.0.1:{port}",
            preferences_path=prefs_path,
        )
        assert res["http_status"] == 200
        assert res["uploaded_count"] == 3
        assert res["preferences_lines_before"] == 0
        assert res["preferences_lines_after"] == 3
        reloaded = tvd.load_preferences_jsonl(prefs_path)
        assert len(reloaded) == 3
        assert [r.clause_id for r in reloaded] == ["c_live_00", "c_live_01", "c_live_02"]
    finally:
        srv.shutdown()
        srv.server_close()
        thread.join(timeout=2.0)


def test_filter_high_purity_manifest_clips(tmp_path: Path) -> None:
    wav_a = tmp_path / "a.wav"
    wav_b = tmp_path / "b.wav"
    wav_c = tmp_path / "c.wav"
    for w in (wav_a, wav_b, wav_c):
        sf.write(str(w), np.zeros(44100 * 3, dtype=np.float32), 44100)

    manifest = tmp_path / "manifest.jsonl"
    rows = [
        {
            "id": "clean_best",
            "wav_44k": str(wav_a),
            "duration_sec": 3.0,
            "transcript": "High WeSpeaker and high DNSMOS clip.",
            "wespeaker_cos": 0.89,
            "dnsmos_p808": 3.92,
        },
        {
            "id": "noisy_low_dnsmos",
            "wav_44k": str(wav_b),
            "duration_sec": 3.0,
            "transcript": "Noisy clip that should be filtered out.",
            "wespeaker_cos": 0.86,
            "dnsmos_p808": 3.10,
        },
        {
            "id": "low_wespeaker",
            "wav_44k": str(wav_c),
            "duration_sec": 3.0,
            "transcript": "Low speaker similarity clip.",
            "wespeaker_cos": 0.71,
            "dnsmos_p808": 3.95,
        },
    ]
    manifest.write_text("\n".join(json.dumps(r) for r in rows) + "\n", encoding="utf-8")

    filtered = v2.filter_high_purity_manifest_clips(
        manifest, min_wespeaker_cos=0.78, min_dnsmos_p808=3.50
    )
    assert len(filtered) == 1
    assert filtered[0]["clip_id"] == "clean_best"
    assert filtered[0]["master_wespeaker_cos"] == pytest.approx(0.89)
    assert filtered[0]["dnsmos_p808"] == pytest.approx(3.92)


def test_dpo_grpo_opsd_losses_and_real_peft_gradient_step() -> None:
    """Verify exact DPO, GRPO+k3-KL, and OPSD top-K KL math improve chosen logp & margin."""
    torch.manual_seed(7)
    cfg = Qwen3Config(
        vocab_size=64,
        hidden_size=32,
        intermediate_size=64,
        num_hidden_layers=2,
        num_attention_heads=4,
        num_key_value_heads=2,
        head_dim=8,
        max_position_embeddings=64,
        tie_word_embeddings=True,
        use_cache=False,
    )
    base = Qwen3ForCausalLM(cfg)
    lora_cfg = LoraConfig(
        r=4,
        lora_alpha=8,
        lora_dropout=0.0,
        bias="none",
        task_type=TaskType.CAUSAL_LM,
        target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"],
    )
    model = get_peft_model(base, lora_cfg)

    prompt_ids = [1, 2, 3, 4]
    chosen_ids = [10, 11, 12, 13, 14]
    rejected_ids = [20, 21, 22, 23, 24]
    fail_ids = [30, 31, 32, 33]

    with torch.no_grad():
        with model.disable_adapter():
            ref_w, _ = v2.sequence_target_logps(model, prompt_ids, chosen_ids, device="cpu")
            ref_l, _ = v2.sequence_target_logps(model, prompt_ids, rejected_ids, device="cpu")
            ref_f, _ = v2.sequence_target_logps(model, prompt_ids, fail_ids, device="cpu")
        old_w, _ = v2.sequence_target_logps(model, prompt_ids, chosen_ids, device="cpu")
        old_l, _ = v2.sequence_target_logps(model, prompt_ids, rejected_ids, device="cpu")
        old_f, _ = v2.sequence_target_logps(model, prompt_ids, fail_ids, device="cpu")

        # Synthetic teacher distribution sharply preferring chosen_ids
        teacher_idx = torch.stack(
            [torch.tensor([tid, (tid + 1) % 64, (tid + 2) % 64, (tid + 3) % 64]) for tid in chosen_ids]
        )
        teacher_lp = torch.log_softmax(torch.tensor([[4.0, 0.5, 0.2, 0.1]] * len(chosen_ids)), dim=-1)

    _, rw0, rl0 = v2.compute_dpo_preference_loss(old_w, old_l, ref_w, ref_l, beta=0.04)
    margin_before = float((rw0 - rl0).item())
    chosen_lp_before = float(old_w.mean().item())

    opt = torch.optim.AdamW([p for p in model.parameters() if p.requires_grad], lr=5e-3)
    for _ in range(8):
        opt.zero_grad(set_to_none=True)
        pol_w, active_logits = v2.sequence_target_logps(
            model, prompt_ids, chosen_ids, device="cpu", return_active_logits=True
        )
        pol_l, _ = v2.sequence_target_logps(model, prompt_ids, rejected_ids, device="cpu")
        pol_f, _ = v2.sequence_target_logps(model, prompt_ids, fail_ids, device="cpu")

        dpo_loss, _, _ = v2.compute_dpo_preference_loss(pol_w, pol_l, ref_w, ref_l, beta=0.04)
        gf_loss, _, _ = v2.compute_dpo_preference_loss(pol_w, pol_f, ref_w, ref_f, beta=0.04)
        grpo_loss, grpo_stats = v2.compute_grpo_group_loss(
            [pol_w, pol_l, pol_f],
            [old_w, old_l, old_f],
            [ref_w, ref_l, ref_f],
            [1.75, 1.35, -2.0],
            kl_coef=0.04,
        )
        assert active_logits is not None
        opsd_kl = v2.compute_opsd_topk_kl_loss(active_logits, teacher_idx, teacher_lp)
        assert grpo_stats["grpo_kl_k3"] >= -1e-6

        loss = dpo_loss + 1.5 * gf_loss + 0.4 * grpo_loss + 0.3 * opsd_kl + 0.35 * (-pol_w.mean())
        assert torch.isfinite(loss)
        loss.backward()
        opt.step()

    with torch.no_grad():
        pol_w_after, _ = v2.sequence_target_logps(model, prompt_ids, chosen_ids, device="cpu")
        pol_l_after, _ = v2.sequence_target_logps(model, prompt_ids, rejected_ids, device="cpu")
        _, rw1, rl1 = v2.compute_dpo_preference_loss(pol_w_after, pol_l_after, ref_w, ref_l, beta=0.04)

    assert float(pol_w_after.mean().item()) > chosen_lp_before
    assert float((rw1 - rl1).item()) > margin_before


def test_rollout_and_heldout_prompts_are_strictly_disjoint() -> None:
    rollout_texts = {txt.strip().lower() for _, txt in v2.DEFAULT_ROLLOUT_PROMPTS}
    heldout_texts = {txt.strip().lower() for _, txt in v2.DEFAULT_HELDOUT_PROMPTS}
    assert len(rollout_texts) >= 6
    assert len(heldout_texts) == 3
    assert rollout_texts.isdisjoint(heldout_texts)
    assert any("bare-metal right on the pixel" in t for t in heldout_texts)


# ---------------------------------------------------------------------------
# Real-Artifact Verification Tests (v002 GGUF, Live preferences.jsonl, Report)
# ---------------------------------------------------------------------------


@pytest.mark.skipif(
    not PREFERENCES_JSONL.is_file(),
    reason="Live preferences.jsonl not yet generated",
)
def test_live_preferences_jsonl_strictly_valid() -> None:
    records = tvd.load_preferences_jsonl(PREFERENCES_JSONL)
    assert len(records) >= 6, f"Expected >= 6 live preference tuples, got {len(records)}"
    for rec in records:
        assert len(rec.chosen_tokens) > 0
        assert len(rec.rejected_tokens) > 0
        assert rec.chosen_tokens != rec.rejected_tokens
        for tok in rec.chosen_tokens:
            assert til.SEMANTIC_START_TOKEN_ID <= tok <= til.SEMANTIC_END_TOKEN_ID


@pytest.mark.skipif(
    not VOICE_LORA_V002.is_file(),
    reason="voice_lora_v002.gguf not yet exported",
)
def test_exported_voice_lora_v002_gguf_metadata_and_distinct_from_v001() -> None:
    assert VOICE_LORA_V001.is_file()
    with VOICE_LORA_V002.open("rb") as fh:
        assert fh.read(4) == b"GGUF"

    v1_sha = til.sha256_file(VOICE_LORA_V001)
    v2_sha = til.sha256_file(VOICE_LORA_V002)
    assert v2_sha != v1_sha, "voice_lora_v002.gguf must differ from voice_lora_v001.gguf"

    gguf_py_dir = str(LLAMA_CPP_DIR / "gguf-py")
    if gguf_py_dir not in sys.path:
        sys.path.insert(0, gguf_py_dir)
    import gguf

    reader = gguf.GGUFReader(str(VOICE_LORA_V002), "r")
    assert reader.fields["general.architecture"].contents() == "qwen3"
    assert reader.fields["general.type"].contents() == "adapter"
    assert reader.fields["adapter.type"].contents() == "lora"
    assert float(reader.fields["adapter.lora.alpha"].contents()) == pytest.approx(64.0)
    assert len(reader.tensors) == 504

    nonzero_b = 0
    for tensor in reader.tensors:
        assert 32 in tuple(tensor.shape), f"Expected rank-32 tensor: {tensor.name} {tensor.shape}"
        if tensor.name.endswith(".lora_b"):
            arr = np.asarray(tensor.data, dtype=np.float32)
            assert np.all(np.isfinite(arr))
            if float(np.max(np.abs(arr))) > 0.0:
                nonzero_b += 1
    assert nonzero_b == 252


@pytest.mark.skipif(
    not GRPO_V2_REPORT.is_file(),
    reason="grpo_v2_report.json not yet written",
)
def test_grpo_v2_report_completeness_logp_delta_and_gate5_hotswap() -> None:
    report: dict[str, Any] = json.loads(GRPO_V2_REPORT.read_text(encoding="utf-8"))
    assert report["phase1_rollouts_summary"]["total_rollouts"] >= 24
    assert report["phase1_ota_upload"]["http_status"] == 200
    assert report["phase1_ota_upload"]["uploaded_count"] >= 6

    p2 = report["phase2_training"]
    assert p2["export"]["sha256"] == til.sha256_file(VOICE_LORA_V002)
    assert p2["weight_delta_v2_vs_v1"]["max_abs_diff"] > 0.0

    # Chosen-token log-prob delta must be strictly positive on on-policy chosen rollouts
    on_policy_lp = report["chosen_logp_delta"]["on_policy_chosen_rollouts"]
    assert on_policy_lp["mean_delta_v2_minus_v1"] > 0.0

    # Held-out A/B evaluated on exact same 3 prompts & seeds
    ab = report["heldout_ab"]
    assert len(report["heldout_prompts"]) == 3
    assert ab["v001"]["all_takes_summary"]["count"] == ab["v002"]["all_takes_summary"]["count"]
    assert ab["v001"]["all_takes_summary"]["count"] >= 9

    # Gate 5 live atomic hot-swap v001 -> v002 -> v001 verified with 0 errors and distinct logits
    g5 = report["gate5_live_hotswap"]
    assert g5["distinct_logit_deltas_confirmed"] is True
    assert g5["zero_errors_zero_leak_confirmed"] is True
    assert g5["atomic_swap_v1_v2_v1"]["swap_errors"] == 0
    assert g5["atomic_swap_v1_v2_v1"]["v1_vs_v2_logit_max_abs_diff"] > 1e-4
    assert g5["atomic_swap_v1_v2_v1"]["v1_restore_max_abs_diff"] <= 1e-3

    # Must NOT have overwritten bundles/v001 or prematurely written bundles/v002/bundle_manifest.json
    assert VOICE_LORA_V001.is_file()
    assert not (VOICE_LORA_V002.parent / "bundle_manifest.json").exists()
