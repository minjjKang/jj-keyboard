package io.github.minjj.keyboard.ime

import android.text.Editable
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.widget.Button
import io.github.minjj.keyboard.R
import io.github.minjj.keyboard.correction.loadLanguage
import io.github.minjj.keyboard.settings.KeyboardSettings
import io.github.minjj.keyboard.ui.KeyboardView
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class WordSuggestionsTest {
    private class Fixture(text: String = "", val korean: Boolean = true, profile: EditorProfile = EditorProfile()) {
        private val context = RuntimeEnvironment.getApplication()
        val buffer = SpannableStringBuilder(text).also { Selection.setSelection(it, it.length) }
        var surroundingReads = 0
        val connection = object : BaseInputConnection(View(context), true) {
            override fun getEditable(): Editable = buffer
            override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence? {
                surroundingReads++
                return super.getTextBeforeCursor(length, flags)
            }
        }
        val session = EditorSession(connection, profile).apply { startCursor(text.length) }
        lateinit var keyboard: KeyboardView
        val controller = KeyboardController({ session }, {}, {}, {}, { if (::keyboard.isInitialized) keyboard.refresh() })

        init {
            controller.subtype(korean)
            controller.configureLanguageOptions(false, true)
            controller.loadLanguage(korean, runBlocking { loadLanguage(context, korean) }!!)
            keyboard = KeyboardView(context, controller).apply {
                bind(profile, KeyboardSettings(wordSuggestions = true))
                measure(View.MeasureSpec.makeMeasureSpec(411, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(891, View.MeasureSpec.AT_MOST))
                layout(0, 0, measuredWidth, measuredHeight)
            }
        }

        fun type(keys: String) = keys.forEach { controller.press(KeySpec(it.toString(), KeyCommand.CHARACTER, letter = true)) }
        fun press(command: KeyCommand) = controller.press(KeySpec("", command))
        fun assertCandidate(): Button {
            val button = keyboard.findViewById<Button>(R.id.key_word_suggestion_0)
            assertEquals("Candidate for fixture text '$buffer'", View.VISIBLE, button.visibility)
            assertTrue(button.text.isNotEmpty())
            return button
        }
    }

    @Test fun `real dictionaries display Korean and English candidates in an empty editor`() {
        for (korean in listOf(true, false)) {
            val fixture = Fixture(korean = korean)
            fixture.type(if (korean) "rksk" else "hel")
            fixture.assertCandidate()
        }
    }

    @Test fun `candidates start after existing whitespace without initial surrounding text`() {
        val fixture = Fixture("기존 문장 ")
        fixture.type("rksk")
        fixture.assertCandidate()
    }

    @Test fun `candidates restart after hiding and showing the keyboard at a word boundary`() {
        val fixture = Fixture()
        fixture.type("rksk")
        fixture.press(KeyCommand.SPACE)
        fixture.session.clearWordContext()
        fixture.type("rksk")
        fixture.assertCandidate()
    }

    @Test fun `candidates start on a new line`() {
        val fixture = Fixture()
        fixture.type("rksk")
        fixture.press(KeyCommand.ENTER)
        fixture.type("rksk")
        fixture.assertCandidate()
    }

    @Test fun `candidates restart after deleting the entire first word`() {
        val fixture = Fixture()
        fixture.type("rk")
        repeat(2) { fixture.press(KeyCommand.DELETE) }
        assertEquals("", fixture.buffer.toString())
        fixture.type("rksk")
        fixture.assertCandidate()
    }

    @Test fun `a recovered candidate replaces only the new word and supports undo`() {
        for (korean in listOf(true, false)) {
            val fixture = Fixture("기존 문장 ", korean)
            fixture.type(if (korean) "rksk" else "hel")
            val literal = fixture.buffer.toString()
            val button = fixture.assertCandidate()
            val candidate = button.text.toString()
            button.performClick()
            assertEquals("기존 문장 $candidate", fixture.buffer.toString())
            fixture.press(KeyCommand.DELETE)
            assertEquals(literal, fixture.buffer.toString())
        }
    }

    @Test fun `recovery does not adopt an existing partial word or read context on every key`() {
        val fixture = Fixture("기존")
        fixture.type("rksk")
        assertTrue(fixture.controller.wordOffers().isEmpty())
        assertEquals(1, fixture.surroundingReads)
        fixture.press(KeyCommand.SPACE)
        fixture.type("rksk")
        fixture.assertCandidate()
        assertEquals(1, fixture.surroundingReads)
    }

    @Test fun `recovery does not read surrounding text in excluded fields`() {
        for (profile in listOf(EditorProfile(password = true), EditorProfile(privateLearning = true),
            EditorProfile(suggestionsDisabled = true), EditorProfile(email = true), EditorProfile(uri = true))) {
            val fixture = Fixture("기존 문장 ", profile = profile)
            fixture.type("rksk")
            assertTrue(fixture.controller.wordOffers().isEmpty())
            assertEquals(0, fixture.surroundingReads)
        }
    }

    @Test fun `recovery waits for a matching collapsed selection`() {
        val fixture = Fixture("기존 문장 ")
        Selection.setSelection(fixture.buffer, 3, fixture.buffer.length)
        fixture.session.selectionChanged(3, fixture.buffer.length, -1, -1)
        fixture.session.prepareWordContext(true)
        assertNull(fixture.session.wordSnapshot(true))
        assertEquals(0, fixture.surroundingReads)
        Selection.setSelection(fixture.buffer, fixture.buffer.length)
        fixture.session.selectionChanged(fixture.buffer.length, fixture.buffer.length, -1, -1)
        fixture.type("rksk")
        fixture.assertCandidate()
    }
}
