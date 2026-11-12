package io.github.minjj.keyboard.ime

import android.icu.text.BreakIterator
import android.view.inputmethod.InputConnection
import android.view.inputmethod.ExtractedTextRequest
import android.os.Build
import android.os.SystemClock
import io.github.minjj.keyboard.hangul.HangulComposer
import io.github.minjj.keyboard.correction.TouchObservation
import io.github.minjj.keyboard.correction.TouchTraining
import io.github.minjj.keyboard.correction.WordContext
import io.github.minjj.keyboard.correction.WordSnapshot
import io.github.minjj.keyboard.touch.TouchSample
import io.github.minjj.keyboard.touch.WordCandidate
import java.util.Locale

/** A captured connection belongs to exactly one onStartInput session. Failed writes aren't retried. */
class EditorSession(
    private val connection: InputConnection,
    val profile: EditorProfile,
    recordSpatial: (Int, Long, List<TouchSample>) -> Unit = { _, _, _ -> },
) {
    private var composer = HangulComposer()
    private val touchTraining = TouchTraining(recordSpatial)
    private val words = WordContext()
    private var wordBoundaryPending = true
    private data class WordUndo(val candidate: WordCandidate, val prior: WordSnapshot, val composer: HangulComposer)
    private var wordUndo: WordUndo? = null
    private var active = true
    internal val activeForTouch get() = active
    internal var ownerConnection: InputConnection = connection
    private var expectedCursor = -1
    private var cursorSelection: Pair<Int, Int>? = null
    private val pendingCursors = ArrayDeque<Int>()
    private var lastSpaceTime = -1L
    private var lastSpaceCursor = -1
    private data class DeletedChunk(val text: String, val cursor: Int, val before: String, val after: String)
    private val deletedChunks = ArrayDeque<DeletedChunk>()
    fun discardDeleteUndo() { deletedChunks.clear() }
    internal fun discardTouchTraining() = touchTraining.clear()
    internal fun observeTouch(touch: TouchObservation?) {
        touchTraining.letter(touch.takeIf { active && profile.allowsSpatialLearning })
    }

    fun owns(candidate: InputConnection?): Boolean = active && candidate === ownerConnection

    fun startCursor(cursor: Int, before: CharSequence? = null) {
        expectedCursor = cursor
        words.clear(atStart = cursor == 0)
        wordBoundaryPending = true
        if (before != null && profile.allowsLanguageModel) words.startAfter(before)
    }

    internal fun clearWordContext() {
        words.clear(atStart = expectedCursor == 0)
        wordBoundaryPending = true
        wordUndo = null
    }

    internal fun prepareWordContext(korean: Boolean) {
        if (!active || !profile.allowsLanguageModel || !wordBoundaryPending) return
        wordBoundaryPending = false
        if (words.snapshot(korean) != null) return
        // Initial surrounding text is optional, and hiding the IME or editing can clear
        // the tracked word. Recheck a boundary once, before this IME writes a new letter.
        val range = selectionRange(requireReliable = true) ?: return
        if (range.first != expectedCursor || range.second != expectedCursor) return
        if (expectedCursor == 0) words.clear(atStart = true)
        else connection.getTextBeforeCursor(128, 0)?.let(words::startAfter)
    }

    internal fun wordSnapshot(korean: Boolean) =
        if (active && profile.allowsLanguageModel) words.snapshot(korean) else null

    internal fun recordLetter(key: Char, alternatives: String) {
        if (active && profile.allowsLanguageModel) words.letter(key, alternatives)
    }

    internal fun selectWord(snapshot: WordSnapshot, candidate: WordCandidate): Boolean {
        if (wordSnapshot(snapshot.korean)?.revision != snapshot.revision) return false
        val literal = snapshot.literal() ?: return false
        if (literal.isEmpty() || literal == candidate.word || !verifiedWord(literal)) return false
        val priorComposer = composer.copy()
        if (!replaceWord(literal, candidate.word)) return false
        discardTouchTraining()
        words.selected(candidate)
        wordUndo = WordUndo(candidate, snapshot, priorComposer)
        return true
    }

    private fun verifiedWord(literal: String): Boolean {
        if (!active || expectedCursor < literal.length) return false
        val range = selectionRange(requireReliable = true) ?: return false
        if (range.first != expectedCursor || range.second != expectedCursor) return false
        val before = connection.getTextBeforeCursor(literal.length + 2, 0)?.toString() ?: return false
        val after = connection.getTextAfterCursor(2, 0)?.toString() ?: return false
        val prefixLength = before.length - literal.length
        return before.endsWith(literal) &&
            (prefixLength == 0 || !Character.isLetterOrDigit(Character.codePointBefore(before, prefixLength))) &&
            (after.isEmpty() || !Character.isLetterOrDigit(Character.codePointAt(after, 0)))
    }

    private fun replaceWord(literal: String, replacement: String, restored: HangulComposer? = null): Boolean {
        val start = expectedCursor - literal.length
        val end = start + replacement.length
        connection.beginBatchEdit()
        try {
            if (!connection.setComposingRegion(start, start + literal.length)) return false
            discardDeleteUndo()
            clearSpaceSequence()
            composer.reset()
            trackCursor(end)
            if (!connection.commitText(replacement, 1)) {
                invalidate()
                return false
            }
            if (restored != null && restored.isComposing &&
                connection.setComposingRegion(end - restored.composing.length, end)
            ) composer = restored
            return active
        } finally {
            connection.endBatchEdit()
        }
    }
    class CompositionCheckpoint internal constructor(internal val composer: HangulComposer,
        internal val cursor: Int, internal val before: String, internal val after: String)

    fun checkpoint(): CompositionCheckpoint? {
        if (!active || profile.password || !composer.isComposing || expectedCursor < 0) return null
        val range = selectionRange() ?: return null
        if (range.first != expectedCursor || range.second != expectedCursor) return null
        val before = connection.getTextBeforeCursor(64, 0)?.toString() ?: return null
        val after = connection.getTextAfterCursor(64, 0)?.toString() ?: return null
        if (!before.endsWith(composer.composing)) return null
        return CompositionCheckpoint(composer.copy(), expectedCursor, before, after)
    }

    /** Restore only a verified existing span, never reinsert cached text into the editor. */
    fun restore(checkpoint: CompositionCheckpoint): Boolean {
        if (!active || profile.password) return false
        val range = selectionRange() ?: return false
        if (range.first != checkpoint.cursor || range.second != checkpoint.cursor ||
            connection.getTextBeforeCursor(64, 0)?.toString() != checkpoint.before ||
            connection.getTextAfterCursor(64, 0)?.toString() != checkpoint.after) return false
        mutate(checkpoint.cursor) { connection.setComposingRegion(checkpoint.cursor - checkpoint.composer.composing.length, checkpoint.cursor) }
        if (active) composer = checkpoint.composer.copy()
        return active
    }

    fun jamo(char: Char): Boolean {
        wordUndo = null
        discardDeleteUndo()
        if (!active) return false
        clearSpaceSequence()
        val oldLength = composer.composing.length
        val update = composer.input(char)
        val newCursor = if (expectedCursor < 0) -1 else expectedCursor - oldLength + update.committed.length + update.composing.length
        mutate(newCursor) {
            // commitText replaces the existing composing span before the new syllable is installed.
            if (update.committed.isNotEmpty() && !connection.commitText(update.committed, 1)) return@mutate false
            connection.setComposingText(update.composing, 1)
        }
        return active
    }

    fun text(text: String): Boolean {
        discardDeleteUndo()
        clearSpaceSequence()
        if (!active || !finishComposition()) return false
        mutate(if (expectedCursor < 0) -1 else expectedCursor + text.length) { connection.commitText(text, 1) }
        return active
    }

    fun backspace() {
        discardDeleteUndo()
        clearSpaceSequence()
        if (!active) return
        val undo = wordUndo
        wordUndo = null
        if (undo != null && verifiedWord(undo.candidate.word)) {
            val literal = undo.prior.literal() ?: return
            if (replaceWord(undo.candidate.word, literal, undo.composer)) words.restore(undo.prior)
            return
        }
        clearWordContext()
        if (composer.isComposing) {
            val oldLength = composer.composing.length
            val remaining = composer.backspace().composing
            mutate(if (expectedCursor < 0) -1 else expectedCursor - oldLength + remaining.length) {
                if (!connection.setComposingText(remaining, 1)) false
                else if (remaining.isEmpty()) connection.finishComposingText() else true
            }
            return
        }
        val selected = connection.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            // Track the next location from the editor callback rather than guessing selection direction.
            expectedCursor = -1
            mutate(-1) { connection.commitText("", 1) }
            return
        }
        val before = connection.getTextBeforeCursor(4096, 0)?.toString()
        if (before.isNullOrEmpty()) {
            // No raw key fallback: a null surrounding-text query can also mean a stale connection.
            return
        }
        val count = GraphemeDeletion.previousLength(before)
        if (count == 0) return
        mutate(if (expectedCursor < 0) -1 else (expectedCursor - count).coerceAtLeast(0)) {
            connection.deleteSurroundingText(count, 0)
        }
    }

    fun space(periodShortcut: Boolean, now: Long = SystemClock.uptimeMillis(), touchBoundary: Boolean = false,
              contextLanguage: Boolean? = null) {
        if (!touchBoundary) discardTouchTraining()
        if (!active) return
        val allowed = periodShortcut && !profile.password && !profile.email && !profile.uri && profile.layout == InputLayout.TEXT
        if (allowed && lastSpaceTime >= 0 && now - lastSpaceTime in 0..500 && expectedCursor == lastSpaceCursor) {
            val before = connection.getTextBeforeCursor(4, 0)?.toString().orEmpty()
            if (before.endsWith(' ') && before.dropLast(1).lastOrNull()?.isLetterOrDigit() == true) {
                clearSpaceSequence()
                mutate(if (expectedCursor < 0) -1 else expectedCursor + 1) {
                    if (!connection.deleteSurroundingText(1, 0)) false else connection.commitText(". ", 1)
                }
                clearWordContext()
                return
            }
        }
        if (text(" ")) {
            if (touchBoundary) touchTraining.space()
            if (contextLanguage != null) words.space(contextLanguage)
        }
        if (active && allowed) { lastSpaceTime = now; lastSpaceCursor = expectedCursor }
    }

    fun deleteForward() {
        discardDeleteUndo()
        clearSpaceSequence()
        if (!active || !finishComposition()) return
        if (!connection.getSelectedText(0).isNullOrEmpty()) { backspace(); return }
        val after = connection.getTextAfterCursor(4096, 0)?.toString().orEmpty()
        val count = GraphemeDeletion.nextLength(after)
        if (count > 0) mutate(expectedCursor) { connection.deleteSurroundingText(0, count) }
    }

    /** A bounded editor snapshot supplies absolute UTF-16 positions; movement respects ICU boundaries. */
    fun moveCursor(direction: Int, extendSelection: Boolean = false) {
        discardDeleteUndo()
        clearSpaceSequence()
        if (!active || !finishComposition()) return
        val reported = selectionRange() ?: return
        // SurroundingText can normalize a backwards selection. Keep our anchor while its bounds match.
        val range = cursorSelection?.takeIf {
            minOf(it.first, it.second) == minOf(reported.first, reported.second) &&
                maxOf(it.first, it.second) == maxOf(reported.first, reported.second)
        } ?: reported
        val left = minOf(range.first, range.second)
        val right = maxOf(range.first, range.second)
        val cursor = if (extendSelection) range.second else left
        val target = if (!extendSelection && left != right) {
            if (direction < 0) left else right
        } else if (direction < 0) {
            val before = if (extendSelection && cursor > left) connection.getSelectedText(0)
                else connection.getTextBeforeCursor(4096, 0)
            (cursor - GraphemeDeletion.previousLength(before?.takeLast(4096)?.toString().orEmpty())).coerceAtLeast(0)
        } else {
            val after = if (extendSelection && cursor < right) connection.getSelectedText(0)
                else connection.getTextAfterCursor(4096, 0)
            cursor + GraphemeDeletion.nextLength(after?.take(4096)?.toString().orEmpty())
        }
        val anchor = if (extendSelection) range.first else target
        mutate(minOf(anchor, target)) { connection.setSelection(anchor, target) }
        if (active) cursorSelection = anchor to target
    }

    private fun selectionRange(requireReliable: Boolean = false): Pair<Int, Int>? {
        if (Build.VERSION.SDK_INT >= 31) {
            val surrounding = connection.getSurroundingText(1, 1, 0)
            if (surrounding != null && surrounding.offset >= 0) {
                return (surrounding.offset + surrounding.selectionStart) to (surrounding.offset + surrounding.selectionEnd)
            }
        }
        val extracted = connection.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = 128; hintMaxLines = 1 }, 0)
        if (extracted != null && extracted.startOffset >= 0 && extracted.selectionStart >= 0 && extracted.selectionEnd >= 0) {
            return (extracted.startOffset + extracted.selectionStart) to (extracted.startOffset + extracted.selectionEnd)
        }
        return if (!requireReliable && expectedCursor >= 0 && connection.getSelectedText(0).isNullOrEmpty()) expectedCursor to expectedCursor else null
    }

    /** Only gesture deletions in this connection can be undone. Never retain password text. */
    fun slideDelete(steps: Int, byWord: Boolean = false): Int {
        if (steps == 0 || !active || profile.password || !finishComposition()) return 0
        val range = selectionRange() ?: return 0
        if (steps < 0) {
            val selected = connection.getSelectedText(0)?.toString().orEmpty()
            var before = connection.getTextBeforeCursor(4096, 0)?.toString() ?: return 0
            val after = connection.getTextAfterCursor(64, 0)?.toString() ?: return 0
            if (selected.length > 4096) return 0
            var cursor = minOf(range.first, range.second)
            var count = 0
            val chunks = mutableListOf<DeletedChunk>()
            val truncated = before.length < cursor
            val words = if (byWord) BreakIterator.getWordInstance(Locale.ROOT).apply { setText(before) } else null
            repeat(-steps.coerceAtLeast(-64)) { index ->
                val length = when {
                    index == 0 && selected.isNotEmpty() -> 0
                    words != null -> previousWordLength(words, before, truncated)
                    else -> GraphemeDeletion.previousLength(before)
                }
                val removed = if (index == 0 && selected.isNotEmpty()) selected else before.takeLast(length)
                if (removed.isNotEmpty()) {
                    count += length; cursor -= length; before = before.dropLast(length)
                    chunks += DeletedChunk(removed, cursor, before.takeLast(64), after)
                }
            }
            if (chunks.isEmpty()) return 0
            mutate(cursor) {
                if (selected.isNotEmpty() && !connection.commitText("", 1)) false
                else count == 0 || connection.deleteSurroundingText(count, 0)
            }
            if (!active) return 0
            deletedChunks.addAll(chunks)
            while (deletedChunks.sumOf { it.text.length } > 4096) deletedChunks.removeFirst()
            return -chunks.size
        } else if (steps > 0) {
            val chunk = deletedChunks.lastOrNull() ?: return 0
            if (range.first != chunk.cursor || range.second != chunk.cursor ||
                connection.getTextBeforeCursor(64, 0)?.toString() != chunk.before ||
                connection.getTextAfterCursor(64, 0)?.toString() != chunk.after) {
                discardDeleteUndo(); return 0
            }
            val chunks = deletedChunks.takeLast(steps.coerceAtMost(64)).asReversed()
            val text = chunks.joinToString("") { it.text }
            repeat(chunks.size) { deletedChunks.removeLast() }
            mutate(chunk.cursor + text.length) { connection.commitText(text, 1) }
            return if (active) chunks.size else 0
        }
        return 0
    }

    private fun previousWordLength(words: BreakIterator, before: String, truncated: Boolean): Int {
        if (before.isEmpty()) return 0
        var boundary = words.following(before.lastIndex)
        while (boundary > 0) {
            val isWord = words.ruleStatus != BreakIterator.WORD_NONE
            boundary = words.previous()
            if (isWord) return if (boundary == 0 && truncated) 0 else before.length - boundary
        }
        return GraphemeDeletion.previousLength(before)
    }

    private fun clearSpaceSequence() { lastSpaceTime = -1; lastSpaceCursor = -1 }

    fun enter() {
        discardDeleteUndo()
        if (!active || !finishComposition()) return
        val action = profile.customActionId ?: profile.action
        if (profile.customActionId != null || action !in setOf(0, 1)) {
            if (!connection.performEditorAction(action)) invalidate()
        } else text("\n")
    }

    fun capsRequested(): Boolean = active && profile.capsMode != 0 && connection.getCursorCapsMode(profile.capsMode) != 0

    fun finishComposition(): Boolean {
        if (!active) return false
        wordUndo = null
        clearSpaceSequence()
        composer.reset()
        return connection.finishComposingText().also { if (!it) invalidate() }
    }

    fun selectionChanged(start: Int, end: Int, candidatesStart: Int, candidatesEnd: Int) {
        if (!active) return
        // Editors can deliver several queued callbacks after a burst of composing updates.
        if (start == end && pendingCursors.contains(end)) {
            while (pendingCursors.isNotEmpty()) { if (pendingCursors.removeFirst() == end) break }
            if (pendingCursors.isEmpty()) expectedCursor = end
            return
        }
        if (start == end && end == expectedCursor && (!composer.isComposing ||
                (candidatesStart >= 0 && candidatesEnd == end))) return
        if (expectedCursor < 0 && start == end && composer.isComposing && candidatesStart >= 0 &&
            candidatesEnd == end && candidatesEnd - candidatesStart == composer.composing.length) {
            expectedCursor = end
            return
        }
        composer.reset()
        clearSpaceSequence()
        discardDeleteUndo()
        discardTouchTraining()
        pendingCursors.clear()
        expectedCursor = if (start == end) end else minOf(start, end)
        words.clear(atStart = start == 0 && end == 0)
        wordBoundaryPending = true
        wordUndo = null
        if (!connection.finishComposingText()) invalidate()
    }

    fun close() { if (active) connection.finishComposingText(); invalidate() }
    fun invalidate() {
        active = false
        cursorSelection = null
        composer.reset()
        pendingCursors.clear()
        clearSpaceSequence()
        discardDeleteUndo()
        discardTouchTraining()
        clearWordContext()
    }

    private fun trackCursor(cursor: Int) {
        cursorSelection = null
        expectedCursor = cursor
        if (cursor >= 0) {
            pendingCursors.addLast(cursor)
            if (pendingCursors.size > 64) pendingCursors.removeFirst()
        }
    }

    private inline fun mutate(cursor: Int, action: () -> Boolean) {
        trackCursor(cursor)
        connection.beginBatchEdit()
        try { if (!action()) invalidate() } finally { connection.endBatchEdit() }
    }
}

object GraphemeDeletion {
    fun nextLength(after: String): Int {
        if (after.isEmpty()) return 0
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(after)
        val end = iterator.following(0)
        if (end == BreakIterator.DONE || (end == after.length && after.length >= 4096)) return 0
        return end
    }
    fun previousLength(before: String): Int {
        if (before.isEmpty()) return 0
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(before)
        val boundary = iterator.preceding(before.length)
        if (boundary == BreakIterator.DONE) return 0
        // The bounded query may begin inside a much larger cluster. Leave it intact when ambiguous.
        if (boundary == 0 && before.length >= 4096) return 0
        return before.length - boundary
    }
}
