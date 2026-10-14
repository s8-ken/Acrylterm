package com.shibaken.androidterminal.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
 * Inline composer docked on top of the IME in the screen's own window (no sheet, no scrim), so keyboard and
 * composer read as one panel. It overlays the terminal instead of resizing it (the caller freezes the terminal's
 * IME insets). A multi-line text field (full IME: Japanese conversion, voice input, ...) for writing a message to
 * the pane's agent; sending types the text and presses Enter in that pane. Back, a tap outside, dragging the handle
 * down, or the keyboard going away (after it was fully shown once) all close the panel together with the keyboard.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ComposerPanel(draftKey: String, onSend: suspend (String) -> Boolean, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    var text by remember(draftKey) { mutableStateOf(ComposerDrafts.get(draftKey)) }
    val latest = rememberUpdatedState(text)
    DisposableEffect(draftKey) { onDispose { ComposerDrafts.set(draftKey, latest.value) } }
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val prefs = remember { context.getSharedPreferences("composer", Context.MODE_PRIVATE) }
    val onDismissNow = rememberUpdatedState(onDismiss)
    val close = { focusManager.clearFocus(); keyboard?.hide(); onDismissNow.value() }
    val minH = 120f
    val screenH = LocalConfiguration.current.screenHeightDp
    val imeDp = with(density) { WindowInsets.ime.getBottom(density).toDp().value }
    val topDp = with(density) { WindowInsets.statusBars.getTop(density).toDp().value }
    val maxH = ((screenH - imeDp - topDp - 48f - 72f) * 0.85f).coerceAtLeast(minH)
    var rawH by rememberSaveable { mutableFloatStateOf(prefs.getFloat("height", minH)) }
    val fieldH = rawH.coerceIn(minH, maxH)

    BackHandler(onBack = close)
    // The keyboard is part of the panel: once it has been fully up, hiding it (swipe down) closes the panel too.
    val imeVisible = WindowInsets.isImeVisible
    var imeArmed by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeVisible) { kotlinx.coroutines.delay(500); imeArmed = true } else if (imeArmed) close()
    }
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
        keyboard?.show()
    }

    Box(Modifier.fillMaxSize()) {
        // Outside tap: closes the panel and is swallowed here, so the terminal underneath never sees it.
        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { close() }) })
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(CyanVeil.Surface)
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            // Own handle (consumes its drags): window-space deltas, because the handle itself moves while the field grows.
            var originY by remember { mutableFloatStateOf(0f) }
            Box(
                Modifier.fillMaxWidth().height(32.dp)
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
                            if (minH - rawH > 80f) close()
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
            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
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
                                close()
                            }
                        }
                    },
                ) { Icon(Icons.AutoMirrored.Filled.Send, "送信") }
            }
        }
    }
}
