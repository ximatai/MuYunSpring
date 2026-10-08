package net.ximatai.muyun.spring.platform.web.workflow;

import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.platform.workflow.WorkflowBusinessTaskActionService;
import net.ximatai.muyun.spring.platform.workflow.WorkflowManualRouteSelection;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.List;

@RestController
@RequestMapping("/workflow/runtime/task/{taskId}/module-task")
public class WorkflowBusinessTaskActionWebController {
    private final WorkflowBusinessTaskActionService service;
    public WorkflowBusinessTaskActionWebController(WorkflowBusinessTaskActionService service) { this.service = service; }
    @PostMapping("/guides/{guideKey}/execute")
    public WorkflowBusinessTaskActionService.Result execute(@PathVariable String taskId, @PathVariable String guideKey,
                                                           @RequestBody Request request) {
        String userId = CurrentUserContext.currentUser().map(user -> user.userId())
                .orElseThrow(() -> new PlatformException("authenticated workflow operator is required"));
        return service.execute(taskId, guideKey, request.version(), request.values(), request.payload(), userId,
                request.reason(), request.manualRouteSelections());
    }
    public record Request(Integer version, Map<String, Object> values, Map<String, Object> payload, String reason,
                          List<WorkflowManualRouteSelection> manualRouteSelections) {}
}
