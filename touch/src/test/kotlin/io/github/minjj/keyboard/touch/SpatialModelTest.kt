package io.github.minjj.keyboard.touch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SpatialModelTest {
    private val q = KeyArea(0, 0, 0, 50, 50)
    private val w = KeyArea(1, 54, 0, 104, 50)
    private val profile = 7
    private val day = 20_000L
    private val row = 0b111
    private val rowSamples = List(100) { TouchSample(2, 180, 0) }

    private fun horizontal(model: SpatialModel, x: Float, layout: Int = profile, today: Long = day, mask: Int = row) =
        model.resolve(layout, today, SpatialKey(w, mask), listOf(SpatialKey(q, mask)), x, 25f)

    @Test fun `row bias corrects a boundary tap after enough samples`() {
        val sparse = SpatialModel().updated(profile, day, rowSamples.take(20))
        val trained = sparse.updated(profile, day, rowSamples)
        assertEquals(w.key, horizontal(sparse, 54f))
        assertEquals(q.key, horizontal(trained, 54f))
        assertEquals(w.key, horizontal(trained, 79f))
        assertEquals(w.key, horizontal(trained, 99f))
        assertNull(w.centerSample(54f, 25f))
        assertEquals(TouchSample(w.key, 180, 0), w.centerSample(88f, 25f))
    }

    @Test fun `stored bias is scoped to profile and row and expires`() {
        val trained = SpatialModel().updated(profile, day, rowSamples)
        val restored = SpatialModel.decode(trained.encode())
        assertEquals(q.key, horizontal(restored, 54f))
        assertEquals(w.key, horizontal(restored, 54f, layout = profile + 1))
        assertEquals(w.key, horizontal(restored, 54f, mask = 0b011))
        assertEquals(w.key, horizontal(restored, 54f, today = day + 30))
    }

    @Test fun `vertical correction uses both rows and protects letter centers`() {
        val upper = SpatialKey(q, 0b111)
        val lower = SpatialKey(KeyArea(10, 10, 54, 60, 104), 0b11 shl 10)
        fun trained(dy: Int) = SpatialModel().updated(profile, day,
            List(200) { TouchSample(2, 0, dy) } + List(200) { TouchSample(11, 0, dy) })
        val downward = trained(190)
        val upward = trained(-190)
        assertEquals(q.key, downward.resolve(profile, day, lower, listOf(upper), 28f, 55f))
        assertEquals(10, upward.resolve(profile, day, upper, listOf(lower), 28f, 49f))
        assertEquals(10, downward.resolve(profile, day, lower, listOf(upper), 28f, 79f))
        val sparseUpper = SpatialModel().updated(profile, day, List(200) { TouchSample(11, 0, 190) })
        assertEquals(10, sparseUpper.resolve(profile, day, lower, listOf(upper), 28f, 55f))
    }

    @Test fun `storage keeps at most eight aggregate buckets and rejects invalid offsets`() {
        val trained = SpatialModel().updated(profile, day, List(1000) { TouchSample(2, 180, 0) })
        assertEquals(11, trained.encode().lineSequence().count { it.isNotBlank() })
        org.junit.Assert.assertTrue(trained.summary(profile, day, 2, row).normal in 113..128)
        assertEquals(trained.encode(), SpatialModel.decode(trained.encode()).encode())
        assertThrows(IllegalArgumentException::class.java) {
            SpatialModel.decode("touch-bias-v1\n7 20000 2 1 201 0\n")
        }
    }
}
