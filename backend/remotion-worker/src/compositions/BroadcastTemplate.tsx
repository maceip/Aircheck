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
import { CompiledTimeline, PackagingSpec } from "../timeline";

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

  // Responsive non-overlapping PiP target geometry
  const pipTarget = isLandscape
    ? { left: 1264, top: 96, width: 600, height: 480, radius: 24 }
    : { left: width - 44 - 320, top: 56, width: 320, height: 420, radius: 22 };

  const presenterLeft = interpolate(pipProgress, [0, 1], [0, pipTarget.left], {
    extrapolateLeft: "clamp",
    extrapolateRight: "clamp",
  });
  const presenterTop = interpolate(pipProgress, [0, 1], [0, pipTarget.top], {
    extrapolateLeft: "clamp",
    extrapolateRight: "clamp",
  });
  const presenterWidth = interpolate(pipProgress, [0, 1], [width, pipTarget.width], {
    extrapolateLeft: "clamp",
    extrapolateRight: "clamp",
  });
  const presenterHeight = interpolate(pipProgress, [0, 1], [height, pipTarget.height], {
    extrapolateLeft: "clamp",
    extrapolateRight: "clamp",
  });
  const presenterRadius = interpolate(pipProgress, [0, 1], [0, pipTarget.radius], {
    extrapolateLeft: "clamp",
    extrapolateRight: "clamp",
  });

  const contentDuration = Math.max(1, timeline.totalFrames - timeline.coverFrames);
  const cornerLabel = (packaging.cornerBugText ?? "").trim();
  const showChannelChip = Boolean(cornerLabel && cornerLabel.toUpperCase() !== "LIVE");

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

      {/* B-Roll / Evidence Stage (behind PiP presenter, non-overlapping in both 9:16 and 16:9) */}
      {activeEvent?.pipShrink && (
        <div
          style={{
            position: "absolute",
            left: isLandscape ? 56 : 48,
            top: isLandscape ? 96 : 504,
            width: isLandscape ? 1160 : width - 96,
            opacity: pipProgress,
            transform: `translateY(${interpolate(pipProgress, [0, 1], [24, 0], {
              extrapolateLeft: "clamp",
              extrapolateRight: "clamp",
            }).toFixed(2)}px)`,
            zIndex: 5,
          }}
        >
          {activeEvent.bRollUrl && (
            <div
              style={{
                width: "100%",
                height: isLandscape ? 584 : 616,
                borderRadius: 18,
                overflow: "hidden",
                border: "2px solid #232730",
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
          )}

          {/* Landscape 16:9 Citation Pill under B-Roll */}
          {isLandscape && activeEvent.citation && (
            <div
              style={{
                marginTop: 14,
                display: "inline-flex",
                alignItems: "center",
                gap: 12,
                padding: "8px 18px",
                borderRadius: 12,
                backgroundColor: "#12151C",
                border: "2px solid #F5B700",
                color: "#F5B700",
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

          {/* Portrait 9:16 Citation + depthTextTag flex-wrap container */}
          {!isLandscape && (activeEvent.citation || activeEvent.keyword) && (
            <div
              style={{
                marginTop: 20,
                display: "flex",
                flexWrap: "wrap",
                alignItems: "center",
                gap: 16,
              }}
            >
              {activeEvent.citation && (
                <div
                  style={{
                    display: "inline-flex",
                    alignItems: "center",
                    gap: 12,
                    padding: "14px 24px",
                    borderRadius: 12,
                    backgroundColor: "#12151C",
                    border: "2px solid #F5B700",
                    color: "#F5B700",
                    fontSize: 30,
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
                    padding: "14px 24px",
                    borderRadius: 12,
                    backgroundColor: "#12151C",
                    border: "2px solid #B8FF00",
                    color: "#B8FF00",
                    fontSize: 34,
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
            padding: "16px 24px",
            borderRadius: 14,
            backgroundColor: "#12151C",
            border: "2px solid #B8FF00",
            color: "#B8FF00",
            fontSize: 34,
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
            padding: "12px 22px",
            borderRadius: 12,
            backgroundColor: "rgba(10,12,16,0.92)",
            border: "2px solid #B8FF00",
            color: "#B8FF00",
            fontSize: isLandscape ? 32 : 36,
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
            transform: activeEvent?.pipShrink ? undefined : `scale(${punchScale.toFixed(4)})`,
            transformOrigin: "center center",
            borderRadius: presenterRadius,
            overflow: "hidden",
            border: pipProgress > 0.05 ? "3px solid #B8FF00" : "none",
            boxShadow: pipProgress > 0.05 ? "0 18px 42px rgba(0, 0, 0, 0.55)" : "none",
            zIndex: activeEvent?.pipShrink ? 10 : 2,
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

      {/* Top-Left LIVE + Brand Corner Bug */}
      {packaging.showBrandBug && (
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
          <div
            style={{
              display: "flex",
              alignItems: "center",
              gap: 8,
              padding: "8px 16px",
              borderRadius: 8,
              backgroundColor: "#D90429",
              color: "#F4F6FA",
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
                backgroundColor: "#F4F6FA",
                display: "inline-block",
              }}
            />
            <span>LIVE</span>
          </div>
          {showChannelChip && (
            <div
              style={{
                padding: "8px 16px",
                borderRadius: 8,
                backgroundColor: "rgba(10,12,16,0.90)",
                border: "1px solid #232730",
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

      {/* Word-Highlighted Kinetic Captions */}
      {!isCover && activePhrase && (
        <div
          style={{
            position: "absolute",
            bottom: isLandscape ? 156 : 248,
            left: isLandscape ? 120 : 48,
            right: isLandscape ? 120 : 48,
            display: "flex",
            flexWrap: "wrap",
            justifyContent: "center",
            gap: isLandscape ? 8 : 14,
            zIndex: 22,
          }}
        >
          {activePhrase.words.map((w, idx) => {
            const spoken = frame >= w.startFrame && frame < w.endFrame;
            return (
              <span
                key={`${w.word}_${idx}`}
                style={{
                  fontSize: isLandscape ? 36 : 50,
                  lineHeight: isLandscape ? 1.15 : undefined,
                  fontWeight: 800,
                  padding: isLandscape ? "5px 12px" : "6px 14px",
                  borderRadius: 10,
                  backgroundColor: spoken ? "#B8FF00" : "rgba(0,0,0,0.78)",
                  color: spoken ? "#000000" : "#F4F6FA",
                }}
              >
                {w.word}
              </span>
            );
          })}
        </div>
      )}

      {/* Clean Broadcast Lower-Third Chyron */}
      <div
        style={{
          position: "absolute",
          bottom: isLandscape ? 28 : 56,
          left: isLandscape ? 56 : 42,
          right: isLandscape ? 56 : 42,
          backgroundColor: "rgba(10,12,16,0.92)",
          border: "2px solid #232730",
          borderLeft: "12px solid #B8FF00",
          borderRadius: 14,
          padding: isLandscape ? "12px 24px" : "20px 28px",
          zIndex: 25,
        }}
      >
        <div
          style={{
            color: "#F4F6FA",
            fontSize: isLandscape ? 28 : 38,
            fontWeight: 700,
            ...(isLandscape
              ? { whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }
              : {}),
          }}
        >
          {packaging.chyronHeadline}
        </div>
        {packaging.chyronSubline && (
          <div
            style={{
              color: "#8A93A2",
              fontSize: isLandscape ? 19 : 26,
              marginTop: isLandscape ? 4 : 6,
              ...(isLandscape
                ? { whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }
                : {}),
            }}
          >
            {packaging.chyronSubline}
          </div>
        )}
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
              border: "2px solid #232730",
              borderTop: "8px solid #B8FF00",
              borderRadius: 20,
              padding: isLandscape ? "44px 56px" : "48px 40px",
              display: "flex",
              flexDirection: "column",
              alignItems: "flex-start",
              gap: 20,
            }}
          >
            {packaging.showBrandBug && (
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
                    borderRadius: 8,
                    backgroundColor: "#D90429",
                    color: "#F4F6FA",
                    fontSize: 20,
                    fontWeight: 800,
                    letterSpacing: 1.4,
                  }}
                >
                  LIVE
                </span>
                {showChannelChip && (
                  <span
                    style={{
                      padding: "6px 14px",
                      borderRadius: 8,
                      backgroundColor: "#0A0C10",
                      border: "1px solid #232730",
                      color: "#B8FF00",
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
                color: "#F4F6FA",
                fontSize: isLandscape ? 60 : 56,
                fontWeight: 800,
                lineHeight: 1.12,
              }}
            >
              {packaging.chyronHeadline}
            </div>
            {packaging.chyronSubline && (
              <div
                style={{
                  color: "#8A93A2",
                  fontSize: isLandscape ? 30 : 32,
                  fontWeight: 500,
                  lineHeight: 1.3,
                }}
              >
                {packaging.chyronSubline}
              </div>
            )}
          </div>
        </AbsoluteFill>
      )}
    </AbsoluteFill>
  );
};
