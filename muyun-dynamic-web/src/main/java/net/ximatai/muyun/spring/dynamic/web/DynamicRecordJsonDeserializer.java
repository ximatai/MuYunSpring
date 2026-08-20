package net.ximatai.muyun.spring.dynamic.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicRelationDescriptor;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class DynamicRecordJsonDeserializer extends JsonDeserializer<DynamicRecord> {
    private final DynamicRecordService recordService;

    DynamicRecordJsonDeserializer(DynamicRecordService recordService) {
        this.recordService = recordService;
    }

    @Override
    public DynamicRecord deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        String moduleAlias = DynamicWebRequest.moduleAlias();
        JsonNode root = parser.getCodec().readTree(parser);
        rejectRecordWrapper(root);
        DynamicRecord record = record(moduleAlias, recordService.mainEntityAlias(moduleAlias), root, parser, context);
        readUiConfigId(record, root);
        readOriginContext(record, root, parser, context);
        readAttachments(record, root, parser, context);
        readChildren(moduleAlias, record, root, parser, context);
        return record;
    }

    private void rejectRecordWrapper(JsonNode root) {
        if (root.has("record") && root.get("record").isObject()) {
            throw new IllegalArgumentException("dynamic record wrapper is not supported; submit the record directly");
        }
    }

    private void readUiConfigId(DynamicRecord record, JsonNode root) {
        JsonNode uiConfigId = root.get("uiConfigId");
        if (uiConfigId != null && !uiConfigId.isNull() && !uiConfigId.asText().isBlank()) {
            record.putMutationMetadata("uiConfigId", uiConfigId.asText());
        }
    }

    private DynamicRecord record(String moduleAlias,
                                 String entityAlias,
                                 JsonNode root,
                                 JsonParser parser,
                                 DeserializationContext context) throws IOException {
        String mainEntityAlias = recordService.mainEntityAlias(moduleAlias);
        DynamicRecord record = Objects.equals(mainEntityAlias, entityAlias)
                ? recordService.mainEntity(moduleAlias).newRecord()
                : recordService.newRecord(moduleAlias, entityAlias);
        JsonNode id = root.get("id");
        if (id != null && !id.isNull()) {
            record.setId(id.asText());
        }
        JsonNode version = root.get("version");
        if (version != null && !version.isNull()) {
            record.setVersion(version.asInt());
        }
        JsonNode values = root.has("values") ? root.get("values") : root;
        if (values == null || values.isNull()) {
            return record;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = values.properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (isEnvelopeField(field.getKey())) {
                continue;
            }
            try (JsonParser valueParser = field.getValue().traverse(parser.getCodec())) {
                valueParser.nextToken();
                record.setValue(field.getKey(), readFieldValue(record, field.getKey(), field.getValue(), valueParser, context));
            }
        }
        return record;
    }

    /**
     * JSON parsers cannot infer an editor's target field type from an untyped record map. Decode
     * numeric JSON nodes by the published entity field fact before DynamicRecord applies its
     * invariant validation. LONG and DECIMAL also accept their lossless textual wire form; the
     * DynamicFieldValueSupport invariant then parses it without a browser Number round-trip.
     */
    private Object readFieldValue(DynamicRecord record,
                                  String fieldName,
                                  JsonNode value,
                                  JsonParser valueParser,
                                  DeserializationContext context) throws IOException {
        FieldType type = record.getEntity().fields().stream()
                .filter(field -> field.fieldName().equals(fieldName))
                .map(FieldDefinition::type)
                .findFirst()
                .orElse(null);
        if (type == FieldType.INTEGER && value.isIntegralNumber()) {
            if (!value.canConvertToInt()) {
                throw new IllegalArgumentException("integer dynamic field exceeds supported range: " + fieldName);
            }
            return value.intValue();
        }
        if (type == FieldType.LONG && value.isIntegralNumber()) {
            if (!value.canConvertToLong()) {
                throw new IllegalArgumentException("long dynamic field exceeds supported range: " + fieldName);
            }
            return value.longValue();
        }
        if (type == FieldType.DECIMAL && value.isNumber()) {
            return value.decimalValue();
        }
        return context.readValue(valueParser, Object.class);
    }

    private void readOriginContext(DynamicRecord record,
                                   JsonNode root,
                                   JsonParser parser,
                                   DeserializationContext context) throws IOException {
        JsonNode originContext = root.get("originContext");
        if (originContext == null || originContext.isNull()) {
            return;
        }
        try (JsonParser originParser = originContext.traverse(parser.getCodec())) {
            originParser.nextToken();
            record.putMutationMetadata("originContext", context.readValue(originParser, Object.class));
        }
    }

    private void readAttachments(DynamicRecord record,
                                 JsonNode root,
                                 JsonParser parser,
                                 DeserializationContext context) throws IOException {
        if (!root.has("attachments") || root.get("attachments").isNull()) {
            return;
        }
        JsonNode attachments = root.get("attachments");
        if (!attachments.isArray()) {
            throw new IllegalArgumentException("dynamic record attachments must be array");
        }
        try (JsonParser attachmentsParser = attachments.traverse(parser.getCodec())) {
            attachmentsParser.nextToken();
            record.putMutationMetadata("attachments", context.readValue(attachmentsParser, Object.class));
        }
    }

    private void readChildren(String moduleAlias,
                              DynamicRecord record,
                              JsonNode root,
                              JsonParser parser,
                              DeserializationContext context) throws IOException {
        JsonNode children = root.get("children");
        if (children == null || children.isNull()) {
            return;
        }
        if (!children.isObject()) {
            throw new IllegalArgumentException("dynamic record children must be object");
        }
        List<DynamicRelationDescriptor> childRelations = recordService.relations(moduleAlias).stream()
                .filter(relation -> Objects.equals(record.getEntity().alias(), relation.parentEntityAlias()))
                .toList();
        List<String> knownRelations = childRelations.stream()
                .map(DynamicRelationDescriptor::code)
                .toList();
        Iterator<String> relationCodes = children.fieldNames();
        while (relationCodes.hasNext()) {
            String relationCode = relationCodes.next();
            if (!knownRelations.contains(relationCode)) {
                throw new IllegalArgumentException("unknown dynamic child relation: " + relationCode);
            }
        }
        for (DynamicRelationDescriptor relation : childRelations) {
            JsonNode relationRows = children.get(relation.code());
            if (relationRows == null || relationRows.isNull()) {
                continue;
            }
            if (!relationRows.isArray()) {
                throw new IllegalArgumentException("dynamic child relation must be array: " + relation.code());
            }
            List<DynamicRecord> rows = new ArrayList<>();
            for (JsonNode childNode : relationRows) {
                rows.add(record(moduleAlias, relation.childEntityAlias(), childNode, parser, context));
            }
            record.setChildren(relation.code(), rows);
        }
    }

    private boolean isEnvelopeField(String fieldName) {
        return "id".equals(fieldName)
                || "version".equals(fieldName)
                || "uiConfigId".equals(fieldName)
                || "values".equals(fieldName)
                || "children".equals(fieldName)
                || "attachments".equals(fieldName)
                || "originContext".equals(fieldName);
    }
}
