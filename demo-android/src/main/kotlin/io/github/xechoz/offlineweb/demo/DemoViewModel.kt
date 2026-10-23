package io.github.xechoz.offlineweb.demo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.xechoz.offlineweb.OfflineEvent
import io.github.xechoz.offlineweb.OfflineWeb
import io.github.xechoz.offlineweb.ResourceState
import io.github.xechoz.offlineweb.UpdateResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DemoUiState(
    val endpoint: String = BuildConfig.OFFLINE_ENDPOINT,
    val url: String = "http://10.0.2.2:8080/site/",
    val installedVersion: String? = null,
    val resourceState: ResourceState = ResourceState.NOT_INSTALLED,
    val storageBytes: Long = 0,
    val updating: Boolean = false,
    val result: String? = null,
    val events: List<OfflineEvent> = emptyList(),
    val loadRequest: Int = 0,
)

class DemoViewModel : ViewModel() {

    var state by mutableStateOf(DemoUiState())
        private set

    private val manager get() = OfflineWeb.manager

    init {
        viewModelScope.launch {
            manager.installed.collect { packages ->
                val pkg = packages.firstOrNull { it.packageId == "shop" }
                val resourceState = when {
                    pkg == null -> ResourceState.NOT_INSTALLED
                    pkg.expiresAt?.let { System.currentTimeMillis() >= it } == true -> ResourceState.EXPIRED
                    else -> ResourceState.ACTIVE
                }
                state = state.copy(
                    installedVersion = pkg?.let { "${it.versionName.ifBlank { it.version.toString() }} (v${it.version})" },
                    resourceState = resourceState,
                )
                refreshStorage()
            }
        }
        viewModelScope.launch {
            manager.events.collect { event ->
                state = state.copy(events = (listOf(event) + state.events).take(80))
            }
        }
        refreshStorage()
    }

    fun setUrl(value: String) {
        state = state.copy(url = value)
    }

    fun load() {
        state = state.copy(loadRequest = state.loadRequest + 1)
    }

    fun update() {
        if (state.updating) return
        state = state.copy(updating = true, result = "正在检查更新…")
        viewModelScope.launch {
            val result = manager.update("shop")
            val message = when (result) {
                is UpdateResult.Installed -> "已安装 v${result.version}"
                is UpdateResult.UpToDate -> "已是最新版本 v${result.version}"
                is UpdateResult.Revoked -> "服务端已撤销该包"
                is UpdateResult.Skipped -> "跳过：${result.reason}"
                is UpdateResult.Failed -> "失败：${result.error.message}"
            }
            val installed = result is UpdateResult.Installed
            state = state.copy(
                updating = false,
                result = message,
                loadRequest = if (installed) state.loadRequest + 1 else state.loadRequest,
            )
            refreshStorage()
        }
    }

    fun removePackage() {
        viewModelScope.launch {
            manager.remove("shop")
            state = state.copy(result = "已删除本地包", loadRequest = state.loadRequest + 1)
            refreshStorage()
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            manager.clear()
            state = state.copy(result = "已清空离线存储", loadRequest = state.loadRequest + 1)
            refreshStorage()
        }
    }

    private fun refreshStorage() {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { manager.storageUsageBytes }
            state = state.copy(storageBytes = bytes)
        }
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
