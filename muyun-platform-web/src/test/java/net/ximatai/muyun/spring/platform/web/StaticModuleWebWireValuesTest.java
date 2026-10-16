package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.web.WebListQuerySummaryItem;
import net.ximatai.muyun.spring.web.WebPageResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StaticModuleWebWireValuesTest {
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
