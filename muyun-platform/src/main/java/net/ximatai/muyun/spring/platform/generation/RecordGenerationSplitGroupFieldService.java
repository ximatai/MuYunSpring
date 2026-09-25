package net.ximatai.muyun.spring.platform.generation;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
public class RecordGenerationSplitGroupFieldService extends AbstractAbilityService<RecordGenerationSplitGroupField> implements
        SoftDeleteAbility<RecordGenerationSplitGroupField>,
        SortAbility<RecordGenerationSplitGroupField> {
    public static final String MODULE_ALIAS = "platform.record_generation_split_group_field";
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);

    public RecordGenerationSplitGroupFieldService(BaseDao<RecordGenerationSplitGroupField, String> splitGroupFieldDao) {
        super(MODULE_ALIAS, RecordGenerationSplitGroupField.class, splitGroupFieldDao);
    }

    public List<RecordGenerationSplitGroupField> selectBySplitPolicyId(String splitPolicyId) {
        if (splitPolicyId == null || splitPolicyId.isBlank()) {
            return List.of();
        }
        return list(Criteria.of().eq("splitPolicyId", splitPolicyId), ALL, Sort.asc("sortOrder"));
    }

    @Override
    public void beforeInsert(RecordGenerationSplitGroupField groupField) {
        normalizeAndValidate(groupField);
    }

    @Override
    public void beforeUpdate(RecordGenerationSplitGroupField groupField) {
        normalizeAndValidate(groupField);
    }

    private void normalizeAndValidate(RecordGenerationSplitGroupField groupField) {
        if (groupField.getModuleMetadataFieldId() != null && !groupField.getModuleMetadataFieldId().isBlank()) {
            groupField.setModuleMetadataFieldId(groupField.getModuleMetadataFieldId().trim());
        }
        groupField.setFieldName(PlatformNameRules.requireFieldName(groupField.getFieldName(), "fieldName"));
        if (groupField.getTitle() == null || groupField.getTitle().isBlank()) {
            groupField.setTitle(groupField.getFieldName());
        }
    }
}
