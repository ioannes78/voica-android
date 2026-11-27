package io.github.ioannes78.voica.audio

import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class VerifiedEvidencePlaybackSourceTest {
    private val bytes = ByteArray(244) { it.toByte() }
    private val expected = EvidencePlaybackExpectation("r", "asset", hash(bytes),
        CanonicalPcmProfile.PROFILE_ID, 100, 10, 20)
    private val descriptor = PlaybackAudioSourceDescriptor("r", "asset", AudioContainerKind.WAV,
        null, 16000, 1, true, 244, 16, 44, 200, 2, 100)

    private fun hash(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data)
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    private class Handle(private val data: ByteArray, private val maxRead: Int = Int.MAX_VALUE,
        private val truncate: Boolean = false, private val cancelled: Boolean = false) : SeekableAudioHandle {
        override val lengthBytes = data.size.toLong()
        var reads = 0
        var closed = false
        override fun readAt(offset: Long, target: ByteArray, targetOffset: Int, length: Int): Int {
            reads++
            if (cancelled) throw CancellationException("fixture")
            if (truncate || offset >= data.size) return -1
            val count = minOf(length, maxRead, data.size - offset.toInt())
            data.copyInto(target, targetOffset, offset.toInt(), offset.toInt() + count)
            return count
        }
        override fun close() { closed = true }
    }

    @Test fun exactOpenedHandleWithShortReadsIsVerified() = runBlocking {
        val handle = Handle(bytes, maxRead = 7)
        assertEquals(EvidencePlaybackVerification.VERIFIED,
            VerifiedEvidencePlaybackSource.verify(PlaybackAudioSource(descriptor, handle), expected))
        assertTrue(handle.reads > 1)
        assertFalse(handle.closed)
    }

    @Test fun wrongRecordingAssetOrDurationRejectedBeforeHashing() = runBlocking {
        for (d in listOf(descriptor.copy(recordingId = "other"), descriptor.copy(assetId = "other"),
            descriptor.copy(totalSampleCount = 99))) {
            val handle = Handle(bytes)
            assertEquals(EvidencePlaybackVerification.IDENTITY_MISMATCH,
                VerifiedEvidencePlaybackSource.verify(PlaybackAudioSource(d, handle), expected))
            assertEquals(0, handle.reads)
        }
    }

    @Test fun nonCanonicalAndInvalidByteBoundsRejected() = runBlocking {
        for (d in listOf(descriptor.copy(sampleRateHz = 8000), descriptor.copy(channelCount = 2),
            descriptor.copy(pcmDataOffsetBytes = 245), descriptor.copy(pcmDataSizeBytes = 199),
            descriptor.copy(lengthBytes = 245), descriptor.copy(container = AudioContainerKind.MP3))) {
            assertEquals(EvidencePlaybackVerification.FORMAT_MISMATCH,
                VerifiedEvidencePlaybackSource.verify(PlaybackAudioSource(d, Handle(bytes)), expected))
        }
    }

    @Test fun wrongBytesAndPrematureEofAreRejected() = runBlocking {
        val changed = bytes.copyOf().also { it[100] = 0 }
        for (handle in listOf(Handle(changed), Handle(bytes, truncate = true))) {
            assertEquals(EvidencePlaybackVerification.CONTENT_MISMATCH,
                VerifiedEvidencePlaybackSource.verify(PlaybackAudioSource(descriptor, handle), expected))
        }
    }

    @Test fun cancellationIsNotConvertedToVerificationSuccess() = runBlocking {
        val handle = Handle(bytes, cancelled = true)
        assertTrue(runCatching {
            VerifiedEvidencePlaybackSource.verify(PlaybackAudioSource(descriptor, handle), expected)
        }.exceptionOrNull() is CancellationException)
        assertFalse(handle.closed)
    }

    @Test fun sampleRangeCannotBeEmptyNegativeOrPastEnd() {
        assertTrue(runCatching { expected.copy(startSampleIndex = -1) }.isFailure)
        assertTrue(runCatching { expected.copy(endSampleIndexExclusive = 10) }.isFailure)
        assertTrue(runCatching { expected.copy(endSampleIndexExclusive = 101) }.isFailure)
    }
}
