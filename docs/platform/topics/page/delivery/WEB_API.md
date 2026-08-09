# 页面交付 Web API

本文按当前动态页面、静态模块运行时与页面偏好 controller 能确认的 URL 梳理页面交付接口。

## 页面初始化

| 方法 | URL | 功能 |
| --- | --- | --- |
| `GET` | `/platform.menu/{menuId}/entry` | 按菜单节点读取页面 bootstrap；`clientType` 默认 `WEB`。 |

bootstrap 返回模块入口、客户端类型、主实体别名、权限裁剪后的 resolved 页面配置，以及 `/{moduleAlias}/openapi` 文档入口。它面向静态和动态模块共用的标准运行器；模块运行态 descriptor 仍由 `/{moduleAlias}/context` 提供。resolved 页面字段包含 `fieldForm`，前端可据此识别物理字段和虚拟展示字段。

页面配置本身由配置专题维护：菜单方案、菜单树、字段 UI 类型配置、UI 配置集、UI 配置、UI 字段配置、查询模板和查询项的配置 URL 见 `configuration/WEB_API.md`。页面交付只消费已发布配置和当前用户可见菜单。

## 列表查询与汇总

| 方法 | URL | 功能 |
| --- | --- | --- |
| `POST` | `/{moduleAlias}/query` | 按动态查询请求分页查询主元数据记录；可结合 `uiConfigId` 做列表投影。 |
| `POST` | `/{moduleAlias}/query/summary` | 按同一查询上下文计算汇总项；汇总配置来自已发布 LIST UI 配置。 |

查询请求可使用 `uiConfigId`、`queryTemplateId`、`externalQueryValues`、`queryForm`、`criteria`、兼容 `conditions`、`quickSearch`、分页和排序。`queryForm` 只接受已发布 LIST UI 的可见主表字段；`quickSearch` 只在可见主表非虚拟文本字段内执行；排序字段必须是动态 SQL mapper 可识别的标准字段、能力字段或物理业务字段。`date_range` / `date_time_range` 字段可提交 `[start, end]`，或提交 `{ "start": "...", "end": "...", "timeZone": "Asia/Shanghai" }`。

## 表单保存

| 方法 | URL | 功能 |
| --- | --- | --- |
| `GET` | `/{moduleAlias}/view/{id}` | 查看记录详情。 |
| `POST` | `/{moduleAlias}/insert` | 新增记录；正式请求使用 `$save` envelope，可携带 `uiConfigId` 和文件删除 metadata。 |
| `POST` | `/{moduleAlias}/update/{id}` | 更新记录；正式请求使用 `$save` envelope，可携带 `uiConfigId`、文件删除 metadata 和乐观锁。 |
| `POST` | `/{moduleAlias}/delete/{id}` | 删除记录，动态侧按平台软删语义执行。 |

页面保存仍走动态记录保存链路，不直接写配置表，也不绕过动作权限、数据权限、字段保护和动态事件。

标准保存入口拒绝请求显式写入虚拟字段。虚拟字段可随页面配置展示，但不作为表单输入值保存；读取记录时已经由引用标题、引用投影等平台读链路注入的虚拟值会随列表、详情和 LIST UI 投影输出。页面需要展示当前表单公式派生结果时，使用 `/{moduleAlias}/formula/preview` 获取后端计算值。

## 字段选项

| 方法 | URL | 功能 |
| --- | --- | --- |
| `GET` | `/platform.module/{moduleAlias}/fields/{fieldName}/options` | 按 resolved 模块字段读取选项。 |

该入口供静态和动态模块共用的表单运行器使用：后端从字段 option binding 解析来源，并在当前租户上下文读取。请求需要模块 `MENU` 权限；`enabledOnly` 默认为 `true`，设为 `false` 时会连同停用项返回，以便已有历史值可回显但前端不可重新选择；`parentCode` 可用于读取树形选项的直接子项。响应项包含稳定 `code`、展示 `title`、`enabled`、`sortOrder` 与可选 `parentCode`。前端保存 code，详情优先消费读投影返回的 title 字段。

## 列表排序

| 方法 | URL | 功能 |
| --- | --- | --- |
| `POST` | `/{moduleAlias}/sort/{id}` | 调整当前记录列表排序；TREE 能力启用时同一路径也承载树内移动。 |

该入口来自 `DynamicRecordWebController` 的动态排序实现，只有主元数据具备 SORT 或 TREE 能力时可用。

## 附件关系

| 方法 | URL | 功能 |
| --- | --- | --- |
| `POST` | `/{moduleAlias}/view/{id}/attachments/query` | 查询记录附件关系。 |
| `POST` | `/{moduleAlias}/view/{id}/attachments/add` | 新增业务附件关系。 |
| `POST` | `/{moduleAlias}/view/{id}/attachments/update/{attachmentId}` | 更新附件关系的排序、备注等关系属性。 |
| `POST` | `/{moduleAlias}/view/{id}/attachments/delete/{attachmentId}` | 删除业务附件关系。 |
| `POST` | `/{moduleAlias}/view/{id}/attachments/upload-ticket` | 获取上传 access envelope。 |
| `POST` | `/{moduleAlias}/view/{id}/attachments/{attachmentId}/preview-ticket` | 获取预览 access envelope。 |
| `POST` | `/{moduleAlias}/view/{id}/attachments/{attachmentId}/download-ticket` | 获取下载 access envelope。 |

preview/download 会先校验记录权限和附件归属。文件二进制、文件事实和物理删除策略不属于这些接口。

## 查重与引用

| 方法 | URL | 功能 |
| --- | --- | --- |
| `POST` | `/{moduleAlias}/{actionCode}/duplicate/check` | 按动态 action 槽位执行查重预检，返回匹配记录摘要。 |
| `POST` | `/{moduleAlias}/references/{fieldName}/resolve` | 解析引用候选、标题和投影；可结合来源和目标 UI/查询上下文。 |

查重不替代数据库唯一约束。引用生成和草稿确认归属业务自动化的记录联动专题。

## 导航与偏好

| 方法 | URL | 功能 |
| --- | --- | --- |
| `GET` | `/{moduleAlias}/navigation/{sessionId}/{recordId}` | 按列表导航会话返回上一条、当前和下一条记录。 |
| `GET` | `/platform.page-preference/{moduleAlias}` | 读取当前用户页面偏好；支持 `clientType` 和 `pageKey`。 |
| `POST` | `/platform.page-preference/{moduleAlias}` | 保存当前用户页面偏好；请求需提供非空 `preferenceJson`。 |

页面偏好只影响当前用户体验，不改变平台配置和发布快照。

## 文档入口

| 方法 | URL | 功能 |
| --- | --- | --- |
| `GET` | `/{moduleAlias}/describe` | 返回动态模块 descriptor。 |
| `GET` | `/{moduleAlias}/openapi` | 返回动态模块 OpenAPI 基础文档模型。 |

## 非页面交付归属

| URL | 归属 |
| --- | --- |
| `/{moduleAlias}/references/{fieldName}/generate` | 记录联动。 |
| `/{moduleAlias}/generation/confirm` | 记录联动。 |
| `/{moduleAlias}/code/preview` | 编码规则。 |
| `/{moduleAlias}/exchange/template`、`/{moduleAlias}/import/*`、`/{moduleAlias}/export/*` | 数据交换。 |
