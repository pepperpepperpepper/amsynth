/*
 *  ParameterQueue.h
 *
 *  Lock-free single-producer / single-consumer queue of parameter changes,
 *  used to carry live OSC parameter updates from the OSC server thread to the
 *  audio thread. The OSC thread push()es; the audio thread drainTo()s, applying
 *  the changes to the Synthesizer in sync with rendering. This keeps all
 *  Synthesizer parameter mutation on the audio thread (the same discipline the
 *  Hz control uses), so the OSC thread never races process().
 *
 *  This file is part of amsynth and is licensed under the GNU GPL v2+.
 */

#ifndef _AMSYNTH_PARAMETER_QUEUE_H
#define _AMSYNTH_PARAMETER_QUEUE_H

#include "core/controls.h"
#include "core/synth/Synthesizer.h"

#include <atomic>

class ParameterQueue
{
public:
	// Producer (OSC) thread. Returns false if the index is out of range or the
	// queue is full — in which case the change is dropped rather than blocking
	// the audio thread.
	bool push(int index, float value, bool normalized)
	{
		if (index < 0 || index >= kAmsynthParameterCount)
			return false;
		const unsigned tail = tail_.load(std::memory_order_relaxed);
		const unsigned next = (tail + 1) & kMask;
		if (next == head_.load(std::memory_order_acquire))
			return false; // full
		buffer_[tail] = { index, value, normalized };
		tail_.store(next, std::memory_order_release);
		return true;
	}

	// Consumer (audio) thread. Applies all pending changes, in order.
	void drainTo(Synthesizer &synth)
	{
		unsigned head = head_.load(std::memory_order_relaxed);
		const unsigned tail = tail_.load(std::memory_order_acquire);
		while (head != tail) {
			const Change &c = buffer_[head];
			if (c.normalized)
				synth.setNormalizedParameterValue((Param) c.index, c.value);
			else
				synth.setParameterValue((Param) c.index, c.value);
			head = (head + 1) & kMask;
		}
		head_.store(head, std::memory_order_release);
	}

private:
	struct Change { int index; float value; bool normalized; };
	static const unsigned kSize = 256; // must be a power of two
	static const unsigned kMask = kSize - 1;

	Change buffer_[kSize];
	std::atomic<unsigned> head_ {0}; // consumer (audio) cursor
	std::atomic<unsigned> tail_ {0}; // producer (OSC) cursor
};

#endif
