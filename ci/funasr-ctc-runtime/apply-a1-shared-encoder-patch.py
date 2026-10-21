#!/usr/bin/env python3
"""Apply Stage 16B FunASR CTC A1 shared-encoder retained-state patch.

Run after apply-a0-config-patch.py against exact sherpa-onnx v1.13.8.
A1 deliberately does not load or execute the compact CTC scorer yet. It only:
- exposes dual-output encoder results when ctc_compact is configured;
- retains pre-adaptor encoder state + exact lengths per OfflineStream;
- consumes/discards that state through the existing stream option + decode ABI;
- never reruns encoder/LLM for align/discard commands.
"""

from __future__ import annotations

import argparse
from pathlib import Path


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def write(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8")


def replace_once(path: Path, old: str, new: str) -> None:
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: anchor count={count}, expected 1: {old!r}")
    write(path, text.replace(old, new, 1))


def insert_before_once(path: Path, anchor: str, addition: str) -> None:
    replace_once(path, anchor, addition + anchor)


def insert_after_once(path: Path, anchor: str, addition: str) -> None:
    replace_once(path, anchor, anchor + addition)


def replace_region(path: Path, start_marker: str, end_marker: str, replacement: str) -> None:
    text = read(path)
    if text.count(start_marker) != 1:
        raise SystemExit(f"{path}: start marker count={text.count(start_marker)}, expected 1")
    start = text.index(start_marker)
    end = text.find(end_marker, start + len(start_marker))
    if end < 0:
        raise SystemExit(f"{path}: end marker missing: {end_marker!r}")
    write(path, text[:start] + replacement + text[end:])


def require_exact_count(path: Path, needle: str, expected: int = 1) -> None:
    count = read(path).count(needle)
    if count != expected:
        raise SystemExit(f"{path}: patched marker count={count}, expected {expected}: {needle!r}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("sherpa_root", type=Path)
    args = parser.parse_args()
    root = args.sherpa_root.resolve()

    model_h = root / "sherpa-onnx/csrc/offline-funasr-nano-model.h"
    model_cc = root / "sherpa-onnx/csrc/offline-funasr-nano-model.cc"
    recognizer_h = root / "sherpa-onnx/csrc/offline-recognizer-funasr-nano-impl.h"
    recognizer_cc = root / "sherpa-onnx/csrc/offline-recognizer-funasr-nano-impl.cc"

    for path in (model_h, model_cc, recognizer_h, recognizer_cc):
        if not path.is_file():
            raise SystemExit(f"missing pinned sherpa source: {path}")

    config_h = root / "sherpa-onnx/csrc/offline-funasr-nano-model-config.h"
    if "std::string ctc_compact;" not in read(config_h):
        raise SystemExit("A1 requires the A0 ctc_compact config ABI patch first")

    insert_before_once(
        model_h,
        "class OfflineFunASRNanoModel {\n",
        "struct OfflineFunASRNanoEncoderOutputs {\n"
        "  Ort::Value llm_embedding{nullptr};\n"
        "  Ort::Value ctc_encoder_out{nullptr};\n"
        "  Ort::Value ctc_encoder_out_lens{nullptr};\n"
        "};\n\n",
    )
    insert_after_once(
        model_h,
        "  Ort::Value ForwardEncoderAdaptor(Ort::Value features);\n",
        "\n"
        "  /** Run the revision-2 dual-output encoder once and retain the exact\n"
        "   * pre-adaptor state for the CTC branch. Only valid when ctc_compact\n"
        "   * is configured.\n"
        "   */\n"
        "  OfflineFunASRNanoEncoderOutputs ForwardEncoderAdaptorWithCtc(\n"
        "      Ort::Value features);\n",
    )
    insert_after_once(
        model_h,
        "  bool HasEmbeddingModel() const;\n",
        "\n  /** Return true when the optional revision-2 compact CTC path is enabled. */\n"
        "  bool HasCtcCompact() const;\n",
    )

    insert_before_once(
        model_cc,
        "  std::vector<std::pair<Ort::Value, Ort::Value>> CreateEmptyKVCache(\n",
        r'''  OfflineFunASRNanoEncoderOutputs ForwardEncoderAdaptorWithCtc(
      Ort::Value features) {
    if (encoder_output_names_.size() != 3u ||
        encoder_output_names_[0] != "encoder_out" ||
        encoder_output_names_[1] != "ctc_encoder_out" ||
        encoder_output_names_[2] != "ctc_encoder_out_lens") {
      SHERPA_ONNX_LOGE(
          "FunASR revision-2 encoder must expose exactly "
          "[encoder_out, ctc_encoder_out, ctc_encoder_out_lens]");
      SHERPA_ONNX_EXIT(-1);
    }

    if (NeedsTypeConversion(features, encoder_in_type_)) {
      features = CastFloatLikeForExpected(std::move(features), encoder_in_type_,
                                          allocator_);
    }

    std::vector<Ort::Value> outputs;
    if (use_cuda_iobinding_) {
      Ort::IoBinding binding(*encoder_sess_);
      binding.BindInput(encoder_input_names_ptr_[0], features);
      for (size_t i = 0; i < 3; ++i) {
        binding.BindOutput(encoder_output_names_ptr_[i], cpu_mem_info_);
      }
      binding.SynchronizeInputs();
      encoder_sess_->Run(Ort::RunOptions{nullptr}, binding);
      binding.SynchronizeOutputs();
      outputs = binding.GetOutputValues();
    } else {
      std::array<Ort::Value, 1> inputs = {std::move(features)};
      outputs = encoder_sess_->Run(
          {}, encoder_input_names_ptr_.data(), inputs.data(), inputs.size(),
          encoder_output_names_ptr_.data(), 3);
    }

    if (outputs.size() != 3u) {
      SHERPA_ONNX_LOGE("ForwardEncoderAdaptorWithCtc: expected 3 outputs, got %zu",
                       outputs.size());
      SHERPA_ONNX_EXIT(-1);
    }

    if (!outputs[1].IsTensor() || !outputs[2].IsTensor()) {
      SHERPA_ONNX_LOGE(
          "ForwardEncoderAdaptorWithCtc: CTC state/length outputs must be tensors");
      SHERPA_ONNX_EXIT(-1);
    }
    auto ctc_info = outputs[1].GetTensorTypeAndShapeInfo();
    auto ctc_shape = ctc_info.GetShape();
    auto lens_info = outputs[2].GetTensorTypeAndShapeInfo();
    auto lens_shape = lens_info.GetShape();
    if (ctc_shape.size() != 3u || ctc_shape[0] != 1 || ctc_shape[1] <= 0 ||
        ctc_shape[2] != 512 || lens_shape.size() != 1u ||
        lens_shape[0] != 1 ||
        lens_info.GetElementType() != ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64) {
      SHERPA_ONNX_LOGE(
          "ForwardEncoderAdaptorWithCtc: invalid CTC state/length shape or type");
      SHERPA_ONNX_EXIT(-1);
    }
    const int64_t valid_len = outputs[2].GetTensorData<int64_t>()[0];
    if (valid_len <= 0 || valid_len > ctc_shape[1]) {
      SHERPA_ONNX_LOGE(
          "ForwardEncoderAdaptorWithCtc: invalid CTC valid length=%lld for T=%lld",
          static_cast<long long>(valid_len),
          static_cast<long long>(ctc_shape[1]));
      SHERPA_ONNX_EXIT(-1);
    }

    OfflineFunASRNanoEncoderOutputs ans;
    ans.llm_embedding = std::move(outputs[0]);
    ans.ctc_encoder_out = std::move(outputs[1]);
    ans.ctc_encoder_out_lens = std::move(outputs[2]);
    return ans;
  }

''',
    )
    replace_once(
        model_cc,
        "  bool HasEmbeddingModel() const { return has_embedding_model_; }\n",
        "  bool HasEmbeddingModel() const { return has_embedding_model_; }\n"
        "  bool HasCtcCompact() const {\n"
        "    return !config_.funasr_nano.ctc_compact.empty();\n"
        "  }\n",
    )
    insert_after_once(
        model_cc,
        "Ort::Value OfflineFunASRNanoModel::ForwardEncoderAdaptor(Ort::Value features) {\n"
        "  return impl_->ForwardEncoderAdaptor(std::move(features));\n"
        "}\n",
        "\nOfflineFunASRNanoEncoderOutputs\n"
        "OfflineFunASRNanoModel::ForwardEncoderAdaptorWithCtc(Ort::Value features) {\n"
        "  return impl_->ForwardEncoderAdaptorWithCtc(std::move(features));\n"
        "}\n",
    )
    insert_after_once(
        model_cc,
        "bool OfflineFunASRNanoModel::HasEmbeddingModel() const {\n"
        "  return impl_->HasEmbeddingModel();\n"
        "}\n",
        "\nbool OfflineFunASRNanoModel::HasCtcCompact() const {\n"
        "  return impl_->HasCtcCompact();\n"
        "}\n",
    )

    insert_after_once(recognizer_h, "#include <algorithm>\n", "#include <cstdint>\n")
    insert_after_once(recognizer_h, "#include <utility>\n", "#include <unordered_map>\n")
    insert_before_once(
        recognizer_h,
        "  void InitFeatConfig();\n",
        r'''  struct RetainedCtcState {
    uint64_t state_id = 0;
    Ort::Value encoder_out{nullptr};
    Ort::Value encoder_out_lens{nullptr};

    RetainedCtcState() = default;
    RetainedCtcState(uint64_t id, Ort::Value out, Ort::Value lens)
        : state_id(id),
          encoder_out(std::move(out)),
          encoder_out_lens(std::move(lens)) {}
  };

  void RetainCtcState(OfflineStream *stream, Ort::Value encoder_out,
                      Ort::Value encoder_out_lens) const;
  void DiscardRetainedCtcState(OfflineStream *stream) const;
  bool ConsumeRetainedCtcState(OfflineStream *stream,
                               RetainedCtcState *state) const;

''',
    )
    insert_after_once(
        recognizer_h,
        "  mutable std::mutex rng_mutex_;\n",
        "\n"
        "  // Revision-2 CTC state is scoped to an OfflineStream and protected\n"
        "  // independently from RNG sampling. State IDs prevent stale pointer\n"
        "  // reuse from consuming the wrong encoder tensor.\n"
        "  mutable std::mutex ctc_state_mutex_;\n"
        "  mutable std::unordered_map<OfflineStream *, RetainedCtcState>\n"
        "      retained_ctc_states_;\n"
        "  mutable uint64_t next_ctc_state_id_ = 1;\n",
    )

    insert_after_once(recognizer_cc, "#include <algorithm>\n", "#include <cerrno>\n#include <cstdlib>\n")
    insert_after_once(
        recognizer_cc,
        "namespace {\n",
        r'''constexpr const char *kVoicaCtcCommandKey = "voica.funasr.ctc.command";
constexpr const char *kVoicaCtcStateIdKey = "voica.funasr.ctc.state_id";
constexpr const char *kVoicaCtcAlignCommand = "align";
constexpr const char *kVoicaCtcDiscardCommand = "discard";
constexpr size_t kVoicaMaxRetainedCtcStates = 8;

static bool ParseVoicaCtcStateId(const std::string &text, uint64_t *value) {
  if (!value || text.empty()) return false;
  errno = 0;
  char *end = nullptr;
  unsigned long long parsed = std::strtoull(text.c_str(), &end, 10);
  if (errno != 0 || end == text.c_str() || *end != '\0' || parsed == 0) {
    return false;
  }
  *value = static_cast<uint64_t>(parsed);
  return true;
}

''',
    )

    insert_before_once(
        recognizer_cc,
        "// Decode multiple audio streams in batch.\n",
        r'''void OfflineRecognizerFunASRNanoImpl::DiscardRetainedCtcState(
    OfflineStream *stream) const {
  if (!stream) return;
  {
    std::lock_guard<std::mutex> lock(ctc_state_mutex_);
    retained_ctc_states_.erase(stream);
  }
  stream->SetOption(kVoicaCtcStateIdKey, "");
}

void OfflineRecognizerFunASRNanoImpl::RetainCtcState(
    OfflineStream *stream, Ort::Value encoder_out,
    Ort::Value encoder_out_lens) const {
  if (!stream) return;
  std::lock_guard<std::mutex> lock(ctc_state_mutex_);

  retained_ctc_states_.erase(stream);
  if (retained_ctc_states_.size() >= kVoicaMaxRetainedCtcStates) {
    // Correctness over availability: stale/concurrent states fail closed
    // rather than allowing an unbounded native tensor cache.
    retained_ctc_states_.clear();
  }

  if (next_ctc_state_id_ == 0) next_ctc_state_id_ = 1;
  const uint64_t state_id = next_ctc_state_id_++;
  if (next_ctc_state_id_ == 0) next_ctc_state_id_ = 1;

  retained_ctc_states_.emplace(
      stream, RetainedCtcState(state_id, std::move(encoder_out),
                               std::move(encoder_out_lens)));
  stream->SetOption(kVoicaCtcStateIdKey, std::to_string(state_id));
}

bool OfflineRecognizerFunASRNanoImpl::ConsumeRetainedCtcState(
    OfflineStream *stream, RetainedCtcState *state) const {
  if (!stream || !state) return false;

  uint64_t requested_state_id = 0;
  const bool has_valid_id = ParseVoicaCtcStateId(
      stream->GetOption(kVoicaCtcStateIdKey), &requested_state_id);

  std::lock_guard<std::mutex> lock(ctc_state_mutex_);
  auto it = retained_ctc_states_.find(stream);
  if (!has_valid_id || it == retained_ctc_states_.end() ||
      it->second.state_id != requested_state_id) {
    if (it != retained_ctc_states_.end()) retained_ctc_states_.erase(it);
    stream->SetOption(kVoicaCtcStateIdKey, "");
    return false;
  }

  *state = std::move(it->second);
  retained_ctc_states_.erase(it);
  stream->SetOption(kVoicaCtcStateIdKey, "");
  return true;
}

''',
    )

    decode_start = "// Decode multiple audio streams in batch.\n"
    decode_end = "#if __ANDROID_API__ >= 9\n"
    replacement = r'''// Decode multiple audio streams in batch.
// Applies LFR processing, runs encoder, and generates text for each stream.
void OfflineRecognizerFunASRNanoImpl::DecodeStreams(OfflineStream **ss,
                                                    int32_t n) const {
  auto memory_info =
      Ort::MemoryInfo::CreateCpu(OrtDeviceAllocator, OrtMemTypeDefault);
  const auto &funasr_config = config_.model_config.funasr_nano;
  for (int32_t i = 0; i != n; ++i) {
    const std::string command = ss[i]->GetOption(kVoicaCtcCommandKey);
    if (!command.empty()) {
      OfflineRecognitionResult command_result;
      if (command == kVoicaCtcDiscardCommand) {
        DiscardRetainedCtcState(ss[i]);
      } else if (command == kVoicaCtcAlignCommand) {
        RetainedCtcState retained;
        const bool consumed = ConsumeRetainedCtcState(ss[i], &retained);
        if (config_.model_config.debug) {
          SHERPA_ONNX_LOGE(
              "DecodeStreams: A1 align command consumed retained state=%d; "
              "compact scorer is not enabled until A2",
              consumed ? 1 : 0);
        }
        // A1 proves lifecycle only. Returning an empty result is deliberate:
        // no synthetic timestamp or placeholder alignment may escape.
      } else {
        if (config_.model_config.debug) {
          SHERPA_ONNX_LOGE("DecodeStreams: unknown Voica CTC command: %s",
                           command.c_str());
        }
        DiscardRetainedCtcState(ss[i]);
      }
      ss[i]->SetOption(kVoicaCtcCommandKey, "");
      ss[i]->SetResult(command_result);
      continue;
    }

    // A fresh normal decode must never inherit a stale native tensor for a
    // recycled OfflineStream address.
    DiscardRetainedCtcState(ss[i]);

    std::vector<float> f = ss[i]->GetFrames();
    f = ApplyLFR(f);
    int32_t num_frames = static_cast<int32_t>(
        f.size() / (config_.feat_config.feature_dim * model_->LfrWindowSize()));
    if (num_frames <= 0) {
      OfflineRecognitionResult r;
      r.text = "";
      ss[i]->SetResult(r);
      continue;
    }

    if (FunASRNanoAudioIsSilent(f.data(), static_cast<int32_t>(f.size()))) {
      OfflineRecognitionResult r;
      ss[i]->SetResult(r);
      continue;
    }

    std::array<int64_t, 3> shape{1, num_frames,
                                 static_cast<int64_t>(f.size() / num_frames)};

    Ort::Value features = Ort::Value::CreateTensor<float>(
        memory_info, const_cast<float *>(f.data()), f.size(), shape.data(),
        shape.size());

    const bool retain_ctc_state = model_->HasCtcCompact();
    Ort::Value encoder_out{nullptr};
    Ort::Value ctc_encoder_out{nullptr};
    Ort::Value ctc_encoder_out_lens{nullptr};
    if (retain_ctc_state) {
      auto encoder_outputs =
          model_->ForwardEncoderAdaptorWithCtc(std::move(features));
      encoder_out = std::move(encoder_outputs.llm_embedding);
      ctc_encoder_out = std::move(encoder_outputs.ctc_encoder_out);
      ctc_encoder_out_lens =
          std::move(encoder_outputs.ctc_encoder_out_lens);
    } else {
      encoder_out = model_->ForwardEncoderAdaptor(std::move(features));
    }

    std::vector<std::string> hotwords =
        ParseHotwordsCsv(funasr_config.hotwords);
    const std::string *lang_ptr =
        funasr_config.language.empty() ? nullptr : &funasr_config.language;
    std::string user_prompt_dyn = BuildUserPrompt(
        hotwords, lang_ptr, funasr_config.itn, &funasr_config.user_prompt);

    if (config_.model_config.debug) {
      SHERPA_ONNX_LOGE(
          "DecodeStreams: hotwords=%zu, language=%s, itn=%d, retain_ctc=%d",
          hotwords.size(),
          funasr_config.language.empty() ? "(empty)"
                                         : funasr_config.language.c_str(),
          funasr_config.itn ? 1 : 0, retain_ctc_state ? 1 : 0);
      SHERPA_ONNX_LOGE("DecodeStreams: user_prompt_dyn=%s",
                       user_prompt_dyn.c_str());
    }

    OfflineRecognitionResult r = GenerateText(
        std::move(encoder_out), funasr_config.system_prompt, user_prompt_dyn);

    if (retain_ctc_state && !r.text.empty()) {
      RetainCtcState(ss[i], std::move(ctc_encoder_out),
                     std::move(ctc_encoder_out_lens));
    }

    ss[i]->SetResult(r);
  }
}

'''
    replace_region(recognizer_cc, decode_start, decode_end, replacement)

    require_exact_count(model_h, "struct OfflineFunASRNanoEncoderOutputs")
    require_exact_count(model_h, "ForwardEncoderAdaptorWithCtc")
    require_exact_count(model_h, "HasCtcCompact")
    require_exact_count(model_cc, "ForwardEncoderAdaptorWithCtc", expected=4)
    require_exact_count(model_cc, "HasCtcCompact", expected=3)
    require_exact_count(recognizer_h, "struct RetainedCtcState")
    require_exact_count(recognizer_h, "retained_ctc_states_")
    require_exact_count(recognizer_cc, 'kVoicaCtcCommandKey = "voica.funasr.ctc.command"')
    require_exact_count(recognizer_cc, "RetainCtcState(", expected=2)
    require_exact_count(recognizer_cc, "ConsumeRetainedCtcState(", expected=2)
    require_exact_count(recognizer_cc, "ForwardEncoderAdaptorWithCtc")
    require_exact_count(recognizer_cc, "A1 align command consumed retained state")

    print("Applied Stage 16B FunASR CTC A1 shared-encoder retained-state patch")


if __name__ == "__main__":
    main()
