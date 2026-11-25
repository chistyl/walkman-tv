package com.walkman.tv.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Best-effort karaoke vocal reduction using stereo center-channel cancellation.
 *
 * Most center-panned lead vocals are shared by left and right channels. Subtracting the
 * opposite channel attenuates that shared signal while retaining some stereo side content.
 * This is not source separation: centered bass, kick, snare and other instruments can also
 * be reduced, and mono sources cannot be processed.
 */
class VocalRemovalProcessor : BaseAudioProcessor() {
    @Volatile
    var enabled: Boolean = false

    private var channelCount: Int = 0

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        channelCount = inputAudioFormat.channelCount
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val output = replaceOutputBuffer(remaining).order(ByteOrder.LITTLE_ENDIAN)
        if (!enabled || channelCount != 2) {
            output.put(inputBuffer)
            output.flip()
            return
        }

        val input = inputBuffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        while (input.remaining() >= 4) {
            val left = input.short.toInt()
            val right = input.short.toInt()
            val (outLeft, outRight) = cancelCenter(left, right)
            output.putShort(outLeft.toShort())
            output.putShort(outRight.toShort())
        }
        // Preserve any incomplete trailing bytes defensively.
        if (input.hasRemaining()) output.put(input)
        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }

    override fun onReset() {
        channelCount = 0
    }

    companion object {
        /**
         * Center cancellation with headroom: L' = 0.75 * (L - R), R' = 0.75 * (R - L).
         * The gain reduces clipping risk while preserving the anti-phase stereo difference.
         */
        fun cancelCenter(left: Int, right: Int): Pair<Int, Int> {
            val outLeft = ((left - right) * 0.75f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            val outRight = ((right - left) * 0.75f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            return outLeft to outRight
        }
    }
}
