# 页面交互 Web API

本文只按当前 `DynamicRecordWebController` 和 `DynamicModuleTaskWebController` 能确认的 URL 梳理页面交互接口。

## 关联视图

| 方法   | URL                                                         | 功能                                                 |
| ------ | ----------------------------------------------------------- | ---------------------------------------------------- |
| `GET`  | `/{moduleAlias}/associations/relation-overview`             | 返回当前模块关联关系概览。                           |
| `GET`  | `/{moduleAlias}/associations/design`                        | 返回关联视图设计描述。                               |
| `POST` | `/{moduleAlias}/view/{id}/associations/{viewCode}/query`    | 查询指定记录的关联视图数据；请求体沿用列表查询协议。 |
| `POST` | `/{moduleAlias}/view/{id}/associations/{viewCode}/diagnose` | 诊断指定记录的关联视图匹配情况。                     |

关联查询会解析当前模块主实体、关联视图和目标模块查询条件，目标输出按目标模块 LIST 语义处理。

## 动作区块与动作执行

| 方法   | URL                                      | 功能                                                   |
| ------ | ---------------------------------------- | ------------------------------------------------------ |
| `GET`  | `/{moduleAlias}/actions`                 | 查询当前用户可见的模块动作目录。                       |
| `GET`  | `/{moduleAlias}/actions/{recordId}`      | 查询记录级动作可用性。                                 |
| `POST` | `/{moduleAlias}/{actionCode}`            | 执行列表级动作。                                       |
| `POST` | `/{moduleAlias}/{actionCode}/{recordId}` | 执行记录级动作；局部编辑和弹窗提交也回到该类动作链路。 |
| `POST` | `/{moduleAlias}/{actionCode}/batch`      | 执行批量动作，请求需提供记录 ID 集合。                 |

`actions` 命名空间只用于动作查询和可用性读取。动作执行直接使用 `{actionCode}` 路径，因此 action code 必须避开平台保留路径。

这些入口是局部编辑、动态弹窗和普通动态 action 的共同宿主路径；具体业务动作可以来自页面交互配置，也可以来自其他专题托管的动态动作。专题语义不因此迁入页面专题。

## 动态弹窗

动态弹窗没有独立专用 URL。DIALOG action 通过动作执行入口初始化弹窗，返回 `dialogKey`、`actionCode`、`submitActionCode`、`submitPath`、`recordId`、`refreshOnSuccess`、`redirectTo` 等弹窗契约字段。

弹窗提交使用返回的 `submitPath`，实际仍是动态 action 主链路。提交目标 action 不能继续是 DIALOG，避免弹窗嵌套动作链失控。

## 局部信息编辑

局部编辑没有独立专用 URL。前端按 bootstrap 下发的 `actionBlocks[type=localEdit]` 渲染入口，提交时调用对应动态记录级动作：

| 方法   | URL                                      | 功能                                                                               |
| ------ | ---------------------------------------- | ---------------------------------------------------------------------------------- |
| `POST` | `/{moduleAlias}/{actionCode}/{recordId}` | 提交局部编辑 payload；后端按发布 `localEdit` UI 配置限制字段范围并执行乐观锁更新。 |

payload 中应包含 `record.version`、`record.values` 和 `uiConfigId`。实际可编辑字段以后端发布配置和动作授权为准。

## 公式即时试算

| 方法   | URL                              | 功能                                                                                    |
| ------ | -------------------------------- | --------------------------------------------------------------------------------------- |
| `POST` | `/{moduleAlias}/formula/preview` | 按页面当前记录 payload 执行已发布公式规则试算，返回试算后的记录、变更字段和结构化诊断。 |

该入口服务 onchange、表单预览等页面交互场景，只做运行态计算，不保存数据，也不生成业务流水。请求体使用 `record` 包裹当前页面记录状态，支持 `values` 和 `children`；响应中的 `changedFields` 用于前端判断需要回填的字段。

公式校验失败时接口返回 `report` 诊断，不按保存链路抛出业务异常。字段可见和可编辑范围仍由页面 bootstrap、动作配置和保存接口兜底；试算入口不替代最终保存校验。

## 模块任务

| 方法   | URL                                       | 功能                                       |
| ------ | ----------------------------------------- | ------------------------------------------ |
| `GET`  | `/{moduleAlias}/tasks/definitions`        | 读取模块任务定义。                         |
| `POST` | `/{moduleAlias}/view/{id}/tasks/check`    | 检查指定记录的任务状态，返回任务状态列表。 |
| `POST` | `/{moduleAlias}/view/{id}/tasks/evaluate` | 检查指定记录的任务状态，返回完整检查结果。 |

任务检查会先校验租户上下文和当前记录 `VIEW` 数据范围。`check` 是轻量状态列表入口，`evaluate` 保留更完整的诊断结果。

## Bootstrap 输出

交互区块通过 `/platform.menu/{menuId}/entry` 返回的 `resolvedConfig` 下发：

| 字段                | 说明                                                                                |
| ------------------- | ----------------------------------------------------------------------------------- |
| `associationBlocks` | 已发布详情 UI 配置中的关联视图区块。                                                |
| `actionBlocks`      | 已发布详情 UI 配置中的 `dialog`、`action`、`localEdit` 区块，并按可见 action 裁剪。 |
| `taskBlocks`        | 已发布详情 UI 配置中的模块任务区块。                                                |

前端可以据此渲染交互区块，但真实访问和执行仍以后端接口权限、数据范围和动作可用性为准。
