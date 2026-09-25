package net.ximatai.muyun.spring.iam.employee;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.CriteriaClause;
import net.ximatai.muyun.database.core.orm.CriteriaOperator;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.action.BusinessException;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.ActingContext;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmployeeDelegationServiceContractTest {
    @Test
    void shouldExposeStableInternalModuleAlias() {
        EmployeeDelegationService service = service(mock(EmployeeDelegationDao.class));

        assertThat(service.getModuleAlias()).isEqualTo("iam.employee_delegation");
    }

    @Test
    void shouldFillDefaultsThroughCrudAbility() {
        EmployeeDelegationDao dao = mock(EmployeeDelegationDao.class);
        when(dao.insert(any())).thenReturn("delegation-1");
        ActiveTenantVerifier tenantVerifier = activeTenantVerifier();
        EmployeeDelegationService service = service(dao, tenantVerifier);
        EmployeeDelegation delegation = delegation("principal-1", "delegate-1");
        delegation.setDelegationType(null);
        delegation.setEnabled(null);

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            service.insert(delegation);
        }

        assertThat(delegation.getDelegationType()).isEqualTo(EmployeeDelegationType.BUSINESS);
        assertThat(delegation.getModuleScopeType()).isEqualTo(EmployeeDelegationScopeType.ALL);
        assertThat(delegation.getModuleAliases()).isEmpty();
        assertThat(delegation.getActionScopeType()).isEqualTo(EmployeeDelegationScopeType.ALL);
        assertThat(delegation.getActionKeys()).isEmpty();
        assertThat(delegation.getEnabled()).isTrue();
        assertThat(delegation.getTenantId()).isEqualTo("tenant_a");
        verify(tenantVerifier).verifyActiveTenant("tenant_a");
    }

    @Test
    void shouldClearScopeValuesWhenScopeTypeIsAll() {
        EmployeeDelegationService service = service(mock(EmployeeDelegationDao.class));
        EmployeeDelegation delegation = delegation("principal-1", "delegate-1");
        delegation.setModuleScopeType(EmployeeDelegationScopeType.ALL);
        delegation.setModuleAliases(Set.of("sales.contract"));
        delegation.setActionScopeType(EmployeeDelegationScopeType.ALL);
        delegation.setActionKeys(Set.of("sales.contract#create"));

        service.normalizeBeforeMutation(delegation);

        assertThat(delegation.getModuleAliases()).isEmpty();
        assertThat(delegation.getActionKeys()).isEmpty();
    }

    @Test
    void shouldRequirePrincipalAndDelegateEmployees() {
        EmployeeDelegationService service = service(mock(EmployeeDelegationDao.class));

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            assertThatThrownBy(() -> service.insert(delegation(null, "delegate-1")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("principalEmployeeId");
            assertThatThrownBy(() -> service.insert(delegation(" ", "delegate-1")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("principalEmployeeId");
            assertThatThrownBy(() -> service.insert(delegation("principal-1", " ")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("delegateEmployeeId");
        }
    }

    @Test
    void shouldRejectSelfDelegation() {
        EmployeeDelegationService service = service(mock(EmployeeDelegationDao.class));

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            assertThatThrownBy(() -> service.beforeInsert(delegation("principal-1", "principal-1")))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.actionMessage().code())
                                    .isEqualTo("iam.employee-delegation.self-delegation-denied"))
                    .hasMessageContaining("delegate must differ from principal");
        }
    }

    @Test
    void shouldRejectPositionWhenItDoesNotBelongToEmployee() {
        EmployeeDelegationService service = service(mock(EmployeeDelegationDao.class));
        EmployeeDelegation delegation = delegation("principal-1", "delegate-1");
        delegation.setPrincipalPositionId("delegate-position");

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            assertThatThrownBy(() -> service.beforeInsert(delegation))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.actionMessage().code())
                                    .isEqualTo("iam.employee-delegation.principal-position-owner-mismatch"))
                    .hasMessageContaining("principal position does not belong");
        }
    }

    @Test
    void shouldRejectInvalidEffectiveRange() {
        EmployeeDelegationService service = service(mock(EmployeeDelegationDao.class));
        EmployeeDelegation delegation = delegation("principal-1", "delegate-1");
        delegation.setEffectiveFrom(Instant.parse("2026-01-02T00:00:00Z"));
        delegation.setEffectiveTo(Instant.parse("2026-01-01T00:00:00Z"));

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            assertThatThrownBy(() -> service.beforeInsert(delegation))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.actionMessage().code())
                                    .isEqualTo("iam.employee-delegation.invalid-effective-range"))
                    .hasMessageContaining("effectiveFrom must be before effectiveTo");
        }
    }

    @Test
    void shouldRequireValuesForIncludeScopes() {
        EmployeeDelegationService service = service(mock(EmployeeDelegationDao.class));
        EmployeeDelegation moduleScoped = delegation("principal-1", "delegate-1");
        moduleScoped.setModuleScopeType(EmployeeDelegationScopeType.INCLUDE);
        EmployeeDelegation actionScoped = delegation("principal-1", "delegate-1");
        actionScoped.setActionScopeType(EmployeeDelegationScopeType.INCLUDE);

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            assertThatThrownBy(() -> service.beforeInsert(moduleScoped))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.actionMessage().code())
                                    .isEqualTo("iam.employee-delegation.module-scope-required"))
                    .hasMessageContaining("moduleAliases are required");
            assertThatThrownBy(() -> service.beforeInsert(actionScoped))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.actionMessage().code())
                                    .isEqualTo("iam.employee-delegation.action-scope-required"))
                    .hasMessageContaining("actionKeys are required");
        }
    }

    @Test
    void shouldUseIsNullCriteriaForNullableDuplicateFields() {
        EmployeeDelegationService service = spy(service(mock(EmployeeDelegationDao.class)));
        org.mockito.ArgumentCaptor<Criteria> criteriaCaptor = org.mockito.ArgumentCaptor.forClass(Criteria.class);
        doReturn(List.of()).when(service).list(criteriaCaptor.capture(), any(PageRequest.class));

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            service.beforeInsert(delegation("principal-1", "delegate-1"));
        }

        Criteria criteria = criteriaCaptor.getValue();
        assertClause(criteria, "principalPositionId", CriteriaOperator.IS_NULL);
        assertClause(criteria, "delegatePositionId", CriteriaOperator.IS_NULL);
        assertClause(criteria, "effectiveFrom", CriteriaOperator.IS_NULL);
        assertClause(criteria, "effectiveTo", CriteriaOperator.IS_NULL);
    }

    @Test
    void shouldRejectDuplicateDelegationWithoutPositionsOrTimeRange() {
        EmployeeDelegationService service = spy(service(mock(EmployeeDelegationDao.class)));
        EmployeeDelegation existing = delegation("principal-1", "delegate-1");
        existing.setId("delegation-1");
        doReturn(List.of(existing)).when(service).list(any(Criteria.class), any(PageRequest.class));

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            assertThatThrownBy(() -> service.beforeInsert(delegation("principal-1", "delegate-1")))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("employee delegation already exists");
        }
    }

    @Test
    void shouldKeepExistingEnabledStateWhenUpdatingDelegation() {
        EmployeeDelegationService service = spy(service(mock(EmployeeDelegationDao.class)));
        EmployeeDelegation existing = delegation("principal-1", "delegate-1");
        existing.setId("delegation-1");
        existing.setEnabled(Boolean.FALSE);
        EmployeeDelegation incoming = delegation("principal-1", "delegate-1");
        doReturn(existing).when(service).select("delegation-1");
        doReturn(1).when(service).update(any(EmployeeDelegation.class));

        service.updateDelegation("principal-1", "delegation-1", incoming);

        assertThat(incoming.getEnabled()).isFalse();
    }

    @Test
    void shouldKeepExistingScopeWhenUpdatingDelegationWithoutScopePayload() {
        EmployeeDelegationService service = spy(service(mock(EmployeeDelegationDao.class)));
        EmployeeDelegation existing = delegation("principal-1", "delegate-1");
        existing.setId("delegation-1");
        existing.setModuleScopeType(EmployeeDelegationScopeType.INCLUDE);
        existing.setModuleAliases(Set.of("sales.contract"));
        existing.setActionScopeType(EmployeeDelegationScopeType.INCLUDE);
        existing.setActionKeys(Set.of("sales.contract#create"));
        EmployeeDelegation incoming = delegation("principal-1", "delegate-1");
        incoming.setModuleScopeType(null);
        incoming.setModuleAliases(null);
        incoming.setActionScopeType(null);
        incoming.setActionKeys(null);
        doReturn(existing).when(service).select("delegation-1");
        doReturn(1).when(service).update(any(EmployeeDelegation.class));

        service.updateDelegation("principal-1", "delegation-1", incoming);

        assertThat(incoming.getModuleScopeType()).isEqualTo(EmployeeDelegationScopeType.INCLUDE);
        assertThat(incoming.getModuleAliases()).containsExactly("sales.contract");
        assertThat(incoming.getActionScopeType()).isEqualTo(EmployeeDelegationScopeType.INCLUDE);
        assertThat(incoming.getActionKeys()).containsExactly("sales.contract#create");
    }

    @Test
    void shouldKeepEnableAbilityAbleToChangeEnabledState() {
        EmployeeDelegationDao dao = mock(EmployeeDelegationDao.class);
        EmployeeDelegation existing = delegation("principal-1", "delegate-1");
        existing.setId("delegation-1");
        existing.setEnabled(Boolean.TRUE);
        existing.setVersion(0);
        when(dao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(existing));
        when(dao.updateByIdAndVersion(any(EmployeeDelegation.class), any())).thenReturn(1);
        EmployeeDelegationService service = service(dao);

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            assertThat(service.disableDelegation("principal-1", "delegation-1")).isEqualTo(1);
            assertThat(existing.getEnabled()).isFalse();
            assertThat(service.enableDelegation("principal-1", "delegation-1")).isEqualTo(1);
            assertThat(existing.getEnabled()).isTrue();
        }
    }

    @Test
    void shouldQueryDelegationsByPrincipalAndDelegate() {
        EmployeeDelegationService service = spy(service(mock(EmployeeDelegationDao.class)));
        EmployeeDelegation byPrincipal = delegation("principal-1", "delegate-1");
        EmployeeDelegation byDelegate = delegation("principal-2", "delegate-1");
        doReturn(List.of(byPrincipal)).when(service).list(any(Criteria.class), any(PageRequest.class), any(), any());
        assertThat(service.delegationsByPrincipal("principal-1")).containsExactly(byPrincipal);

        doReturn(List.of(byDelegate)).when(service).list(any(Criteria.class), any(PageRequest.class), any(), any());
        assertThat(service.delegationsByDelegate("delegate-1")).containsExactly(byDelegate);
    }

    @Test
    void shouldRejectPrincipalScopedOperationWhenDelegationBelongsToAnotherPrincipal() {
        EmployeeDelegationService service = spy(service(mock(EmployeeDelegationDao.class)));
        EmployeeDelegation delegation = delegation("principal-2", "delegate-1");
        delegation.setId("delegation-1");
        doReturn(delegation).when(service).select("delegation-1");

        assertThatThrownBy(() -> service.deleteDelegation("principal-1", "delegation-1"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.actionMessage().code())
                                .isEqualTo("iam.employee-delegation.not-principal-owned"))
                .hasMessageContaining("does not belong to principal employee");
    }

    @Test
    void shouldForcePrincipalEmployeeWhenAddingOrUpdatingFromEmployeeEntry() {
        EmployeeDelegationService service = spy(service(mock(EmployeeDelegationDao.class)));
        EmployeeDelegation existing = delegation("principal-1", "delegate-1");
        existing.setId("delegation-1");
        EmployeeDelegation command = delegation("other", "delegate-1");
        doReturn("delegation-1").when(service).insert(any(EmployeeDelegation.class));
        doReturn(existing).when(service).select("delegation-1");
        doReturn(1).when(service).update(any(EmployeeDelegation.class));

        service.addDelegation("principal-1", command);
        assertThat(command.getPrincipalEmployeeId()).isEqualTo("principal-1");

        command.setPrincipalEmployeeId("other");
        service.updateDelegation("principal-1", "delegation-1", command);
        assertThat(command.getId()).isEqualTo("delegation-1");
        assertThat(command.getPrincipalEmployeeId()).isEqualTo("principal-1");
    }

    @Test
    void shouldResolveActingContextFromGlobalDelegation() {
        EmployeeDelegationService service = spy(service(mock(EmployeeDelegationDao.class)));
        EmployeeDelegation delegation = delegation("principal-1", "delegate-1");
        delegation.setId("delegation-1");
        doReturn(List.of(delegation)).when(service).list(any(Criteria.class), any(PageRequest.class), any());

        ActingContext context = service.resolveActingContext(
                CurrentUser.tenantUser("assistant-user", "Assistant", "tenant_a"),
                "delegate-1", null, "principal-1", null,
                "sales.contract", "create", Instant.parse("2026-01-01T00:00:00Z"));

        assertThat(context.delegationId()).isEqualTo("delegation-1");
        assertThat(context.operator().userId()).isEqualTo("assistant-user");
        assertThat(context.principal().employeeId()).isEqualTo("principal-1");
        assertThat(context.principal().organizationId()).isEqualTo("org-principal");
        assertThat(context.principal().departmentId()).isEqualTo("dept-principal");
        assertThat(context.principal().employeePositionId()).isNull();
        assertThat(context.matches("sales.contract", "create")).isTrue();
    }

    @Test
    void shouldRejectActingContextWhenOperatorIsNotDelegateEmployeeAccount() {
        EmployeeDelegationService service = spy(service(mock(EmployeeDelegationDao.class), activeTenantVerifier(),
                "other-employee"));

        assertThatThrownBy(() -> service.resolveActingContext(
                CurrentUser.tenantUser("assistant-user", "Assistant", "tenant_a"),
                "delegate-1", null, "principal-1", null,
                "sales.contract", "create", Instant.parse("2026-01-01T00:00:00Z")))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.actionMessage().code())
                                .isEqualTo("iam.employee-delegation.operator-not-delegate"))
                .hasMessageContaining("operator is not bound to delegate employee");
    }

    @Test
    void shouldResolveActingContextFromPrincipalPositionWithServerFacts() {
        EmployeeDelegationService service = spy(service(mock(EmployeeDelegationDao.class)));
        EmployeeDelegation delegation = delegation("principal-1", "delegate-1");
        delegation.setId("delegation-1");
        delegation.setPrincipalPositionId("principal-position");
        doReturn(List.of(delegation)).when(service).list(any(Criteria.class), any(PageRequest.class), any());

        ActingContext context = service.resolveActingContext(
                CurrentUser.tenantUser("assistant-user", "Assistant", "tenant_a"),
                "delegate-1", null, "principal-1", "principal-position",
                "sales.contract", "create", Instant.parse("2026-01-01T00:00:00Z"));

        assertThat(context.principal().employeeId()).isEqualTo("principal-1");
        assertThat(context.principal().employeePositionId()).isEqualTo("principal-position");
        assertThat(context.principal().organizationId()).isEqualTo("org-position");
        assertThat(context.principal().departmentId()).isEqualTo("dept-position");
    }

    @Test
    void shouldMatchModuleAndActionScopes() {
        EmployeeDelegationService service = spy(service(mock(EmployeeDelegationDao.class)));
        EmployeeDelegation moduleScoped = delegation("principal-1", "delegate-1");
        moduleScoped.setId("module-delegation");
        moduleScoped.setModuleScopeType(EmployeeDelegationScopeType.INCLUDE);
        moduleScoped.setModuleAliases(Set.of("sales.contract"));
        EmployeeDelegation actionScoped = delegation("principal-1", "delegate-1");
        actionScoped.setId("action-delegation");
        actionScoped.setActionScopeType(EmployeeDelegationScopeType.INCLUDE);
        actionScoped.setActionKeys(Set.of("sales.contract#create"));
        doReturn(List.of(moduleScoped, actionScoped)).when(service)
                .list(any(Criteria.class), any(PageRequest.class), any());

        ActingContext moduleContext = service.resolveActingContext(
                CurrentUser.tenantUser("assistant-user", "Assistant", "tenant_a"),
                "delegate-1", null, "principal-1", null,
                "sales.contract", "update", Instant.parse("2026-01-01T00:00:00Z"));
        ActingContext actionContext = service.resolveActingContext(
                CurrentUser.tenantUser("assistant-user", "Assistant", "tenant_a"),
                "delegate-1", null, "principal-1", null,
                "sales.contract", "create", Instant.parse("2026-01-01T00:00:00Z"));

        assertThat(moduleContext.delegationId()).isEqualTo("module-delegation");
        assertThat(actionContext.delegationId()).isEqualTo("action-delegation");
        assertThatThrownBy(() -> service.resolveActingContext(
                CurrentUser.tenantUser("assistant-user", "Assistant", "tenant_a"),
                "delegate-1", null, "principal-1", null,
                "sales.order", "create", Instant.parse("2026-01-01T00:00:00Z")))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.actionMessage().code())
                                .isEqualTo("iam.employee-delegation.not-allowed"))
                .hasMessageContaining("employee delegation is not allowed");
    }

    @Test
    void shouldRejectDisabledExpiredOrPositionMismatchedDelegation() {
        EmployeeDelegationService service = spy(service(mock(EmployeeDelegationDao.class)));
        EmployeeDelegation disabled = delegation("principal-1", "delegate-1");
        disabled.setId("disabled");
        disabled.setEnabled(Boolean.FALSE);
        EmployeeDelegation expired = delegation("principal-1", "delegate-1");
        expired.setId("expired");
        expired.setEffectiveTo(Instant.parse("2025-01-01T00:00:00Z"));
        EmployeeDelegation positionBound = delegation("principal-1", "delegate-1");
        positionBound.setId("position-bound");
        positionBound.setPrincipalPositionId("principal-position");
        doReturn(List.of(disabled, expired, positionBound)).when(service)
                .list(any(Criteria.class), any(PageRequest.class), any());

        assertThatThrownBy(() -> service.resolveActingContext(
                CurrentUser.tenantUser("assistant-user", "Assistant", "tenant_a"),
                "delegate-1", null, "principal-1", null,
                "sales.contract", "create", Instant.parse("2026-01-01T00:00:00Z")))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.actionMessage().code())
                                .isEqualTo("iam.employee-delegation.not-allowed"))
                .hasMessageContaining("employee delegation is not allowed");
    }

    private EmployeeDelegationService service(EmployeeDelegationDao dao) {
        return service(dao, activeTenantVerifier());
    }

    private EmployeeDelegationService service(EmployeeDelegationDao dao, ActiveTenantVerifier tenantVerifier) {
        return service(dao, tenantVerifier, "delegate-1");
    }

    private EmployeeDelegationService service(EmployeeDelegationDao dao,
                                             ActiveTenantVerifier tenantVerifier,
                                             String operatorEmployeeId) {
        EmployeeService employeeService = mock(EmployeeService.class);
        EmployeePositionService employeePositionService = mock(EmployeePositionService.class);
        EmployeeAccountService employeeAccountService = mock(EmployeeAccountService.class);
        when(employeeService.requireEnabled(eq("principal-1"), any()))
                .thenReturn(employee("principal-1"));
        when(employeeService.requireEnabled(eq("principal-2"), any()))
                .thenReturn(employee("principal-2"));
        when(employeeService.requireEnabled(eq("delegate-1"), any()))
                .thenReturn(employee("delegate-1"));
        when(employeePositionService.select("principal-position"))
                .thenReturn(position("principal-position", "principal-1", true));
        when(employeePositionService.select("delegate-position"))
                .thenReturn(position("delegate-position", "delegate-1", true));
        when(employeePositionService.select("disabled-position"))
                .thenReturn(position("disabled-position", "principal-1", false));
        when(employeeAccountService.employeeIdOfUser("assistant-user")).thenReturn(operatorEmployeeId);
        return new EmployeeDelegationService(dao, tenantVerifier, employeeService, employeePositionService,
                employeeAccountService);
    }

    private EmployeeDelegation delegation(String principalEmployeeId, String delegateEmployeeId) {
        EmployeeDelegation delegation = new EmployeeDelegation();
        delegation.setPrincipalEmployeeId(principalEmployeeId);
        delegation.setDelegateEmployeeId(delegateEmployeeId);
        return delegation;
    }

    private Employee employee(String id) {
        Employee employee = new Employee();
        employee.setId(id);
        employee.setOrganizationId("org-" + id.replace("principal-1", "principal").replace("delegate-1", "delegate"));
        employee.setDepartmentId("dept-" + id.replace("principal-1", "principal").replace("delegate-1", "delegate"));
        employee.setEnabled(Boolean.TRUE);
        return employee;
    }

    private EmployeePosition position(String id, String employeeId, boolean enabled) {
        EmployeePosition position = new EmployeePosition();
        position.setId(id);
        position.setEmployeeId(employeeId);
        position.setOrganizationId("org-position");
        position.setDepartmentId("dept-position");
        position.setEnabled(enabled);
        return position;
    }

    private void assertClause(Criteria criteria, String field, CriteriaOperator operator) {
        CriteriaClause clause = criteria.getClauses().stream()
                .filter(item -> field.equals(item.getField()))
                .findFirst()
                .orElseThrow();
        assertThat(clause.getOperator()).isEqualTo(operator);
    }

    private ActiveTenantVerifier activeTenantVerifier() {
        return mock(ActiveTenantVerifier.class);
    }
}
