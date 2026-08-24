# 平台驱动前端 DSL 使用指南

本文面向静态模块开发者、动态配置维护者和前端业务开发者，说明如何使用平台页面 DSL 交付管理页面，以及何时应使用受控前端扩展或独立页面。

这里的 DSL 不是把前端组件、SQL 或业务流程写进 Java 配置。它只声明稳定的页面意图：页面骨架、导航来源、字段、标准编辑器、关系和平台 traits。静态声明与动态 UI 配置最终都会编译为同一份 `ResolvedModuleUiDescriptor`，由同一套标准页面运行器执行。

## 先做选择

优先顺序应为：**平台页面 DSL → 前端受控扩展 → 独立业务页面**。不要因为某个字段、一个列表单元格或一个业务动作就绕开标准页面。

| 情况                                           | 推荐方式                                                        | 原因                                                 |
| ---------------------------------------------- | --------------------------------------------------------------- | ---------------------------------------------------- |
| 标准 CRUD、字段展示、启停、回收站、引用选择    | 页面 DSL + traits                                               | 平台已经拥有保存、权限、租户、审计、校验和交互闭环。 |
| 按组织、租户、分类等范围筛选的标准列表         | 页面 DSL 的 navigator 和 context binding                        | 范围、查询和新建预填仍走标准链路。                   |
| 树形资源、列表详情、平铺管理页                 | `PageTemplates` 选择页面骨架                                    | 避免业务自行拼三栏布局、抽屉和列表状态。             |
| 子资源或标准关联明细                           | `detailRelation`、`managedDetailRelation`、`editorContribution` | 关系身份、父子约束、权限和保存语义由平台治理。       |
| 额外状态列、只读会话明细、密码管理等领域特性   | `ModulePageEnhancement` 受控扩展                                | 标准页面保留所有权，业务只在命名边界注入内容。       |
| 复杂工作台、跨多个聚合的编排、非标准交互主流程 | 独立业务页面                                                    | 此时页面本身就是业务能力，不应伪装成通用 CRUD。      |

动态配置维护者不需要选择另一套前端内核：动态 UI 配置同样应声明字段、视图和页面事实，经来源归一后交给标准运行器。动态配置当前没有承载的能力，不应通过任意 JSON、组件名或脚本表达式绕过平台；应先判断是否形成稳定的跨业务平台能力。

## 基本链路

静态模块的页面声明位于其 Web Controller 的 `moduleUiDefinition()`；模型、Service 和页面声明各自只保留自己的事实：

```text
Model / 注解 / Ability       字段、引用、能力与数据契约
Service                      领域行为、生命周期和不变量
moduleUiDefinition()         页面结构、字段意图、导航与标准 traits
        ↓
ModuleUiDefinition → ResolvedModuleUiDescriptor → 标准页面运行器
```

页面声明不会替代标准 Web、Service 或 Action 链路。列表查询、保存、动作授权、数据范围、租户、乐观锁、审计和生命周期仍由模块运行时负责。

## 最小标准页面

普通模块从一个已支持的页面骨架开始，再声明列表、编辑器和标准 traits：

```java
@Override
public ModuleUiDefinition moduleUiDefinition() {
    return ModuleUiDefinition.builder(CustomerService.MODULE_ALIAS)
            .page(PageTemplates.listDetailCard(page -> page
                    .list(list -> list.fields(fields -> fields
                            .title("客户管理")
                            .field("customerNo", field -> field.label("客户编号").width("140px"))
                            .field("title", field -> field.label("客户名称"))
                            .field("enabled", field -> field.label("状态")
                                    .uiType("enabledStatus").width("90px").align("center"))))
                    .detail(detail -> detail.editor(form -> form
                            .title("客户档案")
                            .field("customerNo", field -> field.label("客户编号").required())
                            .field("title", field -> field.label("客户名称").required())
                            .field("enabled", field -> field.label("启用状态")
                                    .uiType("enabledStatus"))))
                    .traits(traits -> traits.standardCrud().enabledStatus().recycleBin()
                            .responsiveDetailSurface())))
            .build();
}
```

可用的页面根骨架保持小集合：

| 骨架                                | 适合场景                                                |
| ----------------------------------- | ------------------------------------------------------- |
| `PageTemplates.listDetailCard(...)` | 一张主列表配合详情/编辑抽屉；绝大多数管理页的默认选择。 |
| `PageTemplates.flatManagement(...)` | 有范围导航但不需要树形主资源工作区的管理页。            |
| `PageTemplates.treeManagement(...)` | 主资源本身是树，且树的维护是该页面主流程。              |

如果需求不符合这三种语义，不要先给平台增加一个骨架名称；先确认它是否能由标准骨架加受控扩展解决。只有多个业务共享同一结构和运行语义时，才将新骨架上升为平台能力。

## 字段与编辑器：只表达页面意图

字段声明使用模型中已存在的字段事实。DSL 适合设置标签、顺序、必填、只读、隐藏，以及少量平台已收口的展示提示；不适合重复声明字段类型、数据库列、权限或 SQL。

```java
.field("departmentId", field -> field.label("所属部门").required().uiType("recordPicker"))
.field("enabled", field -> field.label("启用状态").uiType("enabledStatus"))
.field("passwordStatus", field -> field.label("密码状态").readOnly())
.field("internalRemark", field -> field.hidden())
```

使用要点：

- `required()` 表达页面输入要求，不替代模型校验、唯一约束或 Service 不变量。
- `readOnly()` 和 `hidden()` 仅收敛当前页面表达；字段保护、数据权限和脱敏仍由后端统一执行。
- `recordPicker` 只表示引用选择控件，候选范围和关联上下文由标准页面的 picker/context 契约提供。
- `enabledStatus` 仅用于平台启停字段；业务布尔展示使用 `booleanStatus`，编辑型业务布尔值使用通用 `switch` 语义。
- `password` 等写入后不可读字段应由模型注解和标准字段事实定义为写入型输入；前端不应通过“详情隐藏”模拟数据保护。

默认编辑器应覆盖主资源的标准新建与编辑。确实存在同一模块多个稳定编辑面时，使用 `editors(...)` 声明命名 surface；不要在外层页面复制字段集合。

## 导航、范围与内嵌管理

导航用于建立当前页面的上下文和查询范围，不是第二套业务数据模型。选择范围后，绑定会进入右侧列表查询；创建时仍要由模型引用约束、保存校验和数据权限保证真实归属。

```java
.navigator(navigator -> navigator
        .level("tenant", level -> level
                .microList("iam.tenant", "租户", "搜索租户")
                .sourceScope(PageNavigatorSourceScope.CURRENT_TENANT)
                .singleResultPolicy(PageNavigatorSingleResultPolicy.AUTO_SELECT_AND_HIDE))
        .level("organization", level -> level
                .tree("iam.organization", "机构树", "搜索机构"))
        .bindNavigatorToNavigator("tenant", "organization", "tenantId")
        .bindNavigatorToList("organization", "organizationId"))
```

常用选择：

- 用 `microList` 承载轻量范围项，用 `tree` 承载具有树能力的来源。
- `sourceScope(CURRENT_TENANT)` 表示来源受当前租户上下文约束；超级管理员是否可见由平台当前用户与来源权限规则决定，不能由页面硬编码绕过。
- `AUTO_SELECT_AND_HIDE` 只适用于“当前用户可访问范围确定且唯一”时。它是减少无意义导航的体验策略，不是权限策略。
- 默认导航是只读选择。只有明确需要在当前页维护导航来源时才调用 `.manageable()` 或 `.manageable("editorKey")`。

`manageable` 是二元开关：声明后才启用该来源的标准新建、编辑、删除；未声明时不出现编辑态。它不配置动作子集，也不绕过来源模块的 `create`、`update`、`delete` 权限、数据范围、乐观锁或编辑器校验。可选的 `editorKey` 只选择来源模块已声明的编辑 surface，不能在使用方复制一套来源字段。

### 导航范围内的主树资源

当页面的主流程是“先选范围，再维护一个独立树资源”时，仍使用 `treeManagement`，不要新增业务三栏组件，也不要把树塞进详情扩展。主树通过已注册的 `editorContribution` 声明为资源，并显式指定提供持久化父范围的导航层级：

```java
.navigator(navigator -> navigator
        .level("application", level -> level.microList("platform.application", "应用", "搜索应用"))
        .level("category", level -> level.tree("platform.dictionary_category", "类目", "搜索类目")
                .manageable())
        .bindNavigatorToNavigator("application", "category", "applicationAlias"))
.treeResource("item", "category", "categoryId", resource -> resource
        .availableWhenEquals("categoryKind", "DICTIONARY")
        .title("字典项")
        .createTitle("新建字典项"))
```

`resource` 必须有同名 `editorContribution`，并由静态 action contribution 提供标准树 CRUD；`scopeNavigatorKey` 只能指向同页已声明的导航层级。若资源只适用于范围记录的某个稳定状态，可用 `availableWhenEquals` 声明字段和值；条件不满足时运行器不加载树也不开放新建，资源控制器仍须执行领域不变量。运行器把资源访问固定投影到模块的 `tree-resources/{resource}/{scopeId}` 路径，未选中范围时 fail-closed。页面模块仍拥有动作授权与 runtime descriptor，资源控制器只保留领域范围绑定、归属校验和不变量。该能力当前是静态 action contribution 的平台接入点；动态来源没有同等可执行资源注册时，应明确拒绝，而不是在前端拼业务 URL。

## 关系和子资源

先按关系的真实保存语义选择 DSL，而不是按页面长相选择：

| 关系事实                               | 使用方式                             |
| -------------------------------------- | ------------------------------------ |
| 只读关联明细                           | `detailRelation(...)`                |
| 由目标模块标准 CRUD 维护的关联         | `managedDetailRelation(...)`         |
| 可查询但刻意不开放变更的关联           | `managedReadOnlyDetailRelation(...)` |
| 随父记录 `children` 一起保存的聚合子表 | `aggregateChildRelation(...)`        |
| 子资源独立编辑表单                     | `editorContribution(resource, ...)`  |
| 只需在列表行查看标准关系摘要           | `list(...).expandRelation(...)`      |

关系读取不自动等于可编辑。尤其不要因为能查询到子表就给它开放新增、修改或删除；变更能力必须由 relation 的保存模型、父子约束、动作权限和回收策略共同证明。

## 列表常驻查询与查询摘要

列表查询分为两层：服务端查询描述符声明可用条件及其过滤语义、授权和数据范围；页面 DSL 只声明这些条件如何被持续呈现。页面不得用 DSL 自行定义新的服务端过滤语义。

静态模块在 `PageListDefinition` 中按两个独立区域声明：

```java
list.persistentQueries(queries -> queries.control("onlineOnly", control -> control
        .label("仅在线")
        .uiType(ViewControlType.SWITCH)
        .defaultValue(false)));
list.querySummaries(summaries -> summaries.item("onlineUsers", summary -> summary
        .label("在线")
        .contributor("iam.active-user-count")));
```

`persistentQueries` 是搜索框之后、高级过滤之前的常驻 UI 区域。当前标准控件是布尔 `SWITCH`；其 `externalCriteriaKey` 必须由模块的服务端查询描述符接收。控件改变后，前端立即以 `externalQueryValues` 重查标准 `POST /{moduleAlias}/query`，不新增专用查询接口，也不在浏览器内过滤数据。嵌入页面已拥有同名 `externalQueryValues` 时，嵌入值优先，页面 DSL 不得覆盖上游导航范围。

`querySummaries` 位于列表分页栏左侧。摘要针对本次有效查询命中的完整记录集合计算，忽略分页；关键字、常驻条件、高级条件、查询模板、导航范围或数据权限变化时，摘要必须同步变化。每个摘要 key 在同一列表内唯一，响应只返回稳定的 `{ key, value }`，展示标题仍由页面 descriptor 持有。

摘要有两类来源：

| source          | DSL                             | 适用范围                         |
| --------------- | ------------------------------- | -------------------------------- |
| `MATCHED_COUNT` | `.matchedCount()`               | 直接复用当前查询命中的总数。     |
| `CONTRIBUTOR`   | `.contributor("domain.metric")` | 金额、分组、在线状态等业务指标。 |

业务指标实现 `ListQuerySummaryContributor`，显式声明唯一的 `moduleAlias()` 与 `contributorKey()`，并只通过 `ListQuerySummaryContext.count(...)` 或 `aggregate(...)` 计算。平台在启动时建立 contributor catalog：重复的 `(moduleAlias, contributorKey)` 会阻止装配；静态页面在执行计划编译时、动态页面在发布候选计划阶段都会验证声明的 contributor 已注册。因此错误配置不会等到首次列表查询才暴露。上下文自动叠加当前查询、已提供的页面入口表达、租户及数据范围；菜单入口本身不是授权或数据范围凭据。contributor 不得自行绕过该上下文查询记录，也不应将某个业务模块的指标固化为平台内置 source。

动态页面的已发布 layout 可在 `LIST_DETAIL_CARD` 根节点声明查询摘要：

```json
{
  "template": "LIST_DETAIL_CARD",
  "querySummaries": [
    {
      "key": "onlineUsers",
      "label": "在线",
      "source": "CONTRIBUTOR",
      "contributorKey": "iam.active-user-count"
    }
  ]
}
```

动态 `persistentQueries` 尚未开放：动态查询配置目前只能表达查询模板项，尚未具备“声明一个来源无关、可由标准查询执行器直接消费的外部条件”的服务端事实。发布时出现该字段会被拒绝，不能以 UI JSON 绕过这一缺口。动态 `querySummaries` 仅由 `LIST_DETAIL_CARD` 支持；`MATCHED_COUNT` 不得携带 `contributorKey`，`CONTRIBUTOR` 必须携带已注册的 `contributorKey`；其他模板声明摘要会在发布时被拒绝，不会静默降级。

## 受控前端扩展：给特性业务留路，不改写标准页面

当业务需要的只是标准页面边界上的特性投影或特有动作时，前端在应用侧注册 `ModulePageEnhancement`。它只接受受信任的 Vue 组件和类型化上下文；后端 descriptor 不下发组件名、可执行脚本或任意渲染协议。

以用户管理的“在线状态、会话展开、密码管理、强制下线”为例：

```ts
export const userModulePageEnhancement: ModulePageEnhancement = {
  id: "iam-user-standard-page-enhancement",
  target: { moduleAlias: "iam.user" },
  list: {
    columns: [
      {
        key: "onlineStatus",
        title: "在线状态",
        before: "enabled",
        cell: UserOnlineStatusCell,
      },
    ],
    rowExpansion: {
      key: "iam-user-sessions",
      component: UserSessionListExpansion,
    },
  },
  detail: {
    actions: [
      {
        key: "iam-user-password",
        actionCode: "changePassword",
        title: "密码管理",
        run: openPassword,
      },
    ],
  },
};
```

扩展边界：

- 列、单元格、行展开和操作只补充或替换命名位置，不接管列表查询、选择状态或表格壳层。
- 表单贡献只能挂在标准表单的固定位置；扩展得到的是冻结的草稿与字段快照，通过受控 `setField` 和 `reportValidity` 协作，不能直接持有 HTTP client 或可变表单状态。
- 特有动作必须仍调用模块已声明、已授权的后端 action；前端扩展不创建绕过审计和数据权限的私有写入口。
- 一个扩展只描述一个模块/视图的稳定业务事实。若第二个业务以相同语义复用，应考虑把该边界上升为 descriptor 或平台组件能力。

## 不适用边界与降级方式

以下情况应降低对 DSL 的期待，而不是把 DSL 扩展成万能 UI 描述语言：

| 需求                                           | 正确处理                                                                             |
| ---------------------------------------------- | ------------------------------------------------------------------------------------ |
| 一个模块专有的可视化、算法交互、地图、排班画布 | 业务自有页面或组件；必要时通过菜单接入工作台。                                       |
| 多聚合协同、长事务编排、向导式提交             | 业务应用拥有页面与用例编排，调用标准模块/动作 API。                                  |
| 仅某业务使用的字段联动或复杂布局               | 先在业务侧实现；确认多个模块共享语义后再提出平台能力。                               |
| 需要第三方 UI、脚本或低代码自定义组件          | 不把可执行内容下发到 descriptor；走受控插件/集成边界，待平台具备对应治理能力再接入。 |
| 需要绕过标准保存、权限或数据范围才能实现       | 先修正领域模型、Service 或平台能力，不在 Controller 或前端打补丁。                   |

独立页面也必须继续使用平台的认证、当前用户、模块权限、标准动作和错误治理；“独立”只表示页面组合归业务所有，不表示可以复制一条数据写入和权限链路。

## 评审清单

提交前逐项确认：

1. 是否先从三个页面骨架中选择，而非新增私有布局？
2. 字段、引用、能力和数据约束是否来自模型/Ability，而非重复写进 UI DSL？
3. 是否使用 navigator binding 表达范围，而不是前端手工拼 query 条件？
4. 导航来源是否默认只读；若开启 `manageable`，是否确有完整的来源 CRUD 与编辑 surface？
5. 关系是否按实际读写语义选择 declaration，且没有“可读即可以改”的越权？
6. 特殊展示和业务动作是否落在受控 `ModulePageEnhancement`，并仍经过授权后端 action？
7. 是否没有把组件名、SQL、脚本、物理列、数据权限或领域规则放入 DSL？
8. 若新增了通用能力，是否至少有第二个业务场景、稳定语义和契约测试支撑？

## 相关文档

- [UI 声明与读投影设计](../../../architecture/UI_DECLARATION_AND_READ_PROJECTION.md)：Definition、Descriptor、Plan 的编译边界和字段/关系契约。
- [动静一体核心设计](../../../architecture/DYNAMIC_STATIC_UNIFIED_CORE.md)：静态声明、动态配置和统一运行态的总体原则。
- [页面交付概览](delivery/OVERVIEW.md)：菜单、bootstrap、查询和保存的在线交付链路。
- [前端技术架构](../../../frontend/TECHNICAL_ARCHITECTURE.md)：标准运行器、平台组件与业务项目的前端分层。
