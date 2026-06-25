// Headless smoke test: instantiate amsynth.wasm, play a note, verify it makes
// sound. Run with: node web/test-node.mjs
import { readFileSync } from "node:fs";

const bytes = readFileSync(new URL("./amsynth.wasm", import.meta.url));

const state = { mem: null };
const u8 = () => new Uint8Array(state.mem.buffer);

// Minimal WASI / env shim. The engine only touches files on the (absent) host
// filesystem, so these can be near no-ops.
const imports = {
  env: {
    emscripten_notify_memory_growth() {},
    __syscall_getdents64: () => 0, // empty directory
  },
  wasi_snapshot_preview1: {
    fd_close: () => 0,
    fd_seek: () => 0,
    fd_read: (fd, iov, iovcnt, nread) => { new DataView(state.mem.buffer).setUint32(nread, 0, true); return 0; },
    fd_write: (fd, iov, iovcnt, nwritten) => {
      const dv = new DataView(state.mem.buffer);
      let total = 0, text = "";
      for (let i = 0; i < iovcnt; i++) {
        const ptr = dv.getUint32(iov + i * 8, true);
        const len = dv.getUint32(iov + i * 8 + 4, true);
        text += new TextDecoder().decode(u8().subarray(ptr, ptr + len));
        total += len;
      }
      if (text.trim()) console.log(`[wasm fd${fd}] ${text.trimEnd()}`);
      dv.setUint32(nwritten, total, true);
      return 0;
    },
  },
};

const { instance } = await WebAssembly.instantiate(bytes, imports);
const ex = instance.exports;
state.mem = ex.memory;
if (ex._initialize) ex._initialize();
else if (ex.__wasm_call_ctors) ex.__wasm_call_ctors();

const SR = 44100, N = 128;
ex.synth_init(SR);

// Report a few parameters via the bridge.
const decode = (ptr) => { const m = u8(); let s = ""; for (let i = ptr; m[i]; i++) s += String.fromCharCode(m[i]); return s; };
console.log(`parameters: ${ex.synth_param_count()}`);
for (const i of [11, 9, 14]) // cutoff, resonance, master volume
  console.log(`  [${i}] ${decode(ex.synth_param_name(i))} = ${ex.synth_get_param(i).toFixed(3)} (min ${ex.synth_param_min(i)}, max ${ex.synth_param_max(i)})`);

// Make sure it's audible: crank master volume, play middle C.
ex.synth_set_param(14, ex.synth_param_max(14));
ex.synth_note_on(60, 110);

let peak = 0, sumSq = 0, total = 0;
const ptrL = ex.synth_out_left();
for (let b = 0; b < 200; b++) { // ~0.58 s
  ex.synth_process(N);
  const L = new Float32Array(state.mem.buffer, ptrL, N);
  for (let i = 0; i < N; i++) { const s = L[i]; peak = Math.max(peak, Math.abs(s)); sumSq += s * s; total++; }
}
const rms = Math.sqrt(sumSq / total);
console.log(`rendered ${total} frames | peak ${peak.toFixed(4)} | rms ${rms.toFixed(4)}`);
console.log(peak > 0.001 ? "PASS: engine produced audio" : "FAIL: silence");
process.exit(peak > 0.001 ? 0 : 1);
