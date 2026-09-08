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
            "Transcribe audio exactly.\n" +
                "Examples:\n" +
                "<- \"What is one plus one\"\n" +
                "-> \"What is one plus one?\"\n" +
                "<- \"然后给妈妈\"\n" +
                "-> \"然后给妈妈\"\n" +
                "<- \"打一个电话问要不要带伞。\"\n" +
                "-> \"打一个电话，问要不要带伞。\"\n" +
                "Output only the spoken words with punctuation",
            LocalMnnEngine.systemPrompt()
        )
    }

    @Test
    fun buildsEnglishMessages() {
        assertEquals(
            "User hotwords: OpenAI, Fcitx\nContext: preceding text",
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
            "用户常用词: OpenAI, Fcitx\n上下文：前文",
            LocalMnnEngine.contextMessage("前文", listOf("OpenAI", "Fcitx"), "zh_CN")
        )
        assertEquals(
            "音频：<audio>/tmp/voice.wav</audio>",
            LocalMnnEngine.audioMessage("/tmp/voice.wav", "zh_CN")
        )
    }
}
