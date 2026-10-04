package net.ximatai.muyun.spring.dynamic.web;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.exchange.importer.BuildDynamicImportPlanCommand;
import net.ximatai.muyun.spring.platform.exchange.importer.DynamicImportCommand;
import net.ximatai.muyun.spring.platform.exchange.importer.DynamicImportErrorFileService;
import net.ximatai.muyun.spring.platform.exchange.importer.DynamicImportExecutionResult;
import net.ximatai.muyun.spring.platform.exchange.importer.DynamicImportFacade;
import net.ximatai.muyun.spring.platform.exchange.importer.DynamicImportParseResult;
import net.ximatai.muyun.spring.platform.exchange.importer.DynamicImportPlan;
import net.ximatai.muyun.spring.platform.exchange.importer.DynamicImportResult;
import net.ximatai.muyun.spring.platform.exchange.importer.GroupedWorkbook;
import net.ximatai.muyun.spring.platform.exchange.importer.ImportDuplicateStrategy;
import net.ximatai.muyun.spring.platform.exchange.importer.ImportErrorRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DynamicImportWebControllerTest {
    private static final String MODULE = "sales.order";
    private final ObjectMapper objectMapper = new ObjectMapper();

    private DynamicRecordService recordService;
    private DynamicImportFacade importFacade;
    private DynamicImportErrorFileService errorFileService;
    private ActiveTenantVerifier activeTenantVerifier;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        recordService = mock(DynamicRecordService.class);
        importFacade = mock(DynamicImportFacade.class);
        errorFileService = new DynamicImportErrorFileService();
        activeTenantVerifier = mock(ActiveTenantVerifier.class);
        mvc = MockMvcBuilders
                .standaloneSetup(new DynamicImportWebController(
                        recordService, importFacade, errorFileService, activeTenantVerifier))
                .setControllerAdvice(new PlatformWebExceptionHandler())
                .addFilters(new CurrentUserWebFilter(() -> java.util.Optional.of(
                        CurrentUser.tenantUser("user-1", "User", "tenant_a"))))
                .build();
    }

    @Test
    void shouldParseMultipartWorkbookThroughFacade() throws Exception {
        DynamicModuleDescriptor descriptor = descriptor();
        when(recordService.describe(MODULE)).thenReturn(descriptor);
        when(importFacade.parse(descriptor, new byte[]{1, 2, 3})).thenReturn(new DynamicImportParseResult(
                MODULE,
                "order",
                "Order",
                List.of(new DynamicImportParseResult.Sheet(
                        "Order",
                        "Order",
                        "order",
                        true,
                        1,
                        List.of(new DynamicImportParseResult.Field("orderNo", "Order No", false, true))
                ))
        ));

        mvc.perform(multipart("/{moduleAlias}/import/parse", MODULE)
                        .file(new MockMultipartFile("file", "order.xlsx",
                                DynamicImportWebController.XLSX_CONTENT_TYPE, new byte[]{1, 2, 3})))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moduleAlias").value(MODULE))
                .andExpect(jsonPath("$.mainEntityAlias").value("order"))
                .andExpect(jsonPath("$.mainSheetName").value("Order"))
                .andExpect(jsonPath("$.sheets[0].fields[0].matchKeyCandidate").value(true));

        verify(recordService).describe(MODULE);
        verify(activeTenantVerifier).verifyActiveTenant("tenant_a");
    }

    @Test
    void shouldReturnPlatformErrorWhenParseFilePartIsMissing() throws Exception {
        mvc.perform(multipart("/{moduleAlias}/import/parse", MODULE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("缺少必要请求部分"))
                .andExpect(jsonPath("$.targets[0].fieldName").value("file"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void shouldReturnPlatformErrorWhenExecuteCommandPartIsMissing() throws Exception {
        mvc.perform(multipart("/{moduleAlias}/import/execute", MODULE)
                        .file(new MockMultipartFile("file", "order.xlsx",
                                DynamicImportWebController.XLSX_CONTENT_TYPE, new byte[]{1, 2, 3})))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("缺少必要请求部分"))
                .andExpect(jsonPath("$.targets[0].fieldName").value("command"));
    }

    @Test
    void shouldRejectParseWhenModuleDoesNotSupportExchange() throws Exception {
        when(recordService.describe(MODULE)).thenReturn(descriptorWithoutExchange());

        mvc.perform(multipart("/{moduleAlias}/import/parse", MODULE)
                        .file(new MockMultipartFile("file", "order.xlsx",
                                DynamicImportWebController.XLSX_CONTENT_TYPE, new byte[]{1, 2, 3})))
                .andExpect(status().isBadRequest());
    }

    @Test
    void shouldExecuteImportAndReturnErrorFileToken() throws Exception {
        DynamicModuleDescriptor descriptor = descriptor();
        when(recordService.describe(MODULE)).thenReturn(descriptor);
        when(importFacade.importWorkbook(any(DynamicImportCommand.class))).thenReturn(importResultWithErrors());

        MockMultipartFile command = new MockMultipartFile("command", "command.json",
                MediaType.APPLICATION_JSON_VALUE,
                objectMapper.writeValueAsBytes(Map.of(
                        "mainSheet", Map.of(
                                "matchFieldName", "orderNo",
                                "duplicateStrategy", "OVERWRITE"
                        ),
                        "childSheets", List.of(Map.of(
                                "entityAlias", "orderLine",
                                "matchFieldName", "sku",
                                "duplicateStrategy", "SKIP"
                        ))
                )));

        mvc.perform(multipart("/{moduleAlias}/import/execute", MODULE)
                        .file(command)
                        .file(new MockMultipartFile("file", "order.xlsx",
                                DynamicImportWebController.XLSX_CONTENT_TYPE, new byte[]{4, 5, 6})))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.updated").value(2))
                .andExpect(jsonPath("$.skipped").value(3))
                .andExpect(jsonPath("$.errorCount").value(1))
                .andExpect(jsonPath("$.summaries.order.created").value(1))
                .andExpect(jsonPath("$.summaries.order.updated").value(2))
                .andExpect(jsonPath("$.summaries.order.skipped").value(3))
                .andExpect(jsonPath("$.summaries.order.errors").value(1))
                .andExpect(jsonPath("$.partialSuccess").value(true))
                .andExpect(jsonPath("$.errorFileName").value("sales_order-import-errors.xlsx"))
                .andExpect(jsonPath("$.errorFileToken").isNotEmpty());

        ArgumentCaptor<DynamicImportCommand> captor = ArgumentCaptor.forClass(DynamicImportCommand.class);
        verify(importFacade).importWorkbook(captor.capture());
        assertThat(captor.getValue().descriptor()).isSameAs(descriptor);
        assertThat(captor.getValue().excelBytes()).containsExactly(4, 5, 6);
        BuildDynamicImportPlanCommand buildCommand = captor.getValue().buildPlanCommand();
        assertThat(buildCommand.mainMatchFieldName()).isEqualTo("orderNo");
        assertThat(buildCommand.mainDuplicateStrategy()).isEqualTo(ImportDuplicateStrategy.OVERWRITE);
        assertThat(buildCommand.childSheets().getFirst().entityAlias()).isEqualTo("orderLine");
        assertThat(buildCommand.childSheets().getFirst().duplicateStrategy()).isEqualTo(ImportDuplicateStrategy.SKIP);
    }

    @Test
    void shouldTreatSkippedRowsAsHandledWhenErrorsAlsoExist() throws Exception {
        DynamicModuleDescriptor descriptor = descriptor();
        when(recordService.describe(MODULE)).thenReturn(descriptor);
        when(importFacade.importWorkbook(any(DynamicImportCommand.class))).thenReturn(importResultWithOnlySkippedAndErrors());

        MockMultipartFile command = new MockMultipartFile("command", "command.json",
                MediaType.APPLICATION_JSON_VALUE,
                objectMapper.writeValueAsBytes(Map.of(
                        "mainSheet", Map.of("matchFieldName", "orderNo")
                )));

        mvc.perform(multipart("/{moduleAlias}/import/execute", MODULE)
                        .file(command)
                        .file(new MockMultipartFile("file", "order.xlsx",
                                DynamicImportWebController.XLSX_CONTENT_TYPE, new byte[]{4, 5, 6})))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(0))
                .andExpect(jsonPath("$.updated").value(0))
                .andExpect(jsonPath("$.skipped").value(2))
                .andExpect(jsonPath("$.errorCount").value(1))
                .andExpect(jsonPath("$.partialSuccess").value(false));
    }

    @Test
    void shouldDownloadErrorFileByToken() throws Exception {
        String token = errorFileService.save(MODULE, "tenant_a", "errors.xlsx", new byte[]{9, 8, 7});
        when(recordService.describe(MODULE)).thenReturn(descriptor());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/{moduleAlias}/import/error-file/{token}", MODULE, token))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Import-FileName", "errors.xlsx"))
                .andExpect(header().string("Access-Control-Expose-Headers",
                        "Content-Disposition,X-Import-FileName"))
                .andExpect(content().bytes(new byte[]{9, 8, 7}));
    }

    @Test
    void shouldRejectErrorFileTokenFromAnotherModule() throws Exception {
        String token = errorFileService.save("crm.customer", "tenant_a", "errors.xlsx", new byte[]{9, 8, 7});
        when(recordService.describe(MODULE)).thenReturn(descriptor());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/{moduleAlias}/import/error-file/{token}", MODULE, token))
                .andExpect(status().isBadRequest());
    }

    private DynamicModuleDescriptor descriptor() {
        return DynamicModuleDescriptor.from(new ModuleDefinition(
                MODULE,
                "Order",
                List.of(new EntityDefinition("order", "sales_order", "Order", List.of(
                        FieldDefinition.string("orderNo", "Order No")
                ), java.util.Set.of(EntityCapability.EXCHANGE)))
        ));
    }

    private DynamicModuleDescriptor descriptorWithoutExchange() {
        return DynamicModuleDescriptor.from(new ModuleDefinition(
                MODULE,
                "Order",
                List.of(new EntityDefinition("order", "sales_order", "Order", List.of(
                        FieldDefinition.string("orderNo", "Order No")
                )))
        ));
    }

    private DynamicImportResult importResultWithErrors() {
        DynamicImportPlan plan = new DynamicImportPlan(MODULE, null, List.of(
                new DynamicImportPlan.SheetPlan(
                        "Order",
                        "order",
                        "Order",
                        true,
                        "orderNo",
                        ImportDuplicateStrategy.ERROR,
                        List.of(new DynamicImportPlan.FieldPlan("order", "orderNo", "Order No",
                                false, true, false))
                )
        ));
        DynamicImportExecutionResult execution = new DynamicImportExecutionResult(
                1,
                2,
                3,
                List.of(new ImportErrorRow("order", new LinkedHashMap<>(), "invalid", null)),
                Map.of("order", new net.ximatai.muyun.spring.platform.exchange.importer.ImportEntityExecutionSummary("order", 1, 2, 3, 1))
        );
        return new DynamicImportResult(plan, new GroupedWorkbook(new LinkedHashMap<>(), List.of()),
                execution, new byte[]{7, 8, 9});
    }

    private DynamicImportResult importResultWithOnlySkippedAndErrors() {
        DynamicImportPlan plan = new DynamicImportPlan(MODULE, null, List.of(
                new DynamicImportPlan.SheetPlan(
                        "Order",
                        "order",
                        "Order",
                        true,
                        "orderNo",
                        ImportDuplicateStrategy.ERROR,
                        List.of(new DynamicImportPlan.FieldPlan("order", "orderNo", "Order No",
                                false, true, false))
                )
        ));
        DynamicImportExecutionResult execution = new DynamicImportExecutionResult(
                0,
                0,
                2,
                List.of(new ImportErrorRow("order", new LinkedHashMap<>(), "invalid", null)),
                Map.of()
        );
        return new DynamicImportResult(plan, new GroupedWorkbook(new LinkedHashMap<>(), List.of()),
                execution, new byte[]{7, 8, 9});
    }
}
