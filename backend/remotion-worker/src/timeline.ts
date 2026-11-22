export interface AlignedWord {
  word: string;
  startMs: number;
  endMs: number;
}

export interface BeatSpec {
  id: string;
  ordinal: number;
  spokenText: string;
  anchorWord?: string;
  depthTextTag?: string;
  evidenceSource?: string;
  bRollUrl?: string;
}

export interface PackagingSpec {
  chyronHeadline: string;
  chyronSubline: string;
  cornerBugText: string;
  showBrandBug: boolean;
  templateId?: "Newsroom916" | "Explainer169";
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
  keyword?: string;
  citation?: string;
  bRollUrl?: string;
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
 * Compiles word-level `align_{sha8}.json` timestamps + `"beat:word"` semantic anchors
 * into frame-accurate captions, automatic 1.0x -> 1.14x punch-in cuts, PiP presenter shrink
 * during evidence cards, and a 0.5s first-frame cover frame.
 */
export function compileTimeline(params: {
  words: AlignedWord[];
  beats: BeatSpec[];
  fps?: number;
  coverSec?: number;
}): CompiledTimeline {
  const fps = params.fps ?? 30;
  const coverFrames = Math.round((params.coverSec ?? 0.5) * fps);
  const lastWordEndMs = params.words.at(-1)?.endMs ?? 5000;
  const contentFrames = Math.max(fps * 3, Math.ceil((lastWordEndMs / 1000) * fps));
  const totalFrames = coverFrames + contentFrames;

  // 1. Group words into kinetic 3-4 word pop-caption phrases
  const captions: CaptionPhrase[] = [];
  const phraseSize = 4;
  for (let i = 0; i < params.words.length; i += phraseSize) {
    const slice = params.words.slice(i, i + phraseSize);
    if (slice.length === 0) continue;
    const mapped = slice.map((w) => ({
      word: w.word,
      startFrame: coverFrames + Math.floor((w.startMs / 1000) * fps),
      endFrame: coverFrames + Math.max(Math.ceil((w.endMs / 1000) * fps), Math.floor((w.startMs / 1000) * fps) + 2),
    }));
    captions.push({
      startFrame: mapped[0].startFrame,
      endFrame: mapped[mapped.length - 1].endFrame,
      words: mapped,
    });
  }

  // 2. Resolve word-anchored semantic events ("beat:word") & automatic punch-in cuts
  const events: AnchoredEvent[] = [];
  let cursorIdx = 0;
  const normalize = (s: string) => s.toLowerCase().replace(/[^a-z0-9]/g, "");

  for (const beat of params.beats) {
    const anchorTarget = normalize(beat.anchorWord ?? "");
    let matchedWordIdx = -1;
    if (anchorTarget) {
      for (let idx = cursorIdx; idx < params.words.length; idx++) {
        if (normalize(params.words[idx].word) === anchorTarget) {
          matchedWordIdx = idx;
          cursorIdx = idx + 1;
          break;
        }
      }
    }

    const triggerWord =
      matchedWordIdx >= 0
        ? params.words[matchedWordIdx]
        : params.words[Math.min(cursorIdx, Math.max(0, params.words.length - 1))];

    const triggerFrame = triggerWord
      ? coverFrames + Math.floor((triggerWord.startMs / 1000) * fps)
      : coverFrames + Math.floor((beat.ordinal / Math.max(1, params.beats.length)) * contentFrames);

    const spanFrames = Math.round(2.5 * fps);
    const hasEvidence = Boolean(beat.evidenceSource?.trim() || beat.bRollUrl);

    events.push({
      beatOrdinal: beat.ordinal,
      triggerFrame,
      endFrame: Math.min(totalFrames, triggerFrame + spanFrames),
      keyword: beat.depthTextTag?.trim() || undefined,
      citation: beat.evidenceSource?.trim() || undefined,
      bRollUrl: beat.bRollUrl,
      punchInScale: beat.ordinal % 2 === 1 ? 1.14 : 1.0,
      pipShrink: hasEvidence,
    });
  }

  return { fps, coverFrames, totalFrames, captions, events };
}
