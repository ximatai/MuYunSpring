package net.ximatai.muyun.spring.platform.generation;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.StandardBusinessService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import org.springframework.stereotype.Service;

@Service
public class RecordGenerationSplitPolicyService extends StandardBusinessService<RecordGenerationSplitPolicy> implements SoftDeleteAbility<RecordGenerationSplitPolicy> {
    public static final String MODULE_ALIAS = "platform.record_generation_split_policy";

    public RecordGenerationSplitPolicyService(BaseDao<RecordGenerationSplitPolicy, String> splitPolicyDao) {
        super(MODULE_ALIAS, RecordGenerationSplitPolicy.class, splitPolicyDao);
    }

    public RecordGenerationSplitPolicy selectByObjectMappingId(String objectMappingId) {
        if (objectMappingId == null || objectMappingId.isBlank()) {
            return null;
        }
        return findOne(Criteria.of().eq("objectMappingId", objectMappingId));
    }

    @Override
    protected void validateBeforeSave(RecordGenerationSplitPolicy policy) {
        if (policy.getQuantityField() != null && policy.getQuantityField().isBlank()) {
            policy.setQuantityField(null);
        }
        if (policy.getQuantityField() != null) {
            policy.setQuantityField(PlatformNameRules.requireFieldName(policy.getQuantityField(), "quantityField"));
        }
        if (policy.getQuantityModuleMetadataFieldId() != null && !policy.getQuantityModuleMetadataFieldId().isBlank()) {
            policy.setQuantityModuleMetadataFieldId(policy.getQuantityModuleMetadataFieldId().trim());
        }
        if (policy.getQuantityStep() != null && policy.getQuantityStep() <= 0) {
            throw new PlatformException("Split policy quantityStep must be positive");
        }
        if (policy.getTitle() == null || policy.getTitle().isBlank()) {
            policy.setTitle("Split Policy");
        }
    }
}
