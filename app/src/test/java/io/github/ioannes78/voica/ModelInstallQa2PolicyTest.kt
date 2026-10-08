package io.github.ioannes78.voica

import android.app.ApplicationExitInfo
import io.github.ioannes78.voica.model.ModelCapabilities
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.model.ModelDescriptorSnapshot
import io.github.ioannes78.voica.model.ModelFileDescriptor
import io.github.ioannes78.voica.model.ModelInstallCandidateInspection
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.model.ModelPackageFormat
import io.github.ioannes78.voica.model.ModelSourceType
import io.github.ioannes78.voica.model.RedistributionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelInstallQa2PolicyTest {
    @Test
    fun userRequestedMainProcessExitRequiresExplicitResume() {
        assertTrue(
            shouldInterruptModelInstallForExit(
                exit =
                    ModelInstallExitSnapshot(
                        reason = ApplicationExitInfo.REASON_USER_REQUESTED,
                        timestampMs = 200L,
                        processName = "io.github.ioannes78.voica",
                    ),
                lastHandledTimestampMs = 100L,
                mainProcessName = "io.github.ioannes78.voica",
                appVersionChanged = false,
            ),
        )
    }

    @Test
    fun staleExitVersionChangeAndValidatorProcessDoNotInterrupt() {
        val exit =
            ModelInstallExitSnapshot(
                reason = ApplicationExitInfo.REASON_USER_REQUESTED,
                timestampMs = 200L,
                processName = "io.github.ioannes78.voica",
            )
        assertFalse(
            shouldInterruptModelInstallForExit(
                exit,
                lastHandledTimestampMs = 200L,
                mainProcessName = "io.github.ioannes78.voica",
                appVersionChanged = false,
            ),
        )
        assertFalse(
            shouldInterruptModelInstallForExit(
                exit,
                lastHandledTimestampMs = 100L,
                mainProcessName = "io.github.ioannes78.voica",
                appVersionChanged = true,
            ),
        )
        assertFalse(
            shouldInterruptModelInstallForExit(
                exit.copy(processName = "io.github.ioannes78.voica:model-validator"),
                lastHandledTimestampMs = 100L,
                mainProcessName = "io.github.ioannes78.voica",
                appVersionChanged = false,
            ),
        )
    }

    @Test
    fun incompleteDownloadCancelsButCompletePackagePauses() {
        val downloading = record(ModelInstallPhase.DOWNLOAD, downloadedBytes = 4L)
        assertFalse(
            shouldPauseInsteadOfCancel(
                downloading,
                inspection(downloadedBytes = 4L, packageComplete = false),
            ),
        )
        assertTrue(
            shouldPauseInsteadOfCancel(
                downloading.copy(downloadedBytes = 10L),
                inspection(downloadedBytes = 10L, packageComplete = true),
            ),
        )
    }

    @Test
    fun postDownloadPhasesPauseWithoutDestructiveCleanup() {
        val phases =
            listOf(
                ModelInstallPhase.VERIFY,
                ModelInstallPhase.EXTRACT,
                ModelInstallPhase.FILE_VERIFY,
                ModelInstallPhase.RUNTIME_VALIDATE,
                ModelInstallPhase.ATOMIC_ACTIVATE,
            )
        phases.forEach { phase ->
            assertTrue(
                "phase=$phase",
                shouldPauseInsteadOfCancel(
                    record(phase, downloadedBytes = 10L),
                    inspection(downloadedBytes = 10L, packageComplete = true),
                ),
            )
        }
        assertFalse(
            shouldPauseInsteadOfCancel(
                record(ModelInstallPhase.INTERRUPTED, downloadedBytes = 10L),
                inspection(downloadedBytes = 10L, packageComplete = true),
            ),
        )
    }

    @Test
    fun historicalReadyIsSupersededWhenStorageNoLongerReportsActive() {
        val ready = record(ModelInstallPhase.READY, downloadedBytes = 10L)
        assertTrue(
            shouldSupersedeHistoricalReady(
                record = ready,
                inspection = inspection(downloadedBytes = 0L, packageComplete = false),
                requestedVersion = "v2",
                requestedRevision = 2L,
            ),
        )
        assertFalse(
            shouldSupersedeHistoricalReady(
                record = ready,
                inspection =
                    inspection(downloadedBytes = 0L, packageComplete = false).copy(
                        installed = true,
                        confirmedGood = true,
                        active = true,
                    ),
                requestedVersion = "v2",
                requestedRevision = 2L,
            ),
        )
    }

    @Test
    fun networkFailurePresentationNeverLeaksRawEnglish() {
        val record =
            record(ModelInstallPhase.DOWNLOAD, downloadedBytes = 7L).copy(
                lastFailureCode = "NETWORK",
                lastFailureMessage = "Software caused connection abort",
            )
        assertEquals("网络连接异常，正在等待重试", modelInstallRecordUserMessage(record))
        assertFalse(modelInstallRecordUserMessage(record)!!.contains("Software"))
    }

    @Test
    fun unknownEnglishFailureFallsBackToChinese() {
        val record =
            record(ModelInstallPhase.FAILED_CONFIGURATION).copy(
                lastFailureCode = "UNKNOWN_NATIVE_ERROR",
                lastFailureMessage = "native validation failed",
            )
        assertEquals("模型安装未完成，请重试", modelInstallRecordUserMessage(record))
        assertEquals(
            "模型操作失败，请重试",
            modelUserSafeErrorMessage("connection reset", "模型操作失败，请重试"),
        )
    }

    private fun record(
        phase: ModelInstallPhase,
        downloadedBytes: Long = 0L,
    ): ModelInstallJournalRecord =
        ModelInstallJournalRecord(
            operationId = "qa2-op",
            snapshot = snapshot(),
            origin = ModelInstallOrigin.MANUAL,
            phase = phase,
            downloadedBytes = downloadedBytes,
            totalBytes = 10L,
            createdAtMs = 1L,
            updatedAtMs = 1L,
        )

    private fun inspection(
        downloadedBytes: Long,
        packageComplete: Boolean,
    ): ModelInstallCandidateInspection =
        ModelInstallCandidateInspection(
            downloadedBytes = downloadedBytes,
            totalBytes = 10L,
            packageComplete = packageComplete,
            installed = false,
            confirmedGood = false,
            active = false,
        )

    private fun snapshot(): ModelDescriptorSnapshot =
        ModelDescriptorSnapshot(
            descriptor =
                ModelDescriptor(
                    modelId = "vad",
                    kind = ModelKind.VAD,
                    displayName = "Silero VAD",
                    version = "v2",
                    revision = 2L,
                    runtimeId = "sherpa-onnx",
                    runtimeVersionMin = null,
                    runtimeVersionMax = null,
                    languages = setOf("zh"),
                    capabilities = ModelCapabilities(),
                    sourceType = ModelSourceType.MANAGED_DOWNLOAD,
                    builtinAssetPath = null,
                    packageFormat = ModelPackageFormat.SINGLE_FILE,
                    downloadUrl = "https://example.invalid/model.bin",
                    mirrors = emptyList(),
                    downloadSizeBytes = 10L,
                    installedSizeBytes = 10L,
                    packageSha256 = "0".repeat(64),
                    files =
                        listOf(
                            ModelFileDescriptor(
                                relativePath = "model.onnx",
                                sizeBytes = 10L,
                                sha256 = "1".repeat(64),
                                packagePath = "model.onnx",
                            ),
                        ),
                    abis = setOf("arm64-v8a"),
                    minSdk = 26,
                    appVersionMin = null,
                    appVersionMax = null,
                    licenseId = "Apache-2.0",
                    licenseUrl = null,
                    sourceUrl = "https://example.invalid/source",
                    homepage = null,
                    attribution = "test",
                    redistributionPolicy = RedistributionPolicy.UPSTREAM_ONLY,
                    releaseChannel = "production",
                    autoUpdateEligible = false,
                ),
            manifestDigest = "a".repeat(64),
        )
}
