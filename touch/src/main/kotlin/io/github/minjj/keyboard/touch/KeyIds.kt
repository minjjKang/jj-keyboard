package io.github.minjj.keyboard.touch

/** Stable storage identities. Existing letter IDs 0..25 never change. Occurrences distinguish duplicate keys. */
object KeyIds {
    const val STRIDE = 256
    const val LIMIT = 1024
    private const val FUNCTIONS_START = 160
    const val SYMBOLS = "+×÷=/_<>[]`~\\|{}€£¥$₩!@#%^&*()-'\":;,?•○●□■♤♡◇♧°☆▪¤《》¡¿."
    val functions = listOf("SHIFT", "DELETE", "DELETE_FORWARD", "CURSOR_LEFT", "CURSOR_RIGHT", "SPACE", "ENTER",
        "LANGUAGE", "SYMBOLS", "MORE_SYMBOLS", "LETTERS", "SETTINGS", "NEXT_IME", "MODE", "HIDE", "CLIPBOARD")
    private val functionLabels = listOf("Shift", "삭제", "앞으로 삭제", "커서 왼쪽", "커서 오른쪽", "공백", "Enter",
        "한/영", "기호", "기호 페이지", "문자", "설정", "입력기 선택", "배치 전환", "키보드 닫기", "클립보드")
    fun character(c: Char): Int? = when {
        c.lowercaseChar() in PHYSICAL_LETTERS -> PHYSICAL_LETTERS.indexOf(c.lowercaseChar())
        c in '0'..'9' -> 26 + (c - '0')
        c in SYMBOLS -> 36 + SYMBOLS.indexOf(c)
        else -> null
    }
    fun function(name: String): Int? = functions.indexOf(name).takeIf { it >= 0 }?.let { FUNCTIONS_START + it }
    fun occurrence(base: Int, index: Int): Int = base + STRIDE * index
    fun valid(id: Int): Boolean = id in 0 until LIMIT && (id % STRIDE in 0..35 ||
        id % STRIDE in 36 until 36 + SYMBOLS.length || id % STRIDE in FUNCTIONS_START until FUNCTIONS_START + functions.size)
    fun isFunction(id: Int) = id % STRIDE >= FUNCTIONS_START
    fun kind(id: Int): Int = when (id % STRIDE) { in 0..25 -> 0; in 26..35 -> 1; in 36..159 -> 2; else -> 3 }
    fun glyph(id: Int): Char? = when (val base = id % STRIDE) {
        in 0..25 -> PHYSICAL_LETTERS[base]
        in 26..35 -> '0' + (base - 26)
        in 36 until 36 + SYMBOLS.length -> SYMBOLS[base - 36]
        else -> null
    }
    fun token(id: Int): Char = if (id < STRIDE) glyph(id) ?: (0xE000 + id).toChar() else (0xE000 + id).toChar()
    fun fromToken(token: Char): Int? = if (token.code in 0xE000 until 0xE000 + LIMIT)
        (token.code - 0xE000).takeIf(::valid) else character(token)
    fun label(id: Int): String {
        val base = id % STRIDE
        val label = glyph(id)?.toString() ?: functionLabels[base - FUNCTIONS_START]
        return if (id < STRIDE) label else when (base) {
            function("SPACE") -> "오른쪽 공백"
            function("SHIFT") -> "오른쪽 Shift"
            else -> "$label ${id / STRIDE + 1}"
        }
    }
    fun legacyRow(mask: Int): Set<Int> = PHYSICAL_LETTERS.indices.filterTo(linkedSetOf()) { mask and (1 shl it) != 0 }
}
