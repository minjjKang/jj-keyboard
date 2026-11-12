package io.github.minjj.keyboard.touch

import kotlin.math.abs
import kotlin.math.roundToInt

const val PHYSICAL_LETTERS = "qwertyuiopasdfghjklzxcvbnm"
const val CORRECTION_EDGE = 0.38f
private const val SCALE = 1000
private const val MIN_COUNT = 30f

data class TouchSample(val key: Int, val dx: Int, val dy: Int, val guided: Boolean = false)
data class KeyArea(val key: Int, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    fun dx(x: Float) = (x - (left + width / 2f)) / width
    fun dy(y: Float) = (y - (top + height / 2f)) / height
    fun centerSample(x: Float, y: Float): TouchSample? {
        val dx = dx(x); val dy = dy(y)
        if (abs(dx) > .20f || abs(dy) > .20f) return null
        return TouchSample(key, (dx * SCALE).roundToInt(), (dy * SCALE).roundToInt())
    }
}
data class SpatialKey(val area: KeyArea, val rowKeys: Set<Int>) {
    constructor(area: KeyArea, rowMask: Int) : this(area, KeyIds.legacyRow(rowMask))
    val rowMask get() = rowKeys.filter { it < 26 }.fold(0) { mask, key -> mask or (1 shl key) }
}
data class LayoutProfile(val id: Int, val layout: LayoutDescriptor?, val lastUsed: Long, val blocked: Boolean = false)
data class KeySummary(val normal: Int, val guided: Int, val effective: Float, val rowEffective: Float,
                      val meanX: Float, val meanY: Float, val offsetX: Float, val offsetY: Float,
                      val reference: Int?) {
    val stage get() = when {
        effective >= MIN_COUNT -> 3
        rowEffective >= MIN_COUNT -> 2
        normal + guided > 0 -> 1
        else -> 0
    }
}
private data class Aggregate(val profile: Int, val day: Long, val key: Int, val count: Int,
                             val x: Long, val y: Long, val guided: Boolean)
private data class Totals(val normal: Int = 0, val guided: Int = 0, val x: Float = 0f, val y: Float = 0f) {
    val effective get() = normal * .35f + guided
    operator fun plus(a: Aggregate): Totals {
        val weight = if (a.guided) 1f else .35f
        return Totals(normal + if (a.guided) 0 else a.count, guided + if (a.guided) a.count else 0,
            x + a.x * weight / SCALE, y + a.y * weight / SCALE)
    }
}

/** Bounded statistics and per-key relative points. Never contains text or cross-key input sequences. */
private data class StoredPoint(val dx: Int, val dy: Int, val day: Long)

class SpatialModel private constructor(val layouts: List<LayoutProfile>, private val aggregates: List<Aggregate>,
                                     private val clouds: Map<Pair<Int, Int>, List<StoredPoint>> = emptyMap()) {
    constructor() : this(emptyList(), emptyList())
    private data class SummaryKey(val profile: Int, val day: Long, val key: Int, val row: Set<Int>)
    private val densities = java.util.concurrent.ConcurrentHashMap<Triple<Int, Long, Int>, TouchDensity>()
    private val summaries = java.util.concurrent.ConcurrentHashMap<SummaryKey, KeySummary>()

    fun select(layout: LayoutDescriptor, current: Int? = null): Int? {
        val candidates = layouts.mapNotNull { p -> p.layout?.distance(layout)?.let { Triple(p.id, it, p.id == current) } }
        val best = candidates.minWithOrNull(compareBy<Triple<Int, Float, Boolean>> { it.second }.thenBy { it.first }) ?: return null
        return candidates.firstOrNull { it.third && it.second <= best.second + .02f }?.first ?: best.first
    }

    fun withLayout(id: Int, layout: LayoutDescriptor, day: Long): SpatialModel {
        require(id in 128..1_000_000 && day in 0..1_000_000)
        if (layouts.any { it.id == id }) return this
        val saved = layouts.filter { it.lastUsed >= 0 }
        val transient = (layouts.filter { it.lastUsed < 0 } + LayoutProfile(id, layout, -1)).takeLast(8)
        val next = saved + transient
        val ids = next.map { it.id }.toSet()
        return SpatialModel(next, aggregates.filter { it.profile in ids }, clouds.filterKeys { it.first in ids })
    }

    fun removed(id: Int, keepLayout: Boolean = false): SpatialModel = SpatialModel(
        layouts.mapNotNull { if (it.id != id) it else if (keepLayout) it.copy(blocked = true, lastUsed = maxOf(0, it.lastUsed)) else null },
        aggregates.filter { it.profile != id }, clouds.filterKeys { it.first != id })

    fun updated(profile: Int, day: Long, samples: List<TouchSample>): SpatialModel {
        require(profile in 0..1_000_000 && day in 0..1_000_000)
        require(samples.all { KeyIds.valid(it.key) && abs(it.dx.toLong()) <= (if (it.guided) 720 else 200) &&
            abs(it.dy.toLong()) <= (if (it.guided) 720 else 200) })
        var entries = aggregates.filter { day - it.day in 0..29 }.toMutableList()
        for (s in samples) {
            val last = entries.indexOfLast { it.profile == profile && it.key == s.key && it.day == day && it.guided == s.guided && it.count < 16 }
            if (last >= 0) {
                val a = entries[last]
                entries[last] = a.copy(count = a.count + 1, x = a.x + s.dx, y = a.y + s.dy)
            } else entries.add(Aggregate(profile, day, s.key, 1, s.dx.toLong(), s.dy.toLong(), s.guided))
        }
        // Eight small buckets per key preserve rare keys instead of evicting them for common letters.
        entries = entries.groupBy { it.profile to it.key }.values.flatMap { it.takeLast(8) }.toMutableList()
        val profiles = (layouts.filter { it.id != profile && it.lastUsed >= 0 } +
            (layouts.firstOrNull { it.id == profile } ?: LayoutProfile(profile, null, day)).copy(lastUsed = day, blocked = false))
            .takeLast(24)
        val ids = profiles.map { it.id }.toSet()
        val nextClouds = clouds.filterKeys { it.first in ids }.mapValues { (_, points) -> points.filter { day - it.day in 0..29 } }.toMutableMap()
        samples.groupBy { it.key }.forEach { (key, added) ->
            val id = profile to key
            nextClouds[id] = (nextClouds[id].orEmpty() + added.map { StoredPoint(it.dx, it.dy, day) }).takeLast(100)
        }
        val previews = layouts.filter { it.lastUsed < 0 && it.id != profile }.takeLast(8)
        return SpatialModel(profiles + previews, entries.filter { a -> a.profile in ids }, nextClouds.filterValues { it.isNotEmpty() })
    }

    private fun totals(profile: Int, day: Long, keys: Set<Int>): Totals = aggregates.fold(Totals()) { acc, a ->
        if (a.profile == profile && day - a.day in 0..29 && a.key in keys) acc + a else acc
    }

    fun summary(profile: Int, day: Long, key: Int, rowMask: Int): KeySummary = summary(profile, day, key, KeyIds.legacyRow(rowMask))

    fun summary(profile: Int, day: Long, key: Int, rowKeys: Set<Int>): KeySummary {
        val cacheKey = SummaryKey(profile, day, key, rowKeys)
        summaries[cacheKey]?.let { return it }
        val result = calculateSummary(profile, day, key, rowKeys)
        if (summaries.size < 4096) summaries[cacheKey] = result
        return result
    }

    private fun calculateSummary(profile: Int, day: Long, key: Int, rowKeys: Set<Int>): KeySummary {
        val direct = totals(profile, day, setOf(key)); val row = totals(profile, day, rowKeys)
        val p = layouts.firstOrNull { it.id == profile }
        val reference = if (p?.blocked == false && p.layout != null && row.effective < MIN_COUNT) {
            val comparable = layouts.filter { it.id != profile && it.layout != null && !it.blocked }
                .mapNotNull { other -> other.layout!!.distance(p.layout, true)?.let { other to it } }
                .sortedWith(compareBy<Pair<LayoutProfile, Float>> { it.second }.thenBy { it.first.id })
            comparable.firstOrNull { totals(it.first.id, day, rowKeys).effective >= MIN_COUNT }?.first?.id
                ?: layouts.firstOrNull { it.layout == null && it.id == p.layout.family && !it.blocked &&
                    totals(it.id, day, rowKeys).effective >= MIN_COUNT }?.id
        } else null
        fun offset(k: Totals, r: Totals, axis: Boolean): Float {
            if (r.effective < MIN_COUNT) return 0f
            val rowOffset = (if (axis) r.x else r.y) / (r.effective + 50f)
            return (if (k.effective < MIN_COUNT) rowOffset else
                ((if (axis) k.x else k.y) + rowOffset * 50f) / (k.effective + 50f)).coerceIn(-.15f, .15f)
        }
        val referenceKey = reference?.let { totals(it, day, setOf(key)) }
        val referenceRow = reference?.let { totals(it, day, rowKeys) }
        fun combined(axis: Boolean): Float = (offset(direct, row, axis) + if (referenceKey != null && referenceRow != null)
            offset(referenceKey, referenceRow, axis) * .25f * (1f - row.effective / MIN_COUNT) else 0f).coerceIn(-.15f, .15f)
        return KeySummary(direct.normal, direct.guided, direct.effective, row.effective,
            if (direct.effective > 0) direct.x / direct.effective else 0f,
            if (direct.effective > 0) direct.y / direct.effective else 0f, combined(true), combined(false), reference)
    }

    fun density(profile: Int, day: Long, key: Int): TouchDensity {
        val cacheKey = Triple(profile, day, key)
        densities[cacheKey]?.let { return it }
        val points = clouds[profile to key].orEmpty().filter { day - it.day in 0..29 }.map { DensityPoint(it.dx / 1000f, it.dy / 1000f) }
        val result = TouchDensity.from(points)
        if (densities.size < 4096) densities[cacheKey] = result
        return result
    }

    fun prepared(profile: Int, day: Long, keys: List<SpatialKey>): SpatialResolver {
        val summaries = HashMap<Int, KeySummary>()
        keys.forEach { summaries[it.area.key] = summary(profile, day, it.area.key, it.rowKeys) }
        return SpatialResolver { checkNotNull(summaries[it.area.key]) }
    }

    fun resolve(profile: Int, day: Long, literal: SpatialKey, neighbors: List<SpatialKey>, x: Float, y: Float,
                evidence: ((Int) -> Float)? = null): Int =
        SpatialResolver { summary(profile, day, it.area.key, it.rowKeys) }.resolve(literal, neighbors, x, y, evidence)

    fun encode(): String = buildString {
        appendLine("touch-bias-v4")
        for (p in layouts.filter { it.lastUsed >= 0 }) {
            appendLine("P ${p.id} ${p.lastUsed} ${if (p.blocked) 1 else 0} ${p.layout?.encode() ?: "-"}")
        }
        for (a in aggregates) appendLine("B ${a.profile} ${a.day} ${a.key} ${a.count} ${a.x} ${a.y} ${if (a.guided) 1 else 0}")
        for ((id, points) in clouds.entries.sortedWith(compareBy({ it.key.first }, { it.key.second }))) {
            if (points.isEmpty()) continue
            val day = points.maxOf { it.day }
            val bytes = java.nio.ByteBuffer.allocate(points.size * 4)
            points.forEach { p -> bytes.putInt(((day - p.day).toInt() shl 22) or ((p.dx + 720) shl 11) or (p.dy + 720)) }
            appendLine("D ${id.first} ${id.second} $day ${java.util.Base64.getEncoder().encodeToString(bytes.array())}")
        }
    }

    companion object {
        fun decode(encoded: String): SpatialModel {
            require(encoded.length <= 2 * 1024 * 1024)
            val lines = encoded.lineSequence().filter { it.isNotBlank() }.iterator()
            require(lines.hasNext())
            val header = lines.next(); require(header in setOf("touch-bias-v1", "touch-bias-v2", "touch-bias-v3", "touch-bias-v4"))
            val profiles = mutableListOf<LayoutProfile>(); val data = mutableListOf<Aggregate>()
            val clouds = mutableMapOf<Pair<Int, Int>, List<StoredPoint>>()
            for (line in lines) {
                val f = line.split(' ')
                if (header == "touch-bias-v1") {
                    require(f.size in 6..106 && (f.size - 2) % 4 == 0)
                    val id = f[0].toInt(); val day = f[1].toLong()
                    require(id in 0..127 && day in 0..1_000_000)
                    if (profiles.none { it.id == id }) profiles.add(LayoutProfile(id, null, day))
                    var countTotal = 0
                    val keys = mutableSetOf<Int>()
                    for (i in 2 until f.size step 4) {
                        val k = f[i].toInt(); val n = f[i + 1].toInt(); val x = f[i + 2].toLong(); val y = f[i + 3].toLong()
                        require(k in PHYSICAL_LETTERS.indices && keys.add(k) && n in 1..100 &&
                            x in (-200L * n)..(200L * n) && y in (-200L * n)..(200L * n))
                        countTotal += n
                        // Preserve aggregate means while reducing legacy buckets to bounded chunks.
                        var used = 0
                        while (used < n) {
                            val c = minOf(16, n - used)
                            data.add(Aggregate(id, day, k, c, x * (used + c) / n - x * used / n,
                                y * (used + c) / n - y * used / n, false)); used += c
                        }
                    }
                    require(countTotal <= 100)
                } else if (f.firstOrNull() == "P") {
                    require(f.size == 5)
                    val id = f[1].toInt(); val day = f[2].toLong(); val blocked = f[3].toInt()
                    require(id in 0..1_000_000 && profiles.none { it.id == id } && day in 0..1_000_000 && blocked in 0..1)
                    val layout = if (f[4] == "-") null else LayoutDescriptor.decode(f[4])
                    require(layout == null || id >= 128)
                    profiles.add(LayoutProfile(id, layout, day, blocked == 1))
                } else if (f.firstOrNull() == "D") {
                    require(header == "touch-bias-v4" && f.size == 5)
                    val id = f[1].toInt(); val key = f[2].toInt(); val day = f[3].toLong()
                    require(profiles.any { it.id == id } && KeyIds.valid(key) && day in 0..1_000_000 && f[4].length <= 536 && (id to key) !in clouds)
                    val bytes = java.util.Base64.getDecoder().decode(f[4])
                    require(bytes.size in 4..400 && bytes.size % 4 == 0)
                    val buffer = java.nio.ByteBuffer.wrap(bytes)
                    clouds[id to key] = List(bytes.size / 4) {
                        val packed = buffer.int
                        val age = packed ushr 22; val dx = (packed ushr 11 and 2047) - 720; val dy = (packed and 2047) - 720
                        require(age in 0..29 && day >= age && dx in -720..720 && dy in -720..720)
                        StoredPoint(dx, dy, day - age)
                    }
                    require(clouds.size <= 24 * 100)
                } else {
                    require(f.size == 8 && f[0] == "B")
                    val id = f[1].toInt(); val day = f[2].toLong(); val k = f[3].toInt(); val n = f[4].toInt()
                    val x = f[5].toLong(); val y = f[6].toLong(); val g = f[7].toInt()
                    require(profiles.any { it.id == id } && day in 0..1_000_000 && (if (header == "touch-bias-v2") k in PHYSICAL_LETTERS.indices else KeyIds.valid(k)) && n in 1..16 && g in 0..1)
                    val limit = (if (g == 1) 720L else 200L) * n
                    require(x in -limit..limit && y in -limit..limit)
                    data.add(Aggregate(id, day, k, n, x, y, g == 1))
                }
                require(profiles.size <= 24 && data.size <= 24 * 100 * 64)
            }
            val bounded = data.groupBy { it.profile to it.key }.values.flatMap { it.takeLast(8) }
            if (header != "touch-bias-v1") require(data.size == bounded.size)
            return SpatialModel(profiles, bounded, clouds)
        }
    }
}

/** Shared decision kernel for live taps and map rendering. Prepared summaries avoid per-pixel model lookups. */
class SpatialResolver internal constructor(private val summary: (SpatialKey) -> KeySummary) {
    fun resolve(literal: SpatialKey, neighbors: List<SpatialKey>, x: Float, y: Float,
                evidence: ((Int) -> Float)? = null): Int {
        val area = literal.area
        fun score(key: SpatialKey): Float? {
            val s = summary(key)
            if ((KeyIds.isFunction(key.area.key) && s.effective < MIN_COUNT) || (s.rowEffective < MIN_COUNT && s.reference == null)) return null
            val dx = key.area.dx(x) - s.offsetX; val dy = key.area.dy(y) - s.offsetY
            return dx * dx + dy * dy
        }
        val literalScore = score(literal)
        if (literalScore == null && evidence == null) return area.key
        val literalEvidence = evidence?.invoke(area.key) ?: 0f
        var bestKey = area.key; var bestGain = 0f
        for (neighbor in neighbors) {
            val other = neighbor.area
            val sameRow = area.top == other.top && area.bottom == other.bottom
            val nearEdge = if (sameRow) abs(area.dx(x)) >= CORRECTION_EDGE && abs(other.dx(x)) <= .72f &&
                abs(area.dy(y)) <= .5f && abs(other.dy(y)) <= .5f else
                abs(area.dy(y)) >= CORRECTION_EDGE && abs(other.dy(y)) <= .72f && abs(area.dx(x)) <= .5f && abs(other.dx(x)) <= .5f
            if (!nearEdge) continue
            val neighborScore = score(neighbor)
            if ((KeyIds.isFunction(area.key) || KeyIds.isFunction(other.key)) &&
                (summary(literal).effective < MIN_COUNT || summary(neighbor).effective < MIN_COUNT)) continue
            val languageGain = if (area.key < 26 && other.key < 26) (evidence?.invoke(other.key) ?: 0f) - literalEvidence else 0f
            val spatialGain = if (literalScore != null && neighborScore != null) literalScore - neighborScore else {
                if (languageGain <= 0f) continue
                area.dx(x) * area.dx(x) + area.dy(y) * area.dy(y) - other.dx(x) * other.dx(x) - other.dy(y) * other.dy(y)
            }
            val gain = spatialGain + languageGain - if (sameRow) .015f else .025f
            if (gain > bestGain) { bestGain = gain; bestKey = other.key }
        }
        return bestKey
    }

}
