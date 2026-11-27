import * as crypto from "node:crypto";
import * as fsSync from "node:fs";
import * as fs from "node:fs/promises";
import * as http from "node:http";
import * as net from "node:net";
import * as os from "node:os";
import * as path from "node:path";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import type { Storage as StorageClient } from "@google-cloud/storage";
import { bundle } from "@remotion/bundler";
import { renderMedia, selectComposition } from "@remotion/renderer";
import { VERSION as REMOTION_VERSION } from "remotion/version";
import { AlignedWord, BeatSpec, BreakingStyle, BrollFraming, OverlayItemSpec, PackagingSpec, compileTimeline } from "./timeline";

const execFileAsync = promisify(execFile);

const MAX_BODY_BYTES = 5 * 1024 * 1024;
const MAX_OVERFLOW_DRAIN_BYTES = 32 * 1024 * 1024;
const OVERFLOW_DRAIN_TIMEOUT_MS = 2_000;
const ALIGN_FETCH_TIMEOUT_MS = 15_000;
const B_ROLL_HEAD_TIMEOUT_MS = 3_000;
const DEFAULT_RENDER_TIMEOUT_MS = 60_000;
const FFMPEG_TIMEOUT_MS = 120_000;
const FFMPEG_MAX_BUFFER_BYTES = 8 * 1024 * 1024;

const IMAGE_EXTENSION_RE = /\.(png|jpe?g|webp|gif|bmp|svg|avif)$/i;
const VIDEO_EXTENSION_RE = /\.(mp4|mov|webm|m4v|mkv)$/i;

export class HttpError extends Error {
  public readonly statusCode: number;

  constructor(statusCode: number, message: string) {
    super(message);
    this.name = "HttpError";
    this.statusCode = statusCode;
  }
}

let storageInstance: StorageClient | null = null;
async function getStorageClient(): Promise<StorageClient> {
  if (!storageInstance) {
    const { Storage } = await import("@google-cloud/storage");
    storageInstance = new Storage();
  }
  return storageInstance;
}

let bundleLocationPromise: Promise<string> | null = null;
async function getBundleLocation(): Promise<string> {
  if (!bundleLocationPromise) {
    bundleLocationPromise = bundle({
      entryPoint: path.resolve(__dirname, "remotionRoot.js"),
    }).catch((err) => {
      bundleLocationPromise = null;
      throw err;
    });
  }
  return bundleLocationPromise;
}

export function resolvePort(): number {
  const raw = process.env.REMOTION_WORKER_PORT?.trim() || process.env.PORT?.trim();
  if (!raw) return 8444;
  const parsed = Number(raw);
  if (!Number.isInteger(parsed) || parsed < 0 || parsed > 65535) {
    throw new Error(`Invalid port "${raw}": expected an integer between 0 and 65535`);
  }
  return parsed;
}

export function resolveHost(): string {
  return process.env.REMOTION_WORKER_HOST?.trim() || process.env.HOST?.trim() || "127.0.0.1";
}

function resolveRemotionInternalPort(): number | undefined {
  const raw = process.env.REMOTION_INTERNAL_PORT?.trim();
  if (!raw) return undefined;
  const parsed = Number(raw);
  if (!Number.isInteger(parsed) || parsed < 1 || parsed > 65535) {
    throw new Error(`Invalid REMOTION_INTERNAL_PORT "${raw}": expected an integer between 1 and 65535`);
  }
  return parsed;
}

async function bindEphemeralIpv4Port(): Promise<number> {
  return new Promise<number>((resolve, reject) => {
    const probe = net.createServer();
    probe.unref();
    probe.once("error", (err) => {
      reject(err);
    });
    probe.listen({ port: 0, host: "127.0.0.1" }, () => {
      const addr = probe.address();
      const allocated = addr && typeof addr === "object" ? addr.port : 0;
      probe.close((closeErr) => {
        if (closeErr) {
          reject(closeErr);
          return;
        }
        if (!allocated || allocated <= 0) {
          reject(new Error("Failed to allocate non-zero ephemeral loopback port"));
          return;
        }
        resolve(allocated);
      });
    });
  });
}

async function isIpv6LoopbackPortAvailable(port: number): Promise<boolean> {
  return new Promise<boolean>((resolve) => {
    const probe = net.createServer();
    probe.unref();
    probe.once("error", (err: NodeJS.ErrnoException) => {
      if (err.code === "EAFNOSUPPORT" || err.code === "EADDRNOTAVAIL" || err.code === "EINVAL") {
        resolve(true);
        return;
      }
      resolve(false);
    });
    probe.listen({ port, host: "::1" }, () => {
      probe.close(() => resolve(true));
    });
  });
}

export async function allocateEphemeralLoopbackPort(): Promise<number> {
  const maxAttempts = 5;
  let lastPort = 0;
  for (let attempt = 0; attempt < maxAttempts; attempt++) {
    const port = await bindEphemeralIpv4Port();
    lastPort = port;
    if (await isIpv6LoopbackPortAvailable(port)) {
      return port;
    }
  }
  throw new Error(
    `Unable to allocate free loopback port on both 127.0.0.1 and ::1 after ${maxAttempts} attempts (last candidate: ${lastPort})`,
  );
}

const REMOTION_PORT_COLLISION_RE = /is not available|EADDRINUSE/i;

async function withRemotionPortRetry<T>(
  fixedPort: number | undefined,
  fn: (port: number) => Promise<T>,
): Promise<T> {
  if (fixedPort !== undefined) {
    return fn(fixedPort);
  }
  const maxAttempts = 3;
  let lastErr: unknown;
  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    const port = await allocateEphemeralLoopbackPort();
    try {
      return await fn(port);
    } catch (err: unknown) {
      lastErr = err;
      const msg = err instanceof Error ? err.message : String(err);
      if (attempt < maxAttempts && REMOTION_PORT_COLLISION_RE.test(msg)) {
        continue;
      }
      throw err;
    }
  }
  throw lastErr;
}

export function resolveBrowserExecutable(): string | undefined {
  const explicit = process.env.REMOTION_CHROME_EXECUTABLE?.trim();
  if (explicit) {
    if (!fsSync.existsSync(explicit)) {
      throw new Error(`REMOTION_CHROME_EXECUTABLE does not exist: ${explicit}`);
    }
    return explicit;
  }

  const puppeteerPath = process.env.PUPPETEER_EXECUTABLE_PATH?.trim();
  if (puppeteerPath && fsSync.existsSync(puppeteerPath)) {
    return puppeteerPath;
  }

  const candidates =
    process.platform === "darwin"
      ? [
          "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
          "/Applications/Chromium.app/Contents/MacOS/Chromium",
        ]
      : process.platform === "linux"
        ? ["/usr/bin/chromium", "/usr/bin/chromium-browser", "/usr/bin/google-chrome"]
        : [];

  for (const candidate of candidates) {
    if (fsSync.existsSync(candidate)) {
      return candidate;
    }
  }
  return undefined;
}

export function resolveFfmpegBin(): string {
  const explicit = process.env.FFMPEG_BIN?.trim();
  if (explicit) return explicit;
  for (const candidate of ["/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/usr/bin/ffmpeg"]) {
    if (fsSync.existsSync(candidate)) return candidate;
  }
  return "ffmpeg";
}

function resolveRenderTimeoutMs(): number {
  const raw = process.env.REMOTION_RENDER_TIMEOUT_MS?.trim();
  if (!raw) return DEFAULT_RENDER_TIMEOUT_MS;
  const parsed = Number(raw);
  if (Number.isFinite(parsed) && parsed > 0) {
    return Math.round(parsed);
  }
  return DEFAULT_RENDER_TIMEOUT_MS;
}

function validateHttpUrl(value: unknown, fieldName: string): string {
  if (typeof value !== "string" || !value.trim()) {
    throw new HttpError(400, `${fieldName} must be a non-empty http(s) URL`);
  }
  let parsed: URL;
  try {
    parsed = new URL(value);
  } catch {
    throw new HttpError(400, `${fieldName} is not a valid URL`);
  }
  if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
    throw new HttpError(400, `${fieldName} must use http:// or https:// protocol`);
  }
  return value;
}

function validateAlignedWords(rawWords: unknown): AlignedWord[] {
  if (!Array.isArray(rawWords)) {
    throw new HttpError(400, "inputProps.words must be an array");
  }
  return rawWords.map((item, idx) => {
    if (!item || typeof item !== "object") {
      throw new HttpError(400, `words[${idx}] must be an object`);
    }
    const w = item as Record<string, unknown>;
    if (typeof w.word !== "string" || !w.word.trim()) {
      throw new HttpError(400, `words[${idx}].word must be a non-empty string`);
    }
    if (typeof w.startMs !== "number" || !Number.isFinite(w.startMs) || w.startMs < 0) {
      throw new HttpError(400, `words[${idx}].startMs must be a finite number >= 0`);
    }
    if (typeof w.endMs !== "number" || !Number.isFinite(w.endMs) || w.endMs < w.startMs) {
      throw new HttpError(400, `words[${idx}].endMs must be a finite number >= startMs`);
    }
    return { word: w.word, startMs: w.startMs, endMs: w.endMs };
  });
}

function validateBeats(rawBeats: unknown): BeatSpec[] {
  if (rawBeats === undefined) return [];
  if (!Array.isArray(rawBeats)) {
    throw new HttpError(400, "inputProps.beats must be an array");
  }
  return rawBeats.map((item, idx) => {
    if (!item || typeof item !== "object") {
      throw new HttpError(400, `beats[${idx}] must be an object`);
    }
    const b = item as Record<string, unknown>;
    if (typeof b.ordinal !== "number" || !Number.isInteger(b.ordinal) || b.ordinal < 0) {
      throw new HttpError(400, `beats[${idx}].ordinal must be a non-negative integer`);
    }
    if (b.startMs !== undefined && (typeof b.startMs !== "number" || !Number.isFinite(b.startMs) || b.startMs < 0)) {
      throw new HttpError(400, `beats[${idx}].startMs must be a finite number >= 0`);
    }
    if (
      b.endMs !== undefined &&
      (typeof b.endMs !== "number" ||
        !Number.isFinite(b.endMs) ||
        b.endMs < (typeof b.startMs === "number" ? b.startMs : 0))
    ) {
      throw new HttpError(400, `beats[${idx}].endMs must be a finite number >= startMs`);
    }
    if (b.bRollUrl !== undefined && b.bRollUrl !== "") {
      validateHttpUrl(b.bRollUrl, `beats[${idx}].bRollUrl`);
    }
    if (
      b.bRollMediaType !== undefined &&
      b.bRollMediaType !== "image" &&
      b.bRollMediaType !== "video"
    ) {
      throw new HttpError(400, `beats[${idx}].bRollMediaType must be "image" or "video"`);
    }
    if (b.pipShrink !== undefined && typeof b.pipShrink !== "boolean") {
      throw new HttpError(400, `beats[${idx}].pipShrink must be a boolean`);
    }
    return {
      id: typeof b.id === "string" ? b.id : undefined,
      ordinal: b.ordinal,
      spokenText: typeof b.spokenText === "string" ? b.spokenText : undefined,
      anchorWord: typeof b.anchorWord === "string" ? b.anchorWord : undefined,
      startMs: b.startMs as number | undefined,
      endMs: b.endMs as number | undefined,
      depthTextTag: typeof b.depthTextTag === "string" ? b.depthTextTag : undefined,
      evidenceSource: typeof b.evidenceSource === "string" ? b.evidenceSource : undefined,
      bRollUrl: typeof b.bRollUrl === "string" && b.bRollUrl.trim() ? b.bRollUrl.trim() : undefined,
      bRollMediaType: b.bRollMediaType as "image" | "video" | undefined,
      pipShrink: b.pipShrink as boolean | undefined,
    };
  });
}

const VALID_BREAKING_STYLES = new Set<BreakingStyle>([
  "breaking",
  "developing",
  "exclusive",
  "alert_stat",
  "clean",
]);
const VALID_BROLL_FRAMINGS = new Set<BrollFraming>(["pip", "full", "split"]);
const HEX_ACCENT_RE = /^#[0-9A-Fa-f]{6}$/;

function validatePackaging(rawPackaging: unknown): PackagingSpec {
  if (rawPackaging === undefined) {
    return {
      chyronHeadline: "Aircheck",
      chyronSubline: "",
      cornerBugText: "LIVE",
      showBrandBug: true,
      breakingStyle: "breaking",
      brollFraming: "pip",
    };
  }
  if (!rawPackaging || typeof rawPackaging !== "object" || Array.isArray(rawPackaging)) {
    throw new HttpError(400, "inputProps.packaging must be an object");
  }
  const p = rawPackaging as Record<string, unknown>;
  for (const field of ["chyronHeadline", "chyronSubline", "cornerBugText"] as const) {
    if (p[field] !== undefined && typeof p[field] !== "string") {
      throw new HttpError(400, `inputProps.packaging.${field} must be a string`);
    }
  }
  if (p.showBrandBug !== undefined && typeof p.showBrandBug !== "boolean") {
    throw new HttpError(400, "inputProps.packaging.showBrandBug must be a boolean");
  }
  for (const field of ["showCaptions", "punchIn", "showLowerThird"] as const) {
    if (p[field] !== undefined && typeof p[field] !== "boolean") {
      throw new HttpError(400, `inputProps.packaging.${field} must be a boolean`);
    }
  }
  if (p.templateId !== undefined && p.templateId !== "Newsroom916" && p.templateId !== "Explainer169") {
    throw new HttpError(400, 'inputProps.packaging.templateId must be "Newsroom916" or "Explainer169"');
  }
  let breakingStyle: BreakingStyle = "breaking";
  if (p.breakingStyle !== undefined) {
    if (typeof p.breakingStyle !== "string" || !VALID_BREAKING_STYLES.has(p.breakingStyle as BreakingStyle)) {
      throw new HttpError(400, "inputProps.packaging.breakingStyle is invalid");
    }
    breakingStyle = p.breakingStyle as BreakingStyle;
  }
  let accentHex: string | undefined;
  if (p.accentHex !== undefined) {
    if (typeof p.accentHex !== "string" || !HEX_ACCENT_RE.test(p.accentHex.trim())) {
      throw new HttpError(400, "inputProps.packaging.accentHex must be #RRGGBB");
    }
    accentHex = p.accentHex.trim().toUpperCase();
  }
  let brollFraming: BrollFraming = "pip";
  if (p.brollFraming !== undefined) {
    if (typeof p.brollFraming !== "string" || !VALID_BROLL_FRAMINGS.has(p.brollFraming as BrollFraming)) {
      throw new HttpError(400, 'inputProps.packaging.brollFraming must be "pip", "full", or "split"');
    }
    brollFraming = p.brollFraming as BrollFraming;
  }
  let overlays: OverlayItemSpec[] | undefined;
  if (p.overlays !== undefined) {
    if (!Array.isArray(p.overlays)) {
      throw new HttpError(400, "inputProps.packaging.overlays must be an array");
    }
    overlays = p.overlays
      .filter((item): item is Record<string, unknown> => Boolean(item && typeof item === "object"))
      .map((item) => {
        const rawKind = typeof item.kind === "string" ? item.kind.toUpperCase() : "LOWER_THIRD";
        const kind: OverlayItemSpec["kind"] =
          rawKind === "STAT_CALLOUT" || rawKind === "QUOTE_BRIEF" ? rawKind : "LOWER_THIRD";
        const rawStyle = typeof item.breakingStyle === "string" ? item.breakingStyle : breakingStyle;
        const ovlStyle: BreakingStyle = VALID_BREAKING_STYLES.has(rawStyle as BreakingStyle)
          ? (rawStyle as BreakingStyle)
          : breakingStyle;
        return {
          kind,
          breakingStyle: ovlStyle,
          headline: typeof item.headline === "string" ? item.headline : "",
          subline: typeof item.subline === "string" ? item.subline : "",
          calloutStat: typeof item.calloutStat === "string" ? item.calloutStat : "",
          durationSec:
            typeof item.durationSec === "number" && Number.isFinite(item.durationSec)
              ? Math.max(2, Math.min(20, Math.round(item.durationSec)))
              : 5,
        };
      })
      .filter((o) => Boolean(o.headline.trim() || o.subline?.trim() || o.calloutStat?.trim()));
  }
  return {
    chyronHeadline: (p.chyronHeadline as string | undefined) ?? "Aircheck",
    chyronSubline: (p.chyronSubline as string | undefined) ?? "",
    cornerBugText: (p.cornerBugText as string | undefined) ?? "LIVE",
    showBrandBug: (p.showBrandBug as boolean | undefined) ?? true,
    breakingStyle,
    accentHex,
    brollFraming,
    overlays,
    templateId: p.templateId as "Newsroom916" | "Explainer169" | undefined,
    showCaptions: p.showCaptions as boolean | undefined,
    punchIn: p.punchIn as boolean | undefined,
    showLowerThird: p.showLowerThird as boolean | undefined,
  };
}

interface ValidatedRenderRequest {
  safeJobId: string;
  outputLocalPath?: string;
  bucketName?: string;
  outputGcsPath?: string;
  lipsyncUrl: string;
  voiceUrl: string;
  alignUrl?: string;
  inlineWords?: AlignedWord[];
  durationMs?: number;
  beats: BeatSpec[];
  packaging: PackagingSpec;
  compositionId: "Newsroom916" | "Explainer169";
}

function validateRenderRequest(raw: unknown): ValidatedRenderRequest {
  if (!raw || typeof raw !== "object" || Array.isArray(raw)) {
    throw new HttpError(400, "Request body must be a JSON object");
  }
  const body = raw as Record<string, unknown>;

  if (typeof body.jobId !== "string" || !body.jobId.trim()) {
    throw new HttpError(400, "jobId must be a non-empty string");
  }
  const safeJobId = body.jobId.replace(/[^a-zA-Z0-9_-]/g, "");
  if (!safeJobId) {
    throw new HttpError(400, "jobId must contain at least one alphanumeric, underscore, or hyphen character");
  }

  let outputLocalPath: string | undefined;
  if (body.outputLocalPath !== undefined) {
    if (
      typeof body.outputLocalPath !== "string" ||
      !body.outputLocalPath.trim() ||
      body.outputLocalPath.includes("\0") ||
      !path.isAbsolute(body.outputLocalPath) ||
      !body.outputLocalPath.toLowerCase().endsWith(".mp4")
    ) {
      throw new HttpError(400, "outputLocalPath must be a valid non-empty absolute .mp4 path");
    }
    outputLocalPath = body.outputLocalPath;
  }

  const bucketName = typeof body.bucketName === "string" && body.bucketName.trim() ? body.bucketName.trim() : undefined;
  const outputGcsPath =
    typeof body.outputGcsPath === "string" && body.outputGcsPath.trim() ? body.outputGcsPath.trim() : undefined;

  if ((body.bucketName !== undefined && !bucketName) || (body.outputGcsPath !== undefined && !outputGcsPath)) {
    throw new HttpError(400, "bucketName and outputGcsPath must be non-empty strings when provided");
  }
  if (Boolean(bucketName) !== Boolean(outputGcsPath)) {
    throw new HttpError(400, "bucketName and outputGcsPath must both be provided together");
  }
  if (!outputLocalPath && !(bucketName && outputGcsPath)) {
    throw new HttpError(400, "Either outputLocalPath or both bucketName and outputGcsPath must be provided");
  }

  if (!body.inputProps || typeof body.inputProps !== "object" || Array.isArray(body.inputProps)) {
    throw new HttpError(400, "inputProps must be an object");
  }
  const ip = body.inputProps as Record<string, unknown>;

  let compositionId: "Newsroom916" | "Explainer169" = "Newsroom916";
  if (ip.templateId !== undefined) {
    if (ip.templateId !== "Newsroom916" && ip.templateId !== "Explainer169") {
      throw new HttpError(400, 'inputProps.templateId must be "Newsroom916" or "Explainer169"');
    }
    compositionId = ip.templateId;
  }

  const lipsyncUrl = validateHttpUrl(ip.lipsyncUrl, "inputProps.lipsyncUrl");
  const voiceUrl = validateHttpUrl(ip.voiceUrl, "inputProps.voiceUrl");

  let alignUrl: string | undefined;
  if (ip.alignUrl !== undefined && ip.alignUrl !== "") {
    alignUrl = validateHttpUrl(ip.alignUrl, "inputProps.alignUrl");
  }

  let inlineWords: AlignedWord[] | undefined;
  if (ip.words !== undefined) {
    inlineWords = validateAlignedWords(ip.words);
  }

  let durationMs: number | undefined;
  if (ip.durationMs !== undefined) {
    if (typeof ip.durationMs !== "number" || !Number.isFinite(ip.durationMs) || ip.durationMs <= 0) {
      throw new HttpError(400, "inputProps.durationMs must be a finite positive number");
    }
    durationMs = ip.durationMs;
  }

  const hasUsableWords = Array.isArray(inlineWords) && (inlineWords.length > 0 || durationMs !== undefined);
  if (!hasUsableWords && !alignUrl) {
    throw new HttpError(400, "inputProps must provide either non-empty words or alignUrl");
  }

  const beats = validateBeats(ip.beats);
  const packaging = validatePackaging(ip.packaging);
  if (ip.templateId === undefined && packaging.templateId) {
    compositionId = packaging.templateId;
  }

  return {
    safeJobId,
    outputLocalPath,
    bucketName,
    outputGcsPath,
    lipsyncUrl,
    voiceUrl,
    alignUrl,
    inlineWords,
    durationMs,
    beats,
    packaging,
    compositionId,
  };
}

/**
 * Applies broadcast -16 LUFS loudnorm and relocates the `moov` atom to head (`-movflags +faststart`)
 * so Android Media3 ExoPlayer starts progressive HTTP byte-range playback in <150 ms.
 */
async function postProcessBroadcastMaster(rawMp4: string, faststartMp4: string): Promise<void> {
  const ffmpegBin = resolveFfmpegBin();
  try {
    await execFileAsync(
      ffmpegBin,
      [
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
        "-ar",
        "48000",
        "-movflags",
        "+faststart",
        faststartMp4,
      ],
      { timeout: FFMPEG_TIMEOUT_MS, maxBuffer: FFMPEG_MAX_BUFFER_BYTES },
    );
  } catch (err: unknown) {
    const e = err as { code?: unknown; signal?: unknown; stderr?: unknown; message?: string };
    const stderrStr = typeof e.stderr === "string" ? e.stderr.trim() : "";
    const stderrTail = stderrStr ? stderrStr.slice(-500) : "";
    const details = [
      e.code !== undefined ? `code=${String(e.code)}` : "",
      e.signal ? `signal=${String(e.signal)}` : "",
      stderrTail ? `stderr=${stderrTail}` : "",
    ]
      .filter(Boolean)
      .join(" ");
    throw new Error(`ffmpeg postProcessBroadcastMaster failed (${details || e.message || String(err)})`);
  }
}

async function drainRequestBody(
  req: http.IncomingMessage,
  maxBytes = MAX_OVERFLOW_DRAIN_BYTES,
  timeoutMs = OVERFLOW_DRAIN_TIMEOUT_MS,
): Promise<boolean> {
  if (req.readableEnded || req.complete) {
    return true;
  }
  if (req.destroyed) {
    return false;
  }
  return new Promise<boolean>((resolve) => {
    let drainedBytes = 0;
    let settled = false;
    let timer: NodeJS.Timeout | null = null;

    const finish = (completed: boolean) => {
      if (settled) return;
      settled = true;
      if (timer) {
        clearTimeout(timer);
        timer = null;
      }
      req.off("data", onData);
      req.off("end", onEnd);
      req.off("error", onDone);
      req.off("aborted", onDone);
      req.off("close", onDone);
      resolve(completed);
    };

    const onData = (chunk: Buffer | string) => {
      drainedBytes += Buffer.isBuffer(chunk) ? chunk.length : Buffer.byteLength(chunk);
      if (drainedBytes > maxBytes) {
        finish(false);
      }
    };
    const onEnd = () => finish(true);
    const onDone = () => finish(Boolean(req.readableEnded || req.complete));

    timer = setTimeout(() => finish(false), timeoutMs);
    timer.unref?.();

    req.on("data", onData);
    req.on("end", onEnd);
    req.on("error", onDone);
    req.on("aborted", onDone);
    req.on("close", onDone);
    req.resume();
  });
}

async function readJsonBody(req: http.IncomingMessage): Promise<unknown> {
  const contentLengthHeader = req.headers["content-length"];
  const declared = contentLengthHeader !== undefined ? Number(contentLengthHeader) : Number.NaN;
  const declaredOverflow = Number.isFinite(declared) && declared > MAX_BODY_BYTES;
  if (Number.isFinite(declared) && declared > MAX_OVERFLOW_DRAIN_BYTES) {
    throw new HttpError(413, `Request body exceeds ${MAX_BODY_BYTES} byte limit`);
  }

  const rawText = await new Promise<string>((resolve, reject) => {
    const chunks: Buffer[] = [];
    let totalBytes = 0;
    let tooLarge = declaredOverflow;
    let settled = false;
    let drainTimer: NodeJS.Timeout | null = null;

    const cleanup = () => {
      if (drainTimer) {
        clearTimeout(drainTimer);
        drainTimer = null;
      }
      req.off("data", onData);
      req.off("end", onEnd);
      req.off("error", onError);
      req.off("aborted", onAborted);
    };

    const fail413 = () => {
      if (settled) return;
      settled = true;
      cleanup();
      reject(new HttpError(413, `Request body exceeds ${MAX_BODY_BYTES} byte limit`));
    };

    const startDrainTimer = () => {
      if (!drainTimer) {
        drainTimer = setTimeout(fail413, OVERFLOW_DRAIN_TIMEOUT_MS);
        drainTimer.unref?.();
      }
    };

    if (tooLarge) {
      startDrainTimer();
    }

    const onData = (chunk: Buffer | string) => {
      const buf = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
      totalBytes += buf.length;
      if (!tooLarge && totalBytes > MAX_BODY_BYTES) {
        tooLarge = true;
        chunks.length = 0;
      }
      if (tooLarge) {
        if (totalBytes > MAX_OVERFLOW_DRAIN_BYTES) {
          fail413();
          return;
        }
        startDrainTimer();
        return;
      }
      chunks.push(buf);
    };

    const onEnd = () => {
      if (settled) return;
      if (tooLarge) {
        fail413();
        return;
      }
      settled = true;
      cleanup();
      resolve(Buffer.concat(chunks).toString("utf8"));
    };

    const onError = (err: Error) => {
      if (settled) return;
      settled = true;
      cleanup();
      reject(err);
    };

    const onAborted = () => {
      if (settled) return;
      settled = true;
      cleanup();
      reject(new HttpError(400, "Request aborted"));
    };

    req.on("data", onData);
    req.on("end", onEnd);
    req.on("error", onError);
    req.on("aborted", onAborted);
  });

  try {
    return JSON.parse(rawText);
  } catch {
    throw new HttpError(400, "Invalid JSON request body");
  }
}

async function resolveBeatMediaTypes(beats: BeatSpec[]): Promise<BeatSpec[]> {
  return Promise.all(
    beats.map(async (beat) => {
      if (!beat.bRollUrl || beat.bRollMediaType !== undefined) {
        return beat;
      }
      let pathname = "";
      try {
        pathname = new URL(beat.bRollUrl).pathname;
      } catch {
        return beat;
      }
      if (IMAGE_EXTENSION_RE.test(pathname) || VIDEO_EXTENSION_RE.test(pathname)) {
        return beat;
      }
      try {
        const headResp = await fetch(beat.bRollUrl, {
          method: "HEAD",
          signal: AbortSignal.timeout(B_ROLL_HEAD_TIMEOUT_MS),
        });
        if (headResp.ok) {
          const contentType = (headResp.headers.get("content-type") ?? "").toLowerCase().trim();
          if (contentType.startsWith("video/")) {
            return { ...beat, bRollMediaType: "video" as const };
          }
          if (contentType.startsWith("image/")) {
            return { ...beat, bRollMediaType: "image" as const };
          }
        }
      } catch {
        // Gracefully fall back if HEAD fails or times out
      }
      return beat;
    }),
  );
}

async function handleRenderJob(rawBody: unknown) {
  const req = validateRenderRequest(rawBody);
  const workDir = await fs.mkdtemp(path.join(os.tmpdir(), `aircheck-job-${req.safeJobId}-`));

  try {
    let words: AlignedWord[] = req.inlineWords ?? [];
    let alignDurationMs: number | undefined;

    if (words.length === 0 && req.alignUrl) {
      const alignResp = await fetch(req.alignUrl, {
        signal: AbortSignal.timeout(ALIGN_FETCH_TIMEOUT_MS),
      });
      if (!alignResp.ok) {
        throw new Error(`Failed to fetch alignUrl (HTTP ${alignResp.status})`);
      }
      const alignPayload = (await alignResp.json()) as unknown;
      if (!alignPayload || typeof alignPayload !== "object" || Array.isArray(alignPayload)) {
        throw new Error("alignUrl returned invalid JSON object payload");
      }
      const alignObj = alignPayload as { words?: unknown; durationMs?: unknown };
      words = validateAlignedWords(alignObj.words ?? []);
      if (
        typeof alignObj.durationMs === "number" &&
        Number.isFinite(alignObj.durationMs) &&
        alignObj.durationMs > 0
      ) {
        alignDurationMs = alignObj.durationMs;
      }
    }

    const resolvedBeats = await resolveBeatMediaTypes(req.beats);

    const timeline = compileTimeline({
      words,
      beats: resolvedBeats,
      fps: 30,
      coverSec: 0.5,
      durationMs: req.durationMs ?? alignDurationMs,
    });

    const serveUrl = await getBundleLocation();
    const browserExecutable = resolveBrowserExecutable();
    const envChromeMode = process.env.REMOTION_CHROME_MODE?.trim();
    const chromeMode: "chrome-for-testing" | "headless-shell" | undefined = browserExecutable
      ? envChromeMode === "headless-shell"
        ? "headless-shell"
        : "chrome-for-testing"
      : undefined;

    const inputProps = {
      lipsyncUrl: req.lipsyncUrl,
      voiceUrl: req.voiceUrl,
      timeline,
      packaging: req.packaging,
    };

    const fixedInternalPort = resolveRemotionInternalPort();
    const renderTimeoutMs = resolveRenderTimeoutMs();
    const composition = await withRemotionPortRetry(fixedInternalPort, (port) =>
      selectComposition({
        serveUrl,
        id: req.compositionId,
        inputProps,
        browserExecutable: browserExecutable ?? null,
        chromeMode,
        port,
        timeoutInMilliseconds: renderTimeoutMs,
      }),
    );

    const rawMp4 = path.join(workDir, "raw.mp4");
    const finalMp4 = path.join(workDir, "final_faststart.mp4");

    await withRemotionPortRetry(fixedInternalPort, (port) =>
      renderMedia({
        composition: { ...composition, durationInFrames: timeline.totalFrames },
        serveUrl,
        codec: "h264",
        outputLocation: rawMp4,
        inputProps,
        browserExecutable: browserExecutable ?? null,
        chromeMode,
        port,
        enforceAudioTrack: true,
        timeoutInMilliseconds: renderTimeoutMs,
        overwrite: true,
      }),
    );

    await postProcessBroadcastMaster(rawMp4, finalMp4);
    const finalBytes = await fs.readFile(finalMp4);
    const finalSha256 = crypto.createHash("sha256").update(finalBytes).digest("hex");

    if (req.outputLocalPath) {
      const destDir = path.dirname(req.outputLocalPath);
      await fs.mkdir(destDir, { recursive: true });
      const tmpDest = path.join(destDir, `.tmp-${req.safeJobId}-${crypto.randomUUID()}.mp4`);
      try {
        await fs.copyFile(finalMp4, tmpDest);
        await fs.rename(tmpDest, req.outputLocalPath);
      } catch (err) {
        await fs.rm(tmpDest, { force: true }).catch(() => {});
        throw err;
      }
    }

    let gcsUploaded = false;
    if (req.bucketName && req.outputGcsPath) {
      const storage = await getStorageClient();
      await storage.bucket(req.bucketName).file(req.outputGcsPath).save(finalBytes, {
        contentType: "video/mp4",
        metadata: {
          cacheControl: "public, max-age=31536000, immutable",
          metadata: { sha256: finalSha256 },
        },
      });
      gcsUploaded = true;
    }

    return {
      finalSha256,
      durationMs: Math.round((timeline.totalFrames / timeline.fps) * 1000),
      width: composition.width,
      height: composition.height,
      fps: composition.fps,
      ...(req.outputLocalPath ? { outputLocalPath: req.outputLocalPath } : {}),
      ...(gcsUploaded ? { bucketName: req.bucketName, outputGcsPath: req.outputGcsPath } : {}),
    };
  } finally {
    await fs.rm(workDir, { recursive: true, force: true });
  }
}

function extractRequestPathname(rawUrl: string | undefined): string {
  const urlStr = rawUrl ?? "/";
  if (urlStr.startsWith("//")) {
    const qIdx = urlStr.indexOf("?");
    const hIdx = urlStr.indexOf("#");
    const cut = qIdx >= 0 && hIdx >= 0 ? Math.min(qIdx, hIdx) : qIdx >= 0 ? qIdx : hIdx;
    return cut >= 0 ? urlStr.slice(0, cut) : urlStr;
  }
  return new URL(urlStr, "http://127.0.0.1").pathname;
}

function sendJson(
  res: http.ServerResponse,
  statusCode: number,
  body: unknown,
  options?: { closeConnection?: boolean; onFinished?: () => void },
): void {
  if (res.headersSent || res.writableEnded || res.destroyed) return;
  const headers: Record<string, string> = { "Content-Type": "application/json" };
  if (options?.closeConnection) {
    headers.Connection = "close";
  }
  res.writeHead(statusCode, headers);
  res.end(JSON.stringify(body), options?.onFinished);
}

function closeResponseSocketSafely(req: http.IncomingMessage, res: http.ServerResponse): void {
  const sock = res.socket;
  if (!sock || sock.destroyed) return;
  if (req.complete || req.readableEnded) {
    sock.end();
    return;
  }
  const cleanupListeners = () => {
    clearTimeout(forceTimer);
    req.off("end", onReqEnd);
    req.off("close", cleanupListeners);
  };
  const onReqEnd = () => {
    cleanupListeners();
    if (!sock.destroyed) {
      sock.end();
    }
  };
  const forceTimer = setTimeout(() => {
    cleanupListeners();
    if (!sock.destroyed) {
      sock.destroy();
    }
  }, 250);
  forceTimer.unref?.();
  req.once("end", onReqEnd);
  req.once("close", cleanupListeners);
}

async function sendEarlyError(
  req: http.IncomingMessage,
  res: http.ServerResponse,
  statusCode: number,
  message: string,
  forceClose = false,
): Promise<void> {
  const completed = await drainRequestBody(req);
  const shouldClose = forceClose || !completed;
  sendJson(
    res,
    statusCode,
    { error: message },
    shouldClose
      ? {
          closeConnection: true,
          onFinished: () => {
            closeResponseSocketSafely(req, res);
          },
        }
      : undefined,
  );
}

export function createServer() {
  const server = http.createServer(async (req, res) => {
    try {
      let pathname: string;
      try {
        pathname = extractRequestPathname(req.url);
      } catch {
        await sendEarlyError(req, res, 400, "Invalid request URL");
        return;
      }

      if (req.method === "GET" && pathname === "/healthz") {
        const addr = server.address() as net.AddressInfo | null;
        const actualPort = addr && typeof addr === "object" ? addr.port : resolvePort();
        sendJson(res, 200, {
          ok: true,
          remotionVersion: REMOTION_VERSION,
          port: actualPort,
        });
        return;
      }

      if (req.method !== "POST" || pathname !== "/render") {
        await sendEarlyError(req, res, 404, "Not found");
        return;
      }

      const expectedToken = process.env.REMOTION_WORKER_TOKEN;
      if (expectedToken && req.headers.authorization !== `Bearer ${expectedToken}`) {
        await sendEarlyError(req, res, 401, "Unauthorized");
        return;
      }

      const payload = await readJsonBody(req);
      const result = await handleRenderJob(payload);
      sendJson(res, 200, result);
    } catch (err: unknown) {
      if (err instanceof HttpError) {
        if (err.statusCode === 413) {
          req.resume();
          sendJson(
            res,
            413,
            { error: err.message },
            {
              closeConnection: true,
              onFinished: () => {
                closeResponseSocketSafely(req, res);
              },
            },
          );
          return;
        }
        sendJson(res, err.statusCode, { error: err.message });
        return;
      }
      const msg = err instanceof Error ? err.message : String(err);
      sendJson(res, 500, { error: msg });
    }
  });

  return server;
}

if (require.main === module) {
  const port = resolvePort();
  const host = resolveHost();
  const server = createServer();
  server.listen(port, host, () => {
    const addr = server.address() as net.AddressInfo | null;
    const boundPort = addr && typeof addr === "object" ? addr.port : port;
    console.log(`Aircheck Remotion worker listening on http://${host}:${boundPort}`);
  });
}
