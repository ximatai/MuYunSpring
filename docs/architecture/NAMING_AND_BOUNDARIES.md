# 命名与边界

## 三类模块名称

| 名称          | 含义           | 示例                               | 规则                                         |
| ------------- | -------------- | ---------------------------------- | -------------------------------------------- |
| Gradle 子项目 | 构建和依赖边界 | `muyun-ability`                    | 只有存在真实代码、测试和稳定依赖边界时才创建 |
| Java 包       | 源码命名空间   | `net.ximatai.muyun.spring.ability` | 按代码职责组织                               |
| 平台模块别名  | 运行时业务边界 | `platform.metadata`                | 用于权限、审计、菜单、OpenAPI 等运行时语义   |

三者不能混用。一个 Gradle 子项目可以包含多个平台模块。

## Gradle 分层

Gradle 子项目表达稳定的构建、依赖和交付边界，而不是运行时 application/module alias。当前后端按以下职责分层：

| 层次          | 子项目                                                                          | 职责                                                             | 依赖约束                                                                                   |
| ------------- | ------------------------------------------------------------------------------- | ---------------------------------------------------------------- | ------------------------------------------------------------------------------------------ |
| 领域核心      | `muyun-common`、`muyun-ability`、`muyun-dynamic`、`muyun-platform`、`muyun-iam` | 领域模型、服务、能力契约和运行态                                 | 不依赖 `*-web` 或 `muyun-boot`                                                             |
| 通用交付适配  | `muyun-web-adapter`                                                             | HTTP 协议、请求生命周期、通用 mutation/query/scope/realtime 契约 | 只依赖 `muyun-ability` 及其通用契约；不依赖 IAM、Dynamic、Platform 或任何 `*-web` 交付模块 |
| 平台 Web 交付 | `muyun-platform-web`                                                            | 静态平台模块交付、端点投影与注册、平台实时推送适配               | 依赖领域核心和 `muyun-web-adapter`                                                         |
| 领域 Web 交付 | `muyun-iam-web`、`muyun-dynamic-web`                                            | 各领域 Controller、Web adapter 和对应测试                        | 依赖各自领域模块；可复用平台 Web 交付能力                                                  |
| 演示          | `muyun-demo`、`muyun-demo-web`                                                  | 可运行的演示领域及其 Web 场景                                    | `muyun-demo` 不依赖 Web；`muyun-demo-web` 承接 Web 入口                                    |
| 应用宿主      | `muyun-boot`                                                                    | Spring Boot application、装配、配置和本地启动                    | 只聚合运行模块，不承载领域或 Web 交付实现                                                  |

依赖方向始终从交付层指向领域层，再由 `muyun-boot` 统一装配；不得以生产依赖把领域模块或 `muyun-web-adapter` 反向依赖到某个 `*-web` 模块。标准 `muyun-boot` 不依赖 Demo；演示运行任务只在运行时追加 `muyun-demo-web`，并以 `school-demo` profile 装配完整演示环境。跨模块测试复用应优先放在 Gradle `testFixtures`，不把测试构造提升为生产 API，也不把它们迁回 `muyun-boot`。

静态应用身份及其初始数据、领域装配属于所属领域核心：例如 `EducationApplication` 与 `TeachingDemoConfiguration` 位于 `muyun-demo`。应用声明的扫描、目录和协调也属于 `muyun-platform`；静态模块身份以 `muyun-platform` 的 `@PlatformStaticModule(application = XxxApplication.class)` 声明，不得为了被 Controller 引用而迁入 Web 模块。HTTP 是该模块的一种可选投影：`*-web` 通过 `@RequestMapping` 交付入口，非规范路径再以 `@PlatformStaticWebScope(CUSTOM)` 显式标明；没有 HTTP 入口的模块不依赖 Web 交付层。

构建任务 `verifyModuleBoundaries` 会校验生产 Project 依赖白名单（不只是禁止少数反向依赖），并禁止 `muyun-boot` 出现 `@RestController`、`@Controller`、`@Service` 或 `@Repository`。`verifyAll` 已包含该检查；新增模块依赖或在 Boot 放置业务实现时必须先通过它。

`muyun-boot` 的 Java 根包只保留 `MuYunSpringApplication`。宿主装配按 `configuration.database`、`configuration.runtime`、`configuration.platform`、`configuration.iam`、`configuration.dynamic` 分域；启动任务编排位于 `bootstrap`。这些包只负责 Spring 组合，不承载领域实现或 HTTP 交付。

## Java 包根

统一使用：

```text
net.ximatai.muyun.spring
```

当前包和模块保持克制，不提前创建空目录或空子项目。新增边界必须能减少真实复杂度。

## 平台模块别名

平台模块别名格式：

```text
<applicationAlias>.<moduleName>
```

`applicationAlias` 使用单段小写标识，`moduleAlias` 使用至少两段点分小写标识，分段只允许小写字母、数字和下划线，且必须以字母开头。模块 alias 总长度不超过标准实体 ID 长度。

内置 `applicationAlias` 不是 Gradle 子项目名，也不是 Java 包边界。`platform` 表示平台配置、页面交付、自动化、工作流和治理等平台能力域；`iam` 表示租户、组织、职员、用户、角色和权限等身份组织域。两者都是平台内置应用域，和后续 `crm`、`sales` 等业务应用复用同一套 Application/Module 归属模型，但不表示 `iam` 是普通业务应用，也不表示所有内置模块都应归入 `platform`。

应用、模块、元数据和配置对象统一使用 alias 语义：

| 对象     | 语义字段 | 参数名             | 唯一范围                                |
| -------- | -------- | ------------------ | --------------------------------------- |
| 应用     | `alias`  | `applicationAlias` | 全局                                    |
| 模块     | `alias`  | `moduleAlias`      | 全局，且必须以 `applicationAlias.` 开头 |
| 元数据   | `alias`  | `metadataAlias`    | 应用内                                  |
| 菜单方案 | `alias`  | `menuSchemeAlias`  | 同一租户/scope 内                       |
| 数据字典 | `alias`  | `dictionaryAlias`  | 应用内                                  |

别名是稳定运行时标识，不是 URL，也不是 Java 包名。菜单节点本身不设计 alias/code；需要稳定选择的是菜单方案。模块身份在业务字段、参数、DTO 和关系表列中统一叫 `moduleAlias` / `module_alias`，不使用 `moduleId` / `module_id` 表达同一件事。即使 `Module.id` 与 `Module.alias` 使用相同值，模块下属业务仍按 `moduleAlias` 命名。

元数据身份不等于物理表名。`Metadata.id` 是平台生成的稳定 ID；`metadataAlias` 是应用内业务别名；`schemaName + tableName` 才是物理表定位。

## 模型命名

`Model` 用于持久化领域对象契约：

```text
EntityContract
TreeCapable
SortCapable
TitledCapable
```

`Definition` 用于配置定义：

```text
ModuleDefinition
EntityDefinition
FieldDefinition
ActionDefinition
```

`Runtime` 或 `Instance` 用于运行态记录：

```text
WorkflowInstance
WorkflowTask
WriteBackExecution
```

## 动态边界

`dynamic` 表示元数据驱动的运行时执行。它不表示任意脚本、不表示插件系统，也不允许绕过 Java 服务、权限、审计、校验和建表治理。

动态 Web 的标准保存请求与静态 CRUD 一样直接提交 `DynamicRecord` 业务实体；`id`、`version`、`values`、`children` 和 `attachments` 是动态记录的保留结构字段，不作为动态业务字段名使用。平台不以 `$save`、`record` wrapper 或保存 metadata 建立第二套写协议。
