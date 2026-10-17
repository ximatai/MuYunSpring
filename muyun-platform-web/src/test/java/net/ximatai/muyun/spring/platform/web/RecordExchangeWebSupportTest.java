package net.ximatai.muyun.spring.platform.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.ximatai.muyun.spring.ability.*;
import net.ximatai.muyun.spring.ability.child.*;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.i18n.TextResolver;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.option.OptionSourceRegistry;
import net.ximatai.muyun.spring.common.platform.*;
import net.ximatai.muyun.spring.dynamic.metadata.*;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.content.BusinessContentQueryService;
import net.ximatai.muyun.spring.platform.exchange.*;
import net.ximatai.muyun.spring.platform.ui.ResolvedDetailRelationDescriptor;
import net.ximatai.muyun.spring.web.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.util.*;

class RecordExchangeWebSupportTest {
    @Test
    void matchingKeyFactsIncludeSingleFieldTenantUniqueDeclarations() {
        for (boolean unique : List.of(false, true)) {
            var fixture = new Fixture(true, false, unique);
            fixture.support.template(fixture.web, new RecordExchangeService.Export("DATA", "en", "UTC"));
            var captured = org.mockito.ArgumentCaptor.forClass(RecordExchangeAccess.class);
            verify(fixture.exchange).template(captured.capture(), any());
            assertThat(captured.getValue().definition().main().field("title").matchingKey()).isEqualTo(unique);
        }
    }

    @Test
    void queryExportUsesStableIdentitiesFromNonemptyPublishedStaticProjections() {
        var fixture = new Fixture(true);
        when(fixture.actions.resolveActionCode("test.exchange", "query", Set.of()))
                .thenReturn(
                        ActionExecutionContext.ofPlatformAction(
                                "test.exchange", PlatformAction.QUERY, Set.of(), Optional.empty()));
        when(fixture.web.query(any()))
                .thenReturn(
                        WebPageResponse.fromList(
                                List.of(Map.of("id", "static-id", "title", "Published title"))));
        fixture.support.export(
                fixture.web,
                new RecordExchangeWebSupport.ExportRequest(
                        null, null, new RecordExchangeService.Export("DATA", "en", "UTC")));
        verify(fixture.exchange).export(any(), eq(List.of("static-id")), any());
    }

    @Test
    void selectedIdentitiesNarrowTheCompiledQueryBeforeItsCountAndPageLimit() {
        var fixture = new Fixture(true);
        when(fixture.actions.resolveActionCode("test.exchange", "query", Set.of()))
                .thenReturn(
                        ActionExecutionContext.ofPlatformAction(
                                "test.exchange", PlatformAction.QUERY, Set.of(), Optional.empty()));
        when(fixture.web.query(any()))
                .thenAnswer(
                        invocation -> {
                            WebQueryRequest query = invocation.getArgument(0);
                            assertThat(query.conditions())
                                    .contains(new WebQueryCondition("id", "in", List.of("chosen")));
                            assertThat(query.quickSearch()).isEqualTo("equipment");
                            assertThat(query.criteria()).isNotNull();
                            return WebPageResponse.fromList(List.of(Map.of("id", "chosen")));
                        });
        var input =
                new WebQueryRequest(
                        null,
                        false,
                        List.of(),
                        WebQueryCriteria.group(
                                WebQueryGroupOperator.OR,
                                List.of(
                                        new WebQueryCondition(
                                                "title", "contains", List.of("equipment")))),
                        Map.of(),
                        List.of(),
                        null,
                        null,
                        Map.of(),
                        false,
                        "equipment",
                        List.of(),
                        null,
                        null,
                        null);
        fixture.support.export(
                fixture.web,
                new RecordExchangeWebSupport.ExportRequest(
                        List.of("chosen"),
                        input,
                        new RecordExchangeService.Export("READING", "de", "UTC")));
        verify(fixture.exchange).export(any(), eq(List.of("chosen")), any());
        verify(fixture.web, times(1)).query(any());
    }

    @Test
    void staticChildReadsUseTheirProducerViewIdentityAndNeverInheritTheParentExportGrant() {
        var fixture = new Fixture(true, true);
        var root = mock(RootAbility.class);
        var child = mock(OwnedAbility.class);
        when(root.getModuleAlias()).thenReturn("test.exchange");
        when(child.getModuleAlias()).thenReturn("test.exchange_line");
        when(fixture.web.service()).thenReturn(root);
        var relation =
                new ChildRelation<Sample, Sample>(
                        "lines", child, (row, parent) -> {}, "parentId", row -> List.of());
        when(root.childRelations()).thenReturn(List.of(relation));
        var record = new Sample();
        record.setId("child-id");
        record.setTitle("Secret child title");
        when(child.selectChildRows(
                        any(), any(net.ximatai.muyun.database.core.orm.PageRequest.class)))
                .thenReturn(List.of(record));
        var view =
                ActionExecutionContext.ofPlatformAction(
                        "test.exchange",
                        PlatformAction.VIEW,
                        Set.of("parent-id"),
                        Optional.empty());
        when(fixture.actions.resolveActionCode("test.exchange", "view", Set.of("parent-id")))
                .thenReturn(view);
        var producer =
                ActionExecutionContext.ofPlatformAction(
                        "test.exchange_line",
                        PlatformAction.VIEW,
                        Set.of("child-id"),
                        Optional.empty());
        when(fixture.actions.resolveFieldReadContext("test.exchange_line", Set.of("child-id")))
                .thenReturn(producer);
        var newProducer =
                ActionExecutionContext.ofPlatformAction(
                        "test.exchange_line", PlatformAction.VIEW, Set.of(), Optional.empty());
        when(fixture.actions.resolveFieldReadContext("test.exchange_line", Set.of()))
                .thenReturn(newProducer);
        when(child.fieldReadPolicy(any()))
                .thenAnswer(
                        invocation -> {
                            ActionExecutionContext context = invocation.getArgument(0);
                            assertThat(context.moduleAlias()).isEqualTo("test.exchange_line");
                            assertThat(context.actionCode()).isEqualTo("view");
                            assertThat(context.authorizationResult()).isNull();
                            return FieldReadPolicy.readableFields(Set.of());
                        });
        when(fixture.actions.resolveActionCode("test.exchange", "update", Set.of("parent-id")))
                .thenReturn(
                        ActionExecutionContext.ofPlatformAction(
                                "test.exchange",
                                PlatformAction.UPDATE,
                                Set.of("parent-id"),
                                Optional.empty()));
        when(fixture.exchange.export(any(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            RecordExchangeAccess access = invocation.getArgument(0);
                            assertThat(
                                            access.children("line", "parent-id", 100)
                                                    .getFirst()
                                                    .values())
                                    .doesNotContainKey("title");
                            assertThatThrownBy(
                                            () ->
                                                    access.requireNewRowRead(
                                                            "line", "parent-id", Set.of("title")))
                                    .isInstanceOf(PlatformAccessDeniedException.class);
                            return new byte[] {1};
                        });
        var outer =
                ActionExecutionContext.ofPlatformAction(
                        "test.exchange", PlatformAction.EXPORT, Set.of(), Optional.empty());
        try (var ignored = ActionExecutionContextHolder.use(outer)) {
            fixture.support.export(
                    fixture.web,
                    new RecordExchangeWebSupport.ExportRequest(
                            List.of("parent-id"),
                            null,
                            new RecordExchangeService.Export("DATA", "en", "UTC")));
            assertThat(ActionExecutionContextHolder.current()).contains(outer);
        }
        verify(child).fieldReadPolicy(producer);
        verify(child).fieldReadPolicy(newProducer);
        verify(root, never()).fieldReadPolicy(any());
    }

    interface RootAbility extends ChildrenAbility<Sample>, FieldReadAbility {}

    interface OwnedAbility extends ChildAbility<Sample>, FieldReadAbility {}

    public static class Sample extends StandardEntity {
        private String title;

        public String getTitle() {
            return title;
        }

        public void setTitle(String title) {
            this.title = title;
        }
    }

    @Test
    void queryExportAuthorizesTheListActionWithoutInventingARecordIdentity() {
        var fixture = new Fixture(true);
        var context = mock(ActionExecutionContext.class);
        when(fixture.actions.resolveActionCode("test.exchange", "query", Set.of()))
                .thenReturn(context);
        when(fixture.web.query(any())).thenReturn(WebPageResponse.fromList(List.of()));
        when(fixture.exchange.export(any(), eq(List.of()), any())).thenReturn(new byte[] {1});
        var result =
                fixture.support.export(
                        fixture.web,
                        new RecordExchangeWebSupport.ExportRequest(
                                List.of(),
                                null,
                                new RecordExchangeService.Export("DATA", "en", "UTC")));
        assertThat(result).containsExactly(1);
        verify(fixture.policies).requireAuthorized(context);
        verify(fixture.policies, never()).requireRecordAction(any());
        verify(fixture.web).query(any());
    }

    @Test
    void undeclaredExchangeFailsBeforeAnyFileOrBusinessExecution() {
        var fixture = new Fixture(false);
        assertThatThrownBy(
                        () ->
                                fixture.support.template(
                                        fixture.web,
                                        new RecordExchangeService.Export("DATA", "en", "UTC")))
                .isInstanceOf(PlatformAccessDeniedException.class);
        verifyNoInteractions(fixture.exchange, fixture.policies);
    }

    private static class Fixture {
        final RecordExchangeService exchange = mock(RecordExchangeService.class);
        final ActionEndpointContextResolver actions = mock(ActionEndpointContextResolver.class);
        final ActionExecutionPolicyService policies = mock(ActionExecutionPolicyService.class);

        @SuppressWarnings("rawtypes")
        final CrudWeb web = mock(CrudWeb.class);

        final RecordExchangeWebSupport support;

        Fixture(boolean enabled) {
            this(enabled, false);
        }

        Fixture(boolean enabled, boolean children) { this(enabled, children, false); }

        @SuppressWarnings("unchecked")
        Fixture(boolean enabled, boolean children, boolean tenantUnique) {
            when(web.webScopeName()).thenReturn("test.exchange");
            var module = mock(StaticModuleDefinition.class);
            when(module.capabilities())
                    .thenReturn(enabled ? Set.of(EntityCapability.EXCHANGE) : Set.of());
            var entities = new ArrayList<EntityDefinition>();
            entities.add(
                    new EntityDefinition(
                            "main", "public", "test_exchange", "Main",
                            List.of(FieldDefinition.string("title", "Title")), Set.of(EntityCapability.CRUD), List.of(),
                            tenantUnique ? List.of(new net.ximatai.muyun.spring.common.model.constraint.TenantUniqueConstraintDefinition(List.of("title"), "unique title")) : List.of()));
            if (children)
                entities.add(
                        new EntityDefinition(
                                "line",
                                "test_exchange_line",
                                "Line",
                                List.of(FieldDefinition.string("title", "Title"))));
            when(module.entities()).thenReturn(entities);
            when(module.modelClass()).thenReturn((Class) Sample.class);
            var statics = mock(StaticModuleDefinitionCatalog.class);
            when(statics.find("test.exchange")).thenReturn(Optional.of(module));
            var ui = mock(ResolvedModuleUiDescriptor.class);
            when(ui.moduleAlias()).thenReturn("test.exchange");
            if (children)
                when(ui.detailRelations())
                        .thenReturn(
                                List.of(
                                        new ResolvedDetailRelationDescriptor(
                                                "lines",
                                                "Lines",
                                                false,
                                                "test.exchange",
                                                "main",
                                                "test.exchange",
                                                "line",
                                                "parentId",
                                                null,
                                                true)));
            var fields = new ArrayList<ResolvedModuleReadField>();
            fields.add(new ResolvedModuleReadField("main", null, "title", false));
            if (children) fields.add(new ResolvedModuleReadField("line", "lines", "title", false));
            var plan =
                    new ModuleExecutionPlan(
                            "test.exchange",
                            "one",
                            ui,
                            new ResolvedModuleReadModel("test.exchange", "main", fields),
                            List.of());
            var plans = mock(ModuleExecutionPlanCatalog.class);
            when(plans.find("test.exchange")).thenReturn(Optional.of(plan));
            var beans = new DefaultListableBeanFactory();
            support =
                    new RecordExchangeWebSupport(
                            exchange,
                            statics,
                            plans,
                            mock(DynamicRecordService.class),
                            actions,
                            policies,
                            new ObjectMapper(),
                            mock(RecordExchangeReferenceSupport.class),
                            mock(BusinessContentQueryService.class),
                            beans.getBeanProvider(OptionSourceRegistry.class),
                            beans.getBeanProvider(TextResolver.class),
                            beans.getBeanProvider(
                                    org.springframework.core.ResolvableType.forClass(
                                            CrudWeb.class)));
        }
    }
}
