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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    showMoreKeys: Boolean = false,
    onDismissMoreKeys: () -> Unit = {},
    showPrefixKey: Boolean = true,
    extKeys: List<String> = emptyList(),
    onExtKey: (Int) -> Unit = {},
    showExtCommands: Boolean = false,
    onExtCommands: () -> Unit = {},
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
    if (showMoreKeys) MoreKeysDialog(view, showPrefixKey, extKeys, onExtKey, showExtCommands, onExtCommands, onDismissMoreKeys)
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

@Composable
private fun MoreKeysDialog(
    view: TerminalCanvasView,
    showPrefixKey: Boolean,
    extKeys: List<String>,
    onExtKey: (Int) -> Unit,
    showExtCommands: Boolean,
    onExtCommands: () -> Unit,
    onDismiss: () -> Unit,
) {
    val hapticView = LocalView.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("その他のキー") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val specialKeys = listOf(
                    Triple("←", "左矢印", KeyEvent.KEYCODE_DPAD_LEFT),
                    Triple("↓", "下矢印", KeyEvent.KEYCODE_DPAD_DOWN),
                    Triple("↑", "上矢印", KeyEvent.KEYCODE_DPAD_UP),
                    Triple("→", "右矢印", KeyEvent.KEYCODE_DPAD_RIGHT),
                    Triple("HOME", "HOME", KeyEvent.KEYCODE_MOVE_HOME),
                    Triple("END", "END", KeyEvent.KEYCODE_MOVE_END),
                    Triple("PGUP", "PGUP", KeyEvent.KEYCODE_PAGE_UP),
                    Triple("PGDN", "PGDN", KeyEvent.KEYCODE_PAGE_DOWN),
                )
                specialKeys.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { (label, desc, code) ->
                            Key(label, desc = desc, modifier = Modifier.weight(1f)) { view.sendKey(code) }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("|", "/", "-", "~").forEach { symbol ->
                        Key(symbol, modifier = Modifier.weight(1f)) { view.commitText(symbol) }
                    }
                }
                if (showPrefixKey) Key("^B", accent = true, desc = "プレフィックス Ctrl+B") { view.session?.sendText("\u0002") }
                extKeys.withIndex().chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { (index, label) ->
                            Key(label, accent = true, desc = "拡張キー: $label", modifier = Modifier.weight(1f)) { onExtKey(index) }
                        }
                    }
                }
                if (showExtCommands) TextButton(onClick = {
                    hapticView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onDismiss()
                    onExtCommands()
                }) { Text("拡張コマンド") }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("閉じる") } },
    )
}
