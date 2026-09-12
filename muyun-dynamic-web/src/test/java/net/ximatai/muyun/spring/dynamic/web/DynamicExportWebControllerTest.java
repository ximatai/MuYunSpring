package net.ximatai.muyun.spring.dynamic.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.CriteriaClause;
import net.ximatai.muyun.database.core.orm.CriteriaGroup;
import net.ximatai.muyun.database.core.orm.CriteriaOperator;
import net.ximatai.muyun.spring.web.WebQueryCondition;
import net.ximatai.muyun.spring.web.WebQueryRequest;
import net.ximatai.muyun.spring.web.WebSort;
import net.ximatai.muyun.spring.web.CurrentUserWebFilter;
import net.ximatai.muyun.spring.web.PlatformWebExceptionHandler;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicEntityOperations;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.exchange.exporter.DynamicExportCommand;
import net.ximatai.muyun.spring.platform.exchange.exporter.DynamicExportFacade;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataFieldService;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldForm;
import net.ximatai.muyun.spring.platform.metadata.RelationRole;
import net.ximatai.muyun.spring.platform.metadata.ResolvedModuleMetadataField;
import net.ximatai.muyun.spring.platform.ui.PlatformPageConfigSnapshot;
import net.ximatai.muyun.spring.platform.ui.PlatformPageConfigSnapshotService;
import net.ximatai.muyun.spring.platform.ui.PlatformQueryItemService;
import net.ximatai.muyun.spring.platform.ui.PlatformQueryTemplate;
import net.ximatai.muyun.spring.platform.ui.PlatformUiClientType;
import net.ximatai.muyun.spring.platform.ui.PlatformUiConfig;
import net.ximatai.muyun.spring.platform.ui.PlatformUiConfigField;
import net.ximatai.muyun.spring.platform.ui.PlatformUiSet;
import net.ximatai.muyun.spring.platform.ui.PlatformUiSetType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DynamicExportWebControllerTest {
    private static final String MODULE = "sales.order";
    private final ObjectMapper objectMapper = new ObjectMapper();

    private DynamicRecordService recordService;
    private ActiveTenantVerifier activeTenantVerifier;
    private DynamicExportFacade exportFacade;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        recordService = mock(DynamicRecordService.class);
        activeTenantVerifier = mock(ActiveTenantVerifier.class);
        exportFacade = mock(DynamicExportFacade.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new DynamicExportWebController(
                        recordService, activeTenantVerifier, exportFacade))
                .setControllerAdvice(new PlatformWebExceptionHandler())
                .addFilters(new CurrentUserWebFilter(() -> java.util.Optional.of(
                        CurrentUser.tenantUser("user-1", "User", "tenant_a"))))
                .build();
    }

    @Test
    void shouldExportDataWorkbookThroughFacade() throws Exception {
        DynamicModuleDescriptor descriptor = descriptor();
        DynamicEntityOperations operations = mock(DynamicEntityOperations.class);
        Criteria criteria = Criteria.of().eq("status", "active");
        when(recordService.describe(MODULE)).thenReturn(descriptor);
        when(recordService.mainEntity(MODULE)).thenReturn(operations);
        when(operations.newRecord()).thenReturn(new net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord(entity()));
        when(operations.queryCriteria(org.mockito.ArgumentMatchers.anyList())).thenReturn(criteria);
        when(exportFacade.exportWorkbook(org.mockito.ArgumentMatchers.any(DynamicExportCommand.class)))
                .thenReturn(new byte[]{7, 8, 9});

        WebQueryRequest request = new WebQueryRequest(
                new net.ximatai.muyun.spring.web.WebPageRequest(2, 50),
                List.of(new WebQueryCondition("status", "EQ", List.of("active"))),
                List.of(new WebSort("orderNo", true))
        );
        mvc.perform(post("/{moduleAlias}/export/data", MODULE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Export-FileName", "sales_order-export.xlsx"))
                .andExpect(content().bytes(new byte[]{7, 8, 9}));

        ArgumentCaptor<DynamicExportCommand> captor = ArgumentCaptor.forClass(DynamicExportCommand.class);
        verify(exportFacade).exportWorkbook(captor.capture());
        assertThat(captor.getValue().descriptor()).isSameAs(descriptor);
        assertThat(captor.getValue().criteria()).isSameAs(criteria);
        assertThat(captor.getValue().pageRequest().getOffset()).isEqualTo(50);
        assertThat(captor.getValue().pageRequest().getLimit()).isEqualTo(50);
        assertThat(captor.getValue().sorts()).hasSize(1);
    }

    @Test
    void shouldReuseLowCodeQueryTemplateWhenExportingData() throws Exception {
        PlatformPageConfigSnapshotService snapshotService = mock(PlatformPageConfigSnapshotService.class);
        PlatformQueryItemService queryItemService = mock(PlatformQueryItemService.class);
        MockMvc exportMvc = MockMvcBuilders
                .standaloneSetup(new DynamicExportWebController(
                        recordService, activeTenantVerifier, exportFacade, snapshotService, queryItemService))
                .setControllerAdvice(new PlatformWebExceptionHandler())
                .addFilters(new CurrentUserWebFilter(() -> java.util.Optional.of(
                        CurrentUser.tenantUser("user-1", "User", "tenant_a"))))
                .build();
        DynamicModuleDescriptor descriptor = descriptor();
        DynamicEntityOperations operations = mock(DynamicEntityOperations.class);
        PlatformQueryTemplate template = new PlatformQueryTemplate();
        template.setId("tpl-active");
        template.setModuleAlias(MODULE);
        template.setAlias("active");
        Criteria templateCriteria = Criteria.of().eq("status", "active");
        Criteria manualCriteria = Criteria.of().eq("ownerId", "user-1");
        when(recordService.describe(MODULE)).thenReturn(descriptor);
        when(recordService.mainEntity(MODULE)).thenReturn(operations);
        when(snapshotService.snapshot(MODULE)).thenReturn(new PlatformPageConfigSnapshot(
                MODULE, List.of(), List.of(), List.of(), List.of(template), List.of()));
        when(queryItemService.compile(eq("tpl-active"), org.mockito.ArgumentMatchers.anyMap()))
                .thenReturn(templateCriteria);
        when(operations.queryCriteria(org.mockito.ArgumentMatchers.anyList())).thenReturn(manualCriteria);
        when(exportFacade.exportWorkbook(org.mockito.ArgumentMatchers.any(DynamicExportCommand.class)))
                .thenReturn(new byte[]{1, 2, 3});

        exportMvc.perform(post("/{moduleAlias}/export/data", MODULE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "queryTemplateId", "tpl-active",
                                "externalQueryValues", Map.of("owner", "user-1"),
                                "conditions", List.of(Map.of(
                                        "fieldName", "ownerId",
                                        "operator", "EQ",
                                        "values", List.of("user-1")
                                ))
                        ))))
                .andExpect(status().isOk());

        ArgumentCaptor<DynamicExportCommand> captor = ArgumentCaptor.forClass(DynamicExportCommand.class);
        verify(exportFacade).exportWorkbook(captor.capture());
        assertThat(captor.getValue().criteria()).isNotSameAs(templateCriteria);
        assertThat(captor.getValue().criteria()).isNotSameAs(manualCriteria);
        assertThat(captor.getValue().criteria().isEmpty()).isFalse();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> externalValues = ArgumentCaptor.forClass(Map.class);
        verify(queryItemService).compile(eq("tpl-active"), externalValues.capture());
        assertThat(externalValues.getValue()).containsEntry("owner", "user-1");
    }

    @Test
    void shouldExportSelectedDataWorkbookThroughFacade() throws Exception {
        DynamicModuleDescriptor descriptor = descriptor();
        DynamicEntityOperations operations = mock(DynamicEntityOperations.class);
        Criteria statusCriteria = Criteria.of().eq("status", "active");
        when(recordService.describe(MODULE)).thenReturn(descriptor);
        when(recordService.mainEntity(MODULE)).thenReturn(operations);
        when(operations.queryCriteria(org.mockito.ArgumentMatchers.anyList())).thenReturn(statusCriteria);
        when(exportFacade.exportWorkbook(org.mockito.ArgumentMatchers.any(DynamicExportCommand.class)))
                .thenReturn(new byte[]{1, 3, 5});

        mvc.perform(post("/{moduleAlias}/export/selected", MODULE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ids": ["order-1", "order-2", "order-1"],
                                  "query": {
                                    "conditions": [
                                      {"fieldName": "status", "operator": "EQ", "values": ["active"]}
                                    ]
                                  }
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Export-FileName", "sales_order-selected-export.xlsx"))
                .andExpect(content().bytes(new byte[]{1, 3, 5}));

        ArgumentCaptor<DynamicExportCommand> captor = ArgumentCaptor.forClass(DynamicExportCommand.class);
        verify(exportFacade).exportWorkbook(captor.capture());
        List<CriteriaClause> clauses = clauses(captor.getValue().criteria());
        assertThat(clauses).anySatisfy(clause -> {
            assertThat(clause.getField()).isEqualTo("id");
            assertThat(clause.getOperator()).isEqualTo(CriteriaOperator.IN);
            assertThat(clause.getValues()).containsExactly("order-1", "order-2");
        });
        assertThat(clauses).anySatisfy(clause -> {
            assertThat(clause.getField()).isEqualTo("status");
            assertThat(clause.getValues()).contains("active");
        });
    }

    @Test
    void shouldExportDataWithNestedCriteriaAndQuickSearch() throws Exception {
        PlatformPageConfigSnapshotService snapshotService = mock(PlatformPageConfigSnapshotService.class);
        PlatformQueryItemService queryItemService = mock(PlatformQueryItemService.class);
        ModuleMetadataFieldService moduleFieldService = mock(ModuleMetadataFieldService.class);
        MockMvc exportMvc = MockMvcBuilders
                .standaloneSetup(new DynamicExportWebController(
                        recordService, activeTenantVerifier, exportFacade,
                        snapshotService, queryItemService, moduleFieldService))
                .setControllerAdvice(new PlatformWebExceptionHandler())
                .addFilters(new CurrentUserWebFilter(() -> java.util.Optional.of(
                        CurrentUser.tenantUser("user-1", "User", "tenant_a"))))
                .build();
        DynamicModuleDescriptor descriptor = descriptor();
        DynamicEntityOperations operations = mock(DynamicEntityOperations.class);
        PlatformUiSet uiSet = uiSet("set-list");
        PlatformUiConfig uiConfig = uiConfig("ui-list", uiSet.getId());
        PlatformUiConfigField codeField = uiField(uiConfig.getId(), "field-code");
        when(recordService.describe(MODULE)).thenReturn(descriptor);
        when(recordService.mainEntity(MODULE)).thenReturn(operations);
        when(snapshotService.snapshot(MODULE)).thenReturn(new PlatformPageConfigSnapshot(
                MODULE, List.of(uiSet), List.of(uiConfig), List.of(codeField), List.of(), List.of()));
        when(moduleFieldService.resolve("field-code")).thenReturn(resolvedField("field-code", "code", "string"));
        when(operations.queryCriteria(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<net.ximatai.muyun.spring.dynamic.runtime.DynamicQueryCondition> conditions =
                    invocation.getArgument(0, List.class);
            var condition = conditions.getFirst();
            return Criteria.of().eq(condition.fieldName(), condition.values().getFirst());
        });
        when(exportFacade.exportWorkbook(any(DynamicExportCommand.class))).thenReturn(new byte[]{4, 5, 6});

        exportMvc.perform(post("/{moduleAlias}/export/data", MODULE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "uiConfigId": "ui-list",
                                  "queryForm": {
                                    "code": "C-001"
                                  },
                                  "quickSearch": "C-001",
                                  "quickSearchFields": ["code"],
                                  "criteria": {
                                    "kind": "GROUP",
                                    "operator": "OR",
                                    "children": [
                                      {"kind": "CONDITION", "fieldName": "status", "operator": "EQ", "values": ["ACTIVE"]},
                                      {"kind": "CONDITION", "fieldName": "status", "operator": "EQ", "values": ["PENDING"]}
                                    ]
                                  }
                                }
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<DynamicExportCommand> captor = ArgumentCaptor.forClass(DynamicExportCommand.class);
        verify(exportFacade).exportWorkbook(captor.capture());
        List<CriteriaClause> clauses = clauses(captor.getValue().criteria());
        assertThat(clauses).anySatisfy(clause -> {
            assertThat(clause.getField()).isEqualTo("status");
            assertThat(clause.getOperator()).isEqualTo(CriteriaOperator.EQ);
            assertThat(clause.getValues()).contains("ACTIVE");
        });
        assertThat(clauses).anySatisfy(clause -> {
            assertThat(clause.getField()).isEqualTo("code");
            assertThat(clause.getOperator()).isEqualTo(CriteriaOperator.LIKE);
            assertThat(clause.getValues()).contains("C-001");
        });
        verify(operations, org.mockito.Mockito.atLeastOnce()).queryCriteria(any());
    }

    @Test
    void shouldRejectVirtualFieldInExportSorts() throws Exception {
        DynamicModuleDescriptor descriptor = descriptor();
        DynamicEntityOperations operations = mock(DynamicEntityOperations.class);
        when(recordService.describe(MODULE)).thenReturn(descriptor);
        when(recordService.mainEntity(MODULE)).thenReturn(operations);
        when(operations.newRecord()).thenReturn(new net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord(entity()));

        mvc.perform(post("/{moduleAlias}/export/data", MODULE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sorts": [
                                    {"field": "displayCode", "desc": true}
                                  ]
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(exportFacade, org.mockito.Mockito.never()).exportWorkbook(any(DynamicExportCommand.class));
    }

    @Test
    void shouldRejectVirtualFieldInSelectedExportSorts() throws Exception {
        DynamicModuleDescriptor descriptor = descriptor();
        DynamicEntityOperations operations = mock(DynamicEntityOperations.class);
        when(recordService.describe(MODULE)).thenReturn(descriptor);
        when(recordService.mainEntity(MODULE)).thenReturn(operations);
        when(operations.newRecord()).thenReturn(new net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord(entity()));

        mvc.perform(post("/{moduleAlias}/export/selected", MODULE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ids": ["order-1"],
                                  "query": {
                                    "sorts": [
                                      {"field": "displayCode", "desc": true}
                                    ]
                                  }
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(exportFacade, org.mockito.Mockito.never()).exportWorkbook(any(DynamicExportCommand.class));
    }

    @Test
    void shouldRejectVirtualFieldInExportQuickSearch() throws Exception {
        PlatformPageConfigSnapshotService snapshotService = mock(PlatformPageConfigSnapshotService.class);
        ModuleMetadataFieldService moduleFieldService = mock(ModuleMetadataFieldService.class);
        MockMvc exportMvc = MockMvcBuilders
                .standaloneSetup(new DynamicExportWebController(
                        recordService, activeTenantVerifier, exportFacade,
                        snapshotService, null, moduleFieldService))
                .setControllerAdvice(new PlatformWebExceptionHandler())
                .addFilters(new CurrentUserWebFilter(() -> java.util.Optional.of(
                        CurrentUser.tenantUser("user-1", "User", "tenant_a"))))
                .build();
        DynamicModuleDescriptor descriptor = descriptor();
        DynamicEntityOperations operations = mock(DynamicEntityOperations.class);
        PlatformUiSet uiSet = uiSet("set-list");
        PlatformUiConfig uiConfig = uiConfig("ui-list", uiSet.getId());
        PlatformUiConfigField displayField = uiField(uiConfig.getId(), "field-display-code");
        when(recordService.describe(MODULE)).thenReturn(descriptor);
        when(recordService.mainEntity(MODULE)).thenReturn(operations);
        when(snapshotService.snapshot(MODULE)).thenReturn(new PlatformPageConfigSnapshot(
                MODULE, List.of(uiSet), List.of(uiConfig), List.of(displayField), List.of(), List.of()));
        when(moduleFieldService.resolve("field-display-code")).thenReturn(resolvedField(
                "field-display-code", "displayCode", "string", MetadataFieldForm.VIRTUAL));

        exportMvc.perform(post("/{moduleAlias}/export/data", MODULE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "uiConfigId": "ui-list",
                                  "quickSearch": "C-001",
                                  "quickSearchFields": ["displayCode"]
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(exportFacade, org.mockito.Mockito.never()).exportWorkbook(any(DynamicExportCommand.class));
    }

    @Test
    void shouldRejectDataExportWhenModuleDoesNotSupportExchange() throws Exception {
        when(recordService.describe(MODULE)).thenReturn(descriptorWithoutExchange());

        mvc.perform(post("/{moduleAlias}/export/data", MODULE))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldRejectSelectedExportWithoutIds() throws Exception {
        when(recordService.describe(MODULE)).thenReturn(descriptor());

        mvc.perform(post("/{moduleAlias}/export/selected", MODULE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[]}"))
                .andExpect(status().isBadRequest());
    }

    private DynamicModuleDescriptor descriptor() {
        return DynamicModuleDescriptor.from(new ModuleDefinition(
                MODULE,
                "Order",
                List.of(entity().withCapabilities(EntityCapability.EXCHANGE))
        ));
    }

    private DynamicModuleDescriptor descriptorWithoutExchange() {
        return DynamicModuleDescriptor.from(new ModuleDefinition(
                MODULE,
                "Order",
                List.of(entity())
        ));
    }

    private EntityDefinition entity() {
        return new EntityDefinition("order", "sales_order", "Order", List.of(
                FieldDefinition.string("orderNo", "Order No").column("order_no"),
                FieldDefinition.string("displayCode", "Display Code").column("display_code").virtual()
        ));
    }

    private PlatformUiSet uiSet(String id) {
        PlatformUiSet uiSet = new PlatformUiSet();
        uiSet.setId(id);
        uiSet.setModuleAlias(MODULE);
        uiSet.setAlias("list");
        uiSet.setSetType(PlatformUiSetType.LIST);
        return uiSet;
    }

    private PlatformUiConfig uiConfig(String id, String uiSetId) {
        PlatformUiConfig uiConfig = new PlatformUiConfig();
        uiConfig.setId(id);
        uiConfig.setUiSetId(uiSetId);
        uiConfig.setClientType(PlatformUiClientType.WEB);
        uiConfig.setPublished(true);
        return uiConfig;
    }

    private PlatformUiConfigField uiField(String uiConfigId, String moduleFieldId) {
        PlatformUiConfigField field = new PlatformUiConfigField();
        field.setUiConfigId(uiConfigId);
        field.setModuleMetadataFieldId(moduleFieldId);
        field.setVisible(true);
        return field;
    }

    private ResolvedModuleMetadataField resolvedField(String id, String fieldName, String fieldSpecAlias) {
        return resolvedField(id, fieldName, fieldSpecAlias, MetadataFieldForm.PHYSICAL);
    }

    private ResolvedModuleMetadataField resolvedField(String id,
                                                     String fieldName,
                                                     String fieldSpecAlias,
                                                     MetadataFieldForm fieldForm) {
        return new ResolvedModuleMetadataField(
                id,
                MODULE,
                "rel-main",
                "main",
                RelationRole.MAIN,
                "metadata-order",
                "order",
                "Order",
                "metadata-" + fieldName,
                fieldName,
                fieldName,
                fieldName,
                fieldSpecAlias,
                fieldForm
        );
    }

    private List<CriteriaClause> clauses(Criteria criteria) {
        List<CriteriaClause> result = new ArrayList<>();
        collect(criteria.getRoot(), result);
        return result;
    }

    private void collect(CriteriaGroup group, List<CriteriaClause> result) {
        for (CriteriaGroup.Entry entry : group.getEntries()) {
            Object node = node(entry);
            if (node instanceof CriteriaClause clause) {
                result.add(clause);
            } else if (node instanceof CriteriaGroup childGroup) {
                collect(childGroup, result);
            }
        }
    }

    private Object node(CriteriaGroup.Entry entry) {
        try {
            Method method = entry.getClass().getMethod("getNode");
            return method.invoke(entry);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read criteria node", e);
        }
    }

}
