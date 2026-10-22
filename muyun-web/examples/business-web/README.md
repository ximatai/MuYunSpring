# 私有业务引用组件接入示例

本示例通过 `@ximatai/muyun-web-app` 的公开入口组合 `ReferencePicker` 与
`ReferencePickerProvider`，不引用仓库内部路径。`BusinessCustomerView` 展示两个相同目标
`business-contact` 的私有业务用途：客户表单的“客户负责人”，以及客户列表的手动查询条件。

`src/fixtures/businessContactReference.ts` 仅提供确定性的本地候选数据，便于独立消费者构建和手动体验；它不是权限、数据范围或服务端接口的替代品。实际业务应让 provider 调用自己的服务端授权候选接口，并分别实现：

- `searchPage({ keyword, pageNum, pageSize, scope })`：按服务端授权范围分页搜索；不可将当前页或第一页当成全部候选。
- `resolve(ids)`：在历史展示范围内按已保存 ID 解析标题；当前候选分页范围不能用来判断历史值是否存在或可展示。

两个 provider 的 `identity.source` 使用不同的 `businessPurpose` ID。目标相同不代表用途、授权范围或缓存可共享；业务侧应为每个用途声明正确的来源身份。

“签约服务”展示普通树形模块引用的手动接入。`businessServiceReference.ts` 为 provider 增加 `loadTree({ scope })`，返回 `{ record, children }` 层级节点；每个节点都是具有独立 ID 的业务记录。空关键词时弹框展示树，关键词搜索仍使用 `searchPage`，历史选择仍通过 `resolve` 回显。完整树必须来自授权来源，不能将分页结果拼成全量树；复杂范围和懒加载树继续使用专门的来源契约。

可在签约服务弹框中展开“交付服务”，选择“现场安装”和“操作培训”，确认后查看保存 ID；取消不写回，父子记录独立选择，最多选择 3 项。“旧版维护套餐”不可新选。此示例验证公开组件接入，不代表服务端权限或真实业务保存验收。

表单只保存 `accountManagerId`。联系人标题是独立展示投影，由选择与按 ID 回显更新，不持久化到客户记录。列表选择先修改查询草稿，只有点击“查询”才把草稿应用为列表条件；在弹窗中取消不会修改已应用条件。字典值应继续使用其独立的字典契约，不应套用记录引用组件。

先在 `muyun-web` 目录运行 `npm run verify:consumer`，确认当前源码产出的 tarball 可由独立消费者构建。若要运行本示例，在示例目录安装依赖后，再安装仓库 `build/consumer-npm/` 下本次产出的 `ximatai-muyun-web-app-<version>.tgz`；锁文件中的已发布旧包不保证包含新增引用出口。随后运行 `npm run dev`，可在负责人选择器中搜索 `苏南乔`（位于默认第一页之后）或翻页选择，再观察表单保存 ID 与显示标题分离。列表中选择负责人后先取消，可确认已应用条件不变；再次选择并点击“查询”，列表才按该 ID 过滤。

## 工作台顶栏业务入口

`AppWorkbenchShell` 和 `Workbench` 提供 `topbar-actions` 作用域插槽，位于账号入口紧邻左侧，平台助手入口之后。业务 App 在装配工作台时提供组件即可；同一插槽可放多个组件，排列顺序由 App 决定。未提供时不占用额外空间。

```vue
<AppWorkbenchShell
  v-model:startup="startup"
  :location="router.currentRoute.value.fullPath"
  @navigate="({ url, mode }) => router[mode](url)"
>
  <template #topbar-actions="{ currentUser }">
    <BusinessTopbarActions :current-user="currentUser" />
  </template>
  <template #default="{ pageDescriptor }">
    <!-- 原有的平台页面或业务 RouterView -->
  </template>
</AppWorkbenchShell>
```

`src/BusinessTopbarActions.vue` 展示自定义图标按钮外观和下拉菜单，并通过 `useWorkbenchNavigation().openRoute` 打开业务页面。示例中的客户路由需由消费 App 注册。该组件供工作台宿主接入，不挂在当前独立的引用组件演示页上。

平台组合包公开 `UiDropdown`、`UiIcon` 及 `UiDropdownItem`、`UiIconName`，业务可复用平台交互，也可在插槽中使用自己的 Vue 组件、SVG 和样式。插槽提供随宿主会话更新的 `currentUser`（未登录时可能为空）；业务组件自行维护请求、显隐和菜单状态，并负责图标入口的可访问名称、键盘操作及窄屏适配。界面显隐不替代服务端授权。顶栏适合紧凑入口，较大内容应使用下拉浮层、弹窗或业务页面承载。

## 只读业务视图

`ReadonlyBusinessViewExample.vue` 展示只消费公开 `RecordReadView` 和 `ReadonlyRecordWorkspace` 的聚合读取与同源多视图，包含复合行身份、分页、搜索和标准自适应详情。数据及描述符均为确定性本地 fixture，不代表服务端授权验收；正式业务应绑定自己的已授权读取服务，并在身份或范围改变时更新 `scopeKey`。契约及菜单声明见[只读业务投影与同源多入口](../../../docs/frontend/READONLY_BUSINESS_VIEWS.md)。

跨目录引用样板复用公开 `ReferencePickerProvider`，演示分页、未搜索历史 ID 回显、失效项和授权范围切换；本地数据仅验证交互契约，正式业务须在服务端校验来源与目标授权。
