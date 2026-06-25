/*
 *  tests.cpp
 *
 *  Copyright (c) 2016-2023 Nick Dowell
 *
 *  This file is part of amsynth.
 *
 *  amsynth is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  amsynth is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with amsynth.  If not, see <http://www.gnu.org/licenses/>.
 */

#include "core/controls.h"
#include "core/midi.h"
#include "core/synth/LowPassFilter.h"
#include "core/synth/MidiController.h"
#include "core/synth/Oscillator.h"
#include "core/synth/Synthesizer.h"
#include "core/synth/VoiceAllocationUnit.h"
#include "core/synth/VoiceBoard.h"

#include <cassert>
#include <cmath>
#include <cstdio>
#include <fstream>
#include <iostream>

#ifndef _WIN32
#include <unistd.h>
#endif

#define TEST(name) static void name()

static std::string writeTempFile(const char *suffix, const std::string &contents)
{
#ifdef _WIN32
    char buffer[L_tmpnam];
    assert(std::tmpnam(buffer));
    auto path = std::string(buffer) + (suffix ? suffix : "");
#else
    char pattern[] = "/tmp/amsynth-tests-XXXXXX";
    const int fd = mkstemp(pattern);
    assert(fd != -1);
    close(fd);

    auto path = std::string(pattern);
    if (suffix && *suffix) {
        const auto pathWithSuffix = path + suffix;
        assert(std::rename(path.c_str(), pathWithSuffix.c_str()) == 0);
        path = pathWithSuffix;
    }
#endif

    std::ofstream file(path, std::ios::out | std::ios::trunc);
    assert(file.is_open());
    file << contents;
    file.close();

    return path;
}

TEST(testMidiOutput) {
    static float audioBuffer[64];

    Synthesizer *synth = new Synthesizer();
    synth->setSampleRate(44100);
    
    std::vector<amsynth_midi_event_t> midiIn;
    std::vector<amsynth_midi_cc_t> midiOut;
    
    synth->process(32, midiIn, midiOut, &audioBuffer[0], &audioBuffer[32]);
    
    Param param = kAmsynthParameter_ReverbWet;

    unsigned char cc = 2;
    synth->getMidiController()->setControllerForParameter(param, cc);
    
    for (unsigned char value = 0; value <= 127; value++) {
        unsigned char midi[4] = { MIDI_STATUS_CONTROLLER, cc, value };
        amsynth_midi_event_t event;
        event.length = 3;
        event.offset_frames = 0;
        event.buffer = midi;
        midiIn.clear();
        midiIn.push_back(event);
        
        midiOut.clear();
        synth->process(32, midiIn, midiOut, &audioBuffer[0], &audioBuffer[32]);

        int outputValue = (int)roundf(synth->getNormalizedParameterValue(param) * 127.0f);
        assert(outputValue == value || 0 == "parameter value should be changed when a cc is processed");

        assert(midiOut.empty() || 0 == "no midi output should be generated when a cc is processed");
    }
    
    midiIn.clear();
    midiOut.clear();
    synth->setNormalizedParameterValue(param, 0);
    synth->process(32, midiIn, midiOut, &audioBuffer[0], &audioBuffer[32]);
    assert(midiOut.size() == 1 || 0 == "midi output should be generated when a parameter is changed");
    assert(midiOut[0].value == 0 || 0 == "midi output value is incorrect");

    delete synth;
}

TEST(testMidiOutput_OnOff) {
    Synthesizer *synth = new Synthesizer();
    MidiController *midiController = synth->getMidiController();
    midiController->setControllerForParameter(kAmsynthParameter_Oscillator2Sync, 2);
    synth->setNormalizedParameterValue(kAmsynthParameter_Oscillator2Sync, 0);
    std::vector<amsynth_midi_cc_t> midiOut;
    midiController->generateMidiOutput(midiOut);
    midiOut.clear();

    unsigned char data[3] = { MIDI_STATUS_CONTROLLER, 2, 0 };

    data[2] = 10; // off ~> 0 (not emitted)
    midiController->HandleMidiData(data, 3);
    midiController->generateMidiOutput(midiOut);
    assert(midiOut.empty());

    data[2] = 100; // on ~> 127 (not emitted)
    midiController->HandleMidiData(data, 3);
    midiController->generateMidiOutput(midiOut);
    assert(midiOut.empty());

    data[2] = 42; // off ~> 0 (not emitted)
    midiController->HandleMidiData(data, 3);
    midiController->generateMidiOutput(midiOut);
    assert(midiOut.empty());

    synth->setNormalizedParameterValue(kAmsynthParameter_Oscillator2Sync, 1);
    midiController->generateMidiOutput(midiOut);
    assert(midiOut[0].value == 127);

    delete synth;
}

static int countActiveVoices(Synthesizer *synth) {
    int count = 0;
    for (int i = 0; i < 128; i++) {
        count = count + (synth->_voiceAllocationUnit->active[i] ? 1 : 0);
    }
    return count;
}

TEST(testMidiAllNotesOff) {
    static float audioBuffer[64];

    Synthesizer *synth = new Synthesizer();
    synth->setSampleRate(44100);
    synth->setParameterValue(kAmsynthParameter_KeyboardMode, KeyboardModePoly);

    std::vector<amsynth_midi_event_t> midiIn;
    std::vector<amsynth_midi_cc_t> midiOut;

    synth->process(32, midiIn, midiOut, &audioBuffer[0], &audioBuffer[32]);
    assert(countActiveVoices(synth) == 0);

    /* */ {
        unsigned char midi[4] = { MIDI_STATUS_NOTE_ON, 64, 100 };
        amsynth_midi_event_t e = { 0, 3, midi };
        midiIn.clear();
        midiIn.push_back(e);
        synth->process(32, midiIn, midiOut, &audioBuffer[0], &audioBuffer[32]);
        assert(countActiveVoices(synth) == 1);
    }

    /* */ {
        unsigned char midi[4] = { MIDI_STATUS_NOTE_ON, 80, 100 };
        amsynth_midi_event_t e = { 0, 3, midi };
        midiIn.clear();
        midiIn.push_back(e);
        synth->process(32, midiIn, midiOut, &audioBuffer[0], &audioBuffer[32]);
        assert(countActiveVoices(synth) == 2);
    }

    /* */ {
        unsigned char midi[4] = { MIDI_STATUS_CONTROLLER, MIDI_CC_ALL_NOTES_OFF, 0 };
        amsynth_midi_event_t e = { 0, 3, midi };
        midiIn.clear();
        midiIn.push_back(e);
        synth->process(32, midiIn, midiOut, &audioBuffer[0], &audioBuffer[32]);
        assert(countActiveVoices(synth) == 0);
    }

    delete synth;
}

TEST(testPresetIgnoredParameters) {
    Preset basePreset;
    basePreset.getParameter(0).setValue(1);
    Preset newPreset = basePreset;
    newPreset.getParameter(0).setValue(0);
    assert(!basePreset.isEqual(newPreset));
    Preset::setLockedParameterNames("amp_attack amp_decay");
    assert(basePreset.isEqual(newPreset));
    Preset::setLockedParameterNames("");
    assert(!basePreset.isEqual(newPreset));
}

static size_t count(const char **strings) {
    size_t count;
    for (count = 0; strings[count]; count ++);
    return count;
}

TEST(testPresetValueStrings) {
    assert(count(parameter_get_value_strings(kAmsynthParameter_Oscillator1Waveform)) == (int)Oscillator::Waveform::kRandom + 1);
    assert(count(parameter_get_value_strings(kAmsynthParameter_Oscillator2Waveform)) == (int)Oscillator::Waveform::kRandom + 1);
    assert(count(parameter_get_value_strings(kAmsynthParameter_KeyboardMode)) == KeyboardModeLegato + 1);
    assert(count(parameter_get_value_strings(kAmsynthParameter_FilterType)) == (int)SynthFilter::Type::kBypass + 1);
    assert(count(parameter_get_value_strings(kAmsynthParameter_FilterSlope)) == (int)SynthFilter::Slope::k12 + 2);
    assert(count(parameter_get_value_strings(kAmsynthParameter_LFOOscillatorSelect)) == 3);
    assert(count(parameter_get_value_strings(kAmsynthParameter_PortamentoMode)) == PortamentoModeLegato + 1);
}

TEST(testOscillatorHighFrequency) {
    static float buffer[VoiceBoard::kMaxProcessBufferSize];
    
    Oscillator osc;
    osc.SetSampleRate(44100);
    for (int waveform = (int)Oscillator::Waveform::kSine; waveform <= (int)Oscillator::Waveform::kRandom; waveform++) {
        osc.SetWaveform((Oscillator::Waveform)waveform);
        osc.ProcessSamples(buffer, VoiceBoard::kMaxProcessBufferSize, 99999, 0.5f);
    }
}

TEST(testPresetTuningPropertiesRoundTrip) {
    Preset preset("Tuning Test");
    preset.setProperty(PROP_NAME(tuning_scl_file), "/path/to/test.scl");
    preset.setProperty(PROP_NAME(tuning_kbm_file), "");

    Preset restored;
    assert(restored.fromString(preset.toString()));

    std::string value;
    assert(restored.getProperty(PROP_NAME(tuning_scl_file), &value));
    assert(value == "/path/to/test.scl");
    assert(restored.getProperty(PROP_NAME(tuning_kbm_file), &value));
    assert(value.empty());
}

TEST(testBankTuningPropertiesRoundTrip) {
    PresetController presetController;
    for (int i = 0; i < PresetController::kNumPresets; i++) {
        presetController.getPreset(i).setName("unused");
    }

    auto &preset = presetController.getPreset(0);
    preset.setName("Bank Test");
    preset.setProperty(PROP_NAME(tuning_scl_file), "/path/to/bank.scl");
    preset.setProperty(PROP_NAME(tuning_kbm_file), "/path/to/bank.kbm");

    auto bankPath = writeTempFile(".bank", "");
    assert(presetController.savePresets(bankPath.c_str()) == 0);

    PresetController restoredController;
    assert(restoredController.loadPresets(bankPath.c_str()) == 0);

    auto &restored = restoredController.getPreset(0);
    std::string value;
    assert(restored.getName() == "Bank Test");
    assert(restored.getProperty(PROP_NAME(tuning_scl_file), &value));
    assert(value == "/path/to/bank.scl");
    assert(restored.getProperty(PROP_NAME(tuning_kbm_file), &value));
    assert(value == "/path/to/bank.kbm");

    std::remove(bankPath.c_str());
}

TEST(testTuningAppliedOnPresetRecall) {
    auto sclPath = writeTempFile(".scl",
                                 "! test.scl\n"
                                 "test\n"
                                 "1\n"
                                 "2/1\n");
    auto kbmPath = writeTempFile(".kbm",
                                 "! test.kbm\n"
                                 "0\n"
                                 "0\n"
                                 "127\n"
                                 "0\n"
                                 "69\n"
                                 "440.0\n"
                                 "0\n");

    Synthesizer synth;
    auto *presetController = synth.getPresetController();

    presetController->getPreset(0).setProperty(PROP_NAME(tuning_scl_file), sclPath);
    presetController->getPreset(0).setProperty(PROP_NAME(tuning_kbm_file), kbmPath);
    presetController->getPreset(1).clearProperty(PROP_NAME(tuning_scl_file));
    presetController->getPreset(1).clearProperty(PROP_NAME(tuning_kbm_file));

    presetController->selectPreset(0);
    assert(synth._voiceAllocationUnit->tuningMap.getScaleFile() == sclPath);
    assert(synth._voiceAllocationUnit->tuningMap.getKeyMapFile() == kbmPath);

    presetController->selectPreset(1);
    // No tuning override: should not change current tuning.
    assert(synth._voiceAllocationUnit->tuningMap.getScaleFile() == sclPath);
    assert(synth._voiceAllocationUnit->tuningMap.getKeyMapFile() == kbmPath);

    presetController->getPreset(1).setProperty(PROP_NAME(tuning_scl_file), "");
    presetController->getPreset(1).setProperty(PROP_NAME(tuning_kbm_file), "");
    presetController->selectPreset(1);
    // Explicit reset: should reset tuning to default.
    assert(synth._voiceAllocationUnit->tuningMap.getScaleFile().empty());
    assert(synth._voiceAllocationUnit->tuningMap.getKeyMapFile().empty());

    std::remove(sclPath.c_str());
    std::remove(kbmPath.c_str());
}

TEST(testTuningEditsCapturedInPreset) {
    auto sclPath = writeTempFile(".scl",
                                 "! test.scl\n"
                                 "test\n"
                                 "1\n"
                                 "2/1\n");

    Synthesizer synth;
    auto *presetController = synth.getPresetController();

    presetController->getPreset(0).clearProperty(PROP_NAME(tuning_scl_file));
    presetController->selectPreset(0);

    synth.setProperty(PROP_NAME(tuning_scl_file), sclPath.c_str());

    std::string value;
    assert(presetController->getCurrentPreset().getProperty(PROP_NAME(tuning_scl_file), &value));
    assert(value == sclPath);
    assert(presetController->isCurrentPresetModified());

    presetController->commitPreset();
    assert(!presetController->isCurrentPresetModified());
    assert(presetController->getPreset(0).getProperty(PROP_NAME(tuning_scl_file), &value));
    assert(value == sclPath);

    std::remove(sclPath.c_str());
}

TEST(testHzModeIgnoresMidiNotes) {
    static float audioBuffer[64];

    Synthesizer synth;
    synth.setSampleRate(44100);
    synth.setHzModeEnabled(true);

    std::vector<amsynth_midi_event_t> midiIn;
    std::vector<amsynth_midi_cc_t> midiOut;

    unsigned char midi[4] = { MIDI_STATUS_NOTE_ON, 64, 100 };
    amsynth_midi_event_t e = { 0, 3, midi };
    midiIn.push_back(e);

    synth.process(32, midiIn, midiOut, &audioBuffer[0], &audioBuffer[32]);
    assert(countActiveVoices(&synth) == 0);
}

TEST(testHzInputGateAndPitch) {
    static float audioBuffer[64];

    Synthesizer synth;
    synth.setSampleRate(44100);
    synth.setHzModeEnabled(true);

    std::vector<amsynth_midi_event_t> midiIn;
    std::vector<amsynth_midi_cc_t> midiOut;

    synth.setHzInput(440.0f, 1.0f, 1.0f);
    synth.process(32, midiIn, midiOut, &audioBuffer[0], &audioBuffer[32]);
    assert(synth._voiceAllocationUnit->active[0]);
    assert(std::fabs(synth._voiceAllocationUnit->_voices[0]->getFrequency() - 440.0f) < 1e-3f);

    synth.setHzInput(880.0f, 1.0f, 1.0f);
    synth.process(32, midiIn, midiOut, &audioBuffer[0], &audioBuffer[32]);
    assert(std::fabs(synth._voiceAllocationUnit->_voices[0]->getFrequency() - 880.0f) < 1e-3f);

    synth.setHzInput(880.0f, 0.0f, 1.0f);
    synth.process(32, midiIn, midiOut, &audioBuffer[0], &audioBuffer[32]);
    assert(!synth._voiceAllocationUnit->keyPressed[0]);
}

TEST(testTuningMapSetRoot) {
    auto sclPath = writeTempFile(".scl",
                                 "! ji.scl\n"
                                 "JI test\n"
                                 "3\n"
                                 "9/8\n"
                                 "5/4\n"
                                 "2/1\n");
    TuningMap tm;
    assert(tm.loadScale(sclPath) == 0);

    tm.setRoot(60); // C4 becomes the 1/1, at its 12-TET pitch
    const double c4 = 440.0 * std::pow(2.0, (60 - 69) / 12.0);
    assert(std::fabs(tm.noteToPitch(60) - c4) < 1e-6);
    assert(std::fabs(tm.noteToPitch(61) / tm.noteToPitch(60) - 9.0 / 8.0) < 1e-9); // degree 1
    assert(std::fabs(tm.noteToPitch(63) / tm.noteToPitch(60) - 2.0) < 1e-9);       // octave

    tm.setRoot(67); // G4 — movable key center keeps G at its 12-TET pitch
    const double g4 = 440.0 * std::pow(2.0, (67 - 69) / 12.0);
    assert(std::fabs(tm.noteToPitch(67) - g4) < 1e-6);
    assert(tm.getRoot() == 67);

    std::remove(sclPath.c_str());
}

TEST(testLoadScaleFromString) {
    const char *scl =
        "! ji.scl\n"
        "JI test\n"
        "3\n"
        "9/8\n"
        "5/4\n"
        "2/1\n";

    // Parsing from a string matches parsing from a file.
    TuningMap tm;
    assert(tm.loadScaleFromString(scl) == 0);
    tm.setRoot(60);
    assert(std::fabs(tm.noteToPitch(61) / tm.noteToPitch(60) - 9.0 / 8.0) < 1e-9);
    assert(std::fabs(tm.noteToPitch(63) / tm.noteToPitch(60) - 2.0) < 1e-9);

    // Through the Synthesizer entry point used by the wasm bridge.
    Synthesizer synth;
    synth.setSampleRate(44100);
    assert(synth.loadTuningScaleFromString(scl) == 0);
    assert(synth._voiceAllocationUnit->tuningMap.getScaleFile() == "<memory>");

    // An empty string resets to the default 12-TET scale.
    assert(synth.loadTuningScaleFromString("") == 0);
    assert(synth._voiceAllocationUnit->tuningMap.isDefault());

    // Malformed data is rejected and leaves the previous scale intact.
    assert(tm.loadScaleFromString("not a scale") != 0);
}

TEST(testLoadKeyMapFromString) {
    const char *kbm =
        "! linear.kbm\n"
        "0\n"     // map size 0 -> automatic linear mapping
        "0\n"     // first note
        "127\n"   // last note
        "60\n"    // middle (zero) note
        "69\n"    // reference note
        "432.0\n" // reference frequency
        "1\n";    // formal octave degree

    // Parsing from a string retunes the reference pitch (A4 -> 432 Hz).
    TuningMap tm;
    assert(tm.loadKeyMapFromString(kbm) == 0);
    assert(std::fabs(tm.noteToPitch(69) - 432.0) < 1e-6);

    // Through the Synthesizer entry point used by the wasm bridge.
    Synthesizer synth;
    synth.setSampleRate(44100);
    assert(synth.loadTuningKeymapFromString(kbm) == 0);
    assert(std::fabs(synth._voiceAllocationUnit->noteToPitch(69) - 432.0) < 1e-6);

    // An empty string resets to the default keymap (A4 = 440 Hz).
    assert(synth.loadTuningKeymapFromString("") == 0);
    assert(std::fabs(synth._voiceAllocationUnit->noteToPitch(69) - 440.0) < 1e-6);

    // Malformed data is rejected and leaves the previous keymap intact.
    assert(tm.loadKeyMapFromString("not a keymap") != 0);
}

TEST(testLoadControllerMapFromString) {
    Synthesizer synth;
    synth.setSampleRate(44100);

    std::vector<amsynth_midi_cc_t> midiOut;
    float buf[64];
    auto sendCC = [&](int cc, int val) {
        unsigned char b[3] = {0xB0, (unsigned char)cc, (unsigned char)val};
        amsynth_midi_event_t e {0, 3, b};
        std::vector<amsynth_midi_event_t> in {e};
        synth.process(32, in, midiOut, &buf[0], &buf[32]);
    };
    auto cutoff = [&]() { return synth.getParameterValue(kAmsynthParameter_FilterCutoff); };

    // Default map: CC74 (Sound Controller 5) sweeps the filter cutoff.
    sendCC(74, 0);   float d0 = cutoff();
    sendCC(74, 127); float d1 = cutoff();
    assert(d1 > d0);

    // Custom map (one parameter name per line, line N = CC N): move the cutoff
    // onto CC1 (mod wheel) and clear everything else.
    std::string map;
    for (int cc = 0; cc < 128; cc++)
        map += (cc == 1 ? "filter_cutoff\n" : "null\n");
    synth.loadControllerMapFromString(map.c_str());

    sendCC(1, 0);    float lo = cutoff();
    sendCC(1, 127);  float hi = cutoff();
    assert(hi > lo);                 // CC1 now drives the cutoff

    sendCC(1, 64);   float mid = cutoff();
    sendCC(74, 0);   assert(cutoff() == mid); // old default CC74 is now inert

    // The exported map reflects the forward mapping...
    std::string dump = synth.getControllerMapString();
    assert(dump.substr(0, dump.find('\n') + 1) == "null\n");        // CC0
    // ...and an empty string restores the built-in defaults.
    synth.loadControllerMapFromString("");
    sendCC(74, 0);   float r0 = cutoff();
    sendCC(74, 127); float r1 = cutoff();
    assert(r1 > r0);                 // CC74 sweeps the cutoff again
}

TEST(testTonicSplitOverlay) {
    auto sclPath = writeTempFile(".scl",
                                 "! ji.scl\n"
                                 "JI test\n"
                                 "3\n"
                                 "9/8\n"
                                 "5/4\n"
                                 "2/1\n");
    Synthesizer synth;
    synth.setSampleRate(44100);
    assert(synth.loadTuningScale(sclPath.c_str()) == 0);

    auto *vau = synth._voiceAllocationUnit;
    vau->setTonicSplitEnabled(true);
    vau->setTonicSplitPoint(33); // bottom octave (notes < 33) is the control zone

    // Control-zone key: re-roots the scale and makes no sound.
    vau->HandleMidiNoteOn(24, 1.0f); // C1
    assert(countActiveVoices(&synth) == 0);
    assert(vau->tuningMap.getRoot() == 24);
    const double cRoot = 440.0 * std::pow(2.0, (24 - 69) / 12.0);
    assert(std::fabs(vau->tuningMap.noteToPitch(24) - cRoot) < 1e-6);

    // Play-zone key: sounds, using the re-rooted tuning.
    vau->HandleMidiNoteOn(60, 1.0f); // C4
    assert(countActiveVoices(&synth) == 1);

    // Control-zone note-off is a no-op (no stuck voice/state).
    vau->HandleMidiNoteOff(24, 0.0f);
    assert(countActiveVoices(&synth) == 1);

    std::remove(sclPath.c_str());
}

TEST(testTuningSplitPersistedPerPreset) {
    Preset preset("Split Test");
    preset.setProperty(PROP_NAME(tuning_split), "1");
    preset.setProperty(PROP_NAME(tuning_split_point), "36");
    preset.setProperty(PROP_NAME(tuning_root), "62");

    // Round-trip through the preset string serialization.
    Preset restored;
    assert(restored.fromString(preset.toString()));
    std::string v;
    assert(restored.getProperty(PROP_NAME(tuning_split), &v) && v == "1");
    assert(restored.getProperty(PROP_NAME(tuning_split_point), &v) && v == "36");
    assert(restored.getProperty(PROP_NAME(tuning_root), &v) && v == "62");

    // Round-trip through a bank file.
    PresetController pc;
    for (int i = 0; i < PresetController::kNumPresets; i++)
        pc.getPreset(i).setName("unused");
    pc.getPreset(0) = preset;
    auto bankPath = writeTempFile(".bank", "");
    assert(pc.savePresets(bankPath.c_str()) == 0);

    PresetController pc2;
    assert(pc2.loadPresets(bankPath.c_str()) == 0);
    assert(pc2.getPreset(0).getProperty(PROP_NAME(tuning_split), &v) && v == "1");
    assert(pc2.getPreset(0).getProperty(PROP_NAME(tuning_split_point), &v) && v == "36");
    assert(pc2.getPreset(0).getProperty(PROP_NAME(tuning_root), &v) && v == "62");

    std::remove(bankPath.c_str());
}

TEST(testTuningSplitAppliedOnPresetRecall) {
    Synthesizer synth;
    auto *pc = synth.getPresetController();
    auto *vau = synth._voiceAllocationUnit;

    pc->getPreset(1).setProperty(PROP_NAME(tuning_split), "1");
    pc->getPreset(1).setProperty(PROP_NAME(tuning_split_point), "36");
    pc->getPreset(1).setProperty(PROP_NAME(tuning_root), "62");

    pc->selectPreset(1);
    assert(vau->getTonicSplitEnabled());
    assert(vau->getTonicSplitPoint() == 36);
    assert(vau->tuningMap.getRoot() == 62);

    // Editing via the property API re-roots and captures back onto the preset.
    synth.setProperty(PROP_NAME(tuning_root), "64");
    assert(vau->tuningMap.getRoot() == 64);
    std::string v;
    assert(pc->getCurrentPreset().getProperty(PROP_NAME(tuning_root), &v) && v == "64");
}

#define RUN_TEST(testFunction) do { printf("%s()... ", #testFunction); testFunction(); printf("OK\n"); } while (0)

int main(int argc, const char * argv[])  {
    RUN_TEST(testMidiOutput);
    RUN_TEST(testMidiOutput_OnOff);
    RUN_TEST(testPresetIgnoredParameters);
    RUN_TEST(testPresetValueStrings);
    RUN_TEST(testMidiAllNotesOff);
    RUN_TEST(testOscillatorHighFrequency);
    RUN_TEST(testPresetTuningPropertiesRoundTrip);
    RUN_TEST(testBankTuningPropertiesRoundTrip);
    RUN_TEST(testTuningAppliedOnPresetRecall);
    RUN_TEST(testTuningEditsCapturedInPreset);
    RUN_TEST(testHzModeIgnoresMidiNotes);
    RUN_TEST(testHzInputGateAndPitch);
    RUN_TEST(testTuningMapSetRoot);
    RUN_TEST(testLoadScaleFromString);
    RUN_TEST(testLoadKeyMapFromString);
    RUN_TEST(testLoadControllerMapFromString);
    RUN_TEST(testTonicSplitOverlay);
    RUN_TEST(testTuningSplitPersistedPerPreset);
    RUN_TEST(testTuningSplitAppliedOnPresetRecall);
    return 0;
}
