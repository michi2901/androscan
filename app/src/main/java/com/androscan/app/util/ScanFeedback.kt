package com.androscan.app.util

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

object ScanFeedback {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun peep() {
        playTone(durationMs = 120)
    }

    fun doublePeep() {
        playTone(durationMs = 100)
        mainHandler.postDelayed({ playTone(durationMs = 100) }, 180L)
    }

    fun vibrateOnce(context: Context) {
        vibrate(context, longArrayOf(0, 40))
    }

    fun vibrateDouble(context: Context) {
        vibrate(context, longArrayOf(0, 50, 80, 50))
    }

    private fun playTone(durationMs: Int) {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, durationMs)
            mainHandler.postDelayed({ tone.release() }, durationMs + 50L)
        } catch (_: Exception) {
            // ignore missing audio
        }
    }

    @Suppress("DEPRECATION")
    private fun vibrate(context: Context, pattern: LongArray) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(VibratorManager::class.java)
                manager?.defaultVibrator?.vibrate(
                    VibrationEffect.createWaveform(pattern, -1)
                )
            } else {
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
                } else {
                    vibrator?.vibrate(pattern, -1)
                }
            }
        } catch (_: Exception) {
            // ignore missing vibrator
        }
    }
}
