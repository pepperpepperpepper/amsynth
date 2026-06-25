/*
 *  Synthesizer.cpp
 *
 *  Copyright (c) 2014 Nick Dowell
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

#include "Synthesizer.h"

#include "MidiController.h"
#include "PresetController.h"
#include "VoiceAllocationUnit.h"
#include "VoiceBoard.h"

#include <algorithm>
#include <cassert>
#include <cmath>
#include <cstdio>
#include <cstring>

#ifdef WITH_MTS_ESP
#include "MTS-ESP/Client/libMTSClient.h"
#endif

class Synthesizer::PresetObserver final : public PresetController::Observer {
public:
	explicit PresetObserver(Synthesizer *synthesizer)
	: synthesizer_(synthesizer) {}

	void currentPresetDidChange() final {
		auto &preset = synthesizer_->_presetController->getCurrentPreset();

		std::string value;
		if (preset.getProperty(PROP_NAME(tuning_scl_file), &value))
			synthesizer_->setProperty(PROP_NAME(tuning_scl_file), value.c_str());
		if (preset.getProperty(PROP_NAME(tuning_kbm_file), &value))
			synthesizer_->setProperty(PROP_NAME(tuning_kbm_file), value.c_str());
		// Split/root after the scale & keymap so setRoot re-anchors the loaded scale.
		if (preset.getProperty(PROP_NAME(tuning_split), &value))
			synthesizer_->setProperty(PROP_NAME(tuning_split), value.c_str());
		if (preset.getProperty(PROP_NAME(tuning_split_point), &value))
			synthesizer_->setProperty(PROP_NAME(tuning_split_point), value.c_str());
		if (preset.getProperty(PROP_NAME(tuning_root), &value))
			synthesizer_->setProperty(PROP_NAME(tuning_root), value.c_str());
	}

private:
	Synthesizer *synthesizer_;
};


Synthesizer::Synthesizer()
: _sampleRate(-1)
, _midiController(nullptr)
, _presetController(nullptr)
, _voiceAllocationUnit(nullptr)
{
	_voiceAllocationUnit = new VoiceAllocationUnit;
	_voiceAllocationUnit->SetSampleRate((int) _sampleRate);

	_presetController = new PresetController;
	_presetController->getCurrentPreset().addObserver(_voiceAllocationUnit);
	presetObserver_ = new PresetObserver(this);
	_presetController->addObserver(presetObserver_);
	_presetController->notify(); // apply per-preset properties for initial selection
	for (const auto &bank : PresetController::getPresetBanks()) {
		if (bank.file_path == _presetController->getFilePath()) {
			propertyStore_[PROP_NAME(preset_bank_name)] = bank.name;
			break;
		}
	}
	propertyStore_[PROP_NAME(preset_name)] = _presetController->getCurrentPreset().getName();
	propertyStore_[PROP_NAME(preset_number)] = std::to_string(_presetController->getCurrPresetNumber());

	_midiController = new MidiController();
	_midiController->SetMidiEventHandler(_voiceAllocationUnit);
	_midiController->setPresetController(*_presetController);
}

Synthesizer::~Synthesizer()
{
	delete _midiController;
	_presetController->removeObserver(presetObserver_);
	delete presetObserver_;
	delete _presetController;
	delete _voiceAllocationUnit;
}

void Synthesizer::setProperty(const char *name, const char *value)
{
	if (value && strlen(value))
		propertyStore_[name] = value;
	else
		propertyStore_.erase(name);

	if (name == std::string(PROP_NAME(max_polyphony)))
		setMaxNumVoices(std::stoi(value));

	if (name == std::string(PROP_NAME(midi_channel)))
		setMidiChannel(std::stoi(value));

	if (name == std::string(PROP_NAME(pitch_bend_range)))
		setPitchBendRangeSemitones(std::stoi(value));

	if (name == std::string(PROP_NAME(tuning_kbm_file))) {
		auto result = loadTuningKeymap(value);
		if (result == 0)
			_presetController->getCurrentPreset().setProperty(PROP_NAME(tuning_kbm_file), value ? value : "");
	}

	if (name == std::string(PROP_NAME(tuning_scl_file))) {
		auto result = loadTuningScale(value);
		if (result == 0)
			_presetController->getCurrentPreset().setProperty(PROP_NAME(tuning_scl_file), value ? value : "");
	}

	if (name == std::string(PROP_NAME(tuning_split))) {
		bool enabled = value && strlen(value) && std::stoi(value) != 0;
		_voiceAllocationUnit->setTonicSplitEnabled(enabled);
		_presetController->getCurrentPreset().setProperty(PROP_NAME(tuning_split), enabled ? "1" : "0");
	}

	if (name == std::string(PROP_NAME(tuning_split_point)) && value && strlen(value)) {
		_voiceAllocationUnit->setTonicSplitPoint(std::stoi(value));
		_presetController->getCurrentPreset().setProperty(PROP_NAME(tuning_split_point), value);
	}

	if (name == std::string(PROP_NAME(tuning_root)) && value && strlen(value)) {
		_voiceAllocationUnit->setTonicRoot(std::stoi(value));
		_presetController->getCurrentPreset().setProperty(PROP_NAME(tuning_root), value);
	}
	
#ifdef WITH_MTS_ESP
	if (name == std::string(PROP_NAME(tuning_mts_esp_disabled)))
		_voiceAllocationUnit->mtsEspDisabled = std::stoi(value);
#endif
}

std::map<std::string, std::string> Synthesizer::getProperties()
{
	auto props = propertyStore_;
	props.erase(PROP_NAME(tuning_kbm_file));
	props.erase(PROP_NAME(tuning_scl_file));
	props.erase(PROP_NAME(tuning_split));
	props.erase(PROP_NAME(tuning_split_point));
	props.erase(PROP_NAME(tuning_root));
	props[PROP_NAME(max_polyphony)] = std::to_string(getMaxNumVoices());
	props[PROP_NAME(midi_channel)] = std::to_string(getMidiChannel());
	props[PROP_NAME(pitch_bend_range)] = std::to_string(getPitchBendRangeSemitones());
	if (!_voiceAllocationUnit->tuningMap.getKeyMapFile().empty())
		props[PROP_NAME(tuning_kbm_file)] = _voiceAllocationUnit->tuningMap.getKeyMapFile();
	if (!_voiceAllocationUnit->tuningMap.getScaleFile().empty())
		props[PROP_NAME(tuning_scl_file)] = _voiceAllocationUnit->tuningMap.getScaleFile();
#ifdef WITH_MTS_ESP
	props[PROP_NAME(tuning_mts_esp_disabled)] = _voiceAllocationUnit->mtsEspDisabled ? "1" : "0";
#endif
	return props;
}

void Synthesizer::loadBank(const char *filename)
{
	_presetController->loadPresets(filename);
	_presetController->selectPreset(_presetController->getCurrPresetNumber());
}

void Synthesizer::saveBank(const char *filename)
{
	_presetController->commitPreset();
	_presetController->savePresets(filename);
}

void Synthesizer::setState(const std::string &buffer)
{
	if (!_presetController->getCurrentPreset().fromString(buffer))
		return;

	std::istringstream input (buffer);
	for (std::string line; std::getline(input, line); ) {
		std::istringstream stream (line);
		std::string type, key, value;
		stream >> type;

		if (type == "<property>") {
			stream >> key;
			stream.get(); // skip whitespace
			std::getline(stream, value); // value may contain whitespace
			setProperty(key.c_str(), value.c_str());
		}
	}

	// Apply per-preset properties (e.g. tuning) after global properties.
	_presetController->notify();
}

std::string Synthesizer::getState()
{
	std::stringstream stream;
	_presetController->getCurrentPreset().toString(stream);

	for (auto &it : getProperties())
		stream << "<property> " << it.first << " " << it.second << std::endl;

	return stream.str();
}

int Synthesizer::getPresetNumber()
{
	return _presetController->getCurrPresetNumber();
}

void Synthesizer::setPresetNumber(int number)
{
	_presetController->selectPreset(number);
}

float Synthesizer::getParameterValue(Param parameter)
{
	return _presetController->getCurrentPreset().getParameter(parameter).getValue();
}

float Synthesizer::getNormalizedParameterValue(Param parameter)
{
	return _presetController->getCurrentPreset().getParameter(parameter).getNormalisedValue();
}

void Synthesizer::setParameterValue(Param parameter, float value)
{
	_presetController->getCurrentPreset().getParameter(parameter).setValue(value);
}

void Synthesizer::setNormalizedParameterValue(Param parameter, float value)
{
	_presetController->getCurrentPreset().getParameter(parameter).setNormalisedValue(value);
}

void Synthesizer::getParameterName(Param parameter, char *buffer, size_t maxLen)
{
	strncpy(buffer, _presetController->getCurrentPreset().getParameter(parameter).getName(), maxLen);
}

void Synthesizer::getParameterLabel(Param parameter, char *buffer, size_t maxLen)
{
	strncpy(buffer, _presetController->getCurrentPreset().getParameter(parameter).getLabel().c_str(), maxLen);
}

void Synthesizer::getParameterDisplay(Param parameter, char *buffer, size_t maxLen)
{
	strncpy(buffer, _presetController->getCurrentPreset().getParameter(parameter).getStringValue().c_str(), maxLen);
}

int Synthesizer::getPitchBendRangeSemitones()
{
	return _voiceAllocationUnit->getPitchBendRangeSemitones();
}

void Synthesizer::setPitchBendRangeSemitones(int value)
{
	_voiceAllocationUnit->setPitchBendRangeSemitones(value);
}

int Synthesizer::getMaxNumVoices()
{
	return _voiceAllocationUnit->GetMaxVoices();
}

void Synthesizer::setMaxNumVoices(int value)
{
	_voiceAllocationUnit->SetMaxVoices(value);
}

unsigned char Synthesizer::getMidiChannel()
{
	return _midiController->assignedChannel;
}

void Synthesizer::setMidiChannel(unsigned char channel)
{
	_midiController->assignedChannel = channel;
	if (channel != kMidiChannel_Any) {
		// A reset is required when switching to a new channel since we will
		// not receive the note off events for currently held notes.
		needsResetAllVoices_ = true;
	}
}

void Synthesizer::setHzModeEnabled(bool enabled)
{
	if (hzModeEnabled_ == enabled)
		return;

	hzModeEnabled_ = enabled;
	hzGateInput_ = false;
	hzGateActive_ = false;
	hzAppliedFrequency_ = 0.0f;

	needsResetAllVoices_ = true;
	if (_midiController) {
		_midiController->setIgnoreNoteEvents(enabled);
		_midiController->setIgnorePitchWheelEvents(enabled);
	}
}

void Synthesizer::setHzInput(float frequencyHz, float gate, float velocity)
{
	hzFrequency_ = frequencyHz;
	hzGateInput_ = gate > 0.5f;
	hzVelocity_ = std::max(0.0f, std::min(velocity, 1.0f));
}

int Synthesizer::loadTuningKeymap(const char *filename)
{
	if (filename && strlen(filename))
		return _voiceAllocationUnit->loadKeyMap(filename);

	_voiceAllocationUnit->tuningMap.defaultKeyMap();
	return 0;
}

int Synthesizer::loadTuningKeymapFromString(const char *keyMapData)
{
	if (keyMapData && strlen(keyMapData))
		return _voiceAllocationUnit->loadKeyMapFromString(keyMapData);

	_voiceAllocationUnit->tuningMap.defaultKeyMap();
	return 0;
}

int Synthesizer::loadTuningScale(const char *filename)
{
	if (filename && strlen(filename))
		return _voiceAllocationUnit->loadScale(filename);

	_voiceAllocationUnit->tuningMap.defaultScale();
	return 0;
}

int Synthesizer::loadTuningScaleFromString(const char *scaleData)
{
	if (scaleData && strlen(scaleData))
		return _voiceAllocationUnit->loadScaleFromString(scaleData);

	_voiceAllocationUnit->tuningMap.defaultScale();
	return 0;
}

void Synthesizer::loadControllerMapFromString(const char *data)
{
	_midiController->loadControllerMapFromString(data ? data : "");
}

std::string Synthesizer::getControllerMapString()
{
	return _midiController->getControllerMapString();
}

void Synthesizer::setSampleRate(int sampleRate)
{
	_sampleRate = sampleRate;
	_voiceAllocationUnit->SetSampleRate(sampleRate);
}

void Synthesizer::process(unsigned int nframes,
						  const std::vector<amsynth_midi_event_t> &midi_in,
						  std::vector<amsynth_midi_cc_t> &midi_out,
						  float *audio_l, float *audio_r, unsigned audio_stride)
{
	if (_sampleRate < 0) {
		assert(nullptr == "sample rate has not been set");
		return;
	}

	if (needsResetAllVoices_) {
		needsResetAllVoices_ = false;
		_voiceAllocationUnit->resetAllVoices();
	}

	if (hzModeEnabled_) {
		const bool gate = hzGateInput_ && (hzFrequency_ > 0.0f);
		const float frequencyHz = hzFrequency_;
		if (gate && !hzGateActive_) {
			_voiceAllocationUnit->HandleHzNoteOn(frequencyHz, hzVelocity_);
			hzAppliedFrequency_ = frequencyHz;
		} else if (!gate && hzGateActive_) {
			_voiceAllocationUnit->HandleHzNoteOff();
		} else if (gate) {
			if (std::fabs(frequencyHz - hzAppliedFrequency_) > 1e-3f) {
				_voiceAllocationUnit->HandleHzPitch(frequencyHz);
				hzAppliedFrequency_ = frequencyHz;
			}
		}
		hzGateActive_ = gate;
	}

	std::vector<amsynth_midi_event_t>::const_iterator event = midi_in.begin();
	unsigned frames_left_in_buffer = nframes, frame_index = 0;
	while (frames_left_in_buffer) {
		while (event != midi_in.end() && event->offset_frames <= frame_index) {
			_midiController->HandleMidiData(event->buffer, event->length);
#ifdef WITH_MTS_ESP
			MTS_ParseMIDIDataU(_voiceAllocationUnit->mtsClient, event->buffer, event->length);
#endif
			++event;
		}
		
		unsigned block_size_frames = std::min(frames_left_in_buffer, (unsigned)VoiceBoard::kMaxProcessBufferSize);
		if (event != midi_in.end() && event->offset_frames > frame_index) {
			unsigned frames_until_next_event = event->offset_frames - frame_index;
			block_size_frames = std::min(block_size_frames, frames_until_next_event);
		}
		
		_voiceAllocationUnit->Process(audio_l + (frame_index * audio_stride),
									  audio_r + (frame_index * audio_stride),
									  block_size_frames, audio_stride);
		
		frame_index += block_size_frames;
		frames_left_in_buffer -= block_size_frames;
	}

	while (event != midi_in.end()) {
		_midiController->HandleMidiData(event->buffer, event->length);
#ifdef WITH_MTS_ESP
		MTS_ParseMIDIDataU(_voiceAllocationUnit->mtsClient, event->buffer, event->length);
#endif
		++event;
	}

	_midiController->generateMidiOutput(midi_out);
}
