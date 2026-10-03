package io.github.ioannes78.voica

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.ioannes78.voica.ble.DeviceRepository
import io.github.ioannes78.voica.database.RecordingLibraryRepository
import io.github.ioannes78.voica.ui.VoicaApp
import io.github.ioannes78.voica.ui.theme.VoicaTheme

class MainActivity : ComponentActivity() {
    private val appContainer: AppContainer
        get() = (application as VoicaApplication).container

    private val deviceRepository: DeviceRepository
        get() = appContainer.deviceRepository

    private val recordingLibraryRepository: RecordingLibraryRepository
        get() = appContainer.recordingLibraryRepository

    private val canonicalAudioCoordinator: CanonicalAudioCoordinator
        get() = appContainer.canonicalAudioCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val themeSettings by appContainer.themeSettingsStore.settings.collectAsState()
            VoicaTheme(settings = themeSettings) {
                VoicaApp(
                    repository = deviceRepository,
                    recordingLibraryRepository = recordingLibraryRepository,
                    canonicalAudioCoordinator = canonicalAudioCoordinator,
                    localAudioImportCoordinator = appContainer.localAudioImportCoordinator,
                    localRecordingDeleteCoordinator =
                        appContainer.localRecordingDeleteCoordinator,
                    playbackController = appContainer.playbackController,
                    modelManager = appContainer.modelManager,
                    modelUpdateController = appContainer.modelUpdateController,
                    transcriptionCoordinator = appContainer.transcriptionCoordinator,
                    transcriptionRepository = appContainer.transcriptionRepository,
                    diarizationCoordinator = appContainer.diarizationCoordinator,
                    diarizationRepository = appContainer.diarizationRepository,
                    aiSummaryCoordinator = appContainer.aiSummaryCoordinator,
                    aiSummaryRepository = appContainer.aiSummaryRepository,
                    providerProfileStore = appContainer.providerProfileStore,
                    providerConfigurationRepository =
                        appContainer.providerConfigurationRepository,
                    providerAdapterRegistry = appContainer.providerAdapterRegistry,
                    themeSettingsStore = appContainer.themeSettingsStore,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        deviceRepository.setForeground(true)
    }

    override fun onStop() {
        deviceRepository.setForeground(false)
        super.onStop()
    }
}
