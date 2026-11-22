package com.example.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64

/**
 * Compact 16-byte image placeholder codec (4x4 RGB565 average grid -> 16 bytes -> Base64).
 * Stored in Room `clips.thumbHash` so the Clips grid renders instant 1-frame placeholders with zero disk I/O.
 */
object ThumbHashCodec {

    private const val GRID = 4

    fun encode(bitmap: Bitmap): String {
        val tiny = Bitmap.createScaledBitmap(bitmap, GRID, GRID, true)
        val out = ByteArray(GRID * GRID)
        var idx = 0
        for (y in 0 until GRID) {
            for (x in 0 until GRID) {
                val px = tiny.getPixel(x, y)
                // Pack RGB332 into 1 byte per cell -> exact 16-byte compact hash
                val r = (Color.red(px) shr 5) and 0x07
                val g = (Color.green(px) shr 5) and 0x07
                val b = (Color.blue(px) shr 6) and 0x03
                out[idx++] = ((r shl 5) or (g shl 2) or b).toByte()
            }
        }
        if (tiny !== bitmap) tiny.recycle()
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decode(encoded: String?): Bitmap? {
        if (encoded.isNullOrBlank()) return null
        val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }.getOrNull() ?: return null
        if (bytes.size < GRID * GRID) return null
        val bmp = Bitmap.createBitmap(GRID, GRID, Bitmap.Config.ARGB_8888)
        var idx = 0
        for (y in 0 until GRID) {
            for (x in 0 until GRID) {
                val v = bytes[idx++].toInt() and 0xFF
                val r = ((v shr 5) and 0x07) * 36
                val g = ((v shr 2) and 0x07) * 36
                val b = (v and 0x03) * 85
                bmp.setPixel(x, y, Color.rgb(r, g, b))
            }
        }
        return Bitmap.createScaledBitmap(bmp, 32, 48, true).also {
            if (it !== bmp) bmp.recycle()
        }
    }
}
