package com.example.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.math.max

class MediaStorageManager(private val context: Context) {

    private val mediaDir: File
        get() = File(context.filesDir, "aircheck_media").apply { if (!exists()) mkdirs() }

    fun fileForKey(key: String?): File? {
        if (key.isNullOrBlank()) return null
        val file = File(mediaDir, key)
        return if (file.exists()) file else null
    }

    fun createMediaFile(prefix: String, extension: String): Pair<String, File> {
        val key = "${prefix}_${UUID.randomUUID().toString().replace("-", "").take(10)}.$extension"
        return key to File(mediaDir, key)
    }

    suspend fun importImageFromUri(uri: Uri, prefix: String = "selfie", maxDimension: Int = 1280): Pair<String, Bitmap> =
        withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            val largest = max(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
            var sampleSize = 1
            while (largest / sampleSize > maxDimension) {
                sampleSize *= 2
            }
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val decoded = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            } ?: throw IllegalStateException("Unable to decode selected image.")

            val scaled = scaleBitmapDown(decoded, maxDimension)
            if (scaled !== decoded) decoded.recycle()

            val (key, file) = createMediaFile(prefix, "jpg")
            FileOutputStream(file).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            key to scaled
        }

    suspend fun saveBitmap(bitmap: Bitmap, prefix: String): String = withContext(Dispatchers.IO) {
        val (key, file) = createMediaFile(prefix, "jpg")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        key
    }

    suspend fun loadBitmap(key: String?): Bitmap? = withContext(Dispatchers.IO) {
        val file = fileForKey(key) ?: return@withContext null
        BitmapFactory.decodeFile(file.absolutePath)
    }

    suspend fun loadDrawableBitmap(drawableResId: Int, maxDimension: Int = 1024): Bitmap? =
        withContext(Dispatchers.IO) {
            val raw = BitmapFactory.decodeResource(context.resources, drawableResId) ?: return@withContext null
            val scaled = scaleBitmapDown(raw, maxDimension)
            if (scaled !== raw) raw.recycle()
            scaled
        }

    private fun scaleBitmapDown(src: Bitmap, maxDim: Int): Bitmap {
        val w = src.width
        val h = src.height
        val longest = max(w, h)
        if (longest <= maxDim) return src
        val ratio = maxDim.toFloat() / longest.toFloat()
        val nw = (w * ratio).toInt().coerceAtLeast(1)
        val nh = (h * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, nw, nh, true)
    }
}
