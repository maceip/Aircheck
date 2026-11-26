import React from "react";
import {
  AbsoluteFill,
  Audio,
  Img,
  OffthreadVideo,
  Sequence,
  interpolate,
  spring,
  useCurrentFrame,
  useVideoConfig,
} from "remotion";
import { ensureBroadcastFonts } from "../fonts";
import { BreakingStyle, CompiledTimeline, OverlayItemSpec, PackagingSpec } from "../timeline";

export type AircheckTemplateProps = {
  lipsyncUrl: string;
  voiceUrl: string;
  timeline: CompiledTimeline;
  packaging: PackagingSpec;
};

const VIDEO_EXTENSION_RE = /\.(mp4|mov|webm|m4v|mkv)$/i;

export function isVideoMediaUrl(url: string, hint?: "image" | "video"): boolean {
  if (hint === "video") return true;
  if (hint === "image") return false;
  try {
    const pathname = new URL(url, "http://127.0.0.1").pathname;
    return VIDEO_EXTENSION_RE.test(pathname);
  } catch {
    const clean = url.split(/[?#]/)[0] ?? "";
    return VIDEO_EXTENSION_RE.test(clean);
  }
}

interface StyleTheme {
  bannerLabel: string;
  kickerBg: string | null;
  kickerFg: string;
  accent: string;
}

const HEX_COLOR_RE = /^#[0-9A-Fa-f]{6}$/;

function isLightHex(hex: string): boolean {
  const r = parseInt(hex.slice(1, 3), 16);
  const g = parseInt(hex.slice(3, 5), 16);
  const b = parseInt(hex.slice(5, 7), 16);
  return 0.2126 * r + 0.7152 * g + 0.0722 * b >= 155;
}

function getStyleTheme(style?: BreakingStyle, accentHex?: string): StyleTheme {
  const customAccent = accentHex && HEX_COLOR_RE.test(accentHex) ? accentHex.toUpperCase() : undefined;
  const base = ((): StyleTheme => {
    switch (style) {
      case "developing":
        return {
          bannerLabel: "DEVELOPING",
          kickerBg: "#FFB800",
          kickerFg: "#000000",
          accent: "#FFB800",
        };
      case "exclusive":
        return {
          bannerLabel: "EXCLUSIVE",
          kickerBg: "#FFFFFF",
          kickerFg: "#000000",
          accent: "#FFFFFF",
        };
      case "alert_stat":
        return {
          bannerLabel: "DATA ALERT",
          kickerBg: "#D71921",
          kickerFg: "#FFFFFF",
          accent: "#FFB800",
        };
      case "clean":
        return {
          bannerLabel: "",
          kickerBg: null,
          kickerFg: "#EBEBEF",
          accent: "#8E8E96",
        };
      case "breaking":
      default:
        return {
          bannerLabel: "BREAKING NEWS",
          kickerBg: "#D71921",
          kickerFg: "#FFFFFF",
          accent: "#D71921",
        };
    }
  })();
  if (!customAccent || customAccent === "#D71921") {
    return base;
  }
  return {
    ...base,
    accent: customAccent,
    kickerBg: base.kickerBg ? customAccent : null,
    kickerFg: base.kickerBg ? (isLightHex(customAccent) ? "#0A0B0E" : "#FFFFFF") : base.kickerFg,
  };
}

function pickActiveOverlay(
  items: OverlayItemSpec[],
  contentFrame: number,
  contentDurationFrames: number,
): OverlayItemSpec | undefined {
  if (items.length === 0) return undefined;
  if (items.length === 1) return items[0];
  const slot = Math.max(1, Math.floor(contentDurationFrames / items.length));
  const idx = Math.min(items.length - 1, Math.max(0, Math.floor(contentFrame / slot)));
  return items[idx];
}

/**
 * 100% Data-Driven Remotion 4 Newsroom 9:16 & Explainer 16:9 Template.
 * Reads ONLY immutable `inputProps` URLs (zero mutable `public/main.mp4` singletons).
 */
export const AircheckBroadcastTemplate: React.FC<AircheckTemplateProps> = ({
  lipsyncUrl,
  voiceUrl,
  timeline,
  packaging,
}) => {
  ensureBroadcastFonts();

  const frame = useCurrentFrame();
  const { fps, width, height } = useVideoConfig();
  const isLandscape = width > height;

  const isCover = frame < timeline.coverFrames;
  const activeEvent = timeline.events.find((e) => frame >= e.triggerFrame && frame < e.endFrame);
  const activePhrase = timeline.captions.find((c) => frame >= c.startFrame && frame < c.endFrame);

  const eventSpan = activeEvent ? Math.max(1, activeEvent.endFrame - activeEvent.triggerFrame) : 1;
  const transFrames = Math.max(1, Math.min(10, Math.floor(eventSpan / 3)));

  const enterProgress = activeEvent
    ? spring({
        frame: frame - activeEvent.triggerFrame,
        fps,
        durationInFrames: transFrames,
        config: { damping: 18, stiffness: 170 },
      })
    : 0;

  const exitProgress = activeEvent
    ? interpolate(
        frame,
        [Math.max(activeEvent.triggerFrame, activeEvent.endFrame - transFrames), activeEvent.endFrame],
        [1, 0],
        { extrapolateLeft: "clamp", extrapolateRight: "clamp" },
      )
    : 0;

  const eventProgress = activeEvent
    ? eventSpan < 3
      ? 1
      : Math.max(0, Math.min(1, enterProgress * exitProgress))
    : 0;

  const pipProgress = activeEvent?.pipShrink ? eventProgress : 0;
  const punchProgress = activeEvent && !activeEvent.pipShrink ? eventProgress : 0;
  const punchScale = 1 + ((activeEvent?.punchInScale ?? 1) - 1) * punchProgress;
  const brollFraming = packaging.brollFraming ?? "pip";
  const isFullCutaway = brollFraming === "full";
  const isSplitCutaway = brollFraming === "split";

  // Responsive non-overlapping PiP / Split target geometry
  const pipTarget = isSplitCutaway
    ? isLandscape
      ? { left: Math.round(width * 0.5), top: 0, width: width - Math.round(width * 0.5), height, radius: 0 }
      : { left: 0, top: Math.round(height * 0.5), width, height: height - Math.round(height * 0.5), radius: 0 }
    : isLandscape
      ? { left: 1264, top: 96, width: 600, height: 480, radius: 20 }
      : { left: width - 44 - 320, top: 56, width: 320, height: 420, radius: 18 };

  const presenterLeft = isFullCutaway
    ? 0
    : interpolate(pipProgress, [0, 1], [0, pipTarget.left], {
        extrapolateLeft: "clamp",
        extrapolateRight: "clamp",
      });
  const presenterTop = isFullCutaway
    ? 0
    : interpolate(pipProgress, [0, 1], [0, pipTarget.top], {
        extrapolateLeft: "clamp",
        extrapolateRight: "clamp",
      });
  const presenterWidth = isFullCutaway
    ? width
    : interpolate(pipProgress, [0, 1], [width, pipTarget.width], {
        extrapolateLeft: "clamp",
        extrapolateRight: "clamp",
      });
  const presenterHeight = isFullCutaway
    ? height
    : interpolate(pipProgress, [0, 1], [height, pipTarget.height], {
        extrapolateLeft: "clamp",
        extrapolateRight: "clamp",
      });
  const presenterRadius = isFullCutaway
    ? 0
    : interpolate(pipProgress, [0, 1], [0, pipTarget.radius], {
        extrapolateLeft: "clamp",
        extrapolateRight: "clamp",
      });
  const presenterOpacity = isFullCutaway ? Math.max(0, 1 - pipProgress) : 1;

  const contentDuration = Math.max(1, timeline.totalFrames - timeline.coverFrames);
  const contentFrame = Math.max(0, frame - timeline.coverFrames);

  const attachedOverlays = packaging.overlays ?? [];
  const lowerOverlays = attachedOverlays.filter((o) => o.kind !== "QUOTE_BRIEF");
  const quoteOverlays = attachedOverlays.filter((o) => o.kind === "QUOTE_BRIEF");
  const activeLower = pickActiveOverlay(lowerOverlays, contentFrame, contentDuration);
  const activeQuote = pickActiveOverlay(quoteOverlays, contentFrame, contentDuration);

  const effectiveStyle: BreakingStyle =
    activeLower?.breakingStyle ?? packaging.breakingStyle ?? "breaking";
  const theme = getStyleTheme(effectiveStyle, packaging.accentHex);
  const quoteTheme = getStyleTheme(activeQuote?.breakingStyle ?? effectiveStyle, packaging.accentHex);

  const activeHeadline = (activeLower?.headline?.trim() || packaging.chyronHeadline || "Aircheck").toUpperCase();
  const activeSubline = activeLower?.subline?.trim() ?? packaging.chyronSubline ?? "";
  const activeStat = activeLower?.calloutStat?.trim() ?? "";

  const cornerLabel = (packaging.cornerBugText ?? "").trim();
  const showChannelChip = Boolean(cornerLabel && cornerLabel.toUpperCase() !== "LIVE");

  const brollBox = isFullCutaway
    ? {
        left: isLandscape ? 56 : 40,
        top: isLandscape ? 92 : 140,
        width: isLandscape ? width - 112 : width - 80,
        mediaHeight: isLandscape ? height - 300 : height - 470,
      }
    : isSplitCutaway
      ? {
          left: isLandscape ? 0 : 0,
          top: 0,
          width: isLandscape ? Math.round(width * 0.5) : width,
          mediaHeight: isLandscape ? height : Math.round(height * 0.5),
        }
      : {
          // PiP cutaway: b-roll fills the whole frame behind the corner presenter (no empty band).
          left: 0,
          top: 0,
          width,
          mediaHeight: height,
        };

  return (
    <AbsoluteFill style={{ backgroundColor: "#000000", fontFamily: "'Space Grotesk', sans-serif" }}>
      {/* Broadcast Stage Backdrop behind PiP when shrinking */}
      {activeEvent?.pipShrink && (
        <AbsoluteFill
          style={{
            backgroundColor: "#0A0C10",
            opacity: pipProgress,
            zIndex: 1,
          }}
        />
      )}

      {/* B-Roll / Evidence Stage (honors pip / full / split framing) */}
      {activeEvent?.pipShrink && (
        <div
          style={{
            position: "absolute",
            left: brollBox.left,
            top: brollBox.top,
            width: brollBox.width,
            opacity: pipProgress,
            transform: !isFullCutaway
              ? undefined
              : `translateY(${interpolate(pipProgress, [0, 1], [24, 0], {
                  extrapolateLeft: "clamp",
                  extrapolateRight: "clamp",
                }).toFixed(2)}px)`,
            zIndex: 5,
          }}
        >
          {activeEvent.bRollUrl ? (
            <div
              style={{
                width: "100%",
                height: brollBox.mediaHeight,
                borderRadius: isFullCutaway ? 16 : 0,
                overflow: "hidden",
                border: isFullCutaway ? `2px solid ${theme.accent}` : "none",
                borderBottom: isSplitCutaway && !isLandscape ? `3px solid ${theme.accent}` : undefined,
                borderRight: isSplitCutaway && isLandscape ? `3px solid ${theme.accent}` : undefined,
                backgroundColor: "#12151C",
              }}
            >
              {isVideoMediaUrl(activeEvent.bRollUrl, activeEvent.bRollMediaType) ? (
                <Sequence
                  key={`broll-seq-${activeEvent.beatOrdinal}-${activeEvent.triggerFrame}`}
                  from={activeEvent.triggerFrame}
                  durationInFrames={eventSpan}
                  layout="none"
                >
                  <OffthreadVideo
                    src={activeEvent.bRollUrl}
                    muted
                    style={{ width: "100%", height: "100%", objectFit: "cover" }}
                  />
                </Sequence>
              ) : (
                <Img
                  src={activeEvent.bRollUrl}
                  style={{ width: "100%", height: "100%", objectFit: "cover" }}
                />
              )}
            </div>
          ) : activeEvent.spokenText ? (
            <div
              style={{
                width: "100%",
                boxSizing: "border-box",
                padding: isLandscape ? "36px 44px" : "44px 42px",
                borderRadius: 16,
                backgroundColor: "#10131B",
                border: "2px solid #2C2E38",
                borderLeft: `8px solid ${theme.accent}`,
              }}
            >
              <div
                style={{
                  color: theme.accent,
                  fontSize: isLandscape ? 20 : 24,
                  fontWeight: 700,
                  letterSpacing: 1.2,
                  fontFamily: "'JetBrains Mono', monospace",
                  marginBottom: 18,
                }}
              >
                B-ROLL CUTAWAY
              </div>
              <div
                style={{
                  color: "#F4F6FA",
                  fontSize: isLandscape ? 36 : 42,
                  fontWeight: 700,
                  lineHeight: 1.25,
                }}
              >
                {activeEvent.spokenText}
              </div>
            </div>
          ) : null}

          {/* Landscape 16:9 Citation Pill under B-Roll */}
          {isLandscape && activeEvent.citation && (
            <div
              style={{
                marginTop: 14,
                display: "inline-flex",
                alignItems: "center",
                gap: 12,
                padding: "8px 18px",
                borderRadius: 10,
                backgroundColor: "#12151C",
                border: "2px solid #FFB800",
                color: "#FFB800",
                fontSize: 22,
                fontWeight: 700,
                fontFamily: "'JetBrains Mono', monospace",
                fontVariantNumeric: "tabular-nums",
                maxWidth: "100%",
                boxSizing: "border-box",
              }}
            >
              <span>SOURCE</span>
              <span style={{ color: "#F4F6FA", fontFamily: "'Space Grotesk', sans-serif" }}>
                {activeEvent.citation}
              </span>
            </div>
          )}

          {/* Portrait 9:16 Citation + depthTextTag flex-wrap container (<= 2 chips) */}
          {!isLandscape && (activeEvent.citation || activeEvent.keyword) && (
            <div
              style={{
                marginTop: 18,
                display: "flex",
                flexWrap: "wrap",
                alignItems: "center",
                gap: 14,
              }}
            >
              {activeEvent.citation && (
                <div
                  style={{
                    display: "inline-flex",
                    alignItems: "center",
                    gap: 12,
                    padding: "12px 22px",
                    borderRadius: 10,
                    backgroundColor: "#12151C",
                    border: "2px solid #FFB800",
                    color: "#FFB800",
                    fontSize: 28,
                    fontWeight: 700,
                    fontFamily: "'JetBrains Mono', monospace",
                    fontVariantNumeric: "tabular-nums",
                    maxWidth: "100%",
                    boxSizing: "border-box",
                  }}
                >
                  <span>SOURCE</span>
                  <span style={{ color: "#F4F6FA", fontFamily: "'Space Grotesk', sans-serif" }}>
                    {activeEvent.citation}
                  </span>
                </div>
              )}
              {activeEvent.keyword && (
                <div
                  style={{
                    display: "inline-block",
                    padding: "12px 22px",
                    borderRadius: 10,
                    backgroundColor: "#12151C",
                    border: `2px solid ${theme.accent}`,
                    color: theme.accent,
                    fontSize: 30,
                    fontWeight: 800,
                    fontFamily: "'JetBrains Mono', monospace",
                    fontVariantNumeric: "tabular-nums",
                    boxSizing: "border-box",
                  }}
                >
                  {activeEvent.keyword}
                </div>
              )}
            </div>
          )}
        </div>
      )}

      {/* Landscape 16:9 depthTextTag docked below PiP in right column */}
      {isLandscape && activeEvent?.pipShrink && activeEvent.keyword && (
        <div
          style={{
            position: "absolute",
            left: 1264,
            top: 598,
            width: 600,
            boxSizing: "border-box",
            padding: "14px 22px",
            borderRadius: 12,
            backgroundColor: "#12151C",
            border: `2px solid ${theme.accent}`,
            color: theme.accent,
            fontSize: 32,
            fontWeight: 800,
            fontFamily: "'JetBrains Mono', monospace",
            fontVariantNumeric: "tabular-nums",
            opacity: pipProgress,
            zIndex: 12,
          }}
        >
          {activeEvent.keyword}
        </div>
      )}

      {/* Full-Screen / Punch-in depthTextTag callout when PiP is NOT active */}
      {!isCover && activeEvent && !activeEvent.pipShrink && activeEvent.keyword && (
        <div
          style={{
            position: "absolute",
            top: isLandscape ? 32 : 56,
            right: isLandscape ? 56 : 44,
            padding: "12px 20px",
            borderRadius: 10,
            backgroundColor: "rgba(10,12,16,0.92)",
            border: `2px solid ${theme.accent}`,
            color: theme.accent,
            fontSize: isLandscape ? 30 : 34,
            fontWeight: 800,
            fontFamily: "'JetBrains Mono', monospace",
            fontVariantNumeric: "tabular-nums",
            opacity: eventProgress,
            zIndex: 28,
          }}
        >
          {activeEvent.keyword}
        </div>
      )}

      {/* Frame-Locked Presenter Video + Cloned Voice Track inside <Sequence from={coverFrames}> */}
      <Sequence from={timeline.coverFrames} durationInFrames={contentDuration} layout="none">
        <div
          style={{
            position: "absolute",
            left: presenterLeft,
            top: presenterTop,
            width: presenterWidth,
            height: presenterHeight,
            opacity: presenterOpacity,
            transform: activeEvent?.pipShrink ? undefined : `scale(${punchScale.toFixed(4)})`,
            transformOrigin: "center center",
            borderRadius: presenterRadius,
            overflow: "hidden",
            border: pipProgress > 0.05 && !isFullCutaway && !isSplitCutaway ? `3px solid ${theme.accent}` : "none",
            boxShadow: pipProgress > 0.05 && !isFullCutaway && !isSplitCutaway ? "0 18px 42px rgba(0, 0, 0, 0.55)" : "none",
            zIndex: activeEvent?.pipShrink ? (isFullCutaway ? 2 : 10) : 2,
          }}
        >
          {lipsyncUrl ? (
            <OffthreadVideo
              src={lipsyncUrl}
              muted={Boolean(voiceUrl)}
              style={{ width: "100%", height: "100%", objectFit: "cover" }}
            />
          ) : null}
        </div>
        {voiceUrl ? <Audio src={voiceUrl} /> : null}
      </Sequence>

      {/* Upper-Left QUOTE_BRIEF Broadcast Card (non-overlapping with LIVE bug, PiP, and Lower-Third) */}
      {!isCover && activeQuote && (
        <div
          style={{
            position: "absolute",
            top: isLandscape ? 98 : 132,
            left: isLandscape ? 56 : 44,
            width: isLandscape ? 560 : 580,
            maxWidth: activeEvent?.pipShrink && !isLandscape && !isFullCutaway && !isSplitCutaway ? width - 440 : width - 88,
            backgroundColor: "rgba(10,12,18,0.92)",
            border: "2px solid #2C2E38",
            borderLeft: `8px solid ${quoteTheme.accent}`,
            borderRadius: 12,
            padding: isLandscape ? "16px 22px" : "20px 24px",
            boxSizing: "border-box",
            zIndex: 24,
          }}
        >
          <div
            style={{
              color: quoteTheme.accent,
              fontSize: isLandscape ? 17 : 21,
              fontWeight: 700,
              letterSpacing: 1.1,
              fontFamily: "'JetBrains Mono', monospace",
              fontVariantNumeric: "tabular-nums",
              marginBottom: 6,
            }}
          >
            {activeQuote.calloutStat?.trim()
              ? `QUOTE CARD · ${activeQuote.calloutStat.trim().toUpperCase()}`
              : "QUOTE CARD"}
          </div>
          <div
            style={{
              color: "#FFFFFF",
              fontSize: isLandscape ? 25 : 31,
              fontWeight: 700,
              lineHeight: 1.2,
            }}
          >
            {activeQuote.headline}
          </div>
          {activeQuote.subline && (
            <div
              style={{
                color: "#9AA2B2",
                fontSize: isLandscape ? 18 : 23,
                marginTop: 6,
                lineHeight: 1.25,
              }}
            >
              {activeQuote.subline}
            </div>
          )}
        </div>
      )}

      {/* Top-Left LIVE + Brand Corner Bug */}
      {packaging.showBrandBug && (effectiveStyle !== "clean" || showChannelChip) && (
        <div
          style={{
            position: "absolute",
            top: isLandscape ? 32 : 56,
            left: isLandscape ? 56 : 44,
            display: "flex",
            alignItems: "center",
            gap: 10,
            zIndex: 28,
          }}
        >
          {effectiveStyle !== "clean" && (
            <div
              style={{
                display: "flex",
                alignItems: "center",
                gap: 8,
                padding: "8px 16px",
                borderRadius: 8,
                backgroundColor: theme.kickerBg ?? "#D71921",
                color: theme.kickerFg,
                fontSize: isLandscape ? 20 : 22,
                fontWeight: 800,
                letterSpacing: 1.4,
                fontFamily: "'JetBrains Mono', monospace",
                fontVariantNumeric: "tabular-nums",
              }}
            >
              <span
                style={{
                  width: 10,
                  height: 10,
                  borderRadius: "50%",
                  backgroundColor: theme.kickerFg,
                  display: "inline-block",
                }}
              />
              <span>LIVE</span>
            </div>
          )}
          {showChannelChip && (
            <div
              style={{
                padding: "8px 16px",
                borderRadius: 8,
                backgroundColor: "rgba(10,12,16,0.90)",
                border: "1px solid #2C2E38",
                color: "#F4F6FA",
                fontSize: isLandscape ? 20 : 22,
                fontWeight: 700,
                letterSpacing: 1.2,
                fontFamily: "'JetBrains Mono', monospace",
                fontVariantNumeric: "tabular-nums",
              }}
            >
              {cornerLabel}
            </div>
          )}
        </div>
      )}

      {/* Word-Highlighted Kinetic Captions (clearance above breaking kicker + lower-third) */}
      {!isCover && activePhrase && (
        <div
          style={{
            position: "absolute",
            bottom: isLandscape ? 176 : 284,
            left: isLandscape ? 120 : 48,
            right: isLandscape ? 120 : 48,
            display: "flex",
            flexWrap: "wrap",
            justifyContent: "center",
            gap: isLandscape ? 8 : 12,
            zIndex: 22,
          }}
        >
          {activePhrase.words.map((w, idx) => {
            const spoken = frame >= w.startFrame && frame < w.endFrame;
            return (
              <span
                key={`${w.word}_${idx}`}
                style={{
                  fontSize: isLandscape ? 34 : 46,
                  lineHeight: 1.15,
                  fontWeight: 800,
                  padding: isLandscape ? "5px 12px" : "6px 14px",
                  borderRadius: 8,
                  backgroundColor: spoken
                    ? packaging.accentHex && HEX_COLOR_RE.test(packaging.accentHex)
                      ? theme.accent
                      : effectiveStyle === "developing"
                        ? "#FFB800"
                        : effectiveStyle === "breaking"
                          ? "#D71921"
                          : "#FFFFFF"
                    : "rgba(8,9,12,0.84)",
                  color: spoken
                    ? packaging.accentHex && HEX_COLOR_RE.test(packaging.accentHex)
                      ? isLightHex(theme.accent) ? "#0A0B0E" : "#FFFFFF"
                      : effectiveStyle === "breaking"
                        ? "#FFFFFF"
                        : "#000000"
                    : "#F4F6FA",
                }}
              >
                {w.word}
              </span>
            );
          })}
        </div>
      )}

      {/* Broadcast Lower-Third Chyron + Breaking News Style Kicker Chip */}
      <div
        style={{
          position: "absolute",
          bottom: isLandscape ? 28 : 52,
          left: isLandscape ? 56 : 42,
          right: isLandscape ? 56 : 42,
          display: "flex",
          flexDirection: "column",
          alignItems: "flex-start",
          zIndex: 25,
        }}
      >
        {theme.kickerBg && theme.bannerLabel && (
          <div
            style={{
              display: "inline-flex",
              alignItems: "center",
              gap: 10,
              padding: isLandscape ? "6px 16px" : "8px 18px",
              borderTopLeftRadius: 8,
              borderTopRightRadius: 8,
              backgroundColor: theme.kickerBg,
              color: theme.kickerFg,
              fontSize: isLandscape ? 18 : 22,
              fontWeight: 800,
              letterSpacing: 1.3,
              fontFamily: "'JetBrains Mono', monospace",
              fontVariantNumeric: "tabular-nums",
            }}
          >
            <span
              style={{
                width: 9,
                height: 9,
                borderRadius: "50%",
                backgroundColor: theme.kickerFg,
                display: "inline-block",
              }}
            />
            <span>
              {activeStat && !theme.bannerLabel.toUpperCase().includes(activeStat.toUpperCase())
                ? `${theme.bannerLabel} · ${activeStat.toUpperCase()}`
                : theme.bannerLabel}
            </span>
          </div>
        )}
        <div
          style={{
            width: "100%",
            boxSizing: "border-box",
            backgroundColor: "rgba(10,12,18,0.94)",
            border: "2px solid #2C2E38",
            borderLeft: `10px solid ${theme.accent}`,
            borderTopLeftRadius: theme.kickerBg ? 0 : 12,
            borderTopRightRadius: 12,
            borderBottomLeftRadius: 12,
            borderBottomRightRadius: 12,
            padding: isLandscape ? "12px 24px" : "18px 26px",
          }}
        >
          <div
            style={{
              color: "#FFFFFF",
              fontSize: isLandscape ? 28 : 38,
              fontWeight: 700,
              whiteSpace: "nowrap",
              overflow: "hidden",
              textOverflow: "ellipsis",
            }}
          >
            {activeHeadline}
          </div>
          {activeSubline && (
            <div
              style={{
                color: effectiveStyle === "clean" ? "#9AA2B2" : theme.accent,
                fontSize: isLandscape ? 19 : 24,
                fontWeight: 600,
                fontFamily: "'JetBrains Mono', monospace",
                marginTop: isLandscape ? 4 : 6,
                whiteSpace: "nowrap",
                overflow: "hidden",
                textOverflow: "ellipsis",
              }}
            >
              {activeSubline}
            </div>
          )}
        </div>
      </div>

      {/* Opaque 0.5s First-Frame Cover Card for Crisp Feed Thumbnails */}
      {isCover && (
        <AbsoluteFill
          style={{
            backgroundColor: "#0A0C10",
            justifyContent: "center",
            alignItems: "center",
            padding: isLandscape ? 80 : 64,
            zIndex: 30,
          }}
        >
          <div
            style={{
              width: "100%",
              maxWidth: isLandscape ? 1280 : 920,
              backgroundColor: "#12151C",
              border: "2px solid #2C2E38",
              borderTop: `8px solid ${theme.accent}`,
              borderRadius: 18,
              padding: isLandscape ? "44px 56px" : "48px 40px",
              display: "flex",
              flexDirection: "column",
              alignItems: "flex-start",
              gap: 20,
            }}
          >
            {theme.kickerBg && theme.bannerLabel && (
              <div
                style={{
                  display: "flex",
                  alignItems: "center",
                  gap: 10,
                  fontFamily: "'JetBrains Mono', monospace",
                  fontVariantNumeric: "tabular-nums",
                }}
              >
                <span
                  style={{
                    padding: "6px 14px",
                    borderRadius: 6,
                    backgroundColor: theme.kickerBg,
                    color: theme.kickerFg,
                    fontSize: 20,
                    fontWeight: 800,
                    letterSpacing: 1.4,
                  }}
                >
                  {theme.bannerLabel}
                </span>
                {showChannelChip && (
                  <span
                    style={{
                      padding: "6px 14px",
                      borderRadius: 6,
                      backgroundColor: "#0A0C10",
                      border: "1px solid #2C2E38",
                      color: "#EBEBEF",
                      fontSize: 20,
                      fontWeight: 700,
                      letterSpacing: 1.2,
                    }}
                  >
                    {cornerLabel}
                  </span>
                )}
              </div>
            )}
            <div
              style={{
                color: "#FFFFFF",
                fontSize: isLandscape ? 58 : 54,
                fontWeight: 800,
                lineHeight: 1.12,
              }}
            >
              {activeHeadline}
            </div>
            {activeSubline && (
              <div
                style={{
                  color: "#9AA2B2",
                  fontSize: isLandscape ? 28 : 30,
                  fontWeight: 500,
                  lineHeight: 1.3,
                }}
              >
                {activeSubline}
              </div>
            )}
          </div>
        </AbsoluteFill>
      )}
    </AbsoluteFill>
  );
};
