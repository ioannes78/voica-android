from pathlib import Path


def replace_required(path: str, old: str, new: str) -> None:
    target = Path(path)
    text = target.read_text()
    count = text.count(old)
    if count == 0:
        return
    if count != 1:
        raise SystemExit(f"{path}: expected at most one match, got {count}: {old!r}")
    target.write_text(text.replace(old, new, 1))


replacements = [
    (
        "app/src/main/java/io/github/ioannes78/voica/PhoneMicrophonePermission.kt",
        "Voica 只会在你明确选择「手机麦克风」并开始录音时使用麦克风。",
        "闻知只会在你明确选择「手机麦克风」并开始录音时使用麦克风。",
    ),
    (
        "app/src/main/java/io/github/ioannes78/voica/ui/device/HomePhoneMicrophonePermissionGate.kt",
        "Voica 只会在你选择手机麦克风并开始录音或实时转写时使用麦克风。",
        "闻知只会在你选择手机麦克风并开始录音或实时转写时使用麦克风。",
    ),
    (
        "app/src/main/java/io/github/ioannes78/voica/ui/phone/PhoneMicrophoneProductUi.kt",
        "Voica 只会在你明确开始录音或实时转写时使用麦克风。",
        "闻知只会在你明确开始录音或实时转写时使用麦克风。",
    ),
    (
        "app/src/main/java/io/github/ioannes78/voica/ui/settings/StorageManagementScreen.kt",
        "Voica 仅保留本地运行必须的文件：原始音频、标准 WAV、模型、转写与说话人结果。",
        "闻知仅保留本地运行必须的文件：原始音频、标准 WAV、模型、转写与说话人结果。",
    ),
    (
        "app/src/main/java/io/github/ioannes78/voica/ui/settings/StorageManagementScreen.kt",
        "这里只统计缓存与临时文件，仅包含 Voica 可安全自动清理的内容。",
        "这里只统计缓存与临时文件，仅包含闻知可安全自动清理的内容。",
    ),
    (
        "app/src/main/java/io/github/ioannes78/voica/ui/settings/ProductSettingsScreen.kt",
        "Voica 青绿 · 跟随系统",
        "闻知墨韵 · 可选经典配色",
    ),
    (
        "app/src/main/java/io/github/ioannes78/voica/IsolatedModelValidationService.kt",
        "；Voica 主进程未受影响",
        "；闻知主进程未受影响",
    ),
    (
        "app/src/main/java/io/github/ioannes78/voica/TranscriptContentExportFormatter.kt",
        'val title = recordingName.trim().ifEmpty { "Voica 转写" }',
        'val title = recordingName.trim().ifEmpty { "闻知转写" }',
    ),
    (
        "app/src/main/java/io/github/ioannes78/voica/ExportUxSupport.kt",
        'const val DEFAULT_DISPLAY_LABEL = "系统下载 / Voica"',
        'const val DEFAULT_DISPLAY_LABEL = "系统下载 / WENZHI"',
    ),
    (
        "app/src/main/java/io/github/ioannes78/voica/ExportUxSupport.kt",
        'const val DEFAULT_SHORT_LABEL = "下载/Voica"',
        'const val DEFAULT_SHORT_LABEL = "下载/WENZHI"',
    ),
    (
        "app/src/main/java/io/github/ioannes78/voica/ContentTextExportCoordinator.kt",
        'Environment.DIRECTORY_DOWNLOADS + "/Voica/"',
        'Environment.DIRECTORY_DOWNLOADS + "/WENZHI/"',
    ),
    (
        "app/src/main/java/io/github/ioannes78/voica/LocalAudioExportCoordinator.kt",
        'Environment.DIRECTORY_DOWNLOADS + "/Voica/"',
        'Environment.DIRECTORY_DOWNLOADS + "/WENZHI/"',
    ),
    (
        "app/src/main/java/io/github/ioannes78/voica/ui/OfflineVoicaApp.kt",
        "已导出到 Downloads/Voica",
        "已导出到 Downloads/WENZHI",
    ),
    (
        "app/src/main/java/io/github/ioannes78/voica/ui/ai/AiSummaryProductCard.kt",
        "已导出到 Downloads/Voica",
        "已导出到 Downloads/WENZHI",
    ),
    (
        "app/src/main/java/io/github/ioannes78/voica/ui/transcript/TranscriptProductContent.kt",
        "已导出到 Downloads/Voica",
        "已导出到 Downloads/WENZHI",
    ),
    (
        "app/src/main/java/io/github/ioannes78/voica/ui/transcript/TranscriptContentControls.kt",
        "已导出到 Downloads/Voica",
        "已导出到 Downloads/WENZHI",
    ),
    (
        "app/src/main/java/io/github/ioannes78/voica/Stage158ReconciliationProbeActivity.kt",
        "Voica Stage15.8 Reconciliation Probe",
        "WENZHI Stage15.8 Reconciliation Probe",
    ),
]

for replacement in replacements:
    replace_required(*replacement)

# Keep tests aligned with the new export destination without touching technical package names.
for root in [Path("app/src/test"), Path("app/src/androidTest")]:
    if not root.exists():
        continue
    for path in root.rglob("*"):
        if not path.is_file() or path.suffix not in {".kt", ".java", ".xml"}:
            continue
        text = path.read_text()
        updated = text.replace("Downloads/Voica", "Downloads/WENZHI")
        updated = updated.replace('/Voica/', '/WENZHI/')
        if updated != text:
            path.write_text(updated)

# User-visible brand residue gate. Technical package/class/action/channel identifiers are intentionally allowed.
banned = [
    "Voica 只会",
    "Voica 主进程",
    "Voica 青绿",
    "Voica 仅保留",
    "仅包含 Voica",
    "Voica 转写",
    "Downloads/Voica",
    "系统下载 / Voica",
    "下载/Voica",
    "Voica Stage15.8 Reconciliation Probe",
]
source_roots = [Path("app/src/main/java"), Path("app/src/main/res")]
violations = []
for root in source_roots:
    for path in root.rglob("*"):
        if not path.is_file() or path.suffix not in {".kt", ".java", ".xml"}:
            continue
        text = path.read_text(errors="ignore")
        for phrase in banned:
            if phrase in text:
                violations.append(f"{path}: {phrase}")
if violations:
    raise SystemExit("User-visible Voica residue remains:\n" + "\n".join(violations))

print("Stage 16X P7 user-visible brand cleanup applied and verified")
