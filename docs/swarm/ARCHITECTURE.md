# Aircheck / VoiceStudio Rectified Bare-Metal Architecture (`SPEC.md` Grounded)

This architecture incorporates all 3 critical rectifications over the initial draft and grounds the 5-milestone `VoiceStudio` architecture cleanly inside `/Users/mac/Aircheck` (AGP `9.1.1`, Kotlin `2.4.21`, `compileSdk = 37`, Nav3 `1.2.2`, Room WAL `2.8.5`, Media3 `1.11.1`, NDK `28.2.13676358`, CMake `3.22.1`).

---

## 1. The 3 Rectified Architectural Decisions (Non-Negotiable)

1. **`s2-pro-GGUF` (`llama.cpp` JNI) + LiteRT 44.1 kHz DAC Decoder (NO `.litertlm` / NO Runtime `.tflite` LoRA)**:
   - LiteRT flatbuffers have static weight topologies; runtime LoRA hot-swapping is NOT done in `.tflite`/`.litertlm`.
   - Stage A runs the 4B Dual-AR discrete-token backbone (`fishaudio/s2-pro` via `ggml-org/s2-pro-GGUF` in `llama.cpp` C++20 JNI) with **1-line atomic C++ LoRA hot-swapping** (`llama_adapter_lora_init()` / `llama_set_adapter_lora()`).
   - Discrete audio codebook token IDs decode via a single fine-tuned 44.1 kHz DAC vocoder (`codec_decoder_fp16.tflite`, ~262 MB) in **LiteRT `CompiledModel`** (`com.google.ai.edge.litert:litert:2.3.0` + `litert-gpu:2.3.0` / C API) and feed straight into `thinking-machines-lab/tinker-cookbook` OPSD + GRPO without custom diffusion math.
2. **Removed Redundant 48 kHz RVC v2 Pass -> 5ms Deterministic C++20 NEON DSP Mastering**:
   - Fine-tuning the native 44.1 kHz DAC decoder once on raw dry studio recordings eliminates RMVPE + RVC v2 double phase filtering and saves ~4s/clause.
   - Stage C is a deterministic 5ms C++20 NEON pass (`dsp_mastering.cc`): 12ms studio Room IR convolution + `-16 LUFS` loudness norm + `-58 dBFS` studio noise floor + `±88`-sample (`±2ms @ 44.1 kHz`) zero-crossing scanner writing 64-byte-aligned headerless **44.1 kHz Float32 mono `.pcm`** files.
3. **Swapped UTMOSv2 -> `sherpa-onnx` CTC Forced Aligner + `WeSpeaker ResNet34` (25 MB) + Microsoft `DNSMOS p808` (4 MB)**:
   - Eliminates wav2vec2 SSL latency/RAM bloat.
   - Stage B runs 4 fast deterministic/ONNX gates (`verifier_gates.cc`):
     1. `sherpa-onnx` CTC Forced Aligner: `cer == 0.0f` + word `[startSample, endSample]` + `maxPhonemeDurationMs <= 380.0f`.
     2. `WeSpeaker ResNet34` (25 MB ONNX): `speakerCosineSim >= 0.86f` vs 256-float `master_centroid.bin`.
     3. Microsoft `DNSMOS p808` (4 MB ONNX, `<15ms`): `dnsmosP808 >= 4.10f` + final 80ms `tailRmsDb <= -45.0f` dBFS / spectral flux check.
     4. Cadence gate: `syllablesPerSec` in `4.2f..6.1f`.

---

## 2. Rectified System Topology

```text
[Home / Cloud Training Rig]
   │  1. Base SFT on studio audio (s2-pro-4B + 44.1kHz DAC Decoder)
   │  2. Nightly Tinker Cookbook Loop (Discrete-Token OPSD + GRPO)
   │     trains ONLY a ~35 MB Rank-32 GGUF LoRA Adapter
   ▼
[OTA Bundle Synced over Tailscale / HTTP into Android noBackupFilesDir]
   ├── s2_pro_4b_q4_k_m.gguf        (Static base, synced once: ~2.6 GB)
   ├── codec_decoder_fp16.tflite    (Static fine-tuned vocoder: ~262 MB)
   ├── voice_lora_vXXX.gguf         (Nightly hot-swap delta: ~35 MB)
   ├── sherpa-onnx CTC + wespeaker_resnet34.onnx (25 MB) + master_centroid.bin + dnsmos_p808.onnx (4 MB)
   ├── room_ir_44k.pcm + room_tone_44k.pcm (-58 dBFS)
   └── lexicon.json                 (Deterministic IPA phoneme overrides)
   ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ ANDROID 17 CLIENT (API 37 | Pixel 11 Pro XL 16 GB RAM | arm64-v8a)      │
│                                                                         │
│  [1. Script Input & Deterministic G2P Normalizer (:core-engine Kotlin)] │
│         │  Applies lexicon.json -> splits draft into ClauseNodes (<14s) │
│         ▼                                                               │
│  [2. Async Synthesis Engine (WorkManager Expedited -> C++20 JNI)]       │
│         ├─► Stage A: Dual-AR Token Rollout (llama.cpp JNI + LoRA)       │
│         │     N=4 discrete audio-token rollouts -> LiteRT DAC (44.1kHz) │
│         ├─► Stage B: 4-Gate C++ Verifier (:core-verifier)               │
│         │     CTC (CER==0.0, <=380ms) + WeSpeaker (>=0.86)              │
│         │     + DNSMOS p808 (>=4.10, tail <=-45dBFS) + Cadence (4.2-6.1)│
│         └─► Stage C: 5ms C++20 NEON DSP Mastering & Zero-Cross Scanner  │
│               12ms Room IR + -16 LUFS + -58 dBFS + +/-88 zero-crossing  │
│                 │                                                       │
│                 ▼  Emits 64B-aligned mmap 44.1kHz Float32 .pcm + JSON   │
│  [3. Hairline Clipper (:audio-edl + :ui-clipper)]                       │
│         ├─► Wait-Free Oboe EDL Ring Buffer (std::atomic<EdlSnapshot*>)  │
│         │     15ms (660-sample) equal-power cosine x-fade + room tone   │
│         ├─► Fixed Center Blade + 1:20 Gear-Ratio Vernier Strip          │
│         ├─► Monospace WordPill Take Swapper (0ms swap, tap re-queue)    │
│         └─► GapCompressionRail (100%->25%) + Media3 Transformer Anchors │
│                 │                                                       │
│                 ▼  Logs chosen/rejected discrete token IDs + edits      │
│  [4. Preference Sync Worker (:sync-trainer/android-worker)]             │
└─────────────────┼───────────────────────────────────────────────────────┘
                  │  Uploads preferences.jsonl over Tailscale/Wi-Fi
                  ▼
[Home / Cloud Rig (:sync-trainer/home-daemon/tinker_voice_daemon.py)]
   └─► SingleSpeakerAudioEnv (OPSD + GRPO) -> voice_lora_vXXX.gguf (~35 MB)
```

---

## 3. Multi-Module Layout & Frozen Wave-0 Contracts

- **Gradle Modules** added to `/Users/mac/Aircheck/settings.gradle.kts` alongside `:app`:
  - `:core-engine` (Milestone 1 — `llama.cpp`, LiteRT DAC, NEON DSP, `ClauseNormalizer`)
  - `:core-verifier` (Milestone 2 — `sherpa-onnx`, `WeSpeaker ResNet34`, `DNSMOS p808`)
  - `:audio-edl` (Milestone 3 — Oboe wait-free double-buffered EDL + canonical `ClauseNode.kt`)
  - `:ui-clipper` (Milestone 4 — Compose Vernier Clipper, `WordPill` swapper, `GapCompressionRail`, `Media3BeatExporter`)
  - `:sync-trainer:android-worker` (Milestone 5 Android — `PreferenceSyncWorker` + atomic `HotSwapLora`)
- **Python Rig**: `sync-trainer/home-daemon/` (`tinker_voice_daemon.py`, `ota_server.py`, `export_lora_gguf.sh`)
- **Frozen Wave-0 Files**:
  - `docs/swarm/schemas/clause_node.schema.json`
  - `docs/swarm/schemas/ota_bundle_manifest.schema.json`
  - `docs/swarm/schemas/preference_tuple.schema.json`
  - `app/src/main/java/com/example/data/synthesis/SynthesisContracts.kt`
  - `app/src/main/java/com/example/data/synthesis/SynthesisInterfaces.kt`
  - `app/src/test/java/com/example/SynthesisContractsTest.kt`

---

## 4. Android 17 Native Build, Memory & Constitutional Mandates

- **Toolchain (Verified Local)**: `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`, NDK `28.2.13676358`, CMake `3.22.1`.
- **16 KB Alignment**: Link every Android `.so` with `-Wl,-z,max-page-size=16384`.
- **Cross-Platform CMake Flag Guard**: Apply `-march=armv9-a+simd+fp16` ONLY on `ANDROID` + `arm64-v8a` so host macOS/Linux CTest suites compile cleanly.
- **Zero Models in APK**: `.gguf`, `.tflite`, `.onnx`, `.bin`, `.pcm` models live ONLY under `context.noBackupFilesDir/bundles/` verified by SHA-256 + atomic `.tmp` rename. Never bundle models in `assets/`.
- **LiteRT Mandates (`litert-runtime` skill)**: Use `com.google.ai.edge.litert:litert:2.3.0` + `litert-gpu:2.3.0`, single-thread confined executor, reusable `TensorBuffer`s closed before `CompiledModel.close()`, `xnnPackFlags = 0x23` with versioned `xnnPackWeightCachePath` in `codeCacheDir`, GPU-to-CPU fallback.
- **Memory Eviction**: Call `VoiceEngine::EvictWeightPages()` (`madvise(..., MADV_DONTNEED)`) when synthesis queue goes idle; monitor `/proc/self/status` `VmRSS` to stay strictly under `9,500 MB` foreground ceiling.
- **Constitutional Law 2 (Zero Fabrication)**: Never emit fake audio buffers, hardcoded gate metrics (`cer=0.0`, `dnsmos=4.42`), or fake LoRA hot-swaps in production paths. Missing OTA files MUST surface real actionable sync UI states.
- **UI Label Density Limit**: Max 2 adjacent labels per primary label in every Compose component (`WordPill`, clipper status bar, `SystemDeckSheet`).
- **No Local Emulators**: Run all device gates on USB phone (`adb`) or Limrun remote Android (`LIM_API_KEY` in `~/trap/.env`).
