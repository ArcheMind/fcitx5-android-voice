/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class WavRecorder(private val scope: CoroutineScope) {
    data class Session internal constructor(
        val directory: File,
        val recorder: AudioRecord,
        val writer: Job,
        internal val output: Channel<File>,
        internal val aborted: AtomicBoolean,
        internal val released: AtomicBoolean,
        internal val error: AtomicReference<Throwable?>
    ) {
        val segments: ReceiveChannel<File> get() = output
    }

    private val vad by lazy(LazyThreadSafetyMode.SYNCHRONIZED, SileroVad::create)

    fun prewarm() {
        vad
    }

    @SuppressLint("MissingPermission")
    fun start(directory: File): Session {
        val minBufferBytes = AudioRecord.getMinBufferSize(
            SampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        require(minBufferBytes > 0) { "Audio recording is unavailable" }
        directory.mkdirs()
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBufferBytes * 2, SileroVad.FrameSamples * BytesPerSample * 2)
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Cannot initialize microphone" }

        val output = Channel<File>(Channel.UNLIMITED)
        val aborted = AtomicBoolean(false)
        val released = AtomicBoolean(false)
        val error = AtomicReference<Throwable?>()
        recorder.startRecording()
        val writer = scope.launch(Dispatchers.IO) {
            var segmentIndex = 0
            var capturedSamples = 0
            try {
                val segmenter = SpeechSegmenter(vad)
                vad.reset()
                val buffer = ShortArray(SileroVad.FrameSamples)
                while (!aborted.get() &&
                    recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING &&
                    capturedSamples < MaxSamples
                ) {
                    val count = recorder.read(
                        buffer,
                        0,
                        minOf(buffer.size, MaxSamples - capturedSamples),
                        AudioRecord.READ_BLOCKING
                    )
                    check(count >= 0) { "AudioRecord.read failed: $count" }
                    if (count == 0) continue
                    capturedSamples += count
                    segmenter.accept(buffer, count).forEach { pcm ->
                        enqueueSegment(directory, output, segmentIndex++, pcm)
                    }
                }
                if (!aborted.get()) {
                    segmenter.flush()?.let { pcm ->
                        enqueueSegment(directory, output, segmentIndex, pcm)
                    }
                }
            } catch (cancelled: CancellationException) {
                if (!aborted.get()) throw cancelled
            } catch (failure: Throwable) {
                error.set(failure)
            } finally {
                runCatching { vad.reset() }
                output.close(error.get())
                if (capturedSamples >= MaxSamples) runCatching { recorder.stop() }
            }
        }
        return Session(directory, recorder, writer, output, aborted, released, error)
    }

    suspend fun stop(session: Session) {
        if (session.recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            session.recorder.stop()
        }
        session.writer.join()
        release(session)
        session.error.get()?.let { throw it }
    }

    fun abort(session: Session) {
        session.aborted.set(true)
        runCatching {
            if (session.recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                session.recorder.stop()
            }
        }
        session.writer.cancel()
        scope.launch(Dispatchers.IO) {
            session.writer.join()
            release(session)
            session.directory.listFiles()?.forEach { it.delete() }
        }
    }

    private fun release(session: Session) {
        if (session.released.compareAndSet(false, true)) session.recorder.release()
    }

    private fun enqueueSegment(
        directory: File,
        output: Channel<File>,
        index: Int,
        pcm: ShortArray
    ) {
        val file = directory.resolve("segment-%03d.wav".format(index))
        writeWav(file, pcm)
        Timber.d("Voice segment queued: index=%d samples=%d file=%s", index, pcm.size, file.absolutePath)
        if (output.trySend(file).isFailure) file.delete()
    }

    private fun writeWav(file: File, pcm: ShortArray) {
        val pcmBytes = pcm.size * BytesPerSample
        FileOutputStream(file).use { output ->
            output.write(wavHeader(pcmBytes))
            val bytes = ByteBuffer.allocate(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
            bytes.asShortBuffer().put(pcm)
            output.write(bytes.array())
        }
    }

    private fun wavHeader(pcmBytes: Int): ByteArray = ByteBuffer.allocate(HeaderSize)
        .order(ByteOrder.LITTLE_ENDIAN)
        .apply {
            put("RIFF".toByteArray())
            putInt(pcmBytes + 36)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1.toShort())
            putShort(1.toShort())
            putInt(SampleRate)
            putInt(SampleRate * BytesPerSample)
            putShort(BytesPerSample.toShort())
            putShort(16.toShort())
            put("data".toByteArray())
            putInt(pcmBytes)
        }
        .array()

    private class SpeechSegmenter(private val vad: SileroVad) {
        private val pending = ShortArray(SileroVad.FrameSamples)
        private var pendingSize = 0
        private val preRoll = ArrayDeque<ShortArray>()
        private val current = ArrayList<ShortArray>()
        private var speechLatched = false
        private var speechRunFrames = 0
        private var speechStarted = false
        private var voicedSamples = 0
        private var silentSamples = 0

        fun accept(samples: ShortArray, count: Int): List<ShortArray> {
            val completed = ArrayList<ShortArray>(1)
            var sourceOffset = 0
            while (sourceOffset < count) {
                val copied = minOf(pending.size - pendingSize, count - sourceOffset)
                samples.copyInto(pending, pendingSize, sourceOffset, sourceOffset + copied)
                pendingSize += copied
                sourceOffset += copied
                if (pendingSize == pending.size) {
                    processFrame(pending.copyOf())?.let(completed::add)
                    pendingSize = 0
                }
            }
            return completed
        }

        fun flush(): ShortArray? {
            if (!speechStarted) return null
            if (pendingSize > 0) current += pending.copyOf(pendingSize)
            pendingSize = 0
            return emit()
        }

        private fun processFrame(frame: ShortArray): ShortArray? {
            val probability = vad.predict(frame)
            val speech = probability >= if (speechLatched) SpeechReleaseThreshold else SpeechStartThreshold
            speechLatched = speech

            if (!speechStarted) {
                preRoll.addLast(frame)
                while (preRoll.size > PreRollFrames) preRoll.removeFirst()
                speechRunFrames = if (speech) speechRunFrames + 1 else 0
                if (speechRunFrames < MinSpeechFrames) return null
                current.addAll(preRoll)
                preRoll.clear()
                speechStarted = true
                voicedSamples = speechRunFrames * SileroVad.FrameSamples
                silentSamples = 0
                return null
            }

            current += frame
            if (speech) {
                voicedSamples += SileroVad.FrameSamples
                silentSamples = 0
                return null
            }

            silentSamples += SileroVad.FrameSamples
            if (silentSamples < silenceThresholdSamples()) return null
            return emit()
        }

        private fun silenceThresholdSamples(): Int {
            val progress = voicedSamples.coerceAtMost(SilenceRampSamples).toFloat() / SilenceRampSamples
            return MaxSilenceSamples - ((MaxSilenceSamples - MinSilenceSamples) * progress).toInt()
        }

        private fun emit(): ShortArray {
            val result = ShortArray(current.sumOf { it.size })
            var offset = 0
            current.forEach { frame ->
                frame.copyInto(result, offset)
                offset += frame.size
            }
            current.clear()
            preRoll.clear()
            speechRunFrames = 0
            speechStarted = false
            voicedSamples = 0
            silentSamples = 0
            return result
        }

        companion object {
            private const val SpeechStartThreshold = 0.5f
            private const val SpeechReleaseThreshold = 0.35f
            private const val MinSpeechFrames = 8
            private const val PreRollFrames = 10
            private const val MaxSilenceSamples = SampleRate * 700 / 1000
            private const val MinSilenceSamples = SampleRate * 300 / 1000
            private const val SilenceRampSamples = SampleRate * 8
        }
    }

    companion object {
        private const val SampleRate = 16_000
        private const val BytesPerSample = 2
        private const val HeaderSize = 44
        private const val MaxSamples = SampleRate * 30
    }
}
