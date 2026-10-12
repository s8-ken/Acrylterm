package com.shibaken.androidterminal.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shibaken.androidterminal.terminal.TerminalCanvasView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keys that soft keyboards lack. Ctrl/Alt are sticky: tap once and they apply to the next key.
 * "^B" sends herdr's prefix (Ctrl+b).
 */
@Composable
fun ExtraKeysBar(
    view: TerminalCanvasView,
    ctrlOn: Boolean,
    altOn: Boolean,
    onCtrl: () -> Unit,
    onAlt: () -> Unit,
    onKeyboard: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Key("ESC") { view.sendKey(KeyEvent.KEYCODE_ESCAPE) }
        Key("TAB") { view.sendKey(KeyEvent.KEYCODE_TAB) }
        Key("CTRL", active = ctrlOn, onTap = onCtrl)
        Key("ALT", active = altOn, onTap = onAlt)
        Key("^B", accent = true) { view.session?.sendText("\u0002") }
        RepeatKey("←") { view.sendKey(KeyEvent.KEYCODE_DPAD_LEFT) }
        RepeatKey("↓") { view.sendKey(KeyEvent.KEYCODE_DPAD_DOWN) }
        RepeatKey("↑") { view.sendKey(KeyEvent.KEYCODE_DPAD_UP) }
        RepeatKey("→") { view.sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
        Key("|") { view.session?.sendText("|") }
        Key("/") { view.session?.sendText("/") }
        Key("-") { view.session?.sendText("-") }
        Key("~") { view.session?.sendText("~") }
        Key("HOME") { view.sendKey(KeyEvent.KEYCODE_MOVE_HOME) }
        Key("END") { view.sendKey(KeyEvent.KEYCODE_MOVE_END) }
        Key("PGUP") { view.sendKey(KeyEvent.KEYCODE_PAGE_UP) }
        Key("PGDN") { view.sendKey(KeyEvent.KEYCODE_PAGE_DOWN) }
        Key("⌨", onTap = onKeyboard)
    }
}

@Composable
private fun KeyBox(label: String, active: Boolean, accent: Boolean, modifier: Modifier) {
    val bg = when {
        active -> MaterialTheme.colorScheme.primary
        accent -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val fg = when {
        active -> MaterialTheme.colorScheme.onPrimary
        accent -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier
            .height(36.dp)
            .widthIn(min = 40.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun Key(label: String, active: Boolean = false, accent: Boolean = false, onTap: () -> Unit) {
    KeyBox(label, active, accent, Modifier.pointerInput(onTap) { detectTapGestures(onTap = { onTap() }) })
}

/** Repeats while held (arrows). */
@Composable
private fun RepeatKey(label: String, onFire: () -> Unit) {
    val scope = rememberCoroutineScope()
    KeyBox(
        label, false, false,
        Modifier.pointerInput(onFire) {
            detectTapGestures(onPress = {
                onFire()
                val job = scope.launch {
                    delay(400)
                    while (true) {
                        onFire()
                        delay(50)
                    }
                }
                tryAwaitRelease()
                job.cancel()
            })
        },
    )
}
