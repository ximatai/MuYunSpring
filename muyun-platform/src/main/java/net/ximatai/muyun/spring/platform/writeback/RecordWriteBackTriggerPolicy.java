package net.ximatai.muyun.spring.platform.writeback;

import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordMutationEvent;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;

final class RecordWriteBackTriggerPolicy {
    private RecordWriteBackTriggerPolicy() {
    }

    static EnumSet<RecordWriteBackTriggerMode> modes(RecordWriteBackRule rule) {
        if (rule == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.record-write-back-trigger-policy.record-write-back-rule-must-not-be-null",
                    "Record write-back rule must not be null"));
        }
        String modes = rule.getTriggerModes();
        if (modes == null || modes.isBlank()) {
            RecordWriteBackTriggerMode mode = rule.getTriggerMode() == null
                    ? RecordWriteBackTriggerMode.ALWAYS
                    : rule.getTriggerMode();
            return EnumSet.of(mode);
        }
        EnumSet<RecordWriteBackTriggerMode> parsed = EnumSet.noneOf(RecordWriteBackTriggerMode.class);
        for (String token : modes.split(",")) {
            String text = token.trim();
            if (!text.isBlank()) {
                try {
                    parsed.add(RecordWriteBackTriggerMode.valueOf(text));
                } catch (IllegalArgumentException e) {
                    throw PlatformErrors.badRequestText(TextReference.code(
                            "platform.rejection.record-write-back-trigger-policy.record-write-back-trigger-mode-is-invalid",
                            "Record write-back trigger mode is invalid: {value1}"), Map.of("value1", String.valueOf(text)));
                }
            }
        }
        if (parsed.isEmpty()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.record-write-back-trigger-policy.record-write-back-rule-requires-at-least-one-trigger-mode",
                    "Record write-back rule requires at least one trigger mode"));
        }
        if (parsed.contains(RecordWriteBackTriggerMode.ALWAYS) && parsed.size() > 1) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.record-write-back-trigger-policy.record-write-back-always-trigger-mode-cannot-combine-with-state-modes",
                    "Record write-back ALWAYS trigger mode cannot combine with state modes"));
        }
        return parsed;
    }

    static String serialized(EnumSet<RecordWriteBackTriggerMode> modes) {
        return String.join(",", modes.stream().map(Enum::name).toList());
    }

    static Optional<RecordWriteBackTriggerMode> matchedMode(DynamicRecordMutationEvent event,
                                                            RecordWriteBackRule rule) {
        EnumSet<RecordWriteBackTriggerMode> modes = modes(rule);
        if (modes.contains(RecordWriteBackTriggerMode.ALWAYS)) {
            return Optional.of(RecordWriteBackTriggerMode.ALWAYS);
        }
        boolean beforeEffective = triggerValueMatches(event.beforeRecord(), rule.getTriggerField(), rule.getTriggerValue());
        boolean afterEffective = triggerValueMatches(event.afterRecord(), rule.getTriggerField(), rule.getTriggerValue());
        if (modes.contains(RecordWriteBackTriggerMode.ON_ENTER) && !beforeEffective && afterEffective) {
            return Optional.of(RecordWriteBackTriggerMode.ON_ENTER);
        }
        if (modes.contains(RecordWriteBackTriggerMode.ON_EXIT) && beforeEffective && !afterEffective) {
            return Optional.of(RecordWriteBackTriggerMode.ON_EXIT);
        }
        if (modes.contains(RecordWriteBackTriggerMode.ON_CHANGE_WHILE_EFFECTIVE)
                && beforeEffective && afterEffective && hasSourceFieldChange(event, rule)) {
            return Optional.of(RecordWriteBackTriggerMode.ON_CHANGE_WHILE_EFFECTIVE);
        }
        return Optional.empty();
    }

    private static boolean hasSourceFieldChange(DynamicRecordMutationEvent event, RecordWriteBackRule rule) {
        return rule.getFieldRules().stream()
                .filter(fieldRule -> fieldRule.getSourceType() == RecordWriteBackFieldSourceType.FIELD)
                .map(RecordWriteBackFieldRule::getSourceField)
                .filter(Objects::nonNull)
                .distinct()
                .anyMatch(field -> !Objects.equals(recordValue(event.beforeRecord(), field),
                        recordValue(event.afterRecord(), field)));
    }

    private static boolean triggerValueMatches(DynamicRecord record, String triggerField, String triggerValue) {
        if (record == null) {
            return false;
        }
        Object value = recordValue(record, triggerField);
        return value != null && Objects.equals(String.valueOf(value), triggerValue);
    }

    private static Object recordValue(DynamicRecord record, String field) {
        if (record == null) {
            return null;
        }
        return record.getValue(field);
    }
}
