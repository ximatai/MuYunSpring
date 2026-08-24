# 动态运行态 Web API

本文按 URL 维度列出动态运行态的基础 Web 入口。路径来自当前 `DynamicRecordWebController`、通用 Web 接口和 `DynamicOpenApiGenerator`，仅作为专题索引，不展开完整 OpenAPI。

动态模块主入口根路径：

```text
/{moduleAlias}
```

`moduleAlias` 必须是平台模块别名，形如 `application.module`。当前动态记录 Web 层以模块主元数据为默认记录对象，不暴露 `/{moduleAlias}/entities/{entityAlias}`。

代码确认口径：`query`、`insert`、`update`、`sort`、`describe`、`openapi`、引用解析和关联视图入口由 `DynamicRecordWebController` 直接声明；`view`、`delete`、`enable`、`disable`、`tree` 和动作目录/执行入口来自其实现的通用 Web 接口默认映射。

## 描述与文档

| 方法  | URL                       | 功能                                                                                                          |
| ----- | ------------------------- | ------------------------------------------------------------------------------------------------------------- |
| `GET` | `/{moduleAlias}/describe` | 返回动态模块 descriptor，包含主实体、字段、能力、关系、引用、视图、关联视图和动作目录；需要模块 `VIEW` 权限。 |
| `GET` | `/{moduleAlias}/openapi`  | 返回由 descriptor 生成的 OpenAPI 3.1.1 文档；需要模块 `VIEW` 权限。                                           |

## 查询与记录

| 方法   | URL                            | 功能                                         |
| ------ | ------------------------------ | -------------------------------------------- |
| `POST` | `/{moduleAlias}/query`         | 按 Web 查询请求分页查询主元数据记录。        |
| `GET`  | `/{moduleAlias}/view/{id}`     | 查看单条主元数据记录。                       |
| `POST` | `/{moduleAlias}/insert`        | 新增主元数据记录。                           |
| `POST` | `/{moduleAlias}/update/{id}`   | 更新主元数据记录。                           |
| `POST` | `/{moduleAlias}/delete/{id}`   | 删除主元数据记录，动态侧按平台软删语义执行。 |

保存请求中的 `id`、`version`、`values`、`children`、`attachments`、`originContext`、`uiConfigId` 和 `record` 是动态记录请求保留字段，不应作为动态业务字段名。

删除、启用和停用均为带乐观锁的记录动作，必须提交当前记录快照：`{"version": n}`。版本不一致时接口返回 `409 CONFLICT_VERSION`，客户端应刷新记录后由用户决定是否重试。

## 能力型标准入口

| 方法   | URL                           | 功能                                                               |
| ------ | ----------------------------- | ------------------------------------------------------------------ |
| `POST` | `/{moduleAlias}/enable/{id}`  | 启用记录；仅启停能力可用时出现。                                   |
| `POST` | `/{moduleAlias}/disable/{id}` | 停用记录；仅启停能力可用时出现。                                   |
| `POST` | `/{moduleAlias}/sort/{id}`    | 排序或树内移动；排序能力或树能力可用时使用。                       |
| `GET`  | `/{moduleAlias}/tree`         | 查询树根节点；支持 `flat` 输出平铺树；仅树能力可用时出现。         |
| `GET`  | `/{moduleAlias}/tree/{id}`    | 查询指定节点树；支持 `flat` 和 `includeSelf`；仅树能力可用时出现。 |

## 引用解析

| 方法   | URL                                             | 功能                                     |
| ------ | ----------------------------------------------- | ---------------------------------------- |
| `POST` | `/{moduleAlias}/references/{fieldName}/resolve` | 按字段引用定义解析候选记录、标题和投影。 |

基础 runtime 只要求引用解析闭环。引用生成和草稿确认归属记录联动专题，不在本文列接口。

## 运行态试算

| 方法   | URL                              | 功能                                                                                |
| ------ | -------------------------------- | ----------------------------------------------------------------------------------- |
| `POST` | `/{moduleAlias}/formula/preview` | 基于动态模块主元数据和当前记录 payload 执行公式试算，返回记录、诊断报告和变更字段。 |

该入口复用动态公式运行态，不落库，不读取已有记录原值。公式规则可以把计算结果写入虚拟字段，响应中的 `record.values` 和 `changedFields` 会体现本次试算结果。保存时仍由 insert/update 链路重新执行公式和校验；请求显式写入虚拟字段会被保存入口拒绝，公式内部计算出的虚拟字段不参与动态表持久化。

## 动作

| 方法   | URL                                      | 功能                               |
| ------ | ---------------------------------------- | ---------------------------------- |
| `GET`  | `/{moduleAlias}/actions`                 | 查询模块动作目录。                 |
| `GET`  | `/{moduleAlias}/actions/{recordId}`      | 查询某条记录上的记录级动作可用性。 |
| `POST` | `/{moduleAlias}/{actionCode}`            | 执行列表级动作。                   |
| `POST` | `/{moduleAlias}/{actionCode}/{recordId}` | 执行记录级动作。                   |
| `POST` | `/{moduleAlias}/{actionCode}/batch`      | 执行批量动作。                     |

`actions` 命名空间只用于动作查询。动作执行直接使用 `{actionCode}` 路径，因此动作编码必须避开平台保留路径。动作命中范围由动作定义声明为列表、记录、批量或任意范围。

动态 CRUD 模块会贡献标准批量删除动作 `batchDelete`，通过 `POST /{moduleAlias}/batchDelete/batch` 执行，请求体提供 `ids`。该动作复用软删、动作授权、数据范围和运行事件链路；权限码继承 `delete`。不提供 `/deleteBatch` 之类平行 URL，记录级删除仍使用 `POST /{moduleAlias}/delete/{id}`。

## 关联视图

关联视图 URL 由动态运行态 Controller 承载，但页面交互专题负责其页面语义和交接说明。

| 方法   | URL                                                         | 功能                             |
| ------ | ----------------------------------------------------------- | -------------------------------- |
| `GET`  | `/{moduleAlias}/associations/relation-overview`             | 返回当前模块关联关系概览。       |
| `GET`  | `/{moduleAlias}/associations/design`                        | 返回关联视图设计描述。           |
| `POST` | `/{moduleAlias}/view/{id}/associations/{viewCode}/query`    | 查询指定记录的关联视图数据。     |
| `POST` | `/{moduleAlias}/view/{id}/associations/{viewCode}/diagnose` | 诊断指定记录的关联视图匹配情况。 |

## 其他专题挂接

导入导出、附件、模块任务、页面偏好、页面 bootstrap、页面导航、编码规则、重复校验、引用生单和生成草稿确认等能力可能复用 `/{moduleAlias}` 根路径或动态动作上下文，但归属各自专题，不在本文维护 URL 清单。
