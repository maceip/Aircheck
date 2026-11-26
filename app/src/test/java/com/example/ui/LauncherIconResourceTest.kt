package com.example.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.test.core.app.ApplicationProvider
import com.example.AircheckApplication
import com.example.R
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LauncherIconResourceTest {

    @Test
    fun launcherAdaptiveAndRoundIconsRenderNeonGreenBackgroundAndBlackGlyph() {
        val app = ApplicationProvider.getApplicationContext<AircheckApplication>()
        for (resId in listOf(R.mipmap.ic_launcher, R.mipmap.ic_launcher_round)) {
            val icon = app.getDrawable(resId)
            assertNotNull(icon)
            assertTrue("Expected AdaptiveIconDrawable on API 26+", icon is AdaptiveIconDrawable)

            val bmp = Bitmap.createBitmap(108, 108, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            icon!!.setBounds(0, 0, 108, 108)
            icon.draw(canvas)

            var greenPixels = 0
            var blackPixels = 0
            for (y in 0 until 108) {
                for (x in 0 until 108) {
                    val c = bmp.getPixel(x, y)
                    val r = Color.red(c)
                    val g = Color.green(c)
                    val b = Color.blue(c)
                    if (g > 220 && r in 25..80 && b < 30) greenPixels++
                    if (r < 25 && g < 25 && b < 25 && Color.alpha(c) > 200) blackPixels++
                }
            }
            assertTrue("Expected #31FE01 neon-green adaptive background pixels, got $greenPixels", greenPixels > 1500)
            assertTrue("Expected #000000 foreground glyph pixels, got $blackPixels", blackPixels > 800)
        }
    }
}
