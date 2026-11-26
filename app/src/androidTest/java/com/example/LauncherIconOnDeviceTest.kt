package com.example

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
    fun installedApplicationIconRendersNeonGreenAndBlackGlyphOnDevice() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val icon = ctx.packageManager.getApplicationIcon(ctx.packageName)
        assertTrue("Expected AdaptiveIconDrawable on Android 17", icon is AdaptiveIconDrawable)

        val bmp = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        icon.setBounds(0, 0, 256, 256)
        icon.draw(canvas)

        var greenPixels = 0
        var blackPixels = 0
        for (y in 0 until 256) {
            for (x in 0 until 256) {
                val c = bmp.getPixel(x, y)
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                if (g > 220 && r in 25..80 && b < 30) greenPixels++
                if (r < 25 && g < 25 && b < 25 && Color.alpha(c) > 200) blackPixels++
            }
        }
        val outFile = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "rendered_launcher_icon.png")
        FileOutputStream(outFile).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

        assertTrue("Expected neon-green #31FE01 pixels > 8000, got $greenPixels", greenPixels > 8000)
        assertTrue("Expected black #000000 glyph pixels > 8000, got $blackPixels", blackPixels > 8000)
    }
}
