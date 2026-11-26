package com.example.data.gateway

import com.example.BuildConfig
import com.example.data.AlignedWord
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Deepgram Nova-3 speech-to-text, called straight from the phone (works with the Mac offline).
 * The single transcription + word-timing system: idea voice notes and clip-voice word alignment.
 */
class DeepgramClient(
    private val http: OkHttpClient,
    private val apiKey: String = BuildConfig.DEEPGRAM_API_KEY
) {
    data class Transcript(val text: String, val words: List<AlignedWord>, val durationMs: Long)

    suspend fun transcribe(wavBytes: ByteArray): Transcript = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw IOException("Speech-to-text isn't set up in this build (no Deepgram key).")
        if (wavBytes.isEmpty()) throw IOException("Audio is empty.")
        val req = Request.Builder()
            .url("https://api.deepgram.com/v1/listen?model=nova-3&smart_format=true&punctuate=true")
            .header("Authorization", "Token $apiKey")
            .post(wavBytes.toRequestBody("audio/wav".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val detail = runCatching { JSONObject(body).optString("err_msg") }.getOrNull().orEmpty()
                throw IOException("Speech-to-text failed (HTTP ${resp.code}${if (detail.isNotBlank()) ": $detail" else ""}).")
            }
            val json = JSONObject(body)
            val alt = json.getJSONObject("results").getJSONArray("channels").getJSONObject(0)
                .getJSONArray("alternatives").getJSONObject(0)
            val arr = alt.optJSONArray("words")
            val words = buildList {
                if (arr != null) for (i in 0 until arr.length()) {
                    val w = arr.getJSONObject(i)
                    add(
                        AlignedWord(
                            word = w.optString("punctuated_word").ifBlank { w.optString("word") },
                            startMs = (w.optDouble("start") * 1000).toLong(),
                            endMs = (w.optDouble("end") * 1000).toLong()
                        )
                    )
                }
            }
            val durationMs = (json.optJSONObject("metadata")?.optDouble("duration", 0.0) ?: 0.0).times(1000).toLong()
            Transcript(alt.optString("transcript").trim(), words, durationMs)
        }
    }
}
