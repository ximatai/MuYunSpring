# 静态管理页组件边界

本文记录静态管理页的组件拆分口径，用于指导后续平台配置、身份权限和稳定业务模块页面建设。

## 核心原则

静态管理页优先复用平台组件，但不为了统一形状强行封装。组件边界按职责拆分：

1. 标准管理入口由 Web DSL 声明字段、导航和动作事实，标准模块宿主负责 CRUD、权限、查询和详情会话；业务只通过受控扩展补充领域表达。专用工作区自行维护确属其职责的业务编排。
2. `RecordExplorerPanel` 负责 explorer 外壳，包括标题、刷新、搜索入口、动作区和内容槽。
3. `RecordListExplorer` 负责纯平铺列表展示，只消费已加载记录。
4. `CrudRecordListExplorer` 负责标准 CRUD 平铺列表的数据加载适配，内部复用 `RecordListExplorer`。
5. `TreeRecordExplorer` 负责标准树能力的数据加载和树展示。
6. `RecordPicker` 负责记录选择，树模型的父级选择应使用 tree 模式和父级约束。
7. `RecordDetailPanel` 负责右侧详情/编辑区域的基础外壳，包括单级标题、标题旁状态入口、右侧动作区和内容槽。
8. 业务语义封装只有在出现真实复用场景后再沉淀，例如未来的机构树选择或组织范围浏览。

## 组件层级

### 标准查询列表

`RecordQueryListSurface` 统一承载列表标题、列表操作、查询条件、表格和分页。工作台页头表达页面身份，列表标题表达内容区域，两者属于不同层级；标准主列表保留标题作为内容锚点。标题和业务操作组成稳定的左侧区域，查询区位于右侧；宿主宽度不足时查询区优先换行，不能通过压缩标题或操作按钮换取单行布局。无标题列表不保留空标题占位，适用于已经由父级详情或关联区标题完整限定语境的嵌入列表。布局由公共组件负责。

列表级业务操作通过 `operations` 插槽接入，使用常规密度的 `UiActionButton`；查询提交、重置和高级筛选属于组件拥有的查询区，不能与业务操作混成一个无语义的按钮队列。`toolbarActions` 仅作为旧接入兼容名保留，新页面不再使用。行内操作可使用紧凑密度。

枚举常驻筛选使用 `RecordQueryEnumFilter`，由调用方提供标题、选项、当前值和禁用状态，并处理值变更。组件统一标签关联、尺寸和间距，不负责加载或过滤记录。它不扩展后端 `persistentQueryControls` 描述符协议：标准查询仍由查询运行器发起，业务规则编辑会话则只筛选本地展示，整组检查、试算和应用不受展示筛选影响。

### RecordExplorerPanel

`RecordExplorerPanel` 是 explorer 的外壳组件，不访问业务数据。

抽屉内部的树或导航区使用 `embedded` 模式：取消面板自身的边框、圆角、背景和内边距，由抽屉布局统一管理留白与区域分隔。树区占满所在栏的可用空间，保留标题、搜索及工具操作；不再嵌套一层卡片。独立工作区保留默认面板样式。`MetadataSourceTree` 透传同一模式。

它适合承载：

```text
标题
刷新按钮
搜索输入框
右上角动作
内容 slot
轻量 editor slot
```

它不应承载表单保存、记录加载、权限解释或业务状态机。

管理页 explorer 标题使用单行标题，不显示业务分组 eyebrow。业务分组属于页面导航或详情区语义，不进入 explorer header。

`ManagementExplorerColumn` 的默认状态按工作区宽度自动计算；用户手动展开或收起后，该选择在当前页面会话内优先于自动策略。
展开会恢复标准并排侧栏，可以同时展开多个导航；空间不足时保留主内容最小宽度并通过工作区横向滚动承接，不显示导航浮层。
窗口缩放和选择项变化不覆盖手动状态；组件卸载后状态释放，重新打开页面重新计算默认值。

标准页面的导航交互统一由 `PageNavigatorExplorer` 承载。平面管理、列表详情和树管理布局只安排导航位置及编辑器插槽，复用同一套选择、搜索、范围就绪、排序和管理动作语义。

`editor` slot 只承载显式 create/edit 态的轻量抽屉，不承载选中记录的常驻详情展示。选中记录是 explorer body 与右侧详情区的上下文，不应让 `RecordExplorerPanel` 因为 `selected` 存在就打开 editor。常见写法是由父页面用 `mode !== 'view'` 控制 editor 可见性。

右上角动作区应保持克制：

1. 常规管理页只放一个主新增动作，使用 icon-only 圆形按钮。
2. 同一动作区不放多个相同图标的 icon-only 按钮。
3. 如果存在“新建根节点 / 新建下级”等多个创建语义，侧栏只保留默认主动作，其他动作放到详情动作区或后续菜单组件。
4. 动作语义不能只依赖 `title` tooltip 区分。

## Explorer Item 契约

列表和树的单条记录都应先映射成统一的 `RecordExplorerItemDescriptor`：

```text
title       主标题，必须有且只表达当前记录名称
secondary   一个辅助身份，可选；不要堆多个辅助字段
tag         类型或状态短标签，可选，例如“系统”“分组”“模块入口”“停用”
muted       弱化展示，可选
actions     当前记录的行内动作，可选
```

页面可以继续使用 `titleOf`、`secondaryOf`、`tagOf`、`actionsOf` 等兼容入口，但新增页面优先使用 `itemOf` 一次性声明 item descriptor。这样列表和树保持同一套视觉语言，页面只负责业务事实到 descriptor 的映射。

稳定规则：

1. `secondary` 最多一个；如果需要同时展示作用域和 alias，应优先把作用域放到 `tag`，alias 只在搜索中参与匹配。
2. `tag` 用于类型或状态，不用于承载长业务身份。
3. 编辑、删除、新建下级等当前记录动作必须进入 `actions`，不要放到 panel header。
4. 列表和树都使用 `UiRecordExplorerItem`，业务页不单独覆盖 item 字号、行高、hover 或 selected 样式。

### RecordListExplorer

`RecordListExplorer` 是纯列表 body。

它只负责：

```text
records 展示
keyword 本地过滤
selected 高亮
统一 item descriptor
停用或自定义 tag
inline action
select/action 事件
```

如果父页面已经持有记录集合、loading、错误和选择联动，应直接使用 `RecordListExplorer`。岗位管理的岗位列表属于这种场景，因为它依赖左侧分类和贡献动作权限。

列表项视觉由 `UiRecordExplorerItem` 固定。业务页面不应通过外层容器继承来改变 explorer item 的字号、行高、hover 或 selected 样式。树和列表使用同一 item 视觉契约。

### CrudRecordListExplorer

`CrudRecordListExplorer` 是标准 CRUD 平铺导航适配器。

平台“左树右卡片”包括层级树和平铺列表（单层树）。左侧表达连续的导航结构，不提供页码或翻页交互；应用管理等接入方直接沿用这一契约。平铺导航完整加载当前授权范围和搜索条件下的结果，内部可通过标准分页协议分批读取，但所有批次成功后才替换导航集合，不能仅隐藏分页栏而截断数据。分批查询保留权限、上游导航条件、排序和业务阅读语言，旧查询不得覆盖新范围；回收站导航遵守相同规则。

导航面板标题不展示结果数量，统一保持标题与操作区简洁。底部区域只承载回收站等次级入口。层级树可沿用树能力的节点加载与搜索定位；需要分页检索的数据业务使用标准列表或表格页面，不向导航组件增加分页开关。

它负责：

```text
等待 module runtime ready
调用 context.abilities.crud().query
维护 loading/error
响应 reloadKey
emit loaded/select
内部复用 RecordListExplorer
```

它适合应用、租户等独立平铺 CRUD 管理页。它不应继续增长为业务动作容器、表单状态容器或复杂查询面板。

如果某个页面的列表数据依赖其他区域选择、需要特殊权限组合、需要父页面统一控制加载时机，应把加载状态放在页面 state 中，直接使用 `RecordListExplorer`。

### TreeRecordExplorer

`TreeRecordExplorer` 是标准树能力适配器。

它负责：

```text
等待 module runtime ready
调用 context.abilities.tree().tree
维护 loading/error
响应 reloadKey
维护展开状态
树过滤
emit loaded/select/action
```

当管理页已经有 `RecordExplorerPanel` 时，搜索入口应放在 panel，树组件通过 `keyword` 消费搜索词，并设置 `searchMode="none"` 关闭内置搜索行。同一 explorer 区域不能同时出现 panel 搜索和树内部搜索。

树节点也使用 `UiRecordExplorerItem` 的视觉契约。不要在业务页单独覆盖 Ant Tree 字号来制造页面差异。

业务语义树组件不提前沉淀。比如机构树只有在多个业务页面真实复用时，再基于 `TreeRecordExplorer` 轻封装 `OrganizationTree` 或 `OrganizationSelectTree`。

### RecordPicker

`RecordPicker` 是记录选择组件。树模型的父级字段不是普通枚举，不应使用 `UiSelect` 平铺处理。

树模型编辑 `parentId` 时应使用：

```text
RecordPicker
  -> context 使用当前树模型上下文
  -> reloadKey 跟随当前树模型记录变更
  -> constraints 使用 parentRecordConstraints(currentId)
```

这样可以保持三个语义：

```text
按树形展示候选记录
禁止选择当前记录
禁止选择当前记录的下级
```

根节点通过清空 `parentId` 表达，不在选项中额外伪造“根节点”记录。

### RecordDetailPanel

`RecordDetailPanel` 是工作区右侧详情/编辑区域的卡片外壳组件，不访问业务数据。标准抽屉复用同一详情骨架，但使用无内层边框、无圆角的抽屉表面，以弱分割线界定标题区与内容区，不复用工作区卡片表面。

它适合承载：

```text
单级标题
标题右侧状态切换
右上角动作
内容 slot
```

详情区标题只保留一级标题，不显示 eyebrow 或第二级标题。启用/停用切换如果存在，应放在标题紧邻右侧，通过 `status` slot 接入；表单内不再重复展示“启用状态”字段，启停成功也不额外用详情区横幅重复表达状态。

它不应承载字段布局、保存逻辑、加载逻辑、权限解释或业务状态机。

## 常见组合

多栏管理页的列宽、右侧主体下限和窄屏降级统一由 [管理工作区布局](MANAGEMENT_WORKSPACE_LAYOUT.md) 的 `ManagementWorkspace` 提供；页面继续决定 explorer 的数量与业务联动。

两栏平铺管理页：

```text
StaticManagementLayout
  -> RecordExplorerPanel
    -> CrudRecordListExplorer
  -> RecordDetailPanel
```

流程定义等专用管理页同样使用 `StaticManagementLayout`，通过 `explorer` 提供自有加载的 `RecordListExplorer`，通过 `detail-actions` 提供当前对象的工作区动作，通过 `detail-operation` 提供配置表单的固定提交操作区。流程版本、画布和发布状态由页面维护，不强行套入普通 CRUD 状态机。新建与匹配规则编辑共用页面私有的 `WorkflowSelectionFields`，该组件只呈现受控字段，不负责身份作用域、数据加载或保存。`busy` 表达请求期间的导航锁，独立于 `mode`；加载中、加载失败和真实空列表必须分别反馈。页面留白由标准宿主提供，视图内部不重复增加一层外边距。

两栏树管理页：

```text
StaticManagementLayout
  -> RecordExplorerPanel
    -> TreeRecordExplorer
  -> RecordDetailPanel
```

三栏主子管理页：

```text
RecordExplorerPanel
  -> TreeRecordExplorer
RecordExplorerPanel
  -> RecordListExplorer 或 TreeRecordExplorer
RecordDetailPanel
```

第二栏是否使用列表或树由子模型能力决定。子模型具备树能力时应使用 `TreeRecordExplorer`，不因为它依赖左侧 scope 就降级为平铺列表；scope 只影响传入的 context 或 client。

## 当前页面口径

标准管理入口通过通用模块路由进入 `ModulePageHost`；静态 Java 声明与动态发布页面共用宿主。“静态模块”不表示仍有手写管理页。下表按实际交付边界区分主入口、受控扩展和独立工作区。

| 页面 | 当前交付 | 业务声明或保留扩展 |
| --- | --- | --- |
| 应用、模块管理 | `platform.application` 平铺管理、`platform.module` 树管理，均使用标准宿主 | 模块的菜单入口、编码、流程、动作和低代码治理通过详情扩展或专用工作区交付。 |
| 租户管理 | `iam.tenant` 标准平铺管理 | 租户应用开通与配置通过 `tenantModulePageEnhancement` 的表单/详情扩展交付。 |
| 组织、部门管理 | `iam.organization`、`iam.department` 标准树管理 | 部门的机构导航、树范围及父部门候选由 DSL 声明。 |
| 岗位管理 | `iam.position` 标准列表详情 | 岗位分类导航、列表范围及所属分类候选由 DSL 声明。 |
| 字典管理 | `platform.dictionary_category` 标准树管理 | 应用 → 类目导航及 `item` 树资源由 DSL 声明；`dictionaryCategoryPageEnhancement` 只补类目父级必须为目录的策略。 |
| 字段 UI 控件 | `platform.field_ui_control` 标准平铺管理 | `properties`、`bindings` 为受管聚合子表，随父记录草稿编辑与保存；绑定仅对复合控件开放。 |
| 用户、系统用户视角 | 同一 `iam.user` 标准列表详情 | 租户导航由 DSL 声明；系统用户未选租户时按 `tenantId IS NULL` 查询。在线状态列、会话展开及密码动作通过 `userModulePageEnhancement` 交付，不存在独立系统用户管理页迁移项。 |
| 职员管理 | `iam.employee` 标准列表详情 | 机构导航及任职聚合由 DSL 声明；账号设置通过 `employeeModulePageEnhancement` 的受控抽屉交付。 |
| 角色管理主入口 | `iam.role` 标准列表详情 | `roleModulePageEnhancement` 补归属范围导航、枚举展示、角色组成员字段，以及绑定和授权动作面板。 |
| 角色详情恢复视图 | 已注册的 `iam.role.detail` → `RoleDetailWorkspaceView` → `RoleManagementView` | 仍有独立详情/编辑/保存/删除会话，是 TD-046 唯一剩余收敛项；主入口已迁移不代表这条恢复链路已收口。 |
| 角色授权、账号/任职绑定 | `iam.role.authorization` 工作区及角色增强动作抽屉 | 授权包含模块动作矩阵、数据授权范围和跨模块草稿；绑定包含候选选择及授权生效上下文，均保留专用业务面板，不能用角色普通 CRUD 替代。授权抽屉支持提升为页签及会话恢复，路由 `/iam/role/authorization` 不替换角色菜单主入口。 |
| 审批流程 | 专用流程管理工作区 | 维护流程定义、版本和发布会话，继续复用公共列表、导航锁、详情及操作区，不属于本轮普通管理页迁移核销范围。 |

前端事实入口为 [通用模块路由](../../muyun-web/src/app/router.ts)、[专用路由](../../muyun-web/src/platform-admin-runtime/platformAdminRoutes.ts)、[页面增强注册](../../muyun-web/src/platform-admin-runtime/workspaceViews.ts) 和 [工作区注册](../../muyun-web/src/platform-admin-runtime/workspaceViewContributions.ts)。页面 DSL 位于 `muyun-platform-web` 的应用、模块、字典类目/字典项、菜单管理/菜单方案、字段 UI 控件 Controller，以及 `muyun-iam-web` 的租户、组织、部门、岗位、用户、职员和角色 Controller；领域服务和模块身份不因此迁入前端或宿主层。

TD-046 只跟踪角色详情恢复视图的重复标准会话；不要求将已有专用业务工作区强行配置化。后续新增多层嵌套、批量或组合范围导航时，按真实场景判断公共契约缺口，不把这些能力预列为本次迁移债务。

## 新页面判断

新增静态管理页时按顺序判断：

1. 能否通过标准模块 DSL 表达平铺、列表详情或树管理，以及导航、字段、动作和受管 relation。能则接入标准宿主。
2. 是否只有局部业务表达需要补充。优先使用 `ModulePageEnhancement` 的字段、详情、列表或动作扩展，标准会话仍由宿主拥有。
3. 是否包含授权矩阵、发布治理等独立业务会话。明确工作区身份、输入、权限、草稿和恢复边界，再复用公共组件组合专用工作区。
4. 独立工作区内的列表或树复用 `RecordListExplorer`、`TreeRecordExplorer` 等组件；树模型的父级选择使用 `RecordPicker + parentRecordConstraints`。
5. 只有真实复用需求且能降低接入成本时，才增加业务封装或平台组件，不另建平行 CRUD 状态机。

## 状态机约束

管理页进入 create/edit 模式时，要明确取消后的返回锚点。

1. 编辑已有记录时，取消回到当前选中记录。
2. 新建子记录时，取消回到触发新建时的父级或列表上下文。
3. 新建根记录时，如果进入前已有选中记录，取消应回到原选中记录。
4. 不要用“当前 selected 是否为空”隐式决定取消行为，除非该页面已经明确没有返回对象。
5. 空列表、删除最后一条记录、切换 scope 或重新加载后，默认回到 `view`；新增必须由用户显式点击新增动作触发，除非页面明确设计为连续录入。
6. 复杂主子页面应为 create/edit/cancel、空列表和 scope 切换补状态测试。

## 抽屉承载与动作分区

管理页的记录详情、编辑与配置任务统一使用 `RecordDetailDrawer` 或 `RecordModeDrawer`。它们默认在当前 workbench tab 内展开，不覆盖导航、顶部栏或其他 tab；只有全局任务才允许显式使用 viewport 侧栏。

抽屉 header 只放标题、状态和关闭；可滚动内容进入 body；编辑、删除、刷新、取消、保存、确认等所有业务操作进入固定“操作区域”（`operation` 插槽）。页面不得依赖自身容器定位来改变抽屉高度或覆盖范围。静态管理页对外使用 `explorer` 与 `detail` 表达记录浏览和详情维护职责，不使用 `sidebar`、`card` 等视觉位置词。

操作区域由 `DrawerOperationBar` 提供统一的主操作排序、对齐和窄屏换行；业务页面仅提供操作内容。标准抽屉受控支持 `width` 与 `scope`，默认 `520` 和 `tab`；`viewport` 必须显式声明。

## 必填字段表达

标准描述符表单与手工编排表单统一使用 `RecordFieldLabel` 表达字段标签及必填标记。描述符表单消费字段 `required`，手工表单显式声明 `required`，输入控件同时声明相应的必填语义。可选字段不显示标记；隐藏的必填字段必须有可用默认值，校验失败时应展示可修正的字段。必填表达不代替服务端校验。

### 公式输入框

`FormulaExpressionEditor` 提供领域内的行内公式呈现：已知字段和函数映射为视觉标签，运算符和常量保留文本编辑。公共值始终是原始公式字符串，不产生可执行 AST 或独立持久化编辑模型。宿主提供字段、函数目录以及拖入权限，负责规则会话、预检与应用。

底层 `UiTokenInput` 负责原子标签与文本的混合输入、原文偏移选区、键盘、粘贴、输入法和拖放；adapter 不知道字段、函数或公式语义。`selection`、`focusSelection` 与拖放事件均使用原始字符串偏移，避免业务标签长度改变插入位置。业务视图不直接操作 contenteditable DOM。
