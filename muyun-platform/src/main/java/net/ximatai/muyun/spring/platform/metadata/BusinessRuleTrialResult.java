package net.ximatai.muyun.spring.platform.metadata;

import java.util.List;
import java.util.Map;

public record BusinessRuleTrialResult(BusinessRulePreview preview, Map<String, Object> values,
                                      List<String> changedFields, List<BusinessRuleIssue> errors,
                                      Map<String, List<Map<String, Object>>> children) {
    public BusinessRuleTrialResult(BusinessRulePreview preview, Map<String, Object> values,
            List<String> changedFields, List<BusinessRuleIssue> errors) {
        this(preview, values, changedFields, errors, Map.of());
    }
}
