package net.ximatai.muyun.spring.dynamic.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.ability.OptimisticLockException;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.CriteriaGroup;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicActionDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicAssociationRelationItem;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicAssociationRelationOverview;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicAssociationViewDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicEntityDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicReferenceDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicRelationDescriptor;
import net.ximatai.muyun.spring.dynamic.metadata.DynamicQueryOperator;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionAccessMode;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionCategory;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionExecutorType;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionLevel;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionDefinition;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.ActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.security.FieldEncryptionMode;
import net.ximatai.muyun.spring.common.security.FieldMaskingPolicy;
import net.ximatai.muyun.spring.common.security.FieldProtectionDefinition;
import net.ximatai.muyun.spring.common.security.FieldSignatureMode;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionException;
import net.ximatai.muyun.spring.dynamic.openapi.DynamicOpenApiDocument;
import net.ximatai.muyun.spring.dynamic.openapi.DynamicOpenApiGenerator;
import net.ximatai.muyun.spring.ability.reference.ReferenceCardinality;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionExecutionRequest;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionExecutionResult;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionExecutionContext;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionExecutionException;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionDialog;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionResultBody;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionAvailability;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicAssociationViewDiagnosis;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicAssociationViewDiagnosisStatus;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicEntityOperations;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicFormulaPreviewResult;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicQueryCondition;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicReferenceMatchMode;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicReferenceResolveItem;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicReferenceResolveMode;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicReferenceResolveRequest;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicReferenceResolveResponse;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicReferenceResolveStatus;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.web.CurrentUserWebFilter;
import net.ximatai.muyun.spring.web.PlatformWebExceptionHandler;
import net.ximatai.muyun.spring.web.RequestTraceWebFilter;
import net.ximatai.muyun.spring.web.TenantRequestScope;
import net.ximatai.muyun.spring.platform.web.DynamicRelationProjectionReadServiceTestFactory;
import net.ximatai.muyun.spring.platform.web.DynamicRelationProjectionReadService;
import net.ximatai.muyun.spring.platform.web.ProjectionQueryDescriptor;
import net.ximatai.muyun.spring.platform.web.ProjectionQueryFallbackReason;
import net.ximatai.muyun.spring.platform.web.ModuleExecutionPlanCatalog;
import net.ximatai.muyun.spring.platform.web.ModuleExecutionPlan;
import net.ximatai.muyun.spring.platform.web.ModuleMutationFieldValidation;
import net.ximatai.muyun.spring.platform.web.ModuleQueryFormField;
import net.ximatai.muyun.spring.platform.web.ModuleQueryTemplatePlan;
import net.ximatai.muyun.spring.platform.web.ModuleUiDescriptorCompiler;
import net.ximatai.muyun.spring.platform.web.ModuleUiDefinition;
import net.ximatai.muyun.spring.platform.web.PageTemplates;
import net.ximatai.muyun.spring.platform.web.ResolvedModuleReadField;
import net.ximatai.muyun.spring.platform.web.ResolvedModuleReadModel;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.platform.web.StaticModuleDefinitionCatalog;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.web.RequestTraceContext;
import net.ximatai.muyun.spring.platform.attachment.RecordAttachment;
import net.ximatai.muyun.spring.platform.attachment.RecordAttachmentAccess;
import net.ximatai.muyun.spring.platform.attachment.RecordAttachmentAccessService;
import net.ximatai.muyun.spring.platform.attachment.RecordAttachmentCommand;
import net.ximatai.muyun.spring.platform.attachment.RecordAttachmentService;
import net.ximatai.muyun.spring.platform.code.CodeBusinessPreviewItem;
import net.ximatai.muyun.spring.platform.code.CodeBusinessPreviewService;
import net.ximatai.muyun.spring.platform.code.CodeFieldRole;
import net.ximatai.muyun.spring.platform.duplicate.RecordDuplicateCheckResult;
import net.ximatai.muyun.spring.platform.duplicate.RecordDuplicateCheckService;
import net.ximatai.muyun.spring.platform.duplicate.RecordDuplicateMatch;
import net.ximatai.muyun.spring.platform.generation.RecordGenerationCommitResult;
import net.ximatai.muyun.spring.platform.generation.RecordGenerationDraft;
import net.ximatai.muyun.spring.platform.generation.RecordGenerationResult;
import net.ximatai.muyun.spring.platform.generation.ReferenceRecordGenerationFacade;
import net.ximatai.muyun.spring.platform.impact.RecordImpactType;
import net.ximatai.muyun.spring.platform.impact.RecordOriginContext;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldService;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControl;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlBinding;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlBindingService;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlQueryMode;
import net.ximatai.muyun.spring.platform.metadata.FieldUiControlService;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldForm;
import net.ximatai.muyun.spring.platform.metadata.RelationRole;
import net.ximatai.muyun.spring.platform.metadata.ResolvedModuleMetadataField;
import net.ximatai.muyun.spring.platform.ui.PlatformPageConfigSnapshot;
import net.ximatai.muyun.spring.platform.ui.PlatformPageConfigSnapshotService;
import net.ximatai.muyun.spring.platform.ui.PlatformQueryItemService;
import net.ximatai.muyun.spring.platform.ui.PlatformQueryTemplate;
import net.ximatai.muyun.spring.platform.ui.PlatformRecordNavigationContext;
import net.ximatai.muyun.spring.platform.ui.PlatformRecordNavigationMove;
import net.ximatai.muyun.spring.platform.ui.PlatformRecordNavigationService;
import net.ximatai.muyun.spring.platform.ui.PlatformUiClientType;
import net.ximatai.muyun.spring.platform.ui.PlatformUiConfig;
import net.ximatai.muyun.spring.platform.ui.PlatformUiConfigField;
import net.ximatai.muyun.spring.platform.ui.PlatformUiSet;
import net.ximatai.muyun.spring.platform.ui.PlatformUiSetType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DynamicRecordWebControllerTest {
    private static final String MODULE = "sales.contract";
    private static final String ENTITY = "contract";
    private final ObjectMapper objectMapper = new ObjectMapper();

    private DynamicRecordService service;
    private DynamicEntityOperations mainEntity;
    private ActiveTenantVerifier activeTenantVerifier;
    private CodeBusinessPreviewService codeBusinessPreviewService;
    private ReferenceRecordGenerationFacade referenceGenerationFacade;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(DynamicRecordService.class);
        mainEntity = mock(DynamicEntityOperations.class);
        activeTenantVerifier = mock(ActiveTenantVerifier.class);
        codeBusinessPreviewService = mock(CodeBusinessPreviewService.class);
        referenceGenerationFacade = mock(ReferenceRecordGenerationFacade.class);
        when(service.mainEntity(MODULE)).thenReturn(mainEntity);
        when(service.mainEntityAlias(MODULE)).thenReturn(ENTITY);
        when(service.reference(eq(MODULE), eq(ENTITY), anyString()))
                .thenAnswer(invocation -> reference(invocation.getArgument(2), null));
        when(mainEntity.newRecord()).thenAnswer(invocation -> new DynamicRecord(entity()));
        when(service.newRecord(MODULE, ENTITY)).thenAnswer(invocation -> new DynamicRecord(entity()));
        when(service.actionAuthorizationAvailability(eq(MODULE), anyString(), any()))
                .thenAnswer(invocation -> DynamicActionAvailability.available(invocation.getArgument(1)));
        when(service.actionAuthorizationAvailability(eq(MODULE), eq(ENTITY), anyString(), any()))
                .thenAnswer(invocation -> DynamicActionAvailability.available(invocation.getArgument(2)));
        objectMapper.registerModule(new DynamicRecordJacksonConfiguration()
                .dynamicRecordJacksonModule(service));
        mvc = MockMvcBuilders
                .standaloneSetup(controllerFixture(service, activeTenantVerifier).codePreview(codeBusinessPreviewService).generation(referenceGenerationFacade).build())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new PlatformWebExceptionHandler(), new DynamicWebExceptionHandler())
                .addFilters(new RequestTraceWebFilter(), new CurrentUserWebFilter(() -> java.util.Optional.of(
                        CurrentUser.tenantUser("user-1", "User", "tenant_a"))))
                .build();
    }

    @Test
    void shouldExposeModuleDescriptor() throws Exception {
        when(service.describe(MODULE)).thenReturn(DynamicModuleDescriptor.from(module()));

        mvc.perform(get("/{moduleAlias}/describe", MODULE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moduleAlias").value(MODULE))
                .andExpect(jsonPath("$.mainEntityAlias").value(ENTITY))
                .andExpect(jsonPath("$.entities[0].entityAlias").value(ENTITY))
                .andExpect(jsonPath("$.entities[0].fields[?(@.fieldName == 'displayCode')].storageForm")
                        .value(org.hamcrest.Matchers.contains("VIRTUAL")));
        verify(activeTenantVerifier).verifyActiveTenant("tenant_a");
    }

    @Test
    void shouldExposeDynamicOpenApiDocument() throws Exception {
        when(service.describe(MODULE)).thenReturn(DynamicModuleDescriptor.from(module()));

        mvc.perform(get("/{moduleAlias}/openapi", MODULE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").value("3.1.1"))
                .andExpect(jsonPath("$.x-muyun-module-alias").value(MODULE))
                .andExpect(jsonPath("$.paths['/sales.contract/describe'].get.operationId").isNotEmpty())
                .andExpect(jsonPath("$.paths['/sales.contract/openapi']").doesNotExist())
                .andExpect(jsonPath("$.components.schemas.ContractRecord.type").value("object"));
    }

    @Test
    void shouldPreviewBusinessCodesWithoutPersistingRecord() throws Exception {
        when(codeBusinessPreviewService.preview(eq(MODULE), eq(ENTITY), any(), eq(null), eq(null), eq(null)))
                .thenReturn(List.of(new CodeBusinessPreviewItem(
                        "rule-1",
                        "field-1",
                        "code",
                        CodeFieldRole.PRIMARY,
                        "SO-A0001",
                        null,
                        "2026-06-08T10:00:00"
                )));

        mvc.perform(post("/{moduleAlias}/code/preview", MODULE)
                        .contentType("application/json")
                        .content("""
                                {
                                  "values": {
                                    "code": "draft"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].ruleId").value("rule-1"))
                .andExpect(jsonPath("$[0].fieldName").value("code"))
                .andExpect(jsonPath("$[0].value").value("SO-A0001"))
                .andExpect(jsonPath("$[0].effectiveAt").value("2026-06-08T10:00:00"));

        ArgumentCaptor<Map<String, Object>> contextCaptor = ArgumentCaptor.forClass(Map.class);
        verify(codeBusinessPreviewService).preview(eq(MODULE), eq(ENTITY), contextCaptor.capture(), eq(null),
                eq(null), eq(null));
        assertThat(contextCaptor.getValue()).containsEntry("code", "draft");
        verify(mainEntity, times(1)).newRecord();
    }

    @Test
    void shouldPreviewFormulaWithoutPersistingRecord() throws Exception {
        DynamicRecord calculated = new DynamicRecord(entity())
                .setValue("amount", BigDecimal.valueOf(30));
        when(service.previewFormula(eq(MODULE), eq(ENTITY), any(DynamicRecord.class)))
                .thenReturn(new DynamicFormulaPreviewResult(calculated, null, List.of("amount")));

        mvc.perform(post("/{moduleAlias}/formula/preview", MODULE)
                        .contentType("application/json")
                        .content("""
                                {
                                  "record": {
                                    "values": {
                                      "code": "draft",
                                      "amount": 15
                                    }
                                  }
                                }
                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.record.values.amount").value(30))
                .andExpect(jsonPath("$.changedFields[0]").value("amount"))
                .andExpect(jsonPath("$.report.errors").isArray());

        ArgumentCaptor<DynamicRecord> recordCaptor = ArgumentCaptor.forClass(DynamicRecord.class);
        verify(service).previewFormula(eq(MODULE), eq(ENTITY), recordCaptor.capture());
        assertThat(recordCaptor.getValue().getValue("code")).isEqualTo("draft");
        assertThat(recordCaptor.getValue().getValue("amount")).isEqualTo(15);
    }

    @Test
    void shouldLeaveFormulaPreviewAuthorizationToTheRecordService() throws Exception {
        Method method = DynamicRecordWebController.class.getDeclaredMethod("previewFormula",
                String.class, DynamicFormulaPreviewRequest.class);

        ActionEndpoint endpoint = method.getAnnotation(ActionEndpoint.class);

        assertThat(endpoint).isNull();
    }

    @Test
    void shouldHideUnauthorizedActionsFromRuntimeDescriptor() throws Exception {
        when(service.describe(MODULE)).thenReturn(DynamicModuleDescriptor.from(actionModule()));
        when(service.actionAuthorizationAvailability(eq(MODULE), eq("submit"), any()))
                .thenReturn(DynamicActionAvailability.unavailable("submit", "action permission denied"));
        when(service.actionAuthorizationAvailability(eq(MODULE), eq("delete"), any()))
                .thenReturn(DynamicActionAvailability.unavailable("delete", "action permission denied"));
        when(service.actionAuthorizationAvailability(eq(MODULE), eq(ENTITY), eq("submit"), any()))
                .thenReturn(DynamicActionAvailability.unavailable("submit", "action permission denied"));
        when(service.actionAuthorizationAvailability(eq(MODULE), eq(ENTITY), eq("delete"), any()))
                .thenReturn(DynamicActionAvailability.unavailable("delete", "action permission denied"));

        mvc.perform(get("/{moduleAlias}/describe", MODULE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actions[?(@.code == 'submit')]").isEmpty())
                .andExpect(jsonPath("$.actions[?(@.code == 'delete')]").isEmpty())
                .andExpect(jsonPath("$.entities[0].actions[?(@.code == 'submit')]").isEmpty())
                .andExpect(jsonPath("$.entities[0].actions[?(@.code == 'delete')]").isEmpty())
                .andExpect(jsonPath("$.actions[?(@.code == 'view')]").isNotEmpty())
                .andExpect(jsonPath("$.entities[0].actions[?(@.code == 'view')]").isNotEmpty());
    }

    @Test
    void shouldHideUnauthorizedActionPathsFromRuntimeOpenApi() throws Exception {
        when(service.describe(MODULE)).thenReturn(DynamicModuleDescriptor.from(actionModule()));
        when(service.actionAuthorizationAvailability(eq(MODULE), eq("submit"), any()))
                .thenReturn(DynamicActionAvailability.unavailable("submit", "action permission denied"));
        when(service.actionAuthorizationAvailability(eq(MODULE), eq("delete"), any()))
                .thenReturn(DynamicActionAvailability.unavailable("delete", "action permission denied"));
        when(service.actionAuthorizationAvailability(eq(MODULE), eq(ENTITY), eq("submit"), any()))
                .thenReturn(DynamicActionAvailability.unavailable("submit", "action permission denied"));
        when(service.actionAuthorizationAvailability(eq(MODULE), eq(ENTITY), eq("delete"), any()))
                .thenReturn(DynamicActionAvailability.unavailable("delete", "action permission denied"));

        mvc.perform(get("/{moduleAlias}/openapi", MODULE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/sales.contract/submit/{recordId}']").doesNotExist())
                .andExpect(jsonPath("$.paths['/sales.contract/delete/{id}']").doesNotExist())
                .andExpect(jsonPath("$.paths['/sales.contract/view/{id}'].get").exists());
    }

    @Test
    void shouldDeclareViewPermissionForDynamicDescriptionAndOpenApi() throws Exception {
        Method describe = DynamicRecordWebController.class.getDeclaredMethod("describeModule", String.class);
        Method openApi = DynamicRecordWebController.class.getDeclaredMethod("openApi", String.class);

        assertThat(describe.getAnnotation(ActionEndpoint.class).value()).isEqualTo(PlatformAction.VIEW);
        assertThat(openApi.getAnnotation(ActionEndpoint.class).value()).isEqualTo(PlatformAction.VIEW);
    }

    @Test
    void shouldNotCaptureRootFileLikePath() throws Exception {
        mvc.perform(get("/openapi.json"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(service);
    }

    @Test
    void shouldAllowStaticControllerToTakeOverSameAliasUrl() throws Exception {
        MockMvc takeoverMvc = MockMvcBuilders
                .standaloneSetup(controller(service, activeTenantVerifier),
                        new StaticContractController())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new PlatformWebExceptionHandler(), new DynamicWebExceptionHandler())
                .addFilters(new CurrentUserWebFilter(() -> java.util.Optional.of(
                        CurrentUser.tenantUser("user-1", "User", "tenant_a"))))
                .build();

        takeoverMvc.perform(post("/{moduleAlias}/query", MODULE)
                        .contentType("application/json")
                        .content(json(Map.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("static"));
        verifyNoInteractions(service);

        DynamicRecord record = new DynamicRecord(entity()).setValue("code", "C-001");
        record.setId("contract-1");
        when(service.mainEntity(MODULE)).thenReturn(mainEntity);
        when(mainEntity.select("contract-1")).thenReturn(record);

        takeoverMvc.perform(get("/{moduleAlias}/view/{recordId}", MODULE, "contract-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("contract-1"));
    }

    @Test
    void shouldCreateAndUpdateMainEntityThroughAliasRootContract() throws Exception {
        DynamicRecord created = new DynamicRecord(entity()).setValue("code", "C-001").setValue("amount", 12);
        created.setId("contract-1");
        DynamicRecord updated = new DynamicRecord(entity()).setValue("amount", BigDecimal.TEN);
        updated.setId("contract-1");
        updated.setVersion(4);
        when(service.relations(MODULE)).thenReturn(List.of(
                new DynamicRelationDescriptor("lines", ENTITY, "contract_line", "contractId", false, false)
        ));
        when(service.newRecord(MODULE, "contract_line")).thenAnswer(invocation -> new DynamicRecord(lineEntity()));
        when(mainEntity.insert(any(DynamicRecord.class))).thenReturn("contract-1");
        when(mainEntity.select("contract-1")).thenReturn(created, updated);
        when(mainEntity.update(any(DynamicRecord.class))).thenReturn(1);

        mvc.perform(post("/{moduleAlias}/insert", MODULE)
                        .contentType("application/json")
                        .content(json(Map.of(
                                "values", Map.of("code", "C-001", "amount", 12),
                                "children", Map.of("lines", List.of(Map.of(
                                        "values", Map.of("lineNo", "L-001", "lineAmount", 7))))))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("contract-1"))
                .andExpect(jsonPath("$.values.code").value("C-001"));

        ArgumentCaptor<DynamicRecord> createRecord = ArgumentCaptor.forClass(DynamicRecord.class);
        verify(mainEntity).insert(createRecord.capture());
        assertThat(createRecord.getValue().getValue("code")).isEqualTo("C-001");
        assertThat(createRecord.getValue().getValue("amount")).isEqualTo(BigDecimal.valueOf(12));
        assertThat(createRecord.getValue().getChildren("lines")).singleElement()
                .satisfies(line -> {
                    assertThat(line.getValue("lineNo")).isEqualTo("L-001");
                    assertThat(line.getValue("lineAmount")).isEqualTo(BigDecimal.valueOf(7));
                });

        mvc.perform(post("/{moduleAlias}/update/{recordId}", MODULE, "contract-1")
                        .contentType("application/json")
                        .content(json(Map.of("version", 3, "values", Map.of("amount", BigDecimal.TEN)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("contract-1"))
                .andExpect(jsonPath("$.version").value(4));

        ArgumentCaptor<DynamicRecord> updateRecord = ArgumentCaptor.forClass(DynamicRecord.class);
        verify(mainEntity).update(updateRecord.capture());
        assertThat(updateRecord.getValue().getId()).isEqualTo("contract-1");
        assertThat(updateRecord.getValue().getVersion()).isEqualTo(3);
        assertThat(updateRecord.getValue().getValue("amount")).isEqualTo(BigDecimal.TEN);
    }

    @Test
    void shouldPreserveLosslessLongAndDecimalTextWireValuesThroughDynamicRecordHttpContract() throws Exception {
        EntityDefinition numericEntity = new EntityDefinition(ENTITY, "sales_contract", "Contract", List.of(
                FieldDefinition.string("code", "Code").length(64).required(),
                FieldDefinition.longInteger("externalSequence", "External Sequence").column("external_sequence"),
                FieldDefinition.decimal("amount", "Amount").precision(18, 2)
        ));
        DynamicRecord created = new DynamicRecord(numericEntity)
                .setValue("code", "C-001")
                .setValue("externalSequence", 9007199254740993L)
                .setValue("amount", new BigDecimal("0.123456789012345678"));
        created.setId("contract-1");
        created.setVersion(1);
        when(mainEntity.newRecord()).thenAnswer(invocation -> new DynamicRecord(numericEntity));
        when(mainEntity.insert(any(DynamicRecord.class))).thenReturn("contract-1");
        when(mainEntity.update(any(DynamicRecord.class))).thenReturn(1);
        when(mainEntity.select("contract-1")).thenReturn(created);

        mvc.perform(post("/{moduleAlias}/insert", MODULE)
                        .contentType("application/json")
                        .content("""
                                {
                                  "values": {
                                    "code": "C-001",
                                    "externalSequence": "9007199254740993",
                                    "amount": "0.123456789012345678"
                                  }
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.values.externalSequence").value("9007199254740993"))
                .andExpect(jsonPath("$.values.amount").value("0.123456789012345678"));

        ArgumentCaptor<DynamicRecord> captured = ArgumentCaptor.forClass(DynamicRecord.class);
        verify(mainEntity).insert(captured.capture());
        assertThat(captured.getValue().getValue("externalSequence")).isEqualTo(9007199254740993L);
        assertThat((BigDecimal) captured.getValue().getValue("amount"))
                .isEqualByComparingTo(new BigDecimal("0.123456789012345678"));

        mvc.perform(get("/{moduleAlias}/view/{recordId}", MODULE, "contract-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values.externalSequence").value("9007199254740993"))
                .andExpect(jsonPath("$.values.amount").value("0.123456789012345678"));

        mvc.perform(post("/{moduleAlias}/update/{recordId}", MODULE, "contract-1")
                        .contentType("application/json")
                        .content("""
                                {
                                  "version": 1,
                                  "values": {
                                    "externalSequence": "9007199254740993",
                                    "amount": "9999999999999999.99"
                                  }
                                }
                                """))
                .andExpect(status().isOk());
        ArgumentCaptor<DynamicRecord> updated = ArgumentCaptor.forClass(DynamicRecord.class);
        verify(mainEntity).update(updated.capture());
        assertThat(updated.getValue().getValue("externalSequence")).isEqualTo(9007199254740993L);
        assertThat((BigDecimal) updated.getValue().getValue("amount"))
                .isEqualByComparingTo(new BigDecimal("9999999999999999.99"));
    }

    @Test
    void shouldRejectVirtualFieldWhenSavingDynamicRecord() throws Exception {
        when(service.relations(MODULE)).thenReturn(List.of(
                new DynamicRelationDescriptor("lines", ENTITY, "contract_line", "contractId", false, false)
        ));
        when(service.newRecord(MODULE, "contract_line")).thenAnswer(invocation -> new DynamicRecord(lineEntity()));

        mvc.perform(post("/{moduleAlias}/insert", MODULE)
                        .contentType("application/json")
                        .content(json(Map.of("values", Map.of(
                                "code", "C-001",
                                "displayCode", "C-001 / 12"
                        )))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(PlatformErrorCodes.VALIDATION_FAILED))
                .andExpect(jsonPath("$.message").value("Virtual field cannot be saved: displayCode"));

        mvc.perform(post("/{moduleAlias}/update/{recordId}", MODULE, "contract-1")
                        .contentType("application/json")
                        .content(json(Map.of("values", Map.of("displayCode", "C-001 / 12")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(PlatformErrorCodes.VALIDATION_FAILED))
                .andExpect(jsonPath("$.message").value("Virtual field cannot be saved: displayCode"));

        mvc.perform(post("/{moduleAlias}/insert", MODULE)
                        .contentType("application/json")
                        .content("""
                                {
                                  "values": {
                                    "code": "C-001"
                                  },
                                  "children": {
                                    "lines": [
                                      {
                                        "values": {
                                          "lineNo": "L-001",
                                          "lineDisplay": "L-001 / 7"
                                        }
                                      }
                                    ]
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(PlatformErrorCodes.VALIDATION_FAILED))
                .andExpect(jsonPath("$.message").value("Virtual field cannot be saved: lines.lineDisplay"));

        verify(mainEntity, never()).insert(any(DynamicRecord.class));
        verify(mainEntity, never()).update(any(DynamicRecord.class));
    }

    @Test
    void shouldValidateUiConfigWhenSavingDynamicRecord() throws Exception {
        PlatformPageConfigSnapshotService snapshotService = mock(PlatformPageConfigSnapshotService.class);
        PlatformQueryItemService queryItemService = mock(PlatformQueryItemService.class);
        ModuleMetadataFieldService moduleFieldService = mock(ModuleMetadataFieldService.class);
        MockMvc lowCodeMvc = MockMvcBuilders
                .standaloneSetup(controllerFixture(service, activeTenantVerifier).codePreview(codeBusinessPreviewService).generation(referenceGenerationFacade).query(snapshotService, queryItemService, moduleFieldService).build())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new PlatformWebExceptionHandler(), new DynamicWebExceptionHandler())
                .addFilters(new CurrentUserWebFilter(() -> java.util.Optional.of(
                        CurrentUser.tenantUser("user-1", "User", "tenant_a"))))
                .build();
        PlatformUiConfig uiConfig = new PlatformUiConfig();
        uiConfig.setId("ui-form");
        uiConfig.setUiSetId("set-form");
        uiConfig.setClientType(PlatformUiClientType.WEB);
        uiConfig.setPublished(true);
        PlatformUiConfigField codeField = uiField("ui-form", "module-field-code");
        codeField.setRequiredOverride(true);
        PlatformUiConfigField amountField = uiField("ui-form", "module-field-amount");
        amountField.setReadOnly(true);
        PlatformUiConfigField lineNoField = uiField("ui-form", "module-field-line-no");
        lineNoField.setRequiredOverride(true);
        PlatformUiConfigField lineAmountField = uiField("ui-form", "module-field-line-amount");
        lineAmountField.setReadOnly(true);
        when(snapshotService.snapshot(MODULE)).thenReturn(new PlatformPageConfigSnapshot(
                MODULE,
                List.of(),
                List.of(uiConfig),
                List.of(codeField, amountField, lineNoField, lineAmountField),
                List.of(),
                List.of()
        ));
        when(service.relations(MODULE)).thenReturn(List.of(
                new DynamicRelationDescriptor("lines", ENTITY, "contract_line", "contractId", false, false)
        ));
        when(service.newRecord(MODULE, "contract_line")).thenAnswer(invocation -> new DynamicRecord(lineEntity()));
        when(moduleFieldService.resolve("module-field-code")).thenReturn(resolvedModuleField(
                "module-field-code", "code"));
        when(moduleFieldService.resolve("module-field-amount")).thenReturn(resolvedModuleField(
                "module-field-amount", "amount"));
        when(moduleFieldService.resolve("module-field-line-no")).thenReturn(resolvedModuleField(
                "module-field-line-no", "lineNo", RelationRole.CHILD, "lines", "string"));
        when(moduleFieldService.resolve("module-field-line-amount")).thenReturn(resolvedModuleField(
                "module-field-line-amount", "lineAmount", RelationRole.CHILD, "lines", "decimal"));
        DynamicRecord created = new DynamicRecord(entity()).setValue("code", "C-001");
        created.setId("contract-1");
        DynamicRecord saved = new DynamicRecord(entity()).setValue("code", "C-002");
        saved.setId("contract-1");
        saved.setVersion(5);
        when(mainEntity.insert(any(DynamicRecord.class))).thenReturn("contract-1");
        when(mainEntity.update(any(DynamicRecord.class))).thenReturn(1);
        when(mainEntity.select("contract-1")).thenReturn(created, saved);

        lowCodeMvc.perform(post("/{moduleAlias}/insert", MODULE)
                        .contentType("application/json")
                        .content("""
                                {
                                  "record": {
                                    "values": {
                                      "code": "C-001"
                                    }
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "dynamic record wrapper is not supported; submit the record directly"));

        lowCodeMvc.perform(post("/{moduleAlias}/insert", MODULE)
                        .contentType("application/json")
                        .content("""
                                {
                                  "uiConfigId": "ui-form",
                                  "values": {
                                    "code": "C-001"
                                  }
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("contract-1"));

        ArgumentCaptor<DynamicRecord> inserted = ArgumentCaptor.forClass(DynamicRecord.class);
        verify(mainEntity).insert(inserted.capture());
        assertThat(inserted.getValue().getValue("code")).isEqualTo("C-001");
        assertThat(inserted.getValue().mutationMetadata()).containsEntry("uiConfigId", "ui-form");

        lowCodeMvc.perform(post("/{moduleAlias}/update/{recordId}", MODULE, "contract-1")
                        .contentType("application/json")
                        .content("""
                                {
                                  "uiConfigId": "ui-form",
                                  "version": 4,
                                  "values": {
                                    "code": "C-002"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("contract-1"))
                .andExpect(jsonPath("$.version").value(5));

        ArgumentCaptor<DynamicRecord> updated = ArgumentCaptor.forClass(DynamicRecord.class);
        verify(mainEntity).update(updated.capture());
        assertThat(updated.getValue().getId()).isEqualTo("contract-1");
        assertThat(updated.getValue().getVersion()).isEqualTo(4);
        assertThat(updated.getValue().getValue("code")).isEqualTo("C-002");

        lowCodeMvc.perform(post("/{moduleAlias}/insert", MODULE)
                        .contentType("application/json")
                        .content("""
                                {
                                  "uiConfigId": "ui-form",
                                  "values": {
                                    "code": "C-001"
                                  },
                                  "children": {
                                    "lines": [
                                      {
                                        "values": {
                                          "lineNo": "L-001"
                                        }
                                      },
                                      {
                                        "values": {}
                                      }
                                    ]
                                  }
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(PlatformErrorCodes.VALIDATION_FAILED))
                .andExpect(jsonPath("$.message").value("UI required field is missing: lines.lineNo"))
                .andExpect(jsonPath("$.targets[0].moduleAlias").value(MODULE))
                .andExpect(jsonPath("$.targets[0].relationAlias").value("lines"))
                .andExpect(jsonPath("$.targets[0].fieldName").value("lineNo"))
                .andExpect(jsonPath("$.targets[0].rowIndex").value(1));

        lowCodeMvc.perform(post("/{moduleAlias}/insert", MODULE)
                        .contentType("application/json")
                        .content("""
                                {
                                  "uiConfigId": "ui-form",
                                  "values": {
                                    "code": "C-001"
                                  },
                                  "children": {
                                    "lines": [
                                      {
                                        "values": {
                                          "lineNo": "L-001",
                                          "lineAmount": 10
                                        }
                                      }
                                    ]
                                  }
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(PlatformErrorCodes.VALIDATION_FAILED))
                .andExpect(jsonPath("$.message").value("UI read-only field cannot be saved: lines.lineAmount"))
                .andExpect(jsonPath("$.targets[0].relationAlias").value("lines"))
                .andExpect(jsonPath("$.targets[0].fieldName").value("lineAmount"));

        lowCodeMvc.perform(post("/{moduleAlias}/insert", MODULE)
                        .contentType("application/json")
                        .content("""
                                {
                                  "uiConfigId": "ui-form",
                                  "values": {}
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(PlatformErrorCodes.VALIDATION_FAILED))
                .andExpect(jsonPath("$.message").value("UI required field is missing: code"))
                .andExpect(jsonPath("$.targets[0].moduleAlias").value(MODULE))
                .andExpect(jsonPath("$.targets[0].fieldName").value("code"));

        lowCodeMvc.perform(post("/{moduleAlias}/update/{recordId}", MODULE, "contract-1")
                        .contentType("application/json")
                        .content("""
                                {
                                  "uiConfigId": "ui-form",
                                  "version": 3,
                                  "values": {
                                    "code": "C-001",
                                    "amount": 10
                                  }
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(PlatformErrorCodes.VALIDATION_FAILED))
                .andExpect(jsonPath("$.message").value("UI read-only field cannot be saved: amount"))
                .andExpect(jsonPath("$.targets[0].fieldName").value("amount"));

        lowCodeMvc.perform(post("/{moduleAlias}/insert", MODULE)
                        .contentType("application/json")
                        .content("""
                                {
                                  "uiConfigId": "missing-ui",
                                  "values": {
                                    "code": "C-001"
                                  }
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(PlatformErrorCodes.CONFIG_MISSING))
                .andExpect(jsonPath("$.scope.moduleAlias").value(MODULE))
                .andExpect(jsonPath("$.message").value("UI config is not published in module snapshot: missing-ui"));
    }

    @Test
    void shouldSyncAttachmentsWhenSavingDynamicRecord() throws Exception {
        RecordAttachmentService attachmentService = mock(RecordAttachmentService.class);
        MockMvc attachmentMvc = MockMvcBuilders
                .standaloneSetup(controllerFixture(service, activeTenantVerifier).codePreview(codeBusinessPreviewService).generation(referenceGenerationFacade).attachments(attachmentService).build())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new PlatformWebExceptionHandler(), new DynamicWebExceptionHandler())
                .addFilters(new CurrentUserWebFilter(() -> java.util.Optional.of(
                        CurrentUser.tenantUser("user-1", "User", "tenant_a"))))
                .build();
        DynamicRecord created = new DynamicRecord(entity()).setValue("code", "C-001");
        created.setId("contract-1");
        when(mainEntity.insert(any(DynamicRecord.class))).thenReturn("contract-1");
        when(mainEntity.select("contract-1")).thenReturn(created);

        attachmentMvc.perform(post("/{moduleAlias}/insert", MODULE)
                        .contentType("application/json")
                        .content("""
                                {
                                  "values": {
                                    "code": "C-001"
                                  },
                                  "attachments": [
                                    {
                                      "fileId": "file-1",
                                      "displayName": "contract.pdf",
                                      "sort": 10,
                                      "remark": "signed"
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("contract-1"));

        ArgumentCaptor<DynamicRecord> inserted = ArgumentCaptor.forClass(DynamicRecord.class);
        verify(mainEntity).insert(inserted.capture());
        assertThat(inserted.getValue().getValues()).doesNotContainKey("attachments");
        assertThat(inserted.getValue().mutationMetadata()).containsKey("attachments");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<RecordAttachmentCommand>> commands = ArgumentCaptor.forClass(Collection.class);
        verify(attachmentService).replaceRecordAttachments(eq(MODULE), eq("contract-1"), commands.capture());
        RecordAttachmentCommand command = commands.getValue().iterator().next();
        assertThat(command.fileId()).isEqualTo("file-1");
        assertThat(command.displayName()).isEqualTo("contract.pdf");
        assertThat(command.sort()).isEqualTo(10);
        assertThat(command.remark()).isEqualTo("signed");
    }

    @Test
    void shouldExposeSavedRecordAttachmentMaintenanceEndpoints() throws Exception {
        RecordAttachmentService attachmentService = mock(RecordAttachmentService.class);
        MockMvc attachmentMvc = MockMvcBuilders
                .standaloneSetup(controllerFixture(service, activeTenantVerifier).codePreview(codeBusinessPreviewService).generation(referenceGenerationFacade).attachments(attachmentService).build())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new PlatformWebExceptionHandler(), new DynamicWebExceptionHandler())
                .addFilters(new CurrentUserWebFilter(() -> java.util.Optional.of(
                        CurrentUser.tenantUser("user-1", "User", "tenant_a"))))
                .build();
        RecordAttachment attachment = attachment("att-1", "file-1", "contract.pdf");
        when(attachmentService.listByRecord(MODULE, "contract-1")).thenReturn(List.of(attachment));
        when(attachmentService.deleteAttachment(MODULE, "contract-1", "att-1")).thenReturn(List.of());

        attachmentMvc.perform(post("/{moduleAlias}/view/{recordId}/attachments/query", MODULE, "contract-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("att-1"))
                .andExpect(jsonPath("$[0].fileId").value("file-1"))
                .andExpect(jsonPath("$[0].displayName").value("contract.pdf"));

        attachmentMvc.perform(post("/{moduleAlias}/view/{recordId}/attachments/add", MODULE, "contract-1")
                        .contentType("application/json")
                        .content("""
                                {
                                  "fileId": "file-2",
                                  "displayName": "supplement.pdf",
                                  "sort": 20
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fileId").value("file-1"));

        attachmentMvc.perform(post("/{moduleAlias}/view/{recordId}/attachments/update/{attachmentId}",
                        MODULE, "contract-1", "att-1")
                        .contentType("application/json")
                        .content("""
                                {
                                  "fileId": "file-1",
                                  "displayName": "contract-final.pdf",
                                  "sort": 30
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("att-1"));

        attachmentMvc.perform(post("/{moduleAlias}/view/{recordId}/attachments/delete/{attachmentId}",
                        MODULE, "contract-1", "att-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").doesNotExist());

        verify(attachmentService).add(eq(MODULE), eq("contract-1"), any(RecordAttachmentCommand.class));
        verify(attachmentService).updateAttachment(eq(MODULE), eq("contract-1"), eq("att-1"),
                any(RecordAttachmentCommand.class));
        verify(attachmentService).deleteAttachment(MODULE, "contract-1", "att-1");
    }

    @Test
    void shouldIssueAttachmentAccessTicketsThroughConfiguredAdapter() throws Exception {
        RecordAttachmentService attachmentService = mock(RecordAttachmentService.class);
        RecordAttachmentAccessService accessService = mock(RecordAttachmentAccessService.class);
        MockMvc attachmentMvc = MockMvcBuilders
                .standaloneSetup(controllerFixture(service, activeTenantVerifier).codePreview(codeBusinessPreviewService).generation(referenceGenerationFacade).attachments(attachmentService).attachmentAccess(accessService).build())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new PlatformWebExceptionHandler(), new DynamicWebExceptionHandler())
                .addFilters(new CurrentUserWebFilter(() -> java.util.Optional.of(
                        CurrentUser.tenantUser("user-1", "User", "tenant_a"))))
                .build();
        DynamicRecord visible = new DynamicRecord(entity()).setValue("code", "C-001");
        visible.setId("contract-1");
        RecordAttachment attachment = attachment("att-1", "file-1", "contract.pdf");
        when(mainEntity.select("contract-1")).thenReturn(visible);
        when(attachmentService.requireAttachment(MODULE, "contract-1", "att-1")).thenReturn(attachment);
        when(accessService.issueUploadAccess(MODULE, "contract-1")).thenReturn(new RecordAttachmentAccess(
                "UPLOAD", null, "upload-token", "/api/v1/public/files?access_token=upload-token",
                "2026-06-01T00:10:00Z", Map.of("purpose", "upload")));
        when(accessService.issuePreviewAccess(MODULE, "contract-1", attachment)).thenReturn(new RecordAttachmentAccess(
                "PREVIEW", "file-1", "preview-token", "/view/public/files/file-1?access_token=preview-token",
                "2026-06-01T00:10:00Z", Map.of()));
        when(accessService.issueDownloadAccess(MODULE, "contract-1", attachment)).thenReturn(new RecordAttachmentAccess(
                "DOWNLOAD", "file-1", "download-token", "/api/v1/public/files/file-1/download?access_token=download-token",
                "2026-06-01T00:10:00Z", Map.of()));

        attachmentMvc.perform(post("/{moduleAlias}/view/{recordId}/attachments/upload-ticket", MODULE, "contract-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("UPLOAD"))
                .andExpect(jsonPath("$.accessToken").value("upload-token"))
                .andExpect(jsonPath("$.metadata.purpose").value("upload"));

        attachmentMvc.perform(post("/{moduleAlias}/view/{recordId}/attachments/{attachmentId}/preview-ticket",
                        MODULE, "contract-1", "att-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("PREVIEW"))
                .andExpect(jsonPath("$.fileId").value("file-1"));

        attachmentMvc.perform(post("/{moduleAlias}/view/{recordId}/attachments/{attachmentId}/download-ticket",
                        MODULE, "contract-1", "att-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("DOWNLOAD"))
                .andExpect(jsonPath("$.url").value("/api/v1/public/files/file-1/download?access_token=download-token"));

        verify(attachmentService, times(2)).requireAttachment(MODULE, "contract-1", "att-1");
        verify(accessService).issueUploadAccess(MODULE, "contract-1");
        verify(accessService).issuePreviewAccess(MODULE, "contract-1", attachment);
        verify(accessService).issueDownloadAccess(MODULE, "contract-1", attachment);
    }

    @Test
    void shouldReturnAttachmentErrorCodeWhenAttachmentAccessAdapterMissing() throws Exception {
        RecordAttachmentService attachmentService = mock(RecordAttachmentService.class);
        MockMvc attachmentMvc = MockMvcBuilders
                .standaloneSetup(controllerFixture(service, activeTenantVerifier).codePreview(codeBusinessPreviewService).generation(referenceGenerationFacade).attachments(attachmentService).build())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new PlatformWebExceptionHandler(), new DynamicWebExceptionHandler())
                .addFilters(new CurrentUserWebFilter(() -> java.util.Optional.of(
                        CurrentUser.tenantUser("user-1", "User", "tenant_a"))))
                .build();

        attachmentMvc.perform(post("/{moduleAlias}/view/{recordId}/attachments/upload-ticket", MODULE, "contract-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(PlatformErrorCodes.VALIDATION_FAILED))
                .andExpect(jsonPath("$.message").value("record attachment access service is not configured"));
    }

    @Test
    void shouldExposeDuplicateCheckThroughActionScopedPath() throws Exception {
        RecordDuplicateCheckService duplicateCheckService = mock(RecordDuplicateCheckService.class);
        MockMvc duplicateMvc = MockMvcBuilders
                .standaloneSetup(controllerFixture(service, activeTenantVerifier).codePreview(codeBusinessPreviewService).generation(referenceGenerationFacade).duplicateCheck(duplicateCheckService).build())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new PlatformWebExceptionHandler(), new DynamicWebExceptionHandler())
                .addFilters(new CurrentUserWebFilter(() -> java.util.Optional.of(
                        CurrentUser.tenantUser("user-1", "User", "tenant_a"))))
                .build();
        when(service.action(MODULE, "duplicate_contract"))
                .thenReturn(action("duplicate_contract", EntityActionLevel.RECORD));
        when(duplicateCheckService.check(eq(MODULE), eq("duplicate_contract"), eq("contract-1"), any()))
                .thenReturn(new RecordDuplicateCheckResult(
                        "rule-1",
                        "duplicate_contract",
                        List.of("code"),
                        true,
                        List.of(new RecordDuplicateMatch("contract-2", 5, Map.of("code", "C-001")))));

        duplicateMvc.perform(post("/{moduleAlias}/{actionCode}/duplicate/check", MODULE, "duplicate_contract")
                        .contentType("application/json")
                        .content("""
                                {
                                  "recordId": "contract-1",
                                  "values": {
                                    "code": "C-001"
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicated").value(true))
                .andExpect(jsonPath("$.matches[0].recordId").value("contract-2"))
                .andExpect(jsonPath("$.matches[0].values.code").value("C-001"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> values = ArgumentCaptor.forClass(Map.class);
        verify(duplicateCheckService).check(eq(MODULE), eq("duplicate_contract"), eq("contract-1"), values.capture());
        assertThat(values.getValue()).containsEntry("code", "C-001");
    }

    @Test
    void shouldRejectDuplicateCheckWhenActionIsNotAuthorized() throws Exception {
        RecordDuplicateCheckService duplicateCheckService = mock(RecordDuplicateCheckService.class);
        MockMvc duplicateMvc = MockMvcBuilders
                .standaloneSetup(controllerFixture(service, activeTenantVerifier).codePreview(codeBusinessPreviewService).generation(referenceGenerationFacade).duplicateCheck(duplicateCheckService).build())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new PlatformWebExceptionHandler(), new DynamicWebExceptionHandler())
                .addFilters(new CurrentUserWebFilter(() -> java.util.Optional.of(
                        CurrentUser.tenantUser("user-1", "User", "tenant_a"))))
                .build();
        when(service.action(MODULE, "duplicate_contract"))
                .thenReturn(action("duplicate_contract", EntityActionLevel.RECORD));
        when(service.actionAuthorizationAvailability(eq(MODULE), eq("duplicate_contract"), any()))
                .thenReturn(DynamicActionAvailability.unavailable("duplicate_contract", "action permission denied"));

        duplicateMvc.perform(post("/{moduleAlias}/{actionCode}/duplicate/check", MODULE, "duplicate_contract")
                        .contentType("application/json")
                        .content("""
                                {
                                  "recordId": "contract-1",
                                  "values": {
                                    "code": "C-001"
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("action permission denied"));

        verifyNoInteractions(duplicateCheckService);
    }

    @Test
    void shouldNotDeclareStaticActionEndpointForDuplicateCheck() throws Exception {
        Method method = DynamicRecordWebController.class.getMethod(
                "checkDuplicate", String.class, DynamicWebDuplicateCheckRequest.class);

        assertThat(method.getAnnotation(ActionEndpoint.class)).isNull();
    }

    @Test
    void shouldRejectUnknownDynamicChildRelationInRequest() throws Exception {
        when(service.relations(MODULE)).thenReturn(List.of(
                new DynamicRelationDescriptor("lines", ENTITY, "contract_line", "contractId", false, false)
        ));
        Map<String, Object> body = Map.of(
                "values", Map.of("code", "C-001"),
                "children", Map.of("unknownLines", List.of(Map.of(
                        "values", Map.of("lineNo", "L-001")))));

        mvc.perform(post("/{moduleAlias}/insert", MODULE)
                        .contentType("application/json")
                        .content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("unknown dynamic child relation: unknownLines"));
    }

    @Test
    void shouldRejectNonArrayDynamicChildRelationInRequest() throws Exception {
        when(service.relations(MODULE)).thenReturn(List.of(
                new DynamicRelationDescriptor("lines", ENTITY, "contract_line", "contractId", false, false)
        ));
        Map<String, Object> body = Map.of(
                "values", Map.of("code", "C-001"),
                "children", Map.of("lines", Map.of(
                        "values", Map.of("lineNo", "L-001"))));

        mvc.perform(post("/{moduleAlias}/insert", MODULE)
                        .contentType("application/json")
                        .content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("dynamic child relation must be array: lines"));
    }

    @Test
    void shouldMaskProtectedDynamicFieldsInViewResponse() throws Exception {
        DynamicRecord record = new DynamicRecord(protectedEntity())
                .setValue("code", "C-001")
                .setValue("secret", "sensitive-value");
        record.setId("contract-1");
        when(mainEntity.select("contract-1")).thenReturn(record);

        mvc.perform(get("/{moduleAlias}/view/{recordId}", MODULE, "contract-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values.code").value("C-001"))
                .andExpect(jsonPath("$.values.secret").value("s*************e"));
    }

    @Test
    void shouldBuildMainEntityQueryCriteriaAndReturnPageResponse() throws Exception {
        Criteria criteria = Criteria.of().eq("code", "C-001");
        DynamicRecord record = new DynamicRecord(entity()).setValue("code", "C-001");
        record.setId("contract-1");
        when(mainEntity.queryCriteria(any())).thenReturn(criteria);
        when(mainEntity.pageQuery(eq(criteria), any(PageRequest.class), any(Sort[].class)))
                .thenReturn(PageResult.of(List.of(record), 1, PageRequest.of(2, 30)));

        mvc.perform(post("/{moduleAlias}/query", MODULE)
                        .contentType("application/json")
                        .content(json(Map.of(
                                "conditions", List.of(Map.of(
                                        "fieldName", "code",
                                        "operator", "EQ",
                                        "values", List.of("C-001")
                                )),
                                "page", Map.of("pageNum", 2, "pageSize", 30),
                                "sorts", List.of(Map.of("field", "amount", "desc", true))
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].id").value("contract-1"))
                .andExpect(jsonPath("$.records[0].values.code").value("C-001"))
                .andExpect(jsonPath("$.total").value(1));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DynamicQueryCondition>> conditions = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<PageRequest> page = ArgumentCaptor.forClass(PageRequest.class);
        ArgumentCaptor<Sort[]> sorts = ArgumentCaptor.forClass(Sort[].class);
        verify(mainEntity).queryCriteria(conditions.capture());
        verify(mainEntity).pageQuery(eq(criteria), page.capture(), sorts.capture());
        assertThat(conditions.getValue().getFirst().operator()).isEqualTo(DynamicQueryOperator.EQ);
        assertThat(conditions.getValue().getFirst().values()).isEqualTo(List.of("C-001"));
        assertThat(page.getValue().getOffset()).isEqualTo(30);
        assertThat(page.getValue().getLimit()).isEqualTo(30);
        assertThat(sorts.getValue()[0].getField()).isEqualTo("amount");
    }

    @Test
    void shouldReadNavigatorReferenceQueryThroughReferenceActionScope() throws Exception {
        Criteria criteria = Criteria.of().eq("code", "C-001");
        DynamicRecord record = new DynamicRecord(entity()).setValue("code", "C-001");
        record.setId("contract-1");
        when(mainEntity.queryCriteria(any())).thenReturn(criteria);
        when(service.pageForAction(eq(MODULE), eq(ENTITY), eq(PlatformAction.REFERENCE.code()), any(Criteria.class),
                any(PageRequest.class), any(Sort[].class)))
                .thenReturn(PageResult.of(List.of(record), 1, PageRequest.of(1, 20)));

        mvc.perform(post("/{moduleAlias}/navigator/reference/query", MODULE)
                        .contentType("application/json")
                        .content(json(Map.of("page", Map.of("pageNum", 1, "pageSize", 20)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].id").value("contract-1"))
                .andExpect(jsonPath("$.records[0].values.code").value("C-001"));

        verify(service).pageForAction(eq(MODULE), eq(ENTITY), eq(PlatformAction.REFERENCE.code()), any(Criteria.class),
                any(PageRequest.class), any(Sort[].class));
        verify(mainEntity, never()).pageQuery(any(), any(PageRequest.class), any(Sort[].class));
    }

    @Test
    void shouldReadNavigatorReferenceTreeThroughReferenceActionScope() throws Exception {
        DynamicRecord root = new DynamicRecord(entity()).setValue("code", "ROOT");
        root.setId("root-1");
        DynamicRecord child = new DynamicRecord(entity()).setValue("code", "CHILD");
        child.setId("child-1");
        when(service.childrenForAction(eq(MODULE), eq(ENTITY), eq(PlatformAction.REFERENCE.code()), any(), anyString()))
                .thenAnswer(invocation -> switch (invocation.getArgument(4, String.class)) {
                    case "root" -> List.of(root);
                    case "root-1" -> List.of(child);
                    default -> List.of();
                });

        mvc.perform(post("/{moduleAlias}/navigator/reference/tree/query", MODULE)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].record.id").value("root-1"))
                .andExpect(jsonPath("$.records[0].children[0].record.id").value("child-1"));

        verify(service, times(3)).childrenForAction(eq(MODULE), eq(ENTITY), eq(PlatformAction.REFERENCE.code()),
                any(), anyString());
    }

    @Test
    void shouldPassCollectionQueryOperatorsFromHttpRequestToDynamicCriteria() throws Exception {
        Criteria criteria = Criteria.of().containsAny("tags", List.of("vip", "trial"));
        DynamicRecord record = new DynamicRecord(entity()).setValue("code", "C-001");
        record.setId("contract-1");
        when(mainEntity.queryCriteria(any())).thenReturn(criteria);
        when(mainEntity.pageQuery(eq(criteria), any(PageRequest.class), any(Sort[].class)))
                .thenReturn(PageResult.of(List.of(record), 1, PageRequest.of(1, 20)));

        mvc.perform(post("/{moduleAlias}/query", MODULE)
                        .contentType("application/json")
                        .content("""
                                {
                                  "conditions": [
                                    {"fieldName": "tags", "operator": "CONTAINS", "values": ["vip"]},
                                    {"fieldName": "tags", "operator": "CONTAINS_ANY", "values": ["vip", "trial"]},
                                    {"fieldName": "tags", "operator": "CONTAINS_ALL", "values": ["vip", "paid"]},
                                    {"fieldName": "tags", "operator": "EMPTY", "values": []},
                                    {"fieldName": "tags", "operator": "NOT_EMPTY", "values": []}
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].id").value("contract-1"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DynamicQueryCondition>> conditions = ArgumentCaptor.forClass(List.class);
        verify(mainEntity).queryCriteria(conditions.capture());
        assertThat(conditions.getValue()).extracting(DynamicQueryCondition::operator)
                .containsExactly(DynamicQueryOperator.CONTAINS, DynamicQueryOperator.CONTAINS_ANY,
                        DynamicQueryOperator.CONTAINS_ALL, DynamicQueryOperator.EMPTY,
                        DynamicQueryOperator.NOT_EMPTY);
        assertThat(conditions.getValue().get(1).values()).isEqualTo(List.of("vip", "trial"));
    }

    @Test
    void shouldBuildNestedMainEntityQueryCriteriaTree() throws Exception {
        DynamicRecord record = new DynamicRecord(entity()).setValue("code", "C-001");
        record.setId("contract-1");
        when(mainEntity.queryCriteria(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<DynamicQueryCondition> conditions = invocation.getArgument(0, List.class);
            DynamicQueryCondition condition = conditions.getFirst();
            return Criteria.of().eq(condition.fieldName(), condition.values().getFirst());
        });
        when(mainEntity.pageQuery(any(Criteria.class), any(PageRequest.class), any(Sort[].class)))
                .thenReturn(PageResult.of(List.of(record), 1, PageRequest.of(1, 20)));

        mvc.perform(post("/{moduleAlias}/query", MODULE)
                        .contentType("application/json")
                        .content("""
                                {
                                  "criteria": {
                                    "operator": "OR",
                                    "conditions": [
                                      {"fieldName": "code", "operator": "EQ", "values": ["C-001"]}
                                    ],
                                    "groups": [
                                      {
                                        "operator": "AND",
                                        "conditions": [
                                          {"fieldName": "status", "operator": "EQ", "values": ["ACTIVE"]}
                                        ],
                                        "groups": [
                                          {
                                            "operator": "OR",
                                            "conditions": [
                                              {"fieldName": "ownerId", "operator": "EQ", "values": ["u-1"]},
                                              {"fieldName": "ownerId", "operator": "EQ", "values": ["u-2"]}
                                            ]
                                          }
                                        ]
                                      }
                                    ]
                                  }
                                }
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<Criteria> criteria = ArgumentCaptor.forClass(Criteria.class);
        verify(mainEntity).pageQuery(criteria.capture(), any(PageRequest.class), any(Sort[].class));
        List<CriteriaGroup.Entry> rootEntries = criteria.getValue().getRoot().getEntries();
        assertThat(rootEntries).hasSize(2);
        assertThat(criteriaJoin(rootEntries.get(1))).isEqualTo("OR");
        CriteriaGroup nestedAnd = (CriteriaGroup) criteriaNode(rootEntries.get(1));
        assertThat(nestedAnd.getEntries()).hasSize(2);
        CriteriaGroup nestedOr = (CriteriaGroup) criteriaNode(nestedAnd.getEntries().get(1));
        assertThat(criteriaJoin(nestedOr.getEntries().get(1))).isEqualTo("OR");
    }

    @Test
    void shouldCreateNavigationSessionWhenDynamicQueryRequestsIt() throws Exception {
        PlatformRecordNavigationService navigationService = mock(PlatformRecordNavigationService.class);
        MockMvc navigationMvc = MockMvcBuilders
                .standaloneSetup(controllerFixture(service, activeTenantVerifier).codePreview(codeBusinessPreviewService).generation(referenceGenerationFacade).navigation(navigationService).build())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new PlatformWebExceptionHandler(), new DynamicWebExceptionHandler())
                .addFilters(new CurrentUserWebFilter(() -> java.util.Optional.of(
                        CurrentUser.tenantUser("user-1", "User", "tenant_a"))))
                .build();
        DynamicRecord first = new DynamicRecord(entity()).setValue("code", "C-001");
        first.setId("contract-1");
        DynamicRecord second = new DynamicRecord(entity()).setValue("code", "C-002");
        second.setId("contract-2");
        when(mainEntity.pageQuery(any(Criteria.class), any(PageRequest.class), any(Sort[].class)))
                .thenReturn(PageResult.of(List.of(first, second), 2, PageRequest.of(1, 20)));
        when(navigationService.createCurrentUserSession(eq(MODULE), eq(ENTITY),
                eq(List.of("contract-1", "contract-2")), eq(1), eq(20), eq(2L), eq("query-1")))
                .thenReturn(new PlatformRecordNavigationContext("nav-1", MODULE, ENTITY,
                        List.of("contract-1", "contract-2"), 1, 20, 2, "query-1"));

        navigationMvc.perform(post("/{moduleAlias}/query", MODULE)
                        .contentType("application/json")
                        .content("""
                                {
                                  "navigationSession": true,
                                  "navigationQueryKey": "query-1",
                                  "page": {
                                    "pageNum": 1,
                                    "pageSize": 20
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.navigation.sessionId").value("nav-1"))
                .andExpect(jsonPath("$.navigation.querySnapshotKey").value("query-1"))
                .andExpect(jsonPath("$.navigation.recordIds[0]").value("contract-1"))
                .andExpect(jsonPath("$.records[1].id").value("contract-2"));
    }

    @Test
    void shouldResolveRecordNavigationMove() throws Exception {
        PlatformRecordNavigationService navigationService = mock(PlatformRecordNavigationService.class);
        MockMvc navigationMvc = MockMvcBuilders
                .standaloneSetup(controllerFixture(service, activeTenantVerifier).codePreview(codeBusinessPreviewService).generation(referenceGenerationFacade).navigation(navigationService).build())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new PlatformWebExceptionHandler(), new DynamicWebExceptionHandler())
                .addFilters(new CurrentUserWebFilter(() -> java.util.Optional.of(
                        CurrentUser.tenantUser("user-1", "User", "tenant_a"))))
                .build();
        when(navigationService.move(MODULE, "nav-1", "contract-2"))
                .thenReturn(new PlatformRecordNavigationMove("nav-1", "contract-2",
                        "contract-1", "contract-3", false, false));
        when(service.select(eq(MODULE), eq(ENTITY), anyString()))
                .thenAnswer(invocation -> {
                    DynamicRecord record = new DynamicRecord(entity());
                    record.setId(invocation.getArgument(2));
                    return record;
                });

        navigationMvc.perform(get("/{moduleAlias}/navigation/{sessionId}/{recordId}", MODULE, "nav-1", "contract-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.previousRecordId").value("contract-1"))
                .andExpect(jsonPath("$.nextRecordId").value("contract-3"));
        verify(service).select(MODULE, ENTITY, "contract-1");
        verify(service).select(MODULE, ENTITY, "contract-2");
        verify(service).select(MODULE, ENTITY, "contract-3");
    }

    @Test
    void shouldRejectRecordNavigationWhenNeighborIsNotVisible() throws Exception {
        PlatformRecordNavigationService navigationService = mock(PlatformRecordNavigationService.class);
        MockMvc navigationMvc = MockMvcBuilders
                .standaloneSetup(controllerFixture(service, activeTenantVerifier).codePreview(codeBusinessPreviewService).generation(referenceGenerationFacade).navigation(navigationService).build())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new PlatformWebExceptionHandler(), new DynamicWebExceptionHandler())
                .addFilters(new CurrentUserWebFilter(() -> java.util.Optional.of(
                        CurrentUser.tenantUser("user-1", "User", "tenant_a"))))
                .build();
        when(navigationService.move(MODULE, "nav-1", "contract-2"))
                .thenReturn(new PlatformRecordNavigationMove("nav-1", "contract-2",
                        "hidden", null, false, true));
        DynamicRecord current = new DynamicRecord(entity()).setValue("code", "C-002");
        current.setId("contract-2");
        when(service.select(MODULE, ENTITY, "contract-2")).thenReturn(current);
        when(service.select(MODULE, ENTITY, "hidden")).thenReturn(null);

        navigationMvc.perform(get("/{moduleAlias}/navigation/{sessionId}/{recordId}", MODULE, "nav-1", "contract-2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("record navigation record is not visible: hidden"));
    }

    @Test
    void shouldApplyLowCodeQueryTemplateAndProjectByUiConfig() throws Exception {
        PlatformPageConfigSnapshotService snapshotService = mock(PlatformPageConfigSnapshotService.class);
        PlatformQueryItemService queryItemService = mock(PlatformQueryItemService.class);
        ModuleMetadataFieldService moduleFieldService = mock(ModuleMetadataFieldService.class);
        MockMvc lowCodeMvc = MockMvcBuilders
                .standaloneSetup(controllerFixture(service, activeTenantVerifier).codePreview(codeBusinessPreviewService).generation(referenceGenerationFacade).query(snapshotService, queryItemService, moduleFieldService).build())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new PlatformWebExceptionHandler(), new DynamicWebExceptionHandler())
                .addFilters(new CurrentUserWebFilter(() -> java.util.Optional.of(
                        CurrentUser.tenantUser("user-1", "User", "tenant_a"))))
                .build();
        PlatformUiConfig uiConfig = new PlatformUiConfig();
        uiConfig.setId("ui-list");
        uiConfig.setUiSetId("set-list");
        uiConfig.setClientType(PlatformUiClientType.WEB);
        uiConfig.setPublished(true);
        uiConfig.setLayoutJson("""
                {"template":"LIST_DETAIL_CARD","traits":[],"navigator":{"contextBindings":[{
                  "source":"NAVIGATOR","sourceKey":"tenant","target":"LIST_QUERY","targetKey":"tenantId"
                }],"levels":[{
                  "key":"tenant","kind":"MICRO_LIST","sourceModuleAlias":"iam.tenant"
                }]}}""");
        PlatformUiConfigField codeField = new PlatformUiConfigField();
        codeField.setUiConfigId("ui-list");
        codeField.setModuleMetadataFieldId("module-field-code");
        codeField.setVisible(true);
        PlatformUiConfigField displayField = new PlatformUiConfigField();
        displayField.setUiConfigId("ui-list");
        displayField.setModuleMetadataFieldId("module-field-display-code");
        displayField.setVisible(true);
        PlatformQueryTemplate template = new PlatformQueryTemplate();
        template.setId("tpl-active");
        template.setModuleAlias(MODULE);
        template.setAlias("active");
        when(snapshotService.snapshot(MODULE)).thenReturn(new PlatformPageConfigSnapshot(
                MODULE,
                List.of(),
                List.of(uiConfig),
                List.of(codeField, displayField),
                List.of(template),
                List.of()
        ));
        when(moduleFieldService.resolve("module-field-code")).thenReturn(resolvedModuleField(
                "module-field-code", "code"));
        when(moduleFieldService.resolve("module-field-display-code")).thenReturn(resolvedModuleField(
                "module-field-display-code", "displayCode", RelationRole.MAIN, "main", "string",
                MetadataFieldForm.VIRTUAL));
        Criteria templateCriteria = Criteria.of().eq("code", "C-001");
        when(queryItemService.compile(eq("tpl-active"), any())).thenReturn(templateCriteria);
        DynamicRecord record = new DynamicRecord(entity())
                .setValue("code", "C-001")
                .putDisplayValue("displayCode", "C-001 / Customer")
                .setValue("amount", BigDecimal.TEN);
        record.setId("contract-1");
        when(mainEntity.queryCriteria(any())).thenReturn(Criteria.of().eq("amount", BigDecimal.TEN));
        when(mainEntity.pageQuery(any(Criteria.class), any(PageRequest.class), any(Sort[].class)))
                .thenReturn(PageResult.of(List.of(record), 1, PageRequest.of(1, 20)));

        lowCodeMvc.perform(post("/{moduleAlias}/query", MODULE)
                        .contentType("application/json")
                        .content("""
                                {
                                  "uiConfigId": "ui-list",
                                  "queryTemplateId": "tpl-active",
                                  "externalQueryValues": {
                                    "owner": "user-1",
                                    "optional": null
                                  },
                                  "conditions": [
                                    {
                                      "fieldName": "amount",
                                      "operator": "EQ",
                                      "values": [10]
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].id").value("contract-1"))
                .andExpect(jsonPath("$.records[0].values.code").value("C-001"))
                .andExpect(jsonPath("$.records[0].values.displayCode").value("C-001 / Customer"))
                .andExpect(jsonPath("$.records[0].values.amount").doesNotExist())
                .andExpect(jsonPath("$.total").value(1));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> externalValues = ArgumentCaptor.forClass(Map.class);
        verify(queryItemService).compile(eq("tpl-active"), externalValues.capture());
        assertThat(externalValues.getValue()).containsEntry("owner", "user-1");
        assertThat(externalValues.getValue()).containsEntry("optional", null);
        verify(mainEntity).queryCriteria(any());
        verify(snapshotService, times(3)).snapshot(MODULE);
    }

    @Test
    void shouldRejectPublishedUiRequestWithoutInstalledExecutionPlanBeforeReadingSnapshot() throws Exception {
        PlatformPageConfigSnapshotService snapshotService = mock(PlatformPageConfigSnapshotService.class);
        ModuleExecutionPlanCatalog catalog = new ModuleExecutionPlanCatalog(new StaticModuleDefinitionCatalog(List.of()));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controllerFixture(service, activeTenantVerifier)
                        .query(snapshotService, null, null)
                        .executionPlans(catalog)
                        .build())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new PlatformWebExceptionHandler(), new DynamicWebExceptionHandler())
                .addFilters(new CurrentUserWebFilter(() -> java.util.Optional.of(
                        CurrentUser.tenantUser("user-1", "User", "tenant_a"))))
                .build();

        mvc.perform(post("/{moduleAlias}/query", MODULE)
                        .contentType("application/json")
                        .content("{\"uiConfigId\":\"ui-list\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(
                        "no executable published page plan")));

        verifyNoInteractions(snapshotService);
    }

    @Test
    void shouldExecuteStandardQueryAndSavePathsFromInstalledPlanWithoutReadingSnapshot() throws Exception {
        PlatformPageConfigSnapshotService snapshotService = mock(PlatformPageConfigSnapshotService.class);
        ModuleExecutionPlanCatalog catalog = new ModuleExecutionPlanCatalog(new StaticModuleDefinitionCatalog(List.of()));
        catalog.replaceDynamicPlan(MODULE, java.util.Optional.of(installedDynamicPlan()));
        MockMvc plannedMvc = MockMvcBuilders.standaloneSetup(controllerFixture(service, activeTenantVerifier)
                        .query(snapshotService, null, null).executionPlans(catalog).build())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new PlatformWebExceptionHandler(), new DynamicWebExceptionHandler())
                .addFilters(new CurrentUserWebFilter(() -> java.util.Optional.of(
                        CurrentUser.tenantUser("user-1", "User", "tenant_a"))))
                .build();
        DynamicRecord record = new DynamicRecord(entity()).setValue("code", "C-001");
        record.setId("contract-1");
        when(mainEntity.queryCriteria(any())).thenReturn(Criteria.of().eq("code", "C-001"));
        when(mainEntity.pageQuery(any(Criteria.class), any(PageRequest.class), any(Sort[].class)))
                .thenReturn(PageResult.of(List.of(record), 1, PageRequest.of(1, 20)));

        plannedMvc.perform(get("/{moduleAlias}/query/schema", MODULE).param("uiConfigId", "ui-list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields[0].name").value("code"));
        plannedMvc.perform(post("/{moduleAlias}/query", MODULE).contentType("application/json")
                        .content("{\"uiConfigId\":\"ui-list\",\"conditions\":[{\"fieldName\":\"code\",\"operator\":\"EQ\",\"values\":[\"C-001\"]}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].values.code").value("C-001"));
        plannedMvc.perform(post("/{moduleAlias}/query", MODULE).contentType("application/json")
                        .content("{\"uiConfigId\":\"ui-list\",\"queryTemplateId\":\"tpl-active\",\"externalQueryValues\":{\"code\":\"C-001\"}}"))
                .andExpect(status().isOk());
        plannedMvc.perform(get("/{moduleAlias}/query/schema", MODULE).param("uiConfigId", "stale-list"))
                .andExpect(status().isBadRequest());
        plannedMvc.perform(post("/{moduleAlias}/insert", MODULE).contentType("application/json")
                        .content("{\"uiConfigId\":\"form-v1\",\"values\":{}}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("UI required field is missing: code")));
        plannedMvc.perform(post("/{moduleAlias}/insert", MODULE).contentType("application/json")
                        .content("{\"values\":{\"code\":\"C-001\"}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Save requires published FORM uiConfigId")));
        plannedMvc.perform(post("/{moduleAlias}/insert", MODULE).contentType("application/json")
                        .content("{\"uiConfigId\":\"stale-form\",\"values\":{\"code\":\"C-001\"}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Save requires published FORM uiConfigId")));

        verifyNoInteractions(snapshotService);
    }

    @Test
    void shouldApplyQuickSearchWithinPublishedListUiConfig() throws Exception {
        PlatformPageConfigSnapshotService snapshotService = mock(PlatformPageConfigSnapshotService.class);
        ModuleMetadataFieldService moduleFieldService = mock(ModuleMetadataFieldService.class);
        MockMvc lowCodeMvc = MockMvcBuilders
                .standaloneSetup(controllerFixture(service, activeTenantVerifier).codePreview(codeBusinessPreviewService).generation(referenceGenerationFacade)
                        .query(snapshotService, null, moduleFieldService).build())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new PlatformWebExceptionHandler(), new DynamicWebExceptionHandler())
                .addFilters(new CurrentUserWebFilter(() -> java.util.Optional.of(
                        CurrentUser.tenantUser("user-1", "User", "tenant_a"))))
                .build();
        PlatformUiSet uiSet = new PlatformUiSet();
        uiSet.setId("set-list");
        uiSet.setModuleAlias(MODULE);
        uiSet.setAlias("list");
        uiSet.setSetType(PlatformUiSetType.LIST);
        PlatformUiConfig uiConfig = new PlatformUiConfig();
        uiConfig.setId("ui-list");
        uiConfig.setUiSetId("set-list");
        uiConfig.setClientType(PlatformUiClientType.WEB);
        uiConfig.setPublished(true);
        PlatformUiConfigField codeField = new PlatformUiConfigField();
        codeField.setUiConfigId("ui-list");
        codeField.setModuleMetadataFieldId("module-field-code");
        codeField.setVisible(true);
        PlatformUiConfigField amountField = new PlatformUiConfigField();
        amountField.setUiConfigId("ui-list");
        amountField.setModuleMetadataFieldId("module-field-amount");
        amountField.setVisible(true);
        when(snapshotService.snapshot(MODULE)).thenReturn(new PlatformPageConfigSnapshot(
                MODULE,
                List.of(uiSet),
                List.of(uiConfig),
                List.of(codeField, amountField),
                List.of(),
                List.of()
        ));
        when(moduleFieldService.resolve("module-field-code")).thenReturn(resolvedModuleField(
                "module-field-code", "code"));
        when(moduleFieldService.resolve("module-field-amount")).thenReturn(resolvedModuleField(
                "module-field-amount", "amount", RelationRole.MAIN, "decimal"));
        DynamicRecord record = new DynamicRecord(entity()).setValue("code", "C-001");
        record.setId("contract-1");
        when(mainEntity.pageQuery(any(Criteria.class), any(PageRequest.class), any(Sort[].class)))
                .thenReturn(PageResult.of(List.of(record), 1, PageRequest.of(1, 20)));

        lowCodeMvc.perform(post("/{moduleAlias}/query", MODULE)
                        .contentType("application/json")
                        .content("""
                                {
                                  "uiConfigId": "ui-list",
                                  "quickSearch": "C-001",
                                  "quickSearchFields": ["code"]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].id").value("contract-1"));

        ArgumentCaptor<Criteria> criteria = ArgumentCaptor.forClass(Criteria.class);
        verify(mainEntity).pageQuery(criteria.capture(), any(PageRequest.class), any(Sort[].class));
        assertThat(criteria.getValue().isEmpty()).isFalse();

        lowCodeMvc.perform(post("/{moduleAlias}/query", MODULE)
                        .contentType("application/json")
                        .content("""
                                {
                                  "uiConfigId": "ui-list",
                                  "quickSearch": "C-001",
                                  "quickSearchFields": ["amount"]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Quick search field is not searchable in UI config: amount"));
    }

    @Test
    void shouldExposeDynamicQuerySchemaWithUiScopedQuickSearchFields() throws Exception {
        PlatformPageConfigSnapshotService snapshotService = mock(PlatformPageConfigSnapshotService.class);
        ModuleMetadataFieldService moduleFieldService = mock(ModuleMetadataFieldService.class);
        MockMvc lowCodeMvc = MockMvcBuilders
                .standaloneSetup(controllerFixture(service, activeTenantVerifier).codePreview(codeBusinessPreviewService).generation(referenceGenerationFacade).query(snapshotService, null, moduleFieldService).build())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new PlatformWebExceptionHandler(), new DynamicWebExceptionHandler())
                .addFilters(new CurrentUserWebFilter(() -> java.util.Optional.of(
                        CurrentUser.tenantUser("user-1", "User", "tenant_a"))))
                .build();
        PlatformUiSet uiSet = new PlatformUiSet();
        uiSet.setId("set-list");
        uiSet.setModuleAlias(MODULE);
        uiSet.setAlias("list");
        uiSet.setSetType(PlatformUiSetType.LIST);
        PlatformUiConfig uiConfig = new PlatformUiConfig();
        uiConfig.setId("ui-list");
        uiConfig.setUiSetId("set-list");
        uiConfig.setClientType(PlatformUiClientType.WEB);
        uiConfig.setPublished(true);
        PlatformUiConfigField codeField = uiField("ui-list", "module-field-code");
        PlatformUiConfigField amountField = uiField("ui-list", "module-field-amount");
        when(snapshotService.snapshot(MODULE)).thenReturn(new PlatformPageConfigSnapshot(
                MODULE,
                List.of(uiSet),
                List.of(uiConfig),
                List.of(codeField, amountField),
                List.of(),
                List.of()
        ));
        when(moduleFieldService.resolve("module-field-code")).thenReturn(resolvedModuleField(
                "module-field-code", "code"));
        when(moduleFieldService.resolve("module-field-amount")).thenReturn(resolvedModuleField(
                "module-field-amount", "amount", RelationRole.MAIN, "decimal"));
        when(mainEntity.describe()).thenReturn(DynamicEntityDescriptor.from(entity()));

        lowCodeMvc.perform(get("/{moduleAlias}/query/schema", MODULE)
                        .param("uiConfigId", "ui-list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scopeName").value(MODULE))
                .andExpect(jsonPath("$.entityAlias").value(ENTITY))
                .andExpect(jsonPath("$.quickSearch.enabled").value(true))
                .andExpect(jsonPath("$.quickSearch.fields[0]").value("code"))
                .andExpect(jsonPath("$.quickSearch.fieldSchemas[?(@.name == 'code')].quickSearch")
                        .value(org.hamcrest.Matchers.contains(true)))
                .andExpect(jsonPath("$.quickSearch.fieldSchemas[?(@.name == 'code')].operators[0]")
                        .value(org.hamcrest.Matchers.contains("LIKE")))
                .andExpect(jsonPath("$.fields[?(@.name == 'code')]").isEmpty())
                .andExpect(jsonPath("$.fields[?(@.name == 'amount')]").isEmpty());

        verify(activeTenantVerifier).verifyActiveTenant("tenant_a");
    }

    @Test
    void shouldRejectVirtualFieldInQuickSearch() throws Exception {
        PlatformPageConfigSnapshotService snapshotService = mock(PlatformPageConfigSnapshotService.class);
        ModuleMetadataFieldService moduleFieldService = mock(ModuleMetadataFieldService.class);
        MockMvc lowCodeMvc = MockMvcBuilders
                .standaloneSetup(controllerFixture(service, activeTenantVerifier).codePreview(codeBusinessPreviewService).generation(referenceGenerationFacade).query(snapshotService, null, moduleFieldService).build())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new PlatformWebExceptionHandler(), new DynamicWebExceptionHandler())
                .addFilters(new CurrentUserWebFilter(() -> java.util.Optional.of(
                        CurrentUser.tenantUser("user-1", "User", "tenant_a"))))
                .build();
        PlatformUiSet uiSet = new PlatformUiSet();
        uiSet.setId("set-list");
        uiSet.setModuleAlias(MODULE);
        uiSet.setAlias("list");
        uiSet.setSetType(PlatformUiSetType.LIST);
        PlatformUiConfig uiConfig = new PlatformUiConfig();
        uiConfig.setId("ui-list");
        uiConfig.setUiSetId("set-list");
        uiConfig.setClientType(PlatformUiClientType.WEB);
        uiConfig.setPublished(true);
        PlatformUiConfigField displayField = uiField("ui-list", "module-field-display-code");
        when(snapshotService.snapshot(MODULE)).thenReturn(new PlatformPageConfigSnapshot(
                MODULE,
                List.of(uiSet),
                List.of(uiConfig),
                List.of(displayField),
                List.of(),
                List.of()
        ));
        when(moduleFieldService.resolve("module-field-display-code")).thenReturn(resolvedModuleField(
                "module-field-display-code", "displayCode", RelationRole.MAIN, "main", "string",
                MetadataFieldForm.VIRTUAL));

        lowCodeMvc.perform(post("/{moduleAlias}/query", MODULE)
                        .contentType("application/json")
                        .content("""
                                {
                                  "uiConfigId": "ui-list",
                                  "quickSearch": "C-001",
                                  "quickSearchFields": ["displayCode"]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Quick search field is not searchable in UI config: displayCode"));

        verify(mainEntity, never()).pageQuery(any(Criteria.class), any(PageRequest.class), any(Sort[].class));
    }

    @Test
    void shouldRejectVirtualFieldInQuerySorts() throws Exception {
        mvc.perform(post("/{moduleAlias}/query", MODULE)
                        .contentType("application/json")
                        .content("""
                                {
                                  "sorts": [
                                    {"field": "displayCode", "desc": true}
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Sort field is not a physical dynamic field: displayCode"));

        verify(mainEntity, never()).pageQuery(any(Criteria.class), any(PageRequest.class), any(Sort[].class));
    }

    @Test
    void shouldAllowProjectionFieldSortsWhenDynamicSqlProjectionIsSupported() throws Exception {
        PlatformPageConfigSnapshotService snapshotService = mock(PlatformPageConfigSnapshotService.class);
        ModuleMetadataFieldService moduleFieldService = mock(ModuleMetadataFieldService.class);
        DynamicRelationProjectionReadService projectionReadService = mock(DynamicRelationProjectionReadService.class);
        MockMvc lowCodeMvc = projectionMvc(snapshotService, moduleFieldService, projectionReadService);
        publishedListUiConfig(snapshotService,
                uiField("ui-list", "module-field-code"),
                uiField("ui-list", "module-field-customer-title"));
        when(moduleFieldService.resolve("module-field-code")).thenReturn(resolvedModuleField(
                "module-field-code", "code"));
        when(moduleFieldService.resolve("module-field-customer-title")).thenReturn(resolvedModuleField(
                "module-field-customer-title", "customerTitle", RelationRole.MAIN, "main", "string",
                MetadataFieldForm.VIRTUAL));
        when(projectionReadService.describeListQuery(eq(MODULE), eq(service), any()))
                .thenReturn(new ProjectionQueryDescriptor(
                        MODULE,
                        "dynamic_ui_config_list",
                        true,
                        ProjectionQueryFallbackReason.NONE,
                        Set.of("code", "customerTitle"),
                        Set.of("id", "tenantId", "version"),
                        Set.of("code", "customerTitle"),
                        Set.of("code", "customerTitle"),
                        Set.of("id", "code", "customerTitle"),
                        List.of()
                ));
        DynamicRecord record = new DynamicRecord(entity())
                .setValue("code", "C-001")
                .putProjectedValue("customerTitle", "Acme");
        record.setId("contract-1");
        when(projectionReadService.queryList(eq(MODULE), eq(service), any(), any(Criteria.class),
                any(PageRequest.class), any(Sort[].class)))
                .thenReturn(java.util.Optional.of(PageResult.of(List.of(record), 1, PageRequest.of(1, 20))));

        lowCodeMvc.perform(post("/{moduleAlias}/query", MODULE)
                        .contentType("application/json")
                        .content("""
                                {
                                  "uiConfigId": "ui-list",
                                  "sorts": [
                                    {"field": "customerTitle", "desc": true}
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].values.code").value("C-001"))
                .andExpect(jsonPath("$.records[0].values.customerTitle").value("Acme"));

        ArgumentCaptor<Sort[]> sorts = ArgumentCaptor.forClass(Sort[].class);
        verify(projectionReadService).queryList(eq(MODULE), eq(service), any(), any(Criteria.class),
                any(PageRequest.class), sorts.capture());
        assertThat(sorts.getValue()).hasSize(1);
        assertThat(sorts.getValue()[0].getField()).isEqualTo("customerTitle");
        verify(mainEntity, never()).pageQuery(any(Criteria.class), any(PageRequest.class), any(Sort[].class));
    }

    @Test
    void shouldFallbackWhenDynamicSqlProjectionDoesNotSupportAllUiFields() throws Exception {
        PlatformPageConfigSnapshotService snapshotService = mock(PlatformPageConfigSnapshotService.class);
        ModuleMetadataFieldService moduleFieldService = mock(ModuleMetadataFieldService.class);
        DynamicRelationProjectionReadService projectionReadService = mock(DynamicRelationProjectionReadService.class);
        MockMvc lowCodeMvc = projectionMvc(snapshotService, moduleFieldService, projectionReadService);
        publishedListUiConfig(snapshotService,
                uiField("ui-list", "module-field-code"),
                uiField("ui-list", "module-field-display-code"));
        when(moduleFieldService.resolve("module-field-code")).thenReturn(resolvedModuleField(
                "module-field-code", "code"));
        when(moduleFieldService.resolve("module-field-display-code")).thenReturn(resolvedModuleField(
                "module-field-display-code", "displayCode", RelationRole.MAIN, "main", "string",
                MetadataFieldForm.VIRTUAL));
        when(projectionReadService.describeListQuery(eq(MODULE), eq(service), any()))
                .thenReturn(ProjectionQueryDescriptor.unsupported(
                        MODULE,
                        "dynamic_ui_config_list",
                        Set.of("code", "displayCode"),
                        ProjectionQueryFallbackReason.UNSUPPORTED_OUTPUT_FIELD
                ));
        DynamicRecord record = new DynamicRecord(entity())
                .setValue("code", "C-001")
                .putDisplayValue("displayCode", "C-001 / Customer");
        record.setId("contract-1");
        when(mainEntity.pageQuery(any(Criteria.class), any(PageRequest.class), any(Sort[].class)))
                .thenReturn(PageResult.of(List.of(record), 1, PageRequest.of(1, 20)));

        lowCodeMvc.perform(post("/{moduleAlias}/query", MODULE)
                        .contentType("application/json")
                        .content("""
                                {
                                  "uiConfigId": "ui-list"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].values.code").value("C-001"))
                .andExpect(jsonPath("$.records[0].values.displayCode").value("C-001 / Customer"));

        verify(projectionReadService, never()).queryList(anyString(), any(), any(), any(Criteria.class),
                any(PageRequest.class), any(Sort[].class));
        verify(mainEntity).pageQuery(any(Criteria.class), any(PageRequest.class), any(Sort[].class));
    }

    @Test
    void shouldApplyQueryFormWithinPublishedListUiConfig() throws Exception {
        PlatformPageConfigSnapshotService snapshotService = mock(PlatformPageConfigSnapshotService.class);
        ModuleMetadataFieldService moduleFieldService = mock(ModuleMetadataFieldService.class);
        FieldUiControlService fieldUiControlService = mock(FieldUiControlService.class);
        FieldUiControlBindingService bindingService = mock(FieldUiControlBindingService.class);
        MockMvc lowCodeMvc = MockMvcBuilders
                .standaloneSetup(controllerFixture(service, activeTenantVerifier).codePreview(codeBusinessPreviewService).generation(referenceGenerationFacade)
                        .query(snapshotService, null, moduleFieldService, fieldUiControlService, bindingService).build())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new PlatformWebExceptionHandler(), new DynamicWebExceptionHandler())
                .addFilters(new CurrentUserWebFilter(() -> java.util.Optional.of(
                        CurrentUser.tenantUser("user-1", "User", "tenant_a"))))
                .build();
        PlatformUiSet uiSet = new PlatformUiSet();
        uiSet.setId("set-list");
        uiSet.setModuleAlias(MODULE);
        uiSet.setAlias("list");
        uiSet.setSetType(PlatformUiSetType.LIST);
        PlatformUiConfig uiConfig = new PlatformUiConfig();
        uiConfig.setId("ui-list");
        uiConfig.setUiSetId("set-list");
        uiConfig.setClientType(PlatformUiClientType.WEB);
        uiConfig.setPublished(true);
        PlatformUiConfig restrictedUiConfig = new PlatformUiConfig();
        restrictedUiConfig.setId("ui-list-restricted");
        restrictedUiConfig.setUiSetId("set-list");
        restrictedUiConfig.setClientType(PlatformUiClientType.WEB);
        restrictedUiConfig.setPublished(true);
        PlatformUiConfigField codeField = uiField("ui-list", "module-field-code");
        PlatformUiConfigField amountField = uiField("ui-list", "module-field-amount");
        PlatformUiConfigField submittedAtField = uiField("ui-list", "module-field-submitted-at");
        submittedAtField.setFieldUiControlAlias("period_window");
        PlatformUiConfigField hiddenField = uiField("ui-list-restricted", "module-field-hidden");
        hiddenField.setVisible(false);
        PlatformUiConfigField lineField = uiField("ui-list-restricted", "module-field-line-code");
        PlatformPageConfigSnapshot snapshot = new PlatformPageConfigSnapshot(
                MODULE,
                List.of(uiSet),
                List.of(uiConfig, restrictedUiConfig),
                List.of(codeField, amountField, submittedAtField, hiddenField, lineField),
                List.of(),
                List.of()
        );
        when(snapshotService.snapshot(MODULE)).thenReturn(snapshot);
        when(moduleFieldService.resolve("module-field-code")).thenReturn(resolvedModuleField(
                "module-field-code", "code"));
        when(moduleFieldService.resolve("module-field-amount")).thenReturn(resolvedModuleField(
                "module-field-amount", "amount", RelationRole.MAIN, "decimal"));
        when(moduleFieldService.resolve("module-field-submitted-at")).thenReturn(resolvedModuleField(
                "module-field-submitted-at", "submittedAt", RelationRole.MAIN, "timestamp"));
        when(moduleFieldService.resolve("module-field-line-code")).thenReturn(resolvedModuleField(
                "module-field-line-code", "lineCode", RelationRole.CHILD));
        when(fieldUiControlService.requireFieldUiControl(anyString())).thenAnswer(invocation -> {
            FieldUiControl control = new FieldUiControl();
            control.setAlias(invocation.getArgument(0));
            control.setQueryMode(FieldUiControlQueryMode.DEFAULT);
            return control;
        });
        FieldUiControl periodWindow = new FieldUiControl();
        periodWindow.setAlias("period_window");
        periodWindow.setQueryMode(FieldUiControlQueryMode.BETWEEN);
        when(fieldUiControlService.requireFieldUiControl("period_window")).thenReturn(periodWindow);
        FieldUiControlBinding begin = new FieldUiControlBinding();
        begin.setValueKey("beginAt");
        FieldUiControlBinding finish = new FieldUiControlBinding();
        finish.setValueKey("finishAt");
        when(bindingService.listByFieldUiControlAliases(List.of("period_window"))).thenReturn(List.of(begin, finish));
        when(mainEntity.queryCriteria(any())).thenReturn(Criteria.of().like("code", "C-001"));
        DynamicRecord record = new DynamicRecord(entity()).setValue("code", "C-001");
        record.setId("contract-1");
        when(mainEntity.pageQuery(any(Criteria.class), any(PageRequest.class), any(Sort[].class)))
                .thenReturn(PageResult.of(List.of(record), 1, PageRequest.of(1, 20)));

        lowCodeMvc.perform(post("/{moduleAlias}/query", MODULE)
                        .contentType("application/json")
                        .content("""
                                {
                                  "uiConfigId": "ui-list",
                                  "queryForm": {
                                    "code": "C-001",
                                    "amount": 1200,
                                    "submittedAt": {
                                      "beginAt": "2026-01-01",
                                      "finishAt": "2026-01-31",
                                      "timeZone": "Asia/Shanghai"
                                    }
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].id").value("contract-1"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DynamicQueryCondition>> conditions = ArgumentCaptor.forClass(List.class);
        verify(mainEntity).queryCriteria(conditions.capture());
        assertThat(conditions.getValue()).extracting(DynamicQueryCondition::fieldName)
                .containsExactly("code", "amount", "submittedAt");
        assertThat(conditions.getValue().getFirst().operator()).isNull();
        assertThat(conditions.getValue().getFirst().values()).isEqualTo(List.of("C-001"));
        assertThat(conditions.getValue().get(2).operator())
                .isEqualTo(DynamicQueryOperator.BETWEEN);
        List<Object> rangeValues = new java.util.ArrayList<>();
        rangeValues.addAll(conditions.getValue().get(2).values());
        assertThat(rangeValues).containsExactly("2026-01-01", "2026-01-31");
        assertThat(conditions.getValue().get(2).timeZone()).isEqualTo("Asia/Shanghai");

        org.mockito.Mockito.clearInvocations(mainEntity);
        lowCodeMvc.perform(post("/{moduleAlias}/query", MODULE)
                        .contentType("application/json")
                        .content("""
                                {
                                  "uiConfigId": "ui-list",
                                  "queryForm": {
                                    "submittedAt": ["2026-02-01", "2026-02-02"]
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].id").value("contract-1"));
        verify(mainEntity).queryCriteria(conditions.capture());
        assertThat(conditions.getValue()).hasSize(1);
        assertThat(conditions.getValue().getFirst().operator())
                .isEqualTo(DynamicQueryOperator.BETWEEN);
        rangeValues = new java.util.ArrayList<>();
        rangeValues.addAll(conditions.getValue().getFirst().values());
        assertThat(rangeValues).containsExactly("2026-02-01", "2026-02-02");

        org.mockito.Mockito.clearInvocations(mainEntity);
        lowCodeMvc.perform(post("/{moduleAlias}/query", MODULE)
                        .contentType("application/json")
                        .content("""
                                {
                                  "uiConfigId": "ui-list",
                                  "queryForm": {
                                    "code": "",
                                    "amount": []
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].id").value("contract-1"));
        verify(mainEntity, org.mockito.Mockito.never()).queryCriteria(any());

        lowCodeMvc.perform(post("/{moduleAlias}/query", MODULE)
                        .contentType("application/json")
                        .content("""
                                {
                                  "uiConfigId": "ui-list",
                                  "queryForm": {
                                    "missing": "x"
                                  }
                                }
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Query form field is not available in UI config: missing"));

        lowCodeMvc.perform(post("/{moduleAlias}/query", MODULE)
                        .contentType("application/json")
                        .content("""
                                {
                                  "uiConfigId": "ui-list-restricted",
                                  "queryForm": {
                                    "lineCode": "L-001"
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Query form field is not available in UI config: lineCode"));

        lowCodeMvc.perform(post("/{moduleAlias}/query", MODULE)
                        .contentType("application/json")
                        .content("""
                                {
                                  "uiConfigId": "ui-list-restricted",
                                  "queryForm": {
                                    "hidden": "x"
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Query form field is not available in UI config: hidden"));
    }

    @Test
    void shouldQueryAssociationViewWithTargetQueryContext() throws Exception {
        DynamicEntityOperations lineOperations = mock(DynamicEntityOperations.class);
        DynamicAssociationViewDescriptor association = new DynamicAssociationViewDescriptor(
                "lines",
                ENTITY,
                MODULE,
                "line",
                net.ximatai.muyun.spring.dynamic.metadata.AssociationViewDisplayMode.INLINE_LIST,
                "lines",
                null,
                net.ximatai.muyun.spring.dynamic.metadata.EntityViewType.LIST,
                true
        );
        DynamicRecord line = new DynamicRecord(associationLineEntity()).setValue("contractId", "contract-1")
                .setValue("summary", "Line A");
        line.setId("line-1");
        Criteria targetCriteria = Criteria.of().like("summary", "Line");
        when(service.associationView(MODULE, ENTITY, "lines")).thenReturn(association);
        when(service.queryCriteria(eq(MODULE), eq("line"), any())).thenReturn(targetCriteria);
        when(service.associationViewPage(eq(MODULE), eq(ENTITY), eq("contract-1"), eq("lines"),
                any(Criteria.class), any(PageRequest.class), any(Sort[].class)))
                .thenReturn(PageResult.of(List.of(line), 1, PageRequest.of(1, 20)));
        when(service.entity(MODULE, "line")).thenReturn(lineOperations);

        mvc.perform(post("/{moduleAlias}/view/{id}/associations/{viewCode}/query", MODULE, "contract-1", "lines")
                        .contentType("application/json")
                        .content("""
                                {
                                  "conditions": [
                                    {"fieldName": "summary", "operator": "LIKE", "values": ["Line"]}
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].id").value("line-1"))
                .andExpect(jsonPath("$.records[0].values.summary").value("Line A"));

        ArgumentCaptor<Criteria> criteria = ArgumentCaptor.forClass(Criteria.class);
        verify(service).associationViewPage(eq(MODULE), eq(ENTITY), eq("contract-1"), eq("lines"),
                criteria.capture(), any(PageRequest.class), any(Sort[].class));
        assertThat(criteria.getValue()).isSameAs(targetCriteria);
    }

    @Test
    void shouldRejectVirtualFieldInAssociationViewSorts() throws Exception {
        DynamicEntityOperations lineOperations = mock(DynamicEntityOperations.class);
        DynamicAssociationViewDescriptor association = new DynamicAssociationViewDescriptor(
                "lines",
                ENTITY,
                MODULE,
                "line",
                net.ximatai.muyun.spring.dynamic.metadata.AssociationViewDisplayMode.INLINE_LIST,
                "lines",
                null,
                net.ximatai.muyun.spring.dynamic.metadata.EntityViewType.LIST,
                true
        );
        when(service.associationView(MODULE, ENTITY, "lines")).thenReturn(association);
        when(service.entity(MODULE, "line")).thenReturn(lineOperations);
        when(lineOperations.newRecord()).thenReturn(new DynamicRecord(lineEntity()));

        mvc.perform(post("/{moduleAlias}/view/{id}/associations/{viewCode}/query", MODULE, "contract-1", "lines")
                        .contentType("application/json")
                        .content("""
                                {
                                  "sorts": [
                                    {"field": "lineDisplay", "desc": true}
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Sort field is not a physical dynamic field: lineDisplay"));

        verify(service, never()).associationViewPage(anyString(), anyString(), anyString(), anyString(),
                any(Criteria.class), any(PageRequest.class), any(Sort[].class));
    }


    @Test
    void shouldExposeAssociationDesignEndpoints() throws Exception {
        DynamicAssociationViewDescriptor association = new DynamicAssociationViewDescriptor(
                "lines",
                ENTITY,
                MODULE,
                "line",
                net.ximatai.muyun.spring.dynamic.metadata.AssociationViewDisplayMode.INLINE_LIST,
                "lines",
                null,
                net.ximatai.muyun.spring.dynamic.metadata.EntityViewType.LIST,
                true,
                List.of(net.ximatai.muyun.spring.dynamic.metadata.AssociationViewPathStep.relation(
                        "lines", ENTITY, MODULE, "line")),
                null,
                "ui-list",
                "query-default"
        );
        when(service.associationViewDesignDescriptors(MODULE)).thenReturn(List.of(association));
        when(service.associationRelationOverview(MODULE)).thenReturn(new DynamicAssociationRelationOverview(
                MODULE,
                List.of(new DynamicAssociationRelationItem("RELATION", "lines", MODULE, "line", MODULE, ENTITY, "lines")),
                List.of(new DynamicAssociationRelationItem("RELATION", "lines", MODULE, ENTITY, MODULE, "line", "lines"))
        ));

        mvc.perform(get("/{moduleAlias}/associations/design", MODULE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("lines"))
                .andExpect(jsonPath("$[0].targetUiConfigId").value("ui-list"))
                .andExpect(jsonPath("$[0].targetQueryTemplateId").value("query-default"))
                .andExpect(jsonPath("$[0].path[0].code").value("lines"));
        mvc.perform(get("/{moduleAlias}/associations/relation-overview", MODULE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moduleAlias").value(MODULE))
                .andExpect(jsonPath("$.downstream[0].associationViewCode").value("lines"));
    }

    @Test
    void shouldDiagnoseAssociationView() throws Exception {
        DynamicAssociationViewDescriptor association = new DynamicAssociationViewDescriptor(
                "lines",
                ENTITY,
                MODULE,
                "line",
                net.ximatai.muyun.spring.dynamic.metadata.AssociationViewDisplayMode.INLINE_LIST,
                "lines",
                null,
                net.ximatai.muyun.spring.dynamic.metadata.EntityViewType.LIST,
                true
        );
        Criteria targetCriteria = Criteria.of().like("summary", "Line");
        DynamicAssociationViewDiagnosis diagnosis = new DynamicAssociationViewDiagnosis(
                association,
                Criteria.of().eq("contractId", "contract-1"),
                targetCriteria,
                Criteria.of().eq("contractId", "contract-1").andGroup(targetCriteria.getRoot()),
                1,
                DynamicAssociationViewDiagnosisStatus.OK,
                "association view target matched"
        );
        when(service.associationView(MODULE, ENTITY, "lines")).thenReturn(association);
        when(service.queryCriteria(eq(MODULE), eq("line"), any())).thenReturn(targetCriteria);
        when(service.diagnoseAssociationView(eq(MODULE), eq(ENTITY), eq("contract-1"), eq("lines"),
                any(Criteria.class))).thenReturn(diagnosis);

        mvc.perform(post("/{moduleAlias}/view/{id}/associations/{viewCode}/diagnose", MODULE, "contract-1", "lines")
                        .contentType("application/json")
                        .content("""
                                {
                                  "conditions": [
                                    {"fieldName": "summary", "operator": "LIKE", "values": ["Line"]}
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.view.code").value("lines"))
                .andExpect(jsonPath("$.targetCount").value(1))
                .andExpect(jsonPath("$.status").value("OK"));
    }

    @Test
    void shouldSummarizePublishedListConfigWithSameQueryContext() throws Exception {
        PlatformPageConfigSnapshotService snapshotService = mock(PlatformPageConfigSnapshotService.class);
        PlatformQueryItemService queryItemService = mock(PlatformQueryItemService.class);
        ModuleMetadataFieldService moduleFieldService = mock(ModuleMetadataFieldService.class);
        MockMvc summaryMvc = MockMvcBuilders
                .standaloneSetup(controllerFixture(service, activeTenantVerifier).codePreview(codeBusinessPreviewService).generation(referenceGenerationFacade).query(snapshotService, queryItemService, moduleFieldService).build())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new PlatformWebExceptionHandler(), new DynamicWebExceptionHandler())
                .addFilters(new CurrentUserWebFilter(() -> java.util.Optional.of(
                        CurrentUser.tenantUser("user-1", "User", "tenant_a"))))
                .build();
        PlatformUiConfig uiConfig = new PlatformUiConfig();
        uiConfig.setId("ui-list");
        uiConfig.setUiSetId("set-list");
        uiConfig.setClientType(PlatformUiClientType.WEB);
        uiConfig.setPublished(true);
        uiConfig.setLayoutJson("""
                {
                  "summaryPanel": {
                    "items": [
                      {
                        "detailId": "module-field-amount",
                        "calcType": "sum",
                        "label": "Amount Total",
                        "precision": 2,
                        "formatter": "currency"
                      },
                      {
                        "detailId": "module-field-code",
                        "calcType": "count",
                        "label": "Contract Count"
                      },
                      {
                        "detailId": "module-field-line-amount",
                        "calcType": "sum",
                        "label": "Line Amount"
                      }
                    ]
                  }
                }
                """);
        PlatformUiSet uiSet = new PlatformUiSet();
        uiSet.setId("set-list");
        uiSet.setModuleAlias(MODULE);
        uiSet.setAlias("list");
        uiSet.setSetType(PlatformUiSetType.LIST);
        PlatformQueryTemplate template = new PlatformQueryTemplate();
        template.setId("tpl-active");
        template.setModuleAlias(MODULE);
        template.setAlias("active");
        when(snapshotService.snapshot(MODULE)).thenReturn(new PlatformPageConfigSnapshot(
                MODULE,
                List.of(uiSet),
                List.of(uiConfig),
                List.of(),
                List.of(template),
                List.of()
        ));
        when(moduleFieldService.resolve("module-field-amount")).thenReturn(resolvedModuleField(
                "module-field-amount", "amount"));
        when(moduleFieldService.resolve("module-field-code")).thenReturn(resolvedModuleField(
                "module-field-code", "code"));
        when(moduleFieldService.resolve("module-field-line-amount")).thenReturn(new ResolvedModuleMetadataField(
                "module-field-line-amount",
                MODULE,
                "relation-lines",
                "lines",
                RelationRole.CHILD,
                "metadata-line",
                "contract_line",
                "Contract Line",
                "line-amount",
                "lineAmount",
                "line_amount",
                "Line Amount",
                "decimal",
                MetadataFieldForm.PHYSICAL
        ));
        Criteria templateCriteria = Criteria.of().eq("status", "active");
        Criteria manualCriteria = Criteria.of().eq("code", "C-001");
        when(queryItemService.compile(eq("tpl-active"), any())).thenReturn(templateCriteria);
        when(mainEntity.queryCriteria(any())).thenReturn(manualCriteria);
        DynamicRecord first = new DynamicRecord(entity())
                .setValue("code", "C-001")
                .setValue("amount", new BigDecimal("10.00"));
        DynamicRecord second = new DynamicRecord(entity())
                .setValue("code", "C-002")
                .setValue("amount", new BigDecimal("5.50"));
        DynamicRecord blankCode = new DynamicRecord(entity())
                .setValue("code", "")
                .setValue("amount", new BigDecimal("0.50"));
        when(mainEntity.count(any(Criteria.class))).thenReturn(3L);
        when(mainEntity.list(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(first, second, blankCode));

        summaryMvc.perform(post("/{moduleAlias}/query/summary", MODULE)
                        .contentType("application/json")
                        .content("""
                                {
                                  "uiConfigId": "ui-list",
                                  "queryTemplateId": "tpl-active",
                                  "externalQueryValues": {
                                    "owner": "user-1"
                                  },
                                  "conditions": [
                                    {
                                      "fieldName": "code",
                                      "operator": "EQ",
                                      "values": ["C-001"]
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].detailId").value("module-field-amount"))
                .andExpect(jsonPath("$[0].calcType").value("sum"))
                .andExpect(jsonPath("$[0].label").value("Amount Total"))
                .andExpect(jsonPath("$[0].precision").value(2))
                .andExpect(jsonPath("$[0].formatter").value("currency"))
                .andExpect(jsonPath("$[0].value").value(16.0))
                .andExpect(jsonPath("$[1].detailId").value("module-field-code"))
                .andExpect(jsonPath("$[1].value").value(2))
                .andExpect(jsonPath("$[2].detailId").value("module-field-line-amount"))
                .andExpect(jsonPath("$[2].value").doesNotExist());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> externalValues = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Criteria> countCriteria = ArgumentCaptor.forClass(Criteria.class);
        ArgumentCaptor<Criteria> listCriteria = ArgumentCaptor.forClass(Criteria.class);
        verify(queryItemService).compile(eq("tpl-active"), externalValues.capture());
        verify(mainEntity).queryCriteria(any());
        verify(mainEntity).count(countCriteria.capture());
        verify(mainEntity).list(listCriteria.capture(), any(PageRequest.class));
        assertThat(externalValues.getValue()).containsEntry("owner", "user-1");
        assertThat(listCriteria.getValue()).isSameAs(countCriteria.getValue());
        assertThat(listCriteria.getValue()).isNotSameAs(templateCriteria);
        assertThat(listCriteria.getValue()).isNotSameAs(manualCriteria);
        assertThat(listCriteria.getValue().isEmpty()).isFalse();
    }

    @Test
    void shouldRejectSummaryWhenQueryMatchesTooManyRecords() throws Exception {
        PlatformPageConfigSnapshotService snapshotService = mock(PlatformPageConfigSnapshotService.class);
        ModuleMetadataFieldService moduleFieldService = mock(ModuleMetadataFieldService.class);
        MockMvc summaryMvc = MockMvcBuilders
                .standaloneSetup(controllerFixture(service, activeTenantVerifier).codePreview(codeBusinessPreviewService).generation(referenceGenerationFacade).query(snapshotService, null, moduleFieldService).build())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new PlatformWebExceptionHandler(), new DynamicWebExceptionHandler())
                .addFilters(new CurrentUserWebFilter(() -> java.util.Optional.of(
                        CurrentUser.tenantUser("user-1", "User", "tenant_a"))))
                .build();
        PlatformUiSet uiSet = new PlatformUiSet();
        uiSet.setId("set-list");
        uiSet.setModuleAlias(MODULE);
        uiSet.setAlias("list");
        uiSet.setSetType(PlatformUiSetType.LIST);
        PlatformUiConfig uiConfig = new PlatformUiConfig();
        uiConfig.setId("ui-list");
        uiConfig.setUiSetId("set-list");
        uiConfig.setClientType(PlatformUiClientType.WEB);
        uiConfig.setPublished(true);
        uiConfig.setLayoutJson("""
                {
                  "summaryPanel": {
                    "items": [
                      {
                        "detailId": "module-field-amount",
                        "calcType": "sum"
                      }
                    ]
                  }
                }
                """);
        when(snapshotService.snapshot(MODULE)).thenReturn(new PlatformPageConfigSnapshot(
                MODULE,
                List.of(uiSet),
                List.of(uiConfig),
                List.of(),
                List.of(),
                List.of()
        ));
        when(mainEntity.count(any(Criteria.class))).thenReturn(10_001L);

        summaryMvc.perform(post("/{moduleAlias}/query/summary", MODULE)
                        .contentType("application/json")
                        .content(json(Map.of("uiConfigId", "ui-list"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Summary panel query exceeds max records: 10000"));

        verify(mainEntity).count(any(Criteria.class));
        verify(mainEntity, org.mockito.Mockito.never()).list(any(Criteria.class), any(PageRequest.class));
    }

    @Test
    void shouldKeepDynamicDefaultQueryOperatorWhenWebQueryOmitsOperator() throws Exception {
        Criteria criteria = Criteria.of().like("code", "C-001");
        when(mainEntity.queryCriteria(any())).thenReturn(criteria);
        when(mainEntity.pageQuery(eq(criteria), any(PageRequest.class), any(Sort[].class)))
                .thenReturn(PageResult.of(List.of(), 0, PageRequest.of(1, 20)));

        mvc.perform(post("/{moduleAlias}/query", MODULE)
                        .contentType("application/json")
                        .content(json(Map.of(
                                "conditions", List.of(Map.of(
                                        "fieldName", "code",
                                        "values", List.of("C-001")
                                ))
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DynamicQueryCondition>> conditions = ArgumentCaptor.forClass(List.class);
        verify(mainEntity).queryCriteria(conditions.capture());
        assertThat(conditions.getValue().getFirst().operator()).isNull();
    }

    @Test
    void shouldExposeDateAndTimestampValuesAsStableWebStrings() throws Exception {
        Criteria criteria = Criteria.of().eq("signedDate", LocalDate.parse("2026-06-01"));
        DynamicRecord record = new DynamicRecord(entity())
                .setValue("signedDate", LocalDate.parse("2026-06-01"))
                .setValue("signedAt", Instant.parse("2026-06-01T02:03:04Z"));
        record.setId("contract-1");
        when(mainEntity.select("contract-1")).thenReturn(record);
        when(mainEntity.queryCriteria(any())).thenReturn(criteria);
        when(mainEntity.pageQuery(eq(criteria), any(PageRequest.class), any(Sort[].class)))
                .thenReturn(PageResult.of(List.of(record), 1, PageRequest.of(1, 20)));

        mvc.perform(get("/{moduleAlias}/view/{recordId}", MODULE, "contract-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.values.signedDate").value("2026-06-01"))
                .andExpect(jsonPath("$.values.signedAt").value("2026-06-01T02:03:04Z"));

        mvc.perform(post("/{moduleAlias}/query", MODULE)
                        .contentType("application/json")
                        .content(json(Map.of(
                                "conditions", List.of(Map.of(
                                        "fieldName", "signedDate",
                                        "operator", "EQ",
                                        "values", List.of("2026-06-01")
                                ))
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].values.signedDate").value("2026-06-01"))
                .andExpect(jsonPath("$.records[0].values.signedAt").value("2026-06-01T02:03:04Z"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DynamicQueryCondition>> conditions = ArgumentCaptor.forClass(List.class);
        verify(mainEntity).queryCriteria(conditions.capture());
        assertThat(conditions.getValue().getFirst().values()).isEqualTo(List.of("2026-06-01"));
    }

    @Test
    void shouldExposeMainEntityViewDeleteAndActionsThroughAliasRootContract() throws Exception {
        DynamicRecord record = new DynamicRecord(entity()).setValue("code", "C-001");
        record.setId("contract-1");
        when(mainEntity.select("contract-1")).thenReturn(record);
        when(mainEntity.delete("contract-1", 0)).thenReturn(1);
        when(service.actions(MODULE)).thenReturn(List.of(
                action("export", EntityActionLevel.LIST),
                action("submit", EntityActionLevel.RECORD, "view"),
                action("archive", EntityActionLevel.BATCH)
        ));

        mvc.perform(get("/{moduleAlias}/view/{recordId}", MODULE, "contract-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("contract-1"))
                .andExpect(jsonPath("$.values.code").value("C-001"));

        mvc.perform(post("/{moduleAlias}/delete/{recordId}", MODULE, "contract-1")
                        .contentType("application/json")
                        .content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(1));

        mvc.perform(get("/{moduleAlias}/actions", MODULE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("export"))
                .andExpect(jsonPath("$[0].permission.permissionCode").value(MODULE + ":view"))
                .andExpect(jsonPath("$[1].code").value("submit"))
                .andExpect(jsonPath("$[1].authInheritActionCode").value("view"))
                .andExpect(jsonPath("$[1].permission.permissionCode").value(MODULE + ":view"))
                .andExpect(jsonPath("$[1].permission.inheritActionCode").value("view"))
                .andExpect(jsonPath("$[1].permission.inheritPermissionCode").value(MODULE + ":view"))
                .andExpect(jsonPath("$[1].authInheritActionAlias").doesNotExist())
                .andExpect(jsonPath("$[2].code").value("archive"));

        verify(mainEntity, times(2)).select("contract-1");
        verify(mainEntity).delete("contract-1", 0);
        verify(service).actions(MODULE);
    }

    @Test
    void shouldHideUnauthorizedDynamicActionsFromActionLists() throws Exception {
        DynamicActionDescriptor export = action("export", EntityActionLevel.LIST);
        DynamicActionDescriptor submit = action("submit", EntityActionLevel.RECORD);
        DynamicActionDescriptor archive = action("archive", EntityActionLevel.BATCH);
        DynamicRecord existing = new DynamicRecord(entity()).setValue("code", "C-001");
        existing.setId("contract-1");
        when(service.mainEntityAlias(MODULE)).thenReturn(ENTITY);
        when(service.select(MODULE, ENTITY, "contract-1")).thenReturn(existing);
        when(service.actions(MODULE)).thenReturn(List.of(export, submit, archive));
        when(service.actionAuthorizationAvailability(eq(MODULE), eq("submit"), any()))
                .thenReturn(DynamicActionAvailability.unavailable("submit", "action permission denied"));
        when(service.actionAuthorizationAvailability(eq(MODULE), eq(ENTITY), eq("submit"), any()))
                .thenReturn(DynamicActionAvailability.unavailable("submit", "action permission denied"));
        when(service.actionAvailability(eq(MODULE), eq("export"), any(DynamicRecord.class)))
                .thenReturn(DynamicActionAvailability.available("export"));

        mvc.perform(get("/{moduleAlias}/actions", MODULE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("export"))
                .andExpect(jsonPath("$[1].code").value("archive"))
                .andExpect(jsonPath("$[2]").doesNotExist());

    }

    @Test
    void shouldExposeMainEntityTreeThroughStandardTreeWebContract() throws Exception {
        DynamicRecord first = new DynamicRecord(treeEntity()).setValue("code", "A");
        first.setId("A");
        first.setParentId("root");
        first.setSortOrder(1);
        DynamicRecord second = new DynamicRecord(treeEntity()).setValue("code", "B");
        second.setId("B");
        second.setParentId("root");
        second.setSortOrder(2);
        when(mainEntity.children("root")).thenReturn(List.of(first, second));
        when(mainEntity.children("A")).thenReturn(List.of());
        when(mainEntity.children("B")).thenReturn(List.of());

        mvc.perform(get("/{moduleAlias}/tree?flat=true", MODULE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].id").value("A"))
                .andExpect(jsonPath("$.records[0].values.code").value("A"))
                .andExpect(jsonPath("$.records[0].values.parentId").value("root"))
                .andExpect(jsonPath("$.records[1].id").value("B"));

        verify(mainEntity).children("root");
        verify(mainEntity).children("A");
        verify(mainEntity).children("B");
    }

    @Test
    void shouldExposeDynamicTreeNodeThroughStandardTreeWebContract() throws Exception {
        DynamicRecord root = new DynamicRecord(treeEntity()).setValue("code", "A");
        root.setId("A");
        root.setParentId("root");
        DynamicRecord child = new DynamicRecord(treeEntity()).setValue("code", "A-1");
        child.setId("A-1");
        child.setParentId("A");
        when(mainEntity.select("A")).thenReturn(root);
        when(mainEntity.children("A")).thenReturn(List.of(child));
        when(mainEntity.children("A-1")).thenReturn(List.of());

        mvc.perform(get("/{moduleAlias}/tree/{recordId}?flat=true", MODULE, "A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].id").value("A"))
                .andExpect(jsonPath("$.records[1].id").value("A-1"))
                .andExpect(jsonPath("$.records[1].values.parentId").value("A"));

        verify(mainEntity).select("A");
        verify(mainEntity).children("A");
        verify(mainEntity).children("A-1");
    }

    @Test
    void shouldExposeDynamicNestedTreeByDefault() throws Exception {
        DynamicRecord root = new DynamicRecord(treeEntity()).setValue("code", "A");
        root.setId("A");
        root.setParentId("root");
        DynamicRecord child = new DynamicRecord(treeEntity()).setValue("code", "A-1");
        child.setId("A-1");
        child.setParentId("A");
        when(mainEntity.select("A")).thenReturn(root);
        when(mainEntity.children("A")).thenReturn(List.of(child));
        when(mainEntity.children("A-1")).thenReturn(List.of());

        mvc.perform(get("/{moduleAlias}/tree/{recordId}", MODULE, "A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].record.id").value("A"))
                .andExpect(jsonPath("$.records[0].record.values.code").value("A"))
                .andExpect(jsonPath("$.records[0].children[0].record.id").value("A-1"))
                .andExpect(jsonPath("$.records[0].children[0].record.values.parentId").value("A"))
                .andExpect(jsonPath("$.records[0].children[0].children").isArray());

        verify(mainEntity).select("A");
        verify(mainEntity).children("A");
        verify(mainEntity).children("A-1");
    }

    @Test
    void shouldExcludeEntryNodeWhenIncludeSelfDisabled() throws Exception {
        DynamicRecord root = new DynamicRecord(treeEntity()).setValue("code", "A");
        root.setId("A");
        DynamicRecord child = new DynamicRecord(treeEntity()).setValue("code", "A-1");
        child.setId("A-1");
        child.setParentId("A");
        when(mainEntity.select("A")).thenReturn(root);
        when(mainEntity.children("A")).thenReturn(List.of(child));
        when(mainEntity.children("A-1")).thenReturn(List.of());

        mvc.perform(get("/{moduleAlias}/tree/{recordId}?includeSelf=false", MODULE, "A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].record.id").value("A-1"))
                .andExpect(jsonPath("$.records[0].children").isArray());

        verify(mainEntity).select("A");
        verify(mainEntity).children("A");
        verify(mainEntity).children("A-1");
    }

    @Test
    void shouldExposeDynamicTreeSortThroughStandardSortWebContract() throws Exception {
        when(mainEntity.describe()).thenReturn(DynamicEntityDescriptor.from(treeEntity()));

        mvc.perform(post("/{moduleAlias}/sort/{recordId}", MODULE, "A")
                        .contentType("application/json")
                        .content(json(Map.of(
                                "previousId", "B",
                                "parentId", "P"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(1));

        verify(service).moveInTreeFromAction(eq(MODULE), eq(ENTITY), eq("A"), eq("B"), eq(null), eq("P"), anyString());
    }

    @Test
    void shouldRejectEmptyDynamicTreeSortRequest() throws Exception {
        when(mainEntity.describe()).thenReturn(DynamicEntityDescriptor.from(treeEntity()));

        mvc.perform(post("/{moduleAlias}/sort/{recordId}", MODULE, "A")
                        .contentType("application/json")
                        .content(json(Map.of())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("tree sort requires previousId, nextId, or parentId"));
    }

    @Test
    void shouldExposeDynamicSortOnlyEntityThroughStandardSortWebContract() throws Exception {
        when(mainEntity.describe()).thenReturn(DynamicEntityDescriptor.from(sortableEntity()));

        mvc.perform(post("/{moduleAlias}/sort/{recordId}", MODULE, "A")
                        .contentType("application/json")
                        .content(json(Map.of("previousId", "B"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(1));

        verify(service).moveAfterFromAction(eq(MODULE), eq(ENTITY), eq("A"), eq("B"), anyString());
    }

    @Test
    void shouldRejectParentIdWhenDynamicEntityOnlySupportsSort() throws Exception {
        when(mainEntity.describe()).thenReturn(DynamicEntityDescriptor.from(sortableEntity()));

        mvc.perform(post("/{moduleAlias}/sort/{recordId}", MODULE, "A")
                        .contentType("application/json")
                        .content(json(Map.of("parentId", "P"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("sort parentId requires TREE capability"));
    }

    @Test
    void shouldRejectTreeWebWhenDynamicMainEntityDoesNotSupportTree() throws Exception {
        when(mainEntity.children("root"))
                .thenThrow(new PlatformException("dynamic entity does not support capability: TREE"));

        mvc.perform(get("/{moduleAlias}/tree", MODULE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(PlatformErrorCodes.VALIDATION_FAILED))
                .andExpect(jsonPath("$.message").value("dynamic entity does not support capability: TREE"));
    }

    @Test
    void shouldExposeDynamicEnableThroughStandardEnableWebContract() throws Exception {
        when(mainEntity.enable("contract-1", 0)).thenReturn(1);
        when(mainEntity.disable("contract-1", 0)).thenReturn(1);

        mvc.perform(post("/{moduleAlias}/enable/{recordId}", MODULE, "contract-1")
                        .contentType("application/json")
                        .content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(1));
        mvc.perform(post("/{moduleAlias}/disable/{recordId}", MODULE, "contract-1")
                        .contentType("application/json")
                        .content("{\"version\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(1));

        verify(mainEntity).enable("contract-1", 0);
        verify(mainEntity).disable("contract-1", 0);
    }

    @Test
    void shouldRequireVersionForDynamicStandardRecordActions() throws Exception {
        mvc.perform(post("/{moduleAlias}/delete/{recordId}", MODULE, "contract-1")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/{moduleAlias}/enable/{recordId}", MODULE, "contract-1")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/{moduleAlias}/disable/{recordId}", MODULE, "contract-1")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectEnableWebWhenDynamicMainEntityDoesNotSupportEnable() throws Exception {
        when(mainEntity.enable("contract-1", 0))
                .thenThrow(new PlatformException("dynamic entity does not support capability: ENABLE"));

        mvc.perform(post("/{moduleAlias}/enable/{recordId}", MODULE, "contract-1")
                        .contentType("application/json")
                        .content("{\"version\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(PlatformErrorCodes.VALIDATION_FAILED))
                .andExpect(jsonPath("$.message").value("dynamic entity does not support capability: ENABLE"));
    }

    @Test
    void shouldExecuteActionWithRecordPayload() throws Exception {
        DynamicActionDescriptor submit = action("submit", EntityActionLevel.RECORD);
        when(service.action(MODULE, "submit")).thenReturn(submit);
        when(service.mainEntityAlias(MODULE)).thenReturn(ENTITY);
        when(service.actionEntityAlias(MODULE, "submit")).thenReturn(ENTITY);
        when(service.newRecord(MODULE, ENTITY)).thenAnswer(invocation -> new DynamicRecord(entity()));
        when(service.executeAction(eq(MODULE), eq("submit"), any(DynamicActionExecutionRequest.class)))
                .thenReturn(new DynamicActionExecutionResult(
                        new DynamicActionExecutionContext(MODULE, ENTITY, "submit", submit,
                                "contract-1", "trace-1", "tenant-1", false,
                                DynamicActionAvailability.available("submit")),
                        "ok",
                        DynamicActionResultBody.refreshed("ok").message("已提交")));

        mvc.perform(post("/{moduleAlias}/{actionCode}/{recordId}", MODULE, "submit", "contract-1")
                        .contentType("application/json")
                        .content(json(Map.of(
                                "record", Map.of("values", Map.of("code", "C-001")),
                                "conditions", List.of(Map.of(
                                        "fieldName", "code",
                                        "operator", "EQ",
                                        "values", List.of("C-001")
                                )),
                                "payload", Map.of("comment", "submit")
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.context.moduleAlias").value(MODULE))
                .andExpect(jsonPath("$.context.actionCode").value("submit"))
                .andExpect(jsonPath("$.context.actionLevel").value("RECORD"))
                .andExpect(jsonPath("$.context.executorType").value("SERVICE"))
                .andExpect(jsonPath("$.context.recordId").value("contract-1"))
                .andExpect(jsonPath("$.context.traceId").value("trace-1"))
                .andExpect(jsonPath("$.details.context.entityAlias").doesNotExist())
                .andExpect(jsonPath("$.details.context.action").doesNotExist())
                .andExpect(jsonPath("$.body.type").value("VALUE"))
                .andExpect(jsonPath("$.body.value").value("ok"))
                .andExpect(jsonPath("$.body.message").value("已提交"))
                .andExpect(jsonPath("$.body.refresh").value(true));

        ArgumentCaptor<DynamicActionExecutionRequest> request = ArgumentCaptor.forClass(DynamicActionExecutionRequest.class);
        verify(service).executeAction(eq(MODULE), eq("submit"), request.capture());
        assertThat(request.getValue().recordId()).isEqualTo("contract-1");
        assertThat(request.getValue().record().getValue("code")).isEqualTo("C-001");
        assertThat(request.getValue().queryConditions().iterator().next().fieldName()).isEqualTo("code");
        assertThat(request.getValue().payload()).containsEntry("comment", "submit");
    }

    @Test
    void shouldRejectVirtualFieldInActionSorts() throws Exception {
        DynamicActionDescriptor submit = action("submit", EntityActionLevel.LIST);
        when(service.action(MODULE, "submit")).thenReturn(submit);
        when(service.mainEntityAlias(MODULE)).thenReturn(ENTITY);
        when(service.actionEntityAlias(MODULE, "submit")).thenReturn(ENTITY);
        when(service.entity(MODULE, ENTITY)).thenReturn(mainEntity);

        mvc.perform(post("/{moduleAlias}/{actionCode}", MODULE, "submit")
                        .contentType("application/json")
                        .content("""
                                {
                                  "sorts": [
                                    {"field": "displayCode", "desc": true}
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("Sort field is not a physical dynamic field: displayCode"));

        verify(service, never()).executeAction(eq(MODULE), eq("submit"), any(DynamicActionExecutionRequest.class));
    }


    @Test
    void shouldExecuteContributedWorkflowActionThroughRecordActionPath() throws Exception {
        DynamicActionDescriptor syncWorkflow = workflowAction("syncWorkflow");
        when(service.action(MODULE, "syncWorkflow")).thenReturn(syncWorkflow);
        when(service.mainEntityAlias(MODULE)).thenReturn(ENTITY);
        when(service.actionEntityAlias(MODULE, "syncWorkflow")).thenReturn(ENTITY);
        when(service.executeAction(eq(MODULE), eq("syncWorkflow"), any(DynamicActionExecutionRequest.class)))
                .thenReturn(new DynamicActionExecutionResult(
                        new DynamicActionExecutionContext(MODULE, ENTITY, "syncWorkflow", syncWorkflow,
                                "contract-1", "trace-1", "tenant-1", false,
                                DynamicActionAvailability.available("syncWorkflow")),
                        "ok",
                        DynamicActionResultBody.refreshed("ok")));

        mvc.perform(post("/{moduleAlias}/{actionCode}/{recordId}", MODULE, "syncWorkflow", "contract-1")
                        .contentType("application/json")
                        .content(json(Map.of("payload", Map.of(
                                "selectedDirectLinkKey", "leftRoute",
                                "selectedReason", "choose left"
                        )))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.context.actionCode").value("syncWorkflow"))
                .andExpect(jsonPath("$.context.actionLevel").value("RECORD"))
                .andExpect(jsonPath("$.context.executorType").value("SERVICE"))
                .andExpect(jsonPath("$.context.recordId").value("contract-1"))
                .andExpect(jsonPath("$.body.refresh").value(true));

        ArgumentCaptor<DynamicActionExecutionRequest> request = ArgumentCaptor.forClass(DynamicActionExecutionRequest.class);
        verify(service).executeAction(eq(MODULE), eq("syncWorkflow"), request.capture());
        assertThat(request.getValue().recordId()).isEqualTo("contract-1");
        assertThat(request.getValue().payload()).containsEntry("selectedDirectLinkKey", "leftRoute")
                .containsEntry("selectedReason", "choose left");
    }

    @Test
    void shouldExposeDialogActionResultThroughStableWebResponse() throws Exception {
        DynamicActionDescriptor submitDialog = dialogAction("submitDialog", EntityActionLevel.RECORD);
        DynamicActionDialog dialog = new DynamicActionDialog("contractSubmitDialog", "提交合同",
                "submitDialog", "submit", "/" + MODULE + "/submit/contract-1", "contract-1", true, null);
        when(service.action(MODULE, "submitDialog")).thenReturn(submitDialog);
        when(service.mainEntityAlias(MODULE)).thenReturn(ENTITY);
        when(service.executeAction(eq(MODULE), eq("submitDialog"), any(DynamicActionExecutionRequest.class)))
                .thenReturn(new DynamicActionExecutionResult(
                        new DynamicActionExecutionContext(MODULE, ENTITY, "submitDialog", submitDialog,
                                "contract-1", "trace-1", "tenant-1", false,
                                DynamicActionAvailability.available("submitDialog")),
                        dialog,
                        DynamicActionResultBody.dialog(dialog)));

        mvc.perform(post("/{moduleAlias}/{actionCode}/{recordId}", MODULE, "submitDialog", "contract-1")
                        .contentType("application/json")
                        .content(json(Map.of())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.context.moduleAlias").value(MODULE))
                .andExpect(jsonPath("$.context.actionCode").value("submitDialog"))
                .andExpect(jsonPath("$.context.actionLevel").value("RECORD"))
                .andExpect(jsonPath("$.context.executorType").value("DIALOG"))
                .andExpect(jsonPath("$.context.recordId").value("contract-1"))
                .andExpect(jsonPath("$.body.type").value("DIALOG"))
                .andExpect(jsonPath("$.body.value.dialogKey").value("contractSubmitDialog"))
                .andExpect(jsonPath("$.body.value.title").value("提交合同"))
                .andExpect(jsonPath("$.body.value.actionCode").value("submitDialog"))
                .andExpect(jsonPath("$.body.value.submitActionCode").value("submit"))
                .andExpect(jsonPath("$.body.value.submitPath").value("/" + MODULE + "/submit/contract-1"))
                .andExpect(jsonPath("$.body.value.recordId").value("contract-1"))
                .andExpect(jsonPath("$.body.value.refreshOnSuccess").value(true))
                .andExpect(jsonPath("$.body.value.refreshStrategy.list").value(true))
                .andExpect(jsonPath("$.body.value.refreshStrategy.detail").value(true))
                .andExpect(jsonPath("$.body.refresh").value(false));
    }

    @Test
    void shouldRejectActionRecordIdMismatch() throws Exception {
        when(service.action(MODULE, "submit")).thenReturn(action("submit", EntityActionLevel.RECORD));
        when(service.mainEntityAlias(MODULE)).thenReturn(ENTITY);
        when(service.actionEntityAlias(MODULE, "submit")).thenReturn(ENTITY);
        when(service.newRecord(MODULE, ENTITY)).thenAnswer(invocation -> new DynamicRecord(entity()));

        mvc.perform(post("/{moduleAlias}/{actionCode}/{recordId}", MODULE, "submit", "contract-1")
                        .contentType("application/json")
                        .content(json(Map.of(
                                "record", Map.of("id", "contract-2", "values", Map.of("code", "C-001"))
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(PlatformErrorCodes.VALIDATION_FAILED))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("action request recordId must match record.id"));
    }

    @Test
    void shouldExposeActionFailureStageAndContext() throws Exception {
        DynamicActionDescriptor submit = action("submit", EntityActionLevel.RECORD);
        DynamicActionExecutionContext context = new DynamicActionExecutionContext(MODULE, ENTITY, "submit", submit,
                "contract-1", "trace-1", "tenant-1", false,
                DynamicActionAvailability.unavailable("submit", "只有草稿合同可以提交"));
        when(service.action(MODULE, "submit")).thenReturn(submit);
        when(service.mainEntityAlias(MODULE)).thenReturn(ENTITY);
        when(service.executeAction(eq(MODULE), eq("submit"), any(DynamicActionExecutionRequest.class)))
                .thenThrow(new DynamicActionExecutionException("只有草稿合同可以提交", context,
                        DynamicActionExecutionException.STAGE_AVAILABILITY, null));

        mvc.perform(post("/{moduleAlias}/{actionCode}/{recordId}", MODULE, "submit", "contract-1")
                        .header(RequestTraceContext.TRACE_ID_HEADER, "trace-1")
                        .contentType("application/json")
                        .content(json(Map.of())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DYNAMIC_ACTION_FAILED"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("只有草稿合同可以提交"))
                .andExpect(jsonPath("$.details.failureStage").value("availability"))
                .andExpect(jsonPath("$.traceId").value("trace-1"))
                .andExpect(jsonPath("$.details.context.moduleAlias").value(MODULE))
                .andExpect(jsonPath("$.details.context.actionCode").value("submit"))
                .andExpect(jsonPath("$.details.context.actionLevel").value("RECORD"))
                .andExpect(jsonPath("$.details.context.executorType").value("SERVICE"))
                .andExpect(jsonPath("$.details.context.recordId").value("contract-1"))
                .andExpect(jsonPath("$.details.context.traceId").value("trace-1"));
    }

    @Test
    void shouldExposeExecuteActionFailureWithStableErrorShape() throws Exception {
        DynamicActionDescriptor submit = action("submit", EntityActionLevel.RECORD);
        DynamicActionExecutionContext context = new DynamicActionExecutionContext(MODULE, ENTITY, "submit", submit,
                "contract-1", "trace-2", "tenant-1", false,
                DynamicActionAvailability.available("submit"));
        when(service.action(MODULE, "submit")).thenReturn(submit);
        when(service.mainEntityAlias(MODULE)).thenReturn(ENTITY);
        when(service.executeAction(eq(MODULE), eq("submit"), any(DynamicActionExecutionRequest.class)))
                .thenThrow(new DynamicActionExecutionException("submit failed", context,
                        DynamicActionExecutionException.STAGE_EXECUTE, new IllegalStateException("boom")));

        mvc.perform(post("/{moduleAlias}/{actionCode}/{recordId}", MODULE, "submit", "contract-1")
                        .contentType("application/json")
                        .content(json(Map.of())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DYNAMIC_ACTION_FAILED"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("submit failed"))
                .andExpect(jsonPath("$.details.failureStage").value("execute"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.details.context.actionCode").value("submit"))
                .andExpect(jsonPath("$.details.context.actionLevel").value("RECORD"))
                .andExpect(jsonPath("$.details.context.executorType").value("SERVICE"));
    }

    @Test
    void shouldExposeActionFailureWithoutContextAsStableErrorShape() throws Exception {
        when(service.action(MODULE, "submit")).thenReturn(action("submit", EntityActionLevel.RECORD));
        when(service.mainEntityAlias(MODULE)).thenReturn(ENTITY);
        when(service.executeAction(eq(MODULE), eq("submit"), any(DynamicActionExecutionRequest.class)))
                .thenThrow(new DynamicActionExecutionException("submit failed", null));

        mvc.perform(post("/{moduleAlias}/{actionCode}/{recordId}", MODULE, "submit", "contract-1")
                        .contentType("application/json")
                        .content(json(Map.of())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DYNAMIC_ACTION_FAILED"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("submit failed"))
                .andExpect(jsonPath("$.details.failureStage").value("execute"))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.details.context").doesNotExist());
    }

    @Test
    void shouldRejectActionResponseThatWouldExposeInternalCriteria() throws Exception {
        Criteria criteria = Criteria.of().eq("code", "C-001");
        when(service.action(MODULE, "customCriteria")).thenReturn(action("customCriteria", EntityActionLevel.ANY));
        when(service.mainEntityAlias(MODULE)).thenReturn(ENTITY);
        when(service.executeAction(eq(MODULE), eq("customCriteria"), any(DynamicActionExecutionRequest.class)))
                .thenReturn(new DynamicActionExecutionResult(null, criteria, DynamicActionResultBody.of(criteria)));
        mvc.perform(post("/{moduleAlias}/{actionCode}", MODULE, "customCriteria")
                        .contentType("application/json")
                        .content(json(Map.of())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("dynamic web response does not expose internal Criteria"));
    }

    @Test
    void shouldRejectReservedOpenApiAsActionPath() throws Exception {
        mvc.perform(post("/{moduleAlias}/{actionCode}/{recordId}", MODULE, "openapi", "contract-1")
                        .contentType("application/json")
                        .content(json(Map.of())))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldRejectReservedSortAsActionPath() throws Exception {
        mvc.perform(post("/{moduleAlias}/{actionCode}", MODULE, "sort")
                        .contentType("application/json")
                        .content(json(Map.of())))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldRejectReservedReferenceAsActionPath() throws Exception {
        mvc.perform(post("/{moduleAlias}/{actionCode}", MODULE, "reference")
                        .contentType("application/json")
                        .content(json(Map.of())))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldExecuteListAndBatchActionsThroughStaticLikePaths() throws Exception {
        DynamicActionDescriptor publish = action("publish", EntityActionLevel.LIST);
        DynamicActionDescriptor archive = action("archive", EntityActionLevel.BATCH);
        DynamicActionDescriptor batchDelete = standardBatchDeleteAction();
        DynamicActionDescriptor refreshSelected = action("refreshSelected", EntityActionLevel.ANY);
        when(service.action(MODULE, "publish")).thenReturn(publish);
        when(service.action(MODULE, "archive")).thenReturn(archive);
        when(service.action(MODULE, "batchDelete")).thenReturn(batchDelete);
        when(service.action(MODULE, "refreshSelected")).thenReturn(refreshSelected);
        when(service.mainEntityAlias(MODULE)).thenReturn(ENTITY);
        when(service.executeAction(eq(MODULE), eq("publish"), any(DynamicActionExecutionRequest.class)))
                .thenReturn(new DynamicActionExecutionResult(
                        new DynamicActionExecutionContext(MODULE, ENTITY, "publish", publish,
                                null, "trace-list", "tenant-1", false,
                                DynamicActionAvailability.available("publish")),
                        "ok",
                        DynamicActionResultBody.redirect("/exports/contract.xlsx", "发布已生成")));
        when(service.executeAction(eq(MODULE), eq("archive"), any(DynamicActionExecutionRequest.class)))
                .thenReturn(new DynamicActionExecutionResult(
                        new DynamicActionExecutionContext(MODULE, ENTITY, "archive", archive,
                                null, "trace-batch", "tenant-1", false,
                                DynamicActionAvailability.available("archive")),
                        2,
                        DynamicActionResultBody.changedCount(2, "已归档 2 条")));
        when(service.executeAction(eq(MODULE), eq("batchDelete"), any(DynamicActionExecutionRequest.class)))
                .thenReturn(new DynamicActionExecutionResult(
                        new DynamicActionExecutionContext(MODULE, ENTITY, "batchDelete", batchDelete,
                                null, "trace-batch-delete", "tenant-1", false,
                                DynamicActionAvailability.available("batchDelete")),
                        2,
                        DynamicActionResultBody.changedCount(2)));
        when(service.executeAction(eq(MODULE), eq("refreshSelected"), any(DynamicActionExecutionRequest.class)))
                .thenReturn(new DynamicActionExecutionResult(null, "ok",
                        DynamicActionResultBody.refreshed("ok")));

        mvc.perform(post("/{moduleAlias}/{actionCode}", MODULE, "publish")
                        .contentType("application/json")
                        .content(json(Map.of("payload", Map.of("format", "xlsx")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.context.actionLevel").value("LIST"))
                .andExpect(jsonPath("$.context.executorType").value("SERVICE"))
                .andExpect(jsonPath("$.body.type").value("NONE"))
                .andExpect(jsonPath("$.body.value").doesNotExist())
                .andExpect(jsonPath("$.body.message").value("发布已生成"))
                .andExpect(jsonPath("$.body.refresh").value(false))
                .andExpect(jsonPath("$.body.redirectTo").value("/exports/contract.xlsx"));

        mvc.perform(post("/{moduleAlias}/{actionCode}/batch", MODULE, "archive")
                        .contentType("application/json")
                .content(json(Map.of("ids", List.of("contract-1", "contract-2")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.context.actionLevel").value("BATCH"))
                .andExpect(jsonPath("$.context.executorType").value("SERVICE"))
                .andExpect(jsonPath("$.body.type").value("COUNT"))
                .andExpect(jsonPath("$.body.value").value(2))
                .andExpect(jsonPath("$.body.message").value("已归档 2 条"))
                .andExpect(jsonPath("$.body.refresh").value(true))
                .andExpect(jsonPath("$.body.refreshStrategy.list").value(true))
                .andExpect(jsonPath("$.body.refreshStrategy.detail").value(true));

        mvc.perform(post("/{moduleAlias}/{actionCode}/batch", MODULE, "batchDelete")
                        .contentType("application/json")
                .content(json(Map.of("ids", List.of("contract-1", "contract-2")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.context.actionLevel").value("BATCH"))
                .andExpect(jsonPath("$.body.type").value("COUNT"))
                .andExpect(jsonPath("$.body.value").value(2));

        mvc.perform(post("/{moduleAlias}/{actionCode}/batch", MODULE, "refreshSelected")
                        .contentType("application/json")
                .content(json(Map.of("ids", List.of("contract-1")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.body.type").value("VALUE"))
                .andExpect(jsonPath("$.body.value").value("ok"))
                .andExpect(jsonPath("$.body.refresh").value(true))
                .andExpect(jsonPath("$.body.refreshStrategy.list").value(true))
                .andExpect(jsonPath("$.body.refreshStrategy.detail").value(true));

        ArgumentCaptor<DynamicActionExecutionRequest> batchRequest = ArgumentCaptor.forClass(DynamicActionExecutionRequest.class);
        verify(service).executeAction(eq(MODULE), eq("archive"), batchRequest.capture());
        assertThat(batchRequest.getValue().ids()).containsExactly("contract-1", "contract-2");
        ArgumentCaptor<DynamicActionExecutionRequest> batchDeleteRequest = ArgumentCaptor.forClass(DynamicActionExecutionRequest.class);
        verify(service).executeAction(eq(MODULE), eq("batchDelete"), batchDeleteRequest.capture());
        assertThat(batchDeleteRequest.getValue().ids()).containsExactly("contract-1", "contract-2");
    }

    @Test
    void shouldRejectCustomBatchDeleteActionPathEvenWhenCodeMatchesStandardPath() throws Exception {
        when(service.action(MODULE, "batchDelete")).thenReturn(action("batchDelete", EntityActionLevel.BATCH, "delete"));

        mvc.perform(post("/{moduleAlias}/{actionCode}/batch", MODULE, "batchDelete")
                        .contentType("application/json")
                        .content(json(Map.of("ids", List.of("contract-1")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("dynamic action path is reserved: batchDelete"));

        verify(service, never()).executeAction(eq(MODULE), eq("batchDelete"), any(DynamicActionExecutionRequest.class));
    }

    @Test
        void shouldRejectActionPathWhenLevelDoesNotMatch() throws Exception {
        when(service.action(MODULE, "submit")).thenReturn(action("submit", EntityActionLevel.RECORD));
        when(service.action(MODULE, "publish")).thenReturn(action("publish", EntityActionLevel.LIST));
        when(service.action(MODULE, "archive")).thenReturn(action("archive", EntityActionLevel.BATCH));

        mvc.perform(post("/{moduleAlias}/{actionCode}", MODULE, "submit")
                        .contentType("application/json")
                        .content(json(Map.of())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("dynamic action does not support list path: submit"));

        mvc.perform(post("/{moduleAlias}/{actionCode}/{recordId}", MODULE, "publish", "contract-1")
                        .contentType("application/json")
                        .content(json(Map.of())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("dynamic action does not support record path: publish"));

        mvc.perform(post("/{moduleAlias}/{actionCode}", MODULE, "archive")
                        .contentType("application/json")
                        .content(json(Map.of())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("dynamic action does not support list path: archive"));

        mvc.perform(post("/{moduleAlias}/{actionCode}/batch", MODULE, "publish")
                        .contentType("application/json")
                        .content(json(Map.of("ids", List.of("contract-1")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("dynamic action does not support batch path: publish"));
    }

    @Test
    void shouldResolveMainEntityReferenceWithoutEntityLevelPath() throws Exception {
        Criteria criteria = Criteria.of().eq("customerType", "VIP");
        when(service.mainEntityAlias(MODULE)).thenReturn(ENTITY);
        when(service.queryCriteria(eq("crm.customer"), eq("customer"), any())).thenReturn(criteria);
        when(service.resolveFieldReference(eq(MODULE), eq(ENTITY), eq("customerId"), any(DynamicReferenceResolveRequest.class)))
                .thenReturn(new DynamicReferenceResolveResponse(
                        DynamicReferenceResolveStatus.OK,
                        DynamicReferenceResolveMode.QUERY,
                        List.of(),
                        List.of(),
                        0,
                        20,
                        0
                ));

        mvc.perform(post("/{moduleAlias}/references/{fieldName}/resolve", MODULE, "customerId")
                        .contentType("application/json")
                        .content(json(Map.of(
                                "mode", "QUERY",
                                "fuzzy", "ximatai",
                                "includeProjections", false,
                                "conditions", List.of(Map.of(
                                        "fieldName", "customerType",
                                        "operator", "EQ",
                                        "values", List.of("VIP")
                                )),
                                "formValues", Map.of("customerRegion", "north")
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"))
                .andExpect(jsonPath("$.mode").value("QUERY"));

        ArgumentCaptor<DynamicReferenceResolveRequest> request = ArgumentCaptor.forClass(DynamicReferenceResolveRequest.class);
        verify(service).resolveFieldReference(eq(MODULE), eq(ENTITY), eq("customerId"), request.capture());
        assertThat(request.getValue().fuzzy()).isEqualTo("ximatai");
        assertThat(request.getValue().criteria()).isSameAs(criteria);
        assertThat(request.getValue().includeProjections()).isFalse();
        assertThat(request.getValue().formValues()).containsEntry("customerRegion", "north");
    }

    @Test
    void shouldApplyReferenceQueryTemplateBeforeResolvingCandidates() throws Exception {
        PlatformPageConfigSnapshotService snapshotService = mock(PlatformPageConfigSnapshotService.class);
        PlatformQueryItemService queryItemService = mock(PlatformQueryItemService.class);
        ModuleMetadataFieldService moduleFieldService = mock(ModuleMetadataFieldService.class);
        MockMvc referenceMvc = MockMvcBuilders
                .standaloneSetup(controllerFixture(service, activeTenantVerifier).codePreview(codeBusinessPreviewService).generation(referenceGenerationFacade).query(snapshotService, queryItemService, moduleFieldService).build())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new PlatformWebExceptionHandler(), new DynamicWebExceptionHandler())
                .addFilters(new CurrentUserWebFilter(() -> java.util.Optional.of(
                        CurrentUser.tenantUser("user-1", "User", "tenant_a"))))
                .build();
        PlatformUiSet sourceSet = new PlatformUiSet();
        sourceSet.setId("source-form-set");
        sourceSet.setModuleAlias(MODULE);
        sourceSet.setAlias("form");
        sourceSet.setSetType(PlatformUiSetType.FORM);
        PlatformUiConfig sourceConfig = new PlatformUiConfig();
        sourceConfig.setId("source-form");
        sourceConfig.setUiSetId("source-form-set");
        sourceConfig.setPublished(true);
        PlatformUiSet targetSet = new PlatformUiSet();
        targetSet.setId("target-reference-set");
        targetSet.setModuleAlias("crm.customer");
        targetSet.setAlias("reference");
        targetSet.setSetType(PlatformUiSetType.REFERENCE);
        PlatformUiConfig targetConfig = new PlatformUiConfig();
        targetConfig.setId("target-reference");
        targetConfig.setUiSetId("target-reference-set");
        targetConfig.setPublished(true);
        PlatformQueryTemplate template = new PlatformQueryTemplate();
        template.setId("customer-active");
        template.setModuleAlias("crm.customer");
        template.setAlias("active");
        PlatformQueryTemplate overrideTemplate = new PlatformQueryTemplate();
        overrideTemplate.setId("customer-region");
        overrideTemplate.setModuleAlias("crm.customer");
        overrideTemplate.setAlias("region");
        when(service.reference(MODULE, ENTITY, "customerId"))
                .thenReturn(reference("customerId", "customer-active"));
        when(snapshotService.snapshot(MODULE)).thenReturn(new PlatformPageConfigSnapshot(
                MODULE, List.of(sourceSet), List.of(sourceConfig), List.of(), List.of(), List.of()));
        when(snapshotService.snapshot("crm.customer")).thenReturn(new PlatformPageConfigSnapshot(
                "crm.customer", List.of(targetSet), List.of(targetConfig), List.of(),
                List.of(template, overrideTemplate), List.of()));
        Criteria templateCriteria = Criteria.of().eq("region", "north");
        Criteria manualCriteria = Criteria.of().eq("customerType", "VIP");
        when(queryItemService.compile(eq("customer-region"), any())).thenReturn(templateCriteria);
        when(service.queryCriteria(eq("crm.customer"), eq("customer"), any())).thenReturn(manualCriteria);
        when(service.resolveFieldReference(eq(MODULE), eq(ENTITY), eq("customerId"), any(DynamicReferenceResolveRequest.class)))
                .thenReturn(new DynamicReferenceResolveResponse(
                        DynamicReferenceResolveStatus.OK,
                        DynamicReferenceResolveMode.QUERY,
                        List.of(),
                        List.of(),
                        0,
                        20,
                        0
                ));

        referenceMvc.perform(post("/{moduleAlias}/references/{fieldName}/resolve", MODULE, "customerId")
                        .contentType("application/json")
                        .content(json(Map.of(
                                "includeProjections", false,
                                "sourceUiConfigId", "source-form",
                                "uiConfigId", "target-reference",
                                "queryTemplateId", "customer-region",
                                "externalQueryValues", Map.of("region", "north"),
                                "conditions", List.of(Map.of(
                                        "fieldName", "customerType",
                                        "operator", "EQ",
                                        "values", List.of("VIP")
                                ))
                        ))))
                .andExpect(status().isOk());

        ArgumentCaptor<DynamicReferenceResolveRequest> request = ArgumentCaptor.forClass(DynamicReferenceResolveRequest.class);
        verify(service).resolveFieldReference(eq(MODULE), eq(ENTITY), eq("customerId"), request.capture());
        assertThat(request.getValue().criteria()).isNotSameAs(templateCriteria);
        assertThat(request.getValue().criteria()).isNotSameAs(manualCriteria);
        assertThat(request.getValue().criteria().isEmpty()).isFalse();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> externalValues = ArgumentCaptor.forClass(Map.class);
        verify(queryItemService).compile(eq("customer-region"), externalValues.capture());
        assertThat(externalValues.getValue()).containsEntry("region", "north");
        verify(service).queryCriteria(eq("crm.customer"), eq("customer"), any());
    }

    @Test
    void shouldOverrideReferenceQueryTemplateWithoutUiContextFieldService() throws Exception {
        PlatformPageConfigSnapshotService snapshotService = mock(PlatformPageConfigSnapshotService.class);
        PlatformQueryItemService queryItemService = mock(PlatformQueryItemService.class);
        MockMvc referenceMvc = MockMvcBuilders
                .standaloneSetup(controllerFixture(service, activeTenantVerifier).codePreview(codeBusinessPreviewService).generation(referenceGenerationFacade).query(snapshotService, queryItemService, null).build())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new PlatformWebExceptionHandler(), new DynamicWebExceptionHandler())
                .addFilters(new CurrentUserWebFilter(() -> java.util.Optional.of(
                        CurrentUser.tenantUser("user-1", "User", "tenant_a"))))
                .build();
        PlatformQueryTemplate template = new PlatformQueryTemplate();
        template.setId("customer-region");
        template.setModuleAlias("crm.customer");
        template.setAlias("region");
        when(service.reference(MODULE, ENTITY, "customerId"))
                .thenReturn(reference("customerId", "customer-active"));
        when(snapshotService.snapshot("crm.customer")).thenReturn(new PlatformPageConfigSnapshot(
                "crm.customer", List.of(), List.of(), List.of(), List.of(template), List.of()));
        Criteria templateCriteria = Criteria.of().eq("region", "north");
        when(queryItemService.compile(eq("customer-region"), any())).thenReturn(templateCriteria);
        when(service.resolveFieldReference(eq(MODULE), eq(ENTITY), eq("customerId"), any(DynamicReferenceResolveRequest.class)))
                .thenReturn(new DynamicReferenceResolveResponse(
                        DynamicReferenceResolveStatus.OK,
                        DynamicReferenceResolveMode.QUERY,
                        List.of(),
                        List.of(),
                        0,
                        20,
                        0
                ));

        referenceMvc.perform(post("/{moduleAlias}/references/{fieldName}/resolve", MODULE, "customerId")
                        .contentType("application/json")
                        .content(json(Map.of(
                                "queryTemplateId", "customer-region",
                                "externalQueryValues", Map.of("region", "north")
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OK"));

        ArgumentCaptor<DynamicReferenceResolveRequest> request = ArgumentCaptor.forClass(DynamicReferenceResolveRequest.class);
        verify(service).resolveFieldReference(eq(MODULE), eq(ENTITY), eq("customerId"), request.capture());
        assertThat(request.getValue().criteria()).isSameAs(templateCriteria);
        verify(queryItemService).compile(eq("customer-region"), any());
    }

    @Test
    void shouldGenerateDraftsFromReferenceFieldWithoutExposingRuleAction() throws Exception {
        when(service.mainEntityAlias(MODULE)).thenReturn(ENTITY);
        when(referenceGenerationFacade.generateFromReference(MODULE, ENTITY, "opportunityId", "opp-1"))
                .thenReturn(new RecordGenerationResult(
                        "rule-1",
                        "generateContract",
                        "sales.opportunity",
                        "opp-1",
                        MODULE,
                        "batch-1",
                        List.of()
                ));

        mvc.perform(post("/{moduleAlias}/references/{fieldName}/generate", MODULE, "opportunityId")
                        .contentType("application/json")
                        .content(json(Map.of("sourceRecordId", "opp-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleId").value("rule-1"))
                .andExpect(jsonPath("$.sourceRecordId").value("opp-1"))
                .andExpect(jsonPath("$.targetModuleAlias").value(MODULE));

        verify(referenceGenerationFacade).generateFromReference(MODULE, ENTITY, "opportunityId", "opp-1");
    }

    @Test
    void shouldConfirmGeneratedDraftWithOriginContext() throws Exception {
        RecordOriginContext originContext = new RecordOriginContext(
                RecordImpactType.GENERATE_PUSH,
                "sales.opportunity",
                "opp-1",
                MODULE,
                "rule-1",
                "generateContract",
                "batch-1",
                "contract:1"
        );
        when(referenceGenerationFacade.confirmDraft(any(RecordGenerationDraft.class))).thenReturn("contract-1");

        mvc.perform(post("/{moduleAlias}/generation/confirm", MODULE)
                        .contentType("application/json")
                        .content(json(Map.of(
                                "targetModuleAlias", MODULE,
                                "targetEntityAlias", ENTITY,
                                "record", Map.of("values", Map.of("code", "C-001")),
                                "originContext", Map.of(
                                        "impactType", "GENERATE_PUSH",
                                        "sourceModuleAlias", "sales.opportunity",
                                        "sourceRecordId", "opp-1",
                                        "targetModuleAlias", MODULE,
                                        "generationRuleId", "rule-1",
                                        "actionCode", "generateContract",
                                        "batchId", "batch-1",
                                        "draftKey", "contract:1"
                                )
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ruleId").value("rule-1"))
                .andExpect(jsonPath("$.batchId").value("batch-1"))
                .andExpect(jsonPath("$.recordIds[0]").value("contract-1"));

        ArgumentCaptor<RecordGenerationDraft> draft = ArgumentCaptor.forClass(RecordGenerationDraft.class);
        verify(referenceGenerationFacade).confirmDraft(draft.capture());
        assertThat(draft.getValue().targetModuleAlias()).isEqualTo(MODULE);
        assertThat(draft.getValue().targetEntityAlias()).isEqualTo(ENTITY);
        assertThat(draft.getValue().record().getValue("code")).isEqualTo("C-001");
        assertThat(draft.getValue().originContext()).isEqualTo(originContext);
    }

    @Test
    void shouldRejectGeneratedDraftConfirmForDifferentPathModule() throws Exception {
        mvc.perform(post("/{moduleAlias}/generation/confirm", MODULE)
                        .contentType("application/json")
                        .content(json(Map.of(
                                "targetModuleAlias", "finance.invoice",
                                "targetEntityAlias", "invoice",
                                "record", Map.of("values", Map.of())
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "dynamic generation confirm targetModuleAlias mismatch: finance.invoice != " + MODULE));

        verifyNoInteractions(referenceGenerationFacade);
    }

    @Test
    void shouldRejectGeneratedDraftConfirmWhenChildRelationIsNotArray() throws Exception {
        Map<String, Object> children = new java.util.LinkedHashMap<>();
        children.put("lines", null);

        mvc.perform(post("/{moduleAlias}/generation/confirm", MODULE)
                        .contentType("application/json")
                        .content(json(Map.of(
                                "targetModuleAlias", MODULE,
                                "targetEntityAlias", ENTITY,
                                "record", Map.of(
                                        "values", Map.of("code", "C-001"),
                                        "children", children
                                )
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("dynamic child relation must be array: lines"));

        verifyNoInteractions(referenceGenerationFacade);
    }

    @Test
    void shouldCompleteReferenceGenerationAndConfirmThroughDynamicWebEntries() throws Exception {
        RecordOriginContext originContext = new RecordOriginContext(
                RecordImpactType.GENERATE_PUSH,
                "sales.opportunity",
                "opp-1",
                MODULE,
                "rule-1",
                "generateContract",
                "batch-1",
                "contract:1"
        );
        DynamicRecord draft = new DynamicRecord(entity())
                .setValue("code", "C-001")
                .setValue("amount", new BigDecimal("100.00"));
        draft.setChildren("lines", List.of(new DynamicRecord(lineEntity())
                .setValue("lineNo", "L-001")
                .setValue("lineAmount", new BigDecimal("100.00"))));
        when(service.relations(MODULE)).thenReturn(List.of(
                new DynamicRelationDescriptor("lines", ENTITY, "contract_line", "contractId", false, false)
        ));
        when(service.newRecord(MODULE, "contract_line")).thenAnswer(invocation -> new DynamicRecord(lineEntity()));
        when(service.resolveFieldReference(anyString(), anyString(), anyString(), any()))
                .thenReturn(new DynamicReferenceResolveResponse(
                        DynamicReferenceResolveStatus.OK,
                        DynamicReferenceResolveMode.QUERY,
                        List.of(new DynamicReferenceResolveItem(
                                "opp-1",
                                "OPP-001",
                                DynamicReferenceMatchMode.AUTO,
                                Map.of("opportunityNo", "OPP-001"),
                                Map.of("code", "C-001")
                        )),
                        List.of(),
                        0,
                        20,
                        1
                ));
        when(referenceGenerationFacade.generateFromReference(MODULE, ENTITY, "opportunityId", "opp-1"))
                .thenReturn(new RecordGenerationResult(
                        "rule-1",
                        "generateContract",
                        "sales.opportunity",
                        "opp-1",
                        MODULE,
                        "batch-1",
                        List.of(new RecordGenerationDraft(MODULE, ENTITY, draft, originContext))
                ));
        when(referenceGenerationFacade.confirmDraft(any(RecordGenerationDraft.class))).thenReturn("contract-1");

        mvc.perform(post("/{moduleAlias}/references/{fieldName}/resolve", MODULE, "opportunityId")
                        .contentType("application/json")
                        .content(json(Map.of(
                                "fuzzy", "OPP",
                                "formValues", Map.of("region", "north")
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.options[0].id").value("opp-1"))
                .andExpect(jsonPath("$.options[0].affectPatch.code").value("C-001"));
        MvcResult generationResult = mvc.perform(post("/{moduleAlias}/references/{fieldName}/generate", MODULE, "opportunityId")
                        .contentType("application/json")
                        .content(json(Map.of("sourceRecordId", "opp-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.drafts[0].record.values.code").value("C-001"))
                .andExpect(jsonPath("$.drafts[0].originContext.batchId").value("batch-1"))
                .andReturn();
        @SuppressWarnings("unchecked")
        Map<String, Object> generationBody = objectMapper.readValue(
                generationResult.getResponse().getContentAsString(),
                Map.class
        );
        @SuppressWarnings("unchecked")
        Map<String, Object> draftBody = (Map<String, Object>) ((List<?>) generationBody.get("drafts")).getFirst();

        mvc.perform(post("/{moduleAlias}/generation/confirm", MODULE)
                        .contentType("application/json")
                        .content(json(Map.of(
                                "targetModuleAlias", draftBody.get("targetModuleAlias"),
                                "targetEntityAlias", draftBody.get("targetEntityAlias"),
                                "record", draftBody.get("record"),
                                "originContext", draftBody.get("originContext")
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recordIds[0]").value("contract-1"));

        ArgumentCaptor<DynamicReferenceResolveRequest> resolveRequest =
                ArgumentCaptor.forClass(DynamicReferenceResolveRequest.class);
        verify(service).resolveFieldReference(eq(MODULE), eq(ENTITY), eq("opportunityId"), resolveRequest.capture());
        assertThat(resolveRequest.getValue().formValues()).containsEntry("region", "north");
        verify(referenceGenerationFacade).generateFromReference(MODULE, ENTITY, "opportunityId", "opp-1");
        ArgumentCaptor<RecordGenerationDraft> confirmedDraft = ArgumentCaptor.forClass(RecordGenerationDraft.class);
        verify(referenceGenerationFacade).confirmDraft(confirmedDraft.capture());
        assertThat(confirmedDraft.getValue().record().getValue("code")).isEqualTo("C-001");
        assertThat(confirmedDraft.getValue().record().getChildren("lines")).singleElement()
                .satisfies(line -> assertThat(line.getValue("lineNo")).isEqualTo("L-001"));
        assertThat(confirmedDraft.getValue().originContext().batchId()).isEqualTo("batch-1");
    }

    @Test
    void shouldNotExposeEntityLevelWebApi() throws Exception {
        mvc.perform(post("/{moduleAlias}/entities/{entityAlias}/records", MODULE, ENTITY)
                        .contentType("application/json")
                        .content(json(Map.of("values", Map.of("code", "C-001")))))
                .andExpect(status().isNotFound());
        verifyNoInteractions(service);
    }

    @Test
    void shouldReturnStableBadRequestBody() throws Exception {
        when(service.describe(MODULE)).thenThrow(new ModuleDefinitionException("unknown module alias: " + MODULE));

        mvc.perform(get("/{moduleAlias}/describe", MODULE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(PlatformErrorCodes.VALIDATION_FAILED))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.traceId").isNotEmpty())
                .andExpect(jsonPath("$.message").value("unknown module alias: " + MODULE));
    }

    @Test
    void shouldReturnStableBadRequestWhenTenantContextIsMissing() throws Exception {
        MockMvc noTenantMvc = MockMvcBuilders
                .standaloneSetup(controller(service, activeTenantVerifier))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new PlatformWebExceptionHandler(), new DynamicWebExceptionHandler())
                .addFilters(new CurrentUserWebFilter(java.util.Optional::empty))
                .build();

        noTenantMvc.perform(get("/{moduleAlias}/describe", MODULE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(PlatformErrorCodes.VALIDATION_FAILED))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(MODULE + " requires tenant context"));

        noTenantMvc.perform(get("/{moduleAlias}/openapi", MODULE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(PlatformErrorCodes.VALIDATION_FAILED))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value(MODULE + " requires tenant context"));

        verifyNoInteractions(activeTenantVerifier);
    }

    @Test
    void shouldReturnConflictForOptimisticLockFailure() throws Exception {
        when(mainEntity.update(any(DynamicRecord.class)))
                .thenThrow(new OptimisticLockException("record version conflict: contract-1"));

        mvc.perform(post("/{moduleAlias}/update/{recordId}", MODULE, "contract-1")
                        .contentType("application/json")
                        .content(json(Map.of("values", Map.of("code", "C-001")))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(PlatformErrorCodes.CONFLICT_VERSION))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("数据已被更新，请刷新后重试"));
    }

    @Test
    void shouldReturnStableBadRequestWhenDynamicRecordPayloadCannotBeDecoded() throws Exception {
        mvc.perform(post("/{moduleAlias}/insert", MODULE)
                        .contentType("application/json")
                        .content(json(Map.of("values", Map.of("unknown", "C-001")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(PlatformErrorCodes.VALIDATION_FAILED))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("unknown dynamic field: unknown"));
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private ModuleDefinition module() {
        return new ModuleDefinition(MODULE, "Contract", List.of(entity()));
    }

    private ModuleDefinition actionModule() {
        return ModuleDefinition.builder(MODULE, "Contract")
                .entities(List.of(entity()))
                .relations(List.of())
                .references(List.of())
                .views(List.of())
                .actions(List.of(new EntityActionDefinition(ENTITY, "submit", "Submit", true, EntityActionLevel.RECORD,
                        EntityActionCategory.CUSTOM, EntityActionAccessMode.AUTH_REQUIRED,
                        true, false, null, null, null,
                        EntityActionExecutorType.SERVICE, "submitExecutor")))
                .build();
    }

    private DynamicActionDescriptor action(String code, EntityActionLevel level) {
        return action(code, level, null);
    }

    private DynamicActionDescriptor action(String code, EntityActionLevel level, String authInheritActionCode) {
        return new DynamicActionDescriptor(code, "Submit", true, level, EntityActionCategory.CUSTOM,
                EntityActionAccessMode.AUTH_REQUIRED, true, false, authInheritActionCode, false, null,
                EntityActionExecutorType.SERVICE, "submitExecutor").withPermission(MODULE);
    }

    private DynamicActionDescriptor standardBatchDeleteAction() {
        return new DynamicActionDescriptor(PlatformAction.BATCH_DELETE.code(), "Batch Delete", true,
                EntityActionLevel.BATCH, EntityActionCategory.STANDARD,
                EntityActionAccessMode.AUTH_REQUIRED, true, true,
                PlatformAction.BATCH_DELETE.inheritActionCode(), false, null,
                EntityActionExecutorType.STANDARD, PlatformAction.BATCH_DELETE.code()).withPermission(MODULE);
    }

    private DynamicActionDescriptor workflowAction(String code) {
        return new DynamicActionDescriptor(code, "同步流程", true, EntityActionLevel.RECORD,
                EntityActionCategory.WORKFLOW, EntityActionAccessMode.AUTH_REQUIRED,
                true, false, null, false, null,
                EntityActionExecutorType.SERVICE, "platform.workflow").withPermission(MODULE);
    }

    private DynamicActionDescriptor dialogAction(String code, EntityActionLevel level) {
        return new DynamicActionDescriptor(code, "提交合同", true, level, EntityActionCategory.DIALOG,
                EntityActionAccessMode.AUTH_REQUIRED, true, false, null, false, null,
                EntityActionExecutorType.DIALOG, "contractSubmitDialog");
    }

    private EntityDefinition entity() {
        return new EntityDefinition(ENTITY, "sales_contract", "Contract", List.of(
                FieldDefinition.string("code", "Code").length(64).required(),
                FieldDefinition.decimal("amount", "Amount").precision(18, 2),
                FieldDefinition.string("displayCode", "Display Code").column("display_code").virtual(),
                FieldDefinition.of("signedDate", FieldType.DATE, "Signed Date").column("signed_date"),
                FieldDefinition.timestamp("signedAt", "Signed At").column("signed_at")
        ));
    }

    private EntityDefinition associationLineEntity() {
        return new EntityDefinition("line", "sales_contract_line", "Contract Line", List.of(
                FieldDefinition.string("contractId", "Contract").column("contract_id").length(32),
                FieldDefinition.string("summary", "Summary").length(128)
        ));
    }

    private ResolvedModuleMetadataField resolvedModuleField(String moduleFieldId, String fieldName) {
        return resolvedModuleField(moduleFieldId, fieldName, RelationRole.MAIN);
    }

    private ResolvedModuleMetadataField resolvedModuleField(String moduleFieldId,
                                                           String fieldName,
                                                           RelationRole relationRole) {
        return resolvedModuleField(moduleFieldId, fieldName, relationRole, "main", "string");
    }

    private ResolvedModuleMetadataField resolvedModuleField(String moduleFieldId,
                                                           String fieldName,
                                                           RelationRole relationRole,
                                                           String fieldSpecAlias) {
        return resolvedModuleField(moduleFieldId, fieldName, relationRole, "main", fieldSpecAlias);
    }

    private ResolvedModuleMetadataField resolvedModuleField(String moduleFieldId,
                                                           String fieldName,
                                                           RelationRole relationRole,
                                                           String relationAlias,
                                                           String fieldSpecAlias) {
        return resolvedModuleField(moduleFieldId, fieldName, relationRole, relationAlias, fieldSpecAlias,
                MetadataFieldForm.PHYSICAL);
    }

    private ResolvedModuleMetadataField resolvedModuleField(String moduleFieldId,
                                                           String fieldName,
                                                           RelationRole relationRole,
                                                           String relationAlias,
                                                           String fieldSpecAlias,
                                                           MetadataFieldForm fieldForm) {
        return new ResolvedModuleMetadataField(
                moduleFieldId,
                MODULE,
                "rel-main",
                relationAlias,
                relationRole,
                "metadata-1",
                relationRole == RelationRole.MAIN ? ENTITY : "contract_line",
                "Contract",
                "metadata-field-" + fieldName,
                fieldName,
                fieldName,
                fieldName,
                fieldSpecAlias,
                fieldForm
        );
    }

    private void publishedListUiConfig(PlatformPageConfigSnapshotService snapshotService,
                                       PlatformUiConfigField... fields) {
        PlatformUiSet uiSet = new PlatformUiSet();
        uiSet.setId("set-list");
        uiSet.setModuleAlias(MODULE);
        uiSet.setAlias("list");
        uiSet.setSetType(PlatformUiSetType.LIST);
        PlatformUiConfig uiConfig = new PlatformUiConfig();
        uiConfig.setId("ui-list");
        uiConfig.setUiSetId("set-list");
        uiConfig.setClientType(PlatformUiClientType.WEB);
        uiConfig.setPublished(true);
        when(snapshotService.snapshot(MODULE)).thenReturn(new PlatformPageConfigSnapshot(
                MODULE,
                List.of(uiSet),
                List.of(uiConfig),
                List.of(fields),
                List.of(),
                List.of()
        ));
    }

    private DynamicRecordWebController controller(DynamicRecordService recordService,
                                                  ActiveTenantVerifier activeTenantVerifier) {
        return controllerFixture(recordService, activeTenantVerifier).build();
    }

    private ModuleExecutionPlan installedDynamicPlan() {
        ModuleUiDefinition definition = ModuleUiDefinition.builder(MODULE)
                .page(PageTemplates.listDetailCard(page -> page
                        .list(list -> list.fields(fields -> fields.field("code")))
                        .detail(detail -> detail.editor(editor -> editor.field("code", field -> field.required())))))
                .build();
        var descriptor = ModuleUiDescriptorCompiler.compile(definition, ModuleKind.DYNAMIC, "Contract");
        var schema = new net.ximatai.muyun.spring.ability.query.QuerySchema(MODULE, ENTITY,
                new net.ximatai.muyun.spring.ability.query.QuerySchema.QuickSearch(true, List.of("code"), List.of()),
                List.of(new net.ximatai.muyun.spring.ability.query.QuerySchema.Field("code", "Code",
                        net.ximatai.muyun.spring.ability.query.QueryValueType.STRING,
                        List.of(net.ximatai.muyun.spring.ability.query.QueryOperator.EQ),
                        net.ximatai.muyun.spring.ability.query.QueryOperator.EQ, true, true, null, null, null)),
                List.of(), List.of());
        return new ModuleExecutionPlan(MODULE, "dynamic-runtime-1-ui-1", descriptor,
                new ResolvedModuleReadModel(MODULE, ENTITY,
                        List.of(new ResolvedModuleReadField(ENTITY, null, "code", false))), List.of(),
                net.ximatai.muyun.spring.ability.query.QueryDescriptor.builder(MODULE).build(), schema,
                List.of("tpl-active"), List.of(new ModuleQueryTemplatePlan("tpl-active", List.of(
                        new ModuleQueryTemplatePlan.Node(net.ximatai.muyun.spring.platform.ui.PlatformQueryGroupOperator.AND,
                                "code", DynamicQueryOperator.EQ, null, "code", null, List.of())))), "ui-list", "form-v1",
                List.of(new ModuleQueryFormField("code", ModuleQueryFormField.Mode.DEFAULT, List.of())), List.of(),
                List.of(new ModuleMutationFieldValidation(null, "code", false, true)), List.of(), false);
    }

    private DynamicRecordWebControllerFixture controllerFixture(
            DynamicRecordService recordService,
            ActiveTenantVerifier activeTenantVerifier) {
        return new DynamicRecordWebControllerFixture(recordService, activeTenantVerifier);
    }

    private static final class DynamicRecordWebControllerFixture {
        private final DynamicRecordService recordService;
        private final ActiveTenantVerifier activeTenantVerifier;
        private CodeBusinessPreviewService codeBusinessPreviewService;
        private ReferenceRecordGenerationFacade referenceRecordGenerationFacade;
        private PlatformPageConfigSnapshotService pageConfigSnapshotService;
        private PlatformQueryItemService queryItemService;
        private ModuleMetadataFieldService moduleMetadataFieldService;
        private FieldUiControlService fieldUiControlService;
        private FieldUiControlBindingService fieldUiControlBindingService;
        private RecordAttachmentService recordAttachmentService;
        private RecordAttachmentAccessService recordAttachmentAccessService;
        private RecordDuplicateCheckService duplicateCheckService;
        private PlatformRecordNavigationService navigationService;
        private DynamicRelationProjectionReadService relationProjectionReadService =
                DynamicRelationProjectionReadServiceTestFactory.withDefaults();
        private ModuleExecutionPlanCatalog executionPlanCatalog;

        private DynamicRecordWebControllerFixture(
                DynamicRecordService recordService,
                ActiveTenantVerifier activeTenantVerifier) {
            this.recordService = recordService;
            this.activeTenantVerifier = activeTenantVerifier;
        }

        DynamicRecordWebControllerFixture codePreview(CodeBusinessPreviewService value) {
            codeBusinessPreviewService = value;
            return this;
        }

        DynamicRecordWebControllerFixture generation(ReferenceRecordGenerationFacade value) {
            referenceRecordGenerationFacade = value;
            return this;
        }

        DynamicRecordWebControllerFixture query(
                PlatformPageConfigSnapshotService pageConfig,
                PlatformQueryItemService queryItems,
                ModuleMetadataFieldService metadataFields) {
            return query(pageConfig, queryItems, metadataFields, null, null);
        }

        DynamicRecordWebControllerFixture query(
                PlatformPageConfigSnapshotService pageConfig,
                PlatformQueryItemService queryItems,
                ModuleMetadataFieldService metadataFields,
                FieldUiControlService fieldUiControls,
                FieldUiControlBindingService bindings) {
            pageConfigSnapshotService = pageConfig;
            queryItemService = queryItems;
            moduleMetadataFieldService = metadataFields;
            fieldUiControlService = fieldUiControls;
            fieldUiControlBindingService = bindings;
            return this;
        }

        DynamicRecordWebControllerFixture attachments(RecordAttachmentService value) {
            recordAttachmentService = value;
            return this;
        }

        DynamicRecordWebControllerFixture attachmentAccess(RecordAttachmentAccessService value) {
            recordAttachmentAccessService = value;
            return this;
        }

        DynamicRecordWebControllerFixture duplicateCheck(RecordDuplicateCheckService value) {
            duplicateCheckService = value;
            return this;
        }

        DynamicRecordWebControllerFixture navigation(PlatformRecordNavigationService value) {
            navigationService = value;
            return this;
        }

        DynamicRecordWebControllerFixture projection(DynamicRelationProjectionReadService value) {
            relationProjectionReadService = value;
            return this;
        }

        DynamicRecordWebControllerFixture executionPlans(ModuleExecutionPlanCatalog value) {
            executionPlanCatalog = value;
            return this;
        }

        DynamicRecordWebController build() {
            return new DynamicRecordWebController(
                    recordService,
                    new TenantRequestScope(activeTenantVerifier),
                    new DynamicRecordQueryServices(pageConfigSnapshotService, queryItemService,
                            moduleMetadataFieldService, fieldUiControlService, fieldUiControlBindingService,
                            relationProjectionReadService, executionPlanCatalog),
                    new DynamicRecordAttachmentServices(recordAttachmentService, recordAttachmentAccessService),
                    new DynamicRecordActionServices(codeBusinessPreviewService, referenceRecordGenerationFacade,
                            duplicateCheckService, navigationService));
        }
    }

    private MockMvc projectionMvc(PlatformPageConfigSnapshotService snapshotService,
                                  ModuleMetadataFieldService moduleFieldService,
                                  DynamicRelationProjectionReadService projectionReadService) {
        return MockMvcBuilders
                .standaloneSetup(controllerFixture(service, activeTenantVerifier)
                        .codePreview(codeBusinessPreviewService)
                        .generation(referenceGenerationFacade)
                        .query(snapshotService, null, moduleFieldService)
                        .projection(projectionReadService)
                        .build())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
                .setControllerAdvice(new PlatformWebExceptionHandler(), new DynamicWebExceptionHandler())
                .addFilters(new CurrentUserWebFilter(() -> java.util.Optional.of(
                        CurrentUser.tenantUser("user-1", "User", "tenant_a"))))
                .build();
    }

    private PlatformUiConfigField uiField(String uiConfigId, String moduleFieldId) {
        PlatformUiConfigField field = new PlatformUiConfigField();
        field.setUiConfigId(uiConfigId);
        field.setModuleMetadataFieldId(moduleFieldId);
        field.setVisible(true);
        return field;
    }

    private DynamicReferenceDescriptor reference(String sourceField, String queryTemplateId) {
        return new DynamicReferenceDescriptor(
                ENTITY,
                sourceField,
                "crm.customer",
                "customer",
                ReferenceCardinality.ONE,
                List.of(),
                "id",
                "title",
                null,
                queryTemplateId,
                Set.of(),
                List.of(),
                List.of()
        );
    }

    private RecordAttachment attachment(String id, String fileId, String displayName) {
        RecordAttachment attachment = new RecordAttachment();
        attachment.setId(id);
        attachment.setModuleAlias(MODULE);
        attachment.setRecordId("contract-1");
        attachment.setFileId(fileId);
        attachment.setDisplayName(displayName);
        return attachment;
    }

    private Object criteriaNode(CriteriaGroup.Entry entry) {
        try {
            Method method = entry.getClass().getMethod("getNode");
            return method.invoke(entry);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read criteria node", e);
        }
    }

    private String criteriaJoin(CriteriaGroup.Entry entry) {
        try {
            Method method = entry.getClass().getMethod("getJoin");
            return String.valueOf(method.invoke(entry));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read criteria join", e);
        }
    }

    private EntityDefinition lineEntity() {
        return new EntityDefinition("contract_line", "sales_contract_line", "Contract Line", List.of(
                FieldDefinition.string("lineNo", "Line No").column("line_no").length(64).required(),
                FieldDefinition.decimal("lineAmount", "Line Amount").column("line_amount").precision(18, 2),
                FieldDefinition.string("lineDisplay", "Line Display").column("line_display").virtual()
        ));
    }

    private EntityDefinition protectedEntity() {
        return new EntityDefinition(ENTITY, "sales_contract", "Contract", List.of(
                FieldDefinition.string("code", "Code").length(64).required(),
                FieldDefinition.string("secret", "Secret")
                        .protection(new FieldProtectionDefinition(
                                FieldEncryptionMode.NONE,
                                FieldSignatureMode.NONE,
                                FieldMaskingPolicy.MIDDLE
                        ))
        ));
    }

    private EntityDefinition treeEntity() {
        return new EntityDefinition(ENTITY, "sales_contract", "Contract", List.of(
                FieldDefinition.string("code", "Code").length(64).required(),
                FieldDefinition.parentId(),
                FieldDefinition.sortOrder()
        )).withCapabilities(EntityCapability.TREE);
    }

    private EntityDefinition sortableEntity() {
        return new EntityDefinition(ENTITY, "sales_contract", "Contract", List.of(
                FieldDefinition.string("code", "Code").length(64).required(),
                FieldDefinition.sortOrder()
        )).withCapabilities(EntityCapability.SORT);
    }

    @RestController
    @RequestMapping("/sales.contract")
    static class StaticContractController {
        @PostMapping("/query")
        Map<String, String> query() {
            return Map.of("source", "static");
        }
    }
}
