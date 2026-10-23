package io.github.xechoz.offlineweb

/**
 * Global configuration. Call [OfflineWeb.init] once from Application.onCreate.
 */
data class OfflineConfig(
    val appKey: String,
    val endpoint: String,
    val trustedKeys: Map<String, String>,
    val clientVersion: Int = 1,
    val maxStorageBytes: Long = 200L * 1024 * 1024,
    val networkPolicy: NetworkPolicy = NetworkPolicy.ANY,
    val expiryPolicy: ExpiryPolicy = ExpiryPolicy.STRICT,
    val verifyOnStartup: StartupVerification = StartupVerification.SIZE_ONLY,
    /** Allows http endpoints. Keep false outside local demos. */
    val allowCleartext: Boolean = false,
    val logger: ((String) -> Unit)? = null,
)

enum class NetworkPolicy { ANY, UNMETERED, NOT_ROAMING }

enum class ExpiryPolicy {
    /** Stop serving an expired package and fall back to the network. */
    STRICT,

    /** Keep serving an expired package while update checks run. */
    GRACEFUL,
}

enum class StartupVerification { NONE, SIZE_ONLY, FULL }

enum class MissingResourcePolicy {
    /** Let WebView request the resource from the network. */
    NETWORK,

    /** Reply with an offline 404 instead of touching the network. */
    ERROR,
}

enum class ResourceState { NOT_INSTALLED, ACTIVE, EXPIRED, REVOKED, INVALID }
