import { cancelRender, continueRender, delayRender, staticFile } from "remotion";

let fontLoadPromise: Promise<void> | null = null;

/**
 * Loads bundled Space Grotesk and JetBrains Mono static TTF fonts into `document.fonts`
 * once per browser session, blocking Remotion frame capture via `delayRender` until ready.
 */
export function ensureBroadcastFonts(): void {
  if (typeof window === "undefined" || typeof document === "undefined" || typeof FontFace === "undefined") {
    return;
  }
  if (fontLoadPromise) {
    return;
  }

  const handle = delayRender("Loading Space Grotesk and JetBrains Mono fonts");

  fontLoadPromise = (async () => {
    const spaceGrotesk = new FontFace(
      "Space Grotesk",
      `url(${staticFile("fonts/space_grotesk.ttf")})`,
      { style: "normal" },
    );
    const jetbrainsMono = new FontFace(
      "JetBrains Mono",
      `url(${staticFile("fonts/jetbrains_mono.ttf")})`,
      { style: "normal" },
    );

    const [loadedSpace, loadedMono] = await Promise.all([
      spaceGrotesk.load(),
      jetbrainsMono.load(),
    ]);

    document.fonts.add(loadedSpace);
    document.fonts.add(loadedMono);
    await document.fonts.ready;
    continueRender(handle);
  })().catch((err: unknown) => {
    fontLoadPromise = null;
    cancelRender(err instanceof Error ? err : new Error(String(err)));
  });
}
