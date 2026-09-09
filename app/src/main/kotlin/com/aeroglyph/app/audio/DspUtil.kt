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

    /**
     * Band-passes [samples] around [centerHz] and rescales the result to use
     * the full sample range.
     *
     * This exists for one reason: the chirp detector scores candidates with
     * *normalised* cross-correlation, whose denominator is the total energy of
     * the window -- all of it, at every frequency. A real room puts enormous
     * energy at 50-500Hz (traffic, ventilation, voices, footsteps), typically
     * 30-40 dB above anything in our carrier bands, and none of it can possibly
     * correlate with a chirp. It simply inflates the denominator and crushes
     * the score of a signal that is otherwise perfectly clean: measured, a
     * distant transmission scored 1.00 in silence, 0.26 against mild rumble and
     * 0.02 against realistic room rumble, so detection failed on room acoustics
     * rather than on anything to do with the signal.
     *
     * Filtering the window *and* the template identically leaves the matched
     * filter matched -- the correlation peak stays exactly where it was -- while
     * removing the out-of-band energy from the denominator. Rescaling afterwards
     * is safe because normalised cross-correlation is invariant to independent
     * scaling of either argument.
     *
     * Two cascaded biquads give ~12 dB/octave either side of the passband,
     * which is ~70 dB of rejection down at 180Hz.
     */
    fun bandPassNormalized(
        samples: ShortArray,
        sampleRate: Int,
        centerHz: Double,
        bandwidthHz: Double,
        stages: Int = 2,
    ): ShortArray {
        if (samples.isEmpty()) return ShortArray(0)

        // Each stage is widened so that after cascading, the combined -3dB
        // width is still roughly the chirp's own sweep -- a filter narrower
        // than the signal would throw away the ends of the sweep, which is
        // where a chirp's timing resolution comes from.
        val q = centerHz * COMBINED_WIDTH_FACTOR / bandwidthHz

        var buffer = DoubleArray(samples.size) { samples[it].toDouble() }
        repeat(stages) { buffer = biquadBandPass(buffer, sampleRate, centerHz, q) }

        var peak = 0.0
        for (v in buffer) {
            val a = if (v < 0) -v else v
            if (a > peak) peak = a
        }
        if (peak <= 0.0) return ShortArray(samples.size)

        val scale = NORMALIZED_PEAK / peak
        return ShortArray(samples.size) { (buffer[it] * scale).toInt().toShort() }
    }

    /** One RBJ constant-peak-gain band-pass section, transposed direct form II. */
    private fun biquadBandPass(input: DoubleArray, sampleRate: Int, centerHz: Double, q: Double): DoubleArray {
        val w0 = 2.0 * PI * centerHz / sampleRate
        val cosW0 = cos(w0)
        val alpha = kotlin.math.sin(w0) / (2.0 * q)

        val a0 = 1.0 + alpha
        val b0 = alpha / a0
        val b1 = 0.0
        val b2 = -alpha / a0
        val a1 = -2.0 * cosW0 / a0
        val a2 = (1.0 - alpha) / a0

        val out = DoubleArray(input.size)
        var z1 = 0.0
        var z2 = 0.0
        for (i in input.indices) {
            val x = input[i]
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            out[i] = y
        }
        return out
    }

    /**
     * Cascading two identical band-pass sections narrows the combined -3dB
     * width to roughly 0.64 of one section's, so each section is widened by
     * this factor to land back on the intended width.
     */
    private const val COMBINED_WIDTH_FACTOR = 0.64
    private const val NORMALIZED_PEAK = 30_000.0

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
