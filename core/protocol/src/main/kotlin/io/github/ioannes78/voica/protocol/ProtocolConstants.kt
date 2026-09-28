package io.github.ioannes78.voica.protocol

object ProtocolConstants {
    const val MAGIC = 0x5A
    const val HEADER_LENGTH = 6
    const val MAX_DATA_LENGTH = 8192
    const val FILENAME_FIELD_LENGTH = 24
    const val LIST_NAME_LENGTH = 20
    const val LIST_ENTRY_LENGTH = 28

    object Type {
        const val CONTROL = 0
        const val REALTIME = 1
        const val FILE = 2
        const val KEY = 3
    }

    object Control {
        const val SYNC_TIME = 0
        const val GET_CAPACITY = 1
        const val CAPACITY_RESPONSE = 2
        const val GET_BATTERY = 3
        const val BATTERY_RESPONSE = 4
        const val GET_VERSION = 10
        const val VERSION_RESPONSE = 11
        const val GET_AUTH = 12
        const val AUTH_RESPONSE = 13
        const val BATTERY_VALUE_CHARGING = 110

        @Deprecated("Use BATTERY_VALUE_CHARGING; 110 is a battery-response value, not a command.")
        const val BATTERY_CHARGING = BATTERY_VALUE_CHARGING
    }

    object Realtime {
        const val START = 0
        const val AUDIO_DATA = 1
        const val STOP = 2
        const val PAUSE_RESUME = 3
        const val DEVICE_STATE = 4
    }

    object File {
        const val LIST_REQUEST = 0
        const val LIST_DATA = 1
        const val IMPORT_REQUEST = 2
        const val IMPORT_START = 3
        const val DATA = 4
        const val IMPORT_END = 5
        const val IMPORT_ABORT = 7
        const val DELETE_ONE = 8
        const val DELETE_ALL = 9
        const val DELETE_ALL_RESPONSE = 10
        const val ABORT_RESPONSE = 11
        const val IMPORT_SEGMENT = 12
        const val DELETE_ONE_RESPONSE = 13
        const val LIST_DONE = 18
    }

    object Key {
        const val RECORD_START = 1
        const val RECORD_START_RESPONSE = 2
        const val RECORD_SAVE = 3
        const val RECORD_SAVE_RESPONSE = 4
        const val RECORD_PAUSE = 5
        const val RECORD_PAUSE_RESPONSE = 6
        const val RECORD_RESUME = 7
        const val RECORD_RESUME_RESPONSE = 8
        const val GET_STATE = 19
        const val STATE_RESPONSE = 20
        const val GET_TIME = 21
        const val TIME_RESPONSE = 22
        const val GET_FILENAME = 23
        const val FILENAME_RESPONSE = 24
        const val GET_GAIN = 25
        const val GAIN_RESPONSE = 26
        const val SET_GAIN = 27
        const val SET_GAIN_RESPONSE = 28
    }
}
