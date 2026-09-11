package net.ximatai.muyun.spring.platform.metadata;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import java.util.Map;
import net.ximatai.muyun.spring.common.formula.FormulaRuleKind;
import net.ximatai.muyun.spring.common.formula.FormulaRulePhase;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionValidator;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.module.PlatformModule;
import net.ximatai.muyun.spring.platform.module.PlatformModuleActionService;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.runtime.PlatformDynamicRuntimeRefreshCoordinator;
import net.ximatai.muyun.spring.platform.runtime.PlatformModuleDefinitionCompiler;
import net.ximatai.muyun.spring.platform.runtime.PlatformDynamicRuntimeRefresher;
import net.ximatai.muyun.spring.platform.runtime.PlatformDynamicRuntimeRefreshService;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordRuntime;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.dynamic.schema.DynamicSchemaService;
import net.ximatai.muyun.spring.dynamic.refresh.DynamicModuleRuntimeRefresher;
import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.platform.reference.PlatformReferenceTargetResolver;
import net.ximatai.muyun.spring.platform.support.PlatformPostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** Exercises the proxied governance apply path against repository version checks and transactions. */
@SpringBootTest(classes = {MetadataRelationChangeSetApplyIT.TestApplication.class, BusinessRuleGovernanceRepositoryIT.Config.class})
class BusinessRuleGovernanceRepositoryIT extends PlatformPostgresIntegrationTest {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) { registry.add("muyun.database.repository-schema-mode", () -> "ENSURE"); }

    @Autowired private BusinessRuleGovernanceService governance;
    @Autowired private PlatformModuleService modules;
    @Autowired private MetadataService metadata;
    @Autowired private MetadataFieldService fields;
    @Autowired private ModuleMetadataRelationService relations;
    @Autowired private ModuleMetadataFormulaRuleService formulas;
    @Autowired private FieldSpecService specs;
    @Autowired private MetadataFieldReferenceConfigService referenceConfigs;
    @Autowired private PlatformDynamicRuntimeRefreshCoordinator refresh;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private PlatformDynamicRuntimeRefresher dynamicRefresher;
    @Autowired private DynamicRecordRuntime dynamicRuntime;
    private DynamicRecordService recordService;
    private String moduleAlias;

    @BeforeEach
    void setUp() {
        reset(modules, refresh);
        recordService = new DynamicRecordService(dynamicRuntime);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        moduleAlias = "crm.rule_" + suffix;
        PlatformModule module = new PlatformModule();
        module.setAlias(moduleAlias); module.setApplicationAlias("crm"); module.setModuleKind(ModuleKind.DYNAMIC); module.setTitle("rules");
        when(modules.select(moduleAlias)).thenReturn(module);
        ensureSpec("integer", FieldType.INTEGER);
        ensureSpec("decimal", FieldType.DECIMAL);
        ensureSpec("string", FieldType.STRING);
        Metadata item = new Metadata();
        item.setApplicationAlias("crm"); item.setAlias("rule_" + suffix); item.setTitle("rules");
        item.setSchemaName("public"); item.setTableName("rule_" + suffix);
        metadata.insert(item);
        fields.insert(field(item.getId(), "quantity", "quantity", "integer"));
        fields.insert(field(item.getId(), "total", "total", "decimal"));
        ModuleMetadataRelation relation = new ModuleMetadataRelation();
        relation.setModuleAlias(moduleAlias); relation.setMetadataId(item.getId()); relation.setRelationRole(RelationRole.MAIN); relation.setTitle("main");
        relations.insert(relation);
    }

    @AfterEach
    void resetAbilityRuntime() {
        PlatformAbilityRuntime.resetReferenceTargetResolver();
    }

    @Test
    void shouldApplyReferenceRuleAndPersistComputedValue() {
        Metadata item = metadata.select(mainMetadataId());
        MetadataField title = field(item.getId(), "title", "title", "string");
        title.setTitleField(true);
        fields.insert(title);
        MetadataField supplierId = field(item.getId(), "supplierId", "supplier_id", "string");
        fields.insert(supplierId);
        MetadataFieldReferenceConfig reference = new MetadataFieldReferenceConfig();
        reference.setMetadataFieldId(supplierId.getId());
        reference.setRelationId(mainRelationId());
        reference.setTargetMetadataId(item.getId());
        reference.setTargetUnavailablePolicy(net.ximatai.muyun.spring.ability.reference.ReferenceTargetUnavailablePolicy.RESTRICT);
        referenceConfigs.insert(reference);

        // Register the declared dynamic reference before governance validates the proposed formula.
        dynamicRefresher.refresh(moduleAlias);
        PlatformAbilityRuntime.configureReferenceTargetResolver(
                new PlatformReferenceTargetResolver(null, dynamicRuntime, recordService));

        BusinessRuleProposal rule = new BusinessRuleProposal("deriveTotal", FormulaRuleKind.CALCULATION,
                "total", "{supplierId.quantity}", true, null);
        BusinessRuleGovernanceSnapshot baseline = governance.snapshot(moduleAlias);
        BusinessRulePreview preview = governance.preview(moduleAlias, new BusinessRulePreviewCommand(List.of(rule)));
        assertThat(preview.valid()).isTrue();
        governance.apply(moduleAlias, new BusinessRuleApplyCommand(List.of(rule),
                baseline.baselineFingerprint(), preview.proposalFingerprint()));

        // Governance's coordinator is deliberately mocked in this repository fixture; install the accepted definition.
        dynamicRefresher.refresh(moduleAlias);
        String entityAlias = item.getAlias();
        try (TenantContext.Scope ignored = TenantContext.use("formula-tenant-a")) {
        DynamicRecord supplier = recordService.newRecord(moduleAlias, entityAlias)
                .setValue("title", "supplier")
                .setValue("quantity", 7);
        String supplierRecordId = recordService.create(moduleAlias, entityAlias, supplier);
        DynamicRecord root = recordService.newRecord(moduleAlias, entityAlias)
                .setValue("title", "root")
                .setValue("quantity", 1)
                .setValue("supplierId", supplierRecordId)
                .setValue("total", new java.math.BigDecimal("999"));
        String rootRecordId = recordService.create(moduleAlias, entityAlias, root);

        Object calculated = recordService.select(moduleAlias, entityAlias, rootRecordId).getValue("total");
        assertThat(calculated).isInstanceOf(java.math.BigDecimal.class);
        assertThat((java.math.BigDecimal) calculated).isEqualByComparingTo("7");
        try (TenantContext.Scope system = TenantContext.system("business-rule-governance-test")) {
        BusinessRuleTrialCommand sample = new BusinessRuleTrialCommand(List.of(rule), Map.of("supplierId", supplierRecordId));
        BusinessRuleTrialResult ownTenant = governance.trial(moduleAlias, sample, "formula-tenant-a");
        assertThat(ownTenant.errors()).isEmpty();
        assertThat(ownTenant.values().get("supplierId.quantity")).isEqualTo(7);
        BusinessRuleTrialResult otherTenant = governance.trial(moduleAlias, sample, "formula-tenant-b");
        assertThat(otherTenant.errors()).isEmpty();
        assertThat(otherTenant.values()).containsEntry("supplierId.quantity", null);
        assertThat(otherTenant.values().get("total")).isNull();
        assertThat(governance.trial(moduleAlias, sample, null).errors())
                .extracting(BusinessRuleIssue::code).contains("FORMULA_REFERENCE_TENANT_REQUIRED");
        assertThat(governance.trial(moduleAlias, new BusinessRuleTrialCommand(List.of(rule),
                Map.of("supplierId", supplierRecordId, "supplierId.quantity", 999)), "formula-tenant-a").errors())
                .extracting(BusinessRuleIssue::code).contains("FORMULA_REFERENCE_INPUT_FORBIDDEN");
        assertThat(governance.snapshot(moduleAlias).rules()).allMatch(BusinessRuleSnapshotRule::editable);
        }
        }
    }

    @Test
    void shouldKeepDisabledReferenceRuleEditableAndAllowReenable() {
        configureSelfReference();
        ModuleMetadataFormulaRule stored = formulaRule("deriveTotal", FormulaRuleKind.CALCULATION,
                FormulaRulePhase.BEFORE_SAVE, "total", "{total} = ({supplierId.quantity})", false);
        formulas.insert(stored);

        BusinessRuleGovernanceSnapshot baseline = governance.snapshot(moduleAlias);
        assertThat(baseline.referenceFields()).extracting(BusinessRuleReferenceField::path)
                .containsExactly("supplierId.quantity");
        assertThat(baseline.rules()).singleElement().satisfies(rule -> {
            assertThat(rule.enabled()).isFalse();
            assertThat(rule.editable()).isTrue();
        });

        BusinessRuleProposal reenabled = new BusinessRuleProposal("deriveTotal", FormulaRuleKind.CALCULATION,
                "total", "{supplierId.quantity}", true, null);
        BusinessRulePreview preview = governance.preview(moduleAlias, new BusinessRulePreviewCommand(List.of(reenabled)));
        assertThat(preview.valid()).isTrue();
        governance.apply(moduleAlias, new BusinessRuleApplyCommand(List.of(reenabled), baseline.baselineFingerprint(),
                preview.proposalFingerprint()));

        assertThat(formulas.listByRelationIds(List.of(mainRelationId()))).singleElement().satisfies(rule ->
                assertThat(rule.getEnabled()).isTrue());
        assertThat(governance.snapshot(moduleAlias).rules()).singleElement().satisfies(rule -> {
            assertThat(rule.enabled()).isTrue();
            assertThat(rule.editable()).isTrue();
        });
    }

    @Test
    void shouldKeepValidReferenceRuleEditableWhenLegacyRuleHasInvalidReference() {
        configureSelfReference();
        formulas.insert(formulaRule("deriveTotal", FormulaRuleKind.CALCULATION, FormulaRulePhase.BEFORE_SAVE,
                "total", "{total} = ({supplierId.quantity})", true));
        // This legacy action rule lies outside the main-record execution plan, but its outdated
        // reference must not erase the editable rule's independent directory entry.
        formulas.insert(formulaRule("legacyReference", FormulaRuleKind.VALIDATION,
                FormulaRulePhase.ACTION_BEFORE_EXECUTE, null, "{supplierId.missingField} > 0", true));

        BusinessRuleGovernanceSnapshot snapshot = governance.snapshot(moduleAlias);

        assertThat(snapshot.referenceFields()).extracting(BusinessRuleReferenceField::path)
                .containsExactly("supplierId.quantity");
        assertThat(snapshot.rules()).filteredOn(BusinessRuleSnapshotRule::code, "deriveTotal")
                .allMatch(BusinessRuleSnapshotRule::editable);
        assertThat(snapshot.rules()).filteredOn(BusinessRuleSnapshotRule::code, "legacyReference")
                .allMatch(rule -> !rule.editable());
    }

    @Test
    void shouldRejectDisabledRuleReadingStandardFieldOutsideGovernanceInputs() {
        BusinessRuleProposal disabled = new BusinessRuleProposal("deriveFromId", FormulaRuleKind.CALCULATION,
                "total", "{id}", false, null);
        BusinessRuleGovernanceSnapshot baseline = governance.snapshot(moduleAlias);

        BusinessRulePreview preview = governance.preview(moduleAlias, new BusinessRulePreviewCommand(List.of(disabled)));

        assertThat(preview.valid()).isFalse();
        assertThat(preview.errors()).extracting(BusinessRuleIssue::code).contains("INVALID_RULE_INPUT");
        assertThat(preview.errors()).extracting(BusinessRuleIssue::field).contains("id");
        assertThatThrownBy(() -> governance.apply(moduleAlias, new BusinessRuleApplyCommand(List.of(disabled),
                baseline.baselineFingerprint(), preview.proposalFingerprint())))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("business-rule proposal is invalid");
        assertThat(formulas.listByRelationIds(List.of(mainRelationId()))).isEmpty();
    }

    @Test
    void shouldRejectDisabledRuleWithInvalidReferencePath() {
        configureSelfReference();
        BusinessRuleProposal disabled = new BusinessRuleProposal("deriveMissingReference", FormulaRuleKind.CALCULATION,
                "total", "{supplierId.missingField}", false, null);
        BusinessRuleGovernanceSnapshot baseline = governance.snapshot(moduleAlias);

        BusinessRulePreview preview = governance.preview(moduleAlias, new BusinessRulePreviewCommand(List.of(disabled)));

        assertThat(preview.valid()).isFalse();
        assertThat(preview.errors()).isNotEmpty();
        assertThatThrownBy(() -> governance.apply(moduleAlias, new BusinessRuleApplyCommand(List.of(disabled),
                baseline.baselineFingerprint(), preview.proposalFingerprint())))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("business-rule proposal is invalid");
        assertThat(formulas.listByRelationIds(List.of(mainRelationId()))).isEmpty();
    }

    @Test
    void shouldMakeGovernanceBaselineStaleAfterDirectFormulaRuleUpdate() {
        formulas.insert(formulaRule("deriveTotal", FormulaRuleKind.CALCULATION, FormulaRulePhase.BEFORE_SAVE,
                "total", "{total} = ({quantity} * 2)", true));
        BusinessRuleGovernanceSnapshot baseline = governance.snapshot(moduleAlias);
        ModuleMetadataFormulaRule direct = formulas.listByRelationIds(List.of(mainRelationId())).getFirst();
        direct.setExpression("{total} = ({quantity} * 4)");
        formulas.update(direct);

        BusinessRuleProposal governanceRule = new BusinessRuleProposal("deriveTotal", FormulaRuleKind.CALCULATION,
                "total", "{quantity} * 3", true, null);
        BusinessRulePreview preview = governance.preview(moduleAlias,
                new BusinessRulePreviewCommand(List.of(governanceRule)));

        assertThat(preview.valid()).isTrue();
        assertThatThrownBy(() -> governance.apply(moduleAlias, new BusinessRuleApplyCommand(List.of(governanceRule),
                baseline.baselineFingerprint(), preview.proposalFingerprint())))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("baseline is stale");
        assertThat(formulas.listByRelationIds(List.of(mainRelationId()))).singleElement()
                .extracting(ModuleMetadataFormulaRule::getExpression)
                .isEqualTo("{total} = ({quantity} * 4)");
    }

    @Test
    void shouldAdvanceOwningRelationVersionForDirectFormulaRuleLifecycleMutations() {
        int version = mainRelationVersion();
        ModuleMetadataFormulaRule first = formulaRule("firstRule", FormulaRuleKind.CALCULATION,
                FormulaRulePhase.BEFORE_SAVE, "total", "{total} = ({quantity} * 2)", true);
        formulas.insert(first);
        assertThat(mainRelationVersion()).isGreaterThan(version);

        version = mainRelationVersion();
        formulas.disable(first.getId());
        assertThat(mainRelationVersion()).isGreaterThan(version);

        version = mainRelationVersion();
        formulas.enable(first.getId());
        assertThat(mainRelationVersion()).isGreaterThan(version);

        ModuleMetadataFormulaRule second = formulaRule("secondRule", FormulaRuleKind.VALIDATION,
                FormulaRulePhase.BEFORE_SAVE, null, "{quantity} > 0", true);
        formulas.insert(second);
        version = mainRelationVersion();
        formulas.reorder(List.of(second.getId(), first.getId()));
        assertThat(mainRelationVersion()).isGreaterThan(version);

        version = mainRelationVersion();
        formulas.delete(first.getId());
        assertThat(mainRelationVersion()).isGreaterThan(version);

        version = mainRelationVersion();
        formulas.restore(first.getId());
        assertThat(mainRelationVersion()).isGreaterThan(version);
    }

    @Test
    void shouldRollbackOwningRelationVersionWhenDirectFormulaRuleUpdateIsRejected() {
        ModuleMetadataFormulaRule stored = formulaRule("deriveTotal", FormulaRuleKind.CALCULATION,
                FormulaRulePhase.BEFORE_SAVE, "total", "{total} = ({quantity} * 2)", true);
        formulas.insert(stored);
        int relationVersion = mainRelationVersion();
        ModuleMetadataFormulaRule invalid = formulas.select(stored.getId());
        invalid.setExpression("{total} = ({quantity} +)");

        assertThatThrownBy(() -> formulas.update(invalid))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("formula expression is invalid");

        assertThat(mainRelationVersion()).isEqualTo(relationVersion);
        assertThat(formulas.select(stored.getId()).getExpression()).isEqualTo("{total} = ({quantity} * 2)");
    }

    @Test
    void shouldRestrictChildAggregationsToFieldTypeAndHideRelationKey() {
        configureLineItems();
        BusinessRuleGovernanceSnapshot baseline = governance.snapshot(moduleAlias);

        assertThat(baseline.aggregateFields()).extracting(BusinessRuleField::fieldName)
                .containsExactlyInAnyOrder("lines.productName", "lines.lineAmount")
                .doesNotContain("lines.contractId");
        assertThat(baseline.aggregateFields()).filteredOn(field -> field.fieldName().equals("lines.productName"))
                .extracting(BusinessRuleField::aggregateFunctions).containsExactly(List.of("COUNT"));
        assertThat(baseline.aggregateFields()).filteredOn(field -> field.fieldName().equals("lines.lineAmount"))
                .extracting(BusinessRuleField::aggregateFunctions)
                .containsExactly(List.of("COUNT", "SUM", "AVG", "MAX", "MIN"));

        BusinessRuleProposal countNames = new BusinessRuleProposal("countNames", FormulaRuleKind.CALCULATION,
                "total", "COUNT({lines.productName})", true, null);
        BusinessRuleProposal sumAmount = new BusinessRuleProposal("sumAmount", FormulaRuleKind.CALCULATION,
                "total", "SUM({lines.lineAmount})", true, null);
        assertThat(governance.preview(moduleAlias, new BusinessRulePreviewCommand(List.of(countNames))).valid()).isTrue();
        assertThat(governance.preview(moduleAlias, new BusinessRulePreviewCommand(List.of(sumAmount))).valid()).isTrue();

        List<BusinessRuleProposal> invalid = List.of("SUM", "AVG", "MAX", "MIN").stream()
                .map(function -> new BusinessRuleProposal("invalid" + function, FormulaRuleKind.CALCULATION,
                        "total", function + "({lines.productName})", true, null))
                .toList();
        BusinessRulePreview preview = governance.preview(moduleAlias, new BusinessRulePreviewCommand(invalid));
        assertThat(preview.valid()).isFalse();
        assertThat(preview.errors()).extracting(BusinessRuleIssue::code)
                .containsOnly("AGGREGATE_FIELD_TYPE_UNSUPPORTED");
        assertThat(preview.errors()).extracting(BusinessRuleIssue::field).containsOnly("lines.productName");
        assertThatThrownBy(() -> governance.apply(moduleAlias, new BusinessRuleApplyCommand(invalid,
                baseline.baselineFingerprint(), preview.proposalFingerprint())))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("business-rule proposal is invalid");
        assertThat(formulas.listByRelationIds(List.of(mainRelationId()))).isEmpty();
    }

    @Test
    void shouldRollbackFailedApplyAndAcceptOnlyOneSameBaseline() throws Exception {
        BusinessRuleProposal valid = new BusinessRuleProposal("deriveTotal", FormulaRuleKind.CALCULATION,
                "total", "{quantity} * 3", true, null);
        BusinessRuleGovernanceSnapshot baseline = governance.snapshot(moduleAlias);
        BusinessRulePreview preview = governance.preview(moduleAlias, new BusinessRulePreviewCommand(List.of(valid)));
        assertThat(preview.valid()).isTrue();

        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            governance.apply(moduleAlias, new BusinessRuleApplyCommand(List.of(valid), baseline.baselineFingerprint(), preview.proposalFingerprint()));
            throw new IllegalStateException("force rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(formulas.listByRelationIds(List.of(mainRelationId()))).isEmpty();
        verifyNoInteractions(refresh);

        AtomicInteger accepted = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> applySameBaseline(valid, baseline, preview, ready, start, accepted));
            var second = executor.submit(() -> applySameBaseline(valid, baseline, preview, ready, start, accepted));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        assertThat(accepted.get()).isEqualTo(1);
        assertThat(formulas.listByRelationIds(List.of(mainRelationId()))).extracting(ModuleMetadataFormulaRule::getAlias)
                .containsExactly("deriveTotal");
        verify(refresh, times(1)).activateModulesNow(List.of(moduleAlias));
        assertThatThrownBy(() -> governance.apply(moduleAlias, new BusinessRuleApplyCommand(List.of(valid),
                baseline.baselineFingerprint(), preview.proposalFingerprint()))).isInstanceOf(PlatformException.class)
                .hasMessageContaining("stale");
    }

    private void applySameBaseline(BusinessRuleProposal valid, BusinessRuleGovernanceSnapshot baseline,
                                   BusinessRulePreview preview, CountDownLatch ready, CountDownLatch start,
                                   AtomicInteger accepted) {
        ready.countDown();
        try {
            if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("concurrent start timed out");
            governance.apply(moduleAlias, new BusinessRuleApplyCommand(List.of(valid), baseline.baselineFingerprint(),
                    preview.proposalFingerprint()));
            accepted.incrementAndGet();
        } catch (PlatformException ignored) {
            // A stale baseline or optimistic relation CAS is the expected loser result.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private String mainMetadataId() { return relations.select(mainRelationId()).getMetadataId(); }
    private String mainRelationId() { return relations.list(Criteria.of().eq("moduleAlias", moduleAlias), new PageRequest(0, 1)).getFirst().getId(); }
    private int mainRelationVersion() { return relations.select(mainRelationId()).getVersion(); }
    private void ensureSpec(String alias, FieldType type) {
        if (!specs.list(Criteria.of().eq("alias", alias)).isEmpty()) return;
        FieldSpec spec = new FieldSpec(); spec.setAlias(alias); spec.setTitle(alias); spec.setFieldType(type); specs.insert(spec);
    }
    private MetadataField field(String metadataId, String name, String column, String spec) {
        MetadataField field = new MetadataField(); field.setMetadataId(metadataId); field.setFieldName(name); field.setColumnName(column);
        field.setFieldSpecAlias(spec); field.setTitle(name); return field;
    }
    private void configureLineItems() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        Metadata lines = new Metadata();
        lines.setApplicationAlias("crm"); lines.setAlias("rule_line_" + suffix); lines.setTitle("lines");
        lines.setSchemaName("public"); lines.setTableName("rule_line_" + suffix);
        metadata.insert(lines);
        fields.insert(field(lines.getId(), "contractId", "contract_id", "string"));
        fields.insert(field(lines.getId(), "productName", "product_name", "string"));
        fields.insert(field(lines.getId(), "lineAmount", "line_amount", "decimal"));
        ModuleMetadataRelation child = new ModuleMetadataRelation();
        child.setModuleAlias(moduleAlias); child.setMetadataId(lines.getId()); child.setParentMetadataId(mainMetadataId());
        child.setRelationRole(RelationRole.CHILD); child.setForeignKey("contractId"); child.setRelationAlias("lines");
        child.setTitle("lines");
        relations.insert(child);
    }

    private void configureSelfReference() {
        Metadata item = metadata.select(mainMetadataId());
        MetadataField title = field(item.getId(), "title", "title", "string");
        title.setTitleField(true);
        fields.insert(title);
        MetadataField supplierId = field(item.getId(), "supplierId", "supplier_id", "string");
        fields.insert(supplierId);
        MetadataFieldReferenceConfig reference = new MetadataFieldReferenceConfig();
        reference.setMetadataFieldId(supplierId.getId());
        reference.setRelationId(mainRelationId());
        reference.setTargetMetadataId(item.getId());
        reference.setTargetUnavailablePolicy(net.ximatai.muyun.spring.ability.reference.ReferenceTargetUnavailablePolicy.RESTRICT);
        referenceConfigs.insert(reference);
        dynamicRefresher.refresh(moduleAlias);
        PlatformAbilityRuntime.configureReferenceTargetResolver(
                new PlatformReferenceTargetResolver(null, dynamicRuntime, recordService));
    }
    private ModuleMetadataFormulaRule formulaRule(String alias, FormulaRuleKind kind, FormulaRulePhase phase,
                                                  String targetField, String expression, boolean enabled) {
        ModuleMetadataFormulaRule rule = new ModuleMetadataFormulaRule();
        rule.setRelationId(mainRelationId());
        rule.setAlias(alias);
        rule.setTitle(alias);
        rule.setRuleKind(kind);
        rule.setRulePhase(phase);
        rule.setTargetField(targetField);
        rule.setExpression(expression);
        rule.setEnabled(enabled);
        return rule;
    }

    @TestConfiguration
    @EnableMuYunRepositories(basePackageClasses = ModuleMetadataFormulaRuleDao.class)
    static class Config {
        @Bean ModuleDefinitionValidator moduleDefinitionValidator() { return new ModuleDefinitionValidator(); }
        @Bean ModuleMetadataFormulaRuleService formulaService(ModuleMetadataFormulaRuleDao dao, ModuleMetadataRelationService relations,
                                                              MetadataFieldService fields,
                                                              MetadataFieldReferenceConfigService references) {
            return new ModuleMetadataFormulaRuleService(dao, relations, fields, java.util.Optional.empty(),
                    java.util.Optional.of(references));
        }
        @Bean MetadataFieldReferenceConfigService referenceConfigService(MetadataFieldReferenceConfigDao dao,
                                                                          MetadataFieldService fields,
                                                                          MetadataService metadata,
                                                                          FieldSpecService specs,
                                                                          PlatformModuleService modules,
                                                                          ModuleMetadataRelationService relations) {
            return new MetadataFieldReferenceConfigService(dao, fields, metadata, specs, modules, relations);
        }
        @Bean MetadataViewService metadataViewService() { MetadataViewService value = mock(MetadataViewService.class); when(value.list(any(), any(), any())).thenReturn(List.of()); return value; }
        @Bean MetadataViewFieldService metadataViewFieldService() { MetadataViewFieldService value = mock(MetadataViewFieldService.class); when(value.list(any(), any(), any())).thenReturn(List.of()); return value; }
        @Bean PlatformModuleActionService moduleActionService() { PlatformModuleActionService value = mock(PlatformModuleActionService.class); when(value.list(any(), any(), any())).thenReturn(List.of()); return value; }
        @Bean PlatformModuleDefinitionCompiler moduleCompiler(PlatformModuleService modules, MetadataService metadata, MetadataFieldService fields,
                MetadataFieldDefinitionCompiler compiler, MetadataFieldReferenceConfigService references, ModuleMetadataRelationService relations,
                MetadataViewService views, MetadataViewFieldService viewFields, PlatformModuleActionService actions,
                ModuleMetadataFormulaRuleService formulas, ModuleDefinitionValidator validator) {
            return new PlatformModuleDefinitionCompiler(modules, metadata, fields, compiler, references, relations, views, viewFields, actions, formulas, validator);
        }
        @Bean BusinessRuleGovernanceService governance(PlatformModuleService modules, ModuleMetadataRelationService relations,
                MetadataFieldService fields, MetadataFieldConfigService configs, ModuleMetadataFormulaRuleService formulas,
                MetadataFieldDefinitionCompiler compiler, PlatformModuleDefinitionCompiler modulesCompiler,
                ModuleDefinitionValidator validator, PlatformDynamicRuntimeRefreshCoordinator refresh) {
            return new BusinessRuleGovernanceService(modules, relations, fields, configs, formulas, compiler, modulesCompiler, validator, refresh);
        }
        @Bean DynamicRecordRuntime dynamicRuntime(IDatabaseOperations<?> operations) { return DynamicRecordRuntime.builder(operations).build(); }
        @Bean PlatformDynamicRuntimeRefresher dynamicRuntimeRefresher(PlatformModuleDefinitionCompiler compiler,
                                                                        DynamicSchemaService schema,
                                                                        DynamicRecordRuntime runtime) {
            return new PlatformDynamicRuntimeRefresher(compiler, new DynamicModuleRuntimeRefresher(schema, runtime));
        }
        @Bean PlatformDynamicRuntimeRefreshService dynamicRuntimeRefreshService(PlatformDynamicRuntimeRefresher refresher) {
            return new PlatformDynamicRuntimeRefreshService(refresher);
        }
    }
}
