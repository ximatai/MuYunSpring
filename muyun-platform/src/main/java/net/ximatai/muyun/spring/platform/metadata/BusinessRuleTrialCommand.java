package net.ximatai.muyun.spring.platform.metadata;

import java.util.List;
import java.util.Map;

public record BusinessRuleTrialCommand(List<BusinessRuleProposal> rules, Map<String, Object> sampleValues) {
}
