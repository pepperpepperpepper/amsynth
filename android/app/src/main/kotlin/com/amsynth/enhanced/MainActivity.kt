package com.amsynth.enhanced

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.MutableIntState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

private enum class Sheet { NONE, PRESETS, BANKS, TUNING, KNOBS }

// One preset in the cross-bank searchable library. A user-saved preset carries
// its full sound in [userState] (and bank == USER_BANK); factory presets don't.
private const val USER_BANK = "user"
private data class LibEntry(
    val bank: String,
    val bankName: String,
    val index: Int,
    val name: String,
    val cats: List<String>,
    val userState: String? = null,
)

// A stable identity for the favorites set (survives across launches).
private fun libId(e: LibEntry): String =
    if (e.userState != null) "u${e.name}" else "f${e.bank}${e.index}"

private const val FAV_CAT = "★ Favorites"
private const val USER_CAT = "My Presets"
private val CATEGORY_ORDER =
    listOf("All", "Bass", "Lead", "Pad", "Keys", "Strings", "Brass", "Pluck", "Perc", "FX", "Other")

// Derive category tags from a preset name (amsynth presets carry no metadata).
private fun categoriesFor(name: String): List<String> {
    val n = name.lowercase()
    fun has(vararg k: String) = k.any { n.contains(it) }
    val c = mutableListOf<String>()
    if (has("bass", "sub ")) c.add("Bass")
    if (has("lead", "solo")) c.add("Lead")
    if (has("pad", "warm", "ambient", "atmos")) c.add("Pad")
    if (has("string", "violin", "cello", "orchestr")) c.add("Strings")
    if (has("brass", "horn", "trumpet", "tuba", "sax", "trombone")) c.add("Brass")
    if (has("piano", "rhodes", "clav", "organ", "key", "electric p", "harpsichord")) c.add("Keys")
    if (has("pluck", "guitar", "harp", "bell", "mallet", "marimba", "vibe")) c.add("Pluck")
    if (has("drum", "perc", "kick", "snare", "tom ", "hat", "clap", "cymbal", "conga")) c.add("Perc")
    if (has("fx", "noise", "sweep", "wind", "effect", "sfx", "zap", "laser", "drone")) c.add("FX")
    if (c.isEmpty()) c.add("Other")
    return c
}

private fun bankDisplayName(file: String): String = when {
    file.startsWith("amsynth_factory") -> "Factory"
    else -> file.removeSuffix(".amSynth.bank").removeSuffix(".bank")
        .replace("BriansBank", "Brian's Bank ")
        .replace("PatriksBank", "Patrik's Bank ")
}

private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
// Scientific pitch notation, MIDI 60 = C4 (A4 = 69 = 440 Hz).
private fun noteName(midi: Int): String = "${NOTE_NAMES[((midi % 12) + 12) % 12]}${midi / 12 - 1}"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SynthScreen(params: List<AmsynthEngine.ParamInfo>) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    // Persisted UI state (last sound, pinned knobs, favourites, mode, tuning).
    val prefs = remember { context.getSharedPreferences("amsynth", Context.MODE_PRIVATE) }

    // One value state per parameter; the skin reads these to pick knob frames.
    val values = remember { params.map { mutableFloatStateOf(it.default) } }

    val banks = remember {
        (context.assets.list("banks") ?: emptyArray())
            .sortedWith(compareBy({ !it.startsWith("amsynth_factory") }, { it }))
    }
    var currentBank by remember { mutableStateOf(banks.firstOrNull() ?: "amsynth_factory.bank") }
    var presets by remember { mutableStateOf<List<AmsynthEngine.Preset>>(emptyList()) }
    val currentPreset = remember { mutableIntStateOf(0) }
    var soundName by remember { mutableStateOf(prefs.getString("soundName", null) ?: "AMsynth enhanced") }
    val octave = remember { mutableIntStateOf(0) }

    var sheet by remember { mutableStateOf(Sheet.NONE) }
    var showMenu by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf("") }
    val sheetState = rememberModalBottomSheetState()

    // Play (piano + pinned "mini-patch" of knobs) is the default startup screen;
    // Console shows the full panel. Pin mode: tap knobs in the console to pin.
    var playMode by remember { mutableStateOf(prefs.getBoolean("play", true)) }
    var pinMode by remember { mutableStateOf(false) }
    // Pinned knobs (persisted), default Cutoff + Resonance.
    val pinned = remember {
        mutableStateListOf<Int>().apply {
            addAll((prefs.getString("pinned", "11,9") ?: "11,9").split(",").mapNotNull { it.trim().toIntOrNull() })
            if (isEmpty()) addAll(listOf(11, 9))
        }
    }

    // Cross-bank searchable preset library.
    var library by remember { mutableStateOf<List<LibEntry>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("All") }
    var currentTuning by remember { mutableStateOf(prefs.getString("tuningName", null) ?: "12-TET") }
    // The Scala zip entry backing the current tuning ("" = 12-TET), for restore.
    var currentTuningEntry by remember { mutableStateOf(prefs.getString("tuningEntry", null) ?: "") }
    var scales by remember { mutableStateOf<List<ScalaLibrary.Scale>>(emptyList()) }
    var tuningQuery by remember { mutableStateOf("") }
    val rootNote = remember { mutableIntStateOf(prefs.getInt("root", 60)) } // scale's 1/1 (tonic), default C4

    // Favourited presets (ids from [libId]) and user-saved presets (name -> state).
    val favorites = remember {
        mutableStateListOf<String>().apply { addAll(prefs.getStringSet("favorites", emptySet()) ?: emptySet()) }
    }
    val userPresets = remember {
        mutableStateListOf<Pair<String, String>>().apply {
            (prefs.getStringSet("userPresets", emptySet()) ?: emptySet()).forEach {
                val i = it.indexOf('')
                if (i > 0) add(it.substring(0, i) to it.substring(i + 1))
            }
        }
    }

    // Persist all lightweight UI state (everything except the live sound, which
    // needs the engine and is saved on pause). Cheap; called on each change.
    fun persistPrefs() {
        prefs.edit()
            .putBoolean("play", playMode)
            .putString("pinned", pinned.joinToString(","))
            .putStringSet("favorites", favorites.toHashSet())
            .putStringSet("userPresets", userPresets.map { it.first + "" + it.second }.toHashSet())
            .putInt("root", rootNote.intValue)
            .putString("tuningName", currentTuning)
            .putString("tuningEntry", currentTuningEntry)
            .putString("soundName", soundName)
            .apply()
    }

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

    // Select any preset from the library, loading its bank first if needed. A
    // user preset carries its full sound, so it's applied directly.
    fun selectFromLibrary(e: LibEntry) {
        if (e.userState != null) {
            AmsynthEngine.nativeApplyState(e.userState)?.let { refreshFrom(it) }
            soundName = e.name
            persistPrefs()
            return
        }
        if (e.bank != currentBank) {
            val bytes = context.assets.open("banks/${e.bank}").readBytes()
            if (AmsynthEngine.nativeLoadBank(bytes) > 0) {
                currentBank = e.bank
                presets = AmsynthEngine.presets()
            }
        }
        selectPreset(e.index)
    }

    // Startup: load the default bank (so the preset list exists) then restore the
    // last sound saved on the previous run. nativeApplyState returns the parsed
    // values whether or not the engine is up yet, so this converges with ON_RESUME.
    LaunchedEffect(Unit) {
        runCatching {
            loadBank(currentBank)
            val saved = prefs.getString("sound", null)
            if (!saved.isNullOrBlank()) {
                AmsynthEngine.nativeApplyState(saved)?.let { refreshFrom(it) }
                soundName = prefs.getString("soundName", null) ?: soundName
            }
        }
    }

    // Build the cross-bank library index once (off the main thread). Uses a
    // throwaway parser, so it never disturbs the live bank.
    LaunchedEffect(banks) {
        runCatching {
            val lib = withContext(Dispatchers.Default) {
                val out = mutableListOf<LibEntry>()
                for (f in banks) {
                    val names = AmsynthEngine.nativeListBankPresets(context.assets.open("banks/$f").readBytes())
                    names.forEachIndexed { i, nm ->
                        if (nm.isNotBlank()) out.add(LibEntry(f, bankDisplayName(f), i, nm, categoriesFor(nm)))
                    }
                }
                out
            }
            library = lib
        }
    }

    // Build the Scala scale index once, off the main thread (cached after first run).
    LaunchedEffect(Unit) {
        runCatching { scales = withContext(Dispatchers.IO) { ScalaLibrary.load(context) } }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    // The engine is destroyed on pause, so re-push the current sound
                    // (the live parameter values) and tuning onto the fresh engine —
                    // this both restores state across backgrounding and applies the
                    // last-session sound restored at startup.
                    AmsynthEngine.nativeCreate(0)
                    values.forEachIndexed { i, v -> AmsynthEngine.nativeSetParameter(i, v.floatValue) }
                    if (currentTuningEntry.isNotBlank()) {
                        ScalaLibrary.read(context, currentTuningEntry)?.let { AmsynthEngine.nativeLoadScale(it) }
                    } else {
                        AmsynthEngine.nativeResetTuning()
                    }
                    AmsynthEngine.nativeSetTuningRoot(rootNote.intValue)
                }
                Lifecycle.Event.ON_PAUSE -> {
                    // Save the live sound (needs the engine) plus the rest of the
                    // UI state before tearing the engine down.
                    val state = AmsynthEngine.nativeGetState()
                    if (state.isNotBlank()) prefs.edit().putString("sound", state).apply()
                    persistPrefs()
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
    val scaleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val txt = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            if (txt != null && AmsynthEngine.nativeLoadScale(txt)) {
                AmsynthEngine.nativeSetTuningRoot(rootNote.intValue)
                currentTuning = uri.lastPathSegment?.substringAfterLast('/') ?: "Custom .scl"
                currentTuningEntry = "" // external file — not restorable from the bundled archive
                persistPrefs()
            }
        }
    }
    val keymapLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val txt = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            if (txt != null) AmsynthEngine.nativeLoadKeymap(txt)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Neutral near-black matched to the skin so the letterbox around the
            // 3:2 panel blends into the instrument instead of framing it.
            .background(Color(0xFF262626)),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Slim header: sound name + bank/preset/menu. Far thinner than a
            // Material TopAppBar (which wastes ~64dp of a short screen).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E1E1E))
                    .padding(horizontal = 12.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    soundName,
                    color = Color(0xFFE0A43B),
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (playMode) {
                    Text(
                        "Knobs", color = Color(0xFFE0A43B), fontSize = 14.sp,
                        modifier = Modifier.clickable { sheet = Sheet.KNOBS }.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                    Text(
                        "Console", color = Color(0xFFE0A43B), fontSize = 14.sp,
                        modifier = Modifier.clickable { playMode = false; persistPrefs() }.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                } else {
                    Text(
                        "Pin", color = if (pinMode) Color(0xFFFFD27A) else Color(0xFF6E6145), fontSize = 14.sp,
                        modifier = Modifier.clickable { pinMode = !pinMode }.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                    Text(
                        "Play", color = Color(0xFFE0A43B), fontSize = 14.sp,
                        modifier = Modifier.clickable { pinMode = false; playMode = true; persistPrefs() }.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
                Text(
                    "Banks", color = Color(0xFFE0A43B), fontSize = 14.sp,
                    modifier = Modifier.clickable { sheet = Sheet.BANKS }.padding(horizontal = 8.dp, vertical = 3.dp),
                )
                Text(
                    "Presets", color = Color(0xFFE0A43B), fontSize = 14.sp,
                    modifier = Modifier.clickable { sheet = Sheet.PRESETS }.padding(horizontal = 8.dp, vertical = 3.dp),
                )
                Text(
                    "Tuning", color = Color(0xFFE0A43B), fontSize = 14.sp,
                    modifier = Modifier.clickable { sheet = Sheet.TUNING }.padding(horizontal = 8.dp, vertical = 3.dp),
                )
                Box {
                    Text(
                        "Menu", color = Color(0xFFE0A43B), fontSize = 14.sp,
                        modifier = Modifier.clickable { showMenu = true }.padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(text = { Text("Save as preset…") }, onClick = {
                            showMenu = false
                            saveName = soundName
                            showSaveDialog = true
                        })
                        DropdownMenuItem(text = { Text("Export sound…") }, onClick = {
                            showMenu = false
                            saveLauncher.launch("${soundName.ifBlank { "sound" }}.amSynthState")
                        })
                        DropdownMenuItem(text = { Text("Import sound…") }, onClick = {
                            showMenu = false
                            loadLauncher.launch(arrayOf("*/*"))
                        })
                        DropdownMenuItem(text = { Text("All notes off") }, onClick = {
                            showMenu = false
                            AmsynthEngine.nativeAllNotesOff()
                        })
                    }
                }
            }

            if (!playMode) {
                // Full console: fill the whole width (big knobs, edge to edge). The
                // panel is 3:2, so on wide/short screens it's taller than the
                // viewport → scroll for the lower rows. On squarer/tall screens it
                // fits with room to spare → drop a small keyboard into that dead
                // space so the full console is also playable.
                val onTogglePin: (Int) -> Unit = { p ->
                    if (p in pinned) pinned.remove(p) else pinned.add(p); persistPrefs()
                }
                BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    val panelH = maxWidth * (Skin.BG_H.toFloat() / Skin.BG_W)
                    if (maxHeight - panelH >= 120.dp) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            SkinView(
                                params = params,
                                values = values,
                                modifier = Modifier.fillMaxWidth().aspectRatio(Skin.BG_W.toFloat() / Skin.BG_H),
                                pinMode = pinMode,
                                pinned = pinned,
                                onTogglePin = onTogglePin,
                            )
                            ConsoleKeys(octave, modifier = Modifier.weight(1f).fillMaxWidth())
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                            SkinView(
                                params = params,
                                values = values,
                                modifier = Modifier.fillMaxWidth().aspectRatio(Skin.BG_W.toFloat() / Skin.BG_H),
                                pinMode = pinMode,
                                pinned = pinned,
                                onTogglePin = onTogglePin,
                            )
                        }
                    }
                }
                if (pinMode) {
                    Text(
                        "Tap knobs to pin them to the Play view",
                        color = Color(0xFFB9C6CC),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(6.dp),
                    )
                }
            } else {
                // Play: pinned mini-patch (centered in the free space) above a
                // keyboard sized to a fraction of the screen — so on tall/large
                // screens the keyboard doesn't balloon and the knobs don't strand.
                val kbHeight = (LocalConfiguration.current.screenHeightDp * 0.34f).dp
                    .coerceIn(150.dp, 320.dp)
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (pinned.isEmpty()) {
                        Text(
                            "No knobs yet — tap “Knobs” to add some.",
                            color = Color(0xFFB9C6CC),
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { sheet = Sheet.KNOBS }
                                .padding(24.dp),
                        )
                    } else {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            pinned.forEach { p -> MiniKnob(p, params, values) }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "OCT −", color = Color(0xFFE0A43B), fontSize = 14.sp,
                        modifier = Modifier.clickable { if (octave.intValue > -3) octave.intValue-- }.padding(vertical = 6.dp, horizontal = 6.dp),
                    )
                    Text(
                        "C${3 + octave.intValue}", color = Color(0xFFB9C6CC), fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                    Text(
                        "OCT +", color = Color(0xFFE0A43B), fontSize = 14.sp,
                        modifier = Modifier.clickable { if (octave.intValue < 3) octave.intValue++ }.padding(vertical = 6.dp, horizontal = 6.dp),
                    )
                }

                Keyboard(
                    baseNote = 48 + octave.intValue * 12,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(kbHeight)
                        .padding(horizontal = 10.dp)
                        .padding(bottom = 6.dp),
                )
            }
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
                    Sheet.TUNING -> {
                        val tuneFiltered = if (tuningQuery.isBlank()) scales else scales.filter {
                            it.display.contains(tuningQuery, ignoreCase = true) ||
                                it.entry.contains(tuningQuery, ignoreCase = true)
                        }
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            item {
                                OutlinedTextField(
                                    value = tuningQuery,
                                    onValueChange = { tuningQuery = it },
                                    placeholder = { Text("Search ${scales.size} Scala scales") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 6.dp),
                                )
                                Text(
                                    "Current: $currentTuning",
                                    color = Color(0xFF8AA0A8), fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                                )
                                Text(
                                    "12-TET (default)",
                                    color = if (currentTuning == "12-TET") Color(0xFFE0A43B) else Color(0xFFE7EEF0),
                                    fontSize = 16.sp,
                                    modifier = Modifier.fillMaxWidth()
                                        .clickable {
                                            AmsynthEngine.nativeResetTuning()
                                            AmsynthEngine.nativeSetTuningRoot(rootNote.intValue)
                                            currentTuning = "12-TET"; currentTuningEntry = ""
                                            persistPrefs(); closing()
                                        }
                                        .padding(horizontal = 20.dp, vertical = 10.dp),
                                )
                                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                                    Text(
                                        "Load .scl…", color = Color(0xFFB9C6CC), fontSize = 14.sp,
                                        modifier = Modifier.clickable { scaleLauncher.launch(arrayOf("*/*")); closing() }
                                            .padding(end = 20.dp, top = 4.dp, bottom = 4.dp),
                                    )
                                    Text(
                                        "Load .kbm…", color = Color(0xFFB9C6CC), fontSize = 14.sp,
                                        modifier = Modifier.clickable { keymapLauncher.launch(arrayOf("*/*")); closing() }
                                            .padding(vertical = 4.dp),
                                    )
                                }
                                // Root note (tonic): the MIDI note the scale's 1/1 is anchored to.
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("Root note (tonic):", color = Color(0xFFB9C6CC), fontSize = 14.sp)
                                    Text(
                                        "−", color = Color(0xFFE0A43B), fontSize = 20.sp,
                                        modifier = Modifier
                                            .clickable {
                                                if (rootNote.intValue > 0) {
                                                    rootNote.intValue--
                                                    AmsynthEngine.nativeSetTuningRoot(rootNote.intValue)
                                                    persistPrefs()
                                                }
                                            }
                                            .padding(horizontal = 14.dp, vertical = 2.dp),
                                    )
                                    Text(
                                        noteName(rootNote.intValue), color = Color(0xFFE7EEF0), fontSize = 15.sp,
                                        modifier = Modifier.padding(horizontal = 6.dp),
                                    )
                                    Text(
                                        "+", color = Color(0xFFE0A43B), fontSize = 20.sp,
                                        modifier = Modifier
                                            .clickable {
                                                if (rootNote.intValue < 127) {
                                                    rootNote.intValue++
                                                    AmsynthEngine.nativeSetTuningRoot(rootNote.intValue)
                                                    persistPrefs()
                                                }
                                            }
                                            .padding(horizontal = 14.dp, vertical = 2.dp),
                                    )
                                }
                                // Tap a key to pick the root note directly.
                                NotePicker(
                                    selected = rootNote.intValue,
                                    onSelect = { n ->
                                        rootNote.intValue = n
                                        AmsynthEngine.nativeSetTuningRoot(n)
                                        persistPrefs()
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(64.dp)
                                        .padding(horizontal = 20.dp, vertical = 4.dp),
                                )
                                Text(
                                    if (scales.isEmpty()) "Loading Scala library…" else "${tuneFiltered.size} scales",
                                    color = Color(0xFF8AA0A8), fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                                )
                            }
                            items(tuneFiltered) { s ->
                                Text(
                                    s.display,
                                    color = if (currentTuning == s.display) Color(0xFFE0A43B) else Color(0xFFE7EEF0),
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth()
                                        .clickable {
                                            val txt = ScalaLibrary.read(context, s.entry)
                                            if (txt != null && AmsynthEngine.nativeLoadScale(txt)) {
                                                AmsynthEngine.nativeSetTuningRoot(rootNote.intValue)
                                                currentTuning = s.display
                                                currentTuningEntry = s.entry
                                                persistPrefs()
                                            }
                                            closing()
                                        }
                                        .padding(horizontal = 20.dp, vertical = 9.dp),
                                )
                            }
                        }
                    }
                    Sheet.KNOBS -> {
                        // Add/remove knobs from the Play mini-patch: tap to toggle.
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            item {
                                Text(
                                    "Knobs on the Play screen — tap to add or remove",
                                    color = Color(0xFF8AA0A8), fontSize = 12.sp,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                                )
                            }
                            items(Skin.controls.map { it.param }.distinct()) { p ->
                                val on = p in pinned
                                Row(
                                    modifier = Modifier.fillMaxWidth()
                                        .clickable {
                                            if (on) pinned.remove(p) else pinned.add(p)
                                            persistPrefs()
                                        }
                                        .padding(horizontal = 20.dp, vertical = 11.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        if (on) "★" else "☆",
                                        color = if (on) Color(0xFFE0A43B) else Color(0xFF6E6145),
                                        fontSize = 18.sp,
                                        modifier = Modifier.padding(end = 14.dp),
                                    )
                                    Text(
                                        Skin.label[p] ?: params.getOrNull(p)?.name ?: "Param $p",
                                        color = if (on) Color(0xFFE0A43B) else Color(0xFFE7EEF0),
                                        fontSize = 16.sp,
                                    )
                                }
                            }
                        }
                    }
                    else -> {
                        // Factory presets + the user's saved presets, one library.
                        val allEntries = remember(library, userPresets.toList()) {
                            library + userPresets.mapIndexed { i, (nm, st) ->
                                LibEntry(USER_BANK, USER_CAT, i, nm, categoriesFor(nm), st)
                            }
                        }
                        val present = buildList {
                            add("All")
                            if (favorites.isNotEmpty()) add(FAV_CAT)
                            if (userPresets.isNotEmpty()) add(USER_CAT)
                            addAll(CATEGORY_ORDER.drop(1).filter { c -> allEntries.any { e -> c in e.cats } })
                        }
                        val filtered = allEntries.filter { e ->
                            (query.isBlank() || e.name.contains(query, ignoreCase = true)) &&
                                when (category) {
                                    "All" -> true
                                    FAV_CAT -> libId(e) in favorites
                                    USER_CAT -> e.userState != null
                                    else -> category in e.cats
                                }
                        }
                        LazyColumn(modifier = Modifier.fillMaxWidth()) {
                            item {
                                OutlinedTextField(
                                    value = query,
                                    onValueChange = { query = it },
                                    placeholder = { Text("Search presets") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                                )
                                LazyRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
                                    items(present) { c ->
                                        FilterChip(
                                            selected = category == c,
                                            onClick = { category = c },
                                            label = { Text(c) },
                                            modifier = Modifier.padding(horizontal = 4.dp),
                                        )
                                    }
                                }
                                Text(
                                    "${filtered.size} presets",
                                    color = Color(0xFF8AA0A8),
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                                )
                            }
                            items(filtered) { e ->
                                val selected = e.userState == null &&
                                    e.bank == currentBank && e.index == currentPreset.intValue
                                val fav = libId(e) in favorites
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(
                                        modifier = Modifier.weight(1f)
                                            .clickable { selectFromLibrary(e); closing() }
                                            .padding(horizontal = 8.dp, vertical = 8.dp),
                                    ) {
                                        Text(
                                            e.name,
                                            color = if (selected) Color(0xFFE0A43B) else Color(0xFFE7EEF0),
                                            fontSize = 16.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            "${e.bankName}  ·  ${e.cats.joinToString(", ")}",
                                            color = Color(0xFF8AA0A8),
                                            fontSize = 11.sp,
                                        )
                                    }
                                    if (e.userState != null) {
                                        Text(
                                            "✕", color = Color(0xFF8AA0A8), fontSize = 17.sp,
                                            modifier = Modifier
                                                .clickable {
                                                    userPresets.removeAll { it.first == e.name }
                                                    favorites.remove(libId(e))
                                                    persistPrefs()
                                                }
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                        )
                                    }
                                    Text(
                                        if (fav) "★" else "☆",
                                        color = if (fav) Color(0xFFE0A43B) else Color(0xFF6E6145),
                                        fontSize = 20.sp,
                                        modifier = Modifier
                                            .clickable {
                                                if (fav) favorites.remove(libId(e)) else favorites.add(libId(e))
                                                persistPrefs()
                                            }
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Save the current sound as a named user preset (persisted, shows up in
        // the library under "My Presets" and can be favourited).
        if (showSaveDialog) {
            AlertDialog(
                onDismissRequest = { showSaveDialog = false },
                containerColor = Color(0xFF1B2226),
                title = { Text("Save preset", color = Color(0xFFE7EEF0)) },
                text = {
                    OutlinedTextField(
                        value = saveName,
                        onValueChange = { saveName = it },
                        placeholder = { Text("Preset name") },
                        singleLine = true,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val name = saveName.trim().ifBlank { "My Sound" }
                        val state = AmsynthEngine.nativeGetState()
                        if (state.isNotBlank()) {
                            userPresets.removeAll { it.first == name }
                            userPresets.add(name to state)
                            soundName = name
                            persistPrefs()
                        }
                        showSaveDialog = false
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { showSaveDialog = false }) { Text("Cancel") }
                },
            )
        }
    }
}

/**
 * The compact octave picker + a playable keyboard, dropped into the console's
 * vertical dead space so the full panel is also playable on tall/square screens.
 */
@Composable
private fun ConsoleKeys(octave: MutableIntState, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "OCT −", color = Color(0xFFE0A43B), fontSize = 14.sp,
                modifier = Modifier.clickable { if (octave.intValue > -3) octave.intValue-- }
                    .padding(vertical = 6.dp, horizontal = 6.dp),
            )
            Text(
                "C${3 + octave.intValue}", color = Color(0xFFB9C6CC), fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Text(
                "OCT +", color = Color(0xFFE0A43B), fontSize = 14.sp,
                modifier = Modifier.clickable { if (octave.intValue < 3) octave.intValue++ }
                    .padding(vertical = 6.dp, horizontal = 6.dp),
            )
        }
        Keyboard(
            baseNote = 48 + octave.intValue * 12,
            modifier = Modifier.fillMaxWidth().weight(1f)
                .padding(horizontal = 10.dp).padding(bottom = 6.dp),
        )
    }
}

/**
 * A compact two-octave (C3..C5) keyboard for picking a note by tapping — used
 * to choose the tuning root (tonic). Highlights the [selected] note; taps call
 * [onSelect] with the tapped MIDI note. It does not play sound.
 */
@Composable
private fun NotePicker(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val base = 48 // C3
    val whiteOffsets = intArrayOf(0, 2, 4, 5, 7, 9, 11)
    val whiteNotes = remember {
        buildList {
            for (o in 0..1) for (w in whiteOffsets) add(base + 12 * o + w)
            add(base + 24)
        }
    }
    val blackKeys = remember {
        val afterWhite = intArrayOf(0, 1, 3, 4, 5)
        val noteOffset = intArrayOf(1, 3, 6, 8, 10)
        buildList {
            for (o in 0..1) for (k in afterWhite.indices)
                add(Pair(o * 7 + afterWhite[k], base + 12 * o + noteOffset[k]))
        }
    }
    val whiteCount = whiteNotes.size

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
        return whiteNotes[(pos.x / ww).toInt().coerceIn(0, whiteCount - 1)]
    }

    Canvas(
        modifier = modifier.pointerInput(Unit) {
            detectTapGestures { off -> onSelect(hitTest(off, Size(size.width.toFloat(), size.height.toFloat()))) }
        },
    ) {
        val ww = size.width / whiteCount
        val bw = ww * 0.62f
        val bh = size.height * 0.6f
        for (i in 0 until whiteCount) {
            drawRect(
                color = if (whiteNotes[i] == selected) Color(0xFFE0A43B) else Color(0xFFEEF3F4),
                topLeft = Offset(i * ww, 0f),
                size = Size(ww - 1.5f, size.height),
            )
        }
        for ((whiteIdx, note) in blackKeys) {
            val cx = (whiteIdx + 1) * ww
            drawRect(
                color = if (note == selected) Color(0xFFB9791F) else Color(0xFF1B2226),
                topLeft = Offset(cx - bw / 2, 0f),
                size = Size(bw, bh),
            )
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
