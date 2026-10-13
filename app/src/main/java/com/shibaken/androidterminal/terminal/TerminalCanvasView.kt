package com.shibaken.androidterminal.terminal

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.text.InputType
import android.util.AttributeSet
import android.util.TypedValue
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TextStyle
import com.termux.terminal.WcWidth
import com.termux.view.TerminalRenderer
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Draws a [TerminalBackend]'s emulator and turns IME / keys / touches into terminal input.
 *
 * Japanese input: unlike Termux's TerminalView (TYPE_NULL), this view advertises a real text
 * field to the IME, so Gboard & co. can compose (未確定文字列). The composing text is drawn at the
 * cursor and only the committed text is sent to the remote side. See [TerminalInputConnection].
 *
 * Touch, when the remote app enabled mouse tracking (herdr does):
 *  - tap            → left click (focus a pane, click a tab, …)
 *  - vertical drag  → mouse wheel
 * In [paneMode] (multi-pane screen) a double tap toggles zoom, a two-finger horizontal swipe switches
 * tabs and (single-pane layout only, [onPaneSwipe]) a one-finger horizontal swipe switches panes.
 * Without mouse tracking: drag scrolls the local scrollback (or sends arrow keys in the
 * alternate screen), tap shows the keyboard.
 * In every mode (local text selection, like a normal phone text UI):
 *  - long-press     → select the word under the finger; keep dragging to extend the end
 *  - after lifting  → two handles adjust start/end, a floating toolbar offers コピー / 貼り付け / すべて選択
 *  - tap elsewhere, typing, or leaving the view ends the selection
 */
class TerminalCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var session: TerminalBackend? = null
        set(value) {
            field?.onScreenUpdate = null
            field = value
            value?.onScreenUpdate = { onScreenUpdated() }
            clearSelection()
            topRow = 0
            updateSize()
            invalidate()
        }

    /** Fade only the default background. Glyphs, selection, cursor and explicit ANSI fills stay opaque. */
    var backgroundOpacity: Float = 0.72f
        set(value) {
            val opacity = if (value.isFinite()) value.coerceIn(0f, 1f) else 0.72f
            if (field == opacity) return
            field = opacity
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

    // --- multi-pane (PANES mode) hooks ---------------------------------------------------
    /** PANES mode: taps/double-taps are routed to the pane layer. */
    var paneMode: Boolean = false

    /** Only the focused pane's view is focusable, so the IME / extra keys always target it. */
    var isPaneFocused: Boolean = true
        set(value) {
            field = value
            if (!value) clearSelection()
            isFocusable = value
            isFocusableInTouchMode = value
        }

    /** Tap on an unfocused pane (the tap is not forwarded as a click). */
    var onFocusRequest: (() -> Unit)? = null
    /** Second tap of a double tap on the focused pane; the first tap was already delivered as a click. */
    var onDoubleTap: (() -> Unit)? = null
    /** Two-finger horizontal swipe: -1 = previous tab, +1 = next tab. */
    var onTwoFingerSwipe: ((Int) -> Unit)? = null
    /** One-finger horizontal swipe: -1 = previous pane, +1 = next pane. Set only while panes are paged (single mode). */
    var onPaneSwipe: ((Int) -> Unit)? = null

    /** Width of one character cell in px at the current font size. */
    val cellWidthPx: Float get() = renderer.fontWidth

    private val imm = context.getSystemService(InputMethodManager::class.java)
    private var renderer = TerminalRenderer(spToPx(textSizeSp), TerminalFont.get(context))
    private var topRow = 0 // <= 0; negative values scroll into the transcript

    // --- IME composing ---------------------------------------------------------------
    internal var composingText: String = ""
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }
    private val composePaint = Paint().apply { isAntiAlias = true; typeface = TerminalFont.get(context) }
    private val composeBg = Paint().apply { color = Color.parseColor("#FF173239") }
    private val composeUnderline = Paint().apply { color = Color.parseColor("#FF64F4E5"); strokeWidth = 3f }

    init {
        composePaint.textSize = spToPx(textSizeSp).toFloat()
        isFocusable = true
        isFocusableInTouchMode = true
        keepScreenOn = true
    }

    private fun spToPx(sp: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics).roundToInt()

    private fun createRenderer() {
        renderer = TerminalRenderer(spToPx(textSizeSp), TerminalFont.get(context))
        composePaint.textSize = spToPx(textSizeSp).toFloat()
    }

    private val cellWidth: Float get() = renderer.fontWidth
    private val cellHeight: Int get() = renderer.fontLineSpacing

    private fun onScreenUpdated() {
        // New output snaps back to the live screen unless the user is reading history.
        val emu = session?.emulator ?: return
        val scrolled = emu.scrollCounter
        if (topRow < 0) {
            topRow = max(-emu.screen.activeTranscriptRows, topRow - scrolled)
        }
        emu.clearScrollCounter()
        if (selActive) shiftSelection(emu, scrolled)
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
        val emu = s.emulator
        if (cols != emu.mColumns || rows != emu.mRows) clearSelection() // reflow invalidates the coordinates
        s.resize(cols, rows, cellWidth.roundToInt(), cellHeight)
    }

    override fun onDraw(canvas: Canvas) {
        val emu = session?.emulator
        if (emu == null) {
            canvas.drawColor(androidx.core.graphics.ColorUtils.setAlphaComponent(
                Color.rgb(7, 18, 22), (255 * backgroundOpacity).roundToInt()))
            return
        }
        canvas.drawColor(androidx.core.graphics.ColorUtils.setAlphaComponent(
            emu.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND], (255 * backgroundOpacity).roundToInt()))
        if (selActive) {
            val (sy, sx, ey, ex) = selectionBounds()
            renderer.render(emu, canvas, topRow, sy, ey, sx, ex)
        } else {
            renderer.render(emu, canvas, topRow, -1, -1, -1, -1)
        }
        if (composingText.isNotEmpty() && topRow == 0) drawComposing(canvas, emu)
        if (selActive && !selDragging) drawHandles(canvas)
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
        clearSelection()
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
        clearSelection()
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
        clearSelection()
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

    private var paneSwipeStartX = 0f
    private var paneSwipeStartY = 0f
    private var paneSwipeActive = false // one-finger gesture that may still turn into a pane swipe
    private var paneSwipeFired = false
    private var swipeStartDist = 0f
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var swipeFired = false
    private val swipeThresholdPx = 64f * resources.displayMetrics.density
    private var scrollRemainder = 0f

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
            if (paneMode && !isPaneFocused) {
                onFocusRequest?.invoke()
                return true
            }
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
            if (scaling || paneSwipeFired) return true
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

        override fun onDoubleTap(e: MotionEvent): Boolean {
            // The first tap already went out as a click; the second one only toggles zoom.
            if (paneMode && isPaneFocused) onDoubleTap?.invoke()
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (scaling || session == null) return
            if (paneMode && !isPaneFocused) return // the tap-to-focus path handles unfocused panes
            beginSelection(e.x, e.y)
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
        if (handleSelectionTouch(event)) return true
        scaleDetector.onTouchEvent(event)
        trackTwoFingerSwipe(event)
        trackPaneSwipe(event)
        if (!scaleDetector.isInProgress) gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            scaling = false
            scrollRemainder = 0f
        }
        return true
    }

    /** Detects a one-finger horizontal swipe (pane paging) and reports it once per gesture. */
    private fun trackPaneSwipe(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                paneSwipeStartX = event.x
                paneSwipeStartY = event.y
                paneSwipeActive = onPaneSwipe != null
                paneSwipeFired = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> paneSwipeActive = false // multi-touch: pinch / tab swipe
            MotionEvent.ACTION_MOVE -> if (paneSwipeActive && !paneSwipeFired && event.pointerCount == 1) {
                val dir = PaneGestureMath.oneFingerPaneSwipe(event.x - paneSwipeStartX, event.y - paneSwipeStartY, swipeThresholdPx)
                if (dir != 0) {
                    paneSwipeFired = true
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onPaneSwipe?.invoke(dir)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> paneSwipeActive = false
        }
    }

    /** Detects a parallel two-finger horizontal swipe (distinct from pinch) and reports it once per gesture. */
    private fun trackTwoFingerSwipe(event: MotionEvent) {
        val cb = onTwoFingerSwipe ?: return
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount == 2) {
                swipeStartX = (event.getX(0) + event.getX(1)) / 2
                swipeStartY = (event.getY(0) + event.getY(1)) / 2
                swipeStartDist = hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
                swipeFired = false
            }
            MotionEvent.ACTION_MOVE -> if (event.pointerCount == 2 && !swipeFired && swipeStartDist > 0f) {
                val cx = (event.getX(0) + event.getX(1)) / 2
                val cy = (event.getY(0) + event.getY(1)) / 2
                val dist = hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
                val dir = PaneGestureMath.twoFingerSwipe(cx - swipeStartX, cy - swipeStartY, swipeStartDist, dist, swipeThresholdPx)
                if (dir != 0) {
                    swipeFired = true
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    cb(dir)
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> swipeStartDist = 0f
        }
    }

    // --- text selection ----------------------------------------------------------------
    // Cells are (0-based col, external row); external row 0 is the top of the live screen and
    // transcript rows are negative, i.e. the renderer's convention. The two ends are stored in
    // the order the user set them; [selectionBounds] normalizes. On scroll the rows are shifted
    // by the scroll counter so the selection stays on the same text ([shiftSelection]).

    private var selActive = false
    private var selDragging = false // finger still down after the long-press (extending the end)
    private var selHandle = 0 // 1 = dragging the start handle, 2 = the end handle, 0 = none
    private var ignoreUntilUp = false // the tap that dismissed the selection must not act
    private var selStartCol = 0
    private var selStartRow = 0
    private var selEndCol = 0
    private var selEndRow = 0
    private var actionMode: ActionMode? = null
    private var actionModeSuppressDestroy = false

    private val handleRadius = 9f * resources.displayMetrics.density
    private val handleSlop = 24f * resources.displayMetrics.density
    private val handlePaint = Paint().apply { isAntiAlias = true; color = Color.parseColor("#FF64F4E5") }

    private fun selOrdered(): Boolean =
        selStartRow < selEndRow || (selStartRow == selEndRow && selStartCol <= selEndCol)

    /** Normalized (startRow, startCol, endRow, endCol), end inclusive. */
    private fun selectionBounds(): IntArray =
        if (selOrdered()) intArrayOf(selStartRow, selStartCol, selEndRow, selEndCol)
        else intArrayOf(selEndRow, selEndCol, selStartRow, selStartCol)

    private operator fun IntArray.component4() = this[3]

    /** View coords to (0-based col, external row), clamped to the buffer. */
    private fun selCellAt(x: Float, y: Float): Pair<Int, Int> {
        val emu = session?.emulator ?: return 0 to 0
        val col = (x / cellWidth).toInt().coerceIn(0, emu.mColumns - 1)
        val minRow = -emu.screen.activeTranscriptRows
        val row = (Math.floor((y / cellHeight).toDouble()).toInt() + topRow).coerceIn(minRow, emu.mRows - 1)
        return col to row
    }

    /** The text of one cell, "" when blank (trailing blanks are never reported by the buffer). */
    private fun cellText(emu: TerminalEmulator, col: Int, row: Int): String =
        emu.screen.getSelectedText(col, row, col, row, false)

    /** Non-blank cell; the trailing half of a wide (CJK) character reads as empty but belongs to the word. */
    private fun isWordCell(emu: TerminalEmulator, col: Int, row: Int): Boolean {
        val t = cellText(emu, col, row)
        if (t.isNotEmpty()) return !t.isBlank()
        if (col == 0) return false
        val prev = cellText(emu, col - 1, row)
        return prev.isNotEmpty() && WcWidth.width(prev.codePointBefore(prev.length)) == 2
    }

    private fun beginSelection(x: Float, y: Float) {
        val emu = session?.emulator ?: return
        val (col, row) = selCellAt(x, y)
        var c1 = col
        var c2 = col
        if (isWordCell(emu, col, row)) {
            while (c1 > 0 && isWordCell(emu, c1 - 1, row)) c1--
            while (c2 < emu.mColumns - 1 && isWordCell(emu, c2 + 1, row)) c2++
        }
        selStartCol = c1; selStartRow = row
        selEndCol = c2; selEndRow = row
        selActive = true
        selDragging = true
        selHandle = 0
        paneSwipeActive = false
        scaling = false
        scrollRemainder = 0f
        finishActionMode()
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        parent?.requestDisallowInterceptTouchEvent(true)
        invalidate()
    }

    /** Consumes the touch while a selection is being made / adjusted. Returns true if handled. */
    private fun handleSelectionTouch(event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) ignoreUntilUp = false
        if (ignoreUntilUp) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) ignoreUntilUp = false
            return true
        }
        if (!selActive) return false
        when {
            selDragging -> {
                when (action) {
                    MotionEvent.ACTION_MOVE -> {
                        val (c, r) = selCellAt(event.x, event.y)
                        if (c != selEndCol || r != selEndRow) {
                            selEndCol = c; selEndRow = r
                            invalidate()
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        selDragging = false
                        showActionMode()
                        invalidate()
                    }
                }
            }
            selHandle != 0 -> {
                when (action) {
                    MotionEvent.ACTION_MOVE -> moveHandle(event.x, event.y)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        selHandle = 0
                        showActionMode()
                        invalidate()
                    }
                }
            }
            action == MotionEvent.ACTION_DOWN -> {
                val h = hitHandle(event.x, event.y)
                if (h != 0) {
                    selHandle = h
                    finishActionMode()
                    parent?.requestDisallowInterceptTouchEvent(true)
                } else {
                    clearSelection()
                    ignoreUntilUp = true
                }
            }
        }
        return true
    }

    /** Handle anchor points in view coords: start at the left edge of its cell, end at the right edge. */
    private fun handlePoint(start: Boolean): Pair<Float, Float> {
        val b = selectionBounds()
        return if (start) b[1] * cellWidth to (b[0] - topRow + 1) * cellHeight.toFloat()
        else (b[3] + 1) * cellWidth to (b[2] - topRow + 1) * cellHeight.toFloat()
    }

    /** 1 = start handle, 2 = end handle, 0 = none. Prefers the nearer one when they overlap. */
    private fun hitHandle(x: Float, y: Float): Int {
        var best = 0
        var bestDist = Float.MAX_VALUE
        for (h in 1..2) {
            val (hx, hy) = handlePoint(h == 1)
            val d = hypot(x - hx, y - (hy + handleRadius))
            if (d <= handleSlop + handleRadius && d < bestDist) { best = h; bestDist = d }
        }
        return best
    }

    private fun moveHandle(x: Float, y: Float) {
        // The handle hangs below its line, so aim at the line above the finger.
        val ty = y - handleRadius - cellHeight / 2f
        val r = selCellAt(x, ty).second
        val b = selectionBounds()
        // Normalize storage so start <= end, then move the grabbed end.
        selStartRow = b[0]; selStartCol = b[1]; selEndRow = b[2]; selEndCol = b[3]
        val cols = session?.emulator?.mColumns ?: return
        if (selHandle == 1) {
            selStartCol = (x / cellWidth).roundToInt().coerceIn(0, cols - 1); selStartRow = r
        } else {
            selEndCol = ((x / cellWidth).roundToInt() - 1).coerceIn(0, cols - 1); selEndRow = r
        }
        if (!selOrdered()) { // handles crossed: swap so start stays the earlier one
            // The start handle sits on a cell boundary, the end is inclusive: convert when swapping.
            val tc = if (selHandle == 2) selStartCol else (selStartCol - 1).coerceAtLeast(0)
            val tr = selStartRow
            selStartCol = selEndCol; selStartRow = selEndRow
            selEndCol = tc; selEndRow = tr
            selHandle = 3 - selHandle
        }
        invalidate()
    }

    private fun drawHandles(canvas: Canvas) {
        for (start in booleanArrayOf(true, false)) {
            val (x, y) = handlePoint(start)
            canvas.drawLine(x, y - cellHeight, x, y, handlePaint.apply { strokeWidth = 3f })
            canvas.drawCircle(x, y + handleRadius, handleRadius, handlePaint)
        }
    }

    /** Keeps the selection on the same text when the buffer scrolls up by [scrolled] rows. */
    private fun shiftSelection(emu: TerminalEmulator, scrolled: Int) {
        if (scrolled == 0) return
        val minRow = -emu.screen.activeTranscriptRows
        selStartRow -= scrolled
        selEndRow -= scrolled
        val b = selectionBounds()
        if (b[2] < minRow) { clearSelection(); return }
        if (b[0] < minRow) { // start scrolled out of the transcript: clamp to its first cell
            if (selOrdered()) { selStartRow = minRow; selStartCol = 0 } else { selEndRow = minRow; selEndCol = 0 }
        }
        actionMode?.invalidateContentRect()
    }

    fun clearSelection() {
        if (!selActive) return
        selActive = false
        selDragging = false
        selHandle = 0
        finishActionMode()
        invalidate()
    }

    private fun selectedText(): String {
        val emu = session?.emulator ?: return ""
        val b = selectionBounds()
        return emu.screen.getSelectedText(b[1], b[0], b[3], b[2], true)
            .lines().joinToString("\n") { it.trimEnd() }
    }

    private fun finishActionMode() {
        val m = actionMode ?: return
        actionModeSuppressDestroy = true
        m.finish()
        actionModeSuppressDestroy = false
        actionMode = null
    }

    private fun showActionMode() {
        finishActionMode()
        actionMode = startActionMode(selectionCallback, ActionMode.TYPE_FLOATING)
    }

    private val selectionCallback = object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            menu.add(Menu.NONE, MENU_COPY, 0, "コピー")
            menu.add(Menu.NONE, MENU_PASTE, 1, "貼り付け")
            menu.add(Menu.NONE, MENU_SELECT_ALL, 2, "すべて選択")
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            when (item.itemId) {
                MENU_COPY -> {
                    val text = selectedText()
                    if (text.isNotEmpty()) {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("terminal", text))
                    }
                    clearSelection()
                }
                MENU_PASTE -> {
                    session?.pasteFromClipboard()
                    clearSelection()
                }
                MENU_SELECT_ALL -> {
                    val emu = session?.emulator ?: return true
                    selStartCol = 0; selStartRow = -emu.screen.activeTranscriptRows
                    selEndCol = emu.mColumns - 1; selEndRow = emu.mRows - 1
                    invalidate()
                    mode.invalidateContentRect()
                }
            }
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            if (actionMode === mode) actionMode = null
            if (!actionModeSuppressDestroy) clearSelection() // dismissed by the system (e.g. Back)
        }

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
            if (!selActive) { outRect.set(0, 0, 1, 1); return }
            val b = selectionBounds()
            val top = ((b[0] - topRow) * cellHeight).coerceIn(0, max(0, height - cellHeight))
            val bottom = ((b[2] - topRow + 1) * cellHeight).coerceIn(top + cellHeight, max(top + cellHeight, height))
            if (b[0] == b[2]) {
                outRect.set((b[1] * cellWidth).toInt(), top, ((b[3] + 1) * cellWidth).toInt(), bottom)
            } else {
                outRect.set(0, top, width, bottom)
            }
        }
    }

    override fun onDetachedFromWindow() {
        ignoreUntilUp = false
        clearSelection()
        super.onDetachedFromWindow()
    }

    fun scrollToBottom() {
        if (topRow != 0) {
            topRow = 0
            invalidate()
        }
    }

    companion object {
        private const val MENU_COPY = 1
        private const val MENU_PASTE = 2
        private const val MENU_SELECT_ALL = 3
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
