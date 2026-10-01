package io.github.ioannes78.voica.model

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicModelStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun verifiedVersionBecomesActiveAndPreviousIsRetainedForRollback() {
        val store = AtomicModelStore(temporaryFolder.root.toPath())
        val first = descriptor(version = "v1", revision = 1, bytes = "one".toByteArray())
        stage(store, first, "one".toByteArray())
        store.commitVerifiedVersion(first, store.stagingDirectory(first.modelId, first.version))

        assertEquals("v1", store.state(first.modelId).activeVersion)
        assertEquals(null, store.state(first.modelId).previousVersion)

        val second = descriptor(version = "v2", revision = 2, bytes = "two".toByteArray())
        stage(store, second, "two".toByteArray())
        store.commitVerifiedVersion(second, store.stagingDirectory(second.modelId, second.version))

        assertEquals("v2", store.state(second.modelId).activeVersion)
        assertEquals("v1", store.state(second.modelId).previousVersion)
        assertTrue(store.rollback(second.modelId))
        assertEquals("v1", store.state(second.modelId).activeVersion)
        assertEquals("v2", store.state(second.modelId).previousVersion)
    }

    @Test
    fun corruptCandidateNeverChangesActiveVersion() {
        val store = AtomicModelStore(temporaryFolder.root.toPath())
        val first = descriptor(version = "v1", revision = 1, bytes = "good".toByteArray())
        stage(store, first, "good".toByteArray())
        store.commitVerifiedVersion(first, store.stagingDirectory(first.modelId, first.version))

        val second = descriptor(version = "v2", revision = 2, bytes = "expected".toByteArray())
        stage(store, second, "corrupt".toByteArray())

        try {
            store.commitVerifiedVersion(
                second,
                store.stagingDirectory(second.modelId, second.version),
            )
            throw AssertionError("corrupt candidate must not commit")
        } catch (_: ModelIntegrityException) {
            // expected
        }

        assertEquals("v1", store.state(first.modelId).activeVersion)
        assertTrue(Files.exists(store.stagingDirectory(second.modelId, second.version)))
        assertFalse(Files.exists(store.versionDirectory(second.modelId, second.version)))
    }

    @Test
    fun activeVersionCannotBeDeleted() {
        val store = AtomicModelStore(temporaryFolder.root.toPath())
        val first = descriptor(version = "v1", revision = 1, bytes = "one".toByteArray())
        stage(store, first, "one".toByteArray())
        store.commitVerifiedVersion(first, store.stagingDirectory(first.modelId, first.version))

        try {
            store.removeInactiveVersion(first.modelId, first.version)
            throw AssertionError("active model must be protected")
        } catch (_: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun integrityReportsSizeMismatchBeforeHash() {
        val store = AtomicModelStore(temporaryFolder.root.toPath())
        val descriptor = descriptor(version = "v1", revision = 1, bytes = "expected".toByteArray())
        val staging = stage(store, descriptor, "different".toByteArray())

        val report = ModelIntegrityVerifier.verifyDirectory(staging, descriptor)

        assertFalse(report.valid)
        assertEquals(ModelIntegrityIssueKind.SIZE_MISMATCH, report.issues.single().kind)
    }

    private fun stage(
        store: AtomicModelStore,
        descriptor: ModelDescriptor,
        bytes: ByteArray,
    ): Path {
        val root = store.stagingDirectory(descriptor.modelId, descriptor.version)
        Files.createDirectories(root)
        Files.write(root.resolve("model.bin"), bytes)
        return root
    }

    private fun descriptor(
        version: String,
        revision: Long,
        bytes: ByteArray,
    ): ModelDescriptor =
        ModelDescriptor(
            modelId = "test-model",
            kind = ModelKind.ASR_STREAMING,
            displayName = "Test",
            version = version,
            revision = revision,
            runtimeId = "sherpa-onnx",
            runtimeVersionMin = "1.13.8",
            runtimeVersionMax = null,
            languages = setOf("zh"),
            capabilities = ModelCapabilities(supportsStreaming = true),
            sourceType = ModelSourceType.MANAGED_DOWNLOAD,
            builtinAssetPath = null,
            packageFormat = ModelPackageFormat.ZIP,
            downloadUrl = "https://example.invalid/$version.zip",
            downloadSizeBytes = bytes.size.toLong(),
            installedSizeBytes = bytes.size.toLong(),
            packageSha256 = "0".repeat(64),
            files =
                listOf(
                    ModelFileDescriptor(
                        relativePath = "model.bin",
                        sizeBytes = bytes.size.toLong(),
                        sha256 = digest(bytes),
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
            releaseChannel = "test",
            autoUpdateEligible = false,
        )

    private fun digest(bytes: ByteArray): String {
        val file = temporaryFolder.newFile().toPath()
        Files.write(file, bytes)
        return sha256(file)
    }
}
