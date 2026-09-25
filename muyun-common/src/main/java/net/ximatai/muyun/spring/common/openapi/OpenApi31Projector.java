package net.ximatai.muyun.spring.common.openapi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Projects the source-independent platform API model to the OpenAPI 3.1 wire contract. */
public final class OpenApi31Projector {
    public static final String VERSION = "3.1.1";
    private static final String JSON = "application/json";

    private OpenApi31Projector() {
    }

    public static Map<String, Object> project(PlatformApiDocument document) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("openapi", VERSION);
        result.put("jsonSchemaDialect", "https://json-schema.org/draft/2020-12/schema");
        result.put("info", Map.of("title", document.title(), "version", "1.0.0"));
        // Operation paths are the real Spring MVC paths and already include the module alias.
        // OpenAPI resolves an operation URL by appending a path to the selected server, therefore
        // using the module path here would duplicate it for generated clients and documentation UIs.
        result.put("servers", List.of(Map.of("url", "")));
        result.put("tags", List.of(Map.of("name", document.moduleAlias(), "description", document.title())));
        result.put("paths", paths(document));
        result.put("components", components(document));
        result.put("x-muyun-module-alias", document.moduleAlias());
        result.put("x-muyun-module-base-path", document.basePath());
        return Map.copyOf(result);
    }

    private static Map<String, Object> paths(PlatformApiDocument document) {
        Map<String, Object> paths = new LinkedHashMap<>();
        for (PlatformApiDocument.Operation operation : document.operations()) {
            Map<String, Object> pathItem = castMap(paths.computeIfAbsent(operation.path(), ignored -> new LinkedHashMap<>()));
            pathItem.put(operation.method().toLowerCase(), operation(operation, document));
        }
        return copyMap(paths);
    }

    private static Map<String, Object> operation(PlatformApiDocument.Operation operation,
                                                  PlatformApiDocument document) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("operationId", operation.operationId());
        value.put("summary", operation.summary());
        value.put("tags", List.of(document.moduleAlias()));
        value.put("security", List.of(Map.of("bearerAuth", List.of())));
        List<Map<String, Object>> parameters = pathParameters(operation.path());
        if (!parameters.isEmpty()) {
            value.put("parameters", parameters);
        }
        if (operation.requestSchema() != null) {
            Map<String, Object> media = new LinkedHashMap<>();
            media.put("schema", schemaReference(operation.requestSchema(), document.schemas().keySet()));
            if (operation.requestExample() != null) media.put("example", operation.requestExample());
            value.put("requestBody", Map.of("required", true, "content", Map.of(JSON, Map.copyOf(media))));
        }
        value.put("responses", responses(operation, document));
        putExtension(value, "x-muyun-action-code", operation.actionCode());
        putExtension(value, "x-muyun-permission-code", operation.permissionCode());
        return Map.copyOf(value);
    }

    private static List<Map<String, Object>> pathParameters(String path) {
        List<Map<String, Object>> parameters = new ArrayList<>();
        int position = 0;
        while ((position = path.indexOf('{', position)) >= 0) {
            int end = path.indexOf('}', position);
            if (end < 0) break;
            String name = path.substring(position + 1, end);
            parameters.add(Map.of("name", name, "in", "path", "required", true,
                    "schema", Map.of("type", "string")));
            position = end + 1;
        }
        return List.copyOf(parameters);
    }

    private static Map<String, Object> responses(PlatformApiDocument.Operation operation,
                                                  PlatformApiDocument document) {
        Map<String, Object> responses = new LinkedHashMap<>();
        Map<String, Object> success = new LinkedHashMap<>();
        success.put("description", "Success");
        if (operation.responseSchema() != null) {
            success.put("content", Map.of(responseMediaType(operation), Map.of("schema",
                    schemaReference(operation.responseSchema(), document.schemas().keySet()))));
        }
        responses.put(String.valueOf(operation.successStatus()), success);
        for (String code : operation.errorCodes()) {
            PlatformApiDocument.ErrorResponse error = document.errors().get(code);
            if (error != null) {
                responses.putIfAbsent(String.valueOf(error.status()), Map.of("description", code,
                        "content", Map.of(JSON, Map.of("schema", schemaReference(error.schemaName(), document.schemas().keySet())))));
            }
        }
        return copyMap(responses);
    }

    private static String responseMediaType(PlatformApiDocument.Operation operation) {
        return operation.responseMediaType() == null || operation.responseMediaType().isBlank()
                ? JSON
                : operation.responseMediaType();
    }

    private static Map<String, Object> components(PlatformApiDocument document) {
        Map<String, Object> schemas = new LinkedHashMap<>();
        document.schemas().forEach((name, schema) -> schemas.put(name, schema(schema, document.schemas().keySet())));
        schemas.putIfAbsent("PlatformWebError", Map.of("type", "object", "description", "Platform error response"));
        return Map.of(
                "securitySchemes", Map.of("bearerAuth", Map.of("type", "http", "scheme", "bearer", "bearerFormat", "JWT")),
                "schemas", copyMap(schemas)
        );
    }

    private static Map<String, Object> schema(PlatformApiDocument.Schema schema, Set<String> schemaNames) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", schema.type());
        putExtension(value, "format", schema.format());
        if (!schema.required().isEmpty()) value.put("required", schema.required());
        if (!schema.properties().isEmpty()) {
            Map<String, Object> properties = new LinkedHashMap<>();
            schema.properties().forEach((name, property) -> properties.put(name, property(property, schemaNames)));
            value.put("properties", copyMap(properties));
        }
        if (schema.writeOperation() != null) {
            value.put("x-muyun-write-operation", schema.writeOperation().name());
            value.put("x-muyun-partial-update", schema.partialUpdate());
        }
        if (schema.items() != null) value.put("items", property(schema.items(), schemaNames));
        if (!schema.valueShapeByResultType().isEmpty()) value.put("x-muyun-value-shape-by-result-type", schema.valueShapeByResultType());
        return Map.copyOf(value);
    }

    private static Map<String, Object> property(PlatformApiDocument.Property property, Set<String> schemaNames) {
        Map<String, Object> value = new LinkedHashMap<>();
        if (property.multiple()) {
            value.put("type", "array");
            String itemType = property.itemType() == null ? property.type() : property.itemType();
            value.put("items", schemaReference(itemType, schemaNames));
        } else {
            value.putAll(schemaReference(property.type(), schemaNames));
            putExtension(value, "format", property.format());
        }
        if (property.nullable()) {
            Object reference = value.remove("$ref");
            if (reference != null) {
                value.put("anyOf", List.of(Map.of("$ref", reference), Map.of("type", "null")));
            } else {
                value.put("type", List.of(value.getOrDefault("type", "object"), "null"));
            }
        }
        if (!net.ximatai.muyun.spring.common.model.constraint.FieldWriteRules.NONE.equals(property.writeRules())) {
            value.put("x-muyun-write-rules", Map.of(
                    "semantics", "FINAL_VALUE",
                    "nonNull", property.writeRules().nonNull(),
                    "requiredOnInsert", property.writeRules().requiredOnInsert(),
                    "requiredOnUpdate", property.writeRules().requiredOnUpdate(),
                    "textNormalization", property.writeRules().textNormalization().name()));
        }
        putExtension(value, "description", property.optionSource());
        putExtension(value, "x-muyun-option-source-type", property.optionSourceType());
        putExtension(value, "x-muyun-option-source", property.optionSource());
        putExtension(value, "x-muyun-reference-module-alias", property.referenceModuleAlias());
        putExtension(value, "x-muyun-reference-entity-alias", property.referenceEntityAlias());
        putExtension(value, "x-muyun-temporal-semantics", property.temporalSemantics());
        if (!property.companionFields().isEmpty()) value.put("x-muyun-companion-fields", property.companionFields());
        return Map.copyOf(value);
    }

    private static Map<String, Object> schemaReference(String type, Set<String> schemaNames) {
        if (schemaNames.contains(type)) return Map.of("$ref", "#/components/schemas/" + type);
        return Map.of("type", type == null ? "object" : type);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> copyMap(Map<String, Object> value) {
        return Map.copyOf(value);
    }

    private static void putExtension(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) target.put(key, value);
    }
}
