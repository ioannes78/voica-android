package io.github.ioannes78.voica

import android.app.Application
import io.github.ioannes78.voica.audio.AudioSourceResolver
import io.github.ioannes78.voica.audio.PcmSourceResolver
import io.github.ioannes78.voica.ble.DefaultDeviceRepository
import io.github.ioannes78.voica.ble.DeviceRepository
import io.github.ioannes78.voica.database.RecordingLibraryRepository
import io.github.ioannes78.voica.database.VoicaDatabase
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
    private val recordingsRoot = File(application.noBackupFilesDir, "recordings")

    private val recordingDatabase = VoicaDatabase.create(application)

    val recordingLibraryRepository =
        RecordingLibraryRepository(
            database = recordingDatabase,
            recordingsRoot = recordingsRoot,
        )

    val canonicalAudioCoordinator =
        CanonicalAudioCoordinator(
            repository = recordingLibraryRepository,
            recordingsRoot = recordingsRoot,
            applicationScope = applicationScope,
        )

    private val roomAudioSourceResolver =
        RoomAudioSourceResolver(
            repository = recordingLibraryRepository,
            recordingsRoot = recordingsRoot,
        )

    val audioSourceResolver: AudioSourceResolver = roomAudioSourceResolver
    val pcmSourceResolver: PcmSourceResolver = roomAudioSourceResolver

    private val downloadedAssetRegistry =
        RoomDownloadedAssetRegistry(
            repository = recordingLibraryRepository,
            recordingsRoot = recordingsRoot,
            onRegistered = canonicalAudioCoordinator::requestAutomatic,
        )

    val deviceRepository: DeviceRepository =
        DefaultDeviceRepository(
            context = application,
            parentScope = applicationScope,
            downloadedAssetRegistry = downloadedAssetRegistry,
        )

    init {
        applicationScope.launch(Dispatchers.IO) {
            recordingLibraryRepository.importLegacyStage5IfNeeded()
            recordingLibraryRepository.reconcilePendingDeletes()
            canonicalAudioCoordinator.reconcileOnStartup()
        }
    }
}
