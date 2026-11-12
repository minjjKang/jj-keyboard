package io.github.minjj.keyboard.testing

import android.app.Activity
import android.content.ContextWrapper
import android.os.Bundle
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.minjj.keyboard.R
import io.github.minjj.keyboard.hangul.Dubeolsik
import io.github.minjj.keyboard.ime.MinjjImeService
import io.github.minjj.keyboard.ui.KeyboardView
import io.github.minjj.keyboard.ui.TouchDiagnostic
import io.github.minjj.keyboard.ui.TouchDiagnosticObserver
import java.io.File

/** Collects counts and tap ordinals only while the IME is connected to this test field. */
class TypingDiagnosticActivity : Activity() {
    private lateinit var editor: EditText
    private lateinit var sample: TextView
    private lateinit var status: TextView
    private lateinit var finishButton: Button
    private lateinit var startButtons: List<Button>
    private var run: Run? = null

    private class Run(val expected: String, val language: String) : TouchDiagnosticObserver {
        val counts = LongArray(TouchDiagnostic.entries.size)
        val unmappedTaps = mutableListOf<Long>()
        val correctedTaps = mutableListOf<Long>()
        var service: MinjjImeService? = null
        var maxDispatchDelay = 0L
        var editorUpdates = 0
        var startedAt = 0L
        var languageSwitchAt: Long? = null

        override fun onDown(delayMillis: Long): Int {
            maxDispatchDelay = maxOf(maxDispatchDelay, delayMillis)
            return (++counts[TouchDiagnostic.DOWN.ordinal]).toInt()
        }

        override fun record(event: TouchDiagnostic, value: Long) {
            counts[event.ordinal] += when (event) {
                TouchDiagnostic.RESET, TouchDiagnostic.SYSTEM_CANCEL -> value
                else -> 1
            }
            when (event) {
                TouchDiagnostic.NO_KEY -> unmappedTaps += value
                TouchDiagnostic.CORRECTION -> correctedTaps += value
                else -> Unit
            }
        }
    }

    @android.annotation.SuppressLint("SetTextI18n") // Debug-only diagnostic screen.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val padding = (16 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            isFocusableInTouchMode = true
        }
        sample = TextView(this).apply { textSize = 20f; text = "JJ Keyboard · 손 타이핑 진단" }
        status = TextView(this).apply { text = "방향을 정한 뒤 한글 또는 영문을 선택하세요. 입력 원문은 저장하지 않습니다." }
        editor = EditText(this).apply {
            id = View.generateViewId()
            hint = "예문을 여기에 입력"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            filters = arrayOf(InputFilter.LengthFilter(256))
            minLines = 2
            maxLines = 3
            isEnabled = false
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) {
                    run?.takeIf { it.service != null }?.let { it.editorUpdates++ }
                }
                override fun afterTextChanged(text: Editable?) = Unit
            })
        }
        val actions = LinearLayout(this)
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { action() }
            actions.addView(this, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        startButtons = listOf(
            button("한글 시작") { begin("ko", "오늘 아침에는 가벼운 운동을 하고 따뜻한 차를 마셨습니다") },
            button("영문 시작") { begin("en", "the quick brown fox jumps over the lazy dog") },
        )
        finishButton = button("완료") { finishRun() }.apply { isEnabled = false }
        content.addView(actions)
        content.addView(sample)
        content.addView(editor)
        content.addView(status)
        setContentView(ScrollView(this).apply { addView(content) })
        content.requestFocus()
    }

    private val manager get() = getSystemService(InputMethodManager::class.java)
    private val reportFile get() = File(filesDir, "typing-diagnostic.txt")

    private fun begin(language: String, expected: String) {
        reportFile.delete()
        val current = Run(expected, language)
        run = current
        sample.text = expected
        status.text = "JJ Keyboard를 여는 중입니다. 입력 중 안내가 나오면 시작하세요."
        startButtons.forEach { it.isEnabled = false }
        editor.setText("")
        editor.privateImeOptions = "minjj-manual-${SystemClock.elapsedRealtimeNanos()}"
        editor.isEnabled = true
        editor.requestFocus()
        editor.post {
            if (run !== current) return@post
            manager.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            connect(current, SystemClock.uptimeMillis() + 4_000)
        }
    }

    private fun connect(current: Run, deadline: Long) {
        if (run !== current) return
        val connection = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull { root ->
            val view = root.findViewById<View>(R.id.key_delete) ?: return@firstNotNullOfOrNull null
            val service = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
                .filterIsInstance<MinjjImeService>().firstOrNull() ?: return@firstNotNullOfOrNull null
            val info = service.currentInputEditorInfo
            if (!view.isShown || info?.packageName != packageName || info.privateImeOptions != editor.privateImeOptions) {
                return@firstNotNullOfOrNull null
            }
            generateSequence(view) { it.parent as? View }.filterIsInstance<KeyboardView>().firstOrNull()?.let { it to service }
        }
        val keyboard = connection?.first
        val firstLetter = keyboard?.findViewById<TextView>(R.id.key_q)?.text
        val matchingLanguage = firstLetter != null && (firstLetter == "ㅂ" || firstLetter == "ㅃ") == (current.language == "ko")
        if (keyboard != null && firstLetter != null && !matchingLanguage && current.languageSwitchAt == null) {
            current.languageSwitchAt = SystemClock.uptimeMillis()
            keyboard.findViewById<View>(R.id.key_language).performClick()
        }
        val switchSettled = current.languageSwitchAt?.let { SystemClock.uptimeMillis() - it >= 250 } ?: true
        val service = connection?.second
        if (service != null && matchingLanguage && switchSettled &&
            service.startTypingDiagnostic(editor.privateImeOptions, current)
        ) {
            current.service = service
            current.startedAt = SystemClock.uptimeMillis()
            status.text = "입력 중 · 위 예문을 평소 속도로 따라 치세요. 키가 빠져도 고치지 말고 완료를 누르세요."
            finishButton.isEnabled = true
        } else if (SystemClock.uptimeMillis() < deadline) {
            editor.postDelayed({ connect(current, deadline) }, 50)
        } else {
            clearRun()
            status.text = "JJ Keyboard 연결을 확인하지 못했습니다. 현재 입력기를 확인하고 다시 시작하세요."
        }
    }

    private fun finishRun() {
        val current = run ?: return
        val attached = current.service?.stopTypingDiagnostic(current) == true
        val difference = typingDifference(current.expected, editor.text)
        val report = buildString {
            appendLine("language=${current.language} orientation=${resources.configuration.orientation}")
            appendLine("complete=$attached elapsedMs=${SystemClock.uptimeMillis() - current.startedAt}")
            appendLine(difference)
            val words = current.expected.split(' ')
            val expectedTaps = if (current.language == "ko") words.sumOf { Dubeolsik.keysFor(it)?.length ?: it.length }
                else words.sumOf { it.length }
            appendLine("expectedCharacterTaps=$expectedTaps expectedSpaceTaps=${words.size - 1}")
            appendLine("editorUpdates=${current.editorUpdates} maxDownDispatchDelayMs=${current.maxDispatchDelay}")
            for (event in TouchDiagnostic.entries) appendLine("${event.name}=${current.counts[event.ordinal]}")
            appendLine("NO_KEY_TAPS=${current.unmappedTaps.joinToString(",")}")
            appendLine("CORRECTION_TAPS=${current.correctedTaps.joinToString(",")}")
        }
        clearRun()
        manager.hideSoftInputFromWindow(editor.windowToken, 0)
        status.text = if (attached) "진단이 완료됐습니다. 입력 원문을 지웠습니다." else "연결이 바뀌어 일부 집계만 저장했습니다."
        reportFile.writeText(report)
    }

    private fun clearRun() {
        run?.let { it.service?.stopTypingDiagnostic(it) }
        run = null
        editor.setText("")
        editor.clearFocus()
        editor.isEnabled = false
        finishButton.isEnabled = false
        startButtons.forEach { it.isEnabled = true }
    }

    override fun onPause() {
        if (run != null) {
            clearRun()
            status.text = "화면을 떠나 진단을 중단했습니다. 다시 시작하세요."
        }
        super.onPause()
    }
}

private fun typingDifference(expected: String, actual: CharSequence): String {
    val distance = Array(expected.length + 1) { IntArray(actual.length + 1) }
    for (i in 0..expected.length) distance[i][0] = i
    for (j in 0..actual.length) distance[0][j] = j
    fun canSwap(i: Int, j: Int) = i >= 2 && j >= 2 && expected[i - 1] == actual[j - 2] && expected[i - 2] == actual[j - 1]
    for (i in 1..expected.length) for (j in 1..actual.length) {
        distance[i][j] = minOf(distance[i - 1][j] + 1, distance[i][j - 1] + 1,
            distance[i - 1][j - 1] + if (expected[i - 1] == actual[j - 1]) 0 else 1)
        if (canSwap(i, j)) distance[i][j] = minOf(distance[i][j], distance[i - 2][j - 2] + 1)
    }
    var i = expected.length
    var j = actual.length
    var missing = 0
    var extra = 0
    var replaced = 0
    var swapped = 0
    val positions = mutableListOf<Int>()
    while (i > 0 || j > 0) {
        val cost = distance[i][j]
        when {
            i > 0 && j > 0 && expected[i - 1] == actual[j - 1] && cost == distance[i - 1][j - 1] -> {
                i--; j--
            }
            canSwap(i, j) && cost == distance[i - 2][j - 2] + 1 -> {
                positions += i - 1; swapped++; i -= 2; j -= 2
            }
            i > 0 && j > 0 && cost == distance[i - 1][j - 1] + 1 -> {
                positions += i; replaced++; i--; j--
            }
            i > 0 && cost == distance[i - 1][j] + 1 -> {
                positions += i; missing++; i--
            }
            else -> {
                positions += i + 1; extra++; j--
            }
        }
    }
    return "expectedLength=${expected.length} actualLength=${actual.length} " +
        "missing=$missing extra=$extra replaced=$replaced adjacentSwaps=$swapped " +
        "differencePositions=${positions.asReversed().distinct().take(12).joinToString(",")}"
}
