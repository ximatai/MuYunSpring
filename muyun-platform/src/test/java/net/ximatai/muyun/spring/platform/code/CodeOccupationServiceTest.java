package net.ximatai.muyun.spring.platform.code;

import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CodeOccupationServiceTest {
    @Test void releasePreservesOriginalScopePeriodAndRecyclingPolicyAfterRuleChanges() {
        var recycle = new CodeRecycleEntryService(new TestMemoryDao<>());
        var ledger = new CodeLedgerEntryService(new TestMemoryDao<>(), recycle);
        var rule = rule();
        rule.setAllowRecycle(true);
        ledger.upsertActiveBinding(rule, "SO-A0001", "category=A", "202606", "record-1");
        rule.setAllowRecycle(false); // Changes to current configuration cannot rewrite the issued fact.
        new CodeOccupationService(ledger, recycle).releaseAll("crm.order", "main", "record-1");
        var entry = ledger.findByRuleAndValue("rule-1", "SO-A0001");
        assertThat(entry.getStatus()).isEqualTo(CodeLedgerStatus.AVAILABLE);
        assertThat(entry.getSourceRecordId()).isNull();
        var recycled = recycle.selectByRuleId("rule-1", 10).getFirst();
        assertThat(recycled.getBasisKey()).isEqualTo("category=A");
        assertThat(recycled.getPeriodKey()).isEqualTo("202606");
    }
    @Test void obsoleteOwnerCannotReleaseReassignedCode() {
        var recycle = new CodeRecycleEntryService(new TestMemoryDao<>());
        var ledger = new CodeLedgerEntryService(new TestMemoryDao<>(), recycle);
        var rule = rule(); rule.setAllowRecycle(true);
        var occupations = new CodeOccupationService(ledger, recycle);
        ledger.upsertActiveBinding(rule, "SO-0001", "scope", "day", "record-1");
        occupations.releaseAll("crm.order", "main", "record-1");
        ledger.upsertActiveBinding(rule, "SO-0001", "scope", "day", "record-2");
        occupations.releaseAll("crm.order", "main", "record-1");
        assertThat(ledger.findByRuleAndValue("rule-1", "SO-0001").getSourceRecordId()).isEqualTo("record-2");
    }
    @Test void differentRulesCannotOccupyTheSameTargetValue() {
        var ledger = new CodeLedgerEntryService(new TestMemoryDao<>(), new CodeRecycleEntryService(new TestMemoryDao<>()));
        var rule = rule();
        ledger.upsertActiveBinding(rule, "SO-0001", "scope", "day", "record-1");
        rule.setId("replacement-rule");
        assertThatThrownBy(() -> ledger.upsertActiveBinding(rule, "SO-0001", "scope", "day", "record-2"))
                .hasMessageContaining("already occupied");
    }
    private CodeRule rule() {
        var rule = new CodeRule(); rule.setId("rule-1"); rule.setModuleAlias("crm.order");
        rule.setEntityAlias("main"); rule.setFieldName("orderNo"); return rule;
    }
}
