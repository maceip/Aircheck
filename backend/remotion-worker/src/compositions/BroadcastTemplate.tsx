import React from "react";
import { AbsoluteFill, Audio, Img, OffthreadVideo, spring, useCurrentFrame, useVideoConfig } from "remotion";
import { CompiledTimeline, PackagingSpec } from "../timeline";

export type AircheckTemplateProps = {
  lipsyncUrl: string;
  voiceUrl: string;
  timeline: CompiledTimeline;
  packaging: PackagingSpec;
};

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
  const frame = useCurrentFrame();
  const { fps } = useVideoConfig();

  const isCover = frame < timeline.coverFrames;
  const activeEvent = timeline.events.find((e) => frame >= e.triggerFrame && frame <= e.endFrame);
  const activePhrase = timeline.captions.find((c) => frame >= c.startFrame && frame <= c.endFrame);

  const punchSpring = spring({
    frame: activeEvent ? frame - activeEvent.triggerFrame : 0,
    fps,
    config: { damping: 16, stiffness: 180 },
  });
  const scale = activeEvent && !activeEvent.pipShrink ? 1 + (activeEvent.punchInScale - 1) * punchSpring : 1.0;

  return (
    <AbsoluteFill style={{ backgroundColor: "#000000", fontFamily: "Space Grotesk, sans-serif" }}>
      {/* Presenter Video Layer (Full Screen or Smooth PiP during Evidence Cards) */}
      <AbsoluteFill
        style={{
          transform: activeEvent?.pipShrink
            ? "translate(28%, -26%) scale(0.38)"
            : `scale(${scale.toFixed(4)})`,
          borderRadius: activeEvent?.pipShrink ? 28 : 0,
          overflow: "hidden",
          border: activeEvent?.pipShrink ? "3px solid #B8FF00" : "none",
          zIndex: activeEvent?.pipShrink ? 10 : 1,
        }}
      >
        <OffthreadVideo src={lipsyncUrl} style={{ width: "100%", height: "100%", objectFit: "cover" }} muted />
      </AbsoluteFill>

      {/* Master Cloned Voice Track (offset cleanly past 0.5s thumbnail cover) */}
      {!isCover && <Audio src={voiceUrl} />}

      {/* Evidence Card / B-Roll Plate when Word Anchor Triggers */}
      {activeEvent?.pipShrink && (
        <AbsoluteFill
          style={{
            justifyContent: "center",
            alignItems: "center",
            padding: 56,
            backgroundColor: "#0A0C10",
            zIndex: 5,
          }}
        >
          {activeEvent.bRollUrl && (
            <Img
              src={activeEvent.bRollUrl}
              style={{ width: "100%", height: "54%", objectFit: "cover", borderRadius: 18, border: "2px solid #232730" }}
            />
          )}
          {activeEvent.citation && (
            <div
              style={{
                marginTop: 28,
                padding: "16px 26px",
                borderRadius: 12,
                backgroundColor: "#12151C",
                border: "2px solid #F5B700",
                color: "#F5B700",
                fontSize: 34,
                fontWeight: 700,
              }}
            >
              {activeEvent.citation}
            </div>
          )}
        </AbsoluteFill>
      )}

      {/* Word-Highlighted Kinetic Captions */}
      {!isCover && activePhrase && (
        <div
          style={{
            position: "absolute",
            bottom: 260,
            left: 48,
            right: 48,
            display: "flex",
            flexWrap: "wrap",
            justifyContent: "center",
            gap: 14,
            zIndex: 20,
          }}
        >
          {activePhrase.words.map((w, idx) => {
            const spoken = frame >= w.startFrame && frame <= w.endFrame;
            return (
              <span
                key={`${w.word}_${idx}`}
                style={{
                  fontSize: 54,
                  fontWeight: 800,
                  padding: "6px 14px",
                  borderRadius: 10,
                  backgroundColor: spoken ? "#B8FF00" : "rgba(0,0,0,0.72)",
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
          bottom: 72,
          left: 42,
          right: 42,
          backgroundColor: "rgba(10,12,16,0.92)",
          border: "2px solid #232730",
          borderLeft: "12px solid #B8FF00",
          borderRadius: 14,
          padding: "20px 28px",
          zIndex: 25,
        }}
      >
        <div style={{ color: "#F4F6FA", fontSize: 38, fontWeight: 700 }}>{packaging.chyronHeadline}</div>
        {packaging.chyronSubline && (
          <div style={{ color: "#8A93A2", fontSize: 26, marginTop: 6 }}>{packaging.chyronSubline}</div>
        )}
      </div>

      {/* 0.5s First-Frame Cover Card for Crisp Feed Thumbnails */}
      {isCover && (
        <AbsoluteFill
          style={{
            backgroundColor: "rgba(0,0,0,0.45)",
            justifyContent: "center",
            alignItems: "center",
            padding: 64,
            zIndex: 30,
          }}
        >
          <div
            style={{
              backgroundColor: "#0A0C10",
              border: "2px solid #B8FF00",
              borderRadius: 16,
              padding: "28px 36px",
              color: "#F4F6FA",
              fontSize: 56,
              fontWeight: 800,
              textAlign: "center",
            }}
          >
            {packaging.chyronHeadline}
          </div>
        </AbsoluteFill>
      )}
    </AbsoluteFill>
  );
};
