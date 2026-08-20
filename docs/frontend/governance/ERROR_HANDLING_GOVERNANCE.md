# 错误抛出与前端展示专项治理

本文记录 MuYun 前后端错误治理的阶段性设计。它的目标不是建立一张庞大的错误码表，而是明确后端错误事实、前端展示责任和特殊业务链路的接管边界。

## 问题定位

错误在前后端的责任不同：

| 视角 | 关注点                                                                   |
| ---- | ------------------------------------------------------------------------ |
| 后端 | 数据一致性、错误事实、租户和操作者上下文、运行追踪、审计留痕、后续排查。 |
| 前端 | 当前 UI 场景能否接住错误、错误应展示在哪里、是否需要阻断用户继续操作。   |

后端不应把错误协议设计成 UI 展示协议；前端也不应被迫理解后端完整错误分类。平台需要一套统一后端抛错机制，同时允许前端按场景建立特殊错误展示链路。

## 核心原则

1. 后端错误 envelope 是事实协议，不是 UI 协议。
2. 前端全局错误处理是兜底，不承载业务特殊反馈。
3. 特殊页面、组件或业务链路优先按自己的展示槽位消费 `code`、`targets` 和 `details`。
4. 特殊链路没有接住的错误，必须回落到全局兜底处理。
5. 全局兜底主要根据 HTTP 状态码和当前 UI 上下文决定展示方式，不维护庞大的业务错误码映射。
6. 后端只暴露前端必要事实；严重级别、审计策略、异常类型和内部分类优先留在后端日志、审计和运行事件中。

## 阶段落地状态

当前前端还处于平台 workbench 和登录链路阶段，尚未进入真实动态表单、查重、导入面板或工作流动作区的业务 UI 开发。因此本专项第一阶段只落地已经具备条件的基础能力：

1. 后端统一输出错误 envelope 和 `traceId`。
2. 前端 `web-core` 将 HTTP 失败归一为 `AppError`。
3. 前端提供少量通用展示槽位判定，不建设复杂错误展示中心。
4. 登录链路作为已存在特殊链路，消费登录相关 code。
5. 前端收口当前已确认稳定的平台错误码常量，避免平台代码继续散落魔法字符串。

动态表单字段定位、查重命中、导入行级错误和工作流动作提示属于后续业务触发的技术债。只有对应页面或运行器进入真实开发时，才补特殊 handler 和局部 UI，不提前构造空抽象。

## 后端事实协议

后端对外错误响应保持克制。建议第一阶段稳定以下结构：

```json
{
  "traceId": "req_xxx",
  "code": "DYNAMIC_FIELD_REQUIRED",
  "status": 422,
  "message": "客户名称不能为空",
  "scope": {
    "moduleAlias": "crm.customer",
    "entityAlias": "customer",
    "actionCode": "save"
  },
  "targets": [
    {
      "kind": "field",
      "fieldName": "customerName",
      "relationAlias": "main",
      "rowIndex": 0
    }
  ],
  "details": {}
}
```

字段含义：

| 字段          | 用途                                                                                     |
| ------------- | ---------------------------------------------------------------------------------------- |
| `traceId`     | 串联前端反馈、后端日志、审计和运行事件。                                                 |
| `code`        | 稳定机器码，供特殊链路、测试、日志和排查使用。                                           |
| `status`      | HTTP 状态码的响应体冗余事实，便于日志和非标准 HTTP client 读取；不作为前端展示策略来源。 |
| `message`     | 默认用户文案。特殊链路可以使用，也可以用自己的文案覆盖。                                 |
| `messageArgs` | 可安全展示的模板参数；与 `code` 共同构成未来翻译输入，不承载技术诊断信息。               |
| `scope`       | 错误发生的业务上下文，例如模块、实体、动作。                                             |
| `targets`     | 错误定位事实，例如字段、子表行、记录、动作或附件。                                       |
| `details`     | 特殊链路需要的机器可读补充信息。                                                         |

`scope`、`targets` 和 `details` 只表达事实，不表达 UI 展示策略。

## 默认文案与未来国际化

当前阶段不建设语言包、Locale 解析或翻译服务。业务异常仍返回中文默认 `message`，管理端直接将其作为回退展示；但 `code` 已是稳定的 message key，前端和后端都不得再以文案本身作为判断条件。

需要插值时，服务应提供 `messageArgs`，而不是把记录 ID、技术状态或拼接结果写进 `message`：

```json
{
  "code": "iam.position-category.delete-referenced",
  "message": "该岗位分类已被岗位引用，不能删除",
  "messageArgs": {
    "referenceCount": 3
  }
}
```

`messageArgs` 只允许安全、可展示且可翻译的业务参数；排查数据继续进入 `details`，关联日志继续使用 `traceId`。后续国际化落地时，UI 可按 `code + messageArgs` 选择本地翻译，未命中时回退服务端 `message`。领域 service 不读取 `Accept-Language`，避免业务规则依赖 Web 语言环境。

应用壳当前通过本地 `zh-CN` 默认字典解析少量全局认证提示，键仍然使用错误 `code`；未命中或当前没有对应语言包时回退服务端 `message`。这个入口是后续语言包接入边界，不让认证恢复链路依赖中文文案。

认证失效由应用壳接管倒计时退出时，HTTP 错误会标记为已全局处理；通用展示 adapter 不再二次 toast。页面仍可保留失败后的本地收尾，但不得用同一认证错误覆盖全局退出提示。

第一阶段不建议对前端暴露以下字段作为稳定展示契约：

```text
presentation
severity
recoverable
auditLevel
exceptionType
```

这些信息容易让前端全局层变成复杂策略中心。后端内部仍可保留它们用于日志、审计、告警或运维治理。

## 错误码边界

后端所有错误都可以有 `code`，但前端全局层不应为全部 `code` 建映射表。

平台级 code 先保持少量稳定：

```text
AUTH_REQUIRED
AUTH_EXPIRED
PASSWORD_CHANGE_REQUIRED
ACCESS_DENIED
VALIDATION_FAILED
CONFLICT_VERSION
CONFLICT_UNIQUE
RESOURCE_NOT_FOUND
CONFIG_MISSING
INTERNAL_ERROR
```

专题 code 只在真实特殊展示链路需要时增加，例如：

```text
LOGIN_BAD_CREDENTIALS
DYNAMIC_FIELD_REQUIRED
DUPLICATE_RECORD_MATCHED
IMPORT_ROW_INVALID
WORKFLOW_TASK_ALREADY_PROCESSED
```

新增 code 应满足至少一个条件：

1. 前端特殊链路需要区分展示槽位或交互方式。
2. 后端排查、测试或审计需要稳定机器码。
3. 平台专题需要形成明确业务契约。

不应为了替代普通文案或堆叠历史异常类型而新增 code。

## HTTP 状态码口径

HTTP 状态码用于全局兜底和传输层语义，不承载完整业务含义。

建议口径：

| HTTP 状态 | 通用含义                                       |
| --------- | ---------------------------------------------- |
| `400`     | 请求结构、参数类型或基础请求语义错误。         |
| `401`     | 未登录、token 无效或登录态过期。               |
| `403`     | 已登录但无权限。                               |
| `404`     | 资源不存在，或当前入口无法解析。               |
| `409`     | 乐观锁、状态冲突、唯一冲突等并发或状态类冲突。 |
| `422`     | 业务校验失败，尤其是表单或记录语义校验。       |
| `500`     | 未预期系统异常。                               |
| `503`     | 依赖不可用、系统维护或临时不可服务。           |

特殊链路优先消费 `code`、`targets` 和 `details`；未消费时再交给全局兜底按 HTTP 状态和 UI 上下文处理。

阶段限制：历史代码中大量 `new PlatformException(message)` 仍表示 `VALIDATION_FAILED + 400`，覆盖了请求语义错误、配置规则错误和部分业务校验。当前阶段不批量重判这些调用点。新增字段级校验可继续使用 `PlatformErrors.validation(...)`；新增业务动作失败应优先使用 `BusinessExceptions` 返回稳定 `ActionMessage` 和合适 HTTP 状态。后续按业务链路逐步把可定位的表单/记录校验从默认 `PlatformException(message)` 迁出。

## 前端处理模型

前端错误处理分两层：

```text
特殊业务 handler
  -> 能处理：展示在特殊槽位，结束
  -> 不能处理：交给全局 handler

全局 handler
  -> 根据 HTTP 状态和 UI 上下文兜底展示
```

未来业务链路接入形态：

```ts
try {
  await saveDynamicForm();
} catch (rawError) {
  const error = normalizeError(rawError);

  if (tryHandleDynamicFormError(error, formContext)) {
    return;
  }

  const presentation = resolveGlobalErrorPresentation(error, {
    phase: "action",
    surface: "form",
  });
  renderGlobalError(presentation);
}
```

特殊 handler 必须返回是否已处理。当前动态表单等业务 handler 尚未建设，等对应 UI 进入真实开发时再补：

```ts
type ErrorHandled = boolean;
```

这个返回值是防止特殊链路污染全局链路的关键边界。

## 展示槽位

前端设计不应从错误类型出发，而应从展示槽位出发。

特殊槽位包括：

```text
login-form-error
form-field-error
form-banner-error
duplicate-check-dialog
import-result-panel
action-area-error
workflow-task-hint
```

通用槽位保持极少：

```text
redirect-login
page-error
global-toast
global-modal
silent
```

特殊槽位由具体页面、组件或运行器接管。通用槽位由 `web-core` 和 UI adapter 提供统一兜底。

## 全局兜底策略

全局 handler 不理解动态表单字段、查重结果、导入行错误或工作流动作区。它只根据 HTTP 状态码和当前 UI 上下文决定兜底方式。

建议上下文：

```ts
interface ErrorUiContext {
  phase: "page-load" | "action" | "background";
  surface: "workbench" | "form" | "table" | "dialog" | "unknown";
}
```

建议兜底规则：

| 条件                       | 兜底展示                                    |
| -------------------------- | ------------------------------------------- |
| `401`                      | 登录恢复或跳转登录页。                      |
| `PASSWORD_CHANGE_REQUIRED` | 打开改密入口，保留当前登录会话。            |
| `403` + `page-load`        | 页面级无权限错误。                          |
| `403` + `action`           | 全局 toast。                                |
| `404` + `page-load`        | 页面级不存在错误。                          |
| `409` + `action`           | 全局 modal 或 toast，按操作是否阻断决定。   |
| `5xx` + `page-load`        | 页面级错误，展示 `traceId`。                |
| `5xx` + `action`           | 全局 toast，展示 `traceId`。                |
| `background`               | 默认 silent，可记录前端日志；必要时轻提示。 |
| 其他未接管错误             | 全局 toast。                                |

同一个 HTTP 状态在不同上下文下展示不同，这是前端职责，不应由后端 envelope 指挥。

## 特殊链路边界

### 登录

登录失败属于当前已具备条件的特殊链路。登录页优先消费登录相关 code：

```text
LOGIN_BAD_CREDENTIALS
AUTH_EXPIRED
AUTH_REQUIRED
```

能消费时展示在登录表单顶部或对应输入项；不能消费时交给全局兜底。

### 强制修改密码

`PASSWORD_CHANGE_REQUIRED` 表示当前 Bearer token 仍然有效，但只允许访问身份确认、登出和修改本人密码入口。前端应打开改密入口，不得清除本地会话或跳回登录页。

### 动态表单保存

动态表单保存属于后续业务触发项，当前不建设真实 handler。进入动态表单运行器开发后，再优先消费字段和子表定位：

```text
targets.kind = field
targets.fieldName
targets.relationAlias
targets.rowIndex
```

字段错误展示在字段下方，子表错误展示在对应行和列。没有可定位 target 的校验错误，可以展示在表单顶部。仍无法识别的错误交给全局兜底。

### 查重命中

查重命中属于后续业务触发项，当前不建设真实 handler。它不是普通失败 toast。专题 code 示例：

```text
DUPLICATE_RECORD_MATCHED
```

`details` 可携带候选重复记录、确认策略或后续动作所需事实。动态页面或业务页面接管后打开查重确认弹窗；未接管时回落到全局 toast。

### 导入导出

导入失败如果有错误文件或行列定位，应由导入结果面板接管：

```text
details.errorFileId
targets.rowIndex
targets.fieldName
```

全局 handler 不负责解释导入明细。当前导入结果面板尚未进入前端真实开发，先记录为技术债。

### 工作流动作

工作流任务已处理、任务状态变化、审批动作不可用等错误，应优先展示在动作区或任务提示区。全局 handler 只兜底未被动作区接管的错误。当前工作流动作区前端尚未进入真实开发，先记录为技术债。

## 后端抛错建议

后端可以通过统一异常和工厂方法生成错误事实：

```java
throw PlatformErrors.validation(
        "DYNAMIC_FIELD_REQUIRED",
        "客户名称不能为空",
        ErrorTarget.field("customerName").relation("main"));
```

```java
throw BusinessExceptions.warning(
        "DUPLICATE_RECORD_MATCHED",
        "发现可能重复的记录");
```

```java
throw PlatformErrors.config(
        "DYNAMIC_DESCRIPTOR_MISSING",
        "模块页面配置不存在",
        ErrorScope.module(moduleAlias));
```

后端异常处理器负责补齐 `traceId`、HTTP 状态、默认 message 和日志上下文。审计、运行事件、异常堆栈和内部分类不必全部暴露给前端。

## web-core 建设口径

前端 `web-core` 第一阶段只提供轻量基础契约：

```ts
interface AppError {
  status?: number;
  traceId?: string;
  code: string;
  message: string;
  scope?: Record<string, unknown>;
  targets: ErrorTarget[];
  details?: Record<string, unknown>;
}

function normalizeError(error: unknown): AppError;

function resolveGlobalErrorPresentation(
  error: AppError,
  context: ErrorUiContext,
): GlobalErrorPresentation;
```

当前不建设全局 UI store 或复杂 adapter。等 workbench UI 需要真实 toast、modal、page-error 编排时，再基于 `GlobalErrorPresentation` 接入具体 UI 组件。

后续专题或页面自行提供特殊 handler：

```ts
function tryHandleDynamicFormError(
  error: AppError,
  context: DynamicFormContext,
): boolean;
function tryHandleLoginError(
  error: AppError,
  context: LoginFormContext,
): boolean;
function tryHandleImportError(
  error: AppError,
  context: ImportPanelContext,
): boolean;
```

`web-core` 不注册所有业务 code，也不维护完整业务展示矩阵。

## 与动态页面运行器的关系

动态页面运行器是特殊链路集中区，但不应把所有错误都吞掉。

运行器应接管：

1. 字段和子表行级校验。
2. 表单级校验。
3. 查重命中。
4. 动态动作区可识别错误。
5. 页面 bootstrap 可识别的配置缺失和入口错误。

运行器不认识或不适合局部展示的错误，应回落到全局 handler。

当前动态页面运行器还没有进入真实业务表单开发，上述内容是技术债触发条件，不是当前阶段验收项。

## 后续触发点

下列能力暂不在第一阶段完整建设，按业务条件触发：

1. 动态表单字段级、子表行级和表单顶部错误展示。
2. 查重命中确认弹窗和候选记录交互。
3. 导入行级错误面板和错误文件下载入口。
4. 工作流动作区错误提示和任务状态刷新。
5. 多语言错误文案和 `i18nKey`。
6. 面向前端的完整错误码目录和在线文档。
7. 前端错误展示策略配置化。
8. iframe 或 online 子应用错误协议。
9. 后端审计级别和前端展示级别联动。

进入国际化、online 子应用、统一运维告警或复杂配置治理时，再按实际场景扩展。

## 验收口径

第一阶段完成时，应能证明：

1. 后端所有平台异常都能输出统一 envelope 和 `traceId`。
2. 前端 HTTP client 能把统一 envelope 转成 `AppError`，并保留 `code`、`traceId`、`scope`、`targets` 和 `details`。
3. 登录特殊链路能区分 `AUTH_REQUIRED`、`AUTH_EXPIRED` 和 `LOGIN_BAD_CREDENTIALS`。
4. 全局兜底不依赖庞大的业务 code 映射，只返回少量展示槽位。
5. 前端测试覆盖 `normalizeError`、统一 envelope 解析和全局兜底分流。
6. 后端测试覆盖典型异常到 envelope 的转换。

## 专项收尾结论

当前错误治理专项先暂停在基础契约层，不继续扩展业务 UI：

1. 已完成：后端统一 envelope、`traceId` 响应头、平台错误码基础集合、`PlatformErrors` 工厂契约、前端 `AppError`、HTTP 失败归一、全局展示槽位判定和登录特殊链路。
2. 不继续提前建设：全局错误 store、toast/modal/page-error adapter、动态表单字段错误 handler、查重确认弹窗、导入结果面板和工作流动作区提示。
3. 触发后再建设：对应前端页面或运行器进入真实开发，且能够明确错误展示位置、消费规则和“已处理后不再全局兜底”的返回契约。
4. 如果先进入 workbench 全局通知建设，先接通用 toast/modal/page-error adapter，再接业务特殊 handler。
5. 后端持续约束：新增字段级校验优先使用 `PlatformErrors.validation(...)`，新增业务动作失败优先使用 `BusinessExceptions`；历史 `new PlatformException(message)` 按业务链路逐步迁移，不做无差别批量重判。

跨专题剩余债务记录在 [技术债记录](../../TECHNICAL_DEBT.md) 的 `DD-004`；本文件保留具体错误契约和触发条件。
