package net.ximatai.muyun.spring.dynamic.metadata;


import net.ximatai.muyun.spring.common.formula.FormulaIssueLevel;
import net.ximatai.muyun.spring.common.formula.FormulaRuleKind;
import net.ximatai.muyun.spring.common.formula.FormulaRulePhase;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EntityFormulaRuleDefinitionTest {
    private final ModuleDefinitionValidator validator = new ModuleDefinitionValidator();

    @Test
    void shouldConvertDynamicFormulaRuleToRuntimeRule() {
        EntityFormulaRuleDefinition rule = EntityFormulaRuleDefinition
                .validation("checkAmount", "amount", "{amount} >= 0", "金额不能小于 0")
                .phase(FormulaRulePhase.IMPORT_VALIDATE)
                .severity(FormulaIssueLevel.WARNING)
                .sortOrder(20);

        assertThat(rule.toRuntimeRule()).satisfies(runtime -> {
            assertThat(runtime.id()).isEqualTo("checkAmount");
            assertThat(runtime.expression()).isEqualTo("{amount} >= 0");
            assertThat(runtime.kind()).isEqualTo(FormulaRuleKind.VALIDATION);
            assertThat(runtime.phase()).isEqualTo(FormulaRulePhase.IMPORT_VALIDATE);
            assertThat(runtime.targetField()).isEqualTo("amount");
            assertThat(runtime.severity()).isEqualTo(FormulaIssueLevel.WARNING);
            assertThat(runtime.messageTemplate()).isEqualTo("金额不能小于 0");
            assertThat(runtime.enabled()).isTrue();
        });
    }

    @Test
    void shouldValidateFormulaRuleShapeWithEntityFields() {
        EntityDefinition entity = invoiceEntity()
                .withFormulaRules(
                        EntityFormulaRuleDefinition.calculation("amountCalc", "amount", "{quantity} * {price}"),
                        EntityFormulaRuleDefinition.calculation("lineAmountCalc", "items.lineAmount", "{items.quantity} * {items.price}")
                );

        validator.validateEntity(entity);
    }

    @Test
    void shouldRejectImportValidationCalculationRule() {
        EntityDefinition entity = invoiceEntity()
                .withFormulaRules(EntityFormulaRuleDefinition.calculation("normalizeAmount",
                        "amount", "{quantity} * {price}").phase(FormulaRulePhase.IMPORT_VALIDATE));

        assertThatThrownBy(() -> validator.validateEntity(entity))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("import validation formula must not calculate fields: normalizeAmount");
    }

    @Test
    void shouldValidateChildFormulaTargetAgainstModuleRelation() {
        ModuleDefinition module = ModuleDefinition.builder("sales.invoice", "Invoice")
                .entities(List.of(
                        invoiceEntity().withFormulaRules(
                                EntityFormulaRuleDefinition.calculation("lineAmountCalc",
                                        "items.lineAmount", "{items.quantity} * {items.price}")
                        ),
                        invoiceLineEntity()
                ))
                .relations(List.of(EntityRelationDefinition.child("items", "invoice", "invoice_line", "invoiceId")))
                .build();

        validator.validate(module);
    }

    @Test
    void shouldRejectDuplicateFormulaRuleCode() {
        EntityDefinition entity = invoiceEntity()
                .withFormulaRules(
                        EntityFormulaRuleDefinition.calculation("amountCalc", "amount", "{quantity} * {price}"),
                        EntityFormulaRuleDefinition.calculation("amountCalc", "amount", "{quantity} + {price}")
                );

        assertThatThrownBy(() -> validator.validateEntity(entity))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("duplicate formula rule code: amountCalc");
    }

    @Test
    void shouldRejectDuplicateMainFormulaWritersWhenPublishingModule() {
        ModuleDefinition module = ModuleDefinition.builder("sales.invoice", "Invoice")
                .entities(List.of(invoiceEntity().withFormulaRules(
                        EntityFormulaRuleDefinition.calculation("amountByQuantity", "amount", "{quantity} * 2"),
                        EntityFormulaRuleDefinition.calculation("amountByPrice", "amount", "{price} * 2")
                )))
                .build();

        assertThatThrownBy(() -> validator.validate(module))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("multiple calculation rules write field amount");
    }

    @Test
    void shouldRejectCyclicMainFormulaDependenciesWhenPublishingModule() {
        ModuleDefinition module = ModuleDefinition.builder("sales.invoice", "Invoice")
                .entities(List.of(invoiceEntity().withFormulaRules(
                        EntityFormulaRuleDefinition.calculation("amountFromQuantity", "amount", "{quantity} + 1"),
                        EntityFormulaRuleDefinition.calculation("quantityFromAmount", "quantity", "{amount} + 1")
                )))
                .build();

        assertThatThrownBy(() -> validator.validate(module))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("formula calculation dependency cycle");
    }

    @Test
    void shouldRejectChildCalculationThatDependsOnPlannedMainCalculation() {
        ModuleDefinition module = ModuleDefinition.builder("sales.invoice", "Invoice")
                .entities(List.of(
                        invoiceEntity().withFormulaRules(
                                EntityFormulaRuleDefinition.calculation("amountCalc", "amount", "{quantity} * {price}"),
                                EntityFormulaRuleDefinition.calculation("lineAmountCalc", "items.lineAmount",
                                        "{amount} * {items.quantity}")
                        ),
                        invoiceLineEntity()
                ))
                .relations(List.of(EntityRelationDefinition.child("items", "invoice", "invoice_line", "invoiceId")))
                .build();

        assertThatThrownBy(() -> validator.validate(module))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("child calculation depends on planned main-record calculation field amount");
    }

    @Test
    void shouldRejectUnknownMainTargetField() {
        EntityDefinition entity = invoiceEntity()
                .withFormulaRules(EntityFormulaRuleDefinition.calculation("amountCalc", "totalAmount", "{quantity} * {price}"));

        assertThatThrownBy(() -> validator.validateEntity(entity))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("unknown formula target field: invoice.totalAmount");
    }

    @Test
    void shouldRejectInvalidChildTargetPath() {
        EntityDefinition entity = invoiceEntity()
                .withFormulaRules(EntityFormulaRuleDefinition.calculation("amountCalc",
                        "items.sub.lineAmount", "{quantity} * {price}"));

        assertThatThrownBy(() -> validator.validateEntity(entity))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("invalid formula target field: items.sub.lineAmount");
    }

    @Test
    void shouldRejectInvalidFormulaExpressionDuringModuleValidation() {
        EntityDefinition entity = invoiceEntity()
                .withFormulaRules(EntityFormulaRuleDefinition.calculation("amountCalc", "amount", "SUM({quantity}"));

        assertThatThrownBy(() -> validator.validateEntity(entity))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("invalid formula expression: amountCalc");
    }

    @Test
    void shouldRejectJsonFormulaContentWithBlankExpression() {
        EntityDefinition entity = invoiceEntity()
                .withFormulaRules(EntityFormulaRuleDefinition.calculation("amountCalc", "amount", "{\"expression\":\"\"}"));

        assertThatThrownBy(() -> validator.validateEntity(entity))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("invalid formula expression: amountCalc");
    }

    @Test
    void shouldRejectAssignmentInActionAvailabilityExpression() {
        ModuleDefinition module = ModuleDefinition.builder("sales.invoice", "Invoice")
                .entities(List.of(invoiceEntity()))
                .actions(List.of(new EntityActionDefinition("invoice", "delete", "删除", true)
                        .availableWhen("{status} = 'draft'")))
                .build();

        assertThatThrownBy(() -> validator.validate(module))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("action available expression must not assign fields: delete");
    }

    @Test
    void shouldRejectUnknownFieldInActionAvailabilityExpression() {
        ModuleDefinition module = ModuleDefinition.builder("sales.invoice", "Invoice")
                .entities(List.of(invoiceEntity()))
                .relations(List.of())
                .references(List.of())
                .views(List.of())
                .actions(List.of(new EntityActionDefinition("invoice", "delete", "删除", true)
                        .availableWhen("{statsu} == 'draft'")))
                .build();

        assertThatThrownBy(() -> validator.validate(module))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("unknown action available expression field: invoice.statsu");
    }

    @Test
    void shouldDefaultCreateActionToListLevel() {
        EntityActionDefinition action = new EntityActionDefinition("invoice", "create", "新建", true);

        assertThat(action.level()).isEqualTo(EntityActionLevel.LIST);
    }

    @Test
    void shouldValidateChildFieldInActionAvailabilityExpression() {
        ModuleDefinition module = ModuleDefinition.builder("sales.invoice", "Invoice")
                .entities(List.of(invoiceEntity(), invoiceLineEntity()))
                .relations(List.of(EntityRelationDefinition.child("items", "invoice", "invoice_line", "invoiceId")))
                .references(List.of())
                .views(List.of())
                .actions(List.of(new EntityActionDefinition("invoice", "submit", "提交", true)
                        .availableWhen("SUM({items.lineAmount}) > 0")))
                .build();

        validator.validate(module);
    }

    @Test
    void shouldValidateActionAuthInheritanceTarget() {
        ModuleDefinition module = ModuleDefinition.builder("sales.invoice", "Invoice")
                .entities(List.of(invoiceEntity()))
                .relations(List.of())
                .references(List.of())
                .views(List.of())
                .actions(List.of(new EntityActionDefinition("invoice", "submit", "提交", true, null, null, null,
                        true, false, "approve", null, null, null, null)))
                .build();

        assertThatThrownBy(() -> validator.validate(module))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("action auth inherit target is not configured: invoice.approve");
    }

    @Test
    void shouldRejectAnonymousActionWithAuthPolicy() {
        ModuleDefinition module = ModuleDefinition.builder("sales.invoice", "Invoice")
                .entities(List.of(invoiceEntity()))
                .relations(List.of())
                .references(List.of())
                .views(List.of())
                .actions(List.of(new EntityActionDefinition("invoice", "publicPreview", "公开预览", true, null, null,
                        EntityActionAccessMode.ANONYMOUS_ALLOWED, true, false,
                        null, null, null, null, null)))
                .build();

        assertThatThrownBy(() -> validator.validate(module))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("anonymous action must not require auth policy: invoice.publicPreview");
    }

    @Test
    void shouldRejectActionAuthInheritanceTargetWithoutActionAuth() {
        ModuleDefinition module = ModuleDefinition.builder("sales.invoice", "Invoice")
                .entities(List.of(invoiceEntity()))
                .relations(List.of())
                .references(List.of())
                .views(List.of())
                .actions(List.of(
                        new EntityActionDefinition("invoice", "submit", "提交", true, null, null, null,
                                true, false, "preview", null, null, null, null),
                        new EntityActionDefinition("invoice", "preview", "预览", true, null, null,
                                EntityActionAccessMode.LOGIN_REQUIRED, false, false,
                                null, null, null, null, null)
                ))
                .build();

        assertThatThrownBy(() -> validator.validate(module))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("action auth inherit target must require action auth: invoice.preview");
    }

    @Test
    void shouldRejectActionAuthInheritanceCycle() {
        ModuleDefinition module = ModuleDefinition.builder("sales.invoice", "Invoice")
                .entities(List.of(invoiceEntity()))
                .relations(List.of())
                .references(List.of())
                .views(List.of())
                .actions(List.of(
                        new EntityActionDefinition("invoice", "submit", "提交", true, null, null, null,
                                true, false, "approve", null, null, null, null),
                        new EntityActionDefinition("invoice", "approve", "审核", true, null, null, null,
                                true, false, "submit", null, null, null, null)
                ))
                .build();

        assertThatThrownBy(() -> validator.validate(module))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("action auth inherit cycle: invoice.submit");
    }

    @Test
    void shouldRejectUnknownChildFormulaTargetField() {
        ModuleDefinition module = ModuleDefinition.builder("sales.invoice", "Invoice")
                .entities(List.of(
                        invoiceEntity().withFormulaRules(
                                EntityFormulaRuleDefinition.calculation("lineAmountCalc",
                                        "items.missingAmount", "{items.quantity} * {items.price}")
                        ),
                        invoiceLineEntity()
                ))
                .relations(List.of(EntityRelationDefinition.child("items", "invoice", "invoice_line", "invoiceId")))
                .build();

        assertThatThrownBy(() -> validator.validate(module))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("unknown formula target field: invoice_line.missingAmount");
    }

    @Test
    void shouldRejectUnknownChildFormulaTargetRelation() {
        ModuleDefinition module = ModuleDefinition.builder("sales.invoice", "Invoice")
                .entities(List.of(
                        invoiceEntity().withFormulaRules(
                                EntityFormulaRuleDefinition.calculation("lineAmountCalc",
                                        "details.lineAmount", "{details.quantity} * {details.price}")
                        ),
                        invoiceLineEntity()
                ))
                .relations(List.of(EntityRelationDefinition.child("items", "invoice", "invoice_line", "invoiceId")))
                .build();

        assertThatThrownBy(() -> validator.validate(module))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("unknown formula target relation: invoice.details");
    }

    @Test
    void shouldRejectChildTargetFormulaDirectlyReadingAnotherChildRelation() {
        ModuleDefinition module = ModuleDefinition.builder("sales.invoice", "Invoice")
                .entities(List.of(
                        invoiceEntity().withFormulaRules(
                                EntityFormulaRuleDefinition.calculation("lineAmountCalc",
                                        "items.lineAmount", "{discounts.rate} * {items.price}")
                        ),
                        invoiceLineEntity(),
                        invoiceDiscountEntity()
                ))
                .relations(List.of(
                        EntityRelationDefinition.child("items", "invoice", "invoice_line", "invoiceId"),
                        EntityRelationDefinition.child("discounts", "invoice", "invoice_discount", "invoiceId")
                ))
                .build();

        assertThatThrownBy(() -> validator.validate(module))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("child target formula cannot directly read another child table: discounts.rate");
    }

    private EntityDefinition invoiceEntity() {
        return new EntityDefinition("invoice", "sales_invoice", "Invoice", List.of(
                FieldDefinition.decimal("quantity", "Quantity").precision(18, 2),
                FieldDefinition.decimal("price", "Price").precision(18, 2),
                FieldDefinition.decimal("amount", "Amount").precision(18, 2)
        ));
    }

    private EntityDefinition invoiceLineEntity() {
        return new EntityDefinition("invoice_line", "sales_invoice_line", "Invoice Line", List.of(
                FieldDefinition.string("invoiceId", "Invoice").column("invoice_id"),
                FieldDefinition.decimal("quantity", "Quantity").precision(18, 2),
                FieldDefinition.decimal("price", "Price").precision(18, 2),
                FieldDefinition.decimal("lineAmount", "Line Amount").column("line_amount").precision(18, 2)
        ));
    }

    private EntityDefinition invoiceDiscountEntity() {
        return new EntityDefinition("invoice_discount", "sales_invoice_discount", "Invoice Discount", List.of(
                FieldDefinition.string("invoiceId", "Invoice").column("invoice_id"),
                FieldDefinition.decimal("rate", "Rate").precision(18, 2)
        ));
    }
}
