# 配置治理 Web API

本文按当前已开放的治理 URL 梳理接口线索。配置治理入口围绕 `LowCodeModulePackage`、健康门禁、配置版本和导入导出组织，不复用动态运行态 `/{moduleAlias}` 业务数据接口。

## 平台入口

| URL 前缀                        | 说明                                     |
| ------------------------------- | ---------------------------------------- |
| `/platform.low_code_governance` | 平台静态模块入口，进入平台动作和权限体系 |
| `/platform/low-code-governance` | 同一治理能力的路径风格别名               |

## 配置包与版本

| 方法   | URL                                                                                       | 功能点                                                           |
| ------ | ----------------------------------------------------------------------------------------- | ---------------------------------------------------------------- |
| `POST` | `/platform.low_code_governance/packages/health`                                           | 对提交的模块配置包执行健康检查，返回 `LowCodeConfigHealthReport` |
| `POST` | `/platform.low_code_governance/packages/archive`                                          | 归档 `MODULE_FULL` 配置包，生成不可变版本快照并切换当前治理指针  |
| `POST` | `/platform.low_code_governance/modules/{moduleAlias}/versions/{versionId}/switch-current` | 将指定模块当前治理指针切换到历史版本                             |
| `GET`  | `/platform.low_code_governance/modules/{moduleAlias}/package`                             | 导出模块当前配置包                                               |
| `GET`  | `/platform.low_code_governance/versions/{versionId}/package`                              | 导出指定历史版本配置包                                           |

## 导入迁移

| 方法   | URL                                                    | 功能点                                                           |
| ------ | ------------------------------------------------------ | ---------------------------------------------------------------- |
| `POST` | `/platform.low_code_governance/imports/dry-run`        | 对提交的配置包执行导入预检，返回健康结果和冲突诊断，不写真实配置 |
| `POST` | `/platform.low_code_governance/imports/drafts`         | 在预检不阻断时准备导入草稿，记录基线版本信息                     |
| `POST` | `/platform.low_code_governance/imports/drafts/archive` | 归档导入草稿；若草稿基线版本已变化则由服务层拒绝                 |

## 计量单位与金额诊断

计量单位和金额字段的归档前诊断复用配置包健康检查和导入 dry-run：

| 入口                                                 | 用途                                                        |
| ---------------------------------------------------- | ----------------------------------------------------------- |
| `POST /platform.low_code_governance/packages/health` | 校验当前模块包中的计量单位字段契约、金额字段契约和依赖声明  |
| `POST /platform.low_code_governance/imports/dry-run` | 在跨环境导入前校验计量单位依赖、计量/金额字段契约和冲突诊断 |

健康检查会扫描 `METADATA` bundle 中的字段清单，支持字段扁平属性、运行态 `measureUnit` 对象和运行态 `money` 对象形态。计量单位常见诊断码：

| 诊断码                             | 级别    | 含义                                                                            |
| ---------------------------------- | ------- | ------------------------------------------------------------------------------- |
| `MEASURE_UNIT_DEPENDENCY_MISSING`  | `WARN`  | 模块包使用了计量单位分类，但 dependency manifest 未声明共享 `MEASURE_UNIT` 依赖 |
| `MEASURE_UNIT_BASE_UNIT_MISSING`   | `ERROR` | 计量单位字段缺少 `baseUnitCode`                                                 |
| `MEASURE_UNIT_BASE_VALUE_MISSING`  | `ERROR` | 缺少标准值影子字段或包内字段清单未包含该字段                                    |
| `MEASURE_UNIT_BASE_VALUE_CONFLICT` | `ERROR` | 标准值字段和主业务字段冲突                                                      |
| `MEASURE_UNIT_MODE_MISSING`        | `ERROR` | 计量单位字段缺少固定/可选单位模式                                               |
| `MEASURE_UNIT_FIXED_UNIT_MISSING`  | `ERROR` | 固定单位模式缺少 `fixedUnitCode`                                                |
| `MEASURE_UNIT_COMPANION_MISSING`   | `ERROR` | 可选单位模式缺少单位伴生字段                                                    |
| `MEASURE_UNIT_SCOPE_FIELD_MISSING` | `ERROR` | 配置了换算上下文字段，但包内字段清单缺失该字段                                  |

`MEASURE_UNIT` 依赖推荐声明为 `platform + categoryAlias`，对应共享单位库；历史 `applicationAlias + categoryAlias` 仍兼容。`WARN` 不阻断归档或导入预检，但会提示跨环境迁移风险；`ERROR` 会使健康状态进入 `FAIL`，归档门面会拒绝继续归档。

金额字段常见诊断码：

| 诊断码                                                                                       | 级别    | 含义                                                                                |
| -------------------------------------------------------------------------------------------- | ------- | ----------------------------------------------------------------------------------- |
| `MONEY_OWNER_NOT_NUMERIC`                                                                    | `ERROR` | 金额主字段不是数值类型                                                              |
| `MONEY_CURRENCY_MODE_MISSING` / `MONEY_CURRENCY_MODE_INVALID`                                | `ERROR` | 金额字段缺少或错误配置固定/可选币种模式                                             |
| `MONEY_CURRENCY_COMPANION_MISSING` / `MONEY_CURRENCY_COMPANION_NOT_TEXT`                     | `ERROR` | 可选币种模式缺少文本型币种伴生字段                                                  |
| `MONEY_BASE_AMOUNT_MISSING` / `MONEY_BASE_AMOUNT_CONFLICT` / `MONEY_BASE_AMOUNT_NOT_NUMERIC` | `ERROR` | 基准金额影子字段缺失、冲突或类型不兼容                                              |
| `MONEY_FIXED_CURRENCY_MISSING` / `MONEY_FIXED_CURRENCY_INVALID`                              | `ERROR` | 固定币种缺失或不是 ISO 4217 三位字母代码                                            |
| `MONEY_DEFAULT_CURRENCY_INVALID` / `MONEY_BASE_CURRENCY_INVALID`                             | `ERROR` | 默认币种或基准币种不是 ISO 4217 三位字母代码                                        |
| `MONEY_CURRENCY_DEPENDENCY_MISSING`                                                          | `WARN`  | 金额字段引用了明确币种码，但 dependency manifest 未声明 `CURRENCY` 依赖             |
| `MONEY_RATE_TYPE_MISSING` / `MONEY_RATE_TYPE_INVALID`                                        | `ERROR` | 汇率类型缺失或不是平台代码格式                                                      |
| `MONEY_RATE_TYPE_DEPENDENCY_MISSING`                                                         | `WARN`  | 金额字段引用了明确汇率类型，但 dependency manifest 未声明 `EXCHANGE_RATE_TYPE` 依赖 |
| `MONEY_RATE_DATE_FIELD_MISSING` / `MONEY_RATE_DATE_FIELD_NOT_DATE`                           | `ERROR` | 汇率日期字段缺失或不是日期/时间类型                                                 |
| `MONEY_EXCHANGE_RATE_FIELD_MISSING` / `MONEY_EXCHANGE_RATE_FIELD_NOT_NUMERIC`                | `ERROR` | 汇率影子字段缺失或不是数值类型                                                      |

金额字段如声明了 `fixedCurrencyCode`、`defaultCurrencyCode`、`baseCurrencyCode` 或 `rateTypeCode`，模块包应同步声明 `CURRENCY` 与 `EXCHANGE_RATE_TYPE` 依赖，便于跨环境迁移前确认目标环境已有对应平台事实。

## 模板复用

| 方法   | URL                                                    | 功能点                                                                       |
| ------ | ------------------------------------------------------ | ---------------------------------------------------------------------------- |
| `POST` | `/platform.low_code_governance/templates/from-version` | 基于已归档版本创建 `LowCodeModuleTemplate`；来源必须是包含元数据的完整模块包 |
| `POST` | `/platform.low_code_governance/templates/instantiate`  | 使用客户端提交的模板和实例化参数生成新的 `MODULE_FULL` 配置包                |

## 当前边界

1. 当前开放的是已有治理门面的薄 Web 层，不在 Controller 中重写归档、指针切换、冲突判断或健康检查逻辑。
2. 动态业务数据导入导出仍归属 `/{moduleAlias}/import`、`/{moduleAlias}/export`、`/{moduleAlias}/exchange/template` 等运行态接口，不等同于配置包治理。
3. 导入草稿当前是最小执行承接，不是服务端持久化草稿仓库；归档草稿时由客户端回传 draft，服务层仍会校验归档条件和基线版本。
4. 模板复用当前是无状态工具入口，不是模板仓库；服务端不保存模板、不提供模板列表、模板归档、模板版本或模板市场。
5. 治理对象的模块身份统一使用 `moduleAlias`；模块包身份同时包含 `applicationAlias + moduleAlias`。
6. `PAGE_ONLY` 包不能夹带元数据或自动化配置，是否可导入由预检结果说明。
