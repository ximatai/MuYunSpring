package net.ximatai.muyun.spring.platform.notification;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.workflow.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.ArrayList;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkflowNotificationAdapterTest {
    @AfterEach void cleanup() {TransactionSynchronizationManager.clear(); TenantContext.clear();}
    @Test void reminderIsDeliveredAfterCommitOnlyToCurrentAssigneeAndAllowedPrincipal() {
        var fixture=new Fixture(); fixture.task.setDelegatedFromUserId("principal"); fixture.task.setPrincipalCanProcess(true);
        begin(); fixture.dispatch(); assertThat(fixture.delivered).isEmpty();
        try(var foreign=TenantContext.use("another")) {
            commit(); assertThat(TenantContext.currentTenantId()).contains("another");
        }
        assertThat(fixture.delivered).hasSize(1);
        var notice=fixture.delivered.getFirst();
        assertThat(notice.recipients().userIds()).containsExactlyInAnyOrder("assignee","principal");
        assertThat(notice.recipients().tenantIds()).isEmpty();
        assertThat(notice.recipients().systemWide()).isFalse();
        assertThat(notice.actions()).hasSize(1);
        assertThat(notice.actions().getFirst()).isInstanceOfSatisfying(BusinessNotificationNavigateAction.class, action -> {
            assertThat(action.moduleAlias()).isEqualTo("iam.workflow_workbench");
            assertThat(action.query()).containsEntry("tenantId", "tenant");
        });
        assertThat(fixture.observedTenants).containsExactly("tenant");
    }
    @Test void rollbackAndAutomaticallyCompletedIntermediateTaskDoNotProduceGhostReminders() {
        var fixture=new Fixture(); begin(); fixture.dispatch();
        TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
        TransactionSynchronizationManager.clear(); assertThat(fixture.delivered).isEmpty();
        begin(); fixture.dispatch(); fixture.task.setTaskStatus(WorkflowTaskStatus.DONE); commit();
        assertThat(fixture.delivered).isEmpty();
    }
    @Test void inactiveBusinessNodeIsNotOfferedAndCompletedOutcomeTargetsInitiator() {
        var fixture=new Fixture(); fixture.node.setNodeStatus(WorkflowNodeStatus.COMPLETED);
        fixture.instance.setStartedBy("initiator"); fixture.instance.setInstanceStatus(WorkflowInstanceStatus.COMPLETED);
        fixture.instance.setApprovalStatus(WorkflowApprovalStatus.APPROVED);
        fixture.dispatch();
        assertThat(fixture.delivered).hasSize(1);
        assertThat(fixture.delivered.getFirst().recipients().userIds()).containsExactly("initiator");
        assertThat(fixture.delivered.getFirst().title()).contains("完成");
    }
    @Test void terminatedInstanceDoesNotAdvertiseAnActiveOrdinaryTask() {
        var fixture=new Fixture(); fixture.instance.setInstanceStatus(WorkflowInstanceStatus.TERMINATED);
        fixture.dispatch(); assertThat(fixture.delivered).isEmpty();
    }
    @Test void reminderFailureCannotFailAnAlreadyCommittedBusinessAction() {
        var fixture=new Fixture();
        when(fixture.instances.findById("instance")).thenThrow(new IllegalStateException("temporary database failure"));
        begin(); fixture.dispatch();
        assertThatCode(this::commit).doesNotThrowAnyException();
        assertThat(fixture.delivered).isEmpty();
    }
    private void begin() {TransactionSynchronizationManager.initSynchronization(); TransactionSynchronizationManager.setActualTransactionActive(true);}
    private void commit() {TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit); TransactionSynchronizationManager.clear();}
    static class Fixture {
        final WorkflowTask task=new WorkflowTask(); final WorkflowNodeInstance node=new WorkflowNodeInstance(); final WorkflowInstance instance=new WorkflowInstance();
        final List<BusinessNotification> delivered=new ArrayList<>(); final List<String> observedTenants=new ArrayList<>();
        final WorkflowRuntimePluginDispatcher dispatcher;
        final WorkflowInstanceDao instances=mock(WorkflowInstanceDao.class);
        Fixture() {
            var tasks=mock(WorkflowTaskDao.class); var nodes=mock(WorkflowNodeInstanceDao.class);
            instance.setId("instance"); instance.setTenantId("tenant"); instance.setModuleAlias("demo.purchase"); instance.setInstanceStatus(WorkflowInstanceStatus.RUNNING);
            instance.setApprovalStatus(WorkflowApprovalStatus.PROCESSING);
            task.setId("task"); task.setTenantId("tenant"); task.setInstanceId("instance"); task.setNodeInstanceId("node"); task.setTaskKind(WorkflowTaskKind.APPROVAL); task.setTaskStatus(WorkflowTaskStatus.TODO); task.setAssigneeId("assignee");
            node.setId("node"); node.setNodeTitle("Review"); node.setNodeStatus(WorkflowNodeStatus.ACTIVE);
            when(instances.findById("instance")).thenReturn(instance); when(nodes.findById("node")).thenReturn(node);
            when(tasks.query(any(Criteria.class),any(PageRequest.class))).thenAnswer(call -> { observedTenants.add(TenantContext.currentTenantId().orElse("null")); return task.getTaskStatus()==WorkflowTaskStatus.TODO ? List.of(task) : List.of(); });
            var beans=new StaticListableBeanFactory(); beans.addBean("notifications",(BusinessNotificationDelivery)delivered::add);
            dispatcher=new WorkflowRuntimePluginDispatcher(List.of(new WorkflowNotificationAdapter(tasks,nodes,instances,beans.getBeanProvider(BusinessNotificationDelivery.class))));
        }
        void dispatch() {dispatcher.dispatch(new WorkflowRuntimePluginContext(WorkflowRuntimePluginEventType.AFTER_SUBMIT,"submit",instance.getModuleAlias(),"record","instance",null,null,"actor",null,null,null,null,instance,null,null));}
    }
}
