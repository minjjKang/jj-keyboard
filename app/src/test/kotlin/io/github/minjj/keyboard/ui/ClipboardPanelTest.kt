package io.github.minjj.keyboard.ui

import android.graphics.Color
import android.graphics.Insets
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ScrollView
import io.github.minjj.keyboard.R
import io.github.minjj.keyboard.clipboard.ClipEntry
import io.github.minjj.keyboard.clipboard.ClipboardHistory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-port-mdpi")
class ClipboardPanelTest {
    private val first = ClipEntry("first", "첫 문장", "text/plain", 1)
    private val second = ClipEntry("second", "다음 문장", "text/plain", 2, pinned = true)

    private fun panel(
        pasted: (ClipEntry) -> Unit = {},
        pinned: (Set<String>, Boolean) -> Unit = { _, _ -> },
        deleted: (Set<String>) -> Unit = {},
    ): ClipboardPanel {
        val context = RuntimeEnvironment.getApplication()
        return ClipboardPanel(context, ClipboardHistory(context), false, Color.LTGRAY, 4, {}, pasted, pinned, deleted)
            .apply { render(listOf(first, second)) }
    }

    private fun ClipboardPanel.item(id: String): View = findViewWithTag("clip:$id:paste")

    @Test fun `ordinary taps paste while long presses select and back cancels without pasting`() {
        val pasted = mutableListOf<ClipEntry>()
        val panel = panel(pasted = { pasted += it })
        panel.item(first.id).performClick()
        assertEquals(listOf(first), pasted)
        panel.item(first.id).performLongClick()
        panel.item(second.id).performClick()
        assertTrue(panel.item(first.id).isSelected)
        assertTrue(panel.item(second.id).isSelected)
        assertEquals(1, pasted.size)
        assertTrue(panel.cancelSelection())
        assertFalse(panel.cancelSelection())
        panel.item(second.id).performClick()
        assertEquals(listOf(first, second), pasted)
    }

    @Test fun `toolbar pins mixed selections together and unpins an entirely pinned selection`() {
        val calls = mutableListOf<Pair<Set<String>, Boolean>>()
        val panel = panel(pinned = { ids, value -> calls += ids to value })
        panel.findViewById<View>(R.id.clipboard_pin_selected).performClick()
        assertTrue(calls.isEmpty())
        panel.item(first.id).performClick()
        panel.item(second.id).performClick()
        panel.findViewById<View>(R.id.clipboard_pin_selected).performClick()
        assertEquals(listOf(setOf(first.id, second.id) to true), calls)
        panel.item(second.id).performLongClick()
        assertEquals(panel.context.getString(R.string.clipboard_unpin_selected),
            panel.findViewById<View>(R.id.clipboard_pin_selected).contentDescription)
        panel.findViewById<View>(R.id.clipboard_pin_selected).performClick()
        assertEquals(setOf(second.id) to false, calls.last())
    }

    @Test fun `selection follows live history changes and deleting never includes missing or unselected entries`() {
        val deleted = mutableListOf<Set<String>>()
        val panel = panel(deleted = { deleted += it })
        panel.item(first.id).performLongClick()
        panel.item(second.id).performClick()
        panel.render(listOf(second))
        panel.findViewById<View>(R.id.clipboard_delete_selected).performClick()
        assertEquals(listOf(setOf(second.id)), deleted)
        panel.render(emptyList())
        assertFalse(panel.findViewById<View>(R.id.clipboard_delete_selected).isEnabled)
    }

    @Test fun `content and compact cards stay inside navigation and side safe areas`() {
        val panel = panel()
        panel.dispatchApplyWindowInsets(WindowInsets.Builder()
            .setInsets(WindowInsets.Type.navigationBars(), Insets.of(7, 0, 13, 30)).build())
        for (width in listOf(320, 640)) {
            panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY))
            panel.layout(0, 0, width, 240)
            val scroll = (0 until panel.childCount).map(panel::getChildAt).filterIsInstance<ScrollView>().single()
            assertEquals(34, panel.paddingBottom)
            assertTrue(scroll.bottom <= panel.height - 34)
            assertTrue(scroll.left >= 7)
            assertTrue(scroll.right <= width - 13)
            assertTrue(panel.item(first.id).width <= width / 4)
            assertEquals(44, (panel.getChildAt(0) as ViewGroup).height)
        }
    }
}
