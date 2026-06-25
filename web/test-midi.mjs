// Verifies the generic raw-MIDI forwarder (synth_midi) through the wasm/worklet
// path: a raw note-on makes sound, and a sustain-pedal CC (CC64) holds the note
// after note-off and releases it on pedal-up. (Web MIDI itself is a browser API
// and isn't exercised here; this covers everything downstream of it.)
// Run with: node web/test-midi.mjs
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
let ready = false;
proc.port._recv = (m) => { if (m.type === "ready") ready = true; };
for (let i = 0; i < 100 && !ready; i++) await new Promise((r) => setTimeout(r, 10));

const midi = (b0, b1, b2, length = 3) => proc.onMessage({ type: "midi", b0, b1, b2, length });
const param = (index, value) => proc.onMessage({ type: "param", index, value });
function renderPeak(blocks) {
  let peak = 0;
  for (let b = 0; b < blocks; b++) {
    const L = new Float32Array(128); proc.process([], [[L, new Float32Array(128)]]);
    for (const s of L) peak = Math.max(peak, Math.abs(s));
  }
  return peak;
}

// Snappy envelope so the test is fast and unambiguous.
param(14, 1.0); // master volume max
param(0, 0.0);  // amp attack -> instant
param(2, 1.0);  // amp sustain -> full
param(3, 0.0);  // amp release -> instant

const checks = [];
const check = (name, ok) => { checks.push(ok); console.log(`${ok ? "ok  " : "FAIL"} ${name}`); };

midi(0xB0, 64, 127);          // sustain pedal DOWN (CC64)
midi(0x90, 60, 100);          // note on C4 (raw MIDI)
const peakA = renderPeak(40);
check("raw MIDI note-on produces audio", peakA > 0.05);

midi(0x90, 60, 0);            // note OFF (note-on, velocity 0)
const peakB = renderPeak(40);
check("note held by sustain pedal after note-off", peakB > 0.05);

midi(0xB0, 64, 0);            // sustain pedal UP -> release
renderPeak(60);              // let the (instant) release finish
const peakC = renderPeak(20);
check("note released after sustain pedal up", peakC < 0.01);

// A 2-byte message (program change) must not crash / mis-parse.
midi(0xC0, 0, 0, 2);
renderPeak(4);
check("2-byte program-change handled without error", true);

const pass = checks.every(Boolean);
console.log(`peaks: A=${peakA.toFixed(3)} B=${peakB.toFixed(3)} C=${peakC.toFixed(3)}`);
console.log(pass ? "PASS: raw MIDI + CC forwarding verified in wasm" : "FAIL");
process.exit(pass ? 0 : 1);
