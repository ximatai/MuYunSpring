package net.ximatai.muyun.spring.platform.web;

import java.math.BigDecimal;
import java.util.List;

/** Wire value of the finite, one-field grouped list summary. */
public record WebGroupedListQuerySummaryValue(String kind, List<Row> rows) {
    public static final String KIND = "GROUPED";

    public WebGroupedListQuerySummaryValue {
        kind = KIND;
        rows = rows == null ? List.of() : List.copyOf(rows);
    }

    public record Row(Object value, String label, long count, BigDecimal sum) {
        public Row {
            label = label == null || label.isBlank() ? "记录不可用" : label;
        }
    }
}
