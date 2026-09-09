package com.aeroglyph.app.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.PI
import kotlin.math.sin

/**
 * Confirmation you can feel and hear.
 *
 * The chime is deliberately in the ordinary audible range (A5 → E6), nowhere
 * near the 17-19.5kHz band the modem uses -- it's for the human, and it must
 * never be mistaken by another device for signal.
 */
class Feedback(context: Context) {

    private val appContext = context.applicationContext

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    var hapticsEnabled: Boolean = true
    var chimeEnabled: Boolean = true

    /** Light tick the moment above-floor signal is first detected -- a felt cue that something is landing, before we know yet whether it decodes. */
    fun signalDetected() {
        if (!hapticsEnabled) return
        vibrateMs(8, amplitude = 40)
    }

    /** Stronger confirmation when a whole message has passed CRC. */
    fun messageReceived() {
        if (!hapticsEnabled) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val timings = longArrayOf(0, 30, 60, 90)
            val amplitudes = intArrayOf(0, 160, 0, 255)
            runCatching { vibrator?.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1)) }
        } else {
            vibrateMs(120, amplitude = 255)
        }
    }

    /**
     * Accessibility pulse: conveys "a message arrived, and roughly how long it
     * is" without needing to look at the screen. Not Morse -- just a felt
     * shape: one long lead-in, then one short pulse per ~20 characters,
     * capped so a long URL doesn't buzz forever.
     */
    fun accessibilityPulse(messageLength: Int) {
        if (!hapticsEnabled) return
        val pulses = (messageLength / 20).coerceIn(1, 6)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val timings = mutableListOf(0L, 220L)
            val amplitudes = mutableListOf(0, 200)
            repeat(pulses) {
                timings += 120L
                amplitudes += 0
                timings += 70L
                amplitudes += 140
            }
            runCatching {
                vibrator?.vibrate(VibrationEffect.createWaveform(timings.toLongArray(), amplitudes.toIntArray(), -1))
            }
        } else {
            vibrateMs(220, 255)
        }
    }

    fun error() {
        if (!hapticsEnabled) return
        vibrateMs(40, amplitude = 120)
    }

    private fun vibrateMs(durationMs: Long, amplitude: Int) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, amplitude))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(durationMs)
            }
        }
    }

    /** Short two-note confirmation chime, synthesized rather than shipped as an asset. */
    fun playChime() {
        if (!chimeEnabled) return
        runCatching {
            val pcm = chimePcm
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(CHIME_SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            track.write(pcm, 0, pcm.size)
            track.setNotificationMarkerPosition(pcm.size)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack?) {
                    runCatching { t?.release() }
                }

                override fun onPeriodicNotification(t: AudioTrack?) = Unit
            })
            track.play()
        }
    }

    private val chimePcm: ShortArray by lazy { synthesizeChime() }

    private fun synthesizeChime(): ShortArray {
        val noteSamples = CHIME_SAMPLE_RATE * 90 / 1000
        val out = ShortArray(noteSamples * 2)
        val notes = doubleArrayOf(880.0, 1318.5) // A5, E6

        notes.forEachIndexed { noteIndex, frequency ->
            for (i in 0 until noteSamples) {
                val t = i / CHIME_SAMPLE_RATE.toDouble()
                // Exponential decay so it reads as a soft "ping", not a beep.
                val envelope = Math.exp(-6.0 * i / noteSamples)
                val value = sin(2.0 * PI * frequency * t) * envelope * 0.35
                out[noteIndex * noteSamples + i] = (value * Short.MAX_VALUE).toInt().toShort()
            }
        }
        return out
    }

    private companion object {
        const val CHIME_SAMPLE_RATE = 44_100
    }
}
