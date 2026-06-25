#!/usr/bin/env bash
#
# Build the amsynth DSP engine to a self-contained WebAssembly "reactor" module
# (web/amsynth.wasm) that an AudioWorklet can instantiate directly.
#
# Requires the Emscripten SDK on PATH (e.g. `source ~/emsdk/emsdk_env.sh`).
#
# Notes:
#   * No -DPKGDATADIR  -> the filesystem layer (banks/skins) is inert, so the
#     engine constructs cleanly with no host filesystem.
#   * -fwasm-exceptions -> native wasm exception handling (PresetController uses
#     try/catch); avoids the JS "invoke_*" trampolines that a standalone wasm
#     module can't import inside an AudioWorklet.
#   * -sSTANDALONE_WASM + --no-entry -> a reactor wasm with only WASI imports,
#     which we stub in the worklet. No Emscripten JS glue needed.

set -euo pipefail
cd "$(dirname "$0")/.."   # repo root

OUT=web/amsynth.wasm

emcc \
	-O3 -DNDEBUG -std=c++14 \
	-fwasm-exceptions \
	-I src -I src/core/synth -I external \
	web/synth_bridge.cpp \
	external/freeverb/allpass.cpp \
	external/freeverb/comb.cpp \
	external/freeverb/revmodel.cpp \
	src/core/synth/ADSR.cpp \
	src/core/synth/Distortion.cpp \
	src/core/synth/LowPassFilter.cpp \
	src/core/synth/MidiController.cpp \
	src/core/synth/Oscillator.cpp \
	src/core/synth/Parameter.cpp \
	src/core/synth/Preset.cpp \
	src/core/synth/PresetController.cpp \
	src/core/synth/SoftLimiter.cpp \
	src/core/synth/Synthesizer.cpp \
	src/core/synth/TuningMap.cpp \
	src/core/synth/VoiceAllocationUnit.cpp \
	src/core/synth/VoiceBoard.cpp \
	src/core/filesystem.cpp \
	-sSTANDALONE_WASM=1 \
	-sWASM_BIGINT=1 \
	-sALLOW_MEMORY_GROWTH=1 \
	-Wl,--no-entry \
	-sEXPORTED_FUNCTIONS=@web/exports.txt \
	-o "$OUT"

echo "built $OUT ($(du -h "$OUT" | cut -f1))"
