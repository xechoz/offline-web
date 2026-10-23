# 离线包协议

本文描述服务端与客户端之间的 v1 协议。客户端实现位于 `core/internal/protocol`，服务端实现位于 `server/index.mjs`。

## 1. 产物

一次发布产出三样东西：

| 文件 | 说明 |
|---|---|
| `release.json` | 发布清单，签名对象 |
| `release.sig` | ECDSA DER 签名的 Base64 |
| `archive.zip` | 站点静态文件，zip 根目录就是 web 根目录 |

客户端通过 API 获取 `release.json` 的原始字节与签名，再通过 `archive.url` 下载 zip。

## 2. 客户端 API 响应

```json
{
  "etag": "\"sha256-of-release-bytes\"",
  "release": "base64(release.json exact bytes)",
  "signature": "base64(DER ECDSA signature)"
}
```

- `release` 必须是**原始签名字节**的 Base64，不能是重新序列化后的 JSON。
- `signature` 对 `release` 解码后的字节做 ECDSA P-256 / SHA-256。
- `etag` 用于 `If-None-Match`；服务端可在未变化时返回 `304`。

## 3. release.json

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
    "size": 3125,
    "sha256": "ba2259..."
  },
  "routes": [
    {
      "origin": "http://10.0.2.2:8080",
      "basePath": "/site/",
      "spaFallback": true
    }
  ],
  "files": [
    {
      "path": "index.html",
      "size": 1024,
      "sha256": "..."
    }
  ]
}
```

| 字段 | 类型 | 规则 |
|---|---|---|
| `schemaVersion` | int | 当前固定 1 |
| `keyId` | string | 客户端 `trustedKeys` 的键；未知直接拒绝 |
| `appKey` | string | 必须等于客户端配置的 `appKey` |
| `packageId` | string | `[A-Za-z0-9._-]{1,64}` |
| `version` | int | > 0，单调递增，不允许降级 |
| `versionName` | string | 仅展示 |
| `createdAt` | long | epoch millis |
| `expiresAt` | long/null | epoch millis；null 表示不过期 |
| `minClientVersion` | int | 低于此值的客户端拒绝安装 |
| `entryUrl` | string | 入口 URL，展示/导航用途 |
| `archive.url` | string | 绝对 URL 或相对 endpoint 的路径 |
| `archive.size` | long | zip 字节数 |
| `archive.sha256` | string | 64 位 hex |
| `routes` | array | 至少一个；origin + basePath |
| `files` | array | 至少一个；与 zip 内容精确一致 |

### 3.1 路由

- `origin` 必须能解析为 `scheme://host[:port]`；默认端口归一化。
- `basePath` 必须以 `/` 开头；客户端会补尾部 `/`。
- 匹配规则：同 origin，且请求 path 等于 `basePath` 去掉尾 `/` 或以 `basePath` 开头。
- 多个包路由重叠时，最长 `basePath` 获胜。
- 相对文件路径 = 请求 path 去掉 `basePath`；`/` 或目录结尾映射到 `index.html`。
- 查询参数不参与查找。
- 拒绝包含 `.` / `..` 段的路径。

### 3.2 文件

- `path` 必须是相对 POSIX 路径，禁止绝对路径、`\`、空段、`.`、`..`，长度 ≤ 512。
- `size` 必须等于解压后字节数。
- `sha256` 必须是 64 位 hex。
- `path` 不能重复，且不能仅大小写不同（服务端会保留大小写，客户端按精确 key 查找；建议发布端检查）。
- 文件总数 ≤ 10000；解压后总大小 ≤ 200 MB。

## 4. archive.zip

- zip 根目录即 web 根目录，不能再套一层站点目录（除非入口本身在该目录）。
- 允许目录项；目录项会被忽略。
- 不允许符号链接；服务端上传时会跳过/拒绝。
- 不允许加密 zip。
- 解压结果必须与 `files` 集合**完全相等**：多一个、少一个都失败。
- 每个文件在解压时边写边校验 size 与 sha256。
- zip 整体在下载后先校验 `archive.size` + `archive.sha256`。

## 5. 校验顺序

客户端安装一个 release 时按顺序执行：

1. `release` / `signature` Base64 解码。
2. 解析 JSON，读取 `keyId`。
3. 用固定 SPKI 公钥验证签名；未知 keyId 拒绝。
4. 校验 `schemaVersion`、`appKey`、`packageId`、`version`、`minClientVersion`。
5. 校验 `expiresAt`（安装时已过期则拒绝）。
6. 下载 zip，校验大小与 sha256。
7. 解压到 staging，执行 zip 安全检查与文件级 hash 校验。
8. 写 `release.json` / `release.sig` 到正式版本目录。
9. 原子切换 active 指针；旧 active 变为 previous。
10. 重建内存路由索引。

任何一步失败都不会影响当前 active 版本。

## 6. 生命周期与状态码

| 条件 | 服务端 | 客户端行为 |
|---|---|---|
| 无 release | 404 | 失败，保留旧版本 |
| release 已撤销 | 410 | 删除本地包，回退网络 |
| release 未变化 | 304 | UpToDate |
| 服务端版本 ≤ 本地版本 | 200 | UpToDate，不降级 |
| release 已过期 | 200 | 拒绝安装；已安装的按 `ExpiryPolicy` |
| 签名/清单/hash 不合法 | 200 | 安装失败，保留旧版本 |

回滚策略：不提供客户端降级；服务端把旧内容重新发布成更大的版本号。

## 7. 契约向量

`fixtures/` 中的文件由 `server/scripts/gen-fixture.mjs` 生成：

- `release.json`：固定内容与字节。
- `release.sig`：对固定字节的签名。
- `public-key.b64`：对应 SPKI DER 公钥。
- `release-response.json`：客户端 API 的模拟响应。
- `site.zip`：与 `files` 对应的 zip。

Kotlin 单测与 Node 测试都使用这组文件，确保跨语言实现一致。
