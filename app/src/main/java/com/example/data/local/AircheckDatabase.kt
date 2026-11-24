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
import androidx.room.withTransaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.AlignedWord
import com.example.data.ClipCardRow
import com.example.data.ClipRecord
import com.example.data.CorePipelineSession
import com.example.data.NewsroomLook
import com.example.data.NewsroomStageConfig
import com.example.data.OverlayObjectKind
import com.example.data.PoseGateResult
import com.example.data.RenderJobStatus
import com.example.data.SavedNewsroomOverlay
import com.example.data.ScriptBeat
import com.example.data.ScriptProject
import com.example.data.Stage4Mode
import com.example.data.VoiceSource
import com.voicestudio.edl.PreferenceTelemetryRecord
import com.voicestudio.edl.VoiceProjectManifest
import com.voicestudio.sync.ScriptSynthesisDraft
import com.voicestudio.sync.SyncPreferenceStore
import com.voicestudio.sync.SyncProjectStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong

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

@Entity(
    tableName = "voice_project_manifests",
    indices = [Index(value = ["updatedAtMillis"])]
)
data class VoiceProjectManifestEntity(
    @PrimaryKey val projectId: String,
    val manifestJson: String,
    val activeLoraVersion: Int,
    val globalGapScale: Float,
    val updatedAtMillis: Long
)

@Entity(
    tableName = "voice_preferences",
    indices = [Index(value = ["synced", "timestamp"])]
)
data class VoicePreferenceEntity(
    @PrimaryKey val timestamp: Long,
    val clauseId: String,
    val userAction: String,
    val recordJson: String,
    val synced: Boolean = false
)

@Entity(
    tableName = "saved_newsroom_overlays",
    indices = [Index(value = ["createdAtMillis"])]
)
data class SavedNewsroomOverlayEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val headline: String,
    val subline: String,
    val calloutStat: String,
    val durationSec: Int,
    val a2uiComponentsJson: String,
    val createdAtMillis: Long
)

@Entity(
    tableName = "core_pipeline_sessions",
    indices = [Index(value = ["updatedAtMillis"])]
)
data class CorePipelineSessionEntity(
    @PrimaryKey val id: String,
    val scriptId: String,
    val stage: String,
    val sessionJson: String,
    val updatedAtMillis: Long
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

@Dao
interface VoiceProjectManifestDao {
    @Query("SELECT * FROM voice_project_manifests WHERE projectId = :projectId LIMIT 1")
    fun observeByProjectId(projectId: String): Flow<VoiceProjectManifestEntity?>

    @Query("SELECT * FROM voice_project_manifests WHERE projectId = :projectId LIMIT 1")
    suspend fun getByProjectId(projectId: String): VoiceProjectManifestEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: VoiceProjectManifestEntity)

    @Query("DELETE FROM voice_project_manifests WHERE projectId = :projectId")
    suspend fun deleteByProjectId(projectId: String)
}

@Dao
interface VoicePreferenceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: VoicePreferenceEntity)

    @Query("SELECT * FROM voice_preferences WHERE synced = 0 ORDER BY timestamp ASC LIMIT :limit")
    suspend fun listUnsynced(limit: Int): List<VoicePreferenceEntity>

    @Query("UPDATE voice_preferences SET synced = 1 WHERE timestamp IN (:timestamps)")
    suspend fun markSynced(timestamps: List<Long>)

    @Query("SELECT COUNT(*) FROM voice_preferences WHERE synced = 0")
    fun observeUnsyncedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM voice_preferences WHERE synced = 0")
    suspend fun getUnsyncedCount(): Int
}

@Dao
interface SavedNewsroomOverlayDao {
    @Query("SELECT * FROM saved_newsroom_overlays ORDER BY createdAtMillis DESC")
    fun observeAll(): Flow<List<SavedNewsroomOverlayEntity>>

    @Query("SELECT * FROM saved_newsroom_overlays ORDER BY createdAtMillis DESC")
    suspend fun listAll(): List<SavedNewsroomOverlayEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SavedNewsroomOverlayEntity)

    @Query("DELETE FROM saved_newsroom_overlays WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Entity(
    tableName = "newsroom_looks",
    indices = [Index(value = ["updatedAtMillis"])]
)
data class NewsroomLookEntity(
    @PrimaryKey val id: String,
    val lookJson: String,
    val isDefault: Boolean,
    val updatedAtMillis: Long
)

@Dao
interface NewsroomLookDao {
    @Query("SELECT * FROM newsroom_looks ORDER BY updatedAtMillis DESC")
    fun observeAll(): Flow<List<NewsroomLookEntity>>

    @Query("SELECT * FROM newsroom_looks WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): NewsroomLookEntity?

    @Query("SELECT * FROM newsroom_looks ORDER BY updatedAtMillis DESC")
    suspend fun observeAllOnce(): List<NewsroomLookEntity>

    @Query("SELECT * FROM newsroom_looks WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefault(): NewsroomLookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: NewsroomLookEntity)

    @Query("DELETE FROM newsroom_looks WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface CorePipelineSessionDao {
    @Query("SELECT * FROM core_pipeline_sessions ORDER BY updatedAtMillis DESC LIMIT 1")
    fun observeLatest(): Flow<CorePipelineSessionEntity?>

    @Query("SELECT * FROM core_pipeline_sessions ORDER BY updatedAtMillis DESC LIMIT 1")
    suspend fun getLatest(): CorePipelineSessionEntity?

    @Query("SELECT * FROM core_pipeline_sessions WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): CorePipelineSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CorePipelineSessionEntity)
}

@Database(
    entities = [
        ScriptEntity::class,
        ScriptBeatEntity::class,
        ClipEntity::class,
        OutboxOpEntity::class,
        CasEntryEntity::class,
        VoiceProjectManifestEntity::class,
        VoicePreferenceEntity::class,
        SavedNewsroomOverlayEntity::class,
        CorePipelineSessionEntity::class,
        NewsroomLookEntity::class
    ],
    version = 5,
    exportSchema = false
)
abstract class AircheckDatabase : RoomDatabase() {
    abstract fun scriptDao(): ScriptDao
    abstract fun clipDao(): ClipDao
    abstract fun outboxDao(): OutboxDao
    abstract fun casDao(): CasDao
    abstract fun voiceProjectManifestDao(): VoiceProjectManifestDao
    abstract fun voicePreferenceDao(): VoicePreferenceDao
    abstract fun savedNewsroomOverlayDao(): SavedNewsroomOverlayDao
    abstract fun corePipelineSessionDao(): CorePipelineSessionDao
    abstract fun newsroomLookDao(): NewsroomLookDao

    companion object {
        @Volatile
        private var INSTANCE: AircheckDatabase? = null

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `voice_project_manifests` (
                        `projectId` TEXT NOT NULL,
                        `manifestJson` TEXT NOT NULL,
                        `activeLoraVersion` INTEGER NOT NULL,
                        `globalGapScale` REAL NOT NULL,
                        `updatedAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`projectId`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_voice_project_manifests_updatedAtMillis` " +
                        "ON `voice_project_manifests` (`updatedAtMillis`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `voice_preferences` (
                        `timestamp` INTEGER NOT NULL,
                        `clauseId` TEXT NOT NULL,
                        `userAction` TEXT NOT NULL,
                        `recordJson` TEXT NOT NULL,
                        `synced` INTEGER NOT NULL,
                        PRIMARY KEY(`timestamp`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_voice_preferences_synced_timestamp` " +
                        "ON `voice_preferences` (`synced`, `timestamp`)"
                )
            }
        }

        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `saved_newsroom_overlays` (
                        `id` TEXT NOT NULL,
                        `kind` TEXT NOT NULL,
                        `headline` TEXT NOT NULL,
                        `subline` TEXT NOT NULL,
                        `calloutStat` TEXT NOT NULL,
                        `durationSec` INTEGER NOT NULL,
                        `a2uiComponentsJson` TEXT NOT NULL,
                        `createdAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_saved_newsroom_overlays_createdAtMillis` " +
                        "ON `saved_newsroom_overlays` (`createdAtMillis`)"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `core_pipeline_sessions` (
                        `id` TEXT NOT NULL,
                        `scriptId` TEXT NOT NULL,
                        `stage` TEXT NOT NULL,
                        `sessionJson` TEXT NOT NULL,
                        `updatedAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_core_pipeline_sessions_updatedAtMillis` " +
                        "ON `core_pipeline_sessions` (`updatedAtMillis`)"
                )
            }
        }

        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `newsroom_looks` (
                        `id` TEXT NOT NULL,
                        `lookJson` TEXT NOT NULL,
                        `isDefault` INTEGER NOT NULL,
                        `updatedAtMillis` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_newsroom_looks_updatedAtMillis` " +
                        "ON `newsroom_looks` (`updatedAtMillis`)"
                )
            }
        }

        fun getInstance(context: Context): AircheckDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AircheckDatabase::class.java,
                    "aircheck_v2.db"
                )
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
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

class AircheckLocalRepository(private val db: AircheckDatabase) :
    SyncPreferenceStore,
    SyncProjectStore {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val lastPreferenceTimestamp = AtomicLong(0L)

    val scripts: Flow<List<ScriptProject>> = db.scriptDao().observeAllWithBeats().map { list ->
        list.map { it.toDomain(json) }
    }

    val clipCards: Flow<List<ClipCardRow>> = db.clipDao().observeClipCards().map { rows ->
        rows.map { it.toDomain() }
    }

    val pendingOutboxCount: Flow<Int> = db.outboxDao().observePendingCount()

    val unsyncedPreferenceCount: Flow<Int> = db.voicePreferenceDao().observeUnsyncedCount()

    val savedNewsroomOverlays: Flow<List<SavedNewsroomOverlay>> =
        db.savedNewsroomOverlayDao().observeAll().map { rows ->
            rows.map { it.toDomain() }
        }

    val latestCorePipelineSession: Flow<CorePipelineSession?> =
        db.corePipelineSessionDao().observeLatest().map { entity ->
            entity?.let { decodeCorePipelineSessionOrNull(it.sessionJson) }
        }

    suspend fun listSavedNewsroomOverlays(): List<SavedNewsroomOverlay> =
        db.savedNewsroomOverlayDao().listAll().map { it.toDomain() }

    suspend fun saveNewsroomOverlay(overlay: SavedNewsroomOverlay) {
        db.savedNewsroomOverlayDao().upsert(overlay.toEntity())
    }

    suspend fun deleteNewsroomOverlay(id: String) {
        db.savedNewsroomOverlayDao().deleteById(id)
    }

    val newsroomLooks: Flow<List<NewsroomLook>> =
        db.newsroomLookDao().observeAll().map { rows ->
            rows.mapNotNull { decodeLookOrNull(it) }
        }

    suspend fun newsroomLooksOnce(): List<NewsroomLook> =
        db.newsroomLookDao().observeAllOnce().mapNotNull { decodeLookOrNull(it) }

    suspend fun getNewsroomLook(id: String): NewsroomLook? =
        db.newsroomLookDao().getById(id)?.let { decodeLookOrNull(it) }

    suspend fun getDefaultNewsroomLook(): NewsroomLook? =
        db.newsroomLookDao().getDefault()?.let { decodeLookOrNull(it) }

    suspend fun saveNewsroomLook(look: NewsroomLook) {
        val stamped = look.copy(updatedAtMillis = System.currentTimeMillis())
        db.newsroomLookDao().upsert(
            NewsroomLookEntity(
                id = stamped.id,
                lookJson = json.encodeToString(NewsroomLook.serializer(), stamped),
                isDefault = stamped.isDefault,
                updatedAtMillis = stamped.updatedAtMillis
            )
        )
    }

    /** Marks [id] as the single default look applied by the studio cut. */
    suspend fun setDefaultNewsroomLook(id: String) {
        db.withTransaction {
            val all = db.newsroomLookDao().observeAllOnce()
            all.forEach { entity ->
                val look = decodeLookOrNull(entity) ?: return@forEach
                val shouldBeDefault = entity.id == id
                if (look.isDefault != shouldBeDefault) {
                    saveNewsroomLook(look.copy(isDefault = shouldBeDefault))
                }
            }
        }
    }

    suspend fun deleteNewsroomLook(id: String) {
        db.newsroomLookDao().deleteById(id)
    }

    private fun decodeLookOrNull(entity: NewsroomLookEntity): NewsroomLook? =
        runCatching { json.decodeFromString(NewsroomLook.serializer(), entity.lookJson) }.getOrNull()

    suspend fun getLatestCorePipelineSession(): CorePipelineSession? =
        db.corePipelineSessionDao().getLatest()?.let { decodeCorePipelineSessionOrNull(it.sessionJson) }

    suspend fun saveCorePipelineSession(session: CorePipelineSession) {
        val stamped = session.copy(updatedAtMillis = System.currentTimeMillis())
        val encoded = json.encodeToString(CorePipelineSession.serializer(), stamped)
        db.corePipelineSessionDao().upsert(
            CorePipelineSessionEntity(
                id = stamped.id,
                scriptId = stamped.scriptId,
                stage = stamped.stage.name,
                sessionJson = encoded,
                updatedAtMillis = stamped.updatedAtMillis
            )
        )
    }

    private fun decodeCorePipelineSessionOrNull(rawJson: String): CorePipelineSession? =
        runCatching {
            json.decodeFromString(CorePipelineSession.serializer(), rawJson)
        }.getOrNull()

    fun observeVoiceManifest(projectId: String): Flow<VoiceProjectManifest?> =
        db.voiceProjectManifestDao().observeByProjectId(projectId).map { entity ->
            entity?.let { decodeVoiceManifestOrNull(it.manifestJson) }
        }

    override suspend fun getProjectManifest(projectId: String): VoiceProjectManifest? {
        val entity = db.voiceProjectManifestDao().getByProjectId(projectId) ?: return null
        return decodeVoiceManifestOrNull(entity.manifestJson)
    }

    override suspend fun getScriptDraftForSynthesis(projectId: String): ScriptSynthesisDraft? {
        val script = getScriptById(projectId) ?: return null
        val activeBeats = script.activeBeats
        val draftText = activeBeats.joinToString(" ") { it.spokenText.trim() }.trim()
        if (draftText.isEmpty()) return null
        val overrides = linkedMapOf<String, String>()
        for (beat in activeBeats) {
            val raw = beat.phoneticOverride.trim()
            if (raw.isEmpty()) continue
            raw.split(";", "\n").forEach { entry ->
                val pair = entry.trim()
                val sepIdx = pair.indexOf('=').takeIf { it > 0 } ?: pair.indexOf(':').takeIf { it > 0 }
                if (sepIdx != null && sepIdx < pair.lastIndex) {
                    val key = pair.substring(0, sepIdx).trim()
                    val ipa = pair.substring(sepIdx + 1).trim()
                    if (key.isNotEmpty() && ipa.isNotEmpty()) {
                        overrides[key] = ipa
                    }
                }
            }
        }
        return ScriptSynthesisDraft(
            projectId = projectId,
            rawDraftText = draftText,
            phoneticOverrides = overrides
        )
    }

    override suspend fun saveProjectManifest(manifest: VoiceProjectManifest) {
        val encoded = json.encodeToString(VoiceProjectManifest.serializer(), manifest)
        db.voiceProjectManifestDao().upsert(
            VoiceProjectManifestEntity(
                projectId = manifest.projectId,
                manifestJson = encoded,
                activeLoraVersion = manifest.activeLoraVersion,
                globalGapScale = manifest.globalGapScale,
                updatedAtMillis = System.currentTimeMillis()
            )
        )
    }

    override suspend fun appendPreference(record: PreferenceTelemetryRecord) {
        val uniqueTs = lastPreferenceTimestamp.updateAndGet { prev ->
            if (record.timestamp > prev) record.timestamp else prev + 1L
        }
        val stamped = if (uniqueTs == record.timestamp) record else record.copy(timestamp = uniqueTs)
        val encoded = json.encodeToString(PreferenceTelemetryRecord.serializer(), stamped)
        db.voicePreferenceDao().insert(
            VoicePreferenceEntity(
                timestamp = stamped.timestamp,
                clauseId = stamped.clauseId,
                userAction = stamped.userAction.name,
                recordJson = encoded,
                synced = false
            )
        )
    }

    override suspend fun listUnsyncedPreferences(limit: Int): List<PreferenceTelemetryRecord> {
        return db.voicePreferenceDao().listUnsynced(limit).mapNotNull { entity ->
            runCatching {
                json.decodeFromString(PreferenceTelemetryRecord.serializer(), entity.recordJson)
            }.getOrNull()
        }
    }

    override suspend fun markPreferencesSynced(timestamps: List<Long>) {
        if (timestamps.isEmpty()) return
        db.voicePreferenceDao().markSynced(timestamps)
    }

    private fun decodeVoiceManifestOrNull(rawJson: String): VoiceProjectManifest? =
        runCatching {
            json.decodeFromString(VoiceProjectManifest.serializer(), rawJson)
        }.getOrNull()

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

private fun SavedNewsroomOverlay.toEntity(): SavedNewsroomOverlayEntity =
    SavedNewsroomOverlayEntity(
        id = id,
        kind = kind.name,
        headline = headline,
        subline = subline,
        calloutStat = calloutStat,
        durationSec = durationSec,
        a2uiComponentsJson = a2uiComponentsJson,
        createdAtMillis = createdAtMillis
    )

private fun SavedNewsroomOverlayEntity.toDomain(): SavedNewsroomOverlay =
    SavedNewsroomOverlay(
        id = id,
        kind = OverlayObjectKind.fromRaw(kind),
        headline = headline,
        subline = subline,
        calloutStat = calloutStat,
        durationSec = durationSec,
        a2uiComponentsJson = a2uiComponentsJson,
        createdAtMillis = createdAtMillis
    )
