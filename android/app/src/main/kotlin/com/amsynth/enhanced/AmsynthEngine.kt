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

    data class ParamInfo(
        val index: Int,
        val name: String,
        val min: Float,
        val max: Float,
        val default: Float,
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
            )
        }
}
