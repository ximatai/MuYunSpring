package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.metadata.DBInfo;
import net.ximatai.muyun.database.core.orm.CriteriaSqlCompiler;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.event.RuntimeEvent;
import net.ximatai.muyun.spring.ability.event.RuntimeEventPublisher;
import net.ximatai.muyun.spring.ability.event.RuntimeEventType;
import net.ximatai.muyun.spring.ability.event.RuntimeMutationSource;
import net.ximatai.muyun.spring.ability.reference.ReferenceOption;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.formula.FormulaRulePhase;
import net.ximatai.muyun.spring.common.formula.FormulaIssueLevel;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.platform.ActionDefaultGrantPolicy;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaResult;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaService;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.web.RequestTraceContext;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicAssociationRelationOverview;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicActionDescriptor;
import net.ximatai.muyun.spring.dynamic.metadata.AssociationViewQueryMappingGroupOperator;
import net.ximatai.muyun.spring.dynamic.metadata.AssociationViewRootQueryMapping;
import net.ximatai.muyun.spring.dynamic.metadata.AssociationViewDisplayMode;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionCategory;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionExecutorType;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionLevel;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.dynamic.metadata.EntityAssociationViewDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityFormulaRuleDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityReferenceAffectDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityReferenceDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityReferenceFilterDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityRelationDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityViewType;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDictionaryBinding;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionException;
import net.ximatai.muyun.spring.dynamic.metadata.DynamicQueryOperator;
import net.ximatai.muyun.spring.dynamic.openapi.DynamicOpenApiDocument;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamicRecordServiceTest {
    private static final String SCHEMA = "public";
    private static final String MODULE = "sales.contract";

    @Test
    void shouldKeepVersionedDeleteInsideTransactionBoundary() throws Exception {
        assertThat(DynamicRecordService.class
                .getMethod("delete", String.class, String.class, String.class, Integer.class)
                .getAnnotation(Transactional.class)).isNotNull();
    }

    @Test
    void shouldRunCrudThroughStableServiceApi() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.insertItem(eq(SCHEMA), eq("app_contract"), anyMap(), eq("id")))
                .thenAnswer(invocation -> invocation.<Map<String, Object>>getArgument(2).get("id"));
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(row("contract-1", "C-001", 0, false)));
        when(operations.row(anyString(), anyMap())).thenReturn(Map.of("total_count", 1));
        DynamicRecordService service = service(operations, contractEntity());
        DynamicRecord record = service.newRecord(MODULE, "contract")
                .setValue("code", "C-001")
                .setValue("amount", BigDecimal.TEN);
        record.setId("contract-1");

        String id = service.create(MODULE, "contract", record);
        DynamicRecord selected = service.select(MODULE, "contract", id);
        selected.setValue("amount", BigDecimal.ONE);
        service.update(MODULE, "contract", selected);
        assertThat(service.list(MODULE, "contract", Criteria.of().eq("code", "C-001"), PageRequest.of(1, 10), Sort.desc("amount")))
                .hasSize(1);
        service.page(MODULE, "contract", Criteria.of().eq("code", "C-001"), PageRequest.of(1, 10));
        assertThat(service.count(MODULE, "contract", Criteria.of().eq("code", "C-001"))).isEqualTo(1);
        service.delete(MODULE, "contract", id);
        assertThat(service.selectIgnoreSoftDelete(MODULE, "contract", id)).isNotNull();
        service.deleteBatch(MODULE, "contract", List.of(id));

        verify(operations).insertItem(eq(SCHEMA), eq("app_contract"), anyMap(), eq("id"));
        verify(operations, org.mockito.Mockito.times(3))
                .patchUpdateItemWhere(eq(SCHEMA), eq("app_contract"), anyMap(), anyMap(), eq("id"));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(operations, org.mockito.Mockito.atLeastOnce()).query(sql.capture(), anyMap());
        assertThat(sql.getAllValues()).anySatisfy(value -> assertThat(value)
                .contains("\"deleted\" =")
                .contains("\"deleted\" IS NULL")
                .contains("ORDER BY \"amount\" DESC"));
    }

    @Test
    void shouldExposeMutationMetadataToCoordinatorDuringCreate() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.insertItem(eq(SCHEMA), eq("app_contract"), anyMap(), eq("id")))
                .thenAnswer(invocation -> invocation.<Map<String, Object>>getArgument(2).get("id"));
        AtomicReference<Object> captured = new AtomicReference<>();
        DynamicRecordMutationCoordinator coordinator = new DynamicRecordMutationCoordinator() {
            @Override
            public void afterCreate(String moduleAlias, String entityAlias, DynamicRecord record, String id) {
                captured.set(DynamicMutationContext.current()
                        .map(context -> context.metadata("originContext"))
                        .orElse(null));
            }
        };
        DynamicRecordRuntime runtime = DynamicRecordRuntime.builder(operations)
                .registry(new DynamicModuleRegistry())
                .fieldValueValidator(DynamicFieldValueValidator.NONE)
                .eventPublisher(RuntimeEventPublisher.noop())
                .build()
                .register(new ModuleDefinition(MODULE, "Contract", List.of(contractEntity())));
        DynamicRecordService service = new DynamicRecordService(runtime,
                new net.ximatai.muyun.spring.common.platform.AllowAllActionExecutionPolicyService(),
                new net.ximatai.muyun.spring.common.platform.AllowAllDataScopeCriteriaService(),
                coordinator);
        DynamicRecord record = service.newRecord(MODULE, "contract").setValue("code", "C-001");
        record.setId("contract-1");

        service.create(MODULE, "contract", record, Map.of("originContext", "ctx-1"));

        assertThat(captured).hasValue("ctx-1");
    }

    @Test
    void shouldPublishMutationSnapshotEventsToCoordinator() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.insertItem(eq(SCHEMA), eq("app_contract"), anyMap(), eq("id")))
                .thenAnswer(invocation -> invocation.<Map<String, Object>>getArgument(2).get("id"));
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(row("contract-1", "C-001", 0, false)));
        List<DynamicRecordMutationEvent> events = new ArrayList<>();
        DynamicRecordService service = serviceWithCoordinator(operations, contractEntity(), new DynamicRecordMutationCoordinator() {
            @Override
            public void afterMutation(DynamicRecordMutationEvent event) {
                events.add(event);
            }
        });
        DynamicRecord record = service.newRecord(MODULE, "contract").setValue("code", "C-001");
        record.setId("contract-1");

        service.create(MODULE, "contract", record);
        service.update(MODULE, "contract", record.setValue("amount", BigDecimal.ONE));
        service.delete(MODULE, "contract", "contract-1");

        assertThat(events).hasSize(3);
        assertThat(events.get(0).eventType()).isEqualTo(DynamicRecordMutationEventType.AFTER_SAVE);
        assertThat(events.get(0).saveOperation()).isEqualTo(DynamicRecordSaveOperation.CREATE);
        assertThat(events.get(0).beforeRecord()).isNull();
        assertThat(events.get(0).afterRecord().getId()).isEqualTo("contract-1");
        assertThat(events.get(1).saveOperation()).isEqualTo(DynamicRecordSaveOperation.UPDATE);
        assertThat(events.get(1).beforeRecord().getId()).isEqualTo("contract-1");
        assertThat(events.get(1).afterRecord().getValue("amount")).isEqualTo(BigDecimal.ONE);
        assertThat(events.get(2).eventType()).isEqualTo(DynamicRecordMutationEventType.AFTER_DELETE);
        assertThat(events.get(2).beforeRecord().getId()).isEqualTo("contract-1");
        assertThat(events.get(2).afterRecord()).isNull();
    }

    @Test
    void shouldMarkWriteBackMutationContextOnSystemSave() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(row("contract-1", "C-001", 0, false)));
        AtomicReference<DynamicRecordMutationEvent> captured = new AtomicReference<>();
        DynamicRecordService service = serviceWithCoordinator(operations, contractEntity(), new DynamicRecordMutationCoordinator() {
            @Override
            public void afterMutation(DynamicRecordMutationEvent event) {
                captured.set(event);
            }
        });
        DynamicRecord record = service.newRecord(MODULE, "contract").setValue("code", "C-002");
        record.setId("contract-1");
        DynamicWriteBackContext context = new DynamicWriteBackContext("trace-1", 2, "exec-1", false);

        service.updateWriteBack(MODULE, "contract", record, context);

        assertThat(captured.get().mutationSource()).isEqualTo(RuntimeMutationSource.WRITE_BACK);
        assertThat(captured.get().traceId()).isEqualTo("trace-1");
        assertThat(captured.get().depth()).isEqualTo(2);
        assertThat(captured.get().parentExecutionId()).isEqualTo("exec-1");
        assertThat(captured.get().cascadeAllowed()).isFalse();
        assertThat(captured.get().shouldSkipForSingleHopCascade()).isTrue();
    }

    @Test
    void shouldExposeWriteBackContextOnRuntimeRecordEventPayload() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(row("contract-1", "C-001", 0, false)));
        CollectingRuntimeEventPublisher events = new CollectingRuntimeEventPublisher();
        DynamicRecordService service = service(operations, contractEntity(), events);
        DynamicRecord record = service.newRecord(MODULE, "contract").setValue("code", "C-002");
        record.setId("contract-1");

        service.updateWriteBack(MODULE, "contract", record,
                new DynamicWriteBackContext("trace-wb", 2, "exec-1", false));

        assertThat(events.events()).singleElement()
                .satisfies(event -> {
                    assertThat(event.mutationSource()).isEqualTo(RuntimeMutationSource.WRITE_BACK);
                    assertThat(event.traceId()).isEqualTo("trace-wb");
                    assertThat(event.systemContext()).isFalse();
                    assertThat(event.systemReason()).isNull();
                    assertThat(event.payload())
                            .containsEntry("writeBackDepth", 2)
                            .containsEntry("writeBackParentExecutionId", "exec-1")
                            .containsEntry("writeBackCascadeAllowed", false);
                });
    }

    @Test
    void shouldBypassBusinessDataScopeForWriteBackUpdate() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(row("hidden", "C-001", 0, false)));
        when(operations.patchUpdateItemWhere(anyString(), anyString(), anyMap(), anyMap(), anyString())).thenReturn(1);
        CollectingRuntimeEventPublisher events = new CollectingRuntimeEventPublisher();
        DynamicRecordService service = service(operations, dataScopedActionEntity(), events,
                new FailingDataScopeCriteriaService());
        DynamicRecord record = service.newRecord(MODULE, "contract").setValue("code", "C-002");
        record.setId("hidden");

        assertThat(service.updateWriteBack(MODULE, "contract", record,
                new DynamicWriteBackContext("trace-wb", 1, "exec-1", false))).isEqualTo(1);

        verify(operations).patchUpdateItemWhere(eq(SCHEMA), eq("app_contract"), anyMap(), anyMap(), eq("id"));
        assertThat(events.events()).singleElement()
                .satisfies(event -> {
                    assertThat(event.mutationSource()).isEqualTo(RuntimeMutationSource.WRITE_BACK);
                    assertThat(event.systemContext()).isFalse();
                });
    }

    @Test
    void shouldExposeWriteBackContextOnCreateRuntimeRecordEventPayload() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.insertItem(eq(SCHEMA), eq("app_contract"), anyMap(), eq("id")))
                .thenAnswer(invocation -> invocation.<Map<String, Object>>getArgument(2).get("id"));
        CollectingRuntimeEventPublisher events = new CollectingRuntimeEventPublisher();
        DynamicRecordService service = service(operations, contractEntity(), events);
        DynamicRecord record = service.newRecord(MODULE, "contract").setValue("code", "C-002");
        record.setId("contract-1");

        service.createWriteBack(MODULE, "contract", record,
                new DynamicWriteBackContext("trace-wb-create", 1, "exec-create", true));

        assertThat(events.events()).singleElement()
                .satisfies(event -> {
                    assertThat(event.eventType()).isEqualTo(RuntimeEventType.AFTER_CREATE);
                    assertThat(event.mutationSource()).isEqualTo(RuntimeMutationSource.WRITE_BACK);
                    assertThat(event.traceId()).isEqualTo("trace-wb-create");
                    assertThat(event.payload())
                            .containsEntry("writeBackDepth", 1)
                            .containsEntry("writeBackParentExecutionId", "exec-create")
                            .containsEntry("writeBackCascadeAllowed", true);
                });
    }

    @Test
    void shouldRestoreOuterMutationContextAfterNestedWriteBackContext() {
        try (DynamicMutationContext outer = DynamicMutationContext.open(null,
                RuntimeMutationSource.BUSINESS, "trace-root", Map.of("originContext", "ctx-1"))) {
            assertThat(DynamicMutationContext.current().orElseThrow().mutationSource())
                    .isEqualTo(RuntimeMutationSource.BUSINESS);
            try (DynamicMutationContext ignored = DynamicMutationContext.openWriteBack(null,
                    new DynamicWriteBackContext("trace-wb", 1, "exec-1", true), Map.of())) {
                DynamicMutationContext current = DynamicMutationContext.current().orElseThrow();
                assertThat(current.mutationSource()).isEqualTo(RuntimeMutationSource.WRITE_BACK);
                assertThat(current.traceId()).isEqualTo("trace-wb");
                assertThat(current.depth()).isEqualTo(1);
            }
            DynamicMutationContext restored = DynamicMutationContext.current().orElseThrow();
            assertThat(restored).isSameAs(outer);
            assertThat(restored.mutationSource()).isEqualTo(RuntimeMutationSource.BUSINESS);
            assertThat(restored.metadata("originContext")).isEqualTo("ctx-1");
        }
        assertThat(DynamicMutationContext.current()).isEmpty();
    }

    @Test
    void shouldRejectNullRecordOnUpdateWithClearMessage() {
        DynamicRecordService service = service(operations(), contractEntity());

        assertThatThrownBy(() -> service.update(MODULE, "contract", null))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("dynamic record must not be null");
    }

    @Test
    void shouldBindEntityOperationsForBusinessScopedCalls() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.insertItem(eq(SCHEMA), eq("app_contract"), anyMap(), eq("id")))
                .thenAnswer(invocation -> invocation.<Map<String, Object>>getArgument(2).get("id"));
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(row("contract-1", "C-001", 0, false)));
        DynamicRecordService service = service(operations, contractEntity());
        DynamicEntityOperations contracts = service.entity(MODULE, "contract");
        DynamicRecord record = contracts.newRecord()
                .setValue("code", "C-001")
                .setValue("amount", BigDecimal.TEN);
        record.setId("contract-1");

        assertThat(contracts.create(record)).isEqualTo("contract-1");
        assertThat(contracts.select("contract-1").getValue("code")).isEqualTo("C-001");
        contracts.delete("contract-1");

        verify(operations).insertItem(eq(SCHEMA), eq("app_contract"), anyMap(), eq("id"));
        verify(operations).patchUpdateItemWhere(eq(SCHEMA), eq("app_contract"), anyMap(), anyMap(), eq("id"));
    }

    @Test
    void shouldExposeOpenApiFromStableServiceApi() {
        DynamicRecordService service = service(operations(), contractEntity());

        DynamicOpenApiDocument document = service.openApi(MODULE);

        assertThat(document.moduleAlias()).isEqualTo(MODULE);
        assertThat(document.basePath()).isEqualTo("/" + MODULE);
        assertThat(document.operations())
                .extracting(DynamicOpenApiDocument.Operation::path)
                .contains("/" + MODULE + "/query", "/" + MODULE + "/insert")
                .doesNotContain("/" + MODULE + "/openapi");
        assertThat(document.schemas()).containsKey("ContractRecord");
    }

    @Test
    void shouldKeepCapabilityGatesThroughBoundEntityOperations() {
        DynamicRecordService service = service(operations(), contractEntity());
        DynamicEntityOperations contracts = service.entity(MODULE, "contract");

        assertThatThrownBy(() -> contracts.enable("contract-1"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("ENABLE");
    }

    @Test
    void shouldEvaluateConfiguredActionAvailabilityThroughStableServiceApi() {
        DynamicRecordService service = actionService(operations());
        DynamicRecord draft = service.newRecord(MODULE, "contract")
                .setValue("code", "C-001")
                .setValue("status", "draft");
        DynamicRecord submitted = service.newRecord(MODULE, "contract")
                .setValue("code", "C-002")
                .setValue("status", "submitted");

        DynamicActionAvailability available = service.actionAvailability(MODULE, "submit", draft);
        DynamicActionAvailability unavailable = service.entity(MODULE, "contract")
                .actionAvailability("submit", submitted);

        assertThat(service.action(MODULE, "submit").availabilityCondition()).isTrue();
        assertThat(available.available()).isTrue();
        assertThat(available.report().errors()).isEmpty();
        assertThat(unavailable.available()).isFalse();
        assertThat(unavailable.message()).isEqualTo("只有草稿合同可以提交");
        assertThat(unavailable.report().errors()).singleElement()
                .extracting(error -> error.phase())
                .isEqualTo(net.ximatai.muyun.spring.common.formula.FormulaRulePhase.ACTION_AVAILABLE);
        assertThat(service.module(MODULE).actionAvailability("submit", draft).available()).isTrue();
    }

    @Test
    void shouldResolvePersistedRecordActionAvailabilityInOneBatchRead() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(
                actionRow("contract-1", "C-001", "draft"),
                actionRow("contract-2", "C-002", "submitted")
        ));
        DynamicRecordService service = actionService(operations);

        List<DynamicRecordActionAvailability> availability = service.recordActionAvailability(
                MODULE, "contract", List.of("submit"), List.of("contract-1", "contract-2"));

        assertThat(availability).extracting(DynamicRecordActionAvailability::recordId)
                .containsExactly("contract-1", "contract-2");
        assertThat(availability.get(0).actions().get("submit").available()).isTrue();
        assertThat(availability.get(1).actions().get("submit").available()).isFalse();
        verify(operations, times(1)).query(anyString(), anyMap());
    }

    @Test
    void shouldExposeModuleActionsAsMainEntityActions() {
        DynamicRecordService service = actionService(operations());

        assertThat(service.module(MODULE).actions())
                .containsExactlyElementsOf(service.entity(MODULE, "contract").actions());
        assertThat(service.module(MODULE).action("submit"))
                .isEqualTo(service.entity(MODULE, "contract").action("submit"));
    }

    @Test
    void shouldEvaluateModuleActionAvailabilityAsMainEntityActionAvailability() {
        DynamicRecordService service = actionService(operations());
        DynamicRecord draft = service.newRecord(MODULE, "contract")
                .setValue("code", "C-001")
                .setValue("status", "draft");

        DynamicActionAvailability moduleAvailability = service.module(MODULE).actionAvailability("submit", draft);
        DynamicActionAvailability entityAvailability = service.entity(MODULE, "contract").actionAvailability("submit", draft);

        assertThat(moduleAvailability.available()).isEqualTo(entityAvailability.available());
        assertThat(moduleAvailability.message()).isEqualTo(entityAvailability.message());
        assertThat(moduleAvailability.report().errors()).hasSameSizeAs(entityAvailability.report().errors());
    }

    @Test
    void shouldTreatStandardActionWithoutConditionAsAvailable() {
        DynamicRecordService service = actionService(operations());
        DynamicRecord record = service.newRecord(MODULE, "contract")
                .setValue("code", "C-001");

        assertThat(service.entity(MODULE, "contract").actionAvailability("create", record).available()).isTrue();
    }

    @Test
    void shouldExecuteStandardCreateActionThroughStableActionApi() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.insertItem(eq(SCHEMA), eq("app_contract"), anyMap(), eq("id")))
                .thenAnswer(invocation -> invocation.<Map<String, Object>>getArgument(2).get("id"));
        DynamicRecordService service = actionService(operations);
        DynamicRecord record = service.newRecord(MODULE, "contract")
                .setValue("code", "C-001")
                .setValue("status", "draft");
        record.setId("contract-1");

        DynamicActionExecutionResult result = service.module(MODULE)
                .executeAction("create", DynamicActionExecutionRequest.record(record));

        assertThat(result.value()).isEqualTo("contract-1");
        assertThat(result.body().type()).isEqualTo(DynamicActionResultType.RECORD_ID);
        assertThat(result.body().refresh()).isTrue();
        assertThat(result.context().moduleAlias()).isEqualTo(MODULE);
        assertThat(result.context().entityAlias()).isEqualTo("contract");
        assertThat(result.context().actionCode()).isEqualTo("create");
        assertThat(result.context().availability().available()).isTrue();
        verify(operations).insertItem(eq(SCHEMA), eq("app_contract"), anyMap(), eq("id"));
    }

    @Test
    void shouldExposeGeneratedRecordIdInCreateActionContext() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.insertItem(eq(SCHEMA), eq("app_contract"), anyMap(), eq("id")))
                .thenAnswer(invocation -> invocation.<Map<String, Object>>getArgument(2).get("id"));
        DynamicRecordService service = actionService(operations);
        DynamicRecord record = service.newRecord(MODULE, "contract")
                .setValue("code", "C-001")
                .setValue("status", "draft");

        DynamicActionExecutionResult result = service.module(MODULE)
                .executeAction("create", DynamicActionExecutionRequest.record(record));

        assertThat(result.value()).isInstanceOf(String.class);
        assertThat(result.context().recordId()).isEqualTo(result.value());
    }

    @Test
    void shouldPublishActionExecutionEventAfterStandardAction() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.insertItem(eq(SCHEMA), eq("app_contract"), anyMap(), eq("id")))
                .thenAnswer(invocation -> invocation.<Map<String, Object>>getArgument(2).get("id"));
        CollectingRuntimeEventPublisher events = new CollectingRuntimeEventPublisher();
        DynamicRecordService service = actionService(operations, events);
        DynamicRecord record = service.newRecord(MODULE, "contract")
                .setValue("code", "C-001")
                .setValue("status", "draft");
        record.setId("contract-1");

        try (RequestTraceContext.Scope trace = RequestTraceContext.use("request-trace-1");
             CurrentUserContext.Scope ignored = CurrentUserContext.use(CurrentUser.tenantUser("user-1", "Alice", "tenant_a"))) {
            service.module(MODULE).executeAction("create", DynamicActionExecutionRequest.record(record));
        }

        assertThat(events.events()).extracting(RuntimeEvent::eventType)
                .containsExactly(RuntimeEventType.AFTER_CREATE, RuntimeEventType.ACTION_EXECUTED);
        assertThat(events.events().getFirst().mutationSource()).isEqualTo(RuntimeMutationSource.ACTION);
        RuntimeEvent action = events.events().getLast();
        assertThat(action.traceId()).isEqualTo("request-trace-1");
        assertThat(events.events().getFirst().traceId()).isEqualTo(action.traceId());
        assertThat(action.moduleAlias()).isEqualTo(MODULE);
        assertThat(action.entityAlias()).isEqualTo("contract");
        assertThat(action.recordId()).isEqualTo("contract-1");
        assertThat(action.actionCode()).isEqualTo("create");
        assertThat(action.operatorId()).isEqualTo("user-1");
        assertThat(action.operatorType()).isEqualTo("USER");
        assertThat(action.authorizationDecision()).isEqualTo("ALLOW_ALL");
        assertThat(action.authorizationPermissionCode()).isEqualTo(MODULE + ":create");
        assertThat(action.authorizationPermissionActionCode()).isEqualTo("create");
        assertThat(action.payload()).containsEntry("executorType", "STANDARD")
                .containsEntry("actionLevel", "LIST")
                .containsEntry("resultType", "RECORD_ID")
                .containsEntry("refresh", true)
                .containsEntry("result", "contract-1");
    }

    @Test
    void shouldLinkCrudMutationAndActionEventsWithSameTrace() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(row("contract-1", "C-001", 0, false)));
        CollectingRuntimeEventPublisher events = new CollectingRuntimeEventPublisher();
        DynamicRecordService service = service(operations, contractEntity(), events);
        DynamicRecord record = service.newRecord(MODULE, "contract")
                .setValue("code", "C-001")
                .setValue("amount", BigDecimal.ONE);
        record.setId("contract-1");

        DynamicActionExecutionResult update = service.entity(MODULE, "contract")
                .executeAction("update", DynamicActionExecutionRequest.record(record));
        DynamicActionExecutionResult delete = service.entity(MODULE, "contract")
                .executeAction("delete", DynamicActionExecutionRequest.id("contract-1"));

        assertThat(update.value()).isEqualTo(1);
        assertThat(delete.value()).isEqualTo(1);
        assertActionTrace(events.events().get(0), events.events().get(1), RuntimeEventType.AFTER_UPDATE, "update");
        assertActionTrace(events.events().get(2), events.events().get(3), RuntimeEventType.AFTER_DELETE, "delete");
        assertThat(events.events().get(1).traceId()).isEqualTo(update.context().traceId());
        assertThat(events.events().get(3).traceId()).isEqualTo(delete.context().traceId());
    }

    @Test
    void shouldExecuteStandardBatchDeleteActionThroughBatchPath() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(
                row("contract-1", "C-001", 0, false),
                row("contract-2", "C-002", 0, false)
        ));
        CollectingRuntimeEventPublisher events = new CollectingRuntimeEventPublisher();
        DynamicRecordService service = service(operations, contractEntity(), events);

        DynamicActionExecutionResult result = service.entity(MODULE, "contract")
                .executeAction("batchDelete", DynamicActionExecutionRequest.empty()
                        .withIds(List.of("contract-1", "contract-2")));

        assertThat(result.value()).isEqualTo(2);
        assertThat(result.body().type()).isEqualTo(DynamicActionResultType.COUNT);
        assertThat(result.context().actionCode()).isEqualTo("batchDelete");
        assertThat(result.context().action().actionLevel()).isEqualTo(EntityActionLevel.BATCH);
        assertThat(result.context().authorizationPermissionActionCode()).isEqualTo("delete");
        verify(operations, org.mockito.Mockito.times(2))
                .patchUpdateItemWhere(eq(SCHEMA), eq("app_contract"), anyMap(), anyMap(), eq("id"));
        assertThat(events.events()).extracting(RuntimeEvent::eventType)
                .contains(RuntimeEventType.AFTER_DELETE, RuntimeEventType.ACTION_EXECUTED);
        RuntimeEvent action = events.events().getLast();
        assertThat(action.actionCode()).isEqualTo("batchDelete");
        assertThat(action.payload()).containsEntry("resultType", "COUNT")
                .containsEntry("actionLevel", "BATCH")
                .containsEntry("result", 2);
    }

    @Test
    void shouldLinkSortMutationAndActionEventsWithSameTrace() {
        IDatabaseOperations<Object> operations = operations();
        stubSortableRows(operations);
        CollectingRuntimeEventPublisher events = new CollectingRuntimeEventPublisher();
        DynamicRecordService service = service(operations, sortableEntity(), events);

        DynamicActionExecutionResult reorder = service.entity(MODULE, "contract")
                .executeAction("sort", DynamicActionExecutionRequest.empty()
                        .withOrderedIds(List.of("first", "second", "third")));
        DynamicActionExecutionResult moveBefore = service.entity(MODULE, "contract")
                .executeAction("sort", DynamicActionExecutionRequest.id("third").withBeforeId("first"));

        assertThat(reorder.body().refresh()).isTrue();
        assertThat(moveBefore.body().refresh()).isTrue();
        assertActionTrace(events.events().get(0), events.events().get(1), RuntimeEventType.AFTER_UPDATE, "sort");
        assertActionTrace(events.events().get(2), events.events().get(3), RuntimeEventType.AFTER_UPDATE, "sort");
        assertThat(events.events().get(0).payload()).containsEntry("operation", "reorder");
        assertThat(events.events().get(2).payload()).containsEntry("operation", "moveBefore");
        assertThat(events.events().get(1).traceId()).isEqualTo(reorder.context().traceId());
        assertThat(events.events().get(3).traceId()).isEqualTo(moveBefore.context().traceId());
    }

    @Test
    void shouldRejectAmbiguousSortActionIntent() {
        DynamicRecordService service = service(operations(), sortableEntity());

        assertThatThrownBy(() -> service.entity(MODULE, "contract")
                .executeAction("sort", DynamicActionExecutionRequest.id("third")
                        .withBeforeId("first")
                        .withAfterId("second")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one sort intent");
    }

    @Test
    void shouldAuthorizeDynamicActionAtServiceBoundary() {
        RecordingActionPolicyService policyService = new RecordingActionPolicyService();
        DynamicRecordService service = actionService(operations(), RuntimeEventPublisher.noop(),
                new TestActionExecutor("contractSubmit"), submitActionWithoutAvailability("contractSubmit"),
                DynamicActionTransactionOperator.none(), policyService);
        DynamicRecord draft = service.newRecord(MODULE, "contract")
                .setValue("code", "C-001")
                .setValue("status", "draft");
        draft.setId("contract-1");

        try (CurrentUserContext.Scope ignored = CurrentUserContext.use(CurrentUser.tenantUser("user-1", "User", "tenant-a"))) {
            service.module(MODULE).executeAction("submit", DynamicActionExecutionRequest.record(draft));
        }

        assertThat(policyService.context).satisfies(context -> {
            assertThat(context.moduleAlias()).isEqualTo(MODULE);
            assertThat(context.actionCode()).isEqualTo("submit");
            assertThat(context.permissionCode()).isEqualTo(MODULE + ":submit");
            assertThat(context.actionPolicy().accessMode())
                    .isEqualTo(net.ximatai.muyun.spring.common.platform.ActionAccessMode.AUTH_REQUIRED);
            assertThat(context.actionPolicy().actionAuth()).isTrue();
            assertThat(context.actionPolicy().dataAuth()).isFalse();
            assertThat(context.actionPolicy().defaultGrantPolicy()).isEqualTo(ActionDefaultGrantPolicy.NONE);
            assertThat(context.actionPolicy().permissionActionCode()).isEqualTo("submit");
            assertThat(context.recordIds()).containsExactly("contract-1");
            assertThat(context.currentUser()).get().extracting(CurrentUser::userId).isEqualTo("user-1");
        });
    }

    @Test
    void shouldAuthorizeDynamicCreateAtServiceBoundary() {
        IDatabaseOperations<Object> operations = operations();
        ActionExecutionPolicyService policyService = context -> {
            if ("create".equals(context.actionCode())) {
                throw new PlatformException("create denied");
            }
        };
        DynamicRecordRuntime runtime = new DynamicRecordRuntime(operations)
                .register(new ModuleDefinition(MODULE, "Contract", List.of(contractEntity())));
        DynamicRecordService service = new DynamicRecordService(
                runtime,
                policyService,
                new net.ximatai.muyun.spring.common.platform.AllowAllDataScopeCriteriaService()
        );
        DynamicRecord record = service.newRecord(MODULE, "contract")
                .setValue("code", "C-001")
                .setValue("amount", BigDecimal.TEN);

        assertThatThrownBy(() -> service.create(MODULE, "contract", record))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("create denied");

        verify(operations, never()).insertItem(anyString(), anyString(), anyMap(), anyString());
    }

    @Test
    void shouldExposeDynamicActionUnavailableWhenActionAuthorizationFails() {
        ActionExecutionPolicyService policyService = context -> {
            if ("submit".equals(context.actionCode())) {
                throw new PlatformException("action denied");
            }
        };
        DynamicRecordService service = actionService(operations(), RuntimeEventPublisher.noop(),
                new TestActionExecutor("contractSubmit"), submitActionWithoutAvailability("contractSubmit"),
                DynamicActionTransactionOperator.none(), policyService);

        DynamicActionAvailability availability = service.actionAuthorizationAvailability(MODULE, "submit", List.of("contract-1"));

        assertThat(availability.available()).isFalse();
        assertThat(availability.message()).isEqualTo("action denied");
    }

    @Test
    void shouldExposeDynamicActionUnavailableWhenRecordDataScopeFails() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = invocation.getArgument(1);
            return params.containsValue("visible") && !params.containsValue("hidden")
                    ? List.of(actionRow("visible", "C-001", "draft"))
                    : List.of();
        });
        DynamicRecordService service = actionService(operations, RuntimeEventPublisher.noop(),
                new TestActionExecutor("contractSubmit"), dataAuthSubmitAction("contractSubmit"),
                DynamicActionTransactionOperator.none(), null, new VisibleOnlyDataScopeCriteriaService());

        DynamicActionAvailability availability = service.actionAuthorizationAvailability(MODULE, "submit", List.of("hidden"));

        assertThat(availability.available()).isFalse();
        assertThat(availability.message()).contains("record data permission denied");
    }

    @Test
    void shouldApplyActionAuthorizationAndDataScopeToPageForAction() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.row(anyString(), anyMap())).thenReturn(Map.of("total_count", 1));
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(actionRow("visible", "C-001", "draft")));
        RecordingActionPolicyService policyService = new RecordingActionPolicyService();
        DataScopeCriteriaService dataScope = mock(DataScopeCriteriaService.class);
        when(dataScope.resolveReadScope(eq(MODULE), any(ActionExecutionPolicy.class), any(Criteria.class), any()))
                .thenAnswer(invocation -> {
                    Criteria criteria = invocation.getArgument(2);
                    Criteria scoped = Criteria.of();
                    if (criteria != null && !criteria.isEmpty()) {
                        scoped.andGroup(criteria.getRoot());
                    }
                    scoped.eq("id", "visible");
                    return DataScopeCriteriaResult.restricted(scoped);
                });
        DynamicRecordService service = actionService(operations, RuntimeEventPublisher.noop(),
                new TestActionExecutor("contractSubmit"), dataAuthSubmitAction("contractSubmit"),
                DynamicActionTransactionOperator.none(), policyService, dataScope);

        assertThat(service.pageForAction(MODULE, "contract", "submit",
                Criteria.of().eq("code", "C-001"), PageRequest.of(1, 10)).getRecords())
                .singleElement()
                .extracting(DynamicRecord::getId)
                .isEqualTo("visible");

        assertThat(policyService.context).isNotNull();
        assertThat(policyService.context.actionCode()).isEqualTo("submit");
        ArgumentCaptor<ActionExecutionPolicy> policy = ArgumentCaptor.forClass(ActionExecutionPolicy.class);
        verify(dataScope).resolveReadScope(eq(MODULE), policy.capture(), any(Criteria.class), any());
        assertThat(policy.getValue().actionCode()).isEqualTo("submit");
        assertThat(policy.getValue().requiresDataScope()).isTrue();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(operations, atLeastOnce()).query(sql.capture(), anyMap());
        assertThat(sql.getAllValues()).anySatisfy(value -> assertThat(value)
                .contains("\"code\" =")
                .contains("\"id\" ="));
    }

    @Test
    void shouldNotHideUnexpectedFailureWhenCheckingDynamicActionAuthorizationAvailability() {
        DynamicRecordService service = actionService(operations(), RuntimeEventPublisher.noop(),
                new TestActionExecutor("contractSubmit"), dataAuthSubmitAction("contractSubmit"),
                DynamicActionTransactionOperator.none(), null, new FailingDataScopeCriteriaService());

        assertThatThrownBy(() -> service.actionAuthorizationAvailability(MODULE, "submit", List.of("contract-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("data scope unavailable");
    }

    @Test
    void shouldApplyDataScopeBeforeExecutingDynamicAction() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = invocation.getArgument(1);
            return params.containsValue("visible") && !params.containsValue("hidden")
                    ? List.of(actionRow("visible", "C-001", "draft"))
                    : List.of();
        });
        DynamicRecordService service = actionService(operations, RuntimeEventPublisher.noop(),
                new WritingActionExecutor(), dataAuthSubmitAction("contractSubmit"),
                DynamicActionTransactionOperator.none(), null, new VisibleOnlyDataScopeCriteriaService());
        DynamicRecord hidden = service.newRecord(MODULE, "contract")
                .setValue("code", "C-002")
                .setValue("status", "draft");
        hidden.setId("hidden");

        assertThatThrownBy(() -> service.module(MODULE).executeAction("submit", DynamicActionExecutionRequest.record(hidden)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("record data permission denied");

        verify(operations, never()).patchUpdateItemWhere(anyString(), anyString(), anyMap(), anyMap(), anyString());
    }

    @Test
    void shouldApplyDataScopeToServiceActionInternalMutation() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = invocation.getArgument(1);
            return params.containsValue("visible") && !params.containsValue("hidden")
                    ? List.of(actionRow("visible", "C-001", "draft"))
                    : List.of();
        });
        DynamicRecordService service = actionService(operations, RuntimeEventPublisher.noop(),
                new CrossRecordWritingActionExecutor(), dataAuthSubmitAction("contractSubmit"),
                DynamicActionTransactionOperator.none(), null, new VisibleOnlyDataScopeCriteriaService());

        assertThatThrownBy(() -> service.module(MODULE).executeAction("submit", DynamicActionExecutionRequest.id("visible")))
                .isInstanceOf(DynamicActionExecutionException.class)
                .hasMessageContaining("record data permission denied");

        verify(operations, never()).patchUpdateItemWhere(anyString(), anyString(), anyMap(), anyMap(), anyString());
    }

    @Test
    void shouldPassScopedCriteriaToConditionBasedDynamicActionExecutor() {
        IDatabaseOperations<Object> operations = operations();
        RecordingCriteriaActionExecutor executor = new RecordingCriteriaActionExecutor();
        DynamicRecordService service = actionService(operations, RuntimeEventPublisher.noop(),
                executor, dataAuthListAction("contractSubmit"),
                DynamicActionTransactionOperator.none(), null, new VisibleOnlyDataScopeCriteriaService());

        service.module(MODULE).executeAction("submit", DynamicActionExecutionRequest.empty()
                .withCriteria(Criteria.of().eq("status", "draft")));

        assertThat(executor.request.criteria()).isNotNull();
        String sql = new CriteriaSqlCompiler()
                .compile(executor.request.criteria(), field -> field, DBInfo.Type.POSTGRESQL)
                .getSql();
        assertThat(sql)
                .contains("\"status\" =")
                .contains("\"id\" =");
    }

    @Test
    void shouldIntersectExplicitIdsWhenPassingScopedCriteriaToDynamicActionExecutor() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = invocation.getArgument(1);
            return params.containsValue("visible")
                    ? List.of(actionRow("visible", "C-001", "draft"))
                    : List.of();
        });
        RecordingCriteriaActionExecutor executor = new RecordingCriteriaActionExecutor();
        DynamicRecordService service = actionService(operations, RuntimeEventPublisher.noop(),
                executor, dataAuthListAction("contractSubmit"),
                DynamicActionTransactionOperator.none(), null, new CrossTenantAllDataScopeCriteriaService());

        service.module(MODULE).executeAction("submit", DynamicActionExecutionRequest.empty()
                .withIds(List.of("visible"))
                .withCriteria(Criteria.of().eq("status", "draft")));

        var compiled = new CriteriaSqlCompiler()
                .compile(executor.request.criteria(), field -> field, DBInfo.Type.POSTGRESQL);
        assertThat(compiled.getSql())
                .contains("\"status\" =")
                .contains("\"id\" =");
        assertThat(compiled.getParams()).containsValue("draft").containsValue("visible");
    }

    @Test
    void shouldUseCriteriaScopeTenantModeWhenExecutingConditionBasedDynamicAction() {
        IDatabaseOperations<Object> operations = operations();
        RecordingCriteriaActionExecutor executor = new RecordingCriteriaActionExecutor();
        DynamicRecordService service = actionService(operations, RuntimeEventPublisher.noop(),
                executor, dataAuthListAction("contractSubmit"),
                DynamicActionTransactionOperator.none(), null, new CrossTenantAllDataScopeCriteriaService());

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            service.module(MODULE).executeAction("submit", DynamicActionExecutionRequest.empty()
                    .withCriteria(Criteria.of().eq("status", "draft")));
        }

        assertThat(executor.tenantFilterBypassed).isTrue();
    }

    @Test
    void shouldApplyDataScopeToStandardDynamicUpdateAction() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = invocation.getArgument(1);
            return params.containsValue("visible") && !params.containsValue("hidden")
                    ? List.of(actionRow("visible", "C-001", "draft"))
                    : List.of();
        });
        DynamicRecordService service = service(operations, dataScopedActionEntity(),
                RuntimeEventPublisher.noop(), new VisibleOnlyDataScopeCriteriaService());
        DynamicRecord hidden = service.newRecord(MODULE, "contract")
                .setValue("code", "C-002")
                .setValue("status", "submitted");
        hidden.setId("hidden");

        assertThatThrownBy(() -> service.entity(MODULE, "contract")
                .executeAction("update", DynamicActionExecutionRequest.record(hidden)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("record data permission denied");

        verify(operations, never()).patchUpdateItemWhere(anyString(), anyString(), anyMap(), anyMap(), anyString());
    }

    @Test
    void shouldApplyUpdateDataScopeBeforePreviewingAnExistingFormulaRecord() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = invocation.getArgument(1);
            return params.containsValue("visible") && !params.containsValue("hidden")
                    ? List.of(actionRow("visible", "C-001", "draft"))
                    : List.of();
        });
        EntityDefinition entity = dataScopedActionEntity().withFormulaRules(
                EntityFormulaRuleDefinition.validation("codeRequired", "code", "PRESENT({code})", "code is required")
        );
        DynamicRecordService service = service(operations, entity,
                RuntimeEventPublisher.noop(), new VisibleOnlyDataScopeCriteriaService());
        DynamicRecord hidden = service.newRecord(MODULE, "contract").setValue("code", "C-002");
        hidden.setId("hidden");

        assertThatThrownBy(() -> service.previewFormula(MODULE, "contract", hidden))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("record data permission denied");
    }

    @Test
    void shouldRejectPreviewForANonexistentExistingFormulaRecord() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenReturn(List.of());
        DynamicRecordService service = service(operations, formulaValidationEntity());
        DynamicRecord missing = service.newRecord(MODULE, "contract").setValue("amount", BigDecimal.TEN);
        missing.setId("missing");

        assertThatThrownBy(() -> service.previewFormula(MODULE, "contract", missing))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dynamic record not found: missing");
    }

    @Test
    void shouldAuthorizeNewFormulaPreviewAsCreateAtServiceBoundary() {
        RecordingActionPolicyService policyService = new RecordingActionPolicyService();
        DynamicRecordService service = serviceWithPolicy(operations(), formulaValidationEntity(), policyService);

        service.previewFormula(MODULE, "contract", service.newRecord(MODULE, "contract")
                .setValue("amount", BigDecimal.TEN));

        assertThat(policyService.context.platformAction()).isEqualTo(PlatformAction.CREATE);
    }

    @Test
    void shouldAuthorizeExistingFormulaPreviewAsUpdateAtServiceBoundary() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(Map.of(
                "id", "contract-1", "amount", BigDecimal.ONE, "deleted", Boolean.FALSE, "version", 1
        )));
        RecordingActionPolicyService policyService = new RecordingActionPolicyService();
        DynamicRecordService service = serviceWithPolicy(operations, formulaValidationEntity(), policyService);
        DynamicRecord record = service.newRecord(MODULE, "contract").setValue("amount", BigDecimal.TEN);
        record.setId("contract-1");

        service.previewFormula(MODULE, "contract", record);

        assertThat(policyService.context.platformAction()).isEqualTo(PlatformAction.UPDATE);
    }

    @Test
    void shouldRejectExistingFormulaPreviewWhenUpdateIsUnauthorized() {
        ActionExecutionPolicyService policyService = context -> {
            if (context.platformAction() == PlatformAction.UPDATE) {
                throw new PlatformException("update denied");
            }
        };
        DynamicRecordService service = serviceWithPolicy(operations(), formulaValidationEntity(), policyService);
        DynamicRecord record = service.newRecord(MODULE, "contract").setValue("amount", BigDecimal.TEN);
        record.setId("contract-1");

        assertThatThrownBy(() -> service.previewFormula(MODULE, "contract", record))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("update denied");
    }

    @Test
    void shouldApplyDataScopeToDynamicSortMutations() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = invocation.getArgument(1);
            return params.containsValue("visible") && !params.containsValue("hidden")
                    ? List.of(sortableRow("visible", 1000))
                    : List.of();
        });
        DynamicRecordService service = service(operations, dataScopedSortableEntity(),
                RuntimeEventPublisher.noop(), new VisibleOnlyDataScopeCriteriaService());

        assertThatThrownBy(() -> service.reorder(MODULE, "contract", List.of("visible", "hidden")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("record data permission denied");
        assertThatThrownBy(() -> service.moveBefore(MODULE, "contract", "hidden", "visible"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("record data permission denied");
        verify(operations, never()).patchUpdateItemWhere(anyString(), anyString(), anyMap(), anyMap(), anyString());
    }

    @Test
    void shouldApplyDataScopeToDynamicTreeSortMutations() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = invocation.getArgument(1);
            return params.containsValue("visible") && !params.containsValue("hidden")
                    ? List.of(treeRow("visible", "root", 1000))
                    : List.of();
        });
        DynamicRecordService service = service(operations, dataScopedTreeEntity(),
                RuntimeEventPublisher.noop(), new VisibleOnlyDataScopeCriteriaService());

        assertThatThrownBy(() -> service.moveInTree(MODULE, "contract", "hidden", "visible", null, "root"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("record data permission denied");
        verify(operations, never()).patchUpdateItemWhere(anyString(), anyString(), anyMap(), anyMap(), anyString());

        service.moveInTree(MODULE, "contract", "visible", null, null, "root");
        verify(operations).patchUpdateItemWhere(eq(SCHEMA), eq("app_contract"), anyMap(), anyMap(), eq("id"));
    }

    @Test
    void shouldApplyFullSortScopeDataScopeToStandardSortAction() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = invocation.getArgument(1);
            if (params.containsValue("hidden")) {
                return List.of(sortableRow("visible", 1000), sortableRow("target", 2000));
            }
            if (params.containsValue("visible")) {
                return List.of(sortableRow("visible", 1000));
            }
            if (params.containsValue("target")) {
                return List.of(sortableRow("target", 2000));
            }
            return List.of(sortableRow("visible", 1000), sortableRow("target", 2000), sortableRow("hidden", 3000));
        });
        DynamicRecordService service = service(operations, dataScopedSortableEntity(),
                RuntimeEventPublisher.noop(), new AllowIdsDataScopeCriteriaService("visible", "target"));

        assertThatThrownBy(() -> service.module(MODULE)
                .executeAction("sort", DynamicActionExecutionRequest.id("visible").withBeforeId("target")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("record data permission denied");

        verify(operations, never()).patchUpdateItemWhere(anyString(), anyString(), anyMap(), anyMap(), anyString());
    }

    @Test
    void shouldApplyFullSortScopeDataScopeWhenCrossTenantRoleAllowsBypass() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = invocation.getArgument(1);
            if (params.containsValue("hidden")) {
                return List.of(sortableRow("visible", 1000), sortableRow("target", 2000));
            }
            if (params.containsValue("visible")) {
                return List.of(sortableRow("visible", 1000));
            }
            if (params.containsValue("target")) {
                return List.of(sortableRow("target", 2000));
            }
            return List.of(sortableRow("visible", 1000), sortableRow("target", 2000), sortableRow("hidden", 3000));
        });
        DynamicRecordService service = service(operations, dataScopedSortableEntity(),
                RuntimeEventPublisher.noop(), new CrossTenantAllowIdsDataScopeCriteriaService("visible", "target"));

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            assertThatThrownBy(() -> service.module(MODULE)
                    .executeAction("sort", DynamicActionExecutionRequest.id("visible").withBeforeId("target")))
                    .isInstanceOf(PlatformException.class)
                    .hasMessageContaining("record data permission denied");
            assertThat(TenantContext.currentTenantId()).contains("tenant-a");
            assertThat(TenantContext.tenantFilterBypassed()).isFalse();
        }

        verify(operations, never()).patchUpdateItemWhere(anyString(), anyString(), anyMap(), anyMap(), anyString());
    }

    @Test
    void shouldFilterTreeDescendantIdsByDataScope() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> params = invocation.getArgument(1);
            if (sql.contains("parent_id") && params.containsValue("parent")) {
                return List.of(treeRow("visible", "parent", 1000), treeRow("hidden", "parent", 2000));
            }
            if (sql.contains("parent_id") && (params.containsValue("visible") || params.containsValue("hidden"))) {
                return List.of();
            }
            if (params.containsValue("visible") && params.containsValue("hidden")) {
                return List.of(treeRow("visible", "parent", 1000));
            }
            if (params.containsValue("parent")) {
                return List.of(treeRow("parent", "root", 1000));
            }
            if (params.containsValue("visible")) {
                return List.of(treeRow("visible", "parent", 1000));
            }
            if (params.containsValue("hidden")) {
                return List.of(treeRow("hidden", "parent", 2000));
            }
            return List.of();
        });
        DynamicRecordService service = service(operations, dataScopedTreeEntity(),
                RuntimeEventPublisher.noop(), new AllowIdsDataScopeCriteriaService("parent", "visible"));

        assertThat(service.descendantIds(MODULE, "contract", "parent")).containsExactly("visible");
    }

    @Test
    void shouldApplyDataScopeToDynamicEnableMutations() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = invocation.getArgument(1);
            return params.containsValue("visible") && !params.containsValue("hidden")
                    ? List.of(enabledRow("visible", false))
                    : List.of();
        });
        DynamicRecordService service = service(operations, dataScopedEnabledEntity(),
                RuntimeEventPublisher.noop(), new VisibleOnlyDataScopeCriteriaService());

        assertThatThrownBy(() -> service.enable(MODULE, "contract", "hidden"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("record data permission denied");

        assertThat(service.enable(MODULE, "contract", "visible")).isEqualTo(1);
        verify(operations).patchUpdateItemWhere(eq(SCHEMA), eq("app_contract"), anyMap(), anyMap(), eq("id"));
    }

    @Test
    void shouldBlockActionExecutionWhenAvailabilityFormulaFails() {
        CollectingRuntimeEventPublisher events = new CollectingRuntimeEventPublisher();
        DynamicRecordService service = actionService(operations(), events);
        DynamicRecord submitted = service.newRecord(MODULE, "contract")
                .setValue("code", "C-001")
                .setValue("status", "submitted");

        assertThatThrownBy(() -> service.module(MODULE)
                .executeAction("submit", DynamicActionExecutionRequest.record(submitted)))
                .isInstanceOf(DynamicActionExecutionException.class)
                .hasMessageContaining("只有草稿合同可以提交")
                .satisfies(error -> {
                    DynamicActionExecutionException exception = (DynamicActionExecutionException) error;
                    assertThat(exception.failureStage()).isEqualTo(DynamicActionExecutionException.STAGE_AVAILABILITY);
                    assertThat(exception.context().availability().available()).isFalse();
                    assertThat(exception.context().action().availabilityCondition()).isTrue();
                });
        assertThat(events.events()).singleElement()
                .satisfies(event -> {
                    assertThat(event.eventType()).isEqualTo(RuntimeEventType.ACTION_FAILED);
                    assertThat(event.actionCode()).isEqualTo("submit");
                    assertThat(event.payload()).containsEntry("executorType", "SERVICE")
                            .containsEntry("actionLevel", "ANY")
                            .containsEntry("available", false)
                            .containsEntry("failureStage", "availability")
                            .containsEntry("errorMessage", "只有草稿合同可以提交");
                });
    }

    @Test
    void shouldRejectServiceActionExecutionWhenExecutorKeyIsMissing() {
        CollectingRuntimeEventPublisher events = new CollectingRuntimeEventPublisher();
        DynamicRecordService service = actionService(operations(), events);
        DynamicRecord draft = service.newRecord(MODULE, "contract")
                .setValue("code", "C-001")
                .setValue("status", "draft");

        assertThatThrownBy(() -> service.entity(MODULE, "contract")
                .executeAction("submit", DynamicActionExecutionRequest.record(draft)))
                .isInstanceOf(DynamicActionExecutionException.class)
                .hasMessageContaining("dynamic action executorKey must not be blank")
                .satisfies(error -> {
                    DynamicActionExecutionException exception = (DynamicActionExecutionException) error;
                    assertThat(exception.failureStage()).isEqualTo(DynamicActionExecutionException.STAGE_EXECUTE);
                    assertThat(exception.context().availability().available()).isTrue();
                    assertThat(exception.context().action().executorType())
                            .isEqualTo(net.ximatai.muyun.spring.dynamic.metadata.EntityActionExecutorType.SERVICE);
                });
        assertThat(events.events()).singleElement()
                .satisfies(event -> {
                    assertThat(event.eventType()).isEqualTo(RuntimeEventType.ACTION_FAILED);
                    assertThat(event.payload()).containsEntry("failureStage", "execute")
                            .containsEntry("actionLevel", "ANY")
                            .containsEntry("errorType", IllegalArgumentException.class.getName());
                });
    }

    @Test
    void shouldExecuteRegisteredServiceActionThroughStableActionApi() {
        RecordingActionExecutor executor = new RecordingActionExecutor();
        CollectingRuntimeEventPublisher events = new CollectingRuntimeEventPublisher();
        DynamicRecordService service = actionService(operations(), events, executor);
        DynamicRecord draft = service.newRecord(MODULE, "contract")
                .setValue("code", "C-001")
                .setValue("status", "draft");
        draft.setId("contract-1");

        DynamicActionExecutionResult result = service.entity(MODULE, "contract")
                .executeAction("submit", DynamicActionExecutionRequest.record(draft));

        assertThat(result.value()).isEqualTo("submitted:contract-1");
        assertThat(result.body().type()).isEqualTo(DynamicActionResultType.VALUE);
        assertThat(executor.context().moduleAlias()).isEqualTo(MODULE);
        assertThat(executor.context().entityAlias()).isEqualTo("contract");
        assertThat(executor.context().actionCode()).isEqualTo("submit");
        assertThat(executor.context().availability().available()).isTrue();
        assertThat(executor.request().record()).isSameAs(draft);
        assertThat(events.events()).singleElement()
                .satisfies(event -> {
                    assertThat(event.eventType()).isEqualTo(RuntimeEventType.ACTION_EXECUTED);
                    assertThat(event.recordId()).isEqualTo("contract-1");
                    assertThat(event.actionCode()).isEqualTo("submit");
                    assertThat(event.traceId()).isEqualTo(result.context().traceId());
                    assertThat(event.payload()).containsEntry("executorType", "SERVICE")
                            .containsEntry("actionLevel", "RECORD")
                            .containsEntry("resultType", "VALUE")
                            .containsEntry("result", "submitted:contract-1");
                });
    }

    @Test
    void shouldExecuteChildEntityActionThroughModuleActionApi() {
        RecordingActionExecutor executor = new RecordingActionExecutor();
        DynamicRecordService service = childEntityActionService(executor);
        DynamicRecord line = service.newRecord(MODULE, "line");
        line.setId("line-1");

        DynamicActionExecutionResult result = service.module(MODULE)
                .executeAction("approveLine", DynamicActionExecutionRequest.record(line));

        assertThat(service.actions(MODULE)).extracting(DynamicActionDescriptor::code)
                .contains("approveLine");
        assertThat(service.actionEntityAlias(MODULE, "approveLine")).isEqualTo("line");
        assertThat(result.value()).isEqualTo("submitted:line-1");
        assertThat(executor.context().entityAlias()).isEqualTo("line");
        assertThat(executor.context().actionCode()).isEqualTo("approveLine");
    }

    @Test
    void shouldResolveStandardModuleActionToExplicitMainEntityWhenChildEntityComesFirst() {
        ModuleDefinition module = ModuleDefinition.builder(MODULE, "Contract")
                .entities(List.of(lineEntity().withCapabilities(EntityCapability.CRUD),
                        actionEntity().withCapabilities(EntityCapability.CRUD)))
                .mainEntityAlias("contract")
                .build();
        DynamicRecordService service = new DynamicRecordService(new DynamicRecordRuntime(operations()).register(module));

        assertThat(service.actionEntityAlias(MODULE, "create")).isEqualTo("contract");
    }


    @Test
    void shouldExposeTenantContextToServiceActionAndActionEvent() {
        RecordingActionExecutor executor = new RecordingActionExecutor();
        CollectingRuntimeEventPublisher events = new CollectingRuntimeEventPublisher();
        DynamicRecordService service = actionService(operations(), events, executor);
        DynamicRecord draft = service.newRecord(MODULE, "contract")
                .setValue("code", "C-001")
                .setValue("status", "draft");
        draft.setId("contract-1");

        DynamicActionExecutionResult result;
        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            result = service.entity(MODULE, "contract")
                    .executeAction("submit", DynamicActionExecutionRequest.record(draft));
        }

        assertThat(result.context().tenantId()).isEqualTo("tenant-a");
        assertThat(result.context().systemContext()).isFalse();
        assertThat(executor.context().tenantId()).isEqualTo("tenant-a");
        assertThat(events.events()).singleElement()
                .satisfies(event -> {
                    assertThat(event.tenantId()).isEqualTo("tenant-a");
                    assertThat(event.systemContext()).isFalse();
                    assertThat(event.traceId()).isEqualTo(result.context().traceId());
                });
    }

    @Test
    void shouldExposeSystemContextToServiceActionAndActionEvent() {
        RecordingActionExecutor executor = new RecordingActionExecutor();
        CollectingRuntimeEventPublisher events = new CollectingRuntimeEventPublisher();
        DynamicRecordService service = actionService(operations(), events, executor);
        DynamicRecord draft = service.newRecord(MODULE, "contract")
                .setValue("code", "C-001")
                .setValue("status", "draft");
        draft.setId("contract-1");

        DynamicActionExecutionResult result;
        try (TenantContext.Scope ignored = TenantContext.system("system action replay")) {
            result = service.entity(MODULE, "contract")
                    .executeAction("submit", DynamicActionExecutionRequest.record(draft));
        }

        assertThat(result.context().tenantId()).isNull();
        assertThat(result.context().systemContext()).isTrue();
        assertThat(result.context().systemReason()).isEqualTo("system action replay");
        assertThat(executor.context().systemContext()).isTrue();
        assertThat(executor.context().systemReason()).isEqualTo("system action replay");
        assertThat(events.events()).singleElement()
                .satisfies(event -> {
                    assertThat(event.tenantId()).isNull();
                    assertThat(event.systemContext()).isTrue();
                    assertThat(event.traceId()).isEqualTo(result.context().traceId());
                    assertThat(event.payload()).containsEntry("systemReason", "system action replay");
                });
    }

    @Test
    void shouldExecuteDialogActionAsFrontendInteractionResult() {
        CollectingRuntimeEventPublisher events = new CollectingRuntimeEventPublisher();
        DynamicRecordService service = actionService(operations(), events, null,
                new EntityActionDefinition("contract", "submitDialog", "提交合同", true, EntityActionLevel.RECORD,
                        EntityActionCategory.DIALOG, null, null, null, null,
                        null, null, null, "contractSubmitDialog"
                ));

        DynamicActionExecutionResult result = service.entity(MODULE, "contract")
                .executeAction("submitDialog", DynamicActionExecutionRequest.id("contract-1"));

        assertThat(result.body().type()).isEqualTo(DynamicActionResultType.DIALOG);
        assertThat(result.value()).isInstanceOf(DynamicActionDialog.class);
        assertThat((DynamicActionDialog) result.value())
                .extracting(DynamicActionDialog::dialogKey, DynamicActionDialog::title)
                .containsExactly("contractSubmitDialog", "提交合同");
        assertThat((DynamicActionDialog) result.value())
                .extracting(DynamicActionDialog::actionCode, DynamicActionDialog::submitActionCode,
                        DynamicActionDialog::submitPath)
                .containsExactly("submitDialog", null, null);
        assertThat(result.body().refresh()).isFalse();
        assertThat(((DynamicActionDialog) result.value()).refreshStrategy().active()).isFalse();
        assertThat(result.context().action().executorType()).isEqualTo(EntityActionExecutorType.DIALOG);
        assertThat(events.events()).singleElement()
                .satisfies(event -> {
                    assertThat(event.eventType()).isEqualTo(RuntimeEventType.ACTION_EXECUTED);
                    assertThat(event.recordId()).isEqualTo("contract-1");
                    assertThat(event.actionCode()).isEqualTo("submitDialog");
                    assertThat(event.payload()).containsEntry("executorType", "DIALOG")
                            .containsEntry("actionLevel", "RECORD")
                            .containsEntry("resultType", "DIALOG")
                            .containsEntry("interactionOnly", true);
                    assertThat(event.payload()).doesNotContainKey("result");
                });
    }

    @Test
    void shouldExposeDialogSubmitProtocolWhenExecutorKeyBindsSubmitAction() {
        CollectingRuntimeEventPublisher events = new CollectingRuntimeEventPublisher();
        DynamicRecordService service = actionServiceWithActions(operations(), events,
                submitActionWithExecutorKey("contractSubmit"),
                new EntityActionDefinition("contract", "submitDialog", "提交合同", true, EntityActionLevel.RECORD,
                        EntityActionCategory.DIALOG, null, null, null, null,
                        null, null, null, "contractSubmitDialog#submit"
                ));

        DynamicActionExecutionResult result = service.entity(MODULE, "contract")
                .executeAction("submitDialog", DynamicActionExecutionRequest.id("contract-1"));

        assertThat(result.body().type()).isEqualTo(DynamicActionResultType.DIALOG);
        assertThat((DynamicActionDialog) result.value())
                .extracting(DynamicActionDialog::dialogKey, DynamicActionDialog::actionCode,
                        DynamicActionDialog::submitActionCode, DynamicActionDialog::submitPath,
                        DynamicActionDialog::recordId, DynamicActionDialog::refreshOnSuccess)
                .containsExactly("contractSubmitDialog", "submitDialog", "submit",
                        "/" + MODULE + "/submit/contract-1", "contract-1", true);
        assertThat(((DynamicActionDialog) result.value()).refreshStrategy())
                .extracting(DynamicActionRefreshStrategy::list, DynamicActionRefreshStrategy::detail,
                        DynamicActionRefreshStrategy::redirectToDetail)
                .containsExactly(true, true, false);
        assertThat(events.events()).singleElement()
                .satisfies(event -> {
                    assertThat(event.payload()).containsEntry("refresh", true);
                    assertThat(event.payload().get("refreshStrategy"))
                            .isEqualTo(((DynamicActionDialog) result.value()).refreshStrategy());
                });
    }

    @Test
    void shouldRejectDialogSubmitActionWhenTargetActionIsMissing() {
        DynamicRecordService service = actionService(operations(), RuntimeEventPublisher.noop(), null,
                new EntityActionDefinition("contract", "submitDialog", "提交合同", true, EntityActionLevel.RECORD,
                        EntityActionCategory.DIALOG, null, null, null, null,
                        null, null, null, "contractSubmitDialog#missing"
                ));

        assertThatThrownBy(() -> service.entity(MODULE, "contract")
                .executeAction("submitDialog", DynamicActionExecutionRequest.id("contract-1")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("unknown dynamic action");
    }

    @Test
    void shouldOpenDialogActionWithoutBeforeExecuteRules() {
        CollectingRuntimeEventPublisher events = new CollectingRuntimeEventPublisher();
        DynamicRecordService service = actionRuleService(operations(), events, null,
                new EntityActionDefinition("contract", "submitDialog", "提交合同", true, EntityActionLevel.RECORD,
                        EntityActionCategory.DIALOG, null, null, null, null,
                        null, null, EntityActionExecutorType.DIALOG, "contractSubmitDialog"
                ));
        DynamicRecord draft = service.newRecord(MODULE, "contract")
                .setValue("code", "C-001")
                .setValue("status", "draft")
                .setValue("amount", BigDecimal.ZERO);
        draft.setId("contract-1");

        DynamicActionExecutionResult result = service.entity(MODULE, "contract")
                .executeAction("submitDialog", DynamicActionExecutionRequest.record(draft));

        assertThat(result.body().type()).isEqualTo(DynamicActionResultType.DIALOG);
        assertThat(events.events()).singleElement()
                .satisfies(event -> assertThat(event.payload())
                        .containsEntry("executorType", "DIALOG")
                        .containsEntry("resultType", "DIALOG")
                        .containsEntry("interactionOnly", true));
    }

    @Test
    void shouldExposeCurrentEntityOperationsToServiceActionWithSameTrace() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(actionRow("contract-1", "C-001", "draft")));
        when(operations.patchUpdateItemWhere(anyString(), anyString(), anyMap(), anyMap(), anyString())).thenReturn(1);
        CollectingRuntimeEventPublisher events = new CollectingRuntimeEventPublisher();
        DynamicRecordService service = actionService(operations, events, new WritingActionExecutor());
        DynamicRecord draft = service.newRecord(MODULE, "contract")
                .setValue("code", "C-001")
                .setValue("status", "draft");
        draft.setId("contract-1");

        DynamicActionExecutionResult result = service.entity(MODULE, "contract")
                .executeAction("submit", DynamicActionExecutionRequest.record(draft));

        assertThat(result.body().type()).isEqualTo(DynamicActionResultType.COUNT);
        assertThat(result.value()).isEqualTo(1);
        ArgumentCaptor<Map<String, Object>> body = mapCaptor();
        verify(operations).patchUpdateItemWhere(eq(SCHEMA), eq("app_contract"), body.capture(), anyMap(), eq("id"));
        assertThat(body.getValue()).containsEntry("status", "submitted");
        assertThat(events.events()).extracting(RuntimeEvent::eventType)
                .containsExactly(RuntimeEventType.AFTER_UPDATE, RuntimeEventType.ACTION_EXECUTED);
        assertActionTrace(events.events().get(0), events.events().get(1), RuntimeEventType.AFTER_UPDATE, "submit");
        assertThat(events.events().get(1).traceId()).isEqualTo(result.context().traceId());
    }

    @Test
    void shouldExecuteServiceActionInsideConfiguredTransactionOperator() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(actionRow("contract-1", "C-001", "draft")));
        when(operations.patchUpdateItemWhere(anyString(), anyString(), anyMap(), anyMap(), anyString())).thenReturn(1);
        RecordingActionTransactionOperator transactionOperator = new RecordingActionTransactionOperator();
        DynamicRecordService service = actionService(operations, RuntimeEventPublisher.noop(), new WritingActionExecutor(),
                submitActionWithExecutorKey("contractSubmit"), transactionOperator);
        DynamicRecord draft = service.newRecord(MODULE, "contract")
                .setValue("code", "C-001")
                .setValue("status", "draft");
        draft.setId("contract-1");

        DynamicActionExecutionResult result = service.entity(MODULE, "contract")
                .executeAction("submit", DynamicActionExecutionRequest.record(draft));

        assertThat(result.value()).isEqualTo(1);
        assertThat(transactionOperator.calls()).isEqualTo(1);
        assertThat(transactionOperator.committed()).isEqualTo(1);
        assertThat(transactionOperator.rolledBack()).isZero();
    }

    @Test
    void shouldRollbackActionTransactionAndPublishFailureEventWhenExecutorFails() {
        RecordingActionTransactionOperator transactionOperator = new RecordingActionTransactionOperator();
        CollectingRuntimeEventPublisher events = new CollectingRuntimeEventPublisher();
        DynamicRecordService service = actionService(operations(), events, new FailingActionExecutor(),
                submitActionWithExecutorKey("contractSubmit"), transactionOperator);
        DynamicRecord draft = service.newRecord(MODULE, "contract")
                .setValue("code", "C-001")
                .setValue("status", "draft");
        draft.setId("contract-1");

        assertThatThrownBy(() -> service.entity(MODULE, "contract")
                .executeAction("submit", DynamicActionExecutionRequest.record(draft)))
                .isInstanceOf(DynamicActionExecutionException.class);

        assertThat(transactionOperator.calls()).isEqualTo(1);
        assertThat(transactionOperator.committed()).isZero();
        assertThat(transactionOperator.rolledBack()).isEqualTo(1);
        assertThat(events.events()).singleElement()
                .satisfies(event -> {
                    assertThat(event.eventType()).isEqualTo(RuntimeEventType.ACTION_FAILED);
                    assertThat(event.actionCode()).isEqualTo("submit");
                    assertThat(event.payload()).containsEntry("failureStage", "execute");
                });
    }

    @Test
    void shouldPassPayloadToServiceActionAndExposeStructuredResultBody() {
        PayloadActionExecutor executor = new PayloadActionExecutor();
        CollectingRuntimeEventPublisher events = new CollectingRuntimeEventPublisher();
        DynamicRecordService service = actionService(operations(), events, executor);
        DynamicRecord draft = service.newRecord(MODULE, "contract")
                .setValue("code", "C-001")
                .setValue("status", "draft");
        draft.setId("contract-1");

        DynamicActionExecutionResult result = service.entity(MODULE, "contract")
                .executeAction("submit", DynamicActionExecutionRequest.record(draft)
                        .withPayloadValue("comment", "同意提交"));

        assertThat(executor.request().payload()).containsEntry("comment", "同意提交");
        assertThat(result.value()).isEqualTo("同意提交");
        assertThat(result.body().type()).isEqualTo(DynamicActionResultType.VALUE);
        assertThat(result.body().message()).isEqualTo("已提交");
        assertThat(result.body().refresh()).isTrue();
        assertThat(events.events()).singleElement()
                .satisfies(event -> assertThat(event.payload())
                        .containsEntry("executorType", "SERVICE")
                        .containsEntry("resultType", "VALUE")
                        .containsEntry("message", "已提交")
                        .containsEntry("refresh", true)
                        .containsEntry("result", "同意提交"));
    }

    @Test
    void shouldAllowNullPayloadValueAndKeepPayloadImmutable() {
        DynamicActionExecutionRequest request = DynamicActionExecutionRequest.empty()
                .withPayloadValue("comment", null);

        assertThat(request.payload()).containsEntry("comment", null);
        assertThatThrownBy(() -> request.payload().put("another", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldClassifyStructuredActionResultAsObject() {
        DynamicActionResultBody body = DynamicActionResultBody.of(Map.of("nextStatus", "submitted"));

        assertThat(body.type()).isEqualTo(DynamicActionResultType.OBJECT);
        assertThat(body.value()).isEqualTo(Map.of("nextStatus", "submitted"));
    }

    @Test
    void shouldBuildCommonActionResultBodiesWithBusinessSemantics() {
        DynamicActionResultBody recordId = DynamicActionResultBody.createdRecordId("contract-1");
        DynamicActionResultBody changedCount = DynamicActionResultBody.changedCount(2);
        DynamicActionResultBody changedCountWithMessage = DynamicActionResultBody.changedCount(2, "已归档 2 条");
        DynamicActionResultBody unchangedCount = DynamicActionResultBody.changedCount(0);
        DynamicActionResultBody notice = DynamicActionResultBody.notice("无需刷新");
        DynamicActionResultBody refresh = DynamicActionResultBody.refreshed();
        DynamicActionResultBody refreshedNotice = DynamicActionResultBody.refreshedNotice("已提交");
        DynamicActionResultBody redirect = DynamicActionResultBody.redirect("/contracts/contract-1", "已创建");
        DynamicActionResultBody dialog = DynamicActionResultBody.dialog("contractSubmitDialog", "提交合同");

        assertThat(recordId.type()).isEqualTo(DynamicActionResultType.RECORD_ID);
        assertThat(recordId.value()).isEqualTo("contract-1");
        assertThat(recordId.refresh()).isTrue();
        assertThat(recordId.refreshStrategy().detail()).isTrue();
        assertThat(changedCount.type()).isEqualTo(DynamicActionResultType.COUNT);
        assertThat(changedCount.value()).isEqualTo(2);
        assertThat(changedCount.refresh()).isTrue();
        assertThat(changedCount.refreshStrategy().list()).isTrue();
        assertThat(changedCount.refreshStrategy().detail()).isTrue();
        assertThat(changedCountWithMessage.message()).isEqualTo("已归档 2 条");
        assertThat(unchangedCount.refresh()).isFalse();
        assertThat(notice.type()).isEqualTo(DynamicActionResultType.NONE);
        assertThat(notice.refresh()).isFalse();
        assertThat(notice.message()).isEqualTo("无需刷新");
        assertThat(refresh.type()).isEqualTo(DynamicActionResultType.NONE);
        assertThat(refresh.refresh()).isTrue();
        assertThat(refresh.refreshStrategy().list()).isTrue();
        assertThat(refresh.refreshStrategy().detail()).isTrue();
        assertThat(refreshedNotice.refresh()).isTrue();
        assertThat(refreshedNotice.message()).isEqualTo("已提交");
        assertThat(redirect.type()).isEqualTo(DynamicActionResultType.NONE);
        assertThat(redirect.redirectTo()).isEqualTo("/contracts/contract-1");
        assertThat(redirect.message()).isEqualTo("已创建");
        assertThat(dialog.type()).isEqualTo(DynamicActionResultType.DIALOG);
        assertThat((DynamicActionDialog) dialog.value())
                .extracting(DynamicActionDialog::dialogKey, DynamicActionDialog::title)
                .containsExactly("contractSubmitDialog", "提交合同");
        assertThat(((DynamicActionDialog) dialog.value()).refreshOnSuccess()).isFalse();
        assertThat(dialog.refresh()).isFalse();

        DynamicActionResultBody redirectToDetail = DynamicActionResultBody.none()
                .withRefreshStrategy(DynamicActionRefreshStrategy.redirectToDetail("contract-2", "crm.contract"));
        assertThat(redirectToDetail.refresh()).isTrue();
        assertThat(redirectToDetail.refreshStrategy())
                .extracting(DynamicActionRefreshStrategy::redirectToDetail,
                        DynamicActionRefreshStrategy::redirectRecordId,
                        DynamicActionRefreshStrategy::redirectModuleAlias)
                .containsExactly(true, "contract-2", "crm.contract");
    }

    @Test
    void shouldBlockServiceActionWhenBeforeExecuteRuleFails() {
        RecordingActionExecutor executor = new RecordingActionExecutor();
        CollectingRuntimeEventPublisher events = new CollectingRuntimeEventPublisher();
        DynamicRecordService service = actionRuleService(operations(), events, executor);
        DynamicRecord draft = service.newRecord(MODULE, "contract")
                .setValue("code", "C-001")
                .setValue("status", "draft")
                .setValue("amount", BigDecimal.ZERO);
        draft.setId("contract-1");

        assertThatThrownBy(() -> service.entity(MODULE, "contract")
                .executeAction("submit", DynamicActionExecutionRequest.record(draft)))
                .isInstanceOf(DynamicActionExecutionException.class)
                .hasCauseInstanceOf(DynamicFormulaException.class)
                .hasMessageContaining("提交金额必须大于0")
                .satisfies(error -> {
                    DynamicActionExecutionException exception = (DynamicActionExecutionException) error;
                    assertThat(exception.failureStage()).isEqualTo(DynamicActionExecutionException.STAGE_BEFORE_EXECUTE_RULE);
                    assertThat(exception.context().availability().available()).isTrue();
                    assertThat(exception.context().traceId()).isNotBlank();
                });
        assertThat(executor.context()).isNull();
        assertThat(events.events()).singleElement()
                .satisfies(event -> {
                    assertThat(event.eventType()).isEqualTo(RuntimeEventType.ACTION_FAILED);
                    assertThat(event.actionCode()).isEqualTo("submit");
                    assertThat(event.payload()).containsEntry("executorType", "SERVICE")
                            .containsEntry("available", true)
                            .containsEntry("failureStage", "beforeExecuteRule")
                            .containsEntry("errorType", DynamicFormulaException.class.getName());
                    assertThat(String.valueOf(event.payload().get("errorMessage"))).contains("提交金额必须大于0");
                });
    }

    @Test
    void shouldExecuteServiceActionWhenBeforeExecuteRulePasses() {
        RecordingActionExecutor executor = new RecordingActionExecutor();
        CollectingRuntimeEventPublisher events = new CollectingRuntimeEventPublisher();
        DynamicRecordService service = actionRuleService(operations(), events, executor);
        DynamicRecord draft = service.newRecord(MODULE, "contract")
                .setValue("code", "C-001")
                .setValue("status", "draft")
                .setValue("amount", BigDecimal.ONE);
        draft.setId("contract-1");

        DynamicActionExecutionResult result = service.entity(MODULE, "contract")
                .executeAction("submit", DynamicActionExecutionRequest.record(draft));

        assertThat(result.value()).isEqualTo("submitted:contract-1");
        assertThat(events.events()).singleElement()
                .satisfies(event -> {
                    assertThat(event.eventType()).isEqualTo(RuntimeEventType.ACTION_EXECUTED);
                    assertThat(event.traceId()).isEqualTo(result.context().traceId());
                });
    }

    @Test
    void shouldLoadExistingRecordForBeforeExecuteRuleWhenRequestOnlyHasRecordId() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap()))
                .thenReturn(List.of(actionAmountRow("contract-1", "C-001", "draft", BigDecimal.ONE)));
        IdReturningActionExecutor executor = new IdReturningActionExecutor();
        DynamicRecordService service = actionRuleService(operations, RuntimeEventPublisher.noop(), executor);

        DynamicActionExecutionResult result = service.entity(MODULE, "contract")
                .executeAction("submit", DynamicActionExecutionRequest.id("contract-1"));

        assertThat(result.value()).isEqualTo("submitted:contract-1");
        assertThat(executor.context().recordId()).isEqualTo("contract-1");
        verify(operations, atLeastOnce()).query(anyString(), anyMap());
    }

    @Test
    void shouldMergeExistingMainFieldsAndSubmittedChildrenForBeforeExecuteRule() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap()))
                .thenReturn(List.of(actionAmountRow("contract-1", "C-001", "draft", BigDecimal.ONE)));
        IdReturningActionExecutor executor = new IdReturningActionExecutor();
        DynamicRecordService service = childActionRuleService(operations, RuntimeEventPublisher.noop(), executor, "C-001");
        DynamicRecord line = service.newRecord(MODULE, "line")
                .setValue("summary", "明细已填写");
        DynamicRecord draft = service.newRecord(MODULE, "contract")
                .setChildren("lines", List.of(line));
        draft.setId("contract-1");

        DynamicActionExecutionResult result = service.entity(MODULE, "contract")
                .executeAction("submit", DynamicActionExecutionRequest.record(draft));

        assertThat(result.value()).isEqualTo("submitted:contract-1");
        assertThat(executor.context().recordId()).isEqualTo("contract-1");
        verify(operations, atLeastOnce()).query(anyString(), anyMap());
    }

    @Test
    void shouldPreferSubmittedMainFieldsOverExistingRecordForBeforeExecuteRule() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap()))
                .thenReturn(List.of(actionAmountRow("contract-1", "C-001", "draft", BigDecimal.ONE)));
        IdReturningActionExecutor executor = new IdReturningActionExecutor();
        DynamicRecordService service = childActionRuleService(operations, RuntimeEventPublisher.noop(), executor, "C-002");
        DynamicRecord line = service.newRecord(MODULE, "line")
                .setValue("summary", "明细已填写");
        DynamicRecord draft = service.newRecord(MODULE, "contract")
                .setValue("code", "C-002")
                .setChildren("lines", List.of(line));
        draft.setId("contract-1");

        DynamicActionExecutionResult result = service.entity(MODULE, "contract")
                .executeAction("submit", DynamicActionExecutionRequest.record(draft));

        assertThat(result.value()).isEqualTo("submitted:contract-1");
        assertThat(executor.context().recordId()).isEqualTo("contract-1");
        verify(operations, atLeastOnce()).query(anyString(), anyMap());
    }

    @Test
    void shouldNotLoadExistingRecordForChildrenPayloadWhenBeforeExecuteRuleIsNotConfigured() {
        IDatabaseOperations<Object> operations = operations();
        IdReturningActionExecutor executor = new IdReturningActionExecutor();
        DynamicRecordService service = childActionWithoutRuleService(operations, RuntimeEventPublisher.noop(), executor);
        DynamicRecord draft = service.newRecord(MODULE, "contract")
                .setChildren("lines", List.of(service.newRecord(MODULE, "line").setValue("summary", "明细已填写")));
        draft.setId("contract-1");

        DynamicActionExecutionResult result = service.entity(MODULE, "contract")
                .executeAction("submit", DynamicActionExecutionRequest.record(draft));

        assertThat(result.value()).isEqualTo("submitted:contract-1");
        verify(operations, never()).query(anyString(), anyMap());
    }

    @Test
    void shouldRejectServiceActionExecutionWhenExecutorIsNotRegistered() {
        DynamicRecordService service = actionService(operations(), RuntimeEventPublisher.noop(), null,
                submitActionWithExecutorKey("missingSubmitExecutor"));
        DynamicRecord draft = service.newRecord(MODULE, "contract")
                .setValue("code", "C-001")
                .setValue("status", "draft");

        assertThatThrownBy(() -> service.entity(MODULE, "contract")
                .executeAction("submit", DynamicActionExecutionRequest.record(draft)))
                .isInstanceOf(DynamicActionExecutionException.class)
                .hasMessageContaining("unknown dynamic action executor key: missingSubmitExecutor");
    }

    @Test
    void shouldKeepActionContextWhenServiceActionExecutorFails() {
        CollectingRuntimeEventPublisher events = new CollectingRuntimeEventPublisher();
        DynamicRecordService service = actionService(operations(), events, new FailingActionExecutor());
        DynamicRecord draft = service.newRecord(MODULE, "contract")
                .setValue("code", "C-001")
                .setValue("status", "draft");
        draft.setId("contract-1");

        assertThatThrownBy(() -> service.entity(MODULE, "contract")
                .executeAction("submit", DynamicActionExecutionRequest.record(draft)))
                .isInstanceOf(DynamicActionExecutionException.class)
                .hasCauseInstanceOf(IllegalStateException.class)
                .satisfies(error -> {
                    DynamicActionExecutionException exception = (DynamicActionExecutionException) error;
                    assertThat(exception.failureStage()).isEqualTo(DynamicActionExecutionException.STAGE_EXECUTE);
                    assertThat(exception.context().actionCode()).isEqualTo("submit");
                    assertThat(exception.context().recordId()).isEqualTo("contract-1");
                    assertThat(exception.context().traceId()).isNotBlank();
                });
        assertThat(events.events()).singleElement()
                .satisfies(event -> {
                    assertThat(event.eventType()).isEqualTo(RuntimeEventType.ACTION_FAILED);
                    assertThat(event.recordId()).isEqualTo("contract-1");
                    assertThat(event.actionCode()).isEqualTo("submit");
                    assertThat(event.payload()).containsEntry("executorType", "SERVICE")
                            .containsEntry("available", true)
                            .containsEntry("failureStage", "execute")
                            .containsEntry("errorMessage", "submit failed")
                            .containsEntry("errorType", IllegalStateException.class.getName());
                });
    }

    @Test
    void shouldPublishActionFailureImmediatelyWhenTransactionRollsBack() {
        RecordingActionExecutor executor = new RecordingActionExecutor();
        CollectingRuntimeEventPublisher events = new CollectingRuntimeEventPublisher();
        DynamicRecordService service = actionRuleService(operations(), events, executor);
        DynamicRecord draft = service.newRecord(MODULE, "contract")
                .setValue("code", "C-001")
                .setValue("status", "draft")
                .setValue("amount", BigDecimal.ZERO);
        draft.setId("contract-1");

        try {
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            assertThatThrownBy(() -> service.entity(MODULE, "contract")
                    .executeAction("submit", DynamicActionExecutionRequest.record(draft)))
                    .isInstanceOf(DynamicActionExecutionException.class);
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            assertThat(events.events()).singleElement()
                    .extracting(RuntimeEvent::eventType)
                    .isEqualTo(RuntimeEventType.ACTION_FAILED);
        } finally {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.clearSynchronization();
            }
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void shouldNotPublishActionFailureWhenSuccessEventPublisherFails() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.insertItem(eq(SCHEMA), eq("app_contract"), anyMap(), eq("id")))
                .thenAnswer(invocation -> invocation.<Map<String, Object>>getArgument(2).get("id"));
        ThrowingActionEventPublisher events = new ThrowingActionEventPublisher();
        DynamicRecordService service = actionService(operations, events);
        DynamicRecord record = service.newRecord(MODULE, "contract")
                .setValue("code", "C-001")
                .setValue("status", "draft");
        record.setId("contract-1");

        assertThatThrownBy(() -> service.module(MODULE)
                .executeAction("create", DynamicActionExecutionRequest.record(record)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("event publish failed");
        assertThat(events.eventTypes()).containsExactly(RuntimeEventType.AFTER_CREATE, RuntimeEventType.ACTION_EXECUTED);
    }

    @Test
    void shouldNotPublishActionFailureWhenSuccessEventFailsAfterActionTransactionCommits() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.insertItem(eq(SCHEMA), eq("app_contract"), anyMap(), eq("id")))
                .thenAnswer(invocation -> invocation.<Map<String, Object>>getArgument(2).get("id"));
        ThrowingActionEventPublisher events = new ThrowingActionEventPublisher();
        SpringLikeActionTransactionOperator transactionOperator = new SpringLikeActionTransactionOperator();
        DynamicRecordService service = actionService(operations, events, null,
                submitActionWithExecutorKey("contractSubmit"), transactionOperator);
        DynamicRecord record = service.newRecord(MODULE, "contract")
                .setValue("code", "C-001")
                .setValue("status", "draft");
        record.setId("contract-1");

        assertThatThrownBy(() -> service.module(MODULE)
                .executeAction("create", DynamicActionExecutionRequest.record(record)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("event publish failed");

        assertThat(transactionOperator.committed()).isEqualTo(1);
        assertThat(events.eventTypes()).containsExactly(RuntimeEventType.AFTER_CREATE, RuntimeEventType.ACTION_EXECUTED);
    }

    @Test
    void shouldNotPublishActionFailureWhenMutationEventFailsAfterActionTransactionCommits() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.insertItem(eq(SCHEMA), eq("app_contract"), anyMap(), eq("id")))
                .thenAnswer(invocation -> invocation.<Map<String, Object>>getArgument(2).get("id"));
        ThrowingMutationEventPublisher events = new ThrowingMutationEventPublisher();
        SpringLikeActionTransactionOperator transactionOperator = new SpringLikeActionTransactionOperator();
        DynamicRecordService service = actionService(operations, events, null,
                submitActionWithExecutorKey("contractSubmit"), transactionOperator);
        DynamicRecord record = service.newRecord(MODULE, "contract")
                .setValue("code", "C-001")
                .setValue("status", "draft");
        record.setId("contract-1");

        assertThatThrownBy(() -> service.module(MODULE)
                .executeAction("create", DynamicActionExecutionRequest.record(record)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mutation event publish failed");

        assertThat(transactionOperator.committed()).isEqualTo(1);
        assertThat(events.eventTypes()).containsExactly(RuntimeEventType.AFTER_CREATE);
    }

    @Test
    void shouldRejectDuplicateDynamicActionExecutorKey() {
        assertThatThrownBy(() -> new DynamicActionExecutorRegistry(List.of(
                new TestActionExecutor("contractSubmit"),
                new TestActionExecutor("contractSubmit")
        )))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate dynamic action executor key: contractSubmit");
    }

    @Test
    void shouldRegisterDynamicActionExecutorByMutatingRegistry() {
        DynamicActionExecutorRegistry registry = DynamicActionExecutorRegistry.empty();

        registry.register(new TestActionExecutor("contractSubmit"));

        assertThat(registry.contains("contractSubmit")).isTrue();
    }

    @Test
    void shouldRejectDynamicActionExecutorKeyWithSurroundingSpaces() {
        assertThatThrownBy(() -> new DynamicActionExecutorRegistry(List.of(new TestActionExecutor(" contractSubmit "))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not contain leading or trailing spaces");
    }

    @Test
    void shouldRejectDynamicActionExecutorWithMismatchedDefinitionKey() {
        assertThatThrownBy(() -> new DynamicActionExecutorRegistry(List.of(new MismatchedDefinitionActionExecutor())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("definition key must match executor key: contractSubmit");
    }

    @Test
    void shouldNotLoadExistingRecordForActionWithoutCondition() {
        IDatabaseOperations<Object> operations = operations();
        DynamicRecordService service = actionService(operations);
        DynamicRecord record = service.newRecord(MODULE, "contract")
                .setValue("code", "C-001");
        record.setId("contract-1");

        DynamicActionAvailability availability = service.entity(MODULE, "contract")
                .actionAvailability("create", record);

        assertThat(availability.available()).isTrue();
        verify(operations, never()).query(anyString(), anyMap());
    }

    @Test
    void shouldMergeExistingValuesWhenEvaluatingActionAvailability() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(actionRow("contract-1", "C-001", "draft")));
        DynamicRecordService service = actionService(operations);
        DynamicRecord partial = service.newRecord(MODULE, "contract");
        partial.setId("contract-1");

        assertThat(service.entity(MODULE, "contract").actionAvailability("submit", partial).available()).isTrue();
        verify(operations).query(anyString(), anyMap());
    }

    @Test
    void shouldExposeExplicitChildEntityActionInModuleActionAvailability() {
        ModuleDefinition module = ModuleDefinition.builder(MODULE, "Contract")
                .entities(List.of(actionEntity(), lineEntity()))
                .actions(List.of(
                        new EntityActionDefinition("contract", "submit", "提交", true)
                                .availableWhen("{status} == 'draft'"),
                        new EntityActionDefinition("line", "submitLine", "提交行", true)
                                .availableWhen("{summary} != ''")
                ))
                .mainEntityAlias("contract")
                .build();
        DynamicRecordService service = new DynamicRecordService(new DynamicRecordRuntime(operations()).register(module));
        DynamicRecord line = service.newRecord(MODULE, "line")
                .setValue("summary", "ok");

        assertThat(service.actionEntityAlias(MODULE, "submit")).isEqualTo("contract");
        assertThat(service.actionEntityAlias(MODULE, "submitLine")).isEqualTo("line");
        assertThat(service.actionAvailability(MODULE, "submitLine", line).available()).isTrue();
    }

    @Test
    void shouldExposeSortOperationsThroughStableServiceApi() {
        IDatabaseOperations<Object> operations = operations();
        stubSortableRows(operations);
        DynamicRecordService service = service(operations, sortableEntity());

        assertThat(service.sortedList(MODULE, "contract", Criteria.of()).stream().map(DynamicRecord::getId))
                .containsExactly("first", "second", "third");
        service.reorder(MODULE, "contract", List.of("first", "second", "third"));

        ArgumentCaptor<Map<String, Object>> body = mapCaptor();
        verify(operations, org.mockito.Mockito.times(3))
                .patchUpdateItemWhere(eq(SCHEMA), eq("app_contract"), body.capture(), anyMap(), eq("id"));
        assertThat(body.getAllValues()).allSatisfy(value -> assertThat(value).containsKey("sort_order"));
    }

    @Test
    void shouldExposeReferenceOperationsThroughStableServiceApi() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.row(anyString(), anyMap())).thenReturn(Map.of("total_count", 2));
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(
                referenceRow("contract-1", "Contract One"),
                referenceRow("contract-2", "Contract Two")
        ));
        DynamicRecordService service = service(operations, referenceEntity());

        assertThat(service.title(MODULE, "contract", "contract-1")).isEqualTo("Contract One");
        assertThat(service.titles(MODULE, "contract", List.of("contract-1", "contract-2")))
                .containsEntry("contract-1", "Contract One")
                .containsEntry("contract-2", "Contract Two");
        assertThat(service.projections(MODULE, "contract", List.of("contract-2", "contract-1"), List.of("code", "title")))
                .containsExactly(
                        Map.entry("contract-2", Map.of("code", "CONTRACT-2", "title", "Contract Two")),
                        Map.entry("contract-1", Map.of("code", "CONTRACT-1", "title", "Contract One"))
                );
        assertThat(service.referenceOptions(MODULE, "contract", Criteria.of(), PageRequest.of(1, 10)).getRecords())
                .containsExactly(
                        new ReferenceOption("contract-1", "Contract One"),
                        new ReferenceOption("contract-2", "Contract Two")
                );
    }

    @Test
    void shouldResolveDynamicReferenceQueryThroughStableServiceApi() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.row(anyString(), anyMap())).thenReturn(Map.of("total_count", 2));
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(
                referenceRow("contract-1", "Contract One"),
                referenceRow("contract-2", "Contract Two")
        ));
        DynamicRecordService service = referenceResolvingService(operations);

        DynamicReferenceResolveResponse response = service.resolveReference(
                MODULE,
                "line",
                "contractId",
                DynamicReferenceResolveRequest.query("Contract")
        );

        assertThat(response.status()).isEqualTo(DynamicReferenceResolveStatus.OK);
        assertThat(response.mode()).isEqualTo(DynamicReferenceResolveMode.QUERY);
        assertThat(response.total()).isEqualTo(2);
        assertThat(response.options()).extracting(DynamicReferenceResolveItem::id)
                .containsExactly("contract-1", "contract-2");
        assertThat(response.options().getFirst().title()).isEqualTo("Contract One");
        assertThat(response.options().getFirst().projections()).containsEntry("contractCode", "CONTRACT-1");
    }

    @Test
    void shouldApplyReferenceFiltersAndReturnAffectPatch() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.row(anyString(), anyMap())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = invocation.getArgument(1);
            return params.containsValue("north")
                    ? Map.of("total_count", 1)
                    : Map.of("total_count", 0);
        });
        when(operations.query(anyString(), anyMap())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> params = invocation.getArgument(1);
            assertThat(sql).contains("\"region\"");
            assertThat(params).containsValue("north");
            return List.of(referenceRow("contract-1", "Contract One", "north"));
        });
        EntityReferenceDefinition reference = EntityReferenceDefinition
                .to("line", "contractId", ReferenceTarget.of(MODULE, "contract"))
                .withProjection("title", "contractTitle")
                .withProjection("code", "contractCode")
                .withInteractionRules(
                        List.of(new EntityReferenceFilterDefinition("contractRegion", "region", DynamicQueryOperator.EQ)),
                        List.of(new EntityReferenceAffectDefinition("region", "contractRegion"))
                );
        DynamicRecordService service = referenceResolvingService(
                operations,
                referenceEntityWithRegion(),
                lineEntityWithRegion(),
                reference
        );

        DynamicReferenceResolveResponse response = service.resolveReference(
                MODULE,
                "line",
                "contractId",
                DynamicReferenceResolveRequest.query("Contract")
                        .withFormValues(Map.of("contractRegion", "north"))
        );

        assertThat(response.status()).isEqualTo(DynamicReferenceResolveStatus.OK);
        assertThat(response.options()).hasSize(1);
        assertThat(response.options().getFirst().affectPatch())
                .containsEntry("contractRegion", "north");
    }

    @Test
    void shouldTranslateDynamicReferenceValuesThroughStableServiceApi() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.row(anyString(), anyMap())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> params = invocation.getArgument(1);
            if (params.containsValue("contract-1") && sql.contains("\"id\"")) {
                return Map.of("total_count", 1);
            }
            if (params.containsValue("Contract Two") && sql.contains("\"title\"")) {
                return Map.of("total_count", 1);
            }
            return Map.of("total_count", 0);
        });
        when(operations.query(anyString(), anyMap())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> params = invocation.getArgument(1);
            if (params.containsValue("contract-1") && sql.contains("\"id\"")) {
                return List.of(referenceRow("contract-1", "Contract One"));
            }
            if (params.containsValue("Contract Two") && sql.contains("\"title\"")) {
                return List.of(referenceRow("contract-2", "Contract Two"));
            }
            return List.of();
        });
        DynamicRecordService service = referenceResolvingService(operations);

        DynamicReferenceResolveResponse response = service.resolveReference(
                MODULE,
                "line",
                "contractId",
                DynamicReferenceResolveRequest.translate(List.of("contract-1", "Contract Two", "missing"))
        );

        assertThat(response.status()).isEqualTo(DynamicReferenceResolveStatus.PARTIAL);
        assertThat(response.results()).extracting(DynamicReferenceResolveResult::status)
                .containsExactly(DynamicReferenceResolveStatus.RESOLVED,
                        DynamicReferenceResolveStatus.RESOLVED,
                        DynamicReferenceResolveStatus.NOT_FOUND);
        assertThat(response.results().getFirst().matchedBy()).isEqualTo(DynamicReferenceMatchMode.KEY);
        assertThat(response.results().get(1).matchedBy()).isEqualTo(DynamicReferenceMatchMode.LABEL);
        assertThat(response.results().getFirst().item().projections()).containsEntry("contractCode", "CONTRACT-1");
    }

    @Test
    void shouldOmitReferenceProjectionsWhenRequested() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.row(anyString(), anyMap())).thenReturn(Map.of("total_count", 1));
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(referenceRow("contract-1", "Contract One")));
        DynamicRecordService service = referenceResolvingService(operations);

        DynamicReferenceResolveResponse response = service.resolveReference(
                MODULE,
                "line",
                "contractId",
                DynamicReferenceResolveRequest.query("Contract").withoutProjections()
        );

        assertThat(response.options().getFirst().projections()).isEmpty();
    }

    @Test
    void shouldReportLabelAmbiguityWhenTranslatingReference() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.row(anyString(), anyMap())).thenReturn(Map.of("total_count", 2));
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(
                referenceRow("contract-1", "Contract"),
                referenceRow("contract-2", "Contract")
        ));
        DynamicRecordService service = referenceResolvingService(operations);

        DynamicReferenceResolveResponse response = service.resolveReference(
                MODULE,
                "line",
                "contractId",
                DynamicReferenceResolveRequest.translate(List.of("Contract"))
                        .withMatchMode(DynamicReferenceMatchMode.LABEL)
        );

        assertThat(response.status()).isEqualTo(DynamicReferenceResolveStatus.AMBIGUOUS);
        assertThat(response.results()).hasSize(1);
        assertThat(response.results().getFirst().status()).isEqualTo(DynamicReferenceResolveStatus.AMBIGUOUS);
        assertThat(response.results().getFirst().matchedBy()).isEqualTo(DynamicReferenceMatchMode.LABEL);
        assertThat(response.results().getFirst().candidates()).extracting(DynamicReferenceResolveItem::id)
                .containsExactly("contract-1", "contract-2");
    }

    @Test
    void shouldPreserveKeyAmbiguityWhenAutoTranslateWouldResolveByLabel() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.row(anyString(), anyMap())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> params = invocation.getArgument(1);
            if (params.containsValue("shared") && sql.contains("\"id\"")) {
                return Map.of("total_count", 2);
            }
            if (params.containsValue("shared") && sql.contains("\"title\"")) {
                return Map.of("total_count", 1);
            }
            return Map.of("total_count", 0);
        });
        when(operations.query(anyString(), anyMap())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            @SuppressWarnings("unchecked")
            Map<String, Object> params = invocation.getArgument(1);
            if (params.containsValue("shared") && sql.contains("\"id\"")) {
                return List.of(
                        referenceRow("shared", "First"),
                        referenceRow("shared", "Second")
                );
            }
            if (params.containsValue("shared") && sql.contains("\"title\"")) {
                return List.of(referenceRow("contract-3", "shared"));
            }
            return List.of();
        });
        DynamicRecordService service = referenceResolvingService(operations);

        DynamicReferenceResolveResponse response = service.resolveReference(
                MODULE,
                "line",
                "contractId",
                DynamicReferenceResolveRequest.translate(List.of("shared"))
        );

        assertThat(response.status()).isEqualTo(DynamicReferenceResolveStatus.AMBIGUOUS);
        assertThat(response.results().getFirst().matchedBy()).isEqualTo(DynamicReferenceMatchMode.KEY);
        assertThat(response.results().getFirst().candidates()).hasSize(2);
        verify(operations, org.mockito.Mockito.never()).row(org.mockito.ArgumentMatchers.contains("\"title\""), anyMap());
    }

    @Test
    void shouldApplyCriteriaWhenResolvingReference() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.row(anyString(), anyMap())).thenReturn(Map.of("total_count", 1));
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(referenceRow("contract-1", "Contract One")));
        DynamicRecordService service = referenceResolvingService(operations);

        DynamicReferenceResolveResponse response = service.resolveReference(
                MODULE,
                "line",
                "contractId",
                DynamicReferenceResolveRequest.query("Contract")
                        .withCriteria(Criteria.of().eq("code", "CONTRACT-1"))
        );

        assertThat(response.status()).isEqualTo(DynamicReferenceResolveStatus.OK);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(operations, org.mockito.Mockito.atLeastOnce()).query(sql.capture(), anyMap());
        assertThat(sql.getAllValues()).anySatisfy(statement -> assertThat(statement)
                .contains("\"code\" =")
                .contains("\"title\" LIKE"));
    }

    @Test
    void shouldQueryChildAssociationViewWithTargetCriteria() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.row(anyString(), anyMap())).thenReturn(Map.of("total_count", 1));
        when(operations.query(anyString(), anyMap())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("\"app_contract_line\"")) {
                return List.of(lineRow("line-1", "contract-1", "Line A"));
            }
            return List.of(row("contract-1", "C-001", 0, false));
        });
        DynamicRecordService service = associationService(operations);

        PageResult<DynamicRecord> page = service.associationViewPage(
                MODULE,
                "contract",
                "contract-1",
                "lines",
                Criteria.of().like("summary", "Line"),
                PageRequest.of(1, 20)
        );

        assertThat(page.getRecords()).hasSize(1);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(operations, org.mockito.Mockito.atLeastOnce()).query(sql.capture(), anyMap());
        assertThat(sql.getAllValues()).anySatisfy(statement -> assertThat(statement)
                .contains("\"app_contract_line\"")
                .contains("\"contract_id\" =")
                .contains("\"summary\" LIKE"));
    }

    @Test
    void shouldQueryReferenceAssociationViewBySourceReferenceValue() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.row(anyString(), anyMap())).thenReturn(Map.of("total_count", 1));
        when(operations.query(anyString(), anyMap())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("\"app_contract_line\"")) {
                return List.of(lineRow("line-1", "contract-1", "Line A"));
            }
            return List.of(row("contract-1", "C-001", 0, false));
        });
        DynamicRecordService service = associationService(operations);

        assertThat(service.associationView(MODULE, "line", "contractId").queryable()).isTrue();

        PageResult<DynamicRecord> page = service.associationViewPage(
                MODULE,
                "line",
                "line-1",
                "contractId",
                Criteria.of().like("code", "C-"),
                PageRequest.of(1, 20)
        );

        assertThat(page.getRecords()).hasSize(1);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(operations, org.mockito.Mockito.atLeastOnce()).query(sql.capture(), anyMap());
        assertThat(sql.getAllValues()).anySatisfy(statement -> assertThat(statement)
                .contains("\"app_contract\"")
                .contains("\"id\" =")
                .contains("\"code\" LIKE"));
    }

    @Test
    void shouldRejectNonQueryableAssociationView() {
        IDatabaseOperations<Object> operations = operations();
        EntityRelationDefinition relation = EntityRelationDefinition.child("lines", "contract", "line", "contractId");
        EntityAssociationViewDefinition hiddenView = new EntityAssociationViewDefinition(
                "hiddenLines",
                "contract",
                MODULE,
                "line",
                AssociationViewDisplayMode.INLINE_LIST,
                "lines",
                null,
                EntityViewType.LIST,
                false
        );
        DynamicRecordService service = associationService(operations, List.of(relation), List.of(), List.of(hiddenView),
                new net.ximatai.muyun.spring.common.platform.AllowAllDataScopeCriteriaService());

        assertThatThrownBy(() -> service.associationViewPage(MODULE, "contract", "contract-1",
                "hiddenLines", Criteria.of(), PageRequest.of(1, 20)))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("dynamic association view is not queryable");
    }

    @Test
    void shouldReturnEmptyPageWhenReferenceAssociationValueIsBlank() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.row(anyString(), anyMap())).thenReturn(Map.of("total_count", 0));
        when(operations.query(anyString(), anyMap())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("\"app_contract_line\"")) {
                return List.of(lineRow("line-1", "", "Line A"));
            }
            return List.of();
        });
        DynamicRecordService service = associationService(operations);

        PageResult<DynamicRecord> page = service.associationViewPage(
                MODULE,
                "line",
                "line-1",
                "contractId",
                Criteria.of(),
                PageRequest.of(1, 20)
        );

        assertThat(page.getRecords()).isEmpty();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(operations, org.mockito.Mockito.atLeastOnce()).row(sql.capture(), anyMap());
        assertThat(sql.getAllValues()).anySatisfy(statement -> assertThat(statement).contains("1 = 0"));
    }

    @Test
    void shouldApplyViewScopeToSourceAndQueryScopeToAssociationTarget() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.row(anyString(), anyMap())).thenReturn(Map.of("total_count", 1));
        when(operations.query(anyString(), anyMap())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("\"app_contract_line\"")) {
                return List.of(lineRow("line-1", "contract-1", "Line A"));
            }
            return List.of(row("contract-1", "C-001", 0, false));
        });
        DataScopeCriteriaService dataScope = mock(DataScopeCriteriaService.class);
        when(dataScope.resolveReadScope(eq(MODULE), any(ActionExecutionPolicy.class), any(Criteria.class), any()))
                .thenAnswer(invocation -> DataScopeCriteriaResult.restricted(invocation.getArgument(2)));
        DynamicRecordService service = associationService(operations, dataScope);

        PageResult<DynamicRecord> page = service.associationViewPage(
                MODULE,
                "contract",
                "contract-1",
                "lines",
                Criteria.of().like("summary", "Line"),
                PageRequest.of(1, 20)
        );

        assertThat(page.getRecords()).hasSize(1);
        ArgumentCaptor<ActionExecutionPolicy> policy = ArgumentCaptor.forClass(ActionExecutionPolicy.class);
        verify(dataScope, org.mockito.Mockito.atLeastOnce())
                .resolveReadScope(eq(MODULE), policy.capture(), any(Criteria.class), any());
        assertThat(policy.getAllValues())
                .extracting(ActionExecutionPolicy::actionCode)
                .contains(PlatformAction.VIEW.code(), PlatformAction.QUERY.code());
    }

    @Test
    void shouldExposeAssociationRelationOverviewForDesigner() {
        DynamicRecordService service = associationService(operations());

        DynamicAssociationRelationOverview overview = service.associationRelationOverview(MODULE);

        assertThat(overview.downstream())
                .extracting(item -> item.type() + ":" + item.code() + ":" + item.associationViewCode())
                .contains("RELATION:lines:lines", "REFERENCE:contractId:contractId");
        assertThat(overview.upstream())
                .extracting(item -> item.type() + ":" + item.code() + ":" + item.associationViewCode())
                .contains("RELATION:lines:lines", "REFERENCE:contractId:contractId");
    }

    @Test
    void shouldKeepAssociationDesignFieldsInDescriptor() {
        EntityRelationDefinition relation = EntityRelationDefinition.child("lines", "contract", "line", "contractId");
        EntityAssociationViewDefinition view = new EntityAssociationViewDefinition(
                "lines",
                "contract",
                MODULE,
                "line",
                AssociationViewDisplayMode.INLINE_LIST,
                "lines",
                null,
                EntityViewType.LIST,
                true,
                null,
                AssociationViewRootQueryMapping.sourceField("summary", DynamicQueryOperator.EQ, "code"),
                "ui-list",
                "query-open"
        );
        DynamicRecordService service = associationService(operations(), List.of(relation), List.of(), List.of(view),
                new net.ximatai.muyun.spring.common.platform.AllowAllDataScopeCriteriaService());

        var descriptor = service.associationViewDesignDescriptors(MODULE).getFirst();

        assertThat(descriptor.path()).hasSize(1);
        assertThat(descriptor.rootQueryMapping().targetField()).isEqualTo("summary");
        assertThat(descriptor.targetUiConfigId()).isEqualTo("ui-list");
        assertThat(descriptor.targetQueryTemplateId()).isEqualTo("query-open");
    }

    @Test
    void shouldApplyRootQueryMappingWhenDiagnosingAssociationView() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.row(anyString(), anyMap())).thenReturn(Map.of("total_count", 1));
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(lineRow("line-1", "contract-1", "C-001")));
        EntityReferenceDefinition reference = EntityReferenceDefinition.to("line", "contractId",
                ReferenceTarget.of(MODULE, "contract"));
        EntityAssociationViewDefinition view = new EntityAssociationViewDefinition(
                "contractId",
                "line",
                MODULE,
                "contract",
                AssociationViewDisplayMode.LINKED_RECORD,
                null,
                "contractId",
                EntityViewType.FORM,
                true,
                null,
                AssociationViewRootQueryMapping.group(AssociationViewQueryMappingGroupOperator.AND,
                        List.of(AssociationViewRootQueryMapping.sourceField("code", DynamicQueryOperator.EQ, "summary"))),
                null,
                null
        );
        DynamicRecordService service = associationService(operations, List.of(), List.of(reference), List.of(view),
                new net.ximatai.muyun.spring.common.platform.AllowAllDataScopeCriteriaService());

        DynamicAssociationViewDiagnosis diagnosis = service.diagnoseAssociationView(
                MODULE, "line", "line-1", "contractId", Criteria.of());

        assertThat(diagnosis.status()).isEqualTo(DynamicAssociationViewDiagnosisStatus.OK);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(operations, atLeastOnce()).row(sql.capture(), anyMap());
        assertThat(sql.getAllValues()).anySatisfy(statement -> assertThat(statement)
                .contains("\"id\" =")
                .contains("\"code\" ="));
    }

    @Test
    void shouldApplyRootQueryMappingWhenQueryingAssociationView() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.row(anyString(), anyMap())).thenReturn(Map.of("total_count", 1));
        when(operations.query(anyString(), anyMap())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("\"app_contract_line\"")) {
                return List.of(lineRow("line-1", "contract-1", "C-001"));
            }
            return List.of(row("contract-1", "C-001", 0, false));
        });
        EntityRelationDefinition relation = EntityRelationDefinition.child("lines", "contract", "line", "contractId");
        EntityAssociationViewDefinition view = new EntityAssociationViewDefinition(
                "lines",
                "contract",
                MODULE,
                "line",
                AssociationViewDisplayMode.INLINE_LIST,
                "lines",
                null,
                EntityViewType.LIST,
                true,
                null,
                AssociationViewRootQueryMapping.sourceField("summary", DynamicQueryOperator.EQ, "code"),
                null,
                null
        );
        DynamicRecordService service = associationService(operations, List.of(relation), List.of(), List.of(view),
                new net.ximatai.muyun.spring.common.platform.AllowAllDataScopeCriteriaService());

        PageResult<DynamicRecord> page = service.associationViewPage(
                MODULE, "contract", "contract-1", "lines", Criteria.of(), PageRequest.of(1, 20));

        assertThat(page.getRecords()).hasSize(1);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(operations, atLeastOnce()).query(sql.capture(), anyMap());
        assertThat(sql.getAllValues()).anySatisfy(statement -> assertThat(statement)
                .contains("\"app_contract_line\"")
                .contains("\"contract_id\" =")
                .contains("\"summary\" ="));
    }

    @Test
    void shouldReportFormAssociationWhenTargetIsNotUnique() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.row(anyString(), anyMap())).thenReturn(Map.of("total_count", 2));
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(row("contract-1", "C-001", 0, false)));
        EntityRelationDefinition relation = EntityRelationDefinition.child("lines", "contract", "line", "contractId");
        EntityAssociationViewDefinition formView = new EntityAssociationViewDefinition(
                "lineForm",
                "contract",
                MODULE,
                "line",
                AssociationViewDisplayMode.INLINE_LIST,
                "lines",
                null,
                EntityViewType.FORM,
                true
        );
        DynamicRecordService service = associationService(operations, List.of(relation), List.of(), List.of(formView),
                new net.ximatai.muyun.spring.common.platform.AllowAllDataScopeCriteriaService());

        DynamicAssociationViewDiagnosis diagnosis = service.diagnoseAssociationView(
                MODULE, "contract", "contract-1", "lineForm", Criteria.of());

        assertThat(diagnosis.status()).isEqualTo(DynamicAssociationViewDiagnosisStatus.FORM_NOT_UNIQUE);
        assertThat(diagnosis.message()).contains("must be unique");
    }

    @Test
    void shouldExcludeSoftDeletedTargetWhenResolvingReference() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.row(anyString(), anyMap())).thenReturn(Map.of("total_count", 0));
        when(operations.query(anyString(), anyMap())).thenReturn(List.of());
        DynamicRecordService service = referenceResolvingService(operations);

        DynamicReferenceResolveResponse response = service.resolveFieldReference(
                MODULE,
                "line",
                "contractId",
                DynamicReferenceResolveRequest.query("Deleted Contract")
        );

        assertThat(response.status()).isEqualTo(DynamicReferenceResolveStatus.NOT_FOUND);
        assertThat(response.options()).isEmpty();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(operations, org.mockito.Mockito.atLeastOnce()).query(sql.capture(), anyMap());
        assertThat(sql.getAllValues()).anySatisfy(statement -> assertThat(statement)
                .contains("\"deleted\" =")
                .contains("\"deleted\" IS NULL"));
    }

    @Test
    void shouldExcludeSoftDeletedTargetWhenTranslatingReference() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.row(anyString(), anyMap())).thenReturn(Map.of("total_count", 0));
        when(operations.query(anyString(), anyMap())).thenReturn(List.of());
        DynamicRecordService service = referenceResolvingService(operations);

        DynamicReferenceResolveResponse response = service.resolveFieldReference(
                MODULE,
                "line",
                "contractId",
                DynamicReferenceResolveRequest.translate(List.of("deleted-contract"))
        );

        assertThat(response.status()).isEqualTo(DynamicReferenceResolveStatus.NOT_FOUND);
        assertThat(response.results()).singleElement()
                .extracting(DynamicReferenceResolveResult::status)
                .isEqualTo(DynamicReferenceResolveStatus.NOT_FOUND);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(operations, org.mockito.Mockito.atLeastOnce()).query(sql.capture(), anyMap());
        assertThat(sql.getAllValues()).anySatisfy(statement -> assertThat(statement)
                .contains("\"deleted\" =")
                .contains("\"deleted\" IS NULL"));
    }

    @Test
    void shouldRejectSoftDeletedTargetWhenSavingReference() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenReturn(List.of());
        DynamicEntityOperations lines = referenceResolvingService(operations).entity(MODULE, "line");
        DynamicRecord line = lines.newRecord()
                .setValue("contractId", "deleted-contract")
                .setValue("summary", "should fail");
        line.setId("line-1");

        assertThatThrownBy(() -> lines.create(line))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dynamic reference target not found")
                .hasMessageContaining("sales.contract.contract.deleted-contract");
        verify(operations, org.mockito.Mockito.never()).insertItem(anyString(), anyString(), anyMap(), anyString());
    }

    @Test
    void shouldPreserveExistingUnavailableReferenceWhenUpdatingDynamicRecord() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            return sql.contains("app_contract_line")
                    ? List.of(lineRow("line-1", "deleted-contract", "existing"))
                    : List.of();
        });
        DynamicEntityOperations lines = referenceResolvingService(operations).entity(MODULE, "line");
        DynamicRecord line = lines.newRecord()
                .setValue("contractId", "deleted-contract")
                .setValue("summary", "updated");
        line.setId("line-1");

        lines.update(line);

        verify(operations).patchUpdateItemWhere(eq(SCHEMA), eq("app_contract_line"), anyMap(), anyMap(), eq("id"));

        line.setValue("contractId", "another-deleted-contract");
        assertThatThrownBy(() -> lines.update(line))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dynamic reference target not found");
    }

    @Test
    void shouldReturnNotFoundForEmptyReferenceTranslateValues() {
        DynamicRecordService service = referenceResolvingService(operations());

        DynamicReferenceResolveResponse response = service.resolveReference(
                MODULE,
                "line",
                "contractId",
                DynamicReferenceResolveRequest.translate(List.of())
        );

        assertThat(response.status()).isEqualTo(DynamicReferenceResolveStatus.NOT_FOUND);
        assertThat(response.results()).isEmpty();
        assertThat(response.total()).isZero();
    }

    @Test
    void shouldRejectMissingDynamicReferenceConfig() {
        DynamicRecordService service = service(operations(), lineEntity());

        assertThatThrownBy(() -> service.resolveReference(
                MODULE,
                "line",
                "contractId",
                DynamicReferenceResolveRequest.query("Contract")
        ))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessageContaining("unknown dynamic reference: sales.contract.line.contractId");
    }

    @Test
    void shouldExposeEnableOperationsThroughStableServiceApi() {
        IDatabaseOperations<Object> operations = operations();
        AtomicReference<Boolean> enabled = new AtomicReference<>(Boolean.FALSE);
        when(operations.query(anyString(), anyMap()))
                .thenAnswer(invocation -> List.of(enabledRow("contract-1", enabled.get())));
        when(operations.patchUpdateItemWhere(anyString(), anyString(), anyMap(), anyMap(), anyString()))
                .thenAnswer(invocation -> {
                    enabled.set((Boolean) invocation.<Map<String, Object>>getArgument(2).get("enabled"));
                    return 1;
                });
        DynamicRecordService service = service(operations, enabledEntity());

        assertThat(service.isEnabled(MODULE, "contract", "contract-1")).isFalse();
        assertThat(service.enable(MODULE, "contract", "contract-1")).isEqualTo(1);
        assertThat(service.isEnabled(MODULE, "contract", "contract-1")).isTrue();
        assertThat(service.disable(MODULE, "contract", "contract-1")).isEqualTo(1);
        assertThat(service.isEnabled(MODULE, "contract", "contract-1")).isFalse();
        Criteria activeContracts = service.enabledCriteria(MODULE, "contract", Criteria.of().eq("code", "CONTRACT-1"));
        service.list(MODULE, "contract", activeContracts, PageRequest.of(1, 10));

        ArgumentCaptor<Map<String, Object>> body = mapCaptor();
        verify(operations, org.mockito.Mockito.times(2))
                .patchUpdateItemWhere(eq(SCHEMA), eq("app_contract"), body.capture(), anyMap(), eq("id"));
        assertThat(body.getAllValues().get(0)).containsEntry("enabled", Boolean.TRUE);
        assertThat(body.getAllValues().get(1)).containsEntry("enabled", Boolean.FALSE);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(operations, org.mockito.Mockito.atLeastOnce()).query(sql.capture(), anyMap());
        assertThat(sql.getAllValues()).anySatisfy(statement -> assertThat(statement)
                .contains("\"enabled\" =")
                .contains("\"code\" ="));
    }

    @Test
    void shouldExecuteEnableActionThroughStableActionApi() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(enabledRow("contract-1", false)));
        DynamicRecordService service = service(operations, enabledEntity());

        DynamicActionExecutionResult result = service.module(MODULE)
                .executeAction("enable", DynamicActionExecutionRequest.id("contract-1"));

        assertThat(result.value()).isEqualTo(1);
        assertThat(result.body().type()).isEqualTo(DynamicActionResultType.COUNT);
        assertThat(result.body().refresh()).isTrue();
        assertThat(result.context().recordId()).isEqualTo("contract-1");
        ArgumentCaptor<Map<String, Object>> body = mapCaptor();
        verify(operations).patchUpdateItemWhere(eq(SCHEMA), eq("app_contract"), body.capture(), anyMap(), eq("id"));
        assertThat(body.getValue()).containsEntry("enabled", Boolean.TRUE);
    }

    @Test
    void shouldPublishRecordMutationEventsThroughStableServiceApi() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.insertItem(eq(SCHEMA), eq("app_contract"), anyMap(), eq("id")))
                .thenAnswer(invocation -> invocation.<Map<String, Object>>getArgument(2).get("id"));
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(row("contract-1", "C-001", 0, false)));
        CollectingRuntimeEventPublisher events = new CollectingRuntimeEventPublisher();
        DynamicRecordService service = service(operations, contractEntity(), events);
        DynamicRecord record = service.newRecord(MODULE, "contract")
                .setValue("code", "C-001");
        record.setId("contract-1");

        service.create(MODULE, "contract", record);
        service.update(MODULE, "contract", record);
        service.delete(MODULE, "contract", "contract-1");

        assertThat(events.events()).extracting(RuntimeEvent::eventType)
                .containsExactly(RuntimeEventType.AFTER_CREATE, RuntimeEventType.AFTER_UPDATE, RuntimeEventType.AFTER_DELETE);
        assertThat(events.events()).extracting(RuntimeEvent::mutationSource)
                .containsExactly(RuntimeMutationSource.BUSINESS, RuntimeMutationSource.BUSINESS, RuntimeMutationSource.BUSINESS);
        assertThat(events.events()).allSatisfy(event -> {
            assertThat(event.moduleAlias()).isEqualTo(MODULE);
            assertThat(event.entityAlias()).isEqualTo("contract");
            assertThat(event.recordId()).isEqualTo("contract-1");
            assertThat(event.actionCode()).isNull();
        });
    }

    @Test
    void shouldPublishSystemUpdateEventWithSystemReasonThroughStableServiceApi() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(row("contract-1", "C-001", 0, false)));
        CollectingRuntimeEventPublisher events = new CollectingRuntimeEventPublisher();
        DynamicRecordService service = service(operations, contractEntity(), events);
        DynamicRecord record = service.newRecord(MODULE, "contract")
                .setValue("code", "C-002");
        record.setId("contract-1");

        service.updateSystem(MODULE, "contract", record, "workflow submit");

        assertThat(events.events()).singleElement()
                .satisfies(event -> {
                    assertThat(event.eventType()).isEqualTo(RuntimeEventType.AFTER_UPDATE);
                    assertThat(event.mutationSource()).isEqualTo(RuntimeMutationSource.SYSTEM);
                    assertThat(event.systemContext()).isTrue();
                    assertThat(event.systemReason()).isEqualTo("workflow submit");
                    assertThat(event.payload()).containsEntry("systemReason", "workflow submit");
                });
    }

    @Test
    void shouldBypassBusinessDataScopeForSystemUpdate() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(row("hidden", "C-001", 0, false)));
        when(operations.patchUpdateItemWhere(anyString(), anyString(), anyMap(), anyMap(), anyString())).thenReturn(1);
        CollectingRuntimeEventPublisher events = new CollectingRuntimeEventPublisher();
        DynamicRecordService service = service(operations, dataScopedActionEntity(), events,
                new FailingDataScopeCriteriaService());
        DynamicRecord record = service.newRecord(MODULE, "contract")
                .setValue("code", "C-002");
        record.setId("hidden");

        assertThat(service.updateSystem(MODULE, "contract", record, "workflow submit")).isEqualTo(1);

        verify(operations).patchUpdateItemWhere(eq(SCHEMA), eq("app_contract"), anyMap(), anyMap(), eq("id"));
        assertThat(events.events()).singleElement()
                .satisfies(event -> {
                    assertThat(event.mutationSource()).isEqualTo(RuntimeMutationSource.SYSTEM);
                    assertThat(event.systemContext()).isTrue();
                    assertThat(event.systemReason()).isEqualTo("workflow submit");
                });
    }

    @Test
    void shouldPublishBatchDeleteAndSortMutationEvents() {
        IDatabaseOperations<Object> operations = operations();
        stubSortableRows(operations);
        CollectingRuntimeEventPublisher events = new CollectingRuntimeEventPublisher();
        DynamicRecordService service = service(operations, sortableEntity(), events);

        service.reorder(MODULE, "contract", List.of("first", "second", "third"));
        service.moveBefore(MODULE, "contract", "third", "first");
        service.deleteBatch(MODULE, "contract", List.of("first", "second"));

        assertThat(events.events()).extracting(RuntimeEvent::eventType)
                .containsExactly(RuntimeEventType.AFTER_UPDATE, RuntimeEventType.AFTER_UPDATE, RuntimeEventType.AFTER_DELETE);
        assertThat(events.events().get(0).payload())
                .containsEntry("operation", "reorder")
                .containsEntry("recordIds", List.of("first", "second", "third"));
        assertThat(events.events().get(1).payload())
                .containsEntry("operation", "moveBefore")
                .containsEntry("beforeId", "first");
        assertThat(events.events().get(2).payload())
                .containsEntry("recordIds", List.of("first", "second"))
                .containsEntry("count", 2);
    }

    @Test
    void shouldPublishRuntimeEventAfterCommitWhenTransactionIsActive() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.insertItem(eq(SCHEMA), eq("app_contract"), anyMap(), eq("id")))
                .thenAnswer(invocation -> invocation.<Map<String, Object>>getArgument(2).get("id"));
        CollectingRuntimeEventPublisher events = new CollectingRuntimeEventPublisher();
        DynamicRecordService service = service(operations, contractEntity(), events);
        DynamicRecord record = service.newRecord(MODULE, "contract")
                .setValue("code", "C-001");
        record.setId("contract-1");

        try {
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            service.create(MODULE, "contract", record);
            assertThat(events.events()).isEmpty();
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            assertThat(events.events()).extracting(RuntimeEvent::eventType)
                    .containsExactly(RuntimeEventType.AFTER_CREATE);
        } finally {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.clearSynchronization();
            }
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void shouldNotPublishRuntimeEventAfterRollback() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.insertItem(eq(SCHEMA), eq("app_contract"), anyMap(), eq("id")))
                .thenAnswer(invocation -> invocation.<Map<String, Object>>getArgument(2).get("id"));
        CollectingRuntimeEventPublisher events = new CollectingRuntimeEventPublisher();
        DynamicRecordService service = service(operations, contractEntity(), events);
        DynamicRecord record = service.newRecord(MODULE, "contract")
                .setValue("code", "C-001");
        record.setId("contract-1");

        try {
            TransactionSynchronizationManager.initSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(true);
            service.create(MODULE, "contract", record);
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            assertThat(events.events()).isEmpty();
        } finally {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.clearSynchronization();
            }
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test
    void shouldRejectIncompleteRuntimeEvent() {
        assertThatThrownBy(() -> new RuntimeEvent(null, null, null, MODULE, "contract", null, null,
                null, false, RuntimeMutationSource.BUSINESS, Map.of(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("eventType");
        assertThatThrownBy(() -> RuntimeEvent.of(RuntimeEventType.ACTION_EXECUTED, MODULE, "contract", "contract-1",
                null, null, false, RuntimeMutationSource.ACTION, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("actionCode");
        assertThatThrownBy(() -> RuntimeEvent.of(RuntimeEventType.ACTION_FAILED, MODULE, "contract", "contract-1",
                null, null, false, RuntimeMutationSource.ACTION, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("actionCode");
        assertThatThrownBy(() -> RuntimeEvent.of(RuntimeEventType.AFTER_CREATE, MODULE, "contract", "contract-1",
                null, null, true, RuntimeMutationSource.SYSTEM, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("systemReason");
        assertThatThrownBy(() -> RuntimeEvent.of(RuntimeEventType.AFTER_CREATE, MODULE, "contract", "contract-1",
                null, null, false, RuntimeMutationSource.SYSTEM, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("systemReason");
    }

    @Test
    void shouldRejectWebOnlyStandardActionsFromGenericActionExecutor() {
        DynamicRecordService service = service(operations(), referenceEntity());

        assertThatThrownBy(() -> service.entity(MODULE, "contract")
                .executeAction("reference", DynamicActionExecutionRequest.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only exposed through web endpoint");
    }

    @Test
    void shouldRejectUnsupportedStableServiceAbilityApi() {
        DynamicRecordService service = service(operations(), contractEntity());

        assertThatThrownBy(() -> service.enable(MODULE, "contract", "contract-1"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("ENABLE");
        assertThatThrownBy(() -> service.disable(MODULE, "contract", "contract-1"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("ENABLE");
        assertThatThrownBy(() -> service.isEnabled(MODULE, "contract", "contract-1"))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("ENABLE");
        assertThatThrownBy(() -> service.enabledCriteria(MODULE, "contract", Criteria.of()))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("ENABLE");
        assertThatThrownBy(() -> service.projections(MODULE, "contract", List.of("contract-1"), List.of("code")))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("REFERENCE");
    }

    @Test
    void shouldValidateDictionaryBoundFieldThroughRuntimeValidator() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.insertItem(eq(SCHEMA), eq("app_contract"), anyMap(), eq("id")))
                .thenAnswer(invocation -> invocation.<Map<String, Object>>getArgument(2).get("id"));
        DynamicFieldValueValidator validator = (moduleAlias, entity, field, value) -> {
            FieldDictionaryBinding binding = field.dictionaryBinding();
            if (binding != null && !"active".equals(value)) {
                throw new IllegalArgumentException("invalid dictionary code: " + value);
            }
        };
        DynamicRecordRuntime runtime = DynamicRecordRuntime.builder(operations)
                .fieldValueValidator(validator)
                .build()
                .register(new ModuleDefinition(MODULE, "Contract", List.of(dictionaryEntity())));
        DynamicEntityOperations contracts = new DynamicRecordService(runtime).entity(MODULE, "contract");
        DynamicRecord record = contracts.newRecord()
                .setValue("code", "C-001")
                .setValue("status", "active");
        record.setId("contract-1");

        assertThat(contracts.create(record)).isEqualTo("contract-1");
        DynamicRecord invalid = contracts.newRecord()
                .setValue("code", "C-002")
                .setValue("status", "frozen");
        invalid.setId("contract-2");
        assertThatThrownBy(() -> contracts.create(invalid))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dictionary code");
    }

    @Test
    void shouldRejectSaveWhenDynamicFormulaValidationFailsThroughStableServiceApi() {
        IDatabaseOperations<Object> operations = operations();
        DynamicRecordService service = service(operations, formulaValidationEntity());
        DynamicRecord record = service.newRecord(MODULE, "contract")
                .setValue("amount", BigDecimal.valueOf(-1));
        record.setId("contract-1");

        assertThatThrownBy(() -> service.create(MODULE, "contract", record))
                .isInstanceOf(DynamicFormulaException.class)
                .hasMessageContaining("amount must be positive")
                .satisfies(error -> {
                    DynamicFormulaException exception = (DynamicFormulaException) error;
                    assertThat(exception.firstError().ruleId()).isEqualTo("amountPositive");
                    assertThat(exception.firstError().fieldPath()).isEqualTo("amount");
                });
        verify(operations, never()).insertItem(anyString(), anyString(), anyMap(), anyString());
    }

    @Test
    void shouldKeepFormulaWarningsOnRecordThroughStableServiceApi() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.insertItem(eq(SCHEMA), eq("app_contract"), anyMap(), eq("id")))
                .thenAnswer(invocation -> invocation.<Map<String, Object>>getArgument(2).get("id"));
        DynamicRecordService service = service(operations, formulaWarningEntity());
        DynamicRecord record = service.newRecord(MODULE, "contract")
                .setValue("amount", BigDecimal.valueOf(1500));
        record.setId("contract-1");

        assertThat(service.create(MODULE, "contract", record)).isEqualTo("contract-1");

        assertThat(record.formulaReport().warnings())
                .hasSize(1)
                .first()
                .satisfies(issue -> {
                    assertThat(issue.ruleId()).isEqualTo("amountHighRisk");
                    assertThat(issue.message()).isEqualTo("amount is high");
                });
        verify(operations).insertItem(eq(SCHEMA), eq("app_contract"), anyMap(), eq("id"));
    }

    @Test
    void shouldApplyFieldDefaultAndRejectWriteProtectedInput() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.insertItem(eq(SCHEMA), eq("app_contract"), anyMap(), eq("id")))
                .thenAnswer(invocation -> invocation.<Map<String, Object>>getArgument(2).get("id"));
        DynamicRecordService service = service(operations, behaviorEntity());
        DynamicRecord record = service.newRecord(MODULE, "contract")
                .setValue("code", "C-001");
        record.setId("contract-1");

        assertThat(service.create(MODULE, "contract", record)).isEqualTo("contract-1");
        assertThatThrownBy(() -> {
            DynamicRecord invalid = service.newRecord(MODULE, "contract")
                    .setValue("code", "C-002")
                    .setValue("serverCode", "MANUAL");
            invalid.setId("contract-2");
            service.create(MODULE, "contract", invalid);
        })
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("write protected");

        ArgumentCaptor<Map<String, Object>> body = mapCaptor();
        verify(operations).insertItem(eq(SCHEMA), eq("app_contract"), body.capture(), eq("id"));
        assertThat(body.getValue()).containsEntry("status", "draft");
        assertThat(body.getValue()).doesNotContainKey("server_code");
    }

    @Test
    void shouldRejectWriteProtectedDynamicFieldOnUpdate() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.row(anyString(), anyMap())).thenReturn(Map.of("total_count", 1));
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(behaviorRow("contract-1", "C-001", "SYS-1")));
        DynamicRecordService service = service(operations, behaviorEntity());
        DynamicRecord record = service.newRecord(MODULE, "contract")
                .setValue("serverCode", "MANUAL");
        record.setId("contract-1");

        assertThatThrownBy(() -> service.update(MODULE, "contract", record))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("write protected");
    }

    @Test
    void shouldAllowPlatformAbilityWriteOnWriteProtectedField() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(enabledRow("contract-1", false)));
        when(operations.patchUpdateItemWhere(anyString(), anyString(), anyMap(), anyMap(), anyString())).thenReturn(1);
        DynamicRecordService service = service(operations, writeProtectedEnabledEntity());

        assertThat(service.enable(MODULE, "contract", "contract-1")).isEqualTo(1);

        ArgumentCaptor<Map<String, Object>> body = mapCaptor();
        verify(operations).patchUpdateItemWhere(eq(SCHEMA), eq("app_contract"), body.capture(), anyMap(), eq("id"));
        assertThat(body.getValue()).containsEntry("enabled", Boolean.TRUE);
    }

    @Test
    void shouldCacheDynamicSelectAcrossRuntimeFacadeCallsAndClearAfterUpdate() {
        IDatabaseOperations<Object> operations = operations();
        AtomicReference<String> storedCode = new AtomicReference<>("C-001");
        when(operations.query(anyString(), anyMap()))
                .thenAnswer(invocation -> List.of(row("contract-1", storedCode.get(), 0, false)));
        when(operations.patchUpdateItemWhere(anyString(), anyString(), anyMap(), anyMap(), anyString()))
                .thenAnswer(invocation -> {
                    storedCode.set(String.valueOf(invocation.<Map<String, Object>>getArgument(2).get("code")));
                    return 1;
                });
        DynamicRecordService service = service(operations, contractEntity());

        DynamicRecord first = service.select(MODULE, "contract", "contract-1");
        first.setValue("code", "MUTATED-CALLER");
        DynamicRecord second = service.select(MODULE, "contract", "contract-1");
        assertThat(second.getValue("code")).isEqualTo("C-001");

        second.setValue("code", "C-002");
        service.update(MODULE, "contract", second);
        DynamicRecord third = service.select(MODULE, "contract", "contract-1");

        assertThat(third.getValue("code")).isEqualTo("C-002");
        verify(operations, org.mockito.Mockito.atLeast(3)).query(anyString(), anyMap());
    }

    @Test
    void shouldKeepFullSelectPathAfterRestrictedDataScopeVisibilityCheck() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap()))
                .thenReturn(List.of(row("contract-1", "C-001", 0, false)))
                .thenReturn(List.of(row("contract-1", "C-001", 0, false)));
        DataScopeCriteriaService dataScope = new DataScopeCriteriaService() {
            @Override
            public DataScopeCriteriaResult resolveReadScope(String moduleAlias,
                                                            String actionCode,
                                                            Criteria criteria,
                                                            java.util.Optional<CurrentUser> currentUser) {
                return DataScopeCriteriaResult.restricted(criteria);
            }

            @Override
            public DataScopeCriteriaResult resolveReadScope(String moduleAlias,
                                                            ActionExecutionPolicy policy,
                                                            Criteria criteria,
                                                            java.util.Optional<CurrentUser> currentUser) {
                return resolveReadScope(moduleAlias, policy.permissionActionCode(), criteria, currentUser);
            }
        };
        DynamicRecordService service = service(operations, contractEntity(), RuntimeEventPublisher.noop(), dataScope);

        DynamicRecord selected = service.select(MODULE, "contract", "contract-1");

        assertThat(selected).isNotNull();
        assertThat(selected.getValue("code")).isEqualTo("C-001");
        verify(operations, org.mockito.Mockito.times(2)).query(anyString(), anyMap());
    }

    @Test
    void shouldBypassTenantScopeWhenDynamicDataScopeAllowsCrossTenantData() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(row("contract-1", "C-001", 0, false)));
        DynamicRecordService service = service(operations, contractEntity(), RuntimeEventPublisher.noop(),
                new CrossTenantAllDataScopeCriteriaService());

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            assertThat(service.list(MODULE, "contract", Criteria.of(), PageRequest.of(1, 10)))
                    .extracting(DynamicRecord::getId)
                    .containsExactly("contract-1");
            assertThat(TenantContext.currentTenantId()).contains("tenant-a");
            assertThat(TenantContext.tenantFilterBypassed()).isFalse();
        }

        org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(operations).query(sql.capture(), anyMap());
        assertThat(sql.getValue()).doesNotContain("tenant_id");
    }

    @Test
    void shouldKeepQueryReadScopeTenantBoundaryAndReturnScopedCriteria() {
        DynamicRecordService service = service(operations(), contractEntity(), RuntimeEventPublisher.noop(),
                new CrossTenantAllDataScopeCriteriaService());

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            String scopedSql = service.withQueryReadScope(MODULE, Criteria.of().eq("code", "C-001"), criteria -> {
                assertThat(TenantContext.tenantFilterBypassed()).isTrue();
                return new CriteriaSqlCompiler().compile(criteria, field -> field, DBInfo.Type.POSTGRESQL).getSql();
            });

            assertThat(scopedSql).contains("\"code\" =");
            assertThat(TenantContext.currentTenantId()).contains("tenant-a");
            assertThat(TenantContext.tenantFilterBypassed()).isFalse();
        }
    }

    @Test
    void shouldApplyDataScopeBeforeTheQueryRuntimeSoftDeleteBoundary() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(row("visible", "C-001", 0, false)));
        DynamicRecordService service = service(operations, contractEntity(), RuntimeEventPublisher.noop(),
                new VisibleOnlyDataScopeCriteriaService());

        assertThat(service.list(MODULE, "contract", Criteria.of().eq("code", "C-001"), PageRequest.of(1, 10)))
                .extracting(DynamicRecord::getId)
                .containsExactly("visible");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(operations).query(sql.capture(), anyMap());
        assertThat(sql.getValue()).contains("\"code\" =").contains("\"id\" =").contains("\"deleted\" IS NULL");
    }

    @Test
    void shouldBypassTenantScopeWhenUpdatingDynamicRecordWithCrossTenantDataScope() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap()))
                .thenReturn(List.of(row("contract-1", "C-001", 0, false, "tenant-b")))
                .thenReturn(List.of(row("contract-1", "C-001", 0, false, "tenant-b")));
        DynamicRecordService service = service(operations, contractEntity(), RuntimeEventPublisher.noop(),
                new CrossTenantAllDataScopeCriteriaService());
        DynamicRecord record = service.newRecord(MODULE, "contract")
                .setValue("code", "C-002")
                .setValue("amount", BigDecimal.TEN);
        record.setId("contract-1");

        try (TenantContext.Scope ignored = TenantContext.use("tenant-a")) {
            assertThat(service.update(MODULE, "contract", record)).isEqualTo(1);
            assertThat(TenantContext.currentTenantId()).contains("tenant-a");
            assertThat(TenantContext.tenantFilterBypassed()).isFalse();
        }

        verify(operations).patchUpdateItemWhere(anyString(), anyString(), anyMap(),
                org.mockito.ArgumentMatchers.argThat(where -> "tenant-b".equals(where.get("tenant_id"))),
                anyString());
    }

    @Test
    void shouldApplyDataScopeToReferenceResolveTargetModule() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.row(anyString(), anyMap())).thenReturn(Map.of("total_count", 0));
        when(operations.query(anyString(), anyMap())).thenReturn(List.of());
        DataScopeCriteriaService dataScope = mock(DataScopeCriteriaService.class);
        when(dataScope.resolveReadScope(eq(MODULE), any(ActionExecutionPolicy.class), any(Criteria.class), any()))
                .thenAnswer(invocation -> DataScopeCriteriaResult.unrestricted(invocation.getArgument(2)));
        DynamicRecordService service = referenceResolvingService(operations, dataScope);

        service.resolveReference(MODULE, "line", "contractId", DynamicReferenceResolveRequest.query("C-001"));

        verify(dataScope).resolveReadScope(eq(MODULE), any(ActionExecutionPolicy.class), any(Criteria.class), any());
    }

    @Test
    void shouldResolveCrossModuleReferenceThroughTargetModuleScope() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.row(anyString(), anyMap())).thenReturn(Map.of("total_count", 1));
        when(operations.query(anyString(), anyMap())).thenReturn(List.of(
                referenceRow("customer-1", "Acme")
        ));
        DataScopeCriteriaService dataScope = mock(DataScopeCriteriaService.class);
        when(dataScope.resolveReadScope(eq("crm.customer"), any(ActionExecutionPolicy.class), any(Criteria.class), any()))
                .thenAnswer(invocation -> DataScopeCriteriaResult.unrestricted(invocation.getArgument(2)));
        DynamicRecordRuntime runtime = new DynamicRecordRuntime(operations)
                .register(ModuleDefinition.builder(MODULE, "Contract")
                        .entities(List.of(lineEntity()))
                        .relations(List.of())
                        .references(List.of(EntityReferenceDefinition
                                .to("line", "contractId", ReferenceTarget.of("crm.customer", "customer"))
                                .withProjection("title", "customerTitle")
                                .withProjection("code", "customerCode")))
                        .build())
                .register(new ModuleDefinition(
                        "crm.customer",
                        "Customer",
                        List.of(customerReferenceEntity())
                ));
        DynamicRecordService service = new DynamicRecordService(
                runtime,
                new net.ximatai.muyun.spring.common.platform.AllowAllActionExecutionPolicyService(),
                dataScope
        );

        DynamicReferenceResolveResponse response = service.resolveReference(
                MODULE,
                "line",
                "contractId",
                DynamicReferenceResolveRequest.query("Acme")
        );

        assertThat(response.status()).isEqualTo(DynamicReferenceResolveStatus.OK);
        assertThat(response.options().getFirst().id()).isEqualTo("customer-1");
        assertThat(response.options().getFirst().projections()).containsEntry("customerCode", "CUSTOMER-1");
        verify(dataScope).resolveReadScope(eq("crm.customer"), any(ActionExecutionPolicy.class), any(Criteria.class), any());
    }

    @Test
    void shouldApplyDataScopeToTreeChildrenRead() {
        IDatabaseOperations<Object> operations = operations();
        when(operations.query(anyString(), anyMap())).thenReturn(List.of());
        DataScopeCriteriaService dataScope = mock(DataScopeCriteriaService.class);
        when(dataScope.resolveReadScope(eq(MODULE), any(ActionExecutionPolicy.class), any(Criteria.class), any()))
                .thenAnswer(invocation -> DataScopeCriteriaResult.restricted(invocation.getArgument(2)));
        DynamicRecordRuntime runtime = new DynamicRecordRuntime(operations)
                .register(new ModuleDefinition(MODULE, "Contract", List.of(treeEntity())));
        DynamicRecordService service = new DynamicRecordService(
                runtime,
                new net.ximatai.muyun.spring.common.platform.AllowAllActionExecutionPolicyService(),
                dataScope
        );

        service.children(MODULE, "contract", "root");

        verify(dataScope).resolveReadScope(eq(MODULE), any(ActionExecutionPolicy.class), any(Criteria.class), any());
    }

    @SuppressWarnings("unchecked")
    private IDatabaseOperations<Object> operations() {
        IDatabaseOperations<Object> operations = mock(IDatabaseOperations.class);
        when(operations.getDBInfo()).thenReturn(new DBInfo("POSTGRESQL").setName("muyun_test"));
        when(operations.getDefaultSchemaName()).thenReturn(SCHEMA);
        when(operations.patchUpdateItemWhere(anyString(), anyString(), anyMap(), anyMap(), anyString())).thenReturn(1);
        return operations;
    }

    private DynamicRecordService service(IDatabaseOperations<Object> operations, EntityDefinition entity) {
        return service(operations, entity, RuntimeEventPublisher.noop());
    }

    private DynamicRecordService serviceWithPolicy(IDatabaseOperations<Object> operations,
                                                   EntityDefinition entity,
                                                   ActionExecutionPolicyService policyService) {
        DynamicRecordRuntime runtime = DynamicRecordRuntime.builder(operations)
                .registry(new DynamicModuleRegistry())
                .fieldValueValidator(DynamicFieldValueValidator.NONE)
                .eventPublisher(RuntimeEventPublisher.noop())
                .build()
                .register(new ModuleDefinition(MODULE, "Contract", List.of(entity)));
        return new DynamicRecordService(
                runtime,
                policyService,
                new net.ximatai.muyun.spring.common.platform.AllowAllDataScopeCriteriaService()
        );
    }

    private DynamicRecordService service(IDatabaseOperations<Object> operations,
                                         EntityDefinition entity,
                                         RuntimeEventPublisher eventPublisher) {
        return service(operations, entity, eventPublisher,
                new net.ximatai.muyun.spring.common.platform.AllowAllDataScopeCriteriaService());
    }

    private DynamicRecordService service(IDatabaseOperations<Object> operations,
                                         EntityDefinition entity,
                                         RuntimeEventPublisher eventPublisher,
                                         DataScopeCriteriaService dataScopeCriteriaService) {
        DynamicRecordRuntime runtime = DynamicRecordRuntime.builder(operations)
                .registry(new DynamicModuleRegistry())
                .fieldValueValidator(DynamicFieldValueValidator.NONE)
                .eventPublisher(eventPublisher)
                .build()
                .register(new ModuleDefinition(MODULE, "Contract", List.of(entity)));
        return new DynamicRecordService(
                runtime,
                new net.ximatai.muyun.spring.common.platform.AllowAllActionExecutionPolicyService(),
                dataScopeCriteriaService
        );
    }

    private DynamicRecordService serviceWithCoordinator(IDatabaseOperations<Object> operations,
                                                        EntityDefinition entity,
                                                        DynamicRecordMutationCoordinator coordinator) {
        DynamicRecordRuntime runtime = DynamicRecordRuntime.builder(operations)
                .registry(new DynamicModuleRegistry())
                .fieldValueValidator(DynamicFieldValueValidator.NONE)
                .eventPublisher(RuntimeEventPublisher.noop())
                .build()
                .register(new ModuleDefinition(MODULE, "Contract", List.of(entity)));
        return new DynamicRecordService(
                runtime,
                new net.ximatai.muyun.spring.common.platform.AllowAllActionExecutionPolicyService(),
                new net.ximatai.muyun.spring.common.platform.AllowAllDataScopeCriteriaService(),
                coordinator
        );
    }

    private DynamicRecordService associationService(IDatabaseOperations<Object> operations) {
        return associationService(operations, new net.ximatai.muyun.spring.common.platform.AllowAllDataScopeCriteriaService());
    }

    private DynamicRecordService associationService(IDatabaseOperations<Object> operations,
                                                    DataScopeCriteriaService dataScopeCriteriaService) {
        EntityRelationDefinition relation = EntityRelationDefinition.child("lines", "contract", "line", "contractId");
        EntityReferenceDefinition reference = EntityReferenceDefinition.to("line", "contractId",
                ReferenceTarget.of(MODULE, "contract"));
        return associationService(operations,
                List.of(relation),
                List.of(reference),
                List.of(
                        EntityAssociationViewDefinition.childRelation("lines", "contract", MODULE, "line", "lines"),
                        EntityAssociationViewDefinition.reference("contractId", "line", MODULE, "contract",
                                "contractId")
                ),
                dataScopeCriteriaService);
    }

    private DynamicRecordService associationService(IDatabaseOperations<Object> operations,
                                                    List<EntityRelationDefinition> relations,
                                                    List<EntityReferenceDefinition> references,
                                                    List<EntityAssociationViewDefinition> associationViews,
                                                    DataScopeCriteriaService dataScopeCriteriaService) {
        ModuleDefinition module = ModuleDefinition.builder(MODULE, "Contract")
                .entities(List.of(contractEntity(), lineEntity()))
                .relations(relations)
                .references(references)
                .views(List.of())
                .associationViews(associationViews)
                .actions(List.of())
                .build();
        return new DynamicRecordService(
                new DynamicRecordRuntime(operations).register(module),
                new net.ximatai.muyun.spring.common.platform.AllowAllActionExecutionPolicyService(),
                dataScopeCriteriaService
        );
    }

    private DynamicRecordService actionService(IDatabaseOperations<Object> operations) {
        return actionService(operations, RuntimeEventPublisher.noop());
    }

    private DynamicRecordService actionService(IDatabaseOperations<Object> operations, RuntimeEventPublisher eventPublisher) {
        return actionService(operations, eventPublisher, null,
                new EntityActionDefinition("contract", "submit", "提交", true)
                        .availableWhen("{status} == 'draft'", "只有草稿合同可以提交"));
    }

    private DynamicRecordService actionService(IDatabaseOperations<Object> operations,
                                               RuntimeEventPublisher eventPublisher,
                                               DynamicActionExecutor executor) {
        return actionService(operations, eventPublisher, executor, submitActionWithExecutorKey("contractSubmit"));
    }

    private DynamicRecordService actionService(IDatabaseOperations<Object> operations,
                                               RuntimeEventPublisher eventPublisher,
                                               DynamicActionExecutor executor,
                                               EntityActionDefinition submitAction) {
        return actionService(operations, eventPublisher, executor, submitAction,
                DynamicActionTransactionOperator.none());
    }

    private DynamicRecordService actionService(IDatabaseOperations<Object> operations,
                                               RuntimeEventPublisher eventPublisher,
                                               DynamicActionExecutor executor,
                                               EntityActionDefinition submitAction,
                                               DynamicActionTransactionOperator transactionOperator) {
        return actionService(operations, eventPublisher, executor, submitAction, transactionOperator, null);
    }

    private DynamicRecordService actionService(IDatabaseOperations<Object> operations,
                                               RuntimeEventPublisher eventPublisher,
                                               DynamicActionExecutor executor,
                                               EntityActionDefinition submitAction,
                                               DynamicActionTransactionOperator transactionOperator,
                                               ActionExecutionPolicyService actionExecutionPolicyService) {
        return actionService(operations, eventPublisher, executor, submitAction,
                transactionOperator, actionExecutionPolicyService,
                new net.ximatai.muyun.spring.common.platform.AllowAllDataScopeCriteriaService());
    }

    private DynamicRecordService actionService(IDatabaseOperations<Object> operations,
                                               RuntimeEventPublisher eventPublisher,
                                               DynamicActionExecutor executor,
                                               EntityActionDefinition submitAction,
                                               DynamicActionTransactionOperator transactionOperator,
                                               ActionExecutionPolicyService actionExecutionPolicyService,
                                               DataScopeCriteriaService dataScopeCriteriaService) {
        ModuleDefinition module = ModuleDefinition.builder(MODULE, "Contract")
                .entities(List.of(submitAction.dataAuth() ? dataScopedActionEntity() : actionEntity()))
                .relations(List.of())
                .references(List.of())
                .views(List.of())
                .actions(List.of(submitAction))
                .build();
        DynamicActionExecutorRegistry executorRegistry = executor == null
                ? DynamicActionExecutorRegistry.empty()
                : new DynamicActionExecutorRegistry(List.of(executor));
        return new DynamicRecordService(DynamicRecordRuntime.builder(operations)
                .registry(new DynamicModuleRegistry())
                .fieldValueValidator(DynamicFieldValueValidator.NONE)
                .eventPublisher(eventPublisher)
                .actionExecutorRegistry(executorRegistry)
                .actionTransactionOperator(transactionOperator)
                .build()
                .register(module), actionExecutionPolicyService == null
                ? new net.ximatai.muyun.spring.common.platform.AllowAllActionExecutionPolicyService()
                : actionExecutionPolicyService,
                dataScopeCriteriaService);
    }

    private DynamicRecordService actionServiceWithActions(IDatabaseOperations<Object> operations,
                                                          EntityActionDefinition... actions) {
        return actionServiceWithActions(operations, RuntimeEventPublisher.noop(), actions);
    }

    private DynamicRecordService actionServiceWithActions(IDatabaseOperations<Object> operations,
                                                          RuntimeEventPublisher eventPublisher,
                                                          EntityActionDefinition... actions) {
        ModuleDefinition module = ModuleDefinition.builder(MODULE, "Contract")
                .entities(List.of(actionEntity()))
                .relations(List.of())
                .references(List.of())
                .views(List.of())
                .actions(List.of(actions))
                .build();
        return new DynamicRecordService(DynamicRecordRuntime.builder(operations)
                .registry(new DynamicModuleRegistry())
                .fieldValueValidator(DynamicFieldValueValidator.NONE)
                .eventPublisher(eventPublisher)
                .build()
                .register(module));
    }

    private DynamicRecordService actionRuleService(IDatabaseOperations<Object> operations,
                                                   RuntimeEventPublisher eventPublisher,
                                                   DynamicActionExecutor executor) {
        return actionRuleService(operations, eventPublisher, executor, submitActionWithExecutorKey("contractSubmit"));
    }

    private DynamicRecordService actionRuleService(IDatabaseOperations<Object> operations,
                                                   RuntimeEventPublisher eventPublisher,
                                                   DynamicActionExecutor executor,
                                                   EntityActionDefinition action) {
        ModuleDefinition module = ModuleDefinition.builder(MODULE, "Contract")
                .entities(List.of(actionRuleEntity()))
                .relations(List.of())
                .references(List.of())
                .views(List.of())
                .actions(List.of(action))
                .build();
        DynamicActionExecutorRegistry executorRegistry = executor == null
                ? DynamicActionExecutorRegistry.empty()
                : new DynamicActionExecutorRegistry(List.of(executor));
        return new DynamicRecordService(DynamicRecordRuntime.builder(operations)
                .registry(new DynamicModuleRegistry())
                .fieldValueValidator(DynamicFieldValueValidator.NONE)
                .eventPublisher(eventPublisher)
                .actionExecutorRegistry(executorRegistry)
                .build()
                .register(module));
    }

    private DynamicRecordService childActionRuleService(IDatabaseOperations<Object> operations,
                                                        RuntimeEventPublisher eventPublisher,
                                                        DynamicActionExecutor executor,
                                                        String expectedCode) {
        ModuleDefinition module = ModuleDefinition.builder(MODULE, "Contract")
                .entities(List.of(actionChildRuleEntity(expectedCode), lineEntity()))
                .relations(List.of(EntityRelationDefinition.child("lines", "contract", "line", "contractId")))
                .references(List.of())
                .views(List.of())
                .actions(List.of(submitActionWithoutAvailability("contractSubmit")))
                .build();
        DynamicActionExecutorRegistry executorRegistry = executor == null
                ? DynamicActionExecutorRegistry.empty()
                : new DynamicActionExecutorRegistry(List.of(executor));
        return new DynamicRecordService(DynamicRecordRuntime.builder(operations)
                .registry(new DynamicModuleRegistry())
                .fieldValueValidator(DynamicFieldValueValidator.NONE)
                .eventPublisher(eventPublisher)
                .actionExecutorRegistry(executorRegistry)
                .build()
                .register(module));
    }

    private DynamicRecordService childActionWithoutRuleService(IDatabaseOperations<Object> operations,
                                                               RuntimeEventPublisher eventPublisher,
                                                               DynamicActionExecutor executor) {
        ModuleDefinition module = ModuleDefinition.builder(MODULE, "Contract")
                .entities(List.of(actionEntity(), lineEntity()))
                .relations(List.of(EntityRelationDefinition.child("lines", "contract", "line", "contractId")))
                .references(List.of())
                .views(List.of())
                .actions(List.of(submitActionWithoutAvailability("contractSubmit")))
                .build();
        DynamicActionExecutorRegistry executorRegistry = executor == null
                ? DynamicActionExecutorRegistry.empty()
                : new DynamicActionExecutorRegistry(List.of(executor));
        return new DynamicRecordService(DynamicRecordRuntime.builder(operations)
                .registry(new DynamicModuleRegistry())
                .fieldValueValidator(DynamicFieldValueValidator.NONE)
                .eventPublisher(eventPublisher)
                .actionExecutorRegistry(executorRegistry)
                .build()
                .register(module));
    }

    private DynamicRecordService childEntityActionService(DynamicActionExecutor executor) {
        ModuleDefinition module = ModuleDefinition.builder(MODULE, "Contract")
                .entities(List.of(actionEntity(), lineEntity()))
                .relations(List.of())
                .references(List.of())
                .views(List.of())
                .actions(List.of(new EntityActionDefinition("line", "approveLine", "审核明细", true,
                        EntityActionLevel.RECORD, EntityActionCategory.CUSTOM, null, true, false,
                        null, null, null, null, EntityActionExecutorType.SERVICE, "contractSubmit")))
                .build();
        return new DynamicRecordService(DynamicRecordRuntime.builder(operations())
                .registry(new DynamicModuleRegistry())
                .fieldValueValidator(DynamicFieldValueValidator.NONE)
                .eventPublisher(RuntimeEventPublisher.noop())
                .actionExecutorRegistry(new DynamicActionExecutorRegistry(List.of(executor)))
                .build()
                .register(module));
    }

    private EntityActionDefinition submitActionWithExecutorKey(String executorKey) {
        return new EntityActionDefinition("contract", "submit", "提交", true, EntityActionLevel.RECORD,
                EntityActionCategory.CUSTOM, null, null, null, null,
                "{status} == 'draft'", "只有草稿合同可以提交",
                EntityActionExecutorType.SERVICE, executorKey
        );
    }

    private EntityActionDefinition submitActionWithoutAvailability(String executorKey) {
        return new EntityActionDefinition("contract", "submit", "提交", true, EntityActionLevel.RECORD,
                EntityActionCategory.CUSTOM, null, null, null, null,
                null, null, EntityActionExecutorType.SERVICE, executorKey
        );
    }

    private EntityActionDefinition dataAuthSubmitAction(String executorKey) {
        return new EntityActionDefinition("contract", "submit", "提交", true, EntityActionLevel.RECORD,
                EntityActionCategory.CUSTOM, null, true, true, null,
                null, null, EntityActionExecutorType.SERVICE, executorKey
        );
    }

    private EntityActionDefinition dataAuthListAction(String executorKey) {
        return new EntityActionDefinition("contract", "submit", "提交", true, EntityActionLevel.LIST,
                EntityActionCategory.CUSTOM, null, true, true, null,
                null, null, EntityActionExecutorType.SERVICE, executorKey
        );
    }

    private DynamicRecordService referenceResolvingService(IDatabaseOperations<Object> operations) {
        return referenceResolvingService(operations,
                new net.ximatai.muyun.spring.common.platform.AllowAllDataScopeCriteriaService());
    }

    private DynamicRecordService referenceResolvingService(IDatabaseOperations<Object> operations,
                                                           DataScopeCriteriaService dataScopeCriteriaService) {
        return referenceResolvingService(operations, referenceEntity(), lineEntity(),
                EntityReferenceDefinition.to("line", "contractId", ReferenceTarget.of(MODULE, "contract"))
                        .withProjection("title", "contractTitle")
                        .withProjection("code", "contractCode"),
                dataScopeCriteriaService);
    }

    private DynamicRecordService referenceResolvingService(IDatabaseOperations<Object> operations,
                                                           EntityDefinition referenceEntity,
                                                           EntityDefinition sourceEntity,
                                                           EntityReferenceDefinition reference) {
        return referenceResolvingService(operations, referenceEntity, sourceEntity, reference,
                new net.ximatai.muyun.spring.common.platform.AllowAllDataScopeCriteriaService());
    }

    private DynamicRecordService referenceResolvingService(IDatabaseOperations<Object> operations,
                                                           EntityDefinition referenceEntity,
                                                           EntityDefinition sourceEntity,
                                                           EntityReferenceDefinition reference,
                                                           DataScopeCriteriaService dataScopeCriteriaService) {
        ModuleDefinition module = ModuleDefinition.builder(MODULE, "Contract")
                .entities(List.of(referenceEntity, sourceEntity))
                .relations(List.of())
                .references(List.of(reference))
                .build();
        return new DynamicRecordService(
                new DynamicRecordRuntime(operations).register(module),
                new net.ximatai.muyun.spring.common.platform.AllowAllActionExecutionPolicyService(),
                dataScopeCriteriaService
        );
    }

    private EntityDefinition contractEntity() {
        return new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                List.of(
                        FieldDefinition.string("code", "Code").length(64).required().queryable(),
                        FieldDefinition.decimal("amount", "Amount").precision(18, 2)
                )
        );
    }

    private EntityDefinition actionEntity() {
        return new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                List.of(
                        FieldDefinition.string("code", "Code").length(64).required(),
                        FieldDefinition.string("status", "Status").length(32)
                )
        );
    }

    private EntityDefinition dataScopedActionEntity() {
        return actionEntity().withCapabilities(EntityCapability.CRUD, EntityCapability.DATA_SCOPE);
    }

    private EntityDefinition dataScopedSortableEntity() {
        return sortableEntity().withCapabilities(EntityCapability.CRUD, EntityCapability.SORT, EntityCapability.DATA_SCOPE);
    }

    private EntityDefinition dataScopedTreeEntity() {
        return treeEntity().withCapabilities(EntityCapability.CRUD, EntityCapability.TREE, EntityCapability.SORT,
                EntityCapability.DATA_SCOPE);
    }

    private EntityDefinition dataScopedEnabledEntity() {
        return enabledEntity().withCapabilities(EntityCapability.CRUD, EntityCapability.ENABLE, EntityCapability.DATA_SCOPE);
    }

    private EntityDefinition actionRuleEntity() {
        return new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                List.of(
                        FieldDefinition.string("code", "Code").length(64).required(),
                        FieldDefinition.string("status", "Status").length(32),
                        FieldDefinition.decimal("amount", "Amount").precision(18, 2)
                )
        ).withFormulaRules(EntityFormulaRuleDefinition
                .validation("submitAmountPositive", "amount", "{amount} > 0", "提交金额必须大于0")
                .phase(FormulaRulePhase.ACTION_BEFORE_EXECUTE));
    }

    private EntityDefinition actionChildRuleEntity(String expectedCode) {
        return new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                List.of(
                        FieldDefinition.string("code", "Code").length(64),
                        FieldDefinition.string("status", "Status").length(32)
                )
        ).withFormulaRules(EntityFormulaRuleDefinition
                .validation("submitLineRequired", "lines.summary",
                        "{code} == '" + expectedCode + "' && COUNT({lines.summary}) > 0", "提交前必须填写明细")
                .phase(FormulaRulePhase.ACTION_BEFORE_EXECUTE));
    }

    private EntityDefinition dictionaryEntity() {
        return new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                List.of(
                        FieldDefinition.string("code", "Code").length(64).required(),
                        FieldDefinition.string("status", "Status").dictionary("crm", "customer_status")
                )
        );
    }

    private EntityDefinition behaviorEntity() {
        return new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                List.of(
                        FieldDefinition.string("code", "Code").length(64).required().validationRegex("C-[0-9]+"),
                        FieldDefinition.string("status", "Status").defaultValue("draft"),
                        FieldDefinition.string("serverCode", "Server Code").column("server_code").writeProtected()
                )
        );
    }

    private EntityDefinition writeProtectedEnabledEntity() {
        return new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                List.of(
                        FieldDefinition.string("code", "Code").length(64).required(),
                        FieldDefinition.enabled().writeProtected()
                )
        ).withCapabilities(EntityCapability.CRUD, EntityCapability.ENABLE);
    }

    private EntityDefinition formulaValidationEntity() {
        return new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                List.of(FieldDefinition.decimal("amount", "Amount").precision(18, 2))
        ).withFormulaRules(EntityFormulaRuleDefinition
                .validation("amountPositive", "amount", "{amount} > 0", "amount must be positive"));
    }

    private EntityDefinition formulaWarningEntity() {
        return new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                List.of(FieldDefinition.decimal("amount", "Amount").precision(18, 2))
        ).withFormulaRules(EntityFormulaRuleDefinition
                .validation("amountHighRisk", "amount", "{amount} < 1000", "amount is high")
                .severity(FormulaIssueLevel.WARNING));
    }

    private EntityDefinition sortableEntity() {
        return new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                List.of(
                        FieldDefinition.string("code", "Code").length(64).required(),
                        FieldDefinition.sortOrder()
                )
        ).withCapabilities(EntityCapability.CRUD, EntityCapability.SORT);
    }

    private EntityDefinition treeEntity() {
        return new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                List.of(
                        FieldDefinition.string("code", "Code").length(64).required(),
                        FieldDefinition.parentId(),
                        FieldDefinition.sortOrder()
                )
        ).withCapabilities(EntityCapability.TREE);
    }

    private EntityDefinition referenceEntity() {
        return new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                List.of(
                        FieldDefinition.string("code", "Code").length(64).required(),
                        FieldDefinition.titleField().required()
                )
        ).withCapabilities(EntityCapability.CRUD, EntityCapability.REFERENCE);
    }

    private EntityDefinition customerReferenceEntity() {
        return new EntityDefinition(
                "customer",
                "app_customer",
                "Customer",
                List.of(
                        FieldDefinition.string("code", "Code").length(64).required(),
                        FieldDefinition.titleField().required()
                )
        ).withCapabilities(EntityCapability.CRUD, EntityCapability.REFERENCE);
    }

    private EntityDefinition referenceEntityWithRegion() {
        return new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                List.of(
                        FieldDefinition.string("code", "Code").length(64).required(),
                        FieldDefinition.titleField().required(),
                        FieldDefinition.string("region", "Region").length(32)
                )
        ).withCapabilities(EntityCapability.CRUD, EntityCapability.REFERENCE);
    }

    private EntityDefinition lineEntity() {
        return new EntityDefinition(
                "line",
                "app_contract_line",
                "Contract Line",
                List.of(
                        FieldDefinition.string("contractId", "Contract").column("contract_id").length(32),
                        FieldDefinition.string("summary", "Summary").length(128).queryable()
                )
        );
    }

    private EntityDefinition lineEntityWithRegion() {
        return new EntityDefinition(
                "line",
                "app_contract_line",
                "Contract Line",
                List.of(
                        FieldDefinition.string("contractId", "Contract").column("contract_id").length(32),
                        FieldDefinition.string("contractRegion", "Contract Region").column("contract_region").length(32),
                        FieldDefinition.string("summary", "Summary").length(128)
                )
        );
    }

    private EntityDefinition enabledEntity() {
        return new EntityDefinition(
                "contract",
                "app_contract",
                "Contract",
                List.of(
                        FieldDefinition.string("code", "Code").length(64).required(),
                        FieldDefinition.enabled()
                )
        ).withCapabilities(EntityCapability.CRUD, EntityCapability.ENABLE);
    }

    private Map<String, Object> row(String id, String code, int version, boolean deleted) {
        return Map.of(
                "id", id,
                "code", code,
                "amount", BigDecimal.TEN,
                "deleted", deleted,
                "version", version
        );
    }

    private Map<String, Object> row(String id, String code, int version, boolean deleted, String tenantId) {
        return Map.of(
                "id", id,
                "tenant_id", tenantId,
                "code", code,
                "amount", BigDecimal.TEN,
                "deleted", deleted,
                "version", version
        );
    }

    private Map<String, Object> actionRow(String id, String code, String status) {
        return Map.of(
                "id", id,
                "code", code,
                "status", status,
                "deleted", Boolean.FALSE,
                "version", 0
        );
    }

    private Map<String, Object> actionAmountRow(String id, String code, String status, BigDecimal amount) {
        return Map.of(
                "id", id,
                "code", code,
                "status", status,
                "amount", amount,
                "deleted", Boolean.FALSE,
                "version", 0
        );
    }

    private Map<String, Object> sortableRow(String id, int sortOrder) {
        return Map.of(
                "id", id,
                "code", id.toUpperCase(),
                "sort_order", sortOrder,
                "deleted", Boolean.FALSE,
                "version", 0
        );
    }

    private Map<String, Object> treeRow(String id, String parentId, int sortOrder) {
        return Map.of(
                "id", id,
                "code", id.toUpperCase(),
                "parent_id", parentId,
                "sort_order", sortOrder,
                "deleted", Boolean.FALSE,
                "version", 0
        );
    }

    private Map<String, Object> referenceRow(String id, String name) {
        return Map.of(
                "id", id,
                "code", id.toUpperCase(),
                "title", name,
                "deleted", Boolean.FALSE,
                "version", 0
        );
    }

    private Map<String, Object> referenceRow(String id, String name, String region) {
        return Map.of(
                "id", id,
                "code", id.toUpperCase(),
                "title", name,
                "region", region,
                "deleted", Boolean.FALSE,
                "version", 0
        );
    }

    private Map<String, Object> lineRow(String id, String contractId, String summary) {
        return Map.of(
                "id", id,
                "contract_id", contractId,
                "summary", summary,
                "deleted", Boolean.FALSE,
                "version", 0
        );
    }

    private Map<String, Object> enabledRow(String id, boolean enabled) {
        return Map.of(
                "id", id,
                "code", id.toUpperCase(),
                "enabled", enabled,
                "deleted", Boolean.FALSE,
                "version", 0
        );
    }

    private Map<String, Object> behaviorRow(String id, String code, String serverCode) {
        return Map.of(
                "id", id,
                "code", code,
                "status", "draft",
                "server_code", serverCode,
                "deleted", Boolean.FALSE,
                "version", 0
        );
    }

    private void stubSortableRows(IDatabaseOperations<Object> operations) {
        when(operations.query(anyString(), anyMap())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = invocation.getArgument(1);
            if (params.containsValue("first") && params.containsValue("second")) {
                return List.of(sortableRow("first", 1), sortableRow("second", 2));
            }
            if (params.containsValue("first")) {
                return List.of(sortableRow("first", 1));
            }
            if (params.containsValue("second")) {
                return List.of(sortableRow("second", 2));
            }
            if (params.containsValue("third")) {
                return List.of(sortableRow("third", 3));
            }
            return List.of(
                    sortableRow("first", 1),
                    sortableRow("second", 2),
                    sortableRow("third", 3)
            );
        });
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Map<String, Object>> mapCaptor() {
        return ArgumentCaptor.forClass(Map.class);
    }

    private void assertActionTrace(RuntimeEvent mutation,
                                   RuntimeEvent action,
                                   RuntimeEventType mutationType,
                                   String actionCode) {
        assertThat(mutation.eventType()).isEqualTo(mutationType);
        assertThat(mutation.mutationSource()).isEqualTo(RuntimeMutationSource.ACTION);
        assertThat(action.eventType()).isEqualTo(RuntimeEventType.ACTION_EXECUTED);
        assertThat(action.actionCode()).isEqualTo(actionCode);
        assertThat(action.traceId()).isEqualTo(mutation.traceId());
    }

    private static final class CollectingRuntimeEventPublisher implements RuntimeEventPublisher {
        private final List<RuntimeEvent> events = new ArrayList<>();

        @Override
        public void publish(RuntimeEvent event) {
            events.add(event);
        }

        List<RuntimeEvent> events() {
            return events;
        }
    }

    private static final class RecordingActionTransactionOperator implements DynamicActionTransactionOperator {
        private int calls;
        private int committed;
        private int rolledBack;

        @Override
        public Object execute(DynamicActionExecutionContext context, Supplier<?> action) {
            calls++;
            try {
                Object result = action.get();
                committed++;
                return result;
            } catch (RuntimeException e) {
                rolledBack++;
                throw e;
            }
        }

        int calls() {
            return calls;
        }

        int committed() {
            return committed;
        }

        int rolledBack() {
            return rolledBack;
        }
    }

    private static final class SpringLikeActionTransactionOperator implements DynamicActionTransactionOperator {
        private int committed;

        @Override
        public Object execute(DynamicActionExecutionContext context, Supplier<?> action) {
            boolean actionCompleted = false;
            try {
                TransactionSynchronizationManager.initSynchronization();
                TransactionSynchronizationManager.setActualTransactionActive(true);
                Object result = action.get();
                actionCompleted = true;
                committed++;
                TransactionSynchronizationManager.getSynchronizations()
                        .forEach(TransactionSynchronization::afterCommit);
                return result;
            } catch (RuntimeException e) {
                if (!actionCompleted && TransactionSynchronizationManager.isSynchronizationActive()) {
                    TransactionSynchronizationManager.getSynchronizations()
                            .forEach(synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
                }
                throw e;
            } finally {
                if (TransactionSynchronizationManager.isSynchronizationActive()) {
                    TransactionSynchronizationManager.clearSynchronization();
                }
                TransactionSynchronizationManager.setActualTransactionActive(false);
            }
        }

        int committed() {
            return committed;
        }
    }

    private static final class ThrowingActionEventPublisher implements RuntimeEventPublisher {
        private final List<RuntimeEventType> eventTypes = new ArrayList<>();

        @Override
        public void publish(RuntimeEvent event) {
            eventTypes.add(event.eventType());
            if (event.eventType() == RuntimeEventType.ACTION_EXECUTED) {
                throw new IllegalStateException("event publish failed");
            }
        }

        List<RuntimeEventType> eventTypes() {
            return eventTypes;
        }
    }

    private static final class ThrowingMutationEventPublisher implements RuntimeEventPublisher {
        private final List<RuntimeEventType> eventTypes = new ArrayList<>();

        @Override
        public void publish(RuntimeEvent event) {
            eventTypes.add(event.eventType());
            if (event.eventType() == RuntimeEventType.AFTER_CREATE) {
                throw new IllegalStateException("mutation event publish failed");
            }
        }

        List<RuntimeEventType> eventTypes() {
            return eventTypes;
        }
    }

    private static final class RecordingActionExecutor implements DynamicActionExecutor {
        private DynamicActionExecutionContext context;
        private DynamicActionExecutionRequest request;

        @Override
        public String executorKey() {
            return "contractSubmit";
        }

        @Override
        public Object execute(DynamicActionExecutionContext context, DynamicActionExecutionRequest request) {
            this.context = context;
            this.request = request;
            return "submitted:" + request.record().getId();
        }

        DynamicActionExecutionContext context() {
            return context;
        }

        DynamicActionExecutionRequest request() {
            return request;
        }
    }

    private static final class RecordingCriteriaActionExecutor implements DynamicActionExecutor {
        private DynamicActionExecutionRequest request;
        private boolean tenantFilterBypassed;

        @Override
        public String executorKey() {
            return "contractSubmit";
        }

        @Override
        public Object execute(DynamicActionExecutionContext context, DynamicActionExecutionRequest request) {
            this.request = request;
            this.tenantFilterBypassed = TenantContext.tenantFilterBypassed();
            return DynamicActionResultBody.changedCount(0);
        }
    }

    private record TestActionExecutor(String executorKey) implements DynamicActionExecutor {
        @Override
        public Object execute(DynamicActionExecutionContext context, DynamicActionExecutionRequest request) {
            return null;
        }
    }

    private static final class MismatchedDefinitionActionExecutor implements DynamicActionExecutor {
        @Override
        public String executorKey() {
            return "contractSubmit";
        }

        @Override
        public DynamicActionExecutorDefinition definition() {
            return DynamicActionExecutorDefinition.internal("anotherExecutor");
        }

        @Override
        public Object execute(DynamicActionExecutionContext context, DynamicActionExecutionRequest request) {
            return null;
        }
    }

    private static final class RecordingActionPolicyService implements ActionExecutionPolicyService {
        private ActionExecutionContext context;

        @Override
        public void requireAuthorized(ActionExecutionContext context) {
            this.context = context;
        }
    }

    private static final class FailingDataScopeCriteriaService implements DataScopeCriteriaService {
        @Override
        public DataScopeCriteriaResult resolveReadScope(String moduleAlias,
                                                        String actionCode,
                                                        Criteria criteria,
                                                        java.util.Optional<CurrentUser> currentUser) {
            throw new IllegalStateException("data scope unavailable");
        }

        @Override
        public DataScopeCriteriaResult resolveReadScope(String moduleAlias,
                                                        ActionExecutionPolicy policy,
                                                        Criteria criteria,
                                                        java.util.Optional<CurrentUser> currentUser) {
            throw new IllegalStateException("data scope unavailable");
        }
    }

    private static final class WritingActionExecutor implements DynamicActionExecutor {
        @Override
        public String executorKey() {
            return "contractSubmit";
        }

        @Override
        public Object execute(DynamicActionExecutionContext context, DynamicActionExecutionRequest request) {
            throw new UnsupportedOperationException("writing action requires dynamic action operations");
        }

        @Override
        public Object execute(DynamicActionExecutionContext context,
                              DynamicActionExecutionRequest request,
                              DynamicActionOperations operations) {
            DynamicRecord record = request.record();
            record.setValue("status", "submitted");
            return DynamicActionResultBody.changedCount(operations.update(record));
        }
    }

    private static final class CrossRecordWritingActionExecutor implements DynamicActionExecutor {
        @Override
        public String executorKey() {
            return "contractSubmit";
        }

        @Override
        public Object execute(DynamicActionExecutionContext context, DynamicActionExecutionRequest request) {
            throw new UnsupportedOperationException("cross-record action requires dynamic action operations");
        }

        @Override
        public Object execute(DynamicActionExecutionContext context,
                              DynamicActionExecutionRequest request,
                              DynamicActionOperations operations) {
            DynamicRecord record = operations.newRecord();
            record.setId("hidden");
            record.setValue("code", "C-002");
            record.setValue("status", "submitted");
            return DynamicActionResultBody.changedCount(operations.update(record));
        }
    }

    private static final class VisibleOnlyDataScopeCriteriaService implements DataScopeCriteriaService {
        @Override
        public DataScopeCriteriaResult resolveReadScope(String moduleAlias,
                                                        String actionCode,
                                                        Criteria criteria,
                                                        java.util.Optional<CurrentUser> currentUser) {
            return DataScopeCriteriaResult.restricted(applyReadScope(moduleAlias, actionCode, criteria, currentUser));
        }

        @Override
        public DataScopeCriteriaResult resolveReadScope(String moduleAlias,
                                                        ActionExecutionPolicy policy,
                                                        Criteria criteria,
                                                        java.util.Optional<CurrentUser> currentUser) {
            return resolveReadScope(moduleAlias, policy.permissionActionCode(), criteria, currentUser);
        }

        @Override
        public Criteria applyReadScope(String moduleAlias,
                                       String actionCode,
                                       Criteria criteria,
                                       java.util.Optional<CurrentUser> currentUser) {
            Criteria scoped = Criteria.of();
            if (criteria != null && !criteria.isEmpty()) {
                scoped.andGroup(criteria.getRoot());
            }
            scoped.eq("id", "visible");
            return scoped;
        }
    }

    private static final class CrossTenantAllDataScopeCriteriaService implements DataScopeCriteriaService {
        @Override
        public DataScopeCriteriaResult resolveReadScope(String moduleAlias,
                                                        String actionCode,
                                                        Criteria criteria,
                                                        java.util.Optional<CurrentUser> currentUser) {
            return DataScopeCriteriaResult.crossTenantUnrestricted(criteria == null ? Criteria.of() : criteria);
        }

        @Override
        public DataScopeCriteriaResult resolveReadScope(String moduleAlias,
                                                        ActionExecutionPolicy policy,
                                                        Criteria criteria,
                                                        java.util.Optional<CurrentUser> currentUser) {
            return resolveReadScope(moduleAlias, policy.permissionActionCode(), criteria, currentUser);
        }
    }

    private static final class AllowIdsDataScopeCriteriaService implements DataScopeCriteriaService {
        private final List<String> ids;

        private AllowIdsDataScopeCriteriaService(String... ids) {
            this.ids = List.of(ids);
        }

        @Override
        public DataScopeCriteriaResult resolveReadScope(String moduleAlias,
                                                        String actionCode,
                                                        Criteria criteria,
                                                        java.util.Optional<CurrentUser> currentUser) {
            return DataScopeCriteriaResult.restricted(applyReadScope(moduleAlias, actionCode, criteria, currentUser));
        }

        @Override
        public DataScopeCriteriaResult resolveReadScope(String moduleAlias,
                                                        ActionExecutionPolicy policy,
                                                        Criteria criteria,
                                                        java.util.Optional<CurrentUser> currentUser) {
            return resolveReadScope(moduleAlias, policy.permissionActionCode(), criteria, currentUser);
        }

        @Override
        public Criteria applyReadScope(String moduleAlias,
                                       String actionCode,
                                       Criteria criteria,
                                       java.util.Optional<CurrentUser> currentUser) {
            Criteria scoped = Criteria.of();
            if (criteria != null && !criteria.isEmpty()) {
                scoped.andGroup(criteria.getRoot());
            }
            scoped.in("id", ids);
            return scoped;
        }
    }

    private static final class CrossTenantAllowIdsDataScopeCriteriaService implements DataScopeCriteriaService {
        private final AllowIdsDataScopeCriteriaService delegate;

        private CrossTenantAllowIdsDataScopeCriteriaService(String... ids) {
            this.delegate = new AllowIdsDataScopeCriteriaService(ids);
        }

        @Override
        public DataScopeCriteriaResult resolveReadScope(String moduleAlias,
                                                        String actionCode,
                                                        Criteria criteria,
                                                        java.util.Optional<CurrentUser> currentUser) {
            return DataScopeCriteriaResult.crossTenantRestricted(delegate.applyReadScope(
                    moduleAlias,
                    actionCode,
                    criteria,
                    currentUser
            ));
        }

        @Override
        public DataScopeCriteriaResult resolveReadScope(String moduleAlias,
                                                        ActionExecutionPolicy policy,
                                                        Criteria criteria,
                                                        java.util.Optional<CurrentUser> currentUser) {
            return resolveReadScope(moduleAlias, policy.permissionActionCode(), criteria, currentUser);
        }

        @Override
        public Criteria applyReadScope(String moduleAlias,
                                       String actionCode,
                                       Criteria criteria,
                                       java.util.Optional<CurrentUser> currentUser) {
            return delegate.applyReadScope(moduleAlias, actionCode, criteria, currentUser);
        }
    }

    private static final class PayloadActionExecutor implements DynamicActionExecutor {
        private DynamicActionExecutionRequest request;

        @Override
        public String executorKey() {
            return "contractSubmit";
        }

        @Override
        public Object execute(DynamicActionExecutionContext context, DynamicActionExecutionRequest request) {
            this.request = request;
            return DynamicActionResultBody.of(request.payload().get("comment"))
                    .message("已提交")
                    .withRefresh();
        }

        DynamicActionExecutionRequest request() {
            return request;
        }
    }

    private static final class FailingActionExecutor implements DynamicActionExecutor {
        @Override
        public String executorKey() {
            return "contractSubmit";
        }

        @Override
        public Object execute(DynamicActionExecutionContext context, DynamicActionExecutionRequest request) {
            throw new IllegalStateException("submit failed");
        }
    }

    private static final class IdReturningActionExecutor implements DynamicActionExecutor {
        private DynamicActionExecutionContext context;

        @Override
        public String executorKey() {
            return "contractSubmit";
        }

        @Override
        public Object execute(DynamicActionExecutionContext context, DynamicActionExecutionRequest request) {
            this.context = context;
            return "submitted:" + context.recordId();
        }

        DynamicActionExecutionContext context() {
            return context;
        }
    }
}
