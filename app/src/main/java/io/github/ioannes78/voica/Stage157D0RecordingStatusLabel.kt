package io.github.ioannes78.voica

import io.github.ioannes78.voica.protocol.RecordingStatus

/** QA-only stable label for the sealed RecordingStatus hierarchy. */
internal val RecordingStatus.name: String
    get() = when (this) {
        RecordingStatus.Recording -> "Recording"
        RecordingStatus.Idle -> "Idle"
        RecordingStatus.Paused -> "Paused"
        is RecordingStatus.UnknownRaw -> "UnknownRaw($rawValue)"
    }
