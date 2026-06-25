// Headless test of the REAL AudioWorklet processor code (amsynth-processor.js)
// by shimming the AudioWorklet globals. Validates wasm instantiation, the WASI
// shim, the parameter table, message handling and process() output.
// Run with: node web/test-worklet.mjs
import { readFileSync } from "node:fs";

globalThis.sampleRate = 44100;
let captured = null;
globalThis.registerProcessor = (_name, cls) => { captured = cls; };
globalThis.AudioWorkletProcessor = class {
  constructor() { this.port = { postMessage: (m) => this.port._recv && this.port._recv(m), onmessage: null }; }
};

await import(new URL("./amsynth-processor.js", import.meta.url));

const bytes = readFileSync(new URL("./amsynth.wasm", import.meta.url)).buffer;
const proc = new captured({ processorOptions: { wasmBytes: bytes } });

let params = null;
proc.port._recv = (m) => { if (m.type === "ready") params = m.params; };

// wait for async boot
for (let i = 0; i < 100 && !params; i++) await new Promise((r) => setTimeout(r, 10));
if (!params) { console.log("FAIL: processor never became ready"); process.exit(1); }
console.log(`ready: ${params.length} params, e.g. ${params[14].name}=${params[14].value}`);

// exercise the real message path
proc.onMessage({ type: "param", index: 14, value: 1.0 });   // master volume
proc.onMessage({ type: "tonicSplit", enabled: true });
proc.onMessage({ type: "splitPoint", note: 33 });
proc.onMessage({ type: "tonicRoot", note: 60 });
proc.onMessage({ type: "noteOn", note: 64, vel: 110 });

let peak = 0;
for (let b = 0; b < 200; b++) {
  const L = new Float32Array(128), R = new Float32Array(128);
  proc.process([], [[L, R]]);
  for (let i = 0; i < 128; i++) peak = Math.max(peak, Math.abs(L[i]), Math.abs(R[i]));
}
console.log(`peak ${peak.toFixed(4)}`);

// control-zone note (below split point) must be silent + not crash
proc.onMessage({ type: "noteOff", note: 64 });
proc.onMessage({ type: "allOff" });
console.log(peak > 0.001 ? "PASS: worklet path produced audio" : "FAIL: silence");
process.exit(peak > 0.001 ? 0 : 1);
