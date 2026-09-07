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
            systemPrompt(VoiceInputPreferences.hotwords())
        )
        Timber.d("MNN prewarm response: ready")
    }

    fun transcribe(
        audio: File,
        context: String,
        alreadyInput: String,
        hotwords: List<String>,
        targetLanguage: String,
        onPartial: (String) -> Unit
    ): String {
        check(isReady()) { "The local MNN model is not installed" }
        val instruction = userMessage(context, alreadyInput, audio.absolutePath, targetLanguage)
        Timber.d(
            "MNN request: config=%s audio=%s prompt=%s",
            LocalVoiceModel.configFile().absolutePath,
            audio.absolutePath,
            instruction
        )
        return runCatching {
            transcribeNative(
                LocalVoiceModel.configFile().absolutePath,
                instruction,
                systemPrompt(hotwords),
                PartialOutput(onPartial)
            ).decodeToString().trim()
        }.onSuccess {
            Timber.d("MNN response: %s", it)
        }.onFailure {
            Timber.e(it, "MNN response error")
        }.getOrThrow()
    }

    private external fun transcribeNative(
        configPath: String,
        instruction: String,
        systemPrompt: String,
        output: PartialOutput
    ): ByteArray

    private fun systemPrompt(hotwords: List<String>) = buildString {
        append("Try your best to output the spoken words with natural punctuation.")
        append("\nHotwords: ")
        append(hotwords.joinToString(", "))
    }

    private fun userMessage(
        context: String,
        alreadyInput: String,
        audioPath: String,
        targetLanguage: String
    ) = buildString {
        val isChinese = targetLanguage.startsWith("zh")
        append(if (isChinese) "上下文：" else "Context: ")
        append(context.takeLast(MaxContextChars))
        append(if (isChinese) "\n已输入：" else "\nAlready Inputed: ")
        append(alreadyInput)
        append(if (isChinese) "\n音频：" else "\nAudio: ")
        append("<audio>")
        append(audioPath)
        append("</audio>")
        append(if (isChinese) "\n输出：\n```" else "\nOutput:\n```")
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
