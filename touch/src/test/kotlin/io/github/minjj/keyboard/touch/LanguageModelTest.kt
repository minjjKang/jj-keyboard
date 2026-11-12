package io.github.minjj.keyboard.touch

import io.github.minjj.keyboard.hangul.Dubeolsik
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageModelTest {
    @Test fun `two-beolsik prefixes survive complex jamo and final migration`() {
        for (word in listOf("가나다", "값이", "괜찮아요", "깨끗해요", "죄송해요", "의견")) {
            assertEquals(word, Dubeolsik.composeKeys(Dubeolsik.keysFor(word)!!))
        }
        val model = LanguageModel(true, sequenceOf("가나다" to 500), emptySequence())
        assertEquals("간", Dubeolsik.composeKeys("rks"))
        assertTrue(model.evidence("rks", null, 'k') > model.evidence("rks", null, 'o'))
        assertEquals("가나다", model.suggestions("rksk", "가나", null, emptyList()).first().word)
    }

    @Test fun `previous word favors a known transition and leaves unknown initials neutral`() {
        val model = LanguageModel(false, sequenceOf("morning" to 600, "night" to 600),
            sequenceOf("good morning"))
        assertEquals(0f, model.evidence("", "unknown", 'm'), 0f)
        assertTrue(model.evidence("", "good", 'm') > model.evidence("", "good", 'n'))
    }

    @Test fun `single boundary substitution and prefix completion preserve English case`() {
        val model = LanguageModel(false, sequenceOf("test" to 600, "testing" to 550), emptySequence())
        assertEquals("Test", model.suggestions("Tesr", "Tesr", null, listOf(AmbiguousTap(3, "t"))).first().word)
        assertEquals("TESTING", model.suggestions("TEST", "TEST", null, emptyList()).first().word)
    }

    @Test fun `context can resolve a narrow ambiguity without training but cannot override a center tap`() {
        val n = SpatialKey(KeyArea(24, 0, 0, 50, 50), (1 shl 24) or (1 shl 25))
        val m = SpatialKey(KeyArea(25, 54, 0, 104, 50), n.rowMask)
        val model = LanguageModel(false, sequenceOf("morning" to 600), sequenceOf("good morning"))
        val spatial = SpatialModel()
        val evidence: (Int) -> Float = { model.evidence("", "good", PHYSICAL_LETTERS[it]) }
        assertEquals(m.area.key, spatial.resolve(0, 1, n, listOf(m), 51.5f, 25f, evidence))
        assertEquals(n.area.key, spatial.resolve(0, 1, n, listOf(m), 25f, 25f, evidence))
        assertEquals(n.area.key, spatial.resolve(0, 1, n, listOf(m), 51.5f, 25f))
    }
}
