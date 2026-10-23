package io.github.xechoz.offlineweb.internal.index

import io.github.xechoz.offlineweb.ExpiryPolicy
import io.github.xechoz.offlineweb.internal.protocol.Release
import java.io.File
import java.net.URI

internal data class IndexSource(
    val release: Release,
    val webRoot: File,
)

internal sealed interface ResolveResult {
    data class Hit(
        val file: File,
        val mime: String,
        val charset: String?,
        val packageId: String,
        val version: Int,
    ) : ResolveResult

    data class Missing(
        val packageId: String,
        val version: Int,
        val url: String,
    ) : ResolveResult

    data class Expired(
        val packageId: String,
        val version: Int,
    ) : ResolveResult

    data object NotMine : ResolveResult
}

internal class OfflineIndex private constructor(
    private val bindings: List<RouteBinding>,
) {

    fun resolve(
        url: String,
        isMainFrame: Boolean,
        now: Long,
        expiryPolicy: ExpiryPolicy,
    ): ResolveResult {
        val uri = runCatching { URI(url) }.getOrNull() ?: return ResolveResult.NotMine
        val scheme = uri.scheme?.lowercase() ?: return ResolveResult.NotMine
        if (scheme != "http" && scheme != "https") return ResolveResult.NotMine
        val origin = originOf(uri) ?: return ResolveResult.NotMine
        val path = uri.path ?: "/"

        val binding = bindings
            .asSequence()
            .filter { it.origin == origin && pathMatches(path, it.basePath) }
            .maxByOrNull { it.basePath.length }
            ?: return ResolveResult.NotMine

        if (binding.expiresAt != null &&
            now >= binding.expiresAt &&
            expiryPolicy == ExpiryPolicy.STRICT
        ) {
            return ResolveResult.Expired(binding.packageId, binding.version)
        }

        var relative = relativePath(path, binding.basePath)
        if (relative.isEmpty()) relative = "index.html"
        if (relative.endsWith("/")) relative += "index.html"

        if (!isNormalized(relative)) {
            return ResolveResult.NotMine
        }

        var requestedPath = relative
        var file = binding.files[requestedPath]
        if (file == null) {
            if (isMainFrame && binding.spaFallback) {
                requestedPath = "index.html"
                file = binding.files[requestedPath]
            }
            if (file == null) {
                return ResolveResult.Missing(binding.packageId, binding.version, url)
            }
        }

        val mime = MimeTypes.mimeOf(requestedPath)
        return ResolveResult.Hit(
            file = file,
            mime = mime,
            charset = MimeTypes.charsetOf(mime),
            packageId = binding.packageId,
            version = binding.version,
        )
    }

    companion object {
        fun build(sources: List<IndexSource>): OfflineIndex {
            val bindings = ArrayList<RouteBinding>()
            for (source in sources) {
                val files = source.release.files.mapNotNull { info ->
                    val file = File(source.webRoot, info.path)
                    if (file.isFile) info.path to file else null
                }.toMap()
                if (files.isEmpty()) continue
                for (route in source.release.routes) {
                    val origin = originOf(route.origin) ?: continue
                    bindings += RouteBinding(
                        origin = origin,
                        basePath = normalizeBasePath(route.basePath),
                        spaFallback = route.spaFallback,
                        files = files,
                        packageId = source.release.packageId,
                        version = source.release.version,
                        expiresAt = source.release.expiresAt,
                    )
                }
            }
            return OfflineIndex(bindings)
        }

        fun empty(): OfflineIndex = OfflineIndex(emptyList())

        private fun pathMatches(path: String, basePath: String): Boolean {
            if (basePath == "/") return true
            return path == basePath.trimEnd('/') || path.startsWith(basePath)
        }

        private fun relativePath(path: String, basePath: String): String {
            val base = if (basePath == "/") "/" else basePath
            val rest = path.removePrefix(base)
            return if (rest == path) "" else rest
        }

        private fun normalizeBasePath(value: String): String {
            var path = value.trim()
            if (path.isEmpty()) return "/"
            if (!path.startsWith("/")) path = "/$path"
            if (!path.endsWith("/")) path += "/"
            return path
        }

        private fun isNormalized(path: String): Boolean =
            path.split('/').none { it == "." || it == ".." }

        private fun originOf(uri: URI): String? {
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host?.lowercase() ?: return null
            val defaultPort = if (scheme == "https") 443 else 80
            val port = if (uri.port == -1) defaultPort else uri.port
            return if (port == defaultPort) "$scheme://$host" else "$scheme://$host:$port"
        }

        private fun originOf(raw: String): String? {
            val withScheme = if (raw.contains("://")) raw else "https://$raw"
            val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
            return originOf(uri)
        }
    }

    private data class RouteBinding(
        val origin: String,
        val basePath: String,
        val spaFallback: Boolean,
        val files: Map<String, File>,
        val packageId: String,
        val version: Int,
        val expiresAt: Long?,
    )
}
