package com.example.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

class AudioPlayer {
    companion object {
        private const val MIN_STREAM_BUFFER_BYTES = 256 * 1024
        private const val STREAM_BUFFER_MULTIPLIER = 4
    }

    private var track: AudioTrack? = null
    private var currentSampleRate: Int = 0

    @Synchronized
    fun play(samples: FloatArray, sampleRate: Int) {
        if (samples.isEmpty()) return
        ensureTrack(sampleRate)
        track?.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
    }

    @Synchronized
    private fun ensureTrack(sampleRate: Int) {
        if (track != null && currentSampleRate == sampleRate) {
            return
        }

        stop()

        val audioFormat = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        )

        val bufferSize = maxOf(
            minBufferSize * STREAM_BUFFER_MULTIPLIER,
            MIN_STREAM_BUFFER_BYTES
        )

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        track = audioTrack
        currentSampleRate = sampleRate
        audioTrack.play()
    }

    @Synchronized
    fun stop() {
        track?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
            }
            it.release()
        }
        track = null
        currentSampleRate = 0
    }
}
