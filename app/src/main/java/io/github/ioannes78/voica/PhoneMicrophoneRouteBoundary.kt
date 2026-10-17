package io.github.ioannes78.voica

import java.io.Closeable

/** Why a phone microphone capture timeline must start a new canonical epoch. */
internal enum class PhoneMicrophoneRouteBoundaryReason {
    ROUTE_CHANGED,
    ROUTE_LOST,
    ROUTE_MISMATCH,
    CAPTURE_RECREATED,
}

internal data class PhoneMicrophoneRouteBoundary(
    val reason: PhoneMicrophoneRouteBoundaryReason,
    val oldRoute: PhoneMicrophoneRoute?,
    val newRoute: PhoneMicrophoneRoute?,
    val routeGeneration: Long,
)

/**
 * Process-local synchronous route-boundary seam.
 *
 * PhoneMicrophoneRouteTracker emits on the capture worker before the first PCM frame observed on the
 * new route can reach the formal consumer. The formal recording owner subscribes only while it is
 * alive and converts AUTO-route boundaries into a DISCONTINUITY canonical epoch.
 *
 * This hub never owns AudioRecord and never performs route selection. It exists only to preserve
 * ordering across the capture/formal-recording boundary.
 */
internal object PhoneMicrophoneRouteBoundaryHub {
    private val lock = Any()
    private var nextToken = 1L
    private val listeners = linkedMapOf<Long, (PhoneMicrophoneRouteBoundary) -> Unit>()

    fun subscribe(listener: (PhoneMicrophoneRouteBoundary) -> Unit): Closeable {
        val token = synchronized(lock) {
            val value = nextToken
            nextToken = Math.addExact(nextToken, 1L)
            listeners[value] = listener
            value
        }
        return Closeable {
            synchronized(lock) {
                listeners.remove(token)
            }
        }
    }

    /** Synchronous by design: returns only after current listeners observed the boundary. */
    fun emit(boundary: PhoneMicrophoneRouteBoundary) {
        val snapshot = synchronized(lock) { listeners.values.toList() }
        snapshot.forEach { listener -> listener(boundary) }
    }

    internal fun clearForTest() {
        synchronized(lock) {
            listeners.clear()
            nextToken = 1L
        }
    }
}
