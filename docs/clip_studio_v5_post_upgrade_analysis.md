# Clip Studio v5 — Post-Upgrade Analysis+ & Android 17 E2E Verification Report

## 1. Executive Summary & Architectural Integrity

Clip Studio v5 upgrades Aircheck's four authoring surfaces **strictly in place** inside `app/src/main/java/com/example/ui/clipstudio/` (`ClipStudioKit.kt`, `ClipStudioShell.kt`, `SparkScreen.kt`, `ScriptScreen.kt`, `CutScreen.kt`, `NewsroomLookScreen.kt`), backed by `DraftDeckViewModel.kt`, `StudioViewModel.kt`, Room persistence, `CasMediaStore`, `MediaPlayer`, `ExoPlayer`, and the live Mac-rig `ota_server.py` (`:8443`) + `remotion-worker` (`:8444`) stack.

### Constitutional & Hygiene Verification
- **Zero Duplicate Screen Files**: `app/src/main/java/com/example/ui/draft/` contains **only** `DraftDeckViewModel.kt` (all 8 orphaned v3 Drafting Deck screen files totaling **6,617 LOC** were removed in `a8b29db`), and zero `V2`/`V5` duplicate files exist anywhere under `app/src/main/java`.
- **Zero Mocks, Zero Fake Timers, Zero Dead Controls (Constitutional Law 2)**: All 29 prototype `noop` lambdas, `setInterval`/`setTimeout` progress timers, and hardcoded JS arrays (`BEATS`, `VERSIONS`, `ALTS`, `CLAUSES`, `FOLLOW`, `DIRS`) from `Aircheck Prototype v2.dc.html` have zero presence in production code. Every control mutates real persisted state, executes real media/network operations, and surfaces upstream HTTP errors honestly without fake success toasts.
- **UI Label Density Limit (`<= 2:1`)**: Audited across `SparkScreen.kt`, `ScriptScreen.kt`, `CutScreen.kt`, and `NewsroomLookScreen.kt`; dirty cutaway clauses on `03 CUT` consolidate status into a single metadata row (`edited · voice not synced · b-roll`) rather than stacking 3+ badges.
- **Zero Compiler Warnings in Touched Files**: All v5 text fields use `BasicTextField(state = rememberTextFieldState(...))`, and all touched production/test files compile with zero Kotlin K2 warnings.

---

## 2. Pre-Update (`v4` `1a–1d`) vs. Post-Upgrade (`v5` `3a`) UX & Capabilities Delta

| Surface | Pre-Update v4 State (`1a–1d`) | Post-Upgrade v5 Production State (`3a`) |
|---|---|---|
| **Global Chrome & Dock** (`ClipStudioKit.kt`, `ClipStudioShell.kt`) | `36dp` header with `30×28dp` icons, 3-button `56dp` docks, `10sp` busy sub-line, `#4A4A52` on `#070709` (~2:1 contrast). | `56dp` top bar (`44×44dp` utility keys, Ndot `13sp` active stage pill), 1–2 key `64dp` thumb dock (`92×64dp` red hold-mic + flex-1 `#EBEBEF` next-step commit key), in-place `64dp` progress dock fill, and dismissible `48dp` `#EBEBEF` toast `112dp` above bottom. |
| **`01 SPARK`** (`SparkScreen.kt`) | No onboarding empty state, no pre-record tone picker, no voice-note audio playback, invisible beat re-roll, zero shot planning, destructive `re-record` adjacent to `hold to add direction`. | Dedicated first-run empty state (`TONE` pill strip, `76dp` press-and-hold mic key, `56dp` `type it instead` sheet, Room-backed `Continue your last video` card + 2-up `SHITPOST`/`SOLARPUNK` split panel + Follow-Up sheet); populated outline with `MediaPlayer` voice-note playback (`cs_note_play`), safe Note Edit sheet (`keep last 15s` + text edit + re-record), top-bar `anchors` sheet, 4-beat `180×252` rig JPEG shot previews (`face -> pip -> broll -> card` + `+` b-roll adder), and explicit `44×44dp` beat sparkle rewrite keys. |
| **`02 SCRIPT`** (`ScriptScreen.kt`) | `24dp` version pills crammed into header (hiding `01`/`03` crumbs), hidden 2-tap phrase selection, drag-only `28dp` density ruler, 9-target `270dp` thumb pad, extra `38dp` `X NETWORK` bar. | Clean `56dp` top bar (`timer` length toggle, `keyboard` full-script editor, `undo`), expandable `±12`-word `shorter`/`longer` row, sunken 3-way posture control (`direct · punchy · story` with live word counts), **single-tap** natural phrase selection (`#EBEBEF` inverse highlight + `#1E1E24` underlined pinned phrases), raised `SWAP THIS PHRASE` card (`punchier` / `plainer` / `more`), and context-switching `pin` + `keep this` dock. |
| **`03 CUT`** (`CutScreen.kt`) | `10sp` `[captions: on]` / `LOOK:` text links, hidden long-press clause editing, duplicate `re-voice` + `sync voice` buttons, disabled primary CTA when dirty. | `218dp` sunken `#000` `ExoPlayer` monitor (`64dp` center play/pause, live word/clause captions, `3dp` `#D71921` progress bar), `44dp` horizontal tool pill row (`captions`, `trim pauses`, `punch-in`, `9:16`↔`16:9`, `look`), 2-way `words` · `b-roll` mode switch, **single-tap** clause expansion (`edit words` + `b-roll here`/`back to face`), un-truncated `"1 LINE NEEDS RE-VOICE"` top badge, and a single sequential primary key (`voice the script` -> amber `re-voice N edited line(s)` -> `render talking head` -> `render the video` -> `export & share` -> `share again`). |
| **`Video look`** (`NewsroomLookScreen.kt`) | Confusing `01–04` tabs colliding with stage numbers, `LIVE` / `ACTIVE:` debug clutter on canvas, invisible tick-ruler halves, competing `save look` vs `set default look` buttons. | `56dp` `"Video look"` header with enabled-from-initial-draft `Duplicate look` key (`cs_look_duplicate`), clean `206dp` live graded preview (`ColorMatrix` EV + warm/neutral/cool tint + blur + optional tag kicker + title box), 3-way `background` · `title` · `cards` control, unclipped `BLUR` / `BRIGHTNESS` `±` steppers + 3-way `LIGHT` bar above fold, AI-refreshable `TITLE ON SCREEN` + tag pills, sanitized real `QUOTE_BRIEF`/`STAT_CALLOUT` cards list, and single `"use this look"` commit key returning to `03 CUT` with `"look applied · render to see it"`. |

---

## 3. Verified 29-Row Zero-Mock Production Wiring Matrix

Every row from Section 4 of `docs/clip_studio_v5_pre_update_review.md` was audited in source and exercised by automated tests:

| # | Surface | Control | Production Implementation | Automated Verification |
|---:|---|---|---|---|
| 1 | Top Bar | `settings` (`cs_spark_settings`, `cs_cut_settings`) | Opens `SystemDeckSheet` modal in `MainActivity.kt` (`VoiceModelCareScreen`, `CorePipelineScreen`, `NewsroomStudioScreen`, Mac `:8443` health, cache controls). | `AircheckRunningAppE2ETest`, `AircheckNewsroomAndVoiceCareE2ETest` |
| 2 | Top Bar | `undo` (`cs_script_undo`, `cs_cut_undo`) | `DraftDeckViewModel.stepGlobalSnapshot(-1)` on `02 SCRIPT`; `StudioViewModel.undoCoreEdit()` (`coreUndoStack`) on `03 CUT`. | `AircheckRunningAppE2ETest`, `ClipStudioV5ComposeTest` |
| 3 | Global Dock | In-place busy progress (`cs_dock_busy`) | Driven strictly by `draftState.isBusy`/`busyLabel` and `studioState.isCoreStageBusy`/`coreStageProgress`/`isGeneratingBackdrop`/`exportState`. | `ClipStudioV5ComposeTest`, `ClipStudioV5ExhaustiveE2ETest` |
| 4 | `01 SPARK` | `TONE` pills (`cs_tone_*`) | Persists `DraftTone` via `DraftDeckViewModel.setTone`; sent on `/v1/draft/{angles,arc,reroll-beat,synthesize-script,brush-swap,followup}`. | `AircheckRunningAppE2ETest`, `ClipStudioV5ViewModelTest` |
| 5 | `01 SPARK` | `hold to record your idea` (`cs_record_idea`) | Press-and-hold `VadAudioCaptureEngine` -> WAV capture -> `/v1/draft/transcribe` + `/v1/draft/angles` + `/v1/draft/arc`. | `ClipStudioV5ComposeTest`, `ClipStudioV5ViewModelTest` |
| 6 | `01 SPARK` | `type it instead` (`cs_type_instead`) | Opens `TypedNoteBottomSheet` -> `DraftDeckViewModel.submitTypedNote` -> live `/v1/draft/angles` + `/v1/draft/arc` + shot previews. | `AircheckRunningAppE2ETest` |
| 7 | `01 SPARK` | `Continue your last video` + `SHITPOST`/`SOLARPUNK` (`cs_continue_card`, `cs_followup_dir_*`) | Bound to `StudioUiState.lastCompletedVideo` from Room `clips` (`READY`); hidden when DB is empty; calls `POST /v1/draft/followup` (`angle="overview"`). | `ClipStudioV5ExhaustiveE2ETest` |
| 8 | `01 SPARK` | Follow-Up Sheet (`cs_followup_sheet`, `answer`/`part_2`/`update`, `cs_followup_use`) | Queries real `LocalGraphDatabase` reply (`topReplyQuestion`, omitted when `null`), calls `POST /v1/draft/followup`, loads 4-beat plan via `loadFollowupIntoSpark`. | `ClipStudioV5ExhaustiveE2ETest` |
| 9 | `01 SPARK` | Note strip `play` (`cs_note_play`) | `DraftDeckViewModel.togglePlaySeedAudio()` backed by Android `MediaPlayer` over `lastSeedAudioPath` (`isPlayingSeedAudio`). | `ClipStudioV5ExhaustiveE2ETest` |
| 10 | `01 SPARK` | Note strip `edit` (`cs_note_edit`) | Opens `NoteEditBottomSheet` with text edit (`submitTypedNote`), `keep last 15s` (`distillLast15Seconds`), and explicit `re-record`. | `ClipStudioV5ExhaustiveE2ETest` |
| 11 | `01 SPARK` | Beat shot strip (`cs_shot_b_s`, `cs_add_shot_b`) | Cycles `ShotType.nextInCycle()` (`FACE -> PIP -> BROLL -> CARD`), adds `BROLL`, fetches & caches real `180×252` JPEGs from `POST /v1/studio/shot-preview`. | `AircheckRunningAppE2ETest`, `test_clip_studio_v5_backend.py` |
| 12 | `01 SPARK` | Beat sparkle rewrite (`cs_reroll_beat_i`) | Calls `DraftDeckViewModel.rerollArcBeat(i)` (`POST /v1/draft/reroll-beat`) with per-beat busy state and refreshes shot previews. | `AircheckRunningAppE2ETest` |
| 13 | `01 SPARK` | `anchors` button + sheet (`cs_anchors_button`, `cs_anchors_sheet`) | Binds `doc.extractedAngles` + `doc.blindspotAngles`, toggles via `toggleAngleChip`, refreshes via `requestFreshAngles` (`more`). | `AircheckRunningAppE2ETest` |
| 14 | `01`/`02` Dock | `hold: add` / `hold: say` (`cs_hold_direction`, `cs_speak_own_words`) | Real mic capture + transcription + steering (`startSteerClutch`/`stopSteerClutch`) and voice splice (`startVoiceSplice`/`stopVoiceSplice`). | `ClipStudioV5ComposeTest`, `ClipStudioV5ViewModelTest` |
| 15 | `01 SPARK` | `write the script` (`cs_write_script`) | Calls `DraftDeckViewModel.writeVideoScript()` (`POST /v1/draft/synthesize-script`) and auto-navigates to `02 SCRIPT`. | `AircheckRunningAppE2ETest` |
| 16 | `02 SCRIPT` | `keyboard` (`cs_keyboard`, `cs_raw_editor`, `cs_raw_apply`) | Full-script manual edit via `applyFullScriptKeyboardEdit` with undo snapshot preservation. | `AircheckRunningAppE2ETest` |
| 17 | `02 SCRIPT` | `timer` length toggle + `shorter`/`longer` (`cs_length_toggle`, `cs_length_shorter`, `cs_length_longer`) | Calls `stepScriptLength(±12)` -> real `/v1/draft/density` rewrite updating clauses, word count, and duration. | `AircheckRunningAppE2ETest` |
| 18 | `02 SCRIPT` | 3-way posture (`cs_version_0..2`) | Switches real `doc.candidates` (`direct`, `punchy`, `story`) with live computed word counts. | `AircheckRunningAppE2ETest` |
| 19 | `02 SCRIPT` | Single-tap phrase swap (`cs_para_0`, `cs_phrase_sheet`, `cs_nudge_*`, `cs_pin_phrase`, `cs_keep_phrase`) | `selectPhraseSingleTap` -> `/v1/draft/brush-swap`, nudges (`SHARPER`/`SIMPLER`/`MORE`), verbatim lock (`togglePhraseLock`), and `keepSelectedPhrase`. | `AircheckRunningAppE2ETest` |
| 20 | `02 SCRIPT` | `make the video` (`cs_create_video`) | `StudioViewModel.openStoryInCorePipeline(..., shotPlan)` maps `PIP`/`BROLL` beats to `BROLL_CUTAWAY` clauses and seeds sanitized per-story `QUOTE_BRIEF` overlay in Room. | `AircheckRunningAppE2ETest`, `ClipStudioV5ViewModelTest` |
| 21 | `03 CUT` | `218dp` Video Monitor (`cs_cut_monitor`, `cs_cut_play`, `cs_cut_live_caption`) | Bound to `AircheckPlayerController` (`ExoPlayer`) playing local CAS / remote MP4/WAV with real timecode & live captions. | `ClipStudioV5ExhaustiveE2ETest` |
| 22 | `03 CUT` | Tool pills (`cs_cut_captions`, `cs_cut_strip_silence`, `cs_cut_punch_in`, `cs_cut_aspect`, `cs_cut_look`) | Persist `CoreRenderOptions` in Room and open `Video look`. | `AircheckRunningAppE2ETest` |
| 23 | `03 CUT` | Single-tap clause expand (`cs_clause_i`, `cs_clause_edit_btn_i`, `cs_clause_broll_btn_i`) | Seeks `ExoPlayer`, opens inline clause editor -> `editCutClauseText` (populating `unsyncedClauseIndices`), toggles `BROLL_CUTAWAY`. | `AircheckRunningAppE2ETest`, `ClipStudioV5ExhaustiveE2ETest` |
| 24 | `03 CUT` | Sequential primary key (`cs_cut_primary`) | `voice the script` -> `render talking head` -> inline edit -> amber `re-voice 1 edited line` -> `render the video` -> `export & share` (`MediaStore` `content://`) -> `share again`. | `ClipStudioV5ExhaustiveE2ETest` |
| 25 | `Video look` | Header `Duplicate look` (`cs_look_duplicate`) | Creates/persists base look if initial draft, duplicates `*_COPY` in Room, selects it, and shows `"look duplicated"`. | `AircheckRunningAppE2ETest` |
| 26 | `Video look` | Live preview + `BLUR` / `BRIGHTNESS` / `LIGHT` + backdrop generator | Real `AsyncImage` plate + `ColorMatrix` EV/light + blur; `±` steppers & `warm`/`neutral`/`cool` segments; `CoreStageWorker` `OP_BACKDROP` (`POST /v1/newsroom/backdrop`) with honest HTTP error surfacing. | `AircheckRunningAppE2ETest` |
| 27 | `Video look` | `title` tab (`cs_title_input`, `cs_title_refresh`, `cs_tag_*`) | Persists `chyronHeadline` in Room, calls `refreshHeadlineWithAi()` (`POST /v1/ai-assist`), sets `BreakingNewsStyle`. | `AircheckRunningAppE2ETest` |
| 28 | `Video look` | `cards` tab (`cs_cards_summary`, `cs_overlay_*`, `cs_open_overlay_builder`) | Displays sanitized real `SavedNewsroomOverlay` headlines + voiced `startMs` (or honest `"shown for Ns once voiced."` when unvoiced), toggles attachment in Room, opens `NewsroomStudioScreen`. | `AircheckRunningAppE2ETest`, `AircheckNewsroomAndVoiceCareE2ETest` |
| 29 | `Video look` | `use this look` (`cs_use_look`) | `StudioViewModel.useLookAndReturn` persists slider/light edits, marks default look in Room, navigates to `03 CUT`, and displays `"look applied · render to see it"`. | `AircheckRunningAppE2ETest` |

---

## 4. Surgical Polish & Edge-Case Fixes Applied During Phase 4 QA

1. **Quote Card Prompt Sanitization & Per-Story Overlay Deduplication (`StudioViewModel.kt`)**:
   - Added `StudioViewModel.sanitizeCardQuoteHeadline` and `truncateOnWordBoundary` to strip LLM shot-prompt prefixes (`Text card reading:`, `Quote card:`, `On-screen text:`, `Title card:`, `Card:`) and surrounding quotes while truncating cleanly on word boundaries (`<= 56` chars with `…`).
   - Fixed `openStoryInCorePipeline` so it only reuses an existing `QUOTE_BRIEF` overlay when `headline.equals(quoteHeadline, ignoreCase = true)`, creating a fresh Room-persisted `SavedNewsroomOverlay` whenever a new story has a different card headline.
2. **`Video look` Above-the-Fold Geometry & Honest Unvoiced Card Timing (`NewsroomLookScreen.kt`)**:
   - Tuned `VideoLookPreviewPanel` (`206.dp`), tab bar (`44.dp`), tab body spacing (`spacedBy(12.dp)`), and `LookStepperSlider` (`44.dp` round keys, `spacedBy(4.dp)`) so `BLUR`, `BRIGHTNESS`, and 100% of the 3-way `LIGHT` (`warm / neutral / cool`) segmented bar sit unclipped above `"use this look"` on initial open (verified geometrically via `boundsInRoot` in `AircheckRunningAppE2ETest`).
   - Updated `LookCardsTab` (`resolveCardAppearanceMs`) to show `appears at m:ss` only when `clauses.any { it.endMs > 0L }`, and `"shown for Ns once voiced."` prior to TTS synthesis (zero invented WPM timestamps).
3. **Initial-Draft `Duplicate look` Enablement & Monotonic Timestamps (`NewsroomLookScreen.kt`, `StudioViewModel.kt`)**:
   - Fixed `cs_look_duplicate` being disabled (`enabled = looks.isNotEmpty() || persisted != null`) on first open before any look was saved; enabled whenever `!isLookBusy` so duplicating the initial `LOOK_01` draft persists slider edits and creates `LOOK_01_COPY` immediately with monotonic `createdAtMillis`.
4. **`03 CUT` Top-Bar Dirty Badge Un-Truncated Layout & `<= 2:1` Clause Label Density (`ClipStudioKit.kt`, `CutScreen.kt`)**:
   - Wrapped `CsTopBar` trailing actions in a weighted `Row(Modifier.weight(1f))` with compact crumb spacing and `Modifier.weight(1f, fill = false)` on `cs_cut_badge`, ensuring `"1 LINE NEEDS RE-VOICE"` renders completely un-truncated without squishing `undo` or `settings`.
   - Combined dirty + cutaway clause state into a single status line (`"edited · voice not synced · b-roll"`).
5. **Custom Loopback Port Routing in `AircheckContainer.draftApiClientFor` (`AircheckApplication.kt`)**:
   - When `profileStore.tailscaleRigUrl` explicitly specifies `http://127.0.0.1:<port>` or `http://localhost:<port>`, `usbLoopbackUrl` now honors that explicit loopback URL rather than unconditionally probing `:8443` first, while preserving default `:8443` USB-reverse fallback for LAN/Tailscale URLs.
6. **Android 17 `16k` Emulator `ExoPlayer` Software Codec Priority & Idle Recovery (`AircheckPlayerController.kt`, `CutScreen.kt`)**:
   - Configured `DefaultRenderersFactory.setEnableDecoderFallback(true)` and a custom `MediaCodecSelector` deprioritizing broken QEMU `c2.goldfish.*` video decoders behind Google `c2.android.*` software decoders on the Android 17 16KB-page-size emulator, plus `STATE_IDLE` re-prepare recovery in `prepareUri`/`togglePlayPause` and `onRelease = { view.player = null }` on `PlayerView`.

---

## 5. Complete Test Suite Verification Ledger (`0` Failures)

| Suite | Command | Result | Duration |
|---|---|---|---|
| **Python Mac-Rig Backend** | `cd sync-trainer/home-daemon && /Users/mac/.venv/bin/python -m pytest -q tests/test_drafting_passes.py tests/test_clip_studio_v4.py tests/test_clip_studio_v5_backend.py tests/test_core_pipeline_endpoints.py tests/test_talking_head_multibeat.py` | **68 passed, 0 failed** | `45.71s` |
| **Node Remotion Worker** | `cd /Users/mac/Aircheck/backend/remotion-worker && npm test` | **19 passed, 0 failed** | `48.57s` |
| **Android Unit + Robolectric Compose** | `ANDROID_SDK_ROOT="/opt/homebrew/share/android-commandlinetools" ANDROID_HOME="/opt/homebrew/share/android-commandlinetools" ./gradlew testDebugUnitTest :app:installDebug :app:installDebugAndroidTest --console=plain` | **128 passed, 0 failed** (`:app` 90, `:sync-trainer:android-worker` 11, `:core-verifier` 8, `:ui-clipper` 8, `:core-engine` 7, `:audio-edl` 4) | `36.0s` |
| **Android 17 Emulator E2E (`API 37`, `emulator-5554`)** | `adb -s emulator-5554 shell am instrument -w -r -e class com.example.AircheckNewsroomAndVoiceCareE2ETest,com.example.ClipStudioV5ExhaustiveE2ETest,com.example.AircheckRunningAppE2ETest com.aistudio.aircheck.vqxkrm.test/androidx.test.runner.AndroidJUnitRunner` | **5 passed, 0 failed** (`AircheckNewsroomAndVoiceCareE2ETest`: 1/1; `ClipStudioV5ExhaustiveE2ETest`: 3/3; `AircheckRunningAppE2ETest`: 1/1) | `62.80s` |

### Captured Android 17 Verification Artifacts (`/Users/mac/.gemini/antigravity/brain/7beeef31-63d9-46bb-ba18-c384bc8087fd/`)
- `01_spark_empty_v5.png` — `01 SPARK` first-run state with `TONE` pills, `76dp` hold-to-record key, `type it instead`, and Room-backed `Continue your last video` card.
- `02_spark_outline_v5.png` — `01 SPARK` 4-beat live outline with `180×252` Mac-rig JPEG shot previews (`face`, `corner head`, `b-roll`, `card`), `+` shot adder, sparkle rewrite keys, and Note Strip.
- `03_spark_anchors_v5.png` — `Anchors` bottom sheet (`FROM YOUR NOTE` + `SUGGESTED` + `more` + `keep 3 anchors`).
- `04_script_versions_v5.png` — `02 SCRIPT` 3-way posture control (`direct · punchy · story`), first-tap hint, time-gutter paragraphs, and 2-key dock.
- `05_script_length_v5.png` — `02 SCRIPT` expanded `±12`-word `shorter` / `longer` length bar.
- `06_script_phrase_swap_v5.png` — `02 SCRIPT` single-tap inverse phrase selection + `SWAP THIS PHRASE` sheet + context-switched `pin` / `keep this` dock.
- `07_cut_broll_mode_v5.png` — `03 CUT` `b-roll` mode with per-clause `b-roll` / `face` toggles and `"b-roll added"` toast.
- `08_cut_clause_expanded_v5.png` — `03 CUT` single-tap expanded clause revealing inline `edit words` and `back to face` keys.
- `09_video_look_background_v5.png` — `Video look` `background` tab with live `:8443` studio plate (`10%` blur, `+0.2` EV, `warm` grade) and 100% unclipped `BLUR` / `BRIGHTNESS` / `LIGHT` controls above `"use this look"`.
- `10_video_look_title_v5.png` — `Video look` `title` tab after live AI headline polish (`CLOUD AI EDITING SUBSCRIPTIONS DEAD`) and `breaking` tag selection.
- `11_video_look_cards_v5.png` — `Video look` `cards` tab showing sanitized quote card headline (`"Zero monthly fees. Full local AI video editing." · shown for 4 seconds once voiced.`).
- `12_cut_look_applied_toast_v5.png` — Return to `03 CUT` displaying `"look applied · render to see it"` toast.
- `13_cut_settings_v5.png` — `Studio Settings` sheet confirming live `http://127.0.0.1:8443` `Connected` status and `Voice Model Care` / `Core Story Pipeline` / `Create Newsroom Overlays` entries.
- `14_spark_continue_directions_v5.png` — `01 SPARK` expanded 2-up `SHITPOST` / `SOLARPUNK` follow-up split panel above `Continue your last video`.
- `15_spark_followup_sheet_v5.png` — `Follow up your last video` bottom sheet with real `LocalGraphDatabase` reply question, `answer / part 2 / update` selector, and 4-beat `solarpunk` follow-up draft.
- `16_cut_dirty_revoice_v5.png` — `03 CUT` dirty state after inline word edit showing un-truncated `"1 LINE NEEDS RE-VOICE"` top badge, `"edited · voice not synced · b-roll"`, and amber `"re-voice 1 edited line"` primary key.
- `17_cut_rendered_export_v5.png` — `03 CUT` rendered MP4 loaded in `ExoPlayer` with `"export & share"` primary commit key.

---

## 6. Operational & Residual Risk Notes

1. **Alibaba Bailian Monthly Token-Plan Quota Exhaustion (Live Runtime Mitigation Verified)**:
   - During live E2E testing on 2026-11-25, Alibaba DashScope (`bl`) began returning HTTP `429 insufficient_quota` / `Throttling.AllocationQuota` (`"Your token-plan 1-month quota has been exhausted. The quota will reset at 12-21 16:00:00 UTC."`).
   - Using `ota_server.py`'s built-in `POST /v1/llm/config` endpoint, live `:8443` text/JSON drafting stages (`draft.*`, `ai_assist`, `broll_planner`) were switched without daemon restart to `remote_openai` (`gpt-4.1-mini`) + `local_mlx` (`Qwen3-4B-Instruct-2507-4bit` on `:8080`).
   - Studio backdrop generation (`POST /v1/newsroom/backdrop` in `studio_cut.py`) shells out exclusively to `bl image generate`; until Bailian quota resets or an OpenAI/local Flux image provider fallback is added to `generate_backdrop_plate`, new prompt-to-plate generation returns HTTP `502 backdrop_failed` (which the Android app surfaces honestly in `CsStatusLine` with zero fake success toast, while existing cached plates in `renders/plates/*.png` continue serving `200 OK`).
2. **Android SDK/NDK Host Path Note**:
   - On this Mac host, `/opt/homebrew/share/android-commandlinetools` holds the complete Android 37 SDK + NDK `28.2.13676358` + CMake toolchain (`$HOME/Library/Android/sdk/ndk/28.2.13676358` contains an incomplete `.installer` stub); pass `ANDROID_SDK_ROOT="/opt/homebrew/share/android-commandlinetools" ANDROID_HOME="/opt/homebrew/share/android-commandlinetools"` when building JNI modules.
