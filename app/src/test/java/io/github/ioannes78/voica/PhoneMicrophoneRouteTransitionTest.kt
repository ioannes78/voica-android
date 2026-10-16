package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.RealtimeCanonicalEpochStartReason
import io.github.ioannes78.voica.audio.RealtimeCanonicalPcmFrame
import io.github.ioannes78.voica.database.PhoneMicrophoneRecordingRegistration
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
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
        assertEquals(1L, realtimeFrames[0].epochId)
        assertEquals(0L, realtimeFrames[0].startSampleIndex)
        assertEquals(RealtimeCanonicalEpochStartReason.SESSION_START, realtimeFrames[0].epochStartReason)
        assertEquals(2L, realtimeFrames[1].epochId)
        assertEquals(0L, realtimeFrames[1].startSampleIndex)
        assertEquals(RealtimeCanonicalEpochStartReason.DISCONTINUITY, realtimeFrames[1].epochStartReason)
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
    fun `manual input does not convert platform reroute into auto discontinuity`() = runBlocking {
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
        capture.emit(ShortArray(640) { 22 })
        val stopped = runtime.stop()

        assertTrue(stopped is PhoneMicrophoneRecordingStopResult.Saved)
        assertEquals(2, realtimeFrames.size)
        assertEquals(1L, realtimeFrames[0].epochId)
        assertEquals(1L, realtimeFrames[1].epochId)
        assertEquals(640L, realtimeFrames[1].startSampleIndex)
        assertEquals(null, realtimeFrames[1].epochStartReason)
        assertEquals(0L, runtime.state.value.routeBoundaryCount)
        assertEquals(0L, runtime.state.value.discontinuityEpochCount)

        runtime.close()
        capture.close()
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

    override fun start(
        consumer: PhoneMicrophonePcmConsumer,
        foregroundLabel: String,
    ): PhoneMicrophoneStartResult {
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

    override suspend fun stopAndAwait(timeoutMs: Long): Boolean {
        consumer = null
        mutableState.value = mutableState.value.copy(phase = PhoneMicrophoneCapturePhase.STOPPED)
        return true
    }

    override fun close() {
        consumer = null
        mutableState.value = mutableState.value.copy(phase = PhoneMicrophoneCapturePhase.CLOSED)
    }
}
