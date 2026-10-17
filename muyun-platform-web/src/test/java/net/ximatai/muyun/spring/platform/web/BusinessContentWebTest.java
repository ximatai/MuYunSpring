package net.ximatai.muyun.spring.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.common.content.*;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.content.BusinessContentService;
import net.ximatai.muyun.spring.platform.content.BusinessContentTranslationService;
import net.ximatai.muyun.spring.platform.content.BusinessContentGenerationService;
import net.ximatai.muyun.spring.platform.content.BusinessContentGenerationScope;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.platform.ui.*;
import net.ximatai.muyun.spring.web.PlatformWebExceptionHandler;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BusinessContentWebTest {
    private BusinessContentService content;
    private BusinessContentTranslationService translations;
    private BusinessContentGenerationService generations;
    private BusinessContentGenerationWebScope generationScopes;
    private ModuleExecutionPlanCatalog plans;
    private Controller controller;
    private MockMvc mvc;
    @BeforeEach void setup() {
        content = mock(BusinessContentService.class);
        translations = mock(BusinessContentTranslationService.class);
        generations = mock(BusinessContentGenerationService.class);
        generationScopes = mock(BusinessContentGenerationWebScope.class);
        plans = mock(ModuleExecutionPlanCatalog.class);
        var ui = mock(ResolvedModuleUiDescriptor.class);
        when(ui.moduleAlias()).thenReturn("test.content");
        when(ui.detailRelations()).thenReturn(List.of(new ResolvedDetailRelationDescriptor(
                "lines", "Lines", true, "test.content", "main", "test.content", "line", "mainId",
                null, null, null, ResolvedDetailRelationEditing.DEFAULT, false, "lines",
                new ResolvedDetailRelationListProjection(null, List.of(new ResolvedDetailRelationListField(
                        "description", "Description", null, null, null, null, null))), List.of(), ResolvedUiRule.constant(true))));
        var plan = new ModuleExecutionPlan("test.content", "one", ui,
                new ResolvedModuleReadModel("test.content", "main", List.of(
                        new ResolvedModuleReadField("main", null, "title", false))), List.of());
        when(plans.find("test.content")).thenReturn(Optional.of(plan));
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("content", content); beans.registerSingleton("plans", plans);
        beans.registerSingleton("translations", translations);
        beans.registerSingleton("generations", generations); beans.registerSingleton("generationScopes", generationScopes);
        var registry = new Interceptors();
        new BusinessContentWebConfiguration(beans.getBeanProvider(BusinessContentService.class),
                beans.getBeanProvider(ModuleExecutionPlanCatalog.class), beans.getBeanProvider(net.ximatai.muyun.spring.platform.content.BusinessContentTranslationService.class),
                beans.getBeanProvider(BusinessContentGenerationService.class), beans.getBeanProvider(BusinessContentGenerationWebScope.class)).addInterceptors(registry);
        controller = new Controller();
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper().findAndRegisterModules()))
                .setControllerAdvice(new PlatformWebExceptionHandler()).addInterceptors(registry.values()).build();
    }
    @Test void standardEndpointsExposeOnlyProducerFieldsAndRetainTheOwningViewBoundary() throws Exception {
        var source = new BusinessContentService.CurrentSource(new ContentFieldDefinition("stable", ContentPolicy.AUTO_TRANSLATE),
                "中文原文", 3, null);
        when(content.sources(controller.service, "record")).thenReturn(new BusinessContentService.Sources(4,
                Map.of("title", source, "unexposed", source, "description", source)));
        when(content.history(eq(controller.service), eq("record"), eq("title"), any())).thenReturn(List.of());
        try (var ignored = TenantContext.use("tenant-a")) {
            mvc.perform(get("/test.content/view/record/content"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.recordVersion").value(4))
                    .andExpect(jsonPath("$.fields.title.text").value("中文原文"))
                    .andExpect(jsonPath("$.fields.title.expectedRevision").value(3))
                    .andExpect(jsonPath("$.fields.unexposed").doesNotExist())
                    .andExpect(jsonPath("$.fields.description").doesNotExist());
            mvc.perform(get("/test.content/view/record/content/title/history?page=2&size=10"))
                    .andExpect(status().isOk()).andExpect(content().json("[]"));
            mvc.perform(get("/test.content/view/record/content/unexposed/history")).andExpect(status().isForbidden());
            clearInvocations(content);
            controller.denied = true;
            mvc.perform(get("/test.content/view/record/content")).andExpect(status().isForbidden());
            mvc.perform(get("/test.content/view/record/content/title/history")).andExpect(status().isForbidden());
            verifyNoInteractions(content);
        }
    }
    @Test void aggregateEndpointsRetainParentRoutingAndOnlyExposeDeclaredChildFields() throws Exception {
        var source = new BusinessContentService.CurrentSource(new ContentFieldDefinition("stable", ContentPolicy.AUTO_TRANSLATE), "English original", 2, null);
        when(content.childSources(controller.service, "parent", "lines", "child"))
                .thenReturn(new BusinessContentService.Sources(1, Map.of("description", source, "title", source)));
        when(content.childHistory(eq(controller.service), eq("parent"), eq("lines"), eq("child"), eq("description"), any()))
                .thenReturn(List.of());
        try (var ignored = TenantContext.use("tenant-a")) {
            mvc.perform(get("/test.content/view/parent/relations/lines/child/content"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.fields.description.text").value("English original"))
                    .andExpect(jsonPath("$.fields.title").doesNotExist());
            mvc.perform(get("/test.content/view/parent/relations/lines/child/content/description/history"))
                    .andExpect(status().isOk());
            mvc.perform(get("/test.content/view/parent/relations/missing/child/content")).andExpect(status().isForbidden());
            mvc.perform(get("/test.content/view/parent/relations/lines/child/content/title/history")).andExpect(status().isForbidden());
            clearInvocations(content); controller.denied = true;
            mvc.perform(get("/test.content/view/parent/relations/lines/child/content")).andExpect(status().isForbidden());
            verifyNoInteractions(content);
        }
    }

    @Test void readingProjectsAuthorizedFieldsAndMaintenanceHasDistinctRootAndAggregateTransports() throws Exception {
        var field = new BusinessContentTranslationService.ReadingField("中文原文", null, "English reading", "en", false, null, false);
        var root = BusinessContentService.Location.record("record");
        var child = new BusinessContentService.Location("record", "lines", "child");
        when(translations.read(controller.service, root, "en")).thenReturn(
                new BusinessContentTranslationService.Reading(4, "en", Map.of("title", field, "unexposed", field)));
        when(translations.read(controller.service, child, "en")).thenReturn(
                new BusinessContentTranslationService.Reading(2, "en", Map.of("description", field, "title", field)));
        var state = new BusinessContentTranslationService.Maintenance(null, null, null, null, null, null);
        when(translations.prepare(eq(controller.service), any(), anyString(), anyString(), any())).thenReturn(state);
        try (var ignored = TenantContext.use("tenant-a")) {
            mvc.perform(get("/test.content/view/record/content/reading?language=en")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.recordVersion").value(4))
                    .andExpect(jsonPath("$.fields.title.originalText").value("中文原文"))
                    .andExpect(jsonPath("$.fields.title.text").value("English reading"))
                    .andExpect(jsonPath("$.fields.unexposed").doesNotExist());
            mvc.perform(get("/test.content/view/record/relations/lines/child/content/reading?language=en"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.fields.description.text").value("English reading"))
                    .andExpect(jsonPath("$.fields.title").doesNotExist());
            mvc.perform(post("/test.content/view/record/content/title/translations/en/prepare")).andExpect(status().isOk());
            verify(translations).prepare(controller.service, root, "title", "en", PlatformAction.CONTENT_EDIT);
            mvc.perform(post("/test.content/view/record/relations/lines/child/content/description/translations/de/review")).andExpect(status().isOk());
            verify(translations).prepare(controller.service, child, "description", "de", PlatformAction.CONTENT_CONFIRM);
            mvc.perform(post("/test.content/view/record/content/title/translations/en/draft")
                    .contentType("application/json").content("{\"sourceRevisionId\":\"original-id\",\"sourceRevision\":2,\"expectedRevision\":3,\"text\":\"Human draft\"}"))
                    .andExpect(status().isOk());
            verify(translations).saveDraft(controller.service, root, "title", "en",
                    new BusinessContentTranslationService.Change("original-id", 2, 3, "Human draft", null));
            clearInvocations(translations);
            mvc.perform(post("/test.content/view/record/content/unexposed/translations/en/prepare")).andExpect(status().isForbidden());
            mvc.perform(post("/test.content/view/record/relations/lines/child/content/title/translations/en/prepare")).andExpect(status().isForbidden());
            verifyNoInteractions(translations);
            controller.denied = true;
            mvc.perform(get("/test.content/view/record/content/reading?language=en")).andExpect(status().isForbidden());
            mvc.perform(post("/test.content/view/record/content/title/translations/en/prepare")).andExpect(status().isForbidden());
            verifyNoInteractions(translations);
        }
    }

    @Test void generationUsesExplicitPostAndReadOnlyStatusWithTheOwningRootAndChildTransport() throws Exception {
        var root = BusinessContentService.Location.record("record");
        var child = new BusinessContentService.Location("record", "lines", "child");
        var scope = new BusinessContentGenerationScope.Reference("test", "one", "{}");
        when(generationScopes.capture(eq(controller), any(), anyString())).thenAnswer(invocation -> {
            BusinessContentService.Location location = invocation.getArgument(1);
            String field = invocation.getArgument(2); controller.view(location.recordId());
            if (!(location.childId() == null ? "title" : "description").equals(field))
                throw new PlatformAccessDeniedException("field is not exposed");
            return scope;
        });
        var pending = new BusinessContentGenerationService.Status("PENDING", "opaque-generation", null, 0, null);
        when(generations.request(eq(scope), eq("test.content"), any(), anyString(), anyString(), anyBoolean())).thenReturn(pending);
        when(generations.status(eq(scope), eq("test.content"), any(), anyString(), anyString())).thenReturn(pending);
        try (var ignored = TenantContext.use("tenant-a")) {
            mvc.perform(post("/test.content/view/record/content/title/translations/en/generation"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("PENDING"));
            verify(generations).request(scope, "test.content", root, "title", "en", false);
            mvc.perform(get("/test.content/view/record/content/title/translations/en/generation"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.reference").value("opaque-generation"));
            verify(generations).status(scope, "test.content", root, "title", "en");
            mvc.perform(post("/test.content/view/record/relations/lines/child/content/description/translations/de/generation?retry=true"))
                    .andExpect(status().isOk());
            verify(generations).request(scope, "test.content", child, "description", "de", true);
            mvc.perform(get("/test.content/view/record/relations/lines/child/content/description/translations/de/generation"))
                    .andExpect(status().isOk());
            verify(generations).status(scope, "test.content", child, "description", "de");
            clearInvocations(generations);
            mvc.perform(post("/test.content/view/record/content/unexposed/translations/en/generation")).andExpect(status().isForbidden());
            mvc.perform(get("/test.content/view/record/relations/lines/child/content/title/translations/de/generation")).andExpect(status().isForbidden());
            controller.denied = true;
            mvc.perform(post("/test.content/view/record/content/title/translations/en/generation")).andExpect(status().isForbidden());
            verifyNoInteractions(generations);
        }
    }

    @Test void boundedGenerationPreviewIsReadOnlyAndExplicitRequestsUseOnlyAuthorizedAutomaticFields() throws Exception {
        var automatic = new BusinessContentService.CurrentSource(new ContentFieldDefinition("title", ContentPolicy.AUTO_TRANSLATE), "private raw", 1, null);
        when(content.sources(controller.service, "record")).thenReturn(new BusinessContentService.Sources(1,
                Map.of("title", automatic, "unexposed", automatic)));
        when(content.sources(controller.service, "denied")).thenThrow(new PlatformAccessDeniedException("not visible"));
        var scope = new BusinessContentGenerationScope.Reference("test", "one", "{}");
        when(generationScopes.capture(eq(controller), any(), eq("title"))).thenReturn(scope);
        var missing = new BusinessContentGenerationService.Status("MISSING", null, null, 0, null);
        var pending = new BusinessContentGenerationService.Status("PENDING", "job", null, 0, null);
        when(generations.status(eq(scope), anyString(), any(), eq("title"), eq("de"))).thenReturn(missing);
        when(generations.request(eq(scope), anyString(), any(), eq("title"), eq("de"), eq(false))).thenReturn(pending);
        String command = "{\"ids\":[\"record\",\"record\",\"denied\"],\"language\":\"de\"}";
        try (var ignored = TenantContext.use("tenant-a")) {
            mvc.perform(post("/test.content/content/generation/status").contentType("application/json").content(command))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
                    .andExpect(jsonPath("$.items[0].recordId").value("record"))
                    .andExpect(jsonPath("$.items[0].field").value("title"))
                    .andExpect(jsonPath("$.items[0].status.state").value("MISSING"))
                    .andExpect(jsonPath("$.items[1].status.state").value("UNAVAILABLE"))
                    .andExpect(jsonPath("$.items[1].field").isEmpty());
            verify(generations, never()).request(any(), anyString(), any(), anyString(), anyString(), anyBoolean());
            mvc.perform(post("/test.content/content/generation").contentType("application/json").content(command))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].status.state").value("PENDING"));
            verify(generations).request(scope, "test.content", BusinessContentService.Location.record("record"), "title", "de", false);
            verify(generationScopes, never()).capture(any(), any(), eq("unexposed"));
        }
    }

    @Test void boundedChildGenerationKeepsTheParentRelationAndExplicitRetryIntent() throws Exception {
        var automatic = new BusinessContentService.CurrentSource(new ContentFieldDefinition("description", ContentPolicy.AUTO_TRANSLATE), "child raw", 1, null);
        when(content.childSourcesForSelection(controller.service, "parent", "lines", "child"))
                .thenReturn(new BusinessContentService.Sources(1, Map.of("description", automatic)));
        var scope = new BusinessContentGenerationScope.Reference("test", "one", "{}");
        when(generationScopes.capture(eq(controller), any(), eq("description"))).thenReturn(scope);
        when(generations.request(eq(scope), anyString(), any(), eq("description"), eq("en"), eq(true)))
                .thenReturn(new BusinessContentGenerationService.Status("PENDING", "job", null, 0, null));
        try (var ignored = TenantContext.use("tenant-a")) {
            mvc.perform(post("/test.content/view/parent/relations/lines/content/generation")
                    .contentType("application/json").content("{\"ids\":[\"child\"],\"language\":\"en\",\"retry\":true}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].recordId").value("child"));
            verify(generations).request(scope, "test.content", new BusinessContentService.Location("parent", "lines", "child"), "description", "en", true);
            verify(content, never()).sources(any(), anyString());
        }
    }

    @Test void boundedGenerationRejectsEmptyOrExcessiveSelectionAndUnsupportedLanguageBeforeAnyReads() throws Exception {
        try (var ignored = TenantContext.use("tenant-a")) {
            for (String body : List.of("{\"ids\":[],\"language\":\"en\"}", "{\"ids\":[\"record\"],\"language\":\"fr\"}",
                    new ObjectMapper().writeValueAsString(Map.of("ids", java.util.stream.IntStream.range(0, 51).mapToObj(i -> "row" + i).toList(), "language", "en")))) {
                mvc.perform(post("/test.content/content/generation").contentType("application/json").content(body))
                        .andExpect(status().isBadRequest());
            }
            verifyNoInteractions(content, generations, generationScopes);
        }
    }

    @Test void boundedRootSelectionCanIncludeItsPublishedOwnedChildrenWithoutEnqueuingUnreadableFields() throws Exception {
        var automatic = new BusinessContentService.CurrentSource(new ContentFieldDefinition("text", ContentPolicy.AUTO_TRANSLATE), "raw", 1, null);
        when(content.sources(controller.service, "record")).thenReturn(new BusinessContentService.Sources(1, Map.of("title", automatic)));
        when(content.childSelection(controller.service, "record", "lines", 199))
                .thenReturn(new BusinessContentService.ChildSelection(List.of("child", "denied-child"), false));
        when(content.childSourcesForSelection(controller.service, "record", "lines", "child"))
                .thenReturn(new BusinessContentService.Sources(1, Map.of("description", automatic, "unexposed", automatic)));
        when(content.childSourcesForSelection(controller.service, "record", "lines", "denied-child"))
                .thenThrow(new PlatformAccessDeniedException("child unavailable"));
        var scope = new BusinessContentGenerationScope.Reference("test", "one", "{}");
        when(generationScopes.capture(eq(controller), any(), anyString())).thenReturn(scope);
        when(generations.request(eq(scope), anyString(), any(), anyString(), eq("de"), eq(false)))
                .thenReturn(new BusinessContentGenerationService.Status("PENDING", "job", null, 0, null));
        try (var ignored = TenantContext.use("tenant-a")) {
            mvc.perform(post("/test.content/content/generation").contentType("application/json")
                    .content("{\"ids\":[\"record\"],\"language\":\"de\",\"includeChildren\":true}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(3))
                    .andExpect(jsonPath("$.items[1].ownerId").value("record"))
                    .andExpect(jsonPath("$.items[1].childId").value("child"))
                    .andExpect(jsonPath("$.items[1].relationCode").value("lines"))
                    .andExpect(jsonPath("$.items[2].status.state").value("UNAVAILABLE"));
            verify(generations).request(scope, "test.content", new BusinessContentService.Location("record", "lines", "child"), "description", "de", false);
            verify(generations, times(2)).request(any(), anyString(), any(), anyString(), anyString(), anyBoolean());
        }
    }

    @Test void boundedGenerationPreflightsAggregateAndTextLimitsBeforeAnyTasksAreRegistered() throws Exception {
        var automatic = new BusinessContentService.CurrentSource(new ContentFieldDefinition("text", ContentPolicy.AUTO_TRANSLATE), "raw", 1, null);
        when(content.sources(controller.service, "record")).thenReturn(new BusinessContentService.Sources(1, Map.of("title", automatic)));
        when(content.childSelection(controller.service, "record", "lines", 199))
                .thenReturn(new BusinessContentService.ChildSelection(List.of("child"), true));
        try (var ignored = TenantContext.use("tenant-a")) {
            mvc.perform(post("/test.content/content/generation").contentType("application/json")
                    .content("{\"ids\":[\"record\"],\"language\":\"de\",\"includeChildren\":true}"))
                    .andExpect(status().isBadRequest());
            var fields = java.util.stream.IntStream.range(0, 201).mapToObj(i -> "text" + i).toList();
            var ui = mock(ResolvedModuleUiDescriptor.class);
            when(ui.moduleAlias()).thenReturn("test.content");
            var wide = new ModuleExecutionPlan("test.content", "many", ui,
                    new ResolvedModuleReadModel("test.content", "main", fields.stream().map(field ->
                            new ResolvedModuleReadField("main", null, field, false)).toList()), List.of());
            when(plans.find("test.content")).thenReturn(Optional.of(wide));
            var sources = new LinkedHashMap<String, BusinessContentService.CurrentSource>(); fields.forEach(field -> sources.put(field, automatic));
            when(content.sources(controller.service, "record")).thenReturn(new BusinessContentService.Sources(1, sources));
            mvc.perform(post("/test.content/content/generation").contentType("application/json")
                    .content("{\"ids\":[\"record\"],\"language\":\"de\"}"))
                    .andExpect(status().isBadRequest());
            verifyNoInteractions(generations);
            verify(generationScopes, never()).capture(any(), any(), anyString());
        }
    }

    @Test void diagnosticRowsDoNotConsumeTheAutomaticTextLimit() throws Exception {
        var fields = java.util.stream.IntStream.range(0, 5).mapToObj(i -> "text" + i).toList();
        var ui = mock(ResolvedModuleUiDescriptor.class);
        when(ui.moduleAlias()).thenReturn("test.content");
        var wide = new ModuleExecutionPlan("test.content", "many", ui,
                new ResolvedModuleReadModel("test.content", "main", fields.stream().map(field ->
                        new ResolvedModuleReadField("main", null, field, false)).toList()), List.of());
        when(plans.find("test.content")).thenReturn(Optional.of(wide));
        var automatic = new BusinessContentService.CurrentSource(new ContentFieldDefinition("text", ContentPolicy.AUTO_TRANSLATE), "raw", 1, null);
        var sources = new LinkedHashMap<String, BusinessContentService.CurrentSource>(); fields.forEach(field -> sources.put(field, automatic));
        var ids = java.util.stream.IntStream.range(0, 50).mapToObj(i -> "row" + i).toList();
        for (int i = 0; i < ids.size(); i++) when(content.sources(controller.service, ids.get(i)))
                .thenReturn(new BusinessContentService.Sources(1, i < 40 ? sources : Map.of()));
        var scope = new BusinessContentGenerationScope.Reference("test", "one", "{}");
        when(generationScopes.capture(eq(controller), any(), anyString())).thenReturn(scope);
        when(generations.status(eq(scope), anyString(), any(), anyString(), eq("de")))
                .thenReturn(new BusinessContentGenerationService.Status("MISSING", null, null, 0, null));
        try (var ignored = TenantContext.use("tenant-a")) {
            mvc.perform(post("/test.content/content/generation/status").contentType("application/json")
                    .content(new ObjectMapper().writeValueAsString(Map.of("ids", ids, "language", "de"))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(210));
            verify(generations, times(200)).status(any(), anyString(), any(), anyString(), anyString());
            verify(generations, never()).request(any(), anyString(), any(), anyString(), anyString(), anyBoolean());
        }
    }

    @Test void anUnavailableOwnedReaderProducesOneDiagnosticWithoutAnyGeneration() throws Exception {
        when(content.sources(controller.service, "record")).thenReturn(new BusinessContentService.Sources(1, Map.of()));
        when(content.childSelection(controller.service, "record", "lines", 199))
                .thenThrow(new PlatformAccessDeniedException("bounded reader unavailable"));
        try (var ignored = TenantContext.use("tenant-a")) {
            mvc.perform(post("/test.content/content/generation").contentType("application/json")
                    .content("{\"ids\":[\"record\"],\"language\":\"de\",\"includeChildren\":true}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                    .andExpect(jsonPath("$.items[0].field").isEmpty())
                    .andExpect(jsonPath("$.items[0].status.state").value("UNAVAILABLE"));
            verifyNoInteractions(generations);
        }
    }

    @Test void theSelectionLimitFailureCarriesDeployableChineseAndEnglishTextResources() {
        var failure = (net.ximatai.muyun.spring.common.exception.PlatformException) BusinessContentGenerationBatch.tooLarge();
        var catalog = net.ximatai.muyun.spring.common.i18n.LanguageCatalog.load(getClass().getClassLoader(), "zh-CN");
        var texts = net.ximatai.muyun.spring.common.i18n.TextResources.load(getClass().getClassLoader(), catalog);
        var english = texts.resolve(failure.textSource(), Map.of(), "en");
        var chinese = texts.resolve(failure.textSource(), Map.of(), "zh-CN");
        assertThat(english.text()).isEqualTo("Selection too large. Select fewer records or exclude details (up to 200 records and 200 text items).");
        assertThat(english.missing()).isFalse();
        assertThat(chinese.text()).isEqualTo(failure.textSource().originalText());
    }

    @RestController @RequestMapping("/test.content")
    static class Controller implements CrudWeb<Row, CrudAbility<Row>> {
        @SuppressWarnings("unchecked") final CrudAbility<Row> service = mock(CrudAbility.class);
        boolean denied;
        @Override public CrudAbility<Row> service() { return service; }
        @Override public Row view(String id) {
            if (denied) throw new PlatformAccessDeniedException("Owning menu or page scope denied");
            var row = new Row(); row.setId(id); return row;
        }
    }
    static class Row extends StandardEntity { }
    static class Interceptors extends InterceptorRegistry {
        HandlerInterceptor[] values() { return getInterceptors().stream().map(value -> (HandlerInterceptor) value).toArray(HandlerInterceptor[]::new); }
    }
}
