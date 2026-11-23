"""Unit and real-artifact verification tests for Aircheck VoiceStudio llama.cpp & LoRA builder."""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

import numpy as np
import pytest
import soundfile as sf
import torch

HOME_DAEMON_DIR = Path(__file__).resolve().parents[1]
if str(HOME_DAEMON_DIR) not in sys.path:
    sys.path.insert(0, str(HOME_DAEMON_DIR))

import train_initial_lora as til  # noqa: E402

LLAMA_CPP_DIR = Path("/Users/mac/llama.cpp")
ANDROID_LIBS_DIR = Path("/Users/mac/Downloads/voicestudio-models/android-libs/arm64-v8a")
NDK_LLVM_BIN = Path(
    "/opt/homebrew/share/android-commandlinetools/ndk/28.2.13676358/"
    "toolchains/llvm/prebuilt/darwin-x86_64/bin"
)
HOST_VERIFY_BIN = Path(
    "/Users/mac/Downloads/voicestudio-models/llama-build-host/bin/llama-lora-verify"
)
BASE_GGUF = Path("/Users/mac/Downloads/voicestudio-models/s2-pro/s2-pro.Q4_K_M.gguf")
S2_PRO_HF_DIR = Path("/Users/mac/Downloads/voicestudio-models/s2-pro-hf")
VOICE_LORA_V001 = Path(
    "/Users/mac/Downloads/voicestudio-models/bundles/v001/voice_lora_v001.gguf"
)
TRAINING_REPORT = Path(
    "/Users/mac/Downloads/voicestudio-models/bundles/v001/lora_training_report.json"
)
BUILD_SCRIPT = HOME_DAEMON_DIR / "build_android_llama.sh"


class _FakeTokenizer:
    """Minimal deterministic tokenizer stub for pure unit tests (no model download)."""

    def encode(self, text: str, add_special_tokens: bool = False) -> list[int]:
        assert text.endswith("<|voice|>")
        prefix = text[: -len("<|voice|>")]
        # Simple deterministic byte IDs below special-token range + real <|voice|> ID.
        return [100 + (b % 500) for b in prefix.encode("utf-8")[:24]] + [til.VOICE_TOKEN_ID]


def test_build_qwen3_config_dict_from_s2_pro() -> None:
    s2_cfg = {
        "eos_token_id": 151645,
        "pad_token_id": 151669,
        "dtype": "bfloat16",
        "text_config": {
            "dim": 2560,
            "n_layer": 36,
            "n_head": 32,
            "n_local_heads": 8,
            "head_dim": 128,
            "intermediate_size": 9728,
            "max_seq_len": 32768,
            "norm_eps": 1e-6,
            "rope_base": 1000000,
            "tie_word_embeddings": True,
            "vocab_size": 155776,
        },
    }
    qwen = til.build_qwen3_config_dict(s2_cfg)
    assert qwen["architectures"] == ["Qwen3ForCausalLM"]
    assert qwen["model_type"] == "qwen3"
    assert qwen["vocab_size"] == 155776
    assert qwen["hidden_size"] == 2560
    assert qwen["num_hidden_layers"] == 36
    assert qwen["num_attention_heads"] == 32
    assert qwen["num_key_value_heads"] == 8
    assert qwen["head_dim"] == 128
    assert qwen["intermediate_size"] == 9728
    assert qwen["rope_theta"] == 1000000.0
    assert qwen["tie_word_embeddings"] is True


def test_unpermute_interleaved_rope_weight_and_norm() -> None:
    # 2 heads, head_dim=4, in_dim=3: interleaved [e0, o0, e1, o1] -> split-half [e0, e1, o0, o1]
    w = torch.arange(2 * 4 * 3, dtype=torch.float32).reshape(8, 3)
    out = til.unpermute_interleaved_rope_weight(w, n_heads=2, head_dim=4)
    assert out.shape == (8, 3)
    # Head 0 rows: [0, 2, 1, 3]; Head 1 rows: [4, 6, 5, 7]
    expected_rows = [0, 2, 1, 3, 4, 6, 5, 7]
    assert torch.equal(out, w[expected_rows])

    norm = torch.tensor([10.0, 20.0, 30.0, 40.0, 50.0, 60.0])
    out_norm = til.unpermute_interleaved_rope_norm(norm)
    assert torch.equal(out_norm, torch.tensor([10.0, 30.0, 50.0, 20.0, 40.0, 60.0]))

    with pytest.raises(ValueError):
        til.unpermute_interleaved_rope_weight(w, n_heads=3, head_dim=4)
    with pytest.raises(ValueError):
        til.unpermute_interleaved_rope_norm(torch.tensor([1.0, 2.0, 3.0]))


def test_clean_transcript_and_prompt_format() -> None:
    cleaned = til.clean_transcript("  Hello   world!\n [BLANK_AUDIO]  test ")
    assert cleaned == "Hello world! test"
    prompt = til.build_s2_pro_prompt("Hello\nworld [BLANK_AUDIO]")
    assert "<|im_start|>system\nconvert the provided text to speech<|im_end|>\n" in prompt
    assert "<|im_start|>user\n<|speaker:0|>Hello world<|im_end|>\n" in prompt
    assert prompt.endswith("<|im_start|>assistant\n<|voice|>")


def test_build_sft_sequence_masking_and_semantic_range() -> None:
    tok = _FakeTokenizer()
    seq = til.build_sft_sequence(tok, "Testing Ryan voice.", [0, 42, 4095], max_seq_len=128)
    plen = seq["prompt_len"]
    assert seq["labels"][:plen] == [-100] * plen
    assert seq["input_ids"][plen:] == [
        til.SEMANTIC_START_TOKEN_ID,
        til.SEMANTIC_START_TOKEN_ID + 42,
        til.SEMANTIC_END_TOKEN_ID,
        til.IM_END_TOKEN_ID,
    ]
    assert seq["labels"][plen:] == seq["input_ids"][plen:]

    with pytest.raises(ValueError):
        til.build_sft_sequence(tok, "Invalid code", [4096])
    with pytest.raises(ValueError):
        til.build_sft_sequence(tok, "Negative code", [-1])


def test_load_dataset_entries_manifest_and_filtering(tmp_path: Path) -> None:
    wav_ok = tmp_path / "ok.wav"
    wav_short = tmp_path / "short.wav"
    sf.write(str(wav_ok), np.zeros(44100 * 3, dtype=np.float32), 44100)
    sf.write(str(wav_short), np.zeros(4410, dtype=np.float32), 44100)

    manifest = tmp_path / "manifest.jsonl"
    rows = [
        {
            "id": "clip_ok",
            "wav_44k": str(wav_ok),
            "duration_sec": 3.0,
            "transcript": "Hello from Ryan.",
            "wespeaker_cos": 0.84,
            "dnsmos_p808": 3.85,
        },
        {
            "id": "clip_short",
            "wav_44k": str(wav_short),
            "duration_sec": 0.1,
            "transcript": "Too short.",
            "wespeaker_cos": 0.85,
            "dnsmos_p808": 3.9,
        },
        {
            "id": "clip_low_cos",
            "wav_44k": str(wav_ok),
            "duration_sec": 3.0,
            "transcript": "Different speaker.",
            "wespeaker_cos": 0.45,
            "dnsmos_p808": 3.8,
        },
        {
            "id": "clip_blank",
            "wav_44k": str(wav_ok),
            "duration_sec": 3.0,
            "transcript": "[BLANK_AUDIO]",
            "wespeaker_cos": 0.9,
            "dnsmos_p808": 4.0,
        },
    ]
    manifest.write_text("\n".join(json.dumps(r) for r in rows) + "\n", encoding="utf-8")

    loaded = til.load_dataset_entries(
        manifest_path=manifest,
        fallback_transcripts_json=None,
        fallback_raw_dir=None,
        min_duration_sec=1.0,
        min_wespeaker_cos=0.72,
    )
    assert len(loaded) == 1
    assert loaded[0].clip_id == "clip_ok"
    assert loaded[0].wespeaker_cos == pytest.approx(0.84)


def test_split_train_val_deterministic() -> None:
    items = [{"id": i} for i in range(20)]
    tr1, va1 = til.split_train_val(items, val_fraction=0.1, seed=42)
    tr2, va2 = til.split_train_val(items, val_fraction=0.1, seed=42)
    assert tr1 == tr2 and va1 == va2
    assert len(va1) == 2 and len(tr1) == 18


def test_build_android_llama_script_syntax_and_help() -> None:
    assert BUILD_SCRIPT.is_file()
    syntax = subprocess.run(["bash", "-n", str(BUILD_SCRIPT)], capture_output=True, text=True)
    assert syntax.returncode == 0, syntax.stderr
    help_run = subprocess.run(["bash", str(BUILD_SCRIPT), "--help"], capture_output=True, text=True)
    assert help_run.returncode == 0
    assert "--target" in help_run.stderr


def test_llama_cpp_repo_unmodified() -> None:
    assert (LLAMA_CPP_DIR / ".git").exists()
    diff = subprocess.run(
        ["git", "-C", str(LLAMA_CPP_DIR), "diff", "--quiet"],
        capture_output=True,
        text=True,
    )
    assert diff.returncode == 0, "Upstream /Users/mac/llama.cpp working tree must not be dirtied"


def test_android_arm64_libllama_16kb_alignment_and_symbols() -> None:
    readelf = NDK_LLVM_BIN / "llvm-readelf"
    nm = NDK_LLVM_BIN / "llvm-nm"
    assert readelf.is_file(), f"Missing NDK llvm-readelf: {readelf}"
    assert nm.is_file(), f"Missing NDK llvm-nm: {nm}"

    expected_libs = ("libllama.so", "libggml.so", "libggml-base.so", "libggml-cpu.so")
    for lib_name in expected_libs:
        so_path = ANDROID_LIBS_DIR / lib_name
        assert so_path.is_file() and so_path.stat().st_size > 10_000, f"Missing {so_path}"

        # Verify AArch64 ELF header + 16 KB (0x4000) alignment on every LOAD segment.
        hdr = subprocess.run([str(readelf), "-h", str(so_path)], capture_output=True, text=True)
        assert hdr.returncode == 0
        assert "AArch64" in hdr.stdout, f"{lib_name} is not AArch64"

        phdrs = subprocess.run([str(readelf), "-lW", str(so_path)], capture_output=True, text=True)
        assert phdrs.returncode == 0
        load_lines = [ln for ln in phdrs.stdout.splitlines() if ln.strip().startswith("LOAD")]
        assert load_lines, f"No LOAD segments in {lib_name}"
        for ln in load_lines:
            align = ln.split()[-1]
            assert align in ("0x4000", "0x10000"), f"{lib_name} non-16KB alignment: {ln}"

        # Verify unversioned SONAME / NEEDED for Android Bionic linker compatibility.
        dyn = subprocess.run([str(readelf), "-dW", str(so_path)], capture_output=True, text=True)
        assert dyn.returncode == 0
        assert ".so.0" not in dyn.stdout, f"{lib_name} has versioned .so.0 dynamic entry"

    # Verify LoRA C ABI symbol exports on libllama.so.
    nm_out = subprocess.run(
        [str(nm), "-D", "--defined-only", str(ANDROID_LIBS_DIR / "libllama.so")],
        capture_output=True,
        text=True,
    )
    assert nm_out.returncode == 0
    exported_syms = {line.split()[-1] for line in nm_out.stdout.splitlines() if line.strip()}
    for required in (
        "llama_adapter_lora_init",
        "llama_set_adapter_lora",
        "llama_set_adapters_lora",
        "llama_rm_adapter_lora",
        "llama_clear_adapter_lora",
        "llama_adapter_lora_free",
        "llama_kv_cache_clear",
    ):
        assert required in exported_syms, f"Android libllama.so missing symbol {required}"


def test_fish_qwen3_to_gguf_weight_parity_layer0() -> None:
    """Verify our fish_qwen3 -> Qwen3 RoPE unpermutation matches s2-pro.Q4_K_M.gguf."""
    gguf_py_dir = str(LLAMA_CPP_DIR / "gguf-py")
    if gguf_py_dir not in sys.path:
        sys.path.insert(0, gguf_py_dir)
    import gguf
    from safetensors import safe_open

    reader = gguf.GGUFReader(str(BASE_GGUF), "r")
    gguf_map = {t.name: t for t in reader.tensors}

    def deq(name: str) -> torch.Tensor:
        t = gguf_map[name]
        return torch.from_numpy(gguf.dequantize(t.data, t.tensor_type).copy()).double()

    def cos(a: torch.Tensor, b: torch.Tensor) -> float:
        af, bf = a.double().flatten(), b.double().flatten()
        return float((af @ bf) / (af.norm() * bf.norm()))

    shard1 = S2_PRO_HF_DIR / "model-00001-of-00002.safetensors"
    with safe_open(str(shard1), framework="pt", device="cpu") as f:
        wqkv = f.get_tensor("text_model.model.layers.0.attention.wqkv.weight")
        q_w, k_w, v_w = torch.split(wqkv, [4096, 1024, 1024], dim=0)
        q_norm = f.get_tensor("text_model.model.layers.0.attention.q_norm.weight")
        k_norm = f.get_tensor("text_model.model.layers.0.attention.k_norm.weight")

    q_unperm = til.unpermute_interleaved_rope_weight(q_w, 32, 128)
    k_unperm = til.unpermute_interleaved_rope_weight(k_w, 8, 128)
    qn_unperm = til.unpermute_interleaved_rope_norm(q_norm)
    kn_unperm = til.unpermute_interleaved_rope_norm(k_norm)

    assert cos(q_unperm, deq("blk.0.attn_q.weight")) > 0.995
    assert cos(k_unperm, deq("blk.0.attn_k.weight")) > 0.995
    assert cos(v_w, deq("blk.0.attn_v.weight")) > 0.999
    assert float((qn_unperm.double() - deq("blk.0.attn_q_norm.weight")).abs().max().item()) < 1e-6
    assert float((kn_unperm.double() - deq("blk.0.attn_k_norm.weight")).abs().max().item()) < 1e-6


def test_exported_voice_lora_v001_gguf_metadata_and_report() -> None:
    assert VOICE_LORA_V001.is_file(), f"Missing exported GGUF LoRA: {VOICE_LORA_V001}"
    assert TRAINING_REPORT.is_file(), f"Missing training report: {TRAINING_REPORT}"

    with VOICE_LORA_V001.open("rb") as f:
        assert f.read(4) == b"GGUF"

    gguf_py_dir = str(LLAMA_CPP_DIR / "gguf-py")
    if gguf_py_dir not in sys.path:
        sys.path.insert(0, gguf_py_dir)
    import gguf

    reader = gguf.GGUFReader(str(VOICE_LORA_V001), "r")
    assert reader.fields["general.architecture"].contents() == "qwen3"
    assert reader.fields["general.type"].contents() == "adapter"
    assert reader.fields["adapter.type"].contents() == "lora"
    assert float(reader.fields["adapter.lora.alpha"].contents()) == pytest.approx(64.0)
    # 36 layers x 7 target modules (q,k,v,o,gate,up,down) x 2 (lora_a, lora_b) = 504 tensors
    assert len(reader.tensors) == 504

    nonzero_b = 0
    for tensor in reader.tensors:
        assert 32 in tuple(tensor.shape), f"Expected rank-32 dim in {tensor.name}: {tensor.shape}"
        if tensor.name.endswith(".lora_b"):
            arr = np.asarray(tensor.data, dtype=np.float32)
            assert np.all(np.isfinite(arr)), f"Non-finite values in {tensor.name}"
            if float(np.max(np.abs(arr))) > 0.0:
                nonzero_b += 1
    assert nonzero_b == 252, f"Expected all 252 lora_b tensors non-zero, got {nonzero_b}"

    report = json.loads(TRAINING_REPORT.read_text(encoding="utf-8"))
    assert report["export"]["sha256"] == til.sha256_file(VOICE_LORA_V001)
    assert report["training"]["lora_rank"] == 32
    assert report["training"]["final_train_loss"] < report["training"]["initial_train_loss"]
    assert report["dataset"]["num_clips"] >= 5
    assert report["dataset"]["total_semantic_tokens"] > 0


def test_real_llama_cpp_lora_hotswap_and_logit_shift() -> None:
    """End-to-end host llama.cpp verification loading s2-pro.Q4_K_M.gguf + voice_lora_v001.gguf."""
    assert HOST_VERIFY_BIN.is_file(), f"Missing host verify binary: {HOST_VERIFY_BIN}"
    assert BASE_GGUF.is_file() and VOICE_LORA_V001.is_file()

    result = til.verify_llama_cpp_lora_hotswap(
        verify_bin=HOST_VERIFY_BIN,
        base_gguf=BASE_GGUF,
        lora_gguf=VOICE_LORA_V001,
        scale=1.0,
        hotswap_iters=5,
        n_gpu_layers=99,
    )
    assert result["base_model_loaded"] is True
    assert result["lora_loaded"] is True
    assert result["lora_set_rc"] == 0
    assert result["lora_rm_rc"] == 0
    assert result["vocab_size"] == 155776
    assert result["base_sem_prob"] > 0.99
    assert result["logit_max_abs_diff"] > 1e-4
    assert result["semantic_logit_l2_diff"] > 1e-3
    assert result["post_detach_max_abs_diff"] < 1e-2
    assert result["hotswap_errors"] == 0
