package com.example

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ui.clipstudio.StudioProgressDisplay
import com.example.ui.theme.AircheckTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies [StudioProgressDisplay] renders live substage labels, real elapsed clock updates,
 * and real milestone percentages without stalling at a static 60% state.
 */
@RunWith(AndroidJUnit4::class)
class StudioProgressDisplayUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun progressDisplayRendersLiveRigMilestonesAndElapsedClock() {
        composeRule.mainClock.autoAdvance = false
        var label by mutableStateOf("Uploading voice to HeyGen · 0:02")
        var fraction by mutableFloatStateOf(0.63f)

        composeRule.setContent {
            AircheckTheme {
                StudioProgressDisplay(
                    label = label,
                    progressFraction = fraction
                )
            }
        }
        composeRule.mainClock.advanceTimeBy(250L)

        composeRule.onNodeWithTag("cs_busy_box").assertIsDisplayed()
        composeRule.onNodeWithText("Uploading voice to HeyGen · 0:02").assertIsDisplayed()
        composeRule.onNodeWithText("63%").assertIsDisplayed()

        composeRule.runOnUiThread {
            label = "HeyGen 2k queued · 0:11"
            fraction = 0.68f
        }
        composeRule.mainClock.advanceTimeBy(250L)
        composeRule.onNodeWithText("HeyGen 2k queued · 0:11").assertIsDisplayed()
        composeRule.onNodeWithText("68%").assertIsDisplayed()

        composeRule.runOnUiThread {
            label = "HeyGen 2k rendering · 1:24"
            fraction = 0.72f
        }
        composeRule.mainClock.advanceTimeBy(250L)
        composeRule.onNodeWithText("HeyGen 2k rendering · 1:24").assertIsDisplayed()
        composeRule.onNodeWithText("72%").assertIsDisplayed()

        composeRule.runOnUiThread {
            label = "Downloading HeyGen 2k video · 2:03"
            fraction = 0.85f
        }
        composeRule.mainClock.advanceTimeBy(250L)
        composeRule.onNodeWithText("Downloading HeyGen 2k video · 2:03").assertIsDisplayed()
        composeRule.onNodeWithText("85%").assertIsDisplayed()

        composeRule.runOnUiThread {
            label = "Checking A/V sync · 2:07"
            fraction = 0.92f
        }
        composeRule.mainClock.advanceTimeBy(250L)
        composeRule.onNodeWithText("Checking A/V sync · 2:07").assertIsDisplayed()
        composeRule.onNodeWithText("92%").assertIsDisplayed()
    }
}
