package net.ximatai.muyun.spring.platform.module;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.security.FieldProtectionAbility;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.model.title.TitleField;
import net.ximatai.muyun.spring.common.security.MaskedField;
import net.ximatai.muyun.spring.common.security.FieldMaskingPolicy;
import net.ximatai.muyun.spring.common.security.FieldOutputContext;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Map;
import java.util.stream.Stream;

class DefaultModuleRecordFactsTest {
    @SuppressWarnings("unchecked")
    @Test void staticCustomTitleRunsTheRealListMaskingContract() {
        FieldProtectionAbility<Purchase> ability = mock(FieldProtectionAbility.class, CALLS_REAL_METHODS);
        var record = new Purchase();record.customerName="13812345678";
        when(ability.getModuleAlias()).thenReturn("demo.purchase");doReturn(Purchase.class).when(ability).modelClass();
        doReturn(record).when(ability).select("r1");
        ObjectProvider<CrudAbility<?>> provider=mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(call->Stream.of(ability));
        var facts = new DefaultModuleRecordFacts(provider,mock(DynamicRecordService.class));
        assertThat(facts.displayTitle("demo.purchase","r1")).isEqualTo(ability.maskProtectedValue("customerName",record.customerName,FieldOutputContext.LIST));
        assertThat(facts.displayTitle("demo.purchase","r1")).isNotEqualTo(record.customerName);
    }
    @SuppressWarnings("unchecked")
    @Test void dynamicTitleUsesOutputValuesRatherThanUnprotectedFacts() {
        ObjectProvider<CrudAbility<?>> provider=mock(ObjectProvider.class);when(provider.orderedStream()).thenAnswer(call->Stream.empty());
        var records=mock(DynamicRecordService.class);var record=mock(DynamicRecord.class);
        when(records.mainEntityAlias("demo.purchase")).thenReturn("purchase");
        when(records.select("demo.purchase","purchase","r1")).thenReturn(record);
        when(record.outputValues(FieldOutputContext.LIST)).thenReturn(Map.of("title","***"));
        assertThat(new DefaultModuleRecordFacts(provider,records).displayTitle("demo.purchase","r1")).isEqualTo("***");
        verify(record).outputValues(FieldOutputContext.LIST);verify(record,never()).getValues();
    }
    @SuppressWarnings("unchecked")
    @Test void staticAndDynamicConditionsShareCanonicalIdentityAndAuditFacts() {
        var time = java.time.Instant.parse("2026-10-01T10:00:00Z");
        var staticRecord = new Purchase();
        var dynamicRecord = new DynamicRecord(new net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition(
                "purchase", "demo_purchase", "Purchase", java.util.List.of()));
        for (net.ximatai.muyun.spring.common.model.contract.EntityContract record : java.util.List.of(staticRecord, dynamicRecord)) {
            record.setId("r1"); record.setTenantId("tenant"); record.setVersion(4); record.setDeleted(false);
            record.setCreatedBy("creator"); record.setCreatedAt(time); record.setUpdatedBy("editor"); record.setUpdatedAt(time);
        }
        CrudAbility<Purchase> ability = mock(CrudAbility.class);
        when(ability.getModuleAlias()).thenReturn("static.purchase"); when(ability.select("r1")).thenReturn(staticRecord);
        ObjectProvider<CrudAbility<?>> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(call -> Stream.of(ability));
        var records = mock(DynamicRecordService.class);
        when(records.mainEntityAlias("dynamic.purchase")).thenReturn("purchase");
        when(records.select("dynamic.purchase", "purchase", "r1")).thenReturn(dynamicRecord);
        var facts = new DefaultModuleRecordFacts(provider, records);
        var dynamicFacts = facts.read("dynamic.purchase", "r1");
        var staticFacts = facts.read("static.purchase", "r1");
        for (String key : java.util.List.of("id", "tenantId", "version", "deleted", "deletedAt", "deletedBy", "createdBy", "createdAt", "updatedBy", "updatedAt"))
            assertThat(dynamicFacts).containsEntry(key, staticFacts.get(key));
        var conditions = new net.ximatai.muyun.spring.platform.workflow.WorkflowConditionService(facts);
        for (String module : java.util.List.of("static.purchase", "dynamic.purchase"))
            assertThat(conditions.matches("{version} == 4 && {tenantId} == 'tenant' && {updatedBy} == 'editor'", module, "r1")).isTrue();
    }
    public static class Purchase extends StandardEntity {
        @TitleField @MaskedField(FieldMaskingPolicy.MIDDLE) private String customerName;
    }
}
