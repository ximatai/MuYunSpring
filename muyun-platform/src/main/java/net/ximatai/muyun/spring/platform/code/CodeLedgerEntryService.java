package net.ximatai.muyun.spring.platform.code;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.DisablePlatformOperations;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;

@Service
@DisablePlatformOperations({PlatformAction.CREATE, PlatformAction.UPDATE, PlatformAction.DELETE})
public class CodeLedgerEntryService extends AbstractAbilityService<CodeLedgerEntry> implements
        SoftDeleteAbility<CodeLedgerEntry>,
        QueryAbility<CodeLedgerEntry> {
    public static final String MODULE_ALIAS = "platform.code_ledger_entry";

    public CodeLedgerEntryService(BaseDao<CodeLedgerEntry, String> ledgerEntryDao) {
        super(MODULE_ALIAS, CodeLedgerEntry.class, ledgerEntryDao);
    }

    public synchronized CodeLedgerEntry upsertActiveBinding(CodeRule rule,
                                                            String codeValue,
                                                            String basisKey,
                                                            String periodKey,
                                                            String sourceRecordId) {
        CodeLedgerEntry entry = findByRuleAndValue(rule.getId(), codeValue);
        if (entry == null) {
            entry = new CodeLedgerEntry();
            entry.setRuleId(rule.getId());
            entry.setCodeValue(codeValue);
            entry.setModuleAlias(rule.getModuleAlias());
            entry.setEntityAlias(rule.getEntityAlias());
            entry.setFieldName(rule.getFieldName());
        } else if (entry.getStatus() == CodeLedgerStatus.ACTIVE
                && !Objects.equals(entry.getSourceRecordId(), sourceRecordId)) {
            throw new PlatformException("Code value is already occupied: " + codeValue);
        }
        entry.setBasisKey(normalizeBucket(basisKey));
        entry.setPeriodKey(normalizeBucket(periodKey));
        entry.setSourceRecordId(sourceRecordId);
        entry.setStatus(CodeLedgerStatus.ACTIVE);
        entry.setLastAction(CodeLedgerAction.ASSIGNED);
        if (entry.getId() == null) {
            insert(entry);
        } else {
            update(entry);
        }
        return entry;
    }

    public synchronized CodeLedgerEntry upsertInactiveBinding(CodeRule rule,
                                                              String codeValue,
                                                              String basisKey,
                                                              String periodKey,
                                                              String sourceRecordId,
                                                              CodeLedgerStatus status,
                                                              CodeLedgerAction action) {
        CodeLedgerEntry entry = findByRuleAndValue(rule.getId(), codeValue);
        if (entry == null) {
            entry = new CodeLedgerEntry();
            entry.setRuleId(rule.getId());
            entry.setCodeValue(codeValue);
            entry.setModuleAlias(rule.getModuleAlias());
            entry.setEntityAlias(rule.getEntityAlias());
            entry.setFieldName(rule.getFieldName());
        }
        entry.setBasisKey(normalizeBucket(basisKey));
        entry.setPeriodKey(normalizeBucket(periodKey));
        entry.setSourceRecordId(null);
        entry.setStatus(status == null ? CodeLedgerStatus.DISCARDED : status);
        entry.setLastAction(action == null ? CodeLedgerAction.RELEASED_BY_DELETE : action);
        if (entry.getId() == null) {
            insert(entry);
        } else {
            update(entry);
        }
        return entry;
    }

    public CodeLedgerEntry findByRuleAndValue(String ruleId, String codeValue) {
        if (ruleId == null || ruleId.isBlank() || codeValue == null || codeValue.isBlank()) {
            return null;
        }
        return findOne(Criteria.of()
                .eq("ruleId", ruleId)
                .eq("codeValue", codeValue));
    }

    public CodeLedgerEntry findActiveByTargetAndSourceRecord(String moduleAlias,
                                                             String entityAlias,
                                                             String fieldName,
                                                             String sourceRecordId) {
        if (moduleAlias == null || moduleAlias.isBlank()
                || entityAlias == null || entityAlias.isBlank()
                || fieldName == null || fieldName.isBlank()
                || sourceRecordId == null || sourceRecordId.isBlank()) {
            return null;
        }
        return findOne(Criteria.of()
                .eq("moduleAlias", moduleAlias)
                .eq("entityAlias", entityAlias)
                .eq("fieldName", fieldName)
                .eq("sourceRecordId", sourceRecordId)
                .eq("status", CodeLedgerStatus.ACTIVE));
    }

    public List<CodeLedgerEntry> selectByRuleId(String ruleId, int limit) {
        if (ruleId == null || ruleId.isBlank()) {
            return List.of();
        }
        return list(Criteria.of().eq("ruleId", ruleId), PageRequest.of(1, Math.max(1, limit)),
                net.ximatai.muyun.database.core.orm.Sort.desc("updatedAt"),
                net.ximatai.muyun.database.core.orm.Sort.desc("createdAt"));
    }

    public CodeLedgerEntry releaseStaleBinding(CodeLedgerEntry entry, CodeRule rule, CodeLedgerAction action) {
        if (entry == null) {
            throw new PlatformException("Code ledger entry does not exist");
        }
        CodeLedgerStatus targetStatus = Boolean.TRUE.equals(rule.getAllowRecycle())
                ? CodeLedgerStatus.AVAILABLE
                : CodeLedgerStatus.DISCARDED;
        entry.setSourceRecordId(null);
        entry.setStatus(targetStatus);
        entry.setLastAction(action == null ? CodeLedgerAction.RELEASED_BY_GOVERNANCE : action);
        update(entry);
        return select(entry.getId());
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, CodeLedgerEntry.class, java.util.List.of("id", "ruleId", "moduleAlias", "entityAlias", "fieldName", "codeValue", "basisKey", "periodKey", "sourceRecordId", "status", "lastAction"),
                net.ximatai.muyun.database.core.orm.Sort.desc("createdAt"));
    }

    @Override
    public void beforeInsert(CodeLedgerEntry entry) {
        applyDefaults(entry);
        rejectDuplicate(entry, Criteria.of()
                        .eq("ruleId", entry.getRuleId())
                        .eq("codeValue", entry.getCodeValue()),
                "Code ledger entry already exists for value: " + entry.getRuleId() + "/" + entry.getCodeValue());
    }

    @Override
    public void beforeUpdate(CodeLedgerEntry entry) {
        applyDefaults(entry);
        rejectDuplicate(entry, Criteria.of()
                        .eq("ruleId", entry.getRuleId())
                        .eq("codeValue", entry.getCodeValue()),
                "Code ledger entry already exists for value: " + entry.getRuleId() + "/" + entry.getCodeValue());
    }

    private void applyDefaults(CodeLedgerEntry entry) {
        entry.setBasisKey(normalizeBucket(entry.getBasisKey()));
        entry.setPeriodKey(normalizeBucket(entry.getPeriodKey()));
        if (entry.getStatus() == null) {
            entry.setStatus(CodeLedgerStatus.ACTIVE);
        }
        if (entry.getLastAction() == null) {
            entry.setLastAction(CodeLedgerAction.ASSIGNED);
        }
    }

    private String normalizeBucket(String value) {
        return value == null || value.isBlank() ? CodeSequenceState.DEFAULT_BUCKET : value;
    }
}
