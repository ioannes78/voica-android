package io.github.ioannes78.voica

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

class PhoneMicrophoneRecordingRuntimeTest {
    private lateinit var root: File
    private lateinit var capture: FakePhoneMicrophoneCaptureDriver
    private val registrations = mutableListOf<PhoneMicrophoneRecordingRegistration>()

    @Before
    fun setUp() {
        root = Files.createTempDirectory("phone-mic-recording-runtime").toFile()
        capture = FakePhoneMicrophoneCaptureDriver()
        registrations.clear()
    }

    @After
    fun tearDown() {
        capture.close()
        root.deleteRecursively()
    }

    @Test
    fun `pause resume creates control-boundary epoch without inserting silence`() = runBlocking {
        val runtime = runtime()

        val started = runtime.start()
        assertTrue(started is PhoneMicrophoneRecordingStartResult.Started)
        capture.emit(ShortArray(1_280) { 100 })

        assertEquals(PhoneMicrophoneRecordingActionResult.Success, runtime.pause())
        assertEquals(PhoneMicrophoneRecordingPhase.PAUSED, runtime.state.value.phase)
        assertEquals(80L, runtime.state.value.durationMs)

        assertEquals(PhoneMicrophoneRecordingActionResult.Success, runtime.resume())
        capture.emit(ShortArray(640) { -100 })

        val stopped = runtime.stop()
        assertTrue(stopped is PhoneMicrophoneRecordingStopResult.Saved)
        stopped as PhoneMicrophoneRecordingStopResult.Saved

        assertEquals(1_920L, stopped.sampleCount)
        assertEquals(44L + 1_920L * 2L, stopped.sizeBytes)
        assertEquals(2, runtime.state.value.completedEpochs)
        assertEquals(120L, runtime.state.value.durationMs)
        assertEquals(2, capture.startCalls)
        assertEquals(1, registrations.size)
        assertEquals(120L, registrations.single().mediaDurationMs)
        assertEquals(stopped.sizeBytes, File(root, registrations.single().relativePath).length())
        assertRiffDataSize(File(root, registrations.single().relativePath), 1_920 * 2)

        runtime.close()
    }

    @Test
    fun `post-write realtime failure cannot roll back formal recording truth`() = runBlocking {
        val runtime = runtime()
        val rejectingRealtime =
            PhoneMicrophoneCanonicalPostWriteSink {
                error("synthetic realtime failure")
            }

        assertTrue(runtime.start(postWriteSink = rejectingRealtime) is PhoneMicrophoneRecordingStartResult.Started)
        capture.emit(ShortArray(1_280) { 7 })
        val stopped = runtime.stop()

        assertTrue(stopped is PhoneMicrophoneRecordingStopResult.Saved)
        stopped as PhoneMicrophoneRecordingStopResult.Saved
        assertEquals(1_280L, stopped.sampleCount)
        assertEquals(1_280L, runtime.state.value.writtenSamples)
        assertEquals(1L, runtime.state.value.postWriteOffers)
        assertEquals(1L, runtime.state.value.postWriteOfferFailures)
        assertTrue(runtime.state.value.lastPostWriteFailure?.contains("synthetic realtime failure") == true)
        assertEquals(1, registrations.size)
        assertTrue(stopped.file.isFile)
        assertEquals(44L + 1_280L * 2L, stopped.file.length())

        runtime.close()
    }

    @Test
    fun `capture failure saves usable media but never reports normal completion`() = runBlocking {
        val runtime = runtime()
        assertTrue(runtime.start() is PhoneMicrophoneRecordingStartResult.Started)
        capture.emit(ShortArray(1_280) { 33 })
        capture.fail("synthetic microphone interruption")

        val stopped = runtime.stop()

        assertTrue(stopped is PhoneMicrophoneRecordingStopResult.Saved)
        stopped as PhoneMicrophoneRecordingStopResult.Saved
        assertEquals(1_280L, stopped.sampleCount)
        assertEquals(PhoneMicrophoneRecordingPhase.FAILED, runtime.state.value.phase)
        assertTrue(runtime.state.value.finalizedSha256?.isNotBlank() == true)
        assertTrue(runtime.state.value.lastFailure?.contains("异常中断") == true)
        assertTrue(runtime.state.value.lastFailure?.contains("synthetic microphone interruption") == true)
        assertEquals(1, registrations.size)
        assertEquals(
            "手机录音（异常中断） 2026-10-15 08:00:00",
            registrations.single().displayName,
        )
        assertTrue(stopped.file.isFile)
        assertEquals(44L + 1_280L * 2L, stopped.file.length())

        runtime.close()
    }

    @Test
    fun `stop before first PCM sample does not create empty library recording`() = runBlocking {
        val runtime = runtime()
        assertTrue(runtime.start() is PhoneMicrophoneRecordingStartResult.Started)

        val stopped = runtime.stop()

        assertEquals(PhoneMicrophoneRecordingStopResult.Empty, stopped)
        assertEquals(PhoneMicrophoneRecordingPhase.EMPTY, runtime.state.value.phase)
        assertTrue(registrations.isEmpty())
        assertFalse(root.walkTopDown().any { it.isFile && it.extension == "wav" })
        assertFalse(root.walkTopDown().any { it.isFile && it.name.endsWith(".part") })

        runtime.close()
    }

    @Test
    fun `second recording starts cleanly after first completed recording`() = runBlocking {
        val runtime = runtime()

        assertTrue(runtime.start() is PhoneMicrophoneRecordingStartResult.Started)
        capture.emit(ShortArray(320) { 1 })
        assertTrue(runtime.stop() is PhoneMicrophoneRecordingStopResult.Saved)

        assertTrue(runtime.start() is PhoneMicrophoneRecordingStartResult.Started)
        capture.emit(ShortArray(480) { 2 })
        val second = runtime.stop()
        assertTrue(second is PhoneMicrophoneRecordingStopResult.Saved)

        assertEquals(2, registrations.size)
        assertTrue(registrations[0].recordingId != registrations[1].recordingId)
        assertEquals(20L, registrations[0].mediaDurationMs)
        assertEquals(30L, registrations[1].mediaDurationMs)

        runtime.close()
    }

    private fun runtime(): PhoneMicrophoneRecordingRuntime =
        PhoneMicrophoneRecordingRuntime(
            capture = capture,
            recordingsRoot = root,
            registerRecording = { registration ->
                registrations += registration
                registration.recordingId
            },
            nowInstant = { Instant.parse("2026-10-15T00:00:00Z") },
            zoneId = { ZoneId.of("Asia/Shanghai") },
        )

    private fun assertRiffDataSize(file: File, expectedDataBytes: Int) {
        val bytes = file.readBytes()
        assertEquals("RIFF", bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals("WAVE", bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII))
        val actual =
            (bytes[40].toInt() and 0xff) or
                ((bytes[41].toInt() and 0xff) shl 8) or
                ((bytes[42].toInt() and 0xff) shl 16) or
                ((bytes[43].toInt() and 0xff) shl 24)
        assertEquals(expectedDataBytes, actual)
    }
}

private class FakePhoneMicrophoneCaptureDriver : PhoneMicrophoneCaptureDriver {
    private val mutableState = MutableStateFlow(PhoneMicrophoneCaptureSnapshot())
    override val state: StateFlow<PhoneMicrophoneCaptureSnapshot> = mutableState

    var startCalls = 0
        private set
    private var consumer: PhoneMicrophonePcmConsumer? = null
    private var nextSampleIndex = 0L

    override fun start(
        consumer: PhoneMicrophonePcmConsumer,
        foregroundLabel: String,
    ): PhoneMicrophoneStartResult {
        startCalls += 1
        this.consumer = consumer
        nextSampleIndex = 0L
        mutableState.value = PhoneMicrophoneCaptureSnapshot(phase = PhoneMicrophoneCapturePhase.RECORDING)
        return PhoneMicrophoneStartResult.Started(
            audioSource = PhoneMicrophoneAudioSource.VOICE_RECOGNITION,
            minBufferBytes = 2_560,
            effectiveBufferBytes = 5_120,
        )
    }

    fun emit(samples: ShortArray) {
        check(samples.isNotEmpty())
        val start = nextSampleIndex
        checkNotNull(consumer).onPcm(PhoneMicrophonePcmFrame(start, samples))
        nextSampleIndex += samples.size
    }

    fun fail(message: String) {
        consumer = null
        mutableState.value =
            PhoneMicrophoneCaptureSnapshot(
                phase = PhoneMicrophoneCapturePhase.FAILED,
                samplesRead = nextSampleIndex,
                lastFailure = message,
            )
    }

    override suspend fun stopAndAwait(timeoutMs: Long): Boolean {
        consumer = null
        mutableState.value = PhoneMicrophoneCaptureSnapshot(phase = PhoneMicrophoneCapturePhase.STOPPED)
        return true
    }

    override fun close() {
        consumer = null
        mutableState.value = PhoneMicrophoneCaptureSnapshot(phase = PhoneMicrophoneCapturePhase.CLOSED)
    }
}
