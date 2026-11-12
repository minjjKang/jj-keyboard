package io.github.minjj.keyboard.correction

import android.content.Context
import android.os.Looper
import android.util.AtomicFile
import android.view.View
import android.view.inputmethod.EditorInfo
import io.github.minjj.keyboard.ime.*
import io.github.minjj.keyboard.settings.KeyboardSettings
import io.github.minjj.keyboard.touch.*
import io.github.minjj.keyboard.ui.KeyboardView
import io.github.minjj.keyboard.ui.TouchLearningMapView
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.RealObject
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w600dp-h960dp-mdpi", shadows = [JvmAtomicFile::class])
class PersonalizationIntegrationTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private fun await(condition: () -> Boolean) {
        repeat(500) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(5)
        }
        error("Fixture state did not settle")
    }
    private fun repository(): SpatialStatsRepository = SpatialStatsRepository(AtomicFile(File.createTempFile("learning-test", ".stats", context.cacheDir))).also { r -> await { r.ready } }
    private fun keyboard(settings: KeyboardSettings = KeyboardSettings()): KeyboardView {
        return KeyboardView(context, KeyboardController({ null }, {}, {}, {}, {})).apply {
            bind(EditorProfile(), settings)
            measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.AT_MOST))
            layout(0, 0, measuredWidth, measuredHeight)
        }
    }

    @Test fun `session registration requires package field and random marker`() {
        val s = PersonalizationSessionBridge.Session(42, true, true) {}
        val info = EditorInfo().apply { packageName = context.packageName; fieldId = 42; privateImeOptions = s.marker }
        assertNull(PersonalizationSessionBridge.resolve(info, context.packageName))
        PersonalizationSessionBridge.register(s)
        assertSame(s, PersonalizationSessionBridge.resolve(info, context.packageName))
        info.fieldId = 43; assertNull(PersonalizationSessionBridge.resolve(info, context.packageName))
        info.fieldId = 42; info.packageName = "other.app"; assertNull(PersonalizationSessionBridge.resolve(info, context.packageName))
        PersonalizationSessionBridge.unregister(s)
        info.packageName = context.packageName; assertNull(PersonalizationSessionBridge.resolve(info, context.packageName))
    }

    @Test fun `guided completion stores once and reset invalidates in flight word`() {
        val repo = repository()
        val l = keyboard().learningLayout!!
        val id = repo.layout(l)
        val s = PersonalizationSessionBridge.Session(42, true, true) {}
        s.connected = true; s.layout = l; s.profile = id
        fun begin() {
            s.begin(PracticeWord("fixture", "qw"), repo)
            for (c in "qw") {
                val k = l.keys.first { it.key == KeyIds.character(c) }
                s.tap(c, c, (k.left + k.right) / 2, (k.top + k.bottom) / 2)
            }
        }
        begin(); assertEquals(2, s.complete(repo)); assertEquals(0, s.complete(repo))
        assertEquals(1, repo.model.summary(id, spatialDay(), 0, 0x3ff).guided)
        begin()
        val job = CoroutineScope(Dispatchers.Main).async { repo.clearProfile(id, true) }
        await { job.isCompleted }
        assertTrue(kotlinx.coroutines.runBlocking { job.await() })
        assertEquals(0, s.complete(repo))
        assertEquals(0, repo.model.summary(id, spatialDay(), 0, 0x3ff).guided)
        assertNull(repo.model.summary(id, spatialDay(), 0, 0x3ff).reference)
    }

    @Test fun `view session and invalid edits never store and profile metadata survives reload`() {
        val file = AtomicFile(File.createTempFile("learning-reload", ".stats", context.cacheDir))
        val repo = SpatialStatsRepository(file); await { repo.ready }
        val l = keyboard().learningLayout!!; val id = repo.layout(l)
        val s = PersonalizationSessionBridge.Session(42, true, false) {}
        s.layout = l; s.profile = id; s.connected = true
        s.begin(PracticeWord("fixture", "q"), repo)
        val q = l.keys.first { it.key == 0 }
        s.tap('q', 'q', (q.left + q.right) / 2, (q.top + q.bottom) / 2)
        assertEquals(0, s.complete(repo))
        assertEquals(0, repo.model.summary(id, spatialDay(), 0, 1).guided)
        val invalid = PersonalizationSessionBridge.Session(42, true, true) {}
        invalid.layout = l; invalid.profile = id; invalid.connected = true
        invalid.begin(PracticeWord("fixture", "q"), repo)
        invalid.tap('q', 'q', (q.left + q.right) / 2, (q.top + q.bottom) / 2)
        invalid.reject("fixture-edit")
        assertEquals(0, invalid.complete(repo))
        repo.record(id, repo.generation, listOf(TouchSample(0, 100, 0, true)))
        await { !repo.state.value.saving }
        assertFalse(repo.state.value.toString(), repo.state.value.failed)
        assertTrue("Persisted fixture length=${file.baseFile.length()}; new=${File(file.baseFile.path + ".new").length()}", file.baseFile.length() > 0)
        val reload = SpatialStatsRepository(file); await { reload.ready }
        assertTrue(file.baseFile.readText(), reload.model.layouts.isNotEmpty())
        assertEquals(l, reload.model.layouts.first().layout)
        assertEquals(1, reload.model.summary(id, spatialDay(), 0, 1).guided)
    }

    @Test fun `language only changes preserve the sentence and save touches to their original profiles`() {
        val repo = repository()
        val keyboard = keyboard()
        val ko = keyboard.learningLayout!!
        val en = ko.copy(family = ko.family xor 32)
        val koId = repo.layout(ko); val enId = repo.layout(en)
        val s = PersonalizationSessionBridge.Session(42, true, true) {}
        s.connected = true; s.layout = ko; s.profile = koId
        s.begin(PracticeWord("가rk", PracticeSentences.keys("가rk", true), sentence = true), repo)
        fun input(c: Char, before: String, after: String) {
            val key = ko.keys.first { it.key == KeyIds.character(c) }
            val index = s.taps.size
            s.tap(c, c, (key.left + key.right) / 2, (key.top + key.bottom) / 2)
            s.mutationFinished(PersonalizationSessionBridge.Session.Mutation(s.trial, index, "CHARACTER"), before, after)
        }
        input('r', "", "ㄱ"); input('k', "ㄱ", "가")
        s.inputKorean = false; s.geometry(en, enId, keyboard.learningGeometry!!)
        assertFalse(s.invalid)
        input('r', "가", "가r"); input('k', "가r", "가rk")
        assertEquals(4, s.complete(repo, "가rk"))
        for (id in listOf(koId, enId)) for (c in "rk")
            assertEquals(1, repo.model.summary(id, spatialDay(), KeyIds.character(c)!!, setOf(KeyIds.character(c)!!)).guided)
        assertEquals(koId, repo.model.select(ko)); assertEquals(enId, repo.model.select(en))
        s.begin(PracticeWord("r", "r", sentence = true), repo)
        input('r', "", "r")
        s.geometry(en.copy(width = en.width + 10), enId, keyboard.learningGeometry!!)
        assertTrue("Real geometry changes remain excluded", s.invalid)
        assertEquals(0, s.complete(repo, "r"))
    }

    @Test fun `failed atomic save remains visible instead of claiming persisted data`() {
        val directory = File(context.cacheDir, "blocked-${System.nanoTime()}").apply { mkdirs() }
        File(directory, "sentinel").writeText("fixture")
        val repo = SpatialStatsRepository(AtomicFile(directory)); await { repo.ready }
        val id = repo.layout(keyboard().learningLayout!!)
        repo.record(id, repo.generation, listOf(TouchSample(0, 0, 0, true)))
        await { !repo.state.value.saving }
        assertTrue(repo.state.value.failed)
        assertEquals(1, repo.model.summary(id, spatialDay(), 0, 1).guided)
    }

    @Test fun `map pixels match live hit testing including gaps at fractional densities and zoom levels`() {
        for (split in listOf(false, true)) {
            val keyboard = keyboard(KeyboardSettings(portraitMode = if (split) 1 else 0, horizontalKeyGap = 8, verticalKeyGap = 10))
            val original = keyboard.learningLayout!!
            val actual = checkNotNull(keyboard.learningGeometry)
            for (density in listOf(1f, 2.1875f)) {
                fun scaled(key: LayoutKey) = key.copy(left = key.left / density, top = key.top / density,
                    right = key.right / density, bottom = key.bottom / density)
                val descriptor = original.copy(width = original.width / density, height = original.height / density,
                    horizontalGap = original.horizontalGap / density, verticalGap = original.verticalGap / density,
                    keys = original.keys.map(::scaled), surfaces = original.surfaces.map(::scaled), pixelDensity = density)
                val restored = TouchLearningMapView.storedGeometry(context, LayoutDescriptor.decode(descriptor.encode()))
                assertEquals(actual.keyGapLimit, restored.keyGapLimit)
                assertEquals(actual.verticalGapLimit, restored.verticalGapLimit)
                for (key in actual.keys.filter { it.area != null }) {
                    assertEquals(key.bounds, restored.keys.first { it.area?.key == key.area!!.key }.bounds)
                }
                val model = SpatialModel().withLayout(128, descriptor, spatialDay()).updated(128, spatialDay(),
                    descriptor.keys.flatMap { key -> List(30) { TouchSample(key.key, 150, -100, true) } })
                for (personal in listOf(false, true)) for (w in listOf(257, 600)) {
                    val viewport = if (personal) android.graphics.RectF(descriptor.keys.minOf { it.left },
                        descriptor.keys.minOf { it.top }, descriptor.keys.maxOf { it.right }, descriptor.keys.maxOf { it.bottom })
                        else android.graphics.RectF(0f, 0f, descriptor.width, descriptor.height)
                    val h = (w * viewport.height() / viewport.width()).toInt()
                    val plan = io.github.minjj.keyboard.ui.TouchMapRaster.prepare(restored, viewport, w, h, density)
                    val language = if (personal && w == 257) FloatArray(26) { if (it == 1) .18f else 0f } else null
                    val evidence: ((Int) -> Float)? = language?.let { scores -> { key -> scores.getOrElse(key) { 0f } } }
                    val pixels = io.github.minjj.keyboard.ui.TouchMapRaster.render(plan, model, 128,
                        spatialDay(), personal, language)
                    for (y in 0 until h) for (x in 0 until w) {
                        val px = (viewport.left + (x + .5f) * viewport.width() / w) * density
                        val py = (viewport.top + (y + .5f) * viewport.height() / h) * density
                        val literal = actual.keyAt(px, py, true)
                        val id = literal?.takeIf { it.area != null }?.let {
                            if (personal) actual.correctedKey(it, actual.neighbors(it, px, py), px, py,
                                128, spatialDay(), model, evidence).area?.key else it.area?.key
                        }
                        val expected = id?.let { android.graphics.Color.HSVToColor(95,
                            floatArrayOf(if (it < 26) it * 360f / 26 else (it * 137.5f) % 360f, .35f, .9f)) } ?: 0
                        assertEquals("density=$density split=$split personal=$personal pixel=$x,$y", expected, pixels[y * w + x])
                    }
                }
                val q = actual.keys.first { it.area?.key == 0 }
                val w = actual.keys.first { it.area?.key == 1 }
                val gapX = (q.bounds.right + w.bounds.left) / 2f
                val gapY = q.bounds.exactCenterY()
                assertNotNull(io.github.minjj.keyboard.ui.TouchMapRaster.letterAt(restored, model, 128,
                    spatialDay(), gapX, gapY, true))
            }
        }
    }

}

/** Robolectric's Android Os.rename is a no-op on this Windows host; emulate the platform file move. */
@Implements(AtomicFile::class)
class JvmAtomicFile {
    @RealObject private lateinit var real: AtomicFile
    @Implementation fun startWrite(): FileOutputStream = FileOutputStream(File(real.baseFile.path + ".new"))
    @Implementation fun finishWrite(stream: FileOutputStream) {
        stream.fd.sync(); stream.close()
        Files.move(File(real.baseFile.path + ".new").toPath(), real.baseFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
    @Implementation fun failWrite(stream: FileOutputStream) { stream.close(); File(real.baseFile.path + ".new").delete() }
}
