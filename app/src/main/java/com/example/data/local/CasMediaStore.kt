package com.example.data.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

data class CasStoredAsset(
    val sha256: String,
    val ext: String,
    val file: File,
    val sizeBytes: Long
)

/**
 * Immutable Content-Addressed Media Storage (`filesDir/cas/{sha256_prefix}/{sha256}.{ext}`)
 * with a Room-tracked 1.5 GB LRU eviction budget.
 */
class CasMediaStore(
    private val context: Context,
    private val casDao: CasDao,
    private val maxBudgetBytes: Long = DEFAULT_LRU_BUDGET_BYTES
) {
    private val casRoot: File
        get() = File(context.filesDir, "cas").apply { if (!exists()) mkdirs() }

    val totalBytesFlow: Flow<Long> = casDao.observeTotalBytes()

    fun fileForSha(sha256: String?, ext: String): File? {
        if (sha256.isNullOrBlank() || sha256.length < 8) return null
        val cleanExt = ext.trimStart('.').lowercase()
        val f = File(File(casRoot, sha256.take(2)), "$sha256.$cleanExt")
        return if (f.exists() && f.length() > 0L) f else null
    }

    suspend fun putBytes(bytes: ByteArray, ext: String): CasStoredAsset =
        withContext(Dispatchers.IO) {
            val cleanExt = ext.trimStart('.').lowercase()
            val sha = computeSha256Hex(bytes)
            val shard = File(casRoot, sha.take(2)).apply { if (!exists()) mkdirs() }
            val target = File(shard, "$sha.$cleanExt")

            if (!target.exists() || target.length() != bytes.size.toLong()) {
                val tmp = File(shard, "$sha.$cleanExt.tmp")
                FileOutputStream(tmp).use { it.write(bytes) }
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
            }

            val now = System.currentTimeMillis()
            casDao.upsert(
                CasEntryEntity(
                    sha256 = sha,
                    ext = cleanExt,
                    sizeBytes = target.length(),
                    lastAccessedMillis = now
                )
            )
            enforceLruBudgetLocked(protectSha = sha)
            CasStoredAsset(
                sha256 = sha,
                ext = cleanExt,
                file = target,
                sizeBytes = target.length()
            )
        }

    suspend fun putFile(sourceFile: File, ext: String): CasStoredAsset = withContext(Dispatchers.IO) {
        val cleanExt = ext.trimStart('.').lowercase()
        val sha = computeSha256Hex(sourceFile)
        val shard = File(casRoot, sha.take(2)).apply { if (!exists()) mkdirs() }
        val target = File(shard, "$sha.$cleanExt")

        if (!target.exists() || target.length() != sourceFile.length()) {
            val tmp = File(shard, "$sha.$cleanExt.tmp")
            sourceFile.copyTo(tmp, overwrite = true)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }

        val now = System.currentTimeMillis()
        casDao.upsert(
            CasEntryEntity(
                sha256 = sha,
                ext = cleanExt,
                sizeBytes = target.length(),
                lastAccessedMillis = now
            )
        )
        enforceLruBudgetLocked(protectSha = sha)
        CasStoredAsset(sha256 = sha, ext = cleanExt, file = target, sizeBytes = target.length())
    }

    suspend fun createTempScratchFile(prefix: String, ext: String): File = withContext(Dispatchers.IO) {
        val scratchDir = File(context.cacheDir, "aircheck_scratch").apply { if (!exists()) mkdirs() }
        File.createTempFile(prefix, ".${ext.trimStart('.')}", scratchDir)
    }

    suspend fun clearAllCache() = withContext(Dispatchers.IO) {
        casRoot.listFiles()?.forEach { it.deleteRecursively() }
        casDao.clearAll()
    }

    private suspend fun enforceLruBudgetLocked(protectSha: String?) {
        var total = casDao.getTotalBytes()
        if (total <= maxBudgetBytes) return
        val entries = casDao.listOldestFirst()
        for (entry in entries) {
            if (total <= maxBudgetBytes) break
            if (entry.sha256 == protectSha) continue
            val f = File(File(casRoot, entry.sha256.take(2)), "${entry.sha256}.${entry.ext}")
            if (f.exists()) f.delete()
            casDao.delete(entry.sha256)
            total -= entry.sizeBytes
        }
    }

    companion object {
        const val DEFAULT_LRU_BUDGET_BYTES: Long = 1_500L * 1024L * 1024L

        fun computeSha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }

        fun computeSha256Hex(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buf = ByteArray(8192)
                var read = input.read(buf)
                while (read > 0) {
                    md.update(buf, 0, read)
                    read = input.read(buf)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
