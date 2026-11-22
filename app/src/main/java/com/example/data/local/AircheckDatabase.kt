package com.example.data.local

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import com.example.data.AlignedWord
import com.example.data.ClipCardRow
import com.example.data.ClipRecord
import com.example.data.NewsroomStageConfig
import com.example.data.PoseGateResult
import com.example.data.RenderJobStatus
import com.example.data.ScriptBeat
import com.example.data.ScriptProject
import com.example.data.Stage4Mode
import com.example.data.VoiceSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.ByteOrder

@Entity(
    tableName = "scripts",
    indices = [Index(value = ["updatedAtMillis"])]
)
data class ScriptEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val title: String,
    val ideaSpark: String,
    val sourceUrl: String,
    val targetDurationSec: Int,
    val backdropPresetId: String,
    val customBackdropPrompt: String,
    val photoSha: String?,
    val normalizedPhotoSha: String?,
    val poseGateJson: String,
    val stage4Mode: String,
    val chyronHeadline: String,
    val chyronSubline: String,
    val cornerBugText: String,
    val showBrandBug: Boolean,
    val voiceSource: String,
    val voiceIdOverride: String,
    val masterAudioSha: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

@Entity(
    tableName = "script_beats",
    foreignKeys = [
        ForeignKey(
            entity = ScriptEntity::class,
            parentColumns = ["id"],
            childColumns = ["scriptId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["scriptId", "ordinal"])]
)
data class ScriptBeatEntity(
    @PrimaryKey val id: String,
    val scriptId: String,
    val ordinal: Int,
    val spokenText: String,
    val proposedShortText: String?,
    val estSec: Int,
    val isCut: Boolean,
    val mentionTag: String,
    val depthTextTag: String,
    val anchorWord: String,
    val evidenceSource: String,
    val sfxTag: String,
    val phoneticOverride: String,
    val bRollPrompt: String,
    val bRollSha: String?,
    val beatAudioSha: String?
)

data class ScriptWithBeats(
    @Embedded val script: ScriptEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "scriptId"
    )
    val beats: List<ScriptBeatEntity>
)

@Entity(
    tableName = "clips",
    indices = [
        Index(value = ["scriptId"]),
        Index(value = ["updatedAtMillis"]),
        Index(value = ["status"])
    ]
)
data class ClipEntity(
    @PrimaryKey val id: String,
    val scriptId: String,
    val jobId: String,
    val title: String,
    val photoSha: String?,
    val voiceSha: String?,
    val alignSha: String?,
    val lipsyncSha: String?,
    val finalSha: String?,
    val status: String,
    val progressFraction: Float,
    val stageDetail: String,
    val errorMessage: String?,
    val durationMs: Long,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB)
    val waveformPeaksBlob: ByteArray?,
    val thumbHash: String?,
    val alignJson: String?,
    val chyronHeadline: String,
    val chyronSubline: String,
    val remoteLipsyncUrl: String?,
    val remoteFinalUrl: String?,
    val exportedUri: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ClipEntity) return false
        return id == other.id &&
            scriptId == other.scriptId &&
            jobId == other.jobId &&
            title == other.title &&
            photoSha == other.photoSha &&
            voiceSha == other.voiceSha &&
            alignSha == other.alignSha &&
            lipsyncSha == other.lipsyncSha &&
            finalSha == other.finalSha &&
            status == other.status &&
            progressFraction == other.progressFraction &&
            stageDetail == other.stageDetail &&
            errorMessage == other.errorMessage &&
            durationMs == other.durationMs &&
            waveformPeaksBlob.contentEquals(other.waveformPeaksBlob) &&
            thumbHash == other.thumbHash &&
            alignJson == other.alignJson &&
            chyronHeadline == other.chyronHeadline &&
            chyronSubline == other.chyronSubline &&
            remoteLipsyncUrl == other.remoteLipsyncUrl &&
            remoteFinalUrl == other.remoteFinalUrl &&
            exportedUri == other.exportedUri &&
            createdAtMillis == other.createdAtMillis &&
            updatedAtMillis == other.updatedAtMillis
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + status.hashCode()
        result = 31 * result + updatedAtMillis.hashCode()
        result = 31 * result + (waveformPeaksBlob?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Lightweight indexed projection for the Clips feed (<1 ms read, never loads alignJson or beats).
 */
data class ClipCardTuple(
    val id: String,
    val scriptId: String,
    val jobId: String,
    val title: String,
    val photoSha: String?,
    val finalSha: String?,
    val status: String,
    val progressFraction: Float,
    val stageDetail: String,
    val errorMessage: String?,
    val durationMs: Long,
    val thumbHash: String?,
    val remoteFinalUrl: String?,
    val updatedAtMillis: Long
)

@Entity(
    tableName = "outbox_ops",
    indices = [Index(value = ["createdAtMillis"])]
)
data class OutboxOpEntity(
    @PrimaryKey val id: String,
    val entityType: String,
    val entityId: String,
    val opType: String,
    val payloadJson: String,
    val attempts: Int = 0,
    val lastError: String? = null,
    val createdAtMillis: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "cas_entries",
    indices = [Index(value = ["lastAccessedMillis"])]
)
data class CasEntryEntity(
    @PrimaryKey val sha256: String,
    val ext: String,
    val sizeBytes: Long,
    val lastAccessedMillis: Long
)

@Dao
interface ScriptDao {
    @Transaction
    @Query("SELECT * FROM scripts ORDER BY updatedAtMillis DESC")
    fun observeAllWithBeats(): Flow<List<ScriptWithBeats>>

    @Transaction
    @Query("SELECT * FROM scripts WHERE id = :id LIMIT 1")
    suspend fun getWithBeats(id: String): ScriptWithBeats?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertScript(script: ScriptEntity)

    @Query("DELETE FROM script_beats WHERE scriptId = :scriptId")
    suspend fun deleteBeatsForScript(scriptId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBeats(beats: List<ScriptBeatEntity>)

    @Transaction
    suspend fun upsertScriptWithBeats(script: ScriptEntity, beats: List<ScriptBeatEntity>) {
        upsertScript(script)
        deleteBeatsForScript(script.id)
        if (beats.isNotEmpty()) {
            insertBeats(beats)
        }
    }

    @Query("DELETE FROM scripts WHERE id = :id")
    suspend fun deleteScriptById(id: String)
}

@Dao
interface ClipDao {
    @Query(
        """
        SELECT id, scriptId, jobId, title, photoSha, finalSha, status, progressFraction,
               stageDetail, errorMessage, durationMs, thumbHash, remoteFinalUrl, updatedAtMillis
        FROM clips
        ORDER BY updatedAtMillis DESC
        """
    )
    fun observeClipCards(): Flow<List<ClipCardTuple>>

    @Query("SELECT * FROM clips WHERE id = :id LIMIT 1")
    fun observeClipById(id: String): Flow<ClipEntity?>

    @Query("SELECT * FROM clips WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ClipEntity?

    @Query("SELECT * FROM clips WHERE jobId = :jobId LIMIT 1")
    suspend fun getByJobId(jobId: String): ClipEntity?

    @Query("SELECT * FROM clips WHERE status = 'READY' ORDER BY updatedAtMillis DESC LIMIT :limit")
    suspend fun listReadyClips(limit: Int): List<ClipEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(clip: ClipEntity)

    @Query("UPDATE clips SET exportedUri = :exportedUri, updatedAtMillis = :updatedAtMillis WHERE id = :clipId")
    suspend fun markExported(clipId: String, exportedUri: String, updatedAtMillis: Long)

    @Query("DELETE FROM clips WHERE id = :clipId")
    suspend fun deleteById(clipId: String)
}

@Dao
interface OutboxDao {
    @Query("SELECT * FROM outbox_ops ORDER BY createdAtMillis ASC")
    suspend fun listPending(): List<OutboxOpEntity>

    @Query("SELECT COUNT(*) FROM outbox_ops")
    fun observePendingCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun enqueue(op: OutboxOpEntity)

    @Query("DELETE FROM outbox_ops WHERE id = :id")
    suspend fun remove(id: String)
}

@Dao
interface CasDao {
    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM cas_entries")
    fun observeTotalBytes(): Flow<Long>

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM cas_entries")
    suspend fun getTotalBytes(): Long

    @Query("SELECT * FROM cas_entries ORDER BY lastAccessedMillis ASC")
    suspend fun listOldestFirst(): List<CasEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: CasEntryEntity)

    @Query("UPDATE cas_entries SET lastAccessedMillis = :timestamp WHERE sha256 = :sha256")
    suspend fun touch(sha256: String, timestamp: Long)

    @Query("DELETE FROM cas_entries WHERE sha256 = :sha256")
    suspend fun delete(sha256: String)

    @Query("DELETE FROM cas_entries")
    suspend fun clearAll()
}

@Database(
    entities = [
        ScriptEntity::class,
        ScriptBeatEntity::class,
        ClipEntity::class,
        OutboxOpEntity::class,
        CasEntryEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AircheckDatabase : RoomDatabase() {
    abstract fun scriptDao(): ScriptDao
    abstract fun clipDao(): ClipDao
    abstract fun outboxDao(): OutboxDao
    abstract fun casDao(): CasDao

    companion object {
        @Volatile
        private var INSTANCE: AircheckDatabase? = null

        fun getInstance(context: Context): AircheckDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AircheckDatabase::class.java,
                    "aircheck_v2.db"
                )
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

object WaveformBlobCodec {
    fun encode(peaks: List<Float>): ByteArray? {
        if (peaks.isEmpty()) return null
        val buf = ByteBuffer.allocate(peaks.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        peaks.forEach { buf.putFloat(it) }
        return buf.array()
    }

    fun decode(blob: ByteArray?): List<Float> {
        if (blob == null || blob.size < 4 || blob.size % 4 != 0) return emptyList()
        val buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        val count = blob.size / 4
        return List(count) { buf.float }
    }
}

class AircheckLocalRepository(private val db: AircheckDatabase) {
    private val json = Json { ignoreUnknownKeys = true }

    val scripts: Flow<List<ScriptProject>> = db.scriptDao().observeAllWithBeats().map { list ->
        list.map { it.toDomain(json) }
    }

    val clipCards: Flow<List<ClipCardRow>> = db.clipDao().observeClipCards().map { rows ->
        rows.map { it.toDomain() }
    }

    val pendingOutboxCount: Flow<Int> = db.outboxDao().observePendingCount()

    fun observeClip(clipId: String): Flow<ClipRecord?> =
        db.clipDao().observeClipById(clipId).map { it?.toDomain(json) }

    suspend fun getScriptById(id: String): ScriptProject? =
        db.scriptDao().getWithBeats(id)?.toDomain(json)

    suspend fun saveScript(project: ScriptProject) {
        val entity = project.toScriptEntity(json)
        val beats = project.beats.mapIndexed { idx, beat ->
            beat.toBeatEntity(project.id, idx)
        }
        db.scriptDao().upsertScriptWithBeats(entity, beats)
    }

    suspend fun deleteScript(id: String) {
        db.scriptDao().deleteScriptById(id)
    }

    suspend fun upsertClip(clip: ClipRecord) {
        db.clipDao().upsert(clip.toEntity(json))
    }

    suspend fun getClipById(id: String): ClipRecord? =
        db.clipDao().getById(id)?.toDomain(json)

    suspend fun getClipByJobId(jobId: String): ClipRecord? =
        db.clipDao().getByJobId(jobId)?.toDomain(json)

    suspend fun listReadyClips(limit: Int): List<ClipRecord> =
        db.clipDao().listReadyClips(limit).map { it.toDomain(json) }

    suspend fun markClipExported(clipId: String, uri: String) {
        db.clipDao().markExported(clipId, uri, System.currentTimeMillis())
    }

    suspend fun deleteClip(clipId: String) {
        db.clipDao().deleteById(clipId)
    }

    suspend fun enqueueOutbox(entityType: String, entityId: String, opType: String, payloadJson: String) {
        db.outboxDao().enqueue(
            OutboxOpEntity(
                id = "${entityType}_${entityId}_$opType",
                entityType = entityType,
                entityId = entityId,
                opType = opType,
                payloadJson = payloadJson,
                createdAtMillis = System.currentTimeMillis()
            )
        )
    }

    suspend fun listPendingOutbox(): List<OutboxOpEntity> = db.outboxDao().listPending()

    suspend fun removeOutbox(id: String) = db.outboxDao().remove(id)
}

private fun ScriptProject.toScriptEntity(json: Json): ScriptEntity = ScriptEntity(
    id = id,
    userId = userId,
    title = title,
    ideaSpark = ideaSpark,
    sourceUrl = sourceUrl,
    targetDurationSec = targetDurationSec,
    backdropPresetId = stageConfig.backdropPresetId,
    customBackdropPrompt = stageConfig.customBackdropPrompt,
    photoSha = stageConfig.photoSha,
    normalizedPhotoSha = stageConfig.normalizedPhotoSha,
    poseGateJson = json.encodeToString(PoseGateResult.serializer(), stageConfig.poseGate),
    stage4Mode = stageConfig.stage4Mode.name,
    chyronHeadline = stageConfig.chyronHeadline,
    chyronSubline = stageConfig.chyronSubline,
    cornerBugText = stageConfig.cornerBugText,
    showBrandBug = stageConfig.showBrandBug,
    voiceSource = voiceSource.name,
    voiceIdOverride = voiceIdOverride,
    masterAudioSha = masterAudioSha,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis
)

private fun ScriptBeat.toBeatEntity(scriptId: String, ordinal: Int): ScriptBeatEntity = ScriptBeatEntity(
    id = id,
    scriptId = scriptId,
    ordinal = ordinal,
    spokenText = spokenText,
    proposedShortText = proposedShortText,
    estSec = durationSec,
    isCut = cut,
    mentionTag = mentionTag,
    depthTextTag = depthTextTag,
    anchorWord = anchorWord,
    evidenceSource = evidenceSource,
    sfxTag = sfxTag,
    phoneticOverride = phoneticOverride,
    bRollPrompt = bRollPrompt,
    bRollSha = bRollSha,
    beatAudioSha = beatAudioSha
)

private fun ScriptWithBeats.toDomain(json: Json): ScriptProject {
    val poseGate = runCatching {
        json.decodeFromString(PoseGateResult.serializer(), script.poseGateJson)
    }.getOrDefault(PoseGateResult())

    val sortedBeats = beats.sortedBy { it.ordinal }.map { b ->
        ScriptBeat(
            id = b.id,
            index = b.ordinal,
            spokenText = b.spokenText,
            proposedShortText = b.proposedShortText,
            durationSec = b.estSec,
            cut = b.isCut,
            mentionTag = b.mentionTag,
            depthTextTag = b.depthTextTag,
            anchorWord = b.anchorWord,
            evidenceSource = b.evidenceSource,
            sfxTag = b.sfxTag,
            phoneticOverride = b.phoneticOverride,
            bRollPrompt = b.bRollPrompt,
            bRollSha = b.bRollSha,
            beatAudioSha = b.beatAudioSha
        )
    }

    return ScriptProject(
        id = script.id,
        userId = script.userId,
        title = script.title,
        ideaSpark = script.ideaSpark,
        sourceUrl = script.sourceUrl,
        targetDurationSec = script.targetDurationSec,
        beats = if (sortedBeats.isEmpty()) listOf(ScriptBeat(index = 0)) else sortedBeats,
        stageConfig = NewsroomStageConfig(
            backdropPresetId = script.backdropPresetId,
            customBackdropPrompt = script.customBackdropPrompt,
            photoSha = script.photoSha,
            normalizedPhotoSha = script.normalizedPhotoSha,
            poseGate = poseGate,
            stage4Mode = Stage4Mode.fromRaw(script.stage4Mode),
            chyronHeadline = script.chyronHeadline,
            chyronSubline = script.chyronSubline,
            cornerBugText = script.cornerBugText,
            showBrandBug = script.showBrandBug
        ),
        voiceSource = VoiceSource.fromRaw(script.voiceSource),
        voiceIdOverride = script.voiceIdOverride,
        masterAudioSha = script.masterAudioSha,
        createdAtMillis = script.createdAtMillis,
        updatedAtMillis = script.updatedAtMillis
    )
}

private fun ClipCardTuple.toDomain(): ClipCardRow = ClipCardRow(
    id = id,
    scriptId = scriptId,
    jobId = jobId,
    title = title,
    photoSha = photoSha,
    finalSha = finalSha,
    status = RenderJobStatus.fromRaw(status),
    progressFraction = progressFraction,
    stageDetail = stageDetail,
    errorMessage = errorMessage,
    durationMs = durationMs,
    thumbHash = thumbHash,
    remoteFinalUrl = remoteFinalUrl,
    updatedAtMillis = updatedAtMillis
)

private fun ClipRecord.toEntity(json: Json): ClipEntity = ClipEntity(
    id = id,
    scriptId = scriptId,
    jobId = jobId,
    title = title,
    photoSha = photoSha,
    voiceSha = voiceSha,
    alignSha = alignSha,
    lipsyncSha = lipsyncSha,
    finalSha = finalSha,
    status = status.name,
    progressFraction = progressFraction,
    stageDetail = stageDetail,
    errorMessage = errorMessage,
    durationMs = durationMs,
    waveformPeaksBlob = WaveformBlobCodec.encode(waveformPeaks),
    thumbHash = thumbHash,
    alignJson = if (alignedWords.isEmpty()) null else json.encodeToString(
        kotlinx.serialization.builtins.ListSerializer(AlignedWord.serializer()),
        alignedWords
    ),
    chyronHeadline = chyronHeadline,
    chyronSubline = chyronSubline,
    remoteLipsyncUrl = remoteLipsyncUrl,
    remoteFinalUrl = remoteFinalUrl,
    exportedUri = exportedUri,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis
)

private fun ClipEntity.toDomain(json: Json): ClipRecord {
    val words = alignJson?.takeIf { it.isNotBlank() }?.let { raw ->
        runCatching {
            json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(AlignedWord.serializer()),
                raw
            )
        }.getOrDefault(emptyList())
    }.orEmpty()

    return ClipRecord(
        id = id,
        scriptId = scriptId,
        jobId = jobId,
        title = title,
        photoSha = photoSha,
        voiceSha = voiceSha,
        alignSha = alignSha,
        lipsyncSha = lipsyncSha,
        finalSha = finalSha,
        status = RenderJobStatus.fromRaw(status),
        progressFraction = progressFraction,
        stageDetail = stageDetail,
        errorMessage = errorMessage,
        durationMs = durationMs,
        waveformPeaks = WaveformBlobCodec.decode(waveformPeaksBlob),
        thumbHash = thumbHash,
        alignedWords = words,
        chyronHeadline = chyronHeadline,
        chyronSubline = chyronSubline,
        remoteLipsyncUrl = remoteLipsyncUrl,
        remoteFinalUrl = remoteFinalUrl,
        exportedUri = exportedUri,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis
    )
}
