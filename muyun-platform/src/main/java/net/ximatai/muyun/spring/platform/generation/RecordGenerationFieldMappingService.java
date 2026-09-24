package net.ximatai.muyun.spring.platform.generation;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.StandardBusinessService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.SortAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RecordGenerationFieldMappingService extends StandardBusinessService<RecordGenerationFieldMapping> implements
        SoftDeleteAbility<RecordGenerationFieldMapping>,
        SortAbility<RecordGenerationFieldMapping> {
    public static final String MODULE_ALIAS = "platform.record_generation_field_mapping";

    public RecordGenerationFieldMappingService(BaseDao<RecordGenerationFieldMapping, String> fieldMappingDao) {
        super(MODULE_ALIAS, RecordGenerationFieldMapping.class, fieldMappingDao);
    }

    public List<RecordGenerationFieldMapping> selectByObjectMappingId(String objectMappingId) {
        if (objectMappingId == null || objectMappingId.isBlank()) {
            return List.of();
        }
        return sortedList(Criteria.of().eq("objectMappingId", objectMappingId));
    }

    @Override
    protected void validateBeforeSave(RecordGenerationFieldMapping mapping) {
        if (mapping.getObjectMappingId() == null || mapping.getObjectMappingId().isBlank()) {
            throw new PlatformException("Field mapping requires objectMappingId");
        }
        if (mapping.getMappingType() == null) {
            mapping.setMappingType(RecordGenerationFieldSourceType.DIRECT);
        }
        if (mapping.getSourceField() != null && mapping.getSourceField().isBlank()) {
            mapping.setSourceField(null);
        }
        if (mapping.getSourceField() != null) {
            mapping.setSourceField(PlatformNameRules.requireFieldName(mapping.getSourceField(), "sourceField"));
        }
        if (mapping.getSourceModuleMetadataFieldId() != null && !mapping.getSourceModuleMetadataFieldId().isBlank()) {
            mapping.setSourceModuleMetadataFieldId(mapping.getSourceModuleMetadataFieldId().trim());
        }
        if (mapping.getTargetModuleMetadataFieldId() != null && !mapping.getTargetModuleMetadataFieldId().isBlank()) {
            mapping.setTargetModuleMetadataFieldId(mapping.getTargetModuleMetadataFieldId().trim());
        }
        mapping.setTargetField(PlatformNameRules.requireFieldName(mapping.getTargetField(), "targetField"));
        if (mapping.getMappingType() == RecordGenerationFieldSourceType.DIRECT
                && (mapping.getSourceField() == null || mapping.getSourceField().isBlank())) {
            throw new PlatformException("DIRECT field mapping requires sourceField");
        }
        if (mapping.getMappingType() == RecordGenerationFieldSourceType.FORMULA
                && (mapping.getFormulaExpr() == null || mapping.getFormulaExpr().isBlank())) {
            throw new PlatformException("FORMULA field mapping requires formulaExpr");
        }
        if (mapping.getTitle() == null || mapping.getTitle().isBlank()) {
            mapping.setTitle(mapping.getTargetField());
        }
    }
}
