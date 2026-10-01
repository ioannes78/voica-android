package io.github.ioannes78.voica.model

import java.io.Closeable

class ModelUseRegistry {
    private val counts = mutableMapOf<ModelUseKey, Int>()

    @Synchronized
    fun acquire(
        modelId: String,
        version: String,
        revision: Long,
    ): ModelLease {
        require(revision >= 1L)
        val key = ModelUseKey(modelId, version, revision)
        counts[key] = (counts[key] ?: 0) + 1
        return ModelLease {
            release(key)
        }
    }

    @Synchronized
    fun isInUse(
        modelId: String,
        version: String,
        revision: Long,
    ): Boolean =
        (counts[ModelUseKey(modelId, version, revision)] ?: 0) > 0

    @Synchronized
    private fun release(key: ModelUseKey) {
        val next = (counts[key] ?: 0) - 1
        if (next <= 0) counts.remove(key) else counts[key] = next
    }

    private data class ModelUseKey(
        val modelId: String,
        val version: String,
        val revision: Long,
    )
}

class ModelLease internal constructor(
    private val onClose: () -> Unit,
) : Closeable {
    private var closed = false

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        onClose()
    }
}
