package net.ximatai.muyun.spring.platform.job;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.database.core.orm.*;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.query.*;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.identity.*;
import net.ximatai.muyun.spring.common.platform.JobSystemActionPolicy;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.module.ModuleActionInvoker;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import java.util.*;

/** Topic facade: normal CRUD and JobService registration share the mutation transaction. */
@Service
public class ScheduledActionPlanService extends AbstractAbilityService<ScheduledActionPlan> implements QueryAbility<ScheduledActionPlan> {
    public static final String MODULE_ALIAS = "platform.scheduled_action_plan";
    private final ScheduledActionPlanDao rows;
    private final JobService jobs;
    private final ModuleActionInvoker actions;
    private final ObjectProvider<TrustedExecutionIdentityResolver> identities;
    private final JobSystemActionPolicy whitelist;
    private final JobManagementAccess access;
    private final ObjectMapper json;
    public ScheduledActionPlanService(ScheduledActionPlanDao rows, JobService jobs, ModuleActionInvoker actions,
            ObjectProvider<TrustedExecutionIdentityResolver> identities, JobSystemActionPolicy whitelist,
            JobManagementAccess access, ObjectMapper json) {
        super(MODULE_ALIAS, ScheduledActionPlan.class, rows); this.rows = Objects.requireNonNull(rows); this.jobs = Objects.requireNonNull(jobs); this.actions = Objects.requireNonNull(actions);
        this.identities = Objects.requireNonNull(identities); this.whitelist = Objects.requireNonNull(whitelist); this.access = Objects.requireNonNull(access); this.json = Objects.requireNonNull(json);
    }
    @Override public QueryDescriptor queryDescriptor() {
        var declared = QueryDescriptors.fromModel(MODULE_ALIAS, ScheduledActionPlan.class,
                List.of("id", "tenantId", "moduleAlias", "actionCode", "identityMode", "executionUserId", "jobPlanId", "enabled", "createdAt"), Sort.desc("createdAt"));
        var builder = QueryDescriptor.builder(MODULE_ALIAS).defaultSort(Sort.desc("createdAt"));
        declared.fields().forEach(field -> builder.field(Set.of("moduleAlias", "actionCode").contains(field.fieldName()) ? field.withQuickSearch() : field));
        return builder.build();
    }
    @Override public Criteria activeCriteria(Criteria criteria) {
        var user = user(); Criteria scope = Criteria.of();
        if (criteria != null && !criteria.isEmpty()) scope.andGroup(criteria.getRoot());
        if (user.system()) return scope;
        scope.eq("tenantId", user.tenantId());
        try { access.requireAdministrator(user.tenantId()); return scope; }
        catch (PlatformAccessDeniedException ordinaryUser) {
            var visible = rows.query(Criteria.of().eq("tenantId", user.tenantId()), new PageRequest(0, Integer.MAX_VALUE)).stream()
                    .filter(row -> authorized(row)).map(ScheduledActionPlan::getId).toList();
            return scope.in("id", visible.isEmpty() ? List.of("__none__") : visible);
        }
    }
    private boolean authorized(ScheduledActionPlan row) {
        try { actions.requireAuthorized(row.getModuleAlias(), row.getActionCode(), arguments(row.getArgumentsJson())); return true; }
        catch (RuntimeException denied) { return false; }
    }
    @Override public Integer expectedVersionForUpdate(ScheduledActionPlan incoming, ScheduledActionPlan existing) {
        if (incoming.getVersion() == null || existing != null && !Objects.equals(incoming.getVersion(), existing.getVersion()))
            throw new net.ximatai.muyun.spring.ability.OptimisticLockException("Scheduled action plan changed");
        return incoming.getVersion();
    }
    @Override public void beforeInsert(ScheduledActionPlan row) { row.setJobPlanId(null); save(row); }
    @Override public void beforeUpdate(ScheduledActionPlan row) {
        var old = rows.findById(row.getId()); requireOwned(old);
        if (!Objects.equals(old.getTenantId(), row.getTenantId())) throw new PlatformAccessDeniedException("Plan tenant cannot change");
        row.setJobPlanId(old.getJobPlanId()); save(row);
    }
    @Override public void beforeDelete(String id) {
        var row = rows.findById(id); requireOwned(row);
        jobs.deleteManaged(MODULE_ALIAS, row.getJobPlanId(), row.getTenantId());
    }
    private void requireOwned(ScheduledActionPlan row) {
        var user = user();
        if (row == null || !user.system() && !Objects.equals(row.getTenantId(), user.tenantId())) throw new PlatformAccessDeniedException("Plan outside current scope");
        if ("SYSTEM".equals(row.getIdentityMode())) SystemActionWhitelistService.requirePlatformAdministrator();
        else if (!user.system()) {
            try { access.requireAdministrator(row.getTenantId()); }
            catch (PlatformAccessDeniedException ordinaryUser) { actions.requireAuthorized(row.getModuleAlias(), row.getActionCode(), arguments(row.getArgumentsJson())); }
        }
    }
    private void save(ScheduledActionPlan row) {
        var user = user();
        if (!user.system() && (row.getTenantId() == null || !row.getTenantId().equals(user.tenantId())
                || !row.getTenantId().equals(TenantContext.currentTenantId().orElse(null)))) throw new PlatformAccessDeniedException("Plan must belong to current account tenant");
        try (var businessScope = row.getTenantId() == null ? TenantContext.system("configure platform scheduled action") : TenantContext.use(row.getTenantId())) {
            saveInScope(row, user);
        }
    }
    private void saveInScope(ScheduledActionPlan row, CurrentUser user) {
        if (!Set.of("INITIATOR", "SYSTEM").contains(row.getIdentityMode())) throw new IllegalArgumentException("Unsupported execution identity mode");
        if (user.system()) row.setIdentityMode("SYSTEM");
        var args = arguments(row.getArgumentsJson());
        actions.validateTarget(row.getModuleAlias(), row.getActionCode());
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("moduleAlias", row.getModuleAlias()); input.put("actionCode", row.getActionCode());
        input.put("arguments", args); input.put("planKey", row.getId());
        if ("SYSTEM".equals(row.getIdentityMode())) {
            SystemActionWhitelistService.requirePlatformAdministrator();
            if (!whitelist.allows(row.getModuleAlias(), row.getActionCode())) throw new PlatformAccessDeniedException("System scheduled action is not whitelisted");
            actions.requireAuthorized(row.getModuleAlias(), row.getActionCode(), args);
            row.setExecutionUserId(null);
        } else {
            var resolver = identities.getIfAvailable();
            var ref = resolver == null ? null : resolver.capture(row.getModuleAlias(), row.getActionCode()).orElse(null);
            if (ref == null || !ref.userId().equals(user.userId()) || ref.accountTenantId() == null || !ref.accountTenantId().equals(row.getTenantId()))
                throw new PlatformAccessDeniedException("Initiator must be a tenant account in the execution scope");
            actions.requireAuthorized(row.getModuleAlias(), row.getActionCode(), args);
            input.put("identity", json.convertValue(ref, new TypeReference<Map<String, Object>>() { }));
            row.setExecutionUserId(ref.userId());
        }
        var plan = new JobPlan(); plan.setId(row.getJobPlanId()); plan.setObjectKey(row.getId()); plan.setTenantId(row.getTenantId());
        plan.setJobType(row.getTenantId() == null ? ModuleActionJob.PLATFORM_TYPE : ModuleActionJob.TENANT_TYPE);
        plan.setIdentityMode(row.getIdentityMode()); plan.setScheduleKind(row.getScheduleKind()); plan.setExpression(row.getExpression());
        plan.setZoneId(row.getZoneId()); plan.setIntervalMillis(row.getIntervalMillis()); plan.setInitialDelayMillis(row.getInitialDelayMillis()); plan.setEnabled(row.getEnabled());
        row.setJobPlanId(jobs.registerManaged(MODULE_ALIAS, plan, input).getId());
    }
    static Map<String, Object> arguments(String source) {
        if (source != null && source.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 16384)
            throw new IllegalArgumentException("Action parameters exceed 16 KiB");
        var values = JobService.parameters(source); JobService.encodeParameters(values); rejectCredentials(values); return values;
    }
    private static void rejectCredentials(Object value) {
        if (value instanceof Map<?, ?> map) map.forEach((key, item) -> {
            String name = key.toString().replaceAll("[^A-Za-z]", "").toLowerCase(Locale.ROOT);
            if (name.contains("password") || name.contains("secret") || name.contains("token") || name.contains("credential")
                    || name.contains("authorization") || name.contains("apikey") || name.contains("cookie") || name.contains("privatekey"))
                throw new IllegalArgumentException("Credentials cannot be stored in action parameters");
            rejectCredentials(item);
        });
        else if (value instanceof Collection<?> list) list.forEach(ScheduledActionPlanService::rejectCredentials);
    }
    private static CurrentUser user() {
        var user = CurrentUserContext.currentUser().orElseThrow(() -> new PlatformAccessDeniedException("Authenticated account required"));
        if (user.passwordChangeRequired()) throw new PlatformAccessDeniedException("Password change required"); return user;
    }
}
