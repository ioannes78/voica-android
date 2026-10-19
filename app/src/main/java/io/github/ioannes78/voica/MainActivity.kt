package io.github.ioannes78.voica

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.ioannes78.voica.ble.DeviceRepository
import io.github.ioannes78.voica.database.RecordingLibraryRepository
import io.github.ioannes78.voica.ui.VoicaApp
import io.github.ioannes78.voica.ui.annotation.TimelineAnnotationViewModel
import io.github.ioannes78.voica.ui.phone.LocalPhoneMicrophoneRealtimeRuntime
import io.github.ioannes78.voica.ui.phone.LocalPhoneMicrophoneRecordingRuntime
import io.github.ioannes78.voica.ui.phone.PhoneMicrophoneRecordingScreen
import io.github.ioannes78.voica.ui.phone.PhoneMicrophoneRecordingUiController
import io.github.ioannes78.voica.ui.phone.PhoneMicrophoneRealtimeScreen
import io.github.ioannes78.voica.ui.playback.TimelineAnnotationPlaybackCoordinator
import io.github.ioannes78.voica.ui.realtime.LocalStage16ARealtimeRuntime
import io.github.ioannes78.voica.ui.realtime.PhoneMicrophoneRealtimeUiController
import io.github.ioannes78.voica.ui.realtime.RealtimeTranscriptionScreen
import io.github.ioannes78.voica.ui.status.LocalProductSpeechSettings
import io.github.ioannes78.voica.ui.status.LocalProductSpeechSettingsStore
import io.github.ioannes78.voica.ui.status.LocalRecorderRecordingState
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
        consumeNavigationIntent(intent)
        setContent {
            val themeSettings by appContainer.themeSettingsStore.settings.collectAsState()
            val realtimeOpenRequest by AppRealtimeNavigation.request.collectAsState()
            val phoneOpenRequest by AppPhoneMicrophoneNavigation.request.collectAsState()
            val recordingOpenRequest by AppRecordingNavigation.request.collectAsState()
            val realtimeState by appContainer.stage16ARealtimeRuntime.state.collectAsState()
            val recordingTokenAtRealtimeOpen =
                remember(realtimeOpenRequest?.token) {
                    recordingOpenRequest?.token
                }
            val phoneUiScope = rememberCoroutineScope()
            val phoneRecordingController =
                remember(
                    appContainer.phoneMicrophoneRecordingRuntime,
                    appContainer.phoneMicrophoneRealtimeRuntime,
                    phoneUiScope,
                ) {
                    PhoneMicrophoneRecordingUiController(
                        recordingRuntime = appContainer.phoneMicrophoneRecordingRuntime,
                        realtimeRuntime = appContainer.phoneMicrophoneRealtimeRuntime,
                        scope = phoneUiScope,
                    )
                }
            val phoneRealtimeController =
                remember(
                    appContainer.phoneMicrophoneRealtimeRuntime,
                    appContainer.phoneMicrophoneRecordingRuntime,
                    phoneUiScope,
                ) {
                    PhoneMicrophoneRealtimeUiController(
                        runtime = appContainer.phoneMicrophoneRealtimeRuntime,
                        captureState = appContainer.phoneMicrophoneRecordingRuntime.captureState,
                        scope = phoneUiScope,
                    )
                }
            val annotationPlaybackCoordinator =
                remember(
                    appContainer.playbackController,
                    appContainer.timelineAnnotationAnchorResolver,
                ) {
                    TimelineAnnotationPlaybackCoordinator(
                        controller = appContainer.playbackController,
                        resolver = appContainer.timelineAnnotationAnchorResolver,
                    )
                }
            val phoneOverlayAnnotationViewModel: TimelineAnnotationViewModel =
                viewModel(
                    key = "phone-overlay-annotation",
                    factory =
                        remember(
                            appContainer.timelineAnnotationRepository,
                            annotationPlaybackCoordinator,
                            appContainer.timelineAnnotationAttachmentStore,
                            appContainer.timelineAnnotationContentCoordinator,
                        ) {
                            TimelineAnnotationViewModel.Factory(
                                repository = appContainer.timelineAnnotationRepository,
                                playbackCoordinator = annotationPlaybackCoordinator,
                                attachmentStore = appContainer.timelineAnnotationAttachmentStore,
                                contentCoordinator = appContainer.timelineAnnotationContentCoordinator,
                            )
                        },
                )

            LaunchedEffect(
                realtimeOpenRequest?.token,
                realtimeState.phase,
                recordingOpenRequest?.token,
                recordingTokenAtRealtimeOpen,
            ) {
                if (
                    shouldDismissRealtimeOverlayForRecordingHandoff(
                        realtimeRequest = realtimeOpenRequest,
                        realtimePhase = realtimeState.phase,
                        recordingTokenAtOpen = recordingTokenAtRealtimeOpen,
                        currentRecordingToken = recordingOpenRequest?.token,
                    )
                ) {
                    AppRealtimeNavigation.dismiss()
                }
            }

            VoicaTheme(settings = themeSettings) {
                CompositionLocalProvider(
                    LocalStage16ARealtimeRuntime provides appContainer.stage16ARealtimeRuntime,
                    LocalPhoneMicrophoneRecordingRuntime provides appContainer.phoneMicrophoneRecordingRuntime,
                    LocalPhoneMicrophoneRealtimeRuntime provides appContainer.phoneMicrophoneRealtimeRuntime,
                    LocalRecorderRecordingState provides deviceRepository.recordingState,
                    LocalProductSpeechSettings provides appContainer.localSpeechSettingsStore.settings,
                    LocalProductSpeechSettingsStore provides appContainer.localSpeechSettingsStore,
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Stage13B5Qa4GlobalAttentionHost(container = appContainer) {
                            VoicaApp(
                                repository = deviceRepository,
                                recordingLibraryRepository = recordingLibraryRepository,
                                canonicalAudioCoordinator = canonicalAudioCoordinator,
                                localAudioImportCoordinator = appContainer.localAudioImportCoordinator,
                                localRecordingDeleteCoordinator =
                                    appContainer.localRecordingDeleteCoordinator,
                                localAudioExportCoordinator =
                                    appContainer.localAudioExportCoordinator,
                                storageManagementCoordinator =
                                    appContainer.storageManagementCoordinator,
                                playbackController = appContainer.playbackController,
                                modelManager = appContainer.modelManager,
                                modelUpdateController = appContainer.modelUpdateController,
                                transcriptionCoordinator = appContainer.transcriptionCoordinator,
                                transcriptionRepository = appContainer.transcriptionRepository,
                                stage12CContentRepository = appContainer.stage12CContentRepository,
                                unifiedSearchRepository = appContainer.unifiedSearchRepository,
                                searchIndexRebuilder = appContainer.searchIndexRebuilder,
                                diarizationCoordinator = appContainer.diarizationCoordinator,
                                diarizationRepository = appContainer.diarizationRepository,
                                aiSummaryCoordinator = appContainer.aiSummaryCoordinator,
                                aiSummaryRepository = appContainer.aiSummaryRepository,
                                providerProfileStore = appContainer.providerProfileStore,
                                providerConfigurationRepository =
                                    appContainer.providerConfigurationRepository,
                                providerAdapterRegistry = appContainer.providerAdapterRegistry,
                                themeSettingsStore = appContainer.themeSettingsStore,
                                localSpeechSettingsStore = appContainer.localSpeechSettingsStore,
                                speechBenchmarkRunner =
                                    appContainer.speechBenchmarkRunner.takeIf {
                                        VoicaModelChannel.isDebuggable(application)
                                    },
                                diarizationBenchmarkRunner = null,
                            )
                        }

                        if (realtimeOpenRequest != null) {
                            Surface(modifier = Modifier.fillMaxSize()) {
                                RealtimeTranscriptionScreen(
                                    padding = PaddingValues(0.dp),
                                    runtime = appContainer.stage16ARealtimeRuntime,
                                    canStart = false,
                                    onBack = AppRealtimeNavigation::dismiss,
                                )
                            }
                        }

                        phoneOpenRequest?.let { request ->
                            Surface(modifier = Modifier.fillMaxSize()) {
                                when (request.target) {
                                    PhoneMicrophoneProductTarget.RECORDING ->
                                        PhoneMicrophoneRecordingScreen(
                                            padding = PaddingValues(0.dp),
                                            controller = phoneRecordingController,
                                            annotationViewModel = phoneOverlayAnnotationViewModel,
                                            annotationTargetProvider = {
                                                phoneRecordingAnnotationTarget(
                                                    appContainer.phoneMicrophoneRecordingRuntime.state.value,
                                                )
                                            },
                                            onBack = AppPhoneMicrophoneNavigation::dismiss,
                                        )

                                    PhoneMicrophoneProductTarget.REALTIME ->
                                        PhoneMicrophoneRealtimeScreen(
                                            padding = PaddingValues(0.dp),
                                            controller = phoneRealtimeController,
                                            canStart = false,
                                            onBack = AppPhoneMicrophoneNavigation::dismiss,
                                        )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeNavigationIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        ModelInstallTaskOwnershipStore(applicationContext).registerCurrentTask(taskId)
        AiSummaryTaskOwnershipStore(applicationContext).registerCurrentTask(taskId)
        deviceRepository.setForeground(true)
    }

    override fun onStop() {
        deviceRepository.setForeground(false)
        super.onStop()
    }

    private fun consumeNavigationIntent(intent: Intent) {
        AppRecordingNavigation.consumeIntent(intent)
        val phoneRequest = AppPhoneMicrophoneNavigation.consumeIntent(intent)
        val realtimeRequest = AppRealtimeNavigation.consumeIntent(intent)
        when {
            phoneRequest != null -> AppRealtimeNavigation.dismiss()
            realtimeRequest != null -> AppPhoneMicrophoneNavigation.dismiss()
        }
    }
}
