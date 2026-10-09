package net.ximatai.muyun.spring.platform.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.platform.task.ModuleCompletionCheckService;
import net.ximatai.muyun.spring.platform.ui.PlatformTaskCheckBlock;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.ArrayList;

@Service
public class DefaultWorkflowModuleTaskEvaluator implements WorkflowModuleTaskEvaluator {
    private final WorkflowBusinessTaskResolver specifications;
    private final ModuleCompletionCheckService checks;
    private final ObjectMapper mapper = new ObjectMapper();

    public DefaultWorkflowModuleTaskEvaluator(WorkflowBusinessTaskResolver specifications, ModuleCompletionCheckService checks) {
        this.specifications = specifications; this.checks = checks;
    }

    @Override public WorkflowModuleTaskEvaluation evaluate(WorkflowInstance instance, WorkflowNodeInstance node,
            WorkflowTask task, WorkflowTaskDefinition ignoredDefinition) {
        var specification = specifications.resolve(node);
        var results = new ArrayList<WorkflowTaskCheckResult>();
        for (var check : specification.checks()) {
            var result = new WorkflowTaskCheckResult();
            result.setTaskId(task.getId()); result.setTenantId(task.getTenantId());
            result.setCheckKey(check.getCheckKey()); result.setCheckKind(check.getCheckKind());
            result.setCheckRunId(net.ximatai.muyun.spring.common.id.Ids.newId()); result.setCheckedAt(Instant.now());
            boolean passed;
            if (check.getCheckKind() == WorkflowTaskCheckKind.MANUAL_CONFIRM) continue;
            if (check.getCheckKind() == WorkflowTaskCheckKind.FORMULA) {
                passed = checks.formula(instance.getModuleAlias(), instance.getRecordId(), check.getExpression());
            } else {
                try {
                    var block = mapper.readValue(check.getCheckConfigText(), PlatformTaskCheckBlock.class);
                    var detail = checks.check(instance.getModuleAlias(), instance.getRecordId(), block);
                    passed = Boolean.TRUE.equals(detail.passed()); result.setResultPayloadText(mapper.writeValueAsString(detail));
                } catch (Exception failure) { throw new PlatformException("业务完成项检查失败: " + check.getCheckKey(), failure); }
            }
            result.setPassed(passed); result.setCheckStatus(passed ? WorkflowTaskCheckStatus.PASSED : WorkflowTaskCheckStatus.FAILED);
            result.setFailureMessage(passed ? null : check.getFailureMessage()); results.add(result);
        }
        var failed = results.stream().filter(result -> !Boolean.TRUE.equals(result.getPassed())).findFirst();
        if (failed.isPresent()) return WorkflowModuleTaskEvaluation.failed(failed.get().getFailureMessage(), results, specification.guides());
        if (Boolean.TRUE.equals(specification.definition().getManualConfirm()) || results.isEmpty())
            return new WorkflowModuleTaskEvaluation(WorkflowTaskCheckStatus.NO_CHECK, true, null, results, specification.guides());
        return WorkflowModuleTaskEvaluation.passed(results, specification.guides());
    }
}
