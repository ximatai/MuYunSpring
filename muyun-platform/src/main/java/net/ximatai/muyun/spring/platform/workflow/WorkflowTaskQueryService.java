package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class WorkflowTaskQueryService {
    private final WorkflowTaskDao taskDao;
    private final WorkflowEventDao eventDao;
    private final WorkflowTaskAssignmentPolicyService assignmentPolicyService;

    public WorkflowTaskQueryService(WorkflowTaskDao taskDao, WorkflowEventDao eventDao) {
        this(taskDao, eventDao, new WorkflowTaskAssignmentPolicyService());
    }

    @Autowired
    public WorkflowTaskQueryService(WorkflowTaskDao taskDao, WorkflowEventDao eventDao,
                                    WorkflowTaskAssignmentPolicyService assignmentPolicyService) {
        this.taskDao = taskDao;
        this.eventDao = eventDao;
        this.assignmentPolicyService = assignmentPolicyService == null
                ? new WorkflowTaskAssignmentPolicyService()
                : assignmentPolicyService;
    }

    public List<WorkflowTask> myTodo(String assigneeId, PageRequest pageRequest) {
        String validAssigneeId = requireText(assigneeId, "workflow assignee id must not be blank");
        List<WorkflowTask> tasks = taskDao.query(WorkflowTenantScope.criteria()
                        .eq("taskStatus", WorkflowTaskStatus.TODO),
                new PageRequest(0, Integer.MAX_VALUE), Sort.asc("dueAt"), Sort.desc("createdAt"))
                .stream()
                .filter(task -> assignmentPolicyService.canSeeTodo(task, validAssigneeId))
                .toList();
        PageRequest page = page(pageRequest);
        int from = Math.min(page.getOffset(), tasks.size());
        int to = Math.min(from + page.getLimit(), tasks.size());
        return tasks.subList(from, to);
    }

    public List<WorkflowTask> myDone(String processorId, PageRequest pageRequest) {
        return taskDao.query(WorkflowTenantScope.criteria()
                        .eq("actualProcessorId", requireText(processorId, "workflow processor id must not be blank"))
                        .in("taskStatus", List.of(WorkflowTaskStatus.DONE, WorkflowTaskStatus.REJECTED,
                                WorkflowTaskStatus.ROLLED_BACK, WorkflowTaskStatus.NOTICED,
                                WorkflowTaskStatus.TRANSFERRED)),
                page(pageRequest), Sort.desc("completedAt"), Sort.desc("updatedAt"));
    }

    public List<WorkflowTask> myNotice(String assigneeId, PageRequest pageRequest) {
        return taskDao.query(WorkflowTenantScope.criteria()
                        .eq("assigneeId", requireText(assigneeId, "workflow assignee id must not be blank"))
                        .eq("taskKind", WorkflowTaskKind.NOTICE)
                        .in("taskStatus", List.of(WorkflowTaskStatus.TODO, WorkflowTaskStatus.NOTICED)),
                page(pageRequest), Sort.desc("createdAt"));
    }

    public List<WorkflowTask> instanceTasks(String instanceId, PageRequest pageRequest) {
        return taskDao.query(WorkflowTenantScope.criteria()
                        .eq("instanceId", requireText(instanceId, "workflow instance id must not be blank")),
                page(pageRequest), Sort.asc("createdAt"));
    }

    public List<WorkflowEvent> instanceEvents(String instanceId, PageRequest pageRequest) {
        return eventDao.query(WorkflowTenantScope.criteria()
                        .eq("instanceId", requireText(instanceId, "workflow instance id must not be blank")),
                page(pageRequest), Sort.asc("occurredAt"), Sort.asc("createdAt"));
    }

    private PageRequest page(PageRequest pageRequest) {
        return pageRequest == null ? PageRequest.of(1, 20) : pageRequest;
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new PlatformException(message);
        }
        return value;
    }
}
