#!/usr/bin/env python3
"""Apply the Stage 16B FunASR CTC A0 config-ABI patch to sherpa-onnx v1.13.8.

This gate deliberately patches only the optional `ctcCompact` model path through
Kotlin -> JNI -> C++. Empty keeps stock revision-1 behavior. Shared-encoder state,
compact scoring and second-decode alignment are layered only after this exact
runtime/toolchain boundary compiles cleanly.

The patcher intentionally uses short anchors *inside narrowly bounded FunASR
regions*. This is less brittle than matching large upstream paragraphs while
still failing closed if the pinned v1.13.8 structure drifts.
"""

from __future__ import annotations

import argparse
from pathlib import Path


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def write(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8")


def region_bounds(
    path: Path,
    text: str,
    start_marker: str,
    end_marker: str,
) -> tuple[int, int]:
    if text.count(start_marker) != 1:
        raise SystemExit(
            f"{path}: region start count={text.count(start_marker)}, expected 1: {start_marker!r}",
        )
    if text.count(end_marker) != 1:
        raise SystemExit(
            f"{path}: region end count={text.count(end_marker)}, expected 1: {end_marker!r}",
        )
    start = text.index(start_marker)
    end = text.index(end_marker, start + len(start_marker))
    if end <= start:
        raise SystemExit(f"{path}: invalid patch region ordering")
    return start, end


def replace_once_in_region(
    path: Path,
    start_marker: str,
    end_marker: str,
    old: str,
    new: str,
) -> None:
    text = read(path)
    start, end = region_bounds(path, text, start_marker, end_marker)
    region = text[start:end]
    count = region.count(old)
    if count != 1:
        raise SystemExit(
            f"{path}: anchor count={count}, expected 1 inside {start_marker!r}: {old!r}",
        )
    region = region.replace(old, new, 1)
    write(path, text[:start] + region + text[end:])


def insert_before_once_in_region(
    path: Path,
    start_marker: str,
    end_marker: str,
    anchor: str,
    addition: str,
) -> None:
    replace_once_in_region(
        path=path,
        start_marker=start_marker,
        end_marker=end_marker,
        old=anchor,
        new=addition + anchor,
    )


def require_exact_count(path: Path, needle: str, expected: int = 1) -> None:
    count = read(path).count(needle)
    if count != expected:
        raise SystemExit(
            f"{path}: patched marker count={count}, expected {expected}: {needle!r}",
        )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("sherpa_root", type=Path)
    args = parser.parse_args()
    root = args.sherpa_root.resolve()

    kotlin = root / "android/SherpaOnnxAar/sherpa_onnx/src/main/java/com/k2fsa/sherpa/onnx/OfflineRecognizer.kt"
    header = root / "sherpa-onnx/csrc/offline-funasr-nano-model-config.h"
    config_cc = root / "sherpa-onnx/csrc/offline-funasr-nano-model-config.cc"
    jni = root / "sherpa-onnx/jni/offline-recognizer.cc"

    for path in (kotlin, header, config_cc, jni):
        if not path.is_file():
            raise SystemExit(f"missing pinned sherpa source: {path}")

    # Kotlin ABI: scope to OfflineFunAsrNanoModelConfig because other model
    # configs in this file also have a tokenizer field.
    replace_once_in_region(
        kotlin,
        "data class OfflineFunAsrNanoModelConfig(\n",
        "data class OfflineQwen3AsrModelConfig(\n",
        '    var tokenizer: String = "",\n',
        '    var tokenizer: String = "",\n'
        '    // Optional Stage 16B revision-2 compact CTC scorer. Empty preserves stock behavior.\n'
        '    var ctcCompact: String = "",\n',
    )

    # C++ config ABI.
    replace_once_in_region(
        header,
        "struct OfflineFunASRNanoModelConfig {\n",
        "  OfflineFunASRNanoModelConfig() = default;\n",
        "  std::string tokenizer;\n",
        "  std::string tokenizer;\n\n"
        "  // Optional Stage 16B compact CTC scorer. Empty preserves revision 1.\n"
        "  std::string ctc_compact;\n",
    )

    insert_before_once_in_region(
        config_cc,
        "void OfflineFunASRNanoModelConfig::Register(ParseOptions *po) {\n",
        "bool OfflineFunASRNanoModelConfig::Validate() const {\n",
        '  po->Register("funasr-nano-system-prompt", &system_prompt,\n',
        '  po->Register("funasr-nano-ctc-compact", &ctc_compact,\n'
        '               "Optional compact CTC scorer for FunASR-nano revision 2");\n\n',
    )

    # Optional means revision 1 remains valid. Revision 2 app-side validation
    # requires the file and injects this path before recognizer construction.
    insert_before_once_in_region(
        config_cc,
        "bool OfflineFunASRNanoModelConfig::Validate() const {\n",
        "std::string OfflineFunASRNanoModelConfig::ToString() const {\n",
        "  if (max_new_tokens <= 0) {\n",
        '  if (!ctc_compact.empty() && !FileExists(ctc_compact)) {\n'
        '    SHERPA_ONNX_LOGE("--funasr-nano-ctc-compact: \'%s\' does not exist",\n'
        '                     ctc_compact.c_str());\n'
        '    return false;\n'
        '  }\n\n',
    )

    insert_before_once_in_region(
        config_cc,
        "std::string OfflineFunASRNanoModelConfig::ToString() const {\n",
        "}  // namespace sherpa_onnx\n",
        '  os << "system_prompt=\\\"" << system_prompt << "\\\", ";\n',
        '  os << "ctc_compact=\\\"" << ctc_compact << "\\\", ";\n',
    )

    # JNI: scope to the FunASR block so field reads for other models cannot
    # satisfy the anchor accidentally.
    insert_before_once_in_region(
        jni,
        "  // FunASR Nano\n",
        "  fid = env->GetFieldID(model_config_cls, \"qwen3Asr\",\n",
        "  SHERPA_ONNX_JNI_READ_STRING(ans.model_config.funasr_nano.system_prompt,\n",
        "  SHERPA_ONNX_JNI_READ_STRING(ans.model_config.funasr_nano.ctc_compact,\n"
        "                              ctcCompact, funasr_nano_config_cls,\n"
        "                              funasr_nano_config);\n\n",
    )

    # Exact deterministic postconditions. A future upstream field or duplicate
    # patch fails rather than producing an ambiguous ABI.
    require_exact_count(kotlin, 'var ctcCompact: String = ""')
    require_exact_count(header, "std::string ctc_compact;")
    require_exact_count(config_cc, 'po->Register("funasr-nano-ctc-compact"')
    require_exact_count(config_cc, "!ctc_compact.empty()")
    require_exact_count(config_cc, 'os << "ctc_compact=\\\""')
    require_exact_count(jni, "ans.model_config.funasr_nano.ctc_compact")
    require_exact_count(jni, "ctcCompact, funasr_nano_config_cls")

    print("Applied Stage 16B FunASR CTC A0 config ABI patch")


if __name__ == "__main__":
    main()
