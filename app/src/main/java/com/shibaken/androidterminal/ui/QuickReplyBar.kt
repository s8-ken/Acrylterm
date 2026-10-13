package com.shibaken.androidterminal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.sp
import com.shibaken.androidterminal.R
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.shibaken.androidterminal.ext.ExtEngine
import com.shibaken.androidterminal.herdr.HerdrController
import com.shibaken.androidterminal.session.PaneTerminal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

val QuickReplyBarHeight = 88.dp

/**
 * Chips docked at the bottom of a pane whose agent is waiting for input. Only composed while the
 * pane is BLOCKED, so they disappear when the status changes. Choices come from the pane's last lines.
 * Sits below the terminal (the pane shrinks by [QuickReplyBarHeight]) so it never covers the prompt.
 */
@Composable
fun QuickReplyBar(paneId: String, terminal: PaneTerminal?, herdr: HerdrController, ext: ExtEngine?, onCompose: () -> Unit, modifier: Modifier = Modifier) {
    var choices by remember(paneId) { mutableStateOf(QuickReplies.fallback()) }
    LaunchedEffect(paneId, terminal) {
        suspend fun refresh() {
            try {
                val text = herdr.readPane(paneId, 15)
                val custom = ext?.matchQuickReply(text)
                choices = if (custom != null) {
                    custom.map { QuickChoice(QuickChoice.Kind.OPTION, it.label, it.label, text = it.text, keys = it.keys) } + QuickReplies.ESC
                } else {
                    QuickReplies.parse(text)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // keep what we have
            }
        }
        if (terminal == null) {
            delay(300); refresh()
        } else {
            // Re-read ~300ms after the screen settles, so chips follow consecutive prompts.
            terminal.screenRevision.collectLatest { delay(300); refresh() }
        }
    }
    Column(modifier.padding(4.dp).clip(RoundedCornerShape(12.dp)).background(CyanVeil.Raised).padding(horizontal = 8.dp, vertical = 6.dp)) {
    Text("入力待ち · エージェント", fontSize = 10.sp, color = CyanVeil.Amber)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        choices.forEach { c ->
            Chip(c.description, onClick = { herdr.sendInput(paneId, c.text, c.keys) }) {
                when (c.kind) {
                    QuickChoice.Kind.ENTER -> Icon(Icons.AutoMirrored.Filled.KeyboardReturn, null, tint = chipFg())
                    QuickChoice.Kind.ESC -> Icon(Icons.Filled.Close, null, tint = chipFg())
                    QuickChoice.Kind.OPTION -> Text(c.label, color = chipFg(), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }
        }
        Chip("メッセージを作成", onClick = onCompose) { VeilIcon(R.drawable.veil_edit, null, chipFg()) }
    }
    }
}

@Composable
private fun chipFg() = MaterialTheme.colorScheme.background

@Composable
private fun Chip(description: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .height(48.dp)
            .widthIn(min = 48.dp)
            .clip(CircleShape)
            .background(CyanVeil.Cyan)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description }
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}
