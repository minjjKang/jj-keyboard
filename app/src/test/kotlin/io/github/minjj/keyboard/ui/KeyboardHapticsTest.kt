package io.github.minjj.keyboard.ui

import android.content.Context
import android.os.VibrationAttributes
import android.os.Vibrator
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowVibrator
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeyboardHapticsTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private val shadow = shadowOf(vibrator)

    @Test fun `amplitude motors use a fixed pulse with the selected strength`() {
        shadow.setHasVibrator(true)
        shadow.setHasAmplitudeControl(true)
        for ((strength, amplitude) in listOf(1 to 2, 50 to 127, 100 to 255, 150 to 255)) {
            KeyboardHaptics.play(context, strength)
            assertEquals(20L, shadow.milliseconds)
            val segments = ReflectionHelpers.getStaticField<List<Any>>(ShadowVibrator::class.java, "vibrationEffectSegments")
            assertEquals(amplitude / 255f, ReflectionHelpers.callInstanceMethod<Float>(segments.single(), "getAmplitude"), 0.001f)
            assertEquals(VibrationAttributes.USAGE_TOUCH,
                (shadow.vibrationAttributesFromLastVibration as VibrationAttributes).usage)
        }
    }

    @Test fun `motors without amplitude control vary pulse duration`() {
        shadow.setHasVibrator(true)
        shadow.setHasAmplitudeControl(false)
        for ((strength, duration) in listOf(1 to 10L, 50 to 34L, 100 to 60L)) {
            KeyboardHaptics.play(context, strength)
            assertEquals(duration, shadow.milliseconds)
        }
    }

    @Test fun `zero strength system haptics off and missing motor stay silent`() {
        shadow.setHasVibrator(true)
        KeyboardHaptics.play(context, 0)
        KeyboardHaptics.play(context, -1)
        assertFalse(shadow.isVibrating)
        Settings.System.putInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 0)
        KeyboardHaptics.play(context, 50)
        assertFalse(shadow.isVibrating)
        Settings.System.putInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1)
        shadow.setHasVibrator(false)
        KeyboardHaptics.play(context, 50)
        assertFalse(shadow.isVibrating)
    }
}
