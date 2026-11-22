import { HttpsError } from "firebase-functions/v2/https";
import { ProviderRouting } from "./stageHash";

export interface AlignedWord {
  word: string;
  startMs: number;
  endMs: number;
}

export interface PoseGateOutcome {
  valid: boolean;
  pitchDeg: number;
  yawDeg: number;
  mouthOccluded: boolean;
  canNormalize: boolean;
  note: string;
}

function requireSecret(name: string): string {
  const v = process.env[name]?.trim();
  if (!v) {
    throw new HttpsError(
      "failed-precondition",
      `Server secret ${name} is not configured in Secret Manager. Refusing silent fallback.`
    );
  }
  return v;
}

/**
 * Stage 2 · Single-Pass Persistent `voice_id` Synthesis.
 * Supports ElevenLabs PVC, Fish Audio S2, MiniMax `voice_id`, or explicit LAN GPU Chatterbox/RVC.
 * Zero zero-shot base64 WAV stuffing and zero silent fallback.
 */
export async function synthesizeClonedVoiceWav(params: {
  text: string;
  voiceId: string;
  providers: ProviderRouting;
}): Promise<Buffer> {
  if (!params.voiceId.trim()) {
    throw new HttpsError("failed-precondition", "No enrolled voice_id provided.");
  }

  if (params.providers.useLanVoice) {
    const baseUrl = params.providers.lanGatewayUrl?.trim();
    if (!baseUrl) {
      throw new HttpsError("failed-precondition", "LAN Voice enabled but lanGatewayUrl is empty.");
    }
    const resp = await fetch(`${baseUrl.replace(/\/$/, "")}/v1/stage2/voice`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ voice_id: params.voiceId, text: params.text }),
    });
    if (!resp.ok) {
      throw new HttpsError("unavailable", `LAN Voice provider failed (HTTP ${resp.status}).`);
    }
    return Buffer.from(await resp.arrayBuffer());
  }

  switch (params.providers.voiceProviderId) {
    case "elevenlabs_pvc": {
      const apiKey = requireSecret("ELEVENLABS_API_KEY");
      const resp = await fetch(
        `https://api.elevenlabs.io/v1/text-to-speech/${encodeURIComponent(params.voiceId)}?output_format=pcm_44100`,
        {
          method: "POST",
          headers: {
            "xi-api-key": apiKey,
            "Content-Type": "application/json",
            Accept: "audio/wav",
          },
          body: JSON.stringify({
            text: params.text,
            model_id: "eleven_multilingual_v2",
            voice_settings: { stability: 0.45, similarity_boost: 0.85 },
          }),
        }
      );
      if (!resp.ok) {
        throw new HttpsError("unavailable", `ElevenLabs PVC synthesis failed (HTTP ${resp.status}).`);
      }
      const raw = Buffer.from(await resp.arrayBuffer());
      // Wrap raw PCM in standard 44.1 kHz 16-bit mono RIFF WAV if needed
      return raw.toString("ascii", 0, 4) === "RIFF" ? raw : wrapPcmAsWav(raw, 44100, 1, 16);
    }

    case "fish_s2": {
      const apiKey = requireSecret("FISH_AUDIO_API_KEY");
      const resp = await fetch("https://api.fish.audio/v1/tts", {
        method: "POST",
        headers: {
          Authorization: `Bearer ${apiKey}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          text: params.text,
          reference_id: params.voiceId,
          format: "wav",
          normalize: true,
        }),
      });
      if (!resp.ok) {
        throw new HttpsError("unavailable", `Fish Audio S2 synthesis failed (HTTP ${resp.status}).`);
      }
      return Buffer.from(await resp.arrayBuffer());
    }

    case "minimax": {
      const apiKey = requireSecret("MINIMAX_API_KEY");
      const groupId = requireSecret("MINIMAX_GROUP_ID");
      const resp = await fetch(`https://api.minimax.chat/v1/t2a_v2?GroupId=${encodeURIComponent(groupId)}`, {
        method: "POST",
        headers: {
          Authorization: `Bearer ${apiKey}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          model: "speech-02-hd",
          text: params.text,
          voice_setting: { voice_id: params.voiceId, speed: 1.0, vol: 1.0, pitch: 0 },
          audio_setting: { sample_rate: 44100, format: "wav", channel: 1 },
        }),
      });
      if (!resp.ok) {
        throw new HttpsError("unavailable", `MiniMax synthesis failed (HTTP ${resp.status}).`);
      }
      const json = (await resp.json()) as { data?: { audio?: string } };
      if (!json.data?.audio) {
        throw new HttpsError("internal", "MiniMax returned no audio hex payload.");
      }
      return Buffer.from(json.data.audio, "hex");
    }

    default:
      throw new HttpsError(
        "invalid-argument",
        `Unknown Stage 2 voiceProviderId: ${params.providers.voiceProviderId}`
      );
  }
}

/**
 * Stage 3 · Word-Level Forced Alignment via Groq `whisper-large-v3-turbo` (0.4s) or explicit LAN WhisperX.
 */
export async function alignVoiceWords(params: {
  wavBuffer: Buffer;
  providers: ProviderRouting;
}): Promise<{ words: AlignedWord[]; durationMs: number }> {
  if (params.providers.useLanAlign) {
    const baseUrl = params.providers.lanGatewayUrl?.trim();
    if (!baseUrl) {
      throw new HttpsError("failed-precondition", "LAN Alignment enabled but lanGatewayUrl is empty.");
    }
    const form = new FormData();
    form.append("file", new Blob([new Uint8Array(params.wavBuffer)], { type: "audio/wav" }), "voice.wav");
    const resp = await fetch(`${baseUrl.replace(/\/$/, "")}/v1/stage3/align`, { method: "POST", body: form });
    if (!resp.ok) {
      throw new HttpsError("unavailable", `LAN WhisperX alignment failed (HTTP ${resp.status}).`);
    }
    return (await resp.json()) as { words: AlignedWord[]; durationMs: number };
  }

  const groqKey = requireSecret("GROQ_API_KEY");
  const form = new FormData();
  form.append("file", new Blob([new Uint8Array(params.wavBuffer)], { type: "audio/wav" }), "voice.wav");
  form.append("model", "whisper-large-v3-turbo");
  form.append("response_format", "verbose_json");
  form.append("timestamp_granularities[]", "word");
  form.append("timestamp_granularities[]", "segment");

  const resp = await fetch("https://api.groq.com/openai/v1/audio/transcriptions", {
    method: "POST",
    headers: { Authorization: `Bearer ${groqKey}` },
    body: form,
  });
  if (!resp.ok) {
    throw new HttpsError("unavailable", `Groq Whisper alignment failed (HTTP ${resp.status}).`);
  }
  const payload = (await resp.json()) as {
    duration?: number;
    words?: Array<{ word: string; start: number; end: number }>;
  };

  const words: AlignedWord[] = (payload.words ?? []).map((w) => ({
    word: w.word.trim(),
    startMs: Math.round(w.start * 1000),
    endMs: Math.round(w.end * 1000),
  }));
  const durationMs = Math.round((payload.duration ?? 0) * 1000) || (words.at(-1)?.endMs ?? 0);
  return { words, durationMs };
}

/**
 * Stage 4 · Photo-Driven Neural Head Render (`Wan 2.2 S2V` / `InfiniteTalk` + personal `head_model_id` LoRA),
 * or secondary fast paths (`LatentSync` saved loop / `LibreClip` 9:16 crop) sharing the exact same contract.
 */
export async function renderNeuralTalkingHead(params: {
  stage4Mode: string;
  photoUrl: string;
  voiceUrl: string;
  headModelId: string;
  sourceUrl?: string;
  providers: ProviderRouting;
}): Promise<Buffer> {
  if (params.providers.useLanHead) {
    const baseUrl = params.providers.lanGatewayUrl?.trim();
    if (!baseUrl) {
      throw new HttpsError("failed-precondition", "LAN Head Renderer enabled but lanGatewayUrl is empty.");
    }
    const resp = await fetch(`${baseUrl.replace(/\/$/, "")}/v1/stage4/render`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        stage4Mode: params.stage4Mode,
        photoUrl: params.photoUrl,
        voiceUrl: params.voiceUrl,
        headModelId: params.headModelId,
        sourceUrl: params.sourceUrl,
      }),
    });
    if (!resp.ok) {
      throw new HttpsError("unavailable", `LAN Stage 4 head renderer failed (HTTP ${resp.status}).`);
    }
    return Buffer.from(await resp.arrayBuffer());
  }

  // Dedicated / Serverless GPU endpoint (Modal / fal.ai / Cloud Run GPU / HeyGen Avatar IV)
  const gpuEndpoint = requireSecret("STAGE4_GPU_ENDPOINT");
  const gpuApiKey = requireSecret("STAGE4_GPU_API_KEY");

  const resp = await fetch(gpuEndpoint, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${gpuApiKey}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      provider: params.providers.headProviderId,
      mode: params.stage4Mode,
      image_url: params.photoUrl,
      audio_url: params.voiceUrl,
      head_model_id: params.headModelId,
      source_url: params.sourceUrl ?? "",
    }),
  });
  if (!resp.ok) {
    throw new HttpsError("unavailable", `Stage 4 GPU head render failed (HTTP ${resp.status}).`);
  }

  const contentType = resp.headers.get("content-type") ?? "";
  if (contentType.includes("video/")) {
    return Buffer.from(await resp.arrayBuffer());
  }
  const json = (await resp.json()) as { video_url?: string; url?: string };
  const videoUrl = json.video_url ?? json.url;
  if (!videoUrl) {
    throw new HttpsError("internal", "Stage 4 GPU renderer returned no video_url.");
  }
  const vidResp = await fetch(videoUrl);
  if (!vidResp.ok) {
    throw new HttpsError("unavailable", `Failed to download rendered lipsync video (HTTP ${vidResp.status}).`);
  }
  return Buffer.from(await vidResp.arrayBuffer());
}

/**
 * Cloud <200 ms Face Pose & Occlusion Gate (zero ML Kit on the phone).
 */
export async function checkPhotoPoseCloud(photoBase64: string, headModelId: string): Promise<PoseGateOutcome> {
  const apiKey = requireSecret("GEMINI_API_KEY");
  const resp = await fetch(
    `https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=${encodeURIComponent(apiKey)}`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        contents: [
          {
            parts: [
              {
                inlineData: { mimeType: "image/jpeg", data: photoBase64 },
              },
              {
                text: 'Inspect this presenter portrait for talking-head lip-sync readiness. Return strict JSON: {"valid":boolean,"pitchDeg":number,"yawDeg":number,"mouthOccluded":boolean,"note":string}. Mark valid=false if pitch/yaw > 22 deg, sunglasses hide eyes, or hand/mic occludes mouth.',
              },
            ],
          },
        ],
        generationConfig: { responseMimeType: "application/json" },
      }),
    }
  );
  if (!resp.ok) {
    throw new HttpsError("unavailable", `Cloud Pose Gate failed (HTTP ${resp.status}).`);
  }
  const data = (await resp.json()) as {
    candidates?: Array<{ content?: { parts?: Array<{ text?: string }> } }>;
  };
  const raw = data.candidates?.[0]?.content?.parts?.[0]?.text ?? "{}";
  const parsed = JSON.parse(raw) as Partial<PoseGateOutcome>;
  return {
    valid: Boolean(parsed.valid),
    pitchDeg: Number(parsed.pitchDeg ?? 0),
    yawDeg: Number(parsed.yawDeg ?? 0),
    mouthOccluded: Boolean(parsed.mouthOccluded),
    canNormalize: Boolean(headModelId.trim()),
    note: parsed.note ?? (parsed.valid ? "Eye-level frontal pose verified." : "Pose needs normalization."),
  };
}

function wrapPcmAsWav(pcm: Buffer, sampleRate: number, channels: number, bitsPerSample: number): Buffer {
  const header = Buffer.alloc(44);
  const byteRate = (sampleRate * channels * bitsPerSample) / 8;
  const blockAlign = (channels * bitsPerSample) / 8;
  header.write("RIFF", 0, "ascii");
  header.writeUInt32LE(36 + pcm.length, 4);
  header.write("WAVE", 8, "ascii");
  header.write("fmt ", 12, "ascii");
  header.writeUInt32LE(16, 16);
  header.writeUInt16LE(1, 20);
  header.writeUInt16LE(channels, 22);
  header.writeUInt32LE(sampleRate, 24);
  header.writeUInt32LE(byteRate, 28);
  header.writeUInt16LE(blockAlign, 32);
  header.writeUInt16LE(bitsPerSample, 34);
  header.write("data", 36, "ascii");
  header.writeUInt32LE(pcm.length, 40);
  return Buffer.concat([header, pcm]);
}
