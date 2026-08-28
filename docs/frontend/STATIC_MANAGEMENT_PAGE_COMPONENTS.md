# 静态管理页组件边界

本文记录静态管理页的组件拆分口径，用于指导后续平台配置、身份权限和稳定业务模块页面建设。

## 核心原则

静态管理页优先复用平台组件，但不为了统一形状强行封装。组件边界按职责拆分：

1. 页面负责业务编排、表单字段、动作含义和跨区联动。
2. `RecordExplorerPanel` 负责 explorer 外壳，包括标题、刷新、搜索入口、动作区和内容槽。
3. `RecordListExplorer` 负责纯平铺列表展示，只消费已加载记录。
4. `CrudRecordListExplorer` 负责标准 CRUD 平铺列表的数据加载适配，内部复用 `RecordListExplorer`。
5. `TreeRecordExplorer` 负责标准树能力的数据加载和树展示。
6. `RecordPicker` 负责记录选择，树模型的父级选择应使用 tree 模式和父级约束。
7. `RecordDetailPanel` 负责右侧详情/编辑区域的基础外壳，包括单级标题、标题旁状态入口、右侧动作区和内容槽。
8. 业务语义封装只有在出现真实复用场景后再沉淀，例如未来的机构树选择或组织范围浏览。

## 组件层级

### RecordExplorerPanel

`RecordExplorerPanel` 是 explorer 的外壳组件，不访问业务数据。

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

`editor` slot 只承载显式 create/edit 态的轻量抽屉，不承载选中记录的常驻详情展示。选中记录是 explorer body 与右侧详情区的上下文，不应让 `RecordExplorerPanel` 因为 `selected` 存在就打开 editor。常见写法是由父页面用 `mode !== 'view'` 控制 editor 可见性。

右上角动作区应保持克制：

1. 常规管理页只放一个主新增动作，使用 icon-only 圆形按钮。
2. 同一动作区不放多个相同图标的 icon-only 按钮。
3. 如果存在“新建根节点 / 新建下级”等多个创建语义，侧栏只保留默认主动作，其他动作放到详情动作区或后续菜单组件。
4. 动作语义不能只依赖 `title` tooltip 区分。

动作区的视觉层级由平台组件决定，页面只声明动作语义：新增入口使用默认态的圆形 explorer action；保存、发布、确认等会提交关键状态转换的动作才使用 primary；搜索、刷新、收起使用紧凑 text/icon；删除与回收站按 danger/quiet 语义呈现。业务页不得通过 `record-panel-*` 一类 CSS class 重定义尺寸、圆角或强调色。

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

`CrudRecordListExplorer` 是标准 CRUD 平铺列表适配器。

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

| 页面     | Explorer 组合                                                                                 | 状态口径                                                                                                         |
| -------- | --------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| 应用管理 | `StaticManagementLayout -> CrudRecordListExplorer -> RecordListExplorer -> RecordDetailPanel` | 平铺 CRUD 状态复用 `useFlatCrudManagementState`，详情区复用标准详情外壳。                                        |
| 租户管理 | `StaticManagementLayout -> CrudRecordListExplorer -> RecordListExplorer -> RecordDetailPanel` | 平铺 CRUD 状态复用 `useFlatCrudManagementState`，不内置特殊租户保护规则，详情区复用标准详情外壳。                |
| 组织管理 | `StaticManagementLayout -> TreeRecordExplorer -> RecordDetailPanel`                           | 页面直接依赖树能力，避免在主业务页套业务语义树封装，详情区复用标准详情外壳。                                     |
| 部门管理 | `RecordExplorerPanel -> TreeRecordExplorer` 和 `RecordExplorerPanel -> TreeRecordExplorer`    | 左侧机构树只作为 scope 选择，不承载机构管理动作；部门树按机构 scope 加载和维护。                                 |
| 岗位管理 | `RecordExplorerPanel -> TreeRecordExplorer` 和 `RecordExplorerPanel -> RecordListExplorer`    | 分类树和岗位列表由页面统一编排，岗位列表加载依赖选中分类。                                                       |
| 字典管理 | `RecordExplorerPanel -> TreeRecordExplorer` 和 `RecordExplorerPanel -> TreeRecordExplorer`    | 应用 scope 由页面控制，类目树和字典项树由页面统一编排；字典项父级使用 `RecordPicker + parentRecordConstraints`。 |

## 新页面判断

新增静态管理页时按顺序判断：

1. 是否是独立平铺 CRUD 列表。是则优先使用 `CrudRecordListExplorer`。
2. 是否是标准树模型。是则优先使用 `TreeRecordExplorer`。
3. 是否是树模型的 `parentId` 字段。是则优先使用 `RecordPicker + parentRecordConstraints`。
4. 列表数据是否依赖其他区域选择或复杂权限组合。是则页面 state 自己加载，body 使用 `RecordListExplorer`。
5. 是否需要业务语义封装。只有跨页面真实复用后再沉淀，不为单一页面提前封装。
6. 是否需要新的平台组件。只有它能降低重复、稳定边界并减少接入成本时再新增。

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
