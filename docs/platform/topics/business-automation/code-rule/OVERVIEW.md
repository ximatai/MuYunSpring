# 编码规则

编码规则用于为业务记录生成稳定编码，并维护编码占用、序列状态和可回收编码。静态声明、动态记录与显式领域 API 共用生成和占用执行，配置页面及统一页面字段投影仍按能力契约建设。

动静统一编码能力的目标、静态显式 API 与声明式接入边界、生命周期及分阶段验收见 [业务编码能力契约](DESIGN.md)。本文仅说明当前交付范围；现有静态声明和取号门面不等于编码配置与标准页面已经形成完整使用闭环。

## 能力范围

- 规则以 `moduleAlias/entityAlias/fieldName` 定位目标字段。
- 支持规则树保存、查看、预览和正式生成。
- 支持序列状态、编码台账和回收池。
- 动态记录新增、更新、删除生命周期可接入编码占用、释放和复用。
- 静态 Service 通过 `CodeAbility` 和模型 `@BusinessCode` 声明接入，不要求动态元数据或管理页面；字段可以声明 `SAVE` 或 `EXPLICIT` 触发。
- 静态业务通过 `CodeRuntimeFacade.preview/generateOnly/issueAndBind` 预览、正式取号或取号并绑定自己的业务目标。显式赋码后的标准保存认可精确匹配的已有占用。

## 运行边界

编码台账是当前占用事实表，不是流水日志。同一租户、模块、实体、字段和编码值只有一条当前事实，跨规则共享占用约束，状态在 `ACTIVE`、`AVAILABLE`、`DISCARDED` 间流转。

软删除保留编码占用，恢复校验原占用归属；最终清理和硬删除才按实际发号时保存的策略释放。旧号的规则、计数范围和周期以原台账为准，不随当前规则与日期重算。回收只消费真正释放的号码，不通过软删除绕过业务唯一约束。

标准保存与显式绑定在业务事务内执行，台账通过宿主数据库事务锁串行化同一目标的占用变化；序列分配和回收池消费已在 PostgreSQL 路径验证并发边界；非 PostgreSQL 数据库不宣称具备同等原子语义。

编码规则中的日期段、周期键和系统时间预览使用平台业务时间服务解析当前业务本地时间。组织时区解析已通过平台时间底座接入；如果没有匹配的组织时区解析器，则回退到平台默认时区。后续租户、组织主数据时区应继续接入同一时间服务，不在编码规则内另建时区规则。

## 业务使用口径

- 动态业务配置编码规则后，由动态记录保存链路自动完成赋码和占用。
- 保存前如需前端预览，可通过动态记录编码预览入口获得候选编码。
- 运维人员可通过规则运维入口查看规则快照、定位序列桶、调整序列基线、调整回收池状态、检查或释放台账；也可从序列状态行直接发起基线调整。

## 静态业务接入

模型在持久化字符串字段上声明 `@BusinessCode`，Service 实现 `CodeAbility<T>`。默认实体别名是模块 alias 的最后一段，可通过 `codeEntityAlias()` 显式指定。`primary` 与 `trigger` 属于字段声明；规则维护入口从静态声明校验目标，不伪造动态字段 ID。

`SAVE` 字段在标准保存中自动处理；`@BusinessCode(trigger = CodeTrigger.EXPLICIT)` 字段可在草稿中为空，由已授权领域动作显式调用：

```java
// 在领域动作的业务事务内；调用前完成动作授权、记录范围、审批和版本校验。
var command = CodeRuntimeCommand.builder(moduleAlias, entityAlias, "confirmationNo")
        .sourceRecordId(record.getId())
        .organizationId(organizationId)
        .context(context)
        .build();
record.setConfirmationNo(codeRuntime.issueAndBind(command).value());
service.update(record);
```

`preview` 不分配流水；`generateOnly` 正式分配但不建立记录占用；`issueAndBind` 对目标与记录幂等绑定。独立取号与后续独立保存不构成一个事务。无事务宿主的手工装配不承诺数据库原子性，标准静态自动赋码要求有效业务事务。

接口摘要见 [WEB_API.md](WEB_API.md)。
