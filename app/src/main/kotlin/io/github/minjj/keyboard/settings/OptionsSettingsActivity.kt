package io.github.minjj.keyboard.settings

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Toast
import io.github.minjj.keyboard.R
import io.github.minjj.keyboard.correction.SpatialStatsRepository
import io.github.minjj.keyboard.ui.KeyboardHaptics
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OptionsSettingsActivity : Activity() {
    private val ui by lazy { SettingsUi(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repository: SettingsRepository
    private val bindings = mutableListOf<(KeyboardSettings) -> Unit>()
    private var current = KeyboardSettings()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = intent.getIntExtra(PAGE, R.string.feedback_title)
        val body = ui.column(top = 0)
        repository = SettingsRepository(this)
        when (page) {
            R.string.delete_gesture -> deleteGestureSensitivity(ui.card(body))
            R.string.long_press_title -> choices(body,
                listOf(200 to R.string.long_press_short, 300 to R.string.long_press_normal, 500 to R.string.long_press_long),
                KeyboardSettings::longPressMillis, repository::setLongPress)
            R.string.repeat_title -> choices(body,
                listOf(90 to R.string.speed_slow, 55 to R.string.speed_normal, 30 to R.string.speed_fast),
                KeyboardSettings::repeatMillis, repository::setRepeat)
            R.string.space_gestures_title -> {
                val card = ui.card(body)
                toggle(card, R.string.space_cursor, KeyboardSettings::spaceCursor) { repository.setOption("spaceCursor", it) }
                toggle(card, R.string.space_language, KeyboardSettings::spaceLanguageSwipe) { repository.setOption("spaceLanguage", it) }
                spaceLanguageSensitivity(card)
            }
            R.string.feedback_title -> {
                val card = ui.card(body)
                toggle(card, R.string.vibration, KeyboardSettings::vibration) { repository.setFeedback("vibration", it) }
                vibrationStrength(card)
                toggle(card, R.string.sound, KeyboardSettings::sound) { repository.setFeedback("sound", it) }
            }
            R.string.touch_personalization -> buildPersonalization(body)
            R.string.lexicon_attribution -> {
                val notice = assets.open("language/NOTICE.md").bufferedReader().use { it.readText() }
                ui.card(body).addView(ui.text(notice).apply {
                    setPadding(0, ui.dp(12), 0, ui.dp(12))
                    setTextIsSelectable(true)
                })
            }
        }
        setContentView(ui.scrollPage(body, page) { finish() })
        if (bindings.isNotEmpty()) scope.launch {
            repository.settings.collect { settings ->
                current = settings
                bindings.forEach { it(settings) }
            }
        }
    }

    private fun deleteGestureSensitivity(parent: LinearLayout) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, ui.dp(6), 0, 0)
            addView(ui.text(getString(R.string.delete_gesture_sensitivity)).apply {
                labelFor = R.id.delete_gesture_sensitivity
            }, LinearLayout.LayoutParams(-1, -2))
        }
        ui.caption(row, getString(R.string.delete_gesture_sensitivity_description)).apply {
            maxLines = 3
            ellipsize = null
        }
        val caption = ui.caption(row, "")
        var trackingTouch = false
        val seek = SeekBar(this).apply {
            id = R.id.delete_gesture_sensitivity
            max = MAX_DELETE_GESTURE_STEP_DP - MIN_DELETE_GESTURE_STEP_DP
            keyProgressIncrement = 1
            minimumHeight = ui.dp(48)
            contentDescription = getString(R.string.delete_gesture_sensitivity)
            isEnabled = false
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(view: SeekBar, value: Int, fromUser: Boolean) {
                    val distance = value + MIN_DELETE_GESTURE_STEP_DP
                    val label = getString(R.string.delete_gesture_tick_value, distance)
                    caption.text = label
                    if (Build.VERSION.SDK_INT >= 30) view.stateDescription = label
                    if (fromUser && !trackingTouch) persist { repository.setDeleteGestureStepDp(distance) }
                }
                override fun onStartTrackingTouch(view: SeekBar) { trackingTouch = true }
                override fun onStopTrackingTouch(view: SeekBar) {
                    trackingTouch = false
                    val distance = view.progress + MIN_DELETE_GESTURE_STEP_DP
                    persist { repository.setDeleteGestureStepDp(distance) }
                }
            })
        }
        row.addView(seek, LinearLayout.LayoutParams(-1, -2))
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
        bindings += { settings ->
            if (!trackingTouch) seek.progress = settings.deleteGestureStepDp - MIN_DELETE_GESTURE_STEP_DP
            val label = getString(R.string.delete_gesture_tick_value, seek.progress + MIN_DELETE_GESTURE_STEP_DP)
            caption.text = label
            if (Build.VERSION.SDK_INT >= 30) seek.stateDescription = label
            seek.isEnabled = settings.deleteGesture
            row.alpha = if (seek.isEnabled) 1f else 0.55f
        }
    }

    private fun spaceLanguageSensitivity(parent: LinearLayout) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, ui.dp(6), 0, 0)
            addView(ui.text(getString(R.string.space_language_sensitivity)).apply {
                labelFor = R.id.space_language_sensitivity
            })
        }
        ui.caption(row, getString(R.string.space_language_sensitivity_description))
        val caption = ui.caption(row, "")
        var trackingTouch = false
        val seek = SeekBar(this).apply {
            id = R.id.space_language_sensitivity
            max = 100
            keyProgressIncrement = 1
            minimumHeight = ui.dp(48)
            contentDescription = getString(R.string.space_language_sensitivity)
            isEnabled = false
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(view: SeekBar, value: Int, fromUser: Boolean) {
                    val percent = getString(R.string.percent_value, value)
                    caption.text = percent
                    if (Build.VERSION.SDK_INT >= 30) view.stateDescription = percent
                    if (fromUser && !trackingTouch) persist { repository.setSpaceLanguageSensitivity(value) }
                }
                override fun onStartTrackingTouch(view: SeekBar) { trackingTouch = true }
                override fun onStopTrackingTouch(view: SeekBar) {
                    trackingTouch = false
                    val value = view.progress
                    persist { repository.setSpaceLanguageSensitivity(value) }
                }
            })
        }
        row.addView(seek, LinearLayout.LayoutParams(-1, -2))
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
        bindings += { settings ->
            if (!trackingTouch) seek.progress = settings.spaceLanguageSensitivity
            val percent = getString(R.string.percent_value, seek.progress)
            caption.text = percent
            if (Build.VERSION.SDK_INT >= 30) seek.stateDescription = percent
            seek.isEnabled = settings.spaceLanguageSwipe
            row.alpha = if (seek.isEnabled) 1f else 0.55f
        }
    }

    private fun vibrationStrength(parent: LinearLayout) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, ui.dp(6), 0, 0)
            addView(ui.text(getString(R.string.vibration_strength)).apply {
                labelFor = R.id.vibration_strength
            }, LinearLayout.LayoutParams(-1, -2))
        }
        val caption = ui.caption(row, "")
        var trackingTouch = false
        var lastPreview: Long? = null
        fun preview(strength: Int, dragging: Boolean) {
            if (!current.vibration) return
            val now = SystemClock.uptimeMillis()
            if (!dragging || lastPreview == null || now - lastPreview!! >= 80) {
                KeyboardHaptics.play(this, strength)
                lastPreview = now
            }
        }
        val seek = SeekBar(this).apply {
            id = R.id.vibration_strength
            max = 100
            keyProgressIncrement = 1
            minimumHeight = ui.dp(48)
            contentDescription = getString(R.string.vibration_strength)
            isEnabled = false
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(view: SeekBar, value: Int, fromUser: Boolean) {
                    val percent = getString(R.string.percent_value, value)
                    caption.text = percent
                    if (Build.VERSION.SDK_INT >= 30) view.stateDescription = percent
                    if (!fromUser) return
                    if (!trackingTouch) persist { repository.setVibrationStrength(value) }
                    preview(value, trackingTouch)
                }
                override fun onStartTrackingTouch(view: SeekBar) {
                    trackingTouch = true
                    preview(view.progress, true)
                }
                override fun onStopTrackingTouch(view: SeekBar) {
                    trackingTouch = false
                    val value = view.progress
                    persist { repository.setVibrationStrength(value) }
                    preview(value, false)
                }
            })
        }
        row.addView(seek, LinearLayout.LayoutParams(-1, -2))
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
        bindings += { settings ->
            if (!trackingTouch) {
                seek.progress = settings.vibrationStrength
                val percent = getString(R.string.percent_value, settings.vibrationStrength)
                caption.text = percent
                if (Build.VERSION.SDK_INT >= 30) seek.stateDescription = percent
            }
            seek.isEnabled = settings.vibration
            row.alpha = if (seek.isEnabled) 1f else 0.55f
        }
    }

    private fun buildPersonalization(body: LinearLayout) {
        val card = ui.card(body)
        toggle(card, R.string.touch_correction, KeyboardSettings::touchCorrection) { repository.setOption("touchCorrection", it) }
        toggle(card, R.string.touch_learning, KeyboardSettings::touchLearning, R.string.touch_learning_description) {
            repository.setOption("touchLearning", it)
        }
        toggle(card, R.string.contextual_touch, KeyboardSettings::contextualTouch) { repository.setOption("contextualTouch", it) }
        toggle(card, R.string.word_suggestions, KeyboardSettings::wordSuggestions, R.string.word_suggestions_description) {
            repository.setOption("wordSuggestions", it)
        }
        val practice = ui.card(body)
        ui.actionRow(practice, R.string.guided_training_title) {
            startActivity(Intent(this, TouchLearningActivity::class.java))
        }
        val data = ui.card(body)
        ui.actionRow(data, R.string.lexicon_attribution) {
            startActivity(Intent(this, OptionsSettingsActivity::class.java).putExtra(PAGE, R.string.lexicon_attribution))
        }
        ui.actionRow(data, R.string.touch_learning_reset) {
            scope.launch {
                val cleared = SpatialStatsRepository.get(this@OptionsSettingsActivity).clear()
                val message = if (cleared) R.string.touch_learning_reset_done else R.string.touch_learning_reset_failed
                Toast.makeText(this@OptionsSettingsActivity, message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggle(parent: LinearLayout, title: Int, read: (KeyboardSettings) -> Boolean, description: Int? = null,
        write: suspend (Boolean) -> Unit) {
        val control = ui.toggle(parent, title, description).apply {
            isEnabled = false
            setOnClickListener {
                val checked = isChecked
                persist { write(checked) }
            }
        }
        bindings += { settings ->
            control.isChecked = read(settings)
            control.isEnabled = true
        }
    }

    private fun choices(parent: LinearLayout, options: List<Pair<Int, Int>>, read: (KeyboardSettings) -> Int,
        write: suspend (Int) -> Unit) {
        val group = ui.radioCard(parent)
        val buttons = options.associate { (value, label) ->
            value to ui.radio(group, getString(label)).apply {
                isEnabled = false
                setOnClickListener { persist { write(value) } }
            }
        }
        bindings += { settings ->
            group.check(buttons[read(settings)]?.id ?: -1)
            buttons.values.forEach { it.isEnabled = true }
        }
    }

    private fun persist(write: suspend () -> Unit) {
        scope.launch {
            try {
                withContext(NonCancellable) { write() }
            } catch (_: IOException) {
                if (!isDestroyed) {
                    bindings.forEach { it(current) }
                    Toast.makeText(this@OptionsSettingsActivity, R.string.layout_save_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val PAGE = "optionsPage"
    }
}
