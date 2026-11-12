package io.github.minjj.keyboard.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.*
import io.github.minjj.keyboard.R
import io.github.minjj.keyboard.clipboard.ClipEntry
import io.github.minjj.keyboard.clipboard.ClipboardHistory
import io.github.minjj.keyboard.ime.KeyCommand

@android.annotation.SuppressLint("ViewConstructor") // Constructed with callbacks by the IME, never inflated from XML.
class ClipboardPanel(baseContext: Context, private val history: ClipboardHistory, dark: Boolean,
    surfaceColor: Int, private val columns: Int, private val back: () -> Unit, private val paste: (ClipEntry) -> Unit,
    private val pin: (Set<String>, Boolean) -> Unit, private val delete: (Set<String>) -> Unit,
) : LinearLayout(clipboardContext(baseContext, dark)) {
    private val ink = context.getColor(R.color.settings_text)
    private val secondary = context.getColor(R.color.settings_caption)
    private val accent = context.getColor(R.color.settings_accent)
    private val cards = LinearLayout(context).apply { orientation = VERTICAL }
    private val selected = mutableSetOf<String>()
    private var selecting = false
    private var entries = emptyList<ClipEntry>()
    private val title = label(context.getString(R.string.key_clipboard), 18f).apply {
        setTypeface(typeface, Typeface.BOLD)
        isAccessibilityHeading = true
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val selectionCount = label("", 14f, secondary)
    private val backButton = iconButton(R.drawable.ic_settings_back, R.string.clipboard_back) {
        if (!cancelSelection()) back()
    }
    private val pinButton = iconButton(R.drawable.ic_clipboard_pin, R.string.clipboard_pin_selected) {
        if (!selecting) startSelection()
        else if (selected.isNotEmpty()) {
            val ids = selected.toSet()
            val pinned = !allSelectedPinned()
            cancelSelection()
            pin(ids, pinned)
        }
    }.apply { id = R.id.clipboard_pin_selected }
    private val deleteButton = iconButton(R.drawable.ic_clipboard_delete, R.string.clipboard_delete_selected) {
        if (!selecting) startSelection()
        else if (selected.isNotEmpty()) {
            val ids = selected.toSet()
            cancelSelection()
            delete(ids)
        }
    }.apply { id = R.id.clipboard_delete_selected }

    init {
        id = R.id.clipboard_panel
        orientation = VERTICAL
        setPadding(dp(4), dp(3), dp(4), dp(4))
        setBackgroundColor(surfaceColor)
        // The panel spans the IME width independently of the user's key-width preference.
        // Its content still stops above navigation controls and beside display cutouts.
        setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            val bars = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            } else android.graphics.Insets.of(insets.systemWindowInsetLeft, 0,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            setPadding(dp(4) + bars.left, dp(3), dp(4) + bars.right, dp(4) + bars.bottom)
            insets
        }
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(backButton, LayoutParams(dp(56), dp(44)))
            addView(title, LayoutParams(0, -2, 1f))
            addView(selectionCount, LayoutParams(-2, -2).apply { marginEnd = dp(8) })
            addView(pinButton, LayoutParams(dp(56), dp(44)))
            addView(deleteButton, LayoutParams(dp(56), dp(44)))
        }, LayoutParams(-1, dp(44)))
        addView(ScrollView(context).apply {
            isFillViewport = true
            setPadding(dp(8), 0, dp(8), 0)
            addView(cards)
        }, LayoutParams(-1, 0, 1f))
        updateToolbar()
    }

    fun cancelSelection(): Boolean {
        if (!selecting) return false
        selecting = false
        selected.clear()
        render(entries)
        return true
    }

    private fun startSelection() {
        selecting = true
        render(entries)
    }

    private fun toggleSelection(entry: ClipEntry) {
        selecting = true
        if (!selected.add(entry.id)) selected.remove(entry.id)
        render(entries)
    }

    private fun allSelectedPinned() = selected.isNotEmpty() && entries.filter { it.id in selected }.all { it.pinned }

    private fun updateToolbar() {
        selectionCount.visibility = if (selecting) VISIBLE else GONE
        selectionCount.text = when {
            selected.isEmpty() -> context.getString(R.string.clipboard_select)
            else -> context.getString(R.string.clipboard_selection_count, selected.size)
        }
        selectionCount.contentDescription = context.getString(R.string.clipboard_selected_count, selected.size)
        backButton.contentDescription = context.getString(if (selecting) R.string.clipboard_selection_cancel else R.string.clipboard_back)
        pinButton.contentDescription = context.getString(if (allSelectedPinned()) R.string.clipboard_unpin_selected else R.string.clipboard_pin_selected)
        pinButton.isSelected = allSelectedPinned()
        for (button in listOf(pinButton, deleteButton)) {
            button.isEnabled = entries.isNotEmpty() && (!selecting || selected.isNotEmpty())
            button.setColorFilter(if (button.isSelected) accent else ink)
            button.alpha = if (button.isEnabled) 1f else 0.38f
        }
    }

    fun render(entries: List<ClipEntry>) {
        this.entries = entries
        selected.retainAll(entries.map { it.id }.toSet())
        if (entries.isEmpty()) selecting = false
        updateToolbar()
        cards.removeAllViews()
        if (entries.isEmpty()) {
            cards.addView(LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(24), dp(16), dp(24))
                addView(ImageView(context).apply {
                    setImageDrawable(KeyboardIconDrawable(KeyCommand.CLIPBOARD, secondary))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LayoutParams(dp(32), dp(32)))
                addView(label(context.getString(R.string.clipboard_empty), 14f, secondary).apply {
                    gravity = Gravity.CENTER
                }, LayoutParams(-1, -2).apply { topMargin = dp(12) })
            }, LayoutParams(-1, -2))
            return
        }
        for (pinned in listOf(true, false)) {
            val group = entries.filter { it.pinned == pinned }
            if (group.isEmpty()) continue
            cards.addView(label(context.getString(if (pinned) R.string.clipboard_pinned else R.string.clipboard_recent), 14f, secondary).apply {
                setTypeface(typeface, Typeface.BOLD)
                isAccessibilityHeading = true
                setPadding(dp(4), dp(8), dp(4), dp(6))
            }, LayoutParams(-1, -2))
            for (row in group.chunked(columns)) {
                val line = LinearLayout(context)
                for (entry in row) line.addView(card(entry), LayoutParams(0, dp(96), 1f).apply {
                    setMargins(dp(4), 0, dp(4), dp(8))
                })
                repeat(columns - row.size) {
                    line.addView(View(context), LayoutParams(0, 1, 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
                }
                cards.addView(line, LayoutParams(-1, -2))
            }
        }
    }

    private fun card(entry: ClipEntry): View = FrameLayout(context).apply {
        val checked = entry.id in selected
        val accessibleLabel = entry.text?.take(80) ?: context.getString(R.string.clipboard_image)
        tag = "clip:${entry.id}:paste"
        background = GradientDrawable().apply {
            setColor(context.getColor(R.color.settings_surface))
            cornerRadius = dp(16).toFloat()
            if (checked) setStroke(dp(2), accent)
        }
        clipToOutline = true
        foreground = selectable()
        isSelected = checked
        isFocusable = true
        contentDescription = if (selecting) context.getString(R.string.clipboard_select_item, accessibleLabel)
            else "${context.getString(R.string.clipboard_paste)}: $accessibleLabel"
        if (Build.VERSION.SDK_INT >= 30) {
            stateDescription = if (selecting) context.getString(if (checked) R.string.clipboard_selected else R.string.clipboard_not_selected) else null
        }
        setOnClickListener { if (selecting) toggleSelection(entry) else paste(entry) }
        setOnLongClickListener { toggleSelection(entry); true }
        val preview = if (entry.image) ImageView(context).apply {
            setImageBitmap(history.thumbnailFile(entry.id)?.let { BitmapFactory.decodeFile(it.path) })
            scaleType = ImageView.ScaleType.FIT_CENTER
        } else label(entry.text.orEmpty(), 14f).apply {
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.TOP or Gravity.START
        }
        preview.setPadding(dp(12), dp(12), dp(if (selecting || entry.pinned) 28 else 12), dp(8))
        preview.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(preview, FrameLayout.LayoutParams(-1, -1))
        if (selecting || entry.pinned) addView(ImageView(context).apply {
            if (!selecting || checked) {
                setImageResource(if (selecting) R.drawable.ic_clipboard_selected else R.drawable.ic_clipboard_pin)
                setColorFilter(accent)
            } else background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(1), secondary)
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(8)
            marginEnd = dp(8)
        })
    }

    private fun label(value: String, size: Float, color: Int = ink) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
        includeFontPadding = false
    }

    private fun iconButton(icon: Int, description: Int, click: () -> Unit) = ImageButton(context).apply {
        setImageResource(icon)
        setColorFilter(ink)
        contentDescription = context.getString(description)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        background = selectable()
        setOnClickListener { click() }
    }

    private fun selectable(): Drawable? {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)) context.getDrawable(value.resourceId) else null
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}

/** The IME also requests the dark palette for its high-contrast option in a light system theme. */
private fun clipboardContext(context: Context, dark: Boolean): Context {
    val configuration = Configuration(context.resources.configuration).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    }
    return ContextThemeWrapper(context.createConfigurationContext(configuration), R.style.SettingsTheme)
}
