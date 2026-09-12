# 日志系统治理

## 已落地能力

业务日志以四类不可变、类型化事实表达：登录、动作、请求错误和页面访问。每条事实都有事件 ID、发生时间、采集时间和 `traceId`，并在可得时记录租户、操作者、操作者机构快照、模块和动作。`eventId` 是 PostgreSQL 追加存储的去重键；`traceId` 只用于关联，不参与授权或身份判断。缺失或格式非法的 trace 会回退为事件 ID。

公共事实与 `BusinessLogPublisher`、`BusinessLogStore` 位于 `muyun-ability`。采集方只依赖该中立边界；PostgreSQL 追加写入、游标读取和 schema 初始化位于 `muyun-platform`，由 Starter 在受控启动生命周期装配。存储支持单条、批量、重复事件忽略，以及按时间、事件类型、租户、操作者、操作者机构、模块、动作、错误码、登录结果和 HTTP 状态的游标读取。`operator_organization_id` 是兼容性追加列；历史事件不回填，因此机构范围不会显示其归属无法确认的历史记录。

敏感诊断文本通过 `LogText` 限长并对密码、令牌、Authorization、Cookie 等已知凭据最小脱敏。日志事实不保存密码、会话令牌、Cookie 或原始请求体。

### 登录审计

`UserSessionService.login` 为每次登录尝试生成登录事实，区分成功、未知账号、凭据错误、租户或账号不可用、临时密码过期、会话签发失败和账号登录状态写入失败。成功事实只在会话签发和账号状态写入完成后生成。审计只使用已确认用户身份；未知账号可以没有操作者。

登录审计的默认策略是 fail-open：日志发布或存储失败不会撤销登录或会话，只输出固定、安全的 SLF4J 诊断。来源地址首期只取 Servlet `remoteAddr`；尚未建设可信代理配置，因此不信任 `X-Forwarded-For`。

### 动作与页面访问

`RuntimeActionBusinessLogEventListener` 只观察 `ACTION_EXECUTED` 与 `ACTION_FAILED`，保留原运行事件的 event ID，并转换为动作成功或失败事实。它不改变既有 `RuntimeAuditEventListener` 或运行事件多播语义；发布失败只输出固定诊断。动作耗时和影响数量在没有真实来源时为 `null`，不会用 `0` 伪造统计值。

静态标准 `CrudWeb` 的 insert、update、delete 由 `ActionEndpointInterceptor` 在控制器正常完成或抛出异常后记录一次动作事实；动态动作仍只消费运行事件，避免双计数。静态记录的成功表示控制器事务范围已正常返回，失败表示控制器异常，均不从 HTTP 状态推断提交结果。

`GET /platform.menu/{menuId}/entry` 在租户校验、菜单可见性校验和权限范围内的页面 bootstrap 都成功后，记录一次页面访问。失败的页面入口不记录为访问。页面访问以稳定的 `pageKey`（`moduleAlias:pageMode`）统计，`pageId` 可选；UI config ID 不被当作页面 ID。菜单 ID 只用于归因。写入失败不会影响页面响应。

### 请求错误与程序日志

统一异常 Advice 会记录对外 `PlatformWebError` 的 code、status、message 和 action message 摘要，同时记录可得的异常类型、信息和限长脱敏栈。`RequestTraceWebFilter` 补采集 Advice 未记录的下游 4xx/5xx，并记录逃出过滤器链的未处理异常。每个请求通过请求属性最多生成一条错误事实；错误日志发布失败不改变 HTTP 响应。

`responseCompleted` 当前始终为 `false`：Servlet 应用处理无法证明客户端已收到响应字节。过滤器短路路径会记录其实际固定安全响应摘要，并在 `CurrentUserWebFilter` 退出身份/租户作用域前快照已验证的 user ID 与 tenant ID。覆盖范围依赖 `RequestTraceWebFilter` 处于请求链外层；在其之前由第三方过滤器或网关直接结束的请求，以及尚未到达应用的网关错误，不属于当前采集范围。

Java 程序日志继续使用标准 SLF4J/Logback 控制台和文件通道。Boot 的 console/file pattern 输出 `traceId`、`endpointId`、`moduleAlias` 和 `actionCode`，缺失字段使用稳定占位符。`ActionEndpointInterceptor` 在动作处理期间维护 module/action/endpoint MDC，并在正常完成、异常清理和异步交接时清除。MDC 不写入业务字段或凭据。没有 Logback Appender 写 PostgreSQL，也没有集中程序日志采集。

## 查询与统计边界

`BusinessLogStore.read` 是存储中立的明细读取底座。`BusinessLogStatisticsReader` 提供服务内、类型化的动作和页面访问聚合：动作结果、已知耗时/影响数量的计数和总值，以及按 pageKey 的访问计数。统计查询设有最大扫描量，并以 `complete` 明确标示是否在上限内读取完全部明细。

`BusinessLogReadScope` 是读取范围契约，存储和平台层不依赖 IAM 或 HTTP。IAM 以当前请求的模块动作解析账号角色授权：系统用户的 `PLATFORM` 范围可读全局；租户范围可读当前租户；`ORGANIZATION` 范围只可读其授权机构及下级机构中、日志写入时已快照的操作者机构。任职角色不形成日志治理范围。范围条件会在查询、统计和按 ID 查看时由服务层重复收窄；菜单可见性和 Controller 路径都不是数据隔离手段。

管理端在与“平台设置”同级的“日志管理”二级菜单下注册三个静态页面：`登录日志`、`操作日志`（动作与页面访问）和`异常日志`。它们分别使用三个 Controller；活动与异常共享 `BusinessLogGovernanceService`，登录由 IAM 的 `LoginAuditGovernanceService` 组合该服务和读取范围。每个查询和详情动作都是独立授权点。接口异常的常规详情只给出请求、状态、对外响应摘要等安全字段；异常类型、异常消息和堆栈仅由平台全局范围的内部诊断动作返回。

三个列表都使用平台标准的 `GET /query/schema` 与 `POST /query` 协议：请求为 `WebQueryRequest`，响应为 `WebPageResponse`。登录审计只接受发生时间、操作者和登录结果；业务活动只接受发生时间、操作者、模块和动作；接口异常只接受发生时间、操作者、模块、动作、错误码和 HTTP 状态。查询条件只能使用 schema 声明的扁平条件，排序固定为发生时间倒序。服务边界把 `page/pageSize` 转为存储端的稳定游标推进，单次请求最多跳过 10,000 条；无法精确计数时以 `totalKnown=false` 返回下一个可读页，而不执行全表计数。任何用户条件都只会在 IAM 读取范围之外进一步收窄，不能扩大事件类型、机构范围或内部诊断权限。业务活动的动作和页面访问统计复用同一份标准查询条件，并由服务端固定最大扫描量。

已登录用户的日志响应统一提供 `operatorIdentity`：`employeeName`、`username`、机构和部门的 ID 与名称。IAM 以批量只读投影计算职员和用户名，不触发逐行查询，也不使用查看人的当前数据权限二次筛选已经授权的日志。机构名称始终按事件 `operatorOrganizationId` 快照解析，用户之后调岗不会改写该归属；部门来自当前可解析的职员任职，调岗、删除或历史缺失时为空或可能变化。这些展示字段绝不参与日志授权。

## 明确限制

- 不实现消息队列、持久缓冲、数据库故障期间可靠接收、自动重试或多存储分流。
- 不实现外置检索、通用查询 DSL 或跨日志类型的授权读取 API。
- 不实现分区、长期时间桶聚合或保留期清理策略；容量和保留期有明确依据后再决定。
- 不承诺端到端恰好一次；事件 ID 只保证当前 PostgreSQL 存储的去重。
- 不把工作流历史、删除恢复明细或其他专题业务流水降级为诊断日志。
