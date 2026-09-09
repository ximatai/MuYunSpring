package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.output.AuditReferenceRecordOutputTransformer;
import net.ximatai.muyun.spring.ability.output.RecordOutputContext;

import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlatformAuditOutputContractTest {
    @AfterEach void reset() { PlatformAbilityRuntime.resetReferenceTargetResolver(); }
    @Test void suppliesStaticDetailLabelsWithoutAddingPhysicalColumns() {
        ReferenceAbility<?> target = mock(ReferenceAbility.class);
        when(target.projections(List.of("user-1"), List.of("title")))
                .thenReturn(Map.of("user-1", Map.of("title", "管理员")));
        PlatformAbilityRuntime.configureReferenceTargetResolver(key -> ReferenceTarget.of("iam", "user").equals(key)
                ? java.util.Optional.of(target) : java.util.Optional.empty());
        StandardEntity record = new StandardEntity() { };
        record.setCreatedBy("user-1");
        record.setUpdatedBy("user-1");
        new AuditReferenceRecordOutputTransformer().transformRecord(null, record, RecordOutputContext.view());
        assertThat(record.getCreatedByTitle()).isEqualTo("管理员");
        assertThat(record.getUpdatedByTitle()).isEqualTo("管理员");
        assertThat(StandardEntitySchema.fieldNames()).doesNotContain("createdByTitle", "updatedByTitle");
        assertThat(StandardEntitySchema.auditColumns()).hasSize(9);
        verify(target, times(1)).projections(List.of("user-1"), List.of("title"));
    }
}
