package io.github.minjj.keyboard.settings

import io.github.minjj.keyboard.ime.*
import io.github.minjj.keyboard.ui.KeyboardLayouts
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CharacterCustomizationTest {
    private fun controller() = KeyboardController({ null }, {}, {}, {}, {})

    @Test fun `secondary assignments separate languages allow disabling and retain defaults`() {
        val key = KeySpec("ㄲ", KeyCommand.CHARACTER, "r", letter = true)
        assertEquals("ㄲ", KeyboardLayouts.secondary(key, true))
        assertEquals("=", KeyboardLayouts.secondary(key, false))
        val settings = KeyboardSettings(secondaryOverrides = mapOf("ko:r" to "★", "en:r" to ""))
        assertEquals("★", KeyboardLayouts.secondary(key, true, settings))
        assertNull(KeyboardLayouts.secondary(key, false, settings))
        assertNull(KeyboardLayouts.secondary(KeySpec("", KeyCommand.DELETE), true, settings))
    }

    @Test fun `symbol assignments and secondary assignments follow slots across split layouts`() {
        val controller = controller()
        controller.press(KeySpec("", KeyCommand.SYMBOLS))
        for (extended in listOf(false, true)) {
            val original = KeyboardLayouts.rows(controller, EditorProfile(), "↵", extended = extended)
                .flatten().first { it.value == "+" }
            val slot = requireNotNull(original.symbolSlot)
            val settings = KeyboardSettings(symbolOverrides = mapOf(slot to "🙂"),
                secondaryOverrides = mapOf("symbol:$slot" to "♥"))
            for (split in listOf(false, true)) {
                val rows = KeyboardLayouts.rows(controller, EditorProfile(), "↵", split, extended, settings = settings)
                val customized = rows.flatten().single { it.symbolSlot == slot }
                assertEquals("🙂", customized.label)
                assertEquals("🙂", customized.value)
                assertEquals("♥", KeyboardLayouts.secondary(customized, false, settings))
                assertEquals(if (split) 2 else 1, rows.last().count { it.command == KeyCommand.SPACE })
            }
            controller.press(KeySpec("", KeyCommand.MORE_SYMBOLS))
            assertFalse(KeyboardLayouts.rows(controller, EditorProfile(), "↵", extended = extended, settings = settings)
                .flatten().any { it.value == "🙂" })
            controller.press(KeySpec("", KeyCommand.MORE_SYMBOLS))
        }
    }

    @Test fun `assignments persist reset independently and restore in settings snapshots`() = runBlocking {
        val repository = SettingsRepository(RuntimeEnvironment.getApplication())
        val initial = repository.settings.first()
        try {
            repository.restoreSettings(KeyboardSettings())
            repository.setCharacter(true, "ko:r", "")
            repository.setCharacter(true, "en:r", "★")
            repository.setCharacter(false, "normal:1:1:0", "🙂")
            val saved = SettingsRepository(RuntimeEnvironment.getApplication()).settings.first()
            assertEquals(mapOf("ko:r" to "", "en:r" to "★"), saved.secondaryOverrides)
            assertEquals(mapOf("normal:1:1:0" to "🙂"), saved.symbolOverrides)
            repository.setCharacter(true, "ko:r", null)
            assertFalse(repository.settings.first().secondaryOverrides.containsKey("ko:r"))
            assertEquals("★", repository.settings.first().secondaryOverrides["en:r"])
            repository.restoreSettings(saved)
            assertEquals(saved, repository.settings.first())
        } finally { repository.restoreSettings(initial) }
    }

    @Test fun `invalid stored assignments fall back without breaking other settings`() {
        assertTrue(CharacterCustomization.decode("invalid", true).isEmpty())
        val raw = """{"ko:r":"★","en:r":"\n","normal:1:1:0":"x","en:q":12}"""
        assertEquals(mapOf("ko:r" to "★"), CharacterCustomization.decode(raw, true))
        assertFalse(CharacterCustomization.validValue("", false))
        assertTrue(CharacterCustomization.validValue("", true))
        assertFalse(CharacterCustomization.validValue("123456789", true))
        assertTrue(CharacterCustomization.validValue("👨‍👩‍👧‍👦", false))
    }
}
