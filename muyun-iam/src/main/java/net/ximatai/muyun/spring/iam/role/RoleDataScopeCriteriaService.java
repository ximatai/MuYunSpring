package net.ximatai.muyun.spring.iam.role;

import net.ximatai.muyun.database.core.builder.sql.SchemaBuildRules;
import net.ximatai.muyun.database.core.orm.CompiledCriteria;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.CriteriaSqlCompiler;
import net.ximatai.muyun.database.core.orm.SqlRawCondition;
import net.ximatai.muyun.database.core.orm.SqlSubQuery;
import net.ximatai.muyun.database.core.metadata.DBInfo;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.ActingContext;
import net.ximatai.muyun.spring.common.identity.ActingContextHolder;
import net.ximatai.muyun.spring.common.identity.BusinessPrincipal;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.platform.ActionDefaultGrantPolicy;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaResult;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaService;
import net.ximatai.muyun.spring.common.platform.DataScopeFieldMapping;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.platform.ReferenceDependencyScopePlan;
import net.ximatai.muyun.spring.common.platform.ReferenceDependencyScopeRequest;
import net.ximatai.muyun.spring.common.platform.ReferenceDependencyScopeResolver;
import net.ximatai.muyun.spring.common.schema.PlatformAbilityFields;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;
import net.ximatai.muyun.spring.iam.department.DepartmentService;
import net.ximatai.muyun.spring.iam.organization.OrganizationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
public class RoleDataScopeCriteriaService implements DataScopeCriteriaService {
    private final CriteriaSqlCompiler criteriaSqlCompiler = new CriteriaSqlCompiler();
    private final RoleService roleService;
    private final TenantAdminImplicitGrantPolicy tenantAdminImplicitGrantPolicy;
    private final Optional<OrganizationService> organizationService;
    private final Optional<DepartmentService> departmentService;
    private final Optional<ReferenceDependencyScopeResolver> referenceDependencyScopeResolver;

    public RoleDataScopeCriteriaService(RoleService roleService) {
        this(roleService, null, Optional.empty(), Optional.empty(), Optional.empty());
    }

    public RoleDataScopeCriteriaService(RoleService roleService, Optional<OrganizationService> organizationService) {
        this(roleService, null, organizationService, Optional.empty(), Optional.empty());
    }

    public RoleDataScopeCriteriaService(RoleService roleService,
                                        Optional<OrganizationService> organizationService,
                                        Optional<ReferenceDependencyScopeResolver> referenceDependencyScopeResolver) {
        this(roleService, null, organizationService, Optional.empty(), referenceDependencyScopeResolver);
    }

    public RoleDataScopeCriteriaService(RoleService roleService,
                                        TenantAdminImplicitGrantPolicy tenantAdminImplicitGrantPolicy) {
        this(roleService, tenantAdminImplicitGrantPolicy, Optional.empty(), Optional.empty(), Optional.empty());
    }

    public RoleDataScopeCriteriaService(RoleService roleService,
                                        Optional<OrganizationService> organizationService,
                                        Optional<DepartmentService> departmentService,
                                        Optional<ReferenceDependencyScopeResolver> referenceDependencyScopeResolver) {
        this(roleService, null, organizationService, departmentService, referenceDependencyScopeResolver);
    }

    @Autowired
    public RoleDataScopeCriteriaService(RoleService roleService,
                                        TenantAdminImplicitGrantPolicy tenantAdminImplicitGrantPolicy,
                                        Optional<OrganizationService> organizationService,
                                        Optional<DepartmentService> departmentService,
                                        Optional<ReferenceDependencyScopeResolver> referenceDependencyScopeResolver) {
        this.roleService = Objects.requireNonNull(roleService, "roleService must not be null");
        this.tenantAdminImplicitGrantPolicy = tenantAdminImplicitGrantPolicy;
        this.organizationService = organizationService == null ? Optional.empty() : organizationService;
        this.departmentService = departmentService == null ? Optional.empty() : departmentService;
        this.referenceDependencyScopeResolver = referenceDependencyScopeResolver == null
                ? Optional.empty()
                : referenceDependencyScopeResolver;
    }

    @Override
    public DataScopeCriteriaResult resolveReadScope(String moduleAlias,
                                                    String actionCode,
                                                    Criteria criteria,
                                                    Optional<CurrentUser> currentUser) {
        return resolveReadScope(moduleAlias, policyOf(actionCode), criteria, currentUser);
    }

    @Override
    public DataScopeCriteriaResult resolveReadScope(String moduleAlias,
                                                    ActionExecutionPolicy policy,
                                                    Criteria criteria,
                                                    Optional<CurrentUser> currentUser) {
        return resolveReadScope(moduleAlias, policy, criteria, currentUser, DataScopeFieldMapping.STANDARD);
    }

    @Override
    public DataScopeCriteriaResult resolveReadScope(String moduleAlias,
                                                    ActionExecutionPolicy policy,
                                                    Criteria criteria,
                                                    Optional<CurrentUser> currentUser,
                                                    DataScopeFieldMapping fieldMapping) {
        return resolveReadScope(moduleAlias, policy, criteria, currentUser,
                fieldMapping == null ? DataScopeFieldMapping.STANDARD : fieldMapping, new HashSet<>());
    }

    private DataScopeCriteriaResult resolveReadScope(String moduleAlias,
                                                     ActionExecutionPolicy policy,
                                                     Criteria criteria,
                                                     Optional<CurrentUser> currentUser,
                                                     DataScopeFieldMapping fieldMapping,
                                                     Set<String> visiting) {
        Objects.requireNonNull(policy, "policy must not be null");
        Criteria base = criteria == null ? Criteria.of() : criteria;
        CurrentUser user = currentUser.orElse(null);
        if (user == null) {
            return DataScopeCriteriaResult.restricted(combine(base, denied()));
        }
        if (user.system()) {
            return DataScopeCriteriaResult.unrestricted(base);
        }
        if (hasDirectTenantAdministratorAccess(user, moduleAlias, policy)) {
            // TenantContext remains bound to the current tenant; this deliberately does not
            // produce a cross-tenant scope or bypass the base tenant filter.
            return DataScopeCriteriaResult.unrestricted(base);
        }
        String visitKey = moduleAlias + ":" + policy.permissionActionCode();
        if (!visiting.add(visitKey)) {
            return DataScopeCriteriaResult.restricted(combine(base, denied()));
        }
        ActingContext actingContext = actingContext(moduleAlias, policy, user);
        BusinessPrincipal principal = actingContext == null ? null : actingContext.principal();
        List<EffectiveRoleActionGrant> grants = principal == null
                ? roleService.effectiveActionGrantsWithContext(user.userId(), moduleAlias, policy.permissionActionCode())
                : roleService.effectiveActionGrantsWithContext(principal, moduleAlias, policy.permissionActionCode());
        try {
            List<GrantScope> scopes = grantScopes(moduleAlias, policy, user, principal, grants, fieldMapping, visiting);
            if (scopes.isEmpty()) {
                return DataScopeCriteriaResult.restricted(combine(base, denied()));
            }
            return combineGrantedScopes(base, user, scopes, grants.stream()
                    .map(EffectiveRoleActionGrant::actionGrant)
                    .anyMatch(this::allowsCrossTenant));
        } finally {
            visiting.remove(visitKey);
        }
    }

    private ActingContext actingContext(String moduleAlias, ActionExecutionPolicy policy, CurrentUser user) {
        ActingContext actingContext = ActingContextHolder.current()
                .filter(acting -> acting.matches(moduleAlias, policy.actionCode()))
                .orElse(null);
        if (actingContext == null) {
            return null;
        }
        if (!user.userId().equals(actingContext.operator().userId())) {
            throw new PlatformException("acting context operator does not match current user");
        }
        return actingContext;
    }

    private boolean hasDirectTenantAdministratorAccess(CurrentUser user,
                                                        String moduleAlias,
                                                        ActionExecutionPolicy policy) {
        if (user.tenantId() == null || user.tenantId().isBlank()) {
            return false;
        }
        if (ActingContextHolder.current().filter(acting -> acting.matches(moduleAlias, policy.actionCode())).isPresent()) {
            return false;
        }
        return tenantAdminImplicitGrantPolicy != null
                && tenantAdminImplicitGrantPolicy.grants(user, moduleAlias, policy.actionCode());
    }

    private ActionExecutionPolicy policyOf(String actionCode) {
        Optional<PlatformAction> platformAction = PlatformAction.fromCode(actionCode);
        if (platformAction.isPresent()) {
            return platformAction.get().executionPolicy();
        }
        return new ActionExecutionPolicy(
                actionCode,
                null,
                null,
                true,
                true,
                ActionDefaultGrantPolicy.NONE,
                null
        );
    }

    private List<GrantScope> grantScopes(String moduleAlias,
                                         ActionExecutionPolicy policy,
                                         CurrentUser user,
                                         BusinessPrincipal principal,
                                         List<EffectiveRoleActionGrant> grants,
                                         DataScopeFieldMapping fieldMapping,
                                         Set<String> visiting) {
        java.util.ArrayList<GrantScope> scopes = new java.util.ArrayList<>();
        GrantScope defaultScope = principal == null
                ? resolveDefaultScope(policy.defaultGrantPolicy(), user, fieldMapping)
                : GrantScope.none();
        if (defaultScope.contributes()) {
            scopes.add(defaultScope);
        }
        if (grants != null) {
            grants.stream()
                    .map(grant -> resolveGrantScope(moduleAlias, grant, user, principal, fieldMapping, visiting))
                    .filter(GrantScope::contributes)
                    .forEach(scopes::add);
        }
        return List.copyOf(scopes);
    }

    private DataScopeCriteriaResult combineGrantedScopes(Criteria base,
                                                         CurrentUser user,
                                                         List<GrantScope> scopes,
                                                         boolean hasCrossTenantGrant) {
        Criteria combinedScope = Criteria.of();
        boolean contributedCrossTenantScope = false;

        for (GrantScope grantScope : scopes) {
            if (grantScope.allData()) {
                DataScopeCriteriaResult result = resolveAllScope(base, grantScope.crossTenant(), hasCrossTenantGrant);
                if (result != null) {
                    return result;
                }
                appendCurrentTenantScope(combinedScope, user);
                continue;
            }
            appendGrantScope(combinedScope, grantScope, user, hasCrossTenantGrant);
            contributedCrossTenantScope = contributedCrossTenantScope || grantScope.crossTenant();
        }

        if (combinedScope.isEmpty()) {
            return DataScopeCriteriaResult.restricted(combine(base, denied()));
        }

        Criteria scoped = combine(base, combinedScope);
        return contributedCrossTenantScope
                ? DataScopeCriteriaResult.crossTenantRestricted(scoped)
                : DataScopeCriteriaResult.restricted(scoped);
    }

    @Override
    public Criteria applyReadScope(String moduleAlias,
                                   String actionCode,
                                   Criteria criteria,
                                   Optional<CurrentUser> currentUser) {
        return resolveReadScope(moduleAlias, actionCode, criteria, currentUser).criteria();
    }

    private DataScopeCriteriaResult resolveAllScope(Criteria base,
                                                    boolean grantCrossTenant,
                                                    boolean hasCrossTenantGrant) {
        if (grantCrossTenant) {
            return DataScopeCriteriaResult.crossTenantUnrestricted(base);
        }
        if (!hasCrossTenantGrant) {
            return DataScopeCriteriaResult.unrestricted(base);
        }
        return null;
    }

    private void appendGrantScope(Criteria combinedScope,
                                  GrantScope grantScope,
                                  CurrentUser user,
                                  boolean hasCrossTenantGrant) {
        if (hasCrossTenantGrant && !grantScope.crossTenant()) {
            combinedScope.orGroup(currentTenantScoped(grantScope.criteria(), user).getRoot());
            return;
        }
        combinedScope.orGroup(grantScope.criteria().getRoot());
    }

    private GrantScope resolveGrantScope(String moduleAlias,
                                         EffectiveRoleActionGrant effectiveGrant,
                                         CurrentUser user,
                                         BusinessPrincipal principal,
                                         DataScopeFieldMapping fieldMapping,
                                         Set<String> visiting) {
        RoleAction grant = effectiveGrant.actionGrant();
        DataScopePolicy policy = normalizePolicy(grant);
        if (effectiveGrant.roleGrant() != null
                && effectiveGrant.roleGrant().sourceType() == RoleAssignmentType.ACCOUNT) {
            return resolveAccountManagementScope(effectiveGrant.roleGrant(), user, fieldMapping, grant);
        }
        if (policy == DataScopePolicy.ALL) {
            return GrantScope.all(allowsCrossTenant(grant));
        }
        if (policy == DataScopePolicy.INHERIT_DATA_GRANT) {
            return resolveInheritedDataGrantScope(moduleAlias, grant, effectiveGrant.roleGrant(), user, principal,
                    fieldMapping, visiting);
        }
        if (policy == DataScopePolicy.REFERENCE_DEPENDENCY) {
            if (principal != null) {
                return GrantScope.none();
            }
            return resolveReferenceDependencyScope(moduleAlias, grant, user, visiting);
        }
        Criteria criteria = criteriaForPolicy(policy, user, principal, effectiveGrant.roleGrant(), fieldMapping);
        return GrantScope.restricted(criteria, allowsCrossTenant(grant));
    }

    private GrantScope resolveAccountManagementScope(EffectiveRoleGrant roleGrant,
                                                     CurrentUser user,
                                                     DataScopeFieldMapping fieldMapping,
                                                     RoleAction actionGrant) {
        ManagementScopeType scopeType = roleGrant.managementScopeType();
        if (scopeType == ManagementScopeType.PLATFORM) {
            return GrantScope.all(allowsCrossTenant(actionGrant));
        }
        if (scopeType == ManagementScopeType.TENANT) {
            return GrantScope.all(false);
        }
        if (scopeType == ManagementScopeType.ORGANIZATION) {
            String organizationId = roleGrant.managementScopeId();
            String field = fieldMapping.organizationField();
            if (organizationId == null || field == null) {
                return GrantScope.none();
            }
            OrganizationService service = organizationService.orElseThrow(() ->
                    new PlatformException("organization management scope requires organization hierarchy support"));
            Criteria scope = Criteria.of();
            List<String> organizationIds = service.selfAndDescendantIds(organizationId);
            if (!organizationIds.isEmpty()) {
                scope.orIn(field, organizationIds);
            }
            return GrantScope.restricted(scope, false);
        }
        return GrantScope.none();
    }

    private GrantScope resolveInheritedDataGrantScope(String moduleAlias,
                                                      RoleAction grant,
                                                      EffectiveRoleGrant roleGrant,
                                                      CurrentUser user,
                                                      BusinessPrincipal principal,
                                                      DataScopeFieldMapping fieldMapping,
                                                      Set<String> visiting) {
        RoleAction inheritedGrant = roleService.inheritedDataGrantAction(roleGrant, moduleAlias, grant.getActionCode());
        if (inheritedGrant == null) {
            return GrantScope.none();
        }
        DataScopePolicy inheritedPolicy = normalizePolicy(inheritedGrant);
        if (inheritedPolicy == DataScopePolicy.INHERIT_DATA_GRANT
                || inheritedPolicy == DataScopePolicy.CUSTOM
                || inheritedPolicy == DataScopePolicy.REFERENCE_DEPENDENCY) {
            return GrantScope.none();
        }
        GrantScope resolved = resolveGrantScope(moduleAlias, new EffectiveRoleActionGrant(inheritedGrant, roleGrant),
                user, principal, fieldMapping, visiting);
        if (!resolved.contributes()) {
            return GrantScope.none();
        }
        return resolved.crossTenant() && !allowsCrossTenant(grant)
                ? new GrantScope(resolved.criteria(), resolved.allData(), false)
                : resolved;
    }

    private GrantScope resolveReferenceDependencyScope(String moduleAlias,
                                                       RoleAction grant,
                                                       CurrentUser user,
                                                       Set<String> visiting) {
        String referenceActionCode = normalizeReferenceActionCode(grant);
        ReferenceDependencyScopePlan plan = referenceDependencyScopeResolver
                .flatMap(resolver -> resolver.resolve(new ReferenceDependencyScopeRequest(
                        moduleAlias, grant.getReferenceFieldId(), referenceActionCode)))
                .orElse(null);
        if (plan == null) {
            return GrantScope.none();
        }
        DataScopeCriteriaResult targetScope = resolveReadScope(
                plan.targetModuleAlias(),
                policyOf(referenceActionCode),
                Criteria.of(),
                Optional.of(user),
                DataScopeFieldMapping.STANDARD,
                visiting
        );
        Criteria targetCriteria = targetScope.criteria();
        if (!targetScope.crossTenant()) {
            if (user.tenantId() == null) {
                return GrantScope.none();
            }
            targetCriteria = combine(targetCriteria, Criteria.of().eq(StandardEntitySchema.TENANT_ID_FIELD, user.tenantId()));
        }
        targetCriteria = combine(targetCriteria, Criteria.of().eq(StandardEntitySchema.DELETED_FIELD, Boolean.FALSE));
        CompiledCriteria compiled = criteriaSqlCompiler.compile(targetCriteria,
                plan::resolveTargetColumn, plan.databaseType());
        String subQuery = "SELECT " + quote(plan.resolveTargetColumn(StandardEntitySchema.ID_FIELD), plan.databaseType())
                + " FROM " + SchemaBuildRules.qualifiedName(plan.targetSchemaName(), plan.targetTableName(), plan.databaseType())
                + where(compiled);
        boolean crossTenant = allowsCrossTenant(grant) && targetScope.crossTenant();
        return GrantScope.restricted(Criteria.of().inSubQuery(
                plan.sourceField(), SqlSubQuery.of(subQuery, compiled.getParams())), crossTenant);
    }

    private GrantScope resolveDefaultScope(ActionDefaultGrantPolicy policy,
                                           CurrentUser user,
                                           DataScopeFieldMapping fieldMapping) {
        return switch (normalizeDefaultPolicy(policy)) {
            case NONE, ANY_LOGIN_USER -> GrantScope.none();
            case OWNER -> GrantScope.restricted(criteriaForPolicies(user, fieldMapping, DataScopePolicy.OWNER), false);
            case ASSIGNEE -> GrantScope.restricted(criteriaForPolicies(
                    user, fieldMapping, DataScopePolicy.OWNER, DataScopePolicy.ASSIGNEE), false);
            case MEMBER -> GrantScope.restricted(criteriaForPolicies(
                    user, fieldMapping, DataScopePolicy.OWNER, DataScopePolicy.ASSIGNEE, DataScopePolicy.MEMBER), false);
        };
    }

    private Criteria criteriaForPolicy(DataScopePolicy policy, CurrentUser user) {
        return criteriaForPolicy(policy, user, null, null, DataScopeFieldMapping.STANDARD);
    }

    private Criteria criteriaForPolicy(DataScopePolicy policy,
                                       CurrentUser user,
                                       BusinessPrincipal principal,
                                       EffectiveRoleGrant roleGrant,
                                       DataScopeFieldMapping fieldMapping) {
        return criteriaForPolicies(user, principal, roleGrant, fieldMapping, policy);
    }

    private Criteria criteriaForPolicies(CurrentUser user, DataScopePolicy... policies) {
        return criteriaForPolicies(user, DataScopeFieldMapping.STANDARD, policies);
    }

    private Criteria criteriaForPolicies(CurrentUser user,
                                         DataScopeFieldMapping fieldMapping,
                                         DataScopePolicy... policies) {
        return criteriaForPolicies(user, null, null, fieldMapping, policies);
    }

    private Criteria criteriaForPolicies(CurrentUser user,
                                         BusinessPrincipal principal,
                                         EffectiveRoleGrant roleGrant,
                                         DataScopeFieldMapping fieldMapping,
                                         DataScopePolicy... policies) {
        Criteria scope = Criteria.of();
        if (policies != null) {
            for (DataScopePolicy policy : policies) {
                appendScope(scope, policy, user, principal, roleGrant,
                        fieldMapping == null ? DataScopeFieldMapping.STANDARD : fieldMapping);
            }
        }
        return scope;
    }

    private void appendScope(Criteria scope,
                             DataScopePolicy policy,
                             CurrentUser user,
                             BusinessPrincipal principal,
                             EffectiveRoleGrant roleGrant,
                             DataScopeFieldMapping fieldMapping) {
        switch (normalizePolicy(policy)) {
            case OWNER -> {
                String userId = scopeUserId(user, principal);
                String field = fieldMapping.ownerUserField();
                if (userId != null && field != null) {
                    scope.orEq(field, userId);
                }
            }
            case ASSIGNEE -> {
                String userId = scopeUserId(user, principal);
                String column = fieldMapping.assigneeColumn();
                if (userId != null && column != null) {
                    scope.orRaw(csvContains(column, "userId", userId));
                }
            }
            case MEMBER -> {
                String userId = scopeUserId(user, principal);
                String column = fieldMapping.memberColumn();
                if (userId != null && column != null) {
                    scope.orRaw(csvContains(column, "userId", userId));
                }
            }
            case ORGANIZATION -> {
                String organizationId = scopeOrganizationId(user, principal, roleGrant);
                String field = fieldMapping.organizationField();
                if (organizationId != null && field != null) {
                    scope.orEq(field, organizationId);
                }
            }
            case ORGANIZATION_AND_CHILDREN -> appendOrganizationAndChildrenScope(scope, user, principal, roleGrant,
                    fieldMapping);
            case DEPARTMENT -> {
                String departmentId = scopeDepartmentId(principal, roleGrant);
                String field = fieldMapping.departmentField();
                if (departmentId != null && field != null) {
                    scope.orEq(field, departmentId);
                }
            }
            case DEPARTMENT_AND_CHILDREN -> appendDepartmentAndChildrenScope(scope, principal, roleGrant, fieldMapping);
            case CUSTOM ->
                    throw new PlatformException("custom data scope condition is not supported yet");
            case INHERIT_DATA_GRANT ->
                    throw new PlatformException("inherited data grant must be resolved before append scope");
            case REFERENCE_DEPENDENCY -> {
            }
            case NONE, ALL -> {
            }
        }
    }

    private void appendCurrentTenantScope(Criteria scope, CurrentUser user) {
        if (user.tenantId() == null) {
            scope.orGroup(denied().getRoot());
            return;
        }
        scope.orEq(StandardEntitySchema.TENANT_ID_FIELD, user.tenantId());
    }

    private Criteria currentTenantScoped(Criteria grantScope, CurrentUser user) {
        if (user.tenantId() == null) {
            return denied();
        }
        return Criteria.of()
                .eq(StandardEntitySchema.TENANT_ID_FIELD, user.tenantId())
                .andGroup(grantScope.getRoot());
    }

    private void appendOrganizationAndChildrenScope(Criteria scope,
                                                    CurrentUser user,
                                                    BusinessPrincipal principal,
                                                    EffectiveRoleGrant roleGrant,
                                                    DataScopeFieldMapping fieldMapping) {
        String organizationId = scopeOrganizationId(user, principal, roleGrant);
        String field = fieldMapping.organizationField();
        if (organizationId == null || field == null) {
            return;
        }
        OrganizationService service = organizationService.orElseThrow(() ->
                new PlatformException("organization children data scope requires organization hierarchy support"));
        List<String> organizationIds = service.selfAndDescendantIds(organizationId);
        if (!organizationIds.isEmpty()) {
            scope.orIn(field, organizationIds);
        }
    }

    private void appendDepartmentAndChildrenScope(Criteria scope,
                                                  BusinessPrincipal principal,
                                                  EffectiveRoleGrant roleGrant,
                                                  DataScopeFieldMapping fieldMapping) {
        String organizationId = scopeOrganizationId(null, principal, roleGrant);
        String departmentId = scopeDepartmentId(principal, roleGrant);
        String field = fieldMapping.departmentField();
        if (organizationId == null || departmentId == null || field == null) {
            return;
        }
        DepartmentService service = departmentService.orElseThrow(() ->
                new PlatformException("department children data scope requires department hierarchy support"));
        List<String> departmentIds = service.selfAndDescendantIds(organizationId, departmentId);
        if (!departmentIds.isEmpty()) {
            scope.orIn(field, departmentIds);
        }
    }

    private String scopeUserId(CurrentUser user, BusinessPrincipal principal) {
        if (principal != null) {
            return principal.userId();
        }
        return user.userId();
    }

    private String scopeOrganizationId(CurrentUser user, BusinessPrincipal principal, EffectiveRoleGrant roleGrant) {
        String contextOrganizationId = roleGrant == null ? null : roleGrant.organizationId();
        if (contextOrganizationId != null && !contextOrganizationId.isBlank()) {
            return contextOrganizationId;
        }
        if (principal != null) {
            return principal.organizationId();
        }
        return user == null ? null : user.organizationId();
    }

    private String scopeDepartmentId(BusinessPrincipal principal, EffectiveRoleGrant roleGrant) {
        String contextDepartmentId = roleGrant == null ? null : roleGrant.departmentId();
        if (contextDepartmentId != null && !contextDepartmentId.isBlank()) {
            return contextDepartmentId;
        }
        return principal == null ? null : principal.departmentId();
    }

    private DataScopePolicy normalizePolicy(RoleAction grant) {
        return grant.getDataScopePolicy() == null ? DataScopePolicy.NONE : grant.getDataScopePolicy();
    }

    private DataScopePolicy normalizePolicy(DataScopePolicy policy) {
        return policy == null ? DataScopePolicy.NONE : policy;
    }

    private ActionDefaultGrantPolicy normalizeDefaultPolicy(ActionDefaultGrantPolicy policy) {
        return policy == null ? ActionDefaultGrantPolicy.NONE : policy;
    }

    private boolean allowsCrossTenant(RoleAction grant) {
        return grant != null && grant.getTenantScopePolicy() == TenantScopePolicy.ALL_TENANTS;
    }

    private SqlRawCondition csvContains(String columnName, String paramName, String value) {
        return SqlRawCondition.of(
                "CONCAT(',', " + columnName + ", ',') LIKE :" + paramName,
                Map.of(paramName, "%," + value + ",%")
        );
    }

    private String normalizeReferenceActionCode(RoleAction grant) {
        String actionCode = grant == null ? null : grant.getReferenceActionCode();
        return actionCode == null || actionCode.isBlank() ? PlatformAction.REFERENCE.code() : actionCode.trim();
    }

    private String where(CompiledCriteria criteria) {
        String sql = criteria == null ? "" : criteria.getSql();
        return sql == null || sql.isBlank() ? "" : " WHERE " + sql;
    }

    private String quote(String identifier, DBInfo.Type databaseType) {
        return SchemaBuildRules.quoteIdentifier(identifier, databaseType);
    }

    private Criteria denied() {
        return Criteria.of().raw(SqlRawCondition.of("1 = 0", Map.of()));
    }

    private Criteria combine(Criteria base, Criteria scope) {
        if (scope == null || scope.isEmpty()) {
            return base;
        }
        if (base == null || base.isEmpty()) {
            return scope;
        }
        return Criteria.of()
                .andGroup(base.getRoot())
                .andGroup(scope.getRoot());
    }

    private record GrantScope(Criteria criteria, boolean allData, boolean crossTenant) {
        private GrantScope {
            criteria = criteria == null ? Criteria.of() : criteria;
        }

        static GrantScope all(boolean crossTenant) {
            return new GrantScope(Criteria.of(), true, crossTenant);
        }

        static GrantScope restricted(Criteria criteria, boolean crossTenant) {
            return new GrantScope(criteria, false, crossTenant);
        }

        static GrantScope none() {
            return new GrantScope(Criteria.of(), false, false);
        }

        boolean contributes() {
            return allData || !criteria.isEmpty();
        }
    }
}
