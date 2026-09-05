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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import timber.log.Timber
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
            toast(R.string.voice_input_listening)
        }.onFailure(::showFailure)
    }

    fun finish(cancel: Boolean) {
        val active = session ?: return
        session = null
        if (cancel) {
            cancel(active)
            toast(R.string.voice_input_cancelled)
            return
        }

        processing = true
        toast(R.string.voice_input_processing)
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { recorder.stop(active.recording) }
                active.worker.join()
            }.onFailure { failure ->
                if (failure !is CancellationException && active.generation == generation) {
                    showFailure(failure)
                }
            }
            if (active.generation == generation && !active.committed.get()) {
                toast(R.string.voice_input_empty)
            }
            withContext(Dispatchers.IO) { active.recording.directory.delete() }
            processing = false
        }
    }

    fun cancel() {
        val active = session ?: return
        session = null
        cancel(active)
    }

    private fun startWorker(
        currentGeneration: Long,
        recording: WavRecorder.Session,
        committed: AtomicBoolean
    ) = scope.launch {
        var precedingText = service.getTextBeforeCursor()
        try {
            for (audio in recording.segments) {
                try {
                    val rawText = withContext(Dispatchers.IO) {
                        client.transcribe(audio, precedingText, VoiceInputPreferences.hotwords())
                    }
                    val text = VoiceTranscriptionNormalizer.normalize(rawText)
                    if (currentGeneration == generation && text.isNotBlank()) {
                        service.commitText(text)
                        precedingText += text
                        committed.set(true)
                        Timber.d("Voice segment committed: file=%s text=%s", audio.absolutePath, text)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    if (currentGeneration == generation) showFailure(failure)
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) { audio.delete() }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (currentGeneration == generation) showFailure(failure)
        }
    }

    private fun cancel(active: ActiveSession) {
        processing = true
        ++generation
        recorder.abort(active.recording)
        active.worker.cancel()
        scope.launch {
            active.recording.writer.join()
            processing = false
        }
    }

    private fun missingRemoteKeyMessage(): Int? {
        if (VoiceInputPreferences.preferLocal() && LocalMnnEngine.isReady()) return null
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
