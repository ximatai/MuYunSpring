package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.CriteriaClause;
import net.ximatai.muyun.database.core.orm.CriteriaGroup;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.ability.query.ExternalQueryValueSource;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryField;
import net.ximatai.muyun.spring.ability.query.QueryOperator;
import net.ximatai.muyun.spring.ability.query.QueryRequest;
import net.ximatai.muyun.spring.ability.query.QuerySchema;
import net.ximatai.muyun.spring.ability.query.QueryValueType;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.web.WebListQuerySummaryItem;
import net.ximatai.muyun.spring.web.WebPageResponse;
import net.ximatai.muyun.spring.web.WebQueryCondition;
import net.ximatai.muyun.spring.web.WebQueryRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StandardModuleWebRuntimeTest {
    @Test
    void shouldUseNormalizedRequestAndSameScopeForProjectedCountAndAggregate() {
        String module = "sales.contract";
        WebQueryRequest request = new WebQueryRequest(null, false,
                List.of(new WebQueryCondition("code", "EQ", List.of("C-001"))), null, Map.of(), List.of(),
                null, null, Map.of("tenantId", "tenant-a", "owner", "user-1"), false, null, List.of(), null);
        var aggregate = net.ximatai.muyun.database.core.orm.AggregateQuery.of(List.of(
                net.ximatai.muyun.database.core.orm.AggregateSelection.of("total",
                        net.ximatai.muyun.database.core.orm.AggregateOperation.SUM, "amount")));
        ListQuerySummaryContributor contributor = new ListQuerySummaryContributor() {
            public String moduleAlias() { return module; }
            public String contributorKey() { return "contracts.count"; }
            public WebListQuerySummaryItem summarize(ListQuerySummaryContext context) {
                assertThat(context.request()).isSameAs(request);
                assertThat(context.count(Criteria.of().eq("extra", true))).isEqualTo(1L);
                return new WebListQuerySummaryItem(context.summaryKey(),
                        context.aggregate(aggregate).getFirst().get("total"));
            }
        };
        StaticRecordReadProjectionService projections = mock(StaticRecordReadProjectionService.class);
        CrudAbility<?> service = mock(CrudAbility.class);
        ActionExecutionPolicy standard = ActionExecutionPolicy.standard(
                net.ximatai.muyun.spring.common.platform.PlatformAction.QUERY);
        ActionExecutionPolicy policy = new ActionExecutionPolicy(standard.actionCode(), standard.level(),
                standard.accessMode(), standard.actionAuth(), standard.dataAuth(), standard.defaultGrantPolicy(), "view");
        when(projections.queryDefaultList(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(WebPageResponse.fromList(List.of(Map.of("id", "one")))));
        when(projections.aggregateDefaultList(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(Optional.of(List.of(Map.of("total", 42))));
        StandardModuleWebRuntime runtime = new StandardModuleWebRuntime(dynamicCatalog(module, plan(module)),
                projections, new ListQuerySummaryRuntime(List.of(contributor)));

        assertThat(runtime.listQuerySummaries(module, request, 8, service,
                Criteria.of().eq("tenantId", "tenant-a"), policy))
                .containsExactly(new WebListQuerySummaryItem("count", 42));

        ArgumentCaptor<QueryRequest> countRequest = ArgumentCaptor.forClass(QueryRequest.class);
        ArgumentCaptor<Criteria> countCriteria = ArgumentCaptor.forClass(Criteria.class);
        verify(projections).queryDefaultList(eq(module), countRequest.capture(), countCriteria.capture(), any(),
                eq(service), eq(policy), eq(RecordReadVisibility.ACTIVE));
        assertThat(countRequest.getValue().externalQueryValues()).containsExactlyEntriesOf(Map.of("owner", "user-1"));
        assertThat(countRequest.getValue().conditions()).hasSize(1);
        assertClause(countCriteria.getValue(), "tenantId", "tenant-a");
        assertClause(countCriteria.getValue(), "extra", true);
        ArgumentCaptor<QueryRequest> aggregateRequest = ArgumentCaptor.forClass(QueryRequest.class);
        ArgumentCaptor<Criteria> aggregateCriteria = ArgumentCaptor.forClass(Criteria.class);
        verify(projections).aggregateDefaultList(eq(module), aggregateRequest.capture(), aggregateCriteria.capture(),
                eq(service), eq(policy), eq(RecordReadVisibility.ACTIVE), eq(aggregate));
        assertThat(aggregateRequest.getValue()).isEqualTo(countRequest.getValue());
        assertClause(aggregateCriteria.getValue(), "tenantId", "tenant-a");
        assertThat(clauses(aggregateCriteria.getValue().getRoot())).noneMatch(clause -> "extra".equals(clause.getField()));
        verifyNoInteractions(service);
    }

    @Test
    void shouldPreserveContributorRequestAndUsePolicyForNonProjectionSummaryCount() {
        String module = "sales.contract";
        ModuleExecutionPlan plan = plan(module);
        ListQuerySummaryContributor contributor = new ListQuerySummaryContributor() {
            public String moduleAlias() { return module; }
            public String contributorKey() { return "contracts.count"; }
            public WebListQuerySummaryItem summarize(ListQuerySummaryContext context) {
                assertThat(context.request().externalQueryValues()).containsEntry("tenantId", "tenant-a")
                        .containsEntry("owner", "user-1");
                return new WebListQuerySummaryItem("count", context.count(Criteria.of().eq("extra", true)));
            }
        };
        StaticRecordReadProjectionService projections = mock(StaticRecordReadProjectionService.class);
        when(projections.queryDefaultList(any(), any(), any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        StandardModuleWebRuntime runtime = new StandardModuleWebRuntime(dynamicCatalog(module, plan), projections,
                new ListQuerySummaryRuntime(List.of(contributor)));
        ScopedSoftDeleteAbility service = mock(ScopedSoftDeleteAbility.class);
        @SuppressWarnings("unchecked")
        BaseDao<StandardEntity, String> dao = mock(BaseDao.class);
        when(service.getDao()).thenReturn(dao);
        when(service.count(any())).thenCallRealMethod();
        when(service.activeCriteria(any())).thenCallRealMethod();
        DataScopeAbility<?> scoped = (DataScopeAbility<?>) service;
        ActionExecutionPolicy standard = ActionExecutionPolicy.standard(
                net.ximatai.muyun.spring.common.platform.PlatformAction.QUERY);
        ActionExecutionPolicy policy = new ActionExecutionPolicy(standard.actionCode(), standard.level(),
                standard.accessMode(), standard.actionAuth(), standard.dataAuth(), standard.defaultGrantPolicy(), "view");
        var scope = net.ximatai.muyun.spring.common.platform.DataScopeCriteriaResult.restricted(
                Criteria.of().eq("authorized", true));
        AtomicReference<Criteria> requested = new AtomicReference<>();
        when(scoped.readScopeByPolicy(eq(policy), any(Criteria.class))).thenAnswer(i -> {
            requested.set(i.getArgument(1)); return scope;
        });
        when(scoped.withDataScopeTenant(eq(scope), any())).thenAnswer(i ->
                i.getArgument(1, java.util.function.Supplier.class).get());
        when(dao.count(any())).thenReturn(3L);
        WebQueryRequest request = new WebQueryRequest(null, false,
                List.of(new WebQueryCondition("code", "EQ", List.of("C-001"))), null, Map.of(), List.of(),
                null, null, Map.of("tenantId", "tenant-a", "owner", "user-1"), false, "C-001", List.of("code"), null);
        assertThat(runtime.listQuerySummaries(module, request, 8, service,
                Criteria.of().eq("tenantId", "tenant-a"), policy))
                .extracting(WebListQuerySummaryItem::value).containsExactly(3L);
        assertThat(clauses(requested.get().getRoot())).anySatisfy(c -> {
            assertThat(c.getField()).isEqualTo("code");
            assertThat(c.getValues()).containsExactly("C-001");
        });
        assertClause(requested.get(), "tenantId", "tenant-a");
        assertClause(requested.get(), "ownerId", "user-1");
        assertClause(requested.get(), "extra", true);
        assertClause(requested.get(), "code", "%C-001%");
        verify(scoped).readScopeByPolicy(eq(policy), any(Criteria.class));
        verify(service).count(scope.criteria());
        ArgumentCaptor<Criteria> activeCriteria = ArgumentCaptor.forClass(Criteria.class);
        verify(dao).count(activeCriteria.capture());
        assertClause(activeCriteria.getValue(), "authorized", true);
        assertClause(activeCriteria.getValue(), "deleted", false);
        ArgumentCaptor<QueryRequest> compiledRequest = ArgumentCaptor.forClass(QueryRequest.class);
        verify(projections).queryDefaultList(eq(module), compiledRequest.capture(), any(), any(), eq(service),
                eq(policy), eq(RecordReadVisibility.ACTIVE));
        assertThat(compiledRequest.getValue().externalQueryValues()).containsEntry("owner", "user-1")
                .doesNotContainKey("tenantId");
    }

    private interface ScopedSoftDeleteAbility extends DataScopeAbility<StandardEntity>, SoftDeleteAbility<StandardEntity> {
    }

    private static List<CriteriaClause> clauses(CriteriaGroup group) {
        List<CriteriaClause> result = new java.util.ArrayList<>();
        for (CriteriaGroup.Entry entry : group.getEntries()) {
            Object node = entry.getNode();
            if (node instanceof CriteriaClause clause) result.add(clause);
            else if (node instanceof CriteriaGroup child) result.addAll(clauses(child));
        }
        return result;
    }

    private static void assertClause(Criteria criteria, String field, Object value) {
        assertThat(clauses(criteria.getRoot())).anySatisfy(clause -> {
            assertThat(clause.getField()).isEqualTo(field);
            assertThat(clause.getValues()).containsExactly(value);
        });
    }

    private static ModuleExecutionPlan plan(String module) {
        ModuleUiDefinition definition = ModuleUiDefinition.builder(module)
                .page(PageTemplates.listDetailCard(page -> page.detail(detail -> detail.editor(editor -> editor.field("code")))
                        .list(list -> list
                        .fields(fields -> fields.field("code"))
                        .querySummaries(summary -> summary.item("count", item -> item.label("Count").contributor("contracts.count"))))))
                .build();
        ResolvedModuleUiDescriptor compiled = ModuleUiDescriptorCompiler.compile(definition,
                net.ximatai.muyun.spring.platform.module.ModuleKind.DYNAMIC, "Contract");
        QueryDescriptor query = QueryDescriptor.builder(module)
                .field(QueryField.of("code", QueryValueType.STRING, QueryOperator.EQ, QueryOperator.CONTAINS).withQuickSearch())
                .field(QueryField.of("owner", QueryValueType.STRING, QueryOperator.EQ))
                .externalCriteria("owner", QueryValueType.STRING, ExternalQueryValueSource.USER_INPUT,
                        value -> Criteria.of().eq("ownerId", value))
                .build();
        return new ModuleExecutionPlan(module, "test", compiled, new ResolvedModuleReadModel(module, "contract", List.of()),
                List.of(PageContextBindingDefinition.navigator("tenant", PageContextTarget.LIST_QUERY, "tenantId"),
                        PageContextBindingDefinition.resolvedSelection("owner", PageContextTarget.MUTATION_CONSTRAINT, "owner")),
                query, QuerySchema.from(query), List.of(), List.of(), false);
    }

    private static ModuleExecutionPlanCatalog dynamicCatalog(String module, ModuleExecutionPlan plan) {
        ModuleExecutionPlanCatalog catalog = mock(ModuleExecutionPlanCatalog.class);
        when(catalog.find(module)).thenReturn(Optional.of(plan));
        return catalog;
    }
}
