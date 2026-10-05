package io.github.ioannes78.voica.llm

import java.util.concurrent.CancellationException
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCancellationTest {
    @Test
    fun coroutineCancellationIsNeverMappedToNetworkFailure() {
        val cancellation = CancellationException("cancelled by test")
        val thrown =
            runCatching { mapTransportFailure(cancellation) }
                .exceptionOrNull()

        assertSame(cancellation, thrown)
    }

    @Test
    fun cancelledTransportFailureBecomesCoroutineCancellation() {
        val thrown =
            runCatching {
                mapTransportFailure(
                    LlmTransportException(TransportFailureKind.CANCELLED),
                )
            }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
    }
}
