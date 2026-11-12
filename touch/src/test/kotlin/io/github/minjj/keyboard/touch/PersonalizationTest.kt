package io.github.minjj.keyboard.touch

import org.junit.Assert.*
import org.junit.Test

class PersonalizationTest {
    private val day = 20_000L
    private fun layout(scale: Float = 1f, family: Int = 32, gap: Float = 4f) = LayoutDescriptor(family,
        600f * scale, 220f * scale, 0f, 0f, gap, gap, if (family and 8 != 0) 56f else 0f,
        PHYSICAL_LETTERS.indices.map { k ->
            val row = if (k < 10) 0 else if (k < 19) 1 else 2
            val column = if (k < 10) k else if (k < 19) k - 10 else k - 19
            LayoutKey(k, column * 54f * scale, row * 54f * scale, (column * 54 + 50) * scale,
                (row * 54 + 50) * scale, row, 0)
        })
    private fun trained(id: Int = 128, descriptor: LayoutDescriptor = layout()): SpatialModel =
        SpatialModel().withLayout(id, descriptor, day).updated(id, day, List(100) { TouchSample(0, 180, -100, true) })

    @Test fun `same profile uses immutable anchor and separates structural layouts`() {
        val original = layout()
        val model = trained()
        assertEquals(128, model.select(original))
        assertEquals(128, model.select(layout(1.005f)))
        assertNull(model.select(layout(1.02f))) // Far edge keys have moved beyond the center tolerance.
        assertNull(model.select(layout(family = 0)))
        assertNull(model.select(layout(family = 40)))
        assertNull(model.select(layout(family = 36)))
        assertNull(model.select(layout(gap = 6f)))
        assertEquals(original, model.layouts.first().layout)
    }

    @Test fun `measured descriptor including function blocks round trips and rejects nonfinite values`() {
        val d = layout().copy(surfaces = layout().keys + LayoutKey(-1, 0f, 180f, 50f, 220f, 3, 0))
        assertEquals(d, LayoutDescriptor.decode(d.encode()))
        assertThrows(IllegalArgumentException::class.java) { d.copy(width = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { d.copy(keys = d.keys.drop(1)) }
    }

    @Test fun `preview layouts are not written and cannot evict saved profiles`() {
        var model = trained()
        repeat(50) { model = model.withLayout(129 + it, layout(1 + it / 100f), day) }
        assertEquals(1, SpatialModel.decode(model.encode()).layouts.size)
        assertNotNull(model.layouts.firstOrNull { it.id == 128 })
    }

    @Test fun `guided samples accelerate effective evidence and source counts survive storage`() {
        val model = SpatialModel().withLayout(128, layout(), day).updated(128, day,
            List(10) { TouchSample(0, 100, 0) } + List(30) { TouchSample(0, 400, 0, true) })
        val s = SpatialModel.decode(model.encode()).summary(128, day, 0, 0x3ff)
        assertEquals(10, s.normal); assertEquals(30, s.guided)
        assertEquals(33.5f, s.effective, .001f); assertEquals(3, s.stage)
        assertTrue(s.offsetX in 0f..0.15f)
        assertEquals(0f, model.summary(128, day + 30, 0, 0x3ff).effective, 0f)
        assertThrows(IllegalArgumentException::class.java) { model.updated(128, day, listOf(TouchSample(0, 201, 0))) }
        assertThrows(IllegalArgumentException::class.java) { model.updated(128, day, listOf(TouchSample(0, 721, 0, true))) }
    }

    @Test fun `weak reference is not copied and disappears after own evidence or reset`() {
        val base = trained()
        val d = layout(1.02f)
        assertNotNull(layout().distance(d, true))
        val model = base.withLayout(129, d, day)
        val inherited = model.summary(129, day, 0, 0x3ff)
        assertEquals(128, inherited.reference)
        assertEquals(0, inherited.guided)
        assertTrue(inherited.offsetX > 0f && inherited.offsetX <= .0375f)
        assertNull(model.updated(129, day, List(30) { TouchSample(0, -300, 0, true) }).summary(129, day, 0, 0x3ff).reference)
        assertNull(model.removed(129, true).summary(129, day, 0, 0x3ff).reference)
        assertNull(model.removed(128).summary(129, day, 0, 0x3ff).reference)
        assertNull(model.summary(129, day + 30, 0, 0x3ff).reference)
    }

    @Test fun `rare key history survives frequent other keys and profile count is bounded`() {
        var model = trained().updated(128, day, List(30) { TouchSample(25, 0, 0, true) })
        model = model.updated(128, day, List(2000) { TouchSample(0, 0, 0, true) })
        assertEquals(30, model.summary(128, day, 25, 1 shl 25).guided)
        assertTrue(model.summary(128, day, 0, 1).guided in 113..128)
        repeat(30) { i -> model = model.withLayout(129 + i, layout(), day)
            .updated(129 + i, day, listOf(TouchSample(0, 0, 0, true))) }
        assertEquals(24, model.layouts.size)
        assertEquals(model.encode(), SpatialModel.decode(model.encode()).encode())
    }

    @Test fun `legacy data keeps provenance and rejects malformed storage`() {
        val legacy = SpatialModel.decode("touch-bias-v1\n32 20000 0 100 18000 0\n")
        assertNull(legacy.layouts.first().layout)
        assertEquals(100, legacy.summary(32, day, 0, 1).normal)
        val migrated = SpatialModel.decode(legacy.encode())
        assertEquals(legacy.encode(), migrated.encode())
        listOf("touch-bias-v2\nP 128 20000 0 -\nB 128 20000 0 16 999999 0 1\n",
            "touch-bias-v1\n32 20000 0 1 -9223372036854775808 0\n",
            "touch-bias-v2\nP 128 20000 0 -\nB 128 20000 0 1 -9223372036854775808 0 1\n",
            "touch-bias-v2\nP 128 20000 0 -\nP 128 20000 0 -\n").forEach {
            assertThrows(IllegalArgumentException::class.java) { SpatialModel.decode(it) }
        }
    }

    @Test fun `practice accepts exact and single adjacent substitution but excludes uncertain sequences`() {
        val keys = layout().keys
        fun tap(k: Char, dx: Float = 0f): PracticeTap {
            val a = keys.first { PHYSICAL_LETTERS[it.key] == k }
            return PracticeTap(k, k, (a.left + a.right) / 2 + dx, (a.top + a.bottom) / 2)
        }
        val word = PracticeWord("qw", "qw")
        assertEquals(2, PracticeAlignment.samples(word, listOf(tap('q'), tap('w')), keys)!!.size)
        val boundary = tap('w').copy(x = 55f)
        val changed = PracticeAlignment.samples(word, listOf(boundary, tap('w')), keys)
        assertNull(changed) // Repeated observed w makes an omission+duplication ambiguous.
        val single = PracticeAlignment.samples(PracticeWord("q", "q"), listOf(boundary), keys)
        assertEquals(600, single!!.single().dx)
        assertNull(PracticeAlignment.samples(word, listOf(tap('q')), keys))
        assertNull(PracticeAlignment.samples(word, listOf(tap('w'), tap('q')), keys))
        assertNull(PracticeAlignment.samples(PracticeWord("q", "Q"), listOf(tap('q')), keys))
        assertNull(PracticeAlignment.samples(PracticeWord("q", "q"), listOf(tap('q', 100f)), keys))
    }

    @Test fun `Korean paths include compounds finals and shift and selector varies words`() {
        val selector = PracticeWordSelector(sequenceOf("괜찮다", "값", "꽃밭", "apple", "abc!"), true)
        assertEquals(setOf("괜찮다", "꽃밭"), selector.words.map { it.text }.toSet())
        assertTrue(selector.words.any { it.keys.any(Char::isUpperCase) })
        val english = PracticeWordSelector(sequenceOf("qw", "er", "ty", "!"), false)
        val first = english.next { if (it == 0) 0f else 30f }
        assertEquals("qw", first!!.text)
        assertNotEquals(first.text, english.next { 0f }!!.text)
    }

    @Test fun `bundled practice words can exercise every physical letter in both languages`() {
        val root = java.io.File(System.getProperty("minjj.language.assets"))
        for (ko in listOf(true, false)) {
            val selector = java.io.File(root, if (ko) "ko.tsv" else "en.tsv").bufferedReader().use { reader ->
                PracticeWordSelector(reader.lineSequence().map { it.substringBefore('\t') }, ko)
            }
            assertEquals(PHYSICAL_LETTERS.toSet(), selector.words.flatMap { it.keys.lowercase().toList() }.toSet())
        }
    }
}
