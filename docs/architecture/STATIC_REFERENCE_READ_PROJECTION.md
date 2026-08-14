# 静态模块引用与读投影契约

本文定义静态模块通过引用关系带出关联模块字段的稳定契约。它服务于动静一体路线：静态 Java 模型先用注解和 service 声明能力，动态元数据后续应编译到同一套引用图和读投影运行时，而不是另起一套查询内核。

## 适用范围

该契约用于列表、选择器、详情、导出等读场景中，从当前模块按 `N:1` 或 `1:1` 引用路径读取关联对象摘要字段。

当前已验证的静态样本包括：

```text
iam.user <- iam.employee_account -> iam.employee
iam.employee -> iam.organization
```

用户模块通过职员账号绑定表带出职员工号和职员姓名，验证了反向桥接引用和递归引用路径。
职员模块通过所属机构带出机构名称，验证了普通多对一直接引用路径。

## 引用声明

静态引用关系声明在 Java 模型字段上：

```java
@ReferenceTo(target = EmployeeService.class)
private String employeeId;
```

字段含义：

| 字段 | 含义 |
| --- | --- |
| `target` | 静态业务的首选目标声明。目标 service 必须暴露 `public static String MODULE_ALIAS`。 |
| `moduleAlias`、`entityAlias` | 成对使用的别名后备声明，适用于动态、外部或无法直接依赖目标 class 的场景。 |
| `integrity` | 目标不可用时保留历史、阻断或级联删除的生命周期策略。 |

`target` 与 `moduleAlias` / `entityAlias` 必须二选一。记录引用始终指向目标主键 `id`；字典 code 由独立字典能力处理，不作为记录引用或 join 预留。

引用关系描述的是模型事实，不描述 UI 要展示哪些字段，也不描述 SQL join。普通业务模块不应手写 join SQL。
静态 service 不绑定目标 service；平台按 `ReferenceTo.target` 通过全局 `ReferenceTargetResolver` 解析标题和投影，
以模型字段本身作为引用路径抓手。

引用字段的派生输出声明在输出字段上，而不是作为 `@ReferenceTo` 的附带开关：

```java
@ReferenceLoad(source = "employeeId", field = "title")
private transient String employeeTitle;
```

多值引用需要向页面交付结构化摘要时，在 transient 输出字段上使用 `@ReferenceSummary`。每个摘要项稳定包含
`id`，`fields` 指定额外带出的目标字段；只传 `id`（或留空）可形成 id-only 摘要，不触发目标读取。

```java
@ReferenceSummary(source = "tagIds", fields = {"title", "color"})
private transient List<Map<String, Object>> tagSummaries;
```

该声明先编译为来源无关的 reference-summary descriptor，再由统一引用投影批量解析，页面不应根据原始 ID
自行查询或拼接结构。当前标准 `tagList` UI 只在 LIST 视图交付，只能绑定 `MANY` 的 reference summary，
且必须带出 `title`；`color` 是可选展示字段。当前 `ReferenceSummary` 仅有静态 Java 注解入口，动态元数据
尚不能声明该摘要事实，因此动态页面配置不能使用 `tagList`，这是阶段限制而非动静一体的完成形态。

`@ReferencedBy` 则表达只读反向关联。它从 `List` 的泛型来源模型中寻找唯一指向当前模型的
`@ReferenceTo`；多条引用时以 `sourceField` 消歧。平台启动时按来源模型自动解析唯一的 CRUD service，
读取目标记录后按该外键装配列表；批量详情与领域聚合会对同一来源关系执行一次 `IN` 查询后按外键回填。缺少或重复来源 service 会在启动期失败。该注解不属于聚合、不会写入
来源记录，也不会改变删除策略或触发父删联动。

```java
@ReferencedBy(sourceField = "departmentId")
private transient List<Employee> employees;
```

来源 service 继续负责其自身的数据域、软删和排序规则；反向关联只复用该读取入口，不要求来源 service
成为 `ChildAbility`，目标 service 也不需要重复声明关联配置。

## 多跳引用读取

当 `A.bId -> B.cId -> C`，A 需要读取 C 的字段时，用起点字段和类型化终点声明：

```java
@ReferenceLoad(source = "bId", hops = @ReferenceHop(target = CService.class))
private transient String cTitle;
```

终点字段默认是 `title`；读取其他字段时显式给出 `field`。平台在 B 的引用中寻找唯一指向
`CService` 的字段，并在启动期校验 A→B→C 链路。B 有多个可达 C 的引用时，才在对应 hop 上用 `via`
指出 B 的单个外键字段：

```java
@ReferenceLoad(
    source = "bId",
    hops = @ReferenceHop(target = CService.class, via = "primaryCId"),
    field = "status"
)
private transient String cStatus;
```

`source` 和每个 `via` 都必须是实际 `@ReferenceTo` 字段；每个 hop 的 `target` 都是 service class，不使用
点分路径字符串。多跳路径的 `source` 与每个 `via` 必须是 `ONE` 基数，保证每条源记录只对应一个终点值；
直接读取 `MANY` 引用仍可用，并按源 ID 顺序输出非空值集合。四级及更深链路只需继续添加 hop；平台会按统一引用投影契约逐层批量解析。

## 读取职责分层

引用读取必须按事实、策略和聚合分层，避免同一条关联在模型、列表和领域 service 各维护一份：

| 层次 | 入口 | 适用内容 | 不负责的内容 |
| --- | --- | --- | --- |
| 实体读事实 | `@ReferenceLoad`、`@ReferencedBy` | 记录本身稳定拥有的关联标题、字段或反向集合 | 列表筛选、排序、业务组合 |
| 列表查询策略 | `ModuleReadProjection` | 已暴露字段的列表展示、筛选、排序与 SQL join 优化 | 定义新的实体事实、供领域 service 间接消费 |
| 领域读取聚合 | 领域 read facade + `ReferenceReadFacade.enrich(...)` | 已授权根记录上的业务组合，如任职视图中的账号绑定 | 手工查询并重复拼接已声明的关联标题 |

领域读取需要实体关联字段时，先以根实体 service 和记录集合调用 `ReferenceReadFacade.enrich(...)`。
它只批量回填模型已经声明的 `@ReferenceLoad`、`@ReferencedBy` 事实，不暴露目标服务、引用图或 SQL planner；账号、权限、统计、
生命周期等额外业务信息仍由领域 facade 显式组合。`PlatformAbilityRuntime` 是平台内部的装配入口，普通业务
service 不应直接依赖它。

静态 `CrudAbility` 的标准 `list(...)` 与 `pageQuery(...)` 会自动批量回填声明的 `@ReferenceLoad`，
因此普通列表和分页业务代码不需要再调用读取门面。列表默认不自动回填 `@ReferencedBy`，避免一对多集合随
分页记录膨胀；单条读取和明确的领域聚合仍按各自读取边界处理。

## 读投影声明

当前模块对外可带出的关联字段声明在 service 上：

```java
class UserAccountService implements ModuleReadProjectionContributor {
    @Override
    public List<ModuleReadProjection> moduleReadProjections() {
        return List.of(
                ModuleReadProjection.filterable(
                        ReferencePath.inverseOne(EmployeeAccount::getUserId)
                                .then(EmployeeAccount::getEmployeeId)
                                .select(Employee::getEmployeeNo),
                        "employeeNo"),
                ModuleReadProjection.of(
                        ReferencePath.inverseOne(EmployeeAccount::getUserId)
                                .then(EmployeeAccount::getEmployeeId)
                                .select(Employee::getTitle),
                        "employeeTitle")
        );
    }
}
```

静态链路优先使用 Java getter method reference 描述引用路径：

```java
ReferencePath.from(Employee::getOrganizationId)
        .select(Organization::getTitle)
```

`from(...)` 表示从当前模块主模型的引用字段出发，`then(...)` 表示沿上一跳目标模型继续走一个引用字段，
`inverseOne(...)` 表示通过一个候选桥接模型的字段反向唯一命中当前模块。每一跳都必须落到真实 Java 字段，
并且该字段必须声明 `@ReferenceTo`。列表读投影会生成分页 SQL join，反向桥接必须显式使用
`inverseOne(...)` / `thenInverseOne(...)` 声明一对一唯一关系；未声明唯一性的 `inverse(...)` / `thenInverse(...)`
视为不安全路径，不能进入分页 join。

`outputField` 是当前模块对外暴露的稳定字段名。UI、查询接口和前端只消费 `outputField`，不直接消费跨模块路径。
同一模块内 `outputField` 必须唯一，并且不能覆盖主实体字段或平台标准字段。

直接 `ONE` 引用的输出字段已经在模型上通过 `@ReferenceLoad` 声明时，service 只需补充列表查询策略，
不应再次写出同一条路径：

```java
ModuleReadProjection.declared("organizationTitle", false, true)
```

扫描期会要求该模型上恰好存在一个同名的直接 `@ReferenceLoad` 输出，并将其编译回具体投影路径，
因此仍可复用 SQL join 的列表展示与排序优化。反向桥接、多跳或 `EXISTS` 投影没有单一的实体读事实，
继续使用类型化 `ReferencePath` 明确声明，不能用 `declared(...)` 隐藏其关系语义。

把“带出哪些关联字段”放在当前 service 上，是为了稳定当前模块自己的读 API。若出现 `A -> B -> C`，
且 A 需要带出 C 的字段，A service 应显式声明 `A.bId -> B.cId -> C.field` 的字段引用链；
不应通过消费 B service 的 `outputField` 间接形成投影依赖。

字符串 `path` API 仅作为动态元数据、兼容迁移或临时逃生口保留。普通静态 service 不推荐新增字符串路径声明。

## 查询能力

`ModuleReadProjection` 的默认语义是：

| 声明方式 | 可展示 | 可排序 | 可过滤 |
| --- | --- | --- | --- |
| `ModuleReadProjection.of(referencePath, outputField)` | 是 | 是 | 否 |
| `ModuleReadProjection.declared(outputField, filterable, sortable)` | 是 | 按参数 | 按参数 |
| `ModuleReadProjection.sortableOnly(referencePath, outputField)` | 是 | 是 | 否 |
| `ModuleReadProjection.filterable(referencePath, outputField)` | 是 | 是 | 是 |
| `ModuleReadProjection.filterableOnly(referencePath, outputField)` | 是 | 否 | 是 |
| `ModuleReadProjection.exists(referencePath, outputField)` | 是 | 否 | 是 |

过滤必须显式开启。展示字段不会因为已经被 select 出来就自动获得过滤能力。
`exists(...)` 用于“引用链是否命中”的布尔派生字段，例如职员是否已经绑定账号；SQL planner 会按引用链
生成 left join，并以目标记录主键是否非空作为输出值。

当前用户模块的边界是：

| 输出字段 | 路径 | 能力 |
| --- | --- | --- |
| `employeeNo` | `EmployeeAccount.userId(inverse) -> EmployeeAccount.employeeId -> Employee.employeeNo` | 展示、排序、过滤 |
| `employeeTitle` | `EmployeeAccount.userId(inverse) -> EmployeeAccount.employeeId -> Employee.title` | 展示、排序 |

当前职员模块的账号边界是：

| 输出字段 | 路径 | 能力 |
| --- | --- | --- |
| `username` | `EmployeeAccount.employeeId(inverse) -> EmployeeAccount.userId -> UserAccount.username` | 展示、过滤 |
| `accountBound` | `EmployeeAccount.employeeId(inverse) -> EmployeeAccount.id` | 展示、过滤 |

SQL plan 内部按语义拆分字段集合：

| 字段集合 | 含义 |
| --- | --- |
| `responseFields` | 响应给前端的字段。 |
| `queryableFields` | 可进入查询条件的字段。 |
| `sortableFields` | 可进入排序的字段。 |

## UI 消费边界

静态 UI 只声明当前模块字段或 service 已暴露的读投影输出字段：

```java
.listView(list -> list
        .field("username")
        .field("employeeNo")
        .field("employeeTitle"))
```

UI 不直接写跨模块路径，也不声明 SQL join。跨模块字段引用链是后端 service 和平台 planner 的内部契约。

## SQL 运行时边界

静态引用路径由平台解析成 join plan。当前支持：

1. 直接引用：当前模块字段引用目标模块主记录。
2. 反向桥接引用：候选模块的具体字段引用当前模块。
3. 递归路径：例如 `EmployeeAccount.userId(inverse) -> EmployeeAccount.employeeId`。

运行时 join 会自动附加租户等值和软删过滤。当前只面向适合分页列表的 `N:1` 或 `1:1` 摘要读取，不承诺一对多展开。

`RelationProjectionJoinContributor` 已标记为兼容逃生口。普通静态模块应优先使用：

```text
@ReferenceTo + ModuleReadProjectionContributor
```

只有无法被引用图表达的特殊旧场景，才使用手写 join contributor。

## 跨动静引用读取

`@ReferenceLoad` 不以 SQL join 为前提。静态单条读取、标准 Web 列表读取和动态列表读取都会在根记录读取后进入引用读管线；
领域 read facade 通过显式批量 enrich 入口复用同一实体读事实，不依赖 Web 的 Map 投影或 HTTP 入口。管线按
`ReferenceTarget` 聚合源记录中的目标 ID 与所需字段，通过统一 `ReferenceAbility` 批量读取并回填输出。
多跳路径会按 hop 逐层聚合并批量读取，不因源记录数量退化为逐条查询。
因此静态模块引用动态模块时，仍可获得标题和字段投影；每个目标最多各执行一次标题读取和字段投影读取，
不会产生逐行查询。

需要诊断批量读取行为时，应用可声明一个或多个 `ReferenceReadObserver` Bean；平台会按 Spring 顺序组合并接入
静态与动态引用读管线。观察事件只包含目标、字段集合、批量 ID 数、路径输出字段与 hop 序号，不携带具体 ID
或业务值；没有 Bean 时默认观察器为 `NONE`，不产生额外事件。日志、指标和请求级关联由上层按部署需要接入，
引用能力层不绑定具体观测框架。

静态目标可被当前引用图安全解析时，SQL join 仍是列表筛选、排序和摘要读取的优化路径，而不是投影字段正确性的唯一来源。动态目标或不能安全
编译为 join 的目标自动走上述批量补齐路径；该路径只负责输出，不让动态目标字段隐式成为源列表的筛选或排序字段。
如需跨模块筛选、排序，必须先形成可验证的数据权限与查询语义，再扩展为独立能力。

## 当前限制

1. 动态主实体 `ONE` 引用投影在可解析时可进入同一 SQL planner；静态到动态目标的标题和字段投影走批量补齐，
   当前不承诺其筛选或排序。模块关系、子实体引用、`MANY` 聚合和字典标题仍未接入 SQL planner。
2. 记录引用固定使用目标 `id`；字典值和枚举值不进入记录引用图。
3. 引用路径已有默认 join 深度和 join 数量保护；显式有限回环路径允许规划，自引用裁剪、诊断响应和 plan 缓存仍未实现。
4. 当前数据权限仍以源模块列表读取为主，目标模块数据权限如何叠加需要单独治理。
5. 动态 SQL 投影的引用输出字段已支持输出脱敏；受保护主字段以及加密、签名等存储保护字段仍回退实体查询。

## 已验证样本

职员带出组织名称使用普通多对一直接引用：

```text
iam.employee -> iam.organization
```

该样本证明当前机制不是用户-职员桥接表特化。当前只暴露 `organizationTitle` 展示和排序；是否允许按组织名称过滤，需要结合组织选择器、组织权限和查询性能再决定。
