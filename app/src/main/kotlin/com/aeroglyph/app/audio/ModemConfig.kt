package com.aeroglyph.app.audio

/**
 * The carrier band a transmission uses.
 *
 * Aeroglyph started as a purely near-ultrasonic modem, which is silent and
 * works well across a room. It does not work across a hall, and no amount of
 * threshold tuning changes that -- the limit is physical:
 *
 *  - Air absorbs ~0.8-1.0 dB per metre at 19kHz (20C, 50% RH). Over 25m that
 *    is 20-25 dB thrown away *on top of* the ~14 dB of ordinary spreading loss
 *    between 5m and 25m.
 *  - Phone speakers and microphones are typically 20-30 dB down at 19kHz
 *    relative to their midrange. The ultrasonic band sits in the worst corner
 *    of the hardware's response.
 *
 * Getting from "works at 5m" to "works at 25m" needs roughly 35-40 dB, and the
 * only place that much is available is the carrier frequency itself. Around
 * 10-14.5kHz, absorption falls to ~0.25-0.3 dB/m (saving ~15 dB over 25m) and
 * transducer response improves by another 15 dB or so.
 *
 * The price is honesty about the trade: [LONG_RANGE] is **audible**. It is a
 * deliberate second mode rather than a replacement, because "silent" is a real
 * feature that some rooms need and some do not.
 *
 * Bands are distinguished on the wire purely by their sync chirp, whose sweeps
 * are disjoint. A receiver correlates both templates and lets the winner
 * announce the band -- so a receiver never needs configuring, only the sender
 * chooses.
 */
enum class AcousticBand(
    val label: String,
    val lowToneHz: Double,
    val toneSpacingHz: Double,
    val chirpDurationMs: Double,
    val chirpLowHz: Double,
    val chirpHighHz: Double,
    val audible: Boolean,
) {
    /** Silent, ~5-8m in a normal room. */
    ULTRASONIC(
        label = "Ultrasonic",
        lowToneHz = 17_000.0,
        toneSpacingHz = 166.666_666_67,
        chirpDurationMs = 200.0,
        chirpLowHz = 16_500.0,
        chirpHighHz = 19_500.0,
        audible = false,
    ),

    /**
     * Audible, ~25m. Tones are spaced 300Hz apart rather than 167Hz: at long
     * range the direct path is weak relative to the reverberant field, which
     * smears energy between neighbouring bins, and wider spacing buys back
     * discrimination for free (the band is wide enough to afford it).
     *
     * The sweep is both longer and wider than the ultrasonic one, giving the
     * matched filter a time-bandwidth product of ~1275 instead of ~600 -- about
     * 3 dB more processing gain on the detection stage, which is precisely the
     * stage that fails first as signal fades.
     *
     * It is not made longer still only because the live receiver correlates
     * every band's template on each energy rise, and that cost scales with
     * template length: a 400ms sweep measured out at roughly a quarter-second
     * of CPU per onset, which is long enough to start dropping captured audio.
     */
    LONG_RANGE(
        label = "Long range",
        lowToneHz = 10_000.0,
        toneSpacingHz = 300.0,
        chirpDurationMs = 250.0,
        chirpLowHz = 9_500.0,
        chirpHighHz = 14_600.0,
        audible = true,
    );

    val highToneHz: Double get() = lowToneHz + toneSpacingHz * (ModemConfig.TONE_COUNT - 1)

    fun toneFrequencyHz(symbol: Int): Double {
        require(symbol in 0 until ModemConfig.TONE_COUNT) { "symbol out of range: $symbol" }
        return lowToneHz + toneSpacingHz * symbol
    }

    fun chirpSamples(sampleRate: Int = ModemConfig.SAMPLE_RATE_HZ): Int =
        (sampleRate * chirpDurationMs / 1000.0).toInt()

    /** Human-readable span, e.g. "17.0-19.5 kHz". */
    val spanLabel: String
        get() = String.format("%.1f–%.1f kHz", lowToneHz / 1000.0, highToneHz / 1000.0)
}

/**
 * Every constant that both ends of the acoustic modem must agree on to stay
 * interoperable. Nothing in [AudioEncoder] or [AudioDecoder] should hardcode a
 * number that belongs here.
 */
object ModemConfig {
    const val SAMPLE_RATE_HZ = 48_000

    /** 16-FSK: one of 16 tones per symbol, 4 bits/symbol. */
    const val TONE_COUNT = 16
    const val BITS_PER_SYMBOL = 4

    val DEFAULT_BAND = AcousticBand.ULTRASONIC

    /** Retained for UI that describes the default band. */
    val LOW_TONE_HZ = AcousticBand.ULTRASONIC.lowToneHz
    val HIGH_TONE_HZ = AcousticBand.ULTRASONIC.highToneHz

    /** Symbol duration derived from [RoomProfile.symbolRateHz] at runtime; this is the
     *  conservative default used when no profile is specified (matches "Normal"). */
    const val DEFAULT_SYMBOL_RATE_HZ = 45.0

    /** Raised-cosine fade applied at each symbol boundary to tame spectral splatter. */
    const val SYMBOL_FADE_MS = 4.0

    // ── Sync / end chirps ────────────────────────────────────────────────────
    // Chirps sweep *inside* the band they belong to rather than starting down
    // at 1.8kHz the way the base spec suggests. Two concrete reasons, both
    // found by debugging a receiver that never detected anything:
    //
    //  1. The energy gate that decides "is a transmission happening" measures
    //     the tone bins. A chirp that spends its first 180ms below the band is
    //     invisible to that gate, so the receiver would only wake up for the
    //     last sliver of the chirp -- far too late to correlate against the
    //     full template.
    //  2. Correlation peak width is roughly 1/bandwidth. A 1.8k->19.5k sweep
    //     has a ~2.7-sample-wide peak, so any coarse (strided) search step
    //     large enough to be real-time simply steps straight over it. The
    //     narrower sweeps used here widen that peak to 9-16 samples, which a
    //     stride-4 search cannot miss. Timing precision is irrelevant: one
    //     symbol is thousands of samples wide.

    // Frame header layout (see Frame.kt): 3 bytes, always sent as its own
    // FEC-protected block so the receiver learns the payload length before it
    // needs to know the rest of the frame's size.
    const val HEADER_BYTES = 3
    const val CRC_BYTES = 4
    const val MAX_PAYLOAD_BYTES = 200

    const val DEFAULT_REPEAT_COUNT = 4
    const val REPEAT_GAP_MS = 300L

    /** Echo Relay defaults. */
    const val DEFAULT_RELAY_TTL = 2
    const val RELAY_JITTER_MIN_MS = 400L
    const val RELAY_JITTER_MAX_MS = 900L

    // ── Repair + catch-up (surprise challenges 1 and 2) ──────────────────────
    // Both features are answered by the same gossip rule: whoever holds the
    // message answers a request, after a random wait, and stays quiet if
    // somebody else answers first. Jitter windows are wider than the relay's
    // because several devices may want to answer the same request at once.
    const val REPAIR_JITTER_MIN_MS = 500L
    const val REPAIR_JITTER_MAX_MS = 1_800L

    /** Session ID 0 in a REQUEST means "whatever the latest message is". */
    const val SESSION_ID_LATEST = 0

    /** A newly-arrived listener asks this many times before falling back to waiting for a beacon. */
    const val CATCH_UP_ATTEMPTS = 3
    const val CATCH_UP_RETRY_MS = 6_000L

    /** How often a device holding a message announces that it has one. */
    const val BEACON_INTERVAL_MS = 20_000L

    fun toneFrequencyHz(symbol: Int, band: AcousticBand = DEFAULT_BAND): Double =
        band.toneFrequencyHz(symbol)

    /** Samples one symbol occupies at a given symbol rate. */
    fun samplesPerSymbol(symbolRateHz: Double, sampleRate: Int = SAMPLE_RATE_HZ): Int =
        (sampleRate / symbolRateHz).toInt()

    /** Total symbols a frame of [payloadBytes] occupies (header block + payload/CRC block). */
    fun frameSymbolCount(payloadBytes: Int): Int =
        HEADER_BYTES * 4 + (payloadBytes + CRC_BYTES) * 4

    /**
     * Worst-case samples one complete frame can occupy: the largest payload, at
     * the slowest profile's symbol rate, in the band with the longest chirps.
     *
     * Derived rather than hardcoded because the receiver's ring buffer must be
     * able to hold a whole frame -- and a slower long-range profile silently
     * outgrowing a fixed buffer would show up as frames that decode at short
     * range and vanish at long range, which is a miserable thing to debug.
     */
    fun maxFrameSamples(sampleRate: Int = SAMPLE_RATE_HZ): Int {
        val slowestRate = RoomProfile.entries.minOf { it.symbolRateHz }
        val longestChirp = AcousticBand.entries.maxOf { it.chirpSamples(sampleRate) }
        return frameSymbolCount(MAX_PAYLOAD_BYTES) * samplesPerSymbol(slowestRate, sampleRate) +
            2 * longestChirp
    }
}

/**
 * Quick-select presets for the "Advanced" section of BroadcasterScreen. No
 * closed-loop negotiation happens over the air -- the organizer picks a profile
 * up front, and the receiver works out both the band and the symbol rate for
 * itself, so only the sending device needs configuring.
 */
enum class RoomProfile(
    val label: String,
    val symbolRateHz: Double,
    val repeatCount: Int,
    val band: AcousticBand = AcousticBand.ULTRASONIC,
    val rangeLabel: String,
) {
    QUIET("Quiet", symbolRateHz = 60.0, repeatCount = 3, rangeLabel = "~8 m · silent"),
    NORMAL(
        "Normal",
        symbolRateHz = ModemConfig.DEFAULT_SYMBOL_RATE_HZ,
        repeatCount = ModemConfig.DEFAULT_REPEAT_COUNT,
        rangeLabel = "~6 m · silent",
    ),
    NOISY("Noisy", symbolRateHz = 30.0, repeatCount = 6, rangeLabel = "~5 m · silent"),

    /**
     * Slower symbols on top of the lower band. Each symbol is 3x longer than
     * "Normal", and Goertzel's output SNR grows with integration length, so
     * this is worth about another 4.8 dB beyond what the band change already
     * buys. Repetition stays high because a transient (a door, a cough) is far
     * more likely to eat a whole symbol when symbols are this long.
     */
    LONG_RANGE(
        "Long range",
        symbolRateHz = 15.0,
        repeatCount = 4,
        band = AcousticBand.LONG_RANGE,
        rangeLabel = "~25 m · audible",
    ),
}
