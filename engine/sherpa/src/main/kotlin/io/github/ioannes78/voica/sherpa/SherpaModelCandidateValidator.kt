package io.github.ioannes78.voica.sherpa

import io.github.ioannes78.voica.audio.PcmReadResult
import io.github.ioannes78.voica.audio.PcmSource
import io.github.ioannes78.voica.model.ModelCandidateValidator
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.model.ModelKind
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
            ModelKind.SPEAKER,
            ModelKind.ASR_LARGE,
            -> error(
                "no Stage 8 smoke validator for model kind " + descriptor.kind,
            )
        }
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
            SherpaStreamingZipformerEngine(
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
    }
}
