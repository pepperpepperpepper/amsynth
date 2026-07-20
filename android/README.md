# amsynth — Android app (NDK + Oboe)

**Install:** available from the Oops WTF F-Droid repo — add
`https://fdroid.uh-oh.wtf/repo` in an F-Droid client, or grab the APK directly:
<https://fdroid.uh-oh.wtf/repo/com.amsynth.enhanced_50200.apk>.

A native Android front-end for amsynth. The same platform-free DSP engine in
`src/core/synth` that powers the desktop, plugin, and web builds is compiled with
the **NDK** and driven by **[Oboe](https://github.com/google/oboe)** for
low-latency audio. The UI is **Jetpack Compose**, styled after the desktop
panel — rotary knobs grouped into oscillator/mixer/LFO/filter/envelope/effects/
master sections, a bank + preset browser (all factory banks bundled), a
sweepable multi-touch keyboard with octave shift, and save/load of sounds.

Like the web preview, only the audio engine is shared; there is no JUCE here.

## Architecture

```
MainActivity.kt ──▶ AmsynthEngine.kt ──JNI──▶ amsynth_jni.cpp
  (Compose UI)        (native decls)            │   Synthesizer (src/core/synth)
                                                │   Oboe AudioStream (LowLatency)
                                                ▼
   UI thread ──push──▶ ParameterQueue / MidiRing ──drain──▶ onAudioReady()
   (lock-free, no locks on the audio thread)              synth.process(... stride 2)
```

- **`app/src/main/cpp/amsynth_jni.cpp`** — owns one `Synthesizer` and the Oboe
  output stream. The Oboe data callback renders directly into the interleaved
  float buffer (`process(..., out, out+1, stride=2)`).
- **Thread safety** mirrors the desktop OSC path. The UI thread never touches the
  `Synthesizer`; parameter changes flow through the shared lock-free
  `ParameterQueue` (`src/standalone/ParameterQueue.h`, reused verbatim) and MIDI
  note/CC events through a small lock-free ring. Both are drained at the top of
  `onAudioReady`, so nothing races `process()`.
- **`app/src/main/cpp/CMakeLists.txt`** — compiles the exact engine source set the
  web build uses, with no `-DPKGDATADIR` (the on-disk banks/skins layer stays
  inert and the engine starts from its in-memory default preset).

## Requirements

- Android Studio (Koala/2024.1+) **or** a command-line SDK with:
  - NDK r26+ (`ndk;26.3.11579264`)
  - CMake 3.22.1 (`cmake;3.22.1`)
  - `platforms;android-34`, `build-tools;34.0.0`
- JDK 17 (AGP 8.5 requirement)

## Build

```sh
cd android
cp local.properties.sample local.properties   # then edit sdk.dir

# First time only: generate the Gradle wrapper (or just open in Android Studio).
gradle wrapper --gradle-version 8.7

./gradlew assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug           # build + install on a connected device
```

Oboe is pulled from Maven Central (`com.google.oboe:oboe:1.9.3`) and exposed to
CMake through its prefab package (`find_package(oboe REQUIRED CONFIG)` +
`android.buildFeatures.prefab = true`), so there is no Oboe source to vendor.

### Building on the `moviecomp` host

The Android SDK/NDK and the (self-hosted) F-Droid repo tooling live on the
`moviecomp` host, not the dev machine. `packaging/android/build-on-moviecomp.sh`
clones/fetches the repo there, generates the gradle wrapper if needed, runs
`assembleRelease`, copies the APK back into `./dist`, and (optionally) publishes
it into a local F-Droid repo:

```sh
# build the current branch on moviecomp, fetch the APK into ./dist
packaging/android/build-on-moviecomp.sh

# build a tag and publish into a local F-Droid repo on the host
FDROID_DIR=~/fdroid packaging/android/build-on-moviecomp.sh v1.14.0
```

All targets (`MOVIECOMP_HOST`, `MOVIECOMP_PORT`, `MOVIECOMP_USER`, `REMOTE_DIR`,
`FDROID_DIR`, ...) are overridable via environment variables — see the script
header.

## F-Droid metadata

`android/fastlane/metadata/android/en-US/` holds the F-Droid listing (title,
short/full description, per-version changelogs) that `fdroid update` reads.

## Status / next steps

Implemented:
- Engine create/destroy tied to the activity foreground lifecycle.
- **Desktop-style knob panel**: every parameter is a rotary knob (drag to
  change; discrete params snap to their step; the value is formatted by the
  engine's `parameter_get_display`, so it reads "Saw", "24 dB/oct", … like the
  desktop). Knobs are grouped into the same sections as the desktop GUI.
- **All factory banks bundled** under `assets/banks/` (Factory, Brian's 01–22,
  Patrik's 01–05), with a bank picker and preset browser. Selecting a preset
  pushes its parameters through the `ParameterQueue`, so it applies on the audio
  thread with no UI/audio race.
- A **sweepable, multi-touch keyboard**: a single pointer surface hit-tests each
  touch, so sliding between keys re-triggers notes (legato glide) and several
  fingers sound at once. Octave up/down and panic (all-notes-off).
- **Save / load a sound** via the system file picker (SAF) — `getState` on the
  native side, and `applyState` parses a sound and applies it through the queue.
- Raw-MIDI plumbing (`midi()`/`nativeSetParameter`) ready to wire to Android
  MIDI (`android.media.midi`) the way the web build uses Web MIDI.

Not yet wired (parity with the web preview, in rough priority order):
- Scala `.scl` / `.kbm` tuning and the tonic-split controls.
- CC → parameter map loading.
- Hardware MIDI input via `android.media.midi`.
- The actual desktop skin bitmaps (this is a clean-drawn knob panel, not the
  amsynth skin PNGs).
