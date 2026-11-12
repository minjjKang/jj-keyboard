package io.github.minjj.keyboard.settings

import android.text.InputType
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.minjj.keyboard.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-port-mdpi")
class SettingsScreenTest {
    @Test fun `portrait menu captions stay on one line and title stays fixed`() = checkMenu(320, 640)

    @Test
    @Config(qualifiers = "w640dp-h320dp-land-mdpi")
    fun `landscape menu captions stay on one line and title stays fixed`() = checkMenu(640, 320)

    @Test fun `practice editors are only on the practice screen`() {
        Robolectric.buildActivity(SettingsActivity::class.java).setup().use { controller ->
            val home = controller.get()
            val views = descendants(home.findViewById(android.R.id.content))
            assertTrue(views.none { it is EditText })
            val title = views.filterIsInstance<TextView>().single { it.text == home.getString(R.string.section_test) }
            val menuRow = (title.parent as View).parent as View
            assertTrue(menuRow.performClick())
            assertEquals(InputPracticeActivity::class.java.name, shadowOf(home).nextStartedActivity.component?.className)
        }

        Robolectric.buildActivity(InputPracticeActivity::class.java).setup().use { controller ->
            val practice = controller.get()
            val editors = descendants(practice.findViewById(android.R.id.content)).filterIsInstance<EditText>().toList()
            assertEquals(5, editors.size)
            val card = editors.first().parent as LinearLayout
            assertEquals(LinearLayout.SHOW_DIVIDER_MIDDLE, card.showDividers)
            assertTrue(editors.all { it.parent === card && it.minHeight >= 48 })
            assertEquals(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, editors[3].inputType)
            assertEquals(EditorInfo.IME_ACTION_SEARCH, editors[4].imeOptions)
            assertFalse(editors.any { it.isSaveEnabled || it.hasFocus() })
        }
    }

    private fun checkMenu(width: Int, height: Int) {
        Robolectric.buildActivity(SettingsActivity::class.java).setup().use { controller ->
            val home = controller.get()
            val root = home.findViewById<ViewGroup>(android.R.id.content)
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            val caption = descendants(root).filterIsInstance<TextView>()
                .single { it.text == home.getString(R.string.delete_gesture_description) }
            val menuRow = (caption.parent as View).parent as View
            val card = menuRow.parent as LinearLayout
            val next = card.getChildAt(card.indexOfChild(menuRow) + 1)
            assertEquals(1, next.top - menuRow.bottom)
            assertTrue(menuRow.measuredHeight >= 48)
            assertEquals(12f, caption.textSize, 0.01f)
            assertEquals(1, caption.lineCount)
            assertEquals(TextUtils.TruncateAt.END, caption.ellipsize)

            val title = descendants(root).filterIsInstance<TextView>()
                .single { it.text == home.getString(R.string.app_name) }
            val scroll = descendants(root).filterIsInstance<ScrollView>().single()
            val titlePosition = IntArray(2).also(title::getLocationInWindow)
            scroll.scrollTo(0, Int.MAX_VALUE)
            assertTrue(scroll.scrollY > 0)
            assertEquals(titlePosition[1], IntArray(2).also(title::getLocationInWindow)[1])
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }
    }
}
