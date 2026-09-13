package net.ximatai.muyun.spring.iam.web;

import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.iam.logging.LoginAuditGovernanceService;
import net.ximatai.muyun.spring.platform.web.BusinessLogOperatorCandidateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

import static org.assertj.core.api.Assertions.assertThat;

class LoginAuditLogOperatorCandidateEndpointTest {
    @Test
    void shouldUseTheLoginQueryActionForTheOperatorSelector() throws Exception {
        var endpoint = LoginAuditLogWebController.class.getMethod("operatorCandidates",
                BusinessLogOperatorCandidateRequest.class);

        assertThat(endpoint.getAnnotation(PostMapping.class).value()).containsExactly("/operator-candidates/query");
        assertThat(endpoint.getAnnotation(CustomActionEndpoint.class).value())
                .isEqualTo(LoginAuditGovernanceService.QUERY_ACTION_CODE);
    }
}
