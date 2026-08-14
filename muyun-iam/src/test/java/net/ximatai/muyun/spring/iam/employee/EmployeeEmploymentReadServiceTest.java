package net.ximatai.muyun.spring.iam.employee;

import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.spring.ability.reference.ReferenceReadFacade;
import net.ximatai.muyun.spring.ability.reference.ReferenceLoadResolver;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.iam.user.UserAccountService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmployeeEmploymentReadServiceTest {
    @Test
    void shouldKeepRetainedEmployeeFieldsInRecycleBinEmploymentProjection() {
        EmployeePositionService employeePositionService = mock(EmployeePositionService.class);
        EmployeeService employeeService = mock(EmployeeService.class);
        EmployeeAccountService employeeAccountService = mock(EmployeeAccountService.class);
        UserAccountService userAccountService = mock(UserAccountService.class);
        Employee retained = new Employee();
        retained.setId("employee-1");
        retained.setEmployeeNo("E001");
        retained.setTitle("测试职员");
        retained.setDeleted(true);
        EmployeePosition employment = new EmployeePosition();
        employment.setId("employment-1");
        employment.setEmployeeId("employee-1");
        employment.setOrganizationId("org-1");
        employment.setDepartmentId("dept-1");
        employment.setPositionId("position-1");
        PageRequest page = PageRequest.of(1, 20);
        List<List<? extends EntityContract>> enrichedBatches = new ArrayList<>();
        ReferenceReadFacade referenceReads = new ReferenceReadFacade(new ReferenceLoadResolver() {
            @Override
            public void populate(net.ximatai.muyun.spring.ability.CrudAbility<?> ability, EntityContract entity) {
                throw new AssertionError("employment read must use batch enrichment");
            }

            @Override
            public void populateAll(net.ximatai.muyun.spring.ability.CrudAbility<?> ability,
                                    Collection<? extends EntityContract> entities) {
                enrichedBatches.add(List.copyOf(entities));
                entities.forEach(entity -> {
                    EmployeePosition relation = (EmployeePosition) entity;
                    relation.setOrganizationTitle("机构一");
                    relation.setDepartmentTitle("部门一");
                    relation.setPositionTitle("岗位一");
                });
            }
        });
        EmployeeEmploymentReadService readService = new EmployeeEmploymentReadService(
                employeePositionService, employeeService, employeeAccountService, userAccountService, referenceReads);

        when(employeePositionService.pageQuery(any(), any(), any()))
                .thenReturn(PageResult.of(List.of(employment), 1, page));
        when(employeeService.list(any(), any(PageRequest.class))).thenReturn(List.of());
        when(employeeAccountService.list(any(), any(PageRequest.class))).thenReturn(List.of());
        when(userAccountService.list(any(), any(PageRequest.class))).thenReturn(List.of());

        var result = readService.pageForEmployee(retained,
                new EmployeeEmploymentReadService.Query("employee-1", null, null, false, page));

        assertThat(result.getRecords()).singleElement().satisfies(view -> {
            assertThat(view.employeeNo()).isEqualTo("E001");
            assertThat(view.employeeTitle()).isEqualTo("测试职员");
            assertThat(view.organizationTitle()).isEqualTo("机构一");
            assertThat(view.departmentTitle()).isEqualTo("部门一");
            assertThat(view.positionTitle()).isEqualTo("岗位一");
        });
        assertThat(enrichedBatches).containsExactly(List.of(employment));
    }
}
