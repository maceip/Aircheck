# Aircheck Bare-Metal On-Device Synthesis & Self-Distillation Architecture

This specification grounds the closed-loop bare-metal neural speech synthesis, 4-gate C++ verification, 48kHz RVC v2 + Room IR resynthesis, Hairline Audio/Video Clipper, and nightly GRPO/OPSD self-distillation initiative in the existing `/Users/mac/Aircheck` repository (AGP `9.1.1`, Kotlin `2.4.21`, `compileSdk = 37`, Nav3 `1.2.2`, Room WAL `2.8.5`, Media3 `1.11.1`).

---

## 1. End-to-End Architecture & Dataflow

```text
[Home / Cloud Training Rig (8x H100 or Local Server)]
   │  1. Full-SFT + GRPO + On-Policy Self-Distillation (OPSD)
   │  2. litert-torch / ai-edge-quantizer export -> bundle_manifest.json
   ▼
[OTA Model Bundle (.litertlm / .tflite + RVC .onnx + room_ir + lexicon.json)]
   │  (Synced over Tailscale / WireGuard into Android noBackupFilesDir/bundles/{ver}/)
   ▼
┌─────────────────────────────────────────────────────────────────────────┐
│ ANDROID 17 CLIENT (16 GB RAM Budget / ~9.5 GB Foreground Ceiling)       │
│                                                                         │
│  [1. Script Input & Deterministic G2P Normalizer]                       │
│         │  Splits draft into ClauseNodes (<15s each)                    │
│         ▼                                                               │
│  [2. Async Synthesis Engine (WorkManager Expedited / C++ JNI)]          │
│         ├─► Stage A: Heavy Prosody Generator (N=4 takes per clause)     │
│         │     (Ming-3.0-tts-tiny MoE or Fish-S2-Pro-4B / VoxCPM2)       │
│         ├─► Stage B: 4-Gate C++ Verifier & Reranker (~350 MB RAM)       │
│         │     (CTC CER == 0.0 + WeSpeaker Sim + UTMOSv2 + Syllable Rate)│
│         └─► Stage C: 48kHz RVC v2 Harmonic Resynthesis + Room IR        │
│                 │                                                       │
│                 ▼  Emits 48kHz Float32 PCM + Zero-Crossing Token Map    │
│  [3. Hairline Audio/Video Clipper (Jetpack Compose + Media3 + Oboe)]    │
│         ├─► Fixed Center Blade + Dual-Speed Vernier Tick Scrubber       │
│         ├─► Word-Pill Take Swapper (Take #1 ◄─► Take #2/3)              │
│         └─► Global Gap-Compression Rail + Media3 Video Beat Anchors     │
│                 │                                                       │
│                 ▼  Logs implicit preference pairs (Chosen vs. Rejected) │
│  [4. Telemetry & Preference Sync Worker]                                │
└─────────────────┼───────────────────────────────────────────────────────┘
                  │  Uploads JSONL preference tuples + edits on Wi-Fi
                  ▼
[Home / Cloud Rig: Nightly Self-Distillation & GRPO Adapter Update]
```

---

## 2. Frozen Wave-0 Contracts (Single Source of Truth)

All Kotlin, C++, and Python workers MUST conform strictly to these already-committed contracts:

- **JSON Schemas**:
  - `docs/swarm/schemas/clause_node.schema.json`
  - `docs/swarm/schemas/ota_bundle_manifest.schema.json`
  - `docs/swarm/schemas/preference_tuple.schema.json`
- **Kotlin Wire & Persistence Models**:
  - `app/src/main/java/com/example/data/synthesis/SynthesisContracts.kt`
- **Kotlin Subsystem Interfaces**:
  - `app/src/main/java/com/example/data/synthesis/SynthesisInterfaces.kt`
- **Contract Verification Test**:
  - `app/src/test/java/com/example/SynthesisContractsTest.kt`

### Audio Format Standard
- **Sample Rate**: `48,000 Hz` mono (`SYNTHESIS_SAMPLE_RATE_HZ = 48_000`)
- **Sample Encoding**: 32-bit IEEE Float little-endian (`PCM_48K_F32`, `.pcm`)
- **Acoustic Prefix Tail**: Last `1,500 ms` (`72,000` Float32 samples) of the winning take from clause `k - 1`, written to `takes/{prev_clause_id}_winner_tail_1500ms.pcm` and passed as `acoustic_prefix_uri` to clause `k`.
- **Sample-Accurate Boundaries**: Every `ZeroCrossingToken` stores `start_sample`, `end_sample`, `zero_cross_in`, `zero_cross_out`, `f0_peak_sample`, and optional `video_anchor_id`.

---

## 3. Android 17 Memory Budget & Native Build Mandates

### Local Build Toolchain (Verified Installed)
- `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`
- **NDK**: `28.2.13676358` (fallback `27.3.13750724`)
- **CMake**: `3.22.1`
- **16 KB Page Alignment (Android 15/16/17 Mandatory)**: All native shared libraries built via CMake MUST link with `-Wl,-z,max-page-size=16384`.
- **Oboe Audio**: Consume via Android Gradle Prefab (`buildFeatures { prefab = true }`, `com.google.oboe:oboe`).

### Strict Memory Ceiling Enforcement (`16 GB` Device / `<= 9.5 GB` Foreground Ceiling)
1. **Sequential Stage Residency (Never Co-Resident)**:
   - Stage A (`prosody_generator`: `.litertlm` / `.tflite`) runs to emit `N = 4` candidate takes per clause, then **explicitly closes** (`conversation.close()` -> `engine.close()` under single-thread Mutex) before Stage B loads.
   - Stage B (`ctc_verifier` + `wespeaker_verifier` + `speaker_centroid` + `utmos_verifier`) MUST remain `<= 350 MB` total RSS using file-path `mmap` + `xnnPackFlags = 0x23` + versioned `xnnPackWeightCachePath` in `codeCacheDir`, then closes before Stage C loads.
   - Stage C (`rvc_v2_resynth` ONNX + `room_ir` partitioned FFT convolver) resynthesizes candidates to 48kHz Float32 PCM, extracts zero-crossing/F0 token maps and 1500ms winner tails, then unloads immediately.
2. **Never Pack Models into the APK**:
   - Zero `.litertlm`, `.tflite`, `.onnx`, or Room IR binary weights may be placed in `app/src/main/assets/` or bundled inside the APK.
   - All model files live under `context.noBackupFilesDir/bundles/{bundle_version}/` verified by SHA-256 against `bundle_manifest.json` using atomic `.tmp` -> rename installs.
3. **LiteRT & LiteRT-LM Skill Mandates**:
   - Bundled LiteRT: `com.google.ai.edge.litert:litert:2.3.0` + `com.google.ai.edge.litert:litert-gpu:2.3.0` (never legacy `org.tensorflow:tensorflow-lite*`).
   - LiteRT-LM: `com.google.ai.edge.litertlm:litertlm-android:0.18.0` + `libvndksupport.so` and `libOpenCL.so` (`android:required="false"`) inside `<application>` in `AndroidManifest.xml`.
   - Single-thread background executor owns every `CompiledModel` / `Engine` lifecycle (init, infer, close). Reuse `TensorBuffer`s; close buffers before `CompiledModel.close()`.

---

## 4. Non-Negotiable Engineering & Constitutional Rules

1. **Zero Fabrication / Zero Dead Controls (Constitutional Law 2)**:
   - Never fabricate synthetic sine waves pretending to be neural TTS output, never fake `cer = 0.0` / `utmos_score = 4.42` / `speaker_cosine_sim = 0.914` when models aren't loaded, and never silently swallow missing OTA bundles.
   - If the OTA model bundle is not synced on device, display real `OtaBundleStatus.NOT_INSTALLED` state with real Tailscale sync controls and fail synthesis cleanly with an actionable error.
   - Real DSP tests (C++ zero-crossing detector, F0 peak finder, FFT Room IR convolution, Oboe splicer, G2P normalizer, Room WAL persistence) run against real deterministic audio buffers in unit/instrumentation tests—never fake production code paths.
2. **UI Label Density Limit**:
   - Never stack more than **2 adjacent labels** next to any single primary label (strict <= 2:1 ratio on Word-Pill Take Swapper chips, verifier badges, and Clipper controls).
3. **Continuous Atomic Commits & Push (Constitutional Law 3)**:
   - Every worker commits atomically on its worktree branch; coordinator merges and pushes continuously to `origin`.
4. **No Local Android Emulators**:
   - Verification MUST run on a physical USB phone (`adb`) or Limrun remote Android (`lim` with `LIM_API_KEY` from `~/trap/.env`). Never launch local Android emulators.
