// Verifies the MIDI controller-map (.controllers) loading through the
// wasm/worklet path: default CC->parameter mappings work, a custom map remaps a
// CC, the change drives the parameter, export reflects it, and reset restores
// the built-in defaults.
// Run with: node web/test-controllers.mjs
import { readFileSync } from "node:fs";

globalThis.sampleRate = 44100;
let captured = null;
globalThis.registerProcessor = (_n, cls) => { captured = cls; };
globalThis.AudioWorkletProcessor = class {
  constructor() { this.port = { postMessage: (m) => this.port._recv && this.port._recv(m), onmessage: null }; }
};
await import(new URL("./amsynth-processor.js", import.meta.url));
const bytes = readFileSync(new URL("./amsynth.wasm", import.meta.url)).buffer;

const proc = new captured({ processorOptions: { wasmBytes: bytes } });
let params = null, ccForName = null, exported = null;
proc.port._recv = (m) => {
  if (m.type === "ready") { params = m.params; ccForName = m.ccForName; }
  if (m.type === "ccMap") ccForName = m.ccForName;
  if (m.type === "controllers") exported = m.text;
};
for (let i = 0; i < 100 && !params; i++) await new Promise((r) => setTimeout(r, 10));

const ex = proc.ex;
const cutoffIdx = params.find((p) => p.name === "filter_cutoff").index;
const drain = (n = 2) => { for (let b = 0; b < n; b++) proc.process([], [[new Float32Array(128), new Float32Array(128)]]); };
const midi = (b0, b1, b2) => { proc.onMessage({ type: "midi", b0, b1, b2, length: 3 }); drain(); };
const cutoff = () => ex.synth_get_param(cutoffIdx);

const checks = [];
const check = (name, ok) => { checks.push(ok); console.log(`${ok ? "ok  " : "FAIL"} ${name}`); };

// Built-in defaults reported to the UI.
check("default: filter_cutoff tagged CC74", ccForName.filter_cutoff === 74);
check("default: master_vol tagged CC7", ccForName.master_vol === 7);

// Default CC74 actually sweeps the cutoff.
midi(0xB0, 74, 0);   const d0 = cutoff();
midi(0xB0, 74, 127); const d1 = cutoff();
check("default CC74 sweeps cutoff", d1 > d0);

// Custom map: move the cutoff onto CC1 (mod wheel), clear everything else.
let map = "";
for (let cc = 0; cc < 128; cc++) map += (cc === 1 ? "filter_cutoff\n" : "null\n");
proc.onMessage({ type: "loadControllers", text: map, name: "custom" });
check("custom map: filter_cutoff now tagged CC1", ccForName.filter_cutoff === 1);

midi(0xB0, 1, 0);   const c0 = cutoff();
midi(0xB0, 1, 127); const c1 = cutoff();
check("custom CC1 sweeps cutoff", c1 > c0);

midi(0xB0, 1, 64);  const mid = cutoff();
midi(0xB0, 74, 0);  check("old default CC74 is now inert", cutoff() === mid);

// Export reflects the forward map.
proc.onMessage({ type: "getControllers" });
check("export: line 1 is filter_cutoff", exported && exported.split("\n")[1] === "filter_cutoff");

// Reset restores defaults.
proc.onMessage({ type: "loadControllers", text: "", name: "defaults" });
check("reset: filter_cutoff back on CC74", ccForName.filter_cutoff === 74);

const pass = checks.every(Boolean);
console.log(pass ? "PASS: controller-map loading verified in wasm" : "FAIL");
process.exit(pass ? 0 : 1);
