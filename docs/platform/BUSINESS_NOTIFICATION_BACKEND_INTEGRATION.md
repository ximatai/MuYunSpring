# 实时业务提醒后端接入

本文面向业务后端开发者，说明如何通过平台向当前在线用户发送实时业务提醒，以及如何承接提醒按钮触发的业务记录动作。

实时业务提醒是短生命周期提示，不是消息中心、站内信或工作流待办。平台不持久化消息、不补发离线消息，也不记录用户是否已处理。

## 接入边界

业务代码只依赖并调用 Platform 的 `BusinessNotificationService`，不得直接使用 STOMP、`SimpMessagingTemplate`、destination 字符串或 WebSocket session。
平台不提供 HTTP 消息发布接口；消息只能由后端业务代码或后台任务发布。

```text
业务 Service
  -> BusinessNotificationService
  -> 平台在事务提交后解析当前在线接收人
  -> /user/queue/platform/business-notifications
  -> Workbench 统一展现
```

`publish` 在存在 Spring 事务时会延后到 `afterCommit` 投递；事务回滚时不会发送。业务代码不需要自行注册事务回调。

## 本次页面内的临时提醒

铃铛和展开列表只汇总本次页面已经收到或业务主动恢复的有效提醒。相同 `id` 以输入集合中最后一条为准，最新收到且可处理的提醒位于右下堆叠的左上方；其余只露出卡片边缘。

- 叉号从**本次页面的提醒展示集合**移除卡片，铃铛与临时列表也不再显示它，不调用业务处理接口。关闭不代表业务已处理。
- 单条有效提醒直接展示卡片；多条减少为单条时自动恢复单卡片状态，不显示铃铛。多条时，铃铛角标是去重后尚未关闭、尚未退出有效期的本次提醒数，不是未读数或历史总数。铃铛直接切换卡片堆叠与逐条展开，不增加消息面板外框；提醒较多时可滚动。铃铛提示刷新或退出后清空，展开时不重复显示堆叠卡片。
- `executeAction` 返回成功，且动作显式声明 `dismissOnSuccess=true` 时，组件自动移除提醒并发出 `dismiss`；取消或失败返回 `false`，保持提醒以便重试。未声明移除的导航或中间业务动作不被平台推断为完成。
- 业务撤回时从传入的 `notifications` 集合移除对应项；`expiresAt` 到期或无效时组件自动排除。业务恢复查询、权限、最终有效性和业务处理结果仍由业务维护。
- 当前页面生命周期内，关闭或完成的通知 ID 保留临时去重标记，重复推送不会再次弹出；新的业务事实应使用新 ID。这些标记不进入浏览器存储或后端。账号或执行身份切换时，宿主应通过 `key` 重建组件，并清空输入集合。

业务待办仍须在相应业务页面查看和处理。平台不提供提醒持久化、离线补发、历史、已读未读或处理回执；刷新或重新打开页面不会自行恢复提醒。

## 遮罩层上的持续可见通知与响应期限

普通通知默认在详情抽屉、编辑弹窗等交互层打开时避让，但仍保留本次提醒集合。有响应时限的来电、邀请等事实可显式选择持续可见：

```java
notification.withDisplayPolicy(BusinessNotificationDisplayPolicy.PERSISTENT)
        .withExpiresAt(deadline);
```

- `displayPolicy` 在浏览器协议中取 `standard` 或 `persistent`，省略时按 `standard` 处理。持续可见不表示消息持久化，不增加离线补发或确认回执。
- 持续可见卡片复用标准外观、声明式动作和可选附件区，同样参与临时堆叠；普通抽屉、弹窗打开时仍可通过铃铛展开并处理持续提醒，普通提醒继续避让，安全退出遮罩保持更高优先级。
- 可选 `expiresAt` 为绝对时间。到期后从本次有效提醒集合移除，并禁止继续触发动作；提交前以及操作确认后再次检查期限。未提供期限的既有通知行为保持不变。
- 同一通知处理中禁止再次触发或选择竞争动作，其他通知可独立处理；持续可见通知的记录动作成功后，本次卡片不再重复执行记录命令，保留的卡片仍可导航查看详情。新的业务事实应使用新的通知 ID。
- 前端 `executeAction` 回调可返回 `false` 表示取消或失败，以允许重试；正常完成可返回 `true`，既有返回 `void` 的回调继续兼容。

页面与未保存表单在处理通知时保持挂载。动作若声明导航，则仍按平台页签导航处理；通知展示策略不会强制关闭已有抽屉或弹窗。

浏览器期限与重复点击保护只负责交互防护。业务动作服务端仍须按业务记录中的期限、当前状态与权限执行原子校验，防止请求在传输期间过期、跨客户端重复提交或绕过界面执行；平台不为短生命周期通知建立新的业务确认台账。

## 后台任务与无用户上下文

`BusinessNotificationService` 不要求存在 `CurrentUserContext`，因此定时任务、批处理、系统补偿和启动治理任务都可以发布提醒。

后台任务没有“当前用户”也通常没有可自动推断的“本租户”，所以必须显式声明目标范围：

- 向一个或多个租户发送时，填写 `tenantIds`；
- 面向全部在线用户发送时，明确使用 `systemWide=true`；
- 机构、部门、岗位和用户范围也全部使用稳定 ID 显式给出；
- 空范围不会投递，平台不会根据任务线程猜测租户或广播范围。

例如一个系统任务向指定租户发送提醒：

```java
notificationService.publish(new BusinessNotification(
        Ids.newId(), "platform.maintenance-window", "维护提醒", null,
        "系统将在今晚 22:00 维护。", true,
        new BusinessNotificationRecipients(false, List.of(tenantId), List.of(), List.of(), List.of(), List.of()),
        List.of()));
```

发布者没有用户身份不影响接收人解析：平台会在投递时以每个在线接收者自己的用户和租户上下文解析 IAM 范围。提醒动作被点击后，业务入口也只以点击者的当前身份执行；它不能信任或继承后台任务的身份。

## 发布提醒

注入 `BusinessNotificationService`，构造 `BusinessNotification` 后发布：

```java
@Service
public class PurchaseApprovalService {
    private final BusinessNotificationService notificationService;

    public PurchaseApprovalService(BusinessNotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Transactional
    public void submitForApproval(String orderId, String approverUserId) {
        // 保存单据、推进审批状态……

        notificationService.publish(new BusinessNotification(
                Ids.newId(),
                "purchase.approval-arrived",
                "有新的采购申请需要审批",
                "采购单 " + orderId,
                "请及时查看并处理。",
                false,
                new BusinessNotificationRecipients(
                        false,
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(approverUserId)),
                List.of(
                        new BusinessNotificationNavigateAction(
                                "view", "查看", "purchase.order", orderId, "DETAIL", Map.of(), false),
                        new BusinessNotificationRecordAction(
                                "approve", "同意", "purchase.order", orderId,
                                "approve", Map.of(), false, null, true),
                        new BusinessNotificationRecordAction(
                                "reject", "拒绝", "purchase.order", orderId,
                                "reject", Map.of(), true, "确认拒绝该采购申请？", true))));
    }
}
```

`id` 是本次实时提醒的稳定标识。首期只用于前端队列关联；它不是已读、处理或补发记录的主键。

## 标准展示信息

`BusinessNotification` 可在原有构造参数之后提供可选的 `tone` 和 `occurredAt`，让 Workbench 使用统一的卡片强调色、状态点和标题右侧时间。`SUCCESS` 沿用平台主色，`DANGER` 使用告警色；它们只描述展示，不改变投递范围、动作权限或处理语义；不传时保持普通提醒的默认外观。通知卡片还提供由前端消费者选择性启用的右侧附属区，用于地图、缩略预览等受控展示内容，不向后端通知协议传递任意组件或脚本。

```java
notificationService.publish(new BusinessNotification(
        Ids.newId(),
        "helmet.presence.online",
        "安全帽已上线",
        "设备：HELMET-LOCAL-001",
        "已连接到设备接入服务。",
        true,
        new BusinessNotificationRecipients(false, List.of(), List.of(), List.of(), List.of(), List.of(userId)),
        List.of(new BusinessNotificationNavigateAction(
                "view", "查看设备", "mr.device", deviceId, "DETAIL", Map.of(), false)),
        BusinessNotificationTone.SUCCESS,
        Instant.now()));
```

可用色调为 `DEFAULT`、`SUCCESS` 和 `DANGER`；浏览器协议对应 `default`、`success`、`danger`。`occurredAt` 使用 `Instant`，由 Workbench 按当前用户时区显示。既有八参数构造方式继续有效，普通业务不需要为此改造。

## 接收范围

`BusinessNotificationRecipients` 的所有维度按并集处理，并按 `userId` 去重：

| 字段              | 含义                                                           |
| ----------------- | -------------------------------------------------------------- |
| `systemWide`      | 跨全部租户、向所有当前在线用户发送。仅用于真正的全局平台公告。 |
| `tenantIds`       | 指定租户内的当前在线用户。                                     |
| `organizationIds` | 指定机构的当前在线用户。                                       |
| `departmentIds`   | 指定部门任职人员的当前在线用户。                               |
| `positionIds`     | 指定岗位任职人员的当前在线用户。                               |
| `userIds`         | 指定用户。                                                     |

例如同时指定部门和用户时，属于该部门 **或** 在用户列表中的在线用户都会收到。空范围不会投递。

范围只在服务端用于解析接收人，不会进入浏览器 payload。部门和岗位由 IAM 任职关系解析；停用、失效或已断开实时连接的用户不会收到。

## 动作设计

提醒动作必须是声明式对象，不能传递 JavaScript、任意前端回调或未经约束的 URL。

### 页面导航

使用 `BusinessNotificationNavigateAction` 打开标准模块页面：

```java
new BusinessNotificationNavigateAction(
        "view", "查看", "purchase.order", orderId, "DETAIL", Map.of(), false)
```

前端将其解析为工作台页面。`dismissOnSuccess=false` 表示仅查看不会移除提醒；对于不可关闭提醒，这通常是正确默认值。

### 业务记录动作

使用 `BusinessNotificationRecordAction` 声明记录所属模块、动作目录中的 `actionCode` 和记录 ID：

```java
new BusinessNotificationRecordAction(
        "approve", "同意", "purchase.order", orderId,
        "approve", Map.of(), false, null, true)
```

前端复用标准记录动作入口，并将 `arguments` 置于请求的 `payload`：

```text
POST /purchase.order/approve/{orderId}
```

动作由业务模块的动作目录、Controller 和领域服务实现；平台不提供业务命令分发器，也不接受消息携带的任意 URL 或自定义 endpoint。请求仍须把当前操作者和输入视为不可信，并重新验证：

1. 当前操作者是否具备业务动作权限；
2. 目标记录是否属于可访问的数据范围；
3. 当前状态是否仍允许该动作；
4. 乐观锁、幂等和审计要求是否满足。

接收提醒不代表自动获得执行动作的权限。

## 不可关闭提醒

`dismissible=false` 只影响在线 Workbench：卡片不提供关闭按钮。它不表示平台已经建立待办、处理确认或可靠送达能力。

不可关闭提醒应至少包含一个真正完成业务处理的记录动作，并将该动作设置为 `dismissOnSuccess=true`。纯“查看”动作不应被视为完成处理。

## 限制与演进

当前实现只遍历本进程的实时连接。因此：

- 用户离线、刷新页面或断线期间不会收到，也不会补发；
- 同一用户的多个在线浏览器会同时收到；
- 使用 Spring simple broker 的多实例部署无法跨实例覆盖全部在线用户；
- 可靠送达、已读/已办、历史查询与跨实例广播不属于本次页面提醒的契约，需在相应业务需求出现后独立评估；当前集合的撤回和恢复由业务维护，不要求为此建设消息持久化能力。
