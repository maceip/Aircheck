package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.example.data.NewsroomStageConfig
import com.example.data.ScriptBeat
import com.example.data.ScriptProject
import com.example.data.VoiceSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

@Entity(tableName = "script_projects")
data class ScriptProjectEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val title: String,
    val ideaSpark: String,
    val targetDurationSec: Int,
    val beatsJson: String,
    val stageConfigJson: String,
    val voiceSource: String,
    val voiceSelectionId: String,
    val renderedVideoKey: String?,
    val masterAudioKey: String?,
    val exportedUri: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

@Dao
interface ScriptProjectDao {
    @Query("SELECT * FROM script_projects ORDER BY updatedAtMillis DESC")
    fun observeAll(): Flow<List<ScriptProjectEntity>>

    @Query("SELECT * FROM script_projects WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ScriptProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ScriptProjectEntity)

    @Query("DELETE FROM script_projects WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Database(
    entities = [ScriptProjectEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AircheckDatabase : RoomDatabase() {
    abstract fun scriptProjectDao(): ScriptProjectDao

    companion object {
        @Volatile
        private var INSTANCE: AircheckDatabase? = null

        fun getInstance(context: Context): AircheckDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AircheckDatabase::class.java,
                    "aircheck_studio.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}

class LocalProjectRepository(private val dao: ScriptProjectDao) {
    val projects: Flow<List<ScriptProject>> = dao.observeAll().map { entities ->
        entities.map { it.toDomain() }
    }

    suspend fun getById(id: String): ScriptProject? = dao.getById(id)?.toDomain()

    suspend fun save(project: ScriptProject) {
        dao.upsert(project.toEntity())
    }

    suspend fun delete(id: String) {
        dao.deleteById(id)
    }
}

private fun ScriptProject.toEntity(): ScriptProjectEntity {
    val beatsArray = JSONArray()
    beats.forEach { beat ->
        beatsArray.put(
            JSONObject().apply {
                put("id", beat.id)
                put("index", beat.index)
                put("spokenText", beat.spokenText)
                put("proposedShortText", beat.proposedShortText ?: JSONObject.NULL)
                put("durationSec", beat.durationSec)
                put("cut", beat.cut)
                put("mentionTag", beat.mentionTag)
                put("depthTextTag", beat.depthTextTag)
                put("evidenceSource", beat.evidenceSource)
                put("sfxTag", beat.sfxTag)
                put("phoneticOverride", beat.phoneticOverride)
                put("bRollPrompt", beat.bRollPrompt)
                put("bRollImageKey", beat.bRollImageKey ?: JSONObject.NULL)
                put("beatAudioKey", beat.beatAudioKey ?: JSONObject.NULL)
            }
        )
    }

    val stageObj = JSONObject().apply {
        put("backdropPresetId", stageConfig.backdropPresetId)
        put("customBackdropPrompt", stageConfig.customBackdropPrompt)
        put("selfieImageKey", stageConfig.selfieImageKey ?: JSONObject.NULL)
        put("compositedAnchorKey", stageConfig.compositedAnchorKey ?: JSONObject.NULL)
        put("faceValidated", stageConfig.faceValidated)
        put("faceValidationNote", stageConfig.faceValidationNote)
        put("mouthCenterX", stageConfig.mouthCenterX.toDouble())
        put("mouthCenterY", stageConfig.mouthCenterY.toDouble())
        put("mouthWidthNorm", stageConfig.mouthWidthNorm.toDouble())
        put("chyronHeadline", stageConfig.chyronHeadline)
        put("chyronSubline", stageConfig.chyronSubline)
        put("cornerBugText", stageConfig.cornerBugText)
        put("showBrandBug", stageConfig.showBrandBug)
    }

    return ScriptProjectEntity(
        id = id,
        userId = userId,
        title = title,
        ideaSpark = ideaSpark,
        targetDurationSec = targetDurationSec,
        beatsJson = beatsArray.toString(),
        stageConfigJson = stageObj.toString(),
        voiceSource = voiceSource.name,
        voiceSelectionId = voiceSelectionId,
        renderedVideoKey = renderedVideoKey,
        masterAudioKey = masterAudioKey,
        exportedUri = exportedUri,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis
    )
}

private fun ScriptProjectEntity.toDomain(): ScriptProject {
    val parsedBeats = mutableListOf<ScriptBeat>()
    runCatching {
        val arr = JSONArray(beatsJson)
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            parsedBeats.add(
                ScriptBeat(
                    id = obj.optString("id", "beat_$i"),
                    index = obj.optInt("index", i),
                    spokenText = obj.optString("spokenText", ""),
                    proposedShortText = obj.optString("proposedShortText", "").ifBlank { null },
                    durationSec = obj.optInt("durationSec", 5),
                    cut = obj.optBoolean("cut", false),
                    mentionTag = obj.optString("mentionTag", ""),
                    depthTextTag = obj.optString("depthTextTag", ""),
                    evidenceSource = obj.optString("evidenceSource", ""),
                    sfxTag = obj.optString("sfxTag", ""),
                    phoneticOverride = obj.optString("phoneticOverride", ""),
                    bRollPrompt = obj.optString("bRollPrompt", ""),
                    bRollImageKey = obj.optString("bRollImageKey", "").ifBlank { null },
                    beatAudioKey = obj.optString("beatAudioKey", "").ifBlank { null }
                )
            )
        }
    }

    val stage = runCatching {
        val obj = JSONObject(stageConfigJson)
        NewsroomStageConfig(
            backdropPresetId = obj.optString("backdropPresetId", "volt_truss_globe"),
            customBackdropPrompt = obj.optString("customBackdropPrompt", ""),
            selfieImageKey = obj.optString("selfieImageKey", "").ifBlank { null },
            compositedAnchorKey = obj.optString("compositedAnchorKey", "").ifBlank { null },
            faceValidated = obj.optBoolean("faceValidated", false),
            faceValidationNote = obj.optString("faceValidationNote", ""),
            mouthCenterX = obj.optDouble("mouthCenterX", 0.5).toFloat(),
            mouthCenterY = obj.optDouble("mouthCenterY", 0.54).toFloat(),
            mouthWidthNorm = obj.optDouble("mouthWidthNorm", 0.14).toFloat(),
            chyronHeadline = obj.optString("chyronHeadline", ""),
            chyronSubline = obj.optString("chyronSubline", ""),
            cornerBugText = obj.optString("cornerBugText", "AIRCHECK"),
            showBrandBug = obj.optBoolean("showBrandBug", true)
        )
    }.getOrDefault(NewsroomStageConfig())

    return ScriptProject(
        id = id,
        userId = userId,
        title = title,
        ideaSpark = ideaSpark,
        targetDurationSec = targetDurationSec,
        beats = if (parsedBeats.isEmpty()) listOf(ScriptBeat(index = 0)) else parsedBeats,
        stageConfig = stage,
        voiceSource = VoiceSource.fromRaw(voiceSource),
        voiceSelectionId = voiceSelectionId,
        renderedVideoKey = renderedVideoKey,
        masterAudioKey = masterAudioKey,
        exportedUri = exportedUri,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis
    )
}
