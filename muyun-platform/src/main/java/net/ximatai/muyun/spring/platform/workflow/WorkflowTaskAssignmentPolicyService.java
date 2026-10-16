package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import org.springframework.stereotype.Service;

@Service
public class WorkflowTaskAssignmentPolicyService {
    public boolean canProcess(WorkflowTask task, String operatorId) {
        String validOperatorId = requireText(operatorId, TextReference.code(
                "platform.rejection.workflow-task-assignment-policy.select-a-workflow-operator",
                "workflow operator id must not be blank"));
        if (CurrentUserContext.currentUser()
                .filter(user -> user.system() && validOperatorId.equals(user.userId()))
                .isPresent()) {
            return true;
        }
        if (task == null || task.getTaskStatus() != WorkflowTaskStatus.TODO) {
            return false;
        }
        if (validOperatorId.equals(task.getAssigneeId())) {
            return true;
        }
        return task.getAssignmentKind() == WorkflowAssignmentKind.DELEGATED
                && Boolean.TRUE.equals(task.getPrincipalCanProcess())
                && validOperatorId.equals(task.getDelegatedFromUserId())
                && task.getTransferredFromUserId() == null;
    }

    public boolean canSeeTodo(WorkflowTask task, String userId) {
        String validUserId = requireText(userId, TextReference.code(
                "platform.rejection.workflow-task-assignment-policy.select-a-workflow-user",
                "workflow user id must not be blank"));
        if (task == null || task.getTaskStatus() != WorkflowTaskStatus.TODO) {
            return false;
        }
        if (validUserId.equals(task.getAssigneeId())) {
            return true;
        }
        return task.getAssignmentKind() == WorkflowAssignmentKind.DELEGATED
                && Boolean.TRUE.equals(task.getPrincipalCanProcess())
                && validUserId.equals(task.getDelegatedFromUserId())
                && task.getTransferredFromUserId() == null;
    }

    private String requireText(String value, TextReference source) {
        if (value == null || value.isBlank()) {
            throw PlatformErrors.badRequestText(source);
        }
        return value;
    }
}
