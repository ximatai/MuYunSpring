package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.CriteriaClause;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceOption;
import net.ximatai.muyun.spring.ability.reference.ReferencePlan;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargetResolver;
import net.ximatai.muyun.spring.ability.reference.ReferenceTo;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.common.option.OptionSourceRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultListQuerySummaryGroupLabelResolverTest {
    @Test
    void resolvesStaticReferenceGroupsThroughOneScopedIdBatchWithoutDynamicFallbackOrNullTitleFailure() {
        StaticModuleDefinition source = StaticModuleDefinition.builder("sales", "sales.order", "订单")
                .modelClass(StaticSource.class)
                .entities(List.of(new EntityDefinition("order", "order", "订单", List.of(
                        FieldDefinition.string("customerId", "客户")))))
                .build();
        DynamicRecordService dynamicRecords = mock(DynamicRecordService.class);
        @SuppressWarnings("unchecked")
        ReferenceAbility<?> target = mock(ReferenceAbility.class);
        ReferencePlan plan = new ReferencePlan("customerId", net.ximatai.muyun.spring.ability.reference.ReferenceTarget.of("crm", "customer"),
                net.ximatai.muyun.spring.ability.reference.ReferenceCardinality.ONE);
        when(dynamicRecords.referenceAbility(plan.target())).thenReturn(Optional.of(target));
        when(target.referenceOptions(eq(plan), any(Criteria.class), any(PageRequest.class))).thenReturn(PageResult.of(
                List.of(new ReferenceOption("customer-a", "同名客户"), new ReferenceOption("customer-b", "同名客户"),
                        new ReferenceOption("customer-hidden", null)), 3, new PageRequest(0, Integer.MAX_VALUE)));
        DefaultListQuerySummaryGroupLabelResolver resolver = new DefaultListQuerySummaryGroupLabelResolver(
                new StaticModuleDefinitionCatalog(List.of(source)), provider(dynamicRecords), new OptionSourceRegistry(List.of()));
        ResolvedPageListQuerySummaryDescriptor summary = new ResolvedPageListQuerySummaryDescriptor("byCustomer", "客户",
                PageListQuerySummaryDefinition.Source.GROUPED, null, null, "customerId", "客户", null);
        PlatformAbilityRuntime.configureReferenceTargetResolver(ReferenceTargetResolver.NONE);
        try {
            Map<String, String> labels = resolver.labels("sales.order", summary,
                    List.of("customer-a", "customer-b", "customer-hidden"));
            assertThat(labels).containsExactlyInAnyOrderEntriesOf(Map.of("customer-a", "同名客户", "customer-b", "同名客户"));
            org.mockito.ArgumentCaptor<Criteria> criteria = org.mockito.ArgumentCaptor.forClass(Criteria.class);
            verify(target).referenceOptions(eq(plan), criteria.capture(), any(PageRequest.class));
            CriteriaClause idClause = criteria.getValue().getClauses().stream()
                    .filter(clause -> "id".equals(clause.getField())).findFirst().orElseThrow();
            assertThat(idClause.getValues()).contains("customer-a", "customer-b", "customer-hidden");
            verify(dynamicRecords, never()).describe("sales.order");
        } finally {
            PlatformAbilityRuntime.resetReferenceTargetResolver();
        }
    }

    static final class StaticSource {
        @ReferenceTo(moduleAlias = "crm", entityAlias = "customer")
        String customerId;
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override public T getObject(Object... args) { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
            @Override public T getObject() { return value; }
        };
    }
}
