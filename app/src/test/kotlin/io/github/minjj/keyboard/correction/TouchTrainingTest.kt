package io.github.minjj.keyboard.correction

import io.github.minjj.keyboard.touch.TouchSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TouchTrainingTest {
    private val sample = TouchSample(1, 140, -40)
    private val observation = TouchObservation(7, 0, sample)
    private val batches = mutableListOf<Triple<Int, Long, List<TouchSample>>>()
    private val training = TouchTraining { profile, generation, samples ->
        batches += Triple(profile, generation, samples.toList())
    }

    @Test fun `only a later literal tap releases center samples from the completed word`() {
        training.letter(observation)
        training.letter(observation.copy(sample = null))
        training.space()
        assertTrue(batches.isEmpty())
        training.letter(observation)
        assertEquals(listOf(Triple(7, 0L, listOf(sample))), batches)
    }

    @Test fun `an edit discards pending samples even just after a space`() {
        training.letter(observation)
        training.space()
        training.clear()
        training.letter(observation)
        assertTrue(batches.isEmpty())
    }

    @Test fun `reset and layout changes discard the preceding batch`() {
        training.letter(observation)
        training.space()
        training.letter(observation.copy(generation = 1))
        training.space()
        training.letter(observation.copy(profile = 9, generation = 1))
        assertTrue(batches.isEmpty())
    }
}
