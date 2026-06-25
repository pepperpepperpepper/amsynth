/*
 *  synth_bridge.cpp
 *
 *  A small C ABI around the amsynth DSP engine (src/core/synth) so it can be
 *  compiled to WebAssembly and driven from a Web Audio AudioWorklet.
 *
 *  This is intentionally thin: it owns a single Synthesizer instance, queues
 *  MIDI note events between render calls, and renders into two scratch buffers
 *  that the worklet reads straight out of linear memory.
 *
 *  All of these functions run on the audio render thread (the worklet's
 *  message handler and process() callback share that one thread), so the
 *  event queue needs no locking.
 *
 *  This file is part of amsynth and is licensed under the GNU GPL v2+.
 */

#include "core/synth/Synthesizer.h"
#include "core/controls.h"
#include "core/midi.h"
#include "core/types.h"

#include <emscripten/emscripten.h>

#include <string>
#include <vector>

namespace {

Synthesizer *gSynth = nullptr;

// Raw MIDI bytes queued since the last render. Each entry owns its 3 status
// bytes so the pointers handed to process() stay valid for that call.
struct QueuedMidi { unsigned char bytes[3]; unsigned int length; };
std::vector<QueuedMidi> gQueue;

std::vector<amsynth_midi_event_t> gMidiIn;
std::vector<amsynth_midi_cc_t> gMidiOut;

// Output scratch owned by the module; far larger than any render quantum (128).
constexpr int kMaxFrames = 4096;
float gOutL[kMaxFrames];
float gOutR[kMaxFrames];

} // namespace

extern "C" {

EMSCRIPTEN_KEEPALIVE
void synth_init(int sampleRate)
{
	if (!gSynth)
		gSynth = new Synthesizer();
	gSynth->setSampleRate(sampleRate);
}

EMSCRIPTEN_KEEPALIVE
void synth_note_on(int note, int velocity)
{
	gQueue.push_back({{MIDI_STATUS_NOTE_ON,
	                   (unsigned char)(note & 0x7f),
	                   (unsigned char)(velocity & 0x7f)}, 3});
}

EMSCRIPTEN_KEEPALIVE
void synth_note_off(int note)
{
	gQueue.push_back({{MIDI_STATUS_NOTE_OFF, (unsigned char)(note & 0x7f), 0}, 3});
}

EMSCRIPTEN_KEEPALIVE
void synth_all_notes_off()
{
	gQueue.push_back({{MIDI_STATUS_CONTROLLER, MIDI_CC_ALL_NOTES_OFF, 0}, 3});
}

EMSCRIPTEN_KEEPALIVE
void synth_set_param(int index, float value)
{
	if (gSynth && index >= 0 && index < kAmsynthParameterCount)
		gSynth->setParameterValue((Param)index, value);
}

EMSCRIPTEN_KEEPALIVE
float synth_get_param(int index)
{
	if (gSynth && index >= 0 && index < kAmsynthParameterCount)
		return gSynth->getParameterValue((Param)index);
	return 0.f;
}

EMSCRIPTEN_KEEPALIVE int   synth_param_count()        { return kAmsynthParameterCount; }
EMSCRIPTEN_KEEPALIVE const char *synth_param_name(int i) { return parameter_name_from_index(i); }

EMSCRIPTEN_KEEPALIVE
float synth_param_min(int i)
{
	double mn = 0, mx = 0, def = 0, step = 0;
	get_parameter_properties(i, &mn, &mx, &def, &step);
	return (float)mn;
}

EMSCRIPTEN_KEEPALIVE
float synth_param_max(int i)
{
	double mn = 0, mx = 0, def = 0, step = 0;
	get_parameter_properties(i, &mn, &mx, &def, &step);
	return (float)mx;
}

EMSCRIPTEN_KEEPALIVE
float synth_param_default(int i)
{
	double mn = 0, mx = 0, def = 0, step = 0;
	get_parameter_properties(i, &mn, &mx, &def, &step);
	return (float)def;
}

EMSCRIPTEN_KEEPALIVE float *synth_out_left()  { return gOutL; }
EMSCRIPTEN_KEEPALIVE float *synth_out_right() { return gOutR; }

// Render nframes of stereo audio into the scratch buffers.
EMSCRIPTEN_KEEPALIVE
void synth_process(int nframes)
{
	if (!gSynth)
		return;
	if (nframes > kMaxFrames)
		nframes = kMaxFrames;

	gMidiIn.clear();
	for (auto &q : gQueue) {
		amsynth_midi_event_t e;
		e.offset_frames = 0;
		e.length = q.length;
		e.buffer = q.bytes; // valid until gQueue.clear() below
		gMidiIn.push_back(e);
	}

	gMidiOut.clear();
	gSynth->process((unsigned)nframes, gMidiIn, gMidiOut, gOutL, gOutR, 1);

	gQueue.clear();
}

// --- Tonic-split demo controls (the feature wired up earlier) ---------------

EMSCRIPTEN_KEEPALIVE
void synth_set_tonic_split(int enabled)
{
	if (gSynth)
		gSynth->setProperty(PROP_NAME(tuning_split), enabled ? "1" : "0");
}

EMSCRIPTEN_KEEPALIVE
void synth_set_tonic_split_point(int note)
{
	if (gSynth)
		gSynth->setProperty(PROP_NAME(tuning_split_point), std::to_string(note).c_str());
}

EMSCRIPTEN_KEEPALIVE
void synth_set_tonic_root(int note)
{
	if (gSynth)
		gSynth->setProperty(PROP_NAME(tuning_root), std::to_string(note).c_str());
}

} // extern "C"
