package com.aeroglyph.app.playback

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresPermission
import com.aeroglyph.app.audio.AudioDecoder
import com.aeroglyph.app.audio.DecodeResult
import com.aeroglyph.app.audio.ModemConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
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

/**
 * Continuously captures microphone PCM and tries to pull frames out of it.
 *
 * Deliberately dumb about *meaning*: it reports every frame that decodes
 * cleanly, including repeats. Deduping, relaying, and anything user-facing is
 * [com.aeroglyph.app.session.SessionManager]'s job.
 *
 * Two things keep this affordable to run continuously:
 *  - a frequency-selective energy gate (reusing the same Goertzel bins the
 *    decoder itself uses), so a quiet room costs one cheap pass per chunk
 *    and nothing else;
 *  - a decode-attempt throttle, since a full chirp search is far too
 *    expensive to run on every 40ms buffer that arrives.
 */
class Listener {

    private val _events = MutableSharedFlow<DecodeResult>(extraBufferCapacity = 16)
    val events: SharedFlow<DecodeResult> = _events.asSharedFlow()

    /** Live per-bin Goertzel magnitudes across the 16 tone frequencies -- drives the spectrum visualizer. */
    private val _binEnergies = MutableStateFlow(DoubleArray(ModemConfig.TONE_COUNT))
    val binEnergies: StateFlow<DoubleArray> = _binEnergies.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    /** True while energy in our own 17-19.5kHz band is above the VAD floor -- i.e. "something is happening" in the band we actually care about. */
    private val _signalPresent = MutableStateFlow(false)
    val signalPresent: StateFlow<Boolean> = _signalPresent.asStateFlow()

    /** The strongest of the 16 Goertzel bin magnitudes this chunk -- surfaced in the UI so signal strength is visible, not just a boolean. */
    private val _peakBandEnergy = MutableStateFlow(0.0)
    val peakBandEnergy: StateFlow<Double> = _peakBandEnergy.asStateFlow()

    /** Set when no usable audio source could be opened at all (permission revoked, mic busy). */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var job: Job? = null
    private var audioRecord: AudioRecord? = null

    @Volatile
    var symbolRateHz: Double = ModemConfig.DEFAULT_SYMBOL_RATE_HZ

    /**
     * Paused rather than stopped while this device is itself transmitting --
     * a phone can't usefully decode its own speaker output, and trying just
     * wastes CPU and produces junk.
     */
    @Volatile
    var muted: Boolean = false

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
        _signalPresent.value = false
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private suspend fun captureLoop() {
        val record = openAudioRecord()
        if (record == null) {
            _error.value = "Couldn't open the microphone. Another app may be using it."
            return
        }
        audioRecord = record

        val chunk = ShortArray(CHUNK_SAMPLES)
        var accumulator = ShortArray(0)
        var lastAttemptAt = 0L

        try {
            record.startRecording()
            _isListening.value = true

            while (currentCoroutineContext().isActive) {
                val read = record.read(chunk, 0, chunk.size)
                if (read <= 0) continue

                if (muted) {
                    accumulator = ShortArray(0)
                    _signalPresent.value = false
                    continue
                }

                val bins = AudioDecoder.binEnergies(chunk, 0, read)
                _binEnergies.value = bins

                // Gate on energy in OUR band specifically, not broadband RMS.
                // Broadband RMS is dominated by ordinary room noise (voice,
                // traffic, HVAC hum) and says almost nothing about whether a
                // 17-19.5kHz tone is present; a strong Goertzel bin does.
                val peak = bins.maxOrNull() ?: 0.0
                _peakBandEnergy.value = peak
                val active = peak >= VAD_BAND_ENERGY_FLOOR
                _signalPresent.value = active

                if (!active) {
                    accumulator = ShortArray(0)
                    continue
                }

                accumulator = append(accumulator, chunk, read)
                if (accumulator.size > MAX_ACCUMULATOR_SAMPLES) {
                    accumulator = accumulator.copyOfRange(accumulator.size - MAX_ACCUMULATOR_SAMPLES, accumulator.size)
                }
                if (accumulator.size < MIN_SAMPLES_BEFORE_DECODE) continue

                val now = SystemClock.elapsedRealtime()
                if (now - lastAttemptAt < DECODE_ATTEMPT_INTERVAL_MS) continue
                lastAttemptAt = now

                when (val result = AudioDecoder.decode(
                    buffer = accumulator,
                    symbolRateHz = symbolRateHz,
                    chirpSearchStride = LIVE_CHIRP_SEARCH_STRIDE,
                )) {
                    is DecodeResult.Success -> {
                        _events.tryEmit(result)
                        accumulator = ShortArray(0)
                    }
                    DecodeResult.CrcFailed -> {
                        // Corrupt (or a false-positive chirp match). Drop it
                        // silently and wait for the next repetition -- never
                        // show a half-decoded message to a human.
                        _events.tryEmit(DecodeResult.CrcFailed)
                        accumulator = ShortArray(0)
                    }
                    DecodeResult.Incomplete -> Unit // keep accumulating
                }
            }
        } catch (t: Throwable) {
            if (t !is CancellationException) {
                _error.value = "Microphone capture stopped unexpectedly."
            }
            throw t
        } finally {
            releaseRecord()
            _isListening.value = false
            _signalPresent.value = false
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun openAudioRecord(): AudioRecord? {
        val minBufferBytes = AudioRecord.getMinBufferSize(
            ModemConfig.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBufferBytes <= 0) return null
        val bufferBytes = maxOf(minBufferBytes * 4, CHUNK_SAMPLES * 2 * 4)

        // UNPROCESSED gives us raw samples with the platform's noise
        // suppression / AGC out of the way -- those "improvements" are tuned
        // for speech and actively mangle near-ultrasonic tones. Not every
        // device implements it, so fall back down a chain.
        val sources = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) add(MediaRecorder.AudioSource.UNPROCESSED)
            add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            add(MediaRecorder.AudioSource.MIC)
        }

        for (source in sources) {
            val record = runCatching {
                AudioRecord(source, ModemConfig.SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
            }.getOrNull()

            if (record != null && record.state == AudioRecord.STATE_INITIALIZED) return record
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

    private fun append(existing: ShortArray, chunk: ShortArray, length: Int): ShortArray {
        val combined = ShortArray(existing.size + length)
        existing.copyInto(combined)
        chunk.copyInto(combined, destinationOffset = existing.size, startIndex = 0, endIndex = length)
        return combined
    }

    private companion object {
        const val CHUNK_SAMPLES = 2048

        /**
         * Goertzel magnitude floor (not raw RMS) below which we treat a chunk
         * as silence. A real tone at frequency f produces a magnitude of
         * roughly amplitude/2 at f's own bin, while broadband room noise
         * spreads its energy across the spectrum and so contributes only a
         * small fraction of that to any single bin -- this threshold is set
         * low and biased toward false positives (a spurious wakeup just costs
         * one wasted decode attempt) rather than false negatives (which would
         * mean never noticing a real broadcast at all). Not yet tuned against
         * a real device's actual mic sensitivity at 17-19.5kHz -- see the live
         * peak-energy reading on the Listen screen if this needs adjusting.
         */
        const val VAD_BAND_ENERGY_FLOOR = 120.0

        /** A full chirp search is expensive; at most ~8 attempts/sec while a signal is present. */
        const val DECODE_ATTEMPT_INTERVAL_MS = 120L

        /** Coarser than the unit tests use -- see ChirpSync.findChirp for why this is safe. */
        const val LIVE_CHIRP_SEARCH_STRIDE = 4

        /** Don't attempt a decode until we have at least a sync chirp's worth of audio. */
        val MIN_SAMPLES_BEFORE_DECODE = (ModemConfig.SAMPLE_RATE_HZ * ModemConfig.CHIRP_DURATION_MS / 1000.0).toInt() * 2

        /** ~35s ceiling: longer than the slowest profile's max-length frame, with margin. */
        const val MAX_ACCUMULATOR_SAMPLES = ModemConfig.SAMPLE_RATE_HZ * 35
    }
}
