package io.github.ioannes78.voica.protocol

class SequenceGenerator {
    private var current = -1

    @Synchronized
    fun next(): Int {
        current = (current + 1) and 0xFF
        return current
    }

    @Synchronized
    fun reset() {
        current = -1
    }
}
