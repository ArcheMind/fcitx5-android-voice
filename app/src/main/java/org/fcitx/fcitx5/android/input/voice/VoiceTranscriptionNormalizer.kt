/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.voice

object VoiceTranscriptionNormalizer {
    private val terminalTokens = Regex("<eop>|<\\|im_end\\|>|<\\|endoftext\\|>")
    private val thinking = Regex("<think>.*?</think>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val chineseQuotedWrapper = Regex(
        "^(?:上传的)?音频(?:内容)?(?:是|为|中说的是)[：:]?\\s*[“\"](.*)[”\"]" +
            "(?:[。.]?\\s*(?:如果|如有).*)?$",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val englishQuotedWrapper = Regex(
        "^(?:the )?(?:uploaded )?audio(?: content)?(?: says| is| transcription is)?[：:]?\\s*[“\"](.*)[”\"]" +
            "(?:[。.]?\\s*(?:if|let me know).*)?$",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val resultPrefix = Regex("^(?:转写|识别)(?:结果|文本)?[：:]\\s*")

    fun normalize(raw: String): String {
        var text = raw.replace(thinking, "").replace(terminalTokens, "").trim()
        text = chineseQuotedWrapper.matchEntire(text)?.groupValues?.get(1)
            ?: englishQuotedWrapper.matchEntire(text)?.groupValues?.get(1)
            ?: text.replaceFirst(resultPrefix, "")
        return text.trim()
    }
}
