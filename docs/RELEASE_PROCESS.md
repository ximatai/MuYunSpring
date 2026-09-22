# 发布流程

MuYunSpring 将平台基础能力、Web 交付和 Spring Boot 自动装配发布为 Maven artifact。业务应用通过
`muyun-spring-bom` 统一版本，并依赖 `muyun-spring-boot-starter` 启动平台；`muyun-demo*` 与
`muyun-boot` 不属于公共运行时发布面。

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
4. 执行 `./gradlew publishReleaseToSonatype -Pmuyun.mavenCentralRelease=true`。
5. 从 npm 消费者验证生成的 staging 包发布同一 tag 对应的 npm 包。
6. 两个 registry 都成功后，在独立的最小写权限 job 中推进下一开发版本并推送 `main`。

Maven Central 与 npm 都发布成功才代表一次完整发布。Maven Central 的索引可见性检查保留为发布后的轻量人工验证，
不阻塞 Release workflow。

`muyun.mavenCentralRelease` 是正式发布意图，只允许在 Maven Central staging 和上传步骤启用。签名 staging 会强制
校验 release tag 和完整 PGP 签名材料，并将构件写入各公共模块的 `build/repo`；远程上传还会额外校验 Sonatype
凭据。需要只生成签名 staging、不执行上传时，使用：

```bash
./gradlew stageMavenCentralRelease \
  -Pmuyun.mavenCentralRelease=true \
  -Prelease.tag=v<version>
```

`publishReleaseToLocalRepository` 暂时保留为 `stageMavenCentralRelease` 的兼容别名。`build/repo` 属于正式发布 staging，
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
