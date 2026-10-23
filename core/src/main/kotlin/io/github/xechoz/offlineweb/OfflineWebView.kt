package io.github.xechoz.offlineweb

import android.webkit.WebView
import android.webkit.WebViewClient
import io.github.xechoz.offlineweb.internal.index.OfflineIndex
import io.github.xechoz.offlineweb.internal.web.OfflineWebViewClient
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class OfflineWebViewBinding internal constructor(
    webView: WebView,
    private val manager: OfflineManager,
    private val packageId: String,
    private val delegate: WebViewClient?,
) {

    private val webViewRef = WeakReference(webView)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(computeState(manager.installed.value))
    val state: StateFlow<ResourceState> = _state.asStateFlow()

    private val watchJob: Job = scope.launch {
        manager.installed.collect { packages ->
            _state.value = computeState(packages)
        }
    }

    @Volatile
    private var closed = false

    fun close() {
        if (closed) return
        closed = true
        watchJob.cancel()
        scope.cancel()
        val view = webViewRef.get()
        if (view != null) {
            view.webViewClient = delegate ?: WebViewClient()
        }
        if (view != null) {
            synchronized(bindings) {
                if (bindings[view] === this) bindings.remove(view)
            }
        }
    }

    private fun computeState(packages: List<InstalledPackage>): ResourceState {
        val pkg = packages.firstOrNull { it.packageId == packageId } ?: return ResourceState.NOT_INSTALLED
        val expiresAt = pkg.expiresAt
        return if (expiresAt != null && System.currentTimeMillis() >= expiresAt) {
            ResourceState.EXPIRED
        } else {
            ResourceState.ACTIVE
        }
    }

    companion object {
        internal val bindings = WeakHashMap<WebView, OfflineWebViewBinding>()
    }
}

/**
 * Installs offline interception on this WebView. The optional delegate is honored first;
 * returning a response from it bypasses the offline package.
 */
fun WebView.initOfflineResource(
    resource: OfflineResource,
    delegateClient: WebViewClient? = null,
): OfflineWebViewBinding {
    val manager = OfflineWeb.manager
    synchronized(OfflineWebViewBinding.bindings) {
        OfflineWebViewBinding.bindings[this]?.close()

        val binding = OfflineWebViewBinding(
            webView = this,
            manager = manager,
            packageId = resource.packageId,
            delegate = delegateClient,
        )
        OfflineWebViewBinding.bindings[this] = binding

        webViewClient = OfflineWebViewClient(
            delegate = delegateClient,
            missingResource = resource.missingResource,
            expiryPolicy = manager.config.expiryPolicy,
            indexProvider = { manager.index },
            onEvent = manager::emitEvent,
        )
        return binding
    }
}

fun WebView.clearOfflineResource() {
    synchronized(OfflineWebViewBinding.bindings) {
        OfflineWebViewBinding.bindings[this]?.close()
    }
}
