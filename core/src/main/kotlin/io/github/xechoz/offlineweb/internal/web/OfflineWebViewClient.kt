package io.github.xechoz.offlineweb.internal.web

import android.graphics.Bitmap
import android.os.Build
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import io.github.xechoz.offlineweb.ExpiryPolicy
import io.github.xechoz.offlineweb.MissingResourcePolicy
import io.github.xechoz.offlineweb.OfflineEvent
import io.github.xechoz.offlineweb.internal.index.OfflineIndex
import io.github.xechoz.offlineweb.internal.index.ResolveResult
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream

internal class OfflineWebViewClient(
    private val delegate: WebViewClient?,
    private val missingResource: MissingResourcePolicy,
    private val expiryPolicy: ExpiryPolicy,
    private val indexProvider: () -> OfflineIndex,
    private val onEvent: (OfflineEvent) -> Unit,
) : WebViewClient() {

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): WebResourceResponse? {
        delegate?.shouldInterceptRequest(view, request)?.let { return it }

        if (!request.method.equals("GET", ignoreCase = true)) return null
        val scheme = request.url.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null

        val url = request.url.toString()
        return when (val result = indexProvider().resolve(
            url = url,
            isMainFrame = request.isForMainFrame,
            now = System.currentTimeMillis(),
            expiryPolicy = expiryPolicy,
        )) {
            is ResolveResult.Hit -> {
                onEvent(
                    OfflineEvent(
                        kind = OfflineEvent.Kind.HIT,
                        message = "offline hit",
                        url = url,
                        packageId = result.packageId,
                        version = result.version,
                    ),
                )
                responseFor(request, result)
            }

            is ResolveResult.Missing -> {
                onEvent(
                    OfflineEvent(
                        kind = OfflineEvent.Kind.MISS,
                        message = "missing in offline package",
                        url = url,
                        packageId = result.packageId,
                        version = result.version,
                    ),
                )
                if (missingResource == MissingResourcePolicy.ERROR) {
                    notFoundResponse()
                } else {
                    null
                }
            }

            is ResolveResult.Expired -> {
                onEvent(
                    OfflineEvent(
                        kind = OfflineEvent.Kind.EXPIRED,
                        message = "offline package expired",
                        url = url,
                        packageId = result.packageId,
                        version = result.version,
                    ),
                )
                null
            }

            ResolveResult.NotMine -> null
        }
    }

    private fun responseFor(request: WebResourceRequest, hit: ResolveResult.Hit): WebResourceResponse {
        val file = hit.file
        val length = file.length()
        val headers = mutableMapOf(
            "Cache-Control" to "no-store",
            "Accept-Ranges" to "bytes",
            "X-Offline-Web" to "${hit.packageId}/${hit.version}",
        )

        val rangeHeader = request.requestHeaders?.get("Range")
        if (rangeHeader.isNullOrBlank()) {
            headers["Content-Length"] = length.toString()
            return WebResourceResponse(
                hit.mime,
                hit.charset,
                200,
                "OK",
                headers,
                FileInputStream(file),
            )
        }

        val range = parseRange(rangeHeader, length)
        if (range == null) {
            headers["Content-Range"] = "bytes */$length"
            return WebResourceResponse(
                "text/plain",
                "UTF-8",
                416,
                "Range Not Satisfiable",
                headers,
                ByteArrayInputStream(ByteArray(0)),
            )
        }

        val (start, end) = range
        headers["Content-Range"] = "bytes $start-$end/$length"
        headers["Content-Length"] = (end - start + 1).toString()
        val stream = FileInputStream(file).let { input ->
            input.channel.position(start)
            RangeInputStream(input, end - start + 1)
        }
        return WebResourceResponse(
            hit.mime,
            hit.charset,
            206,
            "Partial Content",
            headers,
            stream,
        )
    }

    private fun notFoundResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "UTF-8",
        404,
        "Not Found",
        mapOf("Cache-Control" to "no-store"),
        ByteArrayInputStream("offline resource not found".toByteArray()),
    )

    /** Parses a single byte range. Returns null when the header is not satisfiable. */
    private fun parseRange(header: String, length: Long): Pair<Long, Long>? {
        if (length <= 0) return null
        val value = header.trim()
        if (!value.startsWith("bytes=") || value.contains(',')) return null
        val spec = value.removePrefix("bytes=").trim()
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val startText = spec.substring(0, dash).trim()
        val endText = spec.substring(dash + 1).trim()

        return try {
            if (startText.isEmpty()) {
                val suffix = endText.toLong()
                if (suffix <= 0) return null
                val start = (length - suffix).coerceAtLeast(0)
                start to (length - 1)
            } else {
                val start = startText.toLong()
                if (start < 0 || start >= length) return null
                val end = if (endText.isEmpty()) length - 1 else endText.toLong()
                if (end < start) return null
                start to end.coerceAtMost(length - 1)
            }
        } catch (_: NumberFormatException) {
            null
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            delegate?.shouldOverrideUrlLoading(view, request) ?: false
        } else {
            false
        }

    @Suppress("DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
        delegate?.shouldOverrideUrlLoading(view, url) ?: false

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        delegate?.onPageStarted(view, url, favicon)
    }

    override fun onPageFinished(view: WebView, url: String) {
        delegate?.onPageFinished(view, url)
    }

    override fun onLoadResource(view: WebView, url: String) {
        delegate?.onLoadResource(view, url)
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
        delegate?.doUpdateVisitedHistory(view, url, isReload)
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            delegate?.onReceivedError(view, request, error)
        }
    }

    @Suppress("DEPRECATION")
    override fun onReceivedError(
        view: WebView,
        errorCode: Int,
        description: String,
        failingUrl: String,
    ) {
        delegate?.onReceivedError(view, errorCode, description, failingUrl)
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            delegate?.onReceivedHttpError(view, request, errorResponse)
        }
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        delegate?.onReceivedSslError(view, handler, error) ?: super.onReceivedSslError(view, handler, error)
    }

    private class RangeInputStream(
        input: InputStream,
        private var remaining: Long,
    ) : FilterInputStream(input) {

        override fun read(): Int {
            if (remaining <= 0) return -1
            val value = super.read()
            if (value >= 0) remaining--
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0) return -1
            val toRead = minOf(length.toLong(), remaining).toInt()
            val read = super.read(buffer, offset, toRead)
            if (read > 0) remaining -= read
            return read
        }

        override fun skip(count: Long): Long {
            val skipped = super.skip(minOf(count, remaining))
            remaining -= skipped
            return skipped
        }

        override fun available(): Int = minOf(super.available().toLong(), remaining).toInt()
    }
}
