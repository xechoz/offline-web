# Changelog

## 0.1.0

首个可用版本。

### core

- 签名 release（ECDSA P-256 / SHA-256）与固定公钥校验。
- OkHttp 下载，Range 续传，进度日志，磁盘预检查。
- zip-slip / zip bomb / 清单外文件 / 重复路径防护。
- 文件级 size + sha256 校验。
- 原子安装、active/previous 指针、启动恢复与清理。
- WebView `shouldInterceptRequest` 拦截、最长 basePath、SPA fallback、Range/206、MIME 映射。
- 过期/撤销/降级/损坏/缺失资源策略。
- Maven POM、sources jar、consumer proguard rules。

### demo-android

- Compose 状态卡、URL 输入、WebView、事件面板。
- 更新/删除/清空、离线命中日志。
- `@Preview` 纯 mock 状态。
- 飞行模式/停服务端验收。

### server

- 单进程 Node.js 示例服务端。
- URL 入口页快照、zip 上传、sample 目录发布。
- ECDSA 签名、release/archives 存储、ETag/304/410、Range。
- 管理页：创建应用/包、发布、撤销、回滚。
- demo 密钥、契约 fixture、Node 测试。
