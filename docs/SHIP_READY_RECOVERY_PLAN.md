# Aircheck Ship-Ready Recovery Plan

> **Purpose**: Concrete, step-by-step engineering & product execution blueprint to take `/Users/mac/Aircheck` from its exact current commit into a **delightful, away-from-laptop, <=60-second personal-voice clip studio** with a rock-solid tactile plain-text drafting interface, verified pre-departure OTA sync, zero dead legacy code, and **100% real JaCoCo test coverage**.

---

## 1. Ground-Truth Inventory (Verified Against Current Repo)

### What Already Works & Is On Disk (Do NOT Rebuild From Scratch)
1. **Trained Personal Voice Stack (`v002` — Verified `0.0000` CER, `0.8397` WeSpeaker)**:
   - Complete 10-artifact OTA bundle packaged and served at `/Users/mac/Downloads/voicestudio-models/bundles/v002/bundle_manifest.json` (`3.04 GB` total):
     - `s2_pro_4b_q4_k_m.gguf` (`2.51 GB`, static once)
     - `voice_lora_v002.gguf` (`132 MB`, personal voice LoRA, nightly delta)
     - `codec_decoder_fp16.tflite` (`370 MB`, fine-tuned 44.1 kHz ModdedDAC FP16)
     - `wespeaker_resnet34.onnx` (`26.5 MB`), `dnsmos_p808.onnx` (`225 KB`), `sherpa_ctc_aligner.tar.gz` (`20.5 MB`)
     - `room_tone_44k.pcm` (`706 KB`), `room_ir_44k.pcm` (`2.2 KB`), `master_centroid.bin` (`1 KB`), `lexicon.json` (`13.7 KB`)
2. **Prebuilt 16 KB-Aligned Android `arm64-v8a` `llama.cpp` Shared Libraries**:
   - Already compiled on disk at `/Users/mac/Downloads/voicestudio-models/android-libs/arm64-v8a/`:
     - `libllama.so` (`41 MB`), `libggml-base.so` (`5.4 MB`), `libggml-cpu.so` (`4.3 MB`), `libggml.so` (`493 KB`)
3. **All 5 On-Device VoiceStudio Library Modules (Built & Passing Module Builds)**:
   - `:core-engine` — `VoiceEngineJni` (`voice_engine.cc`, `llama.cpp` runtime `dlopen` + LoRA hot-swap + Stage C C++20 NEON `dsp_mastering.cc`), `LiteRtCodecDecoder` (44.1 kHz GPU/CPU DAC), `ClauseNormalizer`
   - `:core-verifier` — Stage B 4-gate verifier (`ClauseVerifier`, `OnnxGateBackends` for WeSpeaker/DNSMOS/CTC, `verifier_gates.cc`)
   - `:audio-edl` — Wait-free Oboe `EdlPlayer` (`edl_player.cc`, 15 ms equal-power crossfade over room tone, zero disk stitching)
   - `:ui-clipper` — Jetpack Compose Vernier Clipper (`ClipperScreen.kt`, `VernierScrubCanvas.kt`, `Media3BeatExporter.kt`)
   - `:sync-trainer:android-worker` — `OtaBundleSyncManager` (RFC 9110 Range resume + SHA-256 verification), `VoiceSynthesisWorker` (Stage A -> B -> C orchestrator + `VmRSS < 9,500 MB` guard), `PreferenceSyncWorker`, `VoiceStudioSyncCoordinator`
4. **4-Stage Tactile Plain-Text Drafting UI (`:app`) — Live-Verified on Android 15**:
   - Fixed-viewport 3-band instrument deck (`01 SEED · 02 PICK · 03 CUT · 04 SHIP`), inline `✨` AI rewrite, stretched Siri-style iridescent glowing thinking hairline (`DensityScrubberRule.kt`), async pull-down / Back multi-draft creation & instant switching (`DraftDeckViewModel.kt`), local SQLite FTS5 + vector graph radar (`LocalGraphDatabase.kt`), and Mac `:8443` video render bridge (`ota_server.py` + `drafting_passes.py`).
5. **Legacy Bloat Purged & Real JaCoCo Coverage Wired (Landed in This Commit)**:
   - Deleted **11 dead legacy files** (`ScriptScreen.kt`, `PhotoStageScreen.kt`, `ClipsScreen.kt`, `AircheckNavigation.kt`, `AircheckPreviews.kt`, `SynthesisInterfaces.kt`, `SynthesisContracts.kt`, `AircheckAppFunctionService.kt`, `AudioEngine.kt`, `ThumbHashCodec.kt`, `app_metadata.xml`) and pruned dead 3-tab code from `StudioViewModel`, `StudioComponents`, `SystemDeckSheet`, `CloudPipelineGateways`, `CasMediaStore`, `Models`, `AircheckProfileStore`, `AndroidManifest.xml`, `app/build.gradle.kts`, and `gradle/libs.versions.toml` (`-3,645` lines removed; zero Firebase/AppFunctions/Navigation3 relics).
   - Wired real JaCoCo (`enableUnitTestCoverage = true`, `enableAndroidTestCoverage = true`, `:app:jacocoFullCoverageReport`) + `scripts/check_coverage.py`.

### Real Measured JaCoCo Baseline (`:app` Right Now)
Run `python3 scripts/check_coverage.py --missed` after `./gradlew :app:jacocoFullCoverageReport`:
- **Total**: **`2,708 / 11,719` lines (`23.1%`)** · `18.5%` instructions · `16.6%` branches across `41` compiled units (`34` `.kt` source files in `app/src/main/java`).
- **Well-covered (`>80%`)**: `GraphModels` (`100%`), `LocalVectorEmbedder` (`99.3%`), `XShipIntentHelper` (`98.6%`), `DraftStateReducer` (`87.1%`), `GraphRadarEngine` (`86.2%`), `LocalGraphDatabase` (`84.2%`), `DraftModels` (`81.6%`), `XArchiveImporter` (`80.7%`), `AircheckProfileStore` (`80.2%`).
- **Partially covered**: `AircheckApplication` (`74.2%`), `DraftApiClient` (`72.8%`), `VadAudioCaptureEngine` (`50.0%`), `Models` (`19.8%`), `AircheckDatabase` (`4.9%`).
- **Currently `0.0%` unit-tested (20 files)**: `DraftDeckViewModel` (`1,501` lines), `CutSurgeryCanvas` (`1,341`), `SeedStagePanel` (`649`), `ShipRadarDrawer` (`535`), `CutThumbInstrumentBay` (`512`), `StudioViewModel` (`454`), `TactileDraftingDeckScreen` (`403`), `PickStagePanel` (`337`), `DensityScrubberRule` (`271`), `StudioComponents` (`269`), `ClipReviewScreen` (`260`), `Theme` (`252`), `CloudPipelineGateways` (`226`), `TopTelemetryStrip` (`210`), `SystemDeckSheet` (`206`), `CasMediaStore` (`94`), `MainActivity` (`74`), `ClipExporter` (`65`), `AircheckPlayerController` (`63`), `StageGateways` (`38`).

### The 6 Exact Root Gaps Keeping a Human From Being Delighted Today
1. **Native `libllama.so` not packaged in APK**: The 4 compiled `arm64-v8a` `.so` libraries exist in `/Users/mac/Downloads/voicestudio-models/android-libs/arm64-v8a/`, but are NOT inside `core-engine/src/main/jniLibs/arm64-v8a/`, so `VoiceEngineJni` (`voice_engine.cc:423`) cannot `dlopen("libllama.so")` on the phone.
2. **Drafting LLM & STT still require Mac `:8443`**: `DraftApiClient.kt` only calls `http://10.0.0.242:8443/v1/draft/*` (`whisper-cli` + `qwen3.8-flash`). Walking away from the laptop breaks voice transcription and `✨` AI passes.
3. **No Pre-Departure OTA Sync UX or Automatic Pre-Sync**: `OtaBundleSyncManager` works, but `AircheckApplication.onCreate()` only checks disk (`refreshLocalState`) and `SystemDeckSheet` has no UI to sync `v002`, show download/SHA-256 progress, or confirm `"Offline Voice Ready (v002)"` before leaving home.
4. **`04 SHIP` is disconnected from Yesterday's Local Voice Studio + Vernier Clipper**: `:ui-clipper` (`ClipperScreen`), `:audio-edl` (`EdlController`), and `:sync-trainer:android-worker` (`VoiceSynthesisWorker`) have **zero UI entry point** in `MainActivity` / `ShipRadarDrawer`. There is also no **Clips Library** drawer to replay or re-share previously created clips.
5. **Drafting UI length & gesture polish gaps**: Length counter is locked to `280` chars (X-post length) rather than supporting a **<=60-second spoken clip mode (`~150 words / 01:00`)**; floating `brush_micro_strip` can overlap the line below the brushed phrase; session switcher needs delete/close on old cards and clearer first-run hints.
6. **Test coverage is `23.1%` (not `100%`)**: ViewModels, Compose UI panels, gateways, Room DAOs, player/exporter, and Airplane-Mode end-to-end flows are not yet covered to 100%.

---

## 2. Target Human Experience ("Hand It to Your Cousin" Flow)

1. **Before Leaving Home (1-Time Pre-Sync — Automatic Over USB or Home Wi-Fi)**:
   - Open Aircheck at home (or run the 20-second USB pre-seed script). A clean top status indicator / Settings card syncs and SHA-256-verifies the `v002` voice pack + local STT + local drafting LLM in the background with real MB/% progress until it turns green: **`Offline Ready · v002`**.
2. **Out & About (Zero Laptop, Airplane Mode Works 100%)**:
   - **Step A (`01 SEED`)**: Toggle **`60s Clip`** (or **`280c Post`**) in the top telemetry strip. Tap **`[ Latch Mic ]`** and ramble naturally; on-device STT transcribes live with pauses >600 ms stripped automatically. Tap **`✨`** (stretched Siri iridescent hairline glows while thinking) to tighten prose, or pull down to spin up another topic card while the first card keeps generating asynchronously.
   - **Step B (`02 PICK`)**: Flick through 3 crisp angle cards with diff-dimming; tap any strong phrase to salvage it with a Verbatim Lock; swipe up to enter editing.
   - **Step C (`03 CUT`)**: Slow scrub-brush any awkward words for instant 1-motion replacement; fast swipe to cut a clause with automatic seam-healing; drag the hairline density scrubber to dial exact speaking duration (`00:48 / 01:00`); undo/redo freely; `[ Raw Kbd ]` always anchored bottom-right for single-character fixes.
   - **Step D (`04 SHIP`)**: One unmistakable primary button — **`[ Generate Voice Clip ]`**:
     - Runs **100% on-device** (`VoiceSynthesisWorker`: Stage A `s2-pro-4B` + `voice_lora_v002.gguf` + LiteRT DAC -> Stage B 4-Gate ONNX -> Stage C NEON DSP) with live stage progress and `VmRSS` safety guard.
     - Drops straight into **Vernier Clipper** (`ClipperScreen` + wait-free Oboe `EdlController`) for instant zero-glitch playback in your voice, 1-tap word-pill take swaps, and gap compression.
     - Tap **`[ Export Clip ]`** -> exports a broadcast-ready `<=60s` clip (mastered 44.1 kHz audio + crisp waveform/caption 9:16 MP4 via `Media3BeatExporter`) to `Movies/Aircheck` and opens the Android Share Sheet in one tap.
     - Secondary actions stay clean and honest: **`[ Share Text ]`** (X / Copy ASCII) and **`[ Anchor Video (Mac) ]`** (clearly badged `Mac Ready` when reachable or `Needs Home Mac` when away).
     - Tap **`[ Clips ]`** in the top bar anytime to replay, re-open in Clipper, or share past clips.

---

## 3. Concrete Step-by-Step Execution Plan

Execute these 8 steps strictly in order. **Do NOT advance to the next step until the current step's Verification Gate passes and is committed & pushed to `origin/main`.**

---

### Step 1: Package 16 KB-Aligned Native `libllama.so` & Verify On-Device Voice Engine Loading
**Objective**: Make `VoiceEngineJni` (`:core-engine`) load real `libllama.so` + `libggml*.so` directly from `applicationInfo.nativeLibraryDir` on any `arm64-v8a` Android device.

1. **Stage prebuilt `arm64-v8a` libraries into `:core-engine`**:
   - Copy `/Users/mac/Downloads/voicestudio-models/android-libs/arm64-v8a/{libllama.so,libggml.so,libggml-base.so,libggml-cpu.so}` into `core-engine/src/main/jniLibs/arm64-v8a/` (or wire a Gradle `copy` task sourcing from `VOICESTUDIO_ANDROID_LIBS_DIR` with explicit fail-fast check if missing so release APKs never silently ship without `libllama.so`).
   - Verify 16 KB ELF page alignment (`LOAD Align 0x4000`) on ALL `.so` files in the assembled APK (`libvoicestudio_jni.so`, `libllama.so`, `libggml*.so`, `libvoicestudio_edl.so`, `libvoicestudio_verifier.so`).
2. **Pass `nativeLibraryDir` explicitly into `VoiceEnginePipeline.initialize()`**:
   - In `sync-trainer/android-worker/src/main/kotlin/com/voicestudio/sync/VoiceSynthesisWorker.kt` (`NativeVoiceSynthesisExecutor`, line 356), pass `llamaLibraryPath = File(context.applicationInfo.nativeLibraryDir, "libllama.so").absolutePath` into `pipeline.initialize(...)`.
   - Verify `core-engine/src/main/cpp/voice_engine.cc:423` preloads `libggml-base.so`, `libggml-cpu.so`, `libggml.so`, then `libllama.so` in topological order from that directory so `dlopen` never fails on transitive dependencies.
3. **Verify `sherpa_ctc_aligner.tar.gz` extraction in `OtaBundleSyncManager`**:
   - Confirm `sync-trainer/android-worker/src/main/kotlin/com/voicestudio/sync/OtaBundleSyncManager.kt` atomically extracts `sherpa_ctc_aligner.tar.gz` into `sherpa_ctc_aligner/` and verifies the expected `.onnx` + `tokens.txt` files exist before returning `ResolvedOtaBundlePaths`.

**Verification Gate 1**:
- `./gradlew :app:assembleDebug` succeeds; `unzip -l app/build/outputs/apk/debug/app-debug.apk | grep -E "lib(llama|ggml|voicestudio)"` shows all 7 `arm64-v8a` `.so` libraries.
- `android analyze app/build/outputs/apk/debug/app-debug.apk` reports 16 KB page-size compliance (`0` alignment failures) and `0` Firebase classes.
- Host CTest (`EngineParityTest`, `VerifierCoreTest`, `EdlGlitchTest`) passes `100%`.

---

### Step 2: Bulletproof Pre-Departure OTA Sync & "Offline Ready" UX
**Objective**: Guarantee all `v002` voice models + local STT/LLM artifacts are reliably transferred to the phone BEFORE the user leaves their laptop, with clear progress and zero guesswork.

1. **Add fast USB pre-seed helper (`scripts/push_v002_bundle_usb.sh`)**:
   - Pushes `/Users/mac/Downloads/voicestudio-models/bundles/v002/*` directly over USB ADB into `/sdcard/Android/data/com.aistudio.aircheck.vqxkrm/files/incoming_bundle/v002/` (or via `adb reverse tcp:8443 tcp:8443` + triggering `OtaBundleSyncManager.syncFromRig("http://127.0.0.1:8443")`), verifying every SHA-256 against `bundle_manifest.json`.
   - Note: `adb reverse` + `OtaBundleSyncManager` uses the exact same production code path (RFC 9110 Range resume + SHA-256 atomic rename into `noBackupFilesDir/bundles/v002/`), running at USB 3.0 speed (~150+ MB/s -> ~20 seconds total for 3.04 GB!).
2. **Automatic Wi-Fi / USB Pre-Sync on Home Network**:
   - In `AircheckApplication.onCreate()`, after `otaBundleSyncManager.refreshLocalState(rigUrl)`, if `bundleState.value.status != OtaBundleStatus.READY` and the rig `/health` responds over USB or unmetered Wi-Fi, automatically schedule a resumable foreground/expedited `OtaSyncWorker` (with user-visible pause/resume) so models land on the phone without manual hunting.
   - Schedule `voiceStudioCoordinator.schedulePeriodicPreferenceSync(rigUrl)` once on startup so nightly `v003+` LoRA deltas (~132 MB) pull automatically on Wi-Fi while charging.
3. **Human-Readable "Offline Pack" Card in `SystemDeckSheet.kt` + Header Pill**:
   - Add ONE clean card inside `app/src/main/java/com/example/ui/screens/SystemDeckSheet.kt` backed strictly by real `otaBundleSyncManager.bundleState`:
     - Status pill: `Offline Ready (v002)` (green) / `Syncing 1.4 / 3.0 GB (46%)` (cyan progress bar + active filename) / `Not Downloaded — Sync on Wi-Fi/USB` (amber).
     - Real buttons: **`[ Sync Now ]`** (triggers `otaBundleSyncManager.syncFromRig`) and **`[ Check LoRA Update ]`** (triggers `pullLatestLoraDelta`).
     - Zero fake percentages, zero developer jargon (`VmRSS`, raw hex SHAs stay hidden or collapsed).
   - In `TopTelemetryStrip.kt`, show an unmistakable small status dot next to the settings gear: green = `Offline Ready`, amber = `Sync Needed` (tapping opens Settings directly to the Offline Pack card).

**Verification Gate 2**:
- Unit tests for `OtaBundleSyncManager` cover: cold sync, interrupted `.part` HTTP `206` Range resume, corrupted SHA-256 deletion & failure, `STATIC_ONCE` cross-version reuse (`v001 -> v002`), and `v002` LoRA delta update.
- Live Android test over `adb reverse tcp:8443 tcp:8443` syncs the real `v002` manifest/artifacts and transitions Settings UI to `Offline Ready (v002)`.

---

### Step 3: Local-First Away-From-Laptop Drafting Engine (On-Device STT + On-Device LLM)
**Objective**: Eliminate laptop dependency for text drafting, voice dictation, `✨` rewrites, angles, candidate synthesis, scrub-brush swaps, seam-healing, and density scrubbing.

1. **Add On-Device Speech-to-Text (`OnDeviceSttEngine.kt`)**:
   - Reuse the already-bundled `onnxruntime-android` (or `sherpa-onnx` ASR artifact added to `bundle_manifest.json`, e.g. `whisper-tiny.en` / `sherpa-onnx-streaming-zipformer-en`, ~45–65 MB) so `VadAudioCaptureEngine`'s silence-stripped 16 kHz WAV utterances transcribe 100% locally in `<400 ms`.
   - Create interface `DraftPassBackend` (`transcribe`, `extractAngles`, `synthesize`, `brushSwap`, `seamHeal`, `density`, `rewrite`).
2. **Add On-Device Fast Drafting LLM (`OnDeviceDraftLlmEngine.kt`)**:
   - Add one compact quantized instruct GGUF (`qwen3-1.7b-instruct-q4_k_m.gguf` or `qwen2.5-1.5b-instruct-q4_k_m.gguf`, ~1.0 GB) to `bundle_manifest.json` as `update_cadence: "static_once"` (downloaded during Step 2 pre-sync).
   - Execute via the ALREADY-PACKAGED `libllama.so` (`CoreDraftLlmJni`, 1024-token context, `n_threads = 6`, strict JSON grammar/post-validation, lock-parity `<lock>...</lock>` conservation, ASCII sanitizer).
   - **Strict Memory Discipline**: Explicitly unload / `madvise(MADV_DONTNEED)` the 1.0 GB drafting LLM before `VoiceSynthesisWorker` loads the 2.5 GB `s2_pro_4b_q4_k_m.gguf` so peak `VmRSS` never exceeds `5.5 GB` (far below the `9,500 MB` ceiling).
3. **Add `HybridDraftBackend.kt` Router (Zero-Friction Offline Continuity)**:
   - Default mode **`Auto (Local + Mac)`**: probes Mac `:8443/health` with a `250 ms` timeout (cached for 30s). If Mac is online, uses fast `qwen3.8-flash` (or user can toggle **`Always On-Device`** in Settings); if away from laptop (timeout/offline), silently routes all 7 passes (`transcribe`, `rewrite`, `angles`, `synthesize`, `brush-swap`, `seam-heal`, `density`) to `OnDeviceSttEngine` + `OnDeviceDraftLlmEngine`.
   - Zero error popups when walking out of Wi-Fi range. If NEITHER Mac NOR local models are installed yet, displays ONE clear actionable banner: `"Offline models not synced yet — open Settings on Wi-Fi/USB to download"` with a direct tap action.

**Verification Gate 3**:
- Airplane-mode Android test exercises all 7 drafting passes (`transcribe`, `rewrite`, `angles`, `synthesize`, `brush-swap`, `seam-heal`, `density`) with Wi-Fi + cellular OFF and confirms lock-parity preservation, plain ASCII output, and stretched Siri hairline activation/completion.

---

### Step 4: Wire Unified `<=60s` Personal-Voice Clip Journey (`04 SHIP` -> Local Stage A/B/C -> Vernier Clipper -> Export & Clips Drawer)
**Objective**: Connect today's 4-stage tactile text editor cleanly into yesterday's on-device VoiceStudio engine (`:core-engine` + `:core-verifier` + `:audio-edl` + `:ui-clipper`) so a human goes from spoken/typed idea to a finished `<=60s` clip in their own voice in one continuous flow.

1. **Dual Target Length Mode (`60s Clip` default | `280c Post`)**:
   - In `DraftModels.kt` & `TopTelemetryStrip.kt`, add `DraftTargetMode` (`CLIP_60S` default vs `POST_280`).
   - In `CLIP_60S` mode: top-right counter displays live calibrated speaking duration (`00:38 / 01:00`, computed at `4.8` syllables/sec / `~2.6` words/sec), turning amber at `55–60s` and red above `60s` with `[ Trim to 60s ]` triggering a `-15%` density pass.
   - In `POST_280` mode: keeps the exact `218 / 280` counter and `[ Split 1/2 ]` overflow pill.
2. **Wire `[ Generate Voice Clip ]` on `04 SHIP` (`ShipRadarDrawer.kt` + `MainActivity.kt`)**:
   - Make **`[ Generate Voice Clip ]`** the primary full-width Acid Volt Lime CTA in `CLIP_60S` mode (with `[ Share Text ]` and optional `[ Anchor Video (Mac) ]` cleanly below).
   - Tapping **`[ Generate Voice Clip ]`**:
     1. Saves the locked, sanitized draft text into Room as a `ScriptProject`.
     2. Unloads the small drafting LLM (freeing ~1.0 GB RAM).
     3. Calls `voiceStudioCoordinator.enqueueProjectSynthesis(projectId)` (`VoiceSynthesisWorker`: `ClauseNormalizer` -> Stage A `s2-pro-4B` + `voice_lora_v002.gguf` + LiteRT DAC -> Stage B 4-Gate ONNX -> Stage C NEON DSP).
     4. Renders a clean inline progress sheet (`Normalizing -> Synthesizing Your Voice -> Verifying Quality -> Mastering`, clause `2/4`) without blocking navigation.
     5. On `ClauseSynthesisPhase.READY`, automatically opens `:ui-clipper`'s **`ClipperScreen`** wired to `:audio-edl`'s **`EdlController`**!
3. **Wire `ClipperScreen` Playback, Take Swapping, & One-Tap `<=60s` Export**:
   - Bind `ClipperScreen` directly to `container.edlController` (wait-free Oboe ring buffer with 15 ms equal-power crossfades over `room_tone_44k.pcm`) and `localRepo` (persisting user take-swap & gap-scale choices into `preferences.jsonl` for nightly `v003+` LoRA improvement).
   - Wire `ClipperScreen`'s **`[ Export ]`** button to `Media3BeatExporter` -> renders a `<=60s` 9:16 clip (mastered 44.1 kHz voice + crisp dynamic waveform & word-timed captions on OLED dark canvas, or clean `.m4a`/`.wav` audio option) -> saves into `CasMediaStore` + `ClipRecord` in Room -> opens `ClipReviewScreen` with instant playback, Save to Photos (`Movies/Aircheck`), and Android Share Sheet!
4. **Add Persistent `[ Clips ]` History Drawer (1 Tap From Top Bar)**:
   - Add a compact clip-history button in `TopTelemetryStrip.kt` (showing count badge only when `clipCards.isNotEmpty()`) that opens a clean modal sheet listing every generated local voice clip and Mac anchor video (`ClipCardRow` from Room), with instant tap to play in `ClipReviewScreen`, re-edit in `ClipperScreen`, export, or delete.

**Verification Gate 4**:
- Robolectric + instrumented UI tests verify the complete state machine: `01 SEED -> 02 PICK -> 03 CUT -> 04 SHIP -> VoiceSynthesisWorker -> ClipperScreen -> Media3BeatExporter -> ClipReviewScreen -> Share Intent` AND reopening saved clips from the Clips History drawer.

---

### Step 5: Polish the Tactile Text-Editing Interface for Human Delight
**Objective**: Eliminate every micro-annoyance, visual glitch, or gesture ambiguity across `01 SEED · 02 PICK · 03 CUT · 04 SHIP`.

1. **Fix `CutSurgeryCanvas.kt` Micro-Strip Positioning & Spacing**:
   - Compute `brush_micro_strip` Y-offset from `textLayoutResult.getLineBottom(endLine) + 6.dp` (flipping cleanly above `getLineTop(startLine)` when near the bottom of the Eye Zone) so the 3-pill swap strip **never overlaps or obscures line 2 of the prose**.
   - Ensure the brushed highlight padding does not visually collide with the following word's first glyph.
2. **First-Run Discoverability Without Clutter**:
   - Add concise, self-dismissing 1-line micro-cues persisted in `AircheckProfileStore` (e.g., on `01 SEED` empty state: `"Speak with Latch Mic, type via Raw Kbd, or pull down for a new draft"`; on `03 CUT`: `"Brush words to swap · Swipe fast to cut · Slide hairline to tighten"`), disappearing automatically once the user completes that gesture once.
3. **Session Switcher Polish (`TactileDraftingDeckScreen.kt`)**:
   - Add a small `×` close button on inactive cards in `session_switcher_sheet` so completed or abandoned drafts can be cleared cleanly without piling up.
   - Ensure switching cards preserves scroll position, undo stack, and stage cleanly, and shows a subtle cyan pulse dot whenever a background card is still thinking.
4. **Decompose Oversized Files Before Final Coverage Lock**:
   - Split or trim redundant private helpers in `DraftDeckViewModel.kt` (`2,124` lines), `CutSurgeryCanvas.kt` (`2,088` lines), and `DraftStateReducer.kt` (`1,703` lines) so every unit has single responsibility, zero unreachable defensive branches, and fast maintainable tests.
5. **Enforce All UX Invariants Visually**:
   - Max 2 adjacent labels per primary label on every screen.
   - Minimum `48dp` touch targets on all buttons (`seed_ai_rewrite_button` hit-slop padded to `48dp` while visual badge remains sleek `32dp`).
   - Exact system bar & IME inset handling across `TactileDraftingDeckScreen`, `SystemDeckSheet`, `ClipperScreen`, `ClipReviewScreen`, and `ClipsHistorySheet`.

**Verification Gate 5**:
- Visual screenshot inspection of every screen & state (light/cobalt & volt-lime channels, empty state, Siri thinking glow, async switcher, 3-card pick, brush-swap micro-strip with zero text overlap, density scrubbing, 60s overflow trim, Clipper, ClipReview, Settings Offline Ready) confirms zero clipping, zero overlaps, and crisp legibility.

---

### Step 6: Achieve 100% Real Measured JaCoCo Coverage Across `:app` + All Library Modules
**Objective**: Eliminate every untested code path so regressions like stale relics or broken callbacks are caught immediately by CI.

1. **Remove any remaining dead columns/DAOs/helpers** revealed by `python3 scripts/check_coverage.py --missed` (e.g., unused Room DAO methods in `AircheckDatabase.kt` or unused fields in `Models.kt`).
2. **Build Comprehensive Test Suites Organized by Package**:
   - **`app/src/test/java/com/example/draft/`**:
     - `DraftStateReducerCoverageTest.kt` — close the remaining `131` missed lines in `DraftStateReducer` (`87.1% -> 100%`) and `18` missed lines in `DraftModels` (`81.6% -> 100%`).
     - `DraftApiClientCoverageTest.kt` — real loopback `HttpServer` testing all 7 `/v1/draft/*` endpoints, USB-to-LAN fallback, HTTP 4xx/5xx/empty-body/malformed-JSON/timeout errors, and `HybridDraftBackend` offline switching (`72.8% -> 100%`).
     - `VadAudioCaptureEngineCoverageTest.kt` — PCM energy/VAD silence-stripping (`>600ms`), 15s circular trailing ring buffer, WAV header encoding, momentary clutch, permission/init failure handling (`50.0% -> 100%`).
   - **`app/src/test/java/com/example/graph/`**:
     - Close remaining uncovered branches in `LocalGraphDatabase` (`84.2% -> 100%`), `GraphRadarEngine` (`86.2% -> 100%`), `XArchiveImporter` (`80.7% -> 100%`, ZIP + JS + CSV + JSON + corrupt inputs), `LocalVectorEmbedder` (`99.3% -> 100%`), `XShipIntentHelper` (`98.6% -> 100%`).
   - **`app/src/test/java/com/example/data/` & `engine/`**:
     - `AircheckDatabaseCoverageTest.kt` (in-memory Room DB testing every DAO & mapper: `4.9% -> 100%`).
     - `CasMediaStoreCoverageTest.kt` (`putBytes`, `putFile`, SHA-256 dedup, LRU eviction protecting active SHA, scratch files, `clearAllCache`: `0% -> 100%`).
     - `AircheckProfileStoreCoverageTest.kt` (Proto round-trip, corruption fallback, IO exception catch, all setters: `80.2% -> 100%`).
     - `CloudPipelineGatewaysCoverageTest.kt` (loopback `HttpServer` covering `/health`, `/v1/renders`, `/v1/render-video`, `/v1/jobs/{id}`, MP4 download, every HTTP error & malformed payload: `0% -> 100%`).
     - `ClipExporterCoverageTest.kt` & `AircheckPlayerControllerCoverageTest.kt` (Robolectric MediaStore/FileProvider/ExoPlayer lifecycle: `0% -> 100%`).
     - `ModelsAndAppCoverageTest.kt` (`Models.kt` + `AircheckApplication.kt`: `-> 100%`).
   - **`app/src/test/java/com/example/ui/` (ViewModels)**:
     - `DraftDeckViewModelCoverageTest.kt` — Robolectric + loopback `HttpServer` covering every public method & error branch: latch mic, distill 15s, steer clutch, manual edit, `✨` rewrite (seed & cut, lock parity pass/fail), angle toggle/refresh, synthesize 3, single-slot kill/regen, salvage/remove/splice, brush swap & alt select, strike-cut & seam-heal, clause reorder, density scrub, undo/redo snapshots, split 1/2, async pull-down new draft & concurrent background completion, session switch/close, graph radar quote/reply/weave/mute/import/copy/open-X (`0% -> 100%`).
     - `StudioViewModelCoverageTest.kt` — Robolectric + loopback `HttpServer` covering `renderDraftAsBroadcastClip`, polling (`QUEUED -> VOICE -> LIPSYNC -> PACKAGING -> READY`, `FAILED`, missing `videoUrl`, download failure, 5 consecutive poll errors), `exportClip` (save + share + error), `testMacRigConnection`, `saveTailscaleRigUrl`, `setBrandChannel`, `clearCasCache`, OTA sync trigger (`0% -> 100%`).
   - **`app/src/test/java/com/example/ui/compose/` (Compose UI Behavior Tests via Robolectric `createComposeRule`)**:
     - Dedicated Compose UI tests for EVERY live UI file (`MainActivity`, `TactileDraftingDeckScreen`, `TopTelemetryStrip`, `SeedStagePanel`, `PickStagePanel`, `CutSurgeryCanvas`, `CutThumbInstrumentBay`, `DensityScrubberRule`, `ShipRadarDrawer`, `SystemDeckSheet`, `ClipReviewScreen`, `ClipsHistorySheet`, `StudioComponents`, `Theme` across both Volt Lime & Broadcast Cobalt channels) exercising every click, swipe, drag, empty state, error banner, overflow badge, and Siri thinking animation (`0% -> 100%`).
3. **Run Full Coverage Across ALL Modules**:
   - Enable JaCoCo in `:core-engine`, `:core-verifier`, `:audio-edl`, `:ui-clipper`, and `:sync-trainer:android-worker` too, and add a root `./gradlew verifyAllCoverage` task.

**Verification Gate 6**:
- `./gradlew :app:testDebugUnitTest :core-engine:testDebugUnitTest :core-verifier:testDebugUnitTest :audio-edl:testDebugUnitTest :ui-clipper:testDebugUnitTest :sync-trainer:android-worker:testDebugUnitTest` — **100% passing, 0 skipped**.
- Connected Android 15 instrumentation E2E run (`am instrument -e coverage true ...`) pulled to `app/build/outputs/code_coverage/manual/coverage.ec` and merged via `./gradlew :app:jacocoFullCoverageReport`.
- `python3 scripts/check_coverage.py --missed` reports **`100.0%` line coverage** across `:app` (every live source file verified, zero dead code left behind).
- Python Mac server tests: `pytest sync-trainer/home-daemon/tests/` — **100% passing**.

---

### Step 7: Real Physical-Device Airplane-Mode Certification
**Objective**: Prove the finished APK works in real human hands away from the laptop — never ship off emulator-only runs.

Execute this exact 10-point live test on Ryan's physical `arm64-v8a` Android phone:
1. Install fresh APK (`adb install -r app-debug.apk`). Cold-launch time `< 1.2s`; zero crashes, zero Firebase errors, zero permission loops.
2. Connect at home (USB or Wi-Fi) -> tap **`Sync Offline Models`** -> verify green **`Offline Ready (v002)`** badge.
3. **Enable Airplane Mode** (Wi-Fi OFF, Mobile Data OFF, Mac disconnected).
4. **01 SEED**: Tap `[ Latch Mic ]`, speak a 25-second unscripted thought with pauses -> verify live on-device transcription strips dead air -> tap `✨` -> verify stretched Siri hairline illuminates and prose sharpens cleanly.
5. **Async Multi-Draft**: Tap `Synthesize 3 ->`, immediately pull down on Eye Zone -> verify Draft #2 spins up cleanly while Draft #1 continues generating in the background (`● 2/2` pill) -> switch back to Draft #1 -> verify all 3 candidates arrived cleanly in `02 PICK`.
6. **02 PICK**: Flick between `v1 | v2 | v3`, tap a clause on `v2` to salvage with Verbatim Lock, swipe up on `v1` to commit to `03 CUT`.
7. **03 CUT**: Slow scrub-brush 2 stiff words (verify inline swap + micro-strip sits cleanly without covering text), fast swipe-cut 1 clause (verify seam-heal preserves locked words), drag hairline scrubber leftward to dial under `00:50`, verify Undo/Redo and `[ Raw Kbd ]`.
8. **04 SHIP -> On-Device Voice Clip**: Tap `[ Generate Voice Clip ]` -> observe real Stage A -> B -> C synthesis in Ryan's `v002` voice (measure latency & peak `VmRSS < 9,500 MB`, zero OOM, zero clipped words).
9. **Vernier Clipper -> Export**: Play synthesized clip via Oboe (listen with ears: natural personal voice, 0 pops/clicks at clause seams, 0 XRuns), swap 1 word take, tighten gaps, tap Export -> verify playable `<=60s` clip saved to `Movies/Aircheck` and shared cleanly via Android Share Sheet.
10. **Clips Drawer & Home Sync**: Close clip, open `[ Clips ]` drawer -> verify clip replays cleanly. Turn Airplane Mode OFF at home -> verify `preferences.jsonl` syncs to Mac `:8443` and optional `[ Anchor Video (Mac) ]` renders a lip-locked talking-head video cleanly.

---

### Step 8: Final Repo Hygiene & Release Packaging
1. Audit & remove obsolete `backend/` Firebase Cloud Functions directory (if no longer needed) so zero legacy Firebase files exist anywhere in `/Users/mac/Aircheck`.
2. Build signed release & debug APKs (`./gradlew :app:assembleRelease :app:assembleDebug`), verify via `android analyze`, copy to `/Users/mac/Downloads/Aircheck-release.apk` and `/Users/mac/Downloads/Aircheck-debug.apk`.
3. Tag `v2.0-ship-ready`, ensure `git status` is 100% clean, and push all commits and tags to `origin/main`.
