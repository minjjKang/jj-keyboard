package io.github.minjj.keyboard.ime

import org.junit.Assert.*
import org.junit.Test

class KeyboardControllerTest {
    @Test fun `manual Shift remains set when selection refresh and disabled automatic caps run`() {
        val controller = KeyboardController({ null }, {}, {}, {}, {})
        controller.subtype(false)
        controller.tapShift(1000)
        controller.refreshAutoCaps()
        assertEquals(ShiftState.ONCE, controller.shift)
        controller.configureInputOptions(autoCaps = false, doubleSpace = false)
        assertEquals(ShiftState.ONCE, controller.shift)
        controller.press(KeySpec("a", KeyCommand.CHARACTER, letter = true))
        assertEquals(ShiftState.OFF, controller.shift)
    }

    @Test fun `practice language and cursor navigation remain valid without changing global subtype`() {
        var globalChanges = 0
        val controller = KeyboardController({ null }, { globalChanges++ }, {}, {}, {})
        val practice = io.github.minjj.keyboard.correction.PersonalizationSessionBridge.Session(42, true, true) {}
        practice.word = io.github.minjj.keyboard.touch.PracticeWord("가나", "rksk", sentence = true)
        controller.practiceSession = practice
        controller.press(KeySpec("", KeyCommand.LANGUAGE), InputSource.TOUCH_TAP)
        assertFalse(practice.invalid); assertFalse(controller.korean); assertFalse(practice.inputKorean)
        controller.beginCursorGesture(); controller.moveCursor(-2)
        controller.press(KeySpec("", KeyCommand.CURSOR_RIGHT), InputSource.TOUCH_TAP)
        assertFalse(practice.invalid); assertEquals(0, globalChanges)
        controller.press(KeySpec("", KeyCommand.LANGUAGE), InputSource.TOUCH_GESTURE)
        assertTrue(controller.korean); assertFalse(practice.invalid)
        controller.practiceSession = null
        controller.press(KeySpec("", KeyCommand.LANGUAGE), InputSource.TOUCH_TAP)
        assertEquals(1, globalChanges)
    }

}
