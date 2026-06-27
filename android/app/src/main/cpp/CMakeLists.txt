cmake_minimum_required(VERSION 3.22.1)
project(amsynth_android LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 14)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# This file lives at android/app/src/main/cpp; the repo root is four levels up.
get_filename_component(REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.." ABSOLUTE)

set(CORE ${REPO_ROOT}/src/core/synth)
set(FREEVERB ${REPO_ROOT}/external/freeverb)

# The exact engine source set the web (Emscripten) build uses — the engine is
# platform-free, so the same translation units compile for Android.
add_library(amsynth SHARED
    amsynth_jni.cpp
    ${CORE}/ADSR.cpp
    ${CORE}/Distortion.cpp
    ${CORE}/LowPassFilter.cpp
    ${CORE}/MidiController.cpp
    ${CORE}/Oscillator.cpp
    ${CORE}/Parameter.cpp
    ${CORE}/Preset.cpp
    ${CORE}/PresetController.cpp
    ${CORE}/SoftLimiter.cpp
    ${CORE}/Synthesizer.cpp
    ${CORE}/TuningMap.cpp
    ${CORE}/VoiceAllocationUnit.cpp
    ${CORE}/VoiceBoard.cpp
    ${REPO_ROOT}/src/core/filesystem.cpp
    ${FREEVERB}/allpass.cpp
    ${FREEVERB}/comb.cpp
    ${FREEVERB}/revmodel.cpp
)

target_include_directories(amsynth PRIVATE
    ${REPO_ROOT}/src
    ${REPO_ROOT}/src/core/synth
    ${REPO_ROOT}/external
    ${REPO_ROOT}/src/standalone   # ParameterQueue.h
)

# No -DPKGDATADIR: the filesystem layer (banks/skins on disk) stays inert and the
# engine constructs from its in-memory default preset, exactly like the web build.
target_compile_definitions(amsynth PRIVATE NDEBUG)

# PresetController uses try/catch and the engine uses RTTI in places; be explicit
# rather than relying on NDK defaults.
target_compile_options(amsynth PRIVATE -fexceptions -frtti -O2)

# Oboe (low-latency audio) ships as a prefab package inside its AAR.
find_package(oboe REQUIRED CONFIG)

find_library(log-lib log)

target_link_libraries(amsynth PRIVATE oboe::oboe ${log-lib})
