package net.ximatai.muyun.spring.platform.metadata;

import java.util.List;

public record BusinessRuleApplyResult(BusinessRuleGovernanceSnapshot snapshot, BusinessRulePreview preview,
                                      List<String> activatedModules) {
}
