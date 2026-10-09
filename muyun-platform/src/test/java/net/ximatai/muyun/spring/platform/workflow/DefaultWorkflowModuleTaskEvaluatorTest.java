package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.platform.task.ModuleCompletionCheckService;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DefaultWorkflowModuleTaskEvaluatorTest {
    private final WorkflowBusinessTaskResolver specifications = mock(WorkflowBusinessTaskResolver.class);
    private final ModuleCompletionCheckService checks = mock(ModuleCompletionCheckService.class);
    private final DefaultWorkflowModuleTaskEvaluator evaluator = new DefaultWorkflowModuleTaskEvaluator(specifications, checks);

    @Test
    void manualConfirmationKeepsGuidesAndDoesNotReadHistoricalCheckResults() {
        var definition = definition(true);
        var guide = new WorkflowTaskGuide(); guide.setGuideKind(WorkflowTaskGuideKind.EXECUTE_ACTION);
        when(specifications.resolve(any())).thenReturn(new WorkflowBusinessTaskSpec(definition, List.of(), List.of(guide)));
        var evaluation = evaluate();
        assertThat(evaluation.passed()).isTrue();
        assertThat(evaluation.checkStatus()).isEqualTo(WorkflowTaskCheckStatus.NO_CHECK);
        assertThat(evaluation.guides()).containsExactly(guide);
        verifyNoInteractions(checks);
    }

    @Test
    void evaluatesCurrentBusinessFactsEveryTimeAndReturnsFreshCheckEvidence() {
        var check = formula();
        when(specifications.resolve(any())).thenReturn(new WorkflowBusinessTaskSpec(definition(false), List.of(check), List.of()));
        when(checks.formula("sales.contract", "record-1", "{ready} == true")).thenReturn(false, true);
        var failed = evaluate();
        var passed = evaluate();
        assertThat(failed.passed()).isFalse();
        assertThat(failed.failureMessage()).isEqualTo("missing contract");
        assertThat(failed.checkResults().getFirst().getCheckStatus()).isEqualTo(WorkflowTaskCheckStatus.FAILED);
        assertThat(passed.passed()).isTrue();
        var evidence = passed.checkResults().getFirst();
        assertThat(evidence.getCheckStatus()).isEqualTo(WorkflowTaskCheckStatus.PASSED);
        assertThat(evidence.getTaskId()).isEqualTo("task-1");
        assertThat(evidence.getTenantId()).isEqualTo("tenant-1");
        assertThat(evidence.getCheckRunId()).isNotBlank().isNotEqualTo(failed.checkResults().getFirst().getCheckRunId());
        assertThat(evidence.getCheckedAt()).isNotNull();
        verify(checks, times(2)).formula("sales.contract", "record-1", "{ready} == true");
    }

    @Test
    void manualConfirmCannotOverrideFailedBusinessChecks() {
        when(specifications.resolve(any())).thenReturn(new WorkflowBusinessTaskSpec(definition(true), List.of(formula()), List.of()));
        when(checks.formula(any(), any(), any())).thenReturn(false);
        assertThat(evaluate().passed()).isFalse();
    }

    @Test
    void manualConfirmationRetainsSuccessfulLiveCheckEvidenceForAudit() {
        when(specifications.resolve(any())).thenReturn(new WorkflowBusinessTaskSpec(definition(true), List.of(formula()), List.of()));
        when(checks.formula(any(), any(), any())).thenReturn(true);
        var evaluation = evaluate();
        assertThat(evaluation.passed()).isTrue();
        assertThat(evaluation.checkResults()).hasSize(1);
        assertThat(evaluation.checkResults().getFirst().getCheckStatus()).isEqualTo(WorkflowTaskCheckStatus.PASSED);
    }

    private WorkflowModuleTaskEvaluation evaluate() {
        var instance = new WorkflowInstance(); instance.setModuleAlias("sales.contract"); instance.setRecordId("record-1");
        var task = new WorkflowTask(); task.setId("task-1"); task.setTenantId("tenant-1");
        return evaluator.evaluate(instance, new WorkflowNodeInstance(), task, null);
    }
    private WorkflowTaskDefinition definition(boolean manual) {
        var definition = new WorkflowTaskDefinition(); definition.setManualConfirm(manual); return definition;
    }
    private WorkflowTaskCheck formula() {
        var check = new WorkflowTaskCheck(); check.setCheckKey("contract"); check.setCheckKind(WorkflowTaskCheckKind.FORMULA);
        check.setExpression("{ready} == true"); check.setFailureMessage("missing contract"); return check;
    }
}
