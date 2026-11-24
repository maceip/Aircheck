package com.example.engine

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.example.data.local.CasMediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream

data class ExportVideoResult(
    val mediaStoreUri: Uri,
    val shareIntent: Intent,
    val cachedSha256: String
)

/**
 * Zero-copy MediaStore (`Movies/Aircheck`) exporter & FileProvider share builder
 * backed by immutable SHA-256 CAS MP4 artifacts.
 */
class ClipExporter(
    private val context: Context,
    private val casStore: CasMediaStore,
    private val okHttpClient: OkHttpClient
) {
    suspend fun exportClip(
        finalSha: String?,
        remoteFinalUrl: String?,
        projectTitle: String
    ): Result<ExportVideoResult> = withContext(Dispatchers.IO) {
        runCatching {
            val (sha, mp4File) = resolveLocalMp4(finalSha, remoteFinalUrl)
            if (!mp4File.exists() || mp4File.length() == 0L) {
                throw IllegalStateException("Rendered MP4 is missing from local cache.")
            }

            val safeName = projectTitle
                .replace(Regex("[^a-zA-Z0-9_\\- ]"), "")
                .trim()
                .replace(" ", "_")
                .ifBlank { "Aircheck_Broadcast" }
                .take(40)
            val fileName = "${safeName}_${sha.take(8)}.mp4"

            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Aircheck")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore rejected MP4 video insert.")

            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(mp4File).use { input ->
                    input.copyTo(out)
                }
            } ?: throw IllegalStateException("Unable to write MP4 bytes to MediaStore.")

            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)

            val shareUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                mp4File
            )
            val label = projectTitle.ifBlank { "Aircheck Video" }
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                clipData = ClipData.newRawUri(label, shareUri)
                putExtra(Intent.EXTRA_STREAM, shareUri)
                putExtra(Intent.EXTRA_SUBJECT, label)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            ExportVideoResult(mediaStoreUri = uri, shareIntent = shareIntent, cachedSha256 = sha)
        }
    }

    private suspend fun resolveLocalMp4(finalSha: String?, remoteUrl: String?): Pair<String, File> {
        val existing = casStore.fileForSha(finalSha, "mp4")
        if (existing != null && finalSha != null) {
            return finalSha to existing
        }
        val url = remoteUrl?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("No rendered MP4 available to export yet.")

        val request = Request.Builder().url(url).get().build()
        val bytes = okHttpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Failed to download rendered MP4 (HTTP ${resp.code}).")
            }
            resp.body?.bytes() ?: throw IllegalStateException("Empty MP4 download response.")
        }
        val stored = casStore.putBytes(bytes, "mp4")
        return stored.sha256 to stored.file
    }
}
