#!/usr/bin/env bash
#
# Build a lean *headless* arm64 (aarch64) .deb of amsynth for Raspberry Pi OS
# (Bookworm), inside a Debian arm64 container. Works on an x86 host via qemu
# emulation, or natively on an arm64 host.
#
# The headless build (--without-gui) has no JUCE/X11/freetype dependency — just
# ALSA/JACK + liblo (OSC) — so it runs on Raspberry Pi OS Lite and is a small,
# durable sound-engine package driven over MIDI/OSC.
#
# Usage:   packaging/raspberry-pi/build-deb.sh
# Output:  packaging/raspberry-pi/out/amsynth_<version>_arm64.deb
#
# Requirements: docker. On an x86 host you also need arm64 qemu emulation; if
# arm64 images won't execute, register it once (host-wide, reversible):
#   docker run --privileged --rm tonistiigi/binfmt --install arm64
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(git -C "$here" rev-parse --show-toplevel)"
out="$here/out"
mkdir -p "$out"
rm -f "$out"/*.deb

echo ">> Exporting clean source (git archive HEAD; no submodules needed for headless)…"
git -C "$repo" archive --format=tar --prefix=amsynth/ HEAD >"$out/amsynth-src.tar"

# Make sure arm64 can run under emulation (no-op on an arm64 host).
if ! docker run --rm --platform linux/arm64 debian:bookworm-slim true 2>/dev/null; then
	echo ">> Registering qemu arm64 emulation…"
	docker run --privileged --rm tonistiigi/binfmt --install arm64
fi

echo ">> Building arm64 .deb in Debian bookworm (this is slow under emulation)…"
docker run --rm --platform linux/arm64 -v "$out:/out" debian:bookworm-slim \
	bash -euo pipefail -c '
		export DEBIAN_FRONTEND=noninteractive
		apt-get update -qq
		apt-get install -y --no-install-recommends \
			build-essential debhelper dh-autoreconf autoconf automake libtool \
			autopoint intltool gettext pkg-config \
			libasound2-dev libjack-jackd2-dev liblo-dev dssi-dev dpkg-dev
		cd /tmp && tar xf /out/amsynth-src.tar && cd amsynth
		# Headless: no GUI (-> no JUCE/X), no MTS-ESP (non-GPL, needs submodule).
		# -d skips the GUI build-deps we deliberately did not install.
		EXTRA_CONFIG_FLAGS="--without-gui --without-mts-esp" \
			dpkg-buildpackage -b -us -uc -d
		cp ../amsynth_*_arm64.deb /out/
	'

rm -f "$out/amsynth-src.tar"
echo ">> Done:"
ls -la "$out"/*.deb
