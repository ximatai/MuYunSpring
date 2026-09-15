# 私有业务引用组件接入示例

本示例通过 `@ximatai/muyun-web-app` 的公开入口组合 `ReferencePicker` 与
`ReferencePickerProvider`，不引用仓库内部路径。`BusinessCustomerView` 展示两个相同目标
`business-contact` 的私有业务用途：客户表单的“客户负责人”，以及客户列表的手动查询条件。

`src/fixtures/businessContactReference.ts` 仅提供确定性的本地候选数据，便于独立消费者构建和手动体验；它不是权限、数据范围或服务端接口的替代品。实际业务应让 provider 调用自己的服务端授权候选接口，并分别实现：

- `searchPage({ keyword, pageNum, pageSize, scope })`：按服务端授权范围分页搜索；不可将当前页或第一页当成全部候选。
- `resolve(ids)`：在历史展示范围内按已保存 ID 解析标题；当前候选分页范围不能用来判断历史值是否存在或可展示。

两个 provider 的 `identity.source` 使用不同的 `businessPurpose` ID。目标相同不代表用途、授权范围或缓存可共享；业务侧应为每个用途声明正确的来源身份。

表单只保存 `accountManagerId`。联系人标题是独立展示投影，由选择与按 ID 回显更新，不持久化到客户记录。列表选择先修改查询草稿，只有点击“查询”才把草稿应用为列表条件；在弹窗中取消不会修改已应用条件。字典值应继续使用其独立的字典契约，不应套用记录引用组件。

先在 `muyun-web` 目录运行 `npm run verify:consumer`，确认当前源码产出的 tarball 可由独立消费者构建。若要运行本示例，在示例目录安装依赖后，再安装仓库 `build/consumer-npm/` 下本次产出的 `ximatai-muyun-web-app-<version>.tgz`；锁文件中的已发布旧包不保证包含新增引用出口。随后运行 `npm run dev`，可在负责人选择器中搜索 `苏南乔`（位于默认第一页之后）或翻页选择，再观察表单保存 ID 与显示标题分离。列表中选择负责人后先取消，可确认已应用条件不变；再次选择并点击“查询”，列表才按该 ID 过滤。
