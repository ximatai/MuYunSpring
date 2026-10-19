package net.ximatai.muyun.spring.platform.code;

import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.DisablePlatformOperations;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
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

    private final CodeRecycleEntryService recycleEntries;

    public CodeLedgerEntryService(BaseDao<CodeLedgerEntry, String> ledgerEntryDao, CodeRecycleEntryService recycleEntries) {
        super(MODULE_ALIAS, CodeLedgerEntry.class, ledgerEntryDao);
        this.recycleEntries = java.util.Objects.requireNonNull(recycleEntries, "recycleEntries");
    }

    public CodeLedgerEntry upsertActiveBinding(CodeRule rule,
                                                            String codeValue,
                                                            String basisKey,
                                                            String periodKey,
                                                            String sourceRecordId) {
        lockTarget(rule.getModuleAlias(), rule.getEntityAlias(), rule.getFieldName());
        CodeLedgerEntry owned = findActiveByTargetAndSourceRecord(rule.getModuleAlias(), rule.getEntityAlias(), rule.getFieldName(), sourceRecordId);
        if (owned != null && !Objects.equals(owned.getCodeValue(), codeValue))
            throw new net.ximatai.muyun.spring.common.exception.PlatformException("Record already owns another business code for this field");
        CodeLedgerEntry entry = findByTargetAndValue(rule.getModuleAlias(), rule.getEntityAlias(), rule.getFieldName(), codeValue);
        if (entry == null) {
            entry = new CodeLedgerEntry();
            entry.setRuleId(rule.getId());
            entry.setCodeValue(codeValue);
            entry.setModuleAlias(rule.getModuleAlias());
            entry.setEntityAlias(rule.getEntityAlias());
            entry.setFieldName(rule.getFieldName());
        } else if (entry.getStatus() == CodeLedgerStatus.DISCARDED) {
            throw new net.ximatai.muyun.spring.common.exception.PlatformException("Code value has been discarded: " + codeValue);
        } else if (entry.getStatus() == CodeLedgerStatus.ACTIVE
                && !Objects.equals(entry.getSourceRecordId(), sourceRecordId)) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.code-ledger-entry.code-value-is-already-occupied",
                    "Code value is already occupied: {value1}"), Map.of("value1", String.valueOf(codeValue)));
        }
        if (entry.getId() != null && entry.getStatus() == CodeLedgerStatus.AVAILABLE)
            recycleEntries.markOccupied(entry.getRuleId(), entry.getBasisKey(), entry.getPeriodKey(), entry.getCodeValue());
        entry.setRuleId(rule.getId());
        entry.setRecycleAllowed(Boolean.TRUE.equals(rule.getAllowRecycle()));
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

    public CodeLedgerEntry upsertInactiveBinding(CodeRule rule,
                                                              String codeValue,
                                                              String basisKey,
                                                              String periodKey,
                                                              String sourceRecordId,
                                                              CodeLedgerStatus status,
                                                              CodeLedgerAction action) {
        lockTarget(rule.getModuleAlias(), rule.getEntityAlias(), rule.getFieldName());
        CodeLedgerEntry entry = findByTargetAndValue(rule.getModuleAlias(), rule.getEntityAlias(), rule.getFieldName(), codeValue);
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
        if (entry.getId() != null && entry.getStatus() == CodeLedgerStatus.ACTIVE && !Objects.equals(entry.getSourceRecordId(), sourceRecordId))
            throw new net.ximatai.muyun.spring.common.exception.PlatformException("Cannot release another record's code");
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

    /** Serializes issuance and ownership changes until the outer business transaction completes. */
    public void lockTarget(String module, String entity, String field) {
        if (net.ximatai.muyun.spring.ability.TransactionScopeSupport.isTransactionActive()) {
            lockMutation("code-target", java.util.Arrays.asList(
                    net.ximatai.muyun.spring.common.tenant.TenantContext.currentTenantId().orElse(""),
                    module, entity, field).toString());
        }
    }

    public CodeLedgerEntry findByTargetAndValue(String module, String entity, String field, String value) {
        return findOne(Criteria.of().eq("moduleAlias", module).eq("entityAlias", entity)
                .eq("fieldName", field).eq("codeValue", value));
    }

    public List<CodeLedgerEntry> activeBindings(String module, String entity, String recordId) {
        return list(Criteria.of().eq("moduleAlias", module).eq("entityAlias", entity)
                .eq("sourceRecordId", recordId).eq("status", CodeLedgerStatus.ACTIVE),
                new PageRequest(0, Integer.MAX_VALUE));
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
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.code-ledger-entry.code-ledger-entry-does-not-exist",
                    "Code ledger entry does not exist"));
        }
        CodeLedgerStatus targetStatus = Boolean.TRUE.equals(entry.getRecycleAllowed())
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
                        .eq("moduleAlias", entry.getModuleAlias()).eq("entityAlias", entry.getEntityAlias())
                        .eq("fieldName", entry.getFieldName()).eq("codeValue", entry.getCodeValue()), () -> PlatformErrors.badRequestText(TextReference.code(
                                "platform.rejection.code-ledger-entry.code-ledger-entry-already-exists-for-value",
                                "Code ledger entry already exists for value: {value1}/{value2}"), Map.of("value1", String.valueOf(entry.getRuleId()), "value2", String.valueOf(entry.getCodeValue()))));
    }

    @Override
    public void beforeUpdate(CodeLedgerEntry entry) {
        applyDefaults(entry);
        rejectDuplicate(entry, Criteria.of()
                        .eq("moduleAlias", entry.getModuleAlias()).eq("entityAlias", entry.getEntityAlias())
                        .eq("fieldName", entry.getFieldName()).eq("codeValue", entry.getCodeValue()), () -> PlatformErrors.badRequestText(TextReference.code(
                                "platform.rejection.code-ledger-entry.code-ledger-entry-already-exists-for-value",
                                "Code ledger entry already exists for value: {value1}/{value2}"), Map.of("value1", String.valueOf(entry.getRuleId()), "value2", String.valueOf(entry.getCodeValue()))));
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
