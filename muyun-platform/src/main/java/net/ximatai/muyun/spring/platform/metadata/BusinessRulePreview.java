package net.ximatai.muyun.spring.platform.metadata;

import java.util.List;

public record BusinessRulePreview(BusinessRuleGovernanceSnapshot snapshot, String proposalFingerprint,
                                  List<String> executionOrder, List<BusinessRuleIssue> errors) {
    public boolean valid() { return errors == null || errors.isEmpty(); }
}
