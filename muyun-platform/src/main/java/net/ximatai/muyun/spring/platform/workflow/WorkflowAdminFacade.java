package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.database.core.orm.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class WorkflowAdminFacade {
    private final WorkflowAdminService adminService;

    public WorkflowAdminFacade(WorkflowAdminService adminService) {
        this.adminService = adminService;
    }

    public List<WorkflowTask> currentTodoTasks(String instanceId) {
        return adminService.currentTodoTasks(instanceId);
    }

    public List<WorkflowAdminActiveTaskView> currentTodoTaskViews(String instanceId) {
        return adminService.currentTodoTaskViews(instanceId);
    }

    public List<WorkflowAdminInstanceView> queryCurrentInstances(WorkflowAdminInstanceQueryRequest request,
                                                                 PageRequest pageRequest) {
        return adminService.queryCurrentInstances(request, pageRequest);
    }

    public WorkflowInstanceActionResult forceTerminate(WorkflowInstanceActionRequest request) {
        return adminService.forceTerminate(request);
    }

    public WorkflowInstanceActionResult reset(WorkflowInstanceActionRequest request) {
        return adminService.reset(request);
    }

    public WorkflowTaskActionResult forceApprove(WorkflowTaskActionRequest request) {
        return adminService.forceApprove(request);
    }

    public WorkflowRuntimeRenderBundle renderCurrentBundle(String instanceId) {
        return adminService.renderCurrentBundle(instanceId);
    }

    public WorkflowRuntimeRenderBundle renderInstanceBundle(String instanceId) {
        return adminService.renderInstanceBundle(instanceId);
    }

    public List<WorkflowEvent> currentEvents(String instanceId) {
        return adminService.currentEvents(instanceId);
    }

    public List<WorkflowHistoryEventView> currentEventViews(String instanceId) {
        return adminService.currentEventViews(instanceId);
    }

    public List<WorkflowTask> currentTasks(String instanceId) {
        return adminService.currentTasks(instanceId);
    }

    public List<WorkflowHistoryInstance> queryHistory(String moduleAlias, String recordId, PageRequest pageRequest) {
        return adminService.queryHistory(moduleAlias, recordId, pageRequest);
    }

    public List<WorkflowHistoryInstance> queryHistory(String moduleAlias, String recordId, String startedBy,
                                                      PageRequest pageRequest) {
        return adminService.queryHistory(moduleAlias, recordId, startedBy, pageRequest);
    }

    public WorkflowRuntimeRenderBundle renderHistoryBundle(String historyInstanceId) {
        return adminService.renderHistoryBundle(historyInstanceId);
    }

    public List<WorkflowEvent> historyEvents(String historyInstanceId) {
        return adminService.historyEvents(historyInstanceId);
    }

    public List<WorkflowHistoryEventView> historyEventViews(String historyInstanceId) {
        return adminService.historyEventViews(historyInstanceId);
    }

    public int deleteHistory(String historyInstanceId) {
        return adminService.deleteHistory(historyInstanceId);
    }
}
