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
    private val openThinking = Regex("<think>.*$", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val wrapperStart = Regex(
        "^(?:(?:上传的)?音频(?:内容)?(?:是|为|中说的是)|" +
            "(?:the )?(?:uploaded )?audio(?: content)?(?: says| is| transcription is)?)[：:]?\\s*[“\"]",
        RegexOption.IGNORE_CASE
    )
    private val introductions = buildList {
        for (upload in listOf("", "上传的")) {
            for (content in listOf("", "内容")) {
                for (verb in listOf("是", "为", "中说的是")) add("${upload}音频$content$verb")
            }
        }
        for (article in listOf("", "the ")) {
            for (upload in listOf("", "uploaded ")) {
                for (content in listOf("", " content")) {
                    for (verb in listOf("", " says", " is", " transcription is")) add("${article}${upload}audio$content$verb")
                }
            }
        }
        for (verb in listOf("转写", "识别")) {
            for (suffix in listOf("", "结果", "文本")) add("$verb$suffix")
        }
    }

    fun preview(raw: String): String {
        var text = raw.replace(thinking, "").replace(openThinking, "").replace(terminalTokens, "")
        val tagStart = text.lastIndexOf('<')
        if (tagStart >= 0 && listOf("<think>", "<eop>", "<|im_end|>", "<|endoftext|>")
                .any { it.startsWith(text.substring(tagStart), ignoreCase = true) }) {
            text = text.substring(0, tagStart)
        }
        text = text.trim()
        val wrapper = wrapperStart.find(text)
        if (wrapper != null) {
            text = text.substring(wrapper.range.last + 1).substringBefore('”').substringBefore('"')
        } else if (introductions.any { it.startsWith(text.trimEnd('：', ':', ' '), ignoreCase = true) }) {
            return ""
        }
        return normalize(text)
    }

    fun normalize(raw: String): String {
        var text = raw.replace(thinking, "").replace(terminalTokens, "").trim()
        text = chineseQuotedWrapper.matchEntire(text)?.groupValues?.get(1)
            ?: englishQuotedWrapper.matchEntire(text)?.groupValues?.get(1)
            ?: text.replaceFirst(resultPrefix, "")
        return text.trim()
    }
}
