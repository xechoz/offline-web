package io.github.xechoz.offlineweb

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import io.github.xechoz.offlineweb.internal.download.DownloadException
import io.github.xechoz.offlineweb.internal.download.OkHttpDownloader
import io.github.xechoz.offlineweb.internal.index.IndexSource
import io.github.xechoz.offlineweb.internal.index.OfflineIndex
import io.github.xechoz.offlineweb.internal.install.ZipExtractor
import io.github.xechoz.offlineweb.internal.install.ZipRejectedException
import io.github.xechoz.offlineweb.internal.protocol.Release
import io.github.xechoz.offlineweb.internal.protocol.ReleaseResponse
import io.github.xechoz.offlineweb.internal.protocol.ReleaseVerifier
import io.github.xechoz.offlineweb.internal.protocol.RemotePackageDto
import io.github.xechoz.offlineweb.internal.protocol.ProtocolException
import io.github.xechoz.offlineweb.internal.protocol.protocolJson
import io.github.xechoz.offlineweb.internal.protocol.sha256Hex
import io.github.xechoz.offlineweb.internal.store.OfflineStore
import java.io.File
import java.io.IOException
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.ByteString.Companion.toByteString

class OfflineManager internal constructor(
    private val context: Context,
    internal val config: OfflineConfig,
) {

    private val store = OfflineStore(context, config.appKey)
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val downloader = OkHttpDownloader(http)
    private val mutex = Mutex()
    private val etags = ConcurrentHashMap<String, String>()

    private val _installed = MutableStateFlow<List<InstalledPackage>>(emptyList())
    val installed: StateFlow<List<InstalledPackage>> = _installed.asStateFlow()

    private val _events = MutableSharedFlow<OfflineEvent>(
        extraBufferCapacity = 128,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<OfflineEvent> = _events.asSharedFlow()

    @Volatile
    internal var index: OfflineIndex = OfflineIndex.empty()
        private set

    val storageUsageBytes: Long get() = store.usageBytes()

    init {
        initialize()
    }

    /**
     * Checks the server for a newer release. Pass null to update every installed package.
     */
    suspend fun update(packageId: String? = null): UpdateResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val targets = if (packageId != null) {
                listOf(packageId)
            } else {
                _installed.value.map { it.packageId }
            }
            if (targets.isEmpty()) return@withContext UpdateResult.Skipped("no installed packages")

            var last: UpdateResult = UpdateResult.Skipped("nothing to do")
            for (id in targets) {
                last = updateOne(id)
                if (last !is UpdateResult.UpToDate) return@withContext last
            }
            last
        }
    }

    suspend fun remove(packageId: String) = mutex.withLock {
        withContext(Dispatchers.IO) {
            store.remove(packageId)
            refresh()
        }
    }

    suspend fun clear() = mutex.withLock {
        withContext(Dispatchers.IO) {
            store.clear()
            refresh()
        }
    }

    suspend fun availablePackages(): List<RemotePackage> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "$endpoint/api/v1/apps/${config.appKey}/packages"
            val request = Request.Builder()
                .url(url)
                .header("X-App-Key", config.appKey)
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()
                val items = protocolJson.decodeFromString(
                    ListSerializer(RemotePackageDto.serializer()),
                    body,
                )
                items.map { dto ->
                    RemotePackage(
                        packageId = dto.packageId,
                        name = dto.name,
                        version = dto.version,
                        versionName = dto.versionName,
                        expiresAt = dto.expiresAt,
                        size = dto.size,
                    )
                }
            }
        }.getOrElse {
            emptyList()
        }
    }

    internal fun emitEvent(event: OfflineEvent) {
        _events.tryEmit(event)
    }

    private suspend fun updateOne(packageId: String): UpdateResult {
        if (!isNetworkAllowed()) {
            return UpdateResult.Skipped("network policy requires an unmetered connection")
        }
        if (endpoint.startsWith("http://", ignoreCase = true) && !config.allowCleartext) {
            return UpdateResult.Failed(OfflineError.Network("cleartext HTTP is disabled"))
        }

        return try {
            fetchRelease(packageId).use { response ->
                when (response.code) {
                    304 -> {
                        val version = store.activeVersion(packageId) ?: 0
                        UpdateResult.UpToDate(packageId, version)
                    }

                    410 -> {
                        store.remove(packageId)
                        refresh()
                        emitEvent(
                            OfflineEvent(
                                OfflineEvent.Kind.REVOKED,
                                "package revoked",
                                packageId = packageId,
                            ),
                        )
                        UpdateResult.Revoked(packageId)
                    }

                    404 -> UpdateResult.Failed(OfflineError.Http(404, "package not found: $packageId"))

                    in 200..299 -> {
                        val body = response.body?.string() ?: throw IOException("empty release response")
                        val signed = protocolJson.decodeFromString(ReleaseResponse.serializer(), body)
                        val verified = ReleaseVerifier.verify(signed, config)
                        val release = verified.release
                        if (release.packageId != packageId) {
                            throw ProtocolException("release packageId mismatch")
                        }
                        if (release.minClientVersion > config.clientVersion) {
                            UpdateResult.Failed(
                                OfflineError.UnsupportedVersion(
                                    "release requires client ${release.minClientVersion}",
                                ),
                            )
                        } else {
                            val expiresAt = release.expiresAt
                            if (expiresAt != null && System.currentTimeMillis() >= expiresAt) {
                                UpdateResult.Failed(OfflineError.Expired(packageId))
                            } else {
                                val current = store.activeVersion(packageId) ?: 0
                                if (release.version <= current) {
                                    UpdateResult.UpToDate(packageId, current)
                                } else {
                                    install(packageId, verified, signed.etag)
                                    UpdateResult.Installed(packageId, release.version)
                                }
                            }
                        }
                    }

                    else -> UpdateResult.Failed(OfflineError.Http(response.code))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val error = mapError(e)
            emitEvent(
                OfflineEvent(
                    kind = OfflineEvent.Kind.ERROR,
                    message = error.message,
                    packageId = packageId,
                ),
            )
            config.logger?.invoke("update $packageId failed: ${error.message}")
            UpdateResult.Failed(error)
        }
    }

    private suspend fun install(packageId: String, verified: io.github.xechoz.offlineweb.internal.protocol.VerifiedRelease, etag: String) {
        val release = verified.release
        val archiveUrl = resolveArchiveUrl(release)
        val tmp = File(store.root, "tmp/download-$packageId-${release.version}.zip")

        val required = release.archive.size * 2 + 8L * 1024 * 1024
        val usable = store.root.usableSpace
        if (release.archive.size > config.maxStorageBytes) {
            throw DownloadException(507, "package exceeds maxStorageBytes")
        }
        if (usable in 1 until required) {
            throw DownloadException(507, "not enough disk space")
        }

        try {
            var attempt = 0
            while (true) {
                try {
                    downloader.download(archiveUrl, tmp, release.archive.size) { downloaded, total ->
                        config.logger?.invoke("download $packageId: $downloaded/$total")
                    }
                    break
                } catch (e: DownloadException) {
                    throw e
                } catch (e: IOException) {
                    attempt++
                    if (attempt >= 3) throw e
                    delay(400L * attempt)
                }
            }
            val actualHash = sha256Hex(tmp)
            if (!actualHash.equals(release.archive.sha256, ignoreCase = true)) {
                throw HashMismatchException("archive hash mismatch")
            }

            val staging = store.newStagingDir()
            try {
                try {
                    ZipExtractor.extract(tmp, release.files, staging)
                } catch (e: ProtocolException) {
                    throw ZipRejectedException(e.message ?: "invalid package")
                } catch (e: Exception) {
                    throw ZipRejectedException(e.message ?: "invalid package")
                }
                store.activate(
                    packageId = packageId,
                    version = release.version,
                    releaseBytes = verified.bytes,
                    signature = verified.signature,
                    staging = staging,
                )
            } catch (e: Throwable) {
                staging.deleteRecursively()
                throw e
            }
            etag.takeIf { it.isNotBlank() }?.let { etags[packageId] = it }
            refresh()
            emitEvent(
                OfflineEvent(
                    kind = OfflineEvent.Kind.UPDATE,
                    message = "installed ${release.versionName.ifBlank { release.version.toString() }}",
                    packageId = packageId,
                    version = release.version,
                ),
            )
        } finally {
            tmp.delete()
        }
    }

    private fun fetchRelease(packageId: String): okhttp3.Response {
        val request = Request.Builder()
            .url("$endpoint/api/v1/apps/${config.appKey}/packages/$packageId/release")
            .header("X-App-Key", config.appKey)
            .apply { etags[packageId]?.let { header("If-None-Match", it) } }
            .build()
        // The caller closes the body via response.body?.string(); headers/status are used afterwards.
        return http.newCall(request).execute()
    }

    private fun resolveArchiveUrl(release: Release): String {
        val raw = release.archive.url
        return if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) {
            raw
        } else {
            URL(URL("$endpoint/"), raw.trimStart('/')).toString()
        }
    }

    private val endpoint: String = config.endpoint.trimEnd('/')

    private fun initialize() {
        store.cleanupTmp()
        for (packageId in store.packageIds()) {
            val healthy = recoverPackage(packageId)
            if (!healthy) {
                store.remove(packageId)
                emitEvent(
                    OfflineEvent(
                        kind = OfflineEvent.Kind.ERROR,
                        message = "discarded invalid package",
                        packageId = packageId,
                    ),
                )
            }
        }
        refresh()
    }

    private fun recoverPackage(packageId: String): Boolean {
        val active = store.activeVersion(packageId)
        if (active != null && isHealthy(packageId, active)) return true

        val previous = store.previousVersion(packageId)
        if (previous != null && isHealthy(packageId, previous)) {
            store.writeActivePointer(packageId, previous)
            emitEvent(
                OfflineEvent(
                    kind = OfflineEvent.Kind.ERROR,
                    message = "active package invalid, restored previous version",
                    packageId = packageId,
                    version = previous,
                ),
            )
            return true
        }
        return false
    }

    private fun isHealthy(packageId: String, version: Int): Boolean {
        val bytes = store.readReleaseBytes(packageId, version) ?: return false
        val signature = store.readSignature(packageId, version) ?: return false
        val release = runCatching {
            ReleaseVerifier.verify(
                ReleaseResponse(
                    release = bytes.toByteString().base64(),
                    signature = signature,
                ),
                config,
            ).release
        }.getOrNull() ?: return false
        return store.verifyInstalled(packageId, version, release, config.verifyOnStartup)
    }

    private fun refresh() {
        _installed.value = store.installedPackages()
        val sources = ArrayList<IndexSource>()
        for (pkg in _installed.value) {
            val release = store.readRelease(pkg.packageId, pkg.version) ?: continue
            sources += IndexSource(release, store.webRoot(pkg.packageId, pkg.version))
        }
        index = OfflineIndex.build(sources)
    }

    @Suppress("DEPRECATION")
    private fun isNetworkAllowed(): Boolean {
        if (config.networkPolicy == NetworkPolicy.ANY) return true
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val info = manager.activeNetworkInfo ?: return false
        if (!info.isConnected) return false
        return when (config.networkPolicy) {
            NetworkPolicy.ANY -> true
            NetworkPolicy.UNMETERED ->
                info.type == ConnectivityManager.TYPE_WIFI ||
                    info.type == ConnectivityManager.TYPE_ETHERNET
            NetworkPolicy.NOT_ROAMING -> !info.isRoaming
        }
    }

    private fun mapError(error: Throwable): OfflineError = when (error) {
        is DownloadException -> OfflineError.Http(error.code, error.message ?: "download failed")
        is ZipRejectedException -> OfflineError.ZipRejected(error.message ?: "zip rejected")
        is HashMismatchException -> OfflineError.HashMismatch(error.message ?: "hash mismatch")
        is ProtocolException -> OfflineError.Signature(error.message ?: "protocol error")
        is IOException -> OfflineError.Network(error.message ?: "network error", error)
        else -> OfflineError.Storage(error.message ?: "storage error", error)
    }
}

internal class HashMismatchException(message: String) : Exception(message)
