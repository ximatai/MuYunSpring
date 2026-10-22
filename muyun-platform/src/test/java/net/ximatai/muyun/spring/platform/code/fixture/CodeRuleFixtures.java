package net.ximatai.muyun.spring.platform.code.fixture;

import net.ximatai.muyun.spring.platform.code.*;

import java.util.List;

/** Shared device-type mapping rule for renderer and persistence contracts. */
public final class CodeRuleFixtures {
    private CodeRuleFixtures() { }

    public static CodeRule deviceTypeMapping() {
        var mapping = new CodeRuleSegment(); mapping.setSegmentType(CodeSegmentType.VALUE_MAPPING);
        mapping.setSourceRef("deviceType"); mapping.setSequenceBasis(true);
        var glasses = new CodeValueMapping(); glasses.setSourceValue("mr_glasses"); glasses.setTargetValue("GL-");
        var helmet = new CodeValueMapping(); helmet.setSourceValue("safety_helmet"); helmet.setTargetValue("HM-");
        mapping.setMappings(List.of(glasses, helmet));
        var sequence = new CodeRuleSegment(); sequence.setSegmentType(CodeSegmentType.SEQUENCE); sequence.setLength(6);
        var policy = new CodeSequencePolicy(); policy.setStartValue(1L); policy.setSequenceLength(6);
        var rule = new CodeRule(); rule.setSegments(List.of(mapping, sequence)); rule.setSequencePolicy(policy);
        return rule;
    }
}
