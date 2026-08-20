# 平台封装与业务轻量化专项治理

本文记录 MuYunSpring 近期在 Service Ability、Web 交付 DSL 和前端动态渲染三个层面的专项治理顺序。

专项目标不是继续增加平台概念，而是把已经验证过的公共机制收敛为稳定内核：平台可以承担复杂度，但业务模块只声明能力、领域不变量、交付投影和必要的个性化扩展，不接触公共运行时编排。

本文只列最高优先级和中等推荐优先级。低优先级体验优化、假设性插件市场、完整多 UI adapter、微前端和当前没有真实业务触发的扩展不进入本专项。

## 当前判断

现有路线和主要依赖方向正确：

1. 静态 Service 通过 Ability 组合平台能力，动态实体通过元数据接入相同能力语义。
2. 静态 UI DSL 与动态已发布 UI 都会进入来源无关的 `ResolvedModuleUiDescriptor`。
3. 应用管理、模块管理、岗位管理已经能由同一个标准页面宿主交付；模块管理的前端工作视图通过受控 enhancement 注入，没有让后端指定 Vue 组件。
4. 业务模块已经明显轻于平台运行时，岗位管理可作为普通静态业务接入的基准样板。

当前主要缺口不是业务代码仍然过重，而是平台公共能力继续增长时，执行事实还分散在若干中心类、运行时分支和来源专属命名中。现阶段已经完成较多“统一概念和协议”的工作，下一阶段需要推进“统一编译结果、执行计划和能力实现单元”。

## 治理原则

1. **先保证运行正确，再治理内部结构。** 控件值形态、页面上下文和变更约束等可能影响数据正确性的契约优先收口。
2. **静态链路优雅优先。** 不要求静态 Java 业务绕入动态 Map 运行时；统一发生在能力语义、编译模型、执行计划和契约测试层。
3. **Definition 只负责声明。** 请求运行时消费 Descriptor 或 Execution Plan，不重新解释静态 DSL 或动态配置源。
4. **平台复杂度必须内聚。** 平台可以有复杂实现，但应由小型稳定协作者组成，不能持续堆入单个 Host、Service 或 Web interface。
5. **业务扩展必须有类型和边界。** Service policy、Web projection policy、前端 enhancement 和字段 renderer 都要有冲突规则、失败策略和测试契约。
6. **不建设任意代码插件总线。** 本专项的“插拔”是平台内部强类型能力注册和业务受控贡献，不是从元数据加载任意后端代码或前端组件。

## 实施顺序

| 顺序 | 优先级 | 治理单元 | 核心结果 |
| --- | --- | --- | --- |
| 1 | 最高 | 字段 UI 控件执行闭环 | 配置可声明的控件一定能被正确渲染、校验和序列化 |
| 2 | 最高 | 来源无关的标准页面宿主 | 同一 Descriptor 在静态和动态模块中得到相同页面语义 |
| 3 | 最高 | 服务端模块执行计划 | Web 请求不再重新解释 DSL，查询和变更约束只有一个事实源 |
| 4 | 中等推荐 | 标准能力实现单元注册 | 新增能力不再横向修改多个中心 switch |
| 5 | 中等推荐 | 平台大运行时职责拆分 | 保留轻量门面，内部按稳定职责演进 |
| 6 | 中等推荐 | 静态业务开发体验收口 | 普通业务接入更少样板、更强校验、更易发现正确扩展点 |

后一个治理单元可以做准备性设计，但不应绕过前置契约直接展开大规模重构。

## 当前实施阶段

本文定义的是完整目标，不应把一次 PR 中的结构性收口表述为全部完成。当前阶段优先完成字段控件值语义、来源无关页面契约、Execution Plan 和 capability 的可执行事实收口，并将已验证的 Query、Mutation、Action、Relation 协作者从动态门面中拆出。

以下仍属于后续阶段验收，不能因已有 wrapper 或文件拆分而视为完成：

1. `ModulePageHost` 继续拆出 template renderer 与剩余页面编排，确保 renderer 不管理 HTTP、权限或页面启动。
2. `DynamicRecordActionRuntime` 与 `DynamicRecordRelationRuntime` 按实际协作需求收窄为 gateway，逐步消除对完整 `DynamicRecordService` 门面的回持。
3. 仅在上述边界和独立契约测试完成后，才可将“平台大运行时职责拆分”标记为完成。

## 最高优先级 1：字段 UI 控件执行闭环

### 要解决的问题

平台字段 UI 控件目录已经可以声明文本、数字、布尔、单选、多选、日期、日期时间、JSON、区间和复合值等控件；前端标准表单运行器当前只原生处理其中一部分。未知 `uiType` 如果退化成普通输入框，会使“配置成功”和“运行正确”脱节，集合或复合值还可能产生错误 payload。

字段 UI 控件是跨配置、Descriptor、渲染、校验和保存的完整协议，不能只把 alias 当成一个样式提示字符串。

### 目标边界

1. 每个可发布控件都具有稳定的 renderer 类型、值类型、值形态、属性、复合值 binding、序列化和校验契约。
2. 静态 UI 声明和动态 UI 配置编译到同一种 resolved field-control descriptor。
3. 前端通过平台字段 renderer registry 选择受控组件，不在页面 Host 中逐项硬编码控件分支。
4. 不支持的控件在发布或页面 bootstrap 阶段给出明确诊断，不静默回退为文本输入框。
5. `text` 是显式控件类型；只有明确声明为文本时才使用普通输入框。

### 实施步骤

1. 盘点字段 UI 控件目录、动态页面 bootstrap、`ResolvedViewFieldDescriptor` 和前端表单模型，形成唯一支持矩阵。
2. 定义来源无关的 resolved field-control 契约，至少包含 `alias`、稳定 renderer 类型、value shape、properties 和 bindings；不把数据库字段类型或后端类名暴露给前端。
3. 先为现有真实页面使用的 `text`、`textarea`、数字、开关、单选、引用选择、文件引用补齐 registry，并保持现有页面兼容。
4. 再成套接入 `multi_select`、日期、日期时间、JSON 和区间控件；每次接入同时完成输入、只读展示、查询表单、payload 序列化和契约测试。
5. 在动态 UI 发布和静态模块启动编译中增加支持性校验。缺少 renderer、值形态不匹配或 composite binding 不完整时直接失败。
6. 删除未知控件到普通输入框的隐式回退，只保留明确的兼容迁移诊断。

### 验收标准

1. 控件目录中的每个可启用控件都有前后端支持证据，或被明确标记为不可发布。
2. `multi_select` 始终提交集合，区间控件始终提交受支持的数组或结构化对象，不产生字符串退化。
3. 同一种控件在静态和动态表单中使用同一个 renderer 和序列化器。
4. 新增控件必须同时提交 descriptor、renderer、序列化、校验和测试，不能只新增配置记录。

### 明确不做

1. 不允许后端 Descriptor 携带 Vue 组件名或任意前端模块路径。
2. 不允许动态配置注册任意脚本作为 renderer。
3. 不在本阶段建设第三方控件市场。

## 最高优先级 2：来源无关的标准页面宿主

### 要解决的问题

标准页面宿主已经同时承载静态和动态模块，但仍保留 `DynamicModuleHost`、`StaticModuleCrudClient` 等来源专属公共名称，并在通用列表和树页面中存在“动态记录”“标签”“根标签”等固定文案。引用字段的选择面也不应由 Host 一律猜测为树形选择。

这些问题会让来源无关的 Descriptor 在前端再次被来源名称或页面猜测污染。

### 目标边界

1. 页面宿主只根据 page template、traits、capabilities 和 resolved field facts 工作，不根据 `moduleKind` 选择两套 CRUD 页面内核。
2. 资源名称、空状态、搜索提示、根节点标题和创建标题全部来自 Descriptor 或统一的资源文案默认规则。
3. Reference Picker 的 `LIST`、`TREE` 或 `AUTO` 选择策略由服务端编译后的引用选择契约给出，Host 不自行假设目标模块具备树能力。
4. `ModulePageEnhancement` 继续作为前端拥有的个性化入口；后端只提供业务事实和动作契约。
5. 公共 API 逐步采用来源中性名称，旧名称只作为兼容 alias。

### 实施步骤

1. 先修复通用 Host 中所有“动态记录”“标签”等来源或领域硬编码，优先消费现有 `emptyDescription`、title、record label 和 tree-root title。
2. 为应用管理、模块管理、岗位管理建立标准宿主契约测试，覆盖平铺、树、两级导航、表单、引用选择和启停动作。
3. 把引用选择方式编译进来源无关 Descriptor；目标模块具有 Tree 能力时可以选择树，否则使用列表，不由页面临时试探 URL。
4. 引入 `ModulePageHost`、`ModulePageDescriptor`、`ModuleCrudClient` 等中性公共名称，并为旧名称保留有删除条件的兼容导出。
5. 移除或降级未被标准工作台使用的旧动态页面 skeleton，确保只有一条正式标准页面运行路线。

### 验收标准

1. 仅观察标准页面文案、请求路径和组件树，无法判断模块来自静态 Java 还是动态元数据。
2. 模块管理显示“模块/根模块”，岗位管理显示“岗位”，不出现无关的“标签”或“动态记录”。
3. 非树引用目标不会收到树接口请求，树引用目标仍可使用同一 Record Picker 门面。
4. 后端不能通过 Descriptor 选择任意 Vue 组件；业务个性 UI 只能从已登记 enhancement 或 renderer 进入。

### 明确不做

1. 不为静态模块和动态模块分别维护 PageHost。
2. 不把复杂业务专属页面强行改造成通用模板。
3. 不以删除兼容命名为由进行一次性破坏性前端 API 重写。

## 最高优先级 3：服务端模块执行计划

### 要解决的问题

静态 UI DSL 已能编译为 Descriptor，但部分标准 Web 请求仍会重新调用 `moduleUiDefinition()`，从原始页面定义中提取导航绑定、查询条件和 mutation constraint。静态读投影还存在 Controller 可选注入和静默回退。

这会形成两个运行事实源：前端消费已编译 Descriptor，Web 请求却重新解释 Definition。随着动态 UI、关联投影和页面上下文继续增长，两条链路容易出现行为偏差。

### 目标边界

建立来源无关的 `ModuleExecutionPlan` 概念。静态声明和动态已发布快照都先编译，再分别交付：

```text
静态 Service / Web DSL        动态元数据 / 已发布 UI
             \              /
              Module Compiler
               /            \
ResolvedModuleUiDescriptor   ModuleExecutionPlan
        -> 前端 Host          -> 标准 Web Runtime
```

执行计划至少承担：

1. page-context bindings 和服务端 session value。
2. 查询 schema、列表读投影和允许的筛选/排序字段。
3. create/update mutation constraints。
4. 动作执行策略、数据范围和租户范围适配。
5. 引用选择、树查询和导航源运行约束。
6. 静态定义版本、动态运行态版本和 UI 配置版本对应的缓存失效键。

### 实施步骤

1. 从现有 Descriptor compiler、read projection planner 和 page-context 逻辑中抽取服务端执行所需的最小 plan，不把前端展示字段直接当成 SQL 计划。
2. 在静态模块启动扫描和动态配置发布/refresh 时生成并校验 plan；失败时阻止对应定义进入可执行目录。
3. 提供按 `moduleAlias + view/uiConfig + action` 解析执行计划的标准 catalog，运行时不再访问原始 DSL。
4. 让 `CrudWeb` 和 `StaticQueryViewWeb` 保持业务门面，但把查询、上下文绑定、mutation constraint 和读投影委托给标准 Web Runtime。
5. 移除 `StaticRecordReadProjectionService` 的 Controller 可选注入方式。模块声明了需要投影的字段却缺少执行器时应启动失败，不允许静默降级为另一套返回语义。
6. 按应用管理、岗位管理、模块管理的顺序迁移：先验证简单平铺页，再验证多级导航，最后验证树作用域和自举模块。
7. 执行计划稳定后，再评估把完整 CRUD 纳入现有 `PlatformOperationDefinition → WebEndpointProjection → RegisteredWebEndpoint` 链路；不在 plan 尚未稳定时直接重写所有 Controller。

### 验收标准

1. 标准查询、创建和更新请求不再调用业务 Controller 的 `moduleUiDefinition()`。
2. 页面展示的作用域、列表查询条件和写入约束来自同一份已编译事实。
3. 静态和动态模块对同一种 page-context binding 具有相同服务端行为和错误契约。
4. 简单业务 Controller 只声明模块、标准 Web 能力、UI Definition 和确有必要的 typed Web policy。
5. 读投影缺失、字段越界、绑定冲突和 mutation constraint 不可执行都在启动或发布阶段失败。

### 明确不做

1. Execution Plan 不携带物理 SQL 字符串；SQL 仍由 query/projection planner 生成。
2. 不把 HTTP 请求对象传入 Service 领域层。
3. 不因统一 Web Runtime 而取消业务自定义 Controller endpoint；自定义 endpoint 仍需声明稳定动作和授权语义。

## 中等推荐 1：标准能力实现单元注册

### 要解决的问题

Service Ability 已经提供较轻的业务组合方式，但新增一种平台标准能力仍可能需要同时修改能力枚举、静态能力编译、动态能力推导、动态运行态、动作执行、Web endpoint、OpenAPI 和前端 capability 分支。

这说明“业务选择能力”已经可插拔，而“平台实现一种能力”仍较依赖中心 switch。

### 目标边界

建立平台内部强类型的 capability module 注册机制。一个标准能力实现单元可以分别贡献：

1. 静态 Service/模型检测器。
2. 动态元数据字段要求和定义归一器。
3. 标准 Operation Definition。
4. 来源适配后的运行态 handler 或共享语义 kernel。
5. Web endpoint projection 和执行器。
6. Descriptor/OpenAPI capability facts。
7. 该能力的动静契约测试。

注册机制负责组合和冲突检测，不要求所有能力实现一个巨型万能接口；不同 facet 可以保持小型、强类型契约。

### 实施步骤

1. 先统计新增一个现有能力需要修改的中心位置，确定最小 facet 集合，不预先设计完整插件框架。
2. 选择 `ENABLE` 作为首个迁移样板，验证静态检测、动态开关、动作和 Web 投影的闭环。
3. 依次迁移 `SORT`、`TREE`、`RECYCLE_BIN`；Tree 在 Sort 之后迁移，保持能力依赖关系显式。
4. Reference、Child Relation、Data Scope 等跨读写或安全边界较重的能力，只有前述样板稳定后再进入。
5. 每迁移一个能力就删除对应中心 switch 分支，避免新旧注册方式长期并存。

### 验收标准

1. 新增标准能力时，主要变更集中在一个 capability feature 范围及其契约测试中。
2. 静态接口声明和动态元数据声明最终调用同一语义 kernel，或由同一组 golden contract 保证行为一致。
3. 能力依赖、冲突和缺失字段在编译阶段可诊断。
4. 业务 Service 仍只需实现 Ability 或领域贡献接口，不接触 capability registry。

## 中等推荐 2：平台大运行时职责拆分

### 要解决的问题

标准页面 Host、动态记录门面、动态实体服务和 Web 默认接口持续吸收新能力。平台复杂度虽然被业务隐藏，但单个协调器同时管理过多状态和执行阶段，会让公共能力的修改风险不断扩大。

治理目标不是按行数拆文件，而是让稳定职责可以独立测试、替换和演进。

### 实施步骤

1. 保留现有公共 Facade 和调用方式，先用 characterization test 锁定行为。
2. 前端 Host 按页面启动、导航运行态、列表会话、详情/编辑会话、动作运行态和 template renderer 拆分；平铺、列表详情、树管理共享同一个 record surface。
3. `DynamicRecordService` 按 Query、Mutation、Action、Relation、Security/Data Scope 拆分内部协作者；Facade 继续负责按模块和实体定位运行态。
4. `DynamicEntityService` 将 Tree、Sort、Reference 等能力委托给 capability runtime，保留实体级能力组合和 DAO 边界。
5. `CrudWeb` 将查询、mutation、form-schema 兼容和 page-context 处理迁到独立 Web runtime；interface 只保留轻量开发门面。
6. 每次只迁移一个稳定职责，不同时改公共协议、页面视觉和业务行为。

### 验收标准

1. 公共 Facade 保持兼容，业务模块不因平台内部拆分增加接入代码。
2. 模板 renderer 不直接管理 HTTP、权限和全局页面启动；运行态协作者不直接拥有业务 Vue 组件。
3. 动态 Query、Mutation 和 Action 可以独立进行权限、事务和失败契约测试。
4. 公共能力新增时有明确归属，不再默认进入最大协调器。

## 中等推荐 3：静态业务开发体验收口

### 要解决的问题

当前普通业务模块已经可以通过模型、DAO、Service Ability、Controller 和 UI DSL 接入平台，但字段名、binding key 和部分扩展点仍依赖字符串及经验。开发者容易复制已有 Controller 的基础设施细节，而不是只声明业务事实。

### 实施步骤

1. 将岗位管理固定为普通租户业务 golden sample，将应用管理固定为系统级平铺样板，将模块管理固定为树作用域和平台自举样板。
2. 为 UI 字段、navigator key、binding target 和常用 view code 提供轻量类型化门面或启动期强校验；不引入会显著增加构建复杂度的代码生成器，除非真实模块证明收益。
3. 为标准静态模块提供接入清单或脚手架，只生成模型、DAO、Service、Controller 和最小测试骨架，不生成大段不可维护业务代码。
4. 把平台投影服务、Execution Plan catalog 和运行态协作者隐藏在 Starter/adapter 内，不要求业务 Controller 手工注入。
5. 增加架构适配测试：业务领域模块不得依赖其他领域的 `*-web`，后端 Descriptor 不得出现前端组件名，标准 Host 不得出现“动态记录”等来源措辞。
6. 为兼容 API 标明替代入口和删除条件，避免 `Dynamic*`、`Static*` 历史命名长期成为新的开发默认。

### 静态接入检查清单

普通静态模块以岗位管理、应用管理和模块管理为样板：Service 只声明领域事实与 Ability，Controller 只声明模块交付和 typed Web policy；不得注入 Execution Plan catalog 或运行时内部协作者。

`LegacyStaticReadProjectionCompatibility` 当前仅由 `EmployeeWebController` 临时使用。删除条件是该 Controller 改由 `StandardModuleWebRuntime` 消费已编译 plan，并以启动期 plan 缺失失败和请求期不重解 DSL 的契约测试替代兼容路径；满足后同时删除 marker、目录标记字段和对应 fallback。

### 验收标准

1. 普通模块的 Service 只包含领域归一化、领域校验、引用保护和个性动作，不编排租户、权限、缓存、审计或 HTTP 生命周期。
2. 普通 Controller 只包含模块交付声明、UI DSL 和少量 typed Web policy，不手写标准 CRUD、树、启停和投影流程。
3. 个性前端逻辑通过 enhancement、workspace view、稳定 slot 或 field renderer 注入，不复制标准页面 Host。
4. 新开发者可从三个 golden sample 判断自己的逻辑应放在 Service、Web policy、UI Definition 还是前端 enhancement。

## 不纳入本专项

1. 把静态业务全部改造成动态记录，或让静态 Service 通过 Map 运行时访问数据。
2. 建设一个覆盖所有业务流程的万能页面 DSL。
3. 允许后端配置任意前端组件、脚本或模块地址。
4. 因拆分大类而同步重做权限、工作流、导入导出和实时通信。
5. 在没有第二种真实 UI adapter 前抽象完整多 adapter 插件协议。
6. 仅为了减少文件行数进行没有职责收益的机械拆分。

## 专项完成口径

完成本专项不以新增类数量衡量，而以以下结果为准：

1. 平台配置允许声明的字段控件都能正确执行，或在发布前被明确拒绝。
2. 静态和动态模块共享同一个来源无关页面宿主，页面文案和行为不泄漏定义来源。
3. Web 请求消费已编译 Execution Plan，不重新解释业务 DSL。
4. 标准能力可以按实现单元演进，新增能力不需要在多个中心类复制分支。
5. 平台大运行时由可独立测试的协作者组成，同时保持业务接入 API 轻量。
6. 应用管理、模块管理、岗位管理分别成为系统平铺、平台树和普通租户业务的长期契约样板。
