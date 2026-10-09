package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowArchiveServiceTest {
    @org.junit.jupiter.api.BeforeEach
    void installWorkflowMutationHost() { WorkflowTestMutationHost.install(); }
    @org.junit.jupiter.api.AfterEach
    void resetWorkflowMutationHost() { WorkflowTestMutationHost.reset(); }

    private final MemoryWorkflowInstanceDao instanceDao = new MemoryWorkflowInstanceDao();
    private final MemoryWorkflowNodeInstanceDao nodeDao = new MemoryWorkflowNodeInstanceDao();
    private final MemoryWorkflowRouteInstanceDao routeDao = new MemoryWorkflowRouteInstanceDao();
    private final MemoryWorkflowTaskDao taskDao = new MemoryWorkflowTaskDao();
    private final MemoryWorkflowEventDao eventDao = new MemoryWorkflowEventDao();
    private final MemoryWorkflowHistoryInstanceDao historyDao = new MemoryWorkflowHistoryInstanceDao();
    private final WorkflowArchiveService service = new WorkflowArchiveService(
            instanceDao, nodeDao, routeDao, taskDao, eventDao, historyDao);

    @Test
    void shouldMoveCurrentRuntimeObjectsToHistorySnapshotAndDeleteRuntimeRows() {
        WorkflowInstance instance = instance();
        WorkflowNodeInstance node = node();
        WorkflowRouteInstance route = route();
        WorkflowTask task = task();
        WorkflowEvent event = event();
        instanceDao.insert(instance);
        nodeDao.insert(node);
        routeDao.insert(route);
        taskDao.insert(task);
        eventDao.insert(event);

        WorkflowHistoryInstance history = service.archiveCurrentInstance(instance,
                WorkflowArchiveReason.RECALLED, Instant.parse("2026-06-05T04:00:00Z"));

        assertThat(historyDao.findById("instance-1")).isSameAs(history);
        assertThat(history.getArchiveReason()).isEqualTo(WorkflowArchiveReason.RECALLED);
        assertThat(history.getArchivedAt()).isEqualTo(Instant.parse("2026-06-05T04:00:00Z"));
        assertThat(history.getSemanticJson()).isEqualTo("{\"nodes\":[\"approve\"]}");
        assertThat(history.getLayoutJson()).isEqualTo("{\"zoom\":1}");
        assertThat(instanceDao.findById("instance-1")).isNull();
        assertThat(nodeDao.findById("node-1")).isNull();
        assertThat(routeDao.findById("route-1")).isNull();
        assertThat(taskDao.findById("task-1")).isNull();
        assertThat(eventDao.findById("event-1")).isNull();

        WorkflowHistorySnapshot snapshot = service.parseSnapshot(history);
        assertThat(snapshot.instance().getId()).isEqualTo("instance-1");
        assertThat(snapshot.nodes()).extracting(WorkflowNodeInstance::getId).containsExactly("node-1");
        assertThat(snapshot.routes()).extracting(WorkflowRouteInstance::getId).containsExactly("route-1");
        assertThat(snapshot.routes().getFirst().getSelectedReason()).isEqualTo("choose manual route");
        assertThat(snapshot.tasks()).extracting(WorkflowTask::getId).containsExactly("task-1");
        assertThat(snapshot.events()).extracting(WorkflowEvent::getId).containsExactly("event-1");
    }

    private WorkflowInstance instance() {
        WorkflowInstance instance = new WorkflowInstance();
        instance.setId("instance-1");
        instance.setTenantId("tenant-1");
        instance.setDefinitionId("definition-1");
        instance.setWorkflowVersionId("version-1");
        instance.setVersionNo(1);
        instance.setModuleAlias("sales.contract");
        instance.setRecordId("record-1");
        instance.setApprovalEnabled(true);
        instance.setApprovalStatus(WorkflowApprovalStatus.REVOKED);
        instance.setInstanceStatus(WorkflowInstanceStatus.REVOKED);
        instance.setStartedBy("starter-1");
        instance.setStartedAt(Instant.parse("2026-06-05T01:00:00Z"));
        instance.setLastActionCode("revoke");
        instance.setLastOperatedAt(Instant.parse("2026-06-05T04:00:00Z"));
        instance.setSnapshotText("{}");
        instance.setSemanticJson("{\"nodes\":[\"approve\"]}");
        instance.setLayoutJson("{\"zoom\":1}");
        return instance;
    }

    private WorkflowNodeInstance node() {
        WorkflowNodeInstance node = new WorkflowNodeInstance();
        node.setId("node-1");
        node.setInstanceId("instance-1");
        node.setNodeKey("approve");
        node.setNodeRunId("approve:1");
        node.setNodeType(WorkflowNodeType.APPROVAL);
        node.setNodeStatus(WorkflowNodeStatus.CANCELED);
        return node;
    }

    private WorkflowRouteInstance route() {
        WorkflowRouteInstance route = new WorkflowRouteInstance();
        route.setId("route-1");
        route.setInstanceId("instance-1");
        route.setRouteKey("route-1");
        route.setRouteRunId("route-1:1");
        route.setSourceNodeKey("start");
        route.setTargetNodeKey("approve");
        route.setRouteStatus(WorkflowRouteStatus.CANCELED);
        route.setSelectedReason("choose manual route");
        return route;
    }

    private WorkflowTask task() {
        WorkflowTask task = new WorkflowTask();
        task.setId("task-1");
        task.setInstanceId("instance-1");
        task.setTaskKind(WorkflowTaskKind.APPROVAL);
        task.setTaskStatus(WorkflowTaskStatus.CANCELED);
        return task;
    }

    private WorkflowEvent event() {
        WorkflowEvent event = new WorkflowEvent();
        event.setId("event-1");
        event.setInstanceId("instance-1");
        event.setEventType(WorkflowEventType.INSTANCE_REVOKED);
        event.setActionCode("revoke");
        event.setOccurredAt(Instant.parse("2026-06-05T04:00:00Z"));
        return event;
    }

    private static final class MemoryWorkflowInstanceDao extends TestMemoryDao<WorkflowInstance>
            implements WorkflowInstanceDao {
    }

    private static final class MemoryWorkflowNodeInstanceDao extends TestMemoryDao<WorkflowNodeInstance>
            implements WorkflowNodeInstanceDao {
    }

    private static final class MemoryWorkflowRouteInstanceDao extends TestMemoryDao<WorkflowRouteInstance>
            implements WorkflowRouteInstanceDao {
    }

    private static final class MemoryWorkflowTaskDao extends TestMemoryDao<WorkflowTask>
            implements WorkflowTaskDao {
    }

    private static final class MemoryWorkflowEventDao extends TestMemoryDao<WorkflowEvent>
            implements WorkflowEventDao {
    }

    private static final class MemoryWorkflowHistoryInstanceDao extends TestMemoryDao<WorkflowHistoryInstance>
            implements WorkflowHistoryInstanceDao {
    }
}
