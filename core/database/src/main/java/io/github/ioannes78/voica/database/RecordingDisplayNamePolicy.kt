package io.github.ioannes78.voica.database

object RecordingDisplayNamePolicy {
    private val standardDeviceAudioName =
        Regex(
            "^note\\d{8}-\\d{6}\\.(opus|wav)$",
            RegexOption.IGNORE_CASE,
        )

    fun defaultDisplayName(deviceFilename: String): String =
        if (standardDeviceAudioName.matches(deviceFilename)) {
            deviceFilename.substringBeforeLast('.')
        } else {
            deviceFilename
        }

    fun shouldNormalizeExisting(
        displayName: String,
        originalFilename: String,
    ): Boolean =
        displayName == originalFilename &&
            standardDeviceAudioName.matches(displayName)
}
