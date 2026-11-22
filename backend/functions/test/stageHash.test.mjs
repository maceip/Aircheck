import test from "node:test";
import assert from "node:assert/strict";
import { computeStageHashes, computePronunciationDiff, extractWavPeaks64 } from "../lib/stageHash.js";

const baseInput = {
  fullSpokenText: "Cloud GPU rendering just crossed under a dollar.",
  voiceSource: "ENROLLED_VOICE_CLONE",
  voiceId: "ryan_voice_v1",
  photoSha: "a".repeat(64),
  headModelId: "ryan_head_v1",
  stage4Mode: "photo_head_lora",
  beats: [{ id: "b1", ordinal: 0, spokenText: "Cloud GPU rendering just crossed under a dollar.", anchorWord: "dollar" }],
  packaging: {
    chyronHeadline: "Cloud GPU Shift",
    chyronSubline: "Aircheck Desk",
    cornerBugText: "AIRCHECK",
    showBrandBug: true,
    templateId: "Newsroom916",
  },
  providers: {
    voiceProviderId: "elevenlabs_pvc",
    alignProviderId: "groq_whisper_v3_turbo",
    headProviderId: "wan22_s2v_lora",
    packageProviderId: "cloud_run_remotion",
  },
};

test("editing lower-third chyron invalidates ONLY Stage 5 finalSha8 ($0.00 checkpoint resume)", () => {
  const h1 = computeStageHashes(baseInput);
  const h2 = computeStageHashes({
    ...baseInput,
    packaging: { ...baseInput.packaging, chyronHeadline: "Updated Lower Third" },
  });

  assert.equal(h1.voiceSha8, h2.voiceSha8);
  assert.equal(h1.alignSha8, h2.alignSha8);
  assert.equal(h1.lipsyncSha8, h2.lipsyncSha8);
  assert.notEqual(h1.finalSha8, h2.finalSha8);
});

test("editing one spoken word invalidates Stages 2 through 5 deterministically", () => {
  const h1 = computeStageHashes(baseInput);
  const h2 = computeStageHashes({
    ...baseInput,
    fullSpokenText: "Cloud GPU rendering just crossed under fifty cents.",
  });

  assert.notEqual(h1.voiceSha8, h2.voiceSha8);
  assert.notEqual(h1.alignSha8, h2.alignSha8);
  assert.notEqual(h1.lipsyncSha8, h2.lipsyncSha8);
  assert.notEqual(h1.finalSha8, h2.finalSha8);
});

test("switching providerId invalidates that stage and downstream stages", () => {
  const h1 = computeStageHashes(baseInput);
  const h2 = computeStageHashes({
    ...baseInput,
    providers: { ...baseInput.providers, headProviderId: "infinitetalk_lora" },
  });
  assert.equal(h1.voiceSha8, h2.voiceSha8);
  assert.equal(h1.alignSha8, h2.alignSha8);
  assert.notEqual(h1.lipsyncSha8, h2.lipsyncSha8);
  assert.notEqual(h1.finalSha8, h2.finalSha8);
});

test("Stage 3 pronunciation QC catches misread acronyms before Stage 4 GPU spend", () => {
  const qc = computePronunciationDiff("NVIDIA H100 latency fell 40 percent", [
    { word: "in", startMs: 0, endMs: 120 },
    { word: "video", startMs: 120, endMs: 300 },
    { word: "h100", startMs: 300, endMs: 550 },
    { word: "latency", startMs: 550, endMs: 800 },
    { word: "fell", startMs: 800, endMs: 950 },
    { word: "40", startMs: 950, endMs: 1100 },
    { word: "percent", startMs: 1100, endMs: 1400 },
  ]);
  assert.ok(qc.mismatchedWords.includes("nvidia"));
  assert.ok(qc.wordErrorRate > 0);
});

test("extractWavPeaks64 decodes 64 normalized peaks from a 16-bit PCM RIFF WAV", () => {
  const sr = 16000;
  const pcm = Buffer.alloc(sr * 2);
  for (let i = 0; i < sr; i++) {
    pcm.writeInt16LE(Math.round(Math.sin((2 * Math.PI * 220 * i) / sr) * 20000), i * 2);
  }
  const header = Buffer.alloc(44);
  header.write("RIFF", 0, "ascii");
  header.writeUInt32LE(36 + pcm.length, 4);
  header.write("WAVE", 8, "ascii");
  header.write("fmt ", 12, "ascii");
  header.writeUInt32LE(16, 16);
  header.writeUInt16LE(1, 20);
  header.writeUInt16LE(1, 22);
  header.writeUInt32LE(sr, 24);
  header.writeUInt32LE(sr * 2, 28);
  header.writeUInt16LE(2, 32);
  header.writeUInt16LE(16, 34);
  header.write("data", 36, "ascii");
  header.writeUInt32LE(pcm.length, 40);

  const peaks = extractWavPeaks64(Buffer.concat([header, pcm]), 64);
  assert.equal(peaks.length, 64);
  assert.ok(Math.max(...peaks) > 0.9);
});
