package com.shibaken.androidterminal.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.shibaken.androidterminal.R
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shibaken.androidterminal.terminal.TerminalCanvasView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keys that soft keyboards lack. Ctrl/Alt are sticky: tap once and they apply to the next key.
 * "^B" (legacy TUI mode only, [showPrefixKey]) sends the multiplexer prefix (Ctrl+b).
 */
@Composable
fun ExtraKeysBar(
    view: TerminalCanvasView,
    ctrlOn: Boolean,
    altOn: Boolean,
    onCtrl: () -> Unit,
    onAlt: () -> Unit,
    onKeyboard: () -> Unit,
    showPrefixKey: Boolean = true,
    extKeys: List<String> = emptyList(),
    onExtKey: (Int) -> Unit = {},
) {
    Column {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Pinned: always reachable even when the key row is scrolled.
        Key("⌨", desc = "キーボードの表示/非表示", onTap = onKeyboard)
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Key("ESC") { view.sendKey(KeyEvent.KEYCODE_ESCAPE) }
            Key("TAB") { view.sendKey(KeyEvent.KEYCODE_TAB) }
            Key("CTRL", active = ctrlOn, onTap = onCtrl)
            Key("ALT", active = altOn, onTap = onAlt)
            if (showPrefixKey) Key("^B", accent = true, desc = "プレフィックス Ctrl+B") { view.session?.sendText("\u0002") }
            RepeatKey("←", "左矢印") { view.sendKey(KeyEvent.KEYCODE_DPAD_LEFT) }
            RepeatKey("↓", "下矢印") { view.sendKey(KeyEvent.KEYCODE_DPAD_DOWN) }
            RepeatKey("↑", "上矢印") { view.sendKey(KeyEvent.KEYCODE_DPAD_UP) }
            RepeatKey("→", "右矢印") { view.sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
            Key("|", desc = "縦線") { view.session?.sendText("|") }
            Key("/", desc = "スラッシュ") { view.session?.sendText("/") }
            Key("-", desc = "ハイフン") { view.session?.sendText("-") }
            Key("~", desc = "チルダ") { view.session?.sendText("~") }
            Key("HOME") { view.sendKey(KeyEvent.KEYCODE_MOVE_HOME) }
            Key("END") { view.sendKey(KeyEvent.KEYCODE_MOVE_END) }
            Key("PGUP") { view.sendKey(KeyEvent.KEYCODE_PAGE_UP) }
            Key("PGDN") { view.sendKey(KeyEvent.KEYCODE_PAGE_DOWN) }
            extKeys.forEachIndexed { i, label -> Key(label, accent = true) { onExtKey(i) } }
        }
    }
    }
}

@Composable
private fun KeyBox(label: String, desc: String?, active: Boolean, accent: Boolean, modifier: Modifier) {
    val bg = when {
        active -> MaterialTheme.colorScheme.primary
        accent -> MaterialTheme.colorScheme.surfaceContainerHighest
        else -> MaterialTheme.colorScheme.surface
    }
    val fg = when {
        active -> MaterialTheme.colorScheme.onPrimary
        accent -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(
        modifier
            .semantics { desc?.let { contentDescription = it } }
            .height(48.dp)
            .widthIn(min = 48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .border(
                1.dp,
                if (active) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent,
                RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (label == "⌨") VeilIcon(R.drawable.veil_keyboard, null, CyanVeil.Cyan)
        else Text(label, color = fg, fontFamily = FontFamily.Monospace, fontSize = 12.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun Key(label: String, active: Boolean = false, accent: Boolean = false, desc: String? = null, onTap: () -> Unit) {
    KeyBox(label, desc, active, accent, Modifier.clickable(onClick = onTap))
}

/** Repeats while held (arrows). */
@Composable
private fun RepeatKey(label: String, desc: String, onFire: () -> Unit) {
    val scope = rememberCoroutineScope()
    KeyBox(
        label, desc, false, false,
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
