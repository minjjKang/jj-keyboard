package io.github.minjj.keyboard.ui

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import io.github.minjj.keyboard.ime.KeyCommand
import io.github.minjj.keyboard.ime.KeySpec
import io.github.minjj.keyboard.hangul.Dubeolsik
import io.github.minjj.keyboard.correction.spatialDay
import io.github.minjj.keyboard.touch.*
import kotlinx.coroutines.*
import kotlin.math.roundToInt

/** Uses the same literal/gap/candidate/resolver path as the real keyboard, including function blocks. */
internal class TouchLearningMapView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val originalDash = DashPathEffect(floatArrayOf(3f, 2f), 0f)
    private val viewport = RectF()
    private val chart = RectF()
    private val rasterViewport = RectF()
    private var layout: LayoutDescriptor? = null
    private var model = SpatialModel()
    private var profile = -1
    private var bitmap: Bitmap? = null
    private var geometry: GeometrySnapshot? = null
    private var generation = 0
    private var renderedGeneration = -1
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var evidence: FloatArray? = null
    private var planTask: Deferred<TouchMapRaster.Plan>? = null
    private var planGeometry: GeometrySnapshot? = null
    private var planWidth = 0
    private var planHeight = 0
    private val planViewport = RectF()
    internal var preparationCount = 0; private set
    internal var lastRenderNanos = 0L; private set
    private var personal = true
    private var recent: List<PracticeTap> = emptyList()
    var distributionMode = 0
        set(value) { field = value; invalidate() }
    data class RegionStats(val changedPercent: Float, val areaDelta: Map<Int, Float>)
    var regionStats = RegionStats(0f, emptyMap()); private set
    var regionsChanged: (() -> Unit)? = null
    var keySelected: ((Int) -> Unit)? = null
    internal val renderingComplete get() = layout == null || renderedGeneration == generation
    internal val renderedBounds get() = RectF(chart)
    init { isFocusable = true }

    fun show(layout: LayoutDescriptor?, model: SpatialModel, profile: Int, personal: Boolean, recent: List<PracticeTap> = emptyList(), evidence: FloatArray? = null) {
        val geometryChanged = this.layout != layout
        val evidenceChanged = !(this.evidence?.contentEquals(evidence ?: FloatArray(0)) ?: (evidence == null))
        val rasterChanged = evidenceChanged || geometryChanged || this.model !== model || this.profile != profile || this.personal != personal
        this.evidence = evidence?.copyOf()
        this.layout = layout; this.model = model; this.profile = profile; this.personal = personal; this.recent = recent.toList()
        if (layout != null) viewport.set(
            (layout.keys.minOf { it.left } - 3f).coerceAtLeast(0f), (layout.keys.minOf { it.top } - 3f).coerceAtLeast(0f),
            (layout.keys.maxOf { it.right } + 3f).coerceAtMost(layout.width),
            (layout.keys.maxOf { it.bottom } + 3f).coerceAtMost(layout.height))
        if (geometryChanged) geometry = layout?.let { storedGeometry(context, it) }
        if (rasterChanged || (bitmap == null && job?.isActive != true)) renderMap()
        if (geometryChanged) requestLayout()
        invalidate()
    }

    private fun renderMap() {
        val revision = ++generation
        job?.cancel()
        val descriptor = layout ?: run { bitmap = null; return }
        val snapshot = geometry ?: return
        if (width <= 0 || height <= 0) return
        val scale = minOf(width / viewport.width(), height / viewport.height())
        val w = (viewport.width() * scale).toInt().coerceIn(1, width)
        val h = (viewport.height() * scale).toInt().coerceIn(1, height)
        val left = ((width - w) / 2).toFloat(); val top = ((height - h) / 2).toFloat()
        chart.set(left, top, left + w, top + h)
        val bounds = RectF(viewport)
        if (bitmap?.width != w || bitmap?.height != h || rasterViewport != bounds) bitmap = null
        val currentModel = model; val currentProfile = profile; val showPersonal = personal
        val density = sourceDensity(context, descriptor)
        val day = spatialDay()
        val language = evidence?.copyOf()
        if (planGeometry !== snapshot || planWidth != w || planHeight != h || planViewport != bounds || planTask == null) {
            planTask?.cancel()
            planGeometry = snapshot; planWidth = w; planHeight = h; planViewport.set(bounds)
            preparationCount++
            planTask = scope.async(Dispatchers.Default) {
                TouchMapRaster.prepare(snapshot, bounds, w, h, density) { ensureActive() }
            }
        }
        val preparation = checkNotNull(planTask)
        job = scope.launch {
            val plan = preparation.await()
            val started = System.nanoTime()
            val (pixels, stats) = withContext(Dispatchers.Default) {
                val owners = TouchMapRaster.decisions(plan, currentModel, currentProfile, day, showPersonal, language) { ensureActive() }
                val original = IntArray(KeyIds.LIMIT); val actual = IntArray(KeyIds.LIMIT)
                var changed = 0; var occupied = 0
                owners.indices.forEach { i ->
                    if (plan.baseOwners[i] >= 0) { original[plan.baseOwners[i]]++; occupied++ }
                    if (owners[i] >= 0) actual[owners[i]]++
                    if (owners[i] != plan.baseOwners[i]) changed++
                }
                TouchMapRaster.adaptivePixels(plan, owners) to RegionStats(if (occupied > 0) changed * 100f / occupied else 0f,
                    original.indices.filter { original[it] > 0 }.associateWith { (actual[it] - original[it]) * 100f / original[it] })
            }
            if (generation == revision) {
                bitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
                regionStats = stats
                regionsChanged?.invoke()
                lastRenderNanos = System.nanoTime() - started
                rasterViewport.set(bounds)
                renderedGeneration = revision
                invalidate()
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        renderMap()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val l = layout
        val h = if (l == null) 0 else (w * viewport.height() / viewport.width()).toInt()
        setMeasuredDimension(w, resolveSize(h.coerceAtLeast(1), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val l = layout ?: return
        if (chart.isEmpty) return
        // Draw at native display resolution. No bitmap scaling/filtering can add transparent seams.
        paint.style = Paint.Style.FILL
        bitmap?.let { canvas.drawBitmap(it, chart.left, chart.top, paint) }
        val s = chart.width() / viewport.width()
        canvas.save(); canvas.translate(chart.left, chart.top)
        canvas.scale(s, chart.height() / viewport.height()); canvas.translate(-viewport.left, -viewport.top)
        paint.textAlign = Paint.Align.CENTER; paint.textSize = l.keys.minOf { it.height } * .35f
        val adaptiveIds = l.keys.map { it.key }.toSet()
        for (k in l.surfaces.ifEmpty { l.keys }) {
            if (k.key < 0) continue
            paint.style = Paint.Style.STROKE; paint.color = Color.GRAY; paint.strokeWidth = .75f / s; paint.pathEffect = originalDash
            canvas.drawRoundRect(k.left, k.top, k.right, k.bottom, 3f, 3f, paint)
            paint.pathEffect = null; paint.style = Paint.Style.FILL
            paint.color = context.getColor(io.github.minjj.keyboard.R.color.settings_text)
            val label = if (k.key < 26 && l.korean) Dubeolsik.map(PHYSICAL_LETTERS[k.key]).toString() else shortLabel(k.key)
            paint.textSize = minOf(k.height * .35f, k.width / (label.length * .65f).coerceAtLeast(1f))
            canvas.drawText(label, (k.left + k.right) / 2, k.top + paint.textSize, paint)
            if (k.key !in adaptiveIds) continue
            val delta = regionStats.areaDelta[k.key] ?: 0f
            if (kotlin.math.abs(delta) >= .1f) {
                paint.textSize = minOf(k.height * .22f, k.width / 4)
                paint.color = if (delta > 0) Color.rgb(15, 80, 180) else Color.rgb(170, 65, 20)
                canvas.drawText(java.lang.String.format(java.util.Locale.ROOT, "%+.1f%%", delta),
                    (k.left + k.right) / 2, k.bottom - 3f, paint)
            }
            if (distributionMode == 2) continue
            val density = model.density(profile, spatialDay(), k.key)
            val peak = density.cells.maxOfOrNull { it.count } ?: 1f
            if (distributionMode == 0) for (cell in density.cells) {
                val x = (k.left + k.right) / 2 + cell.dx * k.width
                val y = (k.top + k.bottom) / 2 + cell.dy * k.height
                paint.color = Color.argb((35 + 130 * cell.count / peak).toInt(), 225, 115, 25)
                canvas.drawOval(x - k.width * .075f, y - k.height * .075f,
                    x + k.width * .075f, y + k.height * .075f, paint)
            }
            if (distributionMode == 1) for (point in density.centralPoints()) {
                paint.color = Color.argb(150, 175, 40, 145)
                canvas.drawCircle((k.left + k.right) / 2 + point.dx * k.width,
                    (k.top + k.bottom) / 2 + point.dy * k.height, 2f, paint)
            }
            if (distributionMode == 0) density.mode?.let { mode ->
                val x = (k.left + k.right) / 2 + mode.dx * k.width
                val y = (k.top + k.bottom) / 2 + mode.dy * k.height
                paint.color = Color.rgb(15, 125, 75); paint.strokeWidth = 1.5f / s
                canvas.drawLine(x - 3f, y, x + 3f, y, paint); canvas.drawLine(x, y - 3f, x, y + 3f, paint)
            }
            val summary = model.summary(profile, spatialDay(), k.key, rowKeys(l, k))
            if (distributionMode == 0 && summary.normal + summary.guided > 0) {
                paint.color = Color.rgb(190, 75, 5)
                canvas.drawCircle((k.left + k.right) / 2 + summary.meanX * k.width,
                    (k.top + k.bottom) / 2 + summary.meanY * k.height, 2.5f, paint)
                paint.color = Color.rgb(20, 75, 180)
                canvas.drawRect((k.left + k.right) / 2 + summary.offsetX * k.width - 2,
                    (k.top + k.bottom) / 2 + summary.offsetY * k.height - 2,
                    (k.left + k.right) / 2 + summary.offsetX * k.width + 2,
                    (k.top + k.bottom) / 2 + summary.offsetY * k.height + 2, paint)
            }
        }
        if (distributionMode != 2) {
            paint.color = Color.MAGENTA
            val dots = if (distributionMode == 1) recent.groupBy { KeyIds.fromToken(it.literal) }.flatMap { (id, taps) ->
                val key = l.keys.firstOrNull { it.key == id } ?: return@flatMap emptyList()
                val meanX = taps.map { it.x }.average(); val meanY = taps.map { it.y }.average()
                taps.sortedBy { val dx = (it.x - meanX) / key.width; val dy = (it.y - meanY) / key.height; dx * dx + dy * dy }
                    .take((taps.size + 1) / 2)
            } else recent.takeLast(12)
            for (tap in dots) canvas.drawCircle(tap.x, tap.y, 2f, paint)
        }
        canvas.restore()
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility") // The map also exposes all key statistics in its accessibility description.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val l = layout ?: return true
            if (!chart.contains(event.x, event.y)) return true
            val x = (event.x - chart.left) * viewport.width() / chart.width() + viewport.left
            val y = (event.y - chart.top) * viewport.height() / chart.height() + viewport.top
            val density = sourceDensity(context, l)
            geometry?.let { TouchMapRaster.letterAt(it, model, profile, spatialDay(), x * density, y * density, personal, evidence?.let { values -> { key -> values.getOrElse(key) { 0f } } }) }
                ?.let { keySelected?.invoke(it) }
            performClick()
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onDetachedFromWindow() { job?.cancel(); planTask?.cancel(); planTask = null; super.onDetachedFromWindow() }

    companion object {
        fun rowMask(layout: LayoutDescriptor, key: LayoutKey): Int = rowKeys(layout, key).filter { it < 26 }.fold(0) { mask, id -> mask or (1 shl id) }
        fun rowKeys(layout: LayoutDescriptor, key: LayoutKey): Set<Int> = layout.keys.filter { it.row == key.row && it.half == key.half && KeyIds.kind(it.key) == KeyIds.kind(key.key) }
            .map { it.key }.toSet()
        fun shortLabel(key: Int): String = when (key % KeyIds.STRIDE) {
            KeyIds.function("DELETE") -> "⌫"
            KeyIds.function("DELETE_FORWARD") -> "⌦"
            KeyIds.function("SHIFT") -> "⇧"
            KeyIds.function("ENTER") -> "↵"
            KeyIds.function("CURSOR_LEFT") -> "←"
            KeyIds.function("CURSOR_RIGHT") -> "→"
            KeyIds.function("SPACE") -> "공백"
            else -> KeyIds.glyph(key)?.toString() ?: KeyIds.label(key).substringBefore(" ")
        }
        fun sourceDensity(context: Context, layout: LayoutDescriptor): Float =
            layout.pixelDensity.takeIf { it > 0f } ?: context.resources.displayMetrics.density

        fun storedGeometry(context: Context, layout: LayoutDescriptor): GeometrySnapshot {
            val density = sourceDensity(context, layout)
            val parents = mutableMapOf<Pair<Int, Int>, LinearLayout>()
            val keys = layout.surfaces.ifEmpty { layout.keys }.map { k ->
                val parent = parents.getOrPut(k.row to k.half) { LinearLayout(context) }
                val view = View(context); parent.addView(view)
                val letter = k.key in 0..25
                val value = if (k.key >= 0) KeyIds.glyph(k.key)?.toString().orEmpty() else ""
                val spec = KeySpec(value, if (value.isNotEmpty() || k.key == -2) KeyCommand.CHARACTER else KeyCommand.DELETE, value, letter = letter)
                TouchKey(view, spec, Rect((k.left * density).roundToInt(), (k.top * density).roundToInt(), (k.right * density).roundToInt(), (k.bottom * density).roundToInt()), false, k.half, k.key.takeIf { id -> layout.keys.any { it.key == id } })
            }
            return GeometrySnapshot(keys, (layout.horizontalGap * density).roundToInt(), (layout.verticalGap * density).roundToInt())
        }
    }
}
