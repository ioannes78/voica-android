from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    target = Path(path)
    text = target.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, got {count}: {old[:80]!r}")
    target.write_text(text.replace(old, new, 1))


cards = "app/src/main/java/io/github/ioannes78/voica/ui/transcript/TranscriptionCards.kt"
replace_once(
    cards,
    "import androidx.compose.foundation.background\n",
    "import androidx.compose.animation.animateColorAsState\n"
    "import androidx.compose.animation.core.Animatable\n"
    "import androidx.compose.animation.core.tween\n"
    "import androidx.compose.foundation.background\n",
)
replace_once(
    cards,
    "import androidx.compose.runtime.Composable\n",
    "import androidx.compose.runtime.Composable\nimport androidx.compose.runtime.LaunchedEffect\n",
)
replace_once(
    cards,
    "import androidx.compose.ui.Modifier\n",
    "import androidx.compose.ui.Modifier\n"
    "import androidx.compose.ui.draw.drawBehind\n"
    "import androidx.compose.ui.geometry.Offset\n",
)
replace_once(
    cards,
    "import androidx.compose.ui.text.TextLayoutResult\n",
    "import androidx.compose.ui.text.TextLayoutResult\nimport androidx.compose.ui.text.font.FontWeight\n",
)
replace_once(
    cards,
    "import io.github.ioannes78.voica.database.DiarizationSpeakerEntity\n",
    "import io.github.ioannes78.voica.database.DiarizationSpeakerEntity\n"
    "import io.github.ioannes78.voica.ui.theme.VoicaThemeTokens\n",
)

old_highlight = '''    val displayText = segment.text.ifBlank { stringResource(R.string.transcript_empty_segment) }
    val tokenHighlightColor = MaterialTheme.colorScheme.primaryContainer
    val annotatedText = buildAnnotatedString {
        append(displayText)
        if (
            segment.text.isNotBlank() && activeCue != null &&
            activeCue.textStartOffset in 0..segment.text.length &&
            activeCue.textEndOffsetExclusive in 0..segment.text.length &&
            activeCue.textEndOffsetExclusive > activeCue.textStartOffset
        ) {
            addStyle(SpanStyle(background = tokenHighlightColor), activeCue.textStartOffset, activeCue.textEndOffsetExclusive)
        }
    }
    var textLayout by remember(segment.stableId) { mutableStateOf<TextLayoutResult?>(null) }
'''
new_highlight = '''    val transcriptColors = VoicaThemeTokens.colors
    val displayText = segment.text.ifBlank { stringResource(R.string.transcript_empty_segment) }
    val highlightAlpha = remember(segment.stableId) { Animatable(1f) }
    LaunchedEffect(activeCueId) {
        if (activeCue != null) {
            highlightAlpha.snapTo(0.62f)
            highlightAlpha.animateTo(1f, animationSpec = tween(durationMillis = 150))
        } else {
            highlightAlpha.snapTo(1f)
        }
    }
    val tokenHighlightColor = transcriptColors.transcriptCurrentWordBg.copy(alpha = highlightAlpha.value)
    val annotatedText = buildAnnotatedString {
        append(displayText)
        if (
            segment.text.isNotBlank() && activeCue != null &&
            activeCue.textStartOffset in 0..segment.text.length &&
            activeCue.textEndOffsetExclusive in 0..segment.text.length &&
            activeCue.textEndOffsetExclusive > activeCue.textStartOffset
        ) {
            if (activeCue.textStartOffset > 0) {
                addStyle(
                    SpanStyle(color = transcriptColors.transcriptPlayedText),
                    0,
                    activeCue.textStartOffset,
                )
            }
            addStyle(
                SpanStyle(
                    background = tokenHighlightColor,
                    color = transcriptColors.transcriptCurrentWordText,
                    fontWeight = FontWeight.Medium,
                ),
                activeCue.textStartOffset,
                activeCue.textEndOffsetExclusive,
            )
            if (activeCue.textEndOffsetExclusive < segment.text.length) {
                addStyle(
                    SpanStyle(color = transcriptColors.transcriptUpcomingText),
                    activeCue.textEndOffsetExclusive,
                    segment.text.length,
                )
            }
        }
    }
    var textLayout by remember(segment.stableId) { mutableStateOf<TextLayoutResult?>(null) }
'''
replace_once(cards, old_highlight, new_highlight)

old_column = '''    val canEditSpeakerAssignment = spanId != null && repository != null && segment.speakerAssignmentAvailable

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    when {
                        searchHighlighted -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                        isActive -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                        else -> MaterialTheme.colorScheme.surface
                    },
                    RoundedCornerShape(8.dp),
                )
                .clickable(enabled = syncEnabled, onClick = { onSeek(segment.startSampleIndex) })
                .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
'''
new_column = '''    val canEditSpeakerAssignment = spanId != null && repository != null && segment.speakerAssignmentAvailable
    val paragraphBackground by
        animateColorAsState(
            targetValue =
                when {
                    searchHighlighted -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
                    isActive -> transcriptColors.transcriptActiveParagraphBg
                    else -> transcriptColors.surface
                },
            animationSpec = tween(durationMillis = 150),
            label = "transcriptParagraphBackground",
        )

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(paragraphBackground, RoundedCornerShape(8.dp))
                .drawBehind {
                    if (isActive) {
                        val strokeWidth = 2.dp.toPx()
                        drawLine(
                            color = transcriptColors.transcriptActiveLineBar,
                            start = Offset(strokeWidth / 2f, 6.dp.toPx()),
                            end = Offset(strokeWidth / 2f, size.height - 6.dp.toPx()),
                            strokeWidth = strokeWidth,
                        )
                    }
                }
                .clickable(enabled = syncEnabled, onClick = { onSeek(segment.startSampleIndex) })
                .padding(start = 13.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
'''
replace_once(cards, old_column, new_column)
replace_once(
    cards,
    "                    color = if (isActive || searchHighlighted || canEditSpeakerAssignment) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,\n",
    "                    color = if (isActive || searchHighlighted || canEditSpeakerAssignment) transcriptColors.accent else transcriptColors.textSecondary,\n",
)
replace_once(
    cards,
    "            style = MaterialTheme.typography.bodyMedium,\n            onTextLayout = { textLayout = it },\n",
    "            style = MaterialTheme.typography.bodyMedium,\n            color = transcriptColors.textPrimary,\n            onTextLayout = { textLayout = it },\n",
)

theme = "app/src/main/java/io/github/ioannes78/voica/ui/theme/Theme.kt"
for old, new in [
    ("            transcriptCurrentWordBg = WenzhiDarkAccent,\n", "            transcriptCurrentWordBg = WenzhiDarkAccentContainer,\n"),
    ("            transcriptCurrentWordText = WenzhiDarkBackground,\n", "            transcriptCurrentWordText = WenzhiDarkPrimaryText,\n"),
    ("            transcriptActiveParagraphBg = WenzhiDarkAccentContainer.copy(alpha = 0.76f),\n", "            transcriptActiveParagraphBg = WenzhiDarkAccentContainer.copy(alpha = 0.52f),\n"),
    ("            transcriptCurrentWordBg = WenzhiAccent,\n", "            transcriptCurrentWordBg = WenzhiAccentContainer,\n"),
    ("            transcriptCurrentWordText = Color.White,\n", "            transcriptCurrentWordText = WenzhiInk,\n"),
    ("            transcriptActiveParagraphBg = WenzhiAccentContainer.copy(alpha = 0.72f),\n", "            transcriptActiveParagraphBg = WenzhiAccentContainer.copy(alpha = 0.48f),\n"),
]:
    replace_once(theme, old, new)

replace_once(
    "app/src/main/res/values/strings.xml",
    '    <string name="app_name">Voica</string>\n',
    '    <string name="app_name">闻知</string>\n',
)

cards_text = Path(cards).read_text()
required = [
    "VoicaThemeTokens.colors",
    "Animatable(1f)",
    "tween(durationMillis = 150)",
    "transcriptCurrentWordBg",
    "transcriptUpcomingText",
    "transcriptPlayedText",
    "transcriptActiveLineBar",
    "fontWeight = FontWeight.Medium",
]
missing = [item for item in required if item not in cards_text]
if missing:
    raise SystemExit(f"P6 postcondition missing: {missing}")
if "val tokenHighlightColor = MaterialTheme.colorScheme.primaryContainer" in cards_text:
    raise SystemExit("legacy hard-coded transcript highlight remains")

print("Stage 16X P6 deterministic patch applied")
