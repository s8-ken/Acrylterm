package com.shibaken.androidterminal.ui

import android.content.Context
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.ZoomInMap
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.shibaken.androidterminal.herdr.AgentStatus
import com.shibaken.androidterminal.herdr.FractionRect
import com.shibaken.androidterminal.herdr.HerdrController
import com.shibaken.androidterminal.herdr.HerdrState
import com.shibaken.androidterminal.session.PaneTerminal
import com.shibaken.androidterminal.session.SshTerminalSession
import com.shibaken.androidterminal.terminal.Modifiers
import com.shibaken.androidterminal.terminal.TerminalCanvasView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import com.shibaken.androidterminal.R

/**
 * One [TerminalCanvasView] per pane id, kept across recompositions and tab switches so views are
 * never recreated. Font size and direct-input mode are shared by all of them.
 */
@Stable
class PaneViewPool(
    private val context: Context,
    private val modifiers: Modifiers,
    initialTextSizeSp: Float,
    private val onTextSizePersist: (Float) -> Unit,
) {
    private val views = HashMap<String, TerminalCanvasView>()

    var textSizeSp by mutableFloatStateOf(initialTextSizeSp)
        private set

    var backgroundOpacity = 0.72f
        set(value) {
            field = value
            views.values.forEach { it.backgroundOpacity = value }
        }

    var directInput = false
        set(value) {
            field = value
            views.values.forEach { it.directInput = value }
        }

    fun get(paneId: String): TerminalCanvasView = views.getOrPut(paneId) {
        TerminalCanvasView(context).apply {
            this.modifiers = this@PaneViewPool.modifiers
            backgroundOpacity = this@PaneViewPool.backgroundOpacity
            paneMode = true
            isPaneFocused = false
            directInput = this@PaneViewPool.directInput
            textSizeSp = this@PaneViewPool.textSizeSp
            onTextSizeChanged = { size -> setTextSize(size) }
        }
    }

    private fun setTextSize(size: Float) {
        if (size == textSizeSp) return
        textSizeSp = size
        views.values.forEach { it.textSizeSp = size }
        onTextSizePersist(size)
    }

    /** Width of one cell in px (0 until a view exists). Reads [textSizeSp] so callers recompose on font changes. */
    fun cellWidthPx(): Float {
        textSizeSp
        return views.values.firstOrNull()?.cellWidthPx ?: 0f
    }

    fun prune(keep: Set<String>) {
        val it = views.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key !in keep) {
                e.value.session = null
                it.remove()
            }
        }
    }

    fun clear() {
        views.values.forEach { it.session = null }
        views.clear()
    }
}

private val FULL = FractionRect(0f, 0f, 1f, 1f)

/**
 * The focused tab's panes, placed by herdr's layout fractions. Falls back to the focused pane alone when the
 * tab is zoomed or a pane would get fewer than [PaneLogic.MIN_COLS] columns; then a one-finger horizontal
 * swipe pages through the tab's panes.
 */
@Composable
fun PaneLayout(
    session: SshTerminalSession,
    state: HerdrState,
    herdr: HerdrController,
    pool: PaneViewPool,
    onOpenComposer: () -> Unit,
    inputEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val backends by session.panes.collectAsState()
    val extEngine = session.ext.collectAsState().value?.engine
    val density = LocalDensity.current
    val overrides = remember { mutableStateMapOf<Int, Float>() }

    val allIds = remember(state.panes) { state.panes.mapTo(HashSet()) { it.paneId } }
    LaunchedEffect(allIds) { if (allIds.isNotEmpty()) pool.prune(allIds) }

    val focusedId = state.focusedPaneId
    focusedId?.let { pool.get(it) } // make sure a view exists so the cell width is known
    val layout = state.layout(state.focusedTabId)
    LaunchedEffect(layout) { overrides.clear() }
    LaunchedEffect(overrides.toMap()) {
        if (overrides.isNotEmpty()) { delay(2_500); overrides.clear() } // commit failed / no layout update
    }

    BoxWithConstraints(modifier) {
        val totalW = constraints.maxWidth.toFloat()
        val totalH = constraints.maxHeight.toFloat()
        val baseRects = layout?.normalizedPaneRects().orEmpty()
        val single = focusedId == null || layout == null || layout.zoomed || baseRects.size <= 1 ||
            focusedId !in baseRects || PaneLogic.needsSingleMode(baseRects.values, totalW, pool.cellWidthPx())

        val order = PaneLogic.paneOrder(baseRects)
        val pageSwipe = single && layout?.zoomed == false && order.size >= 2 && focusedId in order
        val pageIndex = order.indexOf(focusedId)

        val geoms = layout?.splits?.map { SplitGeom(layout.normalize(it.rect), it.direction == "right", it.ratio) }.orEmpty()
        fun effective(r: FractionRect): FractionRect {
            var x = r
            overrides.forEach { (i, nr) -> geoms.getOrNull(i)?.let { g -> x = PaneLogic.remap(x, g.rect, g.sideBySide, g.ratio, nr) } }
            return x
        }
        val shown: Map<String, FractionRect> = when {
            focusedId == null -> emptyMap()
            single -> mapOf(focusedId to FULL)
            else -> baseRects.mapValues { effective(it.value) }
        }
        val shownIds = remember(shown.keys.toSet()) { shown.keys.toSet() }
        LaunchedEffect(session, shownIds) { session.setVisiblePanes(shownIds) }
        DisposableEffect(session) { onDispose { session.setVisiblePanes(emptySet()) } }

        val gapPx = with(density) { 0.5.dp.toPx() }

        shown.forEach { (id, r) ->
            key(id) {
                val inset = if (single) 0f else gapPx
                val left = r.x * totalW + inset
                val top = r.y * totalH + inset
                val w = max(1f, r.width * totalW - 2 * inset)
                val h = max(1f, r.height * totalH - 2 * inset)
                val status = state.pane(id)?.agentStatus
                PaneItem(
                    ext = extEngine,
                    view = pool.get(id),
                    backend = backends[id],
                    focused = id == focusedId,
                    inputEnabled = inputEnabled,
                    outline = !single && id == focusedId,
                    blocked = status == AgentStatus.BLOCKED,
                    paneId = id,
                    herdr = herdr,
                    onOpenComposer = onOpenComposer,
                    onFocus = { herdr.focusPane(id) },
                    onDoubleTap = { herdr.toggleZoom(id) },
                    onSwipe = { herdr.focusTabRelative(it) },
                    onPaneSwipe = if (pageSwipe && id == focusedId) { dir ->
                        order.getOrNull((pageIndex + dir).coerceIn(0, order.size - 1))?.let { if (it != id) herdr.focusPane(it) }
                    } else null,
                    modifier = Modifier
                        .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                        .size(with(density) { w.toDp() }, with(density) { h.toDp() }),
                )
            }
        }

        // Divider drag handles (multi-pane only).
        if (!single) layout?.let { layout ->
            layout.splits.forEachIndexed { i, sp ->
                key(layout.tabId, i) {
                    val g = geoms[i]
                    val rect = effective(g.rect)
                    DividerHandle(
                        rect = rect, sideBySide = g.sideBySide, baseRatio = g.ratio, dragRatio = overrides[i],
                        totalW = totalW, totalH = totalH,
                        onRatio = { overrides[i] = it },
                        onCommit = { ratio ->
                            val path = PaneLogic.pathFromId(sp) ?: PaneLogic.splitPath(geoms, i)
                            herdr.setSplitRatio(layout.tabId, path, ratio)
                        },
                        onCancel = { overrides.remove(i) },
                    )
                }
            }
        }

        if (pageSwipe) {
            // Page dots: which pane of the tab is shown.
            Row(
                Modifier.align(Alignment.TopCenter).padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                order.forEachIndexed { i, _ ->
                    Box(
                        Modifier.size(if (i == pageIndex) 7.dp else 5.dp).align(Alignment.CenterVertically)
                            .background(Color.White.copy(alpha = if (i == pageIndex) 0.8f else 0.35f), CircleShape),
                    )
                }
            }
        }
        if (layout?.zoomed == true && focusedId != null) {
            IconButton(
                onClick = { herdr.toggleZoom(focusedId) },
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(36.dp)
                    .background(Color.Black.copy(alpha = 0.45f), CircleShape),
            ) { Icon(Icons.Filled.ZoomInMap, stringResource(R.string.herdrui_exit_zoom), Modifier.size(20.dp), tint = Color.White) }
        }
    }
}

@Composable
private fun PaneItem(
    ext: com.shibaken.androidterminal.ext.ExtEngine?,
    view: TerminalCanvasView,
    backend: PaneTerminal?,
    focused: Boolean,
    inputEnabled: Boolean,
    outline: Boolean,
    blocked: Boolean,
    paneId: String,
    herdr: HerdrController,
    onOpenComposer: () -> Unit,
    onFocus: () -> Unit,
    onDoubleTap: () -> Unit,
    onSwipe: (Int) -> Unit,
    onPaneSwipe: ((Int) -> Unit)?,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    var inHistory by remember(view) { mutableStateOf(view.inHistory) }
    // Claimed from the AndroidView factory/update too: those run before an outgoing item's onDispose, effects after it.
    val owner = remember(view, paneId) { Any() }
    DisposableEffect(view, paneId) {
        view.paneOwner = owner
        // Local history scroller: fetch the pane's scrollback from herdr instead of forwarding wheel events.
        view.historyFetcher = { lines, result -> scope.launch { result(herdr.readPaneHistory(paneId, lines)) } }
        view.onHistoryChanged = { inHistory = it }
        onDispose {
            // When the pane area is recomposed from scratch (e.g. the window widens), the new item takes over this
            // pooled view before the old one is disposed; unbinding here would leave the pane blank.
            if (view.paneOwner !== owner) return@onDispose
            view.paneOwner = null
            view.historyFetcher = null; view.onHistoryChanged = null
            view.exitHistory()
            view.session = null
            view.onFocusRequest = null; view.onDoubleTap = null; view.onTwoFingerSwipe = null
            view.onPaneSwipe = null
        }
    }
    val active = focused && inputEnabled
    LaunchedEffect(view, active) {
        // Move IME focus to this pane (no keyboard popup); also after a notification tap / overlay close.
        if (active) view.requestFocus()
    }
    val attached = backend?.attached?.collectAsState()?.value ?: false
    Box(modifier.clip(MaterialTheme.shapes.medium)) {
      Column(Modifier.fillMaxSize()) {
        AndroidView(
            factory = {
                (view.parent as? ViewGroup)?.removeView(view)
                view.paneOwner = owner
                view
            },
            modifier = Modifier.weight(1f).fillMaxWidth().terminalGutters(),
            update = { v ->
                v.paneOwner = owner
                if (v.session !== backend) v.session = backend
                v.importantForAccessibility = if (inputEnabled) android.view.View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                    else android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                v.isPaneFocused = active
                v.onFocusRequest = onFocus
                v.onDoubleTap = onDoubleTap
                v.onTwoFingerSwipe = onSwipe
                v.onPaneSwipe = onPaneSwipe
            },
        )
        if (focused && blocked) {
            QuickReplyBar(paneId, backend, herdr, ext, onOpenComposer, Modifier.fillMaxWidth().height(QuickReplyBarHeight))
        }
      }
        if (inHistory && focused) {
            IconButton(
                onClick = { view.exitHistory() },
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).size(40.dp)
                    .background(Color.Black.copy(alpha = 0.55f), CircleShape),
            ) { Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.herdrui_jump_latest), Modifier.size(24.dp), tint = Color.White) }
        }
        if (focused && !attached) {
            // Not attached yet: input would be dropped. Dim + small spinner (no text).
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)))
            val connectingDesc = stringResource(R.string.herdrui_connecting_desc)
            CircularProgressIndicator(
                Modifier.align(Alignment.TopEnd).padding(8.dp).size(18.dp).semantics { contentDescription = connectingDesc },
                strokeWidth = 2.dp,
            )
        }
        if (outline) {
            Box(Modifier.fillMaxSize().border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f), MaterialTheme.shapes.medium))
        }
    }
}

/**
 * Invisible >=24dp-wide drag target on a split's divider. The handle itself stays at the committed
 * position while dragging (moving it under the finger would corrupt the drag deltas); a guide line shows
 * the live ratio and the panes re-lay out through [onRatio].
 */
@Composable
private fun DividerHandle(
    rect: FractionRect,
    sideBySide: Boolean,
    baseRatio: Float,
    dragRatio: Float?,
    totalW: Float,
    totalH: Float,
    onRatio: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    onCancel: () -> Unit,
) {
    val density = LocalDensity.current
    val handlePx = with(density) { 24.dp.toPx() }
    val sizePx = if (sideBySide) rect.width * totalW else rect.height * totalH
    val x: Float
    val y: Float
    val w: Float
    val h: Float
    if (sideBySide) {
        x = (rect.x + rect.width * baseRatio) * totalW - handlePx / 2; y = rect.y * totalH
        w = handlePx; h = rect.height * totalH
    } else {
        x = rect.x * totalW; y = (rect.y + rect.height * baseRatio) * totalH - handlePx / 2
        w = rect.width * totalW; h = handlePx
    }
    val curBase = rememberUpdatedState(baseRatio)
    val curSize = rememberUpdatedState(sizePx)
    val curRatio = rememberUpdatedState(onRatio)
    val curCommit = rememberUpdatedState(onCommit)
    val curCancel = rememberUpdatedState(onCancel)
    Box(
        Modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .size(with(density) { w.toDp() }, with(density) { h.toDp() })
            .pointerInput(sideBySide) {
                var start = 0f
                var acc = 0f
                var latest = 0f
                detectDragGestures(
                    onDragStart = { start = curBase.value; acc = 0f; latest = start },
                    onDragEnd = { curCommit.value(latest) },
                    onDragCancel = { curCancel.value() },
                ) { change, amount ->
                    change.consume()
                    acc += if (sideBySide) amount.x else amount.y
                    if (curSize.value > 0f) {
                        latest = PaneLogic.clampRatio(start + acc / curSize.value)
                        curRatio.value(latest)
                    }
                }
            },
    ) {
        if (dragRatio != null) {
            val shift = (dragRatio - baseRatio) * sizePx
            val line = MaterialTheme.colorScheme.primary
            if (sideBySide) {
                Box(Modifier.align(Alignment.Center).offset { IntOffset(shift.roundToInt(), 0) }.fillMaxHeight().width(3.dp).background(line))
            } else {
                Box(Modifier.align(Alignment.Center).offset { IntOffset(0, shift.roundToInt()) }.size(with(density) { w.toDp() }, 3.dp).background(line))
            }
        }
    }
}
