package io.github.minjj.keyboard.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.widget.Button
import android.widget.FrameLayout
import io.github.minjj.keyboard.R

/** Accessible handles change the outline during a drag and apply its size on release. */
@SuppressLint("ViewConstructor") // Constructed with live preview callbacks, never inflated from XML.
class ResizeOverlay(context: Context, initialHeight: () -> Int, initialWidth: () -> Int,
    change: (Int, Int) -> Unit) : FrameLayout(context) {
    init {
        layoutDirection = LAYOUT_DIRECTION_LTR
        val accent = context.getColor(R.color.settings_accent)
        background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(dp(2), accent)
        }
        fun button(text: String, resource: Int, id: Int, click: () -> Unit) = Button(context).apply {
            this.text = text; this.id = id; contentDescription = context.getString(resource)
            isAllCaps = false; textSize = 13f; setTextColor(context.getColor(R.color.settings_on_accent))
            background = GradientDrawable().apply { setColor(accent); cornerRadius = dp(16).toFloat() }
            setPadding(dp(6), 0, dp(6), 0); minWidth = 0; minimumWidth = 0; setOnClickListener { click() }
        }
        val height = button("━", R.string.resize_height, R.id.resize_height) { change((initialHeight() + 5).coerceAtMost(130), initialWidth()) }
        val width = button("┃", R.string.resize_width, R.id.resize_width) { change(initialHeight(), (initialWidth() - 5).coerceAtLeast(60)) }
        addView(height, LayoutParams(dp(72), dp(36), Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        addView(width, LayoutParams(dp(36), dp(72), Gravity.END or Gravity.CENTER_VERTICAL).apply {
            // Leave room for the system Back gesture when the keyboard spans the screen.
            marginEnd = dp(32)
        })
        attachHandle(height, true, initialHeight, initialWidth, change)
        attachHandle(width, false, initialHeight, initialWidth, change)
        isClickable = true
    }
    @SuppressLint("ClickableViewAccessibility") // Click action changes in steps; drag gives continuous preview.
    private fun attachHandle(button: Button, vertical: Boolean, height: () -> Int, width: () -> Int, change: (Int, Int) -> Unit) {
        var start = 0f; var initialHeight = 100; var initialWidth = 100; var extent = 1f; var moved = false
        var targetHeight = 100; var targetWidth = 100
        fun preview(event: MotionEvent) {
            val delta = if (vertical) start - event.rawY else event.rawX - start
            if (kotlin.math.abs(delta) > dp(3)) moved = true
            if (moved) {
                val amount = (delta / extent * if (vertical) initialHeight else initialWidth).toInt()
                targetHeight = if (vertical) (initialHeight + amount).coerceIn(70, 130) else initialHeight
                targetWidth = if (vertical) initialWidth else (initialWidth + amount * 2).coerceIn(60, 100)
                // Transform only the overlay while the finger is down. The keyboard keeps its bounds.
                scaleX = targetWidth.toFloat() / initialWidth
                scaleY = targetHeight.toFloat() / initialHeight
            }
        }
        fun finishDrag() { scaleX = 1f; scaleY = 1f; button.isPressed = false }
        button.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    start = if (vertical) event.rawY else event.rawX; initialHeight = height(); initialWidth = width()
                    extent = (if (vertical) this.height else this.width).toFloat().coerceAtLeast(1f); moved = false; button.isPressed = true
                    pivotX = this.width / 2f
                    pivotY = this.height.toFloat()
                }
                MotionEvent.ACTION_MOVE -> preview(event)
                MotionEvent.ACTION_UP -> {
                    preview(event)
                    finishDrag()
                    if (moved) change(targetHeight, targetWidth) else button.performClick()
                }
                MotionEvent.ACTION_CANCEL -> finishDrag()
            }
            true
        }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
