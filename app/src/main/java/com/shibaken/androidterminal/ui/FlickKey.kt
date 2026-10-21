package com.shibaken.androidterminal.ui

import com.shibaken.androidterminal.R
import androidx.compose.ui.res.stringResource
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.shibaken.androidterminal.terminal.TerminalCanvasView
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val FlickDirection.keyCode: Int get() = when (this) {
    FlickDirection.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
    FlickDirection.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
    FlickDirection.UP -> KeyEvent.KEYCODE_DPAD_UP
    FlickDirection.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
}

@Composable
internal fun FlickKey(view: TerminalCanvasView, modifier: Modifier = Modifier) {
    val hapticView = LocalView.current
    var direction by remember(view) { mutableStateOf<FlickDirection?>(null) }
    val shape = RoundedCornerShape(12.dp)
    val active = direction != null
    val keyDesc = stringResource(R.string.term_flick_key)
    val keyState = stringResource(R.string.term_flick_key_state)
    val leftLabel = stringResource(R.string.term_arrow_left)
    val downLabel = stringResource(R.string.term_arrow_down)
    val upLabel = stringResource(R.string.term_arrow_up)
    val rightLabel = stringResource(R.string.term_arrow_right)
    fun fire(keyCode: Int) {
        hapticView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        view.sendKey(keyCode)
    }
    Box(
        modifier.height(48.dp).clip(shape)
            .background(if (active) CyanVeil.Cyan else CyanVeil.Surface)
            .border(1.dp, if (active) CyanVeil.Cyan else CyanVeil.Border, shape)
            .semantics {
                role = Role.Button
                contentDescription = keyDesc
                stateDescription = keyState
                onClick("Enter") { fire(KeyEvent.KEYCODE_ENTER); true }
                customActions = listOf(
                    CustomAccessibilityAction(leftLabel) { fire(KeyEvent.KEYCODE_DPAD_LEFT); true },
                    CustomAccessibilityAction(downLabel) { fire(KeyEvent.KEYCODE_DPAD_DOWN); true },
                    CustomAccessibilityAction(upLabel) { fire(KeyEvent.KEYCODE_DPAD_UP); true },
                    CustomAccessibilityAction(rightLabel) { fire(KeyEvent.KEYCODE_DPAD_RIGHT); true },
                )
            }
            .pointerInput(view, hapticView) {
                val threshold = 12.dp.toPx()
                coroutineScope {
                    val gestures = this
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        val gesture = FlickGesture(threshold)
                        var repeating: Job? = null
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                // A second finger or a parent consuming the gesture cancels it.
                                if (event.changes.any { it.id != down.id && it.pressed }) break
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.isConsumed) break
                                val delta = change.position - down.position
                                val next = gesture.direction(delta.x, delta.y)
                                if (next != direction) {
                                    repeating?.cancel()
                                    direction = next
                                    if (next != null) {
                                        fire(next.keyCode)
                                        repeating = gestures.launch {
                                            delay(400)
                                            while (true) {
                                                view.sendKey(next.keyCode)
                                                delay(60)
                                            }
                                        }
                                    }
                                }
                                change.consume()
                                if (!change.pressed) {
                                    if (!gesture.hasFlicked) fire(KeyEvent.KEYCODE_ENTER)
                                    break
                                }
                            }
                        } finally {
                            repeating?.cancel()
                            direction = null
                        }
                    }
                }
            },
    ) {
        fun tint(arrow: FlickDirection) = when {
            !active -> CyanVeil.Muted
            direction == arrow -> CyanVeil.Ink
            else -> CyanVeil.Ink.copy(alpha = .3f)
        }
        Box(Modifier.fillMaxSize()) {
            Icon(Icons.Filled.KeyboardArrowUp, null, Modifier.align(Alignment.TopCenter).size(14.dp), tint = tint(FlickDirection.UP))
            Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.align(Alignment.BottomCenter).size(14.dp), tint = tint(FlickDirection.DOWN))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, Modifier.align(Alignment.CenterStart).size(14.dp), tint = tint(FlickDirection.LEFT))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.align(Alignment.CenterEnd).size(14.dp), tint = tint(FlickDirection.RIGHT))
            Icon(Icons.AutoMirrored.Filled.KeyboardReturn, null, Modifier.align(Alignment.Center).size(18.dp),
                tint = if (active) CyanVeil.Ink else CyanVeil.Cyan)
        }
    }
}
