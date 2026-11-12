package io.github.minjj.keyboard.hangul

/** Each result replaces the editor's existing composing span, never the committed prefix. */
data class CompositionUpdate(val committed: String = "", val composing: String = "")

class HangulComposer {
    private data class State(val initial: Char? = null, val vowel: Char? = null, val final: Char? = null) {
        fun text(): String = when {
            initial != null && vowel != null -> HangulSyllable.compose(initial, vowel, final).toString()
            initial != null -> initial.toString()
            vowel != null -> vowel.toString()
            else -> ""
        }
    }

    private var state = State()
    private val history = mutableListOf<State>()
    val composing: String get() = state.text()
    val isComposing: Boolean get() = composing.isNotEmpty()

    fun input(jamo: Char): CompositionUpdate {
        require(jamo in HangulSyllable.INITIALS || jamo in HangulSyllable.VOWELS)
        val previous = state
        var committed = ""
        var startsNewSyllable = false
        state = if (jamo in HangulSyllable.VOWELS) {
            when {
                previous.vowel == null -> previous.copy(vowel = jamo)
                previous.final != null -> {
                    val split = finalSplits[previous.final]
                    committed = previous.copy(final = split?.first).text()
                    startsNewSyllable = true
                    State(initial = split?.second ?: previous.final, vowel = jamo)
                }
                vowelPairs[Pair(previous.vowel, jamo)] != null ->
                    previous.copy(vowel = vowelPairs.getValue(Pair(previous.vowel, jamo)))
                else -> {
                    committed = previous.text()
                    startsNewSyllable = true
                    State(vowel = jamo)
                }
            }
        } else {
            when {
                previous.initial == null && previous.vowel == null -> State(initial = jamo)
                previous.initial != null && previous.vowel != null && previous.final == null &&
                    HangulSyllable.FINALS.indexOf(jamo) > 0 -> previous.copy(final = jamo)
                previous.final != null && finalPairs[Pair(previous.final, jamo)] != null ->
                    previous.copy(final = finalPairs.getValue(Pair(previous.final, jamo)))
                else -> {
                    committed = previous.text()
                    startsNewSyllable = true
                    State(initial = jamo)
                }
            }
        }
        if (startsNewSyllable) {
            history.clear()
            // A migrated final is now the next syllable's initial, and remains on vowel deletion.
            if (state.initial != null && state.vowel != null) history.add(State(initial = state.initial))
            else history.add(State())
        } else history.add(previous)
        return CompositionUpdate(committed, composing)
    }

    fun backspace(): CompositionUpdate {
        state = if (history.isEmpty()) State() else history.removeAt(history.lastIndex)
        return CompositionUpdate(composing = composing)
    }

    fun finish(): String = composing.also { reset() }
    fun copy(): HangulComposer = HangulComposer().also { it.state = state; it.history.addAll(history) }
    fun reset() { state = State(); history.clear() }

    companion object {
        private val vowelPairs = mapOf(
            ('ㅗ' to 'ㅏ') to 'ㅘ', ('ㅗ' to 'ㅐ') to 'ㅙ', ('ㅗ' to 'ㅣ') to 'ㅚ',
            ('ㅜ' to 'ㅓ') to 'ㅝ', ('ㅜ' to 'ㅔ') to 'ㅞ', ('ㅜ' to 'ㅣ') to 'ㅟ',
            ('ㅡ' to 'ㅣ') to 'ㅢ',
        )
        private val finalPairs = mapOf(
            ('ㄱ' to 'ㅅ') to 'ㄳ', ('ㄴ' to 'ㅈ') to 'ㄵ', ('ㄴ' to 'ㅎ') to 'ㄶ',
            ('ㄹ' to 'ㄱ') to 'ㄺ', ('ㄹ' to 'ㅁ') to 'ㄻ', ('ㄹ' to 'ㅂ') to 'ㄼ',
            ('ㄹ' to 'ㅅ') to 'ㄽ', ('ㄹ' to 'ㅌ') to 'ㄾ', ('ㄹ' to 'ㅍ') to 'ㄿ',
            ('ㄹ' to 'ㅎ') to 'ㅀ', ('ㅂ' to 'ㅅ') to 'ㅄ',
        )
        private val finalSplits = finalPairs.entries.associate { it.value to it.key }
    }
}
