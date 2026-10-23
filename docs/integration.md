# 接入指南

## 1. 依赖

```kotlin
repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation("io.github.xechoz:offline-web-core:0.1.1")
}
```

库已发布到 Maven Central。本地开发验证可执行：

```bash
./gradlew :core:publishToMavenLocal
```

## 2. 初始化

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        OfflineWeb.init(
            this,
            OfflineConfig(
                appKey = "demo",
                endpoint = "https://cdn.example.com/offline/",
                trustedKeys = mapOf("prod-2026-01" to SPKI_PUBLIC_KEY_BASE64),
                clientVersion = 1,
                maxStorageBytes = 200L * 1024 * 1024,
                networkPolicy = NetworkPolicy.ANY,
                expiryPolicy = ExpiryPolicy.STRICT,
                verifyOnStartup = StartupVerification.SIZE_ONLY,
                allowCleartext = false,
                logger = { Log.d("OfflineWeb", it) },
            ),
        )
    }
}
```

规则：

- `OfflineWeb.init` 只生效一次；重复调用直接返回。
- `endpoint` 必须以 `/` 结尾或由库 trim；建议写 `https://host/base/`。
- `trustedKeys` value 是 **SPKI DER 公钥 Base64**。
- `allowCleartext = true` 只用于本地 demo；生产不要打开。
- `logger` 不要打印敏感数据。

## 3. WebView 绑定

```kotlin
val binding = webView.initOfflineResource(
    OfflineResource(
        packageId = "shop",
        missingResource = MissingResourcePolicy.NETWORK,
    ),
    delegateClient = myWebViewClient,
)
```

- `initOfflineResource` 会设置 `webView.webViewClient`。
- 业务 delegate 的 `shouldInterceptRequest` 先执行；返回非 null 即跳过离线。
- 其余常用回调（页面开始/结束、错误、SSL、URL override）会转发给 delegate。
- 页面销毁：

```kotlin
binding.close()
// 或
webView.clearOfflineResource()
```

- 重复绑定会先解绑旧 binding。
- binding 内部用 `WeakReference` 持有 WebView；仍建议主动 close 以停止状态收集。

## 4. 更新

```kotlin
viewModelScope.launch {
    when (val result = OfflineWeb.manager.update("shop")) {
        is UpdateResult.Installed -> reload()
        is UpdateResult.UpToDate -> Unit
        is UpdateResult.Revoked -> clearLocalUi()
        is UpdateResult.Skipped -> Unit
        is UpdateResult.Failed -> show(result.error.message)
    }
}
```

`update(null)` 会遍历已安装包，返回第一个非 UpToDate 的结果。

下载流程：

1. `GET .../release`，带 `If-None-Match`。
2. 304 → UpToDate；410 → 删除本地包；404/5xx → Failed。
3. 验签、检查 appKey/packageId/version/minClientVersion/expiresAt。
4. 版本不高于本地 → UpToDate（防降级）。
5. `archive.url` 下载到 `tmp/*.part`，支持 Range 续传。
6. 校验 zip size + sha256。
7. 解压到 `tmp/staging-*`，逐文件校验。
8. 写入 release 目录，原子切换 active 指针。
9. 重建路由索引，发出 `UPDATE` 事件。

`Mutex` 保证同一进程内安装串行。

## 5. 存储布局

```
filesDir/offline-web/<appKey>/
├── releases/<packageId>/<version>/
│   ├── release.json
│   ├── release.sig
│   └── www/
├── active/<packageId>          # 文本：版本号
├── previous/<packageId>
└── tmp/                        # .part / staging，启动清理
```

- 只使用内部存储。
- active 指针通过 tmp + rename 原子写。
- 启动时保留 active + previous，清理其他版本。
- active 校验失败时尝试 previous；都失败则删除包。
- `StartupVerification.FULL` 会重新计算所有文件 sha256，启动更慢。

## 6. 路由与响应

- 只拦截 `GET` + `http/https`。
- 只返回 release `files` 清单中的文件。
- 路径中的查询参数被忽略。
- 目录/空路径映射 `index.html`。
- 主文档导航且路由 `spaFallback=true` 时，缺失路径回退 `index.html`；子资源不 fallback。
- 响应：
  - `Content-Length`
  - `Accept-Ranges: bytes`
  - `Cache-Control: no-store`
  - `X-Offline-Web: <packageId>/<version>`
- 单段 `Range` 返回 `206` + `Content-Range`；非法范围返回 `416`。
- MIME 由扩展名表推导；文本类使用 UTF-8。
- 文件流式读取，不整包读入内存。

## 7. 紧邻边界的行为

| 场景 | 行为 |
|---|---|
| 包过期 + `STRICT` | 停止拦截，回退网络 |
| 包过期 + `GRACEFUL` | 继续拦截，等待更新 |
| 服务端 410 | 删除本地包 |
| 新版本安装失败 | 旧 active 继续服务 |
| 启动 active 损坏 | 尝试 previous，否则删除 |
| 请求路由内缺失文件 + `NETWORK` | 返回 null，走网络 |
| 请求路由内缺失文件 + `ERROR` | 返回本地 404 |
| 非清单 URL | null，走网络 |
| POST/WebSocket | 不拦截 |
| 本地包被删除后已打开页面 | 已打开文件流不受影响；刷新回退网络 |
| 多包路由重叠 | 最长 basePath |

## 8. 错误模型

`UpdateResult.Failed(error)` 中 `OfflineError` 可能是：

- `Network`：连接/超时/IO。
- `Http(code)`：下载或 API 非 2xx。
- `Signature`：签名、JSON、清单校验失败。
- `UnknownKey`：keyId 不在 trustedKeys。
- `HashMismatch`：archive 或文件 hash 不一致。
- `ZipRejected`：路径穿越、清单外文件、大小不符等。
- `DiskFull`：空间不足。
- `UnsupportedVersion`：minClientVersion 太高。
- `Expired` / `Revoked`。
- `Storage`：本地读写异常。

安装失败不会破坏旧版本；调用方可以重试。

## 9. 线程与生命周期

- `OfflineWeb.init` 在 Application 主线程调用；启动校验会做少量磁盘 IO，包很大/文件很多时不建议在首屏关键路径调用。
- `update`、`remove`、`clear` 是 suspend，内部切 `Dispatchers.IO`。
- `installed` / `events` 是 Flow；可在主线程收集。
- 路由索引是不可变快照，`shouldInterceptRequest` 无锁读取。
- `binding.state` 随 `installed` 更新；过期判断按读取时刻计算。

## 10. R8

库自带 `consumer-rules.pro`，保留协议 DTO 的序列化器。业务侧无需额外规则。若自定义反射/序列化，按需添加。

## 11. 低版本兼容

- minSdk 21。
- 时间使用 epoch millis，不依赖 `java.time`。
- Base64 使用 Okio，不依赖 `java.util.Base64` 的 API 26 限制。
- `WebResourceResponse` 使用 API 21 可用的构造器。
- 网络策略使用 API 21 可用的 `ConnectivityManager.activeNetworkInfo`。

## 12. 预览与 demo

`demo-android` 的 `DemoScreen` 提供 `@Preview`，使用 mock state 且不创建 WebView：

```bash
./gradlew :demo-android:assembleDebug
```

demo 的明文配置只允许 `10.0.2.2`、`localhost`、`127.0.0.1`。物理设备调试时，需把 `entryUrl` 改为宿主机局域网地址，并在 demo 的 `network_security_config.xml` 增加该域名/地址。

## 13. 排错

| 现象 | 检查 |
|---|---|
| WebView 一直走网络 | URL 的 origin/basePath 是否与 release routes 一致；是否已安装 active |
| 页面样式丢失 | zip 内路径是否与请求相对路径一致；CSS 引用的资源是否打进包 |
| 安装失败 HashMismatch | 服务端 zip 与 release 是否同一次生成；CDN 是否改写内容 |
| 更新总显示 UpToDate | 本地版本是否已高于服务端；服务端是否只 seed 了 v1 |
| 410 后仍显示旧页面 | 刷新/重新加载；已打开文档不会自动重载 |
| 明文请求失败 | 客户端 `allowCleartext`、Android network security config、WebView 混合内容设置 |
