package io.github.ioannes78.voica

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage16ARealtimePerformanceDiagnosticsTest {
    @After
    fun tearDown() {
        Stage16ARealtimePerformanceDiagnostics.clearForTest()
    }

    @Test
    fun `diagnostics text exposes p7 realtime counters with stable units`() {
        val text = snapshot(modelId = "small", nativeRtf = 0.123456).toDiagnosticsText()

        assertTrue(text.contains("[Stage 16A 实时性能]"))
        assertTrue(text.contains("model=Model small (small)"))
        assertTrue(text.contains("chunk=1280 samples / 80ms"))
        assertTrue(text.contains("canonicalAudio=12.500s"))
        assertTrue(text.contains("nativeRTF=0.1235"))
        assertTrue(text.contains("buffer=40.0ms peak=120.0ms overflow=2 droppedFrames=3 droppedSamples=640"))
        assertTrue(text.contains("outputSink offers=20 rejects=1 failures=2"))
        assertTrue(text.contains("sideSink offers=30 rejects=4 failures=5"))
        assertTrue(text.contains("maxDecode=7.5ms"))
        assertTrue(text.contains("terminalReason=STOP"))
    }

    @Test
    fun `active provider is read live and retained snapshot remains after detach`() {
        val owner = Any()
        var current = snapshot(modelId = "first")
        Stage16ARealtimePerformanceDiagnostics.attach(owner) { current }

        assertEquals("first", Stage16ARealtimePerformanceDiagnostics.snapshot()?.modelId)
        current = snapshot(modelId = "updated")
        assertEquals("updated", Stage16ARealtimePerformanceDiagnostics.snapshot()?.modelId)

        val retained = snapshot(modelId = "retained")
        Stage16ARealtimePerformanceDiagnostics.retainAndDetach(owner, retained)
        assertEquals("retained", Stage16ARealtimePerformanceDiagnostics.snapshot()?.modelId)
    }

    @Test
    fun `stale owner cannot replace newer run diagnostics`() {
        val oldOwner = Any()
        val newOwner = Any()
        Stage16ARealtimePerformanceDiagnostics.attach(oldOwner) { snapshot(modelId = "old") }
        Stage16ARealtimePerformanceDiagnostics.attach(newOwner) { snapshot(modelId = "new") }

        Stage16ARealtimePerformanceDiagnostics.retainAndDetach(
            oldOwner,
            snapshot(modelId = "stale-retained"),
        )

        assertEquals("new", Stage16ARealtimePerformanceDiagnostics.snapshot()?.modelId)
        Stage16ARealtimePerformanceDiagnostics.retainAndDetach(
            newOwner,
            snapshot(modelId = "new-retained"),
        )
        assertEquals("new-retained", Stage16ARealtimePerformanceDiagnostics.snapshot()?.modelId)
    }

    @Test
    fun `provider failure falls back to retained snapshot only after matching detach`() {
        val owner = Any()
        Stage16ARealtimePerformanceDiagnostics.attach(owner) { error("boom") }
        assertNull(Stage16ARealtimePerformanceDiagnostics.snapshot())

        val retained = snapshot(modelId = "after-failure")
        Stage16ARealtimePerformanceDiagnostics.retainAndDetach(owner, retained)
        val result = Stage16ARealtimePerformanceDiagnostics.snapshot()
        assertNotNull(result)
        assertEquals("after-failure", result?.modelId)
        assertFalse(result?.toDiagnosticsText().orEmpty().contains("boom"))
    }

    private fun snapshot(
        modelId: String,
        nativeRtf: Double? = 0.5,
    ) = Stage16ARealtimePerformanceDiagnosticsSnapshot(
        modelId = modelId,
        modelLabel = "Model $modelId",
        effectiveThreads = 4,
        targetChunkSamples = 1_280,
        targetChunkDurationMs = 80,
        canonicalAudioSeconds = 12.5,
        nativeRtf = nativeRtf,
        currentBufferDepthMs = 40.0,
        peakBufferDepthMs = 120.0,
        bufferOverflowEvents = 2,
        bufferDroppedFrames = 3,
        bufferDroppedSamples = 640,
        bufferUpstreamDiscontinuities = 1,
        bufferRejectedAfterFinish = 6,
        bufferRejectedOversizedFrames = 7,
        processedEvents = 100,
        processedPcmFrames = 90,
        processedPcmSamples = 200_000,
        emittedOutputs = 10,
        outputSinkOffers = 20,
        outputSinkRejects = 1,
        outputSinkFailures = 2,
        pauseCommits = 1,
        consumedSourceEvents = 110,
        consumedPcmFrames = 95,
        consumedPcmSamples = 201_000,
        consumedUpstreamDiscontinuities = 2,
        sideSinkOffers = 30,
        sideSinkRejects = 4,
        sideSinkFailures = 5,
        runnerState = "TERMINATED",
        terminalReason = "STOP",
        lastFailure = null,
        asrAcceptCalls = 50,
        asrDecodeCalls = 25,
        asrDecodeMs = 50.0,
        maxAsrDecodeMs = 7.5,
        asrFinishCalls = 4,
        asrFinishMs = 10.0,
        maxAsrFinishMs = 3.5,
        vadAcceptCalls = 50,
        vadFinishCalls = 4,
        vadFinishMs = 8.0,
        maxVadFinishMs = 2.5,
        punctuationCalls = 3,
        punctuationMs = 6.0,
        maxPunctuationMs = 2.2,
    )
}
