# 页面专题 Web API

本文按 URL 维度索引页面专题相关 Web 入口，只列当前代码能确认的功能点，不写完整 OpenAPI。动态 CRUD、引用解析和动作执行由运行态 Controller 承载；页面专题只维护这些 URL 在页面交付和交互中的消费语义。

## 总入口

| 方法 | URL | 功能 |
| --- | --- | --- |
| `GET` | `/platform.menu/{menuId}/entry` | 按菜单节点读取动态页面 bootstrap；支持 `clientType`，默认 `WEB`。 |
| `GET` | `/{moduleAlias}/openapi` | 返回当前动态模块 OpenAPI 基础文档模型，包含页面交付可用入口。 |

## 页面交付

| 方法 | URL | 功能 |
| --- | --- | --- |
| `POST` | `/{moduleAlias}/query` | 动态列表查询。 |
| `POST` | `/{moduleAlias}/query/summary` | 按列表查询上下文计算汇总项。 |
| `GET` | `/{moduleAlias}/view/{id}` | 查看记录详情。 |
| `POST` | `/{moduleAlias}/insert` | 动态页面新增保存；请求体直接为动态业务记录。 |
| `POST` | `/{moduleAlias}/update/{id}` | 动态页面编辑保存；请求体直接为动态业务记录，`version` 参与乐观锁。 |
| `POST` | `/{moduleAlias}/delete/{id}` | 删除记录，动态侧按平台软删语义执行。 |
| `POST` | `/{moduleAlias}/sort/{id}` | 调整列表排序或树内位置；仅记录具备 SORT 或 TREE 能力时可用。 |
| `POST` | `/{moduleAlias}/view/{id}/attachments/query` | 查询记录附件关系。 |
| `POST` | `/{moduleAlias}/view/{id}/attachments/add` | 新增记录附件关系。 |
| `POST` | `/{moduleAlias}/view/{id}/attachments/update/{attachmentId}` | 更新记录附件关系。 |
| `POST` | `/{moduleAlias}/view/{id}/attachments/delete/{attachmentId}` | 删除记录附件关系。 |
| `POST` | `/{moduleAlias}/view/{id}/attachments/upload-ticket` | 获取附件上传 access envelope。 |
| `POST` | `/{moduleAlias}/view/{id}/attachments/{attachmentId}/preview-ticket` | 获取附件预览 access envelope。 |
| `POST` | `/{moduleAlias}/view/{id}/attachments/{attachmentId}/download-ticket` | 获取附件下载 access envelope。 |
| `POST` | `/{moduleAlias}/{actionCode}/duplicate/check` | 执行动态查重预检。 |
| `POST` | `/{moduleAlias}/references/{fieldName}/resolve` | 解析引用候选、标题和投影。 |
| `GET` | `/{moduleAlias}/navigation/{sessionId}/{recordId}` | 按列表导航会话计算上一条、当前和下一条记录。 |
| `GET` | `/platform.user-preference/{preferenceKey}` | 读取当前用户体验偏好；支持 `clientType`。 |
| `POST` | `/platform.user-preference/{preferenceKey}` | 保存当前用户体验偏好。 |
| `DELETE` | `/platform.user-preference/{preferenceKey}` | 删除当前用户体验偏好。 |
| `GET` | `/{moduleAlias}/describe` | 返回动态模块 descriptor，作为页面交付结构入口。 |

详细说明见 [页面交付 Web API](delivery/WEB_API.md)。

## 页面交互

| 方法 | URL | 功能 |
| --- | --- | --- |
| `GET` | `/{moduleAlias}/associations/relation-overview` | 返回关联关系概览。 |
| `GET` | `/{moduleAlias}/associations/design` | 返回关联视图设计描述。 |
| `POST` | `/{moduleAlias}/view/{id}/associations/{viewCode}/query` | 查询指定记录的关联视图数据。 |
| `POST` | `/{moduleAlias}/view/{id}/associations/{viewCode}/diagnose` | 诊断指定记录的关联视图匹配情况。 |
| `GET` | `/{moduleAlias}/actions` | 查询可见动作目录。 |
| `GET` | `/{moduleAlias}/actions/{recordId}` | 查询记录级动作可用性。 |
| `POST` | `/{moduleAlias}/{actionCode}` | 执行列表级动作。 |
| `POST` | `/{moduleAlias}/{actionCode}/{recordId}` | 执行记录级动作。 |
| `POST` | `/{moduleAlias}/{actionCode}/batch` | 执行批量动作。 |
| `POST` | `/{moduleAlias}/formula/preview` | 按页面当前记录 payload 执行公式即时试算。 |
| `GET` | `/{moduleAlias}/tasks/definitions` | 读取模块任务定义。 |
| `POST` | `/{moduleAlias}/view/{id}/tasks/check` | 返回模块任务状态列表。 |
| `POST` | `/{moduleAlias}/view/{id}/tasks/evaluate` | 返回模块任务检查完整结果。 |

详细说明见 [页面交互 Web API](interaction/WEB_API.md)。

## 命名提醒

1. `{moduleAlias}` 是平台模块别名，形如 `application.module`。
2. 动态页面 Web 层以模块主元数据为默认记录对象，不暴露 `/{moduleAlias}/entities/{entityAlias}` 页面路径。
3. 动作执行直接使用 `{actionCode}` 路径，动作编码必须避开平台保留路径。

## 外归属提醒

以下 URL 虽在动态模块路径下出现，但不归页面专题维护：

| URL | 归属 |
| --- | --- |
| `/{moduleAlias}/references/{fieldName}/generate` | 记录联动。 |
| `/{moduleAlias}/generation/confirm` | 记录联动。 |
| `/{moduleAlias}/code/preview` | 编码规则。 |
| `/{moduleAlias}/exchange/template`、`/{moduleAlias}/import/*`、`/{moduleAlias}/export/*` | 数据交换。 |
