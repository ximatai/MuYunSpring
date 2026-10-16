package net.ximatai.muyun.spring.platform.duplicate;

import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicEntityDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicFieldDescriptor;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class RecordDuplicateCheckService {
    private static final PageRequest MATCH_PAGE = PageRequest.of(1, 10);

    private final RecordDuplicateRuleService ruleService;
    private final DynamicRecordService recordService;

    public RecordDuplicateCheckService(RecordDuplicateRuleService ruleService,
                                       DynamicRecordService recordService) {
        this.ruleService = ruleService;
        this.recordService = recordService;
    }

    public RecordDuplicateCheckResult check(String moduleAlias,
                                            String actionCode,
                                            String currentRecordId,
                                            Map<String, ?> values) {
        RecordDuplicateRule rule = ruleService.requireEnabledRule(moduleAlias, actionCode);
        List<String> fieldNames = ruleService.fieldNames(rule);
        if (fieldNames.isEmpty()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.record-duplicate-check.duplicate-rule-requires-fields",
                    "duplicate rule requires fields: {value1}"), Map.of("value1", String.valueOf(actionCode)));
        }
        DynamicEntityDescriptor entity = recordService.mainEntity(moduleAlias).describe();
        validateFields(entity, fieldNames);
        Criteria criteria = Criteria.of();
        for (String fieldName : fieldNames) {
            Object value = values == null ? null : values.get(fieldName);
            if (value == null || value instanceof String text && text.isBlank()) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.record-duplicate-check.duplicate-check-requires-field-value",
                        "duplicate check requires field value: {value1}"), Map.of("value1", String.valueOf(fieldName)));
            }
            criteria.eq(fieldName, value);
        }
        List<RecordDuplicateMatch> matches = recordService
                .pageForAction(moduleAlias, entity.entityAlias(), actionCode, criteria, MATCH_PAGE)
                .getRecords()
                .stream()
                .filter(record -> currentRecordId == null || currentRecordId.isBlank()
                        || !currentRecordId.equals(record.getId()))
                .map(record -> match(record, fieldNames))
                .toList();
        return new RecordDuplicateCheckResult(rule.getId(), rule.getActionCode(), fieldNames,
                !matches.isEmpty(), matches);
    }

    private void validateFields(DynamicEntityDescriptor entity, List<String> fieldNames) {
        Set<String> fields = entity.fields().stream()
                .map(DynamicFieldDescriptor::fieldName)
                .collect(Collectors.toSet());
        for (String fieldName : fieldNames) {
            if (!fields.contains(fieldName)) {
                throw PlatformErrors.badRequestText(TextReference.code(
                        "platform.rejection.record-duplicate-check.duplicate-rule-field-does-not-exist",
                        "duplicate rule field does not exist: {value1}"), Map.of("value1", String.valueOf(fieldName)));
            }
        }
    }

    private RecordDuplicateMatch match(DynamicRecord record, List<String> fieldNames) {
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, Object> recordValues = record.getValues();
        for (String fieldName : fieldNames) {
            values.put(fieldName, recordValues.get(fieldName));
        }
        return new RecordDuplicateMatch(record.getId(), record.getVersion(), values);
    }
}
