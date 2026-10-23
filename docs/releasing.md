# 发布到 Maven Central

## 坐标

```kotlin
implementation("io.github.xechoz:offline-web-core:<version>")
```

groupId 为 `io.github.xechoz`，已通过 Central Portal 命名空间验证。

## 前置条件

- JDK 21
- Central Portal 账号，namespace `io.github.xechoz` 已验证
- 已上传到公钥服务器的 GPG key
- 本机 `~/.gradle/gradle.properties`（不要提交）：

```properties
mavenCentralUsername=<Central Portal token username>
mavenCentralPassword=<Central Portal token password>
signingInMemoryKeyId=<8 位 key id>
signingInMemoryKey=<base64 或 ASCII-armored 私钥>
signingInMemoryKeyPassword=<无密码时留空>
```

## 发布步骤

1. 修改 `core/build.gradle.kts` 中的 `coordinates(...)` 版本号，并更新 `CHANGELOG.md`。
2. 本地验证：

```bash
./gradlew :core:testDebugUnitTest
./gradlew :core:publishToMavenLocal
```

3. 发布并自动 release：

```bash
./gradlew :core:publishToMavenCentral
```

任务会上传 deployment、等待 Central 校验，校验通过后自动发布。Central 同步到 `repo1.maven.org` 通常需要 10–30 分钟。

4. 验证：

```bash
curl -I https://repo1.maven.org/maven2/io/github/xechoz/offline-web-core/<version>/
```

5. 提交构建变更、打 tag 并创建 GitHub Release：

```bash
git tag v<version>
git push origin main --tags
gh release create v<version> --generate-notes
```

## 注意

- Maven Central 版本不可覆盖。发布失败时修复问题并递增版本，不要复用旧版本号。
- 发布前确认 POM 中的 `name`、`description`、`url`、`licenses`、`developers`、`scm` 完整。
- 当前 `javadoc.jar` 为空 jar，仅满足 Central 的发布要求；后续可接 Dokka 生成 API 文档。
- 生产签名私钥不要提交到仓库；本仓库只包含 demo 私钥。
