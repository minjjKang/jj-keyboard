package io.github.minjj.keyboard.hangul

object Dubeolsik {
    private const val LATIN = "qwertyuiopasdfghjklzxcvbnm"
    private const val JAMO = "ㅂㅈㄷㄱㅅㅛㅕㅑㅐㅔㅁㄴㅇㄹㅎㅗㅓㅏㅣㅋㅌㅊㅍㅠㅜㅡ"
    private val shifted = mapOf('q' to 'ㅃ', 'w' to 'ㅉ', 'e' to 'ㄸ', 'r' to 'ㄲ',
        't' to 'ㅆ', 'o' to 'ㅒ', 'p' to 'ㅖ')

    fun map(latin: Char, shift: Boolean = latin.isUpperCase()): Char? {
        val lower = latin.lowercaseChar()
        if (shift && lower in shifted) return shifted[lower]
        return LATIN.indexOf(lower).takeIf { it >= 0 }?.let { JAMO[it] }
    }

    private val keyByJamo = LATIN.zip(JAMO).associate { (key, jamo) -> jamo to key.toString() } +
        shifted.entries.associate { (key, jamo) -> jamo to key.uppercase() } +
        mapOf('ㅘ' to "hk", 'ㅙ' to "ho", 'ㅚ' to "hl", 'ㅝ' to "nj", 'ㅞ' to "np", 'ㅟ' to "nl",
            'ㅢ' to "ml", 'ㄳ' to "rt", 'ㄵ' to "sw", 'ㄶ' to "sg", 'ㄺ' to "fr", 'ㄻ' to "fa",
            'ㄼ' to "fq", 'ㄽ' to "ft", 'ㄾ' to "fx", 'ㄿ' to "fv", 'ㅀ' to "fg", 'ㅄ' to "qt")

    fun keysFor(word: String): String? = buildString {
        for (syllable in word) {
            val parts = HangulSyllable.decompose(syllable) ?: return null
            append(keyByJamo.getValue(parts.initial))
            append(keyByJamo.getValue(parts.vowel))
            parts.final?.let { append(keyByJamo.getValue(it)) }
        }
    }

    fun composeKeys(keys: String): String? = buildString {
        val composer = HangulComposer()
        for (key in keys) append(composer.input(map(key) ?: return null).committed)
        append(composer.composing)
    }
}
