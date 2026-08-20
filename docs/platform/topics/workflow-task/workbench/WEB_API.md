# 工作流工作台 Web API

本文按当前 `WorkflowRuntimeWebController` 中已存在的 URL 梳理工作台入口，只列功能点。

根路径：`/workflow/runtime/workbench`

## 卡片查询

| 方法   | URL                                            | 功能                           |
| ------ | ---------------------------------------------- | ------------------------------ |
| `POST` | `/workflow/runtime/workbench/todo/query`       | 查询当前用户待办卡片。         |
| `POST` | `/workflow/runtime/workbench/done/query`       | 查询当前用户已办卡片。         |
| `POST` | `/workflow/runtime/workbench/notice/query`     | 查询当前用户知会卡片。         |
| `POST` | `/workflow/runtime/workbench/tracking/query`   | 查询当前用户发起跟踪卡片。     |
| `POST` | `/workflow/runtime/workbench/delegation/query` | 查询当前用户委托责任视角卡片。 |

请求使用当前用户作为操作者。`operatorId` 字段即使出现在请求模型中，当前 controller 也不会用它覆盖当前用户。

## 统计

| 方法   | URL                                         | 功能                          |
| ------ | ------------------------------------------- | ----------------------------- |
| `POST` | `/workflow/runtime/workbench/{board}/stats` | 查询指定工作台 board 的统计。 |

当前约定使用的 board 包括 `tracking`、`todo`、`done`、`notice`、`delegation`。

## 查询字段

工作台请求可携带分页、筛选和排序：

| 字段                                        | 说明                                       |
| ------------------------------------------- | ------------------------------------------ |
| `page`                                      | 分页参数。                                 |
| `moduleAlias`                               | 按业务模块筛选。                           |
| `recordId`                                  | 按业务记录筛选。                           |
| `definitionId`                              | 按工作流定义筛选。                         |
| `workflowVersionId` / `definitionVersionId` | 按工作流版本筛选。                         |
| `instanceStatus`                            | 按实例状态筛选。                           |
| `nodeKey`                                   | 按当前节点筛选。                           |
| `taskKind`                                  | 按任务类型筛选。                           |
| `taskStatus`                                | 按任务状态筛选。                           |
| `assignmentKind`                            | 按分派类型筛选。                           |
| `overtimeStatus`                            | 按超期状态筛选。                           |
| `readStatus`                                | 知会列表按全部、未读或已读筛选。           |
| `startedFrom` / `startedTo`                 | 按发起时间范围筛选。                       |
| `receivedFrom` / `receivedTo`               | 按接收时间范围筛选。                       |
| `completedFrom` / `completedTo`             | 按完成时间范围筛选。                       |
| `lastOperatedFrom` / `lastOperatedTo`       | 按最后操作时间范围筛选。                   |
| `dueFrom` / `dueTo`                         | 按到期时间范围筛选。                       |
| `addedByAddSign`                            | 筛选加签产生的任务。                       |
| `addSignSourceNodeKey`                      | 按加签来源节点筛选。                       |
| `submitterUserId`                           | 按发起人筛选。                             |
| `sorts`                                     | 工作台排序列表，具体字段以服务白名单为准。 |

## 相关操作入口

工作台卡片通常会继续跳转到下列运行入口：

| 方法   | URL                                                              | 功能                         |
| ------ | ---------------------------------------------------------------- | ---------------------------- |
| `GET`  | `/workflow/runtime/instance/{instanceId}/bundle`                 | 查看运行实例详情和画布回显。 |
| `POST` | `/workflow/runtime/task/{taskId}/actions/{actionCode}`           | 处理待办任务。               |
| `POST` | `/workflow/runtime/task/{taskId}/read`                           | 标记知会已读。               |
| `GET`  | `/workflow/runtime/task/{taskId}/module-task/prepare`            | 准备业务任务办理。           |
| `POST` | `/workflow/runtime/task/{taskId}/module-task/check-and-continue` | 完成业务任务检查并继续推进。 |
