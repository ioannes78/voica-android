package io.github.ioannes78.voica.ble

import java.util.ArrayDeque
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BoundedBleLog(
    private val capacity: Int = 200,
) {
    init {
        require(capacity > 0)
    }

    private val lock = Any()
    private val entries = ArrayDeque<String>(capacity)
    private var sequence = 0L
    private val mutableLines = MutableStateFlow<List<String>>(emptyList())

    val lines: StateFlow<List<String>> = mutableLines.asStateFlow()

    fun add(message: String) {
        synchronized(lock) {
            sequence += 1
            entries.addLast(sequence.toString().padStart(4, '0') + "  " + message)
            while (entries.size > capacity) entries.removeFirst()
            mutableLines.value = entries.toList()
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            mutableLines.value = emptyList()
        }
    }
}
