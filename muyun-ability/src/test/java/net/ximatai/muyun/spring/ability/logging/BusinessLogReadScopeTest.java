package net.ximatai.muyun.spring.ability.logging;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessLogReadScopeTest {
    @Test
    void shouldIntersectOrganizationScopeWithEventAndStatisticsQueries() {
        BusinessLogReadScope scope = BusinessLogReadScope.organization("tenant-a", Set.of("organization-main",
                "organization-child"));

        BusinessLogQuery query = scope.constrain(new BusinessLogQuery(null, null, "tenant-a", null, null,
                Set.of("organization-child", "organization-other"), null, null, null, null, 20));
        BusinessLogStatisticsQuery statistics = scope.constrain(new BusinessLogStatisticsQuery(null, null, "tenant-a",
                null, Set.of("organization-main", "organization-other"), null, null, 100));

        assertThat(query.operatorOrganizationIds()).containsExactly("organization-child");
        assertThat(statistics.operatorOrganizationIds()).containsExactly("organization-main");
    }

    @Test
    void shouldPreserveTypedDetailFiltersWhileItNarrowsTheReadScope() {
        BusinessLogQuery query = new BusinessLogQuery(null, null, null,
                Set.of(BusinessLogEventType.REQUEST_ERROR), "operator", null, "sales.order", "submit",
                "ORDER_CONFLICT", "login-account", LoginLogDetails.LoginOutcome.FAILURE, 409, null, 20);

        BusinessLogQuery constrained = BusinessLogReadScope.tenant("tenant-a").constrain(query);

        assertThat(constrained.tenantId()).isEqualTo("tenant-a");
        assertThat(constrained.loginOutcome()).isEqualTo(LoginLogDetails.LoginOutcome.FAILURE);
        assertThat(constrained.loginAccount()).isEqualTo("login-account");
        assertThat(constrained.httpStatus()).isEqualTo(409);
    }

    @Test
    void shouldTurnContradictoryAndDeniedScopesIntoEmptyOrganizationFilters() {
        BusinessLogReadScope tenantScope = BusinessLogReadScope.tenant("tenant-a");
        BusinessLogReadScope denied = BusinessLogReadScope.denied();
        BusinessLogQuery otherTenant = new BusinessLogQuery(Instant.EPOCH, Instant.now(), "tenant-b", null,
                null, null, null, null, null, null, 20);

        assertThat(tenantScope.constrain(otherTenant).tenantId()).isEqualTo("tenant-a");
        assertThat(tenantScope.constrain(otherTenant).operatorOrganizationIds()).isEmpty();
        assertThat(denied.constrain(BusinessLogQuery.newest(20)).operatorOrganizationIds()).isEmpty();
        assertThat(denied.constrain(BusinessLogStatisticsQuery.recent(100)).operatorOrganizationIds()).isEmpty();
    }
}
