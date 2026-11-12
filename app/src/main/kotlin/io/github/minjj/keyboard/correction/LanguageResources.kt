package io.github.minjj.keyboard.correction

import android.content.Context
import io.github.minjj.keyboard.touch.LanguageModel
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun loadLanguage(context: Context, korean: Boolean): LanguageModel? = withContext(Dispatchers.IO) {
    val code = if (korean) "ko" else "en"
    try {
        val phrases = context.assets.open("language/$code-phrases.txt").bufferedReader().use { it.readLines() }
        context.assets.open("language/$code.tsv").bufferedReader().useLines { lines ->
            LanguageModel(korean, lines.mapNotNull { line ->
                val tab = line.indexOf('\t')
                val weight = if (tab > 0) line.substring(tab + 1).toIntOrNull() else null
                weight?.let { line.substring(0, tab) to it }
            }, phrases.asSequence())
        }
    } catch (_: IOException) {
        null
    }
}
