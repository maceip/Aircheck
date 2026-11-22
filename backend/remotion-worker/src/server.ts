import * as crypto from "node:crypto";
import * as fs from "node:fs/promises";
import * as http from "node:http";
import * as path from "node:path";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { Storage } from "@google-cloud/storage";
import { bundle } from "@remotion/bundler";
import { renderMedia, selectComposition } from "@remotion/renderer";
import { AlignedWord, BeatSpec, PackagingSpec, compileTimeline } from "./timeline";

const execFileAsync = promisify(execFile);
const storage = new Storage();

let cachedBundleLocation: string | null = null;

async function getBundleLocation(): Promise<string> {
  if (cachedBundleLocation) return cachedBundleLocation;
  cachedBundleLocation = await bundle({
    entryPoint: path.resolve(__dirname, "remotionRoot.js"),
  });
  return cachedBundleLocation;
}

/**
 * Applies broadcast -16 LUFS loudnorm and relocates the `moov` atom to head (`-movflags +faststart`)
 * so Android Media3 ExoPlayer starts progressive HTTP byte-range playback in <150 ms.
 */
async function postProcessBroadcastMaster(rawMp4: string, faststartMp4: string): Promise<void> {
  await execFileAsync("ffmpeg", [
    "-y",
    "-i",
    rawMp4,
    "-c:v",
    "copy",
    "-af",
    "loudnorm=I=-16:TP=-1.5:LRA=11",
    "-c:a",
    "aac",
    "-b:a",
    "192k",
    "-movflags",
    "+faststart",
    faststartMp4,
  ]);
}

async function handleRenderJob(body: {
  jobId: string;
  bucketName: string;
  outputGcsPath: string;
  inputProps: {
    lipsyncUrl: string;
    voiceUrl: string;
    alignUrl: string;
    beats: BeatSpec[];
    packaging: PackagingSpec;
    templateId?: "Newsroom916" | "Explainer169";
  };
}) {
  // Strict per-job isolated workspace — physically impossible for old clips to leak
  const safeJobId = body.jobId.replace(/[^a-zA-Z0-9_-]/g, "");
  if (!safeJobId) throw new Error("Invalid jobId");
  const workDir = path.join("/tmp/jobs", safeJobId);
  await fs.mkdir(workDir, { recursive: true });

  try {
    const alignResp = await fetch(body.inputProps.alignUrl);
    if (!alignResp.ok) throw new Error(`Failed to fetch alignUrl (HTTP ${alignResp.status})`);
    const alignPayload = (await alignResp.json()) as { words?: AlignedWord[]; durationMs?: number };
    const words = alignPayload.words ?? [];

    const timeline = compileTimeline({
      words,
      beats: body.inputProps.beats ?? [],
      fps: 30,
      coverSec: 0.5,
    });

    const compositionId = body.inputProps.templateId === "Explainer169" ? "Explainer169" : "Newsroom916";
    const serveUrl = await getBundleLocation();
    const inputProps = {
      lipsyncUrl: body.inputProps.lipsyncUrl,
      voiceUrl: body.inputProps.voiceUrl,
      timeline,
      packaging: body.inputProps.packaging,
    };

    const composition = await selectComposition({ serveUrl, id: compositionId, inputProps });
    const rawMp4 = path.join(workDir, "raw.mp4");
    const finalMp4 = path.join(workDir, "final_faststart.mp4");

    await renderMedia({
      composition: { ...composition, durationInFrames: timeline.totalFrames },
      serveUrl,
      codec: "h264",
      outputLocation: rawMp4,
      inputProps,
    });

    await postProcessBroadcastMaster(rawMp4, finalMp4);
    const finalBytes = await fs.readFile(finalMp4);
    const finalSha256 = crypto.createHash("sha256").update(finalBytes).digest("hex");

    await storage.bucket(body.bucketName).file(body.outputGcsPath).save(finalBytes, {
      contentType: "video/mp4",
      metadata: {
        cacheControl: "public, max-age=31536000, immutable",
        metadata: { sha256: finalSha256 },
      },
    });

    return {
      finalSha256,
      durationMs: Math.round((timeline.totalFrames / timeline.fps) * 1000),
    };
  } finally {
    // Always erase isolated per-job temp folder immediately
    await fs.rm(workDir, { recursive: true, force: true });
  }
}

export function createServer() {
  return http.createServer(async (req, res) => {
    if (req.method === "GET" && req.url === "/healthz") {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok: true }));
      return;
    }
    if (req.method !== "POST" || req.url !== "/render") {
      res.writeHead(404);
      res.end("Not found");
      return;
    }

    const expectedToken = process.env.REMOTION_WORKER_TOKEN;
    if (expectedToken && req.headers.authorization !== `Bearer ${expectedToken}`) {
      res.writeHead(401);
      res.end("Unauthorized");
      return;
    }

    try {
      const chunks: Buffer[] = [];
      for await (const chunk of req) chunks.push(Buffer.from(chunk));
      const payload = JSON.parse(Buffer.concat(chunks).toString("utf8"));
      const result = await handleRenderJob(payload);
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(result));
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : String(err);
      res.writeHead(500, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ error: msg }));
    }
  });
}

if (require.main === module) {
  const port = Number(process.env.PORT ?? 8080);
  createServer().listen(port, () => {
    console.log(`Aircheck stateless Remotion worker listening on :${port}`);
  });
}
