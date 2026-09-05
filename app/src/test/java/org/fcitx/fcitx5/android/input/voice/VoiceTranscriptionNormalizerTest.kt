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
}
