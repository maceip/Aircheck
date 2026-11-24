import React from "react";
import { Composition, registerRoot } from "remotion";
import { AircheckBroadcastTemplate, AircheckTemplateProps } from "./compositions/BroadcastTemplate";
import { ensureBroadcastFonts } from "./fonts";

ensureBroadcastFonts();

const defaultProps: AircheckTemplateProps = {
  lipsyncUrl: "",
  voiceUrl: "",
  timeline: { fps: 30, coverFrames: 15, totalFrames: 90, captions: [], events: [] },
  packaging: {
    chyronHeadline: "Aircheck",
    chyronSubline: "",
    cornerBugText: "AIRCHECK",
    showBrandBug: true,
  },
};

export const RemotionRoot: React.FC = () => (
  <>
    <Composition
      id="Newsroom916"
      component={AircheckBroadcastTemplate}
      durationInFrames={90}
      fps={30}
      width={1080}
      height={1920}
      defaultProps={defaultProps}
    />
    <Composition
      id="Explainer169"
      component={AircheckBroadcastTemplate}
      durationInFrames={90}
      fps={30}
      width={1920}
      height={1080}
      defaultProps={defaultProps}
    />
  </>
);

registerRoot(RemotionRoot);
