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

    // Sync / end chirps -- a linear sweep is easy to generate and to detect via
    // cross-correlation against a reference template, and its energy-onset is
    // distinctive against steady-state noise.
    const val CHIRP_DURATION_MS = 200.0
    const val SYNC_CHIRP_START_HZ = 1_800.0
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

    /** Echo Relay defaults (see SessionManager / RelayEngine). */
    const val DEFAULT_RELAY_TTL = 2
    const val RELAY_JITTER_MIN_MS = 400L
    const val RELAY_JITTER_MAX_MS = 900L

    fun toneFrequencyHz(symbol: Int): Double {
        require(symbol in 0 until TONE_COUNT) { "symbol out of range: $symbol" }
        val step = (HIGH_TONE_HZ - LOW_TONE_HZ) / (TONE_COUNT - 1)
        return LOW_TONE_HZ + step * symbol
    }
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
