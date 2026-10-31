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

`UiDatePicker` 是日期与日期时间的公共入口，`mode` 为 `date` 或 `datetime`，日期时间支持 `minute`、`second` 精度。受控 `value` 和 `update:value` 只传字符串：日期为 `YYYY-MM-DD`，日期时间为本地日历时间 `YYYY-MM-DDTHH:mm:ss`（可接收分钟值），清空输出空字符串。组件不推断时区、不输出第三方日期对象；记录字段与查询条件沿用各自的 UTC 转换边界。无效值显示错误状态，禁用时不可修改；日历内的 Enter 用于确认选择，不触发外层查询提交。现有 `UiInput` 的 `date`、`datetime-local` 分支统一委派给该组件，静态与动态表单、查询和业务配置复用同一面板。

`UiHelp` 统一承载字段或配置的补充说明，以 `label` 命名帮助入口，内容通过默认插槽提供。帮助按钮放在输入框的 `<label>` 之外，保持字段标签关联；点击或键盘展开，Escape 收起并回到入口。阻断操作的错误、冲突、未保存状态及已启用的特殊策略应在主界面或折叠摘要中保留，不用帮助层替代。

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

`UiModal` 默认提供受控确认、取消和关闭行为；公共维护组件有多个明确事务操作时，可用 `operation` 插槽替换默认操作区。操作仍由调用方声明和处理，模态承载、焦点及关闭生命周期由 adapter 保持统一。长内容可声明 `scrollContent`，由 adapter 在视口内滚动正文，保持操作区可达。

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

详情查看态还支持 `detail.controls`，用于按住操作等无法表达为一次点击的业务控件。
业务通过前端增强登记 Vue 组件，不操作平台 DOM，不在后端 DSL 中登记组件。
该入口复用静态、动态标准宿主的操作栏；控件不自动成为列表行菜单动作，也不参与标准表单保存。

```ts
import {
  configureModulePageEnhancementContributions,
  type ModulePageEnhancement,
} from '@ximatai/muyun-web-app';
import HoldControl from './HoldControl.vue';

const enhancement: ModulePageEnhancement = {
  id: 'device-controls',
  target: { moduleAlias: 'business.device' },
  detail: {
    controls: [{
      key: 'hold-control',
      actionCode: 'hold', // 已由服务端声明并授权的模块动作
      title: '按住操作',
      component: HoldControl,
      placement: 'after-primary',
    }],
  },
};
configureModulePageEnhancementContributions('business', [enhancement]);
```

控件必须声明独立业务 `key` 和 `actionCode`，同一详情区域内不得与其他控件或增强动作重名，
也不得覆盖平台保留动作。`state(record, context)` 可补充可见性、禁用状态和原因；最终状态仍经过
平台动作授权、记录级可用性和宿主忙碌状态解析，业务不能据此放宽权限。

`placement: 'after-primary'` 将控件按贡献顺序放在第一个可见主动作之后；无主动作时追加到操作区。
控件不需要提高动作等级来获得这个位置。所有控件保持直接操作，不能转换成“更多”中的点击菜单项；
平台优先收纳其他动作，保留该位置所绑定的主动作，仍放不下时按顺序换行。
组件应保持紧凑并适应容器宽度，平台只挂载一个业务实例，测量实际外框并监听其尺寸变化。

组件通过 `context` prop 接收公开的只读 `ActionControlContext`：动作标题、模块别名、深度冻结的
记录快照、会话标识、最终禁用和加载状态、活动状态、`signal` 与受控 `refresh()`。
`refresh()` 在各类标准详情中统一刷新当前记录、记录动作可用性和关联列表；刷新经过平台读刷新队列，
编辑或忙碌期间延后执行，关闭或切换记录后不再回写旧详情。它只请求重新读取，不发布平台业务提交事件；
引用详情会沿既有记录刷新通知同步来源视图。
业务组件负责将禁用状态落实到实际交互元素，并提供图标的可访问名称和键盘操作；平台不吞掉
松手等结束事件，也不会把控件事件转换成普通点击动作。

记录身份、所属页面作用域、会话或控件实现变化，详情关闭、页签停用、控件隐藏、禁用或卸载，
都会取消当前可用会话的 `signal`。重新可用时提供新信号；旧上下文的 `refresh()` 失效。
关闭过渡与缓存可能保留组件，业务不得仅依赖卸载清理；信号取消也不会自动回滚已发生的服务端操作。
业务应实现幂等清理，处理指针释放、取消、捕获丢失、窗口失焦和页面隐藏，并处理异步初始化晚于
结束的情况。单次按住操作还应有自己的取消信号，避免松手后异步资源继续启动。

完整交互样例见 [`HoldActionControlExample.vue`](../../muyun-web/examples/business-web/src/components/HoldActionControlExample.vue)，
注册样例见 [`holdActionEnhancement.ts`](../../muyun-web/examples/business-web/src/fixtures/holdActionEnhancement.ts)。
样例仅演示交互与清理，资源申请、业务 API、错误反馈与资源释放由业务组件实现。

标准宿主的业务文件入口以次级操作参与同一自适应操作栏，与新建等主操作共享折叠优先级。文件窗口继续拥有自身权限、租户范围和忙碌状态；操作栏只负责入口呈现与调用，不复制文件执行流程。

`RecordDataExchangeSurface` 为静态、动态宿主提供同一文件交互：导出按用途、语言和范围执行单一主操作；导入分上传、核对、结果三个阶段，优先展示错误和待确认对象，保留失败文件直接修正重传。名称解析语言与本次原文填写语言分别声明，多语言选项渐进展开。匹配字段仅按服务端提供的对象唯一约束推荐；没有推荐时由用户选择，不按字段名或排列顺序猜测。关闭后重开保留当前文件和对象选择；切换业务范围重置文件会话。

列表排序入口显示当前声明字段与方向，默认顺序保持中性标签。流程历史使用本地化时间区分归档快照，定义版本独立标注；快照状态明确表示归档时状态，不推断办理轮次或当前是否在办。

工作台业务详情标题与列表共用当前原文对应的业务阅读投影，不把读取后的译文写入业务记录。`WorkflowRecordPanel` 的显式实例入口默认不查询记录提交状态；当前重提待办可声明 `recordSubmission`，只有服务端记录状态仍指向同一实例时才显示提交入口。已办、归档和被新轮次替换的实例不因此获得当前轮次的操作。

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

`TenantScopeExplorer` 是标准管理页与专用工作台共用的租户导航，放在 `ManagementWorkspace` 的左侧 `ManagementExplorerColumn` 中。候选加载区分首次完整结果与搜索结果；仅首次完整结果确认唯一候选时可自动选择，切换仍受宿主的忙碌与草稿保护约束。
