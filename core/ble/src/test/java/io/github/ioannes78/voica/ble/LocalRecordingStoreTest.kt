package io.github.ioannes78.voica.ble

import io.github.ioannes78.voica.protocol.FilenameResolution
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.time.LocalDateTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalRecordingStoreTest {
    @Test
    fun streamsWavToPartThenCommitsAtomicallyAndReloadsMetadata() = runTest {
        val root = Files.createTempDirectory("voica-local-store").toFile()
        try {
            val store = LocalRecordingStore(root) { 1234L }
            val remote = remote(size = WAV_BYTES.size.toLong())
            val prepared = store.prepare(remote)

            prepared.writer.write(WAV_BYTES.copyOfRange(0, 10))
            prepared.writer.write(WAV_BYTES.copyOfRange(10, WAV_BYTES.size))
            val committed = store.commit(prepared, "note20260930-083059.wav")

            assertEquals(AudioContainer.WAV, committed.artifact.container)
            assertEquals(DeviceAudioFormat.OPUS, committed.artifact.sourceFormat)
            assertEquals(WAV_BYTES.size.toLong(), committed.artifact.sizeBytes)
            assertEquals(1234L, committed.artifact.downloadedAtMs)
            assertEquals(
                sha256(WAV_BYTES),
                committed.artifact.sha256,
            )
            assertTrue(store.resolveAudioFile(committed.artifact).isFile)
            assertArrayEquals(
                WAV_BYTES,
                store.resolveAudioFile(committed.artifact).readBytes(),
            )
            assertTrue(store.isDownloaded(remote.identity))

            val reloaded = LocalRecordingStore(root)
            val artifact = reloaded.artifactForRemote(remote.identity)
            assertNotNull(artifact)
            assertEquals("note20260930-083059.wav", artifact!!.displayFilename)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rawUnknownContainerKeepsRemoteOpusExtensionWhenStartHasNoFilename() = runTest {
        val root = Files.createTempDirectory("voica-raw-opus").toFile()
        try {
            val raw = ByteArray(80) { index -> (index and 0x7F).toByte() }
            val store = LocalRecordingStore(root) { 2233L }
            val remote = remote(size = raw.size.toLong())
            val prepared = store.prepare(remote)
            prepared.writer.write(raw)

            val committed = store.commit(prepared, actualTransferFilename = null)

            assertEquals(AudioContainer.RAW_OPUS, committed.artifact.container)
            assertTrue(committed.artifact.physicalFileName.endsWith(".opus"))
            assertTrue(committed.artifact.displayFilename.endsWith(".opus"))
            assertArrayEquals(raw, store.resolveAudioFile(committed.artifact).readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun opusAndWavDownloadsForSameRemoteAreIndependentAssets() = runTest {
        val root = Files.createTempDirectory("voica-dual-format").toFile()
        try {
            val remote = remote(size = 80L)
            val store = LocalRecordingStore(root) { 3300L }

            val opusBytes = ByteArray(80) { index -> (index and 0x7F).toByte() }
            val opus = store.prepare(remote, DeviceAudioFormat.OPUS)
            opus.writer.write(opusBytes)
            val opusArtifact = store.commit(opus, "note20260930-083059.opus").artifact

            val wav = store.prepare(remote, DeviceAudioFormat.WAV)
            wav.writer.write(WAV_BYTES)
            val wavArtifact = store.commit(wav, "note20260930-083059.wav").artifact

            assertTrue(store.isDownloaded(remote.identity, DeviceAudioFormat.OPUS))
            assertTrue(store.isDownloaded(remote.identity, DeviceAudioFormat.WAV))
            assertEquals(DeviceAudioFormat.OPUS, opusArtifact.sourceFormat)
            assertEquals(DeviceAudioFormat.WAV, wavArtifact.sourceFormat)
            assertTrue(opusArtifact.id != wavArtifact.id)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun runtimeModeDoesNotWriteOrReloadLegacyProperties() = runTest {
        val root = Files.createTempDirectory("voica-runtime-store").toFile()
        try {
            val remote = remote(size = 80L)
            val store = LocalRecordingStore(
                baseDirectory = root,
                nowMs = { 4400L },
                legacyMetadataEnabled = false,
            )
            val prepared = store.prepare(remote, DeviceAudioFormat.OPUS)
            prepared.writer.write(ByteArray(80))
            val committed = store.commit(prepared, "note20260930-083059.opus")

            val completed = root.resolve("completed")
            assertTrue(store.isDownloaded(remote.identity, DeviceAudioFormat.OPUS))
            assertTrue(completed.resolve(committed.artifact.physicalFileName).isFile)
            assertTrue(completed.listFiles().orEmpty().none { it.name.endsWith(".properties") })

            val restarted = LocalRecordingStore(
                baseDirectory = root,
                nowMs = { 4500L },
                legacyMetadataEnabled = false,
            )
            assertTrue(restarted.recordings.value.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun sizeMismatchDeletesPartAndDoesNotCreateReadyArtifact() = runTest {
        val root = Files.createTempDirectory("voica-size-mismatch").toFile()
        try {
            val store = LocalRecordingStore(root)
            val remote = remote(size = WAV_BYTES.size.toLong() + 1)
            val prepared = store.prepare(remote)
            prepared.writer.write(WAV_BYTES)

            val error = runCatching {
                store.commit(prepared, "note.wav")
            }.exceptionOrNull()

            assertTrue(error is FileTransferSinkException)
            assertEquals(
                FileOperationErrorCode.SIZE_MISMATCH,
                (error as FileTransferSinkException).operationError.code,
            )
            assertFalse(store.isDownloaded(remote.identity))
            assertTrue(File(root, "temp").listFiles().orEmpty().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun localDeleteDoesNotNeedRemoteState() = runTest {
        val root = Files.createTempDirectory("voica-delete-local").toFile()
        try {
            val store = LocalRecordingStore(root)
            val remote = remote(size = WAV_BYTES.size.toLong())
            val prepared = store.prepare(remote)
            prepared.writer.write(WAV_BYTES)
            val artifact = store.commit(prepared, "note.wav").artifact

            val result = store.delete(artifact.id)

            assertTrue(result.deleted)
            assertFalse(store.resolveAudioFile(artifact).exists())
            assertFalse(store.isDownloaded(remote.identity))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun startupCleansStalePartFiles() {
        val root = Files.createTempDirectory("voica-stale-part").toFile()
        try {
            val temp = File(root, "temp").apply { mkdirs() }
            File(temp, "old.part").writeBytes(byteArrayOf(1, 2, 3))

            LocalRecordingStore(root)

            assertTrue(temp.listFiles().orEmpty().isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    private fun remote(size: Long) = RemoteDeviceFile(
        identity = "AA|note20260930-083059.opus|$size|16",
        identityProvisional = false,
        deviceAddress = "AA",
        rawTimeValue = 16,
        durationSeconds = 16,
        sizeBytes = size,
        rawFilename = "note20260930-083059.",
        resolvedFilename = "note20260930-083059.opus",
        filenameResolution = FilenameResolution.RecoveredStandardOpusName,
        filenameFieldLength = 20,
        recordedAt = LocalDateTime.of(2026, 9, 30, 8, 30, 59),
        deviceOrder = 0,
        rawListEntryBytes = ByteArray(28),
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private companion object {
        val WAV_BYTES = byteArrayOf(
            'R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte(),
            4, 0, 0, 0,
            'W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte(),
            1, 2, 3, 4,
        )
    }
}
