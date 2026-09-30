package io.github.ioannes78.voica.ble

import io.github.ioannes78.voica.protocol.DeviceFilenameResolver
import io.github.ioannes78.voica.protocol.FilenameResolutionResult
import io.github.ioannes78.voica.protocol.RawDeviceFileEntry
import io.github.ioannes78.voica.protocol.RecordingFilenameParser

object DeviceFileNameProjection {
    fun project(entry: RawDeviceFileEntry): FilenameResolutionResult =
        DeviceFilenameResolver.resolve(entry.rawFilename)
}

object RemoteDeviceFileMapper {
    fun map(
        deviceAddress: String,
        entries: List<RawDeviceFileEntry>,
    ): List<RemoteDeviceFile> {
        val mapped = entries.mapIndexed { index, entry ->
            val resolution = DeviceFileNameProjection.project(entry)
            val effectiveName = resolution.resolvedFilename ?: entry.rawFilename
            RemoteDeviceFile(
                identity = buildIdentity(
                    deviceAddress = deviceAddress,
                    filename = effectiveName,
                    sizeBytes = entry.sizeBytes,
                    rawTimeValue = entry.rawTimeValue,
                ),
                identityProvisional = resolution.resolvedFilename == null,
                deviceAddress = deviceAddress,
                rawTimeValue = entry.rawTimeValue,
                durationSeconds = entry.rawTimeValue,
                sizeBytes = entry.sizeBytes,
                rawFilename = entry.rawFilename,
                resolvedFilename = resolution.resolvedFilename,
                filenameResolution = resolution.resolution,
                filenameFieldLength = entry.filenameFieldLength,
                recordedAt = RecordingFilenameParser.parse(effectiveName),
                deviceOrder = index,
                rawListEntryBytes = entry.rawEntryBytes.copyOf(),
            )
        }.distinctBy { it.identity }

        return mapped.sortedWith(
            compareByDescending<RemoteDeviceFile> { it.recordedAt != null }
                .thenByDescending { it.recordedAt }
                .thenBy { it.deviceOrder },
        )
    }

    private fun buildIdentity(
        deviceAddress: String,
        filename: String,
        sizeBytes: Long,
        rawTimeValue: Long,
    ): String =
        deviceAddress + "|" + filename + "|" + sizeBytes + "|" + rawTimeValue
}
