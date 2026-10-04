package io.github.ioannes78.voica.sherpa

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import io.github.ioannes78.voica.audio.PcmReadResult
import io.github.ioannes78.voica.audio.PcmSource
import io.github.ioannes78.voica.model.ModelCandidateValidator
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.model.SpeakerModelRole
import java.io.File

class SherpaModelCandidateValidator : ModelCandidateValidator {
    override suspend fun validate(
        descriptor: ModelDescriptor,
        installedDirectory: File,
    ) {
        require(installedDirectory.isDirectory) {
            "model candidate directory is missing"
        }

        when (descriptor.kind) {
            ModelKind.VAD -> validateVad(descriptor, installedDirectory)
            ModelKind.ASR_STREAMING ->
                validateStreamingAsr(descriptor, installedDirectory)
            ModelKind.ASR_SECOND_PASS ->
                validateSecondPassAsr(descriptor, installedDirectory)
            ModelKind.PUNCTUATION ->
                validatePunctuation(descriptor, installedDirectory)
            ModelKind.SPEAKER ->
                validateSpeaker(descriptor, installedDirectory)
            ModelKind.ASR_LARGE ->
                validateLargeOfflineAsr(descriptor, installedDirectory)
        }
    }

    private fun validateSpeaker(
        descriptor: ModelDescriptor,
        directory: File,
    ) {
        when (descriptor.speakerRole) {
            SpeakerModelRole.EMBEDDING ->
                validateSpeakerEmbedding(descriptor, directory)
            SpeakerModelRole.DIARIZATION_SEGMENTATION ->
                validateSpeakerSegmentationStructure(descriptor, directory)
            null ->
                error("speaker model candidate must declare speakerRole")
        }
    }

    private fun validateSpeakerSegmentationStructure(
        descriptor: ModelDescriptor,
        directory: File,
    ) {
        val modelFile = requireSingleOnnx(descriptor, directory)
        check(modelFile.length() > 0L) {
            "speaker segmentation ONNX file is empty"
        }
        // sherpa-onnx does not expose a standalone Kotlin segmentation session.
        // Native segmentation validation is therefore completed by the
        // Stage 9 pyannote + embedding bundle smoke test.
    }

    private fun validateSpeakerEmbedding(
        descriptor: ModelDescriptor,
        directory: File,
    ) {
        val modelFile = requireSingleOnnx(descriptor, directory)
        val extractor =
            SpeakerEmbeddingExtractor(
                assetManager = null,
                config =
                    SpeakerEmbeddingExtractorConfig(
                        model = modelFile.absolutePath,
                        numThreads = SherpaRuntime.DEFAULT_NUM_THREADS,
                        debug = false,
                        provider = SherpaRuntime.PROVIDER_CPU,
                    ),
            )
        try {
            check(extractor.dim() > 0) {
                "speaker embedding dimension must be positive"
            }
            val stream = extractor.createStream()
            try {
                stream.acceptWaveform(
                    SMOKE_SPEAKER_FLOAT_SAMPLES,
                    16_000,
                )
                stream.inputFinished()
                check(extractor.isReady(stream)) {
                    "speaker embedding model is not ready for smoke audio"
                }
                val embedding = extractor.compute(stream)
                check(embedding.size == extractor.dim()) {
                    "speaker embedding output dimension mismatch"
                }
                check(embedding.all { it.isFinite() }) {
                    "speaker embedding contains non-finite values"
                }
                check(embedding.any { it != 0F }) {
                    "speaker embedding must not be all zero"
                }
            } finally {
                stream.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun requireSingleOnnx(
        descriptor: ModelDescriptor,
        directory: File,
    ): File {
        val relativePath =
            descriptor.files
                .map { it.relativePath }
                .singleOrNull { it.endsWith(".onnx", ignoreCase = true) }
                ?: error("speaker candidate must contain exactly one ONNX model file")
        val root = directory.canonicalFile
        val file = File(root, relativePath).canonicalFile
        require(file.path.startsWith(root.path + File.separator)) {
            "speaker model file escapes candidate directory"
        }
        require(file.isFile) {
            "speaker model file is missing: $relativePath"
        }
        return file
    }

    private suspend fun validateVad(
        descriptor: ModelDescriptor,
        directory: File,
    ) {
        val modelFile =
            descriptor.files
                .singleOrNull { it.relativePath.endsWith(".onnx") }
                ?.let { File(directory, it.relativePath) }
                ?: error("Silero candidate must contain one ONNX model file")

        val engine =
            SherpaSileroVadEngine(
                model = descriptor,
                modelLocation =
                    SherpaVadModelLocation.File(
                        modelFile.canonicalPath,
                    ),
            )
        try {
            SmokePcmSource(SMOKE_PCM_SAMPLES).use { source ->
                engine.analyze(source)
            }
        } finally {
            engine.close()
        }
    }

    private suspend fun validateStreamingAsr(
        descriptor: ModelDescriptor,
        directory: File,
    ) {
        val engine =
            createSherpaStreamingAsrEngine(
                model = descriptor,
                modelDirectory = directory,
            )
        try {
            val session = engine.openSession()
            try {
                session.acceptSamples(
                    samples = SMOKE_PCM_SAMPLES,
                    offset = 0,
                    count = SMOKE_PCM_SAMPLES.size,
                )
                session.decode()
                val final = session.finishInput()
                check(final.isFinal) {
                    "streaming ASR smoke test did not produce a final result"
                }
            } finally {
                session.close()
            }
        } finally {
            engine.close()
        }
    }

    private suspend fun validateLargeOfflineAsr(
        descriptor: ModelDescriptor,
        directory: File,
    ) {
        val engine =
            createSherpaLargeOfflineAsrEngine(
                model = descriptor,
                modelDirectory = directory,
            )
        try {
            val result =
                engine.transcribe(
                    samples = SMOKE_PCM_SAMPLES,
                    sampleRateHz = 16_000,
                )
            check(result.isFinal) {
                "large offline ASR smoke test did not produce a final result"
            }
        } finally {
            engine.close()
        }
    }

    private suspend fun validateSecondPassAsr(
        descriptor: ModelDescriptor,
        directory: File,
    ) {
        val engine =
            SherpaSenseVoiceEngine(
                model = descriptor,
                modelDirectory = directory,
            )
        try {
            val result =
                engine.transcribe(
                    samples = SMOKE_PCM_SAMPLES,
                    sampleRateHz = 16_000,
                )
            check(result.isFinal) {
                "second-pass ASR smoke test did not produce a final result"
            }
        } finally {
            engine.close()
        }
    }

    private suspend fun validatePunctuation(
        descriptor: ModelDescriptor,
        directory: File,
    ) {
        val engine =
            SherpaCtTransformerPunctuationEngine(
                model = descriptor,
                modelDirectory = directory,
            )
        try {
            engine.addPunctuation("今天我们开会")
        } finally {
            engine.close()
        }
    }

    private class SmokePcmSource(
        private val samples: ShortArray,
    ) : PcmSource {
        override val sampleRateHz = 16_000
        override val channelCount = 1
        override val totalSampleCount = samples.size.toLong()
        private var cursor = 0

        override suspend fun read(
            target: ShortArray,
            targetOffset: Int,
            maxSamples: Int,
        ): PcmReadResult? {
            if (cursor >= samples.size) return null
            val count = minOf(maxSamples, samples.size - cursor)
            samples.copyInto(
                destination = target,
                destinationOffset = targetOffset,
                startIndex = cursor,
                endIndex = cursor + count,
            )
            val start = cursor.toLong()
            cursor += count
            return PcmReadResult(
                startSampleIndex = start,
                sampleCount = count,
            )
        }

        override fun close() = Unit
    }

    private companion object {
        val SMOKE_PCM_SAMPLES = ShortArray(16_000)
        val SMOKE_SPEAKER_FLOAT_SAMPLES =
            FloatArray(48_000) { index ->
                if ((index / 80) % 2 == 0) 0.02F else -0.02F
            }
    }
}
