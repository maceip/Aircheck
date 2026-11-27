# Open TTS models released on Hugging Face since Qwen3-TTS (2026-01-21)

Source: Hugging Face API, pipeline_tag=text-to-speech, 3,136 models pulled, 1,092 original (non-finetune/quant) since 2026-01-21; this table = the 49 most-liked originals.
Params = safetensors count from HF (or model card text if HF has none). 'Cloning' = model card mentions cloning/reference audio (keyword match, NOT verified by running). LiteRT column only lists builds I confirmed exist.

| Released | Model | Params | Likes | Cloning (card) | ONNX (card) | LiteRT build | License | Languages |
|---|---|---|---|---|---|---|---|---|
| 2026-11-16 | [mehdi-hf/cilla-mini](https://huggingface.co/mehdi-hf/cilla-mini) | 28M | 82 | - | - | - | cc-by-nc-4.0 | ['fa'] |
| 2026-11-14 | [n4ze3m/tiny-voca](https://huggingface.co/n4ze3m/tiny-voca) | 4.5M parameters | 443 | yes | yes | - | apache-2.0 | ['en'] |
| 2026-11-14 | [mehdi-hf/cilla-lite](https://huggingface.co/mehdi-hf/cilla-lite) | 110M | 74 | - | - | - | cc-by-nc-4.0 | ['fa'] |
| 2026-11-13 | [KittenML/kitten-tts-2-mini](https://huggingface.co/KittenML/kitten-tts-2-mini) | 82M param | 80 | - | yes | - | apache-2.0 | 16 langs |
| 2026-11-10 | [owensong/Inflect-Mini-v3](https://huggingface.co/owensong/Inflect-Mini-v3) | 2.85M-param | 49 | yes | - | - | apache-2.0 | ['en'] |
| 2026-11-10 | [VoxDance-Lab/VoxMel-0.6B](https://huggingface.co/VoxDance-Lab/VoxMel-0.6B) | unknown | 31 | yes | - | - | apache-2.0 | ['en', 'zh'] |
| 2026-11-09 | [Zyphra/ZONOS3-Mini](https://huggingface.co/Zyphra/ZONOS3-Mini) | 800M param | 665 | yes | - | - | apache-2.0 | 43 langs |
| 2026-11-07 | [Oomni/mithi-v1-flash](https://huggingface.co/Oomni/mithi-v1-flash) | 99.1 M param | 84 | - | - | - | apache-2.0 | 11 langs |
| 2026-11-05 | [ConwayResearch/Underdog-Woof](https://huggingface.co/ConwayResearch/Underdog-Woof) | unknown | 270 | - | yes | - | other | ['en'] |
| 2026-11-01 | [empero-ai/PiVo-1.4M](https://huggingface.co/empero-ai/PiVo-1.4M) | unknown | 61 | yes | yes | - | apache-2.0 | ['en'] |
| 2026-10-19 | [gradium/phonon-40m-en](https://huggingface.co/gradium/phonon-40m-en) | 51M | 137 | - | - | - | cc-by-nc-4.0 | ['en'] |
| 2026-10-19 | [gradium/phonon-31m-en](https://huggingface.co/gradium/phonon-31m-en) | 41M | 184 | yes | - | - | apache-2.0 | ['en'] |
| 2026-10-14 | [Edge0/Audio8-TTS-Realistic-Air](https://huggingface.co/Edge0/Audio8-TTS-Realistic-Air) | 601M | 487 | yes | yes | yes (litert-community, Pixel 11 Pro RTF 0.85) | apache-2.0 | 23 langs |
| 2026-10-08 | [cloud0day3/antalia-mini](https://huggingface.co/cloud0day3/antalia-mini) | 7.62M param | 57 | yes | yes | - | apache-2.0 | ['tr'] |
| 2026-10-01 | [fishaudio/drama-3](https://huggingface.co/fishaudio/drama-3) | 35900M | 1340 | yes | - | - | other | 83 langs |
| 2026-10-01 | [canberkkkkkk/ema-lightning](https://huggingface.co/canberkkkkkk/ema-lightning) | 5.6M-param | 470 | yes | - | - | apache-2.0 | ['tr'] |
| 2026-09-30 | [KittenML/kitten-tts-2](https://huggingface.co/KittenML/kitten-tts-2) | 324M parameters | 227 | yes | - | - | other | None |
| 2026-09-22 | [inclusionAI/Ming-3.0-tts-tiny](https://huggingface.co/inclusionAI/Ming-3.0-tts-tiny) | 8981M | 63 | yes | - | - | mit | 7 langs |
| 2026-09-22 | [inclusionAI/Ming-3.0-tts-0.5B](https://huggingface.co/inclusionAI/Ming-3.0-tts-0.5B) | 1479M | 241 | yes | - | yes (litert-community, GPU+NPU on S26) | mit | 7 langs |
| 2026-08-25 | [samuel-vitorino/sopro-v2-turbo](https://huggingface.co/samuel-vitorino/sopro-v2-turbo) | 122M | 56 | yes | yes | - | apache-2.0 | ['en', 'pt', 'fr', 'de'] |
| 2026-08-25 | [BreezeBlue/Breeze-TTS-2](https://huggingface.co/BreezeBlue/Breeze-TTS-2) | 3466M | 1012 | yes | - | - | other | ['en', 'zh'] |
| 2026-08-19 | [Edge0/Audio8-TTS-Preview-0.1b](https://huggingface.co/Edge0/Audio8-TTS-Preview-0.1b) | 170M | 248 | yes | yes | - | other | 8 langs |
| 2026-08-18 | [tencent/AuK](https://huggingface.co/tencent/AuK) | unknown | 396 | yes | - | - | mit | None |
| 2026-08-15 | [zeroweight-ai/ZeroTTS](https://huggingface.co/zeroweight-ai/ZeroTTS) | unknown | 56 | yes | yes | - | mit | ['vi'] |
| 2026-08-10 | [IndexTeam/IndexTTS-2.5](https://huggingface.co/IndexTeam/IndexTTS-2.5) | unknown | 281 | yes | - | - | other | ['zh', 'en', 'ja', 'es', 'ar'] |
| 2026-07-28 | [Edge0/Audio8-TTS-Preview-0.6b](https://huggingface.co/Edge0/Audio8-TTS-Preview-0.6b) | 601M | 436 | yes | yes | yes (litert-community) | apache-2.0 | 11 langs |
| 2026-07-21 | [neuphonic/neutts-2e](https://huggingface.co/neuphonic/neutts-2e) | 236M | 48 | - | - | - | apache-2.0 | ['en'] |
| 2026-07-07 | [freyavoice/Freya-TTS](https://huggingface.co/freyavoice/Freya-TTS) | 183M | 59 | yes | - | - | apache-2.0 | ['tr'] |
| 2026-06-25 | [owensong/Inflect-Nano-v2](https://huggingface.co/owensong/Inflect-Nano-v2) | 4M param | 140 | yes | yes | - | apache-2.0 | ['en'] |
| 2026-06-25 | [owensong/Inflect-Micro-v2](https://huggingface.co/owensong/Inflect-Micro-v2) | 10M param | 453 | yes | yes | - | apache-2.0 | ['en'] |
| 2026-06-16 | [owensong/Inflect-Nano-v1](https://huggingface.co/owensong/Inflect-Nano-v1) | 4.63M param | 226 | yes | - | - | apache-2.0 | ['en'] |
| 2026-06-11 | [Zyphra/ZONOS2](https://huggingface.co/Zyphra/ZONOS2) | unknown | 162 | yes | - | - | apache-2.0 | None |
| 2026-06-04 | [bosonai/higgs-tts-3-4b](https://huggingface.co/bosonai/higgs-tts-3-4b) | 4655M | 861 | yes | - | - | other | 100 langs |
| 2026-06-03 | [dots-studio/dots.tts-base](https://huggingface.co/dots-studio/dots.tts-base) | 2198M | 54 | yes | - | - | apache-2.0 | None |
| 2026-05-28 | [ResembleAI/chatterbox-flash](https://huggingface.co/ResembleAI/chatterbox-flash) | unknown | 34 | yes | - | - | mit | ['en'] |
| 2026-05-25 | [OpenMOSS-Team/MOSS-TTS-v1.5](https://huggingface.co/OpenMOSS-Team/MOSS-TTS-v1.5) | 8490M | 224 | yes | - | - | apache-2.0 | 31 langs |
| 2026-05-21 | [MisoLabs/MisoTTS](https://huggingface.co/MisoLabs/MisoTTS) | 8188M | 233 | yes | - | - | other | None |
| 2026-05-21 | [KRAFTON/Raon-OpenTTS-1B](https://huggingface.co/KRAFTON/Raon-OpenTTS-1B) | unknown | 82 | yes | - | - | cc-by-nc-4.0 | ['en'] |
| 2026-05-06 | [Supertone/supertonic-3](https://huggingface.co/Supertone/supertonic-3) | 99M param | 1058 | yes | yes | - | openrail | 31 langs |
| 2026-04-26 | [ScenemaAI/scenema-audio](https://huggingface.co/ScenemaAI/scenema-audio) | 22B param | 149 | yes | - | - | other | 13 langs |
| 2026-04-14 | [ResembleAI/chatterbox-nano](https://huggingface.co/ResembleAI/chatterbox-nano) | 110M param | 51 | yes | - | - | mit | ['en'] |
| 2026-04-03 | [openbmb/VoxCPM2](https://huggingface.co/openbmb/VoxCPM2) | 2290M | 1821 | yes | - | - | apache-2.0 | 30 langs |
| 2026-04-02 | [OpenMOSS-Team/MOSS-TTS-Nano-100M](https://huggingface.co/OpenMOSS-Team/MOSS-TTS-Nano-100M) | 0.1B param | 256 | yes | - | - | apache-2.0 | 20 langs |
| 2026-04-01 | [KRAFTON/Raon-OpenTTS-0.3B](https://huggingface.co/KRAFTON/Raon-OpenTTS-0.3B) | unknown | 25 | yes | - | - | cc-by-nc-4.0 | ['en'] |
| 2026-03-09 | [fishaudio/s2-pro](https://huggingface.co/fishaudio/s2-pro) | 4562M | 1533 | - | - | - | other | 83 langs |
| 2026-02-09 | [OpenMOSS-Team/MOSS-TTS-Realtime](https://huggingface.co/OpenMOSS-Team/MOSS-TTS-Realtime) | 2332M | 122 | yes | - | - | apache-2.0 | 20 langs |
| 2026-02-06 | [OpenMOSS-Team/MOSS-TTS](https://huggingface.co/OpenMOSS-Team/MOSS-TTS) | 8490M | 446 | yes | - | - | apache-2.0 | 20 langs |
| 2026-01-22 | [YatharthS/LuxTTS](https://huggingface.co/YatharthS/LuxTTS) | unknown | 212 | yes | - | - | apache-2.0 | ['en'] |
| 2026-01-21 | [Qwen/Qwen3-TTS-12Hz-0.6B-Base](https://huggingface.co/Qwen/Qwen3-TTS-12Hz-0.6B-Base) | 915M | 379 | yes | - | yes (.litertlm, GPU/CPU) | apache-2.0 | 10 langs |
