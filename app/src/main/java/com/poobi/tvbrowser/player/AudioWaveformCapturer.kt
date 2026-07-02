package com.poobi.tvbrowser.player

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

class AudioWaveformCapturer : AudioProcessor {
    private var isActive = true
    private var inputFormat = AudioFormat.NOT_SET

    val amplitudeCache = ConcurrentHashMap<Long, Float>()
    @Volatile var currentPositionMs: Long = 0L

    override fun configure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != androidx.media3.common.C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        inputFormat = inputAudioFormat
        return inputAudioFormat
    }

    override fun isActive(): Boolean = isActive

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        
        var maxVal = 0
        val buffer = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        while (buffer.remaining() >= 2) {
            val sample = Math.abs(buffer.short.toInt())
            if (sample > maxVal) {
                maxVal = sample
            }
        }
        
        val normAmplitude = maxVal / 32768f
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