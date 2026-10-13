package com.shibaken.androidterminal.ui

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
    ModalBottomSheet(onDismissRequest = onDismiss) {
        // Inside the sheet content so the requester is attached by then.
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        Row(
            Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            TextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f).focusRequester(focus),
                minLines = 2,
                maxLines = 6,
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
