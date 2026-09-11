package net.ximatai.muyun.spring.platform.metadata;
import java.util.List;
import net.ximatai.muyun.spring.platform.ui.UiControlRule;

public record BusinessRuleApplyCommand(List<BusinessRuleProposal> rules, String baselineFingerprint,
        String proposalFingerprint, List<UiControlRule> uiRules, String uiBaselineFingerprint) {
    public BusinessRuleApplyCommand(List<BusinessRuleProposal> rules, String baselineFingerprint, String proposalFingerprint) {
        this(rules, baselineFingerprint, proposalFingerprint, null, null);
    }
}
