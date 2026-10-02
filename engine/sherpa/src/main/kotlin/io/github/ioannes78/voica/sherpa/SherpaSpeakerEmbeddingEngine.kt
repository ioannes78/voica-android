package io.github.ioannes78.voica.sherpa

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.model.SpeakerModelRole
import io.github.ioannes78.voica.transcript.DiarizationConfig
import io.github.ioannes78.voica.transcript.SpeakerEmbeddingEngine
import java.io.File

class SherpaSpeakerEmbeddingEngine internal constructor(
    override val model: ModelDescriptor,
    private val native: NativeSpeakerEmbeddingSession,
) : SpeakerEmbeddingEngine {
    private var closed = false

    constructor(
        model: ModelDescriptor,
        modelDirectory: File,
        numThreads: Int = SherpaRuntime.DEFAULT_NUM_THREADS,
    ) : this(
        model = model,
        native =
            SherpaNativeSpeakerEmbeddingSession(
                modelFile = resolveEmbeddingModelFile(model, modelDirectory),
                numThreads = numThreads,
            ),
    )

    init {
        require(model.kind == ModelKind.SPEAKER)
        require(model.speakerRole == SpeakerModelRole.EMBEDDING) {
            "speaker embedding engine requires EMBEDDING model role"
        }
    }

    override suspend fun embed(
        samples: ShortArray,
        sampleRateHz: Int,
    ): FloatArray {
        check(!closed) { "speaker embedding engine is closed" }
        require(sampleRateHz == DiarizationConfig.CANONICAL_SAMPLE_RATE_HZ) {
            "speaker embedding requires 16 kHz canonical PCM"
        }
        require(samples.isNotEmpty()) { "speaker embedding input is empty" }

        val normalized =
            FloatArray(samples.size) { index ->
                samples[index] / 32768.0F
            }
        val embedding = native.compute(normalized, sampleRateHz)
        require(embedding.isNotEmpty()) { "speaker embedding is empty" }
        require(embedding.all { it.isFinite() }) {
            "speaker embedding contains non-finite values"
        }
        require(embedding.any { it != 0F }) {
            "speaker embedding must not be all zero"
        }
        return embedding
    }

    override fun close() {
        if (closed) return
        closed = true
        native.close()
    }

    private companion object {
        fun resolveEmbeddingModelFile(
            descriptor: ModelDescriptor,
            directory: File,
        ): File {
            require(descriptor.kind == ModelKind.SPEAKER)
            require(descriptor.speakerRole == SpeakerModelRole.EMBEDDING)
            require(directory.isDirectory) { "speaker embedding directory is missing" }

            val relativePath =
                descriptor.files
                    .map { it.relativePath }
                    .singleOrNull { it.endsWith(".onnx", ignoreCase = true) }
                    ?: error("speaker embedding model must contain exactly one ONNX file")

            val root = directory.canonicalFile
            val candidate = File(root, relativePath).canonicalFile
            require(candidate.path.startsWith(root.path + File.separator)) {
                "speaker embedding file escapes model directory"
            }
            require(candidate.isFile) {
                "speaker embedding file is missing: $relativePath"
            }
            return candidate
        }
    }
}

internal interface NativeSpeakerEmbeddingSession : AutoCloseable {
    fun compute(
        samples: FloatArray,
        sampleRateHz: Int,
    ): FloatArray
}

private class SherpaNativeSpeakerEmbeddingSession(
    modelFile: File,
    numThreads: Int,
) : NativeSpeakerEmbeddingSession {
    private val extractor =
        SpeakerEmbeddingExtractor(
            assetManager = null,
            config =
                SpeakerEmbeddingExtractorConfig(
                    model = modelFile.absolutePath,
                    numThreads = numThreads,
                    debug = false,
                    provider = SherpaRuntime.PROVIDER_CPU,
                ),
        )
    private var closed = false

    init {
        require(numThreads > 0)
        check(extractor.dim() > 0) {
            "speaker embedding dimension must be positive"
        }
    }

    override fun compute(
        samples: FloatArray,
        sampleRateHz: Int,
    ): FloatArray {
        check(!closed)
        val stream = extractor.createStream()
        try {
            stream.acceptWaveform(samples, sampleRateHz)
            stream.inputFinished()
            require(extractor.isReady(stream)) {
                "speaker embedding model needs more audio"
            }
            val result = extractor.compute(stream)
            check(result.size == extractor.dim()) {
                "speaker embedding output dimension mismatch"
            }
            return result
        } finally {
            stream.release()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        extractor.release()
    }
}
