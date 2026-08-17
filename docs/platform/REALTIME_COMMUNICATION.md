# 平台实时通信设计

## 1. 目标

建立统一的平台实时通信底座，先承载数据变化广播，后续可承载用户通知、在线状态、协同动作、IM 类双向消息等实时能力。

该底座需要同时满足：

1. 单向广播和双向通信复用同一套连接、鉴权、心跳、重连和观测机制。
2. 业务代码不直接依赖 WebSocket、STOMP、topic 字符串或前端实时通信库。
3. 后端只表达业务事实和消息，不向前端下达刷新、弹窗、关闭页面等 UI 指令。
4. 前端业务页面不直接处理传输协议，只消费平台定义的事件、命令和状态。
5. 静态链路和动态链路未来都能接入同一套实时能力，但不因为动态链路牺牲静态业务代码直觉。
6. 第一阶段可用进程内 broker 打通链路，后续可演进到外部 broker、可靠 outbox 和多实例广播。

实时通信底座是平台基础设施，不是单个业务专题的附属能力。数据变化广播、通知中心、IM、工作流待办提醒等能力可以复用它，但不能把各自业务语义混在同一套 payload 中。

## 2. 非目标

本能力不负责：

- 建立完整 IM 产品；
- 建立通用企业事件总线；
- 保证第一阶段消息可靠补发；
- 在实时消息中携带完整业务数据快照；
- 通过实时消息表达 Toast、弹窗、页面刷新、路由跳转等 UI 行为；
- 让业务 Controller、Service 或前端页面直接依赖 STOMP 传输细节；
- 在第一阶段治理动态表单、动态动作或工作流动作的完整实时链路。

动态表单、动态动作和工作流实时消息后续应接入同一外部契约，但当前只保留扩展边界，不作为本阶段正式治理范围。

## 3. 技术选型

平台实时通信主选：

```text
WebSocket + STOMP
```

后端：

```text
spring-boot-starter-websocket
Spring WebSocket MessageBroker
Spring simple broker
```

前端：

```text
@stomp/stompjs
```

暂不默认引入：

```text
sockjs-client
RabbitMQ / ActiveMQ
Kafka / Redis pubsub
Socket.IO
RSocket
```

### 3.1 选择 STOMP 的原因

平台不选择裸 WebSocket 作为业务协议。裸 WebSocket 只提供双向字节或文本通道，后续会逼平台自行发明订阅、路由、用户队列、错误帧、心跳、重连恢复、权限拦截和消息确认语义。

STOMP 提供已经被 Spring 和前端生态验证的消息语义：

- topic 订阅；
- user queue；
- application destination；
- frame header；
- heartbeat；
- broker relay 演进路径；
- 前端自动重连和生命周期回调。

数据变化广播只是 STOMP 订阅模型的一种用法。未来双向通信、用户通知、在线状态和 IM 能在同一连接机制下扩展，而不需要再引入第二套实时技术栈。

### 3.2 SSE 的定位

SSE 适合单向服务端推送。如果平台只建设数据变化广播，SSE 会更轻。

当前选择 STOMP 的原因是平台希望同步建设单向广播和未来双向通信的共同道路。选型权衡从“最轻广播”转为“统一实时通信底座”。

SSE 不作为默认路线，但仍可作为未来特定部署或公开订阅场景的补充 adapter。业务契约不得依赖 SSE 或 STOMP 的差异。

### 3.3 SockJS 的定位

SockJS 的主要价值是当 WebSocket 不可用时提供 fallback。它适合老浏览器或强代理环境兼容，但不适合作为新平台默认主干。

平台第一阶段不启用 SockJS。后续如果客户网络环境大量拦截 WebSocket Upgrade，可以作为部署兼容开关评估：

```text
WebSocket endpoint + SockJS fallback
```

启用 SockJS 不应改变业务事件契约、destination 命名和前端业务 API。

### 3.4 外部 broker 的定位

第一阶段使用 Spring simple broker，目标是打通平台实时能力的边界、封装和前后端链路。

进入以下场景后，再升级外部 broker 或 broker relay：

- 多实例部署需要跨节点广播；
- 实时连接数和 topic 数达到 simple broker 难以承担的规模；
- 需要 broker 级监控、限流、隔离和路由；
- 需要与其他系统共享消息通道；
- IM、协同或通知中心成为核心产品能力。

外部 broker 是传输层演进，不应影响业务发布和订阅门面。

## 4. 分层设计

```text
业务能力层
  ├── ActionResult / DataChange
  ├── 用户通知
  ├── 在线状态
  └── 后续 IM / 协同能力

平台实时门面层
  ├── DataChangeRealtimePublisher
  ├── NotificationPublisher
  ├── RealtimeMessagePublisher
  └── RealtimeChannel / RealtimeCommand 契约

实时传输适配层
  ├── Spring WebSocket / STOMP
  ├── @stomp/stompjs
  ├── 鉴权、心跳、重连、连接状态
  └── destination 权限

运行与可靠性层
  ├── simple broker
  ├── 连接事件和观测
  ├── 后续 broker relay
  └── 后续 outbox / offset 补偿
```

业务能力层不得直接调用 STOMP API。传输适配层不得定义业务事实。

## 5. 后端设计

### 5.1 WebSocket Endpoint

平台统一暴露一个实时通信入口：

```text
/ws/platform
```

该入口负责：

- 建立 WebSocket 连接；
- 接收 STOMP CONNECT；
- 绑定当前用户、租户和 trace 上下文；
- 配置 heartbeat；
- 处理连接建立、断开、异常和订阅事件；
- 进入统一 destination 权限校验。

### 5.2 Destination 约定

平台 destination 使用稳定命名，不直接使用 Controller URL 风格。

统一基础前缀：

```text
/ws/platform
/app/platform/**
/topic/platform/**
/user/queue/platform/**
```

当前默认通道：

```text
/user/queue/platform/data-changes
/user/queue/platform/notifications
/app/platform/ping
```

租户、机构、业务兴趣和 IM 等后续能力使用统一的复数资源段：

```text
/topic/platform/tenants/{tenantId}/public/data-changes
/topic/platform/tenants/{tenantId}/public/notifications
/topic/platform/organizations/{organizationId}/public/data-changes
/topic/platform/organizations/{organizationId}/public/notifications

/topic/platform/modules/{moduleAlias}/data-changes
/topic/platform/modules/{moduleAlias}/records/{recordId}/data-changes
/topic/platform/modules/{moduleAlias}/resources/{resourceKey}/data-changes
/topic/platform/modules/{moduleAlias}/resources/{resourceKey}/records/{recordId}/data-changes

/topic/platform/contexts/{contextType}/{contextId}/data-changes

/user/queue/platform/im/messages
/topic/platform/im/conversations/{conversationId}/messages

/app/platform/im/messages/send
```

命名原则：

1. `/topic/**` 表示服务端广播订阅。
2. `/user/queue/**` 表示用户级点对点消息。
3. `/app/**` 表示客户端发往服务端的命令入口。
4. 平台域固定为 `/platform`。
5. 集合路径使用复数：`tenants`、`organizations`、`modules`、`records`、`resources`。
6. 公共频道显式包含 `/public/`。
7. `data-changes`、`notifications`、`im/messages` 分离，不复用 payload。
8. destination 字符串集中定义，不散落在业务代码中。
9. destination 只表达通道，不表达 UI 行为。
10. `moduleAlias`、`recordId`、`resourceKey`、`contextId` 作为路径变量时必须进行路径编码。

### 5.2.1 频道分层

平台频道分成两类：

```text
接收范围频道
  ├── user
  ├── tenant public
  └── organization public

业务兴趣频道
  ├── module
  ├── record
  ├── resource
  └── context / conversation
```

接收范围频道解决“谁可以收到”。业务兴趣频道解决“谁正在关心这个对象”。业务兴趣频道可以降低噪声和提升体验，但不得单独作为权限边界。

建议订阅策略：

| 场景 | 订阅方式 |
| --- | --- |
| 登录后默认 | user queue |
| 登录后可选 | 当前租户 public、当前机构 public |
| 页面打开列表 | module channel |
| 页面打开详情或编辑 | record channel |
| 业务上下文、任务空间、协同房间 | context / conversation channel |

租户和机构 public channel 是公共事实频道，不是数据权限频道。任何需要角色、数据权限、记录级可见性判断的变化，不得进入公共频道。

### 5.2.2 User Queue 隔离

用户私有频道不在路径中暴露 `userId`：

```text
/user/queue/platform/data-changes
/user/queue/platform/notifications
/user/queue/platform/im/messages
```

所有客户端订阅的是同一个逻辑地址，但服务端发送时必须指定目标用户：

```java
simpMessagingTemplate.convertAndSendToUser(
        userId,
        "/queue/platform/notifications",
        payload
);
```

隔离依赖 STOMP user destination 机制和 CONNECT 阶段绑定的 `Principal`。`Principal.getName()` 必须稳定使用平台 `userId`，不得使用可变的用户名、昵称或显示名。

禁止使用普通广播模拟用户私有消息：

```java
// 禁止
simpMessagingTemplate.convertAndSend("/user/queue/platform/notifications", payload);
```

多端登录时，同一 `userId` 的多个 WebSocket session 都会收到 user queue 消息。这是用户级通知和多端同步的默认语义。只针对某个登录会话生效的通知必须在 payload 中携带稳定会话标识，由客户端按当前登录会话过滤；不得把 session 信息塞进 user queue 路径。

### 5.2.3 典型业务场景

单据详情或编辑协同：

```text
/topic/platform/modules/{moduleAlias}/records/{recordId}/data-changes
```

用户打开详情页或编辑页时按需订阅。record channel 只承载低敏脏标记，页面收到后重新查询详情；敏感记录的完整 payload 必须选择精确 fan-out 到用户 queue。

模块列表新增、删除或集合变化：

```text
/topic/platform/modules/{moduleAlias}/data-changes
```

列表页打开时按需订阅。新增、删除通常发送 `collection-changed`，可不携带具体 `recordId`。如果模块涉及复杂数据权限，优先发送低敏集合变化信号，前端收到后自行重新查询当前列表。

内部 IM 私信：

```text
/user/queue/platform/im/messages
```

IM 发送命令：

```text
/app/platform/im/messages/send
```

如果存在会话或群聊，可扩展：

```text
/topic/platform/im/conversations/{conversationId}/messages
```

IM 不复用 `DataChange` payload，应定义独立消息契约和会话权限。

业务预警或个人待办提醒：

```text
/user/queue/platform/notifications
```

后端根据规则精确计算接收人并 `sendToUser`。若预警天然属于租户或机构公共低敏信息，才允许进入 public notification channel。

安全通知也使用用户私有 notification queue。例如密码被管理员修改、重置，或用户自助修改密码成功后，业务层先产生用户安全事实，再由安全事件 adapter 统一撤销用户 session，并由 realtime adapter 向对应用户发送安全通知：

```text
/user/queue/platform/notifications
```

payload 表达稳定安全事实，例如 `platform.security.password-changed`、`platform.security.password-reset`、`platform.security.force-logout` 和 `logoutRequired`。后端不表达 Toast、弹窗、倒计时秒数或跳转方式；前端根据安全事实决定展示方式，并在本地完成退出登录。

用户管理中的单个登录会话下线发送 `platform.security.session-revoked` 安全事实，payload 携带 `targetSessionId`。由于 STOMP user queue 仍按 `userId` fan-out，同一用户的其他会话可能收到该消息，但只有当前登录 `sessionId` 与 `targetSessionId` 相同的前端才执行本地退出；其他会话必须忽略。

用户登录或登录会话被撤销时，不向无授权的模块级 topic 广播账号或 session 标识。后端将 `UserSessionLifecycleEvent` 转换为用户私有业务事件，只 fan-out 给当前在线且对目标用户通过 `iam.user.sessions` 记录权限判断的管理端用户：

```text
/user/queue/platform/business-events
```

payload 只表达低敏脏标记，例如 `type=iam.user.session.collectionChanged`、`moduleAlias=iam.user`、`recordId=userId`、`reason=LOGGED_IN/LOGGED_OUT/REVOKED` 和 `sensitivity=DIRTY_MARKER`，不携带 `sessionId`、IP、User-Agent、token hash 或终端明细。在线用户扫描、接收者 session 复核、当前用户/租户上下文绑定和 user queue 投递由平台业务实时 fan-out 门面负责；“某模块某记录需要某动作权限”的接收策略由平台 recipient policy 工厂负责。IAM adapter 只负责把会话生命周期事实转换为集合变化事件，并声明目标用户需要 `iam.user.sessions` 记录动作权限。用户管理页收到后只刷新当前可见用户的在线状态；如果目标用户子列表已展开，再通过 `/iam.user/{id}/sessions` 权限接口读取会话明细。

前端业务页面需要订阅模块、记录或上下文 topic 时，必须通过页面实时生命周期封装接入。基础规则：

- 通用页面订阅使用 `usePageRealtimeSubscription`，业务优先使用更具体的 `usePageDataChange`、`usePageBusinessEvent`、`usePageRecordExternalChange`；
- 页面只声明所需实时事实、过滤条件和处理函数，由封装负责挂载时订阅、卸载时反订阅；
- 全局 realtime 连接重建时，由 `app/realtime` 统一恢复页面级 topic 订阅；
- 编辑中记录需要提示外部变更时，使用 `usePageRecordExternalChange` 统一处理模块订阅、当前记录匹配、编辑态判断和保存中自身事件保护；
- 需要按实时事件刷新列表、状态摘要或子资源时，优先使用 `createRealtimeRefreshQueue` 合并短时间内的重复刷新，并用 latest guard 丢弃过期响应；
- 主子表页面收到子资源集合变化事件时，主表只刷新当前可见记录的摘要；子表只有在对应主记录已展开时才刷新明细。页面不得因为低敏脏标记而全量重查所有主表记录或预取所有子资源；
- 应用全局连接启动逻辑只订阅用户私有基础队列，不订阅具体业务模块 topic；
- 业务页面不得直接导入 `app/realtime` 的 `subscribeApp*` 方法，不得直接持有 STOMP subscription 或调用底层 `realtime.subscribe`。

### 5.3 发布门面

平台提供稳定后端门面，普通业务代码不直接使用 `SimpMessagingTemplate`，也不直接面向实时通道发布数据变化。

基础门面：

```java
public interface RealtimeMessagePublisher {
    void broadcast(RealtimeTopic topic, Object payload);

    void sendToUser(String userId, RealtimeQueue queue, Object payload);
}
```

领域门面：

```java
public interface DataChangeRealtimePublisher {
    void publish(CommittedChangeSet changeSet);
}
```

业务动作链路只需要形成 `CommittedChangeSet`。事务提交后的平台事件 adapter 负责调用实时门面，并将它发送到当前用户的数据变化通道。

不建议业务 Service 调用：

```java
simpMessagingTemplate.convertAndSendToUser(userId, "/queue/platform/data-changes", payload);
```

因为这会把业务事实、通道命名和传输技术耦合在一起。业务 Service 应继续通过 ActionResult / DataChange 契约报告事实，不因为实时通信底座存在而新增传输层依赖。

后端发布门面按消息类型分层，不复用 payload：

| 消息类型 | 门面 | 默认 destination | payload 边界 |
| --- | --- | --- | --- |
| 数据变化 | `DataChangeRealtimePublisher` | 发起用户 `/user/queue/platform/data-changes`；公共 module / record topic | user queue 可发送完整 `CommittedChangeSet`；公共 topic 只能发送清空 `facts` 的低敏脏标记 |
| 安全通知 | `SecurityRealtimeNotifier` | `/user/queue/platform/notifications` | 只表达安全事实、是否需要退出、目标 session；不携带 token、IP、User-Agent 或 UI 指令 |
| 业务私有事件 | `BusinessRealtimeNotifier` / `BusinessRealtimeFanOutPublisher` | `/user/queue/platform/business-events` | 只发送给经 recipient policy 判定的用户；普通业务状态变化优先发送低敏脏标记，详情通过业务查询接口读取 |
| 会话 presence | `UserSessionPresenceLookup` + `UserSessionLifecycleEvent` | 用户管理仍复用 `/user/queue/platform/business-events` | presence 只表达 WebSocket 连接观测，不替代 session 有效性；管理页通过会话查询接口读取完整状态 |

### 5.3.1 实时业务提醒

业务提醒是当前在线用户的短生命周期提示，不是持久化消息中心或待办模型。业务代码通过
`BusinessNotificationService` 发布 `BusinessNotification`，平台在事务提交后按接收范围解析当前在线用户，并逐人发送：

```text
/user/queue/platform/business-notifications
```

提醒包含标题、副标题、纯文本内容、`dismissible` 和声明式动作。接收范围可按全局、租户、机构、部门、岗位和用户指定，所有维度按并集处理并按 `userId` 去重；`systemWide` 表示跨租户范围。接收范围不进入前端 payload。

动作只能是平台页面导航，或发送到已注册业务 `BusinessNotificationCommandHandler` 的命令；前端不得执行消息携带的脚本、任意回调或裸 URL。命令处理器必须按当前操作者重新校验权限和业务状态。`dismissible=false` 仅表示在线工作台不提供关闭入口；刷新、离线和断线重连后不保证可恢复，也不形成处理留痕。

各门面收到空接收人、空事件或无法确认当前用户时必须跳过发送。业务代码不得绕过门面直接调用 `RealtimeMessagePublisher`，除非正在实现新的平台 realtime adapter。

### 5.4 会话有效性与 Presence

用户登录会话和实时在线状态必须分开治理：

- 登录会话是认证安全事实，由 session 表判断是否未过期、未撤销、用户和租户仍有效；
- presence 是连接观测事实，由实时连接 registry 按登录 `sessionId` 聚合 WebSocket 连接数和最近观测时间；
- 管理端主表展示会话摘要时应区分“使用中”“闲置”“在线”“离线”：`使用中` 表示存在实时连接且最近有前端观测活动，`闲置` 表示存在实时连接但超过阈值无前端观测活动，`在线` 表示仍有有效 session 但当前没有实时连接，`离线` 表示没有有效 session；
- 管理端子列表展示单个登录会话的连接状态时只使用“使用中”“闲置”“离线”。子列表中的“离线”表示该有效 session 当前没有实时连接，不等同于用户已失效或 session 已被撤销；
- 已连接 session 超过 3 分钟没有新的实时观测活动时视为“闲置”。实时观测活动优先来自前端交互上报；没有观测数据时，HTTP 最近请求仅作为兼容兜底。后端定时扫描 presence 阈值变化并发布会话业务事件，用户管理页只响应消息刷新；
- WebSocket CONNECT / DISCONNECT 发布 `UserSessionLifecycleEvent`，再复用用户管理既有业务事件 fan-out，让已打开的用户管理页刷新摘要和展开行；
- presence 默认是进程内观测。多节点、断线超时、心跳窗口和外部 broker 统一治理前，不承诺强一致在线人数。

### 5.5 客户端命令入口

客户端发往服务端的 STOMP command 统一进入平台实时命令层。

第一阶段只保留轻量探活或诊断命令：

```text
/app/platform/ping
/app/platform/session/activity
```

`/app/platform/session/activity` 由前端 workbench 统一上报浏览器交互活跃，默认监听 `pointermove`、`pointerdown`、`keydown`、`scroll` 和页面重新可见，且必须节流。业务页面不得自行监听鼠标键盘事件或直接发布 activity command。

客户端 activity 上报是应用级能力，不是页面能力：

- activity reporter 只能随全局 realtime 连接启动和停止，必须在连接断开、用户退出或应用销毁时移除事件监听；
- 业务页面不得自行实现“页面加载订阅、卸载反订阅”的底层模板代码，应通过页面实时生命周期封装声明事件需求；
- 新增业务命令必须先进入平台实时命令层，不能从页面直接拼接 `/app/**` destination。

后续 IM、协同命令进入独立业务 adapter。业务 adapter 可以处理命令，但不负责连接、鉴权、心跳和底层消息路由。

### 5.6 鉴权与权限

实时连接的鉴权应复用现有登录态和当前用户上下文。

推荐规则：

1. CONNECT 阶段校验 token。
2. 建立 STOMP Principal 和当前用户上下文，并保留可复核的登录态标识。
3. SUBSCRIBE 阶段复核登录态仍有效，并校验 destination 权限。
4. SEND 阶段复核登录态仍有效，并校验 command 权限。
5. 用户级消息必须通过 user destination 发送，不手写用户私有 topic。

权限粒度按能力演进：

| 阶段 | 权限口径 |
| --- | --- |
| 数据变化广播 | 发起用户始终收到完整 user queue；module / record 共享 topic 只能承载低敏脏标记；跨用户完整 payload 必须通过按权限过滤的 user queue |
| 用户通知 | 只能接收当前用户 queue |
| IM / 协同 | 按房间、会话、参与者或业务资源校验 |
| 动态能力 | 通过动态元数据、动作权限和数据权限 adapter 接入 |

### 5.7 租户与作用域

后端广播不得把跨租户数据变化无差别推给所有连接。

当前采用保守策略：

- 数据变化先发送到当前发起用户的 user queue；
- 普通记录变化可以广播到 module / record topic，但只能发送低敏摘要；
- 公共 topic 摘要不得携带 `facts`、业务字段、会话明细、token、IP、User-Agent 等敏感载荷；
- 跨用户完整业务 payload 必须走 user queue，并在发送前完成接收者权限过滤；
- 无法判断当前用户时不发送实时数据变化；
- 系统态变化和跨用户共享广播后续需要显式声明可见范围。

公共 topic 的多租户和数据权限边界按“脏标记”处理，允许接收者收到可能无权查看的低敏变化提示。真正的数据读取仍由查询接口负责权限判断。

后续支持租户和机构公共频道时，订阅可以按身份归属处理，但发送必须按事件可见性处理。

前端可以默认订阅：

```text
/topic/platform/tenants/{tenantId}/public/data-changes
/topic/platform/organizations/{organizationId}/public/data-changes
```

但这两个频道只能承载真正公共、低敏、天然应被该范围内所有人知道的变化。

适合进入公共频道：

- 字典变化；
- 菜单、页面配置发布；
- 公共组织结构变化；
- 岗位类别、基础配置类变化；
- 低敏平台配置刷新信号。

不得进入公共频道：

- 用户账号；
- 角色授权；
- 员工敏感资料；
- 审批或工作流业务单据；
- 业务交易数据；
- 任何需要角色、数据权限或记录级可见性判断的变化。

敏感或专有变化应选择：

```text
精确到人：/user/queue/platform/**
业务级兴趣频道：/topic/platform/modules/**、/topic/platform/contexts/**
服务端按权限 fan-out：后续能力
```

因此，安全边界不依赖前端是否订阅了 tenant/org public channel，而依赖后端只把公共事件发送到公共频道。

## 6. 前端设计

### 6.1 封装位置

前端实时能力进入 `web-core`，作为无 UI 平台能力。

建议分层：

```text
web-contracts
  └── 实时消息、通道、命令和数据变化 payload 类型

web-core
  ├── realtime client
  ├── connection state
  ├── subscribe / publish 门面
  ├── data change dispatcher
  └── token / trace / reconnect 适配

platform-workbench
  └── 应用启动后建立实时连接并提供全局状态出口

views / business pages
  └── 不直接 import @stomp/stompjs
```

业务页面不直接创建 STOMP client，不直接写 destination 字符串。

### 6.2 前端门面

前端提供稳定门面：

```ts
export interface RealtimeClient {
  connect(): Promise<void>;
  disconnect(): Promise<void>;
  subscribe<T>(channel: RealtimeChannel<T>, handler: RealtimeHandler<T>): RealtimeSubscription;
  publish<T>(command: RealtimeCommand<T>, payload: T): void;
}
```

通道通过类型声明集中定义：

```ts
export const dataChangeChannel: RealtimeChannel<CommittedChangeSet>;
export const userNotificationChannel: RealtimeChannel<UserNotification>;
export const userBusinessEventChannel: RealtimeChannel<BusinessRealtimeEvent>;
export const platformPingCommand: RealtimeCommand<PlatformPingRequest>;
export const sessionActivityCommand: RealtimeCommand<SessionActivityRequest>;
```

业务代码应使用：

```ts
realtime.subscribe(dataChangeChannel, handleChangeSet);
```

而不是：

```ts
client.subscribe('/user/queue/platform/data-changes', callback);
```

### 6.3 连接生命周期

实时连接由应用壳或 workbench 管理，业务页面只订阅平台通道。

连接状态至少包含：

```text
idle
connecting
connected
reconnecting
disconnected
unauthorized
failed
```

前端统一处理：

- token 注入；
- reconnect delay；
- heartbeat；
- 页面可见性恢复；
- logout 后断开连接；
- login 或租户切换后重建连接；
- 订阅恢复；
- trace 和诊断日志。

业务页面不应自行处理重连和认证刷新。

### 6.4 数据变化消费

数据变化广播进入既有数据变化消费机制：

```text
STOMP message
  -> RealtimeClient
  -> dataChangeChannel
  -> DataChangeDispatcher
  -> Query invalidation / 页面策略
```

发起操作的页面可能同时收到：

- HTTP ActionResult 中的 `changeSetId`；
- 实时广播中的同一 `changeSetId`。

前端必须按 `changeSetId` 去重，避免重复刷新或重复提示。

前端不得根据 `message.text` 或实时 payload 文案做逻辑分支。

## 7. 消息契约

### 7.1 RealtimeEnvelope

实时传输层可以统一包装 envelope：

```json
{
  "id": "message-1",
  "type": "platform.data-change",
  "occurredAt": "2026-07-15T10:00:00Z",
  "traceId": "trace-1",
  "payload": {
    "changeSetId": "change-set-1",
    "changes": []
  }
}
```

字段语义：

| 字段 | 说明 |
| --- | --- |
| `id` | 实时消息 ID，用于诊断和后续补偿 |
| `type` | 平台消息类型，不等同于 UI 类型 |
| `occurredAt` | 事件形成时间 |
| `traceId` | 关联 HTTP 动作或后台任务 |
| `payload` | 业务事实载荷 |

`payload` 必须是稳定契约对象，不使用临时 `Map` 作为跨层协议。

### 7.2 DataChange Payload

数据变化消息 payload 复用 `CommittedChangeSet`。用户私有队列可以承载完整数据变化；公共 topic 只能承载低敏摘要，必须清空 `facts`：

```json
{
  "changeSetId": "change-set-1",
  "changes": [
    {
      "moduleAlias": "iam.employee",
      "recordId": "employee-1",
      "type": "record-updated"
    }
  ]
}
```

广播只表达数据事实：

- 哪个模块或资源变化；
- 哪条记录或集合变化；
- 变化类型；
- 变化批次；
- 必要的租户、作用域或版本。

广播不表达：

- 是否刷新页面；
- 是否关闭弹窗；
- 是否弹出 Toast；
- 是否跳转路由；
- 具体 UI 文案。

公共 topic 上的 `DataChange` 只作为脏标记使用。接收页面不得据此认定当前用户具备记录可见权限，也不得直接展示业务字段；需要展示数据时必须重新调用查询或详情接口，让原有租户、权限和字段控制兜底。

### 7.3 命令与事件分离

服务端广播使用事件语义，客户端发送使用命令语义。

```text
Event: 已经发生的事实
Command: 客户端请求服务端执行的动作
```

数据变化、通知、任务到达属于事件。发送 IM 消息、加入房间、更新在线状态属于命令。

命令必须经过权限、参数校验和业务处理，不因为来自 WebSocket 就绕过 HTTP 动作链路的治理原则。

## 8. 可靠性与一致性

### 8.1 第一阶段

第一阶段实时消息采用 user queue 的尽力投递：

```text
业务事务提交
  -> 形成 CommittedChangeSet
  -> 发布进程内事件
  -> 发起用户 user queue 接收完整 payload
  -> module / record topic 接收低敏脏标记
```

约束：

- 广播失败不得使已提交业务操作失败；
- 事务回滚不得广播数据变化；
- HTTP ActionResult 和实时广播共享同一份 `CommittedChangeSet`；
- 前端以 HTTP 回执作为发起动作的即时结果，以实时通道作为当前用户多端和跨用户脏标记信号；
- 公共 topic 不得携带 `facts` 或业务字段，不能依赖前端自行丢弃无权数据；
- 后续如需要跨用户完整 payload fan-out，必须按接收人过滤变化集合。

### 8.2 后续 Outbox

进入多实例、可靠补偿或审计要求后，数据变化事件演进为 outbox：

```text
业务数据 + outbox
  同一事务提交
      ↓
异步分发器
      ↓
STOMP broker / 外部 broker
      ↓
前端订阅者
```

Outbox 解决的是已提交事实的可靠分发，不改变 `CommittedChangeSet` 契约。

### 8.3 断线恢复

STOMP 重连只能恢复连接和订阅，不等同于业务消息可靠补发。

第一阶段断线期间可能错过广播。前端恢复连接后可以触发轻量状态校准，例如重新拉取当前页面依赖的查询。

后续如果需要精确补发，应引入：

- realtime message offset；
- data change outbox 查询；
- 用户通知未读队列；
- IM 消息存储。

不同业务能力按自身可靠性要求补齐，不把所有实时消息强行升级为同一可靠级别。

## 9. 代理与部署

WebSocket/STOMP 对网关和反向代理有明确要求。

部署环境必须支持：

- HTTP Upgrade；
- 长连接；
- 合理的 idle timeout；
- `Connection: Upgrade` 和 `Upgrade: websocket` 透传；
- TLS 终止后的 `wss` 转发；
- 多实例下的会话路由或外部 broker；
- 必要的连接数、消息速率和 topic 数监控。

如果特定环境无法稳定支持 WebSocket Upgrade，再评估 SockJS fallback 或 SSE adapter。该选择属于部署兼容，不改变平台实时消息契约。

## 10. 动静一体边界

实时通信底座不区分静态和动态业务来源。

静态链路：

```text
Java Service
  -> ActionResult / DataChange reporter
  -> CommittedChangeSet
  -> DataChangeRealtimePublisher
```

动态链路未来应通过元数据和运行时 adapter 进入同一契约：

```text
Dynamic action / Dynamic CRUD
  -> Runtime mutation adapter
  -> CommittedChangeSet
  -> DataChangeRealtimePublisher
```

动态侧不要求静态业务代码退回字符串硬编码模式。静态侧继续优先使用 Java 类型强引用、平台注解和稳定门面。

## 11. 代码边界

### 11.1 后端边界

允许依赖 STOMP 的范围：

```text
boot web adapter
platform realtime adapter
测试中的实时通信适配测试
```

不允许直接依赖 STOMP 的范围：

```text
业务 Service
Ability 核心层
ActionResult / DataChange 契约层
动态元数据核心模型
```

后端实时适配代码应依赖平台门面：

```text
DataChangeRealtimePublisher
NotificationPublisher
RealtimeMessagePublisher
```

普通业务 Service 不应为了数据变化广播直接依赖这些门面。通知、IM、协同等后续双向能力如果需要业务服务主动发送消息，应先沉淀对应领域门面，再由领域门面适配实时通信底座。

### 11.2 前端边界

允许直接依赖 `@stomp/stompjs` 的范围：

```text
web-core realtime transport
相关测试
```

不允许直接依赖 `@stomp/stompjs` 的范围：

```text
views
platform-components
dynamic-page-runtime
platform-workbench 业务视图
```

这些层只能依赖 `RealtimeClient`、`RealtimeChannel`、`RealtimeCommand` 和具体平台通道。

## 12. 契约测试

至少覆盖：

- 后端业务发布门面能把 `CommittedChangeSet` 映射到数据变化 destination；
- 业务代码不直接依赖 `SimpMessagingTemplate`；
- destination 常量集中定义；
- CONNECT 鉴权失败时连接不可用；
- SUBSCRIBE 无权限时不能订阅受限通道；
- 事务回滚不广播数据变化；
- HTTP ActionResult 和实时广播共享 `changeSetId`；
- 前端 `RealtimeClient` 能恢复订阅；
- 前端按 `changeSetId` 去重；
- 前端业务页面不直接 import `@stomp/stompjs`；
- logout、token 失效或租户切换时连接生命周期正确收敛。

涉及真实 WebSocket 握手、STOMP frame 和 broker 行为的测试应优先使用 Spring WebSocket 测试或集成测试，不长期只依赖 fake。

## 13. 阶段路线

### 13.1 第一阶段：修路

目标是打通统一实时通信基础设施，不铺大量业务。

范围：

1. 引入后端 WebSocket/STOMP 依赖。
2. 建立 `/ws/platform` endpoint。
3. 建立后端实时发布门面。
4. 建立数据变化广播 adapter。
5. 引入前端 `@stomp/stompjs`。
6. 建立前端 `RealtimeClient` 和通道契约。
7. 将数据变化通道接入现有 DataChange 消费机制。
8. 写明代理、心跳、重连、权限和后续 broker 边界。

不做：

- 完整 IM；
- 通知中心产品化；
- 动态表单实时治理；
- 外部 broker；
- 可靠 outbox；
- SockJS fallback。

### 13.2 第二阶段：平台能力接入

按收益逐步接入：

1. 数据变化跨页面刷新；
2. 用户级平台通知；
3. 工作台待办提醒；
4. 长任务进度事件；
5. 配置刷新提示；
6. 在线状态或协同信号。

每类能力都必须先定义业务 payload 和权限边界，再接入实时通道。

### 13.3 第三阶段：可靠性和规模

触发条件成熟后推进：

1. DataChange outbox；
2. 外部 broker relay；
3. 多实例广播；
4. 消息 offset 和补偿拉取；
5. SockJS fallback 或 SSE adapter；
6. 消息限流、监控、审计和运维面板。

### 13.4 第四阶段：双向业务产品

如果后续建设 IM 或协同：

1. 独立定义 IM 或协同领域模型；
2. 复用实时连接、鉴权、心跳、重连和 user destination；
3. 不复用 DataChange payload；
4. 根据业务需要引入消息存储、未读、撤回、房间权限和历史分页。

## 14. 与既有契约关系

实时通信底座与 [动作结果与数据变更契约](ACTION_RESULT_AND_DATA_CHANGE.md) 的关系：

```text
ActionResult / DataChange
  -> 形成 CommittedChangeSet
  -> HTTP 回执
  -> 实时通信底座广播
```

ActionResult 解决同步 HTTP 回执。实时通信底座解决异步订阅者通知。两者共享数据变化事实，但职责不同。

前端 ActionResult reaction 和实时 data change reaction 应使用相同 `changeSetId` 去重，并共同遵守“前端决定 UI 响应”的边界。

## 15. 参考依据

- [Spring Framework WebSocket/STOMP 官方文档](https://docs.spring.io/spring-framework/reference/web/websocket/stomp.html)；
- [Spring user destination 官方文档](https://docs.spring.io/spring-framework/reference/web/websocket/stomp/user-destination.html)；
- [StompJS heartbeat、auto reconnect 和 lifecycle 文档](https://stomp-js.github.io/guide/stompjs/using-stompjs-v5.html)；
- [SockJS 在 Spring 中的 fallback 定位](https://docs.spring.io/spring-framework/reference/web/websocket/fallback.html)；
- 当前项目 ActionResult / DataChange 契约和前端 web-core 分层。
