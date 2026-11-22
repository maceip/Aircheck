package com.example.data.remote

import android.graphics.Bitmap
import android.util.Log
import com.example.data.ScriptBeat
import com.example.data.estimateDurationSeconds
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.ImagePart
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

private const val TAG = "AircheckAiRepo"

data class HonedScriptResult(
    val title: String,
    val chyronHeadline: String,
    val chyronSubline: String,
    val beats: List<ScriptBeat>
)

data class FactCheckResult(
    val citationLabel: String,
    val note: String,
    val tightenedText: String?
)

class AircheckAiRepository {

    private val streamingTextModel by lazy {
        Firebase.ai.generativeModel(modelName = ModelRegistry.textModelId)
    }

    private val scriptSchema = Schema.obj(
        properties = mapOf(
            "title" to Schema.string(description = "Crisp headline in sentence case, under 65 chars"),
            "chyronHeadline" to Schema.string(description = "Broadcast lower-third headline, under 42 chars"),
            "chyronSubline" to Schema.string(description = "Broadcast lower-third subline, under 52 chars"),
            "beats" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "spokenText" to Schema.string(description = "Natural spoken sentence-case newsroom line"),
                        "durationSec" to Schema.integer(description = "Estimated spoken seconds, minimum 3"),
                        "mentionTag" to Schema.string(description = "Optional @handle or organization reference"),
                        "depthTextTag" to Schema.string(description = "Optional 1-2 word punchy on-screen keyword"),
                        "evidenceSource" to Schema.string(description = "Timely data point or concise verifiable source citation"),
                        "sfxTag" to Schema.string(description = "Optional subtle broadcast sound cue"),
                        "phoneticOverride" to Schema.string(description = "Optional pronunciation hint for acronyms"),
                        "bRollPrompt" to Schema.string(description = "Optional visual B-roll cutaway description")
                    ),
                    optionalProperties = listOf(
                        "mentionTag", "depthTextTag", "evidenceSource",
                        "sfxTag", "phoneticOverride", "bRollPrompt"
                    )
                ),
                description = "3 to 6 tightly timed spoken beats fitting the target runtime"
            )
        )
    )

    private val structuredScriptModel by lazy {
        Firebase.ai.generativeModel(
            modelName = ModelRegistry.textModelId,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = scriptSchema
            }
        )
    }

    private val shortenSchema = Schema.obj(
        properties = mapOf(
            "items" to Schema.array(
                items = Schema.obj(
                    properties = mapOf(
                        "beatId" to Schema.string(description = "Exact input beat ID"),
                        "shorterText" to Schema.string(description = "Tightened prose roughly 20% shorter while preserving facts and punch")
                    )
                )
            )
        )
    )

    private val shortenModel by lazy {
        Firebase.ai.generativeModel(
            modelName = ModelRegistry.textModelId,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = shortenSchema
            }
        )
    }

    private val factCheckSchema = Schema.obj(
        properties = mapOf(
            "citationLabel" to Schema.string(description = "Short inline source or data badge, under 32 chars"),
            "note" to Schema.string(description = "One-sentence factual verification note"),
            "refinedText" to Schema.string(description = "Fact-checked, sharpened sentence-case prose incorporating concrete numbers/facts")
        )
    )

    private val factCheckModel by lazy {
        Firebase.ai.generativeModel(
            modelName = ModelRegistry.textModelId,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = factCheckSchema
            }
        )
    }

    private val imageEditModel by lazy {
        Firebase.ai.generativeModel(
            modelName = ModelRegistry.imageModelId,
            generationConfig = generationConfig {
                responseModalities = listOf(ResponseModality.IMAGE)
            }
        )
    }

    /**
     * Phase 1 streams a live spoken preview directly into the writer's shimmering card,
     * then locks into structured timed beats with citations via Schema.obj.
     */
    suspend fun honeSparkToScript(
        spark: String,
        targetSeconds: Int,
        onStreamChunk: (String) -> Unit
    ): HonedScriptResult {
        val trimmedSpark = spark.trim()
        require(trimmedSpark.isNotEmpty()) { "Enter a spark or topic before honing." }

        val targetWords = (targetSeconds * 2.5f).toInt().coerceIn(20, 175)

        try {
            // 1. Stream immediate spoken angle into the shimmering card for low TTFT
            val streamPrompt = """
                You are an elite short-form newsroom editor.
                Turn this raw idea spark into a sharp, conversational, fact-backed spoken script (~$targetWords words, ~$targetSeconds seconds).
                Use natural sentence case. No markdown bullets, no stage directions, no hashtags.
                Spark: "$trimmedSpark"
            """.trimIndent()

            val streamedDraft = StringBuilder()
            withTimeout(45_000L) {
                streamingTextModel.generateContentStream(streamPrompt).collect { chunk ->
                    val piece = chunk.text.orEmpty()
                    if (piece.isNotEmpty()) {
                        streamedDraft.append(piece)
                        onStreamChunk(streamedDraft.toString().trim())
                    }
                }
            }

            // 2. Structure into timed beats with inline evidence & B-roll cues
            val structurePrompt = """
                Convert this spoken broadcast draft into a crisp beat-by-beat talking-head script targeting $targetSeconds seconds (~$targetWords words total).
                Rules:
                - Every beat spokenText must be clean, natural sentence case (never ALL CAPS).
                - Keep 3 to 5 tight beats; each beat minimum 3 seconds.
                - Include concise evidenceSource citations or concrete timely facts wherever claims appear.
                - Keep chyronHeadline punchy and under 40 characters.

                Original spark: "$trimmedSpark"
                Draft script: "${streamedDraft.toString().trim()}"
            """.trimIndent()

            val response = withTimeout(60_000L) {
                structuredScriptModel.generateContent(structurePrompt)
            }
            val rawJson = response.text ?: throw IllegalStateException("Model returned an empty script response.")
            val root = JSONObject(rawJson)
            val beatsArr = root.getJSONArray("beats")
            val parsedBeats = mutableListOf<ScriptBeat>()
            for (i in 0 until beatsArr.length()) {
                val obj = beatsArr.getJSONObject(i)
                val spoken = obj.optString("spokenText", "").trim()
                if (spoken.isNotBlank()) {
                    val est = obj.optInt("durationSec", estimateDurationSeconds(spoken)).coerceAtLeast(3)
                    parsedBeats.add(
                        ScriptBeat(
                            index = parsedBeats.size,
                            spokenText = spoken,
                            durationSec = est,
                            mentionTag = obj.optString("mentionTag", "").trim(),
                            depthTextTag = obj.optString("depthTextTag", "").trim(),
                            evidenceSource = obj.optString("evidenceSource", "").trim(),
                            sfxTag = obj.optString("sfxTag", "").trim(),
                            phoneticOverride = obj.optString("phoneticOverride", "").trim(),
                            bRollPrompt = obj.optString("bRollPrompt", "").trim()
                        )
                    )
                }
            }
            if (parsedBeats.isEmpty()) {
                throw IllegalStateException("No script beats could be parsed from AI response.")
            }

            return HonedScriptResult(
                title = root.optString("title", trimmedSpark.take(64)).trim(),
                chyronHeadline = root.optString("chyronHeadline", trimmedSpark.take(40)).trim(),
                chyronSubline = root.optString("chyronSubline", "Aircheck Desk").trim(),
                beats = parsedBeats
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Script honing timed out", e)
            throw IllegalStateException("Script generation timed out after 60s. Please retry.", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Script honing failed", e)
            throw e
        }
    }

    /**
     * Streams sharper prose and timely facts directly into a single beat card.
     */
    suspend fun streamExpandBeat(
        projectContext: String,
        beatText: String,
        onStreamChunk: (String) -> Unit
    ): String {
        val prompt = """
            You are a broadcast script doctor editing ONE talking-head beat.
            Make this beat sharper, punchier, and grounded with concrete, timely context if relevant.
            Keep roughly the same spoken length (1 to 2 crisp sentences). Sentence case only.
            Return ONLY the rewritten spoken line with no quotes, markdown, or prefixes.

            Topic context: "$projectContext"
            Current beat: "$beatText"
        """.trimIndent()

        val sb = StringBuilder()
        try {
            withTimeout(45_000L) {
                streamingTextModel.generateContentStream(prompt).collect { chunk ->
                    val piece = chunk.text.orEmpty()
                    if (piece.isNotEmpty()) {
                        sb.append(piece)
                        onStreamChunk(sb.toString().trim())
                    }
                }
            }
            val result = sb.toString().trim()
            if (result.isBlank()) throw IllegalStateException("Model returned an empty rewrite.")
            return result
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Beat expansion timed out", e)
            throw IllegalStateException("Beat rewrite timed out. Please retry.", e)
        } catch (e: CancellationException) {
            throw e
        }
    }

    /**
     * Compresses active beats (short <15s hooks OR full 60s scripts) by ~20%,
     * down to a minimum 3s spoken beat, returning proposals for Keep Original / Use Shorter.
     */
    suspend fun proposeShorten20Percent(beats: List<ScriptBeat>): Map<String, String> {
        val active = beats.filter { !it.cut && it.spokenText.isNotBlank() }
        require(active.isNotEmpty()) { "Write at least one beat before shortening." }

        val listing = active.joinToString("\n") { beat ->
            """{"beatId":"${beat.id}","words":${beat.wordCount},"text":${JSONObject.quote(beat.spokenText)}}"""
        }

        val prompt = """
            Tighten each talking-head beat below by roughly 20% to remove filler words and sharpen cadence.
            Rules:
            - Works on BOTH short single-sentence hooks (<15s) and long multi-beat 60s scripts.
            - Always produce a genuinely tighter version (at least 1-3 fewer words even for short hooks, while staying natural and >= 5 words so it reads at least ~3s).
            - Preserve all core facts, names, and numbers. Sentence case only.

            Beats:
            $listing
        """.trimIndent()

        try {
            val response = withTimeout(60_000L) {
                shortenModel.generateContent(prompt)
            }
            val rawJson = response.text ?: throw IllegalStateException("Empty shorten response.")
            val arr = JSONObject(rawJson).getJSONArray("items")
            val result = mutableMapOf<String, String>()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val id = item.getString("beatId")
                val shorter = item.getString("shorterText").trim()
                if (shorter.isNotBlank()) {
                    result[id] = shorter
                }
            }
            if (result.isEmpty()) throw IllegalStateException("No shortened proposals returned.")
            return result
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException("Shortening timed out after 60s. Please retry.", e)
        } catch (e: CancellationException) {
            throw e
        }
    }

    /**
     * Fact-checks a beat and returns a concise source badge + sharpened factual line.
     */
    suspend fun factCheckBeat(topic: String, beatText: String): FactCheckResult {
        val prompt = """
            Fact-check this short-form broadcast beat and strengthen it with accurate, timely attribution.
            Topic: "$topic"
            Beat: "$beatText"
            Provide:
            - citationLabel: ultra-concise source/data tag (<= 28 chars, e.g. "NIST 2026 Advisory" or "SEC Q3 Filing")
            - note: one concise sentence summarizing accuracy or context
            - refinedText: sharpened sentence-case spoken line preserving natural delivery
        """.trimIndent()

        try {
            val response = withTimeout(60_000L) {
                factCheckModel.generateContent(prompt)
            }
            val obj = JSONObject(response.text ?: throw IllegalStateException("Empty fact check response."))
            return FactCheckResult(
                citationLabel = obj.optString("citationLabel", "Verified").trim(),
                note = obj.optString("note", "").trim(),
                tightenedText = obj.optString("refinedText", "").trim().ifBlank { null }
            )
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException("Fact check timed out after 60s.", e)
        } catch (e: CancellationException) {
            throw e
        }
    }

    /**
     * Identity-locked newsroom anchor compositing via Gemini Nano Banana 2.1.
     */
    suspend fun composeIdentityLockedAnchor(
        selfieBitmap: Bitmap,
        backdropReference: Bitmap?,
        backdropPrompt: String
    ): Bitmap {
        val instruction = buildString {
            append("Transform the first image (the real person's portrait) into a photorealistic 9:16 vertical broadcast news anchor portrait. ")
            append("STRICT IDENTITY LOCK: Preserve the exact face, bone structure, eyes, hair, skin tone, and likeness of the person in the first image without altering their identity. ")
            append("FRAMING & POSE: Chest-up centered studio framing, direct eye contact into the camera lens, calm neutral expression with gently closed lips ready for lip-sync animation, shoulders squared, ZERO hands, ZERO microphones or props blocking the face. ")
            if (backdropReference != null) {
                append("BACKGROUND: Place the anchor seamlessly in front of the studio environment shown in the second reference image with cinematic shallow depth-of-field bokeh and matching broadcast rim lighting. ")
            }
            append("Studio aesthetic: $backdropPrompt. Crisp editorial 4K broadcast photography, clean key light, no on-screen text or watermarks.")
        }

        try {
            val response = withTimeout(60_000L) {
                imageEditModel.generateContent(
                    content {
                        image(selfieBitmap)
                        if (backdropReference != null) {
                            image(backdropReference)
                        }
                        text(instruction)
                    }
                )
            }
            val part = response.candidates.firstOrNull()
                ?.content?.parts?.filterIsInstance<ImagePart>()?.firstOrNull()
            return part?.image ?: throw IllegalStateException("No composited image returned by Gemini image model.")
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException("Studio compositing timed out after 60s. Please retry.", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Studio anchor compositing failed", e)
            throw e
        }
    }

    /**
     * Generates a crisp editorial B-roll plate for a beat cutaway.
     */
    suspend fun generateBRollPlate(bRollPrompt: String, topicContext: String): Bitmap {
        val prompt = """
            Create a cinematic 9:16 vertical editorial newsroom B-roll cutaway photograph for a broadcast segment.
            Topic: $topicContext
            Visual subject: $bRollPrompt
            Style: High-contrast obsidian studio lighting, subtle volt-lime or electric cobalt broadcast rim accents, photorealistic editorial photography, zero text overlays, zero watermarks.
        """.trimIndent()

        try {
            val response = withTimeout(60_000L) {
                imageEditModel.generateContent(prompt)
            }
            val part = response.candidates.firstOrNull()
                ?.content?.parts?.filterIsInstance<ImagePart>()?.firstOrNull()
            return part?.image ?: throw IllegalStateException("No B-roll image returned by Gemini.")
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException("B-roll generation timed out after 60s.", e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "B-roll generation failed", e)
            throw e
        }
    }
}
