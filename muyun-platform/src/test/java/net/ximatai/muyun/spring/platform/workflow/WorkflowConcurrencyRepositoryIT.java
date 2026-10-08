package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.spring.ability.MutationTransactionOperator;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import net.ximatai.muyun.spring.common.platform.ModuleRecordFacts;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.support.PlatformPostgresIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import javax.sql.DataSource;
import java.lang.reflect.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

@SpringBootTest(classes = WorkflowConcurrencyRepositoryIT.Host.class)
class WorkflowConcurrencyRepositoryIT extends PlatformPostgresIntegrationTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }
    @Autowired WorkflowSubmitFacade submissions;
    @Autowired WorkflowTaskActionService actions;
    @Autowired WorkflowDefinitionDao definitions;
    @Autowired WorkflowVersionDao versions;
    @Autowired WorkflowNodeDefinitionDao nodeDefinitions;
    @Autowired WorkflowLinkDefinitionDao links;
    @Autowired WorkflowRouteInstanceDao routeRuns;
    @Autowired WorkflowInstanceDao instances;
    @Autowired WorkflowNodeInstanceDao nodeRuns;
    @Autowired WorkflowTaskDao tasks;
    @Autowired WorkflowEventDao events;
    @Autowired WorkflowHistoryInstanceDao histories;
    @Autowired WorkflowArchiveService archives;
    @Autowired WorkflowApprovalSummaryWriter summaries;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired DataSource dataSource;
    @Autowired Pauses pauses;
    private String module;
    private final String tenant = "concurrent-tenant";

    @BeforeEach void configureHost() {
        var transactions = new TransactionTemplate(transactionManager);
        PlatformAbilityRuntime.configureMutationTransactionOperator(new MutationTransactionOperator() {
            @Override public <T> T execute(java.util.function.Supplier<T> work) { return transactions.execute(status -> work.get()); }
            @Override public void lock(String scope, String key) {
                if (!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("transaction required");
                new JdbcTemplate(dataSource).queryForObject("select pg_advisory_xact_lock(hashtextextended(?, 0))",
                        Object.class, scope.length() + ":" + scope + ":" + key);
            }
        });
        module = "test.concurrent_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        pauses.reset();
        createDefinition();
    }
    @AfterEach void resetHost() { pauses.release.countDown(); PlatformAbilityRuntime.resetMutationTransactionOperator(); TenantContext.clear(); }

    @Test void simultaneousSubmissionsProduceExactlyOneRunningApprovalInstance() throws Exception {
        pauses.submitRecord = "record";
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> {
                Thread.currentThread().setName("first-submit");
                try (var context = TenantContext.use(tenant)) { return submissions.submit(WorkflowSubmitRequest.approval(module, "record").withOperator("initiator")); }
            });
            assertThat(pauses.reached.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> second = executor.submit(() -> {
                try (var context = TenantContext.use(tenant)) { return submissions.submit(WorkflowSubmitRequest.approval(module, "record").withOperator("initiator")); }
            });
            try { assertThatThrownBy(() -> second.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
            finally { pauses.release.countDown(); }
            first.get(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> second.get(10, TimeUnit.SECONDS)).hasCauseInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformException.class)
                    .hasStackTraceContaining("already running");
        }
        assertThat(instances.query(Criteria.of().eq("moduleAlias", module).eq("recordId", "record"), new PageRequest(0, 10))).hasSize(1);
    }

    @Test void rejectedRestartArchivesOnlyOnSuccessfulReplacementAndCannotBeTakenOver() {
        try (var context = TenantContext.use(tenant)) {
            var first = submissions.submit(WorkflowSubmitRequest.approval(module, "restart-record").withOperator("initiator"));
            var todo = tasks.query(Criteria.of().eq("instanceId", first.instance().getId()).eq("taskStatus", WorkflowTaskStatus.TODO), PageRequest.of(1, 10)).getFirst();
            actions.reject(WorkflowTaskActionRequest.reject(todo.getId(), todo.getAssigneeId(), WorkflowRejectResubmitMode.RESTART, "correct data"));
            var resubmit = tasks.query(Criteria.of().eq("instanceId", first.instance().getId()).eq("taskKind", WorkflowTaskKind.RESUBMIT), PageRequest.of(1, 10)).getFirst();
            assertThatThrownBy(() -> submissions.submit(WorkflowSubmitRequest.approval(module, "restart-record").withOperator("other")))
                    .hasMessageContaining("原发起人");
            org.mockito.Mockito.doThrow(new IllegalStateException("summary unavailable")).when(summaries).writeSubmitted(org.mockito.ArgumentMatchers.any());
            try {
                assertThatThrownBy(() -> submissions.submit(WorkflowSubmitRequest.approval(module, "restart-record").withOperator("initiator")))
                        .hasMessage("summary unavailable");
                assertThat(instances.findById(first.instance().getId()).getInstanceStatus()).isEqualTo(WorkflowInstanceStatus.REJECTED);
                assertThat(tasks.findById(resubmit.getId()).getTaskStatus()).isEqualTo(WorkflowTaskStatus.TODO);
                assertThat(histories.query(Criteria.of().eq("moduleAlias", module), PageRequest.of(1, 10))).isEmpty();
            } finally { org.mockito.Mockito.reset(summaries); }
            var replacement = submissions.submit(WorkflowSubmitRequest.approval(module, "restart-record").withOperator("initiator"));
            assertThat(replacement.instance().getId()).isNotEqualTo(first.instance().getId());
            assertThat(replacement.instance().getPreviousInstanceId()).isEqualTo(first.instance().getId());
            assertThat(instances.findById(first.instance().getId())).isNull();
            assertThat(tasks.findById(resubmit.getId())).isNull();
            var history = histories.query(Criteria.of().eq("moduleAlias", module), PageRequest.of(1, 10)).getFirst();
            assertThat(history.getArchiveReason()).isEqualTo(WorkflowArchiveReason.RESTARTED);
            assertThat(archives.parseSnapshot(history).tasks()).filteredOn(task -> task.getId().equals(resubmit.getId()))
                    .singleElement().satisfies(task -> assertThat(task.getTaskStatus()).isEqualTo(WorkflowTaskStatus.DONE));
        }
    }

    @Test void simultaneousLastApproversFinishAllModeAndAdvanceTheInstance() throws Exception {
        WorkflowSubmitResult submitted;
        try (var context = TenantContext.use(tenant)) { submitted = submissions.submit(WorkflowSubmitRequest.approval(module, "record").withOperator("initiator")); }
        List<WorkflowTask> todo = submitted.draft().tasks();
        assertThat(todo).hasSize(2);
        pauses.approvalQuery.set(true);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> {
                Thread.currentThread().setName("first-approval");
                try (var context = TenantContext.use(tenant)) { return actions.approve(WorkflowTaskActionRequest.builder(todo.getFirst().getId(), todo.getFirst().getAssigneeId()).build()); }
            });
            assertThat(pauses.reached.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> second = executor.submit(() -> {
                try (var context = TenantContext.use(tenant)) { return actions.approve(WorkflowTaskActionRequest.builder(todo.getLast().getId(), todo.getLast().getAssigneeId()).build()); }
            });
            try { assertThatThrownBy(() -> second.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
            finally { pauses.release.countDown(); }
            first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
        }
        assertThat(tasks.query(Criteria.of().eq("instanceId", submitted.instance().getId()), new PageRequest(0, 10)))
                .allMatch(task -> task.getTaskStatus() == WorkflowTaskStatus.DONE);
        assertThat(nodeRuns.query(Criteria.of().eq("instanceId", submitted.instance().getId()).eq("nodeKey", "approve"), new PageRequest(0, 1)).getFirst().getNodeStatus())
                .isEqualTo(WorkflowNodeStatus.COMPLETED);
        assertThat(instances.findById(submitted.instance().getId()).getInstanceStatus()).isEqualTo(WorkflowInstanceStatus.COMPLETED);
        assertThat(instances.findById(submitted.instance().getId()).getApprovalStatus()).isEqualTo(WorkflowApprovalStatus.APPROVED);
    }

    @Test void autoBranchSubmissionRejectsBothManualPayloadsWithoutPersistingAndStillUsesBusinessConditions() {
        try (var context = TenantContext.use(tenant)) {
            seedAutoSelectionDefinition(false);
            long existingTaskCount = tasks.count(Criteria.of());
            var request = WorkflowSubmitRequest.workflow(module, "selected-record", "auto_selection").withOperator("initiator");
            assertThatThrownBy(() -> submissions.submit(request.withSelectedRoute("branch-right", "force fallback")))
                    .hasMessageContaining("requires MANUAL branch: branch");
            assertThatThrownBy(() -> submissions.submit(request.withManualRouteSelections(
                    List.of(new WorkflowManualRouteSelection("branch", "branch-right", "force fallback")))))
                    .hasMessageContaining("requires MANUAL branch: branch");
            assertThat(instances.query(Criteria.of().eq("moduleAlias", module), PageRequest.of(1, 20))).isEmpty();
            assertThat(tasks.count(Criteria.of())).isEqualTo(existingTaskCount);

            var matched = submissions.submit(request);
            assertThat(matched.draft().tasks()).extracting(WorkflowTask::getAssigneeId).containsExactly("left");
            assertThat(matched.draft().routes()).filteredOn(route -> route.getSourceNodeKey().equals("branch"))
                    .filteredOn(route -> route.getRouteStatus() == WorkflowRouteStatus.EFFECTIVE)
                    .extracting(WorkflowRouteInstance::getRouteKey).containsExactly("branch-left");
            var fallback = submissions.submit(WorkflowSubmitRequest.workflow(module, "fallback-record", "auto_selection").withOperator("initiator"));
            assertThat(fallback.draft().tasks()).extracting(WorkflowTask::getAssigneeId).containsExactly("right");
        }
    }

    @Test void nonBooleanAutoConditionFailsSubmissionRatherThanCreatingAnInstanceOnTheDefaultRoute() {
        try (var context = TenantContext.use(tenant)) {
            String versionId = seedSelectionDefinition(false, "start", WorkflowRouteMode.AUTO);
            var route = links.query(Criteria.of().eq("workflowVersionId", versionId).eq("routeKey", "branch-left"), PageRequest.of(1, 1)).getFirst();
            var expectedVersion = route.getVersion(); route.setConditionExpression("{id}"); EntityLifecycle.prepareUpdate(route, Instant.now());
            assertThat(links.updateByIdAndVersion(route, expectedVersion)).isOne();
            assertThatThrownBy(() -> submissions.submit(WorkflowSubmitRequest.workflow(module, "record", "auto_selection").withOperator("initiator")))
                    .hasMessageContaining("必须返回布尔值");
            assertThat(instances.query(Criteria.of().eq("moduleAlias", module), PageRequest.of(1, 20))).isEmpty();
        }
    }

    @Test void autoBranchProgressionRejectsBothManualPayloadsAndRollsBackApprovalWhileDirectContinuationRemainsCompatible() {
        try (var context = TenantContext.use(tenant)) {
            seedAutoSelectionDefinition(true);
            var submitted = submissions.submit(WorkflowSubmitRequest.workflow(module, "selected-record", "auto_selection").withOperator("initiator"));
            var first = taskFor(submitted.instance().getId(), "first");
            assertThatThrownBy(() -> actions.approve(WorkflowTaskActionRequest.builder(first.getId(), first.getAssigneeId())
                    .selectedRoute("branch-right", "force fallback").build()))
                    .hasMessageContaining("requires MANUAL branch: branch");
            assertThatThrownBy(() -> actions.approve(WorkflowTaskActionRequest.builder(first.getId(), first.getAssigneeId())
                    .manualRouteSelections(List.of(new WorkflowManualRouteSelection("branch", "branch-right", "force fallback"))).build()))
                    .hasMessageContaining("requires MANUAL branch: branch");
            assertThat(tasks.findById(first.getId()).getTaskStatus()).isEqualTo(WorkflowTaskStatus.TODO);
            assertThat(nodeRuns.findById(first.getNodeInstanceId()).getNodeStatus()).isEqualTo(WorkflowNodeStatus.ACTIVE);
            assertThat(events.query(Criteria.of().eq("taskId", first.getId()).eq("actionCode", "approve"), PageRequest.of(1, 10))).isEmpty();
            assertThat(tasks.query(Criteria.of().eq("instanceId", submitted.instance().getId()), PageRequest.of(1, 20))).hasSize(1);

            actions.approve(WorkflowTaskActionRequest.builder(first.getId(), first.getAssigneeId()).selectedRoute("first-branch", null).build());
            assertThat(tasks.findById(first.getId()).getTaskStatus()).isEqualTo(WorkflowTaskStatus.DONE);
            assertThat(taskFor(submitted.instance().getId(), "left").getTaskStatus()).isEqualTo(WorkflowTaskStatus.TODO);
            assertThat(routeRuns.query(Criteria.of().eq("instanceId", submitted.instance().getId()).eq("sourceNodeKey", "branch"), PageRequest.of(1, 10)))
                    .filteredOn(route -> route.getRouteStatus() == WorkflowRouteStatus.EFFECTIVE)
                    .extracting(WorkflowRouteInstance::getRouteKey).containsExactly("branch-left");
            actions.approve(WorkflowTaskActionRequest.builder(taskFor(submitted.instance().getId(), "left").getId(), "left").build());
            assertThat(instances.findById(submitted.instance().getId()).getInstanceStatus()).isEqualTo(WorkflowInstanceStatus.COMPLETED);
        }
    }

    @Test void customStartNodeKeyResolvesInitiatorForTheInitialManualBranch() {
        try (var context = TenantContext.use(tenant)) {
            seedSelectionDefinition(false, "begin", WorkflowRouteMode.MANUAL);
            var selections = List.of(new WorkflowManualRouteSelection("branch", "branch-right", "business choice"));
            var submitted = submissions.submit(WorkflowSubmitRequest.workflow(module, "selected-record", "auto_selection")
                    .withOperator("initiator").withManualRouteSelections(selections));
            assertThat(submitted.draft().tasks()).extracting(WorkflowTask::getAssigneeId).containsExactly("right");
            assertThat(submitted.draft().routes()).filteredOn(route -> route.getRouteKey().equals("branch-right"))
                    .singleElement().satisfies(route -> {
                        assertThat(route.getRouteReason()).isEqualTo(WorkflowRouteReason.MANUAL_SELECTED);
                        assertThat(route.getSelectedBy()).isEqualTo("initiator");
                        assertThat(route.getSelectedReason()).isEqualTo("business choice");
                    });
            var todo = taskFor(submitted.instance().getId(), "right");
            actions.approve(WorkflowTaskActionRequest.builder(todo.getId(), todo.getAssigneeId()).build());
            assertThat(instances.findById(submitted.instance().getId()).getInstanceStatus()).isEqualTo(WorkflowInstanceStatus.COMPLETED);
        }
    }

    @Test void autoToContinuousManualSubmissionRequiresOnlyActuallyReachedSelectionsAndCompletesNestedConvergence() {
        try (var context = TenantContext.use(tenant)) {
            seedContinuousManualDefinition(false);
            var request = WorkflowSubmitRequest.workflow(module, "selected-record", "continuous_manual").withOperator("initiator");
            var firstChoice = new WorkflowManualRouteSelection("manualA", "manualA-manualB", "enter nested branch");
            var secondChoice = new WorkflowManualRouteSelection("manualB", "manualB-selected", "business decision");
            assertThatThrownBy(() -> submissions.submit(request)).hasMessageContaining("requires selected route: manualA");
            assertThatThrownBy(() -> submissions.submit(request.withManualRouteSelections(List.of(firstChoice))))
                    .hasMessageContaining("requires selected route: manualB");
            assertThat(instances.query(Criteria.of().eq("moduleAlias", module), PageRequest.of(1, 20))).isEmpty();
            var submitted = submissions.submit(request.withManualRouteSelections(List.of(firstChoice, secondChoice)));
            assertThat(submitted.draft().tasks()).extracting(WorkflowTask::getAssigneeId).containsExactly("selected");
            var todo = taskFor(submitted.instance().getId(), "selected");
            actions.approve(WorkflowTaskActionRequest.builder(todo.getId(), todo.getAssigneeId()).build());
            assertThat(instances.findById(submitted.instance().getId()).getInstanceStatus()).isEqualTo(WorkflowInstanceStatus.COMPLETED);
            assertThat(routeRuns.query(Criteria.of().eq("instanceId", submitted.instance().getId()), PageRequest.of(1, 30)))
                    .filteredOn(route -> Set.of("manualA-manualB", "manualB-selected").contains(route.getRouteKey()))
                    .hasSize(2).allMatch(route -> route.getRouteStatus() == WorkflowRouteStatus.CLOSED);

            var unmatched = WorkflowSubmitRequest.workflow(module, "fallback-record", "continuous_manual").withOperator("initiator");
            assertThatThrownBy(() -> submissions.submit(unmatched.withManualRouteSelections(List.of(firstChoice, secondChoice))))
                    .hasMessageContaining("not candidate outgoing route");
            assertThat(submissions.submit(unmatched).draft().tasks()).extracting(WorkflowTask::getAssigneeId).containsExactly("fallback");
        }
    }

    @Test void progressionPlansAutoToContinuousManualAgainstOneSnapshotAndRollsBackIncompleteChoices() {
        try (var context = TenantContext.use(tenant)) {
            seedContinuousManualDefinition(true);
            var submitted = submissions.submit(WorkflowSubmitRequest.workflow(module, "selected-record", "continuous_manual").withOperator("initiator"));
            var first = taskFor(submitted.instance().getId(), "first");
            var firstChoice = new WorkflowManualRouteSelection("manualA", "manualA-manualB", "enter nested branch");
            var secondChoice = new WorkflowManualRouteSelection("manualB", "manualB-selected", "business decision");
            assertThatThrownBy(() -> actions.approve(WorkflowTaskActionRequest.builder(first.getId(), first.getAssigneeId())
                    .manualRouteSelections(List.of(firstChoice)).build())).hasMessageContaining("requires selected route: manualB");
            assertThat(tasks.findById(first.getId()).getTaskStatus()).isEqualTo(WorkflowTaskStatus.TODO);
            assertThat(events.query(Criteria.of().eq("taskId", first.getId()).eq("actionCode", "approve"), PageRequest.of(1, 10))).isEmpty();
            actions.approve(WorkflowTaskActionRequest.builder(first.getId(), first.getAssigneeId())
                    .manualRouteSelections(List.of(firstChoice, secondChoice)).build());
            var selected = taskFor(submitted.instance().getId(), "selected");
            assertThat(selected.getTaskStatus()).isEqualTo(WorkflowTaskStatus.TODO);
            actions.approve(WorkflowTaskActionRequest.builder(selected.getId(), selected.getAssigneeId()).build());
            assertThat(instances.findById(submitted.instance().getId()).getInstanceStatus()).isEqualTo(WorkflowInstanceStatus.COMPLETED);
            assertThat(tasks.query(Criteria.of().eq("instanceId", submitted.instance().getId()).eq("taskStatus", WorkflowTaskStatus.TODO), PageRequest.of(1, 20))).isEmpty();
        }
    }

    @Test void anyConvergenceCancelsPersistedTodoEvenWhenSiblingNodeHasAlreadyBeenSkipped() {
        try (var context = TenantContext.use(tenant)) {
            var submitted = nestedAnyFixture(false);
            var slow = nodeRuns.query(Criteria.of().eq("instanceId", submitted.instance().getId()).eq("nodeKey", "innerRight"), PageRequest.of(1, 1)).getFirst();
            // The executor can classify the path as skipped before progression cancels its existing tasks.
            var expectedVersion = slow.getVersion(); slow.setNodeStatus(WorkflowNodeStatus.SKIPPED);
            EntityLifecycle.prepareUpdate(slow, Instant.now()); assertThat(nodeRuns.updateByIdAndVersion(slow, expectedVersion)).isOne();
            var oldTodo = taskFor(submitted.instance().getId(), "innerRight");
            assertThat(oldTodo.getTaskStatus()).isEqualTo(WorkflowTaskStatus.TODO);
            var winner = taskFor(submitted.instance().getId(), "innerLeft");
            actions.approve(WorkflowTaskActionRequest.builder(winner.getId(), winner.getAssigneeId()).build());
            var canceled = tasks.findById(oldTodo.getId());
            assertThat(canceled.getTaskStatus()).isEqualTo(WorkflowTaskStatus.CANCELED);
            assertThat(canceled.getDecision()).isEqualTo("route_dropped");
            assertThat(canceled.getCompletedAt()).isNotNull();
            assertThat(instances.findById(submitted.instance().getId()).getInstanceStatus()).isEqualTo(WorkflowInstanceStatus.COMPLETED);
            assertThat(tasks.query(Criteria.of().eq("instanceId", submitted.instance().getId()).eq("taskStatus", WorkflowTaskStatus.TODO), PageRequest.of(1, 20))).isEmpty();
        }
    }

    @Test void nestedAnyConvergenceCancelsOnlyItsSiblingAndKeepsParentAndPostConvergenceTasksProcessable() {
        try (var context = TenantContext.use(tenant)) {
            var submitted = nestedAnyFixture(true);
            var id = submitted.instance().getId();
            var outerTask = taskFor(id, "outerRight");
            var innerLoser = taskFor(id, "innerRight");
            var winner = taskFor(id, "innerLeft");
            actions.approve(WorkflowTaskActionRequest.builder(winner.getId(), winner.getAssigneeId()).build());
            assertThat(tasks.findById(innerLoser.getId()).getTaskStatus()).isEqualTo(WorkflowTaskStatus.CANCELED);
            assertThat(tasks.findById(outerTask.getId()).getTaskStatus()).isEqualTo(WorkflowTaskStatus.TODO);
            var afterJoin = taskFor(id, "afterInner");
            assertThat(afterJoin.getTaskStatus()).isEqualTo(WorkflowTaskStatus.TODO);
            assertThat(instances.findById(id).getInstanceStatus()).isEqualTo(WorkflowInstanceStatus.RUNNING);
            actions.approve(WorkflowTaskActionRequest.builder(afterJoin.getId(), afterJoin.getAssigneeId()).build());
            assertThat(tasks.findById(outerTask.getId()).getTaskStatus()).isEqualTo(WorkflowTaskStatus.TODO);
            assertThat(instances.findById(id).getInstanceStatus()).isEqualTo(WorkflowInstanceStatus.RUNNING);
            actions.approve(WorkflowTaskActionRequest.builder(outerTask.getId(), outerTask.getAssigneeId()).build());
            assertThat(instances.findById(id).getInstanceStatus()).isEqualTo(WorkflowInstanceStatus.COMPLETED);
            assertThat(tasks.query(Criteria.of().eq("instanceId", id).eq("taskStatus", WorkflowTaskStatus.TODO), PageRequest.of(1, 20))).isEmpty();
        }
    }

    @Test void withdrawingOwnActiveVoteKeepsAuditAndTransferredAssignmentSnapshotWithoutChangingOtherVotes() {
        try (var context = TenantContext.use(tenant)) {
            var submitted = submissions.submit(WorkflowSubmitRequest.approval(module, "record").withOperator("initiator"));
            var original = submitted.draft().tasks().stream().filter(task -> "one".equals(task.getAssigneeId())).findFirst().orElseThrow();
            var sibling = submitted.draft().tasks().stream().filter(task -> "two".equals(task.getAssigneeId())).findFirst().orElseThrow();
            var transferred = actions.transfer(WorkflowTaskActionRequest.builder(original.getId(), "one").targetAssigneeId("delegate").reason("handover").build()).createdTask();
            actions.approve(WorkflowTaskActionRequest.builder(transferred.getId(), "delegate").reason("original approval").build());
            assertThatThrownBy(() -> actions.revokeApprove(WorkflowTaskActionRequest.builder(transferred.getId(), "two").reason("not my vote").build()))
                    .hasMessageContaining("不能撤销");
            var result = actions.revokeApprove(WorkflowTaskActionRequest.builder(transferred.getId(), "delegate").reason("correct my vote").build());
            var retry = tasks.findById(result.createdTask().getId());
            assertThat(tasks.findById(transferred.getId()).getTaskStatus()).isEqualTo(WorkflowTaskStatus.ROLLED_BACK);
            assertThat(retry.getTaskStatus()).isEqualTo(WorkflowTaskStatus.TODO);
            assertThat(retry.getParentTaskId()).isEqualTo(transferred.getId());
            assertThat(retry.getOriginTaskId()).isEqualTo(transferred.getOriginTaskId());
            assertThat(retry.getAssigneeId()).isEqualTo("delegate");
            assertThat(retry.getOriginalAssigneeId()).isEqualTo("one");
            assertThat(retry.getAssignmentKind()).isEqualTo(WorkflowAssignmentKind.TRANSFERRED);
            assertThat(retry.getTransferredFromUserId()).isEqualTo(transferred.getTransferredFromUserId());
            assertThat(retry.getTransferredBy()).isEqualTo(transferred.getTransferredBy());
            assertThat(retry.getTransferredAt()).isEqualTo(transferred.getTransferredAt());
            assertThat(retry.getActualProcessorId()).isNull(); assertThat(retry.getDecision()).isNull(); assertThat(retry.getCompletedAt()).isNull();
            assertThat(tasks.findById(sibling.getId()).getTaskStatus()).isEqualTo(WorkflowTaskStatus.TODO);
            assertThat(result.node().getNodeStatus()).isEqualTo(WorkflowNodeStatus.ACTIVE);
            assertThat(result.node().getApprovedTaskCount()).isZero();
            assertThat(events.query(Criteria.of().eq("taskId", transferred.getId()).eq("actionCode", "approve"), PageRequest.of(1, 10)))
                    .hasSize(1).allMatch(event -> "original approval".equals(event.getMessage()) && "delegate".equals(event.getOperatorId()));
        }
    }

    @Test void untouchedLinearDownstreamCanBeRewoundAndRetryContinuesUntilApprovalCannotBeWithdrawn() {
        try (var context = TenantContext.use(tenant)) {
            var submitted = linearFixture(); var id = submitted.instance().getId();
            var first = taskFor(id, "first");
            actions.approve(WorkflowTaskActionRequest.builder(first.getId(), "one").reason("agree").build());
            var untouched = taskFor(id, "second");
            var oldNotice = new WorkflowTask(); org.springframework.beans.BeanUtils.copyProperties(untouched, oldNotice);
            oldNotice.setId(null); oldNotice.setVersion(null); oldNotice.setCreatedAt(null); oldNotice.setUpdatedAt(null);
            oldNotice.setTaskKind(WorkflowTaskKind.NOTICE);
            EntityLifecycle.prepareInsert(oldNotice, Instant.parse("2020-01-01T00:00:00Z")); tasks.insert(oldNotice);
            var retainedNotice = tasks.findById(oldNotice.getId());
            var revoked = actions.revokeApprove(WorkflowTaskActionRequest.builder(first.getId(), "one").reason("correct vote").build());
            assertThat(tasks.findById(untouched.getId()).getTaskStatus()).isEqualTo(WorkflowTaskStatus.CANCELED);
            var canceledRound = tasks.findById(untouched.getId());
            var canceledRoundEvents = events.query(Criteria.of().eq("taskId", untouched.getId()), PageRequest.of(1, 20));
            assertThat(nodeRuns.findById(untouched.getNodeInstanceId()).getNodeStatus()).isEqualTo(WorkflowNodeStatus.WAITING);
            assertThat(revoked.node().getNodeStatus()).isEqualTo(WorkflowNodeStatus.ACTIVE);
            assertThat(revoked.createdTask().getOriginalAssigneeId()).isEqualTo(first.getOriginalAssigneeId());
            actions.approve(WorkflowTaskActionRequest.builder(revoked.createdTask().getId(), "one").build());
            var resumed = tasks.query(Criteria.of().eq("nodeInstanceId", untouched.getNodeInstanceId())
                    .eq("taskKind", WorkflowTaskKind.APPROVAL).eq("taskStatus", WorkflowTaskStatus.TODO), PageRequest.of(1, 10)).getFirst();
            assertThat(resumed.getId()).isNotEqualTo(untouched.getId());
            var repeated = actions.revokeApprove(WorkflowTaskActionRequest.builder(revoked.createdTask().getId(), "one").reason("correct again").build());
            assertThat(tasks.findById(resumed.getId()).getTaskStatus()).isEqualTo(WorkflowTaskStatus.CANCELED);
            assertThat(tasks.findById(untouched.getId())).usingRecursiveComparison().isEqualTo(canceledRound);
            assertThat(events.query(Criteria.of().eq("taskId", untouched.getId()), PageRequest.of(1, 20)))
                    .usingRecursiveComparison().isEqualTo(canceledRoundEvents);
            assertThat(tasks.findById(oldNotice.getId())).usingRecursiveComparison().isEqualTo(retainedNotice);
            assertThat(events.query(Criteria.of().eq("taskId", oldNotice.getId()), PageRequest.of(1, 20))).isEmpty();
            actions.approve(WorkflowTaskActionRequest.builder(repeated.createdTask().getId(), "one").build());
            resumed = tasks.query(Criteria.of().eq("nodeInstanceId", untouched.getNodeInstanceId())
                    .eq("taskKind", WorkflowTaskKind.APPROVAL).eq("taskStatus", WorkflowTaskStatus.TODO), PageRequest.of(1, 10)).getFirst();
            actions.approve(WorkflowTaskActionRequest.builder(resumed.getId(), "two").build());
            assertThat(instances.findById(id).getInstanceStatus()).isEqualTo(WorkflowInstanceStatus.COMPLETED);
            assertThatThrownBy(() -> actions.revokeApprove(WorkflowTaskActionRequest.builder(repeated.createdTask().getId(), "one").reason("too late").build()))
                    .hasMessageContaining("不能撤销");
        }
    }

    @Test void crossedBranchCannotWithdrawCompletedApprovalEvenIfItsNextTasksAreUntouched() {
        try (var context = TenantContext.use(tenant)) {
            var submitted = nestedAnyFixture(true); var original = taskFor(submitted.instance().getId(), "innerLeft");
            actions.approve(WorkflowTaskActionRequest.builder(original.getId(), "innerLeft").build());
            assertThatThrownBy(() -> actions.revokeApprove(WorkflowTaskActionRequest.builder(original.getId(), "innerLeft").reason("crossed branch").build()))
                    .hasMessageContaining("不能撤销");
            assertThat(tasks.findById(original.getId()).getTaskStatus()).isEqualTo(WorkflowTaskStatus.DONE);
            assertThat(taskFor(submitted.instance().getId(), "afterInner").getTaskStatus()).isEqualTo(WorkflowTaskStatus.TODO);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = WorkflowApprovalMode.class, names = {"ANY", "RATIO"})
    void withdrawingThresholdVoteRestoresOnlyCurrentRoundSkippedParticipantsAndKeepsOtherApprovedVotes(WorkflowApprovalMode mode) {
        try (var context = TenantContext.use(tenant)) {
            var submitted = linearFixture(mode, 66, List.of("one", "two", "three"));
            var id = submitted.instance().getId(); var originalNode = taskFor(id, "first").getNodeInstanceId();
            var originalTasks = tasks.query(Criteria.of().eq("nodeInstanceId", originalNode), PageRequest.of(1, 20));
            var first = originalTasks.stream().filter(task -> "one".equals(task.getAssigneeId())).findFirst().orElseThrow();
            var second = originalTasks.stream().filter(task -> "two".equals(task.getAssigneeId())).findFirst().orElseThrow();
            var third = originalTasks.stream().filter(task -> "three".equals(task.getAssigneeId())).findFirst().orElseThrow();
            actions.approve(WorkflowTaskActionRequest.builder(first.getId(), "one").build());
            var withdrawing = first;
            if (mode == WorkflowApprovalMode.RATIO) {
                actions.approve(WorkflowTaskActionRequest.builder(second.getId(), "two").build()); withdrawing = second;
            }
            assertThat(tasks.findById(third.getId()).getTaskStatus()).isEqualTo(WorkflowTaskStatus.SKIPPED);
            var oldRound = new WorkflowTask(); org.springframework.beans.BeanUtils.copyProperties(tasks.findById(third.getId()), oldRound);
            oldRound.setId(null); oldRound.setVersion(null); oldRound.setCompletedAt(Instant.parse("2020-01-01T00:00:00Z"));
            EntityLifecycle.prepareInsert(oldRound, Instant.now()); tasks.insert(oldRound);
            actions.revokeApprove(WorkflowTaskActionRequest.builder(withdrawing.getId(), withdrawing.getAssigneeId()).reason("reopen vote").build());
            var reopened = tasks.query(Criteria.of().eq("nodeInstanceId", originalNode).eq("taskStatus", WorkflowTaskStatus.TODO), PageRequest.of(1, 20));
            assertThat(reopened).extracting(WorkflowTask::getAssigneeId)
                    .containsExactlyInAnyOrderElementsOf(mode == WorkflowApprovalMode.ANY ? List.of("one", "two", "three") : List.of("two", "three"));
            assertThat(reopened).noneMatch(task -> oldRound.getId().equals(task.getParentTaskId()));
            assertThat(tasks.findById(third.getId()).getTaskStatus()).isEqualTo(WorkflowTaskStatus.SKIPPED);
            if (mode == WorkflowApprovalMode.RATIO) assertThat(tasks.findById(first.getId()).getTaskStatus()).isEqualTo(WorkflowTaskStatus.DONE);
            var substitute = reopened.stream().filter(task -> "three".equals(task.getAssigneeId())).findFirst().orElseThrow();
            assertThat(substitute.getParentTaskId()).isEqualTo(third.getId());
            assertThat(substitute.getAssignmentSnapshotText()).isEqualTo(third.getAssignmentSnapshotText());
            actions.approve(WorkflowTaskActionRequest.builder(substitute.getId(), "three").build());
            assertThat(nodeRuns.findById(originalNode).getNodeStatus()).isEqualTo(WorkflowNodeStatus.COMPLETED);
            assertThat(nodeRuns.findById(originalNode).getCompletedTaskCount()).isEqualTo(mode == WorkflowApprovalMode.ANY ? 1 : 2);
        }
    }

    private WorkflowSubmitResult linearFixture() { return linearFixture(WorkflowApprovalMode.ALL, null, List.of("one")); }

    private WorkflowSubmitResult linearFixture(WorkflowApprovalMode mode, Integer ratio, List<String> firstUsers) {
        var definition = new WorkflowDefinition(); definition.setApplicationAlias("test"); definition.setModuleAlias(module);
        definition.setAlias("linear"); definition.setTitle("Linear approval"); definition.setEnabled(true); definition.setApprovalEnabled(false);
        definition.setDefinitionStatus(WorkflowDefinitionStatus.PUBLISHED); definition.setCurrentVersionNo(1);
        EntityLifecycle.prepareInsert(definition, Instant.now()); definitions.insert(definition);
        var version = new WorkflowVersion(); version.setDefinitionId(definition.getId()); version.setVersionNo(1);
        version.setPublishStatus(WorkflowPublishStatus.PUBLISHED); version.setSnapshotText("{}"); EntityLifecycle.prepareInsert(version, Instant.now()); versions.insert(version);
        for (var key : List.of("start", "first", "second", "end")) {
            var node = new WorkflowNodeDefinition(); node.setWorkflowVersionId(version.getId()); node.setNodeKey(key); node.setTitle(key);
            node.setNodeType(key.equals("start") ? WorkflowNodeType.START : key.equals("end") ? WorkflowNodeType.END : WorkflowNodeType.APPROVAL);
            if (node.getNodeType() == WorkflowNodeType.APPROVAL) {
                node.setParticipantPolicyText("user:" + (key.equals("first") ? String.join(",", firstUsers) : "two"));
                node.setApprovalMode(key.equals("first") ? mode : WorkflowApprovalMode.ALL); node.setApprovalRatio(key.equals("first") ? ratio : null);
            }
            EntityLifecycle.prepareInsert(node, Instant.now()); nodeDefinitions.insert(node);
        }
        for (var pair : List.of(List.of("start", "first"), List.of("first", "second"), List.of("second", "end"))) {
            var link = new WorkflowLinkDefinition(); link.setWorkflowVersionId(version.getId()); link.setRouteKey(pair.getFirst() + "-" + pair.getLast());
            link.setTitle(pair.getFirst()); link.setSourceNodeKey(pair.getFirst()); link.setTargetNodeKey(pair.getLast());
            EntityLifecycle.prepareInsert(link, Instant.now()); links.insert(link);
        }
        return submissions.submit(WorkflowSubmitRequest.workflow(module, "record", "linear").withOperator("initiator"));
    }

    private WorkflowTask taskFor(String instanceId, String nodeKey) {
        var node = nodeRuns.query(Criteria.of().eq("instanceId", instanceId).eq("nodeKey", nodeKey), PageRequest.of(1, 1)).getFirst();
        return tasks.query(Criteria.of().eq("nodeInstanceId", node.getId()), PageRequest.of(1, 10)).getFirst();
    }

    private void seedAutoSelectionDefinition(boolean afterApproval) {
        seedSelectionDefinition(afterApproval, "start", WorkflowRouteMode.AUTO);
    }

    private void seedContinuousManualDefinition(boolean afterApproval) {
        var definition = new WorkflowDefinition(); definition.setApplicationAlias("test"); definition.setModuleAlias(module);
        definition.setAlias("continuous_manual"); definition.setTitle("Continuous manual choices"); definition.setEnabled(true); definition.setApprovalEnabled(false);
        definition.setDefinitionStatus(WorkflowDefinitionStatus.PUBLISHED); definition.setCurrentVersionNo(1);
        EntityLifecycle.prepareInsert(definition, Instant.now()); definitions.insert(definition);
        var version = new WorkflowVersion(); version.setDefinitionId(definition.getId()); version.setVersionNo(1);
        version.setPublishStatus(WorkflowPublishStatus.PUBLISHED); version.setSnapshotText("{}"); EntityLifecycle.prepareInsert(version, Instant.now()); versions.insert(version);
        var keys = new ArrayList<>(List.of("begin", "auto", "manualA", "manualB", "selected", "otherA", "otherB", "fallback",
                "manualBJoin", "manualAJoin", "autoJoin", "end"));
        if (afterApproval) keys.add("first");
        for (var key : keys) {
            var node = new WorkflowNodeDefinition(); node.setWorkflowVersionId(version.getId()); node.setNodeKey(key); node.setTitle(key);
            node.setNodeType(key.equals("begin") ? WorkflowNodeType.START : key.equals("end") ? WorkflowNodeType.END
                    : Set.of("auto", "manualA", "manualB").contains(key) ? WorkflowNodeType.BRANCH
                    : key.endsWith("Join") ? WorkflowNodeType.CONVERGE : WorkflowNodeType.APPROVAL);
            if (node.getNodeType() == WorkflowNodeType.BRANCH) {
                node.setRouteMode(key.equals("auto") ? WorkflowRouteMode.AUTO : WorkflowRouteMode.MANUAL);
                node.setConvergeNodeKey(key + "Join"); node.setSelectorNodeKey(afterApproval ? "first" : "begin");
                if (!key.equals("auto")) node.setRequireManualSelectionReason(true);
            }
            if (node.getNodeType() == WorkflowNodeType.CONVERGE) node.setConvergeMode(WorkflowConvergeMode.ALL);
            if (node.getNodeType() == WorkflowNodeType.APPROVAL) { node.setParticipantPolicyText("user:" + key); node.setApprovalMode(WorkflowApprovalMode.ALL); }
            EntityLifecycle.prepareInsert(node, Instant.now()); nodeDefinitions.insert(node);
        }
        var edges = new ArrayList<>(List.of(List.of("begin", afterApproval ? "first" : "auto"), List.of("auto", "manualA"), List.of("auto", "fallback"),
                List.of("manualA", "manualB"), List.of("manualA", "otherA"), List.of("manualB", "selected"), List.of("manualB", "otherB"),
                List.of("selected", "manualBJoin"), List.of("otherB", "manualBJoin"), List.of("manualBJoin", "manualAJoin"),
                List.of("otherA", "manualAJoin"), List.of("manualAJoin", "autoJoin"), List.of("fallback", "autoJoin"), List.of("autoJoin", "end")));
        if (afterApproval) edges.add(List.of("first", "auto"));
        for (var pair : edges) {
            var link = new WorkflowLinkDefinition(); link.setWorkflowVersionId(version.getId()); link.setRouteKey(pair.getFirst() + "-" + pair.getLast());
            link.setTitle(link.getRouteKey()); link.setSourceNodeKey(pair.getFirst()); link.setTargetNodeKey(pair.getLast());
            if (pair.getFirst().equals("auto")) {
                link.setDefaultRoute(pair.getLast().equals("fallback"));
                if (pair.getLast().equals("manualA")) link.setConditionExpression("{id} == 'selected-record'");
            }
            EntityLifecycle.prepareInsert(link, Instant.now()); links.insert(link);
        }
    }

    private String seedSelectionDefinition(boolean afterApproval, String startKey, WorkflowRouteMode routeMode) {
        var definition = new WorkflowDefinition(); definition.setApplicationAlias("test"); definition.setModuleAlias(module);
        definition.setAlias("auto_selection"); definition.setTitle("Auto selection"); definition.setEnabled(true); definition.setApprovalEnabled(false);
        definition.setDefinitionStatus(WorkflowDefinitionStatus.PUBLISHED); definition.setCurrentVersionNo(1);
        EntityLifecycle.prepareInsert(definition, Instant.now()); definitions.insert(definition);
        var version = new WorkflowVersion(); version.setDefinitionId(definition.getId()); version.setVersionNo(1);
        version.setPublishStatus(WorkflowPublishStatus.PUBLISHED); version.setSnapshotText("{}"); EntityLifecycle.prepareInsert(version, Instant.now()); versions.insert(version);
        var keys = afterApproval ? List.of(startKey, "first", "branch", "left", "right", "join", "end")
                : List.of(startKey, "branch", "left", "right", "join", "end");
        for (var key : keys) {
            var node = new WorkflowNodeDefinition(); node.setWorkflowVersionId(version.getId()); node.setNodeKey(key); node.setTitle(key);
            node.setNodeType(key.equals(startKey) ? WorkflowNodeType.START : key.equals("end") ? WorkflowNodeType.END
                    : key.equals("branch") ? WorkflowNodeType.BRANCH : key.equals("join") ? WorkflowNodeType.CONVERGE : WorkflowNodeType.APPROVAL);
            if (node.getNodeType() == WorkflowNodeType.BRANCH) { node.setRouteMode(routeMode); node.setSelectorNodeKey(startKey); node.setConvergeNodeKey("join"); }
            if (node.getNodeType() == WorkflowNodeType.CONVERGE) node.setConvergeMode(WorkflowConvergeMode.ALL);
            if (node.getNodeType() == WorkflowNodeType.APPROVAL) { node.setParticipantPolicyText("user:" + key); node.setApprovalMode(WorkflowApprovalMode.ALL); }
            EntityLifecycle.prepareInsert(node, Instant.now()); nodeDefinitions.insert(node);
        }
        var edges = new ArrayList<>(List.of(List.of("branch", "left"), List.of("branch", "right"),
                List.of("left", "join"), List.of("right", "join"), List.of("join", "end")));
        edges.add(List.of(startKey, afterApproval ? "first" : "branch"));
        if (afterApproval) edges.add(List.of("first", "branch"));
        for (var pair : edges) {
            var edge = new WorkflowLinkDefinition(); edge.setWorkflowVersionId(version.getId()); edge.setRouteKey(pair.getFirst() + "-" + pair.getLast());
            edge.setTitle(edge.getRouteKey()); edge.setSourceNodeKey(pair.getFirst()); edge.setTargetNodeKey(pair.getLast());
            if (pair.getFirst().equals("branch")) {
                edge.setDefaultRoute(pair.getLast().equals("right"));
                if (pair.getLast().equals("left")) edge.setConditionExpression("{id} == 'selected-record'");
            }
            EntityLifecycle.prepareInsert(edge, Instant.now()); links.insert(edge);
        }
        return version.getId();
    }

    private WorkflowSubmitResult nestedAnyFixture(boolean nested) {
        var definition = new WorkflowDefinition(); definition.setApplicationAlias("test"); definition.setModuleAlias(module);
        definition.setAlias("nested"); definition.setTitle("Nested branch"); definition.setEnabled(true); definition.setApprovalEnabled(false);
        definition.setDefinitionStatus(WorkflowDefinitionStatus.PUBLISHED); definition.setCurrentVersionNo(1);
        EntityLifecycle.prepareInsert(definition, Instant.now()); definitions.insert(definition);
        var version = new WorkflowVersion(); version.setDefinitionId(definition.getId()); version.setVersionNo(1);
        version.setPublishStatus(WorkflowPublishStatus.PUBLISHED); version.setSnapshotText("{}"); EntityLifecycle.prepareInsert(version, Instant.now()); versions.insert(version);
        var keys = nested ? List.of("start", "outer", "inner", "innerLeft", "innerRight", "outerRight", "innerJoin", "afterInner", "outerJoin", "end")
                : List.of("start", "inner", "innerLeft", "innerRight", "innerJoin", "end");
        for (String key : keys) {
            var node = new WorkflowNodeDefinition(); node.setWorkflowVersionId(version.getId()); node.setNodeKey(key); node.setTitle(key);
            node.setNodeType(key.equals("start") ? WorkflowNodeType.START : key.equals("end") ? WorkflowNodeType.END
                    : key.equals("outer") || key.equals("inner") ? WorkflowNodeType.BRANCH
                    : key.endsWith("Join") ? WorkflowNodeType.CONVERGE : WorkflowNodeType.APPROVAL);
            if (node.getNodeType() == WorkflowNodeType.BRANCH) { node.setRouteMode(WorkflowRouteMode.AUTO); node.setConvergeNodeKey(key + "Join"); }
            if (node.getNodeType() == WorkflowNodeType.CONVERGE) node.setConvergeMode(key.equals("innerJoin") ? WorkflowConvergeMode.ANY : WorkflowConvergeMode.ALL);
            if (node.getNodeType() == WorkflowNodeType.APPROVAL) { node.setParticipantPolicyText("user:" + key); node.setApprovalMode(WorkflowApprovalMode.ALL); }
            EntityLifecycle.prepareInsert(node, Instant.now()); nodeDefinitions.insert(node);
        }
        List<List<String>> edges = new ArrayList<>(List.of(List.of("inner", "innerLeft"), List.of("inner", "innerRight"),
                List.of("innerLeft", "innerJoin"), List.of("innerRight", "innerJoin")));
        if (nested) edges.addAll(List.of(List.of("start", "outer"), List.of("outer", "inner"), List.of("outer", "outerRight"),
                List.of("innerJoin", "afterInner"), List.of("afterInner", "outerJoin"), List.of("outerRight", "outerJoin"), List.of("outerJoin", "end")));
        else edges.addAll(List.of(List.of("start", "inner"), List.of("innerJoin", "end")));
        for (var pair : edges) {
            var edge = new WorkflowLinkDefinition(); edge.setWorkflowVersionId(version.getId()); edge.setTitle(pair.getFirst()); edge.setRouteKey(pair.getFirst() + "-" + pair.getLast());
            edge.setSourceNodeKey(pair.getFirst()); edge.setTargetNodeKey(pair.getLast()); edge.setConditionExpression("true");
            EntityLifecycle.prepareInsert(edge, Instant.now()); links.insert(edge);
        }
        return submissions.submit(WorkflowSubmitRequest.workflow(module, "record", "nested").withOperator("initiator"));
    }

    private void createDefinition() {
        try (var context = TenantContext.use(tenant)) {
            WorkflowDefinition definition = new WorkflowDefinition();
            definition.setApplicationAlias("test"); definition.setModuleAlias(module); definition.setAlias("main"); definition.setTitle("Concurrency");
            definition.setApprovalEnabled(true); definition.setEnabled(true); definition.setDefinitionStatus(WorkflowDefinitionStatus.PUBLISHED); definition.setCurrentVersionNo(1);
            EntityLifecycle.prepareInsert(definition, Instant.now()); definitions.insert(definition);
            WorkflowVersion version = new WorkflowVersion(); version.setDefinitionId(definition.getId()); version.setVersionNo(1);
            version.setPublishStatus(WorkflowPublishStatus.PUBLISHED); version.setSnapshotText("{}");
            EntityLifecycle.prepareInsert(version, Instant.now()); versions.insert(version);
            for (String key : List.of("start", "approve", "approved", "end")) {
                WorkflowNodeDefinition node = new WorkflowNodeDefinition(); node.setWorkflowVersionId(version.getId()); node.setNodeKey(key); node.setTitle(key);
                node.setNodeType(key.equals("start") ? WorkflowNodeType.START : key.equals("end") ? WorkflowNodeType.END : key.equals("approved") ? WorkflowNodeType.MILESTONE : WorkflowNodeType.APPROVAL);
                if (key.equals("approved")) node.setMilestoneType(WorkflowMilestoneType.APPROVAL_COMPLETED);
                if (key.equals("approve")) { node.setApprovalMode(WorkflowApprovalMode.ALL); node.setParticipantPolicyText("{\"rules\":[{\"type\":\"USER\",\"ids\":[\"one\",\"two\"]}]}"); }
                EntityLifecycle.prepareInsert(node, Instant.now()); nodeDefinitions.insert(node);
            }
            for (String source : List.of("start", "approve", "approved")) {
                WorkflowLinkDefinition link = new WorkflowLinkDefinition(); link.setWorkflowVersionId(version.getId()); link.setRouteKey(source + "-next"); link.setTitle(source);
                link.setSourceNodeKey(source); link.setTargetNodeKey(source.equals("start") ? "approve" : source.equals("approve") ? "approved" : "end");
                EntityLifecycle.prepareInsert(link, Instant.now()); links.insert(link);
            }
        }
    }

    static class Pauses implements WorkflowModuleRecordGuard {
        volatile String submitRecord;
        final AtomicBoolean approvalQuery = new AtomicBoolean();
        CountDownLatch reached; CountDownLatch release;
        void reset() { submitRecord = null; approvalQuery.set(false); reached = new CountDownLatch(1); release = new CountDownLatch(1); }
        @Override public void beforeSubmit(WorkflowSubmitRequest request) {
            if (request.recordId().equals(submitRecord) && Thread.currentThread().getName().equals("first-submit")) await();
        }
        void await() { reached.countDown(); try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("pause timed out"); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); } }
        WorkflowTaskDao observe(WorkflowTaskDao delegate) {
            return (WorkflowTaskDao) Proxy.newProxyInstance(WorkflowTaskDao.class.getClassLoader(), new Class<?>[]{WorkflowTaskDao.class}, (proxy, method, arguments) -> {
                try {
                    Object result = method.invoke(delegate, arguments);
                    // Capture the first transaction's sibling snapshot while its task update is uncommitted.
                    if (method.getName().equals("query") && Thread.currentThread().getName().equals("first-approval") && approvalQuery.compareAndSet(true, false)) await();
                    return result;
                } catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
        }
    }

    @SpringBootConfiguration @EnableAutoConfiguration
    @EnableMuYunRepositories(basePackageClasses = WorkflowInstanceDao.class)
    @Import({WorkflowDefinitionService.class, WorkflowVersionService.class, WorkflowDefinitionSelector.class,
            WorkflowConditionService.class, WorkflowInstanceService.class, WorkflowInstanceStateService.class,
            WorkflowNodeInstanceStateService.class, WorkflowRouteInstanceStateService.class, WorkflowRouteRuntimeService.class,
            WorkflowRuntimeEventFactory.class, WorkflowInstanceSnapshotFactory.class, WorkflowParticipantService.class,
            WorkflowRuntimeTaskFactory.class, WorkflowSubmitDraftService.class, WorkflowRuntimeSubmitService.class,
            WorkflowSubmitFacade.class, WorkflowRuntimeActivationService.class, WorkflowRuntimeProgressionService.class,
            WorkflowTaskActionService.class, WorkflowAutomaticApprovalService.class, WorkflowActionPolicyService.class, WorkflowTaskAssignmentPolicyService.class,
            WorkflowApprovalTaskPolicyService.class, WorkflowDelegationService.class, WorkflowDelegationCompletionNoticeService.class,
            WorkflowRuntimePluginDispatcher.class, WorkflowArchiveService.class})
    static class Host {
        @Bean DataSource dataSource() { return DataSourceBuilder.create().url(postgres.getJdbcUrl()).username(postgres.getUsername()).password(postgres.getPassword()).driverClassName(postgres.getDriverClassName()).build(); }
        @Bean ModuleRecordFacts facts() { return (module, id) -> Map.of("id", id); }
        @Bean WorkflowApprovalSummaryWriter summary() { return mock(WorkflowApprovalSummaryWriter.class); }
        @Bean WorkflowModuleTaskEvaluator evaluator() { return mock(WorkflowModuleTaskEvaluator.class); }
        @Bean WorkflowBusinessTaskResolver specifications() { return mock(WorkflowBusinessTaskResolver.class); }
        @Bean Pauses pauses() { return new Pauses(); }
        @Bean static org.springframework.beans.factory.config.BeanPostProcessor taskQueryProbe(Pauses pauses) {
            return new org.springframework.beans.factory.config.BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    return bean instanceof WorkflowTaskDao tasks ? pauses.observe(tasks) : bean;
                }
            };
        }
    }
}
