/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.voice

import timber.log.Timber
import java.io.File

object LocalMnnEngine {
    fun isReady() = LocalVoiceModel.isReady()

    fun transcribe(audio: File, precedingText: String, hotwords: List<String>): String {
        check(isReady()) { "The local MNN model is not installed" }
        val instruction = VoiceTranscriptionClient().transcriptionPrompt(precedingText, hotwords)
        Timber.d(
            "MNN request: config=%s audio=%s prompt=%s",
            LocalVoiceModel.configFile().absolutePath,
            audio.absolutePath,
            instruction
        )
        return runCatching {
            transcribeNative(
                LocalVoiceModel.configFile().absolutePath,
                audio.absolutePath,
                instruction
            ).trim()
        }.onSuccess {
            Timber.d("MNN response: %s", it)
        }.onFailure {
            Timber.e(it, "MNN response error")
        }.getOrThrow()
    }

    private external fun transcribeNative(
        configPath: String,
        audioPath: String,
        instruction: String
    ): String
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
