package com.shibaken.androidterminal.terminal

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
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
            cancelHistory()
            dropHistoryCache()
            field = value
            bandTracker.reset(); headerRows = 0; footerRows = 0
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
            if (holdActive) endHold()
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
            if (!value) { clearSelection(); cancelHistory() }
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

    /**
     * Panes mode: fetches the pane's recent output (ANSI, [HistoryMath.FETCH_LINES] lines) from the host and
     * delivers it on the main thread (null on failure). When set, scrolling a mouse-tracking pane enters the
     * local history view instead of forwarding wheel events.
     */
    var historyFetcher: ((lines: Int, result: (String?) -> Unit) -> Unit)? = null

    /** True while the local history view is shown (for the floating "latest" button). */
    var onHistoryChanged: ((Boolean) -> Unit)? = null
    val inHistory: Boolean get() = histPhase == HistoryPhase.ACTIVE

    /** Width of one character cell in px at the current font size. */
    val cellWidthPx: Float get() = renderer.fontWidth

    private val imm = context.getSystemService(InputMethodManager::class.java)
    private var renderer = TerminalRenderer(spToPx(textSizeSp), TerminalFont.get(context))
    // Local transcript position in px (>= 0 = how far into history); topRow is its nearest whole row (<= 0).
    private var scrollPx = 0f
    private var topRow: Int
        get() = -(scrollPx / cellHeight).roundToInt()
        set(v) { scrollPx = -v * cellHeight.toFloat() }
    private val maxScrollPx: Float get() = (viewEmulator?.screen?.activeTranscriptRows ?: 0) * cellHeight.toFloat()
    /** What is drawn / selected: the history snapshot while in the history view, else the live emulator. */
    private val viewEmulator: TerminalEmulator? get() = hist ?: session?.emulator
    private val boost = RefreshRateBoost(this)

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
        defaultFocusHighlightEnabled = false // keyboard-driven focus (e.g. after Back) must not tint the whole terminal
    }

    private fun spToPx(sp: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics).roundToInt()

    private fun createRenderer() {
        renderer = TerminalRenderer(spToPx(textSizeSp), TerminalFont.get(context))
        composePaint.textSize = spToPx(textSizeSp).toFloat()
    }

    private val cellWidth: Float get() = renderer.fontWidth
    private val cellHeight: Int get() = renderer.fontLineSpacing

    private fun trackBand(emu: TerminalEmulator) {
        val alt = emu.isAlternateBufferActive
        bandTracker.validateContext(emu.mRows, emu.mColumns, alt)
        if (!bandTracker.hasProbe) return
        val now = android.os.SystemClock.uptimeMillis()
        if (bandTracker.probeExpired(now)) { bandTracker.onRepaint(LongArray(0), emu.mColumns, alt, now); return }
        bandTracker.onRepaint(RowSig.ofScreen(emu), emu.mColumns, alt, now)
    }

    /** Called right before wheel events are forwarded: remembers the screen so the next repaint can reveal what scrolled. */
    private fun probeBand(emu: TerminalEmulator) {
        if (!mouseActive && !emu.isAlternateBufferActive) return
        bandTracker.beginProbe(RowSig.ofScreen(emu), emu.mColumns, emu.isAlternateBufferActive, android.os.SystemClock.uptimeMillis())
    }

    private fun onScreenUpdated() {
        // New output snaps back to the live screen unless the user is reading history.
        val emu = session?.emulator ?: return
        val scrolled = emu.scrollCounter
        cachePolicy.onOutput(android.os.SystemClock.uptimeMillis())
        if (scrollPx > 0f && hist == null) {
            scrollPx = min(maxScrollPx, scrollPx + scrolled * cellHeight)
        }
        emu.clearScrollCounter()
        unackedRows = 0 // the repaint now reflects what was sent
        trackBand(emu)
        if (selActive && hist == null) shiftSelection(emu, scrolled)
        if (holdActive && !holdInResize) { holdGotOutput = true; holdLastOutputMs = android.os.SystemClock.uptimeMillis() }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateSize(holdOldW = oldw, holdOldH = oldh)
    }

    // --- render hold ---------------------------------------------------------------------
    // A layout-driven grid resize makes the local emulator reflow immediately, while the remote repaints for the new
    // size only after SIGWINCH round-trips (tens to ~150 ms). Showing the reflowed in-between state reads as flicker,
    // so keep presenting the last rendered frame (top-aligned, clipped to the new bounds) until the remote output
    // after the resize has been quiet for a moment, or a timeout passes.
    private var holdBitmap: Bitmap? = null
    private var holdActive = false
    private var holdInResize = false
    private var holdStartMs = 0L
    private var holdLastOutputMs = 0L
    private var holdGotOutput = false
    private val holdCheck = object : Runnable {
        override fun run() {
            if (!holdActive) return
            val now = android.os.SystemClock.uptimeMillis()
            val quiet = holdGotOutput && now - holdLastOutputMs >= HOLD_QUIET_MS
            if (quiet || now - holdStartMs >= HOLD_TIMEOUT_MS) endHold() else postDelayed(this, 16)
        }
    }

    private fun beginHold(oldW: Int, oldH: Int) {
        val now = android.os.SystemClock.uptimeMillis()
        if (holdActive) { holdStartMs = now; holdGotOutput = false; return } // keep the original pre-resize frame
        val emu = session?.emulator ?: return
        if (oldW <= 0 || oldH <= 0) return
        var bmp = holdBitmap
        if (bmp == null || bmp.width != oldW || bmp.height != oldH) {
            bmp = Bitmap.createBitmap(oldW, oldH, Bitmap.Config.ARGB_8888)
            holdBitmap = bmp
        }
        val c = Canvas(bmp)
        c.drawColor(Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)
        c.drawColor(androidx.core.graphics.ColorUtils.setAlphaComponent(
            emu.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND], (255 * backgroundOpacity).roundToInt()))
        renderer.render(emu, c, topRow, -1, -1, -1, -1)
        holdActive = true; holdGotOutput = false; holdStartMs = now
        removeCallbacks(holdCheck); postDelayed(holdCheck, 16)
    }

    private fun endHold() {
        holdActive = false
        removeCallbacks(holdCheck)
        invalidate()
    }

    private fun updateSize(holdOldW: Int = 0, holdOldH: Int = 0) {
        val s = session ?: return
        if (width == 0 || height == 0) return
        val cols = max(4, (width / cellWidth).toInt())
        val rows = max(2, height / cellHeight)
        val emu = s.emulator
        val gridChanged = cols != emu.mColumns || rows != emu.mRows
        if (gridChanged) {
            clearSelection() // reflow invalidates the coordinates
            footerRows = 0; headerRows = 0
            bandTracker.reset()
            cancelHistory()
            dropHistoryCache() // rebuilt lazily by the next prefetch
            if (holdOldW > 0 && holdOldH > 0) beginHold(holdOldW, holdOldH)
        }
        holdInResize = true
        try { s.resize(cols, rows, cellWidth.roundToInt(), cellHeight) } finally { holdInResize = false }
    }

    override fun onDraw(canvas: Canvas) {
        val emu = viewEmulator
        if (emu == null) {
            canvas.drawColor(androidx.core.graphics.ColorUtils.setAlphaComponent(
                Color.rgb(7, 18, 22), (255 * backgroundOpacity).roundToInt()))
            return
        }
        val hold = holdBitmap
        if (holdActive && android.os.SystemClock.uptimeMillis() - holdStartMs > HOLD_TIMEOUT_MS + 100) endHold() // safety net
        if (holdActive && hold != null && hist == null) {
            canvas.drawColor(androidx.core.graphics.ColorUtils.setAlphaComponent(
                emu.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND], (255 * backgroundOpacity).roundToInt()))
            canvas.drawBitmap(hold, 0f, 0f, null)
            return
        }
        canvas.drawColor(androidx.core.graphics.ColorUtils.setAlphaComponent(
            emu.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND], (255 * backgroundOpacity).roundToInt()))
        val sel = if (selActive) selectionBounds() else null
        val sy = sel?.get(0) ?: -1; val sx = sel?.get(1) ?: -1; val ey = sel?.get(2) ?: -1; val ex = sel?.get(3) ?: -1
        val footer = activeFooter()
        val header = activeHeader()
        val save = canvas.save()
        if (footer > 0 || header > 0) canvas.clipRect(0, header * cellHeight, width, contentHeightPx(footer)) // scrolling region only
        drawScrolled(canvas, emu, sy, ey, sx, ex)
        canvas.restoreToCount(save)
        if (footer > 0) drawPinnedFooter(canvas, footer)
        if (header > 0) drawPinnedHeader(canvas, header)
        if (composingText.isNotEmpty() && scrollPx == 0f && hist == null) drawComposing(canvas, emu)
        if (selActive && !selDragging) drawHandles(canvas)
    }

    /** The (pixel-)scrolled content of [emu]; callers clip it. */
    private fun drawScrolled(canvas: Canvas, emu: TerminalEmulator, sy: Int, ey: Int, sx: Int, ex: Int) {
        val cell = cellHeight
        val px = scrollPx.coerceIn(0f, maxScrollPx)
        val whole = (px / cell).toInt()
        val frac = px - whole * cell
        val save = canvas.save()
        if (frac > 0.5f) {
            // Pixel-scrolled: draw one extra row above and shift by the sub-row part.
            canvas.translate(0f, frac - cell)
            renderer.render(emu, canvas, -whole - 1, emu.mRows + 1, sy, ey, sx, ex)
        } else {
            val off = remoteOffsetPx()
            if (off != 0f) canvas.translate(0f, -off)
            renderer.render(emu, canvas, -whole, emu.mRows, sy, ey, sx, ex)
        }
        canvas.restoreToCount(save)
    }

    // --- pinned footer ---------------------------------------------------------------------
    // A TUI's input box + status lines at the bottom would sway with the scrolling content, so while the content is displaced
    // (history view, fetch offset, wheel-forward offset) those rows are drawn from the LIVE emulator at the bottom and only
    // the region above is scrolled. Detected once per touch so the split stays fixed during a gesture.
    private var footerRows = 0
    private val separatorPaint = Paint().apply { color = Color.parseColor("#4064F4E5") }

    private fun contentHeightPx(footer: Int): Int = ((session?.emulator?.mRows ?: 0) - footer) * cellHeight

    private var headerRows = 0
    private val bandTracker = BandTracker()

    /** Picks the fixed header/footer for this gesture: learned band > DECSTBM margins > visual footer heuristics > none. */
    private fun detectFooter() {
        val live = session?.emulator
        if (live == null || hist != null || !(mouseActive || live.isAlternateBufferActive)) { footerRows = 0; headerRows = 0; return }
        bandTracker.validateContext(live.mRows, live.mColumns, live.isAlternateBufferActive)
        val learned = bandTracker.learned
        val margins = FixedBands.fromMargins(live.topMargin, live.bottomMargin, live.mRows)
        // The heuristics are the fallback for the very first gesture only; skip their cost when a better source exists.
        val heuristic = if (learned == null && margins == null && mouseActive)
            FooterDetector.detectRows(FooterDetector.rowsOf(live), live.cursorRow, live.shouldCursorBeVisible(), live.mColumns) else 0
        val band = FixedBands.choose(learned, margins, heuristic)
        headerRows = band.header; footerRows = band.footer
    }

    /** Header rows to pin right now (not in the history view, whose stitched content scrolls as a whole). */
    private fun activeHeader(): Int {
        val h = headerRows
        if (h <= 0 || hist != null) return 0
        val live = session?.emulator ?: return 0
        if (live.mRows <= h + footerRows + 2) return 0
        return if ((mouseActive || live.isAlternateBufferActive) && remoteOffsetPx() != 0f) h else 0
    }

    private fun drawPinnedHeader(canvas: Canvas, header: Int) {
        val live = session?.emulator ?: return
        val save = canvas.save()
        canvas.clipRect(0, 0, width, header * cellHeight)
        renderer.render(live, canvas, 0, header, -1, -1, -1, -1)
        canvas.restoreToCount(save)
    }

    /** Footer rows to pin right now, 0 when the content is not displaced. */
    private fun activeFooter(): Int {
        val f = footerRows
        if (f <= 0) return 0
        val live = session?.emulator ?: return 0
        if (live.mRows <= f + 1) return 0
        return if (hist != null || ((mouseActive || live.isAlternateBufferActive) && remoteOffsetPx() != 0f)) f else 0
    }

    private fun drawPinnedFooter(canvas: Canvas, footer: Int) {
        val live = session?.emulator ?: return
        val top = contentHeightPx(footer)
        val save = canvas.save()
        canvas.clipRect(0, top, width, height)
        canvas.translate(0f, top.toFloat())
        renderer.render(live, canvas, live.mRows - footer, footer, -1, -1, -1, -1)
        canvas.restoreToCount(save)
        if (hist != null && scrollPx > 0.5f) canvas.drawRect(0f, top.toFloat(), width.toFloat(), top + resources.displayMetrics.density, separatorPaint)
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
    private val wheelAcc = ScrollAccumulator(1f)
    private var unackedRows = 0      // wheel steps sent since the last repaint (drawn as a temporary offset)
    private var releaseOffsetPx = 0f // sub-row offset left over at release, eased back to 0
    private var fling: FlingDecay? = null
    private var flingLastMs = 0L
    private var flingX = 0f
    private var flingY = 0f
    private val clearUnacked = Runnable { if (unackedRows != 0) { unackedRows = 0; invalidate() } }

    /** Temporary content offset (px, + = content moves up) standing in for wheel steps the host has not repainted yet. */
    private fun remoteOffsetPx(): Float {
        val limit = cellHeight * 1.5f
        val pending = if (histPhase == HistoryPhase.FETCHING) HistoryMath.pendingShift(histPendingPx, cellHeight.toFloat()) else 0f
        return (unackedRows * cellHeight + wheelAcc.remainder + releaseOffsetPx + pending).coerceIn(-limit, limit)
    }

    // --- local history view (herdr panes) ----------------------------------------------------
    // Mouse-tracking pane + scroll toward older lines: fetch the pane's recent output once, replay it into an offscreen
    // emulator whose bottom matches the live screen, and scroll that locally (pixel-precise, fling, selection).
    private var histPhase = HistoryPhase.IDLE
    private var hist: TerminalEmulator? = null
    private var histPendingPx = 0f   // distance into history gathered while the fetch is in flight
    private var histGen = 0
    private var prefetchPauseUntilMs = 0L
    private var lastScrollX = 0f
    private var lastScrollY = 0f
    private var histToken = 0L // output counter when the on-demand fetch started
    private var histFailUntilMs = 0L
    private var touching = false
    private val histTimeout = Runnable { if (histPhase == HistoryPhase.FETCHING) failHistory() }

    // Background prefetch: a fresh snapshot is cached per view (= per visible pane) so entering is instant.
    private val cachePolicy = HistoryCachePolicy()
    private var cacheEmu: TerminalEmulator? = null      // exactly aligned build of the snapshot (valid while the policy says fresh)
    private var cacheSnap: HistorySnapshot? = null      // the snapshot itself, kept when stale for content stitching
    private var prefetchGen = 0
    private val prefetchTick = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) return
            maybePrefetch()
            postDelayed(this, PREFETCH_TICK_MS)
        }
    }

    private fun dropHistoryCache() {
        prefetchGen++
        cacheEmu = null
        cacheSnap = null
        cachePolicy.invalidate()
    }

    private fun maybePrefetch() {
        val fetcher = historyFetcher ?: return
        val live = session?.emulator ?: return
        if (!mouseActive || live.isAlternateBufferActive || windowVisibility != VISIBLE || !isShown) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now < prefetchPauseUntilMs) return
        if (!cachePolicy.shouldPrefetch(now, true, histPhase != HistoryPhase.IDLE)) return
        val gen = prefetchGen
        val token = cachePolicy.begin(now)
        fetcher(HistoryMath.FETCH_LINES) { text -> post { onPrefetched(gen, token, text, live) } }
    }

    private fun onPrefetched(gen: Int, token: Long, text: String?, liveAtStart: TerminalEmulator) {
        if (gen != prefetchGen) return
        val live = session?.emulator
        if (text.isNullOrBlank() || live == null || live !== liveAtStart) { cachePolicy.complete(token, false); return }
        val cols = live.mColumns; val rows = live.mRows
        val cw = cellWidth.roundToInt(); val ch = cellHeight
        // Output since the fetch started: keep the snapshot for stitching but skip the exact-alignment build.
        val exact = token == cachePolicy.outputSeq
        val lastRow = if (exact) HistoryMath.lastNonBlankRow({ r -> live.screen.getSelectedText(0, r, cols - 1, r, false).isBlank() }, rows) else -1
        Thread({
            val snap = try { HistoryStitch.snapshotOf(text, cols) } catch (e: Exception) { null }
            val emu = if (exact && snap != null) try { HistoryMath.buildEmulator(text, cols, rows, cw, ch, lastRow) } catch (e: Exception) { null } else null
            post {
                val cur = session?.emulator
                if (gen != prefetchGen || snap == null || cur == null || cur.mColumns != cols || cur.mRows != rows) {
                    cachePolicy.complete(token, false)
                } else {
                    if (!HistoryMath.deepEnough(snap.size - rows, rows)) {
                        // Shallow snapshot (screen only): history would dead-end; pause prefetching for this pane.
                        prefetchPauseUntilMs = android.os.SystemClock.uptimeMillis() + HistoryMath.SHALLOW_BACKOFF_MS
                        cachePolicy.complete(token, false); cacheEmu = null; cacheSnap = null
                        return@post
                    }
                    cacheSnap = snap
                    if (cachePolicy.complete(token, true) && emu != null) cacheEmu = emu else cacheEmu = null
                }
            }
        }, "history-prefetch").start()
    }

    /** The cached snapshot if it is still exactly aligned with the live screen and has history to show. */
    private fun freshCache(): TerminalEmulator? {
        val emu = cacheEmu ?: return null
        val live = session?.emulator ?: return null
        if (!cachePolicy.isFresh || emu.mColumns != live.mColumns || emu.mRows != live.mRows) return null
        return if (HistoryMath.deepEnough(emu.screen.activeTranscriptRows, emu.mRows)) emu else null
    }

    private fun beginHistoryFetch(fetcher: (Int, (String?) -> Unit) -> Unit) {
        histPhase = HistoryPhase.FETCHING
        histToken = cachePolicy.outputSeq
        histPendingPx = 0f
        val gen = ++histGen
        postDelayed(histTimeout, HistoryMath.FETCH_TIMEOUT_MS)
        val live = session?.emulator
        val snap = cacheSnap
        if (snap != null && live != null && snap.cols == live.mColumns) {
            // Stale snapshot: stitch it to the current live screen by content (background), else fetch on demand.
            val cols = live.mColumns; val rows = live.mRows
            val cw = cellWidth.roundToInt(); val ch = cellHeight
            val livePlain = HistoryStitch.livePlain(live); val liveAnsi = HistoryStitch.liveAnsi(live)
            Thread({
                val emu = try { HistoryStitch.build(snap, livePlain, liveAnsi, cols, rows, cw, ch) } catch (e: Exception) { null }
                post {
                    if (gen != histGen || histPhase != HistoryPhase.FETCHING) return@post
                    if (emu != null) onHistoryBuilt(gen, emu) else fetcher(HistoryMath.FETCH_LINES) { text -> post { onHistoryFetched(gen, text) } }
                }
            }, "history-stitch").start()
            return
        }
        fetcher(HistoryMath.FETCH_LINES) { text -> post { onHistoryFetched(gen, text) } }
    }

    private fun onHistoryFetched(gen: Int, text: String?) {
        if (gen != histGen || histPhase != HistoryPhase.FETCHING) return
        val live = session?.emulator
        if (text == null || text.isBlank() || live == null) { failHistory(); return }
        val cols = live.mColumns; val rows = live.mRows
        val cw = cellWidth.roundToInt(); val ch = cellHeight
        val lastRow = HistoryMath.lastNonBlankRow({ r -> live.screen.getSelectedText(0, r, cols - 1, r, false).isBlank() }, rows)
        Thread({
            val emu = try { HistoryMath.buildEmulator(text, cols, rows, cw, ch, lastRow) } catch (e: Exception) { null }
            post { onHistoryBuilt(gen, emu) }
        }, "history-build").start()
    }

    private fun onHistoryBuilt(gen: Int, emu: TerminalEmulator?) {
        if (gen != histGen || histPhase != HistoryPhase.FETCHING) return
        removeCallbacks(histTimeout)
        val live = session?.emulator
        val max = (emu?.screen?.activeTranscriptRows ?: 0) * cellHeight.toFloat()
        if (emu == null || live == null || emu.mColumns != live.mColumns || emu.mRows != live.mRows ||
            !HistoryMath.shouldEnter(histPendingPx, max, cellHeight.toFloat())) {
            // Nothing to show (no scrollback), or the user already reversed the gesture: keep the live view.
            if (emu == null || max < cellHeight) histFailUntilMs = android.os.SystemClock.uptimeMillis() + HistoryMath.FAIL_BACKOFF_MS
            histPhase = HistoryPhase.IDLE; histPendingPx = 0f
            invalidate()
            return
        }
        if (!HistoryMath.deepEnough(emu.screen.activeTranscriptRows, emu.mRows)) {
            // The pane holds almost no history here (alt screen / the app scrolls itself): do not dead-end after a few lines,
            // forward the gesture to the host as before and stay with wheel forwarding for a while.
            histFailUntilMs = android.os.SystemClock.uptimeMillis() + HistoryMath.SHALLOW_BACKOFF_MS
            prefetchPauseUntilMs = histFailUntilMs
            dropHistoryCache()
            replayPending()
            return
        }
        if (cachePolicy.adopt(histToken)) cacheEmu = emu
        enterHistory(emu, histPendingPx)
    }

    private fun enterHistory(emu: TerminalEmulator, startPx: Float) {
        clearSelection()
        hist = emu
        histPhase = HistoryPhase.ACTIVE
        scrollPx = HistoryMath.initialScroll(startPx, emu.screen.activeTranscriptRows * cellHeight.toFloat())
        histPendingPx = 0f
        wheelAcc.reset(); unackedRows = 0; releaseOffsetPx = 0f
        removeCallbacks(clearUnacked)
        onHistoryChanged?.invoke(true)
        postOnAnimation(frameStep)
        invalidate()
    }

    private fun failHistory() {
        histFailUntilMs = android.os.SystemClock.uptimeMillis() + HistoryMath.FAIL_BACKOFF_MS
        histGen++
        removeCallbacks(histTimeout)
        replayPending()
    }

    /** Ends the fetching phase and sends the distance the user already scrolled as wheel-up steps, so nothing is lost. */
    private fun replayPending() {
        val pending = histPendingPx
        histPhase = HistoryPhase.IDLE; histPendingPx = 0f
        removeCallbacks(histTimeout)
        val emu = session?.emulator
        val rows = HistoryMath.replayRows(pending, cellHeight.toFloat(), MAX_DRAG_ROWS)
        if (emu != null && rows > 0 && mouseActive) {
            val (c, r) = cellAt(lastScrollX, lastScrollY)
            repeat(rows) { emu.sendMouseEvent(TerminalEmulator.MOUSE_WHEELUP_BUTTON, c, r, true) }
            unackedRows -= rows
            removeCallbacks(clearUnacked); postDelayed(clearUnacked, UNACKED_TIMEOUT_MS)
        }
        invalidate()
    }

    /** Drops an in-flight fetch or the history view without touching the live state. */
    private fun cancelHistory() {
        if (histPhase == HistoryPhase.IDLE) return
        val wasActive = histPhase == HistoryPhase.ACTIVE
        histGen++
        removeCallbacks(histTimeout)
        if (wasActive) {
            clearSelection() // selection coordinates belong to the snapshot
            hist = null
            scrollPx = 0f
        }
        histPhase = HistoryPhase.IDLE; histPendingPx = 0f
        if (wasActive) onHistoryChanged?.invoke(false)
        invalidate()
    }

    /** Leaves the history view for the live screen (public for the floating "latest" button). */
    fun exitHistory() {
        stopFling()
        cancelHistory()
    }

    private fun maybeExitHistory() {
        if (histPhase == HistoryPhase.ACTIVE && HistoryMath.shouldExit(scrollPx, fling != null, touching)) exitHistory()
    }

    /** Applies [dy] px of scroll (+ = toward newer lines) to whichever path is active. Returns false at a local bound. */
    private fun scrollByPx(dy: Float, x: Float, y: Float, maxRows: Int): Boolean {
        val emu = session?.emulator ?: return false
        boost.touch()
        lastScrollX = x; lastScrollY = y
        if (histPhase == HistoryPhase.IDLE) {
            val fetcher = historyFetcher
            if (fetcher != null && HistoryMath.shouldBegin(dy, mouseActive && !emu.isAlternateBufferActive, true, android.os.SystemClock.uptimeMillis(), histFailUntilMs)) {
                val cached = freshCache()
                if (cached != null) {
                    enterHistory(cached, -dy) // instant: the gesture's distance is applied right away
                    return true
                }
                beginHistoryFetch(fetcher)
            }
        }
        if (histPhase == HistoryPhase.FETCHING) {
            histPendingPx = HistoryMath.addPending(histPendingPx, dy)
            invalidate()
            return true
        }
        if (hist == null && (mouseActive || emu.isAlternateBufferActive)) {
            wheelAcc.rowHeight = cellHeight.toFloat()
            val rows = wheelAcc.add(dy, maxRows)
            if (rows != 0) {
                probeBand(emu)
                if (mouseActive) {
                    val (c, r) = cellAt(x, y)
                    val button = if (rows > 0) TerminalEmulator.MOUSE_WHEELDOWN_BUTTON else TerminalEmulator.MOUSE_WHEELUP_BUTTON
                    repeat(abs(rows)) { emu.sendMouseEvent(button, c, r, true) }
                } else {
                    val key = if (rows > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
                    repeat(abs(rows)) { sendKey(key) }
                }
                unackedRows += rows
                removeCallbacks(clearUnacked); postDelayed(clearUnacked, UNACKED_TIMEOUT_MS)
            }
            invalidate()
            return true
        }
        val max = maxScrollPx
        val next = (scrollPx - dy).coerceIn(0f, max)
        val moved = next != scrollPx
        scrollPx = next
        if (moved) invalidate()
        return moved && next > 0f && next < max
    }

    private fun startFling(f: FlingDecay) {
        fling = f
        flingLastMs = android.os.SystemClock.uptimeMillis()
        postOnAnimation(frameStep)
    }

    private fun stopFling() {
        fling = null
        removeCallbacks(frameStep)
        releaseOffsetPx = 0f
    }

    /** Finger lifted without a fling: ease the sub-row leftovers away instead of jumping. */
    private fun endScrollGesture() {
        releaseOffsetPx = wheelAcc.remainder
        wheelAcc.reset()
        postOnAnimation(frameStep)
        invalidate()
        maybeExitHistory()
    }

    private val frameStep = object : Runnable {
        override fun run() {
            if (holdActive) endHold()
            boost.touch()
            val now = android.os.SystemClock.uptimeMillis()
            val dt = (now - flingLastMs).coerceIn(1L, 50L)
            flingLastMs = now
            var again = false
            val f = fling
            if (f != null) {
                if (f.finished) {
                    fling = null
                    endScrollGesture()
                    return
                }
                val ok = scrollByPx(f.advance(dt), flingX, flingY, MAX_FLING_ROWS)
                if (!ok) { fling = null; endScrollGesture(); return }
                again = true
            } else {
                // settle: leftover remote offset eases out; a local fractional position eases to the nearest row
                if (releaseOffsetPx != 0f) {
                    releaseOffsetPx = if (abs(releaseOffsetPx) < 1f) 0f else releaseOffsetPx * 0.6f
                    again = true
                }
                if (scrollPx > 0f && (hist != null || !(mouseActive || session?.emulator?.isAlternateBufferActive == true))) {
                    val target = (scrollPx / cellHeight).roundToInt() * cellHeight.toFloat()
                    val d = target - scrollPx
                    scrollPx = if (abs(d) < 1f) target else scrollPx + d * 0.4f
                    if (scrollPx != target) again = true
                }
                invalidate()
            }
            if (again) postOnAnimation(this) else maybeExitHistory()
        }
    }

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
        override fun onDown(e: MotionEvent): Boolean {
            stopFling()
            wheelAcc.reset()
            boost.touch()
            if (hist == null) detectFooter()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (paneMode && !isPaneFocused) {
                onFocusRequest?.invoke()
                return true
            }
            if (hist != null) { exitHistory(); return true } // tap outside a selection leaves the history view
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
            scrollByPx(dy, e2.x, e2.y, MAX_DRAG_ROWS)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (scaling || paneSwipeFired || abs(vy) < abs(vx)) return false
            if (session?.emulator == null) return false
            flingX = e2.x; flingY = e2.y
            startFling(FlingDecay(-vy))
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
            if (histPhase == HistoryPhase.FETCHING) cancelHistory()
            topRow = topRow // snap a pixel-scrolled position to a whole row so selection coordinates line up
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
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> touching = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touching = false
        }
        if (holdActive) endHold() // any user interaction (scroll, selection, zoom) shows the live state at once
        if (handleSelectionTouch(event)) return true
        scaleDetector.onTouchEvent(event)
        trackTwoFingerSwipe(event)
        trackPaneSwipe(event)
        if (!scaleDetector.isInProgress) gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            scaling = false
            if (fling == null) endScrollGesture()
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
        val emu = viewEmulator ?: return 0 to 0
        val footer = activeFooter()
        val header = activeHeader()
        val y = if (footer > 0 || header > 0) y.coerceIn(header * cellHeight.toFloat(), contentHeightPx(footer) - 1f) else y // pinned rows are not part of the scrolling content
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
        val emu = viewEmulator ?: return
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
        wheelAcc.reset()
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
        val cols = viewEmulator?.mColumns ?: return
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
        val emu = viewEmulator ?: return ""
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
                    scrollToBottom()
                    session?.pasteFromClipboard()
                    clearSelection()
                }
                MENU_SELECT_ALL -> {
                    val emu = viewEmulator ?: return true
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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(prefetchTick)
        postDelayed(prefetchTick, PREFETCH_TICK_MS)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(prefetchTick)
        dropHistoryCache()
        stopFling()
        cancelHistory()
        boost.release()
        holdActive = false
        removeCallbacks(holdCheck)
        ignoreUntilUp = false
        clearSelection()
        super.onDetachedFromWindow()
    }

    fun scrollToBottom() {
        if (hist != null || histPhase != HistoryPhase.IDLE) { exitHistory(); return } // typing / sending leaves the history view
        if (topRow != 0) {
            topRow = 0
            invalidate()
        }
    }

    companion object {
        private const val PREFETCH_TICK_MS = 500L
        private const val MAX_DRAG_ROWS = 8
        private const val MAX_FLING_ROWS = 4
        private const val UNACKED_TIMEOUT_MS = 140L
        private const val HOLD_QUIET_MS = 50L
        private const val HOLD_TIMEOUT_MS = 250L
        private const val MENU_COPY = 1
        private const val MENU_PASTE = 2
        private const val MENU_SELECT_ALL = 3
        private const val KeyCharacterMapCombining = 0x80000000.toInt() // KeyCharacterMap.COMBINING_ACCENT
    }
}
