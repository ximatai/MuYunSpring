package net.ximatai.muyun.spring.platform.reference;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceCardinality;
import net.ximatai.muyun.spring.ability.reference.ReferenceOption;
import net.ximatai.muyun.spring.ability.reference.ReferencePlan;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.common.model.standard.StandardTitledEntity;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordRuntime;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformReferenceTargetResolverTest {
    @AfterEach
    void resetReferenceTargetResolver() {
        PlatformAbilityRuntime.resetReferenceTargetResolver();
    }

    @Test
    void shouldResolveCandidateBusinessKeyToRecordIdThroughTheScopedDynamicAdapter() {
        ReferenceTarget target = ReferenceTarget.of("education.student", "student");
        ReferencePlan plan = ReferencePlan.of("studentNo", target, ReferenceCardinality.ONE)
                .withTargetFields("studentNo", "name");
        DynamicRecordService records = mock(DynamicRecordService.class);
        DynamicRecordRuntime fallback = mock(DynamicRecordRuntime.class);
        @SuppressWarnings("unchecked") ReferenceAbility<StandardTitledEntity> scoped =
                (ReferenceAbility<StandardTitledEntity>) mock(ReferenceAbility.class);
        when(records.referenceAbility(target)).thenReturn(Optional.of(scoped));
        Criteria candidateCriteria = Criteria.of().eq("studentNo", "20260001");
        PageRequest pageRequest = PageRequest.of(1, 20);
        when(scoped.referenceOptions(plan, candidateCriteria, pageRequest)).thenReturn(PageResult.of(
                List.of(new ReferenceOption("student-record-1", "张三")), 1, pageRequest));
        when(scoped.projections(List.of("student-record-1"), List.of("name")))
                .thenReturn(Map.of("student-record-1", Map.of("name", "张三")));

        PlatformAbilityRuntime.configureReferenceTargetResolver(
                new PlatformReferenceTargetResolver(null, fallback, records));

        ReferenceAbility<?> resolved = PlatformAbilityRuntime.referenceTargetResolver().resolve(target).orElseThrow();
        assertThat(resolved.referenceOptions(plan, candidateCriteria, pageRequest).getRecords())
                .containsExactly(new ReferenceOption("student-record-1", "张三"));
        assertThat(resolved.projections(List.of("student-record-1"), List.of("name")))
                .containsExactly(Map.entry("student-record-1", Map.of("name", "张三")));
        verify(fallback, never()).referenceAbility(target);
    }
}
