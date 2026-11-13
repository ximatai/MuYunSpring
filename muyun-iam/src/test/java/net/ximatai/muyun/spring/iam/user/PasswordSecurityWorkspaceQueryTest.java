package net.ximatai.muyun.spring.iam.user;

import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryCondition;
import net.ximatai.muyun.spring.ability.query.QueryOperator;
import net.ximatai.muyun.spring.ability.query.QueryRequest;
import net.ximatai.muyun.spring.iam.tenant.TenantService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PasswordSecurityWorkspaceQueryTest {
    @Test
    void bothModulesAcceptPlatformNullScopeAndTheDeclaredTenantNavigator() {
        List<QueryAbility<?>> modules = List.of(
                new PasswordPolicyRuleService(mock(PasswordPolicyRuleDao.class)),
                new PasswordSecurityPolicyService(mock(PasswordSecurityPolicyDao.class), mock(TenantService.class), mock(PasswordPolicyRuleService.class)));
        for (QueryAbility<?> module : modules) {
            QueryRequest platform = new QueryRequest(
                    List.of(new QueryCondition("tenantId", QueryOperator.NULL, List.of(), null)),
                    null, Map.of(), List.of(), null, null, Map.of(), null, List.of(), false, null);
            QueryRequest tenant = new QueryRequest(List.of(), null, Map.of(), List.of(), null, null,
                    Map.of("tenantId", "tenant-a"), null, List.of(), false, null);
            assertThat(module.queryCriteria(platform)).isNotNull();
            assertThat(module.queryDescriptor().externalCriteriaKeys()).containsExactly("tenantId");
            assertThat(module.queryCriteria(tenant)).isNotNull();
        }
    }
}
