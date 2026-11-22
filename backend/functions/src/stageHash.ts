import * as crypto from "node:crypto";

export interface BeatInput {
  id: string;
  ordinal: number;
  spokenText: string;
  anchorWord?: string;
  depthTextTag?: string;
  evidenceSource?: string;
  phoneticOverride?: string;
  bRollSha?: string | null;
}

export interface ProviderRouting {
  voiceProviderId: string;
  alignProviderId: string;
  headProviderId: string;
  packageProviderId: string;
  lanGatewayUrl?: string;
  useLanVoice?: boolean;
  useLanAlign?: boolean;
  useLanHead?: boolean;
  useLanPackage?: boolean;
}

export interface PackagingConfig {
  chyronHeadline: string;
  chyronSubline: string;
  cornerBugText: string;
  showBrandBug: boolean;
  templateId?: "Newsroom916" | "Explainer169";
}

export interface StageHashes {
  voiceSha8: string;
  alignSha8: string;
  lipsyncSha8: string;
  finalSha8: string;
}

export function sha256Hex(payload: string | Buffer): string {
  return crypto.createHash("sha256").update(payload).digest("hex");
}

/**
 * Computes deterministic, content-addressed stage hashes with `providerId` folded into every stage.
 * - Editing lower-third chyron or template invalidates ONLY `finalSha8` ($0.00 checkpoint resume).
 * - Editing 1 spoken word invalidates `voiceSha8` -> `alignSha8` -> `lipsyncSha8` -> `finalSha8`.
 * - No file is ever named `main.mp4` or overwritten in place.
 */
export function computeStageHashes(params: {
  fullSpokenText: string;
  voiceSource: string;
  voiceId: string;
  micVoiceSha?: string | null;
  photoSha: string;
  headModelId: string;
  stage4Mode: string;
  beats: BeatInput[];
  packaging: PackagingConfig;
  providers: ProviderRouting;
}): StageHashes {
  const voiceKey =
    params.voiceSource === "MIC_VOICEOVER" && params.micVoiceSha
      ? `mic|${params.micVoiceSha}`
      : `tts|${params.providers.voiceProviderId}|${params.providers.useLanVoice ? "lan" : "cloud"}|${params.voiceId}|${params.fullSpokenText}`;
  const voiceSha8 = sha256Hex(voiceKey).slice(0, 8);

  const alignKey = `align|${params.providers.alignProviderId}|${params.providers.useLanAlign ? "lan" : "cloud"}|${voiceSha8}`;
  const alignSha8 = sha256Hex(alignKey).slice(0, 8);

  const lipsyncKey = `head|${params.stage4Mode}|${params.providers.headProviderId}|${params.providers.useLanHead ? "lan" : "cloud"}|${params.headModelId}|${params.photoSha}|${voiceSha8}`;
  const lipsyncSha8 = sha256Hex(lipsyncKey).slice(0, 8);

  const beatsVisualSignature = params.beats
    .map((b) => `${b.ordinal}:${b.anchorWord ?? ""}:${b.depthTextTag ?? ""}:${b.evidenceSource ?? ""}:${b.bRollSha ?? ""}`)
    .join(";");
  const finalKey = [
    "pkg",
    params.providers.packageProviderId,
    params.providers.useLanPackage ? "lan" : "cloud",
    lipsyncSha8,
    voiceSha8,
    alignSha8,
    params.packaging.templateId ?? "Newsroom916",
    params.packaging.chyronHeadline,
    params.packaging.chyronSubline,
    params.packaging.cornerBugText,
    String(params.packaging.showBrandBug),
    beatsVisualSignature,
  ].join("|");
  const finalSha8 = sha256Hex(finalKey).slice(0, 8);

  return { voiceSha8, alignSha8, lipsyncSha8, finalSha8 };
}

/**
 * Extracts 64 normalized RMS amplitude peaks directly from a 16-bit RIFF PCM WAV buffer
 * so the Android client renders waveforms in 0.1 ms from Room with zero audio file I/O.
 */
export function extractWavPeaks64(wavBuffer: Buffer, bucketCount = 64): number[] {
  if (wavBuffer.length < 44) return [];
  if (wavBuffer.toString("ascii", 0, 4) !== "RIFF" || wavBuffer.toString("ascii", 8, 12) !== "WAVE") {
    return [];
  }

  let offset = 12;
  let channels = 1;
  let bitsPerSample = 16;
  let dataOffset = 44;
  let dataSize = 0;

  while (offset + 8 <= wavBuffer.length) {
    const chunkId = wavBuffer.toString("ascii", offset, offset + 4);
    const chunkSize = wavBuffer.readUInt32LE(offset + 4);
    offset += 8;
    if (chunkId === "fmt ") {
      channels = Math.max(1, wavBuffer.readUInt16LE(offset + 2));
      bitsPerSample = Math.max(8, wavBuffer.readUInt16LE(offset + 14));
    } else if (chunkId === "data") {
      dataOffset = offset;
      dataSize = Math.min(chunkSize, wavBuffer.length - offset);
      break;
    }
    offset += chunkSize;
  }

  const bytesPerSample = Math.max(1, bitsPerSample / 8);
  const stepBytes = bytesPerSample * channels;
  const totalSamples = Math.floor(dataSize / stepBytes);
  if (totalSamples <= 0) return [];

  const samplesPerBucket = Math.max(1, Math.floor(totalSamples / bucketCount));
  const rawPeaks: number[] = [];
  let maxPeak = 0.001;

  for (let b = 0; b < bucketCount; b++) {
    const startSample = b * samplesPerBucket;
    const endSample = Math.min(totalSamples, startSample + samplesPerBucket);
    const stride = Math.max(1, Math.floor((endSample - startSample) / 64));
    let sumSq = 0;
    let count = 0;
    for (let s = startSample; s < endSample; s += stride) {
      const pos = dataOffset + s * stepBytes;
      if (pos + 1 < wavBuffer.length) {
        const sample = wavBuffer.readInt16LE(pos) / 32768;
        sumSq += sample * sample;
        count++;
      }
    }
    const rms = count > 0 ? Math.sqrt(sumSq / count) : 0;
    rawPeaks.push(rms);
    if (rms > maxPeak) maxPeak = rms;
  }

  return rawPeaks.map((p) => Number(Math.min(1, Math.max(0.06, p / maxPeak)).toFixed(4)));
}

/**
 * Automatic Stage 3 pronunciation QC gate: diffs normalized script tokens against
 * Whisper-aligned words so misread acronyms are caught before paying for Stage 4 GPU lip-sync.
 */
export function computePronunciationDiff(
  scriptText: string,
  alignedWords: Array<{ word: string; startMs: number; endMs: number }>
): { wordErrorRate: number; mismatchedWords: string[] } {
  const normalize = (s: string) =>
    s
      .toLowerCase()
      .replace(/[^a-z0-9\s]/g, "")
      .split(/\s+/)
      .filter(Boolean);

  const expected = normalize(scriptText);
  const heardSet = new Set(alignedWords.flatMap((w) => normalize(w.word)));
  if (expected.length === 0) return { wordErrorRate: 0, mismatchedWords: [] };

  const missing = expected.filter((token) => !heardSet.has(token));
  const uniqueMissing = Array.from(new Set(missing));
  return {
    wordErrorRate: Number((missing.length / expected.length).toFixed(3)),
    mismatchedWords: uniqueMissing.slice(0, 12),
  };
}
