package io.github.minjj.keyboard.touch

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class SentenceAndDensityTest {
    @Test fun `sentences vary vocabulary transitions numbers and lengths without short repeated cycles`() {
        for (ko in listOf(true, false)) {
            val selector = PracticeSentences(ko, Random(42))
            val sentences = List(360) { selector.next(true) }
            assertTrue(sentences.map { it.text }.toSet().size > 350)
            assertTrue(sentences.all { it.sentence && it.actionKey == null && it.keys.length in 30..384 })
            assertTrue(sentences.all { PracticeSentences.space in it.keys })
            assertTrue(sentences.count { it.text.any(Char::isDigit) } > 200)
            assertEquals(('0'..'9').toSet(), sentences.flatMap { it.text.filter(Char::isDigit).toList() }.toSet())
            val withoutNumbers = List(36) { selector.next(false) }
            assertTrue(withoutNumbers.none { it.text.any(Char::isDigit) })
            assertTrue("Language=$ko lengths=${sentences.map { it.keys.length }.distinct()}", sentences.map { it.keys.length }.distinct().size >= 12)
        }
    }

    private val ids = listOf('a', 'b', 'c', 'x', '1', '2').map { KeyIds.character(it)!! } +
        listOf("SPACE", "SHIFT", "DELETE", "ENTER").map { KeyIds.function(it)!! }
    private val keys = ids.mapIndexed { index, id -> LayoutKey(id, index * 54f, 0f, index * 54f + 50f, 50f, 0, 0) }
    private fun tap(c: Char, output: Char = c, removed: Int = 1): PracticeTap {
        val key = keys.first { it.key == KeyIds.fromToken(c) }
        return PracticeTap(c, output, key.left + 25f, 25f, removed)
    }
    private fun fn(name: String) = KeyIds.token(KeyIds.function(name)!!)

    @Test fun `mixed sentences learn space enter shift and real backspace edits together`() {
        val word = PracticeWord("Ab 12", PracticeSentences.keys("Ab 12", false), sentence = true)
        val events = listOf(tap(fn("SHIFT")), tap('a', 'A'), tap('x'), tap(fn("DELETE")), tap('b'),
            tap(fn("SPACE")), tap('1'), tap('2'), tap(fn("ENTER")))
        val samples = SentenceAlignment.samples(word, events, keys)!!
        assertEquals(8, samples.size)
        assertTrue(samples.all { it.guided })
        assertEquals(setOf("SPACE", "ENTER", "SHIFT", "DELETE").map { KeyIds.function(it)!! }.toSet(),
            samples.filter { KeyIds.isFunction(it.key) }.map { it.key }.toSet())
        assertFalse(samples.any { it.key == KeyIds.character('x') })
    }

    @Test fun `uncertain repeats and unmatched output are not used as their own labels`() {
        val word = PracticeWord("aaab", "aaab", sentence = true)
        val samples = SentenceAlignment.samples(word, listOf(tap('a'), tap('a'), tap('b')), keys)!!
        assertEquals(listOf(KeyIds.character('b')), samples.map { it.key })
        assertNull(SentenceAlignment.samples(PracticeWord("abc abc abc", PracticeSentences.keys("abc abc abc", false), sentence = true),
            List(12) { tap('x') }, keys))
    }

    @Test fun `committed Hangul deletion can remove several physical units before retyping`() {
        val word = PracticeWord("ab", "ab", sentence = true)
        val result = SentenceAlignment.samples(word, listOf(tap('a'), tap('b'), tap(fn("DELETE"), removed = 2), tap('a'), tap('b')), keys)!!
        assertEquals(listOf(KeyIds.character('a'), KeyIds.character('b'), KeyIds.function("DELETE")), result.map { it.key })
        assertEquals("r", PracticeSentences.keys("ㄱ", true))
        assertEquals("rk", PracticeSentences.keys("가", true))
    }

    @Test fun `recent point clouds retain only the last hundred across input sources and round trip`() {
        val model = SpatialModel().updated(128, 20000, List(75) { TouchSample(0, it, 0) })
            .updated(128, 20001, List(75) { TouchSample(0, 75 + it, 0, true) })
        val restored = SpatialModel.decode(model.encode())
        val density = restored.density(128, 20001, 0)
        assertEquals(100, density.points.size)
        assertEquals(.05f, density.points.first().dx, .00001f)
        assertEquals(.149f, density.points.last().dx, .00001f)
        assertEquals(50, density.centralPoints().size)
        assertEquals(.0995f, density.mode!!.dx, .0001f)
        assertEquals(0, restored.removed(128, true).density(128, 20001, 0).points.size)
        assertEquals(model.encode(), restored.encode())
        assertEquals(0, restored.density(128, 20031, 0).points.size)
    }

    @Test fun `twenty four full recent point profiles stay within the existing storage budget`() {
        val ids = (0..35).toList() + KeyIds.functions.map { KeyIds.function(it)!! } +
            KeyIds.functions.take(8).map { KeyIds.occurrence(KeyIds.function(it)!!, 1) }
        var model = SpatialModel()
        repeat(24) { profile ->
            model = model.updated(128 + profile, 20000, ids.flatMap { key -> List(160) { i -> TouchSample(key, i - 80, 80 - i, true) } })
        }
        val encoded = model.encode()
        assertTrue("Recent points must fit the repository's 2 MiB cap", encoded.toByteArray(Charsets.UTF_8).size < 2 * 1024 * 1024)
        val restored = SpatialModel.decode(encoded)
        assertEquals(24, restored.layouts.size)
        assertEquals(100, restored.density(151, 20000, ids.last()).points.size)
    }

    @Test fun `density persists unordered spatial counts and extracts exactly half of the mass`() {
        val day = 20000L
        val model = SpatialModel().updated(128, day,
            List(20) { TouchSample(0, 100, -100, true) } + List(10) { TouchSample(0, -100, 100, true) } +
                List(10) { TouchSample(0, 300, 300, true) })
        val density = SpatialModel.decode(model.encode()).density(128, day, 0)
        assertEquals(40f, density.count, 0f)
        assertEquals(20f, density.centralHalf().sumOf { it.count.toDouble() }.toFloat(), 0f)
        assertEquals(DensityCell(.1f, -.1f, 20f), density.centralHalf().single())
        assertEquals(0f, model.density(128, day + 30, 0).count, 0f)
        assertEquals(0f, model.removed(128).density(128, day, 0).count, 0f)
        assertEquals(model.encode(), SpatialModel.decode(model.encode()).encode())
        val legacy = SpatialModel.decode("touch-bias-v3\nP 128 20000 0 -\nB 128 20000 0 16 1600 0 1\n")
        assertEquals(16, legacy.summary(128, day, 0, setOf(0)).guided)
        assertEquals(0f, legacy.density(128, day, 0).count, 0f)
    }
}
