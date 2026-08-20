package net.ximatai.muyun.spring.dynamic.metadata;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamicFieldValueSupportTest {
    @Test
    void shouldKeepBusinessDateAsLocalDateWithoutTimeZoneConversion() {
        Object value = DynamicFieldValueSupport.normalize(FieldType.DATE, "2026-06-17");

        assertThat(value).isEqualTo(LocalDate.of(2026, 6, 17));
    }

    @Test
    void shouldAcceptOnlyUtcSecondInstantForTimestampFields() {
        assertThat(DynamicFieldValueSupport.normalize(FieldType.TIMESTAMP, "2026-06-17T01:02:03Z"))
                .isEqualTo(Instant.parse("2026-06-17T01:02:03Z"));
        assertThat(DynamicFieldValueSupport.normalize(
                FieldType.ZONED_TIMESTAMP,
                OffsetDateTime.of(2026, 6, 17, 1, 2, 3, 0, ZoneOffset.UTC)))
                .isEqualTo(Instant.parse("2026-06-17T01:02:03Z"));

        assertThatThrownBy(() -> DynamicFieldValueSupport.normalize(FieldType.TIMESTAMP, "2026-06-17T01:02:03+08:00"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timestamp");
        assertThatThrownBy(() -> DynamicFieldValueSupport.normalize(FieldType.TIMESTAMP, "2026-06-17T01:02:03.100Z"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timestamp");
        assertThatThrownBy(() -> DynamicFieldValueSupport.normalize(
                FieldType.TIMESTAMP,
                Instant.parse("2026-06-17T01:02:03.100Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("second precision");
    }

    @Test
    void shouldNormalizeOnlyIanaTimeZoneIds() {
        assertThat(DynamicFieldValueSupport.normalizeTimeZone("Asia/Shanghai"))
                .isEqualTo("Asia/Shanghai");

        assertThatThrownBy(() -> DynamicFieldValueSupport.normalizeTimeZone("+08:00"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IANA");
        assertThatThrownBy(() -> DynamicFieldValueSupport.normalizeTimeZone("Z"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("IANA");
    }

    @Test
    void shouldRejectLosslessNumericWireTextOutsideWebAdapter() {
        assertThatThrownBy(() -> DynamicFieldValueSupport.normalize(FieldType.LONG, "9007199254740993"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LONG");
        assertThatThrownBy(() -> DynamicFieldValueSupport.normalize(FieldType.DECIMAL, "0.123456789012345678"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DECIMAL");
    }
}
