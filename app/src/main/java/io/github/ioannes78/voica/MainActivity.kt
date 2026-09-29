package io.github.ioannes78.voica

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import io.github.ioannes78.voica.ble.DeviceRepository
import io.github.ioannes78.voica.ui.VoicaApp
import io.github.ioannes78.voica.ui.theme.VoicaTheme

class MainActivity : ComponentActivity() {
    private val deviceRepository: DeviceRepository
        get() = (application as VoicaApplication).container.deviceRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VoicaTheme {
                VoicaApp(deviceRepository)
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
