package com.aeroglyph.app.playback

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.annotation.RequiresPermission
import com.aeroglyph.app.audio.AcousticBand
import com.aeroglyph.app.audio.AudioDecoder
import com.aeroglyph.app.audio.ChirpSync
import com.aeroglyph.app.audio.DecodeResult
import com.aeroglyph.app.audio.FrameHeader
import com.aeroglyph.app.audio.ModemConfig
import com.aeroglyph.app.audio.RoomProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the receiver is doing right now, for both the UI and for debugging in the field. */
enum class ListenState { IDLE, SIGNAL, LOCKED }

/**
 * Continuously captures microphone PCM and pulls frames out of it.
 *
 * Deliberately dumb about *meaning*: it reports every frame it decodes,
 * including repeats. Deduping, relaying, repair and catch-up are all
 * decisions made above this layer.
 *
 * The shape of this class is driven by four things that broke naive versions
 * of it on real hardware:
 *
 *  1. **Audio is never discarded on silence.** An earlier version cleared its
 *     buffer whenever input dropped below a threshold, which meant the
 *     beginning of every transmission -- the sync chirp itself -- was thrown
 *     away right up until the moment the gate opened. Capture now runs into a
 *     continuous ring buffer and the gate only decides *when to look*, never
 *     *what to keep*.
 *
 *  2. **The threshold adapts.** Microphone gain varies enormously between
 *     devices, and UNPROCESSED deliberately disables the automatic gain
 *     control that would otherwise paper over that. Any fixed magic number is
 *     wrong on most phones, so the floor tracks measured background noise and
 *     triggers on a rise above it.
 *
 *  3. **The chirp is searched once, not repeatedly.** Correlation is far too
 *     expensive to re-run over a growing buffer several times a second. An
 *     energy rise gives a coarse time anchor; one correlation pass around
 *     that anchor locks the exact offset; everything after that is bookkeeping.
 *
 *  4. **Both bands are watched at once.** The receiver is never configured --
 *     the sender picks a profile, and which band it chose is discovered by
 *     correlating both chirp templates. That costs one extra correlation pass
 *     per onset, which is why the search window is kept tight (see
 *     [PRE_ROLL_SAMPLES]).
 */
class Listener {

    private val _events = MutableSharedFlow<DecodeResult>(extraBufferCapacity = 16)
    val events: SharedFlow<DecodeResult> = _events.asSharedFlow()

    /** Live per-bin Goertzel magnitudes for whichever band is currently loudest -- drives the spectrum visualizer. */
    private val _binEnergies = MutableStateFlow(DoubleArray(ModemConfig.TONE_COUNT))
    val binEnergies: StateFlow<DoubleArray> = _binEnergies.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _state = MutableStateFlow(ListenState.IDLE)
    val state: StateFlow<ListenState> = _state.asStateFlow()

    /** Strongest bin magnitude across both bands in the latest chunk. Surfaced so signal strength is visible, not just a boolean. */
    private val _peakBandEnergy = MutableStateFlow(0.0)
    val peakBandEnergy: StateFlow<Double> = _peakBandEnergy.asStateFlow()

    /** The adaptive background level the trigger is measured against. */
    private val _noiseFloor = MutableStateFlow(0.0)
    val noiseFloor: StateFlow<Double> = _noiseFloor.asStateFlow()

    /** Name of the audio source we actually managed to open, for the diagnostics readout. */
    private val _sourceName = MutableStateFlow("")
    val sourceName: StateFlow<String> = _sourceName.asStateFlow()

    /** Which band the last lock was on, so the UI can say whether it heard a silent or long-range transmission. */
    private val _activeBand = MutableStateFlow(ModemConfig.DEFAULT_BAND)
    val activeBand: StateFlow<AcousticBand> = _activeBand.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var job: Job? = null
    private var audioRecord: AudioRecord? = null

    /**
     * Paused rather than stopped while this device is itself transmitting --
     * a phone cannot usefully decode its own speaker output.
     */
    @Volatile
    var muted: Boolean = false

    // ── Ring buffer ──────────────────────────────────────────────────────────
    private val ring = ShortArray(RING_SAMPLES)
    private var totalWritten = 0L

    // ── Detection state ──────────────────────────────────────────────────────
    private var onsetAbs = -1L
    private var onsetBand = ModemConfig.DEFAULT_BAND
    private var loudestBand = ModemConfig.DEFAULT_BAND
    private var chirpStartAbs = -1L
    private var lockedBand = ModemConfig.DEFAULT_BAND
    private var lockedHeader: FrameHeader? = null
    private var lockedSymbolRate = ModemConfig.DEFAULT_SYMBOL_RATE_HZ
    private var ignoreBeforeAbs = 0L
    private var lockDeadlineAbs = 0L

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        _error.value = null
        job = scope.launch(Dispatchers.Default) { captureLoop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        releaseRecord()
        _isListening.value = false
        _state.value = ListenState.IDLE
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private suspend fun captureLoop() {
        val opened = openAudioRecord()
        if (opened == null) {
            _error.value = "Couldn't open the microphone. Another app may be using it."
            return
        }
        val (record, sourceLabel) = opened
        audioRecord = record
        _sourceName.value = sourceLabel

        val chunk = ShortArray(CHUNK_SAMPLES)
        resetDetection()

        try {
            record.startRecording()
            _isListening.value = true

            while (currentCoroutineContext().isActive) {
                val read = record.read(chunk, 0, chunk.size)
                if (read <= 0) continue

                if (muted) {
                    // Our own speaker is busy. Keep the ring coherent by
                    // advancing past this audio rather than splicing our own
                    // transmission into the middle of someone else's frame.
                    writeToRing(chunk, read)
                    resetDetection()
                    ignoreBeforeAbs = totalWritten
                    _state.value = ListenState.IDLE
                    continue
                }

                val chunkStartAbs = totalWritten
                writeToRing(chunk, read)

                val peak = measureBands(chunk, read)
                _peakBandEnergy.value = peak

                updateNoiseFloor(peak)
                advanceDetection(peak, chunkStartAbs)
            }
        } catch (t: Throwable) {
            if (t !is CancellationException) {
                _error.value = "Microphone capture stopped unexpectedly."
            }
            throw t
        } finally {
            releaseRecord()
            _isListening.value = false
            _state.value = ListenState.IDLE
        }
    }

    /**
     * Measures every band's tone bins and returns the strongest magnitude seen
     * anywhere, publishing the winning band's bins for the visualizer and
     * remembering which band that was.
     *
     * Watching both bands is what lets an unconfigured receiver hear a
     * long-range broadcast: a gate that only measured the ultrasonic bins would
     * sit at the noise floor throughout a perfectly strong 10-14.5kHz
     * transmission and never once look for a chirp.
     *
     * Recording the winner is what keeps that affordable -- see [loudestBand].
     */
    private fun measureBands(chunk: ShortArray, length: Int): Double {
        var bestPeak = -1.0
        var bestBins: DoubleArray? = null
        var bestBand = loudestBand
        for (band in AcousticBand.entries) {
            val bins = AudioDecoder.binEnergies(chunk, 0, length, band = band)
            val peak = bins.max()
            if (peak > bestPeak) {
                bestPeak = peak
                bestBins = bins
                bestBand = band
            }
        }
        bestBins?.let { _binEnergies.value = it }
        loudestBand = bestBand
        return bestPeak.coerceAtLeast(0.0)
    }

    // ── Detection state machine ──────────────────────────────────────────────

    private fun advanceDetection(peak: Double, chunkStartAbs: Long) {
        if (chirpStartAbs >= 0L) {
            progressLockedFrame()
            return
        }

        if (onsetAbs >= 0L) {
            tryLockOntoChirp()
            return
        }

        if (chunkStartAbs < ignoreBeforeAbs) return

        if (peak >= triggerLevel()) {
            onsetAbs = chunkStartAbs
            onsetBand = loudestBand
            _state.value = ListenState.SIGNAL
        } else {
            _state.value = ListenState.IDLE
        }
    }

    /**
     * One correlation pass per band, in a window anchored on the energy rise.
     *
     * The window starts a little *before* the onset because the gate can only
     * fire on a chunk boundary, and because a chirp's opening sweep is briefly
     * below the lowest watched tone. It is kept deliberately tight all the
     * same: correlation cost is (window - template) * template per band, so a
     * generous window is what turns this from a few tens of milliseconds into
     * something that drops audio.
     */
    private fun tryLockOntoChirp() {
        val windowStart = (onsetAbs - PRE_ROLL_SAMPLES).coerceAtLeast(oldestAvailableAbs())
        val windowEnd = onsetAbs + MAX_CHIRP_SAMPLES + POST_ONSET_SAMPLES
        if (totalWritten < windowEnd) return // not enough audio yet; wait

        val window = copyRange(windowStart, (windowEnd - windowStart).toInt())
        if (window == null) {
            resetDetection()
            return
        }

        // Only the band whose bins actually rose is worth correlating. The gate
        // has already measured both, and a transmission puts its energy in the
        // band it is using -- so trying the other template as well would double
        // the cost of every onset to re-answer a question we can already
        // answer. That matters: measured, a two-template search over the full
        // window ran ~70ms on a desktop JVM, which on phone silicon is longer
        // than the microphone buffer holds, so a false onset would drop
        // captured audio rather than merely waste a little CPU.
        //
        // The search range is bounded for the same reason. A chirp can only
        // have *started* at or before the energy rise it caused, so the scan
        // runs back over the pre-roll plus a chunk of gate latency -- never
        // forward over the whole window.
        val detection = AudioDecoder.findSyncChirp(
            buffer = window,
            searchStride = LIVE_CHIRP_SEARCH_STRIDE,
            maxSearchOffset = (PRE_ROLL_SAMPLES + CHUNK_SAMPLES).toInt(),
            band = onsetBand,
        )

        if (detection == null) {
            // Energy rise that wasn't us -- a door, a voice, a keyboard. Back
            // off briefly so a noisy room cannot spend every chunk correlating:
            // the trigger is deliberately sensitive, so false onsets are
            // expected and must stay cheap.
            resetDetection()
            ignoreBeforeAbs = totalWritten + FAILED_LOCK_COOLDOWN_SAMPLES
            _state.value = ListenState.IDLE
            return
        }

        lockedBand = onsetBand
        _activeBand.value = onsetBand
        chirpStartAbs = windowStart + detection.offsetSamples
        lockedHeader = null
        _state.value = ListenState.LOCKED
        // Give up on a lock that never completes (a truncated or spoofed chirp).
        lockDeadlineAbs = totalWritten + ModemConfig.maxFrameSamples()
        progressLockedFrame()
    }

    private fun progressLockedFrame() {
        if (totalWritten > lockDeadlineAbs) {
            abandonLock()
            return
        }

        val chirpLength = lockedBand.chirpSamples()
        val symbolStartAbs = chirpStartAbs + chirpLength

        val header = lockedHeader ?: run {
            val resolved = resolveHeader(symbolStartAbs) ?: return
            if (resolved.header == null) {
                // Chirp was real enough to lock but the header didn't survive:
                // nothing identifiable, so drop it silently.
                _events.tryEmit(DecodeResult.CrcFailed)
                abandonLock()
                return
            }
            lockedHeader = resolved.header
            lockedSymbolRate = resolved.symbolRateHz
            resolved.header
        }

        val headerSamples = AudioDecoder.headerSampleCount(lockedSymbolRate)
        val payloadSamples = AudioDecoder.payloadSampleCount(header.payloadLength, lockedSymbolRate)
        val frameEndAbs = symbolStartAbs + headerSamples + payloadSamples
        if (totalWritten < frameEndAbs) return // still arriving

        val frameStart = chirpStartAbs
        val frame = copyRange(frameStart, (frameEndAbs - frameStart).toInt())
        if (frame == null) {
            abandonLock()
            return
        }

        val result = AudioDecoder.decodeFromSymbolStart(
            buffer = frame,
            symbolStart = chirpLength,
            symbolRateHz = lockedSymbolRate,
            band = lockedBand,
        )
        _events.tryEmit(result)

        // Skip past what we just consumed either way, so the next repetition
        // is treated as a fresh frame rather than re-decoded from stale audio.
        resetDetection()
        ignoreBeforeAbs = frameEndAbs
        _state.value = ListenState.IDLE
    }

    private class ResolvedHeader(val header: FrameHeader?, val symbolRateHz: Double)

    /**
     * Reads the header, trying each room profile's symbol rate in turn.
     *
     * The sender's profile is not announced anywhere on the wire, and a
     * receiver set to a different profile than the sender would otherwise
     * decode nothing at all with no indication why. Twelve symbols is cheap
     * enough to simply try them all and keep whichever produces a header its
     * own FEC agrees with -- which makes the profiles auto-negotiating without
     * adding a handshake the brief forbids.
     *
     * Rates are tried fastest-first so the common case settles quickly, and
     * rates belonging to a different band than the one we locked are skipped
     * outright: the chirp already told us the band, and trying a mismatched
     * pairing can only produce a false header.
     */
    private fun resolveHeader(symbolStartAbs: Long): ResolvedHeader? {
        val candidateRates = buildList {
            if (RoomProfile.entries.any { it.band == lockedBand && it.symbolRateHz == lockedSymbolRate }) {
                add(lockedSymbolRate)
            }
            RoomProfile.entries
                .filter { it.band == lockedBand }
                .sortedByDescending { it.symbolRateHz }
                .forEach { if (it.symbolRateHz !in this) add(it.symbolRateHz) }
        }

        var sawEnoughAudio = false
        for (rate in candidateRates) {
            val headerSamples = AudioDecoder.headerSampleCount(rate)
            if (totalWritten < symbolStartAbs + headerSamples) continue
            sawEnoughAudio = true

            val slice = copyRange(symbolStartAbs, headerSamples) ?: continue
            val header = AudioDecoder.decodeHeader(slice, symbolStart = 0, symbolRateHz = rate, band = lockedBand)
            if (header != null) return ResolvedHeader(header, rate)
        }

        // Nothing decoded yet. If even the slowest profile's header hasn't
        // arrived, wait; otherwise this really is an unrecoverable header.
        return if (sawEnoughAudio) ResolvedHeader(null, lockedSymbolRate) else null
    }

    private fun abandonLock() {
        // Read the lock position before clearing it, then skip past the chirp
        // so the same failed lock cannot immediately re-trigger.
        val chirpLength = lockedBand.chirpSamples()
        val skipTo = (chirpStartAbs.coerceAtLeast(0L) + chirpLength).coerceAtLeast(totalWritten)
        resetDetection()
        ignoreBeforeAbs = skipTo
        _state.value = ListenState.IDLE
    }

    private fun resetDetection() {
        onsetAbs = -1L
        chirpStartAbs = -1L
        lockedHeader = null
    }

    // ── Adaptive gate ────────────────────────────────────────────────────────

    /**
     * The level a chunk must reach to be treated as the start of something.
     * Both terms matter: the ratio catches a rise above whatever this room and
     * this microphone happen to sit at, while the absolute floor stops a
     * perfectly silent room (noise floor near zero) from triggering on
     * nothing at all.
     */
    private fun triggerLevel(): Double =
        maxOf(ABSOLUTE_MIN_PEAK, _noiseFloor.value * TRIGGER_RATIO)

    private fun updateNoiseFloor(peak: Double) {
        val current = _noiseFloor.value
        if (current == 0.0) {
            _noiseFloor.value = peak
            return
        }
        // Rise slowly, fall quickly: we want the floor to track genuine
        // background level without being dragged up by the very transmissions
        // we are trying to detect.
        val alpha = if (peak > current) NOISE_FLOOR_ATTACK else NOISE_FLOOR_DECAY
        _noiseFloor.value = current + alpha * (peak - current)
    }

    // ── Ring buffer plumbing ─────────────────────────────────────────────────

    private fun writeToRing(chunk: ShortArray, length: Int) {
        for (i in 0 until length) {
            ring[((totalWritten + i) % RING_SAMPLES).toInt()] = chunk[i]
        }
        totalWritten += length
    }

    private fun oldestAvailableAbs(): Long = (totalWritten - RING_SAMPLES).coerceAtLeast(0L)

    /** Contiguous copy of an absolute sample range, or null if it has already been overwritten. */
    private fun copyRange(fromAbs: Long, count: Int): ShortArray? {
        if (count <= 0 || count > RING_SAMPLES) return null
        if (fromAbs < oldestAvailableAbs() || fromAbs + count > totalWritten) return null
        val out = ShortArray(count)
        for (i in 0 until count) {
            out[i] = ring[((fromAbs + i) % RING_SAMPLES).toInt()]
        }
        return out
    }

    // ── Audio source ─────────────────────────────────────────────────────────

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun openAudioRecord(): Pair<AudioRecord, String>? {
        val minBufferBytes = AudioRecord.getMinBufferSize(
            ModemConfig.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferBytes <= 0) return null
        // Half a second of slack, well beyond the platform minimum. The chirp
        // correlation runs on this same loop and takes on the order of a
        // hundred milliseconds on phone silicon; anything the driver buffers
        // meanwhile is read afterwards and lands in the ring intact. Capture
        // latency costs nothing here because every later stage addresses audio
        // by absolute sample position, never by "now".
        val bufferBytes = maxOf(minBufferBytes * 4, CAPTURE_BUFFER_BYTES)

        // UNPROCESSED hands us raw samples with the platform's noise
        // suppression and AGC out of the way. Those are tuned for speech and
        // actively destroy near-ultrasonic tones -- but not every device
        // implements the source, so fall down a chain.
        val sources = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                add(MediaRecorder.AudioSource.UNPROCESSED to "UNPROCESSED")
            }
            add(MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION")
            add(MediaRecorder.AudioSource.MIC to "MIC")
        }

        for ((source, label) in sources) {
            val record = runCatching {
                AudioRecord(
                    source,
                    ModemConfig.SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes,
                )
            }.getOrNull()

            if (record != null && record.state == AudioRecord.STATE_INITIALIZED) return record to label
            runCatching { record?.release() }
        }
        return null
    }

    private fun releaseRecord() {
        audioRecord?.let { record ->
            runCatching { if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop() }
            runCatching { record.release() }
        }
        audioRecord = null
    }

    private companion object {
        const val CHUNK_SAMPLES = 2048

        /** 0.5s of mono PCM16 -- the headroom that lets a slow correlation never cost us audio. */
        const val CAPTURE_BUFFER_BYTES = ModemConfig.SAMPLE_RATE_HZ * 2 / 2

        /** Longest chirp any band uses, which bounds the correlation window. */
        val MAX_CHIRP_SAMPLES = AcousticBand.entries.maxOf { it.chirpSamples() }

        /**
         * Sized from the wire format rather than a round number of seconds: it
         * must hold the largest frame the slowest profile can produce, or
         * long-range messages would decode near the sender and silently vanish
         * further away as the tail of the frame got overwritten mid-decode.
         */
        val RING_SAMPLES = ModemConfig.maxFrameSamples() + ModemConfig.SAMPLE_RATE_HZ * 2

        /**
         * Audio kept before the trigger, so a chirp that began mid-chunk is
         * still complete -- and no more than that, because every sample here is
         * multiplied by the template length in the correlation cost.
         *
         * Budget: a sweep spends its opening below the lowest watched tone
         * (33ms at worst, for the ultrasonic band's 16.5k start against a 17k
         * bin), the gate can only fire on a chunk boundary (43ms), and one more
         * chunk covers buffering jitter. ~120ms, so 6000 samples at 48kHz.
         */
        const val PRE_ROLL_SAMPLES = 6_000L

        /** Extra slack after the onset so the correlation window always contains a whole chirp. */
        const val POST_ONSET_SAMPLES = 4_800L

        /**
         * Coarse search step. Safe because the narrowest sweep's correlation
         * peak is ~9 samples wide -- see the reasoning in ModemConfig.
         */
        const val LIVE_CHIRP_SEARCH_STRIDE = 4

        /**
         * How far above the measured background a chunk must sit to count as
         * an onset. Lowered from 3.0: at 25m a genuine transmission arrives
         * only slightly above the room, and a ratio tuned for across-the-table
         * range simply never fires. False onsets are absorbed by
         * [FAILED_LOCK_COOLDOWN_SAMPLES].
         */
        const val TRIGGER_RATIO = 1.6

        /**
         * Backstop for a genuinely silent room, where a purely ratio-based
         * trigger would fire on rounding noise. Deliberately low: microphone
         * response in these bands is far below what the same mic gives at
         * speech frequencies, so a real transmission across a hall is a much
         * smaller number than intuition suggests.
         */
        const val ABSOLUTE_MIN_PEAK = 8.0

        /**
         * Quiet period after a correlation finds nothing. With a trigger this
         * sensitive, an unlucky room could otherwise start a fresh correlation
         * on every chunk and starve the capture loop. Missing a repetition is
         * cheap; dropping microphone audio is not.
         */
        const val FAILED_LOCK_COOLDOWN_SAMPLES = ModemConfig.SAMPLE_RATE_HZ * 2L / 5L

        const val NOISE_FLOOR_ATTACK = 0.02
        const val NOISE_FLOOR_DECAY = 0.20
    }
}
