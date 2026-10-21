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
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class FunAsrRevision2RecognizerWiringTest {
    @Test
    fun revisionTwoRecognizerFailsClosedWhenStockSherpaAbiHasNoCtcCompactSetter() {
        val root = Files.createTempDirectory("funasr-r2-recognizer").toFile()
        writeBaseFunAsrFiles(root)
        writeCtcBundle(root)

        val error =
            assertThrows(IllegalStateException::class.java) {
                createSherpaLargeOfflineAsrEngine(
                    model = descriptor(),
                    modelDirectory = root,
                )
            }

        assertTrue(
            error.message.orEmpty().contains("patched sherpa-onnx config ABI"),
        )
    }

    private fun writeBaseFunAsrFiles(root: File) {
        File(root, "encoder_adaptor.int8.onnx").writeBytes(byteArrayOf(1))
        File(root, "llm.int8.onnx").writeBytes(byteArrayOf(1))
        File(root, "embedding.int8.onnx").writeBytes(byteArrayOf(1))
        File(root, "Qwen3-0.6B").mkdirs()
        File(root, "Qwen3-0.6B/tokenizer.json").writeText("{}")
    }

    private fun writeCtcBundle(root: File) {
        File(root, FunAsrCtcBundleLayout.COMPACT_SCORER).writeBytes(byteArrayOf(1))
        File(root, FunAsrCtcBundleLayout.TOKENIZER).writeBytes(byteArrayOf(1))
        File(root, FunAsrCtcBundleLayout.METADATA).writeText(
            """
            schemaVersion=1
            scoreDomain=raw_logits
            encoderPostAdaptorOutputIndex=0
            encoderPostAdaptorOutputName=encoder_out
            encoderPreAdaptorOutputIndex=1
            encoderPreAdaptorOutputName=ctc_encoder_out
            encoderPreAdaptorLengthOutputIndex=2
            encoderPreAdaptorLengthOutputName=ctc_encoder_out_lens
            encoderPreAdaptorDim=512
            ctcEncoderInputName=ctc_encoder_out
            ctcEncoderLengthInputName=ctc_encoder_out_lens
            ctcCandidateIdsInputName=candidate_ids
            ctcCandidateScoresOutputName=candidate_logits
            ctcVocabSize=60515
            blankId=60514
            framePeriodUs=60000
            tokenizerFormat=sensevoice_tiktoken
            tokenizerFile=multilingual.tiktoken
            """.trimIndent() + "\n",
        )
    }

    private fun descriptor(): ModelDescriptor {
        val declared =
            linkedSetOf(
                "encoder_adaptor.int8.onnx",
                "llm.int8.onnx",
                "embedding.int8.onnx",
                FunAsrCtcBundleLayout.COMPACT_SCORER,
                FunAsrCtcBundleLayout.TOKENIZER,
                FunAsrCtcBundleLayout.METADATA,
            )
        return ModelDescriptor(
            modelId = "funasr-nano-int8",
            kind = ModelKind.ASR_LARGE,
            displayName = "FunASR Nano INT8",
            version = "2025-12-30",
            revision = 2,
            runtimeId = "sherpa-onnx",
            runtimeVersionMin = "1.13.8",
            runtimeVersionMax = null,
            languages = setOf("zh", "en"),
            capabilities =
                ModelCapabilities(
                    supportsSecondPass = true,
                    supportsHotwords = true,
                    executionMode = AsrExecutionMode.SECOND_PASS,
                    timestampCapability = TimestampCapability.EXTERNAL_ALIGNER_REQUIRED,
                ),
            sourceType = ModelSourceType.BUILTIN,
            builtinAssetPath = "models/funasr-nano-int8",
            packageFormat = ModelPackageFormat.TAR_BZ2,
            downloadUrl = null,
            downloadSizeBytes = null,
            installedSizeBytes = declared.size.toLong(),
            packageSha256 = null,
            files =
                declared.map { path ->
                    ModelFileDescriptor(
                        relativePath = path,
                        sizeBytes = 1L,
                        sha256 = DUMMY_SHA256,
                    )
                },
            abis = setOf("arm64-v8a"),
            minSdk = 26,
            appVersionMin = null,
            appVersionMax = null,
            licenseId = "Apache-2.0",
            licenseUrl = null,
            sourceUrl = "https://example.invalid/funasr",
            homepage = null,
            attribution = "test",
            redistributionPolicy = RedistributionPolicy.UPSTREAM_ONLY,
            releaseChannel = "test",
            autoUpdateEligible = false,
            runtimeModelType = SherpaOfflineAsrModelType.FUNASR_NANO,
        )
    }

    private companion object {
        const val DUMMY_SHA256 =
            "0000000000000000000000000000000000000000000000000000000000000000"
    }
}
