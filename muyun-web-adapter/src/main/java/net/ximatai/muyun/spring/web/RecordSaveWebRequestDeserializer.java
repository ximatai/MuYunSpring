package net.ximatai.muyun.spring.web;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.deser.ContextualDeserializer;
import net.ximatai.muyun.spring.common.mutation.RecordSaveMutationMetadata;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Accepts the reserved standard envelope and keeps direct record bodies readable. */
final class RecordSaveWebRequestDeserializer extends JsonDeserializer<RecordSaveWebRequest<?>>
        implements ContextualDeserializer {
    private final JavaType recordType;

    RecordSaveWebRequestDeserializer() {
        this(null);
    }

    private RecordSaveWebRequestDeserializer(JavaType recordType) {
        this.recordType = recordType;
    }

    @Override
    public JsonDeserializer<?> createContextual(DeserializationContext context, BeanProperty property) {
        JavaType contextualType = context.getContextualType();
        JavaType resolvedRecordType = contextualType == null ? null : contextualType.containedType(0);
        return new RecordSaveWebRequestDeserializer(resolvedRecordType);
    }

    @Override
    public RecordSaveWebRequest<?> deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (recordType == null) {
            throw new IllegalStateException("record save request type must be resolved");
        }
        JsonNode root = parser.getCodec().readTree(parser);
        JsonNode envelope = root.get(RecordSaveWebRequest.ENVELOPE_FIELD);
        if (envelope != null && !envelope.isObject()) {
            throw new IllegalArgumentException("record save envelope must be an object");
        }
        JsonNode recordNode = envelope == null ? root : envelope.get("record");
        if (recordNode == null || recordNode.isNull()) {
            throw new IllegalArgumentException("record save envelope must contain record");
        }
        Object record = context.readTreeAsValue(recordNode, recordType);
        RecordSaveMutationMetadata metadata = envelope != null && envelope.has("metadata") && !envelope.get("metadata").isNull()
                ? context.readTreeAsValue(envelope.get("metadata"), RecordSaveMutationMetadata.class)
                : RecordSaveMutationMetadata.empty();
        return new RecordSaveWebRequest<>(record, metadata, legacyMetadata(envelope, context));
    }

    private Map<String, Object> legacyMetadata(JsonNode envelope, DeserializationContext context) throws IOException {
        if (envelope == null) {
            return Map.of();
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = envelope.properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!"record".equals(field.getKey()) && !"metadata".equals(field.getKey())) {
                metadata.put(field.getKey(), context.readTreeAsValue(field.getValue(), Object.class));
            }
        }
        return metadata;
    }
}
