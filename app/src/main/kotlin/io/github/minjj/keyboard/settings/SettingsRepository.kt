package io.github.minjj.keyboard.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException
import org.json.JSONObject

private val Context.keyboardDataStore by preferencesDataStore(name = "keyboard_settings")

internal const val MIN_SPLIT_GAP_DP = 24
internal const val MAX_SPLIT_GAP_DP = 320
internal const val MIN_NUMBER_ROW_HEIGHT_PERCENT = 70
internal const val MAX_NUMBER_ROW_HEIGHT_PERCENT = 130
internal const val MIN_KEY_GAP_DP = 0
internal const val MAX_KEY_GAP_DP = 12
internal const val DEFAULT_KEY_GAP_DP = 6
internal const val MIN_DELETE_GESTURE_STEP_DP = 4
internal const val MAX_DELETE_GESTURE_STEP_DP = 48
internal const val DEFAULT_DELETE_GESTURE_STEP_DP = 18
private const val DEFAULT_SPLIT_GAP_DP = 56

data class KeyboardSettings(
    val narrowHeight: Int = 54,
    val wideHeight: Int = 62,
    val narrowSplit: Boolean = false,
    val wideSplit: Boolean = false,
    val vibration: Boolean = true,
    val sound: Boolean = false,
    val preview: Boolean = true,
    val fontPercent: Int = 100,
    val longPressMillis: Int = 300,
    val repeatMillis: Int = 55,
    val secondaryCharacters: Boolean = true,
    val numberRow: Boolean = true,
    val extendedLayout: Boolean = true,
    val toolbar: Boolean = true,
    val highContrast: Boolean = false,
    val spaceCursor: Boolean = true,
    val spaceLanguageSwipe: Boolean = true,
    val autoCapitalization: Boolean = true,
    val doubleSpacePeriod: Boolean = false,
    val portraitMode: Int = -1,
    val landscapeMode: Int = -1,
    val deleteGesture: Boolean = true,
    val clipboardHistory: Boolean = true,
    val portraitHeightPercent: Int = 100,
    val landscapeHeightPercent: Int = 100,
    val portraitWidthPercent: Int = 100,
    val landscapeWidthPercent: Int = 100,
    val touchCorrection: Boolean = false,
    val touchLearning: Boolean = false,
    val contextualTouch: Boolean = false,
    val wordSuggestions: Boolean = false,
    val portraitSplitGap: Int = DEFAULT_SPLIT_GAP_DP,
    val landscapeSplitGap: Int = DEFAULT_SPLIT_GAP_DP,
    val numberRowHeightPercent: Int = 100,
    val vibrationStrength: Int = 50,
    val horizontalKeyGap: Int = DEFAULT_KEY_GAP_DP,
    val verticalKeyGap: Int = DEFAULT_KEY_GAP_DP,
    val spaceLanguageSensitivity: Int = 50,
    val secondaryOverrides: Map<String, String> = emptyMap(),
    val symbolOverrides: Map<String, String> = emptyMap(),
    val deleteGestureStepDp: Int = DEFAULT_DELETE_GESTURE_STEP_DP,
) {
    fun splitFor(wide: Boolean, landscape: Boolean): Boolean = when (if (landscape) landscapeMode else portraitMode) {
        0 -> false
        1 -> true
        else -> if (wide) wideSplit else narrowSplit
    }

    fun splitGapFor(landscape: Boolean): Int = if (landscape) landscapeSplitGap else portraitSplitGap
}

/** Only explicit UI preferences belong here. Never pass editor text to this repository. */
class SettingsRepository(context: Context) {
    private val store = context.applicationContext.keyboardDataStore
    val settings = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }.map {
        val legacySplitGap = (it[SPLIT_GAP] ?: DEFAULT_SPLIT_GAP_DP).coerceIn(MIN_SPLIT_GAP_DP, 160)
        KeyboardSettings(
            (it[NARROW_HEIGHT] ?: 54).coerceIn(40, 76), (it[WIDE_HEIGHT] ?: 62).coerceIn(40, 76),
            it[NARROW_SPLIT] ?: false, it[WIDE_SPLIT] ?: false,
            it[VIBRATION] ?: true, it[SOUND] ?: false, it[PREVIEW] ?: true,
            (it[FONT_PERCENT] ?: 100).coerceIn(80, 140),
            (it[LONG_PRESS] ?: 300).coerceIn(200, 1000),
            (it[REPEAT] ?: 55).coerceIn(25, 120),
            it[SECONDARY] ?: true, it[NUMBER_ROW] ?: true, it[EXTENDED] ?: true,
            it[TOOLBAR] ?: true, it[HIGH_CONTRAST] ?: false,
            it[SPACE_CURSOR] ?: true, it[SPACE_LANGUAGE] ?: true,
            it[AUTO_CAPS] ?: true, it[DOUBLE_SPACE] ?: false,
            (it[PORTRAIT_MODE] ?: -1).coerceIn(-1, 1), (it[LANDSCAPE_MODE] ?: -1).coerceIn(-1, 1),
            it[DELETE_GESTURE] ?: true, it[CLIPBOARD_HISTORY] ?: true,
            (it[PORTRAIT_HEIGHT] ?: 100).coerceIn(70, 130), (it[LANDSCAPE_HEIGHT] ?: 100).coerceIn(70, 130),
            (it[PORTRAIT_WIDTH] ?: 100).coerceIn(60, 100), (it[LANDSCAPE_WIDTH] ?: 100).coerceIn(60, 100),
            it[TOUCH_CORRECTION] ?: false, it[TOUCH_LEARNING] ?: false,
            it[CONTEXTUAL_TOUCH] ?: false, it[WORD_SUGGESTIONS] ?: false,
            portraitSplitGap = (it[PORTRAIT_SPLIT_GAP] ?: legacySplitGap).coerceIn(MIN_SPLIT_GAP_DP, MAX_SPLIT_GAP_DP),
            landscapeSplitGap = (it[LANDSCAPE_SPLIT_GAP] ?: legacySplitGap).coerceIn(MIN_SPLIT_GAP_DP, MAX_SPLIT_GAP_DP),
            numberRowHeightPercent = (it[NUMBER_ROW_HEIGHT] ?: 100)
                .coerceIn(MIN_NUMBER_ROW_HEIGHT_PERCENT, MAX_NUMBER_ROW_HEIGHT_PERCENT),
            vibrationStrength = (it[VIBRATION_STRENGTH] ?: 50).coerceIn(0, 100),
            horizontalKeyGap = (it[HORIZONTAL_KEY_GAP] ?: DEFAULT_KEY_GAP_DP).coerceIn(MIN_KEY_GAP_DP, MAX_KEY_GAP_DP),
            verticalKeyGap = (it[VERTICAL_KEY_GAP] ?: DEFAULT_KEY_GAP_DP).coerceIn(MIN_KEY_GAP_DP, MAX_KEY_GAP_DP),
            spaceLanguageSensitivity = (it[SPACE_LANGUAGE_SENSITIVITY] ?: 50).coerceIn(0, 100),
            secondaryOverrides = CharacterCustomization.decode(it[SECONDARY_OVERRIDES], secondary = true),
            symbolOverrides = CharacterCustomization.decode(it[SYMBOL_OVERRIDES], secondary = false),
            deleteGestureStepDp = (it[DELETE_GESTURE_STEP_DP] ?: DEFAULT_DELETE_GESTURE_STEP_DP)
                .coerceIn(MIN_DELETE_GESTURE_STEP_DP, MAX_DELETE_GESTURE_STEP_DP),
        )
    }
    suspend fun setSplit(wide: Boolean, split: Boolean) {
        store.edit { it[if (wide) WIDE_SPLIT else NARROW_SPLIT] = split }
    }
    suspend fun setFontPercent(value: Int) { store.edit { it[FONT_PERCENT] = value.coerceIn(80, 140) } }
    suspend fun setLongPress(value: Int) { store.edit { it[LONG_PRESS] = value.coerceIn(200, 1000) } }
    suspend fun setRepeat(value: Int) { store.edit { it[REPEAT] = value.coerceIn(25, 120) } }
    suspend fun setCharacter(secondary: Boolean, key: String, value: String?) {
        require(CharacterCustomization.validKey(key, secondary))
        require(value == null || CharacterCustomization.validValue(value, secondary))
        val preference = if (secondary) SECONDARY_OVERRIDES else SYMBOL_OVERRIDES
        store.edit {
            val values = CharacterCustomization.decode(it[preference], secondary).toMutableMap()
            if (value == null) values.remove(key) else values[key] = value
            it[preference] = JSONObject(values as Map<*, *>).toString()
        }
    }
    suspend fun setSpaceLanguageSensitivity(value: Int) { store.edit { it[SPACE_LANGUAGE_SENSITIVITY] = value.coerceIn(0, 100) } }
    suspend fun setDeleteGestureStepDp(value: Int) {
        store.edit { it[DELETE_GESTURE_STEP_DP] = value.coerceIn(MIN_DELETE_GESTURE_STEP_DP, MAX_DELETE_GESTURE_STEP_DP) }
    }
    suspend fun setVibrationStrength(value: Int) { store.edit { it[VIBRATION_STRENGTH] = value.coerceIn(0, 100) } }
    suspend fun setOrientationMode(landscape: Boolean, mode: Int) {
        store.edit { it[if (landscape) LANDSCAPE_MODE else PORTRAIT_MODE] = mode.coerceIn(-1, 1) }
    }
    suspend fun setOption(key: String, enabled: Boolean) {
        val preference = when (key) {
            "secondary" -> SECONDARY; "numberRow" -> NUMBER_ROW; "extended" -> EXTENDED
            "toolbar" -> TOOLBAR; "highContrast" -> HIGH_CONTRAST
            "spaceCursor" -> SPACE_CURSOR; "spaceLanguage" -> SPACE_LANGUAGE
            "autoCaps" -> AUTO_CAPS; "doubleSpace" -> DOUBLE_SPACE
            "deleteGesture" -> DELETE_GESTURE; "clipboardHistory" -> CLIPBOARD_HISTORY
            "touchCorrection" -> TOUCH_CORRECTION; "touchLearning" -> TOUCH_LEARNING
            "contextualTouch" -> CONTEXTUAL_TOUCH; "wordSuggestions" -> WORD_SUGGESTIONS
            else -> error("Unknown setting")
        }
        store.edit { it[preference] = enabled }
    }
    /** Restore an explicit settings snapshot, used by device tests after temporary changes. */
    suspend fun restoreSettings(value: KeyboardSettings) {
        store.edit {
            it[NARROW_HEIGHT] = value.narrowHeight; it[WIDE_HEIGHT] = value.wideHeight
            it[NARROW_SPLIT] = value.narrowSplit; it[WIDE_SPLIT] = value.wideSplit
            it[VIBRATION] = value.vibration; it[SOUND] = value.sound; it[PREVIEW] = value.preview
            it[VIBRATION_STRENGTH] = value.vibrationStrength.coerceIn(0, 100)
            it[PORTRAIT_SPLIT_GAP] = value.portraitSplitGap
            it[LANDSCAPE_SPLIT_GAP] = value.landscapeSplitGap
            it[FONT_PERCENT] = value.fontPercent
            it[LONG_PRESS] = value.longPressMillis; it[REPEAT] = value.repeatMillis
            it[SECONDARY] = value.secondaryCharacters; it[NUMBER_ROW] = value.numberRow
            it[NUMBER_ROW_HEIGHT] = value.numberRowHeightPercent
            it[HORIZONTAL_KEY_GAP] = value.horizontalKeyGap.coerceIn(MIN_KEY_GAP_DP, MAX_KEY_GAP_DP)
            it[VERTICAL_KEY_GAP] = value.verticalKeyGap.coerceIn(MIN_KEY_GAP_DP, MAX_KEY_GAP_DP)
            it[EXTENDED] = value.extendedLayout; it[TOOLBAR] = value.toolbar; it[HIGH_CONTRAST] = value.highContrast
            it[SPACE_CURSOR] = value.spaceCursor; it[SPACE_LANGUAGE] = value.spaceLanguageSwipe
            it[SPACE_LANGUAGE_SENSITIVITY] = value.spaceLanguageSensitivity.coerceIn(0, 100)
            it[AUTO_CAPS] = value.autoCapitalization; it[DOUBLE_SPACE] = value.doubleSpacePeriod
            it[PORTRAIT_MODE] = value.portraitMode; it[LANDSCAPE_MODE] = value.landscapeMode
            it[DELETE_GESTURE] = value.deleteGesture; it[CLIPBOARD_HISTORY] = value.clipboardHistory
            it[DELETE_GESTURE_STEP_DP] = value.deleteGestureStepDp.coerceIn(MIN_DELETE_GESTURE_STEP_DP, MAX_DELETE_GESTURE_STEP_DP)
            it[PORTRAIT_HEIGHT] = value.portraitHeightPercent; it[LANDSCAPE_HEIGHT] = value.landscapeHeightPercent
            it[PORTRAIT_WIDTH] = value.portraitWidthPercent; it[LANDSCAPE_WIDTH] = value.landscapeWidthPercent
            it[TOUCH_CORRECTION] = value.touchCorrection; it[TOUCH_LEARNING] = value.touchLearning
            it[CONTEXTUAL_TOUCH] = value.contextualTouch; it[WORD_SUGGESTIONS] = value.wordSuggestions
            it[SECONDARY_OVERRIDES] = JSONObject(value.secondaryOverrides as Map<*, *>).toString()
            it[SYMBOL_OVERRIDES] = JSONObject(value.symbolOverrides as Map<*, *>).toString()
        }
    }
    suspend fun saveSizing(initial: KeyboardSettings, draft: KeyboardSettings) {
        store.edit {
            if (initial.narrowHeight != draft.narrowHeight) it[NARROW_HEIGHT] = draft.narrowHeight
            if (initial.wideHeight != draft.wideHeight) it[WIDE_HEIGHT] = draft.wideHeight
            if (initial.portraitSplitGap != draft.portraitSplitGap) it[PORTRAIT_SPLIT_GAP] = draft.portraitSplitGap
            if (initial.landscapeSplitGap != draft.landscapeSplitGap) it[LANDSCAPE_SPLIT_GAP] = draft.landscapeSplitGap
            if (initial.portraitHeightPercent != draft.portraitHeightPercent) it[PORTRAIT_HEIGHT] = draft.portraitHeightPercent
            if (initial.landscapeHeightPercent != draft.landscapeHeightPercent) it[LANDSCAPE_HEIGHT] = draft.landscapeHeightPercent
            if (initial.portraitWidthPercent != draft.portraitWidthPercent) it[PORTRAIT_WIDTH] = draft.portraitWidthPercent
            if (initial.landscapeWidthPercent != draft.landscapeWidthPercent) it[LANDSCAPE_WIDTH] = draft.landscapeWidthPercent
            if (initial.numberRowHeightPercent != draft.numberRowHeightPercent) it[NUMBER_ROW_HEIGHT] = draft.numberRowHeightPercent
            if (initial.horizontalKeyGap != draft.horizontalKeyGap) {
                it[HORIZONTAL_KEY_GAP] = draft.horizontalKeyGap.coerceIn(MIN_KEY_GAP_DP, MAX_KEY_GAP_DP)
            }
            if (initial.verticalKeyGap != draft.verticalKeyGap) {
                it[VERTICAL_KEY_GAP] = draft.verticalKeyGap.coerceIn(MIN_KEY_GAP_DP, MAX_KEY_GAP_DP)
            }
        }
    }
    suspend fun setFeedback(key: String, enabled: Boolean) {
        val preference = when (key) { "vibration" -> VIBRATION; "sound" -> SOUND; "preview" -> PREVIEW; else -> error("Unknown setting") }
        store.edit { it[preference] = enabled }
    }
    companion object {
        private val SECONDARY_OVERRIDES = stringPreferencesKey("secondary_overrides")
        private val SYMBOL_OVERRIDES = stringPreferencesKey("symbol_overrides")
        private val NARROW_HEIGHT = intPreferencesKey("narrow_height")
        private val WIDE_HEIGHT = intPreferencesKey("wide_height")
        private val NARROW_SPLIT = booleanPreferencesKey("narrow_split")
        private val WIDE_SPLIT = booleanPreferencesKey("wide_split")
        private val VIBRATION = booleanPreferencesKey("vibration")
        private val VIBRATION_STRENGTH = intPreferencesKey("vibration_strength")
        private val SOUND = booleanPreferencesKey("sound")
        private val PREVIEW = booleanPreferencesKey("preview")
        private val SPLIT_GAP = intPreferencesKey("split_gap")
        private val PORTRAIT_SPLIT_GAP = intPreferencesKey("portrait_split_gap")
        private val LANDSCAPE_SPLIT_GAP = intPreferencesKey("landscape_split_gap")
        private val FONT_PERCENT = intPreferencesKey("font_percent")
        private val LONG_PRESS = intPreferencesKey("long_press_ms")
        private val REPEAT = intPreferencesKey("repeat_ms")
        private val SECONDARY = booleanPreferencesKey("secondary")
        private val NUMBER_ROW = booleanPreferencesKey("number_row")
        private val NUMBER_ROW_HEIGHT = intPreferencesKey("number_row_height_percent")
        private val HORIZONTAL_KEY_GAP = intPreferencesKey("horizontal_key_gap")
        private val VERTICAL_KEY_GAP = intPreferencesKey("vertical_key_gap")
        private val EXTENDED = booleanPreferencesKey("extended")
        private val TOOLBAR = booleanPreferencesKey("toolbar")
        private val HIGH_CONTRAST = booleanPreferencesKey("high_contrast")
        private val SPACE_CURSOR = booleanPreferencesKey("space_cursor")
        private val SPACE_LANGUAGE = booleanPreferencesKey("space_language")
        private val SPACE_LANGUAGE_SENSITIVITY = intPreferencesKey("space_language_sensitivity")
        private val AUTO_CAPS = booleanPreferencesKey("auto_caps")
        private val DOUBLE_SPACE = booleanPreferencesKey("double_space")
        private val PORTRAIT_MODE = intPreferencesKey("portrait_mode")
        private val LANDSCAPE_MODE = intPreferencesKey("landscape_mode")
        private val DELETE_GESTURE = booleanPreferencesKey("delete_gesture")
        private val DELETE_GESTURE_STEP_DP = intPreferencesKey("delete_gesture_step_dp")
        private val CLIPBOARD_HISTORY = booleanPreferencesKey("clipboard_history")
        private val PORTRAIT_HEIGHT = intPreferencesKey("portrait_height_percent")
        private val LANDSCAPE_HEIGHT = intPreferencesKey("landscape_height_percent")
        private val PORTRAIT_WIDTH = intPreferencesKey("portrait_width_percent")
        private val LANDSCAPE_WIDTH = intPreferencesKey("landscape_width_percent")
        private val TOUCH_CORRECTION = booleanPreferencesKey("touch_correction")
        private val TOUCH_LEARNING = booleanPreferencesKey("touch_learning")
        private val CONTEXTUAL_TOUCH = booleanPreferencesKey("contextual_touch")
        private val WORD_SUGGESTIONS = booleanPreferencesKey("word_suggestions")
    }
}

/** Explicit key assignments only; empty secondary values disable that key's long press. */
internal object CharacterCustomization {
    private val slot = Regex("(normal|extended):[12]:[0-4]:([0-9]|10)")
    fun validKey(key: String, secondary: Boolean): Boolean = if (secondary) {
        key.removePrefix("symbol:").let { key.startsWith("symbol:") && slot.matches(it) } ||
            Regex("(ko|en):[a-z0-9,./@]").matches(key)
    } else slot.matches(key)

    fun validValue(value: String, secondary: Boolean): Boolean =
        (value.isNotEmpty() || secondary) && value.codePointCount(0, value.length) <= 8 &&
            value.codePoints().noneMatch { Character.isISOControl(it) || Character.isWhitespace(it) || Character.isSpaceChar(it) }

    fun decode(raw: String?, secondary: Boolean): Map<String, String> = try {
        val json = JSONObject(raw ?: "{}")
        buildMap {
            for (key in json.keys()) {
                val value = json.opt(key) as? String ?: continue
                if (validKey(key, secondary) && validValue(value, secondary)) put(key, value)
            }
        }
    } catch (_: org.json.JSONException) { emptyMap() }
}
