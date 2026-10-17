package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.RealtimeCanonicalEpochStartReason
import io.github.ioannes78.voica.audio.RealtimeCanonicalPcmFrame
import io.github.ioannes78.voica.database.PhoneMicrophoneRecordingRegistration
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PhoneMicrophoneRouteTransitionTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        PhoneMicrophoneRouteBoundaryHub.clearForTest()
        root = Files.createTempDirectory("phone-route-transition").toFile()
    }

    @After
    fun tearDown() {
        PhoneMicrophoneRouteBoundaryHub.clearForTest()
        root.deleteRecursively()
    }

    @Test
    fun `route tracker emits synchronous boundary when actual route changes`() {
        val routeA = PhoneMicrophoneRoute(16, 15, "RMX3041", "bottom")
        val routeB = PhoneMicrophoneRoute(17, 15, "RMX3041", "back")
        val observed = mutableListOf<PhoneMicrophoneRouteBoundary>()
        val subscription = PhoneMicrophoneRouteBoundaryHub.subscribe { observed += it }
        val tracker = PhoneMicrophoneRouteTracker()

        tracker.reset(routeA)
        val changed = tracker.observe(routeB)

        assertTrue(changed.changed)
        assertEquals(1L, changed.generation)
        assertEquals(1, observed.size)
        assertEquals(PhoneMicrophoneRouteBoundaryReason.ROUTE_CHANGED, observed.single().reason)
        assertEquals(routeA, observed.single().oldRoute)
        assertEquals(routeB, observed.single().newRoute)
        assertEquals(1L, observed.single().routeGeneration)
        subscription.close()
    }

    @Test
    fun `auto route boundary starts discontinuity epoch before new route PCM without silence`() = runBlocking {
        val capture = RouteTransitionFakeCaptureDriver(PhoneMicrophoneInputRequest.Auto)
        val registrations = mutableListOf<PhoneMicrophoneRecordingRegistration>()
        val realtimeFrames = mutableListOf<RealtimeCanonicalPcmFrame>()
        val runtime = runtime(capture, registrations)
        val routeA = PhoneMicrophoneRoute(16, 15, "RMX3041", "bottom")
        val routeB = PhoneMicrophoneRoute(17, 15, "RMX3041", "back")
        val tracker = PhoneMicrophoneRouteTracker()
        tracker.reset(routeA)

        assertTrue(
            runtime.start(postWriteSink = PhoneMicrophoneCanonicalPostWriteSink { realtimeFrames += it })
                is PhoneMicrophoneRecordingStartResult.Started,
        )
        capture.emit(ShortArray(1_280) { 101 })

        tracker.observe(routeB)
        capture.emit(ShortArray(640) { 202 })

        val stopped = runtime.stop()
        assertTrue(stopped is PhoneMicrophoneRecordingStopResult.Saved)
        stopped as PhoneMicrophoneRecordingStopResult.Saved

        assertEquals(1_920L, stopped.sampleCount)
        assertEquals(44L + 1_920L * 2L, stopped.sizeBytes)
        assertEquals(1, registrations.size)
        assertEquals(2, realtimeFrames.size)
        assertEpoch(realtimeFrames[0], epochId = 1L, start = 0L, RealtimeCanonicalEpochStartReason.SESSION_START)
        assertEpoch(realtimeFrames[1], epochId = 2L, start = 0L, RealtimeCanonicalEpochStartReason.DISCONTINUITY)
        assertEquals(realtimeFrames[0].sessionId, realtimeFrames[1].sessionId)
        assertEquals(1L, runtime.state.value.routeBoundaryCount)
        assertEquals(1L, runtime.state.value.discontinuityEpochCount)
        assertEquals(PhoneMicrophoneRouteBoundaryReason.ROUTE_CHANGED, runtime.state.value.lastRouteBoundaryReason)
        assertFalse(runtime.state.value.hadCaptureInterruption)
        assertEquals(2, runtime.state.value.completedEpochs)

        runtime.close()
        capture.close()
    }

    @Test
    fun `auto read failure recreates capture sequentially and keeps same formal wav`() = runBlocking {
        val capture = RouteTransitionFakeCaptureDriver(PhoneMicrophoneInputRequest.Auto)
        val registrations = mutableListOf<PhoneMicrophoneRecordingRegistration>()
        val realtimeFrames = mutableListOf<RealtimeCanonicalPcmFrame>()
        val runtime = runtime(capture, registrations)

        assertTrue(
            runtime.start(postWriteSink = PhoneMicrophoneCanonicalPostWriteSink { realtimeFrames += it })
                is PhoneMicrophoneRecordingStartResult.Started,
        )
        capture.emit(ShortArray(640) { 31 })
        capture.failRead("synthetic AudioRecord read failure")

        withTimeout(2_000L) {
            while (
                capture.startCalls < 2 ||
                runtime.state.value.captureRecoverySuccesses < 1L
            ) {
                delay(5L)
            }
        }
        assertEquals(1, capture.maxConcurrentCaptures)
        capture.emit(ShortArray(640) { 32 })

        val stopped = runtime.stop()
        assertTrue(stopped is PhoneMicrophoneRecordingStopResult.Saved)
        stopped as PhoneMicrophoneRecordingStopResult.Saved
        assertEquals(1_280L, stopped.sampleCount)
        assertEquals(1, registrations.size)
        assertEquals(2, realtimeFrames.size)
        assertEpoch(realtimeFrames[0], 1L, 0L, RealtimeCanonicalEpochStartReason.SESSION_START)
        assertEpoch(realtimeFrames[1], 2L, 0L, RealtimeCanonicalEpochStartReason.DISCONTINUITY)
        assertEquals(realtimeFrames[0].sessionId, realtimeFrames[1].sessionId)
        assertEquals(1L, runtime.state.value.captureRecoveryAttempts)
        assertEquals(1L, runtime.state.value.captureRecoverySuccesses)
        assertEquals(0L, runtime.state.value.captureRecoveryFailures)
        assertEquals(PhoneMicrophoneCaptureRecoveryTrigger.READ_ERROR, runtime.state.value.lastCaptureRecoveryTrigger)
        assertEquals(PhoneMicrophoneRouteBoundaryReason.CAPTURE_RECREATED, runtime.state.value.lastRouteBoundaryReason)
        assertEquals(1L, runtime.state.value.discontinuityEpochCount)
        assertFalse(runtime.state.value.hadCaptureInterruption)

        runtime.close()
        capture.close()
    }

    @Test
    fun `auto route loss stops old capture before recreation and rejects synthetic silence`() = runBlocking {
        val capture = RouteTransitionFakeCaptureDriver(PhoneMicrophoneInputRequest.Auto)
        val registrations = mutableListOf<PhoneMicrophoneRecordingRegistration>()
        val realtimeFrames = mutableListOf<RealtimeCanonicalPcmFrame>()
        val runtime = runtime(capture, registrations)
        val routeA = PhoneMicrophoneRoute(16, 15, "RMX3041", "bottom")
        val tracker = PhoneMicrophoneRouteTracker()
        tracker.reset(routeA)

        assertTrue(
            runtime.start(postWriteSink = PhoneMicrophoneCanonicalPostWriteSink { realtimeFrames += it })
                is PhoneMicrophoneRecordingStartResult.Started,
        )
        capture.emit(ShortArray(640) { 41 })
        tracker.observe(null)

        withTimeout(2_000L) {
            while (
                capture.startCalls < 2 ||
                runtime.state.value.captureRecoverySuccesses < 1L
            ) {
                delay(5L)
            }
        }
        assertEquals(1, capture.maxConcurrentCaptures)
        capture.emit(ShortArray(640) { 42 })

        val stopped = runtime.stop()
        assertTrue(stopped is PhoneMicrophoneRecordingStopResult.Saved)
        stopped as PhoneMicrophoneRecordingStopResult.Saved
        assertEquals(1_280L, stopped.sampleCount)
        assertEquals(2, realtimeFrames.size)
        assertEpoch(realtimeFrames[0], 1L, 0L, RealtimeCanonicalEpochStartReason.SESSION_START)
        assertEpoch(realtimeFrames[1], 2L, 0L, RealtimeCanonicalEpochStartReason.DISCONTINUITY)
        assertEquals(1L, runtime.state.value.captureRecoveryAttempts)
        assertEquals(1L, runtime.state.value.captureRecoverySuccesses)
        assertEquals(PhoneMicrophoneCaptureRecoveryTrigger.ROUTE_LOST, runtime.state.value.lastCaptureRecoveryTrigger)
        assertEquals(2L, runtime.state.value.routeBoundaryCount)
        assertEquals(PhoneMicrophoneRouteBoundaryReason.CAPTURE_RECREATED, runtime.state.value.lastRouteBoundaryReason)
        assertFalse(runtime.state.value.hadCaptureInterruption)

        runtime.close()
        capture.close()
    }

    @Test
    fun `manual input reroute pauses instead of silently falling back`() = runBlocking {
        val capture = RouteTransitionFakeCaptureDriver(PhoneMicrophoneInputRequest.SpecificDevice(17))
        val registrations = mutableListOf<PhoneMicrophoneRecordingRegistration>()
        val realtimeFrames = mutableListOf<RealtimeCanonicalPcmFrame>()
        val runtime = runtime(capture, registrations)
        val routeA = PhoneMicrophoneRoute(17, 15, "RMX3041", "back")
        val routeB = PhoneMicrophoneRoute(16, 15, "RMX3041", "bottom")
        val tracker = PhoneMicrophoneRouteTracker()
        tracker.reset(routeA)

        assertTrue(
            runtime.start(postWriteSink = PhoneMicrophoneCanonicalPostWriteSink { realtimeFrames += it })
                is PhoneMicrophoneRecordingStartResult.Started,
        )
        capture.emit(ShortArray(640) { 11 })
        tracker.observe(routeB)

        withTimeout(2_000L) {
            while (runtime.state.value.phase != PhoneMicrophoneRecordingPhase.PAUSED) {
                delay(5L)
            }
        }

        val paused = runtime.state.value
        assertEquals(1, realtimeFrames.size)
        assertEquals(640L, paused.writtenSamples)
        assertEquals(0L, paused.routeBoundaryCount)
        assertEquals(0L, paused.discontinuityEpochCount)
        assertEquals(0L, paused.captureRecoveryAttempts)
        assertEquals(1L, paused.manualRouteLossPauses)
        assertTrue(paused.manualRouteRecoveryPending)
        assertEquals(17, paused.lastManualRouteLostDeviceId)
        assertFalse(paused.hadCaptureInterruption)

        val stopped = runtime.stop()
        assertTrue(stopped is PhoneMicrophoneRecordingStopResult.Saved)
        stopped as PhoneMicrophoneRecordingStopResult.Saved
        assertEquals(640L, stopped.sampleCount)
        assertEquals(PhoneMicrophoneRecordingPhase.COMPLETED, runtime.state.value.phase)
        assertFalse(runtime.state.value.manualRouteRecoveryPending)
        assertEquals(0L, runtime.state.value.captureRecoveryAttempts)

        runtime.close()
        capture.close()
    }

    @Test
    fun `auto recovery policy never hides client silencing consumer failure or manual read error`() {
        val manualReadFailure =
            PhoneMicrophoneCaptureSnapshot(
                phase = PhoneMicrophoneCapturePhase.FAILED,
                requestedInput = PhoneMicrophoneInputRequest.SpecificDevice(17),
                terminalReason = PhoneMicrophoneCaptureTerminalReason.READ_ERROR,
            )
        val silenced =
            PhoneMicrophoneCaptureSnapshot(
                phase = PhoneMicrophoneCapturePhase.FAILED,
                requestedInput = PhoneMicrophoneInputRequest.Auto,
                terminalReason = PhoneMicrophoneCaptureTerminalReason.CLIENT_SILENCED,
            )
        val consumerFailure =
            PhoneMicrophoneCaptureSnapshot(
                phase = PhoneMicrophoneCapturePhase.FAILED,
                requestedInput = PhoneMicrophoneInputRequest.Auto,
                terminalReason = PhoneMicrophoneCaptureTerminalReason.CONSUMER_ERROR,
            )

        assertEquals(null, PhoneMicrophoneCaptureAutoRecoveryPolicy.triggerFor(manualReadFailure))
        assertEquals(null, PhoneMicrophoneCaptureAutoRecoveryPolicy.triggerFor(silenced))
        assertEquals(null, PhoneMicrophoneCaptureAutoRecoveryPolicy.triggerFor(consumerFailure))
        assertFalse(
            PhoneMicrophoneCaptureAutoRecoveryPolicy.allowsRouteLossRecovery(
                PhoneMicrophoneInputRequest.SpecificDevice(17),
            ),
        )
    }

    private fun assertEpoch(
        frame: RealtimeCanonicalPcmFrame,
        epochId: Long,
        start: Long,
        reason: RealtimeCanonicalEpochStartReason?,
    ) {
        assertEquals(epochId, frame.epochId)
        assertEquals(start, frame.startSampleIndex)
        assertEquals(reason, frame.epochStartReason)
    }

    private fun runtime(
        capture: RouteTransitionFakeCaptureDriver,
        registrations: MutableList<PhoneMicrophoneRecordingRegistration>,
    ): PhoneMicrophoneRecordingRuntime =
        PhoneMicrophoneRecordingRuntime(
            capture = capture,
            recordingsRoot = root,
            registerRecording = { registration ->
                registrations += registration
                registration.recordingId
            },
            nowInstant = { Instant.parse("2026-10-17T00:00:00Z") },
            zoneId = { ZoneId.of("Asia/Shanghai") },
        )
}

private class RouteTransitionFakeCaptureDriver(
    private val request: PhoneMicrophoneInputRequest,
) : PhoneMicrophoneCaptureDriver {
    private val mutableState = MutableStateFlow(PhoneMicrophoneCaptureSnapshot())
    override val state: StateFlow<PhoneMicrophoneCaptureSnapshot> = mutableState
    private var consumer: PhoneMicrophonePcmConsumer? = null
    private var nextSampleIndex = 0L
    private var activeCaptures = 0

    var startCalls = 0
        private set
    var maxConcurrentCaptures = 0
        private set

    override fun start(
        consumer: PhoneMicrophonePcmConsumer,
        foregroundLabel: String,
    ): PhoneMicrophoneStartResult {
        startCalls += 1
        activeCaptures += 1
        maxConcurrentCaptures = maxOf(maxConcurrentCaptures, activeCaptures)
        check(activeCaptures == 1) { "fake detected overlapping capture owners" }
        this.consumer = consumer
        nextSampleIndex = 0L
        mutableState.value =
            PhoneMicrophoneCaptureSnapshot(
                phase = PhoneMicrophoneCapturePhase.RECORDING,
                requestedInput = request,
                selectedAudioSource = PhoneMicrophoneAudioSource.MIC,
            )
        return PhoneMicrophoneStartResult.Started(
            audioSource = PhoneMicrophoneAudioSource.MIC,
            minBufferBytes = 2_560,
            effectiveBufferBytes = 5_120,
        )
    }

    fun emit(samples: ShortArray) {
        val start = nextSampleIndex
        checkNotNull(consumer).onPcm(PhoneMicrophonePcmFrame(start, samples))
        nextSampleIndex += samples.size
    }

    fun failRead(message: String) {
        consumer = null
        activeCaptures = 0
        mutableState.value =
            PhoneMicrophoneCaptureSnapshot(
                phase = PhoneMicrophoneCapturePhase.FAILED,
                requestedInput = request,
                selectedAudioSource = PhoneMicrophoneAudioSource.MIC,
                terminalReason = PhoneMicrophoneCaptureTerminalReason.READ_ERROR,
                lastFailure = message,
            )
    }

    override suspend fun stopAndAwait(timeoutMs: Long): Boolean {
        consumer = null
        activeCaptures = 0
        mutableState.value =
            mutableState.value.copy(
                phase = PhoneMicrophoneCapturePhase.STOPPED,
                requestedInput = request,
            )
        return true
    }

    override fun close() {
        consumer = null
        activeCaptures = 0
        mutableState.value = mutableState.value.copy(phase = PhoneMicrophoneCapturePhase.CLOSED)
    }
}
