from pathlib import Path


def one(source: str, old: str, new: str, label: str) -> str:
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    return source.replace(old, new, 1)


# 1. Annotation VM: transcript anchors must not depend on current player state.
vm = Path("app/src/main/java/io/github/ioannes78/voica/ui/annotation/TimelineAnnotationViewModel.kt")
s = vm.read_text()
transcript_method = '''    fun createAtTranscriptSample(
        recording: RecordingLibraryItem,
        canonicalAssetId: String?,
        totalSampleCount: Long?,
        sampleIndex: Long,
        provenance: TimelineAnnotationProvenance,
    ) {
        launchOperation {
            val sourceAssetId =
                canonicalAssetId?.takeIf { it.isNotBlank() }
                    ?: error("当前转写时间轴与标准音频不兼容")
            val durationSampleCount =
                totalSampleCount?.takeIf { it > 0L }
                    ?: error("当前转写缺少可验证时间轴")
            val anchor =
                captureCanonicalAnnotationAnchor(
                    recording = recording,
                    sourceAssetId = sourceAssetId,
                    durationSampleCount = durationSampleCount,
                    sampleIndex = sampleIndex,
                ) ?: error("当前转写位置无法建立精确标注")
            repository.create(
                owner = TimelineAnnotationOwner.Recording(recording.id),
                anchor = anchor,
                provenance = provenance,
            )
            mutableState.update { it.copy(notice = "已从转写添加标注") }
        }
    }

'''
s = one(
    s,
    '    fun updateNote(annotationId: String, noteText: String?) {',
    transcript_method + '    fun updateNote(annotationId: String, noteText: String?) {',
    'transcript create method',
)
helper_marker = 'internal fun captureCanonicalAnnotationAnchor(\n'
helper_index = s.find(helper_marker)
if helper_index < 0:
    raise SystemExit('canonical helper marker missing')
s = s[:helper_index] + '''internal fun captureCanonicalAnnotationAnchor(
    recording: RecordingLibraryItem,
    playback: PlaybackSnapshot,
    sampleIndex: Long,
): TimelineAnnotationAnchor? {
    if (playback.recordingId != recording.id) return null
    return captureCanonicalAnnotationAnchor(
        recording = recording,
        sourceAssetId = playback.sourceAssetId,
        durationSampleCount = playback.durationSampleCount,
        sampleIndex = sampleIndex,
    )
}

internal fun captureCanonicalAnnotationAnchor(
    recording: RecordingLibraryItem,
    sourceAssetId: String?,
    durationSampleCount: Long,
    sampleIndex: Long,
): TimelineAnnotationAnchor? {
    val resolvedAssetId = sourceAssetId?.takeIf { it.isNotBlank() } ?: return null
    if (durationSampleCount <= 0L) return null
    if (sampleIndex !in 0L..durationSampleCount) return null

    val canonical =
        recording.assets.firstOrNull { asset ->
            asset.assetId == resolvedAssetId &&
                asset.role == AudioAssetRole.CANONICAL_WAV &&
                asset.integrityState == AudioIntegrityState.VERIFIED &&
                asset.formatValidationState == AudioValidationState.VALID
        } ?: return null
    val profileId =
        recording.derivations.firstOrNull { derivation ->
            derivation.outputAssetId == canonical.assetId &&
                derivation.state == AudioDerivationState.READY
        }?.profileId

    return TimelineAnnotationAnchor(
        domain = TimelineAnnotationAnchorDomainValue.CANONICAL_RECORDING,
        sampleIndex = sampleIndex,
        canonicalAssetIdSnapshot = canonical.assetId,
        canonicalSha256Snapshot = canonical.sha256,
        canonicalProfileIdSnapshot = profileId,
    )
}
'''
vm.write_text(s)


# 2. Transcript timeline card: compact action and exact-cue long press.
cards = Path("app/src/main/java/io/github/ioannes78/voica/ui/transcript/TranscriptionCards.kt")
s = cards.read_text()
s = one(
    s,
    '    syncEnabled: Boolean,\n    onSeek: (Long) -> Unit,\n) {',
    '    syncEnabled: Boolean,\n    onSeek: (Long) -> Unit,\n    onAddAnnotation: ((Long) -> Unit)? = null,\n) {',
    'segment card signature',
)
old_time = '''            Text(
                formatSampleRange(
                    segment.startSampleIndex,
                    segment.endSampleIndexExclusive,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )'''
new_time = '''            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    formatSampleRange(
                        segment.startSampleIndex,
                        segment.endSampleIndexExclusive,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                onAddAnnotation?.let { addAnnotation ->
                    Text(
                        "＋标注",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier =
                            Modifier.clickable(enabled = syncEnabled) {
                                addAnnotation(segment.startSampleIndex)
                            },
                    )
                }
            }'''
s = one(s, old_time, new_time, 'segment annotation action')
old_gesture = '''                Modifier.pointerInput(
                    segment.stableId,
                    activeCueId,
                    syncEnabled,
                ) {
                    detectTapGestures { position ->
                        if (!syncEnabled) return@detectTapGestures
                        val offset = textLayout?.getOffsetForPosition(position) ?: return@detectTapGestures
                        val cue =
                            segment.cues.firstOrNull { candidate ->
                                candidate.projectionQuality == TextProjectionQuality.EXACT &&
                                    offset >= candidate.textStartOffset &&
                                    offset < candidate.textEndOffsetExclusive
                            }
                        onSeek(cue?.startSampleIndex ?: segment.startSampleIndex)
                    }
                },'''
new_gesture = '''                Modifier.pointerInput(
                    segment.stableId,
                    activeCueId,
                    syncEnabled,
                    onAddAnnotation,
                ) {
                    detectTapGestures(
                        onTap = { position ->
                            if (syncEnabled) {
                                val offset = textLayout?.getOffsetForPosition(position)
                                val cue =
                                    offset?.let { textOffset ->
                                        segment.cues.firstOrNull { candidate ->
                                            candidate.projectionQuality == TextProjectionQuality.EXACT &&
                                                textOffset >= candidate.textStartOffset &&
                                                textOffset < candidate.textEndOffsetExclusive
                                        }
                                    }
                                onSeek(cue?.startSampleIndex ?: segment.startSampleIndex)
                            }
                        },
                        onLongPress = { position ->
                            if (syncEnabled && onAddAnnotation != null) {
                                val offset = textLayout?.getOffsetForPosition(position)
                                val cue =
                                    offset?.let { textOffset ->
                                        segment.cues.firstOrNull { candidate ->
                                            candidate.projectionQuality == TextProjectionQuality.EXACT &&
                                                textOffset >= candidate.textStartOffset &&
                                                textOffset < candidate.textEndOffsetExclusive
                                        }
                                    }
                                onAddAnnotation(cue?.startSampleIndex ?: segment.startSampleIndex)
                            }
                        },
                    )
                },'''
s = one(s, old_gesture, new_gesture, 'segment exact cue long press')
cards.write_text(s)


# 3. Detail screen: immutable provenance snapshots and model/revision row wiring.
screen = Path("app/src/main/java/io/github/ioannes78/voica/ui/library/RecordingDetailProductScreen.kt")
s = screen.read_text()
s = one(
    s,
    'import io.github.ioannes78.voica.database.TimelineAnnotationAnchorDomainValue\n',
    'import io.github.ioannes78.voica.database.TimelineAnnotationAnchorDomainValue\nimport io.github.ioannes78.voica.database.TimelineAnnotationProvenance\n',
    'annotation provenance import',
)
s = one(
    s,
    'import io.github.ioannes78.voica.ui.transcript.TranscriptDocumentHeader\n',
    'import io.github.ioannes78.voica.ui.transcript.TranscriptDisplaySegment\nimport io.github.ioannes78.voica.ui.transcript.TranscriptDocumentHeader\n',
    'transcript segment import',
)
old_revision_call = '''                                            onSeek = { sample ->
                                                playbackViewModel.seekAndPlay(recording.id, sample)
                                            },
                                        )'''
new_revision_call = '''                                            onSeek = { sample ->
                                                playbackViewModel.seekAndPlay(recording.id, sample)
                                            },
                                            onAddAnnotation = { sample ->
                                                val provenance =
                                                    revisionAnnotationProvenance(
                                                        transcriptionId = recordingDocument.transcriptionId,
                                                        revisionId = transcriptContentState.currentRevisionId,
                                                        paragraph = paragraph,
                                                    )
                                                if (sample != null) {
                                                    annotationViewModel.createAtTranscriptSample(
                                                        recording = recording,
                                                        canonicalAssetId = recordingDocument.compatiblePlaybackAssetId,
                                                        totalSampleCount = recordingDocument.timeline?.totalSampleCount,
                                                        sampleIndex = sample,
                                                        provenance = provenance,
                                                    )
                                                } else {
                                                    annotationViewModel.createAtPlayback(
                                                        recording = recording,
                                                        playback = playbackSnapshot,
                                                        provenance = provenance,
                                                    )
                                                }
                                            },
                                        )'''
s = one(s, old_revision_call, new_revision_call, 'revision annotation wiring')
old_segment_call = '''                                            onSeek = { sampleIndex ->
                                                if (syncEnabled) {
                                                    playbackViewModel.seekAndPlay(
                                                        recording.id,
                                                        sampleIndex,
                                                    )
                                                }
                                            },
                                        )'''
new_segment_call = '''                                            onSeek = { sampleIndex ->
                                                if (syncEnabled) {
                                                    playbackViewModel.seekAndPlay(
                                                        recording.id,
                                                        sampleIndex,
                                                    )
                                                }
                                            },
                                            onAddAnnotation =
                                                if (syncEnabled) {
                                                    { sampleIndex ->
                                                        annotationViewModel.createAtTranscriptSample(
                                                            recording = recording,
                                                            canonicalAssetId = recordingDocument.compatiblePlaybackAssetId,
                                                            totalSampleCount = recordingDocument.timeline?.totalSampleCount,
                                                            sampleIndex = sampleIndex,
                                                            provenance =
                                                                segmentAnnotationProvenance(
                                                                    transcriptionId = recordingDocument.transcriptionId,
                                                                    segment = segment,
                                                                ),
                                                        )
                                                    }
                                                } else {
                                                    null
                                                },
                                        )'''
s = one(s, old_segment_call, new_segment_call, 'segment annotation wiring')
s = one(
    s,
    '    onSeek: (Long) -> Unit,\n) {\n    val start = paragraph.anchorStartSampleIndex',
    '    onSeek: (Long) -> Unit,\n    onAddAnnotation: (Long?) -> Unit,\n) {\n    val start = paragraph.anchorStartSampleIndex',
    'revision row signature',
)
old_revision_header = '''            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    start?.let(::formatSampleTime) ?: "无音频定位",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                paragraph.speakerDisplayName?.let { speaker ->
                    Text(
                        speaker,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }'''
new_revision_header = '''            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        start?.let(::formatSampleTime) ?: "无音频定位",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    paragraph.speakerDisplayName?.let { speaker ->
                        Text(
                            speaker,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                Text(
                    "＋标注",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onAddAnnotation(start) },
                )
            }'''
s = one(s, old_revision_header, new_revision_header, 'revision annotation action')
helper_insert = '''private fun segmentAnnotationProvenance(
    transcriptionId: String,
    segment: TranscriptDisplaySegment,
): TimelineAnnotationProvenance =
    TimelineAnnotationProvenance(
        sourceTextSnapshot = segment.text,
        sourceTranscriptionIdSnapshot = transcriptionId,
        sourceTranscriptSegmentIdSnapshot = segment.sourceSegmentId,
        sourceTimingQualitySnapshot = "EXACT",
        speakerLabelSnapshot =
            segment.speakerDisplayName
                ?: segment.speakerOrdinal?.let { ordinal -> "说话人 " + (ordinal + 1) },
    )

private fun revisionAnnotationProvenance(
    transcriptionId: String,
    revisionId: String?,
    paragraph: TranscriptReadingParagraph,
): TimelineAnnotationProvenance {
    val segmentIds =
        paragraph.sourceAnchorRefs
            .mapNotNull { ref ->
                ref.takeIf { it.startsWith("SEGMENT:") }
                    ?.removePrefix("SEGMENT:")
                    ?.takeIf { it.isNotBlank() }
            }
            .distinct()
    return TimelineAnnotationProvenance(
        sourceTextSnapshot = paragraph.text,
        sourceTranscriptionIdSnapshot = transcriptionId,
        sourceRevisionIdSnapshot = revisionId,
        sourceTranscriptSegmentIdSnapshot = segmentIds.singleOrNull(),
        sourceTimingQualitySnapshot = paragraph.timingQuality.name,
        speakerLabelSnapshot = paragraph.speakerDisplayName,
    )
}

'''
s = one(
    s,
    'private fun RecordingDetailDestination.toProductTab(): ProductDetailTab =',
    helper_insert + 'private fun RecordingDetailDestination.toProductTab(): ProductDetailTab =',
    'provenance helpers',
)
screen.write_text(s)
