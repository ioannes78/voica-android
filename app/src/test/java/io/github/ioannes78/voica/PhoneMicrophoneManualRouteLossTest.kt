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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PhoneMicrophoneManualRouteLossTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        PhoneMicrophoneRouteBoundaryHub.clearForTest()
        root = Files.createTempDirectory("phone-manual-route-loss").toFile()
    }

    @After
    fun tearDown() {
        PhoneMicrophoneRouteBoundaryHub.clearForTest()
        root.deleteRecursively()
    }

    @Test
    fun `manual route mismatch pauses without accepting replacement route and resumes as discontinuity`() = runBlocking {
        val routeA = PhoneMicrophoneRoute(17, 3, "Wired headset")
        val routeB = PhoneMicrophoneRoute(16, 15, "Phone", "bottom")
        val capture = ManualRouteLossFakeCaptureDriver(PhoneMicrophoneInputRequest.SpecificDevice(17), routeA)
        val registrations = mutableListOf<PhoneMicrophoneRecordingRegistration>()
        val frames = mutableListOf<RealtimeCanonicalPcmFrame>()
        val runtime = runtime(capture, registrations)
        val tracker = PhoneMicrophoneRouteTracker()
        tracker.reset(routeA)

        assertTrue(
            runtime.start(postWriteSink = PhoneMicrophoneCanonicalPostWriteSink { frames += it })
                is PhoneMicrophoneRecordingStartResult.Started,
        )
        capture.emit(ShortArray(640) { 111 })

        // This boundary is synchronous and occurs before PhoneMicrophoneCapture would deliver any
        // PCM from routeB. The formal runtime must pause instead of accepting a silent fallback.
        tracker.observe(routeB)
        withTimeout(2_000L) {
            while (runtime.state.value.phase != PhoneMicrophoneRecordingPhase.PAUSED) delay(5L)
        }

        val paused = runtime.state.value
        assertEquals(640L, paused.writtenSamples)
        assertEquals(1L, paused.manualRouteLossPauses)
        assertTrue(paused.manualRouteRecoveryPending)
        assertEquals(17, paused.lastManualRouteLostDeviceId)
        assertEquals(0L, paused.captureRecoveryAttempts)
        assertEquals(0L, paused.discontinuityEpochCount)
        assertFalse(paused.hadCaptureInterruption)
        assertEquals(1, frames.size)
        assertEquals(1, capture.maxConcurrentCaptures)

        // User explicitly chooses AUTO while paused. Only now may a new capture owner start.
        capture.request = PhoneMicrophoneInputRequest.Auto
        capture.startRoute = routeB
        assertEquals(PhoneMicrophoneRecordingActionResult.Success, runtime.resume())
        capture.emit(ShortArray(640) { 222 })

        val stopped = runtime.stop()
        assertTrue(stopped is PhoneMicrophoneRecordingStopResult.Saved)
        stopped as PhoneMicrophoneRecordingStopResult.Saved
        assertEquals(1_280L, stopped.sampleCount)
        assertEquals(1, registrations.size)
        assertEquals(2, frames.size)
        assertEquals(frames[0].sessionId, frames[1].sessionId)
        assertEpoch(frames[0], 1L, RealtimeCanonicalEpochStartReason.SESSION_START)
        assertEpoch(frames[1], 2L, RealtimeCanonicalEpochStartReason.DISCONTINUITY)
        assertEquals(1L, runtime.state.value.manualRouteLossPauses)
        assertFalse(runtime.state.value.manualRouteRecoveryPending)
        assertEquals(1L, runtime.state.value.discontinuityEpochCount)
        assertEquals(0L, runtime.state.value.captureRecoveryAttempts)
        assertFalse(runtime.state.value.hadCaptureInterruption)
        assertEquals(2, capture.startCalls)
        assertEquals(1, capture.maxConcurrentCaptures)

        runtime.close()
        capture.close()
    }

    @Test
    fun `stop from manual route loss pause saves accepted pcm without abnormal interruption`() = runBlocking {
        val routeA = PhoneMicrophoneRoute(17, 3, "Wired headset")
        val routeB = PhoneMicrophoneRoute(16, 15, "Phone", "bottom")
        val capture = ManualRouteLossFakeCaptureDriver(PhoneMicrophoneInputRequest.SpecificDevice(17), routeA)
        val registrations = mutableListOf<PhoneMicrophoneRecordingRegistration>()
        val runtime = runtime(capture, registrations)
        val tracker = PhoneMicrophoneRouteTracker()
        tracker.reset(routeA)

        assertTrue(runtime.start() is PhoneMicrophoneRecordingStartResult.Started)
        capture.emit(ShortArray(640) { 333 })
        tracker.observe(routeB)
        withTimeout(2_000L) {
            while (runtime.state.value.phase != PhoneMicrophoneRecordingPhase.PAUSED) delay(5L)
        }

        val stopped = runtime.stop()
        assertTrue(stopped is PhoneMicrophoneRecordingStopResult.Saved)
        stopped as PhoneMicrophoneRecordingStopResult.Saved
        assertEquals(640L, stopped.sampleCount)
        assertEquals(PhoneMicrophoneRecordingPhase.COMPLETED, runtime.state.value.phase)
        assertEquals(1L, runtime.state.value.manualRouteLossPauses)
        assertFalse(runtime.state.value.manualRouteRecoveryPending)
        assertFalse(runtime.state.value.hadCaptureInterruption)
        assertEquals(1, registrations.size)

        runtime.close()
        capture.close()
    }

    @Test
    fun `manual route loss policy does not classify unrelated read failure on still selected route`() {
        val route = PhoneMicrophoneRoute(17, 3, "Wired headset")
        val readFailureOnSelectedRoute =
            PhoneMicrophoneCaptureSnapshot(
                phase = PhoneMicrophoneCapturePhase.FAILED,
                requestedInput = PhoneMicrophoneInputRequest.SpecificDevice(17),
                route = route,
                terminalReason = PhoneMicrophoneCaptureTerminalReason.READ_ERROR,
            )

        assertNull(
            PhoneMicrophoneManualRouteLossPolicy.lostSelectedDeviceId(
                readFailureOnSelectedRoute,
                selectedDeviceMissing = false,
            ),
        )
        assertEquals(
            17,
            PhoneMicrophoneManualRouteLossPolicy.lostSelectedDeviceId(
                readFailureOnSelectedRoute.copy(route = null),
                selectedDeviceMissing = false,
            ),
        )
        assertEquals(
            17,
            PhoneMicrophoneManualRouteLossPolicy.lostSelectedDeviceId(
                readFailureOnSelectedRoute,
                selectedDeviceMissing = true,
            ),
        )
    }

    private fun assertEpoch(
        frame: RealtimeCanonicalPcmFrame,
        epochId: Long,
        reason: RealtimeCanonicalEpochStartReason,
    ) {
        assertEquals(epochId, frame.epochId)
        assertEquals(0L, frame.startSampleIndex)
        assertEquals(reason, frame.epochStartReason)
    }

    private fun runtime(
        capture: ManualRouteLossFakeCaptureDriver,
        registrations: MutableList<PhoneMicrophoneRecordingRegistration>,
    ): PhoneMicrophoneRecordingRuntime =
        PhoneMicrophoneRecordingRuntime(
            capture = capture,
            recordingsRoot = root,
            registerRecording = { registration ->
                registrations += registration
                registration.recordingId
            },
            nowInstant = { Instant.parse("2026-10-17T02:00:00Z") },
            zoneId = { ZoneId.of("Asia/Shanghai") },
        )
}

private class ManualRouteLossFakeCaptureDriver(
    var request: PhoneMicrophoneInputRequest,
    var startRoute: PhoneMicrophoneRoute?,
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
                route = startRoute,
            )
        return PhoneMicrophoneStartResult.Started(
            audioSource = PhoneMicrophoneAudioSource.MIC,
            minBufferBytes = 2_560,
            effectiveBufferBytes = 5_120,
        )
    }

    fun emit(samples: ShortArray) {
        check(activeCaptures == 1)
        val start = nextSampleIndex
        checkNotNull(consumer).onPcm(PhoneMicrophonePcmFrame(start, samples))
        nextSampleIndex += samples.size
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
