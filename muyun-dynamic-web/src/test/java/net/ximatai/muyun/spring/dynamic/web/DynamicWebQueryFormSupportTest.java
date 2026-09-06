package net.ximatai.muyun.spring.dynamic.web;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.dynamic.metadata.DynamicQueryOperator;
import net.ximatai.muyun.spring.platform.web.ModuleQueryFormField;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamicWebQueryFormSupportTest {
    @Test
    void shouldInterpretDeclaredRangeKeysOnceAndPreserveTimeZone() {
        ModuleQueryFormField field = new ModuleQueryFormField("submittedAt",
                ModuleQueryFormField.Mode.BETWEEN, List.of("start", "end"));

        var condition = DynamicWebQueryFormSupport.condition(field, Map.of(
                "start", "2026-01-01T00:00:00", "end", "2026-01-31T23:59:59",
                "timeZone", "Asia/Shanghai"));

        assertThat(condition.operator()).isEqualTo(DynamicQueryOperator.BETWEEN);
        assertThat(condition.values()).isEqualTo(List.of("2026-01-01T00:00:00", "2026-01-31T23:59:59"));
        assertThat(condition.timeZone()).isEqualTo("Asia/Shanghai");
    }

    @Test
    void shouldRejectIncompleteRangesAndIgnoreEmptyValues() {
        ModuleQueryFormField field = new ModuleQueryFormField("submittedAt",
                ModuleQueryFormField.Mode.BETWEEN, List.of("beginAt", "finishAt"));

        assertThatThrownBy(() -> DynamicWebQueryFormSupport.condition(field,
                Map.of("beginAt", "2026-01-01", "finishAt", " ")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("requires start and end values");
        assertThatThrownBy(() -> DynamicWebQueryFormSupport.condition(field, List.of("2026-01-01")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("requires exactly two values");
        assertThat(DynamicWebQueryFormSupport.condition(field,
                Map.of("beginAt", "", "finishAt", " "))).isNull();
    }
}
