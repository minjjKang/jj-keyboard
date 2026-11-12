package io.github.minjj.keyboard.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo

enum class InputLayout { TEXT, NUMBER, PHONE, DATETIME }

data class EditorProfile(
    val layout: InputLayout = InputLayout.TEXT,
    val password: Boolean = false,
    val email: Boolean = false,
    val uri: Boolean = false,
    val signed: Boolean = false,
    val decimal: Boolean = false,
    val action: Int = EditorInfo.IME_ACTION_NONE,
    val customActionLabel: String? = null,
    val customActionId: Int? = null,
    val capsMode: Int = 0,
    val privateLearning: Boolean = false,
    val suggestionsDisabled: Boolean = false,
    val standardText: Boolean = true,
) {
    val allowsSpatialModel get() = standardText && layout == InputLayout.TEXT && !password && !privateLearning
    val allowsSpatialLearning get() = allowsSpatialModel && !email && !uri && !suggestionsDisabled
    val allowsLanguageModel get() = layout == InputLayout.TEXT && allowsSpatialLearning

    companion object {
        fun from(info: EditorInfo): EditorProfile {
            val type = info.inputType
            val kind = type and InputType.TYPE_MASK_CLASS
            val variation = type and InputType.TYPE_MASK_VARIATION
            val text = kind == InputType.TYPE_CLASS_TEXT
            val password = (text && variation in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
                (kind == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
            val noEnterAction = info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
            val action = if (noEnterAction) EditorInfo.IME_ACTION_NONE else info.imeOptions and EditorInfo.IME_MASK_ACTION
            return EditorProfile(
                layout = when (kind) {
                    InputType.TYPE_CLASS_NUMBER -> InputLayout.NUMBER
                    InputType.TYPE_CLASS_PHONE -> InputLayout.PHONE
                    InputType.TYPE_CLASS_DATETIME -> InputLayout.DATETIME
                    else -> InputLayout.TEXT
                },
                password = password,
                email = text && variation in setOf(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS),
                uri = text && variation == InputType.TYPE_TEXT_VARIATION_URI,
                signed = kind == InputType.TYPE_CLASS_NUMBER && type and InputType.TYPE_NUMBER_FLAG_SIGNED != 0,
                decimal = kind == InputType.TYPE_CLASS_NUMBER && type and InputType.TYPE_NUMBER_FLAG_DECIMAL != 0,
                action = action,
                customActionLabel = if (noEnterAction) null else info.actionLabel?.toString(),
                customActionId = if (!noEnterAction && info.actionLabel != null) info.actionId else null,
                capsMode = if (text && !password) type and (InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or
                    InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_CAP_WORDS) else 0,
                privateLearning = info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0,
                suggestionsDisabled = type and (InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE) != 0,
                standardText = text,
            )
        }
    }
}
