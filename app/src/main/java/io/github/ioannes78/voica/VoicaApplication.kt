package io.github.ioannes78.voica

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Process
import io.github.ioannes78.voica.audio.AudioSourceResolver
import io.github.ioannes78.voica.audio.PcmSourceResolver
import io.github.ioannes78.voica.ble.DefaultDeviceRepository
import io.github.ioannes78.voica.ble.DeviceRepository
import io.github.ioannes78.voica.database.AiSummaryRepository
import io.github.ioannes78.voica.database.DiarizationActiveRunReader
import io.github.ioannes78.voica.database.DiarizationRepository
import io.github.ioannes78.voica.database.RecordingLibraryRepository
import io.github.ioannes78.voica.database.RealtimeTranscriptPersistenceRepository
import io.github.ioannes78.voica.database.SearchIndexRebuilder
import io.github.ioannes78.voica.database.Stage12CContentRepository
import io.github.ioannes78.voica.database.Stage13B5Qa4Repository
import io.github.ioannes78.voica.database.TranscriptionRepository
import io.github.ioannes78.voica.database.UnifiedSearchRepository
import io.github.ioannes78.voica.database.VoicaDatabase
import io.github.ioannes78.voica.model.ModelUseRegistry
import io.github.ioannes78.voica.opus.NativeOpusBackend
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

    val timelineAnnotationRepository =
        io.github.ioannes78.voica.database.TimelineAnnotationRepository(recordingDatabase)
    val timelineAnnotationAnchorResolver =
        io.github.ioannes78.voica.database.TimelineAnnotationAnchorResolver(recordingDatabase)
    val timelineAnnotationAttachmentStore =
        TimelineAnnotationAttachmentStore(
            context = application,
            database = recordingDatabase,
            annotationRepository = timelineAnnotationRepository,
        )
    val timelineAnnotationContentCoordinator =
        TimelineAnnotationContentCoordinator(
            repository = timelineAnnotationRepository,
            attachmentStore = timelineAnnotationAttachmentStore,
        )

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
    val taskCompletionNoticeStore = TaskCompletionNoticeStore(application)
    private val stage8BaseTranscriptionEngineProvider =
        SherpaStage8TranscriptionEngineProvider(
            assetManager = application.assets,
        )
    private val stage16BLocalFileTranscriptionEngineProvider =
        Stage16BLocalFileTranscriptionEngineProvider(
            delegate = stage8BaseTranscriptionEngineProvider,
            assetManager = application.assets,
        )
    private val stage9BaseDiarizationEngineProvider =
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
    val stage13B5Qa4Repository = Stage13B5Qa4Repository(recordingDatabase)
    val unifiedSearchRepository = UnifiedSearchRepository(recordingDatabase)
    val searchIndexRebuilder =
        SearchIndexRebuilder(
            database = recordingDatabase,
            searchRepository = unifiedSearchRepository,
        )

    val diarizationRepository = DiarizationRepository(recordingDatabase)
    private val stage9VadReusingDiarizationEngineProvider =
        VadReusingStage9DiarizationEngineProvider(
            delegate = stage9BaseDiarizationEngineProvider,
            activeRunReader = DiarizationActiveRunReader(recordingDatabase),
            transcriptionRepository = transcriptionRepository,
        )

    val aiSummaryRepository = AiSummaryRepository(recordingDatabase)

    val transcriptionCoordinator =
        TranscriptionCoordinator(
            scope = applicationScope,
            pcmSourceResolver = pcmSourceResolver,
            transcriptionRepository = transcriptionRepository,
            loadCanonicalLineage = recordingLibraryRepository::loadCanonicalTranscriptionLineage,
            modelManager = Stage16BLocalFileVadModelManager(modelManager),
            modelUseRegistry = modelUseRegistry,
            engineProvider = stage16BLocalFileTranscriptionEngineProvider,
            localSpeechSettings = { localSpeechSettingsStore.settings.value },
            isRecordingActive = recordingLibraryRepository::isRecordingActive,
        )

    val speechBenchmarkRunner =
        SpeechBenchmarkRunner(
            application = application,
            pcmSourceResolver = pcmSourceResolver,
            modelManager = modelManager,
            modelUseRegistry = modelUseRegistry,
            engineProvider = stage8BaseTranscriptionEngineProvider,
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
            engineProvider = stage9VadReusingDiarizationEngineProvider,
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

    val longTaskNotificationController =
        LongTaskNotificationController(
            context = application,
            scope = applicationScope,
            recordingRepository = recordingLibraryRepository,
            transcriptionCoordinator = transcriptionCoordinator,
            diarizationCoordinator = diarizationCoordinator,
            stage12CContentRepository = stage12CContentRepository,
            stage13B5Qa4Repository = stage13B5Qa4Repository,
            taskCompletionNoticeStore = taskCompletionNoticeStore,
        )

    val stage13B5Qa4CandidateNotificationController =
        Stage13B5Qa4CandidateNotificationController(
            context = application,
            scope = applicationScope,
            recordingRepository = recordingLibraryRepository,
            qa4Repository = stage13B5Qa4Repository,
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
                ),
            annotationAttachmentCleanup =
                timelineAnnotationAttachmentStore::deleteAttachmentsForRecording,
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
            onRegistered = { recordingId ->
                canonicalAudioCoordinator.requestAutomatic(recordingId)
                applicationScope.launch {
                    runCatching { timelineAnnotationRepository.reconcilePending() }
                }
            },
        )

    private val defaultDeviceRepository =
        DefaultDeviceRepository(
            context = application,
            parentScope = applicationScope,
            downloadedAssetRegistry = downloadedAssetRegistry,
        )

    private val deviceRuntime: DeviceRepository = defaultDeviceRepository

    private val realtimeOpusBackend = NativeOpusBackend()

    val realtimeAudioRuntime =
        RealtimeAudioRuntime(
            scope = applicationScope,
            transport = defaultDeviceRepository.realtimeTransport,
            packetInspector = realtimeOpusBackend,
            decoderFactory = realtimeOpusBackend,
        )

    private val stage16ARealtimePersistenceRepository =
        RealtimeTranscriptPersistenceRepository(recordingDatabase)
    private val stage16ARealtimeModelResolver =
        Stage16ARealtimeModelResolver(
            activeModel = { modelId -> modelManager.activeModel(modelId) },
            modelUseRegistry = modelUseRegistry,
        )
    private val stage16ARealtimeEngineProvider =
        SherpaStage16ARealtimeEngineProvider(
            assetManager = application.assets,
        )
    private val stage16ARealtimePipelineFactory =
        Stage16ARealtimePipelineBundleFactory(
            scope = applicationScope,
            engineProvider = stage16ARealtimeEngineProvider,
            context = application,
        )
    private val stage16ALocalAudioRoot = File(recordingsRoot, "realtime")

    val stage16ARealtimeRuntime =
        Stage16ARealtimeRuntime(
            scope = applicationScope,
            recorderRuntime = realtimeAudioRuntime,
            modelResolver = stage16ARealtimeModelResolver,
            pipelineFactory = stage16ARealtimePipelineFactory,
            persistenceRepository = stage16ARealtimePersistenceRepository,
            localAudioRoot = stage16ALocalAudioRoot,
            localSpeechSettings = { localSpeechSettingsStore.settings.value },
            currentDeviceFilename = { defaultDeviceRepository.recordingState.value.filename },
        )

    private val phoneMicrophoneRecordingRegistrar =
        io.github.ioannes78.voica.database.PhoneMicrophoneRecordingRegistrar(
            database = recordingDatabase,
            recordingsRoot = recordingsRoot,
        )
    private val phoneMicrophoneRecoveryCoordinator =
        PhoneMicrophoneRecoveryCoordinator(
            recordingsRoot = recordingsRoot,
            isRegistered = { recordingId ->
                recordingLibraryRepository.loadRecording(recordingId) != null
            },
            registerRecording = phoneMicrophoneRecordingRegistrar::register,
        )
    private val phoneMicrophoneIntegrationRepository =
        io.github.ioannes78.voica.database.RealtimeRecordingIntegrationRepository(recordingDatabase)

    internal val phoneMicrophoneRecordingRuntime =
        PhoneMicrophoneRecordingRuntime(
            capture = AndroidPhoneMicrophoneCaptureDriver(application),
            recordingsRoot = recordingsRoot,
            registerRecording = phoneMicrophoneRecordingRegistrar::register,
            timelineAnnotationRepository = timelineAnnotationRepository,
        )

    internal val phoneMicrophoneRealtimeRuntime =
        PhoneMicrophoneRealtimeRuntime(
            scope = applicationScope,
            recordingRuntime = phoneMicrophoneRecordingRuntime,
            modelResolver = stage16ARealtimeModelResolver,
            pipelineFactory = stage16ARealtimePipelineFactory,
            persistenceRepository = stage16ARealtimePersistenceRepository,
            recordingLibraryRepository = recordingLibraryRepository,
            integrationRepository = phoneMicrophoneIntegrationRepository,
            recordingsRoot = recordingsRoot,
            localSpeechSettings = { localSpeechSettingsStore.settings.value },
            timelineAnnotationRepository = timelineAnnotationRepository,
        )

    internal val realtimeTimelineAnnotationCaptureResolver =
        RealtimeTimelineAnnotationCaptureResolver(
            persistenceRepository = stage16ARealtimePersistenceRepository,
            recorderBoundary = stage16ARealtimeRuntime::annotationBoundarySnapshot,
            phoneBoundary = phoneMicrophoneRealtimeRuntime::annotationBoundarySnapshot,
        )

    val deviceRepository: DeviceRepository =
        ServiceBackedDeviceRepository(
            context = application,
            delegate = deviceRuntime,
            scope = applicationScope,
            realtimeState = stage16ARealtimeRuntime.state,
        )

    val stage16ARecordingLifecycleCoordinator =
        Stage16ARecordingLifecycleCoordinator(
            scope = applicationScope,
            realtimeState = stage16ARealtimeRuntime.state,
            deviceRepository = deviceRepository,
            database = recordingDatabase,
            recordingsRoot = recordingsRoot,
            searchIndexRebuilder = searchIndexRebuilder,
            transportGeneration = { realtimeAudioRuntime.snapshot().transportGeneration },
            timelineAnnotationRepository = timelineAnnotationRepository,
        )

    val stage16AReconnectRecoveryCoordinator =
        Stage16AReconnectRecoveryCoordinator(
            scope = applicationScope,
            connectionStates = deviceRepository.connectionState,
            recordingStates = deviceRepository.recordingState,
            realtimeState = stage16ARealtimeRuntime.state,
            transportGeneration = { realtimeAudioRuntime.snapshot().transportGeneration },
            syncRecordingState = deviceRepository::syncRecordingState,
            recover = stage16ARealtimeRuntime::recoverAfterReconnect,
            recordingAnchorProvider = stage16ARecordingLifecycleCoordinator::anchorForRun,
        )

    val realtimeRecordingLifecycleReconciler =
        RealtimeRecordingLifecycleReconciler(
            scope = applicationScope,
            recordingStates = deviceRepository.recordingState,
            runtimeSnapshot = realtimeAudioRuntime::snapshot,
            reconcile = realtimeAudioRuntime::reconcileDeviceRecordingEnded,
        )

    init {
        Stage13CDiarizationAttentionRuntime.install(
            application = application,
            scope = applicationScope,
            database = recordingDatabase,
            recordingRepository = recordingLibraryRepository,
            coordinator = diarizationCoordinator,
        )

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
            val annotationDeleteCleanup =
                runCatching { timelineAnnotationAttachmentStore.cleanupDeletingRecordings() }
            if (
                annotationDeleteCleanup.isSuccess &&
                annotationDeleteCleanup.getOrThrow().blockedRecordingIds.isEmpty()
            ) {
                recordingLibraryRepository.reconcilePendingDeletes()
            }
            runCatching {
                stage16ARealtimePersistenceRepository.interruptNonTerminalRunsForSource(
                    audioSourceType =
                        io.github.ioannes78.voica.database.RealtimeAudioSourceTypeValue.PHONE_MICROPHONE,
                    endedAtMs = System.currentTimeMillis(),
                    endReason = "PROCESS_RESTART",
                    errorCode = "PHONE_MIC_PROCESS_INTERRUPTED",
                    errorMessage = "应用进程中断，手机实时转写未正常结束",
                )
            }
            runCatching { phoneMicrophoneRecoveryCoordinator.reconcileOnStartup() }
            runCatching { timelineAnnotationRepository.reconcilePending() }
            runCatching { timelineAnnotationAttachmentStore.reconcileOnStartup() }
            localAudioImportCoordinator.cleanupStaleStaging()
            localAudioExportCoordinator.cleanupStaleShareCache()
            canonicalAudioCoordinator.reconcileOnStartup()
            transcriptionCoordinator.reconcileOnStartup()
            diarizationCoordinator.reconcileOnStartup()
            autoDiarizationPostProcessor.recoverPendingOnStartup()
            searchIndexRebuilder.rebuildIfRequired()
        }
    }
}
