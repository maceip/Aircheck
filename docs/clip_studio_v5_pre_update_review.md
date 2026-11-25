# Clip Studio v5 — Pre-Update Hygiene & UX Review

## 1. Executive Summary & Architectural Principles

Clip Studio v5 evolves Aircheck's four primary authoring surfaces **in place** inside `app/src/main/java/com/example/ui/clipstudio/`:

- `ClipStudioKit.kt` — shared design tokens, neumorphic soft keys, sunken wells/panels, busy progress dock, and toast primitives
- `ClipStudioShell.kt` — top-level stage navigation (`01 SPARK` · `02 SCRIPT` · `03 CUT` + `Video look`), mic/notification permissions, and toast host
- `SparkScreen.kt` — `01 SPARK` (first-run empty state with Tone selector & Continue Last Video flow + 4-beat outline with immediate rig shot previews & Anchors sheet)
- `ScriptScreen.kt` — `02 SCRIPT` (3-way posture segmented control, expandable ±12-word length row, single-tap phrase swap/pin sheet, and keyboard mode)
- `CutScreen.kt` — `03 CUT` (218dp sunken monitor with centered 64dp play/pause + live caption, 44dp horizontal tool pill row, 2-way `words`/`b-roll` mode switch, tap-to-expand clause actions, and single sequential primary pipeline key)
- `NewsroomLookScreen.kt` — `Video look` (replaces `04 NEWSROOM`: clean 232dp preview without broadcast clutter, 3-way `background`/`title`/`cards` tabs, ±stepper tracks for blur & EV, 3-way light switch, editable AI-refreshable title + tag pills, real attached cards list, and single `"use this look"` commit key)

### Non-Negotiable Constitutional Guardrails for Wave 2 (`@compose-v5-builder`)
1. **Evolve in place — zero duplicate V2/V5 screen files**: Never create `SparkScreenV5.kt`, `ClipStudioShellV2.kt`, or parallel packages. All updates happen directly in `ui/clipstudio/*`.
2. **Zero mocks, zero fake timers, zero hardcoded sample strings (Law 2)**: None of the prototype's hardcoded strings (`BEATS`, `VERSIONS`, `ALTS`, `CLAUSES`, `FOLLOW`, `DIRS`), `noop` handlers, or `setInterval`/`setTimeout` progress/preview timers may appear in production Compose code. Every control must bind to real `DraftDeckViewModel` / `StudioViewModel` state, Room persistence, `CasMediaStore`, `ExoPlayer` / `MediaPlayer`, and Mac-rig endpoints.
3. **UI Label Density Limit**: Never place more than **2 adjacent labels** next to any single primary label (at most a 2:1 ratio of secondary/metadata/badge tokens per primary label; never stack 3+ inline chips or metadata badges).

---

## 2. Pre-Update Hygiene Pass: Dead Duplicated Files Removed & Warnings Resolved

### 2.1 Orphaned v3 Drafting Deck Interfaces Removed (`git rm`)

Prior to v4 (`ClipStudioShell`), Aircheck used a 4-stage plain-text drafting surface (`01 SEED · 02 PICK · 03 CUT · 04 SHIP`) under `app/src/main/java/com/example/ui/draft/`. Once `MainActivity.kt` switched to `ClipStudioShell`, all **8 UI files** (`6,617 lines` / `282,704 bytes`) in `ui/draft/` became completely unreachable dead code, while `DraftDeckViewModel.kt` remained the live shared ViewModel.

A full symbol and import audit across `app/src/{main,test,androidTest}` confirmed **zero external callers** of any declaration in those 8 files (`ScriptScreen.kt`, `DraftDeckViewModel.kt`, and `OnDevicePromptEngine.kt` reference `DraftApiClient.MIN_DENSITY_DELTA` / `MAX_DENSITY_DELTA`, not the private constants in `DensityScrubberRule.kt`). No helper migration into `ClipStudioKit.kt` was required.

| Removed File (`app/src/main/java/com/example/ui/draft/`) | Lines | Top-Level Composables / Types Removed | Superseded By (`ui/clipstudio/`) |
|---|---:|---|---|
| `TactileDraftingDeckScreen.kt` | 631 | `TactileDraftingDeckScreen`, `PendingMicAction` | `ClipStudioShell.kt` |
| `TopTelemetryStrip.kt` | 322 | `TopTelemetryStrip` | `ClipStudioKit.kt` (`CsHeader`, `StageCrumb`) |
| `SeedStagePanel.kt` | 953 | `SeedEyeZone`, `SeedThumbZone`, `AngleToggleChipView` | `SparkScreen.kt` |
| `PickStagePanel.kt` | 606 | `PickEyeZone`, `PickThumbZone`, `PickCandidateCard` | `ScriptScreen.kt` |
| `CutSurgeryCanvas.kt` | 2,088 | `CutSurgeryEyeZone`, `BrushMicroStripState`, `CutGestureTextSurface`, `BrushMicroStripOverlay` | `ScriptScreen.kt` & `CutScreen.kt` |
| `CutThumbInstrumentBay.kt` | 784 | `CutThumbInstrumentBay`, `ClauseVersionRingTrackpad`, `CutSalvageTrayRow`, `GlobalHistoryScrubberBar` | `ScriptScreen.kt` (`ThumbPad` -> v5 Phrase Sheet) |
| `DensityScrubberRule.kt` | 424 | `DensityScrubberRule` | `ScriptScreen.kt` (v5 Top-Bar Length Row ±12w) |
| `ShipRadarDrawer.kt` | 809 | `ShipEyeZone`, `ShipRadarDrawer`, `GraphMatchRow` | `ScriptScreen.kt` / deferred network pass |
| **Total Removed** | **6,617** | **8 orphaned duplicate v3 UI files** | **0 remaining duplicate Drafting Deck screens** |

### 2.2 Preserved Files & Compiler Warning Cleanup

- **Preserved ViewModel**: `app/src/main/java/com/example/ui/draft/DraftDeckViewModel.kt` is untouched and remains the single source of truth for `01 SPARK` and `02 SCRIPT`.
- **Preserved Standalone Screens** (verified by `AircheckSeparateScreensAndA2uiTest` and `AircheckNewsroomAndVoiceCareE2ETest`):
  - `app/src/main/java/com/example/ui/screens/VoiceModelCareScreen.kt`
  - `app/src/main/java/com/example/ui/screens/SystemDeckSheet.kt`
  - `app/src/main/java/com/example/ui/screens/ClipReviewScreen.kt`
  - `app/src/main/java/com/example/ui/screens/CorePipelineScreen.kt`
  - `app/src/main/java/com/example/ui/screens/NewsroomStudioScreen.kt`
- **Compiler Warnings Fixed in `ClipReviewScreen.kt`**:
  - Removed 4 K2 `UNNECESSARY_NOT_NULL_ASSERTION` (`!!`) warnings on `clip!!.remoteFinalUrl` (line 101), `clip!!.remoteLipsyncUrl` (line 103), and `clip!!.errorMessage!!` (line 193) by binding local `val remoteFinalUrl`, `val remoteLipsyncUrl`, and `val clipError` references for clean smart-casting.
- **Wave 2 Compiler Hygiene Note (`ui/clipstudio/*`)**:
  - `SparkScreen.kt:118`, `ScriptScreen.kt:580`, `CutScreen.kt:431`, and `NewsroomLookScreen.kt:539,742` currently emit Compose deprecation warnings for the legacy `BasicTextField(value: String, onValueChange: ...)` overload; Wave 2 (`@compose-v5-builder`) should use `BasicTextField(state = rememberTextFieldState(...))` when rewriting those fields in v5.

---

## 3. UX Defect Audit: Current v4 (`1a`–`1d`) vs. Target v5 (`3a` / `Aircheck Prototype v2`)

### 3.1 Cross-Cutting Systemic Defects (`ClipStudioKit.kt` & `ClipStudioShell.kt`)

| Dimension | Current v4 Defect (`ui/clipstudio/*`) | Target v5 Fix (`3a` / `Aircheck Prototype v2.dc.html`) |
|---|---|---|
| **Touch Target Geometry** | Header strip is `36dp`; gear/undo keys are `30×28dp`; version keys `24dp`; nudge chips `28dp`; note expand key `58×26dp`; timeline toggles `26dp`. Chronic fat-finger errors. | `56dp` top bar with `44×44dp` utility keys (radius `12dp`); `44dp` minimum hit targets everywhere (`40×40dp` only on SPARK note strip play/edit); `64dp` bottom dock keys (radius `18dp`). |
| **Thumb-Zone Hierarchy & Primary CTA** | Every screen stacks 5–9 equal-weight bevelled keys across thumb wells + 3-button `56dp` docks (`re-record` + `keep last 15s` + `write 60s script`; `play` + `sync voice` + `re-render cut`). Next-step white key is `11sp` crammed beside lookalikes. | **One-row bottom dock** (`padding 14/12/10dp`, `12dp` gap) with at most **2 keys**: optional `92×64dp` press-and-hold voice key (`#D9381E`, `mic` over `11sp` mono) + flex-1 `64dp` `#EBEBEF` primary commit key (`15sp` Geist Mono Medium + trailing `arrow_forward`/`check`) naming the exact next step. |
| **Busy & Feedback States** | `CsStatusLine` renders a `10sp` `… busy` line under the header while leaving primary buttons disabled without progress context. Empty states use `#4A4A52` on `#070709` (~2:1 contrast, illegible). | Primary commit key transforms in place into a `64dp` sunken box (`#17171B`, radius `18dp`) with an `80ms` linear `#26262C` fill bar, `14sp` mono label left, and percentage right. Dismissible `48dp` `#EBEBEF` toast (`13sp` mono + `"ok"`) floats `112dp` above bottom. |
| **Surface & Key Visual Language** | Pure `#000000` body + sharp `4dp` hardware bevels (`KeyRole` lip) + text-only/ASCII symbols (`->`, `↶`, `⌜⌝`) that require reading to distinguish. | `#141417` body; raised soft graphite keys (`#1C1C21`, subtle border/shadow, radii `12–22dp`); pressed/selected `#17171B` inner-shadow keys; `#101013` sunken segmented wells (radius `16dp`); `#1A1A1F` cards/sheets (radii `20–26dp`); `#000000` sunken text/video panels (radius `18dp`); crisp Material Symbols Sharp (`Icons.Sharp.*`) icons; instant or `80–120ms` linear motion (no springs). |
| **Top Stage Stepper** | `StageCrumb` uses `11sp` dim `01 · 02 · 03` text with tiny `2dp` horizontal padding; `ScriptScreen` omits `01`/`03` crumbs altogether to cram `v1/v2/v3` into the `36dp` header. | Consistent `56dp` top bar across `01 SPARK`, `02 SCRIPT`, `03 CUT`: active stage is a `44dp` sunken segment (`#17171B`, radius `12dp`, Ndot `13sp`, e.g. `01 SPARK`); inactive stages are `40dp` bare targets in `13sp` Geist Mono (`#EBEBEF` completed, `#4A4A52` future). |

---

### 3.2 `1a` — `01 SPARK` (`SparkScreen.kt`)

**Current v4 Defects (`SparkScreen.kt` lines 61–296, UX Review `1a #1–#5`):**
1. **Cramped 36dp header (`#1`)**: `30×28dp` settings key (`cs_spark_settings`) and `11sp` `TARGET 01:00 · ~150 WDS` compete in a `36dp` bar.
2. **Truncated note strip & hidden seed editing (`#2`)**: Voice note renders as a single truncated `11sp` mono line (`cs_voice_note_preview`) with a tiny `58×26dp` `"expand"` button (`cs_voice_note_toggle`) and **no audio playback button** to hear the recorded voice note. First-run empty state has no onboarding hierarchy, no tone selection before recording, and no way to continue a previous video.
3. **Invisible beat re-roll & zero visual planning (`#3`)**: `"redo ↻"` (`cs_arc_generate`) is an unframed `10sp` text link (~`20dp` tall). Beat rows (`cs_arc_beat_$i`) have **no visible re-roll button** (only a micro-header hint `// TAP ANY BEAT TO RE-ROLL`), and visual shot planning (`face`, `pip`, `broll`, `card`) does not exist at outline time.
4. **Cluttered thumb zone & destructive red-key collision (`#4`)**: Thumb zone stacks 2 chip wells (`KEEP FROM YOUR NOTE` + `ADD AN UNEXPLORED ANGLE`), 2 utility keys (`↻ fresh angle ideas` + red `● hold to add direction`), and 3 dock keys (red `● re-record` + `keep last 15s` + `write 60s script`). Because `"hold to add direction"` and `"re-record"` share identical red `VOICE` styling in adjacent rows, users easily wipe their note when trying to add direction.
5. **Weak primary CTA & low-contrast empty text (`#5`)**: `"write 60s script"` (`11sp`) shares its row with `"keep last 15s"`, making a destructive trim look like a peer action. Empty chip placeholders (`"No claims extracted yet."`) render in `#4A4A52` on `#070709` (~2:1 contrast).

**Target v5 Design (`3a` / `README.md` lines 83–192):**
- **Dedicated Empty First-Run Mode** (when `doc.seedTranscript.isBlank() && !state.isFollowupLoaded`):
  - Centered intro block: `STEP 1 OF 3 · YOUR IDEA` (`11sp` mono `#8E8E96`), `"What's the clip about?"` (`26/32sp` Geist Medium), `"Pick a tone, then hold the red key and talk. Rough is fine. Length is set from what you say."` (`16/23sp` `#8E8E96`).
  - `TONE` horizontal pill row (`44dp` height, radius `22dp`): `direct` · **`hot take`** (default) · `story` · `explainer` · `shitpost` · `solarpunk` (single-select `#EBEBEF` commit fill).
  - Prominent `76dp` red press-and-hold **Record key** (`#D9381E`, radius `20dp`, `34sp` `mic`, `"hold to record your idea"` `15sp` bold mono + `"press and hold"` `12sp` sub-label; switches to `"recording 0:07"` / `"release when done"` while held, then busy state `"outlining your idea…"`).
  - `56dp` standard key `"type it instead"` (`keyboard` icon) opening a clean text-entry sheet/modal.
  - Bottom **Continue Last Video card** (shown **only** when `studioState.lastCompletedVideo != null`): `52×68dp` real thumbnail + duration, `OR CONTINUE YOUR LAST VIDEO`, real clip title, `"follow-up script drafted"`, `expand_less` chevron revealing the `94dp` 2-up LLM-authored split panel (`SHITPOST` / `SOLARPUNK`) and **Follow-Up Bottom Sheet** (`Last video` card + `FOLLOW-UP ANGLE` 3-way `answer | part 2 | update` + 4-beat `DRAFT FOLLOW-UP · 60S` + `"new idea"` / `"use this follow-up"` dock).
- **Populated Outline Mode**:
  - **Single-row Note Strip**: `40×40dp` `play_arrow` key (plays real recorded voice note via `MediaPlayer`), 1-line ellipsized tag (`● YOUR IDEA · 0:18:` in `#D71921` or `FOLLOW-UP:` in `#8E8E96`) + quote (`14sp` `#C8C8CE`), and `40×40dp` `edit` key (opens note edit/re-record/`keep last 15s` sheet so re-record is safely separated from direction mic).
  - **Top-Bar `anchors` Key**: `44dp` key (`anchor` icon + `"anchors"` + `20dp` `#EBEBEF` active count badge) opening the **Anchors Bottom Sheet** (`FROM YOUR NOTE` + `SUGGESTED` `44dp` pills + `"keep N anchors"` commit button).
  - **Sunken Black Outline Panel** (`#000`, radius `18dp`, flex-1): 4 equal-flex beat rows (`54dp` gutter with beat label over time window; `16/21sp` Geist beat text; **Shot Strip** underneath with `30×42dp` vertical preview thumbnails cycling `face -> pip -> broll -> card`, dashed `30×42dp` `+` b-roll adder, and deduplicated summary e.g. `"face · corner head"`; explicit `44×44dp` `auto_awesome` sparkle button on the right to rewrite only that beat).
  - **Slim 2-Key Dock**: `92×64dp` red `"hold: add"` key + flex-1 `64dp` white `"write the script"` key.

---

### 3.3 `1b` — `02 SCRIPT` (`ScriptScreen.kt`)

**Current v4 Defects (`ScriptScreen.kt` lines 127–305 & 395–573, UX Review `1b #1–#5`):**
1. **Tiny 24dp version switcher & missing stage crumbs (`#1`)**: `v1 direct` / `v2 punchy` / `v3 story` are `24dp` tall (`10sp` text) squeezed inside the `36dp` header alongside word count and a `30×28dp` undo button, crowding out `01` / `03` stage navigation.
2. **Hidden two-tap phrase selection & unlabeled history dots (`#2`)**: Selecting a phrase requires tapping first word then last word (`anchor` state in `ScriptScreen.kt:200–208`), explained only inside the bottom thumb pad. Gutter `"••"` history dots (`ScriptScreen.kt:354–358`) step clause versions with zero label or affordance.
3. **Ambiguous drag-only 28dp density ruler (`#3`)**: `DensityRuler` (`28dp` tall, drag-only) displays `"SHORTER -15S"` when idle (`ScriptScreen.kt:449`), reading like a static button rather than a continuous scale.
4. **9-target thumb-pad explosion (`#4`)**: Selecting a phrase opens 3 option keys (`36dp`), 4 nudge pills (`28dp`), and 2 utility keys (`pin phrase`, `prior version`) — 9 cramped targets in a scrolling `270dp` well that crushes the script eye zone.
5. **Undersized voice splice & 4th control row (`#5`)**: `"● hold to speak own words"` uses `10sp` text; the persistent `38dp` `X NETWORK` bar adds a fourth stacked control strip above the `3-key` dock.

**Target v5 Design (`3a` / `README.md` lines 193–219):**
- **Top Bar (`56dp`)**: `01 · 02 SCRIPT · 03` crumbs on left; `44dp` **Length key** (`timer` + `"1:00"`), `44×44dp` **Keyboard key** (`keyboard`), and `44×44dp` **Undo key** (`undo`) on right (settings gear hidden on SCRIPT to keep top bar clean).
- **Expandable Length Row** (when Length key toggled): `48dp` `"− shorter"` key, center readout (`"0:59 · 148 words"` over `"target 1:00"`), and `48dp` `"longer +"` key — each step applies ±12 words via real density rewrite.
- **Sunken 3-Way Version Segmented Control** (`#101013` well, `48dp` segments): `direct 148` · `punchy 112` · `story 161` (`13sp` mono name + `11sp` 65%-opacity live word count).
- **Sunken Black Script Panel** (`#000`, radius `18dp`, scrolls): Dismissible first-tap hint row (`touch_app` `"Tap any phrase to swap or pin it."`); paragraph rows with `56dp` time gutter + `20/30sp` Geist w370 text; **single tap** selects a natural phrase chunk (`#EBEBEF` fill, `#000` text) and triggers swap alternatives; pinned phrases show `#1E1E24` background + `1dp` white underline.
- **Focused Phrase Card & Context-Switching Dock**:
  - When a phrase is selected: raised `#1A1A1F` `SWAP THIS PHRASE` card (`46dp` option keys with `▶` on active choice + 3 `44dp` nudge pills: `bolt punchier`, `short_text plainer`, `refresh more`), and dock switches cleanly to `92×64dp` `push_pin` `"pin"` / `"pinned"` + flex-1 `64dp` `"keep this"` (`check`).
  - When nothing is selected: dock shows `92×64dp` red `"hold: say"` + flex-1 `64dp` white `"make the video"` (`arrow_forward`). `X NETWORK` bar is removed from `02 SCRIPT` to eliminate the 4th control bar.

---

### 3.4 `1c` — `03 CUT` (`CutScreen.kt`)

**Current v4 Defects (`CutScreen.kt` lines 137–394 & 409–558, UX Review `1c #1–#5`):**
1. **Overpacked 36dp header (`#1`)**: Stage crumbs, `1 UNSYNCED EDIT` badge, `00:14 / 00:58` timecode, `30×28dp` undo, and `30×28dp` settings all squeeze into `36dp`.
2. **Hidden 10sp text toggles (`#2`)**: The **only** entry point to `04 NEWSROOM` look styling is a `10sp` grey text string `"LOOK: STUDIO_NIGHT"` (`CutScreen.kt:237–243`), beside `10sp` bracketed text links `[captions: on]` and `[9:16 | 16:9]`.
3. **Undiscoverable long-press word editing (`#3`)**: Editing a spoken line requires a hidden `onLongClick` (`CutScreen.kt:453`), hinted only by `9sp` `#4A4A52` text (`"long-press a line to edit its words"`, ~2:1 contrast).
4. **Duplicate voice-sync buttons (`#4`)**: `"re-voice edited words"` in the `2×2` deck (`cs_cut_revoice`, line 293) and `"sync voice (1)"` in the bottom bar (`cs_cut_sync`, line 362) invoke the exact same `vm::resyncEditedVoice` method simultaneously on screen.
5. **Disabled primary commit & split controls (`#5`)**: While an edit is unsynced, the white primary key (`cs_cut_primary`) disables itself instead of guiding the user through re-voicing, while `play` sits in the bottom dock far from the video monitor.

**Target v5 Design (`3a` / `README.md` lines 220–263):**
- **Top Bar (`56dp`)**: `01 · 02 · 03 CUT`, amber Ndot `11sp` badge (`"1 line needs re-voice"` / `"1 UNSYNCED"` when `unsynced.isNotEmpty()`), `44×44dp` `undo`, and `44×44dp` `settings`.
- **Sunken Video Monitor (`218dp`, `#000`, radius `18dp`)**: Top-left `11sp` mono timecode (`0:14 / 0:58`), centered `64×64dp` circular translucent play/pause button (`play_arrow` / `pause`), bottom live caption overlay (`14/19sp` Geist Medium white), and `3dp` `#D71921` progress bar at bottom edge.
- **Horizontal 44dp Tool Pill Row**: `closed_caption captions`, `content_cut trim pauses`, `zoom_in punch-in`, `crop_portrait 9:16` / `16:9`, and `palette look` (prominent 44dp pill opening `Video look`). Active toggles use `#EBEBEF` commit fill.
- **Sunken 2-Way Mode Switch (`44dp` segments)**: `notes words` · `movie b-roll`.
- **Tap-to-Expand Transcript Panel** (no long-press!):
  - Single tap on any clause seeks to `clause.startMs` and expands two `44dp` inline action keys: `edit edit words` (opens inline clause editor) and `movie b-roll here` / `back to face`.
  - Edited unsynced clauses show a `2dp` `#E6A100` amber left border + `sync_problem edited · voice not synced` (`12sp` mono `#E6A100`). B-roll clauses show a clean `movie B-ROLL` badge.
- **B-Roll Mode**: `≥56dp` rows with timecode, 2-line max spoken text, and a `44dp` `face` / `b-roll` toggle key.
- **Single Primary Dock Key** (2×2 grid & duplicate sync button removed): Advances sequentially through the real pipeline — amber REPAIR `"re-voice N edited line(s)"` -> white COMMIT `"voice the script"` / `"render talking head"` / `"render the video"` -> `"export & share"` -> raised `"share again"`.

---

### 3.5 `1d` — `Video look` (`NewsroomLookScreen.kt`, replaces `04 NEWSROOM`)

**Current v4 Defects (`NewsroomLookScreen.kt` lines 106–826, UX Review `1d #1–#5`):**
1. **11sp back link & two competing white commit keys (`#1`)**: `"<- back"` is an `11sp` text link, while a `92×28dp` white `"save look"` commit key sits in the header (`cs_look_save`) competing with `"set default look"` (`cs_look_default`) in the bottom dock.
2. **Confusing `01–04` tab numbering (`#2`)**: Tabs are labeled `01 BACKDROP`, `02 B-ROLL`, `03 HEADLINES`, `04 CARDS`, colliding with the global `01 SPARK · 02 SCRIPT · 03 CUT` stage numbers, and duplicating B-roll toggling already on `03 CUT`.
3. **Broadcast clutter & 9sp debug tags on preview (`#3`)**: Canvas overlays `ACTIVE: STUDIO_BACKDROP`, a red `LIVE` / `ON AIR` bug, dashed safe-frame crosshairs, and `TAP TO STYLE: QUOTE_CARD` / `TAP TO STYLE: HEADLINE_BAR` boxes simultaneously, obscuring the actual look.
4. **Invisible ruler tap halves & split light toggles (`#4`)**: `CsTickRuler` relies on invisible left/right tap zones, while `light: warm` and `light: cool` are two separate checkboxes representing one 3-state setting (`warm` / `neutral` / `cool`).
5. **Ambiguous 3-peer dock (`#5`)**: Bottom dock splits into `duplicate look`, `test on clip`, and `set default look`, forcing users to guess between saving, setting default, and returning to `03 CUT`.

**Target v5 Design (`3a` / `README.md` lines 264–296):**
- **Clean 56dp Header**: `44×44dp` `arrow_back` key, `"Video look"` title (`17sp` Geist Medium), and `44×44dp` `content_copy` duplicate-look key (`LIVE` bug and `ACTIVE:` debug labels removed).
- **Sunken 232dp Preview (`#000`, radius `18dp`)**: Live backdrop plate (`AsyncImage` with real blur, EV brightness, and warm/neutral/cool grade) + optional kicker tag (`breaking` `#D71921`, `developing` `#FFB800`, `explainer` `#EBEBEF`, or hidden for `none`) + clean `rgba(0,0,0,.82)` radius-`10dp` title box (`15/20sp` Geist Medium).
- **Sunken 3-Way Tab Control (`48dp` segments)**: `wallpaper background` · `title title` · `format_quote cards` (no `01–04` numbers; redundant `B-ROLL` tab removed).
- **Background Tab**: Explicit `48×48dp` round `remove` (`−`) / `add` (`+`) keys flanking a `14dp` sunken `#101013` progress track (`#EBEBEF` fill) for `BLUR` (`±5%`, `0..100%`) and `BRIGHTNESS` (`±0.2 EV`, `-3.0..+3.0 EV`), plus a sunken **3-way `LIGHT` segmented control** (`warm` · `neutral` · `cool`), alongside real look selector / backdrop prompt generation so real rig plates remain creatable and selectable.
- **Title Tab**: `TITLE ON SCREEN` sunken `#000` editable field (`56dp` min-height) with a `48×48dp` `refresh` AI-headline-polish key, plus `TAG ABOVE TITLE` `44dp` pills (`none` · `breaking` · `developing` · `explainer`).
- **Cards Tab**: Displays real attached `QUOTE_BRIEF` / `STAT_CALLOUT` overlays from `StudioUiState` with real text & timing (or honest `"0 cards attached"` empty state + real attach/builder controls).
- **Single Primary Dock Key**: `64dp` white `"use this look"` (`check`) — saves pending edits, marks look as default in Room, navigates back to `03 CUT`, and triggers toast `"look applied · render to see it"`.

---

## 4. Explicit Zero-Mock Action Matrix (`Aircheck Prototype v2.dc.html` -> Production Wiring)

Every `noop`, fake `setTimeout`/`setInterval` timer, CSS placeholder, and hardcoded JS constant in `Aircheck Prototype v2.dc.html` is cataloged below with the **exact real persisted state and backend behavior** that Wave 2 (`@compose-v5-builder`, consuming `@vm-state-architect` & `@rig-backend`) **MUST** wire.

| # | Screen / Surface | Control / Element in `Aircheck Prototype v2.dc.html` | Prototype Fake / `noop` Behavior (FORBIDDEN) | Required Real Production Wiring (Android + Mac Rig) |
|---:|---|---|---|---|
| 1 | Global Top Bar | `settings` gear key (line 31) | `onClick="{{ noop }}"` | Invoke `onOpenSettings()` -> opens real `SystemDeckSheet` modal in `MainActivity`. |
| 2 | Global Top Bar (`SCRIPT` & `CUT`) | `undo` key (lines 30, 376) | `() => setState({ toast: 'undid last change' })` without mutating state | On `02 SCRIPT`: call `draftVm.stepGlobalSnapshot(-1)` (enabled iff undo stack available) + real toast. On `03 CUT`: call `studioVm.undoCoreEdit()` (enabled iff `studioState.coreUndoDepth > 0`). |
| 3 | Global Dock | Busy progress bar (lines 68, 106, 149, 187, 293–300) | `runBusy(label, ms, done)` fake `80ms` `setInterval` timer | Driven strictly by real job state: on `SPARK`/`SCRIPT`, `draftState.isBusy` + `draftState.busyLabel` (real stage progress / indeterminate-to-real step fraction, never a fake timer); on `CUT`/`Video look`, `studioState.isCoreStageBusy` + `coreStageStatusLabel` + `(coreStageProgress * 100).toInt()%` (or `isGeneratingBackdrop` / `exportState == EXPORTING`). |
| 4 | `01 SPARK` (Empty) | `TONE` pills (`direct`, `hot take`, `story`, `explainer`, `shitpost`, `solarpunk`, lines 39, 391) | Local JS index `tone: 1` unused by backend | Bind to `draftState.document.tone: DraftTone` (`HOT_TAKE` default) via `draftVm.setTone(tone)`. Propagated in `DraftApiClient` to `/v1/draft/angles`, `/v1/draft/arc`, `/v1/draft/reroll-beat`, `/v1/draft/synthesize`, `/v1/draft/synthesize-script`, `/v1/draft/brush-swap`, and `/v1/draft/followup`. |
| 5 | `01 SPARK` (Empty) | Red `hold to record your idea` key (lines 40, 302–316, 386–389) | Fake `recT` timer + `runBusy('outlining your idea…', 1800)` -> reveals hardcoded `BEATS` | Use press-and-hold `CsHoldKey` -> `onRequestMic { draftVm.toggleLatchMic() }` (`VadAudioCaptureEngine`), showing real `draftState.recordingElapsedMs`, saving `lastSeedAudioPath` & `lastSeedDurationSec`, running real ASR + `/v1/draft/angles` + `/v1/draft/arc` (with `shots`). |
| 6 | `01 SPARK` (Empty) | `type it instead` key (lines 41, 390) | `() => setState({ hasNote: true })` (loads hardcoded quote & `BEATS`) | Open real typed-idea input sheet/dialog; on submit call `draftVm.submitTypedNote(text)` (persisting `seedTranscript` and running real `extractAngles` + `proposeArc` + shot previews). |
| 7 | `01 SPARK` (Empty) | `OR CONTINUE YOUR LAST VIDEO` card + `DIRS` (`SHITPOST` / `SOLARPUNK`) split panel (lines 43–48, 263–266, 395–396) | Hardcoded `"Your phone just killed a subscription"`, `"0:58"`, CSS striped thumb, and hardcoded `DIRS` array | Bind strictly to `studioState.lastCompletedVideo: LastVideoSummary?` queried from real Room `clips` (`status == READY`). **When `lastCompletedVideo == null`, hide the Continue card completely (never render placeholder data).** Thumbnail loads real `thumbFilePath` / video frame via `AsyncImage` (with duration from `durationMs`). Tapping calls `studioVm.requestFollowupOverview(video)` (`POST /v1/draft/followup` `angle="overview"`) to populate real LLM-authored `SHITPOST` & `SOLARPUNK` hooks/beats. |
| 8 | `01 SPARK` Follow-Up Sheet | `LAST VIDEO` card, `"Most asked in replies"`, `FOLLOW-UP ANGLE` (`answer` / `part 2` / `update`), `DRAFT FOLLOW-UP · 60S`, `use this follow-up` (lines 204–226, 258–262, 397–404) | Hardcoded `"2 DAYS AGO"`, `"what about battery life?"`, `FOLLOW` array, `setTimeout(..., 700)` fake `"drafting…"`, fake `useFollow` | Render real relative age from `video.updatedAtMillis`, real `mmss(video.durationMs)`, and real `video.topReplyQuestion` from `localGraphDatabase` (**omit the reply line if `topReplyQuestion == null`**). Angle segments call `selectFollowupAngle("answer" \| "part_2" \| "update")` -> real `POST /v1/draft/followup` via `LlmRouter`. `"use this follow-up"` calls `draftVm.loadFollowupIntoSpark(...)`, populating real 4 `arcBeats` + `shots`, setting `isFollowupLoaded = true` and `followupSourceLabel`, and starting real shot previews. |
| 9 | `01 SPARK` (Outline) | Note strip `play_arrow` key (line 52) | `onClick="{{ noop }}"` | Wire to `draftVm.togglePlaySeedAudio()` backed by real Android `MediaPlayer` playing `draftState.lastSeedAudioPath` (shows `pause`/`stop` while `draftState.isPlayingSeedAudio`; disabled when no recorded audio file exists, e.g. typed note or follow-up). |
| 10 | `01 SPARK` (Outline) | Note strip `edit` key (lines 54, 390) | `reRecord: () => setState({ hasNote: false })` (instantly wipes outline!) | Open real Note Edit sheet with explicit non-destructive options: edit note text (`submitTypedNote` / `commitRawKeyboardEdit`), `re-record` (returns to empty seed capture or starts seed mic), and `keep last 15s` (`draftVm.distillLast15Seconds()`, relocated from v4 dock). |
| 11 | `01 SPARK` (Outline) | Beat `scenes` thumbnails, `+` add-scene key, & `260ms` preview loader (lines 60, 286–290, 408–419) | `setInterval(..., 260)` incrementing `shotsReady` to swap `hourglass_empty` for CSS gradients | Bind to real `beat.shots: List<ShotSpec>` returned by `/v1/draft/arc` & `/v1/draft/reroll-beat`. Tapping a thumb calls `draftVm.cycleBeatShot(beatIdx, shotIdx)` (`FACE -> PIP -> BROLL -> CARD`); `+` calls `draftVm.addBeatBrollShot(beatIdx)` (both undoable). Each `ShotSpec` triggers real `POST /v1/studio/shot-preview` on the Mac rig, downloads `/v1/renders/shotprev_<sha16>.jpg` into local cache, and displays via `AsyncImage` from `draftState.shotPreviews[shot.id]` (`hourglass_empty` shown strictly while request is in-flight or rig offline — zero fake timers). |
| 12 | `01 SPARK` (Outline) | Beat `auto_awesome` sparkle key (lines 61, 420) | `setTimeout(..., 900)` toggling between 2 hardcoded strings in `BEATS` | Call `draftVm.rerollArcBeat(i)` (`POST /v1/draft/reroll-beat` with active `tone`), showing `"rewriting…"` on beat `i` while in-flight, then updating real beat text + `shots` + shot previews. |
| 13 | `01 SPARK` (Outline) | Top-bar `anchors` key + Anchors Sheet (lines 27, 192–203, 276, 422–426) | Hardcoded 5-item `angles` JS array | Bind `FROM YOUR NOTE` to `doc.extractedAngles` and `SUGGESTED` to `doc.blindspotAngles` (plus `doc.steeringConstraints` if any). Tapping any pill calls `draftVm.toggleAngleChip(chip.id)`. Badge displays real selected anchor count. |
| 14 | `01 SPARK` & `02 SCRIPT` | Dock voice hold key (`hold: add` / `hold: say`, lines 67, 104, 302–316) | Fake `recT` timer + hardcoded toast `'noted: "keep it under 60s"'` / `'your words added to beat 2'` | Press-and-hold `CsHoldKey`: on `SPARK`, `draftVm.startSteerClutch()` / `stopSteerClutch()`; on `SCRIPT`, `draftVm.startVoiceSplice()` / `stopVoiceSplice()` (real audio capture + transcription + LLM steering/splice). |
| 15 | `01 SPARK` (Outline) | Primary `write the script` key (lines 69, 429) | `runBusy('writing 3 versions…', 2000)` -> switches to hardcoded `VERSIONS` | Call `draftVm.writeVideoScript()` (`POST /v1/draft/synthesize-script` with `tone`, selected anchors, and 4 `arcBeats`). `ClipStudioShell` auto-navigates to `02 SCRIPT` once real `doc.candidates` arrive. |
| 16 | `02 SCRIPT` | Top-bar `keyboard` key (line 28) | `onClick="{{ noop }}"` | Toggle full-script manual keyboard editing (`draftVm.toggleRawKeyboardMode()` / `draftVm.applyFullScriptKeyboardEdit(newText)`), saving a global undo snapshot and updating active candidate clauses. |
| 17 | `02 SCRIPT` | Top-bar `timer` length key + `shorter` / `longer` row (lines 28, 78–84, 432–435) | Mutates `lenDelta ± 12` counter without changing a single word of the script | Bind to `draftState.lengthRowOpen` (`draftVm.toggleLengthRow()`); `"shorter"` calls `draftVm.stepScriptLength(-12)` and `"longer"` calls `draftVm.stepScriptLength(+12)` -> executes real `commitDensityScrub` rewrite on rig/on-device engine and updates real word count & estimated duration. |
| 18 | `02 SCRIPT` | Version segments (`direct 148 · punchy 112 · story 161`, lines 75–77, 245–249, 431) | Hardcoded `VERSIONS` array & static word counts | Render real `doc.candidates` (`c.postureLabel.lowercase()` + live `c.plainText()` word count), calling `draftVm.selectCandidateSlot(i)`. |
| 19 | `02 SCRIPT` | Single-tap phrase selection + `SWAP THIS PHRASE` options + 3 nudges (`punchier`, `plainer`, `more`) + `pin` + `keep this` (lines 90, 94–108, 250, 439–455) | Hardcoded `ALTS` array; nudges only show fake toast ``rewriting ${label}…`` without calling LLM | Single word/chunk tap calls `draftVm.selectPhraseSingleTap(offset)` / `selectPhraseSpan(range)`, immediately invoking `performBrushSwap(range)` (`/v1/draft/brush-swap`). Option tap calls `selectBrushAlternative(i)`. Nudges call real `performBrushNudge(SHARPER)` (`punchier`), `performBrushNudge(SIMPLER)` (`plainer`), and `refreshPhraseAlternatives()` / `performBrushNudge(MORE)` (`more`). `pin` calls `togglePhraseLock(range)`. `keep this` calls `keepSelectedPhrase()`. |
| 20 | `02 SCRIPT` | Primary `make the video` key (lines 107, 455) | `runBusy('voicing script…', 2000)` -> switches to hardcoded `CLAUSES` | Call `studioVm.openStoryInCorePipeline(plainText, headline, postCard, shotPlan = ...)` mapping SPARK/SCRIPT `shotPlan` onto real `CoreStoryClause`s (`PIP`/`BROLL` -> `BROLL_CUTAWAY` with `brollCaption = shot.prompt`; `CARD` -> creates & attaches real `SavedNewsroomOverlay` `QUOTE_BRIEF` in Room) and navigating to `03 CUT`. |
| 21 | `03 CUT` | Video Monitor, `play_arrow` / `pause`, timecode, live caption, progress bar (lines 112–117, 318–322, 458–460) | `setInterval(..., 100)` advancing fake `pos` up to `58000ms` over empty black box | Render real `PlayerView` bound to singleton `studioVm.playerController` (`ExoPlayer`) playing local CAS / remote `finalVideo` -> `talkingHead` -> `ttsAudio`, real `positionMs` / `durationMs`, real live caption from `session.alignedWords` / active `CoreStoryClause` when `opts.captions == true`, and clear empty state when `sourceUri == null`. |
| 22 | `03 CUT` | Tool row pills (`captions`, `trim pauses`, `punch-in`, `9:16`/`16:9`, `look`, lines 118–124, 461–465) | Ephemeral JS booleans | Bind to persisted `session.renderOptions`: `studioVm.setCoreCaptions(!opts.captions)`, `setCoreStripSilence(!opts.stripSilence)`, `setCorePunchIn(!opts.punchIn)`, `setCoreAspect(...)`, and `onOpenNewsroom()`. |
| 23 | `03 CUT` | Clause tap -> `edit words` & `b-roll here` / `back to face` (lines 132–138, 353–354) | `edit words` has `onClick="{{ noop }}"`! | Single tap on clause seeks `playerController.seekTo(clause.startMs)` and expands the 2-action row. `edit words` opens real inline `BasicTextField` editor -> `studioVm.editCutClauseText(idx, draft)` (populating real `session.unsyncedClauseIndices`). `b-roll here` / `back to face` (and B-roll tab toggles) call `studioVm.toggleCutClauseBroll(idx)`. |
| 24 | `03 CUT` | Primary sequential pipeline key (lines 150, 363–369) | `runBusy` fake timers cycling `'revoice' -> 'render' -> 'export' -> 'done'` | Drive strictly from real `CorePipelineSession` & `ExportState`: `!hasTtsAudio` -> `"voice the script"` (`runCoreStage1Tts()`); `unsynced.isNotEmpty()` -> amber REPAIR `"re-voice N edited line(s)"` (`resyncEditedVoice()`); `!hasTalkingHead` -> `"render talking head"` (`runCoreStage3SpeechToHead()`); `!cutCurrent` -> `"render the video"` (`runCoreStage4ChopBroll()`); not yet exported -> `"export & share"` (`exportClip(clip, true)`); exported -> `"share again"` (`exportClip(clip, true)`). |
| 25 | `Video look` | Header `content_copy` duplicate key (line 158) | `onClick="{{ noop }}"` | Call `studioVm.duplicateNewsroomLook(draft.id)`, select the new Room-persisted `NewsroomLook`, and post real toast `"look duplicated"`. |
| 26 | `Video look` | Preview canvas, backdrop, & sliders (lines 160–175, 472–482) | CSS radial-gradient + hardcoded `"Your phone just killed a subscription"` + unpersisted `blur`/`ev`/`light` | Render real `AsyncImage(draft.plateUrl)` with real `ColorMatrix` (`brightnessEv`, `light` `warm`/`neutral`/`cool`) + `Modifier.blur` (keep real look picker / rig backdrop prompt generation accessible when editing/creating plates). `−`/`+` keys update `draft.blurPercent` (`±5%`) and `draft.brightnessEv` (`±0.2 EV`); 3-way `LIGHT` updates `draft.light`. Preview title shows real `chyronHeadline` / `session.title` + real `BreakingNewsStyle` kicker tag. |
| 27 | `Video look` (`title` tab) | `TITLE ON SCREEN` field & `refresh` key + `TAG ABOVE TITLE` pills (lines 176–181, 267, 483) | Static hardcoded text span + `refresh` `onClick="{{ noop }}"` + unpersisted `tag` index | Editable `BasicTextField` persisted via `studioVm.setCoreChyronHeadline(...)`; `refresh` key calls `studioVm.refreshHeadlineWithAi()` (real `/v1/ai-assist` `polish_headline` on Mac rig); tag pills (`none` · `breaking` · `developing` · `explainer`) call `studioVm.setTitleTagStyle(tag)` (`CLEAN`, `BREAKING`, `DEVELOPING`, `EXCLUSIVE`). |
| 28 | `Video look` (`cards` tab) | Attached cards summary (line 183) | Hardcoded `"1 quote card attached"` & `""Pay once for the chip." appears at 0:45 for 4 seconds."` | Compute strictly from real `studioState.savedOverlays` (`QUOTE_BRIEF` / `STAT_CALLOUT`) and `session.selectedOverlayIds` + real clause/overlay timing. Display `"0 cards attached"` when none are selected (never invent quote text), and provide real saved-card attach toggles + `open overlay builder` (`onOpenOverlayBuilder`). |
| 29 | `Video look` | Primary `use this look` dock key (lines 188, 484) | `runBusy('applying look…', 1400)` -> switches screen | Call `studioVm.useLookAndReturn(lookId)` (persists `updateNewsroomLook` + `setDefaultNewsroomLook` in Room, posts toast `"look applied · render to see it"`) and navigate back to `03 CUT`. |

---

## 5. Existing Test Tags & Regression Contracts to Preserve in Wave 2

When evolving `ui/clipstudio/*` to v5, Wave 2 (`@compose-v5-builder`) should keep existing E2E / Compose test tags working (or update only Clip Studio-specific assertions in `ClipStudioV5ComposeTest` while keeping `AircheckSeparateScreensAndA2uiTest` and `AircheckNewsroomAndVoiceCareE2ETest` 100% green):

- Shell & navigation: `clip_studio_shell`, `cs_spark_root`, `cs_script_root`, `cs_cut_root`, `cs_newsroom_root`, `cs_crumb_1`, `cs_crumb_2`, `cs_crumb_3`
- `01 SPARK`: `cs_spark_settings`, `cs_voice_note_preview`, `cs_arc_beat_$i`, `cs_keep_${chip.id}`, `cs_angle_${chip.id}`, `cs_hold_direction`, `cs_write_script` (plus new v5 tags for tone pills, seed hold-to-record, `type it instead`, note `play`/`edit`, beat shot thumbs/add, `anchors` button/sheet, and `continue last video` card/sheet)
- `02 SCRIPT`: `cs_version_$i`, `cs_word_count`, `cs_script_undo`, `cs_script_body`, `cs_para_$i`, `cs_option_$i`, `cs_nudge_${n.wire}`, `cs_pin_phrase`, `cs_speak_own_words`, `cs_keyboard`, `cs_create_video` (plus new v5 tags for length toggle & `shorter`/`longer` keys and `keep this`)
- `03 CUT`: `cs_cut_badge`, `cs_cut_timecode`, `cs_cut_undo`, `cs_cut_settings`, `cs_cut_monitor`, `cs_cut_play`, `cs_cut_captions`, `cs_cut_strip_silence`, `cs_cut_punch_in`, `cs_cut_aspect`, `cs_cut_look`, `cs_cut_mode_transcript`, `cs_cut_mode_timeline`, `cs_transcript`, `cs_clause_$idx`, `cs_clause_editor_$idx`, `cs_clause_apply_$idx`, `cs_timeline`, `cs_timeline_toggle_$idx`, `cs_cut_primary`
- `Video look`: `cs_newsroom_back`, `cs_look_duplicate`, `cs_look_canvas`, `cs_look_plate`, `cs_newsroom_tab_$i`, `cs_scrub_blur`, `cs_scrub_ev`, `cs_look_default` / `cs_use_look`
