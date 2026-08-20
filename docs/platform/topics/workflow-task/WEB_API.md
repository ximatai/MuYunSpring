# 工作流与任务 Web API

本文按当前 Controller 中已存在的 URL 梳理工作流与任务相关 Web 入口，只列功能点，不写完整 OpenAPI。

工作流相关 Web 入口主要来自：

| Controller                          | 根路径                                                                        |
| ----------------------------------- | ----------------------------------------------------------------------------- |
| `WorkflowDefinitionWebController`   | `/platform.module/{moduleAlias}/workflow-definitions`                         |
| `WorkflowVersionWebController`      | `/platform.module/{moduleAlias}/workflow-definitions/{definitionId}/versions` |
| `WorkflowRuntimeWebController`      | `/workflow/runtime`                                                           |
| `WorkflowRuntimeAdminWebController` | `/workflow/runtime/admin`                                                     |
| `WorkflowHistoryWebController`      | `/workflow/history`                                                           |
| `WorkflowDelegationWebController`   | `/workflow/delegation`、`/platform.workflow_delegation`                       |

## 工作流定义配置

根路径：`/platform.module/{moduleAlias}/workflow-definitions`

| 方法   | URL                                                                                               | 功能                                                                                       |
| ------ | ------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------ |
| `POST` | `/platform.module/{moduleAlias}/workflow-definitions/query`                                       | 按模块查询工作流定义。                                                                     |
| `GET`  | `/platform.module/{moduleAlias}/workflow-definitions/view/{id}`                                   | 查看某个工作流定义。                                                                       |
| `POST` | `/platform.module/{moduleAlias}/workflow-definitions/insert`                                      | 新增工作流定义；路径模块必须是已存在平台模块，并写入 `moduleAlias` 与 `applicationAlias`。 |
| `POST` | `/platform.module/{moduleAlias}/workflow-definitions/update/{id}`                                 | 更新草稿工作流定义；已发布、停用和归档定义不能通过普通更新改写。                           |
| `POST` | `/platform.module/{moduleAlias}/workflow-definitions/delete/{id}`                                 | 删除草稿工作流定义。                                                                       |
| `POST` | `/platform.module/{moduleAlias}/workflow-definitions/sort/{id}`                                   | 在同一模块内调整定义顺序。                                                                 |
| `POST` | `/platform.module/{moduleAlias}/workflow-definitions/{definitionId}/versions/{versionId}/publish` | 发布指定版本，并同步定义当前版本和模块动作贡献。                                           |
| `POST` | `/platform.module/{moduleAlias}/workflow-definitions/{definitionId}/disable`                      | 停用工作流定义，并禁用已贡献动作。                                                         |
| `POST` | `/platform.module/{moduleAlias}/workflow-definitions/{definitionId}/archive`                      | 归档工作流定义，并禁用已贡献动作。                                                         |

普通定义维护只面向草稿；发布、停用和归档必须走发布门面，避免绕过动作贡献和状态同步。

删除、停用和归档请求体均为 `{"version": n}`。发布同时锁定定义和版本，请求体为 `{"definitionVersion": n, "version": m}`；任一快照失效均返回 `409 CONFLICT_VERSION`。

## 工作流版本配置

根路径：`/platform.module/{moduleAlias}/workflow-definitions/{definitionId}/versions`

| 方法   | URL            | 功能                                                                |
| ------ | -------------- | ------------------------------------------------------------------- |
| `POST` | `/query`       | 查询某个定义下的版本列表。                                          |
| `GET`  | `/view/{id}`   | 查看版本，包含设计器语义图、布局图和发布快照字段。                  |
| `POST` | `/insert`      | 新增草稿版本；路径定义会写入 `definitionId`，请求体不能跨定义写入。 |
| `POST` | `/update/{id}` | 更新草稿版本。已发布版本不可改写。                                  |
| `POST` | `/delete/{id}` | 删除草稿版本。                                                      |

版本配置用于承载设计器语义图和布局图 JSON。版本发布仍通过定义配置下的 `publish` 入口完成。

版本删除同样提交 `{"version": n}`，以避免删除已被其他编辑者更新的草稿版本。

## 运行实例读取

根路径：`/workflow/runtime`

| 方法   | URL                                                                         | 功能                                                 |
| ------ | --------------------------------------------------------------------------- | ---------------------------------------------------- |
| `GET`  | `/workflow/runtime/instance/{instanceId}/bundle`                            | 读取当前运行实例渲染包。                             |
| `GET`  | `/workflow/runtime/instance/{instanceId}/manual-branches`                   | 读取当前实例可选手工分支候选。                       |
| `GET`  | `/workflow/runtime/instance/{instanceId}/manual-branch-candidate-prechecks` | 读取手工分支候选预检结果；当前按当前用户作为操作者。 |
| `GET`  | `/workflow/runtime/instance/{instanceId}/tasks`                             | 读取当前实例任务列表。                               |
| `GET`  | `/workflow/runtime/instance/{instanceId}/events`                            | 读取当前实例事件列表。                               |
| `GET`  | `/workflow/runtime/instance/{instanceId}/add-sign-explanations`             | 读取当前实例加签解释视图。                           |
| `POST` | `/workflow/runtime/instance/{instanceId}/actions`                           | 读取当前实例可执行动作。                             |

## 提交审批

根路径：`/workflow/runtime`

| 方法   | URL                                                                        | 功能                                                       |
| ------ | -------------------------------------------------------------------------- | ---------------------------------------------------------- |
| `POST` | `/workflow/runtime/record/{moduleAlias}/{recordId}/submit/status`          | 查询业务记录提交审批状态。                                 |
| `POST` | `/workflow/runtime/record/{moduleAlias}/{recordId}/submit/preview`         | 预览业务记录提交后会命中的工作流定义、版本和分支选择信息。 |
| `POST` | `/workflow/runtime/record/{moduleAlias}/{recordId}/actions/submitApproval` | 提交业务记录进入审批；该入口是记录级自定义动作。           |

提交请求可携带 `authOrgId`、`selectedRouteKey`、`selectedDirectLinkKey`、`selectedReason` 和 `manualRouteSelections`，用于组织上下文和手工分支选择。

## 实例动作

根路径：`/workflow/runtime`

| 方法   | URL                                                            | 功能                                         |
| ------ | -------------------------------------------------------------- | -------------------------------------------- |
| `POST` | `/workflow/runtime/instance/{instanceId}/actions/{actionCode}` | 执行实例级动作，如撤回、终止等运行实例动作。 |

实例动作请求当前使用当前用户作为操作者，可携带 `reason`。

## 任务动作

根路径：`/workflow/runtime`

| 方法   | URL                                                              | 功能                                               |
| ------ | ---------------------------------------------------------------- | -------------------------------------------------- |
| `POST` | `/workflow/runtime/task/{taskId}/actions/{actionCode}`           | 执行任务动作，如同意、驳回、转办、加签、回退等。   |
| `POST` | `/workflow/runtime/task/{taskId}/read`                           | 将知会任务标记为已读。                             |
| `GET`  | `/workflow/runtime/task/{taskId}/module-task/prepare`            | 准备业务任务办理 bundle。                          |
| `POST` | `/workflow/runtime/task/{taskId}/module-task/check-and-continue` | 校验业务任务完成状态，并在满足条件后继续推进流程。 |

任务动作请求可携带目标处理人、驳回重提模式、原因、手工分支选择、加签段以及加签后的实例级语义图和布局图 JSON。

## 历史查询

普通历史入口根路径：`/workflow/history`

| 方法   | URL                                                 | 功能                               |
| ------ | --------------------------------------------------- | ---------------------------------- |
| `POST` | `/workflow/history/query`                           | 按模块、记录、发起人查询历史实例。 |
| `GET`  | `/workflow/history/{historyInstanceId}/bundle`      | 读取历史实例渲染包。               |
| `GET`  | `/workflow/history/{historyInstanceId}/tasks`       | 读取历史实例任务列表。             |
| `GET`  | `/workflow/history/{historyInstanceId}/tasks/view`  | 读取历史任务解释视图。             |
| `GET`  | `/workflow/history/{historyInstanceId}/events`      | 读取历史实例事件列表。             |
| `GET`  | `/workflow/history/{historyInstanceId}/events/view` | 读取历史事件解释视图。             |

## 管理端当前实例

管理端根路径：`/workflow/runtime/admin`

| 方法   | URL                                                                    | 功能                                                                           |
| ------ | ---------------------------------------------------------------------- | ------------------------------------------------------------------------------ |
| `POST` | `/workflow/runtime/admin/instance/query`                               | 查询当前实例；支持模块、记录、发起人、实例状态、审批态、当前处理人和超期状态。 |
| `POST` | `/workflow/runtime/admin/instance/{instanceId}/bundle`                 | 读取当前实例管理端渲染包。                                                     |
| `POST` | `/workflow/runtime/admin/instance/{instanceId}/render`                 | 读取当前实例管理端渲染包；与 `bundle` 等价入口。                               |
| `POST` | `/workflow/runtime/admin/instance/{instanceId}/events`                 | 读取当前实例事件。                                                             |
| `POST` | `/workflow/runtime/admin/instance/{instanceId}/tasks`                  | 读取当前实例任务。                                                             |
| `GET`  | `/workflow/runtime/admin/instance/{instanceId}/todo-tasks`             | 读取当前实例待办任务。                                                         |
| `GET`  | `/workflow/runtime/admin/instance/{instanceId}/active-tasks`           | 读取可强行处理的活动任务视图。                                                 |
| `POST` | `/workflow/runtime/admin/instance/{instanceId}/actions/forceTerminate` | 管理端强制终止实例。                                                           |
| `POST` | `/workflow/runtime/admin/instance/{instanceId}/actions/reset`          | 管理端重置实例。                                                               |
| `POST` | `/workflow/runtime/admin/task/{taskId}/actions/forceApprove`           | 管理端强行代办或强制同意任务。                                                 |

管理端动作归属 `platform.workflow_admin` 管理模块动作，不复用普通业务记录权限。

## 管理端历史

管理端根路径：`/workflow/runtime/admin`

| 方法   | URL                                                               | 功能                                             |
| ------ | ----------------------------------------------------------------- | ------------------------------------------------ |
| `POST` | `/workflow/runtime/admin/history/query`                           | 管理端查询历史实例。                             |
| `POST` | `/workflow/runtime/admin/history/{historyInstanceId}/bundle`      | 读取历史实例管理端渲染包。                       |
| `POST` | `/workflow/runtime/admin/history/{historyInstanceId}/render`      | 读取历史实例管理端渲染包；与 `bundle` 等价入口。 |
| `POST` | `/workflow/runtime/admin/history/{historyInstanceId}/events`      | 读取历史事件。                                   |
| `POST` | `/workflow/runtime/admin/history/{historyInstanceId}/events/view` | 读取历史事件解释视图。                           |
| `POST` | `/workflow/runtime/admin/history/{historyInstanceId}/delete`      | 管理端硬删除历史实例。                           |

## 委托配置

委托配置有两个等价根路径：`/workflow/delegation` 和 `/platform.workflow_delegation`。

| 方法   | URL                                        | 功能                                 |
| ------ | ------------------------------------------ | ------------------------------------ |
| `POST` | `/workflow/delegation/query`               | 查询当前用户作为委托人的委托配置。   |
| `POST` | `/workflow/delegation/insert`              | 当前用户新增个人委托配置。           |
| `POST` | `/workflow/delegation/update/{id}`         | 当前用户更新个人委托配置。           |
| `POST` | `/workflow/delegation/delete/{id}`         | 当前用户删除个人委托配置。           |
| `POST` | `/workflow/delegation/enable/{id}`         | 当前用户启用个人委托配置。           |
| `POST` | `/workflow/delegation/disable/{id}`        | 当前用户停用个人委托配置。           |
| `POST` | `/workflow/delegation/delegatedToMe/query` | 查询当前用户作为被委托人的委托配置。 |
| `POST` | `/workflow/delegation/manage/query`        | 管理端查询委托配置。                 |
| `POST` | `/workflow/delegation/manage/insert`       | 管理端新增委托配置。                 |
| `POST` | `/workflow/delegation/manage/update/{id}`  | 管理端更新委托配置。                 |
| `POST` | `/workflow/delegation/manage/delete/{id}`  | 管理端删除委托配置。                 |
| `POST` | `/workflow/delegation/manage/enable/{id}`  | 管理端启用委托配置。                 |
| `POST` | `/workflow/delegation/manage/disable/{id}` | 管理端停用委托配置。                 |

`/platform.workflow_delegation` 等价入口：

| 方法   | URL                                                 | 功能                                 |
| ------ | --------------------------------------------------- | ------------------------------------ |
| `POST` | `/platform.workflow_delegation/query`               | 查询当前用户作为委托人的委托配置。   |
| `POST` | `/platform.workflow_delegation/insert`              | 当前用户新增个人委托配置。           |
| `POST` | `/platform.workflow_delegation/update/{id}`         | 当前用户更新个人委托配置。           |
| `POST` | `/platform.workflow_delegation/delete/{id}`         | 当前用户删除个人委托配置。           |
| `POST` | `/platform.workflow_delegation/enable/{id}`         | 当前用户启用个人委托配置。           |
| `POST` | `/platform.workflow_delegation/disable/{id}`        | 当前用户停用个人委托配置。           |
| `POST` | `/platform.workflow_delegation/delegatedToMe/query` | 查询当前用户作为被委托人的委托配置。 |
| `POST` | `/platform.workflow_delegation/manage/query`        | 管理端查询委托配置。                 |
| `POST` | `/platform.workflow_delegation/manage/insert`       | 管理端新增委托配置。                 |
| `POST` | `/platform.workflow_delegation/manage/update/{id}`  | 管理端更新委托配置。                 |
| `POST` | `/platform.workflow_delegation/manage/delete/{id}`  | 管理端删除委托配置。                 |
| `POST` | `/platform.workflow_delegation/manage/enable/{id}`  | 管理端启用委托配置。                 |
| `POST` | `/platform.workflow_delegation/manage/disable/{id}` | 管理端停用委托配置。                 |

委托查询只支持白名单字段条件：`title`、`enabled`、`principalCanProcess`、`moduleScopeType`、`orgScopeType`；当前不支持自定义排序。

## 工作台入口

工作台入口归在 `/workflow/runtime/workbench` 下，详见 [workbench/WEB_API.md](workbench/WEB_API.md)。

## 动态模块挂接线索

以下是关联入口，来自动态记录动作主链路，不属于本专题 Controller URL；动态模块 action 宿主路径的完整契约留给动态模块 Web API 专题。

工作流发布后可向模块动作目录贡献运行时动作。动态模块仍通过动态运行态的标准动作入口执行这些动作：

| 方法   | URL                                      | 功能                                           |
| ------ | ---------------------------------------- | ---------------------------------------------- |
| `GET`  | `/{moduleAlias}/actions`                 | 查询包含 workflow 贡献动作在内的模块动作目录。 |
| `GET`  | `/{moduleAlias}/actions/{recordId}`      | 查询记录级 workflow 动作可用性。               |
| `POST` | `/{moduleAlias}/{actionCode}/{recordId}` | 通过动态记录动作入口执行 workflow 贡献动作。   |

具体 actionCode 以已发布 workflow 对模块贡献的动作目录为准。
