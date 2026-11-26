package com.example

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class LauncherIconOnDeviceTest {

    @Test
    fun installedApplicationIconRendersBothLightAndNightVariantsOnDevice() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext

        val lightCfg = Configuration(ctx.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_NO
        }
        val nightCfg = Configuration(ctx.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
        }

        val lightIcon = ctx.createConfigurationContext(lightCfg).getDrawable(R.mipmap.ic_launcher)!!
        val nightIcon = ctx.createConfigurationContext(nightCfg).getDrawable(R.mipmap.ic_launcher)!!
        assertTrue(lightIcon is AdaptiveIconDrawable)
        assertTrue(nightIcon is AdaptiveIconDrawable)

        val lightBmp = render(lightIcon, 256)
        val nightBmp = render(nightIcon, 256)

        val dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
        FileOutputStream(File(dir, "rendered_launcher_light.png")).use {
            lightBmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        FileOutputStream(File(dir, "rendered_launcher_night.png")).use {
            nightBmp.compress(Bitmap.CompressFormat.PNG, 100, it)
        }

        assertTrue("Light mode background must be neon green", Color.green(lightBmp.getPixel(128, 32)) > 200)
        assertTrue("Light mode glyph must be dark", Color.green(lightBmp.getPixel(128, 86)) < 40)
        assertTrue("Dark mode background must be dark", Color.green(nightBmp.getPixel(128, 32)) < 40)
        assertTrue("Dark mode glyph must be neon green", Color.green(nightBmp.getPixel(128, 86)) > 180)
    }

    private fun render(drawable: android.graphics.drawable.Drawable, px: Int): Bitmap {
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, px, px)
        drawable.draw(canvas)
        return bmp
    }
}
