package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.database.core.orm.*;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.reference.*;
import net.ximatai.muyun.spring.common.model.standard.StandardTitledEntity;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.content.BusinessContentQueryService;
import net.ximatai.muyun.spring.platform.reference.StaticAbilityCatalog;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class RecordExchangeReferenceSupportTest {
    @AfterEach void reset() { PlatformAbilityRuntime.resetReferenceTargetResolver(); }

    @Test @SuppressWarnings("unchecked")
    void fileObjectChoicesReuseTheScopedMaskedCandidateSubtitle() {
        var target = ReferenceTarget.of("test.category", "category");
        ReferenceAbility<StandardTitledEntity> ability = mock(ReferenceAbility.class);
        var catalog = mock(StaticAbilityCatalog.class);
        when(catalog.findByTarget(target)).thenReturn(Optional.of(ability));
        when(catalog.findReference(target)).thenReturn(Optional.of(ability));
        var queries = mock(BusinessContentQueryService.class);
        when(queries.referenceName(eq(ability), eq("title"), eq("Same name"), eq("en"), anyString()))
                .thenReturn(Criteria.of().eq("title", "Same name"));
        when(ability.referenceOptions(any(), any(), any())).thenReturn(PageResult.of(
                List.of(new ReferenceOption("category-one", "Same name")), 1, PageRequest.of(1, 21)));
        when(ability.projections(List.of("category-one"), List.of("code")))
                .thenReturn(Map.of("category-one", Map.of("code", "CAT-001")));
        PlatformAbilityRuntime.configureReferenceTargetResolver(value -> value.equals(target) ? Optional.of(ability) : Optional.empty());
        var support = new RecordExchangeReferenceSupport(catalog, mock(DynamicRecordService.class), queries,
                mock(BusinessContentRecordPresentationService.class));
        var source = new RecordExchangeReferenceSupport.Source("test.product", "product",
                ReferencePlan.of("categoryId", target, ReferenceCardinality.ONE).withTargetFields("id", "title")
                        .withCandidateSubtitleProjection("code"), false, null);
        assertThat(support.resolve(source, "Same name", "en", Map.of()).candidates()).singleElement()
                .satisfies(candidate -> { assertThat(candidate.id()).isEqualTo("category-one"); assertThat(candidate.subtitle()).isEqualTo("CAT-001"); });
        var many = new RecordExchangeReferenceSupport.Source("test.product", "product",
                ReferencePlan.of("categoryIds", target, ReferenceCardinality.MANY).withTargetFields("id", "title")
                        .withCandidateSubtitleProjection("code"), false, null);
        assertThat(support.resolve(many, "Same name", "en", Map.of()).candidates()).singleElement()
                .satisfies(candidate -> { assertThat(candidate.kind()).isEqualTo("ORIGINAL"); assertThat(candidate.subtitle()).isEqualTo("CAT-001"); });
        clearInvocations(ability);
        when(ability.isReferenceFieldProtected("code")).thenReturn(true);
        assertThat(support.resolve(source, "Same name", "en", Map.of()).candidates()).singleElement()
                .satisfies(candidate -> assertThat(candidate.subtitle()).isNull());
        verify(ability, never()).projections(any(), any());
    }
}
