package io.github.ioannes78.voica

import android.app.Application
import io.github.ioannes78.voica.model.ModelCatalog
import io.github.ioannes78.voica.model.ModelManager
import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ModelUpdateSettings(
    val automaticChecksEnabled: Boolean = true,
    val automaticSmallModelUpdatesEnabled: Boolean = true,
)

interface ModelUpdateSettingsStore {
    val settings: StateFlow<ModelUpdateSettings>

    fun setAutomaticChecksEnabled(enabled: Boolean)

    fun setAutomaticSmallModelUpdatesEnabled(enabled: Boolean)
}

class SharedPreferencesModelUpdateSettingsStore(
    application: Application,
) : ModelUpdateSettingsStore {
    private val preferences =
        application.getSharedPreferences(
            PREFERENCES_NAME,
            Application.MODE_PRIVATE,
        )
    private val mutableSettings =
        MutableStateFlow(
            ModelUpdateSettings(
                automaticChecksEnabled =
                    preferences.getBoolean(KEY_AUTO_CHECK, true),
                automaticSmallModelUpdatesEnabled =
                    preferences.getBoolean(KEY_AUTO_SMALL, true),
            ),
        )

    override val settings: StateFlow<ModelUpdateSettings> =
        mutableSettings.asStateFlow()

    override fun setAutomaticChecksEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_AUTO_CHECK, enabled).apply()
        mutableSettings.value =
            mutableSettings.value.copy(
                automaticChecksEnabled = enabled,
            )
    }

    override fun setAutomaticSmallModelUpdatesEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_AUTO_SMALL, enabled).apply()
        mutableSettings.value =
            mutableSettings.value.copy(
                automaticSmallModelUpdatesEnabled = enabled,
            )
    }

    private companion object {
        const val PREFERENCES_NAME = "voica-model-updates"
        const val KEY_AUTO_CHECK = "automatic-checks-enabled"
        const val KEY_AUTO_SMALL = "automatic-small-model-updates-enabled"
    }
}

data class ModelUpdateCheckState(
    val checking: Boolean = false,
    val lastSuccessfulCheckAtMs: Long? = null,
    val automaticallyUpdatedModelIds: List<String> = emptyList(),
    val errorMessage: String? = null,
)

class ModelUpdateController(
    private val modelManager: ModelManager,
    private val settingsStore: ModelUpdateSettingsStore,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val checkMutex = Mutex()
    private val mutableState = MutableStateFlow(ModelUpdateCheckState())

    val settings: StateFlow<ModelUpdateSettings> = settingsStore.settings
    val state: StateFlow<ModelUpdateCheckState> = mutableState.asStateFlow()

    fun setAutomaticChecksEnabled(enabled: Boolean) {
        settingsStore.setAutomaticChecksEnabled(enabled)
    }

    fun setAutomaticSmallModelUpdatesEnabled(enabled: Boolean) {
        settingsStore.setAutomaticSmallModelUpdatesEnabled(enabled)
    }

    suspend fun checkForUpdates(force: Boolean): ModelCatalog =
        checkMutex.withLock {
            mutableState.value =
                mutableState.value.copy(
                    checking = true,
                    errorMessage = null,
                    automaticallyUpdatedModelIds = emptyList(),
                )

            try {
                val catalog = modelManager.checkForUpdates(force)
                val autoUpdated =
                    if (settings.value.automaticSmallModelUpdatesEnabled) {
                        automaticallyUpdateSmallModels(catalog)
                    } else {
                        emptyList()
                    }
                mutableState.value =
                    ModelUpdateCheckState(
                        checking = false,
                        lastSuccessfulCheckAtMs = nowMs(),
                        automaticallyUpdatedModelIds = autoUpdated,
                        errorMessage = null,
                    )
                catalog
            } catch (cancelled: CancellationException) {
                mutableState.value =
                    mutableState.value.copy(checking = false)
                throw cancelled
            } catch (error: Exception) {
                mutableState.value =
                    mutableState.value.copy(
                        checking = false,
                        errorMessage =
                            error.message ?: error::class.java.simpleName,
                    )
                throw error
            }
        }

    private suspend fun automaticallyUpdateSmallModels(
        catalog: ModelCatalog,
    ): List<String> {
        val descriptor =
            catalog.model(Stage8ModelIds.VAD)
                ?: return emptyList()
        if (!descriptor.autoUpdateEligible ||
            descriptor.downloadUrl.isNullOrBlank()
        ) {
            return emptyList()
        }

        val availability =
            modelManager.availability(descriptor.modelId)
                ?: return emptyList()
        if (!availability.updateAvailable) {
            return emptyList()
        }

        val durable = modelManager as? DurableModelInstallController
        if (durable != null) {
            try {
                durable.installAndActivate(
                    modelId = descriptor.modelId,
                    version = descriptor.version,
                    revision = descriptor.revision,
                    origin = ModelInstallOrigin.AUTO_SMALL,
                )
            } catch (_: ModelInstallUserResumeRequiredException) {
                // A user-visible durable operation is waiting for explicit recovery.
                // Automatic checks must not silently restart or replace it.
                return emptyList()
            }
            return listOf(descriptor.modelId)
        }

        val candidateAlreadyInstalled =
            availability.installedVersion == descriptor.version &&
                availability.installedRevision == descriptor.revision

        if (!candidateAlreadyInstalled) {
            modelManager.install(
                modelId = descriptor.modelId,
                version = descriptor.version,
                revision = descriptor.revision,
            )
        }
        modelManager.confirmInstalledVersion(
            modelId = descriptor.modelId,
            version = descriptor.version,
            revision = descriptor.revision,
        )
        return listOf(descriptor.modelId)
    }
}
