package io.github.minjj.keyboard.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.media.AudioManager
import android.os.Build
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.ImageButton
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.github.minjj.keyboard.R
import io.github.minjj.keyboard.correction.SpatialStatsRepository
import io.github.minjj.keyboard.touch.LayoutDescriptor
import io.github.minjj.keyboard.touch.KeyIds
import io.github.minjj.keyboard.touch.LayoutKey
import io.github.minjj.keyboard.ime.*
import io.github.minjj.keyboard.settings.KeyboardSettings
import kotlin.math.min
import kotlin.math.roundToInt

private const val MIN_KEY_TEXT_SIZE_SP = 12

/** Coordinates are routed here while native buttons retain semantic click and long-click actions. */
@android.annotation.SuppressLint("ViewConstructor")
class KeyboardView(context: Context, private val controller: KeyboardController) : LinearLayout(context) {
    private val keyTypeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private var profile = EditorProfile()
    private var settings = KeyboardSettings()
    private var signature = ""
    private var rows: List<List<KeySpec>> = emptyList()
    private var flatKeys: List<KeySpec> = emptyList()
    private val keyButtons = mutableListOf<Pair<HintKeyButton, KeySpec>>()
    private val toolbarButtons = mutableListOf<Pair<View, KeySpec>>()
    private val wordButtons = mutableListOf<Button>()
    private val cursorModeLabel = context.getString(R.string.cursor_mode_active)
    private val cursorModeScrim = Paint().apply { color = 0xD9000000.toInt() }
    private val cursorModePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        strokeCap = Paint.Cap.ROUND
    }
    private var cursorModeVisible = false
    internal var spatialStats: SpatialStatsRepository? = null
    private val touchRouter = TouchRouter(
        controller, { profile }, { settings }, ::secondaryFor, { _, sliding -> feedback(sliding) }, ::dp, { spatialStats }, ::spatialProfile,
        ::swipeLanguage, ::showCursorMode,
    )
    private var availableWidth = 0
    private var editCharacter: ((KeySpec) -> Unit)? = null
    private var editSymbolsOnly = false
    private var regularKeyWidth = 0
    private var rendered: RenderState? = null
    private data class RenderState(val profile: EditorProfile, val settings: KeyboardSettings, val width: Int, val contentWidth: Int,
        val screenWidthDp: Int, val screenHeightDp: Int, val orientation: Int, val uiMode: Int, val density: Int, val fontScale: Float,
        val korean: Boolean, val symbols: Int, val shift: ShiftState, val held: Boolean)
    var splitActive = false; private set
    var extendedActive = false; private set

    internal fun legacySpatialProfile(): Int {
        val widthDp = keyboardWidth / resources.displayMetrics.density
        val widthBand = if (widthDp < 600) 0 else if (widthDp < 840) 1 else 2
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return widthBand + (if (landscape) 4 else 0) + (if (splitActive) 8 else 0) +
            (if (extendedActive) 16 else 0) + (if (controller.korean) 32 else 0) + (if (settings.numberRow) 64 else 0)
    }
    internal var learningLayout: LayoutDescriptor? = null
        private set
    internal var learningGeometry: GeometrySnapshot? = null
        private set
    private var learningProfile = -1
    internal fun spatialProfile(): Int = learningLayout?.let { descriptor ->
        spatialStats?.layout(descriptor, learningProfile)?.also { learningProfile = it }
    } ?: legacySpatialProfile()
    private val dark get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val ink get() = Color.parseColor(if (settings.highContrast) "#000000" else if (dark) "#F6F6F8" else "#17181C")
    private val muted get() = Color.parseColor(if (settings.highContrast) "#252525" else if (dark) "#ACADB5" else "#727784")
    internal val surfaceColor get() = if (settings.highContrast || dark) Color.BLACK else Color.rgb(219, 222, 229)
    private val keyboardWidth: Int get() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val percent = if (landscape) settings.landscapeWidthPercent else settings.portraitWidthPercent
        return (availableWidth * percent / 100).coerceAtLeast(dp(280)).coerceAtMost(availableWidth)
    }
    private val contentWidth get() = (keyboardWidth - paddingLeft - paddingRight).coerceAtLeast(0)
    init {
        orientation = VERTICAL; layoutDirection = LAYOUT_DIRECTION_LTR
        setBackgroundColor(surfaceColor); setPadding(dp(4), dp(3), dp(4), dp(4))
        setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            val bars = if (android.os.Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars())
                else android.graphics.Insets.of(insets.systemWindowInsetLeft, 0, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            setPadding(dp(4) + bars.left, dp(3), dp(4) + bars.right, dp(4) + bars.bottom)
            insets
        }
    }

    fun bind(profile: EditorProfile, settings: KeyboardSettings) {
        this.profile = profile; this.settings = settings; refresh()
    }

    /** Reuse the IME layout and styling while native key clicks select an assignment to edit. */
    fun bindEditor(settings: KeyboardSettings, symbolsOnly: Boolean, edit: (KeySpec) -> Unit) {
        val changedMode = editCharacter == null || editSymbolsOnly != symbolsOnly
        editCharacter = edit
        editSymbolsOnly = symbolsOnly
        if (changedMode) { rendered = null; signature = "" }
        bind(EditorProfile(), settings.copy(toolbar = false, secondaryCharacters = true))
    }

    fun refresh() {
        val config = resources.configuration
        val state = RenderState(profile, settings, availableWidth, contentWidth, config.screenWidthDp, config.screenHeightDp, config.orientation,
            config.uiMode, config.densityDpi, config.fontScale, controller.korean, controller.symbolsPage, controller.shift, controller.shiftHeld)
        if (state == rendered) {
            updateWordButtons()
            return
        }
        rendered = state
        val widthDp = keyboardWidth / resources.displayMetrics.density
        val wide = widthDp >= 600
        val landscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE
        splitActive = settings.splitFor(wide, landscape) && widthDp >= 480
        extendedActive = settings.extendedLayout && widthDp >= 840 && profile.layout == InputLayout.TEXT
        val newRows = KeyboardLayouts.rows(controller, profile, enterLabel(), splitActive, extendedActive, settings.numberRow, settings)
        val gap = if (splitActive) min(dp(settings.splitGapFor(landscape)), contentWidth / 3) else 0
        val scale = (if (landscape) settings.landscapeHeightPercent else settings.portraitHeightPercent) / 100f
        val requestedHeight = (if (config.screenWidthDp >= 600) settings.wideHeight else settings.narrowHeight) * scale
        val maxHeight = (resources.configuration.screenHeightDp * (if (landscape) 0.40f else 0.42f) * scale - if (settings.toolbar) 44 else 0) / newRows.size
        val rowHeight = min(requestedHeight.toFloat(), maxHeight).coerceAtLeast(30f).toInt()
        val hasNumberRow = profile.layout == InputLayout.TEXT && (settings.numberRow || controller.symbolsPage != 0)
        val firstRowHeight = if (hasNumberRow) (rowHeight * settings.numberRowHeightPercent / 100f).roundToInt() else rowHeight
        val wordBar = settings.wordSuggestions && profile.allowsLanguageModel
        val newSignature = "${newRows.map { it.map { key -> Triple(key.command, key.value, key.splitBefore) } }}:$availableWidth:$contentWidth:$splitActive:$extendedActive:$gap:$rowHeight:$firstRowHeight:$dark:${config.densityDpi}:$wordBar:${settings.copy(fontPercent = 100)}"
        flatKeys = newRows.flatten()
        if (signature != newSignature) {
            if (controller.shiftHeld) {
                cancelInteractions(false)
                rendered = null
                refresh()
                return
            }
            signature = newSignature; rows = newRows; rebuild(rowHeight, firstRowHeight, gap)
        } else {
            rows = newRows
            keyButtons.forEachIndexed { index, pair -> styleKey(pair.first, flatKeys[index]) }
        }
        if (regularKeyWidth > 0) {
            val keyMargin = dp(settings.verticalKeyGap)
            if (firstRowHeight == rowHeight) {
                fitCharacterLabels(keyButtons, dp(rowHeight) - keyMargin)
            } else {
                val numberKeyCount = newRows.first().size
                fitCharacterLabels(keyButtons.subList(0, numberKeyCount), dp(firstRowHeight) - keyMargin)
                fitCharacterLabels(keyButtons.subList(numberKeyCount, keyButtons.size), dp(rowHeight) - keyMargin)
            }
        }
        updateWordButtons()
    }

    private fun rebuild(rowHeight: Int, firstRowHeight: Int, gap: Int) {
        touchRouter.updateGeometry(GeometrySnapshot(emptyList(), 0))
        removeAllViews(); keyButtons.clear(); toolbarButtons.clear(); wordButtons.clear(); setBackgroundColor(surfaceColor)
        val horizontalGap = dp(settings.horizontalKeyGap)
        val verticalGap = dp(settings.verticalKeyGap)
        val groups = rows.flatMap { row ->
            val boundary = row.indexOfFirst { it.splitBefore }.takeIf { it > 0 } ?: ((row.size + 1) / 2)
            if (splitActive) listOf(row.take(boundary), row.drop(boundary)) else listOf(row)
        }
        regularKeyWidth = if (profile.layout == InputLayout.TEXT) groups.filter { group ->
            group.any { it.command == KeyCommand.CHARACTER } &&
                (!splitActive || group.none { it.command == KeyCommand.SPACE })
        }
            .minOfOrNull { group ->
                val width = if (splitActive) (contentWidth - gap) / 2 else contentWidth
                ((width - horizontalGap * group.size) / group.sumOf { it.weight.toDouble() }).toInt()
            }?.coerceAtLeast(1) ?: 1 else 0
        val toolbar = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL; orientation = HORIZONTAL
            visibility = if (settings.toolbar) VISIBLE else GONE
        }
        val showSuggestions = settings.toolbar && settings.wordSuggestions && profile.allowsLanguageModel
        // Reserve one item width per candidate when the keyboard is too narrow for 56dp tools.
        val toolbarItemWidth = min(dp(56), (availableWidth - paddingLeft - paddingRight).coerceAtLeast(0) / if (showSuggestions) 7 else 5)
        fun tool(command: KeyCommand, description: Int) {
            val key = KeySpec("", command)
            val button = toolbarButton(key, description)
            toolbarButtons += button to key
            toolbar.addView(button, LayoutParams(toolbarItemWidth, dp(44)))
        }
        tool(KeyCommand.SETTINGS, R.string.key_settings); tool(KeyCommand.MODE, R.string.key_mode)
        tool(KeyCommand.CLIPBOARD, R.string.key_clipboard)
        if (showSuggestions) {
            val suggestions = LinearLayout(context).apply { orientation = HORIZONTAL }
            repeat(2) { index ->
                val key = KeySpec("", KeyCommand.SUGGESTION, index.toString())
                val button = Button(context).apply {
                    id = KeyboardKeyIds.of(key)
                    isAllCaps = false
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    minWidth = 0
                    minimumWidth = 0
                    setPadding(dp(4), 0, dp(4), 0)
                    textSize = 13f
                    setTextColor(if (settings.highContrast) Color.BLACK else ink)
                    background = keyBackground(true)
                    visibility = INVISIBLE
                    setOnClickListener { (tag as? WordOffer)?.let(controller::selectWord) }
                }
                wordButtons += button
                toolbarButtons += button to key
                suggestions.addView(button, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            }
            toolbar.addView(suggestions, LayoutParams(0, dp(44), 1f))
        } else toolbar.addView(View(context), LayoutParams(0, dp(44), 1f))
        tool(KeyCommand.CURSOR_LEFT, R.string.key_cursor_left)
        tool(KeyCommand.CURSOR_RIGHT, R.string.key_cursor_right)
        addView(toolbar, LayoutParams(LayoutParams.MATCH_PARENT, dp(44)))
        for ((rowIndex, row) in rows.withIndex()) {
            fun horizontal() = LinearLayout(context).apply {
                orientation = HORIZONTAL; gravity = Gravity.CENTER
            }
            val line = horizontal(); val left = if (splitActive) horizontal() else line; val right = if (splitActive) horizontal() else line
            if (splitActive) {
                if (regularKeyWidth > 0) {
                    left.gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
                    right.gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
                }
                line.addView(left, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
                line.addView(View(context), LayoutParams(gap, LayoutParams.MATCH_PARENT))
                line.addView(right, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            }
            val boundary = row.indexOfFirst { it.splitBefore }.takeIf { it > 0 } ?: ((row.size + 1) / 2)
            row.forEachIndexed { index, key ->
                val regular = regularKeyWidth > 0 && key.command == KeyCommand.CHARACTER
                val button = HintKeyButton(context).apply {
                    id = KeyboardKeyIds.of(key); typeface = keyTypeface
                    isAllCaps = false; minWidth = 0; minimumWidth = 0; minHeight = 0; minimumHeight = 0
                    gravity = Gravity.CENTER; includeFontPadding = false
                    maxLines = 1; stateListAnimator = null; elevation = 0f
                    background = keyBackground(key.command != KeyCommand.CHARACTER && key.command != KeyCommand.SPACE)
                    setPadding(0, 0, 0, 0)
                    if (regular) setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE)
                    isSoundEffectsEnabled = false; isHapticFeedbackEnabled = true
                    if (key.command == KeyCommand.SPACER) { visibility = INVISIBLE; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
                    else if (editCharacter != null) {
                        tag = key
                        when (key.command) {
                            KeyCommand.CHARACTER -> if (!editSymbolsOnly || key.symbolSlot != null) {
                                setOnClickListener { (tag as? KeySpec)?.let { editCharacter?.invoke(it) } }
                            } else { isClickable = false; isFocusable = false; alpha = 0.55f }
                            KeyCommand.SHIFT, KeyCommand.LANGUAGE, KeyCommand.SYMBOLS,
                            KeyCommand.MORE_SYMBOLS, KeyCommand.LETTERS -> setOnClickListener { controller.press(key) }
                            else -> { isClickable = false; isFocusable = false; alpha = 0.55f }
                        }
                    } else setOnClickListener { feedback(); controller.press(key) }
                    if (editCharacter == null && secondaryFor(key) != null) setOnLongClickListener {
                        val value = secondaryFor(key) ?: return@setOnLongClickListener false
                        feedback()
                        controller.secondary(value)
                        true
                    }
                }
                styleKey(button, key); keyButtons += button to key
                val container = if (splitActive && index >= boundary) right else left
                // Keep split controls proportional to a letter; only spaces absorb unused width.
                val fixed = regular || (splitActive && regularKeyWidth > 0 && key.command != KeyCommand.SPACE)
                val keyWidth = if (regular) regularKeyWidth else (regularKeyWidth * key.weight).toInt()
                container.addView(button, LayoutParams(if (fixed) keyWidth else 0, LayoutParams.MATCH_PARENT,
                    if (fixed) 0f else key.weight).apply {
                        setMargins(horizontalGap / 2, verticalGap / 2,
                            horizontalGap - horizontalGap / 2, verticalGap - verticalGap / 2)
                    })
            }
            val height = if (rowIndex == 0) firstRowHeight else rowHeight
            addView(line, LayoutParams(contentWidth, dp(height)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        }
    }

    private fun updateWordButtons() {
        if (wordButtons.isEmpty()) return
        val offers = controller.wordOffers()
        wordButtons.forEachIndexed { index, button ->
            val offer = offers.getOrNull(index)
            if (button.tag == offer) return@forEachIndexed
            button.tag = offer
            button.text = offer?.candidate?.word.orEmpty()
            button.visibility = if (offer == null) INVISIBLE else VISIBLE
        }
    }

    private fun fitCharacterLabels(buttons: List<Pair<HintKeyButton, KeySpec>>, keyHeight: Int) {
        val letters = buttons.filter { it.second.command == KeyCommand.CHARACTER }
        val labels = letters.joinToString("\n") { it.first.text }
        val paint = TextPaint().apply { typeface = keyTypeface }
        val desired = desiredTextSize(if (extendedActive) 25 else 22)
        var size = min(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, desired.toFloat(),
            resources.displayMetrics), regularKeyWidth * 0.82f)
        // Measure the actual labels because Hangul and symbols can use taller fallback fonts.
        while (size > 1f) {
            paint.textSize = size
            val layout = StaticLayout.Builder.obtain(labels, 0, labels.length, paint, regularKeyWidth)
                .setIncludePad(false)
                .setUseLineSpacingFromFallbacks(true)
                .build()
            val lineHeight = (0 until layout.lineCount).maxOf { layout.getLineBottom(it) - layout.getLineTop(it) }
            if (lineHeight <= keyHeight) break
            size = min(size - 1f, size * keyHeight / lineHeight)
        }
        for ((button, _) in letters) {
            paint.textSize = size
            val labelWidth = paint.measureText(button.text.toString())
            val available = (regularKeyWidth - button.paddingLeft - button.paddingRight).coerceAtLeast(1)
            val fitted = if (labelWidth > available) size * available / labelWidth else size
            if (button.textSize != fitted) button.setTextSize(TypedValue.COMPLEX_UNIT_PX, fitted)
        }
    }

    private fun desiredTextSize(baseSize: Int) = (baseSize * settings.fontPercent / 100).coerceAtLeast(MIN_KEY_TEXT_SIZE_SP)

    private fun styleKey(button: HintKeyButton, key: KeySpec) {
        if (editCharacter != null) button.tag = key
        val icon = when {
            key.command == KeyCommand.DELETE || key.command == KeyCommand.SHIFT -> key.command
            key.command == KeyCommand.ENTER && key.label == context.getString(R.string.action_return) -> KeyCommand.ENTER
            else -> null
        }
        val label = if (icon != null) "" else key.label
        if (button.text.toString() != label) {
            button.stopLanguageAnimation()
            button.text = label
        }
        button.setPrimaryIcon(icon, ink); button.iconScale = settings.fontPercent / 100f
        button.capsLocked = key.command == KeyCommand.SHIFT && controller.shift == ShiftState.LOCKED
        if (key.command != KeyCommand.CHARACTER || regularKeyWidth == 0) {
            val large = key.command == KeyCommand.CHARACTER || key.command == KeyCommand.SHIFT ||
                key.command == KeyCommand.CURSOR_LEFT || key.command == KeyCommand.CURSOR_RIGHT
            val desired = desiredTextSize(if (large) (if (extendedActive) 25 else 22) else 14)
            val desiredPixels = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, desired.toFloat(), resources.displayMetrics).roundToInt()
            if (button.autoSizeMaxTextSize != desiredPixels) {
                if (desired == MIN_KEY_TEXT_SIZE_SP) {
                    button.setAutoSizeTextTypeUniformWithPresetSizes(intArrayOf(desiredPixels), TypedValue.COMPLEX_UNIT_PX)
                } else {
                    button.setAutoSizeTextTypeUniformWithConfiguration(MIN_KEY_TEXT_SIZE_SP, desired, 1, TypedValue.COMPLEX_UNIT_SP)
                }
            }
        }
        val color = if (key.command == KeyCommand.SPACE && !settings.highContrast) muted else ink
        if (button.currentTextColor != color) button.setTextColor(color)
        button.secondaryLabel = secondaryFor(key); button.secondaryColor = muted
        button.languageArrows = key.command == KeyCommand.SPACE && settings.spaceLanguageSwipe && profile.layout == InputLayout.TEXT
        if (key.command == KeyCommand.SPACE) {
            val sidePadding = if (button.languageArrows) dp(18) else 0
            button.setPadding(sidePadding, 0, sidePadding, 0)
        }
        val resource = when (key.command) {
            KeyCommand.SHIFT -> when (controller.shift) {
                ShiftState.OFF -> if (controller.shiftHeld) R.string.key_shift_once else R.string.key_shift_off
                ShiftState.ONCE -> R.string.key_shift_once
                ShiftState.LOCKED -> R.string.key_shift_locked
            }
            KeyCommand.DELETE -> R.string.key_delete
            KeyCommand.DELETE_FORWARD -> R.string.key_delete_forward
            KeyCommand.CURSOR_LEFT -> R.string.key_cursor_left
            KeyCommand.CURSOR_RIGHT -> R.string.key_cursor_right
            KeyCommand.SPACE -> R.string.key_space
            KeyCommand.LANGUAGE -> R.string.key_language
            KeyCommand.SYMBOLS -> R.string.key_symbols
            KeyCommand.LETTERS -> R.string.key_letters
            KeyCommand.MORE_SYMBOLS -> R.string.key_more_symbols
            else -> null
        }
        button.contentDescription = if (key.command == KeyCommand.SPACE && button.languageArrows) context.getString(R.string.key_space_gestures)
        else if (key.command == KeyCommand.DELETE && settings.deleteGesture && !profile.password) context.getString(R.string.key_delete_gestures)
        else resource?.let(context::getString) ?: button.secondaryLabel?.let {
            context.getString(R.string.key_secondary_description, key.label, it)
        } ?: key.label
        if (key.command == KeyCommand.SHIFT) button.isSelected = controller.uppercase
    }

    private fun secondaryFor(key: KeySpec): String? = if (settings.secondaryCharacters && profile.layout == InputLayout.TEXT)
        KeyboardLayouts.secondary(key, controller.korean, settings) else null

    private fun toolbarButton(key: KeySpec, description: Int) = ImageButton(context).apply {
        val command = key.command
        id = KeyboardKeyIds.of(key); contentDescription = context.getString(description)
        val color = if (command == KeyCommand.MODE && splitActive) Color.parseColor("#5A99FF") else if (settings.highContrast) Color.WHITE else ink
        val buttonColors = StateListDrawable().apply {
            fun roundedSquare(color: String) = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
                setColor(Color.parseColor(color))
            }
            addState(intArrayOf(android.R.attr.state_pressed), roundedSquare(if (dark || settings.highContrast) "#51535B" else "#AFBDD2"))
            addState(intArrayOf(), roundedSquare(if (dark || settings.highContrast) "#303136" else "#C4C8D2"))
        }
        background = InsetDrawable(buttonColors, dp(4), dp(6), dp(4), dp(6))
        setImageDrawable(KeyboardIconDrawable(command, color))
        setPadding(dp(10), dp(12), dp(10), dp(12))
        isSoundEffectsEnabled = false; setOnClickListener { controller.press(key) }
    }

    private fun feedback(slidingDelete: Boolean = false) {
        val strength = settings.vibrationStrength
        if (settings.vibration) KeyboardHaptics.play(context, if (slidingDelete) (strength + 1) / 2 else strength)
        if (settings.sound) context.getSystemService(AudioManager::class.java)?.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD)
    }

    private fun swipeLanguage(direction: Int) {
        val previousLabel = keyButtons.firstOrNull { it.second.command == KeyCommand.SPACE }?.first?.text?.toString()
        controller.press(KeySpec("", KeyCommand.LANGUAGE), InputSource.TOUCH_GESTURE)
        // Switching from a symbol page can replace the space keys during the controller callback.
        if (previousLabel != null) {
            keyButtons.forEach { (button, key) ->
                if (key.command == KeyCommand.SPACE) button.animateLanguageLabel(previousLabel, direction)
            }
        }
    }

    private fun showCursorMode(visible: Boolean) {
        if (cursorModeVisible == visible) return
        cursorModeVisible = visible
        if (Build.VERSION.SDK_INT >= 30) {
            keyButtons.forEach { (button, key) ->
                if (key.command == KeyCommand.SPACE) button.stateDescription = cursorModeLabel.takeIf { visible }
            }
        }
        invalidate()
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!cursorModeVisible) return
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), cursorModeScrim)
        val x = (paddingLeft + width - paddingRight) / 2f
        val y = (paddingTop + height - paddingBottom) / 2f
        val iconTop = y - dp(25)
        val iconBottom = y - dp(5)
        val cap = dp(4)
        cursorModePaint.strokeWidth = dp(2).toFloat()
        canvas.drawLine(x, iconTop, x, iconBottom, cursorModePaint)
        canvas.drawLine(x - cap, iconTop, x + cap, iconTop, cursorModePaint)
        canvas.drawLine(x - cap, iconBottom, x + cap, iconBottom, cursorModePaint)
        val arrowY = (iconTop + iconBottom) / 2
        for (direction in -1..1 step 2) {
            val tip = x + direction * dp(15)
            val tail = tip - direction * cap
            canvas.drawLine(tip, arrowY, tail, arrowY - cap, cursorModePaint)
            canvas.drawLine(tip, arrowY, tail, arrowY + cap, cursorModePaint)
        }
        cursorModePaint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, resources.displayMetrics)
        canvas.drawText(cursorModeLabel, x, y + dp(3) - cursorModePaint.ascent(), cursorModePaint)
    }

    private fun shape(color: String) = GradientDrawable().apply { setColor(Color.parseColor(color)); cornerRadius = dp(5).toFloat() }
    private fun keyBackground(function: Boolean) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), shape(if (settings.highContrast) "#FFC64A" else if (dark) "#626570" else "#AFBDD2"))
        addState(intArrayOf(android.R.attr.state_selected), shape(if (settings.highContrast) "#FFC64A" else if (dark) "#526B97" else "#A9BFEB"))
        addState(intArrayOf(), shape(if (settings.highContrast) "#FFE451" else if (dark) (if (function) "#202126" else "#3C3D43") else (if (function) "#C4C8D2" else "#FFFFFF")))
    }
    private fun enterLabel(): String = profile.customActionLabel ?: context.getString(when (profile.action) {
        android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH -> R.string.action_search
        android.view.inputmethod.EditorInfo.IME_ACTION_DONE -> R.string.action_done
        android.view.inputmethod.EditorInfo.IME_ACTION_NEXT -> R.string.action_next
        android.view.inputmethod.EditorInfo.IME_ACTION_PREVIOUS -> R.string.action_previous
        android.view.inputmethod.EditorInfo.IME_ACTION_GO -> R.string.action_go
        android.view.inputmethod.EditorInfo.IME_ACTION_SEND -> R.string.action_send
        else -> R.string.action_return
    })
    internal var touchDiagnostics: TouchDiagnosticObserver?
        get() = touchRouter.diagnostics
        set(value) { touchRouter.diagnostics = value }

    fun cancelInteractions(resetShift: Boolean = true) {
        touchRouter.cancel(notifyShift = resetShift)
        keyButtons.forEach { (button, _) -> button.stopLanguageAnimation() }
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean =
        (editCharacter == null && event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) || super.onInterceptTouchEvent(event)

    @android.annotation.SuppressLint("ClickableViewAccessibility") // Each native key exposes its own actions.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (editCharacter != null || !event.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) return super.onTouchEvent(event)
        touchRouter.route(event)
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val measuredWidth = MeasureSpec.getSize(widthMeasureSpec)
        if (availableWidth != measuredWidth) {
            availableWidth = measuredWidth
            cancelInteractions(false)
        }
        if (rendered?.width != measuredWidth || rendered?.contentWidth != contentWidth) refresh()
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    @android.annotation.SuppressLint("DrawAllocation") // Snapshot measured key bounds once per layout, outside touch handling.
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (editCharacter != null) return
        if (changed) touchRouter.cancel()
        val occurrences = mutableMapOf<Int, Int>()
        fun target(view: View, key: KeySpec, toolbar: Boolean): TouchKey {
            val bounds = Rect(0, 0, view.width, view.height)
            offsetDescendantRectToMyCoords(view, bounds)
            val half = if (splitActive && !toolbar) {
                val group = view.parent as LinearLayout
                (group.parent as LinearLayout).indexOfChild(group)
            } else 0
            val id = TouchKey.identity(key)?.let { base ->
                val index = occurrences.getOrDefault(base, 0); occurrences[base] = index + 1
                KeyIds.occurrence(base, index)
            }
            val adaptive = id?.takeIf { profile.layout == InputLayout.TEXT && controller.symbolsPage == 0 && KeyIds.kind(it) != 2 }
            return TouchKey(view, key, bounds, toolbar, half, adaptive)
        }
        val keys = buildList {
            if (settings.toolbar) toolbarButtons.forEach { (view, key) -> add(target(view, key, true)) }
            keyButtons.forEach { (view, key) ->
                if (view.visibility == VISIBLE) add(target(view, key, false))
            }
        }
        val geometry = GeometrySnapshot(keys, dp(settings.horizontalKeyGap), dp(settings.verticalKeyGap))
        learningGeometry = geometry
        touchRouter.updateGeometry(geometry)
        val letters = keys.filter { it.area != null }
        if (letters.isNotEmpty()) {
            val density = resources.displayMetrics.density
            val screen = IntArray(2); getLocationOnScreen(screen)
            val rowTops = keys.map { it.bounds.top }.distinct().sorted()
            val descriptor = LayoutDescriptor(legacySpatialProfile(), width / density, height / density,
                screen[0] / density, screen[1] / density, dp(settings.horizontalKeyGap) / density, dp(settings.verticalKeyGap) / density,
                if (splitActive) min(settings.splitGapFor(resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE).toFloat(), contentWidth / density / 3) else 0f,
                letters.map { key -> val area = checkNotNull(key.area)
                    LayoutKey(area.key, area.left / density, area.top / density, area.right / density, area.bottom / density,
                        rowTops.indexOf(area.top), if (key.half == 0) 0 else 1)
                }, keys.filter { !it.bounds.isEmpty }.map { key ->
                    LayoutKey(key.area?.key ?: TouchKey.identity(key.spec) ?: if (key.spec.command == KeyCommand.CHARACTER) -2 else -1,
                        key.bounds.left / density, key.bounds.top / density, key.bounds.right / density, key.bounds.bottom / density,
                        rowTops.indexOf(key.bounds.top), if (key.half == 0) 0 else 1)
                }, density, controller.symbolsPage, profile.layout.ordinal)
            learningLayout = descriptor
            val id = spatialProfile()
            controller.practiceSession?.geometry(descriptor, id, geometry)
        } else learningLayout = null
    }
    override fun onDetachedFromWindow() {
        cancelInteractions(false)
        touchDiagnostics = null
        super.onDetachedFromWindow()
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
