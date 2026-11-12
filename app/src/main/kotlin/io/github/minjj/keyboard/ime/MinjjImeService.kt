package io.github.minjj.keyboard.ime

import android.content.Intent
import android.content.ClipboardManager
import android.content.ClipDescription
import android.content.res.Configuration
import android.os.Build
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.Toast
import android.view.inputmethod.InputContentInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import io.github.minjj.keyboard.clipboard.ClipboardHistory
import io.github.minjj.keyboard.clipboard.ClipEntry
import io.github.minjj.keyboard.ui.ClipboardPanel
import io.github.minjj.keyboard.ui.KeyboardFrame
import android.view.Gravity
import io.github.minjj.keyboard.R
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputMethodSubtype
import io.github.minjj.keyboard.settings.KeyboardSettings
import io.github.minjj.keyboard.settings.SettingsActivity
import io.github.minjj.keyboard.settings.SettingsRepository
import io.github.minjj.keyboard.ui.KeyboardView
import io.github.minjj.keyboard.ui.TouchDiagnosticObserver
import io.github.minjj.keyboard.correction.SpatialStatsRepository
import io.github.minjj.keyboard.correction.PersonalizationSessionBridge
import io.github.minjj.keyboard.correction.loadLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MinjjImeService : InputMethodService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: EditorSession? = null
    private var keyboard: KeyboardView? = null
    private var settings = KeyboardSettings()
    private var profile = EditorProfile()
    private var changingConfiguration = false
    private var inputRoot: FrameLayout? = null
    private var clipboardPanel: ClipboardPanel? = null
    private data class TypingDiagnostic(val marker: String, val observer: TouchDiagnosticObserver)
    private var typingDiagnostic: TypingDiagnostic? = null
    private var practice: PersonalizationSessionBridge.Session? = null
    private var practicePreviousLanguage: Boolean? = null
    private fun inputSettings(): KeyboardSettings = if (practice == null) settings else settings.copy(
        touchLearning = false, contextualTouch = true, wordSuggestions = false,
        autoCapitalization = false, doubleSpacePeriod = false)

    private fun endPractice() {
        val previous = practice ?: return
        previous.connected = false
        if (previous.taps.isNotEmpty()) previous.reject("connection-ended")
        previous.taps.clear(); previous.editTrace.clear(); previous.pendingMutations.clear(); previous.pendingEvents.clear()
        previous.prediction(null, false)
        previous.finishComposition = null; previous.selectPage = null
        practice = null; controller.practiceSession = null
        controller.configureInputOptions(settings.autoCapitalization, settings.doubleSpacePeriod)
        controller.configureLanguageOptions(settings.contextualTouch, settings.wordSuggestions)
        practicePreviousLanguage?.let { controller.subtype(it) }; practicePreviousLanguage = null
    }

    internal fun startTypingDiagnostic(marker: String, observer: TouchDiagnosticObserver): Boolean {
        val info = currentInputEditorInfo ?: return false
        if (info.packageName != packageName || info.privateImeOptions != marker) return false
        typingDiagnostic = TypingDiagnostic(marker, observer)
        keyboard?.touchDiagnostics = observer
        return true
    }

    internal fun stopTypingDiagnostic(observer: TouchDiagnosticObserver): Boolean {
        if (typingDiagnostic?.observer !== observer) return false
        typingDiagnostic = null
        keyboard?.touchDiagnostics = null
        return true
    }
    private data class RotationRecovery(val identity: List<Any?>, val expires: Long, val checkpoint: EditorSession.CompositionCheckpoint)
    private var rotationRecovery: RotationRecovery? = null
    private fun identity(info: EditorInfo): List<Any?>? {
        if (info.fieldId <= 0 && info.privateImeOptions.isNullOrEmpty()) return null
        return listOf(info.packageName, info.fieldId, info.fieldName, info.privateImeOptions, info.inputType)
    }
    private val history by lazy { ClipboardHistory.get(this) }
    private val spatial get() = SpatialStatsRepository.get(this)
    private val requestedLanguages = mutableSetOf<Boolean>()
    private var settingsReady = false
    private val clipboardListener = ClipboardManager.OnPrimaryClipChangedListener { captureClipboard() }
    private val controller = KeyboardController(
        session = { session }, languageChanged = ::switchLanguage,
        settings = {
            startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            requestHideSelf(0)
        },
        nextIme = { getSystemService(InputMethodManager::class.java).showInputMethodPicker() },
        changed = { renderKeyboard() },
        modeChanged = {
            val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val next = if (keyboard?.splitActive == true) 0 else 1
            scope.launch { SettingsRepository(this@MinjjImeService).setOrientationMode(landscape, next) }
        },
        hide = { requestHideSelf(0) },
        clipboard = ::showClipboard,
        interaction = { rotationRecovery = null },
    )

    override fun onCreate() {
        super.onCreate()
        getSystemService(ClipboardManager::class.java).addPrimaryClipChangedListener(clipboardListener)
        scope.launch { runCatching { history.refresh() } }
        scope.launch { history.entries.collect { clipboardPanel?.render(it) } }
        scope.launch { SettingsRepository(this@MinjjImeService).settings.collect {
            if (settings.touchLearning != it.touchLearning || settings.touchCorrection != it.touchCorrection) {
                session?.discardTouchTraining()
            }
            settings = it
            settingsReady = true
            val effective = inputSettings()
            controller.configureInputOptions(effective.autoCapitalization, effective.doubleSpacePeriod)
            controller.configureLanguageOptions(effective.contextualTouch, effective.wordSuggestions)
            renderKeyboard()
        } }
    }

    override fun onCreateInputView(): View {
        keyboard?.cancelInteractions(false)
        clipboardPanel = null
        return KeyboardFrame(this).also { root ->
            inputRoot = root
            root.addView(KeyboardView(this, controller).also {
                keyboard = it
                it.spatialStats = spatial
                it.bind(profile, settings)
            },
                FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        }
    }

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        endPractice()
        if (attribute.packageName != packageName || attribute.privateImeOptions != typingDiagnostic?.marker) {
            typingDiagnostic = null
        }
        keyboard?.touchDiagnostics = typingDiagnostic?.observer
        closeClipboard()
        if (changingConfiguration && restarting && session?.owns(currentInputConnection) == true) {
            keyboard?.bind(profile, settings)
            return
        }
        // Never commit through a previous connection during a restart or editor change.
        session?.invalidate()
        profile = EditorProfile.from(attribute)
        val recovery = rotationRecovery?.takeIf { restarting && it.identity == identity(attribute) && android.os.SystemClock.uptimeMillis() <= it.expires }
        session = currentInputConnection?.let { connection ->
            val observedConnection = object : InputConnectionWrapper(connection, false) {
                private fun permit(kind: String) {
                    practice?.let { if (it.pendingMutations.size < 512) { it.pendingMutations.addLast(kind); it.pendingEvents.addLast(PersonalizationSessionBridge.Session.Mutation(it.trial, it.dispatchEvent, it.dispatchCommand)) } else it.reject() }
                }
                override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    permit("commit"); return super.commitText(text, newCursorPosition)
                }
                override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                    permit("compose"); return super.setComposingText(text, newCursorPosition)
                }
                override fun setSelection(start: Int, end: Int): Boolean {
                    permit("selection"); return super.setSelection(start, end)
                }
                override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                    permit("delete"); return super.deleteSurroundingText(beforeLength, afterLength)
                }
            }
            EditorSession(observedConnection, profile) { layout, generation, samples ->
                if (practice == null && settings.touchLearning) spatial.record(layout, generation, samples)
            }.apply {
                ownerConnection = connection
                val before = if (Build.VERSION.SDK_INT >= 30 && profile.allowsLanguageModel &&
                    (settings.contextualTouch || settings.wordSuggestions) &&
                    attribute.initialSelStart >= 0 && attribute.initialSelStart == attribute.initialSelEnd
                ) attribute.getInitialTextBeforeCursor(128, 0) else null
                startCursor(minOf(attribute.initialSelStart, attribute.initialSelEnd), before)
            }
        }
        val restored = recovery != null && session?.restore(recovery.checkpoint) == true
        if (!changingConfiguration) rotationRecovery = null
        keyboard?.cancelInteractions(false)
        controller.reset()
        getSystemService(InputMethodManager::class.java).currentInputMethodSubtype?.let {
            controller.subtype(it.languageTag.startsWith("ko", true) || it.locale.startsWith("ko"))
        }
        // A restarting editor may still own a composing span; finish it without restoring old state.
        if (restarting && !restored) session?.finishComposition()
        PersonalizationSessionBridge.resolve(attribute, packageName)?.takeIf { profile.allowsSpatialLearning }?.let {
            practicePreviousLanguage = controller.korean
            practice = it; controller.practiceSession = it; it.connected = true
            it.finishComposition = { session?.finishComposition() }
            it.selectPage = controller::practicePage
            controller.configureInputOptions(false, false)
            controller.configureLanguageOptions(true, false)
            controller.subtype(it.inputKorean)
            controller.practicePage(it.word?.page ?: 0)
        }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        keyboard?.spatialStats = spatial
        keyboard?.touchDiagnostics = typingDiagnostic?.takeIf {
            info.packageName == packageName && info.privateImeOptions == it.marker
        }?.observer
        keyboard?.bind(profile, inputSettings())
        captureClipboard()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        session?.selectionChanged(newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        controller.refreshAutoCaps()
        renderKeyboard()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        practice?.takeIf { it.taps.isNotEmpty() }?.reject("input-view-ended")
        keyboard?.touchDiagnostics = null
        closeClipboard(); session?.discardDeleteUndo()
        session?.discardTouchTraining()
        session?.clearWordContext()
        keyboard?.cancelInteractions()
        // The base callback finishes the composing span. During UI-only configuration work,
        // keep both the span and composer until onStartInput confirms connection identity.
        if (changingConfiguration) return
        rotationRecovery = null
        session?.finishComposition()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        endPractice()
        keyboard?.touchDiagnostics = null
        keyboard?.cancelInteractions(false)
        if (changingConfiguration) return
        rotationRecovery = null
        session?.close(); session = null
        super.onFinishInput()
    }

    override fun onCurrentInputMethodSubtypeChanged(newSubtype: InputMethodSubtype?) {
        super.onCurrentInputMethodSubtypeChanged(newSubtype)
        session?.discardTouchTraining()
        if (newSubtype != null) controller.subtype(newSubtype.languageTag.startsWith("ko", true) || newSubtype.locale.startsWith("ko"))
    }

    private fun switchLanguage(korean: Boolean) {
        val manager = getSystemService(InputMethodManager::class.java)
        val own = manager.inputMethodList.firstOrNull { it.packageName == packageName } ?: return
        val desired = (0 until own.subtypeCount).map(own::getSubtypeAt).firstOrNull {
            it.languageTag.startsWith(if (korean) "ko" else "en", true)
        } ?: return
        switchInputMethod(own.id, desired)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        practice?.reject("configuration")
        session?.discardTouchTraining()
        session?.clearWordContext()
        rotationRecovery = currentInputEditorInfo?.let { info -> identity(info)?.let { id ->
            session?.checkpoint()?.let { RotationRecovery(id, android.os.SystemClock.uptimeMillis() + 1500, it) }
        } }
        closeClipboard()
        keyboard?.cancelInteractions()
        changingConfiguration = true
        try { super.onConfigurationChanged(newConfig) } finally { changingConfiguration = false }
        keyboard?.bind(profile, inputSettings())
    }

    override fun onEvaluateFullscreenMode(): Boolean = false
    private fun captureClipboard() {
        if (!settingsReady || !settings.clipboardHistory || profile.password) return
        val clip = runCatching { getSystemService(ClipboardManager::class.java).primaryClip }.getOrNull() ?: return
        scope.launch { runCatching { history.capture(clip) } }
    }

    private fun showClipboard() {
        if (profile.password) { Toast.makeText(this, R.string.clipboard_unavailable, Toast.LENGTH_SHORT).show(); return }
        val keyboard = keyboard ?: return
        val root = inputRoot ?: return
        if (clipboardPanel != null) { closeClipboard(); return }
        captureClipboard(); keyboard.cancelInteractions()
        val dark = settings.highContrast || resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        clipboardPanel = ClipboardPanel(this, history, dark, keyboard.surfaceColor,
            if (root.width / resources.displayMetrics.density >= 840) 8 else 4,
            ::closeClipboard, ::pasteClip,
            { ids, pinned -> scope.launch { runCatching { history.setPinned(ids, pinned) } } },
            { ids -> scope.launch { runCatching { history.delete(ids) } } }).also {
            it.render(history.entries.value)
            root.addView(it, FrameLayout.LayoutParams(-1, -1, Gravity.BOTTOM))
            it.requestApplyInsets()
        }
        keyboard.visibility = View.GONE
        scope.launch { runCatching { history.refresh() } }
    }

    private fun closeClipboard() {
        clipboardPanel?.let { inputRoot?.removeView(it) }; clipboardPanel = null
        keyboard?.visibility = View.VISIBLE
    }

    private fun renderKeyboard() {
        val korean = controller.korean
        if (profile.allowsLanguageModel && (practice != null || settings.contextualTouch || settings.wordSuggestions) &&
            requestedLanguages.add(korean)
        ) {
            scope.launch {
                loadLanguage(this@MinjjImeService, korean)?.let { controller.loadLanguage(korean, it) }
            }
        }
        keyboard?.bind(profile, inputSettings())
        practice?.let { active ->
            val evidence = controller.contextualEvidence()
            active.prediction(evidence?.let { FloatArray(26) { key -> it(key) } }, settings.touchCorrection)
        }
    }

    private fun pasteClip(entry: ClipEntry) {
        if (profile.password || session == null) return
        session?.discardTouchTraining()
        session?.clearWordContext()
        if (entry.image) {
            val accepted = currentInputEditorInfo?.contentMimeTypes?.any { ClipDescription.compareMimeTypes(entry.mime, it) } == true
            val success = accepted && runCatching {
                session?.finishComposition()
                currentInputConnection?.commitContent(InputContentInfo(history.imageUri(entry.id), ClipDescription("clipboard image", arrayOf(entry.mime)), null),
                    InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null) == true
            }.getOrDefault(false)
            if (!success) { Toast.makeText(this, R.string.clipboard_image_unsupported, Toast.LENGTH_SHORT).show(); return }
        } else session?.text(entry.text.orEmpty())
        controller.refreshAutoCaps(); closeClipboard(); keyboard?.bind(profile, settings)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        practice?.reject("hardware-event")
        session?.discardTouchTraining()
        session?.clearWordContext()
        if (keyCode == KeyEvent.KEYCODE_BACK && clipboardPanel != null) {
            if (clipboardPanel?.cancelSelection() != true) closeClipboard()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
    override fun onDestroy() {
        getSystemService(ClipboardManager::class.java).removePrimaryClipChangedListener(clipboardListener)
        keyboard?.cancelInteractions(false); session?.invalidate(); session = null
        scope.cancel(); super.onDestroy()
    }
}
