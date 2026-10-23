package io.github.xechoz.offlineweb

/**
 * Page-level binding: which package to serve and how to behave when a file is missing.
 */
data class OfflineResource(
    val packageId: String,
    val missingResource: MissingResourcePolicy = MissingResourcePolicy.NETWORK,
)

data class InstalledPackage(
    val packageId: String,
    val version: Int,
    val versionName: String,
    val entryUrl: String,
    val expiresAt: Long?,
    val sizeBytes: Long,
)

data class RemotePackage(
    val packageId: String,
    val name: String,
    val version: Int?,
    val versionName: String,
    val expiresAt: Long?,
    val size: Long?,
)

data class OfflineEvent(
    val kind: Kind,
    val message: String,
    val url: String? = null,
    val packageId: String? = null,
    val version: Int? = null,
    val timestamp: Long = System.currentTimeMillis(),
) {
    enum class Kind { INFO, HIT, MISS, EXPIRED, REVOKED, UPDATE, ERROR }
}

sealed interface OfflineError {
    val message: String

    data class Network(override val message: String, val cause: Throwable? = null) : OfflineError

    data class Http(val code: Int, override val message: String = "HTTP $code") : OfflineError

    data class Signature(override val message: String) : OfflineError

    data class UnknownKey(val keyId: String) : OfflineError {
        override val message: String = "unknown keyId: $keyId"
    }

    data class HashMismatch(override val message: String) : OfflineError

    data class ZipRejected(override val message: String) : OfflineError

    data class DiskFull(override val message: String) : OfflineError

    data class UnsupportedVersion(override val message: String) : OfflineError

    data class Revoked(val packageId: String) : OfflineError {
        override val message: String = "package $packageId was revoked"
    }

    data class Expired(val packageId: String) : OfflineError {
        override val message: String = "package $packageId expired"
    }

    data class Storage(override val message: String, val cause: Throwable? = null) : OfflineError
}

sealed interface UpdateResult {
    data class UpToDate(val packageId: String, val version: Int) : UpdateResult

    data class Installed(val packageId: String, val version: Int) : UpdateResult

    data class Revoked(val packageId: String) : UpdateResult

    data class Skipped(val reason: String) : UpdateResult

    data class Failed(val error: OfflineError) : UpdateResult
}

sealed interface InstallResult {
    data class Installed(val packageId: String, val version: Int) : InstallResult

    data class Failed(val error: OfflineError) : InstallResult
}
