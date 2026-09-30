package io.github.ioannes78.voica.database

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyStage5ScannerTest {
    @Test
    fun validStage5OpusIsVerifiedAndPreservesStableRecordingId() {
        val root = Files.createTempDirectory("voica-legacy").toFile()
        try {
            val completed = File(root, "completed").apply { mkdirs() }
            val remoteIdentity = "AA:BB|note20260930-083059.opus|80|16"
            val stableId = sha256(remoteIdentity.encodeToByteArray())
            val bytes = ByteArray(80) { it.toByte() }
            File(completed, "$stableId.opus").writeBytes(bytes)
            writeMetadata(
                File(completed, "$stableId.properties"),
                mapOf(
                    "id" to stableId,
                    "sourceRemoteIdentity" to remoteIdentity,
                    "sourceDeviceAddress" to "AA:BB",
                    "displayFilename" to "note20260930-083059.opus",
                    "physicalFileName" to "$stableId.opus",
                    "recordedAt" to "2026-09-30T08:30:59",
                    "downloadedAtMs" to "1234",
                    "sizeBytes" to "80",
                    "sha256" to sha256(bytes),
                    "container" to "RAW_OPUS",
                ),
            )

            val result = LegacyStage5Scanner(root).scan()

            assertTrue(result.issues.isEmpty())
            assertEquals(1, result.candidates.size)
            val candidate = result.candidates.single()
            assertEquals(stableId, candidate.recordingId)
            assertEquals(LegacyAssetFormat.OPUS, candidate.sourceFormat)
            assertEquals(16_000L, candidate.deviceReportedDurationMs)
            assertEquals("completed/$stableId.opus", candidate.relativePath)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun shaMismatchIsDiagnosticAndNotImported() {
        val root = Files.createTempDirectory("voica-legacy-bad-sha").toFile()
        try {
            val completed = File(root, "completed").apply { mkdirs() }
            val remoteIdentity = "AA|note20260930-083059.opus|3|1"
            val stableId = sha256(remoteIdentity.encodeToByteArray())
            File(completed, "$stableId.opus").writeBytes(byteArrayOf(1, 2, 3))
            writeMetadata(
                File(completed, "$stableId.properties"),
                mapOf(
                    "id" to stableId,
                    "sourceRemoteIdentity" to remoteIdentity,
                    "sourceDeviceAddress" to "AA",
                    "displayFilename" to "note20260930-083059.opus",
                    "physicalFileName" to "$stableId.opus",
                    "recordedAt" to "",
                    "downloadedAtMs" to "1234",
                    "sizeBytes" to "3",
                    "sha256" to "0".repeat(64),
                    "container" to "RAW_OPUS",
                ),
            )

            val result = LegacyStage5Scanner(root).scan()

            assertTrue(result.candidates.isEmpty())
            assertEquals("RAW_SHA_MISMATCH", result.issues.single().code)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun orphanAudioIsReportedWithoutDeletion() {
        val root = Files.createTempDirectory("voica-legacy-orphan").toFile()
        try {
            val completed = File(root, "completed").apply { mkdirs() }
            val orphan = File(completed, "orphan.opus").apply { writeBytes(byteArrayOf(1)) }

            val result = LegacyStage5Scanner(root).scan()

            assertEquals("LEGACY_ORPHAN_AUDIO", result.issues.single().code)
            assertTrue(orphan.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun writeMetadata(file: File, values: Map<String, String>) {
        val properties = Properties()
        values.forEach(properties::setProperty)
        FileOutputStream(file).use { properties.store(it, null) }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
}
