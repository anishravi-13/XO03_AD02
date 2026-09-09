package com.aeroglyph.app.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/** Small, shared DSP building blocks used by the encoder, decoder, and chirp detector. */
object DspUtil {

    /**
     * Raised-cosine envelope: 1.0 in the steady middle, easing to 0 across
     * [fadeSamples] at each end of a [totalSamples]-long block. Applied at every
     * symbol boundary so adjacent tones don't click/splatter into each other.
     */
    fun raisedCosineEnvelope(sampleIndex: Int, totalSamples: Int, fadeSamples: Int): Double {
        if (fadeSamples <= 0) return 1.0
        return when {
            sampleIndex < fadeSamples ->
                0.5 * (1 - cos(PI * sampleIndex / fadeSamples))
            sampleIndex >= totalSamples - fadeSamples ->
                0.5 * (1 - cos(PI * (totalSamples - 1 - sampleIndex) / fadeSamples))
            else -> 1.0
        }
    }

    /**
     * Goertzel algorithm: the magnitude of [samples] at [targetHz], without
     * computing a full FFT. O(n) per frequency, which is exactly what we want
     * when we only ever care about [ModemConfig.TONE_COUNT] known bins.
     */
    fun goertzelMagnitude(samples: ShortArray, offset: Int, length: Int, sampleRate: Int, targetHz: Double): Double {
        val k = (0.5 + length * targetHz / sampleRate).toInt()
        val omega = 2.0 * PI * k / length
        val cosine = cos(omega)
        val coeff = 2.0 * cosine

        var q1 = 0.0
        var q2 = 0.0
        for (i in 0 until length) {
            val sample = samples[offset + i].toDouble()
            val q0 = coeff * q1 - q2 + sample
            q2 = q1
            q1 = q0
        }
        val real = q1 - q2 * cosine
        val imag = q2 * kotlin.math.sin(omega)
        return sqrt(real * real + imag * imag) / length
    }

    /** Root-mean-square amplitude of the first [length] samples -- the cheap
     *  gate [Listener] uses to decide whether it's worth running Goertzel at all. */
    fun rms(samples: ShortArray, length: Int): Double {
        if (length <= 0) return 0.0
        var sumSquares = 0.0
        for (i in 0 until length) {
            val s = samples[i].toDouble()
            sumSquares += s * s
        }
        return sqrt(sumSquares / length)
    }
}
