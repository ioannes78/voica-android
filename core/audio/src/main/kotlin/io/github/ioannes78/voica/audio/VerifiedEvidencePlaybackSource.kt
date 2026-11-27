package io.github.ioannes78.voica.audio

import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** QA-only expected identity. Does not grant playback authority or change PlaybackController. */
data class EvidencePlaybackExpectation(
    val recordingId: String,
    val assetId: String,
    val sha256: String,
    val profileId: String,
    val totalSampleCount: Long,
    val startSampleIndex: Long,
    val endSampleIndexExclusive: Long,
) {
    init {
        require(recordingId.isNotBlank() && assetId.isNotBlank())
        require(Regex("[0-9a-f]{64}").matches(sha256))
        require(profileId == CanonicalPcmProfile.PROFILE_ID)
        require(startSampleIndex >= 0 && endSampleIndexExclusive > startSampleIndex &&
            endSampleIndexExclusive <= totalSampleCount)
    }
}

enum class EvidencePlaybackVerification { VERIFIED, IDENTITY_MISMATCH, FORMAT_MISMATCH, CONTENT_MISMATCH }

/**
 * Checks the exact opened handle, not merely a pathname or a Room metadata hash.
 * Caller retains ownership of the handle and must close it on rejection/cancellation.
 * A VERIFIED result is valid only for this handle; no later load by recordingId is authorized.
 * Does not itself serialize playback commands or protect against subsequent in-place mutation.
 */
object VerifiedEvidencePlaybackSource {
    suspend fun verify(
        source: PlaybackAudioSource,
        expected: EvidencePlaybackExpectation,
    ): EvidencePlaybackVerification = withContext(Dispatchers.IO) {
        val d = source.descriptor
        val handle = source.handle
        if (d.recordingId != expected.recordingId || d.assetId != expected.assetId ||
            d.totalSampleCount != expected.totalSampleCount) {
            return@withContext EvidencePlaybackVerification.IDENTITY_MISMATCH
        }
        val offset = d.pcmDataOffsetBytes
        val size = d.pcmDataSizeBytes
        if (d.container != AudioContainerKind.WAV || !d.seekable ||
            d.sampleRateHz != CanonicalPcmProfile.SAMPLE_RATE_HZ ||
            d.channelCount != CanonicalPcmProfile.CHANNEL_COUNT ||
            d.bitsPerSample != CanonicalPcmProfile.BITS_PER_SAMPLE ||
            d.bytesPerFrame != CanonicalPcmProfile.BYTES_PER_SAMPLE ||
            d.lengthBytes != handle.lengthBytes || d.lengthBytes <= 0 ||
            offset == null || size == null || offset < 0 || size < 0 ||
            offset > d.lengthBytes || size > d.lengthBytes - offset ||
            size % CanonicalPcmProfile.BYTES_PER_SAMPLE != 0L ||
            size / CanonicalPcmProfile.BYTES_PER_SAMPLE != expected.totalSampleCount
        ) return@withContext EvidencePlaybackVerification.FORMAT_MISMATCH

        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var position = 0L
        while (position < d.lengthBytes) {
            currentCoroutineContext().ensureActive()
            val wanted = minOf(buffer.size.toLong(), d.lengthBytes - position).toInt()
            val count = handle.readAt(position, buffer, 0, wanted)
            if (count !in 1..wanted) return@withContext EvidencePlaybackVerification.CONTENT_MISMATCH
            digest.update(buffer, 0, count)
            position += count
        }
        currentCoroutineContext().ensureActive()
        val hash = digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        if (hash != expected.sha256) EvidencePlaybackVerification.CONTENT_MISMATCH
        else EvidencePlaybackVerification.VERIFIED
    }
}
