package net.ximatai.muyun.spring.platform.code;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
public class CodeRuleSegmentService extends AbstractAbilityService<CodeRuleSegment> implements
        SoftDeleteAbility<CodeRuleSegment>,
        SortAbility<CodeRuleSegment> {
    public static final String MODULE_ALIAS = "platform.code_rule_segment";
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);

    public CodeRuleSegmentService(BaseDao<CodeRuleSegment, String> segmentDao) {
        super(MODULE_ALIAS, CodeRuleSegment.class, segmentDao);
    }

    public List<CodeRuleSegment> selectByRuleId(String ruleId) {
        return list(Criteria.of().eq("ruleId", ruleId), ALL, Sort.asc("sortOrder"));
    }

    @Override
    public void beforeInsert(CodeRuleSegment segment) {
        normalizeAndValidate(segment);
    }

    @Override
    public void beforeUpdate(CodeRuleSegment segment) {
        normalizeAndValidate(segment);
    }

    private void normalizeAndValidate(CodeRuleSegment segment) {
        if (segment.getSegmentType() != null && (segment.getTitle() == null || segment.getTitle().isBlank())) {
            segment.setTitle(defaultTitle(segment));
        }
        if (segment.getPadMode() == null) {
            segment.setPadMode(CodePadMode.NONE);
        }
        if (segment.getNullPolicy() == null) {
            segment.setNullPolicy(CodeNullPolicy.ERROR);
        }
        if (segment.getTruncatePolicy() == null) {
            segment.setTruncatePolicy(CodeTruncatePolicy.NONE);
        }
        if (segment.getSequenceBasis() == null) {
            segment.setSequenceBasis(Boolean.FALSE);
        }
    }

    private String defaultTitle(CodeRuleSegment segment) {
        if (segment.getSourceRef() != null && !segment.getSourceRef().isBlank()) {
            return segment.getSourceRef();
        }
        if (segment.getSegmentType() == CodeSegmentType.CONSTANT && segment.getFixedValue() != null
                && !segment.getFixedValue().isBlank()) {
            return segment.getFixedValue();
        }
        return segment.getSegmentType().name();
    }
}
