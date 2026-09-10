package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.common.exception.PlatformException;

import java.util.Set;

final class MetadataFormulaFieldValidator {
    private final ModuleMetadataRelationService relationService;
    private final MetadataFieldService fieldService;
    private final MetadataFieldReferenceConfigService referenceConfigService;

    MetadataFormulaFieldValidator(ModuleMetadataRelationService relationService, MetadataFieldService fieldService) {
        this(relationService, fieldService, null);
    }

    MetadataFormulaFieldValidator(ModuleMetadataRelationService relationService,
                                  MetadataFieldService fieldService,
                                  MetadataFieldReferenceConfigService referenceConfigService) {
        this.relationService = relationService;
        this.fieldService = fieldService;
        this.referenceConfigService = referenceConfigService;
    }

    void validateExpressionFields(Set<String> fieldPaths, ModuleMetadataRelation relation, String context) {
        for (String fieldPath : fieldPaths) {
            validateFieldPath(fieldPath, relation, context + " field");
        }
    }

    void validateTargetField(String targetField, ModuleMetadataRelation relation, String context) {
        if (targetField == null || targetField.isBlank()) {
            return;
        }
        validateFieldPath(targetField, relation, context + " target field");
    }

    private void validateFieldPath(String fieldPath, ModuleMetadataRelation relation, String context) {
        if (!fieldPath.contains(".")) {
            requireMetadataField(relation.getMetadataId(), fieldPath, context);
            return;
        }
        String[] parts = fieldPath.split("\\.");
        // A declared reference may continue through further declared reference hops.  The
        // source-neutral compiler validates every hop and terminal field before governance
        // accepts the definition; this metadata guard only distinguishes it from child syntax.
        if (parts.length >= 2 && declaredReferenceRoot(relation, parts[0])) {
            return;
        }
        if (parts.length != 2) {
            throw new PlatformException(context + " is invalid: " + fieldPath);
        }
        ModuleMetadataRelation childRelation = relationService.list(Criteria.of()
                        .eq("moduleAlias", relation.getModuleAlias())
                        .eq("parentMetadataId", relation.getMetadataId())
                        .eq("relationAlias", parts[0]), new PageRequest(0, 1)).stream()
                .findFirst()
                .orElse(null);
        if (childRelation == null) {
            throw new PlatformException(context + " relation does not exist: " + parts[0]);
        }
        requireMetadataField(childRelation.getMetadataId(), parts[1], context);
    }

    private boolean declaredReferenceRoot(ModuleMetadataRelation relation, String fieldName) {
        if (referenceConfigService == null) {
            return false;
        }
        MetadataField source = fieldService.list(Criteria.of()
                        .eq("metadataId", relation.getMetadataId()).eq("fieldName", fieldName),
                new PageRequest(0, 1)).stream().findFirst().orElse(null);
        return source != null && referenceConfigService.findForRelation(source.getId(), relation.getId()) != null;
    }

    private void requireMetadataField(String metadataId, String fieldName, String context) {
        if (fieldService.count(Criteria.of()
                .eq("metadataId", metadataId)
                .eq("fieldName", fieldName)) <= 0) {
            throw new PlatformException(context + " does not exist: " + fieldName);
        }
    }
}
