package io.github.minjj.keyboard.touch

import io.github.minjj.keyboard.hangul.Dubeolsik
import kotlin.math.abs
import kotlin.math.roundToInt

data class PracticeWord(val text: String, val keys: String, val page: Int = 0, val actionKey: Int? = null, val sentence: Boolean = false)
data class PracticeTap(val literal: Char, val output: Char, val x: Float, val y: Float, val removed: Int = 1, val profile: Int = -1, val korean: Boolean? = null)

class PracticeWordSelector(words: Sequence<String>, korean: Boolean) {
    val words = words.mapNotNull { word ->
        val keys = if (korean) Dubeolsik.keysFor(word)?.takeIf { Dubeolsik.composeKeys(it) == word }
            else word.takeIf { it.all { c -> c in 'a'..'z' } }
        keys?.takeIf { word.length in 2..10 && it.length <= 24 }?.let { PracticeWord(word, it) }
    }.distinctBy { it.text }.toList()
    private val recent = ArrayDeque<String>()
    fun next(count: (Int) -> Float): PracticeWord? {
        val candidates = words.filter { it.text !in recent }
        val chosen = (candidates.ifEmpty { words }).maxByOrNull { w ->
            w.keys.lowercase().toSet().sumOf { (30f - count(PHYSICAL_LETTERS.indexOf(it))).coerceAtLeast(1f).toDouble() } /
                (2 + w.keys.length) - if (w.keys.any { it.isUpperCase() }) 2 else 0
        } ?: return null
        recent.addLast(chosen.text)
        while (recent.size > 8) recent.removeFirst()
        return chosen
    }
}

/** Conservative whole-word alignment; typed output is never used as its own training label. */
object PracticeAlignment {
    fun samples(word: PracticeWord, taps: List<PracticeTap>, keys: List<LayoutKey>): List<TouchSample>? {
        if (word.keys.length != taps.size) return null
        val substitutions = word.keys.indices.filter { word.keys[it] != taps[it].literal }
        if (substitutions.size > 1) return null
        // A repeated symbol makes a single omission+duplication indistinguishable from substitution.
        if (substitutions.isNotEmpty() && (word.keys.lowercase().toSet().size != word.keys.length ||
                taps.map { it.literal.lowercaseChar() }.toSet().size != taps.size)) return null
        return word.keys.mapIndexed { i, expected ->
            val tap = taps[i]
            if (expected.isUpperCase() != tap.literal.isUpperCase()) return null
            val target = keys.firstOrNull { it.key == KeyIds.fromToken(expected) } ?: return null
            val literal = keys.firstOrNull { it.key == KeyIds.fromToken(tap.literal) } ?: return null
            if (target.half != literal.half) return null
            val dx = (tap.x - (target.left + target.right) / 2) / target.width
            val dy = (tap.y - (target.top + target.bottom) / 2) / target.height
            if (!dx.isFinite() || !dy.isFinite() || abs(dx) > .72f || abs(dy) > .72f) return null
            if (target.key != literal.key && !((target.row == literal.row && abs(target.left - literal.left) <=
                    maxOf(target.width, literal.width) * 1.5f) ||
                    (abs(target.row - literal.row) == 1 && tap.x in target.left..target.right))) return null
            TouchSample(target.key, (dx * 1000).roundToInt(), (dy * 1000).roundToInt(), true)
        }
    }
}
