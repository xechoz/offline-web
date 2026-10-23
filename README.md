# Offline Web

[![Maven Central](https://img.shields.io/maven-central/v/io.github.xechoz/offline-web-core)](https://central.sonatype.com/artifact/io.github.xechoz/offline-web-core)

一个面向 Android WebView 的离线 Web 资源库：服务端把站点打包成签名离线包，客户端下载、校验、解压后，通过 `WebViewClient.shouldInterceptRequest` 透明地把请求映射到本地文件。业务代码只需要多写一行 `initOfflineResource(...)`。

本仓库同时包含：

- `core/`：可发布的 Android 库（Kotlin，minSdk 21）。
- `demo-android/`：Compose demo APK，包含状态、WebView、拦截事件面板。
- `server/`：单进程 Node.js 示例服务端，负责抓取/上传、签名、发布和管理页。
- `fixtures/`：Kotlin 与 Node 双方共用的协议契约向量。
- `offline-web.md`：完整实施计划与设计决策。

## 特性

- 签名 release：ECDSA P-256 + SHA-256，绑定包内容、路由、文件清单和过期时间。
- 安全解压：zip-slip、zip bomb、重复路径、清单外文件、大小/数量限制全部拒绝。
- 原子安装：临时目录校验通过后 rename，active/previous 指针原子切换；失败保留旧版本。
- WebView 拦截：只处理 GET + http(s)，最长 `basePath` 匹配，只读取清单内文件。
- Range/206：视频、音频等大资源按需读取。
- 生命周期：升级、回退、撤销（410）、过期、损坏检测、启动恢复。
- Compose demo：可切换状态、查看命中事件，飞行模式验收。

## 快速开始

### 1. 启动示例服务端

```bash
cd server
npm install
npm start
```

默认地址与凭据：

- 客户端 API：`http://127.0.0.1:8080/api/v1/...`
- 管理页：`http://127.0.0.1:8080/admin`
- 管理 token：`demo-admin`（可用 `ADMIN_TOKEN` 覆盖）
- 演示站点：`http://127.0.0.1:8080/site/`
- 首次启动会自动把 `sample-web/` 发布为 `shop` v1。

模拟器里访问宿主机使用 `10.0.2.2`，所以 demo 的 entry URL 是 `http://10.0.2.2:8080/site/`。

### 2. 构建并安装 demo

需要 Android SDK 36 和 JDK 21：

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/Android/Sdk
./gradlew :demo-android:installDebug
```

也可以只构建：

```bash
./gradlew :demo-android:assembleDebug
# demo-android/build/outputs/apk/debug/demo-android-debug.apk
```

### 3. 验收离线加载

1. 打开 demo，点击“下载安装”，状态变为 `ACTIVE`，WebView 显示“离线商店”。
2. 停掉服务端（Ctrl-C）。
3. 在 demo 里点击“打开”，或者杀进程后重新启动。
4. 页面、CSS、JS、图片、`data.json` 仍完整显示；事件面板出现 `HIT`。
5. 重新启动服务端，在管理页重新发布，demo 点击“检查更新”后版本递增并刷新。

清空演示数据：

```bash
rm -rf server/data          # 下次启动重新 seed v1
adb shell pm clear io.github.xechoz.offlineweb.demo
```

## 库接入

### 依赖

已发布到 Maven Central：

```kotlin
repositories {
    google()
    mavenCentral()
}

dependencies {
    implementation("io.github.xechoz:offline-web-core:0.1.1")
}
```

本地开发验证可先发布到 `mavenLocal()`：

```bash
./gradlew :core:publishToMavenLocal
```

### 初始化

在 `Application.onCreate` 调用一次：

```kotlin
OfflineWeb.init(
    context = this,
    config = OfflineConfig(
        appKey = "demo",
        endpoint = "https://cdn.example.com/offline/",
        trustedKeys = mapOf(
            "prod-2026-01" to "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE...",
        ),
        clientVersion = 1,
        maxStorageBytes = 200L * 1024 * 1024,
        networkPolicy = NetworkPolicy.ANY,
        expiryPolicy = ExpiryPolicy.STRICT,
        verifyOnStartup = StartupVerification.SIZE_ONLY,
        allowCleartext = false,
    ),
)
```

`trustedKeys` 的 value 是 **SPKI DER 公钥的 Base64**（不是 PEM 文本）。`keyId` 可配置多个，实现密钥轮换。

### 绑定 WebView

```kotlin
val binding = webView.initOfflineResource(
    resource = OfflineResource(
        packageId = "shop",
        missingResource = MissingResourcePolicy.NETWORK,
    ),
    delegateClient = existingWebViewClient, // 可选，业务 client 优先
)

webView.loadUrl("https://cdn.example.com/shop/")
```

- `delegateClient` 返回非 null 时直接使用业务响应，离线逻辑不介入。
- `binding.state` 是 `StateFlow<ResourceState>`。
- 页面销毁时调用 `binding.close()` 或 `webView.clearOfflineResource()`，恢复原 `WebViewClient`。
- 不修改 JS、DOM Storage、混合内容等设置，由业务自行决定。

### 更新

```kotlin
lifecycleScope.launch {
    when (val result = OfflineWeb.manager.update("shop")) {
        is UpdateResult.Installed -> { /* 重新加载页面 */ }
        is UpdateResult.UpToDate -> Unit
        is UpdateResult.Revoked -> Unit
        is UpdateResult.Skipped -> Unit
        is UpdateResult.Failed -> showError(result.error)
    }
}
```

其他 API：

```kotlin
OfflineWeb.manager.installed         // StateFlow<List<InstalledPackage>>
OfflineWeb.manager.events            // SharedFlow<OfflineEvent>
OfflineWeb.manager.storageUsageBytes // 本地占用
OfflineWeb.manager.availablePackages()
OfflineWeb.manager.remove("shop")
OfflineWeb.manager.clear()
```

### 策略

| 策略 | 取值 | 默认 |
|---|---|---|
| `NetworkPolicy` | `ANY` / `UNMETERED` / `NOT_ROAMING` | `ANY` |
| `ExpiryPolicy` | `STRICT`（过期不拦截）/ `GRACEFUL`（继续拦截） | `STRICT` |
| `StartupVerification` | `NONE` / `SIZE_ONLY` / `FULL` | `SIZE_ONLY` |
| `MissingResourcePolicy` | `NETWORK` / `ERROR` | `NETWORK` |

## 协议摘要

完整字段、签名与校验规则见 [docs/package-format.md](docs/package-format.md)。

```
release.json（服务端精确字节）
{
  "schemaVersion": 1,
  "keyId": "demo-2026-01",
  "appKey": "demo",
  "packageId": "shop",
  "version": 1,
  "versionName": "1.0.0",
  "createdAt": 1790000000000,
  "expiresAt": null,
  "minClientVersion": 1,
  "entryUrl": "http://10.0.2.2:8080/site/",
  "archive": { "url": "api/v1/downloads/shop/1/archive.zip", "size": 3125, "sha256": "..." },
  "routes": [
    { "origin": "http://10.0.2.2:8080", "basePath": "/site/", "spaFallback": true }
  ],
  "files": [
    { "path": "index.html", "size": 123, "sha256": "..." }
  ]
}
```

- 签名：ECDSA P-256 / SHA-256，DER 签名 Base64。
- 客户端 API 返回 base64 的原始 `release.json` 字节，避免 JSON 重序列化。
- zip 根目录就是 web 根目录；zip 内容必须与 `files` 完全一致。
- 版本号只增不减；撤销返回 `410`；过期按策略处理。

## 服务端

```bash
cd server
npm start
npm test
```

- `POST /admin/api/packages/:id/deploy`：URL 快照并发布。
- `POST /admin/api/packages/:id/deploy-zip`：上传 zip（根目录 = web 根目录）。
- `POST /admin/api/packages/:id/deploy-sample`：直接发布 `sample-web/`。
- `POST /admin/api/releases/:pkg/:ver/revoke`：撤销。
- `POST /admin/api/releases/:pkg/:ver/rollback`：旧内容发布成新版本。
- `GET /api/v1/apps/:appKey/packages`：包列表。
- `GET /api/v1/apps/:appKey/packages/:id/release`：ETag / 304 / 410 + 签名 release。
- `GET /api/v1/downloads/:pkg/:ver/archive.zip`：支持 Range。

完整说明见 [docs/server-api.md](docs/server-api.md)。

> `server/keys/demo-private.pem` 是公开的演示私钥，只用于 demo。生产必须使用 KMS/Secret Manager，详见 [docs/security.md](docs/security.md)。

## 构建与测试

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/Android/Sdk

./gradlew :core:testDebugUnitTest
./gradlew :core:lintDebug
./gradlew :demo-android:assembleDebug
./gradlew :core:publishToMavenLocal

# 发布到 Maven Central（需要 Central 凭据与 GPG key）
./gradlew :core:publishToMavenCentral

cd server && npm ci && npm test
```

发布流程和凭据说明见 [docs/releasing.md](docs/releasing.md)。

契约测试：

- `fixtures/release.json` / `release.sig` / `public-key.b64` 由 `server/scripts/gen-fixture.mjs` 生成。
- Kotlin 单测验证签名、URL 路由、zip 安全；Node 测试验证同一份 fixture 和 API 行为。

## 已知限制

- 只拦截 `shouldInterceptRequest`；Service Worker 的部分请求可能不经过它。需要可在 API 24+ 禁用 Service Worker，见 [docs/packaging-limitations.md](docs/packaging-limitations.md)。
- 不拦截 POST、WebSocket、SSE。
- URL 抓取只快照入口页和直接静态资源，不执行 JS、不递归 `<a>`；SPA 请用 zip 上传构建产物。
- 离线包是明文缓存，不提供私有内容加密/DRM。
- 演示服务端是单进程同步实现，无数据库、队列、鉴权体系，不适合直接生产。

## 目录

```
core/                  Android library
demo-android/          Compose demo
server/                Node demo server
fixtures/              shared protocol fixtures
docs/                  protocol / API / security / limits
offline-web.md         implementation plan
```

## License

Apache-2.0，见 [LICENSE](LICENSE)。
