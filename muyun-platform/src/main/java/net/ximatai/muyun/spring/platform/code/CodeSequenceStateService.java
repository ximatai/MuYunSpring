package net.ximatai.muyun.spring.platform.code;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.DisablePlatformOperations;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;

@Service
@DisablePlatformOperations({PlatformAction.CREATE, PlatformAction.UPDATE, PlatformAction.DELETE})
public class CodeSequenceStateService extends AbstractAbilityService<CodeSequenceState> implements
        SoftDeleteAbility<CodeSequenceState>,
        QueryAbility<CodeSequenceState> {
    public static final String MODULE_ALIAS = "platform.code_sequence_state";

    private final CodeSequenceAllocator sequenceAllocator;

    public CodeSequenceStateService(BaseDao<CodeSequenceState, String> stateDao) {
        this(stateDao, List.of());
    }

    @Autowired
    public CodeSequenceStateService(BaseDao<CodeSequenceState, String> stateDao,
                                    List<CodeSequenceAllocator> sequenceAllocators) {
        super(MODULE_ALIAS, CodeSequenceState.class, stateDao);
        if (sequenceAllocators != null && sequenceAllocators.size() > 1) {
            throw new PlatformException("Only one CodeSequenceAllocator can be active");
        }
        this.sequenceAllocator = sequenceAllocators == null || sequenceAllocators.isEmpty()
                ? null
                : sequenceAllocators.getFirst();
    }

    public Long allocateNextValue(String ruleId,
                                  String basisKey,
                                  String periodKey,
                                  CodeSequencePolicy policy) {
        if (policy == null) {
            throw new PlatformException("Code sequence allocation requires sequencePolicy");
        }
        String effectiveBasisKey = normalizeBucket(basisKey);
        String effectivePeriodKey = normalizeBucket(periodKey);
        if (sequenceAllocator != null) {
            return sequenceAllocator.allocateNextValue(new CodeSequenceAllocation(
                    ruleId,
                    effectiveBasisKey,
                    effectivePeriodKey,
                    TenantContext.currentTenantId().orElse(null),
                    startValue(policy),
                    stepValue(policy),
                    policy.getSequenceLength(),
                    policy.getMaxValue(),
                    policy.getOverflowPolicy()
            ));
        }
        return allocateNextValueWithDao(ruleId, effectiveBasisKey, effectivePeriodKey, policy);
    }

    private synchronized Long allocateNextValueWithDao(String ruleId,
                                                       String effectiveBasisKey,
                                                       String effectivePeriodKey,
                                                       CodeSequencePolicy policy) {
        CodeSequenceState state = selectState(ruleId, effectiveBasisKey, effectivePeriodKey);
        long current = state == null ? initialCurrentValue(policy) : state.getCurrentValue();
        long next = nextValue(current, policy);
        if (state == null) {
            state = new CodeSequenceState();
            state.setRuleId(ruleId);
            state.setBasisKey(effectiveBasisKey);
            state.setPeriodKey(effectivePeriodKey);
            state.setCurrentValue(next);
            insert(state);
        } else {
            state.setCurrentValue(next);
            update(state);
        }
        return next;
    }

    public CodeSequenceState selectState(String ruleId, String basisKey, String periodKey) {
        return findOne(Criteria.of()
                .eq("ruleId", ruleId)
                .eq("basisKey", normalizeBucket(basisKey))
                .eq("periodKey", normalizeBucket(periodKey)));
    }

    public List<CodeSequenceState> selectByRuleId(String ruleId, int limit) {
        if (ruleId == null || ruleId.isBlank()) {
            return List.of();
        }
        return list(Criteria.of().eq("ruleId", ruleId), PageRequest.of(1, Math.max(1, limit)),
                net.ximatai.muyun.database.core.orm.Sort.desc("updatedAt"),
                net.ximatai.muyun.database.core.orm.Sort.desc("createdAt"));
    }

    public CodeSequenceState setCurrentValue(String ruleId, String basisKey, String periodKey, Long currentValue) {
        if (currentValue == null || currentValue < 0) {
            throw new PlatformException("Code sequence currentValue must not be negative");
        }
        String effectiveBasisKey = normalizeBucket(basisKey);
        String effectivePeriodKey = normalizeBucket(periodKey);
        CodeSequenceState state = selectState(ruleId, effectiveBasisKey, effectivePeriodKey);
        if (state == null) {
            state = new CodeSequenceState();
            state.setRuleId(ruleId);
            state.setBasisKey(effectiveBasisKey);
            state.setPeriodKey(effectivePeriodKey);
            state.setCurrentValue(currentValue);
            insert(state);
            return state;
        }
        state.setCurrentValue(currentValue);
        update(state);
        return select(state.getId());
    }

    public long previewNextValue(Long currentValue, CodeSequencePolicy policy) {
        if (policy == null) {
            throw new PlatformException("Code sequence preview requires sequencePolicy");
        }
        return nextValue(currentValue == null ? initialCurrentValue(policy) : currentValue, policy);
    }

    public String periodKey(CodeSequencePolicy policy, LocalDateTime at) {
        if (policy == null || policy.getResetPolicy() == null || policy.getResetPolicy() == CodeSequenceResetPolicy.NONE) {
            return CodeSequenceState.DEFAULT_BUCKET;
        }
        LocalDateTime effectiveAt = at == null ? LocalDateTime.now() : at;
        return switch (policy.getResetPolicy()) {
            case NONE -> CodeSequenceState.DEFAULT_BUCKET;
            case YEAR -> DateTimeFormatter.ofPattern("yyyy").format(effectiveAt);
            case MONTH -> DateTimeFormatter.ofPattern("yyyyMM").format(effectiveAt);
            case DAY -> DateTimeFormatter.ofPattern("yyyyMMdd").format(effectiveAt);
        };
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, CodeSequenceState.class, java.util.List.of("id", "ruleId", "basisKey", "periodKey", "currentValue"),
                net.ximatai.muyun.database.core.orm.Sort.asc("ruleId"),
                net.ximatai.muyun.database.core.orm.Sort.asc("basisKey"),
                net.ximatai.muyun.database.core.orm.Sort.asc("periodKey"));
    }

    @Override
    public void beforeInsert(CodeSequenceState state) {
        normalizeAndValidate(state);
    }

    @Override
    public void beforeUpdate(CodeSequenceState state) {
        normalizeAndValidate(state);
    }

    private void normalizeAndValidate(CodeSequenceState state) {
        state.setBasisKey(normalizeBucket(state.getBasisKey()));
        state.setPeriodKey(normalizeBucket(state.getPeriodKey()));
        if (state.getCurrentValue() == null) {
            state.setCurrentValue(0L);
        }
        rejectDuplicate(state, Criteria.of()
                        .eq("ruleId", state.getRuleId())
                        .eq("basisKey", state.getBasisKey())
                        .eq("periodKey", state.getPeriodKey()),
                "Code sequence state already exists for bucket: "
                        + state.getRuleId() + "/" + state.getBasisKey() + "/" + state.getPeriodKey());
    }

    private String normalizeBucket(String value) {
        return value == null || value.isBlank() ? CodeSequenceState.DEFAULT_BUCKET : value;
    }

    private long initialCurrentValue(CodeSequencePolicy policy) {
        return startValue(policy) - stepValue(policy);
    }

    private long startValue(CodeSequencePolicy policy) {
        return policy.getStartValue() == null ? 1L : policy.getStartValue();
    }

    private long stepValue(CodeSequencePolicy policy) {
        return policy.getStepValue() == null ? 1L : policy.getStepValue();
    }

    private long nextValue(long current, CodeSequencePolicy policy) {
        long next = current + stepValue(policy);
        if (!isOverflow(next, policy)) {
            return next;
        }
        if (policy.getOverflowPolicy() == CodeSequenceOverflowPolicy.RESET) {
            return startValue(policy);
        }
        throw new PlatformException("Code sequence overflow for rule: " + policy.getRuleId());
    }

    private boolean isOverflow(long next, CodeSequencePolicy policy) {
        if (policy.getMaxValue() != null && next > policy.getMaxValue()) {
            return true;
        }
        if (policy.getSequenceLength() != null && policy.getSequenceLength() > 0) {
            long maxByLength = (long) Math.pow(10, policy.getSequenceLength()) - 1L;
            return next > maxByLength;
        }
        return false;
    }
}
