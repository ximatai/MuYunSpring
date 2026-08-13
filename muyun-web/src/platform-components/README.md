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

## 受控模块页面增强

标准模块页是 descriptor 的默认渲染器。业务应用需要个性化时，应在应用启动处通过
`configureModulePageEnhancements(...)` 注册前端源码内的增强，而不是把 Vue 组件名或脚本写入后端 UI DSL。

第一阶段只开放列表公共操作、受控附加列和行操作。业务可以提供 Vue 单元格组件和抽屉内容组件；列表、权限门面、查询、分页、反馈和抽屉外壳仍由平台负责。附加列只能使用已经由后端读投影返回的数据；需要新字段时应先扩展后端读投影或提供受权限保护的业务 API。

具有稳定对象身份的业务面板不应复用临时抽屉。它可以在同一增强内声明 `workspaceViews`，并由动作的 `openWorkspaceTab(view, input)` 打开为按参数去重的 Workbench Tab；平台会为该 Vue 组件提供对应模块的 `ModuleContext`。

## Management State Helpers

`useFlatCrudManagementState` is for single-record management pages where the detail area is the primary workspace. Empty states may stay in `create` mode so the page can guide users to create the first record.

`createRecordEditorSessionState` is for local editor sessions inside composite management pages. Canceling a create session closes the editor by returning to `view`; canceling an edit session restores the selected record draft.

By default, `startCreate()` clears the selected record. Use `preserveSelection` when the selected record is the surrounding context, and use `selectedRecord` plus a custom `draft`/`mode` when creating a child record under a parent.
