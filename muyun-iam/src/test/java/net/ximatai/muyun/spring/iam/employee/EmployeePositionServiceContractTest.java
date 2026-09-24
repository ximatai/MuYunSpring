package net.ximatai.muyun.spring.iam.employee;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.action.BusinessException;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmployeePositionServiceContractTest {
    @Test
    void shouldExposeStableInternalModuleAlias() {
        EmployeePositionService service = service(mock(EmployeePositionDao.class));

        assertThat(service.getModuleAlias()).isEqualTo("iam.employee_position");
    }

    @Test
    void shouldFillRelationDefaultsThroughCrudAbility() {
        EmployeePositionDao dao = mock(EmployeePositionDao.class);
        when(dao.insert(any())).thenReturn("relation-1");
        ActiveTenantVerifier tenantVerifier = activeTenantVerifier();
        EmployeePositionService service = service(dao, tenantVerifier);
        EmployeePosition relation = relation("employee-1", "org-1", "dept-1", "position-1", true);

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            service.insert(relation);
        }

        assertThat(relation.getEnabled()).isTrue();
        assertThat(relation.getPrimaryPosition()).isTrue();
        assertThat(relation.getTenantId()).isEqualTo("tenant_a");
        verify(tenantVerifier).verifyActiveTenant("tenant_a");
    }

    @Test
    void shouldRequireTenantContextForMutation() {
        EmployeePositionService service = service(mock(EmployeePositionDao.class));

        assertThatThrownBy(() -> service.insert(relation("employee-1", "org-1", "dept-1", "position-1", false)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("tenant context");
    }

    @Test
    void shouldRequireRelationFields() {
        EmployeePositionService service = service(mock(EmployeePositionDao.class));

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            assertThatThrownBy(() -> service.insert(relation(" ", "org-1", "dept-1", "position-1", false)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("employeeId");
            assertThatThrownBy(() -> service.insert(relation("employee-1", " ", "dept-1", "position-1", false)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("organizationId");
            assertThatThrownBy(() -> service.insert(relation("employee-1", "org-1", " ", "position-1", false)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("departmentId");
            assertThatThrownBy(() -> service.insert(relation("employee-1", "org-1", "dept-1", " ", false)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("positionId");
        }
    }

    @Test
    void shouldAllowNonPrimaryCrossDepartmentRelationFact() {
        EmployeePositionService service = service(mock(EmployeePositionDao.class));

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            service.beforeInsert(relation("employee-1", "org-1", "dept-2", "position-1", false));
        }
    }

    @Test
    void shouldRejectPrimaryPositionOutsideEmployeeMainDepartment() {
        EmployeePositionService service = service(mock(EmployeePositionDao.class));

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            assertThatThrownBy(() -> service.beforeInsert(
                    relation("employee-1", "org-1", "dept-2", "position-1", true)))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.actionMessage().code())
                                    .isEqualTo("iam.employee-position.primary-owner-mismatch"))
                    .hasMessage("主岗位的机构和部门必须与职员主机构、主部门一致");
        }
    }

    @Test
    void shouldExposePrimaryPositionDuplicateAsBusinessError() {
        EmployeePositionDao dao = mock(EmployeePositionDao.class);
        EmployeePosition existing = relation("employee-1", "org-1", "dept-1", "position-1", true);
        existing.setId("relation-existing");
        when(dao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(existing));
        EmployeePositionService service = service(dao);

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            assertThatThrownBy(() -> service.beforeInsert(
                    relation("employee-1", "org-1", "dept-1", "position-2", true)))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.actionMessage().code())
                                    .isEqualTo("iam.employee-position.primary-already-exists"))
                    .hasMessage("该职员已有主岗位");
        }
    }

    @Test
    void shouldExposeDuplicateEmploymentAsBusinessError() {
        EmployeePositionDao dao = mock(EmployeePositionDao.class);
        EmployeePosition existing = relation("employee-1", "org-1", "dept-1", "position-1", false);
        existing.setId("relation-existing");
        when(dao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(existing));
        EmployeePositionService service = service(dao);

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            assertThatThrownBy(() -> service.beforeInsert(
                    relation("employee-1", "org-1", "dept-1", "position-1", false)))
                    .isInstanceOfSatisfying(BusinessException.class, exception ->
                            assertThat(exception.actionMessage().code())
                                    .isEqualTo("iam.employee-position.already-exists"))
                    .hasMessage("该职员已存在相同任职");
        }
    }

    @Test
    void shouldResolveEmployeeScopedPositions() {
        EmployeePositionService service = spy(service(mock(EmployeePositionDao.class)));
        EmployeePosition relation = relation("employee-1", "org-1", "dept-1", "position-1", true);
        doReturn(List.of(relation)).when(service).list(any(), any(), any());

        assertThat(service.positions("employee-1")).containsExactly(relation);
    }

    @Test
    void shouldReadRoleResolutionPositionsWithoutReferenceProjection() {
        EmployeePositionDao dao = mock(EmployeePositionDao.class);
        EmployeePosition relation = relation("employee-1", "org-1", "dept-1", "position-1", true);
        when(dao.query(any(Criteria.class), any(PageRequest.class), any())).thenReturn(List.of(relation));
        EmployeePositionService service = spy(service(dao));
        doThrow(new AssertionError("role resolution must not use the projected list read"))
                .when(service).list(any(), any(), any());

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            assertThat(service.activePositionsForRoleResolution("employee-1")).containsExactly(relation);
        }

        verify(dao).query(any(Criteria.class), any(PageRequest.class), any());
    }

    @Test
    void shouldRejectSortAcrossEmployees() {
        EmployeePositionService service = service(mock(EmployeePositionDao.class));

        assertThatThrownBy(() -> service.validateSortScope(
                relation("employee-1", "org-1", "dept-1", "position-1", true),
                relation("employee-2", "org-1", "dept-1", "position-1", true)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("same employee");
    }

    @Test
    void shouldSwitchPrimaryPositionWithinSameEmployee() {
        EmployeePositionService service = spy(service(mock(EmployeePositionDao.class)));
        EmployeePosition existingPrimary = relation("employee-1", "org-1", "dept-1", "position-1", true);
        existingPrimary.setId("relation-old");
        existingPrimary.setEnabled(Boolean.TRUE);
        EmployeePosition target = relation("employee-1", "org-1", "dept-1", "position-2", false);
        target.setId("relation-new");
        target.setEnabled(Boolean.FALSE);
        doReturn(target).when(service).select("relation-new");
        doReturn(List.of(existingPrimary)).when(service).list(any(Criteria.class), any(PageRequest.class));
        doReturn(1).when(service).update(any(EmployeePosition.class));

        try (TenantContext.Scope ignored = TenantContext.use("tenant_a")) {
            assertThat(service.makePrimaryPosition("employee-1", "relation-new")).isEqualTo(2);
        }

        assertThat(existingPrimary.getPrimaryPosition()).isFalse();
        assertThat(target.getPrimaryPosition()).isTrue();
        assertThat(target.getEnabled()).isTrue();
        verify(service).update(existingPrimary);
        verify(service).update(target);
    }

    @Test
    void shouldPersistOutgoingPrimaryBeforeIncomingPrimaryInAggregateReplacement() {
        EmployeePositionService service = service(mock(EmployeePositionDao.class));
        EmployeePosition existingPrimary = relation("employee-1", "org-1", "dept-1", "position-1", true);
        existingPrimary.setId("relation-old");
        existingPrimary.setEnabled(Boolean.TRUE);
        EmployeePosition incomingOld = relation("employee-1", "org-1", "dept-1", "position-1", false);
        incomingOld.setId("relation-old");
        EmployeePosition incomingNew = relation("employee-1", "org-1", "dept-1", "position-2", true);
        incomingNew.setId("relation-new");

        assertThat(service.orderForReplacement(List.of(incomingNew, incomingOld), List.of(existingPrimary)))
                .containsExactly(incomingOld, incomingNew);
    }

    private EmployeePositionService service(EmployeePositionDao dao) {
        return service(dao, activeTenantVerifier());
    }

    private EmployeePositionService service(EmployeePositionDao dao, ActiveTenantVerifier tenantVerifier) {
        EmployeeService employeeService = mock(EmployeeService.class);
        when(employeeService.requireEnabledOrThrow(eq("employee-1"), any())).thenReturn(employee("employee-1", "org-1", "dept-1"));
        return new EmployeePositionService(dao, tenantVerifier, employeeService);
    }

    private EmployeePosition relation(String employeeId, String organizationId, String departmentId,
                                      String positionId, boolean primaryPosition) {
        EmployeePosition relation = new EmployeePosition();
        relation.setEmployeeId(employeeId);
        relation.setOrganizationId(organizationId);
        relation.setDepartmentId(departmentId);
        relation.setPositionId(positionId);
        relation.setPrimaryPosition(primaryPosition);
        return relation;
    }

    private Employee employee(String id, String organizationId, String departmentId) {
        Employee employee = new Employee();
        employee.setId(id);
        employee.setOrganizationId(organizationId);
        employee.setDepartmentId(departmentId);
        employee.setEnabled(Boolean.TRUE);
        return employee;
    }

    private ActiveTenantVerifier activeTenantVerifier() {
        return mock(ActiveTenantVerifier.class);
    }
}
