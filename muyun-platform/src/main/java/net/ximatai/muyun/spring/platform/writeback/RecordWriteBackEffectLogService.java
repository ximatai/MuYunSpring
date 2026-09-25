package net.ximatai.muyun.spring.platform.writeback;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RecordWriteBackEffectLogService extends AbstractAbilityService<RecordWriteBackEffectLog> implements
        SoftDeleteAbility<RecordWriteBackEffectLog> {
    public static final String MODULE_ALIAS = "platform.record_write_back_effect_log";
    private static final PageRequest DEFAULT_QUERY_PAGE = PageRequest.of(1, 100);

    public RecordWriteBackEffectLogService(BaseDao<RecordWriteBackEffectLog, String> effectLogDao) {
        super(MODULE_ALIAS, RecordWriteBackEffectLog.class, effectLogDao);
    }

    public List<RecordWriteBackEffectLog> selectByExecutionId(String executionId) {
        return list(Criteria.of().eq("executionId", requireText(executionId, "executionId")),
                DEFAULT_QUERY_PAGE,
                Sort.asc("createdAt"));
    }

    public List<RecordWriteBackEffectLog> selectByTraceId(String traceId, PageRequest pageRequest) {
        return list(Criteria.of().eq("traceId", requireText(traceId, "traceId")),
                pageOrDefault(pageRequest),
                Sort.asc("createdAt"));
    }

    public List<RecordWriteBackEffectLog> selectByTarget(String targetModuleAlias,
                                                         String targetRecordId,
                                                         PageRequest pageRequest) {
        return list(Criteria.of()
                        .eq("targetModuleAlias", requireText(targetModuleAlias, "targetModuleAlias"))
                        .eq("targetRecordId", requireText(targetRecordId, "targetRecordId")),
                pageOrDefault(pageRequest),
                Sort.desc("createdAt"));
    }

    public List<RecordWriteBackEffectLog> selectActiveContributions(String ruleId,
                                                                    String triggerModuleAlias,
                                                                    String triggerRecordId,
                                                                    String targetModuleAlias,
                                                                    String targetRecordId,
                                                                    String targetField,
                                                                    String sourceField) {
        Criteria criteria = Criteria.of()
                .eq("ruleId", requireText(ruleId, "ruleId"))
                .eq("triggerModuleAlias", requireText(triggerModuleAlias, "triggerModuleAlias"))
                .eq("triggerRecordId", requireText(triggerRecordId, "triggerRecordId"))
                .eq("targetModuleAlias", requireText(targetModuleAlias, "targetModuleAlias"))
                .eq("targetRecordId", requireText(targetRecordId, "targetRecordId"))
                .eq("targetField", requireText(targetField, "targetField"))
                .eq("status", RecordWriteBackEffectStatus.ACTIVE);
        if (sourceField != null && !sourceField.isBlank()) {
            criteria.eq("sourceField", sourceField.trim());
        }
        return list(criteria, DEFAULT_QUERY_PAGE, Sort.desc("createdAt"));
    }

    public List<RecordWriteBackEffectLog> selectAppliedEffects(String ruleId,
                                                               String triggerModuleAlias,
                                                               String triggerRecordId,
                                                               String targetModuleAlias,
                                                               String targetRecordId,
                                                               String targetField,
                                                               String sourceField,
                                                               String afterValue) {
        Criteria criteria = Criteria.of()
                .eq("ruleId", requireText(ruleId, "ruleId"))
                .eq("triggerModuleAlias", requireText(triggerModuleAlias, "triggerModuleAlias"))
                .eq("triggerRecordId", requireText(triggerRecordId, "triggerRecordId"))
                .eq("targetModuleAlias", requireText(targetModuleAlias, "targetModuleAlias"))
                .eq("targetRecordId", requireText(targetRecordId, "targetRecordId"))
                .eq("targetField", requireText(targetField, "targetField"))
                .eq("afterValue", valueText(afterValue))
                .eq("status", RecordWriteBackEffectStatus.APPLIED);
        if (sourceField != null && !sourceField.isBlank()) {
            criteria.eq("sourceField", sourceField.trim());
        }
        return list(criteria, DEFAULT_QUERY_PAGE, Sort.desc("createdAt"));
    }

    @Override
    public void beforeInsert(RecordWriteBackEffectLog effectLog) {
        applyDefaults(effectLog);
    }

    @Override
    public void beforeUpdate(RecordWriteBackEffectLog effectLog) {
        applyDefaults(effectLog);
    }

    private void applyDefaults(RecordWriteBackEffectLog effectLog) {
        if (effectLog == null) {
            throw new PlatformException("Record write-back effect log must not be null");
        }
        if (effectLog.getStatus() == null) {
            effectLog.setStatus(RecordWriteBackEffectStatus.APPLIED);
        }
    }

    private PageRequest pageOrDefault(PageRequest pageRequest) {
        return pageRequest == null ? DEFAULT_QUERY_PAGE : pageRequest;
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new PlatformException("Record write-back effect log " + fieldName + " must not be blank");
        }
        return value.trim();
    }

    private String valueText(String value) {
        return value == null ? "" : value;
    }
}
