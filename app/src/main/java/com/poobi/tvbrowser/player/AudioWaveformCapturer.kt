package com.poobi.tvbrowser.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LongFloatRingCache(private val capacity: Int = 4096) {
    private val keys = LongArray(capacity) { -1L }
    private val values = FloatArray(capacity)

    @Synchronized
    operator fun get(key: Long): Float? {
        val idx = (key.hashCode() and 0x7FFFFFFF) % capacity
        return if (keys[idx] == key) values[idx] else null
    }

    @Synchronized
    operator fun set(key: Long, value: Float) {
        val idx = (key.hashCode() and 0x7FFFFFFF) % capacity
        keys[idx] = key
        values[idx] = value
    }

    @Synchronized
    fun clear() {
        keys.fill(-1L)
        values.fill(0f)
    }
}

class AudioWaveformCapturer : AudioProcessor {
    private var isProcessorActive = true
    private var inputFormat = AudioFormat.NOT_SET

    val amplitudeCache = LongFloatRingCache(4096)
    @Volatile var currentPositionMs: Long = 0L

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        val isSupportedPcm = when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT,
            C.ENCODING_PCM_FLOAT,
            C.ENCODING_PCM_24BIT,
            C.ENCODING_PCM_32BIT -> true
            else -> false
        }

        if (!isSupportedPcm || inputAudioFormat.channelCount <= 0) {
            inputFormat = AudioFormat.NOT_SET
            return AudioFormat.NOT_SET
        }

        inputFormat = inputAudioFormat
        return inputAudioFormat
    }

    override fun isActive(): Boolean = inputFormat != AudioFormat.NOT_SET && isProcessorActive

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return

        var maxVal = 0
        val buffer = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)

        try {
            when (inputFormat.encoding) {
                C.ENCODING_PCM_16BIT -> {
                    while (buffer.remaining() >= 2) {
                        val rawSample = buffer.short.toInt()
                        val sample = if (rawSample == Short.MIN_VALUE.toInt()) 32767 else Math.abs(rawSample)
                        if (sample > maxVal) maxVal = sample
                    }
                }
                C.ENCODING_PCM_FLOAT -> {
                    while (buffer.remaining() >= 4) {
                        val sample = (Math.abs(buffer.float) * 32767f).toInt().coerceIn(0, 32767)
                        if (sample > maxVal) maxVal = sample
                    }
                }
                C.ENCODING_PCM_24BIT -> {
                    while (buffer.remaining() >= 3) {
                        val b0 = buffer.get().toInt() and 0xFF
                        val b1 = buffer.get().toInt() and 0xFF
                        val b2 = buffer.get().toInt()
                        val sample24 = (b2 shl 16) or (b1 shl 8) or b0
                        val sample = Math.abs(sample24 shr 8).coerceIn(0, 32767)
                        if (sample > maxVal) maxVal = sample
                    }
                }
                C.ENCODING_PCM_32BIT -> {
                    while (buffer.remaining() >= 4) {
                        val sample = Math.abs(buffer.int shr 16).coerceIn(0, 32767)
                        if (sample > maxVal) maxVal = sample
                    }
                }
                else -> {
                    while (buffer.remaining() >= 2) {
                        val rawSample = buffer.short.toInt()
                        val sample = if (rawSample == Short.MIN_VALUE.toInt()) 32767 else Math.abs(rawSample)
                        if (sample > maxVal) maxVal = sample
                    }
                }
            }
        } catch (_: Exception) {}

        val normAmplitude = (maxVal / 32768f).coerceIn(0f, 1f)
        val currentBucket = currentPositionMs / 50L
        if (currentBucket > 0) {
            val existing = amplitudeCache[currentBucket] ?: 0f
            if (normAmplitude > existing) {
                amplitudeCache[currentBucket] = normAmplitude
            }
        }

        val outputSize = inputBuffer.remaining()
        val outputBuffer = replaceOutputBuffer(outputSize)
        outputBuffer.put(inputBuffer)
        outputBuffer.flip()
    }

    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER

    private fun replaceOutputBuffer(size: Int): ByteBuffer {
        if (outputBuffer.capacity() < size) {
            outputBuffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
        }
        return outputBuffer
    }

    override fun getOutput(): ByteBuffer {
        val output = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return output
    }

    override fun queueEndOfStream() {}
    override fun isEnded(): Boolean = false
    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
    }
    override fun reset() {
        flush()
        amplitudeCache.clear()
    }
}