package io.github.minjj.keyboard.ui

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.media.AudioManager
import android.os.Looper
import android.os.SystemClock
import android.os.Vibrator
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import io.github.minjj.keyboard.R
import io.github.minjj.keyboard.ime.*
import io.github.minjj.keyboard.settings.KeyboardSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAudioManager
import org.robolectric.shadows.ShadowChoreographer
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class KeyboardViewTest {
    @Test fun `toolbar spans available width while key rows follow width settings`() {
        val keyboard = KeyboardView(RuntimeEnvironment.getApplication(), KeyboardController({ null }, {}, {}, {}, {}))
        for (percent in listOf(100, 70)) {
            keyboard.bind(EditorProfile(), KeyboardSettings(portraitWidthPercent = percent))
            keyboard.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.AT_MOST))
            keyboard.layout(0, 0, keyboard.measuredWidth, keyboard.measuredHeight)
            val toolbar = keyboard.getChildAt(0)
            val row = keyboard.getChildAt(1)
            assertEquals(600 - keyboard.paddingLeft - keyboard.paddingRight, toolbar.width)
            assertEquals(600 * percent / 100 - keyboard.paddingLeft - keyboard.paddingRight, row.width)
            assertEquals((600 - row.width) / 2, row.left)
        }
    }

    @Test fun `resize handle previews during drag and applies only on release`() {
        val changes = mutableListOf<Pair<Int, Int>>()
        val overlay = ResizeOverlay(RuntimeEnvironment.getApplication(), { 100 }, { 100 },
            { height, width -> changes += height to width })
        overlay.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY))
        overlay.layout(0, 0, 600, 300)
        val handle = overlay.findViewById<View>(R.id.resize_height)
        val x = (handle.left + handle.right) / 2f
        val y = (handle.top + handle.bottom) / 2f
        fun touch(action: Int, offset: Float) {
            val event = MotionEvent.obtain(0, 0, action, x, y + offset, 0)
            try { assertTrue(overlay.dispatchTouchEvent(event)) } finally { event.recycle() }
        }

        touch(MotionEvent.ACTION_DOWN, 0f)
        touch(MotionEvent.ACTION_MOVE, 60f)
        assertEquals(0.8f, overlay.scaleY, 0.01f)
        assertTrue(changes.isEmpty())
        touch(MotionEvent.ACTION_CANCEL, 60f)
        assertEquals(1f, overlay.scaleY, 0f)
        assertTrue(changes.isEmpty())

        touch(MotionEvent.ACTION_DOWN, 0f)
        touch(MotionEvent.ACTION_MOVE, 60f)
        touch(MotionEvent.ACTION_UP, 60f)
        assertEquals(listOf(80 to 100), changes)
    }

    @Test fun `space arrows and accessibility hint follow language swipe setting`() {
        val keyboard = KeyboardView(RuntimeEnvironment.getApplication(), KeyboardController({ null }, {}, {}, {}, {}))
        keyboard.bind(EditorProfile(), KeyboardSettings())
        val spaces = buttons(keyboard).filter { it.id == R.id.key_space }.map { it as HintKeyButton }
        assertTrue(spaces.isNotEmpty())
        assertTrue(spaces.all { it.languageArrows && it.contentDescription.toString().contains("좌우") })
        keyboard.bind(EditorProfile(), KeyboardSettings(spaceLanguageSwipe = false))
        assertTrue(buttons(keyboard).filter { it.id == R.id.key_space }.all { !(it as HintKeyButton).languageArrows })
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `space hold dims the keys and clears on release and cancellation in both layouts`() {
        val settings = KeyboardSettings(portraitMode = 0, landscapeMode = 1)
        for ((qualifiers, width) in listOf("w600dp-h960dp-port-mdpi" to 600, "w960dp-h600dp-land-mdpi" to 960)) {
            RuntimeEnvironment.setQualifiers(qualifiers)
            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            val keyboard = KeyboardView(activity, KeyboardController({ null }, {}, {}, {}, {}))
            keyboard.bind(EditorProfile(), settings)
            attachKeyboard(activity, keyboard, width)
            val status = activity.getString(R.string.cursor_mode_active)
            val spaces = buttons(keyboard).filter { it.id == R.id.key_space }
            assertEquals(if (width == 600) 1 else 2, spaces.size)
            val normal = drawKeyboardPixel(keyboard)
            for (endAction in listOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) {
                val (x, y) = keyCenter(keyboard, spaces.last())
                val down = SystemClock.uptimeMillis()
                touch(keyboard, down, MotionEvent.ACTION_DOWN, x, y)
                assertNull(spaces.last().stateDescription)
                shadowOf(Looper.getMainLooper()).idleFor(settings.longPressMillis.toLong() + 1, TimeUnit.MILLISECONDS)
                assertTrue(spaces.all { it.stateDescription == status })
                assertNotEquals(normal, drawKeyboardPixel(keyboard))
                touch(keyboard, down, MotionEvent.ACTION_MOVE, x - 28, y)
                assertEquals(status, spaces.last().stateDescription)
                touch(keyboard, down, endAction, x - 28, y)
                assertTrue(spaces.all { it.stateDescription == null })
                assertEquals(normal, drawKeyboardPixel(keyboard))
            }
        }
    }

    @Test fun `held Shift and space select with independent pointers in both layouts`() {
        val settings = KeyboardSettings(portraitMode = 0, landscapeMode = 1)
        for ((qualifiers, width) in listOf("w600dp-h960dp-port-mdpi" to 600, "w960dp-h600dp-land-mdpi" to 960)) {
            RuntimeEnvironment.setQualifiers(qualifiers)
            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            val editor = android.widget.EditText(activity).apply {
                setText("가👨‍👩‍👧‍👦나"); setSelection(text.length)
            }
            val connection = editor.onCreateInputConnection(android.view.inputmethod.EditorInfo())!!
            val session = EditorSession(connection, EditorProfile()).apply { startCursor(editor.length()) }
            lateinit var keyboard: KeyboardView
            val controller = KeyboardController({ session }, {}, {}, {}, { keyboard.refresh() })
            keyboard = KeyboardView(activity, controller)
            keyboard.bind(EditorProfile(), settings)
            attachKeyboard(activity, keyboard, width)
            val shift = keyCenter(keyboard, buttons(keyboard).first { it.id == R.id.key_shift })
            val space = keyCenter(keyboard, buttons(keyboard).last { it.id == R.id.key_space })
            val down = SystemClock.uptimeMillis()
            fun pointers(action: Int, points: List<Pair<Float, Float>>) {
                val properties = points.indices.map { index -> MotionEvent.PointerProperties().apply {
                    id = index; toolType = MotionEvent.TOOL_TYPE_FINGER
                } }.toTypedArray()
                val coordinates = points.map { (x, y) -> MotionEvent.PointerCoords().apply {
                    this.x = x; this.y = y; pressure = 1f; size = 1f
                } }.toTypedArray()
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, points.size,
                    properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
                try { assertTrue(keyboard.dispatchTouchEvent(event)) } finally { event.recycle() }
            }
            val second = 1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT
            pointers(MotionEvent.ACTION_DOWN, listOf(shift))
            pointers(MotionEvent.ACTION_POINTER_DOWN or second, listOf(shift, space))
            shadowOf(Looper.getMainLooper()).idleFor(settings.longPressMillis.toLong() + 1, TimeUnit.MILLISECONDS)
            pointers(MotionEvent.ACTION_MOVE, listOf(shift, space.first - 28 to space.second))
            assertEquals("👨‍👩‍👧‍👦나", connection.getSelectedText(0).toString())
            pointers(MotionEvent.ACTION_MOVE, listOf(shift, space.first - 14 to space.second))
            assertEquals("나", connection.getSelectedText(0).toString())
            pointers(MotionEvent.ACTION_POINTER_UP or second, listOf(shift, space.first - 14 to space.second))
            pointers(MotionEvent.ACTION_UP, listOf(shift))
            assertFalse(controller.shiftHeld)
            assertEquals(ShiftState.OFF, controller.shift)
            controller.press(KeySpec("1", KeyCommand.CHARACTER))
            assertEquals("가👨‍👩‍👧‍👦1", editor.text.toString())
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `quick space swipes animate the label and settle on the new language`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        lateinit var keyboard: KeyboardView
        val controller = KeyboardController({ null }, {}, {}, {}, { keyboard.refresh() })
        keyboard = KeyboardView(activity, controller)
        keyboard.bind(EditorProfile(), KeyboardSettings())
        attachKeyboard(activity, keyboard, 411)
        ShadowChoreographer.setPaused(true)
        for ((offset, expectedLabel) in listOf(55 to "English", -55 to "한국어")) {
            val (x, y) = keyCenter(keyboard, keyboard.findViewById(R.id.key_space))
            val down = SystemClock.uptimeMillis()
            touch(keyboard, down, MotionEvent.ACTION_DOWN, x, y)
            touch(keyboard, down, MotionEvent.ACTION_MOVE, x + offset, y)
            touch(keyboard, down, MotionEvent.ACTION_UP, x + offset, y)
            val space = keyboard.findViewById<HintKeyButton>(R.id.key_space)
            assertEquals(expectedLabel, space.text.toString())
            layoutKeyboard(keyboard, 411)
            shadowOf(Looper.getMainLooper()).idleFor(60, TimeUnit.MILLISECONDS)
            val animated = drawView(space)
            shadowOf(Looper.getMainLooper()).idleFor(240, TimeUnit.MILLISECONDS)
            val settled = drawView(space)
            try {
                assertFalse(animated.sameAs(settled))
            } finally {
                animated.recycle()
                settled.recycle()
            }
        }
    }

    @Test
    fun `space swipe sensitivity changes required distance`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        lateinit var keyboard: KeyboardView
        val controller = KeyboardController({ null }, {}, {}, {}, { keyboard.refresh() })
        keyboard = KeyboardView(activity, controller)
        keyboard.bind(EditorProfile(), KeyboardSettings(spaceLanguageSensitivity = 0))
        attachKeyboard(activity, keyboard, 411)
        fun swipe(offset: Int) {
            val (x, y) = keyCenter(keyboard, keyboard.findViewById(R.id.key_space))
            val down = SystemClock.uptimeMillis()
            touch(keyboard, down, MotionEvent.ACTION_DOWN, x, y)
            touch(keyboard, down, MotionEvent.ACTION_MOVE, x + offset, y)
            touch(keyboard, down, MotionEvent.ACTION_UP, x + offset, y)
        }
        swipe(30)
        assertEquals("한국어", keyboard.findViewById<HintKeyButton>(R.id.key_space).text.toString())
        keyboard.bind(EditorProfile(), KeyboardSettings(spaceLanguageSensitivity = 100))
        layoutKeyboard(keyboard, 411)
        swipe(30)
        assertEquals("English", keyboard.findViewById<HintKeyButton>(R.id.key_space).text.toString())
    }

    private fun attachKeyboard(activity: Activity, keyboard: KeyboardView, width: Int) {
        activity.setContentView(FrameLayout(activity).apply {
            addView(keyboard, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        shadowOf(Looper.getMainLooper()).idleFor(50, TimeUnit.MILLISECONDS)
        layoutKeyboard(keyboard, width)
    }

    private fun layoutKeyboard(keyboard: KeyboardView, width: Int) {
        keyboard.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.AT_MOST))
        keyboard.layout(0, 0, width, keyboard.measuredHeight)
    }

    private fun keyCenter(keyboard: KeyboardView, key: View): Pair<Float, Float> {
        val bounds = Rect(0, 0, key.width, key.height)
        keyboard.offsetDescendantRectToMyCoords(key, bounds)
        return bounds.exactCenterX() to bounds.exactCenterY()
    }

    private fun touch(keyboard: KeyboardView, down: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { assertTrue(keyboard.dispatchTouchEvent(event)) } finally { event.recycle() }
    }

    private fun drawView(view: View): Bitmap =
        Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }

    private fun drawKeyboardPixel(keyboard: KeyboardView): Int {
        val bitmap = drawView(keyboard)
        return try { bitmap.getPixel(0, 0) } finally { bitmap.recycle() }
    }

    @Test
    @Config(qualifiers = "w640dp-h1000dp-port-mdpi")
    fun `narrowing a wide keyboard preserves its height in both orientations`() {
        val keyboard = KeyboardView(RuntimeEnvironment.getApplication(), KeyboardController({ null }, {}, {}, {}, {}))
        val settings = KeyboardSettings(narrowHeight = 40, wideHeight = 62)
        fun layout(width: Int, value: KeyboardSettings): Int {
            keyboard.bind(EditorProfile(), value)
            keyboard.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.AT_MOST))
            keyboard.layout(0, 0, keyboard.measuredWidth, keyboard.measuredHeight)
            return keyboard.height
        }

        val fullHeight = layout(640, settings)
        assertEquals(fullHeight, layout(576, settings.copy(portraitWidthPercent = 90)))
        assertEquals(576, keyboard.width)
        assertTrue(layout(576, settings.copy(portraitWidthPercent = 90, portraitHeightPercent = 90)) < fullHeight)

        RuntimeEnvironment.setQualifiers("w920dp-h700dp-land-mdpi")
        val landscapeHeight = layout(920, settings)
        assertEquals(landscapeHeight, layout(552, settings.copy(landscapeWidthPercent = 60)))
        assertEquals(552, keyboard.width)
        assertTrue(layout(552, settings.copy(landscapeWidthPercent = 60, landscapeHeightPercent = 90)) < landscapeHeight)
    }

    @Test fun `number row height is independent in portrait and split landscape`() {
        val settings = KeyboardSettings(landscapeMode = 1)
        for ((qualifiers, width) in listOf("w411dp-h891dp-port-mdpi" to 411, "w960dp-h600dp-land-mdpi" to 960)) {
            RuntimeEnvironment.setQualifiers(qualifiers)
            val controller = KeyboardController({ null }, {}, {}, {}, {})
            val keyboard = KeyboardView(RuntimeEnvironment.getApplication(), controller)
            fun bind(value: KeyboardSettings, layout: InputLayout = InputLayout.TEXT) {
                keyboard.bind(EditorProfile(layout = layout), value)
                layoutKeyboard(keyboard, width)
            }
            fun keyHeight(id: Int) = keyboard.findViewById<View>(id).height

            bind(settings)
            val letterHeight = keyHeight(R.id.key_q)
            val numberHeight = keyHeight(R.id.key_1)
            assertEquals(letterHeight, numberHeight)
            bind(settings.copy(numberRowHeightPercent = 70))
            assertTrue(keyHeight(R.id.key_1) < numberHeight)
            assertEquals(letterHeight, keyHeight(R.id.key_q))
            bind(settings.copy(numberRowHeightPercent = 130))
            assertTrue(keyHeight(R.id.key_1) > numberHeight)
            assertEquals(letterHeight, keyHeight(R.id.key_q))
            val hidden = settings.copy(numberRow = false, numberRowHeightPercent = 70)
            bind(hidden)
            assertNull(keyboard.findViewById<View>(R.id.key_1))

            controller.press(KeySpec("", KeyCommand.SYMBOLS))
            bind(hidden)
            val shortSymbolNumberHeight = keyHeight(R.id.key_1)
            bind(hidden.copy(numberRowHeightPercent = 100))
            assertTrue(keyHeight(R.id.key_1) > shortSymbolNumberHeight)

            bind(settings, InputLayout.NUMBER)
            val keypadHeight = keyHeight(R.id.key_1)
            bind(settings.copy(numberRowHeightPercent = 70), InputLayout.NUMBER)
            assertEquals(keypadHeight, keyHeight(R.id.key_1))
        }
    }

    @Test fun `key gaps change independently without changing keyboard size in both orientations`() {
        for ((qualifiers, width) in listOf("w411dp-h891dp-port-mdpi" to 411, "w960dp-h600dp-land-mdpi" to 960)) {
            RuntimeEnvironment.setQualifiers(qualifiers)
            val keyboard = KeyboardView(RuntimeEnvironment.getApplication(), KeyboardController({ null }, {}, {}, {}, {}))
            for (split in listOf(false, true)) {
                val settings = KeyboardSettings(narrowSplit = split, wideSplit = split)
                fun bind(horizontal: Int, vertical: Int) {
                    keyboard.bind(EditorProfile(), settings.copy(horizontalKeyGap = horizontal, verticalKeyGap = vertical))
                    layoutKeyboard(keyboard, width)
                }
                fun bounds(id: Int) = keyboard.findViewById<View>(id).let { key ->
                    Rect(0, 0, key.width, key.height).also { keyboard.offsetDescendantRectToMyCoords(key, it) }
                }
                bind(6, 6)
                val height = keyboard.height
                val keyHeight = bounds(R.id.key_q).height()
                for ((horizontal, vertical) in listOf(0 to 0, 12 to 0, 0 to 12, 11 to 3, 12 to 12)) {
                    bind(horizontal, vertical)
                    val q = bounds(R.id.key_q)
                    val w = bounds(R.id.key_w)
                    val a = bounds(R.id.key_a)
                    assertEquals(horizontal, w.left - q.right)
                    assertEquals(vertical, a.top - q.bottom)
                    assertEquals(keyHeight + 6 - vertical, q.height())
                    assertEquals(height, keyboard.height)
                    assertTrue(buttons(keyboard).all { it.width > 0 && it.height > 0 })
                    val geometryKeys = listOf(R.id.key_q, R.id.key_w, R.id.key_a).map { id ->
                        val key = keyboard.findViewById<View>(id)
                        TouchKey(key, KeySpec("x", KeyCommand.CHARACTER, "x"), bounds(id), false, 0)
                    }
                    val geometry = GeometrySnapshot(geometryKeys, horizontal, vertical)
                    if (horizontal > 0) assertEquals(geometryKeys[0], geometry.keyAt(q.right.toFloat(), q.exactCenterY(), true))
                    if (vertical > 0) {
                        val x = (maxOf(q.left, a.left) + minOf(q.right, a.right)) / 2f
                        assertEquals(geometryKeys[0], geometry.keyAt(x, q.bottom.toFloat(), true))
                        assertNull(geometry.keyAt(x, q.bottom.toFloat(), false))
                    }
                }
            }
        }
    }

    @Test fun `small control labels accept the minimum size and grow again`() {
        val keyboard = KeyboardView(RuntimeEnvironment.getApplication(), KeyboardController({ null }, {}, {}, {}, {}))
        val settings = KeyboardSettings()
        for ((percent, expected) in listOf(80 to 12, 100 to 14, 90 to 12, 85 to 12, 80 to 12, 95 to 13, 100 to 14)) {
            keyboard.bind(EditorProfile(), settings.copy(fontPercent = percent))
            assertEquals("font size $percent%", expected, keyboard.findViewById<Button>(R.id.key_language).autoSizeMaxTextSize)
        }
    }

    @Test fun `ordinary letters share exactly one size across rows and split halves`() {
        val controller = KeyboardController({ null }, {}, {}, {}, {})
        val keyboard = KeyboardView(RuntimeEnvironment.getApplication(), controller)
        val ids = "qwertyuiopasdfghjklzxcvbnm".map { KeyboardKeyIds.of(KeySpec(it.toString(), KeyCommand.CHARACTER, letter = true)) }.toSet()
        for (width in listOf(600, 960, 1500)) for (split in listOf(false, true)) {
            keyboard.bind(EditorProfile(), KeyboardSettings(wideSplit = split, fontPercent = 125))
            repeat(2) {
                keyboard.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.AT_MOST))
                keyboard.layout(0, 0, width, keyboard.measuredHeight)
            }
            val letters = buttons(keyboard).filter { it.id in ids }
            assertEquals(26, letters.size)
            assertEquals("width=$width split=$split", 1, letters.map { it.width }.distinct().size)
            assertEquals(1, letters.map { it.height }.distinct().size)
            assertEquals(1, letters.map { it.textSize }.distinct().size)
        }
    }

    @Test fun `split keys stay near outer edges with proportional controls and usable spaces`() {
        for ((qualifiers, width) in listOf("w600dp-h960dp-port-mdpi" to 600, "w960dp-h600dp-land-mdpi" to 960)) {
            RuntimeEnvironment.setQualifiers(qualifiers)
            for (extended in listOf(false, true)) for (profile in listOf(EditorProfile(), EditorProfile(email = true), EditorProfile(uri = true))) {
                val controller = KeyboardController({ null }, {}, {}, {}, {})
                val keyboard = KeyboardView(RuntimeEnvironment.getApplication(), controller)
                val settings = KeyboardSettings(portraitMode = 1, landscapeMode = 1, extendedLayout = extended)
                for (page in 0..2) {
                    if (page > 0) controller.press(KeySpec("", if (page == 1) KeyCommand.SYMBOLS else KeyCommand.MORE_SYMBOLS))
                    keyboard.bind(profile, settings)
                    keyboard.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.AT_MOST))
                    keyboard.layout(0, 0, width, keyboard.measuredHeight)
                    val rows = KeyboardLayouts.rows(controller, profile, "줄바꿈", true, keyboard.extendedActive)
                    val firstHalf = (keyboard.getChildAt(1) as ViewGroup).getChildAt(0) as ViewGroup
                    val characterWidth = firstHalf.getChildAt(0).width
                    if (page == 0) {
                        fun left(id: Int): Int {
                            val bounds = Rect()
                            keyboard.findViewById<View>(id).getDrawingRect(bounds)
                            keyboard.offsetDescendantRectToMyCoords(keyboard.findViewById(id), bounds)
                            return bounds.left
                        }
                        assertTrue("QWERTY home row is staggered", left(R.id.key_a) > left(R.id.key_q))
                        assertTrue("QWERTY lower row is staggered", left(R.id.key_z) > left(R.id.key_a))
                        assertTrue("Right home row is staggered", left(R.id.key_h) > left(R.id.key_y))
                        assertTrue("B stays on the right half below H", left(R.id.key_b) < left(R.id.key_h))
                    }
                    assertTrue("Letters use the half's width", characterWidth > firstHalf.width / 7)
                    assertTrue("First row starts at the outside edge", firstHalf.getChildAt(0).left <= 4)
                    rows.forEachIndexed { index, specs ->
                        val line = keyboard.getChildAt(index + 1) as ViewGroup
                        val halves = listOf(line.getChildAt(0) as ViewGroup, line.getChildAt(2) as ViewGroup)
                        val views = halves.flatMap { half -> (0 until half.childCount).map { half.getChildAt(it) } }
                        assertEquals(specs.size, views.size)
                        specs.zip(views).forEach { (spec, view) ->
                            val half = view.parent as ViewGroup
                            assertTrue("Key stays inside its half", view.left >= 0 && view.right <= half.width && view.width > 0)
                            if (spec.command == KeyCommand.SPACE) assertTrue(view.width >= characterWidth)
                            else assertTrue("Controls cannot absorb leftover width", view.width <= characterWidth * 1.6f)
                        }
                        if (specs.none { it.command == KeyCommand.SPACE }) {
                            val right = halves.last()
                            assertTrue("Right row ends at the outside edge", right.width - right.getChildAt(right.childCount - 1).right <= 4)
                        }
                    }
                }
            }
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `rotation keeps character labels centered inside shorter landscape keys`() {
        val keyboard = KeyboardView(RuntimeEnvironment.getApplication(), KeyboardController({ null }, {}, {}, {}, {}))
        val profile = EditorProfile()
        val settings = KeyboardSettings(fontPercent = 140)
        fun layout(width: Int) {
            keyboard.bind(profile, settings)
            keyboard.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.AT_MOST))
            keyboard.layout(0, 0, width, keyboard.measuredHeight)
        }

        layout(411)
        val portraitHeight = buttons(keyboard).first { it.id == R.id.key_q }.height
        RuntimeEnvironment.setQualifiers("w960dp-h390dp-land-mdpi")
        layout(960)

        val sampleIds = setOf(R.id.key_q, R.id.key_w, R.id.key_a, R.id.key_comma)
        val keys = buttons(keyboard).filter { it.id in sampleIds }
        assertTrue(keys.map { it.id }.containsAll(sampleIds))
        assertEquals(1, keys.map { it.textSize }.distinct().size)
        for (key in keys) {
            assertTrue(key.height < portraitHeight)
            assertEquals(Gravity.CENTER, key.gravity)
            assertEquals(key.paddingTop, key.paddingBottom)
            val lineTop = key.baseline - key.layout.getLineBaseline(0)
            val lineBottom = lineTop + key.layout.height
            assertTrue("${key.text}: size=${key.textSize}, key height=${key.height}, line=$lineTop..$lineBottom",
                lineTop >= 0 && lineBottom <= key.height)
            assertEquals(key.height / 2f, (lineTop + lineBottom) / 2f, 1f)
        }
    }

    @Test
    @Config(shadows = [RecordingAudioManager::class])
    fun `character clicks play one keypress sound only when enabled`() {
        val context = RuntimeEnvironment.getApplication()
        val audio = Shadow.extract<RecordingAudioManager>(context.getSystemService(AudioManager::class.java))
        val keyboard = KeyboardView(context, KeyboardController({ null }, {}, {}, {}, {}))
        val settings = KeyboardSettings(vibration = false)

        keyboard.bind(EditorProfile(), settings)
        buttons(keyboard).first { it.id == R.id.key_q }.performClick()
        assertTrue(audio.effects.isEmpty())

        keyboard.bind(EditorProfile(), settings.copy(sound = true))
        buttons(keyboard).first { it.id == R.id.key_q }.performClick()
        assertEquals(listOf(AudioManager.FX_KEYPRESS_STANDARD), audio.effects)
    }

    @Test fun `key vibration follows the toggle and selected strength`() {
        val context = RuntimeEnvironment.getApplication()
        val vibrator = context.getSystemService(Vibrator::class.java)
        val shadow = shadowOf(vibrator).apply {
            setHasVibrator(true)
            setHasAmplitudeControl(false)
        }
        val keyboard = KeyboardView(context, KeyboardController({ null }, {}, {}, {}, {}))
        keyboard.bind(EditorProfile(), KeyboardSettings(vibration = false, vibrationStrength = 100))
        buttons(keyboard).first { it.id == R.id.key_q }.performClick()
        assertFalse(shadow.isVibrating)
        keyboard.bind(EditorProfile(), KeyboardSettings(vibrationStrength = 0))
        buttons(keyboard).first { it.id == R.id.key_q }.performClick()
        assertFalse(shadow.isVibrating)
        keyboard.bind(EditorProfile(), KeyboardSettings(vibrationStrength = 100))
        buttons(keyboard).first { it.id == R.id.key_q }.performClick()
        assertEquals(60L, shadow.milliseconds)
    }

    @Implements(AudioManager::class)
    class RecordingAudioManager : ShadowAudioManager() {
        val effects = mutableListOf<Int>()

        @Implementation
        protected fun playSoundEffect(effect: Int) {
            effects += effect
        }
    }

    private fun buttons(root: ViewGroup): List<Button> = (0 until root.childCount).flatMap {
        val child = root.getChildAt(it)
        when (child) { is Button -> listOf(child); is ViewGroup -> buttons(child); else -> emptyList() }
    }

    @Test fun `functional keys remain visible at narrow and wide sizes`() {
        val context = RuntimeEnvironment.getApplication()
        var current: KeyboardView? = null
        val controller = KeyboardController({ null }, {}, {}, {}, { current?.refresh() })
        val keyboard = KeyboardView(context, controller).also { current = it }
        for (width in listOf(280, 411, 800, 1200)) {
            keyboard.bind(EditorProfile(), KeyboardSettings(wideSplit = true))
            keyboard.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.AT_MOST))
            keyboard.layout(0, 0, width, keyboard.measuredHeight)
            keyboard.refresh()
            keyboard.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.AT_MOST))
            keyboard.layout(0, 0, width, keyboard.measuredHeight)
            val keys = buttons(keyboard)
            listOf(R.string.key_delete, R.string.key_space, R.string.key_language, R.string.key_symbols).forEach { label ->
                assertTrue("width=$width, label=${context.getString(label)}, keys=${keys.map { Triple(it.contentDescription, it.width, it.height) }}",
                    keys.any { it.contentDescription.toString().startsWith(context.getString(label)) && it.width > 0 })
            }
            assertTrue(keys.all { it.width > 0 && it.height > 0 })
        }
    }

    @Test fun `accessibility shift clicks update labels and state`() {
        val context = RuntimeEnvironment.getApplication()
        var current: KeyboardView? = null
        val controller = KeyboardController({ null }, {}, {}, {}, { current?.refresh() })
        val keyboard = KeyboardView(context, controller).also { current = it }
        keyboard.bind(EditorProfile(), KeyboardSettings())
        val shift = buttons(keyboard).first { it.contentDescription == context.getString(R.string.key_shift_off) }
        shift.performClick()
        assertTrue(buttons(keyboard).any { it.text == "ㅃ" })
        assertEquals(context.getString(R.string.key_shift_once), shift.contentDescription)
    }

    @Test
    @Config(qualifiers = "w600dp-h960dp-port-mdpi")
    fun `split layout uses the orientation gap and leaves room for keys on narrower widths`() {
        val context = RuntimeEnvironment.getApplication()
        val controller = KeyboardController({ null }, {}, {}, {}, {})
        val keyboard = KeyboardView(context, controller)
        val settings = KeyboardSettings(narrowSplit = true, wideSplit = true, portraitSplitGap = 200, landscapeSplitGap = 320)
        for ((orientation, request) in listOf("port" to 200, "land" to 320)) {
            RuntimeEnvironment.setQualifiers("+$orientation")
            for (width in listOf(480, 600, 1000)) {
                keyboard.bind(EditorProfile(), settings)
                keyboard.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.AT_MOST))
                keyboard.layout(0, 0, width, keyboard.measuredHeight)
                val keys = buttons(keyboard)
                assertEquals(2, keys.count { it.id == R.id.key_space })
                val row = keys.first { it.id == R.id.key_t }.parent.parent as ViewGroup
                assertEquals(minOf(request, (width - 8) / 3), row.getChildAt(1).width)
                assertTrue(keys.all { it.width > 0 })
            }
        }
    }

    @Test fun `numeric keypad aligns zero and the action column across optional symbols and split mode`() {
        val keyboard = KeyboardView(RuntimeEnvironment.getApplication(), KeyboardController({ null }, {}, {}, {}, {}))
        fun column(id: Int): Pair<Int, Int> {
            val key = keyboard.findViewById<View>(id)
            val bounds = android.graphics.Rect(0, 0, key.width, key.height)
            keyboard.offsetDescendantRectToMyCoords(key, bounds)
            return bounds.left to bounds.right
        }
        val profiles = listOf(
            EditorProfile(layout = InputLayout.NUMBER),
            EditorProfile(layout = InputLayout.NUMBER, decimal = true),
            EditorProfile(layout = InputLayout.NUMBER, signed = true),
            EditorProfile(layout = InputLayout.NUMBER, signed = true, decimal = true),
            EditorProfile(layout = InputLayout.PHONE),
            EditorProfile(layout = InputLayout.DATETIME),
        )
        for (profile in profiles) for (split in listOf(false, true)) {
            val width = 601
            keyboard.bind(profile, KeyboardSettings(portraitMode = if (split) 1 else 0))
            keyboard.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.AT_MOST))
            keyboard.layout(0, 0, width, keyboard.measuredHeight)
            assertEquals(column(R.id.key_2), column(R.id.key_0))
            assertEquals(column(R.id.key_5), column(R.id.key_8))
            assertEquals(column(R.id.key_delete), column(R.id.key_enter))
            assertEquals(column(R.id.key_delete), column(R.id.key_space))
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `toolbar and side margins use the keyboard surface color`() {
        val context = RuntimeEnvironment.getApplication()
        val keyboard = KeyboardView(context, KeyboardController({ null }, {}, {}, {}, {}))
        val frame = KeyboardFrame(context).apply {
            addView(keyboard, FrameLayout.LayoutParams(480, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        }
        for ((theme, highContrast) in listOf("notnight" to false, "night" to false, "night" to true)) {
            RuntimeEnvironment.setQualifiers("+$theme")
            keyboard.bind(EditorProfile(), KeyboardSettings(highContrast = highContrast))
            frame.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.AT_MOST))
            frame.layout(0, 0, 600, frame.measuredHeight)
            assertEquals(60, keyboard.left)
            assertEquals(540, keyboard.right)
            val bitmap = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
            frame.draw(Canvas(bitmap))
            val surface = (keyboard.background as ColorDrawable).color
            assertEquals(surface, bitmap.getPixel(0, frame.height / 2))
            assertEquals(surface, bitmap.getPixel(frame.width - 1, frame.height / 2))
            val toolbar = keyboard.getChildAt(0)
            val toolbarCenterY = keyboard.top + toolbar.top + toolbar.height / 2
            assertEquals(surface, bitmap.getPixel(frame.width / 2, toolbarCenterY))
            bitmap.recycle()
        }
    }

    @Test fun `email and URL expose period alongside their contextual punctuation`() {
        val controller = KeyboardController({ null }, {}, {}, {}, {})
        for ((profile, special) in listOf(EditorProfile(email = true) to "@", EditorProfile(uri = true) to "/")) {
            for (split in listOf(false, true)) {
                val keys = KeyboardLayouts.rows(controller, profile, "완료", split).last()
                assertTrue(keys.any { it.command == KeyCommand.CHARACTER && it.value == special })
                assertTrue(keys.any { it.command == KeyCommand.CHARACTER && it.value == "." })
            }
        }
    }

    @Test fun `extended layout has desktop editing keys and sensible split boundaries`() {
        val controller = KeyboardController({ null }, {}, {}, {}, {})
        val rows = KeyboardLayouts.rows(controller, EditorProfile(), "줄바꿈", split = true, extended = true)
        assertTrue(rows.first().any { it.command == KeyCommand.DELETE_FORWARD })
        assertEquals(KeyCommand.DELETE, rows[1].last().command)
        assertEquals(KeyCommand.ENTER, rows[2].last().command)
        assertEquals(2, rows[3].count { it.command == KeyCommand.SHIFT })
        assertTrue(rows[1][5].splitBefore)
        assertEquals(2, rows.last().count { it.command == KeyCommand.SPACE })
        assertTrue(rows.last().any { it.command == KeyCommand.CURSOR_LEFT })
        assertTrue(rows.last().any { it.command == KeyCommand.CURSOR_RIGHT })
        val noNumbers = KeyboardLayouts.rows(controller, EditorProfile(), "줄바꿈", extended = true, numberRow = false)
        assertEquals(4, noNumbers.size)
    }

    @Test fun `symbol pages follow the extended layout width and preference`() {
        val layouts = listOf(
            Triple("w600dp-h960dp-port-mdpi", 600, true),
            Triple("w960dp-h600dp-land-mdpi", 960, true),
            Triple("w960dp-h600dp-land-mdpi", 960, false),
        )
        for ((qualifiers, width, enabled) in layouts) {
            RuntimeEnvironment.setQualifiers(qualifiers)
            val controller = KeyboardController({ null }, {}, {}, {}, {})
            val keyboard = KeyboardView(RuntimeEnvironment.getApplication(), controller)
            controller.press(KeySpec("", KeyCommand.SYMBOLS))
            for (page in 1..2) {
                keyboard.bind(EditorProfile(), KeyboardSettings(extendedLayout = enabled))
                keyboard.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.AT_MOST))
                keyboard.layout(0, 0, width, keyboard.measuredHeight)
                val keys = buttons(keyboard).filter { it.visibility == View.VISIBLE }
                assertTrue(keys.all { it.width > 0 && it.height > 0 })
                val extended = width >= 840 && enabled
                fun rowOf(id: Int) = keys.first { it.id == id }.parent as ViewGroup
                fun rowText(id: Int) = buttons(rowOf(id)).map { it.text.toString() }
                val pageKeys = keys.filter { it.id == R.id.key_more_symbols }
                assertEquals(List(if (extended) 2 else 1) { "$page/2" }, pageKeys.map { it.text.toString() })
                if (extended) {
                    assertEquals(rowOf(R.id.key_1), rowOf(R.id.key_delete_forward))
                    assertTrue(rowOf(R.id.key_delete).top < rowOf(R.id.key_more_symbols).top)
                    assertTrue(rowOf(R.id.key_enter).top < rowOf(R.id.key_more_symbols).top)
                } else {
                    assertEquals("1234567890".map(Char::toString), rowText(R.id.key_1))
                    assertFalse(keys.any { it.id == R.id.key_delete_forward })
                    assertEquals(rowOf(R.id.key_more_symbols), rowOf(R.id.key_delete))
                    assertEquals(rowOf(R.id.key_space), rowOf(R.id.key_enter))
                    assertEquals(listOf(R.id.key_letters, R.id.key_comma, R.id.key_space, R.id.key_period, R.id.key_enter),
                        buttons(rowOf(R.id.key_space)).map { it.id })
                    if (page == 1) {
                        val row = keyboard.getChildAt(3) as ViewGroup
                        assertEquals("!@#$%^&*()".map(Char::toString), buttons(row).map { it.text.toString() })
                    } else assertTrue(keys.any { it.text.toString() == "₩" })
                }
                controller.press(KeySpec("", KeyCommand.MORE_SYMBOLS))
            }
        }
    }

    @Test fun `orientation override is independent of width defaults`() {
        val settings = KeyboardSettings(wideSplit = false, narrowSplit = false, portraitMode = 0, landscapeMode = 1)
        assertFalse(settings.splitFor(wide = true, landscape = false))
        assertTrue(settings.splitFor(wide = false, landscape = true))
        assertFalse(settings.copy(landscapeMode = -1).splitFor(wide = true, landscape = true))
    }

    @Test fun `punctuation exposes hints and long click access only when secondary input is enabled`() {
        val context = RuntimeEnvironment.getApplication()
        val controller = KeyboardController({ null }, {}, {}, {}, {})
        val keyboard = KeyboardView(context, controller)
        keyboard.bind(EditorProfile(), KeyboardSettings())
        val comma = buttons(keyboard).first { it.id == R.id.key_comma } as HintKeyButton
        val period = buttons(keyboard).first { it.id == R.id.key_period } as HintKeyButton
        assertEquals("!", comma.secondaryLabel)
        assertEquals("?", period.secondaryLabel)
        assertTrue(comma.isLongClickable)
        assertTrue(period.isLongClickable)
        assertTrue(comma.contentDescription.toString().contains("!"))
        keyboard.bind(EditorProfile(), KeyboardSettings(secondaryCharacters = false))
        val disabled = buttons(keyboard).first { it.id == R.id.key_comma } as HintKeyButton
        assertNull(disabled.secondaryLabel)
        assertFalse(disabled.isLongClickable)
    }
}

