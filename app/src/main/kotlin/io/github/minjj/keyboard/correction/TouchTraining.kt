package io.github.minjj.keyboard.correction

import io.github.minjj.keyboard.touch.TouchSample

data class TouchObservation(val profile: Int, val generation: Long, val sample: TouchSample?)

/** Keep the previous word provisional until the next accepted literal letter tap. */
internal class TouchTraining(private val record: (Int, Long, List<TouchSample>) -> Unit) {
    private var profile = -1
    private var generation = -1L
    private var current = mutableListOf<TouchSample>()
    private var completed: List<TouchSample> = emptyList()

    fun letter(touch: TouchObservation?) {
        if (touch == null) {
            clear()
            return
        }
        if (touch.profile != profile || touch.generation != generation) {
            clear()
            profile = touch.profile
            generation = touch.generation
        }
        if (completed.isNotEmpty()) {
            record(profile, generation, completed)
            completed = emptyList()
        }
        if (current.size < 32) touch.sample?.let(current::add)
    }

    fun space() {
        if (current.isNotEmpty()) {
            completed = current
            current = mutableListOf()
        }
    }

    fun clear() {
        current.clear()
        completed = emptyList()
    }
}
