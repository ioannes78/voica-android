package io.github.ioannes78.voica.model

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultModelManagerTest {
    private val root = Files.createTempDirectory("voica-model-manager").toFile()

    @After
    fun cleanup() {
        root.deleteRecursively()
    }

    @Test
    fun installVerifyConfirmAndRemoteRevisionFlow() = runBlocking {
        val v1Bytes = "model-v1".toByteArray()
        val v2Bytes = "model-v2".toByteArray()
        val bundled = catalog(descriptor("v1", 1, v1Bytes))
        var remote = catalog(descriptor("v2", 2, v2Bytes))
        val downloader =
            FakeDownloader(
                mapOf("https://example.invalid/v2.bin" to v2Bytes),
            )
        val manager =
            manager(
                bundled = bundled,
                remoteProvider = ModelCatalogProvider { remote },
                downloader = downloader,
            )

        val baseline = manager.availability("asr")!!
        assertEquals(ModelState.NOT_INSTALLED, baseline.state)
        assertFalse(baseline.updateAvailable)

        manager.checkForUpdates(force = true)
        val update = manager.availability("asr")!!
        assertEquals(2L, update.availableRevision)
        assertEquals(ModelState.NOT_INSTALLED, update.state)

        manager.install("asr", "v2", 2)
        val installed = manager.availability("asr")!!
        assertEquals(ModelState.INSTALLED, installed.state)
        assertEquals("v2", installed.installedVersion)
        assertNull(installed.activeVersion)

        manager.confirmInstalledVersion("asr", "v2", 2)
        val active = manager.availability("asr")!!
        assertEquals("v2", active.activeVersion)
        assertEquals(2L, active.activeRevision)
        assertFalse(active.updateAvailable)

        remote = catalog(descriptor("v3", 3, "model-v3".toByteArray()))
        manager.checkForUpdates(force = true)
        assertTrue(manager.availability("asr")!!.updateAvailable)
    }

    @Test
    fun builtinWithOverrideRemainsActiveUntilDownloadedCandidateIsConfirmed() = runBlocking {
        val baseline =
            descriptor(
                version = "baseline",
                revision = 1,
                bytes = "builtin".toByteArray(),
                sourceType = ModelSourceType.BUILTIN_WITH_OVERRIDE,
                builtinAssetPath = "models/silero.onnx",
                downloadable = false,
                modelId = "silero",
                kind = ModelKind.VAD,
            )
        val updateBytes = "silero-v2".toByteArray()
        val remote =
            baseline.copy(
                version = "v2",
                revision = 2,
                packageFormat = ModelPackageFormat.SINGLE_FILE,
                downloadUrl = "https://example.invalid/silero-v2.bin",
                downloadSizeBytes = updateBytes.size.toLong(),
                installedSizeBytes = updateBytes.size.toLong(),
                packageSha256 = sha256(updateBytes),
                files =
                    listOf(
                        ModelFileDescriptor(
                            relativePath = "model.bin",
                            sizeBytes = updateBytes.size.toLong(),
                            sha256 = sha256(updateBytes),
                        ),
                    ),
            )
        val manager =
            manager(
                bundled = catalog(baseline),
                remoteProvider = ModelCatalogProvider { catalog(remote) },
                downloader =
                    FakeDownloader(
                        mapOf(
                            "https://example.invalid/silero-v2.bin" to updateBytes,
                        ),
                    ),
            )

        manager.checkForUpdates(true)
        val before = manager.availability("silero")!!
        assertEquals("baseline", before.activeVersion)
        assertEquals(1L, before.activeRevision)
        assertTrue(before.updateAvailable)

        manager.install("silero", "v2", 2)
        val candidate = manager.availability("silero")!!
        assertEquals("baseline", candidate.activeVersion)

        manager.confirmInstalledVersion("silero", "v2", 2)
        val after = manager.availability("silero")!!
        assertEquals("v2", after.activeVersion)
        assertEquals(2L, after.activeRevision)
    }

    @Test
    fun cancellationDeletesPartialPackageAndClearsTransientOperation() = runBlocking {
        val descriptor = descriptor("v1", 1, "model".toByteArray())
        val downloader = BlockingDownloader()
        val manager =
            manager(
                bundled = catalog(descriptor),
                remoteProvider = null,
                downloader = downloader,
            )

        val job = async {
            manager.install("asr", "v1", 1)
        }
        downloader.started.await()
        assertEquals(
            ModelState.DOWNLOADING,
            manager.operations.value["asr"]?.state,
        )

        manager.cancelInstall("asr")
        try {
            job.await()
            throw AssertionError("cancelled install must not complete")
        } catch (_: CancellationException) {
            // expected
        }

        assertFalse(manager.operations.value.containsKey("asr"))
        assertTrue(File(root, "packages").listFiles().isNullOrEmpty())
    }

    @Test
    fun failedCandidateValidationDoesNotChangeActiveVersion() = runBlocking {
        val baselineBytes = "baseline".toByteArray()
        val updateBytes = "update".toByteArray()
        val baseline =
            descriptor(
                version = "baseline",
                revision = 1,
                bytes = baselineBytes,
                sourceType = ModelSourceType.BUILTIN_WITH_OVERRIDE,
                builtinAssetPath = "models/baseline.bin",
                downloadable = false,
                modelId = "silero",
                kind = ModelKind.VAD,
            )
        val remote =
            baseline.copy(
                version = "v2",
                revision = 2,
                packageFormat = ModelPackageFormat.SINGLE_FILE,
                downloadUrl = "https://example.invalid/v2.bin",
                downloadSizeBytes = updateBytes.size.toLong(),
                installedSizeBytes = updateBytes.size.toLong(),
                packageSha256 = sha256(updateBytes),
                files =
                    listOf(
                        ModelFileDescriptor(
                            relativePath = "model.bin",
                            sizeBytes = updateBytes.size.toLong(),
                            sha256 = sha256(updateBytes),
                        ),
                    ),
            )
        val manager =
            manager(
                bundled = catalog(baseline),
                remoteProvider = ModelCatalogProvider { catalog(remote) },
                downloader =
                    FakeDownloader(
                        mapOf("https://example.invalid/v2.bin" to updateBytes),
                    ),
                validator =
                    ModelCandidateValidator { _, _ ->
                        error("native smoke failed")
                    },
            )

        manager.checkForUpdates(true)
        manager.install("silero", "v2", 2)

        try {
            manager.confirmInstalledVersion("silero", "v2", 2)
            throw AssertionError("failed smoke validation must block activation")
        } catch (_: IllegalStateException) {
            // expected
        }

        val availability = manager.availability("silero")!!
        assertEquals("baseline", availability.activeVersion)
        assertEquals(1L, availability.activeRevision)
        assertEquals(ModelState.LOAD_FAILED, availability.state)
    }

    @Test
    fun remoteCatalogCannotRegressBundledModelRevision() = runBlocking {
        val bundled = catalog(descriptor("v2", 2, "two".toByteArray()))
        val remote = catalog(descriptor("v1", 1, "one".toByteArray()))
        val manager =
            manager(
                bundled = bundled,
                remoteProvider = ModelCatalogProvider { remote },
                downloader = FakeDownloader(emptyMap()),
            )

        try {
            manager.checkForUpdates(true)
            throw AssertionError("revision regression must be rejected")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }

    private fun manager(
        bundled: ModelCatalog,
        remoteProvider: ModelCatalogProvider?,
        downloader: ModelPackageDownloader,
        validator: ModelCandidateValidator = ModelCandidateValidator { _, _ -> },
    ) =
        DefaultModelManager(
            bundledCatalog = bundled,
            remoteCatalogProvider = remoteProvider,
            environment =
                ModelEnvironment(
                    runtimeId = "sherpa-onnx",
                    runtimeVersion = "1.13.8",
                    abi = "arm64-v8a",
                    sdkInt = 26,
                    appVersionCode = 20,
                ),
            storage = ModelStorage(File(root, "models")),
            packageDirectory = File(root, "packages"),
            downloader = downloader,
            candidateValidator = validator,
        )

    private fun catalog(descriptor: ModelDescriptor) =
        ModelCatalog(
            catalogVersion = 1,
            manifestVersion = descriptor.revision.toInt(),
            channel = "production",
            publishedAt = null,
            manifestDigest =
                descriptor.revision.toString().padStart(64, 'a').takeLast(64),
            models = listOf(descriptor),
        )

    private fun descriptor(
        version: String,
        revision: Long,
        bytes: ByteArray,
        sourceType: ModelSourceType = ModelSourceType.MANAGED_DOWNLOAD,
        builtinAssetPath: String? = null,
        downloadable: Boolean = true,
        modelId: String = "asr",
        kind: ModelKind = ModelKind.ASR_STREAMING,
    ): ModelDescriptor =
        ModelDescriptor(
            modelId = modelId,
            kind = kind,
            displayName = modelId,
            version = version,
            revision = revision,
            runtimeId = "sherpa-onnx",
            runtimeVersionMin = "1.13.8",
            runtimeVersionMax = null,
            languages = setOf("zh", "en"),
            capabilities =
                ModelCapabilities(
                    supportsStreaming = kind == ModelKind.ASR_STREAMING,
                ),
            sourceType = sourceType,
            builtinAssetPath = builtinAssetPath,
            packageFormat =
                if (downloadable) ModelPackageFormat.SINGLE_FILE else null,
            downloadUrl =
                if (downloadable) {
                    "https://example.invalid/" + version + ".bin"
                } else {
                    null
                },
            mirrors = emptyList(),
            downloadSizeBytes =
                if (downloadable) bytes.size.toLong() else null,
            installedSizeBytes = bytes.size.toLong(),
            packageSha256 =
                if (downloadable) sha256(bytes) else null,
            files =
                listOf(
                    ModelFileDescriptor(
                        relativePath = "model.bin",
                        sizeBytes = bytes.size.toLong(),
                        sha256 = sha256(bytes),
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
            redistributionPolicy = RedistributionPolicy.VOICA_MIRROR_ALLOWED,
            releaseChannel = "production",
            autoUpdateEligible = true,
        )

    private class FakeDownloader(
        private val bytesByUrl: Map<String, ByteArray>,
    ) : ModelPackageDownloader {
        override suspend fun download(
            url: String,
            destinationPart: File,
            expectedBytes: Long?,
            progressListener: ModelDownloadProgressListener?,
        ) {
            val bytes = bytesByUrl[url] ?: error("missing fixture for " + url)
            destinationPart.parentFile?.mkdirs()
            destinationPart.writeBytes(bytes)
            progressListener?.onProgress(
                ModelDownloadProgress(
                    downloadedBytes = bytes.size.toLong(),
                    totalBytes = expectedBytes,
                ),
            )
        }
    }

    private class BlockingDownloader : ModelPackageDownloader {
        val started = CompletableDeferred<Unit>()

        override suspend fun download(
            url: String,
            destinationPart: File,
            expectedBytes: Long?,
            progressListener: ModelDownloadProgressListener?,
        ) {
            destinationPart.parentFile?.mkdirs()
            destinationPart.writeText("partial")
            started.complete(Unit)
            CompletableDeferred<Unit>().await()
        }
    }

    companion object {
        private fun sha256(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") {
                (it.toInt() and 0xFF).toString(16).padStart(2, '0')
            }
        }
    }
}
