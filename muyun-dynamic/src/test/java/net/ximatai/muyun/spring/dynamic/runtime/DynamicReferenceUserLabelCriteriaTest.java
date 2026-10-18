package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.database.core.metadata.DBInfo;
import net.ximatai.muyun.database.core.orm.*;
import net.ximatai.muyun.spring.ability.reference.*;
import net.ximatai.muyun.spring.common.model.standard.StandardTitledEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.*;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class DynamicReferenceUserLabelCriteriaTest {
    @ParameterizedTest @EnumSource(DynamicReferenceMatchMode.class)
    @SuppressWarnings("unchecked") void expandsOnlyTheUserNameConditionInsideTheExistingScope(DynamicReferenceMatchMode mode) {
        var source = mock(DynamicEntityService.class);
        ReferenceAbility<?> target = mock(ReferenceAbility.class);
        doReturn(StandardTitledEntity.class).when(target).modelClass();
        when(target.referenceOptions(any(), any())).thenAnswer(call -> PageResult.of(List.of(), 0, call.getArgument(1)));
        var plan = ReferencePlan.of("categoryId", ReferenceTarget.of("test", "category"), ReferenceCardinality.ONE);
        var resolver = new DynamicReferenceResolver(source, plan, target, List.of());
        var received = new ArrayList<Criteria>();
        UnaryOperator<Criteria> labels = input -> { received.add(input); return Criteria.of().eq("id", "translated-id"); };
        var fixed = Criteria.of().eq("tenantId", "tenant-a").eq("enabled", true);
        resolver.resolve(DynamicReferenceResolveRequest.query("Equipment").withMatchMode(mode).withCriteria(fixed), labels);
        var captured = org.mockito.ArgumentCaptor.forClass(Criteria.class);
        verify(target).referenceOptions(captured.capture(), any());
        var compiled = new CriteriaSqlCompiler().compile(captured.getValue(), field -> field, DBInfo.Type.POSTGRESQL);
        assertThat(compiled.getParams()).containsValues("tenant-a", true);
        if (mode == DynamicReferenceMatchMode.KEY) {
            assertThat(received).isEmpty();
            assertThat(compiled.getParams()).containsValue("Equipment").doesNotContainValue("translated-id");
        } else {
            assertThat(received).hasSize(1);
            var label = new CriteriaSqlCompiler().compile(received.getFirst(), field -> field, DBInfo.Type.POSTGRESQL);
            assertThat(label.getParams()).containsValue("%Equipment%").doesNotContainValue("tenant-a");
            assertThat(compiled.getParams()).containsValue("translated-id");
            if (mode == DynamicReferenceMatchMode.AUTO) assertThat(compiled.getParams()).containsValue("Equipment");
        }
        assertThat(new CriteriaSqlCompiler().compile(fixed, field -> field, DBInfo.Type.POSTGRESQL).getParams())
                .containsValues("tenant-a", true).doesNotContainValue("translated-id");
    }

    @Test @SuppressWarnings("unchecked") void blankBrowseAndIdentityRecoveryNeverInvokeNameExpansion() {
        var source = mock(DynamicEntityService.class);
        ReferenceAbility<?> target = mock(ReferenceAbility.class);
        doReturn(StandardTitledEntity.class).when(target).modelClass();
        when(target.referenceOptions(any(), any())).thenAnswer(call -> PageResult.of(List.of(), 0, call.getArgument(1)));
        var resolver = new DynamicReferenceResolver(source,
                ReferencePlan.of("categoryId", ReferenceTarget.of("test", "category"), ReferenceCardinality.ONE), target, List.of());
        UnaryOperator<Criteria> expansion = ignored -> { throw new AssertionError("not a user name query"); };
        resolver.resolve(DynamicReferenceResolveRequest.query(" "), expansion);
        resolver.resolve(DynamicReferenceResolveRequest.translate(List.of("stable-id")).withMatchMode(DynamicReferenceMatchMode.KEY), expansion);
        verify(target, times(2)).referenceOptions(any(), any());
    }
}
