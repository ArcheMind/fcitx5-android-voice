/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.voice

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.input.FcitxInputMethodService
import timber.log.Timber

class VoiceInputController(
    private val service: FcitxInputMethodService,
    private val scope: CoroutineScope
) {
    private val recorder = WavRecorder(scope)
    private val client = VoiceTranscriptionClient()
    private var session: WavRecorder.Session? = null
    private var processing = false

    fun start() {
        if (session != null || processing) return
        if (ContextCompat.checkSelfPermission(service, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            toast(R.string.voice_input_missing_permission)
            return
        }
        val missingKeyMessage = if (VoiceTranscriptionClient.isCurrentTimeZoneChina()) {
            R.string.voice_input_missing_zhipu_key.takeIf {
                VoiceInputPreferences.zhipuKey().isEmpty()
            }
        } else {
            R.string.voice_input_missing_openai_key.takeIf {
                VoiceInputPreferences.openAIKey().isEmpty()
            }
        }
        if (missingKeyMessage != null) {
            toast(missingKeyMessage)
            return
        }
        runCatching {
            val file = service.cacheDir.resolve("voice-input/current.wav")
            session = recorder.start(file)
            toast(R.string.voice_input_listening)
        }.onFailure(::showFailure)
    }

    fun finish(cancel: Boolean) {
        val active = session ?: return
        session = null
        if (cancel) {
            recorder.abort(active)
            toast(R.string.voice_input_cancelled)
            return
        }
        processing = true
        toast(R.string.voice_input_processing)
        val precedingText = service.getTextBeforeCursor()
        val hotwords = VoiceInputPreferences.hotwords()
        scope.launch {
            runCatching {
                val audio = withContext(Dispatchers.IO) { recorder.stop(active) }
                try {
                    withContext(Dispatchers.IO) {
                        client.transcribe(audio, precedingText, hotwords)
                    }
                } finally {
                    audio.delete()
                }
            }.onSuccess { text ->
                if (text.isBlank()) {
                    toast(R.string.voice_input_empty)
                } else {
                    service.commitText(text)
                }
            }.onFailure(::showFailure)
            processing = false
        }
    }

    fun cancel() {
        session?.let(recorder::abort)
        session = null
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
