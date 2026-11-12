package io.github.minjj.keyboard.ime

import android.content.res.Configuration
import android.text.Editable
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import io.github.minjj.keyboard.hangul.Dubeolsik
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ImeLifecycleTest {
    private class Editor(val buffer: SpannableStringBuilder = SpannableStringBuilder().also { Selection.setSelection(it, 0) }) : BaseInputConnection(View(RuntimeEnvironment.getApplication()), true) {
        override fun getEditable(): Editable = buffer
    }
    private fun start(service: MinjjImeService, editor: Editor) {
        val info = EditorInfo().apply { initialSelStart = 0; initialSelEnd = 0; inputType = 1; packageName = "test.editor"; privateImeOptions = "stable-field" }
        ReflectionHelpers.setField(service, "mInputConnection", editor)
        ReflectionHelpers.setField(service, "mStartedInputConnection", editor)
        ReflectionHelpers.setField(service, "mInputStarted", true)
        ReflectionHelpers.setField(service, "mInputEditorInfo", info)
        service.onStartInput(info, false)
    }

    @Test fun `configuration recreation preserves composition on the same connection`() {
        val lifecycle = Robolectric.buildService(MinjjImeService::class.java).create()
        try {
            val service = lifecycle.get(); val editor = Editor()
            start(service, editor)
            val before = ReflectionHelpers.getField<EditorSession>(service, "session")
            "rks".forEach { before.jamo(Dubeolsik.map(it)!!) }
            service.onConfigurationChanged(Configuration(service.resources.configuration).apply { screenWidthDp = 800 })
            val after = ReflectionHelpers.getField<EditorSession>(service, "session")
            assertSame(before, after)
            after.jamo('ㅏ')
            assertEquals("가나", editor.buffer.toString())
        } finally { lifecycle.destroy() }
    }

    @Test fun `new editor does not inherit the previous composing state`() {
        val lifecycle = Robolectric.buildService(MinjjImeService::class.java).create()
        try {
            val service = lifecycle.get(); val first = Editor()
            start(service, first)
            val previous = ReflectionHelpers.getField<EditorSession>(service, "session")
            previous.jamo('ㄱ'); previous.jamo('ㅏ')
            service.onFinishInput()
            val second = Editor(); start(service, second)
            ReflectionHelpers.getField<EditorSession>(service, "session").jamo('ㄴ')
            previous.jamo('ㅏ')
            assertEquals("가", first.buffer.toString()); assertEquals("ㄴ", second.buffer.toString())
        } finally { lifecycle.destroy() }
    }

    @Test fun `deferred configuration restart recovers only the same identified editor`() {
        for (sameField in listOf(true, false)) {
            val lifecycle = Robolectric.buildService(MinjjImeService::class.java).create()
            try {
                val service = lifecycle.get(); val editor = Editor(); start(service, editor)
                val before = ReflectionHelpers.getField<EditorSession>(service, "session")
                "rks".forEach { before.jamo(Dubeolsik.map(it)!!) }
                service.onConfigurationChanged(Configuration(service.resources.configuration).apply { orientation = Configuration.ORIENTATION_LANDSCAPE })
                service.onUpdateSelection(1, 1, 1, 1, -1, -1)
                val replacement = Editor(editor.buffer)
                val info = EditorInfo().apply {
                    initialSelStart = 1; initialSelEnd = 1; inputType = 1; packageName = "test.editor"
                    privateImeOptions = if (sameField) "stable-field" else "different-field"
                }
                ReflectionHelpers.setField(service, "mInputConnection", replacement)
                ReflectionHelpers.setField(service, "mStartedInputConnection", replacement)
                ReflectionHelpers.setField(service, "mInputEditorInfo", info)
                service.onStartInput(info, true)
                ReflectionHelpers.getField<EditorSession>(service, "session").jamo('ㅏ')
                assertEquals(if (sameField) "가나" else "간ㅏ", editor.buffer.toString())
            } finally { lifecycle.destroy() }
        }
    }
}
