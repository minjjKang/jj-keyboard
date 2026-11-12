package io.github.minjj.keyboard.ui

import android.graphics.Color
import android.graphics.RectF
import io.github.minjj.keyboard.touch.*

/** Geometry is prepared once; typing only reevaluates pixels near a possible correction boundary. */
internal object TouchMapRaster {
    private val colors = IntArray(KeyIds.LIMIT) { Color.HSVToColor(95, floatArrayOf(if (it < 26) it * 360f / 26 else (it * 137.5f) % 360f, .35f, .9f)) }
    private val adaptiveColors = Array(4) { style -> IntArray(KeyIds.LIMIT) { id ->
        val boundary = style and 1 != 0; val changed = style and 2 != 0
        val hue = if (id < 26) id * 360f / 26 else (id * 137.5f) % 360f
        Color.HSVToColor(if (changed) 230 else if (boundary) 220 else 65,
            floatArrayOf(hue, if (boundary || changed) .7f else .24f, if (boundary) .65f else .95f))
    } }
    data class Variant(val literal: SpatialKey, val neighbors: List<SpatialKey>)
    class Plan(val width: Int, val height: Int, val base: IntArray, val types: IntArray,
               val boundaries: IntArray, val variants: List<Variant>, val xs: FloatArray, val ys: FloatArray,
               val keys: List<SpatialKey>, val baseOwners: IntArray)

    fun prepare(geometry: GeometrySnapshot, viewport: RectF, width: Int, height: Int, density: Float,
                checkCancelled: () -> Unit = {}): Plan {
        val base = IntArray(width * height)
        val owners = IntArray(base.size) { -1 }
        val types = IntArray(base.size)
        val positions = IntArray(base.size)
        var count = 0
        val variants = ArrayList<Variant>()
        val variantIds = HashMap<Pair<Int, List<Int>>, Int>()
        val spatial = geometry.spatialKeys.associateBy { it.area.key }
        val xs = FloatArray(width) { (viewport.left + (it + .5f) * viewport.width() / width) * density }
        val ys = FloatArray(height) { (viewport.top + (it + .5f) * viewport.height() / height) * density }
        for (y in 0 until height) {
            checkCancelled()
            val py = ys[y]
            for (x in 0 until width) {
                val px = xs[x]
                val literal = geometry.keyAt(px, py, true) ?: continue
                val key = literal.area?.key ?: continue
                val index = y * width + x
                base[index] = colors[key]; owners[index] = key
                val neighbors = geometry.neighbors(literal, px, py)
                if (neighbors.isEmpty()) continue
                val signature = key to neighbors.map { it.area!!.key }
                types[index] = variantIds.getOrPut(signature) {
                    variants.add(Variant(spatial.getValue(key), neighbors.map { spatial.getValue(it.area!!.key) }))
                    variants.lastIndex
                }
                positions[count++] = index
            }
        }
        return Plan(width, height, base, types, positions.copyOf(count), variants, xs, ys, geometry.spatialKeys, owners)
    }

    fun decisions(plan: Plan, model: SpatialModel, profile: Int, day: Long, personal: Boolean,
                  evidence: FloatArray? = null, checkCancelled: () -> Unit = {}): IntArray {
        val pixels = plan.baseOwners.copyOf()
        val active = personal && plan.keys.any { key -> model.summary(profile, day, key.area.key, key.rowKeys).let {
            it.rowEffective >= 30f || it.reference != null
        } }
        if (!active && evidence == null) return pixels
        val resolver = (if (personal) model else SpatialModel()).prepared(profile, day, plan.keys)
        val language: ((Int) -> Float)? = evidence?.let { values -> { key -> values.getOrElse(key) { 0f } } }
        for ((ordinal, index) in plan.boundaries.withIndex()) {
            if (ordinal and 1023 == 0) checkCancelled()
            val v = plan.variants[plan.types[index]]
            pixels[index] = resolver.resolve(v.literal, v.neighbors, plan.xs[index % plan.width],
                plan.ys[index / plan.width], language)
        }
        return pixels
    }

    fun render(plan: Plan, model: SpatialModel, profile: Int, day: Long, personal: Boolean,
               evidence: FloatArray? = null, checkCancelled: () -> Unit = {}): IntArray {
        val owners = decisions(plan, model, profile, day, personal, evidence, checkCancelled)
        for (i in owners.indices) owners[i] = if (owners[i] < 0) 0 else colors[owners[i]]
        return owners
    }

    fun adaptivePixels(plan: Plan, owners: IntArray): IntArray = IntArray(owners.size) { i ->
        val id = owners[i]
        if (id < 0) 0 else {
            val x = i % plan.width; val y = i / plan.width
            val boundary = x == 0 || y == 0 || x == plan.width - 1 || y == plan.height - 1 ||
                owners[i - 1] != id || owners[i + 1] != id || owners[i - plan.width] != id || owners[i + plan.width] != id
            val changed = id != plan.baseOwners[i]
            adaptiveColors[(if (changed) 2 else 0) + (if (boundary) 1 else 0)][id]
        }
    }

    fun letterAt(geometry: GeometrySnapshot, model: SpatialModel, profile: Int, day: Long,
                 x: Float, y: Float, personal: Boolean, evidence: ((Int) -> Float)? = null): Int? {
        val literal = geometry.keyAt(x, y, true) ?: return null
        if (literal.area == null) return null
        val neighbors = geometry.neighbors(literal, x, y)
        return if ((personal || evidence != null) && neighbors.isNotEmpty()) geometry.correctedKey(literal, neighbors, x, y,
            profile, day, if (personal) model else SpatialModel(), evidence).area?.key else literal.area.key
    }

    fun render(geometry: GeometrySnapshot, model: SpatialModel, profile: Int, day: Long,
               viewport: RectF, width: Int, height: Int, density: Float, personal: Boolean,
               checkCancelled: () -> Unit = {}): IntArray =
        render(prepare(geometry, viewport, width, height, density, checkCancelled), model, profile, day, personal,
            checkCancelled = checkCancelled)
}
