import test from "node:test";
import assert from "node:assert/strict";
import { compileTimeline } from "../dist/timeline.js";

test("compileTimeline aligns word-anchored evidence cards, punch-in cuts, and prevents Beat 0 from shadowing Beat 1", () => {
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
  assert.equal(tl.totalFrames, 72); // 15 cover + ceil(1.9 * 30) = 57 content
  assert.equal(tl.captions.length, 2);
  assert.equal(tl.events.length, 2);

  // Beat 0 anchored at "prices" (500ms -> frame 15 + 15 = 30), ends at Beat 1 triggerFrame (48)
  assert.equal(tl.events[0].triggerFrame, 30);
  assert.equal(tl.events[0].endFrame, 48);
  assert.equal(tl.events[0].pipShrink, true);
  assert.equal(tl.events[0].citation, "Q4 GPU Index");

  // Beat 1 anchored at "forty" (1100ms -> frame 15 + 33 = 48), runs to totalFrames (72)
  assert.equal(tl.events[1].triggerFrame, 48);
  assert.equal(tl.events[1].endFrame, 72);
  assert.equal(tl.events[1].punchInScale, 1.14);
  assert.equal(tl.events[1].pipShrink, false);
  assert.equal(tl.events[1].keyword, "-40%");
});

test("compileTimeline honors explicit startMs/endMs, sorts out-of-order ordinals, and clamps overlapping beats", () => {
  const tl = compileTimeline({
    fps: 30,
    coverSec: 0.5,
    words: [],
    durationMs: 4000,
    beats: [
      {
        ordinal: 1,
        startMs: 1500,
        endMs: 3500,
        bRollUrl: "http://127.0.0.1:8443/v1/plates/clip.mp4",
        bRollMediaType: "video",
        depthTextTag: "  +18%  ",
      },
      {
        ordinal: 0,
        startMs: 200,
        endMs: 2500, // overlaps Beat 1 startMs (1500ms -> frame 60)
        bRollUrl: "http://127.0.0.1:8443/v1/plates/plate.png",
        pipShrink: false, // explicit override even though bRollUrl is present
      },
    ],
  });

  assert.equal(tl.totalFrames, 15 + 120);
  assert.equal(tl.events.length, 2);

  // Sorted by ordinal: Beat 0 first, clamped to Beat 1's triggerFrame (15 + 45 = 60)
  assert.equal(tl.events[0].beatOrdinal, 0);
  assert.equal(tl.events[0].triggerFrame, 21);
  assert.equal(tl.events[0].endFrame, 60);
  assert.equal(tl.events[0].pipShrink, false);

  // Beat 1 starts at 60, ends at 15 + ceil(3.5 * 30) = 120
  assert.equal(tl.events[1].beatOrdinal, 1);
  assert.equal(tl.events[1].triggerFrame, 60);
  assert.equal(tl.events[1].endFrame, 120);
  assert.equal(tl.events[1].bRollMediaType, "video");
  assert.equal(tl.events[1].keyword, "+18%");
  assert.equal(tl.events[1].pipShrink, true);
});

test("compileTimeline computes durationMs precedence and 5000ms fallback when empty", () => {
  // 1. All empty -> 5000ms fallback -> 15 + 150 = 165 frames
  const fallbackTl = compileTimeline({ words: [], beats: [] });
  assert.equal(fallbackTl.totalFrames, 165);

  // 2. Beat endMs exceeds lastWordEndMs and durationMs
  const beatDrivenTl = compileTimeline({
    words: [{ word: "Hello", startMs: 0, endMs: 500 }],
    beats: [{ ordinal: 0, startMs: 0, endMs: 3200 }],
    durationMs: 2000,
  });
  assert.equal(beatDrivenTl.totalFrames, 15 + Math.ceil(3.2 * 30));

  // 3. Explicit durationMs exceeds words
  const explicitTl = compileTimeline({
    words: [{ word: "Hello", startMs: 0, endMs: 500 }],
    beats: [],
    durationMs: 2500,
  });
  assert.equal(explicitTl.totalFrames, 15 + 75);
});

test("compileTimeline chunks captions into 4-word non-overlapping half-open intervals clamped to totalFrames", () => {
  const words = [
    { word: "One", startMs: 0, endMs: 200 },
    { word: "Two", startMs: 200, endMs: 400 },
    { word: "Three", startMs: 400, endMs: 600 },
    { word: "Four", startMs: 600, endMs: 900 },
    { word: "Five", startMs: 900, endMs: 1200 },
  ];
  const tl = compileTimeline({ words, beats: [], fps: 30, coverSec: 0.5 });

  assert.equal(tl.captions.length, 2);
  assert.equal(tl.captions[0].words.length, 4);
  assert.equal(tl.captions[1].words.length, 1);

  assert.ok(tl.captions[0].endFrame <= tl.captions[1].startFrame);
  assert.ok(tl.captions[1].endFrame <= tl.totalFrames);

  for (const phrase of tl.captions) {
    for (let i = 0; i < phrase.words.length - 1; i++) {
      assert.ok(phrase.words[i].endFrame <= phrase.words[i + 1].startFrame);
    }
  }
});

function assertNoEventOverlaps(tl) {
  for (let i = 0; i < tl.events.length; i++) {
    const ev = tl.events[i];
    assert.ok(Number.isFinite(ev.triggerFrame) && Number.isFinite(ev.endFrame));
    assert.ok(ev.triggerFrame >= tl.coverFrames, `event[${i}].triggerFrame < coverFrames`);
    assert.ok(ev.endFrame <= tl.totalFrames, `event[${i}].endFrame > totalFrames`);
    assert.ok(ev.triggerFrame < ev.endFrame, `event[${i}] empty/inverted [${ev.triggerFrame}, ${ev.endFrame})`);
    if (i + 1 < tl.events.length) {
      assert.ok(
        ev.endFrame <= tl.events[i + 1].triggerFrame,
        `event[${i}] [${ev.triggerFrame}, ${ev.endFrame}) overlaps event[${i + 1}] [${tl.events[i + 1].triggerFrame}, ${tl.events[i + 1].endFrame})`,
      );
    }
  }
}

function assertNoCaptionOverlaps(tl) {
  for (let i = 0; i < tl.captions.length; i++) {
    const cap = tl.captions[i];
    assert.ok(Number.isFinite(cap.startFrame) && Number.isFinite(cap.endFrame));
    assert.ok(cap.startFrame >= tl.coverFrames);
    assert.ok(cap.endFrame <= tl.totalFrames);
    assert.ok(cap.startFrame < cap.endFrame);
    if (i + 1 < tl.captions.length) {
      assert.ok(
        cap.endFrame <= tl.captions[i + 1].startFrame,
        `caption[${i}] [${cap.startFrame}, ${cap.endFrame}) overlaps caption[${i + 1}] [${tl.captions[i + 1].startFrame}, ${tl.captions[i + 1].endFrame})`,
      );
    }
    for (let j = 0; j < cap.words.length; j++) {
      const w = cap.words[j];
      assert.ok(Number.isFinite(w.startFrame) && Number.isFinite(w.endFrame));
      assert.ok(w.startFrame < w.endFrame);
      if (j + 1 < cap.words.length) {
        assert.ok(
          w.endFrame <= cap.words[j + 1].startFrame,
          `caption[${i}].words[${j}] [${w.startFrame}, ${w.endFrame}) overlaps words[${j + 1}] [${cap.words[j + 1].startFrame}, ${cap.words[j + 1].endFrame})`,
        );
      }
    }
  }
}

test("compileTimeline eliminates beat triggerFrame collisions (startMs > durationMs, identical startMs, anchor collision, duplicate ordinal)", () => {
  // 1. startMs > durationMs (and no endMs): beat.startMs extends totalContentMs & colliding beats stay non-overlapping
  const gtDuration = compileTimeline({
    fps: 30,
    coverSec: 0.5,
    durationMs: 2000,
    words: [{ word: "Start", startMs: 0, endMs: 500 }],
    beats: [
      { ordinal: 0, startMs: 6000 },
      { ordinal: 1, startMs: 6000 },
    ],
  });
  assert.ok(gtDuration.totalFrames >= 15 + 180);
  assert.equal(gtDuration.events.length, 2);
  assertNoEventOverlaps(gtDuration);

  // 2. Identical startMs within duration
  const identicalStart = compileTimeline({
    fps: 30,
    coverSec: 0.5,
    durationMs: 5000,
    words: [],
    beats: [
      { ordinal: 0, startMs: 1000, endMs: 4000 },
      { ordinal: 1, startMs: 1000, endMs: 4500 },
    ],
  });
  assert.equal(identicalStart.events.length, 2);
  assertNoEventOverlaps(identicalStart);

  // 3. startMs collides with anchorWord
  const anchorCollision = compileTimeline({
    fps: 30,
    coverSec: 0.5,
    durationMs: 5000,
    words: [{ word: "marker", startMs: 1000, endMs: 1300 }],
    beats: [
      { ordinal: 0, startMs: 1000, endMs: 3500 },
      { ordinal: 1, anchorWord: "marker", endMs: 4500 },
    ],
  });
  assert.equal(anchorCollision.events.length, 2);
  assertNoEventOverlaps(anchorCollision);

  // 4. Duplicate ordinals
  const dupOrdinal = compileTimeline({
    fps: 30,
    coverSec: 0.5,
    durationMs: 5000,
    words: [],
    beats: [
      { ordinal: 0, startMs: 1000, endMs: 2500 },
      { ordinal: 0, startMs: 1000, endMs: 3000 },
      { ordinal: 0, startMs: 1000, endMs: 3500 },
    ],
  });
  assert.equal(dupOrdinal.events.length, 3);
  assertNoEventOverlaps(dupOrdinal);
});

test("compileTimeline matches Unicode and non-ASCII anchorWord tokens accurately", () => {
  const tl = compileTimeline({
    fps: 30,
    coverSec: 0.5,
    durationMs: 5000,
    words: [
      { word: "café,", startMs: 1000, endMs: 1400 },
      { word: "Москва", startMs: 2000, endMs: 2500 },
      { word: "北京。", startMs: 3000, endMs: 3500 },
    ],
    beats: [
      { ordinal: 0, anchorWord: "北京" },
      { ordinal: 1, anchorWord: "café" },
    ],
  });
  assert.equal(tl.events.length, 2);
  // Beat 0 anchors on "北京" at 3000ms -> 15 + 90 = 105
  assert.equal(tl.events[0].triggerFrame, 105);
  assertNoEventOverlaps(tl);
});

test("compileTimeline sanitizes NaN/negative word timestamps and resolves dense same-frame words with zero overlaps", () => {
  const nanTl = compileTimeline({
    fps: 30,
    coverSec: 0.5,
    words: [
      { word: "Broken", startMs: Number.NaN, endMs: Number.NaN },
      { word: "Negative", startMs: -250, endMs: 400 },
    ],
    beats: [{ ordinal: 0 }],
  });
  assert.equal(nanTl.captions.length, 1);
  assertNoCaptionOverlaps(nanTl);
  assertNoEventOverlaps(nanTl);

  const denseWords = [
    { word: "a", startMs: 100, endMs: 110 },
    { word: "b", startMs: 100, endMs: 110 },
    { word: "c", startMs: 100, endMs: 110 },
    { word: "d", startMs: 100, endMs: 110 },
    { word: "e", startMs: 4990, endMs: 5000 },
    { word: "f", startMs: 4990, endMs: 5000 },
    { word: "g", startMs: 4990, endMs: 5000 },
    { word: "h", startMs: 4990, endMs: 5000 },
    { word: "i", startMs: 4990, endMs: 5000 },
  ];
  const denseTl = compileTimeline({
    fps: 30,
    coverSec: 0.5,
    durationMs: 5000,
    words: denseWords,
    beats: [],
  });
  assert.equal(denseTl.captions.length, 3);
  assertNoCaptionOverlaps(denseTl);
});

test("compileTimeline guards NaN, Infinity, negative, and zero coverSec cleanly", () => {
  for (const invalidCoverSec of [Number.NaN, Number.POSITIVE_INFINITY, Number.NEGATIVE_INFINITY, -1]) {
    const tl = compileTimeline({
      fps: 30,
      coverSec: invalidCoverSec,
      durationMs: 2000,
      words: [
        { word: "Alpha", startMs: 0, endMs: 500 },
        { word: "Beta", startMs: 500, endMs: 1000 },
      ],
      beats: [{ ordinal: 0, startMs: 200, endMs: 1500 }],
    });
    assert.equal(tl.coverFrames, 15, `Expected default 15 coverFrames for coverSec=${String(invalidCoverSec)}`);
    assert.ok(Number.isFinite(tl.totalFrames) && tl.totalFrames === 75);
    assertNoCaptionOverlaps(tl);
    assertNoEventOverlaps(tl);
  }

  const zeroCoverTl = compileTimeline({
    fps: 30,
    coverSec: 0,
    durationMs: 2000,
    words: [
      { word: "Alpha", startMs: 0, endMs: 500 },
      { word: "Beta", startMs: 500, endMs: 1000 },
    ],
    beats: [{ ordinal: 0, startMs: 200, endMs: 1500 }],
  });
  assert.equal(zeroCoverTl.coverFrames, 0);
  assert.equal(zeroCoverTl.totalFrames, 60);
  assertNoCaptionOverlaps(zeroCoverTl);
  assertNoEventOverlaps(zeroCoverTl);
});
