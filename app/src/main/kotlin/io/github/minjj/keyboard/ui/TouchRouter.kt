package io.github.minjj.keyboard.ui

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import io.github.minjj.keyboard.ime.EditorProfile
import io.github.minjj.keyboard.ime.InputSource
import io.github.minjj.keyboard.ime.KeyCommand
import io.github.minjj.keyboard.ime.KeySpec
import io.github.minjj.keyboard.ime.KeyboardController
import io.github.minjj.keyboard.ime.WordOffer
import io.github.minjj.keyboard.settings.KeyboardSettings
import io.github.minjj.keyboard.settings.DEFAULT_DELETE_GESTURE_STEP_DP
import io.github.minjj.keyboard.settings.MAX_DELETE_GESTURE_STEP_DP
import io.github.minjj.keyboard.settings.MIN_DELETE_GESTURE_STEP_DP
import io.github.minjj.keyboard.touch.KeyIds
import io.github.minjj.keyboard.touch.SpatialModel
import io.github.minjj.keyboard.correction.SpatialStatsRepository
import io.github.minjj.keyboard.correction.TouchObservation
import io.github.minjj.keyboard.correction.spatialDay
import kotlin.math.abs

internal enum class TouchDiagnostic {
    DOWN, OVERLAP, NO_KEY, CHARACTER_TARGET, SPACE_TARGET, MOVE_CANCEL, RELEASE_OUTSIDE, SYSTEM_CANCEL, RESET,
    GESTURE, TAP_READY, CORRECTION, CHARACTER_ACCEPTED, CHARACTER_REJECTED, SPACE_DISPATCHED,
}

internal interface TouchDiagnosticObserver {
    fun onDown(delayMillis: Long): Int

    /** NO_KEY and CORRECTION carry the 1-based tap ordinal. RESET and SYSTEM_CANCEL carry a count. */
    fun record(event: TouchDiagnostic, value: Long = 0)
}

/** Owns each touch from DOWN to UP/CANCEL. Native buttons still handle semantic clicks. */
internal class TouchRouter(
    private val controller: KeyboardController,
    private val profile: () -> EditorProfile,
    private val settings: () -> KeyboardSettings,
    private val secondaryFor: (KeySpec) -> String?,
    private val feedback: (View, Boolean) -> Unit,
    private val dp: (Int) -> Int,
    private val spatialStats: () -> SpatialStatsRepository?,
    private val spatialProfile: () -> Int,
    private val swipeLanguage: (direction: Int) -> Unit,
    private val showCursorMode: (Boolean) -> Unit,
) {
    private class Press(
        val key: TouchKey,
        val downX: Float,
        val downY: Float,
        val downTime: Long,
        val finger: Boolean,
        val offer: WordOffer?,
        val diagnosticObserver: TouchDiagnosticObserver?,
        val diagnosticOrdinal: Int,
    ) {
        var cancelled = false
        var longPress: Runnable? = null
        var consumed = false
        var cursorMode = false
        var cursorX = downX
        var deleteSliding = false
        var deleteByWord = false
        var deleteApplied = 0
        var tapReady = false
        var heldShiftOnRelease = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private val neutralSpatial = SpatialModel()
    private val presses = mutableMapOf<Int, Press>()
    var diagnostics: TouchDiagnosticObserver? = null
    // Released taps wait here while an earlier finger can still produce a tap.
    private val orderedTaps = ArrayDeque<Press>()
    private var repeating: Press? = null
    private var geometry = GeometrySnapshot(emptyList(), 0)
    private val repeat = object : Runnable {
        override fun run() {
            val press = repeating ?: return
            if (!press.key.view.isAttachedToWindow) {
                stopRepeat(press)
                return
            }
            controller.press(press.key.spec, InputSource.TOUCH_GESTURE)
            if (repeating === press) handler.postDelayed(this, settings().repeatMillis.toLong())
        }
    }

    fun updateGeometry(snapshot: GeometrySnapshot) {
        if (geometry == snapshot) return
        geometry = snapshot
        controller.discardTouchTraining()
        cancel()
    }

    fun route(event: MotionEvent) {
        val index = event.actionIndex
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancel()
                down(event, index)
            }
            MotionEvent.ACTION_POINTER_DOWN -> down(event, index)
            MotionEvent.ACTION_MOVE -> for (i in 0 until event.pointerCount) {
                val press = presses[event.getPointerId(i)] ?: continue
                move(press, event.getX(i), event.getY(i), event.eventTime)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val press = presses.remove(event.getPointerId(index)) ?: return
                val cancelled = Build.VERSION.SDK_INT >= 33 && event.flags and MotionEvent.FLAG_CANCELED != 0
                finish(press, event.getX(index), event.getY(index), cancelled)
            }
            MotionEvent.ACTION_CANCEL -> {
                // Only fingers still down belong to the cancelled part of the stream.
                diagnostics?.record(TouchDiagnostic.SYSTEM_CANCEL, presses.size.toLong())
                orderedTaps.removeAll { !it.tapReady }
                drainTaps()
                cancel(reportReset = false)
                return
            }
        }
        drainTaps()
    }

    fun cancel(notifyShift: Boolean = true, reportReset: Boolean = true) {
        if (reportReset && orderedTaps.isNotEmpty()) diagnostics?.record(TouchDiagnostic.RESET, orderedTaps.size.toLong())
        for (press in presses.values) {
            cancelLongPress(press)
            press.key.view.isPressed = false
        }
        presses.clear()
        orderedTaps.clear()
        showCursorMode(false)
        repeating?.let(::stopRepeat)
        if (controller.shiftHeld) controller.cancelTouches(notifyShift)
    }

    private fun down(event: MotionEvent, index: Int) {
        val observer = diagnostics
        val ordinal = observer?.onDown((SystemClock.uptimeMillis() - event.eventTime).coerceAtLeast(0)) ?: 0
        if (event.pointerCount > 1) observer?.record(TouchDiagnostic.OVERLAP)
        val x = event.getX(index)
        val y = event.getY(index)
        val key = keyAt(x, y) ?: run {
            controller.practiceSession?.reject("no-key")
            observer?.record(TouchDiagnostic.NO_KEY, ordinal.toLong())
            return
        }
        val pointerId = event.getPointerId(index)
        val finger = event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN) &&
            event.getToolType(index) == MotionEvent.TOOL_TYPE_FINGER
        val offer = if (key.spec.command == KeyCommand.SUGGESTION) key.view.tag as? WordOffer else null
        if (key.spec.command == KeyCommand.SUGGESTION && offer == null) return
        val press = Press(key, x, y, event.eventTime, finger, offer, observer, ordinal)
        val command = key.spec.command
        observer?.let {
            when (command) {
                KeyCommand.CHARACTER -> it.record(TouchDiagnostic.CHARACTER_TARGET)
                KeyCommand.SPACE -> it.record(TouchDiagnostic.SPACE_TARGET)
                else -> Unit
            }
        }
        val firstShift = command == KeyCommand.SHIFT && !hasShiftPointer()
        presses[pointerId] = press
        if ((command == KeyCommand.CHARACTER || command == KeyCommand.SPACE) && controller.practiceSession?.word?.actionKey == null) orderedTaps.addLast(press)
        key.view.isPressed = true
        if (firstShift && controller.practiceSession?.word?.actionKey == null) controller.holdShift()
        val hasLongPress = command == KeyCommand.DELETE ||
            (command == KeyCommand.SPACE && settings().spaceCursor) || secondaryFor(key.spec) != null
        if (hasLongPress && presses[pointerId] === press) {
            val task = Runnable {
                press.longPress = null
                if (!press.cancelled && key.view.isAttachedToWindow) onLongPress(press)
            }
            press.longPress = task
            handler.postDelayed(task, settings().longPressMillis.toLong())
        }
    }

    private fun onLongPress(press: Press) {
        if (controller.practiceSession?.word?.actionKey != null) { press.consumed = true; controller.practiceSession?.reject("long-press"); return }
        if (press.key.spec.command != KeyCommand.SPACE || controller.practiceSession?.word?.sentence != true)
            controller.practiceSession?.reject("long-press")
        when (press.key.spec.command) {
            KeyCommand.DELETE -> {
                if ((deleteGestureEnabled() && controller.shiftHeld) || repeating != null) return
                press.consumed = true
                repeating = press
                click(press.key, InputSource.TOUCH_GESTURE)
                if (repeating === press) handler.postDelayed(repeat, settings().repeatMillis.toLong())
            }
            KeyCommand.SPACE -> {
                if (!consumeTap(press)) return
                press.cursorMode = true
                showCursorMode(true)
                controller.beginCursorGesture()
                feedback(press.key.view, false)
            }
            KeyCommand.CHARACTER -> {
                val value = secondaryFor(press.key.spec) ?: return
                if (!consumeTap(press)) return
                feedback(press.key.view, false)
                controller.secondary(value, InputSource.TOUCH_LONG_PRESS)
                press.key.view.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_LONG_CLICKED)
            }
            else -> Unit
        }
    }

    private fun consumeTap(press: Press): Boolean {
        diagnostics?.record(TouchDiagnostic.GESTURE)
        press.consumed = true
        orderedTaps.remove(press)
        drainTaps()
        return press in presses.values
    }

    private fun move(press: Press, x: Float, y: Float, time: Long) {
        if (press.cancelled) return
        if (controller.practiceSession?.word?.actionKey != null) { if (!withinTapArea(press, x, y)) cancelPress(press); return }
        when (press.key.spec.command) {
            KeyCommand.DELETE -> when {
                abs(y - press.downY) > dp(48) -> cancelPress(press)
                wantsDeleteSlide(press, x) -> {
                    cancelLongPress(press)
                    stopRepeat(press)
                    slideDelete(press, x)
                }
                !containsTap(press, x, y) -> cancelPress(press)
            }
            KeyCommand.SPACE -> {
                if (press.cursorMode) {
                    val steps = ((x - press.cursorX) / dp(14)).toInt().coerceIn(-8, 8)
                    if (steps != 0) {
                        press.cursorX += steps * dp(14)
                        controller.moveCursor(steps)
                    }
                } else if (!press.consumed && settings().spaceLanguageSwipe && abs(x - press.downX) >= dp(60 - settings().spaceLanguageSensitivity.coerceIn(0, 100) * 40 / 100) &&
                    abs(y - press.downY) < dp(32) && time - press.downTime < settings().longPressMillis
                ) {
                    cancelLongPress(press)
                    if (!consumeTap(press)) return
                    updatePressed(press.key)
                    swipeLanguage(if (x < press.downX) -1 else 1)
                } else if (abs(y - press.downY) > dp(48)) {
                    cancelPress(press)
                }
            }
            KeyCommand.CHARACTER -> {
                if (!withinTapArea(press, x, y)) cancelPress(press)
                else if (press.longPress != null && !press.key.contains(x, y) && keyAt(x, y) !== press.key) {
                    cancelLongPress(press)
                }
            }
            else -> if (!withinTapArea(press, x, y)) cancelPress(press)
        }
    }

    private fun finish(press: Press, x: Float, y: Float, cancelled: Boolean) {
        cancelLongPress(press)
        stopRepeat(press)
        updatePressed(press.key)
        if (press.cursorMode) showCursorMode(presses.values.any { it.cursorMode })
        val aborted = cancelled || press.cancelled
        if (aborted) controller.practiceSession?.reject("cancelled")
        if (controller.practiceSession?.word?.actionKey != null) {
            if (!aborted && !press.consumed && withinTapArea(press, x, y)) finishTap(press)
            else controller.practiceSession?.reject("cancelled")
            return
        }
        when (press.key.spec.command) {
            KeyCommand.SUGGESTION -> if (!aborted && press.key.contains(x, y)) {
                press.offer?.takeIf { it == press.key.view.tag }?.let(controller::selectWord)
            }
            KeyCommand.SHIFT -> if (!hasShiftPointer()) {
                if (controller.releaseShift(aborted || !withinTapArea(press, x, y))) {
                    finishTap(press)
                } else if (!aborted && press.finger && withinTapArea(press, x, y) && controller.practiceSession?.word?.sentence == true) {
                    val density = press.key.view.resources.displayMetrics.density
                    press.key.area?.let { controller.practiceSession?.tap(KeyIds.token(it.key), KeyIds.token(it.key), press.downX / density, press.downY / density) }
                }
            }
            KeyCommand.DELETE -> if (!aborted && abs(y - press.downY) <= dp(48)) {
                // MOVE can be resampled beyond the finger. Reconcile with the release position.
                if (wantsDeleteSlide(press, x)) slideDelete(press, x)
                else if (!press.consumed && !press.deleteSliding && containsTap(press, x, y)) {
                    finishTap(press)
                }
            }
            KeyCommand.CHARACTER, KeyCommand.SPACE -> {
                val inside = if (press.key.spec.command == KeyCommand.SPACE) abs(y - press.downY) <= dp(48)
                    else withinTapArea(press, x, y)
                if (!aborted && !press.consumed && inside) {
                    diagnostics?.record(TouchDiagnostic.TAP_READY)
                    press.heldShiftOnRelease = controller.captureHeldShift(press.key.spec)
                    press.tapReady = true
                } else {
                    if (cancelled) diagnostics?.record(TouchDiagnostic.SYSTEM_CANCEL, 1)
                    else if (!press.cancelled && !press.consumed && !inside) {
                        diagnostics?.record(TouchDiagnostic.RELEASE_OUTSIDE)
                    }
                    orderedTaps.remove(press)
                }
            }
            else -> if (!aborted && !press.consumed && withinTapArea(press, x, y)) {
                finishTap(press)
            }
        }
    }

    private fun drainTaps() {
        while (true) {
            val press = orderedTaps.firstOrNull() ?: return
            if (!press.tapReady) return
            orderedTaps.removeFirst()
            finishTap(press)
        }
    }

    private fun withinTapArea(press: Press, x: Float, y: Float): Boolean = when (press.key.spec.command) {
        KeyCommand.CHARACTER -> press.key.contains(x, y, dp(6)) || keyAt(x, y) === press.key
        KeyCommand.SHIFT -> containsTap(press, x, y)
        else -> containsTap(press, x, y, ViewConfiguration.get(press.key.view.context).scaledTouchSlop)
    }

    private fun containsTap(press: Press, x: Float, y: Float, slop: Int = 0): Boolean =
        press.key.contains(x, y, slop) ||
            (!press.key.contains(press.downX, press.downY) && keyAt(x, y) === press.key)

    private fun keyAt(x: Float, y: Float) = geometry.keyAt(x, y, allowKeyGaps = !profile().password)

    private fun deleteGestureEnabled() = settings().deleteGesture && !profile().password

    private fun deleteGestureStep() = dp(settings().deleteGestureStepDp
        .coerceIn(MIN_DELETE_GESTURE_STEP_DP, MAX_DELETE_GESTURE_STEP_DP)).coerceAtLeast(1)

    private fun wantsDeleteSlide(press: Press, x: Float) =
        // Capture the slide before leaving the key even when a deletion tick is wider than the key.
        deleteGestureEnabled() && (press.deleteSliding ||
            abs(x - press.downX) >= minOf(deleteGestureStep(), dp(DEFAULT_DELETE_GESTURE_STEP_DP)))

    private fun slideDelete(press: Press, x: Float) {
        if (!press.deleteSliding) {
            press.deleteByWord = controller.shiftHeld
            press.deleteSliding = true
        }
        val desired = ((x - press.downX) / deleteGestureStep()).toInt()
        val steps = (desired - press.deleteApplied).coerceIn(-64, 64)
        if (steps == 0) return
        val applied = controller.slideDelete(steps, press.deleteByWord)
        press.deleteApplied += applied
        if (applied != 0) feedback(press.key.view, true)
    }

    private fun hasShiftPointer() = presses.values.any { it.key.spec.command == KeyCommand.SHIFT }

    private fun cancelPress(press: Press) {
        diagnostics?.record(TouchDiagnostic.MOVE_CANCEL)
        press.cancelled = true
        orderedTaps.remove(press)
        cancelLongPress(press)
        stopRepeat(press)
        updatePressed(press.key)
    }

    private fun updatePressed(key: TouchKey) {
        key.view.isPressed = presses.values.any { press ->
            val languageSwiped = key.spec.command == KeyCommand.SPACE && press.consumed && !press.cursorMode
            press.key === key && !press.cancelled && !languageSwiped
        }
    }

    private fun cancelLongPress(press: Press) {
        press.longPress?.let(handler::removeCallbacks)
        press.longPress = null
    }

    private fun stopRepeat(press: Press) {
        if (repeating !== press) return
        repeating = null
        handler.removeCallbacks(repeat)
    }

    private fun finishTap(press: Press) {
        val key = press.key
        val options = settings()
        val field = profile()
        if (!press.finger || key.area == null || !field.allowsSpatialModel) {
            click(key, InputSource.TOUCH_TAP, tap = press)
            return
        }
        val practice = controller.practiceSession
        val stats = if (options.touchCorrection || options.touchLearning || practice != null) spatialStats() else null
        val frozen = practice?.takeIf { it.generation == stats?.generation }?.model
        val spatial = (frozen ?: stats?.model)?.takeIf { options.touchCorrection }
        val contextual = key.spec.letter && options.contextualTouch && field.allowsLanguageModel
        val suggestions = key.spec.letter && options.wordSuggestions && field.allowsLanguageModel
        val neighbors = if (spatial != null || contextual || suggestions) {
            geometry.neighbors(key, press.downX, press.downY)
        } else emptyList()
        val evidence = if (contextual && neighbors.isNotEmpty()) controller.contextualEvidence(press.heldShiftOnRelease) else null
        val layout = if (stats != null) spatialProfile() else 0
        val chosen = if (neighbors.isNotEmpty() && (spatial != null || evidence != null)) {
            geometry.correctedKey(key, neighbors, press.downX, press.downY, layout, spatialDay(), spatial ?: neutralSpatial, evidence)
        } else key
        if (!key.spec.letter && chosen.spec.letter) {
            // Function taps dispatch immediately; queued character/space taps retain their release-time Shift.
            if (key.spec.command !in setOf(KeyCommand.CHARACTER, KeyCommand.SPACE))
                press.heldShiftOnRelease = controller.captureHeldShift(chosen.spec)
            else if (press.heldShiftOnRelease) controller.captureHeldShift(chosen.spec)
        }
        if (chosen !== key && diagnostics === press.diagnosticObserver) {
            diagnostics?.record(TouchDiagnostic.CORRECTION, press.diagnosticOrdinal.toLong())
        }
        val observation = if (practice == null && stats != null && chosen === key && options.touchLearning && field.allowsSpatialLearning) {
            TouchObservation(layout, stats.generation, key.area.centerSample(press.downX, press.downY))
        } else null
        val alternatives = if (suggestions && neighbors.isNotEmpty()) {
            (listOf(key) + neighbors).filter { it !== chosen && it.spec.letter }.joinToString("") { it.spec.value }
        } else ""
        // Non-letter/function taps have no word-level provisional label. Accept only literal centers.
        val direct = observation?.takeIf { !key.spec.letter && controller.activeForTouch }?.sample
        if (direct != null && chosen.spec.command != KeyCommand.CHARACTER) stats?.record(layout, observation.generation, listOf(direct))
        val result = click(chosen, InputSource.TOUCH_TAP, observation.takeIf { key.spec.letter }, alternatives, press)
        if (direct != null && chosen.spec.command == KeyCommand.CHARACTER && result?.accepted == true)
            stats?.record(layout, observation.generation, listOf(direct))
    }

    private fun click(key: TouchKey, source: InputSource, observation: TouchObservation? = null,
                      alternatives: String = "", tap: Press? = null): io.github.minjj.keyboard.ime.CharacterInputResult? {
        if (!key.toolbar) feedback(key.view, false)
        val practice = controller.practiceSession
        if (practice?.word?.actionKey != null) {
            if (tap == null || !tap.finger || source != InputSource.TOUCH_TAP || tap.key.area == null || key.area == null) practice.reject("function-tap")
            else {
                val density = key.view.resources.displayMetrics.density
                practice.tap(KeyIds.token(tap.key.area.key), KeyIds.token(key.area.key), tap.downX / density, tap.downY / density)
            }
            return null
        }
        val shifted = (tap?.heldShiftOnRelease ?: controller.shiftHeld) || controller.shift != io.github.minjj.keyboard.ime.ShiftState.OFF
        fun token(value: Char): Char = if (shifted && (!controller.korean ||
            io.github.minjj.keyboard.hangul.Dubeolsik.map(value, true) != io.github.minjj.keyboard.hangul.Dubeolsik.map(value, false))) value.uppercaseChar() else value
        if (practice?.word?.sentence == true && key.spec.command in setOf(KeyCommand.SHIFT, KeyCommand.SPACE, KeyCommand.DELETE, KeyCommand.ENTER)) {
            if (tap == null || !tap.finger || source != InputSource.TOUCH_TAP || tap.key.area == null || key.area == null) practice.reject("function-tap")
            else {
                val density = key.view.resources.displayMetrics.density
                practice.tap(KeyIds.token(tap.key.area.key), KeyIds.token(key.area.key), tap.downX / density, tap.downY / density)
            }
        }
        practice?.dispatchCommand = key.spec.command.name
        practice?.dispatchEvent = if (key.spec.command == KeyCommand.CHARACTER) practice.taps.size else practice.taps.lastIndex
        val result = controller.press(key.spec, source, observation, alternatives,
            heldShiftForKey = tap?.heldShiftOnRelease, eventTime = tap?.downTime)
        if (practice != null && key.spec.command == KeyCommand.CHARACTER) {
            if (tap == null || !tap.finger || source != InputSource.TOUCH_TAP || result?.accepted != true || tap.key.area == null || key.area == null)
                practice.reject("tap:${tap == null}:${tap?.finger}:${result?.accepted}")
            else {
                val density = key.view.resources.displayMetrics.density
                practice.tap(token(KeyIds.token(checkNotNull(tap.key.area).key)), token(KeyIds.token(checkNotNull(key.area).key)), tap.downX / density, tap.downY / density)
            }
        }
        diagnostics?.let { observer ->
            if (result != null) observer.record(if (result.accepted) TouchDiagnostic.CHARACTER_ACCEPTED else TouchDiagnostic.CHARACTER_REJECTED)
            if (key.spec.command == KeyCommand.SPACE) observer.record(TouchDiagnostic.SPACE_DISPATCHED)
        }
        key.view.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED)
        return result
    }
}
