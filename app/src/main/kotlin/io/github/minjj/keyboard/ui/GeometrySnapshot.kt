package io.github.minjj.keyboard.ui

import android.graphics.Rect
import android.view.View
import io.github.minjj.keyboard.ime.KeyCommand
import io.github.minjj.keyboard.ime.KeySpec
import io.github.minjj.keyboard.touch.CORRECTION_EDGE
import io.github.minjj.keyboard.touch.KeyIds
import io.github.minjj.keyboard.touch.KeyArea
import io.github.minjj.keyboard.touch.SpatialModel
import io.github.minjj.keyboard.touch.SpatialKey
import kotlin.math.abs

internal data class TouchKey(val view: View, val spec: KeySpec, val bounds: Rect, val toolbar: Boolean, val half: Int,
                             val spatialId: Int? = identity(spec)) {
    val area: KeyArea? = spatialId?.takeIf { !bounds.isEmpty }
        ?.let { KeyArea(it, bounds.left, bounds.top, bounds.right, bounds.bottom) }

    companion object {
        fun identity(spec: KeySpec): Int? = if (spec.command == KeyCommand.CHARACTER)
            spec.value.singleOrNull()?.let(KeyIds::character) else KeyIds.function(spec.command.name)
    }

    fun contains(x: Float, y: Float, slop: Int = 0): Boolean =
        x >= bounds.left - slop && x < bounds.right + slop &&
            y >= bounds.top - slop && y < bounds.bottom + slop

    fun distanceSquared(x: Float, y: Float): Float {
        val dx = maxOf(bounds.left - x, x - bounds.right, 0f)
        val dy = maxOf(bounds.top - y, y - bounds.bottom, 0f)
        return dx * dx + dy * dy
    }
}

/** All bounds are in keyboard coordinates and stay fixed for the life of this snapshot. */
internal data class GeometrySnapshot(val keys: List<TouchKey>, val keyGapLimit: Int, val verticalGapLimit: Int = keyGapLimit) {
    private class Row(val keys: List<TouchKey>) {
        val top = keys.first().bounds.top
        val bottom = keys.first().bounds.bottom
        val half = keys.first().half

        fun keyAtX(x: Float, gapLimit: Int): TouchKey? {
            keys.firstOrNull { x >= it.bounds.left && x < it.bounds.right }?.let { return it }
            for (index in 0 until keys.lastIndex) {
                val left = keys[index]
                val right = keys[index + 1]
                if (left.spec.command != KeyCommand.CHARACTER && right.spec.command != KeyCommand.CHARACTER) continue
                val gap = right.bounds.left - left.bounds.right
                if (gap in 1..gapLimit && x >= left.bounds.right && x < right.bounds.left) {
                    return if (x < left.bounds.right + gap / 2f) left else right
                }
            }
            return null
        }
    }

    private val rows = keys.filter { !it.bounds.isEmpty }.groupBy { Triple(it.bounds.top, it.bounds.bottom, it.half) }.values.map(::Row)
    private val adjacentRows = rows.groupBy { it.half }.values.flatMap { it.zipWithNext() }
    val spatialKeys: List<SpatialKey> = keys.mapNotNull { target -> target.area?.let { area ->
        SpatialKey(area, keys.filter { it.view.parent === target.view.parent && it.half == target.half &&
            it.area?.key?.let(KeyIds::kind) == KeyIds.kind(area.key) }.mapNotNull { it.area?.key }.toSet())
    } }
    private val spatialByKey = spatialKeys.associateBy { it.area.key }

    fun neighbors(literal: TouchKey, x: Float, y: Float): List<TouchKey> {
        val area = literal.area ?: return emptyList()
        val dx = area.dx(x)
        val dy = area.dy(y)
        if (abs(dx) < CORRECTION_EDGE && abs(dy) < CORRECTION_EDGE) return emptyList()
        return buildList {
            if (abs(dx) >= CORRECTION_EDGE) {
                val other = keys.getOrNull(keys.indexOf(literal) + if (dx < 0f) -1 else 1)
                if (other?.area != null && other.view.parent === literal.view.parent) add(other)
            }
            if (abs(dy) >= CORRECTION_EDGE) {
                for (other in keys) {
                    val otherArea = other.area ?: continue
                    if (other.half != literal.half || x < otherArea.left || x >= otherArea.right) continue
                    val gap = if (dy < 0f) area.top - otherArea.bottom else otherArea.top - area.bottom
                    if (gap in 0..verticalGapLimit) add(other)
                }
            }
        }
    }

    fun correctedKey(literal: TouchKey, neighbors: List<TouchKey>, x: Float, y: Float, profile: Int, day: Long,
                     model: SpatialModel, evidence: ((Int) -> Float)?): TouchKey {
        val area = literal.area ?: return literal
        val chosen = model.resolve(profile, day, spatialKey(area),
            neighbors.map { spatialKey(checkNotNull(it.area)) }, x, y, evidence)
        return neighbors.firstOrNull { it.area?.key == chosen } ?: literal
    }

    private fun spatialKey(area: KeyArea): SpatialKey {
        return checkNotNull(spatialByKey[area.key])
    }

    fun keyAt(x: Float, y: Float, allowKeyGaps: Boolean): TouchKey? {
        keys.firstOrNull { it.contains(x, y) }?.let { return it }
        if (!allowKeyGaps) return null
        for (row in rows) {
            if (y >= row.top && y < row.bottom) row.keyAtX(x, keyGapLimit)?.let { return it }
        }
        // A vertical gap must be bounded by keys in the same half on both rows.
        for ((upper, lower) in adjacentRows) {
            val gap = lower.top - upper.bottom
            if (gap !in 1..verticalGapLimit || y < upper.bottom || y >= lower.top) continue
            val above = upper.keyAtX(x, keyGapLimit) ?: continue
            val below = lower.keyAtX(x, keyGapLimit) ?: continue
            return if (above.distanceSquared(x, y) < below.distanceSquared(x, y)) above else below
        }
        return null
    }
}
