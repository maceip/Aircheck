import test from "node:test";
import assert from "node:assert/strict";
import { compileTimeline } from "../dist/timeline.js";

test("compileTimeline aligns word-anchored evidence cards, punch-in cuts, and 0.5s cover frame", () => {
  const tl = compileTimeline({
    fps: 30,
    coverSec: 0.5,
    words: [
      { word: "Cloud", startMs: 0, endMs: 250 },
      { word: "GPU", startMs: 250, endMs: 500 },
      { word: "prices", startMs: 500, endMs: 850 },
      { word: "fell", startMs: 850, endMs: 1100 },
      { word: "forty", startMs: 1100, endMs: 1450 },
      { word: "percent.", startMs: 1450, endMs: 1900 },
    ],
    beats: [
      {
        id: "b0",
        ordinal: 0,
        spokenText: "Cloud GPU prices fell.",
        anchorWord: "prices",
        evidenceSource: "Q4 GPU Index",
      },
      {
        id: "b1",
        ordinal: 1,
        spokenText: "Forty percent.",
        anchorWord: "forty",
        depthTextTag: "-40%",
      },
    ],
  });

  assert.equal(tl.coverFrames, 15);
  assert.equal(tl.captions.length, 2);
  assert.equal(tl.events.length, 2);

  // Beat 0 anchored at "prices" (500ms -> frame 15 + 15 = 30) triggers PiP shrink for evidence card
  assert.equal(tl.events[0].triggerFrame, 30);
  assert.equal(tl.events[0].pipShrink, true);
  assert.equal(tl.events[0].citation, "Q4 GPU Index");

  // Beat 1 anchored at "forty" (1100ms -> frame 15 + 33 = 48) triggers 1.14x hook punch-in
  assert.equal(tl.events[1].triggerFrame, 48);
  assert.equal(tl.events[1].punchInScale, 1.14);
  assert.equal(tl.events[1].pipShrink, false);
});
