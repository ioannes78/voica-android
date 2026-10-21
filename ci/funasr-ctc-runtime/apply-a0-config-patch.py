#!/usr/bin/env python3
"""Apply the Stage 16B FunASR CTC A0 config-ABI patch to sherpa-onnx v1.13.8.

This gate deliberately patches only the optional `ctcCompact` model path through
Kotlin -> JNI -> C++. Empty keeps stock revision-1 behavior. Shared-encoder state,
compact scoring and second-decode alignment are layered only after this exact
runtime/toolchain boundary compiles cleanly.
"""

from __future__ import annotations

import argparse
from pathlib import Path


def replace_once(path: Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected exactly one patch anchor, found {count}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


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

    replace_once(
        kotlin,
        '    var tokenizer: String = "",\n    var systemPrompt: String = "You are a helpful assistant.",\n',
        '    var tokenizer: String = "",\n    // Optional Stage 16B revision-2 compact CTC scorer. Empty preserves stock behavior.\n    var ctcCompact: String = "",\n    var systemPrompt: String = "You are a helpful assistant.",\n',
    )

    replace_once(
        header,
        '  // Path to tokenizer directory (e.g., Qwen3-0.6B)\n  std::string tokenizer;\n\n  // System prompt\n',
        '  // Path to tokenizer directory (e.g., Qwen3-0.6B)\n  std::string tokenizer;\n\n  // Optional path to Stage 16B compact CTC scorer. Empty preserves revision 1.\n  std::string ctc_compact;\n\n  // System prompt\n',
    )

    replace_once(
        config_cc,
        '  po->Register(\n      "funasr-nano-tokenizer", &tokenizer,\n      "Path to tokenizer directory (e.g., Qwen3-0.6B) for FunASR-nano");\n\n  po->Register("funasr-nano-system-prompt", &system_prompt,\n',
        '  po->Register(\n      "funasr-nano-tokenizer", &tokenizer,\n      "Path to tokenizer directory (e.g., Qwen3-0.6B) for FunASR-nano");\n\n  po->Register("funasr-nano-ctc-compact", &ctc_compact,\n               "Optional compact CTC scorer for FunASR-nano revision 2");\n\n  po->Register("funasr-nano-system-prompt", &system_prompt,\n',
    )

    replace_once(
        config_cc,
        '  if (!FileExists(embedding)) {\n    SHERPA_ONNX_LOGE("--funasr-nano-embedding: \'%s\' does not exist", embedding.c_str());\n    return false;\n  }\n\n  if (max_new_tokens <= 0) {\n',
        '  if (!FileExists(embedding)) {\n    SHERPA_ONNX_LOGE("--funasr-nano-embedding: \'%s\' does not exist", embedding.c_str());\n    return false;\n  }\n\n  if (!ctc_compact.empty() && !FileExists(ctc_compact)) {\n    SHERPA_ONNX_LOGE("--funasr-nano-ctc-compact: \'%s\' does not exist",\n                     ctc_compact.c_str());\n    return false;\n  }\n\n  if (max_new_tokens <= 0) {\n',
    )

    replace_once(
        config_cc,
        '  os << "tokenizer=\\\"" << tokenizer << "\\\", ";\n  os << "system_prompt=\\\"" << system_prompt << "\\\", ";\n',
        '  os << "tokenizer=\\\"" << tokenizer << "\\\", ";\n  os << "ctc_compact=\\\"" << ctc_compact << "\\\", ";\n  os << "system_prompt=\\\"" << system_prompt << "\\\", ";\n',
    )

    replace_once(
        jni,
        '  SHERPA_ONNX_JNI_READ_STRING(ans.model_config.funasr_nano.tokenizer, tokenizer,\n                              funasr_nano_config_cls, funasr_nano_config);\n\n  SHERPA_ONNX_JNI_READ_STRING(ans.model_config.funasr_nano.system_prompt,\n',
        '  SHERPA_ONNX_JNI_READ_STRING(ans.model_config.funasr_nano.tokenizer, tokenizer,\n                              funasr_nano_config_cls, funasr_nano_config);\n\n  SHERPA_ONNX_JNI_READ_STRING(ans.model_config.funasr_nano.ctc_compact, ctcCompact,\n                              funasr_nano_config_cls, funasr_nano_config);\n\n  SHERPA_ONNX_JNI_READ_STRING(ans.model_config.funasr_nano.system_prompt,\n',
    )

    # Deterministic postconditions. These are intentionally simple so upstream drift fails closed.
    checks = {
        kotlin: ["var ctcCompact: String = \"\""],
        header: ["std::string ctc_compact;"],
        config_cc: ["funasr-nano-ctc-compact", "ctc_compact.c_str()"],
        jni: ["funasr_nano.ctc_compact, ctcCompact"],
    }
    for path, needles in checks.items():
        text = path.read_text(encoding="utf-8")
        for needle in needles:
            if needle not in text:
                raise SystemExit(f"{path}: missing patched ABI marker: {needle}")

    print("Applied Stage 16B FunASR CTC A0 config ABI patch")


if __name__ == "__main__":
    main()
