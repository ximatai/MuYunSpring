# 计算公式使用说明

计算公式用于声明可重复、可审计的业务判断和计算；它不是浏览器脚本、表达式注入入口或通用流程编排器。公式由平台解析、校验并编译为受限程序，浏览器只执行平台下发的受限 `WEB_UI` 程序，保存和业务事实仍由服务端负责。

## 先判断使用哪一类公式

| 场景 | 选择 | 真相源与执行位置 |
| --- | --- | --- |
| 字段可见、必填、只读等交互判断 | `WEB_UI` 谓词 | 服务端编译并随页面 descriptor 下发；浏览器仅即时渲染 |
| 表单内的即时派生值 | `FORM_COMPUTE` | 服务端签发计算程序；浏览器可预览，服务端保存前仍复算 |
| 动态记录的默认值、保存校验、保存前计算、动作前校验、导入校验 | 动态元数据公式规则 | 动态运行态服务端执行；`formula/preview` 只提供当前草稿试算 |
| 聚合子表中由一个变更行影响同一直接子表其他行 | 受限子表 `FORM_COMPUTE` | 前端即时预览，父记录保存时服务端再次调和 |

不要用 `WEB_UI` 隐藏字段代替服务端授权、数据范围、保存校验或状态流转；也不要把公式用于跨模块写入、异步任务、流程编排或任意 JavaScript。

## 表单交互公式

静态模块通过 `UiFormula` 声明 `visible`、`required`、`readOnly` 等规则。例如：

```java
.field("pageMode", field -> field.visible(
        UiFormula.booleanExpression("{moduleAlias.entryType} == 'module'")))
```

`{fieldName}` 读取当前草稿字段。公式程序只能由服务端生成；浏览器不接收、解析或执行原始表达式，也不能补充未被 descriptor 声明的字段。

### 读取引用目标字段

当规则依赖已选择的 ONE 引用目标时，在引用字段上显式声明 `selectionProjections`，再使用相对目标的点路径：

```java
@ReferenceTo(target = SupplierService.class,
        selectionProjections = {"organizationId.regionCode"})
private String supplierId;

.field("taxPolicy", field -> field.visible(
        UiFormula.booleanExpression("{supplierId.organizationId.regionCode} == 'CN'")))
```

该声明不是对象导航语法糖，而是一次受控的引用读取授权：

1. 第一段必须是当前表单已声明的 ONE 引用；后续引用跳也必须是已声明的 ONE 引用。
2. 末端字段以及每一跳均按 `REFERENCE` 数据范围和字段保护读取；不可见或不存在时公式只获得空值。
3. 投影只存在于浏览器的临时选择上下文，不写入业务草稿、保存请求或记录持久化字段。
4. 静态、动态与混合引用目标使用同一条路径契约；实现方不得通过前端候选对象绕过声明。

投影路径应只取规则真正需要的最小字段。不要为“以后可能用到”而暴露整段目标记录，也不要用 MANY 引用驱动标量表单规则。

## 动态记录公式规则

动态模块的公式规则由元数据关系维护，并在模块刷新后编译为运行态定义。规则必须明确 `ruleKind`、`rulePhase`、目标字段和启用状态；保存、动作或导入入口只执行与自身阶段匹配的规则。

页面 onchange 需要即时结果时调用 `/{moduleAlias}/formula/preview`。返回结果只是当前 payload 的试算：客户端可以展示派生值和诊断，但不能以此替代实际保存。保存前服务端会在同一业务能力、权限、租户、数据范围和事务边界内重新执行适用规则。

## 设计与治理边界

- 公式负责确定性判断和字段计算；外部 I/O、跨聚合写入、通知、任务调度和流程推进应进入各自的平台能力。
- `WEB_UI` 只影响交互，不构成安全边界；所有引用、查询、详情与变更仍由后端执行授权和范围约束。
- 公式修改属于配置变更：动态规则通过运行态刷新生效，页面只消费已编译 descriptor，不依赖浏览器私有解释器。
- 新增公式 profile、字段路径能力或语义时，必须同时更新本说明、页面 DSL 指南和对应契约测试。

相关入口见[页面 DSL 使用指南](../page/PLATFORM_DRIVEN_FRONTEND_DSL_GUIDE.md)、[动态运行态概览](../runtime/OVERVIEW.md)和[页面交互 Web API](../page/interaction/WEB_API.md)。
