package io.github.minjj.keyboard.touch

import kotlin.math.roundToInt

data class DensityCell(val dx: Float, val dy: Float, val count: Float)
data class DensityPoint(val dx: Float, val dy: Float)

/** Unordered 10%-of-key spatial bins, never an input sequence or a raw coordinate history. */
data class TouchDensity(val cells: List<DensityCell>, val points: List<DensityPoint> = emptyList()) {
    val count = cells.sumOf { it.count.toDouble() }.toFloat()
    val mode: DensityPoint? by lazy {
        val peak = cells.maxWithOrNull(compareBy<DensityCell> { it.count }.thenBy { -it.dx * it.dx - it.dy * it.dy }) ?: return@lazy null
        val local = points.filter { cell((it.dx * 1000).roundToInt(), (it.dy * 1000).roundToInt()) == cell((peak.dx * 1000).roundToInt(), (peak.dy * 1000).roundToInt()) }
        if (local.isEmpty()) DensityPoint(peak.dx, peak.dy)
        else DensityPoint(local.map { it.dx }.average().toFloat(), local.map { it.dy }.average().toFloat())
    }
    fun centralPoints(): List<DensityPoint> = central
    private val central: List<DensityPoint> by lazy {
        val center = mode ?: return@lazy emptyList()
        val counts = cells.associate { cell((it.dx * 1000).roundToInt(), (it.dy * 1000).roundToInt()) to it.count }
        points.sortedWith(compareByDescending<DensityPoint> { counts[cell((it.dx * 1000).roundToInt(), (it.dy * 1000).roundToInt())] ?: 0f }
            .thenBy { (it.dx - center.dx) * (it.dx - center.dx) + (it.dy - center.dy) * (it.dy - center.dy) }).take((points.size + 1) / 2)
    }
    fun centralHalf(): List<DensityCell> {
        if (count <= 0) return emptyList()
        val meanX = cells.sumOf { (it.dx * it.count).toDouble() }.toFloat() / count
        val meanY = cells.sumOf { (it.dy * it.count).toDouble() }.toFloat() / count
        var remaining = count / 2
        return cells.sortedWith(compareByDescending<DensityCell> { it.count }
            .thenBy { (it.dx - meanX) * (it.dx - meanX) + (it.dy - meanY) * (it.dy - meanY) }
            .thenBy { it.dy }.thenBy { it.dx }).mapNotNull { cell ->
            if (remaining <= 0) null else cell.copy(count = minOf(cell.count, remaining)).also { remaining -= it.count }
        }
    }
    companion object {
        const val SIDE = 15
        fun from(points: List<DensityPoint>): TouchDensity {
            require(points.size <= 100)
            val cells = points.groupingBy { cell((it.dx * 1000).roundToInt(), (it.dy * 1000).roundToInt()) }.eachCount()
            return TouchDensity(cells.map { (id, count) -> DensityCell((id % SIDE - 7) / 10f, (id / SIDE - 7) / 10f, count.toFloat()) }, points.toList())
        }
        fun cell(dx: Int, dy: Int): Int = ((dy / 100f).roundToInt() + 7).coerceIn(0, 14) * SIDE +
            ((dx / 100f).roundToInt() + 7).coerceIn(0, 14)
    }
}
