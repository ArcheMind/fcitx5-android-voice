/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.voice

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import timber.log.Timber
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class VoiceInputController(
    private val service: FcitxInputMethodService,
    private val scope: CoroutineScope
) {
    private data class ActiveSession(
        val generation: Long,
        val recording: WavRecorder.Session,
        val worker: Job,
        val committed: AtomicBoolean
    )

    private val recorder = WavRecorder(scope)
    private val client = VoiceTranscriptionClient()
    private var session: ActiveSession? = null
    private var generation = 0L
    private var processing = false
    private var previewText = ""

    var voiceState: VoiceState = VoiceState.Idle
        private set(value) {
            field = value
            service.onVoiceStateChanged(value)
        }

    init {
        scope.launch(Dispatchers.IO) {
            runCatching(recorder::prewarm)
                .onFailure { Timber.e(it, "Silero VAD prewarm failed") }
        }
    }

    fun start() {
        if (session != null || processing) return
        if (ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            toast(R.string.voice_input_missing_permission)
            return
        }
        if (VoiceInputPreferences.preferLocal() && LocalMnnEngine.isReady()) {
            scope.launch(Dispatchers.IO) {
                runCatching(LocalMnnEngine::prewarm)
                    .onFailure { Timber.e(it, "MNN prewarm failed") }
            }
        }
        if (missingRemoteKeyMessage() != null) return

        val currentGeneration = ++generation
        runCatching {
            val directory = service.cacheDir.resolve("voice-input/session-$currentGeneration")
            val recording = recorder.start(directory)
            val committed = AtomicBoolean(false)
            val worker = startWorker(currentGeneration, recording, committed)
            session = ActiveSession(currentGeneration, recording, worker, committed)
            voiceState = VoiceState.Recording
        }.onFailure(::showFailure)
    }

    fun finish(cancel: Boolean) {
        val active = session ?: return
        if (cancel) {
            session = null
            cancel(active)
            return
        }
        if (processing) return

        processing = true
        voiceState = VoiceState.Processing
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { recorder.stop(active.recording) }
                active.worker.join()
            }.onFailure { failure ->
                if (failure !is CancellationException && active.generation == generation) {
                    showFailure(failure)
                }
            }
            withContext(Dispatchers.IO) { active.recording.directory.delete() }
            if (active.generation == generation) {
                session = null
                processing = false
                voiceState = VoiceState.Idle
            }
        }
    }

    fun cancel(clearPreview: Boolean = true) {
        val active = session ?: return
        session = null
        cancel(active, clearPreview)
    }

    private fun startWorker(
        currentGeneration: Long,
        recording: WavRecorder.Session,
        committed: AtomicBoolean
    ) = scope.launch {
        var precedingText = service.getTextBeforeCursor()
        var hasPreviousChunk = false
        try {
            for (audio in recording.segments) {
                try {
                    val rawText = transcribe(audio, precedingText, hasPreviousChunk, currentGeneration)
                    val text = VoiceTranscriptionNormalizer.normalize(rawText)
                    if (currentGeneration == generation && text.isNotBlank()) {
                        service.commitText(text)
                        previewText = ""
                        precedingText += text
                        hasPreviousChunk = true
                        committed.set(true)
                        Timber.d("Voice segment committed: file=%s text=%s", audio.absolutePath, text)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    if (currentGeneration == generation) showFailure(failure)
                } finally {
                    if (currentGeneration == generation) clearPreviewText()
                    withContext(NonCancellable + Dispatchers.IO) { audio.delete() }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (currentGeneration == generation) showFailure(failure)
        }
    }

    private suspend fun transcribe(
        audio: File,
        precedingText: String,
        hasPreviousChunk: Boolean,
        currentGeneration: Long
    ): String = coroutineScope {
        val updates = Channel<String>(Channel.CONFLATED)
        val reader = launch {
            var firstPreview = true
            for (raw in updates) {
                if (currentGeneration != generation) continue
                val text = VoiceTranscriptionNormalizer.preview(raw)
                if (text == previewText) continue
                service.previewVoiceText(text)
                previewText = text
                if (firstPreview && text.isNotBlank()) {
                    firstPreview = false
                    Timber.d("Voice segment first preview: file=%s text=%s", audio.absolutePath, text)
                }
            }
        }
        try {
            withContext(Dispatchers.IO) {
                client.transcribe(audio, precedingText, VoiceInputPreferences.hotwords(), hasPreviousChunk) {
                    updates.trySend(it)
                }
            }
        } finally {
            updates.close()
            withContext(NonCancellable) { reader.join() }
        }
    }

    private fun clearPreviewText() {
        if (previewText.isNotEmpty()) service.previewVoiceText("")
        previewText = ""
    }

    private fun cancel(active: ActiveSession, clearPreview: Boolean = true) {
        processing = true
        voiceState = VoiceState.Idle
        ++generation
        if (clearPreview) clearPreviewText() else previewText = ""
        recorder.abort(active.recording)
        active.worker.cancel()
        scope.launch {
            active.recording.writer.join()
            active.worker.join()
            processing = false
        }
    }

    private fun missingRemoteKeyMessage(): Int? {
        if (VoiceInputPreferences.isAvailable()) return null
        return if (VoiceTranscriptionClient.isCurrentTimeZoneChina()) {
            R.string.voice_input_missing_zhipu_key.takeIf {
                VoiceInputPreferences.zhipuKey().isEmpty()
            }
        } else {
            R.string.voice_input_missing_openai_key.takeIf {
                VoiceInputPreferences.openAIKey().isEmpty()
            }
        }
    }

    private fun showFailure(error: Throwable) {
        Timber.e(error, "Voice input failed")
        Toast.makeText(
            service,
            service.getString(R.string.voice_input_failed, error.message ?: error.javaClass.simpleName),
            Toast.LENGTH_LONG
        ).show()
    }

    private fun toast(message: Int) {
        Toast.makeText(service, message, Toast.LENGTH_SHORT).show()
    }
}
