import test from "node:test";
import assert from "node:assert/strict";
import * as crypto from "node:crypto";
import * as fs from "node:fs/promises";
import * as http from "node:http";
import * as net from "node:net";
import * as os from "node:os";
import * as path from "node:path";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { createServer, resolveFfmpegBin } from "../dist/server.js";

const execFileAsync = promisify(execFile);

function resolveFfprobeBin(ffmpegBin) {
  if (ffmpegBin.endsWith("/ffmpeg")) {
    return ffmpegBin.slice(0, -"/ffmpeg".length) + "/ffprobe";
  }
  return "ffprobe";
}

async function createSyntheticFixtures(dir) {
  const ffmpegBin = resolveFfmpegBin();
  const lipsyncPath = path.join(dir, "lipsync.mp4");
  const voicePath = path.join(dir, "voice.wav");
  const bRollImagePath = path.join(dir, "broll_plate.png");
  const bRollVideoPath = path.join(dir, "broll_clip.mp4");
  const alignPath = path.join(dir, "align.json");

  // 1. 2.4s talking-head synthetic MP4 (distinct frame counter per frame)
  await execFileAsync(ffmpegBin, [
    "-y",
    "-f",
    "lavfi",
    "-i",
    "testsrc2=size=640x360:rate=30:duration=2.4",
    "-c:v",
    "libx264",
    "-pix_fmt",
    "yuv420p",
    lipsyncPath,
  ]);

  // 2. 2.4s non-silent modulated WAV (speech-like tone at -18 dBFS)
  await execFileAsync(ffmpegBin, [
    "-y",
    "-f",
    "lavfi",
    "-i",
    "sine=f=320:sample_rate=44100:duration=2.4,tremolo=f=5:d=0.6,volume=0.15",
    voicePath,
  ]);

  // 3. B-roll PNG plate
  await execFileAsync(ffmpegBin, [
    "-y",
    "-f",
    "lavfi",
    "-i",
    "color=c=#18243A:size=1080x1920:duration=0.1",
    "-frames:v",
    "1",
    bRollImagePath,
  ]);

  // 4. 1.2s B-roll MP4 clip
  await execFileAsync(ffmpegBin, [
    "-y",
    "-f",
    "lavfi",
    "-i",
    "smptebars=size=640x360:rate=30:duration=1.2",
    "-c:v",
    "libx264",
    "-pix_fmt",
    "yuv420p",
    bRollVideoPath,
  ]);

  // 5. Forced alignment payload
  const words = [
    { word: "Breaking", startMs: 0, endMs: 280 },
    { word: "compute", startMs: 280, endMs: 600 },
    { word: "costs", startMs: 600, endMs: 900 },
    { word: "dropped", startMs: 900, endMs: 1200 },
    { word: "forty", startMs: 1200, endMs: 1550 },
    { word: "percent", startMs: 1550, endMs: 1950 },
    { word: "this", startMs: 1950, endMs: 2150 },
    { word: "quarter.", startMs: 2150, endMs: 2400 },
  ];
  await fs.writeFile(alignPath, JSON.stringify({ words, durationMs: 2400 }), "utf8");

  return { lipsyncPath, voicePath, bRollImagePath, bRollVideoPath, alignPath, words };
}

async function startFixtureServer(dir) {
  const mimeTypes = {
    ".mp4": "video/mp4",
    ".wav": "audio/wav",
    ".png": "image/png",
    ".json": "application/json",
  };
  const server = http.createServer(async (req, res) => {
    const pathname = new URL(req.url ?? "/", "http://127.0.0.1").pathname;
    if (pathname === "/broll-video-noext") {
      try {
        const data = await fs.readFile(path.join(dir, "broll_clip.mp4"));
        res.writeHead(200, { "Content-Type": "video/mp4", "Content-Length": String(data.length) });
        res.end(req.method === "HEAD" ? undefined : data);
      } catch {
        res.writeHead(404);
        res.end();
      }
      return;
    }

    if (pathname === "/broll-noext-head405") {
      if (req.method === "HEAD") {
        res.writeHead(405, { "Content-Type": "text/plain" });
        res.end();
        return;
      }
      try {
        const data = await fs.readFile(path.join(dir, "broll_plate.png"));
        res.writeHead(200, { "Content-Type": "image/png", "Content-Length": String(data.length) });
        res.end(data);
      } catch {
        res.writeHead(404);
        res.end();
      }
      return;
    }

    const safeName = path.basename(pathname);
    const fullPath = path.join(dir, safeName);
    try {
      const data = await fs.readFile(fullPath);
      const ext = path.extname(safeName);
      res.writeHead(200, {
        "Content-Type": mimeTypes[ext] ?? "application/octet-stream",
        "Content-Length": String(data.length),
      });
      res.end(req.method === "HEAD" ? undefined : data);
    } catch {
      res.writeHead(404);
      res.end("Not found");
    }
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  const addr = server.address();
  return { server, baseUrl: `http://127.0.0.1:${addr.port}` };
}

async function inspectMedia(ffmpegBin, mp4Path) {
  const ffprobeBin = resolveFfprobeBin(ffmpegBin);
  const { stdout: probeStdout } = await execFileAsync(ffprobeBin, [
    "-v",
    "error",
    "-show_format",
    "-show_streams",
    "-of",
    "json",
    mp4Path,
  ]);
  const probe = JSON.parse(probeStdout);

  const { stderr: silenceStderr } = await execFileAsync(ffmpegBin, [
    "-i",
    mp4Path,
    "-af",
    "silencedetect=n=-45dB:d=0.1",
    "-f",
    "null",
    "-",
  ]);

  const { stderr: volumeStderr } = await execFileAsync(ffmpegBin, [
    "-i",
    mp4Path,
    "-af",
    "volumedetect",
    "-f",
    "null",
    "-",
  ]);

  return { probe, silenceStderr, volumeStderr };
}

test("POST /render executes real Newsroom916 (alignUrl + image/video B-roll) and Explainer169 (inline words) renders without GCP credentials", async () => {
  const prevCreds = process.env.GOOGLE_APPLICATION_CREDENTIALS;
  process.env.GOOGLE_APPLICATION_CREDENTIALS = "/nonexistent/gcp-creds.json";

  const tempDir = await fs.mkdtemp(path.join(os.tmpdir(), "aircheck-smoke-"));
  const workerServer = createServer();
  let fixtureServer;

  try {
    const fixtures = await createSyntheticFixtures(tempDir);
    const startedFixture = await startFixtureServer(tempDir);
    fixtureServer = startedFixture.server;
    const fixtureBase = startedFixture.baseUrl;

    await new Promise((resolve) => workerServer.listen(0, "127.0.0.1", resolve));
    const workerAddr = workerServer.address();
    const workerBase = `http://127.0.0.1:${workerAddr.port}`;
    const ffmpegBin = resolveFfmpegBin();

    // 1. Newsroom916 (1080x1920) using alignUrl + image & video B-roll + LIVE corner bug
    const out916 = path.join(tempDir, "out_916.mp4");
    const res916 = await fetch(`${workerBase}/render`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        jobId: "smoke-916",
        outputLocalPath: out916,
        inputProps: {
          templateId: "Newsroom916",
          lipsyncUrl: `${fixtureBase}/lipsync.mp4`,
          voiceUrl: `${fixtureBase}/voice.wav`,
          alignUrl: `${fixtureBase}/align.json`,
          beats: [
            {
              ordinal: 0,
              startMs: 100,
              endMs: 1200,
              bRollUrl: `${fixtureBase}/broll_plate.png`,
              bRollMediaType: "image",
              evidenceSource: "Q4 Compute Audit",
              depthTextTag: "-40% COST",
              pipShrink: true,
            },
            {
              ordinal: 1,
              startMs: 1200,
              endMs: 2400,
              bRollUrl: `${fixtureBase}/broll_clip.mp4`,
              bRollMediaType: "video",
              depthTextTag: "LIVE FEED",
              pipShrink: true,
            },
          ],
          packaging: {
            chyronHeadline: "Compute Costs Drop 40%",
            chyronSubline: "AIRCHECK NEWSROOM",
            cornerBugText: "AIRCHECK",
            showBrandBug: true,
          },
        },
      }),
    });
    assert.equal(res916.status, 200, `9:16 render failed: ${res916.status}`);
    const body916 = await res916.json();
    assert.equal(body916.width, 1080);
    assert.equal(body916.height, 1920);
    assert.equal(body916.fps, 30);
    assert.equal(body916.durationMs, 2900); // 15 cover + 72 content = 87 frames
    assert.equal(body916.outputLocalPath, out916);
    assert.match(body916.finalSha256, /^[0-9a-f]{64}$/);
    assert.ok(Number.isInteger(body916.captionPhraseCount) && body916.captionPhraseCount > 0);

    const fileBytes916 = await fs.readFile(out916);
    assert.equal(crypto.createHash("sha256").update(fileBytes916).digest("hex"), body916.finalSha256);

    const { probe: probe916, silenceStderr: silence916, volumeStderr: volume916 } = await inspectMedia(ffmpegBin, out916);
    const vStream916 = probe916.streams.find((s) => s.codec_type === "video");
    const aStream916 = probe916.streams.find((s) => s.codec_type === "audio");
    assert.ok(vStream916 && aStream916);
    assert.equal(vStream916.codec_name, "h264");
    assert.equal(vStream916.width, 1080);
    assert.equal(vStream916.height, 1920);
    assert.equal(vStream916.r_frame_rate, "30/1");
    assert.equal(vStream916.nb_frames, "87");
    assert.equal(aStream916.codec_name, "aac");
    assert.equal(aStream916.sample_rate, "48000");

    // Verify 0.5s cover silence ends around t=0.50s (no 500ms audio chop / early start)
    const silenceEnds = [...silence916.matchAll(/silence_end:\s*([0-9.]+)/g)].map((m) => Number(m[1]));
    assert.equal(silenceEnds.length, 1, `Expected single cover silence segment, got stderr:\n${silence916}`);
    assert.ok(
      silenceEnds[0] >= 0.40 && silenceEnds[0] <= 0.62,
      `Expected audio onset near 0.50s, got ${silenceEnds[0]}s`,
    );

    const meanMatch916 = volume916.match(/mean_volume:\s*(-?[0-9.]+)\s*dB/);
    const maxMatch916 = volume916.match(/max_volume:\s*(-?[0-9.]+)\s*dB/);
    assert.ok(meanMatch916 && maxMatch916);
    assert.ok(Number(meanMatch916[1]) > -40, `Audio too quiet: ${meanMatch916[1]} dB`);
    assert.ok(Number(maxMatch916[1]) <= 0, `Audio clipped: ${maxMatch916[1]} dB`);

    // Extract cover frame (t=0.1s) and B-roll frame (t=1.2s) to ensure distinct non-black frames
    const coverPng = path.join(tempDir, "frame_cover.png");
    const contentPng = path.join(tempDir, "frame_content.png");
    await execFileAsync(ffmpegBin, ["-y", "-ss", "0.1", "-i", out916, "-frames:v", "1", coverPng]);
    await execFileAsync(ffmpegBin, ["-y", "-ss", "1.2", "-i", out916, "-frames:v", "1", contentPng]);
    const coverBytes = await fs.readFile(coverPng);
    const contentBytes = await fs.readFile(contentPng);
    assert.ok(coverBytes.length > 1000 && contentBytes.length > 1000);
    assert.notDeepEqual(
      crypto.createHash("sha256").update(coverBytes).digest("hex"),
      crypto.createHash("sha256").update(contentBytes).digest("hex"),
    );

    // 2. Explainer169 (1920x1080) using inline words (no alignUrl) + punch-in beat
    const out169 = path.join(tempDir, "out_169.mp4");
    const res169 = await fetch(`${workerBase}/render`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        jobId: "smoke-169",
        outputLocalPath: out169,
        inputProps: {
          templateId: "Explainer169",
          lipsyncUrl: `${fixtureBase}/lipsync.mp4`,
          voiceUrl: `${fixtureBase}/voice.wav`,
          words: fixtures.words,
          durationMs: 2400,
          beats: [
            {
              ordinal: 0,
              startMs: 100,
              endMs: 1200,
              bRollUrl: `${fixtureBase}/broll_clip.mp4`,
              bRollMediaType: "video",
              evidenceSource: "Cluster Telemetry",
              depthTextTag: "16:9 STAGE",
              pipShrink: true,
            },
            {
              ordinal: 1,
              startMs: 1200,
              endMs: 2400,
              depthTextTag: "+14% ZOOM",
              pipShrink: false,
            },
          ],
          packaging: {
            chyronHeadline: "Explainer 16:9 Broadcast",
            chyronSubline: "LOCAL WORKER",
            cornerBugText: "LIVE",
            showBrandBug: true,
          },
        },
      }),
    });
    assert.equal(res169.status, 200, `16:9 render failed: ${res169.status}`);
    const body169 = await res169.json();
    assert.equal(body169.width, 1920);
    assert.equal(body169.height, 1080);
    assert.equal(body169.fps, 30);
    assert.equal(body169.durationMs, 2900);

    const { probe: probe169, volumeStderr: volume169 } = await inspectMedia(ffmpegBin, out169);
    const vStream169 = probe169.streams.find((s) => s.codec_type === "video");
    const aStream169 = probe169.streams.find((s) => s.codec_type === "audio");
    assert.equal(vStream169.codec_name, "h264");
    assert.equal(vStream169.width, 1920);
    assert.equal(vStream169.height, 1080);
    assert.equal(vStream169.r_frame_rate, "30/1");
    assert.equal(vStream169.nb_frames, "87");
    assert.equal(aStream169.codec_name, "aac");
    assert.equal(aStream169.sample_rate, "48000");

    const meanMatch169 = volume169.match(/mean_volume:\s*(-?[0-9.]+)\s*dB/);
    assert.ok(meanMatch169 && Number(meanMatch169[1]) > -40);

    // 3. Extensionless video B-roll URL (NO bRollMediaType) + Explainer169 long 2-line caption + evidenceSource
    const outNoExt = path.join(tempDir, "out_169_noext.mp4");
    const longWords = [
      { word: "Electroencephalographically", startMs: 0, endMs: 600 },
      { word: "Counterrevolutionaries", startMs: 600, endMs: 1200 },
      { word: "Microarchitectural", startMs: 1200, endMs: 1800 },
      { word: "Synchronization", startMs: 1800, endMs: 2400 },
    ];
    const resNoExt = await fetch(`${workerBase}/render`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        jobId: "smoke-169-noext",
        outputLocalPath: outNoExt,
        inputProps: {
          templateId: "Explainer169",
          lipsyncUrl: `${fixtureBase}/lipsync.mp4`,
          voiceUrl: `${fixtureBase}/voice.wav`,
          words: longWords,
          durationMs: 2400,
          beats: [
            {
              ordinal: 0,
              startMs: 100,
              endMs: 2200,
              bRollUrl: `${fixtureBase}/broll-video-noext`,
              evidenceSource: "Long Source Label For Overlap Check",
              depthTextTag: "HEAD VIDEO",
              pipShrink: true,
            },
          ],
          packaging: {
            chyronHeadline: "Extensionless Video B-Roll & Long Caption Clearance",
            chyronSubline: "AIRCHECK M1 REGRESSION",
            cornerBugText: "LIVE",
            showBrandBug: true,
          },
        },
      }),
    });
    assert.equal(resNoExt.status, 200, `16:9 noext render failed: ${resNoExt.status}`);
    const bodyNoExt = await resNoExt.json();
    assert.equal(bodyNoExt.width, 1920);
    assert.equal(bodyNoExt.height, 1080);
    assert.equal(bodyNoExt.fps, 30);
    assert.equal(bodyNoExt.durationMs, 2900);
    assert.match(bodyNoExt.finalSha256, /^[0-9a-f]{64}$/);
  } finally {
    if (prevCreds === undefined) delete process.env.GOOGLE_APPLICATION_CREDENTIALS;
    else process.env.GOOGLE_APPLICATION_CREDENTIALS = prevCreds;
    await new Promise((resolve) => workerServer.close(resolve));
    if (fixtureServer) {
      await new Promise((resolve) => fixtureServer.close(resolve));
    }
    await fs.rm(tempDir, { recursive: true, force: true });
  }
});

test("POST /render succeeds under 127.0.0.1:3000 contention and renders extensionless B-roll when HEAD returns 405", async () => {
  const prevCreds = process.env.GOOGLE_APPLICATION_CREDENTIALS;
  const prevInternalPort = process.env.REMOTION_INTERNAL_PORT;
  process.env.GOOGLE_APPLICATION_CREDENTIALS = "/nonexistent/gcp-creds.json";
  delete process.env.REMOTION_INTERNAL_PORT;

  const tempDir = await fs.mkdtemp(path.join(os.tmpdir(), "aircheck-smoke-port3000-"));
  const workerServer = createServer();
  const port3000Blocker = net.createServer();
  let blockerBound = false;
  let fixtureServer;

  try {
    await new Promise((resolve, reject) => {
      const onErr = (err) => {
        if (err.code === "EADDRINUSE") {
          resolve();
          return;
        }
        reject(err);
      };
      port3000Blocker.once("error", onErr);
      port3000Blocker.listen(3000, "127.0.0.1", () => {
        port3000Blocker.off("error", onErr);
        blockerBound = true;
        resolve();
      });
    });

    const fixtures = await createSyntheticFixtures(tempDir);
    const startedFixture = await startFixtureServer(tempDir);
    fixtureServer = startedFixture.server;
    const fixtureBase = startedFixture.baseUrl;

    await new Promise((resolve) => workerServer.listen(0, "127.0.0.1", resolve));
    const workerAddr = workerServer.address();
    const workerBase = `http://127.0.0.1:${workerAddr.port}`;
    const ffmpegBin = resolveFfmpegBin();

    const outMp4 = path.join(tempDir, "out_port3000_head405.mp4");
    const res = await fetch(`${workerBase}/render`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        jobId: "smoke-port3000-head405",
        outputLocalPath: outMp4,
        inputProps: {
          templateId: "Newsroom916",
          lipsyncUrl: `${fixtureBase}/lipsync.mp4`,
          voiceUrl: `${fixtureBase}/voice.wav`,
          words: fixtures.words,
          durationMs: 2400,
          beats: [
            {
              ordinal: 0,
              startMs: 100,
              endMs: 2000,
              bRollUrl: `${fixtureBase}/broll-noext-head405`,
              evidenceSource: "HEAD 405 Fallback",
              depthTextTag: "PORT SAFE",
              pipShrink: true,
            },
          ],
          packaging: {
            chyronHeadline: "Ephemeral Port & HEAD 405",
            chyronSubline: "AIRCHECK M1",
            cornerBugText: "LIVE",
            showBrandBug: true,
          },
        },
      }),
    });

    assert.equal(res.status, 200, `Render under :3000 contention failed: ${res.status}`);
    const body = await res.json();
    assert.equal(body.width, 1080);
    assert.equal(body.height, 1920);
    assert.equal(body.fps, 30);
    assert.equal(body.durationMs, 2900);
    assert.match(body.finalSha256, /^[0-9a-f]{64}$/);

    const { probe } = await inspectMedia(ffmpegBin, outMp4);
    const vStream = probe.streams.find((s) => s.codec_type === "video");
    const aStream = probe.streams.find((s) => s.codec_type === "audio");
    assert.ok(vStream && aStream);
    assert.equal(vStream.nb_frames, "87");
  } finally {
    if (prevCreds === undefined) delete process.env.GOOGLE_APPLICATION_CREDENTIALS;
    else process.env.GOOGLE_APPLICATION_CREDENTIALS = prevCreds;
    if (prevInternalPort === undefined) delete process.env.REMOTION_INTERNAL_PORT;
    else process.env.REMOTION_INTERNAL_PORT = prevInternalPort;
    if (blockerBound) {
      await new Promise((resolve) => port3000Blocker.close(resolve));
    }
    await new Promise((resolve) => workerServer.close(resolve));
    if (fixtureServer) {
      await new Promise((resolve) => fixtureServer.close(resolve));
    }
    await fs.rm(tempDir, { recursive: true, force: true });
  }
});
