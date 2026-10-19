package net.ximatai.muyun.spring.platform.code;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.metadata.DBInfo;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicModuleRegistry;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordRuntime;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DynamicCodeCoordinatorTest {
    private static final String MODULE = "crm.order";
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-08T00:00:00Z"), ZoneOffset.UTC);

    @Test
    void shouldGenerateCodeForDynamicMainRecordCreateAndPreviewDoesNotConsumeSequence() {
        Services services = services();
        CodeRule rule = rule("orderNo", CodeMode.AUTO);
        services.ruleService.saveRuleTree(rule);
        assertThat(services.previewService.previewDraft(new PreviewCodeRuleCommand(
                services.ruleService.viewRuleTree(rule.getId()), Map.of(), null, null, null)).value())
                .isEqualTo("SO-0001");
        DynamicRecordService recordService = dynamicService(services);

        DynamicRecord record = recordService.newRecord(MODULE, "main")
                .setValue("title", "first");
        recordService.create(MODULE, "main", record);

        assertThat(record.getValue("orderNo")).isEqualTo("SO-0001");
        assertThat(services.previewService.previewDraft(new PreviewCodeRuleCommand(
                services.ruleService.viewRuleTree(rule.getId()), Map.of(), null, null, null)).value())
                .isEqualTo("SO-0001");

        DynamicRecord next = recordService.newRecord(MODULE, "main")
                .setValue("title", "second");
        recordService.create(MODULE, "main", next);
        assertThat(next.getValue("orderNo")).isEqualTo("SO-0002");
    }

    @Test
    void shouldRejectManualValueForAutoRuleButKeepManualValueForEditableAutoRule() {
        Services services = services();
        services.ruleService.saveRuleTree(rule("orderNo", CodeMode.AUTO));
        DynamicRecordService recordService = dynamicService(services);

        DynamicRecord invalid = recordService.newRecord(MODULE, "main")
                .setValue("title", "invalid")
                .setValue("orderNo", "MANUAL-1");
        assertThatThrownBy(() -> recordService.create(MODULE, "main", invalid))
                .hasMessageContaining("AUTO code field does not accept manual value");

        Services editableServices = services();
        editableServices.ruleService.saveRuleTree(rule("orderNo", CodeMode.AUTO_WITH_MANUAL_EDIT));
        DynamicRecordService editableRecordService = dynamicService(editableServices);
        DynamicRecord manual = editableRecordService.newRecord(MODULE, "main")
                .setValue("title", "manual")
                .setValue("orderNo", "KEEP-1");
        editableRecordService.create(MODULE, "main", manual);

        assertThat(manual.getValue("orderNo")).isEqualTo("KEEP-1");
    }

    @Test
    void shouldSyncActiveLedgerAfterDynamicCreate() {
        Services services = services();
        services.ruleService.saveRuleTree(rule("orderNo", CodeMode.AUTO));
        DynamicRecordService recordService = dynamicService(services, statefulOperations());

        DynamicRecord record = recordService.newRecord(MODULE, "main")
                .setValue("title", "first");
        recordService.create(MODULE, "main", record);

        CodeLedgerEntry ledger = services.ledgerService.findByRuleAndValue(
                services.ruleService.resolveRule(new ResolveCodeRuleCommand(MODULE, "main", null, "orderNo", null, null))
                        .rule().getId(),
                "SO-0001"
        );
        assertThat(ledger.getStatus()).isEqualTo(CodeLedgerStatus.ACTIVE);
        assertThat(ledger.getSourceRecordId()).isEqualTo(record.getId());
    }

    @Test
    void shouldRejectManualCodeUpdateForAutoRule() {
        Services services = services();
        services.ruleService.saveRuleTree(rule("orderNo", CodeMode.AUTO));
        DynamicRecordService recordService = dynamicService(services, statefulOperations());
        DynamicRecord record = recordService.newRecord(MODULE, "main").setValue("title", "first");
        recordService.create(MODULE, "main", record);

        DynamicRecord update = recordService.newRecord(MODULE, "main")
                .setValue("orderNo", "MANUAL-1");
        update.setId(record.getId());
        update.setVersion(1);

        assertThatThrownBy(() -> recordService.update(MODULE, "main", update))
                .hasMessageContaining("AUTO code field does not accept manual value");
    }

    @Test
    void shouldReleaseOldCodeAndBindManualValueForEditableAutoUpdate() {
        Services services = services();
        CodeRule rule = rule("orderNo", CodeMode.AUTO_WITH_MANUAL_EDIT);
        rule.setAllowRecycle(Boolean.TRUE);
        services.ruleService.saveRuleTree(rule);
        DynamicRecordService recordService = dynamicService(services, statefulOperations());
        DynamicRecord record = recordService.newRecord(MODULE, "main").setValue("title", "first");
        recordService.create(MODULE, "main", record);

        DynamicRecord update = recordService.newRecord(MODULE, "main")
                .setValue("orderNo", "MANUAL-9");
        update.setId(record.getId());
        update.setVersion(1);
        recordService.update(MODULE, "main", update);

        CodeLedgerEntry oldLedger = services.ledgerService.findByRuleAndValue(rule.getId(), "SO-0001");
        assertThat(oldLedger.getStatus()).isEqualTo(CodeLedgerStatus.AVAILABLE);
        assertThat(oldLedger.getSourceRecordId()).isNull();
        assertThat(services.recycleService.list(null, net.ximatai.muyun.database.core.orm.PageRequest.of(1, 10)).getFirst()
                .getStatus()).isEqualTo(CodeRecycleStatus.AVAILABLE);
        CodeLedgerEntry newLedger = services.ledgerService.findByRuleAndValue(rule.getId(), "MANUAL-9");
        assertThat(newLedger.getStatus()).isEqualTo(CodeLedgerStatus.ACTIVE);
        assertThat(newLedger.getSourceRecordId()).isEqualTo(record.getId());
    }

    @Test
    void shouldRecalculateOnlyWhenLinkedDependencyChanges() {
        Services services = services();
        CodeRule rule = rule("orderNo", CodeMode.AUTO);
        rule.setLinkedUpdate(Boolean.TRUE);
        rule.setSegments(new java.util.ArrayList<>(rule.getSegments()));
        CodeRuleSegment titleBasis = segment(CodeSegmentType.FIELD_VALUE, null, "title");
        titleBasis.setSequenceBasis(Boolean.TRUE);
        rule.getSegments().add(1, titleBasis);
        services.ruleService.saveRuleTree(rule);
        DynamicRecordService recordService = dynamicService(services, statefulOperations());
        DynamicRecord record = recordService.newRecord(MODULE, "main").setValue("title", "A");
        recordService.create(MODULE, "main", record);

        DynamicRecord update = recordService.newRecord(MODULE, "main").setValue("title", "B");
        update.setId(record.getId());
        update.setVersion(1);
        recordService.update(MODULE, "main", update);

        assertThat(update.getValue("orderNo")).isEqualTo("SO-B0001");

        Services stableServices = services();
        CodeRule stableRule = rule("orderNo", CodeMode.AUTO);
        stableRule.setLinkedUpdate(Boolean.FALSE);
        stableRule.setSegments(new java.util.ArrayList<>(stableRule.getSegments()));
        CodeRuleSegment stableTitleBasis = segment(CodeSegmentType.FIELD_VALUE, null, "title");
        stableTitleBasis.setSequenceBasis(Boolean.TRUE);
        stableRule.getSegments().add(1, stableTitleBasis);
        stableServices.ruleService.saveRuleTree(stableRule);
        DynamicRecordService stableRecordService = dynamicService(stableServices, statefulOperations());
        DynamicRecord stable = stableRecordService.newRecord(MODULE, "main").setValue("title", "A");
        stableRecordService.create(MODULE, "main", stable);

        DynamicRecord stableUpdate = stableRecordService.newRecord(MODULE, "main").setValue("title", "B");
        stableUpdate.setId(stable.getId());
        stableUpdate.setVersion(1);
        stableRecordService.update(MODULE, "main", stableUpdate);

        assertThat(safeValue(stableUpdate, "orderNo")).isNull();
    }

    @Test
    void shouldRecalculateFormulaCodeOnlyWhenFormulaDependencyChanges() {
        Services services = services();
        CodeRule rule = rule("orderNo", CodeMode.AUTO);
        rule.setLinkedUpdate(Boolean.TRUE);
        rule.setSegments(new java.util.ArrayList<>(rule.getSegments()));
        CodeRuleSegment titleFormula = new CodeRuleSegment();
        titleFormula.setSegmentType(CodeSegmentType.FORMULA);
        titleFormula.setFormulaExpr("UPPER({title})");
        titleFormula.setSequenceBasis(Boolean.TRUE);
        rule.getSegments().add(1, titleFormula);
        services.ruleService.saveRuleTree(rule);
        DynamicRecordService recordService = dynamicService(services, statefulOperations());
        DynamicRecord record = recordService.newRecord(MODULE, "main")
                .setValue("title", "alpha")
                .setValue("note", "before");
        recordService.create(MODULE, "main", record);

        DynamicRecord unrelatedUpdate = recordService.newRecord(MODULE, "main")
                .setValue("note", "after");
        unrelatedUpdate.setId(record.getId());
        unrelatedUpdate.setVersion(1);
        recordService.update(MODULE, "main", unrelatedUpdate);

        assertThat(safeValue(unrelatedUpdate, "orderNo")).isNull();
        assertThat(recordService.select(MODULE, "main", record.getId()).getValue("orderNo"))
                .isEqualTo("SO-ALPHA0001");

        DynamicRecord titleUpdate = recordService.newRecord(MODULE, "main")
                .setValue("title", "beta");
        titleUpdate.setId(record.getId());
        titleUpdate.setVersion(2);
        recordService.update(MODULE, "main", titleUpdate);

        assertThat(titleUpdate.getValue("orderNo")).isEqualTo("SO-BETA0001");
    }

    @Test
    void shouldRecalculateMixedSegmentCodeWhenNonFormulaDependencyChanges() {
        Services services = services();
        CodeRule rule = rule("orderNo", CodeMode.AUTO);
        rule.setLinkedUpdate(Boolean.TRUE);
        rule.setSegments(new java.util.ArrayList<>(rule.getSegments()));
        CodeRuleSegment category = segment(CodeSegmentType.FIELD_VALUE, null, "category");
        category.setSequenceBasis(Boolean.TRUE);
        CodeRuleSegment titleFormula = new CodeRuleSegment();
        titleFormula.setSegmentType(CodeSegmentType.FORMULA);
        titleFormula.setFormulaExpr("UPPER({title})");
        titleFormula.setSequenceBasis(Boolean.TRUE);
        rule.getSegments().add(1, category);
        rule.getSegments().add(2, titleFormula);
        services.ruleService.saveRuleTree(rule);
        DynamicRecordService recordService = dynamicService(services, statefulOperations());
        DynamicRecord record = recordService.newRecord(MODULE, "main")
                .setValue("title", "alpha")
                .setValue("category", "A");
        recordService.create(MODULE, "main", record);

        DynamicRecord update = recordService.newRecord(MODULE, "main")
                .setValue("category", "B");
        update.setId(record.getId());
        update.setVersion(1);
        recordService.update(MODULE, "main", update);

        assertThat(update.getValue("orderNo")).isEqualTo("SO-BALPHA0001");
    }

    @Test
    void shouldRetainCodeOccupationDuringSoftDeletion() {
        Services services = services();
        CodeRule rule = rule("orderNo", CodeMode.AUTO);
        rule.setAllowRecycle(Boolean.FALSE);
        services.ruleService.saveRuleTree(rule);
        DynamicRecordService recordService = dynamicService(services, statefulOperations());
        DynamicRecord record = recordService.newRecord(MODULE, "main").setValue("title", "first");
        recordService.create(MODULE, "main", record);

        recordService.delete(MODULE, "main", record.getId());

        CodeLedgerEntry oldLedger = services.ledgerService.findByRuleAndValue(rule.getId(), "SO-0001");
        assertThat(oldLedger.getStatus()).isEqualTo(CodeLedgerStatus.ACTIVE);
        assertThat(oldLedger.getSourceRecordId()).isEqualTo(record.getId());
        assertThat(services.recycleService.list(null, net.ximatai.muyun.database.core.orm.PageRequest.of(1, 10))).isEmpty();
    }

    private DynamicRecordService dynamicService(Services services) {
        return dynamicService(services, operations());
    }

    private DynamicRecordService dynamicService(Services services, IDatabaseOperations<Object> operations) {
        DynamicRecordRuntime runtime = DynamicRecordRuntime.builder(operations)
                .registry(new DynamicModuleRegistry())
                .build()
                .register(module());
        DynamicRecordService[] holder = new DynamicRecordService[1];
        DynamicCodeCoordinator coordinator = new DynamicCodeCoordinator(
                services.ruleService,
                services.generateService,
                services.previewService,
                services.stateService,
                services.ledgerService,
                services.recycleService,
                new DynamicRecordServiceProxy(holder),
                new CodeBusinessTimeService(clock),
                clock
        );
        holder[0] = new DynamicRecordService(
                runtime,
                new net.ximatai.muyun.spring.common.platform.AllowAllActionExecutionPolicyService(),
                new net.ximatai.muyun.spring.common.platform.AllowAllDataScopeCriteriaService(),
                coordinator
        );
        return holder[0];
    }

    @SuppressWarnings("unchecked")
    private IDatabaseOperations<Object> statefulOperations() {
        IDatabaseOperations<Object> operations = mock(IDatabaseOperations.class);
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        AtomicInteger ids = new AtomicInteger();
        when(operations.getDBInfo()).thenReturn(new DBInfo("POSTGRESQL").setName("muyun_test"));
        when(operations.getDefaultSchemaName()).thenReturn("public");
        when(operations.insertItem(anyString(), anyString(), anyMap(), anyString())).thenAnswer(invocation -> {
            Map<String, Object> body = new LinkedHashMap<>(invocation.getArgument(2));
            String id = String.valueOf(body.get("id"));
            body.put("id", id);
            rows.put(id, body);
            return id;
        });
        when(operations.query(anyString(), anyMap())).thenAnswer(invocation -> rows.values().stream()
                .filter(row -> !Boolean.TRUE.equals(row.get("deleted")))
                .map(LinkedHashMap::new)
                .toList());
        when(operations.row(anyString(), anyMap())).thenAnswer(invocation -> Map.of("total_count", (long) rows.size()));
        when(operations.patchUpdateItemWhere(anyString(), anyString(), anyMap(), anyMap(), anyString())).thenAnswer(invocation -> {
            Map<String, Object> body = invocation.getArgument(2);
            Map<String, Object> where = invocation.getArgument(3);
            String id = String.valueOf(where.get("id"));
            Map<String, Object> row = rows.get(id);
            if (row == null) {
                return 0;
            }
            row.putAll(body);
            return 1;
        });
        return operations;
    }

    @SuppressWarnings("unchecked")
    private IDatabaseOperations<Object> operations() {
        IDatabaseOperations<Object> operations = mock(IDatabaseOperations.class);
        when(operations.getDBInfo()).thenReturn(new DBInfo("POSTGRESQL").setName("muyun_test"));
        when(operations.getDefaultSchemaName()).thenReturn("public");
        when(operations.insertItem(anyString(), anyString(), anyMap(), anyString())).thenAnswer(call -> String.valueOf(((Map<?, ?>) call.getArgument(2)).get("id")));
        when(operations.query(anyString(), anyMap())).thenReturn(List.of());
        when(operations.row(anyString(), anyMap())).thenReturn(Map.of("total_count", 0L));
        when(operations.patchUpdateItemWhere(anyString(), anyString(), anyMap(), anyMap(), anyString())).thenReturn(1);
        return operations;
    }

    private ModuleDefinition module() {
        EntityDefinition entity = new EntityDefinition(
                "main",
                "crm_order",
                "Order",
                List.of(
                        FieldDefinition.string("title", "Title").required(),
                        FieldDefinition.string("note", "Note"),
                        FieldDefinition.string("category", "Category"),
                        FieldDefinition.string("orderNo", "Order No").column("order_no").length(64).unique()
                ),
                Set.of(EntityCapability.CRUD)
        ).withBusinessCodes(Map.of("orderNo", new net.ximatai.muyun.spring.ability.code.CodeFieldDefinition(true, null)));
        return new ModuleDefinition(MODULE, "Order", List.of(entity));
    }

    private CodeRule rule(String fieldName, CodeMode mode) {
        CodeRule rule = new CodeRule();
        rule.setModuleAlias(MODULE);
        rule.setEntityAlias("main");
        rule.setFieldName(fieldName);
        rule.setFieldRole(CodeFieldRole.PRIMARY);
        rule.setMode(mode);
        rule.setEnabled(Boolean.TRUE);
        rule.setSegments(List.of(
                segment(CodeSegmentType.CONSTANT, "SO-", null),
                sequenceSegment()
        ));
        CodeSequencePolicy policy = new CodeSequencePolicy();
        policy.setStartValue(1L);
        policy.setStepValue(1L);
        policy.setSequenceLength(4);
        policy.setResetPolicy(CodeSequenceResetPolicy.NONE);
        rule.setSequencePolicy(policy);
        return rule;
    }

    private CodeRuleSegment sequenceSegment() {
        CodeRuleSegment segment = segment(CodeSegmentType.SEQUENCE, null, null);
        segment.setLength(4);
        return segment;
    }

    private CodeRuleSegment segment(CodeSegmentType type, String fixedValue, String sourceRef) {
        CodeRuleSegment segment = new CodeRuleSegment();
        segment.setSegmentType(type);
        segment.setFixedValue(fixedValue);
        segment.setSourceRef(sourceRef);
        return segment;
    }

    private Services services() {
        CodeRuleSegmentService segmentService = new CodeRuleSegmentService(new TestMemoryDao<>());
        CodeSequencePolicyService policyService = new CodeSequencePolicyService(new TestMemoryDao<>());
        CodeValueMappingService mappingService = new CodeValueMappingService(new TestMemoryDao<>());
        CodeRuleService ruleService = new CodeRuleService(
                new TestMemoryDao<>(),
                segmentService,
                policyService,
                mappingService
        );
        CodeSequenceStateService stateService = new CodeSequenceStateService(new TestMemoryDao<>());
        CodeRecycleEntryService recycleService = new CodeRecycleEntryService(new TestMemoryDao<>());
        CodeLedgerEntryService ledgerService = new CodeLedgerEntryService(new TestMemoryDao<>(), recycleService);
        CodePreviewService previewService = new CodePreviewService(
                new net.ximatai.muyun.spring.common.formula.FormulaEngine(clock),
                clock
        );
        CodeGenerateService generateService = new CodeGenerateService(ruleService, previewService, stateService,
                recycleService, null, null, clock);
        return new Services(ruleService, previewService, generateService, recycleService, ledgerService, stateService);
    }

    private record Services(CodeRuleService ruleService,
                            CodePreviewService previewService,
                            CodeGenerateService generateService,
                            CodeRecycleEntryService recycleService,
                            CodeLedgerEntryService ledgerService, CodeSequenceStateService stateService) {
    }

    private Object safeValue(DynamicRecord record, String fieldName) {
        try {
            return record.getValue(fieldName);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static final class DynamicRecordServiceProxy extends DynamicRecordService {
        private final DynamicRecordService[] delegate;

        private DynamicRecordServiceProxy(DynamicRecordService[] delegate) {
            super(new DynamicRecordRuntime(mock(IDatabaseOperations.class)));
            this.delegate = delegate;
        }

        @Override
        public net.ximatai.muyun.spring.dynamic.runtime.DynamicEntityOperations entity(String moduleAlias, String entityAlias) {
            return delegate[0].entity(moduleAlias, entityAlias);
        }
    }
}
