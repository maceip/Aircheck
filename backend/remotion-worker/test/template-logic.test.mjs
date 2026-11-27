import test from "node:test";
import assert from "node:assert/strict";
import { pickActiveOverlay, presenterFit } from "../dist/compositions/BroadcastTemplate.js";

test("pickActiveOverlay shows each overlay for its durationSec at the start of its slot", () => {
  const fps = 30;
  const total = 600; // 20 s, two overlays -> 10 s slots
  const a = { kind: "QUOTE_BRIEF", headline: "A", durationSec: 5 };
  const b = { kind: "QUOTE_BRIEF", headline: "B", durationSec: 3 };
  assert.equal(pickActiveOverlay([a, b], 0, total, fps), a);
  assert.equal(pickActiveOverlay([a, b], 149, total, fps), a);
  assert.equal(pickActiveOverlay([a, b], 150, total, fps), undefined);
  assert.equal(pickActiveOverlay([a, b], 300, total, fps), b);
  assert.equal(pickActiveOverlay([a, b], 389, total, fps), b);
  assert.equal(pickActiveOverlay([a, b], 390, total, fps), undefined);
  // A single overlay is no longer pinned for the whole clip.
  assert.equal(pickActiveOverlay([a], 100, total, fps), a);
  assert.equal(pickActiveOverlay([a], 200, total, fps), undefined);
  // No duration means the overlay fills its slot.
  const open = { kind: "LOWER_THIRD", headline: "C" };
  assert.equal(pickActiveOverlay([open], 599, total, fps), open);
  assert.equal(pickActiveOverlay([], 0, total, fps), undefined);
});

test("presenterFit never crops a portrait presenter to a chest band in a landscape frame", () => {
  const portrait = 1080 / 1920;
  // Portrait presenter, full 16:9 frame: shown whole over a blurred fill.
  assert.deepEqual(presenterFit(portrait, 1920, 1080, false), { letterbox: true, objectPosition: "50% 50%" });
  // Same presenter in the landscape PiP box: cropped, but biased to keep the head.
  assert.deepEqual(presenterFit(portrait, 600, 480, true), { letterbox: false, objectPosition: "50% 18%" });
  // Matching aspect (portrait in 9:16, landscape in 16:9): plain centered cover.
  assert.deepEqual(presenterFit(portrait, 1080, 1920, false), { letterbox: false, objectPosition: "50% 50%" });
  assert.deepEqual(presenterFit(16 / 9, 1920, 1080, false), { letterbox: false, objectPosition: "50% 50%" });
  // Unknown aspect keeps the legacy centered cover.
  assert.deepEqual(presenterFit(undefined, 1920, 1080, false), { letterbox: false, objectPosition: "50% 50%" });
});
