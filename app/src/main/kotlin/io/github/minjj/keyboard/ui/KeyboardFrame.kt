package io.github.minjj.keyboard.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.widget.FrameLayout

/** Overlays follow the measured keyboard, including while its child is hidden for clipboard. */
class KeyboardFrame(context: Context) : FrameLayout(context) {
    private val marginPaint = Paint()

    override fun dispatchDraw(canvas: Canvas) {
        val keyboard = getChildAt(0) as? KeyboardView
        if (keyboard != null && keyboard.width < width) {
            marginPaint.color = keyboard.surfaceColor
            canvas.drawRect(0f, 0f, keyboard.left.toFloat(), height.toFloat(), marginPaint)
            canvas.drawRect(keyboard.right.toFloat(), 0f, width.toFloat(), height.toFloat(), marginPaint)
        }
        super.dispatchDraw(canvas)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val keyboard = getChildAt(0)
        if (keyboard == null) { super.onMeasure(widthMeasureSpec, heightMeasureSpec); return }
        measureChildWithMargins(keyboard, widthMeasureSpec, 0, heightMeasureSpec, 0)
        setMeasuredDimension(View.MeasureSpec.getSize(widthMeasureSpec), resolveSize(keyboard.measuredHeight, heightMeasureSpec))
        for (i in 1 until childCount) {
            val child = getChildAt(i)
            val width = child.layoutParams.width.takeIf { it >= 0 } ?: measuredWidth
            child.measure(View.MeasureSpec.makeMeasureSpec(width.coerceAtMost(measuredWidth), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(measuredHeight, View.MeasureSpec.EXACTLY))
        }
    }
}
