package io.github.minjj.keyboard.ui

import android.graphics.*
import android.graphics.drawable.Drawable
import io.github.minjj.keyboard.ime.KeyCommand
import kotlin.math.cos
import kotlin.math.sin

/** Original line icons in a 24-unit viewport, independent of platform emoji fonts. */
class KeyboardIconDrawable(private val command: KeyCommand, color: Int) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; style = Paint.Style.STROKE; strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    override fun draw(canvas: Canvas) {
        val size = minOf(bounds.width(), bounds.height())
        canvas.save()
        canvas.translate(bounds.exactCenterX() - size / 2f, bounds.exactCenterY() - size / 2f)
        canvas.scale(size / 24f, size / 24f)
        val path = Path()
        when (command) {
            KeyCommand.CLIPBOARD -> {
                canvas.drawRoundRect(5f, 4f, 19f, 22f, 2f, 2f, paint)
                canvas.drawRoundRect(8f, 2f, 16f, 7f, 1.5f, 1.5f, paint)
                canvas.drawLine(9f, 12f, 15f, 12f, paint); canvas.drawLine(9f, 16f, 15f, 16f, paint)
            }
            KeyCommand.SETTINGS -> {
                repeat(16) { index ->
                    val radius = if (index % 2 == 0) 9f else 7.5f
                    val angle = Math.PI * index / 8
                    val x = 12 + radius * cos(angle).toFloat(); val y = 12 + radius * sin(angle).toFloat()
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close(); canvas.drawPath(path, paint); canvas.drawCircle(12f, 12f, 3f, paint)
            }
            KeyCommand.MODE -> {
                canvas.drawRoundRect(2f, 5f, 10f, 19f, 2f, 2f, paint)
                canvas.drawRoundRect(14f, 5f, 22f, 19f, 2f, 2f, paint)
                canvas.drawLine(5f, 9f, 7f, 9f, paint); canvas.drawLine(17f, 9f, 19f, 9f, paint)
            }
            KeyCommand.CURSOR_LEFT, KeyCommand.CURSOR_RIGHT -> {
                val a = if (command == KeyCommand.CURSOR_LEFT) 15f else 9f
                path.moveTo(a, 5f); path.lineTo(24f - a, 12f); path.lineTo(a, 19f); canvas.drawPath(path, paint)
            }
            KeyCommand.NEXT_IME -> {
                canvas.drawCircle(12f, 12f, 9f, paint); canvas.drawOval(7f, 3f, 17f, 21f, paint)
                canvas.drawLine(3f, 12f, 21f, 12f, paint)
            }
            KeyCommand.HIDE -> {
                path.moveTo(6f, 9f); path.lineTo(12f, 15f); path.lineTo(18f, 9f); canvas.drawPath(path, paint)
            }
            KeyCommand.ENTER -> {
                path.moveTo(19f, 5f); path.lineTo(19f, 12f); path.quadTo(19f, 14f, 17f, 14f)
                path.lineTo(5f, 14f); path.moveTo(10f, 9f); path.lineTo(5f, 14f); path.lineTo(10f, 19f)
                canvas.drawPath(path, paint)
            }
            KeyCommand.DELETE -> {
                path.moveTo(9f, 5f); path.lineTo(21f, 5f); path.lineTo(21f, 19f); path.lineTo(9f, 19f)
                path.lineTo(3f, 12f); path.close(); canvas.drawPath(path, paint)
                canvas.drawLine(11f, 9f, 17f, 15f, paint); canvas.drawLine(17f, 9f, 11f, 15f, paint)
            }
            KeyCommand.SHIFT -> {
                path.moveTo(12f, 3f); path.lineTo(3f, 12f); path.lineTo(8f, 12f); path.lineTo(8f, 21f)
                path.lineTo(16f, 21f); path.lineTo(16f, 12f); path.lineTo(21f, 12f); path.close()
                canvas.drawPath(path, paint)
            }
            else -> Unit
        }
        canvas.restore()
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Drawable opacity is no longer used")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
