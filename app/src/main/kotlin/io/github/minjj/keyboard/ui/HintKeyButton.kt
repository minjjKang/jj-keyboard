package io.github.minjj.keyboard.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.animation.DecelerateInterpolator
import android.widget.Button
import android.util.TypedValue
import io.github.minjj.keyboard.ime.KeyCommand

/** The main label remains a real accessible Button; the small secondary label is decorative. */
class HintKeyButton(context: Context) : Button(context) {
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val capsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2 * resources.displayMetrics.density }
    private var primaryIcon: KeyboardIconDrawable? = null
    private var primaryCommand: KeyCommand? = null
    private var primaryColor = 0
    private val languagePaint = Paint()
    private var languageAnimation: ValueAnimator? = null
    private var previousLanguage = ""
    private var languageDirection = 0
    var iconScale = 1f
    var capsLocked = false
        set(value) { if (field != value) { field = value; invalidate() } }
    var languageArrows = false
        set(value) { if (field != value) { field = value; invalidate() } }
    fun setPrimaryIcon(command: KeyCommand?, color: Int) {
        if (primaryCommand != command || primaryColor != color) {
            primaryCommand = command; primaryColor = color
            capsPaint.color = color
            primaryIcon = command?.let { KeyboardIconDrawable(it, color) }; invalidate()
        }
    }
    var secondaryLabel: String? = null
        set(value) { if (field != value) { field = value; invalidate() } }
    var secondaryColor: Int = 0
        set(value) { if (field != value) { field = value; hintPaint.color = value; invalidate() } }

    internal fun animateLanguageLabel(previous: String, direction: Int) {
        stopLanguageAnimation()
        if (text.toString() == previous || !ValueAnimator.areAnimatorsEnabled()) return
        previousLanguage = previous
        languageDirection = direction
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener { invalidate() }
        }
        languageAnimation = animator
        animator.start()
    }

    internal fun stopLanguageAnimation() {
        val animator = languageAnimation ?: return
        languageAnimation = null
        animator.cancel()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        stopLanguageAnimation()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val animator = languageAnimation
        if (animator?.isRunning == true) drawLanguageTransition(canvas, animator.animatedFraction)
        else super.onDraw(canvas)
        if (languageArrows) {
            val density = resources.displayMetrics.density
            val offset = minOf(width / 2f - 10 * density, maxOf(width / 3f, paint.measureText(text.toString()) / 2 + 14 * density))
            val y = height / 2f; val half = 4 * density
            capsPaint.color = currentTextColor
            val left = width / 2f - offset; val right = width / 2f + offset
            canvas.drawLine(left + half, y - half, left, y, capsPaint)
            canvas.drawLine(left, y, left + half, y + half, capsPaint)
            canvas.drawLine(right - half, y - half, right, y, capsPaint)
            canvas.drawLine(right, y, right - half, y + half, capsPaint)
        }
        primaryIcon?.let {
            val size = minOf(minOf(width, height) * 0.56f, 28 * resources.displayMetrics.density * iconScale).toInt()
            val x = (width - size) / 2; val y = (height - size) / 2
            it.setBounds(x, y, x + size, y + size); it.draw(canvas)
            if (primaryCommand == KeyCommand.SHIFT && capsLocked) {
                canvas.drawLine(x + size * 0.2f, y + size + 3 * resources.displayMetrics.density,
                    x + size * 0.8f, y + size + 3 * resources.displayMetrics.density, capsPaint)
            }
        }
        val label = secondaryLabel ?: return
        hintPaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10f, resources.displayMetrics)
        val available = (width - 10f * resources.displayMetrics.density).coerceAtLeast(1f)
        val labelWidth = hintPaint.measureText(label)
        if (labelWidth > available) hintPaint.textSize *= available / labelWidth
        canvas.drawText(label, width - 5f * resources.displayMetrics.density,
            11f * resources.displayMetrics.density, hintPaint)
    }

    private fun drawLanguageTransition(canvas: Canvas, progress: Float) {
        val center = width / 2f
        val travel = minOf(center, 64 * resources.displayMetrics.density) * languageDirection
        val clip = canvas.save()
        canvas.clipRect(paddingLeft, 0, width - paddingRight, height)
        languagePaint.set(paint)
        languagePaint.textAlign = Paint.Align.CENTER
        languagePaint.color = currentTextColor
        val alpha = languagePaint.alpha
        languagePaint.alpha = (alpha * (1 - progress)).toInt()
        canvas.drawText(previousLanguage, center + travel * progress, baseline.toFloat(), languagePaint)
        languagePaint.alpha = (alpha * progress).toInt()
        canvas.drawText(text, 0, text.length, center - travel * (1 - progress), baseline.toFloat(), languagePaint)
        canvas.restoreToCount(clip)
    }
}
