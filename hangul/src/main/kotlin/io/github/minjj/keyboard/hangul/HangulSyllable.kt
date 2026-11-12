package io.github.minjj.keyboard.hangul

/** Modern Hangul syllable arithmetic, independent of Android. */
object HangulSyllable {
    const val INITIALS = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
    const val VOWELS = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
    const val FINALS = "\u0000ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ"

    data class Parts(val initial: Char, val vowel: Char, val final: Char? = null)

    fun compose(initial: Char, vowel: Char, final: Char? = null): Char {
        val l = INITIALS.indexOf(initial)
        val v = VOWELS.indexOf(vowel)
        val t = if (final == null) 0 else FINALS.indexOf(final)
        require(l >= 0 && v >= 0 && t >= 0) { "Invalid modern Hangul components" }
        return (0xAC00 + (l * 21 + v) * 28 + t).toChar()
    }

    fun decompose(syllable: Char): Parts? {
        val offset = syllable.code - 0xAC00
        if (offset !in 0 until 11172) return null
        return Parts(INITIALS[offset / 588], VOWELS[offset % 588 / 28],
            FINALS[offset % 28].takeUnless { it == '\u0000' })
    }
}
