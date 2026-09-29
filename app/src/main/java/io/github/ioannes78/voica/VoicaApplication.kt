package io.github.ioannes78.voica

import android.app.Application
import io.github.ioannes78.voica.ble.DefaultDeviceRepository
import io.github.ioannes78.voica.ble.DeviceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class VoicaApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(
    application: Application,
) {
    private val applicationScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val deviceRepository: DeviceRepository =
        DefaultDeviceRepository(application, applicationScope)
}
