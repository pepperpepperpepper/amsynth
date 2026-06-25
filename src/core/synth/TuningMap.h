/*
 *  TuningMap.h
 *
 *  Copyright (c) 2001 Nick Dowell
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

#ifndef _TUNINGMAP_H
#define _TUNINGMAP_H

#include <iosfwd>
#include <string>
#include <vector>

class TuningMap
{
/*
 * A TuningMap consists of two parts.
 * The "key map" maps from MIDI note numbers to logical note numbers for the scale.
 * (This is often the identity mapping, but if your scale has, for example,
 * 11 notes in it, you'll want to skip one per octave so the scale lines up
 * with the pattern of keys on a standard keyboard.)
 * The "scale" maps from those logical note numbers to actual pitches.
 * In terms of member variables, "scale" and "scaleDesc" belong to the scale,
 * and everything else belongs to the mapping.
 * For more information, refer to http://www.huygens-fokker.org/scala/
 */
public:
		TuningMap		();
		// Default is 12-equal, standard mapping

	int	loadScale		(const std::string & filename);
	int	loadScaleFromString	(const std::string & scaleData);
	int	loadKeyMap		(const std::string & filename);
	int	loadKeyMapFromString	(const std::string & keyMapData);
	// All return 0 on success

	void	defaultScale		();
	void	defaultKeyMap		();

	const std::string & getScaleFile() const { return scaleFile; }
	const std::string & getKeyMapFile() const { return keyMapFile; }

	bool	isDefault		() const { return scaleFile.empty() && keyMapFile.empty(); }

	double	noteToPitch		(int note) const;

	void	setRoot		(int midiNote);
	// Movable key center: re-anchor the current scale so its 1/1 lands on
	// midiNote's pitch class, with that note at its 12-TET (A4=440) frequency.
	// Does not reload the scale. -1 from getRoot() means "not re-rooted".
	int		getRoot		() const { return rootNote; }

	bool	inActiveRange   (int note) const { return activeRange[note]; }

private:
	int	loadScaleStream		(std::istream & stream, const std::string & name);
	int	loadKeyMapStream	(std::istream & stream, const std::string & name);

	std::string scaleFile;
	std::string keyMapFile;

	std::vector<double>	scale;
	// note that logical indices to this begin with 1

	int			zeroNote;
	int			refNote;
	double			refPitch;
	int			mapRepeatInc;
	int			rootNote;

	bool		activeRange[128];
	std::vector<int>	mapping; // -1 for unmapped

	double			basePitch;
	void			updateBasePitch		();
	void			activateRange(int min, int max); // Activates the given ranges inclusively.
};

#endif

