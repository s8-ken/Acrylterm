package com.shibaken.androidterminal.terminal

import android.text.Editable
import android.text.Selection
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputMethodManager

/**
 * InputConnection that lets IMEs compose normally (Japanese 未確定文字列, Korean, etc.) and only
 * forwards committed text to the terminal.
 *
 * The backing Editable only ever holds the text the IME is currently composing. When the IME
 * commits (commitText / finishComposingText) the whole Editable is sent and cleared, so from
 * the IME's point of view the field is empty between compositions. Backspace on an empty
 * field is turned into DEL for the remote side.
 *
 * Every change to the Editable is reported back with InputMethodManager.updateSelection —
 * Gboard's Japanese mode relies on it to keep its composing model in sync.
 */
class TerminalInputConnection(private val view: TerminalCanvasView) : BaseInputConnection(view, true) {

    private val imm: InputMethodManager = view.context.getSystemService(InputMethodManager::class.java)

    private fun composing(): String {
        val e = editable ?: return ""
        val start = BaseInputConnection.getComposingSpanStart(e)
        val end = BaseInputConnection.getComposingSpanEnd(e)
        return if (start in 0..end && end <= e.length) e.subSequence(start, end).toString() else ""
    }

    /** Redraw the composing overlay and tell the IME where selection/composition are now. */
    private fun syncState() {
        view.composingText = composing()
        val e = editable ?: return
        val selStart = Selection.getSelectionStart(e).coerceAtLeast(0)
        val selEnd = Selection.getSelectionEnd(e).coerceAtLeast(0)
        val cStart = BaseInputConnection.getComposingSpanStart(e)
        val cEnd = BaseInputConnection.getComposingSpanEnd(e)
        imm.updateSelection(view, selStart, selEnd, cStart, cEnd)
    }

    /** Sends everything in the editable and empties it. */
    private fun flush() {
        val e: Editable = editable ?: return
        if (e.isNotEmpty()) {
            val text = e.toString()
            e.clear()
            view.commitText(text)
        }
        view.composingText = ""
        imm.updateSelection(view, 0, 0, -1, -1)
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        super.setComposingText(text ?: "", newCursorPosition)
        syncState()
        return true
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        val r = super.setComposingRegion(start, end)
        syncState()
        return r
    }

    override fun setSelection(start: Int, end: Int): Boolean {
        val r = super.setSelection(start, end)
        syncState()
        return r
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        super.commitText(text ?: "", newCursorPosition)
        flush()
        return true
    }

    /**
     * Commits the current composition (the IME asked for it, e.g. on a confirm action or when
     * input is restarted). Hiding the keyboard goes through [closeConnection] instead, which
     * drops the composition.
     */
    override fun finishComposingText(): Boolean {
        super.finishComposingText()
        flush()
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        val e = editable
        if (e == null || e.isEmpty()) {
            // Nothing local to delete: the user is erasing remote text.
            if (beforeLength > 0) view.sendBackspace(beforeLength)
            if (afterLength > 0) repeat(afterLength) { view.sendKey(KeyEvent.KEYCODE_FORWARD_DEL) }
            return true
        }
        val r = super.deleteSurroundingText(beforeLength, afterLength)
        syncState()
        return r
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        val e = editable
        if (e == null || e.isEmpty()) {
            if (beforeLength > 0) view.sendBackspace(beforeLength)
            if (afterLength > 0) repeat(afterLength) { view.sendKey(KeyEvent.KEYCODE_FORWARD_DEL) }
            return true
        }
        val r = super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
        syncState()
        return r
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        val e = editable
        // While composing, DEL edits the composition locally instead of reaching the remote.
        if (event.keyCode == KeyEvent.KEYCODE_DEL && e != null && e.isNotEmpty()) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val end = Selection.getSelectionEnd(e).let { if (it < 0) e.length else it }
                if (end > 0) {
                    val start = Character.offsetByCodePoints(e, end, -1)
                    e.delete(start, end)
                }
                if (e.isEmpty()) {
                    e.clear()
                    view.composingText = ""
                    imm.updateSelection(view, 0, 0, -1, -1)
                } else {
                    syncState()
                }
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            view.onKeyDown(event.keyCode, event)
        }
        return true
    }

    override fun performEditorAction(actionCode: Int): Boolean {
        flush()
        view.sendKey(KeyEvent.KEYCODE_ENTER)
        return true
    }

    override fun closeConnection() {
        // Drop (don't send) half-composed text when the keyboard goes away.
        editable?.clear()
        view.composingText = ""
        super.closeConnection()
    }
}
