package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.common.platform.CustomActionEndpoint;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.platform.module.StaticModuleActionDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionCategory;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionExecutorType;
import net.ximatai.muyun.spring.web.FormActionWeb;
import net.ximatai.muyun.spring.web.FormActionRequest;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class PageActionInvocationCompilerTest {
    @Test
    void scannedAndCompiledInvocationCallsTheActualSpringEndpoint() throws Exception {
        try (var context = new org.springframework.context.support.GenericApplicationContext()) {
            context.registerBean(Endpoint.class);
            context.refresh();
            var definition = new StaticModuleDefinitionScanner(context).scan().getFirst();
            assertThat(definition.actions()).filteredOn(action -> action.actionCode().equals("approve"))
                    .singleElement().satisfies(action -> {
                        assertThat(action.category()).isEqualTo(EntityActionCategory.CUSTOM);
                        assertThat(action.executorType()).isEqualTo(EntityActionExecutorType.SERVICE);
                    });
            var page = ModuleUiDescriptorCompiler.compile(definition).page();
            var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(context.getBean(Endpoint.class)).build();
            for (var action : page.actions()) {
                var invocation = action.invocation();
                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                        org.springframework.http.HttpMethod.valueOf(invocation.method()),
                        invocation.path().replace("{recordId}", "document-7")))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                                .string(action.actionCode().equals("approve") ? "approved:document-7" : "rebuilt"));
            }
        }
    }

    @Test
    void overriddenEndpointCompilesOnceAndMatchesTheMostSpecificSpringHandler() throws Exception {
        var bindings = compile(OverriddenEndpoint.class);
        assertThat(bindings).containsOnlyKeys("rebuild", "approve");
        var invocation = bindings.get("rebuild").get(PageActionAnchor.PAGE);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new OverriddenEndpoint()).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                org.springframework.http.HttpMethod.valueOf(invocation.method()), invocation.path()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string("overridden"));
    }

    @Test
    void compilesActualHttpMethodAndRecordVariableWithoutGuessingFromTheActionCode() {
        var invocations = compile(Endpoint.class);
        assertThat(invocations.get("rebuild").get(PageActionAnchor.PAGE))
                .isEqualTo(new PageActionInvocation("GET", "/demo.entry/operations/rebuild-index", PageActionInvocation.Input.NONE));
        assertThat(invocations.get("approve").get(PageActionAnchor.DETAIL))
                .isEqualTo(new PageActionInvocation("PATCH", "/demo.entry/documents/{recordId}/approval", PageActionInvocation.Input.NONE));
        assertThat(invocations).doesNotContainKey("unselected");
    }

    @Test
    void anyScopeActionCanBindPageAndDetailButCannotRepeatOneAnchor() {
        var bindings = compile(AnyScopeEndpoint.class).get("recalculate");
        assertThat(bindings).containsOnlyKeys(PageActionAnchor.PAGE, PageActionAnchor.DETAIL);
        assertThat(bindings.get(PageActionAnchor.PAGE).path()).isEqualTo("/demo.entry/recalculate-all");
        assertThat(bindings.get(PageActionAnchor.DETAIL).path()).isEqualTo("/demo.entry/{recordId}/recalculate");
        assertThatThrownBy(() -> compile(DuplicatePageEndpoint.class))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("multiple button mappings").hasMessageContaining("PAGE");
    }

    @Test
    void rejectsInputsAndMappingsThatPageButtonsCannotSupply() {
        for (var controller : List.of(BodyEndpoint.class, QueryEndpoint.class, MultiplePaths.class,
                UnknownVariable.class, OutsideModule.class, MissingMapping.class)) {
            assertThatThrownBy(() -> compile(controller)).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("pageInvocable");
        }
    }

    @Test
    void rejectsMissingBindingDuringStaticPageCompilationAndFreezesValidBinding() {
        var action = StaticModuleActionDefinition.recordAction("approve", "批准");
        var ui = ModuleUiDefinition.builder("demo.entry").managedActions()
                .page(PageTemplates.listDetailCard(page -> page.list(list -> list.fields(fields -> { }))
                        .detail(detail -> detail.editor(editor -> { }))))
                .pageAction("approve", PageActionAnchor.DETAIL).build();
        var definition = StaticModuleDefinition.builder("demo", "demo.entry", "Entry")
                .actions(List.of(action)).uiDefinition(ui).build();
        assertThatThrownBy(() -> ModuleUiDescriptorCompiler.compile(definition))
                .hasMessageContaining("页面动作缺少可执行绑定");
        var binding = compile(Endpoint.class).get("approve");
        var resolved = ModuleUiDescriptorCompiler.compile(definition.toBuilder()
                .actionInvocations(Map.of("approve", binding)).build());
        assertThat(resolved.page().actions()).singleElement().satisfies(entry ->
                assertThat(entry.invocation()).isEqualTo(binding.get(PageActionAnchor.DETAIL)));
    }

    @Test
    void staticDefaultCategoryAndExecutorMatchPersistedActionDefaults() {
        var custom = StaticModuleActionDefinition.recordAction("approve", "批准");
        assertThat(custom.category()).isEqualTo(EntityActionCategory.CUSTOM);
        assertThat(custom.executorType()).isEqualTo(EntityActionExecutorType.SERVICE);
        assertThat(StaticModuleActionDefinition.recordAction("update", "更新").category()).isEqualTo(EntityActionCategory.STANDARD);
    }

    @Test
    void formBindingRequiresRealFormTransportAndExplicitFormSupport() {
        var ordinary = StaticModuleActionDefinition.recordAction("approve", "批准");
        var action = new StaticModuleActionDefinition(ordinary.actionCode(), ordinary.permissionActionCode(), ordinary.title(),
                ordinary.actionLevel(), ordinary.category(), ordinary.accessMode(), ordinary.actionAuth(), ordinary.dataAuth(),
                ordinary.defaultGrantPolicy(), ordinary.executorType(), ordinary.executorKey(), true);
        assertThat(StaticPageActionInvocationCompiler.compile(Endpoint.class, "demo.entry", List.of(action), code -> code)
                .get("approve")).doesNotContainKey(PageActionAnchor.FORM);
        assertThat(StaticPageActionInvocationCompiler.compile(FormEndpoint.class, "demo.entry", List.of(ordinary), code -> code)).isEmpty();
        var formUi = ModuleUiDefinition.builder("demo.entry").managedActions()
                .page(PageTemplates.listDetailCard(page -> page.list(list -> list.fields(fields -> { }))
                        .detail(detail -> detail.editor(editor -> { }))))
                .pageAction("approve", PageActionAnchor.FORM).build();
        var definition = StaticModuleDefinition.builder("demo", "demo.entry", "Entry")
                .actions(List.of(action)).uiDefinition(formUi).build();
        assertThatThrownBy(() -> ModuleUiDescriptorCompiler.compile(definition))
                .hasMessageContaining("页面动作缺少可执行绑定").hasMessageContaining("FORM");
        assertThat(StaticPageActionInvocationCompiler.compile(FormEndpoint.class, "demo.entry", List.of(action), code -> code)
                .get("approve").get(PageActionAnchor.FORM))
                .isEqualTo(new PageActionInvocation("POST", "/demo.entry/form-actions/approve", PageActionInvocation.Input.FORM_RECORD));
    }

    @Test
    void dynamicUsesTheSameAnchorAndInputContract() {
        var action = new PlatformModuleRuntimeAction("approve", "approve", "批准", PlatformActionLevel.RECORD,
                EntityActionCategory.CUSTOM, null, true, true, null, EntityActionExecutorType.SERVICE,
                "approval", true, "ALLOW", false, true);
        var binding = PageActionInvocationCompiler.dynamic("demo.entry", action);
        assertThat(binding).containsOnlyKeys(PageActionAnchor.DETAIL, PageActionAnchor.FORM);
        assertThat(binding.get(PageActionAnchor.DETAIL)).isEqualTo(new PageActionInvocation("POST", "/demo.entry/approve/{recordId}", PageActionInvocation.Input.NONE));
        assertThat(binding.get(PageActionAnchor.FORM)).isEqualTo(new PageActionInvocation("POST", "/demo.entry/form-actions/approve", PageActionInvocation.Input.FORM_RECORD));
    }

    @Test
    void rejectsMalformedAndCrossOriginPaths() {
        for (String path : List.of("//host/path", "/x/{recordId}/bad}", "/x/{id}", "/x/../y", "/x/{recordId}/{recordId}")) {
            assertThatThrownBy(() -> new PageActionInvocation("POST", path, PageActionInvocation.Input.NONE))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private Map<String, Map<PageActionAnchor, PageActionInvocation>> compile(Class<?> controller) {
        return StaticPageActionInvocationCompiler.compile(controller, "demo.entry", List.of(), code -> code);
    }

    @RestController
    @net.ximatai.muyun.spring.platform.module.PlatformStaticModule(
            application = StaticTestApplications.DemoApplication.class, alias = "demo.entry", title = "Entry")
    @RequestMapping("/demo.entry")
    static class Endpoint implements StaticModuleUiContributor {
        public ModuleUiDefinition moduleUiDefinition() {
            return ModuleUiDefinition.builder("demo.entry").managedActions()
                    .page(PageTemplates.listDetailCard(page -> page.list(list -> list.fields(fields -> { }))
                            .detail(detail -> detail.editor(editor -> { }))))
                    .pageAction("rebuild", PageActionAnchor.PAGE)
                    .pageAction("approve", PageActionAnchor.DETAIL).build();
        }
        @GetMapping("/operations/rebuild-index")
        @CustomActionEndpoint(value = "rebuild", level = PlatformActionLevel.LIST, pageInvocable = true)
        public String rebuild() { return "rebuilt"; }
        @PatchMapping("/documents/{documentId}/approval")
        @CustomActionEndpoint(value = "approve", level = PlatformActionLevel.RECORD,
                recordIdPathVariable = "documentId", pageInvocable = true)
        public String approve(@PathVariable("documentId") String id) { return "approved:" + id; }
        @PostMapping("/unselected") @CustomActionEndpoint("unselected") public void unselected() { }
    }
    @RestController
    @RequestMapping("/demo.entry")
    static class OverriddenEndpoint extends Endpoint {
        @Override
        @GetMapping("/operations/rebuild-index")
        @CustomActionEndpoint(value = "rebuild", level = PlatformActionLevel.LIST, pageInvocable = true)
        public String rebuild() { return "overridden"; }
    }
    @RequestMapping("/demo.entry")
    static class AnyScopeEndpoint {
        @PostMapping("/recalculate-all")
        @CustomActionEndpoint(value = "recalculate", level = PlatformActionLevel.ANY, pageInvocable = true)
        public void all() { }
        @PostMapping("/{id}/recalculate")
        @CustomActionEndpoint(value = "recalculate", level = PlatformActionLevel.ANY, pageInvocable = true)
        public void one(@PathVariable("id") String id) { }
    }
    @RequestMapping("/demo.entry")
    static class DuplicatePageEndpoint extends AnyScopeEndpoint {
        @GetMapping("/another-recalculation")
        @CustomActionEndpoint(value = "recalculate", level = PlatformActionLevel.ANY, pageInvocable = true)
        public void another() { }
    }
    @RequestMapping("/demo.entry") static class BodyEndpoint {
        @PostMapping("/execute") @CustomActionEndpoint(value = "execute", pageInvocable = true)
        public void execute(@RequestBody Map<String, Object> body) { }
    }
    @RequestMapping("/demo.entry") static class QueryEndpoint {
        @GetMapping("/execute") @CustomActionEndpoint(value = "execute", pageInvocable = true)
        public void execute(@RequestParam String name) { }
    }
    @RequestMapping("/demo.entry") static class MultiplePaths {
        @PostMapping({"/one", "/two"}) @CustomActionEndpoint(value = "execute", pageInvocable = true)
        public void execute() { }
    }
    @RequestMapping("/demo.entry") static class UnknownVariable {
        @PostMapping("/{tenantId}/{id}") @CustomActionEndpoint(value = "execute", pageInvocable = true)
        public void execute(@PathVariable("id") String id) { }
    }
    @RequestMapping("/elsewhere") static class OutsideModule {
        @PostMapping("/execute") @CustomActionEndpoint(value = "execute", pageInvocable = true)
        public void execute() { }
    }
    @RequestMapping("/demo.entry") static class MissingMapping {
        @CustomActionEndpoint(value = "execute", pageInvocable = true) public void execute() { }
    }
    @RequestMapping("/demo.entry") static class FormEndpoint implements FormActionWeb<Object, StandardEntity, Object> {
        public Object service() { return this; }
        public String webScopeName() { return "demo.entry"; }
        public Object executeFormAction(String code, FormActionRequest<StandardEntity> request) { return null; }
    }
}
