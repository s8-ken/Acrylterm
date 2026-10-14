package com.shibaken.androidterminal.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Unsent composer text per pane, kept in memory for the life of the process. */
object ComposerDrafts {
    private val drafts = HashMap<String, String>()
    fun get(key: String): String = drafts[key] ?: ""
    fun set(key: String, value: String) { if (value.isEmpty()) drafts.remove(key) else drafts[key] = value }
}

/**
 * Bottom sheet with a normal multi-line text field (full IME: Japanese conversion, voice input, …) for
 * writing a message to the pane's agent. Sending types the text and presses Enter in that pane.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposerSheet(draftKey: String, onSend: suspend (String) -> Boolean, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    var text by remember(draftKey) { mutableStateOf(ComposerDrafts.get(draftKey)) }
    val latest = rememberUpdatedState(text)
    DisposableEffect(draftKey) { onDispose { ComposerDrafts.set(draftKey, latest.value) } }
    val focus = remember { FocusRequester() }
    val context = LocalContext.current
    val density = LocalDensity.current
    val prefs = remember { context.getSharedPreferences("composer", Context.MODE_PRIVATE) }
    val minH = 120f
    val screenH = LocalConfiguration.current.screenHeightDp
    val imeDp = with(density) { WindowInsets.ime.getBottom(density).toDp().value }
    val topDp = with(density) { WindowInsets.statusBars.getTop(density).toDp().value }
    val maxH = ((screenH - imeDp - topDp - 48f - 72f) * 0.85f).coerceAtLeast(minH)
    var rawH by rememberSaveable { mutableFloatStateOf(prefs.getFloat("height", minH)) }
    val fieldH = rawH.coerceIn(minH, maxH)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        dragHandle = {
            // Own handle (consumes its drags so the sheet does not move): window-space deltas, because the handle itself moves while the field grows.
            var originY by remember { mutableFloatStateOf(0f) }
            Box(
                Modifier.fillMaxWidth().height(48.dp)
                    .onGloballyPositioned { originY = it.positionInWindow().y }
                    .semantics { contentDescription = "入力欄の高さを変更" }
                    .pointerInput(maxH) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            down.consume()
                            var last = originY + down.position.y
                            rawH = rawH.coerceIn(minH, maxH)
                            while (true) {
                                val ev = awaitPointerEvent()
                                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!c.pressed) break
                                val y = originY + c.position.y
                                val dy = y - last
                                last = y
                                if (dy != 0f) {
                                    c.consume()
                                    rawH = (rawH - dy / density.density).coerceAtMost(maxH)
                                }
                            }
                            if (minH - rawH > 80f) onDismiss()
                            else {
                                rawH = rawH.coerceIn(minH, maxH)
                                prefs.edit().putFloat("height", rawH).apply()
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(CyanVeil.Muted))
            }
        },
    ) {
        // Inside the sheet content so the requester is attached by then.
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        Row(
            Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            TextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f).height(fieldH.dp).focusRequester(focus),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
            )
            IconButton(
                enabled = text.isNotBlank() && !sending,
                onClick = {
                    val t = text
                    sending = true
                    scope.launch {
                        // Keep the draft if the send failed (the caller shows the error).
                        val ok = try { onSend(t) } finally { sending = false }
                        if (ok) {
                            text = ""
                            onDismiss()
                        }
                    }
                },
            ) { Icon(Icons.AutoMirrored.Filled.Send, "送信") }
        }
    }
}
