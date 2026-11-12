package io.github.minjj.keyboard.touch

import kotlin.math.abs
import kotlin.math.roundToInt

/** Align only unambiguous, editor-confirmed matches against the external prompt. */
object SentenceAlignment {
    private val delete = checkNotNull(KeyIds.function("DELETE"))
    private val shift = checkNotNull(KeyIds.function("SHIFT"))
    private val enter = checkNotNull(KeyIds.function("ENTER"))
    private val space = checkNotNull(KeyIds.function("SPACE"))
    private fun normalized(c: Char): Char = KeyIds.fromToken(c)?.takeIf { KeyIds.isFunction(it) }
        ?.let { KeyIds.token(it % KeyIds.STRIDE) } ?: c

    fun samples(word: PracticeWord, events: List<PracticeTap>, keys: List<LayoutKey>, confirmedKeys: String? = null): List<TouchSample>? {
        val typed = mutableListOf<PracticeTap>()
        val auxiliary = mutableListOf<PracticeTap>()
        for (tap in events) when (KeyIds.fromToken(tap.output)?.rem(KeyIds.STRIDE)) {
            delete -> if (typed.isNotEmpty()) { repeat(tap.removed.coerceIn(0, typed.size)) { typed.removeAt(typed.lastIndex) }; if (tap.removed > 0) auxiliary.add(tap) }
            shift, enter -> auxiliary.add(tap)
            else -> typed.add(tap)
        }
        val expected = word.keys.map(::normalized)
        val actual = typed.map { normalized(it.output) }
        if (confirmedKeys != null && confirmedKeys.map(::normalized) != actual) return null
        val pairs = matches(expected, actual) ?: return null
        val result = mutableListOf<TouchSample>()
        var matches = 0
        for ((i, j) in pairs) {
            val id = KeyIds.fromToken(expected[i]) ?: continue
            sample(id, typed[j], keys)?.let { result.add(it); matches++ }
        }
        // Editing/modifier commands are known labels only inside a mostly verified sentence.
        if (matches >= expected.size * .8f) for (tap in auxiliary) {
            val id = KeyIds.fromToken(tap.output) ?: continue
            val base = id % KeyIds.STRIDE
            if (KeyIds.fromToken(tap.literal)?.rem(KeyIds.STRIDE) != base) continue
            if (base == shift && actual.none { it.isUpperCase() }) continue
            sample(id, tap, keys)?.let(result::add)
        }
        return result.takeIf { it.isNotEmpty() }
    }

    private fun matches(expected: List<Char>, actual: List<Char>): List<Pair<Int, Int>>? {
        val n = expected.size; val m = actual.size
        if (n == 0 || n > 384 || m > 384) return null
        val forward = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) forward[i][0] = i
        for (j in 0..m) forward[0][j] = j
        for (i in 1..n) for (j in 1..m) forward[i][j] = minOf(forward[i - 1][j] + 1, forward[i][j - 1] + 1,
            forward[i - 1][j - 1] + if (expected[i - 1] == actual[j - 1]) 0 else 1)
        val distance = forward[n][m]
        if (distance > maxOf(3, n / 8)) return null
        val backward = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) backward[i][m] = n - i
        for (j in 0..m) backward[n][j] = m - j
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) backward[i][j] = minOf(backward[i + 1][j] + 1, backward[i][j + 1] + 1,
            backward[i + 1][j + 1] + if (expected[i] == actual[j]) 0 else 1)
        val byExpected = Array(n) { mutableListOf<Int>() }
        val byActual = IntArray(m)
        for (i in 0 until n) for (j in 0 until m) if (expected[i] == actual[j] && forward[i][j] + backward[i + 1][j + 1] == distance) {
            byExpected[i].add(j); byActual[j]++
        }
        return byExpected.indices.mapNotNull { i -> byExpected[i].singleOrNull()?.takeIf { byActual[it] == 1 }?.let { i to it } }
    }

    data class AlignedTouch(val sample: TouchSample, val event: Int)

    fun editedSamples(word: PracticeWord, events: List<PracticeTap>, keys: List<LayoutKey>, trace: PracticeEditTrace,
                      editorText: String): List<AlignedTouch>? {
        val expected = PracticeEditTrace.encode(word.text)?.toList() ?: return null
        if (PracticeEditTrace.encode(editorText) != trace.text || trace.owners.size != trace.text.length) return null
        val pairs = matches(expected, trace.text.toList()) ?: return null
        val result = mutableListOf<AlignedTouch>()
        val used = mutableSetOf<Int>()
        for ((i, j) in pairs) {
            val event = trace.owners[j]
            val tap = events.getOrNull(event) ?: continue
            val output = PracticeEditTrace.physical(expected[i])
            if (normalized(tap.output) != normalized(output)) continue
            if (output.isLetter() && tap.korean != null && tap.korean != PracticeEditTrace.hangul(expected[i])) continue
            val id = KeyIds.fromToken(output) ?: continue
            if (event in used) continue
            sample(id, tap, keys)?.let { used.add(event); result.add(AlignedTouch(it, event)) }
        }
        if (pairs.size >= expected.size * .8f) for ((event, tap) in events.withIndex()) {
            val id = KeyIds.fromToken(tap.output) ?: continue
            val base = id % KeyIds.STRIDE
            if (base !in setOf(delete, shift, enter) || event in used) continue
            if (base == delete && event !in trace.deletions) continue
            if (base == shift && pairs.none { PracticeEditTrace.physical(expected[it.first]).isUpperCase() }) continue
            if (KeyIds.fromToken(tap.literal)?.rem(KeyIds.STRIDE) != base) continue
            sample(id, tap, keys)?.let { result.add(AlignedTouch(it, event)) }
        }
        return result.takeIf { it.isNotEmpty() }
    }

    private fun sample(expected: Int, tap: PracticeTap, keys: List<LayoutKey>): TouchSample? {
        val raw = KeyIds.fromToken(tap.literal) ?: return null
        val literal = keys.firstOrNull { it.key == raw } ?: return null
        val target = if (expected % KeyIds.STRIDE == space && raw % KeyIds.STRIDE == space) literal
            else keys.firstOrNull { it.key == expected } ?: return null
        if (target.half != literal.half) return null
        val dx = (tap.x - (target.left + target.right) / 2) / target.width
        val dy = (tap.y - (target.top + target.bottom) / 2) / target.height
        if (!dx.isFinite() || !dy.isFinite() || abs(dx) > .72f || abs(dy) > .72f) return null
        if (target.key != literal.key && !((target.row == literal.row && abs(target.left - literal.left) <=
                maxOf(target.width, literal.width) * 1.5f) || (abs(target.row - literal.row) == 1 && tap.x in target.left..target.right))) return null
        return TouchSample(target.key, (dx * 1000).roundToInt(), (dy * 1000).roundToInt(), true)
    }
}
