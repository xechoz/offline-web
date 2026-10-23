package io.github.xechoz.offlineweb.internal.index

internal object MimeTypes {

    private val types = mapOf(
        "html" to "text/html",
        "htm" to "text/html",
        "js" to "text/javascript",
        "mjs" to "text/javascript",
        "cjs" to "text/javascript",
        "css" to "text/css",
        "json" to "application/json",
        "map" to "application/json",
        "xml" to "application/xml",
        "txt" to "text/plain",
        "csv" to "text/csv",
        "svg" to "image/svg+xml",
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "avif" to "image/avif",
        "ico" to "image/x-icon",
        "bmp" to "image/bmp",
        "woff" to "font/woff",
        "woff2" to "font/woff2",
        "ttf" to "font/ttf",
        "otf" to "font/otf",
        "eot" to "application/vnd.ms-fontobject",
        "wasm" to "application/wasm",
        "mp4" to "video/mp4",
        "webm" to "video/webm",
        "mp3" to "audio/mpeg",
        "wav" to "audio/wav",
        "ogg" to "audio/ogg",
        "pdf" to "application/pdf",
        "webmanifest" to "application/manifest+json",
        "zip" to "application/zip",
    )

    fun mimeOf(path: String): String {
        val extension = path.substringAfterLast('.', "").lowercase()
        return types[extension] ?: "application/octet-stream"
    }

    fun charsetOf(mime: String): String? = when {
        mime.startsWith("text/") -> "UTF-8"
        mime == "application/json" -> "UTF-8"
        mime == "application/manifest+json" -> "UTF-8"
        mime == "image/svg+xml" -> "UTF-8"
        else -> null
    }
}
