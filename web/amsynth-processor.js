// AudioWorklet processor that runs the amsynth DSP engine (compiled to
// amsynth.wasm) on the audio render thread.
//
// The main thread fetches the wasm bytes and passes them in via
// processorOptions; we instantiate the module here, then drive it from
// port messages (notes, parameters) and pump audio in process().
//
// Everything below runs on the audio thread, so the message handler and
// process() never overlap — no locking needed.

class AmsynthProcessor extends AudioWorkletProcessor {
  constructor(options) {
    super();
    this.ex = null;       // wasm exports, once instantiated
    this.mem = null;      // WebAssembly.Memory
    this.pending = [];    // messages received before the module is ready
    this.port.onmessage = (e) => this.onMessage(e.data);
    this.boot(options.processorOptions.wasmBytes);
  }

  // Minimal WASI/env shim — the engine only reaches for an (absent) filesystem.
  imports() {
    const memBuf = () => this.mem.buffer;
    return {
      env: {
        emscripten_notify_memory_growth: () => {},
        __syscall_getdents64: () => 0,
      },
      wasi_snapshot_preview1: {
        fd_close: () => 0,
        fd_seek: () => 0,
        fd_read: (fd, iov, n, nread) => { new DataView(memBuf()).setUint32(nread, 0, true); return 0; },
        fd_write: (fd, iov, n, nwritten) => {
          const dv = new DataView(memBuf());
          let total = 0;
          for (let i = 0; i < n; i++) total += dv.getUint32(iov + i * 8 + 4, true);
          dv.setUint32(nwritten, total, true);
          return 0;
        },
      },
    };
  }

  async boot(bytes) {
    const { instance } = await WebAssembly.instantiate(bytes, this.imports());
    const ex = instance.exports;
    this.mem = ex.memory;
    if (ex._initialize) ex._initialize();
    else if (ex.__wasm_call_ctors) ex.__wasm_call_ctors();
    ex.synth_init(sampleRate);

    this.ex = ex;
    this.ptrL = ex.synth_out_left();
    this.ptrR = ex.synth_out_right();

    // Build the parameter table for the UI.
    const dec = new TextDecoder();
    const readStr = (ptr) => {
      const u8 = new Uint8Array(this.mem.buffer);
      let end = ptr; while (u8[end]) end++;
      return dec.decode(u8.subarray(ptr, end));
    };
    const params = [];
    for (let i = 0; i < ex.synth_param_count(); i++) {
      params.push({
        index: i, name: readStr(ex.synth_param_name(i)),
        min: ex.synth_param_min(i), max: ex.synth_param_max(i),
        def: ex.synth_param_default(i), value: ex.synth_get_param(i),
      });
    }

    for (const m of this.pending) this.apply(m);
    this.pending.length = 0;
    this.port.postMessage({ type: "ready", params });
  }

  onMessage(m) {
    if (!this.ex) { this.pending.push(m); return; }
    this.apply(m);
  }

  apply(m) {
    const ex = this.ex;
    switch (m.type) {
      case "noteOn":     ex.synth_note_on(m.note, m.vel); break;
      case "noteOff":    ex.synth_note_off(m.note); break;
      case "allOff":     ex.synth_all_notes_off(); break;
      case "param":      ex.synth_set_param(m.index, m.value); break;
      case "tonicSplit": ex.synth_set_tonic_split(m.enabled ? 1 : 0); break;
      case "splitPoint": ex.synth_set_tonic_split_point(m.note); break;
      case "tonicRoot":  ex.synth_set_tonic_root(m.note); break;
      case "loadScale": {
        const bytes = new TextEncoder().encode(m.text || "");
        const ptr = ex.synth_scale_buffer();
        const cap = ex.synth_scale_buffer_size();
        const n = Math.min(bytes.length, cap - 1);
        new Uint8Array(this.mem.buffer, ptr, n).set(bytes.subarray(0, n));
        const rc = ex.synth_load_scale(n);
        this.port.postMessage({ type: "scaleLoaded", ok: rc === 0, name: m.name || "" });
        break;
      }
    }
  }

  process(_inputs, outputs) {
    const out = outputs[0];
    if (!this.ex || out.length === 0) return true;
    const n = out[0].length; // render quantum (128)
    this.ex.synth_process(n);
    const buf = this.mem.buffer; // re-read in case memory grew
    out[0].set(new Float32Array(buf, this.ptrL, n));
    if (out[1]) out[1].set(new Float32Array(buf, this.ptrR, n));
    return true;
  }
}

registerProcessor("amsynth-processor", AmsynthProcessor);
