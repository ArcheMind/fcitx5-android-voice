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
            "Try your best to output the exact spoken words with natural punctuation.\n" +
                "Hotwords: Fcitx5, 小企鹅",
            LocalMnnEngine.systemPrompt(listOf("Fcitx5", "小企鹅"))
        )
    }

    @Test
    fun buildsEnglishMessages() {
        assertEquals("Context: preceding text", LocalMnnEngine.contextMessage("preceding text", "en"))
        assertEquals(
            "Audio: <audio>/tmp/voice.wav</audio>",
            LocalMnnEngine.audioMessage("/tmp/voice.wav", "en")
        )
    }

    @Test
    fun buildsChineseMessages() {
        assertEquals("上下文：前文", LocalMnnEngine.contextMessage("前文", "zh_CN"))
        assertEquals(
            "音频：<audio>/tmp/voice.wav</audio>",
            LocalMnnEngine.audioMessage("/tmp/voice.wav", "zh_CN")
        )
    }
}
