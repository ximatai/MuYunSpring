# 日志系统治理

## 已落地能力

业务日志以四类不可变、类型化事实表达：登录、动作、请求错误和页面访问。每条事实都有事件 ID、发生时间、采集时间和 `traceId`，并在可得时记录租户、操作者、模块和动作。`eventId` 是 PostgreSQL 追加存储的去重键；`traceId` 只用于关联，不参与授权或身份判断。缺失或格式非法的 trace 会回退为事件 ID。

公共事实与 `BusinessLogPublisher`、`BusinessLogStore` 位于 `muyun-ability`。采集方只依赖该中立边界；PostgreSQL 追加写入、游标读取和 schema 初始化位于 `muyun-platform`，由 Starter 在受控启动生命周期装配。存储支持单条、批量、重复事件忽略，以及按时间、租户、模块、动作、错误码的游标读取。没有普通 CRUD Web 接口。

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

`BusinessLogStore.read` 是明细读取底座。`BusinessLogStatisticsReader` 提供服务内、类型化的动作和页面访问聚合：动作结果、已知耗时/影响数量的计数和总值，以及按 pageKey 的访问计数。统计查询设有最大扫描量，并以 `complete` 明确标示是否在上限内读取完全部明细。

该读取能力不执行 IAM 授权，也不暴露 Web 管理接口。调用方必须先按 IAM、租户和敏感诊断权限完成授权；日志内的 tenant 字段本身不构成读取授权。

## 明确限制

- 不实现消息队列、持久缓冲、数据库故障期间可靠接收、自动重试或多存储分流。
- 不实现日志管理界面、外置检索、通用查询 DSL 或授权读取 API。
- 不实现分区、长期时间桶聚合或保留期清理策略；容量和保留期有明确依据后再决定。
- 不承诺端到端恰好一次；事件 ID 只保证当前 PostgreSQL 存储的去重。
- 不把工作流历史、删除恢复明细或其他专题业务流水降级为诊断日志。
