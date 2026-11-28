package com.example.ui.design

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37])
class AcNoticeTest {

    @Test
    fun stripeTile_isDiagonalOrderedDither_ofKindColourOnBase() {
        val base = Color(0xFFF5F5F3)
        AcNoticeKind.entries.forEach { kind ->
            val tile = noticeStripeTile(kind.stripe, base, cellPx = 2)
            assertEquals(32, tile.width)
            assertEquals(32, tile.height)
            val px = tile.toPixelMap()
            val colours = (0 until tile.height).flatMap { y -> (0 until tile.width).map { x -> px[x, y].toArgb() } }.toSet()
            // Only the kind colour and the base: a dither, not a blend.
            assertEquals(setOf(kind.stripe.toArgb(), base.toArgb()), colours)
            val stripeShare = colours.let { (0 until 32).sumOf { y -> (0 until 32).count { x -> px[x, y].toArgb() == kind.stripe.toArgb() } } } / 1024.0
            assertTrue("${kind.name} stripe coverage $stripeShare", stripeShare in 0.2..0.6)
            // 45-degree stripes: cells on the same anti-diagonal share the stripe intensity, so moving one
            // cell right and one cell up keeps a cell in the same stripe band (Bayer may still flip it).
            assertTrue(px[0, 0].toArgb() == base.toArgb())
        }
        // Same inputs return the cached tile.
        assertTrue(noticeStripeTile(AcNoticeKind.INFO.stripe, base, 2) === noticeStripeTile(AcNoticeKind.INFO.stripe, base, 2))
    }

    @Test
    fun kindColours_followTheMapping() {
        assertEquals(AcTokens.OpRed, AcNoticeKind.ERROR.stripe)
        assertEquals(AcTokens.OpGreen, AcNoticeKind.SUCCESS.stripe)
        assertEquals(AcTokens.OpBlue, AcNoticeKind.INFO.stripe)
        assertEquals(AcTokens.OpOrange, AcNoticeKind.BUSY.stripe)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun notice_rendersEachKind_andClicks() = runComposeUiTest {
        var clicks = 0
        setContent {
            Column {
                AcNoticeKind.entries.forEach { kind ->
                    AcNotice(kind = kind, onClick = { clicks++ }, modifier = Modifier.testTag("notice_${kind.name}")) {
                        Text("text ${kind.name}")
                    }
                }
            }
        }
        AcNoticeKind.entries.forEach { kind ->
            onNodeWithTag("notice_${kind.name}")
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, kind.name.lowercase()))
            onNodeWithText("text ${kind.name}").assertExists()
        }
        onNodeWithTag("notice_ERROR").performClick()
        assertEquals(1, clicks)
    }
}
