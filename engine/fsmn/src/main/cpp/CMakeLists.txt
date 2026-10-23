cmake_minimum_required(VERSION 3.22.1)
project(voica_fsmn_candidate_jni LANGUAGES C CXX)

include(FetchContent)

# Header-only use of the exact ONNX Runtime build already carried by sherpa-onnx
# v1.13.8. We intentionally do NOT link or package the imported libonnxruntime.so
# from this archive; the candidate JNI resolves the single APK copy at runtime.
FetchContent_Declare(
    onnxruntime_headers
    URL https://github.com/csukuangfj/onnxruntime-libs/releases/download/v1.28.2/onnxruntime-android-1.28.2.zip
    URL_HASH SHA256=01518867f78241138b6aa25925802e843a4fa9085af8d303d49e35bbb52aff4d
)
FetchContent_GetProperties(onnxruntime_headers)
if(NOT onnxruntime_headers_POPULATED)
    FetchContent_Populate(onnxruntime_headers)
endif()

add_library(voica_fsmn_candidate_jni SHARED voica_fsmn_runtime_probe.cpp)

target_compile_features(voica_fsmn_candidate_jni PRIVATE cxx_std_17)
target_compile_options(voica_fsmn_candidate_jni PRIVATE -Wall -Wextra -Werror)
target_include_directories(
    voica_fsmn_candidate_jni
    PRIVATE
    ${onnxruntime_headers_SOURCE_DIR}/headers
)
target_link_libraries(
    voica_fsmn_candidate_jni
    PRIVATE
    android
    dl
    log
)
