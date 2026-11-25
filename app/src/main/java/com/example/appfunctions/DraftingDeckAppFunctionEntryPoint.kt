package com.example.appfunctions

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.appfunctions.AppFunctionAccessLevel
import androidx.appfunctions.AppFunctionAppUnknownException
import androidx.appfunctions.AppFunctionDeclaration
import androidx.appfunctions.AppFunctionDisabledException
import androidx.appfunctions.AppFunctionInvalidArgumentException
import androidx.appfunctions.AppFunctionManager
import androidx.appfunctions.AppFunctionSerializable
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import androidx.appfunctions.ExperimentalAppFunctionsApi
import androidx.appfunctions.metadata.AppFunctionMetadata
import com.example.AircheckApplication
import com.example.MainActivity
import com.example.data.BrandChannel
import com.example.data.ClipRecord
import com.example.data.RenderJobStatus
import com.example.data.ScriptBeat
import com.example.data.ScriptProject
import com.example.data.VoiceSource
import com.example.data.estimateDurationSeconds
import com.example.data.local.DEFAULT_MAC_RIG_LAN_URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Partial-update wrapper distinguishing "do not modify" (`null` wrapper) from "clear to empty/null"
 * (`SetStringField(value = null)`).
 *
 * @property value Nullable string payload to assign when this wrapper is non-null.
 */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class SetStringField(
    val value: String? = null
)

/**
 * Created or updated plain-text broadcast script draft summary.
 *
 * @property scriptId Unique identifier of the saved script project in Room.
 * @property title Broadcast headline or working script title.
 * @property spokenText Sanitized plain-text script body.
 * @property openDraftIntent Immutable PendingIntent opening Aircheck directly to the drafting deck.
 */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class SeededDraftSummary(
    val scriptId: String,
    val title: String,
    val spokenText: String,
    val openDraftIntent: PendingIntent
)

/**
 * Relevant peer match from Aircheck's local X archive Network Graph Radar.
 *
 * @property handle Normalized lowercase X handle without a leading `@`.
 * @property tweetId Matched archive post identifier.
 * @property snippet Excerpt of the matched post text.
 * @property matchReason Reason category (`Counter-argument`, `Maintainer`, `Active thread`, or `Peer`).
 * @property score Combined lexical and vector relevance score.
 */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class GraphPeerMatchItem(
    val handle: String,
    val tweetId: String,
    val snippet: String,
    val matchReason: String,
    val score: Double
)

/**
 * Updated studio configuration snapshot returned after partial setting updates.
 *
 * @property brandChannel Active studio accent channel (`VOLT_LIME` or `BROADCAST_COBALT`).
 * @property tailscaleRigUrl Configured Mac Studio `:8443` endpoint URL.
 */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class StudioSettingsSnapshot(
    val brandChannel: String,
    val tailscaleRigUrl: String
)

/**
 * Enqueued Mac Studio `:8443` talking-head render receipt.
 *
 * @property jobId Remote render job identifier on the Mac Studio rig.
 * @property clipId Local Room clip identifier tracking render progress.
 * @property resolvedRigUrl Reachable Mac Studio URL that accepted the render job.
 */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class QueuedRigRenderReceipt(
    val jobId: String,
    val clipId: String,
    val resolvedRigUrl: String
)

/**
 * Android 17 (`CINNAMON_BUN`) `:app` module AppFunction service entry point exposing plain-text
 * draft seeding, Graph Radar peer lookup, studio configuration updates, and Mac Studio render submission.
 */
@AppFunctionServiceEntryPoint(
    serviceName = "DraftingDeckAppFunctionService",
    appFunctionXmlFileName = "drafting_deck_app_function_service"
)
abstract class DraftingDeckAppFunctionEntryPoint : AppFunctionService() {

    private val container by lazy {
        (applicationContext as AircheckApplication).container
    }

    /**
     * Creates or updates a plain-text broadcast script draft in local Room storage and returns a deep-link PendingIntent.
     *
     * @param spokenText Plain-text spoken thought or script body to save.
     * @param chyronHeadline Optional lower-third broadcast headline; pass `null` to derive from the first sentence.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun seedVoiceDraft(
        spokenText: String,
        chyronHeadline: String? = null
    ): SeededDraftSummary = withContext(Dispatchers.IO) {
        val cleanBody = spokenText.trim()
        if (cleanBody.isEmpty()) {
            throw AppFunctionInvalidArgumentException("spokenText must not be blank.")
        }
        val effectiveHeadline = chyronHeadline?.trim().orEmpty().ifBlank {
            cleanBody.split('.', '!', '?').firstOrNull().orEmpty().trim().take(36).uppercase()
                .ifBlank { "AIRCHECK DRAFT" }
        }
        val project = ScriptProject(
            title = effectiveHeadline,
            ideaSpark = cleanBody,
            voiceSource = VoiceSource.ENROLLED_VOICE_CLONE,
            beats = listOf(
                ScriptBeat(
                    index = 0,
                    spokenText = cleanBody,
                    durationSec = estimateDurationSeconds(cleanBody, 8).coerceIn(4, 60)
                )
            )
        ).let { base ->
            base.copy(
                stageConfig = base.stageConfig.copy(
                    chyronHeadline = effectiveHeadline,
                    chyronSubline = "AIRCHECK LIVE DESK"
                )
            )
        }
        container.localRepo.saveScript(project)
        container.profileStore.setActiveScriptId(project.id)

        val launchIntent = Intent(applicationContext, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_HANDOFF_STAGE, "CUT")
            putExtra(MainActivity.EXTRA_HANDOFF_SEED_TEXT, cleanBody)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            project.id.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        SeededDraftSummary(
            scriptId = project.id,
            title = effectiveHeadline,
            spokenText = cleanBody,
            openDraftIntent = pendingIntent
        )
    }

    /**
     * Queries Aircheck's local X archive Network Graph Radar for peers relevant to the given draft text.
     *
     * @param draftText Plain-text draft or topic query to match against imported contacts and posts.
     * @param maxResults Maximum number of ranked peer matches to return (`1..10`, defaults to `5` when `0`).
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun queryGraphPeers(
        draftText: String,
        maxResults: Int = 0
    ): List<GraphPeerMatchItem> = withContext(Dispatchers.IO) {
        val cleanQuery = draftText.trim()
        if (cleanQuery.isEmpty()) {
            throw AppFunctionInvalidArgumentException("draftText must not be blank.")
        }
        val limit = if (maxResults <= 0) 5 else maxResults.coerceIn(1, 10)
        val matches = container.graphRadarEngine.findMatches(draftText = cleanQuery, limit = limit)
        matches.map { match ->
            GraphPeerMatchItem(
                handle = match.handle,
                tweetId = match.tweetId,
                snippet = match.snippet,
                matchReason = match.reason.label,
                score = match.score
            )
        }
    }

    /**
     * Partially updates Aircheck's studio accent channel or Mac Studio `:8443` server URL.
     *
     * @param brandChannel Optional wrapper setting `VOLT_LIME` or `BROADCAST_COBALT`; `null` leaves channel unchanged.
     * @param tailscaleRigUrl Optional wrapper updating the Mac Studio URL (`null` inside wrapper resets to default LAN URL).
     */
    @OptIn(ExperimentalAppFunctionsApi::class)
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    @AppFunctionAccessLevel(
        level = AppFunctionMetadata.ACCESS_LEVEL_SELF,
        isCompatEnforcementEnabled = true
    )
    suspend fun updateStudioSettings(
        brandChannel: SetStringField? = null,
        tailscaleRigUrl: SetStringField? = null
    ): StudioSettingsSnapshot = withContext(Dispatchers.IO) {
        if (brandChannel != null) {
            val raw = brandChannel.value?.trim().orEmpty()
            val parsed = BrandChannel.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: throw AppFunctionInvalidArgumentException(
                    "Unsupported brandChannel '$raw'; expected VOLT_LIME or BROADCAST_COBALT."
                )
            container.profileStore.setBrandChannel(parsed)
        }
        if (tailscaleRigUrl != null) {
            val rawUrl = tailscaleRigUrl.value?.trim().orEmpty().ifBlank { DEFAULT_MAC_RIG_LAN_URL }
            if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://")) {
                throw AppFunctionInvalidArgumentException("tailscaleRigUrl must start with http:// or https://.")
            }
            container.profileStore.setTailscaleRigUrl(rawUrl)
        }
        val updated = container.profileStore.current()
        StudioSettingsSnapshot(
            brandChannel = updated.brandChannel.name,
            tailscaleRigUrl = updated.tailscaleRigUrl
        )
    }

    /**
     * Submits a saved script project to the paired Mac Studio `:8443` rig for talking-head video rendering.
     *
     * @param spokenText Spoken script text to render as a 9:16 anchor clip.
     * @param chyronHeadline Optional lower-third headline to display on the broadcast clip.
     */
    @OptIn(ExperimentalAppFunctionsApi::class)
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    @AppFunctionAccessLevel(
        level = AppFunctionMetadata.ACCESS_LEVEL_SELF,
        isCompatEnforcementEnabled = true
    )
    suspend fun enqueueMacRigRender(
        spokenText: String,
        chyronHeadline: String? = null
    ): QueuedRigRenderReceipt = withContext(Dispatchers.IO) {
        val cleanBody = spokenText.trim()
        if (cleanBody.isEmpty()) {
            throw AppFunctionInvalidArgumentException("spokenText must not be blank.")
        }
        val configuredUrl = container.profileStore.current().tailscaleRigUrl.ifBlank { DEFAULT_MAC_RIG_LAN_URL }
        val health = runCatching {
            container.macRigGateway.resolveAndCheckHealth(configuredUrl)
        }.getOrElse { err ->
            throw AppFunctionDisabledException(
                err.localizedMessage ?: "Mac Studio rig is currently unreachable."
            )
        }
        val headline = chyronHeadline?.trim().orEmpty().ifBlank {
            cleanBody.split('.', '!', '?').firstOrNull().orEmpty().trim().take(36).uppercase()
                .ifBlank { "BREAKING NEWS" }
        }
        val project = ScriptProject(
            title = headline,
            ideaSpark = cleanBody,
            voiceSource = VoiceSource.ENROLLED_VOICE_CLONE,
            beats = listOf(
                ScriptBeat(
                    index = 0,
                    spokenText = cleanBody,
                    durationSec = estimateDurationSeconds(cleanBody, 8).coerceIn(5, 10)
                )
            )
        ).let { base ->
            base.copy(
                stageConfig = base.stageConfig.copy(
                    chyronHeadline = headline,
                    chyronSubline = "AIRCHECK LIVE DESK"
                )
            )
        }
        container.localRepo.saveScript(project)
        val enqueued = try {
            container.macRigGateway.enqueueMacRigRender(
                rigBaseUrl = health.resolvedUrl,
                project = project,
                photoSha = null,
                resolution = "1080P",
                ratio = "9:16",
                maxDurationSec = project.totalActiveSeconds.coerceIn(5, 10)
            )
        } catch (e: Exception) {
            throw AppFunctionAppUnknownException(e.localizedMessage ?: "Mac Studio render request failed.")
        }
        val now = System.currentTimeMillis()
        container.localRepo.upsertClip(
            ClipRecord(
                id = enqueued.clipId,
                scriptId = project.id,
                jobId = enqueued.jobId,
                title = headline,
                photoSha = null,
                voiceSha = null,
                alignSha = null,
                lipsyncSha = null,
                finalSha = null,
                status = RenderJobStatus.QUEUED,
                progressFraction = 0.08f,
                stageDetail = "Queued via AppFunction",
                errorMessage = null,
                durationMs = project.totalActiveSeconds * 1000L,
                waveformPeaks = emptyList(),
                thumbHash = null,
                alignedWords = emptyList(),
                chyronHeadline = headline,
                chyronSubline = "AIRCHECK LIVE DESK",
                remoteLipsyncUrl = null,
                remoteFinalUrl = null,
                exportedUri = null,
                createdAtMillis = now,
                updatedAtMillis = now
            )
        )
        QueuedRigRenderReceipt(
            jobId = enqueued.jobId,
            clipId = enqueued.clipId,
            resolvedRigUrl = health.resolvedUrl
        )
    }
}

/**
 * Dynamically enables or disables hardware/network-dependent AppFunctions at runtime via
 * [AppFunctionManager.setAppFunctionEnabled] whenever Mac Studio `:8443` reachability or local
 * VoiceStudio OTA bundle readiness changes.
 */
object AppFunctionAvailabilitySync {
    const val ID_MAC_RIG_RENDER: String =
        "com.example.appfunctions.DraftingDeckAppFunctionEntryPoint#enqueueMacRigRender"
    const val ID_LOCAL_VOICE_SYNTH: String =
        "com.voicestudio.sync.appfunctions.VoiceStudioSyncAppFunctionEntryPoint#triggerLocalVoiceSynthesis"

    suspend fun syncDynamicFunctionStates(
        context: Context,
        isMacRigOnline: Boolean,
        isLocalVoiceBundleReady: Boolean
    ) {
        val manager = runCatching { AppFunctionManager.getInstance(context.applicationContext) }.getOrNull()
            ?: return
        runCatching {
            manager.setAppFunctionEnabled(
                ID_MAC_RIG_RENDER,
                if (isMacRigOnline) {
                    AppFunctionManager.APP_FUNCTION_STATE_ENABLED
                } else {
                    AppFunctionManager.APP_FUNCTION_STATE_DISABLED
                }
            )
        }
        runCatching {
            manager.setAppFunctionEnabled(
                ID_LOCAL_VOICE_SYNTH,
                if (isLocalVoiceBundleReady) {
                    AppFunctionManager.APP_FUNCTION_STATE_ENABLED
                } else {
                    AppFunctionManager.APP_FUNCTION_STATE_DISABLED
                }
            )
        }
    }
}
