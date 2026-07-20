package com.amsynth.enhanced

/**
 * Thin Kotlin facade over the native amsynth engine (libamsynth.so).
 *
 * The native side owns the Synthesizer and the Oboe output stream; everything
 * here is a `external` declaration into [amsynth_jni.cpp]. Control calls are
 * lock-free on the native side, so they are safe to issue from the UI thread.
 */
object AmsynthEngine {
    init { System.loadLibrary("amsynth") }

    /** Create the engine and start the audio stream. Pass 0 to let Oboe pick the rate. */
    external fun nativeCreate(sampleRate: Int)
    /** Stop the stream and destroy the engine. */
    external fun nativeDestroy()

    external fun nativeNoteOn(note: Int, velocity: Int)
    external fun nativeNoteOff(note: Int)
    external fun nativeAllNotesOff()

    external fun nativeSetParameter(index: Int, value: Float)
    external fun nativeGetParameterValue(index: Int): Float

    external fun nativeGetParameterCount(): Int
    external fun nativeGetParameterName(index: Int): String
    external fun nativeGetParameterMin(index: Int): Float
    external fun nativeGetParameterMax(index: Int): Float
    external fun nativeGetParameterDefault(index: Int): Float
    external fun nativeGetParameterStep(index: Int): Float
    external fun nativeGetParameterDisplay(index: Int, value: Float): String

    // Law-aware value<->[0,1] mapping (for the skin's knob film-strip frames).
    external fun nativeNormalize(index: Int, value: Float): Float
    external fun nativeDenormalize(index: Int, norm: Float): Float

    fun normalize(index: Int, value: Float): Float = nativeNormalize(index, value)
    fun denormalize(index: Int, norm: Float): Float = nativeDenormalize(index, norm)

    // Preset bank (loaded from a bundled asset; independent of the audio stream).
    external fun nativeLoadBank(data: ByteArray): Int
    external fun nativeGetPresetCount(): Int
    external fun nativeGetPresetName(index: Int): String
    external fun nativeGetPresetValues(index: Int): FloatArray
    external fun nativeSelectPreset(index: Int)
    /** Names of every slot in a bank buffer (empty string for empty slots); does not change the live bank. */
    external fun nativeListBankPresets(data: ByteArray): Array<String>

    // Save / load a single sound (preset-state string).
    external fun nativeGetState(): String
    external fun nativeApplyState(state: String): FloatArray?

    // Microtuning: load a Scala scale (.scl) / keyboard map (.kbm) string, or reset to 12-TET.
    external fun nativeLoadScale(scl: String): Boolean
    external fun nativeLoadKeymap(kbm: String): Boolean
    external fun nativeResetTuning()
    /** Set the MIDI note that becomes the scale's 1/1 (the tuning root / tonic). */
    external fun nativeSetTuningRoot(note: Int)

    data class ParamInfo(
        val index: Int,
        val name: String,
        val min: Float,
        val max: Float,
        val default: Float,
        val step: Float,
    )

    /** Static parameter table (available before the stream starts). */
    fun parameters(): List<ParamInfo> =
        (0 until nativeGetParameterCount()).map { i ->
            ParamInfo(
                index = i,
                name = nativeGetParameterName(i),
                min = nativeGetParameterMin(i),
                max = nativeGetParameterMax(i),
                default = nativeGetParameterDefault(i),
                step = nativeGetParameterStep(i),
            )
        }

    /** Formatted value as the desktop GUI shows it (e.g. "Saw", "24 dB/oct"). */
    fun display(index: Int, value: Float): String = nativeGetParameterDisplay(index, value)

    /** A named slot in the loaded bank. [index] is the raw bank slot (0..127). */
    data class Preset(val index: Int, val name: String)

    /** Non-empty presets in the loaded bank, in bank order. */
    fun presets(): List<Preset> =
        (0 until nativeGetPresetCount())
            .map { Preset(it, nativeGetPresetName(it)) }
            .filter { it.name.isNotBlank() }
}
