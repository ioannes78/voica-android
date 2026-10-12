package io.github.ioannes78.voica.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class StreamingChunkAssemblerTest {
    @Test
    fun `multiple canonical frames assemble one exact chunk`() {
        val assembler = StreamingChunkAssembler(targetChunkSamples = 640)
        assembler.startEpoch(sessionId = 1, epochId = 1)

        assertEquals(0, assembler.accept(frame(start = 0, samples = ShortArray(320) { 1 })).size)
        val chunks = assembler.accept(frame(start = 320, samples = ShortArray(320) { 2 }))

        assertEquals(1, chunks.size)
        val chunk = chunks.single()
        assertEquals(0L, chunk.startSampleIndex)
        assertEquals(640L, chunk.endSampleIndexExclusive)
        assertArrayEquals(ShortArray(640) { if (it < 320) 1 else 2 }, chunk.samples)
        assertEquals(0, assembler.snapshot().bufferedSamples)
    }

    @Test
    fun `large frame emits full chunks and flush keeps exact tail range`() {
        val assembler = StreamingChunkAssembler(targetChunkSamples = 400)
        assembler.startEpoch(sessionId = 2, epochId = 7)
        val samples = ShortArray(1_000) { it.toShort() }

        val chunks = assembler.accept(frame(sessionId = 2, epochId = 7, start = 1_200, samples = samples))
        assertEquals(2, chunks.size)
        assertEquals(1_200L, chunks[0].startSampleIndex)
        assertEquals(1_600L, chunks[1].startSampleIndex)

        val tail = assembler.flush()!!
        assertEquals(2_000L, tail.startSampleIndex)
        assertEquals(2_200L, tail.endSampleIndexExclusive)
        assertEquals(200, tail.sampleCount)
        assertNull(assembler.flush())
    }

    @Test
    fun `cross epoch and sample gap are rejected instead of stitched`() {
        val assembler = StreamingChunkAssembler(targetChunkSamples = 640)
        assembler.startEpoch(sessionId = 1, epochId = 1)
        assembler.accept(frame(start = 0, samples = ShortArray(320)))

        assertThrows(IllegalArgumentException::class.java) {
            assembler.accept(frame(epochId = 2, start = 0, samples = ShortArray(320)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            assembler.accept(frame(start = 640, samples = ShortArray(320)))
        }
    }

    @Test
    fun `flush then reset allows a new epoch without fake continuity`() {
        val assembler = StreamingChunkAssembler(targetChunkSamples = 640)
        assembler.startEpoch(sessionId = 1, epochId = 1)
        assembler.accept(frame(start = 0, samples = ShortArray(320) { 1 }))
        val firstTail = assembler.flush()!!
        assertEquals(0L, firstTail.startSampleIndex)
        assertEquals(320L, firstTail.endSampleIndexExclusive)

        assembler.reset()
        assembler.startEpoch(sessionId = 1, epochId = 2)
        val second = assembler.accept(frame(epochId = 2, start = 0, samples = ShortArray(640) { 2 })).single()
        assertEquals(0L, second.startSampleIndex)
        assertEquals(2L, second.epochId)
    }

    private fun frame(
        sessionId: Long = 1,
        epochId: Long = 1,
        start: Long,
        samples: ShortArray,
    ) = RealtimeCanonicalPcmFrame(
        sessionId = sessionId,
        epochId = epochId,
        startSampleIndex = start,
        samples = samples,
        epochStartReason = if (start == 0L) RealtimeCanonicalEpochStartReason.SESSION_START else null,
    )
}
