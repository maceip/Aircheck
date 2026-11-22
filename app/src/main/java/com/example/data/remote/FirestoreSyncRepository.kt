package com.example.data.remote

import android.content.Context
import android.util.Log
import com.example.R
import com.example.data.NewsroomStageConfig
import com.example.data.ScriptBeat
import com.example.data.ScriptProject
import com.example.data.Stage4Mode
import com.example.data.VoiceSource
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

enum class OperationType(val value: String) {
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete"),
    LIST("list"),
    GET("get"),
    WRITE("write"),
}

fun handleFirestoreError(exception: Throwable, operationType: OperationType, path: String?): String {
    val causes = generateSequence(exception) { it.cause }
    val rootError = causes.firstOrNull { it is FirebaseFirestoreException }
        ?: causes.firstOrNull { it !is CancellationException }
        ?: exception

    val auth = FirebaseAuth.getInstance()
    val currentUser = auth.currentUser

    val providerInfoList = currentUser?.providerData?.map { provider ->
        JSONObject().apply {
            put("providerId", provider.providerId)
            put("email", provider.email)
        }
    } ?: emptyList()

    val authInfoJson = JSONObject().apply {
        put("userId", currentUser?.uid)
        put("email", currentUser?.email)
        put("emailVerified", currentUser?.isEmailVerified)
        put("tenantId", currentUser?.tenantId)
        put("providerInfo", JSONArray(providerInfoList))
    }

    val errorInfoJson = JSONObject().apply {
        put("error", rootError.message ?: rootError.toString())
        put("operationType", operationType.value)
        put("path", path)
        put("authInfo", authInfoJson)
    }

    val jsonString = errorInfoJson.toString()
    Log.e("FirestoreError", "Firestore Error: $jsonString")
    return jsonString
}

class FirestoreSyncRepository(
    private val dbProvider: () -> FirebaseFirestore?
) {

    constructor(context: Context) : this(
        dbProvider = {
            if (FirebaseApp.getApps(context.applicationContext).isEmpty()) {
                null
            } else {
                runCatching {
                    FirebaseFirestore.getInstance(
                        context.applicationContext.getString(R.string.firestore_database_id)
                    )
                }.getOrNull()
            }
        }
    )

    constructor(db: FirebaseFirestore) : this(dbProvider = { db })

    private val db: FirebaseFirestore?
        get() = dbProvider()

    private val auth: FirebaseAuth?
        get() = runCatching { Firebase.auth }.getOrNull()

    private fun requireUserId(): String {
        return auth?.currentUser?.uid
            ?: throw IllegalStateException("Sign in with Google to sync with Aircheck Cloud.")
    }

    fun observeUserScripts(): Flow<List<ScriptProject>> = flow {
        val firestore = db
        val uid = auth?.currentUser?.uid
        if (firestore == null || uid.isNullOrBlank()) {
            emit(emptyList())
            return@flow
        }
        val path = "users/$uid/scripts"
        emitAll(
            firestore.collection("users").document(uid).collection("scripts")
                .snapshots()
                .map { snapshot ->
                    snapshot.documents.mapNotNull { doc -> doc.toScriptProjectOrNull(uid) }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth?.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun syncProject(project: ScriptProject): Result<Unit> {
        val firestore = db
            ?: return Result.failure(IllegalStateException("Firebase is not configured (missing app/google-services.json)."))
        val uid = auth?.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Not signed in."))
        val safeId = project.id.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(128)
        val docRef = firestore.collection("users").document(uid).collection("scripts").document(safeId)
        val path = docRef.path

        return try {
            val safeTarget = when (project.targetDurationSec) {
                15, 30, 45, 60 -> project.targetDurationSec
                else -> 30
            }
            val beatsList = project.beats.take(40).map { beat ->
                mapOf(
                    "id" to beat.id.take(64),
                    "index" to beat.index,
                    "spokenText" to beat.spokenText.take(600),
                    "durationSec" to beat.durationSec.coerceAtLeast(3),
                    "cut" to beat.cut,
                    "mentionTag" to beat.mentionTag.take(64),
                    "depthTextTag" to beat.depthTextTag.take(64),
                    "anchorWord" to beat.anchorWord.take(48),
                    "evidenceSource" to beat.evidenceSource.take(120),
                    "sfxTag" to beat.sfxTag.take(64),
                    "phoneticOverride" to beat.phoneticOverride.take(64),
                    "bRollPrompt" to beat.bRollPrompt.take(240)
                )
            }
            val stageMap = mapOf(
                "backdropPresetId" to project.stageConfig.backdropPresetId.take(64),
                "customBackdropPrompt" to project.stageConfig.customBackdropPrompt.take(240),
                "photoSha" to project.stageConfig.photoSha.orEmpty().take(64),
                "normalizedPhotoSha" to project.stageConfig.normalizedPhotoSha.orEmpty().take(64),
                "stage4Mode" to project.stageConfig.stage4Mode.id,
                "chyronHeadline" to project.stageConfig.chyronHeadline.take(80),
                "chyronSubline" to project.stageConfig.chyronSubline.take(100),
                "cornerBugText" to project.stageConfig.cornerBugText.take(32),
                "showBrandBug" to project.stageConfig.showBrandBug
            )

            val snapshot = docRef.get().await()
            if (snapshot.exists()) {
                val updatePayload = buildMap<String, Any?> {
                    put("title", project.title.take(180))
                    put("ideaSpark", project.ideaSpark.take(1000))
                    put("sourceUrl", project.sourceUrl.take(500))
                    put("targetDurationSec", safeTarget)
                    put("beats", beatsList)
                    put("stageConfig", stageMap)
                    put("voiceSource", project.voiceSource.name)
                    if (project.voiceIdOverride.isNotBlank()) {
                        put("voiceSelectionId", project.voiceIdOverride.take(128))
                    }
                    put("updatedAt", FieldValue.serverTimestamp())
                }.filterValues { it != null }
                docRef.update(updatePayload).await()
            } else {
                val createPayload = buildMap<String, Any?> {
                    put("userId", uid)
                    put("title", project.title.take(180))
                    put("ideaSpark", project.ideaSpark.take(1000))
                    put("sourceUrl", project.sourceUrl.take(500))
                    put("targetDurationSec", safeTarget)
                    put("beats", beatsList)
                    put("stageConfig", stageMap)
                    put("voiceSource", project.voiceSource.name)
                    if (project.voiceIdOverride.isNotBlank()) {
                        put("voiceSelectionId", project.voiceIdOverride.take(128))
                    }
                    put("createdAt", FieldValue.serverTimestamp())
                    put("updatedAt", FieldValue.serverTimestamp())
                }.filterValues { it != null }
                docRef.set(createPayload).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun deleteProject(projectId: String): Result<Unit> {
        val firestore = db
            ?: return Result.failure(IllegalStateException("Firebase is not configured (missing app/google-services.json)."))
        val uid = auth?.currentUser?.uid
            ?: return Result.failure(IllegalStateException("Not signed in."))
        val safeId = projectId.replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(128)
        val docRef = firestore.collection("users").document(uid).collection("scripts").document(safeId)
        return try {
            requireUserId()
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docRef.path)
            Result.failure(e)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun DocumentSnapshot.toScriptProjectOrNull(uid: String): ScriptProject? {
        val data = getData(DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) ?: return null
        val rawBeats = data["beats"] as? List<Map<String, Any?>> ?: emptyList()
        val beats = rawBeats.mapIndexed { idx, map ->
            ScriptBeat(
                id = (map["id"] as? String).orEmpty().ifBlank { "beat_$idx" },
                index = (map["index"] as? Number)?.toInt() ?: idx,
                spokenText = (map["spokenText"] as? String).orEmpty(),
                durationSec = (map["durationSec"] as? Number)?.toInt() ?: 5,
                cut = (map["cut"] as? Boolean) ?: false,
                mentionTag = (map["mentionTag"] as? String).orEmpty(),
                depthTextTag = (map["depthTextTag"] as? String).orEmpty(),
                anchorWord = (map["anchorWord"] as? String).orEmpty(),
                evidenceSource = (map["evidenceSource"] as? String).orEmpty(),
                sfxTag = (map["sfxTag"] as? String).orEmpty(),
                phoneticOverride = (map["phoneticOverride"] as? String).orEmpty(),
                bRollPrompt = (map["bRollPrompt"] as? String).orEmpty()
            )
        }
        val rawStage = data["stageConfig"] as? Map<String, Any?> ?: emptyMap()
        val stage = NewsroomStageConfig(
            backdropPresetId = (rawStage["backdropPresetId"] as? String).orEmpty().ifBlank { "volt_truss_globe" },
            customBackdropPrompt = (rawStage["customBackdropPrompt"] as? String).orEmpty(),
            photoSha = (rawStage["photoSha"] as? String).orEmpty().ifBlank { null },
            normalizedPhotoSha = (rawStage["normalizedPhotoSha"] as? String).orEmpty().ifBlank { null },
            stage4Mode = Stage4Mode.fromRaw(rawStage["stage4Mode"] as? String),
            chyronHeadline = (rawStage["chyronHeadline"] as? String).orEmpty(),
            chyronSubline = (rawStage["chyronSubline"] as? String).orEmpty(),
            cornerBugText = (rawStage["cornerBugText"] as? String).orEmpty().ifBlank { "AIRCHECK" },
            showBrandBug = (rawStage["showBrandBug"] as? Boolean) ?: true
        )
        val createdAtTs = getTimestamp("createdAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        val updatedAtTs = getTimestamp("updatedAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)

        return ScriptProject(
            id = id,
            userId = (data["userId"] as? String).orEmpty().ifBlank { uid },
            title = (data["title"] as? String).orEmpty(),
            ideaSpark = (data["ideaSpark"] as? String).orEmpty(),
            sourceUrl = (data["sourceUrl"] as? String).orEmpty(),
            targetDurationSec = (data["targetDurationSec"] as? Number)?.toInt() ?: 30,
            beats = if (beats.isEmpty()) listOf(ScriptBeat(index = 0)) else beats,
            stageConfig = stage,
            voiceSource = VoiceSource.fromRaw(data["voiceSource"] as? String),
            voiceIdOverride = (data["voiceSelectionId"] as? String).orEmpty(),
            createdAtMillis = createdAtTs?.toDate()?.time ?: System.currentTimeMillis(),
            updatedAtMillis = updatedAtTs?.toDate()?.time ?: System.currentTimeMillis()
        )
    }
}
