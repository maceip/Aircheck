# Ready-to-Paste Swarm Launch Command (Rectified `VoiceStudio` Spec)

Copy and paste the block below into chat (with `/teamwork`) to launch the rectified 5-milestone initiative immediately:

```text
/teamwork Launch the rectified Aircheck / VoiceStudio Local Single-Speaker Voice Studio initiative:
- Stage A: s2-pro-4B GGUF via llama.cpp JNI + atomic runtime 35MB Rank-32 GGUF LoRA hot-swap (`llama_adapter_lora_init`) + 44.1 kHz fine-tuned DAC vocoder (`codec_decoder_fp16.tflite`) in LiteRT CompiledModel (NO .litertlm / NO runtime .tflite LoRA).
- Stage B: 4-Gate C++ Verifier via sherpa-onnx CTC Forced Aligner (CER == 0.0, max phoneme <= 380ms) + WeSpeaker ResNet34 (25MB ONNX, cos >= 0.86) + Microsoft DNSMOS p808 (4MB ONNX, >= 4.10 + tail RMS <= -45 dBFS) + cadence (4.2..6.1 syl/s) (NO UTMOSv2).
- Stage C: Deterministic 5ms C++20 NEON DSP mastering (12ms Room IR, -16 LUFS, -58 dBFS room floor, +/-88 sample zero-crossing scan) emitting 64-byte-aligned headerless 44.1 kHz Float32 mono .pcm (NO redundant 48kHz RVC v2).
- Clipper: Wait-free Oboe (AAudio) double-buffered `std::atomic<EdlSnapshot*>` ring buffer (15ms / 660-sample equal-power cosine crossfade over room tone, zero disk stitching) + Jetpack Compose Vernier Clipper (fixed center razor blade, 1:20 gear ratio, monospace WordPill take swapper, vertical GapCompressionRail 100%->25%, Media3 Transformer beat anchors).
- Closed-Loop Rig: WorkManager `preferences.jsonl` upload over Tailscale -> `thinking-machines-lab/tinker-cookbook` `SingleSpeakerAudioEnv` (OPSD + GRPO) -> `convert_lora_to_gguf.py` -> 35MB `voice_lora_vXXX.gguf` hot-swapped live via `nativeHotSwapLora()`.

Read these frozen specs and contracts FIRST before spawning any worker:
1. /Users/mac/Aircheck/docs/swarm/ARCHITECTURE.md
2. /Users/mac/Aircheck/docs/swarm/SWARM_PLAN.md
3. /Users/mac/Aircheck/docs/swarm/schemas/clause_node.schema.json
4. /Users/mac/Aircheck/docs/swarm/schemas/ota_bundle_manifest.schema.json
5. /Users/mac/Aircheck/docs/swarm/schemas/preference_tuple.schema.json
6. /Users/mac/Aircheck/app/src/main/java/com/example/data/synthesis/SynthesisContracts.kt
7. /Users/mac/Aircheck/app/src/main/java/com/example/data/synthesis/SynthesisInterfaces.kt

Swarm Execution & Blocking Gate Rules:
- Load `swarm_pane` skill and write `plan.json` immediately after spawning Wave 1.
- Follow the 3-wave DAG, module ownership matrix, and 5 hard verification gates in `docs/swarm/SWARM_PLAN.md`:
  * Wave 1 (parallel): `build-scaffold` (share: multi-module Gradle + NDK 28.2.13676358 + 16KB alignment + Oboe Prefab + LiteRT 2.3.0 + ONNX Runtime + CMake skeletons) + `sync-daemon` (share: `sync-trainer/home-daemon/` tinker-cookbook OPSD/GRPO + GGUF LoRA exporter + Tailscale HTTP server + Gate 5 `EndToEndLoopTest.py`). Merge `build-scaffold` before spawning Wave 2.
  * Wave 2 (4-way parallel, `workspace: "share"`, strict module ownership):
    - `core-engine` (M1 / Gate 1: `:core-engine`, `EngineParityTest.cc` host + arm64)
    - `core-verifier` (M2 / Gate 2: `:core-verifier`, `VerifierGateTest.kt`)
    - `audio-edl` (M3 / Gate 3: `:audio-edl`, `EdlGlitchTest.kt` asserting XRun == 0 and max derivative < 0.02f)
    - `ui-clipper` (M4 / Gate 4: `:ui-clipper`, `TimelineSyncTest.kt` asserting <1ms Media3 beat anchor sync)
  * Wave 3: `sync-integrator` (share: `sync-trainer/android-worker/` + `:app` Nav3/Room/DI/SystemDeck wiring) -> `verifier` (inherit: full 5-gate verification, release APK 16KB/zero-bundled-model audit via `android analyze`, Compose E2E + screenshots).
- Quota guard: use default/Gemini models (97% quota); do NOT assign 0%-quota Claude or GPT-OSS models.
- Hard constraints:
  * ZERO local Android emulators — verify on physical USB phone (`adb`) or Limrun remote Android (`LIM_API_KEY` in `~/trap/.env`, delete instance afterward).
  * Constitutional Law 2: ZERO fabricated TTS audio, ZERO hardcoded gate metrics, ZERO dead UI controls; missing OTA GGUF/TFLite/ONNX files must fail cleanly with real Tailscale sync UX.
  * Max 2 adjacent labels per label in every Compose component.
  * Constitutional Law 3: Commit and push continuously to `origin` after every wave merges.
```
