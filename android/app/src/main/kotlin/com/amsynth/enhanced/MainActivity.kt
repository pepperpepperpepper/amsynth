package com.amsynth.enhanced

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A minimal playable front-end: an on-screen keyboard plus a slider for every
 * engine parameter. The DSP and audio output are entirely native (Oboe); this
 * activity only sends control events.
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

    // Run audio only while the activity is in the foreground.
    override fun onResume() {
        super.onResume()
        AmsynthEngine.nativeCreate(0) // 0 = let Oboe choose the device rate
    }

    override fun onPause() {
        AmsynthEngine.nativeAllNotesOff()
        AmsynthEngine.nativeDestroy()
        super.onPause()
    }
}

@Composable
private fun SynthScreen(params: List<AmsynthEngine.ParamInfo>) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF222B30))
            .padding(12.dp)
    ) {
        Text(
            "AMsynth enhanced",
            color = Color(0xFFE0A43B),
            fontSize = 20.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Parameter sliders take the available space above the keyboard.
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(params) { p -> ParamSlider(p) }
        }

        Keyboard(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .padding(top = 8.dp)
        )
    }
}

@Composable
private fun ParamSlider(p: AmsynthEngine.ParamInfo) {
    var value by remember { mutableStateOf(p.default) }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            "${p.name.replace('_', ' ')}  ${"%.2f".format(value)}",
            color = Color(0xFFE7EEF0),
            fontSize = 12.sp,
        )
        Slider(
            value = value,
            onValueChange = {
                value = it
                AmsynthEngine.nativeSetParameter(p.index, it)
            },
            valueRange = p.min..p.max,
        )
    }
}

/** Two octaves of touchable keys (C3..B4). Each key is its own pointer target,
 *  so several can sound at once (the platform dispatches one pointer per key). */
@Composable
private fun Keyboard(modifier: Modifier = Modifier) {
    val low = 48 // C3
    val high = 72 // C5
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        for (note in low..high) {
            val white = isWhite(note)
            Box(
                modifier = Modifier
                    .weight(if (white) 1.6f else 1f)
                    .fillMaxSize()
                    .background(if (white) Color(0xFFEEF3F4) else Color(0xFF1B2226))
                    .pointerInput(note) {
                        detectTapGestures(onPress = {
                            AmsynthEngine.nativeNoteOn(note, 100)
                            tryAwaitRelease()
                            AmsynthEngine.nativeNoteOff(note)
                        })
                    },
                contentAlignment = Alignment.BottomCenter,
            ) {
                if (note % 12 == 0) {
                    Text(
                        "C${note / 12 - 1}",
                        color = Color(0xFF8AA0A8),
                        fontSize = 10.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
        }
    }
}

private fun isWhite(note: Int): Boolean = when (note % 12) {
    0, 2, 4, 5, 7, 9, 11 -> true
    else -> false
}
