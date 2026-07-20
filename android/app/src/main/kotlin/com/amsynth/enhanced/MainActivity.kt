package com.amsynth.enhanced

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch

/**
 * Playable front-end for the native amsynth engine. The control panel is the
 * actual desktop skin (see [SkinView]); below it are octave/panic controls and
 * a sweepable multi-touch keyboard. The top bar hosts the bank/preset browsers
 * and save/load. All DSP and audio output are native (Oboe).
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

    // One value state per parameter; the skin reads these to pick knob frames.
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

    LaunchedEffect(Unit) { loadBank(currentBank) }

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
            // The native skin panel fills the space above the keyboard.
            SkinView(
                params = params,
                values = values,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(4.dp),
            )

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
                    .height(140.dp)
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
