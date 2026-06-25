// Verifies the .scl loader + tonic split through the full wasm/worklet path,
// deterministically: it queries the engine's own note->pitch mapping (exposed
// via synth_note_to_pitch) rather than trying to detect pitch from audio.
// Run with: node web/test-scale.mjs
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
let ready = false, loaded = null;
proc.port._recv = (m) => { if (m.type === "ready") ready = true; if (m.type === "scaleLoaded") loaded = m.ok; };
for (let i = 0; i < 100 && !ready; i++) await new Promise((r) => setTimeout(r, 10));

const ex = proc.ex;                       // wasm exports
const pitch = (n) => ex.synth_note_to_pitch(n);
const drain = (blocks = 4) => { for (let b = 0; b < blocks; b++) proc.process([], [[new Float32Array(128), new Float32Array(128)]]); };
const close = (a, b, tol = 1e-3) => Math.abs(a / b - 1) < tol;

const checks = [];
const check = (name, ok) => { checks.push(ok); console.log(`${ok ? "ok  " : "FAIL"} ${name}`); };

// Default 12-TET: A4 = 440, C4 = 261.6.
check("default A4 == 440 Hz", close(pitch(69), 440));
check("default C4 == 261.6 Hz", close(pitch(60), 440 * 2 ** (-9 / 12)));

// Load an exaggerated 3-note-per-octave JI scale and re-root via a control key.
proc.onMessage({ type: "loadScale", text: "! t.scl\n3-note test\n3\n9/8\n5/4\n2/1\n", name: "t" });
check("scl loaded ok", loaded === true);

proc.onMessage({ type: "tonicSplit", enabled: true });
proc.onMessage({ type: "splitPoint", note: 33 });
proc.onMessage({ type: "noteOn", note: 24, vel: 100 }); // control-zone key -> root = 24
drain();                                                  // consume the queued event

const root = 440 * 2 ** ((24 - 69) / 12);
check("control key set root to note 24 (12-TET pitch)", close(pitch(24), root));
check("note 25 is 9/8 above the root", close(pitch(25) / pitch(24), 9 / 8));
check("note 26 is 5/4 above the root", close(pitch(26) / pitch(24), 5 / 4));
check("note 27 is one 2/1 octave above the root", close(pitch(27) / pitch(24), 2));

// A control-zone key must not start a voice (it's silent).
proc.onMessage({ type: "noteOn", note: 30, vel: 100 });
let peak = 0;
for (let b = 0; b < 40; b++) {
  const L = new Float32Array(128); proc.process([], [[L, new Float32Array(128)]]);
  for (const s of L) peak = Math.max(peak, Math.abs(s));
}
check("control-zone keys are silent", peak < 1e-4);

// Reset to default 12-TET.
proc.onMessage({ type: "loadScale", text: "", name: "12-TET" });
proc.onMessage({ type: "tonicSplit", enabled: false });
check("reset back to 12-TET A4 == 440", close(pitch(69), 440));

const pass = checks.every(Boolean);
console.log(pass ? "PASS: scale loader + tonic split verified in wasm" : "FAIL");
process.exit(pass ? 0 : 1);
