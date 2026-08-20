package net.ximatai.muyun.spring.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.web.ScopedWeb;
import net.ximatai.muyun.spring.web.SortWebRequest;
import net.ximatai.muyun.spring.web.endpoint.RegisteredWebEndpoint;
import net.ximatai.muyun.spring.web.endpoint.ResolvedWebEndpoint;
import net.ximatai.muyun.spring.web.endpoint.StaticWebOperationTarget;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class StaticAbilityOperationRuntimeTest {
    @Test
    void shouldDispatchSortBeforeAndAfterVectorsThroughRegisteredCapabilityAdapter() throws Exception {
        SortAbility<?> ability = mock(SortAbility.class);
        StaticAbilityOperationRuntime runtime = new StaticAbilityOperationRuntime(mock(ObjectProvider.class));

        assertThat(runtime.execute(sortEndpoint(ability), request("moving"), new SortWebRequest("previous", null)))
                .isEqualTo(1);
        assertThat(runtime.execute(sortEndpoint(ability), request("moving"), new SortWebRequest(null, "next")))
                .isEqualTo(1);

        verify(ability).moveAfter("moving", "previous");
        verify(ability).moveBefore("moving", "next");
    }

    @Test
    void shouldPreserveStaticSortPartitionRejectionThroughCapabilityAdapter() throws Exception {
        SortAbility<?> ability = mock(SortAbility.class);
        doThrow(new PlatformException("Sort can only move records within the same partition: organizationId"))
                .when(ability).moveAfter("moving", "other-partition");
        StaticAbilityOperationRuntime runtime = new StaticAbilityOperationRuntime(mock(ObjectProvider.class));

        assertThatThrownBy(() -> runtime.execute(sortEndpoint(ability), request("moving"),
                new SortWebRequest("other-partition", null)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("same partition");
    }

    private RegisteredWebEndpoint sortEndpoint(SortAbility<?> ability) throws Exception {
        ScopedWeb<Object> anchor = new ScopedWeb<>() {
            @Override
            public Object service() {
                return ability;
            }

            @Override
            public <T> T webScope(java.util.function.Supplier<T> action) {
                return action.get();
            }
        };
        ResolvedWebEndpoint definition = new ResolvedWebEndpoint("demo.sort.sort", "demo.sort", "sort", "sort",
                PlatformAction.SORT, RequestMethod.POST, "/demo.sort/sort/{id}",
                ResolvedWebEndpoint.Source.STATIC_ABILITY);
        Method marker = StaticAbilityOperationRuntimeTest.class.getDeclaredMethod("marker");
        return new RegisteredWebEndpoint(definition,
                RequestMappingInfo.paths(definition.path()).methods(RequestMethod.POST).build(), this, marker,
                new StaticWebOperationTarget("demo.sort", anchor, ability));
    }

    private MockHttpServletRequest request(String id) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/demo.sort/sort/" + id);
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("id", id));
        return request;
    }

    private void marker() {
    }
}
