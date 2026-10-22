# 发布流程

MuYunSpring 将平台基础能力、Web 交付和 Spring Boot 自动装配发布为 Maven artifact。业务应用通过
`muyun-spring-bom` 统一版本，并依赖 `muyun-spring-boot-starter` 启动平台；`muyun-demo*` 与
`muyun-boot` 不属于公共运行时发布面。

公共发布范围以 `build.gradle.kts` 的 `publicArtifactProjectNames` 为准，模块职责见[命名与边界](architecture/NAMING_AND_BOUNDARIES.md)。

发布模块中的 Java `public` 类型不全是业务扩展点。推荐接入入口、协议类型、内部协作者及源码/Javadoc 的保留规则见 [Java 接入 API 与 Javadoc 边界](architecture/JAVA_API_BOUNDARY.md)。
BOM 不包含 Java 类，但承担版本约束；Starter 承担运行时依赖与自动装配。是否公开发布应依据消费契约，不按源码数量裁剪。

正式发布保持统一版本与完整依赖闭包。日常联调优先使用本地消费者仓库，积累稳定改动后再发布正式版本，
避免仅为文档或中间状态发版；紧急修复可独立发布。不为配额合并领域模块、删除必需构件或引入模块独立版本。

## 发布用量与统一 bundle

Maven Central 按组织统计发布次数、文件数和体积。根发布任务将所有公开模块的本次版本目录汇集到
`build/maven-central/repository`，通过一次部署上传；模块坐标、依赖、源码、Javadoc、Gradle 元数据、签名和校验和保持不变。
仓库级 `maven-metadata.xml` 及其校验文件、历史版本不进入 bundle。
合并部署主要减少发布次数，不能消除文件数和体积配额；具体阈值及组织余量仍以 Sonatype Usage Center 为准。

无凭据的本地盘点：

```bash
./gradlew reportMavenPublication
```

该任务复用本地 Maven 发布，检查当前版本各模块的产物与校验和，输出模块级文件数、未压缩体积以及
`build/maven-publication-report.json`。无签名盘点不代表正式发布用量，
正式报告由签名后的 bundle 生成，写入 `build/maven-central/report.json`，并由 Release CI 留存。
报告不硬编码组织配额，不代替在线 Usage Center，也不阻断必要的紧急发布。

## 本地消费者验证

在框架仓库执行：

```bash
./gradlew verifyAll verifyPublishedConsumer
```

全部公共 artifact 会写入根目录 `build/consumer-repo`。`verifyPublishedConsumer` 随后构建并启动
`samples/published-consumer`；该工程只以 Maven 坐标解析 BOM 与 Starter，并使用独立 PostgreSQL。
这验证 POM 的传递依赖和 Spring Boot 自动装配，而不依赖 Gradle project dependency。该仓库只服务本地消费，
不会加载或要求 Maven Central 的 PGP 签名材料；`publishToMavenLocal` 采用相同边界。

首次正式发布或发布链路发生调整后，可人工运行 `verifyMavenCentralConsumer`。它会等待 BOM 出现在 Maven
Central，再以远端仓库运行同一个消费者，用于确认公开仓库解析与运行。该检查不进入 Release workflow，避免
Maven Central 索引延迟放大为发布流水线风险。

发布完成后 `main` 通常已进入下一个 `-SNAPSHOT` 版本，人工验证时应显式传入刚发布的版本：

```bash
MUYUN_RELEASE_VERSION=<released-version> ./gradlew verifyMavenCentralConsumer
```

## Maven Central 发布

`gradle.properties`、`muyun-web/package.json` 和 `muyun-web/package-lock.json` 共同表示下一开发版本，必须完全一致并
保持 `X.Y.Z-SNAPSHOT`。其中 `Y` 是上海时区当前年份的后两位，`Z` 是该年的发布流水号并从 1 开始。发布使用与其去掉
`-SNAPSHOT` 后一致的 `v<version>` tag 触发 `.github/workflows/release.yml`；workflow 从 tag 推导正式构件版本，
再依次执行发布 gate、工作区清理、后端与消费者验证，以及远端发布任务。清理必须发生在验证之前：npm 消费者
验证会生成正式发布使用的 staging 包，之后不得再次清理该目录。

需要配置以下 GitHub Actions secrets：

- `SONATYPE_TOKEN`、`SONATYPE_PASSWORD`
- `SIGNING_KEY_ID`
- `SIGNING_SECRET_KEY` 或 `SIGNING_SECRET_KEY_BASE64`
- `SIGNING_PASSWORD`
- `NPM_TOKEN`：`@ximatai/muyun-web-app` 的 npm 官方仓库发布 token。使用具备包读写权限、覆盖
  `@ximatai` scope 且启用 `bypass 2FA` 的 granular token；否则 npm 会在实际 publish 阶段拒绝发布。

本地预检：

```bash
node scripts/version.mjs check
node scripts/version.mjs verify-release v<version>
./gradlew verifyReleaseTagVersion verifyReleaseCredentials -Prelease.tag=v<version>
```

每次正式发布按以下顺序进行：

1. 在 `main` 更新 [变更记录](CHANGELOG.md)，并确认 `node scripts/version.mjs check` 通过。
2. 推送匹配开发版本的 tag，例如三处版本均为 `0.26.2-SNAPSHOT` 时执行
   `git tag v0.26.2 && git push origin v0.26.2`。
3. Maven Central 与 npm 都发布成功后，Release workflow 自动将三处版本推进到 `0.26.3-SNAPSHOT`，提交并非强制地
   推送到 `main`。如果发布时已经跨年，则推进到新年份的 `0.<新年份>.1-SNAPSHOT`。

tag 必须与当前 `muyunVersion` 去掉 `-SNAPSHOT` 后完全一致。发布任务自身依赖 tag/version 与凭证 gate；Release workflow
按以下顺序执行：

1. 校验三处开发版本完全一致、年份正确、tag 匹配，并校验发布凭据。
2. 清理工作区。
3. 执行 `verifyAll`、本地 Maven 消费者验证、npm 消费者验证和 npm publish dry-run。
4. 执行 `./gradlew publishReleaseToSonatype -Pmuyun.mavenCentralRelease=true`，以单个 bundle 上传并等待部署状态到达 `PUBLISHED`。
5. 从 npm 消费者验证生成的 staging 包发布同一 tag 对应的 npm 包。
6. 两个 registry 都成功后，在独立的最小写权限 job 中推进下一开发版本并推送 `main`。

Maven Central 与 npm 都发布成功才代表一次完整发布。Maven Central 的索引可见性检查保留为发布后的轻量人工验证，
不阻塞 Release workflow。

插件只负责单次打包上传；平台脚本统一承接上传后的校验、发布和最终状态等待，合计最多 15 分钟。
`publishToSonatype` 保留为标准平台发布任务的兼容入口，不再执行插件自身的轮询。
部署状态 `PUBLISHING` 只表示正在分发，到达 `PUBLISHED` 才视为 Maven 发布完成。状态请求及响应体读取的临时故障
只重试已有 deployment；身份不符、权限错误、非法响应和失败终态直接中止。若超时或 npm 阶段失败，
应先查 Portal 和 registry，不得直接重跑整条发布。Release CI 会留存 deployment ID；可用同一凭据只读等待原部署：

```bash
node scripts/wait-maven-central.mjs <deployment-id-file>
```

该命令读取 `SONATYPE_TOKEN` 与 `SONATYPE_PASSWORD`，默认只读。若原部署停在 `VALIDATED`，会提示恢复操作，
此时可在 Portal 发布原部署，或显式启用发布模式：

```bash
node scripts/wait-maven-central.mjs <deployment-id-file> --publish
```

正式 Gradle 发布任务使用此模式：确认 `VALIDATED` 后，只对同一 deployment 提交一次发布。
发布回执丢失或暂时失败时，继续查询原部署状态，不盲目重试发布请求；若仍无法确认，超时后由人工核实并恢复。
恢复命令不上传、不创建或删除 deployment；已处于 `PUBLISHING` 或 `PUBLISHED` 时不会再次提交发布。

`muyun.mavenCentralRelease` 是正式发布意图，只允许在 Maven Central staging 和上传步骤启用。签名 staging 会强制
校验 release tag 和完整 PGP 签名材料，并将构件写入各公共模块的 `build/repo`；远程上传还会额外校验 Sonatype
凭据。需要离线验证签名产物并生成预览 ZIP、不执行上传时，使用：

```bash
./gradlew bundleMavenCentralRelease \
  -Pmuyun.mavenCentralRelease=true \
  -Prelease.tag=v<version>
```

该任务检查附件完整性、校验和与未知文件，生成 `build/maven-central/MuYunSpring-<version>.zip`。
正式上传由既有插件直接从同一已验证目录打包，不依赖预览 ZIP。
`stageMavenCentralRelease` 只生成签名 staging，`publishReleaseToLocalRepository` 保留为其兼容别名。`build/repo` 属于正式发布 staging，
不是普通开发消费仓库；本地依赖供应应使用 Maven Local 或 `build/consumer-repo`。Release workflow 只在凭据预检和
对应 registry 的最终发布步骤注入秘密，常规测试与消费者验证不得接触发布私钥或 token。

开发分支上的前端版本同样保留 `-SNAPSHOT`，本地 npm 消费者包因此也是快照版本。Release workflow 将 tag 对应的
`MUYUN_RELEASE_VERSION` 注入 staging 构建，只有待发布 npm 包去掉 `-SNAPSHOT`；`pack:consumer` 会同时校验三处开发
版本和正式版本映射。这样开发态与生产态都能在前后端之间精确对齐。

## 年份与开发版本维护

版本只通过 `scripts/version.mjs` 修改，避免 Gradle、npm manifest 和 lockfile 分别维护：

```bash
node scripts/version.mjs check
node scripts/version.mjs advance 0.26.14
node scripts/version.mjs ensure-current-year
```

同一年内，发布 `0.26.14` 后得到 `0.26.15-SNAPSHOT`；进入 2027 年后则得到 `0.27.1-SNAPSHOT`。按上海时区每日运行的
`version-rollover.yml` 会幂等检查跨年状态；旧年份版本不能触发正式发布。workflow 使用 `GITHUB_TOKEN` 推送的版本提交
不会递归触发普通 CI，因此推进 job 在写入前后运行版本校验，并且只允许三份版本文件发生变化。若仓库以后启用禁止
Actions 直接推送的分支保护，应将该 job 改为 GitHub App 或自动 PR，而不要放宽发布校验。

## 单通道发布补偿

若 Release 已成功发布 Maven Central、但 npm 因临时凭据或外部故障漏发，不要重跑完整 tag workflow：Maven Central
构件不可用同一版本重复上传。仅从该 release tag 建立干净 worktree，重新生成并校验 npm 包后补发缺失通道：

```bash
npm ci --prefix muyun-web
MUYUN_RELEASE_VERSION=<released-version> npm run pack:consumer --prefix muyun-web
cd build/consumer-npm/staging/web-app
npm publish --dry-run --access public --registry=https://registry.npmjs.org/
npm publish --access public --registry=https://registry.npmjs.org/
```

补偿只允许发布 registry 中尚不存在的同版本包，并应在发布后回读该版本与 `latest` tag。该路径是异常恢复，不替代
GitHub Actions 的常规发布；恢复完成后应修正对应的 CI 或凭据配置，避免下一次 tag 重复进入补偿流程。补偿使两个
registry 都完整后，手动运行 `Development Version Rollover` workflow 并填写 `released_version=X.Y.Z`，以同一套
CAS 校验推进开发版本；不要直接编辑三份版本文件。

## pre-FieldSpec schema 升级

`FieldCatalogLegacySchemaBridge` 已移除，不再由 `local` profile 自动修改数据库。升级保留该阶段数据库的环境，应先停止应用并备份，再执行：

```bash
psql "$DATABASE_URL" -v ON_ERROR_STOP=1 \
  -f scripts/migrations/field-catalog-pre-fieldspec-postgresql.sql
```

脚本只支持 PostgreSQL 的 `public` schema，包含旧字段目录表/列重命名、必填字段回填和旧
`platform_metadata_field.field_type_alias` 的数据合并。执行成功后再部署当前版本；不要在应用运行期间执行。

## npm 宿主部署配置

消费者包保留 `VITE_MUYUN_API_BASE_URL`、`VITE_MUYUN_AUTH_TOKEN` 和 `VITE_MUYUN_CREDENTIALS` 的环境变量引用，由业务宿主构建时解析。平台打包时不能把这些值提前固化为默认值、空值或打包机器的配置；平台内置角色、组织等页面与业务页面必须使用宿主指定的 API 地址。鉴权令牌仍优先使用平台当前登录会话，环境令牌仅沿用既有开发配置语义，不应在生产前端构建中填入长期凭据。

`verify:consumer` 安装当前 tarball，用独立消费者的 Vite 再次构建公开 API，并通过 Vitest 执行产物，验证宿主地址、认证恢复、当前会话、匿名请求、credentials、语言、流式请求及提交结果分发。宿主请求装配契约见[前端本地消费者验证](frontend/LOCAL_CONSUMER.md)。
