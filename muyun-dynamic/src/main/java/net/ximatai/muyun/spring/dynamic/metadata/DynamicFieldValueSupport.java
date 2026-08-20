package net.ximatai.muyun.spring.dynamic.metadata;

import net.ximatai.muyun.spring.common.time.PlatformTimeService;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.regex.Pattern;

public final class DynamicFieldValueSupport {
    private static final Pattern UTC_INSTANT_SECONDS = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z"
    );
    private static final DateTimeFormatter UTC_SECOND_FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC);

    private DynamicFieldValueSupport() {
    }

    public static Object normalize(FieldType type, Object value) {
        if (value == null) {
            return null;
        }
        return switch (type) {
            case STRING, TEXT -> requireType(type, value, String.class);
            case INTEGER -> requireType(type, value, Integer.class);
            case LONG -> longValue(value);
            case BOOLEAN -> requireType(type, value, Boolean.class);
            case TIMESTAMP, ZONED_TIMESTAMP -> timestampValue(value);
            case DATE -> dateValue(value);
            case DECIMAL -> decimalValue(value);
            case JSON -> value;
        };
    }

    public static Object normalizeLoaded(FieldType type, Object value) {
        if (value == null) {
            return null;
        }
        return switch (type) {
            case TIMESTAMP, ZONED_TIMESTAMP -> loadedTimestampValue(value);
            default -> normalize(type, value);
        };
    }

    public static Object parseDefaultValue(FieldType type, String value) {
        if (value == null) {
            return null;
        }
        return switch (type) {
            case STRING, TEXT, JSON -> value;
            case INTEGER -> Integer.valueOf(value);
            case LONG -> Long.valueOf(value);
            case BOOLEAN -> parseBoolean(value);
            case DECIMAL -> new BigDecimal(value);
            case TIMESTAMP, ZONED_TIMESTAMP -> timestampValue(value);
            case DATE -> dateValue(value);
        };
    }

    public static String normalizeTimeZone(Object value) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("timeZone must be an IANA ZoneId");
        }
        return PlatformTimeService.requireIanaZoneId(text).getId();
    }

    public static String formatUtcInstant(Instant value) {
        return UTC_SECOND_FORMATTER.format(requireSecondPrecision(value));
    }

    private static Object requireType(FieldType type, Object value, Class<?> requiredType) {
        if (!requiredType.isInstance(value)) {
            throw new IllegalArgumentException("invalid value type for dynamic field type: " + type);
        }
        return value;
    }

    private static Instant timestampValue(Object value) {
        if (value instanceof Instant instant) {
            return requireSecondPrecision(instant);
        }
        if (value instanceof OffsetDateTime offsetDateTime && ZoneOffset.UTC.equals(offsetDateTime.getOffset())) {
            return requireSecondPrecision(offsetDateTime.toInstant());
        }
        if (value instanceof String text) {
            String trimmed = text.trim();
            if (!UTC_INSTANT_SECONDS.matcher(trimmed).matches()) {
                throw new IllegalArgumentException("timestamp must be a UTC second instant");
            }
            return requireSecondPrecision(Instant.parse(trimmed));
        }
        throw new IllegalArgumentException("invalid value type for dynamic field type: " + FieldType.TIMESTAMP);
    }

    private static Instant loadedTimestampValue(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) {
            return requireSecondPrecision(timestamp.toInstant());
        }
        if (value instanceof LocalDateTime localDateTime) {
            return requireSecondPrecision(localDateTime.toInstant(ZoneOffset.UTC));
        }
        if (value instanceof Date date) {
            return requireSecondPrecision(date.toInstant());
        }
        return timestampValue(value);
    }

    private static LocalDate dateValue(Object value) {
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        if (value instanceof String text) {
            return LocalDate.parse(text);
        }
        throw new IllegalArgumentException("invalid value type for dynamic field type: " + FieldType.DATE);
    }

    private static Long longValue(Object value) {
        if (value instanceof Long longValue) {
            return longValue;
        }
        if (value instanceof String text) {
            try {
                return Long.valueOf(text);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("invalid value type for dynamic field type: " + FieldType.LONG,
                        exception);
            }
        }
        throw new IllegalArgumentException("invalid value type for dynamic field type: " + FieldType.LONG);
    }

    private static Object decimalValue(Object value) {
        if (value instanceof BigDecimal) {
            return value;
        }
        if (value instanceof String text) {
            try {
                return new BigDecimal(text);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("invalid value type for dynamic field type: " + FieldType.DECIMAL,
                        exception);
            }
        }
        if (value instanceof Number) {
            return value;
        }
        throw new IllegalArgumentException("invalid value type for dynamic field type: " + FieldType.DECIMAL);
    }

    private static Boolean parseBoolean(String value) {
        if ("true".equalsIgnoreCase(value)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(value)) {
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("boolean defaultValue must be true or false");
    }

    private static Instant requireSecondPrecision(Instant value) {
        Instant truncated = value.truncatedTo(ChronoUnit.SECONDS);
        if (!value.equals(truncated)) {
            throw new IllegalArgumentException("timestamp must use second precision");
        }
        return value;
    }
}
