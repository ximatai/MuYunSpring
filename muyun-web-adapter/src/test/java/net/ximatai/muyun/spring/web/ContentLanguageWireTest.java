package net.ximatai.muyun.spring.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.common.content.ContentWriteContext;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class ContentLanguageWireTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new MuYunSpringJacksonConfiguration().codeTitleEnumJacksonModule());
    @Test void languagesArePerRecordWriteIntentAndDoNotReplaceRealOriginals() throws Exception {
        var row = mapper.readValue("""
                {"description":"80°C，暂勿发货","$contentLanguages":{"description":{"languageTag":"zh-CN"}},
                 "lines":[{"description":"Investigation pending","$contentLanguages":{"description":{"languageTag":"en","expectedRevision":2}}}]}
                """, Fixture.class);
        assertThat(row.description).isEqualTo("80°C，暂勿发货");
        assertThat(ContentWriteContext.inputs(row).get("description").languageTag()).isEqualTo("zh-CN");
        assertThat(ContentWriteContext.inputs(row.lines.getFirst()).get("description").expectedRevision()).isEqualTo(2);
        var wire = mapper.valueToTree(row);
        assertThat(wire.path("$contentLanguages").path("description").path("languageTag").asText()).isEqualTo("zh-CN");
        assertThat(wire.has("contentLanguageInputs")).isFalse();
        assertThat(mapper.valueToTree(new Fixture()).has("$contentLanguages")).isFalse();
        row.contentLanguageInputs(Map.of("description", new ContentWriteContext.SourceInput("de", 3)));
        assertThat(mapper.<com.fasterxml.jackson.databind.JsonNode>valueToTree(row)).isNotEqualTo(wire);
    }
    @Test void rejectsInvalidLanguageMetadataWithoutTraversingBusinessJson() throws Exception {
        assertThatThrownBy(() -> mapper.readValue("{\"$contentLanguages\":{\"description\":{\"languageTag\":\"bad_tag\"}}}", Fixture.class))
                .isInstanceOf(com.fasterxml.jackson.databind.exc.ValueInstantiationException.class).hasMessageContaining("Invalid source language tag");
        assertThatThrownBy(() -> mapper.readValue("{\"$contentLanguages\":{\"description\":null}}", Fixture.class))
                .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class).hasMessageContaining("field and source input");
        var row = mapper.readValue("{\"extra\":{\"$contentLanguages\":{\"some\":\"business JSON\"}}}", Fixture.class);
        assertThat(ContentWriteContext.inputs(row)).isEmpty();
        assertThat(row.extra).containsKey("$contentLanguages");
    }
    public static class Fixture extends StandardEntity {
        public String description;
        public List<Fixture> lines;
        public Map<String, Object> extra;
    }
}
