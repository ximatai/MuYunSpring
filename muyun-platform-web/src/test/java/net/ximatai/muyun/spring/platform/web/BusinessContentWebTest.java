package net.ximatai.muyun.spring.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.common.content.*;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.content.BusinessContentService;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BusinessContentWebTest {
    private BusinessContentService content;
    private Controller controller;
    private MockMvc mvc;
    @BeforeEach void setup() {
        content = mock(BusinessContentService.class);
        var plans = mock(ModuleExecutionPlanCatalog.class);
        var ui = mock(ResolvedModuleUiDescriptor.class);
        when(ui.moduleAlias()).thenReturn("test.content");
        when(ui.detailRelations()).thenReturn(List.of(new net.ximatai.muyun.spring.platform.ui.ResolvedDetailRelationDescriptor(
                "lines", "Lines", true, "test.content", "main", "test.content", "line", "mainId", null, false)));
        var plan = new ModuleExecutionPlan("test.content", "one", ui,
                new ResolvedModuleReadModel("test.content", "main", List.of(
                        new ResolvedModuleReadField("main", null, "title", false),
                        new ResolvedModuleReadField("line", "lines", "description", false))), List.of());
        when(plans.find("test.content")).thenReturn(Optional.of(plan));
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("content", content); beans.registerSingleton("plans", plans);
        var registry = new Interceptors();
        new BusinessContentWebConfiguration(beans.getBeanProvider(BusinessContentService.class),
                beans.getBeanProvider(ModuleExecutionPlanCatalog.class)).addInterceptors(registry);
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
