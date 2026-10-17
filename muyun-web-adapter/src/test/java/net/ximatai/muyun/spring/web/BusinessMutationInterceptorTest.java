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

    @Test
    void registeredContentWritesHonorExplicitMutationAndStillRequireTheActionContext() throws Exception {
        var catalog = org.mockito.Mockito.mock(net.ximatai.muyun.spring.web.endpoint.RegisteredWebEndpointCatalog.class);
        var endpoint = org.mockito.Mockito.mock(net.ximatai.muyun.spring.web.endpoint.RegisteredWebEndpoint.class);
        var definition = org.mockito.Mockito.mock(net.ximatai.muyun.spring.web.endpoint.ResolvedWebEndpoint.class);
        org.mockito.Mockito.when(endpoint.definition()).thenReturn(definition);
        org.mockito.Mockito.when(definition.action()).thenReturn(PlatformAction.CONTENT_CONFIRM);
        org.mockito.Mockito.when(definition.source()).thenReturn(
                net.ximatai.muyun.spring.web.endpoint.ResolvedWebEndpoint.Source.STATIC_EXPLICIT);
        org.mockito.Mockito.when(catalog.find(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Optional.of(endpoint));
        var interceptor = new BusinessMutationInterceptor(catalog);
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        var handler = new HandlerMethod(new Endpoints(), Endpoints.class.getMethod("module"));
        interceptor.preHandle(request, response, handler);
        assertThat(MutationContextHolder.current()).isEmpty();
        try (var action = ActionExecutionContextHolder.use(ActionExecutionContext.ofPlatformAction(
                "test.business", PlatformAction.CONTENT_CONFIRM, Set.of("record-1"), Optional.empty()))) {
            interceptor.preHandle(request, response, handler);
            assertThat(MutationContextHolder.current()).isPresent();
            interceptor.afterCompletion(request, response, handler, null);
            org.mockito.Mockito.when(definition.source()).thenReturn(
                    net.ximatai.muyun.spring.web.endpoint.ResolvedWebEndpoint.Source.STATIC_ABILITY);
            interceptor.preHandle(request, response, handler);
            assertThat(MutationContextHolder.current()).isEmpty();
            org.mockito.Mockito.when(definition.source()).thenReturn(
                    net.ximatai.muyun.spring.web.endpoint.ResolvedWebEndpoint.Source.STATIC_EXPLICIT);
            handler = new HandlerMethod(new Endpoints(), Endpoints.class.getMethod("prepare"));
            interceptor.preHandle(request, response, handler);
            assertThat(MutationContextHolder.current()).isEmpty();
        }
    }

    static class Endpoints {
        public void prepare() {}
        @BusinessMutation public void module() {}
        @BusinessMutation(actionContextRequired = false) public void indirect() {}
    }
}
