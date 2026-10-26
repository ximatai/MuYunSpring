package net.ximatai.muyun.spring.platform.config;

import net.ximatai.muyun.spring.common.i18n.DiagnosticText;
import net.ximatai.muyun.spring.platform.measure.MeasureUnitCategoryService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class LowCodeMeasureUnitHealthChecker implements LowCodeModuleHealthChecker {
    private static final Set<String> NUMERIC_TYPES = Set.of("DECIMAL", "NUMERIC", "NUMBER", "INTEGER", "INT",
            "LONG", "BIGINT");
    private static final Set<String> TEXT_TYPES = Set.of("STRING", "TEXT", "VARCHAR", "CHAR");

    @Override
    public List<LowCodeConfigHealthItem> check(LowCodeModuleHealthContext context) {
        LowCodeModulePackage modulePackage = context == null ? null : context.modulePackage();
        if (modulePackage == null || !modulePackage.includes(LowCodePackageBundleType.METADATA)) {
            return List.of();
        }
        LowCodeConfigBundle bundle = modulePackage.bundleMap().get(LowCodePackageBundleType.METADATA);
        if (bundle == null || bundle.content().isEmpty()) {
            return List.of();
        }
        List<FieldContract> fields = LowCodeMetadataFieldProjection.from(bundle).stream()
                .map(this::contract)
                .toList();
        if (fields.isEmpty()) {
            return List.of();
        }
        Set<String> fieldNames = fields.stream()
                .map(FieldContract::fieldName)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, String> fieldTypes = fields.stream()
                .filter(field -> field.fieldName() != null)
                .filter(field -> field.fieldType() != null)
                .collect(Collectors.toMap(FieldContract::fieldName, FieldContract::fieldType, (left, right) -> left));
        Set<String> measureDependencies = modulePackage.dependencyManifest().dependencies().stream()
                .filter(dependency -> dependency != null && dependency.type() == LowCodePackageDependencyType.MEASURE_UNIT)
                .map(dependency -> dependency.applicationAlias() + ":" + dependency.alias())
                .collect(Collectors.toSet());
        List<LowCodeConfigHealthItem> items = new ArrayList<>();
        for (FieldContract field : fields) {
            if (field.unitCategoryAlias() == null) {
                continue;
            }
            String targetId = field.fieldName() == null ? field.unitCategoryAlias() : field.fieldName();
            requireType(items,
                field.fieldType(),
                NUMERIC_TYPES,
                "MEASURE_UNIT_OWNER_NOT_NUMERIC",
                DiagnosticText.code("platform.config.diagnostic.measure-unit-field-requires-numeric-owner",
                    "measure unit field requires numeric owner"),
                targetId);
            requireDependency(items, modulePackage.applicationAlias(), field.unitCategoryAlias(), measureDependencies, targetId);
            String baseUnitCategoryAlias = field.baseUnitCategoryAlias() == null
                    ? field.unitCategoryAlias()
                    : field.baseUnitCategoryAlias();
            if (!Objects.equals(field.unitCategoryAlias(), baseUnitCategoryAlias)) {
                requireDependency(items, modulePackage.applicationAlias(), baseUnitCategoryAlias, measureDependencies, targetId);
            }
            requireText(items,
                field.baseUnitCode(),
                "MEASURE_UNIT_BASE_UNIT_MISSING",
                DiagnosticText.code("platform.config.diagnostic.measure-unit-field-requires-baseunitcode",
                    "measure unit field requires baseUnitCode"),
                targetId);
            requireBaseValueField(items, field, fieldNames, fieldTypes, targetId);
            if (field.unitMode() == null) {
                items.add(error("MEASURE_UNIT_MODE_MISSING",
                DiagnosticText.code("platform.config.diagnostic.measure-unit-field-requires-unitmode",
                    "measure unit field requires unitMode"),
                targetId,
                DiagnosticText.code("platform.config.diagnostic.set-unitmode-to-fixed-or-selectable",
                    "Set unitMode to FIXED or SELECTABLE")));
            } else if ("FIXED".equals(field.unitMode())) {
                requireText(items,
                field.fixedUnitCode(),
                "MEASURE_UNIT_FIXED_UNIT_MISSING",
                DiagnosticText.code("platform.config.diagnostic.fixed-measure-unit-field-requires-fixedunitcode",
                    "fixed measure unit field requires fixedUnitCode"),
                targetId);
            } else if ("SELECTABLE".equals(field.unitMode())) {
                requireRelatedField(items,
                field.unitFieldName(),
                fieldNames,
                "MEASURE_UNIT_COMPANION_MISSING",
                DiagnosticText.code("platform.config.diagnostic.selectable-measure-unit-field-requires-unit-companion-field",
                    "selectable measure unit field requires unit companion field"),
                targetId);
                requireRelatedType(items,
                field.unitFieldName(),
                fieldTypes,
                TEXT_TYPES,
                "MEASURE_UNIT_COMPANION_NOT_TEXT",
                DiagnosticText.code("platform.config.diagnostic.measure-unit-companion-field-must-be-text",
                    "measure unit companion field must be text"),
                targetId);
            }
            requireOptionalRelatedField(items,
                field.conversionScopeFieldName(),
                fieldNames,
                "MEASURE_UNIT_SCOPE_FIELD_MISSING",
                DiagnosticText.code("platform.config.diagnostic.measure-unit-conversion-scope-field-is-missing",
                    "measure unit conversion scope field is missing"),
                targetId);
        }
        return List.copyOf(items);
    }

    private void requireDependency(List<LowCodeConfigHealthItem> items,
                                   String applicationAlias,
                                   String categoryAlias,
                                   Set<String> dependencies,
                                   String targetId) {
        String dependencyKey = applicationAlias + ":" + categoryAlias;
        String sharedDependencyKey = MeasureUnitCategoryService.SHARED_APPLICATION_ALIAS + ":" + categoryAlias;
        if (dependencies.contains(sharedDependencyKey) || dependencies.contains(dependencyKey)) {
            return;
        }
        items.add(LowCodeConfigHealthItem.warn(LowCodeConfigHealthScope.DEPENDENCY,
                "MEASURE_UNIT_DEPENDENCY_MISSING",
                DiagnosticText.code("platform.config.diagnostic.measure-unit-category-is-not-declared-in-dependency-manifest",
                    "measure unit category is not declared in dependency manifest"),
                "measureUnit",
                targetId,
                DiagnosticText.code("platform.config.diagnostic.declare-measure-unit-dependency-for-cross-environment-migration",
                    "Declare MEASURE_UNIT dependency {sharedDependencyKey} for cross-environment migration", Map.of("sharedDependencyKey", String.valueOf(sharedDependencyKey)))));
    }

    private void requireBaseValueField(List<LowCodeConfigHealthItem> items,
                                       FieldContract field,
                                       Set<String> fieldNames,
                                       Map<String, String> fieldTypes,
                                       String targetId) {
        String baseValueFieldName = field.baseValueFieldName();
        String ownerFieldName = field.fieldName();
        if (baseValueFieldName == null) {
            items.add(error("MEASURE_UNIT_BASE_VALUE_MISSING",
                DiagnosticText.code("platform.config.diagnostic.measure-unit-field-requires-base-value-field",
                    "measure unit field requires base value field"),
                targetId,
                DiagnosticText.code("platform.config.diagnostic.add-a-shadow-base-value-field-and-bind-basevaluefieldname",
                    "Add a shadow base value field and bind baseValueFieldName")));
            return;
        }
        if (Objects.equals(ownerFieldName, baseValueFieldName)
                || (field.ownerFieldId() != null && field.baseValueFieldId() != null
                && Objects.equals(field.ownerFieldId(), field.baseValueFieldId()))) {
            items.add(error("MEASURE_UNIT_BASE_VALUE_CONFLICT",
                DiagnosticText.code("platform.config.diagnostic.measure-base-value-field-must-be-different-from-owner",
                    "measure base value field must be different from owner"),
                targetId,
                DiagnosticText.code("platform.config.diagnostic.use-a-dedicated-shadow-field-such-as-base",
                    "Use a dedicated shadow field such as {ownerFieldName}Base", Map.of("ownerFieldName", String.valueOf(ownerFieldName)))));
            return;
        }
        if (!fieldNames.contains(baseValueFieldName)) {
            items.add(error("MEASURE_UNIT_BASE_VALUE_MISSING",
                DiagnosticText.code("platform.config.diagnostic.measure-base-value-field-is-missing-from-metadata-fields",
                    "measure base value field is missing from metadata fields"),
                targetId,
                DiagnosticText.code("platform.config.diagnostic.include-the-shadow-base-value-field-in-the-metadata-bundle",
                    "Include the shadow base value field in the metadata bundle")));
            return;
        }
        requireRelatedType(items,
                baseValueFieldName,
                fieldTypes,
                NUMERIC_TYPES,
                "MEASURE_UNIT_BASE_VALUE_NOT_NUMERIC",
                DiagnosticText.code("platform.config.diagnostic.measure-base-value-field-must-be-numeric",
                    "measure base value field must be numeric"),
                targetId);
    }

    private void requireRelatedField(List<LowCodeConfigHealthItem> items,
                                     String fieldName,
                                     Set<String> fieldNames,
                                     String code,
                                     DiagnosticText message,
                                     String targetId) {
        if (fieldName == null || !fieldNames.contains(fieldName)) {
            items.add(error(code,
                message,
                targetId,
                DiagnosticText.code("platform.config.diagnostic.include-and-bind-related-field",
                    "Include and bind related field")));
        }
    }

    private void requireOptionalRelatedField(List<LowCodeConfigHealthItem> items,
                                             String fieldName,
                                             Set<String> fieldNames,
                                             String code,
                                             DiagnosticText message,
                                             String targetId) {
        if (fieldName != null && !fieldNames.contains(fieldName)) {
            items.add(error(code,
                message,
                targetId,
                DiagnosticText.code("platform.config.diagnostic.include-or-remove-related-field",
                    "Include or remove related field")));
        }
    }

    private void requireRelatedType(List<LowCodeConfigHealthItem> items,
                                    String fieldName,
                                    Map<String, String> fieldTypes,
                                    Set<String> expectedTypes,
                                    String code,
                                    DiagnosticText message,
                                    String targetId) {
        if (fieldName == null) {
            return;
        }
        requireType(items, fieldTypes.get(fieldName), expectedTypes, code, message, targetId);
    }

    private void requireType(List<LowCodeConfigHealthItem> items,
                             String fieldType,
                             Set<String> expectedTypes,
                             String code,
                             DiagnosticText message,
                             String targetId) {
        if (fieldType != null && !expectedTypes.contains(fieldType)) {
            items.add(error(code,
                message,
                targetId,
                DiagnosticText.code("platform.config.diagnostic.use-a-compatible-metadata-field-type",
                    "Use a compatible metadata field type")));
        }
    }

    private void requireText(List<LowCodeConfigHealthItem> items,
                             String value,
                             String code,
                             DiagnosticText message,
                             String targetId) {
        if (value == null) {
            items.add(error(code,
                message,
                targetId,
                DiagnosticText.code("platform.config.diagnostic.set-required-measure-unit-field",
                    "Set required measure unit field")));
        }
    }

    private LowCodeConfigHealthItem error(String code,
                DiagnosticText message,
                String targetId,
                DiagnosticText suggestion) {
        return LowCodeConfigHealthItem.error(LowCodeConfigHealthScope.METADATA,
                code,
                message,
                "field",
                targetId,
                suggestion);
    }

    private FieldContract contract(LowCodeMetadataFieldProjection field) {
        Map<String, Object> measureUnit = field.nested("measureUnit");
        return new FieldContract(
                field.fieldName(),
                field.ownerFieldId(),
                field.runtimeFieldType(),
                field.firstText(measureUnit, "unitCategoryAlias", "categoryAlias"),
                field.firstText(measureUnit, "unitMode", "mode"),
                field.firstText(measureUnit, "fixedUnitCode"),
                field.firstText(measureUnit, "baseUnitCategoryAlias"),
                field.firstText(measureUnit, "baseUnitCode"),
                field.relatedFieldName(measureUnit, "unitFieldName", "unitFieldId"),
                field.relatedFieldName(measureUnit, "baseValueFieldName", "baseValueFieldId"),
                field.firstText(measureUnit, "baseValueFieldId"),
                field.relatedFieldName(measureUnit, "conversionScopeFieldName", "conversionScopeFieldId")
        );
    }

    private record FieldContract(
            String fieldName,
            String ownerFieldId,
            String fieldType,
            String unitCategoryAlias,
            String unitMode,
            String fixedUnitCode,
            String baseUnitCategoryAlias,
            String baseUnitCode,
            String unitFieldName,
            String baseValueFieldName,
            String baseValueFieldId,
            String conversionScopeFieldName
    ) {
    }
}
