package io.github.xechoz.offlineweb.demo

import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.xechoz.offlineweb.OfflineEvent
import io.github.xechoz.offlineweb.OfflineResource
import io.github.xechoz.offlineweb.ResourceState
import io.github.xechoz.offlineweb.clearOfflineResource
import io.github.xechoz.offlineweb.initOfflineResource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DemoScreen(
    state: DemoUiState,
    onUrlChange: (String) -> Unit,
    onLoad: () -> Unit,
    onUpdate: () -> Unit,
    onRemove: () -> Unit,
    onClear: () -> Unit,
    showWebView: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Offline Web", fontWeight = FontWeight.SemiBold)
                        Text(
                            "离线优先 · WebView 拦截",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.padding(end = 12.dp),
                    ) {
                        Text(
                            "DEMO",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp),
        ) {
            StatusCard(state = state, onUpdate = onUpdate, onRemove = onRemove, onClear = onClear)
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.url,
                    onValueChange = onUrlChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("URL") },
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = onLoad) { Text("打开") }
            }
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
            ) {
                if (showWebView) {
                    WebViewPanel(
                        url = state.url,
                        loadRequest = state.loadRequest,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("WebView", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            EventLog(events = state.events)
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun StatusCard(
    state: DemoUiState,
    onUpdate: () -> Unit,
    onRemove: () -> Unit,
    onClear: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("离线包状态", style = MaterialTheme.typography.labelMedium)
                    Text(
                        state.installedVersion ?: "尚未安装",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                StatusChip(state.resourceState)
            }
            Spacer(Modifier.height(10.dp))
            StatusLine("服务端", state.endpoint)
            StatusLine("存储占用", formatBytes(state.storageBytes))
            if (state.result != null) {
                Spacer(Modifier.height(6.dp))
                Text(state.result, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            if (state.updating) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onUpdate, enabled = !state.updating) {
                    if (state.updating) {
                        CircularProgressIndicator(
                            modifier = Modifier.width(16.dp).height(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(if (state.installedVersion == null) "下载安装" else "检查更新")
                }
                OutlinedButton(onClick = onRemove, enabled = state.installedVersion != null) {
                    Text("删除包")
                }
                OutlinedButton(onClick = onClear) { Text("清空") }
            }
        }
    }
}

@Composable
private fun StatusChip(state: ResourceState) {
    val (label, color) = when (state) {
        ResourceState.ACTIVE -> "ACTIVE" to Color(0xFF146C5A)
        ResourceState.NOT_INSTALLED -> "NOT INSTALLED" to Color(0xFF667085)
        ResourceState.EXPIRED -> "EXPIRED" to Color(0xFFB54708)
        ResourceState.REVOKED -> "REVOKED" to Color(0xFFB42318)
        ResourceState.INVALID -> "INVALID" to Color(0xFFB42318)
    }
    Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.12f)) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(modifier = Modifier.padding(top = 3.dp)) {
        Text(
            label,
            modifier = Modifier.width(70.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EventLog(events: List<OfflineEvent>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
            Text(
                "事件（最近 80 条）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (events.isEmpty()) {
                Text(
                    "还没有拦截事件",
                    modifier = Modifier.padding(vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
                LazyColumn(modifier = Modifier.height(124.dp)) {
                    items(events) { event ->
                        EventRow(event, formatter)
                    }
                }
            }
        }
    }
}

@Composable
private fun EventRow(event: OfflineEvent, formatter: SimpleDateFormat) {
    val color = when (event.kind) {
        OfflineEvent.Kind.HIT -> Color(0xFF146C5A)
        OfflineEvent.Kind.MISS -> Color(0xFFB54708)
        OfflineEvent.Kind.EXPIRED, OfflineEvent.Kind.REVOKED, OfflineEvent.Kind.ERROR -> Color(0xFFB42318)
        OfflineEvent.Kind.UPDATE, OfflineEvent.Kind.INFO -> Color(0xFF175CD3)
    }
    Row(modifier = Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(
            formatter.format(Date(event.timestamp)),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            event.kind.name,
            modifier = Modifier.width(58.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold,
        )
        Column(Modifier.weight(1f)) {
            Text(event.message, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val url = event.url
            if (url != null) {
                Text(
                    url,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun WebViewPanel(url: String, loadRequest: Int, modifier: Modifier = Modifier) {
    var webView by remember { mutableStateOf<WebView?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
                initOfflineResource(OfflineResource(packageId = "shop"))
            }.also { created ->
                webView = created
            }
        },
    )

    LaunchedEffect(webView, url, loadRequest) {
        webView?.loadUrl(url)
    }

    DisposableEffect(Unit) {
        onDispose {
            webView?.clearOfflineResource()
            webView?.destroy()
            webView = null
        }
    }
}

@Preview(showBackground = true, widthDp = 420, heightDp = 860)
@Composable
private fun DemoScreenPreview() {
    OfflineWebDemoTheme {
        DemoScreen(
            state = DemoUiState(
                installedVersion = "1.0.0 (v1)",
                resourceState = ResourceState.ACTIVE,
                storageBytes = 182_400,
                result = "已是最新版本 v1",
                events = listOf(
                    OfflineEvent(
                        kind = OfflineEvent.Kind.HIT,
                        message = "offline hit",
                        url = "http://10.0.2.2:8080/site/index.html",
                        packageId = "shop",
                        version = 1,
                    ),
                    OfflineEvent(
                        kind = OfflineEvent.Kind.MISS,
                        message = "missing in offline package",
                        url = "http://10.0.2.2:8080/site/api/optional.json",
                        packageId = "shop",
                        version = 1,
                    ),
                ),
            ),
            onUrlChange = {},
            onLoad = {},
            onUpdate = {},
            onRemove = {},
            onClear = {},
            showWebView = false,
        )
    }
}
