package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMicrophoneFoundationTest {
    @Test
    fun `buffer policy keeps canonical alignment and headroom`() {
        val minBufferBytes = 3_001
        val effective = PhoneMicrophoneBufferPolicy.effectiveBufferBytes(minBufferBytes)

        assertTrue(effective >= minBufferBytes * 2)
        assertTrue(
            effective >=
                PhoneMicrophoneBufferPolicy.READ_CHUNK_SAMPLES *
                CanonicalPcmProfile.BYTES_PER_SAMPLE * 2,
        )
        assertEquals(0, effective % CanonicalPcmProfile.BYTES_PER_SAMPLE)
    }

    @Test
    fun `permission policy distinguishes first request rationale and permanent denial`() {
        assertEquals(
            PhoneMicrophonePermissionAction.REQUEST_PERMISSION,
            PhoneMicrophonePermissionPolicy.resolve(
                granted = false,
                requestedBefore = false,
                shouldShowRationale = false,
            ).action,
        )
        assertEquals(
            PhoneMicrophonePermissionAction.SHOW_RATIONALE_AND_REQUEST,
            PhoneMicrophonePermissionPolicy.resolve(
                granted = false,
                requestedBefore = true,
                shouldShowRationale = true,
            ).action,
        )
        assertEquals(
            PhoneMicrophonePermissionAction.OPEN_APP_SETTINGS,
            PhoneMicrophonePermissionPolicy.resolve(
                granted = false,
                requestedBefore = true,
                shouldShowRationale = false,
            ).action,
        )
        assertEquals(
            PhoneMicrophonePermissionAction.READY,
            PhoneMicrophonePermissionPolicy.resolve(
                granted = true,
                requestedBefore = true,
                shouldShowRationale = false,
            ).action,
        )
    }

    @Test
    fun `execution lease requires foreground but wake lock failure is diagnostic only`() {
        PhoneMicrophoneExecutionLeaseDiagnostics.clearForTest()
        val foreground = FakeForegroundLease(acquireResult = true)
        val wakeLock = FakeWakeLock(failAcquire = true)
        val lease = PhoneMicrophoneExecutionLease(foreground, wakeLock)

        assertTrue(lease.acquire("手机录音"))
        val acquired = lease.snapshot()
        assertTrue(acquired.foregroundRequested)
        assertFalse(acquired.wakeLockHeld)
        assertEquals(1, acquired.foregroundAcquireSuccesses)
        assertEquals(0, acquired.wakeLockAcquireSuccesses)
        assertTrue(acquired.lastFailure?.contains("wake acquire failed") == true)

        lease.release()
        val released = lease.snapshot()
        assertFalse(released.foregroundRequested)
        assertEquals(1, foreground.releaseCalls)
    }

    @Test
    fun `execution lease rejects capture when microphone foreground request fails`() {
        val foreground = FakeForegroundLease(acquireResult = false)
        val wakeLock = FakeWakeLock()
        val lease = PhoneMicrophoneExecutionLease(foreground, wakeLock)

        assertFalse(lease.acquire("手机录音"))
        assertFalse(lease.snapshot().foregroundRequested)
        assertEquals(0, wakeLock.acquireCalls)
    }

    @Test
    fun `pcm frame uses contiguous canonical sample coordinates`() {
        val frame =
            PhoneMicrophonePcmFrame(
                startSampleIndex = 1_280L,
                samples = ShortArray(640),
            )

        assertEquals(1_920L, frame.endSampleIndexExclusive)
    }

    @Test
    fun `platform silenced PCM never reaches recording consumer`() {
        var consumerCalls = 0
        var acceptedSamples = 0
        val consumer =
            PhoneMicrophonePcmConsumer { frame ->
                consumerCalls += 1
                acceptedSamples += frame.samples.size
            }
        val frame =
            PhoneMicrophonePcmFrame(
                startSampleIndex = 0L,
                samples = ShortArray(1_280),
            )

        assertFalse(
            deliverPhoneMicrophonePcmIfAudible(
                clientSilenced = true,
                frame = frame,
                consumer = consumer,
            ),
        )
        assertEquals(0, consumerCalls)
        assertEquals(0, acceptedSamples)

        assertTrue(
            deliverPhoneMicrophonePcmIfAudible(
                clientSilenced = false,
                frame = frame,
                consumer = consumer,
            ),
        )
        assertEquals(1, consumerCalls)
        assertEquals(1_280, acceptedSamples)
    }
}

private class FakeForegroundLease(
    private val acquireResult: Boolean,
) : PhoneMicrophoneForegroundLeaseHandle {
    var acquireCalls = 0
    var releaseCalls = 0

    override fun acquire(label: String): Boolean {
        acquireCalls += 1
        return acquireResult
    }

    override fun release() {
        releaseCalls += 1
    }
}

private class FakeWakeLock(
    private val failAcquire: Boolean = false,
) : PhoneMicrophonePartialWakeLockHandle {
    private var held = false
    var acquireCalls = 0
    var releaseCalls = 0

    override val isHeld: Boolean
        get() = held

    override fun acquire() {
        acquireCalls += 1
        if (failAcquire) error("wake acquire failed")
        held = true
    }

    override fun release() {
        releaseCalls += 1
        held = false
    }
}
