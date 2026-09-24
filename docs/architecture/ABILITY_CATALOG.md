# 平台能力清单

本文是能力索引，不替代代码和测试。它用于让接手者快速判断：某个业务问题应优先复用哪项平台能力、需要什么模型前提、使用时有哪些边界。

能力实现主要位于 `muyun-ability`。静态业务通过模型契约、基类、注解和 ability 接口接入；动态业务通过元数据能力声明编译到同一套运行语义。

## 能力使用原则

1. 业务 Service 优先表达能力组合，不重复写 CRUD、租户、软删、树、排序、引用、子表等通用逻辑。
2. 静态链路优雅优先。动态链路由平台维护，静态业务会大量编写，能力设计要优先降低静态 Service 的样板代码。
3. 能力启用后字段语义必须稳定。动态侧开启能力时应使用平台标准字段，不允许运行态随意改名。
4. 能力文档只记录稳定边界；细节行为优先由 contract test 锁住。

## 基础链路

| 能力                            | 核心解决问题                                                          | 主要依赖                               | 注意点                                                                                                                                                                                 |
| ------------------------------- | --------------------------------------------------------------------- | -------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `CrudAbility`                   | 统一插入、查询、分页、更新、删除、乐观锁、生命周期 hook 和平台内部链  | `EntityContract`、`BaseDao<T, String>` | 所有业务能力的主入口。平台内部链先于业务扩展 hook 执行，业务覆盖 `after*` 不应破坏平台能力。                                                                                           |
| `AbstractAbilityService`        | 给静态 Service 提供 `moduleAlias`、`modelClass`、`dao` 和常用校验辅助 | `CrudAbility`、`BaseDao`               | 标准静态 Service 通常继承它；不要把普通非业务工具继续塞进这里。                                                                                                                        |
| `StandardBusinessService`       | 给普通静态业务收口保存前规范化、通用保存校验、插入校验和更新校验 hook | `AbstractAbilityService`               | 适合不需要系统态或租户态写入门禁的普通业务，业务优先覆盖 `normalizeBeforeMutation`、`validateBeforeSave`、`validateBeforeInsert`、`validateBeforeUpdate`，避免重复覆盖多个 CRUD hook。 |
| `SystemStandardBusinessService` | 在系统态写入校验基础上收口系统配置保存 hook 模板                      | `SystemManagedAbility`                 | 适合租户等明确要求系统态维护的配置；系统态业务不要直接用 `StandardBusinessService` 绕过系统上下文。                                                                                    |
| `BaseDao`                       | 屏蔽静态 DAO、动态 DAO 和底层数据访问差异                             | MuYunDatabase 默认实现                 | 生命周期、权限、软删等不应下沉到 DAO；DAO 只负责数据访问。                                                                                                                             |

静态 Service 的 `mutateFields(policy, id, mutation, fields...)` 供领域动作声明少量业务字段变更，仍执行正常 `update` 的租户门禁、数据范围、版本、校验、缓存和生命周期。普通资料保存不得覆盖的动作专用字段，在 `beforeUpdate(incoming, existing)` 中用 `retainCommandFields` 保留。字段白名单由服务端声明，不能接受客户端字段名；该辅助入口适用于具备无参构造器的静态模型，不是跳过业务校验的 DAO patch。密码动作是当前接入样板，动态动作输入仍按现有元数据契约另行编译。

## 数据状态与作用域

| 能力                               | 核心解决问题                                                | 主要依赖                                                                 | 注意点                                                                                                                                  |
| ---------------------------------- | ----------------------------------------------------------- | ------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------- |
| `MutationScopeAbility` | 将记录归属要求从可覆盖的业务 hook 中分离 | `CrudAbility` 内部写链 | 由租户态、系统态能力实现；更新与删除按库内记录判断。覆盖业务 hook 不会取消门禁。 |
| `DataScopeAbility` | 按动作统一数据范围查询与变更校验 | 宿主安装的 `DataScopeCriteriaService` | 默认从平台运行时解析，业务无需复制 provider/getter；未装配时明确失败。叠加回收站能力后，列表、保留记录和恢复来源读取自动复用数据范围。 |
| `SoftDeleteAbility`                | 统一软删除写入、默认过滤和忽略软删读取                      | `EntityContract.deleted/deletedAt`                                       | 默认读写隐藏已删除数据；确需读取已删除数据时使用明确的 RAW/ignore 入口。                                                                |
| `EnableAbility`                    | 统一启用、停用、启用校验和启用条件构造                      | `EnabledCapable.enabled`                                                 | 启停不是默认过滤条件；业务需要时显式调用 `enabledCriteria` 或 `requireEnabled`。                                                        |
| `SystemManagedAbility`             | 限制系统级配置只能在系统态维护                              | `TenantContext.system(reason)`                                           | 适合租户、应用、平台模块等系统态配置；写入前可做 `normalizeBeforeMutation`。                                                            |
| `PlatformManagedProtectionAbility` | 保护平台托管记录，限制普通运行态创建、删除和核心字段修改    | `PlatformManagedCapable.systemManaged`、`PlatformManagedMutationContext` | 适合模块动作、元数据标准字段等由平台贡献或初始化数据维护的记录；普通入口默认只允许启停和排序，平台同步应显式进入托管 mutation context。 |
| `TenantActiveScopedAbility`        | 限制租户内业务写入必须处于有效租户上下文                    | `TenantContext.currentTenantId()`、`ActiveTenantVerifier`                | 写入前会要求租户上下文并校验租户有效；适合组织、部门等租户内业务。                                                                      |
| `TenantActiveScopedService`        | 收口租户有效性依赖与标准保存校验 hook | `StandardBusinessService`、`TenantActiveScopedAbility`                    | 后续租户内静态 Service 优先继承它，而不是重复声明 verifier 字段和转发方法。                                                             |
| `GlobalScopedAbility`              | 表达不受当前租户过滤影响的全局配置读取                      | `SoftDeleteAbility`                                                      | 适合租户自身、平台全局配置等；不要用于普通租户业务绕过隔离。                                                                            |

`SystemManagedAbility`、`PlatformManagedProtectionAbility` 和 `InitialDataAbility` 表达不同边界：

- `SystemManagedAbility` 是 service 级写入门禁，要求写入口处于系统态。
- `PlatformManagedProtectionAbility` 是 record 级运行态保护，保护已由平台贡献、生成或初始化维护的 `systemManaged` 记录。
- `InitialDataAbility` 是启动和同步时的期望数据校准能力；执行托管记录写入时应显式进入 `PlatformManagedMutationContext`。

接入 `PlatformManagedProtectionAbility` 时，模型应实现 `PlatformManagedCapable` 并复用 `systemManaged` 字段。普通入口默认只允许 `enabled` 和 `sortOrder`，不要把业务身份、来源、绑定、权限语义或物理结构字段加入默认白名单。确有领域内平台派生记录需要由普通业务动作创建时，应由具体 service 覆盖 `allowOrdinaryPlatformManagedInsert` 并收窄到明确字段角色或来源。

平台运行模式由 `PlatformRuntimeModeProvider` 读取，当前只区分 `DEVELOPMENT` 和 `PRODUCTION`，Spring Boot 配置项为 `muyun.runtime.mode`，未配置时默认 `PRODUCTION`。运行模式只作为平台治理策略的默认输入，例如 schema migration 默认选项、配置包、调试接口和安全默认值；它不替代 `TenantContext.system(...)`、`PlatformManagedMutationContext` 或具体 Ability 的显式边界。Schema migration 在开发态默认 execute，在产品态默认 strict execute；显式传入的 `MigrationOptions` 优先。

## 结构能力

| 能力          | 核心解决问题                                       | 主要依赖                                 | 注意点                                                                                                                                                                   |
| ------------- | -------------------------------------------------- | ---------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `SortAbility` | 统一排序字段、完整分区重排、相邻移动               | `SortCapable.sortOrder`                  | `reorder` 必须覆盖完整排序分区；静态模型以 `@SortPartitionBy` 一次声明分区字段，动态实体以 `sortPartitionFields` 声明。查询范围与跨分区校验由同一 `SortPartition` 派生。 |
| `TreeAbility` | 统一树形父子关系、根节点、子节点、祖先后代、环保护 | `TreeCapable.parentId`，天然包含排序能力 | 树天然支持同父级排序；`parentId` 是隐式排序分区键，静态/动态声明的业务分区字段会自动与它组合。                                                                           |

## 引用与聚合

| 能力                | 核心解决问题                                          | 主要依赖                                                 | 注意点                                                                                                                                                                                                                                           |
| ------------------- | ----------------------------------------------------- | -------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `ReferenceAbility`  | 给被引用模型提供标题、选项、投影读取能力              | `EntityContract`、`TitledCapable` 或 `@TitleField`       | 标题解析不应触发目标业务的完整变更逻辑；找不到标题字段应 fail-fast。                                                                                                                                                                             |
| `ReferencerAbility` | 给引用方声明引用依赖，支持标题回填、投影和缓存失效    | `@ReferenceTo` 等静态引用声明或动态引用配置              | 引用依赖是跨模型缓存失效和展示解析的基础，不应在业务 Service 里手写散落逻辑。                                                                                                                                                                    |
| `@ReferencedBy`     | 按模型声明装配只读反向引用列表                        | `@ReferencedBy`、来源 `CrudAbility`                      | 平台按列表泛型来源模型自动解析唯一来源 service 并复用其数据域读取；不授予写入、替换或删除来源记录的权限。                                                                                                                                        |
| `ChildAbility`      | 给子表 Service 提供子记录选择、排序和软删兼容入口     | 子模型 `EntityContract`                                  | 子表自身仍是标准实体能力组合，不应脱离 CRUD 链路。                                                                                                                                                                                               |
| `ChildrenAbility`   | 给父表 Service 提供父子聚合插入、替换、装配和父删联动 | `ChildRelation`、`@Children` / `@ChildOf` 或动态关系配置 | `@ChildOf` 必须与同字段 `@ReferenceTo` 共存；父删除遵循该引用的 `integrity`：仅 `CASCADE_DELETE` 清理子项，`RESTRICT` 阻断，`PRESERVE_HISTORY` 保留。`null` 子列表表示不改子表，空列表表示清空。普通反向展示使用 `@ReferencedBy`，不进入本能力。 |

引用写入完整性由 `ReferenceWriteValidator` 统一执行，静态 `@ReferenceTo` 无需额外实现 `ReferencerAbility` 即可生效；动态定义和判别引用复用同一执行器。检查读取受租户、软删约束的原始引用事实，不经过候选列表数据权限或输出脱敏。`SAME_TENANT` 同时比较源、目标记录的租户，系统上下文不能绕过；租户等平台级目标应显式声明 `GLOBAL`。

`@ReferenceIntegrity(requireEnabled = true)` 表示每次保存及恢复均要求目标存在且启用，包括未改变引用值的更新。目标必须具备标准启停状态；元数据配置和可解析目标的编译校验提前拒绝不匹配的声明，写入时复核同一契约。动态 `ReferenceIntegrityPolicy`、元数据引用配置和字段属性草稿传递同一声明。默认 `false` 允许更新保留既有的失效引用，但引用值及其依赖字段必须保持不变；`onTargetUnavailable` 单独控制目标删除时的保留、阻断或级联。主岗归属等领域不变量继续由业务 Service 表达。

## 字段治理

| 能力                     | 核心解决问题                                               | 主要依赖                                                               | 注意点                                                                                                                                                                                   |
| ------------------------ | ---------------------------------------------------------- | ---------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `FieldProtectionAbility` | 统一字段加密、签名校验和输出脱敏的运行语义                 | 静态字段注解或动态字段保护元数据、`FieldCryptoProvider`、`FieldSigner` | 写入时临时转换为存储态并立即恢复业务对象；更新 hook 从 RAW 快照保留受保护字段时使用 `retainProtectedFieldFromStorage`，统一解密和验签且不修改快照。动态侧保护配置独立于字段基础表，签名伴生字段由平台生成。                                                                                       |
| `PlatformRecordOutput`   | 统一对外输出转换入口，承接静态选项标题、字段脱敏等展示增强 | `CrudAbility`、`RecordOutputContext`、`RecordOutputTransformer`        | 不污染 service 默认读链；Web、表单、导出和自定义输出应优先走该门面，而不是在 Web 或业务层散落专项转换。新增输出增强优先增加 `RecordOutputTransformer`，不要新增专项 `XxxOutputAbility`。 |

`PlatformRecordOutput` 是输出投影门面，不是 CRUD 生命周期的一部分。业务 service 默认读链保持 raw 业务对象；对外响应、导出、消息推送或跨 service 组装展示数据时，应由调用方显式选择输出上下文。当前已接入静态 `@OptionField` 标题补全和字段脱敏；动态元数据字段的标题补全等转换等真实动态业务推进时再接入同一门面。

## 缓存、事件与执行支撑

| 能力                                                    | 核心解决问题                                                               | 主要依赖                                              | 注意点                                                                                                                                                                                                                                                                                                                             |
| ------------------------------------------------------- | -------------------------------------------------------------------------- | ----------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `CacheAbility`                                          | 统一按 ID 和全量列表缓存、写后失效、事务内绕过、对象副本隔离               | `CrudAbility`、`CacheRegistry`、`TenantContext`       | 缓存命名空间包含服务、模块和 DAO；跨模型引用失效依赖 `ReferencerAbility`。                                                                                                                                                                                                                                                         |
| `MutationTransactionOperator` | 为标准插入、更新、删除、恢复和清理提供统一事务入口 | 宿主事务管理器 | 聚合写入失败整体回滚，不要求每个业务复制事务壳。 |
| `PlatformAbilityDispatcher`                             | 调度平台内部 after 链，避免业务 hook 覆盖破坏平台能力                      | CRUD 生命周期                                         | 新能力如果需要挂入 CRUD 内部链，应优先考虑这里，而不是要求业务手动调用 `super`。                                                                                                                                                                                                                                                   |
| `PlatformOperation` / `DisablePlatformOperations`       | 声明规范 Service 动作，并为少量特殊模块停用默认公开动作                    | `PlatformAction`、Ability 规范方法                    | Ability 不声明 HTTP；普通 Service 零额外配置，停用只写具体动作数组，不引入 exposure 档位或动作组。                                                                                                                                                                                                                                 |
| `StaticAbilityWebEndpointRegistrar`                     | 把静态 Service 的标准 Operation 投射为真实 Spring MVC 端点，并沉淀注册目录 | 静态模块锚点、Ability、`RequestMappingHandlerMapping` | 当前承接启停、排序、树和回收站独立端点；HTTP 契约由投射描述提供，所有编译端点复用单一 Dispatcher，不生成 Ability Handler 类。注册目录供权限与后续 OpenAPI 复用。                                                                                                                                                                   |
| `RecordWebProjectionPolicy` / `TreeWebProjectionPolicy` | 给标准端点补充路径派生范围等纯 Web 投影约束                                | 静态模块或子资源 Controller、统一 Dispatcher          | 只表达 HTTP 语境范围，不承载领域行为；独立业务接口继续使用 Spring Controller。完整替换标准端点前必须在具体 Service 停用对应 Operation。                                                                                                                                                                                            |
| `RuntimeEventPublisher` 等事件组件                      | 提供 after-commit 运行事件发布和监听边界                                   | `TransactionScopeSupport`、事件 listener              | 平台审计只记录必要上下文；工作流等专题应保留自己的流水。                                                                                                                                                                                                                                                                           |
| `@ModuleExtension` / `@RuntimeEventHandler`             | 给模块运行事件提供声明式扩展处理器                                         | `RuntimeEvent`、`RuntimeEventHandlerRegistry`         | 这是 Ability 事件链路上的扩展点，不是绕过权限、租户、生命周期和审计的插件内核；默认 after 类事件提交后执行且失败告警，非 after 类事件事务内执行且失败阻断。类级 `entityAlias` 可作为方法级默认值，方法级声明优先。handler phase 只约束处理器执行时机，不改变事件源发布时间；已由事件源 after-commit 发布的事件不会回到事务内执行。 |

标准写入由 `MutationTransactionOperator` 包裹，Spring 宿主通过事务管理器装配；父子聚合及其生命周期共享事务。仅手工构造、未安装事务执行器的独立使用不承诺原子回滚。跨多个独立 Service 的领域编排仍应声明外层事务。

## 选型提示

| 业务场景                                | 推荐能力组合                                                                                         |
| --------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| 系统态维护的全局配置，如租户、应用      | `SystemManagedAbility + GlobalScopedAbility + EnableAbility + SortAbility`                           |
| 租户内树形业务，如组织机构、部门        | `TenantActiveScopedService + SoftDeleteAbility + EnableAbility + TreeAbility + ReferenceAbility` |
| 可被其他模型选择的基础资料              | `ReferenceAbility`，必要时叠加 `EnableAbility`、`SortAbility`                                        |
| 引用了其他模型且需要标题/投影展示的业务 | `ReferencerAbility` + 静态引用注解或动态引用配置                                                     |
| 主子表聚合保存和读取                    | 父 Service 实现 `ChildrenAbility`，子 Service 实现 `ChildAbility`                                    |
| 读多写少且需要减少重复查询的模型        | `CacheAbility`，并确认写后失效和引用依赖失效测试覆盖                                                 |

## 后续维护规则

1. 新增能力接口、能力基类或重要能力支撑组件时，同步补一行清单。
2. 只有能力语义、依赖或接入方式稳定后才写入本文；探索过程和临时方案不要写进来。
3. 如果能力已废弃或合并，及时删除或改名，保持清单准确。
