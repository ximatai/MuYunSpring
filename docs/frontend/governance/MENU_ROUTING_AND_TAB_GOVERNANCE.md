# 菜单、路由与页签专项治理

本文记录 MuYun 前端菜单入口、Workbench 页签、Vue Router 和业务页面发布形态之间的专项治理规划。

## 问题定位

平台菜单体系是业务入口，不应退化为平台前端静态 Vue Router 表的展示层。

业务开发常见场景是：业务页面由业务包提供，菜单由平台配置。业务包上线或菜单配置变化时，平台 Workbench 应尽量通过菜单体系识别业务入口并打开页面，而不是要求平台前端每次都重新发包。

因此需要区分两层语义：

| 层次     | 职责                                                                    |
| -------- | ----------------------------------------------------------------------- |
| 菜单入口 | 表达用户要打开哪个业务能力，例如模块、路由、外链或子应用入口。          |
| 页面承载 | 决定用 Vue Router、动态模块运行器、iframe、新窗口或未来微前端承载页面。 |

Vue Router 是页面承载方式之一，不是所有菜单和 tab 的唯一身份来源。

更准确地说，这里的治理目标是解耦四件事：

| 事项     | 含义                                                 |
| -------- | ---------------------------------------------------- |
| 导航意图 | 用户从菜单想进入哪个工作入口。                       |
| 页面承载 | Workbench 用哪种 host 承载这个入口。                 |
| 页面实现 | 页面由平台内置、业务包、远程应用还是动态运行器实现。 |
| 发布单元 | 页面是否随平台一起发布，还是业务包独立发布。         |

菜单体系需要从“静态前端路由目录”升级为“页面入口协议”。

## 核心原则

1. 菜单 target 不等同于 Vue route。
2. Tab identity 应来自业务入口，而不是单纯来自 `route.fullPath`。
3. Workbench 负责菜单、tab 和页面 host 的编排；具体页面由对应 host 承载。
4. 平台内置页面可以走静态 Vue Router。
5. 模块页面应优先走来源无关的 `ModulePageHost` 标准运行器。
6. 独立业务页面应支持不重发平台包的承载方式。
7. 微前端不是第一阶段默认方案，只有当 iframe 或 offline route 无法满足业务体验时再评估。

## 当前阶段收敛口径

本专项当前优先建设长期必要、返工成本高、且不强依赖具体业务页面的底座能力。不要把后续业务接入、微前端、复杂状态管理和后端配置大迁移一次性做完。

当前阶段应优先完成：

1. 稳定 `MenuNavigationTarget`、`PageDescriptor`、`TabPolicy` 和 `PageHost` 的前端入口协议。
2. 保证 active tab 与浏览器 URL 有基础恢复闭环，支持刷新和复制 URL 恢复当前业务入口。
3. 保持 `WorkbenchOutlet`、host registry 和各类 host 的分层边界，避免页面承载逻辑散落在 Workbench 或菜单解析中。
4. 用测试锁住 tab key、URL 序列化、URL 恢复和 host 分发，防止后续业务接入退回到 route path 拼接。
5. 对照后端 `Menu` 模型识别必要字段缺口；只有当现有字段会造成协议歧义或明显迁移成本时，才推动后端模型调整。

当前阶段应暂缓：

1. 完整 tab lifecycle、snapshot、dirty check 和复杂 keep-alive 治理。
2. iframe `postMessage` 全协议和 online 页面内部状态恢复。
3. 微前端 host、子应用生命周期和跨应用依赖治理。
4. business route manifest 的后端同步、版本治理和配置中心闭环。
5. 后端菜单 target envelope 的大规模表结构迁移。
6. 多菜单方案、跨租户、跨环境持久化恢复和配置包迁移恢复。

如果某项能力需要真实业务页面、标准模块运行器或后端配置中心进一步定型，应先记录边界并暂停，不用在当前专项里提前固化完整方案。

### 标准模块页面宿主

`ModulePageHost` 是 module 菜单的唯一页面宿主：它只消费来源无关 descriptor，不根据静态 Java 或动态元数据选择平行页面内核。恢复既有工作台页签时，`dynamic-module-host` 仅作为历史 descriptor 值由页签宿主映射到该正式宿主；业务运行时不保留动态专用页面壳或扩展入口。

## 目标模型

菜单点击后不直接等价于 `router.push()`，而是先生成统一导航目标。

```text
MenuRecord
  -> MenuNavigationTarget
  -> PageDescriptor
  -> MenuTab
  -> PageHost
```

`MenuNavigationTarget` 保持贴近后端菜单模型，负责表达菜单配置中的业务入口。`PageDescriptor` 是 Workbench 消费层的归一结果，负责把入口解释成可打开页面。

建议 `PageDescriptor` 至少表达以下信息：

| 字段        | 含义                                                                                                                |
| ----------- | ------------------------------------------------------------------------------------------------------------------- |
| `pageType`  | 页面是什么，例如 `platform-route`、`business-route`、`dynamic-module`、`remote-url`、`micro-app`、`external-link`。 |
| `openMode`  | Workbench 怎么承载，例如 `workbench-route`、`dynamic-runner`、`iframe`、`micro-app`、`new-window`。                 |
| `target`    | 具体目标，例如 route name/path、moduleAlias、remote url、micro app entry。                                          |
| `params`    | 页面入参，例如 route params/query、entryParams、动态模块配置 id。                                                   |
| `tabPolicy` | tab 打开策略，例如单例、多实例、是否可关闭、刷新策略。                                                              |
| `title`     | 默认页签标题，可被页面 host 或远程页面协议更新。                                                                    |

新菜单配置不应继续用单一 `path` 同时表达内部路由、外部 URL、动态模块和远程应用。可以保留兼容层，但新增能力应优先走结构化 target。

## 后端菜单模型对照

前端 resolver 不应长期替后端模型弥补语义缺口。推进本专项时需要同步观察后端菜单模型是否能稳定表达页面入口：

| 字段或语义                                     | 当前作用                                                               | 风险与观察点                                                                                                         |
| ---------------------------------------------- | ---------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------- |
| `moduleAlias`                                  | 后端菜单绑定模块入口的锚点，也是权限和模块上下文锚点；为空时只是容器。 | 菜单是否可点击由是否绑定模块入口推导；一个绑定了模块的菜单仍可继续拥有子菜单。                                       |
| `MenuNavigationTarget.menuType`                | 前端 resolver 的解析结果，区分 `module`、`route`、`link`。             | 这是前端承载和页签治理语义，不是后端菜单模型字段；不要把它反向作为权限或菜单配置主体。                               |
| `route`                                        | 表达平台 route、offline 业务 path、routeName 或 pageKey。              | 字段容易过载；新增能力应通过结构化 target 或 resolver 规则区分语义。                                                 |
| `openMode`                                     | 表达菜单入口在 Workbench 页签还是外部窗口打开。                        | 使用 `tab` / `window`，不按 URL 形态隐式推断承载方式；`group` 不配置。                                               |
| `externalUrl`                                  | 表达 online 业务页面或外部系统 URL。                                   | URL 只表达目标，不表达打开方式；`link + tab` 由前端编译为 iframe，`link + window` 打开新窗口。                       |
| `pageMode`                                     | 表达动态模块列表、表单、详情等模式。                                   | 应与动态运行器支持的页面模式保持枚举一致。                                                                           |
| `defaultUiConfigId` / `defaultQueryTemplateId` | 表达默认页面配置和查询模板。                                           | 如果页面入口长期由 alias 管理，后续可能需要 alias 或版本语义，避免跨环境 ID 不稳定。                                 |
| `entryParamsJson`                              | 表达入口参数。                                                         | JSON 字符串适合兼容阶段；如果参数需要校验、展示或配置治理，应收敛成结构化参数模型。                                  |
| `id` / `schemeId`                              | 支撑菜单身份和菜单方案。                                               | `menuId` 适合当前菜单树内的 tab identity；跨菜单方案、跨租户或跨环境恢复时，应纳入菜单方案上下文或 descriptor 版本。 |

第一阶段可以继续由前端把现有 `Menu` 字段编译成 `MenuNavigationTarget` 和 `PageDescriptor`。一旦出现以下情况，应优先提醒并评估后端字段或模型调整：

1. 前端需要通过复杂字符串约定判断同一个字段的多种业务语义。
2. 菜单配置需要显式选择页签或外部窗口，但后端没有承载方式字段。
3. 页面入口需要参与配置校验、权限、审计或配置包迁移，但只存在不可校验的 JSON 或 URL。
4. URL 恢复需要跨菜单方案、跨租户或跨环境稳定，但只依赖当前菜单树 `menuId`。

后端菜单 `openMode` 是业务打开方式，只表达 `tab` 或 `window`。前端 resolver 先结合菜单入口快照编译出 `MenuNavigationTarget.menuType`，再生成内部 `PageDescriptor.openMode`：`module + tab` 进入 dynamic runner，`route + tab` 进入 route host，`link + tab` 进入 iframe，`window` 入口点击时打开新窗口。不要根据 URL 是相对路径、同源地址还是绝对地址推断打开方式。

建议长期保留以下 host 概念：

| Host              | 适用场景                                      | 第一阶段策略                              |
| ----------------- | --------------------------------------------- | ----------------------------------------- |
| PlatformRouteHost | 平台内置页面，例如元数据、菜单、设计器。      | 使用 Vue Router。                         |
| ModulePageHost    | 标准模块页面，例如 `moduleAlias + pageMode`。 | 进入来源无关的模块页面运行器。            |
| BusinessRouteHost | offline 业务页面，随平台统一构建发布。        | 先轻量占位，后续再接业务 route manifest。 |
| ExternalPageHost  | online 业务页面或外部系统。                   | 先支持新窗口或 iframe。                   |
| MicroAppHost      | online 子应用，独立发布但需要更强一体化体验。 | 只预留，不作为第一阶段默认方案。          |

## Online 发布场景

Online 场景指平台前端和业务前端独立发布，但部署在同一个 nginx 入口下。

示例：

```text
/platform/     平台 Workbench
/crm/          CRM 业务前端
/wms/          仓储业务前端
```

菜单可以配置业务入口，例如：

```text
/crm/customer/list
/wms/stock/inbound
```

理想目标是业务包单独发布后，平台不重发，也能通过菜单打开新页面。

可选承载方式：

| 方式        | 优点                                         | 风险                                                |
| ----------- | -------------------------------------------- | --------------------------------------------------- |
| 新窗口打开  | 最简单，隔离强，发布独立。                   | 体验割裂，Workbench tab 无法统一管理内容状态。      |
| iframe 内嵌 | 平台不重发，能放入 Workbench tab，成本中等。 | 需要处理通信、title、刷新、样式隔离和页面生命周期。 |
| 微前端      | 体验接近单体应用，可共享平台能力。           | 工程复杂度高，依赖版本、回滚和隔离成本高。          |

第一阶段建议优先支持新窗口或 iframe，不急于引入微前端。

Online 场景可以进一步引入 remote manifest，但它的职责不是把业务 Vue Router 注册进平台，而是让平台和后端知道业务包有哪些可打开入口。

示例：

```text
/crm/route-manifest.json
```

remote manifest 可用于：

1. 校验菜单 target 是否存在。
2. 提供标题、图标和参数 schema。
3. 声明推荐 `openMode`，例如 iframe 或 micro-app。
4. 支持后端菜单配置时选择业务入口。

remote manifest 的治理要求：

1. manifest 应有版本或更新时间，用于菜单入口刷新和配置校验。
2. 后端可同步 manifest 形成可配置入口资产，前端只消费已确认的菜单 target。
3. manifest 只描述可打开入口和推荐承载方式，不承接业务动作治理。

remote manifest 不应承担：

1. 替代业务应用内部路由。
2. 替代微前端生命周期管理。
3. 要求平台构建期感知所有 online 业务页面。

## Offline 发布场景

Offline 场景指业务页面和平台 Workbench 统一构建、统一发布。

这种方式适合早期开发、核心业务线、或与平台组件和运行态强耦合的页面。

建议业务包提供 route manifest，由平台构建时汇总：

```text
业务页面代码
  -> route manifest
  -> 平台构建集成
  -> 菜单配置 path / routeName / pageKey
  -> Workbench 打开 BusinessRouteHost
```

Offline route manifest 可以包含：

```text
pageKey
routeName
path
component
title
paramsSchema
```

菜单可以直接引用 `path`。对业务开发来说，path 可读性强，看到菜单配置通常就能定位页面代码；只要 route manifest 和业务发布约定稳定，path 本身并不必然比 `pageKey` 或 `routeName` 更脆弱。

`pageKey`、`routeName` 和 `path` 都可以作为 offline 业务页面入口标识。治理重点不是否定 path，而是避免一个裸 path 字符串同时表达页面类型、承载方式、tab 策略和发布单元。path 应位于结构化 target 中，由 resolver 判断它指向平台内置 route、offline 业务 route，还是需要按兼容规则处理。

优点：

1. 实现简单。
2. 类型、组件、状态和平台能力共享成本低。
3. 页面体验接近单体应用。

缺点：

1. 平台和业务发布耦合。
2. 新业务上线可能要求平台重新构建。
3. 长期可能导致平台包膨胀。

## Tab 身份

Tab 不应只用 Vue route 作为 key。Tab key 应由 `tabPolicy` 根据 descriptor 和菜单上下文生成，而不是由页面自行拼接。

```text
tabPolicy.identity = by-menu
tabKey = "menu:" + menuId
```

后续如同一菜单支持多实例打开，可再引入业务参数：

```text
tabPolicy.identity = by-params
tabKey = "menu:" + menuId + ":" + stableEntryParams
```

不依赖菜单上下文的入口可以使用 `by-target`：

```text
tabPolicy.identity = by-target
tabKey = pageDescriptor.pageType + ":" + stableTarget
```

Tab 保存的是业务入口目标：

```text
MenuTab
  key
  title
  target
  pageDescriptor
  restoreState
```

Router 只是某些 target 的实现细节。

`menuId` 适合作为当前菜单树运行态入口身份，不等同于跨租户、跨菜单方案或配置包迁移后的长期业务 alias。需要刷新恢复或跨环境持久化时，应由 `tabPolicy` 明确 `by-menu`、`by-target`、`by-params` 等策略，并纳入菜单方案上下文或 descriptor 版本。

后续需要多实例 tab 时，也应由 `tabPolicy` 明确，而不是让各页面自行拼接随机 key。

## 浏览器 URL 与分享恢复

Workbench tab 不能只是内存状态。当前 active tab 应与浏览器 URL 联动，URL 应尽量表达当前页面的业务含义。

理想目标：

1. 点击菜单打开或切换 tab 时，浏览器 URL 跟随 active tab 变化。
2. 浏览器刷新后，Workbench 能从 URL 恢复当前 active tab 和页面 host。
3. 用户复制 URL 给另一个用户，对方在另一个浏览器打开后，应进入同一个业务页面。
4. URL 应可读，能看出页面业务含义，不应只是一段不可理解的随机 tab id。

这不意味着所有页面都必须注册为平台静态 Vue Router route。URL 是 active tab 的可恢复表达，仍应经过 `PageDescriptor` resolver 还原业务入口。

不同 host 的 URL 表达可以不同：

| Host              | URL 表达建议                                                                                    |
| ----------------- | ----------------------------------------------------------------------------------------------- |
| PlatformRouteHost | 使用平台内置 route，例如 `/platform/metadata`。                                                 |
| BusinessRouteHost | 使用 offline 业务 route path，例如 `/crm/customer/list`，或平台 workspace route 携带业务 path。 |
| ModulePageHost    | 使用可读模块入口，例如 `/platform/dynamic/crm.customer/list?uiConfigId=customer-list-v1`。      |
| ExternalPageHost  | 使用平台 workspace route 携带 remote url，或在新窗口直接打开业务 url。                          |
| MicroAppHost      | 使用平台 workspace route 携带 app 和 route，例如 `/platform/app/crm/customer/list`。            |

URL 恢复时应重新走菜单入口解析和页面 host 初始化。URL 可表达入口，不代表绕过其他专项治理。

多 tab 工作区和 URL 的关系建议第一阶段先采用“URL 表达当前 active tab”。其他已打开 tab 可以作为内存状态或后续持久化状态，不要求一次性完整进入 URL。

## Tab 状态保存策略

多 tab 切换时，前一个 tab 的状态不能只有一种保存方式。应区分入口恢复、页面运行态和瞬时状态。

| 状态类型   | 示例                                                                        | 建议策略                                                       |
| ---------- | --------------------------------------------------------------------------- | -------------------------------------------------------------- |
| 入口状态   | 菜单、`PageDescriptor`、route path/query、moduleAlias、pageMode、recordId。 | 进入 URL 或 `MenuTab.restoreState`，用于刷新、分享和重新打开。 |
| 页面运行态 | 表格分页、排序、筛选、滚动位置、选中行、展开节点、表单草稿。                | 由 PageHost 或页面提供 tab 级 snapshot/restore。               |
| 瞬时状态   | loading、临时弹窗、上传中状态、临时连接。                                   | 默认不跨 tab 保存，由页面在失活或关闭时自行处理。              |

第一阶段可以优先支持平台内置页面和动态 host 的 keep-alive，但不能把 keep-alive 作为唯一策略。keep-alive 适合保留 Vue 组件实例，缺点是内存和缓存失控，因此后续需要纳入：

```text
maxAliveTabs
cacheable
dirty
lastActiveAt
closable
```

更稳定的长期方向是 PageHost 生命周期协议：

```text
onTabActivate(snapshot)
onTabDeactivate(): snapshot
onTabClose()
onTabBeforeClose(): allow | block
onTabSnapshot(): snapshot
onTabRestore(snapshot)
```

Workbench 负责保存和分发 tab 生命周期，页面或 host 负责声明哪些状态可保存、如何恢复。

未保存内容必须显式进入 dirty check。切换 tab 可以保留 dirty 状态；关闭 tab 或刷新页面时，如果存在未保存内容，应由 `onTabBeforeClose` 或同等协议阻止静默丢失。

不同 host 的状态策略不同：

| Host              | 状态策略                                                                |
| ----------------- | ----------------------------------------------------------------------- |
| PlatformRouteHost | 优先使用 keep-alive，后续补 snapshot/dirty 生命周期。                   |
| BusinessRouteHost | offline 业务页面可复用平台 PageHost 生命周期。                          |
| ModulePageHost    | 查询条件、分页、表单草稿等应由标准模块运行器统一 snapshot。             |
| ExternalPageHost  | iframe 内部状态 Workbench 无法直接读取，只能通过 postMessage 协议协作。 |
| MicroAppHost      | 由微前端生命周期和子应用协议共同管理。                                  |

iframe 或 online 页面如需参与 tab 状态管理，应通过最小消息协议：

```text
tab:activate
tab:deactivate
tab:close
tab:before-close
tab:snapshot
tab:restore
tab:dirty-change
tab:title-change
```

如果 iframe 页面不接入协议，Workbench 只能保存 iframe URL 和 tab 元信息，不能承诺恢复页面内部状态。

URL 与 tab 状态的边界应保持清楚：URL 保存可分享、可恢复的业务入口和关键参数；复杂 UI 状态和未保存草稿不默认塞进 URL。

## 与 Vue Router 的关系

Vue Router 主要承担以下职责：

1. 平台内置页面导航。
2. Offline 业务页面导航。
3. 浏览器地址栏和刷新恢复。
4. 动态模块运行器的固定入口，例如 `/platform/dynamic/:moduleAlias`。

Vue Router 不承担以下职责：

1. 解释所有菜单类型。
2. 决定 tab 的业务身份。
3. 加载 online 独立业务包。
4. 替代动态模块运行器。

Workbench 在处理菜单点击时，应先根据 `MenuNavigationTarget` 选择 host：

```text
route  -> PlatformRouteHost 或 BusinessRouteHost
module -> ModulePageHost
link   -> ExternalPageHost
APP    -> MicroAppHost
```

当前 `web-contracts` 已有 `route / module / link`。是否增加 `APP` 或更明确的 external app target，应在 online 承载方案定型后再进入契约。

动态模块不应被设计成大量伪静态业务路由。动态模块只需要少量固定 route 进入运行器，页面变化由元数据驱动。

module 的最小 descriptor 应稳定包含：

```text
pageType: dynamic-module
openMode: dynamic-runner
target:
  moduleAlias
  pageMode
  defaultUiConfigId?
  defaultQueryTemplateId?
params:
  recordId?
  query?
  entryParamsJson?
```

第一阶段可以不实现完整动态页面运行器，但不能让 module target 在各处自由拼字段。

## 解析示例

route 指向平台内置页面时：

```text
MenuNavigationTarget
  menuType: route
  openMode: tab
  route: /platform/metadata

PageDescriptor
  pageType: platform-route
  openMode: workbench-route
  target.route: /platform/metadata
  tabPolicy.identity: by-menu
```

route 指向 offline 业务页面时：

```text
MenuNavigationTarget
  menuType: route
  openMode: tab
  route: crm.customer.list

PageDescriptor
  pageType: business-route
  openMode: workbench-route
  target.routeName: crm.customer.list
  target.resolvedBy: offline-route-manifest
  tabPolicy.identity: by-target
```

module 指向动态模块时：

```text
MenuNavigationTarget
  menuType: module
  openMode: tab
  moduleAlias: crm.customer
  pageMode: LIST
  defaultUiConfigId: customer-list-v1

PageDescriptor
  pageType: dynamic-module
  openMode: dynamic-runner
  target.moduleAlias: crm.customer
  target.pageMode: LIST
  target.uiConfigId: customer-list-v1
  tabPolicy.identity: by-menu
```

link 指向 online 业务页面时：

```text
MenuNavigationTarget
  menuType: link
  openMode: tab
  externalUrl: /crm/customer/list

PageDescriptor
  pageType: remote-url
  openMode: iframe
  target.url: /crm/customer/list
  tabPolicy.identity: by-menu
```

这些示例是治理目标，不要求当前代码一次性完成所有字段。

## 战役路线图

本路线图用于跟踪菜单、tab、URL、PageHost 专项研发。每个战役应作为独立编程单元推进，并经过实现、验证、subagent review、修复和提交后再进入下一战役。

### 战役 0：治理文档合入与基线同步

目标：

- [x] 专项治理文档合入 `main`。
- [x] 本地 `main` 对齐远端 squash 后历史。
- [x] 后续研发从干净 `origin/main` 创建分支。

验收：

- [x] 文档在 `main` 可见。
- [x] 本地工作树干净。
- [x] 后续研发分支不基于临时文档分支。

### 战役 1：导航协议与解析核心

目标：

- [x] 定义 `PageDescriptor`、`PageType`、`OpenMode`、`TabPolicy`、`PageHostType`、`TabRestoreState`。
- [x] 建立 `MenuNavigationTarget -> PageDescriptor` 的 resolver。
- [x] 建立 `PageDescriptor -> MenuTab` 的转换规则。
- [x] 建立 `PageDescriptor -> URL` 和 `URL -> PageDescriptor` 的最小规则。
- [x] route、module、link 都能生成明确 descriptor。

验收：

- [x] route 可以解析为平台内置 route descriptor。
- [x] module 可以解析为最小 dynamic descriptor。
- [x] link 可以解析为 external descriptor。
- [x] `path` 是 offline route 的首要可读入口标识。
- [x] `routeName` 或 `pageKey` 至少预留一种 manifest 解析能力。
- [x] URL 生成结果可读，能表达业务含义。
- [x] resolver 有纯函数测试。

### 战役 2：PageHost Outlet 与 Workbench 内容区重构

目标：

- [x] 增加 `WorkbenchOutlet` 或同等内容区承载器。
- [x] 增加 `PlatformRouteHost`。
- [x] 增加 `BusinessRouteHost`。
- [x] 增加 `ModulePageHost` 标准宿主。
- [x] 增加 `ExternalPageHost` 占位。
- [x] `Workbench` 内容区通过 descriptor 选择 host，而不是散落判断菜单类型。

验收：

- [x] 平台 route 菜单能进入 PlatformRouteHost。
- [x] 业务 route 菜单能进入 BusinessRouteHost。
- [x] module 菜单能进入 ModulePageHost 标准宿主。
- [x] link 菜单能进入 ExternalPageHost 占位。
- [x] `App.vue` 不再直接散落 target 类型判断。
- [x] PageHost outlet 有构建验证和 host 分发测试。

### 战役 3：URL 与 Active Tab 联动

目标：

- [x] active tab 变化时更新浏览器 URL。
- [x] 浏览器刷新时从 URL 解析 descriptor。
- [x] URL 对应菜单存在时恢复菜单、title、tab 关系。
- [x] URL 无菜单上下文时允许 direct entry。
- [x] tab 关闭后 URL 切换到相邻 tab 或空工作台。

验收：

- [x] 点击菜单后 URL 跟随变化。
- [x] 复制 URL 到新浏览器可打开同一业务入口。
- [x] 刷新页面后恢复 active tab。
- [x] 无效 workbench-owned URL 不生成坏 tab，回到当前 tab 或空工作台。

### 战役 4：Tab 状态生命周期

目标：

- [ ] 定义 PageHost 生命周期：activate、deactivate、snapshot、restore、beforeClose、close。
- [ ] 平台内置 route 支持基本 keep-alive。
- [ ] ModulePageHost 预留查询、分页、表单草稿 snapshot 结构。
- [ ] dirty tab 关闭有拦截出口。

验收：

- [ ] 切换 tab 后平台内置页面状态保留。
- [ ] 关闭 dirty tab 不静默丢失。
- [ ] snapshot/restore 行为有测试。
- [ ] URL 只承载入口和关键参数，不塞复杂 UI 状态。

### 战役 5：Offline Route Manifest

目标：

- [ ] 定义业务 route manifest 格式。
- [ ] 提供业务 route 注册 API。
- [ ] resolver 能通过 manifest 解析 `path`。
- [ ] `routeName` 或 `pageKey` 至少选择一种作为补充入口标识。
- [ ] 示例业务页面通过 manifest 接入。

验收：

- [ ] mock 菜单配置业务 path 能打开业务页面。
- [ ] mock 菜单配置 `routeName` 或 `pageKey` 能打开业务页面。
- [ ] Workbench 不硬编码业务 route。
- [ ] 刷新后能恢复对应业务页面和 tab。

### 战役 6：Online ExternalPageHost MVP

目标：

- [ ] ExternalPageHost 支持 new-window 模式。
- [ ] ExternalPageHost 支持 iframe 模式。
- [ ] iframe 支持最小消息协议：title-change、dirty-change、before-close、activate、deactivate。
- [ ] 第一版 remote URL 只支持同源相对路径或明确配置入口。
- [ ] 定义 remote manifest 草案和示例 JSON。

验收：

- [ ] 菜单通过 `link.externalUrl=/crm/customer/list` 或结构化 `target.url=/crm/customer/list` 可打开 online 页面。
- [ ] iframe 页面可更新 tab title。
- [ ] iframe 页面不接协议时可以降级显示。
- [ ] 新窗口模式可配置。
- [ ] 示例 remote manifest 可读取，并能辅助 descriptor 生成。
- [ ] 不要求平台注册业务 Vue Router。
- [ ] 不引入微前端运行时。

## 当前不做

第一阶段不做以下事项：

1. 完整微前端框架选型。
2. 所有业务包动态发现。
3. iframe 与 Workbench 的完整通信协议。
4. 复杂 tab 多实例策略。
5. 把所有菜单强行注册成平台 Vue Router route。
6. 用单一 path 字段承载所有页面类型和打开策略。
7. iframe 与微前端页面内部状态的完整恢复协议。

这些能力在 online/offline 场景验证后再逐步上升。
