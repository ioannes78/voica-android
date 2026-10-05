package io.github.ioannes78.voica

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Process
import io.github.ioannes78.voica.ai.AiSummaryEngine
import io.github.ioannes78.voica.audio.AudioSourceResolver
import io.github.ioannes78.voica.audio.PcmSourceResolver
import io.github.ioannes78.voica.ble.DefaultDeviceRepository
import io.github.ioannes78.voica.ble.DeviceRepository
import io.github.ioannes78.voica.database.AiSummaryRepository
import io.github.ioannes78.voica.database.DiarizationRepository
import io.github.ioannes78.voica.database.RecordingLibraryRepository
import io.github.ioannes78.voica.database.SearchIndexRebuilder
import io.github.ioannes78.voica.database.Stage12CContentRepository
import io.github.ioannes78.voica.database.StructuredTranscriptInputBuilder
import io.github.ioannes78.voica.database.TranscriptionRepository
import io.github.ioannes78.voica.database.UnifiedSearchRepository
import io.github.ioannes78.voica.database.VoicaDatabase
import io.github.ioannes78.voica.llm.AndroidKeystoreCredentialStore
import io.github.ioannes78.voica.llm.AppPrivateProviderProfileStore
import io.github.ioannes78.voica.llm.ProviderAdapterRegistry
import io.github.ioannes78.voica.llm.ProviderConfigurationRepository
import io.github.ioannes78.voica.llm.UrlConnectionLlmHttpTransport
import io.github.ioannes78.voica.model.ModelUseRegistry
import io.github.ioannes78.voica.playback.AndroidPlaybackController
import io.github.ioannes78.voica.ui.theme.SharedPreferencesThemeSettingsStore
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class VoicaApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        if (isModelValidatorProcess()) return
        container = AppContainer(this)
    }

    private fun isModelValidatorProcess(): Boolean {
        val processName =
            if (Build.VERSION.SDK_INT >= 28) {
                Application.getProcessName()
            } else {
                val manager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
                val pid = Process.myPid()
                manager.runningAppProcesses
                    ?.firstOrNull { it.pid == pid }
                    ?.processName
            }
        return processName == packageName + MODEL_VALIDATOR_PROCESS_SUFFIX
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

    val localAudioImportCoordinator =
        LocalAudioImportCoordinator(
            context = application,
            repository = recordingLibraryRepository,
            recordingsRoot = recordingsRoot,
            onImported = canonicalAudioCoordinator::requestAutomatic,
        )

    val localAudioExportCoordinator =
        LocalAudioExportCoordinator(
            context = application,
            repository = recordingLibraryRepository,
            recordingsRoot = recordingsRoot,
            canonicalAudioCoordinator = canonicalAudioCoordinator,
        )

    private val roomAudioSourceResolver =
        RoomAudioSourceResolver(
            repository = recordingLibraryRepository,
            recordingsRoot = recordingsRoot,
        )

    val audioSourceResolver: AudioSourceResolver = roomAudioSourceResolver
    val pcmSourceResolver: PcmSourceResolver = roomAudioSourceResolver

    val modelUseRegistry = ModelUseRegistry()
    val modelManager =
        createVoicaModelManager(
            application = application,
            useRegistry = modelUseRegistry,
        )

    val modelUpdateSettingsStore =
        SharedPreferencesModelUpdateSettingsStore(application)
    val themeSettingsStore =
        SharedPreferencesThemeSettingsStore(application)
    val localSpeechSettingsStore =
        SharedPreferencesLocalSpeechSettingsStore(application)
    private val stage8TranscriptionEngineProvider =
        SherpaStage8TranscriptionEngineProvider(
            assetManager = application.assets,
        )
    private val stage9DiarizationEngineProvider =
        SherpaStage9DiarizationEngineProvider(
            assetManager = application.assets,
        )
    val modelUpdateController =
        ModelUpdateController(
            modelManager = modelManager,
            settingsStore = modelUpdateSettingsStore,
        )

    val transcriptionRepository = TranscriptionRepository(recordingDatabase)
    val stage12CContentRepository = Stage12CContentRepository(recordingDatabase)
    val unifiedSearchRepository = UnifiedSearchRepository(recordingDatabase)
    val searchIndexRebuilder =
        SearchIndexRebuilder(
            database = recordingDatabase,
            searchRepository = unifiedSearchRepository,
        )

    val diarizationRepository = DiarizationRepository(recordingDatabase)

    val aiSummaryRepository = AiSummaryRepository(recordingDatabase)
    val structuredTranscriptInputBuilder = StructuredTranscriptInputBuilder(recordingDatabase)
    val providerCredentialStore = AndroidKeystoreCredentialStore(application)
    val providerProfileStore = AppPrivateProviderProfileStore(application)
    val providerConfigurationRepository =
        ProviderConfigurationRepository(
            profileStore = providerProfileStore,
            credentialStore = providerCredentialStore,
        )
    val providerAdapterRegistry =
        ProviderAdapterRegistry(
            transport = UrlConnectionLlmHttpTransport(),
            credentials = providerCredentialStore,
        )
    val aiSummaryCoordinator =
        AiSummaryCoordinator(
            scope = applicationScope,
            repository = aiSummaryRepository,
            inputBuilder = structuredTranscriptInputBuilder,
            profileStore = providerProfileStore,
            providerRegistry = providerAdapterRegistry,
            engine = AiSummaryEngine(),
            isRecordingActive = recordingLibraryRepository::isRecordingActive,
        )

    val transcriptionCoordinator =
        TranscriptionCoordinator(
            scope = applicationScope,
            pcmSourceResolver = pcmSourceResolver,
            transcriptionRepository = transcriptionRepository,
            loadCanonicalLineage = recordingLibraryRepository::loadCanonicalTranscriptionLineage,
            modelManager = modelManager,
            modelUseRegistry = modelUseRegistry,
            engineProvider = stage8TranscriptionEngineProvider,
            localSpeechSettings = { localSpeechSettingsStore.settings.value },
            isRecordingActive = recordingLibraryRepository::isRecordingActive,
        )

    val speechBenchmarkRunner =
        SpeechBenchmarkRunner(
            application = application,
            pcmSourceResolver = pcmSourceResolver,
            modelManager = modelManager,
            modelUseRegistry = modelUseRegistry,
            engineProvider = stage8TranscriptionEngineProvider,
            localSpeechSettings = { localSpeechSettingsStore.settings.value },
        )

    val diarizationBenchmarkRunner =
        DiarizationBenchmarkRunner(
            application = application,
            pcmSourceResolver = pcmSourceResolver,
            modelManager = modelManager,
            modelUseRegistry = modelUseRegistry,
            engineProvider = stage9DiarizationEngineProvider,
            localSpeechSettings = { localSpeechSettingsStore.settings.value },
        )

    val diarizationCoordinator =
        DiarizationCoordinator(
            scope = applicationScope,
            pcmSourceResolver = pcmSourceResolver,
            diarizationRepository = diarizationRepository,
            transcriptionRepository = transcriptionRepository,
            loadCanonicalLineage = recordingLibraryRepository::loadCanonicalTranscriptionLineage,
            modelManager = modelManager,
            modelUseRegistry = modelUseRegistry,
            engineProvider = stage9DiarizationEngineProvider,
            localSpeechSettings = { localSpeechSettingsStore.settings.value },
            isRecordingActive = recordingLibraryRepository::isRecordingActive,
        )

    val autoDiarizationPostProcessor =
        AutoDiarizationPostProcessor(
            application = application,
            scope = applicationScope,
            transcriptionCoordinator = transcriptionCoordinator,
            transcriptionRepository = transcriptionRepository,
            diarizationCoordinator = diarizationCoordinator,
            diarizationRepository = diarizationRepository,
            localSpeechSettings = { localSpeechSettingsStore.settings.value },
        )

    internal val playbackRuntime =
        AndroidPlaybackController(
            context = application,
            sourceResolver = audioSourceResolver,
            scope = applicationScope,
        )

    val playbackController =
        ServiceBackedPlaybackController(
            context = application,
            delegate = playbackRuntime,
        )

    val localRecordingDeleteCoordinator =
        LocalRecordingDeleteCoordinator(
            repository = recordingLibraryRepository,
            hooks =
                DefaultRecordingDeletionHooks(
                    playbackController = playbackController,
                    canonicalAudioCoordinator = canonicalAudioCoordinator,
                    transcriptionCoordinator = transcriptionCoordinator,
                    diarizationCoordinator = diarizationCoordinator,
                    aiSummaryCoordinator = aiSummaryCoordinator,
                ),
        )

    val storageManagementCoordinator =
        StorageManagementCoordinator(
            application = application,
            repository = recordingLibraryRepository,
            canonicalAudioCoordinator = canonicalAudioCoordinator,
            importCoordinator = localAudioImportCoordinator,
            exportCoordinator = localAudioExportCoordinator,
            modelManager = modelManager,
            playbackController = playbackController,
        )

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

    private val deviceRuntime: DeviceRepository =
        DefaultDeviceRepository(
            context = application,
            parentScope = applicationScope,
            downloadedAssetRegistry = downloadedAssetRegistry,
        )

    val deviceRepository: DeviceRepository =
        ServiceBackedDeviceRepository(
            context = application,
            delegate = deviceRuntime,
            scope = applicationScope,
        )

    init {
        applicationScope.launch {
            combine(
                deviceRepository.recordingState,
                playbackController.stateFlow,
            ) { recordingState, playbackState ->
                RecordingPlaybackInterlockPolicy.shouldPause(
                    recordingState = recordingState,
                    playbackState = playbackState,
                )
            }
                .distinctUntilChanged()
                .collect { shouldPause ->
                    if (shouldPause) {
                        playbackController.pause()
                    }
                }
        }

        if (modelUpdateSettingsStore.settings.value.automaticChecksEnabled) {
            applicationScope.launch(Dispatchers.IO) {
                runCatching {
                    modelUpdateController.checkForUpdates(force = false)
                }
            }
        }

        applicationScope.launch(Dispatchers.IO) {
            recordingLibraryRepository.importLegacyStage5IfNeeded()
            recordingLibraryRepository.normalizeStandardDeviceDisplayNames()
            recordingLibraryRepository.reconcilePendingDeletes()
            localAudioImportCoordinator.cleanupStaleStaging()
            localAudioExportCoordinator.cleanupStaleShareCache()
            canonicalAudioCoordinator.reconcileOnStartup()
            transcriptionCoordinator.reconcileOnStartup()
            diarizationCoordinator.reconcileOnStartup()
            autoDiarizationPostProcessor.recoverPendingOnStartup()
            aiSummaryRepository.reconcileInterruptedOnStartup()
            searchIndexRebuilder.rebuildIfRequired()
        }
    }
}
