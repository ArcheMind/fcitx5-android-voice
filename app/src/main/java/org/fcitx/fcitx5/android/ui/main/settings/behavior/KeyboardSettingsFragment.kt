/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.text.InputType
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceScreen
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceFragment
import org.fcitx.fcitx5.android.input.voice.VoiceInputPreferences

class KeyboardSettingsFragment : ManagedPreferenceFragment(AppPrefs.getInstance().keyboard) {
    override fun onPreferenceUiCreated(screen: PreferenceScreen) {
        val context = screen.context
        val category = PreferenceCategory(context).apply {
            title = context.getString(R.string.voice_input_settings)
            isIconSpaceReserved = false
        }
        screen.addPreference(category)
        category.addPreference(secretPreference(R.string.voice_input_openai_key, VoiceInputPreferences.OpenAIKey))
        category.addPreference(secretPreference(R.string.voice_input_zhipu_key, VoiceInputPreferences.ZhipuKey))
        category.addPreference(EditTextPreference(context).apply {
            key = VoiceInputPreferences.Hotwords
            title = context.getString(R.string.voice_input_hotwords)
            summary = context.getString(R.string.voice_input_hotwords_summary)
            isIconSpaceReserved = false
            isSingleLineTitle = false
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                it.isSingleLine = false
            }
        })
    }

    private fun secretPreference(title: Int, keyValue: String) =
        EditTextPreference(requireContext()).apply {
            key = keyValue
            setTitle(title)
            isIconSpaceReserved = false
            isSingleLineTitle = false
            summaryProvider = Preference.SummaryProvider<EditTextPreference> { preference ->
                if (preference.text.isNullOrBlank()) {
                    getString(R.string.voice_input_key_missing)
                } else {
                    getString(R.string.voice_input_key_saved)
                }
            }
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                it.isSingleLine = true
            }
        }
}
