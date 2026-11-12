package io.github.minjj.keyboard.touch

import io.github.minjj.keyboard.hangul.Dubeolsik

data class WordCandidate(val word: String, val keys: String)
data class AmbiguousTap(val index: Int, val alternatives: String)

/** A bounded offline prefix index with a small fixed table of adjacent words. */
class LanguageModel(
    private val korean: Boolean,
    frequencies: Sequence<Pair<String, Int>>,
    phrases: Sequence<String>,
) {
    private data class Entry(val word: String, val keys: String, val weight: Int)
    private class Node {
        var children: MutableMap<Char, Node>? = null
        var exact: Entry? = null
        val best = ArrayList<Entry>(3)
    }

    private val root = Node()
    private val transitions: Map<String, List<Entry>>

    init {
        val sentences = phrases.map { it.trim().split(' ') }.toList()
        val entries = LinkedHashMap<String, Entry>()
        for ((word, weight) in frequencies) {
            val keys = keysFor(word) ?: continue
            entries.putIfAbsent(word, Entry(word, keys, weight))
        }
        for (sentence in sentences) for (word in sentence) {
            val keys = keysFor(word) ?: continue
            entries.putIfAbsent(word, Entry(word, keys, 480))
        }
        for (entry in entries.values.sortedByDescending { it.weight }) {
            var node = root
            for (key in entry.keys) {
                val children = node.children ?: mutableMapOf<Char, Node>().also { node.children = it }
                node = children.getOrPut(key, ::Node)
                if (node.best.size < 3) node.best += entry
            }
            if (node.exact == null) node.exact = entry
        }
        val adjacent = mutableMapOf<String, MutableMap<Entry, Int>>()
        for (sentence in sentences) for ((before, after) in sentence.zipWithNext()) {
            val entry = entries[after] ?: continue
            val counts = adjacent.getOrPut(before, ::mutableMapOf)
            counts[entry] = (counts[entry] ?: 0) + 1
        }
        transitions = adjacent.mapValues { (_, next) -> next.entries.sortedByDescending { it.value }.map { it.key } }
    }

    private fun keysFor(word: String): String? {
        if (word.isEmpty()) return null
        return if (korean) Dubeolsik.keysFor(word) else word.takeIf { it.all { c -> c in 'a'..'z' } }
    }

    private fun normalize(keys: String) = if (korean) keys else keys.lowercase()
    private fun node(keys: String): Node? {
        var node = root
        for (key in keys) node = node.children?.get(key) ?: return null
        return node
    }

    fun evidence(keys: String, previous: String?, nextKey: Char): Float {
        val prefix = normalize(keys + nextKey)
        val linked = previous?.let { transitions[it.lowercase()] }.orEmpty()
        val context = if (linked.any { it.keys.startsWith(prefix) }) 0.08f else 0f
        val weight = node(prefix)?.best?.firstOrNull()?.weight ?: return context
        // At a word boundary, use a known transition instead of globally favoring common initials.
        if (keys.isEmpty() && context == 0f) return 0f
        val frequency = ((weight - 300) / 500f).coerceIn(0f, 1f)
        return (0.07f + frequency * 0.05f + context).coerceAtMost(0.18f)
    }

    fun suggestions(keys: String, literal: String, previous: String?, ambiguity: List<AmbiguousTap>): List<WordCandidate> {
        if (keys.length < 2) return emptyList()
        val prefix = normalize(keys)
        val replacements = buildList {
            for ((index, alternatives) in ambiguity) {
                if (index !in prefix.indices) continue
                for (key in normalize(alternatives)) {
                    val variant = prefix.substring(0, index) + key + prefix.substring(index + 1)
                    node(variant)?.exact?.let(::add)
                }
            }
        }
        val contextual = previous?.let { transitions[it.lowercase()] }.orEmpty().filter { it.keys.startsWith(prefix) }
        val completions = node(prefix)?.best.orEmpty()
        return (contextual + replacements.sortedByDescending { it.weight } + completions)
            .asSequence().filter { !it.word.equals(literal, ignoreCase = !korean) }
            .distinctBy { it.word }.take(2).map { entry ->
                if (korean) WordCandidate(entry.word, entry.keys)
                else {
                    val word = when {
                        keys.length > 1 && keys.all { it.isUpperCase() } -> entry.word.uppercase()
                        keys.first().isUpperCase() -> entry.word.replaceFirstChar { it.uppercaseChar() }
                        else -> entry.word
                    }
                    WordCandidate(word, word)
                }
            }.toList()
    }
}
