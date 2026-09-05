/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */
package org.fcitx.fcitx5.android.input.voice

import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object LocalVoiceModelDownloader {
    private const val BaseUrl =
        "https://huggingface.co/taobao-mnn/Qwen2.5-Omni-3B-MNN/resolve/main"

    fun download(onProgress: (downloaded: Long, total: Long) -> Unit) {
        val directory = LocalVoiceModel.directory().also(File::mkdirs)
        val total = LocalVoiceModel.Files.sumOf { it.size }
        LocalVoiceModel.Files.forEach { item ->
            val target = directory.resolve(item.name)
            if (target.length() == item.size) return@forEach
            if (target.length() > item.size) target.delete()
            val existing = target.length()
            val url = "$BaseUrl/${item.name}"
            Timber.d("Model download request: url=%s range=%d-", url, existing)
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
            }
            try {
                val status = connection.responseCode
                Timber.d("Model download response: url=%s status=%d headers=%s", url, status, connection.headerFields)
                check(status == HttpURLConnection.HTTP_OK || status == HttpURLConnection.HTTP_PARTIAL) {
                    "Model download HTTP $status: ${connection.errorStream?.bufferedReader()?.readText().orEmpty()}"
                }
                val append = existing > 0 && status == HttpURLConnection.HTTP_PARTIAL
                if (!append && existing > 0) target.delete()
                connection.inputStream.use { input ->
                    FileOutputStream(target, append).buffered().use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            val completed = LocalVoiceModel.Files.sumOf { file ->
                                directory.resolve(file.name).length().coerceAtMost(file.size)
                            }
                            onProgress(completed, total)
                        }
                    }
                }
            } catch (error: Throwable) {
                Timber.e(error, "Model download error: url=%s", url)
                throw error
            } finally {
                connection.disconnect()
            }
            check(target.length() == item.size) {
                "Incomplete model file ${item.name}: ${target.length()}/${item.size}"
            }
            onProgress(LocalVoiceModel.Files.sumOf { file ->
                directory.resolve(file.name).length().coerceAtMost(file.size)
            }, total)
        }
    }
}
