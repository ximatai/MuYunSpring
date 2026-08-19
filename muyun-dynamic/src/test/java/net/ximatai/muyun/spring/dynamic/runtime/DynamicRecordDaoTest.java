package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.spring.common.model.title.TitleField;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.metadata.DBInfo;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.database.core.orm.SqlRawCondition;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.formula.FormulaIssueLevel;
import net.ximatai.muyun.spring.common.formula.FormulaRuntimeReport;
import net.ximatai.muyun.spring.common.identity.ActingContext;
import net.ximatai.muyun.spring.common.identity.ActingContextHolder;
import net.ximatai.muyun.spring.common.identity.BusinessPrincipal;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceOption;
import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.spring.common.model.capability.EnabledCapable;
import net.ximatai.muyun.spring.common.model.capability.TitledCapable;
import net.ximatai.muyun.spring.common.model.capability.TreeCapable;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContextHolder;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityFormulaRuleDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityRelationDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DynamicRecordDaoTest {
    private static final String SCHEMA = "public";
    private static final String TABLE = "app_contract";

    @Test
    void shouldInsertWithTableColumnsAndEntityDefaults() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.insertItem(eq(SCHEMA), eq(TABLE), anyMap(), eq("id")))
                .thenAnswer(invocation -> invocation.<Map<String, Object>>getArgument(2).get("id"));
        DynamicRecord record = new DynamicRecord(contractEntity())
                .setValue("code", "C-001")
                .setValue("amount", BigDecimal.TEN);
        record.setTenantId("tenant-a");

        String id = entityService(operations).insert(record);

        ArgumentCaptor<Map<String, Object>> body = mapCaptor();
        verify(operations).insertItem(eq(SCHEMA), eq(TABLE), body.capture(), eq("id"));
        assertThat(id).hasSize(32);
        assertThat(body.getValue())
                .containsEntry("id", id)
                .containsEntry("tenant_id", "tenant-a")
                .containsEntry("code", "C-001")
                .containsEntry("amount", BigDecimal.TEN)
                .containsEntry("version", 0)
                .containsEntry("deleted", Boolean.FALSE);
        assertThat(body.getValue()).containsEntry("deleted_at", null);
        assertThat(body.getValue()).containsEntry("deleted_by", null);
        assertThat(body.getValue()).containsKeys("created_at", "updated_at");
    }

    @Test
    void shouldNotPersistVirtualFieldValues() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.insertItem(eq(SCHEMA), eq(TABLE), anyMap(), eq("id")))
                .thenAnswer(invocation -> invocation.<Map<String, Object>>getArgument(2).get("id"));
        EntityDefinition entity = new EntityDefinition(
                "contract",
                TABLE,
                "Contract",
                List.of(
                        FieldDefinition.string("code", "Code").length(64).required(),
                        FieldDefinition.string("displayCode", "Display Code").column("display_code").virtual()
                )
        );
        DynamicRecord record = new DynamicRecord(entity)
                .setValue("code", "C-001")
                .setValue("displayCode", "C-001 / Customer");

        new DynamicEntityService(new DynamicRecordDao(operations, entity), "sales.contract").insert(record);

        ArgumentCaptor<Map<String, Object>> body = mapCaptor();
        verify(operations).insertItem(eq(SCHEMA), eq(TABLE), body.capture(), eq("id"));
        assertThat(body.getValue())
                .containsEntry("code", "C-001")
                .doesNotContainKey("display_code");
    }

    @Test
    void shouldUseEntitySchemaWhenWritingDynamicRecords() {
        IDatabaseOperations<Object> operations = operations();
        EntityDefinition entity = new EntityDefinition(
                "contract",
                "tenant_a",
                TABLE,
                "Contract",
                List.of(FieldDefinition.string("code", "Code")),
                java.util.Set.of(EntityCapability.CRUD)
        );
        when(operations.insertItem(eq("tenant_a"), eq(TABLE), anyMap(), eq("id")))
                .thenAnswer(invocation -> invocation.<Map<String, Object>>getArgument(2).get("id"));
        DynamicRecord record = new DynamicRecord(entity).setValue("code", "C-001");

        new DynamicEntityService(new DynamicRecordDao(operations, entity), "sales.contract").insert(record);

        verify(operations).insertItem(eq("tenant_a"), eq(TABLE), anyMap(), eq("id"));
    }

    @Test
    void shouldWriteAndReadDynamicDataScopeAbilityFields() {
        IDatabaseOperations<Object> operations = operations();
        EntityDefinition entity = contractEntity().withCapabilities(EntityCapability.DATA_SCOPE);
        when(operations.insertItem(eq(SCHEMA), eq(TABLE), anyMap(), eq("id")))
                .thenAnswer(invocation -> invocation.<Map<String, Object>>getArgument(2).get("id"));
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(Map.ofEntries(
                Map.entry("id", "contract-1"),
                Map.entry("tenant_id", "tenant-a"),
                Map.entry("version", 0),
                Map.entry("deleted", Boolean.FALSE),
                Map.entry("auth_user_id", "user-1"),
                Map.entry("auth_assignee_ids", "user-2,user-3"),
                Map.entry("auth_member_ids", "user-4"),
                Map.entry("auth_organization_id", "org-1"),
                Map.entry("auth_department_id", "dept-1"),
                Map.entry("auth_module_alias", "sales.contract"),
                Map.entry("code", "C-001"),
                Map.entry("amount", BigDecimal.TEN)
        )));
        DynamicEntityService service = new DynamicEntityService(new DynamicRecordDao(operations, entity), "sales.contract");
        DynamicRecord record = new DynamicRecord(entity)
                .setValue("code", "C-001")
                .setValue("amount", BigDecimal.TEN);
        record.setTenantId("tenant-a");
        record.setAuthUserId("user-1");
        record.setAuthAssigneeIds("user-2,user-3");
        record.setAuthMemberIds("user-4");
        record.setAuthOrganizationId("org-1");
        record.setAuthDepartmentId("dept-1");
        record.setAuthModuleAlias("sales.contract");

        service.insert(record);
        DynamicRecord selected = service.select("contract-1");

        ArgumentCaptor<Map<String, Object>> body = mapCaptor();
        verify(operations).insertItem(eq(SCHEMA), eq(TABLE), body.capture(), eq("id"));
        assertThat(body.getValue())
                .containsEntry("auth_user_id", "user-1")
                .containsEntry("auth_assignee_ids", "user-2,user-3")
                .containsEntry("auth_member_ids", "user-4")
                .containsEntry("auth_organization_id", "org-1")
                .containsEntry("auth_department_id", "dept-1")
                .containsEntry("auth_module_alias", "sales.contract");
        assertThat(selected.getAuthUserId()).isEqualTo("user-1");
        assertThat(selected.getAuthAssigneeIds()).isEqualTo("user-2,user-3");
        assertThat(selected.getAuthMemberIds()).isEqualTo("user-4");
        assertThat(selected.getAuthOrganizationId()).isEqualTo("org-1");
        assertThat(selected.getAuthDepartmentId()).isEqualTo("dept-1");
        assertThat(selected.getAuthModuleAlias()).isEqualTo("sales.contract");
    }

    @Test
    void shouldPrepareDynamicDataScopeOwnershipFromCurrentUserOnInsert() {
        IDatabaseOperations<Object> operations = operations();
        EntityDefinition entity = contractEntity().withCapabilities(EntityCapability.DATA_SCOPE);
        when(operations.insertItem(eq(SCHEMA), eq(TABLE), anyMap(), eq("id")))
                .thenAnswer(invocation -> invocation.<Map<String, Object>>getArgument(2).get("id"));
        DynamicEntityService service = new DynamicEntityService(new DynamicRecordDao(operations, entity), "sales.contract");
        DynamicRecord record = new DynamicRecord(entity)
                .setValue("code", "C-001")
                .setValue("amount", BigDecimal.TEN);

        try (CurrentUserContext.Scope user = CurrentUserContext.use(CurrentUser.tenantUser(
                "user-1", "User", "tenant-a", "org-1"));
             ActionExecutionContextHolder.Scope action = ActionExecutionContextHolder.use(
                     ActionExecutionContext.ofPlatformAction(
                             "sales.contract",
                             PlatformAction.CREATE,
                             java.util.Set.of(),
                             CurrentUserContext.currentUser()))) {
            service.insert(record);
        }

        ArgumentCaptor<Map<String, Object>> body = mapCaptor();
        verify(operations).insertItem(eq(SCHEMA), eq(TABLE), body.capture(), eq("id"));
        assertThat(body.getValue())
                .containsEntry("auth_user_id", "user-1")
                .containsEntry("auth_organization_id", "org-1")
                .containsEntry("auth_module_alias", "sales.contract");
        assertThat(body.getValue()).doesNotContainKey("auth_department_id");
    }

    @Test
    void shouldPrepareDynamicDataScopeOwnershipFromActingPrincipalOnInsert() {
        IDatabaseOperations<Object> operations = operations();
        EntityDefinition entity = contractEntity().withCapabilities(EntityCapability.DATA_SCOPE);
        when(operations.insertItem(eq(SCHEMA), eq(TABLE), anyMap(), eq("id")))
                .thenAnswer(invocation -> invocation.<Map<String, Object>>getArgument(2).get("id"));
        DynamicEntityService service = new DynamicEntityService(new DynamicRecordDao(operations, entity), "sales.contract");
        DynamicRecord record = new DynamicRecord(entity)
                .setValue("code", "C-001")
                .setValue("amount", BigDecimal.TEN);
        CurrentUser operator = CurrentUser.tenantUser("assistant-user", "Assistant", "tenant-a", "org-assistant");
        BusinessPrincipal principal = BusinessPrincipal.employeePosition(
                "employee-principal", "org-principal", "dept-principal", "position-principal");

        try (CurrentUserContext.Scope user = CurrentUserContext.use(operator);
             ActingContextHolder.Scope acting = ActingContextHolder.use(new ActingContext(
                     "delegation-1", operator, principal, "sales.contract", "create"));
             ActionExecutionContextHolder.Scope action = ActionExecutionContextHolder.use(
                     ActionExecutionContext.ofPlatformAction(
                             "sales.contract",
                             PlatformAction.CREATE,
                             java.util.Set.of(),
                             CurrentUserContext.currentUser()))) {
            service.insert(record);
        }

        ArgumentCaptor<Map<String, Object>> body = mapCaptor();
        verify(operations).insertItem(eq(SCHEMA), eq(TABLE), body.capture(), eq("id"));
        assertThat(body.getValue())
                .containsEntry("auth_organization_id", "org-principal")
                .containsEntry("auth_department_id", "dept-principal")
                .containsEntry("auth_module_alias", "sales.contract");
        assertThat(body.getValue()).doesNotContainEntry("auth_user_id", "assistant-user");
    }

    @Test
    void shouldRunLifecycleHooksAroundCrudOperations() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.insertItem(eq(SCHEMA), eq(TABLE), anyMap(), eq("id")))
                .thenAnswer(invocation -> invocation.<Map<String, Object>>getArgument(2).get("id"));
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(Map.of(
                "id", "contract-1",
                "code", "C-001",
                "amount", BigDecimal.TEN,
                "deleted", Boolean.FALSE,
                "version", 2
        )));
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        DynamicRecordDao dao = new DynamicRecordDao(operations, contractEntity());
        DynamicEntityService entityService = DynamicEntityService.withLifecycle(dao, "sales.contract", lifecycle);

        DynamicRecord inserted = new DynamicRecord(contractEntity())
                .setValue("amount", BigDecimal.TEN);
        entityService.insert(inserted);
        DynamicRecord selected = entityService.select("contract-1");
        entityService.update(selected.setValue("amount", BigDecimal.ONE));
        entityService.delete("contract-1");

        assertThat(lifecycle.events)
                .containsExactly(
                        "beforeInsert:HOOK-CODE",
                        "afterSelect:contract-1",
                        "beforeUpdate:3",
                        "beforeDelete:contract-1"
                );
        ArgumentCaptor<Map<String, Object>> insertBody = mapCaptor();
        verify(operations).insertItem(eq(SCHEMA), eq(TABLE), insertBody.capture(), eq("id"));
        assertThat(insertBody.getValue()).containsEntry("code", "HOOK-CODE");
    }

    @Test
    void shouldApplyDynamicFormulaRulesBeforeInsert() {
        IDatabaseOperations<Object> operations = operations();
        EntityDefinition entity = formulaEntity();
        when(operations.insertItem(eq(SCHEMA), eq(TABLE), anyMap(), eq("id")))
                .thenAnswer(invocation -> invocation.<Map<String, Object>>getArgument(2).get("id"));
        DynamicRecord record = new DynamicRecord(entity)
                .setValue("quantity", BigDecimal.valueOf(2))
                .setValue("price", BigDecimal.valueOf(15));

        new DynamicEntityService(new DynamicRecordDao(operations, entity), "sales.contract").insert(record);

        ArgumentCaptor<Map<String, Object>> body = mapCaptor();
        verify(operations).insertItem(eq(SCHEMA), eq(TABLE), body.capture(), eq("id"));
        assertThat((BigDecimal) body.getValue().get("amount")).isEqualByComparingTo("30");
    }

    @Test
    void shouldCalculateVirtualFieldBeforeInsertWithoutPersistingIt() {
        IDatabaseOperations<Object> operations = operations();
        EntityDefinition entity = new EntityDefinition(
                "contract",
                TABLE,
                "Contract",
                List.of(
                        FieldDefinition.string("code", "Code").length(64),
                        FieldDefinition.string("displayCode", "Display Code").column("display_code").virtual()
                )
        ).withFormulaRules(EntityFormulaRuleDefinition.calculation(
                "displayCodeCalc", "displayCode", "'合同-' + {code}"));
        when(operations.insertItem(eq(SCHEMA), eq(TABLE), anyMap(), eq("id")))
                .thenAnswer(invocation -> invocation.<Map<String, Object>>getArgument(2).get("id"));
        DynamicRecord record = new DynamicRecord(entity).setValue("code", "C-001");

        new DynamicEntityService(new DynamicRecordDao(operations, entity), "sales.contract").insert(record);

        ArgumentCaptor<Map<String, Object>> body = mapCaptor();
        verify(operations).insertItem(eq(SCHEMA), eq(TABLE), body.capture(), eq("id"));
        assertThat(record.getValue("displayCode")).isEqualTo("合同-C-001");
        assertThat(body.getValue())
                .containsEntry("code", "C-001")
                .doesNotContainKey("display_code")
                .doesNotContainKey("displayCode");
    }

    @Test
    void shouldPreviewDynamicFormulaRulesWithoutPersistingOrMutatingSourceRecord() {
        IDatabaseOperations<Object> operations = operations();
        EntityDefinition entity = formulaEntity();
        DynamicRecord record = new DynamicRecord(entity)
                .setValue("quantity", BigDecimal.valueOf(2))
                .setValue("price", BigDecimal.valueOf(15));

        DynamicFormulaPreviewResult result = new DynamicEntityService(new DynamicRecordDao(operations, entity),
                "sales.contract").previewFormula(record);

        assertThat((BigDecimal) result.record().getValue("amount")).isEqualByComparingTo("30");
        assertThat(result.changedFields()).containsExactly("amount");
        assertThat(result.report().hasErrors()).isFalse();
        assertThat(record.getValue("amount")).isNull();
        verifyNoInteractions(operations);
    }

    @Test
    void shouldApplyDynamicFormulaRulesAfterLifecycleHooks() {
        IDatabaseOperations<Object> operations = operations();
        EntityDefinition entity = formulaEntity();
        when(operations.insertItem(eq(SCHEMA), eq(TABLE), anyMap(), eq("id")))
                .thenAnswer(invocation -> invocation.<Map<String, Object>>getArgument(2).get("id"));
        DynamicRecordLifecycle lifecycle = new DynamicRecordLifecycle() {
            @Override
            public void beforeInsert(DynamicRecord record) {
                record.setValue("quantity", BigDecimal.valueOf(4));
            }
        };
        DynamicRecord record = new DynamicRecord(entity)
                .setValue("quantity", BigDecimal.valueOf(2))
                .setValue("price", BigDecimal.valueOf(15));

        DynamicEntityService.withLifecycle(new DynamicRecordDao(operations, entity), "sales.contract", lifecycle)
                .insert(record);

        ArgumentCaptor<Map<String, Object>> body = mapCaptor();
        verify(operations).insertItem(eq(SCHEMA), eq(TABLE), body.capture(), eq("id"));
        assertThat((BigDecimal) body.getValue().get("amount")).isEqualByComparingTo("60");
    }

    @Test
    void shouldApplyDynamicFormulaRulesWithExistingValuesBeforePartialUpdate() {
        IDatabaseOperations<Object> operations = operations();
        EntityDefinition entity = formulaEntity();
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(Map.of(
                "id", "contract-1",
                "quantity", BigDecimal.valueOf(2),
                "price", BigDecimal.TEN,
                "amount", BigDecimal.valueOf(20),
                "deleted", Boolean.FALSE,
                "version", 3
        )));
        DynamicRecord record = new DynamicRecord(entity)
                .setValue("price", BigDecimal.valueOf(15));
        record.setId("contract-1");

        new DynamicEntityService(new DynamicRecordDao(operations, entity), "sales.contract").update(record);

        ArgumentCaptor<Map<String, Object>> body = mapCaptor();
        verify(operations).patchUpdateItemWhere(eq(SCHEMA), eq(TABLE), body.capture(), anyMap(), eq("id"));
        assertThat(body.getValue())
                .containsEntry("price", BigDecimal.valueOf(15))
                .doesNotContainKey("quantity");
        assertThat((BigDecimal) body.getValue().get("amount")).isEqualByComparingTo("30");
    }

    @Test
    void shouldCalculateVirtualFieldBeforeUpdateWithoutPersistingIt() {
        IDatabaseOperations<Object> operations = operations();
        EntityDefinition entity = new EntityDefinition(
                "contract",
                TABLE,
                "Contract",
                List.of(
                        FieldDefinition.string("code", "Code").length(64),
                        FieldDefinition.string("displayCode", "Display Code").column("display_code").virtual()
                )
        ).withFormulaRules(EntityFormulaRuleDefinition.calculation(
                "displayCodeCalc", "displayCode", "'合同-' + {code}"));
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(Map.of(
                "id", "contract-1",
                "code", "C-001",
                "deleted", Boolean.FALSE,
                "version", 3
        )));
        DynamicRecord record = new DynamicRecord(entity).setValue("code", "C-002");
        record.setId("contract-1");

        new DynamicEntityService(new DynamicRecordDao(operations, entity), "sales.contract").update(record);

        ArgumentCaptor<Map<String, Object>> body = mapCaptor();
        verify(operations).patchUpdateItemWhere(eq(SCHEMA), eq(TABLE), body.capture(), anyMap(), eq("id"));
        assertThat(record.getValue("displayCode")).isEqualTo("合同-C-002");
        assertThat(body.getValue())
                .containsEntry("code", "C-002")
                .doesNotContainKey("display_code")
                .doesNotContainKey("displayCode");
    }

    @Test
    void shouldIgnoreDisabledFormulaRulesWhenCheckingUpdateRules() {
        EntityDefinition entity = new EntityDefinition(
                "contract",
                TABLE,
                "Contract",
                List.of(
                        FieldDefinition.decimal("quantity", "Quantity").precision(18, 2),
                        FieldDefinition.decimal("price", "Price").precision(18, 2),
                        FieldDefinition.decimal("amount", "Amount").precision(18, 2)
                )
        ).withFormulaRules(EntityFormulaRuleDefinition
                .calculation("amountCalc", "amount", "{quantity} * {price}")
                .disabled());

        assertThat(new DynamicFormulaRuntime("sales.contract", entity, null).hasBeforeUpdateRules(new DynamicRecord(entity))).isFalse();
    }

    @Test
    void shouldRejectDynamicRecordWhenFormulaValidationFails() {
        IDatabaseOperations<Object> operations = operations();
        EntityDefinition entity = new EntityDefinition(
                "contract",
                TABLE,
                "Contract",
                List.of(FieldDefinition.decimal("amount", "Amount").precision(18, 2))
        ).withFormulaRules(EntityFormulaRuleDefinition
                .validation("amountPositive", "amount", "{amount} > 0", "amount must be positive"));
        DynamicRecord record = new DynamicRecord(entity).setValue("amount", BigDecimal.valueOf(-1));

        assertThatThrownBy(() -> new DynamicEntityService(new DynamicRecordDao(operations, entity), "sales.contract")
                .insert(record))
                .isInstanceOf(DynamicFormulaException.class)
                .hasMessageContaining("dynamic formula rule failed: sales.contract.contract: amountPositive")
                .satisfies(error -> {
                    DynamicFormulaException exception = (DynamicFormulaException) error;
                    assertThat(exception.moduleAlias()).isEqualTo("sales.contract");
                    assertThat(exception.entityAlias()).isEqualTo("contract");
                    assertThat(exception.errors()).hasSize(1);
                    assertThat(exception.firstError().ruleId()).isEqualTo("amountPositive");
                    assertThat(exception.firstError().fieldPath()).isEqualTo("amount");
                    assertThat(exception.firstError().code()).isEqualTo("FORMULA_RULE_NOT_MATCHED");
                    assertThat(exception.warnings()).isEmpty();
                });
        verify(operations, never()).insertItem(anyString(), anyString(), anyMap(), anyString());
    }

    @Test
    void shouldReturnFormulaValidationDiagnosisWhenPreviewFails() {
        EntityDefinition entity = new EntityDefinition(
                "contract",
                TABLE,
                "Contract",
                List.of(
                        FieldDefinition.decimal("quantity", "Quantity").precision(18, 2),
                        FieldDefinition.decimal("price", "Price").precision(18, 2),
                        FieldDefinition.decimal("amount", "Amount").precision(18, 2)
                )
        ).withFormulaRules(
                EntityFormulaRuleDefinition.calculation("amountCalc", "amount", "{quantity} * {price}"),
                EntityFormulaRuleDefinition.validation("amountPositive", "amount", "{amount} > 0",
                        "amount must be positive")
        );
        DynamicRecord record = new DynamicRecord(entity)
                .setValue("quantity", BigDecimal.valueOf(2))
                .setValue("price", BigDecimal.valueOf(-15));

        DynamicFormulaPreviewResult result = new DynamicFormulaRuntime("sales.contract", entity, null)
                .preview(record, null);

        assertThat(result.report().hasErrors()).isTrue();
        assertThat(result.report().errors()).anySatisfy(issue ->
                assertThat(issue.message()).isEqualTo("amount must be positive"));
        assertThat(result.changedFields()).isEmpty();
        assertThat(result.record().getValue("amount")).isNull();
    }

    @Test
    void shouldKeepFormulaWarningsOnRecordWithoutBlockingSave() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.insertItem(eq(SCHEMA), eq(TABLE), anyMap(), eq("id")))
                .thenAnswer(invocation -> invocation.<Map<String, Object>>getArgument(2).get("id"));
        EntityDefinition entity = new EntityDefinition(
                "contract",
                TABLE,
                "Contract",
                List.of(FieldDefinition.decimal("amount", "Amount").precision(18, 2))
        ).withFormulaRules(EntityFormulaRuleDefinition
                .validation("amountHighRisk", "amount", "{amount} < 1000", "amount is high")
                .severity(FormulaIssueLevel.WARNING));
        DynamicRecord record = new DynamicRecord(entity).setValue("amount", BigDecimal.valueOf(1500));

        String id = new DynamicEntityService(new DynamicRecordDao(operations, entity), "sales.contract")
                .insert(record);

        assertThat(id).isNotBlank();
        assertThat(record.formulaReport().hasErrors()).isFalse();
        assertThat(record.formulaReport().warnings())
                .hasSize(1)
                .first()
                .satisfies(issue -> {
                    assertThat(issue.ruleId()).isEqualTo("amountHighRisk");
                    assertThat(issue.fieldPath()).isEqualTo("amount");
                    assertThat(issue.code()).isEqualTo("FORMULA_RULE_NOT_MATCHED");
                    assertThat(issue.message()).isEqualTo("amount is high");
                });
        verify(operations).insertItem(eq(SCHEMA), eq(TABLE), anyMap(), eq("id"));
    }

    @Test
    void shouldKeepFormulaWarningsOnRecordAfterUpdate() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(Map.of(
                "id", "contract-1",
                "amount", BigDecimal.valueOf(1200),
                "deleted", Boolean.FALSE,
                "version", 2
        )));
        EntityDefinition entity = new EntityDefinition(
                "contract",
                TABLE,
                "Contract",
                List.of(FieldDefinition.decimal("amount", "Amount").precision(18, 2))
        ).withFormulaRules(EntityFormulaRuleDefinition
                .validation("amountHighRisk", "amount", "{amount} < 1000", "amount is high")
                .severity(FormulaIssueLevel.WARNING));
        DynamicRecord record = new DynamicRecord(entity).setValue("amount", BigDecimal.valueOf(1500));
        record.setId("contract-1");
        record.setVersion(2);

        new DynamicEntityService(new DynamicRecordDao(operations, entity), "sales.contract").update(record);

        assertThat(record.formulaReport().warnings())
                .hasSize(1)
                .first()
                .extracting(issue -> issue.ruleId())
                .isEqualTo("amountHighRisk");
    }

    @Test
    void shouldClearFormulaReportWhenUpdateHasNoFormulaRules() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(Map.of(
                "id", "contract-1",
                "code", "C-001",
                "amount", BigDecimal.TEN,
                "deleted", Boolean.FALSE,
                "version", 2
        )));
        DynamicRecord record = new DynamicRecord(contractEntity());
        FormulaRuntimeReport staleReport = new FormulaRuntimeReport();
        staleReport.warn("stale", "{amount}", "stale warning");
        record.formulaReport(staleReport);
        record.setId("contract-1");
        record.setVersion(2);

        new DynamicEntityService(new DynamicRecordDao(operations, contractEntity()), "sales.contract").update(record);

        assertThat(record.formulaReport().warnings()).isEmpty();
        assertThat(record.formulaReport().errors()).isEmpty();
    }

    @Test
    void shouldNotCopyFormulaReportWithRecordData() {
        DynamicRecord record = new DynamicRecord(contractEntity());
        FormulaRuntimeReport report = new FormulaRuntimeReport();
        report.warn("amountHighRisk", "{amount}", "amount is high");
        record.formulaReport(report);

        DynamicRecord copy = record.copy();

        assertThat(copy.formulaReport().warnings()).isEmpty();
    }

    @Test
    void shouldApplyDynamicFormulaRulesToChildRows() {
        EntityDefinition invoice = new EntityDefinition(
                "invoice",
                "sales_invoice",
                "Invoice",
                List.of(FieldDefinition.decimal("totalAmount", "Total Amount").column("total_amount").precision(18, 2))
        ).withFormulaRules(new EntityFormulaRuleDefinition("lineAmountCalc",
                "SUM({items.lineAmount} = {items.quantity} * {items.price})"));
        EntityDefinition line = new EntityDefinition(
                "invoice_line",
                "sales_invoice_line",
                "Invoice Line",
                List.of(
                        FieldDefinition.decimal("quantity", "Quantity").precision(18, 2),
                        FieldDefinition.decimal("price", "Price").precision(18, 2),
                        FieldDefinition.decimal("lineAmount", "Line Amount").column("line_amount").precision(18, 2)
                )
        );
        ModuleDefinition module = ModuleDefinition.builder("sales.invoice", "Invoice")
                .entities(List.of(invoice, line))
                .relations(List.of(EntityRelationDefinition.child("items", "invoice", "invoice_line", "invoiceId")))
                .build();
        DynamicRecord parent = new DynamicRecord(invoice);
        DynamicRecord child = new DynamicRecord(line)
                .setValue("quantity", BigDecimal.valueOf(3))
                .setValue("price", BigDecimal.valueOf(11));
        parent.setChildren("items", List.of(child));

        new DynamicFormulaRuntime("sales.invoice", invoice, module).beforeInsert(parent);

        assertThat((BigDecimal) child.getValue("lineAmount")).isEqualByComparingTo("33");
    }

    @Test
    void shouldUsePersistedChildRowsForEquivalentPreviewAndUpdateWhenChildRowsAreNotSubmitted() {
        EntityDefinition invoice = new EntityDefinition(
                "invoice",
                "sales_invoice",
                "Invoice",
                List.of(FieldDefinition.decimal("totalAmount", "Total Amount").column("total_amount").precision(18, 2))
        ).withFormulaRules(new EntityFormulaRuleDefinition("totalAmountCalc",
                "{totalAmount} = SUM({ items.lineAmount })"));
        EntityDefinition line = new EntityDefinition(
                "invoice_line",
                "sales_invoice_line",
                "Invoice Line",
                List.of(FieldDefinition.decimal("lineAmount", "Line Amount").column("line_amount").precision(18, 2))
        );
        ModuleDefinition module = ModuleDefinition.builder("sales.invoice", "Invoice")
                .entities(List.of(invoice, line))
                .relations(List.of(EntityRelationDefinition.child("items", "invoice", "invoice_line", "invoiceId")))
                .build();
        DynamicRecord record = new DynamicRecord(invoice).setValue("totalAmount", BigDecimal.valueOf(100));
        record.setId("invoice-1");
        DynamicRecord existingLine = new DynamicRecord(line).setValue("lineAmount", BigDecimal.valueOf(35));
        existingLine.setId("line-1");
        DynamicFormulaRuntime runtime = new DynamicFormulaRuntime("sales.invoice", invoice, module);
        DynamicFormulaPreviewResult preview = runtime.preview(record.copy(), new DynamicRecord(invoice),
                Map.of("items", List.of(existingLine)));

        runtime
                .beforeUpdate(record, null, Map.of("items", List.of(existingLine)));

        assertThat((BigDecimal) preview.record().getValue("totalAmount")).isEqualByComparingTo("35");
        assertThat(preview.changedFields()).containsExactly("totalAmount");
        assertThat((BigDecimal) record.getValue("totalAmount")).isEqualByComparingTo("35");
    }

    @Test
    void shouldApplyChildDependentFormulaRulesOnUpdateWhenChildRowsAreSubmitted() {
        EntityDefinition invoice = new EntityDefinition(
                "invoice",
                "sales_invoice",
                "Invoice",
                List.of(FieldDefinition.decimal("totalAmount", "Total Amount").column("total_amount").precision(18, 2))
        ).withFormulaRules(new EntityFormulaRuleDefinition("totalAmountCalc",
                "{totalAmount} = SUM({ items.lineAmount } = {items.quantity} * {items.price})"));
        EntityDefinition line = new EntityDefinition(
                "invoice_line",
                "sales_invoice_line",
                "Invoice Line",
                List.of(
                        FieldDefinition.decimal("quantity", "Quantity").precision(18, 2),
                        FieldDefinition.decimal("price", "Price").precision(18, 2),
                        FieldDefinition.decimal("lineAmount", "Line Amount").column("line_amount").precision(18, 2)
                )
        );
        ModuleDefinition module = ModuleDefinition.builder("sales.invoice", "Invoice")
                .entities(List.of(invoice, line))
                .relations(List.of(EntityRelationDefinition.child("items", "invoice", "invoice_line", "invoiceId")))
                .build();
        DynamicRecord record = new DynamicRecord(invoice).setValue("totalAmount", BigDecimal.ZERO);
        DynamicRecord first = new DynamicRecord(line)
                .setValue("quantity", BigDecimal.valueOf(2))
                .setValue("price", BigDecimal.valueOf(10));
        DynamicRecord second = new DynamicRecord(line)
                .setValue("quantity", BigDecimal.valueOf(3))
                .setValue("price", BigDecimal.valueOf(20));
        record.setChildren("items", List.of(first, second));

        new DynamicFormulaRuntime("sales.invoice", invoice, module).beforeUpdate(record, null, Map.of());

        assertThat((BigDecimal) record.getValue("totalAmount")).isEqualByComparingTo("80");
        assertThat((BigDecimal) first.getValue("lineAmount")).isEqualByComparingTo("20");
        assertThat((BigDecimal) second.getValue("lineAmount")).isEqualByComparingTo("60");
    }

    @Test
    void shouldApplyChildDependentFormulaRulesOnEmptySubmittedChildRows() {
        EntityDefinition invoice = new EntityDefinition(
                "invoice",
                "sales_invoice",
                "Invoice",
                List.of(FieldDefinition.decimal("totalAmount", "Total Amount").column("total_amount").precision(18, 2))
        ).withFormulaRules(new EntityFormulaRuleDefinition("totalAmountCalc",
                "{totalAmount} = SUM({items.lineAmount})"));
        EntityDefinition line = new EntityDefinition(
                "invoice_line",
                "sales_invoice_line",
                "Invoice Line",
                List.of(FieldDefinition.decimal("lineAmount", "Line Amount").column("line_amount").precision(18, 2))
        );
        ModuleDefinition module = ModuleDefinition.builder("sales.invoice", "Invoice")
                .entities(List.of(invoice, line))
                .relations(List.of(EntityRelationDefinition.child("items", "invoice", "invoice_line", "invoiceId")))
                .build();
        DynamicRecord record = new DynamicRecord(invoice).setValue("totalAmount", BigDecimal.valueOf(100));
        record.setChildren("items", List.of());

        new DynamicFormulaRuntime("sales.invoice", invoice, module).beforeUpdate(record, null, Map.of());

        assertThat((BigDecimal) record.getValue("totalAmount")).isEqualByComparingTo("0");
    }

    @Test
    void shouldKeepUnsubmittedPersistedRowsWhenPartiallyUpdatingChildrenForAggregateFormula() {
        EntityDefinition invoice = new EntityDefinition(
                "invoice",
                "sales_invoice",
                "Invoice",
                List.of(FieldDefinition.decimal("totalAmount", "Total Amount").column("total_amount").precision(18, 2))
        ).withFormulaRules(new EntityFormulaRuleDefinition("totalAmountCalc",
                "{totalAmount} = SUM({items.lineAmount})"));
        EntityDefinition line = new EntityDefinition(
                "invoice_line",
                "sales_invoice_line",
                "Invoice Line",
                List.of(FieldDefinition.decimal("lineAmount", "Line Amount").column("line_amount").precision(18, 2))
        );
        ModuleDefinition module = ModuleDefinition.builder("sales.invoice", "Invoice")
                .entities(List.of(invoice, line))
                .relations(List.of(EntityRelationDefinition.child("items", "invoice", "invoice_line", "invoiceId")))
                .build();
        DynamicRecord persistedFirst = new DynamicRecord(line).setValue("lineAmount", BigDecimal.TEN);
        persistedFirst.setId("line-1");
        DynamicRecord persistedSecond = new DynamicRecord(line).setValue("lineAmount", BigDecimal.valueOf(20));
        persistedSecond.setId("line-2");
        DynamicRecord changedFirst = new DynamicRecord(line).setValue("lineAmount", BigDecimal.valueOf(15));
        changedFirst.setId("line-1");
        DynamicRecord record = new DynamicRecord(invoice).setValue("totalAmount", BigDecimal.ZERO);
        record.setPartialChildren("items", List.of(changedFirst));

        new DynamicFormulaRuntime("sales.invoice", invoice, module)
                .beforeUpdate(record, null, Map.of("items", List.of(persistedFirst, persistedSecond)));

        assertThat((BigDecimal) record.getValue("totalAmount")).isEqualByComparingTo("35");
        assertThat((BigDecimal) changedFirst.getValue("lineAmount")).isEqualByComparingTo("15");
    }

    @Test
    void shouldCombinePersistedAndSubmittedRelationsWhenUpdatingFormulas() {
        EntityDefinition invoice = new EntityDefinition(
                "invoice",
                "sales_invoice",
                "Invoice",
                List.of(
                        FieldDefinition.decimal("itemTotal", "Item Total").column("item_total").precision(18, 2),
                        FieldDefinition.decimal("attachmentTotal", "Attachment Total").column("attachment_total").precision(18, 2)
                )
        ).withFormulaRules(
                new EntityFormulaRuleDefinition("itemTotalCalc", "{itemTotal} = SUM({items.lineAmount})"),
                new EntityFormulaRuleDefinition("attachmentTotalCalc", "{attachmentTotal} = SUM({attachments.fileSize})")
        );
        EntityDefinition item = new EntityDefinition(
                "invoice_line",
                "sales_invoice_line",
                "Invoice Line",
                List.of(FieldDefinition.decimal("lineAmount", "Line Amount").column("line_amount").precision(18, 2))
        );
        EntityDefinition attachment = new EntityDefinition(
                "invoice_attachment",
                "sales_invoice_attachment",
                "Invoice Attachment",
                List.of(FieldDefinition.decimal("fileSize", "File Size").column("file_size").precision(18, 2))
        );
        ModuleDefinition module = ModuleDefinition.builder("sales.invoice", "Invoice")
                .entities(List.of(invoice, item, attachment))
                .relations(List.of(
                        EntityRelationDefinition.child("items", "invoice", "invoice_line", "invoiceId"),
                        EntityRelationDefinition.child("attachments", "invoice", "invoice_attachment", "invoiceId")
                ))
                .build();
        DynamicRecord record = new DynamicRecord(invoice)
                .setValue("itemTotal", BigDecimal.valueOf(100))
                .setValue("attachmentTotal", BigDecimal.ZERO);
        DynamicRecord persistedItem = new DynamicRecord(item).setValue("lineAmount", BigDecimal.valueOf(100));
        persistedItem.setId("item-1");
        DynamicRecord attachmentRow = new DynamicRecord(attachment).setValue("fileSize", BigDecimal.valueOf(8));
        record.setChildren("attachments", List.of(attachmentRow));

        new DynamicFormulaRuntime("sales.invoice", invoice, module)
                .beforeUpdate(record, null, Map.of("items", List.of(persistedItem)));

        assertThat((BigDecimal) record.getValue("itemTotal")).isEqualByComparingTo("100");
        assertThat((BigDecimal) record.getValue("attachmentTotal")).isEqualByComparingTo("8");
    }

    @Test
    void shouldMergeExistingChildValuesBeforeUpdateFormulaCalculation() {
        EntityDefinition invoice = new EntityDefinition(
                "invoice",
                "sales_invoice",
                "Invoice",
                List.of(FieldDefinition.decimal("totalAmount", "Total Amount").column("total_amount").precision(18, 2))
        ).withFormulaRules(new EntityFormulaRuleDefinition("totalAmountCalc",
                "{totalAmount} = SUM({items.lineAmount} = {items.quantity} * {items.price})"));
        EntityDefinition line = new EntityDefinition(
                "invoice_line",
                "sales_invoice_line",
                "Invoice Line",
                List.of(
                        FieldDefinition.decimal("quantity", "Quantity").precision(18, 2),
                        FieldDefinition.decimal("price", "Price").precision(18, 2),
                        FieldDefinition.decimal("lineAmount", "Line Amount").column("line_amount").precision(18, 2)
                )
        );
        ModuleDefinition module = ModuleDefinition.builder("sales.invoice", "Invoice")
                .entities(List.of(invoice, line))
                .relations(List.of(EntityRelationDefinition.child("items", "invoice", "invoice_line", "invoiceId")))
                .build();
        DynamicRecord existingLine = new DynamicRecord(line)
                .setValue("quantity", BigDecimal.valueOf(2))
                .setValue("price", BigDecimal.valueOf(10));
        existingLine.setId("line-1");
        DynamicRecord payloadLine = new DynamicRecord(line).setValue("quantity", BigDecimal.valueOf(4));
        payloadLine.setId("line-1");
        DynamicRecord record = new DynamicRecord(invoice).setValue("totalAmount", BigDecimal.ZERO);
        record.setChildren("items", List.of(payloadLine));

        new DynamicFormulaRuntime("sales.invoice", invoice, module)
                .beforeUpdate(record, null, Map.of("items", List.of(existingLine)));

        assertThat((BigDecimal) record.getValue("totalAmount")).isEqualByComparingTo("40");
        assertThat((BigDecimal) payloadLine.getValue("lineAmount")).isEqualByComparingTo("40");
    }

    @Test
    void shouldNotRunAfterSelectForInternalReadsOrListQueries() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.row(anyString(), anyMap())).thenReturn(Map.of("total_count", 1));
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(Map.of(
                "id", "contract-1",
                "code", "C-001",
                "amount", BigDecimal.TEN,
                "deleted", Boolean.FALSE,
                "version", 2
        )));
        RecordingLifecycle lifecycle = new RecordingLifecycle();
        DynamicRecordDao dao = new DynamicRecordDao(operations, contractEntity());
        DynamicEntityService entityService = DynamicEntityService.withLifecycle(dao, "sales.contract", lifecycle);

        DynamicRecord partial = new DynamicRecord(contractEntity()).setValue("amount", BigDecimal.ONE);
        partial.setId("contract-1");
        entityService.update(partial);
        entityService.delete("contract-1");
        dao.query(Criteria.of().eq("code", "C-001"), PageRequest.of(1, 10));
        dao.pageQuery(Criteria.of().eq("code", "C-001"), PageRequest.of(1, 10));
        dao.count(Criteria.of().eq("code", "C-001"));

        assertThat(lifecycle.events)
                .containsExactly(
                        "beforeUpdate:3",
                        "beforeDelete:contract-1"
                );
    }

    @Test
    void shouldUpdateByTableColumnsAndIncrementVersion() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(Map.of(
                "id", "contract-1",
                "code", "C-000",
                "amount", BigDecimal.ONE,
                "deleted", Boolean.FALSE,
                "version", 2
        )));
        DynamicRecord record = new DynamicRecord(contractEntity())
                .setValue("code", "C-001")
                .setValue("amount", BigDecimal.TEN);
        record.setId("contract-1");
        record.setTenantId("tenant-a");
        record.setVersion(3);

        entityService(operations).update(record);

        ArgumentCaptor<Map<String, Object>> body = mapCaptor();
        ArgumentCaptor<Map<String, Object>> where = mapCaptor();
        verify(operations).patchUpdateItemWhere(eq(SCHEMA), eq(TABLE), body.capture(), where.capture(), eq("id"));
        assertThat(body.getValue())
                .containsEntry("code", "C-001")
                .containsEntry("amount", BigDecimal.TEN)
                .containsEntry("version", 4);
        assertThat(where.getValue())
                .containsEntry("id", "contract-1")
                .containsEntry("version", 3);
        assertThat(body.getValue())
                .containsKey("updated_at")
                .doesNotContainKeys("tenant_id", "created_at", "created_by");
    }

    @Test
    void shouldResolveCurrentVersionWhenUpdatingPartialRecord() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.row(anyString(), anyMap())).thenReturn(Map.of("total_count", 0));
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(Map.of(
                "id", "contract-1",
                "code", "C-001",
                "amount", BigDecimal.TEN,
                "deleted", Boolean.FALSE,
                "version", 7
        )));
        DynamicRecord record = new DynamicRecord(contractEntity())
                .setValue("amount", BigDecimal.ONE);
        record.setId("contract-1");

        entityService(operations).update(record);

        ArgumentCaptor<Map<String, Object>> body = mapCaptor();
        ArgumentCaptor<Map<String, Object>> where = mapCaptor();
        verify(operations).patchUpdateItemWhere(eq(SCHEMA), eq(TABLE), body.capture(), where.capture(), eq("id"));
        assertThat(body.getValue())
                .containsEntry("amount", BigDecimal.ONE)
                .containsEntry("version", 8)
                .doesNotContainKeys("created_at", "created_by", "code");
        assertThat(where.getValue())
                .containsEntry("id", "contract-1")
                .containsEntry("version", 7);
    }

    @Test
    void shouldRequireTimeZoneCompanionWhenUpdatingZonedTimestamp() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(Map.of(
                "id", "meeting-1",
                "meeting_at", Instant.parse("2026-01-01T01:30:00Z"),
                "meeting_at_timezone", "Asia/Shanghai",
                "deleted", Boolean.FALSE,
                "version", 1
        )));
        EntityDefinition entity = zonedTimestampEntity();
        DynamicEntityService service = new DynamicEntityService(
                new DynamicRecordDao(operations, entity),
                "sales.meeting"
        );
        DynamicRecord record = new DynamicRecord(entity)
                .setValue("meetingAt", Instant.parse("2026-01-02T01:30:00Z"));
        record.setId("meeting-1");
        record.setVersion(1);

        assertThat(record.explicitFieldCodes()).contains("meetingAt");
        assertThatThrownBy(record::validateForUpdate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field companion is missing");
        assertThatThrownBy(() -> service.update(record))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field companion is missing");
        verify(operations, never()).patchUpdateItemWhere(anyString(), anyString(), anyMap(), anyMap(), anyString());

        DynamicRecord clearWithoutCompanion = new DynamicRecord(entity)
                .setValue("meetingAt", null);
        clearWithoutCompanion.setId("meeting-1");
        clearWithoutCompanion.setVersion(1);
        assertThatThrownBy(() -> service.update(clearWithoutCompanion))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("field companion is missing");

        DynamicRecord clearWithCompanion = new DynamicRecord(entity)
                .setValue("meetingAt", null)
                .setValue("meetingAtTimeZone", null);
        clearWithCompanion.setId("meeting-1");
        clearWithCompanion.setVersion(1);
        service.update(clearWithCompanion);

        DynamicRecord valid = new DynamicRecord(entity)
                .setValue("meetingAt", Instant.parse("2026-01-02T01:30:00Z"))
                .setValue("meetingAtTimeZone", "Asia/Shanghai");
        valid.setId("meeting-1");
        valid.setVersion(1);
        service.update(valid);

        ArgumentCaptor<Map<String, Object>> body = mapCaptor();
        verify(operations, times(2))
                .patchUpdateItemWhere(eq(SCHEMA), eq("app_meeting"), body.capture(), anyMap(), eq("id"));
        assertThat(body.getValue())
                .containsEntry("meeting_at", Instant.parse("2026-01-02T01:30:00Z"))
                .containsEntry("meeting_at_timezone", "Asia/Shanghai");
    }

    @Test
    void shouldRejectDynamicUpdateWhenExpectedVersionDoesNotMatch() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.patchUpdateItemWhere(anyString(), anyString(), anyMap(), anyMap(), anyString())).thenReturn(0);
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(Map.of(
                "id", "contract-1",
                "code", "C-001",
                "amount", BigDecimal.TEN,
                "deleted", Boolean.FALSE,
                "version", 2
        )));
        DynamicRecord record = new DynamicRecord(contractEntity())
                .setValue("amount", BigDecimal.ONE);
        record.setId("contract-1");
        record.setVersion(1);

        assertThatThrownBy(() -> entityService(operations).update(record))
                .isInstanceOf(net.ximatai.muyun.spring.ability.OptimisticLockException.class)
                .hasMessageContaining("version conflict");

        ArgumentCaptor<Map<String, Object>> body = mapCaptor();
        ArgumentCaptor<Map<String, Object>> where = mapCaptor();
        verify(operations).patchUpdateItemWhere(eq(SCHEMA), eq(TABLE), body.capture(), where.capture(), eq("id"));
        assertThat(where.getValue()).containsEntry("version", 1);
    }

    @Test
    void shouldRequireExpectedVersionForDynamicVersionUpdate() {
        DynamicRecordDao dao = new DynamicRecordDao(operations(), contractEntity());
        DynamicRecord record = new DynamicRecord(contractEntity()).setValue("amount", BigDecimal.ONE);
        record.setId("contract-1");

        assertThatThrownBy(() -> dao.updateByIdAndVersion(record, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expectedVersion");
        assertThatThrownBy(() -> dao.deleteByIdAndVersion("contract-1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expectedVersion");
    }

    @Test
    void shouldRejectUngovernedDynamicDaoWrites() {
        DynamicRecordDao dao = new DynamicRecordDao(operations(), contractEntity());
        DynamicRecord record = new DynamicRecord(contractEntity()).setValue("amount", BigDecimal.ONE);
        record.setId("contract-1");

        assertThatThrownBy(() -> dao.updateById(record))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("conditional update");
        assertThatThrownBy(() -> dao.updateByIdAndCondition(record, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conditions");
        assertThatThrownBy(() -> dao.upsert(record))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("upsert");
    }

    @Test
    void shouldRejectPartialUpdateWhenOnlyDeletedRecordExists() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenReturn(List.of());
        DynamicRecord record = new DynamicRecord(contractEntity())
                .setValue("amount", BigDecimal.ONE);
        record.setId("contract-1");

        assertThat(entityService(operations).update(record)).isZero();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(operations).query(sql.capture(), anyMap());
        assertThat(sql.getValue())
                .contains("\"id\" =")
                .contains("\"deleted\" =")
                .contains("\"deleted\" IS NULL");
        verify(operations, never()).patchUpdateItemWhere(anyString(), anyString(), anyMap(), anyMap(), anyString());
    }

    @Test
    void shouldSoftDeleteActiveRecordAndIncrementVersion() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(Map.of(
                "id", "contract-1",
                "code", "C-001",
                "amount", BigDecimal.TEN,
                "deleted", Boolean.FALSE,
                "version", 2
        )));

        entityService(operations).delete("contract-1");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(operations).query(sql.capture(), anyMap());
        assertThat(sql.getValue())
                .contains("\"id\" =")
                .doesNotContain("\"deleted\"");

        ArgumentCaptor<Map<String, Object>> body = mapCaptor();
        ArgumentCaptor<Map<String, Object>> where = mapCaptor();
        verify(operations).patchUpdateItemWhere(eq(SCHEMA), eq(TABLE), body.capture(), where.capture(), eq("id"));
        assertThat(body.getValue())
                .containsEntry("deleted", Boolean.TRUE)
                .containsEntry("version", 3);
        assertThat(body.getValue()).containsKey("deleted_at");
        assertThat(body.getValue()).containsKey("deleted_by");
        assertThat(where.getValue()).containsEntry("version", 2);
        assertThat(body.getValue()).doesNotContainKeys("code", "amount", "created_at", "created_by");
    }

    @Test
    void shouldNotExposeDynamicDeleteByIdBypass() {
        DynamicRecordDao dao = dao(operations());

        assertThatThrownBy(() -> dao.deleteById("contract-1"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("DynamicEntityService");
    }

    @Test
    void shouldRejectDynamicEntityServiceWithoutPlatformModuleAlias() {
        assertThatThrownBy(() -> new DynamicEntityService(dao(operations()), "contract"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("platform module alias");
    }

    @Test
    void shouldUseSharedCriteriaCompilerForRawCriteria() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenReturn(List.of());
        DynamicRecordDao dao = dao(operations);

        dao.query(
                Criteria.of().raw(SqlRawCondition.of("\"code\" = :code", Map.of("code", "C-001"))),
                PageRequest.of(1, 10)
        );

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> params = mapCaptor();
        verify(operations).query(sql.capture(), params.capture());
        assertThat(sql.getValue()).contains("\"code\" = :sq");
        assertThat(params.getValue()).containsValue("C-001");
    }

    @Test
    void shouldQueryPageAndCountWithSoftDeleteScopeAndSortMapping() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.row(anyString(), anyMap())).thenReturn(Map.of("total_count", 1));
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(Map.of(
                "id", "contract-1",
                "code", "C-001",
                "deleted", Boolean.FALSE,
                "version", 0
        )));
        DynamicRecordDao dao = dao(operations);
        DynamicEntityService entityService = new DynamicEntityService(dao, "sales.contract");

        assertThat(entityService.pageQuery(Criteria.of().eq("tenantId", "tenant-a").eq("code", "C-001"),
                        PageRequest.of(1, 10),
                        Sort.desc("amount")).getRecords())
                .hasSize(1)
                .first()
                .extracting(record -> record.getValue("code"))
                .isEqualTo("C-001");
        assertThat(entityService.pageQuery(Criteria.of().eq("code", "C-001"), PageRequest.of(1, 10)).getTotal())
                .isEqualTo(1);
        assertThat(entityService.count(Criteria.of().eq("code", "C-001"))).isEqualTo(1);

        ArgumentCaptor<String> querySql = ArgumentCaptor.forClass(String.class);
        verify(operations, org.mockito.Mockito.times(2)).query(querySql.capture(), anyMap());
        assertThat(querySql.getAllValues().getFirst())
                .contains("FROM \"public\".\"app_contract\"")
                .contains("\"tenant_id\" =")
                .contains("\"code\" =")
                .contains("\"deleted\" =")
                .contains("\"deleted\" IS NULL")
                .contains("ORDER BY \"amount\" DESC")
                .contains("LIMIT :limit OFFSET :offset");

        ArgumentCaptor<String> countSql = ArgumentCaptor.forClass(String.class);
        verify(operations, org.mockito.Mockito.times(3)).row(countSql.capture(), anyMap());
        assertThat(countSql.getAllValues().getFirst())
                .startsWith("SELECT COUNT(*) AS total_count FROM \"public\".\"app_contract\"")
                .contains("\"code\" =")
                .contains("\"deleted\" =");
    }

    @Test
    void shouldResolveDeletedAtAsDynamicStandardField() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenReturn(List.of());
        DynamicRecordDao dao = dao(operations);

        dao.query(Criteria.of().eq("deletedAt", Instant.parse("2026-01-02T00:00:00Z")), PageRequest.of(1, 10));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(operations).query(sql.capture(), anyMap());
        assertThat(sql.getValue()).contains("\"deleted_at\" =");
    }

    @Test
    void shouldKeepDaoRawAndServiceScoped() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(Map.of(
                "id", "contract-1",
                "tenant_id", "tenant-a",
                "code", "C-001",
                "amount", BigDecimal.TEN,
                "deleted", Boolean.TRUE,
                "version", 2
        )));
        DynamicRecordDao dao = dao(operations);
        DynamicEntityService entityService = new DynamicEntityService(dao, "sales.contract");

        DynamicRecord raw = dao.findById("contract-1");
        assertThat(raw.getDeleted()).isTrue();
        assertThat(raw.getTenantId()).isEqualTo("tenant-a");
        assertThat(entityService.select("contract-1")).isNull();
        assertThat(entityService.count(Criteria.of().eq("code", "C-001"))).isZero();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(operations, org.mockito.Mockito.times(2)).query(sql.capture(), anyMap());
        assertThat(sql.getAllValues().get(0)).doesNotContain("\"deleted\"");
        assertThat(sql.getAllValues().get(1)).contains("\"deleted\"");
        ArgumentCaptor<String> countSql = ArgumentCaptor.forClass(String.class);
        verify(operations).row(countSql.capture(), anyMap());
        assertThat(countSql.getValue())
                .contains("\"deleted\" =")
                .contains("\"deleted\" IS NULL");
    }

    @Test
    void shouldCreateEntityServiceThroughDynamicRecordRuntime() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.insertItem(eq(SCHEMA), eq(TABLE), anyMap(), eq("id")))
                .thenAnswer(invocation -> invocation.<Map<String, Object>>getArgument(2).get("id"));
        DynamicEntityService entityService = new DynamicRecordRuntime(operations)
                .register(new ModuleDefinition("sales.contract", "Contract", List.of(contractEntity())))
                .entityService("sales.contract", "contract");
        DynamicRecord record = new DynamicRecord(contractEntity())
                .setValue("code", "C-001")
                .setValue("amount", BigDecimal.TEN);

        String id = entityService.insert(record);

        assertThat(id).hasSize(32);
        verify(operations).insertItem(eq(SCHEMA), eq(TABLE), anyMap(), eq("id"));
    }

    @Test
    void shouldReorderAndMoveDynamicRecordsByDeclaredSortField() {
        IDatabaseOperations<Object> operations = operations();
        stubSortableRows(operations);
        DynamicEntityService entityService = new DynamicEntityService(new DynamicRecordDao(operations, sortableEntity()), "sales.contract");

        assertThat(entityService.sortedList(Criteria.of()).stream().map(DynamicRecord::getId))
                .containsExactly("first", "second", "third");
        entityService.reorder(List.of("first", "second", "third"));
        entityService.moveBefore("third", "first");

        ArgumentCaptor<Map<String, Object>> body = mapCaptor();
        verify(operations, org.mockito.Mockito.times(4))
                .patchUpdateItemWhere(eq(SCHEMA), eq(TABLE), body.capture(), anyMap(), eq("id"));
        assertThat(body.getAllValues()).allSatisfy(value -> assertThat(value).containsKey("sort_order"));
        assertThat((Integer) body.getAllValues().get(3).get("sort_order")).isPositive();
    }

    @Test
    void shouldApplyTenantScopeWhenReorderingDynamicRecords() {
        IDatabaseOperations<Object> operations = operations();
        stubSortableRows(operations);
        DynamicEntityService entityService = new DynamicEntityService(new DynamicRecordDao(operations, sortableEntity()), "sales.contract");

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            entityService.reorder(List.of("first", "second", "third"));
        }

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(operations, org.mockito.Mockito.atLeastOnce()).query(sql.capture(), anyMap());
        assertThat(sql.getAllValues()).allSatisfy(statement -> assertThat(statement).contains("\"tenant_id\" ="));
    }

    @Test
    void shouldRejectDuplicateDynamicReorderIdsAndMissingSortField() {
        assertThatThrownBy(() -> new DynamicEntityService(new DynamicRecordDao(operations(), sortableEntity()), "sales.contract")
                .reorder(List.of("same", "same")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("duplicate");

        assertThatThrownBy(() -> new DynamicEntityService(new DynamicRecordDao(operations(), contractEntity()), "sales.contract")
                .sortedList(Criteria.of()))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("does not support capability: SORT");
    }

    @Test
    void shouldRejectDynamicSortMoveAcrossDeclaredPartition() {
        IDatabaseOperations<Object> operations = operations();
        stubPartitionRows(operations);
        DynamicEntityService entityService = new DynamicEntityService(
                new DynamicRecordDao(operations, partitionSortableEntity()), "sales.contract");

        assertThatThrownBy(() -> entityService.moveBefore("left", "right"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("same partition: groupId");

        entityService.reorder(List.of("left"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(operations, org.mockito.Mockito.atLeastOnce()).query(sql.capture(), anyMap());
        assertThat(sql.getAllValues()).anySatisfy(statement -> assertThat(statement).contains("\"group_id\" ="));
    }

    @Test
    void shouldRejectDynamicTreeSortMoveAcrossDeclaredBusinessPartition() {
        IDatabaseOperations<Object> operations = operations();
        stubPartitionRows(operations);
        DynamicEntityService entityService = new DynamicEntityService(
                new DynamicRecordDao(operations, partitionTreeEntity()), "sales.contract");

        assertThatThrownBy(() -> entityService.moveBefore("left", "right"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("same partition: groupId");

        entityService.reorder(List.of("left"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(operations, org.mockito.Mockito.atLeastOnce()).query(sql.capture(), anyMap());
        assertThat(sql.getAllValues()).anySatisfy(statement -> assertThat(statement)
                .contains("\"parent_id\" =")
                .contains("\"group_id\" ="));
    }

    @Test
    void shouldResolveDynamicReferenceTitlesAndOptions() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.row(anyString(), anyMap())).thenReturn(Map.of("total_count", 1));
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(
                referenceRow("contract-1", "Contract One"),
                referenceRow("contract-2", "Contract Two")
        ));
        DynamicEntityService entityService = new DynamicEntityService(new DynamicRecordDao(operations, referenceEntity()), "sales.contract");
        DynamicRecord record = new DynamicRecord(referenceEntity()).setValue("title", "Contract");

        assertThat(record).isInstanceOf(TitledCapable.class);
        assertThat(record.getTitle()).isEqualTo("Contract");
        assertThat(entityService).isNotInstanceOf(ReferenceAbility.class);
        assertThat(entityService.title("contract-1")).isEqualTo("Contract One");
        assertThat(entityService.titles(List.of("contract-1", "contract-2")))
                .containsEntry("contract-1", "Contract One")
                .containsEntry("contract-2", "Contract Two");
        assertThat(entityService.referenceOptions(Criteria.of(), PageRequest.of(1, 10)).getRecords())
                .containsExactly(
                        new ReferenceOption("contract-1", "Contract One"),
                        new ReferenceOption("contract-2", "Contract Two")
                );

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(operations, org.mockito.Mockito.atLeastOnce()).query(sql.capture(), anyMap());
        assertThat(sql.getAllValues()).anySatisfy(value -> assertThat(value)
                .contains("\"deleted\" =")
                .contains("\"deleted\" IS NULL"));
    }

    @Test
    void shouldApplyTenantScopeWhenResolvingDynamicReferences() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.row(anyString(), anyMap())).thenReturn(Map.of("total_count", 1));
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(
                referenceRow("contract-1", "Contract One"),
                referenceRow("contract-2", "Contract Two")
        ));
        DynamicEntityService entityService = new DynamicEntityService(new DynamicRecordDao(operations, referenceEntity()), "sales.contract");

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            entityService.title("contract-1");
            entityService.titles(List.of("contract-1", "contract-2"));
            entityService.referenceOptions(Criteria.of(), PageRequest.of(1, 10));
        }

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(operations, org.mockito.Mockito.atLeastOnce()).query(sql.capture(), anyMap());
        assertThat(sql.getAllValues()).allSatisfy(statement -> assertThat(statement).contains("\"tenant_id\" ="));
    }

    @Test
    void shouldReuseTreeAbilityForDynamicRecordsWhenMetadataEnablesTree() {
        IDatabaseOperations<Object> operations = operations();
        stubTreeRows(operations);
        DynamicEntityService entityService = new DynamicEntityService(new DynamicRecordDao(operations, treeEntity()), "sales.contract");
        DynamicRecord record = new DynamicRecord(treeEntity()).setValue("code", "C");

        assertThat(record).isInstanceOf(TreeCapable.class);
        assertThat(entityService).isNotInstanceOf(TreeAbility.class);
        assertThat(entityService.children("root").stream().map(DynamicRecord::getId))
                .containsExactly("A", "B");
        assertThat(entityService.ancestorIds("A1")).containsExactly("A");
        assertThat(entityService.descendantIds("A")).containsExactly("A1");
        entityService.moveAfter("A", "B");

        assertThatThrownBy(() -> entityService.moveBefore("A1", "B"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("same parent");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(operations, org.mockito.Mockito.atLeastOnce()).query(sql.capture(), anyMap());
        assertThat(sql.getAllValues()).anySatisfy(value -> assertThat(value)
                .contains("\"parent_id\" =")
                .contains("ORDER BY \"sort_order\" ASC"));

        ArgumentCaptor<Map<String, Object>> body = mapCaptor();
        verify(operations, org.mockito.Mockito.times(1))
                .patchUpdateItemWhere(eq(SCHEMA), eq(TABLE), body.capture(), anyMap(), eq("id"));
        assertThat(body.getAllValues().getFirst()).containsKey("sort_order");
        assertThat((Integer) body.getAllValues().getFirst().get("sort_order")).isPositive();
    }

    @Test
    void shouldApplyTenantScopeWhenResolvingDynamicTree() {
        IDatabaseOperations<Object> operations = operations();
        stubTreeRows(operations);
        DynamicEntityService entityService = new DynamicEntityService(new DynamicRecordDao(operations, treeEntity()), "sales.contract");

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            entityService.children("root");
            entityService.ancestorIds("A1");
            entityService.descendantIds("A");
        }

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(operations, org.mockito.Mockito.atLeastOnce()).query(sql.capture(), anyMap());
        assertThat(sql.getAllValues()).allSatisfy(statement -> assertThat(statement).contains("\"tenant_id\" ="));
    }

    @Test
    void shouldPrepareDynamicTreeDefaultsThroughCrudAbility() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.insertItem(eq(SCHEMA), eq(TABLE), anyMap(), eq("id")))
                .thenAnswer(invocation -> invocation.<Map<String, Object>>getArgument(2).get("id"));
        DynamicEntityService entityService = new DynamicEntityService(new DynamicRecordDao(operations, treeEntity()), "sales.contract");
        DynamicRecord record = new DynamicRecord(treeEntity()).setValue("code", "C");
        record.setId("C");

        entityService.insert(record);

        ArgumentCaptor<Map<String, Object>> body = mapCaptor();
        verify(operations).insertItem(eq(SCHEMA), eq(TABLE), body.capture(), eq("id"));
        assertThat(body.getValue()).containsEntry("parent_id", "root");
    }

    @Test
    void shouldRejectInvalidDynamicTreePlacementThroughAdapter() {
        IDatabaseOperations<Object> operations = operations();
        stubTreeRows(operations);
        DynamicEntityService entityService = new DynamicEntityService(new DynamicRecordDao(operations, treeEntity()), "sales.contract");
        DynamicRecord missingParent = new DynamicRecord(treeEntity())
                .setValue("code", "C")
                .setValue("parentId", "missing");
        missingParent.setId("C");

        assertThatThrownBy(() -> entityService.insert(missingParent))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("missing parent");

        DynamicRecord selfParent = new DynamicRecord(treeEntity())
                .setValue("code", "A")
                .setValue("parentId", "A");
        selfParent.setId("A");

        assertThatThrownBy(() -> entityService.insert(selfParent))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("itself as parent");

        DynamicRecord moveUnderDescendant = new DynamicRecord(treeEntity())
                .setValue("code", "A")
                .setValue("parentId", "A1");
        moveUnderDescendant.setId("A");

        assertThatThrownBy(() -> entityService.update(moveUnderDescendant))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("under its descendant");
    }

    @Test
    void shouldNotPrepareAbilityDefaultsForPlainDynamicCrudFields() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.insertItem(eq(SCHEMA), eq(TABLE), anyMap(), eq("id")))
                .thenAnswer(invocation -> invocation.<Map<String, Object>>getArgument(2).get("id"));
        EntityDefinition entity = new EntityDefinition(
                "contract",
                TABLE,
                "Contract",
                List.of(
                        FieldDefinition.string("code", "Code").length(64).required(),
                        FieldDefinition.parentId(),
                        FieldDefinition.bool("active", "Active")
                )
        );
        DynamicEntityService entityService = new DynamicEntityService(new DynamicRecordDao(operations, entity), "sales.contract");
        DynamicRecord record = new DynamicRecord(entity).setValue("code", "C");
        record.setId("C");

        entityService.insert(record);

        ArgumentCaptor<Map<String, Object>> body = mapCaptor();
        verify(operations).insertItem(eq(SCHEMA), eq(TABLE), body.capture(), eq("id"));
        assertThat(body.getValue()).doesNotContainKeys("parent_id", "active");
    }

    @Test
    void shouldPrepareDynamicEnableDefaultsOnlyWhenCapabilityDeclared() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.insertItem(eq(SCHEMA), eq(TABLE), anyMap(), eq("id")))
                .thenAnswer(invocation -> invocation.<Map<String, Object>>getArgument(2).get("id"));
        EntityDefinition entity = enabledEntity();
        DynamicEntityService entityService = new DynamicEntityService(new DynamicRecordDao(operations, entity), "sales.contract");
        DynamicRecord record = new DynamicRecord(entity).setValue("code", "C");
        record.setId("C");

        assertThat(record).isInstanceOf(EnabledCapable.class);
        assertThat(entityService).isNotInstanceOf(EnableAbility.class);
        entityService.insert(record);

        ArgumentCaptor<Map<String, Object>> body = mapCaptor();
        verify(operations).insertItem(eq(SCHEMA), eq(TABLE), body.capture(), eq("id"));
        assertThat(body.getValue()).containsEntry("enabled", Boolean.TRUE);
    }

    @Test
    void shouldPreserveExplicitDynamicEnabledValueOnInsert() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.insertItem(eq(SCHEMA), eq(TABLE), anyMap(), eq("id")))
                .thenAnswer(invocation -> invocation.<Map<String, Object>>getArgument(2).get("id"));
        EntityDefinition entity = enabledEntity();
        DynamicEntityService entityService = new DynamicEntityService(new DynamicRecordDao(operations, entity), "sales.contract");
        DynamicRecord record = new DynamicRecord(entity)
                .setValue("code", "C")
                .setValue("enabled", Boolean.FALSE);
        record.setId("C");

        entityService.insert(record);

        ArgumentCaptor<Map<String, Object>> body = mapCaptor();
        verify(operations).insertItem(eq(SCHEMA), eq(TABLE), body.capture(), eq("id"));
        assertThat(body.getValue()).containsEntry("enabled", Boolean.FALSE);
    }

    @Test
    void shouldToggleDynamicEnabledWithoutMarkerInterface() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(Map.of(
                "id", "contract-1",
                "code", "C-001",
                "enabled", Boolean.FALSE,
                "deleted", Boolean.FALSE,
                "version", 2
        )));
        DynamicEntityService entityService = new DynamicEntityService(new DynamicRecordDao(operations, enabledEntity()), "sales.contract");

        assertThat(entityService.enable("contract-1")).isEqualTo(1);

        ArgumentCaptor<Map<String, Object>> body = mapCaptor();
        ArgumentCaptor<Map<String, Object>> where = mapCaptor();
        verify(operations).patchUpdateItemWhere(eq(SCHEMA), eq(TABLE), body.capture(), where.capture(), eq("id"));
        assertThat(body.getValue())
                .containsEntry("enabled", Boolean.TRUE)
                .containsEntry("version", 3);
        assertThat(where.getValue()).containsEntry("version", 2);
    }

    @Test
    void shouldRejectDynamicEnableMethodsWithoutCapability() {
        DynamicEntityService entityService = new DynamicEntityService(new DynamicRecordDao(operations(), contractEntity()), "sales.contract");

        assertThatThrownBy(() -> entityService.enable("contract-1"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("does not support capability: ENABLE");
        assertThatThrownBy(() -> entityService.disable("contract-1"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("does not support capability: ENABLE");
        assertThatThrownBy(() -> entityService.isEnabled("contract-1"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("does not support capability: ENABLE");
        assertThatThrownBy(() -> entityService.enabledCriteria(Criteria.of()))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("does not support capability: ENABLE");
    }

    @Test
    void shouldRejectReferenceMethodsWithoutTitleField() {
        assertThatThrownBy(() -> new DynamicEntityService(new DynamicRecordDao(operations(), contractEntity()), "sales.contract")
                .title("contract-1"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("does not support capability: REFERENCE");
    }

    @Test
    void shouldRejectSortMethodsWithoutSortCapability() {
        assertThatThrownBy(() -> new DynamicEntityService(new DynamicRecordDao(operations(), contractEntity()), "sales.contract")
                .sortedList(Criteria.of()))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("does not support capability: SORT");
    }

    @Test
    void shouldNotMutateCallerCriteria() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.row(anyString(), anyMap())).thenReturn(Map.of("total_count", 1));
        when(operations.query(anyString(), anyMap())).thenReturn(List.of());
        DynamicRecordDao dao = dao(operations);
        Criteria criteria = Criteria.of().eq("code", "C-001");

        dao.query(criteria, PageRequest.of(1, 10));
        dao.pageQuery(criteria, PageRequest.of(1, 10));
        dao.count(criteria);

        assertThat(criteria.getClauses())
                .hasSize(1)
                .first()
                .extracting(clause -> clause.getField())
                .isEqualTo("code");
    }

    private DynamicRecordDao dao(IDatabaseOperations<Object> operations) {
        return new DynamicRecordDao(operations, contractEntity());
    }

    private DynamicEntityService entityService(IDatabaseOperations<Object> operations) {
        return new DynamicEntityService(dao(operations), "sales.contract");
    }

    @SuppressWarnings("unchecked")
    private IDatabaseOperations<Object> operations() {
        IDatabaseOperations<Object> operations = mock(IDatabaseOperations.class);
        when(operations.getDBInfo()).thenReturn(new DBInfo("POSTGRESQL").setName("muyun_test"));
        when(operations.getDefaultSchemaName()).thenReturn(SCHEMA);
        when(operations.patchUpdateItemWhere(anyString(), anyString(), anyMap(), anyMap(), anyString())).thenReturn(1);
        return operations;
    }

    private EntityDefinition contractEntity() {
        return new EntityDefinition(
                "contract",
                TABLE,
                "Contract",
                List.of(
                        FieldDefinition.string("code", "Code").length(64).required(),
                        FieldDefinition.decimal("amount", "Amount").precision(18, 2)
                )
        );
    }

    private EntityDefinition zonedTimestampEntity() {
        return new EntityDefinition(
                "meeting",
                "app_meeting",
                "Meeting",
                List.of(
                        FieldDefinition.zonedTimestamp("meetingAt", "Meeting At").column("meeting_at"),
                        FieldDefinition.zonedTimestampTimeZone("meetingAt", "meeting_at")
                )
        );
    }

    private EntityDefinition formulaEntity() {
        return new EntityDefinition(
                "contract",
                TABLE,
                "Contract",
                List.of(
                        FieldDefinition.decimal("quantity", "Quantity").precision(18, 2),
                        FieldDefinition.decimal("price", "Price").precision(18, 2),
                        FieldDefinition.decimal("amount", "Amount").precision(18, 2)
                )
        ).withFormulaRules(EntityFormulaRuleDefinition
                .calculation("amountCalc", "amount", "{quantity} * {price}"));
    }

    private EntityDefinition enabledEntity() {
        return new EntityDefinition(
                "contract",
                TABLE,
                "Contract",
                List.of(
                        FieldDefinition.string("code", "Code").length(64).required(),
                        FieldDefinition.enabled()
                )
        ).withCapabilities(EntityCapability.ENABLE);
    }

    private EntityDefinition sortableEntity() {
        return new EntityDefinition(
                "contract",
                TABLE,
                "Contract",
                List.of(
                        FieldDefinition.string("code", "Code").length(64).required(),
                        FieldDefinition.decimal("amount", "Amount").precision(18, 2),
                        FieldDefinition.sortOrder()
                )
        ).withCapabilities(EntityCapability.CRUD, EntityCapability.SORT);
    }

    private EntityDefinition partitionSortableEntity() {
        return new EntityDefinition(
                "contract",
                TABLE,
                "Contract",
                List.of(
                        FieldDefinition.string("groupId", "Group").column("group_id").length(64).required(),
                        FieldDefinition.sortOrder()
                )
        ).withCapabilities(EntityCapability.CRUD, EntityCapability.SORT)
                .withSortPartitionFields("groupId");
    }

    private EntityDefinition referenceEntity() {
        return new EntityDefinition(
                "contract",
                TABLE,
                "Contract",
                List.of(
                        FieldDefinition.string("code", "Code").length(64).required(),
                        FieldDefinition.titleField().required(),
                        FieldDefinition.decimal("amount", "Amount").precision(18, 2)
                )
        ).withCapabilities(EntityCapability.CRUD, EntityCapability.REFERENCE);
    }

    private EntityDefinition treeEntity() {
        return new EntityDefinition(
                "contract",
                TABLE,
                "Contract",
                List.of(
                        FieldDefinition.string("code", "Code").length(64).required(),
                        FieldDefinition.parentId(),
                        FieldDefinition.sortOrder()
                )
        ).withCapabilities(EntityCapability.TREE);
    }

    private EntityDefinition partitionTreeEntity() {
        return new EntityDefinition(
                "contract",
                TABLE,
                "Contract",
                List.of(
                        FieldDefinition.string("groupId", "Group").column("group_id").length(64).required(),
                        FieldDefinition.parentId(),
                        FieldDefinition.sortOrder()
                )
        ).withCapabilities(EntityCapability.TREE)
                .withSortPartitionFields("groupId");
    }

    private Map<String, Object> row(String id, int sortOrder) {
        return Map.of(
                "id", id,
                "tenant_id", "tenant-a",
                "code", id.toUpperCase(),
                "amount", BigDecimal.TEN,
                "sort_order", sortOrder,
                "deleted", Boolean.FALSE,
                "version", 0
        );
    }

    private Map<String, Object> treeRow(String id, String parentId, int sortOrder) {
        return Map.of(
                "id", id,
                "tenant_id", "tenant-a",
                "code", id,
                "parent_id", parentId,
                "sort_order", sortOrder,
                "deleted", Boolean.FALSE,
                "version", 0
        );
    }

    private Map<String, Object> partitionRow(String id, String groupId, String parentId, int sortOrder) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("id", id);
        row.put("tenant_id", "tenant-a");
        row.put("group_id", groupId);
        row.put("sort_order", sortOrder);
        row.put("deleted", Boolean.FALSE);
        row.put("version", 0);
        if (parentId != null) {
            row.put("parent_id", parentId);
        }
        return row;
    }

    private Map<String, Object> referenceRow(String id, String name) {
        return Map.of(
                "id", id,
                "tenant_id", "tenant-a",
                "code", id.toUpperCase(),
                "title", name,
                "amount", BigDecimal.TEN,
                "deleted", Boolean.FALSE,
                "version", 0
        );
    }

    private void stubSortableRows(IDatabaseOperations<Object> operations) {
        when(operations.query(anyString(), anyMap())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = invocation.getArgument(1);
            String paramText = params.toString();
            if (paramText.contains("first")) {
                return List.of(row("first", 100));
            }
            if (paramText.contains("second")) {
                return List.of(row("second", 200));
            }
            if (paramText.contains("third")) {
                return List.of(row("third", 300));
            }
            return List.of(row("first", 100), row("second", 200), row("third", 300));
        });
    }

    private void stubPartitionRows(IDatabaseOperations<Object> operations) {
        when(operations.query(anyString(), anyMap())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = invocation.getArgument(1);
            if (params.containsValue("left")) {
                return List.of(partitionRow("left", "group-a", "root", 100));
            }
            if (params.containsValue("right")) {
                return List.of(partitionRow("right", "group-b", "root", 200));
            }
            return List.of(partitionRow("left", "group-a", "root", 100));
        });
    }

    private void stubTreeRows(IDatabaseOperations<Object> operations) {
        when(operations.query(anyString(), anyMap())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> params = invocation.getArgument(1);
            if (sql.contains("\"id\" =")) {
                if (params.containsValue("A")) {
                    return List.of(treeRow("A", "root", 1));
                }
                if (params.containsValue("B")) {
                    return List.of(treeRow("B", "root", 2));
                }
                if (params.containsValue("A1")) {
                    return List.of(treeRow("A1", "A", 1));
                }
                return List.of();
            }
            if (sql.contains("\"parent_id\" =")) {
                if (params.containsValue("root")) {
                    return List.of(treeRow("A", "root", 1), treeRow("B", "root", 2));
                }
                if (params.containsValue("A")) {
                    return List.of(treeRow("A1", "A", 1));
                }
                return List.of();
            }
            return List.of(treeRow("A", "root", 1), treeRow("B", "root", 2), treeRow("A1", "A", 1));
        });
    }

    private static class RecordingLifecycle implements DynamicRecordLifecycle {
        private final List<String> events = new ArrayList<>();

        @Override
        public void beforeInsert(DynamicRecord record) {
            record.setValue("code", "HOOK-CODE");
            events.add("beforeInsert:" + record.getValue("code"));
        }

        @Override
        public void beforeUpdate(DynamicRecord record) {
            events.add("beforeUpdate:" + record.getVersion());
        }

        @Override
        public void beforeDelete(String id) {
            events.add("beforeDelete:" + id);
        }

        @Override
        public void afterSelect(DynamicRecord record) {
            events.add("afterSelect:" + record.getId());
        }
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Map<String, Object>> mapCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }
}
