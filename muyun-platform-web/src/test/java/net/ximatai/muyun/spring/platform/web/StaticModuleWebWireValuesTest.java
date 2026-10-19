package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.web.WebListQuerySummaryItem;
import net.ximatai.muyun.spring.web.WebPageResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StaticModuleWebWireValuesTest {
    @Test void rendersScientificDecimalsAsExactPlainTextIncludingNestedRecordsAndPages() {
        var mapper = new ObjectMapper();
        var original = Map.of("amount", new java.math.BigDecimal("1.2E+2"),
                "tiny", new java.math.BigDecimal("1.234567890123456789E-8"),
                "lines", List.of(Map.of("amount", new java.math.BigDecimal("9.007199254740993123E+20"))));
        var fields = Map.of("amount", FieldValueType.DECIMAL, "tiny", FieldValueType.DECIMAL,
                "lines.amount", FieldValueType.DECIMAL);
        var output = mapper.valueToTree(StaticModuleWebWireValues.adapt(original, fields, mapper));
        assertThat(output.path("amount").asText()).isEqualTo("120");
        assertThat(output.path("tiny").asText()).isEqualTo("0.00000001234567890123456789");
        assertThat(output.at("/lines/0/amount").asText()).isEqualTo("900719925474099312300");
        var page = new WebPageResponse<>(List.of(original), 1, 1, 20, 1, true, null);
        assertThat(mapper.valueToTree(StaticModuleWebWireValues.adaptPage(page, fields, mapper))
                .at("/records/0/amount").asText()).isEqualTo("120");
        assertThat(original.get("amount")).isEqualTo(new java.math.BigDecimal("1.2E+2"));
    }

    @Test
    void preservesListQuerySummariesWhenAdaptingRecordsToWireValues() {
        WebPageResponse<Map<String, Object>> response = new WebPageResponse<>(
                List.of(Map.of("amount", 12L)), 1, 1, 20, 1, true, null,
                List.of(new WebListQuerySummaryItem("onlineUsers", 3)),
                new net.ximatai.muyun.spring.web.WebBusinessContentQueryStatus("en", 1,
                        Map.of("record", Map.of("title", "Equipment"))));

        WebPageResponse<?> adapted = StaticModuleWebWireValues.adaptPage(response,
                Map.of("amount", FieldValueType.LONG), new ObjectMapper());

        assertThat(adapted.records()).singleElement().isEqualTo(Map.of("amount", "12"));
        assertThat(adapted.summaries()).containsExactly(new WebListQuerySummaryItem("onlineUsers", 3));
        assertThat(adapted.businessContent()).isEqualTo(response.businessContent());
        assertThat(adapted.withSummaries(List.of()).businessContent()).isEqualTo(response.businessContent());
    }
}
