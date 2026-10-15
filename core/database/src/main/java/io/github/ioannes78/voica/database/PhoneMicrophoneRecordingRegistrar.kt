package io.github.ioannes78.voica.database

import androidx.room.withTransaction
import java.io.File
import java.security.MessageDigest
import java.util.UUID

data class PhoneMicrophoneRecordingRegistration(
    val recordingId: String,
    val originalFilename: String,
    val displayName: String,
    val recordedAtLocalIso: String,
    val startedAtMs: Long,
    val completedAtMs: Long,
    val relativePath: String,
    val mediaDurationMs: Long,
    val sizeBytes: Long,
    val sha256: String,
    val sampleRateHz: Int,
    val channelCount: Int,
)

/**
 * Narrow Stage 16A.1 ingress into the existing Recording Library.
 *
 * Phone microphone WAV is the formal source media itself. It is therefore registered directly as a
 * verified CANONICAL_WAV and deliberately does not create a fake AudioDerivation row or a fake remote
 * device/source asset. All normal library, playback, file transcription, search and export code keeps
 * using the existing Room recording tables.
 */
class PhoneMicrophoneRecordingRegistrar(
    private val database: VoicaDatabase,
    private val recordingsRoot: File,
) {
    private val dao = database.recordingDao()
    private val searchIndexRebuilder = SearchIndexRebuilder(database)

    suspend fun register(registration: PhoneMicrophoneRecordingRegistration): String {
        validate(registration)
        val target = managedExistingFile(registration.relativePath)
            ?: error("phone microphone WAV is outside recordings root or missing")
        require(target.length() == registration.sizeBytes) {
            "phone microphone WAV size changed before registration"
        }
        require(sha256(target).equals(registration.sha256, ignoreCase = true)) {
            "phone microphone WAV SHA-256 changed before registration"
        }

        val assetId = UUID.randomUUID().toString()
        database.withTransaction {
            check(
                dao.insertRecordingIgnore(
                    RecordingEntity(
                        id = registration.recordingId,
                        sourceType = RecordingSourceType.PHONE_MICROPHONE,
                        sourceRemoteIdentity = null,
                        sourceDeviceAddress = null,
                        originalFilename = registration.originalFilename,
                        displayName = registration.displayName.trim(),
                        recordedAtLocalIso = registration.recordedAtLocalIso,
                        deviceReportedDurationMs = null,
                        mediaDurationMs = registration.mediaDurationMs,
                        downloadedAtMs = null,
                        createdAtMs = registration.startedAtMs,
                        updatedAtMs = registration.completedAtMs,
                    ),
                ) != -1L,
            ) { "phone microphone recording id collision" }

            check(
                dao.insertUserMetadataIgnore(
                    RecordingUserMetadataEntity(
                        recordingId = registration.recordingId,
                        folderId = null,
                        isFavorite = false,
                        updatedAtMs = registration.completedAtMs,
                    ),
                ) != -1L,
            ) { "failed to create phone microphone recording metadata" }

            check(
                dao.insertAssetIgnore(
                    AudioAssetEntity(
                        assetId = assetId,
                        recordingId = registration.recordingId,
                        role = AudioAssetRole.CANONICAL_WAV,
                        relativePath = registration.relativePath,
                        container = "WAV",
                        codec = "PCM",
                        sampleFormat = "PCM16_LE",
                        sampleRateHz = registration.sampleRateHz,
                        channelCount = registration.channelCount,
                        sizeBytes = registration.sizeBytes,
                        sha256 = registration.sha256.lowercase(),
                        integrityState = AudioIntegrityState.VERIFIED,
                        formatValidationState = AudioValidationState.VALID,
                        createdAtMs = registration.completedAtMs,
                        verifiedAtMs = registration.completedAtMs,
                    ),
                ) != -1L,
            ) { "failed to register phone microphone canonical WAV" }
        }
        searchIndexRebuilder.reindexRecording(registration.recordingId)
        return registration.recordingId
    }

    private fun validate(registration: PhoneMicrophoneRecordingRegistration) {
        require(registration.recordingId.isNotBlank()) { "recordingId must not be blank" }
        require(registration.originalFilename.endsWith(".wav", ignoreCase = true)) {
            "phone microphone recording filename must be WAV"
        }
        require(registration.displayName.isNotBlank()) { "displayName must not be blank" }
        require(registration.recordedAtLocalIso.isNotBlank()) { "recordedAtLocalIso must not be blank" }
        require(registration.startedAtMs >= 0L) { "startedAtMs must be non-negative" }
        require(registration.completedAtMs >= registration.startedAtMs) {
            "completedAtMs must not precede startedAtMs"
        }
        require(registration.relativePath.isNotBlank()) { "relativePath must not be blank" }
        require(registration.mediaDurationMs >= 0L) { "mediaDurationMs must be non-negative" }
        require(registration.sizeBytes > WAV_HEADER_BYTES) { "phone microphone WAV must contain PCM" }
        require(SHA256.matches(registration.sha256.lowercase())) { "invalid SHA-256" }
        require(registration.sampleRateHz == CANONICAL_SAMPLE_RATE_HZ) {
            "phone microphone WAV must be 16 kHz"
        }
        require(registration.channelCount == CANONICAL_CHANNEL_COUNT) {
            "phone microphone WAV must be mono"
        }
    }

    private fun managedExistingFile(relativePath: String): File? {
        val root = recordingsRoot.canonicalFile
        val candidate = runCatching { File(recordingsRoot, relativePath).canonicalFile }.getOrNull()
            ?: return null
        if (
            candidate.path != root.path &&
            !candidate.path.startsWith(root.path + File.separator)
        ) {
            return null
        }
        return candidate.takeIf(File::isFile)
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

    private companion object {
        val SHA256 = Regex("^[0-9a-f]{64}$")
        const val HASH_BUFFER_BYTES = 64 * 1024
        const val WAV_HEADER_BYTES = 44L
        const val CANONICAL_SAMPLE_RATE_HZ = 16_000
        const val CANONICAL_CHANNEL_COUNT = 1
    }
}
