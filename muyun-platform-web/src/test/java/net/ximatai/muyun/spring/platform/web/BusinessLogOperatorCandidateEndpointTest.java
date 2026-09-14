package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessLogOperatorCandidateEndpointTest {
    @Test
    void shouldUseTheExistingLogQueryActionForBothPlatformLogSelectors() throws Exception {
        assertCandidateAction(BusinessActivityLogWebController.class, "queryEvents");
        assertCandidateAction(RequestErrorLogWebController.class, "queryEvents");
    }

    private static void assertCandidateAction(Class<?> controller, String action) throws Exception {
        Method endpoint = controller.getMethod("operatorCandidates", BusinessLogOperatorCandidateRequest.class);
        assertThat(endpoint.getAnnotation(PostMapping.class).value()).containsExactly("/operator-candidates/query");
        assertThat(endpoint.getAnnotation(CustomActionEndpoint.class).value()).isEqualTo(action);
    }
}
