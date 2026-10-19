package net.ximatai.muyun.spring.platform.code;

import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.StandardBusinessService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CodeValueMappingService extends StandardBusinessService<CodeValueMapping> implements
        SoftDeleteAbility<CodeValueMapping>,
        EnableAbility<CodeValueMapping>,
        SortAbility<CodeValueMapping> {
    public static final String MODULE_ALIAS = "platform.code_value_mapping";

    public CodeValueMappingService(BaseDao<CodeValueMapping, String> mappingDao) {
        super(MODULE_ALIAS, CodeValueMapping.class, mappingDao);
    }

    public List<CodeValueMapping> selectBySegmentId(String segmentId) {
        return sortedList(Criteria.of().eq("segmentId", segmentId));
    }

    @Override
    protected void validateBeforeSave(CodeValueMapping mapping) {
        if (mapping.getDefaultMapping() == null) {
            mapping.setDefaultMapping(Boolean.FALSE);
        }
        if (!Boolean.TRUE.equals(mapping.getDefaultMapping())
                && (mapping.getSourceValue() == null || mapping.getSourceValue().isBlank())) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.code-value-mapping.code-value-mapping-requires-sourcevalue",
                    "Code value mapping requires sourceValue"));
        }
        if (mapping.getTargetValue() == null) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.code-value-mapping.code-value-mapping-requires-targetvalue",
                    "Code value mapping requires targetValue"));
        }
        if (mapping.getTitle() == null || mapping.getTitle().isBlank()) {
            mapping.setTitle(mapping.getTargetValue());
        }
        requireRuleScope(mapping);
        if (mapping.getOrgScopeType() == null) {
            mapping.setOrgScopeType(CodeOrgScopeType.GLOBAL);
        }
    }
    static void requireRuleScope(CodeValueMapping mapping) {
        if (mapping.getOrgScopeType() == CodeOrgScopeType.ORG || mapping.getOrgScopeId() != null && !mapping.getOrgScopeId().isBlank())
            throw new IllegalArgumentException("Mapping rows use their owning rule's organization scope");
    }

}
