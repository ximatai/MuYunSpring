package net.ximatai.muyun.spring.dynamic.web;

import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.reference.PlatformTenantReferences;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceReadPipeline;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.common.security.FieldOutputContext;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DynamicTenantReadProjectionTest {
    @AfterEach
    void reset() {
        PlatformAbilityRuntime.resetReferenceTargetResolver();
    }

    @Test
    void projectsTenantTitleAsBatchReadOutputAndKeepsItOutOfDynamicMutationFields() {
        ReferenceAbility<?> tenant = mock(ReferenceAbility.class);
        when(tenant.projections(List.of("tenant-1"), List.of("title")))
                .thenReturn(Map.of("tenant-1", Map.of("title", "总部租户")));
        PlatformAbilityRuntime.configureReferenceTargetResolver(target -> ReferenceTarget.of("iam", "tenant").equals(target)
                ? java.util.Optional.of(tenant) : java.util.Optional.empty());
        DynamicRecord record = new DynamicRecord(new EntityDefinition("contract", "contract", "Contract",
                List.of(FieldDefinition.string("code", "编码"))));
        record.setTenantId("tenant-1");

        new ReferenceReadPipeline<DynamicRecord>(PlatformTenantReferences.availablePlans(), List.of(),
                PlatformTenantReferences::values,
                (source, output) -> output.forEach(source::putReadProjectionValue),
                target -> PlatformAbilityRuntime.referenceTargetResolver().resolve(target).orElseThrow())
                .populate(List.of(record));

        assertThat(record.outputValues(FieldOutputContext.VIEW)).containsEntry("tenantTitle", "总部租户");
        DynamicRecordService service = mock(DynamicRecordService.class);
        when(service.references("demo.contract", "contract")).thenReturn(List.of());
        assertThat(DynamicWebRecordReadFields.readOnlyOutputs(service, "demo.contract", record))
                .contains("tenantTitle");
    }
}
