package net.ximatai.muyun.spring.platform.metadata;

import java.util.List;
import java.util.Map;

public record BusinessRuleTrialResult(BusinessRulePreview preview, Map<String, Object> values,
                                      List<String> changedFields, List<BusinessRuleIssue> errors) {
}
