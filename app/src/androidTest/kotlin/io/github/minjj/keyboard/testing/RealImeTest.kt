package io.github.minjj.keyboard.testing

import android.content.Intent
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Point
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.os.SystemClock
import android.text.InputType
import android.util.TypedValue
import android.util.AtomicFile
import android.view.InputDevice
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
import android.widget.TextView
import android.widget.Spinner
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.UiScrollable
import androidx.test.uiautomator.UiSelector
import androidx.test.uiautomator.Until
import io.github.minjj.keyboard.settings.KeyboardSettings
import io.github.minjj.keyboard.settings.InputPracticeActivity
import io.github.minjj.keyboard.settings.TouchLearningActivity
import io.github.minjj.keyboard.hangul.Dubeolsik
import io.github.minjj.keyboard.touch.KeyIds
import io.github.minjj.keyboard.touch.PHYSICAL_LETTERS
import io.github.minjj.keyboard.settings.LayoutSettingsActivity
import io.github.minjj.keyboard.settings.OptionsSettingsActivity
import io.github.minjj.keyboard.settings.SettingsActivity
import io.github.minjj.keyboard.settings.SettingsRepository
import io.github.minjj.keyboard.R
import io.github.minjj.keyboard.correction.SpatialStatsRepository
import io.github.minjj.keyboard.correction.spatialDay
import io.github.minjj.keyboard.ime.MinjjImeService
import io.github.minjj.keyboard.touch.SpatialModel
import io.github.minjj.keyboard.touch.TouchSample
import io.github.minjj.keyboard.ui.KeyboardView
import io.github.minjj.keyboard.ui.TouchDiagnostic
import io.github.minjj.keyboard.ui.TouchDiagnosticObserver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.json.JSONTokener
import org.json.JSONArray
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Interacts with the real IME window. Only fixture content in the debug Activity is inspected. */
@RunWith(AndroidJUnit4::class)
class RealImeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private lateinit var activity: ImeTestActivity
    private lateinit var originalSettings: KeyboardSettings
    private val packageName = "io.github.minjj.keyboard"
    private val runLabel = InstrumentationRegistry.getArguments().getString("artifactRun", "manual")
        .also { require(it.matches(Regex("[A-Za-z0-9_-]+"))) }
    private val output get() = File(instrumentation.targetContext.getExternalFilesDir(null), "device-tests/$runLabel").apply { mkdirs() }
    private var activeTestName = "setup"
    private var liveFailureCaptured = false

    @get:Rule val failures = object : TestWatcher() {
        override fun starting(description: Description) { activeTestName = description.methodName; liveFailureCaptured = false }
        override fun failed(e: Throwable?, description: Description) {
            if (liveFailureCaptured) return
            if (safeFailureCapture()) {
                device.takeScreenshot(File(output, "failed-${description.methodName}.png"))
                device.dumpWindowHierarchy(File(output, "failed-${description.methodName}.xml"))
            }
        }
    }

    @Before fun setup() {
        check(output.isDirectory)
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        Configurator.getInstance().waitForIdleTimeout = 100
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        cancelInjectedTouches()
        await("Device requires manual authentication to unlock") { !instrumentation.targetContext
            .getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked }
        device.setOrientationNatural()
        device.executeShellCommand("ime enable $packageName/.ime.MinjjImeService")
        device.executeShellCommand("ime set $packageName/.ime.MinjjImeService")
        activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, ImeTestActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as ImeTestActivity
        originalSettings = runBlocking { SettingsRepository(activity).settings.first() }
        runBlocking { SettingsRepository(activity).restoreSettings(KeyboardSettings()) }
        native()
        korean()
    }

    @After fun cleanup() {
        cancelInjectedTouches()
        if (::originalSettings.isInitialized) runBlocking {
            val repository = SettingsRepository(instrumentation.targetContext)
            repository.restoreSettings(originalSettings)
        }
        device.setOrientationNatural()
        // Keep the fixture visible so TestWatcher can capture failures after @After.
        // The next test starts with CLEAR_TASK and replaces it.
    }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun native(type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
        action: Int = EditorInfo.IME_ACTION_NONE, value: String = "") {
        await("Fixture window focus and layout") {
            var ready = false
            main { ready = activity.hasWindowFocus() && activity.primary.isLaidOut && activity.primary.width > 0 }
            ready
        }
        main { activity.configure(type, action, value) }
        instrumentation.waitForIdleSync()
        await("Editor input connection active") {
            var active = false
            main { active = activity.getSystemService(InputMethodManager::class.java).isActive(activity.primary) }
            active
        }
        main { activity.getSystemService(InputMethodManager::class.java).showSoftInput(activity.primary, 0) }
        var keyboardVisible = false
        main { keyboardVisible = WindowInspector.getGlobalWindowViews().any { it.findViewById<View>(R.id.key_delete)?.isShown == true } }
        if (!keyboardVisible) {
            // A fresh instrumentation launch can suppress an implicit show. Use an actual tap
            // only while the keyboard is absent, never against a transitioning keyboard surface.
            val location = IntArray(2); var center = Point()
            main {
                activity.primary.getLocationOnScreen(location)
                center = Point(location[0] + activity.primary.width / 2, location[1] + activity.primary.height / 2)
            }
            device.click(center.x, center.y)
            main { activity.getSystemService(InputMethodManager::class.java).showSoftInput(activity.primary, 0) }
        }
        var marker = ""
        main { marker = activity.primary.privateImeOptions }
        await("IME connected to current fixture editor") {
            var bound = false
            main {
                for (root in WindowInspector.getGlobalWindowViews()) {
                    val view = root.findViewById<View>(R.id.key_delete) ?: continue
                    val service = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
                        .filterIsInstance<MinjjImeService>().firstOrNull() ?: continue
                    if (service.currentInputEditorInfo?.privateImeOptions == marker) bound = true
                }
            }
            bound
        }
        point("delete")
        awaitImeAnimation()
        val delete = point("delete")
        val offset = key("delete").visibleBounds.centerY() - delete.y
        if (kotlin.math.abs(offset) <= 1) return

        // The fixture can inherit a displaced IME surface when the control target changes.
        File(output, "ime-position.txt").appendText("test=$activeTestName offsetBeforeReopen=$offset\n")
        val manager = activity.getSystemService(InputMethodManager::class.java)
        main { manager.hideSoftInputFromWindow(activity.primary.windowToken, 0) }
        await("IME hidden before requesting a fresh show") { !device.hasObject(By.res(packageName, "key_delete")) }
        awaitImeAnimation()
        main { manager.showSoftInput(activity.primary, 0) }
        point("delete")
        awaitImeAnimation()
        val reopened = point("delete")
        val reopenedOffset = key("delete").visibleBounds.centerY() - reopened.y
        File(output, "ime-position.txt").appendText("test=$activeTestName offsetAfterReopen=$reopenedOffset\n")
        assertTrue("IME surface aligned after reopening: $reopenedOffset px", kotlin.math.abs(reopenedOffset) <= 1)
    }

    /** Observe the first show after changing apps; native() deliberately repairs misalignment. */
    private fun withLearningFixture(block: (SpatialStatsRepository) -> Unit) {
        lateinit var repository: SpatialStatsRepository
        var previous: SpatialStatsRepository? = null
        val fixtureFile = File.createTempFile("guided-device-fixture", ".stats", activity.cacheDir)
        main {
            repository = SpatialStatsRepository(AtomicFile(fixtureFile))
            previous = SpatialStatsRepository.replaceForTests(repository)
        }
        await("Isolated learning storage ready") { repository.ready }
        try { block(repository) } finally {
            main { SpatialStatsRepository.replaceForTests(previous) }
            activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext, ImeTestActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as ImeTestActivity
            fixtureFile.delete(); File(fixtureFile.path + ".bak").delete()
        }
    }

    private fun learningPage(korean: Boolean): TouchLearningActivity {
        val page = instrumentation.startActivitySync(Intent(instrumentation.targetContext, TouchLearningActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as TouchLearningActivity
        main { page.findViewById<View>(if (korean) R.id.learning_tab_ko else R.id.learning_tab_en).performClick() }
        await("Learning screen ready") {
            var ready = false; main { ready = page.findViewById<View>(R.id.learning_start).isEnabled }; ready
        }
        main { page.findViewById<View>(R.id.learning_start).performClick() }
        await("Dedicated keyboard connected") {
            var ready = false
            main { ready = page.learningSession?.let { it.connected && it.layout != null } == true }
            ready
        }
        await("Learning IME animation settled") { var settled = false; main { settled = page.readyForTyping }; settled }
        main {
            assertFalse("Initial word rejected: ${page.learningSession?.rejectionReason}", page.learningSession?.invalid == true)
            assertEquals(View.VISIBLE, page.findViewById<View>(R.id.learning_map).visibility)
            assertNull(page.findViewById<View>(R.id.learning_reset_profile))
            assertNull(page.findViewById<View>(R.id.learning_delete_profile))
            val session = checkNotNull(page.learningSession)
            val actual = checkNotNull(session.geometry)
            val restored = io.github.minjj.keyboard.ui.TouchLearningMapView.storedGeometry(page, checkNotNull(session.layout))
            assertEquals(actual.keyGapLimit, restored.keyGapLimit)
            assertEquals(actual.verticalGapLimit, restored.verticalGapLimit)
            for (letter in actual.keys.filter { it.area != null }) {
                assertEquals(letter.bounds, restored.keys.first { it.area?.key == letter.area!!.key }.bounds)
            }
        }
        return page
    }

    private fun learningCount(repository: SpatialStatsRepository): Int {
        var count = 0
        main { count = repository.model.layouts.sumOf { p -> p.layout?.keys.orEmpty().sumOf {
            repository.model.summary(p.id, spatialDay(), it.key, setOf(it.key)).guided
        } } }
        return count
    }

    private fun typeLearningWord(page: TouchLearningActivity, korean: Boolean): Int {
        val word = checkNotNull(page.learningSession?.word)
        var count = 0
        word.keys.forEach { c ->
            if (c.isUpperCase()) { tap("shift"); count++ }
            tapSpatialKey(checkNotNull(KeyIds.fromToken(c)))
            count++
        }
        return count
    }

    private fun learningResultScreenshot(page: TouchLearningActivity, name: String) {
        await("IME hidden for learning result") { !device.hasObject(By.res(packageName, "key_delete")) }
        await("Learning result map laid out") {
            var ready = false
            main { val map = page.findViewById<io.github.minjj.keyboard.ui.TouchLearningMapView>(R.id.learning_map)
                ready = map.isShown && map.isLaidOut && !map.isLayoutRequested && map.renderingComplete }
            ready
        }
        device.waitForIdle(500)
        screenshot(name)
    }

    private fun assertLearningFitsAboveIme(page: TouchLearningActivity, landscape: Boolean) {
        await("Fitted map raster ready") {
            var ready = false
            main { ready = page.findViewById<io.github.minjj.keyboard.ui.TouchLearningMapView>(R.id.learning_map).renderingComplete }
            ready
        }
        val keyboard = liveKeyboard()
        main {
            val ime = IntArray(2); keyboard.getLocationOnScreen(ime)
            fun fullyVisible(view: View): Rect {
                val location = IntArray(2); view.getLocationOnScreen(location)
                val expected = Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
                val visible = Rect()
                assertTrue("Visible learning control ${view.id}", view.getGlobalVisibleRect(visible))
                assertEquals("Unclipped learning control ${view.id}", expected, visible)
                assertTrue("Learning control above IME ${view.id}", expected.bottom <= ime[1])
                return expected
            }
            val input = fullyVisible(page.findViewById(R.id.learning_editor))
            fullyVisible(page.findViewById(R.id.learning_prompt))
            fullyVisible(page.findViewById(R.id.learning_controls_hint))
            for (id in listOf(R.id.learning_complete, R.id.learning_retry, R.id.learning_skip, R.id.learning_finish))
                assertNull("Removed practice button $id", page.findViewById<View>(id))
            val map = page.findViewById<io.github.minjj.keyboard.ui.TouchLearningMapView>(R.id.learning_map)
            val bounds = fullyVisible(map)
            assertTrue("Readable complete map height", map.renderedBounds.height() >= dp(72))
            assertTrue(map.renderedBounds.left >= 0 && map.renderedBounds.top >= 0 &&
                map.renderedBounds.right <= map.width && map.renderedBounds.bottom <= map.height)
            if (landscape) assertTrue("Side by side", input.right < bounds.left)
            else assertTrue("Stacked", input.bottom < bounds.top)
            fun containsScroll(view: View): Boolean = view is android.widget.ScrollView || view is android.widget.HorizontalScrollView ||
                (view is ViewGroup && (0 until view.childCount).any { containsScroll(view.getChildAt(it)) })
            assertFalse("No scroll container in focused training", containsScroll(page.window.decorView))
        }
    }

    private fun tapSpatialKey(id: Int, xFraction: Float = .5f) {
        point("delete")
        val keyboard = liveKeyboard()
        await("Spatial target $id laid out") {
            var ready = false
            main { ready = !keyboard.isLayoutRequested && keyboard.learningGeometry?.keys?.any { it.area?.key == id } == true }
            ready
        }
        lateinit var location: Point
        main {
            val key = keyboard.learningGeometry!!.keys.first { it.area?.key == id }
            val offset = IntArray(2); keyboard.getLocationOnScreen(offset)
            location = Point(offset[0] + key.bounds.left + (key.bounds.width() * xFraction).toInt(),
                offset[1] + key.bounds.centerY())
        }
        val now = SystemClock.uptimeMillis()
        pointers(now, MotionEvent.ACTION_DOWN, listOf(location))
        pointers(now, MotionEvent.ACTION_UP, listOf(location))
        instrumentation.waitForIdleSync()
    }

    @Test fun guidedPracticeIncludesDigitsAndFunctionsInBothOrientations() = withLearningFixture { repo ->
        for (landscape in listOf(false, true)) {
            if (landscape) device.setOrientationLeft() else device.setOrientationNatural()
            for (ko in listOf(true, false)) {
                runBlocking { SettingsRepository(activity).restoreSettings(KeyboardSettings(
                    portraitMode = if (ko) 0 else 1, landscapeMode = if (ko) 0 else 1, touchLearning = false)) }
                val page = learningPage(ko)
                val seen = mutableSetOf<String>()
                repeat(2) { trial ->
                    val active = page.learningSession!!
                    val word = active.word!!
                    assertTrue(word.sentence)
                    assertNull(word.actionKey)
                    assertTrue(word.text.any(Char::isDigit))
                    assertTrue(seen.add(word.text))
                    assertEquals(0, active.layout!!.page)
                    assertLearningFitsAboveIme(page, landscape)
                    val before = learningCount(repo)
                    val length = typeLearningWord(page, ko)
                    val profile = active.profile
                    tap("enter")
                    await("Mixed sentence including Enter saved") { learningCount(repo) == before + length + 1 }
                    assertTrue(repo.model.summary(profile, spatialDay(), KeyIds.function("SPACE")!!, setOf(KeyIds.function("SPACE")!!)).guided > 0)
                    assertTrue(repo.model.summary(profile, spatialDay(), KeyIds.function("ENTER")!!, setOf(KeyIds.function("ENTER")!!)).guided > 0)
                    assertLearningFitsAboveIme(page, landscape)
                    if (trial == 1) {
                        main { page.findViewById<Spinner>(R.id.learning_distribution).setSelection(1) }
                        assertLearningFitsAboveIme(page, landscape)
                    }
                    screenshot("sentence-${if (landscape) "landscape" else "portrait"}-${if (ko) "ko" else "en"}-$trial")
                }
                main { page.finish() }
            }
        }
    }

    @Test fun sentencePracticeContinuesBeyondTenAndThirtyTwoSamples() = withLearningFixture { repo ->
        val page = learningPage(false)
        val fixture = "we see 2 bees"
        repeat(12) {
            main { page.learningSession!!.begin(io.github.minjj.keyboard.touch.PracticeWord(fixture,
                io.github.minjj.keyboard.touch.PracticeSentences.keys(fixture, false), sentence = true), repo) }
            typeLearningWord(page, false)
            tap("enter")
            await("Next sentence remains active") { var ready = false; main { ready = page.readyForTyping && page.learningSession?.completed == it + 1 }; ready }
        }
        val active = page.learningSession!!
        assertTrue(active.adopted[KeyIds.character('e')!!] > 32)
        assertTrue(active.totalSaved > 150)
        assertEquals(12, active.completed)
        main { page.finish() }
    }

    @Test fun adaptiveMapShowsExpansionRecentPointsAndActualMode() = withLearningFixture { repo ->
        for (landscape in listOf(false, true)) {
            if (landscape) device.setOrientationLeft() else device.setOrientationNatural()
            runBlocking { SettingsRepository(activity).restoreSettings(KeyboardSettings(portraitMode = 0,
                landscapeMode = 0, extendedLayout = false, touchCorrection = false)) }
            val page = learningPage(false)
            val active = page.learningSession!!
            main {
                repo.record(active.profile, repo.generation, active.layout!!.keys.flatMap { key -> List(160) { i ->
                    TouchSample(key.key, 300 + i % 20 * 5, -60 + i % 13 * 10, true)
                } })
                active.begin(active.word!!, repo)
            }
            val map = page.findViewById<io.github.minjj.keyboard.ui.TouchLearningMapView>(R.id.learning_map)
            await("Learned regions expand and shrink") { var ready = false; main {
                ready = map.renderingComplete && map.regionStats.changedPercent > .1f &&
                    map.regionStats.areaDelta.values.any { it > .1f } && map.regionStats.areaDelta.values.any { it < -.1f }
            }; ready }
            assertLearningFitsAboveIme(page, landscape)
            main { page.findViewById<Spinner>(R.id.learning_distribution).setSelection(1) }
            assertLearningFitsAboveIme(page, landscape)
            device.waitForIdle(500); SystemClock.sleep(150)
            screenshot("adaptive-preview-half-${if (landscape) "landscape" else "portrait"}")
            for (key in active.layout!!.keys) {
                val points = repo.model.density(active.profile, spatialDay(), key.key)
                assertEquals(100, points.points.size)
                assertEquals(50, points.centralPoints().size)
                assertNotNull(points.mode)
            }
            main { page.findViewById<Spinner>(R.id.learning_map_mode).setSelection(1) }
            await("Actual mode respects disabled correction") { var ready = false; main { ready = map.renderingComplete && map.regionStats.changedPercent == 0f }; ready }
            device.waitForIdle(500); SystemClock.sleep(150)
            screenshot("adaptive-actual-off-${if (landscape) "landscape" else "portrait"}")
            main {
                page.findViewById<Spinner>(R.id.learning_map_mode).setSelection(0)
                page.findViewById<Spinner>(R.id.learning_distribution).setSelection(0)
            }
            await("Preview returns without changing input settings") { var ready = false; main { ready = map.renderingComplete && map.regionStats.changedPercent > .1f }; ready }
            assertFalse(runBlocking { SettingsRepository(activity).settings.first().touchCorrection })
            main {
                val percent = java.lang.String.format(java.util.Locale.getDefault(), "%.1f%%", map.regionStats.changedPercent)
                assertTrue("Caption shows the computed nonzero change", page.findViewById<TextView>(R.id.learning_region_status).text.contains(percent))
            }
            device.waitForIdle(500); SystemClock.sleep(150)
            screenshot("adaptive-preview-density-${if (landscape) "landscape" else "portrait"}")
            main { page.finish() }
        }
    }

    @Test fun generalKeyboardDigitsAndFunctionsLearnAndCorrectRealBoundaryTaps() = withLearningFixture { repo ->
        val options = SettingsRepository(activity)
        runBlocking { options.restoreSettings(KeyboardSettings(portraitMode = 0, landscapeMode = 0,
            touchLearning = true, touchCorrection = true)) }
        fun trainCurrent(): Int {
            var id = -1
            val keyboard = liveKeyboard()
            main {
                id = keyboard.spatialProfile()
                repo.record(id, repo.generation, keyboard.learningLayout!!.keys.flatMap { k ->
                    List(100) { TouchSample(k.key, 400, 0, true) }
                })
            }
            return id
        }
        for (landscape in listOf(false, true)) {
            if (landscape) device.setOrientationLeft() else device.setOrientationNatural()
            native()
            val textId = trainCurrent()
            tapSpatialKey(KeyIds.character('2')!!, .01f); expect("1")
            tapSpatialKey(KeyIds.character('2')!!); expect("12")
            assertTrue(repo.model.summary(textId, spatialDay(), KeyIds.character('2')!!, setOf(KeyIds.character('2')!!)).normal > 0)
            tap("symbols")
            val symbolKeyboard = liveKeyboard()
            await("Symbol geometry excluded") { var excluded = false; main { excluded = symbolKeyboard.learningLayout == null }; excluded }
            val beforeSymbols = repo.model.encode()
            tapText("+"); expect("12+")
            assertEquals("Symbol page does not learn", beforeSymbols, repo.model.encode())
            native(value = "abcd")
            trainCurrent()
            tapSpatialKey(KeyIds.function("CURSOR_RIGHT")!!, .01f)
            await("Right key edge corrects to left function") { var correct = false; main { correct = activity.primary.selectionStart == 3 }; correct }
            tapSpatialKey(KeyIds.function("CURSOR_RIGHT")!!)
            await("Function center remains right") { var correct = false; main { correct = activity.primary.selectionStart == 4 }; correct }
            screenshot("general-keys-corrected-${if (landscape) "landscape" else "portrait"}")
            native(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            val privateId = trainCurrent()
            val before = repo.model.summary(privateId, spatialDay(), KeyIds.character('2')!!, setOf(KeyIds.character('2')!!)).normal
            tapSpatialKey(KeyIds.character('2')!!, .01f); expect("2")
            tapSpatialKey(KeyIds.character('2')!!); expect("22")
            assertEquals(before, repo.model.summary(privateId, spatialDay(), KeyIds.character('2')!!, setOf(KeyIds.character('2')!!)).normal)
        }
    }

    @Test fun guidedPracticeKeepsHiddenNumberRowLayout() = withLearningFixture { repo ->
        runBlocking { SettingsRepository(activity).restoreSettings(KeyboardSettings(numberRow = false, touchLearning = false)) }
        val page = learningPage(false)
        repeat(2) {
            val word = page.learningSession!!.word!!
            assertTrue(word.sentence)
            assertFalse(word.text.any(Char::isDigit))
            assertTrue(page.learningSession!!.layout!!.keys.none { KeyIds.kind(it.key) == 1 })
            typeLearningWord(page, false)
            tap("enter")
        }
        assertTrue(learningCount(repo) > 0)
        main { page.finish() }
    }

    private fun completeLearning(page: TouchLearningActivity) {
        val trial = page.learningSession!!.trial
        tap("enter")
        await("Done advances or finishes practice") {
            var changed = false
            main { changed = page.learningSession?.trial != trial }
            changed
        }
    }

    private fun finishLearning(page: TouchLearningActivity) {
        main { assertTrue(page.findViewById<EditText>(R.id.learning_editor).text.isEmpty()) }
        completeLearning(page)
        completeLearning(page)
        main { assertNull(page.learningSession) }
    }

    private fun practiceText(value: String, korean: Boolean) {
        io.github.minjj.keyboard.touch.PracticeSentences.keys(value, korean).forEach { c ->
            if (c.isUpperCase()) tap("shift")
            tapSpatialKey(checkNotNull(KeyIds.fromToken(c)))
        }
    }

    private fun practiceCursorMove(page: TouchLearningActivity, steps: Int) {
        val center = point("space")
        val down = SystemClock.uptimeMillis()
        pointers(down, MotionEvent.ACTION_DOWN, listOf(center))
        await("Practice cursor mode shown") {
            var shown = false
            main { shown = liveKeyboardViewState(R.id.key_space) == page.getString(R.string.cursor_mode_active) }
            shown
        }
        val moved = Point(center.x + steps * dp(14), center.y)
        pointers(down, MotionEvent.ACTION_MOVE, listOf(moved))
        pointers(down, MotionEvent.ACTION_UP, listOf(moved))
        instrumentation.waitForIdleSync()
    }

    private fun liveKeyboardViewState(id: Int): CharSequence? = WindowInspector.getGlobalWindowViews()
        .firstNotNullOfOrNull { it.findViewById<View>(id)?.takeIf(View::isShown) }?.stateDescription

    @Test fun practiceClearingRejectedInputRestartsSameSentence() = withLearningFixture { repo ->
        for (landscape in listOf(false, true)) {
            if (landscape) device.setOrientationLeft() else device.setOrientationNatural()
            for (ko in listOf(true, false)) {
                runBlocking { SettingsRepository(activity).restoreSettings(KeyboardSettings(
                    portraitMode = if (ko) 0 else 1, landscapeMode = if (ko) 0 else 1)) }
                val page = learningPage(ko)
                val active = page.learningSession!!
                val word = active.word!!
                practiceText(if (ko) "가나 7" else "ab 7", ko)
                main { page.findViewById<EditText>(R.id.learning_editor).append("x") }
                await("Unverified edit displays recovery guidance") { var invalid = false; main {
                    invalid = active.invalid && page.findViewById<TextView>(R.id.learning_status).text == page.getString(R.string.learning_interrupted)
                }; invalid }
                val trial = active.trial
                val delete = point("delete"); val down = SystemClock.uptimeMillis()
                pointers(down, MotionEvent.ACTION_DOWN, listOf(delete))
                try {
                    await("Held backspace clears the rejected text") { var empty = false; main {
                        empty = page.findViewById<EditText>(R.id.learning_editor).text.isEmpty()
                    }; empty }
                } finally { pointers(down, MotionEvent.ACTION_UP, listOf(delete)) }
                await("Empty editor rearms the same sentence") { var ready = false; main {
                    ready = !active.invalid && active.trial > trial && page.readyForTyping
                }; ready }
                main {
                    assertSame(word, active.word)
                    assertEquals(0, active.completed); assertEquals(0, active.excluded)
                    assertEquals(0, active.totalSaved)
                }
                val before = learningCount(repo)
                val length = typeLearningWord(page, ko)
                completeLearning(page)
                assertEquals("Only the fresh attempt is learned", before + length + 1, learningCount(repo))
                assertEquals(1, active.completed); assertEquals(0, active.excluded)
                assertLearningFitsAboveIme(page, landscape)
                screenshot("practice-recovered-${if (landscape) "landscape" else "portrait"}-${if (ko) "ko" else "en"}")
                main { page.finish() }
            }
        }
    }

    @Test fun practiceEmptyDoneSkipsThenFinishesWithoutLearningEmptyTouches() = withLearningFixture { repo ->
        for (landscape in listOf(false, true)) {
            if (landscape) device.setOrientationLeft() else device.setOrientationNatural()
            for (ko in listOf(true, false)) {
                runBlocking { SettingsRepository(activity).restoreSettings(KeyboardSettings(
                    portraitMode = if (ko) 0 else 1, landscapeMode = if (ko) 0 else 1)) }
                val page = learningPage(ko)
                val active = page.learningSession!!
                val first = active.word
                val before = learningCount(repo)
                completeLearning(page)
                main {
                    assertSame(active, page.learningSession)
                    assertNotEquals(first, active.word)
                    assertEquals(1, active.excluded); assertEquals(0, active.completed)
                    assertEquals(page.getString(R.string.learning_empty_skipped), page.findViewById<TextView>(R.id.learning_controls_hint).text)
                }
                assertEquals(before, learningCount(repo))
                assertLearningFitsAboveIme(page, landscape)
                screenshot("practice-skip-${if (landscape) "landscape" else "portrait"}-${if (ko) "ko" else "en"}")
                // Entering and then clearing text breaks the consecutive-empty streak.
                tap("r"); tap("delete")
                completeLearning(page)
                main { assertSame(active, page.learningSession); assertEquals(2, active.excluded) }
                val length = typeLearningWord(page, ko)
                completeLearning(page)
                assertEquals(before + length + 1, learningCount(repo))
                completeLearning(page)
                main { assertSame(active, page.learningSession); assertEquals(3, active.excluded) }
                completeLearning(page)
                main {
                    assertNull(page.learningSession)
                    assertEquals(4, active.excluded); assertEquals(1, active.completed)
                    assertTrue(page.findViewById<View>(R.id.learning_start).isShown)
                    assertFalse(page.findViewById<View>(R.id.learning_editor).isShown)
                    assertTrue(page.findViewById<TextView>(R.id.learning_status).text.startsWith("연습 종료"))
                }
                assertEquals("Empty Done touches are never learned", before + length + 1, learningCount(repo))
                learningResultScreenshot(page, "practice-finished-${if (landscape) "landscape" else "portrait"}-${if (ko) "ko" else "en"}")
                // A newly started session also begins with a fresh empty-completion streak.
                main { page.findViewById<View>(R.id.learning_start).performClick() }
                await("Practice restarts") { var ready = false; main { ready = page.learningSession?.connected == true && page.readyForTyping }; ready }
                finishLearning(page)
                main { page.finish() }
            }
        }
    }

    @Test fun practiceLanguageSwitchPreservesInputAndSeparatesProfiles() = withLearningFixture { repo ->
        for (landscape in listOf(false, true)) {
            if (landscape) device.setOrientationLeft() else device.setOrientationNatural()
            runBlocking { SettingsRepository(activity).restoreSettings(KeyboardSettings(portraitMode = 0, landscapeMode = 0, extendedLayout = false)) }
            val page = learningPage(true)
            val active = page.learningSession!!
            val koId = active.profile
            val koLayout = active.layout!!
            val fixture = "가나 ab 7"
            main {
                active.begin(io.github.minjj.keyboard.touch.PracticeWord(fixture,
                    io.github.minjj.keyboard.touch.PracticeSentences.keys(fixture, true), sentence = true), repo)
                page.findViewById<TextView>(R.id.learning_prompt).text = fixture
            }
            practiceText("가나", true)
            tap("language")
            await("Practice changes locally to English") { var ready = false; main {
                ready = page.learningSession === active && active.connected && !active.inputKorean && active.layout?.korean == false
            }; ready }
            main {
                assertFalse("Language switch rejected: ${active.rejectionReason}", active.invalid)
                assertEquals("가나", page.findViewById<EditText>(R.id.learning_editor).text.toString())
                assertEquals(fixture, active.word!!.text)
            }
            val enId = active.profile
            val enLayout = active.layout!!
            assertNotEquals(koId, enId)
            practiceText(" ab 7", false)
            main {
                assertEquals(fixture, page.findViewById<EditText>(R.id.learning_editor).text.toString())
                assertFalse(active.invalid)
            }
            screenshot("practice-language-${if (landscape) "landscape" else "portrait"}")
            tap("enter")
            await("Mixed script sentence submitted") { var ready = false; main { ready = active.completed == 1 && page.readyForTyping }; ready }
            assertEquals(1, repo.model.summary(koId, spatialDay(), KeyIds.character('r')!!, setOf(KeyIds.character('r')!!)).guided)
            assertEquals(1, repo.model.summary(enId, spatialDay(), KeyIds.character('a')!!, setOf(KeyIds.character('a')!!)).guided)
            assertEquals(0, repo.model.summary(enId, spatialDay(), KeyIds.character('r')!!, setOf(KeyIds.character('r')!!)).guided)
            assertEquals(0, repo.model.summary(koId, spatialDay(), KeyIds.character('a')!!, setOf(KeyIds.character('a')!!)).guided)
            assertEquals(koId, repo.model.select(koLayout)); assertEquals(enId, repo.model.select(enLayout))
            val next = active.word!!
            assertTrue(next.text.none { it in '가'..'힣' })
            val center = point("space"); val time = SystemClock.uptimeMillis(); val end = Point(center.x + dp(60), center.y)
            pointers(time, MotionEvent.ACTION_DOWN, listOf(center))
            pointers(time, MotionEvent.ACTION_MOVE, listOf(end))
            pointers(time, MotionEvent.ACTION_UP, listOf(end))
            await("Practice space swipe changes to Korean") { var ready = false; main { ready = active.inputKorean && active.layout?.korean == true }; ready }
            main {
                assertSame(next, active.word)
                assertFalse("Language swipe rejected: ${active.rejectionReason}", active.invalid)
            }
            completeLearning(page)
            assertTrue(active.word!!.text.any { it in '가'..'힣' })
            main { page.finish() }
        }
    }

    @Test fun practiceSpaceCursorEditsMiddleWithoutDiscardingSentence() = withLearningFixture { repo ->
        for (landscape in listOf(false, true)) {
            if (landscape) device.setOrientationLeft() else device.setOrientationNatural()
            runBlocking { SettingsRepository(activity).restoreSettings(KeyboardSettings(portraitMode = 0, landscapeMode = 0, extendedLayout = false)) }
            for (ko in listOf(true, false)) {
                val page = learningPage(ko)
                val active = page.learningSession!!
                val expected = if (ko) "가나다 7" else "abcd 7"
                val initial = if (ko) "가다 7" else "acd 7"
                main {
                    active.begin(io.github.minjj.keyboard.touch.PracticeWord(expected,
                        io.github.minjj.keyboard.touch.PracticeSentences.keys(expected, ko), sentence = true), repo)
                    page.findViewById<TextView>(R.id.learning_prompt).text = expected
                }
                practiceText(initial, ko)
                val tapsBefore = active.taps.size
                practiceCursorMove(page, 1 - initial.length)
                await("Practice cursor reached insertion point") { var ready = false; main {
                    ready = page.findViewById<EditText>(R.id.learning_editor).selectionStart == 1
                }; ready }
                main {
                    assertFalse("Cursor hold rejected: ${active.rejectionReason}", active.invalid)
                    assertEquals(tapsBefore, active.taps.size)
                    assertEquals(initial, page.findViewById<EditText>(R.id.learning_editor).text.toString())
                }
                practiceText(if (ko) "나" else "b", ko)
                main {
                    assertEquals(expected, page.findViewById<EditText>(R.id.learning_editor).text.toString())
                    assertFalse("Middle insertion rejected: ${active.rejectionReason}", active.invalid)
                }
                practiceCursorMove(page, expected.length - 2)
                await("Practice cursor returns to end") { var ready = false; main {
                    ready = page.findViewById<EditText>(R.id.learning_editor).selectionStart == expected.length
                }; ready }
                val profile = active.profile
                val before = learningCount(repo)
                val count = io.github.minjj.keyboard.touch.PracticeSentences.keys(expected, ko).length + 1
                screenshot("practice-cursor-${if (landscape) "landscape" else "portrait"}-${if (ko) "ko" else "en"}")
                tap("enter")
                await("Edited sentence validated") { learningCount(repo) == before + count }
                assertEquals(1, repo.model.summary(profile, spatialDay(), KeyIds.function("SPACE")!!, setOf(KeyIds.function("SPACE")!!)).guided)
                main { page.finish() }
            }
        }
    }

    @Test fun sentencePracticeLearnsBackspaceAndEnterInContext() = withLearningFixture { repo ->
        for (ko in listOf(true, false)) {
            val page = learningPage(ko)
            val text = if (ko) "가나 7시에 만나요" else "Meet 7 friends"
            val path = io.github.minjj.keyboard.touch.PracticeSentences.keys(text, ko)
            main { page.learningSession!!.begin(io.github.minjj.keyboard.touch.PracticeWord(text, path, sentence = true), repo) }
            var edited = false
            for (c in path) {
                if (c.isUpperCase()) tap("shift")
                tapSpatialKey(KeyIds.fromToken(c)!!)
                if (!edited && c == io.github.minjj.keyboard.touch.PracticeSentences.space) {
                    tap("delete"); tap("delete")
                    val retype = if (ko) "sk" else "t"
                    retype.forEach { tap(it.toString()) }
                    tap("space")
                    edited = true
                }
            }
            val active = page.learningSession!!
            val profile = active.profile
            main { assertEquals(text, page.findViewById<EditText>(R.id.learning_editor).text.toString()) }
            val before = repo.model.summary(profile, spatialDay(), KeyIds.function("DELETE")!!, setOf(KeyIds.function("DELETE")!!)).guided
            tap("enter")
            await("Confirmed backspace edits saved") {
                repo.model.summary(profile, spatialDay(), KeyIds.function("DELETE")!!, setOf(KeyIds.function("DELETE")!!)).guided == before + 2
            }
            assertFalse(active.invalid)
            main { page.finish() }
        }
    }

    @Test fun correctedFunctionEdgeConsumesHeldShiftLikeALetter() = withLearningFixture { repo ->
        runBlocking { SettingsRepository(activity).restoreSettings(KeyboardSettings(portraitMode = 0,
            extendedLayout = false, autoCapitalization = false, touchCorrection = true, touchLearning = false)) }
        native(); english()
        val keyboard = liveKeyboard()
        main {
            val id = keyboard.spatialProfile()
            repo.record(id, repo.generation, keyboard.learningLayout!!.keys.flatMap { k ->
                List(100) { TouchSample(k.key, 400, 0, true) }
            })
        }
        val shift = point("shift")
        val delete = key("delete").visibleBounds.let { Point(it.left + 1, it.centerY()) }
        overlappingTap(shift, delete)
        expect("M")
        tap("m"); expect("Mm")
    }

    @Test fun guidedPracticeFitsAboveImeInBothOrientations() = withLearningFixture { repo ->
        for (landscape in listOf(false, true)) {
            if (landscape) device.setOrientationLeft() else device.setOrientationNatural()
            val options = instrumentation.startActivitySync(Intent(instrumentation.targetContext, OptionsSettingsActivity::class.java)
                .putExtra(OptionsSettingsActivity.PAGE, R.string.touch_personalization).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as OptionsSettingsActivity
            await("Personalization menu without IME") { !device.hasObject(By.res(packageName, "key_delete")) }
            assertFalse("Removed learning data menu", device.hasObject(By.text("학습 데이터 확인")))
            device.waitForIdle(500)
            screenshot("personalization-groups-${if (landscape) "landscape" else "portrait"}")
            main { options.finish() }
            for (split in listOf(false, true)) {
                runBlocking { SettingsRepository(activity).restoreSettings(KeyboardSettings(portraitMode = if (split) 1 else 0,
                    landscapeMode = if (split) 1 else 0, touchLearning = true)) }
                for (ko in listOf(true, false)) {
                    val page = learningPage(ko)
                    assertLearningFitsAboveIme(page, landscape)
                    val before = learningCount(repo)
                    val length = typeLearningWord(page, ko)
                    tap("enter")
                    await("Focused training word saved") { learningCount(repo) == before + length + 1 }
                    assertLearningFitsAboveIme(page, landscape)
                    device.waitForIdle(500)
                    screenshot("training-with-ime-${if (ko) "ko" else "en"}-${if (landscape) "landscape" else "portrait"}-${if (split) "split" else "regular"}")
                    main { page.finish() }
                }
            }
        }
    }

    @Test fun guidedPracticeLearnsOnlyValidatedTouches() = withLearningFixture { repo ->
        for (landscape in listOf(false, true)) {
            if (landscape) device.setOrientationLeft() else device.setOrientationNatural()
            for (split in listOf(false, true)) {
                runBlocking { SettingsRepository(activity).restoreSettings(KeyboardSettings(portraitMode = if (split) 1 else 0,
                    landscapeMode = if (split) 1 else 0, touchLearning = false)) }
                for (ko in listOf(true, false)) {
                    val page = learningPage(ko)
                    val before = learningCount(repo)
                    val length = typeLearningWord(page, ko)
                    main { assertFalse("Typed word rejected: ${page.learningSession?.rejectionReason}", page.learningSession?.invalid == true) }
                    completeLearning(page)
                    assertEquals("Validated touches: ${page.learningSession?.lastOutcome}", before + length + 1, learningCount(repo))
                    tap("q"); tap("delete")
                    completeLearning(page)
                    assertEquals(before + length + 1, learningCount(repo))
                    completeLearning(page)
                    learningResultScreenshot(page, "guided-result-${if (ko) "ko" else "en"}-${if (landscape) "landscape" else "portrait"}-${if (split) "split" else "regular"}")
                    main { page.finish() }
                }
            }
        }
    }

    @Test fun layoutProfilesResumeAcrossGeometryChanges() = withLearningFixture { repo ->
        val original = KeyboardSettings(portraitMode = 0, touchLearning = false)
        var firstId = -1
        for ((index, options) in listOf(original, original.copy(portraitHeightPercent = 125),
            original.copy(portraitWidthPercent = 75), original.copy(horizontalKeyGap = 12, verticalKeyGap = 12),
            original.copy(portraitMode = 1, portraitSplitGap = 80), original).withIndex()) {
            runBlocking { SettingsRepository(activity).restoreSettings(options) }
            val page = learningPage(true)
            val countBefore = learningCount(repo)
            val length = typeLearningWord(page, true)
            completeLearning(page)
            assertEquals("Profile word $index: ${page.learningSession?.lastOutcome}", countBefore + length + 1, learningCount(repo))
            var id = -1
            val keyboard = liveKeyboard()
            main { id = keyboard.spatialProfile() }
            if (index == 0) firstId = id else if (index == 5) assertEquals(firstId, id) else assertNotEquals(firstId, id)
            main { page.finish() }
        }
        assertTrue(repo.model.layouts.count { it.lastUsed >= 0 } >= 5)
    }

    @Test fun liveContextMapReusesGeometryAndMatchesTapDecisions() = withLearningFixture { repo ->
        val page = learningPage(false)
        lateinit var map: io.github.minjj.keyboard.ui.TouchLearningMapView
        main { map = page.findViewById(R.id.learning_map) }
        await("Initial map ready") { var done = false; main { done = map.renderingComplete }; done }
        var preparationCount = 0
        var initialRevision = 0L
        main { preparationCount = map.preparationCount; initialRevision = page.learningSession!!.predictionRevision }
        // Start a known English prefix via actual touches. No prompt text is used as a model label.
        type("th")
        await("Context reaches map") { var updated = false; main { updated = page.learningSession!!.predictionRevision > initialRevision &&
            page.learningSession!!.evidence?.any { it != 0f } == true && map.renderingComplete }; updated }
        main { assertEquals(preparationCount, map.preparationCount) }
        screenshot("live-context-prefix")
        val active = checkNotNull(page.learningSession)
        val layout = checkNotNull(active.layout)
        val geometry = checkNotNull(active.geometry)
        val density = layout.pixelDensity
        val viewport = android.graphics.RectF(layout.keys.minOf { it.left }, layout.keys.minOf { it.top },
            layout.keys.maxOf { it.right }, layout.keys.maxOf { it.bottom })
        val w = 640; val h = (w * viewport.height() / viewport.width()).toInt()
        val model = SpatialModel().withLayout(active.profile, layout, spatialDay()).updated(active.profile, spatialDay(),
            layout.keys.flatMap { key -> List(30) { TouchSample(key.key, 140, -80, true) } })
        val scores = checkNotNull(active.evidence).copyOf()
        val language: (Int) -> Float = { scores.getOrElse(it) { 0f } }
        val prepareStart = System.nanoTime()
        val plan = io.github.minjj.keyboard.ui.TouchMapRaster.prepare(geometry, viewport, w, h, density)
        val prepareMs = (System.nanoTime() - prepareStart) / 1_000_000.0
        val reference = IntArray(w * h)
        val referenceStart = System.nanoTime()
        for (y in 0 until h) for (x in 0 until w) {
            val px = (viewport.left + (x + .5f) * viewport.width() / w) * density
            val py = (viewport.top + (y + .5f) * viewport.height() / h) * density
            val key = io.github.minjj.keyboard.ui.TouchMapRaster.letterAt(geometry, model, active.profile,
                spatialDay(), px, py, true, language)
            reference[y * w + x] = key?.let { android.graphics.Color.HSVToColor(95,
                floatArrayOf(if (it < 26) it * 360f / 26 else (it * 137.5f) % 360f, .35f, .9f)) } ?: 0
        }
        val referenceMs = (System.nanoTime() - referenceStart) / 1_000_000.0
        // Warm up, then record repeated context-only rendering with the same geometry.
        io.github.minjj.keyboard.ui.TouchMapRaster.render(plan, model, active.profile, spatialDay(), true, scores)
        val durations = mutableListOf<Double>()
        repeat(5) {
            val start = System.nanoTime()
            val pixels = io.github.minjj.keyboard.ui.TouchMapRaster.render(plan, model, active.profile, spatialDay(), true, scores)
            durations += (System.nanoTime() - start) / 1_000_000.0
            assertArrayEquals(reference, pixels)
        }
        val blank = io.github.minjj.keyboard.ui.TouchMapRaster.render(plan, SpatialModel(), active.profile, spatialDay(), false)
        val contextual = io.github.minjj.keyboard.ui.TouchMapRaster.render(plan, SpatialModel(), active.profile, spatialDay(), false, scores)
        assertTrue("Real language evidence changes boundary colors", blank.indices.any { blank[it] != contextual[it] })
        File(output, "map-render-performance.txt").writeText("pixels=${w*h} prepareMs=$prepareMs referenceMs=$referenceMs cachedMedianMs=${durations.sorted()[2]} exactMatch=true geometryReused=true\n")
        tap("delete"); tap("delete")
        typeLearningWord(page, false)
        completeLearning(page)
        finishLearning(page)
        assertTrue(learningCount(repo) > 0)
        main { page.finish() }
    }

    @Test fun guidedEnglishCapturesEveryPhysicalKey() = withLearningFixture { repo ->
        val page = learningPage(false)
        val length = typeLearningWord(page, false)
        main { assertFalse("English word: ${page.learningSession?.rejectionReason}", page.learningSession?.invalid == true) }
        tap("enter")
        assertEquals("English word: ${page.learningSession?.lastOutcome}", length + 1, learningCount(repo))
        main { page.finish() }
    }

    @Test fun learningSummarySurvivesProfileRefresh() = withLearningFixture { repo ->
        val page = learningPage(false)
        val length = typeLearningWord(page, false)
        val totalKeys = page.learningSession!!.layout!!.keys.size
        completeLearning(page)
        finishLearning(page)
        learningResultScreenshot(page, "learning-summary-final")
        main {
            assertEquals(page.getString(R.string.learning_session_result, length + 1, 2, 0, 0, totalKeys),
                page.findViewById<TextView>(R.id.learning_status).text.toString())
            assertEquals("", page.findViewById<TextView>(R.id.learning_prompt).text.toString())
        }
        assertEquals(length + 1, learningCount(repo))
        main { page.finish() }
    }

    @Test fun guidedPracticeStopsOnRotationAndEditorChange() = withLearningFixture { repo ->
        val page = learningPage(true)
        tap("r")
        device.setOrientationLeft()
        instrumentation.waitForIdleSync()
        assertEquals(0, learningCount(repo))
        main { page.finish() }
        native(); type("rksk"); tap("space"); tap("r")
        assertEquals(0, learningCount(repo))
    }

    @Test fun profileResetCannotRestorePendingSamples() = withLearningFixture { repo ->
        val page = learningPage(true)
        typeLearningWord(page, true)
        var id = -1
        val keyboard = liveKeyboard()
        main { id = keyboard.spatialProfile() }
        val done = CountDownLatch(1)
        main { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            repo.clearProfile(id, true); done.countDown()
        } }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        completeLearning(page)
        assertEquals(0, learningCount(repo))
        main { page.finish() }
    }

    @Test fun appSwitchFirstShowKeepsImeGeometryAndSafeArea() {
        val manager = activity.getSystemService(InputMethodManager::class.java)
        repeat(30) { iteration ->
            if (iteration == 15) {
                device.setOrientationLeft()
                point("delete")
                awaitImeAnimation()
            }
            main {
                activity.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            await("System settings receives focus") {
                var focused = true
                main { focused = activity.hasWindowFocus() }
                !focused && device.currentPackageName != packageName
            }
            main {
                activity.startActivity(Intent(activity, ImeTestActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            }
            await("Fixture receives focus after app switch") {
                var focused = false
                main { focused = activity.hasWindowFocus() }
                focused
            }
            var marker = ""
            main {
                activity.configure()
                marker = activity.primary.privateImeOptions
                manager.showSoftInput(activity.primary, 0)
            }
            await("IME bound after app switch") {
                var bound = false
                main {
                    bound = WindowInspector.getGlobalWindowViews().any { root ->
                        val view = root.findViewById<View>(R.id.key_delete)
                        val service = view?.context as? MinjjImeService
                        view?.isShown == true && service?.currentInputEditorInfo?.privateImeOptions == marker
                    }
                }
                bound
            }
            awaitImeAnimation()
            val keyboard = liveKeyboard()
            val initialTouchOffset = touchScreenOffset(keyboard, point("delete"), verify = false)
            // A target handoff can finish moving the IME surface after the editor's callback.
            // Retain the early sample, then check whether any displacement persists.
            SystemClock.sleep(350)
            val expected = point("delete")
            val visible = key("delete").visibleBounds
            val offset = visible.centerY() - expected.y
            var details = ""
            var contained = false
            var aboveSafeArea = false
            val touchOffset = touchScreenOffset(keyboard, expected, verify = false)
            main {
                val root = keyboard.rootView
                val types = android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout()
                val insets = root.rootWindowInsets
                val bars = insets.getInsets(types)
                val row = keyboard.getChildAt(keyboard.childCount - 1)
                contained = row.bottom <= keyboard.height - keyboard.paddingBottom
                val rootLocation = IntArray(2).also(root::getLocationOnScreen)
                val keyboardLocation = IntArray(2).also(keyboard::getLocationOnScreen)
                val safeBottom = minOf(device.displayHeight, rootLocation[1] + root.height) - bars.bottom
                val rowBottom = keyboardLocation[1] + row.bottom
                aboveSafeArea = rowBottom <= safeBottom
                val ancestors = generateSequence(keyboard as View) { it.parent as? View }.joinToString(";") {
                    "${it.javaClass.simpleName}:y=${it.y},h=${it.height},bottomPadding=${it.paddingBottom}"
                }
                details = "iteration=$iteration offset=$offset initialTouchOffset=$initialTouchOffset " +
                    "touchOffset=$touchOffset visible=$visible expected=$expected " +
                    "orientation=${keyboard.resources.configuration.orientation} " +
                    "bars=$bars stable=${insets.getInsetsIgnoringVisibility(types)} " +
                    "lastRowBottom=${row.bottom} screenRowBottom=$rowBottom safeBottom=$safeBottom " +
                    "contained=$contained aboveSafeArea=$aboveSafeArea ancestors=[$ancestors]\n"
            }
            File(output, "app-switch-geometry.txt").appendText(details)
            if (offset != 0 || touchOffset != 0 || !contained || !aboveSafeArea) {
                device.takeScreenshot(File(output, "app-switch-first-show-$iteration.png"))
                device.dumpWindowHierarchy(File(output, "app-switch-first-show-$iteration.xml"))
                liveFailureCaptured = true
            }
            assertEquals("First-show screen coordinates: $details", 0, offset)
            assertEquals("First-show input surface coordinates: $details", 0, touchOffset)
            assertTrue("Last row fits above bottom padding: $details", contained)
            assertTrue("Last row stays above system safe area: $details", aboveSafeArea)
        }
        device.takeScreenshot(File(output, "app-switch-first-show-final.png"))
    }

    private fun awaitImeAnimation() {
        val boundAt = SystemClock.uptimeMillis()
        await("IME animation finished before touch") {
            var stable = false
            main { stable = !activity.imeAnimating && SystemClock.uptimeMillis() - activity.lastImeTransition >= 150 }
            stable && SystemClock.uptimeMillis() - boundAt >= 150
        }
    }
    private fun key(name: String): UiObject2 = device.wait(Until.findObject(By.res(packageName, "key_$name")), 5000)
        ?: throw AssertionError("Missing IME key: $name")
    private fun tap(name: String) {
        val center = point(name)
        device.click(center.x, center.y)
    }
    /** Accessibility can cache pre-rotation bounds. Ground injected taps in the actual attached
     * IME views, requiring layout to settle. This still exercises the real touch dispatcher. */
    private fun point(name: String): Point {
        val id = instrumentation.targetContext.resources.getIdentifier("key_$name", "id", packageName)
        require(id != 0)
        var previous: Rect? = null
        val deadline = SystemClock.uptimeMillis() + 7000
        while (SystemClock.uptimeMillis() < deadline) {
            val width = device.displayWidth
            var rect: Rect? = null
            main {
                val roots = WindowInspector.getGlobalWindowViews()
                for (root in roots) {
                    val view = root.findViewById<View>(id) ?: continue
                    if (!view.isShown || !view.isAttachedToWindow || root.width != width) continue
                    var ancestor: View? = view
                    var pending = false
                    while (ancestor != null) { if (ancestor.isLayoutRequested) pending = true; ancestor = ancestor.parent as? View }
                    if (pending) continue
                    val location = IntArray(2); view.getLocationOnScreen(location)
                    if (view.width > 0 && view.height > 0) rect = Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
                }
            }
            if (rect != null && rect == previous) {
                File(output, "touch-points.txt").appendText("$activeTestName $name $rect appDensity=${instrumentation.targetContext.resources.displayMetrics.density} activityDensity=${activity.resources.displayMetrics.density}\n")
                return Point(rect!!.centerX(), rect!!.centerY())
            }
            previous = rect
            SystemClock.sleep(80)
        }
        throw AssertionError("IME key layout did not settle: $name")
    }
    private fun tapText(text: String) {
        if (text == ".") { tap("period"); return }
        if (text == ",") { tap("comma"); return }
        val found = device.wait(Until.findObject(By.pkg(packageName).desc(text)), 3000)
            ?: throw AssertionError("Missing key label: $text")
        found.click()
    }
    private fun korean() {
        if (device.hasObject(By.res(packageName, "key_letters"))) tap("letters")
        if (key("q").text != "ㅂ") tap("language")
        await("Korean layout") { key("q").text == "ㅂ" }
    }
    private fun english() {
        if (key("q").text == "ㅂ") tap("language")
        await("English layout") { key("q").text.lowercase() == "q" }
    }
    private fun type(keys: String) { keys.forEach { tap(it.toString()) } }
    private fun text(): String { var value = ""; main { value = activity.primary.text.toString() }; return value }
    private fun expect(value: String) = await("Expected fixture text '$value', actual '${text()}'") { text() == value }
    private fun await(label: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5000
        while (SystemClock.uptimeMillis() < deadline) { if (condition()) return; SystemClock.sleep(40) }
        val passed = condition()
        if (!passed && safeFailureCapture()) {
            device.takeScreenshot(File(output, "failed-$activeTestName-live.png"))
            device.dumpWindowHierarchy(File(output, "failed-$activeTestName-live.xml"))
            liveFailureCaptured = true
        }
        assertTrue(label, passed)
    }
    private fun screenshot(name: String) { device.takeScreenshot(File(output, "$name.png")) }
    private fun safeFailureCapture() = device.hasObject(By.pkg(packageName)) &&
        !device.hasObject(By.res(packageName, "clipboard_panel")) &&
        !instrumentation.targetContext.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked

    @Test fun hangulCompositionAndHistoryDeletion() {
        type("rksk"); expect("가나")
        tap("delete"); expect("가ㄴ")
        tap("delete"); expect("가")
        native(); korean(); type("rhk"); expect("과")
        tap("delete"); expect("고"); tap("delete"); expect("ㄱ"); tap("delete"); expect("")
        type("rkqtl"); expect("갑시")
        screenshot("portrait-hangul")
    }

    @Test fun selectionReplacementAndCursorMovement() {
        native(value = "앞 선택 뒤"); korean()
        main { activity.primary.setSelection(2, 4) }
        SystemClock.sleep(200)
        type("gks"); expect("앞 한 뒤")
        main { activity.primary.setSelection(0) }
        SystemClock.sleep(200)
        type("sk"); expect("나앞 한 뒤")
        tap("space")
        main { activity.primary.setSelection(0, 2) }
        SystemClock.sleep(200)
        tap("delete"); expect("앞 한 뒤")
    }

    @Test fun languageSwitchAndShift() {
        type("gks"); expect("한")
        tap("language"); type("abc"); expect("한abc")
        tap("shift"); tap("d"); tap("e"); expect("한abcDe")
        val shift = point("shift")
        device.click(shift.x, shift.y); SystemClock.sleep(60); device.click(shift.x, shift.y)
        type("fg"); expect("한abcDeFG")
        tap("shift"); tap("h"); expect("한abcDeFGh")
        tap("language"); tap("shift"); tap("r"); tap("k"); expect("한abcDeFGh까")
    }

    @Test fun committedGraphemeDeletion() {
        listOf("😀", "👍🏽", "👨‍👩‍👧‍👦", "🇰🇷", "e\u0301", "1\uFE0F\u20E3").forEach { cluster ->
            native(value = "앞$cluster"); tap("delete"); expect("앞")
        }
    }

    @Test fun numericPhoneAndEnterActions() {
        for (landscape in listOf(false, true)) {
            if (landscape) device.setOrientationLeft() else device.setOrientationNatural()
            val orientation = if (landscape) "landscape" else "portrait"
            native(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL)
            assertEquals(point("2").x, point("0").x)
            assertEquals(point("delete").x, point("enter").x)
            tapText("-"); tap("1"); tapText("."); tap("5"); expect("-1.5")
            screenshot("numeric-grid-$orientation")
            native(InputType.TYPE_CLASS_PHONE)
            assertEquals(point("2").x, point("0").x)
            assertEquals(point("delete").x, point("enter").x)
            tapText("+"); type("8210"); tapText("#"); expect("+8210#")
        }
        device.setOrientationNatural()
        native(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_SEARCH); korean()
        type("rkat"); tap("enter")
        await("Search action") { var value = -1; main { value = activity.lastAction }; value == EditorInfo.IME_ACTION_SEARCH }
        native(); korean(); type("rk"); tap("enter"); type("sk"); expect("가\n나")
    }

    @Test fun inputPreviewAndDuplicateNavigationAreAbsent() {
        runBlocking { SettingsRepository(activity).setFeedback("preview", true) }
        native()
        korean()
        var center = point("r")
        var downTime = SystemClock.uptimeMillis()
        touch(downTime, MotionEvent.ACTION_DOWN, center)
        try {
            assertEquals("", previewText())
            main { WindowInspector.getGlobalWindowViews().forEach { root ->
                assertNull(root.findViewById<View>(R.id.key_preview))
                assertNull(root.findViewById<View>(R.id.key_next_ime))
                assertNull(root.findViewById<View>(R.id.key_hide))
            } }
        }
        finally { touch(downTime, MotionEvent.ACTION_UP, center) }
        val types = listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        )
        types.forEach { type ->
            native(type)
            val numeric = type and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_NUMBER
            if (!numeric) korean()
            center = point(if (numeric) "1" else "r")
            downTime = SystemClock.uptimeMillis()
            touch(downTime, MotionEvent.ACTION_DOWN, center)
            try {
                SystemClock.sleep(120)
                assertEquals("Password type $type preview", "", previewText())
            } finally { touch(downTime, MotionEvent.ACTION_UP, center) }
            if (type == types.first()) screenshot("password-no-preview")
            expect(if (numeric) "1" else "ㄱ")
        }
    }

    private fun previewText(): String {
        var value: String? = null
        main { value = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull {
            it.findViewById<TextView>(R.id.key_preview)?.text?.toString()
        } }
        return value.orEmpty()
    }

    @Test fun contextLayoutsAndAllEditorActions() {
        native(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        english(); tap("a"); tapText("@"); tap("b"); tapText("."); type("com"); expect("a@b.com")
        native(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        english(); tap("a"); tapText("/"); tap("b"); expect("a/b")
        native(InputType.TYPE_CLASS_DATETIME)
        tap("2"); tapText("/"); tap("3"); tapText(":"); tap("4"); tapText("-"); tap("5"); expect("2/3:4-5")
        listOf(EditorInfo.IME_ACTION_SEARCH, EditorInfo.IME_ACTION_DONE, EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_NEXT, EditorInfo.IME_ACTION_PREVIOUS, EditorInfo.IME_ACTION_SEND).forEach { action ->
            native(InputType.TYPE_CLASS_TEXT, action)
            tap("enter")
            await("Editor action $action") { var value = -1; main { value = activity.lastAction }; value == action }
        }
        main {
            activity.primary.setImeActionLabel("Check", 42)
            activity.getSystemService(InputMethodManager::class.java).restartInput(activity.primary)
        }
        await("Custom editor action label") { key("enter").text == "Check" }
        tap("enter")
        await("Custom editor action ID") { var value = -1; main { value = activity.lastAction }; value == 42 }
    }

    @Test fun editorSwitchAndRestartDoNotDuplicateText() {
        type("rks"); expect("간")
        main {
            activity.secondary.requestFocus()
            activity.getSystemService(InputMethodManager::class.java).showSoftInput(activity.secondary, 0)
        }
        SystemClock.sleep(200); korean(); type("sk")
        await("Second editor") { var value = ""; main { value = activity.secondary.text.toString() }; value == "나" }
        expect("간")
        main {
            activity.primary.requestFocus()
            activity.getSystemService(InputMethodManager::class.java).restartInput(activity.primary)
        }
        SystemClock.sleep(200); korean(); type("rk"); expect("간가")
    }

    @Test fun rotateWhileComposingAndSplitLayout() {
        type("rks"); expect("간")
        device.setOrientationLeft()
        await("Landscape") { device.displayWidth > device.displayHeight }
        key("k"); tap("k"); expect("가나")
        runBlocking { SettingsRepository(activity).setSplit(true, true) }
        SystemClock.sleep(300)
        val left = key("t").visibleBounds; val right = key("y").visibleBounds
        assertTrue("Split gap in landscape", right.left - left.right >= 40)
        val spaces = device.findObjects(By.res(packageName, "key_space"))
        assertEquals("Space on each half", 2, spaces.size)
        val gapBefore = right.left - left.right
        runBlocking {
            val repository = SettingsRepository(activity)
            val initial = repository.settings.first()
            repository.saveSizing(initial, initial.copy(landscapeSplitGap = 112))
        }
        await("Adjustable split gap") { key("y").visibleBounds.left - key("t").visibleBounds.right > gapBefore }
        type("rkqt"); expect("가나값")
        screenshot("landscape-split")
        device.setOrientationNatural(); key("l"); tap("l"); expect("가나갑시")
        screenshot("portrait-split")
    }

    @Test fun splitKeysKeepProportionsAcrossRotationAndSymbolPages() {
        runBlocking {
            SettingsRepository(activity).restoreSettings(KeyboardSettings(portraitMode = 1, landscapeMode = 1))
        }
        for (extended in listOf(false, true)) {
            runBlocking {
                val repository = SettingsRepository(activity)
                repository.restoreSettings(repository.settings.first().copy(extendedLayout = extended))
            }
            repeat(4) { rotation ->
                val landscape = rotation % 2 == 1
                if (landscape) device.setOrientationLeft() else device.setOrientationNatural()
                await("Split display orientation") { (device.displayWidth > device.displayHeight) == landscape }
                native()
                for (page in 0..2) {
                    if (page == 1) tap("symbols") else if (page == 2) tap("more_symbols")
                    point("1")
                    main {
                        val key = WindowInspector.getGlobalWindowViews().firstNotNullOf {
                            it.findViewById<View>(R.id.key_1)?.takeIf(View::isShown)
                        }
                        val keyboard = generateSequence(key) { it.parent as? View }.filterIsInstance<KeyboardView>().first()
                        val firstRow = keyboard.findViewById<View>(R.id.key_1).parent as android.view.ViewGroup
                        val letterWidth = firstRow.getChildAt(0).width
                        val margin = (3 * keyboard.resources.displayMetrics.density + 0.5f).toInt()
                        assertTrue("Letters fill their split half", letterWidth > firstRow.width / 7)
                        assertTrue("Left keys start near the outside edge", firstRow.getChildAt(0).left <= margin + 1)
                        for (id in listOf(R.id.key_shift, R.id.key_delete, R.id.key_enter, R.id.key_delete_forward)) {
                            val control = keyboard.findViewById<View>(id) ?: continue
                            assertTrue("Control width remains proportional", control.width in 1..(letterWidth * 1.6f).toInt())
                        }
                    }
                    if (rotation < 2) screenshot("split-balanced-${if (extended) "extended" else "standard"}-${if (landscape) "landscape" else "portrait"}-$page")
                }
            }
        }
    }

    @Test fun repeatedDeletionStopsAfterReleaseAndTouchCancellation() {
        native(value = "abcdefghijklmnopqrst")
        val deletePoint = point("delete")
        device.swipe(deletePoint.x, deletePoint.y, deletePoint.x, deletePoint.y, 140)
        await("Repeat deletion") { text().length < 18 }
        val after = text(); SystemClock.sleep(250); expect(after)
        native(); korean()
        val character = point("r")
        val downTime = SystemClock.uptimeMillis()
        touch(downTime, MotionEvent.ACTION_DOWN, character)
        touch(downTime, MotionEvent.ACTION_MOVE, Point(0, 0))
        touch(downTime, MotionEvent.ACTION_UP, Point(0, 0)); expect("")
    }

    @Test fun rapidInputDoesNotLoseOrDuplicateKeys() {
        val centers = "rksk".toSet().associateWith { point(it.toString()) }
        val started = SystemClock.uptimeMillis()
        var count = 0
        repeat(10) { "rksk".forEach { key ->
            val remaining = started + count * 65 - SystemClock.uptimeMillis()
            if (remaining > 0) SystemClock.sleep(remaining)
            val point = centers.getValue(key)
            val downTime = SystemClock.uptimeMillis()
            touch(downTime, MotionEvent.ACTION_DOWN, point); touch(downTime, MotionEvent.ACTION_UP, point)
            count++
        } }
        expect("가나".repeat(10))
        val elapsed = SystemClock.uptimeMillis() - started
        File(output, "rapid-input.txt").writeText("40 injected taps; elapsedMs=$elapsed; tapsPerSecond=${40000.0 / elapsed}; exact fixture match=true\n")
    }

    @Test fun rapidOverlappingTapsAndEdgeDriftInBothOrientations() {
        for (landscape in listOf(false, true)) {
            if (landscape) device.setOrientationLeft()
            for (korean in listOf(true, false)) {
                native()
                val keyboard = liveKeyboard()
                main {
                    val key = keyboard.findViewById<TextView>(R.id.key_q)
                    if ((key.text == "ㅂ") != korean) keyboard.findViewById<View>(R.id.key_language).performClick()
                }
                val q = point("q")
                val offset = touchScreenOffset(keyboard, q)
                val w = point("w")
                val left = key("q").visibleBounds
                val right = key("w").visibleBounds
                assertEquals(q.y + offset, left.centerY())
                assertEquals(w.y + offset, right.centerY())
                val edge = (left.right + right.left) / 2
                val start = Point(edge + 1, w.y + offset)
                val drift = Point(edge - 1, start.y)
                val a = point(if (korean) "r" else "a").withOffset(offset)
                val b = point(if (korean) "k" else "l").withOffset(offset)
                val c = point(if (korean) "s" else "l").withOffset(offset)
                val d = point(if (korean) "k" else "a").withOffset(offset)
                val space = point("space").withOffset(offset)
                repeat(10) {
                    val time = SystemClock.uptimeMillis()
                    pointers(time, MotionEvent.ACTION_DOWN, listOf(start))
                    pointers(time, MotionEvent.ACTION_MOVE, listOf(drift))
                    pointers(time, MotionEvent.ACTION_UP, listOf(drift))
                }
                // A large excursion still cancels, even if the finger returns before UP.
                val time = SystemClock.uptimeMillis()
                pointers(time, MotionEvent.ACTION_DOWN, listOf(start))
                pointers(time, MotionEvent.ACTION_MOVE, listOf(q.withOffset(offset)))
                pointers(time, MotionEvent.ACTION_UP, listOf(start))
                val prefix = if (korean) "ㅈ".repeat(10) else "w".repeat(10)
                expect(prefix)

                val started = SystemClock.uptimeMillis()
                repeat(20) { i ->
                    for ((j, pair) in listOf(a to b, space to c, d to space).withIndex()) {
                        val remaining = started + (i * 3 + j) * 130 - SystemClock.uptimeMillis()
                        if (remaining > 0) SystemClock.sleep(remaining)
                        overlappingTap(pair.first, pair.second)
                    }
                }
                val expected = prefix + (if (korean) "가 나 " else "al la ").repeat(20)
                expect(expected)

                // Cancelling the first finger must unblock the already released second tap.
                overlappingTap(a, space, cancelFirst = true)
                expect("$expected ")
                if (korean) {
                    val shift = point("shift").withOffset(offset)
                    val chordTime = SystemClock.uptimeMillis()
                    val index = MotionEvent.ACTION_POINTER_INDEX_SHIFT
                    pointers(chordTime, MotionEvent.ACTION_DOWN, listOf(space))
                    pointers(chordTime, MotionEvent.ACTION_POINTER_DOWN or (1 shl index), listOf(space, shift))
                    pointers(chordTime, MotionEvent.ACTION_POINTER_DOWN or (2 shl index), listOf(space, shift, a))
                    pointers(chordTime, MotionEvent.ACTION_POINTER_UP or (2 shl index), listOf(space, shift, a))
                    pointers(chordTime, MotionEvent.ACTION_POINTER_UP or (1 shl index), listOf(space, shift))
                    pointers(chordTime, MotionEvent.ACTION_UP, listOf(space))
                    val nextTime = SystemClock.uptimeMillis()
                    pointers(nextTime, MotionEvent.ACTION_DOWN, listOf(a))
                    pointers(nextTime, MotionEvent.ACTION_UP, listOf(a))
                    expect("$expected  ㄲㄱ")
                }
                File(output, "overlapping-taps.txt").appendText(
                    "landscape=$landscape korean=$korean driftTaps=10 orderedTaps=120 " +
                        "largeMoveCancelled=true releasedTapSurvivedCancel=true exactMatch=true\n")
            }
        }
    }

    private fun overlappingTap(first: Point, second: Point, cancelFirst: Boolean = false) {
        val time = SystemClock.uptimeMillis()
        val index = 1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT
        pointers(time, MotionEvent.ACTION_DOWN, listOf(first))
        pointers(time, MotionEvent.ACTION_POINTER_DOWN or index, listOf(first, second))
        pointers(time, MotionEvent.ACTION_POINTER_UP or index, listOf(first, second))
        pointers(time, if (cancelFirst) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, listOf(first))
    }

    private fun liveKeyboard(): KeyboardView {
        lateinit var keyboard: KeyboardView
        main {
            val key = WindowInspector.getGlobalWindowViews().firstNotNullOf {
                it.findViewById<View>(R.id.key_delete)?.takeIf(View::isShown)
            }
            keyboard = generateSequence(key) { it.parent as? View }.filterIsInstance<KeyboardView>().first()
        }
        return keyboard
    }

    private fun Point.withOffset(yOffset: Int) = Point(x, y + yOffset)

    /** Some launches leave the IME surface translated relative to getLocationOnScreen. */
    private fun touchScreenOffset(keyboard: KeyboardView, point: Point, verify: Boolean = true): Int {
        var receivedY: Int? = null
        main {
            keyboard.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    val location = IntArray(2)
                    keyboard.getLocationOnScreen(location)
                    receivedY = location[1] + event.y.toInt()
                }
                false
            }
        }
        fun probe(p: Point): Int {
            receivedY = null
            val time = SystemClock.uptimeMillis()
            pointers(time, MotionEvent.ACTION_DOWN, listOf(p))
            pointers(time, MotionEvent.ACTION_CANCEL, listOf(p))
            return checkNotNull(receivedY) { "Calibration touch did not reach the IME" }
        }
        try {
            val offset = point.y - probe(point)
            if (verify) assertEquals(point.y, probe(point.withOffset(offset)))
            return offset
        } finally {
            main { keyboard.setOnTouchListener(null) }
        }
    }

    @Test fun heldShiftAndLetterUseIndependentPointers() {
        val shift = point("shift")
        val letter = point("r")
        val downTime = SystemClock.uptimeMillis()
        pointers(downTime, MotionEvent.ACTION_DOWN, listOf(shift))
        pointers(downTime, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(shift, letter))
        pointers(downTime, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(shift, letter))
        pointers(downTime, MotionEvent.ACTION_UP, listOf(shift))
        tap("k"); expect("까")
        assertTrue("Shift released after chord", key("shift").contentDescription.contains("꺼짐"))
    }

    @Test fun simultaneousLettersFollowPointerIdsAndCancellationStopsLongPress() {
        val consonant = point("r")
        val vowel = point("k")
        val downTime = SystemClock.uptimeMillis()
        pointers(downTime, MotionEvent.ACTION_DOWN, listOf(consonant))
        pointers(downTime, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            listOf(consonant, vowel))
        pointers(downTime, MotionEvent.ACTION_POINTER_UP, listOf(consonant, vowel))
        pointers(downTime, MotionEvent.ACTION_UP, listOf(vowel), ids = listOf(1))
        expect("가")

        val cancelTime = SystemClock.uptimeMillis()
        touch(cancelTime, MotionEvent.ACTION_DOWN, consonant)
        touch(cancelTime, MotionEvent.ACTION_CANCEL, consonant)
        SystemClock.sleep(400)
        expect("가")
    }

    @Test fun narrowGapsUseNearestKeyExceptInPasswordsAndSplitCenter() {
        val repository = SettingsRepository(activity)
        val spaces = By.res(packageName, "key_space")
        fun gapPoints(): List<Point> {
            val q = key("q").visibleBounds
            val w = key("w").visibleBounds
            val a = key("a").visibleBounds
            val shift = device.findObjects(By.res(packageName, "key_shift")).minBy { it.visibleBounds.left }.visibleBounds
            val z = key("z").visibleBounds
            val overlapLeft = maxOf(q.left, a.left)
            val overlapRight = minOf(q.right, a.right)
            assertTrue("The upper and lower keys overlap horizontally", overlapLeft < overlapRight)
            assertTrue(w.left - q.right >= 2 && a.top - q.bottom >= 2 && z.left - shift.right >= 2)
            val x = (overlapLeft + overlapRight) / 2
            return listOf(Point(q.right, q.centerY()), Point(w.left - 1, w.centerY()),
                Point(x, q.bottom), Point(x, a.top - 1),
                Point(shift.right, shift.centerY()), Point(z.left - 1, z.centerY()))
        }
        for (landscape in listOf(false, true)) {
            if (landscape) device.setOrientationLeft()
            runBlocking { repository.setOrientationMode(landscape, 0) }
            native(); korean()
            await("Full keyboard") { device.findObjects(spaces).size == 1 }
            val gaps = gapPoints()
            gaps.take(5).forEach { device.click(it.x, it.y) }
            val q = key("q").visibleBounds
            device.click(q.centerX(), q.centerY()) // The gap next to Shift arms the shifted Q.
            device.click(gaps.last().x, gaps.last().y)
            expect("ㅂㅈㅂㅁㅃㅋ")

            native(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD); korean()
            gapPoints().forEach { device.click(it.x, it.y) }
            val passwordKey = key("q").visibleBounds
            device.click(passwordKey.centerX(), passwordKey.centerY())
            expect("ㅂ")

            native(); korean()
            runBlocking { repository.setOrientationMode(landscape, 1) }
            await("Split mode enabled") { device.findObjects(spaces).size == 2 }
            val splitLeft = key("t").visibleBounds
            val splitRight = key("y").visibleBounds
            val rowBelow = key("a").visibleBounds
            assertTrue(splitRight.left > splitLeft.right && rowBelow.top > splitLeft.bottom)
            val splitCenter = (splitLeft.right + splitRight.left) / 2
            device.click(splitCenter, splitLeft.centerY())
            device.click(splitCenter, (splitLeft.bottom + rowBelow.top) / 2)
            expect("")
        }
    }

    @Test fun spatialCorrectionIsOptInAndSkipsPasswordFields() {
        point("w")
        lateinit var keyboard: KeyboardView
        var original: SpatialStatsRepository? = null
        var profile = 0
        lateinit var descriptor: io.github.minjj.keyboard.touch.LayoutDescriptor
        main {
            val letter = WindowInspector.getGlobalWindowViews().firstNotNullOf { it.findViewById<View>(R.id.key_w) }
            keyboard = generateSequence(letter) { it.parent as? View }.filterIsInstance<KeyboardView>().first()
            original = keyboard.spatialStats
            profile = keyboard.spatialProfile()
            descriptor = checkNotNull(keyboard.learningLayout)
        }
        val file = File.createTempFile("spatial-fixture", ".stats", activity.cacheDir)
        val fixture = SpatialModel().withLayout(profile, descriptor, spatialDay()).updated(profile, spatialDay(),
            List(400) { TouchSample(2, 190, 190) } + List(400) { TouchSample(11, 0, 190) }).encode()
        file.writeText(fixture)
        lateinit var stats: SpatialStatsRepository
        main {
            stats = SpatialStatsRepository(AtomicFile(file))
            keyboard.spatialStats = stats
        }
        try {
            await("Isolated spatial model loaded") {
                var loaded = false
                main { loaded = stats.model.encode() == fixture }
                loaded
            }
            fun fingerTap(edge: Point) {
                val time = SystemClock.uptimeMillis()
                pointers(time, MotionEvent.ACTION_DOWN, listOf(edge))
                pointers(time, MotionEvent.ACTION_UP, listOf(edge))
            }
            fun tapLeftEdgeOfW() {
                point("w")
                val w = key("w").visibleBounds
                fingerTap(Point(w.left + 1, w.centerY()))
            }
            fun upperEdgeOfA(): Point {
                point("a")
                val a = key("a").visibleBounds
                val q = key("q").visibleBounds
                val left = maxOf(a.left, q.left)
                val right = minOf(a.right, q.right)
                assertTrue("Q and A overlap horizontally", left < right)
                return Point((left + right) / 2, a.top + 1)
            }
            tapLeftEdgeOfW()
            expect("ㅈ")
            tap("delete")
            expect("")

            runBlocking { SettingsRepository(activity).setOption("touchCorrection", true) }
            tapLeftEdgeOfW()
            expect("ㅂ")
            fingerTap(upperEdgeOfA())
            expect("ㅂㅂ")
            val outerKey = key("q").visibleBounds
            val blank = Point(outerKey.left - 1, outerKey.centerY())
            fingerTap(blank)
            expect("ㅂㅂ")

            val outcomes = mutableListOf<Pair<TouchDiagnostic, Long>>()
            val previousObserver = keyboard.touchDiagnostics
            main {
                keyboard.touchDiagnostics = object : TouchDiagnosticObserver {
                    private var tapOrdinal = 0
                    override fun onDown(delayMillis: Long) = ++tapOrdinal
                    override fun record(event: TouchDiagnostic, value: Long) {
                        if (event == TouchDiagnostic.NO_KEY || event == TouchDiagnostic.CORRECTION) outcomes += event to value
                    }
                }
            }
            try {
                val q = key("q").visibleBounds
                val w = key("w").visibleBounds
                fingerTap(blank)
                overlappingTap(Point(q.centerX(), q.centerY()), Point(w.left + 1, w.centerY()))
                expect("ㅂㅂㅂㅂ")
                main { assertEquals(listOf(TouchDiagnostic.NO_KEY to 1L, TouchDiagnostic.CORRECTION to 3L), outcomes) }
            } finally {
                main { keyboard.touchDiagnostics = previousObserver }
            }

            native(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            korean()
            tapLeftEdgeOfW()
            expect("ㅈ")
            fingerTap(upperEdgeOfA())
            expect("ㅈㅁ")
        } finally {
            main { keyboard.spatialStats = original }
            file.delete()
        }
    }

    @Test fun contextualTouchUsesPreviousWordWithoutChangingCenterTaps() {
        english()
        runBlocking {
            val repository = SettingsRepository(activity)
            repository.setOption("contextualTouch", true)
            repository.setOption("wordSuggestions", true)
        }
        type("good")
        key("word_suggestion_0") // Wait for the real dictionary to load before tapping the boundary.
        tap("space")
        val n = key("n").visibleBounds
        val m = key("m").visibleBounds
        val edge = Point((n.right + m.left) / 2 - 1, n.centerY())
        val time = SystemClock.uptimeMillis()
        pointers(time, MotionEvent.ACTION_DOWN, listOf(edge))
        pointers(time, MotionEvent.ACTION_UP, listOf(edge))
        expect("good m")
        tap("space"); tap("n")
        expect("good m n")
    }

    @Test fun wordSuggestionsRecoverAtExistingBoundariesAfterNewlineAndClearing() {
        runBlocking { SettingsRepository(activity).setOption("wordSuggestions", true) }
        native(value = "기존 문장 ")
        korean()
        type("rksk")
        expect("기존 문장 가나")
        key("word_suggestion_0")
        tap("enter")
        type("rksk")
        expect("기존 문장 가나\n가나")
        key("word_suggestion_0")

        native()
        korean()
        type("rk")
        tap("delete"); tap("delete")
        expect("")
        type("rksk")
        expect("가나")
        key("word_suggestion_0")
    }

    @Test fun selectingKoreanWordAndUndoKeepsCompositionAndHidesCandidatesInPassword() {
        runBlocking { SettingsRepository(activity).setOption("wordSuggestions", true) }
        type("rksk")
        expect("가나")
        val word = key("word_suggestion_0").text
        val keys = io.github.minjj.keyboard.hangul.Dubeolsik.keysFor(word)
        assertTrue("Candidate extends the physical key sequence", keys?.startsWith("rksk") == true && word != "가나")
        tap("word_suggestion_0")
        expect(word)
        tap("delete")
        expect("가나")
        tap("delete")
        expect("가ㄴ")

        native(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        korean()
        type("rksk")
        expect("가나")
        assertFalse(device.hasObject(By.res(packageName, "key_word_suggestion_0")))
    }

    @Test fun heldShiftDeletesAndRestoresWholeWords() {
        val original = "first 둘째 last!"
        native(value = original)
        val shift = point("shift")
        val delete = point("delete")
        val left = Point(delete.x - 2 * dp(18), delete.y)
        val reverse = Point(delete.x - dp(18), delete.y)
        val down = SystemClock.uptimeMillis()
        pointers(down, MotionEvent.ACTION_DOWN, listOf(shift))
        pointers(down, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(shift, delete))
        SystemClock.sleep(350)
        expect(original)
        pointers(down, MotionEvent.ACTION_MOVE, listOf(shift, left))
        expect("first ")
        pointers(down, MotionEvent.ACTION_MOVE, listOf(shift, reverse))
        expect("first 둘째 ")
        pointers(down, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(shift, reverse))
        pointers(down, MotionEvent.ACTION_UP, listOf(shift))
        assertTrue(key("shift").contentDescription.contains("꺼짐"))
        slideDelete(1)
        expect(original)
        slideDelete(-1)
        expect("first 둘째 last")
    }

    @Test fun samsungSymbolPagesInBothOrientations() {
        for (landscape in listOf(false, true)) {
            if (landscape) {
                device.setOrientationLeft()
                await("Landscape display") { device.displayWidth > device.displayHeight }
            }
            native()
            tap("symbols")
            val suffix = if (landscape) "landscape" else "portrait"
            if (landscape) {
                assertTrue(point("delete_forward").y < point("delete").y)
                assertTrue(point("delete").y < point("enter").y)
            } else {
                assertFalse(device.hasObject(By.res(packageName, "key_delete_forward")))
                assertEquals(point("more_symbols").y, point("delete").y)
                assertEquals(point("space").y, point("enter").y)
            }
            screenshot("symbols-first-$suffix")
            tap("1"); tapText("×"); tapText(if (landscape) "₩" else "$")
            tap("more_symbols")
            if (!landscape) tapText("₩")
            tapText("€")
            screenshot("symbols-second-$suffix")
            tapText("♡"); tapText("☆")
            tap("delete")
            tap("letters")
            tap("r"); tap("k")
            expect(if (landscape) "1×₩€♡가" else "1×$₩€♡가")
        }
    }

    @Test fun automaticEnglishCapitalizationFollowsEditor() {
        native(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        english(); type("hello"); expect("Hello")
        tapText("."); tap("space"); type("world"); expect("Hello. World")
    }

    @Test fun webInputTextareaAndContentEditable() {
        await("Web fixture loaded") { js("!!document.getElementById('probe')") == "true" }
        listOf("probe", "area", "editable").forEach { id ->
            main { activity.web.requestFocus() }
            js("document.getElementById('$id').focus(); true")
            val rect = JSONArray(js("JSON.stringify((()=>{const r=document.getElementById('$id').getBoundingClientRect();return [(r.x+r.width/2)*devicePixelRatio,(r.y+r.height/2)*devicePixelRatio]})())"))
            val location = IntArray(2)
            main { activity.web.getLocationOnScreen(location) }
            device.click(location[0] + rect.getDouble(0).toInt(), location[1] + rect.getDouble(1).toInt())
            SystemClock.sleep(250); korean(); type("rksk")
            val property = if (id == "editable") "textContent" else "value"
            await("Web $id Hangul; actual=${js("document.getElementById('$id').$property")}; native=${text()}") {
                js("document.getElementById('$id').$property") == "가나"
            }
            tap("delete")
            await("Web $id delete") { js("document.getElementById('$id').$property") == "가ㄴ" }
        }
        screenshot("webview-input")
    }

    @Test fun cursorKeysAndForwardDeleteRespectGraphemes() {
        native(value = "A👨‍👩‍👧‍👦B")
        tap("cursor_left"); tap("cursor_left"); tap("1"); expect("A1👨‍👩‍👧‍👦B")
        device.setOrientationLeft()
        await("Extended tablet layout") { device.hasObject(By.res(packageName, "key_delete_forward")) }
        tap("delete_forward"); expect("A1B")
        tap("cursor_right"); tap("2"); expect("A1B2")
        screenshot("extended-tablet-editing")
    }

    @Test fun spaceCursorGestureAndLanguageSwipeDoNotInsertSpaces() {
        runBlocking { SettingsRepository(activity).setOrientationMode(landscape = true, mode = 1) }
        val cursorStatus = activity.getString(R.string.cursor_mode_active)
        fun spaceState(): CharSequence? {
            var state: CharSequence? = null
            main {
                state = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull {
                    it.findViewById<View>(R.id.key_space)?.takeIf(View::isShown)
                }?.stateDescription
            }
            return state
        }
        for (landscape in listOf(false, true)) {
            if (landscape) device.setOrientationLeft()
            val orientation = if (landscape) "landscape-split" else "portrait"
            native(value = "가나"); korean()
            var center = point("space")
            var time = SystemClock.uptimeMillis()
            touch(time, MotionEvent.ACTION_DOWN, center)
            await("Cursor mode shown") { spaceState() == cursorStatus }
            // The accessibility state is set before the frame is presented.
            SystemClock.sleep(80)
            screenshot("space-cursor-$orientation")
            val moved = Point(center.x - 2 * dp(14), center.y)
            touch(time, MotionEvent.ACTION_MOVE, moved)
            assertEquals(cursorStatus, spaceState())
            touch(time, MotionEvent.ACTION_UP, moved)
            await("Cursor mode cleared") { spaceState() == null }
            tap("1"); expect("1가나")
            native(); korean()
            center = point("space"); time = SystemClock.uptimeMillis()
            val delta = dp(if (landscape) -70 else 70)
            touch(time, MotionEvent.ACTION_DOWN, center)
            touch(time, MotionEvent.ACTION_MOVE, Point(center.x + delta, center.y))
            touch(time, MotionEvent.ACTION_UP, Point(center.x + delta, center.y))
            await("Space swipe changed language") { key("q").text.lowercase() == "q" }
            expect(""); tap("a"); expect("a")
        }
    }

    @Test fun shiftSpaceCursorGestureSelectsAndReversesWithoutInsertingSpaces() {
        runBlocking { SettingsRepository(activity).setOrientationMode(landscape = true, mode = 1) }
        for (landscape in listOf(false, true)) {
            if (landscape) device.setOrientationLeft()
            native(value = "가👨‍👩‍👧‍👦나"); korean()
            val shift = point("shift"); val space = point("space")
            val time = SystemClock.uptimeMillis()
            val second = 1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT
            pointers(time, MotionEvent.ACTION_DOWN, listOf(shift))
            pointers(time, MotionEvent.ACTION_POINTER_DOWN or second, listOf(shift, space))
            SystemClock.sleep(450)
            fun move(steps: Int, expected: String) {
                pointers(time, MotionEvent.ACTION_MOVE, listOf(shift, Point(space.x + steps * dp(14), space.y)))
                await("Shift space selection") {
                    var selected = ""
                    main {
                        val start = activity.primary.selectionStart
                        val end = activity.primary.selectionEnd
                        selected = activity.primary.text.substring(minOf(start, end), maxOf(start, end))
                    }
                    selected == expected
                }
            }
            move(-2, "👨‍👩‍👧‍👦나")
            move(-1, "나")
            pointers(time, MotionEvent.ACTION_POINTER_UP or second,
                listOf(shift, Point(space.x - dp(14), space.y)))
            pointers(time, MotionEvent.ACTION_UP, listOf(shift))
            assertTrue("Shift chord does not toggle Shift", key("shift").contentDescription.contains("꺼짐"))
            tap("1"); expect("가👨‍👩‍👧‍👦1")
        }
    }

    @Test fun characterSettingsEditorsPersistAndResetInBothOrientations() {
        val repository = SettingsRepository(activity)
        for ((orientation, suffix) in listOf(Configuration.ORIENTATION_PORTRAIT to "portrait",
            Configuration.ORIENTATION_LANDSCAPE to "landscape")) {
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) device.setOrientationLeft()
            device.pressBack()
            instrumentation.startActivitySync(Intent(instrumentation.targetContext, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            val home = focusedActivity<SettingsActivity>(orientation)
            val menu = screenPoint(home.window.decorView) {
                it.contentDescription?.startsWith(home.getString(R.string.characters_title)) == true
            }
            assertTrue(device.click(menu.x, menu.y))
            val parent = focusedActivity<io.github.minjj.keyboard.settings.CharacterSettingsActivity>(orientation, R.string.characters_title)
            assertTrue(screenViews(parent.window.decorView).any {
                it is android.widget.Switch && it.contentDescription?.startsWith(parent.getString(R.string.secondary_characters)) == true
            })
            for (secondary in listOf(true, false)) {
                val title = if (secondary) R.string.secondary_editor_title else R.string.symbol_editor_title
                val point = screenPoint(parent.window.decorView) { it.contentDescription == parent.getString(title) }
                assertTrue(device.click(point.x, point.y))
                val editor = focusedActivity<io.github.minjj.keyboard.settings.CharacterSettingsActivity>(orientation, title)
                val id = if (secondary) "ko:1" else "normal:1:0:0"
                fun openKey() {
                    await("Character grid ready") {
                        var ready = false
                        main { ready = screenViews(editor.window.decorView).any { it.tag == id } }
                        ready
                    }
                    val key = screenPoint(editor.window.decorView) { it.tag == id }
                    assertTrue(device.click(key.x, key.y))
                    assertNotNull(device.wait(Until.findObject(By.res(packageName, "character_value")), 3000))
                }
                openKey()
                device.findObject(By.res(packageName, "character_value")).text = "★"
                device.findObject(By.res("android", "button1")).click()
                await("Custom assignment saved in $suffix") {
                    runBlocking { repository.settings.first().let {
                        (if (secondary) it.secondaryOverrides else it.symbolOverrides)[id] == "★"
                    } }
                }
                assertSame(editor, focusedActivity<io.github.minjj.keyboard.settings.CharacterSettingsActivity>(orientation))
                SystemClock.sleep(250) // Let the dialog dismissal animation finish before capturing the grid.
                screenshot("character-${if (secondary) "secondary" else "symbol"}-$suffix")
                openKey()
                assertEquals("★", device.findObject(By.res(packageName, "character_value")).text)
                device.findObject(By.res("android", "button3")).click()
                await("Custom assignment reset in $suffix") {
                    runBlocking { repository.settings.first().let {
                        !(if (secondary) it.secondaryOverrides else it.symbolOverrides).containsKey(id)
                    } }
                }
                device.pressBack()
                focusedActivity<io.github.minjj.keyboard.settings.CharacterSettingsActivity>(orientation, R.string.characters_title)
            }
            device.pressBack()
        }
    }

    @Test fun characterEditorKeepsUnsavedDraftAcrossRotation() {
        val editorClass = io.github.minjj.keyboard.settings.CharacterSettingsActivity::class.java
        val page = R.string.secondary_editor_title
        device.pressBack()
        instrumentation.startActivitySync(Intent(instrumentation.targetContext, editorClass)
            .putExtra(io.github.minjj.keyboard.settings.CharacterSettingsActivity.PAGE, page)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val editor = focusedActivity<io.github.minjj.keyboard.settings.CharacterSettingsActivity>(Configuration.ORIENTATION_PORTRAIT, page)
        await("Character grid loaded") {
            var ready = false
            main { ready = screenViews(editor.window.decorView).any { it.tag == "ko:1" } }
            ready
        }
        val key = screenPoint(editor.window.decorView) { it.tag == "ko:1" }
        assertTrue(device.click(key.x, key.y))
        val input = device.wait(Until.findObject(By.res(packageName, "character_value")), 3000)!!
        input.text = "☆"
        device.setOrientationLeft()
        await("Unsaved character draft restored") {
            var rotated = false
            main {
                rotated = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<io.github.minjj.keyboard.settings.CharacterSettingsActivity>()
                    .any { it !== editor && it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            }
            rotated && device.findObject(By.res(packageName, "character_value"))?.text == "☆"
        }
        screenshot("character-draft-after-rotation")
        device.findObject(By.res("android", "button1")).click()
        await("Restored draft saved") {
            runBlocking { SettingsRepository(activity).settings.first().secondaryOverrides["ko:1"] == "☆" }
        }
    }

    @Test fun customCharactersTypeInAllKeyboardsAcrossRotation() {
        val repository = SettingsRepository(activity)
        runBlocking {
            repository.setCharacter(true, "ko:r", "★")
            repository.setCharacter(true, "en:r", "♥")
            for (variant in listOf("normal", "extended")) {
                repository.setCharacter(false, "$variant:1:1:0", "🙂")
                repository.setCharacter(true, "symbol:$variant:1:1:0", "§")
            }
        }
        for (landscape in listOf(false, true)) {
            if (landscape) device.setOrientationLeft()
            native(); korean(); holdKey("r", 420); expect("★")
            native(); english(); holdKey("r", 420); expect("♥")
            tap("symbols")
            val customized = device.wait(Until.findObject(By.pkg(packageName).descStartsWith("🙂,")), 3000)
                ?: throw AssertionError("Custom symbol with secondary hint")
            customized.click(); expect("♥🙂")
            val bounds = customized.visibleBounds
            val key = Point(bounds.centerX(), bounds.centerY())
            val time = SystemClock.uptimeMillis()
            touch(time, MotionEvent.ACTION_DOWN, key)
            SystemClock.sleep(420)
            touch(time, MotionEvent.ACTION_UP, key)
            expect("♥🙂§")
            runBlocking { repository.setOption("secondary", false) }
            native(); english(); holdKey("r", 420); expect("r")
            runBlocking { repository.setOption("secondary", true) }
        }
    }

    @Test fun secondaryLongPressHonorsDelayAndProducesOnlyOneCharacter() {
        english(); holdKey("a", 420); expect("!")
        native(); english(); holdKey("comma", 420); holdKey("period", 420); expect("!?")
        tap("comma"); tap("period"); expect("!?,.")
        device.setOrientationLeft()
        await("Extended punctuation layout") { device.hasObject(By.res(packageName, "key_delete_forward")) }
        native(); korean(); holdKey("comma", 420); holdKey("period", 420); expect("!?")
        screenshot("punctuation-long-press")
        runBlocking { SettingsRepository(activity).setOption("secondary", false) }
        native(); holdKey("comma", 420); holdKey("period", 420); expect(",.")
        runBlocking { SettingsRepository(activity).setOption("secondary", true) }
        native(); korean(); holdKey("r", 420); tap("k"); expect("까")
        runBlocking { SettingsRepository(activity).setLongPress(500) }
        native(); english(); holdKey("a", 250); expect("a")
        holdKey("a", 650); expect("a!")
    }

    @Test fun toolbarModeAndAppearanceOptionsPreserveComposition() {
        type("rks"); expect("간")
        tap("mode")
        await("Quick split mode") { device.findObjects(By.res(packageName, "key_space")).size == 2 }
        tap("k"); expect("가나")
        runBlocking {
            val repository = SettingsRepository(activity)
            repository.setOption("numberRow", false); repository.setFontPercent(125); repository.setOption("highContrast", true)
        }
        await("Number row hidden") { !device.hasObject(By.res(packageName, "key_1")) }
        type("rk"); expect("가나가")
        screenshot("high-contrast-split")
        tap("mode")
        await("Quick normal mode") { device.findObjects(By.res(packageName, "key_space")).size == 1 }
        device.pressBack()
        await("Keyboard hidden") { !device.hasObject(By.res(packageName, "key_q")) }
    }

    @Test fun doubleSpaceShortcutIsEnglishOnlyAndOptIn() {
        runBlocking { SettingsRepository(activity).setOption("doubleSpace", true) }
        native(); english(); type("hello"); doubleSpaceTap(); expect("hello. ")
        native(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        english(); tap("a"); doubleSpaceTap(); expect("a  ")
        native(); korean(); type("rk"); doubleSpaceTap(); expect("가  ")
    }

    @Test fun layoutPagesPreviewAndPersistActualUserChanges() {
        device.pressBack()
        instrumentation.startActivitySync(Intent(instrumentation.targetContext, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val layout = openLayoutMenu()
        waitForLayoutPreview(layout)
        val bounds = screenBounds(layout.window.decorView) { it.contentDescription == layout.getString(R.string.font_title) }
        val before = previewValue { it.findViewById<TextView>(R.id.key_q).textSize }
        val repository = SettingsRepository(activity)
        val start = Point(bounds.centerX() - bounds.width() / 6, bounds.centerY())
        val end = Point(bounds.right - dp(8), bounds.centerY())
        val time = SystemClock.uptimeMillis()
        touch(time, MotionEvent.ACTION_DOWN, start)
        try {
            moveAndSettle(time, start, end)
            await("Preview font grows while dragging") { previewValue { it.findViewById<TextView>(R.id.key_q).textSize } > before }
            assertEquals(100, runBlocking { repository.settings.first().fontPercent })
        } finally { touch(time, MotionEvent.ACTION_UP, end) }
        await("Font preference persisted") { runBlocking { repository.settings.first().fontPercent > 100 } }
        screenshot("settings-font-preview")
        fun tapSwitch(title: Int) {
            val point = screenPoint(layout.window.decorView) { it is android.widget.Switch && it.text == layout.getString(title) }
            assertTrue(device.click(point.x, point.y))
        }
        tapSwitch(R.string.high_contrast)
        tapSwitch(R.string.toolbar)
        await("Contrast and toolbar saved from layout") {
            runBlocking { repository.settings.first().let { it.highContrast && !it.toolbar } } &&
                previewValue { it.findViewById<View>(R.id.key_settings)?.isShown == false }
        }
        tapSwitch(R.string.number_row)
        await("Number row removed from preview and saved") {
            previewValue { it.findViewById<View>(R.id.key_1) == null } && runBlocking { !repository.settings.first().numberRow }
        }
        screenshot("settings-layout-preview")
        device.pressBack()
        device.wait(Until.findObject(By.descStartsWith("키보드 모드,")), 3000).click()
        device.wait(Until.findObject(By.text("분할 키보드")), 3000).click()
        await("Portrait mode change is visible and saved") {
            previewValue { (it as io.github.minjj.keyboard.ui.KeyboardView).splitActive } &&
                runBlocking { repository.settings.first().portraitMode == 1 }
        }
        screenshot("settings-mode-preview")
    }

    @Test fun compactSettingsAndInputPracticeInBothOrientations() {
        device.pressBack()
        val descriptions = listOf(R.string.layout_summary, R.string.delete_gesture_description,
            R.string.space_gestures_summary, R.string.feedback_summary, R.string.auto_caps_description,
            R.string.double_space_description)

        for ((orientation, name) in listOf(Configuration.ORIENTATION_PORTRAIT to "portrait",
            Configuration.ORIENTATION_LANDSCAPE to "landscape")) {
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                device.executeShellCommand("wm user-rotation lock 1")
                await("Device display rotates to landscape") { device.displayWidth > device.displayHeight }
            }
            instrumentation.startActivitySync(Intent(instrumentation.targetContext, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            val home = focusedActivity<SettingsActivity>(orientation)
            val defaultMode = home.getString(R.string.mode_inherit_short)
            val modeSummary = home.getString(R.string.mode_summary, defaultMode, defaultMode)
            await("Menu summaries measured in $name") {
                var ready = false
                main {
                    ready = screenViews(home.window.decorView).filterIsInstance<TextView>()
                        .any { it.text == modeSummary && it.layout != null }
                }
                ready
            }
            val measured = mutableListOf<String>()
            main {
                val views = screenViews(home.window.decorView).toList()
                assertTrue(views.none { it is EditText })
                val texts = views.filterIsInstance<TextView>()
                val layoutOnly = listOf(R.string.font_title, R.string.size_title, R.string.high_contrast, R.string.toolbar)
                    .map { home.getString(it) }
                assertTrue(texts.none { it.text in layoutOnly })
                val metrics = home.resources.displayMetrics
                val captionSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, metrics)
                for (text in descriptions.map { home.getString(it) } + modeSummary) {
                    val caption = texts.single { it.text.toString() == text }
                    val row = (caption.parent as View).parent as View
                    assertEquals(text, captionSize, caption.textSize, 0.01f)
                    assertEquals(text, 1, caption.lineCount)
                    assertEquals(text, 0, caption.layout.getEllipsisCount(0))
                    assertTrue(text, row.height >= dp(48))
                    measured += "$name: ${row.height / metrics.density} dp, ${caption.lineCount} line, $text"
                }
            }
            File(output, "compact-settings-measurements.txt").appendText(measured.joinToString("\n", postfix = "\n"))
            screenshot("compact-settings-$name")
            val practiceTitle = home.getString(R.string.section_test)
            val entry = screenPoint(home.window.decorView) { it.contentDescription == practiceTitle }
            screenshot("compact-settings-input-$name")
            assertTrue(device.click(entry.x, entry.y))
            val practice = focusedActivity<InputPracticeActivity>(orientation)
            val hints = listOf(R.string.test_hint, R.string.email_hint, R.string.number_hint, R.string.password_hint, R.string.search_hint)
            main {
                val fields = screenViews(practice.window.decorView).filterIsInstance<EditText>().toList()
                assertEquals(hints.map { practice.getString(it) }, fields.map { it.hint.toString() })
                assertTrue(fields.none { it.hasFocus() })
            }
            screenshot("input-practice-$name")
            for (field in hints) {
                val hint = practice.getString(field)
                val input = screenPoint(practice.window.decorView) { it is EditText && it.hint == hint }
                assertTrue(device.click(input.x, input.y))
                key("delete")
                tap(if (field == R.string.number_hint) "1" else "a")
                await("Practice editor accepts keyboard input in $name") {
                    var entered = false
                    main {
                        val editor = practice.currentFocus as? EditText
                        entered = editor?.hint == hint && editor.text.isNotEmpty()
                    }
                    entered
                }
                if (field == R.string.search_hint) {
                    assertEquals(practice.getString(R.string.action_search), key("enter").contentDescription)
                    tap("enter")
                    SystemClock.sleep(250)
                    screenshot("input-practice-search-$name")
                }
                if (device.hasObject(By.res(packageName, "key_delete"))) device.pressBack()
                await("Practice keyboard hidden in $name") { !device.hasObject(By.res(packageName, "key_delete")) }
            }
            val back = practice.getString(R.string.layout_back)
            val backButton = screenPoint(practice.window.decorView) { it.contentDescription == back }
            assertTrue(device.click(backButton.x, backButton.y))
            focusedActivity<SettingsActivity>(orientation)
        }
    }

    private inline fun <reified T : android.app.Activity> focusedActivity(orientation: Int, layoutPage: Int? = null): T {
        var focused: T? = null
        await("${T::class.java.simpleName} ready in orientation $orientation (layout page $layoutPage)") {
            main {
                focused = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<T>().firstOrNull {
                        it.hasWindowFocus() && it.resources.configuration.orientation == orientation && it.window.decorView.isLaidOut &&
                            (layoutPage == null || screenViews(it.window.decorView).filterIsInstance<TextView>().any { heading ->
                                heading.isAccessibilityHeading && heading.text == it.getString(layoutPage)
                            })
                    }
            }
            focused != null
        }
        return checkNotNull(focused)
    }

    private fun screenViews(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(screenViews(view.getChildAt(index)))
    }

    private fun screenPoint(root: View, matches: (View) -> Boolean): Point =
        screenBounds(root, matches).let { Point(it.centerX(), it.centerY()) }

    private fun screenBounds(root: View, matches: (View) -> Boolean): Rect {
        val bounds = Rect()
        main {
            val view = screenViews(root).single(matches)
            view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true)
            assertTrue("Requested control is visible", view.getGlobalVisibleRect(bounds))
        }
        return bounds
    }

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density + 0.5f).toInt()
    @Test fun backspaceSlideDeletesAndRestoresWithoutSplittingGraphemes() {
        assertEquals(2, io.github.minjj.keyboard.ime.GraphemeDeletion.previousLength("앞😀"))
        val original = "A👨‍👩‍👧‍👦e\u0301한글Z"
        native(value = original)
        val center = point("delete"); val time = SystemClock.uptimeMillis()
        val left = Point(center.x - dp(18) * 3, center.y)
        val reverse = Point(center.x - dp(18), center.y)
        var end = center
        touch(time, MotionEvent.ACTION_DOWN, center)
        try {
            moveAndSettle(time, center, left); end = left; expect("A👨‍👩‍👧‍👦e\u0301")
            moveAndSettle(time, left, reverse); end = reverse; expect("A👨‍👩‍👧‍👦e\u0301한글")
        } finally { touch(time, MotionEvent.ACTION_UP, end) }
        slideDelete(1); expect(original)
        slideDelete(-2); expect("A👨‍👩‍👧‍👦e\u0301한")
        tap("1"); slideDelete(1); expect("A👨‍👩‍👧‍👦e\u0301한1")
        native(value = "앞😀뒤"); slideDelete(-2); expect("앞")
        tap("cursor_left"); slideDelete(1); expect("앞")
        native(); korean(); type("rkqt"); slideDelete(-1); expect(""); slideDelete(1); expect("값")
        screenshot("slide-delete-undo")
        native(value = "abcde")
        val start = point("delete"); val down = SystemClock.uptimeMillis()
        touch(down, MotionEvent.ACTION_DOWN, start)
        try { moveAndSettle(down, start, Point(start.x - 3 * dp(18), start.y)); expect("ab") }
        finally { touch(down, MotionEvent.ACTION_UP, Point(start.x - 2 * dp(18), start.y)) }
        expect("abc") // An overshooting MOVE must settle back to the release coordinate.
    }

    @Test fun deleteSlideCancellationPasswordAndOptionOffDoNotRestoreText() {
        native(value = "abcdef")
        var center = point("delete"); var time = SystemClock.uptimeMillis()
        touch(time, MotionEvent.ACTION_DOWN, center)
        val outside = Point(center.x, center.y - dp(80))
        try { moveAndSettle(time, center, outside) }
        finally { touch(time, MotionEvent.ACTION_UP, outside) }
        expect("abcdef")
        native(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, value = "secret")
        slideDelete(-5); expect("secret")
        runBlocking { SettingsRepository(activity).setOption("deleteGesture", false) }
        native(value = "abcdef"); slideDelete(-5); expect("abcdef")
        tap("delete"); expect("abcde")
    }

    @Test fun clipboardTextHistoryPinsDeletesPersistsAndRejectsSensitiveCopies() {
        val history = io.github.minjj.keyboard.clipboard.ClipboardHistory.get(activity)
        val clipboard = activity.getSystemService(android.content.ClipboardManager::class.java)
        var previous: android.content.ClipData? = null
        main { previous = clipboard.primaryClip }
        runBlocking { history.refresh() }
        val originalIds = history.entries.value.map { it.id }.toSet()
        try {
            main { clipboard.setPrimaryClip(android.content.ClipData.newPlainText("fixture", "MINJJ-CLIP-한글😀")) }
            await("First clipboard captured") { history.entries.value.any { it.text == "MINJJ-CLIP-한글😀" } }
            main { clipboard.setPrimaryClip(android.content.ClipData.newPlainText("fixture", "MINJJ-CLIP-second")) }
            await("Second clipboard captured") { history.entries.value.any { it.text == "MINJJ-CLIP-second" } }
            val first = history.entries.value.first { it.text == "MINJJ-CLIP-한글😀" }
            val second = history.entries.value.first { it.text == "MINJJ-CLIP-second" }
            native(value = "앞뒤"); main { activity.primary.setSelection(1) }; instrumentation.waitForIdleSync()
            tap("clipboard")
            tapClipboard(first.id, "pin")
            await("Clipboard pinned") { history.entries.value.first { it.id == first.id }.pinned }
            tapClipboard(second.id, "delete")
            await("Clipboard deleted") { history.entries.value.none { it.id == second.id } }
            device.wait(Until.findObject(By.desc("문자 자판으로 돌아가기")), 3000).click()
            tap("clipboard"); SystemClock.sleep(250)
            assertFalse("Reopening must not resurrect the deleted system clipboard item", history.entries.value.any { it.text == "MINJJ-CLIP-second" })
            if (history.entries.value.all { it.text?.startsWith("MINJJ-CLIP-") == true }) screenshot("clipboard-text-history")
            tapClipboard(first.id, "paste"); expect("앞MINJJ-CLIP-한글😀뒤")
            val reloaded = io.github.minjj.keyboard.clipboard.ClipboardHistory(activity)
            runBlocking { reloaded.refresh() }
            assertTrue(reloaded.entries.value.any { it.id == first.id && it.pinned })
            main { clipboard.setPrimaryClip(android.content.ClipData.newPlainText("fixture", "MINJJ-CLIP-한글😀")) }
            SystemClock.sleep(200)
            assertEquals(1, history.entries.value.count { it.text == "MINJJ-CLIP-한글😀" })
            main { clipboard.setPrimaryClip(android.content.ClipData.newPlainText("fixture", "MINJJ-SECRET-sensitive").apply {
                description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
            }) }
            SystemClock.sleep(200); assertFalse(history.entries.value.any { it.text == "MINJJ-SECRET-sensitive" })
            native(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            main { clipboard.setPrimaryClip(android.content.ClipData.newPlainText("fixture", "MINJJ-SECRET-password")) }
            SystemClock.sleep(200); assertFalse(history.entries.value.any { it.text == "MINJJ-SECRET-password" })
            tap("clipboard"); assertFalse(device.hasObject(By.res(packageName, "clipboard_panel")))
        } finally {
            runBlocking { history.entries.value.filterNot { it.id in originalIds }.forEach { history.delete(it.id) } }
            main { previous?.let(clipboard::setPrimaryClip) ?: clipboard.clearPrimaryClip() }
        }
    }

    @Test fun clipboardLayoutAndActionsInBothOrientations() {
        val history = io.github.minjj.keyboard.clipboard.ClipboardHistory.get(activity)
        val clipboard = activity.getSystemService(android.content.ClipboardManager::class.java)
        val repository = SettingsRepository(activity)
        var previous: android.content.ClipData? = null
        main { previous = clipboard.primaryClip }
        runBlocking { history.refresh() }
        val originalIds = history.entries.value.map { it.id }.toSet()
        try {
            for (text in listOf("MINJJ-DESIGN-자주 쓰는 문장", "MINJJ-DESIGN-여러 줄의 긴 문장도 카드 안에서 자연스럽게 표시되는지 확인합니다.", "MINJJ-DESIGN-최근 복사한 항목")) {
                main { clipboard.setPrimaryClip(android.content.ClipData.newPlainText("fixture", text)) }
                await("Design fixture captured") { history.entries.value.any { it.text == text } }
            }
            val pinned = history.entries.value.first { it.text == "MINJJ-DESIGN-자주 쓰는 문장" }
            val recent = history.entries.value.first { it.text == "MINJJ-DESIGN-최근 복사한 항목" }
            runBlocking { history.pin(pinned.id) }
            // Clipboard must fill the IME even when keys have a narrower width preference.
            runBlocking { repository.restoreSettings(KeyboardSettings(portraitWidthPercent = 65, landscapeWidthPercent = 65)) }
            for (landscape in listOf(false, true)) {
                if (landscape) {
                    device.setOrientationLeft()
                    activity = focusedActivity(Configuration.ORIENTATION_LANDSCAPE)
                }
                for (contrast in listOf(false, true)) {
                    runBlocking { repository.setOption("highContrast", contrast) }
                    native()
                    val toolbarBounds = Rect()
                    var surfaceColor = 0
                    main {
                        val keyboard = WindowInspector.getGlobalWindowViews().firstNotNullOf { root ->
                            screenViews(root).filterIsInstance<KeyboardView>().firstOrNull { it.isShown }
                        }
                        assertTrue(keyboard.width < (keyboard.parent as View).width)
                        keyboard.getChildAt(0).getGlobalVisibleRect(toolbarBounds)
                        surfaceColor = (keyboard.background as android.graphics.drawable.ColorDrawable).color
                    }
                    tap("clipboard")
                    var panel: View? = null
                    await("Clipboard layout settled") {
                        main {
                            panel = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull { it.findViewById<View>(R.id.clipboard_panel) }
                        }
                        panel?.let { it.isLaidOut && !it.isLayoutRequested } == true
                    }
                    val clipboardPanel = checkNotNull(panel)
                    main {
                        val views = screenViews(clipboardPanel).toList()
                        val title = views.filterIsInstance<TextView>().single { it.isAccessibilityHeading && it.text == activity.getString(R.string.key_clipboard) }
                        assertEquals("Clipboard title fits", 0, title.layout.getEllipsisCount(0))
                        assertTrue("History setting stays in Settings", views.none { it is android.widget.Switch })
                        assertTrue("No storage footer", views.filterIsInstance<TextView>().none { it.text.contains("최대 50개") })
                        val back = views.single { it.contentDescription == activity.getString(R.string.clipboard_back) }
                        assertTrue("Back touch target", back.width >= dp(48) && back.height >= dp(44))
                        assertEquals("Full IME width", (clipboardPanel.parent as View).width, clipboardPanel.width)
                        assertEquals("Keyboard background preserved", surfaceColor,
                            (clipboardPanel.background as android.graphics.drawable.ColorDrawable).color)
                        val panelToolbar = (clipboardPanel as ViewGroup).getChildAt(0)
                        val panelToolbarBounds = Rect().also { panelToolbar.getGlobalVisibleRect(it) }
                        assertEquals("Toolbar position preserved", toolbarBounds.top, panelToolbarBounds.top)
                        assertEquals("Toolbar height preserved", toolbarBounds.height(), panelToolbarBounds.height())
                        val bars = clipboardPanel.rootWindowInsets.getInsets(android.view.WindowInsets.Type.systemBars())
                        assertTrue("Navigation safe area reserved", clipboardPanel.paddingBottom >= bars.bottom + dp(4))
                        val scroll = views.filterIsInstance<android.widget.ScrollView>().single()
                        assertTrue("Content stops above safe area", scroll.bottom <= clipboardPanel.height - clipboardPanel.paddingBottom)
                        val card = clipboardPanel.findViewWithTag<View>("clip:${recent.id}:paste")
                        assertTrue("Compact clipboard cards", card.width <= clipboardPanel.width / if (landscape) 8 else 4)
                        if (contrast) assertEquals(Configuration.UI_MODE_NIGHT_YES,
                            clipboardPanel.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
                    }
                    val suffix = (if (landscape) "landscape" else "portrait") + (if (contrast) "-dark" else "-system")
                    screenshotClipboardFixtures("clipboard-design-$suffix", originalIds)
                    tapClipboardToolbar(R.id.clipboard_pin_selected)
                    tapClipboard(pinned.id, "paste")
                    tapClipboard(recent.id, "paste")
                    expect("")
                    screenshotClipboardFixtures("clipboard-selection-$suffix", originalIds)
                    tapClipboardToolbar(R.id.clipboard_pin_selected)
                    await("Selected entries pinned together") { history.entries.value.filter { it.id in setOf(pinned.id, recent.id) }.all { it.pinned } }
                    tapClipboardToolbar(R.id.clipboard_pin_selected)
                    tapClipboard(pinned.id, "paste")
                    tapClipboard(recent.id, "paste")
                    tapClipboardToolbar(R.id.clipboard_pin_selected)
                    await("Selected entries unpinned together") { history.entries.value.filter { it.id in setOf(pinned.id, recent.id) }.none { it.pinned } }
                    // A normal tap still pastes after leaving selection mode.
                    tapClipboard(recent.id, "paste")
                    expect(checkNotNull(recent.text))
                    assertFalse(device.hasObject(By.res(packageName, "clipboard_panel")))
                }
            }
            native()
            tap("clipboard")
            tapClipboardToolbar(R.id.clipboard_delete_selected)
            tapClipboard(pinned.id, "paste")
            tapClipboard(recent.id, "paste")
            tapClipboardToolbar(R.id.clipboard_delete_selected)
            await("Only selected entries deleted") {
                history.entries.value.none { it.id in setOf(pinned.id, recent.id) } &&
                    history.entries.value.any { it.id !in originalIds }
            }
            assertTrue("Original history preserved", history.entries.value.map { it.id }.containsAll(originalIds))
        } finally {
            device.pressBack()
            runBlocking { history.entries.value.filterNot { it.id in originalIds }.forEach { history.delete(it.id) } }
            main { previous?.let(clipboard::setPrimaryClip) ?: clipboard.clearPrimaryClip() }
        }
    }

    @Test fun clipboardImageCommitsRealBytesOnlyToSupportingEditors() {
        val history = io.github.minjj.keyboard.clipboard.ClipboardHistory.get(activity)
        val clipboard = activity.getSystemService(android.content.ClipboardManager::class.java)
        var previous: android.content.ClipData? = null
        main { previous = clipboard.primaryClip; activity.acceptImages = true }
        native(); runBlocking { history.refresh() }
        val originalIds = history.entries.value.map { it.id }.toSet()
        try {
            main { clipboard.setPrimaryClip(android.content.ClipData.newUri(activity.contentResolver, "fixture",
                android.net.Uri.parse("content://$packageName.fixture-image/sample"))) }
            await("Clipboard image imported") { history.entries.value.any { it.image && it.id !in originalIds } }
            val image = history.entries.value.first { it.image && it.id !in originalIds }
            tap("clipboard")
            tapClipboard(image.id, "paste")
            await("Actual committed PNG bytes decoded") { var decoded = false; main { decoded = activity.lastImageDecoded && activity.lastImageMime == "image/png" }; decoded }
            main { activity.acceptImages = false }; native()
            tap("clipboard")
            tapClipboard(image.id, "paste")
            assertTrue(device.hasObject(By.res(packageName, "clipboard_panel")))
            var decoded = true; main { decoded = activity.lastImageDecoded }; assertFalse(decoded); expect("")
            device.wait(Until.findObject(By.desc("문자 자판으로 돌아가기")), 3000).click()
        } finally {
            runBlocking { history.entries.value.filterNot { it.id in originalIds }.forEach { history.delete(it.id) } }
            main { previous?.let(clipboard::setPrimaryClip) ?: clipboard.clearPrimaryClip() }
        }
    }

    private fun tapClipboard(id: String, action: String) {
        if (action != "paste") {
            val toolbarId = if (action == "pin") R.id.clipboard_pin_selected else R.id.clipboard_delete_selected
            tapClipboardToolbar(toolbarId)
            tapClipboard(id, "paste")
            tapClipboardToolbar(toolbarId)
            return
        }
        var point: Point? = null
        await("Clipboard action ready") {
            main {
                val view = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull { it.findViewWithTag<View>("clip:$id:$action") }
                if (view != null && view.isShown && view.width > 0 && !view.isLayoutRequested) {
                    val visible = Rect()
                    if (view.getLocalVisibleRect(visible) && visible.height() >= dp(16)) {
                        val location = IntArray(2); view.getLocationOnScreen(location)
                        point = Point(location[0] + visible.centerX(), location[1] + visible.centerY())
                    } else view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true)
                }
            }
            point != null
        }
        device.click(point!!.x, point!!.y)
    }

    private fun tapClipboardToolbar(id: Int) {
        var point: Point? = null
        await("Clipboard toolbar ready") {
            main {
                val button = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull { it.findViewById<View>(id) }
                if (button != null && button.isShown && button.isEnabled && !button.isLayoutRequested) {
                    val visible = Rect()
                    if (button.getLocalVisibleRect(visible)) {
                        val location = IntArray(2).also(button::getLocationOnScreen)
                        point = Point(location[0] + visible.centerX(), location[1] + visible.centerY())
                    }
                }
            }
            point != null
        }
        device.click(point!!.x, point!!.y)
    }

    private fun screenshotClipboardFixtures(name: String, originalIds: Set<String>) {
        var panel: io.github.minjj.keyboard.ui.ClipboardPanel? = null
        val history = io.github.minjj.keyboard.clipboard.ClipboardHistory.get(activity)
        main {
            panel = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull { it.findViewById(R.id.clipboard_panel) }
            panel?.render(history.entries.value.filterNot { it.id in originalIds })
        }
        try {
            instrumentation.waitForIdleSync()
            screenshot(name)
        } finally {
            main { panel?.render(history.entries.value) }
            instrumentation.waitForIdleSync()
        }
    }

    private fun slideDelete(steps: Int) {
        val center = point("delete"); val time = SystemClock.uptimeMillis()
        val end = Point(center.x + dp(18) * steps, center.y)
        touch(time, MotionEvent.ACTION_DOWN, center)
        try { moveAndSettle(time, center, end) }
        finally { touch(time, MotionEvent.ACTION_UP, end) }
    }

    /** Feed a physical swipe cadence, then hold at its endpoint for two resampling frames. */
    private fun moveAndSettle(time: Long, from: Point, to: Point) {
        for (step in 1..5) {
            SystemClock.sleep(16)
            touch(time, MotionEvent.ACTION_MOVE, Point(from.x + (to.x - from.x) * step / 5, from.y + (to.y - from.y) * step / 5))
        }
        repeat(2) { SystemClock.sleep(16); touch(time, MotionEvent.ACTION_MOVE, to) }
    }

    @Test fun numberRowHeightPreviewsAndPersistsInBothOrientations() {
        val repository = SettingsRepository(activity)
        val layout = openSizePreview()
        fun previewHeight(id: Int) = previewValue { it.findViewById<View>(id).height }
        val letterHeight = previewHeight(R.id.key_q)
        assertEquals(letterHeight, previewHeight(R.id.key_1))

        val bounds = screenBounds(layout.window.decorView) { it.contentDescription == layout.getString(R.string.number_row_height) }
        device.click(bounds.left + dp(16), bounds.centerY())
        await("Only the number row shrinks in the preview") {
            previewHeight(R.id.key_1) < letterHeight && previewHeight(R.id.key_q) == letterHeight
        }
        assertEquals(100, runBlocking { repository.settings.first().numberRowHeightPercent })
        device.setOrientationLeft()
        waitForLayoutPreview(focusedActivity<LayoutSettingsActivity>(Configuration.ORIENTATION_LANDSCAPE, R.string.layout_title))
        await("Number row height draft survives rotation") { previewHeight(R.id.key_1) < previewHeight(R.id.key_q) }
        device.wait(Until.findObject(By.res(packageName, "resize_done")), 3000).click()
        await("Number row height saved") { runBlocking { repository.settings.first().numberRowHeightPercent < 100 } }
        closeSizeSettings()
        native()
        assertTrue(key("1").visibleBounds.height() < key("q").visibleBounds.height())
        device.setOrientationNatural()
        await("Saved number row height applies in portrait") {
            key("1").visibleBounds.height() < key("q").visibleBounds.height()
        }
        tap("1")
        expect("1")
    }

    @Test fun keyGapSlidersPreviewCancelResetAndPersistAcrossRotation() {
        val repository = SettingsRepository(activity)
        var layout = openSizePreview()
        fun gap(horizontal: Boolean): Int = previewValue { keyboard ->
            fun bounds(id: Int) = keyboard.findViewById<View>(id).let { key ->
                Rect(0, 0, key.width, key.height).also { (keyboard as ViewGroup).offsetDescendantRectToMyCoords(key, it) }
            }
            val q = bounds(R.id.key_q)
            if (horizontal) bounds(R.id.key_w).left - q.right else bounds(R.id.key_a).top - q.bottom
        }
        fun change(title: Int, end: Boolean) {
            val bounds = screenBounds(layout.window.decorView) { it.contentDescription == layout.getString(title) }
            assertTrue(device.click(if (end) bounds.right - dp(16) else bounds.left + dp(16), bounds.centerY()))
        }
        change(R.string.horizontal_key_gap, true)
        change(R.string.vertical_key_gap, false)
        await("Independent gaps preview") { gap(true) == dp(12) && gap(false) == 0 }
        assertEquals(KeyboardSettings(), runBlocking { repository.settings.first() })
        screenshot("key-gaps-portrait")
        device.wait(Until.findObject(By.res(packageName, "resize_cancel")), 3000).click()
        await("Cancel restores saved gaps") { gap(true) == dp(6) && gap(false) == dp(6) }
        change(R.string.horizontal_key_gap, true)
        change(R.string.vertical_key_gap, true)
        device.wait(Until.findObject(By.res(packageName, "resize_reset")), 3000).click()
        await("Reset restores default gaps") { gap(true) == dp(6) && gap(false) == dp(6) }
        change(R.string.horizontal_key_gap, false)
        change(R.string.vertical_key_gap, true)
        device.setOrientationLeft()
        layout = focusedActivity(Configuration.ORIENTATION_LANDSCAPE, R.string.layout_title)
        waitForLayoutPreview(layout)
        await("Gap draft survives rotation") { gap(true) == 0 && gap(false) == dp(12) }
        device.waitForIdle()
        screenBounds(layout.window.decorView) { it.contentDescription == layout.getString(R.string.vertical_key_gap) }
        SystemClock.sleep(500) // Capture after the system rotation and scroll animations settle.
        screenshot("key-gaps-landscape")
        device.wait(Until.findObject(By.res(packageName, "resize_done")), 3000).click()
        await("Gaps saved") { runBlocking { repository.settings.first().let { it.horizontalKeyGap == 0 && it.verticalKeyGap == 12 } } }
        closeSizeSettings()
        native()
        assertEquals(0, key("w").visibleBounds.left - key("q").visibleBounds.right)
        assertEquals(dp(12), key("a").visibleBounds.top - key("q").visibleBounds.bottom)
        device.setOrientationNatural()
        native()
        assertEquals(0, key("w").visibleBounds.left - key("q").visibleBounds.right)
        assertEquals(dp(12), key("a").visibleBounds.top - key("q").visibleBounds.bottom)
        tap("1")
        expect("1")
    }

    @Test fun unsavedSizeRequiresTwoBackPresses() {
        openSizePreview()
        val portrait = Configuration.ORIENTATION_PORTRAIT
        val clean = focusedActivity<LayoutSettingsActivity>(portrait)
        main {
            val sliders = screenViews(clean.window.decorView).filterIsInstance<android.widget.SeekBar>().toList()
            assertEquals(listOf(R.string.font_title, R.string.number_row_height, R.string.horizontal_key_gap,
                R.string.vertical_key_gap, R.string.portrait_split_gap).map { clean.getString(it) },
                sliders.map { it.contentDescription.toString() })
        }
        device.pressBack()
        focusedActivity<SettingsActivity>(portrait)
        val dirty = openLayoutMenu()
        waitForLayoutPreview(dirty)
        val repository = SettingsRepository(activity)
        val saved = runBlocking { repository.settings.first() }
        val original = previewBounds()
        dragResize("resize_height", 0, dp(32))
        val resized = previewBounds()
        assertTrue(resized != original)
        val contrast = screenPoint(dirty.window.decorView) {
            it is android.widget.Switch && it.text == dirty.getString(R.string.high_contrast)
        }
        assertTrue(device.click(contrast.x, contrast.y))
        val savedAppearance = saved.copy(highContrast = true)
        await("Appearance saves independently of the size draft") { runBlocking { repository.settings.first() } == savedAppearance }
        assertEquals(resized, previewBounds())
        val backLabel = dirty.getString(R.string.layout_back)
        val back = screenPoint(dirty.window.decorView) { it.contentDescription == backLabel }
        assertTrue(device.click(back.x, back.y))
        SystemClock.sleep(250)
        assertSame(dirty, focusedActivity<LayoutSettingsActivity>(portrait))
        assertEquals(savedAppearance, runBlocking { repository.settings.first() })
        screenshot("size-unsaved-back")
        device.pressBack()
        focusedActivity<SettingsActivity>(portrait)
        assertEquals(savedAppearance, runBlocking { repository.settings.first() })
    }

    @Test fun vibrationStrengthSliderPersistsInBothOrientations() {
        device.pressBack()
        val repository = SettingsRepository(activity)
        assertEquals(50, runBlocking { repository.settings.first().vibrationStrength })
        runBlocking {
            repository.setVibrationStrength(-1)
            assertEquals(0, repository.settings.first().vibrationStrength)
            repository.setVibrationStrength(101)
            assertEquals(100, repository.settings.first().vibrationStrength)
            repository.setVibrationStrength(50)
        }
        var saved = 50
        for ((orientation, name) in listOf(Configuration.ORIENTATION_PORTRAIT to "portrait",
            Configuration.ORIENTATION_LANDSCAPE to "landscape")) {
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                device.executeShellCommand("wm user-rotation lock 1")
                await("Device display rotates to landscape") { device.displayWidth > device.displayHeight }
            }
            instrumentation.startActivitySync(Intent(instrumentation.targetContext, OptionsSettingsActivity::class.java)
                .putExtra(OptionsSettingsActivity.PAGE, R.string.feedback_title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            val submenu = focusedActivity<OptionsSettingsActivity>(orientation)
            val seek = submenu.findViewById<android.widget.SeekBar>(R.id.vibration_strength)
            await("Vibration strength loaded in $name") {
                var ready = false
                main { ready = seek.isEnabled && seek.progress == saved }
                ready
            }
            val bounds = screenBounds(submenu.window.decorView) { it === seek }
            var left = 0
            var right = 0
            main {
                assertEquals(0, seek.min)
                assertEquals(100, seek.max)
                assertTrue(seek.height >= dp(48))
                left = bounds.left + seek.paddingLeft
                right = bounds.right - seek.paddingRight
            }
            val target = if (orientation == Configuration.ORIENTATION_PORTRAIT) 75 else 25
            val start = left + (right - left) * saved / 100
            val end = left + (right - left) * target / 100
            assertTrue(device.swipe(start, bounds.centerY(), end, bounds.centerY(), 16))
            await("Slider strength saved in $name") {
                runBlocking { repository.settings.first().vibrationStrength in (target - 1)..(target + 1) }
            }
            saved = runBlocking { repository.settings.first().vibrationStrength }
            main {
                assertEquals(saved, seek.progress)
                assertTrue(screenViews(submenu.window.decorView).filterIsInstance<TextView>()
                    .any { it.text == submenu.getString(R.string.percent_value, saved) })
            }
            fun toggleVibration() {
                val point = screenPoint(submenu.window.decorView) {
                    it is android.widget.Switch && it.text == submenu.getString(R.string.vibration)
                }
                assertTrue(device.click(point.x, point.y))
            }
            toggleVibration()
            await("Vibration off disables slider in $name") {
                var disabled = false
                main { disabled = !seek.isEnabled && seek.progress == saved }
                disabled && runBlocking { !repository.settings.first().vibration }
            }
            toggleVibration()
            await("Vibration on retains strength in $name") {
                var enabled = false
                main { enabled = seek.isEnabled && seek.progress == saved }
                enabled && runBlocking { repository.settings.first().vibration }
            }
            screenshot("vibration-strength-$name")
            device.pressBack()
        }
        runBlocking {
            repository.restoreSettings(repository.settings.first().copy(vibrationStrength = 42))
            assertEquals(42, repository.settings.first().vibrationStrength)
        }
    }

    @Test fun optionSubmenusSaveWithoutClosing() {
        device.pressBack()
        instrumentation.startActivitySync(Intent(instrumentation.targetContext, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val portrait = Configuration.ORIENTATION_PORTRAIT
        val repository = SettingsRepository(activity)
        val repeat = R.string.repeat_title
        val feedback = R.string.feedback_title
        for (page in listOf(repeat, feedback)) {
            val home = focusedActivity<SettingsActivity>(portrait)
            val title = home.getString(page)
            val row = screenPoint(home.window.decorView) { it.contentDescription?.startsWith("$title,") == true }
            assertTrue(device.click(row.x, row.y))
            val submenu = focusedActivity<OptionsSettingsActivity>(portrait)
            val label = submenu.getString(if (page == repeat) R.string.speed_fast else R.string.sound)
            val option = screenPoint(submenu.window.decorView) { it is TextView && it.text == label }
            assertTrue(device.click(option.x, option.y))
            await("Submenu option persisted") {
                runBlocking { repository.settings.first().let { if (page == repeat) it.repeatMillis == 30 else it.sound } }
            }
            assertSame(submenu, focusedActivity<OptionsSettingsActivity>(portrait))
            screenshot(if (page == repeat) "options-repeat" else "options-feedback")
            device.pressBack()
            focusedActivity<SettingsActivity>(portrait)
        }
    }

    @Test fun tabletGeometryAndResizeHandlesPersistPerOrientation() {
        val repository = SettingsRepository(activity)
        val portrait = keyboardGeometry("portrait-default"); screenshot("portrait-default-size")
        assertTrue("Portrait occupancy ${portrait[2]}", portrait[2] <= 0.47)
        assertFalse(device.hasObject(By.res(packageName, "key_resize")))
        openSizePreview()
        dragResize("resize_height", 0, dp(48))
        screenshot("resize-height-preview")
        dragResize("resize_width", -dp(48), 0)
        val portraitDraft = previewBounds()
        device.setOrientationLeft()
        waitForLayoutPreview(focusedActivity<LayoutSettingsActivity>(Configuration.ORIENTATION_LANDSCAPE, R.string.layout_title))
        device.setOrientationNatural()
        var layout = focusedActivity<LayoutSettingsActivity>(Configuration.ORIENTATION_PORTRAIT, R.string.layout_title)
        waitForLayoutPreview(layout)
        await("Unsaved portrait size survives a rotation round trip") { previewBounds() == portraitDraft }
        screenshot("resize-handles-preview")
        fun toggleSplitPreview() {
            val label = layout.getString(R.string.split_preview)
            val point = screenPoint(layout.window.decorView) { it is android.widget.Switch && it.text == label }
            assertTrue(device.click(point.x, point.y))
        }
        toggleSplitPreview()
        fun gapWidth() = previewValue { keyboard ->
            val row = keyboard.findViewById<View>(R.id.key_q).parent.parent as android.view.ViewGroup
            row.getChildAt(1).width
        }
        fun increaseGap(title: String) {
            val originalGap = gapWidth()
            val gap = screenBounds(layout.window.decorView) { it.contentDescription == title }
            device.click(gap.right - dp(8), gap.centerY())
            await("Split gap increases beyond the previous maximum") { gapWidth() > maxOf(originalGap, dp(160)) }
        }
        increaseGap("세로 분할 자판 간격")
        assertEquals(56, runBlocking { repository.settings.first().portraitSplitGap })
        screenshot("settings-split-gap-preview")
        device.wait(Until.findObject(By.res(packageName, "resize_done")), 3000).click()
        await("Portrait resize persisted") { runBlocking { repository.settings.first().let { it.portraitHeightPercent < 100 && it.portraitWidthPercent < 100 } } }
        assertTrue(runBlocking { repository.settings.first().let {
            it.portraitSplitGap > 160 && it.landscapeSplitGap == 56 && !it.splitFor(true, false)
        } })
        closeSizeSettings()
        native()
        val smaller = keyboardGeometry("portrait-resized"); screenshot("portrait-resized")
        assertTrue(smaller[0] < portrait[0]); assertTrue(smaller[1] < portrait[1])
        type("rksk"); expect("가나")
        device.setOrientationLeft(); point("delete")
        val landscape = keyboardGeometry("landscape-default"); screenshot("landscape-default-size")
        assertTrue("Landscape occupancy ${landscape[2]}", landscape[2] <= 0.46)
        assertEquals(100, runBlocking { repository.settings.first().landscapeHeightPercent })
        openSizePreview(); dragResize("resize_height", 0, dp(32))
        device.wait(Until.findObject(By.res(packageName, "resize_cancel")), 3000).click()
        closeSizeSettings()
        native()
        assertEquals(100, runBlocking { repository.settings.first().landscapeHeightPercent })
        val cancelled = keyboardGeometry("landscape-cancelled")
        assertEquals(landscape[1], cancelled[1], 1.0)
        val portraitGap = runBlocking { repository.settings.first().portraitSplitGap }
        layout = openSizePreview()
        toggleSplitPreview()
        increaseGap("가로 분할 자판 간격")
        screenshot("settings-landscape-split-gap-preview")
        device.wait(Until.findObject(By.res(packageName, "resize_done")), 3000).click()
        await("Landscape split gap persists independently") { runBlocking { repository.settings.first().let {
            it.landscapeSplitGap > 160 && it.portraitSplitGap == portraitGap
        } } }
        closeSizeSettings()
        native()
        device.setOrientationNatural(); point("delete")
        openSizePreview()
        device.wait(Until.findObject(By.res(packageName, "resize_reset")), 3000).click()
        device.wait(Until.findObject(By.res(packageName, "resize_done")), 3000).click()
        await("Reset saved") { runBlocking { repository.settings.first().let { it.portraitHeightPercent == 100 && it.portraitWidthPercent == 100 } } }
        closeSizeSettings()
    }

    private fun openSizePreview(): LayoutSettingsActivity {
        tap("settings")
        val layout = openLayoutMenu()
        waitForLayoutPreview(layout)
        assertNotNull(device.wait(Until.findObject(By.res(packageName, "resize_height")), 3000))
        return layout
    }

    private fun openLayoutMenu(): LayoutSettingsActivity {
        val orientation = activity.resources.configuration.orientation
        val home = focusedActivity<SettingsActivity>(orientation)
        val title = home.getString(R.string.layout_title)
        val row = screenPoint(home.window.decorView) { it.contentDescription?.startsWith("$title,") == true }
        assertTrue(device.click(row.x, row.y))
        return focusedActivity<LayoutSettingsActivity>(orientation, R.string.layout_title)
    }

    private fun waitForLayoutPreview(layout: LayoutSettingsActivity) {
        await("Layout keyboard preview measured") {
            var ready = false
            main { ready = layout.findViewById<View>(R.id.resize_keyboard)?.isLaidOut == true }
            ready
        }
    }

    private fun closeSizeSettings() {
        val orientation = if (device.displayWidth > device.displayHeight) {
            Configuration.ORIENTATION_LANDSCAPE
        } else {
            Configuration.ORIENTATION_PORTRAIT
        }
        val layout = focusedActivity<LayoutSettingsActivity>(orientation, R.string.layout_title)
        await("Layout size actions ready after saving") {
            var ready = false
            main { ready = layout.findViewById<View>(R.id.resize_reset).isEnabled }
            ready
        }
        device.pressBack()
        focusedActivity<SettingsActivity>(orientation)
        device.pressBack()
    }

    private fun <T : Any> previewValue(read: (View) -> T): T {
        var result: T? = null
        main {
            val keyboard = WindowInspector.getGlobalWindowViews().filter { it.hasWindowFocus() }
                .firstNotNullOf { it.findViewById<View>(R.id.resize_keyboard) }
            result = read(keyboard)
        }
        return checkNotNull(result)
    }

    private fun previewBounds(checkOverlay: Boolean = false): Point = previewValue { keyboard ->
        val group = keyboard as android.view.ViewGroup
        val firstRow = group.getChildAt(1)
        val lastRow = group.getChildAt(group.childCount - 1)
        val size = Point(firstRow.width, lastRow.bottom - firstRow.top)
        if (checkOverlay) {
            val overlay = (keyboard.parent as View).findViewById<View>(R.id.resize_height).parent as View
            assertEquals("Overlay matches the key rows after layout", size, Point(overlay.width, overlay.height))
            assertEquals("Overlay starts at the first key row", keyboard.top + firstRow.top, overlay.top)
            assertEquals("Overlay ends at the last key row", keyboard.top + lastRow.bottom, overlay.bottom)
            assertEquals("Overlay is centered on the key rows", keyboard.left + firstRow.left, overlay.left)
        }
        size
    }

    private fun dragResize(id: String, dx: Int, dy: Int) {
        val bounds = device.wait(Until.findObject(By.res(packageName, id)), 3000).visibleBounds
        val center = Point(bounds.centerX(), bounds.centerY()); val time = SystemClock.uptimeMillis()
        val original = previewBounds()
        touch(time, MotionEvent.ACTION_DOWN, center)
        val end = Point(center.x + dx, center.y + dy)
        try {
            moveAndSettle(time, center, end)
            assertEquals("Preview keyboard keeps its bounds during drag", original, previewBounds())
        }
        finally { touch(time, MotionEvent.ACTION_UP, end) }
        await("Preview keyboard resizes on release") { previewBounds() != original }
        previewBounds(checkOverlay = true)
    }
    private fun keyboardGeometry(label: String): List<Double> {
        point("delete")
        var result = emptyList<Double>(); var details = ""
        main {
            fun keyboard(view: View): io.github.minjj.keyboard.ui.KeyboardView? {
                if (view is io.github.minjj.keyboard.ui.KeyboardView) return view
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) keyboard(view.getChildAt(i))?.let { return it }
                return null
            }
            val view = WindowInspector.getGlobalWindowViews().firstNotNullOf { keyboard(it) }
            val letters = "qwertyuiopasdfghjklzxcvbnm".map { char ->
                view.findViewById<TextView>(activity.resources.getIdentifier("key_$char", "id", packageName))
            }
            assertEquals("Equal letter widths", 1, letters.map { it.width }.distinct().size)
            assertEquals("Equal letter heights", 1, letters.map { it.height }.distinct().size)
            assertEquals("Equal letter font sizes", 1, letters.map { it.textSize }.distinct().size)
            result = listOf(view.getChildAt(1).width.toDouble(), view.height.toDouble(), view.height.toDouble() / device.displayHeight)
            details = "$label: display=${device.displayWidth}x${device.displayHeight}, keyboard=${view.width}x${view.height}, occupancy=${result[2]}, letter=${letters.first().width}x${letters.first().height}, fontPx=${letters.first().textSize}\n"
        }
        File(output, "geometry.txt").appendText(details)
        return result
    }

    private fun holdKey(name: String, millis: Long) {
        val center = point(name); val time = SystemClock.uptimeMillis()
        touch(time, MotionEvent.ACTION_DOWN, center)
        SystemClock.sleep(millis)
        touch(time, MotionEvent.ACTION_UP, center)
    }
    private fun doubleSpaceTap() {
        val center = point("space")
        repeat(2) {
            val time = SystemClock.uptimeMillis()
            touch(time, MotionEvent.ACTION_DOWN, center); touch(time, MotionEvent.ACTION_UP, center)
            SystemClock.sleep(50)
        }
    }

    private fun js(script: String): String {
        val latch = CountDownLatch(1)
        var value = ""
        main { activity.web.evaluateJavascript(script) { result -> value = JSONTokener(result).nextValue().toString(); latch.countDown() } }
        assertTrue("WebView callback", latch.await(3, TimeUnit.SECONDS))
        return value
    }

    private fun cancelInjectedTouches() {
        // Android's injection verifier can retain a failed run's pointers across instrumentation sessions.
        // CANCEL must contain the same pointer IDs. These tests use at most three consecutive IDs.
        for (count in 3 downTo 1) {
            val properties = (0 until count).map { pointerId -> MotionEvent.PointerProperties().apply {
                id = pointerId; toolType = MotionEvent.TOOL_TYPE_FINGER
            } }.toTypedArray()
            val coordinates = properties.map { MotionEvent.PointerCoords() }.toTypedArray()
            val now = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, count,
                properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            // A count that does not match an active gesture may be rejected without changing its state.
            try { instrumentation.uiAutomation.injectInputEvent(event, true) } finally { event.recycle() }
        }
    }

    private fun touch(downTime: Long, action: Int, point: Point) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, point.x.toFloat(), point.y.toFloat(), 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        val started = SystemClock.uptimeMillis()
        try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally {
            val elapsed = SystemClock.uptimeMillis() - started
            if (elapsed > 100) File(output, "injection-timing.txt").appendText("action=$action elapsedMs=$elapsed\n")
            event.recycle()
        }
    }

    private fun pointers(downTime: Long, action: Int, points: List<Point>, ids: List<Int> = points.indices.toList()) {
        val properties = points.indices.map { index -> MotionEvent.PointerProperties().apply {
            id = ids[index]; toolType = MotionEvent.TOOL_TYPE_FINGER
        } }.toTypedArray()
        val coordinates = points.map { point -> MotionEvent.PointerCoords().apply {
            x = point.x.toFloat(); y = point.y.toFloat(); pressure = 1f; size = 1f
        } }.toTypedArray()
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, points.size,
            properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
    }
}
