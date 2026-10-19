#include <jni.h>
#include <dlfcn.h>
#include <onnxruntime_c_api.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <deque>
#include <fstream>
#include <sstream>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>

namespace {

constexpr int kSampleRate = 16000;
constexpr int kFrameLength = 400;
constexpr int kFrameShift = 160;
constexpr int kFftSize = 512;
constexpr int kSpectrumBins = kFftSize / 2 + 1;
constexpr int kMelBins = 80;
constexpr int kLfrM = 5;
constexpr int kFeatureDim = kMelBins * kLfrM;
constexpr int kCacheLayers = 4;
constexpr int kCacheProjectionDim = 128;
constexpr int kCacheOrder = 19;
constexpr int kCacheElements = kCacheProjectionDim * kCacheOrder;
constexpr int kOutputDim = 248;
constexpr int kFrameMs = 10;
constexpr int kEndpointWindowFrames = 20;
constexpr int kSpeechTransitionFrames = 15;
constexpr int kLookaheadEndFrames = 10;
constexpr int kStartLookbackFrames = 40;
constexpr int kScheduleChunkFrames = 6000;
constexpr float kPreEmphasis = 0.97F;
constexpr float kLowFrequencyHz = 20.0F;
constexpr float kHighFrequencyHz = 8000.0F;
constexpr float kEnergyFloor = 1.1920929e-07F;
constexpr double kPi = 3.14159265358979323846;

using OrtGetApiBaseFunction = const OrtApiBase* (*)();

void ThrowIllegalState(JNIEnv* env, const std::string& message) {
    jclass exception_class = env->FindClass("java/lang/IllegalStateException");
    if (exception_class != nullptr) {
        env->ThrowNew(exception_class, message.c_str());
    }
}

void ThrowIllegalArgument(JNIEnv* env, const std::string& message) {
    jclass exception_class = env->FindClass("java/lang/IllegalArgumentException");
    if (exception_class != nullptr) {
        env->ThrowNew(exception_class, message.c_str());
    }
}

std::string DlErrorOr(const char* fallback) {
    const char* error = dlerror();
    return error == nullptr ? std::string(fallback) : std::string(error);
}

struct OrtRuntime {
    void* library = nullptr;
    const OrtApiBase* base = nullptr;
    const OrtApi* api = nullptr;

    OrtRuntime() {
        library = dlopen("libonnxruntime.so", RTLD_NOW | RTLD_LOCAL);
        if (library == nullptr) {
            throw std::runtime_error("Unable to load APK ONNX Runtime: " + DlErrorOr("unknown dlopen error"));
        }

        dlerror();
        auto get_api_base = reinterpret_cast<OrtGetApiBaseFunction>(dlsym(library, "OrtGetApiBase"));
        const char* symbol_error = dlerror();
        if (symbol_error != nullptr || get_api_base == nullptr) {
            const std::string detail =
                symbol_error == nullptr ? "missing symbol" : std::string(symbol_error);
            dlclose(library);
            library = nullptr;
            throw std::runtime_error("APK ONNX Runtime does not export OrtGetApiBase: " + detail);
        }

        base = get_api_base();
        if (base == nullptr) {
            dlclose(library);
            library = nullptr;
            throw std::runtime_error("OrtGetApiBase returned null");
        }
        api = base->GetApi(ORT_API_VERSION);
        if (api == nullptr) {
            dlclose(library);
            library = nullptr;
            throw std::runtime_error(
                "APK ONNX Runtime does not support the API version required by FSMN candidate");
        }
    }

    ~OrtRuntime() {
        if (library != nullptr) {
            dlclose(library);
        }
    }

    OrtRuntime(const OrtRuntime&) = delete;
    OrtRuntime& operator=(const OrtRuntime&) = delete;
};

void CheckOrt(const OrtApi* api, OrtStatus* status, const char* operation) {
    if (status == nullptr) return;
    const char* raw = api->GetErrorMessage(status);
    const std::string message =
        std::string(operation) + ": " + (raw == nullptr ? "unknown ONNX Runtime error" : raw);
    api->ReleaseStatus(status);
    throw std::runtime_error(message);
}

std::vector<float> ParseCmvnVectorLine(const std::string& line) {
    std::istringstream input(line);
    std::vector<std::string> tokens;
    std::string token;
    while (input >> token) {
        tokens.push_back(token);
    }
    if (tokens.size() < 5 || tokens.front() != "<LearnRateCoef>") {
        throw std::runtime_error("Invalid FSMN CMVN vector line");
    }

    std::vector<float> result;
    result.reserve(kFeatureDim);
    for (size_t index = 3; index + 1 < tokens.size(); ++index) {
        result.push_back(std::stof(tokens[index]));
    }
    return result;
}

std::pair<std::vector<float>, std::vector<float>> LoadCmvn(const std::string& path) {
    std::ifstream input(path);
    if (!input.is_open()) {
        throw std::runtime_error("Unable to open FSMN CMVN file: " + path);
    }

    std::vector<float> means;
    std::vector<float> scales;
    std::string line;
    while (std::getline(input, line)) {
        if (line.find("<AddShift>") != std::string::npos) {
            if (!std::getline(input, line)) {
                throw std::runtime_error("FSMN CMVN AddShift vector is missing");
            }
            means = ParseCmvnVectorLine(line);
        } else if (line.find("<Rescale>") != std::string::npos) {
            if (!std::getline(input, line)) {
                throw std::runtime_error("FSMN CMVN Rescale vector is missing");
            }
            scales = ParseCmvnVectorLine(line);
        }
    }

    if (means.size() != kFeatureDim || scales.size() != kFeatureDim) {
        throw std::runtime_error(
            "FSMN CMVN must contain exactly 400 shift and 400 scale values");
    }
    return {std::move(means), std::move(scales)};
}

float Mel(float frequency_hz) {
    return 1127.0F * std::log1p(frequency_hz / 700.0F);
}

void Fft(std::array<float, kFftSize>* real, std::array<float, kFftSize>* imag) {
    int j = 0;
    for (int i = 1; i < kFftSize; ++i) {
        int bit = kFftSize >> 1;
        while ((j & bit) != 0) {
            j ^= bit;
            bit >>= 1;
        }
        j ^= bit;
        if (i < j) {
            std::swap((*real)[i], (*real)[j]);
            std::swap((*imag)[i], (*imag)[j]);
        }
    }

    for (int length = 2; length <= kFftSize; length <<= 1) {
        const double angle = -2.0 * kPi / static_cast<double>(length);
        const float step_real = static_cast<float>(std::cos(angle));
        const float step_imag = static_cast<float>(std::sin(angle));
        for (int start = 0; start < kFftSize; start += length) {
            float current_real = 1.0F;
            float current_imag = 0.0F;
            for (int offset = 0; offset < length / 2; ++offset) {
                const int left = start + offset;
                const int right = left + length / 2;
                const float left_real = (*real)[left];
                const float left_imag = (*imag)[left];
                const float right_real =
                    (*real)[right] * current_real - (*imag)[right] * current_imag;
                const float right_imag =
                    (*real)[right] * current_imag + (*imag)[right] * current_real;
                (*real)[left] = left_real + right_real;
                (*imag)[left] = left_imag + right_imag;
                (*real)[right] = left_real - right_real;
                (*imag)[right] = left_imag - right_imag;

                const float next_real =
                    current_real * step_real - current_imag * step_imag;
                current_imag = current_real * step_imag + current_imag * step_real;
                current_real = next_real;
            }
        }
    }
}

class FbankFrontend {
  public:
    FbankFrontend() {
        for (int i = 0; i < kFrameLength; ++i) {
            window_[i] =
                0.54F -
                0.46F * static_cast<float>(
                            std::cos(2.0 * kPi * static_cast<double>(i) /
                                     static_cast<double>(kFrameLength - 1)));
        }

        const float bin_width = static_cast<float>(kSampleRate) / static_cast<float>(kFftSize);
        const float mel_low = Mel(kLowFrequencyHz);
        const float mel_high = Mel(kHighFrequencyHz);
        const float mel_step = (mel_high - mel_low) / static_cast<float>(kMelBins + 1);
        for (int mel_index = 0; mel_index < kMelBins; ++mel_index) {
            const float left = mel_low + static_cast<float>(mel_index) * mel_step;
            const float center = mel_low + static_cast<float>(mel_index + 1) * mel_step;
            const float right = mel_low + static_cast<float>(mel_index + 2) * mel_step;
            for (int bin = 0; bin < kSpectrumBins; ++bin) {
                const float mel_frequency = Mel(bin_width * static_cast<float>(bin));
                float weight = 0.0F;
                if (mel_frequency > left && mel_frequency < right) {
                    weight = mel_frequency <= center
                                 ? (mel_frequency - left) / (center - left)
                                 : (right - mel_frequency) / (right - center);
                }
                mel_filters_[mel_index][bin] = weight;
            }
        }
    }

    std::array<float, kMelBins> Compute(const float* samples) const {
        std::array<float, kFrameLength> frame{};
        double mean = 0.0;
        for (int i = 0; i < kFrameLength; ++i) {
            mean += static_cast<double>(samples[i]);
        }
        mean /= static_cast<double>(kFrameLength);
        for (int i = 0; i < kFrameLength; ++i) {
            frame[i] = samples[i] - static_cast<float>(mean);
        }
        for (int i = kFrameLength - 1; i > 0; --i) {
            frame[i] -= kPreEmphasis * frame[i - 1];
        }
        frame[0] -= kPreEmphasis * frame[0];

        std::array<float, kFftSize> real{};
        std::array<float, kFftSize> imag{};
        for (int i = 0; i < kFrameLength; ++i) {
            real[i] = frame[i] * window_[i];
        }
        Fft(&real, &imag);

        std::array<float, kMelBins> output{};
        for (int mel_index = 0; mel_index < kMelBins; ++mel_index) {
            float energy = 0.0F;
            for (int bin = 0; bin < kSpectrumBins; ++bin) {
                const float weight = mel_filters_[mel_index][bin];
                if (weight > 0.0F) {
                    energy += weight * (real[bin] * real[bin] + imag[bin] * imag[bin]);
                }
            }
            output[mel_index] = std::log(std::max(energy, kEnergyFloor));
        }
        return output;
    }

  private:
    std::array<float, kFrameLength> window_{};
    std::array<std::array<float, kSpectrumBins>, kMelBins> mel_filters_{};
};

class EndpointState {
  public:
    EndpointState(float speech_noise_threshold, int max_speech_duration_ms)
        : speech_noise_threshold_(speech_noise_threshold),
          max_segment_frames_(std::max(1, max_speech_duration_ms / kFrameMs)) {
        ResetDetection();
    }

    std::vector<std::pair<int64_t, int64_t>> Accept(
        const float* probabilities,
        size_t frame_count) {
        std::vector<std::pair<int64_t, int64_t>> emitted;
        emitted.reserve(2);
        for (size_t row = 0; row < frame_count; ++row) {
            ProcessFrame(probabilities[row * kOutputDim], &emitted);
        }
        return emitted;
    }

    std::vector<std::pair<int64_t, int64_t>> Finish(int64_t total_samples) {
        std::vector<std::pair<int64_t, int64_t>> emitted;
        if (in_segment_) {
            const int64_t start_sample = FrameToSample(current_start_frame_);
            if (total_samples > start_sample) {
                emitted.emplace_back(start_sample, total_samples);
                previous_end_frame_ =
                    std::max(previous_end_frame_, total_samples / kFrameShift);
            }
            ResetDetection();
        }
        return emitted;
    }

  private:
    void RecomputeEndSilence() {
        int scheduled_ms;
        if (accumulated_ms_ <= 10000) {
            scheduled_ms = 2000;
        } else if (accumulated_ms_ <= 20000) {
            scheduled_ms = 1000;
        } else if (accumulated_ms_ <= 30000) {
            scheduled_ms = 800;
        } else if (accumulated_ms_ <= 40000) {
            scheduled_ms = 600;
        } else if (accumulated_ms_ <= 50000) {
            scheduled_ms = 400;
        } else if (accumulated_ms_ <= 60000) {
            scheduled_ms = 200;
        } else {
            scheduled_ms = 100;
        }
        max_end_silence_frames_ = std::max(0, (scheduled_ms - 150) / kFrameMs);
        end_lookback_frames_ =
            std::max(0, max_end_silence_frames_ - kLookaheadEndFrames - 1);
    }

    void ResetDetection() {
        window_.fill(0);
        window_position_ = 0;
        window_speech_count_ = 0;
        previous_window_state_ = 0;
        consecutive_silence_frames_ = 0;
        in_segment_ = false;
        current_start_frame_ = -1;
        accumulated_ms_ = 0;
        in_speech_latched_ = false;
        RecomputeEndSilence();
    }

    static int64_t FrameToSample(int64_t frame) {
        return std::max<int64_t>(0, frame) * static_cast<int64_t>(kFrameShift);
    }

    void Emit(
        int64_t start_frame,
        int64_t end_frame,
        std::vector<std::pair<int64_t, int64_t>>* emitted) {
        start_frame = std::max(start_frame, previous_end_frame_);
        start_frame = std::max<int64_t>(0, start_frame);
        end_frame = std::min(end_frame, processed_frames_ + 1);
        if (end_frame > start_frame) {
            emitted->emplace_back(FrameToSample(start_frame), FrameToSample(end_frame));
            previous_end_frame_ = end_frame;
        }
    }

    void ProcessFrame(
        float silence_probability,
        std::vector<std::pair<int64_t, int64_t>>* emitted) {
        const int64_t frame = processed_frames_;
        if (frame > 0 && frame % kScheduleChunkFrames == 0) {
            if (in_segment_ || in_speech_latched_) {
                accumulated_ms_ += kScheduleChunkFrames * kFrameMs;
                in_speech_latched_ = true;
            }
            RecomputeEndSilence();
        }

        const int speech =
            (1.0F - silence_probability) >=
                    silence_probability + speech_noise_threshold_
                ? 1
                : 0;
        window_speech_count_ -= window_[window_position_];
        window_speech_count_ += speech;
        window_[window_position_] = speech;
        window_position_ = (window_position_ + 1) % kEndpointWindowFrames;

        int transition;
        if (previous_window_state_ == 0 &&
            window_speech_count_ >= kSpeechTransitionFrames) {
            previous_window_state_ = 1;
            transition = 3;
        } else if (previous_window_state_ == 1 &&
                   window_speech_count_ <= kSpeechTransitionFrames) {
            previous_window_state_ = 0;
            transition = 1;
        } else {
            transition = previous_window_state_ == 0 ? 0 : 2;
        }

        if (transition == 3) {
            consecutive_silence_frames_ = 0;
            if (!in_segment_) {
                current_start_frame_ = frame - kStartLookbackFrames;
                current_start_frame_ = std::max(current_start_frame_, previous_end_frame_);
                current_start_frame_ = std::max<int64_t>(0, current_start_frame_);
                in_segment_ = true;
            } else if (frame - current_start_frame_ + 1 > max_segment_frames_) {
                Emit(current_start_frame_, frame, emitted);
                ResetDetection();
            }
        } else if (transition == 1 || transition == 2) {
            consecutive_silence_frames_ = 0;
            if (in_segment_ && frame - current_start_frame_ + 1 > max_segment_frames_) {
                Emit(current_start_frame_, frame, emitted);
                ResetDetection();
            }
        } else {
            ++consecutive_silence_frames_;
            if (in_segment_) {
                if (consecutive_silence_frames_ >= max_end_silence_frames_) {
                    Emit(current_start_frame_, frame - end_lookback_frames_, emitted);
                    ResetDetection();
                } else if (frame - current_start_frame_ + 1 > max_segment_frames_) {
                    Emit(current_start_frame_, frame, emitted);
                    ResetDetection();
                }
            }
        }
        ++processed_frames_;
    }

    float speech_noise_threshold_;
    int max_segment_frames_;
    std::array<int, kEndpointWindowFrames> window_{};
    int window_position_ = 0;
    int window_speech_count_ = 0;
    int previous_window_state_ = 0;
    int consecutive_silence_frames_ = 0;
    bool in_segment_ = false;
    int64_t current_start_frame_ = -1;
    int64_t previous_end_frame_ = 0;
    int accumulated_ms_ = 0;
    bool in_speech_latched_ = false;
    int max_end_silence_frames_ = 0;
    int end_lookback_frames_ = 0;
    int64_t processed_frames_ = 0;
};

class FsmnSession {
  public:
    FsmnSession(
        const std::string& model_path,
        const std::string& cmvn_path,
        int num_threads,
        float speech_noise_threshold,
        int max_speech_duration_ms)
        : runtime_(),
          endpoint_(speech_noise_threshold, max_speech_duration_ms) {
        if (num_threads <= 0) {
            throw std::invalid_argument("FSMN numThreads must be positive");
        }
        if (!(speech_noise_threshold > -1.0F && speech_noise_threshold < 1.0F)) {
            throw std::invalid_argument("FSMN speechNoiseThreshold must be within (-1, 1)");
        }
        if (max_speech_duration_ms <= 0) {
            throw std::invalid_argument("FSMN maxSpeechDurationMs must be positive");
        }

        auto cmvn = LoadCmvn(cmvn_path);
        cmvn_means_ = std::move(cmvn.first);
        cmvn_scales_ = std::move(cmvn.second);
        for (auto& cache : caches_) {
            cache.assign(kCacheElements, 0.0F);
        }

        const OrtApi* api = runtime_.api;
        CheckOrt(api, api->CreateEnv(ORT_LOGGING_LEVEL_ERROR, "voica-fsmn", &environment_), "CreateEnv");
        CheckOrt(api, api->CreateSessionOptions(&session_options_), "CreateSessionOptions");
        CheckOrt(
            api,
            api->SetIntraOpNumThreads(session_options_, num_threads),
            "SetIntraOpNumThreads");
        CheckOrt(
            api,
            api->SetSessionGraphOptimizationLevel(session_options_, ORT_ENABLE_ALL),
            "SetSessionGraphOptimizationLevel");
        CheckOrt(api, api->DisableCpuMemArena(session_options_), "DisableCpuMemArena");
        CheckOrt(
            api,
            api->CreateSession(environment_, model_path.c_str(), session_options_, &session_),
            "CreateSession");
        CheckOrt(
            api,
            api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &memory_info_),
            "CreateCpuMemoryInfo");
    }

    ~FsmnSession() {
        const OrtApi* api = runtime_.api;
        if (memory_info_ != nullptr) api->ReleaseMemoryInfo(memory_info_);
        if (session_ != nullptr) api->ReleaseSession(session_);
        if (session_options_ != nullptr) api->ReleaseSessionOptions(session_options_);
        if (environment_ != nullptr) api->ReleaseEnv(environment_);
    }

    FsmnSession(const FsmnSession&) = delete;
    FsmnSession& operator=(const FsmnSession&) = delete;

    std::vector<std::pair<int64_t, int64_t>> Accept(
        const jshort* samples,
        size_t count) {
        if (finished_) {
            throw std::runtime_error("FSMN session already finished");
        }
        total_samples_seen_ += static_cast<int64_t>(count);
        sample_buffer_.reserve(sample_buffer_.size() + count);
        for (size_t index = 0; index < count; ++index) {
            sample_buffer_.push_back(static_cast<float>(samples[index]));
        }

        std::vector<std::array<float, kFeatureDim>> features;
        ExtractCompleteFbankFrames(&features);
        return RunFeatures(features, false);
    }

    std::vector<std::pair<int64_t, int64_t>> Finish() {
        if (finished_) return {};
        finished_ = true;

        std::vector<std::array<float, kFeatureDim>> features;
        ExtractCompleteFbankFrames(&features);
        if (fbank_frames_seen_ > 0) {
            while (features_emitted_ < fbank_frames_seen_) {
                while (lfr_frames_.size() < kLfrM) {
                    lfr_frames_.push_back(last_fbank_frame_);
                }
                features.push_back(BuildNextFeature());
            }
        }

        auto emitted = RunFeatures(features, true);
        auto final_segment = endpoint_.Finish(total_samples_seen_);
        emitted.insert(emitted.end(), final_segment.begin(), final_segment.end());
        return emitted;
    }

  private:
    void ExtractCompleteFbankFrames(std::vector<std::array<float, kFeatureDim>>* features) {
        while (sample_buffer_.size() - sample_offset_ >= kFrameLength) {
            auto frame = frontend_.Compute(sample_buffer_.data() + sample_offset_);
            sample_offset_ += kFrameShift;
            PushFbankFrame(frame, features);
        }

        if (sample_offset_ > 8192 && sample_offset_ * 2 > sample_buffer_.size()) {
            sample_buffer_.erase(
                sample_buffer_.begin(),
                sample_buffer_.begin() + static_cast<std::ptrdiff_t>(sample_offset_));
            sample_offset_ = 0;
        }
    }

    void PushFbankFrame(
        const std::array<float, kMelBins>& frame,
        std::vector<std::array<float, kFeatureDim>>* features) {
        if (fbank_frames_seen_ == 0) {
            lfr_frames_.push_back(frame);
            lfr_frames_.push_back(frame);
        }
        lfr_frames_.push_back(frame);
        last_fbank_frame_ = frame;
        ++fbank_frames_seen_;
        while (lfr_frames_.size() >= kLfrM) {
            features->push_back(BuildNextFeature());
        }
    }

    std::array<float, kFeatureDim> BuildNextFeature() {
        if (lfr_frames_.size() < kLfrM) {
            throw std::runtime_error("FSMN LFR window is incomplete");
        }
        std::array<float, kFeatureDim> feature{};
        for (int frame = 0; frame < kLfrM; ++frame) {
            for (int mel = 0; mel < kMelBins; ++mel) {
                const int index = frame * kMelBins + mel;
                feature[index] =
                    (lfr_frames_[frame][mel] + cmvn_means_[index]) * cmvn_scales_[index];
            }
        }
        lfr_frames_.pop_front();
        ++features_emitted_;
        return feature;
    }

    size_t TensorElementCount(OrtValue* value) const {
        OrtTensorTypeAndShapeInfo* shape_info = nullptr;
        CheckOrt(runtime_.api, runtime_.api->GetTensorTypeAndShape(value, &shape_info), "GetTensorTypeAndShape");
        size_t count = 0;
        OrtStatus* status = runtime_.api->GetTensorShapeElementCount(shape_info, &count);
        runtime_.api->ReleaseTensorTypeAndShapeInfo(shape_info);
        CheckOrt(runtime_.api, status, "GetTensorShapeElementCount");
        return count;
    }

    std::vector<std::pair<int64_t, int64_t>> RunFeatures(
        const std::vector<std::array<float, kFeatureDim>>& features,
        bool final_run) {
        if (features.empty()) return {};

        std::vector<float> flattened;
        flattened.reserve(features.size() * kFeatureDim);
        for (const auto& feature : features) {
            flattened.insert(flattened.end(), feature.begin(), feature.end());
        }

        std::array<OrtValue*, 5> owned_inputs{};
        std::array<const OrtValue*, 5> input_values{};
        std::array<OrtValue*, 5> outputs{};
        try {
            const std::array<int64_t, 3> feature_shape = {
                1,
                static_cast<int64_t>(features.size()),
                kFeatureDim,
            };
            CheckOrt(
                runtime_.api,
                runtime_.api->CreateTensorWithDataAsOrtValue(
                    memory_info_,
                    flattened.data(),
                    flattened.size() * sizeof(float),
                    feature_shape.data(),
                    feature_shape.size(),
                    ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT,
                    &owned_inputs[0]),
                "CreateTensor(features)");
            input_values[0] = owned_inputs[0];

            const std::array<int64_t, 4> cache_shape = {
                1,
                kCacheProjectionDim,
                kCacheOrder,
                1,
            };
            for (int index = 0; index < kCacheLayers; ++index) {
                CheckOrt(
                    runtime_.api,
                    runtime_.api->CreateTensorWithDataAsOrtValue(
                        memory_info_,
                        caches_[index].data(),
                        caches_[index].size() * sizeof(float),
                        cache_shape.data(),
                        cache_shape.size(),
                        ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT,
                        &owned_inputs[index + 1]),
                    "CreateTensor(cache)");
                input_values[index + 1] = owned_inputs[index + 1];
            }

            static constexpr const char* kInputNames[] = {
                "speech",
                "in_cache0",
                "in_cache1",
                "in_cache2",
                "in_cache3",
            };
            static constexpr const char* kOutputNames[] = {
                "logits",
                "out_cache0",
                "out_cache1",
                "out_cache2",
                "out_cache3",
            };
            CheckOrt(
                runtime_.api,
                runtime_.api->Run(
                    session_,
                    nullptr,
                    kInputNames,
                    input_values.data(),
                    input_values.size(),
                    kOutputNames,
                    outputs.size(),
                    outputs.data()),
                "Run(FSMN VAD)");

            const size_t expected_logits = features.size() * kOutputDim;
            if (TensorElementCount(outputs[0]) != expected_logits) {
                throw std::runtime_error("FSMN logits shape does not match expected [1,T,248]");
            }
            float* logits = nullptr;
            CheckOrt(
                runtime_.api,
                runtime_.api->GetTensorMutableData(outputs[0], reinterpret_cast<void**>(&logits)),
                "GetTensorMutableData(logits)");
            if (logits == nullptr) {
                throw std::runtime_error("FSMN logits tensor returned null data");
            }

            if (!final_run) {
                for (int index = 0; index < kCacheLayers; ++index) {
                    if (TensorElementCount(outputs[index + 1]) != kCacheElements) {
                        throw std::runtime_error("FSMN cache output shape is incompatible");
                    }
                    float* cache_data = nullptr;
                    CheckOrt(
                        runtime_.api,
                        runtime_.api->GetTensorMutableData(
                            outputs[index + 1],
                            reinterpret_cast<void**>(&cache_data)),
                        "GetTensorMutableData(cache)");
                    if (cache_data == nullptr) {
                        throw std::runtime_error("FSMN cache tensor returned null data");
                    }
                    std::copy(cache_data, cache_data + kCacheElements, caches_[index].begin());
                }
            }

            auto segments = endpoint_.Accept(logits, features.size());
            for (OrtValue*& output : outputs) {
                if (output != nullptr) runtime_.api->ReleaseValue(output);
            }
            for (OrtValue*& input : owned_inputs) {
                if (input != nullptr) runtime_.api->ReleaseValue(input);
            }
            return segments;
        } catch (...) {
            for (OrtValue*& output : outputs) {
                if (output != nullptr) runtime_.api->ReleaseValue(output);
            }
            for (OrtValue*& input : owned_inputs) {
                if (input != nullptr) runtime_.api->ReleaseValue(input);
            }
            throw;
        }
    }

    OrtRuntime runtime_;
    OrtEnv* environment_ = nullptr;
    OrtSessionOptions* session_options_ = nullptr;
    OrtSession* session_ = nullptr;
    OrtMemoryInfo* memory_info_ = nullptr;
    FbankFrontend frontend_;
    EndpointState endpoint_;
    std::vector<float> cmvn_means_;
    std::vector<float> cmvn_scales_;
    std::array<std::vector<float>, kCacheLayers> caches_{};
    std::vector<float> sample_buffer_;
    size_t sample_offset_ = 0;
    std::deque<std::array<float, kMelBins>> lfr_frames_;
    std::array<float, kMelBins> last_fbank_frame_{};
    int64_t fbank_frames_seen_ = 0;
    int64_t features_emitted_ = 0;
    int64_t total_samples_seen_ = 0;
    bool finished_ = false;
};

jlongArray SegmentsToJava(
    JNIEnv* env,
    const std::vector<std::pair<int64_t, int64_t>>& segments) {
    const jsize length = static_cast<jsize>(segments.size() * 2);
    jlongArray result = env->NewLongArray(length);
    if (result == nullptr || length == 0) return result;

    std::vector<jlong> values;
    values.reserve(static_cast<size_t>(length));
    for (const auto& segment : segments) {
        values.push_back(static_cast<jlong>(segment.first));
        values.push_back(static_cast<jlong>(segment.second));
    }
    env->SetLongArrayRegion(result, 0, length, values.data());
    return result;
}

FsmnSession* RequireSession(jlong handle) {
    if (handle == 0) {
        throw std::invalid_argument("FSMN native session handle is null");
    }
    return reinterpret_cast<FsmnSession*>(handle);
}

std::string JavaString(JNIEnv* env, jstring value, const char* name) {
    if (value == nullptr) {
        throw std::invalid_argument(std::string(name) + " must not be null");
    }
    const char* raw = env->GetStringUTFChars(value, nullptr);
    if (raw == nullptr) {
        throw std::runtime_error(std::string("Unable to read ") + name);
    }
    const std::string result(raw);
    env->ReleaseStringUTFChars(value, raw);
    if (result.empty()) {
        throw std::invalid_argument(std::string(name) + " must not be empty");
    }
    return result;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_ioannes78_voica_fsmn_FsmnOrtRuntimeProbe_nativeOrtVersion(
    JNIEnv* env,
    jobject /* thiz */) {
    try {
        OrtRuntime runtime;
        const char* version = runtime.base->GetVersionString();
        if (version == nullptr || version[0] == '\0') {
            throw std::runtime_error("APK ONNX Runtime returned an empty version string");
        }
        return env->NewStringUTF(version);
    } catch (const std::exception& error) {
        ThrowIllegalState(env, error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_ioannes78_voica_fsmn_FsmnNativeBridge_nativeCreate(
    JNIEnv* env,
    jobject /* thiz */,
    jstring model_path,
    jstring cmvn_path,
    jint num_threads,
    jfloat speech_noise_threshold,
    jint max_speech_duration_ms) {
    try {
        const std::string model = JavaString(env, model_path, "modelPath");
        const std::string cmvn = JavaString(env, cmvn_path, "cmvnPath");
        auto* session =
            new FsmnSession(
                model,
                cmvn,
                static_cast<int>(num_threads),
                static_cast<float>(speech_noise_threshold),
                static_cast<int>(max_speech_duration_ms));
        return reinterpret_cast<jlong>(session);
    } catch (const std::invalid_argument& error) {
        ThrowIllegalArgument(env, error.what());
        return 0;
    } catch (const std::exception& error) {
        ThrowIllegalState(env, error.what());
        return 0;
    }
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_github_ioannes78_voica_fsmn_FsmnNativeBridge_nativeAccept(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jshortArray samples,
    jint offset,
    jint count) {
    try {
        if (samples == nullptr) {
            throw std::invalid_argument("samples must not be null");
        }
        const jsize length = env->GetArrayLength(samples);
        if (offset < 0 || count < 0 || offset > length || count > length - offset) {
            throw std::invalid_argument("FSMN PCM slice is out of bounds");
        }
        if (count == 0) return env->NewLongArray(0);

        std::vector<jshort> buffer(static_cast<size_t>(count));
        env->GetShortArrayRegion(samples, offset, count, buffer.data());
        if (env->ExceptionCheck()) return nullptr;

        return SegmentsToJava(
            env,
            RequireSession(handle)->Accept(buffer.data(), buffer.size()));
    } catch (const std::invalid_argument& error) {
        ThrowIllegalArgument(env, error.what());
        return nullptr;
    } catch (const std::exception& error) {
        ThrowIllegalState(env, error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_io_github_ioannes78_voica_fsmn_FsmnNativeBridge_nativeFinish(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle) {
    try {
        return SegmentsToJava(env, RequireSession(handle)->Finish());
    } catch (const std::invalid_argument& error) {
        ThrowIllegalArgument(env, error.what());
        return nullptr;
    } catch (const std::exception& error) {
        ThrowIllegalState(env, error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_ioannes78_voica_fsmn_FsmnNativeBridge_nativeClose(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle) {
    try {
        delete RequireSession(handle);
    } catch (const std::invalid_argument& error) {
        ThrowIllegalArgument(env, error.what());
    } catch (const std::exception& error) {
        ThrowIllegalState(env, error.what());
    }
}
