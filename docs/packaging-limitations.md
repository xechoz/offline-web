# 打包与拦截限制

## 1. URL 抓取范围

`POST /admin/api/packages/:id/deploy` 是**入口页快照**，不是整站爬虫：

- 只请求 `sourceUrl` 一次。
- 解析入口 HTML，抓取直接引用的同源静态资源。
- 不执行 JavaScript。
- 不递归 `<a href>`。
- 不抓 `fetch()` / `XMLHttpRequest` / WebSocket / SSE 的运行时请求。
- 跨域资源保持原 URL（离线时不可用），并在 `skipped` 中记录。
- CSS 只处理 `url()` 与 `@import` 的直接引用；复杂预处理器/嵌套导入可能不完整。
- `<base href>` 会被移除，避免本地相对路径错乱。
- 入口页目录之外的资源会被跳过。
- 查询字符串资源会加 hash 后缀，改写引用。

因此：

- 多页站点、SPA、动态接口、跨域 CDN 资源，应使用 **zip 上传构建产物**。
- 示例站点的 `data.json` 是 JS 运行时 `fetch`，所以 URL 抓取不会包含它；`deploy-sample` 直接打包目录，会包含。

## 2. Service Worker

- 库只拦截 `WebViewClient.shouldInterceptRequest`。
- 部分 Service Worker 请求可能由 Chromium 网络栈直接处理，不经过该回调。
- 如果站点使用 SW 且强依赖离线缓存，可能出现“部分资源走网络”。
- 建议：
  - 离线 WebView 页面在 API 24+ 禁用 Service Worker；或
  - 站点在离线包场景不注册 SW；或
  - 接受 SW 与库缓存并存，做好版本失效。

## 3. 不支持的请求

- POST / PUT / DELETE / PATCH 等非 GET。
- WebSocket / SSE。
- `file://`、`content://`、`blob:`、`data:`。
- 需要请求体参与业务逻辑的接口。
- 需要 HTTP 认证协商、客户端证书的请求（库不转发自定义认证逻辑，除非业务 delegate 处理）。

## 4. Range 与流媒体

- 支持单段 `Range: bytes=start-end`、`bytes=start-`、`bytes=-suffix`。
- 不支持多段 Range；返回 416 或完整响应（当前实现返回 416）。
- 不解析 `If-Range`。
- 适合图片、字体、音频、渐进式视频；不做 HLS/DASH 分片调度。
- 文件流由 WebView 负责关闭。

## 5. MIME 与编码

- MIME 由扩展名内置表推导，release 不存 content-type。
- 文本类（`text/*`、JSON、SVG）固定 UTF-8。
- 未知扩展名回退 `application/octet-stream`。
- 如果站点依赖特殊 MIME（如 `application/wasm`、`font/woff2`），内置表已覆盖常见类型；自定义类型需要扩展库。
- 不支持服务端 content-encoding；zip 内应是解码后的静态文件。

## 6. 路由与 URL

- 匹配按 `scheme + host + port + basePath`。
- 默认端口归一化（http 80 / https 443）。
- 查询参数不参与文件查找。
- 目录映射 `index.html`；不自动尝试 `index.htm`。
- SPA fallback 只对主文档导航生效。
- 大小写敏感（按 manifest key 精确匹配）。
- URL 中的 `%2F` 等编码由 `URI.path` 解码；包含 `..` 的路径会被拒绝。
- 不支持同一 origin 下多个不相关 basePath 共享一个 zip（可以配置多条 routes，但文件路径都相对各自 basePath）。

## 7. WebView 差异

- 不同厂商 WebView 版本对 `shouldInterceptRequest`、Range、缓存、SW 的行为可能不同。
- 库不修改 WebView 设置：
  - JS、DOM Storage、mixed content、zoom、UA、cookie 都由业务配置。
- 如果业务 delegate 在 `shouldInterceptRequest` 中返回响应，离线逻辑不会执行。
- 页面已经打开后删除/升级包，已加载资源流不受影响；刷新才会使用新索引。

## 8. 包体与性能

- 上限：200 MB、10000 文件、单路径 512 字符。
- 解压时逐文件 hash；大包安装会占用 CPU/IO。
- 不提供增量更新；每次全量 zip。
- 启动 `FULL` 校验会重新读所有文件；大包建议 `SIZE_ONLY` 或 `NONE`（自行权衡）。
- 下载使用 `.part` 续传；服务端不支持 Range 时自动重下。
- 安装成功删除 zip；staging 失败会清理。

## 9. 缓存

- 离线响应 `Cache-Control: no-store`，避免 WebView 旧缓存。
- 但 WebView 内存缓存、Service Worker、业务自定义缓存仍可能介入。
- 升级后建议 `reload()` 或重新创建 WebView；demo 在安装后重新 `loadUrl`。

## 10. 推荐做法

- 用前端构建产物 zip 发布，确保 `index.html`、assets、运行时 JSON 都在包内。
- 发布前在无网环境用 Chrome/WebView 验证。
- 为每个站点设置明确的 basePath，避免与线上其他路径冲突。
- 对需要登录/个性化的接口，保留网络请求，不要把敏感数据打进包。
- 使用 `expiresAt` + 定期 `update()` 控制内容新鲜度。
- 生产使用 HTTPS，管理端接真实鉴权。
