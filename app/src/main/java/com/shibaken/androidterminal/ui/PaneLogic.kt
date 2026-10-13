package com.shibaken.androidterminal.ui

import com.shibaken.androidterminal.herdr.FractionRect
import com.shibaken.androidterminal.herdr.LayoutSplit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.math.roundToInt

/** Geometry of one split in 0..1 fractions of the tab area. [sideBySide] = children are left/right. */
data class SplitGeom(val rect: FractionRect, val sideBySide: Boolean, val ratio: Float)

/** Pure helpers for the multi-pane screen (kept free of Android types so they are unit-testable). */
object PaneLogic {
    const val MIN_COLS = 40
    const val MIN_RATIO = 0.1f
    const val MAX_RATIO = 0.9f
    private const val EPS = 0.002f

    /** True when at least one pane would get fewer than [minCols] columns, so only the focused pane should be shown. */
    fun needsSingleMode(rects: Collection<FractionRect>, totalWidthPx: Float, cellWidthPx: Float, minCols: Int = MIN_COLS): Boolean {
        if (rects.size <= 1 || totalWidthPx <= 0f || cellWidthPx <= 0f) return false
        return rects.any { it.width * totalWidthPx / cellWidthPx < minCols }
    }

    /** Pane ids in reading order of their layout rects: top to bottom, then left to right. */
    fun paneOrder(rects: Map<String, FractionRect>): List<String> =
        rects.entries.sortedWith(compareBy({ (it.value.y / EPS).roundToInt() }, { it.value.x })).map { it.key }

    /**
     * Maps [r] (which must lie inside [split]) after the split's divider moved from [oldRatio] to [newRatio].
     * Rects outside the split are returned unchanged.
     */
    fun remap(r: FractionRect, split: FractionRect, sideBySide: Boolean, oldRatio: Float, newRatio: Float): FractionRect {
        val inside = r.x >= split.x - EPS && r.y >= split.y - EPS &&
            r.x + r.width <= split.x + split.width + EPS && r.y + r.height <= split.y + split.height + EPS
        if (!inside) return r
        return if (sideBySide) {
            val a = mapAxis(r.x, split.x, split.width, oldRatio, newRatio)
            val b = mapAxis(r.x + r.width, split.x, split.width, oldRatio, newRatio)
            r.copy(x = a, width = b - a)
        } else {
            val a = mapAxis(r.y, split.y, split.height, oldRatio, newRatio)
            val b = mapAxis(r.y + r.height, split.y, split.height, oldRatio, newRatio)
            r.copy(y = a, height = b - a)
        }
    }

    private fun mapAxis(v: Float, start: Float, size: Float, oldRatio: Float, newRatio: Float): Float {
        val b0 = start + size * oldRatio
        val b1 = start + size * newRatio
        val end = start + size
        return if (v <= b0) {
            if (b0 - start <= 0f) start else start + (v - start) * (b1 - start) / (b0 - start)
        } else {
            if (end - b0 <= 0f) end else end - (end - v) * (end - b1) / (end - b0)
        }
    }

    /**
     * Path from the root split to split [index] for `layout.set_split_ratio`: one entry per ancestor,
     * false = first child, true = second child. Ancestors are the splits whose rect strictly contains this one.
     */
    fun splitPath(splits: List<SplitGeom>, index: Int): List<Boolean> {
        val s = splits[index]
        val cx = s.rect.x + s.rect.width / 2
        val cy = s.rect.y + s.rect.height / 2
        return splits.withIndex()
            .filter { (i, a) ->
                i != index && a.rect.width * a.rect.height > s.rect.width * s.rect.height &&
                    s.rect.x >= a.rect.x - EPS && s.rect.y >= a.rect.y - EPS &&
                    s.rect.x + s.rect.width <= a.rect.x + a.rect.width + EPS &&
                    s.rect.y + s.rect.height <= a.rect.y + a.rect.height + EPS
            }
            .sortedByDescending { (_, a) -> a.rect.width * a.rect.height }
            .map { (_, a) ->
                if (a.sideBySide) cx > a.rect.x + a.rect.width * a.ratio else cy > a.rect.y + a.rect.height * a.ratio
            }
    }

    /** If herdr reports the split id as a boolean array it already is the path. */
    fun pathFromId(split: LayoutSplit): List<Boolean>? {
        val arr = split.id as? JsonArray ?: return null
        val out = arr.map { ((it as? JsonPrimitive)?.booleanOrNull) ?: return null }
        return out
    }

    fun clampRatio(r: Float): Float = r.coerceIn(MIN_RATIO, MAX_RATIO)
}
