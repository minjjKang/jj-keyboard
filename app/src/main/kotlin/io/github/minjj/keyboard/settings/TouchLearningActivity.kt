package io.github.minjj.keyboard.settings

import android.app.Activity
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.View
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.*
import android.widget.*
import io.github.minjj.keyboard.R
import io.github.minjj.keyboard.correction.*
import io.github.minjj.keyboard.hangul.Dubeolsik
import io.github.minjj.keyboard.touch.*
import io.github.minjj.keyboard.ui.TouchLearningMapView
import kotlinx.coroutines.*

/** Product learning screen. Input and per-tap coordinates live only for its active word. */
class TouchLearningActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ui by lazy { SettingsUi(this) }
    private val stats by lazy { SpatialStatsRepository.get(this) }
    private var korean = true
    private var session: PersonalizationSessionBridge.Session? = null
    internal val learningSession get() = session
    private var imeAnimating = false
    private var lastImeTransition = SystemClock.uptimeMillis()
    internal val readyForTyping get(): Boolean {
        if (session?.word?.page != session?.layout?.page) return false
        if (imeAnimating || SystemClock.uptimeMillis() - lastImeTransition < 150) return false
        return if (Build.VERSION.SDK_INT >= 30) window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
        else editor.hasFocus()
    }
    private var selector: PracticeSentences? = null
    private var selectedId: Int? = null
    private var currentId: Int? = null
    private var currentLayout: LayoutDescriptor? = null
    private var spinnerIds: List<Int?> = emptyList()
    private var updatingSpinner = false
    private var updatingLanguage = false
    private var resetting = false
    private var emptyCompletions = 0
    private var initialLearned = 0
    private var shownPrediction = -1L
    private var preview = true
    private var distribution = 0
    private var recent = emptyList<PracticeTap>()
    private var settings = KeyboardSettings()
    private lateinit var profiles: Spinner
    private lateinit var status: TextView
    private lateinit var prompt: TextView
    private lateinit var detail: TextView
    private lateinit var saving: TextView
    private lateinit var start: Button
    private lateinit var controlsHint: TextView
    private lateinit var map: TouchLearningMapView
    private lateinit var editor: EditText
    private lateinit var geometryLabel: TextView
    private lateinit var tabs: RadioGroup
    private lateinit var setup: View
    private lateinit var legend: TextView
    private lateinit var practiceCard: LinearLayout
    private lateinit var mapCard: LinearLayout
    private val manager get() = getSystemService(InputMethodManager::class.java)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 30) window.decorView.setWindowInsetsAnimationCallback(object : WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
            private val active = mutableSetOf<WindowInsetsAnimation>()
            override fun onPrepare(animation: WindowInsetsAnimation) {
                if (animation.typeMask and WindowInsets.Type.ime() != 0) {
                    active.add(animation); imeAnimating = true; lastImeTransition = SystemClock.uptimeMillis()
                }
            }
            override fun onProgress(insets: WindowInsets, running: MutableList<WindowInsetsAnimation>) = insets
            override fun onEnd(animation: WindowInsetsAnimation) {
                if (active.remove(animation)) { imeAnimating = active.isNotEmpty(); lastImeTransition = SystemClock.uptimeMillis() }
            }
        })
        korean = savedInstanceState?.getBoolean("korean") ?: true
        val body = ui.column(top = 0, bottom = 8).apply { isFocusableInTouchMode = true }
        setup = ui.centered(body)
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val workspace = LinearLayout(this).apply {
            orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            setPadding(ui.dp(12), 0, ui.dp(12), ui.dp(8))
            id = R.id.learning_workspace
        }
        tabs = ui.radioCard(body).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(R.string.learning_tab_ko, R.string.learning_tab_en).forEachIndexed { i, title ->
            ui.radio(tabs, getString(title)).apply {
                id = if (i == 0) R.id.learning_tab_ko else R.id.learning_tab_en
                isChecked = korean == (i == 0)
                layoutParams = RadioGroup.LayoutParams(0, ui.dp(48), 1f)
            }
        }
        tabs.setOnCheckedChangeListener { _, id ->
            if (updatingLanguage) return@setOnCheckedChangeListener
            stop(false); korean = id == R.id.learning_tab_ko; currentLayout = null; currentId = null
            selectedId = null; selector = null; updateStart(); refreshProfiles(); loadWords()
        }
        val profileCard = panel(body)
        profileCard.addView(ui.text(getString(R.string.learning_profile_title), 14f, ui.secondary).apply {
            labelFor = R.id.learning_profiles
        })
        profiles = Spinner(this).apply { id = R.id.learning_profiles; minimumHeight = ui.dp(48) }
        profileCard.addView(profiles, LinearLayout.LayoutParams(-1, -2))
        profiles.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (updatingSpinner) return
                val nextId = spinnerIds.getOrNull(position)
                if (nextId == selectedId) return
                selectedId = nextId
                refreshMap()
                status.setText(if (selectedId != null && selectedId != currentId) R.string.learning_other_layout else R.string.learning_ready)
                updateStart()
            }
        }
        practiceCard = panel(workspace)
        status = ui.text(getString(R.string.learning_loading), 14f).apply {
            id = R.id.learning_status; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }
        practiceCard.addView(status)
        saving = ui.text("", 12f, ui.secondary).apply { setPadding(0, ui.dp(4), 0, ui.dp(8)) }; practiceCard.addView(saving)
        prompt = ui.text("", 17f).apply {
            id = R.id.learning_prompt; visibility = View.GONE
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 3
            setPadding(0, ui.dp(6), 0, ui.dp(4))
        }
        practiceCard.addView(prompt)
        editor = object : EditText(this) {
            override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
                val target = super.onCreateInputConnection(outAttrs) ?: return null
                outAttrs.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                return object : InputConnectionWrapper(target, false) {
                    private fun mutation(kind: String, deleteBefore: Int = 0, deleteAfter: Int = 0, action: () -> Boolean): Boolean {
                        val active = session
                        val authorized = active?.pendingMutations?.firstOrNull() == kind
                        val event = if (authorized) {
                            active?.pendingMutations?.removeFirst()
                            active?.pendingEvents?.removeFirstOrNull()
                        } else {
                            if (kind != "selection" || active?.taps?.isNotEmpty() == true) active?.reject("unexpected:$kind")
                            null
                        }
                        val before = editor.text.toString()
                        val selectionStart = minOf(editor.selectionStart, editor.selectionEnd).coerceIn(0, before.length)
                        val selectionEnd = maxOf(editor.selectionStart, editor.selectionEnd).coerceIn(0, before.length)
                        val bounded = kind == "delete" || ((kind == "commit" || kind == "compose") && BaseInputConnection.getComposingSpanStart(editor.text) < 0)
                        val start = if (bounded) (selectionStart - deleteBefore).coerceAtLeast(0) else null
                        val end = if (bounded) (selectionEnd + deleteAfter).coerceAtMost(before.length) else null
                        active?.applyingMutation = true
                        return try { action().also { active?.mutationFinished(event, before, editor.text.toString(), start, end) } }
                        finally { active?.applyingMutation = false }
                    }
                    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean = mutation("commit") { super.commitText(text, newCursorPosition) }
                    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean = mutation("compose") { super.setComposingText(text, newCursorPosition) }
                    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean = mutation("delete", beforeLength, afterLength) { super.deleteSurroundingText(beforeLength, afterLength) }
                    override fun setSelection(start: Int, end: Int): Boolean = mutation("selection") { super.setSelection(start, end) }
                    override fun sendKeyEvent(event: KeyEvent): Boolean { session?.reject(); return super.sendKeyEvent(event) }
                }
            }
            @android.annotation.SuppressLint("ClickableViewAccessibility") // Native EditText handles click and selection accessibility.
            override fun onTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_DOWN && text.isNotEmpty()) session?.reject()
                return super.onTouchEvent(event)
            }
            override fun performClick(): Boolean = super.performClick()
        }.apply {
            id = R.id.learning_editor; inputType = InputType.TYPE_CLASS_TEXT
            textSize = 18f; setTextColor(ui.ink); backgroundTintList = ColorStateList.valueOf(ui.accent)
            minimumHeight = ui.dp(48)
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            isSaveEnabled = false; importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            maxLines = 2; setSingleLine(false); setHorizontallyScrolling(false); visibility = View.GONE
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (!resetting) {
                        val resetSkip = emptyCompletions != 0 && !s.isNullOrEmpty()
                        if (!s.isNullOrEmpty()) emptyCompletions = 0
                        if (session?.applyingMutation != true) session?.reject("external-edit")
                        if (resetSkip || session?.invalid == true) editor.post { connectedChanged() }
                    }
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_DONE) { complete(); true } else false }
        }
        practiceCard.addView(editor)
        start = button(practiceCard, R.string.learning_start) { begin() }.apply { id = R.id.learning_start }
        controlsHint = ui.text(getString(R.string.learning_controls_hint), 12f, ui.secondary).apply {
            id = R.id.learning_controls_hint; visibility = View.GONE
            setPadding(0, ui.dp(4), 0, ui.dp(4))
        }
        practiceCard.addView(controlsHint)
        mapCard = panel(workspace)
        mapCard.addView(ui.text(getString(R.string.learning_personal_map)).apply {
            setTypeface(typeface, Typeface.BOLD); isAccessibilityHeading = true
            setPadding(0, 0, 0, ui.dp(8))
        })
        val mapOptions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        mapCard.addView(mapOptions)
        fun choice(idValue: Int, values: List<String>, selected: Int, action: (Int) -> Unit) {
            val spinner = Spinner(this).apply {
                id = idValue; minimumHeight = ui.dp(48)
                adapter = ArrayAdapter(this@TouchLearningActivity, android.R.layout.simple_spinner_dropdown_item, values)
                setSelection(selected)
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = action(position)
                }
            }
            mapOptions.addView(spinner, LinearLayout.LayoutParams(0, ui.dp(48), 1f))
        }
        choice(R.id.learning_map_mode, listOf(getString(R.string.learning_preview), getString(R.string.learning_actual)), 0) {
            preview = it == 0; refreshMap()
        }
        choice(R.id.learning_distribution, listOf(getString(R.string.learning_density), getString(R.string.learning_half_points), getString(R.string.learning_no_points)), 0) {
            distribution = it
            if (::map.isInitialized) map.distributionMode = it
            updateLegend()
        }
        geometryLabel = ui.text("", 12f, ui.secondary).apply {
            id = R.id.learning_region_status; setPadding(0, 0, 0, ui.dp(4)); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }
        mapCard.addView(geometryLabel)
        map = TouchLearningMapView(this).apply { id = R.id.learning_map; keySelected = ::showKey; regionsChanged = ::updateMapCaption }
        mapCard.addView(map, LinearLayout.LayoutParams(-1, 0, 1f))
        legend = ui.text(getString(R.string.learning_legend), 11f, ui.secondary).apply { setPadding(0, ui.dp(4), 0, 0) }
        mapCard.addView(legend)
        detail = ui.text("", 12f).apply { id = R.id.learning_key_detail; visibility = View.GONE; maxLines = 2 }
        mapCard.addView(detail)
        if (landscape) {
            practiceCard.layoutParams = LinearLayout.LayoutParams(0, -1, .42f)
            mapCard.layoutParams = LinearLayout.LayoutParams(0, -1, .58f).apply { leftMargin = ui.dp(8) }
        } else {
            practiceCard.layoutParams = LinearLayout.LayoutParams(-1, -2)
            mapCard.layoutParams = LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = ui.dp(8) }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundResource(R.color.settings_background)
            val heading = ui.column(top = 0, bottom = 0).also { ui.header(it, R.string.guided_training_title) { finish() } }
            addView(ui.centered(heading), LinearLayout.LayoutParams(-1, -2))
            addView(setup, LinearLayout.LayoutParams(-1, -2))
            addView(workspace, LinearLayout.LayoutParams(-1, 0, 1f))
            setOnApplyWindowInsetsListener { _, insets ->
                @Suppress("DEPRECATION")
                val bars = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars() or
                    WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()) else android.graphics.Insets.of(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        setContentView(root)
        scope.launch {
            SettingsRepository(this@TouchLearningActivity).settings.collect {
                settings = it; refreshMap()
            }
        }
        scope.launch {
            stats.state.collect { state ->
                saving.text = getString(when {
                    !state.ready -> R.string.learning_loading
                    state.failed -> R.string.learning_failed
                    state.saving -> R.string.learning_saving
                    else -> R.string.learning_saved
                })
                refreshProfiles(); updateStart(); refreshMap()
                if (state.ready && session == null && status.text == getString(R.string.learning_loading)) status.setText(R.string.learning_ready)
            }
        }
        loadWords()
    }

    private fun panel(parent: LinearLayout) = ui.card(parent).apply {
        showDividers = LinearLayout.SHOW_DIVIDER_NONE
        setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12))
    }

    private fun updatePresentation(active: Boolean) {
        setup.visibility = if (active) View.GONE else View.VISIBLE
        saving.visibility = if (active) View.GONE else View.VISIBLE
        updateLegend()
        detail.visibility = View.GONE
        practiceCard.setPadding(ui.dp(12), ui.dp(if (active) 4 else 12), ui.dp(12), ui.dp(if (active) 4 else 12))
        mapCard.setPadding(ui.dp(12), ui.dp(if (active) 6 else 12), ui.dp(12), ui.dp(if (active) 6 else 12))
    }

    private fun button(parent: LinearLayout, title: Int, action: () -> Unit): Button =
        Button(this, null, android.R.attr.borderlessButtonStyle).apply {
            setText(title); isAllCaps = false; minimumHeight = ui.dp(48); minWidth = 0; minimumWidth = 0
            setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()), intArrayOf(ui.accent, ui.secondary)))
            setOnClickListener { action() }
            parent.addView(this, LinearLayout.LayoutParams(-1, -2))
        }

    private fun loadWords() {
        selector = PracticeSentences(korean)
        updateStart()
    }

    private fun updateStart() {
        start.isEnabled = stats.ready && selector != null && (selectedId == null || selectedId == currentId)
    }

    private fun refreshProfiles() {
        val values = stats.model.layouts.filter { it.lastUsed >= 0 && (it.layout?.let { l -> l.page == 0 && l.inputKind == 0 } != false) && (it.layout?.korean ?: (it.id and 32 != 0)) == korean }.sortedByDescending { it.lastUsed }
        spinnerIds = listOf(null) + values.map { it.id }
        val labels = listOf(getString(R.string.learning_current_layout)) + values.map { p ->
            p.layout?.let { label(it) } ?: getString(R.string.learning_legacy) + " · " +
                getString(if (p.id and 4 != 0) R.string.learning_landscape else R.string.learning_portrait)
        }
        updatingSpinner = true
        profiles.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        profiles.setSelection(spinnerIds.indexOf(selectedId).coerceAtLeast(0))
        updatingSpinner = false
    }

    private fun label(l: LayoutDescriptor): String = getString(R.string.learning_profile_label,
        getString(if (l.korean) R.string.learning_tab_ko else R.string.learning_tab_en),
        getString(if (l.landscape) R.string.learning_landscape else R.string.learning_portrait),
        getString(if (l.split) R.string.learning_split else R.string.learning_regular), keySpan(l), l.height, l.horizontalGap, l.verticalGap)

    private fun keySpan(l: LayoutDescriptor): Float {
        val keys = l.surfaces.ifEmpty { l.keys }
        return keys.maxOf { it.right } - keys.minOf { it.left }
    }

    private fun selectedLayout(): LayoutDescriptor? = if (selectedId == null) currentLayout else
        stats.model.layouts.firstOrNull { it.id == selectedId }?.layout
    private fun refreshMap() {
        if (!::map.isInitialized) return
        val l = selectedLayout(); val id = selectedId ?: currentId ?: -1
        val live = session?.takeIf { it.connected && it.profile == id }
        val frozen = live?.takeIf { it.generation == stats.generation }?.model ?: stats.model
        map.show(l, frozen, id, preview || (live?.spatialEnabled ?: settings.touchCorrection),
            live?.taps?.filter { it.profile < 0 || it.profile == id } ?: if (selectedId == null) recent.filter { it.profile < 0 || it.profile == id } else emptyList(), live?.evidence)
        updateMapCaption()
        map.contentDescription = getString(R.string.learning_personal_map) + "\n" + l?.keys.orEmpty().map { it.key }.joinToString("\n") { keyText(it) }
        detail.text = ""; detail.visibility = View.GONE
    }

    private fun updateLegend() {
        if (::legend.isInitialized) legend.setText(if (distribution == 1) R.string.learning_half_legend else R.string.learning_legend)
    }

    private fun updateMapCaption() {
        if (!::geometryLabel.isInitialized || !::map.isInitialized) return
        val l = selectedLayout() ?: run { geometryLabel.setText(R.string.learning_map_empty); return }
        val id = selectedId ?: currentId ?: -1
        val count = l.keys.sumOf { stats.model.density(id, spatialDay(), it.key).count.toDouble() }.toInt()
        geometryLabel.text = getString(R.string.learning_region_caption,
            getString(if (preview) R.string.learning_preview else R.string.learning_actual),
            getString(if (settings.touchCorrection) R.string.learning_correction_on_short else R.string.learning_correction_off_short),
            map.regionStats.changedPercent, count)
    }

    private fun keyText(key: Int): String {
        val l = selectedLayout()
        val mask = l?.keys?.firstOrNull { it.key == key }?.let { TouchLearningMapView.rowKeys(l, it) } ?: setOf(key)
        val s = stats.model.summary(selectedId ?: currentId ?: -1, spatialDay(), key, mask)
        val stages = intArrayOf(R.string.learning_stage_empty, R.string.learning_stage_collecting, R.string.learning_stage_row, R.string.learning_stage_key)
        val name = if (korean && key < 26) Dubeolsik.map(PHYSICAL_LETTERS[key]).toString() else KeyIds.label(key)
        return getString(R.string.learning_key_detail, name, s.normal, s.guided, s.effective,
            getString(stages[s.stage]) + if (s.reference != null) " · " + getString(R.string.learning_reference) else "",
            s.meanX * 100, s.meanY * 100)
    }
    private fun showKey(key: Int) { detail.text = keyText(key); detail.visibility = View.VISIBLE }
    private fun learned(id: Int): Int = stats.model.layouts.firstOrNull { it.id == id }?.layout?.let { l ->
        l.keys.count { stats.model.summary(id, spatialDay(), it.key, TouchLearningMapView.rowKeys(l, it)).stage == 3 }
    } ?: 0

    private fun begin() {
        if (!start.isEnabled || selector == null || !stats.ready) return
        val enabled = manager.enabledInputMethodList.any { it.packageName == packageName }
        val selected = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.DEFAULT_INPUT_METHOD)
        if (!enabled || selected?.substringBefore('/') != packageName) {
            status.setText(R.string.learning_choose_ime); manager.showInputMethodPicker(); return
        }
        stop(false)
        emptyCompletions = 0
        recent = emptyList()
        shownPrediction = -1L
        val active = PersonalizationSessionBridge.Session(editor.id, korean, true) { editor.post { connectedChanged() } }
        session = active; PersonalizationSessionBridge.register(active)
        updatePresentation(true)
        editor.privateImeOptions = active.marker; editor.visibility = View.VISIBLE
        start.visibility = View.GONE; controlsHint.visibility = View.VISIBLE
        profiles.isEnabled = false
        status.setText(R.string.learning_connecting)
        nextWord()
        editor.requestFocus()
        manager.restartInput(editor)
        editor.post { manager.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT) }
        editor.postDelayed({
            if (session === active && !active.connected) { stop(false); status.setText(R.string.learning_choose_ime) }
        }, 4000)
    }

    private fun connectedChanged() {
        val active = session ?: return
        // Rearm only after the old contents have been completely removed. No old touch survives.
        if (active.invalid && active.connected && stats.ready && editor.text.isEmpty()) {
            active.word?.let { word ->
                active.finishComposition?.invoke()
                active.begin(word, stats)
            }
        }
        if (korean != active.inputKorean) {
            korean = active.inputKorean; selector = PracticeSentences(korean); selectedId = null
            updatingLanguage = true
            tabs.check(if (korean) R.id.learning_tab_ko else R.id.learning_tab_en)
            updatingLanguage = false
            refreshProfiles()
        }
        val l = active.layout ?: return
        if (currentId == null || currentLayout != l) {
            currentId = active.profile; currentLayout = l
            initialLearned = learned(active.profile)
            refreshMap()
        }
        if (shownPrediction != active.predictionRevision) {
            shownPrediction = active.predictionRevision
            refreshMap()
        }
        if (!readyForTyping) {
            status.setText(R.string.learning_connecting)
            editor.postDelayed({ if (session === active) connectedChanged() }, 80)
            return
        }
        controlsHint.setText(if (emptyCompletions == 1) R.string.learning_empty_skipped else R.string.learning_controls_hint)
        status.text = if (active.invalid) getString(R.string.learning_interrupted) else
            getString(R.string.learning_progress, active.completed, active.totalSaved, active.excluded) +
                if (active.word?.actionKey != null) " · ${active.taps.size}/5회" else
                    if (active.word?.sentence == true && active.word!!.text.any { it in '가'..'힣' } != active.inputKorean)
                        " · " + getString(R.string.learning_next_language, getString(if (active.inputKorean) R.string.learning_tab_ko else R.string.learning_tab_en)) else ""
        refreshMap()
    }

    private fun nextWord() {
        val active = session ?: return
        active.finishComposition?.invoke()
        val word = nextExercise(active)
        if (word == null) { stop(true); return }
        resetting = true; editor.setText(""); resetting = false
        active.begin(word, stats)
        active.selectPage?.invoke(word.page)
        editor.hint = getString(if (word.actionKey == null) R.string.learning_input_hint else R.string.learning_function_hint)
        prompt.text = getString(if (word.actionKey == null) R.string.learning_word else R.string.learning_function_word, word.text)
        prompt.visibility = View.VISIBLE
        connectedChanged()
    }

    private fun nextExercise(active: PersonalizationSessionBridge.Session): PracticeWord? =
        selector?.next(settings.numberRow) { key -> stats.model.summary(active.profile, spatialDay(), key, setOf(key)).effective }

    private fun complete() {
        val active = session ?: return
        if (!active.connected || active.layout == null || !readyForTyping) return
        if (editor.text.isEmpty()) {
            active.excluded++
            emptyCompletions++
            if (emptyCompletions >= 2) stop(true) else nextWord()
            return
        }
        emptyCompletions = 0
        val dots = active.taps.toList()
        active.complete(stats, editor.text.toString())
        recent = dots
        currentId = active.profile; currentLayout = active.layout
        nextWord()
        refreshMap()
    }

    private fun stop(showResult: Boolean) {
        val active = session
        if (active != null) {
            currentId = active.profile.takeIf { it >= 0 }; currentLayout = active.layout ?: currentLayout
            if (showResult) status.text = getString(R.string.learning_session_result, active.totalSaved, active.excluded,
                initialLearned, learned(active.profile), active.layout?.keys?.size ?: 0)
            active.finishComposition?.invoke()
            PersonalizationSessionBridge.unregister(active)
        }
        session = null
        if (!::editor.isInitialized) return
        updatePresentation(false)
        manager.hideSoftInputFromWindow(editor.windowToken, 0)
        resetting = true; editor.setText(""); resetting = false; editor.clearFocus(); editor.visibility = View.GONE
        prompt.text = ""; prompt.visibility = View.GONE
        start.visibility = View.VISIBLE; controlsHint.visibility = View.GONE
        profiles.isEnabled = true
        refreshProfiles(); refreshMap(); updateStart()
    }

    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("korean", korean); super.onSaveInstanceState(outState) }
    override fun onPause() { stop(false); recent = emptyList(); refreshMap(); super.onPause() }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
