package net.ximatai.muyun.spring.platform.web.workflow;

import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.platform.workflow.WorkflowBusinessTaskActionService;
import net.ximatai.muyun.spring.platform.workflow.WorkflowManualRouteSelection;
import net.ximatai.muyun.spring.platform.workflow.WorkflowTaskStatus;
import net.ximatai.muyun.spring.platform.workflow.WorkflowInstanceStatus;
import net.ximatai.muyun.spring.web.BusinessMutation;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.List;

@RestController
@RequestMapping("/workflow/runtime/task/{taskId}/module-task")
public class WorkflowBusinessTaskActionWebController {
    private final WorkflowBusinessTaskActionService service;
    public WorkflowBusinessTaskActionWebController(WorkflowBusinessTaskActionService service) { this.service = service; }
    @PostMapping("/guides/{guideKey}/execute")
    @BusinessMutation(actionContextRequired = false)
    public Receipt execute(@PathVariable String taskId, @PathVariable String guideKey,
                                                           @RequestBody Request request) {
        String userId = CurrentUserContext.currentUser().map(user -> user.userId())
                .orElseThrow(() -> new PlatformException("authenticated workflow operator is required"));
        var result = service.execute(taskId, guideKey, request.version(), request.values(), request.payload(), userId,
                request.reason(), request.manualRouteSelections()).actionResult();
        return new Receipt(result.task().getId(), result.instance().getId(),
                result.instance().getModuleAlias(), result.instance().getRecordId(),
                result.task().getTaskStatus(), result.instance().getInstanceStatus());
    }
    /** Business values are read through the owning module's normal protected output endpoint. */
    public record Receipt(String taskId, String instanceId, String moduleAlias, String recordId,
                          WorkflowTaskStatus taskStatus, WorkflowInstanceStatus instanceStatus) {}

    public record Request(Integer version, Map<String, Object> values, Map<String, Object> payload, String reason,
                          List<WorkflowManualRouteSelection> manualRouteSelections) {}
}
