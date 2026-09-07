/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.voice

import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.TimeZone
import java.util.UUID

class VoiceTranscriptionClient {
    suspend fun transcribe(
        audio: File,
        precedingText: String,
        hotwords: List<String>,
        onPartial: (String) -> Unit
    ): String {
        if (VoiceInputPreferences.preferLocal() && LocalMnnEngine.isReady()) {
            return LocalMnnEngine.transcribe(audio, precedingText, hotwords, onPartial)
        }
        return if (isCurrentTimeZoneChina()) {
            transcribeWithZhipu(audio, precedingText, hotwords)
        } else {
            transcribeWithOpenAI(audio, precedingText, hotwords)
        }
    }

    private fun transcribeWithOpenAI(
        audio: File,
        precedingText: String,
        hotwords: List<String>
    ): String {
        val key = VoiceInputPreferences.openAIKey()
        require(key.isNotEmpty()) { "OpenAI API key is not configured" }
        val prompt = transcriptionPrompt(precedingText, hotwords)
        val audioBase64 = Base64.encodeToString(audio.readBytes(), Base64.NO_WRAP)
        val body = buildJsonObject {
            put("model", JsonPrimitive("gpt-audio-1.5"))
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", JsonPrimitive("text"))
                            put("text", JsonPrimitive(prompt))
                        })
                        add(buildJsonObject {
                            put("type", JsonPrimitive("input_audio"))
                            put("input_audio", buildJsonObject {
                                put("data", JsonPrimitive(audioBase64))
                                put("format", JsonPrimitive("wav"))
                            })
                        })
                    })
                })
            })
        }.toString()
        Timber.d(
            "OpenAI request: model=gpt-audio-1.5 prompt=%s audioBytes=%d",
            prompt,
            audio.length()
        )
        val response = postJson(OpenAIEndpoint, key, body)
        Timber.d("OpenAI response: %s", response)
        val root = Json.parseToJsonElement(response).jsonObject
        val content = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
        return content.orEmpty().trim()
    }

    private fun transcribeWithZhipu(
        audio: File,
        precedingText: String,
        hotwords: List<String>
    ): String {
        val key = VoiceInputPreferences.zhipuKey()
        require(key.isNotEmpty()) { "Zhipu API key is not configured" }
        val boundary = "FcitxVoice-${UUID.randomUUID()}"
        val prompt = precedingText.takeLast(MaxContextChars)
        Timber.d(
            "Zhipu request: model=glm-asr-2512 prompt=%s hotwords=%s audioBytes=%d",
            prompt,
            hotwords,
            audio.length()
        )
        val connection = openConnection(ZhipuEndpoint, key).apply {
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        connection.outputStream.use { raw ->
            val writer = BufferedWriter(OutputStreamWriter(raw, Charsets.UTF_8))
            fun field(name: String, value: String) {
                writer.append("--$boundary\r\n")
                writer.append("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                writer.append(value).append("\r\n")
            }
            field("model", "glm-asr-2512")
            if (prompt.isNotEmpty()) field("prompt", prompt)
            hotwords.forEach { field("hotwords", it) }
            writer.append("--$boundary\r\n")
            writer.append("Content-Disposition: form-data; name=\"file\"; filename=\"voice.wav\"\r\n")
            writer.append("Content-Type: audio/wav\r\n\r\n")
            writer.flush()
            audio.inputStream().use { it.copyTo(raw) }
            raw.write("\r\n--$boundary--\r\n".toByteArray())
        }
        val response = readResponse(connection)
        Timber.d("Zhipu response: %s", response)
        return Json.parseToJsonElement(response).jsonObject["text"]
            ?.jsonPrimitive?.contentOrNull.orEmpty().trim()
    }

    internal fun transcriptionPrompt(precedingText: String, hotwords: List<String>): String = buildString {
        append(contextPrompt(precedingText))
        append("\nHotwords: ")
        append(hotwords.joinToString(", "))
    }

    internal fun contextPrompt(precedingText: String) = "Context: ${precedingText.takeLast(MaxContextChars)}"

    private fun postJson(endpoint: String, key: String, body: String): String {
        val connection = openConnection(endpoint, key).apply {
            setRequestProperty("Content-Type", "application/json")
        }
        connection.outputStream.use { it.write(body.toByteArray()) }
        return readResponse(connection)
    }

    private fun openConnection(endpoint: String, key: String) =
        (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 90_000
            setRequestProperty("Authorization", "Bearer $key")
        }

    private fun readResponse(connection: HttpURLConnection): String {
        val status = connection.responseCode
        val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (status !in 200..299) {
            throw IllegalStateException("HTTP $status: $body")
        }
        return body
    }

    companion object {
        private const val OpenAIEndpoint = "https://api.openai.com/v1/chat/completions"
        private const val ZhipuEndpoint = "https://open.bigmodel.cn/api/paas/v4/audio/transcriptions"
        private const val MaxContextChars = 200
        private val ChinaTimeZones = setOf(
            "Asia/Shanghai",
            "Asia/Chongqing",
            "Asia/Harbin",
            "Asia/Urumqi",
            "Asia/Kashgar",
            "PRC"
        )

        fun isCurrentTimeZoneChina() = TimeZone.getDefault().id in ChinaTimeZones
    }
}
