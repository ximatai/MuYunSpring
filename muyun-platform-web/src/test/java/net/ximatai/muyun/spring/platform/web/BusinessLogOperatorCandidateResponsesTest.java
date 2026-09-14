package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.logging.BusinessLogOperatorCandidate;
import net.ximatai.muyun.spring.ability.logging.BusinessLogOperatorCandidatePage;
import net.ximatai.muyun.spring.ability.logging.BusinessLogOperatorIdentity;
import net.ximatai.muyun.spring.ability.logging.BusinessLogOperatorIdentityKey;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessLogOperatorCandidateResponsesTest {
    @Test
    void shouldKeepPlatformAccountAndAuthorizedHistoricalSelectionWithoutCurrentTenantScope() {
        BusinessLogOperatorCandidatePage page = new BusinessLogOperatorCandidatePage(List.of(
                new BusinessLogOperatorCandidate(null, "platform-admin")), 1, 1, 20);
        BusinessLogOperatorCandidatePage selected = new BusinessLogOperatorCandidatePage(List.of(
                new BusinessLogOperatorCandidate("tenant-a", "former-user", "former-account")), 1, 1, 1);

        var response = BusinessLogOperatorCandidateResponses.from(page, selected, keys -> Map.of(
                new BusinessLogOperatorIdentityKey(null, "platform-admin", null),
                new BusinessLogOperatorIdentity(null, "admin", null, null, null, null),
                new BusinessLogOperatorIdentityKey("tenant-a", "former-user", null),
                new BusinessLogOperatorIdentity("历史职员", "former", null, null, null, null)
        ));

        assertThat(response.records()).containsExactly(new BusinessLogOperatorCandidateResponse(
                "platform-admin", "admin", null, "admin", null, null, null, null, null));
        assertThat(response.selectedRecords()).containsExactly(new BusinessLogOperatorCandidateResponse(
                "former-user", "历史职员 (former)", null, "former-account", "历史职员", null, null, null, null));
    }

    @Test
    void shouldFallBackToTheStoredAccountWhenHistoricalIamIdentityIsGone() {
        BusinessLogOperatorCandidatePage page = new BusinessLogOperatorCandidatePage(List.of(
                new BusinessLogOperatorCandidate("tenant-a", "removed-user", "retired.account")), 1, 1, 20);

        var response = BusinessLogOperatorCandidateResponses.from(page, null, keys -> Map.of());

        assertThat(response.records()).containsExactly(new BusinessLogOperatorCandidateResponse(
                "removed-user", "retired.account", null, "retired.account", null, null, null, null, null));
    }

    @Test
    void shouldExposeStructuredEmployeeAndEventTimeOrganizationDetails() {
        BusinessLogOperatorCandidatePage page = new BusinessLogOperatorCandidatePage(List.of(
                new BusinessLogOperatorCandidate("tenant-a", "user-a", "demo_admin", "organization-a",
                        "department-a")), 1, 1, 20);

        var response = BusinessLogOperatorCandidateResponses.from(page, null, keys -> Map.of(
                new BusinessLogOperatorIdentityKey("tenant-a", "user-a", "organization-a"),
                new BusinessLogOperatorIdentity("演示租户管理员", "demo_admin", "organization-a", "综合管理部",
                        "department-a", "研发一部")
        ));

        assertThat(response.records()).containsExactly(new BusinessLogOperatorCandidateResponse("user-a",
                "演示租户管理员 (demo_admin)", "综合管理部 / 研发一部", "demo_admin", "演示租户管理员",
                "organization-a", "综合管理部", "department-a", "研发一部"));
    }
}
