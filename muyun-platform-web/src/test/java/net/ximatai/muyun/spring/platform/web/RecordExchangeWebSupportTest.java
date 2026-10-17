package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.platform.*;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.i18n.TextResolver;
import net.ximatai.muyun.spring.common.option.OptionSourceRegistry;
import net.ximatai.muyun.spring.dynamic.metadata.*;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.content.BusinessContentQueryService;
import net.ximatai.muyun.spring.platform.exchange.*;
import net.ximatai.muyun.spring.web.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecordExchangeWebSupportTest {
    @Test void queryExportAuthorizesTheListActionWithoutInventingARecordIdentity() {
        var fixture = new Fixture(true);
        var context = mock(ActionExecutionContext.class);
        when(fixture.actions.resolveActionCode("test.exchange", "query", Set.of())).thenReturn(context);
        when(fixture.web.query(any())).thenReturn(WebPageResponse.fromList(List.of()));
        when(fixture.exchange.export(any(),eq(List.of()),any())).thenReturn(new byte[]{1});
        var result = fixture.support.export(fixture.web,new RecordExchangeWebSupport.ExportRequest(List.of(),null,
                new RecordExchangeService.Export("DATA","en","UTC")));
        assertThat(result).containsExactly(1);
        verify(fixture.policies).requireAuthorized(context);
        verify(fixture.policies,never()).requireRecordAction(any());
        verify(fixture.web).query(any());
    }

    @Test void undeclaredExchangeFailsBeforeAnyFileOrBusinessExecution() {
        var fixture = new Fixture(false);
        assertThatThrownBy(() -> fixture.support.template(fixture.web,new RecordExchangeService.Export("DATA","en","UTC")))
                .isInstanceOf(PlatformAccessDeniedException.class);
        verifyNoInteractions(fixture.exchange,fixture.policies);
    }

    private static class Fixture {
        final RecordExchangeService exchange = mock(RecordExchangeService.class);
        final ActionEndpointContextResolver actions = mock(ActionEndpointContextResolver.class);
        final ActionExecutionPolicyService policies = mock(ActionExecutionPolicyService.class);
        @SuppressWarnings("rawtypes") final CrudWeb web = mock(CrudWeb.class);
        final RecordExchangeWebSupport support;
        @SuppressWarnings("unchecked") Fixture(boolean enabled) {
            when(web.webScopeName()).thenReturn("test.exchange");
            var module = mock(StaticModuleDefinition.class);
            when(module.capabilities()).thenReturn(enabled ? Set.of(EntityCapability.EXCHANGE) : Set.of());
            when(module.entities()).thenReturn(List.of(new EntityDefinition("main","test_exchange","Main",List.of(FieldDefinition.string("title","Title")))));
            when(module.modelClass()).thenReturn((Class)StandardEntity.class);
            var statics = mock(StaticModuleDefinitionCatalog.class);
            when(statics.find("test.exchange")).thenReturn(Optional.of(module));
            var ui = mock(ResolvedModuleUiDescriptor.class);
            when(ui.moduleAlias()).thenReturn("test.exchange");
            var plan = new ModuleExecutionPlan("test.exchange","one",ui,new ResolvedModuleReadModel("test.exchange","main",
                    List.of(new ResolvedModuleReadField("main",null,"title",false))),List.of());
            var plans = mock(ModuleExecutionPlanCatalog.class);
            when(plans.find("test.exchange")).thenReturn(Optional.of(plan));
            var beans = new DefaultListableBeanFactory();
            support = new RecordExchangeWebSupport(exchange,statics,plans,mock(DynamicRecordService.class),actions,policies,
                    new ObjectMapper(),mock(RecordExchangeReferenceSupport.class),mock(BusinessContentQueryService.class),
                    beans.getBeanProvider(OptionSourceRegistry.class),beans.getBeanProvider(TextResolver.class),
                    beans.getBeanProvider(org.springframework.core.ResolvableType.forClass(CrudWeb.class)));
        }
    }
}
