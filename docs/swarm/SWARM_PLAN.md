# Rectified Swarm Execution Plan (`VoiceStudio` on Aircheck — 5 Milestones, 5 Hard Gates)

## 1. Execution Topology & Wave DAG

This plan maps the rectified **5-Milestone `SPEC.md`** onto **8 specialized swarm workers across 3 waves** with strict per-module directory ownership so parallel `workspace: "share"` workers compile, test, and pass their standalone verification gates without merge conflicts.

```text
Wave 0 (DONE & PUSHED ON main):
  - Frozen JSON Schemas: docs/swarm/schemas/*.json (44.1kHz ClauseNode, GGUF/LiteRT/ONNX OTA manifest, preferences.jsonl)
  - Frozen Kotlin Contracts & Interfaces: SynthesisContracts.kt, SynthesisInterfaces.kt
  - Contract & Media3 Math Tests: SynthesisContractsTest.kt (green)

Wave 1 (Parallel immediately):
  ├─► [build-scaffold]  (share)  settings.gradle.kts, libs.versions.toml, module Gradle/CMake skeletons, NDK 28 + 16KB align
  └─► [sync-daemon]     (share)  sync-trainer/home-daemon/** (tinker-cookbook OPSD+GRPO + convert_lora_to_gguf + Tailscale HTTP)
        │
        ▼  (Merge build-scaffold into coordinator branch before launching Wave 2!)

Wave 2 (4-Way Parallel Milestone Build — zero overlapping module paths):
  ├─► [core-engine]     (share)  Milestone 1 -> Gate 1 (`EngineParityTest.cc`)
  ├─► [core-verifier]   (share)  Milestone 2 -> Gate 2 (`VerifierGateTest.kt`)
  ├─► [audio-edl]       (share)  Milestone 3 -> Gate 3 (`EdlGlitchTest.kt`)
  └─► [ui-clipper]      (share)  Milestone 4 -> Gate 4 (`TimelineSyncTest.kt`)
        │
        ▼  (Merge all 4 passing Gate 1-4 branches + sync-daemon into coordinator branch)

Wave 3 (App Integration & Full 5-Gate Real-Device Verification):
  ├─► [sync-integrator] (share)  sync-trainer/android-worker/** + :app Nav3/Room/DI/ViewModel/SystemDeck wiring
  └─► [verifier]        (inherit) Gate 1-5 audit + 16KB/zero-model APK analysis + Limrun/USB Compose E2E + screenshots
```

---

## 2. Exclusive Ownership Matrix (Zero File Collisions)

| Worker | Milestone / Gate | Workspace | Exclusive Owned Paths |
|---|---|---|---|
| `build-scaffold` | Prep | `share` | `settings.gradle.kts`, `gradle/libs.versions.toml`, `build.gradle.kts`, initial module `*/build.gradle.kts` & `CMakeLists.txt` skeletons |
| `sync-daemon` | M5 (Rig) / Gate 5a | `share` | `sync-trainer/home-daemon/**` |
| `core-engine` | M1 / Gate 1 | `share` | `core-engine/**` |
| `core-verifier` | M2 / Gate 2 | `share` | `core-verifier/**` |
| `audio-edl` | M3 / Gate 3 | `share` | `audio-edl/**` |
| `ui-clipper` | M4 / Gate 4 | `share` | `ui-clipper/**` |
| `sync-integrator` | M5 (Phone) + Glue | `share` | `sync-trainer/android-worker/**`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/example/AircheckApplication.kt`, `app/src/main/java/com/example/data/local/AircheckDatabase.kt`, `app/src/main/java/com/example/navigation/AircheckNavigation.kt`, `app/src/main/java/com/example/MainActivity.kt`, `app/src/main/java/com/example/ui/StudioViewModel.kt`, `app/src/main/java/com/example/ui/screens/SystemDeckSheet.kt`, `app/src/main/java/com/example/ui/screens/ScriptScreen.kt` |
| `verifier` | Gates 1–5 E2E | `inherit` | `app/src/androidTest/java/com/example/VoiceStudioEndToEndTest.kt` + live device/Limrun screenshots & audit |

---

## 3. Quota-Aware Model Selection

Checked via `swarm(action="models")` on 2026-11-22:
- **Use default / Gemini 4 models (`97%` quota remaining)**: leave `model` unset (inherits coordinator) or assign `gemini-4-carbon-high` / `gemini-4-argon-high` for C++20/ML workers (`core-engine`, `core-verifier`, `audio-edl`, `sync-daemon`) and `gemini-3.8-flash-high` for `build-scaffold` / `verifier`.
- **Do NOT assign until quota resets (2026-11-25)**: `claude-opus-5-5-*`, `claude-sonnet-5-5-*`, `gpt-oss-120b-medium` (`0%` quota).

---

## 4. Swarm UI Overview (`plan.json`)

Coordinator writes `<appData>/brain/<conversation-id>/.system_generated/swarm/plan.json` immediately after spawning Wave 1 (per `swarm_pane/SKILL.md`):

```json
{
  "goal": {
    "title": "Build VoiceStudio Local Synthesis and Clipper",
    "line": "GGUF LoRA + LiteRT DAC, 4-gate verifier, Oboe EDL, and Tinker loop."
  },
  "tasks": {
    "build-scaffold": "Scaffold 5 Gradle modules, NDK 28, Oboe, LiteRT, ONNX",
    "sync-daemon": "Build Tinker OPSD/GRPO daemon and GGUF LoRA exporter",
    "core-engine": "Implement llama.cpp LoRA, LiteRT DAC, and NEON DSP",
    "core-verifier": "Implement sherpa-onnx CTC, WeSpeaker, and DNSMOS p808",
    "audio-edl": "Implement wait-free Oboe EDL with 15ms cosine crossfade",
    "ui-clipper": "Build 1:20 Vernier Clipper, WordPills, and Media3 anchors",
    "sync-integrator": "Wire PreferenceSyncWorker, hot-swap LoRA, and Aircheck UI",
    "verifier": "Run Gates 1-5, 16KB APK audit, and Limrun E2E"
  },
  "threads": [
    {
      "id": "t-engine-verifier",
      "title": "Native Synthesis and 4-Gate Verification",
      "goal": "s2-pro GGUF + LoRA, LiteRT 44.1kHz DAC, NEON mastering, and 4-gate verifier.",
      "tasks": ["build-scaffold", "core-engine", "core-verifier"]
    },
    {
      "id": "t-edl-clipper",
      "title": "Wait-Free Oboe EDL and Vernier Clipper",
      "goal": "Zero-xrun atomic EDL ring buffer, 1:20 Vernier scrubber, and Media3 beat sync.",
      "tasks": ["audio-edl", "ui-clipper"]
    },
    {
      "id": "t-closed-loop",
      "title": "Tinker LoRA Loop and E2E Verification",
      "goal": "OPSD/GRPO nightly 35MB GGUF LoRA hot-swap, app wiring, and 5-gate verification.",
      "tasks": ["sync-daemon", "sync-integrator", "verifier"]
    }
  ]
}
```

---

## 5. Worker Briefs & Blocking Gate Definitions

### 1. `build-scaffold` (Wave 1, `workspace: "share"`)
- **Owns**: `settings.gradle.kts`, `gradle/libs.versions.toml`, root `build.gradle.kts`, module `build.gradle.kts` & CMake skeletons for `:core-engine`, `:core-verifier`, `:audio-edl`, `:ui-clipper`, `:sync-trainer:android-worker`.
- **Requirements**:
  - Read `litert-runtime` skill first. Add `com.google.ai.edge.litert:litert:2.3.0` + `com.google.ai.edge.litert:litert-gpu:2.3.0` (NO `litertlm-android`, NO legacy `org.tensorflow:tensorflow-lite*`).
  - Add `com.google.oboe:oboe` (`buildFeatures { prefab = true }`), `com.microsoft.onnxruntime:onnxruntime-android`, `androidx.work:work-runtime-ktx` + `work-testing`, `androidx.media3:media3-transformer`, and `sherpa-onnx` Android artifact/CMake wiring.
  - Configure `ndkVersion = "28.2.13676358"`, CMake `3.22.1`, `abiFilters += "arm64-v8a"`, `-Wl,-z,max-page-size=16384` (16 KB Android 17 alignment). Guard `-march=armv9-a+simd+fp16` strictly behind `if(ANDROID AND CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")` so host macOS/Linux CTest builds compile cleanly!
  - Put canonical `ClauseNode.kt` (`package com.voicestudio.edl` matching `SynthesisContracts.kt` + `SPEC.md` Section 2) in `:audio-edl` so `:core-engine`, `:core-verifier`, `:ui-clipper`, `:sync-trainer:android-worker`, and `:app` depend cleanly on `:audio-edl`.
  - **Done criteria**: `./gradlew :app:testDebugUnitTest` and empty module compile tasks pass 100%; commit and notify `@coordinator`.

### 2. `sync-daemon` (Wave 1, `workspace: "share"` — Milestone 5 Rig / Gate 5a)
- **Owns**: `sync-trainer/home-daemon/**` (`pyproject.toml`, `tinker_voice_daemon.py`, `ota_server.py`, `export_lora_gguf.sh`, `tests/test_end_to_end_loop.py`).
- **Requirements**:
  - Implement `SingleSpeakerAudioEnv(Env)` targeting `thinking-machines-lab/tinker-cookbook` + `s2-pro-4B` PyTorch checkpoint:
    - Step A (OPSD): teacher conditioned on edited cadence + updated `lexicon.json` phonemes, student sees raw text, Rank-32 LoRA, 10 steps, `lr=1e-4`.
    - Step B (GRPO / Online DPO): human Take-Swap pairs (`chosen_tokens` vs `rejected_tokens`) + automated `gate_failures` (`cer > 0.0 -> -2.0` hard penalty; reward `1.5 * speaker_cosine_sim + 0.5 * (dnsmos_p808 / 5.0) + (1.0 if human_chosen else 0.0)`), `kl_coef = 0.04`, 15 steps.
  - Implement `export_lora_gguf.sh` invoking `llama.cpp/convert_lora_to_gguf.py` -> `voice_lora_vXXX.gguf` (~35 MB) and updating `bundle_manifest.json` with SHA-256 checksums conforming to `docs/swarm/schemas/ota_bundle_manifest.schema.json`.
  - Implement Tailscale/HTTP OTA + telemetry server (`ota_server.py`): `POST /v1/preferences` (validates `docs/swarm/schemas/preference_tuple.schema.json` and appends `preferences.jsonl`), `GET /v1/bundles/latest/manifest.json`, `GET /v1/bundles/{ver}/{filename}` with HTTP Range resume.
  - **Gate 5a (`pytest`)**: Verify preference JSONL ingestion, reward scoring (hard `-2.0` penalty on `cer > 0`), OPSD/GRPO batch construction, manifest SHA-256 generation, Range HTTP download, and fail-fast error behavior when PyTorch base checkpoint is missing (zero fabricated LoRA weights).

### 3. `core-engine` (Wave 2, `workspace: "share"` — Milestone 1 / Gate 1)
- **Owns**: `core-engine/**`.
- **Requirements**:
  - Implement exact header contracts (`voice_engine.h`, `dsp_mastering.h`) and C++20 sources (`voice_engine.cc`, `dsp_mastering.cc`, `jni_bridge.cc`):
    - `VoiceEngine`: loads `s2_pro_4b_q4_k_m.gguf` + `voice_lora_vXXX.gguf` via `llama.cpp`, hot-swaps LoRA atomically via `llama_adapter_lora_init()` / `llama_set_adapter_lora()` without leaking native heap across repeated swap cycles, decodes discrete codebook rollouts (`N=4`) to 44.1 kHz Float32 PCM via LiteRT `CompiledModel` (`codec_decoder_fp16.tflite`), and implements `EvictWeightPages()` via `madvise(..., MADV_DONTNEED)`.
    - `dsp_mastering.cc`: deterministic 5ms C++20 NEON DSP pass applying 12ms studio Room IR convolution (`room_ir_44k.pcm`), `-16 LUFS` integrated loudness normalization, `-58 dBFS` studio noise floor mix, 64-byte-aligned headerless `.pcm` emission, 1.5s (`66,150`-sample) prefix tail extraction, and `FindNearestZeroCrossing(pcm, total_samples, target_sample)` scanning `±88` samples (`±2ms @ 44.1 kHz`) for minimum `|pcm[i]|` sign change.
    - `VoiceEngineJni.kt` (`nativeInit`, `nativeHotSwapLora`, `nativeSynthesizeCandidates`, `nativeFindNearestZeroCrossing`, `nativeEvictWeightPages`) + `ClauseNormalizer.kt` (`lexicon.json` IPA deterministic splitter keeping clauses `< 14s`).
  - **Gate 1 (`EngineParityTest.cc` + Kotlin tests)**: Build & run host CTest verifying NEON/scalar DSP mastering (`-16 LUFS`, Room IR impulse identity, `FindNearestZeroCrossing` `|pcm[idx]| < 0.005f`, 64-byte alignment), `ClauseNormalizer` `<14s` splitting, and real missing-GGUF/TFLite error handling (ZERO synthetic TTS fabrication); wire `adb shell /data/local/tmp/` parity runner for real GGUF/LoRA/TFLite device benchmarking (`<25s` + 0-byte heap growth across 10 `HotSwapLora` cycles when models are staged).

### 4. `core-verifier` (Wave 2, `workspace: "share"` — Milestone 2 / Gate 2)
- **Owns**: `core-verifier/**`.
- **Requirements**:
  - Implement `verifier_gates.h`, `verifier_gates.cc`, and `ClauseVerifier.kt`:
    - Links `sherpa-onnx` (CTC Forced Aligner -> `[start_sample, end_sample]`, exact `cer == 0.0f`, `max_phoneme_duration_ms <= 380.0f`), `onnxruntime-mobile` (`wespeaker_resnet34.onnx` 25 MB cosine sim `>= 0.86f` against 256-float `master_centroid.bin`, and Microsoft `dnsmos_p808.onnx` 4 MB `>= 4.10f`), final 80ms `tail_rms_db <= -45.0f` dBFS + spectral flux glitch check, and cadence gate (`4.2f..6.1f` syllables/sec).
    - Scans `±88` samples (`±2ms @ 44.1 kHz`) around each aligned word boundary for `zero_cross_in` / `zero_cross_out` (`|pcm[idx]| < 0.005f`) and locates `f0_peak_sample` via YIN/autocorrelation pitch tracking.
    - Sorts passing candidates and writes `ClauseNode.json` + top 3 64-byte-aligned `.pcm` files to disk.
  - **Gate 2 (`VerifierGateTest.kt` + host CTest)**: Test 3 known-good audio buffers and 2 corrupted buffers (1 dropped word -> `cer > 0.0f`, 1 white-noise tail glitch -> `tailRmsDb > -45.0f`). Assert corrupted buffers are hard-rejected (`passedAllGates == false`) and surviving buffers emit `abs(pcm[zeroCrossIn]) < 0.005f`.

### 5. `audio-edl` (Wave 2, `workspace: "share"` — Milestone 3 / Gate 3)
- **Owns**: `audio-edl/**`.
- **Requirements**:
  - Implement `edl_player.h`, `edl_player.cc`, and `EdlController.kt`:
    - Wait-free Oboe (`AAudio`) 44.1 kHz Float32 output stream backed by `std::atomic<EdlSnapshot*>` double-buffered Edit Decision List over `mmap`'d `.pcm` files (NO WAV stitching on disk).
    - `UpdateEdlSnapshot()` swaps pointer atomically in `< 0.05 ms` and defers retirement of the prior snapshot off the audio thread (epoch/RCU or lock-free retire queue — ZERO `delete` or mutex lock on the real-time audio callback!).
    - Real-time `onAudioReady` splices across `AudioSlice` boundaries with a `660`-sample (`15 ms @ 44.1 kHz`) equal-power cosine crossfade (`cos(t) * sliceA + sin(t) * sliceB`) overlaid on the `-58 dBFS` studio room-tone loop.
  - **Gate 3 (`EdlGlitchTest.kt` + C++ render test)**: Programmatically swap takes and sweep `globalGapScale` from `1.0f` to `0.25f` 50 times during active rendering/playback. Assert `AAudioStream_getXRunCount() == 0` (on device) and verify rendered PCM has zero first-derivative sample jumps `> 0.02f` across all 50 splice mutations.

### 6. `ui-clipper` (Wave 2, `workspace: "share"` — Milestone 4 / Gate 4)
- **Owns**: `ui-clipper/**`.
- **Requirements**:
  - Single-screen OLED Broadcast Deck Jetpack Compose editor (`ClipperScreen.kt`, `VernierScrubCanvas.kt`, `Media3BeatExporter.kt`):
    - **Top Row**: Monospace `WordPill` lazy row synced to current playhead sample; swipe pill down swaps `selectedTakeIndex` in `EdlSnapshot` (0ms), tap opens inline text/phoneme override and re-queues ONLY that `ClauseNode`. Strictly obey **Max 2 Adjacent Labels per Label**!
    - **Center Zone**: High-contrast min/max peak waveform `Canvas` with permanently fixed center vertical razor blade (`canvasWidth / 2f`).
    - **Bottom Zone**: `VernierScrubCanvas` (coarse drag `1 dp = 220 samples ~5ms`; fine Vernier strip `160 dp = 2,205 samples = 50ms`, 1:20 gear ratio), magnetic snap on drag release to `zeroCrossIn`/`zeroCrossOut` firing `HapticFeedbackConstants.CLOCK_TICK`.
    - **Right Edge**: Vertical `GapCompressionRail` (`100% -> 25%`) + `Media3BeatExporter.kt` (`computeAnchorTimestampsUs()` + `androidx.media3.transformer.Composition`) locking every `videoAnchorId` cut to its post-compression sample offset.
    - Honest empty / missing-OTA-bundle state with zero mock waveforms or fake metrics (Constitutional Law 2).
  - **Gate 4 (`TimelineSyncTest.kt` + Compose UI tests)**: Compress global gaps by 40% on a 5-clause project with 3 linked video cuts; assert all 3 Media3 `ClippingConfiguration` timestamps shift to match the exact new cumulative sample offsets within `< 1 ms`, and test WordPill swipe + Vernier drag + GapCompressionRail interactions.

### 7. `sync-integrator` (Wave 3, `workspace: "share"` — Milestone 5 Phone + App Wiring)
- **Owns**: `sync-trainer/android-worker/**` and `:app` integration files listed in Ownership Matrix.
- **Requirements**:
  - Implement `PreferenceSyncWorker.kt` (WorkManager Wi-Fi/Tailscale `NetworkType.UNMETERED` constrained): uploads `preferences.jsonl` (chosen/rejected `discreteAudioTokens` + `gate_failures` + `user_gap_scale`) to Tailscale rig, checks latest manifest, downloads ONLY the ~35 MB `voice_lora_vXXX.gguf` delta via SHA-256 atomic `.tmp` rename into `noBackupFilesDir`, and calls `nativeHotSwapLora()` live without restarting the app!
  - Implement expedited `VoiceSynthesisWorker.kt` orchestrating Stage A -> Stage B -> Stage C with live `/proc/self/status` `VmRSS` telemetry guard (`<= 9.5 GB` foreground ceiling) and `EvictWeightPages()` on completion.
  - Wire Room WAL persistence for `VoiceProjectManifest` / `ClauseNode` / `PreferenceTelemetryRecord`, Navigation 3 `ScreenKey.ClipperKey(scriptId)` into `MainActivity` / `StudioViewModel` / `ScriptScreen`, and real OTA GGUF/LiteRT/ONNX/LoRA version & Tailscale sync controls in `SystemDeckSheet`.
  - **Done criteria**: Full `./gradlew :app:testDebugUnitTest` + module unit tests pass 100%; commit and push.

### 8. `verifier` (Wave 3, `workspace: "inherit"` — Gates 1–5 & Real-Device Audit)
- **Owns**: `app/src/androidTest/java/com/example/VoiceStudioEndToEndTest.kt` + final verification report.
- **Requirements**:
  - **NO LOCAL ANDROID EMULATORS!** Verify on attached USB phone (`adb`) OR Limrun remote Android (`source ~/trap/.env && lim android ...`; delete Limrun instance after completion).
  - Run all host CTest (`EngineParityTest`, `EdlGlitchTest`), Python `pytest` (`test_end_to_end_loop.py`), Gradle unit tests (`VerifierGateTest`, `TimelineSyncTest`, `SynthesisContractsTest`, `:app:testDebugUnitTest`), and `./gradlew :app:assembleRelease` + `android analyze` verifying:
    - Every `.so` (including `libvoicestudio_jni.so`) is 16 KB page aligned (`0x4000`).
    - Zero `.gguf`, `.tflite`, `.onnx`, `.bin`, or `.pcm` model blobs are bundled inside the APK.
  - Run `VoiceStudioEndToEndTest` on USB/Limrun device verifying Navigation 3 -> Clipper screen -> WordPill take swap -> GapCompressionRail sweep -> Room `preferences.jsonl` queue -> System Deck OTA state.
  - Capture full-window screenshots in your worker `<appData>/brain/<conversation-id>/` directory and flag each on its own line: `Screenshot: <absolute path> | <what changed, <=10 words>`.
  - Run full Constitutional Law 2 anti-fabrication audit across the diff.
