# Offline Web：离线 Web 资源库实施计划（定稿）

> 状态：已定稿，进入实施。
> 平台：Android（Kotlin + Compose demo）；示例服务端：Node.js。
> 坐标：`io.github.xechoz:offline-web-core:0.1.1`，Apache-2.0。

## 1. 目标与范围

### 1.1 目标

- 提供可发布的 Android 离线 Web 核心库：配置 → 下载 → 校验 → 解压 → 安装 → WebView 拦截 → 本地资源响应。
- 提供一个尽量简单的 Node.js 示例服务端：URL 快照打包、zip 上传、签名、发布、管理页、客户端 API。
- 提供 Compose demo APK，覆盖首次安装、断网加载、升级、过期、撤销、包损坏等场景。
- 核心库发布到 Maven（先 `publishToMavenLocal`，具备发布 Maven Central 的 POM/签名配置）。

### 1.2 非目标（v1）

- 不做通用 HTTP 缓存，不替代 PWA Service Worker。
- 不支持 POST / WebSocket / SSE 离线。
- 不执行页面 JS 做整站抓取；URL 抓取只做入口页快照。
- 不做增量差分包（v1 全量包）。
- 不提供私有内容加密/DRM；离线包按公开静态资源处理。
- 不发布 Compose 封装 artifact；demo 内使用 `AndroidView` 包装。

### 1.3 总体验收

- 飞行模式下，demo 的页面及 CSS/JS/图片/字体/本地 JSON 完整加载。
- 服务端发布新版本后，客户端可更新，刷新 WebView 使用新版本。
- 篡改 zip、过期、撤销、磁盘写满、下载中断均有确定行为，不破坏当前可用版本。
- `publishToMavenLocal` 后独立工程能解析 `offline-web-core`。

## 2. 模块结构

```
web_offline/
├── core/                    Android Library（唯一发布的库）
├── demo-android/            Compose demo + AndroidView WebView 包装
├── server/                  Node.js 示例服务端
│   ├── index.mjs            API + 抓取 + 签名 + 静态托管
│   ├── admin.html           单页管理界面（原生 JS）
│   ├── sample-web/          演示站点
│   ├── keys/                demo 密钥（仅演示，提交仓库）
│   ├── scripts/             密钥/契约 fixture 生成脚本
│   ├── data/                运行期数据（gitignore）
│   └── test/                node:test
├── fixtures/                协议契约固定测试向量
├── docs/                    协议、API、接入、安全、限制
├── gradle/                  version catalog 与 wrapper
└── offline-web.md           本计划
```

依赖方向：

- `core` → OkHttp、kotlinx-coroutines、kotlinx-serialization、androidx.annotation；不依赖 Compose。
- `demo-android` → `core`、Compose Material3。
- `server` → express、cheerio、archiver、adm-zip。
- 服务端与客户端不共享代码，通过 `fixtures/` 做跨语言契约测试。

技术基线：

| 项 | 版本/取值 |
|---|---|
| Kotlin | 2.2.x |
| AGP | 8.13.x |
| Gradle | 8.13 |
| JDK（构建） | 21 |
| compileSdk / targetSdk | 36 |
| minSdk | 21 |
| Node | 26 |
| 发布 groupId | `io.github.xechoz` |
| 发布 artifact | `offline-web-core` |
| 许可证 | Apache-2.0 |

## 3. 离线包协议

### 3.1 包格式

```
archive.zip                 # zip 根目录即 web 根目录
├── index.html
├── assets/app.js
├── assets/app.css
└── img/logo.png
```

- 权威清单在服务端签名的 release 中，zip 内不再放 manifest。
- 路径必须是相对路径；禁止绝对路径、`..`、反斜杠、符号链接、重复路径、仅大小写不同的冲突路径。
- 限制：解压后 ≤ 200 MB、文件数 ≤ 10000、单路径 ≤ 512 字符；超出直接拒绝。
- 解压结果必须与 `files` 清单完全一致（多文件、少文件都失败）。

### 3.2 签名 release envelope

`release.json` 是服务端生成并签名的**精确 UTF-8 字节**：

```json
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
  "archive": {
    "url": "api/v1/downloads/shop/1/archive.zip",
    "size": 123456,
    "sha256": "..."
  },
  "routes": [
    { "origin": "http://10.0.2.2:8080", "basePath": "/site/", "spaFallback": true }
  ],
  "files": [
    { "path": "index.html", "size": 1234, "sha256": "..." }
  ]
}
```

- `createdAt` / `expiresAt` 使用 epoch millis，避免 Android 低版本 `java.time` 限制。
- 单包单 active release，无 channel 概念。
- 版本号只增不减；回滚由服务端把旧内容发布成新的更大版本。

### 3.3 签名与校验

- 算法：ECDSA P-256 + SHA-256；签名对象为 `release.json` 精确字节；输出 DER 签名 Base64。
- `keyId` 支持轮换；客户端固定 `Map<keyId, publicKeySpkiBase64>`。
- 客户端 API 响应：

```json
{
  "etag": "\"sha256-of-release-bytes\"",
  "release": "base64(release.json exact bytes)",
  "signature": "base64(DER ECDSA signature)"
}
```

Base64 传递原始字节，避免 JSON 重序列化造成字节漂移。

客户端校验顺序（任一步失败都不激活新版本）：

1. 用固定公钥验证签名，未知 `keyId` 直接拒绝；
2. 解析 release，校验 `schemaVersion / appKey / packageId / minClientVersion`；
3. 校验 `expiresAt` 与版本号单调；
4. 下载后校验 `archive.size` 和 `archive.sha256`；
5. 解压时做 zip 安全检查，解压结果与 `files` 集合完全一致；
6. 逐文件校验 `size + sha256`；
7. staging 原子改名为正式版本目录，原子写 active 指针；
8. 重建内存路由快照。

### 3.4 URL 匹配与文件映射

- `scheme + host + port` 必须与 `routes[].origin` 完全一致，路径以 `basePath` 开头。
- 多包路由重叠时最长 `basePath` 优先。
- 相对路径 = 请求 path 去掉 `basePath`，按 URI 语义解码；拒绝含 `..` 的路径。
- `/` 或目录结尾映射到 `index.html`。
- 查询参数不参与查找；`data.json?v=2` 命中 `data.json`。
- 只读取 `files` 清单声明的文件，禁止目录扫描和包外读取。
- SPA fallback：仅主文档导航、路由开启 `spaFallback`、目标文件缺失时返回 `index.html`；子资源不走 fallback。

### 3.5 生命周期

- 版本号由服务端发布时分配，单调递增；客户端禁止降级。
- 客户端本地保留 active + previous；新版本安装失败时 previous 继续服务；active 启动校验失败时自动回退 previous。
- 撤销：客户端收到 `410 Gone` 后删除本地包并停止拦截。
- 过期：按 `ExpiryPolicy` 处理，默认 `STRICT`（停止拦截、回退网络、触发更新检查）。
- 启动校验：默认 `SIZE_ONLY`，可选 `FULL`；不可信则失效或回退。

## 4. 客户端设计（core）

### 4.1 对外 API

```kotlin
// Application.onCreate
OfflineWeb.init(
    context = this,
    config = OfflineConfig(
        appKey = "demo",
        endpoint = "http://10.0.2.2:8080/",
        trustedKeys = mapOf("demo-2026-01" to PUBLIC_KEY_SPKI_BASE64),
        clientVersion = 1,
        maxStorageBytes = 200L * 1024 * 1024,
        networkPolicy = NetworkPolicy.ANY,
        expiryPolicy = ExpiryPolicy.STRICT,
        allowCleartext = true, // 仅 demo；默认 false
        logger = { Log.d("OfflineWeb", it) },
    ),
)

val result: UpdateResult = OfflineWeb.manager.update("shop")

val binding = webView.initOfflineResource(
    OfflineResource(
        packageId = "shop",
        missingResource = MissingResourcePolicy.NETWORK,
        delegateClient = existingWebViewClient,
    )
)
binding.state // StateFlow<ResourceState>
binding.close()
```

`OfflineManager`：

```kotlin
interface OfflineManager {
    val installed: StateFlow<List<InstalledPackage>>
    val events: SharedFlow<OfflineEvent>
    val storageUsageBytes: Long

    suspend fun update(packageId: String? = null): UpdateResult
    suspend fun remove(packageId: String)
    suspend fun clear()

    fun resolve(url: String, isMainFrame: Boolean = false): ResolveResult
    suspend fun availablePackages(): List<RemotePackage>
}
```

- `OfflineConfig` 全局一份，按 `appKey` 隔离存储。
- `OfflineResource` 只描述页面级包与策略，不承载下载配置。
- 下载器、存储、时钟通过 internal interface 注入，便于测试。
- 公开 API 不暴露 OkHttp / 文件句柄类型。

### 4.2 存储布局

```
filesDir/offline-web/<appKey>/
├── releases/<packageId>/<version>/
│   ├── release.json
│   ├── release.sig
│   └── www/...
├── active/<packageId>        # 文件内容：版本号，原子 rename
├── previous/<packageId>
├── tmp/                      # .part / staging
└── usage.json                # lastAccess、容量统计（可选）
```

- 只使用内部存储。
- 下载、解压、校验都在 tmp；成功后 rename 到 releases，最后原子写指针。
- 启动清理 tmp 和孤儿版本目录。
- 所有文件访问先经过清单校验，杜绝路径穿越。

### 4.3 安装状态机

```
NOT_INSTALLED → CHECKING → DOWNLOADING → VERIFYING_ARCHIVE
              → EXTRACTING → VERIFYING_FILES → ACTIVATING → ACTIVE
                                          ↘ FAILED（保留旧 ACTIVE）

ACTIVE → SUPERSEDED | EXPIRED | REVOKED | INVALID
```

- 每个 appKey 的安装流程用 `Mutex` 串行化。
- 状态通过 `StateFlow` 暴露。
- 进程被杀：tmp 下次清理；指针只在成功后替换，不存在半个版本。

### 4.4 下载

- OkHttp；支持 `Range` 续传、`ETag/If-Range`、指数退避重试。
- 进度回调 `(bytes, total)`。
- 下载前检查磁盘空间（约 2.5 × archive size）。
- `NetworkPolicy`：`ANY / UNMETERED / NOT_ROAMING`；不满足返回 `Skipped`。
- 默认强制 HTTPS；demo 通过 `allowCleartext = true` 放行。
- 安装成功后删除 zip。

### 4.5 WebView 拦截

```kotlin
override fun shouldInterceptRequest(
    view: WebView,
    request: WebResourceRequest,
): WebResourceResponse? {
    delegate?.shouldInterceptRequest(view, request)?.let { return it }
    if (request.method != "GET") return null

    return when (val hit = resolver.resolve(request.url.toString(), request.isForMainFrame)) {
        is Hit.File -> hit.toResponse(request)
        is Hit.Missing -> missingPolicy.response(request)
        Hit.NotMine -> null
    }
}
```

关键规则：

- 只在 `WebViewClient.shouldInterceptRequest` 拦截；`OfflineWebChromeClient` 仅做可选 console/progress 转发。
- 只拦截 `http/https` 的 `GET`；`file://`、`content://`、`blob:`、`data:` 不介入。
- 业务 delegate 优先。
- 响应头：`Content-Length`、`Accept-Ranges: bytes`、`Cache-Control: no-store`、调试头 `X-Offline-Web: <pkg>/<version>`。
- 支持单段 `Range`，返回 `206 + Content-Range`。
- MIME 由扩展名内置表推导，文本用 UTF-8；不写入 release 清单。
- 文件流式返回；异常时回退网络。
- `Cache-Control: no-store` 防 WebView 缓存旧版本。
- 不修改 WebView 设置（JS、DOM storage、混合内容由业务配置）。
- 已知限制：Service Worker 的部分请求可能不经过 `shouldInterceptRequest`；文档说明可在 API 24+ 禁用 SW。

### 4.6 边缘逻辑

| 场景 | 行为 |
|---|---|
| 已过期 | 按 `ExpiryPolicy`，默认停止拦截并触发更新 |
| 服务端撤销 | 收到 410，删除本地包，回退网络 |
| 安装新版本失败 | 保留旧 active，记录事件 |
| active 启动校验失败 | 尝试 previous；均失败标记 INVALID |
| 本地文件被篡改 | 启动 SIZE_ONLY / FULL 校验；失败失效 |
| 路由命中但文件缺失 | `NETWORK` 回退网络；或 `ERROR` 返回 404 |
| 非路由 URL | null，走网络 |
| POST / WebSocket | 不拦截 |
| 请求带 Cookie | 可选 `bypassIfCookies` |
| 下载中断/磁盘满 | 清理 tmp，保留旧版本 |
| 多包路由重叠 | 最长 basePath 优先 |
| 时钟偏差 | 用响应 Date header 估算偏移 |
| 新版本号更小 | 拒绝，防降级 |
| 打开页面后包被删 | 已打开资源流不受影响，刷新回退 |

### 4.7 线程与生命周期

- `OfflineWeb.init` 主线程调用；磁盘 IO 用 `Dispatchers.IO`。
- 路由索引为不可变快照，`AtomicReference` 无锁读取。
- `initOfflineResource` 幂等；binding 存 `WeakHashMap<WebView, Binding>`。
- `binding.close()` 恢复传入 delegate；无 delegate 时只移除离线行为。
- 页面销毁后 binding 失效，避免泄漏。

### 4.8 错误模型

```kotlin
sealed class OfflineError {
    data class Network(...)
    data class Http(val code: Int)
    data class SignatureInvalid(...)
    data class KeyUnknown(val keyId: String)
    data class HashMismatch(val path: String)
    data class ZipRejected(val reason: String)
    data class DiskFull(...)
    data class UnsupportedVersion(...)
    data class Revoked(...)
    data class Expired(...)
    data class Storage(...)
}
```

对外用 `UpdateResult` / `InstallResult` sealed 类型表达成功、跳过、失败，不抛裸异常。

## 5. 服务端设计（Node.js demo）

### 5.1 选型

- Node 26 + express + cheerio + archiver + adm-zip。
- 单进程、同步发布、无数据库、无任务队列、无 channel、无 telemetry。
- ECDSA 使用 Node 内置 `crypto`；测试使用内置 `node --test`。

### 5.2 存储

```
server/data/
├── store.json                 # apps / packages / releases / active 指针
├── archives/<pkg>/<ver>.zip
├── releases/<pkg>/<ver>.json
├── releases/<pkg>/<ver>.sig
└── work/                      # 临时目录，启动清空
```

- 写操作 `write tmp + rename` 原子替换。
- 单进程串行执行。
- 一个包只有一个 active release。

### 5.3 打包与发布

```
POST /admin/api/packages/:id/deploy        { sourceUrl, expiresAt? }
POST /admin/api/packages/:id/deploy-zip    raw body = zip
```

同步完成：抓取/校验 → 计算文件 hash → 分配版本 → 生成 release.json → 签名 → 打 zip → 写 store → 切 active。响应返回版本、文件数、体积、跳过日志。

### 5.4 URL 抓取范围

- 只快照入口页 + 直接引用的静态资源，不递归抓 `<a>`。
- 支持 `<script src>`、`<link href>`、`<img src/srcset>`、`<source>`、`<video poster>`、内联 `<style>`。
- CSS 处理一层 `url()` / `@import`。
- 只处理同源资源；跨域资源记录 skipped。
- query 资源使用 hash 文件名，引用改写成相对路径。
- 限制 100 个文件 / 50 MB。
- 不执行 JS。多页站点、SPA、跨域资源走 zip 上传。

### 5.5 API

管理端（`X-Admin-Token`，默认 `demo-admin`）：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/admin` | 管理页 |
| GET/POST | `/admin/api/apps` | 应用列表/创建，返回公钥 |
| GET/POST | `/admin/api/apps/:appKey/packages` | 包列表/创建 |
| POST | `/admin/api/packages/:id/deploy` | URL 抓取 + 发布 |
| POST | `/admin/api/packages/:id/deploy-zip` | zip 上传 + 发布 |
| POST | `/admin/api/releases/:pkg/:ver/revoke` | 撤销 |
| POST | `/admin/api/releases/:pkg/:ver/rollback` | 旧内容复制成新版本再发布 |

客户端：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/apps/:appKey/packages` | 包列表、active 版本、过期时间 |
| GET | `/api/v1/apps/:appKey/packages/:id/release` | ETag / 304 / 410，返回 `{etag, release, signature}` |
| GET | `/api/v1/downloads/:pkg/:ver/archive.zip` | 自带 Range |

- AppKey 只是标识，不是信任根；完整性依赖 release 签名。
- `/site/` 托管 `sample-web/`，作为抓取目标。
- demo 服务端不加客户端鉴权；README 明确生产必须加。

### 5.6 签名密钥

- demo 密钥对固定在 `server/keys/` 并提交仓库；公钥 Base64 同时写入 demo `BuildConfig`。
- README 醒目警告：私钥仅用于 demo，生产必须使用 KMS/Secret Manager。
- 服务端按 `keyId` 选择密钥；客户端固定公钥列表支持轮换。

### 5.7 管理页

- 单 HTML + 原生 JS，无前端构建步骤。
- 功能：token 设置、应用/包创建、URL 发布、zip 发布、release 列表、撤销、回滚、构建日志。
- 发布 demo 站点的一键按钮。

## 6. Demo（demo-android + server/sample-web）

### 6.1 页面

- **Settings/Status**：endpoint、appKey、公钥、安装版本、存储占用、更新按钮、进度。
- **Web**：URL 输入、加载/刷新、WebView。
- **Events**：拦截命中/缺失/失败日志。

Screen 级 composable 均配 `@Preview`，使用纯 mock 状态，不依赖 ViewModel/网络。

### 6.2 示例站点

`server/sample-web` 单页站点：HTML + CSS + JS + 图片 + 字体 + `data.json`，显示版本号，确保断网后仍完整显示。

### 6.3 演示场景

1. 启动 server，自动或一键发布 v1；
2. demo 更新并打开 `/site/`，事件面板出现离线命中；
3. 停 server / 飞行模式，刷新仍完整显示；
4. 发布 v2，demo 更新，页面显示新版本；
5. 撤销/过期，验证停止拦截或删除；
6. 篡改 zip hash，验证安装失败且旧版本可用；
7. 清理缓存后重新安装。

### 6.4 网络

- 真实断网：飞行模式。
- demo 的明文 HTTP 仅在 debug `network_security_config.xml` 允许 `10.0.2.2/localhost`。
- 库本身不放开明文；demo 配置 `allowCleartext = true`。

## 7. 测试策略

| 层级 | 内容 |
|---|---|
| 契约 | `fixtures/` 固定 release/signature/public key；Kotlin 与 Node 双方验签 |
| core JVM | URL 匹配、路径穿越、zip-slip/zip bomb/重复路径、版本单调、过期、清理、MIME |
| core instrumentation | MockWebServer 下载/续传、安装、篡改拒绝、WebView 断网加载、delegate、Range、过期/撤销 |
| server | 签名、store 原子写、抓取改写、ETag/304/410、Range |
| E2E | server 发布 → demo 更新 → 停 server → 离线加载 |
| 静态检查 | Android lint、R8、apiCheck；Node test |

## 8. 安全设计

| 威胁 | 对策 |
|---|---|
| MITM/篡改下载 | HTTPS + release 签名 + archive sha256 |
| CDN/存储替换 | 签名绑定 archive hash 与文件清单 |
| 恶意 zip | 路径/数量/大小限制，拒绝链接，集合精确匹配 |
| 本地篡改 | 内部存储 + 启动校验 + 回退/失效 |
| 重放/降级 | 版本单调 + minClientVersion + expiresAt |
| 包内容 XSS | 与线上站点同风险；不注入 JS bridge，建议站点自带 CSP |
| 私有内容泄露 | v1 不支持；缓存为明文 |
| 服务端密钥泄露 | demo 密钥仅演示；生产 KMS、权限隔离、轮换 |
| AppKey 泄露 | AppKey 只是标识，签名校验才是信任根 |

## 9. 工程化与发布

### 9.1 Maven

- 坐标：`io.github.xechoz:offline-web-core:0.1.1`，Apache-2.0。
- Vanniktech maven publish + release variant + sources/javadoc jar。
- `publishToMavenLocal` 本地验证；`publishToMavenCentral` 通过 GPG 签名发布，版本不可覆盖。
- POM 包含 name/description/url/license/developer/scm。
- `consumer-rules.pro` 处理 kotlinx.serialization / 反射；release demo 跑 R8。
- 低版本兼容：时间用 epoch millis；Base64 用 Okio，避免 `java.util.Base64` 的 API 26 限制。

### 9.2 CI

- Gradle：`check`、lint、apiCheck、demo R8。
- Node：`npm ci && npm test`。
- tag：构建 + 签名 + Maven Central + demo APK + server 分发包。
- JDK 21，Gradle 缓存。

### 9.3 文档

```
README.md
docs/package-format.md
docs/server-api.md
docs/integration.md
docs/security.md
docs/packaging-limitations.md
CHANGELOG.md
```

## 10. 里程碑

| 阶段 | 内容 | 验收 | 预估 |
|---|---|---|---|
| M0 | 脚手架、demo 密钥、契约 fixture、CI 骨架 | 两侧测试可跑；fixture 在 Kotlin 端验签通过 | 2–3 天 |
| M1 | core：下载、解压、校验、存储、状态机、更新 API | 安装/篡改/中断/磁盘满行为确定 | 8 天 |
| M2 | core：WebView 拦截、路由、Range、delegate、过期/撤销 | 模拟器断网加载完整页面 | 4 天 |
| M3 | Node server：deploy/deploy-zip、admin、sample-web、客户端 API | admin 发布后客户端升级 | 3 天 |
| M4 | demo：Compose 页面、AndroidView、事件面板 | 7 个演示场景通过 | 3 天 |
| M5 | 加固、文档、R8、publishToMavenLocal → Central | 外部空工程可解析 | 4 天 |

合计约 4 周。

## 11. 风险

| 风险 | 缓解 |
|---|---|
| WebView 拦截行为随厂商/版本差异 | 固定测试矩阵；不依赖 SW；失败回退网络 |
| URL 抓取覆盖不了 SPA | 上传构建产物为主路径 |
| WebView 缓存导致升级后仍旧资源 | `Cache-Control: no-store` |
| 大文件/视频内存压力 | 流式响应 + Range |
| Maven Central 命名空间/签名耗时 | M0 就跑通 `publishToMavenLocal`，尽早申请 |
| ECDSA 低版本兼容性 | 契约测试 + API 23/30 模拟器验证 |
| 包体过大 | 200 MB 限制、磁盘预检查、后台安装与进度 |

## 12. 已定决策

1. 服务端使用 Node.js 单进程 demo，不用 Kotlin/Ktor。
2. Maven groupId `io.github.xechoz`，许可证 Apache-2.0。
3. minSdk 21。
4. 不建 `core-compose` 模块；demo 内用 `AndroidView` 包装。
5. 删除 channel，只保留单 active release。
6. 删除客户端 events 上报 API；事件仅本地展示。
7. 删除独立 `protocol` 模块；协议代码并入 core，用 fixture 做跨语言契约测试。
