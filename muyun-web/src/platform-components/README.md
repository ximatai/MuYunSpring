# platform-components

`platform-components` is reserved for reusable platform-level business components.

Typical examples:

- reference selectors
- dictionary-aware controls
- attachment panels
- import/export action groups
- workflow action panels
- query tables and child tables

## File transfers

`FileTransferUploader` is the common browser upload component. It deliberately
does not know an application record. A caller supplies either the FileServer
ticket transport or a storage-specific upload callback; the component owns
selection, multipart upload, progress, cancellation, retry and standard
feedback.

`SingleImageFileReferenceField` is the one-image field presentation for a
declared `@FileReference`: it composes that uploader with preview, download,
replacement and removal. It always persists the same `fileId`; preview and
download first request a policy-authorized target, so the component remains
neutral between MuYunFileServer and database-inline assets.

This layer may compose `vue-ui-antdv`, `web-core`, `dynamic-page-runtime`, and `web-contracts`, but should only accept business semantics that are stable across multiple business projects.

## 管理标题栏视觉契约

`ManagementPanelHeader` 的 `status` 插槽承载与标题并列的标准记录状态。它在自身作用域声明
`--muyun-record-status-switch-offset-y`；`RecordStatusSwitch` 消费该变量并默认回退为 `0`。
因此，启停开关在标准管理标题栏中遵循与标题文字一致的视觉基线，在其他位置使用时不携带该布局偏移。
业务页面和 UI DSL 不应自行设置这个变量或以 margin、transform 修正开关位置。

## 动态表单纵向节奏

`RecordFormFields` 使用 `--muyun-record-form-label-gap` 控制标签与控件的局部间距，默认值为 `6px`。
动态模块运行器以真实网格容器承载字段，并声明舒适编辑密度：标签间距 `8px`、字段行距 `16px`、列距 `12px`。
字段语义和布局密度分别由 descriptor 与运行器负责，UI DSL 不声明像素值；静态页面可在评估后显式采用相同的容器契约。

## 受控模块页面增强

标准模块页是 descriptor 的默认渲染器。业务应用需要个性化时，应在应用启动处通过
`configureModulePageEnhancements(...)` 注册前端源码内的增强，而不是把 Vue 组件名或脚本写入后端 UI DSL。

第一阶段只开放列表公共操作、受控附加列和行操作。业务可以提供 Vue 单元格组件和抽屉内容组件；列表、权限门面、查询、分页、反馈和抽屉外壳仍由平台负责。附加列只能使用已经由后端读投影返回的数据；需要新字段时应先扩展后端读投影或提供受权限保护的业务 API。

具有稳定对象身份的业务面板不应复用临时抽屉。它可以在同一增强内声明 `workspaceViews`，并由动作的 `openWorkspaceTab(view, input)` 打开为按参数去重的 Workbench Tab；平台会为该 Vue 组件提供对应模块的 `ModuleContext`。

## Management State Helpers

`useFlatCrudManagementState` is for single-record management pages where the detail area is the primary workspace. Empty states may stay in `create` mode so the page can guide users to create the first record.

`createRecordEditorSessionState` is for local editor sessions inside composite management pages. Canceling a create session closes the editor by returning to `view`; canceling an edit session restores the selected record draft.

By default, `startCreate()` clears the selected record. Use `preserveSelection` when the selected record is the surrounding context, and use `selectedRecord` plus a custom `draft`/`mode` when creating a child record under a parent.
