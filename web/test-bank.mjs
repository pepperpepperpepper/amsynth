// Verifies preset-bank loading through the wasm/worklet path using the real
// factory bank (and a >64 KB bank to exercise the malloc path), and that
// selecting a preset updates the parameter values.
// Run with: node web/test-bank.mjs
import { readFileSync } from "node:fs";

globalThis.sampleRate = 44100;
let captured = null;
globalThis.registerProcessor = (_n, cls) => { captured = cls; };
globalThis.AudioWorkletProcessor = class {
  constructor() { this.port = { postMessage: (m) => this.port._recv && this.port._recv(m), onmessage: null }; }
};
await import(new URL("./amsynth-processor.js", import.meta.url));
const bytes = readFileSync(new URL("./amsynth.wasm", import.meta.url)).buffer;
const read = (p) => readFileSync(new URL(p, import.meta.url), "utf8");

const proc = new captured({ processorOptions: { wasmBytes: bytes } });
let ready = false, bank = null, preset = null;
proc.port._recv = (m) => {
  if (m.type === "ready") ready = true;
  if (m.type === "bankLoaded") bank = m;
  if (m.type === "presetChanged") preset = m;
};
for (let i = 0; i < 100 && !ready; i++) await new Promise((r) => setTimeout(r, 10));

const checks = [];
const check = (name, ok) => { checks.push(ok); console.log(`${ok ? "ok  " : "FAIL"} ${name}`); };

// Real factory bank.
proc.onMessage({ type: "loadBank", text: read("../data/banks/amsynth_factory.bank"), name: "factory" });
check("factory bank loaded", bank.ok);
check("128 preset names", bank.names.length === 128);
check("first preset is named", bank.names[0].length > 0);
console.log(`   e.g. preset 0 = "${bank.names[0]}"`);

// Selecting a preset changes the parameter values.
const v0 = bank.values.slice();
let differs = false;
for (const idx of [1, 5, 10, 20, 50]) {
  proc.onMessage({ type: "setPreset", preset: idx });
  if (preset.values.some((x, i) => x !== v0[i])) { differs = true; break; }
}
check("selecting a preset changes parameters", differs);

// A bank larger than the 64 KB shared text buffer (exercises the malloc path).
proc.onMessage({ type: "loadBank", text: read("../data/banks/BriansBank21.amSynth.bank"), name: "big" });
check("large bank (>64 KB) loaded via malloc", bank.ok && bank.names.length === 128);

// Malformed data is rejected.
proc.onMessage({ type: "loadBank", text: "not a bank", name: "bad" });
check("malformed bank rejected", bank.ok === false);

const pass = checks.every(Boolean);
console.log(pass ? "PASS: preset-bank loading verified in wasm" : "FAIL");
process.exit(pass ? 0 : 1);
