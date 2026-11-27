Runtime patches applied in the training job (see Azure job spec, not committed: contains SAS secrets):
- QwenLM/Qwen3-TTS PR #387 (text_projection for 0.6B + talker/sub-talker label alignment + keeps EOS target),
  then `pip install --no-deps /opt/Qwen3-TTS` so the patched qwen_tts package is used.
- flash_attention_2 -> sdpa (flash-attn not installed).
- Base model snapshot_download to /tmp/models/base06 (sft_12hz copytree needs a local path).
Why #387: unpatched run6 trained (loss 10.7->3.9) but generations never hit EOS (~18 min/line, issue #371).

Run9 (2026-11-25): container runs as `appuser`, so patching /opt failed in run7/8. Fix: copy repo to /tmp, git apply #387 there,
PYTHONPATH=/tmp/Qwen3-TTS, assert patched module is loaded, cap samples at max_new_tokens=360, upload checkpoints one tar each
(single PUT limit). Result: loss 2.74 -> 1.61 over 5 epochs; all 15 samples stop on their own and transcribe correctly (whisper medium).
Checkpoints: blob aircheckvoice82601f/voice/run9/checkpoint-epoch-{0..4}.tar (2.4G each).
