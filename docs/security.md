# 安全设计

## 1. 信任模型

- 客户端只信任 `OfflineConfig.trustedKeys` 中固定的 SPKI 公钥。
- 服务端用对应私钥对 `release.json` 精确字节签名。
- `release.json` 绑定：
  - `appKey` / `packageId` / `version`
  - `archive.size` / `archive.sha256`
  - 每个文件的 `path` / `size` / `sha256`
  - `routes`、`expiresAt`、`minClientVersion`
- 因此 CDN、对象存储、网络中间人都无法替换包内容而不被发现。
- `appKey` 只是命名空间，不是秘密；没有签名时，知道 appKey 不能伪造安装。

## 2. 威胁与对策

| 威胁 | 对策 |
|---|---|
| 下载被替换 | archive sha256 + release 签名 |
| release 被篡改 | ECDSA 签名；客户端固定公钥 |
| 降级/重放 | 版本单调递增；`minClientVersion`；`expiresAt` |
| zip-slip | 拒绝绝对路径、`..`、反斜杠、符号链接；canonical path 检查 |
| zip bomb | 文件数/总大小限制；只允许清单文件；逐文件大小上限 |
| 清单外读取 | 只从 `files` 映射的 File 返回；不扫描目录 |
| 本地篡改 | 内部存储 + 启动 SIZE_ONLY/FULL 校验 + previous 回退 |
| 路径穿越请求 | 拒绝 `.`/`..` 段；只查 manifest key |
| 恶意 HTML/XSS | 与线上站点同风险；库不注入 JS bridge；建议站点 CSP |
| 管理端滥用 | demo token；生产需真实鉴权/审计 |
| 私钥泄露 | demo 私钥公开；生产 KMS/Secret Manager + 轮换 |
| 明文窃听 | 生产强制 HTTPS；demo 仅本地明文 |

## 3. 密钥

### demo

```
server/keys/demo-private.pem    # 公开的演示私钥
server/keys/demo-public.pem
server/keys/demo-public.b64     # SPKI DER Base64，客户端使用
keyId = demo-2026-01
```

这组密钥**不能用于生产**。任何人都可以用它签发包。

### 生产建议

- 私钥放 KMS/HSM/Secret Manager，服务进程只拿签名权限。
- 每个环境/租户独立 keyId。
- 客户端内置多个公钥，支持轮换；旧 key 保留到所有包更新完。
- 发布系统记录“谁、何时、用什么 key 签了哪个 release”。
- 私钥泄露时：撤销旧 key、发布新 key 的客户端版本、服务端停用旧 key。

## 4. 传输

- 客户端默认拒绝 `http://` endpoint（除非 `allowCleartext = true`）。
- 生产建议 HTTPS + 证书校验；高安全场景可加 certificate pinning（OkHttp）。
- WebView 页面本身也应由业务配置网络安全策略；库不修改 WebView 的 mixed content / cleartext 设置。
- `Cache-Control: no-store` 用于离线响应，避免 WebView 缓存旧版本资源。

## 5. 本地存储

- 只写 `filesDir/offline-web/...`（应用内部存储）。
- release 文件、zip、staging 都在应用私有目录；其他应用不可读。
- 离线包是**明文**，不提供内容加密/DRM。敏感内容不应放入离线包。
- 卸载应用会删除；清除数据也会删除。

## 6. WebView 内容安全

- 离线包内容等同于站点自身内容，XSS 风险由站点负责。
- 不要在 WebView 中暴露不必要的 `@JavascriptInterface`。
- 建议服务端为站点设置 CSP，限制脚本/连接来源。
- 不建议对不可信第三方页面启用离线包。
- Service Worker 可能绕过 `shouldInterceptRequest`；见 `packaging-limitations.md`。

## 7. 管理端

- demo 使用固定 `X-Admin-Token`，默认 `demo-admin`。
- 生产必须接入真实身份、权限、审计、CSRF 防护、速率限制。
- 上传 zip 需要病毒扫描、配额、内容审核。
- 发布接口应幂等、可审计、可回滚。

## 8. 已知非目标

- 不提供客户端认证/授权。
- 不提供私有内容加密。
- 不防御已 root 设备上的本地内存/文件读取。
- 不防御恶意服务端签发恶意内容（签名只保证来源与完整性，不保证内容安全）。
