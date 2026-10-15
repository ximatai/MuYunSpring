package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.output.RecordOutputContext;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.ability.security.FieldProtectionAbility;
import net.ximatai.muyun.spring.platform.metadata.FieldSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class ReferenceSummaryRecordOutputTransformerTest {
    private final ReferenceSummaryRecordOutputTransformer transformer = new ReferenceSummaryRecordOutputTransformer();

    @AfterEach
    void reset() {
        PlatformAbilityRuntime.resetReferenceTargetResolver();
    }

    @SuppressWarnings("unchecked")
    private CrudAbility<FieldSpec> source() {
        CrudAbility<FieldSpec> service = mock(CrudAbility.class);
        doReturn(FieldSpec.class).when(service).modelClass();
        return service;
    }

    @Test
    void batchesDeclaredSummariesWithoutChangingSourceIdsOrInventingMissingTargets() {
        ReferenceAbility<?> controls = mock(ReferenceAbility.class);
        PlatformAbilityRuntime.configureReferenceTargetResolver(target ->
                ReferenceTarget.of("platform", "field_ui_control").equals(target)
                        ? Optional.of(controls) : Optional.empty());
        when(controls.projections(List.of("text", "missing"), List.of("title", "alias")))
                .thenReturn(Map.of("text", Map.of("title", "文本", "alias", "text")));
        var first = record("first", "text");
        var second = record("second", "text");
        var missing = record("third", "missing");
        missing.setDefaultUiControlSummary(Map.of("id", "old", "title", "Stale"));
        var records = List.of(first, second, missing);

        assertThat(transformer.transformRecords(source(), records, RecordOutputContext.view())).isSameAs(records);

        assertThat(first.getDefaultUiControlSummary()).containsExactlyInAnyOrderEntriesOf(
                Map.of("id", "text", "title", "文本", "alias", "text"));
        assertThat(second.getDefaultUiControlSummary()).isEqualTo(first.getDefaultUiControlSummary());
        assertThat(missing.getDefaultUiControlSummary()).isNull();
        assertThat(records).extracting(FieldSpec::getDefaultUiControlAlias).containsExactly("text", "text", "missing");
        assertThat(records).extracting(FieldSpec::getTitle).containsExactly("first", "second", "third");
        verify(controls).projections(List.of("text", "missing"), List.of("title", "alias"));
        verifyNoMoreInteractions(controls);
    }

    @Test
    void clearsSummariesWhenTheReferenceIsClearedWithoutReadingCandidates() {
        var source = source();
        var record = record("first", null);
        record.setDefaultUiControlSummary(Map.of("id", "old", "title", "Stale"));
        record.setUiControlSummaries(List.of(Map.of("id", "old", "title", "Stale")));
        ReferenceAbility<?> controls = mock(ReferenceAbility.class);
        PlatformAbilityRuntime.configureReferenceTargetResolver(target ->
                ReferenceTarget.of("platform", "field_ui_control").equals(target)
                        ? Optional.of(controls) : Optional.empty());

        transformer.transformRecord(source, record, RecordOutputContext.form("editor"));

        assertThat(record.getDefaultUiControlSummary()).isNull();
        assertThat(record.getUiControlSummaries()).isEmpty();
        assertThat(record.getDefaultUiControlAlias()).isNull();
        verifyNoInteractions(controls);
    }

    @Test
    @SuppressWarnings("unchecked")
    void doesNotExposeAMaskedSourceIdentityThroughItsSummary() {
        CrudAbility<FieldSpec> source = mock(CrudAbility.class, withSettings().extraInterfaces(
                FieldProtectionAbility.class));
        doReturn(FieldSpec.class).when(source).modelClass();
        var protection = (FieldProtectionAbility<?>) source;
        when(protection.maskProtectedValue(eq("defaultUiControlAlias"), eq("text"), any())).thenReturn("***");
        ReferenceAbility<?> controls = mock(ReferenceAbility.class);
        PlatformAbilityRuntime.configureReferenceTargetResolver(target ->
                ReferenceTarget.of("platform", "field_ui_control").equals(target)
                        ? Optional.of(controls) : Optional.empty());
        var record = record("first", "text");
        record.setDefaultUiControlSummary(Map.of("id", "text", "title", "Stale"));

        transformer.transformRecord(source, record, RecordOutputContext.view());

        assertThat(record.getDefaultUiControlSummary()).isNull();
        assertThat(record.getDefaultUiControlAlias()).isEqualTo("text");
        verifyNoInteractions(controls);
    }

    @Test
    void leavesOtherOutputSurfacesOnTheirExistingProjectionPaths() {
        var source = source();
        assertThat(transformer.supports(source, RecordOutputContext.view())).isTrue();
        assertThat(transformer.supports(source, RecordOutputContext.form("editor"))).isTrue();
        for (var context : List.of(RecordOutputContext.list(), RecordOutputContext.reference(),
                RecordOutputContext.audit(), RecordOutputContext.export())) {
            assertThat(transformer.supports(source, context)).isFalse();
        }
    }

    private FieldSpec record(String title, String control) {
        var record = new FieldSpec();
        record.setTitle(title);
        record.setDefaultUiControlAlias(control);
        return record;
    }
}
