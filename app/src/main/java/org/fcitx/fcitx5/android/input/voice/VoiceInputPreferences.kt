/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.voice

import androidx.preference.PreferenceManager
import org.fcitx.fcitx5.android.utils.appContext

object VoiceInputPreferences {
    const val OpenAIKey = "voice_input_openai_key"
    const val ZhipuKey = "voice_input_zhipu_key"
    const val Hotwords = "voice_input_hotwords"

    private val preferences
        get() = PreferenceManager.getDefaultSharedPreferences(appContext)

    fun openAIKey() = preferences.getString(OpenAIKey, "").orEmpty().trim()

    fun zhipuKey() = preferences.getString(ZhipuKey, "").orEmpty().trim()

    fun hotwords(): List<String> = preferences.getString(Hotwords, "").orEmpty()
        .split(',', '\n')
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()
        .take(100)
}
