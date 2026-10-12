package com.shibaken.androidterminal.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.shibaken.androidterminal.session.SshTerminalSession
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TextStyle
import com.termux.terminal.WcWidth
import com.termux.view.TerminalRenderer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws a [SshTerminalSession]'s emulator and turns IME / keys / touches into terminal input.
 *
 * Japanese input: unlike Termux's TerminalView (TYPE_NULL), this view advertises a real text
 * field to the IME, so Gboard & co. can compose (未確定文字列). The composing text is drawn at the
 * cursor and only the committed text is sent to the remote side. See [TerminalInputConnection].
 *
 * Touch, when the remote app enabled mouse tracking (herdr does):
 *  - tap            → left click (focus a pane, click a tab, …)
 *  - vertical drag  → mouse wheel
 *  - long-press + drag → left-button drag (resize splits, select text in herdr)
 * Without mouse tracking: drag scrolls the local scrollback (or sends arrow keys in the
 * alternate screen), tap shows the keyboard.
 */
class TerminalCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var session: SshTerminalSession? = null
        set(value) {
            field?.onScreenUpdate = null
            field = value
            value?.onScreenUpdate = { onScreenUpdated() }
            topRow = 0
            updateSize()
            invalidate()
        }

    /** Sticky modifiers driven by the extra-keys bar. Consumed by the next key/char. */
    var modifiers: Modifiers? = null

    /** When true the IME is asked not to predict/convert (good for commands). */
    var directInput: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            imm.restartInput(this)
        }

    /** Text size in sp; changes with pinch. */
    var textSizeSp: Float = 12f
        set(value) {
            val v = value.coerceIn(6f, 32f)
            if (field == v) return
            field = v
            createRenderer()
            updateSize()
            invalidate()
            onTextSizeChanged?.invoke(v)
        }
    var onTextSizeChanged: ((Float) -> Unit)? = null

    private val imm = context.getSystemService(InputMethodManager::class.java)
    private var renderer = TerminalRenderer(spToPx(textSizeSp), Typeface.MONOSPACE)
    private var topRow = 0 // <= 0; negative values scroll into the transcript

    // --- IME composing ---------------------------------------------------------------
    internal var composingText: String = ""
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }
    private val composePaint = Paint().apply { isAntiAlias = true; typeface = Typeface.MONOSPACE }
    private val composeBg = Paint().apply { color = Color.parseColor("#FF2D333B") }
    private val composeUnderline = Paint().apply { color = Color.parseColor("#FF58A6FF"); strokeWidth = 3f }

    init {
        composePaint.textSize = spToPx(textSizeSp).toFloat()
        isFocusable = true
        isFocusableInTouchMode = true
        keepScreenOn = true
    }

    private fun spToPx(sp: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics).roundToInt()

    private fun createRenderer() {
        renderer = TerminalRenderer(spToPx(textSizeSp), Typeface.MONOSPACE)
        composePaint.textSize = spToPx(textSizeSp).toFloat()
    }

    private val cellWidth: Float get() = renderer.fontWidth
    private val cellHeight: Int get() = renderer.fontLineSpacing

    private fun onScreenUpdated() {
        // New output snaps back to the live screen unless the user is reading history.
        val emu = session?.emulator ?: return
        if (topRow < 0) {
            val scrolled = emu.scrollCounter
            topRow = max(-emu.screen.activeTranscriptRows, topRow - scrolled)
        }
        emu.clearScrollCounter()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateSize()
    }

    private fun updateSize() {
        val s = session ?: return
        if (width == 0 || height == 0) return
        val cols = max(4, (width / cellWidth).toInt())
        val rows = max(2, height / cellHeight)
        s.resize(cols, rows, cellWidth.roundToInt(), cellHeight)
    }

    override fun onDraw(canvas: Canvas) {
        val emu = session?.emulator
        if (emu == null) {
            canvas.drawColor(Color.BLACK)
            return
        }
        canvas.drawColor(emu.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND])
        renderer.render(emu, canvas, topRow, -1, -1, -1, -1)
        if (composingText.isNotEmpty() && topRow == 0) drawComposing(canvas, emu)
    }

    /** Draws the IME's uncommitted text over the cursor position, wrapping if needed. */
    private fun drawComposing(canvas: Canvas, emu: TerminalEmulator) {
        composePaint.color = emu.mColors.mCurrentColors[TextStyle.COLOR_INDEX_FOREGROUND]
        val cw = cellWidth
        val ch = cellHeight.toFloat()
        val cols = emu.mColumns
        var col = emu.cursorCol
        var row = emu.cursorRow
        var i = 0
        val text = composingText
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val w = max(1, WcWidth.width(cp))
            if (col + w > cols) { col = 0; row++ }
            val x = col * cw
            val y = row * ch
            canvas.drawRect(x, y, x + w * cw, y + ch, composeBg)
            canvas.drawText(String(Character.toChars(cp)), x, y + ch - composePaint.descent(), composePaint)
            canvas.drawLine(x, y + ch - 1.5f, x + w * cw, y + ch - 1.5f, composeUnderline)
            col += w
            i += Character.charCount(cp)
        }
    }

    // --- keyboard / IME ----------------------------------------------------------------

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = if (directInput) {
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        } else {
            // A normal text field so Japanese IMEs compose; no autocorrect.
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0
        return TerminalInputConnection(this)
    }

    fun showKeyboard() {
        requestFocus()
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideKeyboard() {
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    fun toggleKeyboard() {
        val imeVisible = ViewCompat.getRootWindowInsets(this)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        if (imeVisible) hideKeyboard() else showKeyboard()
    }

    /** Tell the IME the (empty) editor state changed after we consumed its text. */
    internal fun resetImeSelection() {
        imm.updateSelection(this, 0, 0, -1, -1)
    }

    /** Committed text from the IME (or a paste). */
    internal fun commitText(text: CharSequence) {
        val s = session ?: return
        if (text.isEmpty()) return
        scrollToBottom()
        val mods = modifiers
        val str = text.toString().replace("\r\n", "\r").replace('\n', '\r')
        if (mods != null && (mods.ctrl || mods.alt) && str.codePointCount(0, str.length) == 1) {
            sendCodePoint(str.codePointAt(0), mods.ctrl, mods.alt)
            mods.consume()
        } else {
            s.sendText(str)
        }
    }

    private fun sendCodePoint(cp: Int, ctrl: Boolean, alt: Boolean) {
        val s = session ?: return
        var c = cp
        if (ctrl) {
            c = when {
                c in 'a'.code..'z'.code -> c - 'a'.code + 1
                c in 'A'.code..'Z'.code -> c - 'A'.code + 1
                c == ' '.code || c == '2'.code || c == '@'.code -> 0
                c == '['.code || c == '3'.code -> 27
                c == '\\'.code || c == '4'.code -> 28
                c == ']'.code || c == '5'.code -> 29
                c == '^'.code || c == '6'.code -> 30
                c == '_'.code || c == '7'.code || c == '/'.code || c == '-'.code -> 31
                c == '8'.code || c == '?'.code -> 127
                else -> c
            }
        }
        val sb = StringBuilder()
        if (alt) sb.append('\u001b')
        sb.appendCodePoint(c)
        s.sendText(sb.toString())
    }

    /** Sends a special key (arrows, Esc, F-keys, …) honouring terminal modes and modifiers. */
    fun sendKey(keyCode: Int, extraShift: Boolean = false) {
        val s = session ?: return
        val emu = s.emulator
        val mods = modifiers
        var keyMod = 0
        if (mods?.ctrl == true) keyMod = keyMod or KeyHandler.KEYMOD_CTRL
        if (mods?.alt == true) keyMod = keyMod or KeyHandler.KEYMOD_ALT
        if (extraShift) keyMod = keyMod or KeyHandler.KEYMOD_SHIFT
        val code = KeyHandler.getCode(keyCode, keyMod, emu.isCursorKeysApplicationMode, emu.isKeypadApplicationMode)
        if (code != null) {
            scrollToBottom()
            s.sendText(code)
        }
        mods?.consume()
    }

    internal fun sendBackspace(count: Int = 1) {
        repeat(count) { sendKey(KeyEvent.KEYCODE_DEL) }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val s = session ?: return super.onKeyDown(keyCode, event)
        if (keyCode == KeyEvent.KEYCODE_BACK) return super.onKeyDown(keyCode, event)
        if (event.isSystem && keyCode != KeyEvent.KEYCODE_ENTER) return super.onKeyDown(keyCode, event)
        val emu = s.emulator
        var keyMod = 0
        val ctrl = event.isCtrlPressed || modifiers?.ctrl == true
        val alt = event.isAltPressed || modifiers?.alt == true
        if (ctrl) keyMod = keyMod or KeyHandler.KEYMOD_CTRL
        if (alt) keyMod = keyMod or KeyHandler.KEYMOD_ALT
        if (event.isShiftPressed) keyMod = keyMod or KeyHandler.KEYMOD_SHIFT
        if (event.isNumLockOn) keyMod = keyMod or KeyHandler.KEYMOD_NUM_LOCK

        val code = KeyHandler.getCode(keyCode, keyMod, emu.isCursorKeysApplicationMode, emu.isKeypadApplicationMode)
        if (code != null) {
            scrollToBottom()
            s.sendText(code)
            modifiers?.consume()
            return true
        }
        // Printable character from a hardware keyboard (or an IME that sends key events).
        val meta = event.metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_ALT_MASK).inv()
        val cp = event.getUnicodeChar(meta)
        if (cp != 0 && (cp and KeyCharacterMapCombining) == 0) {
            scrollToBottom()
            sendCodePoint(cp, ctrl, alt)
            modifiers?.consume()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // --- touch -------------------------------------------------------------------------

    private var dragMode = false
    private var scrollRemainder = 0f
    private var lastDragCell: Pair<Int, Int>? = null

    private fun cellAt(x: Float, y: Float): Pair<Int, Int> {
        val emu = session?.emulator
        val cols = emu?.mColumns ?: 80
        val rows = emu?.mRows ?: 24
        val col = (x / cellWidth).toInt().coerceIn(0, cols - 1) + 1
        val row = (y / cellHeight).toInt().coerceIn(0, rows - 1) + 1
        return col to row
    }

    private val mouseActive: Boolean get() = session?.emulator?.isMouseTrackingActive == true

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val emu = session?.emulator ?: return true
            if (mouseActive && topRow == 0) {
                val (c, r) = cellAt(e.x, e.y)
                emu.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, c, r, true)
                emu.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, c, r, false)
            } else {
                showKeyboard()
            }
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (dragMode || scaling) return true
            val emu = session?.emulator ?: return true
            scrollRemainder += dy
            val lines = (scrollRemainder / cellHeight).toInt()
            if (lines == 0) return true
            scrollRemainder -= lines * cellHeight
            when {
                mouseActive -> {
                    val (c, r) = cellAt(e2.x, e2.y)
                    val button = if (lines > 0) TerminalEmulator.MOUSE_WHEELDOWN_BUTTON else TerminalEmulator.MOUSE_WHEELUP_BUTTON
                    repeat(abs(lines)) { emu.sendMouseEvent(button, c, r, true) }
                }
                emu.isAlternateBufferActive -> {
                    val key = if (lines > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
                    repeat(abs(lines)) { sendKey(key) }
                }
                else -> {
                    topRow = min(0, max(-emu.screen.activeTranscriptRows, topRow + lines))
                    invalidate()
                }
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (!mouseActive || scaling) return
            val emu = session?.emulator ?: return
            dragMode = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            val cell = cellAt(e.x, e.y)
            lastDragCell = cell
            emu.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, cell.first, cell.second, true)
        }
    })

    private var scaling = false
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            scaling = true
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val f = detector.scaleFactor
            if (abs(f - 1f) > 0.08f) {
                textSizeSp = (textSizeSp * f * 2).roundToInt() / 2f
                return true
            }
            return false
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (dragMode) {
            val emu = session?.emulator
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val cell = cellAt(event.x, event.y)
                    if (cell != lastDragCell && emu != null) {
                        lastDragCell = cell
                        emu.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON_MOVED, cell.first, cell.second, true)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val cell = cellAt(event.x, event.y)
                    emu?.sendMouseEvent(TerminalEmulator.MOUSE_LEFT_BUTTON, cell.first, cell.second, false)
                    dragMode = false
                    lastDragCell = null
                }
            }
            return true
        }
        if (!scaleDetector.isInProgress) gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            scaling = false
            scrollRemainder = 0f
        }
        return true
    }

    fun scrollToBottom() {
        if (topRow != 0) {
            topRow = 0
            invalidate()
        }
    }

    companion object {
        private const val KeyCharacterMapCombining = 0x80000000.toInt() // KeyCharacterMap.COMBINING_ACCENT
    }
}

/** Sticky Ctrl/Alt state shared between the extra-keys bar and the view. */
class Modifiers(private val onChange: () -> Unit) {
    var ctrl = false
        set(v) { field = v; onChange() }
    var alt = false
        set(v) { field = v; onChange() }
    /** Locked modifiers stay on until tapped again (double-tap in the bar). */
    var ctrlLocked = false
    var altLocked = false

    fun consume() {
        if (ctrl && !ctrlLocked) ctrl = false
        if (alt && !altLocked) alt = false
    }
}
