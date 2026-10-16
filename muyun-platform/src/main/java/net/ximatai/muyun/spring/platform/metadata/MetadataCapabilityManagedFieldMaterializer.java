package net.ximatai.muyun.spring.platform.metadata;

import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.PlatformManagedMutationContext;
import net.ximatai.muyun.spring.common.platform.EntityCapability;

import java.util.ArrayList;
import java.util.Set;

/** Materializes the platform-owned metadata-field catalogue; callers own transaction and schema activation. */
final class MetadataCapabilityManagedFieldMaterializer {
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);

    private MetadataCapabilityManagedFieldMaterializer() {
    }

    static void materialize(MetadataFieldService fieldService, Metadata metadata, Set<EntityCapability> capabilities) {
        ArrayList<MetadataSystemFieldCatalog.MetadataSystemFieldDescriptor> fields = new ArrayList<>(
                MetadataSystemFieldCatalog.baselineFields());
        if (Boolean.TRUE.equals(metadata.getDataScopeEnabled())) {
            fields.addAll(MetadataSystemFieldCatalog.dataScopeFields());
        }
        for (ModuleMetadataCapabilityFieldContribution contribution : MetadataCapabilityCatalog.plan(capabilities).metadataFields()) {
            fields.add(new MetadataSystemFieldCatalog.MetadataSystemFieldDescriptor(contribution.fieldName(),
                    contribution.columnName(), contribution.fieldSpecAlias(), contribution.fieldName()));
        }
        for (MetadataSystemFieldCatalog.MetadataSystemFieldDescriptor contribution : fields) {
            java.util.List<MetadataField> existing = fieldService.list(Criteria.of().eq("metadataId", metadata.getId())
                    .eq("fieldName", contribution.fieldName()), ALL);
            if (!existing.isEmpty()) {
                reconcileExisting(fieldService, metadata, contribution, existing);
                continue;
            }
            MetadataField field = new MetadataField();
            field.setMetadataId(metadata.getId());
            field.setFieldName(contribution.fieldName());
            field.setColumnName(contribution.columnName());
            field.setFieldSpecAlias(contribution.fieldSpecAlias());
            field.setTitle(contribution.title());
            field.setFieldOwnership(MetadataFieldOwnership.STANDARD);
            field.setSystemManaged(Boolean.TRUE);
            field.setSortableField("sortOrder".equals(contribution.fieldName()));
            PlatformManagedMutationContext.runAsPlatformManaged(() -> fieldService.insert(field));
        }
    }

    private static void reconcileExisting(MetadataFieldService fieldService, Metadata metadata,
                                          MetadataSystemFieldCatalog.MetadataSystemFieldDescriptor expected,
                                          java.util.List<MetadataField> existing) {
        if (existing.size() != 1) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.metadata-capability-managed-field-materializer.metadata-has-duplicate-platform-reserved-fields",
                    "元数据“{value1}”存在重复的平台保留字段：{value2}"), Map.of("value1", String.valueOf(metadata.getAlias()), "value2", String.valueOf(expected.fieldName())));
        }
        MetadataField field = existing.getFirst();
        if (!expected.columnName().equals(field.getColumnName())
                || !expected.fieldSpecAlias().equals(field.getFieldSpecAlias())) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.metadata-capability-managed-field-materializer.field-conflicts-with-a-platform-reserved-field-and-cannot-be-adopted-automatically",
                    "字段“{value1}”与平台保留字段定义冲突，不能自动接管。"), Map.of("value1", String.valueOf(expected.fieldName())));
        }
        if (field.getFieldOwnership() == MetadataFieldOwnership.STANDARD
                && Boolean.TRUE.equals(field.getSystemManaged())) {
            return;
        }
        PlatformManagedMutationContext.runAsPlatformManaged(() -> {
            field.setFieldOwnership(MetadataFieldOwnership.STANDARD);
            field.setSystemManaged(Boolean.TRUE);
            fieldService.update(field);
        });
    }
}
