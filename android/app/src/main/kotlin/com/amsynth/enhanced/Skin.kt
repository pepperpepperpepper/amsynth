package com.amsynth.enhanced

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Renders amsynth's native "default" skin: the same background.png and
 * film-strip knob/popup bitmaps the desktop GUI uses, placed at the exact
 * layout.ini coordinates over the 600x400 canvas. Knob frames are chosen from
 * the parameter's law-aware normalized value, identical to the desktop
 * (frame = int((frames-1) * normalized)). Generated from data/skins/default.
 */
object Skin {
    data class Res(val file: String, val w: Int, val h: Int, val frames: Int)
    data class Ctl(val param: Int, val type: String, val res: String, val x: Int, val y: Int)

    const val BG_W = 600
    const val BG_H = 400

    val resources: Map<String, Res> = mapOf(
        "button_simple" to Res("button_simple.png", 21, 21, 2),
        "filter_slope_res" to Res("filter_slope.png", 45, 15, 2),
        "filter_type_res" to Res("filter_type.png", 65, 15, 5),
        "keybmode" to Res("keybmode.png", 55, 15, 3),
        "knob_boost" to Res("knob_boost.png", 49, 49, 49),
        "knob_boost_cut" to Res("knob_boost_cut.png", 49, 49, 49),
        "knob_mix" to Res("knob_mix.png", 49, 49, 49),
        "knob_osc_octave" to Res("knob_osc_octave.png", 49, 49, 8),
        "knob_osc_pitch" to Res("knob_osc_pitch.png", 49, 49, 25),
        "knob_spot" to Res("knob_spot.png", 49, 49, 49),
        "knob_width" to Res("knob_width.png", 49, 49, 49),
        "osc_select" to Res("osc_select.png", 55, 15, 3),
        "portamento_modes" to Res("portamento_modes.png", 55, 15, 2),
        "waveform_lfo" to Res("waveform_lfo.png", 49, 49, 7),
        "waveform_osc" to Res("waveform_pics.png", 49, 49, 5),
    )

    val controls: List<Ctl> = listOf(
        Ctl(4, "popup", "waveform_osc", 20, 20),        // osc1_waveform
        Ctl(23, "knob", "knob_spot", 80, 20),           // osc1_pulsewidth
        Ctl(13, "popup", "waveform_osc", 20, 110),      // osc2_waveform
        Ctl(24, "knob", "knob_spot", 80, 110),          // osc2_pulsewidth
        Ctl(17, "knob", "knob_osc_octave", 20, 170),    // osc2_range
        Ctl(33, "knob", "knob_osc_pitch", 80, 170),     // osc2_pitch
        Ctl(12, "knob", "knob_boost_cut", 140, 170),    // osc2_detune
        Ctl(30, "button", "button_simple", 41, 89),     // osc2_sync
        Ctl(18, "knob", "knob_mix", 170, 20),           // osc_mix
        Ctl(22, "knob", "knob_boost", 170, 80),         // osc_mix_mode
        Ctl(0, "knob", "knob_boost", 260, 20),          // amp_attack
        Ctl(1, "knob", "knob_boost", 320, 20),          // amp_decay
        Ctl(2, "knob", "knob_boost", 380, 20),          // amp_sustain
        Ctl(3, "knob", "knob_boost", 440, 20),          // amp_release
        Ctl(34, "popup", "filter_type_res", 288, 92),   // filter_type
        Ctl(35, "popup", "filter_slope_res", 355, 92),  // filter_slope
        Ctl(9, "knob", "knob_boost", 260, 110),         // filter_resonance
        Ctl(11, "knob", "knob_spot", 320, 110),         // filter_cutoff
        Ctl(37, "knob", "knob_width", 380, 110),        // filter_kbd_track
        Ctl(10, "knob", "knob_boost_cut", 440, 110),    // filter_env_amount
        Ctl(5, "knob", "knob_boost", 260, 170),         // filter_attack
        Ctl(6, "knob", "knob_boost", 320, 170),         // filter_decay
        Ctl(7, "knob", "knob_boost", 380, 170),         // filter_sustain
        Ctl(8, "knob", "knob_boost", 440, 170),         // filter_release
        Ctl(14, "knob", "knob_boost", 530, 20),         // master_vol
        Ctl(29, "knob", "knob_boost", 530, 80),         // distortion_crunch
        Ctl(16, "popup", "waveform_lfo", 20, 260),      // lfo_waveform
        Ctl(15, "knob", "knob_spot", 80, 260),          // lfo_freq
        Ctl(19, "knob", "knob_boost", 140, 260),        // freq_mod_amount
        Ctl(36, "popup", "osc_select", 137, 302),       // freq_mod_osc
        Ctl(20, "knob", "knob_boost", 200, 260),        // filter_mod_amount
        Ctl(21, "knob", "knob_boost", 260, 260),        // amp_mod_amount
        Ctl(27, "knob", "knob_boost", 350, 260),        // reverb_wet
        Ctl(25, "knob", "knob_width", 410, 260),        // reverb_roomsize
        Ctl(28, "knob", "knob_width", 470, 260),        // reverb_width
        Ctl(26, "knob", "knob_boost", 530, 260),        // reverb_damp
        Ctl(31, "knob", "knob_boost", 20, 340),         // portamento_time
        Ctl(40, "popup", "portamento_modes", 111, 356), // portamento_mode
        Ctl(32, "popup", "keybmode", 205, 356),         // keyboard_mode
        Ctl(38, "knob", "knob_spot", 470, 340),         // filter_vel_sens
        Ctl(39, "knob", "knob_spot", 530, 340),         // amp_vel_sens
    )

    val controlByParam: Map<Int, Ctl> = controls.associateBy { it.param }

    // Short labels for the pinned mini-patch knobs (the skin bakes labels into
    // the background, so standalone knobs need their own).
    val label: Map<Int, String> = mapOf(
        0 to "Amp Atk", 1 to "Amp Dec", 2 to "Amp Sus", 3 to "Amp Rel",
        4 to "Osc1 Wave", 5 to "Flt Atk", 6 to "Flt Dec", 7 to "Flt Sus", 8 to "Flt Rel",
        9 to "Resonance", 10 to "Env Amt", 11 to "Cutoff", 12 to "Detune", 13 to "Osc2 Wave",
        14 to "Volume", 15 to "LFO Speed", 16 to "LFO Wave", 17 to "Octave", 18 to "Osc Mix",
        19 to "LFO>Osc", 20 to "LFO>Flt", 21 to "LFO>Amp", 22 to "Ring Mod", 23 to "Osc1 PW",
        24 to "Osc2 PW", 25 to "Rev Size", 26 to "Rev Damp", 27 to "Rev Wet", 28 to "Rev Width",
        29 to "Drive", 30 to "Sync", 31 to "Portamento", 32 to "Keyboard", 33 to "Semitone",
        34 to "Flt Type", 35 to "Slope", 36 to "LFO Osc", 37 to "Key Track", 38 to "Flt Vel",
        39 to "Amp Vel", 40 to "Porta Mode",
    )
}

private fun loadBitmap(ctx: Context, path: String): ImageBitmap =
    ctx.assets.open(path).use { BitmapFactory.decodeStream(it).asImageBitmap() }

// Apply a value change from a vertical drag on a control, in normalized space
// (matching the desktop's sensitivity). Returns the new raw value.
private fun draggedValue(param: Int, p: AmsynthEngine.ParamInfo, curValue: Float, dy: Float, sens: Float): Float {
    val curNorm = AmsynthEngine.normalize(param, curValue)
    val newNorm = (curNorm - dy / sens).coerceIn(0f, 1f)
    return AmsynthEngine.denormalize(param, newNorm)
}

// Cycle a discrete control (popup/button) to its next frame; returns new value.
private fun cycledValue(param: Int, res: Skin.Res, curValue: Float): Float {
    val cur = AmsynthEngine.normalize(param, curValue)
    val curFrame = if (res.frames > 1) ((res.frames - 1) * cur).roundToInt().coerceIn(0, res.frames - 1) else 0
    val nf = (curFrame + 1) % res.frames
    val nn = if (res.frames > 1) nf.toFloat() / (res.frames - 1) else 0f
    return AmsynthEngine.denormalize(param, nn)
}

private fun knobSens(p: AmsynthEngine.ParamInfo, scale: Float): Float =
    ((if (p.step == 0f) 300f else min(40f * ((p.max - p.min) / p.step), 480f)) * scale).coerceAtLeast(1f)

/**
 * The full skinned control panel, fit whole into [modifier]'s box and centered.
 * When [pinMode] is true, tapping a control toggles it in the pinned set via
 * [onTogglePin] (and pinned controls are highlighted); otherwise knobs drag and
 * selectors cycle. A control consumes its touch from the initial press.
 */
@Composable
fun SkinView(
    params: List<AmsynthEngine.ParamInfo>,
    values: List<MutableFloatState>,
    modifier: Modifier = Modifier,
    pinMode: Boolean = false,
    pinned: List<Int> = emptyList(),
    onTogglePin: (Int) -> Unit = {},
) {
    val context = LocalContext.current
    val bg = remember { loadBitmap(context, "skin/background.png") }
    val bitmaps = remember { Skin.resources.mapValues { loadBitmap(context, "skin/" + it.value.file) } }

    Canvas(
        modifier = modifier.pointerInput(pinMode) {
            awaitPointerEventScope {
                while (true) {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val scale = min(size.width / Skin.BG_W.toFloat(), size.height / Skin.BG_H.toFloat())
                    val ox = (size.width - Skin.BG_W * scale) / 2f
                    val oy = (size.height - Skin.BG_H * scale) / 2f
                    val hit = Skin.controls.lastOrNull { c ->
                        val r = Skin.resources[c.res] ?: return@lastOrNull false
                        val x0 = ox + c.x * scale; val y0 = oy + c.y * scale
                        down.position.x >= x0 && down.position.x <= x0 + r.w * scale &&
                            down.position.y >= y0 && down.position.y <= y0 + r.h * scale
                    }
                    if (hit == null) continue
                    down.consume()
                    val idx = hit.param
                    if (pinMode) {
                        // Tap toggles pin (ignore small drags).
                        var moved = false
                        while (true) {
                            val e = awaitPointerEvent()
                            val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                            if (abs(ch.position.x - down.position.x) > 16f ||
                                abs(ch.position.y - down.position.y) > 16f) moved = true
                            if (!ch.pressed) { ch.consume(); if (!moved) onTogglePin(idx); break }
                            ch.consume()
                        }
                    } else if (hit.type == "knob") {
                        val sens = knobSens(params[idx], scale)
                        var prevY = down.position.y
                        while (true) {
                            val e = awaitPointerEvent()
                            val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                            if (!ch.pressed) { ch.consume(); break }
                            val dy = ch.position.y - prevY
                            prevY = ch.position.y
                            val nv = draggedValue(idx, params[idx], values[idx].floatValue, dy, sens)
                            values[idx].floatValue = nv
                            AmsynthEngine.nativeSetParameter(idx, nv)
                            ch.consume()
                        }
                    } else {
                        var moved = false
                        while (true) {
                            val e = awaitPointerEvent()
                            val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                            if (abs(ch.position.x - down.position.x) > 12f ||
                                abs(ch.position.y - down.position.y) > 12f) moved = true
                            if (!ch.pressed) {
                                ch.consume()
                                if (!moved) {
                                    val nv = cycledValue(idx, Skin.resources[hit.res]!!, values[idx].floatValue)
                                    values[idx].floatValue = nv
                                    AmsynthEngine.nativeSetParameter(idx, nv)
                                }
                                break
                            }
                            ch.consume()
                        }
                    }
                }
            }
        },
    ) {
        val scale = min(size.width / Skin.BG_W.toFloat(), size.height / Skin.BG_H.toFloat())
        val ox = (size.width - Skin.BG_W * scale) / 2f
        val oy = (size.height - Skin.BG_H * scale) / 2f
        drawImage(
            image = bg,
            srcOffset = IntOffset(0, 0),
            srcSize = IntSize(bg.width, bg.height),
            dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()),
            dstSize = IntSize((Skin.BG_W * scale).roundToInt(), (Skin.BG_H * scale).roundToInt()),
        )
        for (c in Skin.controls) {
            val r = Skin.resources[c.res] ?: continue
            val bmp = bitmaps[c.res] ?: continue
            val norm = AmsynthEngine.normalize(c.param, values[c.param].floatValue).coerceIn(0f, 1f)
            val frame = if (r.frames > 1) ((r.frames - 1) * norm).toInt().coerceIn(0, r.frames - 1) else 0
            drawImage(
                image = bmp,
                srcOffset = IntOffset(0, frame * r.h),
                srcSize = IntSize(r.w, r.h),
                dstOffset = IntOffset((ox + c.x * scale).roundToInt(), (oy + c.y * scale).roundToInt()),
                dstSize = IntSize((r.w * scale).roundToInt(), (r.h * scale).roundToInt()),
            )
            if (c.param in pinned) {
                // Highlight pinned controls so the selection is visible.
                drawRect(
                    color = Color(0x66E0A43B),
                    topLeft = androidx.compose.ui.geometry.Offset(ox + c.x * scale, oy + c.y * scale),
                    size = androidx.compose.ui.geometry.Size(r.w * scale, r.h * scale),
                )
            }
        }
    }
}

/**
 * A single pinned control drawn from the skin bitmaps, with its label — used in
 * the play view's mini-patch dock. Drag to change a knob; tap to cycle a
 * selector. Independent of [SkinView] so it can be placed next to the keyboard.
 */
@Composable
fun MiniKnob(
    param: Int,
    params: List<AmsynthEngine.ParamInfo>,
    values: List<MutableFloatState>,
    modifier: Modifier = Modifier,
) {
    val ctl = Skin.controlByParam[param] ?: return
    val res = Skin.resources[ctl.res] ?: return
    val context = LocalContext.current
    val bmp = remember(ctl.res) { loadBitmap(context, "skin/" + res.file) }

    Column(
        modifier = modifier.width(64.dp).padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(
            modifier = Modifier
                .width(56.dp)
                .aspectRatio(res.w.toFloat() / res.h)
                .pointerInput(param) {
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            if (ctl.type == "knob") {
                                val sens = knobSens(params[param], 1f)
                                var prevY = down.position.y
                                while (true) {
                                    val e = awaitPointerEvent()
                                    val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!ch.pressed) { ch.consume(); break }
                                    val dy = ch.position.y - prevY
                                    prevY = ch.position.y
                                    val nv = draggedValue(param, params[param], values[param].floatValue, dy, sens)
                                    values[param].floatValue = nv
                                    AmsynthEngine.nativeSetParameter(param, nv)
                                    ch.consume()
                                }
                            } else {
                                var moved = false
                                while (true) {
                                    val e = awaitPointerEvent()
                                    val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                                    if (abs(ch.position.x - down.position.x) > 12f ||
                                        abs(ch.position.y - down.position.y) > 12f) moved = true
                                    if (!ch.pressed) {
                                        ch.consume()
                                        if (!moved) {
                                            val nv = cycledValue(param, res, values[param].floatValue)
                                            values[param].floatValue = nv
                                            AmsynthEngine.nativeSetParameter(param, nv)
                                        }
                                        break
                                    }
                                    ch.consume()
                                }
                            }
                        }
                    }
                },
        ) {
            val norm = AmsynthEngine.normalize(param, values[param].floatValue).coerceIn(0f, 1f)
            val frame = if (res.frames > 1) ((res.frames - 1) * norm).toInt().coerceIn(0, res.frames - 1) else 0
            drawImage(
                image = bmp,
                srcOffset = IntOffset(0, frame * res.h),
                srcSize = IntSize(res.w, res.h),
                dstOffset = IntOffset(0, 0),
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            )
        }
        Text(
            Skin.label[param] ?: "",
            color = Color(0xFFE7EEF0),
            fontSize = 10.sp,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}
