# Aircheck design system (v2, approved by Ryan 2026-11-27)

Visual reference: `mockups.html` in `openrig-execution/aircheck-screens` (open via the review server). This file is the rule book. Every screen, sheet and button in the app uses ONLY the components below. Agents must not hand-roll buttons, colors, text styles, outlines or accent bars. Any new component or visual change needs Ryan's approval first.

## Look in one paragraph
The app is a light grey device (Teenage Engineering Recorder / Nothing OS). Normal app controls are white, round and raised, and turn red when selected. Anything that edits audio or video is a dark square key set into a black well (Recorder). Media itself — video, waveforms, timelines, meters — is shown on black displays drawn in thin lines (OP-1). One raised red primary key per screen. Every key presses down with a haptic tick.

## Tokens (`ui/design/AcTokens.kt`)
| token | value | use |
|---|---|---|
| chassis | #D9DAD7 | screen background |
| chassisHi / chassisLo | #F1F1EF / #A8A9A5 | panel bevel highlight / shade |
| ui | #F5F5F3 | app key + card surface |
| ink / ink2 | #141414 / #5C5D5A | text / secondary text |
| hairline | #C4C5C1 | dividers |
| red / redHi / redLo | #D71921 / #FF4A3D / #8E0F14 | primary, selected, active media icon, LED on |
| well | #0B0B0B | media key well |
| mediaKey / mediaKeyHi / mediaKeyLo | #303033 / #45454A / #1A1A1C | media key body |
| display | #0A0A0A | OP-1 display background |
| opBlue / opOrange / opGrey / opRed / opGreen | #4A7FC4 / #D08A3E / #9B9B9B / #EC4A2E / #3FBF7F | thin-line graphics INSIDE displays only |

No other colors in UI code. Specifically banned: lime / AcidVoltLime / channelAccent, BroadcastCobalt, Cs.Cobalt outlines, HwLed multicolor, Mint, any per-feature accent.

## Type (`ui/design/AcType.kt`)
Bundle fonts in `res/font`: Doto (800), Jost (200/300), Inter (400/500/600), JetBrains Mono (400/500).
| style | font | size | use |
|---|---|---|---|
| Step | Doto 800 | 17 | "01 SPARK", "02 CUT" header (active word in red) |
| Headline | Inter 600 | 24 | screen question/title |
| Title | Inter 600 | 18 | sheet titles |
| Body | Inter 400 | 17 | beat text, transcript, any content you read |
| BodySmall | Inter 400 | 14 | helper copy |
| Label | JetBrains Mono 500 | 13 | text on keys |
| Caption | JetBrains Mono 500 | 10.5, +0.1em, UPPERCASE | eyebrows, beat tags, timestamps |
| Numeral | Jost 200 | 15–30 | timecodes and numbers inside displays |
No raw `fontSize =` or ad-hoc `mono()/sans()` sizes outside these styles.

## Components (`ui/design/AcComponents.kt`)
All keys: raised at rest, pressed state sinks (inset shadow, 1–2dp down, scale 0.98), and call `LocalHapticFeedback.current.performHapticFeedback(HapticFeedbackType.KeyboardTap)` (or `View.performHapticFeedback(KEYBOARD_PRESS)`) on press-down. Min touch target 48dp.

1. **AcKey** (app control) — white `ui` surface, fully round (pill for text, circle for icon), soft neumorphic shadow (light top-left, grey bottom-right). `selected = true` → red fill, white content. Sizes: regular 46dp, small 38dp, icon 54/40dp. No LEDs, no outlines. Use for: navigation, tone choices, sheets, settings, edit/AI buttons on beats, anchors, back/undo/new.
2. **AcPrimaryKey** — red gradient, raised with a red-lo skirt and drop shadow, white icon + Inter 600 label. Exactly one per screen (record lives in the media well instead, see below). Use for: make the video, render the video, generate, save.
3. **AcMediaWell** + **AcMediaKey** — black well (radius 14, inner shadow) holding dark square keys (radius 12, top highlight, bottom skirt). White glyph; `active = true` turns the glyph red. Optional `toggle: Boolean?` shows a small LED (red glow on, dark off) only for on/off controls. Optional tiny mono caption under the glyph. Use ONLY for controls that edit or play media: record, play, pause, stop, split/trim, captions, b-roll add, aspect, overlays toggle.
4. **AcDisplay** — black rounded panel for media: video player, waveform, timeline, meters, timecode. Graphics drawn 1–1.5dp thin lines using op* colors; numbers in Numeral style.
5. **AcCard** — `ui` surface, radius 20, soft raised shadow, Caption tag + Body text. No left accent bars, no colored borders.
6. **AcField** — recessed well text field (inset shadow), Inter 16. Replaces Material OutlinedTextField.
7. **AcPanel** — chassis-colored grouping panel with bevel (Recorder body), e.g. the Recorder block on Spark.
8. **AcVideoOverlayKey** — a control drawn on top of video (e.g. play on the Cut player): translucent dark disc, white glyph, no elevation, raise or skirt. Use only over video.
9. **AcTileKey** — picture tile (studio mockup, 2026-11-28): raised `ui` tile, radius 16, a small AcDisplay picture over a Label; `selected = true` turns it red like AcKey. Use for visual choices: transition tiles, B-roll framing.
10. **AcLed** — the media-key on/off light on its own (red glow on, dark off). Only for real on/off state, e.g. a Newsroom graphic card's on-this-clip light.

**Exception:** Controls overlaid on video are flat (no elevation); the only exception to raised keys. Approved by Ryan 2026-11-28.

`AcKey(icon, badgeColor = …)` may carry a small status dot (e.g. the rig connection on the Cut settings key).

## Banned everywhere
- Vertical highlight bars / left accent rails on cards, keys or rows.
- Lime green buttons or outlines; colored outlines used to show state.
- Pills mixed with square keys; Material Outlined/Text buttons and OutlinedTextField.
- Multicolor LEDs; LEDs on keys without an on/off state.
- Flat primary buttons without elevation.

## Component migration map
| old | new |
|---|---|
| CsKey, CsKeyLabel, CsIconKey, CsPill, CsChip, CsBreakingChip, CsSegmentedRow, NudgePillKey, AnchorsTopKey, ModelButton, OutlinedButton, IconButton | AcKey |
| SeedRecordHeroKey, CsHoldKey, CsVoiceDockKey | AcMediaKey (record, red glyph) in AcMediaWell |
| "make the video", "render the video", "generate video", Save/Generate overlay buttons | AcPrimaryKey |
| VideoEditOledKey, CutToggleKey, CutStudioActionKey, HardwareKeyButton | AcMediaKey (toggle LED only where on/off) |
| AircheckTokens (Theme.kt broadcast palette), Cs colors, HwLed | AcTokens |
| CsWell / player frames / waveform / ruler | AcDisplay |

## Screen notes (from Ryan's review)
- Spark record: Recorder panel (AcPanel) with live waveform display and media well (record, play, stop). Tone choices = AcKey, selected red. "type it instead" = AcKey. Follow-up cards = AcCard, no rails.
- Spark beats: Body 17 beat text; each beat card has AcKey edit + AcKey AI rewrite (selected/red while generating). Bottom: media well record + AcPrimaryKey "make the video".
- Anchors sheet: Accept = AcPrimaryKey (not a notification-style bar); toggles become AcKey selected states; show what toggling does.
- Cut status (option C, approved by Ryan 2026-11-28): no black status boxes. One Caption line under the "02 CUT" step header shows voice / head / cut / export as ✓ done (ink), working… or redo (red), · not yet (muted). The rig connection is a dot on the settings AcKey: ink = online, red = offline. The freed height goes to the player.
- Cut: AcDisplay player ~2x larger with drag-resize grip; AcDisplay timeline with trim brackets and a draggable b-roll track (removes per-sentence b-roll tags and the b-roll tab); zoom key removed; media well: play, split, b-roll, captions(toggle); AcPrimaryKey render. Keys over the video are flat (AcVideoOverlayKey).
- Cut joins and lanes (studio mockup, approved 2026-11-28): blue ◆ at each sentence join on the VOICE lane (filled = not a hard cut) opens the Transition sheet; the read-only GFX lane shows when each graphic is on screen (chyron and bug span the clip), following the compositor the cut will use. Bottom row: overlays AcKey (Newsroom), transitions AcKey with the blue glyph (all joins), AcPrimaryKey. Renders start only from Cut.
- Transition sheet: 7 AcTileKey tiles, each a looping A→B preview (thin op lines), selected red; speed fast/med/slow AcKeys; "use for every join"; AcPrimaryKey done.
- B-roll sheet (tap a block): preview display, prompt AcField with AI key, pick photo/video, framing AcTileKey pictures (corner / full / split; a new block copies the last framing), delete, AcPrimaryKey done.
- Newsroom: one screen, no tabs. Preview display ~60% of the height, selected graphic outlined; one row of + new / AI 3-pack AcKeys and a card per graphic (AcCard with a miniature on a display and an AcLed for on/off); tapping a card opens a sheet with only that kind's fields + save. Bottom: backdrop AcKey + AcPrimaryKey done.
- Backdrop: set preview display, room prompt, accent, light, blur/exposure rulers, grain/border, presenter photo, saved looks; AcPrimaryKey "generate set". Window video / objects / outfit / motion wait for rig support.
- Settings: one look per video (mapped per provider internally); regroup; no rails.
