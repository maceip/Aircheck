package com.example.ui

import android.content.res.Configuration
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
    fun launcherAdaptiveIconsSwitchCleanlyBetweenLightAndNightModes() {
        val base = ApplicationProvider.getApplicationContext<AircheckApplication>()

        // 1. Light mode (`UI_MODE_NIGHT_NO`): neon-green background + dark/purple beveled glyph
        val lightCfg = Configuration(base.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
        }
        val lightCtx = base.createConfigurationContext(lightCfg)
        val lightIcon = lightCtx.getDrawable(R.mipmap.ic_launcher)
        assertNotNull(lightIcon)
        assertTrue(lightIcon is AdaptiveIconDrawable)
        val lightBmp = render(lightIcon!!)
        assertTrue("Light mode outer background should be neon green", Color.green(lightBmp.getPixel(54, 14)) > 200)
        assertTrue("Light mode top glyph bar should be dark", Color.green(lightBmp.getPixel(54, 36)) < 40)

        // 2. Dark mode (`UI_MODE_NIGHT_YES`): obsidian/navy background + neon-green/purple beveled glyph
        val nightCfg = Configuration(base.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
        }
        val nightCtx = base.createConfigurationContext(nightCfg)
        val nightIcon = nightCtx.getDrawable(R.mipmap.ic_launcher)
        assertNotNull(nightIcon)
        assertTrue(nightIcon is AdaptiveIconDrawable)
        val nightBmp = render(nightIcon!!)
        assertTrue("Dark mode outer background should be dark", Color.green(nightBmp.getPixel(54, 14)) < 40)
        assertTrue("Dark mode top glyph bar should be neon green", Color.green(nightBmp.getPixel(54, 36)) > 180)
    }

    private fun render(drawable: android.graphics.drawable.Drawable): Bitmap {
        val bmp = Bitmap.createBitmap(108, 108, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, 108, 108)
        drawable.draw(canvas)
        return bmp
    }
}
