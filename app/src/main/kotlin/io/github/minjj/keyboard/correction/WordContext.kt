package io.github.minjj.keyboard.correction

import io.github.minjj.keyboard.hangul.Dubeolsik
import io.github.minjj.keyboard.touch.AmbiguousTap
import io.github.minjj.keyboard.touch.WordCandidate

internal data class WordSnapshot(
    val keys: String,
    val previous: String?,
    val ambiguous: List<AmbiguousTap>,
    val revision: Long,
    val korean: Boolean,
) {
    fun literal(): String? = if (korean) Dubeolsik.composeKeys(keys) else keys
}

/** Tracks only writes accepted from this IME, starting at a known word boundary. */
internal class WordContext {
    private val keys = StringBuilder()
    private val ambiguous = ArrayList<AmbiguousTap>(4)
    private var previous: String? = null
    private var boundary = false
    private var sealed = false
    private var revision = 0L

    fun clear(atStart: Boolean = false) {
        keys.clear()
        ambiguous.clear()
        previous = null
        boundary = atStart
        sealed = false
        revision++
    }

    fun startAfter(before: CharSequence) {
        if (before.lastOrNull()?.isWhitespace() != true) return
        clear(atStart = true)
        val text = before.trimEnd()
        val word = text.takeLastWhile { !it.isWhitespace() }
        if (word.length in 1..32 && (before.length < 128 || text.length > word.length) &&
            word.all { it in 'a'..'z' || it in 'A'..'Z' || it in '가'..'힣' }
        ) previous = word.toString()
    }

    fun letter(key: Char, alternatives: String) {
        if (!boundary) return
        if (sealed || keys.length == 32) {
            clear()
            return
        }
        if (alternatives.isNotEmpty() && ambiguous.size < 4) ambiguous += AmbiguousTap(keys.length, alternatives)
        keys.append(key)
        revision++
    }

    fun space(korean: Boolean) {
        if (keys.isNotEmpty()) previous = if (korean) Dubeolsik.composeKeys(keys.toString()) else keys.toString()
        keys.clear()
        ambiguous.clear()
        boundary = true
        sealed = false
        revision++
    }

    fun snapshot(korean: Boolean): WordSnapshot? =
        if (boundary && !sealed) WordSnapshot(keys.toString(), previous, ambiguous.toList(), revision, korean) else null

    fun selected(candidate: WordCandidate) {
        keys.clear().append(candidate.keys)
        ambiguous.clear()
        sealed = true
        revision++
    }

    fun restore(snapshot: WordSnapshot) {
        keys.clear().append(snapshot.keys)
        ambiguous.clear()
        ambiguous.addAll(snapshot.ambiguous)
        previous = snapshot.previous
        boundary = true
        sealed = false
        revision++
    }
}
