package net.ximatai.muyun.spring.dynamic.openapi;

import net.ximatai.muyun.spring.common.option.OptionBinding;
import net.ximatai.muyun.spring.common.option.OptionSelectionMode;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicActionDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicEntityDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicFieldDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicReferenceDescriptor;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DynamicOpenApiSchemaFactory {
    Map<String, DynamicOpenApiDocument.Schema> schemas(DynamicEntityDescriptor entity) {
        Map<String, DynamicOpenApiDocument.Schema> schemas = new LinkedHashMap<>();
        schemas.put(schemaName(entity.entityAlias(), "Values"), valuesSchema(entity));
        schemas.put(schemaName(entity.entityAlias(), "Record"), recordSchema(entity));
        schemas.put("DynamicRecordPayload", recordPayloadSchema(entity));
        schemas.put("DynamicRecordResponse", recordResponseSchema(entity));
        schemas.put("WebQueryRequest", queryRequestSchema("WebQueryRequest", "WebQueryCondition", "WebPageRequest", "WebSort"));
        schemas.put("WebQueryCondition", queryConditionSchema("WebQueryCondition"));
        schemas.put("WebQueryCriteria", queryCriteriaSchema());
        schemas.put("WebPageRequest", pageRequestSchema("WebPageRequest"));
        schemas.put("RecordActionWebRequest", recordActionWebRequestSchema());
        schemas.put("WebSort", sortSchema("WebSort"));
        schemas.put("DynamicSummaryItem", summaryItemSchema());
        schemas.put("DynamicSummaryItemList", summaryItemListSchema());
        schemas.put("SortWebRequest", sortRequestSchema());
        schemas.put("TreeSortWebRequest", treeSortRequestSchema());
        schemas.put("DynamicExchangeTemplateRequest", exchangeTemplateRequestSchema());
        schemas.put("DynamicSelectedExportRequest", selectedExportRequestSchema());
        schemas.put("DynamicImportParseRequest", importParseRequestSchema());
        schemas.put("DynamicImportExecuteMultipartRequest", importExecuteMultipartRequestSchema());
        schemas.put("DynamicImportExecuteRequest", importExecuteRequestSchema());
        schemas.put("DynamicImportMainSheetRequest", importMainSheetRequestSchema());
        schemas.put("DynamicImportChildSheetRequest", importChildSheetRequestSchema());
        schemas.put("DynamicImportParseResult", importParseResultSchema());
        schemas.put("DynamicImportParseSheet", importParseSheetSchema());
        schemas.put("DynamicImportParseField", importParseFieldSchema());
        schemas.put("DynamicImportUploadResult", importUploadResultSchema());
        schemas.put("DynamicWebActionRequest", actionRequestSchema());
        schemas.put("DynamicWebReferenceRequest", referenceRequestSchema());
        schemas.put("DynamicWebDuplicateCheckRequest", duplicateCheckRequestSchema());
        schemas.put("RecordDuplicateCheckResult", duplicateCheckResultSchema());
        schemas.put("RecordDuplicateMatch", duplicateMatchSchema());
        schemas.put("RecordAttachmentCommand", attachmentCommandSchema());
        schemas.put("RecordAttachment", attachmentSchema());
        schemas.put("RecordAttachmentAccess", attachmentAccessSchema());
        schemas.put("RecordAttachmentList", arraySchema("RecordAttachmentList", "RecordAttachment"));
        schemas.put("WebPageResponse", pageResponseSchema("WebPageResponse"));
        schemas.put("DynamicPageResponse", pageResponseSchema("DynamicPageResponse"));
        schemas.put("DynamicWebActionExecutionResponse", actionExecutionResponseSchema());
        schemas.put("DynamicReferenceResolveResponse", referenceResolveResponseSchema());
        schemas.put("DynamicModuleDescriptor", moduleDescriptorSchema());
        schemas.put("DynamicActionDescriptor", actionDescriptorSchema());
        schemas.put("ActionPermissionDescriptor", actionPermissionDescriptorSchema());
        schemas.put("DynamicEntityDescriptor", entityDescriptorSchema());
        schemas.put("DynamicFieldDescriptor", fieldDescriptorSchema());
        schemas.put("DynamicFieldCompanionDescriptor", fieldCompanionDescriptorSchema());
        schemas.put("DynamicFieldQueryDescriptor", fieldQueryDescriptorSchema());
        schemas.put("DynamicFormulaRuleDescriptor", formulaRuleDescriptorSchema());
        schemas.put("DynamicRelationDescriptor", relationDescriptorSchema());
        schemas.put("DynamicReferenceDescriptor", referenceDescriptorSchema());
        schemas.put("DynamicReferenceProjectionDescriptor", referenceProjectionDescriptorSchema());
        schemas.put("DynamicAssociationViewDescriptor", associationViewDescriptorSchema());
        schemas.put("AssociationViewPathStep", associationViewPathStepSchema());
        schemas.put("AssociationViewRootQueryMapping", associationViewRootQueryMappingSchema());
        schemas.put("DynamicAssociationViewDescriptorList", arraySchema("DynamicAssociationViewDescriptorList",
                "DynamicAssociationViewDescriptor"));
        schemas.put("DynamicAssociationRelationOverview", associationRelationOverviewSchema());
        schemas.put("DynamicAssociationRelationItem", associationRelationItemSchema());
        schemas.put("DynamicAssociationViewDiagnosis", associationViewDiagnosisSchema());
        schemas.put("PlatformModuleTaskDefinitionList", arraySchema("PlatformModuleTaskDefinitionList",
                "PlatformModuleTaskDefinition"));
        schemas.put("PlatformModuleTaskDefinition", moduleTaskDefinitionSchema());
        schemas.put("PlatformModuleTaskGuideDefinition", moduleTaskGuideDefinitionSchema());
        schemas.put("PlatformModuleTaskCheckDefinition", moduleTaskCheckDefinitionSchema());
        schemas.put("DynamicViewDescriptor", viewDescriptorSchema());
        schemas.put("DynamicViewFieldDescriptor", viewFieldDescriptorSchema());
        schemas.put("BinaryFile", new DynamicOpenApiDocument.Schema("BinaryFile", "string", "binary",
                List.of(), Map.of(), null));
        schemas.put("DynamicActionDescriptorList", arraySchema("DynamicActionDescriptorList", "DynamicActionDescriptor"));
        schemas.put("WebListResponse", listResponseSchema("WebListResponse"));
        schemas.put("DynamicWebActionAvailabilityList", arraySchema("DynamicWebActionAvailabilityList", "DynamicWebActionAvailabilityResponse"));
        schemas.put("DynamicWebActionAvailabilityResponse", actionAvailabilityResponseSchema());
        schemas.put("DynamicWebActionContext", actionContextSchema());
        schemas.put("DynamicWebActionResultBody", actionResultBodySchema());
        schemas.put("PlatformWebError", platformWebErrorSchema());
        schemas.put("ErrorScope", errorScopeSchema());
        schemas.put("ErrorTarget", errorTargetSchema());
        schemas.put("DynamicActionDialog", actionDialogSchema());
        schemas.put("DynamicActionRefreshStrategy", actionRefreshStrategySchema());
        schemas.put("DynamicReferenceResolveItem", referenceResolveItemSchema());
        schemas.put("DynamicReferenceResolveResult", referenceResolveResultSchema());
        return Map.copyOf(schemas);
    }

    private DynamicOpenApiDocument.Schema recordSchema(DynamicEntityDescriptor entity) {
        Map<String, DynamicOpenApiDocument.Property> properties = recordEnvelopeProperties(entity);
        return new DynamicOpenApiDocument.Schema(schemaName(entity.entityAlias(), "Record"),
                "object", null, List.of(), properties, null);
    }

    private DynamicOpenApiDocument.Schema valuesSchema(DynamicEntityDescriptor entity) {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (DynamicFieldDescriptor field : entity.fields()) {
            properties.put(field.fieldName(), property(field));
            if (field.required()) {
                required.add(field.fieldName());
            }
        }
        return new DynamicOpenApiDocument.Schema(schemaName(entity.entityAlias(), "Values"),
                "object", null, required, properties, null);
    }

    private DynamicOpenApiDocument.Schema recordPayloadSchema(DynamicEntityDescriptor entity) {
        Map<String, DynamicOpenApiDocument.Property> properties = recordEnvelopeProperties(entity);
        properties.put("attachments", arrayProperty("RecordAttachmentCommand"));
        return new DynamicOpenApiDocument.Schema("DynamicRecordPayload", "object", null,
                List.of(), properties, null);
    }

    private DynamicOpenApiDocument.Schema recordResponseSchema(DynamicEntityDescriptor entity) {
        Map<String, DynamicOpenApiDocument.Property> properties = recordEnvelopeProperties(entity);
        properties.put("children", new DynamicOpenApiDocument.Property("object", null, false, true,
                false, null, null, null, null, null, List.of()));
        return new DynamicOpenApiDocument.Schema("DynamicRecordResponse", "object", null,
                List.of(), properties, null);
    }

    private Map<String, DynamicOpenApiDocument.Property> recordEnvelopeProperties(DynamicEntityDescriptor entity) {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("id", new DynamicOpenApiDocument.Property("string", null, false, true,
                false, null, null, null, null, null, List.of()));
        properties.put("version", new DynamicOpenApiDocument.Property("integer", "int32", false, true,
                false, null, null, null, null, null, List.of()));
        properties.put("values", new DynamicOpenApiDocument.Property(schemaName(entity.entityAlias(), "Values"),
                null, false, false, false, null, null, null, null, null, List.of()));
        return properties;
    }

    private DynamicOpenApiDocument.Property property(DynamicFieldDescriptor field) {
        FieldShape shape = fieldShape(field.type(), field.selectionMode());
        OptionBinding optionBinding = field.optionBinding();
        DynamicReferenceDescriptor reference = field.reference();
        return new DynamicOpenApiDocument.Property(
                shape.type(),
                shape.format(),
                field.required(),
                !field.required(),
                OptionSelectionMode.MULTIPLE == field.selectionMode(),
                optionBinding == null ? null : optionBinding.sourceType(),
                optionBinding == null ? null : optionBinding.source(),
                reference == null ? null : reference.targetModuleAlias(),
                reference == null ? null : reference.targetEntityAlias(),
                null,
                field.temporalSemantics().name(),
                field.companions().stream()
                        .map(companion -> companion.fieldName())
                        .toList()
        );
    }

    private FieldShape fieldShape(FieldType type, OptionSelectionMode selectionMode) {
        if (OptionSelectionMode.MULTIPLE == selectionMode) {
            return new FieldShape("array", null);
        }
        return switch (type) {
            case STRING, TEXT -> new FieldShape("string", null);
            case INTEGER -> new FieldShape("integer", "int32");
            case LONG -> new FieldShape("integer", "int64");
            case BOOLEAN -> new FieldShape("boolean", null);
            case DATE -> new FieldShape("string", "date");
            case TIMESTAMP, ZONED_TIMESTAMP -> new FieldShape("string", "date-time");
            case DECIMAL -> new FieldShape("number", "decimal");
            case JSON -> new FieldShape("object", null);
        };
    }

    private DynamicOpenApiDocument.Schema moduleDescriptorSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("moduleAlias", stringProperty(false));
        properties.put("title", stringProperty(false));
        properties.put("mainEntityAlias", stringProperty(false));
        properties.put("actions", arrayProperty("DynamicActionDescriptor"));
        properties.put("entities", arrayProperty("DynamicEntityDescriptor"));
        properties.put("relations", arrayProperty("DynamicRelationDescriptor"));
        properties.put("references", arrayProperty("DynamicReferenceDescriptor"));
        properties.put("associationViews", arrayProperty("DynamicAssociationViewDescriptor"));
        return new DynamicOpenApiDocument.Schema("DynamicModuleDescriptor", "object", null,
                List.of("moduleAlias", "title", "mainEntityAlias"), properties, null);
    }

    private DynamicOpenApiDocument.Schema actionDescriptorSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("code", stringProperty(false));
        properties.put("title", stringProperty(false));
        properties.put("enabled", booleanProperty(false));
        properties.put("actionLevel", stringProperty(false));
        properties.put("category", stringProperty(false));
        properties.put("accessMode", stringProperty(false));
        properties.put("actionAuth", booleanProperty(false));
        properties.put("dataAuth", booleanProperty(false));
        properties.put("authInheritActionCode", stringProperty(true));
        properties.put("availabilityCondition", booleanProperty(false));
        properties.put("unavailableMessage", stringProperty(true));
        properties.put("executorType", stringProperty(false));
        properties.put("executorKey", stringProperty(true));
        properties.put("permission", objectProperty("ActionPermissionDescriptor"));
        return new DynamicOpenApiDocument.Schema("DynamicActionDescriptor", "object", null,
                List.of("code", "title", "enabled", "actionLevel", "executorType"), properties, null);
    }

    private DynamicOpenApiDocument.Schema actionPermissionDescriptorSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("permissionCode", stringProperty(false));
        properties.put("actionAuth", booleanProperty(false));
        properties.put("dataAuth", booleanProperty(false));
        properties.put("inheritActionCode", stringProperty(true));
        properties.put("inheritPermissionCode", stringProperty(true));
        return new DynamicOpenApiDocument.Schema("ActionPermissionDescriptor", "object", null,
                List.of("permissionCode", "actionAuth", "dataAuth"), properties, null);
    }

    private DynamicOpenApiDocument.Schema entityDescriptorSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("entityAlias", stringProperty(false));
        properties.put("title", stringProperty(false));
        properties.put("capabilities", arrayProperty("string"));
        properties.put("fields", arrayProperty("DynamicFieldDescriptor"));
        properties.put("formulaRules", arrayProperty("DynamicFormulaRuleDescriptor"));
        properties.put("actions", arrayProperty("DynamicActionDescriptor"));
        properties.put("views", arrayProperty("DynamicViewDescriptor"));
        properties.put("associationViews", arrayProperty("DynamicAssociationViewDescriptor"));
        return new DynamicOpenApiDocument.Schema("DynamicEntityDescriptor", "object", null,
                List.of("entityAlias", "title"), properties, null);
    }

    private DynamicOpenApiDocument.Schema fieldDescriptorSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("fieldName", stringProperty(false));
        properties.put("type", stringProperty(false));
        properties.put("temporalSemantics", stringProperty(false));
        properties.put("title", stringProperty(false));
        properties.put("required", booleanProperty(false));
        properties.put("columnSpan", integerProperty(false));
        properties.put("unique", booleanProperty(false));
        properties.put("indexed", booleanProperty(false));
        properties.put("sortable", booleanProperty(false));
        properties.put("titleField", booleanProperty(false));
        properties.put("length", integerProperty(true));
        properties.put("precision", integerProperty(true));
        properties.put("scale", integerProperty(true));
        properties.put("optionBinding", objectProperty("object"));
        properties.put("selectionMode", stringProperty(true));
        properties.put("reference", objectProperty("DynamicReferenceDescriptor"));
        properties.put("companions", arrayProperty("DynamicFieldCompanionDescriptor"));
        properties.put("query", objectProperty("DynamicFieldQueryDescriptor"));
        properties.put("defaultValue", stringProperty(true));
        properties.put("validationRegex", stringProperty(true));
        properties.put("copyable", booleanProperty(false));
        properties.put("writeProtected", booleanProperty(false));
        return new DynamicOpenApiDocument.Schema("DynamicFieldDescriptor", "object", null,
                List.of("fieldName", "type", "title"), properties, null);
    }

    private DynamicOpenApiDocument.Schema fieldCompanionDescriptorSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("fieldName", stringProperty(false));
        properties.put("kind", stringProperty(false));
        properties.put("role", stringProperty(false));
        properties.put("requiredWhenOwnerPresent", booleanProperty(false));
        properties.put("requiredWhenOwnerUpdated", booleanProperty(false));
        return new DynamicOpenApiDocument.Schema("DynamicFieldCompanionDescriptor", "object", null,
                List.of("fieldName", "kind", "role"), properties, null);
    }

    private DynamicOpenApiDocument.Schema fieldQueryDescriptorSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("queryable", booleanProperty(false));
        properties.put("defaultOperator", stringProperty(true));
        properties.put("operators", arrayProperty("string"));
        return new DynamicOpenApiDocument.Schema("DynamicFieldQueryDescriptor", "object", null,
                List.of("queryable"), properties, null);
    }

    private DynamicOpenApiDocument.Schema formulaRuleDescriptorSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("code", stringProperty(false));
        properties.put("expression", stringProperty(false));
        properties.put("kind", stringProperty(false));
        properties.put("phase", stringProperty(false));
        properties.put("targetField", stringProperty(true));
        properties.put("severity", stringProperty(false));
        properties.put("messageTemplate", stringProperty(true));
        properties.put("stopOnError", booleanProperty(false));
        properties.put("enabled", booleanProperty(false));
        properties.put("sortOrder", integerProperty(false));
        return new DynamicOpenApiDocument.Schema("DynamicFormulaRuleDescriptor", "object", null,
                List.of("code", "expression", "kind", "phase"), properties, null);
    }

    private DynamicOpenApiDocument.Schema relationDescriptorSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("code", stringProperty(false));
        properties.put("parentEntityAlias", stringProperty(false));
        properties.put("childEntityAlias", stringProperty(false));
        properties.put("childForeignKeyField", stringProperty(false));
        properties.put("autoPopulate", booleanProperty(false));
        properties.put("cascadeOnParentUnavailable", booleanProperty(false));
        return new DynamicOpenApiDocument.Schema("DynamicRelationDescriptor", "object", null,
                List.of("code", "parentEntityAlias", "childEntityAlias"), properties, null);
    }

    private DynamicOpenApiDocument.Schema referenceDescriptorSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("sourceEntityAlias", stringProperty(false));
        properties.put("sourceField", stringProperty(false));
        properties.put("targetModuleAlias", stringProperty(false));
        properties.put("targetEntityAlias", stringProperty(false));
        properties.put("cardinality", stringProperty(false));
        properties.put("projections", arrayProperty("DynamicReferenceProjectionDescriptor"));
        return new DynamicOpenApiDocument.Schema("DynamicReferenceDescriptor", "object", null,
                List.of("sourceEntityAlias", "sourceField", "targetModuleAlias", "targetEntityAlias"),
                properties, null);
    }

    private DynamicOpenApiDocument.Schema referenceProjectionDescriptorSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("targetField", stringProperty(false));
        properties.put("outputField", stringProperty(false));
        return new DynamicOpenApiDocument.Schema("DynamicReferenceProjectionDescriptor", "object", null,
                List.of("targetField", "outputField"), properties, null);
    }

    private DynamicOpenApiDocument.Schema associationViewDescriptorSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("code", stringProperty(false));
        properties.put("sourceEntityAlias", stringProperty(false));
        properties.put("targetModuleAlias", stringProperty(false));
        properties.put("targetEntityAlias", stringProperty(false));
        properties.put("displayMode", stringProperty(false));
        properties.put("relationCode", stringProperty(true));
        properties.put("referenceField", stringProperty(true));
        properties.put("viewType", stringProperty(true));
        properties.put("queryable", booleanProperty(false));
        properties.put("path", arrayProperty("AssociationViewPathStep"));
        properties.put("rootQueryMapping", objectProperty("AssociationViewRootQueryMapping"));
        properties.put("targetUiConfigId", stringProperty(true));
        properties.put("targetQueryTemplateId", stringProperty(true));
        return new DynamicOpenApiDocument.Schema("DynamicAssociationViewDescriptor", "object", null,
                List.of("code", "sourceEntityAlias", "targetModuleAlias", "targetEntityAlias"), properties, null);
    }

    private DynamicOpenApiDocument.Schema associationViewPathStepSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("type", stringProperty(false));
        properties.put("code", stringProperty(false));
        properties.put("sourceEntityAlias", stringProperty(false));
        properties.put("targetModuleAlias", stringProperty(false));
        properties.put("targetEntityAlias", stringProperty(false));
        return new DynamicOpenApiDocument.Schema("AssociationViewPathStep", "object", null,
                List.of("type", "code", "sourceEntityAlias", "targetModuleAlias", "targetEntityAlias"),
                properties, null);
    }

    private DynamicOpenApiDocument.Schema associationViewRootQueryMappingSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("groupOperator", stringProperty(true));
        properties.put("children", arrayProperty("AssociationViewRootQueryMapping"));
        properties.put("targetField", stringProperty(true));
        properties.put("operator", stringProperty(true));
        properties.put("sourceType", stringProperty(true));
        properties.put("sourceField", stringProperty(true));
        properties.put("systemVariable", stringProperty(true));
        properties.put("constantValue", objectProperty("object"));
        return new DynamicOpenApiDocument.Schema("AssociationViewRootQueryMapping", "object", null,
                List.of(), properties, null);
    }

    private DynamicOpenApiDocument.Schema associationRelationOverviewSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("moduleAlias", stringProperty(false));
        properties.put("upstream", arrayProperty("DynamicAssociationRelationItem"));
        properties.put("downstream", arrayProperty("DynamicAssociationRelationItem"));
        return new DynamicOpenApiDocument.Schema("DynamicAssociationRelationOverview", "object", null,
                List.of("moduleAlias"), properties, null);
    }

    private DynamicOpenApiDocument.Schema associationRelationItemSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("type", stringProperty(false));
        properties.put("code", stringProperty(false));
        properties.put("sourceModuleAlias", stringProperty(false));
        properties.put("sourceEntityAlias", stringProperty(false));
        properties.put("targetModuleAlias", stringProperty(false));
        properties.put("targetEntityAlias", stringProperty(false));
        properties.put("associationViewCode", stringProperty(true));
        return new DynamicOpenApiDocument.Schema("DynamicAssociationRelationItem", "object", null,
                List.of("type", "code", "sourceModuleAlias", "sourceEntityAlias", "targetModuleAlias",
                        "targetEntityAlias"), properties, null);
    }

    private DynamicOpenApiDocument.Schema associationViewDiagnosisSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("view", objectProperty("DynamicAssociationViewDescriptor"));
        properties.put("associationCriteria", objectProperty("WebQueryCriteria"));
        properties.put("requestCriteria", objectProperty("WebQueryCriteria"));
        properties.put("targetCriteria", objectProperty("WebQueryCriteria"));
        properties.put("targetCount", integerProperty(false));
        properties.put("status", stringProperty(false));
        properties.put("message", stringProperty(true));
        return new DynamicOpenApiDocument.Schema("DynamicAssociationViewDiagnosis", "object", null,
                List.of("view", "targetCount", "status"), properties, null);
    }

    private DynamicOpenApiDocument.Schema moduleTaskDefinitionSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("moduleAlias", stringProperty(false));
        properties.put("taskCode", stringProperty(false));
        properties.put("title", stringProperty(true));
        properties.put("taskType", stringProperty(false));
        properties.put("originType", stringProperty(false));
        properties.put("originId", stringProperty(true));
        properties.put("managed", booleanProperty(false));
        properties.put("system", booleanProperty(false));
        properties.put("enabled", booleanProperty(false));
        properties.put("sortOrder", integerProperty(false));
        properties.put("diagnosticPath", stringProperty(true));
        properties.put("guides", arrayProperty("PlatformModuleTaskGuideDefinition"));
        properties.put("checks", arrayProperty("PlatformModuleTaskCheckDefinition"));
        return new DynamicOpenApiDocument.Schema("PlatformModuleTaskDefinition", "object", null,
                List.of("moduleAlias", "taskCode", "taskType", "originType", "managed", "system", "enabled"),
                properties, null);
    }

    private DynamicOpenApiDocument.Schema moduleTaskGuideDefinitionSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("taskCode", stringProperty(true));
        properties.put("guideType", stringProperty(false));
        properties.put("actionCode", stringProperty(true));
        properties.put("path", stringProperty(true));
        properties.put("moduleAlias", stringProperty(true));
        properties.put("viewCode", stringProperty(true));
        properties.put("fieldName", stringProperty(true));
        properties.put("title", stringProperty(true));
        return new DynamicOpenApiDocument.Schema("PlatformModuleTaskGuideDefinition", "object", null,
                List.of("guideType"), properties, null);
    }

    private DynamicOpenApiDocument.Schema moduleTaskCheckDefinitionSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("taskCode", stringProperty(true));
        properties.put("checkType", stringProperty(false));
        properties.put("associationViewCode", stringProperty(true));
        properties.put("queryTemplateId", stringProperty(true));
        properties.put("externalRecordIdKey", stringProperty(true));
        properties.put("targetModuleAlias", stringProperty(true));
        properties.put("generationRuleId", stringProperty(true));
        properties.put("expectedCount", integerProperty(false));
        properties.put("diagnosticPath", stringProperty(true));
        return new DynamicOpenApiDocument.Schema("PlatformModuleTaskCheckDefinition", "object", null,
                List.of("checkType", "expectedCount"), properties, null);
    }

    private DynamicOpenApiDocument.Schema viewDescriptorSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("viewType", stringProperty(false));
        properties.put("title", stringProperty(false));
        properties.put("fields", arrayProperty("DynamicViewFieldDescriptor"));
        return new DynamicOpenApiDocument.Schema("DynamicViewDescriptor", "object", null,
                List.of("viewType", "title"), properties, null);
    }

    private DynamicOpenApiDocument.Schema viewFieldDescriptorSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("fieldName", stringProperty(false));
        properties.put("title", stringProperty(false));
        properties.put("temporalSemantics", stringProperty(false));
        properties.put("visible", booleanProperty(false));
        properties.put("controlType", stringProperty(false));
        properties.put("companions", arrayProperty("DynamicFieldCompanionDescriptor"));
        properties.put("readOnly", booleanProperty(false));
        properties.put("required", booleanProperty(false));
        return new DynamicOpenApiDocument.Schema("DynamicViewFieldDescriptor", "object", null,
                List.of("fieldName", "title", "visible", "controlType"), properties, null);
    }

    private DynamicOpenApiDocument.Schema queryRequestSchema(String name,
                                                             String conditionSchema,
                                                             String pageSchema,
                                                             String sortSchema) {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("conditions", arrayProperty(conditionSchema));
        properties.put("criteria", objectProperty("WebQueryCriteria"));
        properties.put("queryForm", objectProperty("object"));
        properties.put("page", objectProperty(pageSchema));
        properties.put("sorts", arrayProperty(sortSchema));
        properties.put("uiConfigId", stringProperty(true));
        properties.put("queryTemplateId", stringProperty(true));
        properties.put("externalQueryValues", objectProperty("object"));
        properties.put("navigationSession", booleanProperty(true));
        properties.put("quickSearch", stringProperty(true));
        properties.put("quickSearchFields", arrayProperty("string"));
        properties.put("navigationQueryKey", stringProperty(true));
        return new DynamicOpenApiDocument.Schema(name, "object", null, List.of(), properties, null);
    }

    private DynamicOpenApiDocument.Schema queryConditionSchema(String name) {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("fieldName", stringProperty(false));
        properties.put("operator", stringProperty(true));
        properties.put("values", arrayProperty("object"));
        properties.put("timeZone", stringProperty(true));
        return new DynamicOpenApiDocument.Schema(name, "object", null, List.of(), properties, null);
    }

    private DynamicOpenApiDocument.Schema queryCriteriaSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("operator", stringProperty(true));
        properties.put("conditions", arrayProperty("WebQueryCondition"));
        properties.put("groups", arrayProperty("WebQueryCriteria"));
        return new DynamicOpenApiDocument.Schema("WebQueryCriteria", "object", null, List.of(), properties, null);
    }

    private DynamicOpenApiDocument.Schema pageRequestSchema(String name) {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("pageNum", new DynamicOpenApiDocument.Property("integer", "int32", false, false,
                false, null, null, null, null, null, List.of()));
        properties.put("pageSize", new DynamicOpenApiDocument.Property("integer", "int32", false, false,
                false, null, null, null, null, null, List.of()));
        return new DynamicOpenApiDocument.Schema(name, "object", null, List.of(), properties, null);
    }

    private DynamicOpenApiDocument.Schema recordActionWebRequestSchema() {
        return new DynamicOpenApiDocument.Schema("RecordActionWebRequest", "object", null, List.of("version"), Map.of(
                "version", new DynamicOpenApiDocument.Property("integer", "int32", true, false,
                        false, null, null, null, null, null, List.of())
        ), null);
    }

    private DynamicOpenApiDocument.Schema sortSchema(String name) {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("field", stringProperty(false));
        properties.put("desc", new DynamicOpenApiDocument.Property("boolean", null, false, false,
                false, null, null, null, null, null, List.of()));
        return new DynamicOpenApiDocument.Schema(name, "object", null, List.of(), properties, null);
    }

    private DynamicOpenApiDocument.Schema sortRequestSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("previousId", stringProperty(true));
        properties.put("nextId", stringProperty(true));
        return new DynamicOpenApiDocument.Schema("SortWebRequest", "object", null, List.of(), properties, null);
    }

    private DynamicOpenApiDocument.Schema treeSortRequestSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("previousId", stringProperty(true));
        properties.put("nextId", stringProperty(true));
        properties.put("parentId", stringProperty(true));
        return new DynamicOpenApiDocument.Schema("TreeSortWebRequest", "object", null, List.of(), properties, null);
    }

    private DynamicOpenApiDocument.Schema importParseRequestSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("file", new DynamicOpenApiDocument.Property("string", "binary", true, false,
                false, null, null, null, null, null, List.of()));
        return new DynamicOpenApiDocument.Schema("DynamicImportParseRequest", "object", null,
                List.of("file"), properties, null);
    }

    private DynamicOpenApiDocument.Schema exchangeTemplateRequestSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("disabledReferenceDropdownFields", arrayProperty("string"));
        properties.put("referenceDropdownLimit", integerProperty(true));
        return new DynamicOpenApiDocument.Schema("DynamicExchangeTemplateRequest", "object", null,
                List.of(), properties, null);
    }

    private DynamicOpenApiDocument.Schema selectedExportRequestSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("ids", arrayProperty("string"));
        properties.put("query", objectProperty("WebQueryRequest"));
        return new DynamicOpenApiDocument.Schema("DynamicSelectedExportRequest", "object", null,
                List.of("ids"), properties, null);
    }

    private DynamicOpenApiDocument.Schema importExecuteMultipartRequestSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("command", objectProperty("DynamicImportExecuteRequest"));
        properties.put("file", new DynamicOpenApiDocument.Property("string", "binary", true, false,
                false, null, null, null, null, null, List.of()));
        return new DynamicOpenApiDocument.Schema("DynamicImportExecuteMultipartRequest", "object", null,
                List.of("command", "file"), properties, null);
    }

    private DynamicOpenApiDocument.Schema importExecuteRequestSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("mainSheet", objectProperty("DynamicImportMainSheetRequest"));
        properties.put("childSheets", arrayProperty("DynamicImportChildSheetRequest"));
        return new DynamicOpenApiDocument.Schema("DynamicImportExecuteRequest", "object", null,
                List.of("mainSheet"), properties, null);
    }

    private DynamicOpenApiDocument.Schema importMainSheetRequestSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("matchFieldName", stringProperty(false));
        properties.put("duplicateStrategy", stringProperty(true));
        return new DynamicOpenApiDocument.Schema("DynamicImportMainSheetRequest", "object", null,
                List.of("matchFieldName"), properties, null);
    }

    private DynamicOpenApiDocument.Schema importChildSheetRequestSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("entityAlias", stringProperty(false));
        properties.put("matchFieldName", stringProperty(false));
        properties.put("duplicateStrategy", stringProperty(true));
        return new DynamicOpenApiDocument.Schema("DynamicImportChildSheetRequest", "object", null,
                List.of("entityAlias", "matchFieldName"), properties, null);
    }

    private DynamicOpenApiDocument.Schema importParseResultSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("moduleAlias", stringProperty(false));
        properties.put("mainEntityAlias", stringProperty(false));
        properties.put("mainSheetName", stringProperty(false));
        properties.put("sheets", arrayProperty("DynamicImportParseSheet"));
        return new DynamicOpenApiDocument.Schema("DynamicImportParseResult", "object", null,
                List.of("moduleAlias", "mainEntityAlias", "sheets"), properties, null);
    }

    private DynamicOpenApiDocument.Schema importParseSheetSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("sheetKey", stringProperty(false));
        properties.put("sheetName", stringProperty(false));
        properties.put("entityAlias", stringProperty(false));
        properties.put("main", booleanProperty(false));
        properties.put("rowCount", integerProperty(false));
        properties.put("fields", arrayProperty("DynamicImportParseField"));
        return new DynamicOpenApiDocument.Schema("DynamicImportParseSheet", "object", null,
                List.of("sheetKey", "sheetName", "entityAlias", "main"), properties, null);
    }

    private DynamicOpenApiDocument.Schema importParseFieldSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("fieldName", stringProperty(false));
        properties.put("title", stringProperty(false));
        properties.put("relateId", booleanProperty(false));
        properties.put("matchKeyCandidate", booleanProperty(false));
        return new DynamicOpenApiDocument.Schema("DynamicImportParseField", "object", null,
                List.of("fieldName", "title"), properties, null);
    }

    private DynamicOpenApiDocument.Schema importUploadResultSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("created", integerProperty(false));
        properties.put("updated", integerProperty(false));
        properties.put("skipped", integerProperty(false));
        properties.put("errorCount", integerProperty(false));
        properties.put("partialSuccess", booleanProperty(false));
        properties.put("message", stringProperty(true));
        properties.put("errorFileName", stringProperty(true));
        properties.put("errorFileToken", stringProperty(true));
        return new DynamicOpenApiDocument.Schema("DynamicImportUploadResult", "object", null,
                List.of("created", "updated", "skipped", "errorCount", "partialSuccess"),
                properties, null);
    }

    private DynamicOpenApiDocument.Schema actionRequestSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("recordId", stringProperty(true));
        properties.put("record", objectProperty("DynamicRecordPayload"));
        properties.put("ids", arrayProperty("string"));
        properties.put("orderedIds", arrayProperty("string"));
        properties.put("beforeId", stringProperty(true));
        properties.put("afterId", stringProperty(true));
        properties.put("parentId", stringProperty(true));
        properties.put("conditions", arrayProperty("WebQueryCondition"));
        properties.put("page", objectProperty("WebPageRequest"));
        properties.put("sorts", arrayProperty("WebSort"));
        properties.put("fieldNames", arrayProperty("string"));
        properties.put("payload", objectProperty("object"));
        return new DynamicOpenApiDocument.Schema("DynamicWebActionRequest", "object", null, List.of(), properties, null);
    }

    private DynamicOpenApiDocument.Schema referenceRequestSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("mode", stringProperty(true));
        properties.put("matchMode", stringProperty(true));
        properties.put("fuzzy", stringProperty(true));
        properties.put("values", arrayProperty("object"));
        properties.put("conditions", arrayProperty("WebQueryCondition"));
        properties.put("criteria", objectProperty("WebQueryCriteria"));
        properties.put("page", objectProperty("WebPageRequest"));
        properties.put("includeProjections", new DynamicOpenApiDocument.Property("boolean", null, false, true,
                false, null, null, null, null, null, List.of()));
        properties.put("formValues", objectProperty("object"));
        properties.put("sourceUiConfigId", stringProperty(true));
        properties.put("uiConfigId", stringProperty(true));
        properties.put("queryTemplateId", stringProperty(true));
        properties.put("externalQueryValues", objectProperty("object"));
        return new DynamicOpenApiDocument.Schema("DynamicWebReferenceRequest", "object", null, List.of(), properties, null);
    }

    private DynamicOpenApiDocument.Schema duplicateCheckRequestSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("recordId", stringProperty(true));
        properties.put("values", objectProperty("object"));
        return new DynamicOpenApiDocument.Schema("DynamicWebDuplicateCheckRequest", "object", null,
                List.of(), properties, null);
    }

    private DynamicOpenApiDocument.Schema duplicateCheckResultSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("ruleId", stringProperty(true));
        properties.put("actionCode", stringProperty(false));
        properties.put("fieldNames", arrayProperty("string"));
        properties.put("duplicated", booleanProperty(false));
        properties.put("matches", arrayProperty("RecordDuplicateMatch"));
        return new DynamicOpenApiDocument.Schema("RecordDuplicateCheckResult", "object", null,
                List.of("actionCode", "fieldNames", "duplicated", "matches"), properties, null);
    }

    private DynamicOpenApiDocument.Schema duplicateMatchSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("recordId", stringProperty(false));
        properties.put("version", integerProperty(true));
        properties.put("values", objectProperty("object"));
        return new DynamicOpenApiDocument.Schema("RecordDuplicateMatch", "object", null,
                List.of("recordId", "values"), properties, null);
    }

    private DynamicOpenApiDocument.Schema attachmentCommandSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("id", stringProperty(true));
        properties.put("fileId", stringProperty(false));
        properties.put("displayName", stringProperty(true));
        properties.put("sort", integerProperty(true));
        properties.put("remark", stringProperty(true));
        return new DynamicOpenApiDocument.Schema("RecordAttachmentCommand", "object", null,
                List.of("fileId"), properties, null);
    }

    private DynamicOpenApiDocument.Schema attachmentSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("id", stringProperty(false));
        properties.put("version", integerProperty(true));
        properties.put("moduleAlias", stringProperty(false));
        properties.put("recordId", stringProperty(false));
        properties.put("fileId", stringProperty(false));
        properties.put("displayName", stringProperty(true));
        properties.put("sort", integerProperty(true));
        properties.put("remark", stringProperty(true));
        return new DynamicOpenApiDocument.Schema("RecordAttachment", "object", null,
                List.of("id", "moduleAlias", "recordId", "fileId"), properties, null);
    }

    private DynamicOpenApiDocument.Schema attachmentAccessSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("mode", stringProperty(false));
        properties.put("fileId", stringProperty(true));
        properties.put("accessToken", stringProperty(true));
        properties.put("url", stringProperty(true));
        properties.put("expiresAt", new DynamicOpenApiDocument.Property("string", "date-time", false, true,
                false, null, null, null, null, null, List.of()));
        properties.put("metadata", objectProperty("object"));
        return new DynamicOpenApiDocument.Schema("RecordAttachmentAccess", "object", null,
                List.of("mode"), properties, null);
    }

    private DynamicOpenApiDocument.Schema summaryItemSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("detailId", stringProperty(true));
        properties.put("calcType", stringProperty(true));
        properties.put("label", stringProperty(true));
        properties.put("precision", integerProperty(true));
        properties.put("formatter", stringProperty(true));
        properties.put("value", objectProperty("object"));
        return new DynamicOpenApiDocument.Schema("DynamicSummaryItem", "object", null, List.of(), properties, null);
    }

    private DynamicOpenApiDocument.Schema summaryItemListSchema() {
        return new DynamicOpenApiDocument.Schema("DynamicSummaryItemList", "array", null, List.of(), Map.of(),
                new DynamicOpenApiDocument.Property("DynamicSummaryItem", null, false, false,
                        false, null, null, null, null, null, List.of()));
    }

    private DynamicOpenApiDocument.Schema pageResponseSchema(String name) {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("records", arrayProperty("DynamicRecordResponse"));
        properties.put("total", new DynamicOpenApiDocument.Property("integer", "int64", false, false,
                false, null, null, null, null, null, List.of()));
        properties.put("pageNum", new DynamicOpenApiDocument.Property("integer", "int32", false, false,
                false, null, null, null, null, null, List.of()));
        properties.put("pageSize", new DynamicOpenApiDocument.Property("integer", "int32", false, false,
                false, null, null, null, null, null, List.of()));
        properties.put("pages", new DynamicOpenApiDocument.Property("integer", "int64", false, false,
                false, null, null, null, null, null, List.of()));
        properties.put("totalKnown", new DynamicOpenApiDocument.Property("boolean", null, false, false,
                false, null, null, null, null, null, List.of()));
        return new DynamicOpenApiDocument.Schema(name, "object", null, List.of(), properties, null);
    }

    private DynamicOpenApiDocument.Schema listResponseSchema(String name) {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("records", arrayProperty("DynamicRecordResponse"));
        return new DynamicOpenApiDocument.Schema(name, "object", null, List.of(), properties, null);
    }

    private DynamicOpenApiDocument.Schema actionExecutionResponseSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("context", objectProperty("DynamicWebActionContext"));
        properties.put("body", objectProperty("DynamicWebActionResultBody"));
        return new DynamicOpenApiDocument.Schema("DynamicWebActionExecutionResponse", "object", null,
                List.of(), properties, null);
    }

    private DynamicOpenApiDocument.Schema actionContextSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("moduleAlias", stringProperty(false));
        properties.put("actionCode", stringProperty(false));
        properties.put("actionLevel", stringProperty(false));
        properties.put("executorType", stringProperty(false));
        properties.put("recordId", stringProperty(true));
        properties.put("traceId", stringProperty(true));
        return new DynamicOpenApiDocument.Schema("DynamicWebActionContext", "object", null,
                List.of("moduleAlias", "actionCode", "actionLevel", "executorType"), properties, null);
    }

    private DynamicOpenApiDocument.Schema actionResultBodySchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("type", stringProperty(false));
        properties.put("value", objectProperty("object"));
        properties.put("message", stringProperty(true));
        properties.put("refresh", new DynamicOpenApiDocument.Property("boolean", null, false, false,
                false, null, null, null, null, null, List.of()));
        properties.put("redirectTo", stringProperty(true));
        properties.put("refreshStrategy", objectProperty("DynamicActionRefreshStrategy"));
        return new DynamicOpenApiDocument.Schema("DynamicWebActionResultBody", "object", null,
                List.of("type", "refresh"), properties, null, actionResultValueShapeByType());
    }

    private DynamicOpenApiDocument.Schema actionDialogSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("dialogKey", stringProperty(false));
        properties.put("title", stringProperty(true));
        properties.put("actionCode", stringProperty(true));
        properties.put("submitActionCode", stringProperty(true));
        properties.put("submitPath", stringProperty(true));
        properties.put("recordId", stringProperty(true));
        properties.put("refreshOnSuccess", new DynamicOpenApiDocument.Property("boolean", null, false, false,
                false, null, null, null, null, null, List.of()));
        properties.put("redirectTo", stringProperty(true));
        properties.put("refreshStrategy", objectProperty("DynamicActionRefreshStrategy"));
        return new DynamicOpenApiDocument.Schema("DynamicActionDialog", "object", null,
                List.of("dialogKey"), properties, null);
    }

    private DynamicOpenApiDocument.Schema actionRefreshStrategySchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("list", new DynamicOpenApiDocument.Property("boolean", null, false, false,
                false, null, null, null, null, null, List.of()));
        properties.put("detail", new DynamicOpenApiDocument.Property("boolean", null, false, false,
                false, null, null, null, null, null, List.of()));
        properties.put("redirectToDetail", new DynamicOpenApiDocument.Property("boolean", null, false, false,
                false, null, null, null, null, null, List.of()));
        properties.put("redirectRecordId", stringProperty(true));
        properties.put("redirectModuleAlias", stringProperty(true));
        return new DynamicOpenApiDocument.Schema("DynamicActionRefreshStrategy", "object", null,
                List.of("list", "detail", "redirectToDetail"), properties, null);
    }

    private Map<String, String> actionResultValueShapeByType() {
        return Map.of(
                "VALUE", "scalar",
                "RECORD_ID", "string",
                "RECORD", "DynamicRecordResponse",
                "LIST", "array",
                "PAGE", "DynamicPageResponse",
                "COUNT", "integer",
                "OBJECT", "object",
                "DIALOG", "DynamicActionDialog",
                "NONE", "null"
        );
    }

    private DynamicOpenApiDocument.Schema actionAvailabilityResponseSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("action", objectProperty("DynamicActionDescriptor"));
        properties.put("available", new DynamicOpenApiDocument.Property("boolean", null, false, false,
                false, null, null, null, null, null, List.of()));
        properties.put("message", stringProperty(true));
        return new DynamicOpenApiDocument.Schema("DynamicWebActionAvailabilityResponse", "object", null,
                List.of("action", "available"), properties, null);
    }

    private DynamicOpenApiDocument.Schema referenceResolveResponseSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("status", stringProperty(false));
        properties.put("mode", stringProperty(true));
        properties.put("options", arrayProperty("DynamicReferenceResolveItem"));
        properties.put("results", arrayProperty("DynamicReferenceResolveResult"));
        properties.put("offset", new DynamicOpenApiDocument.Property("integer", "int32", false, false,
                false, null, null, null, null, null, List.of()));
        properties.put("limit", new DynamicOpenApiDocument.Property("integer", "int32", false, false,
                false, null, null, null, null, null, List.of()));
        properties.put("total", new DynamicOpenApiDocument.Property("integer", "int64", false, false,
                false, null, null, null, null, null, List.of()));
        return new DynamicOpenApiDocument.Schema("DynamicReferenceResolveResponse", "object", null,
                List.of(), properties, null);
    }

    private DynamicOpenApiDocument.Schema referenceResolveItemSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("id", stringProperty(false));
        properties.put("title", stringProperty(false));
        properties.put("matchedBy", stringProperty(true));
        properties.put("projections", objectProperty("object"));
        properties.put("affectPatch", objectProperty("object"));
        return new DynamicOpenApiDocument.Schema("DynamicReferenceResolveItem", "object", null,
                List.of("id", "title"), properties, null);
    }

    private DynamicOpenApiDocument.Schema referenceResolveResultSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("input", objectProperty("object"));
        properties.put("status", stringProperty(false));
        properties.put("matchedBy", stringProperty(true));
        properties.put("item", objectProperty("DynamicReferenceResolveItem"));
        properties.put("candidates", arrayProperty("DynamicReferenceResolveItem"));
        return new DynamicOpenApiDocument.Schema("DynamicReferenceResolveResult", "object", null,
                List.of("status"), properties, null);
    }

    private DynamicOpenApiDocument.Schema arraySchema(String name, String itemName) {
        return new DynamicOpenApiDocument.Schema(name, "array", null, List.of(), Map.of(),
                new DynamicOpenApiDocument.Property(itemName, null, false, false,
                        false, null, null, null, null, null, List.of()));
    }

    private DynamicOpenApiDocument.Property stringProperty(boolean nullable) {
        return new DynamicOpenApiDocument.Property("string", null, false, nullable,
                false, null, null, null, null, null, List.of());
    }

    private DynamicOpenApiDocument.Property booleanProperty(boolean nullable) {
        return new DynamicOpenApiDocument.Property("boolean", null, false, nullable,
                false, null, null, null, null, null, List.of());
    }

    private DynamicOpenApiDocument.Property integerProperty(boolean nullable) {
        return new DynamicOpenApiDocument.Property("integer", "int32", false, nullable,
                false, null, null, null, null, null, List.of());
    }

    private DynamicOpenApiDocument.Property objectProperty(String schemaName) {
        return new DynamicOpenApiDocument.Property(schemaName, null, false, true,
                false, null, null, null, null, null, List.of());
    }

    private DynamicOpenApiDocument.Property arrayProperty(String itemName) {
        return new DynamicOpenApiDocument.Property("array", null, false, false,
                true, null, null, null, null, itemName, List.of());
    }

    private DynamicOpenApiDocument.Schema platformWebErrorSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("traceId", new DynamicOpenApiDocument.Property("string", null, true, false,
                false, null, null, null, null, null, List.of()));
        properties.put("code", new DynamicOpenApiDocument.Property("string", null, true, false,
                false, null, null, null, null, null, List.of()));
        properties.put("status", new DynamicOpenApiDocument.Property("integer", "int32", true, false,
                false, null, null, null, null, null, List.of()));
        properties.put("message", new DynamicOpenApiDocument.Property("string", null, true, false,
                false, null, null, null, null, null, List.of()));
        properties.put("scope", objectProperty("ErrorScope"));
        properties.put("targets", arrayProperty("ErrorTarget"));
        properties.put("details", objectProperty("object"));
        return new DynamicOpenApiDocument.Schema("PlatformWebError", "object", null,
                List.of("traceId", "code", "status", "message", "targets", "details"),
                properties, null);
    }

    private DynamicOpenApiDocument.Schema errorScopeSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("moduleAlias", stringProperty(true));
        properties.put("entityAlias", stringProperty(true));
        properties.put("actionCode", stringProperty(true));
        return new DynamicOpenApiDocument.Schema("ErrorScope", "object", null, List.of(), properties, null);
    }

    private DynamicOpenApiDocument.Schema errorTargetSchema() {
        Map<String, DynamicOpenApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("kind", stringProperty(true));
        properties.put("moduleAlias", stringProperty(true));
        properties.put("entityAlias", stringProperty(true));
        properties.put("relationAlias", stringProperty(true));
        properties.put("fieldName", stringProperty(true));
        properties.put("rowIndex", integerProperty(true));
        properties.put("recordId", stringProperty(true));
        properties.put("actionCode", stringProperty(true));
        properties.put("attachmentId", stringProperty(true));
        return new DynamicOpenApiDocument.Schema("ErrorTarget", "object", null, List.of(),
                properties, null);
    }


    private String schemaName(String entityAlias, String suffix) {
        return upperName(entityAlias) + suffix;
    }

    private String upperName(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String normalized = lowerName(value);
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }

    private String lowerName(String value) {
        StringBuilder result = new StringBuilder();
        boolean upperNext = false;
        for (char c : value.toCharArray()) {
            if (c == '.' || c == '_' || c == '-') {
                upperNext = result.length() > 0;
                continue;
            }
            result.append(upperNext ? Character.toUpperCase(c) : c);
            upperNext = false;
        }
        return result.toString();
    }

    private record FieldShape(String type, String format) {
    }
}
