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

字段绑定按模块定义归属处理；规则读写与试算要求业务租户上下文。系统管理员配置规则时携带既有 `X-MuYun-Tenant-Id` 请求头。绑定更新使用 `expectedVersion`，规则树使用模型 `version`，避免覆盖他人的修改。

## 运维动作

基础路径同规则管理。

- `POST /ops/view/{id}`：查看单条规则的运维快照。
- `POST /ops/queryByBizObject`：按业务对象查询规则运维快照。
- `POST /ops/sequenceState/locate`：定位规则、依据桶和期间桶对应的序列状态。
- `POST /ops/sequenceState/baseline`：设置序列基线。
- `POST /ops/recycleEntry/{id}/adjust`：调整回收池条目状态。
- `POST /ops/ledgerEntry/{id}/inspect`：检查编码台账条目。
- `POST /ops/ledgerEntry/{id}/release`：释放滞留台账占用。

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
