package io.github.ioannes78.voica.sherpa

import io.github.ioannes78.voica.model.ModelDescriptor
import java.io.File
import java.util.Properties

/**
 * Revision-2 contract for FunASR Nano true CTC timing.
 *
 * Revision 1 intentionally has no CTC bundle. Revision 2 keeps the existing
 * encoder_adaptor output at index 0 so an unmodified sherpa-onnx v1.13.8 text
 * path can continue to consume the post-adaptor LLM embedding. A second output
 * at index 1 exposes the pre-adaptor 512-d encoder state for the CTC branch.
 *
 * The compact CTC scorer accepts only the final target ids plus blank and emits
 * candidate raw logits. Full-vocabulary log-softmax is unnecessary for Viterbi
 * forced alignment because the per-frame log-softmax normalization term is the
 * same for every CTC state and therefore cannot change the best path.
 */
internal object FunAsrCtcBundleLayout {
    const val MIN_REVISION = 2L
    const val SCHEMA_VERSION = 1

    const val ENCODER_ADAPTOR = "encoder_adaptor.int8.onnx"
    const val COMPACT_SCORER = "ctc_compact.int8.onnx"
    const val TOKENIZER = "multilingual.tiktoken"
    const val METADATA = "ctc-alignment.properties"

    const val POST_ADAPTOR_OUTPUT_INDEX = 0
    const val PRE_ADAPTOR_OUTPUT_INDEX = 1
    const val PRE_ADAPTOR_DIM = 512

    const val POST_ADAPTOR_OUTPUT_NAME = "encoder_out"
    const val PRE_ADAPTOR_OUTPUT_NAME = "ctc_encoder_out"
    const val CTC_ENCODER_INPUT_NAME = "ctc_encoder_out"
    const val CTC_ENCODER_LENGTH_INPUT_NAME = "ctc_encoder_out_lens"
    const val CTC_CANDIDATE_IDS_INPUT_NAME = "candidate_ids"
    const val CTC_CANDIDATE_SCORES_OUTPUT_NAME = "candidate_logits"

    const val TOKENIZER_FORMAT = "sensevoice_tiktoken"
    const val SCORE_DOMAIN = "raw_logits"

    val REQUIRED_PATHS =
        setOf(
            ENCODER_ADAPTOR,
            COMPACT_SCORER,
            TOKENIZER,
            METADATA,
        )
}

internal data class FunAsrCtcBundleMetadata(
    val schemaVersion: Int,
    val scoreDomain: String,
    val encoderPostAdaptorOutputIndex: Int,
    val encoderPostAdaptorOutputName: String,
    val encoderPreAdaptorOutputIndex: Int,
    val encoderPreAdaptorOutputName: String,
    val encoderPreAdaptorDim: Int,
    val ctcEncoderInputName: String,
    val ctcEncoderLengthInputName: String,
    val ctcCandidateIdsInputName: String,
    val ctcCandidateScoresOutputName: String,
    val ctcVocabSize: Int,
    val blankId: Int,
    val framePeriodUs: Long,
    val tokenizerFormat: String,
    val tokenizerFile: String,
)

internal data class FunAsrCtcBundle(
    val encoderAdaptor: File,
    val compactScorer: File,
    val tokenizer: File,
    val metadataFile: File,
    val metadata: FunAsrCtcBundleMetadata,
)

internal fun requiresFunAsrCtcBundle(descriptor: ModelDescriptor): Boolean =
    descriptor.runtimeModelType == SherpaOfflineAsrModelType.FUNASR_NANO &&
        descriptor.revision >= FunAsrCtcBundleLayout.MIN_REVISION

/**
 * Validates only the revision-2 package contract. It does not claim native CTC
 * runtime support; that remains a separate Gate until the shared-encoder JNI
 * path and actual checkpoint parity have passed.
 */
internal fun validateFunAsrCtcBundle(
    descriptor: ModelDescriptor,
    directory: File,
): FunAsrCtcBundle? {
    if (!requiresFunAsrCtcBundle(descriptor)) return null

    val root = directory.canonicalFile
    require(root.isDirectory) { "FunASR CTC model directory is missing" }

    val declaredPaths = descriptor.files.mapTo(linkedSetOf()) { it.relativePath }
    FunAsrCtcBundleLayout.REQUIRED_PATHS.forEach { path ->
        require(path in declaredPaths) {
            "FunASR revision ${descriptor.revision} must declare CTC resource: $path"
        }
    }

    fun requiredFile(relativePath: String): File =
        File(root, relativePath).canonicalFile.also { file ->
            require(file.path.startsWith(root.path + File.separator)) {
                "FunASR CTC resource escapes model directory: $relativePath"
            }
            require(file.isFile && file.length() > 0L) {
                "Missing or empty FunASR CTC resource: $relativePath"
            }
        }

    val encoderAdaptor = requiredFile(FunAsrCtcBundleLayout.ENCODER_ADAPTOR)
    val compactScorer = requiredFile(FunAsrCtcBundleLayout.COMPACT_SCORER)
    val tokenizer = requiredFile(FunAsrCtcBundleLayout.TOKENIZER)
    val metadataFile = requiredFile(FunAsrCtcBundleLayout.METADATA)

    val properties =
        Properties().apply {
            metadataFile.inputStream().buffered().use(::load)
        }

    fun required(key: String): String =
        properties.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }
            ?: error("Missing FunASR CTC metadata property: $key")

    fun requiredInt(key: String): Int =
        required(key).toIntOrNull()
            ?: error("Invalid integer FunASR CTC metadata property: $key")

    fun requiredLong(key: String): Long =
        required(key).toLongOrNull()
            ?: error("Invalid long FunASR CTC metadata property: $key")

    val metadata =
        FunAsrCtcBundleMetadata(
            schemaVersion = requiredInt("schemaVersion"),
            scoreDomain = required("scoreDomain"),
            encoderPostAdaptorOutputIndex = requiredInt("encoderPostAdaptorOutputIndex"),
            encoderPostAdaptorOutputName = required("encoderPostAdaptorOutputName"),
            encoderPreAdaptorOutputIndex = requiredInt("encoderPreAdaptorOutputIndex"),
            encoderPreAdaptorOutputName = required("encoderPreAdaptorOutputName"),
            encoderPreAdaptorDim = requiredInt("encoderPreAdaptorDim"),
            ctcEncoderInputName = required("ctcEncoderInputName"),
            ctcEncoderLengthInputName = required("ctcEncoderLengthInputName"),
            ctcCandidateIdsInputName = required("ctcCandidateIdsInputName"),
            ctcCandidateScoresOutputName = required("ctcCandidateScoresOutputName"),
            ctcVocabSize = requiredInt("ctcVocabSize"),
            blankId = requiredInt("blankId"),
            framePeriodUs = requiredLong("framePeriodUs"),
            tokenizerFormat = required("tokenizerFormat"),
            tokenizerFile = required("tokenizerFile"),
        )

    require(metadata.schemaVersion == FunAsrCtcBundleLayout.SCHEMA_VERSION) {
        "Unsupported FunASR CTC metadata schema: ${metadata.schemaVersion}"
    }
    require(metadata.scoreDomain == FunAsrCtcBundleLayout.SCORE_DOMAIN) {
        "FunASR CTC scorer must expose raw_logits"
    }
    require(
        metadata.encoderPostAdaptorOutputIndex ==
            FunAsrCtcBundleLayout.POST_ADAPTOR_OUTPUT_INDEX &&
            metadata.encoderPostAdaptorOutputName ==
            FunAsrCtcBundleLayout.POST_ADAPTOR_OUTPUT_NAME,
    ) {
        "FunASR revision 2 must preserve encoder_adaptor output[0]=encoder_out"
    }
    require(
        metadata.encoderPreAdaptorOutputIndex ==
            FunAsrCtcBundleLayout.PRE_ADAPTOR_OUTPUT_INDEX &&
            metadata.encoderPreAdaptorOutputName ==
            FunAsrCtcBundleLayout.PRE_ADAPTOR_OUTPUT_NAME,
    ) {
        "FunASR revision 2 must expose encoder_adaptor output[1]=ctc_encoder_out"
    }
    require(metadata.encoderPreAdaptorDim == FunAsrCtcBundleLayout.PRE_ADAPTOR_DIM) {
        "FunASR CTC pre-adaptor encoder dimension must be 512"
    }
    require(metadata.ctcEncoderInputName == FunAsrCtcBundleLayout.CTC_ENCODER_INPUT_NAME)
    require(
        metadata.ctcEncoderLengthInputName ==
            FunAsrCtcBundleLayout.CTC_ENCODER_LENGTH_INPUT_NAME,
    )
    require(
        metadata.ctcCandidateIdsInputName ==
            FunAsrCtcBundleLayout.CTC_CANDIDATE_IDS_INPUT_NAME,
    )
    require(
        metadata.ctcCandidateScoresOutputName ==
            FunAsrCtcBundleLayout.CTC_CANDIDATE_SCORES_OUTPUT_NAME,
    )
    require(metadata.ctcVocabSize > 1) { "FunASR CTC vocabulary size must be > 1" }
    require(metadata.blankId in 0 until metadata.ctcVocabSize) {
        "FunASR CTC blank id is outside the declared vocabulary"
    }
    require(metadata.framePeriodUs == FUNASR_CTC_FRAME_PERIOD_US) {
        "FunASR CTC frame period must remain the verified 60 ms contract"
    }
    require(metadata.tokenizerFormat == FunAsrCtcBundleLayout.TOKENIZER_FORMAT) {
        "FunASR CTC tokenizer format must be sensevoice_tiktoken"
    }
    require(metadata.tokenizerFile == FunAsrCtcBundleLayout.TOKENIZER) {
        "FunASR CTC tokenizer file must be multilingual.tiktoken"
    }

    return FunAsrCtcBundle(
        encoderAdaptor = encoderAdaptor,
        compactScorer = compactScorer,
        tokenizer = tokenizer,
        metadataFile = metadataFile,
        metadata = metadata,
    )
}
