"""Unit tests for fishaudio/s2-pro Dual-AR + LoRA inference & synthesis verification."""

from __future__ import annotations

import json
import math
import sys
from pathlib import Path

import numpy as np
import pytest
import torch
from safetensors.torch import save_file

HOME_DAEMON_DIR = Path(__file__).resolve().parents[1]
if str(HOME_DAEMON_DIR) not in sys.path:
    sys.path.insert(0, str(HOME_DAEMON_DIR))

import s2_pro_infer  # noqa: E402
import synthesize_and_render  # noqa: E402
import train_initial_lora  # noqa: E402


class _FakeTokenizer:
    """Deterministic lightweight tokenizer for prompt-structure unit tests."""

    def __init__(self, voice_id: int = 197, im_start_id: int = 198, im_end_id: int = 199) -> None:
        self._vocab: dict[str, int] = {
            "<|im_start|>": im_start_id,
            "<|im_end|>": im_end_id,
            "<|voice|>": voice_id,
            "<|speaker:0|>": 196,
        }

    def encode(self, text: str, add_special_tokens: bool = False) -> list[int]:
        del add_special_tokens
        out: list[int] = []
        i = 0
        n = len(text)
        while i < n:
            matched = False
            for tok, tid in self._vocab.items():
                if text.startswith(tok, i):
                    out.append(tid)
                    i += len(tok)
                    matched = True
                    break
            if not matched:
                out.append(1 + (ord(text[i]) % 180))
                i += 1
        return out


def _tiny_dual_ar_config() -> s2_pro_infer.DualARConfig:
    return s2_pro_infer.DualARConfig(
        vocab_size=320,
        n_layer=2,
        n_head=4,
        n_local_heads=2,
        head_dim=16,
        dim=64,
        intermediate_size=128,
        rope_base=1_000_000.0,
        norm_eps=1e-6,
        max_seq_len=64,
        tie_word_embeddings=True,
        attention_qk_norm=True,
        semantic_begin_id=200,
        semantic_end_id=263,
        im_end_id=199,
        scale_codebook_embeddings=True,
        norm_fastlayer_input=True,
        codebook_size=64,
        residual_codebook_size=32,
        num_codebooks=4,
        n_fast_layer=2,
        fast_dim=64,
        fast_n_head=4,
        fast_n_local_heads=2,
        fast_head_dim=16,
        fast_intermediate_size=128,
        fast_rope_base=1_000_000.0,
        fast_norm_eps=1e-6,
        fast_attention_qk_norm=False,
    )


def test_rope_weight_permutation_is_exact_inverse() -> None:
    torch.manual_seed(7)
    n_heads, head_dim, in_dim = 8, 128, 256
    w = torch.randn(n_heads * head_dim, in_dim)
    neox = train_initial_lora.unpermute_interleaved_rope_weight(w, n_heads, head_dim)
    restored = s2_pro_infer.permute_neox_to_interleaved_rope_weight(neox, n_heads, head_dim)
    assert torch.equal(w, restored)


def test_remap_fish_qwen3_omni_keys() -> None:
    assert s2_pro_infer.remap_fish_qwen3_omni_key("text_model.model.embeddings.weight") == "embeddings.weight"
    assert (
        s2_pro_infer.remap_fish_qwen3_omni_key("text_model.model.layers.5.attention.wqkv.weight")
        == "layers.5.attention.wqkv.weight"
    )
    assert (
        s2_pro_infer.remap_fish_qwen3_omni_key("audio_decoder.codebook_embeddings.weight")
        == "codebook_embeddings.weight"
    )
    assert (
        s2_pro_infer.remap_fish_qwen3_omni_key("audio_decoder.embeddings.weight")
        == "fast_embeddings.weight"
    )
    assert (
        s2_pro_infer.remap_fish_qwen3_omni_key("audio_decoder.layers.3.feed_forward.w2.weight")
        == "fast_layers.3.feed_forward.w2.weight"
    )
    assert s2_pro_infer.remap_fish_qwen3_omni_key("audio_decoder.norm.weight") == "fast_norm.weight"
    assert s2_pro_infer.remap_fish_qwen3_omni_key("audio_decoder.output.weight") == "fast_output.weight"


def test_dual_ar_slow_and_lora_exact_parity_with_hf_qwen3_peft(tmp_path: Path) -> None:
    """Proves S2ProDualAR + merged RoPE-permuted LoRA matches HF Qwen3ForCausalLM + PeftModel."""
    from peft import LoraConfig, PeftModel, TaskType, get_peft_model
    from transformers import Qwen3Config, Qwen3ForCausalLM

    torch.manual_seed(1234)
    cfg = _tiny_dual_ar_config()
    dual_ar = s2_pro_infer.S2ProDualAR(cfg).float().eval()

    # Build matching HF Qwen3ForCausalLM by converting DualAR's interleaved weights to NeoX
    qwen_cfg = Qwen3Config(
        vocab_size=cfg.vocab_size,
        hidden_size=cfg.dim,
        intermediate_size=cfg.intermediate_size,
        num_hidden_layers=cfg.n_layer,
        num_attention_heads=cfg.n_head,
        num_key_value_heads=cfg.n_local_heads,
        head_dim=cfg.head_dim,
        rms_norm_eps=cfg.norm_eps,
        rope_theta=cfg.rope_base,
        tie_word_embeddings=True,
        max_position_embeddings=cfg.max_seq_len,
    )
    qwen_cfg._attn_implementation = "eager"
    hf_model = Qwen3ForCausalLM(qwen_cfg).float().eval()

    q_dim = cfg.n_head * cfg.head_dim
    kv_dim = cfg.n_local_heads * cfg.head_dim
    hf_sd: dict[str, torch.Tensor] = {
        "model.embed_tokens.weight": dual_ar.embeddings.weight.detach().clone(),
        "lm_head.weight": dual_ar.embeddings.weight.detach().clone(),
        "model.norm.weight": dual_ar.norm.weight.detach().clone(),
    }
    for i in range(cfg.n_layer):
        blk = dual_ar.layers[i]
        q_w, k_w, v_w = torch.split(blk.attention.wqkv.weight.detach(), [q_dim, kv_dim, kv_dim], dim=0)
        p = f"model.layers.{i}"
        hf_sd[f"{p}.self_attn.q_proj.weight"] = train_initial_lora.unpermute_interleaved_rope_weight(
            q_w, cfg.n_head, cfg.head_dim
        )
        hf_sd[f"{p}.self_attn.k_proj.weight"] = train_initial_lora.unpermute_interleaved_rope_weight(
            k_w, cfg.n_local_heads, cfg.head_dim
        )
        hf_sd[f"{p}.self_attn.v_proj.weight"] = v_w.clone()
        hf_sd[f"{p}.self_attn.o_proj.weight"] = blk.attention.wo.weight.detach().clone()
        hf_sd[f"{p}.self_attn.q_norm.weight"] = train_initial_lora.unpermute_interleaved_rope_norm(
            blk.attention.q_norm.weight.detach()
        )
        hf_sd[f"{p}.self_attn.k_norm.weight"] = train_initial_lora.unpermute_interleaved_rope_norm(
            blk.attention.k_norm.weight.detach()
        )
        hf_sd[f"{p}.input_layernorm.weight"] = blk.attention_norm.weight.detach().clone()
        hf_sd[f"{p}.post_attention_layernorm.weight"] = blk.ffn_norm.weight.detach().clone()
        hf_sd[f"{p}.mlp.gate_proj.weight"] = blk.feed_forward.w1.weight.detach().clone()
        hf_sd[f"{p}.mlp.up_proj.weight"] = blk.feed_forward.w3.weight.detach().clone()
        hf_sd[f"{p}.mlp.down_proj.weight"] = blk.feed_forward.w2.weight.detach().clone()

    hf_model.load_state_dict(hf_sd, strict=True)

    # Attach non-zero Rank-4 PEFT LoRA matching train_initial_lora.py target modules and save
    lora_cfg = LoraConfig(
        r=4,
        lora_alpha=8,
        lora_dropout=0.0,
        bias="none",
        task_type=TaskType.CAUSAL_LM,
        target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"],
    )
    peft_model = get_peft_model(hf_model, lora_cfg).float().eval()
    with torch.no_grad():
        for name, param in peft_model.named_parameters():
            if "lora_A" in name or "lora_B" in name:
                param.normal_(0.0, 0.05)

    adapter_dir = tmp_path / "peft_lora_adapter"
    peft_model.save_pretrained(str(adapter_dir), safe_serialization=True)

    # Merge that exact saved adapter into DualAR state_dict
    dual_sd = {k: v.detach().clone() for k, v in dual_ar.state_dict().items()}
    merge_stats = s2_pro_infer.merge_peft_lora_into_dual_ar_state_dict(
        dual_sd, config=cfg, lora_dir=adapter_dir, lora_scale=1.0
    )
    assert merge_stats.merged_modules == cfg.n_layer * 7
    assert merge_stats.consumed_tensors == cfg.n_layer * 14
    assert merge_stats.max_delta_abs > 0.0
    dual_ar.load_state_dict(dual_sd, strict=True)

    # Compare logits on pure text tokens (IDs < semantic_begin_id)
    text_ids = torch.tensor([[5, 42, 17, 103, 88, 12, 64]], dtype=torch.long)
    dual_inp = torch.zeros((1, cfg.num_codebooks + 1, text_ids.size(1)), dtype=torch.long)
    dual_inp[:, 0, :] = text_ids

    with torch.inference_mode():
        hf_logits = peft_model(input_ids=text_ids).logits
        dual_logits, _ = dual_ar.forward_slow(dual_inp, start_pos=0, use_cache=False, return_all=True)

    max_diff = float((hf_logits - dual_logits).abs().max().item())
    assert max_diff < 1e-4, f"DualAR+LoRA drifted from HF Qwen3+PeftModel: max_abs_diff={max_diff}"

    # Verify KV-cache step-by-step decode matches full-sequence forward pass identically
    dual_ar.setup_caches(max_batch_size=1, max_seq_len=32, dtype=torch.float32)
    with torch.inference_mode():
        prefill_logits, _ = dual_ar.forward_slow(dual_inp[:, :, :-2], start_pos=0, use_cache=True)
        step1_logits, _ = dual_ar.forward_slow(dual_inp[:, :, -2:-1], start_pos=5, use_cache=True)
        step2_logits, _ = dual_ar.forward_slow(dual_inp[:, :, -1:], start_pos=6, use_cache=True)

    assert float((prefill_logits[0, -1] - dual_logits[0, 4]).abs().max().item()) < 5e-5
    assert float((step1_logits[0, -1] - dual_logits[0, 5]).abs().max().item()) < 5e-5
    assert float((step2_logits[0, -1] - dual_logits[0, 6]).abs().max().item()) < 5e-5


def test_dual_ar_codebook_embedding_scaling_and_generation() -> None:
    torch.manual_seed(42)
    cfg = _tiny_dual_ar_config()
    model = s2_pro_infer.S2ProDualAR(cfg).float().eval()

    # Check embed_slow_input scales semantic tokens by 1/sqrt(num_codebooks + 1)
    inp = torch.zeros((1, cfg.num_codebooks + 1, 2), dtype=torch.long)
    inp[0, 0, 0] = 10  # text token
    inp[0, 0, 1] = cfg.semantic_begin_id + 3  # semantic token
    inp[0, 1:, 1] = torch.tensor([3, 5, 7, 9], dtype=torch.long)

    emb = model.embed_slow_input(inp)
    expected_text = model.embeddings.weight[10]
    expected_sem = model.embeddings.weight[cfg.semantic_begin_id + 3].clone()
    for i, cb in enumerate([3, 5, 7, 9]):
        expected_sem = expected_sem + model.codebook_embeddings.weight[cb + i * cfg.codebook_size]
    expected_sem = expected_sem / math.sqrt(cfg.num_codebooks + 1)

    assert torch.allclose(emb[0, 0], expected_text, atol=1e-6)
    assert torch.allclose(emb[0, 1], expected_sem, atol=1e-6)

    # Check autoregressive generation produces valid [num_codebooks, T] codes & deterministic seeds
    tok = _FakeTokenizer()
    ref_codes = np.zeros((cfg.num_codebooks, 6), dtype=np.int32)
    prompt = s2_pro_infer.build_s2_pro_prompt_tensor(
        tok,
        "Hello world",
        ref_text="Reference voice line.",
        ref_codes=ref_codes,
        num_codebooks=cfg.num_codebooks,
        semantic_begin_id=cfg.semantic_begin_id,
    )
    assert prompt.shape[0] == cfg.num_codebooks + 1
    assert int(prompt[0, -1].item()) == 197  # ends with <|voice|>

    # Verify both multiturn (1-turn & 2-turn) and system prompt styles preserve VQ codes
    p_sys = s2_pro_infer.build_s2_pro_prompt_tensor(
        tok,
        "Hello world",
        ref_text="Reference voice line.",
        ref_codes=ref_codes,
        prompt_style="system",
        num_codebooks=cfg.num_codebooks,
        semantic_begin_id=cfg.semantic_begin_id,
    )
    p_two = s2_pro_infer.build_s2_pro_prompt_tensor(
        tok,
        "Hello world",
        ref_turns=[("First reference.", ref_codes), ("Second reference.", ref_codes)],
        prompt_style="multiturn",
        num_codebooks=cfg.num_codebooks,
        semantic_begin_id=cfg.semantic_begin_id,
    )
    assert int(p_sys[0, -1].item()) == 197
    assert int(p_two[0, -1].item()) == 197
    assert p_two.shape[1] > prompt.shape[1]

    res1 = s2_pro_infer.generate_dual_ar_codes(
        model,
        prompt,
        seed=948101,
        max_new_tokens=12,
        temperature=0.72,
        top_p=0.80,
        top_k=16,
        min_new_tokens=4,
    )
    res2 = s2_pro_infer.generate_dual_ar_codes(
        model,
        prompt,
        seed=948101,
        max_new_tokens=12,
        temperature=0.72,
        top_p=0.80,
        top_k=16,
        min_new_tokens=4,
    )
    assert np.array_equal(res1.codes, res2.codes)
    assert res1.codes.shape[0] == cfg.num_codebooks
    assert res1.generated_frames >= 4
    assert int(res1.codes[0].max()) < cfg.codebook_size
    assert int(res1.codes[1:].max()) < cfg.residual_codebook_size


def test_chunked_decode_window_planner_and_stitching_continuity() -> None:
    # Verify exact frame coverage across short, boundary, and multi-chunk lengths
    for total_frames in (1, 32, 64, 65, 104, 128, 191, 250):
        windows = s2_pro_infer.plan_chunked_decode_windows(
            total_frames, chunk_frames=64, overlap_frames=24, xfade_frames=2
        )
        assert windows[0][0] == 0
        last_start, last_valid, _, _ = windows[-1]
        assert last_start + last_valid == total_frames
        for idx, (_, valid, keep_start, xfade_len) in enumerate(windows):
            if idx == 0:
                assert keep_start == 0 and xfade_len == 0
            else:
                assert valid >= 25  # overlap_frames + 1 invariant
                assert keep_start == 22 and xfade_len == 2

        # Stitch a known continuous 440 Hz sine wave and verify zero seam distortion
        frame_len = 16
        t_all = np.arange(total_frames * frame_len, dtype=np.float32)
        true_wave = np.sin(2.0 * np.pi * t_all / 37.0).astype(np.float32)
        chunk_pcms = []
        for start, valid, _, _ in windows:
            buf = np.zeros(64 * frame_len, dtype=np.float32)
            s0 = start * frame_len
            s1 = (start + valid) * frame_len
            buf[: s1 - s0] = true_wave[s0:s1]
            chunk_pcms.append(buf)
        stitched = s2_pro_infer._stitch_decoded_windows(
            chunk_pcms, windows, total_frames=total_frames, frame_length_samples=frame_len
        )
        assert stitched.shape == true_wave.shape
        assert float(np.max(np.abs(stitched - true_wave))) < 1e-6


def test_s2_pro_config_and_lora_real_assets_if_present() -> None:
    """Smoke-check real on-disk config.json and adapter_config.json schema when present."""
    if not s2_pro_infer.DEFAULT_S2_PRO_HF_DIR.is_dir():
        pytest.skip("s2-pro-hf weights not present on this host")
    cfg = s2_pro_infer.DualARConfig.from_pretrained(s2_pro_infer.DEFAULT_S2_PRO_HF_DIR)
    assert cfg.n_layer == 36
    assert cfg.n_fast_layer == 4
    assert cfg.num_codebooks == 10
    assert cfg.dim == 2560
    assert cfg.attention_qk_norm is True
    assert cfg.fast_attention_qk_norm is False
    assert cfg.scale_codebook_embeddings is True
    assert cfg.norm_fastlayer_input is True


def test_mastering_lufs_convergence_and_tail_rms(tmp_path: Path) -> None:
    sr = synthesize_and_render.SAMPLE_RATE_HZ
    t = np.arange(sr * 2, dtype=np.float64) / float(sr)
    # 1.8 s of 440 Hz tone + 0.2 s clean quiet tail
    tone = (0.05 * np.sin(2.0 * np.pi * 440.0 * t)).astype(np.float32)
    tone[int(sr * 1.8) :] = 0.0

    # Write a unit-impulse room IR (544 floats, 64-byte aligned)
    ir = np.zeros(544, dtype=np.float32)
    ir[0] = 1.0
    ir_path = tmp_path / "unit_ir.pcm"
    ir_path.write_bytes(ir.astype("<f4").tobytes())

    mastered, meta = synthesize_and_render.master_synthesized_pcm(
        tone,
        room_ir_path=ir_path,
        room_tone_path=None,
        target_lufs=-16.0,
    )
    assert meta["room_ir_applied"] is True
    assert meta["room_ir_taps"] == 544
    assert abs(meta["mastered_lufs"] - (-16.0)) < 0.35
    assert meta["peak_amplitude"] <= 0.99
    assert synthesize_and_render.compute_tail_rms_dbfs(mastered, tail_ms=80, sample_rate_hz=sr) <= -90.0


def test_syllable_count_and_candidate_selection() -> None:
    target = synthesize_and_render.DEFAULT_TARGET_TEXT
    syl = synthesize_and_render.count_transcript_syllables(target)
    # "We(1) ripped(1) out(1) the(1) cloud(1) wrap-per(2) and(1) ran(1) the(1) en-tire(2)
    #  voice(1) stu-di-o(3) bare(1) me-tal(2) right(1) on(1) the(1) Pi-xel(2)" -> ~23 syllables
    assert 20 <= syl <= 26

    c_bad_cer = {
        "take_index": 0,
        "seed": 1,
        "gates": {
            "passed_all_strict_gates": False,
            "ranking_score": 0.4,
            "cer": 0.35,
            "wespeaker_cosine": 0.85,
            "dnsmos_p808": 3.8,
        },
    }
    c_good = {
        "take_index": 1,
        "seed": 2,
        "gates": {
            "passed_all_strict_gates": False,
            "ranking_score": 1.6,
            "cer": 0.0,
            "wespeaker_cosine": 0.84,
            "dnsmos_p808": 3.7,
        },
    }
    c_strict = {
        "take_index": 2,
        "seed": 3,
        "gates": {
            "passed_all_strict_gates": True,
            "ranking_score": 1.75,
            "cer": 0.0,
            "wespeaker_cosine": 0.89,
            "dnsmos_p808": 4.2,
        },
    }
    assert synthesize_and_render.select_best_candidate([c_bad_cer, c_good])["take_index"] == 1
    assert synthesize_and_render.select_best_candidate([c_bad_cer, c_good, c_strict])["take_index"] == 2

