package net.ximatai.muyun.spring.platform.code;

import org.springframework.stereotype.Service;
import java.util.*;

/** Releases the original occupation facts, including after its numbering rule is disabled or changed. */
@Service
public final class CodeOccupationService {
    private final CodeLedgerEntryService ledger;
    private final CodeRecycleEntryService recycle;
    public CodeOccupationService(CodeLedgerEntryService ledger, CodeRecycleEntryService recycle) {
        this.ledger = Objects.requireNonNull(ledger);
        this.recycle = Objects.requireNonNull(recycle);
    }
    public void release(String module, String entity, String recordId, String field, String value, CodeLedgerAction action) {
        ledger.lockTarget(module, entity, field);
        CodeLedgerEntry entry = ledger.findActiveByTargetAndSourceRecord(module, entity, field, recordId);
        if (entry == null || !Objects.equals(entry.getCodeValue(), value)) return;
        CodeRule original = new CodeRule();
        original.setId(entry.getRuleId()); original.setModuleAlias(module); original.setEntityAlias(entity);
        original.setFieldName(field); original.setAllowRecycle(Boolean.TRUE.equals(entry.getRecycleAllowed()));
        recycle.record(original, entry.getBasisKey(), entry.getPeriodKey(), entry.getCodeValue(), recordId);
        ledger.upsertInactiveBinding(original, value, entry.getBasisKey(), entry.getPeriodKey(), recordId,
                Boolean.TRUE.equals(entry.getRecycleAllowed()) ? CodeLedgerStatus.AVAILABLE : CodeLedgerStatus.DISCARDED, action);
    }
    public void releaseAll(String module, String entity, String recordId) {
        ledger.activeBindings(module, entity, recordId).stream().sorted(Comparator.comparing(CodeLedgerEntry::getFieldName))
                .forEach(entry -> release(module, entity, recordId, entry.getFieldName(), entry.getCodeValue(), CodeLedgerAction.RELEASED_BY_DELETE));
    }
}
