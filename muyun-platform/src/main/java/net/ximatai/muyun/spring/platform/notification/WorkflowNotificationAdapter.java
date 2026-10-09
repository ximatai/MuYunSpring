package net.ximatai.muyun.spring.platform.notification;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.workflow.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

/** Consumes committed workflow facts. Durable tasks remain authoritative when recipients are offline. */
@Service
public class WorkflowNotificationAdapter implements WorkflowRuntimePlugin {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(WorkflowNotificationAdapter.class);
    private final WorkflowTaskDao tasks;
    private final WorkflowNodeInstanceDao nodes;
    private final WorkflowInstanceDao instances;
    private final ObjectProvider<BusinessNotificationDelivery> notifications;
    public WorkflowNotificationAdapter(WorkflowTaskDao tasks, WorkflowNodeInstanceDao nodes, WorkflowInstanceDao instances,
                                       ObjectProvider<BusinessNotificationDelivery> notifications) {
        this.tasks=tasks; this.nodes=nodes; this.instances=instances; this.notifications=notifications;
    }
    @Override public String pluginKey() {return "platform.workflow.notifications";}
    @Override public WorkflowRuntimePluginDispatchTiming dispatchTiming() {return WorkflowRuntimePluginDispatchTiming.AFTER_COMMIT;}
    @Override public Set<WorkflowRuntimePluginEventType> eventTypes() {
        return Set.of(WorkflowRuntimePluginEventType.AFTER_SUBMIT, WorkflowRuntimePluginEventType.AFTER_APPROVE,
                WorkflowRuntimePluginEventType.AFTER_COMPLETE, WorkflowRuntimePluginEventType.AFTER_TRANSFER,
                WorkflowRuntimePluginEventType.AFTER_REJECT, WorkflowRuntimePluginEventType.AFTER_ROLLBACK,
                WorkflowRuntimePluginEventType.AFTER_REVOKE, WorkflowRuntimePluginEventType.AFTER_RESET,
                WorkflowRuntimePluginEventType.AFTER_TERMINATE);
    }
    @Override public void handle(WorkflowRuntimePluginContext context) {
        if(context.instance()==null) return;
        try(var tenant=TenantContext.use(context.instance().getTenantId())) {
            var delivery=notifications.getIfAvailable();
            if(delivery==null) return;
            var instance=instances.findById(context.instanceId());
            if(instance==null || !Objects.equals(instance.getTenantId(), context.instance().getTenantId())) return;
            var scope=Criteria.of().eqNullable("tenantId",instance.getTenantId()).eq("instanceId",instance.getId());
            for(var task:tasks.query(scope.eq("taskStatus",WorkflowTaskStatus.TODO),new PageRequest(0,Integer.MAX_VALUE))) {
                var node=nodes.findById(task.getNodeInstanceId());
                if(task.getTaskKind()!=WorkflowTaskKind.NOTICE && task.getTaskKind()!=WorkflowTaskKind.RESUBMIT
                        && (instance.getInstanceStatus()!=WorkflowInstanceStatus.RUNNING || node==null || node.getNodeStatus()!=WorkflowNodeStatus.ACTIVE)) continue;
                Set<String> recipients=new LinkedHashSet<>();
                if(task.getAssigneeId()!=null) recipients.add(task.getAssigneeId());
                if(Boolean.TRUE.equals(task.getPrincipalCanProcess()) && task.getDelegatedFromUserId()!=null) recipients.add(task.getDelegatedFromUserId());
                if(recipients.isEmpty()) continue;
                String title=task.getTaskKind()==WorkflowTaskKind.NOTICE ? "审批通知" : task.getTaskKind()==WorkflowTaskKind.RESUBMIT ? "申请需重新提交" : "有新的审批或业务待办";
                delivery.deliver(notification("workflow-task-"+task.getId(),title,
                        node==null ? instance.getModuleAlias() : node.getNodeTitle(),instance,recipients));
            }
            if(instance.getStartedBy()!=null && (instance.getInstanceStatus()!=WorkflowInstanceStatus.RUNNING
                    || instance.getApprovalStatus()==WorkflowApprovalStatus.APPROVED)) {
                String outcome=instance.getInstanceStatus()!=WorkflowInstanceStatus.RUNNING
                        ? instance.getInstanceStatus().getTitle() : instance.getApprovalStatus().getTitle();
                delivery.deliver(notification("workflow-result-"+instance.getId()+"-"+outcome,"流程状态："+outcome,
                        instance.getModuleAlias(),instance,Set.of(instance.getStartedBy())));
            }
        } catch (RuntimeException failure) {
            // The transaction has committed. A transient reminder failure cannot undo the business action.
            LOG.warn("workflow notification delivery failed for instance {}", context.instanceId(), failure);
        }
    }
    private BusinessNotification notification(String id,String title,String subtitle,WorkflowInstance instance,Set<String> userIds) {
        var recipients=new BusinessNotificationRecipients(false,List.of(),List.of(),List.of(),List.of(),List.copyOf(userIds));
        Map<String,String> query=instance.getTenantId()==null ? Map.of() : Map.of("tenantId",instance.getTenantId());
        return new BusinessNotification(id,"workflow",title,subtitle,"请在审批工作台查看办理详情",true,recipients,
                List.of(new BusinessNotificationNavigateAction("open","打开审批工作台","iam.workflow_workbench",null,"LIST",query,true)),
                BusinessNotificationTone.DEFAULT,Instant.now());
    }
}
