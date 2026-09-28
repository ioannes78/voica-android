package io.github.ioannes78.voica.protocol

data class DeviceTime(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int,
) {
    init {
        require(year in 0..0xFFFF)
        require(month in 1..12)
        require(day in 1..31)
        require(hour in 0..23)
        require(minute in 0..59)
        require(second in 0..59)
    }
}

sealed interface BatteryState {
    data class Level(val percent: Int) : BatteryState {
        init {
            require(percent in 0..100)
        }
    }

    data object Charging : BatteryState

    data class Unknown(val rawValue: Int?) : BatteryState
}

data class AuthCode(
    val text: String?,
    val hex: String,
)
