# 计量单位产品化走查

本文用于走查计量单位从平台配置到动态记录保存的最小可交付链路。它不是完整用户手册，只记录当前后端能力已经支撑的配置路径、验收口径和管理端产品化缺口。

## 样例目标

以销售订单行 `sales.order / line` 为例，业务录入数量时允许选择包装单位，并由平台写入统一标准值：

| 业务输入 | 配置事实                   | 标准值   |
| -------- | -------------------------- | -------- |
| `2托`    | `1托 = 48箱`，`1箱 = 12瓶` | `1152瓶` |
| `2卷`    | `1卷 = 30米`               | `60米`   |

字段形态：

| 字段           | 角色           | 说明                                          |
| -------------- | -------------- | --------------------------------------------- |
| `quantity`     | 主业务数值字段 | 用户录入的业务数量                            |
| `quantityUnit` | 单位伴生字段   | 保存单位 code，例如 `pallet`、`box`、`bottle` |
| `quantityBase` | 标准值影子字段 | 平台生成，保存基准单位下的数值                |

## 配置路径

1. 创建应用与模块。
   - 应用 alias：`sales`
   - 模块 alias：`sales.order`
   - 主元数据 alias：`line`

2. 创建计量单位分类。
   - `package`：数量分类，基准单位 `bottle`
   - `roll`：卷分类，基准单位 `roll`
   - `length`：长度分类，基准单位 `m`

3. 创建单位目录。
   - `package / bottle`
   - `package / box`
   - `package / pallet`
   - `roll / roll`
   - `length / m`

4. 创建业务硬换算规则。
   - `package:pallet -> package:box`，factor `48`
   - `package:box -> package:bottle`，factor `12`
   - `roll:roll -> length:m`，factor `30`
   - 规则可按平台全局、租户公开、模块或记录上下文生效；同一边优先使用更具体的上下文规则。

5. 配置模块字段。
   - `quantity` 是数值字段，并启用计量单位契约。
   - 包装数量使用可选单位模式：`unitFieldName=quantityUnit`，`baseValueFieldName=quantityBase`，`baseUnitCategoryAlias=package`，`baseUnitCode=bottle`，`conversionMode=BUSINESS_RULE`。
   - 卷长换算可使用固定单位模式：`fixedUnitCode=roll`，`baseUnitCategoryAlias=length`，`baseUnitCode=m`，`conversionMode=BUSINESS_RULE`。
   - 可选单位字段使用伴生字段，标准值字段使用影子字段并由平台管理。

6. 保存模块字段配置，等待自动刷新后验收运行态契约。
   - 模块字段保存时会自动创建或复用单位伴生字段和标准值影子字段。
   - 自动运行态刷新后 `FieldDefinition.measureUnit` 应包含单位分类、固定/可选模式、伴生字段、标准值字段、基准单位分类、基准单位、换算模式和单位必填约束；手动刷新只作为运维兜底入口。

7. 动态保存验收。
   - 创建 `quantity=2, quantityUnit=pallet` 的记录，应生成 `quantityBase=1152`。
   - 创建 `quantity=2` 且固定单位为 `roll` 的记录，应生成 `quantityBase=60`。
   - 更新主数值或单位时应重新计算标准值。
   - 仅更新主数值时应沿用旧单位。
   - 单位必填时，数量存在但单位为空应拒绝。
   - 单位可选时，缺少单位不执行归一，标准值写空。

## 已有自动化证据

| 验收点                                                       | 测试                                              |
| ------------------------------------------------------------ | ------------------------------------------------- |
| 业务硬换算链式解析、反向换算、上下文优先级、有效期           | `MeasureUnitConversionRuleServiceTest`            |
| 动态保存前归一、默认单位、更新沿用旧值、清空单位、业务硬换算 | `MeasureUnitDynamicRecordMutationCoordinatorTest` |
| 元数据字段配置编译到 `FieldDefinition.measureUnit`           | `PlatformMetadataServiceContractTest`             |
| 静态 `@MeasureUnitField` 编译为统一字段定义契约              | `StaticMeasureUnitFieldDefinitionCompilerTest`    |
| 配置包 `MEASURE_UNIT` 依赖和字段契约门禁                     | `LowCodeModuleHealthServiceTest`                  |

## 管理端产品化缺口

当前后端能力已经覆盖配置对象、运行态消费和治理门禁，但管理端仍需要补齐可用维护面：

| 缺口             | 建议                                                                                                                   |
| ---------------- | ---------------------------------------------------------------------------------------------------------------------- |
| 计量单位分类维护 | 提供共享分类列表、租户公开/平台全局公开区分、启停、基准单位、维度和唯一性提示                                          |
| 单位目录维护     | 在分类下维护单位 code、标题、符号、精度、线性换算参数和排序                                                            |
| 硬换算规则维护   | 提供 from/to 分类与单位选择、factor、scope、模块、上下文对象、有效期和启停                                             |
| 字段配置入口     | 在模块字段配置中提供计量单位开关、固定/可选单位模式、默认单位、伴生字段、标准值字段和换算模式；不引入独立 prepare 入口 |
| 自动字段提示     | 保存主数值字段配置后展示平台自动创建或复用的 `quantityUnit`、`quantityBase`，并展示平台管理属性                        |
| 治理诊断         | 在配置包健康检查或导入 dry-run 前展示缺少单位分类、缺少伴生字段、缺少标准值字段、上下文字段缺失等门禁                  |
| 保存失败诊断     | 将缺单位、缺规则、单位禁用、有效期不匹配等错误转成面向配置人员的可读提示                                               |

## 后续战役候选

1. 管理端 UI 战役：补齐分类、单位、硬换算规则和模块字段计量单位配置页面。
2. 静态写链路战役：若静态业务需要自动标准值归一，再建设统一静态归一门面或写链路扩展。
3. 治理深化战役：配置包导出时自动采集真实 `MEASURE_UNIT` 依赖，降低人工维护 manifest 的风险。
4. 运行诊断战役：统一换算失败诊断模型，给页面、导入和外部写入复用。
