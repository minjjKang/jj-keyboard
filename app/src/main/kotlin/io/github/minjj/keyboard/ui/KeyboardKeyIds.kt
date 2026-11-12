package io.github.minjj.keyboard.ui

import android.view.View
import io.github.minjj.keyboard.R
import io.github.minjj.keyboard.ime.KeyCommand
import io.github.minjj.keyboard.ime.KeySpec

/** Stable semantic identities survive label, language, Shift and layout changes. */
object KeyboardKeyIds {
    private val letters = "qwertyuiopasdfghjklzxcvbnm".toList().zip(listOf(
        R.id.key_q, R.id.key_w, R.id.key_e, R.id.key_r, R.id.key_t, R.id.key_y,
        R.id.key_u, R.id.key_i, R.id.key_o, R.id.key_p, R.id.key_a, R.id.key_s,
        R.id.key_d, R.id.key_f, R.id.key_g, R.id.key_h, R.id.key_j, R.id.key_k,
        R.id.key_l, R.id.key_z, R.id.key_x, R.id.key_c, R.id.key_v, R.id.key_b,
        R.id.key_n, R.id.key_m,
    )).toMap()
    private val digits = listOf(R.id.key_0, R.id.key_1, R.id.key_2, R.id.key_3, R.id.key_4,
        R.id.key_5, R.id.key_6, R.id.key_7, R.id.key_8, R.id.key_9)

    fun of(key: KeySpec): Int = when (key.command) {
        KeyCommand.CHARACTER -> if (key.letter) letters[key.value.single()] ?: View.NO_ID
            else when (key.value) {
                "," -> R.id.key_comma
                "." -> R.id.key_period
                else -> key.value.singleOrNull()?.digitToIntOrNull()?.let { digits[it] } ?: View.NO_ID
            }
        KeyCommand.SHIFT -> R.id.key_shift
        KeyCommand.DELETE -> R.id.key_delete
        KeyCommand.DELETE_FORWARD -> R.id.key_delete_forward
        KeyCommand.CURSOR_LEFT -> R.id.key_cursor_left
        KeyCommand.CURSOR_RIGHT -> R.id.key_cursor_right
        KeyCommand.SPACE -> R.id.key_space
        KeyCommand.ENTER -> R.id.key_enter
        KeyCommand.LANGUAGE -> R.id.key_language
        KeyCommand.SYMBOLS -> R.id.key_symbols
        KeyCommand.MORE_SYMBOLS -> R.id.key_more_symbols
        KeyCommand.LETTERS -> R.id.key_letters
        KeyCommand.SETTINGS -> R.id.key_settings
        KeyCommand.NEXT_IME -> R.id.key_next_ime
        KeyCommand.MODE -> R.id.key_mode
        KeyCommand.HIDE -> R.id.key_hide
        KeyCommand.CLIPBOARD -> R.id.key_clipboard
        KeyCommand.SUGGESTION -> if (key.value == "0") R.id.key_word_suggestion_0 else R.id.key_word_suggestion_1
        KeyCommand.SPACER -> View.NO_ID
    }
}
