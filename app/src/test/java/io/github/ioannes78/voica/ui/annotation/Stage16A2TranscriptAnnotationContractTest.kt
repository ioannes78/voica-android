package io.github.ioannes78.voica.ui.annotation

import io.github.ioannes78.voica.database.AudioAssetRole
import io.github.ioannes78.voica.database.AudioDerivationState
import io.github.ioannes78.voica.database.AudioIntegrityState
import io.github.ioannes78.voica.database.AudioValidationState
import io.github.ioannes78.voica.database.RecordingAsset
import io.github.ioannes78.voica.database.RecordingDerivation
import io.github.ioannes78.voica.database.RecordingLibraryItem
import io.github.ioannes78.voica.database.TimelineAnnotationAnchorDomainValue
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage16A2TranscriptAnnotationContractTest {
    @Test
    fun transcriptSourceCanCaptureCanonicalAnchorWithoutLoadedPlayback() {
        val anchor =
            captureCanonicalAnnotationAnchor(
                recording = recording(),
                sourceAssetId = ASSET_ID,
                durationSampleCount = 32_000L,
                sampleIndex = 12_345L,
            )

        requireNotNull(anchor)
        assertEquals(TimelineAnnotationAnchorDomainValue.CANONICAL_RECORDING, anchor.domain)
        assertEquals(12_345L, anchor.sampleIndex)
        assertEquals(ASSET_ID, anchor.canonicalAssetIdSnapshot)
        assertEquals(SHA, anchor.canonicalSha256Snapshot)
        assertEquals(PROFILE, anchor.canonicalProfileIdSnapshot)
    }

    @Test
    fun transcriptSourceRejectsMismatchedAssetAndOutOfRangeSamples() {
        assertNull(
            captureCanonicalAnnotationAnchor(
                recording = recording(),
                sourceAssetId = "other-asset",
                durationSampleCount = 32_000L,
                sampleIndex = 12_345L,
            ),
        )
        assertNull(
            captureCanonicalAnnotationAnchor(
                recording = recording(),
                sourceAssetId = ASSET_ID,
                durationSampleCount = 32_000L,
                sampleIndex = 32_001L,
            ),
        )
    }

    @Test
    fun transcriptTimelineEntryKeepsExactCueRevisionAndUnifiedComposerRules() {
        val cards = appProductionSource("ui/transcript/TranscriptionCards.kt")
        val screen = appProductionSource("ui/library/RecordingDetailProductScreen.kt")
        val composer = appProductionSource("ui/annotation/TimelineAnnotationComposerButton.kt")
        val projection = appProductionSource("ui/transcript/TranscriptAnnotationProjection.kt")
        val viewModel = appProductionSource("ui/annotation/TimelineAnnotationViewModel.kt")
        val productContent = appProductionSource("ui/transcript/TranscriptProductContent.kt")
        val detailDialog = appProductionSource("ui/annotation/TimelineAnnotationDetailDialog.kt")

        assertTrue(cards.contains("annotationAction"))
        assertTrue(cards.contains("onLongPress = { position ->"))
        assertTrue(cards.contains("candidate.projectionQuality == TextProjectionQuality.EXACT"))
        assertTrue(cards.contains("onAddAnnotation(cue?.startSampleIndex ?: segment.startSampleIndex)"))
        assertTrue(screen.contains("TimelineAnnotationComposerButton("))
        assertTrue(composer.contains("Text(\"快速标记\")"))
        assertTrue(composer.contains("Text(\"文字笔记\")"))
        assertTrue(composer.contains("Text(\"拍照\")"))
        assertTrue(composer.contains("Text(\"从相册添加\")"))
        assertTrue(viewModel.contains("fun createAtTranscriptSample("))
        assertTrue(viewModel.contains("sourceAssetId = sourceAssetId"))
        assertTrue(screen.contains("sourceTranscriptionIdSnapshot = transcriptionId"))
        assertTrue(screen.contains("sourceRevisionIdSnapshot = revisionId"))
        assertTrue(screen.contains("sourceTimingQualitySnapshot = paragraph.timingQuality.name"))
        assertTrue(screen.contains("sourceTranscriptSegmentIdSnapshot = segmentIds.singleOrNull()"))
        assertTrue(
            "Only paragraphs without an audio anchor may fall back to the current playback position",
            screen.contains("if (sample != null)") &&
                screen.contains("annotationViewModel.createAtPlayback("),
        )
        assertTrue(
            "Reading mode projects annotations without polluting text selection",
            screen.contains("TranscriptAnnotatedReading(") &&
                projection.contains("DisableSelection"),
        )
        assertTrue(
            "Current transcript projection is sample-based and never inferred from text length",
            projection.contains("projection.sampleIndex >= candidate.startSampleIndex") &&
                projection.contains("projection.sampleIndex < candidate.endSampleIndexExclusive"),
        )
        assertTrue(
            "Projected annotations stay in one compact horizontal strip",
            projection.contains("horizontalScroll(rememberScrollState())"),
        )
        assertTrue(
            "Annotation visibility is controlled by the compact count chip",
            productContent.contains("label = \"标注 $annotationCount\"") &&
                !productContent.contains("标注 $annotationCount · "),
        )
        assertTrue(
            "Transcript annotation details expose photo viewing, edit, delete, and seek",
            detailDialog.contains("DetailAnnotationPhotoDialog(") &&
                detailDialog.contains("Text(\"编辑\")") &&
                detailDialog.contains("Text(\"删除\")") &&
                detailDialog.contains("Text(\"跳转播放\")"),
        )
    }

    private fun recording(): RecordingLibraryItem =
        RecordingLibraryItem(
            id = RECORDING_ID,
            sourceRemoteIdentity = null,
            sourceDeviceAddress = null,
            originalFilename = "recording.wav",
            displayName = "recording",
            recordedAtLocalIso = null,
            deviceReportedDurationMs = 2_000L,
            downloadedAtMs = null,
            state = "ACTIVE",
            assets =
                listOf(
                    RecordingAsset(
                        assetId = ASSET_ID,
                        role = AudioAssetRole.CANONICAL_WAV,
                        relativePath = "canonical/$ASSET_ID.wav",
                        container = "WAV",
                        codec = "PCM",
                        sampleFormat = "PCM16_LE",
                        sampleRateHz = 16_000,
                        channelCount = 1,
                        sizeBytes = 64_044L,
                        sha256 = SHA,
                        integrityState = AudioIntegrityState.VERIFIED,
                        formatValidationState = AudioValidationState.VALID,
                    ),
                ),
            derivations =
                listOf(
                    RecordingDerivation(
                        profileId = PROFILE,
                        sourceSha256 = "b".repeat(64),
                        sourceAssetId = "source",
                        outputAssetId = ASSET_ID,
                        pipelineVersion = 1,
                        state = AudioDerivationState.READY,
                        errorCode = null,
                        errorDetail = null,
                    ),
                ),
        )

    private fun appProductionSource(relative: String): String {
        val candidates =
            listOf(
                File("src/main/java/io/github/ioannes78/voica/$relative"),
                File("app/src/main/java/io/github/ioannes78/voica/$relative"),
            )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("app production source not found: $relative")
    }

    private companion object {
        const val RECORDING_ID = "recording-1"
        const val ASSET_ID = "canonical-asset"
        const val PROFILE = "CANONICAL_PCM16_MONO_16K_V1"
        const val SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
