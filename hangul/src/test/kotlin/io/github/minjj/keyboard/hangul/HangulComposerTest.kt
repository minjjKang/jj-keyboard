package io.github.minjj.keyboard.hangul

import org.junit.Assert.*
import org.junit.Test
import java.text.Normalizer

class HangulComposerTest {
    private class Typing {
        val composer = HangulComposer()
        var committed = ""
        val text get() = committed + composer.composing
        fun keys(keys: String): String {
            keys.forEach { committed += composer.input(Dubeolsik.map(it)!!).committed }
            return text
        }
        fun delete(): String { composer.backspace(); return text }
    }

    @Test fun `syllables carry their final into next initial`() {
        val t = Typing()
        assertEquals("ㄱ", t.keys("r")); assertEquals("가", t.keys("k"))
        assertEquals("간", t.keys("s")); assertEquals("가나", t.keys("k"))
        assertEquals("가ㄴ", t.delete()); assertEquals("가", t.delete())
    }

    @Test fun `compound vowels undo the actual input history`() {
        val t = Typing()
        assertEquals("과", t.keys("rhk"))
        assertEquals("고", t.delete()); assertEquals("ㄱ", t.delete()); assertEquals("", t.delete())
        val c = HangulComposer()
        c.input('ㄱ'); c.input('ㅘ')
        assertEquals("ㄱ", c.backspace().composing)
    }

    @Test fun `compound finals split and undo`() {
        val t = Typing()
        assertEquals("값", t.keys("rkqt"))
        assertEquals("갑", t.delete()); assertEquals("값", t.keys("t"))
        assertEquals("갑시", t.keys("l")); assertEquals("갑ㅅ", t.delete())
    }

    @Test fun `shift consonants and standalone vowels`() {
        assertEquals("까", Typing().keys("Rk"))
        assertEquals("따", Typing().keys("Ek"))
        assertEquals("있어", Typing().keys("dlTdj"))
        assertEquals("ㅘㄱ", Typing().keys("hkr"))
        assertEquals("ㄱㄱ", Typing().keys("rr"))
        assertEquals("가따", Typing().keys("rkEk"))
    }

    @Test fun `real words mix all transition kinds`() {
        assertEquals("안녕하세요", Typing().keys("dkssudgktpdy"))
        assertEquals("한글", Typing().keys("gksrmf"))
        assertEquals("닭이", Typing().keys("ekfrdl"))
        assertEquals("읽어요", Typing().keys("dlfrdjdy"))
    }

    @Test fun `finish clears history and returns text once`() {
        val c = HangulComposer()
        "rhk".forEach { c.input(Dubeolsik.map(it)!!) }
        assertEquals("과", c.finish()); assertEquals("", c.finish())
        assertEquals("", c.backspace().composing)
    }

    @Test fun `all 11172 syllables match Unicode NFC and decompose`() {
        for (l in 0..18) for (v in 0..20) for (t in 0..27) {
            val initial = HangulSyllable.INITIALS[l]
            val vowel = HangulSyllable.VOWELS[v]
            val final = if (t == 0) null else HangulSyllable.FINALS[t]
            val actual = HangulSyllable.compose(initial, vowel, final)
            val nfd = "${(0x1100 + l).toChar()}${(0x1161 + v).toChar()}" +
                if (t == 0) "" else (0x11A7 + t).toChar().toString()
            assertEquals(Normalizer.normalize(nfd, Normalizer.Form.NFC), actual.toString())
            assertEquals(HangulSyllable.Parts(initial, vowel, final), HangulSyllable.decompose(actual))
        }
        assertNull(HangulSyllable.decompose('a'))
    }

    @Test fun `every compound final migrates only its second consonant`() {
        val compounds = listOf("ㄱㅅㄳ", "ㄴㅈㄵ", "ㄴㅎㄶ", "ㄹㄱㄺ", "ㄹㅁㄻ", "ㄹㅂㄼ",
            "ㄹㅅㄽ", "ㄹㅌㄾ", "ㄹㅍㄿ", "ㄹㅎㅀ", "ㅂㅅㅄ")
        compounds.forEach { pair ->
            val c = HangulComposer()
            listOf('ㄱ', 'ㅏ', pair[0], pair[1]).forEach { c.input(it) }
            assertEquals(HangulSyllable.compose('ㄱ', 'ㅏ', pair[2]).toString(), c.composing)
            val result = c.input('ㅏ')
            assertEquals(HangulSyllable.compose('ㄱ', 'ㅏ', pair[0]).toString(), result.committed)
            assertEquals(HangulSyllable.compose(pair[1], 'ㅏ').toString(), result.composing)
        }
    }
}
