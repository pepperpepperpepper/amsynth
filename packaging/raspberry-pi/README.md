# Raspberry Pi packaging (headless)

Builds a lean **headless** arm64 `.deb` of amsynth for Raspberry Pi OS
(Bookworm). The headless build (`--without-gui`) has no JUCE/X11/freetype
dependency — only ALSA/JACK + liblo (OSC) — so it runs on **Raspberry Pi OS
Lite** and is a small, durable sound-engine driven over MIDI/OSC.

## Build

```sh
packaging/raspberry-pi/build-deb.sh
# -> packaging/raspberry-pi/out/amsynth_<version>_arm64.deb
```

Runs the build in a Debian arm64 container. On an x86 host this uses qemu
emulation (slow but works); on an arm64 host (including a Pi itself) it builds
natively. Requires `docker`; if arm64 images won't execute on an x86 host the
script registers qemu automatically (needs one privileged `docker run`).

## Install on the Pi

```sh
sudo apt install ./amsynth_<version>_arm64.deb   # pulls in libasound2, libjack, liblo
amsynth --help
```

Drive it over ALSA MIDI, JACK, or OSC (the Hz/OSC control). With no display it
runs as a background sound engine.

## Notes / scope

- This is **headless only** by design. The full GUI build is a separate target
  (the regular `./configure` defaults), and would need Raspberry Pi OS Desktop
  (X11) plus the JUCE submodule.
- Targets **arm64 (aarch64)** — Pi 3/4/5 on 64-bit Raspberry Pi OS. 32-bit
  armhf would need an armhf base image instead.
- For cross-distro / cross-release durability you could later wrap the same
  headless binary in an AppImage or Flatpak; a `.deb` is the native first step.
