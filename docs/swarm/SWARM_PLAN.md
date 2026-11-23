# Swarm Execution Plan: Bare-Metal Neural Voice & Hairline Clipper Initiative

## 1. Execution Topology & Wave DAG

This plan decomposes the initiative into **7 specialized workers across 3 waves** with **strict non-overlapping file ownership** so parallel `workspace: "share"` workers compile and test independently with zero merge conflicts.

```text
Wave 0 (DONE & PUSHED ON main):
  - Frozen JSON Schemas: docs/swarm/schemas/*.json
  - Frozen Kotlin Contracts & Interfaces: SynthesisContracts.kt, SynthesisInterfaces.kt
  - Contract Tests: SynthesisContractsTest.kt (green)

Wave 1 (Parallel immediately):
  ├─► [build-scaffold]  (workspace: share)  Gradle/NDK/CMake/Prefab/Manifest/CMake skeleton
  └─► [rig-trainer]     (workspace: share)  training-rig/** (SFT/GRPO/OPSD + export + OTA/JSONL server)
        │
        ▼  (Merge build-scaffold into coordinator branch before launching Wave 2!)

Wave 2 (4-Way Parallel Android Build — all inherit Wave 0 interfaces + Wave 1 Gradle/NDK):
  ├─► [native-dsp]       (workspace: share)  app/src/main/cpp/** + com/example/nativeaudio/**
  ├─► [g2p-storage]      (workspace: share)  com/example/engine/g2p/** + com/example/data/synthesis/** + SynthesisDao.kt
  ├─► [synth-workers]    (workspace: share)  com/example/engine/synthesis/** + com/example/worker/**
  └─► [hairline-clipper] (workspace: share)  com/example/ui/clipper/**
        │
        ▼  (Merge Wave 2 branches + rig-trainer cleanly into coordinator branch)

Wave 3 (Integration & Real-Device Verification):
  ├─► [integrator]       (workspace: share)  App DI, Room v3 migration, Nav3 ClipperKey, ViewModel, SystemDeck
  └─► [verifier]         (workspace: inherit) APK 16KB/zero-model audit + Limrun/USB Compose E2E + screenshots
```

---

## 2. Exclusive Ownership Matrix (Zero File Collisions)

| Worker | Workspace | Exclusive Owned Paths (No Other Worker May Edit) |
|---|---|---|
| `build-scaffold` | `share` | `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/cpp/CMakeLists.txt` (initial skeleton only) |
| `rig-trainer` | `share` | `training-rig/**` |
| `native-dsp` | `share` | `app/src/main/cpp/**` (after Wave 1 lands), `app/src/main/java/com/example/nativeaudio/**`, `app/src/test/java/com/example/NativeDspTest.kt` |
| `g2p-storage` | `share` | `app/src/main/java/com/example/engine/g2p/**`, `app/src/main/java/com/example/data/synthesis/*Impl.kt`, `app/src/main/java/com/example/data/local/SynthesisDao.kt`, `app/src/test/java/com/example/G2pAndStorageTest.kt` |
| `synth-workers` | `share` | `app/src/main/java/com/example/engine/synthesis/**`, `app/src/main/java/com/example/worker/**`, `app/src/test/java/com/example/SynthesisWorkerTest.kt` |
| `hairline-clipper` | `share` | `app/src/main/java/com/example/ui/clipper/**`, `app/src/test/java/com/example/HairlineClipperUiTest.kt` |
| `integrator` | `share` | `app/src/main/java/com/example/AircheckApplication.kt`, `app/src/main/java/com/example/data/local/AircheckDatabase.kt`, `app/src/main/java/com/example/navigation/AircheckNavigation.kt`, `app/src/main/java/com/example/MainActivity.kt`, `app/src/main/java/com/example/ui/StudioViewModel.kt`, `app/src/main/java/com/example/ui/screens/SystemDeckSheet.kt`, `app/src/main/java/com/example/ui/screens/ScriptScreen.kt` |
| `verifier` | `inherit` | `app/src/androidTest/java/com/example/HairlineClipperEndToEndTest.kt` + live device verification & screenshots |

> **Rule**: Do NOT modify frozen Wave-0 contract files (`SynthesisContracts.kt`, `SynthesisInterfaces.kt`, `docs/swarm/schemas/*.json`) inside Wave 2 workers. If a contract field must change, post `@coordinator` first.

---

## 3. Quota-Aware Model Selection

Checked via `swarm(action="models")` on 2026-11-22:
- **Use default / Gemini 4 models (97% quota remaining)**: e.g. leave `model` unset (uses coordinator model) or explicitly assign `gemini-4-carbon-high` / `gemini-4-argon-high` for complex C++/ML workers (`native-dsp`, `rig-trainer`, `integrator`) and `gemini-3.8-flash-high` for fast verification/scaffold tasks.
- **Avoid until quota resets (2026-11-25)**: `claude-opus-5-5-*`, `claude-sonnet-5-5-*`, and `gpt-oss-120b-medium` currently show `0%` quota remaining.

---

## 4. Swarm UI Overview (`plan.json`)

Immediately after spawning Wave 1 workers, coordinator writes `<appData>/brain/<conversation-id>/.system_generated/swarm/plan.json` per `swarm_pane/SKILL.md`:

```json
{
  "goal": {
    "title": "Build Bare-Metal Voice Pipeline and Clipper",
    "line": "On-device neural prosody, 4-gate C++ verifier, Oboe clipper, and GRPO rig."
  },
  "tasks": {
    "build-scaffold": "Configure NDK 28, CMake, Oboe, LiteRT, WorkManager",
    "rig-trainer": "Build SFT/GRPO/OPSD rig, LiteRT export, Tailscale OTA server",
    "native-dsp": "Implement C++ Oboe splicer, 4-gate verifier, RVC+IR",
    "g2p-storage": "Build G2P splitter, Room WAL tables, OTA bundle sync",
    "synth-workers": "Build Expedited synthesis DAG and Wi-Fi preference sync",
    "hairline-clipper": "Build Compose vernier clipper, take swapper, gap rail",
    "integrator": "Wire Nav3, Room v3, ViewModel, and System Deck telemetry",
    "verifier": "Audit 16KB APK and run Limrun/USB E2E tests"
  },
  "threads": [
    {
      "id": "t-rig",
      "title": "Training Rig and Tailscale OTA",
      "goal": "Full-SFT/GRPO/OPSD training, LiteRT/ONNX export, and OTA/JSONL server.",
      "tasks": ["rig-trainer"]
    },
    {
      "id": "t-native-engine",
      "title": "Android 17 Synthesis and Native DSP",
      "goal": "NDK build, G2P clause splitter, sequential Stage A/B/C workers, and C++ DSP.",
      "tasks": ["build-scaffold", "native-dsp", "g2p-storage", "synth-workers"]
    },
    {
      "id": "t-clipper-e2e",
      "title": "Hairline Clipper UI and Verification",
      "goal": "Compose vernier clipper with Oboe/Media3 sync, app wiring, and real-device E2E.",
      "tasks": ["hairline-clipper", "integrator", "verifier"]
    }
  ]
}
```

---

## 5. Worker Briefs (Ready for `swarm(action="add")`)

### 1. `build-scaffold` (Wave 1, `workspace: "share"`)
- **Owns**: `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, initial `app/src/main/cpp/CMakeLists.txt` + minimal `app/src/main/cpp/aircheck_dsp_stub.cpp`.
- **Requirements**:
  - Read `litert-runtime` and `litert-lm` skills before editing Gradle/Manifest.
  - Add `androidx.work:work-runtime-ktx`, `androidx.work:work-testing` (testImplementation), `com.google.ai.edge.litert:litert:2.3.0`, `com.google.ai.edge.litert:litert-gpu:2.3.0`, `com.google.ai.edge.litertlm:litertlm-android:0.18.0`, `com.microsoft.onnxruntime:onnxruntime-android`, and `com.google.oboe:oboe` (with `buildFeatures { prefab = true }`).
  - Configure `ndkVersion = "28.2.13676358"` and `externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }` with `abiFilters += "arm64-v8a"` and `-Wl,-z,max-page-size=16384` linker flag for Android 17 16 KB page alignment.
  - Add `<uses-native-library android:name="libvndksupport.so" android:required="false"/>` and `<uses-native-library android:name="libOpenCL.so" android:required="false"/>` inside `<application>`, plus foreground service permissions/declaration for expedited WorkManager synthesis (`FOREGROUND_SERVICE_DATA_SYNC`).
  - **Done criteria**: `./gradlew :app:testDebugUnitTest` and `./gradlew :app:compileDebugKotlin` pass cleanly; commit on your share branch and notify `@coordinator`.

### 2. `rig-trainer` (Wave 1, `workspace: "share"`)
- **Owns**: `training-rig/**` (all new Python training, export, OTA/telemetry server, schemas validation, and pytest tests).
- **Requirements**:
  - Implement modular, real PyTorch / LiteRT-Torch / AI-Edge-Quantizer Python pipeline under `training-rig/`:
    1. `training_rig/config.py` — Typed configs for `ming_3_0_tts_tiny`, `fish_s2_pro_4b`, `voxcpm2`, 8x H100 DDP or single-GPU local server.
    2. `training_rig/train_sft_grpo_opsd.py` — Full-SFT warmstart + GRPO group-relative reward trainer (using real 4-gate reward terms: CTC CER, WeSpeaker sim, UTMOSv2, syllable rate) + On-Policy Self-Distillation (OPSD) nightly adapter update ingesting `preferences_v1.jsonl` Chosen-vs-Rejected pairs.
    3. `training_rig/export_ota_bundle.py` — `litert-torch` + `ai-edge-quantizer` exporter packaging `.litertlm`/`.tflite`, RVC v2 `.onnx`, `room_ir_48k.f32`, `lexicon.json`, computing SHA-256 checksums, enforcing `<= 9500 MB` stage ceiling and `<= 350 MB` Stage B ceiling, and writing `bundle_manifest.json` valid against `docs/swarm/schemas/ota_bundle_manifest.schema.json`.
    4. `training_rig/ota_server.py` — FastAPI / stdlib HTTP server designed for Tailscale/WireGuard binding (`100.x.y.z` or configured interface) serving `GET /v1/bundles/latest/manifest.json`, `GET /v1/bundles/{ver}/{filename}` (with HTTP Range resume support), and `POST /v1/preferences` appending validated JSONL tuples matching `docs/swarm/schemas/preference_tuple.schema.json`.
    5. `training_rig/nightly_distill.py` — Nightly batch driver consuming uploaded JSONL preference tuples -> building Chosen-vs-Rejected GRPO/OPSD batches -> emitting incremented `bundle_version` checkpoint manifest.
    6. `training-rig/tests/test_rig_pipeline.py` — Comprehensive `pytest` suite validating schema conformance, manifest SHA-256 + memory-budget checks, preference JSONL ingestion, and HTTP OTA/Range download end-to-end.
  - Zero fabricated success paths: fail loudly if GPU/model weights are missing during actual training runs; keep unit tests cleanly testing real serialization, reward computation, manifest validation, and HTTP server round-trips.
  - **Done criteria**: `pytest` passes 100%; commit and notify `@coordinator`.

### 3. `native-dsp` (Wave 2, `workspace: "share"`)
- **Owns**: `app/src/main/cpp/**`, `app/src/main/java/com/example/nativeaudio/**`, `app/src/test/java/com/example/NativeDspTest.kt`.
- **Requirements**:
  - Implement `NativeDspBridge` and `OboeScrubberBridge` from `SynthesisInterfaces.kt` backed by real C++20 JNI (`libaircheck_dsp.so`):
    1. **Oboe 48kHz Float32 Stream (`oboe_scrubber.cpp`)**: Low-latency `AAudio`/`Oboe` playback callback splicing clause PCM files at `zero_cross_in` / `zero_cross_out` with configurable `gap_after_ms` silence spans, lock-free atomic playhead sample tracking, sample-accurate seek, and glitch-free hot-swap of a clause take.
    2. **Zero-Crossing & F0 Peak Analyzer (`pcm_analyzer.cpp`)**: Real DSP over 48kHz Float32 mono PCM locating nearest zero crossings (`zero_cross_in`, `zero_cross_out`) around each word boundary and autocorrelation/YIN F0 pitch peak (`f0_peak_sample`) within each word span, plus exact extraction of the trailing 1500ms (`72,000` samples) acoustic prefix tail.
    3. **Partitioned FFT Room IR Convolver (`room_ir_convolver.cpp`)**: Real overlap-add FFT convolution applying 48kHz Room IR `.f32`/`.wav` onto RVC v2 output with gain normalization and clipping guard.
    4. **Stage B 4-Gate C++ Verifier (`four_gate_verifier.cpp`)**: Runs CTC CER, WeSpeaker cosine similarity against enrolled centroid, UTMOSv2 MOS inference (via LiteRT / XNNPACK mmap under `<= 350 MB` RAM), and syllable-rate estimation; returns `Result.failure(...)` cleanly if required model paths are missing/unreadable (ZERO fabricated pass scores!).
    5. **Stage C RVC v2 ONNX Runner (`rvc_onnx_runner.cpp`)**: Invokes ONNX Runtime C/Kotlin API for 48kHz harmonic resynthesis + Room IR convolution; fails cleanly if model missing (zero synthetic audio fallback!).
    6. **Host C++ Tests (`app/src/main/cpp/tests/`)**: Standalone CMake/CTest target testing `pcm_analyzer` (zero-crossing accuracy, YIN F0 peak detection, 1500ms tail extraction) and `room_ir_convolver` (impulse identity & convolution accuracy).
  - **Done criteria**: Both host `ctest` AND `./gradlew :app:testDebugUnitTest` pass with 16 KB linker alignment; commit and notify `@coordinator`.

### 4. `g2p-storage` (Wave 2, `workspace: "share"`)
- **Owns**: `app/src/main/java/com/example/engine/g2p/**`, `app/src/main/java/com/example/data/synthesis/*Impl.kt`, `app/src/main/java/com/example/data/local/SynthesisDao.kt`, `app/src/test/java/com/example/G2pAndStorageTest.kt`.
- **Requirements**:
  - Implement `ClauseNormalizer` (`DeterministicG2pNormalizer.kt`):
    - Deterministic text normalization (numbers, currency, acronyms, punctuation, contractions, phonetic overrides).
    - Splits script drafts into ordered `ClauseNode`s (`c_001`, `c_002`, ...) strictly `< 15.0s` estimated spoken duration each, respecting punctuation and natural prosody clause boundaries.
    - Loads OTA `lexicon.json` when present (plus deterministic English G2P letter-to-IPA ruleset for OOV words) to populate `normalized_phonemes`.
  - Implement Room WAL entities & DAO (`SynthesisDao.kt`): `ClauseNodeEntity`, `TakeCandidateEntity`, `ZeroCrossingTokenEntity`, `OtaBundleEntity`, `PreferenceEventEntity` + `SynthesisRepositoryImpl.kt` implementing `SynthesisRepository` (including automatic creation of `PreferenceTuple` rows whenever `selectTake`, `updateGapAfterMs`, `updateAllGapsMs`, or `bindVideoAnchor` is called).
  - Implement `OtaBundleManagerImpl.kt`: Tailscale/WireGuard HTTP client (OkHttp with Range resume, `.tmp` atomic install into `noBackupFilesDir/bundles/{ver}/`), strict SHA-256 verification of every artifact, `manifest.fitsMemoryBudget()` enforcement, and real `OtaBundleState` reporting.
  - **Done criteria**: `./gradlew :app:testDebugUnitTest` passes with tests for `<15s` splitting, deterministic IPA, Room WAL CRUD + preference pair emission, and SHA-256 OTA verification (including corruption rejection); commit and notify `@coordinator`.

### 5. `synth-workers` (Wave 2, `workspace: "share"`)
- **Owns**: `app/src/main/java/com/example/engine/synthesis/**`, `app/src/main/java/com/example/worker/**`, `app/src/test/java/com/example/SynthesisWorkerTest.kt`.
- **Requirements**:
  - Read `litert-lm` and `litert-runtime` skills before writing any LiteRT code.
  - Implement `StageAProsodyEngine.kt`: Single-thread confined executor (`Executors.newSingleThreadExecutor()`), Mutex-protected `Engine`/`CompiledModel` lifecycle (load from absolute `noBackupFilesDir` path -> generate `N = 4` takes per clause conditioned on `acoustic_prefix_uri` -> explicit close-once before Stage B).
  - Implement `ClauseSynthesisWorker.kt` (`CoroutineWorker` with `OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST`, foreground notification with `ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC`):
    - Checks `OtaBundleManager.loadVerifiedManifest()` first; fails cleanly with explicit error if bundle missing/corrupt.
    - Reads `/proc/self/status` `VmRSS` before/after each stage and aborts cleanly if foreground RSS approaches `9500 MB`.
    - Sequential per-clause or per-batch stage handoff: Stage A (load -> generate 4 takes -> unload) -> Stage B (`NativeDspBridge.loadVerifierSession` -> score & rerank all 4 takes -> select highest-scoring `passed_all_gates == true` take -> unload) -> Stage C (`NativeDspBridge.loadResynthesisSession` -> RVC v2 + Room IR -> write 48kHz f32 PCM + 1500ms tail -> zero-crossing token map -> unload) -> persist `ClauseNode`s to `SynthesisRepository`.
  - Implement `PreferenceSyncWorker.kt`: WorkManager constrained strictly to `NetworkType.UNMETERED` (Wi-Fi / Tailscale), streams unsynced `PreferenceTuple` rows as newline-delimited JSONL to `POST {tailscaleBaseUrl}/v1/preferences`, marks synced in Room ONLY on HTTP 2xx.
  - Implement `ClauseSynthesisCoordinatorImpl.kt` implementing `ClauseSynthesisCoordinator`.
  - **Done criteria**: Unit tests using `TestListenableWorkerBuilder` verify missing-bundle failure (zero fabrication), stage execution ordering + unload guarantees, reranker selection (`cer == 0.0`), and Wi-Fi constraint on `PreferenceSyncWorker`; commit and notify `@coordinator`.

### 6. `hairline-clipper` (Wave 2, `workspace: "share"`)
- **Owns**: `app/src/main/java/com/example/ui/clipper/**`, `app/src/test/java/com/example/HairlineClipperUiTest.kt`.
- **Requirements**:
  - Build the OLED Broadcast Deck **Hairline Audio/Video Clipper** in Jetpack Compose matching Aircheck's existing theme (`OledVoid`, `ConsoleElevated`, `StudioCrisp`, `LocalAircheckTokens`, Space Grotesk + JetBrains Mono):
    1. **Fixed Center Blade + Dual-Speed Vernier Tick Scrubber** (`VernierScrubberCanvas.kt`): Fixed vertical center blade (`1.dp` Volt Lime / Cobalt accent); dragging waveform horizontally scrolls under the blade with dual-speed vernier precision (drag vertically away from ruler for 10x sample-accurate micro-scrubbing down to `zero_cross_in` / `zero_cross_out`), synchronized to `OboeScrubberBridge.seekToSample` and Media3 video preview frame position.
    2. **Word-Pill Take Swapper** (`WordPillTakeSwapper.kt`): Interactive word pills (`Take #1 ◄─► Take #2/3`) displaying real 4-gate status (`CER 0.0`, `Sim`, `UTMOS`, `Syl/s`) when tapped/expanded — strictly enforcing **Max 2 Adjacent Labels per Label** (never stack 3+ inline badges/chips next to any label!). Swapping a take invokes `OboeScrubberBridge.hotSwapClauseTake` + `SynthesisRepository.selectTake` (automatically emitting Chosen-vs-Rejected `PreferenceTuple`).
    3. **Global Gap-Compression Rail** (`GapCompressionRail.kt`): Hardware-deck rail adjusting `gap_after_ms` globally or per-clause (0..500ms) with live total duration telemetry and Room persistence.
    4. **Media3 Video Beat Anchor Bar** (`VideoBeatAnchorStrip.kt`): Displays `f0_peak_sample` markers and lets Ryan snap/toggle `video_anchor_id` directly to Media3 timeline beats.
    5. **Empty / Unsynthesized State**: Honest actionable state when no clauses are synthesized or OTA bundle is missing — ZERO fake waveforms, ZERO hardcoded mock metrics.
  - **Done criteria**: Compose Robolectric UI tests (`HairlineClipperUiTest.kt`) exercise vernier scrubbing, take swapping, gap-compression rail, video anchor snapping, and verify real callback/state transitions; commit and notify `@coordinator`.

### 7. `integrator` (Wave 3, `workspace: "share"`)
- **Owns**: `AircheckApplication.kt`, `AircheckDatabase.kt`, `AircheckNavigation.kt`, `MainActivity.kt`, `StudioViewModel.kt`, `SystemDeckSheet.kt`, `ScriptScreen.kt`.
- **Requirements**:
  - Register Room v3 entities & `SynthesisDao` in `AircheckDatabase` with clean migration (preserving v2 `scripts`, `script_beats`, `clips`, `outbox_ops`, `cas_entries`).
  - Wire `ClauseNormalizer`, `OtaBundleManager`, `SynthesisRepository`, `NativeDspBridge`, `OboeScrubberBridge`, and `ClauseSynthesisCoordinator` inside `AircheckContainer`.
  - Add `ScreenKey.ClipperKey(val scriptId: String)` to Navigation 3 (`AircheckNavigation.kt`) + entry point from `ScriptScreen` ("Synthesize & Clip") and `MainActivity`.
  - Add real Tailscale OTA endpoint configuration, bundle sync status, verified artifact sizes, pending preference JSONL count, and live `/proc/self/status` RSS readout to `SystemDeckSheet.kt` (respecting Max 2 Adjacent Labels per Label and Zero Fabrication).
  - **Done criteria**: Full `./gradlew :app:testDebugUnitTest` and `./gradlew :app:assembleRelease` pass with zero warnings/errors; commit and push.

### 8. `verifier` (Wave 3, `workspace: "inherit"`)
- **Owns**: `app/src/androidTest/java/com/example/HairlineClipperEndToEndTest.kt` + verification report.
- **Requirements**:
  - **NO LOCAL EMULATORS!** Verify on attached physical USB phone (`adb`) OR Limrun remote Android (`source ~/trap/.env && lim android ...`; delete Limrun instance when finished).
  - Run `ctest` (C++), `pytest` (Python `training-rig`), `./gradlew :app:testDebugUnitTest`, and `./gradlew :app:assembleRelease` + `android analyze` confirming:
    - All `.so` files are 16 KB page aligned (`LOAD` alignment `0x4000`).
    - Zero `.litertlm`, `.tflite`, `.onnx`, or Room IR binary weights are packed inside the APK.
  - Run `HairlineClipperEndToEndTest` on the real/Limrun device verifying end-to-end UI interactions -> real Room DB state changes (including `preference_events` JSONL logging on take swap and gap adjustment) -> System Deck OTA state.
  - Capture full-window screenshots saved in your worker `<appData>/brain/<conversation-id>/` folder and flag each on its own line: `Screenshot: <absolute path> | <what changed, <=10 words>`.
  - Audit the entire diff against Constitutional Law 2 (zero fake audio, zero fabricated verifier metrics, zero dead toggles).
