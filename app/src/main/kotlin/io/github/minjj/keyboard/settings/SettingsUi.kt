package io.github.minjj.keyboard.settings

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import io.github.minjj.keyboard.R

internal class SettingsUi(private val context: Context) {
    val ink get() = context.getColor(R.color.settings_text)
    val secondary get() = context.getColor(R.color.settings_caption)
    val accent get() = context.getColor(R.color.settings_accent)

    fun dp(value: Int) = (value * context.resources.displayMetrics.density + 0.5f).toInt()

    fun column(top: Int = 8, bottom: Int = 16): LinearLayout = BoundedColumn(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(top), dp(12), dp(bottom))
    }

    fun centered(column: LinearLayout) = FrameLayout(context).apply {
        addView(column, FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
    }

    fun fixedBar(column: LinearLayout) = centered(column).apply {
        setBackgroundResource(R.color.settings_background)
        // The resize overlay can extend beyond the keyboard preview while dragging.
        translationZ = dp(1).toFloat()
        outlineProvider = null
    }

    fun scrollPage(
        body: LinearLayout,
        title: Int,
        includeImeInsets: Boolean = false,
        back: (() -> Unit)? = null,
    ) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.color.settings_background)
        val heading = column(bottom = 0).also { header(it, title, back) }
        addView(centered(heading), LinearLayout.LayoutParams(-1, -2))
        addView(ScrollView(context).apply {
            isFillViewport = true
            addView(centered(body))
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            val bars = if (Build.VERSION.SDK_INT >= 30) {
                val types = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                insets.getInsets(if (includeImeInsets) types or WindowInsets.Type.ime() else types)
            } else {
                android.graphics.Insets.of(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    fun header(parent: LinearLayout, title: Int, back: (() -> Unit)? = null) {
        val line = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
        }
        if (back != null) {
            line.addView(ImageButton(context).apply {
                setImageResource(R.drawable.ic_settings_back)
                setColorFilter(ink)
                background = selectable(android.R.attr.selectableItemBackgroundBorderless)
                contentDescription = context.getString(R.string.layout_back)
                setOnClickListener { back() }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
        }
        line.addView(text(context.getString(title), 22f, ink).apply {
            setTypeface(typeface, Typeface.BOLD)
            isAccessibilityHeading = true
        }, LinearLayout.LayoutParams(0, -2, 1f))
        parent.addView(line, LinearLayout.LayoutParams(-1, -2))
    }

    fun sectionHeading(parent: LinearLayout, title: Int) {
        parent.addView(text(context.getString(title), 14f, secondary).apply {
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12), 0, dp(12), dp(6))
            isAccessibilityHeading = true
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }

    fun card(parent: LinearLayout, topMarginDp: Int = 8, group: LinearLayout = LinearLayout(context)): LinearLayout = group.apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(context.getColor(R.color.settings_surface))
            cornerRadius = dp(16).toFloat()
        }
        clipToOutline = true
        dividerDrawable = GradientDrawable().apply {
            setColor(context.getColor(R.color.settings_divider))
            setSize(1, dp(1))
        }
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        setPadding(dp(12), dp(2), dp(12), dp(2))
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(topMarginDp) })
    }

    fun radioCard(parent: LinearLayout, topMarginDp: Int = 8) = RadioGroup(context).also { card(parent, topMarginDp, it) }

    fun radio(parent: RadioGroup, label: CharSequence) = RadioButton(context).apply {
        id = View.generateViewId()
        text = label
        textSize = 16f
        setTextColor(ink)
        minimumHeight = dp(48)
        includeFontPadding = false
        setPaddingRelative(0, dp(6), 0, dp(6))
        parent.addView(this, RadioGroup.LayoutParams(-1, -2))
    }

    fun text(value: CharSequence, size: Float = 16f, color: Int = ink) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
        includeFontPadding = false
    }

    fun caption(value: CharSequence) = text(value, 12f, secondary).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    fun caption(parent: LinearLayout, value: CharSequence) = caption(value).also {
        parent.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
    }

    fun actionRow(parent: LinearLayout, title: Int, detail: TextView? = null, action: () -> Unit): View {
        val titleText = context.getString(title)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            minimumHeight = dp(48)
            contentDescription = titleText
            isFocusable = true
            foreground = selectable()
            setOnClickListener { action() }
        }
        val labels = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(text(titleText), LinearLayout.LayoutParams(-1, -2))
            if (detail != null) addView(detail, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        }
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
        return row
    }

    fun toggleMenuRow(parent: LinearLayout, title: Int, description: Int? = null, openMenu: () -> Unit): Switch {
        val line = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val detail = description?.let { caption(context.getString(it)) }
        actionRow(line, title, detail, openMenu).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            if (description != null) contentDescription = "${context.getString(title)}, ${context.getString(description)}"
        }
        line.addView(View(context).apply {
            setBackgroundColor(secondary)
            alpha = 0.35f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(1), dp(24)).apply {
            marginStart = dp(8)
            marginEnd = dp(8)
        })
        val control = Switch(context).apply {
            minimumWidth = dp(48)
            minimumHeight = dp(48)
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            contentDescription = context.getString(title)
        }
        line.addView(control, LinearLayout.LayoutParams(-2, -2))
        parent.addView(line, LinearLayout.LayoutParams(-1, -2))
        return control
    }

    fun toggle(parent: LinearLayout, title: Int, description: Int? = null): Switch {
        val toggle = Switch(context).apply { minimumHeight = dp(48) }
        if (description == null) {
            toggle.setText(title)
            toggle.setTextColor(ink)
            toggle.textSize = 16f
            toggle.includeFontPadding = false
            toggle.setPadding(0, dp(6), 0, dp(6))
            toggle.switchPadding = dp(12)
            parent.addView(toggle, LinearLayout.LayoutParams(-1, -2))
        } else {
            val label = context.getString(title)
            val detail = context.getString(description)
            val line = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                isFocusable = false
                foreground = selectable()
                setOnClickListener { if (toggle.isEnabled) toggle.performClick() }
            }
            val labels = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                setPaddingRelative(0, dp(6), dp(12), dp(6))
                addView(text(label), LinearLayout.LayoutParams(-1, -2))
                caption(this, detail)
            }
            line.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            toggle.contentDescription = "$label, $detail"
            toggle.minimumWidth = dp(48)
            toggle.gravity = Gravity.CENTER_VERTICAL or Gravity.END
            line.addView(toggle, LinearLayout.LayoutParams(-2, -2))
            parent.addView(line, LinearLayout.LayoutParams(-1, -2))
        }
        return toggle
    }

    private fun selectable(attribute: Int = android.R.attr.selectableItemBackground): Drawable? {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(attribute, value, true)) context.getDrawable(value.resourceId) else null
    }
}

private class BoundedColumn(context: Context) : LinearLayout(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = minOf(MeasureSpec.getSize(widthMeasureSpec), (760 * resources.displayMetrics.density).toInt())
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightMeasureSpec)
    }
}
