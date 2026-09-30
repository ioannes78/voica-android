package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.AudioPipelineException
import io.github.ioannes78.voica.audio.CanonicalAudioStage
import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import io.github.ioannes78.voica.audio.PcmWavToCanonicalWavConverter
import io.github.ioannes78.voica.audio.RawOpusToCanonicalWavConverter
import io.github.ioannes78.voica.audio.RawOpusValidationResult
import io.github.ioannes78.voica.audio.RawOpusValidator
import io.github.ioannes78.voica.audio.WavParseResult
import io.github.ioannes78.voica.audio.WavPcmParser
import io.github.ioannes78.voica.database.AudioAssetRole
import io.github.ioannes78.voica.database.AudioDerivationState
import io.github.ioannes78.voica.database.AudioIntegrityState
import io.github.ioannes78.voica.database.AudioValidationState
import io.github.ioannes78.voica.database.CanonicalConversionSource
import io.github.ioannes78.voica.database.CanonicalWavRegistration
import io.github.ioannes78.voica.database.RecordingAsset
import io.github.ioannes78.voica.database.RecordingLibraryRepository
import io.github.ioannes78.voica.opus.NativeOpusBackend
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface CanonicalGenerationOutcome {
    data object Ready : CanonicalGenerationOutcome
    data object AlreadyReady : CanonicalGenerationOutcome
    data object NoSource : CanonicalGenerationOutcome
    data class Failed(
        val code: String,
        val recoverable: Boolean,
        val detail: String?,
    ) : CanonicalGenerationOutcome
}

class CanonicalAudioCoordinator(
    private val repository: RecordingLibraryRepository,
    private val recordingsRoot: File,
    private val applicationScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val serial = Mutex()
    private val canonicalDir = File(recordingsRoot, CANONICAL_DIRECTORY)
    private val activeTokens = ConcurrentHashMap<String, AtomicBoolean>()
    private val automaticLock = Any()
    private val automaticJobs = mutableMapOf<String, Job>()
    private val automaticRerun = mutableSetOf<String>()

    fun requestAutomatic(recordingId: String) {
        synchronized(automaticLock) {
            if (automaticJobs[recordingId]?.isActive == true) {
                automaticRerun += recordingId
                return
            }

            lateinit var job: Job
            job = applicationScope.launch(start = CoroutineStart.LAZY) {
                try {
                    do {
                        synchronized(automaticLock) {
                            automaticRerun.remove(recordingId)
                        }
                        generate(recordingId)
                        val rerun = synchronized(automaticLock) {
                            automaticRerun.remove(recordingId)
                        }
                    } while (rerun)
                } finally {
                    synchronized(automaticLock) {
                        if (automaticJobs[recordingId] === job) {
                            automaticJobs.remove(recordingId)
                        }
                    }
                }
            }
            automaticJobs[recordingId] = job
            job.start()
        }
    }

    fun cancel(recordingId: String) {
        activeTokens[recordingId]?.set(true)
        synchronized(automaticLock) {
            automaticRerun.remove(recordingId)
            automaticJobs.remove(recordingId)?.cancel()
        }
    }

    suspend fun generate(recordingId: String): CanonicalGenerationOutcome =
        serial.withLock {
            val token = AtomicBoolean(false)
            activeTokens[recordingId] = token
            try {
                generateLocked(recordingId, token)
            } finally {
                activeTokens.remove(recordingId, token)
            }
        }

    suspend fun reconcileOnStartup() {
        val interrupted = repository.loadInterruptedCanonicalDerivations(PROFILE_ID)
        withContext(ioDispatcher) {
            canonicalDir.mkdirs()
            canonicalDir.listFiles()
                .orEmpty()
                .filter { it.isFile && it.name.endsWith(".part") }
                .forEach(File::delete)
        }

        interrupted.forEach { derivation ->
            val target = targetFile(
                recordingId = derivation.recordingId,
                sourceSha256 = derivation.sourceSha256,
                pipelineVersion = derivation.pipelineVersion,
            )
            val recovered = withContext(ioDispatcher) {
                inspectCanonicalFile(target)
            }
            if (recovered != null) {
                repository.commitCanonicalWav(
                    CanonicalWavRegistration(
                        recordingId = derivation.recordingId,
                        sourceAssetId = derivation.sourceAssetId,
                        sourceSha256 = derivation.sourceSha256,
                        profileId = derivation.profileId,
                        pipelineVersion = derivation.pipelineVersion,
                        relativePath = relativePath(target),
                        sizeBytes = target.length(),
                        sha256 = recovered.sha256,
                        sampleRateHz = CanonicalPcmProfile.SAMPLE_RATE_HZ,
                        channelCount = CanonicalPcmProfile.CHANNEL_COUNT,
                        verifiedAtMs = nowMs(),
                    ),
                )
            }
        }

        repository.reconcileInterruptedCanonicalDerivations(PROFILE_ID)
    }

    private suspend fun generateLocked(
        recordingId: String,
        cancellationToken: AtomicBoolean,
    ): CanonicalGenerationOutcome {
        val source = repository.loadCanonicalConversionSource(
            recordingId = recordingId,
            profileId = PROFILE_ID,
        ) ?: return CanonicalGenerationOutcome.NoSource

        if (
            source.derivationState == AudioDerivationState.READY &&
            source.existingCanonical != null &&
            withContext(ioDispatcher) { isCanonicalAssetValid(source.existingCanonical) }
        ) {
            return CanonicalGenerationOutcome.AlreadyReady
        }

        source.existingCanonical?.let { existing ->
            val valid = withContext(ioDispatcher) { isCanonicalAssetValid(existing) }
            if (!valid) {
                repository.markAssetIntegrity(
                    assetId = existing.assetId,
                    integrityState = AudioIntegrityState.CORRUPTED,
                    validationState = AudioValidationState.CORRUPTED,
                )
            }
        }

        val sourceFile = runCatching {
            managedFile(source.sourceRelativePath)
        }.getOrElse { error ->
            return fail(
                source = source,
                code = "RAW_FILE_MISSING",
                recoverable = false,
                detail = error.message,
            )
        }

        if (!sourceFile.isFile) {
            repository.markAssetIntegrity(
                assetId = source.sourceAssetId,
                integrityState = AudioIntegrityState.MISSING,
                validationState = AudioValidationState.CORRUPTED,
            )
            return fail(
                source = source,
                code = "RAW_FILE_MISSING",
                recoverable = false,
                detail = source.sourceRelativePath,
            )
        }
        if (sourceFile.length() != source.sourceSizeBytes) {
            repository.markAssetIntegrity(
                assetId = source.sourceAssetId,
                integrityState = AudioIntegrityState.CORRUPTED,
                validationState = AudioValidationState.CORRUPTED,
            )
            return fail(
                source = source,
                code = "RAW_SIZE_MISMATCH",
                recoverable = false,
                detail = "expected=${source.sourceSizeBytes} actual=${sourceFile.length()}",
            )
        }

        val verifiedSourceSha = withContext(ioDispatcher) { sha256(sourceFile) }
        if (!verifiedSourceSha.equals(source.sourceSha256, ignoreCase = true)) {
            repository.markAssetIntegrity(
                assetId = source.sourceAssetId,
                integrityState = AudioIntegrityState.CORRUPTED,
                validationState = AudioValidationState.CORRUPTED,
            )
            return fail(
                source = source,
                code = "RAW_SHA_MISMATCH",
                recoverable = false,
                detail = "source SHA-256 no longer matches Room",
            )
        }

        repository.updateCanonicalDerivation(
            recordingId = source.recordingId,
            sourceAssetId = source.sourceAssetId,
            sourceSha256 = source.sourceSha256,
            profileId = PROFILE_ID,
            pipelineVersion = PIPELINE_VERSION,
            state = AudioDerivationState.PREPARING,
        )

        val callerJob = currentCoroutineContext()[Job]
        return try {
            when (source.sourceRole) {
                AudioAssetRole.DEVICE_OPUS ->
                    generateFromOpus(
                        source,
                        sourceFile,
                        callerJob,
                        cancellationToken,
                    )

                AudioAssetRole.DEVICE_WAV ->
                    generateFromDeviceWav(
                        source,
                        sourceFile,
                        callerJob,
                        cancellationToken,
                    )

                else ->
                    fail(
                        source = source,
                        code = "UNSUPPORTED_CONTAINER",
                        recoverable = false,
                        detail = "unsupported source role=${source.sourceRole}",
                    )
            }
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                terminalFailure(
                    source = source,
                    state = AudioDerivationState.CANCELLED,
                    code = "USER_CANCELLED",
                    detail = "conversion cancelled",
                )
            }
            throw error
        } catch (error: AudioPipelineException) {
            val cancelled = error.code == "USER_CANCELLED"
            withContext(NonCancellable) {
                terminalFailure(
                    source = source,
                    state = when {
                        cancelled -> AudioDerivationState.CANCELLED
                        error.recoverable -> AudioDerivationState.FAILED_RECOVERABLE
                        else -> AudioDerivationState.FAILED_PERMANENT
                    },
                    code = error.code,
                    detail = error.message,
                )
            }
            CanonicalGenerationOutcome.Failed(
                code = error.code,
                recoverable = error.recoverable,
                detail = error.message,
            )
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                terminalFailure(
                    source = source,
                    state = AudioDerivationState.FAILED_RECOVERABLE,
                    code = "LOCAL_WRITE_FAILED",
                    detail = error.message ?: error::class.java.simpleName,
                )
            }
            CanonicalGenerationOutcome.Failed(
                code = "LOCAL_WRITE_FAILED",
                recoverable = true,
                detail = error.message,
            )
        }
    }

    private suspend fun generateFromOpus(
        source: CanonicalConversionSource,
        sourceFile: File,
        callerJob: Job?,
        cancellationToken: AtomicBoolean,
    ): CanonicalGenerationOutcome {
        val backend = NativeOpusBackend()
        val validation = withContext(ioDispatcher) {
            RawOpusValidator(backend).validate(sourceFile)
        }
        val valid = validation as? RawOpusValidationResult.Valid
        if (valid == null) {
            repository.updateSourceValidation(
                assetId = source.sourceAssetId,
                validationState = AudioValidationState.UNSUPPORTED,
                container = "DEVICE_RAW_CANDIDATE",
                codec = "OPUS",
            )
            val unsupported = validation as RawOpusValidationResult.UnsupportedFraming
            throw AudioPipelineException(
                code = "UNSUPPORTED_FRAMING",
                recoverable = false,
                message = unsupported.detail,
            )
        }

        repository.updateSourceValidation(
            assetId = source.sourceAssetId,
            validationState = AudioValidationState.VALID,
            container = "RAW_OPUS",
            codec = "OPUS",
            channelCount = valid.value.channelCount,
        )

        ensureCapacity(valid.value.decodedDurationUs)
        val target = targetFile(source.recordingId, source.sourceSha256, PIPELINE_VERSION)
        val result = withContext(ioDispatcher) {
            RawOpusToCanonicalWavConverter(
                inspector = backend,
                decoderFactory = backend,
            ).convert(
                sourceFile = sourceFile,
                targetFile = target,
                expectedSourceSha256 = source.sourceSha256,
                isCancelled = {
                    cancellationToken.get() || callerJob?.isActive == false
                },
                onStage = { stage -> persistStageBlocking(source, stage) },
            )
        }

        return commitResult(
            source = source,
            target = target,
            sizeBytes = result.wav.sizeBytes,
            sha256 = result.wav.sha256,
        )
    }

    private suspend fun generateFromDeviceWav(
        source: CanonicalConversionSource,
        sourceFile: File,
        callerJob: Job?,
        cancellationToken: AtomicBoolean,
    ): CanonicalGenerationOutcome {
        val parsed = withContext(ioDispatcher) { WavPcmParser.parse(sourceFile) }
        val info = (parsed as? WavParseResult.Valid)?.info
        if (info == null) {
            repository.updateSourceValidation(
                assetId = source.sourceAssetId,
                validationState = AudioValidationState.INVALID,
                container = "WAV",
                codec = null,
            )
            throw AudioPipelineException(
                code = "WAV_HEADER_FAILED",
                recoverable = false,
                message = (parsed as? WavParseResult.Invalid)?.reason ?: "invalid WAV",
            )
        }
        if (!info.isPcm || info.bitsPerSample != 16) {
            repository.updateSourceValidation(
                assetId = source.sourceAssetId,
                validationState = AudioValidationState.UNSUPPORTED,
                container = "WAV",
                codec = null,
                sampleRateHz = info.sampleRateHz,
                channelCount = info.channelCount,
            )
            throw AudioPipelineException(
                code = "UNSUPPORTED_WAV_PCM",
                recoverable = false,
                message = "device WAV is not PCM16",
            )
        }

        repository.updateSourceValidation(
            assetId = source.sourceAssetId,
            validationState = AudioValidationState.VALID,
            container = "WAV",
            codec = "PCM",
            sampleRateHz = info.sampleRateHz,
            channelCount = info.channelCount,
        )

        if (info.isCanonical) {
            persistStage(source, CanonicalAudioStage.VERIFYING)
            repository.updateCanonicalDerivation(
                recordingId = source.recordingId,
                sourceAssetId = source.sourceAssetId,
                sourceSha256 = source.sourceSha256,
                profileId = PROFILE_ID,
                pipelineVersion = PIPELINE_VERSION,
                state = AudioDerivationState.COMMITTING,
            )
            repository.commitCanonicalWav(
                CanonicalWavRegistration(
                    recordingId = source.recordingId,
                    sourceAssetId = source.sourceAssetId,
                    sourceSha256 = source.sourceSha256,
                    profileId = PROFILE_ID,
                    pipelineVersion = PIPELINE_VERSION,
                    relativePath = source.sourceRelativePath,
                    sizeBytes = source.sourceSizeBytes,
                    sha256 = source.sourceSha256,
                    sampleRateHz = CanonicalPcmProfile.SAMPLE_RATE_HZ,
                    channelCount = CanonicalPcmProfile.CHANNEL_COUNT,
                    verifiedAtMs = nowMs(),
                ),
            )
            cleanupPreviousCanonical(source, source.sourceRelativePath)
            return CanonicalGenerationOutcome.Ready
        }

        ensureCapacity(info.durationUs)
        val target = targetFile(source.recordingId, source.sourceSha256, PIPELINE_VERSION)
        val result = withContext(ioDispatcher) {
            PcmWavToCanonicalWavConverter().convert(
                sourceFile = sourceFile,
                targetFile = target,
                expectedSourceSha256 = source.sourceSha256,
                isCancelled = {
                    cancellationToken.get() || callerJob?.isActive == false
                },
                onStage = { stage -> persistStageBlocking(source, stage) },
            )
        }

        return commitResult(
            source = source,
            target = target,
            sizeBytes = result.wav.sizeBytes,
            sha256 = result.wav.sha256,
        )
    }

    private suspend fun commitResult(
        source: CanonicalConversionSource,
        target: File,
        sizeBytes: Long,
        sha256: String,
    ): CanonicalGenerationOutcome {
        val parsed = withContext(ioDispatcher) { WavPcmParser.parse(target) }
        val info = (parsed as? WavParseResult.Valid)?.info
        if (info == null || !info.isCanonical) {
            throw AudioPipelineException(
                code = "DERIVED_FILE_CORRUPTED",
                recoverable = true,
                message = "generated WAV failed canonical verification",
            )
        }

        repository.updateCanonicalDerivation(
            recordingId = source.recordingId,
            sourceAssetId = source.sourceAssetId,
            sourceSha256 = source.sourceSha256,
            profileId = PROFILE_ID,
            pipelineVersion = PIPELINE_VERSION,
            state = AudioDerivationState.COMMITTING,
        )
        val relative = relativePath(target)
        repository.commitCanonicalWav(
            CanonicalWavRegistration(
                recordingId = source.recordingId,
                sourceAssetId = source.sourceAssetId,
                sourceSha256 = source.sourceSha256,
                profileId = PROFILE_ID,
                pipelineVersion = PIPELINE_VERSION,
                relativePath = relative,
                sizeBytes = sizeBytes,
                sha256 = sha256,
                sampleRateHz = CanonicalPcmProfile.SAMPLE_RATE_HZ,
                channelCount = CanonicalPcmProfile.CHANNEL_COUNT,
                verifiedAtMs = nowMs(),
            ),
        )
        cleanupPreviousCanonical(source, relative)
        return CanonicalGenerationOutcome.Ready
    }

    private suspend fun fail(
        source: CanonicalConversionSource,
        code: String,
        recoverable: Boolean,
        detail: String?,
    ): CanonicalGenerationOutcome {
        terminalFailure(
            source = source,
            state = if (recoverable) {
                AudioDerivationState.FAILED_RECOVERABLE
            } else {
                AudioDerivationState.FAILED_PERMANENT
            },
            code = code,
            detail = detail,
        )
        return CanonicalGenerationOutcome.Failed(code, recoverable, detail)
    }

    private suspend fun terminalFailure(
        source: CanonicalConversionSource,
        state: String,
        code: String,
        detail: String?,
    ) {
        repository.updateCanonicalDerivation(
            recordingId = source.recordingId,
            sourceAssetId = source.sourceAssetId,
            sourceSha256 = source.sourceSha256,
            profileId = PROFILE_ID,
            pipelineVersion = PIPELINE_VERSION,
            state = state,
            errorCode = code,
            errorDetail = detail,
        )
    }

    private fun persistStageBlocking(
        source: CanonicalConversionSource,
        stage: CanonicalAudioStage,
    ) {
        runBlocking {
            persistStage(source, stage)
        }
    }

    private suspend fun persistStage(
        source: CanonicalConversionSource,
        stage: CanonicalAudioStage,
    ) {
        repository.updateCanonicalDerivation(
            recordingId = source.recordingId,
            sourceAssetId = source.sourceAssetId,
            sourceSha256 = source.sourceSha256,
            profileId = PROFILE_ID,
            pipelineVersion = PIPELINE_VERSION,
            state = when (stage) {
                CanonicalAudioStage.DECODING -> AudioDerivationState.DECODING
                CanonicalAudioStage.NORMALIZING -> AudioDerivationState.NORMALIZING
                CanonicalAudioStage.WRITING -> AudioDerivationState.WRITING
                CanonicalAudioStage.VERIFYING -> AudioDerivationState.VERIFYING
            },
        )
    }

    private fun ensureCapacity(durationUs: Long) {
        val outputBytes = (
            durationUs.coerceAtLeast(0L) *
                CanonicalPcmProfile.BYTES_PER_SECOND /
                1_000_000L
        ).coerceAtLeast(MINIMUM_OUTPUT_ESTIMATE_BYTES) + WAV_HEADER_BYTES
        val margin = maxOf(MINIMUM_STORAGE_MARGIN_BYTES, outputBytes / 10L)
        val required = outputBytes + margin
        val available = canonicalDir.parentFile?.usableSpace ?: recordingsRoot.usableSpace
        if (available < required) {
            throw AudioPipelineException(
                code = "INSUFFICIENT_STORAGE",
                recoverable = true,
                message = "required=$required available=$available",
            )
        }
    }

    private fun targetFile(
        recordingId: String,
        sourceSha256: String,
        pipelineVersion: Int,
    ): File {
        canonicalDir.mkdirs()
        return File(
            canonicalDir,
            "$recordingId-${sourceSha256.take(SHA_PATH_PREFIX)}-v$pipelineVersion.wav",
        )
    }

    private fun managedFile(relativePath: String): File {
        val root = recordingsRoot.canonicalFile
        val candidate = File(recordingsRoot, relativePath).canonicalFile
        require(
            candidate.path == root.path ||
                candidate.path.startsWith(root.path + File.separator),
        ) {
            "asset escapes recordings root"
        }
        return candidate
    }

    private fun relativePath(file: File): String {
        val root = recordingsRoot.canonicalFile.toPath()
        val candidate = file.canonicalFile.toPath()
        require(candidate.startsWith(root)) { "asset escapes recordings root" }
        return root.relativize(candidate)
            .toString()
            .replace(File.separatorChar, '/')
    }

    private fun cleanupPreviousCanonical(
        source: CanonicalConversionSource,
        newRelativePath: String,
    ) {
        val previous = source.existingCanonical ?: return
        if (
            previous.relativePath == newRelativePath ||
            !previous.relativePath.startsWith("$CANONICAL_DIRECTORY/")
        ) {
            return
        }
        runCatching { managedFile(previous.relativePath).delete() }
    }

    private fun isCanonicalAssetValid(asset: RecordingAsset): Boolean {
        val file = runCatching { managedFile(asset.relativePath) }.getOrNull() ?: return false
        if (!file.isFile || file.length() != asset.sizeBytes) return false
        if (!sha256(file).equals(asset.sha256, ignoreCase = true)) return false
        val parsed = WavPcmParser.parse(file)
        return parsed is WavParseResult.Valid && parsed.info.isCanonical
    }

    private data class CanonicalInspection(
        val sha256: String,
    )

    private fun inspectCanonicalFile(file: File): CanonicalInspection? {
        if (!file.isFile) return null
        val parsed = WavPcmParser.parse(file)
        if (parsed !is WavParseResult.Valid || !parsed.info.isCanonical) return null
        return CanonicalInspection(sha256 = sha256(file))
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(HASH_BUFFER_BYTES).use { input ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }

    companion object {
        const val PROFILE_ID = CanonicalPcmProfile.PROFILE_ID
        const val PIPELINE_VERSION = 1

        private const val CANONICAL_DIRECTORY = "canonical"
        private const val SHA_PATH_PREFIX = 12
        private const val HASH_BUFFER_BYTES = 64 * 1024
        private const val WAV_HEADER_BYTES = 44L
        private const val MINIMUM_OUTPUT_ESTIMATE_BYTES = 1L * 1024L * 1024L
        private const val MINIMUM_STORAGE_MARGIN_BYTES = 8L * 1024L * 1024L
    }
}
