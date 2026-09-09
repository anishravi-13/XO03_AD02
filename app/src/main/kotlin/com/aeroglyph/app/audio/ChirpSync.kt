package com.aeroglyph.app.audio

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/** The result of scanning a buffer for a chirp: where it starts, and how confident we are. */
data class ChirpDetection(val offsetSamples: Int, val score: Double)

/**
 * Generates and detects the sync/end chirps that bracket every frame.
 *
 * A chirp is used (rather than a fixed tone) specifically because a linear
 * sweep has a very distinctive, low-ambiguity cross-correlation peak against
 * steady-state room noise or music -- exactly what we need to reliably find
 * "a transmission is starting" before we've locked onto the symbol clock.
 *
 * Each [AcousticBand] sweeps its own disjoint frequency range, so the chirp
 * doubles as the band announcement: whichever template correlates best tells
 * the receiver which band the rest of the frame is in. That keeps the receiver
 * configuration-free -- only the sender picks a profile.
 */
object ChirpSync {

    fun generateSyncChirp(
        sampleRate: Int = ModemConfig.SAMPLE_RATE_HZ,
        band: AcousticBand = ModemConfig.DEFAULT_BAND,
    ): ShortArray = generateChirp(band.chirpLowHz, band.chirpHighHz, band.chirpDurationMs, sampleRate)

    fun generateEndChirp(
        sampleRate: Int = ModemConfig.SAMPLE_RATE_HZ,
        band: AcousticBand = ModemConfig.DEFAULT_BAND,
    ): ShortArray = generateChirp(band.chirpHighHz, band.chirpLowHz, band.chirpDurationMs, sampleRate)

    private fun generateChirp(startHz: Double, endHz: Double, durationMs: Double, sampleRate: Int): ShortArray {
        val n = (sampleRate * durationMs / 1000.0).toInt()
        val durationSec = n / sampleRate.toDouble()
        val rateHzPerSec = (endHz - startHz) / durationSec
        val fadeSamples = (sampleRate * ModemConfig.SYMBOL_FADE_MS / 1000.0).toInt()

        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i / sampleRate.toDouble()
            // Instantaneous phase of a linear sweep: startHz*t + 0.5*rate*t^2
            val phase = 2.0 * PI * (startHz * t + 0.5 * rateHzPerSec * t * t)
            val envelope = DspUtil.raisedCosineEnvelope(i, n, fadeSamples)
            out[i] = (sin(phase) * Short.MAX_VALUE * envelope).toInt().toShort()
        }
        return out
    }

    /**
     * Scans the start of [buffer] for the best alignment of [template] via
     * normalized cross-correlation. Returns null if the best match scores
     * below [threshold] (score is in [-1, 1]; 1.0 is a perfect match).
     *
     * The search is deliberately bounded to the first [maxSearchOffset]
     * samples rather than the whole buffer: a chirp has a very narrow
     * autocorrelation peak (that's what makes it a good sync marker), so
     * there's no cheap way to skip-scan for it -- every offset in the search
     * range must be checked. Scanning an entire multi-second recording at
     * that resolution is not real-time-tractable, and it's also the wrong
     * job for this function: [com.aeroglyph.app.playback.Listener] is expected
     * to keep feeding this a short rolling window of *recent* audio, so a
     * not-yet-consumed chirp is always near the front of whatever buffer it
     * hands over. The default (3 template lengths) comfortably covers that
     * case with margin for buffering jitter.
     *
     * [searchStride] trades alignment precision for speed by checking every
     * Nth offset. Landing a few samples off the true peak is harmless here:
     * each symbol window is thousands of samples wide, so a handful of samples
     * of misalignment is a fraction of a percent of one symbol. Tests use
     * stride 1; the live listener uses a coarser stride to stay real-time.
     */
    fun findChirp(
        buffer: ShortArray,
        template: ShortArray,
        threshold: Double = 0.6,
        maxSearchOffset: Int = template.size * 3,
        searchStride: Int = 1,
    ): ChirpDetection? {
        if (buffer.size < template.size) return null

        val templateEnergy = template.sumOf { it.toDouble() * it.toDouble() }
        if (templateEnergy <= 0.0) return null

        val lastOffset = minOf(maxSearchOffset, buffer.size - template.size)
        val stride = searchStride.coerceAtLeast(1)
        var bestOffset = -1
        var bestScore = -1.0
        var offset = 0
        while (offset <= lastOffset) {
            var dot = 0.0
            var bufEnergy = 0.0
            for (j in template.indices) {
                val s = buffer[offset + j].toDouble()
                dot += s * template[j]
                bufEnergy += s * s
            }
            if (bufEnergy > 0.0) {
                val score = dot / sqrt(bufEnergy * templateEnergy)
                if (score > bestScore) {
                    bestScore = score
                    bestOffset = offset
                }
            }
            offset += stride
        }
        return if (bestOffset >= 0 && bestScore >= threshold) ChirpDetection(bestOffset, bestScore) else null
    }
}
