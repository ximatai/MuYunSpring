package net.ximatai.muyun.spring.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.platform.module.PlatformModuleAction;
import net.ximatai.muyun.spring.platform.module.PlatformModuleActionService;
import net.ximatai.muyun.spring.web.RecordActionWebRequest;
import net.ximatai.muyun.spring.web.ScopedWeb;
import net.ximatai.muyun.spring.web.SortWebRequest;
import net.ximatai.muyun.spring.web.endpoint.RegisteredWebEndpoint;
import net.ximatai.muyun.spring.web.endpoint.ResolvedWebEndpoint;
import net.ximatai.muyun.spring.web.endpoint.StaticWebOperationTarget;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;

import java.lang.reflect.Method;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void shouldRejectGeneratedRecordOperationsOutsideRequiredCrudNavigatorScope() throws Exception {
        PlatformModuleActionService service = mock(PlatformModuleActionService.class);
        PlatformModuleAction foreignAction = new PlatformModuleAction();
        foreignAction.setId("action-b");
        foreignAction.setModuleAlias("platform.module-b");
        when(service.select("action-b")).thenReturn(foreignAction);
        CrudWeb<PlatformModuleAction, PlatformModuleActionService> anchor = scopedActionAnchor(service);
        StaticAbilityOperationRuntime runtime = new StaticAbilityOperationRuntime(mock(ObjectProvider.class));
        RegisteredWebEndpoint enable = actionEndpoint(PlatformAction.ENABLE, anchor, service);
        RegisteredWebEndpoint disable = actionEndpoint(PlatformAction.DISABLE, anchor, service);
        RegisteredWebEndpoint sort = actionEndpoint(PlatformAction.SORT, anchor, service);
        MockHttpServletRequest enableRequest = scopedRequest("action-b");
        MockHttpServletRequest disableRequest = scopedRequest("action-b");
        MockHttpServletRequest sortRequest = scopedRequest("action-b");

        assertThatThrownBy(() -> executeWithRequest(enableRequest, () -> runtime.execute(enable, enableRequest,
                new RecordActionWebRequest(1))))
                .hasMessageContaining("Record does not belong to the current page scope: moduleAlias");
        assertThatThrownBy(() -> executeWithRequest(disableRequest, () -> runtime.execute(disable, disableRequest,
                new RecordActionWebRequest(1))))
                .hasMessageContaining("Record does not belong to the current page scope: moduleAlias");
        assertThatThrownBy(() -> executeWithRequest(sortRequest, () -> runtime.execute(sort, sortRequest,
                new SortWebRequest("action-a", null))))
                .hasMessageContaining("Record does not belong to the current page scope: moduleAlias");

        verify(service, never()).enable("action-b", 1);
        verify(service, never()).disable("action-b", 1);
        verify(service, never()).moveAfter("action-b", "action-a");
    }

    @Test
    void shouldNotRequirePageContextForGeneratedOperationsWithOnlyOptionalCrudNavigatorScope() throws Exception {
        PlatformModuleActionService service = mock(PlatformModuleActionService.class);
        PlatformModuleAction action = new PlatformModuleAction();
        action.setId("action-b");
        action.setModuleAlias("platform.module-b");
        when(service.select("action-b")).thenReturn(action);
        when(service.enable("action-b", 1)).thenReturn(1);
        StaticAbilityOperationRuntime runtime = new StaticAbilityOperationRuntime(mock(ObjectProvider.class));
        RegisteredWebEndpoint endpoint = actionEndpoint(PlatformAction.ENABLE,
                actionAnchor(service, NavigatorListQueryMode.OPTIONAL_FILTER), service);
        MockHttpServletRequest request = request("action-b");

        assertThat(runtime.execute(endpoint, request, new RecordActionWebRequest(1))).isEqualTo(1);

        verify(service).enable("action-b", 1);
    }

    @Test
    void shouldPreserveGeneratedOperationsForLegacyCrudControllersWithoutPageContextContract() throws Exception {
        PlatformModuleActionService service = mock(PlatformModuleActionService.class);
        PlatformModuleAction action = new PlatformModuleAction();
        action.setId("action-b");
        action.setModuleAlias("platform.module-b");
        when(service.select("action-b")).thenReturn(action);
        when(service.enable("action-b", 1)).thenReturn(1);
        StaticAbilityOperationRuntime runtime = new StaticAbilityOperationRuntime(mock(ObjectProvider.class));
        RegisteredWebEndpoint endpoint = actionEndpoint(PlatformAction.ENABLE,
                actionAnchor(service, NavigatorListQueryMode.REQUIRED_SCOPE, false), service);
        MockHttpServletRequest request = request("action-b");

        assertThat(runtime.execute(endpoint, request, new RecordActionWebRequest(1))).isEqualTo(1);

        verify(service).enable("action-b", 1);
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

    private RegisteredWebEndpoint actionEndpoint(PlatformAction action,
                                                 CrudWeb<PlatformModuleAction, PlatformModuleActionService> anchor,
                                                 PlatformModuleActionService service) throws Exception {
        String operation = action.code();
        ResolvedWebEndpoint definition = new ResolvedWebEndpoint("platform.module_action." + operation,
                "platform.module_action", operation, operation, action, RequestMethod.POST,
                "/platform.module_action/" + operation + "/{id}", ResolvedWebEndpoint.Source.STATIC_ABILITY);
        Method marker = StaticAbilityOperationRuntimeTest.class.getDeclaredMethod("marker");
        return new RegisteredWebEndpoint(definition,
                RequestMappingInfo.paths(definition.path()).methods(RequestMethod.POST).build(), this, marker,
                new StaticWebOperationTarget("platform.module_action", anchor, service));
    }

    private CrudWeb<PlatformModuleAction, PlatformModuleActionService> scopedActionAnchor(
            PlatformModuleActionService service) {
        return actionAnchor(service, NavigatorListQueryMode.REQUIRED_SCOPE, true);
    }

    private CrudWeb<PlatformModuleAction, PlatformModuleActionService> actionAnchor(
            PlatformModuleActionService service, NavigatorListQueryMode navigatorScopeMode) {
        return actionAnchor(service, navigatorScopeMode, false);
    }

    private CrudWeb<PlatformModuleAction, PlatformModuleActionService> actionAnchor(
            PlatformModuleActionService service,
            NavigatorListQueryMode navigatorScopeMode,
            boolean requiresModuleExecutionPlan) {
        return new CrudWeb<>() {
            @Override
            public PlatformModuleActionService service() {
                return service;
            }

            @Override
            public <T> T webScope(java.util.function.Supplier<T> action) {
                return action.get();
            }

            @Override
            public boolean requiresModuleExecutionPlan() {
                return requiresModuleExecutionPlan;
            }

            @Override
            public java.util.List<PageContextBindingDefinition> recordScopeBindings() {
                return java.util.List.of(PageContextBindingDefinition.navigatorList("module", "moduleAlias",
                        navigatorScopeMode));
            }
        };
    }

    private MockHttpServletRequest request(String id) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/demo.sort/sort/" + id);
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("id", id));
        return request;
    }

    private MockHttpServletRequest scopedRequest(String id) {
        MockHttpServletRequest request = request(id);
        request.addHeader(PageContextScopePolicy.CONTEXT_HEADER, "{\"module\":\"platform.module-a\"}");
        return request;
    }

    private Object executeWithRequest(MockHttpServletRequest request, java.util.function.Supplier<Object> action) {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            return action.get();
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private void marker() {
    }
}
