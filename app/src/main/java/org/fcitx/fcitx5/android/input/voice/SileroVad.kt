/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.voice

import org.fcitx.fcitx5.android.utils.appContext
import timber.log.Timber
import java.io.Closeable

internal class SileroVad private constructor(private var pointer: Long) : Closeable {
    fun reset() {
        check(pointer != 0L) { "Silero VAD is closed" }
        SileroVadNative.reset(pointer)
    }

    fun predict(samples: ShortArray): Float {
        check(pointer != 0L) { "Silero VAD is closed" }
        require(samples.size == FrameSamples) { "Silero VAD requires $FrameSamples samples" }
        return SileroVadNative.predict(pointer, samples)
    }

    override fun close() {
        if (pointer == 0L) return
        SileroVadNative.release(pointer)
        pointer = 0L
    }

    companion object {
        const val FrameSamples = 512

        fun create(): SileroVad {
            val model = appContext.assets.open("models/silero_vad.mnn").use { it.readBytes() }
            Timber.d("Silero VAD create request: modelBytes=%d", model.size)
            return SileroVad(SileroVadNative.create(model)).also {
                Timber.d("Silero VAD create response: ready")
            }
        }
    }
}

private object SileroVadNative {
    init {
        System.loadLibrary("native-lib")
    }

    external fun create(model: ByteArray): Long
    external fun reset(pointer: Long)
    external fun predict(pointer: Long, samples: ShortArray): Float
    external fun release(pointer: Long)
}
