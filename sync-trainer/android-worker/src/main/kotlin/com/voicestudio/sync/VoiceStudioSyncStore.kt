package com.voicestudio.sync

import com.voicestudio.edl.ClauseNode
import com.voicestudio.edl.ClauseSynthesisProgress
import com.voicestudio.edl.DEFAULT_TAKES_PER_CLAUSE
import com.voicestudio.edl.PreferenceTelemetryRecord
import com.voicestudio.edl.VoiceProjectManifest
import com.voicestudio.engine.VoiceEngineJni
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * Persistence boundary for Room-backed `voice_preferences` rows consumed by [PreferenceSyncWorker]
 * and populated by `:app` and [VoiceSynthesisWorker].
 */
interface SyncPreferenceStore {
    suspend fun listUnsyncedPreferences(limit: Int = 200): List<PreferenceTelemetryRecord>
    suspend fun markPreferencesSynced(timestamps: List<Long>)
    suspend fun appendPreference(record: PreferenceTelemetryRecord)
}

/**
 * Persistence boundary for Room-backed `voice_project_manifests` and script drafts consumed by
 * [VoiceSynthesisWorker].
 */
interface SyncProjectStore {
    suspend fun getProjectManifest(projectId: String): VoiceProjectManifest?
    suspend fun getScriptDraftForSynthesis(projectId: String): ScriptSynthesisDraft?
    suspend fun saveProjectManifest(manifest: VoiceProjectManifest)
}

data class ScriptSynthesisDraft(
    val projectId: String,
    val rawDraftText: String,
    val phoneticOverrides: Map<String, String> = emptyMap()
)

/**
 * Provides persisted Tailscale rig URL from `:app`'s Proto DataStore.
 */
interface SyncConfigProvider {
    suspend fun getTailscaleBaseUrl(): String
}

/**
 * Callback invoked by [PreferenceSyncWorker] when a newer SHA-256-verified `voice_lora_vXXX.gguf`
 * delta is downloaded so the live `llama.cpp` engine hot-swaps the LoRA without restarting the app.
 */
interface LoraHotSwapBridge {
    suspend fun onLoraHotSwapped(loraVersion: Int, loraFile: File): Result<Boolean>
}

/**
 * Real synthesis execution contract for [VoiceSynthesisWorker] combining `:core-engine`
 * (`VoiceEngineJni` + `LiteRtCodecDecoder`) and `:core-verifier` (`ClauseVerifier`).
 */
interface VoiceSynthesisExecutor {
    suspend fun synthesizeClauses(
        projectId: String,
        clauses: List<ClauseNode>,
        bundle: ResolvedOtaBundlePaths,
        outputRootDir: File,
        numCandidates: Int = DEFAULT_TAKES_PER_CLAUSE,
        onClauseProgress: suspend (completed: Int, total: Int, activeClauseId: String, phase: com.voicestudio.edl.ClauseSynthesisPhase) -> Unit
    ): Result<ClauseSynthesisExecutionResult>
}

data class ClauseSynthesisExecutionResult(
    val updatedClauses: List<ClauseNode>,
    val rejectedPreferences: List<PreferenceTelemetryRecord> = emptyList(),
    val evictedBytes: Long = 0L
)

/**
 * Process-scoped runtime registry connecting `:app`'s Room WAL stores, Proto DataStore config,
 * [OtaBundleSyncManager], and live native `VoiceEngineJni` handle to WorkManager workers.
 *
 * Workers run in the same process and are instantiated via WorkManager's reflection factory, so
 * this registry avoids tight coupling while remaining trivially overridable in unit tests.
 */
object VoiceStudioRuntimeRegistry {
    @Volatile
    var otaBundleSyncManager: OtaBundleSyncManager? = null

    @Volatile
    var preferenceStore: SyncPreferenceStore? = null

    @Volatile
    var projectStore: SyncProjectStore? = null

    @Volatile
    var configProvider: SyncConfigProvider? = null

    @Volatile
    var loraHotSwapBridge: LoraHotSwapBridge? = null

    @Volatile
    var synthesisExecutorFactory: ((android.content.Context) -> VoiceSynthesisExecutor)? = null

    @Volatile
    var vmRssReader: () -> Int? = { ProcessMemoryReader.readVmRssMb() }

    private val activeNativeEngineHandle = AtomicLong(0L)

    val synthesisProgressFlow = MutableStateFlow(ClauseSynthesisProgress())

    fun registerActiveEngineHandle(handle: Long) {
        activeNativeEngineHandle.set(handle)
    }

    fun getActiveEngineHandle(): Long = activeNativeEngineHandle.get()

    /**
     * Default live LoRA hot-swap implementation:
     * - When a native `VoiceEngineJni` handle is currently resident in memory, invokes
     *   `VoiceEngineJni.hotSwapLora(handle, loraFile.absolutePath)` immediately (zero restart).
     * - When the engine is currently idle/evicted (`handle == 0L`), verifies the GGUF file is
     *   readable on disk so the next synthesis session loads the new LoRA cleanly.
     */
    val defaultLoraHotSwapBridge: LoraHotSwapBridge = object : LoraHotSwapBridge {
        override suspend fun onLoraHotSwapped(loraVersion: Int, loraFile: File): Result<Boolean> {
            if (!loraFile.isFile || !loraFile.canRead() || loraFile.length() == 0L) {
                return Result.failure(
                    IllegalStateException("Verified LoRA file missing or empty: ${loraFile.absolutePath}")
                )
            }
            val handle = activeNativeEngineHandle.get()
            return if (handle != 0L) {
                VoiceEngineJni.hotSwapLora(handle, loraFile.absolutePath)
            } else {
                Result.success(true)
            }
        }
    }

    fun resetForTest() {
        otaBundleSyncManager = null
        preferenceStore = null
        projectStore = null
        configProvider = null
        loraHotSwapBridge = null
        synthesisExecutorFactory = null
        vmRssReader = { ProcessMemoryReader.readVmRssMb() }
        activeNativeEngineHandle.set(0L)
        synthesisProgressFlow.value = ClauseSynthesisProgress()
    }
}
