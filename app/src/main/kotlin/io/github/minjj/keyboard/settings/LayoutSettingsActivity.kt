package io.github.minjj.keyboard.settings

import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.window.OnBackInvokedDispatcher
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Toast
import io.github.minjj.keyboard.R
import io.github.minjj.keyboard.ime.EditorProfile
import io.github.minjj.keyboard.ime.KeyboardController
import io.github.minjj.keyboard.ui.KeyboardView
import io.github.minjj.keyboard.ui.ResizeOverlay
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The settings scroll above a keyboard measured at the same width as the IME. */
class LayoutSettingsActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ui by lazy { SettingsUi(this) }
    private lateinit var repository: SettingsRepository
    private lateinit var initial: KeyboardSettings
    private lateinit var draft: KeyboardSettings
    private lateinit var controls: LinearLayout
    private lateinit var keyboard: KeyboardView
    private lateinit var preview: FrameLayout
    private val profile = EditorProfile()
    private val syncControls = mutableListOf<() -> Unit>()
    private var splitPreview: Boolean? = null
    private var page = R.string.layout_title
    private var saving = false
    private var discardPromptAt: Long? = null
    private var discardToast: Toast? = null
    private val resizing get() = page == R.string.layout_title
    private val hasSizeChanges get() = initial.withSizingFrom(draft) != initial
    private val landscape get() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    private val widthPercent get() = if (landscape) draft.landscapeWidthPercent else draft.portraitWidthPercent
    private val heightPercent get() = if (landscape) draft.landscapeHeightPercent else draft.portraitHeightPercent
    private val previewWidthDp get() = targetWidth(
        (if (::preview.isInitialized) preview.width else 0).takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
    ) / resources.displayMetrics.density
    private val wide get() = previewWidthDp >= 600
    private val showingSplit get() = splitPreview ?: draft.splitFor(wide, landscape)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        page = if (intent.getIntExtra(PAGE, R.string.layout_title) == R.string.mode_title) {
            R.string.mode_title
        } else {
            R.string.layout_title
        }
        if (resizing && Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, ::navigateBack)
        }
        repository = SettingsRepository(this)
        scope.launch {
            initial = repository.settings.first()
            draft = initial
            if (resizing) {
                savedInstanceState?.getIntArray(SIZE_DRAFT)?.let { saved ->
                    draft = initial.copy(narrowHeight = saved[0], wideHeight = saved[1], portraitSplitGap = saved[2],
                        portraitHeightPercent = saved[3], landscapeHeightPercent = saved[4],
                        portraitWidthPercent = saved[5], landscapeWidthPercent = saved[6],
                        landscapeSplitGap = saved.getOrElse(7) { saved[2] },
                        numberRowHeightPercent = saved.getOrElse(8) { initial.numberRowHeightPercent },
                        horizontalKeyGap = saved.getOrElse(9) { initial.horizontalKeyGap },
                        verticalKeyGap = saved.getOrElse(10) { initial.verticalKeyGap })
                }
                splitPreview = savedInstanceState?.takeIf { it.containsKey(SPLIT_PREVIEW) }?.getBoolean(SPLIT_PREVIEW)
            }
            buildPage()
        }
    }

    private fun buildPage() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            setBackgroundResource(R.color.settings_background)
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            val bars = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                else android.graphics.Insets.of(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= 30) WindowInsets.CONSUMED else insets.consumeSystemWindowInsets()
        }
        val header = ui.column(bottom = 0).also { ui.header(it, page, ::navigateBack) }
        root.addView(ui.fixedBar(header), LinearLayout.LayoutParams(-1, -2))
        controls = ui.column(top = 0)
        root.addView(ScrollView(this).apply { addView(ui.centered(controls)) }, LinearLayout.LayoutParams(-1, 0, 1f))
        if (resizing) {
            buildLayoutControls()
            root.addView(ui.fixedBar(ui.column(top = 0, bottom = 0).apply {
                addView(sizeActions(), LinearLayout.LayoutParams(-1, -2))
            }), LinearLayout.LayoutParams(-1, -2))
            render()
            // Let the controls draw before constructing and binding the preview on the next frame.
            root.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    root.viewTreeObserver.removeOnPreDrawListener(this)
                    root.postOnAnimation {
                        if (!isFinishing && !isDestroyed) mountPreview(root)
                    }
                    return true
                }
            })
        } else {
            buildModeControls()
            mountPreview(root)
        }
        setContentView(root)
    }

    private fun mountPreview(root: LinearLayout) {
        keyboard = KeyboardView(ContextThemeWrapper(this, R.style.AppTheme), KeyboardController({ null }, {}, {}, {}, {})).apply {
            id = R.id.resize_keyboard
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val overlay = if (resizing) ResizeOverlay(this, { heightPercent }, { widthPercent }, ::resize) else null
        preview = object : FrameLayout(this) {
            override fun onInterceptTouchEvent(event: MotionEvent) = overlay == null

            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val available = MeasureSpec.getSize(widthMeasureSpec)
                fun exact(size: Int) = MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY)
                measureChildWithMargins(keyboard, exact(available), 0, heightMeasureSpec, 0)
                overlay?.let {
                    val firstRow = keyboard.getChildAt(1)
                    val toolbar = keyboard.getChildAt(0)
                    val toolbarHeight = if (toolbar.visibility == View.GONE) 0 else toolbar.measuredHeight
                    val rowsHeight = (keyboard.measuredHeight - keyboard.paddingTop - keyboard.paddingBottom - toolbarHeight).coerceAtLeast(0)
                    it.measure(exact(firstRow.measuredWidth), exact(rowsHeight))
                    (it.layoutParams as FrameLayout.LayoutParams).bottomMargin = keyboard.paddingBottom
                }
                setMeasuredDimension(available, resolveSize(keyboard.measuredHeight, heightMeasureSpec))
            }
        }.apply {
            isClickable = !resizing
            isMotionEventSplittingEnabled = false
            clipChildren = false
            if (!resizing) importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val center = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        preview.addView(keyboard, FrameLayout.LayoutParams(-1, -2, center))
        overlay?.let { preview.addView(it, FrameLayout.LayoutParams(0, 0, center)) }
        preview.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft && resizing) render()
        }
        root.addView(preview, LinearLayout.LayoutParams(-1, -2))
        render()
    }

    private fun targetWidth(available: Int) = (available * widthPercent / 100).coerceIn(minOf(dp(280), available), available)

    private fun update(value: KeyboardSettings) {
        if (value != draft) clearDiscardPrompt()
        val previousWidth = widthPercent
        draft = value
        if (::preview.isInitialized && previousWidth != widthPercent) preview.requestLayout()
        render()
    }

    private fun render() {
        if (::preview.isInitialized) {
            val mode = splitPreview?.let { if (it) 1 else 0 }
            val value = when {
                mode == null -> draft
                landscape -> draft.copy(landscapeMode = mode)
                else -> draft.copy(portraitMode = mode)
            }
            keyboard.bind(profile, value)
            preview.setBackgroundColor(keyboard.surfaceColor)
        }
        syncControls.forEach { it() }
    }

    private fun buildSizeControls() {
        ui.sectionHeading(controls, R.string.size_title)
        val card = ui.card(controls, topMarginDp = 0)
        val updateNumberRowHeight: (Int) -> Unit = { height ->
            updateSizing(draft.copy(numberRowHeightPercent = height))
        }
        slider(card, R.string.number_row_height, MIN_NUMBER_ROW_HEIGHT_PERCENT, MAX_NUMBER_ROW_HEIGHT_PERCENT, 1,
            { draft.numberRowHeightPercent }, percent = true, enabled = { !saving },
            live = updateNumberRowHeight, commit = updateNumberRowHeight)
        val updateHorizontalGap: (Int) -> Unit = { updateSizing(draft.copy(horizontalKeyGap = it)) }
        slider(card, R.string.horizontal_key_gap, MIN_KEY_GAP_DP, MAX_KEY_GAP_DP, 1,
            { draft.horizontalKeyGap }, enabled = { !saving }, live = updateHorizontalGap, commit = updateHorizontalGap)
        val updateVerticalGap: (Int) -> Unit = { updateSizing(draft.copy(verticalKeyGap = it)) }
        slider(card, R.string.vertical_key_gap, MIN_KEY_GAP_DP, MAX_KEY_GAP_DP, 1,
            { draft.verticalKeyGap }, enabled = { !saving }, live = updateVerticalGap, commit = updateVerticalGap)
        val split = toggle(card, R.string.split_preview, showingSplit) {
            splitPreview = it
            render()
        }
        syncControls += {
            split.isEnabled = previewWidthDp >= 480
            split.isChecked = showingSplit && split.isEnabled
        }
        val gapTitle = if (landscape) R.string.landscape_split_gap else R.string.portrait_split_gap
        val updateGap: (Int) -> Unit = { gap ->
            updateSizing(if (landscape) draft.copy(landscapeSplitGap = gap) else draft.copy(portraitSplitGap = gap))
        }
        slider(card, gapTitle, MIN_SPLIT_GAP_DP, MAX_SPLIT_GAP_DP, 4, { draft.splitGapFor(landscape) },
            enabled = { !saving && showingSplit && previewWidthDp >= 480 }, live = updateGap, commit = updateGap)
    }

    private fun updateSizing(value: KeyboardSettings) {
        if (!saving) update(value)
    }

    private fun resize(height: Int, width: Int) = updateSizing(sized(draft, height, width))

    private fun sized(value: KeyboardSettings, height: Int, width: Int) = if (landscape) {
        value.copy(landscapeHeightPercent = height, landscapeWidthPercent = width)
    } else value.copy(portraitHeightPercent = height, portraitWidthPercent = width)

    // API 33+ delivers system back through the callback registered in onCreate.
    @SuppressLint("GestureBackNavigation")
    @Suppress("DEPRECATION")
    @Deprecated("Use OnBackInvokedDispatcher on Android 13 and later")
    override fun onBackPressed() {
        if (resizing) navigateBack() else super.onBackPressed()
    }

    private fun navigateBack() {
        if (saving) return
        val now = SystemClock.uptimeMillis()
        val confirmed = discardPromptAt?.let { now - it <= DISCARD_WINDOW_MS } ?: false
        if (!resizing || !::draft.isInitialized || !hasSizeChanges || confirmed) {
            finish()
        } else {
            clearDiscardPrompt()
            discardPromptAt = now
            discardToast = Toast.makeText(this, R.string.resize_discard_back, Toast.LENGTH_SHORT).also { it.show() }
        }
    }

    private fun clearDiscardPrompt() {
        discardPromptAt = null
        discardToast?.cancel()
        discardToast = null
    }

    private fun sizeActions() = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        fun action(label: Int, id: Int, click: () -> Unit): Button {
            val button = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
                this.id = id
                setText(label)
                isAllCaps = false
                setTextColor(ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
                    intArrayOf(if (id == R.id.resize_done) ui.accent else ui.ink, ui.secondary)
                ))
                setOnClickListener { click() }
            }
            addView(button, LinearLayout.LayoutParams(0, dp(48), 1f))
            return button
        }
        val reset = action(R.string.resize_reset, R.id.resize_reset) {
            updateSizing(sized(draft.copy(numberRowHeightPercent = 100,
                horizontalKeyGap = DEFAULT_KEY_GAP_DP, verticalKeyGap = DEFAULT_KEY_GAP_DP), 100, 100))
        }
        val cancel = action(R.string.resize_cancel, R.id.resize_cancel) {
            splitPreview = null
            updateSizing(draft.withSizingFrom(initial))
        }
        val done = action(R.string.resize_done, R.id.resize_done, ::saveSize)
        syncControls += {
            reset.isEnabled = !saving
            cancel.isEnabled = !saving && hasSizeChanges
            done.isEnabled = !saving && hasSizeChanges
        }
    }

    private fun saveSize() {
        if (saving || !hasSizeChanges) return
        val size = draft
        saving = true
        clearDiscardPrompt()
        syncControls.forEach { it() }
        scope.launch {
            try {
                withContext(NonCancellable) { repository.saveSizing(initial, size) }
                initial = initial.withSizingFrom(size)
            } catch (_: IOException) {
                if (!isDestroyed) Toast.makeText(this@LayoutSettingsActivity, R.string.layout_save_failed, Toast.LENGTH_SHORT).show()
            } finally {
                saving = false
                syncControls.forEach { it() }
            }
        }
    }

    private fun KeyboardSettings.withSizingFrom(value: KeyboardSettings) = copy(
        narrowHeight = value.narrowHeight, wideHeight = value.wideHeight,
        portraitSplitGap = value.portraitSplitGap, landscapeSplitGap = value.landscapeSplitGap,
        portraitHeightPercent = value.portraitHeightPercent, landscapeHeightPercent = value.landscapeHeightPercent,
        portraitWidthPercent = value.portraitWidthPercent, landscapeWidthPercent = value.landscapeWidthPercent,
        numberRowHeightPercent = value.numberRowHeightPercent,
        horizontalKeyGap = value.horizontalKeyGap, verticalKeyGap = value.verticalKeyGap
    )

    private fun buildModeControls() {
        for (horizontal in listOf(false, true)) {
            ui.sectionHeading(controls, if (horizontal) R.string.landscape_mode else R.string.portrait_mode)
            val group = ui.radioCard(controls, topMarginDp = 0)
            val labels = listOf(R.string.mode_inherit, R.string.mode_normal, R.string.mode_split)
            val buttons = labels.map { ui.radio(group, getString(it)) }
            val selected = if (horizontal) draft.landscapeMode else draft.portraitMode
            group.check(buttons[selected + 1].id)
            group.setOnCheckedChangeListener { _, id ->
                val index = buttons.indexOfFirst { it.id == id }
                if (index >= 0) {
                    val mode = index - 1
                    update(if (horizontal) draft.copy(landscapeMode = mode) else draft.copy(portraitMode = mode))
                    persist { repository.setOrientationMode(horizontal, mode) }
                }
            }
        }
        ui.sectionHeading(controls, R.string.mode_inherit_short)
        val card = ui.card(controls, topMarginDp = 0)
        toggle(card, R.string.split_narrow, draft.narrowSplit) {
            update(draft.copy(narrowSplit = it))
            persist { repository.setSplit(false, it) }
        }
        toggle(card, R.string.split_wide, draft.wideSplit) {
            update(draft.copy(wideSplit = it))
            persist { repository.setSplit(true, it) }
        }
    }

    private fun buildLayoutControls() {
        slider(ui.card(controls), R.string.font_title, 80, 140, 5, { draft.fontPercent }, percent = true,
            live = { update(draft.copy(fontPercent = it)) }) {
            update(draft.copy(fontPercent = it))
            persist { repository.setFontPercent(it) }
        }
        buildSizeControls()
        ui.sectionHeading(controls, R.string.layout_display)
        val card = ui.card(controls, topMarginDp = 0)
        toggle(card, R.string.high_contrast, draft.highContrast) {
            update(draft.copy(highContrast = it))
            persist { repository.setOption("highContrast", it) }
        }
        toggle(card, R.string.toolbar, draft.toolbar) {
            update(draft.copy(toolbar = it))
            persist { repository.setOption("toolbar", it) }
        }
        toggle(card, R.string.number_row, draft.numberRow) {
            update(draft.copy(numberRow = it))
            persist { repository.setOption("numberRow", it) }
        }
        toggle(card, R.string.extended_layout, draft.extendedLayout, R.string.extended_description) {
            update(draft.copy(extendedLayout = it))
            persist { repository.setOption("extended", it) }
        }
    }

    private fun persist(write: suspend () -> Unit) {
        scope.launch {
            try {
                withContext(NonCancellable) { write() }
            } catch (_: IOException) {
                if (!isDestroyed) Toast.makeText(this@LayoutSettingsActivity, R.string.layout_save_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun toggle(parent: LinearLayout, title: Int, checked: Boolean, description: Int? = null,
        change: (Boolean) -> Unit) = ui.toggle(parent, title, description).apply {
        isChecked = checked
        setOnClickListener { change(isChecked) }
    }

    private fun slider(parent: LinearLayout, title: Int, minimum: Int, maximum: Int, step: Int, read: () -> Int,
        percent: Boolean = false, enabled: () -> Boolean = { true }, live: ((Int) -> Unit)? = null, commit: (Int) -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, 0)
            addView(ui.text(getString(title)), LinearLayout.LayoutParams(-1, -2))
        }
        val caption = ui.caption(row, "")
        fun display(value: Int) {
            caption.text = getString(if (percent) R.string.percent_value else R.string.dp_value, value)
        }
        var startValue: Int? = null
        val seek = SeekBar(this).apply {
            max = (maximum - minimum) / step
            minHeight = dp(40)
            contentDescription = getString(title)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(view: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val value = minimum + progress * step
                    display(value)
                    if (startValue == null) commit(value) else live?.invoke(value)
                }
                override fun onStartTrackingTouch(view: SeekBar) { startValue = view.progress }
                override fun onStopTrackingTouch(view: SeekBar) {
                    val start = startValue
                    startValue = null
                    if (view.progress != start) commit(minimum + view.progress * step)
                }
            })
        }
        row.addView(seek, LinearLayout.LayoutParams(-1, -2))
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
        syncControls += {
            if (startValue == null) {
                seek.progress = (read() - minimum) / step
                display(read())
                seek.isEnabled = enabled()
                row.alpha = if (seek.isEnabled) 1f else 0.55f
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (resizing && ::draft.isInitialized) {
            outState.putIntArray(SIZE_DRAFT, intArrayOf(draft.narrowHeight, draft.wideHeight, draft.portraitSplitGap,
                draft.portraitHeightPercent, draft.landscapeHeightPercent, draft.portraitWidthPercent, draft.landscapeWidthPercent,
                draft.landscapeSplitGap, draft.numberRowHeightPercent, draft.horizontalKeyGap, draft.verticalKeyGap))
            splitPreview?.let { outState.putBoolean(SPLIT_PREVIEW, it) }
        }
    }

    override fun onDestroy() {
        clearDiscardPrompt()
        scope.cancel()
        super.onDestroy()
    }
    private fun dp(value: Int) = ui.dp(value)

    companion object {
        const val PAGE = "layoutPage"
        private const val SIZE_DRAFT = "sizeDraft"
        private const val SPLIT_PREVIEW = "splitPreview"
        private const val DISCARD_WINDOW_MS = 3000L
    }
}
