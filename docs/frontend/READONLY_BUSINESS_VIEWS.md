# 只读业务投影与同源多入口

## 读取接入

聚合投影和业务队列通过公开的 `RecordReadView` 与 `ReadonlyRecordWorkspace` 接入，不要求投影数据表、可写 CRUD 或 `ModuleContext`。领域服务拥有来源授权、业务资格、查询合并、稳定排序、分页及总数；平台拥有列表、详情、加载、错误、刷新、选中和响应式布局。不能在浏览器拼接各来源的第一页冒充完整分页。

业务提供已编译的 `ResolvedModuleUiDescriptor`，复用标准列、单元格与详情字段解析；浏览器不解释 Java DSL 或原始动态配置。详情只展示显式声明的 `page.detail.display`，不自动展开响应或回退编辑字段。字典及引用标题应随已授权投影交付，不为展示补查其他模块的普通 CRUD。

`RecordReadView` 契约：

- `sourceId` 是逻辑读取来源，`viewCode` 是来源内的具名视图，均不要求新建模块。
- `scopeKey` 随身份、租户或授权范围变化，用于使旧会话失效，不承载令牌或授予权限。
- `recordKey` 必须非空、稳定且唯一，聚合来源时包含原始来源，不能只使用可能重复的记录 ID。
- `query(request, { signal, contentLanguage })` 返回标准 `WebPageResponse`；旧接口由业务适配参数和响应。运行器校验记录形状、分页整数及唯一键；异常响应清空本次结果并进入错误状态，不写入分页状态。总数未知时保留 `totalKnown: false` 的下界语义，不推算精确总数。
- 可选的 `querySchema` 声明当前来源允许的查询字段、关系、组合方式、关键词字段与默认排序。
- 可选的 `detail.load(record, options)` 返回重新授权的详情投影，`detail.title` 提供标题；未提供时不打开详情。

工作区的可选 `query: RecordReadQuery` 承接额外筛选和排序；关键词、分页和用户查询状态由运行器维护。调用方的 `query` 条件与用户条件以 AND 组合，排序可由用户覆盖并恢复默认；菜单默认条件可由所属页面调整，强制业务范围和授权范围必须在服务端执行，详情、汇总和导出也不能绕过。

列表的 `businessContent.readings` 使用同一 `recordKey` 绑定阅读文本，适配器不得将不同来源的同名 ID 合并。接口语言变化后，业务重新交付本身份的已授权描述；同一来源、视图、范围下更新描述或适配器会重新读取，但保留搜索与分页位置。

刷新重读列表及仍在结果中的选中项；搜索、分页和筛选变化关闭旧详情。切换视图或身份立即清空旧结果、选中、错误和搜索状态并回到第一页，保留每页条数偏好；旧事实同步清空，适配器调用等待当前属性更新合并后执行，避免新视图携带旧入口条件。传输忽略取消信号也不能让迟到响应覆盖新会话。不同页签各持有一个工作区实例，不跨身份缓存结果。宽窄切换移动同一棵详情组件树，保留局部状态。宿主应提供明确高度的容器。

`queries`、`cell`、`detail`、`detailOperations` 插槽保留领域展示和动作组合；修改原记录进入已有标准详情或领域动作入口，继续校验权限、版本和数据范围。读取契约不提供保存、删除或启停。

查询复用标准常驻字段编辑器、条件编排器和排序工具区，支持 schema 的 `NONE`、`FLAT_AND`、`TREE` 组合方式。常驻字段采用声明的默认值，编辑草稿在点击查询前不生效；平面条件不重复提供常驻字段。条件编辑器折叠时保留同一实例及未应用草稿，身份、视图或查询能力变化后清空；仅文案或展示描述刷新保留查询位置。条件合并保持 OR 语义，不为单个组增加根层级，并将其他常驻条件计入最终请求的层级与节点预算。查询、重置及排序统一校验最终复杂度，拒绝时保留上次有效查询。应用筛选或排序回到第一页并关闭旧详情；同身份的等值查询声明更新不会重置分页。

字典选项由来源通过 `queryOptionsByField` 提供；引用筛选通过 `referencePickerOf` 复用已授权候选。没有候选适配器的引用字段不开放原始 ID 输入，也不隐式创建目标 CRUD。该读取工作区不提供汇总、批量写入、导出或助手编排。

## 同一模块的多个菜单

静态业务可在一个 `@PlatformStaticModule` 上重复声明 `@PlatformMenu`，无需为每个菜单再注册模块或 Controller：

```java
@PlatformStaticModule(application = AssetApplication.class,
        alias = "asset.device_use", title = "设备领用")
@PlatformMenu(id = "asset.menu.current", parent = "asset.menu.devices",
        title = "当前领用", entryParamsJson = "{\"view\":\"current\"}")
@PlatformMenu(id = "asset.menu.history", parent = "asset.menu.devices",
        title = "交接记录", entryParamsJson = "{\"view\":\"history\"}")
class DeviceUseWebController { /* 一个领域与 HTTP 接入实现 */ }
```

多个入口分别声明稳定菜单 ID，共享模块的 `accessMode`；应用内贡献 ID 不可重复。参数必须是 JSON 对象，所属领域负责视图白名单与值域。路由入口保留业务参数，外链不使用本地业务视图参数。

标准模块还可声明 `defaultUiConfigId`、`defaultQueryTemplateId`，沿用现有模块归属、发布及租户校验。自定义路由消费自己的已授权描述，不声明标准运行器的 UI/查询配置。

前端通过 `usePageDescriptor` 和 `usePageRoute` 读取所属页签的恢复事实，避免跟随另一个活跃页签。`resolveRecordReadEntry` 使用页面声明的模块和视图白名单选择展示；直接访问必须显式指定允许的默认视图或直接视图，未知、冲突及跨模块入口拒绝。标准模块映射到自定义业务路由时保留菜单默认 UI/query 标识，服务端仍重新核验。刷新恢复从当前用户可见菜单重建入口，快照中的菜单参数不能覆盖现行菜单事实。

服务端通过 `MenuEntryRequestContext` 取得经当前可见菜单校验后的参数，不能信任浏览器提供的 JSON。标准 CRUD 复用 `ModulePageEntryPolicy` 追加固定条件、限制动作及核对记录；自定义查询显式执行所属领域策略。未知视图应拒绝，不能降级为全部记录。

菜单可见性、视图资格、来源记录权限分别裁决。多个同模块菜单共享模块菜单授权，不新增各视图独立授权码；独立资格使用所属领域动作校验，不能由入口参数授予。无菜单上下文的 API 仍按原有权限执行，该可选策略不能承担全局数据隔离。

同源的当前状态与历史记录若具有不同生命周期或权限，应保持各自领域模型，只共享读取工作区。

## 引用与消费者支持

跨目录候选复用 `ReferencePickerProvider` 的 `businessPurpose`，详见[引用组件治理](../platform/topics/page/REFERENCE_COMPONENT_GOVERNANCE.md)。一个 provider 对应一个规范目标模块，候选 ID 是该目标的持久化身份。跨目录共享目标对象不改变此身份；跨模块聚合且 ID 可能重复时，应先在领域确定统一目标和稳定身份，不能直接将列表的复合 `recordKey` 当成引用保存协议。搜索、回显及最终保存分别执行业务用途和来源记录授权。`resolve` 必须支持未搜索过的历史 ID，不能仅查本地候选缓存；允许历史展示不等于允许重新选入。历史仅展示项应标记 `disabled`；可保留原引用，当前选择中移除后不能重新选入。授权修订变化会清除旧候选、历史标题及未完成的读取结果。

普通投影优先使用 `ReadonlyRecordWorkspace`。定制布局可组合公开的 `ManagementWorkspace`、`RecordQueryListSurface`、`RecordQueryListCell`、`RecordDetailPanel`、`RecordDetailFields`、`RecordDetailExtensionSection` 及展示解析器；定制列表通过 `cell` 插槽复用 `RecordQueryListCell`，保留引用标题、状态、文件大小与阅读文本等标准展示语义。这些是展示组合契约，不公开完整模块运行器或任意内部路径。新增公开接口同时维护安装包契约和样板。

[业务消费者样板](../../muyun-web/examples/business-web/src/views/ReadonlyBusinessViewExample.vue)和[跨目录引用样板](../../muyun-web/examples/business-web/src/views/CrossDirectoryReferenceExample.vue)使用确定性的本地数据验证当前／历史视图及跨目录选择，仅消费公开包；不代表真实业务授权、生产聚合查询或业务部署验收。业务在平台发包后更新正式依赖，再完成自身后端的来源权限、租户及真实数据回归。
