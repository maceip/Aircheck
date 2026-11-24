package com.example.engine

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.draft.VadAudioCaptureEngine
import com.voicestudio.edl.OtaArtifactFormat
import com.voicestudio.edl.OtaArtifactRole
import com.voicestudio.edl.OtaBundleManifest
import com.voicestudio.edl.OtaModelArtifact
import com.voicestudio.edl.OtaUpdateCadence
import com.voicestudio.edl.SYNTHESIS_SAMPLE_RATE_HZ
import com.voicestudio.sync.OtaBundleSyncManager
import com.voicestudio.sync.VoiceStudioRuntimeRegistry
import com.voicestudio.verifier.AudioDspMetrics
import com.voicestudio.verifier.OrtDnsmosP808Engine
import com.voicestudio.verifier.OrtWeSpeakerEngine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class VoiceArtifactInspection(
    val role: OtaArtifactRole,
    val label: String,
    val expectedFilename: String,
    val exists: Boolean,
    val sizeBytes: Long,
    val sha256Verified: Boolean?,
    val path: String
)

data class VoiceSampleRecord(
    val fileName: String,
    val path: String,
    val sizeBytes: Long,
    val durationSec: Double,
    val sampleRateHz: Int,
    val updatedAtMillis: Long
)

data class LocalLoraRecord(
    val fileName: String,
    val path: String,
    val sizeBytes: Long,
    val sha256Hex: String,
    val isActiveInBundle: Boolean,
    val updatedAtMillis: Long
)

data class VoiceQualityReport(
    val hasAudio: Boolean,
    val inspectedFileName: String?,
    val inspectedFilePath: String?,
    val sampleRateHz: Int,
    val durationSec: Double,
    val rmsDbfs: Float?,
    val peakDbfs: Float?,
    val tail80msRmsDbfs: Float?,
    val clippedSampleCount: Int,
    val dnsmosP808: Float?,
    val speakerCosineSim: Float?,
    val summaryMessage: String
)

data class VoiceStorageSnapshot(
    val bundlesDirPath: String,
    val voiceCareDirPath: String,
    val activeBundleDirPath: String?,
    val bundleVersion: String?,
    val activeLoraVersion: Int,
    val presentArtifactCount: Int,
    val totalExpectedArtifacts: Int,
    val totalVoiceBytesOnPhone: Long,
    val artifacts: List<VoiceArtifactInspection>,
    val samples: List<VoiceSampleRecord>,
    val importedFiles: List<LocalLoraRecord>,
    val lastVerifiedChecksums: Boolean
)

/**
 * Strictly phone-only manager for inspecting, importing, verifying, and quality-checking
 * on-phone VoiceStudio models, LoRA weights, and enrollment samples.
 *
 * Constitutional Law 2 & User Mandate:
 * - Zero Mac network sync, zero HTTP clients, zero cloud fallback.
 * - Operates exclusively under `context.noBackupFilesDir/bundles` and `context.noBackupFilesDir/voice_care`.
 * - Every reported size, SHA-256 digest, duration, dBFS level, DNSMOS score, and WeSpeaker cosine
 *   similarity is measured directly from real local files on the phone.
 */
class VoiceModelCareManager(
    private val context: Context,
    private val otaBundleSyncManager: OtaBundleSyncManager,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val careMutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

    val bundlesRootDir: File
        get() = File(context.noBackupFilesDir, OtaBundleSyncManager.BUNDLES_DIR_NAME)

    val voiceCareRootDir: File
        get() = File(context.noBackupFilesDir, VOICE_CARE_DIR_NAME)

    val samplesDir: File
        get() = File(voiceCareRootDir, SAMPLES_SUBDIR_NAME)

    val lorasDir: File
        get() = File(voiceCareRootDir, LORAS_SUBDIR_NAME)

    fun nextSampleOutputFile(): File {
        samplesDir.mkdirs()
        return File(samplesDir, "voice_sample_${System.currentTimeMillis()}.wav")
    }

    suspend fun writeWavSampleForCare(
        pcm16k: ShortArray,
        fileName: String? = null
    ): File = withContext(ioDispatcher) {
        require(pcm16k.isNotEmpty()) { "Voice sample PCM must not be empty." }
        samplesDir.mkdirs()
        val safeName = sanitizeFilename(
            raw = fileName ?: "voice_sample_${System.currentTimeMillis()}.wav",
            defaultExt = ".wav"
        )
        val out = File(samplesDir, safeName)
        VadAudioCaptureEngine.writePcm16Wav(out, pcm16k, VadAudioCaptureEngine.SAMPLE_RATE_HZ)
    }

    suspend fun deleteVoiceSample(fileName: String): Boolean = careMutex.withLock {
        withContext(ioDispatcher) {
            val clean = sanitizeFilename(fileName, defaultExt = ".wav")
            val target = File(samplesDir, clean)
            if (target.isFile && target.canonicalPath.startsWith(samplesDir.canonicalPath)) {
                target.delete()
            } else {
                false
            }
        }
    }

    suspend fun inspectLocalVoiceStorage(verifyChecksums: Boolean = false): VoiceStorageSnapshot =
        careMutex.withLock {
            withContext(ioDispatcher) {
                voiceCareRootDir.mkdirs()
                samplesDir.mkdirs()
                lorasDir.mkdirs()

                val otaState = otaBundleSyncManager.refreshLocalState("")
                val manifest = otaState.activeManifest
                val activeDir = otaState.installedDirPath?.let(::File)?.takeIf { it.isDirectory }
                    ?: findFallbackLocalBundleDir()

                val activeLoraName = manifest?.artifacts
                    ?.firstOrNull { it.role == OtaArtifactRole.VOICE_LORA_GGUF }
                    ?.filename

                val artifacts = ROLE_SPECS.map { spec ->
                    val manifestArtifact = manifest?.artifacts?.firstOrNull { it.role == spec.role }
                    val expectedName = manifestArtifact?.filename ?: spec.defaultFilename
                    val candidate = resolveLocalFileForRole(
                        role = spec.role,
                        expectedName = expectedName,
                        activeDir = activeDir
                    )
                    val exists = candidate != null && candidate.exists() && candidate.length() > 0L
                    val sizeBytes = when {
                        candidate == null -> 0L
                        candidate.isDirectory -> directoryTotalBytes(candidate)
                        candidate.isFile -> candidate.length()
                        else -> 0L
                    }
                    val shaVerified = if (verifyChecksums && exists && candidate != null && candidate.isFile && manifestArtifact != null) {
                        candidate.length() == manifestArtifact.sizeBytes &&
                            OtaBundleSyncManager.computeSha256Hex(candidate) ==
                            manifestArtifact.sha256.lowercase(Locale.US)
                    } else {
                        null
                    }
                    VoiceArtifactInspection(
                        role = spec.role,
                        label = spec.label,
                        expectedFilename = expectedName,
                        exists = exists,
                        sizeBytes = sizeBytes,
                        sha256Verified = shaVerified,
                        path = candidate?.absolutePath
                            ?: File(activeDir ?: bundlesRootDir, expectedName).absolutePath
                    )
                }

                val samples = samplesDir.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".wav", ignoreCase = true) && it.length() >= 44L }
                    ?.sortedByDescending { it.lastModified() }
                    ?.mapNotNull { file ->
                        val parsed = runCatching { inspectWavHeader(file) }.getOrNull() ?: return@mapNotNull null
                        VoiceSampleRecord(
                            fileName = file.name,
                            path = file.absolutePath,
                            sizeBytes = file.length(),
                            durationSec = parsed.durationSec,
                            sampleRateHz = parsed.sampleRateHz,
                            updatedAtMillis = file.lastModified()
                        )
                    }
                    .orEmpty()

                val imported = lorasDir.listFiles()
                    ?.filter { it.isFile && !it.name.endsWith(".tmp") && it.length() > 0L }
                    ?.sortedByDescending { it.lastModified() }
                    ?.map { file ->
                        val sha = OtaBundleSyncManager.computeSha256Hex(file)
                        LocalLoraRecord(
                            fileName = file.name,
                            path = file.absolutePath,
                            sizeBytes = file.length(),
                            sha256Hex = sha,
                            isActiveInBundle = activeLoraName != null && activeLoraName == file.name,
                            updatedAtMillis = file.lastModified()
                        )
                    }
                    .orEmpty()

                val totalBytes = directoryTotalBytes(bundlesRootDir) + directoryTotalBytes(voiceCareRootDir)

                VoiceStorageSnapshot(
                    bundlesDirPath = bundlesRootDir.absolutePath,
                    voiceCareDirPath = voiceCareRootDir.absolutePath,
                    activeBundleDirPath = activeDir?.absolutePath,
                    bundleVersion = manifest?.bundleVersion,
                    activeLoraVersion = otaState.activeLoraVersion,
                    presentArtifactCount = artifacts.count { it.exists },
                    totalExpectedArtifacts = artifacts.size,
                    totalVoiceBytesOnPhone = totalBytes,
                    artifacts = artifacts,
                    samples = samples,
                    importedFiles = imported,
                    lastVerifiedChecksums = verifyChecksums
                )
            }
        }

    suspend fun importLocalModelOrLoraUri(
        uri: Uri,
        fallbackName: String? = null
    ): Result<LocalLoraRecord> = careMutex.withLock {
        withContext(ioDispatcher) {
            runCatching {
                lorasDir.mkdirs()
                val resolvedName = queryDisplayName(uri)
                    ?: fallbackName
                    ?: uri.lastPathSegment?.substringAfterLast('/')
                    ?: "voice_lora_${System.currentTimeMillis()}.gguf"
                val safeName = sanitizeFilename(resolvedName, defaultExt = ".gguf")
                val destFile = File(lorasDir, safeName)
                val tmpFile = File(lorasDir, "$safeName.tmp")
                if (tmpFile.exists()) tmpFile.delete()

                val input = context.contentResolver.openInputStream(uri)
                    ?: error("Unable to open selected phone file: $uri")
                input.use { stream ->
                    FileOutputStream(tmpFile).use { out ->
                        stream.copyTo(out, COPY_BUFFER_BYTES)
                        out.flush()
                        out.fd.sync()
                    }
                }
                promoteImportedStagingFile(tmpFile, destFile)
            }
        }
    }

    suspend fun importLocalModelOrLoraFile(
        sourceFile: File,
        targetName: String = sourceFile.name
    ): Result<LocalLoraRecord> = careMutex.withLock {
        withContext(ioDispatcher) {
            runCatching {
                require(sourceFile.isFile && sourceFile.canRead() && sourceFile.length() > 0L) {
                    "Source model file missing or empty: ${sourceFile.absolutePath}"
                }
                lorasDir.mkdirs()
                val safeName = sanitizeFilename(targetName, defaultExt = ".gguf")
                val destFile = File(lorasDir, safeName)
                val tmpFile = File(lorasDir, "$safeName.tmp")
                if (tmpFile.exists()) tmpFile.delete()

                FileInputStream(sourceFile).use { input ->
                    FileOutputStream(tmpFile).use { out ->
                        input.copyTo(out, COPY_BUFFER_BYTES)
                        out.flush()
                        out.fd.sync()
                    }
                }
                promoteImportedStagingFile(tmpFile, destFile)
            }
        }
    }

    private suspend fun promoteImportedStagingFile(
        tmpFile: File,
        destFile: File
    ): LocalLoraRecord {
        if (tmpFile.length() <= 0L) {
            tmpFile.delete()
            error("Imported file is empty (0 bytes).")
        }
        if (destFile.exists() && !destFile.delete()) {
            tmpFile.delete()
            error("Unable to replace existing local model file: ${destFile.name}")
        }
        if (!tmpFile.renameTo(destFile)) {
            tmpFile.copyTo(destFile, overwrite = true)
            tmpFile.delete()
        }

        val shaHex = OtaBundleSyncManager.computeSha256Hex(destFile)
        var promotedIntoBundle = false

        val isManifestJson = destFile.name.equals(OtaBundleSyncManager.MANIFEST_FILENAME, ignoreCase = true)
        if (isManifestJson) {
            val parsed = runCatching {
                json.decodeFromString(OtaBundleManifest.serializer(), destFile.readText(Charsets.UTF_8))
            }.getOrNull()
            if (parsed != null && OtaBundleSyncManager.validateManifest(parsed) == null) {
                val targetBundleDir = File(bundlesRootDir, parsed.bundleVersion).apply { mkdirs() }
                destFile.copyTo(File(targetBundleDir, OtaBundleSyncManager.MANIFEST_FILENAME), overwrite = true)
                File(bundlesRootDir, OtaBundleSyncManager.ACTIVE_BUNDLE_POINTER_FILENAME)
                    .writeText(parsed.bundleVersion, Charsets.UTF_8)
                otaBundleSyncManager.refreshLocalState("")
            }
        } else if (isLoraCandidateFilename(destFile.name)) {
            promotedIntoBundle = tryPromoteLoraIntoInstalledBundle(destFile, shaHex)
        } else {
            // Mirror static artifact into local phone bundle slot so local verification picks it up.
            val localPhoneDir = File(bundlesRootDir, LOCAL_PHONE_BUNDLE_DIR).apply { mkdirs() }
            runCatching { destFile.copyTo(File(localPhoneDir, destFile.name), overwrite = true) }
            otaBundleSyncManager.refreshLocalState("")
        }

        return LocalLoraRecord(
            fileName = destFile.name,
            path = destFile.absolutePath,
            sizeBytes = destFile.length(),
            sha256Hex = shaHex,
            isActiveInBundle = promotedIntoBundle,
            updatedAtMillis = destFile.lastModified()
        )
    }

    private suspend fun tryPromoteLoraIntoInstalledBundle(
        loraFile: File,
        sha256Hex: String
    ): Boolean {
        val state = otaBundleSyncManager.refreshLocalState("")
        val manifest = state.activeManifest ?: return false
        val bundleDirPath = state.installedDirPath ?: return false
        val bundleDir = File(bundleDirPath)
        if (!bundleDir.isDirectory) return false

        val bundleTarget = File(bundleDir, loraFile.name)
        if (bundleTarget.canonicalPath != loraFile.canonicalPath) {
            loraFile.copyTo(bundleTarget, overwrite = true)
        }
        val nextLoraVersion = max(manifest.loraVersion + 1, 1)
        val updatedLoraArtifact = OtaModelArtifact(
            role = OtaArtifactRole.VOICE_LORA_GGUF,
            filename = loraFile.name,
            format = OtaArtifactFormat.GGUF,
            sha256 = sha256Hex,
            sizeBytes = bundleTarget.length(),
            estimatedRamMb = manifest.artifacts
                .firstOrNull { it.role == OtaArtifactRole.VOICE_LORA_GGUF }
                ?.estimatedRamMb ?: 64,
            updateCadence = OtaUpdateCadence.NIGHTLY_DELTA
        )
        val updatedArtifacts = if (manifest.artifacts.any { it.role == OtaArtifactRole.VOICE_LORA_GGUF }) {
            manifest.artifacts.map {
                if (it.role == OtaArtifactRole.VOICE_LORA_GGUF) updatedLoraArtifact else it
            }
        } else {
            manifest.artifacts + updatedLoraArtifact
        }
        val updatedManifest = manifest.copy(
            loraVersion = nextLoraVersion,
            artifacts = updatedArtifacts
        )
        if (OtaBundleSyncManager.validateManifest(updatedManifest) != null) {
            return false
        }
        val manifestFile = File(bundleDir, OtaBundleSyncManager.MANIFEST_FILENAME)
        manifestFile.writeText(
            json.encodeToString(OtaBundleManifest.serializer(), updatedManifest),
            Charsets.UTF_8
        )
        otaBundleSyncManager.refreshLocalState("")
        val bridge = VoiceStudioRuntimeRegistry.loraHotSwapBridge
            ?: VoiceStudioRuntimeRegistry.defaultLoraHotSwapBridge
        bridge.onLoraHotSwapped(nextLoraVersion, bundleTarget)
        return true
    }

    suspend fun runOnPhoneQualityCheck(): VoiceQualityReport = careMutex.withLock {
        withContext(ioDispatcher) {
            samplesDir.mkdirs()
            val newestWav = samplesDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".wav", ignoreCase = true) && it.length() > 44L }
                ?.maxByOrNull { it.lastModified() }

            val loadedAudio: LoadedAudioBuffer? = if (newestWav != null) {
                runCatching { readWavSamples(newestWav) }.getOrNull()
            } else {
                val newestPcmTake = findNewestSynthesizedPcmTake()
                newestPcmTake?.let { runCatching { readFloat32PcmTake(it) }.getOrNull() }
            }

            if (loadedAudio == null || loadedAudio.samples.isEmpty()) {
                return@withContext VoiceQualityReport(
                    hasAudio = false,
                    inspectedFileName = null,
                    inspectedFilePath = null,
                    sampleRateHz = 0,
                    durationSec = 0.0,
                    rmsDbfs = null,
                    peakDbfs = null,
                    tail80msRmsDbfs = null,
                    clippedSampleCount = 0,
                    dnsmosP808 = null,
                    speakerCosineSim = null,
                    summaryMessage = "No voice sample or synthesized take on phone yet. Record a sample first."
                )
            }

            val samples = loadedAudio.samples
            val sr = loadedAudio.sampleRateHz
            val durationSec = samples.size.toDouble() / sr.toDouble()
            var sumSq = 0.0
            var peakAbs = 0.0f
            var clipped = 0
            for (v in samples) {
                val a = abs(v)
                if (a > peakAbs) peakAbs = a
                if (a >= 0.999f) clipped++
                sumSq += v.toDouble() * v.toDouble()
            }
            val rms = sqrt(sumSq / samples.size.toDouble())
            val rmsDb = if (rms <= 1e-6) -120.0f else (20.0 * log10(rms)).toFloat()
            val peakDb = if (peakAbs <= 1e-6f) -120.0f else (20.0 * log10(peakAbs.toDouble())).toFloat()
            val tailDb = AudioDspMetrics.computeTailRmsDb(
                pcm44k = samples,
                sampleRateHz = sr
            )

            // Run local ONNX DNSMOS p808 & WeSpeaker cosine ONLY when verified .onnx files exist on phone.
            val activeDir = otaBundleSyncManager.bundleState.value.installedDirPath
                ?.let(::File)
                ?.takeIf { it.isDirectory }
                ?: findFallbackLocalBundleDir()

            val dnsmosFile = resolveLocalFileForRole(
                role = OtaArtifactRole.DNSMOS_P808_ONNX,
                expectedName = "dnsmos_p808.onnx",
                activeDir = activeDir
            )
            val weSpeakerFile = resolveLocalFileForRole(
                role = OtaArtifactRole.WESPEAKER_RESNET34_ONNX,
                expectedName = "wespeaker_resnet34.onnx",
                activeDir = activeDir
            )
            val centroidFile = resolveLocalFileForRole(
                role = OtaArtifactRole.MASTER_CENTROID_BIN,
                expectedName = "master_centroid.bin",
                activeDir = activeDir
            )

            var dnsmosScore: Float? = null
            var cosineScore: Float? = null
            if ((dnsmosFile != null && dnsmosFile.isFile) ||
                (weSpeakerFile != null && weSpeakerFile.isFile && centroidFile != null && centroidFile.isFile)
            ) {
                val pcm44k = if (sr == SYNTHESIS_SAMPLE_RATE_HZ) {
                    samples
                } else {
                    resampleLinear(samples, sr, SYNTHESIS_SAMPLE_RATE_HZ)
                }
                if (dnsmosFile != null && dnsmosFile.isFile) {
                    dnsmosScore = OrtDnsmosP808Engine.scoreWithSharedEnvironment(
                        modelFile = dnsmosFile,
                        pcm44k = pcm44k
                    ).getOrNull()
                }
                if (weSpeakerFile != null && weSpeakerFile.isFile && centroidFile != null && centroidFile.isFile) {
                    cosineScore = OrtWeSpeakerEngine.cosineWithSharedEnvironment(
                        modelFile = weSpeakerFile,
                        centroidFile = centroidFile,
                        pcm44k = pcm44k
                    ).getOrNull()
                }
            }

            val summary = buildString {
                append("Checked ${loadedAudio.file.name} (${String.format(Locale.US, "%.2f", durationSec)}s): ")
                append("RMS ${String.format(Locale.US, "%.1f", rmsDb)} dBFS, ")
                append("Peak ${String.format(Locale.US, "%.1f", peakDb)} dBFS, ")
                append("Clipped $clipped")
                if (dnsmosScore != null) {
                    append(", DNSMOS ${String.format(Locale.US, "%.2f", dnsmosScore)}")
                }
                if (cosineScore != null) {
                    append(", Cosine ${String.format(Locale.US, "%.3f", cosineScore)}")
                }
                if (dnsmosScore == null && cosineScore == null) {
                    append(". Local ONNX verifier models not yet imported on phone.")
                } else {
                    append(".")
                }
            }

            VoiceQualityReport(
                hasAudio = true,
                inspectedFileName = loadedAudio.file.name,
                inspectedFilePath = loadedAudio.file.absolutePath,
                sampleRateHz = sr,
                durationSec = durationSec,
                rmsDbfs = rmsDb,
                peakDbfs = peakDb,
                tail80msRmsDbfs = tailDb,
                clippedSampleCount = clipped,
                dnsmosP808 = dnsmosScore,
                speakerCosineSim = cosineScore,
                summaryMessage = summary
            )
        }
    }

    private fun resolveLocalFileForRole(
        role: OtaArtifactRole,
        expectedName: String,
        activeDir: File?
    ): File? {
        val searchDirs = buildList {
            if (activeDir != null) add(activeDir)
            val localPhoneDir = File(bundlesRootDir, LOCAL_PHONE_BUNDLE_DIR)
            if (localPhoneDir.isDirectory) add(localPhoneDir)
            val staticCache = File(bundlesRootDir, OtaBundleSyncManager.STATIC_CACHE_DIR_NAME)
            if (staticCache.isDirectory) add(staticCache)
            bundlesRootDir.listFiles()?.forEach { child ->
                if (child.isDirectory && !child.name.startsWith(".")) add(child)
            }
            if (lorasDir.isDirectory) add(lorasDir)
        }

        for (dir in searchDirs) {
            if (role == OtaArtifactRole.SHERPA_CTC_ALIGNER) {
                val extracted = File(dir, OtaBundleSyncManager.SHERPA_CTC_EXTRACTED_DIR)
                if (extracted.isDirectory && File(extracted, "tokens.txt").isFile) return extracted
            }
            val exact = File(dir, expectedName)
            if (exact.exists() && (exact.isDirectory || exact.length() > 0L)) return exact
            if (role == OtaArtifactRole.VOICE_LORA_GGUF) {
                val anyLora = dir.listFiles()
                    ?.filter { it.isFile && isLoraCandidateFilename(it.name) && it.length() > 0L }
                    ?.maxByOrNull { it.lastModified() }
                if (anyLora != null) return anyLora
            }
        }
        return null
    }

    private fun findFallbackLocalBundleDir(): File? {
        if (!bundlesRootDir.isDirectory) return null
        val localPhone = File(bundlesRootDir, LOCAL_PHONE_BUNDLE_DIR)
        if (localPhone.isDirectory) return localPhone
        return bundlesRootDir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") && it.name != OtaBundleSyncManager.STATIC_CACHE_DIR_NAME }
            ?.maxByOrNull { it.lastModified() }
    }

    private fun findNewestSynthesizedPcmTake(): File? {
        val projectsDir = File(context.noBackupFilesDir, "projects")
        if (!projectsDir.isDirectory) return null
        return projectsDir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".pcm", ignoreCase = true) && it.length() >= 4L }
            .maxByOrNull { it.lastModified() }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else {
                    null
                }
            }
    }.getOrNull()

    private data class WavHeaderInfo(
        val sampleRateHz: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val audioFormat: Int,
        val dataOffset: Int,
        val dataByteLength: Int
    ) {
        val durationSec: Double
            get() {
                val bytesPerFrame = max(1, channels * max(1, bitsPerSample / 8))
                val frames = dataByteLength.toDouble() / bytesPerFrame.toDouble()
                return if (sampleRateHz > 0) frames / sampleRateHz.toDouble() else 0.0
            }
    }

    private data class LoadedAudioBuffer(
        val file: File,
        val sampleRateHz: Int,
        val samples: FloatArray
    )

    private fun inspectWavHeader(file: File): WavHeaderInfo {
        val bytes = FileInputStream(file).use { input ->
            val cap = min(file.length(), 8192L).toInt()
            val header = ByteArray(cap)
            val n = input.read(header)
            if (n < 44) error("Invalid WAV header (<44 bytes)")
            if (n == cap) header else header.copyOf(n)
        }
        return parseWavHeaderBytes(bytes, file.length())
    }

    private fun parseWavHeaderBytes(headerBytes: ByteArray, totalFileLength: Long): WavHeaderInfo {
        val buf = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
        val riff = ByteArray(4).also { buf.get(it) }.toString(Charsets.US_ASCII)
        buf.int // chunk size
        val wave = ByteArray(4).also { buf.get(it) }.toString(Charsets.US_ASCII)
        require(riff == "RIFF" && wave == "WAVE") { "Unsupported WAV container ($riff/$wave)" }

        var audioFormat = 1
        var channels = 1
        var sampleRate = 16_000
        var bitsPerSample = 16
        var dataOffset = -1
        var dataLen = 0

        while (buf.remaining() >= 8) {
            val chunkId = ByteArray(4).also { buf.get(it) }.toString(Charsets.US_ASCII)
            val chunkSize = buf.int
            val chunkStart = buf.position()
            if (chunkId == "fmt " && chunkSize >= 16 && buf.remaining() >= 16) {
                audioFormat = buf.short.toInt() and 0xFFFF
                channels = (buf.short.toInt() and 0xFFFF).coerceAtLeast(1)
                sampleRate = buf.int.coerceAtLeast(1)
                buf.int // byteRate
                buf.short // blockAlign
                bitsPerSample = (buf.short.toInt() and 0xFFFF).coerceAtLeast(8)
            } else if (chunkId == "data") {
                dataOffset = chunkStart
                val availableInFile = (totalFileLength - chunkStart.toLong()).coerceAtLeast(0L)
                dataLen = min(chunkSize.toLong().and(0xFFFFFFFFL), availableInFile).toInt()
                break
            }
            val nextPos = chunkStart + chunkSize + (chunkSize and 1)
            if (nextPos < 0 || nextPos > buf.limit()) break
            buf.position(nextPos)
        }
        require(dataOffset >= 12) { "Missing data chunk in WAV file" }
        return WavHeaderInfo(
            sampleRateHz = sampleRate,
            channels = channels,
            bitsPerSample = bitsPerSample,
            audioFormat = audioFormat,
            dataOffset = dataOffset,
            dataByteLength = dataLen
        )
    }

    private fun readWavSamples(file: File): LoadedAudioBuffer {
        val allBytes = file.readBytes()
        val info = parseWavHeaderBytes(allBytes, allBytes.size.toLong())
        val endOffset = min(allBytes.size, info.dataOffset + info.dataByteLength)
        val payload = ByteBuffer.wrap(allBytes, info.dataOffset, (endOffset - info.dataOffset).coerceAtLeast(0))
            .order(ByteOrder.LITTLE_ENDIAN)

        val monoSamples: FloatArray = when {
            info.audioFormat == 3 && info.bitsPerSample == 32 -> {
                val frameCount = payload.remaining() / (4 * info.channels)
                FloatArray(frameCount) {
                    var sum = 0f
                    for (c in 0 until info.channels) {
                        sum += payload.float
                    }
                    (sum / info.channels.toFloat()).coerceIn(-1.0f, 1.0f)
                }
            }
            info.bitsPerSample == 16 -> {
                val frameCount = payload.remaining() / (2 * info.channels)
                FloatArray(frameCount) {
                    var sum = 0f
                    for (c in 0 until info.channels) {
                        sum += payload.short / 32768.0f
                    }
                    (sum / info.channels.toFloat()).coerceIn(-1.0f, 1.0f)
                }
            }
            else -> error("Unsupported WAV PCM depth: format=${info.audioFormat}, bits=${info.bitsPerSample}")
        }
        return LoadedAudioBuffer(
            file = file,
            sampleRateHz = info.sampleRateHz,
            samples = monoSamples
        )
    }

    private fun readFloat32PcmTake(file: File): LoadedAudioBuffer {
        val bytes = file.readBytes()
        val count = bytes.size / 4
        val buf = ByteBuffer.wrap(bytes, 0, count * 4).order(ByteOrder.LITTLE_ENDIAN)
        val samples = FloatArray(count) {
            val v = buf.float
            if (v.isFinite()) v.coerceIn(-1.0f, 1.0f) else 0f
        }
        return LoadedAudioBuffer(
            file = file,
            sampleRateHz = SYNTHESIS_SAMPLE_RATE_HZ,
            samples = samples
        )
    }

    companion object {
        const val VOICE_CARE_DIR_NAME: String = "voice_care"
        const val SAMPLES_SUBDIR_NAME: String = "samples"
        const val LORAS_SUBDIR_NAME: String = "loras"
        const val LOCAL_PHONE_BUNDLE_DIR: String = "phone_local"
        private const val COPY_BUFFER_BYTES: Int = 64 * 1024

        private data class RoleSpec(
            val role: OtaArtifactRole,
            val label: String,
            val defaultFilename: String
        )

        private val ROLE_SPECS = listOf(
            RoleSpec(OtaArtifactRole.S2_PRO_BASE_GGUF, "Base Voice LLM", "s2_pro_4b_q4_k_m.gguf"),
            RoleSpec(OtaArtifactRole.VOICE_LORA_GGUF, "Personal Voice LoRA", "voice_lora_v001.gguf"),
            RoleSpec(OtaArtifactRole.CODEC_DECODER_TFLITE, "LiteRT Codec Decoder", "codec_decoder_fp16.tflite"),
            RoleSpec(OtaArtifactRole.SHERPA_CTC_ALIGNER, "Sherpa CTC Aligner", "sherpa_ctc.tar.gz"),
            RoleSpec(OtaArtifactRole.WESPEAKER_RESNET34_ONNX, "WeSpeaker Verifier", "wespeaker_resnet34.onnx"),
            RoleSpec(OtaArtifactRole.DNSMOS_P808_ONNX, "DNSMOS p808 Scorer", "dnsmos_p808.onnx"),
            RoleSpec(OtaArtifactRole.MASTER_CENTROID_BIN, "Speaker Centroid", "master_centroid.bin"),
            RoleSpec(OtaArtifactRole.ROOM_IR_PCM, "Room Impulse PCM", "room_ir_44k.pcm"),
            RoleSpec(OtaArtifactRole.ROOM_TONE_PCM, "Room Tone PCM", "room_tone_44k.pcm"),
            RoleSpec(OtaArtifactRole.G2P_LEXICON, "Phonetic Lexicon", "lexicon.json")
        )

        internal fun isLoraCandidateFilename(filename: String): Boolean {
            val lower = filename.lowercase(Locale.US)
            return lower.endsWith(".gguf") && !lower.startsWith("s2_pro")
        }

        internal fun sanitizeFilename(raw: String, defaultExt: String): String {
            val leaf = raw.trim().substringAfterLast('/').substringAfterLast('\\')
            val cleaned = leaf.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_')
            if (cleaned.isEmpty() || cleaned == "." || cleaned == "..") {
                return "voice_model_${System.currentTimeMillis()}$defaultExt"
            }
            return if (cleaned.contains('.')) cleaned else "$cleaned$defaultExt"
        }

        internal fun directoryTotalBytes(dir: File): Long {
            if (!dir.exists()) return 0L
            if (dir.isFile) return dir.length()
            var sum = 0L
            dir.walkTopDown().forEach { child ->
                if (child.isFile) sum += child.length()
            }
            return sum
        }

        internal fun resampleLinear(
            src: FloatArray,
            srcRateHz: Int,
            dstRateHz: Int
        ): FloatArray {
            if (src.isEmpty() || srcRateHz <= 0 || dstRateHz <= 0 || srcRateHz == dstRateHz) return src
            val outLen = ((src.size.toLong() * dstRateHz.toLong()) / srcRateHz.toLong()).toInt().coerceAtLeast(1)
            val ratio = srcRateHz.toDouble() / dstRateHz.toDouble()
            return FloatArray(outLen) { idx ->
                val srcPos = idx.toDouble() * ratio
                val i0 = srcPos.toInt().coerceIn(0, src.lastIndex)
                val i1 = (i0 + 1).coerceAtMost(src.lastIndex)
                val frac = (srcPos - i0.toDouble()).toFloat()
                src[i0] + (src[i1] - src[i0]) * frac
            }
        }
    }
}
