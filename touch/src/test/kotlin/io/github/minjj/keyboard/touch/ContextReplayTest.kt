package io.github.minjj.keyboard.touch

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class ContextReplayTest {
    @Test fun `bundled models separate recovered errors from newly introduced errors`() {
        val assets = File(checkNotNull(System.getProperty("minjj.language.assets")))
        val spatial = SpatialModel()
        var recovered = 0
        var introduced = 0
        var total = 0
        for ((language, previous, letters) in listOf(Triple("en", "good", "nm"), Triple("ko", "오늘", "as"))) {
            val model = File(assets, "$language.tsv").bufferedReader().useLines { lines ->
                val phrases = File(assets, "$language-phrases.txt").readLines()
                LanguageModel(language == "ko", lines.map {
                    val (word, weight) = it.split('\t', limit = 2)
                    word to weight.toInt()
                }, phrases.asSequence())
            }
            val (leftId, rightId) = letters.map { PHYSICAL_LETTERS.indexOf(it) }
            val mask = (1 shl leftId) or (1 shl rightId)
            val left = SpatialKey(KeyArea(leftId, 0, 0, 50, 50), mask)
            val right = SpatialKey(KeyArea(rightId, 54, 0, 104, 50), mask)
            val cases = listOf(
                51.5f to rightId, // Same observed touch can come from either intention.
                51.5f to leftId,
                25f to leftId,
                40f to leftId,
            )
            for ((x, expected) in cases) {
                val baseline = spatial.resolve(0, 1, left, listOf(right), x, 25f)
                val corrected = spatial.resolve(0, 1, left, listOf(right), x, 25f) { id ->
                    model.evidence("", previous, PHYSICAL_LETTERS[id])
                }
                if (baseline != expected && corrected == expected) recovered++
                if (baseline == expected && corrected != expected) introduced++
                total++
            }
        }
        assertEquals(8, total)
        assertEquals(2, recovered)
        assertEquals(2, introduced)
    }
}
