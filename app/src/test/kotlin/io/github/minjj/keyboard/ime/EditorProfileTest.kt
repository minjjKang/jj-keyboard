package io.github.minjj.keyboard.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EditorProfileTest {
    private fun profile(type: Int, options: Int = EditorInfo.IME_ACTION_DONE) = EditorProfile.from(EditorInfo().apply {
        inputType = type; imeOptions = options
    })

    @Test fun `personalized hit testing and learning respect editor restrictions`() {
        val text = InputType.TYPE_CLASS_TEXT
        assertTrue(profile(text).allowsSpatialLearning)
        assertFalse(profile(InputType.TYPE_NULL).allowsSpatialModel)
        assertFalse(profile(text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD).allowsSpatialModel)
        assertFalse(profile(text, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING).allowsSpatialModel)
        for (flag in listOf(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, InputType.TYPE_TEXT_FLAG_AUTO_COMPLETE)
        ) {
            assertTrue(profile(text or flag).allowsSpatialModel)
            assertFalse(profile(text or flag).allowsSpatialLearning)
        }
    }

    @Test fun `numeric phone and datetime pads remain outside text keyboard personalization`() {
        for (type in listOf(InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME)) {
            assertFalse(profile(type).allowsSpatialModel)
            assertFalse(profile(type).allowsSpatialLearning)
            assertFalse(profile(type).allowsLanguageModel)
            assertFalse(profile(type, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING).allowsSpatialModel)
        }
        assertFalse(profile(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD).allowsSpatialModel)
    }

    @Test fun `all password variants suppress previews and capitalization`() {
        listOf(InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD).forEach {
            val p = profile(InputType.TYPE_CLASS_TEXT or it or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
            assertTrue(p.password); assertEquals(0, p.capsMode)
        }
        assertTrue(profile(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD).password)
    }

    @Test fun `class flags do not leak across layouts`() {
        val number = profile(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        assertEquals(InputLayout.NUMBER, number.layout); assertTrue(number.signed); assertTrue(number.decimal)
        assertEquals(InputLayout.PHONE, profile(InputType.TYPE_CLASS_PHONE).layout)
        assertEquals(InputLayout.DATETIME, profile(InputType.TYPE_CLASS_DATETIME).layout)
        assertTrue(profile(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS).email)
        assertTrue(profile(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI).uri)
    }

    @Test fun `no enter action overrides action labels and action mask`() {
        val info = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION
            actionLabel = "Custom"; actionId = 42
        }
        val p = EditorProfile.from(info)
        assertEquals(EditorInfo.IME_ACTION_NONE, p.action)
        assertNull(p.customActionLabel); assertNull(p.customActionId)
    }
}
