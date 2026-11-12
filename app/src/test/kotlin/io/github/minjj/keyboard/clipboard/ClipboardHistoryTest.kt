package io.github.minjj.keyboard.clipboard

import android.content.ClipData
import android.os.PersistableBundle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ClipboardHistoryTest {
    @Test fun `batch pinning sets mixed selections consistently and deletion preserves other entries`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val directory = File(context.cacheDir, UUID.randomUUID().toString())
        val history = ClipboardHistory(context, directory)
        for (text in listOf("first", "second", "keep")) history.capture(ClipData.newPlainText("fixture", text))
        val ids = history.entries.value.filter { it.text != "keep" }.map { it.id }.toSet()
        history.pin(ids.first())
        history.setPinned(ids, true)
        assertTrue(history.entries.value.filter { it.id in ids }.all { it.pinned })
        assertFalse(history.entries.value.single { it.text == "keep" }.pinned)
        history.setPinned(ids, false)
        assertTrue(history.entries.value.none { it.pinned })
        history.delete(ids)
        val reloaded = ClipboardHistory(context, directory)
        reloaded.refresh()
        assertEquals(listOf("keep"), reloaded.entries.value.map { it.text })
    }

    @Test fun `duplicate copies retain pin and persisted history survives reload`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val directory = File(context.cacheDir, UUID.randomUUID().toString())
        val history = ClipboardHistory(context, directory)
        history.capture(ClipData.newPlainText("fixture", "한글😀"))
        val id = history.entries.value.single().id; history.pin(id)
        assertTrue("Pin applied", history.entries.value.single().pinned)
        history.capture(ClipData.newPlainText("fixture", "한글😀"))
        assertEquals(1, history.entries.value.size)
        assertTrue("Duplicate retains pin", history.entries.value.single().pinned)
        val reloaded = ClipboardHistory(context, directory); reloaded.refresh()
        assertTrue("Reload retains pin", reloaded.entries.value.single().pinned)
        assertEquals("한글😀", reloaded.entries.value.single().text)
        reloaded.delete(id); assertTrue(reloaded.entries.value.isEmpty())
    }

    @Test fun `expiry and clear preserve pinned entries while capacity stays bounded`() = runBlocking {
        val context = RuntimeEnvironment.getApplication(); var clock = 1000L
        val history = ClipboardHistory(context, File(context.cacheDir, UUID.randomUUID().toString())) { clock }
        history.capture(ClipData.newPlainText("fixture", "keep")); history.pin(history.entries.value.single().id)
        repeat(55) { history.capture(ClipData.newPlainText("fixture", "entry-$it")); clock++ }
        assertEquals(50, history.entries.value.size)
        assertTrue(history.entries.value.any { it.text == "keep" && it.pinned })
        clock += 3_600_001; history.refresh()
        assertEquals(listOf("keep"), history.entries.value.map { it.text })
        history.capture(ClipData.newPlainText("fixture", "remove")); history.clearUnpinned()
        assertEquals(listOf("keep"), history.entries.value.map { it.text })
    }

    @Test fun `sensitive oversized and arbitrary file URIs are never retained`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val history = ClipboardHistory(context, File(context.cacheDir, UUID.randomUUID().toString()))
        val secret = ClipData.newPlainText("fixture", "secret").apply {
            description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        }
        history.capture(secret); history.capture(ClipData.newPlainText("fixture", "a".repeat(20_001)))
        history.capture(ClipData.newRawUri("fixture", android.net.Uri.parse("file:///private/data")))
        assertTrue(history.entries.value.isEmpty())
        assertNull(history.imageFile("../../settings")); assertNull(history.imageMime("../history.json"))
    }
}
