package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.iam.position.PositionCategoryService;
import net.ximatai.muyun.spring.iam.position.PositionService;
import net.ximatai.muyun.spring.iam.web.PositionWebController;
import net.ximatai.muyun.spring.platform.application.ApplicationService;
import net.ximatai.muyun.spring.platform.module.PlatformModuleService;
import net.ximatai.muyun.spring.platform.runtime.PlatformDynamicRuntimeRefreshService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Golden wiring and source-boundary contracts for standard static module controllers. */
class StaticModuleWebControllerAdapterTest {

    @Test
    void applicationControllerGetsStrictPlanBridgeFromContext() {
        StandardModuleWebRuntime runtime = mock(StandardModuleWebRuntime.class);
        try (GenericApplicationContext context = baseContext(runtime)) {
            context.registerBean(ApplicationService.class, () -> mock(ApplicationService.class));
            context.registerBean(ApplicationWebController.class);
            context.refresh();

            assertStrictBridge(context.getBean(ApplicationWebController.class), runtime);
        }
    }

    @Test
    void platformModuleControllerGetsStrictPlanBridgeFromContext() {
        StandardModuleWebRuntime runtime = mock(StandardModuleWebRuntime.class);
        try (GenericApplicationContext context = baseContext(runtime)) {
            context.registerBean(PlatformModuleService.class, () -> mock(PlatformModuleService.class));
            context.registerBean(PlatformDynamicRuntimeRefreshService.class,
                    () -> mock(PlatformDynamicRuntimeRefreshService.class));
            context.registerBean(PlatformOpenApiCatalogService.class,
                    () -> mock(PlatformOpenApiCatalogService.class));
            context.registerBean(PlatformModuleWebController.class);
            context.refresh();

            assertStrictBridge(context.getBean(PlatformModuleWebController.class), runtime);
        }
    }

    @Test
    void positionControllerGetsStrictPlanBridgeFromContext() {
        StandardModuleWebRuntime runtime = mock(StandardModuleWebRuntime.class);
        try (GenericApplicationContext context = baseContext(runtime)) {
            context.registerBean(PositionService.class, () -> mock(PositionService.class));
            context.registerBean(PositionCategoryService.class, () -> mock(PositionCategoryService.class));
            context.registerBean(PositionWebController.class);
            context.refresh();

            assertStrictBridge(context.getBean(PositionWebController.class), runtime);
        }
    }

    @Test
    void businessControllersMustNotReachIntoExecutionPlanRuntimeInternals() throws IOException {
        assertBusinessControllerUsesAdapter("src/main/java/net/ximatai/muyun/spring/platform/web/ApplicationWebController.java");
        assertBusinessControllerUsesAdapter("src/main/java/net/ximatai/muyun/spring/platform/web/PlatformModuleWebController.java");
        assertBusinessControllerUsesAdapter("../muyun-iam-web/src/main/java/net/ximatai/muyun/spring/iam/web/PositionWebController.java");
    }

    private GenericApplicationContext baseContext(StandardModuleWebRuntime runtime) {
        GenericApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(StandardModuleWebRuntime.class, () -> runtime);
        return context;
    }

    private void assertStrictBridge(StaticModuleWebControllerAdapter<?> controller,
                                    StandardModuleWebRuntime runtime) {
        assertThat(controller.standardModuleWebRuntime()).isSameAs(runtime);
        assertThat(controller.requiresModuleExecutionPlan()).isTrue();
    }

    private void assertBusinessControllerUsesAdapter(String sourceFile) throws IOException {
        String source = Files.readString(Path.of(sourceFile));
        assertThat(source).contains("extends StaticModuleWebControllerAdapter");
        assertThat(source).doesNotContain("import net.ximatai.muyun.spring.platform.web.StandardModuleWebRuntime;");
        assertThat(source).doesNotContain("import net.ximatai.muyun.spring.platform.web.ModuleExecutionPlanCatalog;");
        assertThat(source).doesNotContain("setStandardModuleWebRuntime");
        assertThat(source).doesNotContain("requiresModuleExecutionPlan");
    }
}
