package com.aeroglyph.app.playback

import com.aeroglyph.app.audio.AcousticBand
import com.aeroglyph.app.audio.AudioDecoder
import com.aeroglyph.app.audio.ChirpDetection
import com.aeroglyph.app.audio.DecodeResult
import com.aeroglyph.app.audio.FrameHeader
import com.aeroglyph.app.audio.ModemConfig
import com.aeroglyph.app.audio.RoomProfile

/** What the receiver is doing right now, for both the UI and for debugging in the field. */
enum class ListenState { IDLE, SIGNAL, LOCKED }

/**
 * The receive state machine: PCM chunks in, decoded frames out.
 *
 * Deliberately free of any Android dependency. [Listener] owns the microphone
 * and does nothing but push chunks in here, which means the part of the system
 * that has historically been hardest to get right can be driven with
 * synthesised audio in an ordinary JVM test instead of only on a phone. Every
 * receive bug this project has hit -- discarding audio before the gate opened,
 * a broadband gate that could not see in-band tones, correlating the wrong
 * band's template -- was invisible to the codec tests and would have been
 * caught immediately here.
 *
 * The design points that matter:
 *
 *  1. **Audio is never discarded on silence.** Capture runs into a continuous
 *     ring buffer; the gate only decides *when to look*, never *what to keep*.
 *     An earlier version cleared its buffer below a threshold and so threw away
 *     the sync chirp -- the very thing it needed -- on every transmission.
 *
 *  2. **Every band is gated separately.** Bands are watched simultaneously so
 *     an unconfigured receiver can hear either one, but their noise levels are
 *     nothing alike: ordinary room noise is far stronger at 10-14.5kHz than at
 *     17-19.5kHz. Comparing raw magnitudes between them therefore always picks
 *     the lower band, whatever is actually being transmitted. Each band gets
 *     its own adaptive floor, and they are compared by how far each has risen
 *     *above its own* background.
 *
 *  3. **The chirp is searched once, in one band.** Correlation is far too
 *     expensive to re-run over a growing buffer several times a second, or to
 *     run for every band on every onset. The gate says which band rose and
 *     roughly when; a single bounded correlation pass turns that into an exact
 *     offset.
 */
class FrameDetector(
    private val sampleRate: Int = ModemConfig.SAMPLE_RATE_HZ,
    private val onResult: (DecodeResult) -> Unit,
) {

    /** Paused while this device is itself transmitting -- a phone cannot decode its own speaker. */
    var muted: Boolean = false

    var state: ListenState = ListenState.IDLE
        private set

    /** Bins of the most excited band, for the spectrum visualiser. */
    var binEnergies: DoubleArray = DoubleArray(ModemConfig.TONE_COUNT)
        private set

    /** Diagnostics for the on-screen SIGNAL / FLOOR readout, from the most excited band. */
    var peakEnergy: Double = 0.0
        private set
    var noiseFloor: Double = 0.0
        private set
    var activeBand: AcousticBand = ModemConfig.DEFAULT_BAND
        private set

    private val ringSamples = ModemConfig.maxFrameSamples(sampleRate) + sampleRate * 2
    private val ring = ShortArray(ringSamples)
    private var totalWritten = 0L

    private val gates = AcousticBand.entries.map { BandGate(it) }
    private val maxChirpSamples = AcousticBand.entries.maxOf { it.chirpSamples(sampleRate) }

    private var onsetAbs = -1L
    private var onsetBand = ModemConfig.DEFAULT_BAND
    private var chirpStartAbs = -1L
    private var lockedBand = ModemConfig.DEFAULT_BAND
    private var lockedHeader: FrameHeader? = null
    private var lockedSymbolRate = ModemConfig.DEFAULT_SYMBOL_RATE_HZ
    private var ignoreBeforeAbs = 0L
    private var lockDeadlineAbs = 0L

    /** Feeds one captured chunk through the machine. */
    fun offer(chunk: ShortArray, length: Int) {
        if (length <= 0) return

        if (muted) {
            // Keep the ring coherent by advancing past our own transmission
            // rather than splicing it into the middle of someone else's frame.
            writeToRing(chunk, length)
            resetDetection()
            ignoreBeforeAbs = totalWritten
            state = ListenState.IDLE
            return
        }

        val chunkStartAbs = totalWritten
        writeToRing(chunk, length)
        measureBands(chunk, length)
        advanceDetection(chunkStartAbs)
    }

    fun reset() {
        resetDetection()
        gates.forEach { it.reset() }
        state = ListenState.IDLE
    }

    // ── Per-band gating ──────────────────────────────────────────────────────

    /**
     * One adaptive noise floor per band.
     *
     * Keeping these separate is not a refinement, it is what makes watching two
     * bands work at all: a single shared floor would be set by whichever band
     * is noisier -- always the lower one -- and a genuine near-ultrasonic
     * transmission, whose bins are far smaller in absolute terms, would never
     * clear it.
     */
    private class BandGate(val band: AcousticBand) {
        var floor = 0.0
        var peak = 0.0
        var bins: DoubleArray = DoubleArray(ModemConfig.TONE_COUNT)
        private var seeded = false

        fun observe(newBins: DoubleArray) {
            bins = newBins
            peak = newBins.max()
            // Seed once, on the first chunk ever seen. Keying this off
            // "floor == 0.0" instead was a trap: in a digitally silent room the
            // floor stays exactly zero, so the seeding branch fired again on
            // the very chunk carrying the transmission, setting floor = peak
            // and guaranteeing the trigger could never fire.
            if (!seeded) {
                seeded = true
                floor = peak
                return
            }
            // Rise slowly, fall quickly: track genuine background level without
            // being dragged up by the very transmissions we want to detect.
            val alpha = if (peak > floor) NOISE_FLOOR_ATTACK else NOISE_FLOOR_DECAY
            floor += alpha * (peak - floor)
        }

        /**
         * How far this band has risen above its own background, which is how
         * bands are compared. Never a raw magnitude: room noise is far stronger
         * at 10-14.5kHz than at 17-19.5kHz, so comparing magnitudes always
         * picks the lower band no matter what is being transmitted.
         *
         * The floor is held at [ABSOLUTE_MIN_PEAK] for this ratio so a silent
         * room -- where the floor is genuinely near zero -- cannot produce an
         * enormous excitation from nothing.
         */
        val excitation: Double get() = peak / maxOf(floor, ABSOLUTE_MIN_PEAK)

        val triggered: Boolean get() = peak >= maxOf(ABSOLUTE_MIN_PEAK, floor * TRIGGER_RATIO)

        fun reset() {
            floor = 0.0
            peak = 0.0
            seeded = false
        }
    }

    private fun measureBands(chunk: ShortArray, length: Int) {
        for (gate in gates) {
            gate.observe(AudioDecoder.binEnergies(chunk, 0, length, sampleRate, gate.band))
        }
        val mostExcited = gates.maxBy { it.excitation }
        binEnergies = mostExcited.bins
        peakEnergy = mostExcited.peak
        noiseFloor = mostExcited.floor
    }

    // ── Detection state machine ──────────────────────────────────────────────

    private fun advanceDetection(chunkStartAbs: Long) {
        if (chirpStartAbs >= 0L) {
            progressLockedFrame()
            return
        }
        if (onsetAbs >= 0L) {
            // Deliberately *not* re-anchored onto a later, stronger rise. That
            // seems attractive -- a noise blip just before a transmission would
            // otherwise own the onset -- but a chirp swells by more than a
            // factor of two as its sweep enters the watched band, so it
            // re-anchors onto the middle of itself, and by the time the level
            // stops climbing the anchor has slid past the chirp start and out
            // of pre-roll reach. Keeping false onsets rare (see TRIGGER_RATIO)
            // is the better answer; a missed onset costs one repetition.
            tryLockOntoChirp()
            return
        }
        if (chunkStartAbs < ignoreBeforeAbs) return

        // Whichever band has risen furthest above its own floor is the one
        // plausibly carrying a transmission -- never simply the loudest.
        val candidate = gates.filter { it.triggered }.maxByOrNull { it.excitation }
        if (candidate != null) {
            onsetAbs = chunkStartAbs
            onsetBand = candidate.band
            state = ListenState.SIGNAL
        } else {
            state = ListenState.IDLE
        }
    }

    private fun tryLockOntoChirp() {
        val windowStart = (onsetAbs - PRE_ROLL_SAMPLES).coerceAtLeast(oldestAvailableAbs())
        val windowEnd = onsetAbs + maxChirpSamples + POST_ONSET_SAMPLES
        if (totalWritten < windowEnd) return // not enough audio yet; wait

        val window = copyRange(windowStart, (windowEnd - windowStart).toInt())
        if (window == null) {
            resetDetection()
            return
        }

        // The gate's band is a hint about *where to look first*, never a
        // commitment. It is derived from which bins rose, which is reliable
        // when the rise really was a transmission and meaningless when it was
        // a stray noise that happened to land just before one -- and in that
        // case the onset timing is still perfectly good, so giving up on the
        // strength of a bad band guess would throw away a frame that is sitting
        // right there in the window. Correlating the hinted band first keeps
        // the common case at one pass; other bands are tried only on failure.
        //
        // The scan runs backwards over the pre-roll only: a chirp can only have
        // *started* at or before the energy rise it caused.
        var found: ChirpDetection? = null
        var foundBand = onsetBand
        for (band in listOf(onsetBand) + AcousticBand.entries.filter { it != onsetBand }) {
            val hit = AudioDecoder.findSyncChirp(
                buffer = window,
                sampleRate = sampleRate,
                searchStride = LIVE_CHIRP_SEARCH_STRIDE,
                maxSearchOffset = (PRE_ROLL_SAMPLES + CHUNK_HINT_SAMPLES).toInt(),
                band = band,
            )
            if (hit != null) {
                found = hit
                foundBand = band
                break
            }
        }
        val detection = found

        if (detection == null) {
            // Energy rise that wasn't us -- a door, a voice, a keyboard. Back
            // off so a noisy room cannot spend every chunk correlating.
            resetDetection()
            ignoreBeforeAbs = totalWritten + FAILED_LOCK_COOLDOWN_SAMPLES
            state = ListenState.IDLE
            return
        }

        lockedBand = foundBand
        activeBand = foundBand
        chirpStartAbs = windowStart + detection.offsetSamples
        lockedHeader = null
        state = ListenState.LOCKED
        lockDeadlineAbs = totalWritten + ModemConfig.maxFrameSamples(sampleRate)
        progressLockedFrame()
    }

    private fun progressLockedFrame() {
        if (totalWritten > lockDeadlineAbs) {
            abandonLock()
            return
        }

        val leadIn = lockedBand.leadInSamples(sampleRate)
        val symbolStartAbs = chirpStartAbs + leadIn

        val header = lockedHeader ?: run {
            val resolved = resolveHeader(symbolStartAbs) ?: return
            if (resolved.header == null) {
                // Chirp was real enough to lock but the header didn't survive:
                // nothing identifiable, so drop it.
                onResult(DecodeResult.CrcFailed)
                abandonLock()
                return
            }
            lockedHeader = resolved.header
            lockedSymbolRate = resolved.symbolRateHz
            resolved.header
        }

        val headerSamples = AudioDecoder.headerSampleCount(lockedSymbolRate, sampleRate)
        val payloadSamples = AudioDecoder.payloadSampleCount(header.payloadLength, lockedSymbolRate, sampleRate)
        val frameEndAbs = symbolStartAbs + headerSamples + payloadSamples
        if (totalWritten < frameEndAbs) return // still arriving

        val frame = copyRange(chirpStartAbs, (frameEndAbs - chirpStartAbs).toInt())
        if (frame == null) {
            abandonLock()
            return
        }

        onResult(
            AudioDecoder.decodeFromSymbolStart(
                buffer = frame,
                symbolStart = leadIn,
                sampleRate = sampleRate,
                symbolRateHz = lockedSymbolRate,
                band = lockedBand,
            ),
        )

        // Skip past what we consumed, *including the end chirp*, so the next
        // repetition is treated as a fresh frame rather than re-decoded from
        // stale audio. Leaving the end chirp in play made it trigger an onset
        // of its own; the correlation then failed (it is a reverse sweep) and
        // started a cooldown that ran into the following repetition.
        resetDetection()
        ignoreBeforeAbs = frameEndAbs + lockedBand.chirpSamples(sampleRate)
        state = ListenState.IDLE
    }

    private class ResolvedHeader(val header: FrameHeader?, val symbolRateHz: Double)

    /**
     * Reads the header, trying each symbol rate the locked band offers.
     *
     * Nothing on the wire announces the sender's profile, so a receiver set to
     * a different one would otherwise decode nothing with no indication why.
     * Twelve symbols is cheap enough to try them all and keep whichever
     * produces a header its own FEC agrees with -- auto-negotiation without a
     * handshake.
     */
    private fun resolveHeader(symbolStartAbs: Long): ResolvedHeader? {
        val rates = RoomProfile.entries
            .filter { it.band == lockedBand }
            .map { it.symbolRateHz }
            .distinct()

        // Every candidate is evaluated and the most confident wins. Taking the
        // first rate whose FEC merely accepts is not good enough: the header is
        // six SECDED codewords, each of which accepts 144 of 256 possible
        // bytes, so noise satisfies the whole block about 3% of the time. Doing
        // that across three candidate rates would burn roughly one lock in ten
        // on a header that was never sent -- discarding a frame that had in
        // fact arrived intact.
        var best: ResolvedHeader? = null
        var bestConfidence = 0.0
        var awaitingAudio = false

        for (rate in rates) {
            val headerSamples = AudioDecoder.headerSampleCount(rate, sampleRate)
            if (totalWritten < symbolStartAbs + headerSamples) {
                awaitingAudio = true
                continue
            }

            val slice = copyRange(symbolStartAbs, headerSamples) ?: continue
            val scored = AudioDecoder.decodeHeaderScored(
                buffer = slice,
                symbolStart = 0,
                sampleRate = sampleRate,
                symbolRateHz = rate,
                band = lockedBand,
            ) ?: continue

            if (scored.confidence >= AudioDecoder.MIN_HEADER_CONFIDENCE && scored.confidence > bestConfidence) {
                bestConfidence = scored.confidence
                best = ResolvedHeader(scored.header, rate)
            }
        }

        // Wait until *every* candidate has had its audio before choosing. A
        // faster rate needs fewer samples, so it always becomes readable first;
        // returning as soon as one is plausible therefore hands the decision to
        // whichever rate is quickest rather than whichever is right, and the
        // fastest rate would win essentially every time.
        if (awaitingAudio) return null
        return best ?: ResolvedHeader(null, lockedSymbolRate)
    }

    private fun abandonLock() {
        // Read the lock position before clearing it, then skip past the chirp
        // so the same failed lock cannot immediately re-trigger.
        val skipTo = (chirpStartAbs.coerceAtLeast(0L) + lockedBand.chirpSamples(sampleRate))
            .coerceAtLeast(totalWritten)
        resetDetection()
        ignoreBeforeAbs = skipTo
        state = ListenState.IDLE
    }

    private fun resetDetection() {
        onsetAbs = -1L
        chirpStartAbs = -1L
        lockedHeader = null
    }

    // ── Ring buffer plumbing ─────────────────────────────────────────────────

    private fun writeToRing(chunk: ShortArray, length: Int) {
        for (i in 0 until length) {
            ring[((totalWritten + i) % ringSamples).toInt()] = chunk[i]
        }
        totalWritten += length
    }

    private fun oldestAvailableAbs(): Long = (totalWritten - ringSamples).coerceAtLeast(0L)

    /** Contiguous copy of an absolute sample range, or null if it has already been overwritten. */
    private fun copyRange(fromAbs: Long, count: Int): ShortArray? {
        if (count <= 0 || count > ringSamples) return null
        if (fromAbs < oldestAvailableAbs() || fromAbs + count > totalWritten) return null
        return ShortArray(count) { ring[((fromAbs + it) % ringSamples).toInt()] }
    }

    companion object {
        /**
         * Audio kept before the trigger so a chirp that began mid-chunk is still
         * complete. Budget: a sweep spends its opening below the lowest watched
         * tone (~33ms), the gate fires only on a chunk boundary (~43ms), and a
         * chirp occupying just the tail of a chunk may not lift that chunk's
         * average enough to trigger until the next one (~43ms more). ~120ms,
         * plus margin.
         */
        const val PRE_ROLL_SAMPLES = 8_000L

        /** Slack after the onset so the correlation window always holds a whole chirp. */
        const val POST_ONSET_SAMPLES = 4_800L

        /** Typical capture chunk, used only to size the backwards search. */
        const val CHUNK_HINT_SAMPLES = 2_048L

        /**
         * Coarse search step. Safe because the narrowest sweep's correlation
         * peak is ~9 samples wide -- see the reasoning in ModemConfig.
         */
        const val LIVE_CHIRP_SEARCH_STRIDE = 4

        /**
         * How far above its own measured background a band must rise to count
         * as an onset.
         *
         * This was dropped to 1.6 to catch faint transmissions, which turned
         * out to be both wrong and harmful. The floor is an asymmetric EMA that
         * rises slowly and falls fast, so it deliberately tracks something near
         * the *minimum* rather than the mean -- which puts ordinary noise at
         * roughly 1.5x the floor all by itself. A 1.6 trigger therefore fired
         * more or less continuously, and since each false onset costs half a
         * second of correlate-and-fail, the receiver spent nearly all its time
         * blind and genuine chirps kept landing in the gaps.
         *
         * Sensitivity was never the right lever here: a distant transmission
         * measures ~10x its band's floor once the correlation is done in-band,
         * so 3.0 keeps weak signals comfortably while leaving noise alone.
         */
        const val TRIGGER_RATIO = 3.0

        /**
         * Backstop for a genuinely silent room, where a purely ratio-based
         * trigger would fire on rounding noise. Deliberately low: microphone
         * response in these bands is far below what the same mic gives at
         * speech frequencies.
         */
        const val ABSOLUTE_MIN_PEAK = 8.0

        /**
         * Quiet period after a correlation finds nothing. With a trigger this
         * sensitive an unlucky room could otherwise start a fresh correlation
         * every chunk and starve capture.
         *
         * Derived from the repeat gap rather than picked: it must be strictly
         * shorter than the silence between repetitions, or one failed lock
         * would reach into the next repetition and suppress it -- turning a
         * single stray noise into a lost message however many times the sender
         * repeated it. Half the gap leaves margin on both sides.
         */
        val FAILED_LOCK_COOLDOWN_SAMPLES =
            ModemConfig.SAMPLE_RATE_HZ * ModemConfig.REPEAT_GAP_MS / 2 / 1_000L

        const val NOISE_FLOOR_ATTACK = 0.02
        const val NOISE_FLOOR_DECAY = 0.20
    }
}
