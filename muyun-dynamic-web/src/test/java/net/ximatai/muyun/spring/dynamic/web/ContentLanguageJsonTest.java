package net.ximatai.muyun.spring.dynamic.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.content.*;
import net.ximatai.muyun.spring.dynamic.metadata.*;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicRelationDescriptor;
import net.ximatai.muyun.spring.dynamic.runtime.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentLanguageJsonTest {
    private ObjectMapper mapper;
    @BeforeEach void setup() {
        var main = new EntityDefinition("main", "content_wire_main", "Main", List.of(FieldDefinition.text("description", "Description")));
        var line = new EntityDefinition("line", "content_wire_line", "Line", List.of(FieldDefinition.text("description", "Description")));
        var records = mock(DynamicRecordService.class);
        var operations = mock(DynamicEntityOperations.class);
        when(records.mainEntityAlias("test.content")).thenReturn("main");
        when(records.mainEntity("test.content")).thenReturn(operations);
        when(operations.newRecord()).thenAnswer(call -> new DynamicRecord(main));
        when(records.newRecord("test.content", "line")).thenAnswer(call -> new DynamicRecord(line));
        when(records.relations("test.content")).thenReturn(List.of(new DynamicRelationDescriptor("lines", "main", "line", "mainId", false, false)));
        mapper = new ObjectMapper().registerModule(new DynamicRecordJacksonConfiguration().dynamicRecordJacksonModule(records));
        var request = new MockHttpServletRequest(); request.setRequestURI("/test.content");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
    @AfterEach void reset() { RequestContextHolder.resetRequestAttributes(); }
    @Test void envelopeAndFlatRecordsUseTheSamePerRowLanguageIntent() throws Exception {
        for (String values : List.of("\"description\":\"中文原文\"", "\"values\":{\"description\":\"中文原文\"}")) {
            var row = mapper.readValue("{" + values + ",\"$contentLanguages\":{\"description\":{\"languageTag\":\"zh-CN\"}},"
                    + "\"children\":{\"lines\":[{\"values\":{\"description\":\"Original English\"},\"$contentLanguages\":{\"description\":{\"languageTag\":\"en\",\"expectedRevision\":2}}}]}}", DynamicRecord.class);
            assertThat(row.getValue("description")).isEqualTo("中文原文");
            assertThat(row.getPlatformValues()).doesNotContainKey("$contentLanguages");
            assertThat(ContentWriteContext.inputs(row).get("description").languageTag()).isEqualTo("zh-CN");
            assertThat(ContentWriteContext.inputs(row.getChildren("lines").getFirst()).get("description").expectedRevision()).isEqualTo(2);
            assertThat(mapper.valueToTree(row).path("$contentLanguages").path("description").path("languageTag").asText()).isEqualTo("zh-CN");
        }
    }
    @Test void invalidMetadataIsRejectedAndEmptyInputDoesNotClaimALanguage() throws Exception {
        assertThatThrownBy(() -> mapper.readValue("{\"$contentLanguages\":[]}", DynamicRecord.class)).isInstanceOf(IllegalArgumentException.class);
        var invalid = catchThrowable(() -> mapper.readValue("{\"$contentLanguages\":{\"description\":null}}", DynamicRecord.class));
        assertThat(invalid).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("field and source input");
        assertThat(new net.ximatai.muyun.spring.web.PlatformWebExceptionHandler().handleBadRequest((IllegalArgumentException) invalid).getStatusCode().value()).isEqualTo(400);
        var row = mapper.readValue("{\"description\":\"Original\"}", DynamicRecord.class);
        assertThat(ContentWriteContext.inputs(row)).isEmpty();
        assertThat(mapper.valueToTree(row).has("$contentLanguages")).isFalse();
    }
}
