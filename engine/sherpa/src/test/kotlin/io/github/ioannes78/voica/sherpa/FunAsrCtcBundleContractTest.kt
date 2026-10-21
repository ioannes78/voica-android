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
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FunAsrCtcBundleContractTest {
    @Test
    fun revisionOneDoesNotRequireCtcBundle() {
        val descriptor = descriptor(revision = 1)
        val missing = Files.createTempDirectory("funasr-ctc-r1").resolve("missing").toFile()

        assertFalse(requiresFunAsrCtcBundle(descriptor))
        assertNull(validateFunAsrCtcBundle(descriptor, missing))
    }

    @Test
    fun revisionTwoLoadsStrictSharedEncoderContract() {
        val root = Files.createTempDirectory("funasr-ctc-r2").toFile()
        writeValidBundle(root)
        val descriptor = descriptor(revision = 2)

        val bundle = requireNotNull(validateFunAsrCtcBundle(descriptor, root))

        assertTrue(requiresFunAsrCtcBundle(descriptor))
        assertEquals(
            FunAsrCtcBundleLayout.POST_ADAPTOR_OUTPUT_INDEX,
            bundle.metadata.encoderPostAdaptorOutputIndex,
        )
        assertEquals(
            FunAsrCtcBundleLayout.PRE_ADAPTOR_OUTPUT_INDEX,
            bundle.metadata.encoderPreAdaptorOutputIndex,
        )
        assertEquals(
            FunAsrCtcBundleLayout.PRE_ADAPTOR_LENGTH_OUTPUT_INDEX,
            bundle.metadata.encoderPreAdaptorLengthOutputIndex,
        )
        assertEquals(512, bundle.metadata.encoderPreAdaptorDim)
        assertEquals(60_000L, bundle.metadata.framePeriodUs)
        assertEquals("raw_logits", bundle.metadata.scoreDomain)
    }

    @Test
    fun revisionTwoRequiresEveryCtcResourceToBeDeclaredByDescriptor() {
        val root = Files.createTempDirectory("funasr-ctc-declared").toFile()
        writeValidBundle(root)
        val descriptor =
            descriptor(
                revision = 2,
                declaredPaths =
                    FunAsrCtcBundleLayout.REQUIRED_PATHS - FunAsrCtcBundleLayout.COMPACT_SCORER,
            )

        val error =
            assertThrows(IllegalArgumentException::class.java) {
                validateFunAsrCtcBundle(descriptor, root)
            }

        assertTrue(error.message.orEmpty().contains(FunAsrCtcBundleLayout.COMPACT_SCORER))
    }

    @Test
    fun revisionTwoRefusesSharedEncoderOutputOrderThatWouldBreakRevisionOneTextPath() {
        val root = Files.createTempDirectory("funasr-ctc-output-order").toFile()
        writeValidBundle(
            root = root,
            metadataOverrides = mapOf("encoderPostAdaptorOutputIndex" to "1"),
        )

        val error =
            assertThrows(IllegalArgumentException::class.java) {
                validateFunAsrCtcBundle(descriptor(revision = 2), root)
            }

        assertTrue(error.message.orEmpty().contains("output[0]"))
    }

    @Test
    fun revisionTwoRequiresExactPreAdaptorLengthOutput() {
        val root = Files.createTempDirectory("funasr-ctc-length-output").toFile()
        writeValidBundle(
            root = root,
            metadataOverrides = mapOf("encoderPreAdaptorLengthOutputIndex" to "3"),
        )

        val error =
            assertThrows(IllegalArgumentException::class.java) {
                validateFunAsrCtcBundle(descriptor(revision = 2), root)
            }

        assertTrue(error.message.orEmpty().contains("output[2]"))
    }

    @Test
    fun revisionTwoRefusesFullVocabularyScoreContract() {
        val root = Files.createTempDirectory("funasr-ctc-score-domain").toFile()
        writeValidBundle(
            root = root,
            metadataOverrides = mapOf("scoreDomain" to "log_softmax_full_vocab"),
        )

        val error =
            assertThrows(IllegalArgumentException::class.java) {
                validateFunAsrCtcBundle(descriptor(revision = 2), root)
            }

        assertTrue(error.message.orEmpty().contains("raw_logits"))
    }

    private fun writeValidBundle(
        root: java.io.File,
        metadataOverrides: Map<String, String> = emptyMap(),
    ) {
        root.mkdirs()
        FunAsrCtcBundleLayout.REQUIRED_PATHS
            .filterNot { it == FunAsrCtcBundleLayout.METADATA }
            .forEach { relativePath ->
                java.io.File(root, relativePath).apply {
                    parentFile?.mkdirs()
                    writeBytes(byteArrayOf(1))
                }
            }

        val metadata =
            linkedMapOf(
                "schemaVersion" to "1",
                "scoreDomain" to "raw_logits",
                "encoderPostAdaptorOutputIndex" to "0",
                "encoderPostAdaptorOutputName" to "encoder_out",
                "encoderPreAdaptorOutputIndex" to "1",
                "encoderPreAdaptorOutputName" to "ctc_encoder_out",
                "encoderPreAdaptorLengthOutputIndex" to "2",
                "encoderPreAdaptorLengthOutputName" to "ctc_encoder_out_lens",
                "encoderPreAdaptorDim" to "512",
                "ctcEncoderInputName" to "ctc_encoder_out",
                "ctcEncoderLengthInputName" to "ctc_encoder_out_lens",
                "ctcCandidateIdsInputName" to "candidate_ids",
                "ctcCandidateScoresOutputName" to "candidate_logits",
                "ctcVocabSize" to "60515",
                "blankId" to "60514",
                "framePeriodUs" to "60000",
                "tokenizerFormat" to "sensevoice_tiktoken",
                "tokenizerFile" to "multilingual.tiktoken",
            )
        metadata.putAll(metadataOverrides)
        java.io.File(root, FunAsrCtcBundleLayout.METADATA).writeText(
            metadata.entries.joinToString(separator = "\n", postfix = "\n") { (key, value) ->
                "$key=$value"
            },
        )
    }

    private fun descriptor(
        revision: Long,
        declaredPaths: Set<String> = FunAsrCtcBundleLayout.REQUIRED_PATHS,
    ): ModelDescriptor =
        ModelDescriptor(
            modelId = "funasr-nano-int8",
            kind = ModelKind.ASR_LARGE,
            displayName = "FunASR Nano INT8",
            version = "2025-12-30",
            revision = revision,
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
            installedSizeBytes = declaredPaths.size.toLong(),
            packageSha256 = null,
            files =
                declaredPaths.sorted().map { path ->
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

    private companion object {
        const val DUMMY_SHA256 =
            "0000000000000000000000000000000000000000000000000000000000000000"
    }
}
