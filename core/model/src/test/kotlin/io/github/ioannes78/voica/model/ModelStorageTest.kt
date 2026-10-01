package io.github.ioannes78.voica.model

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelStorageTest {
    private val root = Files.createTempDirectory("voica-model-storage").toFile()

    @After
    fun cleanup() {
        root.deleteRecursively()
    }

    @Test
    fun verifiedCandidateDoesNotBecomeActiveUntilConfirmed() {
        val storage = ModelStorage(root)
        val descriptor = descriptor(version = "v1", revision = 1)
        val staging = prepareStaging(storage, descriptor, "one")

        val installed = storage.promoteVerifiedStaging(descriptor, staging)

        assertTrue(installed.directory.isDirectory)
        assertFalse(installed.confirmedGood)
        assertNull(storage.activeDirectory(descriptor.modelId))

        storage.confirmGood(descriptor)

        assertNotNull(storage.activeDirectory(descriptor.modelId))
        assertEquals("v1", storage.activationState(descriptor.modelId).activeVersion)
    }

    @Test
    fun newConfirmedVersionKeepsPreviousAndRollbackSwapsThem() {
        val storage = ModelStorage(root)
        val first = descriptor(version = "v1", revision = 1)
        storage.promoteVerifiedStaging(first, prepareStaging(storage, first, "one"))
        storage.confirmGood(first)

        val second = descriptor(version = "v2", revision = 2)
        storage.promoteVerifiedStaging(second, prepareStaging(storage, second, "two"))
        storage.confirmGood(second)

        val upgraded = storage.activationState(first.modelId)
        assertEquals("v2", upgraded.activeVersion)
        assertEquals(2L, upgraded.activeRevision)
        assertEquals("v1", upgraded.previousVersion)
        assertEquals(1L, upgraded.previousRevision)

        val rolledBack = storage.rollback(first.modelId)
        assertEquals("v1", rolledBack.activeVersion)
        assertEquals("v2", rolledBack.previousVersion)
    }

    @Test
    fun clearActivationLeavesInstalledCandidateButNoActiveOverride() {
        val storage = ModelStorage(root)
        val descriptor = descriptor(version = "v1", revision = 1)
        storage.promoteVerifiedStaging(
            descriptor,
            prepareStaging(storage, descriptor, "one"),
        )
        storage.confirmGood(descriptor)

        storage.clearActivation(descriptor.modelId)

        assertNull(storage.activeDirectory(descriptor.modelId))
        assertNotNull(storage.installedVersion(descriptor))
        assertNull(storage.activationState(descriptor.modelId).activeVersion)
    }

    @Test
    fun sizeOrShaMismatchBlocksPromotion() {
        val storage = ModelStorage(root)
        val descriptor = descriptor(version = "v1", revision = 1)
        val staging = storage.createStagingDirectory(descriptor)
        File(staging, "model.onnx").writeText("tampered")

        val result = storage.verifyDirectory(descriptor, staging)

        assertTrue(result is ModelVerificationResult.Invalid)
    }

    @Test
    fun activeAndRollbackVersionsCannotBeDeleted() {
        val storage = ModelStorage(root)
        val first = descriptor(version = "v1", revision = 1)
        storage.promoteVerifiedStaging(first, prepareStaging(storage, first, "one"))
        storage.confirmGood(first)

        val second = descriptor(version = "v2", revision = 2)
        storage.promoteVerifiedStaging(second, prepareStaging(storage, second, "two"))
        storage.confirmGood(second)

        try {
            storage.removeVersion(second.modelId, "v2", 2)
            throw AssertionError("active model removal must fail")
        } catch (_: IllegalStateException) {
            // expected
        }

        try {
            storage.removeVersion(first.modelId, "v1", 1)
            throw AssertionError("rollback model removal must fail")
        } catch (_: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun modelLeaseTracksUseAndIsIdempotentOnClose() {
        val registry = ModelUseRegistry()
        val lease = registry.acquire("asr", "v1", 1)

        assertTrue(registry.isInUse("asr", "v1", 1))
        lease.close()
        lease.close()
        assertFalse(registry.isInUse("asr", "v1", 1))
    }

    private fun prepareStaging(
        storage: ModelStorage,
        descriptor: ModelDescriptor,
        content: String,
    ): File {
        val staging = storage.createStagingDirectory(descriptor)
        File(staging, "model.onnx").writeText(content)
        return staging
    }

    private fun descriptor(
        version: String,
        revision: Long,
    ): ModelDescriptor {
        val content = if (version == "v1") "one" else "two"
        val fixture = File(root, "hash-fixture-$version").apply { writeText(content) }
        val hash = sha256Hex(fixture)
        val size = fixture.length()
        fixture.delete()

        return ModelDescriptor(
            modelId = "asr",
            kind = ModelKind.ASR_STREAMING,
            displayName = "ASR",
            version = version,
            revision = revision,
            runtimeId = "sherpa-onnx",
            runtimeVersionMin = "1.13.8",
            runtimeVersionMax = null,
            languages = setOf("zh", "en"),
            capabilities = ModelCapabilities(supportsStreaming = true),
            sourceType = ModelSourceType.MANAGED_DOWNLOAD,
            builtinAssetPath = null,
            packageFormat = ModelPackageFormat.ZIP,
            downloadUrl = "https://example.invalid/$version.zip",
            downloadSizeBytes = size,
            installedSizeBytes = size,
            packageSha256 = "a".repeat(64),
            files =
                listOf(
                    ModelFileDescriptor(
                        relativePath = "model.onnx",
                        sizeBytes = size,
                        sha256 = hash,
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
            attribution = "test",
            redistributionPolicy = RedistributionPolicy.VOICA_MIRROR_ALLOWED,
            releaseChannel = "production",
            autoUpdateEligible = false,
        )
    }
}
