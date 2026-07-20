package com.amsynth.enhanced

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin

/**
 * Playable front-end for the native amsynth engine, styled after the desktop
 * panel: rotary knobs grouped into the same sections (oscillators, mixer, LFO,
 * filter, envelopes, effects, master), a bank/preset browser, a sweepable
 * multi-touch keyboard, octave shift, and save/load of sounds. All DSP and
 * audio output are native (Oboe); this activity only sends control events.
 */
class MainActivity : ComponentActivity() {

    private val params by lazy { AmsynthEngine.parameters() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                SynthScreen(params)
            }
        }
    }
}

// A control panel: a title and the (paramIndex -> label) knobs it contains. The
// grouping and order mirror the desktop amsynth layout.
private data class Panel(val title: String, val items: List<Pair<Int, String>>)

private val PANELS = listOf(
    Panel("Oscillator 1", listOf(4 to "Waveform", 23 to "Shape")),
    Panel("Oscillator 2", listOf(13 to "Waveform", 24 to "Shape", 17 to "Octave", 33 to "Semitone", 12 to "Detune", 30 to "Sync")),
    Panel("Mixer", listOf(18 to "Osc Balance", 22 to "Ring Mod")),
    Panel("LFO", listOf(16 to "Waveform", 15 to "Speed", 36 to "Routing", 19 to "To Pitch", 20 to "To Filter", 21 to "To Amp")),
    Panel("Filter", listOf(34 to "Type", 35 to "Slope", 11 to "Cutoff", 9 to "Resonance", 10 to "Env Amount", 37 to "Key Track", 38 to "Key Vel")),
    Panel("Filter Envelope", listOf(5 to "Attack", 6 to "Decay", 7 to "Sustain", 8 to "Release")),
    Panel("Amp Envelope", listOf(0 to "Attack", 1 to "Decay", 2 to "Sustain", 3 to "Release", 39 to "Velocity")),
    Panel("Effects", listOf(29 to "Distortion", 25 to "Reverb Size", 26 to "Reverb Damp", 27 to "Reverb Wet", 28 to "Reverb Width")),
    Panel("Master", listOf(14 to "Volume", 31 to "Portamento", 40 to "Porta Mode", 32 to "Keyboard")),
)

private enum class Sheet { NONE, PRESETS, BANKS }

private fun bankDisplayName(file: String): String = when {
    file.startsWith("amsynth_factory") -> "Factory"
    else -> file.removeSuffix(".amSynth.bank").removeSuffix(".bank")
        .replace("BriansBank", "Brian's Bank ")
        .replace("PatriksBank", "Patrik's Bank ")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SynthScreen(params: List<AmsynthEngine.ParamInfo>) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // One value state per parameter, so dragging a knob only recomposes that
    // knob (not all 41). Seeded from the engine defaults.
    val values = remember { params.map { mutableFloatStateOf(it.default) } }

    val banks = remember {
        (context.assets.list("banks") ?: emptyArray())
            .sortedWith(compareBy({ !it.startsWith("amsynth_factory") }, { it }))
    }
    var currentBank by remember { mutableStateOf(banks.firstOrNull() ?: "amsynth_factory.bank") }
    var presets by remember { mutableStateOf<List<AmsynthEngine.Preset>>(emptyList()) }
    val currentPreset = remember { mutableIntStateOf(0) }
    var soundName by remember { mutableStateOf("AMsynth enhanced") }
    val octave = remember { mutableIntStateOf(0) }

    var sheet by remember { mutableStateOf(Sheet.NONE) }
    var showMenu by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    fun refreshFrom(vals: FloatArray) {
        vals.forEachIndexed { i, v -> if (i < values.size) values[i].floatValue = v }
    }

    fun selectPreset(bankIndex: Int) {
        currentPreset.intValue = bankIndex
        AmsynthEngine.nativeSelectPreset(bankIndex)
        refreshFrom(AmsynthEngine.nativeGetPresetValues(bankIndex))
        soundName = presets.firstOrNull { it.index == bankIndex }?.name ?: "Preset $bankIndex"
    }

    fun loadBank(file: String) {
        val bytes = context.assets.open("banks/$file").readBytes()
        if (AmsynthEngine.nativeLoadBank(bytes) > 0) {
            currentBank = file
            presets = AmsynthEngine.presets()
            selectPreset(presets.firstOrNull()?.index ?: 0)
        }
    }

    // Load the default (factory) bank once, seed the UI, apply the first sound.
    LaunchedEffect(Unit) { loadBank(currentBank) }

    // Run audio only while foregrounded; re-apply the current sound on resume.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    AmsynthEngine.nativeCreate(0)
                    AmsynthEngine.nativeSelectPreset(currentPreset.intValue)
                }
                Lifecycle.Event.ON_PAUSE -> {
                    AmsynthEngine.nativeAllNotesOff()
                    AmsynthEngine.nativeDestroy()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Save / load a sound through the system file picker (SAF).
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            val state = AmsynthEngine.nativeGetState()
            context.contentResolver.openOutputStream(uri)?.use { it.write(state.toByteArray()) }
        }
    }
    val loadLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes != null) {
                val applied = AmsynthEngine.nativeApplyState(String(bytes))
                if (applied != null) {
                    refreshFrom(applied)
                    soundName = uri.lastPathSegment?.substringAfterLast('/') ?: "Loaded sound"
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1B2226),
                    titleContentColor = Color(0xFFE0A43B),
                ),
                title = { Text(soundName, fontSize = 17.sp, maxLines = 1) },
                actions = {
                    TextButton(onClick = { sheet = Sheet.BANKS }) {
                        Text("Banks", color = Color(0xFFE0A43B))
                    }
                    TextButton(onClick = { sheet = Sheet.PRESETS }) {
                        Text("Presets", color = Color(0xFFE0A43B))
                    }
                    Box {
                        TextButton(onClick = { showMenu = true }) {
                            Text("Menu", color = Color(0xFFE0A43B))
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(text = { Text("Save sound…") }, onClick = {
                                showMenu = false
                                saveLauncher.launch("${soundName.ifBlank { "sound" }}.amSynthState")
                            })
                            DropdownMenuItem(text = { Text("Load sound…") }, onClick = {
                                showMenu = false
                                loadLauncher.launch(arrayOf("*/*"))
                            })
                            DropdownMenuItem(text = { Text("All notes off") }, onClick = {
                                showMenu = false
                                AmsynthEngine.nativeAllNotesOff()
                            })
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFF222B30)),
        ) {
            // Knob panels scroll above the keyboard.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp),
            ) {
                PANELS.forEach { panel -> PanelView(panel, params, values) }
                Spacer(Modifier.height(8.dp))
            }

            // Octave / panic row.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { if (octave.intValue > -3) octave.intValue-- }) { Text("Oct −") }
                Text("C${3 + octave.intValue}", color = Color(0xFFE7EEF0), fontSize = 14.sp)
                TextButton(onClick = { if (octave.intValue < 3) octave.intValue++ }) { Text("Oct +") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { AmsynthEngine.nativeAllNotesOff() }) { Text("Panic") }
            }

            Keyboard(
                baseNote = 48 + octave.intValue * 12,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .padding(horizontal = 10.dp)
                    .padding(bottom = 8.dp),
            )
        }

        if (sheet != Sheet.NONE) {
            ModalBottomSheet(
                onDismissRequest = { sheet = Sheet.NONE },
                sheetState = sheetState,
                containerColor = Color(0xFF1B2226),
            ) {
                val closing = {
                    scope.launch { sheetState.hide() }.invokeOnCompletion { sheet = Sheet.NONE }
                }
                when (sheet) {
                    Sheet.BANKS -> LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(banks) { file ->
                            val selected = file == currentBank
                            Text(
                                bankDisplayName(file),
                                color = if (selected) Color(0xFFE0A43B) else Color(0xFFE7EEF0),
                                fontSize = 16.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { loadBank(file); closing() }
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                            )
                        }
                    }
                    else -> LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(presets) { preset ->
                            val selected = preset.index == currentPreset.intValue
                            Text(
                                preset.name,
                                color = if (selected) Color(0xFFE0A43B) else Color(0xFFE7EEF0),
                                fontSize = 16.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectPreset(preset.index); closing() }
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PanelView(
    panel: Panel,
    params: List<AmsynthEngine.ParamInfo>,
    values: List<MutableFloatState>,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            panel.title.uppercase(),
            color = Color(0xFFE0A43B),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 2.dp, bottom = 2.dp),
        )
        FlowRow(modifier = Modifier.fillMaxWidth()) {
            panel.items.forEach { (index, label) ->
                Knob(param = params[index], label = label, value = values[index].floatValue) { v ->
                    values[index].floatValue = v
                    AmsynthEngine.nativeSetParameter(index, v)
                }
            }
        }
    }
}

/**
 * A rotary knob. Drag up/down to change the value; discrete parameters snap to
 * their step. The label below shows the value formatted exactly like the desktop
 * GUI ("Saw", "24 dB/oct", …). The knob consumes its touch from the initial
 * press, so dragging a knob never scrolls the panel behind it.
 */
@Composable
private fun Knob(
    param: AmsynthEngine.ParamInfo,
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
) {
    val cur by rememberUpdatedState(value)
    val cb by rememberUpdatedState(onValueChange)
    val span = (param.max - param.min).let { if (it == 0f) 1f else it }
    val frac = ((value - param.min) / span).coerceIn(0f, 1f)

    Column(
        modifier = Modifier.width(74.dp).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            color = Color(0xFFB9C6CC),
            fontSize = 10.sp,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
        Canvas(
            modifier = Modifier
                .size(46.dp)
                .padding(top = 2.dp, bottom = 2.dp)
                .pointerInput(param.index) {
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            down.consume()
                            var prevY = down.position.y
                            while (true) {
                                val ev = awaitPointerEvent()
                                val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!ch.pressed) { ch.consume(); break }
                                val dy = ch.position.y - prevY
                                prevY = ch.position.y
                                var nv = (cur + (-dy / 250f) * span).coerceIn(param.min, param.max)
                                if (param.step > 0f) {
                                    nv = (param.min + round((nv - param.min) / param.step) * param.step)
                                        .coerceIn(param.min, param.max)
                                }
                                cb(nv)
                                ch.consume()
                            }
                        }
                    }
                },
        ) {
            val sw = 5.dp.toPx()
            val d = size.minDimension
            val tl = Offset((size.width - d) / 2 + sw / 2, (size.height - d) / 2 + sw / 2)
            val arcSize = Size(d - sw, d - sw)
            drawArc(Color(0xFF3A474E), 135f, 270f, false, tl, arcSize, style = Stroke(sw, cap = StrokeCap.Round))
            drawArc(Color(0xFFE0A43B), 135f, 270f * frac, false, tl, arcSize, style = Stroke(sw, cap = StrokeCap.Round))
            val ang = Math.toRadians(135.0 + 270.0 * frac)
            val cx = size.width / 2
            val cy = size.height / 2
            val r = d / 2 - sw
            drawLine(
                Color(0xFFEEF3F4),
                Offset(cx, cy),
                Offset(cx + (r * cos(ang)).toFloat(), cy + (r * sin(ang)).toFloat()),
                strokeWidth = 2.5.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
        Text(
            AmsynthEngine.display(param.index, value),
            color = Color(0xFFE7EEF0),
            fontSize = 10.sp,
            maxLines = 1,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * A two-octave piano keyboard you can sweep across. A single pointer surface
 * owns every touch: dragging a finger from one key to the next re-triggers the
 * note (legato glide), and multiple fingers sound multiple notes at once.
 */
@Composable
private fun Keyboard(baseNote: Int, modifier: Modifier = Modifier) {
    val whiteOffsets = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    val whiteNotes = remember(baseNote) {
        buildList {
            for (oct in 0..1) for (o in whiteOffsets) add(baseNote + 12 * oct + o)
            add(baseNote + 24)
        }
    }
    val blackKeys = remember(baseNote) {
        val afterWhite = intArrayOf(0, 1, 3, 4, 5)
        val noteOffset = intArrayOf(1, 3, 6, 8, 10)
        buildList {
            for (oct in 0..1) for (k in afterWhite.indices)
                add(Pair(oct * 7 + afterWhite[k], baseNote + 12 * oct + noteOffset[k]))
        }
    }
    val whiteCount = whiteNotes.size
    val pressed = remember { mutableStateListOf<Int>() }

    fun hitTest(pos: Offset, size: Size): Int {
        val ww = size.width / whiteCount
        val bw = ww * 0.62f
        val bh = size.height * 0.6f
        if (pos.y <= bh) {
            for ((whiteIdx, note) in blackKeys) {
                val cx = (whiteIdx + 1) * ww
                if (pos.x >= cx - bw / 2 && pos.x <= cx + bw / 2) return note
            }
        }
        val wi = (pos.x / ww).toInt().coerceIn(0, whiteCount - 1)
        return whiteNotes[wi]
    }

    Canvas(
        modifier = modifier.pointerInput(baseNote) {
            awaitPointerEventScope {
                val active = HashMap<Long, Int>()
                while (true) {
                    val event = awaitPointerEvent()
                    for (change in event.changes) {
                        val id = change.id.value
                        if (change.pressed) {
                            val note = hitTest(change.position, Size(size.width.toFloat(), size.height.toFloat()))
                            val prev = active[id]
                            if (prev != note) {
                                if (prev != null) {
                                    AmsynthEngine.nativeNoteOff(prev)
                                    pressed.remove(prev)
                                }
                                AmsynthEngine.nativeNoteOn(note, 100)
                                active[id] = note
                                pressed.add(note)
                                change.consume()
                            }
                        } else {
                            val prev = active.remove(id)
                            if (prev != null) {
                                AmsynthEngine.nativeNoteOff(prev)
                                pressed.remove(prev)
                                change.consume()
                            }
                        }
                    }
                }
            }
        },
    ) {
        val ww = size.width / whiteCount
        val bw = ww * 0.62f
        val bh = size.height * 0.6f
        for (i in 0 until whiteCount) {
            val down = whiteNotes[i] in pressed
            drawRect(
                color = if (down) Color(0xFFE0A43B) else Color(0xFFEEF3F4),
                topLeft = Offset(i * ww, 0f),
                size = Size(ww - 1.5f, size.height),
            )
        }
        for ((whiteIdx, note) in blackKeys) {
            val cx = (whiteIdx + 1) * ww
            val down = note in pressed
            drawRect(
                color = if (down) Color(0xFFB9791F) else Color(0xFF1B2226),
                topLeft = Offset(cx - bw / 2, 0f),
                size = Size(bw, bh),
            )
        }
    }
}
