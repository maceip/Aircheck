export interface AlignedWord {
  word: string;
  startMs: number;
  endMs: number;
}

export interface BeatSpec {
  id?: string;
  ordinal: number;
  spokenText?: string;
  anchorWord?: string;
  startMs?: number;
  endMs?: number;
  depthTextTag?: string;
  evidenceSource?: string;
  bRollUrl?: string;
  bRollMediaType?: "image" | "video";
  pipShrink?: boolean;
}

export type BreakingStyle = "breaking" | "developing" | "exclusive" | "alert_stat" | "clean";
export type BrollFraming = "pip" | "full" | "split";

export interface OverlayItemSpec {
  kind: "LOWER_THIRD" | "STAT_CALLOUT" | "QUOTE_BRIEF";
  breakingStyle?: BreakingStyle;
  headline: string;
  subline?: string;
  calloutStat?: string;
  durationSec?: number;
}

export interface PackagingSpec {
  chyronHeadline: string;
  chyronSubline: string;
  cornerBugText: string;
  showBrandBug: boolean;
  breakingStyle?: BreakingStyle;
  accentHex?: string;
  brollFraming?: BrollFraming;
  overlays?: OverlayItemSpec[];
  templateId?: "Newsroom916" | "Explainer169";
  /** Clip Studio toggles. Undefined keeps the legacy always-on behaviour for older callers. */
  showCaptions?: boolean;
  punchIn?: boolean;
  showLowerThird?: boolean;
}

export interface CaptionPhrase {
  startFrame: number;
  endFrame: number;
  words: Array<{ word: string; startFrame: number; endFrame: number }>;
}

export interface AnchoredEvent {
  beatOrdinal: number;
  triggerFrame: number;
  endFrame: number;
  spokenText?: string;
  keyword?: string;
  citation?: string;
  bRollUrl?: string;
  bRollMediaType?: "image" | "video";
  punchInScale: number;
  pipShrink: boolean;
}

export interface CompiledTimeline {
  fps: number;
  coverFrames: number;
  totalFrames: number;
  captions: CaptionPhrase[];
  events: AnchoredEvent[];
}

/**
 * Compiles word-level `align_{sha8}.json` timestamps + `"beat:word"` / explicit `[startMs, endMs]`
 * semantic anchors into frame-accurate captions, automatic 1.0x -> 1.14x punch-in cuts,
 * PiP presenter shrink during evidence cards, and a 0.5s first-frame cover card.
 * All frame intervals are half-open `[startFrame, endFrame)`.
 */
export function compileTimeline(params: {
  words: AlignedWord[];
  beats: BeatSpec[];
  fps?: number;
  coverSec?: number;
  durationMs?: number;
}): CompiledTimeline {
  const fps = typeof params.fps === "number" && Number.isFinite(params.fps) && params.fps > 0 ? params.fps : 30;
  const coverSec =
    typeof params.coverSec === "number" && Number.isFinite(params.coverSec) && params.coverSec >= 0
      ? params.coverSec
      : 0.5;
  const coverFrames = Math.max(0, Math.round(coverSec * fps));

  const sanitizedWords = params.words.map((w) => {
    const startMs =
      typeof w.startMs === "number" && Number.isFinite(w.startMs) && w.startMs >= 0 ? w.startMs : 0;
    const endMs =
      typeof w.endMs === "number" && Number.isFinite(w.endMs) && w.endMs >= startMs ? w.endMs : startMs;
    return { word: w.word, startMs, endMs };
  });

  const sortedWords = [...sanitizedWords].sort((a, b) => a.startMs - b.startMs);
  const lastWordEndMs = sortedWords.reduce((max, w) => Math.max(max, w.endMs), 0);
  const lastBeatMs = params.beats.reduce((max, b) => {
    let nextMax = max;
    if (typeof b.startMs === "number" && Number.isFinite(b.startMs) && b.startMs >= 0) {
      nextMax = Math.max(nextMax, b.startMs);
    }
    if (typeof b.endMs === "number" && Number.isFinite(b.endMs) && b.endMs >= 0) {
      nextMax = Math.max(nextMax, b.endMs);
    }
    return nextMax;
  }, 0);
  const explicitDurationMs =
    typeof params.durationMs === "number" && Number.isFinite(params.durationMs) && params.durationMs > 0
      ? params.durationMs
      : 0;

  const totalContentMs = Math.max(lastWordEndMs, lastBeatMs, explicitDurationMs);
  const effectiveMs = totalContentMs > 0 ? totalContentMs : 5000;
  const contentFrames = Math.max(1, Math.ceil((effectiveMs / 1000) * fps));
  const totalFrames = coverFrames + contentFrames;

  // 1. Group words into kinetic 4-word pop-caption phrases with strict non-overlapping half-open [startFrame, endFrame)
  const wordStarts = sortedWords.map((w) =>
    Math.min(
      Math.max(coverFrames, totalFrames - 1),
      coverFrames + Math.max(0, Math.floor((w.startMs / 1000) * fps)),
    ),
  );
  for (let i = 1; i < wordStarts.length; i++) {
    if (wordStarts[i] <= wordStarts[i - 1]) {
      wordStarts[i] = wordStarts[i - 1] + 1;
    }
  }
  if (wordStarts.length > 0 && wordStarts[wordStarts.length - 1] > totalFrames - 1) {
    wordStarts[wordStarts.length - 1] = totalFrames - 1;
    for (let i = wordStarts.length - 2; i >= 0; i--) {
      if (wordStarts[i] >= wordStarts[i + 1]) {
        wordStarts[i] = wordStarts[i + 1] - 1;
      }
    }
  }

  const validWords: Array<{ word: AlignedWord; startFrame: number }> = [];
  for (let i = 0; i < sortedWords.length; i++) {
    if (wordStarts[i] >= coverFrames && wordStarts[i] < totalFrames) {
      validWords.push({ word: sortedWords[i], startFrame: wordStarts[i] });
    }
  }

  const captions: CaptionPhrase[] = [];
  const phraseSize = 4;
  for (let i = 0; i < validWords.length; i += phraseSize) {
    const slice = validWords.slice(i, i + phraseSize);
    if (slice.length === 0) continue;

    const mapped = slice.map((item, idx) => {
      const globalIdx = i + idx;
      const startFrame = item.startFrame;
      const rawEnd = Math.max(
        coverFrames + Math.ceil((item.word.endMs / 1000) * fps),
        startFrame + 1,
      );
      const nextWordStart =
        globalIdx + 1 < validWords.length ? validWords[globalIdx + 1].startFrame : totalFrames;
      const cappedEnd = Math.min(rawEnd, nextWordStart);
      const endFrame = Math.min(totalFrames, Math.max(startFrame + 1, cappedEnd));
      return {
        word: item.word.word,
        startFrame,
        endFrame,
      };
    });

    captions.push({
      startFrame: mapped[0].startFrame,
      endFrame: mapped[mapped.length - 1].endFrame,
      words: mapped,
    });
  }

  // 2. Two-pass deterministic beat resolution: sort by ordinal, resolve triggerFrame, resolve collisions, clamp non-overlapping endFrame
  const sortedBeats = [...params.beats].sort((a, b) => {
    if (a.ordinal !== b.ordinal) return a.ordinal - b.ordinal;
    const aStart = typeof a.startMs === "number" && Number.isFinite(a.startMs) ? a.startMs : Number.POSITIVE_INFINITY;
    const bStart = typeof b.startMs === "number" && Number.isFinite(b.startMs) ? b.startMs : Number.POSITIVE_INFINITY;
    return aStart - bStart;
  });

  const normalize = (s: string) => {
    const nfkc = s.normalize("NFKC").toLowerCase();
    const stripped = nfkc.replace(/[^\p{L}\p{N}]/gu, "");
    return stripped || nfkc.trim();
  };
  let cursorIdx = 0;

  const pass1 = sortedBeats.map((beat, beatIdx) => {
    let rawTriggerFrame: number;

    if (typeof beat.startMs === "number" && Number.isFinite(beat.startMs) && beat.startMs >= 0) {
      rawTriggerFrame = coverFrames + Math.floor((beat.startMs / 1000) * fps);
      while (cursorIdx < sortedWords.length && sortedWords[cursorIdx].startMs < beat.startMs) {
        cursorIdx++;
      }
    } else {
      const anchorTarget = normalize(beat.anchorWord ?? "");
      let matchedWordIdx = -1;
      if (anchorTarget) {
        for (let idx = cursorIdx; idx < sortedWords.length; idx++) {
          if (normalize(sortedWords[idx].word) === anchorTarget) {
            matchedWordIdx = idx;
            cursorIdx = idx + 1;
            break;
          }
        }
      }

      if (matchedWordIdx >= 0) {
        rawTriggerFrame = coverFrames + Math.floor((sortedWords[matchedWordIdx].startMs / 1000) * fps);
      } else if (sortedWords.length > 0 && cursorIdx < sortedWords.length) {
        rawTriggerFrame = coverFrames + Math.floor((sortedWords[cursorIdx].startMs / 1000) * fps);
        cursorIdx += 1;
      } else {
        rawTriggerFrame =
          coverFrames + Math.floor((beatIdx / Math.max(1, sortedBeats.length)) * contentFrames);
      }
    }

    const triggerFrame = Math.min(
      Math.max(coverFrames, totalFrames - 1),
      Math.max(coverFrames, rawTriggerFrame),
    );

    return { beat, triggerFrame };
  });

  const beatTriggers = pass1.map((p) => p.triggerFrame);
  for (let i = 1; i < beatTriggers.length; i++) {
    if (beatTriggers[i] <= beatTriggers[i - 1]) {
      beatTriggers[i] = beatTriggers[i - 1] + 1;
    }
  }
  if (beatTriggers.length > 0 && beatTriggers[beatTriggers.length - 1] > totalFrames - 1) {
    beatTriggers[beatTriggers.length - 1] = totalFrames - 1;
    for (let i = beatTriggers.length - 2; i >= 0; i--) {
      if (beatTriggers[i] >= beatTriggers[i + 1]) {
        beatTriggers[i] = beatTriggers[i + 1] - 1;
      }
    }
  }

  const validBeats: Array<{ beat: BeatSpec; triggerFrame: number }> = [];
  for (let i = 0; i < pass1.length; i++) {
    if (beatTriggers[i] >= coverFrames && beatTriggers[i] < totalFrames) {
      validBeats.push({ beat: pass1[i].beat, triggerFrame: beatTriggers[i] });
    }
  }

  const events: AnchoredEvent[] = [];
  for (let i = 0; i < validBeats.length; i++) {
    const { beat, triggerFrame } = validBeats[i];
    const nextTriggerFrame = i + 1 < validBeats.length ? validBeats[i + 1].triggerFrame : totalFrames;
    const hasExplicitEnd =
      typeof beat.endMs === "number" &&
      Number.isFinite(beat.endMs) &&
      beat.endMs > (typeof beat.startMs === "number" && Number.isFinite(beat.startMs) ? beat.startMs : -1);

    const rawEndFrame = hasExplicitEnd
      ? coverFrames + Math.ceil(((beat.endMs as number) / 1000) * fps)
      : nextTriggerFrame;

    let endFrame = Math.min(totalFrames, Math.max(triggerFrame + 1, rawEndFrame));
    if (i + 1 < validBeats.length) {
      endFrame = Math.min(endFrame, nextTriggerFrame);
    }
    if (endFrame <= triggerFrame) continue;

    const bRollUrl = beat.bRollUrl?.trim() || undefined;
    const citation = beat.evidenceSource?.trim() || undefined;
    const keyword = beat.depthTextTag?.trim() || undefined;
    const pipShrink =
      typeof beat.pipShrink === "boolean"
        ? beat.pipShrink
        : Boolean(citation || bRollUrl);

    events.push({
      beatOrdinal: beat.ordinal,
      triggerFrame,
      endFrame,
      spokenText: beat.spokenText?.trim() || undefined,
      keyword,
      citation,
      bRollUrl,
      bRollMediaType: beat.bRollMediaType,
      punchInScale: beat.ordinal % 2 === 1 ? 1.14 : 1.0,
      pipShrink,
    });
  }

  return { fps, coverFrames, totalFrames, captions, events };
}
