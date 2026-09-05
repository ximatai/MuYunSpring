package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.openapi.PlatformApiDocument;
import net.ximatai.muyun.spring.common.option.OptionBinding;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import net.ximatai.muyun.spring.common.web.PlatformWebWireContract;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Compiles static entity definitions into the same schema facts consumed by the OpenAPI projector. */
final class StaticModuleOpenApiSchemaFactory {
    Map<String, PlatformApiDocument.Schema> schemas(StaticModuleDefinition module) {
        Map<String, PlatformApiDocument.Schema> schemas = new LinkedHashMap<>();
        for (EntityDefinition entity : module.entities()) {
            schemas.put(schemaName(entity), entitySchema(entity));
        }
        EntityDefinition main = module.entities().isEmpty() ? null : module.entities().getFirst();
        if (main != null) {
            String entitySchemaName = schemaName(main);
            schemas.put("WebQueryRequest", webQueryRequestSchema());
            schemas.put("WebPageRequest", webPageRequestSchema());
            schemas.put("WebQueryCondition", webQueryConditionSchema());
            schemas.put("WebQueryCriteria", webQueryCriteriaSchema());
            schemas.put("WebSort", webSortSchema());
            schemas.put("RecordActionWebRequest", recordActionWebRequestSchema());
            schemas.put("TreeSortWebRequest", treeSortRequestSchema());
            schemas.put("TreeSortScopeRequest", treeSortScopeRequestSchema());
            schemas.put(entitySchemaName + "PageResponse", pageResponseSchema(entitySchemaName));
        }
        schemas.put("PlatformWebError", platformWebErrorSchema());
        return Map.copyOf(schemas);
    }

    String mainSchemaName(StaticModuleDefinition module) {
        return module.entities().isEmpty() ? null : schemaName(module.entities().getFirst());
    }

    private PlatformApiDocument.Schema entitySchema(EntityDefinition entity) {
        Map<String, PlatformApiDocument.Property> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (FieldDefinition field : entity.fields()) {
            properties.put(field.fieldName(), property(field));
            if (field.isRequired()) required.add(field.fieldName());
        }
        properties.put("id", new PlatformApiDocument.Property("string", null, false, true, false,
                null, "Platform record identifier", null, null, null, null, List.of()));
        properties.put("tenantId", new PlatformApiDocument.Property("string", null, false, true, false,
                null, "Platform tenant identifier", null, null, null, null, List.of()));
        properties.put("version", new PlatformApiDocument.Property("integer", "int32", false, true, false,
                null, "Optimistic lock version; required when updating an existing record", null, null, null, null, List.of()));
        properties.put("deleted", new PlatformApiDocument.Property("boolean", null, false, true, false,
                null, "Soft delete flag", null, null, null, null, List.of()));
        properties.put("deletedAt", temporalProperty());
        properties.put("deletedBy", stringProperty());
        properties.put("createdBy", stringProperty());
        properties.put("createdAt", temporalProperty());
        properties.put("updatedBy", stringProperty());
        properties.put("updatedAt", temporalProperty());
        return new PlatformApiDocument.Schema(schemaName(entity), "object", null, required, properties, null);
    }

    private PlatformApiDocument.Property temporalProperty() {
        return new PlatformApiDocument.Property("string", "date-time", false, true, false,
                null, null, null, null, null, null, List.of());
    }

    private PlatformApiDocument.Property property(FieldDefinition field) {
        FieldShape shape = fieldShape(field.type());
        OptionBinding binding = field.optionBinding();
        boolean multiple = field.valueShape().name().equals("JSON_SET");
        return new PlatformApiDocument.Property(shape.type(), shape.format(), field.isRequired(), !field.isRequired(), multiple,
                binding == null ? null : binding.sourceType(), binding == null ? field.name() : binding.source(),
                null, null, null, null, List.of());
    }

    private PlatformApiDocument.Schema webQueryRequestSchema() {
        Map<String, PlatformApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("page", objectProperty("WebPageRequest"));
        properties.put("unpaged", booleanProperty());
        properties.put("conditions", arrayProperty("WebQueryCondition"));
        properties.put("criteria", objectProperty("WebQueryCriteria"));
        properties.put("queryForm", objectProperty(null));
        properties.put("sorts", arrayProperty("WebSort"));
        properties.put("uiConfigId", stringProperty());
        properties.put("queryTemplateId", stringProperty());
        properties.put("externalQueryValues", objectProperty(null));
        properties.put("navigationSession", booleanProperty());
        properties.put("quickSearch", stringProperty());
        properties.put("quickSearchFields", arrayProperty("string"));
        properties.put("navigationQueryKey", stringProperty());
        return new PlatformApiDocument.Schema("WebQueryRequest", "object", null, List.of(), properties, null);
    }

    private PlatformApiDocument.Schema webPageRequestSchema() {
        return new PlatformApiDocument.Schema("WebPageRequest", "object", null, List.of("pageNum", "pageSize"), Map.of(
                "pageNum", requiredIntegerProperty(),
                "pageSize", requiredIntegerProperty()
        ), null);
    }

    private PlatformApiDocument.Schema webQueryConditionSchema() {
        return new PlatformApiDocument.Schema("WebQueryCondition", "object", null, List.of("fieldName", "operator"), Map.of(
                "fieldName", requiredStringProperty(),
                "operator", requiredStringProperty(),
                "values", arrayProperty("object"),
                "timeZone", stringProperty()
        ), null);
    }

    private PlatformApiDocument.Schema webQueryCriteriaSchema() {
        return new PlatformApiDocument.Schema("WebQueryCriteria", "object", null, List.of(), Map.of(
                "operator", stringProperty(),
                "conditions", arrayProperty("WebQueryCondition"),
                "groups", arrayProperty("WebQueryCriteria")
        ), null);
    }

    private PlatformApiDocument.Schema webSortSchema() {
        return new PlatformApiDocument.Schema("WebSort", "object", null, List.of("field", "desc"), Map.of(
                "field", requiredStringProperty(),
                "desc", requiredBooleanProperty()
        ), null);
    }

    private PlatformApiDocument.Schema recordActionWebRequestSchema() {
        return new PlatformApiDocument.Schema("RecordActionWebRequest", "object", null, List.of("version"), Map.of(
                "version", requiredIntegerProperty()
        ), null);
    }

    private PlatformApiDocument.Schema treeSortRequestSchema() {
        Map<String, PlatformApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("previousId", stringProperty());
        properties.put("nextId", stringProperty());
        properties.put("parentId", stringProperty());
        properties.put("scope", objectProperty("TreeSortScopeRequest"));
        return new PlatformApiDocument.Schema("TreeSortWebRequest", "object", null, List.of(), properties, null);
    }

    private PlatformApiDocument.Schema treeSortScopeRequestSchema() {
        Map<String, PlatformApiDocument.Property> properties = new LinkedHashMap<>();
        properties.put("externalQueryValues", objectProperty(null));
        properties.put("navigatorHostModuleAlias", stringProperty());
        properties.put("navigatorTargetLevelKey", stringProperty());
        return new PlatformApiDocument.Schema("TreeSortScopeRequest", "object", null, List.of(), properties, null);
    }

    private PlatformApiDocument.Schema pageResponseSchema(String itemSchema) {
        return new PlatformApiDocument.Schema(itemSchema + "PageResponse", "object", null, List.of(), Map.of(
                "records", new PlatformApiDocument.Property(itemSchema, null, true, false, true,
                        null, null, null, null, itemSchema, null, List.of()),
                "total", new PlatformApiDocument.Property("integer", "int64", true, false, false,
                        null, null, null, null, null, null, List.of()),
                "pageNum", requiredIntegerProperty(),
                "pageSize", requiredIntegerProperty(),
                "pages", new PlatformApiDocument.Property("integer", "int64", true, false, false,
                        null, null, null, null, null, null, List.of()),
                "totalKnown", requiredBooleanProperty(),
                "navigation", objectProperty(null)
        ), null);
    }

    private PlatformApiDocument.Schema platformWebErrorSchema() {
        return new PlatformApiDocument.Schema("PlatformWebError", "object", null,
                List.of("traceId", "code", "status", "message"), Map.of(
                "traceId", requiredStringProperty(),
                "code", new PlatformApiDocument.Property("string", null, true, false, false,
                        null, null, null, null, null, null, List.of()),
                "status", requiredIntegerProperty(),
                "message", new PlatformApiDocument.Property("string", null, true, false, false,
                        null, null, null, null, null, null, List.of()),
                "actionMessage", objectProperty(null),
                "scope", objectProperty(null),
                "targets", arrayProperty("object"),
                "details", objectProperty(null),
                "messageArgs", objectProperty(null)
        ), null);
    }

    private PlatformApiDocument.Property stringProperty() {
        return new PlatformApiDocument.Property("string", null, false, true, false,
                null, null, null, null, null, null, List.of());
    }

    private PlatformApiDocument.Property requiredStringProperty() {
        return new PlatformApiDocument.Property("string", null, true, false, false,
                null, null, null, null, null, null, List.of());
    }

    private PlatformApiDocument.Property booleanProperty() {
        return new PlatformApiDocument.Property("boolean", null, false, true, false,
                null, null, null, null, null, null, List.of());
    }

    private PlatformApiDocument.Property requiredBooleanProperty() {
        return new PlatformApiDocument.Property("boolean", null, true, false, false,
                null, null, null, null, null, null, List.of());
    }

    private PlatformApiDocument.Property requiredIntegerProperty() {
        return new PlatformApiDocument.Property("integer", "int32", true, false, false,
                null, null, null, null, null, null, List.of());
    }

    private PlatformApiDocument.Property objectProperty(String schemaName) {
        return new PlatformApiDocument.Property(schemaName == null ? "object" : schemaName, null, false, true, false,
                null, null, null, null, null, null, List.of());
    }

    private PlatformApiDocument.Property arrayProperty(String itemType) {
        return new PlatformApiDocument.Property("array", null, false, true, true,
                null, null, null, null, itemType, null, List.of());
    }

    private String schemaName(EntityDefinition entity) {
        return upperCamel(entity.alias());
    }

    private String upperCamel(String value) {
        StringBuilder result = new StringBuilder();
        boolean uppercase = true;
        for (char current : value.toCharArray()) {
            if (current == '_' || current == '-' || current == '.') {
                uppercase = true;
            } else {
                result.append(uppercase ? Character.toUpperCase(current) : current);
                uppercase = false;
            }
        }
        return result.toString();
    }

    private FieldShape fieldShape(FieldType type) {
        PlatformWebWireContract.WireShape shape = PlatformWebWireContract.openApiShape(type.name());
        return new FieldShape(shape.type(), shape.format());
    }

    private record FieldShape(String type, String format) {
    }
}
