#include <jni.h>
#include <opus.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace {

constexpr jint kMaxPacketBytes = 1275;
constexpr jint kMaxFramesPerPacket = 48;

struct DecoderHolder {
    DecoderHolder(OpusDecoder* decoder_in, int sample_rate_in, int channels_in)
        : decoder(decoder_in), sample_rate(sample_rate_in), channels(channels_in) {}

    ~DecoderHolder() {
        if (decoder != nullptr) {
            opus_decoder_destroy(decoder);
            decoder = nullptr;
        }
    }

    OpusDecoder* decoder;
    int sample_rate;
    int channels;
    std::mutex mutex;
};

std::mutex g_registry_mutex;
std::unordered_map<jlong, std::shared_ptr<DecoderHolder>> g_decoders;
std::atomic<jlong> g_next_handle{1};

void throw_java(JNIEnv* env, const char* class_name, const std::string& message) {
    jclass clazz = env->FindClass(class_name);
    if (clazz != nullptr) {
        env->ThrowNew(clazz, message.c_str());
        env->DeleteLocalRef(clazz);
    }
}

bool valid_sample_rate(jint sample_rate) {
    return sample_rate == 8000 || sample_rate == 12000 || sample_rate == 16000 ||
           sample_rate == 24000 || sample_rate == 48000;
}

bool copy_packet(JNIEnv* env, jbyteArray input, std::vector<unsigned char>* output) {
    if (input == nullptr) {
        throw_java(env, "java/lang/IllegalArgumentException", "packet is null");
        return false;
    }

    const jsize length = env->GetArrayLength(input);
    if (length <= 0 || length > kMaxPacketBytes) {
        throw_java(
            env,
            "java/lang/IllegalArgumentException",
            "packet length must be between 1 and 1275 bytes");
        return false;
    }

    output->resize(static_cast<size_t>(length));
    env->GetByteArrayRegion(
        input,
        0,
        length,
        reinterpret_cast<jbyte*>(output->data()));
    return !env->ExceptionCheck();
}

std::shared_ptr<DecoderHolder> find_decoder(JNIEnv* env, jlong handle) {
    if (handle <= 0) {
        throw_java(env, "java/lang/IllegalStateException", "decoder handle is closed");
        return nullptr;
    }

    std::lock_guard<std::mutex> guard(g_registry_mutex);
    const auto it = g_decoders.find(handle);
    if (it == g_decoders.end()) {
        throw_java(env, "java/lang/IllegalStateException", "decoder handle is invalid");
        return nullptr;
    }
    return it->second;
}

jintArray make_inspection(
    JNIEnv* env,
    jint valid,
    jint error,
    jint toc,
    jint channels,
    jint frame_count,
    jint samples_per_frame_48k,
    jint total_samples_48k,
    jint bandwidth,
    jint payload_offset,
    jint min_frame_bytes,
    jint max_frame_bytes) {
    const std::array<jint, 11> values{
        valid,
        error,
        toc,
        channels,
        frame_count,
        samples_per_frame_48k,
        total_samples_48k,
        bandwidth,
        payload_offset,
        min_frame_bytes,
        max_frame_bytes,
    };

    jintArray result = env->NewIntArray(static_cast<jsize>(values.size()));
    if (result == nullptr) {
        return nullptr;
    }
    env->SetIntArrayRegion(
        result,
        0,
        static_cast<jsize>(values.size()),
        values.data());
    return result;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_io_github_ioannes78_voica_opus_NativeOpusBridge_nativeVersion(
    JNIEnv* env,
    jobject /* thiz */) {
    return env->NewStringUTF(opus_get_version_string());
}

extern "C" JNIEXPORT jintArray JNICALL
Java_io_github_ioannes78_voica_opus_NativeOpusBridge_nativeInspect(
    JNIEnv* env,
    jobject /* thiz */,
    jbyteArray packet) {
    std::vector<unsigned char> data;
    if (!copy_packet(env, packet, &data)) {
        return nullptr;
    }

    unsigned char toc = 0;
    std::array<const unsigned char*, kMaxFramesPerPacket> frames{};
    std::array<opus_int16, kMaxFramesPerPacket> sizes{};
    int payload_offset = 0;

    const int parsed = opus_packet_parse(
        data.data(),
        static_cast<opus_int32>(data.size()),
        &toc,
        frames.data(),
        sizes.data(),
        &payload_offset);

    if (parsed < 0) {
        return make_inspection(
            env,
            0,
            parsed,
            -1,
            -1,
            -1,
            -1,
            -1,
            -1,
            -1,
            -1,
            -1);
    }

    const int channels = opus_packet_get_nb_channels(data.data());
    const int frame_count =
        opus_packet_get_nb_frames(data.data(), static_cast<opus_int32>(data.size()));
    const int samples_per_frame =
        opus_packet_get_samples_per_frame(data.data(), 48000);
    const int total_samples =
        opus_packet_get_nb_samples(
            data.data(),
            static_cast<opus_int32>(data.size()),
            48000);
    const int bandwidth = opus_packet_get_bandwidth(data.data());

    int min_frame_bytes = sizes[0];
    int max_frame_bytes = sizes[0];
    for (int index = 1; index < parsed; ++index) {
        min_frame_bytes = std::min(min_frame_bytes, static_cast<int>(sizes[index]));
        max_frame_bytes = std::max(max_frame_bytes, static_cast<int>(sizes[index]));
    }

    const bool valid =
        channels > 0 &&
        frame_count > 0 &&
        samples_per_frame > 0 &&
        total_samples > 0;

    return make_inspection(
        env,
        valid ? 1 : 0,
        valid ? OPUS_OK : OPUS_INVALID_PACKET,
        static_cast<int>(toc),
        channels,
        frame_count,
        samples_per_frame,
        total_samples,
        bandwidth,
        payload_offset,
        min_frame_bytes,
        max_frame_bytes);
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_github_ioannes78_voica_opus_NativeOpusBridge_nativeCreateDecoder(
    JNIEnv* env,
    jobject /* thiz */,
    jint sample_rate,
    jint channels) {
    if (!valid_sample_rate(sample_rate)) {
        throw_java(env, "java/lang/IllegalArgumentException", "unsupported Opus sample rate");
        return 0;
    }
    if (channels != 1 && channels != 2) {
        throw_java(env, "java/lang/IllegalArgumentException", "Opus decoder channels must be 1 or 2");
        return 0;
    }

    int error = OPUS_OK;
    OpusDecoder* decoder = opus_decoder_create(sample_rate, channels, &error);
    if (decoder == nullptr || error != OPUS_OK) {
        throw_java(
            env,
            "java/lang/IllegalStateException",
            std::string("opus_decoder_create failed: ") + opus_strerror(error));
        return 0;
    }

    const jlong handle = g_next_handle.fetch_add(1);
    auto holder = std::make_shared<DecoderHolder>(decoder, sample_rate, channels);
    {
        std::lock_guard<std::mutex> guard(g_registry_mutex);
        g_decoders.emplace(handle, std::move(holder));
    }
    return handle;
}

extern "C" JNIEXPORT jshortArray JNICALL
Java_io_github_ioannes78_voica_opus_NativeOpusBridge_nativeDecode(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle,
    jbyteArray packet) {
    auto holder = find_decoder(env, handle);
    if (holder == nullptr) {
        return nullptr;
    }

    std::vector<unsigned char> data;
    if (!copy_packet(env, packet, &data)) {
        return nullptr;
    }

    const int max_frame_size = holder->sample_rate * 120 / 1000;
    std::vector<opus_int16> pcm(
        static_cast<size_t>(max_frame_size * holder->channels));

    int samples_per_channel = 0;
    {
        std::lock_guard<std::mutex> guard(holder->mutex);
        samples_per_channel = opus_decode(
            holder->decoder,
            data.data(),
            static_cast<opus_int32>(data.size()),
            pcm.data(),
            max_frame_size,
            0);
    }

    if (samples_per_channel < 0) {
        throw_java(
            env,
            "java/lang/IllegalArgumentException",
            std::string("opus_decode failed: ") + opus_strerror(samples_per_channel));
        return nullptr;
    }

    const jsize output_length =
        static_cast<jsize>(samples_per_channel * holder->channels);
    jshortArray result = env->NewShortArray(output_length);
    if (result == nullptr) {
        return nullptr;
    }
    if (output_length > 0) {
        env->SetShortArrayRegion(
            result,
            0,
            output_length,
            reinterpret_cast<const jshort*>(pcm.data()));
    }
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_ioannes78_voica_opus_NativeOpusBridge_nativeResetDecoder(
    JNIEnv* env,
    jobject /* thiz */,
    jlong handle) {
    auto holder = find_decoder(env, handle);
    if (holder == nullptr) {
        return;
    }

    std::lock_guard<std::mutex> guard(holder->mutex);
    const int error = opus_decoder_ctl(holder->decoder, OPUS_RESET_STATE);
    if (error != OPUS_OK) {
        throw_java(
            env,
            "java/lang/IllegalStateException",
            std::string("OPUS_RESET_STATE failed: ") + opus_strerror(error));
    }
}

extern "C" JNIEXPORT void JNICALL
Java_io_github_ioannes78_voica_opus_NativeOpusBridge_nativeDestroyDecoder(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong handle) {
    if (handle <= 0) {
        return;
    }

    std::lock_guard<std::mutex> guard(g_registry_mutex);
    g_decoders.erase(handle);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* /* vm */, void* /* reserved */) {
    return JNI_VERSION_1_6;
}

JNIEXPORT void JNICALL JNI_OnUnload(JavaVM* /* vm */, void* /* reserved */) {
    std::lock_guard<std::mutex> guard(g_registry_mutex);
    g_decoders.clear();
}
