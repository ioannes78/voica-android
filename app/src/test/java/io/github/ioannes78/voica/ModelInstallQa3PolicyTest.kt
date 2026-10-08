package io.github.ioannes78.voica

import io.github.ioannes78.voica.model.ModelCapabilities
import io.github.ioannes78.voica.model.ModelDescriptor
import io.github.ioannes78.voica.model.ModelDescriptorSnapshot
import io.github.ioannes78.voica.model.ModelFileDescriptor
import io.github.ioannes78.voica.model.ModelKind
import io.github.ioannes78.voica.model.ModelPackageFormat
import io.github.ioannes78.voica.model.ModelSourceType
import io.github.ioannes78.voica.model.RedistributionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelInstallQa3PolicyTest {
    @Test
    fun taskRemovalMarkOnlyAppliesToExactActiveManualGeneration() {
        val record = record(ModelInstallPhase.DOWNLOAD, generation = 4L)
        assertTrue(
            shouldApplyTaskRemovalMark(
                ModelInstallTaskRemovalMark(record.operationId, 4L),
                record,
            ),
        )
        assertFalse(
            shouldApplyTaskRemovalMark(
                ModelInstallTaskRemovalMark(record.operationId, 3L),
                record,
            ),
        )
        assertFalse(
            shouldApplyTaskRemovalMark(
                ModelInstallTaskRemovalMark(record.operationId, 4L),
                record.copy(requiresUserResume = true),
            ),
        )
        assertFalse(
            shouldApplyTaskRemovalMark(
                ModelInstallTaskRemovalMark(record.operationId, 4L),
                record.copy(origin = ModelInstallOrigin.AUTO_SMALL),
            ),
        )
    }

    @Test
    fun downloadNotificationUsesRealDeterminateProgress() {
        assertEquals(30, modelInstallDownloadPercent(48L, 160L))
        assertEquals(100, modelInstallDownloadPercent(160L, 160L))
        assertEquals(null, modelInstallDownloadPercent(20L, null))
        assertEquals(null, modelInstallDownloadPercent(20L, 0L))
    }

    @Test
    fun finalizeNotificationTracksActualDurablePhase() {
        assertEquals(
            "正在校验下载文件",
            modelInstallNotificationPhaseLabel(ModelInstallPhase.VERIFY),
        )
        assertEquals(
            "正在解压模型",
            modelInstallNotificationPhaseLabel(ModelInstallPhase.EXTRACT),
        )
        assertEquals(
            "正在校验模型文件",
            modelInstallNotificationPhaseLabel(ModelInstallPhase.FILE_VERIFY),
        )
        assertEquals(
            "正在验证运行库",
            modelInstallNotificationPhaseLabel(ModelInstallPhase.RUNTIME_VALIDATE),
        )
    }

    @Test
    fun notificationActionSemanticsMatchProductPage() {
        assertEquals(
            ModelInstallNotificationAction.CANCEL_DOWNLOAD,
            modelInstallNotificationAction(
                phase = ModelInstallPhase.DOWNLOAD,
                requiresUserResume = false,
                downloadedBytes = 40L,
                totalBytes = 100L,
            ),
        )
        assertEquals(
            ModelInstallNotificationAction.PAUSE_INSTALL,
            modelInstallNotificationAction(
                phase = ModelInstallPhase.DOWNLOAD,
                requiresUserResume = false,
                downloadedBytes = 100L,
                totalBytes = 100L,
            ),
        )
        assertEquals(
            ModelInstallNotificationAction.PAUSE_INSTALL,
            modelInstallNotificationAction(
                phase = ModelInstallPhase.EXTRACT,
                requiresUserResume = false,
                downloadedBytes = 100L,
                totalBytes = 100L,
            ),
        )
        assertEquals(
            ModelInstallNotificationAction.CONTINUE_INSTALL,
            modelInstallNotificationAction(
                phase = ModelInstallPhase.INTERRUPTED,
                requiresUserResume = true,
                downloadedBytes = 60L,
                totalBytes = 100L,
            ),
        )
        assertEquals(
            ModelInstallNotificationAction.NONE,
            modelInstallNotificationAction(
                phase = ModelInstallPhase.READY,
                requiresUserResume = false,
                downloadedBytes = 100L,
                totalBytes = 100L,
            ),
        )
    }

    private fun record(
        phase: ModelInstallPhase,
        generation: Long,
    ): ModelInstallJournalRecord =
        ModelInstallJournalRecord(
            operationId = "qa3-op",
            snapshot = snapshot(),
            origin = ModelInstallOrigin.MANUAL,
            phase = phase,
            downloadedBytes = 4L,
            totalBytes = 10L,
            executorKind = ModelInstallExecutorKind.WORK_MANAGER_DOWNLOAD,
            executorGeneration = generation,
            createdAtMs = 1L,
            updatedAtMs = 1L,
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
