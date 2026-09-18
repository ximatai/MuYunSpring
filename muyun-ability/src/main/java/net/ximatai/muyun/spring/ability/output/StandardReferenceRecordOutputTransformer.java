package net.ximatai.muyun.spring.ability.output;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.reference.PlatformStandardReferences;
import net.ximatai.muyun.spring.ability.reference.ReferenceReadPipeline;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;

import java.util.List;

/** Batch-enriches standard read-only reference labels for static record output. */
public final class StandardReferenceRecordOutputTransformer implements RecordOutputTransformer {
    @Override
    public boolean supports(CrudAbility<?> service, RecordOutputContext context) {
        return service != null && service.modelClass() != null
                && StandardEntity.class.isAssignableFrom(service.modelClass())
                && (context.surface() == RecordOutputSurface.VIEW || context.surface() == RecordOutputSurface.FORM);
    }

    @Override
    public <T extends EntityContract> T transformRecord(CrudAbility<T> service, T record, RecordOutputContext context) {
        if (record != null) {
            transformRecords(service, List.of(record), context);
        }
        return record;
    }

    @Override
    public <T extends EntityContract> List<T> transformRecords(CrudAbility<T> service, List<T> records,
                                                                 RecordOutputContext context) {
        new ReferenceReadPipeline<T>(PlatformStandardReferences.availablePlans(), List.of(),
                PlatformStandardReferences::values, (record, output) -> {
                    if (record instanceof StandardEntity entity) {
                        if (output.containsKey(StandardEntitySchema.TENANT_TITLE_FIELD)) {
                            entity.setTenantTitle((String) output.get(StandardEntitySchema.TENANT_TITLE_FIELD));
                        }
                        if (output.containsKey(StandardEntitySchema.CREATED_BY_TITLE_FIELD)) {
                            entity.setCreatedByTitle((String) output.get(StandardEntitySchema.CREATED_BY_TITLE_FIELD));
                        }
                        if (output.containsKey(StandardEntitySchema.UPDATED_BY_TITLE_FIELD)) {
                            entity.setUpdatedByTitle((String) output.get(StandardEntitySchema.UPDATED_BY_TITLE_FIELD));
                        }
                    }
                }, target -> PlatformAbilityRuntime.referenceTargetResolver().resolve(target).orElseThrow())
                .populate(records);
        return records;
    }
}
