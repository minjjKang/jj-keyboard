package io.github.minjj.keyboard.testing

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Debug-only local fixtures. This Activity and its JavaScript are absent from release builds. */
class ImeTestActivity : Activity() {
    lateinit var primary: EditText; private set
    lateinit var secondary: EditText; private set
    lateinit var web: WebView; private set
    var lastAction = -1; private set
    var acceptImages = false
    var lastImageDecoded = false; private set
    var lastImageMime: String? = null; private set
    var imeAnimating = false; private set
    var lastImeTransition = 0L; private set

    @android.annotation.SuppressLint("SetJavaScriptEnabled", "SetTextI18n") // Fixed debug fixtures only; network loads blocked, no user content or release entry point.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 80, 24, 16)
        }
        root.addView(TextView(this).apply { text = "JJ Keyboard · 기기 입력 시험 (DEBUG)"; textSize = 18f })
        primary = object : EditText(this) {
            override fun onCreateInputConnection(outAttrs: EditorInfo): android.view.inputmethod.InputConnection? {
                val delegate = super.onCreateInputConnection(outAttrs) ?: return null
                if (!acceptImages) return delegate
                outAttrs.contentMimeTypes = arrayOf("image/png")
                return object : android.view.inputmethod.InputConnectionWrapper(delegate, false) {
                    override fun commitContent(content: android.view.inputmethod.InputContentInfo, flags: Int, opts: Bundle?): Boolean {
                        return runCatching {
                            if (flags and android.view.inputmethod.InputConnection.INPUT_CONTENT_GRANT_READ_URI_PERMISSION != 0) content.requestPermission()
                            try {
                                contentResolver.openInputStream(content.contentUri)?.use {
                                    val bitmap = android.graphics.BitmapFactory.decodeStream(it)
                                    lastImageDecoded = bitmap != null && bitmap.width == 48 && bitmap.height == 48 && bitmap.getPixel(0, 0) == android.graphics.Color.BLUE
                                    bitmap?.recycle()
                                }
                                lastImageMime = content.description.getMimeType(0)
                                lastImageDecoded
                            } finally { content.releasePermission() }
                        }.getOrDefault(false)
                    }
                }
            }
        }.apply {
            hint = "Native test input"; textSize = 22f; isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            setOnEditorActionListener { _, action, _ -> lastAction = action; true }
        }
        secondary = EditText(this).apply {
            hint = "Second editor"; textSize = 20f; isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        root.addView(primary, LinearLayout.LayoutParams(-1, 150))
        root.addView(secondary, LinearLayout.LayoutParams(-1, 110))
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.blockNetworkLoads = true
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.decorView.setWindowInsetsAnimationCallback(object : android.view.WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                private val active = mutableSetOf<android.view.WindowInsetsAnimation>()
                override fun onPrepare(animation: android.view.WindowInsetsAnimation) {
                    if (animation.typeMask and android.view.WindowInsets.Type.ime() != 0) {
                        active += animation; imeAnimating = true; lastImeTransition = android.os.SystemClock.uptimeMillis()
                    }
                }
                override fun onProgress(insets: android.view.WindowInsets, running: MutableList<android.view.WindowInsetsAnimation>) = insets
                override fun onEnd(animation: android.view.WindowInsetsAnimation) {
                    if (active.remove(animation)) { imeAnimating = active.isNotEmpty(); lastImeTransition = android.os.SystemClock.uptimeMillis() }
                }
            })
        }
        web.loadDataWithBaseURL("https://fixture.invalid/", """
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <style>body{font:18px sans-serif;background:#f4f6f1}input,textarea,div[contenteditable]{font:22px sans-serif;width:95%;margin:8px 0;border:1px solid #777;min-height:44px}</style>
            <label>WebView input<input id="probe" autocomplete="off"></label>
            <label>WebView textarea<textarea id="area"></textarea></label>
            <div id="editable" contenteditable="true" aria-label="WebView contenteditable"></div>
        """.trimIndent(), "text/html", "UTF-8", null)
        configure()
    }

    fun configure(type: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
        options: Int = EditorInfo.IME_ACTION_NONE, value: String = "") {
        primary.inputType = type
        primary.imeOptions = options
        primary.privateImeOptions = "minjj-fixture-${android.os.SystemClock.elapsedRealtimeNanos()}"
        primary.setText(value)
        primary.setSelection(primary.length())
        lastAction = -1
        lastImageDecoded = false; lastImageMime = null
        primary.requestFocus()
        // EditorInfo is captured when a connection starts; changing imeOptions on a focused
        // EditText requires a new connection before the IME can observe the new action.
        getSystemService(InputMethodManager::class.java).restartInput(primary)
        primary.post { getSystemService(InputMethodManager::class.java).showSoftInput(primary, InputMethodManager.SHOW_IMPLICIT) }
    }
}
