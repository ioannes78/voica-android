package io.github.ioannes78.voica.sherpa

import com.k2fsa.sherpa.onnx.OfflinePunctuation
import com.k2fsa.sherpa.onnx.OfflinePunctuationConfig
import com.k2fsa.sherpa.onnx.OfflinePunctuationModelConfig
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.transcript.PunctuationEngine
import java.io.File

object CtTransformerPunctuationLayout {
    const val MODEL = "model.int8.onnx"
}

data class PunctuationSettings(
    val numThreads: Int = SherpaRuntime.DEFAULT_NUM_THREADS,
    val debug: Boolean = false,
) {
    init {
        require(numThreads > 0)
    }
}

class SherpaCtTransformerPunctuationEngine internal constructor(
    override val model: ModelDescriptor,
    private val native: NativePunctuationProcessor,
) : PunctuationEngine {
    private var closed = false

    constructor(
        model: ModelDescriptor,
        modelDirectory: File,
        settings: PunctuationSettings = PunctuationSettings(),
    ) : this(
        model = model,
        native =
            SherpaNativePunctuationProcessor(
                modelFile =
                    File(modelDirectory, CtTransformerPunctuationLayout.MODEL)
                        .canonicalFile
                        .also { file ->
                            require(modelDirectory.isDirectory) {
                                "punctuation model directory is missing"
                            }
                            require(file.isFile) {
                                "missing punctuation model file: " +
                                    CtTransformerPunctuationLayout.MODEL
                            }
                            require(
                                file.path.startsWith(
                                    modelDirectory.canonicalFile.path + File.separator,
                                ),
                            ) {
                                "punctuation model file escapes model directory"
                            }
                        },
                settings = settings,
            ),
    )

    init {
        require(model.kind == ModelKind.PUNCTUATION) {
            "CT-Transformer engine requires a PUNCTUATION model descriptor"
        }
    }

    override suspend fun addPunctuation(text: String): String {
        check(!closed) { "punctuation engine is closed" }
        if (text.isBlank()) return text
        return native.addPunctuation(text)
    }

    override fun close() {
        if (closed) return
        closed = true
        native.close()
    }
}

internal interface NativePunctuationProcessor : AutoCloseable {
    fun addPunctuation(text: String): String
}

private class SherpaNativePunctuationProcessor(
    modelFile: File,
    settings: PunctuationSettings,
) : NativePunctuationProcessor {
    private val delegate =
        OfflinePunctuation(
            assetManager = null,
            config =
                OfflinePunctuationConfig(
                    model =
                        OfflinePunctuationModelConfig(
                            ctTransformer = modelFile.absolutePath,
                            numThreads = settings.numThreads,
                            debug = settings.debug,
                            provider = SherpaRuntime.PROVIDER_CPU,
                        ),
                ),
        )
    private var closed = false

    override fun addPunctuation(text: String): String {
        check(!closed)
        return delegate.addPunctuation(text)
    }

    override fun close() {
        if (closed) return
        closed = true
        delegate.release()
    }
}
