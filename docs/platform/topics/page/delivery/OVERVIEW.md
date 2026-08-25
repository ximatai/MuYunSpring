# 页面交付概览

页面交付把动态模块从后端可交付推进到前端可按配置运行：菜单能进入页面，bootstrap 能下发页面上下文，列表、表单、查询、汇总、附件、查重、引用候选和页面偏好能走稳定后端契约。

## 能力定位

页面交付不建设另一套动态表单内核。它消费已发布 UI 配置和查询模板，并通过动态 Web 入口执行数据读写。

核心能力：

1. 菜单入口定位模块、页面模式、客户端类型、默认 UI 配置、默认查询模板和入口参数。
2. bootstrap 返回菜单解析后的模块身份、主实体别名、权限裁剪的 resolved 页面配置和 OpenAPI 入口；模块运行态 descriptor 继续由标准模块 context 交付。
3. 列表查询、汇总和引用候选复用同一查询模板与 Criteria 编译链路。
4. 表单保存直接提交业务记录；`version` 作为标准字段参与乐观锁，按已发布 UI 配置执行 required/readOnly 校验。
5. 子表保存沿用动态记录 `children` 语义：缺省或 `null` 表示不改，空数组表示提交空子表。
6. 附件只维护业务记录与 `fileId` 的关系，上传、预览、下载通过 access envelope 对接文件服务。
7. 字段文件引用由 descriptor 自动渲染标准上传控件：前端以当前模块的 upload-ticket 上传并仅回填 `fileId`；保存生命周期确认并转正新增文件，并从实体新旧值自动推导提交后的旧文件删除。具体边界见 [文件引用生命周期](../../../FILE_REFERENCE_LIFECYCLE.md)。
8. 查重预检绑定动态 action 槽位和权限，不替代数据库唯一约束。
9. 页面偏好属于当前用户体验配置，不改变平台 UI 配置真相源。

## 主链路

```text
/platform.menu/{menuId}/entry
  -> PlatformPageBootstrap
  -> resolvedConfig
  -> /{moduleAlias}/query
  -> /{moduleAlias}/insert 或 /update/{id}
  -> 动态记录服务、权限、数据范围、事件和审计
```

bootstrap 只消费在线发布快照。未发布配置、其他客户端配置和不可见动作不应进入在线响应。

## 查询语义

列表查询以 `WebQueryRequest` 为入口，支持：

| 输入                  | 作用                                                                                     |
| --------------------- | ---------------------------------------------------------------------------------------- |
| `uiConfigId`          | 控制列表列投影和页面字段范围。                                                           |
| `queryTemplateId`     | 使用已发布查询模板。                                                                     |
| `externalQueryValues` | 为查询模板中的外部值占位提供运行时值。                                                   |
| `queryForm`           | 按已发布 LIST UI 的可见主表字段提交表单值；区间 UI 控件会编译为范围查询。                |
| `criteria`            | 表达任意层级 `AND/OR` 分组嵌套。                                                         |
| `conditions`          | 兼容顶层扁平 `AND` 条件。                                                                |
| `quickSearch`         | 在已发布 LIST 配置的可见主关系非虚拟文本字段范围内编译为 `LIKE`。                        |
| `page` / `sorts`      | 分页和排序；排序字段必须落在动态 SQL mapper 可识别的标准字段、能力字段或物理业务字段内。 |

运行时合并规则为：

```text
queryTemplateId/externalQueryValues
AND queryForm
AND conditions
AND criteria
AND quickSearch
```

汇总面板和引用候选复用这条查询语义，避免形成第二套查询协议。

列表的常驻查询控件、查询摘要及动态 `LIST_DETAIL_CARD` 的对应声明方式，统一见[平台驱动前端 DSL 使用指南](../PLATFORM_DRIVEN_FRONTEND_DSL_GUIDE.md#列表常驻查询与查询摘要)。本页只定义请求的合并与执行语义，不重复页面表达 DSL。

`queryForm` 只接受已发布 LIST UI 中可见的主关系字段。普通字段按字段配置链的默认查询操作符编译；`date_range` 和 `date_time_range` 字段 UI 控件按 `BETWEEN` 编译，值可以是 `[start, end]` 数组，也可以是 `{ "start": "...", "end": "...", "timeZone": "Asia/Shanghai" }` 对象。复合控件值分量由 bootstrap 的 `resolvedConfig.fieldUiControls[].bindings` 作为控件目录下发，具体字段通过 `fieldUiControlAlias` 引用该目录。字段形态由动态 descriptor 的 `fields[].storageForm` 和页面 bootstrap 的 `resolvedConfig.uiFields[].fieldForm` 下发；虚拟字段不会被配置为可查询字段，也不会参与 quickSearch、列表排序或关联视图查询排序。读链路已经注入到记录上的虚拟值可以随列表、详情和 LIST UI 投影输出，公式虚拟值的即时展示仍通过 `formula/preview` 获取。

时间字段查询遵循动态字段语义：`DATE` 按业务日期直接查询；`TIMESTAMP` 和 `ZONED_TIMESTAMP` 如果收到 ISO 本地日期闭区间，会按查询项 `timeZone` 或平台默认业务时区转换成 UTC 半开区间执行。查询模板的 `timeZone` 使用 IANA `ZoneId`，不接受 `+08:00` 这类纯 offset。

时间字段展示由前端平台组件统一承担。后端 API 对绝对时刻保持 ISO instant 机器语义；列表、详情、系统信息和会话信息等页面展示应使用平台时间组件按当前用户上下文中的有效时区格式化，并在 tooltip 中保留 UTC 原值和展示时区。业务日期字段仍按日期展示，不做时区换算；没有当前用户时区时，前端只允许以浏览器时区作为展示兜底。

## 保存语义

页面保存始终以业务记录作为请求体。带 `uiConfigId` 时：

1. UI 配置必须来自已发布快照。
2. 主关系字段校验当前记录。
3. 子关系字段只校验本次提交的 `children.{relationCode}` 行。
4. 未提交子表不校验；空数组表示提交了空子表。
5. `version` 参与乐观锁。
6. 字段保护、动作权限、数据范围和动态事件仍由动态保存链路处理。

标准 `insert` / `update` 保存入口拒绝显式写入虚拟字段，包括主表和本次提交的子表行。虚拟字段可以作为页面展示字段进入 descriptor 和 bootstrap；读取记录时已经由引用标题、引用投影等平台读链路注入的虚拟值会随记录输出。页面如需基于当前表单值展示公式派生结果，应调用公式试算入口，由后端返回本次计算出的虚拟字段值。

动态记录请求保留字段包括 `id`、`version`、`values`、`children`、`attachments`、`originContext`、`uiConfigId` 和 `record`，不应作为业务字段名使用。

## 边界说明

1. 静态模块 UI 声明不另建页面内核；后续按统一 resolved descriptor 和读投影路线接入页面交付，职员、部门样板阶段结果见 [静态 UI 样板接入收口记录](STATIC_UI_SAMPLE_INTEGRATION_STATUS.md)。
2. 页面配置以 `moduleAlias` 和元数据字段稳定 ID 为锚点，不引入平行字段身份。
3. 菜单入口不负责权限本身；菜单剪枝、动作授权和数据范围由身份权限专题负责。页面请求携带菜单入口上下文时，默认只能调用同一模块；只有 Web 层通过 `@PlatformPageEntryChild(parentModuleAlias = "…")` 声明为该页面子资源时，才允许其辅助接口复用父菜单上下文，不能由前端任意指定跨模块豁免。它不复用 `@PlatformStaticModule.parent`，后者只描述持久化的平台模块树。
4. 附件接口不保存 MIME、大小、上传人等文件事实。
5. 用户偏好不改变已发布 UI 配置，只影响当前用户的页面体验。
6. 关联视图、局部编辑、动态弹窗和模块任务归属页面交互专题。
