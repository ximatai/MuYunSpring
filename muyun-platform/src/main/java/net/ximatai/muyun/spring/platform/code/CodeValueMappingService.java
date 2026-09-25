package net.ximatai.muyun.spring.platform.code;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.StandardBusinessService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
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
            throw new PlatformException("Code value mapping requires sourceValue");
        }
        if (mapping.getTargetValue() == null) {
            throw new PlatformException("Code value mapping requires targetValue");
        }
        if (mapping.getTitle() == null || mapping.getTitle().isBlank()) {
            mapping.setTitle(mapping.getTargetValue());
        }
        if (mapping.getOrgScopeType() == null) {
            mapping.setOrgScopeType(CodeOrgScopeType.GLOBAL);
        }
    }
}
