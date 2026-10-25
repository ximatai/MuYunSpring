package net.ximatai.muyun.spring.ability.event;

import net.ximatai.muyun.spring.common.identity.ActingContext;
import net.ximatai.muyun.spring.common.identity.ActingContextHolder;
import net.ximatai.muyun.spring.common.identity.BusinessPrincipal;
import net.ximatai.muyun.spring.common.identity.JobExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContextHolder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public final class RuntimeEventAuditContext {
    public static final String ACTING_DELEGATION_ID = "actingDelegationId";
    public static final String ACTING_PRINCIPAL_USER_ID = "actingPrincipalUserId";
    public static final String ACTING_PRINCIPAL_EMPLOYEE_ID = "actingPrincipalEmployeeId";
    public static final String ACTING_PRINCIPAL_ORGANIZATION_ID = "actingPrincipalOrganizationId";
    public static final String ACTING_PRINCIPAL_DEPARTMENT_ID = "actingPrincipalDepartmentId";
    public static final String ACTING_PRINCIPAL_EMPLOYEE_POSITION_ID = "actingPrincipalEmployeePositionId";

    public static final String JOB_TYPE = "jobType";
    public static final String JOB_PLAN_ID = "jobPlanId";
    public static final String JOB_RUN_ID = "jobRunId";
    public static final String JOB_IDENTITY_MODE = "jobIdentityMode";

    private RuntimeEventAuditContext() {
    }

    public static Map<String, Object> capture(String moduleAlias, String actionCode) {
        Map<String, Object> context = new LinkedHashMap<>(ActingContextHolder.current()
                .filter(acting -> matches(acting, moduleAlias, actionCode))
                .map(RuntimeEventAuditContext::actingContext)
                .orElse(Map.of()));
        JobExecutionContext.current().ifPresent(job -> {
            context.put(JOB_TYPE, job.jobType());
            putText(context, JOB_PLAN_ID, job.planId());
            context.put(JOB_RUN_ID, job.runId());
            context.put(JOB_IDENTITY_MODE, job.identityMode());
        });
        return Map.copyOf(context);
    }

    public static String text(Map<String, Object> auditContext, String key) {
        if (auditContext == null || !auditContext.containsKey(key)) {
            return null;
        }
        Object value = auditContext.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static boolean matches(ActingContext acting, String moduleAlias, String actionCode) {
        if (actionCode != null && !actionCode.isBlank()) {
            return acting.matches(moduleAlias, actionCode);
        }
        String currentActionCode = ActionExecutionContextHolder.current()
                .filter(context -> context.moduleAlias().equals(normalize(moduleAlias)))
                .map(ActionExecutionContext::actionCode)
                .orElse(null);
        if (currentActionCode != null && !currentActionCode.isBlank()) {
            return acting.matches(moduleAlias, currentActionCode);
        }
        String actingModule = acting.moduleAlias();
        return actingModule == null || actingModule.equals(normalize(moduleAlias));
    }

    private static Map<String, Object> actingContext(ActingContext acting) {
        BusinessPrincipal principal = acting.principal();
        Map<String, Object> context = new LinkedHashMap<>();
        putText(context, ACTING_DELEGATION_ID, acting.delegationId());
        putText(context, ACTING_PRINCIPAL_USER_ID, principal.userId());
        putText(context, ACTING_PRINCIPAL_EMPLOYEE_ID, principal.employeeId());
        putText(context, ACTING_PRINCIPAL_ORGANIZATION_ID, principal.organizationId());
        putText(context, ACTING_PRINCIPAL_DEPARTMENT_ID, principal.departmentId());
        putText(context, ACTING_PRINCIPAL_EMPLOYEE_POSITION_ID, principal.employeePositionId());
        return Map.copyOf(context);
    }

    private static void putText(Map<String, Object> context, String key, String value) {
        Optional.ofNullable(normalize(value)).ifPresent(normalized -> context.put(key, normalized));
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
