package io.github.minjj.keyboard.touch

/** Links the current editor contents to touches, including insertion/replacement away from the end. */
class PracticeEditTrace {
    var text = ""; private set
    var owners: List<Int> = emptyList(); private set
    val deletions = mutableSetOf<Int>()
    var observed = false; private set
    private var lastEvent = -1
    private var lastCommand: String? = null
    private var eventText = ""
    private var eventOwners: List<Int> = emptyList()
    private var prefixLimit = Int.MAX_VALUE
    private var suffixLimit = Int.MAX_VALUE

    fun clear() {
        text = ""; owners = emptyList(); deletions.clear(); observed = false
        lastEvent = -1; lastCommand = null; eventText = ""; eventOwners = emptyList()
        prefixLimit = Int.MAX_VALUE; suffixLimit = Int.MAX_VALUE
    }

    fun apply(before: String, after: String, event: Int, command: String?, editStart: Int? = null, editEnd: Int? = null): Boolean {
        val beforeKeys = encode(before) ?: return false
        val next = encode(after) ?: return false
        if (beforeKeys.length > 384 || next.length > 384) return false
        observed = true
        val resynchronized = text != beforeKeys
        if (resynchronized) { text = beforeKeys; owners = List(beforeKeys.length) { -1 } }
        // Hangul may commit one syllable and then install another in the same physical keypress.
        if (resynchronized || event < 0 || event != lastEvent || command != lastCommand) {
            eventText = beforeKeys; eventOwners = owners
            prefixLimit = editStart?.let { encode(before.take(it.coerceIn(0, before.length)))?.length } ?: Int.MAX_VALUE
            suffixLimit = editEnd?.let { encode(before.drop(it.coerceIn(0, before.length)))?.length } ?: Int.MAX_VALUE
        }
        lastEvent = event; lastCommand = command
        val old = eventText
        var prefix = 0
        while (prefix < minOf(old.length, next.length, prefixLimit) && old[prefix] == next[prefix]) prefix++
        var suffix = 0
        while (suffix < minOf(minOf(old.length, next.length) - prefix, suffixLimit) &&
            old[old.lastIndex - suffix] == next[next.lastIndex - suffix]) suffix++
        if (command == "DELETE" && event >= 0) {
            deletions.remove(event)
            if (old.length > next.length) deletions.add(event)
        }
        val insertedOwner = event.takeIf { command == "CHARACTER" || command == "SPACE" } ?: -1
        owners = eventOwners.take(prefix) + List(next.length - prefix - suffix) { insertedOwner } + eventOwners.takeLast(suffix)
        text = next
        return true
    }

    companion object {
        private const val HANGUL_KEY = 0xF000
        /** Keep Hangul and Latin input distinct even when their physical key sequences coincide. */
        fun encode(value: String): String? = runCatching {
            buildString {
                for (c in value) when {
                    c == ' ' -> append(PracticeSentences.space)
                    c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' -> append(c)
                    else -> PracticeSentences.keys(c.toString(), true).forEach { append((HANGUL_KEY + it.code).toChar()) }
                }
            }
        }.getOrNull()
        fun physical(key: Char): Char = if (key.code in HANGUL_KEY + 65..HANGUL_KEY + 122) (key.code - HANGUL_KEY).toChar() else key
        fun hangul(key: Char) = key.code in HANGUL_KEY + 65..HANGUL_KEY + 122
    }
}
