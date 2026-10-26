package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.config.LowCodeConfigBundle;
import net.ximatai.muyun.spring.platform.config.LowCodeConfigHealthReport;
import net.ximatai.muyun.spring.platform.config.LowCodeModuleConfigArchiveFacade;
import net.ximatai.muyun.spring.platform.config.LowCodeModuleConfigArchiveResult;
import net.ximatai.muyun.spring.platform.config.LowCodeModuleConfigVersion;
import net.ximatai.muyun.spring.platform.config.LowCodeModuleHealthContext;
import net.ximatai.muyun.spring.platform.config.LowCodeModuleHealthService;
import net.ximatai.muyun.spring.platform.config.LowCodeModulePackage;
import net.ximatai.muyun.spring.platform.config.LowCodeModulePackageExchangeService;
import net.ximatai.muyun.spring.platform.config.LowCodeModulePackageImportDraft;
import net.ximatai.muyun.spring.platform.config.LowCodeModulePackageImportService;
import net.ximatai.muyun.spring.platform.config.LowCodeModuleTemplate;
import net.ximatai.muyun.spring.platform.config.LowCodeModuleTemplateInstantiationRequest;
import net.ximatai.muyun.spring.platform.config.LowCodeModuleTemplateService;
import net.ximatai.muyun.spring.platform.config.LowCodePackageBundleType;
import net.ximatai.muyun.spring.platform.config.LowCodePackageDryRunResult;
import net.ximatai.muyun.spring.platform.config.LowCodePackageMode;
import net.ximatai.muyun.spring.platform.config.LowCodePackageExchangeManifest;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class LowCodeGovernanceWebControllerTest {
    @BeforeEach
    void setUp() {
        TenantContext.setTenantId("tenant-a");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void shouldCheckPackageHealthThroughGovernanceEndpoint() throws Exception {
        LowCodeModuleHealthService healthService = mock(LowCodeModuleHealthService.class);
        LowCodeConfigHealthReport report = LowCodeConfigHealthReport.of("crm.contract", List.of());
        when(healthService.check(any(LowCodeModuleHealthContext.class))).thenReturn(report);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller(healthService)).build();
        mvc.perform(post("/platform.low_code_governance/packages/health")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(packageJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moduleAlias").value("crm.contract"))
                .andExpect(jsonPath("$.status").value("PASS"));

        ArgumentCaptor<LowCodeModuleHealthContext> captor =
                ArgumentCaptor.forClass(LowCodeModuleHealthContext.class);
        verify(healthService).check(captor.capture());
        assertThat(captor.getValue().moduleAlias()).isEqualTo("crm.contract");
    }

    @Test
    void healthEndpointDeliversBilingualResourcesAndRetainsOriginalDiagnostics() throws Exception {
        var health = mock(LowCodeModuleHealthService.class);
        var text = net.ximatai.muyun.spring.common.i18n.DiagnosticText.code(
                "platform.config.diagnostic.package-dependency-is-missing", "Package dependency is missing: {type}",
                Map.of("type", "custom {type}"));
        var item = net.ximatai.muyun.spring.platform.config.LowCodeConfigHealthItem.error(
                net.ximatai.muyun.spring.platform.config.LowCodeConfigHealthScope.DEPENDENCY, "REQUIRED_DEPENDENCY_MISSING",
                text, "MODULE", "crm.customer", text);
        when(health.check(any())).thenReturn(LowCodeConfigHealthReport.of("crm.contract", List.of(item)));
        var loader = getClass().getClassLoader();
        var languages = net.ximatai.muyun.spring.common.i18n.LanguageCatalog.load(loader, "zh-CN");
        var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        beans.registerSingleton("languages", languages);
        beans.registerSingleton("texts", net.ximatai.muyun.spring.common.i18n.TextResources.load(loader, languages));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var advice = new PlatformPresentationResponseAdvice(mapper,
                beans.getBeanProvider(net.ximatai.muyun.spring.common.i18n.TextResolver.class),
                beans.getBeanProvider(net.ximatai.muyun.spring.common.i18n.LanguageCatalog.class));
        var mvc = MockMvcBuilders.standaloneSetup(controller(health)).setControllerAdvice(advice)
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(mapper)).build();
        mvc.perform(post("/platform.low_code_governance/packages/health").contentType(MediaType.APPLICATION_JSON).content(packageJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAIL"))
                .andExpect(jsonPath("$.items[0].code").value("REQUIRED_DEPENDENCY_MISSING"))
                .andExpect(jsonPath("$.items[0].targetId").value("crm.customer"))
                .andExpect(jsonPath("$.items[0].message").value("Package dependency is missing: custom {type}"))
                .andExpect(jsonPath("$.items[0].messageText.languages.en.text").value("Package dependency is missing: custom {type}"))
                .andExpect(jsonPath("$.items[0].messageText.languages['zh-CN'].text").value("缺少配置包依赖：custom {type}"))
                .andExpect(jsonPath("$.items[0].suggestionText.languages['zh-CN'].text").value("缺少配置包依赖：custom {type}"));
    }

    @Test
    void shouldArchivePackageThroughArchiveFacade() throws Exception {
        LowCodeModuleConfigArchiveFacade archiveFacade = mock(LowCodeModuleConfigArchiveFacade.class);
        LowCodeModuleConfigVersion version = version("version-1");
        when(archiveFacade.archive(any(LowCodeModulePackage.class), any(), any()))
                .thenReturn(new LowCodeModuleConfigArchiveResult(version,
                        LowCodeConfigHealthReport.of("crm.contract", List.of())));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller(archiveFacade)).build();
        mvc.perform(post("/platform.low_code_governance/packages/archive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "modulePackage": %s,
                                  "operatorId": "u-1",
                                  "remark": "归档"
                                }
                                """.formatted(packageJson())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version.id").value("version-1"))
                .andExpect(jsonPath("$.version.moduleAlias").value("crm.contract"));

        ArgumentCaptor<LowCodeModulePackage> packageCaptor = ArgumentCaptor.forClass(LowCodeModulePackage.class);
        verify(archiveFacade).archive(packageCaptor.capture(), org.mockito.ArgumentMatchers.eq("u-1"),
                org.mockito.ArgumentMatchers.eq("归档"));
        assertThat(packageCaptor.getValue().moduleAlias()).isEqualTo("crm.contract");
    }

    @Test
    void shouldSwitchCurrentPackageVersionThroughArchiveFacade() throws Exception {
        LowCodeModuleConfigArchiveFacade archiveFacade = mock(LowCodeModuleConfigArchiveFacade.class);
        when(archiveFacade.switchCurrentVersion("crm.contract", "version-1")).thenReturn(version("version-1"));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller(archiveFacade)).build();
        mvc.perform(post("/platform/low-code-governance/modules/crm.contract/versions/version-1/switch-current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("version-1"))
                .andExpect(jsonPath("$.currentVersion").value(true));

        verify(archiveFacade).switchCurrentVersion("crm.contract", "version-1");
    }

    @Test
    void shouldExportCurrentAndVersionPackageThroughExchangeService() throws Exception {
        LowCodeModulePackageExchangeService exchangeService = mock(LowCodeModulePackageExchangeService.class);
        LowCodeModulePackage modulePackage = modulePackage();
        when(exchangeService.exportCurrentPackage("crm.contract")).thenReturn(packageJson());
        when(exchangeService.exportVersionPackage("version-1")).thenReturn(packageJson());
        when(exchangeService.parsePackage(packageJson())).thenReturn(modulePackage);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller(exchangeService)).build();
        mvc.perform(get("/platform.low_code_governance/modules/crm.contract/package"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moduleAlias").value("crm.contract"));
        mvc.perform(get("/platform.low_code_governance/versions/version-1/package"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.moduleAlias").value("crm.contract"));

        verify(exchangeService).exportCurrentPackage("crm.contract");
        verify(exchangeService).exportVersionPackage("version-1");
    }

    @Test
    void shouldPrepareAndArchiveImportDraftThroughImportService() throws Exception {
        LowCodeModulePackageExchangeService exchangeService = mock(LowCodeModulePackageExchangeService.class);
        LowCodeModulePackageImportService importService = mock(LowCodeModulePackageImportService.class);
        LowCodeModulePackage modulePackage = modulePackage();
        LowCodePackageDryRunResult dryRun = new LowCodePackageDryRunResult(
                modulePackage, null, LowCodeConfigHealthReport.of("crm.contract", List.of()), List.of());
        LowCodeModulePackageImportDraft draft = new LowCodeModulePackageImportDraft(
                "draft-1", modulePackage, dryRun, null, Instant.EPOCH);
        when(exchangeService.dryRunImport(any(LowCodeModulePackage.class))).thenReturn(dryRun);
        when(importService.prepareDraft(any(LowCodeModulePackage.class))).thenReturn(draft);
        when(importService.archiveDraft(any(LowCodeModulePackageImportDraft.class), any(), any()))
                .thenReturn(new LowCodeModuleConfigArchiveResult(version("version-1"), dryRun.healthReport()));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller(exchangeService, importService)).build();
        mvc.perform(post("/platform.low_code_governance/imports/dry-run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(packageJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));
        mvc.perform(post("/platform.low_code_governance/imports/drafts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(packageJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draftId").value("draft-1"));
        mvc.perform(post("/platform.low_code_governance/imports/drafts/archive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "draft": {
                                    "draftId": "draft-1",
                                    "modulePackage": %s,
                                    "dryRunResult": {
                                      "modulePackage": %s,
                                      "healthReport": {"moduleAlias": "crm.contract", "items": []},
                                      "conflicts": []
                                    },
                                    "createdAt": "1970-01-01T00:00:00Z"
                                  },
                                  "operatorId": "u-1",
                                  "remark": "导入"
                                }
                                """.formatted(packageJson(), packageJson())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version.id").value("version-1"));

        verify(exchangeService).dryRunImport(any(LowCodeModulePackage.class));
        verify(importService).prepareDraft(any(LowCodeModulePackage.class));
        verify(importService).archiveDraft(any(LowCodeModulePackageImportDraft.class),
                org.mockito.ArgumentMatchers.eq("u-1"), org.mockito.ArgumentMatchers.eq("导入"));
    }

    @Test
    void shouldCreateAndInstantiateTemplateThroughGovernanceEndpoint() throws Exception {
        LowCodeModuleTemplateService templateService = mock(LowCodeModuleTemplateService.class);
        LowCodeModuleTemplate template = template();
        LowCodeModulePackage instantiated = new LowCodeModulePackage(
                "1.0",
                LowCodePackageMode.MODULE_FULL,
                "sales",
                "sales.contract",
                List.of(LowCodeConfigBundle.included(LowCodePackageBundleType.METADATA,
                        Map.of("moduleAlias", "sales.contract"))),
                null,
                LowCodePackageExchangeManifest.draft("1.0"));
        when(templateService.createTemplateFromVersion("contract_template", "Contract Template", "version-1"))
                .thenReturn(template);
        when(templateService.instantiate(any(LowCodeModuleTemplate.class),
                any(LowCodeModuleTemplateInstantiationRequest.class))).thenReturn(instantiated);

        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller(templateService)).build();
        mvc.perform(post("/platform.low_code_governance/templates/from-version")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "templateAlias": "contract_template",
                                  "title": "Contract Template",
                                  "versionId": "version-1"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.templateAlias").value("contract_template"))
                .andExpect(jsonPath("$.basePackage.mode").value("TEMPLATE"));
        mvc.perform(post("/platform.low_code_governance/templates/instantiate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "template": {
                                    "templateAlias": "contract_template",
                                    "title": "Contract Template",
                                    "basePackage": %s
                                  },
                                  "request": {
                                    "applicationAlias": "sales",
                                    "moduleAlias": "sales.contract",
                                    "title": "Sales Contract",
                                    "parameters": {"tableName": "sales_contract"}
                                  }
                                }
                                """.formatted(templatePackageJson())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("MODULE_FULL"))
                .andExpect(jsonPath("$.moduleAlias").value("sales.contract"));

        verify(templateService).createTemplateFromVersion("contract_template", "Contract Template", "version-1");
        ArgumentCaptor<LowCodeModuleTemplate> templateCaptor =
                ArgumentCaptor.forClass(LowCodeModuleTemplate.class);
        ArgumentCaptor<LowCodeModuleTemplateInstantiationRequest> requestCaptor =
                ArgumentCaptor.forClass(LowCodeModuleTemplateInstantiationRequest.class);
        verify(templateService).instantiate(templateCaptor.capture(), requestCaptor.capture());
        assertThat(templateCaptor.getValue().templateAlias()).isEqualTo("contract_template");
        assertThat(templateCaptor.getValue().basePackage().mode()).isEqualTo(LowCodePackageMode.TEMPLATE);
        assertThat(templateCaptor.getValue().basePackage().moduleAlias()).isEqualTo("crm.contract");
        assertThat(requestCaptor.getValue().moduleAlias()).isEqualTo("sales.contract");
        assertThat(requestCaptor.getValue().parameters()).containsEntry("tableName", "sales_contract");
    }

    private LowCodeGovernanceWebController controller(LowCodeModuleHealthService healthService) {
        return new LowCodeGovernanceWebController(
                mock(LowCodeModuleConfigArchiveFacade.class),
                healthService,
                mock(LowCodeModulePackageExchangeService.class),
                mock(LowCodeModulePackageImportService.class),
                mock(LowCodeModuleTemplateService.class));
    }

    private LowCodeGovernanceWebController controller(LowCodeModuleConfigArchiveFacade archiveFacade) {
        return new LowCodeGovernanceWebController(
                archiveFacade,
                mock(LowCodeModuleHealthService.class),
                mock(LowCodeModulePackageExchangeService.class),
                mock(LowCodeModulePackageImportService.class),
                mock(LowCodeModuleTemplateService.class));
    }

    private LowCodeGovernanceWebController controller(LowCodeModulePackageExchangeService exchangeService) {
        return controller(exchangeService, mock(LowCodeModulePackageImportService.class));
    }

    private LowCodeGovernanceWebController controller(LowCodeModulePackageExchangeService exchangeService,
                                                     LowCodeModulePackageImportService importService) {
        return new LowCodeGovernanceWebController(
                mock(LowCodeModuleConfigArchiveFacade.class),
                mock(LowCodeModuleHealthService.class),
                exchangeService,
                importService,
                mock(LowCodeModuleTemplateService.class));
    }

    private LowCodeGovernanceWebController controller(LowCodeModuleTemplateService templateService) {
        return new LowCodeGovernanceWebController(
                mock(LowCodeModuleConfigArchiveFacade.class),
                mock(LowCodeModuleHealthService.class),
                mock(LowCodeModulePackageExchangeService.class),
                mock(LowCodeModulePackageImportService.class),
                templateService);
    }

    private LowCodeModulePackage modulePackage() {
        return new LowCodeModulePackage(
                "1.0",
                LowCodePackageMode.MODULE_FULL,
                "crm",
                "crm.contract",
                List.of(LowCodeConfigBundle.included(LowCodePackageBundleType.METADATA,
                        Map.of("moduleAlias", "crm.contract"))),
                null,
                LowCodePackageExchangeManifest.draft("1.0"));
    }

    private LowCodeModuleTemplate template() {
        return new LowCodeModuleTemplate("contract_template", "Contract Template", templatePackage());
    }

    private LowCodeModulePackage templatePackage() {
        return new LowCodeModulePackage(
                "1.0",
                LowCodePackageMode.TEMPLATE,
                "crm",
                "crm.contract",
                List.of(LowCodeConfigBundle.included(LowCodePackageBundleType.METADATA,
                        Map.of("moduleAlias", "crm.contract"))),
                null,
                LowCodePackageExchangeManifest.draft("1.0"));
    }

    private LowCodeModuleConfigVersion version(String id) {
        LowCodeModuleConfigVersion version = new LowCodeModuleConfigVersion();
        version.setId(id);
        version.setModuleAlias("crm.contract");
        version.setVersionNo(1);
        version.setCurrentVersion(Boolean.TRUE);
        version.setPackageSnapshotText(packageJson());
        version.setPackageHash("hash");
        return version;
    }

    private String packageJson() {
        return """
                {
                  "protocolVersion": "1.0",
                  "mode": "MODULE_FULL",
                  "applicationAlias": "crm",
                  "moduleAlias": "crm.contract",
                  "bundles": [
                    {
                      "type": "METADATA",
                      "included": true,
                      "content": {"moduleAlias": "crm.contract"}
                    }
                  ]
                }
                """;
    }

    private String templatePackageJson() {
        return """
                {
                  "protocolVersion": "1.0",
                  "mode": "TEMPLATE",
                  "applicationAlias": "crm",
                  "moduleAlias": "crm.contract",
                  "bundles": [
                    {
                      "type": "METADATA",
                      "included": true,
                      "content": {"moduleAlias": "crm.contract"}
                    }
                  ]
                }
                """;
    }
}
