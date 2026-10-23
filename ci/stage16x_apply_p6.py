from pathlib import Path

cards = Path("app/src/main/java/io/github/ioannes78/voica/ui/transcript/TranscriptionCards.kt")
strings = Path("app/src/main/res/values/strings.xml")


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one match, got {count}: {old[:100]!r}")
    path.write_text(text.replace(old, new, 1))


text = cards.read_text()
if "VoicaThemeTokens.colors" not in text:
    raise SystemExit("P6 base visual implementation is missing; refusing non-deterministic patch")

if "Animatable(1f)" in text:
    replace_once(
        cards,
        "    val highlightAlpha = remember(segment.stableId) { Animatable(1f) }\n",
        "    val highlightAlpha = remember(segment.stableId) { Animatable(0f) }\n",
    )
    replace_once(
        cards,
        '''    LaunchedEffect(activeCueId) {
        if (activeCue != null) {
            highlightAlpha.snapTo(0.62f)
            highlightAlpha.animateTo(1f, animationSpec = tween(durationMillis = 150))
        } else {
            highlightAlpha.snapTo(1f)
        }
    }
''',
        '''    LaunchedEffect(activeCueId) {
        if (activeCueId == null) {
            highlightAlpha.snapTo(0f)
        } else {
            highlightAlpha.snapTo(0.42f)
            highlightAlpha.animateTo(0.72f, animationSpec = tween(durationMillis = 150))
        }
    }
''',
    )
elif "Animatable(0f)" not in text:
    raise SystemExit("Unexpected P6 highlight state")

text = cards.read_text()
if "isActive -> transcriptColors.transcriptActiveParagraphBg\n" in text:
    replace_once(
        cards,
        "                    isActive -> transcriptColors.transcriptActiveParagraphBg\n",
        "                    isActive -> transcriptColors.transcriptActiveParagraphBg.copy(alpha = 0.52f)\n",
    )
elif "isActive -> transcriptColors.transcriptActiveParagraphBg.copy(alpha = 0.52f)\n" not in text:
    raise SystemExit("Unexpected P6 paragraph state")

string_text = strings.read_text()
if '<string name="app_name">Voica</string>' in string_text:
    replace_once(
        strings,
        '    <string name="app_name">Voica</string>\n',
        '    <string name="app_name">闻知</string>\n',
    )
elif '<string name="app_name">闻知</string>' not in string_text:
    raise SystemExit("Unexpected app_name state")

final = cards.read_text()
required = [
    "VoicaThemeTokens.colors",
    "Animatable(0f)",
    "highlightAlpha.snapTo(0.42f)",
    "highlightAlpha.animateTo(0.72f, animationSpec = tween(durationMillis = 150))",
    "transcriptCurrentWordBg",
    "transcriptUpcomingText",
    "transcriptPlayedText",
    "transcriptActiveLineBar",
    "transcriptActiveParagraphBg.copy(alpha = 0.52f)",
    "fontWeight = FontWeight.Medium",
]
missing = [item for item in required if item not in final]
if missing:
    raise SystemExit(f"P6 postcondition missing: {missing}")
if "val tokenHighlightColor = MaterialTheme.colorScheme.primaryContainer" in final:
    raise SystemExit("legacy hard-coded transcript highlight remains")

print("Stage 16X P6 gentle-reading refinement verified")
