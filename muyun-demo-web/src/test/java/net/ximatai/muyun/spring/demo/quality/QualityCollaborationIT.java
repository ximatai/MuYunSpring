package net.ximatai.muyun.spring.demo.quality;

import com.fasterxml.jackson.databind.*;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.iam.tenant.Tenant;
import net.ximatai.muyun.spring.iam.tenant.TenantService;
import net.ximatai.muyun.spring.iam.tenant.TenantApplicationService;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.metadata.MetadataFieldService;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevisionService;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevisionStatus;
import net.ximatai.muyun.spring.platform.ui.PlatformPresentationRevision;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;

import net.ximatai.muyun.spring.boot.MuYunSpringApplication;
import net.ximatai.muyun.spring.common.identity.*;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.demo.quality.configuration.*;
import net.ximatai.muyun.spring.demo.quality.equipment.EquipmentCategoryService;
import net.ximatai.muyun.spring.platform.content.BusinessContentService;
import net.ximatai.muyun.spring.platform.web.ModuleExecutionPlanCatalog;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/** Actual Boot, metadata, schema, controllers and PostgreSQL; this does not replace live business acceptance. */
@Testcontainers
@SpringBootTest(classes = MuYunSpringApplication.class)
@ContextConfiguration(classes = QualityDemoConfiguration.class)
@ActiveProfiles("collaboration-demo")
class QualityCollaborationIT {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @Autowired WebApplicationContext web;
    @Autowired ObjectMapper mapper;
    @Autowired EquipmentCategoryService categories;
    @Autowired BusinessContentService content;
    @Autowired ModuleExecutionPlanCatalog plans;
    @Autowired QualityDemoBootstrapTask sample;
    @Autowired QualityDemoPageTask samplePage;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry values) {
        values.add("spring.datasource.url", postgres::getJdbcUrl);
        values.add("spring.datasource.username", postgres::getUsername);
        values.add("spring.datasource.password", postgres::getPassword);
        values.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
        values.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }

    @Test void sampleDeliversEnglishUiThroughExistingCodeAndConfigurationLocalization() throws Exception {
        var catalog = web.getBean(net.ximatai.muyun.spring.common.i18n.LanguageCatalog.class);
        var resources = net.ximatai.muyun.spring.common.i18n.TextResources.load(getClass().getClassLoader(), catalog);
        var missing = new java.util.LinkedHashMap<String, String>();
        for (var plan : plans.plans().values()) if (plan.moduleAlias().startsWith("quality.")) {
            for (var source : plan.uiDescriptor().sourceTexts().values()) {
                if (source.sourceKind() != net.ximatai.muyun.spring.common.i18n.TextReference.SourceKind.CODE) continue;
                var english = resources.resolve(source, Map.of(), "en");
                if (english.missing() || english.text().matches("(?s).*[\\p{IsHan}].*")) missing.put(source.sourceId(), source.originalText());
            }
        }
        assertThat(missing).isEmpty();
        try (var actor = CurrentUserContext.use(CurrentUser.systemUser("english-ui", "English UI"));
             var tenant = TenantContext.system("read sample English presentation")) {
            var mvc = webAppContextSetup(web).addFilters(web.getBean(net.ximatai.muyun.spring.web.LanguageWebFilter.class)).build();
            var response = mvc.perform(get("/platform.module/quality.exception/context").header("Accept-Language", "en"))
                    .andReturn().getResponse();
            assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
            var ui = mapper.readTree(response.getContentAsString()).path("uiDescriptor");
            var main = java.util.stream.StreamSupport.stream(ui.at("/page/detail/editor/fields").spliterator(), false)
                    .filter(field -> field.at("/fieldRef/fieldName").asText().equals("description")).findFirst().orElseThrow();
            assertThat(main.path("label").asText()).isEqualTo("Description");
            assertThat(ui.findValuesAsText("searchPlaceholder")).contains("Search exception title");
        }
    }

    @Test void restartingSampleKeepsFieldIdentitiesAndUserOwnedPresentationDrafts() {
        try (var actor = CurrentUserContext.use(CurrentUser.systemUser("sample-contract", "Sample Contract"));
             var tenant = TenantContext.system("verify sample restart")) {
            var fields = web.getBean(MetadataFieldService.class);
            var before = fields.list(Criteria.of(), PageRequest.of(1, 2000))
                    .stream().collect(java.util.stream.Collectors.toMap(field -> field.getId(), field -> field.getContentPolicy()));
            var revisions = web.getBean(PlatformPresentationRevisionService.class);
            var published = revisions.list(Criteria.of())
                    .stream().filter(row -> row.getStatus() == PlatformPresentationRevisionStatus.PUBLISHED)
                    .filter(row -> row.getUiTreeJson().contains("inspections")).findFirst().orElseThrow();
            var draft = new PlatformPresentationRevision();
            draft.setVariantId(published.getVariantId()); draft.setRevisionNo(published.getRevisionNo() + 1);
            draft.setTemplateAlias(published.getTemplateAlias()); draft.setTemplateVersion(published.getTemplateVersion());
            draft.setStatus(PlatformPresentationRevisionStatus.DRAFT);
            draft.setTitle("用户草稿"); draft.setUiTreeJson(published.getUiTreeJson().replace("异常单列表", "自定义列表"));
            String id = revisions.insert(draft);
            sample.run(); samplePage.run();
            assertThat(revisions.select(id).getUiTreeJson()).contains("自定义列表");
            assertThat(fields.list(Criteria.of(), PageRequest.of(1, 2000)))
                    .allSatisfy(field -> assertThat(field.getContentPolicy()).isEqualTo(before.get(field.getId())));
        }
    }

    @Test void existingTenantReceivesEnabledSampleMenusOnFirstInstallationAndEveryRestart() {
        try (var actor = CurrentUserContext.use(CurrentUser.systemUser("sample-menus", "Sample Menus"));
             var tenant = TenantContext.system("verify existing tenant sample installation")) {
            var tenants = web.getBean(TenantService.class);
            var existing = new Tenant();
            existing.setAlias("quality_existing"); existing.setTitle("Existing tenant"); existing.setEnabled(true);
            tenants.insert(existing);
            var menus = web.getBean(net.ximatai.muyun.spring.platform.menu.MenuService.class);
            String sourceId = "platform.menu.module.quality.exception";
            String copyId = net.ximatai.muyun.spring.platform.menu.DefaultTenantMenuProvisioner.tenantMenuId(existing.getAlias(), sourceId);
            // Reconstruct a tenant which predates this optional application's first installation.
            // Only fixture preparation uses the DAO; startup and menu copies use the real services.
            menus.getDao().deleteById(copyId);
            var startup = web.getBeansOfType(net.ximatai.muyun.spring.platform.runtime.PlatformBootstrapTask.class).values().stream()
                    .filter(task -> java.util.Set.of("platform.menu-contribution-reconciliation", "platform.tenant-menu-reconciliation",
                            "demo-quality-collaboration", "demo-quality-mount").contains(task.name()))
                    .sorted(java.util.Comparator.comparingInt(net.ximatai.muyun.spring.platform.runtime.PlatformBootstrapTask::order)).toList();
            for (int restart = 0; restart < 2; restart++) {
                startup.forEach(net.ximatai.muyun.spring.platform.runtime.PlatformBootstrapTask::run);
                try (var scoped = TenantContext.use(existing.getAlias())) {
                    assertThat(menus.select(copyId)).isNotNull().satisfies(menu -> assertThat(menu.getEnabled()).isTrue());
                }
            }
        }
    }

    @Test void sharedSampleSavesReopensAndRevisesStaticAndDynamicContentWithIndependentChildAuthors() throws Exception {
        MockMvc mvc = webAppContextSetup(web).build();
        try (var actor = CurrentUserContext.use(CurrentUser.systemUser("sample-setup", "Sample Setup"));
             var tenant = TenantContext.system("provision sample contract tenant")) {
            var tenants = web.getBean(TenantService.class);
            var record = new Tenant();
            record.setAlias("quality_contract_a"); record.setTitle("Quality contract tenant"); record.setEnabled(true);
            tenants.insert(record); tenants.provisionTenant(record.getAlias());
            web.getBean(TenantApplicationService.class)
                    .ensureApplicationsOpened(record.getAlias(), java.util.List.of("iam", "quality"));
        }
        try (var actor = CurrentUserContext.use(CurrentUser.systemUser("cn-author", "China Engineer"));
             var tenant = TenantContext.use("quality_contract_a")) {
            var category = write(mvc, "/quality.equipment_category/insert", Map.of(
                    "code", "CNC", "title", "数控设备", "parentId", "root", "remark", "英文界面仍保留中文备注",
                    "$contentLanguages", Map.of("title", Map.of("languageTag", "zh-CN"))));
            String categoryId = category.path("id").asText();
            assertThat(categoryId).isNotBlank();
            assertThat(read(mvc, "/quality.equipment_category/view/" + categoryId).path("remark").asText()).contains("中文备注");
            var provenance = read(mvc, "/quality.equipment_category/view/" + categoryId + "/content");
            assertThat(provenance.at("/fields/title/content/key").asText()).isEqualTo("equipment-category-name");
            assertThat(provenance.at("/fields/title/source/authorId").asText()).isEqualTo("cn-author");
            assertThat(provenance.at("/fields/title/source/sourceLanguage").asText()).isEqualTo("zh-CN");
            assertThat(provenance.path("fields").has("remark")).isFalse();

            var product = write(mvc, "/quality.product/insert", Map.of("code", "SEAL-1", "title", "密封组件",
                    "categoryId", categoryId, "$contentLanguages", Map.of("title", Map.of("languageTag", "zh-CN"))));
            String productId = product.path("id").asText();
            String original = "温度达到 80°C 时，12 件样品中有 2 件密封失效；暂勿发货。";
            var issue = write(mvc, "/quality.exception/insert", Map.of("title", "密封失效", "description", original,
                    "productId", productId, "$contentLanguages", Map.of("title", Map.of("languageTag", "zh-CN"),
                            "description", Map.of("languageTag", "zh-CN")),
                    "inspections", java.util.List.of(Map.of("description", "高温密封试验", "sampleQuantity", 12, "failedQuantity", 2,
                            "$contentLanguages", Map.of("description", Map.of("languageTag", "zh-CN"))))));
            String id = issue.path("id").asText();
            var reopened = read(mvc, "/quality.exception/view/" + id);
            assertThat(reopened.at("/values/description").asText()).isEqualTo(original);
            assertThat(reopened.at("/values/productId").asText()).isEqualTo(productId);
            assertThat(reopened.at("/children/inspections")).hasSize(1);
            String childId = reopened.at("/children/inspections/0/id").asText();
            var records = web.getBean(DynamicRecordService.class);
            assertThat(content.childSources(records.mainEntity(QualityDemoBootstrapTask.MODULE_ALIAS), id, "inspections", childId).fields())
                    .containsKey("description");
            var child = read(mvc, "/quality.exception/view/" + id + "/relations/inspections/" + childId + "/content");
            assertThat(child.at("/fields/description/source/authorId").asText()).as(child.toPrettyString()).isEqualTo("cn-author");
            assertThat(child.at("/fields/description/source/sourceLanguage").asText()).isEqualTo("zh-CN");
            assertThat(read(mvc, "/quality.exception/view/" + id + "/content").path("fields").has("internalNote")).isFalse();

            try (var american = CurrentUserContext.use(CurrentUser.systemUser("us-author", "US Buyer"))) {
                var updated = write(mvc, "/quality.exception/update/" + id, Map.of("version", reopened.path("version").asInt(),
                        "title", "密封失效", "description", original, "productId", productId,
                        "investigations", java.util.List.of(Map.of("description",
                                "Please keep this batch on hold until the replacement seals pass the retest.",
                                "$contentLanguages", Map.of("description", Map.of("languageTag", "en"))))));
                var current = read(mvc, "/quality.exception/view/" + id);
                String noteId = current.at("/children/investigations/0/id").asText();
                var note = read(mvc, "/quality.exception/view/" + id + "/relations/investigations/" + noteId + "/content");
                assertThat(note.at("/fields/description/source/sourceLanguage").asText()).isEqualTo("en");
                assertThat(note.at("/fields/description/source/authorId").asText()).isEqualTo("us-author");
                assertThat(current.at("/children/inspections")).hasSize(1);
                assertThat(updated.path("id").asText()).isEqualTo(id);
            }
            try (var german = CurrentUserContext.use(CurrentUser.systemUser("de-author", "German Quality"))) {
                var before = read(mvc, "/quality.exception/view/" + id);
                var prior = before.at("/children/investigations/0");
                write(mvc, "/quality.exception/update/" + id, Map.of("version", before.path("version").asInt(),
                        "title", "密封失效", "description", original, "productId", productId,
                        "investigations", java.util.List.of(
                                Map.of("id", prior.path("id").asText(), "version", prior.path("version").asInt(),
                                        "description", prior.at("/values/description").asText()),
                                Map.of("description", "Bitte die Dichtungen vor dem Versand erneut prüfen.",
                                        "$contentLanguages", Map.of("description", Map.of("languageTag", "de"))))));
                var after = read(mvc, "/quality.exception/view/" + id);
                assertThat(after.at("/children/investigations")).hasSize(2);
                var note = java.util.stream.StreamSupport.stream(after.at("/children/investigations").spliterator(), false)
                        .filter(row -> row.at("/values/description").asText().startsWith("Bitte")).findFirst().orElseThrow();
                var germanSource = read(mvc, "/quality.exception/view/" + id + "/relations/investigations/" + note.path("id").asText() + "/content");
                assertThat(germanSource.at("/fields/description/source/sourceLanguage").asText()).isEqualTo("de");
                assertThat(germanSource.at("/fields/description/source/authorId").asText()).isEqualTo("de-author");
            }
            var current = read(mvc, "/quality.exception/view/" + id);
            write(mvc, "/quality.exception/update/" + id, Map.of("version", current.path("version").asInt(),
                    "title", "密封失效", "description", original.replace("80°C", "60°C"), "productId", productId,
                    "$contentLanguages", Map.of("description", Map.of("languageTag", "zh-CN", "expectedRevision", 1))));
            var history = read(mvc, "/quality.exception/view/" + id + "/content/description/history");
            assertThat(history).hasSize(2);
            assertThat(history.get(0).path("text").asText()).contains("60°C");
            assertThat(history.get(1).path("text").asText()).contains("80°C");
            try (var otherTenant = TenantContext.use("quality_contract_b")) {
                assertThatThrownBy(() -> content.sources(categories, categoryId))
                        .isInstanceOf(PlatformAccessDeniedException.class);
            }
        }
    }

    private JsonNode write(MockMvc mvc, String path, Map<String, ?> values) throws Exception {
        var response = mvc.perform(post(path).contentType("application/json").content(mapper.writeValueAsString(values)))
                .andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isBetween(200, 201);
        var result = mapper.readTree(response.getContentAsString());
        return result.has("data") ? result.path("data") : result;
    }
    private JsonNode read(MockMvc mvc, String path) throws Exception {
        var response = mvc.perform(get(path)).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return mapper.readTree(response.getContentAsString());
    }
}
