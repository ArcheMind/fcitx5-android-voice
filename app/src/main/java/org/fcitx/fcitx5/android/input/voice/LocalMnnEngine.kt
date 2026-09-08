/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.voice

import androidx.annotation.Keep
import timber.log.Timber
import java.io.File
import java.nio.charset.CharacterCodingException

object LocalMnnEngine {
    fun isReady() = LocalVoiceModel.isReady()

    fun prewarm() {
        check(isReady()) { "The local MNN model is not installed" }
        Timber.d("MNN prewarm request: config=%s", LocalVoiceModel.configFile().absolutePath)
        prewarmNative(
            LocalVoiceModel.configFile().absolutePath,
            systemPrompt()
        )
        Timber.d("MNN prewarm response: ready")
    }

    fun transcribe(
        audio: File,
        targetLanguage: String,
        onPartial: (String) -> Unit
    ): String {
        check(isReady()) { "The local MNN model is not installed" }
        val instruction = audioMessage(audio.absolutePath, targetLanguage)
        Timber.d(
            "MNN request: config=%s audio=%s prompt=%s",
            LocalVoiceModel.configFile().absolutePath,
            audio.absolutePath,
            instruction
        )
        return runCatching {
            transcribeNative(
                instruction,
                PartialOutput(onPartial)
            ).decodeToString().trim()
        }.onSuccess {
            Timber.d("MNN response: %s", it)
        }.onFailure {
            Timber.e(it, "MNN response error")
        }.getOrThrow()
    }

    private external fun transcribeNative(audioMessage: String, output: PartialOutput): ByteArray

    internal fun systemPrompt() = """
        Transcribe audio exactly.
        Examples:
        <- "What is one plus one?"
        -> "What is one plus one?"
        <- "然后给妈妈"
        -> "然后给妈妈"
        <- "打一个电话，问要不要带伞。"
        -> "打一个电话，问要不要带伞。"
        Output only the spoken words with punctuation
    """.trimIndent()

    internal fun contextMessage(context: String, hotwords: List<String>, targetLanguage: String) = buildString {
        if (targetLanguage.startsWith("zh")) {
            append("用户常用词: ")
            append(hotwords.joinToString(", "))
            append("\n上下文：")
        } else {
            append("User hotwords: ")
            append(hotwords.joinToString(", "))
            append("\nContext: ")
        }
        append(context.takeLast(MaxContextChars))
    }

    internal fun audioMessage(audioPath: String, targetLanguage: String) =
        (if (targetLanguage.startsWith("zh")) "音频：" else "Audio: ") +
            "<audio>$audioPath</audio>"

    fun beginSession(context: String, hotwords: List<String>, targetLanguage: String) {
        check(isReady()) { "The local MNN model is not installed" }
        val contextMessage = contextMessage(context, hotwords, targetLanguage)
        val systemPrompt = systemPrompt()
        Timber.d("MNN begin session request: system=%s context=%s", systemPrompt, contextMessage)
        beginSessionNative(
            LocalVoiceModel.configFile().absolutePath,
            systemPrompt,
            contextMessage
        )
        Timber.d("MNN begin session response: ready")
    }

    fun commitTranscript(transcript: String) {
        Timber.d("MNN commit transcript request: transcript=%s", transcript)
        commitTranscriptNative(transcript)
        Timber.d("MNN commit transcript response: committed")
    }

    fun endSession() {
        Timber.d("MNN end session request")
        endSessionNative()
        Timber.d("MNN end session response: ended")
    }

    @Keep
    class PartialOutput(private val accept: (String) -> Unit) {
        fun onPartial(bytes: ByteArray) {
            val text = try {
                bytes.decodeToString(throwOnInvalidSequence = true)
            } catch (_: CharacterCodingException) {
                // A token can end inside a UTF-8 character; wait for the next snapshot.
                return
            }
            Timber.d("MNN partial response: %s", text)
            accept(text)
        }
    }

    fun unload() {
        Timber.d("MNN unload request")
        unloadNative()
        Timber.d("MNN unload response: released")
    }

    private external fun prewarmNative(configPath: String, systemPrompt: String)
    private external fun beginSessionNative(configPath: String, systemPrompt: String, contextMessage: String)
    private external fun commitTranscriptNative(transcript: String)
    private external fun endSessionNative()
    private external fun unloadNative()

    private const val MaxContextChars = 200
}

object LocalVoiceModel {
    private val directory: File
        get() = org.fcitx.fcitx5.android.utils.appContext.filesDir
            .resolve("models/Qwen2.5-Omni-3B-MNN")

    fun configFile() = directory.resolve("config.json")

    fun isReady() = Files.all { directory.resolve(it.name).length() == it.size }

    fun directory() = directory

    data class ModelFile(val name: String, val size: Long)

    val Files = listOf(
        ModelFile("config.json", 586),
        ModelFile("llm_config.json", 2_188),
        ModelFile("tokenizer.txt", 3_193_458),
        ModelFile("embeddings_bf16.bin", 622_329_856),
        ModelFile("llm.mnn", 573_936),
        ModelFile("llm.mnn.json", 2_407_046),
        ModelFile("llm.mnn.weight", 1_737_291_202),
        ModelFile("audio.mnn", 440_544),
        ModelFile("audio.mnn.weight", 368_027_842)
    )
}
