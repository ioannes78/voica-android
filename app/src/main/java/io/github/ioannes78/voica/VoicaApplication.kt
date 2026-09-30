package io.github.ioannes78.voica

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.ioannes78.voica.audio.AudioSourceResolver
import io.github.ioannes78.voica.audio.PcmSourceResolver
import io.github.ioannes78.voica.ble.DefaultDeviceRepository
import io.github.ioannes78.voica.ble.DeviceRepository
import io.github.ioannes78.voica.database.RecordingLibraryRepository
import io.github.ioannes78.voica.database.VoicaDatabase
import io.github.ioannes78.voica.playback.AndroidPlaybackController
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

    val playbackController =
        AndroidPlaybackController(
            context = application,
            sourceResolver = audioSourceResolver,
            scope = applicationScope,
        )

    private val processLifecycleObserver =
        object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                applicationScope.launch {
                    playbackController.setAppForeground(true)
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                applicationScope.launch {
                    playbackController.setAppForeground(false)
                }
            }
        }

    private val deviceAudioAssetValidator =
        DeviceAudioAssetValidator(
            repository = recordingLibraryRepository,
            recordingsRoot = recordingsRoot,
        )

    private val downloadedAssetRegistry =
        RoomDownloadedAssetRegistry(
            repository = recordingLibraryRepository,
            recordingsRoot = recordingsRoot,
            assetValidator = deviceAudioAssetValidator,
            onRegistered = canonicalAudioCoordinator::requestAutomatic,
        )

    val deviceRepository: DeviceRepository =
        DefaultDeviceRepository(
            context = application,
            parentScope = applicationScope,
            downloadedAssetRegistry = downloadedAssetRegistry,
        )

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(processLifecycleObserver)

        applicationScope.launch(Dispatchers.IO) {
            recordingLibraryRepository.importLegacyStage5IfNeeded()
            recordingLibraryRepository.normalizeStandardDeviceDisplayNames()
            recordingLibraryRepository.reconcilePendingDeletes()
            canonicalAudioCoordinator.reconcileOnStartup()
        }
    }
}
