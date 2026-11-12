package io.github.minjj.keyboard.touch

import kotlin.math.abs

/** Measured dp geometry, never requested slider values. The anchor remains immutable. */
data class LayoutKey(val key: Int, val left: Float, val top: Float, val right: Float, val bottom: Float,
                     val row: Int, val half: Int) {
    val width get() = right - left
    val height get() = bottom - top
    fun area() = KeyArea(key, (left * 100).toInt(), (top * 100).toInt(), (right * 100).toInt(), (bottom * 100).toInt())
}

data class LayoutDescriptor(val family: Int, val width: Float, val height: Float, val screenX: Float,
                            val screenY: Float, val horizontalGap: Float, val verticalGap: Float,
                            val splitGap: Float, val keys: List<LayoutKey>, val surfaces: List<LayoutKey> = emptyList(),
                            val pixelDensity: Float = 0f, val page: Int = 0, val inputKind: Int = 0) {
    val korean get() = family and 32 != 0
    val landscape get() = family and 4 != 0
    val split get() = family and 8 != 0
    init {
        require(pixelDensity.isFinite() && pixelDensity in 0f..16f)
        require(family in 0..127 && page in 0..2 && inputKind in 0..3)
        require(keys.none { it.key in 0..25 } || keys.map { it.key }.containsAll(PHYSICAL_LETTERS.indices.toList()))
        require(keys.size in 1..100 && keys.all { KeyIds.valid(it.key) } && keys.map { it.key }.toSet().size == keys.size)
        require(listOf(width, height, screenX, screenY, horizontalGap, verticalGap, splitGap).all { it.isFinite() && it in 0f..10000f })
        require(width > 0 && height > 0)
        require(keys.all { k -> listOf(k.left, k.top, k.right, k.bottom).all { it.isFinite() && it in 0f..10000f } &&
            k.width > 0 && k.height > 0 && k.row in 0..12 && k.half in 0..1 })
        require(surfaces.size <= 100 && surfaces.all { k -> (k.key in -2..-1 || KeyIds.valid(k.key)) &&
            listOf(k.left, k.top, k.right, k.bottom).all { it.isFinite() && it in 0f..10000f } &&
            k.width > 0 && k.height > 0 && k.row in 0..12 && k.half in 0..1 })
    }

    fun distance(other: LayoutDescriptor, similar: Boolean = false): Float? {
        // The old width band is only a legacy fallback hint, not an independent layout family.
        if (family and 124 != other.family and 124 || page != other.page || inputKind != other.inputKind) return null
        val ids = keys.map { it.key }.toSet(); val otherIds = other.keys.map { it.key }.toSet()
        val legacy = ids == PHYSICAL_LETTERS.indices.toSet() || otherIds == PHYSICAL_LETTERS.indices.toSet()
        if (ids != otherIds && !(similar && legacy && ids.intersect(otherIds).size == 26)) return null
        val sizeLimit = if (similar) .15f else .05f
        val moveLimit = if (similar) .30f else .10f
        val gapLimit = if (similar) 2f else 1f
        if (abs(horizontalGap - other.horizontalGap) > gapLimit || abs(verticalGap - other.verticalGap) > gapLimit) return null
        val unit = keys.map { it.width }.average().toFloat()
        if (abs(splitGap - other.splitGap) > unit * moveLimit) return null
        var distance = 0f
        for (key in keys.filter { it.key in otherIds }) {
            val next = other.keys.first { it.key == key.key }
            if ((!legacy && key.row != next.row) || key.half != next.half) return null
            val dw = abs(key.width - next.width) / key.width
            val dh = abs(key.height - next.height) / key.height
            val dx = abs((key.left + key.right) / 2 + screenX - (next.left + next.right) / 2 - other.screenX) / key.width
            val dy = abs((key.top + key.bottom) / 2 + screenY - (next.top + next.bottom) / 2 - other.screenY) / key.height
            if (dw > sizeLimit || dh > sizeLimit || dx > moveLimit || dy > moveLimit) return null
            distance += dw + dh + dx + dy
        }
        return distance
    }

    fun encode(): String = (listOf(family, width, height, screenX, screenY, horizontalGap, verticalGap, splitGap) +
        keys.flatMap { listOf(it.key, it.left, it.top, it.right, it.bottom, it.row, it.half) }).joinToString(",") +
        ";" + surfaces.flatMap { listOf(it.key, it.left, it.top, it.right, it.bottom, it.row, it.half) }.joinToString(",") + ";" + pixelDensity + ";" + page + ";" + inputKind

    companion object {
        fun decode(value: String): LayoutDescriptor {
            val parts = value.split(';'); require(parts.size in 1..5)
            val f = parts[0].split(','); require(f.size in 15..708 && (f.size - 8) % 7 == 0)
            val s = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.split(',').orEmpty()
            require(s.size % 7 == 0 && s.size <= 700)
            return LayoutDescriptor(f[0].toInt(), f[1].toFloat(), f[2].toFloat(), f[3].toFloat(), f[4].toFloat(),
                f[5].toFloat(), f[6].toFloat(), f[7].toFloat(), (8 until f.size step 7).map { i ->
                    LayoutKey(f[i].toInt(), f[i + 1].toFloat(), f[i + 2].toFloat(), f[i + 3].toFloat(),
                        f[i + 4].toFloat(), f[i + 5].toInt(), f[i + 6].toInt())
                }, (s.indices step 7).map { i -> LayoutKey(s[i].toInt(), s[i + 1].toFloat(), s[i + 2].toFloat(),
                    s[i + 3].toFloat(), s[i + 4].toFloat(), s[i + 5].toInt(), s[i + 6].toInt()) }, parts.getOrNull(2)?.toFloat() ?: 0f, parts.getOrNull(3)?.toInt() ?: 0, parts.getOrNull(4)?.toInt() ?: 0)
        }
    }
}
