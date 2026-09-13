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
                "platform-admin", "admin", null));
        assertThat(response.selectedRecords()).containsExactly(new BusinessLogOperatorCandidateResponse(
                "former-user", "历史职员 (former)", null));
    }

    @Test
    void shouldFallBackToTheStoredAccountWhenHistoricalIamIdentityIsGone() {
        BusinessLogOperatorCandidatePage page = new BusinessLogOperatorCandidatePage(List.of(
                new BusinessLogOperatorCandidate("tenant-a", "removed-user", "retired.account")), 1, 1, 20);

        var response = BusinessLogOperatorCandidateResponses.from(page, null, keys -> Map.of());

        assertThat(response.records()).containsExactly(new BusinessLogOperatorCandidateResponse(
                "removed-user", "retired.account", null));
    }
}
