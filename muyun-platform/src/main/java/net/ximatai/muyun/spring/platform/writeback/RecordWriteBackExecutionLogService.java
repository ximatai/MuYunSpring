package net.ximatai.muyun.spring.platform.writeback;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class RecordWriteBackExecutionLogService extends AbstractAbilityService<RecordWriteBackExecutionLog> implements
        SoftDeleteAbility<RecordWriteBackExecutionLog> {
    public static final String MODULE_ALIAS = "platform.record_write_back_execution_log";
    private static final PageRequest DEFAULT_QUERY_PAGE = PageRequest.of(1, 100);

    public RecordWriteBackExecutionLogService(BaseDao<RecordWriteBackExecutionLog, String> executionLogDao) {
        super(MODULE_ALIAS, RecordWriteBackExecutionLog.class, executionLogDao);
    }

    @Transactional
    public String insertExecutionLog(RecordWriteBackExecutionLog log) {
        return insert(log);
    }

    @Transactional
    public void updateExecutionLog(RecordWriteBackExecutionLog log) {
        update(log);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String saveFailureExecutionLog(RecordWriteBackExecutionLog log) {
        if (log.getId() != null && selectIncludingDeleted(log.getId()) != null) {
            update(log);
            return log.getId();
        }
        RecordWriteBackExecutionLog detached = detachedFailureLog(log);
        return insert(detached);
    }

    public List<RecordWriteBackExecutionLog> selectByTraceId(String traceId) {
        return selectByTraceId(traceId, DEFAULT_QUERY_PAGE);
    }

    public List<RecordWriteBackExecutionLog> selectByTraceId(String traceId, PageRequest pageRequest) {
        return list(Criteria.of().eq("traceId", requireText(traceId, "traceId")),
                pageOrDefault(pageRequest),
                Sort.asc("depth"),
                Sort.asc("createdAt"));
    }

    public List<RecordWriteBackExecutionLog> selectByRuleId(String ruleId, int limit) {
        if (ruleId == null || ruleId.isBlank()) {
            return List.of();
        }
        return list(Criteria.of().eq("ruleId", ruleId.trim()),
                PageRequest.of(1, Math.max(1, limit)),
                Sort.desc("createdAt"));
    }

    public List<RecordWriteBackExecutionLog> selectByTrigger(String triggerModuleAlias,
                                                             String triggerRecordId,
                                                             PageRequest pageRequest) {
        return list(Criteria.of()
                        .eq("triggerModuleAlias", requireText(triggerModuleAlias, "triggerModuleAlias"))
                        .eq("triggerRecordId", requireText(triggerRecordId, "triggerRecordId")),
                pageOrDefault(pageRequest),
                Sort.desc("createdAt"));
    }

    public List<RecordWriteBackExecutionLog> selectByTarget(String targetModuleAlias,
                                                            String targetRecordId,
                                                            PageRequest pageRequest) {
        return list(Criteria.of()
                        .eq("targetModuleAlias", requireText(targetModuleAlias, "targetModuleAlias"))
                        .eq("targetRecordId", requireText(targetRecordId, "targetRecordId")),
                pageOrDefault(pageRequest),
                Sort.desc("createdAt"));
    }

    public List<RecordWriteBackExecutionLog> selectFailed(String triggerModuleAlias, PageRequest pageRequest) {
        return selectByStatus(triggerModuleAlias, RecordWriteBackExecutionStatus.FAILED, pageRequest);
    }

    public List<RecordWriteBackExecutionLog> selectByStatus(String triggerModuleAlias,
                                                            RecordWriteBackExecutionStatus status,
                                                            PageRequest pageRequest) {
        if (status == null) {
            throw new PlatformException("Record write-back execution status must not be null");
        }
        return list(Criteria.of()
                        .eq("triggerModuleAlias", requireText(triggerModuleAlias, "triggerModuleAlias"))
                        .eq("status", status),
                pageOrDefault(pageRequest),
                Sort.desc("createdAt"));
    }

    private RecordWriteBackExecutionLog detachedFailureLog(RecordWriteBackExecutionLog source) {
        RecordWriteBackExecutionLog detached = new RecordWriteBackExecutionLog();
        detached.setTraceId(source.getTraceId());
        detached.setRuleId(source.getRuleId());
        detached.setEventId(source.getEventId());
        detached.setEventType(source.getEventType());
        detached.setDepth(source.getDepth());
        detached.setParentExecutionId(source.getParentExecutionId());
        detached.setTriggerModuleAlias(source.getTriggerModuleAlias());
        detached.setTriggerRecordId(source.getTriggerRecordId());
        detached.setTargetModuleAlias(source.getTargetModuleAlias());
        detached.setTargetRecordId(source.getTargetRecordId());
        detached.setStatus(source.getStatus());
        detached.setMessage(source.getMessage());
        detached.setEventSnapshot(source.getEventSnapshot());
        detached.setPatchSnapshot(source.getPatchSnapshot());
        return detached;
    }

    @Override
    public void beforeInsert(RecordWriteBackExecutionLog log) {
        applyDefaults(log);
    }

    @Override
    public void beforeUpdate(RecordWriteBackExecutionLog log) {
        applyDefaults(log);
    }

    private void applyDefaults(RecordWriteBackExecutionLog log) {
        if (log == null) {
            throw new PlatformException("Record write-back execution log must not be null");
        }
        log.setDepth(log.getDepth() == null ? 0 : log.getDepth());
        if (log.getStatus() == null) {
            log.setStatus(RecordWriteBackExecutionStatus.PLANNED);
        }
    }

    private PageRequest pageOrDefault(PageRequest pageRequest) {
        return pageRequest == null ? DEFAULT_QUERY_PAGE : pageRequest;
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new PlatformException("Record write-back execution log " + fieldName + " must not be blank");
        }
        return value.trim();
    }
}
