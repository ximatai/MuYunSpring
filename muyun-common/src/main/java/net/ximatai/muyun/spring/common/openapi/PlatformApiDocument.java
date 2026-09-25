package net.ximatai.muyun.spring.common.openapi;

import net.ximatai.muyun.spring.common.model.constraint.FieldWriteRules;
import net.ximatai.muyun.spring.common.model.constraint.WriteOperation;

import java.util.List;
import java.util.Map;

/** Source-independent module API document used by static and dynamic delivery. */
public interface PlatformApiDocument {
    String moduleAlias();

    String title();

    String basePath();

    List<Operation> operations();

    Map<String, Schema> schemas();

    Map<String, ErrorResponse> errors();

    record Operation(String method, String path, String operationId, String summary,
                     String requestSchema, String responseSchema, String actionCode,
                     String permissionCode, List<String> errorCodes, int successStatus,
                     String responseMediaType, Map<String, Object> requestExample) {
        public Operation {
            errorCodes = errorCodes == null ? List.of() : List.copyOf(errorCodes);
            requestExample = requestExample == null ? null : Map.copyOf(requestExample);
            if (successStatus < 100 || successStatus > 599) {
                throw new IllegalArgumentException("OpenAPI success status must be an HTTP status: " + successStatus);
            }
        }

        public Operation(String method, String path, String operationId, String summary,
                         String requestSchema, String responseSchema, String actionCode,
                         String permissionCode, List<String> errorCodes) {
            this(method, path, operationId, summary, requestSchema, responseSchema, actionCode, permissionCode,
                    errorCodes, 200, null);
        }

        public Operation(String method, String path, String operationId, String summary,
                         String requestSchema, String responseSchema, String actionCode,
                         String permissionCode, List<String> errorCodes, int successStatus) {
            this(method, path, operationId, summary, requestSchema, responseSchema, actionCode, permissionCode,
                    errorCodes, successStatus, null);
        }

        public Operation(String method, String path, String operationId, String summary,
                         String requestSchema, String responseSchema, String actionCode,
                         String permissionCode, List<String> errorCodes, int successStatus,
                         String responseMediaType) {
            this(method, path, operationId, summary, requestSchema, responseSchema, actionCode, permissionCode,
                    errorCodes, successStatus, responseMediaType, null);
        }
    }

    record Schema(String name, String type, String format, List<String> required,
                  Map<String, Property> properties, Property items,
                  Map<String, String> valueShapeByResultType, WriteOperation writeOperation, boolean partialUpdate) {
        public Schema(String name, String type, String format, List<String> required,
                      Map<String, Property> properties, Property items, Map<String, String> valueShapeByResultType) {
            this(name, type, format, required, properties, items, valueShapeByResultType, null, false);
        }

        public Schema withWriteOperation(WriteOperation operation, boolean partialUpdate) {
            return new Schema(name, type, format, required, properties, items, valueShapeByResultType,
                    operation, partialUpdate);
        }

        public Schema {
            required = required == null ? List.of() : List.copyOf(required);
            properties = properties == null ? Map.of() : Map.copyOf(properties);
            valueShapeByResultType = valueShapeByResultType == null ? Map.of() : Map.copyOf(valueShapeByResultType);
        }

        public Schema(String name, String type, String format, List<String> required,
                      Map<String, Property> properties, Property items) {
            this(name, type, format, required, properties, items, Map.of());
        }
    }

    record Property(String type, String format, boolean required, boolean nullable, boolean multiple,
                    String optionSourceType, String optionSource, String referenceModuleAlias,
                    String referenceEntityAlias, String itemType, String temporalSemantics,
                    List<String> companionFields, FieldWriteRules writeRules) {
        public Property {
            writeRules = writeRules == null ? FieldWriteRules.NONE : writeRules;
            companionFields = companionFields == null ? List.of() : List.copyOf(companionFields);
        }

        public Property(String type, String format, boolean required, boolean nullable, boolean multiple,
                        String optionSourceType, String optionSource, String referenceModuleAlias,
                        String referenceEntityAlias, String itemType, String temporalSemantics,
                        List<String> companionFields) {
            this(type, format, required, nullable, multiple, optionSourceType, optionSource, referenceModuleAlias,
                    referenceEntityAlias, itemType, temporalSemantics, companionFields, FieldWriteRules.NONE);
        }

        public Property withNullable(boolean value) {
            return new Property(type, format, required, value, multiple, optionSourceType, optionSource,
                    referenceModuleAlias, referenceEntityAlias, itemType, temporalSemantics, companionFields, writeRules);
        }

        public Property withRequired(boolean value) {
            return new Property(type, format, value, nullable, multiple, optionSourceType, optionSource,
                    referenceModuleAlias, referenceEntityAlias, itemType, temporalSemantics, companionFields, writeRules);
        }

        /** Final persisted-value constraints; these do not imply mandatory JSON properties. */
        public Property withWriteRules(FieldWriteRules rules) {
            return new Property(type, format, required, nullable, multiple, optionSourceType, optionSource,
                    referenceModuleAlias, referenceEntityAlias, itemType, temporalSemantics, companionFields, rules);
        }

        public Property(String type, String format, boolean required, boolean nullable, boolean multiple,
                        String optionSourceType, String optionSource, String referenceModuleAlias,
                        String referenceEntityAlias, String itemType, List<String> companionFields) {
            this(type, format, required, nullable, multiple, optionSourceType, optionSource,
                    referenceModuleAlias, referenceEntityAlias, itemType, null, companionFields);
        }
    }

    record ErrorResponse(String code, int status, String schemaName) {
    }
}
