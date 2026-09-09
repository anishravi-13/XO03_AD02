package com.aeroglyph.app.audio

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
    const val LOW_TONE_HZ = 17_000.0
    const val HIGH_TONE_HZ = 19_500.0

    /** Symbol duration derived from [RoomProfile.symbolRateHz] at runtime; this is the
     *  conservative default used when no profile is specified (matches "Normal"). */
    const val DEFAULT_SYMBOL_RATE_HZ = 45.0

    /** Raised-cosine fade applied at each symbol boundary to tame spectral splatter. */
    const val SYMBOL_FADE_MS = 4.0

    // ── Sync / end chirps ────────────────────────────────────────────────────
    // These sweep *inside* the near-ultrasonic band rather than starting down
    // at 1.8kHz the way the base spec suggests. Two concrete reasons, both
    // found by debugging a receiver that never detected anything:
    //
    //  1. The energy gate that decides "is a transmission happening" measures
    //     our own 16 tone bins. A chirp that spends its first 180ms below
    //     17kHz is invisible to that gate, so the receiver would only wake up
    //     for the last sliver of the chirp -- far too late to correlate
    //     against the full template.
    //  2. Correlation peak width is roughly 1/bandwidth. A 1.8k->19.5k sweep
    //     has a ~2.7-sample-wide peak, so any coarse (strided) search step
    //     large enough to be real-time simply steps straight over it. A 3kHz
    //     sweep widens that peak to ~16 samples, which a stride-4 search
    //     cannot miss. Timing precision is irrelevant here: one symbol is
    //     ~1000 samples wide.
    //
    // It is also, usefully, inaudible -- which the whole design was after.
    const val CHIRP_DURATION_MS = 200.0
    const val SYNC_CHIRP_START_HZ = 16_500.0
    const val SYNC_CHIRP_END_HZ = HIGH_TONE_HZ
    const val END_CHIRP_START_HZ = HIGH_TONE_HZ
    const val END_CHIRP_END_HZ = SYNC_CHIRP_START_HZ

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

    fun toneFrequencyHz(symbol: Int): Double {
        require(symbol in 0 until TONE_COUNT) { "symbol out of range: $symbol" }
        val step = (HIGH_TONE_HZ - LOW_TONE_HZ) / (TONE_COUNT - 1)
        return LOW_TONE_HZ + step * symbol
    }

    /** Samples one symbol occupies at a given symbol rate. */
    fun samplesPerSymbol(symbolRateHz: Double, sampleRate: Int = SAMPLE_RATE_HZ): Int =
        (sampleRate / symbolRateHz).toInt()

    /** Total symbols a frame of [payloadBytes] occupies (header block + payload/CRC block). */
    fun frameSymbolCount(payloadBytes: Int): Int =
        HEADER_BYTES * 4 + (payloadBytes + CRC_BYTES) * 4
}

/**
 * Quick-select presets for the "Advanced" section of BroadcasterScreen. No
 * closed-loop negotiation happens over the air -- the organizer just picks a
 * profile up front based on how noisy/echoey the room is. Swaps symbol rate
 * and repetition count only; the frame format itself never changes.
 */
enum class RoomProfile(val label: String, val symbolRateHz: Double, val repeatCount: Int) {
    QUIET("Quiet", symbolRateHz = 60.0, repeatCount = 3),
    NORMAL("Normal", symbolRateHz = ModemConfig.DEFAULT_SYMBOL_RATE_HZ, repeatCount = ModemConfig.DEFAULT_REPEAT_COUNT),
    NOISY("Noisy", symbolRateHz = 30.0, repeatCount = 6),
}
