package com.voicestudio.sync

import android.content.Context
import com.voicestudio.edl.FOREGROUND_RAM_CEILING_MB
import com.voicestudio.edl.OtaArtifactFormat
import com.voicestudio.edl.OtaArtifactRole
import com.voicestudio.edl.OtaBundleManifest
import com.voicestudio.edl.OtaBundleState
import com.voicestudio.edl.OtaBundleStatus
import com.voicestudio.edl.OtaModelArtifact
import com.voicestudio.edl.OtaUpdateCadence
import com.voicestudio.edl.SYNTHESIS_SAMPLE_RATE_HZ
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Verified on-disk artifact paths resolved under `noBackupFilesDir/bundles/{bundle_version}/`
 * required to run Stage A (GGUF + LoRA + LiteRT DAC), Stage B (4-gate ONNX verifier), and
 * Stage C (NEON DSP mastering).
 */
data class ResolvedOtaBundlePaths(
    val manifest: OtaBundleManifest,
    val bundleDir: File,
    val baseGguf: File,
    val loraGguf: File,
    val vocoderTflite: File,
    val sherpaCtcModelDir: File,
    val weSpeakerOnnx: File,
    val dnsmosP808Onnx: File,
    val masterCentroidBin: File,
    val roomIrPcm: File,
    val roomTonePcm: File,
    val lexiconJson: File?
)

data class LoraDeltaSyncResult(
    val manifest: OtaBundleManifest,
    val loraVersion: Int,
    val loraFile: File,
    val lexiconFile: File?
)

/**
 * Tailscale/HTTP OTA Bundle Sync Manager talking to `sync-trainer/home-daemon/ota_server.py`.
 *
 * - Endpoints:
 *   - `GET /v1/bundles/latest/manifest.json`
 *   - `GET /v1/bundles/{bundle_version}/{filename}` with RFC 9110 HTTP `Range` resume into `.part`
 * - Strict verification:
 *   - Validates `sample_rate_hz == 44100` and `foreground_ram_ceiling_mb <= 9500 MB` + total RAM fit.
 *   - Enforces exact byte size and lowercase SHA-256 checksum before atomic rename into
 *     `context.noBackupFilesDir/bundles/{bundle_version}/`.
 *   - Deletes corrupted `.part` files immediately on mismatch.
 * - Differential updates:
 *   - Static base artifacts (`STATIC_ONCE`: ~2.6 GB `s2_pro_4b_q4_k_m.gguf`, `codec_decoder_fp16.tflite`,
 *     `wespeaker_resnet34.onnx`, `dnsmos_p808.onnx`, `master_centroid.bin`, `room_ir_44k.pcm`,
 *     `room_tone_44k.pcm`) are synced once and reused across bundle versions via SHA-256 verification.
 *   - Nightly delta (`pullLatestLoraDelta`) pulls ONLY `voice_lora_vXXX.gguf` (~35 MB) + `lexicon.json`
 *     when `remote.loraVersion > activeLoraVersion`.
 *
 * Constitutional Law 2: Never reports `READY` unless verified files exist on disk; never fabricates
 * fallback weights or dummy manifests.
 */
class OtaBundleSyncManager(
    private val bundlesRootDir: File,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    constructor(
        context: Context,
        httpClient: OkHttpClient = defaultHttpClient(),
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO
    ) : this(
        bundlesRootDir = File(context.noBackupFilesDir, BUNDLES_DIR_NAME),
        httpClient = httpClient,
        ioDispatcher = ioDispatcher
    )

    private val syncMutex = Mutex()
    private val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
    }

    private val _bundleState = MutableStateFlow(OtaBundleState())
    val bundleState: StateFlow<OtaBundleState> = _bundleState.asStateFlow()

    /**
     * Scans `noBackupFilesDir/bundles/` to restore real persisted OTA state without network I/O.
     */
    suspend fun refreshLocalState(tailscaleBaseUrl: String = _bundleState.value.tailscaleBaseUrl): OtaBundleState =
        withContext(ioDispatcher) {
            val loaded = loadInstalledBundleInternal()
            val nextState = if (loaded == null) {
                OtaBundleState(
                    status = OtaBundleStatus.NOT_INSTALLED,
                    tailscaleBaseUrl = tailscaleBaseUrl,
                    activeManifest = null,
                    activeLoraVersion = 0,
                    installedDirPath = null,
                    verifiedBytes = 0L,
                    totalBytes = 0L,
                    errorMessage = null
                )
            } else {
                val (bundleDir, manifest) = loaded
                val validationError = validateManifest(manifest)
                if (validationError != null) {
                    OtaBundleState(
                        status = OtaBundleStatus.CORRUPT_OR_INCOMPATIBLE,
                        tailscaleBaseUrl = tailscaleBaseUrl,
                        activeManifest = manifest,
                        activeLoraVersion = 0,
                        installedDirPath = bundleDir.absolutePath,
                        verifiedBytes = 0L,
                        totalBytes = manifest.artifacts.sumOf { it.sizeBytes },
                        errorMessage = validationError
                    )
                } else {
                    val missing = manifest.artifacts.filterNot { art ->
                        val file = File(bundleDir, art.filename)
                        file.isFile && file.canRead() && file.length() == art.sizeBytes
                    }
                    val totalBytes = manifest.artifacts.sumOf { it.sizeBytes }
                    if (missing.isEmpty()) {
                        OtaBundleState(
                            status = OtaBundleStatus.READY,
                            tailscaleBaseUrl = tailscaleBaseUrl,
                            activeManifest = manifest,
                            activeLoraVersion = manifest.loraVersion,
                            installedDirPath = bundleDir.absolutePath,
                            verifiedBytes = totalBytes,
                            totalBytes = totalBytes,
                            errorMessage = null
                        )
                    } else {
                        val presentBytes = manifest.artifacts
                            .filter { File(bundleDir, it.filename).isFile }
                            .sumOf { File(bundleDir, it.filename).length() }
                        OtaBundleState(
                            status = OtaBundleStatus.NOT_INSTALLED,
                            tailscaleBaseUrl = tailscaleBaseUrl,
                            activeManifest = manifest,
                            activeLoraVersion = 0,
                            installedDirPath = bundleDir.absolutePath,
                            verifiedBytes = presentBytes,
                            totalBytes = totalBytes,
                            errorMessage = "Incomplete OTA bundle; missing: ${missing.joinToString { it.filename }}"
                        )
                    }
                }
            }
            _bundleState.value = nextState
            nextState
        }

    /**
     * Performs a full or incremental OTA bundle sync from `{tailscaleBaseUrl}/v1/bundles/latest/manifest.json`.
     * Static base artifacts whose SHA-256 matches an already-verified local artifact are reused with zero re-download.
     */
    suspend fun syncFromRig(tailscaleBaseUrl: String): Result<OtaBundleManifest> =
        syncMutex.withLock {
            withContext(ioDispatcher) {
                val normalizedUrl = normalizeBaseUrl(tailscaleBaseUrl)
                    ?: return@withContext failSync(
                        baseUrl = tailscaleBaseUrl,
                        status = OtaBundleStatus.SYNC_FAILED,
                        message = "Invalid Tailscale Rig URL: '$tailscaleBaseUrl'"
                    )

                val previousInstalled = loadInstalledBundleInternal()
                _bundleState.value = _bundleState.value.copy(
                    status = OtaBundleStatus.SYNCING,
                    tailscaleBaseUrl = normalizedUrl,
                    errorMessage = null
                )

                val remoteManifest = fetchLatestManifest(normalizedUrl).getOrElse { err ->
                    return@withContext failSync(
                        baseUrl = normalizedUrl,
                        status = OtaBundleStatus.SYNC_FAILED,
                        message = err.message ?: "Failed fetching OTA manifest"
                    )
                }

                val validationError = validateManifest(remoteManifest)
                if (validationError != null) {
                    return@withContext failSync(
                        baseUrl = normalizedUrl,
                        status = OtaBundleStatus.CORRUPT_OR_INCOMPATIBLE,
                        message = validationError
                    )
                }

                val targetDir = bundleDirForVersion(remoteManifest.bundleVersion)
                targetDir.mkdirs()

                val totalBytes = remoteManifest.artifacts.sumOf { it.sizeBytes }
                var verifiedBytes = 0L
                _bundleState.value = _bundleState.value.copy(
                    status = OtaBundleStatus.SYNCING,
                    tailscaleBaseUrl = normalizedUrl,
                    totalBytes = totalBytes,
                    verifiedBytes = 0L,
                    errorMessage = null
                )

                for (artifact in remoteManifest.artifacts) {
                    val artifactResult = ensureArtifactVerified(
                        baseUrl = normalizedUrl,
                        manifest = remoteManifest,
                        artifact = artifact,
                        targetBundleDir = targetDir,
                        previousBundleDir = previousInstalled?.first,
                        baseVerifiedBytes = verifiedBytes,
                        totalBundleBytes = totalBytes
                    )
                    if (artifactResult.isFailure) {
                        val err = artifactResult.exceptionOrNull()!!
                        val failStatus = if (err is SecurityException || err is IllegalArgumentException) {
                            OtaBundleStatus.CORRUPT_OR_INCOMPATIBLE
                        } else {
                            OtaBundleStatus.SYNC_FAILED
                        }
                        return@withContext failSync(
                            baseUrl = normalizedUrl,
                            status = failStatus,
                            message = err.message ?: "Failed syncing ${artifact.filename}"
                        )
                    }
                    verifiedBytes += artifact.sizeBytes
                    _bundleState.value = _bundleState.value.copy(
                        verifiedBytes = verifiedBytes,
                        totalBytes = totalBytes
                    )
                }

                persistActiveBundlePointer(targetDir, remoteManifest)
                _bundleState.value = OtaBundleState(
                    status = OtaBundleStatus.READY,
                    tailscaleBaseUrl = normalizedUrl,
                    activeManifest = remoteManifest,
                    activeLoraVersion = remoteManifest.loraVersion,
                    installedDirPath = targetDir.absolutePath,
                    verifiedBytes = totalBytes,
                    totalBytes = totalBytes,
                    errorMessage = null
                )
                Result.success(remoteManifest)
            }
        }

    /**
     * Checks `{tailscaleBaseUrl}/v1/bundles/latest/manifest.json` and, ONLY when
     * `remote.loraVersion > activeLoraVersion`, downloads ONLY the `NIGHTLY_DELTA` artifacts
     * (`voice_lora_vXXX.gguf` ~35 MB + `lexicon.json`) while reusing verified `STATIC_ONCE`
     * artifacts from the installed base bundle without hitting the network for static weights.
     *
     * Returns `Result.success(null)` if already up-to-date.
     */
    suspend fun pullLatestLoraDelta(tailscaleBaseUrl: String): Result<LoraDeltaSyncResult?> =
        syncMutex.withLock {
            withContext(ioDispatcher) {
                val normalizedUrl = normalizeBaseUrl(tailscaleBaseUrl)
                    ?: return@withContext Result.failure(
                        IllegalArgumentException("Invalid Tailscale Rig URL: '$tailscaleBaseUrl'")
                    )

                val installed = loadInstalledBundleInternal()
                val activeLoraVersion = installed?.second?.loraVersion ?: 0

                val remoteManifest = fetchLatestManifest(normalizedUrl).getOrElse { err ->
                    return@withContext Result.failure(err)
                }

                val validationError = validateManifest(remoteManifest)
                if (validationError != null) {
                    _bundleState.value = _bundleState.value.copy(
                        status = OtaBundleStatus.CORRUPT_OR_INCOMPATIBLE,
                        tailscaleBaseUrl = normalizedUrl,
                        errorMessage = validationError
                    )
                    return@withContext Result.failure(IllegalArgumentException(validationError))
                }

                if (remoteManifest.loraVersion <= activeLoraVersion) {
                    return@withContext Result.success(null)
                }

                val deltaArtifacts = remoteManifest.artifacts.filter { art ->
                    art.updateCadence == OtaUpdateCadence.NIGHTLY_DELTA ||
                        art.role == OtaArtifactRole.VOICE_LORA_GGUF ||
                        art.role == OtaArtifactRole.G2P_LEXICON
                }
                val loraArtifact = deltaArtifacts.firstOrNull { it.role == OtaArtifactRole.VOICE_LORA_GGUF }
                    ?: return@withContext Result.failure(
                        IllegalStateException(
                            "Remote manifest ${remoteManifest.bundleVersion} declares lora_version=${remoteManifest.loraVersion} without a voice_lora_gguf artifact"
                        )
                    )

                val targetDir = bundleDirForVersion(remoteManifest.bundleVersion)
                targetDir.mkdirs()

                val totalDeltaBytes = deltaArtifacts.sumOf { it.sizeBytes }
                var downloadedDeltaBytes = 0L

                _bundleState.value = _bundleState.value.copy(
                    status = OtaBundleStatus.SYNCING,
                    tailscaleBaseUrl = normalizedUrl,
                    totalBytes = totalDeltaBytes,
                    verifiedBytes = 0L,
                    errorMessage = null
                )

                // 1. Download & SHA-256 verify ONLY nightly delta artifacts (LoRA + lexicon).
                var verifiedLoraFile: File? = null
                var verifiedLexiconFile: File? = null
                for (artifact in deltaArtifacts) {
                    val fileResult = ensureArtifactVerified(
                        baseUrl = normalizedUrl,
                        manifest = remoteManifest,
                        artifact = artifact,
                        targetBundleDir = targetDir,
                        previousBundleDir = installed?.first,
                        baseVerifiedBytes = downloadedDeltaBytes,
                        totalBundleBytes = totalDeltaBytes
                    )
                    if (fileResult.isFailure) {
                        val err = fileResult.exceptionOrNull()!!
                        _bundleState.value = _bundleState.value.copy(
                            status = if (err is SecurityException) {
                                OtaBundleStatus.CORRUPT_OR_INCOMPATIBLE
                            } else {
                                OtaBundleStatus.SYNC_FAILED
                            },
                            errorMessage = err.message
                        )
                        return@withContext Result.failure(err)
                    }
                    val verifiedFile = fileResult.getOrThrow()
                    if (artifact.role == OtaArtifactRole.VOICE_LORA_GGUF) {
                        verifiedLoraFile = verifiedFile
                    } else if (artifact.role == OtaArtifactRole.G2P_LEXICON) {
                        verifiedLexiconFile = verifiedFile
                    }
                    downloadedDeltaBytes += artifact.sizeBytes
                }

                // 2. Link/reuse verified STATIC_ONCE base artifacts from previous bundle without network calls.
                val staticArtifacts = remoteManifest.artifacts.filterNot { it in deltaArtifacts }
                var allStaticReady = true
                for (staticArt in staticArtifacts) {
                    val reused = tryReuseVerifiedStaticArtifact(
                        artifact = staticArt,
                        targetBundleDir = targetDir,
                        previousBundleDir = installed?.first
                    )
                    if (reused == null) {
                        allStaticReady = false
                    }
                }

                val fullBytes = remoteManifest.artifacts.sumOf { it.sizeBytes }
                if (allStaticReady) {
                    persistActiveBundlePointer(targetDir, remoteManifest)
                }
                val finalStatus = if (allStaticReady) OtaBundleStatus.READY else OtaBundleStatus.NOT_INSTALLED
                _bundleState.value = OtaBundleState(
                    status = finalStatus,
                    tailscaleBaseUrl = normalizedUrl,
                    activeManifest = remoteManifest,
                    activeLoraVersion = remoteManifest.loraVersion,
                    installedDirPath = targetDir.absolutePath,
                    verifiedBytes = if (allStaticReady) fullBytes else downloadedDeltaBytes,
                    totalBytes = fullBytes,
                    errorMessage = if (allStaticReady) {
                        null
                    } else {
                        "LoRA v${remoteManifest.loraVersion} synced; base static artifacts require full OTA sync."
                    }
                )

                Result.success(
                    LoraDeltaSyncResult(
                        manifest = remoteManifest,
                        loraVersion = remoteManifest.loraVersion,
                        loraFile = verifiedLoraFile!!,
                        lexiconFile = verifiedLexiconFile
                    )
                )
            }
        }

    suspend fun loadVerifiedManifest(): OtaBundleManifest? = withContext(ioDispatcher) {
        val loaded = loadInstalledBundleInternal() ?: return@withContext null
        if (validateManifest(loaded.second) != null) return@withContext null
        loaded.second
    }

    suspend fun resolveArtifactFile(role: OtaArtifactRole): File? = withContext(ioDispatcher) {
        val (bundleDir, manifest) = loadInstalledBundleInternal() ?: return@withContext null
        val artifact = manifest.artifacts.firstOrNull { it.role == role } ?: return@withContext null
        val candidate = File(bundleDir, artifact.filename)
        if (candidate.exists() && candidate.canRead()) candidate else null
    }

    /**
     * Strictly verifies that ALL required synthesis and 4-gate verification artifacts exist on disk
     * inside the verified OTA bundle directory.
     *
     * Constitutional Law 2: Returns `Result.failure(...)` listing every missing role/file if ANY
     * required model artifact is absent — NEVER substitutes mock/placeholder files.
     */
    suspend fun resolveVerifiedBundleForSynthesis(): Result<ResolvedOtaBundlePaths> =
        withContext(ioDispatcher) {
            val loaded = loadInstalledBundleInternal()
                ?: return@withContext Result.failure(
                    IllegalStateException(
                        "No verified OTA voice bundle installed under ${bundlesRootDir.absolutePath}. " +
                            "Sync the 44.1 kHz bundle over Tailscale in System Deck first."
                    )
                )
            val (bundleDir, manifest) = loaded
            val validationError = validateManifest(manifest)
            if (validationError != null) {
                return@withContext Result.failure(IllegalStateException("Invalid OTA manifest: $validationError"))
            }

            fun resolveRequiredFile(role: OtaArtifactRole): File? {
                val art = manifest.artifacts.firstOrNull { it.role == role } ?: return null
                val file = File(bundleDir, art.filename)
                return if (file.isFile && file.canRead() && file.length() > 0L) file else null
            }

            fun resolveSherpaDir(): File? {
                val art = manifest.artifacts.firstOrNull { it.role == OtaArtifactRole.SHERPA_CTC_ALIGNER }
                val candidateDir = File(bundleDir, SHERPA_CTC_EXTRACTED_DIR)
                if (candidateDir.isDirectory && File(candidateDir, "tokens.txt").isFile) {
                    return candidateDir
                }
                if (art != null) {
                    val direct = File(bundleDir, art.filename)
                    if (direct.isDirectory && File(direct, "tokens.txt").isFile) {
                        return direct
                    }
                    if (direct.isFile && direct.canRead() && direct.length() > 0L) {
                        // Allow flat directory layout where tokens.txt sits alongside the CTC .onnx file.
                        if (File(bundleDir, "tokens.txt").isFile) {
                            return bundleDir
                        }
                    }
                }
                return null
            }

            val missing = mutableListOf<String>()
            val baseGguf = resolveRequiredFile(OtaArtifactRole.S2_PRO_BASE_GGUF)
                ?: run { missing += "s2_pro_base_gguf (s2_pro_4b_q4_k_m.gguf)"; null }
            val loraGguf = resolveRequiredFile(OtaArtifactRole.VOICE_LORA_GGUF)
                ?: run { missing += "voice_lora_gguf (voice_lora_vXXX.gguf)"; null }
            val vocoderTflite = resolveRequiredFile(OtaArtifactRole.CODEC_DECODER_TFLITE)
                ?: run { missing += "codec_decoder_tflite (codec_decoder_fp16.tflite)"; null }
            val sherpaDir = resolveSherpaDir()
                ?: run { missing += "sherpa_ctc_aligner (sherpa_ctc/tokens.txt + *.onnx)"; null }
            val weSpeakerOnnx = resolveRequiredFile(OtaArtifactRole.WESPEAKER_RESNET34_ONNX)
                ?: run { missing += "wespeaker_resnet34_onnx (wespeaker_resnet34.onnx)"; null }
            val dnsmosOnnx = resolveRequiredFile(OtaArtifactRole.DNSMOS_P808_ONNX)
                ?: run { missing += "dnsmos_p808_onnx (dnsmos_p808.onnx)"; null }
            val centroidBin = resolveRequiredFile(OtaArtifactRole.MASTER_CENTROID_BIN)
                ?: run { missing += "master_centroid_bin (master_centroid.bin)"; null }
            val roomIrPcm = resolveRequiredFile(OtaArtifactRole.ROOM_IR_PCM)
                ?: run { missing += "room_ir_pcm (room_ir_44k.pcm)"; null }
            val roomTonePcm = resolveRequiredFile(OtaArtifactRole.ROOM_TONE_PCM)
                ?: run { missing += "room_tone_pcm (room_tone_44k.pcm)"; null }

            if (missing.isNotEmpty()) {
                return@withContext Result.failure(
                    IllegalStateException(
                        "Missing required OTA bundle artifacts (${manifest.bundleVersion}): ${missing.joinToString(", ")}"
                    )
                )
            }

            val lexiconFile = manifest.artifacts
                .firstOrNull { it.role == OtaArtifactRole.G2P_LEXICON }
                ?.let { File(bundleDir, it.filename) }
                ?.takeIf { it.isFile && it.canRead() && it.length() > 0L }

            Result.success(
                ResolvedOtaBundlePaths(
                    manifest = manifest,
                    bundleDir = bundleDir,
                    baseGguf = baseGguf!!,
                    loraGguf = loraGguf!!,
                    vocoderTflite = vocoderTflite!!,
                    sherpaCtcModelDir = sherpaDir!!,
                    weSpeakerOnnx = weSpeakerOnnx!!,
                    dnsmosP808Onnx = dnsmosOnnx!!,
                    masterCentroidBin = centroidBin!!,
                    roomIrPcm = roomIrPcm!!,
                    roomTonePcm = roomTonePcm!!,
                    lexiconJson = lexiconFile
                )
            )
        }

    private fun fetchLatestManifest(normalizedBaseUrl: String): Result<OtaBundleManifest> = runCatching {
        val url = "$normalizedBaseUrl/v1/bundles/latest/manifest.json"
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("GET $url failed with HTTP ${response.code}")
            }
            val bodyText = response.body?.string()
                ?: throw IOException("Empty response body from $url")
            json.decodeFromString(OtaBundleManifest.serializer(), bodyText)
        }
    }

    private fun ensureArtifactVerified(
        baseUrl: String,
        manifest: OtaBundleManifest,
        artifact: OtaModelArtifact,
        targetBundleDir: File,
        previousBundleDir: File?,
        baseVerifiedBytes: Long,
        totalBundleBytes: Long
    ): Result<File> = runCatching {
        validateSafeFilename(artifact.filename)
        val targetFile = File(targetBundleDir, artifact.filename)

        // 1. Reuse if targetFile already exists and matches exact size & SHA-256.
        if (verifyFileMatchesArtifact(targetFile, artifact)) {
            return@runCatching targetFile
        }

        // 2. For STATIC_ONCE artifacts, reuse from previously verified bundle or static cache.
        if (artifact.updateCadence == OtaUpdateCadence.STATIC_ONCE) {
            val reused = tryReuseVerifiedStaticArtifact(artifact, targetBundleDir, previousBundleDir)
            if (reused != null) {
                return@runCatching reused
            }
        }

        // 3. Download via HTTP Range resume into `.part` file, verify SHA-256, then atomic rename.
        val partFile = File(targetBundleDir, "${artifact.filename}.part")
        val downloadUrl = buildArtifactUrl(baseUrl, manifest.bundleVersion, artifact)

        var existingLength = if (partFile.isFile) partFile.length() else 0L
        if (existingLength > artifact.sizeBytes) {
            partFile.delete()
            existingLength = 0L
        }

        if (existingLength == artifact.sizeBytes) {
            // Possibly finished writing before previous process died; verify SHA-256 before re-fetching.
            if (verifyFileMatchesArtifact(partFile, artifact)) {
                return@runCatching atomicPromoteToTarget(partFile, targetFile)
            } else {
                partFile.delete()
                existingLength = 0L
            }
        }

        downloadWithRangeResume(
            url = downloadUrl,
            partFile = partFile,
            expectedSizeBytes = artifact.sizeBytes,
            initialOffset = existingLength,
            onProgressBytes = { fileBytes ->
                _bundleState.value = _bundleState.value.copy(
                    verifiedBytes = (baseVerifiedBytes + fileBytes).coerceAtMost(totalBundleBytes),
                    totalBytes = totalBundleBytes
                )
            }
        )

        // Strict size + SHA-256 verification before promoting `.part` to final filename.
        val actualSize = partFile.length()
        if (actualSize != artifact.sizeBytes) {
            partFile.delete()
            throw SecurityException(
                "Size mismatch for ${artifact.filename}: expected ${artifact.sizeBytes} bytes, got $actualSize bytes"
            )
        }
        val actualSha256 = computeSha256Hex(partFile)
        val expectedSha256 = artifact.sha256.lowercase(Locale.US)
        if (actualSha256 != expectedSha256) {
            partFile.delete()
            throw SecurityException(
                "SHA-256 mismatch for ${artifact.filename}: expected $expectedSha256, got $actualSha256"
            )
        }

        val promoted = atomicPromoteToTarget(partFile, targetFile)
        if (artifact.updateCadence == OtaUpdateCadence.STATIC_ONCE) {
            mirrorToStaticCache(promoted, artifact)
        }
        promoted
    }

    private fun downloadWithRangeResume(
        url: String,
        partFile: File,
        expectedSizeBytes: Long,
        initialOffset: Long,
        onProgressBytes: (Long) -> Unit
    ) {
        var offset = initialOffset
        var attemptedFullFallback = false

        while (true) {
            val reqBuilder = Request.Builder().url(url).get()
            if (offset > 0L) {
                reqBuilder.header("Range", "bytes=$offset-")
            }

            httpClient.newCall(reqBuilder.build()).execute().use { response ->
                when (response.code) {
                    206 -> {
                        if (offset <= 0L) {
                            throw IOException("Unexpected 206 Partial Content without Range request for $url")
                        }
                        val contentRange = response.header("Content-Range")
                        if (!isMatchingContentRangeStart(contentRange, offset, expectedSizeBytes)) {
                            partFile.delete()
                            throw IOException("Mismatched Content-Range '$contentRange' for offset $offset ($url)")
                        }
                        val bodyStream = response.body?.byteStream()
                            ?: throw IOException("Empty 206 response body for $url")
                        streamIntoFile(bodyStream, partFile, append = true, startOffset = offset, onProgressBytes)
                        return
                    }
                    200 -> {
                        // Server ignored Range or we asked from 0: write full payload from byte 0.
                        val bodyStream = response.body?.byteStream()
                            ?: throw IOException("Empty 200 response body for $url")
                        streamIntoFile(bodyStream, partFile, append = false, startOffset = 0L, onProgressBytes)
                        return
                    }
                    416 -> {
                        if (!attemptedFullFallback && offset > 0L) {
                            partFile.delete()
                            offset = 0L
                            attemptedFullFallback = true
                            // Retry from byte 0
                        } else {
                            partFile.delete()
                            throw IOException("HTTP 416 Range Not Satisfiable for $url")
                        }
                    }
                    else -> {
                        throw IOException("GET $url failed with HTTP ${response.code}")
                    }
                }
            }
        }
    }

    private fun streamIntoFile(
        inputStream: java.io.InputStream,
        partFile: File,
        append: Boolean,
        startOffset: Long,
        onProgressBytes: (Long) -> Unit
    ) {
        partFile.parentFile?.mkdirs()
        var writtenTotal = startOffset
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        FileOutputStream(partFile, append).use { out ->
            while (true) {
                val read = inputStream.read(buffer)
                if (read < 0) break
                if (read > 0) {
                    out.write(buffer, 0, read)
                    writtenTotal += read
                    onProgressBytes(writtenTotal)
                }
            }
            out.flush()
            out.fd.sync()
        }
    }

    private fun tryReuseVerifiedStaticArtifact(
        artifact: OtaModelArtifact,
        targetBundleDir: File,
        previousBundleDir: File?
    ): File? {
        val targetFile = File(targetBundleDir, artifact.filename)
        if (verifyFileMatchesArtifact(targetFile, artifact)) {
            return targetFile
        }

        val candidates = buildList {
            if (previousBundleDir != null && previousBundleDir != targetBundleDir) {
                add(File(previousBundleDir, artifact.filename))
            }
            add(File(staticCacheDir(), artifact.filename))
            bundlesRootDir.listFiles()?.forEach { child ->
                if (child.isDirectory && child != targetBundleDir && child.name != STATIC_CACHE_DIR_NAME) {
                    add(File(child, artifact.filename))
                }
            }
        }

        for (candidate in candidates) {
            if (!verifyFileMatchesArtifact(candidate, artifact)) continue
            if (linkOrAtomicCopy(candidate, targetFile)) {
                return targetFile
            }
        }
        return null
    }

    private fun mirrorToStaticCache(verifiedFile: File, artifact: OtaModelArtifact) {
        runCatching {
            val cacheDir = staticCacheDir()
            cacheDir.mkdirs()
            val cacheFile = File(cacheDir, artifact.filename)
            if (!verifyFileMatchesArtifact(cacheFile, artifact)) {
                linkOrAtomicCopy(verifiedFile, cacheFile)
            }
        }
    }

    private fun linkOrAtomicCopy(source: File, destination: File): Boolean {
        destination.parentFile?.mkdirs()
        val tmp = File(destination.parentFile, "${destination.name}.tmp")
        if (tmp.exists()) tmp.delete()
        val linked = runCatching {
            Files.createLink(tmp.toPath(), source.toPath())
            true
        }.getOrDefault(false)

        if (!linked) {
            val copied = runCatching {
                FileInputStream(source).use { input ->
                    FileOutputStream(tmp).use { output ->
                        input.copyTo(output, STREAM_BUFFER_BYTES)
                        output.flush()
                        output.fd.sync()
                    }
                }
                true
            }.getOrDefault(false)
            if (!copied) {
                tmp.delete()
                return false
            }
        }
        return runCatching {
            atomicPromoteToTarget(tmp, destination)
            true
        }.getOrElse {
            tmp.delete()
            false
        }
    }

    private fun atomicPromoteToTarget(stagingFile: File, targetFile: File): File {
        targetFile.parentFile?.mkdirs()
        val tmpFile = if (stagingFile.name.endsWith(".tmp")) {
            stagingFile
        } else {
            val staged = File(targetFile.parentFile, "${targetFile.name}.tmp")
            if (staged.exists()) staged.delete()
            if (!stagingFile.renameTo(staged)) {
                throw IOException("Failed staging ${stagingFile.name} -> ${staged.name}")
            }
            staged
        }

        if (targetFile.exists() && !targetFile.delete()) {
            tmpFile.delete()
            throw IOException("Failed replacing existing OTA artifact: ${targetFile.absolutePath}")
        }
        if (!tmpFile.renameTo(targetFile)) {
            tmpFile.delete()
            throw IOException("Atomic rename failed: ${tmpFile.absolutePath} -> ${targetFile.absolutePath}")
        }
        return targetFile
    }

    private fun persistActiveBundlePointer(bundleDir: File, manifest: OtaBundleManifest) {
        bundlesRootDir.mkdirs()
        val manifestFile = File(bundleDir, MANIFEST_FILENAME)
        writeAtomicText(manifestFile, json.encodeToString(OtaBundleManifest.serializer(), manifest))
        val activePointer = File(bundlesRootDir, ACTIVE_BUNDLE_POINTER_FILENAME)
        writeAtomicText(activePointer, manifest.bundleVersion)
    }

    private fun writeAtomicText(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        atomicPromoteToTarget(tmp, target)
    }

    private fun loadInstalledBundleInternal(): Pair<File, OtaBundleManifest>? {
        if (!bundlesRootDir.isDirectory) return null
        val pointerFile = File(bundlesRootDir, ACTIVE_BUNDLE_POINTER_FILENAME)
        if (pointerFile.isFile && pointerFile.canRead()) {
            val activeVersion = runCatching { pointerFile.readText(Charsets.UTF_8).trim() }.getOrNull()
            if (!activeVersion.isNullOrBlank() && SAFE_SEGMENT_REGEX.matches(activeVersion)) {
                val dir = bundleDirForVersion(activeVersion)
                val manifest = readManifestFromDir(dir)
                if (manifest != null) return dir to manifest
            }
        }

        // Fallback scan for highest verified loraVersion subdirectory when active pointer is absent.
        var best: Pair<File, OtaBundleManifest>? = null
        bundlesRootDir.listFiles()?.forEach { child ->
            if (!child.isDirectory || child.name == STATIC_CACHE_DIR_NAME || child.name.startsWith(".")) {
                return@forEach
            }
            val manifest = readManifestFromDir(child) ?: return@forEach
            if (best == null || manifest.loraVersion > best.second.loraVersion) {
                best = child to manifest
            }
        }
        return best
    }

    private fun readManifestFromDir(dir: File): OtaBundleManifest? {
        val manifestFile = File(dir, MANIFEST_FILENAME)
        if (!manifestFile.isFile || !manifestFile.canRead()) return null
        return runCatching {
            json.decodeFromString(OtaBundleManifest.serializer(), manifestFile.readText(Charsets.UTF_8))
        }.getOrNull()
    }

    private fun bundleDirForVersion(bundleVersion: String): File {
        require(SAFE_SEGMENT_REGEX.matches(bundleVersion) && bundleVersion != "." && bundleVersion != "..") {
            "Unsafe bundle_version rejected: '$bundleVersion'"
        }
        return File(bundlesRootDir, bundleVersion)
    }

    private fun staticCacheDir(): File = File(bundlesRootDir, STATIC_CACHE_DIR_NAME)

    private fun buildArtifactUrl(baseUrl: String, bundleVersion: String, artifact: OtaModelArtifact): String {
        val customPath = artifact.downloadPath?.trim()?.takeIf { it.isNotEmpty() }
        return if (customPath != null) {
            if (customPath.startsWith("http://") || customPath.startsWith("https://")) {
                customPath
            } else {
                "$baseUrl/${customPath.trimStart('/')}"
            }
        } else {
            "$baseUrl/v1/bundles/$bundleVersion/${artifact.filename}"
        }
    }

    private fun failSync(
        baseUrl: String,
        status: OtaBundleStatus,
        message: String
    ): Result<OtaBundleManifest> {
        _bundleState.value = _bundleState.value.copy(
            status = status,
            tailscaleBaseUrl = baseUrl,
            errorMessage = message
        )
        return Result.failure(IllegalStateException(message))
    }

    companion object {
        const val BUNDLES_DIR_NAME: String = "bundles"
        const val STATIC_CACHE_DIR_NAME: String = "_static"
        const val MANIFEST_FILENAME: String = "bundle_manifest.json"
        const val ACTIVE_BUNDLE_POINTER_FILENAME: String = "active_bundle.txt"
        const val SHERPA_CTC_EXTRACTED_DIR: String = "sherpa_ctc"
        private const val STREAM_BUFFER_BYTES: Int = 64 * 1024

        private val SAFE_SEGMENT_REGEX = Regex("^[A-Za-z0-9._-]+$")
        private val SHA256_HEX_REGEX = Regex("^[a-f0-9]{64}$")
        private val CONTENT_RANGE_REGEX = Regex("""^bytes\s+(\d+)-(\d+)/(\d+|\*)$""")

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

        fun normalizeBaseUrl(rawUrl: String): String? {
            val trimmed = rawUrl.trim().trimEnd('/')
            if (trimmed.isEmpty()) return null
            if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
                return null
            }
            return trimmed
        }

        fun validateSafeFilename(filename: String) {
            require(
                filename.isNotEmpty() &&
                    filename != "." &&
                    filename != ".." &&
                    SAFE_SEGMENT_REGEX.matches(filename)
            ) {
                "Unsafe OTA artifact filename rejected: '$filename'"
            }
        }

        /**
         * Strictly validates [OtaBundleManifest] against `ota_bundle_manifest.schema.json`
         * and Android 17 bare-metal mandates (`sample_rate_hz == 44100`, `<= 9500 MB` RAM ceiling).
         */
        fun validateManifest(manifest: OtaBundleManifest): String? {
            if (manifest.schemaVersion != 1) {
                return "Unsupported OTA schema_version=${manifest.schemaVersion} (expected 1)"
            }
            if (manifest.bundleVersion.isBlank() ||
                !SAFE_SEGMENT_REGEX.matches(manifest.bundleVersion) ||
                manifest.bundleVersion == "." ||
                manifest.bundleVersion == ".."
            ) {
                return "Invalid bundle_version '${manifest.bundleVersion}'"
            }
            if (manifest.loraVersion < 0) {
                return "Invalid negative lora_version=${manifest.loraVersion}"
            }
            if (manifest.createdAtUtc.isBlank()) {
                return "Missing created_at_utc in OTA manifest"
            }
            if (manifest.sampleRateHz != SYNTHESIS_SAMPLE_RATE_HZ) {
                return "Incompatible sample_rate_hz=${manifest.sampleRateHz} (must be $SYNTHESIS_SAMPLE_RATE_HZ Hz)"
            }
            if (manifest.foregroundRamCeilingMb !in 512..FOREGROUND_RAM_CEILING_MB) {
                return "Invalid foreground_ram_ceiling_mb=${manifest.foregroundRamCeilingMb} (ceiling <= $FOREGROUND_RAM_CEILING_MB MB)"
            }
            if (manifest.artifacts.isEmpty()) {
                return "OTA manifest contains zero artifacts"
            }
            val totalRamMb = manifest.totalEstimatedRamMb()
            if (totalRamMb > manifest.foregroundRamCeilingMb || totalRamMb > FOREGROUND_RAM_CEILING_MB) {
                return "OTA bundle RAM budget exceeded: ${totalRamMb} MB > ${manifest.foregroundRamCeilingMb} MB ceiling"
            }
            for (art in manifest.artifacts) {
                if (!SAFE_SEGMENT_REGEX.matches(art.filename) || art.filename == "." || art.filename == "..") {
                    return "Unsafe artifact filename '${art.filename}'"
                }
                if (!SHA256_HEX_REGEX.matches(art.sha256)) {
                    return "Invalid sha256 digest for '${art.filename}'"
                }
                if (art.sizeBytes <= 0L) {
                    return "Invalid size_bytes=${art.sizeBytes} for '${art.filename}'"
                }
                if (art.estimatedRamMb <= 0) {
                    return "Invalid estimated_ram_mb=${art.estimatedRamMb} for '${art.filename}'"
                }
                if (art.format == OtaArtifactFormat.TAR_GZ && art.role != OtaArtifactRole.SHERPA_CTC_ALIGNER) {
                    return "Unexpected tar_gz archive for non-CTC role ${art.role}"
                }
            }
            return null
        }

        fun verifyFileMatchesArtifact(file: File, artifact: OtaModelArtifact): Boolean {
            if (!file.isFile || !file.canRead()) return false
            if (file.length() != artifact.sizeBytes) return false
            return computeSha256Hex(file) == artifact.sha256.lowercase(Locale.US)
        }

        fun computeSha256Hex(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(STREAM_BUFFER_BYTES)
            FileInputStream(file).use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) digest.update(buf, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun computeSha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { "%02x".format(it) }
        }

        internal fun isMatchingContentRangeStart(
            contentRangeHeader: String?,
            expectedStart: Long,
            expectedTotalSize: Long
        ): Boolean {
            if (contentRangeHeader.isNullOrBlank()) return false
            val match = CONTENT_RANGE_REGEX.matchEntire(contentRangeHeader.trim()) ?: return false
            val start = match.groupValues[1].toLongOrNull() ?: return false
            val totalStr = match.groupValues[3]
            if (start != expectedStart) return false
            if (totalStr != "*") {
                val total = totalStr.toLongOrNull() ?: return false
                if (total != expectedTotalSize) return false
            }
            return true
        }
    }
}
