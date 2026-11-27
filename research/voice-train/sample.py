"""Renders the same test lines from every checkpoint so they can be compared by ear."""
import pathlib, sys, time
import soundfile as sf
import torch
from qwen_tts import Qwen3TTSModel

out_dir, samples = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
samples.mkdir(parents=True, exist_ok=True)
lines = [
    "Hey everybody, big news today in the phone world.",
    "Huawei and Xiaomi both launched new flagships, and Apple is coming soon.",
    "Here is what actually matters if you are thinking about upgrading.",
]
for ckpt in sorted(out_dir.glob("checkpoint-epoch-*")):
    tts = Qwen3TTSModel.from_pretrained(str(ckpt), device_map="cuda:0", dtype=torch.bfloat16, attn_implementation="sdpa")
    for i, text in enumerate(lines):
        t = time.time()
        wavs, sr = tts.generate_custom_voice(text=text, speaker="ryan", max_new_tokens=360)
        sf.write(samples / f"{ckpt.name}_line{i}.wav", wavs[0], sr)
        print(f"{ckpt.name} line{i}: {len(wavs[0]) / sr:.2f}s audio in {time.time() - t:.2f}s")
    del tts
    torch.cuda.empty_cache()
