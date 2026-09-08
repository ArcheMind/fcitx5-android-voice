/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalMnnEnginePromptTest {
    @Test
    fun buildsFixedSystemPrompt() {
        assertEquals(
            "Transcribe audio, output the spoken words with punctuation\n" +
                "Examples:\n" +
                "<- \"one plus one?\"\n" +
                "-> \"one plus one?\"\n" +
                "<- \"给妈妈\"\n" +
                "-> \"给妈妈\"\n" +
                "<- \"打电话问要不要伞\"\n" +
                "-> \"打电话，问要不要伞。\"",
            LocalMnnEngine.systemPrompt()
        )
    }

    @Test
    fun buildsEnglishMessages() {
        assertEquals(
            "User words: OpenAI, Fcitx\nContext: preceding text",
            LocalMnnEngine.contextMessage("preceding text", listOf("OpenAI", "Fcitx"), "en")
        )
        assertEquals(
            "Audio: <audio>/tmp/voice.wav</audio>",
            LocalMnnEngine.audioMessage("/tmp/voice.wav", "en")
        )
    }

    @Test
    fun buildsChineseMessages() {
        assertEquals(
            "用户词: OpenAI, Fcitx\n上下文：前文",
            LocalMnnEngine.contextMessage("前文", listOf("OpenAI", "Fcitx"), "zh_CN")
        )
        assertEquals(
            "音频：<audio>/tmp/voice.wav</audio>",
            LocalMnnEngine.audioMessage("/tmp/voice.wav", "zh_CN")
        )
    }
}
