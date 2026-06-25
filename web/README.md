# amsynth — web preview (Emscripten + AudioWorklet)

A browser preview of the amsynth DSP engine. The portable C++ engine in
`src/core/synth` is compiled to WebAssembly and driven from a Web Audio
**AudioWorklet**; a small HTML page provides an on-screen keyboard, sliders for
all 41 parameters, and the tonic-split controls.

The GUI here is a thin HTML/JS front-end — it does **not** use JUCE. Only the
audio engine is shared with the desktop/plugin builds, so the two stay in sync
at the DSP level.

## Layout

| File | Role |
|------|------|
| `synth_bridge.cpp`     | C ABI wrapper around `Synthesizer` (notes, params, render) |
| `build.sh`             | compiles the engine + bridge to `amsynth.wasm` |
| `exports.txt`          | exported C symbols |
| `amsynth-processor.js` | AudioWorklet: instantiates the wasm, renders on the audio thread |
| `index.html`           | UI: keyboard, parameter sliders, tonic-split demo |
| `test-node.mjs`        | headless check that the engine makes sound in wasm |
| `test-worklet.mjs`     | headless check of the actual worklet code path |

## Build

Requires the Emscripten SDK:

```sh
# one-time
git clone https://github.com/emscripten-core/emsdk ~/emsdk
~/emsdk/emsdk install latest && ~/emsdk/emsdk activate latest

# each shell
source ~/emsdk/emsdk_env.sh

# build  ->  web/amsynth.wasm  (~250 KB)
./web/build.sh
```

## Run

Serve over http (AudioWorklet + fetch don't work from `file://`):

```sh
python3 -m http.server -d web 8000
# open http://localhost:8000/  and click “Start audio”
```

No special COOP/COEP headers are needed — the wasm bytes are fetched on the main
thread and handed to the worklet via `processorOptions` (no SharedArrayBuffer).

## Test (headless, no browser)

```sh
node web/test-node.mjs      # engine path: plays a note, checks the output is non-silent
node web/test-worklet.mjs   # worklet path: same, through amsynth-processor.js
```

## How it fits together

```
index.html ──postMessage──▶ amsynth-processor.js (audio thread)
  (UI events)                  │  WebAssembly.instantiate(amsynth.wasm)
                               ▼
                     synth_bridge.cpp  ──▶  Synthesizer::process()
```

The engine is compiled **without** `-DPKGDATADIR`, so its filesystem layer is
inert (no preset banks / skins on disk) and the default in-memory preset is
used. `-fwasm-exceptions` is required (PresetController uses try/catch) and keeps
the module instantiable inside an AudioWorklet with only a few WASI stubs.

## Known limitations / next steps

- **No preset banks.** Only the built-in default preset. Could be added by
  bundling a `.bank` file and feeding it to `Synthesizer::loadBank` via MEMFS.
- **Generic sliders**, not the skinned panel. The skin PNGs + `layout.ini` in
  `data/skins/default` could be reused on a canvas for a faithful UI.
- **No MIDI input.** Add the Web MIDI API and forward note/CC to the worklet.
- **Tonic split** is wired, but with the default 12-TET scale the re-root is
  inaudible; load a `.scl` scale (needs a small `loadTuningScale` bridge that
  accepts file bytes) to hear it.
