# 实时业务提醒后端接入

本文面向业务后端开发者，说明如何通过平台向当前在线用户发送实时业务提醒，以及如何承接提醒按钮触发的业务命令。

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

发布者没有用户身份不影响接收人解析：平台会在投递时以每个在线接收者自己的用户和租户上下文解析 IAM 范围。提醒命令被点击后，业务 handler 也只以点击者的当前身份执行；它不能信任或继承后台任务的身份。

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
                        new BusinessNotificationCommandAction(
                                "approve", "同意", "purchase.approval.approve",
                                Map.of("orderId", orderId), false, null, true),
                        new BusinessNotificationCommandAction(
                                "reject", "拒绝", "purchase.approval.reject",
                                Map.of("orderId", orderId), true, "确认拒绝该采购申请？", true))));
    }
}
```

`id` 是本次实时提醒的稳定标识。首期只用于前端队列和命令调用关联；它不是已读、处理或补发记录的主键。

## 接收范围

`BusinessNotificationRecipients` 的所有维度按并集处理，并按 `userId` 去重：

| 字段 | 含义 |
| --- | --- |
| `systemWide` | 跨全部租户、向所有当前在线用户发送。仅用于真正的全局平台公告。 |
| `tenantIds` | 指定租户内的当前在线用户。 |
| `organizationIds` | 指定机构的当前在线用户。 |
| `departmentIds` | 指定部门任职人员的当前在线用户。 |
| `positionIds` | 指定岗位任职人员的当前在线用户。 |
| `userIds` | 指定用户。 |

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

### 业务命令

使用 `BusinessNotificationCommandAction` 声明一个稳定 command：

```java
new BusinessNotificationCommandAction(
        "approve", "同意", "purchase.approval.approve",
        Map.of("orderId", orderId), false, null, true)
```

前端调用统一入口：

```text
POST /platform/notifications/commands/{command}
```

这是已登录用户发起的普通业务命令入口，不是前端发布消息的入口，也不证明调用者收到过对应提醒。

业务模块实现同名 `BusinessNotificationCommandHandler`：

```java
@Component
public class PurchaseApprovalNotificationHandler implements BusinessNotificationCommandHandler {
    private final PurchaseApprovalService approvalService;

    public PurchaseApprovalNotificationHandler(PurchaseApprovalService approvalService) {
        this.approvalService = approvalService;
    }

    @Override
    public String command() {
        return "purchase.approval.approve";
    }

    @Override
    public Object handle(BusinessNotificationCommandInvocation invocation) {
        String orderId = String.valueOf(invocation.arguments().get("orderId"));
        return approvalService.approveFromCurrentOperator(orderId);
    }
}
```

同一 command 只能注册一个 handler，重复注册会在启动期失败。

命令 payload 可以被客户端修改，因此 handler 必须把 `arguments` 视为不可信输入，并重新验证：

1. 当前操作者是否具备业务动作权限；
2. 目标记录是否属于可访问的数据范围；
3. 当前状态是否仍允许该动作；
4. 乐观锁、幂等和审计要求是否满足。

接收提醒不代表自动获得执行动作的权限。

## 不可关闭提醒

`dismissible=false` 只影响在线 Workbench：卡片不提供关闭按钮。它不表示平台已经建立待办、处理确认或可靠送达能力。

不可关闭提醒应至少包含一个真正完成业务处理的命令动作，并将该动作设置为 `dismissOnSuccess=true`。纯“查看”动作不应被视为完成处理。

## 限制与演进

当前实现只遍历本进程的实时连接。因此：

- 用户离线、刷新页面或断线期间不会收到，也不会补发；
- 同一用户的多个在线浏览器会同时收到；
- 使用 Spring simple broker 的多实例部署无法跨实例覆盖全部在线用户；
- 需要可靠送达、已读/已办、重试、撤回、历史查询或跨实例广播时，应升级为持久化通知模型与外部 broker / outbox，而不是在业务代码自行补偿。
