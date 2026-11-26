package com.example

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Times the real on-phone run9 voice synthesis on this device. Skipped if the model is not installed. */
@RunWith(AndroidJUnit4::class)
class OnPhoneVoiceLiveTest {
    @Test
    fun run9_synthesizesOnThisPhone_andReportsRealTimeFactor() {
        val voice = ApplicationProvider.getApplicationContext<AircheckApplication>().container.onDeviceVoice
        Log.i("PHONE_VOICE", "missing=${voice.missingFiles()}")
        assumeTrue("run9 voice model not on this phone: ${voice.missingFiles()}", voice.isInstalled())
        val text = "Phones now run large language models locally, so small teams can ship AI features without a cloud bill."
        val t0 = System.currentTimeMillis()
        val wav = voice.synthesize(text)
        val ms = System.currentTimeMillis() - t0
        // 24 kHz mono 16-bit PCM: 48,000 bytes per second after the 44-byte header.
        val audioSec = (wav.length() - 44) / 48_000.0
        Log.i("PHONE_VOICE", "synth ms=$ms audioSec=%.2f rtf=%.2f bytes=${wav.length()}".format(audioSec, ms / 1000.0 / audioSec))
        assertTrue("no audio produced", audioSec > 1.0)
        wav.delete()
    }
}
