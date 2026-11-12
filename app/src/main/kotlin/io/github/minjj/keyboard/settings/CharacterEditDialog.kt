package io.github.minjj.keyboard.settings

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import io.github.minjj.keyboard.R

/** A rounded editor above the IME, with an underlined field and quiet text actions. */
internal class CharacterEditDialog(
    private val activity: Activity,
    keyLabel: String,
    private val secondary: Boolean,
    private val initialValue: String,
    private val save: (String?) -> Unit,
) : Dialog(activity) {
    private val ui = SettingsUi(activity)
    val input = EditText(activity).apply {
        id = R.id.character_value
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setSingleLine()
        imeOptions = EditorInfo.IME_ACTION_DONE
        setText(initialValue)
        setSelectAllOnFocus(true)
        textSize = 22f
        setTextColor(ui.ink)
        setHintTextColor(ui.secondary)
        contentDescription = activity.getString(if (secondary) R.string.character_secondary_label else R.string.character_symbol_label)
        minimumHeight = ui.dp(48)
        setPadding(0, ui.dp(4), 0, ui.dp(8))
        background = null
    }
    private val error = ui.text("", 12f, activity.getColor(R.color.settings_error)).apply {
        visibility = View.GONE
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val actions = mutableListOf<Button>()
    private lateinit var saveButton: Button
    private var saving = false

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(24), ui.dp(24), ui.dp(24), ui.dp(12))
        }
        body.addView(ui.text(activity.getString(R.string.character_edit_key, keyLabel), 22f).apply {
            setTypeface(typeface, Typeface.BOLD)
            isAccessibilityHeading = true
        }, LinearLayout.LayoutParams(-1, -2))
        val label = ui.text(activity.getString(if (secondary) R.string.character_secondary_label else R.string.character_symbol_label),
            12f, ui.secondary).apply { labelFor = input.id }
        body.addView(label, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(28) })
        val underline = View(activity).apply { setBackgroundColor(ui.secondary) }
        input.setOnFocusChangeListener { _, focused ->
            label.setTextColor(if (focused) ui.accent else ui.secondary)
            underline.setBackgroundColor(if (focused) ui.ink else ui.secondary)
        }
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                if (saveButton.isEnabled) save(input.text.toString())
                true
            } else false
        }
        body.addView(input, LinearLayout.LayoutParams(-1, -2))
        body.addView(underline, LinearLayout.LayoutParams(-1, ui.dp(1)))
        body.addView(error, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        val fieldDetail = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        fieldDetail.addView(ui.text(if (secondary) activity.getString(R.string.character_empty_secondary) else "", 12f, ui.secondary),
            LinearLayout.LayoutParams(0, -2, 1f))
        val count = ui.text("", 12f, ui.secondary).apply { gravity = Gravity.END }
        fieldDetail.addView(count, LinearLayout.LayoutParams(-2, -2).apply { marginStart = ui.dp(12) })
        body.addView(fieldDetail, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        fun updateCount() {
            val text = input.text.toString()
            count.text = activity.getString(R.string.character_count, text.codePointCount(0, text.length))
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                error.visibility = View.GONE
                updateCount()
                updateSaveButton()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        updateCount()
        val buttons = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        fun action(label: Int, id: Int, primary: Boolean = false, click: () -> Unit): Button {
            return Button(activity).apply {
                this.id = id
                setText(label)
                isAllCaps = false
                textSize = if (id == android.R.id.button3) 14f else 17f
                setTypeface(typeface, if (id == android.R.id.button3) Typeface.NORMAL else Typeface.BOLD)
                minWidth = 0; minimumWidth = 0; minHeight = ui.dp(48)
                stateListAnimator = null
                setPadding(ui.dp(8), 0, ui.dp(8), 0)
                val enabledColor = if (primary) ui.accent else if (id == android.R.id.button3) ui.secondary else ui.ink
                setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
                    intArrayOf(enabledColor, Color.argb(100, Color.red(ui.secondary), Color.green(ui.secondary), Color.blue(ui.secondary)))))
                background = RippleDrawable(ColorStateList.valueOf(0x16808080), rounded(Color.TRANSPARENT, 12), null)
                setOnClickListener { click() }
                actions += this
            }
        }
        body.addView(action(R.string.character_default, android.R.id.button3) { save(null) },
            LinearLayout.LayoutParams(-2, ui.dp(48)).apply { topMargin = ui.dp(8); marginStart = -ui.dp(8) })
        buttons.addView(action(android.R.string.cancel, android.R.id.button2) { dismiss() },
            LinearLayout.LayoutParams(0, ui.dp(48), 1f))
        buttons.addView(View(activity).apply { setBackgroundColor(activity.getColor(R.color.settings_divider)) },
            LinearLayout.LayoutParams(ui.dp(1), ui.dp(20)))
        saveButton = action(R.string.character_save, android.R.id.button1, primary = true) { save(input.text.toString()) }
        buttons.addView(saveButton, LinearLayout.LayoutParams(0, ui.dp(48), 1f))
        body.addView(buttons, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
        updateSaveButton()
        setContentView(ScrollView(activity).apply {
            isFillViewport = false
            background = rounded(activity.getColor(R.color.settings_surface), 28)
            clipToOutline = true
            addView(body)
        })
        setCanceledOnTouchOutside(true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply { dimAmount = 0.35f }
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            attributes = attributes.apply { y = ui.dp(8) }
            @Suppress("DEPRECATION")
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        }
    }

    override fun onStart() {
        super.onStart()
        val available = activity.window.decorView.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        window?.setLayout(minOf(ui.dp(520), available - ui.dp(16)), WindowManager.LayoutParams.WRAP_CONTENT)
        input.requestFocus()
        input.post { if (isShowing) activity.getSystemService(InputMethodManager::class.java).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT) }
    }

    fun setSaving(saving: Boolean) {
        this.saving = saving
        input.isEnabled = !saving
        actions.forEach { it.isEnabled = !saving }
        updateSaveButton()
        setCancelable(!saving)
        setCanceledOnTouchOutside(!saving)
    }

    fun showError(message: String) {
        error.text = message
        error.visibility = View.VISIBLE
    }

    private fun updateSaveButton() {
        if (!::saveButton.isInitialized) return
        val value = input.text.toString()
        saveButton.isEnabled = !saving && value != initialValue && CharacterCustomization.validValue(value, secondary)
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = ui.dp(radius).toFloat()
    }
}
