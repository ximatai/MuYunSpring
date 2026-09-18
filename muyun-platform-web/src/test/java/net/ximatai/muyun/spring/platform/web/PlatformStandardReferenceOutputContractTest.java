package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.output.RecordOutputContext;
import net.ximatai.muyun.spring.ability.output.StandardReferenceRecordOutputTransformer;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformStandardReferenceOutputContractTest {
    @AfterEach
    void reset() {
        PlatformAbilityRuntime.resetReferenceTargetResolver();
    }

    @Test
    void suppliesStaticAuditLabelsWithoutAddingPhysicalColumns() {
        ReferenceAbility<?> target = mock(ReferenceAbility.class);
        when(target.projections(List.of("user-1"), List.of("title")))
                .thenReturn(Map.of("user-1", Map.of("title", "管理员")));
        PlatformAbilityRuntime.configureReferenceTargetResolver(key -> ReferenceTarget.of("iam", "user").equals(key)
                ? java.util.Optional.of(target) : java.util.Optional.empty());
        StandardEntity record = new StandardEntity() { };
        record.setCreatedBy("user-1");
        record.setUpdatedBy("user-1");

        new StandardReferenceRecordOutputTransformer().transformRecord(null, record, RecordOutputContext.view());

        assertThat(record.getCreatedByTitle()).isEqualTo("管理员");
        assertThat(record.getUpdatedByTitle()).isEqualTo("管理员");
        assertThat(StandardEntitySchema.fieldNames()).doesNotContain(
                StandardEntitySchema.CREATED_BY_TITLE_FIELD, StandardEntitySchema.UPDATED_BY_TITLE_FIELD);
        assertThat(StandardEntitySchema.auditColumns()).hasSize(9);
        verify(target, times(1)).projections(List.of("user-1"), List.of("title"));
    }

    @Test
    void suppliesStaticTenantTitleWithoutMakingItAPersistedField() {
        ReferenceAbility<?> target = mock(ReferenceAbility.class);
        when(target.projections(List.of("tenant-1"), List.of("title")))
                .thenReturn(Map.of("tenant-1", Map.of("title", "总部租户")));
        PlatformAbilityRuntime.configureReferenceTargetResolver(key -> ReferenceTarget.of("iam", "tenant").equals(key)
                ? java.util.Optional.of(target) : java.util.Optional.empty());
        StandardEntity record = new StandardEntity() { };
        record.setTenantId("tenant-1");

        new StandardReferenceRecordOutputTransformer().transformRecord(null, record, RecordOutputContext.view());

        assertThat(record.getTenantTitle()).isEqualTo("总部租户");
        assertThat(StandardEntitySchema.fieldNames()).doesNotContain(StandardEntitySchema.TENANT_TITLE_FIELD);
        verify(target).projections(List.of("tenant-1"), List.of("title"));
    }
}
