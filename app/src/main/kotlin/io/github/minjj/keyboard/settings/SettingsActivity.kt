package io.github.minjj.keyboard.settings

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import io.github.minjj.keyboard.R
import kotlinx.coroutines.*

/** Main settings menu. */
class SettingsActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repository: SettingsRepository
    private lateinit var content: LinearLayout
    private lateinit var enableStatus: TextView
    private lateinit var selectedStatus: TextView
    private lateinit var enableRow: View
    private lateinit var chooseRow: View
    private var current = KeyboardSettings()
    private val homeBindings = mutableListOf<(KeyboardSettings) -> Unit>()
    private var updating = false
    private val ui by lazy { SettingsUi(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = SettingsRepository(this)
        content = ui.column(top = 0)
        setContentView(ui.scrollPage(content, R.string.app_name))

        ui.sectionHeading(content, R.string.section_setup)
        val setup = ui.card(content, topMarginDp = 0)
        enableStatus = ui.caption(getString(R.string.keyboard_enabled))
        selectedStatus = ui.caption(getString(R.string.keyboard_selected))
        enableRow = ui.actionRow(setup, R.string.enable_keyboard, enableStatus) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        chooseRow = ui.actionRow(setup, R.string.choose_keyboard, selectedStatus) {
            getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        }

        val style = section(R.string.section_style)
        row(style, R.string.mode_title, { getString(R.string.mode_summary, modeLabel(it.portraitMode), modeLabel(it.landscapeMode)) }) {
            openLayoutSettings(R.string.mode_title)
        }
        row(style, R.string.layout_title, { getString(R.string.layout_summary) }) {
            openLayoutSettings(R.string.layout_title)
        }
        row(style, R.string.characters_title, { getString(R.string.characters_summary) }) {
            startActivity(Intent(this, CharacterSettingsActivity::class.java))
        }

        val gestures = section(R.string.section_gestures)
        toggle(gestures, R.string.delete_gesture, { it.deleteGesture }, R.string.delete_gesture_description,
            openMenu = { openOptions(R.string.delete_gesture) }) {
            repository.setOption("deleteGesture", it)
        }
        row(gestures, R.string.long_press_title, { durationLabel(it.longPressMillis) }) {
            openOptions(R.string.long_press_title)
        }
        row(gestures, R.string.repeat_title, { speedLabel(it.repeatMillis) }) {
            openOptions(R.string.repeat_title)
        }
        row(gestures, R.string.space_gestures_title, { getString(R.string.space_gestures_summary) }) {
            openOptions(R.string.space_gestures_title)
        }
        row(gestures, R.string.feedback_title, { getString(R.string.feedback_summary) }) {
            openOptions(R.string.feedback_title)
        }
        val input = section(R.string.section_input)
        row(input, R.string.touch_personalization) {
            openOptions(R.string.touch_personalization)
        }
        toggle(input, R.string.clipboard_history, { it.clipboardHistory }) { repository.setOption("clipboardHistory", it) }
        toggle(input, R.string.auto_capitalization, { it.autoCapitalization }, R.string.auto_caps_description) {
            repository.setOption("autoCaps", it)
        }
        toggle(input, R.string.double_space, { it.doubleSpacePeriod }, R.string.double_space_description) {
            repository.setOption("doubleSpace", it)
        }
        row(input, R.string.section_test) {
            startActivity(Intent(this, InputPracticeActivity::class.java))
        }
        scope.launch { repository.settings.collect { current = it; applyBindings() } }
    }

    private fun openLayoutSettings(page: Int) {
        startActivity(Intent(this, LayoutSettingsActivity::class.java).putExtra(LayoutSettingsActivity.PAGE, page))
    }

    private fun openOptions(page: Int) {
        startActivity(Intent(this, OptionsSettingsActivity::class.java).putExtra(OptionsSettingsActivity.PAGE, page))
    }

    override fun onResume() {
        super.onResume()
        updateSetupStatus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::enableStatus.isInitialized) updateSetupStatus()
    }

    private fun updateSetupStatus() {
        val enabled = getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.packageName == packageName }
        val selected = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/') == packageName
        enableStatus.visibility = if (enabled) View.VISIBLE else View.GONE
        selectedStatus.visibility = if (selected) View.VISIBLE else View.GONE
        enableRow.contentDescription = getString(R.string.enable_keyboard) + if (enabled) ", ${getString(R.string.keyboard_enabled)}" else ""
        chooseRow.contentDescription = getString(R.string.choose_keyboard) + if (selected) ", ${getString(R.string.keyboard_selected)}" else ""
    }

    private fun section(title: Int): LinearLayout {
        ui.sectionHeading(content, title)
        return ui.card(content, topMarginDp = 0)
    }

    private fun row(parent: LinearLayout, title: Int, summary: ((KeyboardSettings) -> String)? = null,
        action: () -> Unit): View {
        val detail = summary?.let { ui.caption("") }
        val row = ui.actionRow(parent, title, detail, action = action)
        if (summary != null && detail != null) {
            val titleText = getString(title)
            val update: (KeyboardSettings) -> Unit = {
                val text = summary(it)
                detail.text = text
                row.contentDescription = "$titleText, $text"
            }
            update(current)
            homeBindings += update
        }
        return row
    }

    private fun toggle(parent: LinearLayout, title: Int,
        read: (KeyboardSettings) -> Boolean, description: Int? = null, openMenu: (() -> Unit)? = null,
        write: suspend (Boolean) -> Unit) {
        val toggle = (if (openMenu == null) ui.toggle(parent, title, description)
            else ui.toggleMenuRow(parent, title, description, openMenu)).apply {
            setOnCheckedChangeListener { _, checked -> if (!updating) scope.launch { write(checked) } }
        }
        homeBindings += { toggle.isChecked = read(it) }
    }

    private fun modeLabel(value: Int) = getString(when (value) {
        0 -> R.string.mode_normal_short
        1 -> R.string.mode_split_short
        else -> R.string.mode_inherit_short
    })
    private fun durationLabel(value: Int) = when (value) {
        200 -> getString(R.string.long_press_short); 300 -> getString(R.string.long_press_normal); 500 -> getString(R.string.long_press_long)
        else -> getString(R.string.milliseconds_value, value)
    }
    private fun speedLabel(value: Int) = getString(when { value >= 75 -> R.string.speed_slow; value <= 40 -> R.string.speed_fast; else -> R.string.speed_normal })
    private fun applyBindings() {
        updating = true
        try { homeBindings.forEach { it(current) } } finally { updating = false }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    private fun dp(value: Int) = ui.dp(value)
}
