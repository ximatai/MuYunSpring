package net.ximatai.muyun.spring.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.mutation.RecordSaveMutationMetadata;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RecordSaveWebRequestTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void keepsDirectStaticRecordFieldsNamedRecordAndMetadataOutOfTheSaveEnvelope() throws Exception {
        RecordSaveWebRequest<StaticRecord> request = objectMapper.readValue(
                "{\"record\":\"business record\",\"metadata\":\"business metadata\"}",
                new TypeReference<>() { });

        assertThat(request.record()).isEqualTo(new StaticRecord("business record", "business metadata"));
        assertThat(request.metadata()).isEqualTo(RecordSaveMutationMetadata.empty());
    }

    @Test
    void readsFileDeletionMetadataOnlyFromTheReservedSaveEnvelope() throws Exception {
        RecordSaveWebRequest<StaticRecord> request = objectMapper.readValue(
                "{\"$save\":{\"record\":{\"record\":\"business record\"},\"metadata\":{\"fileDeletions\":[]}}}",
                new TypeReference<>() { });

        assertThat(request.record()).isEqualTo(new StaticRecord("business record", null));
        assertThat(request.metadata()).isEqualTo(RecordSaveMutationMetadata.empty());
    }

    record StaticRecord(String record, String metadata) {
    }
}
