// Verifies preset-state export/import through the wasm/worklet path: an edited
// sound (parameters + properties) survives a getState -> change -> setState
// round-trip, and malformed/empty state is rejected.
// Run with: node web/test-state.mjs
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
let ready = null, state = null, loaded = null;
proc.port._recv = (m) => {
  if (m.type === "ready") ready = m;
  if (m.type === "state") state = m;
  if (m.type === "stateLoaded") loaded = m;
};
for (let i = 0; i < 100 && !ready; i++) await new Promise((r) => setTimeout(r, 10));

const checks = [];
const check = (name, ok) => { checks.push(ok); console.log(`${ok ? "ok  " : "FAIL"} ${name}`); };

// Pick a parameter with a real range to edit.
const p = ready.params.find((q) => q.max > q.min);
const target = p.min + 0.37 * (p.max - p.min);
const tol = (p.max - p.min) * 0.01;
proc.onMessage({ type: "param", index: p.index, value: target });

// Also flip a property so the export covers more than just parameters.
proc.onMessage({ type: "tonicSplit", enabled: true });

// Export.
proc.onMessage({ type: "getState" });
check("state exported", state && state.text.length > 0);
check("state includes parameters", /\d/.test(state.text));
check("state includes properties", state.text.includes("<property>"));
check("tonic-split property serialized", state.text.includes("tuning_split"));

// Move the parameter away, then restore from the exported state.
proc.onMessage({ type: "param", index: p.index, value: p.min });
proc.onMessage({ type: "loadState", text: state.text, name: "round-trip" });
check("state import reported ok", loaded && loaded.ok);
check(`parameter "${p.name}" restored (${loaded.values[p.index].toFixed(3)} ~ ${target.toFixed(3)})`,
  Math.abs(loaded.values[p.index] - target) <= tol);

// Empty / malformed state is rejected by the guard.
loaded = null;
proc.onMessage({ type: "loadState", text: "", name: "empty" });
check("empty state rejected", loaded && loaded.ok === false);

const pass = checks.every(Boolean);
console.log(pass ? "PASS: preset-state export/import verified in wasm" : "FAIL");
process.exit(pass ? 0 : 1);
