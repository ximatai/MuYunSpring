# 编码规则 Web API

本文按 URL 简要列功能点，不展开完整请求和响应结构。

## 规则管理

基础路径：

- `/platform.code_rule`
- `/platform/code/rule`

接口：

- `POST /query`：查询编码规则列表，可按规则目标、模式、组织范围、启停状态、生效时间等字段过滤。
- `GET /view/{id}`：查看单条编码规则。
- `GET /query/schema`：查询标准只读规则列表的编译查询契约。
- `GET /targets?moduleAlias=...`：发现静态声明与动态可绑定字段；可省略模块筛选。
- `POST /binding`：配置动态字段角色和触发方式，携带绑定版本；静态声明不可由此修改。
- `GET /rules?moduleAlias=...&entityAlias=...`：读取当前租户内目标的完整规则树。
- `GET /viewTree/{id}`：查看规则树。
- `POST /saveTree`：编译并保存完整规则树，启停通过 `enabled` 一并维护；不开放独立头表编辑。
- `POST /preview`：预览编码，可按 `ruleId` 或草稿规则、上下文、组织、时间和指定序列值生成预览结果。

`targets` 的系统来源字段使用公共字段标题契约，`title` 保留原文，`titleText` 提供语言呈现；主表限定来源通过独立 `ownerTitle` 标明归属，`fieldName` 始终保留可执行的限定名。业务自定义标题不按系统字段翻译。

字段绑定按模块定义归属处理；规则读写与试算要求业务租户上下文。系统管理员配置规则时携带既有 `X-MuYun-Tenant-Id` 请求头。绑定更新使用 `expectedVersion`，规则树使用模型 `version`，避免覆盖他人的修改。

## 运维动作

基础路径同规则管理。

- `POST /ops/view/{id}`：查看单条规则的运维快照。
- `POST /ops/queryByBizObject`：按业务对象查询规则运维快照。
- `POST /ops/sequenceState/locate`：定位规则、依据桶和期间桶对应的序列状态。
- `POST /ops/sequenceState/baseline`：设置序列基线。
- `POST /ops/recycleEntry/{entryId}/adjust`：调整回收池条目状态。
- `POST /ops/ledgerEntry/{entryId}/inspect`：检查编码台账条目。
- `POST /ops/ledgerEntry/{entryId}/release`：释放滞留台账占用。

台账与回收条目操作使用 `opsQuery/opsManage` 管理授权及实际租户隔离，不将条目 ID 当作规则 ID 做记录授权。检查返回 `MATCHED/RECORD_MISSING/VALUE_EMPTY/VALUE_CHANGED`，也可能返回禁止释放的 `TARGET_UNAVAILABLE/OCCUPIED_BY_ANOTHER_RECORD`。匹配持号的保留记录不会被释放；可释放的陈旧占用按原发号事实同步回收池和台账。回收调整禁止重开正在占用、已消费或原策略禁止回收的号码。

## 只读事实表

编码台账：

- `/platform.code_ledger_entry`
- `/platform/code/ledger-entry`
- `POST /query`：查询编码占用事实。
- `GET /view/{id}`：查看台账条目。

编码回收池：

- `/platform.code_recycle_entry`
- `/platform/code/recycle-entry`
- `POST /query`：查询可回收编码。
- `GET /view/{id}`：查看回收池条目。

编码序列状态：

- `/platform.code_sequence_state`
- `/platform/code/sequence-state`
- `POST /query`：查询序列状态。
- `GET /view/{id}`：查看序列状态。
- `POST /adjust/{id}`：按序列状态行调整当前基线值，复用编码运维日志和下一值预览。

编码日志：

- `/platform.code_issue_log`
- `/platform/code/issue-log`
- `POST /query`：查询编码生成、占用、释放等执行日志。
- `GET /view/{id}`：查看编码日志条目。

## 动态记录预览

基础路径：

- `/{moduleAlias}`

接口：

- `POST /code/preview`：按当前动态记录值预览业务编码。该入口走 `CREATE` 动作语义，用于保存前展示候选编码，不表示已正式占用。

## 标准业务表单

动静统一基础路径：`/{moduleAlias}`。

- `POST /code/form-state`：`QUERY` 授权的只读策略与候选查询，不读取业务记录、不分配号码、不写占用。请求包含可选 `entityAlias`、当前 `record` 和子表的 `parent` 草稿；静态子表使用已声明的直接关系别名，动态子表使用实体别名。目标来自已安装定义，不能查询任意实体。
- 响应逐字段返回 `fieldName/mode/trigger/readOnly/configured/preview`。字段 descriptor 中的 `businessCode` 仅声明 `primary/trigger`；机构、生效时间决定的模式不固化进页面定义。
- 不完整来源可能暂时没有候选；缺少有效 `SAVE` 规则且尚未赋码时标准表单阻止提交并提示配置，参与表单的编号字段不因隐藏或只读而跳过该检查；编辑已赋码记录可只读保留原号，保存仍校验真实占用。`EXPLICIT` 字段可等待业务动作。配置缺失在表单正文提示，配置修复后可重试。候选必须与提交草稿隔离，最终编号以保存响应为准。
- 标准表单在编号策略刷新期间报告异步未就绪，主表单与保存操作区展示等待状态；读取失败在表单正文展示原因和重试入口，保留业务草稿。重试成功后重新评估提交资格。候选和旧请求结果不能覆盖当前草稿或更新后的策略；策略读取失败与提交后保存拒绝分别反馈。

静态无页面示例：`POST /education.receipt/insert` 接受 `subject`；`POST /education.receipt/{id}/confirm` 接受 `expectedVersion`，由专用 `confirm` 记录动作授权。该接口固定操作自己的确认号，不开放通用任意模块赋码。

### 人工配置保存请求

- `POST /platform.code_rule/save-requests/{requestId}`：提交标准 `CodeRule` 规则树，复用 `saveTree` 权限与领域保存链路；请求标识为 16–80 位字母、数字或连字符。同用户、业务租户、目标及内容重试返回当前获准读取的规则，不重复写入。
- `GET /platform.code_rule/save-requests/{requestId}?moduleAlias=...&entityAlias=...&fieldName=...`：以 `configurationQuery` 权限核实当前可配置目标与原请求回执；返回 `committed/cancelled/ruleId/savedVersion`。查不到回执不能证明原请求失败；已提交回执仍要求当前规则可读。
- `POST /platform.code_rule/save-requests/{requestId}/abandon`：请求体为 `moduleAlias/entityAlias/fieldName`，复用 `saveTree` 权限。确认已提交，或阻止尚未执行的迟到请求；返回同一回执结构，不撤销配置。

以上入口均要求明确的业务租户，保存沿现有首次启用绑定权限与规则版本约束执行。回执不含完整配置、草稿或授权。
