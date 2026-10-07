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
import net.ximatai.muyun.spring.platform.module.PlatformModuleDao;
import net.ximatai.muyun.spring.platform.runtime.PlatformDynamicRuntimeRefreshCoordinator;
import net.ximatai.muyun.spring.platform.runtime.PlatformModuleDefinitionCompiler;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordRuntime;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.dynamic.schema.DynamicSchemaService;
import net.ximatai.muyun.spring.dynamic.refresh.DynamicModuleRuntimeRefresher;
import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.MutationTransactionOperator;
import net.ximatai.muyun.spring.platform.reference.PlatformReferenceTargetResolver;
import net.ximatai.muyun.spring.platform.reference.StaticAbilityCatalog;
import net.ximatai.muyun.spring.platform.support.PlatformPostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
import java.util.Optional;

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
    @Autowired private PlatformModuleDefinitionCompiler definitionCompiler;
    @Autowired private DynamicSchemaService schemaService;
    @Autowired private DynamicRecordRuntime dynamicRuntime;
    private DynamicRecordService recordService;
    private String moduleAlias;

    @BeforeEach
    void setUp() {
        // This isolated host does not import Starter's mutation runtime configuration.
        TransactionTemplate mutations = new TransactionTemplate(transactionManager);
        TransactionTemplate statements = new TransactionTemplate(transactionManager);
        statements.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED);
        PlatformAbilityRuntime.configureMutationTransactionOperator(new MutationTransactionOperator() {
            @Override public <T> T execute(java.util.function.Supplier<T> work) {
                return mutations.execute(status -> work.get());
            }
            @Override public <T> T executeStatement(java.util.function.Supplier<T> work) {
                return statements.execute(status -> work.get());
            }
        });
        reset(refresh);
        recordService = new DynamicRecordService(dynamicRuntime);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        moduleAlias = "crm.rule_" + suffix;
        PlatformModule module = new PlatformModule();
        module.setAlias(moduleAlias); module.setApplicationAlias("crm"); module.setModuleKind(ModuleKind.DYNAMIC); module.setTitle("rules");
        modules.insert(module);
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
        PlatformAbilityRuntime.resetMutationTransactionOperator();
        PlatformAbilityRuntime.resetReferenceTargetResolver();
    }

    @Test
    void shouldPersistReferenceEnabledRequirement() {
        MetadataField title = field(mainMetadataId(), "title", "title", "string");
        title.setTitleField(true);
        fields.insert(title);
        MetadataField targetId = field(mainMetadataId(), "targetId", "target_id", "string");
        fields.insert(targetId);
        MetadataFieldReferenceConfig reference = new MetadataFieldReferenceConfig();
        reference.setMetadataFieldId(targetId.getId());
        reference.setRelationId(mainRelationId());
        reference.setTargetMetadataId(mainMetadataId());
        reference.setRequireEnabled(true);
        assertThatThrownBy(() -> referenceConfigs.insert(reference))
                .isInstanceOf(PlatformException.class).hasMessageContaining("requires target ENABLE capability");
        assertThat(referenceConfigs.findForRelation(targetId.getId(), mainRelationId())).isNull();
        ensureSpec("boolean", FieldType.BOOLEAN);
        fields.insert(field(mainMetadataId(), "enabled", "enabled", "boolean"));
        referenceConfigs.insert(reference);

        assertThat(referenceConfigs.select(reference.getId()).getRequireEnabled()).isTrue();
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
        installDeclaredModel();
        PlatformAbilityRuntime.configureReferenceTargetResolver(
                new PlatformReferenceTargetResolver(new StaticAbilityCatalog(List.of(modules)), dynamicRuntime, recordService));

        BusinessRuleProposal rule = new BusinessRuleProposal("deriveTotal", FormulaRuleKind.CALCULATION,
                "total", "{supplierId.quantity}", true, null);
        BusinessRuleGovernanceSnapshot baseline = governance.snapshot(moduleAlias);
        BusinessRulePreview preview = governance.preview(moduleAlias, new BusinessRulePreviewCommand(List.of(rule)));
        assertThat(preview.valid()).isTrue();
        governance.apply(moduleAlias, new BusinessRuleApplyCommand(List.of(rule),
                baseline.baselineFingerprint(), preview.proposalFingerprint()));

        // Governance's coordinator is deliberately mocked in this repository fixture; install the accepted definition.
        installDeclaredModel();
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
    void shouldRejectUnquotedFieldInValidationBeforeTrialOrApplication() {
        BusinessRuleProposal rule = new BusinessRuleProposal("nonNegative", FormulaRuleKind.VALIDATION,
                null, "quantity >= 0", true, "数量不能为负数");
        BusinessRuleGovernanceSnapshot baseline = governance.snapshot(moduleAlias);
        BusinessRulePreview preview = governance.preview(moduleAlias, new BusinessRulePreviewCommand(List.of(rule)));
        assertThat(preview.valid()).isFalse();
        assertThat(preview.errors()).extracting(BusinessRuleIssue::code).contains("FORMULA_PARSE_ERROR");
        BusinessRuleTrialResult result = governance.trial(moduleAlias,
                new BusinessRuleTrialCommand(List.of(rule), Map.of("quantity", -1)));
        assertThat(result.errors()).extracting(BusinessRuleIssue::code).contains("FORMULA_PARSE_ERROR");
        assertThatThrownBy(() -> governance.apply(moduleAlias, new BusinessRuleApplyCommand(List.of(rule),
                baseline.baselineFingerprint(), preview.proposalFingerprint())))
                .isInstanceOf(PlatformException.class);
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
    void shouldTrialExplicitChildSamplesWithoutPersistingOrTreatingMissingRowsAsEmpty() {
        configureLineItems();
        BusinessRuleProposal sum = new BusinessRuleProposal("sumAmount", FormulaRuleKind.CALCULATION,
                "total", "SUM({lines.lineAmount})", true, null);
        for (int amount : List.of(8, 16)) {
            BusinessRuleTrialResult result = governance.trial(moduleAlias, new BusinessRuleTrialCommand(List.of(sum),
                    Map.of(), Map.of("lines", List.of(Map.of("lineAmount", 10), Map.of("lineAmount", amount)))));
            assertThat(result.errors()).isEmpty();
            assertThat(new java.math.BigDecimal(result.values().get("total").toString()))
                    .isEqualByComparingTo(java.math.BigDecimal.valueOf(10 + amount));
        }
        assertThat(governance.trial(moduleAlias, new BusinessRuleTrialCommand(List.of(sum), Map.of())).errors())
                .extracting(BusinessRuleIssue::code).contains("TRIAL_CHILD_REQUIRED");
        assertThat(governance.trial(moduleAlias, new BusinessRuleTrialCommand(List.of(sum), Map.of(),
                Map.of("lines", List.of()))).errors()).isEmpty();
        assertThat(governance.trial(moduleAlias, new BusinessRuleTrialCommand(List.of(sum), Map.of(),
                Map.of("lines", List.of(Map.of("contractId", "injected"))))).errors())
                .extracting(BusinessRuleIssue::code).contains("INVALID_TRIAL_CHILD_FIELD");
        assertThat(governance.trial(moduleAlias, new BusinessRuleTrialCommand(List.of(sum), Map.of(),
                Map.of("other", List.of()))).errors())
                .extracting(BusinessRuleIssue::code).contains("INVALID_TRIAL_CHILD");
        assertThat(formulas.listByRelationIds(List.of(mainRelationId()))).isEmpty();
    }

    @Test
    void shouldComputeChildRowsBeforeTotalsInTrialAndRealAggregateSave() {
        configureLineItems();
        var child = relations.list(Criteria.of().eq("moduleAlias", moduleAlias).eq("relationRole", RelationRole.CHILD)).getFirst();
        fields.insert(field(child.getMetadataId(), "quantity", "quantity", "integer"));
        fields.insert(field(child.getMetadataId(), "price", "price", "decimal"));
        var rules = List.of(new BusinessRuleProposal("sumAmount", FormulaRuleKind.CALCULATION,
                "total", "SUM({lines.lineAmount})", true, null),
                new BusinessRuleProposal("lineAmount", FormulaRuleKind.CALCULATION,
                        "lines.lineAmount", "{lines.quantity} * {lines.price}", true, null));
        var baseline = governance.snapshot(moduleAlias);
        assertThat(baseline.childFields()).extracting(BusinessRuleField::fieldName)
                .contains("lines.lineAmount").doesNotContain("lines.contractId");
        var checked = governance.preview(moduleAlias, new BusinessRulePreviewCommand(rules));
        assertThat(checked.errors()).isEmpty();
        assertThat(checked.executionOrder()).containsExactly("lineAmount", "sumAmount");
        var sample = Map.<String, List<Map<String, Object>>>of("lines", List.of(
                Map.of("quantity", 2, "price", 12, "lineAmount", 999),
                Map.of("quantity", 3, "price", 9, "lineAmount", 999)));
        var trial = governance.trial(moduleAlias, new BusinessRuleTrialCommand(rules, Map.of(), sample));
        assertThat(trial.errors()).isEmpty();
        assertThat(new java.math.BigDecimal(trial.values().get("total").toString())).isEqualByComparingTo("51");
        assertThat(new java.math.BigDecimal(trial.children().get("lines").getFirst().get("lineAmount").toString()))
                .isEqualByComparingTo("24");
        assertThat(sample.get("lines").getFirst()).containsEntry("lineAmount", 999);
        governance.apply(moduleAlias, new BusinessRuleApplyCommand(rules, baseline.baselineFingerprint(), checked.proposalFingerprint()));
        assertThat(governance.snapshot(moduleAlias).rules()).allMatch(BusinessRuleSnapshotRule::editable);
        var definition = definitionCompiler.compile(moduleAlias);
        new DynamicModuleRuntimeRefresher(schemaService, dynamicRuntime).refresh(definition);
        String main = definition.mainEntityAlias();
        String line = definition.relations().getFirst().childEntityAlias();
        try (var tenant = TenantContext.use("aggregate-rule-test")) {
            var record = recordService.newRecord(moduleAlias, main).setValue("total", new java.math.BigDecimal("999"));
            record.setChildren("lines", List.of(
                    recordService.newRecord(moduleAlias, line).setValue("quantity", 2).setValue("price", new java.math.BigDecimal("12")).setValue("lineAmount", new java.math.BigDecimal("999")),
                    recordService.newRecord(moduleAlias, line).setValue("quantity", 3).setValue("price", new java.math.BigDecimal("9")).setValue("lineAmount", new java.math.BigDecimal("999"))));
            String id = recordService.create(moduleAlias, main, record);
            var saved = recordService.select(moduleAlias, main, id);
            saved.setChildren("lines", recordService.aggregateChildrenForView(moduleAlias, id, "lines"));
            assertThat((java.math.BigDecimal) saved.getValue("total")).isEqualByComparingTo("51");
            assertThat((java.math.BigDecimal) saved.getChildren("lines").getFirst().getValue("lineAmount")).isEqualByComparingTo("24");
            saved.getChildren("lines").getFirst().setValue("quantity", 4);
            recordService.update(moduleAlias, main, saved);
            saved = recordService.select(moduleAlias, main, id);
            saved.setChildren("lines", recordService.aggregateChildrenForView(moduleAlias, id, "lines"));
            assertThat((java.math.BigDecimal) saved.getValue("total")).isEqualByComparingTo("75");
            saved.setChildren("lines", List.of(saved.getChildren("lines").getFirst()));
            recordService.update(moduleAlias, main, saved);
            saved = recordService.select(moduleAlias, main, id);
            assertThat((java.math.BigDecimal) saved.getValue("total")).isEqualByComparingTo("48");
            saved.setChildren("lines", List.of());
            recordService.update(moduleAlias, main, saved);
            assertThat((java.math.BigDecimal) recordService.select(moduleAlias, main, id).getValue("total")).isEqualByComparingTo("0");
        }
    }

    @Test
    void shouldValidateProvidedTrialInputTypesBeforeMainAndChildCalculations() {
        configureLineItems();
        var child = relations.list(Criteria.of().eq("moduleAlias", moduleAlias)
                .eq("relationRole", RelationRole.CHILD)).getFirst();
        fields.insert(field(child.getMetadataId(), "quantity", "quantity", "integer"));
        fields.insert(field(child.getMetadataId(), "price", "price", "decimal"));
        var rules = List.of(new BusinessRuleProposal("sumAmount", FormulaRuleKind.CALCULATION,
                "total", "SUM({lines.lineAmount})", true, null),
                new BusinessRuleProposal("lineAmount", FormulaRuleKind.CALCULATION,
                        "lines.lineAmount", "{lines.quantity} * {lines.price}", true, null));
        var sample = Map.<String, List<Map<String, Object>>>of("lines", List.of(
                Map.of("quantity", "2", "price", "3.5"), Map.of("quantity", 1, "price", 20)));
        var valid = governance.trial(moduleAlias, new BusinessRuleTrialCommand(rules, Map.of(), sample));
        assertThat(valid.errors()).isEmpty();
        assertThat(new java.math.BigDecimal(valid.values().get("total").toString())).isEqualByComparingTo("27");
        assertThat(sample.get("lines").getFirst()).containsEntry("quantity", "2");
        for (Object invalid : List.of(1.5, "1.5", 2147483648L)) {
            var badChild = governance.trial(moduleAlias, new BusinessRuleTrialCommand(rules, Map.of(),
                    Map.of("lines", List.of(Map.of("quantity", invalid, "price", 3.5)))));
            assertThat(badChild.errors()).extracting(BusinessRuleIssue::code).contains("FORMULA_TYPE_MISMATCH");
            assertThat(badChild.errors()).extracting(BusinessRuleIssue::field).contains("lines.quantity");
            assertThat(badChild.changedFields()).isEmpty();
            var badMain = governance.trial(moduleAlias, new BusinessRuleTrialCommand(rules,
                    Map.of("quantity", invalid), sample));
            assertThat(badMain.errors()).extracting(BusinessRuleIssue::field).contains("quantity");
        }
        assertThat(formulas.listByRelationIds(List.of(mainRelationId()))).isEmpty();
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
        verify(refresh).scheduleModules(List.of(moduleAlias));
        org.mockito.Mockito.clearInvocations(refresh);

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
        verify(refresh, times(1)).scheduleModules(List.of(moduleAlias));
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
        installDeclaredModel();
        PlatformAbilityRuntime.configureReferenceTargetResolver(
                new PlatformReferenceTargetResolver(new StaticAbilityCatalog(List.of(modules)), dynamicRuntime, recordService));
    }
    private void installDeclaredModel() {
        // This isolated governance fixture mocks the platform activation coordinator.
        new DynamicModuleRuntimeRefresher(schemaService, dynamicRuntime).refresh(definitionCompiler.compile(moduleAlias));
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
    @EnableMuYunRepositories(basePackageClasses = {ModuleMetadataFormulaRuleDao.class, PlatformModuleDao.class})
    static class Config {
        @Bean @Primary PlatformModuleService persistedModules(PlatformModuleDao dao) {
            return new PlatformModuleService(dao, event -> {});
        }
        @Bean ModuleDefinitionValidator moduleDefinitionValidator() { return new ModuleDefinitionValidator(); }
        @Bean ModuleMetadataFormulaRuleService formulaService(ModuleMetadataFormulaRuleDao dao, ModuleMetadataRelationService relations,
                                                              MetadataFieldService fields,
                                                              MetadataFieldReferenceConfigService references) {
            return new ModuleMetadataFormulaRuleService(dao, relations, fields, Optional.empty(),
                    Optional.of(references));
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
    }
}
