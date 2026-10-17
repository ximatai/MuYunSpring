package net.ximatai.muyun.spring.dynamic.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.ximatai.muyun.spring.common.i18n.*;
import net.ximatai.muyun.spring.common.identity.*;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.tenant.ActiveTenantVerifier;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;
import net.ximatai.muyun.spring.dynamic.metadata.*;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.exchange.RecordExchangeService;
import net.ximatai.muyun.spring.platform.exchange.importer.*;
import net.ximatai.muyun.spring.platform.web.RecordExchangeWebSupport;
import net.ximatai.muyun.spring.web.CurrentUserWebFilter;
import net.ximatai.muyun.spring.web.PlatformWebExceptionHandler;

import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.*;

/**
 * Legacy transport forwards to the same source-neutral file engine; no legacy write facade remains
 * on HTTP.
 */
class DynamicImportWebControllerTest {
    private static final String MODULE = "sales.order";
    private final ObjectMapper json = new ObjectMapper();
    private DynamicRecordService records;
    private RecordExchangeWebSupport exchange;
    private DynamicImportErrorFileService files;
    private ActiveTenantVerifier tenants;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        records = mock(DynamicRecordService.class);
        exchange = mock(RecordExchangeWebSupport.class);
        files = new DynamicImportErrorFileService();
        tenants = mock(ActiveTenantVerifier.class);
        var beans = new DefaultListableBeanFactory();
        var catalog = LanguageCatalog.load(getClass().getClassLoader(), "zh-CN");
        beans.registerSingleton("catalog", catalog);
        beans.registerSingleton("texts", TextResources.load(getClass().getClassLoader(), catalog));
        when(records.describe(MODULE)).thenReturn(descriptor(true));
        mvc =
                MockMvcBuilders.standaloneSetup(
                                new DynamicImportWebController(
                                        records,
                                        exchange,
                                        files,
                                        tenants,
                                        beans.getBeanProvider(TextResolver.class),
                                        beans.getBeanProvider(LanguageCatalog.class)))
                        .setControllerAdvice(new PlatformWebExceptionHandler())
                        .addFilters(new CurrentUserWebFilter(() -> Optional.of(actor("user-1"))))
                        .build();
    }

    @Test
    void parsesV2FileMetadataThroughTheCommonEngineWithoutExposingTransportColumnsAsMatchFields()
            throws Exception {
        when(exchange.compatibilityPreview(eq(MODULE), any()))
                .thenReturn(
                        new RecordExchangeService.Preview(
                                "document",
                                "digest",
                                "DATA",
                                "en",
                                List.of(
                                        new RecordExchangeService.Sheet(
                                                "order",
                                                "Order",
                                                true,
                                                1,
                                                List.of("orderNo"),
                                                Map.of("orderNo", "Order No"))),
                                List.of()));
        mvc.perform(
                        multipart("/{moduleAlias}/import/parse", MODULE)
                                .file(file(new byte[] {1, 2, 3})))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mainEntityAlias").value("order"))
                .andExpect(jsonPath("$.sheets[0].fields[0].fieldName").value("orderNo"))
                .andExpect(jsonPath("$.sheets[0].fields[0].matchKeyCandidate").value(true));
        verify(exchange).compatibilityPreview(MODULE, new byte[] {1, 2, 3});
        verify(tenants).verifyActiveTenant("tenant_a");
    }

    @Test
    void rejectsMissingFileOrCommandPartsThroughTheStandardErrorContract() throws Exception {
        mvc.perform(multipart("/{moduleAlias}/import/parse", MODULE))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.targets[0].fieldName").value("file"));
        mvc.perform(multipart("/{moduleAlias}/import/execute", MODULE).file(file(new byte[] {1})))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.targets[0].fieldName").value("command"));
        verifyNoInteractions(exchange);
    }

    @Test
    void disabledExchangeCannotReachTheSharedEngine() throws Exception {
        when(records.describe(MODULE)).thenReturn(descriptor(false));
        mvc.perform(multipart("/{moduleAlias}/import/parse", MODULE).file(file(new byte[] {1})))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(exchange);
    }

    @Test
    void forwardsLegacyStrategiesAndReturnsTheCommonPartialResultAndCorrectionFile()
            throws Exception {
        var rows = new ArrayList<RecordExchangeService.PreviewRow>();
        for (String operation :
                List.of("CREATE", "UPDATE", "UPDATE", "SKIP", "SKIP", "SKIP", "ERROR"))
            rows.add(
                    new RecordExchangeService.PreviewRow(
                            operation, "order", "R", operation, "record", Map.of(), Map.of(),
                            Map.of(), List.of(), null));
        when(exchange.compatibilityImport(eq(MODULE), any(), any()))
                .thenReturn(
                        new RecordExchangeService.Result(
                                1,
                                2,
                                3,
                                1,
                                true,
                                Base64.getEncoder().encodeToString(new byte[] {9, 8, 7}),
                                rows));
        mvc.perform(
                        multipart("/{moduleAlias}/import/execute", MODULE)
                                .file(
                                        command(
                                                Map.of(
                                                        "mainSheet",
                                                                Map.of(
                                                                        "matchFieldName",
                                                                        "orderNo",
                                                                        "duplicateStrategy",
                                                                        "OVERWRITE"),
                                                        "childSheets",
                                                                List.of(
                                                                        Map.of(
                                                                                "entityAlias",
                                                                                "line",
                                                                                "matchFieldName",
                                                                                "sku",
                                                                                "duplicateStrategy",
                                                                                "SKIP")))))
                                .file(file(new byte[] {4, 5, 6})))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.updated").value(2))
                .andExpect(jsonPath("$.skipped").value(3))
                .andExpect(jsonPath("$.errorCount").value(1))
                .andExpect(jsonPath("$.partialSuccess").value(true))
                .andExpect(jsonPath("$.summaries.order.created").value(1))
                .andExpect(jsonPath("$.summaries.order.errors").value(1))
                .andExpect(jsonPath("$.errorFileToken").isNotEmpty())
                .andExpect(
                        jsonPath("$.messageText.languages.en.text")
                                .value("Import completed. Correct the errors and retry."));
        var captured = ArgumentCaptor.forClass(RecordExchangeService.Command.class);
        verify(exchange)
                .compatibilityImport(eq(MODULE), eq(new byte[] {4, 5, 6}), captured.capture());
        assertThat(captured.getValue().sheets())
                .containsExactly(
                        new RecordExchangeService.SheetCommand(
                                "order", "orderNo", ImportDuplicateStrategy.OVERWRITE),
                        new RecordExchangeService.SheetCommand(
                                "line", "sku", ImportDuplicateStrategy.SKIP));
    }

    @Test
    void correctionDownloadRequiresTheOriginalCacheOwnerAndFreshProducerSourceAuthorization()
            throws Exception {
        String token = cache(MODULE, "user-1");
        mvc.perform(post("/{moduleAlias}/import/error-file/{token}", MODULE, token))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Import-FileName", "errors.xlsx"))
                .andExpect(content().bytes(new byte[] {9, 8, 7}));
        verify(exchange).requireCompatibilityFileRead(MODULE, new byte[] {9, 8, 7});
        doThrow(
                        new net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException(
                                "Source access revoked"))
                .when(exchange)
                .requireCompatibilityFileRead(eq(MODULE), any());
        mvc.perform(post("/{moduleAlias}/import/error-file/{token}", MODULE, token))
                .andExpect(status().isForbidden());
    }

    @Test
    void anotherUserOrModuleCannotDownloadACachedCorrectionFile() throws Exception {
        mvc.perform(
                        post(
                                "/{moduleAlias}/import/error-file/{token}",
                                MODULE,
                                cache(MODULE, "other")))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        post(
                                "/{moduleAlias}/import/error-file/{token}",
                                MODULE,
                                cache("crm.customer", "user-1")))
                .andExpect(status().isBadRequest());
        verify(exchange, never()).requireCompatibilityFileRead(anyString(), any());
    }

    private String cache(String module, String user) {
        try (var ignored = CurrentUserContext.use(actor(user))) {
            return files.save(module, "tenant_a", "errors.xlsx", new byte[] {9, 8, 7});
        }
    }

    private CurrentUser actor(String id) {
        return CurrentUser.tenantUser(id, "User", "tenant_a");
    }

    private MockMultipartFile file(byte[] bytes) {
        return new MockMultipartFile(
                "file", "file.xlsx", DynamicImportWebController.XLSX_CONTENT_TYPE, bytes);
    }

    private MockMultipartFile command(Object value) throws Exception {
        return new MockMultipartFile(
                "command",
                "command.json",
                MediaType.APPLICATION_JSON_VALUE,
                json.writeValueAsBytes(value));
    }

    private DynamicModuleDescriptor descriptor(boolean enabled) {
        return DynamicModuleDescriptor.from(
                new ModuleDefinition(
                        MODULE,
                        "Order",
                        List.of(
                                new EntityDefinition(
                                        "order",
                                        "sales_order",
                                        "Order",
                                        List.of(FieldDefinition.string("orderNo", "Order No")),
                                        enabled ? Set.of(EntityCapability.EXCHANGE) : Set.of()))));
    }
}
