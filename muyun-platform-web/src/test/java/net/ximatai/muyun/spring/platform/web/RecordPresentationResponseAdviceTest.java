package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.web.BusinessMutation;
import net.ximatai.muyun.spring.web.endpoint.RegisteredWebEndpointCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RecordPresentationResponseAdviceTest {
    private final RecordTextPresentationService texts = mock(RecordTextPresentationService.class);
    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    private final Object body = Map.of("id", "owner", "values", Map.of("title", "原文"));

    private Object deliver(String methodName) throws Exception {
        if (!beans.containsSingleton("texts")) beans.registerSingleton("texts", texts);
        var advice = new RecordPresentationResponseAdvice(beans.getBeanProvider(RegisteredWebEndpointCatalog.class),
                beans.getBeanProvider(RecordTextPresentationService.class));
        var method = Routes.class.getMethod(methodName);
        var request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE, new HandlerMethod(new Routes(), method));
        return advice.beforeBodyWrite(body, new MethodParameter(method, -1), MediaType.APPLICATION_JSON,
                MappingJackson2HttpMessageConverter.class, new ServletServerHttpRequest(request),
                new ServletServerHttpResponse(new MockHttpServletResponse()));
    }

    @Test
    void ordinaryMvcReadDeclarationsConsumeOnlyPreviouslyCapturedSources() throws Exception {
        var projected = Map.of("projected", true);
        when(texts.projectCaptured(body)).thenReturn(projected);
        assertThat(deliver("query")).isSameAs(projected);
        assertThat(deliver("view")).isSameAs(projected);
        verify(texts, times(2)).projectCaptured(body);
    }

    @Test
    void mutationsAndUndeclaredRoutesKeepTheirRawResponse() throws Exception {
        assertThat(deliver("update")).isSameAs(body);
        assertThat(deliver("create")).isSameAs(body);
        assertThat(deliver("other")).isSameAs(body);
        verifyNoInteractions(texts);
    }

    static class Routes {
        @ActionEndpoint(PlatformAction.QUERY) public Object query() { return null; }
        @ActionEndpoint(PlatformAction.VIEW) public Object view() { return null; }
        @BusinessMutation @ActionEndpoint(PlatformAction.UPDATE) public Object update() { return null; }
        @ActionEndpoint(PlatformAction.CREATE) public Object create() { return null; }
        public Object other() { return null; }
    }
}
