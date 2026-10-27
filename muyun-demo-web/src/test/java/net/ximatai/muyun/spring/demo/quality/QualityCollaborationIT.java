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
import org.springframework.test.annotation.DirtiesContext;
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
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
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

    @Test void sampleMenusDeliverEnglishForGlobalNavigationAndNewTenantCopies() throws Exception {
        var expected = Map.of(QualityDemoMenuDeclarations.ROOT, "Quality collaboration",
                "platform.menu.module.quality.equipment_category", "Equipment categories",
                "platform.menu.module.quality.product", "Product catalog",
                "platform.menu.module.quality.exception", "Quality exceptions");
        try (var actor = CurrentUserContext.use(CurrentUser.systemUser("english-menu", "English Menu"));
             var tenant = TenantContext.system("verify sample navigation translations")) {
            var mvc = webAppContextSetup(web).addFilters(web.getBean(net.ximatai.muyun.spring.web.LanguageWebFilter.class)).build();
            var response = mvc.perform(get("/platform.menu/mine").header("Accept-Language", "en")).andReturn().getResponse();
            assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
            var records = mapper.readTree(response.getContentAsString()).findValues("record");
            var titles = records.stream().collect(java.util.stream.Collectors.toMap(
                    row -> row.path("id").asText(), row -> row.path("title").asText()));
            assertThat(titles).containsAllEntriesOf(expected);

            var created = new Tenant(); created.setAlias("quality_english_menus");
            created.setTitle("English sample navigation"); created.setEnabled(true);
            web.getBean(TenantService.class).insert(created);
            var menus = web.getBean(net.ximatai.muyun.spring.platform.menu.MenuService.class);
            var texts = web.getBean(net.ximatai.muyun.spring.common.i18n.TextResolver.class);
            try (var scope = TenantContext.use(created.getAlias())) {
                expected.forEach((id, english) -> {
                    var copy = menus.select(net.ximatai.muyun.spring.platform.menu.DefaultTenantMenuProvisioner
                            .tenantMenuId(created.getAlias(), id));
                    assertThat(copy).isNotNull();
                    var source = net.ximatai.muyun.spring.platform.localization.ConfigurationTextSources
                            .menuTitle(copy.getId(), copy.getTitle());
                    assertThat(texts.resolve(source, Map.of(), "en").text()).isEqualTo(english);
                    assertThat(texts.resolve(source, Map.of(), "zh-CN").text()).isEqualTo(copy.getTitle());
                });
            }
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
            var privateField = fields.list(Criteria.of().eq("fieldName", "internalNote"), PageRequest.of(1, 1)).getFirst();
            var protections = web.getBean(net.ximatai.muyun.spring.platform.metadata.MetadataFieldProtectionConfigService.class);
            var protection = protections.findByMetadataFieldId(privateField.getId());
            // Reconstruct the initial sample's unprotected declaration, then exercise its real upgrade.
            web.getBean(org.springframework.transaction.support.TransactionTemplate.class).executeWithoutResult(status -> {
                protection.setEnabled(false);
                protections.update(protection);
                privateField.setContentPolicy(net.ximatai.muyun.spring.common.content.ContentPolicy.AUTO_TRANSLATE);
                publishField(privateField);
            });
            sample.run(); samplePage.run();
            assertThat(revisions.select(id).getUiTreeJson()).contains("自定义列表");
            before.forEach((fieldId, policy) -> assertThat(fields.select(fieldId)).isNotNull()
                    .satisfies(field -> assertThat(field.getContentPolicy()).isEqualTo(policy)));
            var upgraded = protections.findByMetadataFieldId(privateField.getId());
            assertThat(upgraded.getId()).isEqualTo(protection.getId());
            assertThat(upgraded.getEnabled()).isTrue();
            assertThat(upgraded.getMaskingPolicy()).isEqualTo(net.ximatai.muyun.spring.common.security.FieldMaskingPolicy.FULL);
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
            String categoryTranslations = "/quality.equipment_category/view/" + categoryId + "/content/title/translations/en/";
            var maintenance = write(mvc, categoryTranslations + "prepare", Map.of());
            var draft = write(mvc, categoryTranslations + "draft", Map.of(
                    "sourceRevisionId", maintenance.at("/original/revisionId").asText(),
                    "sourceRevision", maintenance.at("/original/revision").asInt(), "text", "CNC Equipment"));
            assertThat(read(mvc, "/quality.equipment_category/view/" + categoryId + "/content/reading?language=en")
                    .at("/fields/title/text").asText()).isEqualTo("数控设备");
            assertThat(write(mvc, "/quality.equipment_category/query", Map.of("quickSearch", "CNC Equipment"))
                    .path("total").asLong()).isZero();
            write(mvc, categoryTranslations + "confirm", Map.of(
                    "sourceRevisionId", maintenance.at("/original/revisionId").asText(),
                    "sourceRevision", maintenance.at("/original/revision").asInt(), "expectedRevision", draft.path("expectedRevision").asInt(),
                    "reviewedDraftRevision", draft.at("/draft/revision").asInt()));
            var englishCategory = read(mvc, "/quality.equipment_category/view/" + categoryId + "/content/reading?language=en");
            assertThat(englishCategory.at("/fields/title/text").asText()).isEqualTo("CNC Equipment");
            assertThat(englishCategory.at("/fields/title/originalText").asText()).isEqualTo("数控设备");
            assertThat(read(mvc, "/quality.equipment_category/view/" + categoryId).path("title").asText()).isEqualTo("数控设备");

            var categoryMatches = write(mvc, "/quality.equipment_category/query", Map.of(
                    "quickSearch", "CNC Equipment", "page", Map.of("pageNum", 1, "pageSize", 1)));
            assertThat(categoryMatches.path("total").asLong()).isEqualTo(1);
            assertThat(categoryMatches.at("/records/0/id").asText()).isEqualTo(categoryId);
            assertThat(categoryMatches.at("/records/0/title").asText()).isEqualTo("数控设备");

            var product = write(mvc, "/quality.product/insert", Map.of("code", "SEAL-1", "title", "密封组件",
                    "categoryId", categoryId, "$contentLanguages", Map.of("title", Map.of("languageTag", "zh-CN"))));
            String productId = product.path("id").asText();
            confirmTranslation(mvc, "/quality.product/view/" + productId + "/content/title/translations/en/", "Seal component");
            String original = "温度达到 80°C 时，12 件样品中有 2 件密封失效；暂勿发货。";
            String privateNote = "仅供内部核实的供应商批次说明，不得进入阅读译文。";
            var issue = write(mvc, "/quality.exception/insert", Map.of("title", "密封失效", "description", original,
                    "internalNote", privateNote,
                    "productId", productId, "$contentLanguages", Map.of("title", Map.of("languageTag", "zh-CN"),
                            "description", Map.of("languageTag", "zh-CN")),
                    "inspections", java.util.List.of(Map.of("description", "高温密封试验", "sampleQuantity", 12, "failedQuantity", 2,
                            "$contentLanguages", Map.of("description", Map.of("languageTag", "zh-CN"))))));
            String id = issue.path("id").asText();
            confirmTranslation(mvc, "/quality.exception/view/" + id + "/content/title/translations/en/", "Seal failure");
            confirmTranslation(mvc, "/quality.exception/view/" + id + "/content/description/translations/de/", "Dichtung bei 80°C defekt");
            var dynamicMatches = write(mvc, "/quality.exception/query", Map.of(
                    "criteria", Map.of("kind", "GROUP", "operator", "OR", "children", java.util.List.of(
                            Map.of("kind", "CONDITION", "fieldName", "title", "operator", "EQ", "values", java.util.List.of("Seal failure")),
                            Map.of("kind", "CONDITION", "fieldName", "description", "operator", "EQ", "values", java.util.List.of("Dichtung bei 80°C defekt")))),
                    "page", Map.of("pageNum", 1, "pageSize", 1)));
            assertThat(dynamicMatches.path("total").asLong()).isEqualTo(1);
            assertThat(dynamicMatches.at("/records/0/id").asText()).isEqualTo(id);
            assertThat(dynamicMatches.at("/records/0/values/title").asText()).isEqualTo("密封失效");
            var productReading = presentationRead(mvc, "/quality.product/view/" + productId);
            assertThat(productReading.path("categoryTitle").asText()).isEqualTo("数控设备");
            assertThat(productReading.at("/$contentTexts/categoryTitle/readings/en").asText()).isEqualTo("CNC Equipment");
            var issueReading = presentationRead(mvc, "/quality.exception/view/" + id);
            assertThat(issueReading.at("/values/productTitle").asText()).isEqualTo("密封组件");
            assertThat(issueReading.at("/$contentTexts/productTitle/readings/en").asText()).isEqualTo("Seal component");
            assertThat(issueReading.at("/$contentTexts/productIdTitle/readings/en").asText()).isEqualTo("Seal component");
            for (String[] source : java.util.List.of(new String[] {"/platform.module/quality.product/references/categoryId/resolve", "CNC Equipment", categoryId, "数控设备"},
                    new String[] {"/quality.exception/references/productId/resolve", "Seal component", productId, "密封组件"})) {
                var candidates = presentationQuery(mvc, source[0], Map.of("mode", "QUERY", "fuzzy", source[1]));
                assertThat(candidates.path("total").asLong()).isEqualTo(1);
                assertThat(candidates.at("/options/0/id").asText()).isEqualTo(source[2]);
                assertThat(candidates.at("/options/0/subtitle").asText()).isEqualTo(source[0].contains("categoryId") ? "CNC" : "SEAL-1");
                assertThat(candidates.at("/options/0/title").asText()).isEqualTo(source[3]);
                assertThat(candidates.at("/options/0/titleContent/readings/en").asText()).isEqualTo(source[1]);
                var restored = presentationQuery(mvc, source[0], Map.of("mode", "TRANSLATE", "values", java.util.List.of(source[2])));
                assertThat(restored.at("/results/0/item/id").asText()).isEqualTo(source[2]);
                assertThat(restored.at("/results/0/item/subtitle").asText()).isEqualTo(source[0].contains("categoryId") ? "CNC" : "SEAL-1");
                assertThat(restored.at("/results/0/item/titleContent/readings/en").asText()).isEqualTo(source[1]);
            }
            for (String[] target : java.util.List.of(new String[] {"quality.equipment_category", "CNC Equipment", categoryId},
                    new String[] {"quality.product", "Seal component", productId})) {
                var navigator = presentationQuery(mvc, "/" + target[0] + "/navigator/reference/query", Map.of("quickSearch", target[1]));
                assertThat(navigator.path("total").asLong()).isEqualTo(1);
                assertThat(navigator.at("/records/0/id").asText()).isEqualTo(target[2]);
                assertThat(navigator.at("/records/0/$contentTexts/title/readings/en").asText()).isEqualTo(target[1]);
            }
            var categoryTree = presentationQuery(mvc, "/quality.equipment_category/navigator/reference/tree/query", Map.of());
            assertThat(categoryTree.at("/records/0/record/id").asText()).isEqualTo(categoryId);
            assertThat(categoryTree.at("/records/0/record/$contentTexts/title/readings/en").asText()).isEqualTo("CNC Equipment");
            assertThat(issue.toString()).doesNotContain(privateNote);
            var reopened = read(mvc, "/quality.exception/view/" + id);
            assertThat(reopened.at("/values/internalNote").asText()).matches("\\*+");
            assertThat(reopened.toString()).doesNotContain(privateNote);
            assertThat(storedValue("select internal_note from quality_exception where id = ?", id))
                    .isEqualTo(privateNote);
            assertThat(storedValue("select count(*) from platform_business_content_revision where record_id = ? and original_text = ?",
                    id, privateNote)).isEqualTo(0L);
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
            String childTranslations = "/quality.exception/view/" + id + "/relations/inspections/" + childId + "/content/description/translations/de/";
            var childMaintenance = write(mvc, childTranslations + "prepare", Map.of());
            var childDraft = write(mvc, childTranslations + "draft", Map.of(
                    "sourceRevisionId", childMaintenance.at("/original/revisionId").asText(),
                    "sourceRevision", childMaintenance.at("/original/revision").asInt(), "text", "Dichtheitsprüfung bei hoher Temperatur"));
            write(mvc, childTranslations + "confirm", Map.of(
                    "sourceRevisionId", childMaintenance.at("/original/revisionId").asText(),
                    "sourceRevision", childMaintenance.at("/original/revision").asInt(), "expectedRevision", childDraft.path("expectedRevision").asInt(),
                    "reviewedDraftRevision", childDraft.at("/draft/revision").asInt()));
            assertThat(read(mvc, "/quality.exception/view/" + id + "/relations/inspections/" + childId + "/content/reading?language=de")
                    .at("/fields/description/text").asText()).isEqualTo("Dichtheitsprüfung bei hoher Temperatur");
            var privateTranslation = mvc.perform(post("/quality.exception/view/" + id + "/content/internalNote/translations/en/prepare")
                    .contentType("application/json").content("{}")).andReturn().getResponse();
            assertThat(privateTranslation.getStatus()).isBetween(400, 499);
            assertThat(privateTranslation.getContentAsString()).doesNotContain(privateNote);
            assertThat(read(mvc, "/quality.exception/view/" + id + "/content").path("fields").has("internalNote")).isFalse();
            var privateHistory = mvc.perform(get("/quality.exception/view/" + id + "/content/internalNote/history"))
                    .andReturn().getResponse();
            assertThat(privateHistory.getStatus()).isBetween(400, 499);
            assertThat(privateHistory.getContentAsString()).doesNotContain(privateNote);

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
            assertThat(storedValue("select internal_note from quality_exception where id = ?", id))
                    .as("unrelated aggregate edits preserve the private original").isEqualTo(privateNote);
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

    private Object storedValue(String sql, String... arguments) throws java.sql.SQLException {
        try (var connection = web.getBean(javax.sql.DataSource.class).getConnection();
             var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) statement.setString(index + 1, arguments[index]);
            try (var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getObject(1);
            }
        }
    }

    @Test void serverPagesByCurrentReadingAndReportsUntranslatedRowsOutsideTheTextMatches() throws Exception {
        MockMvc mvc = webAppContextSetup(web).build();
        try (var actor = CurrentUserContext.use(CurrentUser.systemUser("ordering-setup", "Ordering Setup"));
             var tenant = TenantContext.system("provision reading order contract tenant")) {
            var tenants = web.getBean(TenantService.class);
            for (String alias : java.util.List.of("quality_reading_order", "quality_order_other_tenant")) {
                var value = new Tenant(); value.setAlias(alias); value.setTitle("Reading order"); value.setEnabled(true);
                tenants.insert(value); tenants.provisionTenant(value.getAlias());
                web.getBean(TenantApplicationService.class).ensureApplicationsOpened(alias, java.util.List.of("iam", "quality"));
            }
        }
        try (var actor = CurrentUserContext.use(CurrentUser.systemUser("ordering-author", "Ordering Author"));
             var tenant = TenantContext.use("quality_reading_order")) {
            var category = write(mvc, "/quality.equipment_category/insert", Map.of("code", "ORDER-CATEGORY", "title", "设备", "parentId", "root",
                    "$contentLanguages", Map.of("title", Map.of("languageTag", "zh-CN"))));
            String[] raw = {"设备一", "设备二", "设备三"};
            String[] english = {"Zulu equipment", "Alpha equipment"};
            var products = new java.util.ArrayList<String>();
            var issues = new java.util.ArrayList<String>();
            for (int index = 0; index < raw.length; index++) {
                String product = write(mvc, "/quality.product/insert", Map.of("code", "ORDER-" + index, "title", raw[index],
                        "categoryId", category.path("id").asText(), "$contentLanguages", Map.of("title", Map.of("languageTag", "zh-CN"))))
                        .path("id").asText();
                products.add(product);
                String issue = write(mvc, "/quality.exception/insert", Map.of("title", raw[index], "description", "Original details", "productId", product,
                        "$contentLanguages", Map.of("title", Map.of("languageTag", "zh-CN"), "description", Map.of("languageTag", "en"))))
                        .path("id").asText();
                issues.add(issue);
                if (index < english.length) {
                    confirmTranslation(mvc, "/quality.product/view/" + product + "/content/title/translations/en/", english[index]);
                    confirmTranslation(mvc, "/quality.exception/view/" + issue + "/content/title/translations/en/", english[index]);
                }
            }
            for (String module : java.util.List.of("quality.product", "quality.exception")) {
                var expected = module.endsWith("product") ? products : issues;
                var first = readingQuery(mvc, module, Map.of("page", Map.of("pageNum", 1, "pageSize", 1),
                        "sorts", java.util.List.of(Map.of("field", "title", "desc", false))));
                var second = readingQuery(mvc, module, Map.of("page", Map.of("pageNum", 2, "pageSize", 1),
                        "sorts", java.util.List.of(Map.of("field", "title", "desc", false))));
                assertThat(first.path("total").asLong()).isEqualTo(3);
                assertThat(second.path("total").asLong()).isEqualTo(3);
                assertThat(first.at("/records/0/id").asText()).isEqualTo(expected.get(1));
                assertThat(second.at("/records/0/id").asText()).isEqualTo(expected.get(0));
                assertThat(first.at(module.endsWith("product") ? "/records/0/title" : "/records/0/values/title").asText()).isEqualTo(raw[1]);
                assertThat(first.at("/businessContent/language").asText()).isEqualTo("en");
                assertThat(first.at("/businessContent/missingRecords").asLong()).isEqualTo(1);
                assertThat(first.at("/businessContent/readings/" + expected.get(1) + "/title").asText()).isEqualTo(english[1]);
                var filtered = readingQuery(mvc, module, Map.of("quickSearch", "Alpha equipment"));
                assertThat(filtered.path("total").asLong()).isEqualTo(1);
                assertThat(filtered.at("/businessContent/missingRecords").asLong()).isEqualTo(1);
                assertThat(filtered.toString()).doesNotContain("__content_order_", "reading_text");
                var rawQuery = write(mvc, "/" + module + "/query", Map.of());
                assertThat(rawQuery.has("businessContent")).isFalse();
                confirmTranslation(mvc, "/" + module + "/view/" + expected.getFirst() + "/content/title/translations/en/", english[1]);
                var tiedIds = java.util.List.of(expected.get(0), expected.get(1)).stream().sorted().toList();
                for (int page = 1; page <= 2; page++) {
                    var tied = readingQuery(mvc, module, Map.of("page", Map.of("pageNum", page, "pageSize", 1),
                            "sorts", java.util.List.of(Map.of("field", "title", "desc", false))));
                    assertThat(tied.at("/records/0/id").asText()).isEqualTo(tiedIds.get(page - 1));
                }
                var descending = readingQuery(mvc, module, Map.of("page", Map.of("pageNum", 1, "pageSize", 1),
                        "sorts", java.util.List.of(Map.of("field", "title", "desc", true))));
                assertThat(descending.at("/records/0/id").asText()).isEqualTo(expected.get(2));
                try (var other = TenantContext.use("quality_order_other_tenant")) {
                    var invisible = readingQuery(mvc, module, Map.of("sorts", java.util.List.of(Map.of("field", "title", "desc", false))));
                    assertThat(invisible.path("total").asLong()).isZero();
                    assertThat(invisible.at("/businessContent/missingRecords").asLong()).isZero();
                    assertThat(invisible.at("/businessContent/readings").isEmpty()).isTrue();
                }
            }
        }
    }

    @Test void exchangeUpdatesDynamicChildrenThroughTheirOwningAggregate() throws Exception {
        var mvc = webAppContextSetup(web).build();
        try (var actor = CurrentUserContext.use(CurrentUser.systemUser("exchange-child", "Exchange Child"));
             var tenant = TenantContext.system("configure exchange aggregate fixture")) {
            var tenants = web.getBean(TenantService.class);
            var value = new Tenant(); value.setAlias("quality_exchange_child"); value.setTitle("Exchange aggregate"); value.setEnabled(true);
            tenants.insert(value); tenants.provisionTenant(value.getAlias());
            web.getBean(TenantApplicationService.class).ensureApplicationsOpened(value.getAlias(), java.util.List.of("iam", "quality"));
        }
        try (var actor = CurrentUserContext.use(CurrentUser.systemUser("exchange-child", "Exchange Child"));
             var tenant = TenantContext.use("quality_exchange_child")) {
            String category = write(mvc, "/quality.equipment_category/insert", Map.of("code", "EX-CHILD-C", "title", "分类", "parentId", "root")).path("id").asText();
            String product = write(mvc, "/quality.product/insert", Map.of("code", "EX-CHILD-P", "title", "设备", "categoryId", category)).path("id").asText();
            String id = write(mvc, "/quality.exception/insert", Map.of("title", "聚合文件", "description", "父原文唯一标识", "productId", product,
                    "$contentLanguages", Map.of("title", Map.of("languageTag", "zh-CN"), "description", Map.of("languageTag", "zh-CN")),
                    "children", Map.of("inspections", java.util.List.of(Map.of("description", "明细原文唯一标识", "sampleQuantity", 12, "failedQuantity", 2,
                            "$contentLanguages", Map.of("description", Map.of("languageTag", "zh-CN")))),
                            "investigations", java.util.List.of(Map.of("description", "只读关联调查",
                                    "$contentLanguages", Map.of("description", Map.of("languageTag", "zh-CN"))))))).path("id").asText();
            var exported = mvc.perform(post("/quality.exception/exchange/files/export").contentType("application/json")
                    .content(mapper.writeValueAsBytes(Map.of("ids", java.util.List.of(id), "options", Map.of("purpose", "DATA", "language", "en")))))
                    .andReturn().getResponse();
            assertThat(exported.getStatus()).isEqualTo(200);
            var output = new java.io.ByteArrayOutputStream();
            try (var in = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(exported.getContentAsByteArray()));
                 var out = new java.util.zip.ZipOutputStream(output)) {
                java.util.zip.ZipEntry entry;
                while ((entry = in.getNextEntry()) != null) {
                    byte[] bytes = in.readAllBytes();
                    if (entry.getName().equals("xl/sharedStrings.xml")) bytes = new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
                            .replace("父原文唯一标识", "New parent original").replace("明细原文唯一标识", "New child original")
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    out.putNextEntry(new java.util.zip.ZipEntry(entry.getName())); out.write(bytes); out.closeEntry();
                }
            }
            byte[] edited = output.toByteArray();
            var command = new java.util.LinkedHashMap<String, Object>();
            command.put("sourceLanguage", "en");
            command.put("sheets", java.util.List.of(Map.of("entityAlias", "quality_exception", "matchField", "title", "duplicateStrategy", "ERROR"),
                    Map.of("entityAlias", "quality_inspection", "matchField", "description", "duplicateStrategy", "ERROR"),
                    Map.of("entityAlias", "quality_investigation", "matchField", "description", "duplicateStrategy", "ERROR")));
            var preview = exchangeMultipart(mvc, "preview", edited, command);
            command.put("documentId", preview.path("documentId").asText()); command.put("previewDigest", preview.path("previewDigest").asText());
            var imported = exchangeMultipart(mvc, "import", edited, command);
            assertThat(imported.path("errorCount").asInt()).isZero();
            assertThat(imported.path("updated").asInt()).isEqualTo(2);
            var current = read(mvc, "/quality.exception/view/" + id);
            assertThat(current.at("/values/description").asText()).isEqualTo("New parent original");
            var children = web.getBean(DynamicRecordService.class).aggregateChildrenForView("quality.exception", id, "inspections");
            assertThat(children).hasSize(1);
            assertThat(children.getFirst().getValue("description")).isEqualTo("New child original");
            var contexts = web.getBean(DynamicRecordService.class).aggregateChildrenForView("quality.exception", id, "investigations");
            assertThat(contexts).hasSize(1);
            assertThat(contexts.getFirst().getVersion()).isZero();
            assertThat(contexts.getFirst().getValue("description")).isEqualTo("只读关联调查");
        }
    }

    private JsonNode exchangeMultipart(MockMvc mvc, String operation, byte[] bytes, Map<String, Object> command) throws Exception {
        var result = mvc.perform(multipart("/quality.exception/exchange/files/" + operation)
                .file(new org.springframework.mock.web.MockMultipartFile("file", "input.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes))
                .file(new org.springframework.mock.web.MockMultipartFile("command", "", "application/json", mapper.writeValueAsBytes(command))))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("%s", result.getResolvedException()).isEqualTo(200);
        var body = mapper.readTree(result.getResponse().getContentAsByteArray());
        return body.has("data") ? body.path("data") : body;
    }

    @Test void exchangeReferenceNamesFollowTheFileLanguageWithoutBrowserReadingHeaders() throws Exception {
        var mvc = webAppContextSetup(web).build();
        try (var actor = CurrentUserContext.use(CurrentUser.systemUser("exchange-contract", "Exchange Contract"));
             var tenant = TenantContext.system("configure exchange name fixture")) {
            var tenants = web.getBean(TenantService.class);
            var value = new Tenant(); value.setAlias("quality_exchange_name"); value.setTitle("Exchange names"); value.setEnabled(true);
            tenants.insert(value); tenants.provisionTenant(value.getAlias());
            web.getBean(TenantApplicationService.class).ensureApplicationsOpened(value.getAlias(),java.util.List.of("iam","quality"));
        }
        try (var actor = CurrentUserContext.use(CurrentUser.systemUser("exchange-contract", "Exchange Contract"));
             var tenant = TenantContext.use("quality_exchange_name")) {
            String category = write(mvc,"/quality.equipment_category/insert",Map.of("code","FILE-NAME","title","原始分类","parentId","root",
                    "$contentLanguages",Map.of("title",Map.of("languageTag","zh-CN")))).path("id").asText();
            confirmTranslation(mvc,"/quality.equipment_category/view/"+category+"/content/title/translations/en/","File category");
            String product = write(mvc,"/quality.product/insert",Map.of("code","FILE-PRODUCT","title","原始商品","categoryId",category,
                    "$contentLanguages",Map.of("title",Map.of("languageTag","zh-CN")))).path("id").asText();
            for (boolean browserLanguage : java.util.List.of(false,true)) for (boolean queryExport : java.util.List.of(false,true)) {
                var request = post("/quality.product/exchange/files/export").contentType("application/json")
                        .content(mapper.writeValueAsBytes(queryExport ? Map.of("query",Map.of("conditions",java.util.List.of(
                                Map.of("fieldName","id","operator","eq","values",java.util.List.of(product)))),"options",Map.of("purpose","DATA","language","en"))
                                : Map.of("ids",java.util.List.of(product),"options",Map.of("purpose","DATA","language","en"))));
                if (browserLanguage) request.header(net.ximatai.muyun.spring.web.BusinessContentQueryWebContext.LANGUAGES_HEADER,"de");
                var response = mvc.perform(request).andReturn().getResponse();
                assertThat(response.getStatus()).isEqualTo(200);
                String strings = "";
                try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(response.getContentAsByteArray()))) {
                    java.util.zip.ZipEntry entry;
                    while ((entry = zip.getNextEntry()) != null) if (entry.getName().equals("xl/sharedStrings.xml")) {
                        strings = new String(zip.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8); break;
                    }
                }
                assertThat(strings).contains(">File category<");
            }
        }
    }

    @Test void readingProjectionBindsTheAuthorizedTenantIncarnationAndVersionFromTheSameRead() throws Exception {
        var mvc = webAppContextSetup(web).build();
        try (var actor = CurrentUserContext.use(CurrentUser.systemUser("snapshot-contract", "Snapshot Contract"));
             var tenant = TenantContext.system("verify authorized reading identity")) {
            var tenants = web.getBean(TenantService.class);
            var value = new Tenant(); value.setAlias("quality_read_snapshot"); value.setTitle("Read snapshot"); value.setEnabled(true);
            tenants.insert(value); tenants.provisionTenant(value.getAlias());
            web.getBean(TenantApplicationService.class).ensureApplicationsOpened(value.getAlias(), java.util.List.of("iam", "quality"));
        }
        try (var actor = CurrentUserContext.use(CurrentUser.systemUser("snapshot-contract", "Snapshot Contract"));
             var tenant = TenantContext.use("quality_read_snapshot")) {
            String id = write(mvc, "/quality.equipment_category/insert", Map.of("code", "READ-SNAPSHOT", "title", "身份设备", "parentId", "root",
                    "$contentLanguages", Map.of("title", Map.of("languageTag", "zh-CN")))).path("id").asText();
            confirmTranslation(mvc, "/quality.equipment_category/view/" + id + "/content/title/translations/en/", "Authorized equipment");
            var row = categories.select(id);
            var reading = web.getBean(net.ximatai.muyun.spring.platform.web.BusinessContentReadingProjectionService.class);
            var request = new org.springframework.mock.web.MockHttpServletRequest();
            var attributes = new org.springframework.web.context.request.ServletRequestAttributes(request);
            var previous = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
            org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(attributes);
            try {
                var output = java.util.List.of(Map.of("id", id, "title", row.getTitle()));
                assertThat(reading.readings(categories, output, java.util.Set.of("title"), "en")).isEmpty();
                reading.capture(categories, java.util.List.of(row));
                assertThat(reading.readings(categories, output, java.util.Set.of("title"), "en"))
                        .containsEntry(id, Map.of("title", "Authorized equipment"));
                executeSql("update quality_equipment_category set updated_at = updated_at + interval '1 second' where id = ?", id);
                assertThat(reading.readings(categories, output, java.util.Set.of("title"), "en")).isEmpty();
                executeSql("update quality_equipment_category set updated_at = updated_at - interval '1 second' where id = ?", id);
                executeSql("update quality_equipment_category set version = version + 1 where id = ?", id);
                assertThat(reading.readings(categories, output, java.util.Set.of("title"), "en")).isEmpty();
                executeSql("update quality_equipment_category set version = version - 1, created_at = created_at + interval '1 second' where id = ?", id);
                assertThat(reading.readings(categories, output, java.util.Set.of("title"), "en")).isEmpty();
                executeSql("update quality_equipment_category set created_at = created_at - interval '1 second', tenant_id = 'replacement-tenant' where id = ?", id);
                executeSql("update platform_business_content_revision set tenant_id = 'replacement-tenant' where record_id = ?", id);
                executeSql("update platform_business_content_translation set tenant_id = 'replacement-tenant' where record_id = ?", id);
                assertThat(reading.readings(categories, output, java.util.Set.of("title"), "en")).isEmpty();
                // The replacement really has a current translated slot; matching raw text alone would leak it.
                assertThat(storedValue("select t.translated_text from platform_business_content_translation t join quality_equipment_category b on b.id = t.record_id and b.tenant_id = t.tenant_id where t.kind = 'CONFIRMED' and t.record_id = ?", id))
                        .isEqualTo("Authorized equipment");
            } finally {
                org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(previous);
                executeSql("update quality_equipment_category set tenant_id = ?, created_at = ?, updated_at = ?, version = ? where id = ?",
                        row.getTenantId(), java.sql.Timestamp.from(row.getCreatedAt()), java.sql.Timestamp.from(row.getUpdatedAt()), row.getVersion(), id);
                executeSql("update platform_business_content_revision set tenant_id = ? where record_id = ?", row.getTenantId(), id);
                executeSql("update platform_business_content_translation set tenant_id = ? where record_id = ?", row.getTenantId(), id);
            }
        }
    }

    private void executeSql(String sql, Object... arguments) throws java.sql.SQLException {
        try (var connection = web.getBean(javax.sql.DataSource.class).getConnection();
             var statement = connection.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) statement.setObject(index + 1, arguments[index]);
            statement.executeUpdate();
        }
    }

    private JsonNode presentationQuery(MockMvc mvc, String path, Map<String, ?> values) throws Exception {
        var response = mvc.perform(post(path).header("X-MuYun-Content-Languages", "zh-CN,en,de")
                .contentType("application/json").content(mapper.writeValueAsString(values))).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return mapper.readTree(response.getContentAsString());
    }

    private JsonNode presentationRead(MockMvc mvc, String path) throws Exception {
        var response = mvc.perform(get(path).header("X-MuYun-Content-Languages", "zh-CN,en,de")).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return mapper.readTree(response.getContentAsString());
    }

    private JsonNode readingQuery(MockMvc mvc, String module, Map<String, ?> values) throws Exception {
        var response = mvc.perform(post("/" + module + "/query").header("X-MuYun-Content-Language", "en")
                .contentType("application/json").content(mapper.writeValueAsString(values))).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);
        return mapper.readTree(response.getContentAsString());
    }

    private void publishField(net.ximatai.muyun.spring.platform.metadata.MetadataField field) {
        var relations = web.getBean(net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationService.class);
        var owner = relations.list(Criteria.of().eq("metadataId", field.getMetadataId())
                .eq("relationRole", net.ximatai.muyun.spring.platform.metadata.RelationRole.MAIN)).getFirst();
        var entity = web.getBean(net.ximatai.muyun.spring.platform.metadata.MetadataService.class).select(field.getMetadataId());
        var proposal = new net.ximatai.muyun.spring.platform.metadata.MetadataRelationChangeSetPreviewCommand(
                entity.getVersion(), Map.of(), java.util.List.of(
                    new net.ximatai.muyun.spring.platform.metadata.MetadataFieldChangeSetDraft(
                        net.ximatai.muyun.spring.platform.metadata.MetadataFieldChangeSetDraft.Operation.UPDATE,
                        field.getId(), field.getVersion(), field)));
        var preview = web.getBean(net.ximatai.muyun.spring.platform.metadata.MetadataRelationChangeSetPreviewService.class)
                .preview(owner.getModuleAlias(), owner.getId(), proposal);
        assertThat(preview.errors()).isEmpty();
        web.getBean(net.ximatai.muyun.spring.platform.metadata.MetadataRelationChangeSetApplyService.class).apply(
                owner.getModuleAlias(), owner.getId(),
                new net.ximatai.muyun.spring.platform.metadata.MetadataRelationChangeSetApplyCommand(proposal, preview.proposalFingerprint()));
    }

    private void confirmTranslation(MockMvc mvc, String path, String text) throws Exception {
        var opened = write(mvc, path + "prepare", Map.of());
        var input = new java.util.LinkedHashMap<String, Object>(Map.of(
                "sourceRevisionId", opened.at("/original/revisionId").asText(),
                "sourceRevision", opened.at("/original/revision").asInt(), "text", text));
        if (opened.hasNonNull("expectedRevision")) input.put("expectedRevision", opened.path("expectedRevision").asInt());
        var draft = write(mvc, path + "draft", input);
        write(mvc, path + "confirm", Map.of(
                "sourceRevisionId", opened.at("/original/revisionId").asText(),
                "sourceRevision", opened.at("/original/revision").asInt(),
                "expectedRevision", draft.path("expectedRevision").asInt(),
                "reviewedDraftRevision", draft.at("/draft/revision").asInt()));
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
