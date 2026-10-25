package net.ximatai.muyun.spring.platform.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.identity.*;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.module.ModuleActionInvoker;
import org.springframework.beans.factory.ObjectProvider;
import java.time.Duration;
import java.util.*;

/** The identity reference and action input belong to the immutable run, never the current plan. */
public final class ModuleActionJob implements JobHandler {
    public static final String TENANT_TYPE = "platform.module-action";
    public static final String PLATFORM_TYPE = "platform.module-action-platform";
    private final JobScope scope;
    private final ObjectProvider<ModuleActionInvoker> actions;
    private final ScheduledActionPlanDao plans;
    private final net.ximatai.muyun.spring.common.platform.JobSystemActionPolicy whitelist;
    private final ObjectProvider<TrustedExecutionIdentityResolver> identities;
    private final ObjectMapper json;
    public ModuleActionJob(JobScope scope, ObjectProvider<ModuleActionInvoker> actions, ScheduledActionPlanDao plans, net.ximatai.muyun.spring.common.platform.JobSystemActionPolicy whitelist, ObjectProvider<TrustedExecutionIdentityResolver> identities, ObjectMapper json) {
        this.scope = Objects.requireNonNull(scope); this.actions = Objects.requireNonNull(actions); this.plans = Objects.requireNonNull(plans); this.whitelist = Objects.requireNonNull(whitelist);
        this.identities = Objects.requireNonNull(identities); this.json = Objects.requireNonNull(json);
    }
    @Override public JobDefinition definition() {
        return new JobDefinition(scope == JobScope.TENANT ? TENANT_TYPE : PLATFORM_TYPE, "platform.job.type.moduleAction", scope,
                null, "default", null, Duration.ofMinutes(5), 1, Duration.ZERO,
                input -> input.get("planKey").toString(), true, 3, true, null);
    }
    @Override public void requirePlanChange(JobPlan plan) {
        if ("SYSTEM".equals(plan.getIdentityMode())) SystemActionWhitelistService.requirePlatformAdministrator();
    }
    @Override public List<String> failureUserIds(JobRun run) {
        if (!"INITIATOR".equals(run.getIdentityMode())) return List.of();
        var ref = JobService.parameters(run.getParametersJson()).get("identity");
        return ref instanceof Map<?, ?> identity && identity.get("userId") instanceof String id ? List.of(id) : List.of();
    }
    @Override public void planStateChanged(JobPlan plan) {
        var row = plans.findById(plan.getObjectKey());
        if (row != null && Objects.equals(row.getJobPlanId(), plan.getId())) {
            row.setEnabled(plan.getEnabled());
            if (plans.updateByIdAndVersion(row, row.getVersion()) != 1)
                throw new net.ximatai.muyun.spring.ability.OptimisticLockException("Scheduled action plan changed");
        }
    }
    @Override public boolean supportsIdentityMode(String mode) { return "SYSTEM".equals(mode) || "INITIATOR".equals(mode); }
    @Override public JobResult execute(JobContext context) {
        var input = context.parameters(); String module = (String) input.get("moduleAlias"), code = (String) input.get("actionCode");
        if ("INITIATOR".equals(context.identityMode())) {
            var resolver = identities.getIfAvailable();
            var ref = json.convertValue(input.get("identity"), TrustedExecutionIdentityResolver.Reference.class);
            if (context.tenantId() == null || !context.tenantId().equals(ref.accountTenantId())) throw new JobFailure("IDENTITY_SCOPE_MISMATCH", false);
            TrustedExecutionIdentityResolver.Identity identity;
            try (var system = TenantContext.system("resolve scheduled initiator")) {
                identity = resolver == null ? null : resolver.resolve(ref, module, code).orElse(null);
            }
            if (identity == null || identity.user().system() || identity.user().passwordChangeRequired()
                    || !context.tenantId().equals(identity.user().tenantId())) throw new JobFailure("IDENTITY_WITHDRAWN", false);
            try (var user = CurrentUserContext.use(identity.user()); var tenant = TenantContext.use(context.tenantId());
                 var acting = identity.actingContext() == null ? ActingContextHolder.without() : ActingContextHolder.use(identity.actingContext())) {
                return invoke(context, module, code);
            }
        }
        return invoke(context, module, code);
    }
    @SuppressWarnings("unchecked")
    private JobResult invoke(JobContext context, String module, String code) {
        return context.transaction(() -> {
            if ("SYSTEM".equals(context.identityMode()) && !whitelist.allows(module, code))
                throw new JobFailure("SYSTEM_ACTION_NOT_WHITELISTED", false);
            actions.getObject().invoke(module, code, (Map<String, Object>) context.parameters().get("arguments"));
            return JobResult.success("ACTION_EXECUTED", Map.of());
        });
    }
}
