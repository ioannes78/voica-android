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
import io.github.ioannes78.voica.model.ModelState
import io.github.ioannes78.voica.model.RedistributionPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelUpdateControllerTest {
    @Test
    fun automaticCheckUpdatesOnlySileroAndLeavesLargeModelsManual() = runBlocking {
        val silero = descriptor(
            modelId = Stage8ModelIds.VAD,
            kind = ModelKind.VAD,
            revision = 2,
            sourceType = ModelSourceType.BUILTIN_WITH_OVERRIDE,
            builtinAssetPath = "models/silero.onnx",
            autoUpdateEligible = true,
        )
        val asr = descriptor(
            modelId = Stage8ModelIds.FIRST_PASS_ASR,
            kind = ModelKind.ASR_STREAMING,
            revision = 1,
            sourceType = ModelSourceType.MANAGED_DOWNLOAD,
            builtinAssetPath = null,
            autoUpdateEligible = false,
        )
        val manager = FakeModelManager(listOf(silero, asr))
        val store = InMemorySettingsStore()
        val controller =
            ModelUpdateController(
                modelManager = manager,
                settingsStore = store,
                nowMs = { 1234L },
            )

        controller.checkForUpdates(force = false)

        assertFalse(manager.lastForce)
        assertEquals(listOf(Stage8ModelIds.VAD), manager.installed)
        assertEquals(listOf(Stage8ModelIds.VAD), manager.confirmed)
        assertFalse(manager.installed.contains(Stage8ModelIds.FIRST_PASS_ASR))
        assertEquals(
            listOf(Stage8ModelIds.VAD),
            controller.state.value.automaticallyUpdatedModelIds,
        )
        assertEquals(1234L, controller.state.value.lastSuccessfulCheckAtMs)
    }

    @Test
    fun disablingAutomaticSmallUpdatesStillChecksButDoesNotInstall() = runBlocking {
        val silero = descriptor(
            modelId = Stage8ModelIds.VAD,
            kind = ModelKind.VAD,
            revision = 2,
            sourceType = ModelSourceType.BUILTIN_WITH_OVERRIDE,
            builtinAssetPath = "models/silero.onnx",
            autoUpdateEligible = true,
        )
        val manager = FakeModelManager(listOf(silero))
        val store =
            InMemorySettingsStore(
                ModelUpdateSettings(
                    automaticChecksEnabled = true,
                    automaticSmallModelUpdatesEnabled = false,
                ),
            )
        val controller =
            ModelUpdateController(
                modelManager = manager,
                settingsStore = store,
            )

        controller.checkForUpdates(force = true)

        assertTrue(manager.lastForce)
        assertTrue(manager.installed.isEmpty())
        assertTrue(manager.confirmed.isEmpty())
    }

    private class InMemorySettingsStore(
        initial: ModelUpdateSettings = ModelUpdateSettings(),
    ) : ModelUpdateSettingsStore {
        private val mutable = MutableStateFlow(initial)
        override val settings: StateFlow<ModelUpdateSettings> = mutable

        override fun setAutomaticChecksEnabled(enabled: Boolean) {
            mutable.value = mutable.value.copy(automaticChecksEnabled = enabled)
        }

        override fun setAutomaticSmallModelUpdatesEnabled(enabled: Boolean) {
            mutable.value =
                mutable.value.copy(
                    automaticSmallModelUpdatesEnabled = enabled,
                )
        }
    }

    private class FakeModelManager(
        models: List<ModelDescriptor>,
    ) : ModelManager {
        private val catalog =
            ModelCatalog(
                catalogVersion = 1,
                manifestVersion = 2,
                channel = "production",
                publishedAt = null,
                manifestDigest = "d".repeat(64),
                models = models,
            )
        private val activeRevision =
            mutableMapOf(
                Stage8ModelIds.VAD to 1L,
            )
        private val installedRevision =
            mutableMapOf(
                Stage8ModelIds.VAD to 1L,
            )

        override val operations: StateFlow<Map<String, ModelOperationStatus>> =
            MutableStateFlow(emptyMap())

        val installed = mutableListOf<String>()
        val confirmed = mutableListOf<String>()
        var lastForce = false

        override suspend fun catalog(): ModelCatalog = catalog

        override suspend fun availability(modelId: String): ModelAvailability? {
            val descriptor = catalog.model(modelId) ?: return null
            val active = activeRevision[modelId]
            val installed = installedRevision[modelId]
            return ModelAvailability(
                descriptor = descriptor,
                state =
                    if (installed == null) {
                        ModelState.NOT_INSTALLED
                    } else {
                        ModelState.INSTALLED
                    },
                builtinVersion =
                    if (modelId == Stage8ModelIds.VAD) "baseline" else null,
                builtinRevision =
                    if (modelId == Stage8ModelIds.VAD) 1L else null,
                installedVersion =
                    installed?.let {
                        if (it == descriptor.revision) descriptor.version else "baseline"
                    },
                installedRevision = installed,
                availableVersion = descriptor.version,
                availableRevision = descriptor.revision,
                activeVersion =
                    active?.let {
                        if (it == descriptor.revision) descriptor.version else "baseline"
                    },
                activeRevision = active,
                updateAvailable =
                    active != null && descriptor.revision > active,
            )
        }

        override suspend fun activeModel(modelId: String): ActiveModel? = null

        override suspend fun checkForUpdates(force: Boolean): ModelCatalog {
            lastForce = force
            return catalog
        }

        override suspend fun install(
            modelId: String,
            version: String,
            revision: Long,
        ) {
            installed += modelId
            installedRevision[modelId] = revision
        }

        override suspend fun cancelInstall(modelId: String) = Unit

        override suspend fun confirmInstalledVersion(
            modelId: String,
            version: String,
            revision: Long,
        ) {
            confirmed += modelId
            activeRevision[modelId] = revision
        }

        override suspend fun removeDownloadedVersion(
            modelId: String,
            version: String,
            revision: Long,
        ) = Unit

        override suspend fun rollback(modelId: String) = Unit
    }

    companion object {
        private fun descriptor(
            modelId: String,
            kind: ModelKind,
            revision: Long,
            sourceType: ModelSourceType,
            builtinAssetPath: String?,
            autoUpdateEligible: Boolean,
        ) =
            ModelDescriptor(
                modelId = modelId,
                kind = kind,
                displayName = modelId,
                version = "v" + revision,
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
                packageFormat = ModelPackageFormat.SINGLE_FILE,
                downloadUrl = "https://example.invalid/" + modelId + ".bin",
                mirrors = emptyList(),
                downloadSizeBytes = 1L,
                installedSizeBytes = 1L,
                packageSha256 = "a".repeat(64),
                files =
                    listOf(
                        ModelFileDescriptor(
                            relativePath = "model.bin",
                            sizeBytes = 1L,
                            sha256 = "b".repeat(64),
                        ),
                    ),
                abis = setOf("arm64-v8a"),
                minSdk = 26,
                appVersionMin = 20,
                appVersionMax = null,
                licenseId = "test",
                licenseUrl = null,
                sourceUrl = "https://example.invalid/source",
                homepage = null,
                attribution = "test",
                redistributionPolicy = RedistributionPolicy.UPSTREAM_ONLY,
                releaseChannel = "production",
                autoUpdateEligible = autoUpdateEligible,
            )
    }
}
