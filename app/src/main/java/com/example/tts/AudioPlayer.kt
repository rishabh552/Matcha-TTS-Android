package com.example.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

class AudioPlayer {
    companion object {
        private const val TAG = "MatchaAudio"
        private const val MIN_STREAM_BUFFER_BYTES = 256 * 1024
        private const val STREAM_BUFFER_MULTIPLIER = 4
        private const val PCM16_CONVERT_CHUNK_SAMPLES = 4096
    }

    private val stateLock = Any()
    private var track: AudioTrack? = null
    private var currentSampleRate: Int = 0
    private var currentEncoding: Int = AudioFormat.ENCODING_INVALID

    fun play(samples: FloatArray, sampleRate: Int): Boolean {
        if (samples.isEmpty()) return true

        val (audioTrack, encoding) = ensureTrack(sampleRate) ?: return false

        return if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            writeFloat(audioTrack, samples)
        } else {
            writePcm16(audioTrack, samples)
        }
    }

    private fun ensureTrack(sampleRate: Int): Pair<AudioTrack, Int>? {
        synchronized(stateLock) {
            val existing = track
            if (existing != null && currentSampleRate == sampleRate) {
                return existing to currentEncoding
            }
        }

        val oldTrack = synchronized(stateLock) {
            val old = track
            track = null
            currentSampleRate = 0
            currentEncoding = AudioFormat.ENCODING_INVALID
            old
        }
        releaseTrack(oldTrack)

        buildTrack(sampleRate, AudioFormat.ENCODING_PCM_FLOAT)?.let { newTrack ->
            synchronized(stateLock) {
                track = newTrack
                currentSampleRate = sampleRate
                currentEncoding = AudioFormat.ENCODING_PCM_FLOAT
            }
            Log.i(TAG, "Using float AudioTrack @${sampleRate}Hz")
            return newTrack to AudioFormat.ENCODING_PCM_FLOAT
        }

        buildTrack(sampleRate, AudioFormat.ENCODING_PCM_16BIT)?.let { newTrack ->
            synchronized(stateLock) {
                track = newTrack
                currentSampleRate = sampleRate
                currentEncoding = AudioFormat.ENCODING_PCM_16BIT
            }
            Log.w(TAG, "Float AudioTrack unavailable. Falling back to PCM16 @${sampleRate}Hz")
            return newTrack to AudioFormat.ENCODING_PCM_16BIT
        }

        Log.e(TAG, "Failed to create AudioTrack for sampleRate=$sampleRate")
        return null
    }

    private fun buildTrack(sampleRate: Int, encoding: Int): AudioTrack? {
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            encoding
        )

        if (minBufferSize <= 0) {
            Log.e(TAG, "Invalid minBufferSize=$minBufferSize for encoding=$encoding")
            return null
        }

        val bufferSize = maxOf(
            minBufferSize * STREAM_BUFFER_MULTIPLIER,
            MIN_STREAM_BUFFER_BYTES
        )

        val audioFormat = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(encoding)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        return try {
            AudioTrack.Builder()
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
                .also { it.play() }
        } catch (t: Throwable) {
            Log.e(TAG, "AudioTrack build failed (encoding=$encoding, buffer=$bufferSize)", t)
            null
        }
    }

    private fun writeFloat(audioTrack: AudioTrack, samples: FloatArray): Boolean {
        var offset = 0
        while (offset < samples.size) {
            val wrote = audioTrack.write(
                samples,
                offset,
                samples.size - offset,
                AudioTrack.WRITE_BLOCKING
            )
            if (wrote <= 0) {
                Log.e(TAG, "Float write failed: $wrote")
                return false
            }
            offset += wrote
        }
        return true
    }

    private fun writePcm16(audioTrack: AudioTrack, samples: FloatArray): Boolean {
        val buffer = ShortArray(minOf(PCM16_CONVERT_CHUNK_SAMPLES, samples.size))
        var offset = 0

        while (offset < samples.size) {
            val chunk = minOf(buffer.size, samples.size - offset)

            for (i in 0 until chunk) {
                val clamped = samples[offset + i].coerceIn(-1.0f, 1.0f)
                buffer[i] = (clamped * 32767.0f).toInt().toShort()
            }

            var wroteSamples = 0
            while (wroteSamples < chunk) {
                val wrote = audioTrack.write(
                    buffer,
                    wroteSamples,
                    chunk - wroteSamples,
                    AudioTrack.WRITE_BLOCKING
                )
                if (wrote <= 0) {
                    Log.e(TAG, "PCM16 write failed: $wrote")
                    return false
                }
                wroteSamples += wrote
            }

            offset += chunk
        }

        return true
    }

    fun stop() {
        val oldTrack = synchronized(stateLock) {
            val old = track
            track = null
            currentSampleRate = 0
            currentEncoding = AudioFormat.ENCODING_INVALID
            old
        }

        releaseTrack(oldTrack)
    }

    private fun releaseTrack(audioTrack: AudioTrack?) {
        if (audioTrack == null) return

        try {
            audioTrack.pause()
        } catch (_: IllegalStateException) {
        }

        try {
            audioTrack.flush()
        } catch (_: IllegalStateException) {
        }

        try {
            audioTrack.stop()
        } catch (_: IllegalStateException) {
        }

        audioTrack.release()
    }
}
