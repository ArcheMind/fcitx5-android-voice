/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceTranscriptionNormalizerTest {
    @Test
    fun removesQwenChineseWrapperAndTerminalToken() {
        val raw = "上传的音频内容是“上滑取消，我已经验证过了能工作”。" +
            "如果还有其他需要处理的音频内容，你可以随时告诉我哦。<eop>"

        assertEquals(
            "上滑取消，我已经验证过了能工作",
            VoiceTranscriptionNormalizer.normalize(raw)
        )
    }

    @Test
    fun preservesPlainTranscription() {
        assertEquals(
            "明天用小企鹅语音输入法测试 Fcitx5。",
            VoiceTranscriptionNormalizer.normalize("明天用小企鹅语音输入法测试 Fcitx5。<eop>")
        )
    }

    @Test
    fun streamsPlainTextWithoutWaitingForTheSentence() {
        assertEquals("今", VoiceTranscriptionNormalizer.preview("今"))
        assertEquals("今天天气", VoiceTranscriptionNormalizer.preview("今天天气"))
    }

    @Test
    fun hidesIncompleteControlTokensAndThinking() {
        for (raw in listOf("<", "<thi", "<think>internal", "<think>internal</thi")) {
            assertEquals("", VoiceTranscriptionNormalizer.preview(raw))
        }
        assertEquals("你好", VoiceTranscriptionNormalizer.preview("<think>internal</think>你好<|im_"))
        assertEquals("你好", VoiceTranscriptionNormalizer.preview("你好<eop>"))
    }

    @Test
    fun streamsWrapperContentsWithoutExplanations() {
        val intro = "上传的音频内容是“"
        for (end in 1..intro.length) {
            assertEquals("", VoiceTranscriptionNormalizer.preview(intro.take(end)))
        }
        assertEquals("你好", VoiceTranscriptionNormalizer.preview(intro + "你好"))
        assertEquals("你好", VoiceTranscriptionNormalizer.preview(intro + "你好”。如果还需要"))
        assertEquals("hello", VoiceTranscriptionNormalizer.preview("the audio says \"hello\". If you"))
        assertEquals("hello", VoiceTranscriptionNormalizer.preview("the uploaded audio is \"hello"))
        assertEquals("你好", VoiceTranscriptionNormalizer.preview("识别结果：你好"))
    }
}
