#!/usr/bin/env python3
"""Apply Stage 16B FunASR CTC A2 compact scorer + native Viterbi patch.

Run after A0 + A1 against exact sherpa-onnx v1.13.8. A2 consumes the retained
pre-adaptor encoder state on the second same-stream decode, runs the compact
CTC scorer once, and returns trusted target spans through the existing
OfflineRecognitionResult tokens/timestamps/durations ABI.
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


def replace_exact_count(path: Path, old: str, new: str, expected: int) -> None:
    text = read(path)
    count = text.count(old)
    if count != expected:
        raise SystemExit(f"{path}: anchor count={count}, expected {expected}: {old!r}")
    write(path, text.replace(old, new))


def insert_before_once(path: Path, anchor: str, addition: str) -> None:
    replace_once(path, anchor, addition + anchor)


def insert_after_once(path: Path, anchor: str, addition: str) -> None:
    replace_once(path, anchor, anchor + addition)


def region_bounds(path: Path, text: str, start_marker: str, end_marker: str) -> tuple[int, int]:
    if text.count(start_marker) != 1:
        raise SystemExit(f"{path}: region start count={text.count(start_marker)}, expected 1")
    start = text.index(start_marker)
    end = text.find(end_marker, start + len(start_marker))
    if end < 0:
        raise SystemExit(f"{path}: region end missing: {end_marker!r}")
    return start, end


def insert_after_once_in_region(
    path: Path,
    start_marker: str,
    end_marker: str,
    anchor: str,
    addition: str,
) -> None:
    text = read(path)
    start, end = region_bounds(path, text, start_marker, end_marker)
    region = text[start:end]
    count = region.count(anchor)
    if count != 1:
        raise SystemExit(f"{path}: regional anchor count={count}, expected 1: {anchor!r}")
    region = region.replace(anchor, anchor + addition, 1)
    write(path, text[:start] + region + text[end:])


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
    recognizer_cc = root / "sherpa-onnx/csrc/offline-recognizer-funasr-nano-impl.cc"
    for path in (model_h, model_cc, recognizer_cc):
        if not path.is_file():
            raise SystemExit(f"missing pinned sherpa source: {path}")
    if "ForwardEncoderAdaptorWithCtc" not in read(model_h) or "RetainedCtcState" not in read(recognizer_cc):
        raise SystemExit("A2 requires the A1 shared-encoder patch first")

    insert_after_once(
        model_h,
        "  bool HasCtcCompact() const;\n",
        "\n"
        "  /** Run compact CTC decoder/projection for blank + target positions. */\n"
        "  Ort::Value ForwardCompactCtc(\n"
        "      Ort::Value encoder_out, Ort::Value encoder_out_lens,\n"
        "      const std::vector<int64_t> &candidate_ids);\n",
    )

    replace_exact_count(
        model_cc,
        "        sess_opts_embedding_(GetSessionOptions(config)),\n",
        "        sess_opts_embedding_(GetSessionOptions(config)),\n"
        "        sess_opts_ctc_(GetSessionOptions(config)),\n",
        expected=2,
    )
    insert_after_once_in_region(
        model_cc,
        "  explicit Impl(const OfflineModelConfig &config)\n",
        "  void InitEncoderAdaptorFromMemory(void *model_data,\n",
        "    has_embedding_model_ = true;\n",
        "    if (!c.ctc_compact.empty()) InitCtcCompact(c.ctc_compact);\n",
    )
    insert_after_once_in_region(
        model_cc,
        "  template <typename Manager>\n  Impl(Manager *mgr, const OfflineModelConfig &config)\n",
        "  // Forward pass through encoder adaptor model.\n",
        "    has_embedding_model_ = true;\n",
        "    if (!c.ctc_compact.empty()) {\n"
        "      auto buf_ctc = ReadFile(mgr, c.ctc_compact);\n"
        "      InitCtcCompactFromMemory(buf_ctc.data(), buf_ctc.size());\n"
        "    }\n",
    )

    insert_before_once(
        model_cc,
        "  void InitEmbeddingFromMemory(void *model_data, size_t model_data_length) {\n",
        r'''  void SetupCtcCompactFromSession() {
    GetInputNames(ctc_sess_.get(), &ctc_input_names_, &ctc_input_names_ptr_);
    GetOutputNames(ctc_sess_.get(), &ctc_output_names_, &ctc_output_names_ptr_);
    if (ctc_input_names_.size() != 3u || ctc_output_names_.size() != 1u ||
        ctc_input_names_[0] != "ctc_encoder_out" ||
        ctc_input_names_[1] != "ctc_encoder_out_lens" ||
        ctc_input_names_[2] != "candidate_ids" ||
        ctc_output_names_[0] != "candidate_logits") {
      SHERPA_ONNX_LOGE("FunASR compact CTC scorer ABI mismatch");
      SHERPA_ONNX_EXIT(-1);
    }
    ctc_encoder_in_type_ = GetSessionInputElemType(ctc_sess_.get(), 0);
    auto lens_type = GetSessionInputElemType(ctc_sess_.get(), 1);
    auto ids_type = GetSessionInputElemType(ctc_sess_.get(), 2);
    auto out_info = ctc_sess_->GetOutputTypeInfo(0).GetTensorTypeAndShapeInfo();
    if ((ctc_encoder_in_type_ != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT &&
         ctc_encoder_in_type_ != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT16) ||
        lens_type != ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64 ||
        ids_type != ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64 ||
        out_info.GetElementType() != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT) {
      SHERPA_ONNX_LOGE("FunASR compact CTC scorer tensor types unsupported");
      SHERPA_ONNX_EXIT(-1);
    }
  }

  void InitCtcCompactFromMemory(void *model_data, size_t model_data_length) {
    ctc_sess_ = std::make_unique<Ort::Session>(
        env_, model_data, model_data_length, sess_opts_ctc_);
    SetupCtcCompactFromSession();
  }

''',
    )
    insert_before_once(
        model_cc,
        "  void InitEmbedding(const std::string &model_path) {\n",
        r'''  void InitCtcCompact(const std::string &model_path) {
    ctc_sess_ = std::make_unique<Ort::Session>(
        env_, SHERPA_ONNX_TO_ORT_PATH(model_path), sess_opts_ctc_);
    SetupCtcCompactFromSession();
  }

''',
    )
    insert_before_once(
        model_cc,
        "  std::vector<std::pair<Ort::Value, Ort::Value>> CreateEmptyKVCache(\n",
        r'''  Ort::Value ForwardCompactCtc(
      Ort::Value encoder_out, Ort::Value encoder_out_lens,
      const std::vector<int64_t> &candidate_ids) {
    if (!ctc_sess_ || candidate_ids.empty()) {
      SHERPA_ONNX_LOGE("ForwardCompactCtc: scorer missing or candidates empty");
      SHERPA_ONNX_EXIT(-1);
    }
    if (NeedsTypeConversion(encoder_out, ctc_encoder_in_type_)) {
      encoder_out = CastFloatLikeForExpected(
          std::move(encoder_out), ctc_encoder_in_type_, allocator_);
    }
    if (!encoder_out_lens.IsTensor() ||
        encoder_out_lens.GetTensorTypeAndShapeInfo().GetElementType() !=
            ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64) {
      SHERPA_ONNX_LOGE("ForwardCompactCtc: encoder_out_lens must be int64");
      SHERPA_ONNX_EXIT(-1);
    }
    std::array<int64_t, 1> candidate_shape{
        static_cast<int64_t>(candidate_ids.size())};
    Ort::Value candidate_tensor = Ort::Value::CreateTensor<int64_t>(
        cpu_mem_info_, const_cast<int64_t *>(candidate_ids.data()),
        candidate_ids.size(), candidate_shape.data(), candidate_shape.size());
    std::array<Ort::Value, 3> inputs = {
        std::move(encoder_out), std::move(encoder_out_lens),
        std::move(candidate_tensor)};
    std::vector<Ort::Value> outputs;
    if (use_cuda_iobinding_) {
      Ort::IoBinding binding(*ctc_sess_);
      for (size_t i = 0; i < inputs.size(); ++i) {
        binding.BindInput(ctc_input_names_ptr_[i], inputs[i]);
      }
      binding.BindOutput(ctc_output_names_ptr_[0], cpu_mem_info_);
      binding.SynchronizeInputs();
      ctc_sess_->Run(Ort::RunOptions{nullptr}, binding);
      binding.SynchronizeOutputs();
      outputs = binding.GetOutputValues();
    } else {
      outputs = ctc_sess_->Run(
          {}, ctc_input_names_ptr_.data(), inputs.data(), inputs.size(),
          ctc_output_names_ptr_.data(), ctc_output_names_ptr_.size());
    }
    if (outputs.size() != 1u || !outputs[0].IsTensor()) {
      SHERPA_ONNX_LOGE("ForwardCompactCtc: expected one tensor output");
      SHERPA_ONNX_EXIT(-1);
    }
    auto info = outputs[0].GetTensorTypeAndShapeInfo();
    auto shape = info.GetShape();
    if (info.GetElementType() != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT ||
        shape.size() != 3u || shape[0] != 1 || shape[1] <= 0 ||
        shape[2] != static_cast<int64_t>(candidate_ids.size())) {
      SHERPA_ONNX_LOGE("ForwardCompactCtc: invalid candidate_logits shape/type");
      SHERPA_ONNX_EXIT(-1);
    }
    return std::move(outputs[0]);
  }

''',
    )

    replace_once(
        model_cc,
        "  Ort::SessionOptions sess_opts_embedding_;\n",
        "  Ort::SessionOptions sess_opts_embedding_;\n  Ort::SessionOptions sess_opts_ctc_;\n",
    )
    replace_once(
        model_cc,
        "  std::unique_ptr<Ort::Session> embedding_sess_;\n",
        "  std::unique_ptr<Ort::Session> embedding_sess_;\n  std::unique_ptr<Ort::Session> ctc_sess_;\n",
    )
    replace_once(
        model_cc,
        "  std::vector<const char *> embedding_output_names_ptr_;\n",
        "  std::vector<const char *> embedding_output_names_ptr_;\n\n"
        "  std::vector<std::string> ctc_input_names_;\n"
        "  std::vector<const char *> ctc_input_names_ptr_;\n"
        "  std::vector<std::string> ctc_output_names_;\n"
        "  std::vector<const char *> ctc_output_names_ptr_;\n",
    )
    replace_once(
        model_cc,
        "  ONNXTensorElementDataType llm_embeds_in_type_ =\n"
        "      ONNX_TENSOR_ELEMENT_DATA_TYPE_UNDEFINED;\n",
        "  ONNXTensorElementDataType llm_embeds_in_type_ =\n"
        "      ONNX_TENSOR_ELEMENT_DATA_TYPE_UNDEFINED;\n"
        "  ONNXTensorElementDataType ctc_encoder_in_type_ =\n"
        "      ONNX_TENSOR_ELEMENT_DATA_TYPE_UNDEFINED;\n",
    )
    insert_after_once(
        model_cc,
        "bool OfflineFunASRNanoModel::HasCtcCompact() const {\n"
        "  return impl_->HasCtcCompact();\n"
        "}\n",
        "\nOrt::Value OfflineFunASRNanoModel::ForwardCompactCtc(\n"
        "    Ort::Value encoder_out, Ort::Value encoder_out_lens,\n"
        "    const std::vector<int64_t> &candidate_ids) {\n"
        "  return impl_->ForwardCompactCtc(\n"
        "      std::move(encoder_out), std::move(encoder_out_lens), candidate_ids);\n"
        "}\n",
    )

    insert_after_once(
        recognizer_cc,
        'constexpr const char *kVoicaCtcStateIdKey = "voica.funasr.ctc.state_id";\n',
        'constexpr const char *kVoicaCtcTargetIdsKey = "voica.funasr.ctc.target_ids";\n'
        'constexpr const char *kVoicaCtcBlankIdKey = "voica.funasr.ctc.blank_id";\n'
        'constexpr const char *kVoicaCtcVocabSizeKey = "voica.funasr.ctc.vocab_size";\n'
        'constexpr float kVoicaFunAsrCtcFrameSeconds = 0.06f;\n'
        'constexpr size_t kVoicaMaxCtcTargets = 512;\n',
    )
    insert_before_once(
        recognizer_cc,
        "static bool ParseVoicaCtcStateId(const std::string &text, uint64_t *value) {\n",
        r'''static bool ParseVoicaNonNegativeInt64(const std::string &text,
                                           int64_t *value) {
  if (!value || text.empty()) return false;
  errno = 0;
  char *end = nullptr;
  long long parsed = std::strtoll(text.c_str(), &end, 10);
  if (errno != 0 || end == text.c_str() || *end != '\0' || parsed < 0) return false;
  *value = static_cast<int64_t>(parsed);
  return true;
}

static bool ParseVoicaCtcTargetIds(const std::string &text, int64_t blank_id,
                                   int64_t vocab_size,
                                   std::vector<int64_t> *targets) {
  if (!targets || text.empty() || vocab_size <= 1 || blank_id < 0 ||
      blank_id >= vocab_size) return false;
  targets->clear();
  size_t begin = 0;
  while (begin < text.size()) {
    size_t end = text.find(',', begin);
    if (end == std::string::npos) end = text.size();
    if (end == begin) return false;
    int64_t id = -1;
    if (!ParseVoicaNonNegativeInt64(text.substr(begin, end - begin), &id) ||
        id >= vocab_size || id == blank_id) return false;
    targets->push_back(id);
    if (targets->size() > kVoicaMaxCtcTargets) return false;
    begin = end + 1;
  }
  return !targets->empty();
}

struct VoicaCtcFrameSpan {
  int32_t start_frame = -1;
  int32_t end_frame = -1;
};

static bool AlignVoicaCompactCtc(const float *scores, int32_t frame_count,
                                 const std::vector<int64_t> &targets,
                                 std::vector<VoicaCtcFrameSpan> *spans) {
  if (!scores || !spans || frame_count <= 0 || targets.empty()) return false;
  const int32_t target_count = static_cast<int32_t>(targets.size());
  int32_t minimum_frames = target_count;
  for (int32_t i = 1; i < target_count; ++i) {
    if (targets[i] == targets[i - 1]) ++minimum_frames;
  }
  if (frame_count < minimum_frames) return false;

  const int32_t candidate_count = target_count + 1;
  const int32_t state_count = target_count * 2 + 1;
  const float neg_inf = -std::numeric_limits<float>::infinity();
  constexpr int8_t kStay = 0;
  constexpr int8_t kAdvanceOne = 1;
  constexpr int8_t kAdvanceTwo = 2;
  constexpr int8_t kUnreachable = 127;
  auto emission = [&](int32_t frame, int32_t state) -> float {
    const int32_t column = state % 2 == 0 ? 0 : ((state - 1) / 2 + 1);
    return scores[static_cast<size_t>(frame) * candidate_count + column];
  };

  std::vector<float> previous(state_count, neg_inf);
  std::vector<float> current(state_count, neg_inf);
  std::vector<int8_t> back(
      static_cast<size_t>(frame_count) * state_count, kUnreachable);
  const float blank0 = emission(0, 0);
  const float target0 = emission(0, 1);
  if (std::isfinite(blank0)) previous[0] = blank0;
  if (std::isfinite(target0)) previous[1] = target0;

  for (int32_t frame = 1; frame < frame_count; ++frame) {
    std::fill(current.begin(), current.end(), neg_inf);
    for (int32_t state = 0; state < state_count; ++state) {
      float best_score = previous[state];
      int8_t best_back = kStay;
      if (state > 0 && previous[state - 1] > best_score) {
        best_score = previous[state - 1];
        best_back = kAdvanceOne;
      }
      if (state > 1 && state % 2 == 1) {
        const int32_t target_index = (state - 1) / 2;
        const bool may_skip_blank =
            target_index == 0 || targets[target_index] != targets[target_index - 1];
        if (may_skip_blank && previous[state - 2] > best_score) {
          best_score = previous[state - 2];
          best_back = kAdvanceTwo;
        }
      }
      if (best_score == neg_inf) continue;
      const float e = emission(frame, state);
      if (!std::isfinite(e)) continue;
      const float score = best_score + e;
      if (!std::isfinite(score)) continue;
      current[state] = score;
      back[static_cast<size_t>(frame) * state_count + state] = best_back;
    }
    previous.swap(current);
  }

  const int32_t last_target_state = state_count - 2;
  const int32_t final_blank_state = state_count - 1;
  int32_t state = previous[final_blank_state] > previous[last_target_state]
                      ? final_blank_state
                      : last_target_state;
  if (!std::isfinite(previous[state])) return false;
  std::vector<int32_t> path(frame_count, -1);
  for (int32_t frame = frame_count - 1; frame >= 0; --frame) {
    path[frame] = state;
    if (frame == 0) break;
    const int8_t b = back[static_cast<size_t>(frame) * state_count + state];
    if (b == kAdvanceOne) --state;
    else if (b == kAdvanceTwo) state -= 2;
    else if (b != kStay) return false;
    if (state < 0) return false;
  }
  if (path[0] != 0 && path[0] != 1) return false;

  spans->assign(target_count, VoicaCtcFrameSpan{});
  for (int32_t frame = 0; frame < frame_count; ++frame) {
    const int32_t path_state = path[frame];
    if (path_state % 2 == 0) continue;
    const int32_t target_index = (path_state - 1) / 2;
    auto &span = (*spans)[target_index];
    if (span.start_frame < 0) span.start_frame = frame;
    span.end_frame = frame + 1;
  }
  for (const auto &span : *spans) {
    if (span.start_frame < 0 || span.end_frame <= span.start_frame) return false;
  }
  return true;
}

''',
    )

    replace_once(
        recognizer_cc,
        r'''      } else if (command == kVoicaCtcAlignCommand) {
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
''',
        r'''      } else if (command == kVoicaCtcAlignCommand) {
        int64_t blank_id = -1;
        int64_t vocab_size = -1;
        std::vector<int64_t> target_ids;
        const bool valid_args =
            ParseVoicaNonNegativeInt64(ss[i]->GetOption(kVoicaCtcBlankIdKey),
                                       &blank_id) &&
            ParseVoicaNonNegativeInt64(ss[i]->GetOption(kVoicaCtcVocabSizeKey),
                                       &vocab_size) &&
            ParseVoicaCtcTargetIds(ss[i]->GetOption(kVoicaCtcTargetIdsKey),
                                   blank_id, vocab_size, &target_ids);
        if (!valid_args) {
          DiscardRetainedCtcState(ss[i]);
        } else {
          RetainedCtcState retained;
          if (ConsumeRetainedCtcState(ss[i], &retained)) {
            if (retained.encoder_out_lens.IsTensor() &&
                retained.encoder_out_lens.GetTensorTypeAndShapeInfo()
                        .GetElementType() == ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64) {
              const int64_t valid_len64 =
                  retained.encoder_out_lens.GetTensorData<int64_t>()[0];
              if (valid_len64 > 0 &&
                  valid_len64 <= std::numeric_limits<int32_t>::max()) {
                std::vector<int64_t> candidate_ids;
                candidate_ids.reserve(target_ids.size() + 1);
                candidate_ids.push_back(blank_id);
                candidate_ids.insert(candidate_ids.end(), target_ids.begin(),
                                     target_ids.end());
                Ort::Value logits = model_->ForwardCompactCtc(
                    std::move(retained.encoder_out),
                    std::move(retained.encoder_out_lens), candidate_ids);
                auto logits_shape = logits.GetTensorTypeAndShapeInfo().GetShape();
                const int32_t valid_len = static_cast<int32_t>(valid_len64);
                if (logits_shape.size() == 3u && logits_shape[0] == 1 &&
                    logits_shape[1] >= valid_len &&
                    logits_shape[2] == static_cast<int64_t>(candidate_ids.size())) {
                  std::vector<VoicaCtcFrameSpan> spans;
                  if (AlignVoicaCompactCtc(logits.GetTensorData<float>(), valid_len,
                                           target_ids, &spans)) {
                    for (size_t target_index = 0; target_index < target_ids.size();
                         ++target_index) {
                      const auto &span = spans[target_index];
                      command_result.tokens.push_back(
                          std::to_string(target_ids[target_index]));
                      command_result.timestamps.push_back(
                          span.start_frame * kVoicaFunAsrCtcFrameSeconds);
                      command_result.durations.push_back(
                          (span.end_frame - span.start_frame) *
                          kVoicaFunAsrCtcFrameSeconds);
                    }
                  }
                }
              }
            }
          }
        }
''',
    )
    insert_after_once(
        recognizer_cc,
        '      ss[i]->SetOption(kVoicaCtcCommandKey, "");\n',
        '      ss[i]->SetOption(kVoicaCtcTargetIdsKey, "");\n'
        '      ss[i]->SetOption(kVoicaCtcBlankIdKey, "");\n'
        '      ss[i]->SetOption(kVoicaCtcVocabSizeKey, "");\n',
    )

    require_exact_count(model_h, "ForwardCompactCtc")
    require_exact_count(model_cc, "  void SetupCtcCompactFromSession() {")
    require_exact_count(model_cc, "InitCtcCompactFromMemory", expected=2)
    require_exact_count(model_cc, "InitCtcCompact(c.ctc_compact)")
    require_exact_count(model_cc, 'ctc_output_names_[0] != "candidate_logits"')
    require_exact_count(model_cc, "  Ort::Value ForwardCompactCtc(\n")
    require_exact_count(model_cc, "OfflineFunASRNanoModel::ForwardCompactCtc(")
    require_exact_count(model_cc, "impl_->ForwardCompactCtc(")
    require_exact_count(recognizer_cc, "AlignVoicaCompactCtc", expected=2)
    require_exact_count(recognizer_cc, 'kVoicaCtcVocabSizeKey = "voica.funasr.ctc.vocab_size"')
    require_exact_count(recognizer_cc, "kVoicaFunAsrCtcFrameSeconds = 0.06f")
    require_exact_count(recognizer_cc, "model_->ForwardCompactCtc")
    if "A1 align command consumed retained state" in read(recognizer_cc):
        raise SystemExit("A2 must replace the A1 lifecycle-only align branch")

    print("Applied Stage 16B FunASR CTC A2 compact scorer + Viterbi patch")


if __name__ == "__main__":
    main()
