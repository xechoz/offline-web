# 服务端 API

示例服务端是单进程 Node.js 实现，用于演示完整的“抓取/上传 → 签名 → 发布 → 客户端升级”流程。它不是生产级服务。

启动：

```bash
cd server
npm install
npm start
```

环境变量：

| 变量 | 默认 | 说明 |
|---|---|---|
| `PORT` | `8080` | 监听端口 |
| `ADMIN_TOKEN` | `demo-admin` | 管理端 token |
| `DATA_DIR` | `server/data` | 数据目录（store、归档、release、临时目录） |

默认 seed：首次启动、且 `shop` 没有任何 release 时，自动把 `sample-web/` 发布为 v1，入口 `http://10.0.2.2:8080/site/`。

## 1. 管理端

除 `GET /admin` 外，所有管理 API 需要请求头：

```
X-Admin-Token: demo-admin
```

### GET /admin

单页管理界面：创建应用/包、URL 发布、zip 上传、sample 发布、release 列表、撤销/回滚、日志。

### GET /admin/api/state

返回 `{ apps, packages, releases }`。`apps` 含公钥 Base64，不返回私钥。

### POST /admin/api/apps

```json
{ "appKey": "demo", "name": "Demo App" }
```

创建应用。示例服务端所有应用共用 `server/keys` 中的 demo 密钥对。

### POST /admin/api/apps/:appKey/packages

```json
{
  "packageId": "shop",
  "name": "Shop",
  "entryUrl": "http://10.0.2.2:8080/site/",
  "spaFallback": true
}
```

`entryUrl` 是客户端实际访问的 URL，决定 release 的 `routes.origin/basePath`。服务端抓取时使用的 `sourceUrl` 可以与它不同（例如服务端用 `127.0.0.1` 抓取，客户端用 `10.0.2.2`）。

### PATCH /admin/api/packages/:id

可更新 `name`、`entryUrl`、`spaFallback`。已发布的 release 不会自动改变路由；需要重新发布。

### POST /admin/api/packages/:id/deploy

```json
{
  "sourceUrl": "http://127.0.0.1:8080/site/",
  "versionName": "1.0.1",
  "expiresAt": 1799999999999
}
```

同步执行：

1. `fetch(sourceUrl)` 取入口 HTML（必须 `text/html`）。
2. 解析/改写入口页与直接静态资源，下载到临时目录。
3. 计算文件列表、大小、sha256。
4. 生成 `release.json`，用 demo 私钥签名。
5. 打包 zip，写入 `data/archives`、`data/releases`。
6. 旧 active 标记为 `superseded`，新版本成为 active。
7. 返回版本号、文件数、zip 体积、跳过的跨域资源。

抓取限制：不执行 JS、不递归 `<a>`、只抓同源资源；入口页之外的资源如果不在入口目录下会被跳过。SPA 请用 zip 上传。

### POST /admin/api/packages/:id/deploy-sample

请求体可选 `{ "versionName": "..." }`。直接把 `sample-web/` 目录打包发布，包含 `data.json`，适合 demo/验收。

### POST /admin/api/packages/:id/deploy-zip

- `Content-Type: application/zip`，body 为 zip 二进制。
- 可选 query：`versionName`、`expiresAt`。
- zip 根目录即 web 根目录。
- 示例：

```bash
curl -X POST \
  -H 'X-Admin-Token: demo-admin' \
  -H 'Content-Type: application/zip' \
  --data-binary @fixtures/site.zip \
  'http://127.0.0.1:8080/admin/api/packages/shop/deploy-zip?versionName=zip-1'
```

### POST /admin/api/releases/:pkg/:ver/revoke

把 release 标记为 `revoked`；如果它是 active，清空 active。客户端下次请求 release 得到 `410`，删除本地包。

### POST /admin/api/releases/:pkg/:ver/rollback

重新解压该版本 archive，发布成新的、更大的版本号。不会复用旧版本号，也不会让客户端降级。

## 2. 客户端 API

无需 token。`X-App-Key` 仅作标识，不是信任根；完整性靠 release 签名。

### GET /api/v1/apps/:appKey/packages

```json
[
  {
    "packageId": "shop",
    "name": "Shop",
    "version": 1,
    "versionName": "1.0.0",
    "expiresAt": null,
    "size": 3125
  }
]
```

### GET /api/v1/apps/:appKey/packages/:id/release

- 200：`{ etag, release, signature }`，`Cache-Control: no-store`，带 `ETag`。
- 304：`If-None-Match` 命中。
- 404：无 active release。
- 410：release 被撤销。

### GET /api/v1/downloads/:pkg/:ver/archive.zip

- 支持 `Range`，返回 206/`Content-Range`。
- `Cache-Control: no-store`。
- 客户端会根据 release 中的 `archive.url` 拼接此路径。

### GET /api/health

`{"ok":true}`，用于启动/健康检查。

## 3. 数据目录

```
server/data/
├── store.json
├── archives/<pkg>/<ver>.zip
├── releases/<pkg>/<ver>/release.json
├── releases/<pkg>/<ver>/release.sig
└── work/                       # 启动清空
```

`store.json` 结构：

```json
{
  "apps": {
    "demo": { "name": "...", "keyId": "demo-2026-01", "publicKey": "..." }
  },
  "packages": {
    "shop": {
      "appKey": "demo",
      "name": "Shop",
      "entryUrl": "http://10.0.2.2:8080/site/",
      "spaFallback": true,
      "nextVersion": 2,
      "activeVersion": 1,
      "createdAt": 1790000000000
    }
  },
  "releases": {
    "shop": [
      {
        "version": 1,
        "versionName": "1.0.0",
        "createdAt": 1790000000000,
        "expiresAt": null,
        "size": 3125,
        "sha256": "...",
        "fileCount": 5,
        "status": "active"
      }
    ]
  }
}
```

写 store 使用 tmp + rename。发布是同步的，同一进程内可视为串行。

## 4. 重置

```bash
rm -rf server/data
npm start
```

服务端会重新 seed v1。客户端需要“删除包”或清空应用数据，否则本地可能保留更高版本，服务端 v1 会被判定为不需要升级（客户端禁止降级）。

## 5. 生产化清单（本 demo 未实现）

- 管理端真实鉴权/审计，不要使用固定 token。
- 客户端 API 鉴权、限流、CORS、签名密钥 KMS 化。
- 多进程/对象存储/数据库/任务队列。
- zip 上传病毒扫描、资源配额、并发发布锁。
- 发布回滚的幂等与权限控制。
- 日志、指标、告警、备份。
- HTTPS 与证书固定（客户端示例默认允许 demo 的明文 HTTP）。

私钥仅用于 demo：`server/keys/demo-private.pem` 已提交仓库，生产必须替换。
