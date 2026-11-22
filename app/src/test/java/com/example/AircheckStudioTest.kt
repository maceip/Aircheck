package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.BrandChannel
import com.example.data.ClipRecord
import com.example.data.RenderJobStatus
import com.example.data.ScriptBeat
import com.example.data.ScriptProject
import com.example.data.local.AircheckDatabase
import com.example.data.local.AircheckLocalRepository
import com.example.data.local.CasMediaStore
import com.example.engine.ThumbHashCodec
import com.example.ui.StudioUiState
import com.example.ui.screens.ScriptScreen
import com.example.ui.theme.AircheckTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AircheckStudioTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun appNameMatchesAircheckIdentity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Aircheck", context.getString(R.string.app_name))
    }

    @Test
    fun roomNormalizedTablesAndCasStoreDeduplicateBySha256() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AircheckDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repo = AircheckLocalRepository(db)
            val cas = CasMediaStore(context, db.casDao(), maxBudgetBytes = 1024L * 1024L)

            val script = ScriptProject(
                id = "s_test_1",
                title = "Blue Ocean Pipeline",
                ideaSpark = "Cloud-first talking head",
                beats = listOf(
                    ScriptBeat(id = "b1", index = 0, spokenText = "First beat line.", anchorWord = "First"),
                    ScriptBeat(id = "b2", index = 1, spokenText = "Second beat line.", anchorWord = "Second")
                )
            )
            repo.saveScript(script)
            val restoredScript = repo.getScriptById("s_test_1")
            assertNotNull(restoredScript)
            assertEquals(2, restoredScript!!.beats.size)
            assertEquals("First", restoredScript.beats[0].anchorWord)

            // Verify CAS deduplication by SHA-256
            val a1 = cas.putBytes("photo-payload-bytes".toByteArray(), "jpg")
            val a2 = cas.putBytes("photo-payload-bytes".toByteArray(), "jpg")
            assertEquals(a1.sha256, a2.sha256)
            assertEquals(a1.file.absolutePath, a2.file.absolutePath)
            assertTrue(a1.file.exists())

            // Verify ClipCardRow projection and waveform blob persistence
            val clip = ClipRecord(
                id = "clip_1",
                scriptId = script.id,
                jobId = "job_1",
                title = script.title,
                photoSha = a1.sha256,
                voiceSha = "voice1234",
                alignSha = "align1234",
                lipsyncSha = "lip1234",
                finalSha = "final1234",
                status = RenderJobStatus.READY,
                progressFraction = 1f,
                stageDetail = "Ready",
                errorMessage = null,
                durationMs = 30_000L,
                waveformPeaks = List(64) { (it + 1) / 64f },
                thumbHash = null,
                alignedWords = emptyList(),
                chyronHeadline = "Headline",
                chyronSubline = "Desk",
                remoteLipsyncUrl = null,
                remoteFinalUrl = "https://storage.googleapis.com/example/final.mp4",
                exportedUri = null,
                createdAtMillis = 100L,
                updatedAtMillis = 200L
            )
            repo.upsertClip(clip)
            val cards = repo.clipCards.first()
            assertEquals(1, cards.size)
            assertEquals("clip_1", cards.first().id)
            assertEquals(64, repo.getClipById("clip_1")!!.waveformPeaks.size)

            // Verify ThumbHash 16-byte round-trip
            val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(184, 255, 0))
            }
            val hash = ThumbHashCodec.encode(bmp)
            val decoded = ThumbHashCodec.decode(hash)
            assertNotNull(decoded)
            assertTrue(decoded!!.width > 0 && decoded.height > 0)
            bmp.recycle()
            decoded.recycle()
        } finally {
            db.close()
        }
    }

    @Test
    fun scriptScreenLaunchesWideOpenWithoutJargonOrDefaultDrawers() {
        var latestSpark = ""
        var sparkAiTriggered = false

        val initialState = StudioUiState(
            activeProject = ScriptProject(
                title = "",
                ideaSpark = "",
                beats = listOf(ScriptBeat(id = "b1", index = 0, spokenText = ""))
            )
        )

        composeTestRule.setContent {
            AircheckTheme(channel = BrandChannel.VOLT_LIME) {
                ScriptScreen(
                    uiState = initialState,
                    onUpdateTitle = {},
                    onUpdateSpark = { latestSpark = it },
                    onSetTargetDuration = {},
                    onToggleHoneSpark = { sparkAiTriggered = true },
                    onUpdateBeatText = { _, _ -> },
                    onUpdateBeatMetadata = { _, _, _, _, _, _, _ -> },
                    onToggleExpandBeat = {},
                    onFactCheckBeat = {},
                    onShorten20Percent = {},
                    onAcceptShortProposal = {},
                    onRejectShortProposal = {},
                    onToggleCutBeat = {},
                    onToggleBeatMicRecord = {},
                    onAddBeat = {},
                    onDeleteBeat = {},
                    onDismissError = {},
                    onPrimaryAction = {}
                )
            }
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("spark_input").assertIsDisplayed()
        composeTestRule.onNodeWithTag("spark_ai_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("beat_input_b1").assertIsDisplayed()
        composeTestRule.onNodeWithTag("script_primary_cta").assertIsDisplayed()

        composeTestRule.onNodeWithText("01 //", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("TITLE //", substring = true).assertDoesNotExist()

        composeTestRule.onNodeWithTag("spark_input").performTextInput("AI chip prices fell 30%")
        composeTestRule.onNodeWithTag("spark_ai_button").performClick()
        composeTestRule.waitForIdle()

        assertTrue(latestSpark.contains("AI chip prices"))
        assertTrue(sparkAiTriggered)
    }
}
