package io.github.ioannes78.voica.fsmn

import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import io.github.ioannes78.voica.audio.PcmReadResult
import io.github.ioannes78.voica.audio.PcmSource
import io.github.ioannes78.voica.model.ModelCandidateValidator
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.model.Stage16BCandidateRuntimes
import java.io.File

/**
 * Stage 16B candidate validator for the dedicated FSMN VAD runtime.
 *
 * ModelStorage has already verified package/file hashes before this point. This validator adds the
 * runtime boundary: native library load, shared ONNX Runtime resolution, model/CMVN initialization,
 * and one bounded inference pass. It deliberately does not assert segmentation quality; that belongs
 * to the real-device VAD Matrix Gate.
 */
class FsmnModelCandidateValidator : ModelCandidateValidator {
    override suspend fun validate(
        descriptor: ModelDescriptor,
        installedDirectory: File,
    ) {
        require(descriptor.kind == ModelKind.VAD) {
            "FSMN candidate validator requires a VAD descriptor"
        }
        require(descriptor.runtimeId == Stage16BCandidateRuntimes.FSMN_VAD_ID) {
            "FSMN candidate runtime mismatch: ${descriptor.runtimeId}"
        }
        require(installedDirectory.isDirectory) {
            "FSMN candidate install directory is missing"
        }

        val modelFile = requiredFile(descriptor, installedDirectory, MODEL_FILE)
        val cmvnFile = requiredFile(descriptor, installedDirectory, CMVN_FILE)
        val source = SilenceValidationPcmSource(VALIDATION_SAMPLE_COUNT)
        try {
            FsmnVadEngine(
                model = descriptor,
                modelLocation =
                    FsmnVadModelLocation(
                        modelPath = modelFile.absolutePath,
                        cmvnPath = cmvnFile.absolutePath,
                    ),
                settings = FsmnVadSettings(numThreads = 1),
            ).use { engine ->
                val segments = engine.analyze(source)
                segments.forEach { segment ->
                    check(segment.startSampleIndex >= 0L)
                    check(segment.endSampleIndexExclusive <= VALIDATION_SAMPLE_COUNT)
                    check(segment.endSampleIndexExclusive > segment.startSampleIndex)
                }
            }
        } finally {
            source.close()
        }
    }

    private fun requiredFile(
        descriptor: ModelDescriptor,
        installedDirectory: File,
        relativePath: String,
    ): File {
        require(descriptor.files.any { it.relativePath == relativePath }) {
            "FSMN candidate descriptor is missing $relativePath"
        }
        return File(installedDirectory, relativePath).also { file ->
            require(file.isFile && file.length() > 0L) {
                "FSMN candidate file is missing or empty: $relativePath"
            }
        }
    }

    private companion object {
        const val MODEL_FILE = "model_quant.onnx"
        const val CMVN_FILE = "vad.mvn"
        const val VALIDATION_SAMPLE_COUNT = CanonicalPcmProfile.SAMPLE_RATE_HZ
    }
}

private class SilenceValidationPcmSource(
    override val totalSampleCount: Long,
) : PcmSource {
    override val sampleRateHz: Int = CanonicalPcmProfile.SAMPLE_RATE_HZ
    override val channelCount: Int = CanonicalPcmProfile.CHANNEL_COUNT
    private var position = 0L
    private var closed = false

    override suspend fun read(
        target: ShortArray,
        targetOffset: Int,
        maxSamples: Int,
    ): PcmReadResult? {
        check(!closed) { "validation PCM source is closed" }
        require(targetOffset >= 0 && maxSamples >= 0 && targetOffset + maxSamples <= target.size)
        if (position >= totalSampleCount) return null
        val count = minOf(maxSamples.toLong(), totalSampleCount - position).toInt()
        target.fill(0, targetOffset, targetOffset + count)
        val result = PcmReadResult(startSampleIndex = position, sampleCount = count)
        position += count
        return result
    }

    override fun close() {
        closed = true
    }
}
