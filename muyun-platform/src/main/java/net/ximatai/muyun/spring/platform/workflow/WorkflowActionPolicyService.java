package net.ximatai.muyun.spring.platform.workflow;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.platform.ActionAccessMode;
import net.ximatai.muyun.spring.common.platform.ActionDefaultGrantPolicy;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.AllowAllActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public class WorkflowActionPolicyService {
    public static final String MANAGEMENT_MODULE_ALIAS = "platform.workflow_admin";
    public static final String MANAGEMENT_QUERY_ACTION = "workflowAdminQuery";
    public static final String MANAGEMENT_TODO_TASK_QUERY_ACTION = "todoTaskQuery";
    public static final String MANAGEMENT_FORCE_APPROVE_ACTION = "forceApprove";
    public static final String MANAGEMENT_FORCE_TERMINATE_ACTION = "forceTerminate";
    public static final String MANAGEMENT_RESET_ACTION = "reset";
    public static final String MANAGEMENT_DELETE_HISTORY_ACTION = "deleteHistory";
    public static final List<String> RUNTIME_RECORD_ACTION_CODES = List.of(
            "approve",
            "revokeApprove",
            "reject",
            "rollback",
            "resubmit",
            "complete",
            "notice",
            "transfer",
            "addSign",
            "invalidate",
            "cancel",
            "revoke",
            "terminate",
            "reset"
    );

    private final ActionExecutionPolicyService executionPolicyService;
    private final List<WorkflowModuleRecordGuard> recordGuards;
    private final WorkflowTaskAssignmentPolicyService assignmentPolicyService;

    public WorkflowActionPolicyService() {
        this(new AllowAllActionExecutionPolicyService(), List.of(), new WorkflowTaskAssignmentPolicyService());
    }

    public WorkflowActionPolicyService(ActionExecutionPolicyService executionPolicyService) {
        this(executionPolicyService, List.of(), new WorkflowTaskAssignmentPolicyService());
    }

    @Autowired
    public WorkflowActionPolicyService(ObjectProvider<ActionExecutionPolicyService> executionPolicyService,
                                       ObjectProvider<WorkflowModuleRecordGuard> recordGuards,
                                       ObjectProvider<WorkflowTaskAssignmentPolicyService> assignmentPolicyService) {
        this(executionPolicyService == null
                        ? new AllowAllActionExecutionPolicyService()
                        : executionPolicyService.getIfAvailable(AllowAllActionExecutionPolicyService::new),
                recordGuards == null ? List.of() : recordGuards.orderedStream().toList(),
                assignmentPolicyService == null
                        ? new WorkflowTaskAssignmentPolicyService()
                        : assignmentPolicyService.getIfAvailable(WorkflowTaskAssignmentPolicyService::new));
    }

    public WorkflowActionPolicyService(ActionExecutionPolicyService executionPolicyService,
                                       List<WorkflowModuleRecordGuard> recordGuards) {
        this(executionPolicyService, recordGuards, new WorkflowTaskAssignmentPolicyService());
    }

    public WorkflowActionPolicyService(ActionExecutionPolicyService executionPolicyService,
                                       List<WorkflowModuleRecordGuard> recordGuards,
                                       WorkflowTaskAssignmentPolicyService assignmentPolicyService) {
        this.executionPolicyService = executionPolicyService == null
                ? new AllowAllActionExecutionPolicyService()
                : executionPolicyService;
        this.recordGuards = recordGuards == null ? List.of() : List.copyOf(recordGuards);
        this.assignmentPolicyService = assignmentPolicyService == null
                ? new WorkflowTaskAssignmentPolicyService()
                : assignmentPolicyService;
    }

    public void requireRuntimeAction(WorkflowInstance instance, String actionCode) {
        if (instance == null) {
            throw new PlatformException("workflow instance must not be null");
        }
        String validActionCode = requireText(actionCode, "workflow action code must not be blank");
        String moduleAlias = requireText(instance.getModuleAlias(), "workflow module alias must not be blank");
        String recordId = requireText(instance.getRecordId(), "workflow record id must not be blank");
        ActionExecutionPolicy policy = runtimePolicy(validActionCode);
        executionPolicyService.requireRecordAction(ActionExecutionContext.ofPolicy(
                moduleAlias,
                policy,
                Set.of(recordId),
                CurrentUserContext.currentUser()));
        requireRecordDataScope(moduleAlias, recordId, policy);
    }

    public void requireRecordView(WorkflowInstance instance) {
        requireRuntimeAction(instance, "view");
    }

    public void requireManagementAction(String actionCode) {
        String validActionCode = requireText(actionCode, "workflow action code must not be blank");
        executionPolicyService.requireAuthorized(ActionExecutionContext.ofPolicy(
                MANAGEMENT_MODULE_ALIAS,
                managementPolicy(validActionCode),
                Set.of(),
                CurrentUserContext.currentUser()));
    }

    public void requireTaskOperator(WorkflowTask task, String actionCode, String operatorId) {
        requireText(actionCode, "workflow action code must not be blank");
        requireAssignee(task, operatorId);
    }

    public void requireNodeTaskAction(WorkflowTask task, WorkflowNodeInstance node, String actionCode,
                                      String operatorId, String reason) {
        requireText(actionCode, "workflow action code must not be blank");
        requireAssignee(task, operatorId);
        requireNodeAction(node, actionCode, reason);
    }

    public void requireInstanceAction(String actionCode, String reason) {
        requireText(actionCode, "workflow action code must not be blank");
        if ("revoke".equals(actionCode) || "terminate".equals(actionCode) || "reset".equals(actionCode)
                || "forceTerminate".equals(actionCode)) {
            requireReason(actionCode, reason);
        }
    }

    public void requireManagementTaskAction(String actionCode, String reason) {
        String validActionCode = requireText(actionCode, "workflow action code must not be blank");
        requireManagementAction(validActionCode);
        if (MANAGEMENT_FORCE_APPROVE_ACTION.equals(validActionCode)) {
            requireReason(validActionCode, reason);
        }
    }

    public void requireManagementInstanceAction(String actionCode, String reason) {
        String validActionCode = requireText(actionCode, "workflow action code must not be blank");
        requireManagementAction(validActionCode);
        if (MANAGEMENT_FORCE_TERMINATE_ACTION.equals(validActionCode)
                || MANAGEMENT_RESET_ACTION.equals(validActionCode)) {
            requireReason(validActionCode, reason);
        }
    }

    private void requireNodeAction(WorkflowNodeInstance node, String actionCode, String reason) {
        if (node == null) {
            throw new PlatformException("workflow node must not be null");
        }
        switch (actionCode) {
            case "reject" -> {
                if (Boolean.FALSE.equals(node.getAllowReject())) {
                    throw new PlatformException("workflow node does not allow reject: " + node.getNodeKey());
                }
                if (Boolean.TRUE.equals(node.getRequireRejectReason())) {
                    requireReason(actionCode, reason);
                }
            }
            case "rollback" -> {
                if (Boolean.FALSE.equals(node.getAllowRollback())) {
                    throw new PlatformException("workflow node does not allow rollback: " + node.getNodeKey());
                }
                if (Boolean.TRUE.equals(node.getRequireRollbackReason())) {
                    requireReason(actionCode, reason);
                }
            }
            case "terminate" -> {
                if (Boolean.FALSE.equals(node.getAllowTerminate())) {
                    throw new PlatformException("workflow node does not allow terminate: " + node.getNodeKey());
                }
                if (Boolean.TRUE.equals(node.getRequireTerminateReason())) {
                    requireReason(actionCode, reason);
                }
            }
            case "addSign" -> {
                if (!Boolean.TRUE.equals(node.getAllowAddSign())) {
                    throw new PlatformException("workflow node does not allow add sign: " + node.getNodeKey());
                }
                requireReason(actionCode, reason);
            }
            default -> {
            }
        }
    }

    public void requireRejectReturnToMe(WorkflowNodeInstance node) {
        if (node == null) {
            throw new PlatformException("workflow node must not be null");
        }
        if (!Boolean.TRUE.equals(node.getAllowRejectReturnToMe())) {
            throw new PlatformException("workflow node does not allow reject return to me: " + node.getNodeKey());
        }
    }

    private void requireAssignee(WorkflowTask task, String operatorId) {
        if (task == null) {
            throw new PlatformException("workflow task must not be null");
        }
        String validOperatorId = requireText(operatorId, "workflow operator id must not be blank");
        if (CurrentUserContext.currentUser()
                .filter(user -> user.system() && validOperatorId.equals(user.userId()))
                .isPresent()) {
            return;
        }
        if (!assignmentPolicyService.canProcess(task, validOperatorId)) {
            throw new PlatformException("workflow task action operator is not assignee or delegated principal: "
                    + task.getId());
        }
    }

    private void requireReason(String actionCode, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new PlatformException("workflow action reason is required: " + actionCode);
        }
    }

    public static ActionExecutionPolicy runtimePolicy(String actionCode) {
        return new ActionExecutionPolicy(
                actionCode,
                PlatformActionLevel.RECORD,
                ActionAccessMode.AUTH_REQUIRED,
                true,
                true,
                ActionDefaultGrantPolicy.NONE,
                null
        );
    }

    private ActionExecutionPolicy managementPolicy(String actionCode) {
        return new ActionExecutionPolicy(
                actionCode,
                PlatformActionLevel.LIST,
                ActionAccessMode.AUTH_REQUIRED,
                true,
                false,
                ActionDefaultGrantPolicy.NONE,
                null
        );
    }

    private void requireRecordDataScope(String moduleAlias, String recordId, ActionExecutionPolicy policy) {
        for (WorkflowModuleRecordGuard recordGuard : recordGuards) {
            recordGuard.requireRecordAction(moduleAlias, recordId, policy);
        }
    }

    private String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new PlatformException(message);
        }
        return value;
    }
}
