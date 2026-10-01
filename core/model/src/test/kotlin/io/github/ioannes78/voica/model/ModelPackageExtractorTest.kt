package io.github.ioannes78.voica.model

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelPackageExtractorTest {
    private val root = Files.createTempDirectory("voica-model-package").toFile()

    @After
    fun cleanup() {
        root.deleteRecursively()
    }

    @Test
    fun zipExtractsOnlyWhitelistedPackagePaths() {
        val payload = "model".toByteArray()
        val packageFile = File(root, "model.zip")
        ZipOutputStream(packageFile.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("upstream/model.onnx"))
            zip.write(payload)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("upstream/test.wav"))
            zip.write(ByteArray(1024))
            zip.closeEntry()
        }

        val descriptor =
            descriptor(
                packageFormat = ModelPackageFormat.ZIP,
                packageFile = packageFile,
                installedBytes = payload,
                packagePath = "upstream/model.onnx",
            )
        val storage = ModelStorage(File(root, "managed"))
        val staging = storage.createStagingDirectory(descriptor)

        val extractor = ModelPackageExtractor()
        extractor.verifyPackage(descriptor, packageFile)
        extractor.extract(descriptor, packageFile, staging)

        assertEquals("model", File(staging, "model.onnx").readText())
        assertFalse(File(staging, "test.wav").exists())
        assertEquals(
            ModelVerificationResult.Valid,
            storage.verifyDirectory(descriptor, staging),
        )
    }

    @Test
    fun tarBz2MapsUpstreamRootToInstalledRelativePath() {
        val payload = "sense".toByteArray()
        val packageFile = File(root, "sense.tar.bz2")
        BZip2CompressorOutputStream(packageFile.outputStream()).use { bzip ->
            TarArchiveOutputStream(bzip).use { tar ->
                val entry = TarArchiveEntry("upstream/model.int8.onnx")
                entry.size = payload.size.toLong()
                tar.putArchiveEntry(entry)
                tar.write(payload)
                tar.closeArchiveEntry()
                tar.finish()
            }
        }

        val descriptor =
            descriptor(
                packageFormat = ModelPackageFormat.TAR_BZ2,
                packageFile = packageFile,
                installedBytes = payload,
                packagePath = "upstream/model.int8.onnx",
            )
        val storage = ModelStorage(File(root, "managed-tar"))
        val staging = storage.createStagingDirectory(descriptor)

        val extractor = ModelPackageExtractor()
        extractor.verifyPackage(descriptor, packageFile)
        extractor.extract(descriptor, packageFile, staging)

        assertEquals("sense", File(staging, "model.onnx").readText())
        assertEquals(
            ModelVerificationResult.Valid,
            storage.verifyDirectory(descriptor, staging),
        )
    }

    @Test
    fun singleFilePackageIsVerifiedBeforeExtraction() {
        val payload = "vad".toByteArray()
        val packageFile = File(root, "silero.onnx").apply { writeBytes(payload) }
        val descriptor =
            descriptor(
                packageFormat = ModelPackageFormat.SINGLE_FILE,
                packageFile = packageFile,
                installedBytes = payload,
                packagePath = "silero.onnx",
            )
        val storage = ModelStorage(File(root, "managed-single"))
        val staging = storage.createStagingDirectory(descriptor)

        ModelPackageExtractor().apply {
            verifyPackage(descriptor, packageFile)
            extract(descriptor, packageFile, staging)
        }

        assertTrue(File(staging, "model.onnx").isFile)
    }

    private fun descriptor(
        packageFormat: ModelPackageFormat,
        packageFile: File,
        installedBytes: ByteArray,
        packagePath: String,
    ) =
        ModelDescriptor(
            modelId = "fixture",
            kind = ModelKind.ASR_STREAMING,
            displayName = "Fixture",
            version = "v1",
            revision = 1,
            runtimeId = "sherpa-onnx",
            runtimeVersionMin = "1.13.8",
            runtimeVersionMax = null,
            languages = setOf("zh"),
            capabilities = ModelCapabilities(),
            sourceType = ModelSourceType.MANAGED_DOWNLOAD,
            builtinAssetPath = null,
            packageFormat = packageFormat,
            downloadUrl = "https://example.invalid/model",
            mirrors = emptyList(),
            downloadSizeBytes = packageFile.length(),
            installedSizeBytes = installedBytes.size.toLong(),
            packageSha256 = sha256Hex(packageFile),
            files =
                listOf(
                    ModelFileDescriptor(
                        relativePath = "model.onnx",
                        sizeBytes = installedBytes.size.toLong(),
                        sha256 = sha256(installedBytes),
                        packagePath = packagePath,
                    ),
                ),
            abis = setOf("arm64-v8a"),
            minSdk = 26,
            appVersionMin = 20,
            appVersionMax = null,
            licenseId = "Apache-2.0",
            licenseUrl = null,
            sourceUrl = "https://example.invalid/source",
            homepage = null,
            attribution = "fixture",
            redistributionPolicy = RedistributionPolicy.UPSTREAM_ONLY,
            releaseChannel = "production",
            autoUpdateEligible = false,
        )

    private fun sha256(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }
}
