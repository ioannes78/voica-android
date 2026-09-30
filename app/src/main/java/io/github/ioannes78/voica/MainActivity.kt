package io.github.ioannes78.voica

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VoicaTheme {
                VoicaApp(
                    repository = deviceRepository,
                    recordingLibraryRepository = recordingLibraryRepository,
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
