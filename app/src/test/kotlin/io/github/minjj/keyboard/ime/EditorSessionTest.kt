package io.github.minjj.keyboard.ime

import android.content.Context
import android.text.Editable
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import io.github.minjj.keyboard.hangul.Dubeolsik
import io.github.minjj.keyboard.touch.WordCandidate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EditorSessionTest {
    @Test fun `word selection replaces the exact composed range and backspace restores Hangul history`() {
        val editor = Editor()
        val session = editor.session()
        "rksk".forEach {
            assertTrue(session.jamo(Dubeolsik.map(it)!!))
            session.recordLetter(it, "")
        }
        val prior = session.wordSnapshot(true)!!
        val candidate = WordCandidate("가나다", "rkskek")
        assertTrue(session.selectWord(prior, candidate))
        assertEquals("가나다", editor.buffer.toString())
        session.backspace()
        assertEquals("가나", editor.buffer.toString())
        session.backspace()
        assertEquals("가ㄴ", editor.buffer.toString())
    }

    @Test fun `word selection refuses a stale candidate or an externally edited suffix`() {
        val editor = Editor()
        val session = editor.session()
        "he".forEach { session.text(it.toString()); session.recordLetter(it, "") }
        val stale = session.wordSnapshot(false)!!
        session.text("l"); session.recordLetter('l', "")
        val candidate = WordCandidate("hello", "hello")
        assertFalse(session.selectWord(stale, candidate))
        val current = session.wordSnapshot(false)!!
        editor.buffer.replace(2, 3, "x")
        assertFalse(session.selectWord(current, candidate))
        assertEquals("hex", editor.buffer.toString())
    }

    @Test fun `a rejected candidate span leaves ordinary input available`() {
        val editor = Editor("good ")
        val session = editor.session()
        session.startCursor(5, "good ")
        assertEquals("good", session.wordSnapshot(false)?.previous)
        "mo".forEach { session.text(it.toString()); session.recordLetter(it, "") }
        editor.rejectRegion = true
        assertFalse(session.selectWord(session.wordSnapshot(false)!!, WordCandidate("morning", "morning")))
        assertTrue(session.text("r"))
        assertEquals("good mor", editor.buffer.toString())
    }
    @Test fun `rotation checkpoint restores a verified span and keeps input history`() {
        val editor = Editor(); val first = editor.session(); first.type("rkqt")
        val checkpoint = first.checkpoint()!!
        first.invalidate(); editor.finishComposingText()
        val next = editor.session()
        assertTrue(next.restore(checkpoint)); next.backspace()
        assertEquals("갑", editor.buffer.toString())
        val mismatched = Editor("다른값").session()
        assertFalse(mismatched.restore(checkpoint))
        assertNull(Editor("secret").session(EditorProfile(password = true)).checkpoint())
    }
    @Test fun `slide deletion restores graphemes in order but not after another edit`() {
        val original = "앞👨‍👩‍👧‍👦e\u0301한글"
        val editor = Editor(original); val session = editor.session()
        session.slideDelete(-3); assertEquals("앞👨‍👩‍👧‍👦", editor.buffer.toString())
        session.slideDelete(2); assertEquals("앞👨‍👩‍👧‍👦e\u0301한", editor.buffer.toString())
        session.slideDelete(-1); session.slideDelete(3); assertEquals(original, editor.buffer.toString())
        session.slideDelete(-1); session.text("!"); session.slideDelete(1)
        assertEquals("앞👨‍👩‍👧‍👦e\u0301한!", editor.buffer.toString())
    }

    @Test fun `slide undo guards external changes cursor moves selection and passwords`() {
        val editor = Editor("abcXYZ"); val session = editor.session()
        Selection.setSelection(editor.buffer, 3, 6); session.selectionChanged(3, 6, -1, -1)
        session.slideDelete(-1); assertEquals("abc", editor.buffer.toString())
        session.slideDelete(1); assertEquals("abcXYZ", editor.buffer.toString())
        session.slideDelete(-1); editor.buffer.replace(0, 1, "!"); session.slideDelete(1)
        assertEquals("!bcXY", editor.buffer.toString())
        session.slideDelete(-1); session.moveCursor(-1); session.slideDelete(1)
        assertEquals("!bcX", editor.buffer.toString())
        val secret = Editor("password"); secret.session(EditorProfile(password = true)).slideDelete(-4)
        assertEquals("password", secret.buffer.toString())
    }

    @Test fun `slide delete finishes active Hangul composition and cannot cross sessions`() {
        val editor = Editor(); val session = editor.session(); session.type("rkqt")
        session.slideDelete(-1); assertEquals("", editor.buffer.toString())
        session.slideDelete(1); assertEquals("값", editor.buffer.toString())
        session.slideDelete(-1); session.invalidate(); session.slideDelete(1)
        assertEquals("", editor.buffer.toString())
    }

    @Test fun `word slide deletes punctuation with each word and restores whole steps`() {
        val original = "앞 can't 마지막!  "
        val editor = Editor(original)
        val session = editor.session()
        assertEquals(-2, session.slideDelete(-2, byWord = true))
        assertEquals("앞 ", editor.buffer.toString())
        assertEquals(1, session.slideDelete(1))
        assertEquals("앞 can't ", editor.buffer.toString())
        assertEquals(1, session.slideDelete(1))
        assertEquals(original, editor.buffer.toString())
    }

    @Test fun `word slide leaves a word intact when the editor query starts inside it`() {
        val original = "a".repeat(4097)
        val editor = Editor(original)
        assertEquals(0, editor.session().slideDelete(-1, byWord = true))
        assertEquals(original, editor.buffer.toString())
    }
    private class Editor(text: String = "") : BaseInputConnection(View(RuntimeEnvironment.getApplication()), true) {
        val buffer = SpannableStringBuilder(text).also { Selection.setSelection(it, it.length) }
        var failWrite = false
        var writeCount = 0
        var action: Int? = null
        var rejectRegion = false
        override fun getEditable(): Editable = buffer
        override fun setComposingRegion(start: Int, end: Int): Boolean =
            !rejectRegion && super.setComposingRegion(start, end)
        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            writeCount++
            return if (failWrite) false else super.setComposingText(text, newCursorPosition)
        }
        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            writeCount++
            return if (failWrite) false else super.commitText(text, newCursorPosition)
        }
        override fun performEditorAction(editorAction: Int): Boolean { action = editorAction; return true }
    }
    private fun Editor.session(profile: EditorProfile = EditorProfile()) = EditorSession(this, profile).apply {
        startCursor(Selection.getSelectionEnd(buffer))
    }
    private fun EditorSession.type(keys: String) { keys.forEach { jamo(Dubeolsik.map(it)!!) } }

    @Test fun `composition migration updates editor span without duplicating prefix`() {
        val editor = Editor(); val session = editor.session()
        session.type("rkqtl")
        assertEquals("갑시", editor.buffer.toString())
        session.backspace(); assertEquals("갑ㅅ", editor.buffer.toString())
        session.backspace(); assertEquals("갑", editor.buffer.toString())
        session.text(" "); session.text("Hello")
        assertEquals("갑 Hello", editor.buffer.toString())
    }

    @Test fun `selection is replaced and selection deletion does not delete surrounding text`() {
        val editor = Editor("앞 선택 뒤")
        Selection.setSelection(editor.buffer, 2, 4)
        val session = editor.session()
        session.startCursor(2); session.type("gks")
        assertEquals("앞 한 뒤", editor.buffer.toString())
        session.finishComposition()
        Selection.setSelection(editor.buffer, 0, 2)
        session.selectionChanged(0, 2, -1, -1)
        session.backspace(); assertEquals("한 뒤", editor.buffer.toString())
    }

    @Test fun `cursor move ends composition and starts at new location`() {
        val editor = Editor("앞뒤"); val session = editor.session()
        session.type("rk")
        assertEquals("앞뒤가", editor.buffer.toString())
        Selection.setSelection(editor.buffer, 0)
        session.selectionChanged(0, 0, -1, -1)
        session.type("sk")
        assertEquals("나앞뒤가", editor.buffer.toString())
    }

    @Test fun `queued own selection callbacks preserve ongoing composition`() {
        val editor = Editor(); val session = editor.session()
        session.type("rksk")
        session.selectionChanged(1, 1, 0, 1)
        session.selectionChanged(1, 1, 0, 1)
        session.selectionChanged(1, 1, 0, 1)
        session.selectionChanged(2, 2, 1, 2)
        session.type("s")
        assertEquals("가난", editor.buffer.toString())
    }

    @Test fun `editor without initial selection can acknowledge the first composing span`() {
        val editor = Editor(); val session = EditorSession(editor, EditorProfile())
        session.startCursor(-1)
        session.type("r"); session.selectionChanged(1, 1, 0, 1)
        session.type("k"); session.selectionChanged(1, 1, 0, 1)
        session.type("s")
        assertEquals("간", editor.buffer.toString())
    }

    @Test fun `failed or invalidated connection never receives retries`() {
        val editor = Editor(); val session = editor.session()
        editor.failWrite = true
        assertFalse(session.jamo('ㄱ'))
        assertFalse(session.jamo('ㅏ'))
        assertFalse(session.text("x"))
        session.backspace()
        assertEquals(1, editor.writeCount)
        val next = Editor(); val nextSession = next.session()
        session.invalidate()
        assertTrue(nextSession.jamo('ㄴ'))
        assertTrue(nextSession.jamo('ㅏ'))
        assertEquals("나", next.buffer.toString()); assertEquals("", editor.buffer.toString())
    }

    @Test fun `finish commits current composing span only once`() {
        val editor = Editor(); val session = editor.session()
        session.type("rhk"); session.finishComposition(); session.finishComposition(); session.close()
        assertEquals("과", editor.buffer.toString())
        assertEquals(-1, BaseInputConnection.getComposingSpanStart(editor.buffer))
    }

    @Test fun `committed text deletion respects grapheme clusters`() {
        listOf("가", "😀", "👍🏽", "👨‍👩‍👧‍👦", "🇰🇷", "e\u0301", "1\uFE0F\u20E3").forEach { cluster ->
            val editor = Editor("앞$cluster"); editor.session().backspace()
            assertEquals(cluster, "앞", editor.buffer.toString())
        }
    }

    @Test fun `enter actions and literal line breaks are distinct`() {
        val editor = Editor(); val session = editor.session(EditorProfile(action = EditorInfo.IME_ACTION_SEARCH))
        session.type("rk"); session.enter()
        assertEquals(EditorInfo.IME_ACTION_SEARCH, editor.action)
        assertEquals("가", editor.buffer.toString())
        val multiline = Editor(); multiline.session().enter(); assertEquals("\n", multiline.buffer.toString())
    }

    @Test fun `cursor keys traverse committed emoji by grapheme rather than UTF16 unit`() {
        val emoji = "👨‍👩‍👧‍👦"
        val editor = Editor("A${emoji}B"); val session = editor.session()
        session.moveCursor(-1)
        assertEquals(1 + emoji.length, Selection.getSelectionEnd(editor.buffer))
        session.moveCursor(-1)
        assertEquals(1, Selection.getSelectionEnd(editor.buffer))
        session.moveCursor(1)
        assertEquals(1 + emoji.length, Selection.getSelectionEnd(editor.buffer))
        session.text("!")
        assertEquals("A${emoji}!B", editor.buffer.toString())
    }

    @Test fun `forward delete removes the next complete grapheme without touching the prefix`() {
        val editor = Editor("앞👍🏽뒤")
        Selection.setSelection(editor.buffer, 1)
        val session = editor.session()
        session.deleteForward()
        assertEquals("앞뒤", editor.buffer.toString())
        assertEquals(1, Selection.getSelectionEnd(editor.buffer))
    }

    @Test fun `selection movement keeps its anchor and reverses across whole graphemes`() {
        val emoji = "👨‍👩‍👧‍👦"
        val value = "Ae\u0301${emoji}B"
        val editor = Editor(value); val session = editor.session()
        val anchor = 3
        Selection.setSelection(editor.buffer, anchor)
        fun move(direction: Int, target: Int) {
            session.moveCursor(direction, extendSelection = true)
            assertEquals(anchor, Selection.getSelectionStart(editor.buffer))
            assertEquals(target, Selection.getSelectionEnd(editor.buffer))
            session.selectionChanged(anchor, target, -1, -1)
            assertEquals(value, editor.buffer.toString())
        }
        move(-1, 1); move(-1, 0); move(-1, 0)
        move(1, 1); move(1, anchor); move(1, anchor + emoji.length)
        move(1, value.length); move(1, value.length)
        move(-1, anchor + emoji.length); move(-1, anchor)
        move(-1, 1)
        session.moveCursor(1)
        assertEquals(anchor, Selection.getSelectionStart(editor.buffer))
        assertEquals(anchor, Selection.getSelectionEnd(editor.buffer))
    }

    @Test fun `held Shift cursor gesture selects composed Hangul and is not a Shift tap`() {
        val editor = Editor(); val session = editor.session()
        session.type("rksk")
        val controller = KeyboardController({ session }, {}, {}, {}, {})
        controller.holdShift(); controller.beginCursorGesture(); controller.moveCursor(-2)
        assertEquals("가나", editor.getSelectedText(0).toString())
        assertEquals(-1, BaseInputConnection.getComposingSpanStart(editor.buffer))
        assertFalse(controller.releaseShift(false))
        assertEquals(ShiftState.OFF, controller.shift)
        controller.press(KeySpec("1", KeyCommand.CHARACTER))
        assertEquals("1", editor.buffer.toString())
    }

    @Test fun `manual Shift selects but automatic capitalization only moves the cursor`() {
        val editor = Editor("ab"); val session = editor.session()
        val controller = KeyboardController({ session }, {}, {}, {}, {})
        controller.tapShift(1000); controller.beginCursorGesture(); controller.moveCursor(-1)
        assertEquals("b", editor.getSelectedText(0).toString())
        controller.tapShift(2000); controller.moveCursor(-1)
        assertEquals(1, Selection.getSelectionStart(editor.buffer))
        assertEquals(1, Selection.getSelectionEnd(editor.buffer))
        controller.tapShift(3000); controller.tapShift(3100)
        assertEquals(ShiftState.LOCKED, controller.shift)
        controller.moveCursor(1)
        assertEquals("b", editor.getSelectedText(0).toString())

        val automatic = Editor("A. "); val autoSession = automatic.session(EditorProfile(capsMode = android.text.TextUtils.CAP_MODE_SENTENCES))
        val auto = KeyboardController({ autoSession }, {}, {}, {}, {})
        auto.subtype(false)
        assertEquals(ShiftState.ONCE, auto.shift)
        auto.beginCursorGesture(); auto.moveCursor(-1)
        assertNull(automatic.getSelectedText(0))
        assertEquals(2, Selection.getSelectionEnd(automatic.buffer))
    }

    @Test @Config(sdk = [29]) fun `selection gestures use extracted text on legacy Android`() {
        val editor = android.widget.EditText(RuntimeEnvironment.getApplication()).apply {
            setText("가😀나")
            setSelection(text.length)
        }
        val connection = editor.onCreateInputConnection(EditorInfo())!!
        val session = EditorSession(connection, EditorProfile()).apply { startCursor(editor.length()) }
        session.moveCursor(-1, extendSelection = true)
        session.moveCursor(-1, extendSelection = true)
        assertEquals(4, editor.selectionStart)
        assertEquals(1, editor.selectionEnd)
        session.moveCursor(1, extendSelection = true)
        assertEquals(4, editor.selectionStart)
        assertEquals(3, editor.selectionEnd)
        editor.setSelection(0)
        session.selectionChanged(0, 0, -1, -1)
        session.moveCursor(1, extendSelection = true)
        assertEquals(0, editor.selectionStart)
        assertEquals(1, editor.selectionEnd)
        session.invalidate(); session.moveCursor(1, extendSelection = true)
        assertEquals(1, editor.selectionEnd)
    }

    @Test fun `double space shortcut is opt in bounded by time and disabled for sensitive contexts`() {
        val editor = Editor("Hello"); val session = editor.session()
        session.space(true, 100); session.space(true, 300)
        assertEquals("Hello. ", editor.buffer.toString())
        val late = Editor("Hello"); val slow = late.session()
        slow.space(true, 100); slow.space(true, 701)
        assertEquals("Hello  ", late.buffer.toString())
        for (profile in listOf(EditorProfile(password = true), EditorProfile(email = true), EditorProfile(uri = true))) {
            val sensitive = Editor("Hello"); val input = sensitive.session(profile)
            input.space(true, 100); input.space(true, 200)
            assertEquals("Hello  ", sensitive.buffer.toString())
        }
        val off = Editor("Hello"); val input = off.session()
        input.space(false, 100); input.space(false, 200)
        assertEquals("Hello  ", off.buffer.toString())
    }

    @Test @Config(sdk = [29]) fun `legacy Android cursor fallback avoids splitting surrogate pairs`() {
        val editor = Editor("a😀b"); val session = editor.session()
        session.moveCursor(-1); session.moveCursor(-1)
        assertEquals(1, Selection.getSelectionEnd(editor.buffer))
        session.deleteForward()
        assertEquals("ab", editor.buffer.toString())
    }
}
