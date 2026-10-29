# 动作结果与数据变更契约

## 1. 目标

建立统一的业务动作结果契约，覆盖静态 CRUD、静态业务动作、动态动作和工作流动作。

该契约需要同时满足：

1. Controller 保持贴近原始业务返回，不手工构造统一包装对象。
2. 后端表达业务结果、可读消息及数据变化事实。
3. 前端根据有限语义统一渲染，不为每个接口编写样板代码。
4. 后端不向前端下达刷新、弹窗、关闭页面等 UI 指令。
5. HTTP 回执和数据变化广播来自同一份已提交事实。
6. 静态模块优先使用 Java 类型强引用，避免业务代码依赖字符串解析。
7. 动态模块通过元数据接入相同的外部契约。

## 2. 非目标

本能力不负责：

- 指定消息使用 Toast、弹窗还是行内方式展示；
- 指定页面刷新、对话框关闭或路由跳转；
- 在广播消息中直接携带完整业务数据；
- 建立通用的企业级事件总线；
- 通过注解表达复杂的数据依赖和记录 ID 提取规则。

## 3. ActionResult

业务动作成功后，HTTP 输出层统一形成：

```json
{
  "data": {
    "id": "account-1",
    "username": "demo-admin"
  },
  "message": {
    "code": "iam.employee-account.provisioned",
    "text": "账号已创建并绑定职员",
    "type": "SUCCESS"
  },
  "changeSetId": "change-set-1",
  "changes": [
    {
      "moduleAlias": "iam.employee-account",
      "recordId": "account-1",
      "type": "record-created"
    },
    {
      "moduleAlias": "iam.employee",
      "recordId": "employee-1",
      "type": "record-updated"
    }
  ]
}
```

字段来源：

| 字段          | 提供方                              | 形成阶段                       |
| ------------- | ----------------------------------- | ------------------------------ |
| `data`        | Controller 原始返回值               | Controller 返回时              |
| `message`     | 应用 Service 或平台标准动作         | 业务结果确定时                 |
| `changeSetId` | 动作执行上下文                      | 请求进入时生成，事务提交后生效 |
| `changes`     | Service、通用 CRUD 能力及影响解析器 | 事务成功提交后确认             |
| 最终包装      | Web 输出层                          | HTTP 序列化前                  |

`data` 不再区分顶层 `record`、`count` 等不同包装形式。Controller 原本返回什么，统一放入 `data`。

## 4. ActionMessage

```java
public record ActionMessage(
        String code,
        String text,
        ActionMessageType type,
        Map<String, Object> messageArgs,
        TextReference textSource
) {
}
```

第一阶段仅支持有限枚举：

```java
public enum ActionMessageType {
    SUCCESS,
    INFO,
    WARNING,
    ERROR
}
```

语义如下：

- `SUCCESS`：业务操作按预期完成；
- `INFO`：中性业务结果；
- `WARNING`：存在需要关注的业务情况；
- `ERROR`：业务操作失败。

禁止引入以下 UI 类型：

```text
TOAST
MODAL
CENTER
TOP_RIGHT
REFRESH
CLOSE_DIALOG
```

后端负责：

- 判断业务结果；
- 提供可读 `text`；
- 提供有限的消息性质；
- 提供可选的稳定业务 `code`。

前端负责：

- 决定是否展示；
- 决定展示位置和组件；
- 根据 `type` 统一选择视觉样式；
- 决定当前页面是否静默处理。

前端不得根据 `text` 内容执行逻辑分支。

### 4.1 标准消息

通用 CRUD 消息由平台统一提供，例如：

```text
「演示租户」新增成功
「演示租户」修改成功
「演示租户」删除成功
```

业务 Controller 和 Service 不重复声明。

记录展示文本来自模块的统一记录标题事实：静态模型使用 `@TitleField`，动态模型使用字段元数据的
`titleField`。页面 descriptor 只投影对应字段名，删除确认、标准动作消息、回收站展示和恢复反馈不得
各自维护另一套 `title/name/alias/id` 猜测规则。

回收站恢复和彻底删除也进入同一业务动作结果链路。完整成功使用 `SUCCESS`；尽力执行产生跳过或失败
明细时保留原始报告，并使用 `WARNING` 表达汇总结果。前端按照有限消息类型选择展示语气，不根据消息
文本执行逻辑分支。

### 4.2 特殊成功消息

特殊业务消息由应用 Service 在确定业务结果后报告：

```java
actionMessageReporter.success(
        "iam.employee-account.provisioned",
        "账号已创建并绑定职员"
);
```

Controller 不参与消息组装。

### 4.3 失败消息

特殊业务失败由业务异常携带：

```java
throw BusinessExceptions.warning(
        "iam.employee-account.username-occupied",
        "登录账号已被占用");
```

统一异常处理器负责形成错误响应，并保留正确的 HTTP 状态。需要翻译的业务失败可通过 `BusinessException(code, TextReference, messageType, messageArgs)` 声明原文来源及参数，完整构造也支持指定状态、作用域和目标。HTTP 展示层与成功回执共用文本解析契约，原异常和错误日志保留原文事实；稳定错误码、参数与目标不受语言影响。

成功与失败可以复用 `ActionMessage` 结构，但不能混淆 HTTP 成功和失败语义。

失败响应的迁移边界：

- 用户可修正的业务失败或配置失败使用 `BusinessException` / `BusinessExceptions`，并提供稳定 `code`；
- 参数校验、权限、认证和乐观锁等通用失败由 Web 异常处理器映射为统一 `ActionMessage`；
- 平台运行时不变量、调用上下文缺失、数据结构循环和系统故障继续使用平台异常或系统异常；
- 后端失败消息只表达业务语义，不表达 Toast、弹窗、刷新、关闭页面等前端 UI 行为。

乐观锁冲突统一返回 `CONFLICT_VERSION`。前端保存流可以为该错误配置页面级 `actionErrorHandlers`：当页面能确认当前正在编辑的记录时，局部 handler 标记该记录已发生外部变更，并在编辑区域提示重新加载；当 handler 返回 `false` 或没有匹配 handler 时，错误继续走全局展示。页面级接管不得吞掉不匹配记录、非编辑态或无法定位记录的冲突。

## 5. BusinessMutation

`BusinessMutation` 是 Web 动作边界标记：

```java
@Target({ElementType.METHOD, ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface BusinessMutation {
}
```

它只表达：

> 当前 Handler 是需要进入统一业务动作结果链路的写操作。

示例：

```java
@BusinessMutation
@PostMapping("/{employeeId}/account/provision")
public EmployeeAccountView provisionAccount(...) {
    return employeeAccountApplicationService.provision(...);
}
```

它负责触发：

1. 创建动作执行上下文；
2. 启用业务结果和数据变化收集；
3. 启用统一 HTTP 输出包装。

它不负责声明：

- 业务消息；
- 模块标识；
- 记录 ID；
- 数据变更类型；
- UI 行为；
- 字符串属性解析表达式。

标准静态 CRUD 应在平台基类统一接入，普通业务 Controller 不重复标记。

查询、文件下载、流式响应、第三方回调等不进入该机制。

### 5.1 StandardMutation

标准静态写动作使用 `StandardMutation` 声明默认结果策略：

```java
@BusinessMutation
public @interface StandardMutation {
    StandardMutationKind value();
}
```

第一阶段仅表达有限标准动作：

```java
CREATE, UPDATE, DELETE, ENABLE, DISABLE, SORT
```

它负责让平台根据标准动作、当前静态模块身份、返回记录或路径变量自动派生：

- 标准成功消息；
- 标准数据变化事实；
- HTTP `ActionResult` 包装。

它不负责表达：

- 自定义业务消息；
- 跨模块影响；
- 复杂记录 ID 提取规则；
- 字符串属性解析表达式；
- UI 行为。

标准动作注解不能演进为配置语言。复杂业务事实仍由应用 Service 通过 reporter 显式报告。

## 6. 数据变更声明

数据变更由真正了解业务结果的 Service 或平台能力报告。

职员账号创建场景包括：

```text
账号记录已创建
职员记录的账号读取投影已变化
```

静态业务代码应通过 Java 类型强引用声明：

```java
changeRecorder.created(EmployeeAccountService.class, accountId);
changeRecorder.updated(EmployeeService.class, employeeId);
```

平台负责将静态 Service 类型解析为稳定的外部模块标识。

动态模块由动态运行时提供模块身份，但最终形成相同的 `DataChange` 外部契约。

业务代码不得依赖以下方式：

```java
changeRecorder.updated("iam.employee", employeeId);
```

第一阶段，跨模块投影影响由业务 Service 显式报告。后续静态引用和读取投影依赖图成熟后，可以由平台影响解析器补齐派生变化。

业务内容译文通过 `business-content-changed` 表达普通阅读投影变化，保持业务原文及其版本不变。确认、显式删除和机器结果写入产生该事实，私有人工草稿不产生该事实；载荷仅标识受影响模块，不携带正文或记录身份。HTTP 动作由同一 MutationContext 汇入回执，后台生成由提交后适配器通知，前端重新受权读取。

## 7. 事务边界

Service 在事务中登记的是变更意图，不是最终事件。

完整执行过程：

```text
识别 @BusinessMutation
    ↓
创建 MutationContext
    ↓
Controller 调用应用 Service
    ↓
Service 登记消息和变更意图
    ↓
事务成功提交
    ↓
形成 CommittedChangeSet
    ↓
HTTP 包装 + 异步事件广播
```

如果事务回滚：

- 不生成成功 `ActionResult`；
- 不形成已提交 `ChangeSet`；
- 不广播数据变化事件；
- 由统一异常处理链路返回失败结果。

请求中的多个相同变更应在形成 `CommittedChangeSet` 时去重。

## 8. HTTP 回执与事件广播

HTTP 回执和事件广播同源于事务提交后形成的 `CommittedChangeSet`，对外 payload 按接收者投影：

```text
CommittedChangeSet
    ├── ActionResultResponseAdvice → 发起者 HTTP 完整 changes
    └── DataChangeEventPublisher  → 实时投递
          ├── 发起用户私有队列 → 完整 changes
          └── 其他用户私有队列 → 按接收者授权的安全投影
```

### 8.1 HTTP 回执

发起操作的前端通过 HTTP 收到完整的 `CommittedChangeSet`（`changeSetId` 与 `changes`），立即获知本次提交的记录、资源、scope 和业务 facts，不等待实时广播。

### 8.2 事件广播

发起用户的私有队列承接同账号多会话同步，收到完整变化事实。其他在线用户发送前复核有效会话，按本次操作租户分区过滤，并在接收者身份与租户上下文下重新检查模块 `QUERY` 权限。

其他用户的 payload 仍使用 `CommittedChangeSet` 结构并保留同一 `changeSetId`，但 `changes` 是安全投影：

- 普通记录及子资源变化归并为模块级 `collection-changed`（由 `DataChange.collectionChanged(...)` 产生）；
- `business-content-changed` 保留业务内容阅读失效类型；
- 无记录、资源与 scope 身份的 `module-page-configuration-changed`、`configuration-translations-changed` 保留共享配置失效类型，可跨业务租户通知；
- 所有投影均移除 `recordId`、`resourceKey`、`scope`，且 `facts` 为空；未通过授权或没有可发送变化时不投递。

普通变化仅发给本次操作租户内的用户及通过授权的系统用户。跨租户写入仍以操作分区为准，尚不推导记录实际 owner 分区。接收者须通过既有授权查询接口重新读取可见数据，不能从失效提示推断具体记录可见性。广播不携带刷新、弹窗等 UI 指令。

### 8.3 去重

发起操作的前端可能同时收到：

- HTTP 返回的 `changeSetId`；
- 实时广播中的相同 `changeSetId`。

前端应使用该标识去重，避免重复处理同一批变化。

## 9. 事件可靠性

第一阶段可以在事务提交后发布进程内事件，并异步发送 WebSocket 或 SSE 消息。

进入多实例部署或需要可靠补发后，演进为窄范围的 DataChange Outbox：

```text
业务数据 + Outbox
    同一事务提交
        ↓
异步分发器
        ↓
实时广播
```

广播失败不得使已成功提交的业务操作返回失败。

## 10. Web 输出包装

Controller 不返回 `ActionResult`：

```java
public EmployeeAccountView provisionAccount(...) {
    return service.provision(...);
}
```

统一 Web 输出层负责组合：

```text
Controller 原始 data
+ ActionMessage
+ CommittedChangeSet
= ActionResult
```

包装层必须：

- 保留原始 HTTP 状态；
- 避免重复包装；
- 排除查询、下载、流式响应及第三方回调；
- 支持原始返回值为 `void`；
- 不介入异常响应处理；
- 正确识别继承自静态 CRUD 基类的 Handler。

使用统一动作结果的删除接口不应返回 `204 No Content`，建议返回 `200` 和 `data: null`，以便携带消息和数据变化事实。

## 11. 分层边界

```text
平台契约层
  ├── ActionMessage
  ├── DataChange
  ├── CommittedChangeSet
  └── 变更记录门面

应用 Service
  ├── 执行业务
  ├── 声明特殊业务消息
  └── 报告直接数据变化

Web Adapter
  ├── BusinessMutation
  ├── MutationContext 建立
  └── ActionResultResponseAdvice

事件 Adapter
  └── 消费 CommittedChangeSet 并异步广播

前端
  ├── 通用消息渲染
  ├── 数据变化消费
  └── 页面级响应策略
```

平台契约层不得依赖 Spring MVC、WebSocket 或前端框架。

## 12. 首批落地范围

第一阶段以职员账号业务为纵向试点：

1. 创建账号并绑定职员；
2. 移除职员账号并物理删除账号；
3. 返回账号原始业务数据；
4. 返回特殊成功或失败消息；
5. 报告账号和职员两个模块的数据变化；
6. 验证 HTTP 与异步广播共享 `changeSetId`；
7. 验证事务回滚时不返回和不广播错误变化。

试点完成后依次接入：

1. 顶层静态 CRUD；
2. 嵌套静态标准写动作；
3. 其他静态业务动作；
4. 动态动作；
5. 工作流动作。

嵌套静态标准写动作已接入第一阶段，但只覆盖标准 CRUD、启停和排序。当前变化事实仍按所属静态模块表达。更细的父子资源身份、`resourceKey` 和 `scope` 语义暂不在第一阶段扩展，避免把嵌套路由结构误当成稳定外部资源模型。

## 13. 契约测试

至少覆盖：

- Controller 原始返回值被正确放入 `data`；
- `void` 返回被包装为 `data: null`；
- 标准 CRUD 自动形成标准消息；
- 特殊业务消息由 Service 提供；
- 静态模块强引用正确解析；
- 事务回滚不产生 `changes` 和广播；
- 重复变化正确合并；
- HTTP 和广播使用相同 `changeSetId`；
- 继承的静态 CRUD Handler 能识别 `BusinessMutation`；
- 文件、流和查询响应不被包装；
- 错误响应保留正确 HTTP 状态；
- 前端能够通用渲染所有 `ActionMessageType`。

## 14. 后续实现映射

本文档先作为设计基线。落地时按以下映射推进，避免新增契约与已有平台对象重名或职责重叠。

| 存量对象或链路                                              | 当前处理                                                                                                                                                        | 目标                                                                         |
| ----------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------- |
| `CrudWeb` 标准 `insert` / `update` / `delete`               | 使用 `StandardMutation` 进入动作管线，标准 Web 方法在写成功后登记标准消息和变化事实                                                                             | 外部 HTTP 契约统一，业务代码不再使用旧顶层 `record` 包装                     |
| `EnableWeb` / `SortWeb`                                     | 使用 `StandardMutation` 进入动作管线；启停按记录更新处理，排序按集合变化处理                                                                                    | 标准静态写动作默认携带标准消息和变化事实                                     |
| `TreeWeb`                                                   | 使用 `StandardMutation(SORT)` 接入树排序；排序按集合变化处理                                                                                                    | 树排序与普通排序使用相同外部动作结果契约                                     |
| 嵌套静态 CRUD / Tree CRUD                                   | 标准增改删、启停、普通排序和树排序已接入 `StandardMutation`；查询和树查询保持原查询响应                                                                         | 保持 controller 返回原始记录或计数，HTTP 输出层统一包装                      |
| `StandardMutationResultSupport`                             | 作为 boot web 层公开门面，供标准静态动作登记标准消息和变化事实                                                                                                  | `platform` 包等自定义 Web support 不依赖包内 helper                          |
| `StaticStandardMutationSupport`                             | 作为标准 Web 基类内部执行 helper，负责 data scope、selectForAction、动作策略等上下文细节                                                                        | 不作为跨包业务接入门面                                                       |
| `BusinessMutationResult`                                    | 作为简单非标准静态动作的声明式结果契约，使用 service class 强引用目标模块；记录 ID 通过 `recordIdSource = PATH_VARIABLE` 和显式 `recordId` 声明                 | 仅适合成功即必然发生目标变化的动作；不支持对象路径、返回值路径等字符串表达式 |
| `BusinessMutationResultSupport`                             | 作为 boot web 层业务动作门面，供复杂非标准静态动作登记业务消息和显式变化事实                                                                                    | 不复用标准 CRUD 文案，不表达 UI 行为                                         |
| 业务专用静态动作                                            | 简单单结果动作使用 `@BusinessMutationResult`；复杂多影响动作使用 `@BusinessMutation` 加代码式 reporter；底层业务 Service 不因 HTTP 输出契约反向依赖 boot web    | Controller 返回贴近原始业务数据                                              |
| 授权集合动作                                                | 第一阶段用 `COLLECTION_CHANGED` 表达授权集合变化；账号角色授权当前归入 `RoleService` 聚合模块；可能幂等的授权动作由代码式 reporter 根据业务结果决定是否报告变化 | 授权关系独立成静态模块后，再切换为独立模块身份                               |
| 计数型动作                                                  | Controller 直接返回 `int` / `Integer`；进入 `BusinessMutation` 时统一放入 `data`                                                                                | 计数语义保留为原始数字，不再引入计数包装模型                                 |
| 动态记录 CRUD                                               | 第一阶段不进入 `ActionResult` 管线；Controller 返回原始动态记录或原始计数，不再沿用旧顶层 `record` / `count` 包装                                               | 后续映射到相同 `ActionResult`，动态元数据提供模块身份                        |
| `DynamicActionResultBody`                                   | 暂保持旧形态，禁止作为新静态动作结果参考                                                                                                                        | 拆分为业务数据、业务消息、数据变化事实和明确交互动作                         |
| `WorkflowTaskActionResult` / `WorkflowInstanceActionResult` | 暂保持工作流专用结果                                                                                                                                            | 后续由工作流 Adapter 映射到统一外部动作结果                                  |
| `PlatformWebError`                                          | 继续由异常处理链路返回，保留 HTTP 失败状态                                                                                                                      | 可复用 `ActionMessage` 结构表达业务错误性质                                  |

项目未上线，不保留旧顶层 `record` 包装的过渡兼容。前端静态客户端只消费原始记录、原始计数或统一 `ActionResult`；计数型动作进入动作包装后通过 `data` 读取真实计数。

通过任务等间接目标办理业务的接口，可声明 `@BusinessMutation(actionContextRequired = false)`，由领域服务校验身份、任务归属与真实业务动作权限，并在事务内产生变更事实。该声明只开启事实收集和标准结果交付，不提供授权，也不取代领域校验。业务接口默认仍要求已解析的模块动作上下文。

## 业务命令确认与回执恢复

前端 `createOperationConfirmation` 同时服务普通业务与助手。即时完成的既有 adapter 可继续返回
`OperationPresentation`；异步命令返回 `OperationReceipt`，由领域 adapter 将权威事实映射为
`processing`（已受理、未完成）、`succeeded` 或 `failed`。`failed` 表示原命令已经确认失败，
不表示可以重放；只有 `OperationRejectedError` 明确证明未接纳写入时，才允许原确认在有效期内重试。
普通异常、超时、无回执和查询权限不足均保持结果未知，不自动重提。查询失败可能令处理中的结果变为未知，
但不会重新开放执行入口。

`restoreOperationConfirmation({ presentation, lookup }, scopeIsCurrent)` 只恢复查询能力，不接收执行回调，
也不恢复确认授权。普通业务持久化自己的请求身份，通过 `lookup` 在当前权限下查询领域回执；平台不规定业务
回执端点、补偿动作或新建一张通用命令表。助手历史恢复复用同一状态机，其现有可持久化引用类型不因此扩大。
`check()` 在 processing／unknown 状态可由用户显式触发；重复点击合并为同一次请求，终态不再查询或执行。
命令受理后不再受原确认草稿的过期时间限制，但仍受身份与执行范围限制。

`scopeIsCurrent` 必须绑定创建时的身份及业务范围代次，而不只比较可再次出现的用户 ID。
平台在请求前后复核范围，拒绝迟到结果进入新范围；读取回执返回副本，避免展示方改写执行事实。
页面保存可能重建 Surface。接入方可通过可选 `receiptScopeIsCurrent` 区分提交范围与回执范围：
提交检查原 Surface 有效性，回执仍须检查原身份、页面和业务范围，但不因同范围内 Surface 重建而丢失。
未提供该谓词时，回执与提交使用同一范围检查。
查询回执与撤销、释放资源、重试业务任务分别是读和写操作；后者仍应由领域重新授权、明确确认并执行。
