package io.github.ioannes78.voica

import io.github.ioannes78.voica.model.ActiveModel
import io.github.ioannes78.voica.model.ModelAvailability
import io.github.ioannes78.voica.model.ModelCapabilities
import io.github.ioannes78.voica.model.ModelCatalog
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.model.ModelFileDescriptor
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.model.ModelManager
import io.github.ioannes78.voica.model.ModelOperationStatus
import io.github.ioannes78.voica.model.ModelPackageFormat
import io.github.ioannes78.voica.model.ModelSourceType
import io.github.ioannes78.voica.model.RedistributionPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class Stage16BLocalFileVadTest {
    @Test
    fun localFileVadPrefersFsmnWhenBothAreActive() = runBlocking {
        val fsmn = active(Stage16BLocalFileVadPolicy.PRIMARY_MODEL_ID)
        val ten = active(Stage16BLocalFileVadPolicy.FALLBACK_MODEL_ID)
        val manager =
            Stage16BLocalFileVadModelManager(
                FakeModelManager(
                    mapOf(
                        fsmn.descriptor.modelId to fsmn,
                        ten.descriptor.modelId to ten,
                    ),
                ),
            )

        val resolved = manager.activeModel(Stage8ModelIds.VAD)

        assertEquals(Stage16BLocalFileVadPolicy.PRIMARY_MODEL_ID, resolved?.descriptor?.modelId)
    }

    @Test
    fun localFileVadFallsBackToTenWhenFsmnIsUnavailable() = runBlocking {
        val ten = active(Stage16BLocalFileVadPolicy.FALLBACK_MODEL_ID)
        val manager =
            Stage16BLocalFileVadModelManager(
                FakeModelManager(mapOf(ten.descriptor.modelId to ten)),
            )

        val resolved = manager.activeModel(Stage8ModelIds.VAD)

        assertEquals(Stage16BLocalFileVadPolicy.FALLBACK_MODEL_ID, resolved?.descriptor?.modelId)
    }

    @Test
    fun localFileVadNeverFallsBackToSilero() = runBlocking {
        val silero = active(Stage8ModelIds.VAD)
        val manager =
            Stage16BLocalFileVadModelManager(
                FakeModelManager(mapOf(silero.descriptor.modelId to silero)),
            )

        try {
            manager.activeModel(Stage8ModelIds.VAD)
            fail("expected missing Local File VAD models")
        } catch (missing: MissingTranscriptionModelsException) {
            assertEquals(
                Stage16BLocalFileVadPolicy.orderedModelIds,
                missing.modelIds,
            )
            assertFalse(missing.modelIds.contains(Stage8ModelIds.VAD))
        }
    }

    private class FakeModelManager(
        private val active: Map<String, ActiveModel>,
    ) : ModelManager {
        override val operations: StateFlow<Map<String, ModelOperationStatus>> =
            MutableStateFlow(emptyMap())

        override suspend fun catalog(): ModelCatalog =
            ModelCatalog(
                catalogVersion = 1,
                manifestVersion = 1,
                channel = "test",
                publishedAt = null,
                manifestDigest = DIGEST,
                models = active.values.map { it.descriptor },
            )

        override suspend fun availability(modelId: String): ModelAvailability? = null

        override suspend fun activeModel(modelId: String): ActiveModel? = active[modelId]

        override suspend fun checkForUpdates(force: Boolean): ModelCatalog = catalog()

        override suspend fun install(modelId: String, version: String, revision: Long) = error("not used")

        override suspend fun cancelInstall(modelId: String) = Unit

        override suspend fun confirmInstalledVersion(modelId: String, version: String, revision: Long) =
            error("not used")

        override suspend fun removeDownloadedVersion(modelId: String, version: String, revision: Long) =
            error("not used")

        override suspend fun rollback(modelId: String) = error("not used")
    }

    companion object {
        private val DIGEST = "d".repeat(64)

        private fun active(modelId: String): ActiveModel =
            ActiveModel(
                descriptor = descriptor(modelId),
                manifestDigest = DIGEST,
                installedDirectory = null,
            )

        private fun descriptor(modelId: String): ModelDescriptor =
            ModelDescriptor(
                modelId = modelId,
                kind = ModelKind.VAD,
                displayName = modelId,
                version = "v1",
                revision = 1,
                runtimeId = "test-runtime",
                runtimeVersionMin = null,
                runtimeVersionMax = null,
                languages = setOf("und"),
                capabilities = ModelCapabilities(),
                sourceType = ModelSourceType.BUILTIN,
                builtinAssetPath = "models/fake.onnx",
                packageFormat = ModelPackageFormat.SINGLE_FILE,
                downloadUrl = null,
                downloadSizeBytes = null,
                installedSizeBytes = 1,
                packageSha256 = null,
                files =
                    listOf(
                        ModelFileDescriptor(
                            relativePath = "fake.onnx",
                            sizeBytes = 1,
                            sha256 = "a".repeat(64),
                        ),
                    ),
                abis = setOf("arm64-v8a"),
                minSdk = 26,
                appVersionMin = null,
                appVersionMax = null,
                licenseId = "Apache-2.0",
                licenseUrl = null,
                sourceUrl = "https://example.invalid/model",
                homepage = null,
                attribution = "test",
                redistributionPolicy = RedistributionPolicy.UPSTREAM_ONLY,
                releaseChannel = "test",
                autoUpdateEligible = false,
            )
    }
}
