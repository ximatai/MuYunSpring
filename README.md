# MuYunSpring

[![CI](https://github.com/ximatai/MuYunSpring/actions/workflows/ci.yml/badge.svg)](https://github.com/ximatai/MuYunSpring/actions/workflows/ci.yml)

MuYunSpring 是一个基于 Java 21、Spring Boot 和 Vue 3 的企业应用平台底座。项目的核心路线是“动静一体”：静态 Java 业务模型和动态元数据模型复用同一套平台能力、数据访问、生命周期、权限、审计和页面交付链路。

它不是单纯的动态 CRUD，也不是把低代码平台和 Java 业务开发拆成两套系统。MuYunSpring 希望让稳定业务模块保持直观的 Java 接入体验，同时让可配置业务对象通过元数据获得相同的平台能力。

## 项目状态

MuYunSpring 目前处于平台底座快速演进阶段，重点是沉淀“动静一体”的后端能力契约、动态运行态、平台配置治理、身份权限、页面交付接口和前端工作台骨架。当前更适合作为企业应用平台底座、架构参考或二次开发起点，不是面向终端用户的完整业务套件。

当前成熟度大致如下：

| 范围 | 状态 |
| --- | --- |
| 后端 Ability、静态模型、动态记录运行态、schema 初始化 | 已形成主要契约，仍随平台边界演进 |
| 平台配置、IAM、页面交付接口、业务自动化、工作流任务、治理能力 | 已有阶段能力和测试支撑，具体边界以 `docs/` 和测试为准 |
| 前端 workbench、登录、菜单、页签、UI adapter、mock/后端模式 | 可运行的骨架和接入示例 |
| 前端真实动态业务页面闭环、低代码设计器、多 UI adapter、插件市场 | 当前非目标或后续触发建设 |

适合关注本项目的场景：

- 企业内部系统、业务中台和可配置业务应用的平台底座。
- 希望静态 Java 业务代码和动态配置对象共享同一套能力、权限、审计、租户和生命周期语义。
- 需要把编码规则、导入导出、页面交付、工作流任务等能力沉淀为可复用平台能力。
- 需要低代码配置能力，但不希望业务逻辑绕过服务层、权限和治理链路。

不适合的场景：

- 需要立即交付给业务用户使用的完整低代码产品。
- 需要完整可视化表单设计器、流程设计器、插件市场或多 UI adapter。
- 只需要一个轻量 CRUD generator，不需要平台治理、租户、审计和动静一体边界。

## 核心能力

| 方向 | 当前范围 | 阶段状态 |
| --- | --- | --- |
| 动静一体 | 静态模型和动态元数据共享 CRUD、软删、树、排序、引用、缓存、生命周期和租户作用域语义。 | 核心契约 |
| 动态运行态 | 支持应用、模块、元数据、字段、关系和动态记录运行态刷新，动态记录复用平台 Ability 链路。 | 核心契约 |
| 平台配置 | 应用、模块、元数据、字段、字典、菜单和 UI 配置可由平台自身管理并自举。 | 阶段能力 |
| 身份权限 | 租户、组织、部门、岗位、员工、用户、角色、动作授权、菜单可见性和当前用户上下文。 | 阶段能力 |
| 页面交付 | 菜单入口、页面 bootstrap、列表查询、表单保存、附件、查重、引用候选和页面偏好接口。 | 阶段契约 |
| 业务自动化 | 编码规则、导入导出、生单、回写、来源关系、贡献台账和执行诊断。 | 阶段能力 |
| 工作流任务 | 流程定义、版本、实例、任务、审批、历史、委托、待办工作台和插件挂点。 | 阶段能力 |
| 治理闭环 | 配置包、健康检查、版本归档、指针切换、迁移 dry-run、导入草稿和模板复用。 | 阶段能力 |
| 前端工作台 | Vue 3 + TypeScript + Vite，包含登录、菜单、页签、平台 UI adapter、mock/后端模式和业务接入示例。 | 可运行骨架 |

## 仓库结构

```text
muyun-common      通用基础设施、异常、上下文、租户和公共工具
muyun-ability     平台能力接口、默认实现、生命周期和数据访问契约
muyun-dynamic     动态元数据、动态记录运行态和动态运行态刷新
muyun-platform    平台配置、页面交付、自动化、工作流和治理能力
muyun-iam         租户、组织、用户、角色、权限和身份上下文
muyun-web-adapter 通用 HTTP 协议适配、请求生命周期和 Web 基础能力
muyun-platform-web 平台能力的静态 Web 交付、端点投影和实时推送适配
muyun-iam-web     IAM 领域的 Web 交付
muyun-dynamic-web 动态记录和元数据的 Web 交付
muyun-demo        演示业务模型与服务
muyun-demo-web    演示业务的 Web 交付
muyun-spring-bom  面向业务应用的依赖版本清单
muyun-spring-boot-starter Spring Boot 自动装配、平台组件扫描与标准运行时依赖
muyun-boot        Spring Boot 应用、装配、配置和本地启动入口
muyun-web         Vue 前端工作台、平台 UI adapter、动态页面骨架和业务示例
docs              架构原则、平台专题、前端路线和技术债记录
```

领域模块不承载 HTTP 入口；`*-web` 模块依赖领域模块和 `muyun-web-adapter` 完成交付。`muyun-boot` 只负责组装这些模块并启动应用，不沉淀领域模型、Controller 或可复用测试构造。

业务二开应用应依赖 `muyun-spring-bom` 和 `muyun-spring-boot-starter`，并保留自己的
`@SpringBootApplication`、业务模型和 Web 交付；不复制或依赖 `muyun-boot`。本仓库可通过
`./gradlew publishReleaseToConsumerRepository` 生成统一本地 Maven 仓库，用于验证外部消费者。

## 技术栈

后端：

- Java 21
- Spring Boot
- Gradle Kotlin DSL
- PostgreSQL
- JUnit 5 / Testcontainers

前端：

- Vue 3
- TypeScript
- Vite
- Ant Design Vue 作为首个 UI adapter
- Vue Router / Pinia / TanStack Query for Vue

## 二开接入（推荐）

MuYunSpring 已发布到 Maven Central。业务应用应保留自己的
`@SpringBootApplication`、领域模型与 Web 交付，只通过 BOM 和 Starter 接入平台；不要复制或依赖本仓库的
`muyun-boot`（它只是框架仓库的本地宿主）。

要求 Java 21 和 PostgreSQL。Gradle Kotlin DSL 示例：

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("net.ximatai.muyun.spring:muyun-spring-bom:<version>"))
    implementation("net.ximatai.muyun.spring:muyun-spring-boot-starter")
    runtimeOnly("org.postgresql:postgresql")
}
```

将 `<version>` 替换为 [Maven Central](https://central.sonatype.com/search?q=g%3Anet.ximatai.muyun.spring) 中需要使用的正式版本。再在业务应用配置自己的数据源、运行模式与初始管理员密码。开发环境可使用 `muyun.runtime.mode=development`；生产环境使用默认 `production`，并以严格 schema migration 策略治理变更。不要在公网或共享环境使用示例密码。

平台的公开发布面、消费者验证和首发流程见[发布流程](docs/RELEASE_PROCESS.md)。

## 本地框架开发

本地完整开发栈需要 Java 21、Docker Compose v2、Node.js `>=22.23.0` 和 npm。后端默认使用 PostgreSQL，仓库不内置嵌入式数据库。

| 服务 | 默认地址 |
| --- | --- |
| PostgreSQL | `127.0.0.1:54321` |
| 后端 API | `http://127.0.0.1:8080` |
| 前端工作台 | `http://127.0.0.1:5173/` |

一键启动完整学校演示环境：

```bash
./scripts/dev-local.sh --demo
```

该脚本会启动 PostgreSQL、后端连续编译、后端和前端；`Ctrl-C` 仅停止脚本拉起的后端和前端进程，数据库容器会保留。`--demo` 是默认模式，会加载 `school-demo` 及示例租户、机构、角色和学校数据；纯平台开发使用：

```bash
./scripts/dev-local.sh --platform
```

需要分终端运行时，先 `docker compose up -d`，然后复制本机配置并启动后端：

```bash
cp muyun-boot/src/main/resources/application-local.yml.example \
  muyun-boot/src/main/resources/application-local.yml
./gradlew :muyun-boot:bootRun --args='--spring.profiles.active=local'
```

`application-local.yml` 已被 Git 忽略，可通过环境变量覆盖数据库和初始管理员密码；它也包含本地 Vite 地址的 CORS 白名单。前端本地联调必须以 `local` profile 启动后端，或使用 `./scripts/dev-local.sh`，不要直接执行未带 profile/白名单参数的 `bootRun`，否则浏览器会因 CORS 预检失败而显示网络错误。完整学校演示使用 `:muyun-boot:demoBootRun`。前端在另一个终端启动：

```bash
npm ci --prefix muyun-web
npm run dev:backend --prefix muyun-web
```

裸库首次启动会创建平台超级管理员：用户名 `admin`、密码 `admin123`、租户留空。该默认值只允许本地开发使用。

## 运行验证

后端完整验证（单元测试、`*IT` 集成测试和模块边界检查）：

```bash
./gradlew verifyAll
```

框架发布前应验证真实 Maven 消费者，而不只验证发布任务：

```bash
./gradlew verifyAll verifyPublishedConsumer
```

`verifyPublishedConsumer` 会将当前构建发布到本地消费者仓库，再构建并启动只依赖 Maven 坐标的独立消费者。首次正式发布或发布链路调整后，可人工运行 `MUYUN_RELEASE_VERSION=<released-version> ./gradlew verifyMavenCentralConsumer`，确认 Maven Central 中的 BOM 与 Starter 可被真实解析和启动；该远端检查不阻塞 Release CI。

前端验证：

```bash
npm run check --prefix muyun-web
```

## 文档导航

- [开发原则](docs/DEVELOPMENT_PRINCIPLES.md)：长期路线、推进方式和测试策略。
- [动静一体核心设计](docs/architecture/DYNAMIC_STATIC_UNIFIED_CORE.md)：静态模块与动态模块如何共享平台底座。
- [命名与边界](docs/architecture/NAMING_AND_BOUNDARIES.md)：Gradle 子项目、Java 包、平台模块别名和动态边界。
- [平台文档入口](docs/platform/README.md)：按业务专题整理的平台能力和 Web 接口交接入口。
- [前端技术架构](docs/frontend/TECHNICAL_ARCHITECTURE.md)：Vue 前端技术路线、组件契约、运行器边界和协作方式。
- [技术债记录](docs/TECHNICAL_DEBT.md)：已确认但暂缓处理的平台级问题。
- [发布流程](docs/RELEASE_PROCESS.md)：Maven Central 发布、签名凭据和消费者验证。
- [变更记录](docs/CHANGELOG.md)：正式版本面向使用者的能力与兼容性变化。

更多专题能力和 Web 接口说明从 [平台文档入口](docs/platform/README.md) 继续阅读。

## 贡献

欢迎通过 Issue 和 Pull Request 参与。开始前请阅读 [CONTRIBUTING.md](CONTRIBUTING.md)，并尽量让改动沿用现有模块边界、命名和测试风格。

常规贡献建议：

- Issue 适合提交可复现 bug、文档缺口、平台边界问题和具体能力建议。
- 路线级调整、模块拆分、新框架或大范围公共契约变化，建议先通过 Issue 讨论。
- 从 `main` 拉出短生命周期分支。
- 一个 PR 聚焦一个完整能力、业务目标或修复目标。
- 对外可见行为、平台契约和边界修正应补测试或说明验证方式。
- PR 描述包含变更内容、验证结果和剩余风险。
- 当前仓库尚未建立专门的安全披露流程；请不要在公开 Issue 中直接贴出可利用漏洞细节。

## 许可证

MuYunSpring 使用 [Apache License 2.0](LICENSE)。
