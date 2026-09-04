# MuYun 前端组件契约

本文记录 MuYun 前端组件治理原则。它用于防止业务项目把底层 UI 库 API 当成平台 API，也用于指导哪些组件应沉淀为平台能力。具体组件 props 会随着平台业务接口建设逐步收口。

静态管理页的 explorer、列表和树组件边界见 [静态管理页组件边界](STATIC_MANAGEMENT_PAGE_COMPONENTS.md)。树/平铺统一交互、拖拽、异步加载和结构动效见 [统一树与平铺拖拽组件专项治理](governance/UNIFIED_TREE_COMPONENT_GOVERNANCE.md)。

## 基本原则

1. MuYun 组件表达平台语义，不表达 Ant Design Vue 语义。
2. 静态页面和动态页面运行器优先复用同一批组件。
3. 组件暴露窄口径能力，第三方 UI 事件和值类型在 adapter 内归一。
4. 基础组件可以轻量，平台业务组件必须收口业务语义。
5. 业务项目中的稳定复用能力可以上升到平台包，但项目私有组件不强行平台化。

## 组件分类

### 基础 UI 组件

基础 UI 组件提供统一外观、交互和受控值语义，例如：

```text
UiInput
UiSelect
UiDatePicker
UiModal
UiTable
UiForm
```

这类组件可以比较薄，但不能退化成 Ant Design Vue props 的完整透传。

### 平台语义组件

平台语义组件承接 MuYun 平台能力，例如：

```text
DictionarySelect
ReferenceSelect
ActionBar
AttachmentPanel
UserSelect
OrganizationSelect
```

这类组件通常会组合 UI、web-core client、权限、错误态和平台上下文。它们应优先沉淀在 `platform-components` 或 `dynamic-page-runtime` 中，而不是散落到业务项目。

### 页面级运行组件

页面级运行组件负责把平台契约组合成可用页面能力，例如：

```text
DynamicForm
QueryTable
ChildTable
```

这些组件是动态页面运行器和静态业务页面之间的重要交汇点。它们应优先复用 `web-contracts` 和 `web-core`，并由平台团队维护。

## 业务组件上升标准

业务组件进入平台包前，应满足尽量多的条件：

1. 在多个业务项目或多个模块中重复出现。
2. 依赖平台标准语义，例如模块、动作、引用、字典、附件、权限或审计。
3. 能通过稳定 props、事件和文档交给下游团队使用。
4. 维护责任属于平台团队或有明确共同维护机制。
5. 抽象后能明显减少业务项目代码量和风格分叉。

不满足这些条件的组件，可以留在业务项目中。

## 与底层 UI 库的关系

业务项目不直接使用 Ant Design Vue。需要底层能力时，优先判断：

1. 是否已有 MuYun 组件可以表达。
2. 是否应该增强已有 MuYun 组件。
3. 是否应该新增平台组件。
4. 是否只是项目私有 UI，适合留在业务项目。

只有确认为项目私有且不影响平台风格的场景，才考虑在业务项目内局部处理，并应避免污染平台公共契约。

## 与 web-core 的关系

组件不应各自实现 HTTP、错误提示、消息机制、权限判断和缓存失效。涉及这些能力时，应优先通过 `web-core` 提供的 client、上下文和工具接入。

例如后续动态记录、引用候选、字典解析、动作执行等能力，应从平台 client 进入，而不是在组件里拼裸 URL。

## 当前阶段

当前骨架只提供最小组件集合，目的是固定方向和示例：

```text
UiInput
UiSelect
DictionarySelect
ReferenceSelect
UiForm
UiTable
ActionBar
```

这些组件不是最终形态。真实平台业务进入建设后，应根据后端能力和前端使用反馈继续调整契约。

## 受控模块页面增强

descriptor 驱动页面是默认页面，不是业务个性化的上限。业务应用可以在前端组合根通过
`configureModulePageEnhancements(...)` 按 `moduleAlias + viewCode` 注册模块页面增强；增强保留在
TypeScript/Vue 源码中，不进入后端 DSL，也不允许 DSL 携带前端组件名或脚本。

当前开放的受控区域是：列表公共操作、附加列表列、行操作、详情查看态操作区，以及由这些操作打开的标准抽屉内容。业务只提供动作处理函数、Vue 单元格组件或抽屉内容组件；平台继续拥有列表骨架、详情操作栏、动作授权状态、查询与分页、错误反馈、抽屉承载范围和关闭行为。动作应通过 `actionCode` 绑定模块权限；前端可用性只用于体验控制，抽屉内业务 API 仍必须由后端校验权限。

列表还可注册 `batchActions`。只有存在批量动作时平台才显示选择框；业务 handler 接收当前查询页内的已选记录、`clearSelection()` 与标准模块上下文。当前选择集只覆盖当前查询页；跨页选择、统一确认与任务型批处理待后端批量任务契约明确后再进入。详情只读展示可注册 `detail.sections`：业务提供内容组件，平台负责分区标题、分隔和详情表面，区块不参与 draft 或标准保存。

附加列不能绕过读投影索取字段：它只能消费 descriptor 已允许输出的记录数据。确有领域数据需要时，由后端显式补充读投影，或由权限保护的业务 client 获取，不能通过前端增强改变 SQL 或暴露物理列。

完整 Vue 页面接管是后续的高级逃生舱，不是当前受控增强的替代品。它仍必须复用模块运行时、平台 client 和 Workbench 承载；普通个性化优先停留在受控增强层。

当前由增强动作打开的自定义面板是当前 tab 内的受控抽屉，适用于一次性或短生命周期任务。具有稳定对象身份的业务视图可在同一增强中声明 `workspaceViews`，并由动作通过 `openWorkspaceTab(...)` 打开为按参数去重的 Workbench Tab；该视图仍由平台注入模块上下文，业务只提供普通 Vue 组件和可序列化的 `parse` / `titleOf` 规则。

工作视图的首个公共承载是 Tab。需要 URL 恢复后的抽屉承载、抽屉与 Tab 间状态交接时，不允许通过增强注册表伪装实现；待 Workbench 的业务工作视图 host 完成同一套恢复与交接语义后再开放。

示意：

```ts
const conversationView = {
  type: "crm.customer.conversation",
  moduleAlias: "crm.customer",
  component: CustomerConversationView,
  titleOf: ({ customerId }) => `客户对话 ${customerId}`,
  parse: (query) =>
    typeof query.customerId === "string"
      ? { customerId: query.customerId }
      : undefined,
};

configureModulePageEnhancements([
  {
    id: "customer-conversation",
    target: { moduleAlias: "crm.customer" },
    workspaceViews: [conversationView],
    list: {
      rowActions: [
        {
          key: "conversation",
          actionCode: "crm.customer.conversation",
          title: "对话",
          run: ({ record, openWorkspaceTab }) =>
            openWorkspaceTab(conversationView, {
              customerId: String(record.id),
            }),
        },
      ],
    },
  },
]);
```
