package io.github.xechoz.offlineweb.internal.protocol

import io.github.xechoz.offlineweb.OfflineConfig
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.decodeBase64

internal const val SCHEMA_VERSION = 1
internal const val MAX_PACKAGE_BYTES = 200L * 1024 * 1024
internal const val MAX_FILES = 10_000
internal const val MAX_PATH_LENGTH = 512

internal val protocolJson = Json {
    ignoreUnknownKeys = true
    isLenient = false
    encodeDefaults = false
}

@Serializable
internal data class Release(
    val schemaVersion: Int = SCHEMA_VERSION,
    val keyId: String,
    val appKey: String,
    val packageId: String,
    val version: Int,
    val versionName: String = "",
    val createdAt: Long = 0L,
    val expiresAt: Long? = null,
    val minClientVersion: Int = 1,
    val entryUrl: String = "",
    val archive: ArchiveInfo,
    val routes: List<Route> = emptyList(),
    val files: List<FileInfo> = emptyList(),
)

@Serializable
internal data class ArchiveInfo(
    val url: String,
    val size: Long,
    val sha256: String,
)

@Serializable
internal data class Route(
    val origin: String,
    val basePath: String = "/",
    val spaFallback: Boolean = false,
)

@Serializable
internal data class FileInfo(
    val path: String,
    val size: Long,
    val sha256: String,
)

@Serializable
internal data class ReleaseResponse(
    val etag: String = "",
    val release: String,
    val signature: String,
)

@Serializable
internal data class RemotePackageDto(
    val packageId: String,
    val name: String = "",
    val version: Int? = null,
    val versionName: String = "",
    val expiresAt: Long? = null,
    val size: Long? = null,
)

internal data class VerifiedRelease(
    val bytes: ByteArray,
    val signature: String,
    val release: Release,
)

internal object ReleaseVerifier {

    fun verify(response: ReleaseResponse, config: OfflineConfig): VerifiedRelease {
        val releaseBytes = response.release.decodeBase64()?.toByteArray()
            ?: throw ProtocolException("release is not valid base64")
        val signatureBytes = response.signature.decodeBase64()?.toByteArray()
            ?: throw ProtocolException("signature is not valid base64")

        val release = try {
            protocolJson.decodeFromString(Release.serializer(), releaseBytes.decodeToString())
        } catch (e: Exception) {
            throw ProtocolException("release json is invalid: ${e.message}", e)
        }

        if (release.schemaVersion != SCHEMA_VERSION) {
            throw ProtocolException("unsupported schema version ${release.schemaVersion}")
        }
        if (release.appKey != config.appKey) {
            throw ProtocolException("appKey mismatch: ${release.appKey}")
        }
        if (!isSafeId(release.packageId)) {
            throw ProtocolException("packageId is invalid")
        }

        val publicKey = config.trustedKeys[release.keyId]
            ?: throw ProtocolException("unknown keyId ${release.keyId}")
        val keyBytes = publicKey.decodeBase64()?.toByteArray()
            ?: throw ProtocolException("public key is not valid base64")

        val verifier = try {
            KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(keyBytes))
        } catch (e: Exception) {
            throw ProtocolException("public key is invalid: ${e.message}", e)
        }

        val ok = try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(verifier)
                update(releaseBytes)
                verify(signatureBytes)
            }
        } catch (e: Exception) {
            throw ProtocolException("signature verification failed: ${e.message}", e)
        }
        if (!ok) throw ProtocolException("signature mismatch")

        validate(release)
        return VerifiedRelease(releaseBytes, response.signature, release)
    }

    fun validate(release: Release) {
        if (release.version <= 0) throw ProtocolException("version must be positive")
        if (release.archive.size <= 0) throw ProtocolException("archive size must be positive")
        if (release.archive.url.isBlank()) throw ProtocolException("archive url is blank")
        if (!isSha256(release.archive.sha256)) throw ProtocolException("archive sha256 is invalid")
        if (release.files.isEmpty()) throw ProtocolException("release has no files")
        if (release.files.size > MAX_FILES) throw ProtocolException("too many files")
        if (release.routes.isEmpty()) throw ProtocolException("release has no routes")

        var total = 0L
        val seen = HashSet<String>(release.files.size * 2)
        for (file in release.files) {
            if (!isSafePath(file.path)) throw ProtocolException("unsafe path ${file.path}")
            if (!seen.add(file.path)) throw ProtocolException("duplicate path ${file.path}")
            if (file.size < 0) throw ProtocolException("negative size for ${file.path}")
            if (!isSha256(file.sha256)) throw ProtocolException("invalid sha256 for ${file.path}")
            total += file.size
            if (total > MAX_PACKAGE_BYTES) throw ProtocolException("package is too large")
        }

        for (route in release.routes) {
            if (route.origin.isBlank()) throw ProtocolException("route origin is blank")
            if (!route.basePath.startsWith("/")) throw ProtocolException("basePath must start with /")
        }
    }
}

internal fun isSha256(value: String): Boolean =
    value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

internal fun isSafeId(value: String): Boolean =
    value.isNotEmpty() &&
        value.length <= 64 &&
        value.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }

internal fun isSafePath(path: String): Boolean {
    if (path.isEmpty() || path.length > MAX_PATH_LENGTH) return false
    if (path.startsWith("/") || path.contains('\\') || path.contains('\u0000')) return false
    val segments = path.split('/')
    return segments.none { it.isEmpty() || it == "." || it == ".." }
}

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

internal fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toHex()
}

private fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    val digits = "0123456789abcdef"
    for (i in indices) {
        val value = this[i].toInt() and 0xff
        out[i * 2] = digits[value ushr 4]
        out[i * 2 + 1] = digits[value and 0x0f]
    }
    return String(out)
}

internal class ProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)
