import * as crypto from "node:crypto";
import { initializeApp } from "firebase-admin/app";
import { FieldValue, getFirestore } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";
import { getStorage } from "firebase-admin/storage";
import { onCall, HttpsError } from "firebase-functions/v2/https";
import { onTaskDispatched } from "firebase-functions/v2/tasks";
import { getFunctions } from "firebase-admin/functions";
import {
  BeatInput,
  PackagingConfig,
  ProviderRouting,
  computePronunciationDiff,
  computeStageHashes,
  extractWavPeaks64,
  sha256Hex,
} from "./stageHash";
import {
  alignVoiceWords,
  checkPhotoPoseCloud,
  renderNeuralTalkingHead,
  synthesizeClonedVoiceWav,
} from "./providers";

initializeApp();

const REGION = "us-central1";
const DB_ID = process.env.FIRESTORE_DATABASE_ID || "(default)";

function getDb() {
  return DB_ID === "(default)" ? getFirestore() : getFirestore(DB_ID);
}

function requireUid(auth?: { uid?: string }): string {
  if (!auth?.uid) {
    throw new HttpsError("unauthenticated", "Google Sign-In is required.");
  }
  return auth.uid;
}

async function gcsExists(path: string): Promise<boolean> {
  const [exists] = await getStorage().bucket().file(path).exists();
  return exists;
}

async function signedReadUrl(path: string): Promise<string> {
  const [url] = await getStorage()
    .bucket()
    .file(path)
    .getSignedUrl({
      version: "v4",
      action: "read",
      expires: Date.now() + 60 * 60 * 1000,
    });
  return url;
}

/**
 * Stage 0 · URL / Long-Form Ingest + Highlight Scorer (`LibreClip` / `AutoClip` contract).
 */
export const stage0Ingest = onCall(
  { region: REGION, enforceAppCheck: true, secrets: ["GEMINI_API_KEY"] },
  async (request) => {
    requireUid(request.auth);
    const sourceUrl = String(request.data?.sourceUrl ?? "").trim();
    const targetSeconds = Number(request.data?.targetSeconds ?? 30);
    if (!sourceUrl.startsWith("http://") && !sourceUrl.startsWith("https://")) {
      throw new HttpsError("invalid-argument", "sourceUrl must be a valid http(s) URL.");
    }

    const pageResp = await fetch(sourceUrl, { headers: { "User-Agent": "AircheckBot/2.0" } });
    if (!pageResp.ok) {
      throw new HttpsError("unavailable", `Unable to fetch sourceUrl (HTTP ${pageResp.status}).`);
    }
    const html = (await pageResp.text()).replace(/<script[\s\S]*?<\/script>/gi, " ").replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").slice(0, 12000);

    const apiKey = process.env.GEMINI_API_KEY;
    if (!apiKey) {
      throw new HttpsError("failed-precondition", "GEMINI_API_KEY secret missing.");
    }
    const llmResp = await fetch(
      `https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=${encodeURIComponent(apiKey)}`,
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          contents: [
            {
              parts: [
                {
                  text: `Extract the sharpest broadcast claims from this source (${sourceUrl}) into a tight ${targetSeconds}-second talking-head beat sheet JSON: {"title":string,"chyronHeadline":string,"chyronSubline":string,"beats":[{"spokenText":string,"durationSec":number,"anchorWord":string,"depthTextTag":string,"evidenceSource":string,"bRollPrompt":string}]}.\n\nSource text:\n${html}`,
                },
              ],
            },
          ],
          generationConfig: { responseMimeType: "application/json" },
        }),
      }
    );
    if (!llmResp.ok) {
      throw new HttpsError("unavailable", `Stage 0 highlight scoring failed (HTTP ${llmResp.status}).`);
    }
    const body = (await llmResp.json()) as {
      candidates?: Array<{ content?: { parts?: Array<{ text?: string }> } }>;
    };
    const raw = body.candidates?.[0]?.content?.parts?.[0]?.text;
    if (!raw) {
      throw new HttpsError("internal", "Stage 0 returned empty script JSON.");
    }
    return JSON.parse(raw);
  }
);

/**
 * Cloud <200 ms Face Pose & Occlusion Gate on deduplicated CAS photo.
 */
export const validatePhotoPose = onCall(
  { region: REGION, enforceAppCheck: true, secrets: ["GEMINI_API_KEY"] },
  async (request) => {
    const uid = requireUid(request.auth);
    const photoGcsPath = String(request.data?.photoGcsPath ?? "");
    const headModelId = String(request.data?.headModelId ?? "");
    if (!photoGcsPath.startsWith(`users/${uid}/cas/`)) {
      throw new HttpsError("permission-denied", "Invalid CAS photo path.");
    }
    const [bytes] = await getStorage().bucket().file(photoGcsPath).download();
    return checkPhotoPoseCloud(bytes.toString("base64"), headModelId);
  }
);

/**
 * Optional 2s Identity-Locked Eye-Level Pose Normalizer (`Flux Kontext` + user's `head_model_id` LoRA).
 */
export const normalizePhotoPose = onCall(
  { region: REGION, enforceAppCheck: true, secrets: ["POSE_NORMALIZER_ENDPOINT", "POSE_NORMALIZER_API_KEY"] },
  async (request) => {
    const uid = requireUid(request.auth);
    const photoGcsPath = String(request.data?.photoGcsPath ?? "");
    const headModelId = String(request.data?.headModelId ?? "").trim();
    const backdropPrompt = String(request.data?.backdropPrompt ?? "");
    if (!photoGcsPath.startsWith(`users/${uid}/cas/`)) {
      throw new HttpsError("permission-denied", "Invalid CAS photo path.");
    }
    if (!headModelId) {
      throw new HttpsError("failed-precondition", "headModelId is required for identity-locked pose normalization.");
    }
    const endpoint = process.env.POSE_NORMALIZER_ENDPOINT;
    const apiKey = process.env.POSE_NORMALIZER_API_KEY;
    if (!endpoint || !apiKey) {
      throw new HttpsError("failed-precondition", "POSE_NORMALIZER_ENDPOINT / API_KEY not configured.");
    }
    const photoUrl = await signedReadUrl(photoGcsPath);
    const resp = await fetch(endpoint, {
      method: "POST",
      headers: { Authorization: `Bearer ${apiKey}`, "Content-Type": "application/json" },
      body: JSON.stringify({ image_url: photoUrl, head_model_id: headModelId, prompt: backdropPrompt }),
    });
    if (!resp.ok) {
      throw new HttpsError("unavailable", `Pose normalizer failed (HTTP ${resp.status}).`);
    }
    const out = (await resp.json()) as { image_url?: string };
    if (!out.image_url) {
      throw new HttpsError("internal", "Pose normalizer returned no image_url.");
    }
    return { normalizedUrl: out.image_url };
  }
);

/**
 * Fast (<120 ms) Render Enqueue Entrypoint:
 * Verifies enrollment, computes SHA-256 stage hashes (checkpoint skip), writes Firestore job doc,
 * and dispatches durable Cloud Task DAG (`stage2VoiceTask` -> `stage3AlignTask` -> `stage4HeadRenderTask` -> `stage5PackageTask`).
 */
export const enqueueClipRender = onCall(
  { region: REGION, enforceAppCheck: true },
  async (request) => {
    const uid = requireUid(request.auth);
    const scriptId = String(request.data?.scriptId ?? "").trim();
    const title = String(request.data?.title ?? "Untitled Aircheck").trim();
    const photoSha = String(request.data?.photoSha ?? "").trim();
    const photoGcsPath = String(request.data?.photoGcsPath ?? "").trim();
    const headModelId = String(request.data?.headModelId ?? "").trim();
    const voiceSource = String(request.data?.voiceSource ?? "ENROLLED_VOICE_CLONE");
    const voiceId = String(request.data?.voiceId ?? "").trim();
    const micVoiceSha = request.data?.micVoiceSha ? String(request.data.micVoiceSha) : null;
    const micVoiceGcsPath = request.data?.micVoiceGcsPath ? String(request.data.micVoiceGcsPath) : null;
    const stage4Mode = String(request.data?.stage4Mode ?? "photo_head_lora");
    const sourceUrl = String(request.data?.sourceUrl ?? "");
    const beats = (request.data?.beats ?? []) as BeatInput[];
    const packaging = (request.data?.packaging ?? {}) as PackagingConfig;
    const providers = (request.data?.providers ?? {}) as ProviderRouting;

    if (!scriptId || beats.length === 0) {
      throw new HttpsError("invalid-argument", "scriptId and non-empty beats are required.");
    }
    if (voiceSource === "ENROLLED_VOICE_CLONE" && !voiceId) {
      throw new HttpsError("failed-precondition", "voice_id is not enrolled. Refusing silent voice fallback.");
    }
    if (stage4Mode === "photo_head_lora" && (!headModelId || !photoGcsPath.startsWith(`users/${uid}/cas/`))) {
      throw new HttpsError("failed-precondition", "head_model_id and uploaded CAS photo are required.");
    }

    const fullSpokenText = beats.map((b) => b.spokenText.trim()).filter(Boolean).join(" ");
    const hashes = computeStageHashes({
      fullSpokenText,
      voiceSource,
      voiceId,
      micVoiceSha,
      photoSha,
      headModelId,
      stage4Mode,
      beats,
      packaging,
      providers,
    });

    const jobId = `job_${crypto.randomUUID().replace(/-/g, "").slice(0, 12)}`;
    const clipId = `clip_${hashes.finalSha8}_${jobId.slice(4, 10)}`;

    const jobRef = getDb().collection("users").doc(uid).collection("jobs").doc(jobId);
    await jobRef.set({
      userId: uid,
      clipId,
      scriptId,
      title,
      fullSpokenText,
      photoSha,
      photoGcsPath,
      headModelId,
      voiceSource,
      voiceId,
      micVoiceSha,
      micVoiceGcsPath,
      stage4Mode,
      sourceUrl,
      beats,
      packaging,
      providers,
      hashes,
      chyronHeadline: packaging.chyronHeadline ?? title,
      chyronSubline: packaging.chyronSubline ?? "",
      status: "QUEUED",
      progressFraction: 0.05,
      stageDetail: "Queued on Cloud Tasks",
      createdAt: FieldValue.serverTimestamp(),
      updatedAt: FieldValue.serverTimestamp(),
    });

    const queue = getFunctions().taskQueue(`locations/${REGION}/functions/stage2VoiceTask`);
    await queue.enqueue({ uid, jobId }, { dispatchDeadlineSeconds: 300 });

    return { jobId, clipId, hashes };
  }
);

export const stage2VoiceTask = onTaskDispatched(
  {
    region: REGION,
    retryConfig: { maxAttempts: 3, minBackoffSeconds: 5 },
    secrets: ["ELEVENLABS_API_KEY", "FISH_AUDIO_API_KEY", "MINIMAX_API_KEY", "MINIMAX_GROUP_ID"],
  },
  async (req) => {
    const { uid, jobId } = req.data as { uid: string; jobId: string };
    const jobRef = getDb().collection("users").doc(uid).collection("jobs").doc(jobId);
    const snap = await jobRef.get();
    if (!snap.exists) return;
    const job = snap.data()!;

    try {
      await jobRef.update({
        status: "VOICE",
        progressFraction: 0.2,
        stageDetail: "Synthesizing enrolled voice track",
        updatedAt: FieldValue.serverTimestamp(),
      });

      const voiceArtifactPath = `users/${uid}/cas/voice/voice_${job.hashes.voiceSha8}.wav`;
      let wavBuffer: Buffer;

      if (job.voiceSource === "MIC_VOICEOVER" && job.micVoiceGcsPath) {
        [wavBuffer] = await getStorage().bucket().file(job.micVoiceGcsPath).download();
        await getStorage().bucket().file(voiceArtifactPath).save(wavBuffer, { contentType: "audio/wav" });
      } else if (await gcsExists(voiceArtifactPath)) {
        // $0.00 checkpoint hit: identical voice + provider + text SHA-256 already exists
        [wavBuffer] = await getStorage().bucket().file(voiceArtifactPath).download();
      } else {
        wavBuffer = await synthesizeClonedVoiceWav({
          text: job.fullSpokenText,
          voiceId: job.voiceId,
          providers: job.providers,
        });
        await getStorage().bucket().file(voiceArtifactPath).save(wavBuffer, { contentType: "audio/wav" });
      }

      const fullVoiceSha = sha256Hex(wavBuffer);
      const peaks64 = extractWavPeaks64(wavBuffer, 64);

      await jobRef.update({
        voiceSha: fullVoiceSha,
        voiceGcsPath: voiceArtifactPath,
        waveformPeaks: peaks64,
        progressFraction: 0.35,
        stageDetail: "Voice ready · queuing alignment",
        updatedAt: FieldValue.serverTimestamp(),
      });

      const nextQueue = getFunctions().taskQueue(`locations/${REGION}/functions/stage3AlignTask`);
      await nextQueue.enqueue({ uid, jobId }, { dispatchDeadlineSeconds: 180 });
    } catch (err: unknown) {
      await failJob(uid, jobId, err);
      throw err;
    }
  }
);

export const stage3AlignTask = onTaskDispatched(
  {
    region: REGION,
    retryConfig: { maxAttempts: 3, minBackoffSeconds: 5 },
    secrets: ["GROQ_API_KEY"],
  },
  async (req) => {
    const { uid, jobId } = req.data as { uid: string; jobId: string };
    const jobRef = getDb().collection("users").doc(uid).collection("jobs").doc(jobId);
    const snap = await jobRef.get();
    if (!snap.exists) return;
    const job = snap.data()!;

    try {
      await jobRef.update({
        status: "ALIGN",
        progressFraction: 0.45,
        stageDetail: "Word-level alignment & pronunciation QC",
        updatedAt: FieldValue.serverTimestamp(),
      });

      const alignPath = `users/${uid}/cas/align/align_${job.hashes.alignSha8}.json`;
      let alignment: { words: Array<{ word: string; startMs: number; endMs: number }>; durationMs: number };

      if (await gcsExists(alignPath)) {
        const [raw] = await getStorage().bucket().file(alignPath).download();
        alignment = JSON.parse(raw.toString("utf8"));
      } else {
        const [wavBuffer] = await getStorage().bucket().file(job.voiceGcsPath).download();
        alignment = await alignVoiceWords({ wavBuffer, providers: job.providers });
        await getStorage()
          .bucket()
          .file(alignPath)
          .save(Buffer.from(JSON.stringify(alignment), "utf8"), { contentType: "application/json" });
      }

      const qc = computePronunciationDiff(job.fullSpokenText, alignment.words);

      await jobRef.update({
        alignSha: job.hashes.alignSha8,
        alignGcsPath: alignPath,
        alignedWords: alignment.words,
        durationMs: alignment.durationMs,
        pronunciationWer: qc.wordErrorRate,
        pronunciationWarnings: qc.mismatchedWords,
        progressFraction: 0.55,
        stageDetail: "Alignment locked · queuing neural head render",
        updatedAt: FieldValue.serverTimestamp(),
      });

      const nextQueue = getFunctions().taskQueue(`locations/${REGION}/functions/stage4HeadRenderTask`);
      await nextQueue.enqueue({ uid, jobId }, { dispatchDeadlineSeconds: 900 });
    } catch (err: unknown) {
      await failJob(uid, jobId, err);
      throw err;
    }
  }
);

export const stage4HeadRenderTask = onTaskDispatched(
  {
    region: REGION,
    retryConfig: { maxAttempts: 2, minBackoffSeconds: 15 },
    secrets: ["STAGE4_GPU_ENDPOINT", "STAGE4_GPU_API_KEY"],
  },
  async (req) => {
    const { uid, jobId } = req.data as { uid: string; jobId: string };
    const jobRef = getDb().collection("users").doc(uid).collection("jobs").doc(jobId);
    const snap = await jobRef.get();
    if (!snap.exists) return;
    const job = snap.data()!;

    try {
      await jobRef.update({
        status: "LIPSYNC",
        progressFraction: 0.65,
        stageDetail: `Rendering talking head (${job.stage4Mode})`,
        updatedAt: FieldValue.serverTimestamp(),
      });

      const lipsyncPath = `users/${uid}/cas/lipsync/lipsync_${job.hashes.lipsyncSha8}.mp4`;
      let lipsyncSha: string;

      if (await gcsExists(lipsyncPath)) {
        // $0.00 checkpoint hit when only packaging/chyrons changed!
        lipsyncSha = job.hashes.lipsyncSha8;
      } else {
        const photoUrl = await signedReadUrl(job.photoGcsPath);
        const voiceUrl = await signedReadUrl(job.voiceGcsPath);
        const mp4Bytes = await renderNeuralTalkingHead({
          stage4Mode: job.stage4Mode,
          photoUrl,
          voiceUrl,
          headModelId: job.headModelId,
          sourceUrl: job.sourceUrl,
          providers: job.providers,
        });
        lipsyncSha = sha256Hex(mp4Bytes);
        await getStorage().bucket().file(lipsyncPath).save(mp4Bytes, { contentType: "video/mp4" });
      }

      const lipsyncUrl = await signedReadUrl(lipsyncPath);

      await jobRef.update({
        lipsyncSha,
        lipsyncGcsPath: lipsyncPath,
        remoteLipsyncUrl: lipsyncUrl,
        progressFraction: 0.82,
        stageDetail: "Head render ready · packaging in Remotion",
        updatedAt: FieldValue.serverTimestamp(),
      });

      const nextQueue = getFunctions().taskQueue(`locations/${REGION}/functions/stage5PackageTask`);
      await nextQueue.enqueue({ uid, jobId }, { dispatchDeadlineSeconds: 600 });
    } catch (err: unknown) {
      await failJob(uid, jobId, err);
      throw err;
    }
  }
);

export const stage5PackageTask = onTaskDispatched(
  {
    region: REGION,
    retryConfig: { maxAttempts: 3, minBackoffSeconds: 10 },
    secrets: ["REMOTION_WORKER_URL", "REMOTION_WORKER_TOKEN"],
  },
  async (req) => {
    const { uid, jobId } = req.data as { uid: string; jobId: string };
    const jobRef = getDb().collection("users").doc(uid).collection("jobs").doc(jobId);
    const snap = await jobRef.get();
    if (!snap.exists) return;
    const job = snap.data()!;

    try {
      await jobRef.update({
        status: "PACKAGING",
        progressFraction: 0.9,
        stageDetail: "Burning captions, punch-ins & -16 LUFS master",
        updatedAt: FieldValue.serverTimestamp(),
      });

      const finalPath = `users/${uid}/cas/final/final_${job.hashes.finalSha8}.mp4`;
      const workerUrl = job.providers?.useLanPackage
        ? `${String(job.providers.lanGatewayUrl).replace(/\/$/, "")}/render`
        : process.env.REMOTION_WORKER_URL;
      if (!workerUrl) {
        throw new HttpsError("failed-precondition", "REMOTION_WORKER_URL is not configured.");
      }

      const [lipsyncUrl, voiceUrl, alignUrl] = await Promise.all([
        signedReadUrl(job.lipsyncGcsPath),
        signedReadUrl(job.voiceGcsPath),
        signedReadUrl(job.alignGcsPath),
      ]);

      const resp = await fetch(workerUrl, {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          ...(process.env.REMOTION_WORKER_TOKEN
            ? { Authorization: `Bearer ${process.env.REMOTION_WORKER_TOKEN}` }
            : {}),
        },
        body: JSON.stringify({
          jobId,
          outputGcsPath: finalPath,
          bucketName: getStorage().bucket().name,
          inputProps: {
            lipsyncUrl,
            voiceUrl,
            alignUrl,
            beats: job.beats,
            packaging: job.packaging,
            templateId: job.packaging?.templateId ?? "Newsroom916",
          },
        }),
      });
      if (!resp.ok) {
        throw new HttpsError("unavailable", `Stateless Remotion worker failed (HTTP ${resp.status}).`);
      }

      const result = (await resp.json()) as {
        finalSha256: string;
        thumbHash?: string;
        durationMs?: number;
      };
      const remoteFinalUrl = await signedReadUrl(finalPath);

      await jobRef.update({
        status: "READY",
        progressFraction: 1.0,
        stageDetail: "Broadcast master ready",
        finalSha: result.finalSha256,
        finalGcsPath: finalPath,
        remoteFinalUrl,
        thumbHash: result.thumbHash ?? null,
        durationMs: result.durationMs ?? job.durationMs ?? 0,
        updatedAt: FieldValue.serverTimestamp(),
      });

      // Dispatch high-priority FCM push so the phone wakes and caches even when locked
      const personaSnap = await getDb()
        .collection("users")
        .doc(uid)
        .collection("profile")
        .doc("persona")
        .get();
      const fcmToken = personaSnap.data()?.fcmToken as string | undefined;
      if (fcmToken) {
        await getMessaging().send({
          token: fcmToken,
          android: { priority: "high" },
          data: {
            event: "CLIP_READY",
            jobId,
            clipId: String(job.clipId),
            scriptId: String(job.scriptId),
            title: String(job.title),
            status: "READY",
            progressFraction: "1.0",
            finalSha: result.finalSha256,
            remoteFinalUrl,
            thumbHash: result.thumbHash ?? "",
            durationMs: String(result.durationMs ?? job.durationMs ?? 0),
          },
        });
      }
    } catch (err: unknown) {
      await failJob(uid, jobId, err);
      throw err;
    }
  }
);

/**
 * Phase A Heavy Upfront Cloud Persona Training trigger (Cloud Run GPU Job, 20-45 min).
 */
export const startPersonaTraining = onCall(
  { region: REGION, enforceAppCheck: true, secrets: ["PERSONA_TRAINER_JOB_URL", "PERSONA_TRAINER_TOKEN"] },
  async (request) => {
    const uid = requireUid(request.auth);
    const trainingVideoGcsUris = (request.data?.trainingVideoGcsUris ?? []) as string[];
    const voiceProviderId = String(request.data?.voiceProviderId ?? "elevenlabs_pvc");

    if (!Array.isArray(trainingVideoGcsUris) || trainingVideoGcsUris.length === 0) {
      throw new HttpsError("invalid-argument", "At least one gs:// reference video URI is required.");
    }

    const trainerUrl = process.env.PERSONA_TRAINER_JOB_URL;
    if (!trainerUrl) {
      throw new HttpsError("failed-precondition", "PERSONA_TRAINER_JOB_URL is not configured.");
    }

    const trainingJobId = `train_${crypto.randomUUID().replace(/-/g, "").slice(0, 10)}`;
    const personaRef = getDb().collection("users").doc(uid).collection("profile").doc("persona");
    await personaRef.set(
      {
        trainingJobId,
        trainingStatus: "RUNNING",
        trainingError: "",
        updatedAt: FieldValue.serverTimestamp(),
      },
      { merge: true }
    );

    const resp = await fetch(trainerUrl, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        ...(process.env.PERSONA_TRAINER_TOKEN
          ? { Authorization: `Bearer ${process.env.PERSONA_TRAINER_TOKEN}` }
          : {}),
      },
      body: JSON.stringify({ uid, trainingJobId, trainingVideoGcsUris, voiceProviderId }),
    });
    if (!resp.ok) {
      await personaRef.set(
        { trainingStatus: "FAILED", trainingError: `Trainer HTTP ${resp.status}` },
        { merge: true }
      );
      throw new HttpsError("unavailable", `Persona trainer launch failed (HTTP ${resp.status}).`);
    }
    return { trainingJobId };
  }
);

async function failJob(uid: string, jobId: string, err: unknown) {
  const message = err instanceof Error ? err.message : String(err);
  await getDb()
    .collection("users")
    .doc(uid)
    .collection("jobs")
    .doc(jobId)
    .set(
      {
        status: "FAILED",
        stageDetail: "Failed",
        errorMessage: message.slice(0, 400),
        updatedAt: FieldValue.serverTimestamp(),
      },
      { merge: true }
    );
}
