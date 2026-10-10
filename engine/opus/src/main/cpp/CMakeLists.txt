cmake_minimum_required(VERSION 3.22.1)
project(voica_opus_jni LANGUAGES C CXX)

include(FetchContent)

set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_DRED OFF CACHE BOOL "" FORCE)
set(OPUS_OSCE OFF CACHE BOOL "" FORCE)
set(OPUS_DEEP_PLC OFF CACHE BOOL "" FORCE)

set(VOICA_OPUS_ARCHIVE "$ENV{VOICA_OPUS_ARCHIVE}")
if(VOICA_OPUS_ARCHIVE AND EXISTS "${VOICA_OPUS_ARCHIVE}")
    file(TO_CMAKE_PATH "${VOICA_OPUS_ARCHIVE}" VOICA_OPUS_ARCHIVE_CMAKE)
    set(VOICA_OPUS_URLS "file://${VOICA_OPUS_ARCHIVE_CMAKE}")
else()
    # Both HTTPS endpoints serve the same Opus 1.6.1 release archive. Integrity is
    # pinned below, so the mirror improves availability without weakening provenance.
    set(
        VOICA_OPUS_URLS
        "https://downloads.xiph.org/releases/opus/opus-1.6.1.tar.gz"
        "https://ftp.osuosl.org/pub/xiph/releases/opus/opus-1.6.1.tar.gz"
    )
endif()

FetchContent_Declare(
    opus_src
    URL ${VOICA_OPUS_URLS}
    URL_HASH SHA256=6ffcb593207be92584df15b32466ed64bbec99109f007c82205f0194572411a1
)
FetchContent_MakeAvailable(opus_src)

add_library(voica_opus_jni SHARED voica_opus_jni.cpp)

target_compile_features(voica_opus_jni PRIVATE cxx_std_17)
target_compile_options(voica_opus_jni PRIVATE -Wall -Wextra -Werror)

target_link_libraries(
    voica_opus_jni
    PRIVATE
    Opus::opus
    log
)
