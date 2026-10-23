package io.github.xechoz.offlineweb

import android.annotation.SuppressLint
import android.content.Context
import java.net.URI

@SuppressLint("StaticFieldLeak")
object OfflineWeb {

    private val lock = Any()

    @Volatile
    private var instance: OfflineManager? = null

    /**
     * Initializes the library. Call once from Application.onCreate.
     */
    fun init(context: Context, config: OfflineConfig) {
        synchronized(lock) {
            if (instance != null) return
            validate(config)
            instance = OfflineManager(context.applicationContext, config)
            config.logger?.invoke("initialized appKey=${config.appKey}")
        }
    }

    val manager: OfflineManager
        get() = instance ?: error("OfflineWeb.init(context, config) must be called first")

    internal val index
        get() = manager.index

    private fun validate(config: OfflineConfig) {
        require(config.appKey.matches(Regex("[A-Za-z0-9._-]{1,64}"))) {
            "appKey must contain only letters, digits, '.', '_' or '-'"
        }
        require(config.clientVersion > 0) { "clientVersion must be positive" }
        require(config.maxStorageBytes > 0) { "maxStorageBytes must be positive" }
        require(config.trustedKeys.isNotEmpty()) { "trustedKeys must not be empty" }
        val uri = runCatching { URI(config.endpoint) }.getOrNull()
            ?: error("endpoint is not a valid URL")
        require(uri.scheme == "http" || uri.scheme == "https") {
            "endpoint must use http or https"
        }
        require(uri.host != null) { "endpoint must contain a host" }
        if (uri.scheme == "http" && !config.allowCleartext) {
            require(false) { "http endpoint requires allowCleartext = true" }
        }
    }
}
