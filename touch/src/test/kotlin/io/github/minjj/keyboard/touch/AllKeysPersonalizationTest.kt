package io.github.minjj.keyboard.touch

import org.junit.Assert.*
import org.junit.Test

class AllKeysPersonalizationTest {
    private val day = 20_000L
    private val one = checkNotNull(KeyIds.character('1'))
    private val two = checkNotNull(KeyIds.character('2'))
    private val left = checkNotNull(KeyIds.function("CURSOR_LEFT"))
    private val right = checkNotNull(KeyIds.function("CURSOR_RIGHT"))
    private fun pair(a: Int, b: Int) = listOf(SpatialKey(KeyArea(a, 0, 0, 50, 50), setOf(a, b)),
        SpatialKey(KeyArea(b, 54, 0, 104, 50), setOf(a, b)))
    private fun resolve(model: SpatialModel, keys: List<SpatialKey>, x: Float = 55f) =
        model.resolve(128, day, keys[1], listOf(keys[0]), x, 25f)

    @Test fun `stable identities round trip without collisions including duplicated physical keys`() {
        val bases = (PHYSICAL_LETTERS + "0123456789" + KeyIds.SYMBOLS).map { KeyIds.character(it)!! } +
            KeyIds.functions.map { KeyIds.function(it)!! }
        assertEquals(bases.size, bases.toSet().size)
        val ids = (0..3).flatMap { occurrence -> bases.map { KeyIds.occurrence(it, occurrence) } }
        assertEquals(ids.size, ids.toSet().size)
        ids.forEach { assertTrue(KeyIds.valid(it)); assertEquals(it, KeyIds.fromToken(KeyIds.token(it))) }
        assertEquals(PHYSICAL_LETTERS.indices.toList(), PHYSICAL_LETTERS.map { KeyIds.character(it) })
    }

    @Test fun `digits and symbols correct boundaries without colliding with letter bits`() {
        for (ids in listOf(one to two, KeyIds.character('+')!! to KeyIds.character('×')!!)) {
            val keys = pair(ids.first, ids.second)
            val letterOnly = SpatialModel().updated(128, day, List(100) { TouchSample(0, 400, 0, true) })
            assertEquals(ids.second, resolve(letterOnly, keys))
            val trained = letterOnly.updated(128, day, keys.flatMap { k -> List(100) { TouchSample(k.area.key, 400, 0, true) } })
            assertEquals(ids.first, resolve(trained, keys))
            assertEquals(ids.second, resolve(trained, keys, 79f))
            assertEquals(ids.first, resolve(SpatialModel.decode(trained.encode()), keys))
        }
    }

    @Test fun `function correction requires own evidence on both sides and protects centers`() {
        val keys = pair(left, right)
        val oneSided = SpatialModel().updated(128, day, List(100) { TouchSample(left, 400, 0, true) })
        assertEquals(right, resolve(oneSided, keys))
        val trained = oneSided.updated(128, day, List(30) { TouchSample(right, 400, 0, true) })
        assertEquals(left, resolve(trained, keys))
        assertEquals(right, resolve(trained, keys, 79f))
        assertEquals(right, resolve(trained, keys, 100f))
    }

    @Test fun `guided alignment supports symbols and distinguishes left and right space`() {
        val space = KeyIds.function("SPACE")!!
        val duplicate = KeyIds.occurrence(space, 1)
        val ids = listOf(one, KeyIds.character('+')!!, two, space, duplicate)
        val layout = ids.mapIndexed { index, id -> LayoutKey(id, index * 54f, 0f, index * 54f + 50f, 50f, 0, 0) }
        val word = PracticeWord("fixture", ids.map(KeyIds::token).joinToString(""))
        val taps = layout.map { PracticeTap(KeyIds.token(it.key), KeyIds.token(it.key), it.left + 25f, 25f) }
        assertEquals(ids, PracticeAlignment.samples(word, taps, layout)!!.map { it.key })
        assertNull(PracticeAlignment.samples(word, taps.dropLast(1), layout))
        val model = SpatialModel().updated(128, day, List(32) { TouchSample(space, 100, 0, true) })
        assertEquals(0, model.summary(128, day, duplicate, setOf(duplicate)).guided)
    }

    @Test fun `page and editor layout profiles remain isolated and legacy aggregates survive migration`() {
        val descriptor = LayoutDescriptor(0, 104f, 50f, 0f, 0f, 4f, 4f, 0f,
            listOf(LayoutKey(one, 0f, 0f, 50f, 50f, 0, 0), LayoutKey(two, 54f, 0f, 104f, 50f, 0, 0)))
        val model = SpatialModel().withLayout(128, descriptor, day)
        assertEquals(descriptor, LayoutDescriptor.decode(descriptor.encode()))
        assertNull(model.select(descriptor.copy(page = 1)))
        assertNull(model.select(descriptor.copy(inputKind = 1)))
        val old = SpatialModel.decode("touch-bias-v2\nP 128 20000 0 -\nB 128 20000 0 16 1600 0 1\n")
        val migrated = SpatialModel.decode(old.updated(128, day, listOf(TouchSample(right, 100, 0))).encode())
        assertEquals(16, migrated.summary(128, day, 0, setOf(0)).guided)
        assertEquals(1, migrated.summary(128, day, right, setOf(right)).normal)
        assertThrows(IllegalArgumentException::class.java) {
            SpatialModel.decode("touch-bias-v2\nP 128 20000 0 -\nB 128 20000 $right 1 0 0 0\n")
        }
    }
}
