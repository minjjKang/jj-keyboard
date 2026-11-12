package io.github.minjj.keyboard.touch

import org.junit.Assert.*
import org.junit.Test

class PracticeEditTraceTest {
    private val ids = PHYSICAL_LETTERS.indices.toList() + listOf(KeyIds.function("DELETE")!!)
    private val keys = ids.mapIndexed { i, id -> LayoutKey(id, i * 54f, 0f, i * 54f + 50, 50f, 0, 0) }
    private fun tap(c: Char, ko: Boolean = false, profile: Int = 128): PracticeTap {
        val key = keys.first { it.key == KeyIds.fromToken(c) }
        return PracticeTap(c, c, key.left + 25, 25f, profile = profile, korean = ko)
    }
    private fun word(text: String) = PracticeWord(text, PracticeSentences.keys(text, true), sentence = true)

    @Test fun `cursor insertion keeps earlier and later touch owners and deletion removes the edited point`() {
        val trace = PracticeEditTrace()
        trace.apply("", "a", 0, "CHARACTER", 0, 0)
        trace.apply("a", "ac", 1, "CHARACTER", 1, 1)
        trace.apply("ac", "ac", -1, "CURSOR")
        trace.apply("ac", "abc", 2, "CHARACTER", 1, 1)
        assertEquals(listOf(0, 2, 1), trace.owners)
        val events = listOf(tap('a'), tap('c'), tap('b'))
        assertEquals(listOf(0, 2, 1), SentenceAlignment.editedSamples(word("abc"), events, keys, trace, "abc")!!.map { it.event })
        trace.apply("abc", "ac", 3, "DELETE", 1, 2)
        val result = SentenceAlignment.editedSamples(word("ac"), events + tap(KeyIds.token(KeyIds.function("DELETE")!!)), keys, trace, "ac")!!
        assertEquals(listOf(0, 1, 3), result.map { it.event })
        assertFalse(result.any { it.event == 2 })
    }

    @Test fun `selection bounds disambiguate repeated characters when replacing in the middle`() {
        val trace = PracticeEditTrace()
        repeat(4) { i -> trace.apply("a".repeat(i), "a".repeat(i + 1), i, "CHARACTER", i, i) }
        trace.apply("aaaa", "aaa", 4, "CHARACTER", 1, 3)
        assertEquals(listOf(0, 4, 3), trace.owners)
        trace.apply("aaa", "aa", 5, "DELETE", 0, 1)
        assertEquals(listOf(4, 3), trace.owners)
    }

    @Test fun `Hangul commit and new composing writes from one touch preserve the reattached final consonant`() {
        val trace = PracticeEditTrace()
        trace.apply("", "ㄱ", 0, "CHARACTER", 0, 0)
        trace.apply("ㄱ", "기", 1, "CHARACTER")
        trace.apply("기", "길", 2, "CHARACTER")
        trace.apply("길", "기", 3, "CHARACTER")
        trace.apply("기", "기라", 3, "CHARACTER", 1, 1)
        assertEquals(listOf(0, 1, 2, 3), trace.owners)
        val events = "rlfk".map { tap(it, true) }
        assertEquals(4, SentenceAlignment.editedSamples(word("기라"), events, keys, trace, "기라")!!.size)
    }

    @Test fun `Hangul and English with the same physical keys cannot train one another's text`() {
        val trace = PracticeEditTrace()
        trace.apply("", "ㄱ", 0, "CHARACTER", 0, 0)
        trace.apply("ㄱ", "가", 1, "CHARACTER")
        trace.apply("가", "가r", 2, "CHARACTER", 1, 1)
        trace.apply("가r", "가rk", 3, "CHARACTER", 2, 2)
        val events = listOf(tap('r', true), tap('k', true), tap('r', false, 129), tap('k', false, 129))
        val onlyHangul = SentenceAlignment.editedSamples(word("가가"), events, keys, trace, "가rk")!!
        assertEquals(listOf(0, 1), onlyHangul.map { it.event })
        val mixed = SentenceAlignment.editedSamples(word("가rk"), events, keys, trace, "가rk")!!
        assertEquals(4, mixed.size)
        assertNull(SentenceAlignment.editedSamples(word("가rk"), events, keys, trace, "가나"))
        trace.clear()
        assertFalse(trace.observed)
        assertTrue(trace.owners.isEmpty())
        assertTrue(trace.deletions.isEmpty())
    }
}
