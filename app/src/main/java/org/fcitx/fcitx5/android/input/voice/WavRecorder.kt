/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference

class WavRecorder(private val scope: CoroutineScope) {
    data class Session(
        val file: File,
        val recorder: AudioRecord,
        val writer: Job,
        val error: AtomicReference<Throwable?>
    )

    @SuppressLint("MissingPermission")
    fun start(file: File): Session {
        val minBuffer = AudioRecord.getMinBufferSize(
            SampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        require(minBuffer > 0) { "Audio recording is unavailable" }
        file.parentFile?.mkdirs()
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuffer * 2
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Cannot initialize microphone" }
        val error = AtomicReference<Throwable?>()
        recorder.startRecording()
        val writer = scope.launch(Dispatchers.IO) {
            runCatching {
                FileOutputStream(file).use { output ->
                    output.write(ByteArray(HeaderSize))
                    val buffer = ByteArray(minBuffer)
                    var written = 0
                    while (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING && written < MaxPcmBytes) {
                        val count = recorder.read(buffer, 0, minOf(buffer.size, MaxPcmBytes - written))
                        if (count <= 0) break
                        output.write(buffer, 0, count)
                        written += count
                    }
                }
            }.onFailure(error::set)
        }
        return Session(file, recorder, writer, error)
    }

    suspend fun stop(session: Session): File {
        if (session.recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
            session.recorder.stop()
        }
        session.writer.join()
        session.recorder.release()
        session.error.get()?.let { throw it }
        val pcmBytes = (session.file.length() - HeaderSize).coerceAtLeast(0)
        require(pcmBytes > SampleRate / 5) { "Recording is too short" }
        RandomAccessFile(session.file, "rw").use {
            it.seek(0)
            it.write(wavHeader(pcmBytes.toInt()))
        }
        return session.file
    }

    fun abort(session: Session) {
        runCatching {
            if (session.recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                session.recorder.stop()
            }
        }
        session.writer.cancel()
        session.recorder.release()
        session.file.delete()
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
            putShort(16)
            put("data".toByteArray())
            putInt(pcmBytes)
        }
        .array()

    companion object {
        private const val SampleRate = 16_000
        private const val BytesPerSample = 2
        private const val HeaderSize = 44
        private const val MaxPcmBytes = SampleRate * BytesPerSample * 30
    }
}
