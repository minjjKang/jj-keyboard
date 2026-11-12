package io.github.minjj.keyboard.settings

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import io.github.minjj.keyboard.R
import io.github.minjj.keyboard.ime.EditorProfile
import io.github.minjj.keyboard.ime.KeyCommand
import io.github.minjj.keyboard.ime.KeySpec
import io.github.minjj.keyboard.ime.KeyboardController
import io.github.minjj.keyboard.ui.KeyboardLayouts
import io.github.minjj.keyboard.ui.KeyboardView
import java.io.IOException
import kotlinx.coroutines.*

/** Each editor is a separate settings page; key assignments are shared across orientations. */
class CharacterSettingsActivity : Activity() {
    private val ui by lazy { SettingsUi(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repository: SettingsRepository
    private var current = KeyboardSettings()
    private var page = 0
    private var keyboardPage = 0
    private var korean = true
    private lateinit var keyboard: KeyboardView
    private lateinit var previewController: KeyboardController
    private var dialog: CharacterEditDialog? = null
    private var editingId: String? = null
    private var editingKey: KeySpec? = null
    private var saving = false
    private val bindings = mutableListOf<() -> Unit>()
    private val secondary get() = page == R.string.secondary_editor_title

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = SettingsRepository(this)
        page = intent.getIntExtra(PAGE, R.string.characters_title)
            .takeIf { it == R.string.secondary_editor_title || it == R.string.symbol_editor_title }
            ?: R.string.characters_title
        keyboardPage = savedInstanceState?.getInt(KEYBOARD_PAGE) ?: if (secondary) 0 else 2
        korean = savedInstanceState?.getBoolean(KOREAN, keyboardPage != 1) ?: (keyboardPage != 1)
        val body = ui.column(top = 0)
        if (page == R.string.characters_title) buildMenu(body) else buildEditor(body)
        setContentView(if (page == R.string.characters_title) ui.scrollPage(body, page) { finish() } else editorPage(body))
        var restore = savedInstanceState
        scope.launch {
            repository.settings.collect {
                current = it
                bindings.forEach { bind -> bind() }
                if (::keyboard.isInitialized) {
                    renderKeys()
                    restore?.getString(EDITING_ID)?.let { id ->
                        val savedKey = restore?.getBundle(EDITING_KEY)?.let { saved ->
                            KeySpec(saved.getString("label").orEmpty(), KeyCommand.CHARACTER,
                                saved.getString("value").orEmpty(), letter = saved.getBoolean("letter"),
                                symbolSlot = saved.getString("symbolSlot"))
                        }
                        (savedKey ?: editableKeys().firstOrNull { keyId(it) == id })?.let { key ->
                            editKey(key, restore?.getString(DRAFT))
                        }
                    }
                }
                restore = null
            }
        }
    }

    private fun buildMenu(body: LinearLayout) {
        val toggle = ui.toggle(ui.card(body), R.string.secondary_characters, R.string.secondary_description).apply {
            isEnabled = false
            setOnClickListener {
                val value = isChecked
                persist { repository.setOption("secondary", value) }
            }
        }
        bindings += { toggle.isChecked = current.secondaryCharacters; toggle.isEnabled = true }
        val card = ui.card(body)
        for (target in listOf(R.string.secondary_editor_title, R.string.symbol_editor_title)) {
            ui.actionRow(card, target) {
                startActivity(Intent(this, CharacterSettingsActivity::class.java).putExtra(PAGE, target))
            }
        }
    }

    private fun buildEditor(body: LinearLayout) {
        body.addView(ui.text(getString(if (secondary) R.string.secondary_editor_help else R.string.symbol_editor_help),
            14f, ui.secondary).apply {
            setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
            setLineSpacing(ui.dp(4).toFloat(), 1f)
        }, LinearLayout.LayoutParams(-1, -2))
        previewController = KeyboardController({ null }, {}, {}, {}, {
            if (::keyboard.isInitialized) {
                korean = previewController.korean
                keyboardPage = if (previewController.symbolsPage > 0) previewController.symbolsPage + 1 else if (korean) 0 else 1
                keyboard.refresh()
            }
        })
        previewController.subtype(korean)
        if (keyboardPage >= 2) {
            previewController.press(KeySpec("", KeyCommand.SYMBOLS))
            if (keyboardPage == 3) previewController.press(KeySpec("", KeyCommand.MORE_SYMBOLS))
        }
        keyboard = KeyboardView(ContextThemeWrapper(this, R.style.AppTheme), previewController).apply {
            // The page owns system-bar insets; keep the keyboard's own key padding.
            setOnApplyWindowInsetsListener { _, insets -> insets }
        }
    }

    private fun editorPage(body: LinearLayout) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.color.settings_background)
        addView(ui.fixedBar(ui.column(bottom = 0).also { ui.header(it, page) { finish() } }), LinearLayout.LayoutParams(-1, -2))
        addView(ScrollView(this@CharacterSettingsActivity).apply { addView(ui.centered(body)) }, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(FrameLayout(this@CharacterSettingsActivity).apply {
            addView(keyboard, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        }, LinearLayout.LayoutParams(-1, -2))
        setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            val bars = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                else android.graphics.Insets.of(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= 30) WindowInsets.CONSUMED else insets.consumeSystemWindowInsets()
        }
    }

    private fun editableKeys(): List<KeySpec> {
        return KeyboardLayouts.rows(previewController, EditorProfile(), getString(R.string.action_return),
            extended = keyboard.extendedActive, numberRow = current.numberRow, settings = current)
            .flatten().filter { it.command == KeyCommand.CHARACTER && (secondary || it.symbolSlot != null) }.distinctBy(::keyId)
    }

    private fun keyId(key: KeySpec): String = if (secondary) {
        requireNotNull(KeyboardLayouts.secondaryId(key, korean))
    } else requireNotNull(key.symbolSlot)

    private fun renderKeys() {
        keyboard.bindEditor(current, symbolsOnly = !secondary, ::editKeyFromKeyboard)
    }

    private fun editKeyFromKeyboard(key: KeySpec) = editKey(key)

    private fun editKey(key: KeySpec, draft: String? = null) {
        if (dialog != null) return
        val id = keyId(key)
        val initial = draft ?: if (secondary) KeyboardLayouts.secondary(key, korean, current).orEmpty() else key.value
        lateinit var prompt: CharacterEditDialog
        prompt = CharacterEditDialog(this, key.label, secondary, initial) { value ->
            if (!saving) {
                if (value != null && !CharacterCustomization.validValue(value, secondary)) {
                    prompt.showError(getString(R.string.character_invalid))
                } else {
                    saving = true
                    prompt.setSaving(true)
                    scope.launch {
                        try {
                            withContext(NonCancellable) { repository.setCharacter(secondary, id, value) }
                            prompt.dismiss()
                        } catch (_: IOException) {
                            if (!isDestroyed) prompt.showError(getString(R.string.layout_save_failed))
                        } finally {
                            saving = false
                            prompt.setSaving(false)
                        }
                    }
                }
            }
        }
        dialog = prompt
        editingId = id
        editingKey = key
        prompt.setOnDismissListener { dialog = null; editingId = null; editingKey = null }
        prompt.show()
    }

    private fun persist(write: suspend () -> Unit) {
        scope.launch {
            try { withContext(NonCancellable) { write() } }
            catch (_: IOException) {
                if (!isDestroyed) Toast.makeText(this@CharacterSettingsActivity, R.string.layout_save_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEYBOARD_PAGE, keyboardPage)
        outState.putBoolean(KOREAN, korean)
        editingKey?.let { key ->
            outState.putBundle(EDITING_KEY, Bundle().apply {
                putString("label", key.label); putString("value", key.value)
                putBoolean("letter", key.letter); putString("symbolSlot", key.symbolSlot)
            })
        }
        editingId?.let { outState.putString(EDITING_ID, it); outState.putString(DRAFT, dialog?.input?.text.toString()) }
    }

    override fun onDestroy() {
        dialog?.dismiss()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val PAGE = "characterPage"
        private const val KEYBOARD_PAGE = "keyboardPage"
        private const val KOREAN = "korean"
        private const val EDITING_KEY = "editingKey"
        private const val EDITING_ID = "editingId"
        private const val DRAFT = "characterDraft"
    }
}
