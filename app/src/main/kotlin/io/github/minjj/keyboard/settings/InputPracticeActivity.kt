package io.github.minjj.keyboard.settings

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import io.github.minjj.keyboard.R

class InputPracticeActivity : Activity() {
    private val ui by lazy { SettingsUi(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = ui.column(top = 0).apply {
            isFocusableInTouchMode = true
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        val fields = ui.card(body)
        field(fields, R.string.test_hint, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        field(fields, R.string.email_hint, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        field(fields, R.string.number_hint, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        field(fields, R.string.password_hint, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        field(fields, R.string.search_hint, InputType.TYPE_CLASS_TEXT).apply {
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_SEARCH) {
                    Toast.makeText(this@InputPracticeActivity, R.string.search_done, Toast.LENGTH_SHORT).show()
                    true
                } else false
            }
        }
        setContentView(ui.scrollPage(body, R.string.section_test, includeImeInsets = true) { finish() })
        body.requestFocus()
    }

    override fun finish() {
        getSystemService(AutofillManager::class.java)?.cancel()
        super.finish()
    }

    private fun field(parent: LinearLayout, hint: Int, type: Int) = EditText(this).apply {
        setHint(hint)
        inputType = type
        textSize = 16f
        setTextColor(ui.ink)
        setHintTextColor(ui.secondary)
        includeFontPadding = false
        minHeight = ui.dp(48)
        setPadding(0, ui.dp(6), 0, ui.dp(6))
        backgroundTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
            intArrayOf(ui.accent, Color.TRANSPARENT)
        )
        isSaveEnabled = false
        parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
}
