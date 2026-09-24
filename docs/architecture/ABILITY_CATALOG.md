# 业务 Service 能力接入目录

本文面向平台二次开发者，按业务需要说明：选择什么入口、声明什么事实、平台自动完成什么，以及哪些决定仍属于业务。它不是所有内部类的清单。静态 Java 声明与动态元数据进入同一套能力执行链；具体默认组合以各自接入入口为准。

## 先选择 Service 入口

| 业务场景 | 推荐入口 | 自动获得 / 仍需选择 |
| --- | --- | --- |
| 租户内业务，如组织、部门、员工 | `TenantActiveScopedService<T>` | 标准保存模板、有效租户写入门禁；数据权限、删除方式等按业务选择 |
| 必须在系统态维护的配置 | `SystemStandardBusinessService<T>` | 标准保存模板、系统态写入门禁；需要全局读取时叠加 `GlobalScopedAbility` |
| 不需要上述写入门禁的业务 | `StandardBusinessService<T>` | 标准保存模板；不自动赋予系统态，也不自动绕过租户过滤 |
| 底层适配、特殊生命周期或已有完整模板 | `AbstractAbilityService<T>` / `CrudAbility<T>` | 标准 CRUD 链；普通业务优先使用上面的入口 |

推荐基类统一提供模块身份、模型类型、DAO 和保存校验入口。业务优先覆盖 `normalizeBeforeMutation`、`validateBeforeSave`、`validateBeforeInsert`、`validateBeforeUpdate`；平台门禁与规范化独立于业务保存 hook，`after*` 无需手动调用 `super` 维持平台能力。

标准 CRUD 自动执行版本控制、生命周期及已声明能力的内部链。通过 Starter 接入时，平台安装标准写入事务与能力运行时，普通业务不复制事务壳、权限 provider 或引用 resolver。跨多个独立 Service 的领域编排仍声明外层事务；手工构造且未安装事务执行器的独立使用不承诺原子回滚。

**继承标准基类不等于启用所有业务能力。** 软删除、回收站、启停、缓存均需显式选择；未接入软删除的 CRUD 使用硬删除。普通可维护资料推荐软删除，绑定表等是否保留历史由业务决定。动态实体定义目前默认归一 CRUD、生命周期、软删除和缓存；这不表示静态 Service 自动实现同名接口，也不表示所有读取都命中缓存。

学校示例见 [StudentService](../../muyun-demo/src/main/java/net/ximatai/muyun/spring/demo/school/student/StudentService.java)；租户业务见 [OrganizationService](../../muyun-iam/src/main/java/net/ximatai/muyun/spring/iam/organization/OrganizationService.java) 和 [DepartmentService](../../muyun-iam/src/main/java/net/ximatai/muyun/spring/iam/department/DepartmentService.java)。示例中的每个可选能力都表达业务选择，不要求照搬整组。

## 按业务需要选择能力

| 业务需要 | 最小接入 | 平台自动完成 | 边界与取舍 |
| --- | --- | --- | --- |
| 删除后保留记录 | `SoftDeleteAbility` | 软删、默认隐藏、恢复基础入口、版本与生命周期 | 不自动开放业务回收站；明确读取保留记录时使用 ignore 入口 |
| 向操作者开放回收站 | `RecycleBinAbility` | 包含软删；回收站查询、恢复接入、数据范围组合 | 最终清理默认关闭；资源显式启用并校验保留期等规则 |
| 启用 / 停用 | `EnableAbility` + `EnabledCapable.enabled` | 标准启停动作、启用校验与条件构造 | 停用记录仍可见；不等于删除，不自动级联停用下游 |
| 手工排序 | `SortAbility` + `SortCapable.sortOrder` | 分区重排、相邻移动、跨分区校验 | `reorder` 提交完整分区；业务以 `@SortPartitionBy` 声明分区 |
| 树形结构 | `TreeAbility` + `TreeCapable.parentId/sortOrder` | 父子、祖先后代、根节点、环保护、同级排序 | 已包含排序，无需重复实现 `SortAbility`；`parentId` 自动加入排序分区 |
| 作为引用候选目标 | `ReferenceAbility` + `TitledCapable` | 标题、候选、引用投影与原始完整性事实读取 | `@TitleField` 可指定标题来源，不能替代接口的 `TitledCapable` 类型约束 |
| 字段加密、签名或脱敏 | `FieldProtectionAbility` + 字段注解 | 存储保护、验签、按输出上下文脱敏 | 宿主配置密钥/provider；存储保护与展示脱敏各有执行边界 |
| 减少重复读取 | `CacheAbility` | ID / 全量列表缓存、副本隔离、写后失效、事务内绕过 | 缓存读取自动维护引用依赖，普通查询与事务内读取不改写共享缓存依赖；当前为进程内失效 |

标准字段名和含义在动静两侧保持一致。拥有 `deleted`、`enabled` 等字段本身不等于 Service 已开启相应行为。不要因“开箱即用”自动开放恢复、最终清理或推断业务级联。

删除与恢复完整边界见 [软删除、回收站与删除日志治理](../platform/SOFT_DELETION_AND_RECYCLE_BIN_GOVERNANCE.md)。普通 CRUD 和父子聚合共享整次写入事务；回收站恢复/清理协调器按节点独立提交，允许部分成功和续跑，两者不能混用事务承诺。

## 模型声明一次，平台负责执行

| 共性业务规则 | 声明入口 | 自动获得 |
| --- | --- | --- |
| 租户内单字段或组合字段唯一 | `@TenantUniqueConstraint` | 标准写入校验、数据库唯一冲突翻译、保留记录冲突提示 |
| 机构内、部门内等业务分区排序 | `@SortPartitionBy` | 同一分区条件用于查询、移动和树父节点校验 |
| 记录引用及完整性 | `@ReferenceTo` / `@ReferenceIntegrity` | 标准写入和恢复校验、显式删除策略；有缓存时自动维护直接引用依赖 |
| 关联标题或摘要字段 | `@ReferenceLoad` / `@ReferenceSummary` | 按声明解析、批量读取；不要求注入目标 Service 或另写标题拼装 |
| 只读反向关联集合 | `@ReferencedBy` | 自动解析来源 Service，并复用来源的数据范围；不获得来源写入权 |
| 父子聚合关系 | `@Children` + 子模型外键上的 `@ChildOf`、`@ReferenceTo` | 在父子能力组合内自动发现关系、绑定归属并装配 |

例如 [Department](../../muyun-iam/src/main/java/net/ximatai/muyun/spring/iam/department/Department.java) 声明机构内编码唯一、同机构排序和引用启用机构。业务 Service 保留机构范围等领域意图，不重复实现这些公共流程。员工主岗判定、账号解绑等领域不变量继续放在业务代码中。

### 引用的推荐接入

```java
@ReferenceTo(target = OrganizationService.class,
        integrity = @ReferenceIntegrity(requireEnabled = true,
                onTargetUnavailable = ReferenceTargetUnavailablePolicy.RESTRICT))
private String organizationId;

@ReferenceLoad(source = "organizationId", field = "title")
private transient String organizationTitle;
```

使用平台宿主时，普通静态 Service 无需额外实现 `ReferencerAbility`，即可获得声明的引用完整性、读字段装配和缓存依赖维护。Service 的能力编译结果也从模型关系推导引用依赖。`ReferencerAbility` 保留给动态运行态和确有自定义引用行为的扩展，不是普通业务的必选标记。

缓存依赖由缓存能力统一维护，业务不手工刷新或删除依赖。标准写入在提交后失效，回滚保留原缓存；`clearCache()` 和动态 runtime 清理同时回收对应范围的依赖，不影响其他 Service/runtime。

引用关系、候选范围、展示字段和生命周期策略各自声明：

- `@ReferenceTo` 表达关系；完整性读取受租户与软删约束的原始事实，不经过候选数据权限或输出脱敏。`SAME_TENANT` 比较源与目标归属，系统上下文不能绕过；平台级目标显式声明 `GLOBAL`。
- `requireEnabled = true` 要求每次保存和恢复的目标均存在且启用，目标必须具备启停契约。默认 `false` 允许普通更新原样保留已有失效引用，但不能通过改变引用值或其依赖字段引入失效引用。
- `onTargetUnavailable` 独立决定目标删除时保留、阻断或级联；平台不根据字段名、普通引用或集合形状猜测级联。
- `@ReferenceLoad` 声明稳定的关联读事实；标准单条、列表和分页读取自动装配。普通列表不自动装配反向集合和聚合子集合，避免读取规模随关联膨胀。

候选选择、只读展示、领域读取聚合和列表筛选排序的详细边界见 [静态模块引用与读投影契约](STATIC_REFERENCE_READ_PROJECTION.md)。

## 作用域与权限：组合使用，语义独立

| 决策 | 接入 | 不包含的承诺 |
| --- | --- | --- |
| 数据受当前租户过滤 | 普通 CRUD 默认归属范围 | 无租户时不自动要求有效租户；这由租户写入门禁负责 |
| 数据按全局范围读取 | `GlobalScopedAbility` | 不包含软删，也不授予系统写入权限；可组合独立的软删、缓存、回收站 |
| 写入必须处于有效租户 | `TenantActiveScopedService` / `TenantActiveScopedAbility` | 不等于操作者拥有该记录的数据权限 |
| 写入必须处于系统态 | `SystemStandardBusinessService` / `SystemManagedAbility` | 不自动允许修改平台托管记录 |
| 按操作者和动作限制记录范围 | `DataScopeAbility` | 普通 `select/list` 不会隐式变成某个动作的授权读取 |
| 保护平台托管的记录 | `PlatformManagedProtectionAbility` + `PlatformManagedCapable` | 不替代 Service 级系统态门禁或初始化同步 |

`DataScopeAbility` 默认使用宿主权限运行时；缺少装配时明确失败。标准交付入口按动作衔接数据范围，自定义业务读取使用 `selectForAction`、`listForAction` 等入口。普通读取保留租户/软删范围，供领域内部读取；它们不是对外授权接口。特殊归属映射由业务声明 `dataScopeFieldMapping`，例如部门以 `organizationId` 和自身 `id` 表达机构、部门范围，不能由平台猜测。

组织、部门的完整参考组合是 `TenantActiveScopedService + SoftDeleteAbility + EnableAbility + TreeAbility + ReferenceAbility + DataScopeAbility`，并声明适用的归属映射。回收站叠加数据权限时，平台自动复用范围解析，业务不再复制权限分支。

平台托管记录默认只允许普通入口修改 `enabled`、`sortOrder`。`InitialDataAbility` 负责启动/同步时的期望数据校准；托管同步显式使用 `PlatformManagedMutationContext`。身份、来源、权限、物理结构等字段不能加入通用修改白名单。运行模式不替代上述边界，配置说明见 [平台文档入口](../platform/README.md#平台运行模式)。

## 主子表：先确定生命周期所有权

| 业务场景 | 推荐方式 | 关键边界 |
| --- | --- | --- |
| 父记录统一提交明细 | 父 `ChildrenAbility`，子 `ChildAbility`，模型声明关系 | 平台自动装配，子项仍执行标准 CRUD 生命周期，与父写入共享事务 |
| 只查看来源记录 | `@ReferencedBy` | 不获得替换、删除或级联权限 |
| 关联记录独立管理、独立授权 | 独立 Service 与关系交付 | 不把可见部分当成父聚合的完整明细 |

聚合保存中，子集合 `null` 表示不修改，空集合表示清空。`@ChildOf` 必须与同字段 `@ReferenceTo` 共存；父删仅在该引用声明 `CASCADE_DELETE` 时清理子项，`RESTRICT` 阻断，`PRESERVE_HISTORY` 保留。

通用 `ChildAbility` 读取不支持直接叠加独立 `DataScopeAbility`，会明确拒绝。否则权限过滤可能把未返回的子项误判为删除意图。独立授权的子资源优先采用独立命令；确需聚合时，应提供具有完整集合语义的显式读取与授权方案，不能简单取消保护。子项排序、软删后的业务身份复用等领域差异通过既有 hook 扩展，不由平台自动猜测。

## 领域动作、输出与扩展

- `mutateFields(policy, id, mutation, fields...)` 适用于具备无参构造器的静态模型，声明少量字段变更并保留正常更新的作用域、版本、校验、缓存和生命周期。动作专用字段在普通更新 hook 中使用 `retainCommandFields` 保留；组合字段保护时自动解密、验签后保留业务值，业务无需增加分支。字段白名单由服务端声明。动态动作仍按动态输入契约编译。
- `FieldProtectionAbility` 写入时临时转换存储态并恢复业务对象；从 RAW 快照保留受保护字段使用 `retainProtectedFieldFromStorage`。加密、签名与脱敏可以独立声明，不为每种策略新增能力接口。
- `PlatformRecordOutput` 是标准对外输出门面，承接选项标题、脱敏等转换。领域默认读取不做展示脱敏；Web、导出、推送和自定义展示在交付边界选择输出上下文。新增转换实现 `RecordOutputTransformer`，不新增专项 `XxxOutputAbility`。
- Ability 规范动作通过 `PlatformOperation` 进入统一交付；具体 Service 用 `DisablePlatformOperations` 停用少量不适用动作。Ability 不声明 HTTP。标准静态启停、排序、树和回收站端点由平台投射；CRUD 仍由标准 Web adapter 接入。
- 路径派生条件使用 `RecordWebProjectionPolicy` / `TreeWebProjectionPolicy`；领域行为留在 Service。替换标准端点前停用对应 Operation。
- `@ModuleExtension` / `@RuntimeEventHandler` 承接运行事件扩展。默认 after 事件提交后执行、失败告警，其余事件事务内执行、失败阻断；handler phase 不能把已提交后发布的事件搬回事务内，也不能绕过权限或审计。工作流等专题保留自己的业务流水。

## 平台维护者索引

以下组件负责装配与执行，不是普通业务必须逐项选择的能力：

| 组件 | 职责 |
| --- | --- |
| `BaseDao` | 数据访问；不承载权限、生命周期和领域编排 |
| `MutationScopeAbility` | 内部写链的强制归属门禁，独立于可覆盖的业务 hook |
| `MutationTransactionOperator` | 宿主安装标准写入事务 |
| `PlatformAbilityDispatcher` | 平台生命周期链与能力协作 |
| `ReferenceWriteValidator` / `ReferenceDependencyRegistry` | 引用完整性执行与缓存依赖维护 |
| `StaticAbilityWebEndpointRegistrar` | 标准动作到真实 HTTP 端点的投射与注册 |
| `RuntimeEventPublisher` | 运行事件发布与事务时机 |

维护本目录时，优先补充业务使用方式、自动保障和组合限制；新增内部类不要求新增一个业务能力条目。文档只记录稳定契约，行为细节由测试证明。
