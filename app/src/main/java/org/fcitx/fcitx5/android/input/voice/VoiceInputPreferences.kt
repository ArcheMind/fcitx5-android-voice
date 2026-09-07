/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.voice

import androidx.preference.PreferenceManager
import org.fcitx.fcitx5.android.utils.appContext

object VoiceInputPreferences {
    const val PreferLocal = "voice_input_prefer_local"
    const val KeepModelReady = "voice_input_keep_model_ready"
    const val OpenAIKey = "voice_input_openai_key"
    const val ZhipuKey = "voice_input_zhipu_key"
    const val Hotwords = "voice_input_hotwords"

    private val preferences
        get() = PreferenceManager.getDefaultSharedPreferences(appContext)

    fun preferLocal() = preferences.getBoolean(PreferLocal, true)

    fun keepModelReady() = preferences.getBoolean(KeepModelReady, false)

    fun setKeepModelReady(value: Boolean) {
        preferences.edit().putBoolean(KeepModelReady, value).apply()
    }

    fun openAIKey() = preferences.getString(OpenAIKey, "").orEmpty().trim()

    fun zhipuKey() = preferences.getString(ZhipuKey, "").orEmpty().trim()

    fun isAvailable() =
        (preferLocal() && LocalMnnEngine.isReady()) ||
            if (VoiceTranscriptionClient.isCurrentTimeZoneChina()) zhipuKey().isNotEmpty()
            else openAIKey().isNotEmpty()

    fun hotwords(): List<String> = preferences.getString(Hotwords, "").orEmpty()
        .split(',', '\n')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .take(100)
}
