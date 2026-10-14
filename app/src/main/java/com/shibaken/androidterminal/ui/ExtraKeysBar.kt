package com.shibaken.androidterminal.ui

import android.view.KeyEvent
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.shibaken.androidterminal.R
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shibaken.androidterminal.terminal.TerminalCanvasView

/**
 * Keys that soft keyboards lack. Ctrl/Alt apply once on tap, hold on double tap, and release on tap.
 * A second scrollable row carries HOME/END/PGUP/PGDN, symbols, macros (extension keys + commands) and "＋".
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
    ctrlHeld: Boolean = false,
    altHeld: Boolean = false,
    showPrefixKey: Boolean = true,
    extKeys: List<String> = emptyList(),
    onExtKey: (Int) -> Unit = {},
    extCommands: List<String> = emptyList(),
    onExtCommand: (String) -> Unit = {},
    showExtCreate: Boolean = false,
    onExtCreate: () -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .semantics { contentDescription = "補助キー" },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Key("⌨", desc = "キーボードの表示/非表示", modifier = Modifier.weight(1f), onTap = onKeyboard)
        Key("ESC", modifier = Modifier.weight(1f)) { view.sendKey(KeyEvent.KEYCODE_ESCAPE) }
        Key("TAB", modifier = Modifier.weight(1f)) { view.sendKey(KeyEvent.KEYCODE_TAB) }
        ModifierKey("CTRL", active = ctrlOn, held = ctrlHeld, modifier = Modifier.weight(1f), onTap = onCtrl)
        ModifierKey("ALT", active = altOn, held = altHeld, modifier = Modifier.weight(1f), onTap = onAlt)
        FlickKey(view, Modifier.weight(1f))
    }
    MiniKeysRow(view, showPrefixKey, extKeys, onExtKey, extCommands, onExtCommand, showExtCreate, onExtCreate)
}

/** Second, horizontally scrollable row of smaller keys that don't fit in the main bar. */
@Composable
private fun MiniKeysRow(
    view: TerminalCanvasView,
    showPrefixKey: Boolean,
    extKeys: List<String>,
    onExtKey: (Int) -> Unit,
    extCommands: List<String>,
    onExtCommand: (String) -> Unit,
    showExtCreate: Boolean,
    onExtCreate: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(bottom = 2.dp)
            .semantics { contentDescription = "その他のキー" },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(
            "HOME" to KeyEvent.KEYCODE_MOVE_HOME,
            "END" to KeyEvent.KEYCODE_MOVE_END,
            "PGUP" to KeyEvent.KEYCODE_PAGE_UP,
            "PGDN" to KeyEvent.KEYCODE_PAGE_DOWN,
        ).forEach { (label, code) -> MiniKey(label) { view.sendKey(code) } }
        listOf("|", "/", "-", "~").forEach { symbol -> MiniKey(symbol) { view.commitText(symbol) } }
        if (showPrefixKey) MiniKey("^B", accent = true, desc = "プレフィックス Ctrl+B") { view.session?.sendText("\u0002") }
        // マクロ = 拡張キー(keys.add)+ 拡張コマンド(command)。ユーザーには区別しない。
        extKeys.forEachIndexed { index, label -> MiniKey("⚡$label", accent = true, desc = "マクロ: $label") { onExtKey(index) } }
        extCommands.forEach { name -> MiniKey("⚡$name", accent = true, desc = "マクロ: $name") { onExtCommand(name) } }
        if (showExtCreate) MiniKey(if (extKeys.isEmpty() && extCommands.isEmpty()) "＋ マクロを作る" else "＋", accent = true,
            desc = "拡張を作る", onTap = onExtCreate)
    }
}

@Composable
private fun MiniKey(label: String, accent: Boolean = false, desc: String? = null, onTap: () -> Unit) {
    val hapticView = LocalView.current
    Box(
        Modifier
            .semantics { desc?.let { contentDescription = it } }
            .height(32.dp)
            .widthIn(min = 40.dp, max = 160.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (accent) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surface)
            .clickable(role = Role.Button) {
                hapticView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onTap()
            }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (accent) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun KeyBox(label: String, desc: String?, active: Boolean, accent: Boolean, modifier: Modifier, content: (@Composable () -> Unit)? = null) {
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
            .widthIn(min = 44.dp, max = 180.dp)
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
        if (content != null) content()
        else if (label == "⌨") VeilIcon(R.drawable.veil_keyboard, null, CyanVeil.Cyan)
        else Text(label, color = fg, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun Key(label: String, active: Boolean = false, accent: Boolean = false, desc: String? = null, modifier: Modifier = Modifier, onTap: () -> Unit) {
    val hapticView = LocalView.current
    KeyBox(label, desc, active, accent, modifier.clickable(role = Role.Button) {
        hapticView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        onTap()
    })
}

@Composable
private fun ModifierKey(label: String, active: Boolean, held: Boolean, modifier: Modifier = Modifier, onTap: () -> Unit) {
    val hapticView = LocalView.current
    val foreground = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    KeyBox(label, label, active, false,
        modifier.semantics {
            stateDescription = when {
                held -> "HOLD、タップで解除"
                active -> "次の1キーに適用、素早くもう1回タップでHOLD"
                else -> "オフ、2回タップでHOLD"
            }
        }.clickable(role = Role.Button) {
            hapticView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onTap()
        },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = foreground, fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
            if (held) Text("HOLD", color = foreground, fontSize = 9.sp, lineHeight = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}
