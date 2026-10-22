# Java 接入 API 与 Javadoc 边界

业务应用通过 BOM 和 Starter 接入平台，具体声明方式以[能力清单](ABILITY_CATALOG.md)和[动静一体核心设计](DYNAMIC_STATIC_UNIFIED_CORE.md)为准。Maven 发布模块是依赖与交付边界，不代表其中每个 Java `public` 类型都是业务扩展点。

## 接入与扩展

| 使用场景 | 推荐入口 | 边界 |
| --- | --- | --- |
| 静态模型 | `common.model.contract`、`common.model.capability`、`common.model.standard` 及字段注解 | 声明模型事实与能力字段，复用平台解析和校验 |
| 静态 Service | `BaseDao`、`CrudAbility`、`StandardBusinessService`、`TenantActiveScopedService` 及按需组合的 Ability | 在业务 hook 中表达领域规则，保留标准生命周期、权限、租户和事务链路 |
| 静态应用与模块 | `PlatformStaticApplication`、`PlatformStaticModule`、`StaticModuleServiceDeclaration` | 以领域 Service 为模块事实源；无 HTTP 交付也能注册模块 |
| 静态 Web 与页面 | `CrudWeb`、`StaticQueryViewWeb`、`StaticModuleUiContributor`、`ModuleUiDefinition` 及其 DSL 值类型 | 声明交付与页面表达，由平台编译并执行 |
| 动态记录 | `DynamicRecordService`、`DynamicRecord` | 通过门面定位模块并复用 Ability；业务不直接拼装单实体运行态和 DAO |
| 编码规则 | 模型编码声明、标准保存；确需显式调用时使用 `CodeRuntimeFacade` | 预览、取号、台账绑定具有不同副作用，不能以独立取号替代标准保存 |
| 平台专题能力 | 相应专题门面、类型化贡献接口及其请求/结果类型 | 按专题契约接入，不从名称中的 `Service`、`Facade` 或 `public` 修饰符推断可替换性 |

扩展接口及其参数、返回值、注解、枚举和 DSL 类型共同构成可用契约。受支持基类的 `protected` 构造入口、校验模板和扩展 hook 也属于接入面，不能为缩减文档统一切换为只展示 `public` 成员。

接口可供业务实现时，应说明发现/注册方式、调用时机和作用域。普通业务不实现平台调度、编译、装配或持久化内部协作者；跨多个独立 Service 的领域编排仍由业务明确声明事务。

## 模块职责与文档用途

| Maven 模块 | Javadoc 的阅读重点 |
| --- | --- |
| `muyun-common` | 模型、字段声明、上下文、公共结果与值类型 |
| `muyun-ability` | 能力组合、基类、生命周期 hook 和能力扩展契约 |
| `muyun-dynamic` | 动态记录门面、元数据与运行态描述；底层运行态供平台维护 |
| `muyun-platform` | 静态应用/模块声明、平台专题门面、贡献接口和领域值类型 |
| `muyun-iam` | 身份组织领域服务与模型；上下文不替代访问授权 |
| `muyun-web-adapter` | 通用 HTTP 协议、请求作用域和类型化 Web 策略 |
| `muyun-platform-web` | 静态标准投影、UI DSL、交付 DTO 和贡献接口 |
| `muyun-iam-web`、`muyun-dynamic-web` | 领域 HTTP 适配与协议类型；业务不继承内置 Controller 复制流程 |
| `muyun-spring-boot-starter` | 自动装配入口与配置属性；业务保留自己的应用宿主 |
| `muyun-spring-bom` | 版本清单，不含 Java 接入类型 |

HTTP 协议以各[平台专题](../platform/README.md)的 `WEB_API.md`、OpenAPI 和协议测试为准。Controller 的 Java 方法不是业务服务 API；协议 DTO 仍需保留文档，便于理解字段和值域。框架内部实现的源码供排查和平台维护使用，不要求业务复制其调用链。

## 文档收敛与兼容性

Javadoc 保留接入接口、扩展点及其完整类型依赖；没有完整分类证据的类型继续展示。包内可能同时存在声明、协议和实现，不按 `web`、`runtime`、`configuration` 或 `*Service` 等名字批量排除。

对逐项核对过职责、生产调用点、公开签名、样板与文档引用的内部协作者，可以使用标准 Javadoc `@hidden` 标记，并在源码注释说明内部职责和推荐接入入口。标记只收敛生成的 API 导航，不修改 Java 可见性、运行时签名或源码包，也不意味着对外可调用签名可以无迁移地删除。`@hidden` 的标准语义见 [JDK 21 文档规范](https://docs.oracle.com/en/java/javase/21/docs/specs/javadoc/doc-comment-spec.html#hidden)。

新增内部实现优先使用满足实际协作需要的最小 Java 可见性；确需跨包调用时保留必要的 `public`。存量公开类型若要改可见性、迁包或删除，需单独评估消费兼容性，不能借文档收敛隐式实施。未标记 `@hidden` 也不自动意味着类型可被任意继承、替换或绕过标准入口调用。

源码包保留完整生产源码；Javadoc 保留导航、搜索和真实说明，不以空文档、删除 hook 或移除协议类型换取体积下降。调整后应生成受影响模块的 Javadoc JAR，检查推荐入口、`protected` 扩展点、类型依赖和链接，并确认内部标记仅影响文档展示。
