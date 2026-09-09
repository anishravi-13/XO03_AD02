package com.aeroglyph.app.playback

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.annotation.RequiresPermission
import com.aeroglyph.app.audio.AcousticBand
import com.aeroglyph.app.audio.DecodeResult
import com.aeroglyph.app.audio.ModemConfig
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

/**
 * Owns the microphone and feeds captured PCM to a [FrameDetector].
 *
 * All the decoding intelligence lives in the detector, which is plain Kotlin
 * and unit-tested; this class exists only to open an [AudioRecord], pump
 * chunks through, and republish the detector's state as flows. Keeping the
 * split sharp is deliberate -- the receive logic is the part that has been
 * hard to get right, and it should never again be reachable only from a phone.
 *
 * Deliberately dumb about *meaning*: every decoded frame is reported, repeats
 * included. Deduping, relaying, repair and catch-up are decided above here.
 */
class Listener {

    private val _events = MutableSharedFlow<DecodeResult>(extraBufferCapacity = 16)
    val events: SharedFlow<DecodeResult> = _events.asSharedFlow()

    /** Live per-bin Goertzel magnitudes for the most excited band -- drives the spectrum visualizer. */
    private val _binEnergies = MutableStateFlow(DoubleArray(ModemConfig.TONE_COUNT))
    val binEnergies: StateFlow<DoubleArray> = _binEnergies.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _state = MutableStateFlow(ListenState.IDLE)
    val state: StateFlow<ListenState> = _state.asStateFlow()

    /** Strongest bin magnitude in the most excited band. Surfaced so signal strength is visible, not just a boolean. */
    private val _peakBandEnergy = MutableStateFlow(0.0)
    val peakBandEnergy: StateFlow<Double> = _peakBandEnergy.asStateFlow()

    /** The adaptive background level that band's trigger is measured against. */
    private val _noiseFloor = MutableStateFlow(0.0)
    val noiseFloor: StateFlow<Double> = _noiseFloor.asStateFlow()

    /** Name of the audio source we actually managed to open, for the diagnostics readout. */
    private val _sourceName = MutableStateFlow("")
    val sourceName: StateFlow<String> = _sourceName.asStateFlow()

    /** Which band the last lock was on, so the UI can say what kind of transmission it heard. */
    private val _activeBand = MutableStateFlow(ModemConfig.DEFAULT_BAND)
    val activeBand: StateFlow<AcousticBand> = _activeBand.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var job: Job? = null
    private var audioRecord: AudioRecord? = null

    private val detector = FrameDetector { result -> _events.tryEmit(result) }

    /**
     * Paused rather than stopped while this device is itself transmitting --
     * a phone cannot usefully decode its own speaker output.
     */
    var muted: Boolean
        get() = detector.muted
        set(value) { detector.muted = value }

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
        detector.reset()

        try {
            record.startRecording()
            _isListening.value = true

            while (currentCoroutineContext().isActive) {
                val read = record.read(chunk, 0, chunk.size)
                if (read <= 0) continue

                detector.offer(chunk, read)

                _binEnergies.value = detector.binEnergies
                _peakBandEnergy.value = detector.peakEnergy
                _noiseFloor.value = detector.noiseFloor
                _state.value = detector.state
                _activeBand.value = detector.activeBand
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

        /** 0.5s of mono PCM16 -- headroom so a slow correlation never costs us audio. */
        const val CAPTURE_BUFFER_BYTES = ModemConfig.SAMPLE_RATE_HZ
    }
}
