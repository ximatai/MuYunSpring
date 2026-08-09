# 前端技术架构

本文记录 MuYunSpring 前端的阶段性架构方向。它强调设计哲学、团队协作方式和可演进边界，不把尚未进入建设阶段的平台业务细节提前定死。

前端稳定设计共识见 [MuYun 前端设计原则](DESIGN_PRINCIPLES.md)。后续推进步骤和路线目标见 [MuYun 前端路线草案](DEVELOPMENT_APPROACH.md)。
静态管理页组件拆分见 [静态管理页组件边界](STATIC_MANAGEMENT_PAGE_COMPONENTS.md)。
菜单、路由和页签治理见 [菜单、路由与页签专项治理](governance/MENU_ROUTING_AND_TAB_GOVERNANCE.md)。错误抛出和前端展示治理见 [错误抛出与前端展示专项治理](governance/ERROR_HANDLING_GOVERNANCE.md)。动作结果、数据变更和本地 reaction 治理见 [动作结果与数据变更治理](governance/ACTION_RESULT_REACTIONS_GOVERNANCE.md)。

## 技术路线

首期采用：

```text
Vue 3 + TypeScript + Vite
Ant Design Vue 作为首个 UI adapter
Vue Router
Pinia
TanStack Query for Vue
```

选择 Vue 的原因是中后台页面、复杂表单、业务组件组合和 descriptor 驱动页面的代码更贴近业务直觉；Ant Design Vue 只作为首个 adapter，不作为业务项目的直接开发语义。

## 分层骨架

当前正式前端骨架位于：

```text
muyun-web/
```

现阶段先使用单项目内分层，未来根据发布和协作需要拆为私有 npm 包：

```text
muyun-web/src/web-contracts       前端公共契约和平台语义类型
muyun-web/src/web-core            无 UI 平台能力，如 HTTP、错误、消息、上下文、Query 治理
muyun-web/src/vue-ui-antdv        Vue + Ant Design Vue UI adapter
muyun-web/src/dynamic-page-runtime        动态页面运行器
muyun-web/src/platform-components    跨业务可复用的平台业务组件
muyun-web/src/platform-workbench      平台工作台
muyun-web/src/platform-admin-runtime 平台管理页、路由注册和工作区运行时
muyun-web/src/views                 平台自有管理页实现
muyun-web/src/app                   框架仓库自身的启动、登录和开发宿主装配
```

未来包名按前端职责命名，避免复用后端 Gradle 子项目名：

```text
@muyun/web-contracts
@muyun/web-core
@muyun/vue-ui-antdv
@muyun/dynamic-page-runtime
@muyun/platform-components
@muyun/platform-workbench
@muyun/platform-admin-runtime
```

首个对外运行时不会仓促把上述内部目录逐个冻结为 npm 包。当前以 `@ximatai/muyun-web-app` 交付面向管理型 App 的组合包：公开 Workbench、认证/HTTP/menu 基础原语、平台管理运行时和样式入口。标准模块运行器与平台业务组件作为包内实现，经 `PlatformAdminOutlet` 交付平台管理页，而非由业务 App 直接拼装。平台自有管理页随包交付；消费应用自己的业务页面、登录品牌和路由组合不进入包。

组合包同时公开 App 自定义轻量页面必需的 `UiButton`、`UiInput` 和 `UiSwitch`。它们是受控的基础消费面，业务 App 应优先使用这些组件而不是原生控件或直接依赖 Ant Design Vue；其余 adapter 组件仍属于内部实现，待独立版本策略稳定后再按职责拆包，避免把当前源码目录直接变成长期发布承诺。

## 能力分层

### web-contracts

承载前端公共类型和平台语义词汇。这里可以表达 `moduleAlias`、`metadataAlias`、`actionCode`、字段、动作、动态页面描述、保存 envelope 等概念，但具体字段会随着后端平台接口进入建设后再收口。

### web-core

承载无 UI 的平台能力，例如：

```text
HTTP client
请求上下文
异常归一
消息机制
当前用户和租户上下文
权限判断
Query client 默认配置
traceId 和前端异常追踪
```

这层应独立于 Vue UI adapter，避免业务项目重复写 axios 封装、错误提示、token 处理和缓存失效。

### vue-ui-antdv

承载 MuYun UI 组件到 Ant Design Vue 的适配。业务项目使用 `UiInput`、`UiSelect`、`UiForm`、`UiTable` 等平台组件，不直接使用 Ant Design Vue 组件。组件由 `@muyun/vue-ui-antdv` 包名表达 MuYun 归属，导出名表达组件职责。

基础组件可以保持轻量，但不能把 Ant Design Vue 的完整 props 原样暴露成 MuYun 公共 API，也不能通过 Vue attribute fallthrough 隐式透传未知属性。`class`、`style` 是允许的外观挂点；跨业务确有需要的浏览器标准属性和可访问性属性必须显式进入组件契约。

adapter 内部优先使用 Ant Design Vue 的成熟承载能力，而不是重新实现浮层定位、菜单键盘导航、模态焦点管理、遮罩和关闭生命周期。当前 `UiDropdown` 封装 `Dropdown + Menu`，`UiModal` 封装受控 `Modal`，`UiSidePanel` 封装上下文 `Drawer`；业务或平台组件只消费它们的 MuYun 语义，不直接依赖 Ant Design Vue。

### dynamic-page-runtime

承载 descriptor 驱动的标准模块运行器。它负责把后端 descriptor、动作、字段、列表、表单和页面上下文转换成 MuYun 组件组合；静态模块声明和动态元数据模块共用此运行器及“列表 + 抽屉”的标准交互。`dynamic-module-host` 是既有菜单和页签 descriptor 的兼容标识，不代表存在动态专用的页面内核。

dynamic-page-runtime 不直接依赖 Ant Design Vue；它依赖 MuYun 契约、web-core 能力和 MuYun 组件。

### platform-components

承载跨业务复用的平台业务组件。这里不是所有业务组件的收容所，只接纳语义稳定、跨项目复用、依赖平台标准能力的组件，例如引用选择、附件面板、导入导出、审批动作区、查询表格和子表。

项目私有组件仍留在业务项目内。

### platform-workbench

承载平台工作台，包括布局、菜单、登录态入口、租户切换入口、全局错误出口和路由框架。它面向平台应用和业务应用提供统一承载形态。

登录页本身不是唯一的 Workbench 视觉实现。平台应提供认证会话、认证失效、强制改密与错误归一等可复用内核，并可提供默认登录页；业务 App 可以复用默认页，也可以按自身品牌实现登录 UI。自定义页面仍必须消费平台认证 client，不复制登录协议、token/session 存储或认证错误策略。

### platform-admin-runtime

承载平台自有的应用、模块、元数据、IAM 等管理页，以及它们的静态路由注册、工作区视图和上下文契约。它是管理型 App 的默认平台交付，不是业务 App 页面收容层。业务 App 通过显式路由挂接自身页面；平台管理菜单通过后端声明的 `route + moduleAlias` 命中本运行时，仍复用模块权限、上下文和标准模块运行器。

## 交付准备度

当前前端平台交付面已经具备管理型 App 的基础承载能力；动态 LIST 入口已有真实 CRUD 闭环，但尚未覆盖完整页面交付：

| 方向                 | 当前状态                                                                                                                                                                            | 后续触发                                                                                                |
| -------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------- |
| Workbench            | 登录、token 保存、session/menu 启动、菜单页签、URL 恢复、mock client、后端模式构建已有测试或构建覆盖。                                                                              | 进入全局通知、租户切换、用户偏好或生产级登录安全时，再补真实 UI 出口和会话治理。                        |
| 页面交付后端契约     | `page/delivery` 后端接口已形成阶段契约，覆盖 bootstrap、查询、保存、附件、查重、引用、导航和偏好；接口细节以 [页面交付 Web API](../platform/topics/page/delivery/WEB_API.md) 为准。 | 前端动态页面运行器开始接真实后端时，再把这些接口固化到 `web-contracts` 和 `web-core` client。           |
| UI adapter           | `vue-ui-antdv` 已提供表单、输入、选择、菜单、页签、表格、空态、错误态等基础组件，并通过边界检查限制 Ant Design Vue 直接外泄。                                                       | 出现真实页面字段、表格、动作区或布局需求时，按 MuYun 组件语义扩展，不透传 Ant Design Vue 完整 API。     |
| dynamic-page-runtime | 标准模块 LIST 入口已接运行时上下文，支持真实查询、新增、编辑、删除和表单字段解析；mock descriptor 仍保留为组件级演示。                                                              | 继续接 bootstrap 的完整视图语义、FORM/DETAIL 入口、局部错误、查重和附件，不把阶段能力表述为完整设计器。 |
| platform-components  | 当前只保留跨业务组件边界说明，没有沉淀真实业务组件。                                                                                                                                | 引用选择、附件面板、导入导出、审批动作区等至少出现稳定跨项目复用语义后再进入。                          |
| 业务示例             | `examples/business-web` 验证业务项目可通过本地 alias 消费平台包，且不直接依赖 Ant Design Vue。                                                                                      | 平台包正式拆分或业务团队开始独立仓库接入时，补私有 npm 发布和迁移说明。                                 |

本阶段不建设动态表单字段编辑器、保存校验 UI、查重弹窗、导入面板或工作流动作区。条件不具备的能力按触发点回收，不提前做空抽象。

## 前端技术债

| 问题                                  | 当前口径                                                                                                                                                                                          | 回收条件                                                                                                                                                                                                   |
| ------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `RecordPicker` 的候选加载仍是阶段实现 | 当前组件用于支撑静态组织父级选择和轻量记录选择；tree 模式优先使用树能力，无树能力时降级 CRUD 列表。但它还不是最终引用候选组件，不承诺完整分页、远程搜索、标题解析、投影回填或页面上下文查询语义。 | 进入动态页面引用字段、跨模块静态引用选择或通用候选选择器建设时，从 `web-core` 沉淀引用候选 client，并复用页面交付的 `/{moduleAlias}/references/{fieldName}/resolve` 语义，避免在组件内固化分页和查询协议。 |

## 业务项目接入示例

当前仓库内提供业务项目接入示例：

```text
muyun-web/examples/business-web
```

它模拟业务团队在独立仓库中消费平台成果。示例和真实管理型 App 当前都只依赖公开组合包 `@ximatai/muyun-web-app`；消费者验证会从刚构建的 tarball 安装该包，不能依赖仓库源码 alias 或 registry 中的同版本缓存。

未来若按职责拆出内部 npm 包，以下目录和包名只是平台内部候选边界，不构成当前业务接入路径。业务项目不要直接依赖它们：

```text
@muyun/web-contracts
@muyun/web-core
@muyun/vue-ui-antdv
@muyun/dynamic-page-runtime
@muyun/platform-components
@muyun/platform-workbench
```

## 协作模式

平台团队负责：

```text
平台契约
无 UI 基础能力
UI adapter
动态页面运行器
跨业务平台组件
平台 workbench
文档、示例、playground 和迁移说明
```

业务团队负责：

```text
业务页面
业务专属组件
业务路由组合
业务项目配置
对平台缺口的反馈和上升申请
```

上升为平台能力的判断标准：

1. 是否跨多个业务项目复用。
2. 是否依赖平台标准能力。
3. 是否能降低业务项目接入成本。
4. 是否有稳定语义和维护责任人。
5. 是否适合通过文档和类型声明给下游使用。

## 前后端语义

前端不复制后端模块边界，但共享平台业务语义。涉及双方共有的概念时，应优先沿用后端已经稳定的命名，例如：

```text
applicationAlias
moduleAlias
metadataAlias
relationAlias
actionCode
fieldName
record
values
children
attachments
originContext
uiConfigId
```

这些语义会在具体平台业务接口建设时进一步固化到 `web-contracts` 和 `web-core` 中。

## 边界约束

当前骨架保留一个最小工程约束：除 `src/vue-ui-antdv` 外，不允许源码直接 import `ant-design-vue`。

```bash
cd muyun-web
npm run check:boundaries
```

后续正式拆包时，该约束应升级为 ESLint/CI 规则。

当前同时提供轻量 ESLint 与 Prettier 配置，用于保障基础代码质量和格式一致。规则保持克制，后续只在确有协作收益时逐步收紧。

`check:boundaries` 除限制 Ant Design Vue 的 import/template 外，也要求所有 `Ui*` adapter 组件关闭 Vue attribute fallthrough。新增基础组件时，必须先声明可支持的 MuYun props、事件与 slots，避免未知 UI 库属性成为事实契约。

## 当前非目标

首期不建设：

1. 完整低代码设计器。
2. 多 UI 库并行 adapter。
3. 可视化流程设计器。
4. 完整插件市场。
5. 完整动态页面交付闭环（FORM/DETAIL、查重、附件、导入和工作流动作）。

当前重点是固定技术路线、分层骨架、团队协作模型、平台 workbench 与管理型 App 的交付能力。后端页面交付接口已有阶段契约；前端已接入标准 LIST CRUD，剩余页面语义按真实业务触发逐步闭合。

## 验证命令

前端统一使用 Vitest。纯 TypeScript 的状态模型、契约、client 和数据转换测试位于 `tests/` 的镜像目录中，使用 `*.test.ts` 并运行在 Node 环境；Vue SFC、DOM 和交互测试使用 `*.component.test.ts`，运行在 jsdom 与 Vue Test Utils 环境。两类测试必须通过独立命令保持边界清晰。

```bash
cd muyun-web
npm install
npm run test:unit
npm run test:component
npm test
npm run lint
npm run format:check
npm run build
npm run check:boundaries
npm run verify:consumer
```

后端仍使用仓库默认验证：

```bash
./gradlew verifyAll
```
