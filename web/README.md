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
| `test-scale.mjs`       | headless check of the .scl/.kbm loaders + tonic-split re-root |
| `test-midi.mjs`        | headless check of the raw-MIDI forwarder (notes + sustain CC) |
| `test-controllers.mjs` | headless check of the CC->parameter map loading/export |
| `test-bank.mjs`        | headless check of preset-bank loading + preset selection |

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
node web/test-scale.mjs     # loads an .scl/.kbm, re-roots via the control zone, verifies pitch
node web/test-midi.mjs      # raw-MIDI path: note-on makes sound, sustain CC holds/releases
node web/test-controllers.mjs  # CC->parameter map: defaults, custom load, export, reset
node web/test-bank.mjs      # preset bank: load (incl. >64 KB), names, preset selection
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

- **Preset banks** load from an amsynth `.bank` file via the "Presets" section
  (`Synthesizer::loadBankFromString` over a malloc'd buffer, since banks can
  exceed the shared text buffer); pick a preset and the sliders update. No bank
  is bundled by default — load one of `data/banks/*.bank`.
- **Generic sliders**, not the skinned panel. The skin PNGs + `layout.ini` in
  `data/skins/default` could be reused on a canvas for a faithful UI.
- **Web MIDI input** is supported: connect a hardware controller and the page
  forwards raw channel-voice messages (notes, pitch bend, sustain and other CC,
  program change) to the worklet via `synth_midi`. Web MIDI needs a secure
  context — `localhost` counts.
- **CC -> parameter mappings** load from an amsynth `controllers` file (one
  parameter name per line; line N = CC N) via the "MIDI CC mappings" section;
  the engine's built-in defaults (CC7 volume, CC74 cutoff, mod-wheel, ...) apply
  otherwise. Sliders are tagged with their CC, and "Export current" downloads an
  editable template.
- **Scala scales (.scl) and keymaps (.kbm)** load from a file (or the built-in
  5-limit JI example via the page's "Load 5-limit JI" button). With the default
  12-TET scale the tonic-split re-root is inaudible by design; load a non-12-TET
  scale to hear the tonic move as you play control-zone keys.
