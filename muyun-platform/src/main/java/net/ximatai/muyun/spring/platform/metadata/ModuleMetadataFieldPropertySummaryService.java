package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import org.springframework.stereotype.Service;

import java.util.List;

/** Provides one effective, relation-scoped read model for field data properties. */
@Service
public class ModuleMetadataFieldPropertySummaryService {
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);

    private final ModuleMetadataRelationService relationService;
    private final MetadataFieldService fieldService;
    private final MetadataFieldReferenceConfigService referenceConfigService;
    private final MetadataFieldConfigService fieldConfigService;
    private final ModuleMetadataFieldService moduleFieldService;

    public ModuleMetadataFieldPropertySummaryService(ModuleMetadataRelationService relationService,
                                                     MetadataFieldService fieldService,
                                                     MetadataFieldReferenceConfigService referenceConfigService,
                                                     MetadataFieldConfigService fieldConfigService,
                                                     ModuleMetadataFieldService moduleFieldService) {
        this.relationService = relationService;
        this.fieldService = fieldService;
        this.referenceConfigService = referenceConfigService;
        this.fieldConfigService = fieldConfigService;
        this.moduleFieldService = moduleFieldService;
    }

    public List<ModuleMetadataFieldPropertySummary> list(String moduleAlias, String relationId) {
        String validModuleAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        ModuleMetadataRelation relation = relationService.select(relationId);
        if (relation == null || !validModuleAlias.equals(relation.getModuleAlias())) {
            throw new PlatformException("metadata relation does not belong to module: " + validModuleAlias + "." + relationId);
        }
        java.util.Map<String, ModuleMetadataField> legacy = moduleFieldService.listByRelationId(relation.getId()).stream()
                .collect(java.util.stream.Collectors.toMap(ModuleMetadataField::getMetadataFieldId, item -> item,
                        (left, ignored) -> left, java.util.LinkedHashMap::new));
        return fieldService.list(Criteria.of().eq("metadataId", relation.getMetadataId()), ALL).stream()
                .map(field -> summary(field, relation, legacy.get(field.getId()))).toList();
    }

    private ModuleMetadataFieldPropertySummary summary(MetadataField field, ModuleMetadataRelation relation,
                                                        ModuleMetadataField legacy) {
        if (legacy != null && legacyBinding(legacy)) {
            return legacySummary(field, legacy);
        }
        MetadataFieldReferenceConfig reference = referenceConfigService.findForRelation(field.getId(), relation.getId());
        MetadataFieldConfig dictionary = effectiveFieldConfig(field.getId(), relation.getId());
        if (reference != null && dictionary != null && dictionary.hasDictionaryBinding()) {
            throw new PlatformException("metadata field has conflicting reference and dictionary bindings: "
                    + field.getFieldName());
        }
        if (reference != null) {
            return new ModuleMetadataFieldPropertySummary(field.getId(), field.getFieldName(), field.getFieldSpecAlias(),
                    MetadataFieldPropertyKind.MODULE_REFERENCE, reference.getVersion(),
                    new ModuleMetadataFieldPropertySummary.Reference(reference.getTargetModuleAlias(),
                            reference.getTargetMetadataId(), reference.getTargetKeyField(), reference.getTargetLabelField(),
                            reference.getCardinality(), reference.getTargetUnavailablePolicy(),
                            reference.projections().stream().map(item -> item.targetField() + ":" + item.outputField()).toList()), null);
        }
        if (dictionary != null && dictionary.hasDictionaryBinding()) {
            return new ModuleMetadataFieldPropertySummary(field.getId(), field.getFieldName(), field.getFieldSpecAlias(),
                    MetadataFieldPropertyKind.DICTIONARY, dictionary.getVersion(), null,
                    new ModuleMetadataFieldPropertySummary.Dictionary(dictionary.getDictionaryApplicationAlias(),
                            dictionary.getDictionaryCategoryAlias(), dictionary.getSelectionMode()));
        }
        return new ModuleMetadataFieldPropertySummary(field.getId(), field.getFieldName(), field.getFieldSpecAlias(),
                MetadataFieldPropertyKind.BASIC, null, null, null);
    }

    private boolean legacyBinding(ModuleMetadataField field) {
        return hasText(field.getReferenceModuleAlias()) || hasText(field.getDictionaryCategoryAlias());
    }

    private ModuleMetadataFieldPropertySummary legacySummary(MetadataField field, ModuleMetadataField legacy) {
        ModuleMetadataFieldPropertySummary.Reference reference = hasText(legacy.getReferenceModuleAlias())
                ? new ModuleMetadataFieldPropertySummary.Reference(legacy.getReferenceModuleAlias(), null,
                legacy.getReferenceModuleKeyField(), legacy.getReferenceModuleLabelField(), null,
                legacy.getReferenceTargetUnavailablePolicy(), List.of()) : null;
        ModuleMetadataFieldPropertySummary.Dictionary dictionary = hasText(legacy.getDictionaryCategoryAlias())
                ? new ModuleMetadataFieldPropertySummary.Dictionary(legacy.getDictionaryApplicationAlias(),
                legacy.getDictionaryCategoryAlias(), null) : null;
        return new ModuleMetadataFieldPropertySummary(field.getId(), field.getFieldName(), field.getFieldSpecAlias(),
                MetadataFieldPropertyKind.LEGACY_LOCKED, legacy.getVersion(), reference, dictionary);
    }

    private MetadataFieldConfig effectiveFieldConfig(String fieldId, String relationId) {
        MetadataFieldConfig override = fieldConfigService.findRelationOverride(fieldId, relationId);
        return override == null ? fieldConfigService.findByMetadataFieldId(fieldId) : override;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
