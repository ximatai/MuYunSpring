package net.ximatai.muyun.spring.ability.output;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.reference.PlatformAuditReferences;
import net.ximatai.muyun.spring.ability.reference.ReferenceReadPipeline;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import java.util.List;

/** Resolves standard static audit labels through the shared module-reference read pipeline. */
public final class AuditReferenceRecordOutputTransformer implements RecordOutputTransformer {
    @Override public boolean supports(CrudAbility<?> service, RecordOutputContext context) {
        return service != null && service.modelClass() != null
                && StandardEntity.class.isAssignableFrom(service.modelClass())
                && (context.surface() == RecordOutputSurface.VIEW || context.surface() == RecordOutputSurface.FORM);
    }
    @Override public <T extends EntityContract> T transformRecord(CrudAbility<T> service, T record, RecordOutputContext context) {
        if (record != null) transformRecords(service, List.of(record), context);
        return record;
    }
    @Override public <T extends EntityContract> List<T> transformRecords(CrudAbility<T> service, List<T> records,
                                                                       RecordOutputContext context) {
        new ReferenceReadPipeline<T>(PlatformAuditReferences.availablePlans(), List.of(), PlatformAuditReferences::values,
                (record, output) -> {
                    if (record instanceof StandardEntity entity) {
                        entity.setCreatedByTitle((String) output.get("createdByTitle"));
                        entity.setUpdatedByTitle((String) output.get("updatedByTitle"));
                    }
                }, target -> PlatformAbilityRuntime.referenceTargetResolver().resolve(target).orElseThrow())
                .populate(records);
        return records;
    }
}
