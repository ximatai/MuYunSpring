# 平台配置与元数据自举

## 目标

平台配置自举的目标是让平台能管理自身配置态：应用、模块、元数据、菜单和数据字典。它不是完整管理后台，也不展开用户、组织、角色、工作流等独立业务。

这条链路要证明：

1. 平台配置业务本身可以复用基础能力体系。
2. 动态模块不再只靠代码或测试构造元数据，而能从持久化配置刷新到运行态。
3. 模块、元数据、菜单、字典使用统一命名和关系边界，避免后续业务接入时重新发明规则。

## 标识规则

| 对象         | 字段                      | 规则                                                            |
| ------------ | ------------------------- | --------------------------------------------------------------- |
| 应用         | `applicationAlias`        | 全局唯一，创建后不允许手动修改                                  |
| 模块         | `moduleAlias`             | 全局唯一，必须满足 `applicationAlias.xxx`，创建后不允许手动修改 |
| 元数据       | `metadataAlias`           | 在应用下唯一，不能作为物理表身份                                |
| 菜单方案     | `menuSchemeAlias`         | 在同一租户/scope 内唯一；菜单节点不设计 alias/code              |
| 数据字典类目 | `dictionaryCategoryAlias` | 在应用下唯一；字段绑定字典类目                                  |
| 数据字典项目 | `dictionaryItemCode`      | 在字典类目下唯一；业务数据默认存项目 code                       |

`Module.id` 可以等于 `moduleAlias`，但业务字段、参数、DTO 和关系表列统一使用 `moduleAlias` / `module_alias`，不使用 `moduleId` / `module_id` 表达模块身份。

`applicationAlias` 使用单段小写标识；`moduleAlias` 使用至少两段点分小写标识，并与动态运行态模块别名校验保持一致。

元数据 `id` 是平台稳定 ID，由平台生成；物理表定位至少包含 `schemaName + tableName`。表名是存储属性，不作为元数据身份。默认场景下 `schemaName` 和 `tableName` 可由平台生成，schema ensure 建表后不允许作为普通配置随意修改。

## 核心模型

### Application

应用是模块、元数据和数据字典类目的顶层归属。菜单不归属应用。应用不是树，但需要支持排序，便于管理端按业务顺序展示。

平台内置应用按能力域拆分：`platform` 承载平台配置、治理和页面交付等平台能力，`iam` 承载身份组织和权限能力。内置应用和业务应用共享 `applicationAlias` 归属模型，但语义上 `iam` 不是普通业务应用，`platform` 也不是所有内置模块的兜底归属。

建议字段：

| 字段        | 含义                                               |
| ----------- | -------------------------------------------------- |
| `id`        | 与 `applicationAlias` 保持一致或由模型适配为同一值 |
| `alias`     | 应用别名，对外参数使用 `applicationAlias`          |
| `title`     | 应用名称                                           |
| `enabled`   | 是否启用                                           |
| `sortOrder` | 排序                                               |

### Module

模块是运行入口、菜单挂载、动作权限和动态运行时定位的业务边界。

建议字段：

| 字段               | 含义                                     |
| ------------------ | ---------------------------------------- |
| `id`               | 与 `moduleAlias` 保持一致                |
| `applicationAlias` | 所属应用                                 |
| `alias`            | 完整模块别名，对外参数使用 `moduleAlias` |
| `title`            | 模块名称                                 |
| `parentId`         | 父模块；根模块使用平台树能力的根节点值   |
| `moduleKind`       | `STATIC` / `DYNAMIC`                     |
| `enabled`          | 是否启用                                 |
| `sortOrder`        | 排序                                     |

模块 alias 创建后不允许手动修改。模块按“应用 -> 多级模块”组织成树，排序范围限定在同一应用和同一父模块下。`moduleKind` 表达当前模块接入方式：静态模块可以没有元数据；动态模块必须有一个主元数据。

### Metadata

元数据是应用内业务实体定义，不等同于模块，也不等同于物理表。

建议字段：

| 字段               | 含义                                     |
| ------------------ | ---------------------------------------- |
| `id`               | 平台稳定 ID，自动生成                    |
| `applicationAlias` | 所属应用                                 |
| `alias`            | 元数据别名，对外参数使用 `metadataAlias` |
| `title`            | 元数据名称                               |
| `schemaName`       | 物理 schema，可默认                      |
| `tableName`        | 物理表名，可默认生成                     |
| `enabled`          | 是否启用                                 |

`metadataAlias` 在同一应用下唯一。`schemaName + tableName` 是物理表定位，不参与元数据语义身份。

元数据字段独立维护，但只表达字段结构事实和轻量字段分类。最小字段包括 `metadataId`、`fieldName`、`columnName`、`fieldSpecAlias`、`fieldOwnership`、`fieldForm`、`ownerFieldId`、`fieldRole`、`systemManaged`、必填、唯一、索引、标题字段和排序字段。`fieldOwnership` 只区分业务字段与平台标准字段；`fieldForm` 表达物理、虚拟、影子、伴生等字段形态。伴生和影子字段必须指向同元数据下的 owner 字段。`fieldSpecAlias` 指向平台字段类型配置；长度、精度、默认查询操作等默认值来自字段类型，字段级长度/精度、查询覆盖进入字段配置，不塞回结构字段表。`fieldName` 对齐动态运行态字段名规则，`columnName` 对齐 SQL 标识符规则；同一元数据内字段名、列名、标题字段和排序字段都必须保持唯一。

`PHYSICAL`、`COMPANION` 和 `SHADOW` 字段会编译为动态物理字段并参与 schema 映射；`VIRTUAL` 字段只进入运行态字段契约，不参与建表和 DDL。虚拟字段不能声明必填、唯一、索引、排序、标题、可查询、默认值或校验正则，也不能使用存储保护、计量单位和金额存储行为。动态 descriptor 通过 `storageForm` 暴露物理/虚拟形态，页面 bootstrap 通过 `fieldForm` 暴露平台字段形态。标准页面保存和动态 `insert` / `update` 不接受外部显式写入虚拟字段。引用标题和引用投影等读链路已经注入到运行态记录上的虚拟值可以随列表、详情和 LIST UI 投影输出，但不进入持久化值集合。公式规则可以把虚拟字段作为计算目标，`formula/preview` 返回本次计算值；保存前公式内部计算出的虚拟字段只停留在运行态记录上，不参与动态表持久化。公式虚拟字段是否在列表/详情读取时自动计算、虚拟字段是否参与导入校验，由后续具体场景单独定义。

平台字段类型配置表达一类字段的默认事实，例如运行态基础类型、默认长度/精度、默认查询操作符、默认 UI 字段类型和允许使用的 UI 字段类型。字段配置表达某个字段的覆盖，例如字段级长度/精度、字典绑定、是否可查询和查询操作符。字段配置未声明查询时继承字段类型默认；只有显式关闭时才禁用查询。

字段 UI 控件是独立平台资源，不等同于运行态字段规格，也不等同于具体页面布局。字段规格回答“数据如何存储、校验和查询”；字段 UI 控件回答“这个字段默认用哪类输入/展示控件、控件有哪些可配置属性、复合控件有哪些值分量”。复合控件的值分量通过字段绑定作为目录事实表达，例如区间的结束值或时区字段；页面 bootstrap 在 `resolvedConfig.fieldUiControls[].bindings` 下发控件目录，字段通过 `fieldUiControlAlias` 引用该目录，不把同一绑定事实复制到每个字段上。具体页面是否展示、只读、必填和标题覆盖仍由视图字段配置表达。

字段配置支持两层口径：元数据级默认配置作用于字段本身；模块关系内字段配置作用于某个模块元数据关系，用于同一元数据在不同模块入口下呈现不同字典、引用、默认值、校验、复制、生单入口或过滤/影响规则。结构字段、物理列名、字段类型以及长度/精度等物理形态仍归属元数据字段和默认字段配置，关系级配置不能改写物理形态。

`ModuleMetadataField` 是模块元数据关系下的字段配置聚合根，表达“某个 `ModuleMetadataRelation` 下某个 `MetadataField` 在当前模块语境中如何使用”。同一套元数据绑定到不同模块时，可以拥有不同的字段辅助配置。主表承载高频单值配置，例如默认值、校验正则、可复制、字典、引用模块、引用 key/label 字段、引用触发生单规则、引用查询模板和扩展字段集合；多值规则用 `ModuleMetadataFieldFilter` 和 `ModuleMetadataFieldAffect` 子表表达。查询模板、生单、回写、引用过滤等配置态能力应优先引用 `ModuleMetadataField.id`，再由服务解析出模块 alias、关系 alias、元数据 alias、字段名、列名和字段类型等运行所需事实。编码规则、生单字段映射、拆分数量字段和拆分分组字段已经按该口径保存配置坐标，并保留 `fieldName` 作为运行态快照。

模块字段引用配置会编译为动态 `EntityReferenceDefinition` 和 descriptor 契约，输出引用目标模块、目标实体、key/label 字段、触发生单规则、查询模板和扩展字段集合。`referenceGenerateRuleId` 必须通过平台 validator 校验方向：引用模块是生单 source，当前字段所属模块是生单 target。引用过滤和带出只允许挂在完整引用字段上，当前引用目标字段限定为目标模块主关系字段；运行态刷新后形成“当前表单字段 -> 引用目标字段”的过滤契约，以及“引用目标字段 -> 当前表单字段”的带出契约。运行态 `DynamicRecordService.resolveReference` 可接收当前表单值，将过滤契约叠加为引用查询条件，并在候选项上返回带出回填 patch；引用解析支持跨动态模块目标，由 `DynamicRecordRuntime` 解析 `ReferenceTarget` 到目标实体服务。`ReferenceRecordGenerationFacade` 可按当前模块、实体、引用字段和选中的引用记录触发生单规则，并负责确认草稿落库时携带 originContext；动态 Web 提供引用字段生单和生成草稿确认入口，生成草稿可携带一级子表 `children` envelope 原样确认，供动态表单查询、选择回填、引用生单、生成关系登记和回写流程消费。如后续需要引用子关系，应先补显式目标 relation 坐标。

字段行为表达服务端可执行的字段规则，当前包括默认值、校验正则、可复制标记和写保护。默认值在动态记录插入前由运行态填充；校验正则只适用于字符串类字段；写保护字段拒绝外部显式写入，但平台内部能力仍可写入自己的标准字段和关系外键。可复制标记当前只作为 descriptor 契约输出，供后续复制能力或前端交互使用。关系级字段行为可以按属性覆盖默认配置，但当前不提供“显式清空继承值”的语义；如需清空默认值或正则，应调整默认配置。视图只读只影响展示和交互，不等同于服务端写保护。

字段引用配置独立表达“源字段 -> 目标元数据”的引用关系，以及标题输出和投影输出；同样支持默认配置和关系级覆盖。当前标题、投影、候选查询、过滤和带出均通过运行态 `ReferenceTarget` resolver 解析目标实体，支持跨动态模块目标。运行态刷新时由字段引用配置编译成运行态 `EntityReferenceDefinition`。运行态 descriptor 输出模块、实体、字段、关系、引用、动作、基础视图和关联视图；动作统一由模块动作目录表达，可覆盖标准动作的启停、标题、级别和权限挂点，也可声明自定义动作。元数据描述数据结构，不再拥有独立对外动作模型；子元数据如需暴露对外动作，应显式追加到模块动作目录并指定目标实体。模块动作的 `actionCode` 在模块内唯一，对外 URL、权限授权和执行定位都以这个口径为准，不再通过实体维度区分同名动作。基础视图只表达列表/表单字段顺序、显示状态、标题、兼容控件类型和 UI 字段类型，不承载复杂布局。

关联视图是运行态交付契约，不是新的元数据关系类型。子关系生成内嵌列表入口，字段引用生成关联记录入口；descriptor 暴露关联路径、目标模块、目标实体、展示模式和查询挂点。后续如需让租户显式调整关联视图标题、排序或展示策略，应在现有关系/引用配置之上扩展，不把“关联元数据”塞回 `relationRole`。

`DynamicRecordService` 是动态模块交付的运行态门面：模块级入口负责 describe、模块动作、关系、引用和关联视图；实体级入口负责实体 descriptor、CRUD、query、reference、view、association view 和 action。descriptor 仍然只是契约输出，不直接代表权限、路由或 UI 编排；公式、工作流、编码、导入导出、推送生成、回写等平台应用能力应建设在这些稳定挂点上。

视图字段中的只读、显示、兼容控件类型和 UI 字段类型属于展示层契约，不等同于服务端写入权限；字段必填不能被视图配置放松。真正的写入限制后续应进入字段行为、动作治理或权限体系。

### ModuleMetadataRelation

模块与元数据通过关系绑定。关系表达“某个模块如何使用某个元数据”，而不是把元数据强绑定到单一模块。

建议字段：

| 字段               | 含义                       |
| ------------------ | -------------------------- |
| `moduleAlias`      | 当前运行模块               |
| `metadataId`       | 被使用的元数据             |
| `relationRole`     | `MAIN` / `CHILD`           |
| `parentMetadataId` | 父级元数据，可为空         |
| `foreignKey`       | 子表指向父表的字段         |
| `relationAlias`    | 关系别名，在模块下唯一     |
| `autoPopulate`     | 读取主记录时是否装配子记录 |
| `sortOrder`        | 关系排序                   |

约束：

1. 一个模块最多一个 `MAIN` 关系。
2. 静态模块可以没有元数据关系。
3. 动态模块必须有一个 `MAIN` 关系。
4. 关系模型允许父、子、孙结构；非 `MAIN` 关系的 `parentMetadataId` 必须已经作为同一模块的关系存在，不能只引用孤立元数据。
5. A 应用或模块下定义的元数据在 B 模块体现时，对外运行口径统一是 B 的 `moduleAlias`。

横向关联、引用选择、关联视图等“关联元数据”场景不是 `relationRole`，后续应作为引用/视图/动作等独立能力表达，避免和主子结构混用。

模块能力配置默认作用于 `MAIN` 关系对应的元数据。用户在模块上开启树、排序、启停、引用等能力时，本质上是给主元数据补齐对应标准字段和能力声明；子元数据能力仍通过关系内部配置表达。

## 跨模块体现

当一个模块使用另一个模块或应用内已有元数据时：

1. 菜单、入口、动作、查询、权限、运行时 URL 和审计口径都使用当前模块的 `moduleAlias`。
2. 元数据结构、物理表和字段定义来自被绑定的 `metadataId`。
3. 对外 API 不暴露“当前记录其实来自另一个模块”的额外切换语义。

这条规则可以降低运行态心智负担：模块负责呈现和运行入口，元数据负责结构和存储。

## 菜单

菜单是导航入口体系，不归属应用。应用仍然只是模块的归类属性；菜单节点可挂载模块，但不理解应用。

菜单按方案组织：

1. `MenuScheme`：菜单方案，支持系统、租户和机构三个 scope。系统/租户口径优先形成闭环，机构方案先保留模型边界，等机构体系进入后再启用业务闭环。
2. `Menu`：某个方案下的菜单节点。一个菜单只属于一个方案；同一个模块如需出现在多个方案或多个位置，创建多个菜单节点分别挂同一个 `moduleAlias`。

系统方案只在系统态维护和读取，不作为租户菜单 fallback 模板。默认 `platform.user.super_admin` 是系统用户，不归属任何租户；租户管理员需要访问平台能力时，应通过租户或机构菜单方案显式配置。`MenuScheme.alias/scope/tenantId` 创建后不可变，`Menu.schemeId` 创建后不可变；如需跨方案调整，应新建菜单节点或后续提供明确的整树迁移能力。

`MenuScheme` 建议字段：

| 字段        | 含义                                                             |
| ----------- | ---------------------------------------------------------------- |
| `tenantId`  | 租户 ID；系统方案为空                                            |
| `alias`     | 方案别名，在同一 scope 内唯一                                    |
| `scopeType` | `SYSTEM` / `TENANT` / `ORGANIZATION`                             |
| `scopeId`   | scope 标识；租户方案默认等于 `tenantId`，系统方案固定为 `system` |
| `title`     | 方案名称                                                         |
| `enabled`   | 是否启用                                                         |
| `sortOrder` | 排序                                                             |

`Menu` 建议字段：

| 字段          | 含义                                 |
| ------------- | ------------------------------------ |
| `tenantId`    | 从所属方案继承，用于租户隔离         |
| `schemeId`    | 所属菜单方案                         |
| `parentId`    | 父菜单；根菜单使用平台树根节点值     |
| `title`       | 菜单名称                             |
| `moduleAlias` | 可选；绑定后该菜单成为可点击模块入口 |
| `openMode`    | `tab` / `window`；入口菜单必填       |
| `route`       | 从模块入口投影的内部路由             |
| `externalUrl` | 从模块入口投影的外部链接 URL         |
| `enabled`     | 是否启用                             |
| `sortOrder`   | 排序                                 |

菜单基础能力只要求菜单方案、菜单树、排序、启停、模块挂载和入口打开方式。权限只对已解析的菜单树做剪枝，不进入菜单模型。

### 系统共享配置读取债务

系统级配置不等于租户可读配置。系统菜单方案不作为租户菜单 fallback；租户管理员需要平台入口时，应通过租户或机构菜单方案显式配置。

后续在币种、计量单位、汇率类型、字段 UI 类型、静态模块定义、默认 UI 模板和查询模板等场景中，如果需要租户运行态读取系统默认配置，应沉淀明确的系统共享配置读取能力，例如 `SystemSharedReadableAbility`。该能力应表达“读取系统共享配置”的业务意图，避免业务 service 直接散用 `TenantContext.bypassTenantFilter(...)`，同时保持系统私有数据不可被租户上下文读取。

### 初始化数据能力

平台内置业务数据通过初始化数据能力治理，而不是分散在各个启动 runner 中直接写库。该能力面向平台中期建设，承接默认菜单方案、默认分组、后续默认角色、权限模板、编码规则模板和系统配置等托管数据。

初始化数据能力提供统一执行语义：

1. 业务 service 通过实现 `InitialDataAbility<T>` 接入本领域初始化数据，普通场景只返回 `initialData()` 领域对象列表。
2. `InitialDataExecutor` 统一发现 service ability 和少量平台 declaration provider，按 phase、order、name 排序，读取声明并执行写入，同时进入 `TenantContext.system("initialize platform data")` 和系统操作者上下文。
3. 每条记录声明治理策略：`CREATE_IF_MISSING`、`RECONCILE_MANAGED` 或 `LOCKED`。
4. 每条记录按字段角色治理：`identity` 字段漂移失败，`managed` 字段可持续校准，`operator` 字段只在创建时写入，重启不覆盖运维修改。即使使用 `CREATE_IF_MISSING`，已有记录也必须通过 `identity` 检查。
5. 能力只负责生命周期、字段治理、冲突语义和执行报告；实际插入/更新默认走领域 service，不提供跨表裸 DAO 初始化框架。

启动阶段由平台 bootstrap 编排器按 `PlatformBootstrapTask` 列表接管平台启动任务，不为每一种能力单独维护专属 runner，也不把具体能力硬编码进 runner。业务 service 只声明本领域期望数据和少量 options；字段角色优先由模型上的 `@InitialDataFields` 或 `@InitialDataRole` 声明，避免业务方复制底层字段清单。扫描型数据和跨领域关系数据可以使用薄 `InitialDataDeclarationProvider`，但 declaration provider 不作为普通业务接入模型。`InitialDataDeclaration`、`InitialDataRecord` 和 `InitialDataField` 属于执行层构建块，普通业务 service 不应直接拼装。

该能力不做自动删除，也不做版本化迁移。托管记录如果已被软删，应作为数据漂移显式失败，不在启动时自动恢复或重新插入。历史数据修复、跨 scope 迁移、整树迁移应由明确的领域能力或运维任务承接。

初始化数据的锚定对象是平台托管事实，不是任意启动脚本。适合放入该能力的是默认应用、系统管理员账号、默认菜单方案、系统内置菜单和后续稳定系统配置等“期望长期存在并可按字段角色校准”的数据。租户、机构、部门、职员、角色和授权属于业务治理数据，不作为平台启动闭环的刚性事实。已上线业务记录的历史值转换、字段搬运、批量归一、跨版本数据修补和长耗时数据处理不应塞进 `InitialDataAbility` 或 declaration provider。后续如果建设运行态数据校准能力，应以 `moduleAlias` 为业务锚点，单独设计运行态门禁、dry-run、执行报告、多租户作用域和审计/后台任务边界。

当前平台 baseline 初始化以稳定内置 ID 作为记录锚点。业务唯一键仍是领域约束和数据库约束，不承担启动时的旧数据迁移匹配；未上线阶段如本地数据与内置 ID 冲突，应清理或显式修正数据，而不是让 initializer 隐式迁移业务身份。

`school-demo` profile 会创建一组租户、组织、部门、职员、租户管理员账号和租户管理员账号角色授权样例数据。该能力属于完整学校演示环境的一部分，不进入 `InitialDataAbility` 平台 baseline。演示租户管理员必须作为租户内普通用户经账号角色授权获得权限，不能复用系统超级管理员的 system user 短路。

租户创建后，平台会为该租户准备默认租户级菜单方案，并从系统超管菜单树复制一组独立菜单数据，保证租户用户解析当前菜单方案时有完整闭环。该 provision 可通过 `TenantService.provisionTenant(tenantId)` 对既有租户显式重放，用于创建后失败修复或新增系统菜单后的租户菜单同步。当前阶段采用每租户物理复制菜单的实现，适合开发和早期平台闭环；SaaS 大规模租户场景下菜单数据会随租户数线性膨胀，后续应演进为系统菜单模板加租户覆盖差异，不再为每个租户复制完整菜单树。

演示租户管理员账号默认授予内置 `tenant.admin` 账号角色。该角色只授予租户内 IAM 治理模块，不授予 `iam.tenant` 这类系统态租户维护模块，也不把平台元数据、动态模型和系统配置能力默认给租户管理员。目标角色授权模型中，该授权事实应落到 `AccountRoleGrant`，管理作用域为 `TENANT:tenantId`。

### 平台超级管理员自举

裸库启动时，平台通过 service-owned 初始化数据创建默认应用和平台超级管理员账号。平台超级管理员是系统用户，不归属租户、机构、部门或职员，也不依赖默认角色授权；系统用户在动作授权、菜单可见性和数据范围链路中以系统态执行。

平台超级管理员账号标识属于平台内置事实，不作为启动配置项开放。标题、启停、排序等展示和运维字段由 operator 语义保护，初始化只在创建时写入，后续重启不覆盖运维修改。`muyun.initial-admin.initial-password` 只承接裸库首次登录所需的初始密码，默认值为 `admin123`。

### 平台内置菜单自注册

裸库启动时，平台静态模块可通过 `@PlatformMenu` 选择性贡献系统内置菜单。`@PlatformStaticModule` 仍表达模块事实，`@PlatformMenu` 只表达导航贡献；模块可以注册但不进入菜单。

平台内置菜单由初始化数据能力承载，按数据来源分层：

1. `MenuSchemeService` 和 `MenuService` 通过 service-owned `InitialDataAbility` 维护系统菜单方案和默认导航分组。
2. `platform.menu-contributions`：由薄 `PlatformMenuInitialDataDeclarationProvider` 扫描 `@PlatformMenu` 并注册模块菜单项。

菜单领域的字段角色由 `Menu` 和 `MenuScheme` 模型上的初始化字段声明表达。固定领域数据由菜单 service 自己声明；扫描型菜单项由 provider 只构造期望菜单对象并调用通用初始化声明门面，不直接维护字段清单，也不直接执行插入/更新。

平台只维护一个系统菜单方案：

| 字段        | 值                           |
| ----------- | ---------------------------- |
| `id`        | `platform.menu_scheme.admin` |
| `alias`     | `platform_admin`             |
| `scopeType` | `SYSTEM`                     |
| `scopeId`   | `system`                     |
| `title`     | `平台超管`                   |

该方案不作为租户菜单 fallback。平台超级管理员账号以系统用户身份读取该方案；租户和机构菜单仍由各自方案维护。

平台内置菜单默认按 1-2-3 级组织：一级是稳定主导航，二级是面板分组，三级是具体模块入口。当前系统方案内置一个一级分组和三个二级分组：

| 层级 | ID                             | 标题                 | 父节点                         |
| ---- | ------------------------------ | -------------------- | ------------------------------ |
| 一级 | `platform.menu.group.platform` | 平台管理             | `ROOT`                         |
| 二级 | `platform.menu.group.config`   | 平台配置与低代码运维 | `platform.menu.group.platform` |
| 二级 | `platform.menu.group.identity` | 组织与权限           | `platform.menu.group.platform` |
| 二级 | `platform.menu.group.ops`      | 平台运行运维         | `platform.menu.group.platform` |

`@PlatformStaticModule` 声明模块能力和默认入口，未声明入口时默认是动态模块入口，声明 `route` 时是内部路由入口，声明 `externalUrl` 时是外部链接入口。`@PlatformMenu` 只声明该模块是否投影到菜单树，以及父级、打开方式、排序、启停等导航属性；菜单不保存独立节点类型，是否可点击由 `moduleAlias` 是否绑定模块入口决定。未绑定 `moduleAlias` 的菜单只是容器；绑定 `moduleAlias` 的菜单是入口，同时仍然可以拥有子菜单。入口菜单的整棵子树以父入口可见为前提；如需父节点不可点但子菜单仍可见，应清空父节点 `moduleAlias`，把父节点建成纯容器。`route/externalUrl` 由绑定模块的入口配置投影而来。`moduleAlias` 是唯一权限和模块上下文锚点。`route/link` 模块入口不承载 `pageMode/defaultUiConfig/defaultQueryTemplate` 等低代码入口配置。菜单使用 deterministic `id` 做系统托管记录的幂等治理键，例如 `platform.menu.module.platform.module`；这不是普通菜单模型的 alias/code。

注册顺序是先注册静态模块和动作，再执行初始化数据能力。平台菜单只做同方案内治理，不自动删除手工新增菜单，也不把未标注 `@PlatformMenu` 的模块放进菜单。菜单方案的 `alias/scope/tenantId` 和菜单的 `schemeId` 属于不可变身份；如果同 ID 记录已经落在其他方案或身份字段漂移，应显式失败，而不是启动时自动迁移。

菜单方案、默认分组和模块菜单的结构字段由平台持续校准；标题、排序、启停属于运维字段，创建后重启不覆盖运维调整。需要强锁定的系统数据应显式使用 `LOCKED` 策略，不作为默认行为。

前端消费菜单时不应把入口表现类型当作权限主体。`module/route/link` 只是模块入口在前端 `MenuNavigationTarget` 中的导航投影，授权、可见性裁剪和页面上下文都以 `moduleAlias` 为准。需要父级上下文的嵌套配置资源不应直接标注为顶层 `@PlatformMenu`，应等待聚合页或结构化入口参数明确后再进入菜单。

### 平台必需字典自注册

静态模型通过 `@DictionaryField` 绑定平台数据字典；字段可在同一声明中给出字典标题、排序和 `initialItems` baseline。平台从静态模块目录收集模型并转换为初始数据声明，不要求领域额外编写启动 Provider。空库会按业务唯一键补齐缺失类目和项目，已有库采用 `CREATE_IF_MISSING` 保留人工维护的标题、排序和启停。多个字段可绑定同一个字典，但同一个 `source` 最多一处声明 baseline；冲突会在初始数据执行前失败。

## 数据字典

数据字典为元数据字段、表单显示、查询条件、导入导出和规则判断提供稳定枚举来源。

数据字典拆成：

1. `DictionaryCategory`：字典类目，应用内唯一 `dictionaryCategoryAlias`，支持树。类目可作为目录，也可作为可绑定字典。
2. `DictionaryItem`：字典项目，归属具体字典类目，实体以 `categoryId` 绑定类目并保留 `categoryAlias` 作为可读冗余，支持树、排序、启停和标题。

业务字段绑定字典类目，不绑定单个字典项目。业务数据默认存字典项目 `code`，不存内部项目 ID；展示和校验时通过 `applicationAlias + dictionaryCategoryAlias + code` 解析到类目，再按 `categoryId + code` 解析字典项目。同一类目内项目 `code` 必须唯一，即使项目本身是树，也不能只按同父级唯一，否则业务数据只存 code 时会产生解析歧义。

字典管理是单一可见模块入口，静态模块别名为 `platform.dictionary_category`。字典项目维护不单独注册可见模块，它的动作作为类目模块的子资源动作贡献，动作码采用 `item_create`、`item_query`、`item_update` 等 `resource_operation` 命名，并可通过权限码继承到 `item_view` 等更粗粒度能力。

字典基础能力只建设字典类目和字典项目的基础维护；字段如何引用字典进入元数据字段行为配置，不塞进最小元数据字段模型。

## 配置到运行时闭环

配置自举至少需要跑通一条配置到运行时闭环：

```text
Application
  -> Module
  -> Metadata + Field
  -> ModuleMetadataRelation
  -> Runtime refresh
  -> DynamicRecordService.entity(moduleAlias, metadataAlias)
  -> CRUD / query
```

验收重点不是 UI 完整度，而是模型边界、运行态刷新边界和运行口径稳定。
