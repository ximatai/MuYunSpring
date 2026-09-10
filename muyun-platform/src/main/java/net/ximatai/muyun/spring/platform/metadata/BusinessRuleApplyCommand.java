package net.ximatai.muyun.spring.platform.metadata;

import java.util.List;

public record BusinessRuleApplyCommand(
        List<BusinessRuleProposal> rules,
        String baselineFingerprint,
        String proposalFingerprint
) {
}
