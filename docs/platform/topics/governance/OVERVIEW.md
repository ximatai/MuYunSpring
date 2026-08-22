# 配置治理专题

## 能力定位

配置治理专题覆盖低代码模块从“能配能跑”进入“可治理、可迁移、可复用、可验收”的生产化能力。它的核心单元是低代码模块包 `LowCodeModulePackage`，不是单个 UI JSON、单张元数据表或整库配置。

本专题与平台配置专题分工明确：平台配置负责应用、模块、元数据、菜单、字典等基础配置；配置治理负责配置包、版本快照、归档指针、配置包迁移、模板复用和健康门禁。

## 核心对象

| 对象                   | 作用                                     | 关键边界                                                                   |
| ---------------------- | ---------------------------------------- | -------------------------------------------------------------------------- |
| `LowCodeModulePackage` | 低代码生产化治理的模块级载体             | 以 `applicationAlias + moduleAlias` 作为稳定身份                           |
| bundle 分层            | 按配置类型拆分包内容                     | 元数据、页面、交互、入口、自动化分别归档，不混成一坨 JSON                  |
| `dependencyManifest`   | 声明迁移和归档前需要满足的外部事实       | 覆盖模块、动作、字典、计量单位、币种、汇率类型、工作流、文件服务和外部依赖 |
| `exchangeManifest`     | 记录包协议、来源版本、来源环境和导出信息 | 用于跨环境迁移和版本追踪，不替代版本表                                     |
| 健康报告               | 归档、导入和模板复用前的结构化门禁       | `FAIL` 阻断，`WARN` 可继续但必须保留诊断                                   |
| 配置版本               | 已归档模块包的不可变快照                 | 当前版本只表达治理导出和迁移基线指针，历史版本保留归档事实                 |
| 导入草稿               | dry-run 之后的最小导入执行承接           | 当前为内存 draft，不持久化、不做复杂合并                                   |
| 模板                   | 从已归档版本生成的可复用样板包           | 模板实例化后生成 `MODULE_FULL` 包，再进入归档链路                          |

## 模块包分层

`LowCodeModulePackage` 当前按以下 bundle 分层：

| 分层          | 内容                                               |
| ------------- | -------------------------------------------------- |
| `METADATA`    | 元数据、字段、关系、引用和能力声明                 |
| `PAGE`        | 列表、表单、详情、查询和页面交付配置               |
| `INTERACTION` | 关联视图、动作区块、弹窗、局部编辑和模块任务       |
| `ENTRY`       | 菜单入口、页面模式、客户端和默认上下文             |
| `AUTOMATION`  | 编码、生成、回写、业务数据导入导出配置和工作流挂点 |

包模式固定为：

1. `MODULE_FULL`：完整模块包，必须包含 `METADATA`，可归档为配置版本。
2. `PAGE_ONLY`：页面迁移包，只允许 `PAGE/INTERACTION/ENTRY`，不直接归档为当前完整版本。
3. `TEMPLATE`：模板包，必须包含 `METADATA`，需实例化为 `MODULE_FULL` 后再进入归档链路。

## 健康门禁

健康检查由 `LowCodeModuleHealthService` 聚合 `LowCodeModuleHealthChecker`，输出 `LowCodeConfigHealthReport`。当前稳定门禁保持克制：

1. 包结构、包模式、bundle 内容和依赖声明基本形态。
2. bundle 顶层 `module/moduleAlias` 与包身份一致。
3. 依赖 manifest 的 resolver 诊断和缺失依赖诊断。
4. 元数据 bundle 中计量单位字段的伴生字段、标准值字段、上下文字段和单位分类依赖声明。
5. 元数据 bundle 中金额字段的币种伴生字段、基准金额字段、汇率日期字段、汇率字段契约，以及币种和汇率类型依赖声明。

健康检查当前只承诺包级身份、依赖事实和少量会影响迁移可用性的字段契约，不深度解析 UI、工作流或自动化配置语义。后续补强时应继续增加独立 checker，避免把治理逻辑堆成单体判断。

## 版本归档与指针切换

`LowCodeModuleConfigArchiveFacade` 将 `MODULE_FULL` 配置包归档为不可变版本快照。执行时先运行健康检查；`FAIL` 阻断归档，`PASS/WARN` 可生成配置版本。

配置版本保存：

1. `packageSnapshotText`：归档时完整包快照。
2. `packageHash`：快照 hash。
3. `summaryJson`：包模式、包含 bundle、健康状态和问题数。
4. `currentVersion`：当前治理导出和迁移基线指针。

指针切换首期只影响 `LowCodeModulePackageExchangeService.exportCurrentPackage`、导入 dry-run 和导入草稿的基线判断，不改写底层元数据、UI、查询、菜单配置，不切换动态运行态，不自动执行数据迁移，也不接入工作流审批。已归档快照不可变，历史版本仍保持 `ARCHIVED` 事实。

## 迁移与导入

`LowCodeModulePackageExchangeService` 承接导出、解析和 dry-run：

1. 从当前治理指针版本或指定历史版本导出模块包 JSON。
2. 解析模块包 JSON 为 `LowCodeModulePackage`。
3. dry-run 复用健康检查，并输出冲突列表。
4. `MODULE_FULL` 指向已有模块时返回 `WARN`，表示后续需要显式归档新版本。
5. `PAGE_ONLY` 要求目标模块已有当前版本，否则阻断。
6. `TEMPLATE` 指向已有模块时阻断。

依赖按两类处理：

1. `MODULE/ACTION/DICTIONARY/MEASURE_UNIT/CURRENCY/EXCHANGE_RATE_TYPE` 是平台默认可解析依赖；required 依赖缺 resolver 或缺失会阻断，optional 依赖只诊断告警。
2. `WORKFLOW/FILE_SERVICE/EXTERNAL` 当前为 manifest-only，缺 resolver 只返回 `WARN`；后续若提供显式 resolver，required 缺失仍应阻断。

计量单位字段使用 `unitCategoryAlias` 时，模块包应在 dependency manifest 中声明 `MEASURE_UNIT` 依赖。推荐依赖身份为 `platform + categoryAlias`，对应平台全局公开或租户公开的共享单位库；历史 `applicationAlias + categoryAlias` 继续兼容。健康检查会对 metadata bundle 的字段清单做轻量契约检查：可选单位必须存在单位伴生字段，标准值字段必须独立存在，记录上下文字段如已配置也必须在包内字段清单中出现。

金额字段使用明确币种码或汇率类型码时，模块包应在 dependency manifest 中声明 `CURRENCY` 与 `EXCHANGE_RATE_TYPE` 依赖。健康检查只校验字段形态、伴生/影子字段和可迁移依赖事实，不在治理层展开业务汇率规则、定价策略或财务结算语义。

`LowCodeModulePackageImportService` 当前只提供最小导入门面：`prepareDraft` 在 dry-run 不阻断时生成内存草稿，`archiveDraft` 校验基线版本未变化后，只允许 `MODULE_FULL` 草稿交给归档门面生成配置版本。当前不持久化草稿、不批量写真实配置表、不做字段级 diff、审批流或合并策略。

## Web 暴露面

配置治理通过 `platform.low_code_governance` 静态模块入口开放。当前 Web 层只承接已有治理门面，覆盖配置包健康检查、归档、指针切换、当前/历史版本导出、导入 dry-run、导入草稿、草稿归档，以及无状态模板派生和实例化；具体 URL 见 `WEB_API.md`。

模板服务当前还没有稳定模板仓库和管理模型。Web 入口只提供从版本派生模板、按客户端提交的模板实例化模块包，不提供模板列表、保存、归档、版本、市场或权限分配。模板实例化结果仍可作为 `LowCodeModulePackage` 进入 dry-run 和归档链路。

## 模板复用

`LowCodeModuleTemplateService` 从已归档 `MODULE_FULL + METADATA` 版本创建模板。模板底包使用 `TEMPLATE` 模式，实例化时生成新的 `MODULE_FULL` 包。

首期实例化规则：

1. 请求必须提供合法 `applicationAlias` 和新 `moduleAlias`。
2. `applicationAlias/module/moduleAlias` 是保留参数，不能被模板参数覆盖。
3. 只替换 bundle 顶层 `module/moduleAlias`。
4. 标题和显式参数只写入 `METADATA` bundle。
5. 依赖 manifest 默认保持不变，不做隐式重写。
6. 实例化结果继续走 dry-run、健康检查和归档链路。

模板首期不做深层 JSON 重写、模板继承、模板市场、自动升级或跨版本参数迁移。

## 演示业务包边界

`sales.contract` 是平台演示业务包，不是具体客户业务系统。它用于反压验证治理链路能表达一个中等复杂模块包，并穿过归档、配置包导出、迁移 dry-run、指针切换、模板实例化和依赖诊断链路。

演示包可以覆盖合同主子表、客户引用、状态字典、列表/表单/详情、查询、关联视图、局部编辑、模块任务、生成/回写、业务数据导入导出配置、权限动作声明和依赖 manifest。治理专题不在这里重做授权运行判定，也不引入业务 service、业务流水或专题数据模型。

## 边界说明

1. 本专题不负责应用、模块、元数据、菜单、字典的基础维护；这些归属配置专题。
2. 本专题不负责运行态 CRUD、动作执行、引用解析和 OpenAPI 消费；这些归属运行态专题。
3. 本专题不负责页面配置细节和前端 bootstrap；这些归属页面交付专题。
4. 本专题不承诺完整配置中心、审批流、字段级 diff、跨版本合并或真实业务系统落地。
5. 后续真实业务专题接入时，应复用模块包、健康门禁、版本、迁移和模板链路，但业务规则和业务流水要按业务边界单独承接。
