// AudioWorklet processor that runs the amsynth DSP engine (compiled to
// amsynth.wasm) on the audio render thread.
//
// The main thread fetches the wasm bytes and passes them in via
// processorOptions; we instantiate the module here, then drive it from
// port messages (notes, parameters) and pump audio in process().
//
// Everything below runs on the audio thread, so the message handler and
// process() never overlap — no locking needed.
//
// Note: AudioWorkletGlobalScope does NOT provide TextEncoder/TextDecoder, so we
// use the small UTF-8 helpers below instead.

function utf8Encode(str) {
  const out = [];
  for (let i = 0; i < str.length; i++) {
    let c = str.charCodeAt(i);
    if (c < 0x80) {
      out.push(c);
    } else if (c < 0x800) {
      out.push(0xc0 | (c >> 6), 0x80 | (c & 0x3f));
    } else if (c >= 0xd800 && c <= 0xdbff) { // high surrogate
      c = 0x10000 + ((c & 0x3ff) << 10) + (str.charCodeAt(++i) & 0x3ff);
      out.push(0xf0 | (c >> 18), 0x80 | ((c >> 12) & 0x3f), 0x80 | ((c >> 6) & 0x3f), 0x80 | (c & 0x3f));
    } else {
      out.push(0xe0 | (c >> 12), 0x80 | ((c >> 6) & 0x3f), 0x80 | (c & 0x3f));
    }
  }
  return Uint8Array.from(out);
}

function utf8Decode(u8) {
  let s = "", i = 0;
  while (i < u8.length) {
    let c = u8[i++];
    if (c >= 0x80) {
      if (c < 0xe0) c = ((c & 0x1f) << 6) | (u8[i++] & 0x3f);
      else if (c < 0xf0) c = ((c & 0x0f) << 12) | ((u8[i++] & 0x3f) << 6) | (u8[i++] & 0x3f);
      else {
        c = ((c & 0x07) << 18) | ((u8[i++] & 0x3f) << 12) | ((u8[i++] & 0x3f) << 6) | (u8[i++] & 0x3f) - 0x10000;
        s += String.fromCharCode(0xd800 + (c >> 10));
        c = 0xdc00 + (c & 0x3ff);
      }
    }
    s += String.fromCharCode(c);
  }
  return s;
}

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
    try {
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
      const readStr = (ptr) => {
        const u8 = new Uint8Array(this.mem.buffer);
        let end = ptr; while (u8[end]) end++;
        return utf8Decode(u8.subarray(ptr, end));
      };
      this.nparams = ex.synth_param_count();
      const params = [];
      for (let i = 0; i < this.nparams; i++) {
        params.push({
          index: i, name: readStr(ex.synth_param_name(i)),
          min: ex.synth_param_min(i), max: ex.synth_param_max(i),
          def: ex.synth_param_default(i), value: ex.synth_get_param(i),
        });
      }

      for (const m of this.pending) this.apply(m);
      this.pending.length = 0;
      this.port.postMessage({ type: "ready", params, ccForName: this.readControllerMap() });
    } catch (e) {
      // Errors here are otherwise invisible (audio thread); surface them.
      this.port.postMessage({ type: "bootError", msg: String(e && (e.stack || e.message || e)) });
    }
  }

  // Decode `len` bytes from the shared text buffer.
  readTextBuf(len) {
    return utf8Decode(new Uint8Array(this.mem.buffer, this.ex.synth_text_buffer(), len));
  }

  // Read the current CC->parameter map back as { paramName: ccNumber }.
  readControllerMap() {
    const lines = this.readTextBuf(this.ex.synth_get_controllers()).split("\n");
    const ccForName = {};
    for (let cc = 0; cc < lines.length; cc++) {
      const name = lines[cc].trim();
      if (name && name !== "null") ccForName[name] = cc;
    }
    return ccForName;
  }

  // Current value of every parameter, for refreshing the UI sliders.
  paramValues() {
    const v = [];
    for (let i = 0; i < this.nparams; i++) v.push(this.ex.synth_get_param(i));
    return v;
  }

  // The loaded bank's 128 preset names.
  presetNames() {
    const arr = this.readTextBuf(this.ex.synth_get_preset_names()).split("\n");
    if (arr.length && arr[arr.length - 1] === "") arr.pop();
    return arr;
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
      case "midi":       ex.synth_midi(m.b0, m.b1, m.b2, m.length); break;
      case "param":      ex.synth_set_param(m.index, m.value); break;
      case "tonicSplit": ex.synth_set_tonic_split(m.enabled ? 1 : 0); break;
      case "splitPoint": ex.synth_set_tonic_split_point(m.note); break;
      case "tonicRoot":  ex.synth_set_tonic_root(m.note); break;
      case "loadScale":  this.loadText(m.text, ex.synth_load_scale, "scaleLoaded", m.name); break;
      case "loadKeymap": this.loadText(m.text, ex.synth_load_keymap, "keymapLoaded", m.name); break;
      case "loadControllers":
        this.loadText(m.text, ex.synth_load_controllers, "controllersLoaded", m.name);
        this.port.postMessage({ type: "ccMap", ccForName: this.readControllerMap() });
        break;
      case "getControllers":
        this.port.postMessage({ type: "controllers", text: this.readTextBuf(ex.synth_get_controllers()) });
        break;
      case "loadBank": {
        // Banks can exceed the shared text buffer, so use a malloc'd buffer.
        const bytes = utf8Encode(m.text || "");
        const ptr = ex.malloc(bytes.length + 1);
        const mem = new Uint8Array(this.mem.buffer);
        mem.set(bytes, ptr);
        mem[ptr + bytes.length] = 0;
        const ok = ex.synth_load_bank(ptr) === 0;
        ex.free(ptr);
        this.port.postMessage({ type: "bankLoaded", ok, name: m.name || "",
          names: this.presetNames(), preset: ex.synth_get_preset(), values: this.paramValues() });
        break;
      }
      case "setPreset":
        ex.synth_set_preset(m.preset);
        this.port.postMessage({ type: "presetChanged", preset: ex.synth_get_preset(), values: this.paramValues() });
        break;
      case "getState":
        this.port.postMessage({ type: "state", text: this.readTextBuf(ex.synth_get_state()) });
        break;
      case "loadState": {
        const bytes = utf8Encode(m.text || "");
        const ptr = ex.synth_text_buffer();
        const cap = ex.synth_text_buffer_size();
        const n = Math.min(bytes.length, cap - 1);
        new Uint8Array(this.mem.buffer, ptr, n).set(bytes.subarray(0, n));
        const ok = ex.synth_set_state(n) === 0;
        // State restore touches every parameter, so refresh the whole UI.
        this.port.postMessage({ type: "stateLoaded", ok, name: m.name || "", values: this.paramValues() });
        break;
      }
    }
  }

  // Write .scl/.kbm/controllers text into the shared wasm buffer and load it.
  loadText(text, loadFn, replyType, name) {
    const bytes = utf8Encode(text || "");
    const ptr = this.ex.synth_text_buffer();
    const cap = this.ex.synth_text_buffer_size();
    const n = Math.min(bytes.length, cap - 1);
    new Uint8Array(this.mem.buffer, ptr, n).set(bytes.subarray(0, n));
    const rc = loadFn(n);
    this.port.postMessage({ type: replyType, ok: rc === 0, name: name || "" });
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
