package io.github.ioannes78.voica

import io.github.ioannes78.voica.audio.CanonicalPcmProfile
import io.github.ioannes78.voica.database.PhoneMicrophoneRecordingRegistration
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class PhoneMicrophoneRecoveryReport(
    val recoveredRecordingIds: List<String> = emptyList(),
    val discardedEmptyStaging: Int = 0,
    val skippedRegistered: Int = 0,
    val failures: List<String> = emptyList(),
)

/**
 * Startup-only recovery for Stage 16A.1 phone microphone media.
 *
 * Abrupt process death can leave `phone_*.wav.part` because the generic incremental writer never
 * had a chance to finalize or delete its staging file. Recovery is deliberately scoped to
 * `recordings/phone/<recordingId>/` and never claims that capture continued after process death.
 * A recoverable file is patched from its actual PCM payload, fsynced, atomically promoted when
 * possible, and registered as an explicitly recovered PHONE_MICROPHONE/CANONICAL_WAV recording.
 */
internal class PhoneMicrophoneRecoveryCoordinator(
    private val recordingsRoot: File,
    private val isRegistered: suspend (recordingId: String) -> Boolean,
    private val registerRecording: suspend (PhoneMicrophoneRecordingRegistration) -> String,
    private val nowInstant: () -> Instant = Instant::now,
    private val zoneId: () -> ZoneId = ZoneId::systemDefault,
) {
    suspend fun reconcileOnStartup(): PhoneMicrophoneRecoveryReport {
        val phoneRoot = File(recordingsRoot, PHONE_DIRECTORY)
        val recordingDirs = phoneRoot.listFiles()?.filter(File::isDirectory).orEmpty()
        val recovered = mutableListOf<String>()
        val failures = mutableListOf<String>()
        var discardedEmpty = 0
        var skippedRegistered = 0

        for (recordingDir in recordingDirs) {
            val recordingId = recordingDir.name.trim()
            if (recordingId.isEmpty()) continue
            if (runCatching { isRegistered(recordingId) }.getOrDefault(false)) {
                skippedRegistered += 1
                continue
            }

            val files = recordingDir.listFiles()?.filter(File::isFile).orEmpty()
            val finalized = files.firstOrNull { it.name.matches(FINAL_WAV_NAME) }
            val staging = files.firstOrNull { it.name.matches(STAGING_WAV_NAME) }
            val source = finalized ?: staging ?: continue

            if (source.name.endsWith(STAGING_SUFFIX) && source.length() <= WAV_HEADER_BYTES) {
                if (source.delete()) discardedEmpty += 1
                recordingDir.takeIf { it.list().isNullOrEmpty() }?.delete()
                continue
            }

            val target =
                if (source.name.endsWith(STAGING_SUFFIX)) {
                    File(source.parentFile, source.name.removeSuffix(STAGING_SUFFIX))
                } else {
                    source
                }

            val result =
                runCatching {
                    val media = repairCanonicalWav(source)
                    val completedFile =
                        if (source == target) {
                            source
                        } else {
                            promote(source, target)
                        }
                    val startedAt = startedAt(completedFile)
                    val completedAt = nowInstant()
                    val relativePath = completedFile.relativeTo(recordingsRoot).invariantSeparatorsPath
                    val registration =
                        PhoneMicrophoneRecordingRegistration(
                            recordingId = recordingId,
                            originalFilename = completedFile.name,
                            displayName =
                                "手机录音（异常恢复） ${DISPLAY_TIME_FORMAT.format(startedAt.atZone(zoneId()))}",
                            recordedAtLocalIso =
                                startedAt.atZone(zoneId()).toOffsetDateTime().toString(),
                            startedAtMs = startedAt.toEpochMilli(),
                            completedAtMs = completedAt.toEpochMilli(),
                            relativePath = relativePath,
                            mediaDurationMs =
                                media.sampleCount * 1_000L / CanonicalPcmProfile.SAMPLE_RATE_HZ,
                            sizeBytes = completedFile.length(),
                            sha256 = sha256(completedFile),
                            sampleRateHz = CanonicalPcmProfile.SAMPLE_RATE_HZ,
                            channelCount = CanonicalPcmProfile.CHANNEL_COUNT,
                        )
                    registerRecording(registration)
                    recordingId
                }

            result.onSuccess { recovered += it }
                .onFailure { failure ->
                    failures += "$recordingId:${failure.javaClass.simpleName}:${failure.message.orEmpty()}"
                }
        }

        return PhoneMicrophoneRecoveryReport(
            recoveredRecordingIds = recovered,
            discardedEmptyStaging = discardedEmpty,
            skippedRegistered = skippedRegistered,
            failures = failures,
        )
    }

    private fun repairCanonicalWav(file: File): RecoveredMedia {
        require(file.isFile) { "phone microphone recovery source is missing" }
        RandomAccessFile(file, "rw").use { raf ->
            require(raf.length() >= WAV_HEADER_BYTES) { "phone microphone WAV is shorter than its header" }
            val header = ByteArray(WAV_HEADER_BYTES.toInt())
            raf.seek(0L)
            raf.readFully(header)
            require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF") { "invalid RIFF header" }
            require(String(header, 8, 4, Charsets.US_ASCII) == "WAVE") { "invalid WAVE header" }
            require(String(header, 12, 4, Charsets.US_ASCII) == "fmt ") { "invalid fmt chunk" }
            require(String(header, 36, 4, Charsets.US_ASCII) == "data") { "invalid data chunk" }
            require(readLe16(header, 20) == 1) { "phone recovery requires PCM" }
            require(readLe16(header, 22) == CanonicalPcmProfile.CHANNEL_COUNT) {
                "phone recovery requires mono audio"
            }
            require(readLe32(header, 24) == CanonicalPcmProfile.SAMPLE_RATE_HZ) {
                "phone recovery requires 16 kHz audio"
            }
            require(readLe16(header, 34) == BITS_PER_SAMPLE) {
                "phone recovery requires PCM16"
            }

            val payloadBytes = raf.length() - WAV_HEADER_BYTES
            val alignedPayloadBytes = payloadBytes - payloadBytes % CanonicalPcmProfile.BYTES_PER_SAMPLE
            require(alignedPayloadBytes > 0L) { "phone recovery contains no PCM payload" }
            if (alignedPayloadBytes != payloadBytes) {
                raf.setLength(WAV_HEADER_BYTES + alignedPayloadBytes)
            }
            require(alignedPayloadBytes <= UINT32_MAX) { "phone recovery WAV exceeds RIFF32 capacity" }
            writeLe32(raf, 4L, 36L + alignedPayloadBytes)
            writeLe32(raf, 40L, alignedPayloadBytes)
            raf.fd.sync()
            return RecoveredMedia(
                sampleCount = alignedPayloadBytes / CanonicalPcmProfile.BYTES_PER_SAMPLE,
            )
        }
    }

    private fun promote(source: File, target: File): File {
        target.parentFile?.mkdirs()
        require(!target.exists()) { "final phone WAV already exists beside staging" }
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: Throwable) {
            Files.move(source.toPath(), target.toPath())
        }
        return target
    }

    private fun startedAt(file: File): Instant {
        val match = FINAL_WAV_NAME.matchEntire(file.name)
        val encoded = match?.groupValues?.getOrNull(1)
        if (!encoded.isNullOrBlank()) {
            runCatching {
                return LocalDateTime.parse(encoded, FILE_TIME_FORMAT).atZone(zoneId()).toInstant()
            }
        }
        val fallbackMs = file.lastModified().coerceAtLeast(0L)
        return Instant.ofEpochMilli(fallbackMs)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(HASH_BUFFER_BYTES).use { input ->
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private fun readLe16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun readLe32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun writeLe32(raf: RandomAccessFile, offset: Long, value: Long) {
        require(value in 0L..UINT32_MAX)
        raf.seek(offset)
        raf.write((value and 0xffL).toInt())
        raf.write((value ushr 8 and 0xffL).toInt())
        raf.write((value ushr 16 and 0xffL).toInt())
        raf.write((value ushr 24 and 0xffL).toInt())
    }

    private data class RecoveredMedia(val sampleCount: Long)

    private companion object {
        const val PHONE_DIRECTORY = "phone"
        const val STAGING_SUFFIX = ".part"
        const val WAV_HEADER_BYTES = 44L
        const val BITS_PER_SAMPLE = 16
        const val UINT32_MAX = 0xffff_ffffL
        const val HASH_BUFFER_BYTES = 64 * 1024
        val FINAL_WAV_NAME = Regex("^phone_(\\d{8}_\\d{6})\\.wav$")
        val STAGING_WAV_NAME = Regex("^phone_(\\d{8}_\\d{6})\\.wav\\.part$")
        val FILE_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
        val DISPLAY_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}
