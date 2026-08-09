package net.ximatai.muyun.spring.web;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.annotation.JsonIgnore;
import net.ximatai.muyun.spring.common.mutation.RecordSaveMutationMetadata;

import java.util.Map;

/**
 * The common HTTP envelope for standard create and update operations.
 */
@JsonDeserialize(using = RecordSaveWebRequestDeserializer.class)
public record RecordSaveWebRequest<T>(T record,
                                      RecordSaveMutationMetadata metadata,
                                      @JsonIgnore Map<String, Object> legacyMetadata) {
    public RecordSaveWebRequest {
        metadata = metadata == null ? RecordSaveMutationMetadata.empty() : metadata;
        legacyMetadata = legacyMetadata == null ? Map.of() : Map.copyOf(legacyMetadata);
    }

    public RecordSaveWebRequest(T record, RecordSaveMutationMetadata metadata) {
        this(record, metadata, Map.of());
    }

    /** Reserved JSON member that makes the standard save envelope unambiguous for static record fields. */
    public static final String ENVELOPE_FIELD = "$save";

    public T requireRecord() {
        if (record == null) {
            throw new IllegalArgumentException("record save request must contain record");
        }
        return record;
    }
}
