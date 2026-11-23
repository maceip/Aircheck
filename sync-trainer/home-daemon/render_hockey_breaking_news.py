#!/usr/bin/env python3
"""
Fast 4-beat (<40s) Breaking News broadcast renderer for Ryan's Golden Gate Bridge Hockey bit:
1. Loads s2-pro Dual-AR + Ryan's trained Rank-32 LoRA ONCE on Apple M5 Max MPS.
2. Synthesizes 3 candidate takes per beat, batch-decodes via real exported FP16 LiteRT
   `codec_decoder_fp16.tflite`, picks best take per beat using real 4-Gate Verifier
   (whisper-cli CER + WeSpeaker ResNet34 + Microsoft DNSMOS p808), and applies 12ms Room IR
   + -16 LUFS + -58 dBFS studio mastering.
3. Renders all 4 deadpan-serious 720P 9:16 breaking-news anchor clips in parallel via
   `bl video ref --model wan3.0-video`, burns crisp BREAKING NEWS lower-third chyron + LIVE bug,
   muxes Ryan's exact locally synthesized voice, and pushes straight to Pixel `/sdcard/Movies/`.
"""

from __future__ import annotations

import base64
import concurrent.futures
import json
import math
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

import numpy as np
import soundfile as sf
import torch

from PIL import Image, ImageDraw, ImageFont

HOME_DAEMON = Path("/Users/mac/Aircheck/sync-trainer/home-daemon")
sys.path.insert(0, str(HOME_DAEMON))

import enroll_voice_assets as eva
import s2_pro_infer as s2i
import synthesize_and_render as sar

AVATAR_PATH = Path("/Users/mac/Downloads/clone/images/avatar_ref.jpg")
BL_BIN = Path("/Users/mac/.local/bin/bl")
WHISPER_BIN = shutil.which("whisper-cli") or "/opt/homebrew/bin/whisper-cli"
WHISPER_MODEL = Path("/Users/mac/models/ggml-medium-q5_0.bin")

OUT_DIR = Path("/Users/mac/Downloads/voicestudio-models/renders/golden_gate_hockey")
FINAL_MP4 = Path("/Users/mac/Downloads/voicestudio-models/renders/golden_gate_hockey_breaking_news.mp4")
STRIP_JPG = Path("/Users/mac/.gemini/antigravity/brain/e8d92277-7081-4761-bd20-5b9124c64828/golden_gate_hockey_strip.jpg")

BEATS = [
    {
        "id": "b01",
        "spoken": "Breaking news out of San Francisco, where a developing situation is unfolding high above the bay.",
        "visual_spoken": "Breaking news out of San Francisco, where a developing situation is unfolding high above the bay.",
        "headline": "BREAKING: GOLDEN GATE BRIDGE",
        "subline": "SITUATION DEVELOPING · SAN FRANCISCO",
    },
    {
        "id": "b02",
        "spoken": "Two hockey players were spotted illegally playing puck on top of the Golden Gate Bridge Saturday midday at two thirty-three P.M.",
        "visual_spoken": "Two hockey players were spotted illegally playing puck on top of the Golden Gate Bridge Saturday midday at 2:33 PM.",
        "headline": "ILLEGAL HOCKEY ON BRIDGE",
        "subline": "SPOTTED SATURDAY MIDDAY · 2:33 PM",
    },
    {
        "id": "b03",
        "spoken": "Officials confirm no less than two bicyclists were injured as a direct result of the rogue bridge match.",
        "visual_spoken": "Officials confirm no less than two bicyclists were injured as a direct result of the rogue bridge match.",
        "headline": "2+ BICYCLISTS INJURED",
        "subline": "TRAFFIC IMPACTED · GOLDEN GATE",
    },
    {
        "id": "b04",
        "spoken": "There are rumors one player has fallen off the bridge, but was saved by the suicide nets. More as this story develops.",
        "visual_spoken": "There are rumors one player has fallen off the bridge, but was saved by the bridge safety nets. More as this story develops.",
        "headline": "PLAYER SAVED BY BRIDGE NETS",
        "subline": "UNCONFIRMED REPORTS · DEVELOPING",
    },
]


def probe_duration(path: Path) -> float:
    out = subprocess.check_output(
        [
            "ffprobe", "-v", "error",
            "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1",
            str(path),
        ],
        text=True,
    ).strip()
    return float(out)


def build_chyron_overlay_png(headline: str, subline: str, out_png: Path) -> None:
    img = Image.new("RGBA", (720, 1280), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    font_bold_path = "/Users/mac/Aircheck/app/src/main/res/font/space_grotesk.ttf"
    font_mono_path = "/Users/mac/Aircheck/app/src/main/res/font/jetbrains_mono.ttf"
    f_live = ImageFont.truetype(font_mono_path, 22)
    f_loc = ImageFont.truetype(font_mono_path, 20)
    f_tag = ImageFont.truetype(font_mono_path, 20)
    f_head = ImageFont.truetype(font_bold_path, 30)
    f_sub = ImageFont.truetype(font_mono_path, 20)

    # Top-left LIVE bug + location strip
    draw.rectangle([28, 46, 152, 88], fill=(217, 4, 41, 245))
    draw.ellipse([42, 60, 56, 74], fill=(255, 255, 255, 255))
    draw.text((64, 54), "LIVE", font=f_live, fill=(255, 255, 255, 255))
    draw.rectangle([152, 46, 434, 88], fill=(10, 12, 16, 224))
    draw.text((168, 55), "SF · 2:33 PM", font=f_loc, fill=(232, 238, 245, 255))

    # Lower-third BREAKING NEWS chyron deck
    draw.rectangle([24, 1040, 260, 1080], fill=(217, 4, 41, 245))
    draw.text((40, 1048), "BREAKING NEWS", font=f_tag, fill=(255, 255, 255, 255))
    draw.rectangle([24, 1080, 696, 1204], fill=(9, 12, 18, 232))
    draw.rectangle([24, 1080, 32, 1204], fill=(184, 255, 0, 255))
    draw.text((48, 1096), headline, font=f_head, fill=(255, 255, 255, 255))
    draw.text((48, 1148), subline, font=f_sub, fill=(184, 255, 0, 255))

    img.save(out_png)


def composite_broadcast_beat(beat: dict, wav_path: Path, raw_mp4: Path, out_mux_mp4: Path, *, use_wan_audio: bool = False) -> dict:
    dur = probe_duration(wav_path)
    target_dur = dur + 0.12
    overlay_png = out_mux_mp4.with_suffix(".chyron.png")
    build_chyron_overlay_png(beat["headline"], beat["subline"], overlay_png)

    if use_wan_audio:
        filter_complex = "[0:v]scale=720:1280:force_original_aspect_ratio=increase,crop=720:1280,fps=30[bg];[bg][1:v]overlay=0:0:shortest=1[v]"
        cmd = [
            "ffmpeg", "-y",
            "-i", str(raw_mp4),
            "-loop", "1", "-i", str(overlay_png),
            "-filter_complex", filter_complex,
            "-map", "[v]", "-map", "0:a:0",
            "-t", f"{probe_duration(raw_mp4):.3f}",
            "-c:v", "libx264", "-preset", "fast", "-crf", "18", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", "192k", "-ar", "44100", "-ac", "1",
            str(out_mux_mp4),
        ]
    else:
        filter_complex = (
            "[0:v]scale=720:1280:force_original_aspect_ratio=increase,crop=720:1280,fps=30,"
            "tpad=stop_mode=clone:stop_duration=3[bg];"
            "[bg][2:v]overlay=0:0:shortest=0[v];"
            "[1:a]apad=pad_dur=0.14[a]"
        )
        cmd = [
            "ffmpeg", "-y",
            "-i", str(raw_mp4),
            "-i", str(wav_path),
            "-loop", "1", "-i", str(overlay_png),
            "-filter_complex", filter_complex,
            "-map", "[v]", "-map", "[a]",
            "-t", f"{target_dur:.3f}",
            "-c:v", "libx264", "-preset", "fast", "-crf", "18", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", "192k", "-ar", "44100", "-ac", "1",
            str(out_mux_mp4),
        ]

    proc = subprocess.run(cmd, capture_output=True, text=True)
    if proc.returncode != 0:
        raise RuntimeError(f"ffmpeg composite failed for {beat['id']}: {proc.stderr}")
    return {
        "id": beat["id"],
        "audio_dur": dur,
        "clip_dur": probe_duration(out_mux_mp4),
        "raw_mp4": str(raw_mp4),
        "mux_mp4": str(out_mux_mp4),
    }


def render_wan_beat(beat: dict, wav_path: Path, out_raw_mp4: Path, out_mux_mp4: Path) -> dict:
    dur = probe_duration(wav_path)
    wan_dur = max(4, min(10, int(math.ceil(dur))))

    if not out_raw_mp4.exists() or out_raw_mp4.stat().st_size == 0:
        with tempfile.TemporaryDirectory(prefix=f"wan_{beat['id']}_") as td:
            mp3_path = Path(td) / "beat.mp3"
            subprocess.run(
                ["ffmpeg", "-y", "-i", str(wav_path), "-ac", "1", "-ar", "44100", "-b:a", "160k", str(mp3_path)],
                check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            )
            img_b64 = base64.b64encode(AVATAR_PATH.read_bytes()).decode("ascii")
            mp3_b64 = base64.b64encode(mp3_path.read_bytes()).decode("ascii")

            prompt = (
                "Image 1 is a deadpan, overly serious breaking-news television anchor reporting live from "
                "a sleek dark obsidian cable-news broadcast desk with softly blurred crimson and electric-blue "
                "breaking-news screens behind him. Locked medium close-up camera, crisp newsroom studio key "
                "lighting, natural blinking, stern serious news anchor expression, accurate lip-sync matching Audio 1. "
                f'Spoken dialogue: "{beat["visual_spoken"]}"'
            )

            cmd = [
                str(BL_BIN), "video", "ref",
                "--model", "wan3.0-video",
                "--image", f"data:image/jpeg;base64,{img_b64}",
                "--image-voice", f"data:audio/mpeg;base64,{mp3_b64}",
                "--prompt", prompt,
                "--resolution", "720P",
                "--ratio", "9:16",
                "--duration", str(wan_dur),
                "--watermark", "false",
                "--download", str(out_raw_mp4),
                "--output", "json",
            ]
            proc = subprocess.run(cmd, capture_output=True, text=True)
            if proc.returncode != 0 or not out_raw_mp4.exists():
                raise RuntimeError(f"wan3.0 failed on {beat['id']}: rc={proc.returncode} {proc.stderr} {proc.stdout}")

    return composite_broadcast_beat(beat, wav_path, out_raw_mp4, out_mux_mp4, use_wan_audio=False)


def main() -> int:
    t0 = time.time()
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    codec_pth = Path("/Users/mac/Downloads/voicestudio-models/s2-pro/codec.pth")
    s2_pro_dir = Path("/Users/mac/Downloads/voicestudio-models/s2-pro-hf")
    lora_v2 = Path("/Users/mac/Downloads/voicestudio-models/lora-work-v002/peft_lora_adapter")
    lora_dir = lora_v2 if (lora_v2 / "adapter_model.safetensors").exists() else Path("/Users/mac/Downloads/voicestudio-models/lora-work-v001/peft_lora_adapter")
    dac_v2 = Path("/Users/mac/Downloads/voicestudio-models/bundles/v002/codec_decoder_fp16.tflite")
    dac_tflite = dac_v2 if dac_v2.exists() else Path("/Users/mac/Downloads/voicestudio-models/bundles/v001/codec_decoder_fp16.tflite")

    ref_specs = [
        (
            Path("/Users/mac/Downloads/clone/voice/voice_ref_alt.wav"),
            "Hello, this message is for Matthew Swish and Ranya. Ranya, I've been singing J. Cole all day, thank you, and Matt. Hope you're feeling better, bro. I'm leaving your mail right there.",
        ),
        (
            Path("/Users/mac/Downloads/voicestudio-models/dataset/wavs/ryan_20260908_190501_b54b7715_004.wav"),
            "the model for the specific hardware, like this Dimensity chip in a Huawei 15T is obviously different than the Pixel Titan chips, which we don't know anything about anyways.",
        ),
    ]

    print("[1/5] Encoding reference turns via ModdedDAC...", flush=True)
    ref_turns = []
    for r_wav, r_txt in ref_specs:
        r_codes, r_dur = s2i.encode_reference_audio_to_codes(r_wav, codec_pth=codec_pth, device="mps")
        ref_turns.append((r_txt, r_codes))
        print(f"  ref {r_wav.name}: {r_dur:.2f}s codes={r_codes.shape}", flush=True)

    print(f"[2/5] Loading s2-pro Dual-AR + LoRA ({lora_dir.parent.name}) on MPS...", flush=True)
    model, tokenizer, lora_stats = s2i.load_s2_pro_dual_ar(
        s2_pro_dir,
        lora_dir=lora_dir,
        lora_scale=1.0,
        device="mps",
    )
    print(f"  Loaded Dual-AR + LoRA (tensors={lora_stats.consumed_tensors if lora_stats else 0})", flush=True)

    seeds_per_beat = [948201, 948202, 948203]
    all_gen: list[tuple[dict, int, s2i.GenerationResult]] = []
    for beat in BEATS:
        prompt = s2i.build_s2_pro_prompt_tensor(
            tokenizer,
            beat["spoken"],
            ref_turns=ref_turns,
            prompt_style="multiturn",
        )
        for seed in seeds_per_beat:
            res = s2i.generate_dual_ar_codes(
                model,
                prompt,
                seed=seed,
                max_new_tokens=240,
                temperature=0.72,
                top_p=0.80,
                top_k=30,
            )
            print(
                f"  {beat['id']} seed={seed}: frames={res.generated_frames} "
                f"({res.generated_frames * 2048 / 44100:.2f}s) eos={res.hit_eos} {res.frames_per_sec} fps",
                flush=True,
            )
            all_gen.append((beat, seed, res))

    del model
    if torch.backends.mps.is_available():
        torch.mps.empty_cache()

    print(f"[3/5] Batch-decoding {len(all_gen)} takes via {dac_tflite.name}...", flush=True)
    decoded = s2i.decode_codes_batch_tflite([g.codes for _, _, g in all_gen], tflite_path=dac_tflite)

    master_centroid = sar.load_master_centroid_bin(Path("/Users/mac/Downloads/voicestudio-models/bundles/v001/master_centroid.bin"))
    wespeaker = eva.WeSpeakerEngine(Path("/Users/mac/Downloads/voicestudio-models/wespeaker/wespeaker_en_voxceleb_resnet34.onnx"))
    dnsmos = eva.DnsmosP808Engine(Path("/Users/mac/Downloads/voicestudio-models/dnsmos/model_v8.onnx"))

    by_beat: dict[str, list[dict]] = {b["id"]: [] for b in BEATS}
    pcm_by_key: dict[tuple[str, int], np.ndarray] = {}

    for idx, ((beat, seed, gen_res), (pcm_raw, _meta)) in enumerate(zip(all_gen, decoded)):
        gates = sar.evaluate_candidate_4gates(
            pcm_raw,
            beat["spoken"],
            master_centroid=master_centroid,
            wespeaker=wespeaker,
            dnsmos=dnsmos,
            whisper_bin=WHISPER_BIN,
            whisper_model=WHISPER_MODEL,
        )
        rec = {
            "take_index": idx,
            "seed": seed,
            "generation": {"generated_frames": gen_res.generated_frames, "hit_eos": gen_res.hit_eos},
            "gates": gates,
        }
        by_beat[beat["id"]].append(rec)
        pcm_by_key[(beat["id"], seed)] = pcm_raw
        print(
            f"  score {beat['id']} seed={seed}: CER={gates['cer']:.4f} WeSpeaker={gates['wespeaker_cosine']:.4f} "
            f"DNSMOS={gates['dnsmos_p808']:.3f} text={gates['recognized_text']!r}",
            flush=True,
        )

    beat_wavs: dict[str, Path] = {}
    beat_reports = []
    for beat in BEATS:
        winner = sar.select_best_candidate(by_beat[beat["id"]])
        win_pcm = pcm_by_key[(beat["id"], winner["seed"])]
        mastered_pcm, _ = sar.master_synthesized_pcm(
            win_pcm,
            room_ir_path=Path("/Users/mac/Downloads/voicestudio-models/bundles/v001/room_ir_44k.pcm"),
            room_tone_path=Path("/Users/mac/Downloads/voicestudio-models/bundles/v001/room_tone_44k.pcm"),
            target_lufs=-16.0,
            noise_floor_dbfs=-58.0,
        )
        wav_path = OUT_DIR / f"{beat['id']}_winner.wav"
        sf.write(str(wav_path), np.clip(mastered_pcm, -1.0, 1.0), 44100, subtype="PCM_16")
        beat_wavs[beat["id"]] = wav_path
        beat_reports.append({"id": beat["id"], "spoken": beat["spoken"], "winner": winner})

    print("[4/5] Rendering all 4 720P breaking-news anchor beats in parallel via wan3.0-video...", flush=True)
    rendered = {}
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        futs = {
            pool.submit(
                render_wan_beat,
                beat,
                beat_wavs[beat["id"]],
                OUT_DIR / f"{beat['id']}_wan_raw.mp4",
                OUT_DIR / f"{beat['id']}_broadcast.mp4",
            ): beat["id"]
            for beat in BEATS
        }
        for fut in concurrent.futures.as_completed(futs):
            bid = futs[fut]
            res = fut.result()
            rendered[bid] = res
            print(f"  Rendered {bid}: {res['clip_dur']:.2f}s -> {res['mux_mp4']}", flush=True)

    print("[5/5] Stitching final <40s Golden Gate Hockey Breaking News MP4...", flush=True)
    concat_list = OUT_DIR / "concat.txt"
    concat_list.write_text(
        "\n".join(f"file '{rendered[b['id']]['mux_mp4']}'" for b in BEATS) + "\n",
        encoding="utf-8",
    )
    subprocess.run(
        [
            "ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", str(concat_list),
            "-c:v", "libx264", "-preset", "fast", "-crf", "18", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", "192k", "-ar", "44100", "-ac", "1",
            "-movflags", "+faststart",
            str(FINAL_MP4),
        ],
        check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )

    total_dur = probe_duration(FINAL_MP4)
    subprocess.run(
        [
            "ffmpeg", "-y", "-i", str(FINAL_MP4),
            "-vf", f"fps={6.0 / max(total_dur, 1.0):.4f},scale=270:-1,tile=6x1",
            "-frames:v", "1", str(STRIP_JPG),
        ],
        check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
    )

    # Push directly to plugged-in Pixel 11 Pro XL so Ryan can text it right from his phone
    pixel_pushed = False
    if subprocess.run(["adb", "-s", "67080DLDV009AD", "get-state"], capture_output=True).returncode == 0:
        r = subprocess.run(
            ["adb", "-s", "67080DLDV009AD", "push", str(FINAL_MP4), "/sdcard/Movies/golden_gate_hockey_breaking_news.mp4"],
            capture_output=True, text=True,
        )
        if r.returncode == 0:
            subprocess.run(
                [
                    "adb", "-s", "67080DLDV009AD", "shell",
                    "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Movies/golden_gate_hockey_breaking_news.mp4",
                ],
                capture_output=True,
            )
            pixel_pushed = True

    summary = {
        "final_mp4": str(FINAL_MP4),
        "strip_jpg": str(STRIP_JPG),
        "total_duration_sec": round(total_dur, 2),
        "size_bytes": FINAL_MP4.stat().st_size,
        "lora_used": str(lora_dir),
        "dac_used": str(dac_tflite),
        "pixel_pushed": pixel_pushed,
        "elapsed_sec": round(time.time() - t0, 1),
        "beats": beat_reports,
    }
    (OUT_DIR / "hockey_broadcast_report.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    print(json.dumps({k: v for k, v in summary.items() if k != "beats"}, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
