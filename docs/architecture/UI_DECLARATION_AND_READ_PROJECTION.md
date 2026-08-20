# UI 声明与读投影设计

本文定义静态模块声明 UI、动态配置后续对齐、Web 返回和数据读取投影的总体边界。

本文是当前阶段的设计草案，不是不可变规范。后续实际推进中，如果实现验证、动态表单接入或前端运行器协作暴露出更好的方案，可以随时提出讨论，也可以在边界更清晰、收益明确时直接改进本文和实现。调整时应继续守住动静一体、service 不承载 UI schema、Web 不暴露物理列、SQL 不直接消费 UI 配置这些核心边界。

当前项目尚未上线，动态表单从 UI 配置到底层执行的链路也尚未形成稳定产品事实。因此 UI 声明设计优先从静态链路切入：先让 Java 静态模块用低成本、边界清晰的方式声明页面意图，再让动态 UI 配置编译到同一套运行结果，最终达成动静一体。后续推进应优先收口目标契约，不把来源态 UI definition、旧表单 schema 或完整实体查询响应保留为正式前端协议。

静态 UI 声明是为了沉淀统一 descriptor、读模型和投影契约。页面交付可以从静态样板先行，但静态和动态最终只在来源层不同，运行时应共用 descriptor、读投影、动作和前端运行器协议。

## 目标

1. 静态 Service 不再承载表单 schema 声明，避免业务行为、平台能力和页面配置混在同一个 service 类型上。
2. 静态 UI 声明可以接驳 service 已知事实，例如模块别名、模型类型、Ability 组合和字段注解。
3. 静态声明和动态配置最终编译成同一套 resolved descriptor，前端不感知来源差异。
4. UI 配置可以影响列表等读场景的字段投影，但不能直接生成 SQL。
5. SQL 只消费后端内部读计划，所有物理列、字段能力、权限、租户和数据范围仍由平台后端校验和编译。

## 后续治理路线

当前没有上线兼容成本，后续推进按“先立契约，再接真实链路，再扩展场景”的生产化顺序执行。SQL 列投影不是第一优先级；Web 契约、安全边界和字段事实先稳定，后续 SQL 裁剪只作为性能优化接入。

当前静态样板状态：`iam.employee` 已验证普通列表、声明表单、组织 scope、部门引用选择、统一保存和记录动作流；`iam.department` 已验证树形业务、机构 scope、父子选择、声明表单、统一保存和记录动作流；`platform.dictionary` 已验证应用 scope 下的类目树、类目 scope 下的条目树、父子选择、主资源表单和子资源表单；`iam.position` 已验证非字典子资源表单可通过父模块 descriptor 和 `RecordFormFields` 复用同一套字段运行器。子资源表单样板已沉淀为稳定接入契约：子资源 view 归属父模块 descriptor，view code 由 resource 命名门面生成，字段按 relationCode 关联子资源实体事实并在编译期失败。动态发布快照已接入模块 runtime context：发布快照先归一为 `ModuleUiDefinition`，再编译为 `ResolvedModuleUiDescriptor`，动态模块 host 可复用 `RecordQueryListPanel` 和 `RecordFormFields` 展示列表与只读表单。后续不继续横向铺更多静态页面，优先补动态保存 envelope、SQL 列投影和字段级授权治理。

### 阶段 1：协议冻结

1. `ResolvedModuleUiDescriptor` 成为唯一前端运行协议，`ModuleUiDefinition` 只存在于后端来源层。
2. `/form/schema` 从静态模块 UI 主线退出；短期保留时也只能由 resolved form view 派生。
3. descriptor 必须明确版本、模块身份、视图、字段、动作、端点、字段能力和视图能力。
4. 字段引用统一支持主表字段、关系字段和动态 `fieldId`；虚拟字段作为后续字段能力治理进入。
5. 字段事实编译应覆盖 Java model、注解、Ability、平台标准字段、`@OptionField`、引用、字典、枚举、单位、金额和字段保护。
6. `visible`、`required`、`readOnly` 要区分数据契约、页面声明、权限态和动作态来源；UI 声明不能绕过数据契约底线。

验收口径：

1. 前端不读取 `uiDefinition` 兜底，静态页面入口只消费 `uiDescriptor`。
2. descriptor 不包含物理列、SQL 片段或来源态配置。
3. 字段缺失和子资源 relation 缺失在编译期明确失败；字段能力冲突、只读必填无默认值等更细约束进入后续字段能力治理。

### 阶段 2：读写链路生产化

1. `ResolvedModuleReadModel` 表达模块级字段事实，`RecordReadProjection` 表达 view/request 级输出计划。
2. `/query` 返回源无关 record map 或平台 record envelope，不继续默认外泄完整 Java 实体。
3. 投影必须叠加动作授权、数据范围、字段可读策略、字段保护、脱敏、引用标题、字典标题和虚拟字段后处理。
4. 查询、排序、展示、导出分开校验；可展示字段不会自动获得可查询、可排序或可导出能力。
5. 系统字段输出策略必须稳定，明确 `id`、`version`、`tenantId`、`enabled`、`title` 等字段哪些进入响应、哪些只供内部计划使用。
6. 保存链路继续回到标准 service 或动态记录服务，保留乐观锁、数据契约校验、动作权限和审计边界。

验收口径：

1. `/iam.employee/query` 和 `/iam.department/query` 只返回列表视图字段和必要平台字段。
2. 输出裁剪发生在权限、租户和数据范围链路之后。
3. 字段保护、引用标题、字典标题和虚拟字段至少各有一类样板或 contract test 覆盖。

### 阶段 3：标准运行器生产化

1. list、form、detail 和 action 运行器作为标准模块页面能力建设，不作为 demo runner。
2. 运行器覆盖加载、空态、错误、无权限、保存冲突、字段校验、乐观锁、删除确认、启停和批量动作等生产交互。
3. 表单和记录动作流应由平台执行器统一承接重复提交保护、权限提示、校验提示、loading、成功反馈和异常反馈；业务页面只提供上下文校验、payload 归一化、具体动作选择和执行后的页面状态同步。
4. 表单生成支持引用、枚举、字典、boolean、日期、时间、数字、文本、只读、隐藏和默认值。
5. 业务页面只做组合编排，例如组织树筛选职员列表，不重复声明右侧列表列、字段能力和表单事实。
6. 前端测试应覆盖运行器行为，避免长期依赖字符串扫描式 contract test 证明生产契约。

验收口径：

1. 普通 CRUD、树/排序、引用/字典/枚举三类静态样板均可通过标准运行器工作；当前已完成职员、部门、字典和岗位四个样板，子资源声明能力已从字典迁移到非字典场景。
2. 运行器在无权限、校验失败、乐观锁冲突和后端错误时有稳定 UI 行为。
3. 业务页面新增字段时不需要重复维护列表列和表单字段事实。

### 阶段 4：动态归一与治理

1. 动态 UI 配置发布快照不是前端协议，必须先转换为 `ModuleUiDefinition` 或等价源无关定义，再编译为 `ResolvedModuleUiDescriptor`。
2. 动态字段 ID、字段名、关系和 viewCode 已有最小稳定映射；子表、虚拟字段和发布版本继续进入治理链路。
3. 动态模块和静态模块共用 descriptor、读投影、动作和前端运行器协议；当前动态 runtime context 已返回共用 descriptor，动态 host 已复用列表运行器和表单字段运行器。
4. descriptor 应带版本号、来源版本、编译时间和校验结果，支持 preview/dry-run 编译。
5. 治理接口应能检查字段缺失、引用失效、字典缺失、动作不可达、权限配置缺口和发布快照不可用。
6. 编译结果缓存、失效策略、配置发布、运行态切换和审计留痕应有明确边界。

验收口径：

1. 至少一个动态模块通过同一运行器展示列表和表单；当前已完成最小只读展示链路，保存 envelope 和动作态表单仍是后续工作。
2. 动态 UI 配置字段、静态字段声明和 resolved descriptor 有互相映射测试；当前已覆盖动态快照到 `ModuleUiDefinition` 的最小适配、字段 ID、relation、viewCode 身份边界和 runtime context 返回 descriptor。
3. preview/dry-run、健康检查、缓存失效和版本切换有 API 或 contract test 证据。

## 核心分层

UI 链路按三个阶段组织：

```text
Definition -> Descriptor -> Plan
```

| 阶段         | 含义                                                             | 消费方                     |
| ------------ | ---------------------------------------------------------------- | -------------------------- |
| `Definition` | 源码声明或配置态事实，例如静态模块 UI 声明、后续动态 UI 配置     | 平台编译器                 |
| `Descriptor` | 编译后的对外 resolved 结果，例如模块、字段、视图和动作的前端协议 | Web 和前端运行器           |
| `Plan`       | 后端内部执行计划，例如读投影、查询计划和后处理任务               | DAO、SQL mapper 和输出转换 |

这三个阶段不能互相替代。`Definition` 不直接给前端，`Descriptor` 不携带物理列，`Plan` 不暴露给前端。当前实现如果临时同时返回 `uiDefinition` 和 `uiDescriptor`，只能作为收口前的短期脚手架；正式前端协议只消费 `uiDescriptor`。

`Definition` 阶段内部再区分来源定义和源无关 UI 定义。静态 DSL 和动态配置都不直接编译到对外 descriptor，而是先归一到同一套 `ModuleUiDefinition`。

```text
StaticModuleDefinition
  -> ModuleUiDefinition
  -> ResolvedModuleUiDescriptor
  -> RecordReadProjection / QueryPlan
  -> DAO / SQL mapper

动态 UI 配置
  -> ModuleUiDefinition
  -> ResolvedModuleUiDescriptor
  -> RecordReadProjection / QueryPlan
  -> DAO / SQL mapper
```

`ModuleUiDefinition` 是静态和动态共同的 UI 声明目标。静态 `StaticModuleDefinition.forService(...)` 是构造它的一种源码 DSL；动态 UI 配置发布后也应先转换为它，再进入统一编译链路。

标准“范围选择 + 右侧查询列表”也属于这个来源无关声明，但它归属具体 LIST view/config，而非整个模块。`scopedListWorkspace` 声明 scope 模块、消费者的单值引用字段和外部查询 key；编译期必须证明该字段引用 scope 模块。运行器按当前页面的 UI config 选择对应 workspace，并由静态、动态 CRUD 将该外部 key 直接编译为范围字段过滤；查询模板只能叠加其他过滤，不承载范围工作区的正确性。scope 模块有树能力时，左栏自动使用树浏览器；否则使用微列表，两种形态共享选择、取消选择、范围预填和右侧标准 CRUD。默认不显示次标题，重复点击已选项会取消范围并恢复全量列表；默认 `ALLOW_UNSCOPED`，只有显式声明 `REQUIRE_SCOPE` 才会禁止未选范围时的新建。scope 只是页面入口约束；真正的数据归属仍由模型字段必填、引用校验和数据权限在统一 CRUD 链路中保证。

静态 LIST view 可用 `manageableScopedTree` 显式允许在范围栏内通过目标模块的标准 CRUD、动作权限和
乐观锁维护树节点；未声明时范围栏保持只读选择。动态页面配置当前尚无对应来源字段，适配时固定为关闭，
这是阶段限制，不表示动态范围工作区已经具备内嵌维护配置能力。

## 静态 UI 声明

静态 UI 声明归属模块定义，不归属 model，也不归属 service。

```text
Model                 字段事实、字段注解和稳定数据契约
Service               业务能力、业务行为、生命周期和运行事实
StaticModuleDefinition 模块身份、动作、页面和视图声明
```

推荐形态：

```java
StaticModuleDefinition.forService(EmployeeService.class)
        .formView(form -> form
                .title("职员档案")
                .field("organizationId", field -> field.required())
                .field("departmentId", field -> field.required())
                .field("employeeNo", field -> field.required())
                .field("title", field -> field.label("职员姓名").required())
                .field("gender")
                .field("mobile")
                .field("email"))
        .listView(list -> list
                .field("employeeNo")
                .field("title")
                .field("organizationId")
                .field("departmentId")
                .field("enabled"))
        .build();
```

这里 `forService(EmployeeService.class)` 是静态 UI 声明接驳 service 的入口。它允许编译器从 service bean 或 service 类型读取模块运行事实，但不要求 service 实现 UI ability。

不推荐继续使用以下形态：

```java
class EmployeeService implements FormAbility<Employee> {
}
```

`FormAbility` 会把 UI schema 暴露能力挂到 service 公共能力面上，长期会让 service 同时承载业务行为、数据能力、页面配置和字段展示。

## 声明内容

第一阶段支持主资源的扁平视图声明，以及父模块下子资源贡献的扁平 form view。模型上应保留多视图、子资源和动态字段接入所需的稳定锚点，静态 DSL 可以提供最短写法，由编译器补齐默认值。

视图身份至少包含：

| 字段         | 含义                                                |
| ------------ | --------------------------------------------------- |
| `viewCode`   | 模块内视图编码，例如 `default_list`、`default_form` |
| `viewKind`   | 视图类型，例如 `LIST`、`FORM`、`DETAIL`             |
| `clientType` | 客户端类型，第一阶段可默认为 Web                    |

第一阶段 `clientType` 只作为未来锚点，不参与编译分支。同一套 `ResolvedModuleUiDescriptor` 协议不能因为客户端不同而生成两套语义模型。

子资源视图继续属于父模块 descriptor，不注册成独立伪模块。例如字典项是 `platform.dictionary_category` 下的 `item` 子资源，动作由 `@PlatformStaticActionContribution(resource = "item")` 贡献，表单 view 使用 `item_default_form` 进入父模块的 `ResolvedModuleUiDescriptor`。这样权限动作、运行入口和页面上下文仍以父模块为边界，子资源字段事实通过 `relationCode=item` 保留定位。

字段 UI 声明只表达页面意图：

| 字段                        | 含义                                           |
| --------------------------- | ---------------------------------------------- |
| `fieldRef`                  | 字段引用，静态主表可简写为业务字段名           |
| `label`                     | 页面标题覆盖                                   |
| `visible`                   | 当前视图是否展示，第一阶段只支持常量规则       |
| `required`                  | 当前视图的输入必填语义，第一阶段只支持常量规则 |
| `readOnly`                  | 当前视图的输入只读语义，第一阶段只支持常量规则 |
| `uiType`                    | 平台 UI 类型提示，不绑定具体前端组件库         |
| `width` / `align` / `fixed` | 列表展示的轻量提示                             |

当前已落地的 `uiType` 先保持小集合：`enabledStatus` 表达启停布尔控件，`booleanStatus` 表达带显式
true/false 标签和语义色的业务布尔展示，`recordPicker` 表达引用选择控件。业务布尔值保留 unknown 状态，
不能复用启停字段“非 false 即启用”的默认语义。动态 UI 配置当前还不能声明 `booleanStatus` presentation，
遇到该控件类型会在适配期失败，不静默降级。`booleanStatus` 是展示类型；在 FORM 中必须显式 `.readOnly()`，
不能伪装为可编辑布尔控件。需要编辑业务布尔值时，声明通用 `switch` 或在后续建立其独立的可编辑语义。
`recordPicker` 只声明字段应由引用选择控件承接，具体候选来源、
作用域、标题函数和刷新 key 由页面组合层提供，避免 descriptor 绑定前端运行态对象。

`fieldRef` 是源无关字段锚点，后续可承载动态字段和子关系定位：

```text
ViewFieldRef
  relationCode/null
  fieldName
  fieldId optional
```

静态主表声明可继续使用 `.field("title")` 这类短写法；编译器将其归一为 `relationCode=null, fieldName=title`。

子资源字段声明使用 relation 字段引用，例如 `.field("item", "code")`。编译器按 `relationCode` 校验字段存在于子资源实体事实中；relation 或字段写错必须在后端 descriptor 编译阶段明确失败。前端表单绑定仍按 `fieldName` 写入当前页面的 draft，`relationCode` 只承担 descriptor 和读模型定位，不要求业务页暴露后端实体结构。

`visible`、`required` 和 `readOnly` 在定义模型上应按规则对象表达：

```text
UiRule<T>
```

第一阶段只实现 `constant(true/false)`。后续动作态、权限态、表达式联动和客户端差异可以扩展规则来源，但不能把规则直接下沉为 SQL 或绕过保存校验。

字段 UI 声明不表达以下事实：

1. 数据库列名、schema 名和表名。
2. 字段真实类型和 Java 类型。
3. 字典、枚举、引用、单位、金额、字段保护和公式等字段能力。
4. 租户、权限、数据范围和动作授权。
5. 查询条件、排序 SQL、join 或任意 SQL 片段。
6. 复杂布局、联动、表达式可见性、子表和客户端差异。

这些事实应从 model、注解、Ability、动态元数据、字段配置或后续页面配置专题中编译得到。静态 DSL 和动态配置都应先归一为 `ModuleUiDefinition`，再与字段事实合并。

编译后的 `ResolvedViewFieldDescriptor.valueType` 是来源无关的字段事实，不是查询能力声明。静态模块从实体模型和
`StandardEntity` 标准字段推导，动态模块从运行态字段 descriptor 推导。列表渲染应优先消费该字段事实；旧模块
缺少该字段时才兼容回退到 query schema。`QueryDescriptor` 继续只决定可过滤、可排序等查询能力，不能成为日期时间
是否正确展示的前置条件。

`valuePresentation` 是与 `valueType` 正交的受控展示语义，不改变 API、存储、查询或排序使用的原始值。文件字节数
使用 `FILE_SIZE`：字段仍为 `LONG` bytes，静态 DSL 通过 `.fileSize()` 声明，动态配置的 `file_size` 控件别名在
适配期归一为同一 resolved presentation。平台统一按 1024 进位展示 B/KB/MB/GB、最多一位小数、空值显示 `-`，
并以 tooltip 保留精确 bytes。该语义不包含上传、MIME、配额、存储或文件生命周期能力；FORM 中只能用于只读字段。
业务模块不得把格式化文本写回数据值，也不应自行实现另一套文件大小格式。

## Service 接入

静态 UI 编译器通过 service 接驳运行事实：

```text
StaticModuleDefinition
  + service bean / service class
  + modelClass from CrudAbility
  + moduleAlias from service
  + Ability interfaces
  + Java model annotations
  + view definitions
  -> ModuleUiDefinition
  -> ResolvedModuleUiDescriptor
```

可接入的 service 事实包括：

1. `moduleAlias`。
2. `modelClass`。
3. `CrudAbility`、`TreeAbility`、`SortAbility`、`ReferenceAbility` 等能力组合。
4. 模型字段、平台标准字段和能力字段。
5. `@OptionField`、引用、字段保护、单位、金额等字段声明。
6. 后续静态模块动作贡献和权限动作事实。

service 不应因为 UI 声明新增公共 ability。确需拆分声明文件时，可以引入模块级 contributor：

```java
interface StaticModuleUiContributor {
    ModuleUiDefinition moduleUiDefinition();
}
```

contributor 仍然贡献模块 UI 定义，不改变 service 的运行能力面。

静态子资源 controller 可以同时作为 action contributor 和 UI contributor：action contribution 把标准子资源动作合并到目标父模块，UI contribution 把子资源 form view 合并到同一父模块 UI descriptor。扫描器同时合并子资源实体事实，保证 `relationCode` 字段能被编译期校验。子资源不因此获得独立菜单、独立路由或独立模块注册；是否成为独立模块必须由业务边界决定，而不是由页面表单需要倒推。

## 前端表单运行器边界

前端静态业务页不直接遍历 `uiDescriptor.views` 查找表单字段，而是通过 `resolveRecordFormFields(uiDescriptor, viewCode)` 把 resolved form view 转成 `RecordFormFields` 可消费的字段 Map。`viewCode` 默认是 `default_form`；子资源表单显式传入对应 view code，例如 `item_default_form`。子资源默认表单 view code 应由命名门面按 resource 生成，避免后端声明和前端读取各自维护字符串约定。

`RecordFormFields` 只负责根据 descriptor 字段事实和页面 fallback 解析字段状态，并渲染当前已支持的控件类型。页面仍负责提供业务上下文：

1. `recordPicker` 的候选 `ModuleContext`、scope、刷新 key、标题函数和选择约束。
2. 页面合成的只读上下文字段，例如当前选中字典类目标题。
3. 保存 payload 归一化、动作选择和保存后的页面状态同步。
4. 当前页面是否禁用字段、是否隐藏上下文外字段。

这种边界保证表单字段事实来自后端 descriptor，同时不把前端运行态对象、页面树状态或业务临时字段反向塞进 UI 声明。

## 编译与校验

静态 UI 声明在应用启动时注册并编译。请求时返回缓存后的 resolved descriptor，不重复解释源码 DSL。

编译分两步：

```text
来源定义 -> ModuleUiDefinition -> ResolvedModuleUiDescriptor
```

第一步只做来源归一：静态 DSL、动态 UI 配置或后续导入包配置都输出同一套 `ModuleUiDefinition`。第二步合并 service、model、元数据和字段能力事实，生成前端 descriptor 与模块级读模型。

编译职责：

1. 校验 `StaticModuleDefinition` 引用的 service 存在。
2. 校验视图字段存在于模型字段、平台标准字段或能力字段中。
3. 合并字段类型、标题、选项、引用、单位、金额、保护、虚拟字段等字段事实；当前 resolved view field 已交付基础值类型。
4. 校验 UI 声明与字段事实冲突，例如虚拟字段不应声明为普通可输入字段。
5. 生成前端可消费的 resolved 视图结构。
6. 生成后端读计划需要的模块级 `ResolvedModuleReadModel`。当前最小实现只包含逻辑字段事实；字段值类型已在 resolved view field 交付，读模型后续再按 SQL 规划需要合并选项、保护、引用和存储形态等事实。
7. 不把物理列写入对外 descriptor。

`required` 和 `readOnly` 需要区分来源：

| 来源     | 含义                                   |
| -------- | -------------------------------------- |
| 数据契约 | 模型、字段定义或平台能力要求的底线约束 |
| UI 声明  | 当前视图或当前页面场景下的输入语义     |

UI 可以加强输入要求，但不能绕过底层数据契约。隐藏或只读的必填字段必须由默认值、平台托管字段或业务链路填充，否则编译或保存校验应失败。

## Web 返回

Web 主协议应逐步收敛到模块或页面 bootstrap，而不是继续扩展独立 `/form/schema`。

返回对象是对外 resolved descriptor，例如：

```json
{
  "moduleAlias": "iam.employee",
  "mainEntity": {
    "fields": []
  },
  "views": {
    "list": {},
    "form": {}
  },
  "actions": [],
  "endpoints": {}
}
```

前端只消费 resolved 结果，不关心模块来自静态声明还是动态配置。

对外 descriptor 与后端读模型分开。对外 descriptor 只表达前端需要的模块、字段、视图、动作和端点；后端 `ResolvedModuleReadModel` 是模块级、缓存级的已解析字段事实和能力事实，可包含投影规划需要的字段读模型、字段角色、存储形态和后处理线索，但不包含本次请求的输出字段、物理列集合或后处理任务，也不进入 Web 响应。

旧的 `/form/schema` 不作为静态模块主入口；如果短期保留，只能作为由当前模块 resolved form view 派生的调试或兼容出口，而不是 service 上的 `FormAbility`。子资源 controller 贡献给父模块的 UI view 不应被当前 controller 的 `/form/schema` 消费。该出口不能只搬运 UI 声明字段，还必须合并静态模型事实，例如字段选项、选项标题输出字段、基础类型和模型字段存在性校验。前端默认路径应尽快收敛到模块 context 或页面 bootstrap。

## 动态配置对齐

动态链路不作为当前静态 UI 声明设计的包袱。后续动态配置应按同一套目标形态反向对齐：

```text
动态元数据 + 动态 UI 配置
  -> ModuleUiDefinition
  -> ResolvedModuleUiDescriptor
  -> Web bootstrap
  -> 前端运行器
```

静态和动态的差异只保留在来源定义层：

| 类型     | 来源定义                                    | 归一目标             | Descriptor                   |
| -------- | ------------------------------------------- | -------------------- | ---------------------------- |
| 静态模块 | Java model、Ability、StaticModuleDefinition | `ModuleUiDefinition` | `ResolvedModuleUiDescriptor` |
| 动态模块 | Metadata、FieldDefinition、动态 UI 配置     | `ModuleUiDefinition` | `ResolvedModuleUiDescriptor` |

前端运行器、Web bootstrap、读投影和输出转换不应因为静态或动态来源不同而分裂成两套协议。

动态 UI 配置不应直接等同于页面布局。布局、控件参数、交互和发布快照可以作为动态来源事实存在，但进入运行态前应先转换为平台通用的 `ModuleUiDefinition`，再与动态字段事实合并。动态 viewCode 使用 UI set alias 这类稳定视图身份；`uiConfigId` 只保留为发布配置、请求校验和页面执行上下文，不作为 descriptor 视图身份。这样动态侧可以有配置管理和发布治理，运行态仍保持与静态相同的 descriptor 和读投影链路。

## 读投影与 SQL

UI 配置可以影响读投影，但不能直接生成 SQL。

正确链路：

```text
Web request(viewCode / uiConfigId)
  -> ResolvedListViewDescriptor
  -> ResolvedModuleReadModel
  -> ActionAuthorization / DataScope / FieldReadPolicy
  -> RecordReadProjectionPlanner
  -> QueryCompiler
  -> DAO / SQL mapper
```

错误链路：

```text
UI 配置 -> SQL columns / SQL fragment
```

`RecordReadProjection` 是后端内部对象。当前最小实现先保持 `ViewFieldRef` 形态的逻辑字段计划，不承载物理列；后续接入 SQL 投影时再补 `selectColumns`。`outputFields` 不应退化为裸字段名，否则会丢失关系锚点，后续无法优雅支持引用字段、关联表字段和动态字段 ID 映射。它最终可包含：

| 内容                      | 作用                                     |
| ------------------------- | ---------------------------------------- |
| `outputFields`            | 本次响应需要输出的字段引用               |
| `fieldReadPolicies`       | 已参与本次投影计划的字段可读策略标记     |
| `selectColumns`           | 经字段事实白名单解析后的物理列           |
| `requiredPlatformColumns` | 平台运行需要自动补齐的列                 |
| `postReadTransforms`      | 字典标题、引用标题、脱敏、虚拟值等后处理 |
| `virtualFields`           | 可输出但无物理列或不直接读列的字段       |
| `referenceTitleFields`    | 引用标题或投影输出字段                   |
| `protectedFields`         | 输出前需要脱敏或签名验证的字段           |

`ResolvedModuleReadModel` 是模块级事实，`RecordReadProjection` 是 view/request 级执行计划。只有 `RecordReadProjection` 可以包含本次请求的 `outputFields`、`selectColumns` 和 `postReadTransforms`。`postReadTransforms` 使用平台统一编码契约表达后处理类型和字段，SQL 投影路径只能执行已明确声明支持 Map 输出的后处理，不能由调用点临时硬编码字符串判断。

例如列表只声明输出：

```text
employeeNo, title, organizationId
```

实际 SQL 投影可能需要：

```text
id, version, tenant_id, employee_no, title, organization_id
```

如果 `organizationId` 需要标题输出，读计划应安排后处理任务，而不是允许 UI 配置声明 join 或 SQL。

读投影规则：

1. UI 决定需要输出哪些字段。
2. 字段事实决定字段能否读取、如何读取、是否有物理列。
3. SQL 只能使用后端白名单列名。
4. `id`、`version`、`tenantId` 和能力字段等平台必需字段自动补齐。
5. 虚拟字段、引用标题、字典标题、脱敏字段走后处理。
6. 展示、查询和排序分开校验；可展示不代表可查询或可排序。
7. 权限、租户、数据范围和动作授权不受 UI 配置控制。
8. descriptor 中出现字段不代表当前用户、当前动作或当前数据范围允许读取；读投影必须在认证上下文中叠加动作授权、数据范围、字段保护和字段级可读策略后再生成输出字段。

静态 DAO 如果短期没有投影查询能力，第一阶段可以先使用读投影做字段校验和输出裁剪，SQL 仍读取完整实体。后续再为 `BaseDao` 或 MuYunDatabase 增加投影读取入口。动态 SQL mapper 可以更早接入列投影。

## 第一阶段落地范围

第一阶段只做最小闭环：

1. 定义 `ModuleUiDefinition`、`ViewDefinition`、`ListViewDefinition`、`FormViewDefinition` 和字段 UI 声明模型。
2. 将视图声明挂到 `StaticModuleDefinition.forService(...)`。
3. 定义 `ViewFieldRef` 和常量版 `UiRule<T>`，让静态短写法可以归一到源无关模型。
4. 编写静态 UI 编译器，先输出 `ModuleUiDefinition`，再合并 service、model 和字段事实。
5. 先输出最小 `ResolvedModuleUiDescriptor`，随后补齐后端内部 `ResolvedModuleReadModel`。
6. Web bootstrap 返回 resolved views。
7. 增加 `RecordReadProjection` 内部模型，先用于列表字段校验和输出裁剪。
8. 已补动态配置到 `ModuleUiDefinition` 的最小适配契约，覆盖 `uiConfigId`、发布快照、字段 ID、relation 与 viewCode 身份边界，并接入动态模块 runtime context 返回 `ResolvedModuleUiDescriptor`。
9. 静态模块主线不再以 service 层 `FormAbility` 作为 UI schema 来源；`/form/schema` 仅作为兼容出口保留。

第一阶段暂不做：

1. 复杂布局和栅格。
2. 表达式联动。
3. 子表表单。
4. 动作态字段差异。
5. 多客户端差异。
6. 静态 DAO 投影 SQL 的完整改造。
7. 动态 UI 配置的大规模重构。

## 命名建议

命名遵循项目既有边界：

| 名称                      | 用途                                                       |
| ------------------------- | ---------------------------------------------------------- |
| `Definition`              | 源码或配置态声明，例如 `FormViewDefinition`                |
| `ModuleUiDefinition`      | 静态 DSL 和动态 UI 配置归一后的源无关 UI 定义              |
| `Descriptor`              | 编译后的对外输出，例如 `ResolvedModuleUiDescriptor`        |
| `ResolvedModuleReadModel` | 模块级后端内部读模型，不暴露给前端，不包含本次请求输出计划 |
| `Plan` / `Projection`     | 后端内部执行计划，例如 `RecordReadProjection`              |
| `moduleAlias`             | 运行时模块身份，不使用 `scopeName` 表达同一件事            |
| `viewCode`                | 模块内视图编码，用于区分 `list`、`form`、`detail` 等视图   |

`FormAbility` 不再作为目标形态。UI 声明不是 service ability，而是模块交付定义。
