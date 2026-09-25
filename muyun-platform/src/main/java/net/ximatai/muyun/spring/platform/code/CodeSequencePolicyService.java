package net.ximatai.muyun.spring.platform.code;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import org.springframework.stereotype.Service;

@Service
public class CodeSequencePolicyService extends AbstractAbilityService<CodeSequencePolicy> implements
        SoftDeleteAbility<CodeSequencePolicy> {
    public static final String MODULE_ALIAS = "platform.code_sequence_policy";

    public CodeSequencePolicyService(BaseDao<CodeSequencePolicy, String> policyDao) {
        super(MODULE_ALIAS, CodeSequencePolicy.class, policyDao);
    }

    public CodeSequencePolicy selectByRuleId(String ruleId) {
        return findOne(Criteria.of().eq("ruleId", ruleId));
    }

    @Override
    public void beforeInsert(CodeSequencePolicy policy) {
        normalizeAndValidate(policy);
    }

    @Override
    public void beforeUpdate(CodeSequencePolicy policy) {
        normalizeAndValidate(policy);
    }

    private void normalizeAndValidate(CodeSequencePolicy policy) {
        if (policy.getStartValue() == null) {
            policy.setStartValue(1L);
        }
        if (policy.getStepValue() == null) {
            policy.setStepValue(1L);
        }
        if (policy.getStepValue() <= 0) {
            throw new PlatformException("Code sequence policy stepValue must be positive");
        }
        if (policy.getResetPolicy() == null) {
            policy.setResetPolicy(CodeSequenceResetPolicy.NONE);
        }
        if (policy.getOverflowPolicy() == null) {
            policy.setOverflowPolicy(CodeSequenceOverflowPolicy.ERROR);
        }
        rejectDuplicate(policy, Criteria.of().eq("ruleId", policy.getRuleId()),
                "Code rule can only have one sequence policy: " + policy.getRuleId());
    }
}
