/*
 *  amsynth_jni.cpp
 *
 *  JNI + Oboe bridge that runs the platform-free amsynth DSP engine
 *  (src/core/synth) on Android. It owns a single Synthesizer and an Oboe output
 *  stream whose data callback renders straight into the interleaved buffer.
 *
 *  Thread-safety mirrors the desktop OSC path: the UI (JNI) thread never touches
 *  the Synthesizer directly while audio is running. Parameter changes go through
 *  the shared lock-free ParameterQueue, and MIDI note/CC events through a small
 *  lock-free ring; both are drained on the audio thread at the top of each
 *  render. No locks on the audio thread.
 *
 *  This file is part of amsynth and is licensed under the GNU GPL v2+.
 */

#include "core/synth/Synthesizer.h"
#include "core/synth/PresetController.h"
#include "core/synth/Preset.h"
#include "core/controls.h"
#include "core/midi.h"
#include "core/types.h"
#include "ParameterQueue.h"   // src/standalone — reused verbatim

#include <oboe/Oboe.h>

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <cstdint>
#include <memory>
#include <vector>
#include <unistd.h>

#define LOG_TAG "amsynth"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Lock-free SPSC ring of raw 1-3 byte MIDI messages (UI thread -> audio thread).
struct MidiMsg { uint8_t bytes[3]; uint8_t len; };

class MidiRing {
public:
	bool push(uint8_t b0, uint8_t b1, uint8_t b2, uint8_t len) {
		const unsigned tail = tail_.load(std::memory_order_relaxed);
		const unsigned next = (tail + 1) & kMask;
		if (next == head_.load(std::memory_order_acquire))
			return false; // full — drop rather than block audio
		buffer_[tail] = {{b0, b1, b2}, len};
		tail_.store(next, std::memory_order_release);
		return true;
	}

	template <class Fn>
	void drain(Fn &&fn) {
		unsigned head = head_.load(std::memory_order_relaxed);
		const unsigned tail = tail_.load(std::memory_order_acquire);
		while (head != tail) {
			fn(buffer_[head]);
			head = (head + 1) & kMask;
		}
		head_.store(head, std::memory_order_release);
	}

private:
	static const unsigned kSize = 1024; // power of two
	static const unsigned kMask = kSize - 1;
	MidiMsg buffer_[kSize];
	std::atomic<unsigned> head_ {0};
	std::atomic<unsigned> tail_ {0};
};

class AmsynthAudio : public oboe::AudioStreamDataCallback {
public:
	AmsynthAudio() {
		// Reserve so the audio thread's per-block bookkeeping never allocates.
		midiStore_.reserve(256);
		midiEvents_.reserve(256);
		midiOut_.reserve(256);
	}

	bool start(int requestedSampleRate) {
		synth_ = new Synthesizer();

		oboe::AudioStreamBuilder b;
		b.setDirection(oboe::Direction::Output)
		 ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
		 ->setSharingMode(oboe::SharingMode::Exclusive)
		 ->setFormat(oboe::AudioFormat::Float)
		 ->setChannelCount(oboe::ChannelCount::Stereo)
		 ->setDataCallback(this);
		if (requestedSampleRate > 0)
			b.setSampleRate(requestedSampleRate);

		oboe::Result r = b.openStream(stream_);
		if (r != oboe::Result::OK) {
			LOGE("failed to open stream: %s", oboe::convertToText(r));
			delete synth_; synth_ = nullptr;
			return false;
		}

		// The engine must run at the stream's *actual* rate.
		synth_->setSampleRate(stream_->getSampleRate());
		stream_->setBufferSizeInFrames(stream_->getFramesPerBurst() * 2);

		r = stream_->requestStart();
		if (r != oboe::Result::OK) {
			LOGE("failed to start stream: %s", oboe::convertToText(r));
			stop();
			return false;
		}
		LOGI("started: %d Hz, burst %d frames",
		     stream_->getSampleRate(), stream_->getFramesPerBurst());
		return true;
	}

	void stop() {
		if (stream_) {
			stream_->stop();
			stream_->close();
			stream_.reset();
		}
		delete synth_;
		synth_ = nullptr;
	}

	// --- control surface (UI thread) ---------------------------------------
	void noteOn(int n, int v)  { midi_.push(MIDI_STATUS_NOTE_ON,  n & 0x7f, v & 0x7f, 3); }
	void noteOff(int n)        { midi_.push(MIDI_STATUS_NOTE_OFF, n & 0x7f, 0, 3); }
	void allNotesOff()         { midi_.push(MIDI_STATUS_CONTROLLER, MIDI_CC_ALL_NOTES_OFF, 0, 3); }
	void midi(int b0, int b1, int b2, int len) {
		if (len >= 1 && len <= 3) midi_.push(b0, b1, b2, (uint8_t) len);
	}
	void setParameter(int index, float value) { params_.push(index, value, /*normalized=*/false); }

	// Apply a whole sound (preset) by pushing every parameter through the same
	// lock-free queue — so the audio thread applies it, exactly like a slider
	// drag. The UI thread never touches the live Synthesizer here.
	void applyPreset(const Preset &p) {
		for (int i = 0; i < kAmsynthParameterCount; i++)
			setParameter(i, p.getParameter(i).getValue());
	}

	// Serialize the current sound for "save". Reads the live preset's floats;
	// like getParameter() this races the audio thread by at most a block, which
	// is harmless for a save action (same approach as the web build).
	std::string getState() { return synth_ ? synth_->getState() : std::string(); }

	// Microtuning (Scala .scl / .kbm). Loading rewrites the TuningMap vectors,
	// which the audio thread reads in process() — applying directly races and
	// crashes (use-after-realloc / empty-scale OOB). So each change runs while the
	// audio callback is parked (outputs silence for the brief mutation window).
	bool loadScale(const std::string &s)  { bool ok = false; withTuningGuard([&] { ok = synth_->loadTuningScaleFromString(s.c_str()) == 0; }); return ok; }
	bool loadKeymap(const std::string &s) { bool ok = false; withTuningGuard([&] { ok = synth_->loadTuningKeymapFromString(s.c_str()) == 0; }); return ok; }
	void resetTuning() { withTuningGuard([&] { synth_->loadTuningScaleFromString(""); synth_->loadTuningKeymapFromString(""); }); }
	// The MIDI note that becomes the scale's 1/1 (the tuning root / tonic).
	void setTuningRoot(int note) { withTuningGuard([&] { synth_->setProperty("tuning_root", std::to_string(note).c_str()); }); }

	// Run fn (which mutates the live tuning) while the audio callback is parked.
	// Signals the callback to park, waits for it to acknowledge (or ~250ms if the
	// stream isn't running), applies the change, then un-parks.
	template <class Fn>
	void withTuningGuard(Fn &&fn) {
		if (!synth_) return;
		applyingTuning_.store(true, std::memory_order_release);
		for (int i = 0; i < 250 && !audioParked_.load(std::memory_order_acquire); ++i)
			usleep(1000);
		fn();
		applyingTuning_.store(false, std::memory_order_release);
	}

	// Reading a parameter races the audio thread by a single float; harmless and
	// only used to seed the UI.
	float getParameter(int index) {
		if (synth_ && index >= 0 && index < kAmsynthParameterCount)
			return synth_->getParameterValue((Param) index);
		return 0.f;
	}

	// --- audio thread ------------------------------------------------------
	oboe::DataCallbackResult onAudioReady(oboe::AudioStream *, void *audioData,
	                                      int32_t numFrames) override {
		// While the UI thread is applying a tuning change (which rewrites the
		// TuningMap vectors the note path reads), park: output silence and don't
		// touch the synth, so process() never reads the tuning mid-mutation.
		if (applyingTuning_.load(std::memory_order_acquire)) {
			audioParked_.store(true, std::memory_order_release);
			float *silence = static_cast<float *>(audioData);
			for (int i = 0; i < numFrames * 2; ++i) silence[i] = 0.f;
			return oboe::DataCallbackResult::Continue;
		}
		audioParked_.store(false, std::memory_order_release);

		// Apply queued control changes in sync with rendering.
		params_.drainTo(*synth_);

		midiStore_.clear();
		midi_.drain([this](const MidiMsg &m) { midiStore_.push_back(m); });

		midiEvents_.clear();
		for (auto &m : midiStore_) {
			amsynth_midi_event_t e;
			e.offset_frames = 0;
			e.length = m.len;
			e.buffer = m.bytes; // stable: midiStore_ is reserved, no realloc
			midiEvents_.push_back(e);
		}

		midiOut_.clear();
		float *out = static_cast<float *>(audioData);
		synth_->process((unsigned) numFrames, midiEvents_, midiOut_,
		                out, out + 1, /*stride=*/2);
		return oboe::DataCallbackResult::Continue;
	}

private:
	Synthesizer *synth_ {nullptr};
	std::shared_ptr<oboe::AudioStream> stream_;
	ParameterQueue params_;
	MidiRing midi_;
	std::vector<MidiMsg> midiStore_;
	std::vector<amsynth_midi_event_t> midiEvents_;
	std::vector<amsynth_midi_cc_t> midiOut_;
	// Tuning-change handshake between the UI thread and the audio callback.
	std::atomic<bool> applyingTuning_ {false};
	std::atomic<bool> audioParked_ {false};
};

std::unique_ptr<AmsynthAudio> g_engine;

// The loaded preset bank. Owned and touched only by the UI (JNI) thread — the
// audio thread never reads it. Selecting a preset copies its parameters into the
// lock-free queue, so this stays independent of the engine's lifecycle and can
// be loaded before audio starts.
std::unique_ptr<PresetController> g_bank;

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_amsynth_enhanced_AmsynthEngine_nativeCreate(JNIEnv *, jobject, jint sampleRate) {
	if (g_engine) return;
	g_engine = std::make_unique<AmsynthAudio>();
	if (!g_engine->start(sampleRate))
		g_engine.reset();
}

JNIEXPORT void JNICALL
Java_com_amsynth_enhanced_AmsynthEngine_nativeDestroy(JNIEnv *, jobject) {
	if (g_engine) { g_engine->stop(); g_engine.reset(); }
}

JNIEXPORT void JNICALL
Java_com_amsynth_enhanced_AmsynthEngine_nativeNoteOn(JNIEnv *, jobject, jint note, jint vel) {
	if (g_engine) g_engine->noteOn(note, vel);
}

JNIEXPORT void JNICALL
Java_com_amsynth_enhanced_AmsynthEngine_nativeNoteOff(JNIEnv *, jobject, jint note) {
	if (g_engine) g_engine->noteOff(note);
}

JNIEXPORT void JNICALL
Java_com_amsynth_enhanced_AmsynthEngine_nativeAllNotesOff(JNIEnv *, jobject) {
	if (g_engine) g_engine->allNotesOff();
}

JNIEXPORT void JNICALL
Java_com_amsynth_enhanced_AmsynthEngine_nativeSetParameter(JNIEnv *, jobject, jint index, jfloat value) {
	if (g_engine) g_engine->setParameter(index, value);
}

JNIEXPORT jfloat JNICALL
Java_com_amsynth_enhanced_AmsynthEngine_nativeGetParameterValue(JNIEnv *, jobject, jint index) {
	return g_engine ? g_engine->getParameter(index) : 0.f;
}

// Parameter metadata is static (no engine instance required), so the UI can
// build its controls before audio starts.
JNIEXPORT jint JNICALL
Java_com_amsynth_enhanced_AmsynthEngine_nativeGetParameterCount(JNIEnv *, jobject) {
	return kAmsynthParameterCount;
}

JNIEXPORT jstring JNICALL
Java_com_amsynth_enhanced_AmsynthEngine_nativeGetParameterName(JNIEnv *env, jobject, jint index) {
	const char *name = parameter_name_from_index(index);
	return env->NewStringUTF(name ? name : "");
}

static float paramProp(int index, int which) {
	double mn = 0, mx = 0, def = 0, step = 0;
	get_parameter_properties(index, &mn, &mx, &def, &step);
	switch (which) { case 0: return (float) mn; case 1: return (float) mx; case 3: return (float) step; default: return (float) def; }
}

JNIEXPORT jfloat JNICALL
Java_com_amsynth_enhanced_AmsynthEngine_nativeGetParameterMin(JNIEnv *, jobject, jint index) { return paramProp(index, 0); }

JNIEXPORT jfloat JNICALL
Java_com_amsynth_enhanced_AmsynthEngine_nativeGetParameterMax(JNIEnv *, jobject, jint index) { return paramProp(index, 1); }

JNIEXPORT jfloat JNICALL
Java_com_amsynth_enhanced_AmsynthEngine_nativeGetParameterDefault(JNIEnv *, jobject, jint index) { return paramProp(index, 2); }

// Snap size for discrete parameters (0 for continuous ones).
JNIEXPORT jfloat JNICALL
Java_com_amsynth_enhanced_AmsynthEngine_nativeGetParameterStep(JNIEnv *, jobject, jint index) { return paramProp(index, 3); }

// Human-readable value, exactly as the desktop GUI shows it (e.g. "Saw",
// "24 dB/oct", "1.50 Hz"). Used for the label under each knob.
JNIEXPORT jstring JNICALL
Java_com_amsynth_enhanced_AmsynthEngine_nativeGetParameterDisplay(JNIEnv *env, jobject, jint index, jfloat value) {
	char buf[64] = {0};
	parameter_get_display(index, value, buf, sizeof(buf));
	return env->NewStringUTF(buf);
}

// Law-aware value<->[0,1] mapping (matches the desktop skin's knob frame math).
// A temporary Parameter carries the per-parameter law/step, so the skin picks
// exactly the same film-strip frame the desktop GUI would.
JNIEXPORT jfloat JNICALL
Java_com_amsynth_enhanced_AmsynthEngine_nativeNormalize(JNIEnv *, jobject, jint index, jfloat value) {
	if (index < 0 || index >= kAmsynthParameterCount) return 0.f;
	Parameter p((Param) index);
	p.setValue(value);
	return p.getNormalisedValue();
}

JNIEXPORT jfloat JNICALL
Java_com_amsynth_enhanced_AmsynthEngine_nativeDenormalize(JNIEnv *, jobject, jint index, jfloat norm) {
	if (index < 0 || index >= kAmsynthParameterCount) return 0.f;
	Parameter p((Param) index);
	p.setNormalisedValue(norm);
	return p.getValue();
}

// --- presets / banks -------------------------------------------------------

// Parse a bank file (bytes) into g_bank. Returns the number of non-empty
// presets, or -1 on a parse error.
JNIEXPORT jint JNICALL
Java_com_amsynth_enhanced_AmsynthEngine_nativeLoadBank(JNIEnv *env, jobject, jbyteArray data) {
	const jsize len = env->GetArrayLength(data);
	std::string s((size_t) len, '\0');
	if (len > 0)
		env->GetByteArrayRegion(data, 0, len, reinterpret_cast<jbyte *>(&s[0]));
	if (!g_bank)
		g_bank = std::make_unique<PresetController>();
	if (g_bank->loadPresetsFromString(s) != 0)
		return -1;
	int n = 0;
	for (int i = 0; i < PresetController::kNumPresets; i++)
		if (!g_bank->getPreset(i).getName().empty())
			n++;
	return n;
}

// Total slots in a bank (fixed at 128). The UI filters out empty-named slots.
JNIEXPORT jint JNICALL
Java_com_amsynth_enhanced_AmsynthEngine_nativeGetPresetCount(JNIEnv *, jobject) {
	return PresetController::kNumPresets;
}

JNIEXPORT jstring JNICALL
Java_com_amsynth_enhanced_AmsynthEngine_nativeGetPresetName(JNIEnv *env, jobject, jint i) {
	if (!g_bank || i < 0 || i >= PresetController::kNumPresets)
		return env->NewStringUTF("");
	return env->NewStringUTF(g_bank->getPreset(i).getName().c_str());
}

// The parameter values of bank preset i — used to seed the UI sliders. Reads
// only g_bank, so it works before the audio engine exists.
JNIEXPORT jfloatArray JNICALL
Java_com_amsynth_enhanced_AmsynthEngine_nativeGetPresetValues(JNIEnv *env, jobject, jint i) {
	jfloatArray arr = env->NewFloatArray(kAmsynthParameterCount);
	if (!g_bank || i < 0 || i >= PresetController::kNumPresets)
		return arr;
	float vals[kAmsynthParameterCount];
	const Preset &p = g_bank->getPreset(i);
	for (int j = 0; j < kAmsynthParameterCount; j++)
		vals[j] = p.getParameter(j).getValue();
	env->SetFloatArrayRegion(arr, 0, kAmsynthParameterCount, vals);
	return arr;
}

// Make bank preset i the live sound (applied on the audio thread).
JNIEXPORT void JNICALL
Java_com_amsynth_enhanced_AmsynthEngine_nativeSelectPreset(JNIEnv *, jobject, jint i) {
	if (g_engine && g_bank && i >= 0 && i < PresetController::kNumPresets)
		g_engine->applyPreset(g_bank->getPreset(i));
}

// Enumerate a bank's preset names WITHOUT touching the live bank (g_bank) — used
// to build the cross-bank searchable library. Parses into a throwaway
// PresetController and returns all 128 slot names (empty string for empty slots).
JNIEXPORT jobjectArray JNICALL
Java_com_amsynth_enhanced_AmsynthEngine_nativeListBankPresets(JNIEnv *env, jobject, jbyteArray data) {
	const jsize len = env->GetArrayLength(data);
	std::string s((size_t) len, '\0');
	if (len > 0)
		env->GetByteArrayRegion(data, 0, len, reinterpret_cast<jbyte *>(&s[0]));
	jclass strCls = env->FindClass("java/lang/String");
	jstring empty = env->NewStringUTF("");
	jobjectArray arr = env->NewObjectArray(PresetController::kNumPresets, strCls, empty);
	PresetController pc;
	if (pc.loadPresetsFromString(s) == 0) {
		for (int i = 0; i < PresetController::kNumPresets; i++) {
			jstring name = env->NewStringUTF(pc.getPreset(i).getName().c_str());
			env->SetObjectArrayElement(arr, i, name);
			env->DeleteLocalRef(name);
		}
	}
	return arr;
}

// --- save / load a sound ---------------------------------------------------

JNIEXPORT jstring JNICALL
Java_com_amsynth_enhanced_AmsynthEngine_nativeGetState(JNIEnv *env, jobject) {
	return env->NewStringUTF(g_engine ? g_engine->getState().c_str() : "");
}

// Apply a saved sound (preset-state string). Returns the applied parameter
// values for the UI, or null if the string could not be parsed.
JNIEXPORT jfloatArray JNICALL
Java_com_amsynth_enhanced_AmsynthEngine_nativeApplyState(JNIEnv *env, jobject, jstring s) {
	const char *cs = env->GetStringUTFChars(s, nullptr);
	std::string str(cs ? cs : "");
	if (cs)
		env->ReleaseStringUTFChars(s, cs);
	Preset tmp;
	if (!tmp.fromString(str))
		return nullptr;
	float vals[kAmsynthParameterCount];
	for (int j = 0; j < kAmsynthParameterCount; j++)
		vals[j] = tmp.getParameter(j).getValue();
	if (g_engine)
		g_engine->applyPreset(tmp);
	jfloatArray arr = env->NewFloatArray(kAmsynthParameterCount);
	env->SetFloatArrayRegion(arr, 0, kAmsynthParameterCount, vals);
	return arr;
}

// --- microtuning (Scala) ---------------------------------------------------

JNIEXPORT jboolean JNICALL
Java_com_amsynth_enhanced_AmsynthEngine_nativeLoadScale(JNIEnv *env, jobject, jstring s) {
	const char *cs = env->GetStringUTFChars(s, nullptr);
	std::string str(cs ? cs : "");
	if (cs) env->ReleaseStringUTFChars(s, cs);
	return (jboolean) (g_engine && g_engine->loadScale(str));
}

JNIEXPORT jboolean JNICALL
Java_com_amsynth_enhanced_AmsynthEngine_nativeLoadKeymap(JNIEnv *env, jobject, jstring s) {
	const char *cs = env->GetStringUTFChars(s, nullptr);
	std::string str(cs ? cs : "");
	if (cs) env->ReleaseStringUTFChars(s, cs);
	return (jboolean) (g_engine && g_engine->loadKeymap(str));
}

JNIEXPORT void JNICALL
Java_com_amsynth_enhanced_AmsynthEngine_nativeResetTuning(JNIEnv *, jobject) {
	if (g_engine) g_engine->resetTuning();
}

JNIEXPORT void JNICALL
Java_com_amsynth_enhanced_AmsynthEngine_nativeSetTuningRoot(JNIEnv *, jobject, jint note) {
	if (g_engine) g_engine->setTuningRoot(note);
}

} // extern "C"
