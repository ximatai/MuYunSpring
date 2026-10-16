package net.ximatai.muyun.spring.web;

import net.ximatai.muyun.spring.ability.action.MutationContextHolder;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContextHolder;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import java.util.Optional;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThat;

class BusinessMutationInterceptorTest {
    @Test
    void moduleMutationStillRequiresResolvedActionContext() throws Exception {
        var interceptor = new BusinessMutationInterceptor();
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        var handler = new HandlerMethod(new Endpoints(), Endpoints.class.getMethod("module"));
        interceptor.preHandle(request, response, handler);
        assertThat(MutationContextHolder.current()).isEmpty();
        try (var action = ActionExecutionContextHolder.use(ActionExecutionContext.ofPlatformAction(
                "test.business", PlatformAction.UPDATE, Set.of("record-1"), Optional.empty()))) {
            interceptor.preHandle(request, response, handler);
            assertThat(MutationContextHolder.current()).isPresent();
            interceptor.afterCompletion(request, response, handler, null);
            assertThat(MutationContextHolder.current()).isEmpty();
        }
    }

    @Test
    void domainAuthorizedMutationCollectsFactsUntilResponseAndClearsOnFailure() throws Exception {
        var interceptor = new BusinessMutationInterceptor();
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        var handler = new HandlerMethod(new Endpoints(), Endpoints.class.getMethod("indirect"));
        interceptor.preHandle(request, response, handler);
        assertThat(MutationContextHolder.current()).isPresent();
        assertThat(ActionExecutionContextHolder.current()).isEmpty();
        interceptor.afterCompletion(request, response, handler, new RuntimeException("domain rejected"));
        assertThat(MutationContextHolder.current()).isEmpty();
        interceptor.preHandle(request, response, handler);
        interceptor.afterConcurrentHandlingStarted(request, response, handler);
        assertThat(MutationContextHolder.current()).isEmpty();
    }

    static class Endpoints {
        @BusinessMutation public void module() {}
        @BusinessMutation(actionContextRequired = false) public void indirect() {}
    }
}
