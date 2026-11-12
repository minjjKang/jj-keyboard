package io.github.minjj.keyboard.ime

import android.os.SystemClock
import io.github.minjj.keyboard.hangul.Dubeolsik
import io.github.minjj.keyboard.correction.TouchObservation
import io.github.minjj.keyboard.correction.PersonalizationSessionBridge
import io.github.minjj.keyboard.correction.WordSnapshot
import io.github.minjj.keyboard.touch.LanguageModel
import io.github.minjj.keyboard.touch.PHYSICAL_LETTERS
import io.github.minjj.keyboard.touch.WordCandidate

enum class KeyCommand { CHARACTER, SHIFT, DELETE, DELETE_FORWARD, CURSOR_LEFT, CURSOR_RIGHT, SPACE, ENTER, LANGUAGE, SYMBOLS, MORE_SYMBOLS, LETTERS, SETTINGS, NEXT_IME, MODE, HIDE, CLIPBOARD, SUGGESTION, SPACER }
data class KeySpec(val label: String, val command: KeyCommand, val value: String = label, val weight: Float = 1f,
    val letter: Boolean = false, val splitBefore: Boolean = false, val symbolSlot: String? = null)
enum class ShiftState { OFF, ONCE, LOCKED }
enum class InputSource { DIRECT, TOUCH_TAP, TOUCH_LONG_PRESS, TOUCH_GESTURE }

/** Whether the editor accepted the character write, independent of the user's intent. */
data class CharacterInputResult(val source: InputSource, val accepted: Boolean)
internal data class WordOffer(val editor: EditorSession, val snapshot: WordSnapshot, val candidate: WordCandidate)

class KeyboardController(
    private val session: () -> EditorSession?,
    private val languageChanged: (Boolean) -> Unit,
    private val settings: () -> Unit,
    private val nextIme: () -> Unit,
    private val changed: () -> Unit,
    private val modeChanged: () -> Unit = {},
    private val hide: () -> Unit = {},
    private val clipboard: () -> Unit = {},
    private val interaction: () -> Unit = {},
) {
    var korean = true; private set
    var symbolsPage = 0; private set
    var shift = ShiftState.OFF; private set
    var shiftHeld = false; private set
    private var heldShiftUsed = false
    private var lastShiftTap = 0L
    private var autoShift = false
    private var autoCapitalization = true
    private var doubleSpacePeriod = false
    private var contextualTouch = false
    private var suggestWords = false
    private val languages = mutableMapOf<Boolean, LanguageModel>()
    internal var practiceSession: PersonalizationSessionBridge.Session? = null
    val uppercase get() = shiftHeld || shift != ShiftState.OFF

    fun reset() {
        symbolsPage = 0; shift = ShiftState.OFF; autoShift = false; shiftHeld = false; heldShiftUsed = false
        refreshAutoCaps(); changed()
    }
    fun subtype(korean: Boolean) {
        if (this.korean != korean) session()?.let { it.finishComposition(); it.clearWordContext() }
        this.korean = korean; practiceSession?.inputKorean = korean; shift = ShiftState.OFF; autoShift = false; refreshAutoCaps(); changed()
    }
    fun holdShift() { shiftHeld = true; heldShiftUsed = false; changed() }
    fun releaseShift(cancelled: Boolean): Boolean {
        shiftHeld = false
        val tapped = !cancelled && !heldShiftUsed
        if (!tapped) changed()
        return tapped
    }
    fun cancelTouches(notify: Boolean = true) { shiftHeld = false; heldShiftUsed = false; if (notify) changed() }
    internal fun captureHeldShift(key: KeySpec): Boolean {
        if (shiftHeld && key.letter) heldShiftUsed = true
        return shiftHeld
    }
    internal val activeForTouch get() = session()?.activeForTouch == true
    internal fun practicePage(page: Int) {
        if (symbolsPage == page || practiceSession == null) return
        session()?.finishComposition(); cancelTouches(false)
        symbolsPage = page; shift = ShiftState.OFF; autoShift = false; changed()
    }
    internal fun discardTouchTraining() { session()?.discardTouchTraining() }

    internal fun configureLanguageOptions(contextualTouch: Boolean, suggestWords: Boolean) {
        if (this.contextualTouch != contextualTouch || this.suggestWords != suggestWords) session()?.clearWordContext()
        this.contextualTouch = contextualTouch
        this.suggestWords = suggestWords
    }

    internal fun loadLanguage(korean: Boolean, model: LanguageModel) {
        languages[korean] = model
        changed()
    }

    private fun wordKey(key: Char, uppercase: Boolean): Char {
        val shifted = uppercase && (!korean || Dubeolsik.map(key, true) != Dubeolsik.map(key, false))
        return if (shifted) key.uppercaseChar() else key
    }

    internal fun contextualEvidence(heldShiftForKey: Boolean = shiftHeld): ((Int) -> Float)? {
        if (!contextualTouch) return null
        val model = languages[korean] ?: return null
        val word = session()?.wordSnapshot(korean) ?: return null
        if (word.keys.isEmpty() && word.previous == null) return null
        val shifted = heldShiftForKey || shift != ShiftState.OFF
        return evidence@ { index ->
            val key = PHYSICAL_LETTERS.getOrNull(index) ?: return@evidence 0f
            val token = if (shifted && (!word.korean || Dubeolsik.map(key, true) != Dubeolsik.map(key, false))) {
                key.uppercaseChar()
            } else key
            model.evidence(word.keys, word.previous, token)
        }
    }

    internal fun wordOffers(): List<WordOffer> {
        if (!suggestWords || symbolsPage != 0) return emptyList()
        val model = languages[korean] ?: return emptyList()
        val editor = session() ?: return emptyList()
        val word = editor.wordSnapshot(korean) ?: return emptyList()
        val literal = word.literal() ?: return emptyList()
        return model.suggestions(word.keys, literal, word.previous, word.ambiguous).map { WordOffer(editor, word, it) }
    }

    internal fun selectWord(offer: WordOffer) {
        val editor = session() ?: return
        if (!suggestWords || editor !== offer.editor || offer.snapshot.korean != korean || symbolsPage != 0) return
        interaction()
        editor.selectWord(offer.snapshot, offer.candidate)
        refreshAutoCaps()
        changed()
    }
    fun tapShift(now: Long) {
        autoShift = false
        shift = when {
            shift == ShiftState.LOCKED -> ShiftState.OFF
            shift == ShiftState.ONCE && now - lastShiftTap in 0..400 -> ShiftState.LOCKED
            shift == ShiftState.ONCE -> ShiftState.OFF
            else -> ShiftState.ONCE
        }
        lastShiftTap = now; changed()
    }

    fun press(key: KeySpec, source: InputSource = InputSource.DIRECT, touch: TouchObservation? = null,
              alternatives: String = "", heldShiftForKey: Boolean? = null,
              eventTime: Long? = null): CharacterInputResult? {
        if (key.command == KeyCommand.SUGGESTION || key.command == KeyCommand.SPACER) return null
        practiceSession?.let {
            if (it.word?.actionKey != null) { it.reject("non-touch-function"); return null }
            val sentence = it.word?.sentence == true
            val allowed = if (sentence) setOf(KeyCommand.CHARACTER, KeyCommand.SHIFT, KeyCommand.SPACE, KeyCommand.DELETE, KeyCommand.ENTER,
                KeyCommand.LANGUAGE, KeyCommand.CURSOR_LEFT, KeyCommand.CURSOR_RIGHT)
                else setOf(KeyCommand.CHARACTER, KeyCommand.SHIFT, KeyCommand.ENTER)
            val navigation = sentence && (key.command == KeyCommand.LANGUAGE || key.command == KeyCommand.CURSOR_LEFT || key.command == KeyCommand.CURSOR_RIGHT)
            val permittedSource = source == InputSource.TOUCH_TAP || (navigation && source == InputSource.DIRECT) ||
                (sentence && key.command == KeyCommand.LANGUAGE && source == InputSource.TOUCH_GESTURE)
            if (key.command !in allowed || !permittedSource) it.reject("command:${key.command}:$source")
        }
        interaction()
        val editor = session()
        if (key.command != KeyCommand.CHARACTER && key.command != KeyCommand.SPACE && key.command != KeyCommand.SHIFT) {
            editor?.discardTouchTraining()
            if (key.command != KeyCommand.DELETE) editor?.clearWordContext()
        }
        var result: CharacterInputResult? = null
        val practice = practiceSession
        practice?.dispatching = true
        try {
            when (key.command) {
                KeyCommand.CHARACTER -> {
                    val uppercase = (heldShiftForKey ?: shiftHeld) || shift != ShiftState.OFF
                    if (key.letter && (contextualTouch || suggestWords)) editor?.prepareWordContext(korean)
                    val accepted = if (key.letter && korean && symbolsPage == 0) {
                        Dubeolsik.map(key.value.single(), uppercase)?.let { editor?.jamo(it) } == true
                    } else {
                        val output = if (key.letter && uppercase) key.value.uppercase() else key.value
                        editor?.text(output) == true
                    }
                    result = CharacterInputResult(source, accepted)
                    editor?.observeTouch(touch.takeIf { accepted && key.letter && source == InputSource.TOUCH_TAP })
                    if (accepted && key.letter && (contextualTouch || suggestWords)) {
                        editor?.recordLetter(wordKey(key.value.single(), uppercase),
                            alternatives.map { wordKey(it, uppercase) }.joinToString(""))
                    } else if (!key.letter) editor?.clearWordContext()
                    if (key.letter) {
                        if (heldShiftForKey == null && shiftHeld) heldShiftUsed = true
                        if (shift == ShiftState.ONCE) { shift = ShiftState.OFF; autoShift = false }
                    }
                    refreshAutoCaps()
                }
                KeyCommand.DELETE -> { editor?.backspace(); refreshAutoCaps() }
                KeyCommand.DELETE_FORWARD -> { editor?.deleteForward(); refreshAutoCaps() }
                KeyCommand.CURSOR_LEFT -> { editor?.moveCursor(-1); refreshAutoCaps() }
                KeyCommand.CURSOR_RIGHT -> { editor?.moveCursor(1); refreshAutoCaps() }
                KeyCommand.SPACE -> {
                    editor?.space(doubleSpacePeriod && !korean, now = eventTime ?: SystemClock.uptimeMillis(),
                        touchBoundary = source == InputSource.TOUCH_TAP,
                        contextLanguage = korean.takeIf { contextualTouch || suggestWords })
                    refreshAutoCaps()
                }
                KeyCommand.ENTER -> { editor?.enter(); refreshAutoCaps() }
                KeyCommand.SHIFT -> { tapShift(SystemClock.uptimeMillis()); return null }
                KeyCommand.LANGUAGE -> {
                    editor?.finishComposition(); korean = !korean; symbolsPage = 0; shift = ShiftState.OFF; autoShift = false
                    if (practice == null) languageChanged(korean) else { practice.inputKorean = korean; practice.changed() }
                    refreshAutoCaps()
                }
                KeyCommand.SYMBOLS -> { editor?.finishComposition(); symbolsPage = 1; shift = ShiftState.OFF }
                KeyCommand.MORE_SYMBOLS -> { symbolsPage = if (symbolsPage == 1) 2 else 1 }
                KeyCommand.LETTERS -> { symbolsPage = 0; refreshAutoCaps() }
                KeyCommand.SETTINGS -> { editor?.finishComposition(); settings() }
                KeyCommand.NEXT_IME -> { editor?.finishComposition(); nextIme() }
                KeyCommand.MODE -> modeChanged()
                KeyCommand.HIDE -> { editor?.finishComposition(); hide() }
                KeyCommand.CLIPBOARD -> { editor?.finishComposition(); clipboard() }
                KeyCommand.SPACER, KeyCommand.SUGGESTION -> return null
            }
        } finally { practice?.dispatching = false }
        changed()
        return result
    }

    fun refreshAutoCaps() {
        if (!autoCapitalization) {
            if (autoShift) { shift = ShiftState.OFF; autoShift = false }
            return
        }
        if (!korean && shift != ShiftState.LOCKED && !shiftHeld && (shift != ShiftState.ONCE || autoShift)) {
            shift = if (session()?.capsRequested() == true) ShiftState.ONCE else ShiftState.OFF
            autoShift = shift == ShiftState.ONCE
        }
    }

    fun configureInputOptions(autoCaps: Boolean, doubleSpace: Boolean) {
        autoCapitalization = autoCaps; doubleSpacePeriod = doubleSpace; refreshAutoCaps()
    }

    private fun preparePracticeCursor() {
        practiceSession?.let {
            if (it.word?.sentence != true) it.reject()
            it.dispatchEvent = -1; it.dispatchCommand = "CURSOR"
        }
    }

    fun beginCursorGesture() {
        preparePracticeCursor()
        if (shiftHeld) heldShiftUsed = true
        session()?.let {
            it.discardTouchTraining()
            it.clearWordContext()
            it.finishComposition()
        }
    }
    fun slideDelete(steps: Int, byWord: Boolean): Int {
        practiceSession?.reject()
        interaction()
        if (byWord && shiftHeld) heldShiftUsed = true
        val applied = session()?.let {
            it.discardTouchTraining()
            it.clearWordContext()
            it.slideDelete(steps, byWord)
        } ?: 0
        refreshAutoCaps(); changed()
        return applied
    }
    fun moveCursor(steps: Int) {
        preparePracticeCursor()
        interaction()
        val extendSelection = shiftHeld || (shift != ShiftState.OFF && !autoShift)
        if (shiftHeld && steps != 0) heldShiftUsed = true
        session()?.discardTouchTraining()
        session()?.clearWordContext()
        repeat(kotlin.math.abs(steps).coerceAtMost(8)) {
            session()?.moveCursor(if (steps < 0) -1 else 1, extendSelection)
        }
        refreshAutoCaps(); changed()
    }

    fun secondary(value: String, source: InputSource = InputSource.DIRECT): CharacterInputResult {
        interaction()
        session()?.discardTouchTraining()
        session()?.clearWordContext()
        val accepted = if (value.length == 1 && value.single() in io.github.minjj.keyboard.hangul.HangulSyllable.INITIALS + io.github.minjj.keyboard.hangul.HangulSyllable.VOWELS) {
            session()?.jamo(value.single())
        } else session()?.text(value)
        if (shiftHeld) heldShiftUsed = true
        if (shift == ShiftState.ONCE) { shift = ShiftState.OFF; autoShift = false }
        refreshAutoCaps(); changed()
        return CharacterInputResult(source, accepted == true)
    }
}
