package io.github.ioannes78.voica.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TranscriptionZeroWidthTokenPersistenceTest {
    @Test
    fun pointAnchoredCtcPunctuationPersistsAfterRecognitionCompletes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database =
            Room.inMemoryDatabaseBuilder(
                context,
                VoicaDatabase::class.java,
            )
                .allowMainThreadQueries()
                .build()
        try {
            database.recordingDao().insertRecordingIgnore(
                RecordingEntity(
                    id = "rec-ctc",
                    sourceType = RecordingSourceType.DEVICE_DOWNLOAD,
                    sourceRemoteIdentity = "remote-ctc",
                    sourceDeviceAddress = "AA:BB",
                    originalFilename = "ctc.wav",
                    displayName = "CTC",
                    recordedAtLocalIso = null,
                    deviceReportedDurationMs = 1_000,
                    downloadedAtMs = 1_000,
                    createdAtMs = 1_000,
                    updatedAtMs = 1_000,
                ),
            )

            var clock = 10_000L
            val ids = AtomicInteger()
            val repository =
                TranscriptionRepository(
                    database = database,
                    nowMs = { ++clock },
                    idFactory = { "ctc-id-" + ids.incrementAndGet() },
                )
            val transcriptionId =
                repository.create(
                    NewTranscriptionRequest(
                        recordingId = "rec-ctc",
                        mode = TranscriptionModeValue.HIGH_QUALITY,
                        sourceCanonicalAssetId = "canonical-ctc",
                        sourceCanonicalSha256 = "a".repeat(64),
                        canonicalProfileId = "CANONICAL_PCM16_16000_MONO_WAV_V1",
                        totalSampleCount = 16_000,
                        pipelineVersion = 2,
                        runtimeId = "sherpa-onnx",
                        runtimeVersion = "1.13.8-p5-ctc-r1",
                        vadModelId = "fsmn-vad-int8",
                        vadModelVersion = "1.0.0",
                        firstPassAsrModelId = "funasr-nano-int8",
                        firstPassAsrModelVersion = "2025-12-30",
                        secondPassAsrModelId = "funasr-nano-int8",
                        secondPassAsrModelVersion = "2025-12-30",
                        punctuationModelId = null,
                        punctuationModelVersion = null,
                        languageConfig = "auto",
                        configSnapshot = "{}",
                        modelManifestDigest = "b".repeat(64),
                        firstPassAsrModelRevision = 2,
                        secondPassAsrModelRevision = 2,
                    ),
                )
            repository.transition(transcriptionId, TranscriptionStateValue.VAD_ANALYZING)
            repository.transition(transcriptionId, TranscriptionStateValue.SECOND_PASS_TRANSCRIBING)

            repository.persistCompleted(
                transcriptionId = transcriptionId,
                segments =
                    listOf(
                        TranscriptSegmentWrite(
                            segmentIndex = 0,
                            startSampleIndex = 0,
                            endSampleIndexExclusive = 4_000,
                            firstPassRawText = "你好，世界",
                            secondPassRawText = "你好，世界",
                            finalText = "你好，世界",
                            detectedLanguage = "zh",
                            confidence = null,
                            tokens =
                                listOf(
                                    TranscriptTokenWrite(
                                        text = "你好",
                                        startSampleIndex = 200,
                                        endSampleIndexExclusive = 800,
                                        source = TranscriptTokenSourceValue.SECOND_PASS,
                                    ),
                                    // FunASR revision-2 anchors sentence punctuation to the preceding
                                    // spoken boundary without inventing an acoustic duration.
                                    TranscriptTokenWrite(
                                        text = "，",
                                        startSampleIndex = 800,
                                        endSampleIndexExclusive = 800,
                                        source = TranscriptTokenSourceValue.SECOND_PASS,
                                    ),
                                    TranscriptTokenWrite(
                                        text = "世界",
                                        startSampleIndex = 900,
                                        endSampleIndexExclusive = 1_600,
                                        source = TranscriptTokenSourceValue.SECOND_PASS,
                                    ),
                                ),
                        ),
                    ),
            )

            assertEquals(
                TranscriptionStateValue.COMPLETED,
                repository.find(transcriptionId)!!.state,
            )
            val segment = repository.loadSegments(transcriptionId).single()
            val punctuation = repository.loadTokens(segment.id).single { it.text == "，" }
            assertEquals(800L, punctuation.startSampleIndex)
            assertEquals(800L, punctuation.endSampleIndexExclusive)
            assertEquals(TranscriptTokenSourceValue.SECOND_PASS, punctuation.source)
        } finally {
            database.close()
        }
    }
}
