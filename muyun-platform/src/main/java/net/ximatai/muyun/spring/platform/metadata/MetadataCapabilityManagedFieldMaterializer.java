package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.PlatformManagedMutationContext;
import net.ximatai.muyun.spring.common.platform.EntityCapability;

import java.util.Set;

/** Materializes only catalog-owned fields; callers own transaction and schema activation. */
final class MetadataCapabilityManagedFieldMaterializer {
    private static final PageRequest ONE = new PageRequest(0, 1);

    private MetadataCapabilityManagedFieldMaterializer() {
    }

    static void materialize(MetadataFieldService fieldService, Metadata metadata, Set<EntityCapability> capabilities) {
        for (ModuleMetadataCapabilityFieldContribution contribution : MetadataCapabilityCatalog.plan(capabilities).metadataFields()) {
            if (!fieldService.list(Criteria.of().eq("metadataId", metadata.getId())
                    .eq("fieldName", contribution.fieldName()), ONE).isEmpty()) continue;
            MetadataField field = new MetadataField();
            field.setMetadataId(metadata.getId());
            field.setFieldName(contribution.fieldName());
            field.setColumnName(contribution.columnName());
            field.setFieldSpecAlias(contribution.fieldSpecAlias());
            field.setTitle(contribution.fieldName());
            field.setFieldOwnership(MetadataFieldOwnership.STANDARD);
            field.setSystemManaged(Boolean.TRUE);
            field.setSortableField("sortOrder".equals(contribution.fieldName()));
            PlatformManagedMutationContext.runAsPlatformManaged(() -> fieldService.insert(field));
        }
    }
}
