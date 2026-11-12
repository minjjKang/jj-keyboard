package io.github.minjj.keyboard.ui

import android.content.Context
import android.media.AudioAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings

/** Uses the same strength mapping as minjj-oho's GestureHaptics. */
internal object KeyboardHaptics {
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    @Suppress("DEPRECATION")
    fun play(context: Context, strength: Int) {
        if (strength <= 0 || Settings.System.getInt(
                context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1
            ) == 0) return
        val vibrator = context.getSystemService(Vibrator::class.java) ?: return
        if (!vibrator.hasVibrator()) return
        val level = strength.coerceAtMost(100)
        val effect = if (vibrator.hasAmplitudeControl()) {
            val amplitude = (level * 255 / 100).coerceAtLeast(1)
            VibrationEffect.createOneShot(20, amplitude)
        } else {
            // On/off motors ignore amplitude; vary pulse length from 10 to 60 ms instead.
            val durationMs = 10L + (level - 1) * 50L / 99
            VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        vibrator.vibrate(effect, attributes)
    }
}
