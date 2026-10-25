package net.ximatai.muyun.spring.platform.job;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.CriteriaGroup;
import net.ximatai.muyun.database.core.orm.PageRequest;
import org.mockito.ArgumentCaptor;
import net.ximatai.muyun.database.core.orm.CriteriaClause;
import net.ximatai.muyun.spring.common.exception.AuthenticationRequiredException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JobManagementQueryScopeTest {
    @Test
    void tenantAdministratorRemainsInOwnTenantEvenWhenAmbientFilterWasBypassed() {
        List<String> authorized = new ArrayList<>();
        try (var user = CurrentUserContext.use(CurrentUser.tenantUser("admin", "admin", "tenant-a"));
             var context = TenantContext.system("test ambient system context")) {
            Criteria scoped = JobManagementQueryScope.apply(Criteria.of().eq("jobType", "sample"), authorized::add);
            assertThat(authorized).containsExactly("tenant-a");
            assertThat(conditions(scoped.getRoot())).anySatisfy(condition -> {
                assertThat(condition.getField()).isEqualTo("tenantId");
                assertThat(condition.getValues().getFirst()).isEqualTo("tenant-a");
            });
        }
    }

    @Test
    void platformAdministratorCanSeeAllScopesEvenWithSelectedTenant() {
        List<String> authorized = new ArrayList<>();
        try (var user = CurrentUserContext.use(CurrentUser.systemUser("admin", "admin"));
             var context = TenantContext.use("selected-tenant")) {
            assertThat(conditions(JobManagementQueryScope.apply(Criteria.of(), authorized::add).getRoot())).isEmpty();
            assertThat(authorized).containsExactly((String) null);
        }
    }

    @Test
    void unauthenticatedAndNonAdministrativeReadsFailClosed() {
        try (var ignored = CurrentUserContext.without()) {
            assertThatThrownBy(() -> JobManagementQueryScope.apply(Criteria.of(), tenant -> {}))
                    .isInstanceOf(AuthenticationRequiredException.class);
        }
        try (var ignored = CurrentUserContext.use(CurrentUser.tenantUser("user", "user", "tenant-a"))) {
            assertThatThrownBy(() -> JobManagementQueryScope.apply(Criteria.of(), tenant -> {
                throw new SecurityException("administrator required");
            })).isInstanceOf(SecurityException.class);
        }
    }

    @Test
    void standardDetailReadCombinesRecordIdWithAdministratorTenantScope() {
        JobRunDao dao = mock(JobRunDao.class);
        when(dao.query(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of());
        var service = new JobRunManagementService(dao, tenant -> assertThat(tenant).isEqualTo("tenant-a"), new JobRegistry(List.of()));
        try (var ignored = CurrentUserContext.use(CurrentUser.tenantUser("admin", "admin", "tenant-a"))) {
            assertThat(service.select("other-tenant-run")).isNull();
        }
        ArgumentCaptor<Criteria> query = ArgumentCaptor.forClass(Criteria.class);
        verify(dao).query(query.capture(), any(PageRequest.class));
        assertThat(conditions(query.getValue().getRoot())).anySatisfy(condition -> {
            assertThat(condition.getField()).isEqualTo("tenantId");
            assertThat(condition.getValues().getFirst()).isEqualTo("tenant-a");
        });
    }

    private static List<CriteriaClause> conditions(CriteriaGroup group) {
        List<CriteriaClause> result = new ArrayList<>();
        for (CriteriaGroup.Entry entry : group.getEntries()) {
            if (entry.getNode() instanceof CriteriaClause condition) result.add(condition);
            if (entry.getNode() instanceof CriteriaGroup nested) result.addAll(conditions(nested));
        }
        return result;
    }
}
