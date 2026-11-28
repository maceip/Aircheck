package com.example.graph

import com.example.data.local.AircheckProfileStore
import com.example.data.local.DEFAULT_MAC_RIG_LAN_URL
import com.example.draft.DraftApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// ---- Wire models for the rig's /v1/pulse and /v1/namedrop endpoints (pulse_namedrop.py) ----

@Serializable
data class PulseEntity(
    val name: String = "",
    @SerialName("corrected_name") val correctedName: String = "",
    val kind: String = ""
)

@Serializable
data class PulseSourceLink(val title: String = "", val url: String = "", val domain: String = "")

@Serializable
data class PulseClaim(
    val text: String = "",
    val about: String = "",
    val kind: String = "",
    val status: String = "unknown",
    val sources: List<PulseSourceLink> = emptyList(),
    val detail: String = ""
)

@Serializable
data class PulseHeatSource(
    val name: String = "",
    val ok: Boolean = false,
    @SerialName("count_24h") val count24h: Double? = null,
    @SerialName("avg_7d") val avg7d: Double? = null,
    val detail: String = "",
    val capped: Boolean = false
)

@Serializable
data class PulseHeat(val ratio: Double? = null, val sources: List<PulseHeatSource> = emptyList())

@Serializable
data class PulseBoost(val kind: String = "", val text: String = "", val why: String = "")

@Serializable
data class PulseScore(
    val text: String = "",
    val field: String = "",
    val query: String = "",
    val entities: List<PulseEntity> = emptyList(),
    val claims: List<PulseClaim> = emptyList(),
    @SerialName("boosts_held") val boostsHeld: Boolean = false,
    @SerialName("held_reason") val heldReason: String = "",
    val heat: PulseHeat = PulseHeat(),
    val boosts: List<PulseBoost> = emptyList(),
    @SerialName("analysis_via") val analysisVia: String = "",
    val areas: List<String> = emptyList()
)

@Serializable
data class PulseAreas(val areas: List<String> = emptyList(), @SerialName("updated_at") val updatedAt: String? = null)

@Serializable
data class NameDropHandle(val handle: String = "", val status: String = "unknown", val evidence: String = "")

@Serializable
data class NameDropPerson(
    val name: String = "",
    val role: String = "",
    val orcid: String = "",
    val handles: Map<String, NameDropHandle> = emptyMap()
)

@Serializable
data class NameDropResult(
    val kind: String = "",
    val title: String = "",
    val people: List<NameDropPerson> = emptyList(),
    val taggable: Map<String, Int> = emptyMap(),
    val notes: List<String> = emptyList()
)

@Serializable
data class FirstLookPerson(
    val id: String = "",
    val name: String = "",
    val platform: String = "x",
    val address: String = "",
    val on: Boolean = true
)

@Serializable
data class FirstLookSendSummary(val at: String = "", val sent: Int = 0, @SerialName("needs_you") val needsYou: Int = 0, val failed: Int = 0)

@Serializable
data class FirstLookList(
    val people: List<FirstLookPerson> = emptyList(),
    @SerialName("bsky_dm_ready") val bskyDmReady: Boolean = false,
    @SerialName("last_send") val lastSend: FirstLookSendSummary? = null
)

@Serializable
data class FirstLookSendResult(
    val id: String = "",
    val name: String = "",
    val platform: String = "",
    val address: String = "",
    val status: String = "",
    val detail: String = "",
    @SerialName("open_url") val openUrl: String? = null,
    @SerialName("copy_text") val copyText: String? = null
)

@Serializable
data class FirstLookSendResponse(val results: List<FirstLookSendResult> = emptyList(), val summary: FirstLookSendSummary = FirstLookSendSummary())

@Serializable
private data class ScoreRequest(val text: String, val field: String)

@Serializable
private data class ResolveRequest(val url: String? = null, val text: String? = null)

@Serializable
private data class SendRequest(@SerialName("post_url") val postUrl: String, val text: String)

@Serializable
private data class PeopleBody(val people: List<FirstLookPerson>)

/**
 * Talks to the rig's Pulse and Name-drop endpoints. Every call returns a Result: a rig that is
 * offline is a failure the UI shows as "unknown", never a guessed value.
 */
/**
 * Shared rig token that unlocks First Look on the rig (AIRCHECK_RIG_TOKEN). It arrives from
 * `aircheck-rig sync-phone` as a staged file and is kept in app-private preferences.
 */
class RigTokenStore(context: android.content.Context) {
    private val prefs = context.applicationContext.getSharedPreferences("aircheck_rig", android.content.Context.MODE_PRIVATE)

    fun get(): String? = prefs.getString(KEY, null)?.takeIf { it.isNotBlank() }

    fun save(token: String) {
        prefs.edit().putString(KEY, token.trim()).apply()
    }

    companion object {
        private const val KEY = "rig_token"
        const val BOOTSTRAP_FILE = "rig_token.txt"
        private val VALID = Regex("^[A-Za-z0-9._-]{16,256}$")

        fun isValid(token: String): Boolean = VALID.matches(token.trim())
    }
}

class PulseClient(
    private val profileStore: AircheckProfileStore,
    private val draftApiClientProvider: (String) -> DraftApiClient,
    private val okHttpClient: OkHttpClient,
    private val rigToken: () -> String? = { null }
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    suspend fun score(text: String, field: String): Result<PulseScore> =
        call("POST", "/v1/pulse/score", json.encodeToString(ScoreRequest.serializer(), ScoreRequest(text, field)), PulseScore.serializer())

    suspend fun areas(): Result<PulseAreas> = call("GET", "/v1/pulse/areas", null, PulseAreas.serializer())

    suspend fun saveAreas(areas: List<String>): Result<PulseAreas> =
        call("PUT", "/v1/pulse/areas", json.encodeToString(PulseAreas.serializer(), PulseAreas(areas)), PulseAreas.serializer())

    suspend fun resolve(input: String): Result<NameDropResult> {
        val trimmed = input.trim()
        val body = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) ResolveRequest(url = trimmed) else ResolveRequest(text = trimmed)
        return call("POST", "/v1/namedrop/resolve", json.encodeToString(ResolveRequest.serializer(), body), NameDropResult.serializer())
    }

    suspend fun firstLook(): Result<FirstLookList> = call("GET", "/v1/namedrop/first-look", null, FirstLookList.serializer())

    suspend fun saveFirstLook(people: List<FirstLookPerson>): Result<FirstLookList> =
        call("PUT", "/v1/namedrop/first-look", json.encodeToString(PeopleBody.serializer(), PeopleBody(people)), FirstLookList.serializer())

    suspend fun sendFirstLook(postUrl: String, text: String): Result<FirstLookSendResponse> =
        // Never retried: the rig may already have sent the DMs when the reply is lost.
        call("POST", "/v1/namedrop/first-look/send", json.encodeToString(SendRequest.serializer(), SendRequest(postUrl, text)), FirstLookSendResponse.serializer(), retry = false)

    private suspend fun <T> call(method: String, path: String, body: String?, serializer: KSerializer<T>, retry: Boolean = true): Result<T> =
        withContext(Dispatchers.IO) {
            try {
                val rigUrl = profileStore.current().tailscaleRigUrl.trim().ifBlank { DEFAULT_MAC_RIG_LAN_URL }
                val client = draftApiClientProvider(rigUrl)
                val base = client.resolveActiveBaseUrl(forceRefresh = false)
                try {
                    execute(base, method, path, body, serializer)
                } catch (e: IOException) {
                    if (!retry) throw e
                    // The cached rig URL may be stale (e.g. USB unplugged); probe again once.
                    val refreshed = client.resolveActiveBaseUrl(forceRefresh = true)
                    if (refreshed == base) throw e
                    execute(refreshed, method, path, body, serializer)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private suspend fun <T> execute(base: String, method: String, path: String, body: String?, serializer: KSerializer<T>): Result<T> {
        val builder = Request.Builder().url(base + path).method(method, body?.toRequestBody(JSON_MEDIA_TYPE))
        // Only the routes that need it get the token, so a wrong rig URL never sees it from Pulse.
        if (path.startsWith(FIRST_LOOK_PATH)) rigToken()?.let { builder.header(RIG_TOKEN_HEADER, it) }
        val req = builder.build()
        okHttpClient.newCall(req).await().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val detail = DraftApiClient.parseServerErrorInfoStatic(json, raw).detail
                return Result.failure(HttpFailure("$method $path: HTTP ${resp.code}${if (detail.isNotBlank()) ": $detail" else ""}"))
            }
            return Result.success(json.decodeFromString(serializer, raw))
        }
    }

    /** Runs the call so that cancelling the coroutine (text changed, sheet closed) cancels the request. */
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }

            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }
        })
    }

    /** A rig HTTP error (not a transport failure), so it is not retried on another URL. */
    class HttpFailure(message: String) : Exception(message)

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val RIG_TOKEN_HEADER = "X-Aircheck-Token"
        const val FIRST_LOOK_PATH = "/v1/namedrop/first-look"
    }
}
