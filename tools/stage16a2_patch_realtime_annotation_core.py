from pathlib import Path


def one(source: str, old: str, new: str, label: str) -> str:
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    return source.replace(old, new, 1)


capture = Path("app/src/main/java/io/github/ioannes78/voica/RealtimeTimelineAnnotationCapture.kt")
if capture.exists():
    raise SystemExit("RealtimeTimelineAnnotationCapture.kt already exists")
capture.write_text('''package io.github.ioannes78.voica

import io.github.ioannes78.voica.database.RealtimeMediaSegmentEntity
import io.github.ioannes78.voica.database.RealtimeTranscriptPersistenceRepository
import io.github.ioannes78.voica.database.TimelineAnnotationAnchor
import io.github.ioannes78.voica.database.TimelineAnnotationAnchorDomainValue
import io.github.ioannes78.voica.database.TimelineAnnotationOwner
import io.github.ioannes78.voica.database.TimelineAnnotationPendingOwnerKindValue
import io.github.ioannes78.voica.transcript.RealtimeProcessedCanonicalBoundary

internal enum class RealtimeAnnotationCaptureSource {
    RECORDER_CARD,
    PHONE_MICROPHONE,
}

internal data class RealtimeAnnotationRuntimeBoundary(
    val source: RealtimeAnnotationCaptureSource,
    val runId: String,
    val pendingRecordingId: String?,
    val canonicalSessionId: Long,
    val canonicalEpochId: Long,
    val sampleIndex: Long,
)

internal data class RealtimeAnnotationCaptureTarget(
    val owner: TimelineAnnotationOwner.Pending,
    val anchor: TimelineAnnotationAnchor,
)

internal fun recorderRealtimeAnnotationBoundary(
    runId: String,
    boundary: RealtimeProcessedCanonicalBoundary?,
): RealtimeAnnotationRuntimeBoundary? {
    if (runId.isBlank() || boundary == null) return null
    return RealtimeAnnotationRuntimeBoundary(
        source = RealtimeAnnotationCaptureSource.RECORDER_CARD,
        runId = runId,
        pendingRecordingId = null,
        canonicalSessionId = boundary.sessionId,
        canonicalEpochId = boundary.epochId,
        sampleIndex = boundary.endSampleIndexExclusive,
    )
}

internal fun phoneRealtimeAnnotationBoundary(
    runId: String,
    recording: PhoneMicrophoneRecordingSnapshot,
): RealtimeAnnotationRuntimeBoundary? {
    if (runId.isBlank()) return null
    val recordingId = recording.recordingId?.takeIf { it.isNotBlank() } ?: return null
    val sessionId = recording.canonicalSessionId ?: return null
    val epochId = recording.activeEpochId ?: return null
    if (recording.writtenSamples <= 0L) return null
    return RealtimeAnnotationRuntimeBoundary(
        source = RealtimeAnnotationCaptureSource.PHONE_MICROPHONE,
        runId = runId,
        pendingRecordingId = recordingId,
        canonicalSessionId = sessionId,
        canonicalEpochId = epochId,
        sampleIndex = recording.writtenSamples,
    )
}

internal fun buildRealtimeAnnotationCaptureTarget(
    boundary: RealtimeAnnotationRuntimeBoundary,
    segments: List<RealtimeMediaSegmentEntity>,
): RealtimeAnnotationCaptureTarget? {
    if (boundary.sampleIndex <= 0L) return null
    val segment =
        segments.singleOrNull { candidate ->
            candidate.runId == boundary.runId &&
                candidate.canonicalSessionId == boundary.canonicalSessionId
        } ?: return null
    val owner =
        when (boundary.source) {
            RealtimeAnnotationCaptureSource.RECORDER_CARD ->
                TimelineAnnotationOwner.Pending(
                    kind = TimelineAnnotationPendingOwnerKindValue.REALTIME_RUN,
                    ownerId = boundary.runId,
                )
            RealtimeAnnotationCaptureSource.PHONE_MICROPHONE -> {
                val recordingId =
                    boundary.pendingRecordingId?.takeIf { it.isNotBlank() } ?: return null
                TimelineAnnotationOwner.Pending(
                    kind = TimelineAnnotationPendingOwnerKindValue.PHONE_CAPTURE,
                    ownerId = recordingId,
                )
            }
        }
    return RealtimeAnnotationCaptureTarget(
        owner = owner,
        anchor =
            TimelineAnnotationAnchor(
                domain = TimelineAnnotationAnchorDomainValue.REALTIME_MEDIA_SEGMENT,
                sampleIndex = boundary.sampleIndex,
                realtimeRunIdSnapshot = boundary.runId,
                realtimeSegmentIdSnapshot = segment.id,
                realtimeSegmentIndexSnapshot = segment.segmentIndex,
                canonicalSessionIdSnapshot = boundary.canonicalSessionId,
                canonicalEpochIdSnapshot = boundary.canonicalEpochId,
            ),
    )
}

internal class RealtimeTimelineAnnotationCaptureResolver(
    private val persistenceRepository: RealtimeTranscriptPersistenceRepository,
    private val recorderBoundary: () -> RealtimeAnnotationRuntimeBoundary?,
    private val phoneBoundary: () -> RealtimeAnnotationRuntimeBoundary?,
) {
    suspend fun resolve(source: RealtimeAnnotationCaptureSource): RealtimeAnnotationCaptureTarget? {
        val boundary =
            when (source) {
                RealtimeAnnotationCaptureSource.RECORDER_CARD -> recorderBoundary()
                RealtimeAnnotationCaptureSource.PHONE_MICROPHONE -> phoneBoundary()
            } ?: return null
        return buildRealtimeAnnotationCaptureTarget(
            boundary = boundary,
            segments = persistenceRepository.getSegments(boundary.runId),
        )
    }
}
''')

# Recorder runtime exposes only the last processed canonical boundary; never wall clock.
path = Path("app/src/main/java/io/github/ioannes78/voica/Stage16ARealtimeRuntime.kt")
s = path.read_text()
s = one(
    s,
    '    suspend fun pause(): Stage16ARealtimeActionOutcome = commandMutex.withLock {',
    '''    internal fun annotationBoundarySnapshot(): RealtimeAnnotationRuntimeBoundary? {
        val run = activeRun ?: return null
        return recorderRealtimeAnnotationBoundary(
            runId = run.runId,
            boundary = run.bundle.runner.snapshot().lastProcessedBoundary,
        )
    }

    suspend fun pause(): Stage16ARealtimeActionOutcome = commandMutex.withLock {''',
    'recorder annotation boundary',
)
path.write_text(s)

# Phone runtime uses formal WAV sample truth and promotes pending phone annotations after Recording exists.
path = Path("app/src/main/java/io/github/ioannes78/voica/PhoneMicrophoneRealtimeRuntime.kt")
s = path.read_text()
s = one(
    s,
    'import io.github.ioannes78.voica.database.RealtimeTranscriptionStateValue\nimport io.github.ioannes78.voica.database.RecordingLibraryRepository\n',
    'import io.github.ioannes78.voica.database.RealtimeTranscriptionStateValue\nimport io.github.ioannes78.voica.database.RecordingLibraryRepository\nimport io.github.ioannes78.voica.database.TimelineAnnotationPendingOwnerKindValue\nimport io.github.ioannes78.voica.database.TimelineAnnotationRepository\n',
    'phone annotation imports',
)
s = one(
    s,
    '    private val localSpeechSettings: () -> LocalSpeechSettings,\n    private val clockMs: () -> Long = System::currentTimeMillis,',
    '    private val localSpeechSettings: () -> LocalSpeechSettings,\n    private val timelineAnnotationRepository: TimelineAnnotationRepository? = null,\n    private val clockMs: () -> Long = System::currentTimeMillis,',
    'phone constructor repo',
)
s = one(
    s,
    '    suspend fun pause(): PhoneMicrophoneRealtimeActionOutcome = commandMutex.withLock {',
    '''    internal fun annotationBoundarySnapshot(): RealtimeAnnotationRuntimeBoundary? {
        val run = activeRun ?: return null
        return phoneRealtimeAnnotationBoundary(
            runId = run.runId,
            recording = recordingRuntime.state.value,
        )
    }

    suspend fun pause(): PhoneMicrophoneRealtimeActionOutcome = commandMutex.withLock {''',
    'phone annotation boundary',
)
s = one(
    s,
    '''        if (!attached && run.persistenceFailure == null) {
            run.persistenceFailure = "ATTACH_RECORDING:realtime run attach returned false"
        }

        val timeoutFailure =''',
    '''        if (!attached && run.persistenceFailure == null) {
            run.persistenceFailure = "ATTACH_RECORDING:realtime run attach returned false"
        }
        if (attached) {
            runCatching {
                timelineAnnotationRepository?.attachPendingOwner(
                    ownerKind = TimelineAnnotationPendingOwnerKindValue.PHONE_CAPTURE,
                    ownerId = saved.recordingId,
                    recordingId = saved.recordingId,
                )
            }
        }

        val timeoutFailure =''',
    'phone pending promotion',
)
path.write_text(s)

# Recorder-card lifecycle promotes run-owned pending annotations only after run->Recording attach succeeds.
path = Path("app/src/main/java/io/github/ioannes78/voica/Stage16ARecordingLifecycleCoordinator.kt")
s = path.read_text()
s = one(
    s,
    'import io.github.ioannes78.voica.database.SearchIndexRebuilder\nimport io.github.ioannes78.voica.database.VoicaDatabase\n',
    'import io.github.ioannes78.voica.database.SearchIndexRebuilder\nimport io.github.ioannes78.voica.database.TimelineAnnotationPendingOwnerKindValue\nimport io.github.ioannes78.voica.database.TimelineAnnotationRepository\nimport io.github.ioannes78.voica.database.VoicaDatabase\n',
    'recorder promotion imports',
)
s = one(
    s,
    '    private val transportGeneration: () -> Long?,\n    private val clockMs: () -> Long = { System.currentTimeMillis() },\n    startCollectors: Boolean = true,',
    '    private val transportGeneration: () -> Long?,\n    private val timelineAnnotationRepository: TimelineAnnotationRepository? = null,\n    private val clockMs: () -> Long = { System.currentTimeMillis() },\n    startCollectors: Boolean = true,',
    'lifecycle constructor repo',
)
s = one(
    s,
    '''            check(realtimeRepository.attachRecording(runId, recordingId, clockMs())) {
                "failed to attach realtime run to recording"
            }
            val remoteFilename =''',
    '''            check(realtimeRepository.attachRecording(runId, recordingId, clockMs())) {
                "failed to attach realtime run to recording"
            }
            runCatching {
                timelineAnnotationRepository?.attachPendingOwner(
                    ownerKind = TimelineAnnotationPendingOwnerKindValue.REALTIME_RUN,
                    ownerId = runId,
                    recordingId = recordingId,
                )
            }.onFailure { failure ->
                latestSnapshot =
                    latestSnapshot.copy(
                        lastError = "annotation promotion pending: " +
                            (failure.message ?: failure.javaClass.simpleName),
                    )
            }
            val remoteFilename =''',
    'recorder pending promotion',
)
path.write_text(s)

# Wire repositories/resolver in the app container.
path = Path("app/src/main/java/io/github/ioannes78/voica/VoicaApplication.kt")
s = path.read_text()
s = one(
    s,
    '            localSpeechSettings = { localSpeechSettingsStore.settings.value },\n        )\n\n    val deviceRepository: DeviceRepository =',
    '''            localSpeechSettings = { localSpeechSettingsStore.settings.value },
            timelineAnnotationRepository = timelineAnnotationRepository,
        )

    internal val realtimeTimelineAnnotationCaptureResolver =
        RealtimeTimelineAnnotationCaptureResolver(
            persistenceRepository = stage16ARealtimePersistenceRepository,
            recorderBoundary = stage16ARealtimeRuntime::annotationBoundarySnapshot,
            phoneBoundary = phoneMicrophoneRealtimeRuntime::annotationBoundarySnapshot,
        )

    val deviceRepository: DeviceRepository =''',
    'phone runtime and resolver wiring',
)
s = one(
    s,
    '            transportGeneration = { realtimeAudioRuntime.snapshot().transportGeneration },\n        )\n\n    val stage16AReconnectRecoveryCoordinator =',
    '            transportGeneration = { realtimeAudioRuntime.snapshot().transportGeneration },\n            timelineAnnotationRepository = timelineAnnotationRepository,\n        )\n\n    val stage16AReconnectRecoveryCoordinator =',
    'recorder lifecycle repo wiring',
)
path.write_text(s)
