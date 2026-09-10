package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.security.FieldEncryptionMode;
import net.ximatai.muyun.spring.common.security.FieldProtectionDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldMeasureUnitDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldMeasureUnitMode;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ListQuerySummaryFieldCatalogTest {
    @Test
    void shouldExposeOnlyPhysicalUnprotectedAndUnitSafeNumericFields() {
        FieldDefinition protectedAmount = FieldDefinition.decimal("protectedAmount", "受保护金额")
                .protection(new FieldProtectionDefinition(FieldEncryptionMode.ENCRYPTED, null, null));
        FieldDefinition selectableQuantity = FieldDefinition.decimal("quantity", "数量")
                .measureUnit(new FieldMeasureUnitDefinition("weight", FieldMeasureUnitMode.SELECTABLE, null, null,
                        "unit", "quantityBase", "weight", "kg", null, null, true));

        assertThat(ListQuerySummaryFieldCatalog.list(List.of(
                FieldDefinition.decimal("amount", "金额"), FieldDefinition.integer("count", "数量"),
                FieldDefinition.string("code", "编码"), FieldDefinition.decimal("virtualAmount", "虚拟金额").virtual(),
                protectedAmount, selectableQuantity, FieldDefinition.decimal("quantityBase", "基准数量"))))
                .extracting(ListQuerySummaryFieldCatalog.Field::fieldName)
                .containsExactly("amount", "count", "quantityBase");
    }

    @Test
    void shouldRejectDottedAndIneligibleSummaryFields() {
        List<FieldDefinition> fields = List.of(FieldDefinition.decimal("amount", "金额"));
        assertThatThrownBy(() -> ListQuerySummaryFieldCatalog.requireEligible("customer.amount", fields, "sales.contract"))
                .hasMessageContaining("direct main entity field");
        assertThatThrownBy(() -> ListQuerySummaryFieldCatalog.requireEligible("missing", fields, "sales.contract"))
                .hasMessageContaining("not declared");
    }
}
