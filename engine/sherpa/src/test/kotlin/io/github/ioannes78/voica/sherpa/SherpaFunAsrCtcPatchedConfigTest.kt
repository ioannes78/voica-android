package io.github.ioannes78.voica.sherpa

import io.github.ioannes78.voica.model.AsrExecutionMode
import io.github.ioannes78.voica.model.ModelCapabilities
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.model.ModelFileDescriptor
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.model.ModelPackageFormat
import io.github.ioannes78.voica.model.ModelSourceType
import io.github.ioannes78.voica.model.RedistributionPolicy
import io.github.ioannes78.voica.model.TimestampCapability
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SherpaFunAsrCtcPatchedConfigTest {
    @Test
    fun stockLikeConfigFailsClosed() {
        val bundle = bundle()

        assertThrows(IllegalStateException::class.java) {
            configurePatchedFunAsrCtcModelConfig(StockLikeConfig(), bundle)
        }
    }

    @Test
    fun patchedConfigReceivesExactCompactScorerPath() {
        val bundle = bundle()
        val config = PatchedConfig()

        configurePatchedFunAsrCtcModelConfig(config, bundle)

        assertEquals(bundle.compactScorer.absolutePath, config.ctcCompact)
    }

    private fun bundle(): FunAsrCtcBundle {
        val root = Files.createTempDirectory("funasr-patched-config").toFile()
        val scorer = File(root, FunAsrCtcBundleLayout.COMPACT_SCORER).apply { writeBytes(byteArrayOf(1)) }
        val encoder = File(root, FunAsrCtcBundleLayout.ENCODER_ADAPTOR).apply { writeBytes(byteArrayOf(1)) }
        val tokenizer = File(root, FunAsrCtcBundleLayout.TOKENIZER).apply { writeBytes(byteArrayOf(1)) }
        val metadata = File(root, FunAsrCtcBundleLayout.METADATA).apply { writeText("test") }
        return FunAsrCtcBundle(
            encoderAdaptor = encoder,
            compactScorer = scorer,
            tokenizer = tokenizer,
            metadataFile = metadata,
            metadata =
                FunAsrCtcBundleMetadata(
                    schemaVersion = 1,
                    scoreDomain = "raw_logits",
                    encoderPostAdaptorOutputIndex = 0,
                    encoderPostAdaptorOutputName = "encoder_out",
                    encoderPreAdaptorOutputIndex = 1,
                    encoderPreAdaptorOutputName = "ctc_encoder_out",
                    encoderPreAdaptorLengthOutputIndex = 2,
                    encoderPreAdaptorLengthOutputName = "ctc_encoder_out_lens",
                    encoderPreAdaptorDim = 512,
                    ctcEncoderInputName = "ctc_encoder_out",
                    ctcEncoderLengthInputName = "ctc_encoder_out_lens",
                    ctcCandidateIdsInputName = "candidate_ids",
                    ctcCandidateScoresOutputName = "candidate_logits",
                    ctcVocabSize = 60515,
                    blankId = 60514,
                    framePeriodUs = 60000,
                    tokenizerFormat = "sensevoice_tiktoken",
                    tokenizerFile = "multilingual.tiktoken",
                ),
        )
    }

    private class StockLikeConfig

    private class PatchedConfig {
        var ctcCompact: String = ""
    }
}
