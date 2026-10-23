package io.github.xechoz.offlineweb.internal.download

import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

internal class DownloadException(val code: Int, message: String) : IOException(message)

internal class OkHttpDownloader(
    private val client: OkHttpClient,
) {

    suspend fun download(
        url: String,
        target: File,
        expectedSize: Long,
        onProgress: (downloaded: Long, total: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        if (target.exists()) target.delete()

        var attempt = 0
        while (true) {
            attempt++
            val existing = if (part.exists()) part.length() else 0L
            val request = Request.Builder()
                .url(url)
                .apply { if (existing > 0) header("Range", "bytes=$existing-") }
                .build()

            val response = client.newCall(request).execute()
            response.use { res ->
                if (res.code == 416 && existing > 0) {
                    part.delete()
                    if (attempt <= 2) return@use
                    throw DownloadException(res.code, "range not satisfiable")
                }
                if (!res.isSuccessful) {
                    if (res.code == 416) {
                        part.delete()
                        if (attempt <= 2) return@use
                    }
                    throw DownloadException(res.code, "download failed with HTTP ${res.code}")
                }

                val body = res.body ?: throw IOException("empty response body")
                val append = res.code == 206 && existing > 0
                if (!append && part.exists()) part.delete()

                val total = if (append) existing + body.contentLength() else body.contentLength()
                var downloaded = if (append) existing else 0L
                onProgress(downloaded, total)

                body.byteStream().use { input ->
                    part.outputStream().let { fileOut ->
                        fileOut.buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                                downloaded += read
                                onProgress(downloaded, if (total > 0) total else expectedSize)
                            }
                        }
                    }
                }

                if (part.length() != expectedSize) {
                    throw IOException("size mismatch after download: ${part.length()} != $expectedSize")
                }
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) {
                    throw IOException("cannot move downloaded package into place")
                }
                return@withContext target
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw IOException("download aborted")
    }
}
