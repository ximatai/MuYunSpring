package net.ximatai.muyun.spring.dynamic.metadata;


import net.ximatai.muyun.spring.common.platform.ActionDefaultGrantPolicy;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.ability.reference.ReferenceIntegrityPolicy;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargetUnavailablePolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModuleDefinitionValidatorTest {
    private final ModuleDefinitionValidator validator = new ModuleDefinitionValidator();

    @Test
    void shouldRejectDiscriminatedReferenceWithUnknownDependencyField() {
        net.ximatai.muyun.spring.ability.reference.ReferencePlan reference = new net.ximatai.muyun.spring.ability.reference.ReferencePlan(
                "scopeId", net.ximatai.muyun.spring.ability.reference.ReferenceTarget.of("iam", "organization"),
                net.ximatai.muyun.spring.ability.reference.ReferenceCardinality.ONE, List.of(),
                net.ximatai.muyun.spring.ability.reference.ReferenceIntegrityPolicy.DEFAULT,
                net.ximatai.muyun.spring.ability.reference.ReferenceTenantScope.SAME_TENANT,
                List.of(new net.ximatai.muyun.spring.ability.reference.ReferenceCandidateDependency("unknownScopeId", "tenantId", true)),
                List.of());
        var branch = new net.ximatai.muyun.spring.ability.discriminator.DiscriminatedValueCasePlan(
                "organization", net.ximatai.muyun.spring.ability.discriminator.DiscriminatedValueSource.REFERENCE,
                null, null, reference);
        EntityDefinition entity = new EntityDefinition("scope_rule", "sales_scope_rule", "Scope rule", List.of(
                FieldDefinition.string("scopeType", "Scope type").column("scope_type"),
                FieldDefinition.string("scopeId", "Scope id").column("scope_id")), Set.of());
        ModuleDefinition module = ModuleDefinition.builder("sales.scope_rule", "Scope rule")
                .entities(List.of(entity))
                .discriminatedValues(List.of(new EntityDiscriminatedValueDefinition("scope_rule", "scopeId", "scopeType",
                        Set.of("organization"), List.of(branch))))
                .build();

        assertThatThrownBy(() -> validator.validate(module))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("unknown discriminator reference dependency");
    }

    @Test
    void shouldRejectRestrictPolicyForManyReference() {
        ModuleDefinition module = ModuleDefinition.builder("sales.invoice", "Invoice")
                .entities(List.of(
                        new EntityDefinition("invoice", "sales_invoice", "Invoice", List.of(
                                FieldDefinition.titleField()), Set.of(EntityCapability.REFERENCE)),
                        new EntityDefinition("line", "sales_invoice_line", "Line", List.of(
                                FieldDefinition.string("invoiceIds", "Invoices").column("invoice_ids").length(256)))))
                .references(List.of(EntityReferenceDefinition
                        .to("line", "invoiceIds", "sales.invoice.invoice")
                        .many()
                        .withIntegrity(new ReferenceIntegrityPolicy(ReferenceTargetUnavailablePolicy.RESTRICT))))
                .build();

        assertThatThrownBy(() -> validator.validate(module))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("RESTRICT reference deletion requires cardinality ONE: invoiceIds");
    }

    @Test
    void shouldRejectCascadeDeletePolicyForManyReference() {
        ModuleDefinition module = ModuleDefinition.builder("sales.invoice", "Invoice")
                .entities(List.of(
                        new EntityDefinition("invoice", "sales_invoice", "Invoice", List.of(
                                FieldDefinition.titleField()), Set.of(EntityCapability.REFERENCE)),
                        new EntityDefinition("line", "sales_invoice_line", "Line", List.of(
                                FieldDefinition.string("invoiceIds", "Invoices").column("invoice_ids").length(256)))))
                .references(List.of(EntityReferenceDefinition
                        .to("line", "invoiceIds", "sales.invoice.invoice")
                        .many()
                        .withIntegrity(new ReferenceIntegrityPolicy(
                                ReferenceTargetUnavailablePolicy.CASCADE_DELETE))))
                .build();

        assertThatThrownBy(() -> validator.validate(module))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("CASCADE_DELETE reference deletion requires cardinality ONE: invoiceIds");
    }

    @Test
    void shouldValidateEveryInModuleSelectionProjectionHopAndTerminalField() {
        EntityDefinition line = new EntityDefinition("line", "sales_line", "Line", List.of(
                FieldDefinition.string("customerId", "Customer").column("customer_id")));
        EntityDefinition customer = new EntityDefinition("customer", "sales_customer", "Customer", List.of(
                FieldDefinition.titleField(), FieldDefinition.string("organizationId", "Organization").column("organization_id")),
                Set.of(EntityCapability.CRUD, EntityCapability.REFERENCE));
        EntityDefinition organization = new EntityDefinition("organization", "sales_organization", "Organization", List.of(
                FieldDefinition.titleField(), FieldDefinition.string("regionCode", "Region").column("region_code")),
                Set.of(EntityCapability.CRUD, EntityCapability.REFERENCE));
        EntityReferenceDefinition customerReference = EntityReferenceDefinition
                .to("line", "customerId", "sales.contract.customer")
                .withRuntimeConfig(null, null, null, null, Set.of("organizationId.regionCode"));
        ModuleDefinition module = ModuleDefinition.builder("sales.contract", "Contract")
                .entities(List.of(line, customer, organization))
                .references(List.of(customerReference,
                        EntityReferenceDefinition.to("customer", "organizationId", "sales.contract.organization")))
                .build();

        validator.validate(module);
    }

    @Test
    void shouldRejectManySelectionProjectionHopAtDynamicModulePublishTime() {
        EntityDefinition line = new EntityDefinition("line", "sales_line", "Line", List.of(
                FieldDefinition.string("customerId", "Customer").column("customer_id")));
        EntityDefinition customer = new EntityDefinition("customer", "sales_customer", "Customer", List.of(
                FieldDefinition.titleField(), FieldDefinition.string("organizationIds", "Organizations").column("organization_ids")),
                Set.of(EntityCapability.CRUD, EntityCapability.REFERENCE));
        EntityDefinition organization = new EntityDefinition("organization", "sales_organization", "Organization", List.of(
                FieldDefinition.titleField(), FieldDefinition.string("regionCode", "Region").column("region_code")),
                Set.of(EntityCapability.CRUD, EntityCapability.REFERENCE));
        ModuleDefinition module = ModuleDefinition.builder("sales.contract", "Contract")
                .entities(List.of(line, customer, organization))
                .references(List.of(
                        EntityReferenceDefinition.to("line", "customerId", "sales.contract.customer")
                                .withRuntimeConfig(null, null, null, null, Set.of("organizationIds.regionCode")),
                        EntityReferenceDefinition.to("customer", "organizationIds", "sales.contract.organization").many()))
                .build();

        assertThatThrownBy(() -> validator.validate(module))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("selection projection hop requires cardinality ONE: sales.contract.customer.organizationIds");
    }

    @Test
    void shouldRejectCustomActionThatConflictsWithReservedWebPath() {
        ModuleDefinition module = ModuleDefinition.builder("sales.contract", "Contract")
                .entities(List.of(contractEntity()))
                .relations(List.of())
                .references(List.of())
                .views(List.of())
                .associationViews(List.of())
                .actions(List.of(customAction("query")))
                .build();

        assertThatThrownBy(() -> validator.validate(module))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("custom action conflicts with reserved web action path: contract.query");
    }

    @Test
    void shouldRejectCustomActionThatConflictsWithPlatformStandardPath() {
        ModuleDefinition module = ModuleDefinition.builder("sales.contract", "Contract")
                .entities(List.of(contractEntity()))
                .relations(List.of())
                .references(List.of())
                .views(List.of())
                .associationViews(List.of())
                .actions(List.of(customAction("create")))
                .build();

        assertThatThrownBy(() -> validator.validate(module))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("custom action conflicts with reserved web action path: contract.create");
    }

    @Test
    void shouldRejectConfiguredStandardActionWithCustomCategoryOnReservedPath() {
        ModuleDefinition module = ModuleDefinition.builder("sales.contract", "Contract")
                .entities(List.of(contractEntity()))
                .relations(List.of())
                .references(List.of())
                .views(List.of())
                .associationViews(List.of())
                .actions(List.of(new EntityActionDefinition("contract", "delete", "Delete", true, EntityActionLevel.RECORD,
                        EntityActionCategory.CUSTOM, EntityActionAccessMode.AUTH_REQUIRED,
                        true, false, null, null, null, EntityActionExecutorType.SERVICE, "deleteExecutor")))
                .build();

        assertThatThrownBy(() -> validator.validate(module))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("custom action conflicts with reserved web action path: contract.delete");
    }

    @Test
    void shouldRejectStandardActionConfiguredWithCustomExecutor() {
        ModuleDefinition module = ModuleDefinition.builder("sales.contract", "Contract")
                .entities(List.of(contractEntity()))
                .relations(List.of())
                .references(List.of())
                .views(List.of())
                .associationViews(List.of())
                .actions(List.of(new EntityActionDefinition("contract", "query", "Query", true, EntityActionLevel.LIST,
                        EntityActionCategory.STANDARD, EntityActionAccessMode.AUTH_REQUIRED,
                        true, false, null, null, null, EntityActionExecutorType.SERVICE, "queryExecutor")))
                .build();

        assertThatThrownBy(() -> validator.validate(module))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("standard action executor must be STANDARD: contract.query");
    }

    @Test
    void shouldRejectStandardActionConfiguredWithWrongLevel() {
        ModuleDefinition module = ModuleDefinition.builder("sales.contract", "Contract")
                .entities(List.of(contractEntity()))
                .relations(List.of())
                .references(List.of())
                .views(List.of())
                .associationViews(List.of())
                .actions(List.of(new EntityActionDefinition("contract", "delete", "Delete", true, EntityActionLevel.LIST,
                        EntityActionCategory.STANDARD, EntityActionAccessMode.AUTH_REQUIRED,
                        true, false, null, null, null, EntityActionExecutorType.STANDARD, null)))
                .build();

        assertThatThrownBy(() -> validator.validate(module))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("standard action level must match platform action: contract.delete");
    }

    @Test
    void shouldRejectScopedDefaultGrantWithoutDataAuth() {
        ModuleDefinition module = ModuleDefinition.builder("sales.contract", "Contract")
                .entities(List.of(contractEntity()))
                .relations(List.of())
                .references(List.of())
                .views(List.of())
                .associationViews(List.of())
                .actions(List.of(new EntityActionDefinition("contract", "follow", "Follow", true, EntityActionLevel.RECORD,
                        EntityActionCategory.CUSTOM, EntityActionAccessMode.AUTH_REQUIRED,
                        true, false, ActionDefaultGrantPolicy.OWNER, null, null, null,
                        EntityActionExecutorType.SERVICE, "followExecutor")))
                .build();

        assertThatThrownBy(() -> validator.validate(module))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("scoped default grant requires data auth: contract.follow");
    }

    @Test
    void shouldRejectLoginOnlyActionWithDefaultGrantPolicy() {
        ModuleDefinition module = ModuleDefinition.builder("sales.contract", "Contract")
                .entities(List.of(contractEntity()))
                .relations(List.of())
                .references(List.of())
                .views(List.of())
                .associationViews(List.of())
                .actions(List.of(new EntityActionDefinition("contract", "profile", "Profile", true, EntityActionLevel.RECORD,
                        EntityActionCategory.CUSTOM, EntityActionAccessMode.LOGIN_REQUIRED,
                        false, false, ActionDefaultGrantPolicy.ANY_LOGIN_USER, null, null, null,
                        EntityActionExecutorType.SERVICE, "profileExecutor")))
                .build();

        assertThatThrownBy(() -> validator.validate(module))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("login-only action must not require auth policy: contract.profile");
    }

    @Test
    void shouldRejectStandardCategoryWhenActionIsNotPlatformStandardAction() {
        ModuleDefinition module = ModuleDefinition.builder("sales.contract", "Contract")
                .entities(List.of(contractEntity()))
                .relations(List.of())
                .references(List.of())
                .views(List.of())
                .associationViews(List.of())
                .actions(List.of(new EntityActionDefinition("contract", "submit", "Submit", true, EntityActionLevel.RECORD,
                        EntityActionCategory.STANDARD, EntityActionAccessMode.AUTH_REQUIRED,
                        true, false, null, null, null, EntityActionExecutorType.STANDARD, null)))
                .build();

        assertThatThrownBy(() -> validator.validate(module))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("standard action is not supported by entity: contract.submit");
    }

    @Test
    void shouldRejectDialogActionWithoutExecutorKey() {
        ModuleDefinition module = ModuleDefinition.builder("sales.contract", "Contract")
                .entities(List.of(contractEntity()))
                .relations(List.of())
                .references(List.of())
                .views(List.of())
                .associationViews(List.of())
                .actions(List.of(new EntityActionDefinition("contract", "submitDialog", "Submit Dialog", true, EntityActionLevel.RECORD,
                        EntityActionCategory.DIALOG, EntityActionAccessMode.AUTH_REQUIRED,
                        true, false, null, null, null, EntityActionExecutorType.DIALOG, null)))
                .build();

        assertThatThrownBy(() -> validator.validate(module))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("dialog action requires executor key: submitDialog");
    }

    @Test
    void shouldRequireDataScopeCapabilityForDataAuthAction() {
        ModuleDefinition module = ModuleDefinition.builder("sales.contract", "Contract")
                .entities(List.of(contractEntity()))
                .relations(List.of())
                .references(List.of())
                .views(List.of())
                .associationViews(List.of())
                .actions(List.of(new EntityActionDefinition("contract", "submit", "Submit", true, EntityActionLevel.RECORD,
                        EntityActionCategory.CUSTOM, EntityActionAccessMode.AUTH_REQUIRED,
                        true, true, null, null, null, EntityActionExecutorType.SERVICE, "submitExecutor")))
                .build();

        assertThatThrownBy(() -> validator.validate(module))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("data auth action requires DATA_SCOPE capability: contract.submit");
    }

    @Test
    void shouldAllowDataAuthActionWhenEntitySupportsDataScope() {
        ModuleDefinition module = ModuleDefinition.builder("sales.contract", "Contract")
                .entities(List.of(contractEntity().withCapabilities(EntityCapability.DATA_SCOPE)))
                .relations(List.of())
                .references(List.of())
                .views(List.of())
                .associationViews(List.of())
                .actions(List.of(new EntityActionDefinition("contract", "submit", "Submit", true, EntityActionLevel.RECORD,
                        EntityActionCategory.CUSTOM, EntityActionAccessMode.AUTH_REQUIRED,
                        true, true, null, null, null, EntityActionExecutorType.SERVICE, "submitExecutor")))
                .build();

        validator.validate(module);
    }

    @Test
    void shouldAllowMeasureUnitFieldWithSelectableCompanionAndBaseValue() {
        ModuleDefinition module = new ModuleDefinition(
                "sales.contract",
                "Contract",
                List.of(measureEntity(selectableMeasureField()))
        );

        validator.validate(module);
    }

    @Test
    void shouldAllowMeasureUnitFieldWithFixedUnitAndBaseValue() {
        ModuleDefinition module = new ModuleDefinition(
                "sales.contract",
                "Contract",
                List.of(measureEntity(fixedMeasureField()))
        );

        validator.validate(module);
    }

    @Test
    void shouldRejectMeasureUnitFieldWhenBaseValueFieldIsMissing() {
        ModuleDefinition module = new ModuleDefinition(
                "sales.contract",
                "Contract",
                List.of(new EntityDefinition("contract", "sales_contract", "Contract", List.of(
                        selectableMeasureField(),
                        FieldDefinition.string("quantityUnit", "Quantity Unit").column("quantity_unit").length(64)
                )))
        );

        assertThatThrownBy(() -> validator.validate(module))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("unknown measure base value field: contract.quantityBase");
    }

    @Test
    void shouldRejectMeasureUnitFieldWhenUnitCompanionIsNotText() {
        ModuleDefinition module = new ModuleDefinition(
                "sales.contract",
                "Contract",
                List.of(new EntityDefinition("contract", "sales_contract", "Contract", List.of(
                        selectableMeasureField(),
                        FieldDefinition.decimal("quantityUnit", "Quantity Unit").column("quantity_unit").precision(18, 2),
                        FieldDefinition.decimal("quantityBase", "Quantity Base").column("quantity_base").precision(18, 2)
                )))
        );

        assertThatThrownBy(() -> validator.validate(module))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("measure unit companion field must be text: contract.quantityUnit");
    }

    @Test
    void shouldRejectMeasureUnitFieldWhenOwnerIsNotNumeric() {
        ModuleDefinition module = new ModuleDefinition(
                "sales.contract",
                "Contract",
                List.of(measureEntity(FieldDefinition.string("quantity", "Quantity").length(64)
                        .measureUnit(selectableMeasureDefinition("quantityBase", "skuId"))))
        );

        assertThatThrownBy(() -> validator.validate(module))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("measure unit field requires numeric owner: contract.quantity");
    }

    @Test
    void shouldRejectMeasureUnitFieldWhenBaseValuePointsToOwner() {
        ModuleDefinition module = new ModuleDefinition(
                "sales.contract",
                "Contract",
                List.of(new EntityDefinition("contract", "sales_contract", "Contract", List.of(
                        FieldDefinition.decimal("quantity", "Quantity").precision(18, 2)
                                .measureUnit(selectableMeasureDefinition("quantity", "skuId")),
                        FieldDefinition.string("quantityUnit", "Quantity Unit").column("quantity_unit").length(64),
                        FieldDefinition.string("skuId", "SKU").column("sku_id").length(64)
                )))
        );

        assertThatThrownBy(() -> validator.validate(module))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("measure base value field must be different from owner: contract.quantity");
    }

    @Test
    void shouldRejectMeasureUnitFieldWhenConversionScopeFieldIsMissing() {
        ModuleDefinition module = new ModuleDefinition(
                "sales.contract",
                "Contract",
                List.of(new EntityDefinition("contract", "sales_contract", "Contract", List.of(
                        FieldDefinition.decimal("quantity", "Quantity").precision(18, 2)
                                .measureUnit(selectableMeasureDefinition("quantityBase", "skuId")),
                        FieldDefinition.string("quantityUnit", "Quantity Unit").column("quantity_unit").length(64),
                        FieldDefinition.decimal("quantityBase", "Quantity Base").column("quantity_base").precision(18, 2)
                )))
        );

        assertThatThrownBy(() -> validator.validate(module))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("unknown measure conversion scope field: contract.skuId");
    }

    private EntityActionDefinition customAction(String actionCode) {
        return new EntityActionDefinition("contract", actionCode, "Custom " + actionCode, true, EntityActionLevel.LIST,
                EntityActionCategory.CUSTOM, EntityActionAccessMode.AUTH_REQUIRED,
                true, false, null, null, null, EntityActionExecutorType.SERVICE, actionCode + "Executor");
    }

    private EntityDefinition contractEntity() {
        return new EntityDefinition("contract", "sales_contract", "Contract", List.of(
                FieldDefinition.string("code", "Code").length(64).required()
        ));
    }

    private EntityDefinition measureEntity(FieldDefinition measureField) {
        return new EntityDefinition("contract", "sales_contract", "Contract", List.of(
                measureField,
                FieldDefinition.string("quantityUnit", "Quantity Unit").column("quantity_unit").length(64),
                FieldDefinition.decimal("quantityBase", "Quantity Base").column("quantity_base").precision(18, 2),
                FieldDefinition.string("skuId", "SKU").column("sku_id").length(64)
        ));
    }

    private FieldDefinition selectableMeasureField() {
        return FieldDefinition.decimal("quantity", "Quantity").precision(18, 2)
                .measureUnit(selectableMeasureDefinition("quantityBase", "skuId"));
    }

    private FieldMeasureUnitDefinition selectableMeasureDefinition(String baseValueFieldName, String conversionScopeFieldName) {
        return new FieldMeasureUnitDefinition(
                        "quantity",
                        FieldMeasureUnitMode.SELECTABLE,
                        null,
                        "box",
                        "quantityUnit",
                baseValueFieldName,
                        "quantity",
                        "bottle",
                        FieldMeasureUnitConversionMode.BUSINESS_RULE,
                conversionScopeFieldName,
                        true
        );
    }

    private FieldDefinition fixedMeasureField() {
        return FieldDefinition.decimal("quantity", "Quantity").precision(18, 2)
                .measureUnit(new FieldMeasureUnitDefinition(
                        "quantity",
                        FieldMeasureUnitMode.FIXED,
                        "bottle",
                        null,
                        null,
                        "quantityBase",
                        "quantity",
                        "bottle",
                        FieldMeasureUnitConversionMode.LINEAR,
                        null,
                        false
                ));
    }
}
