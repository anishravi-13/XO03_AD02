package com.aeroglyph.app.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import com.aeroglyph.app.audio.AcousticBand
import com.aeroglyph.app.audio.AudioEncoder
import com.aeroglyph.app.audio.Frame
import com.aeroglyph.app.audio.ModemConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Plays a [Frame] out of the speaker as PCM16 via [AudioTrack], repeated N
 * times with a short silence between repetitions -- the repetition is what
 * lets a receiver that missed (or half-missed) one pass catch the next one.
 */
class Transmitter(context: Context) {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _isTransmitting = MutableStateFlow(false)
    val isTransmitting: StateFlow<Boolean> = _isTransmitting.asStateFlow()

    /** Most recently written PCM chunk, so the UI can visualize the real outgoing tones. */
    private val _outgoingChunk = MutableStateFlow(ShortArray(0))
    val outgoingChunk: StateFlow<ShortArray> = _outgoingChunk.asStateFlow()

    /** 0f..1f progress through the current transmission, for the UI. */
    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    @Volatile
    private var cancelRequested = false

    private var audioTrack: AudioTrack? = null
    private var focusRequest: AudioFocusRequest? = null
    private var legacyFocusListener: AudioManager.OnAudioFocusChangeListener? = null

    /**
     * Synthesizes and plays [frame]. Suspends until every repetition has been
     * written to the audio device. Safe to cancel -- the coroutine's
     * cancellation tears down the AudioTrack in the finally block.
     */
    suspend fun transmit(
        frame: Frame,
        symbolRateHz: Double = ModemConfig.DEFAULT_SYMBOL_RATE_HZ,
        repeatCount: Int = ModemConfig.DEFAULT_REPEAT_COUNT,
        band: AcousticBand = ModemConfig.DEFAULT_BAND,
    ): Unit = withContext(Dispatchers.IO) {
        val pcm = AudioEncoder.synthesize(frame, symbolRateHz = symbolRateHz, band = band)
        cancelRequested = false

        if (!requestFocus()) return@withContext

        _isTransmitting.value = true
        _progress.value = 0f
        val track = createAudioTrack()
        audioTrack = track

        try {
            track.play()
            for (repetition in 0 until repeatCount) {
                if (cancelRequested) break
                writeInChunks(track, pcm) { withinRepetition ->
                    _progress.value = (repetition + withinRepetition) / repeatCount.toFloat()
                }
                if (repetition != repeatCount - 1) delay(ModemConfig.REPEAT_GAP_MS)
            }
            _progress.value = 1f
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
            audioTrack = null
            _isTransmitting.value = false
            _outgoingChunk.value = ShortArray(0)
            abandonFocus()
        }
    }

    fun cancel() {
        cancelRequested = true
        runCatching { audioTrack?.pause() }
        runCatching { audioTrack?.flush() }
    }

    private fun writeInChunks(track: AudioTrack, pcm: ShortArray, onProgress: (Float) -> Unit) {
        var offset = 0
        while (offset < pcm.size) {
            if (cancelRequested) return
            val length = minOf(WRITE_CHUNK_SAMPLES, pcm.size - offset)
            val written = track.write(pcm, offset, length)
            if (written <= 0) return // device went away; bail rather than spin
            _outgoingChunk.value = pcm.copyOfRange(offset, offset + written)
            offset += written
            onProgress(offset / pcm.size.toFloat())
        }
    }

    private fun createAudioTrack(): AudioTrack {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val format = AudioFormat.Builder()
            .setSampleRate(ModemConfig.SAMPLE_RATE_HZ)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val minBufferBytes = AudioTrack.getMinBufferSize(
            ModemConfig.SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(WRITE_CHUNK_SAMPLES * 2)

        return AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(minBufferBytes * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun requestFocus(): Boolean {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val onFocusChange = AudioManager.OnAudioFocusChangeListener { change ->
            // A call comes in, or another app grabs the speaker: stop cleanly
            // rather than half-transmitting a frame nobody can decode.
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                cancel()
            }
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(onFocusChange)
                .build()
            focusRequest = request
            audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            legacyFocusListener = onFocusChange
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                onFocusChange,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            legacyFocusListener?.let { audioManager.abandonAudioFocus(it) }
            legacyFocusListener = null
        }
    }

    private companion object {
        const val WRITE_CHUNK_SAMPLES = 2048
    }
}
