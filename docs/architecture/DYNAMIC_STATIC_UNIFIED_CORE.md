# 动静一体核心设计

平台封装遵循“平台做重、业务做轻”：Service 层以 Ability 和类型化贡献承接公共能力，Web 层以 DSL 声明模块交付事实，前端以来源无关 descriptor 驱动标准页面并通过受控扩展点注入个性逻辑。平台负责把三层声明编译、校验并运行，业务模块只保留领域不变量、交付表达和真正个性化的实现。

## 目标

静态模块和动态模块是同一个平台的两种接入方式：

| 类型     | 定义来源                    | 典型形态                   | 必须共享的能力                                   |
| -------- | --------------------------- | -------------------------- | ------------------------------------------------ |
| 静态模块 | Java 类、注解、DAO、Service | 内嵌平台业务、稳定领域业务 | CRUD、树、排序、引用、生命周期、建表、权限、审计 |
| 动态模块 | 运行态元数据                | 可配置业务对象             | CRUD、树、排序、引用、生命周期、建表、权限、审计 |

两者可以有不同的声明方式，但不应有两套数据操作、两套生命周期或两套平台能力。

## 可插拔业务边界

Service 与 Web Controller 都是业务接入平台的边界。公共业务不应因来自静态 Java 或动态元数据而在某一层复制实现：Service 通过 Ability、生命周期和领域贡献接口接入；Web Controller 通过标准投影、来源无关 descriptor 和小型 Web 策略接入。两者都可以按模块插拔，且不应反向成为其他业务模块的基础依赖。

模块自身保留领域事实和表达：例如模块 alias 与应用归属的约束、父子不可跨应用、页面字段标签、导航组合或独立业务动作。平台统一管理可由多个业务复用的流程：CRUD、树、排序、引用、启停、租户请求范围、权限与动作可用性、OpenAPI 发现投影、页面能力契约和表单运行器。HTTP 请求、路径变量或 scope 只有在改变交付投影时才进入类型化 Web policy；它们不进入领域 Service。反之，领域不变量不应留在 Controller。

跨领域 Web 模块共享的请求生命周期、scope 或协议能力必须位于 `muyun-web-adapter` 或更低层的稳定契约中。某个 `*-web` 模块的模块专属 Controller、业务 Service 或业务策略不能成为另一个领域 Web 模块的生产依赖；平台配置类 Web 服务可以编排平台模块事实，但不拥有通用请求范围或领域规则。

页面交付阶段的“动静一体”不表示要建设两套静态/动态页面内核。动态 UI 配置和静态模块声明可以有不同来源，但进入运行态前应编译到来源无关的 resolved descriptor，并继续共享同一套数据访问、能力语义、生命周期、权限、审计、租户和事务边界，不能为了页面配置或静态声明另起平行基础内核。

Definition 和 Web DSL 只负责声明，不能成为请求期重复解释的第二事实源。静态声明和动态已发布快照应在启动、发布或刷新阶段编译为面向前端的 `ResolvedModuleUiDescriptor` 与面向服务端的 `ModuleExecutionPlan`；标准 Web Runtime 只消费已编译计划执行查询、页面上下文绑定、读投影和变更约束。缺少计划、字段越界或约束不可执行应在编译阶段失败，不能在请求期静默降级。

这是标准交付路径的稳定契约，不表示存量兼容已经全部清零。当前静态读 transport 的剩余适配和职员模块的兼容路径分别登记为 `TD-043`、`TD-049`；它们不是新的业务接入方式，在完成迁移前也不能被描述为已经满足严格执行计划边界。

标准页面允许前端以 `ModulePageEnhancement` 在受控区域补充业务表达，但扩展只能声明模块/视图/表面/字段位置并接收冻结 draft、字段状态、受控 `setField`、session 与 validity 回报。标准宿主仍独占保存、字段 wire codec、上传传输、权限、刷新和 CRUD 请求；后端 DSL 不携带 Vue 组件名、脚本或 URL。

一层直接子资源通过受管 detail relation 接入同一标准宿主。Web DSL 分别声明可执行 query、可选 mutation、父记录绑定和来源无关适用条件，并在启动期编译出固定 association 协议、动作码、查询/列表/编辑字段事实；运行期只消费冻结计划。后端网关先按父场景的数据权限读取持久化父记录（读为 `VIEW`，写为 `UPDATE`），再绑定父键并校验 child 归属、子资源动作权限、数据权限和写字段白名单。预解析的数据范围只能作为与 Service 实例、操作和记录精确绑定的临时执行上下文传递，网关最终仍调用 Service 的正常多态入口，不得绕过软删、领域 override 或能力组合生命周期。前端标准 surface 独占分页查询、字段 codec、validity、busy/关闭门禁、错误、局部刷新和父切换的陈旧响应隔离，业务不拼 URL、不预载专用选项，也不把模块特判放进 Host。当前可写 handler 只接受静态 Service adapter；动态模块只读/可写 relation 必须在获得稳定的动态执行 handler 后再开放发布，不能因 descriptor 来源无关就宣称已经支持动态 mutation。多层嵌套、树、批量、拖拽、任意组件和组合范围导航不属于该契约。

受管 relation 的编辑与保存语义必须显式声明。`INDEPENDENT` 保留子资源独立命令；聚合子表只在父记录编辑期间提供行内草稿，单元格输入、新增和移除均不发写请求，主表取消会丢弃全部子表草稿。父模型通过 `@Children` 暴露集合，子模型通过 `@ChildOf` 声明归属，标准父模块 `view/insert/update` 负责装配和提交完整聚合，`ChildrenAbility` 在同一事务中委派 `ChildAbility` 执行子记录生命周期；前端不得调用独立 relation 或 aggregate 特殊保存接口。列表查询默认不装配子集合，避免把详情负担扩散到列表。当前聚合草稿只开放给静态 Service handler 驱动的一层直接子资源；动态 mutation、多层嵌套、跨行公式和批量粘贴仍不在稳定契约内。

## 能力目录

平台能力使用同一个目录表达，但按声明方式分层：

详细能力索引见 [平台能力清单](ABILITY_CATALOG.md)。本文只保留动静一体语义分层。

| 类型         | 含义                                               | 当前能力                     |
| ------------ | -------------------------------------------------- | ---------------------------- |
| 基线能力     | 平台实体天然具备，不需要业务或动态元数据逐项声明   | CRUD、软删除、生命周期、缓存 |
| 字段声明能力 | 会要求模型提供标准字段，并改变运行时 API 或行为    | 树、排序、引用               |
| 独立定义能力 | 由关系、引用依赖等独立配置声明，不适合塞进单个字段 | 父子聚合、引用依赖           |

静态接入可以通过接口、基类、注解或后续扫描结果表达能力；动态接入通过 `EntityDefinition`、字段定义、关系定义和引用定义表达能力。进入运行态后，它们都应落到同一套 ability 语义。

## 核心底座

平台核心底座包括：

1. `EntityContract`：统一基础字段和生命周期字段。
2. `BaseDao`：统一静态模型的数据访问入口，默认基于 MuYunDatabase。
3. `CrudAbility`：统一插入、查询、更新、硬删除、分页、计数、平台内部链和业务扩展 hook。
4. `SoftDeleteAbility`：统一软删除过滤、软删除写入和忽略软删读取。
5. `SortAbility`：统一排序字段、列表排序和相邻移动。
6. `TreeAbility`：统一父子关系、祖先、后代、环保护和树位置校验；树天然具备同级排序语义。
7. `ReferenceAbility`：统一标题解析和引用选项读取，保留 RAW 读取入口。
8. `ChildAbility` / `ChildrenAbility`：统一父子聚合的子表插入、更新替换、自动装配和父删联动；父子写链路必须由调用方事务包裹。
9. `CacheAbility`：统一按 ID 和全量列表的本地缓存、写后失效和缓存对象副本隔离。
10. `ReferencerAbility`：声明当前模型引用了哪些来源模型，为后续引用依赖失效提供稳定入口。

动态模块接入时，应复用同一套语义，而不是另起一套动态 CRUD。`EntityCapability` 是能力目录，不只是动态开关；其中基线能力会自动归一到实体定义上，字段声明能力和独立定义能力仍由具体模型配置触发。

## 动态运行态接入

动态模块没有静态 Java Service，但运行态仍应落到同一条能力链路：

```text
DynamicRecordService
  -> DynamicEntityService implements CrudAbility<DynamicRecord>, SoftDeleteAbility<DynamicRecord>, TreeAbility<DynamicRecord>, ReferenceAbility<DynamicRecord>, CacheAbility<DynamicRecord>
  -> DynamicRecordDao implements BaseDao<DynamicRecord, String>
  -> MuYunDatabase
```

`DynamicRecordService` 是动态记录对外门面，负责按模块别名和实体编码定位运行态服务。
`DynamicEntityService` 是单个动态实体的运行态服务，承接 CRUD、软删除、树、排序、引用、父子聚合和引用依赖采集等平台能力，并按元数据能力开关或关系配置决定哪些入口可用。动态父子聚合按元数据关系配置接入同一套 `ChildRelation`，不另起一套动态子表逻辑。
`DynamicRecordDao` 只负责动态表 SQL 映射和数据访问，不承接生命周期、权限或业务编排。

生命周期分为平台内部链和业务扩展 hook。CRUD 标准入口先调度平台内部链，再调用业务 hook；父子聚合等平台能力挂在内部链上，业务覆盖 `afterInsert`、`afterUpdate`、`afterDelete`、`afterSelect` 时不需要手动调用 `super` 来维持平台能力正确性。业务 `after*` hook 是平台能力完成后的扩展点，不用于观察或拦截平台内部链执行前的 RAW 对象状态。

乐观锁由 Ability 层统一表达：更新和带实体删除以当前记录 `version` 作为 expected version，写入时递增到下一版本；冲突时抛出 `OptimisticLockException`。动态 DAO 和静态 Repository 都通过 MuYunDatabase 条件写入口执行 `id + version` 约束写入；静态删除走条件删除，动态软删走条件更新，避免在业务层手写并发控制。

前端静态编辑页保存时必须把详情加载得到的 `version` 带回更新 payload。业务 normalizer 应以当前 draft 为基底，只覆盖需要归一化的业务字段；优先使用前端平台 helper `normalizeRecordDraft(draft, normalizedFields)`，不得重建一个丢失 `id`、`version` 等标准字段的对象。否则后端会只能按当前库内版本执行更新，无法识别“用户基于旧版本保存”的并发冲突。

缓存能力先作为显式能力挂载：服务实现 `CacheAbility` 后，标准 `select(id)` 可复用缓存，写链路在 `afterChanged` 之后由 CRUD 内部统一失效。静态服务默认缓存命名空间包含服务类、模块别名和 DAO 实例；动态运行态缓存命名空间在同一 `DynamicRecordRuntime` 内按模块和实体稳定，在不同运行态之间隔离。缓存对象必须通过 `copyForCache` 进出，避免调用方修改返回对象污染缓存内容。跨模型引用缓存失效已有本地进程内闭环：`ReferencerAbility` 采集引用依赖，目标记录变更时清理引用方缓存；跨节点治理后续再升级。

父子聚合与缓存的关系采用“缓存父记录、按次装配子记录”的策略。父记录命中缓存后仍会重新按关系读取 children，不把装配后的 children 写回父缓存；这样子表变更不需要反向清理父缓存，也避免父缓存被聚合状态污染。聚合装配出的 child 是否继续执行 child service 的完整 `afterSelect` 语义，需要单独设计递归边界，不能在无深度控制的情况下隐式递归。

## 模型定义边界

静态模型和动态模型可以有不同声明入口，但进入平台底座前应编译成统一定义。

1. 静态模型由 Java 类、注解、DAO 和 Ability 组合声明；业务开发者不应为了接入平台再手写 `ModuleDefinition`。

静态 Service 的 Ability 组合也是标准交互能力的事实源。Ability 只在规范方法上用 `@PlatformOperation(PlatformAction.X)` 声明动作，不声明 URL、HTTP method 或请求 DTO，也不额外重复声明类型级 ability code。具体 Service 确需收窄时，使用 `@DisablePlatformOperations` 直接停用少量动作；停用不影响 Service 内部调用，并使默认 Web、UI、权限声明和后续 OpenAPI 同时看不到该动作。

静态业务应用通过独立的 `@PlatformStaticApplication` 声明类注册一次应用别名、标题和排序；声明类是应用的稳定 Java 身份，不承载通用 Boot Bean 装配。它被组件扫描或显式 `@Import` 后自动进入静态应用注册目录；同一应用下的静态模块以必填的 `@PlatformStaticModule(application = XxxApplication.class)` 直接指向它。模块别名以 Service 为事实源，承载模块的 Controller 或声明组件会在启动期校验一致并校验模块 alias 属于应用 alias。仅需注册模块、实体和权限动作而无独立 HTTP 入口时，使用实现 `StaticModuleServiceDeclaration` 的组件承接同一注解，并返回对应 `CrudAbility`；它不产生 Web endpoint。HTTP 路径仍保留原生 `@RequestMapping`：默认范围只能声明唯一的 `/<moduleAlias>`；父资源、嵌套资源、兼容旧路径或其他非标准路径须以 Web 层的 `@PlatformStaticWebScope(CUSTOM)` 显式标记；没有 Web 映射的模块不因此失效。启动期先协调平台托管 Application，再注册模块和动作；模块引用未声明应用会直接失败。人工在管理台创建的 Application 不属于静态声明协调范围，静态应用被移除时按平台托管规则停用而不物理删除。

当前自动 Web 投射范围收敛在启停、排序、树和回收站。完整 CRUD 与查询/详情协议仍由稳定的 `CrudWeb` / `QueryViewWeb` Web adapter 交付，暂不塞入现有投射编译器；它们仍服从同一份动作声明与停用规则。`QueryViewWeb` 只描述 controller 暴露 `query`、`view` 两个标准端点，不表达 service 的业务可写性。后续扩展必须先形成唯一的 `PlatformOperationDefinition → WebEndpointProjection → RegisteredWebEndpoint` 编译链，再扩大自动投射范围。

Web 层通过标准投射描述组合模块基础路径、动作相对路径、HTTP method 和输入绑定，并把启用的 Operation 注册为真实 Spring MVC mapping。所有编译端点进入同一个平台 Dispatcher，不为每种 Ability 生成 Handler 类。端点在 Spring MVC 接受后写入真实端点目录，目录保留实际 `RequestMappingInfo`、Operation 语义和执行目标；模块运行态、Action 权限和后续 OpenAPI 应消费这条统一链路。动态元数据后续也应编译到相同 Operation 和端点目录，不能再维护一套独立硬编码路径。

静态模块在 Web Controller 上以 `@StaticModuleOpenApi` 声明 `GET /{moduleAlias}/openapi`；平台启动时据此注册精确映射，`CrudWeb` 与非 CRUD 静态模块均可按需接入，且不把文档交付误建模为 CRUD 类型能力。该入口与动态模块统一输出 OpenAPI 3.1.1：平台以来源无关的 `PlatformApiDocument` 作为内部编译模型，再投影为标准 `openapi`、`paths`、`components.schemas` 和 Bearer 安全方案。模块文档和 descriptor 的描述权限统一为模块 `VIEW`；API 目录和静态文档路径都会按同一动作策略（包括平台配置覆盖）过滤。静态 Schema 从已编译的 `EntityDefinition` 产生，动态 Schema 从运行态 descriptor 产生；动作码、权限码、引用、候选来源和时间语义通过 `x-muyun-*` 扩展保留，不用私有格式替代标准 OpenAPI。

标准 Ability 端点、Web 投影差异和独立业务 HTTP 接口遵守三条边界：

- 标准启停、排序、树和回收站端点由 Ability 自动装配；`@PlatformStaticActionContribution` 子资源也进入同一编译链，其资源前缀动作和权限继承关系写入 resolved endpoint。
- 路径变量派生的机构、父资源或模块范围属于 Web 投影，使用类型化 `RecordWebProjectionPolicy`、`TreeWebProjectionPolicy` 等策略接入统一 Dispatcher，不在 Controller 里重写一套标准端点。
- 同一模块确需在另一条基础路径投射标准动作时，Controller 使用 `@PlatformStaticWebProjection(module = "...")` 锚定该投影；它可仅收窄这一条路径的暴露动作，不能替代模块声明，也不改变 Service 的能力组合。所有实现 `RecordWebProjectionPolicy` 的静态 Controller 都必须具有模块、子资源贡献或此投影锚点，启动期会校验，避免标准端点静默遗漏。
- 静态模块、子资源贡献和额外投影的标准端点映射由应用上下文契约测试统一核验：按 Service 能力与局部收窄规则计算期望映射，并与 Spring 已接受的端点目录逐项一致，避免共享路径或后续接入出现静默缺口。
- 开发态会输出已注册端点目录（`endpointId`、模块、动作、方法、路径和来源）；编译端点执行期间将 `endpointId` 与既有 `traceId` 一同进入日志上下文，用于从异常、权限和运行日志回溯实际端点。
- 真正独立的业务 HTTP 契约继续使用原生 Spring Controller。确需完全替换某个标准端点时，先在具体 Service 用 `@DisablePlatformOperations` 停用对应动作，再声明显式 `@ActionEndpoint`；标准动作仍启用时发生同路径覆写会在启动期失败。
- 独立 Controller 新增目标模块动作时使用 `@PlatformStaticActionDeclaration(module = "...")`，动作目录由该声明一次性发布；`@PlatformStaticActionScope(module = "...")` 只可绑定目标模块已声明且语义完全一致的动作，不得借 Web scope 创建或覆盖动作。子资源仍使用 `@PlatformStaticActionContribution`，其资源前缀动作与模块级动作保持不同来源边界。模块本体、子资源贡献、独立动作声明、动作 scope 与额外标准投影是互斥的 Controller 归属方式，启动期会拒绝混用，避免扫描目录和运行期鉴权选择不同模块。

旧 `EnableWeb`、`SortWeb`、`TreeWeb`、`RecycleBinWeb` 只作为动态链路和存量兼容入口，不是新的静态模块接入方式。静态业务行为扩展优先留在 Service/Ability hook；仅 HTTP 语境差异进入类型化 Web 投影策略，避免把 URL、请求 DTO 或路径变量污染到 Service。2. 动态模型没有 Java 类，才直接使用 `ModuleDefinition`、`EntityDefinition`、`FieldDefinition` 表达配置态。3. `Definition` 是动态配置和平台内部编译结果，不是静态业务接入的额外负担。4. 字段定义只表达字段事实、物理列、类型、约束和运行态必须知道的轻量字段行为。字典绑定可以作为编译后的运行态事实进入 `FieldDefinition`，但字典类目、项目维护和启用校验仍属于平台字典能力；默认值、过滤、影响等复杂字段行为应进入后续独立配置。5. 模块运行时标识统一使用点分平台模块别名，例如 `iam.organization`。

## 建表路线

平台需要支持两条建表路径：

1. 静态模型：`StaticEntityTableMapper` 根据 Java 模型和注解编译成 `TableWrapper`，再由 `StaticSchemaService` 创建或校验表结构。
2. 动态模型：`DynamicTableMapper` 根据运行态元数据编译成 `TableWrapper`，再由 `DynamicSchemaService` 创建或更新表结构。

建表是平台责任，不是业务 service 责任。表名、字段名等 SQL 标识符必须走白名单校验。破坏性 DDL 必须有明确治理模式，不能作为普通保存动作的副作用。

静态和动态两条路径的共同交汇点是 MuYunDatabase 的 `TableWrapper`。平台标准字段由 `PlatformTableValidator` 统一校验，避免两条路径演化出不同的基础字段、主键、租户列或生命周期列。

静态 DAO 继承的 `ensureTable()` 保留为 MuYunDatabase 提供的开箱入口，适合单个 repository 自检或轻量场景。
平台级初始化、批量拉齐、dry-run、strict migration 和后续审计治理，应统一从 `StaticSchemaService` 进入。
业务 Service 不应为了保存普通业务数据而手工触发表结构变更。

Schema migration 默认策略由平台运行模式决定：`DEVELOPMENT` 默认使用普通 execute，`PRODUCTION` 默认使用 strict execute。显式传入 `MigrationOptions` 时以调用方选项为准，例如治理接口的 dry-run 或 strict 不被运行模式覆盖。运行模式只提供默认治理口径，不替代配置包、迁移任务和破坏性 DDL 的显式审批边界。

## 能力挂载原则

后续工作流、编码规则、生成规则、回写、导入导出、附件、字典、权限、审计等平台能力，都应优先考虑能否同时挂载到静态模块和动态模块。

UI 配置、查询模板、菜单入口、页面 bootstrap、引用候选、附件页面交付等页面能力应优先编译到静态和动态可共用的 descriptor、读投影和动作语义。短期只落到一侧的能力应记录为阶段限制；能力调用的数据读写、动作权限、数据权限、审计、附件业务关系、事务和生命周期仍必须回到平台统一能力链路，不能绕过静态/动态共用的底层契约。

标准模块管理页也属于这条收敛链：列表、详情抽屉、表单和动作区由同一 descriptor 驱动的模块运行器交付，不因模块来自 Java 静态声明或动态元数据而分出两套页面壳。`dynamic-module-host` 是既有菜单/页签 descriptor 的兼容标识；其运行器实际承载的是来源无关的标准模块 CRUD 页面，不应据此在业务 App 复制动态专用页面。

字段控件是从配置、descriptor、渲染、校验到 wire value 的完整协议。可发布控件必须具备前后端一致的值形态和可执行 renderer，否则应在静态启动或动态发布阶段拒绝；后端 descriptor 不携带 Vue 组件名、脚本或模块路径。业务个性 UI 通过前端登记的 enhancement、稳定 slot 或 field renderer 接入，不复制标准页面宿主。

静态模块的列表和表单字段是对外 UI 暴露策略，不从 Java 实体自动推断。需要通过标准管理页交付的静态 Controller 应实现 `StaticModuleUiContributor` 并声明 `ModuleUiDefinition`；这样字段顺序、标签、必填、控件和敏感字段可见性都有显式边界。业务 App 只写本模块的 UI 声明，继续复用平台的列表、抽屉和表单组件。

应用管理、模块管理和岗位管理分别作为系统级平铺模块、平台树形自举模块和普通租户业务模块的长期样板。新增静态业务应从这三个样板判断领域逻辑、Web policy、UI Definition 与前端 enhancement 的归属，并以契约测试证明接入，而不是复制平台运行时编排。

静态模块引用和 service 级读投影的当前稳定契约见 [静态模块引用与读投影契约](STATIC_REFERENCE_READ_PROJECTION.md)。静态与动态列表读取都按 `ReferenceTarget` 聚合引用 ID，复用同一 `ReferenceAbility` 批量补齐标题和字段投影；静态可安全编译的 SQL join 只是该统一语义的优化路径。
静态 `@ReferenceLoad` 的多跳声明会先编译为只含 `ReferenceTarget` 与字段 hop 的 `ReferenceLoadPath`，再通过同一引用投影契约执行；动态元数据接入多跳读取时应产出该路径契约，不得另建动态专用读取内核。
动态侧使用 `EntityReferenceLoadDefinition` 声明来源字段、终端字段、输出字段与类型化 hop；hop 显式给出 `ReferenceTarget` 和当前节点的 `via` 字段，以便配置保存期完成校验。静态与动态的多跳路径均由 `ReferenceLoadReader` 执行，且来源与可校验 hop 必须为 `ONE` 基数；零跳的直接读取则复用普通引用投影的单值或集合语义。动态 `EntityReferencedByDefinition` 只声明目标实体、来源实体、来源引用字段和虚拟输出字段，运行态按同一 CRUD 查询链路装配反向集合，不把反向关系硬编码到 Service。
引用候选按目标模块的 `REFERENCE` 数据权限过滤；来源记录已取得读取权限后，其标题和字段投影只遵循租户、软删除和字段保护，不额外以目标业务数据范围过滤来源记录。

如果某个能力短期只能挂到一侧，应记录为阶段限制，不能把它包装成最终形态。

能力字段属于平台契约，不属于业务配置自由项。静态模型通过 Java 字段和注解声明能力字段；动态模型一旦开启字段声明能力，必须使用同一组标准字段名、列名和类型。例如树能力统一使用 `parentId` / `parent_id`，并自动包含排序能力的 `sortOrder` / `sort_order`；启停能力统一使用 `enabled` / `enabled`，但不属于基线字段，也不参与平台默认过滤。`tenantId` / `tenant_id` 属于所有平台实体的基础字段，不由业务元数据重复声明。后续工作流等需要标准字段的能力也按同一原则处理；软删除、生命周期和缓存属于基线能力，不要求在动态元数据中重复声明。

租户过滤属于默认 Ability 作用域，不属于业务 DAO 的手写条件。运行时存在当前租户时，插入会补齐 `tenantId`，默认查询、分页、计数、按 ID 读取、更新和删除都应在同一租户作用域内执行；无租户上下文时按系统态处理。

## 运行时边界

动态运行时可以缓存或编译模块快照，但快照只是性能优化和一致性边界，不应成为绕过模型、权限、审计、校验和数据访问契约的第二套内核。
