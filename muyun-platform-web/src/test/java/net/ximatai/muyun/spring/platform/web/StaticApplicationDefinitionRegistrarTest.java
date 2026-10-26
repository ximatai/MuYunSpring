package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.platform.module.StaticDeclarationPreflightTask;

import net.ximatai.muyun.spring.platform.module.StaticModuleDefinitionRegistrar;

import net.ximatai.muyun.spring.platform.application.Application;
import net.ximatai.muyun.spring.platform.application.ApplicationService;
import net.ximatai.muyun.spring.platform.application.PlatformApplication;
import net.ximatai.muyun.spring.platform.application.PlatformStaticApplication;
import net.ximatai.muyun.spring.platform.application.StaticApplicationDefinition;
import net.ximatai.muyun.spring.platform.application.StaticApplicationDefinitionCatalog;
import net.ximatai.muyun.spring.platform.application.StaticApplicationDefinitionRegistrar;
import net.ximatai.muyun.spring.platform.application.StaticApplicationDefinitionScanner;
import net.ximatai.muyun.spring.platform.runtime.PlatformBootstrapTask;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StaticApplicationDefinitionRegistrarTest {
    @Test
    void shouldScanAndRegisterPlatformManagedApplicationBeforeModules() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(EducationApplication.class)) {
            StaticApplicationDefinitionCatalog catalog = new StaticApplicationDefinitionCatalog(
                    List.of(), List.of(new StaticApplicationDefinitionScanner(context)));
            ApplicationService service = mock(ApplicationService.class);
            when(service.select("education")).thenReturn(null);

            StaticApplicationDefinitionRegistrar registrar = new StaticApplicationDefinitionRegistrar(service, catalog);
            registrar.registerAll();

            var captor = forClass(Application.class);
            verify(service).insert(captor.capture());
            assertThat(captor.getValue()).satisfies(application -> {
                assertThat(application.getAlias()).isEqualTo("education");
                assertThat(application.getTitle()).isEqualTo("教学管理");
                assertThat(application.getSortOrder()).isEqualTo(100);
                assertThat(application.getEnabled()).isTrue();
                assertThat(application.getSystemManaged()).isTrue();
            });
            assertThat(registrar.order()).isLessThan(new StaticModuleDefinitionRegistrar(
                    mock(net.ximatai.muyun.spring.platform.module.PlatformModuleService.class),
                    mock(net.ximatai.muyun.spring.platform.module.PlatformModuleActionService.class),
                    List.of()).order());
        }
    }

    @Test
    void shouldSelfRegisterAnnotatedStaticApplicationClass() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(PlatformApplication.class)) {
            List<StaticApplicationDefinition> definitions = new StaticApplicationDefinitionScanner(context).scan();

            assertThat(definitions).singleElement().satisfies(definition -> {
                assertThat(definition.alias()).isEqualTo("platform");
                assertThat(definition.title()).isEqualTo("平台能力");
                assertThat(definition.sortOrder()).isEqualTo(10);
            });
        }
    }

    @Test
    void shouldRejectManualApplicationWithSameAlias() {
        ApplicationService service = mock(ApplicationService.class);
        Application manual = new Application();
        manual.setAlias("education");
        manual.setSystemManaged(Boolean.FALSE);
        when(service.select("education")).thenReturn(manual);
        StaticApplicationDefinitionRegistrar registrar = new StaticApplicationDefinitionRegistrar(service,
                List.of(StaticApplicationDefinition.of("education", "教学管理", 100)));

        assertThatThrownBy(registrar::registerAll)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-platform-managed application: education");
    }

    @Test
    void shouldDisableStalePlatformManagedApplicationWhenScanningDeclarations() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(EducationApplication.class)) {
            StaticApplicationDefinitionCatalog catalog = new StaticApplicationDefinitionCatalog(
                    List.of(), List.of(new StaticApplicationDefinitionScanner(context)));
            ApplicationService service = mock(ApplicationService.class);
            when(service.select("education")).thenReturn(null);
            Application stale = new Application();
            stale.setAlias("retired");
            stale.setSystemManaged(Boolean.TRUE);
            when(service.list(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(stale));

            new StaticApplicationDefinitionRegistrar(service, catalog).registerAll();

            verify(service).disable("retired");
        }
    }

    @Test
    void shouldRejectUndeclaredModuleApplicationBeforeApplicationReconciliationWrites() {
        ApplicationService applicationService = mock(ApplicationService.class);
        StaticApplicationDefinitionCatalog applicationCatalog = new StaticApplicationDefinitionCatalog(List.of(
                StaticApplicationDefinition.of("platform", "平台能力", 10)));
        StaticModuleDefinitionCatalog moduleCatalog = new StaticModuleDefinitionCatalog(List.of(
                StaticModuleDefinition.builder("iam", "iam.user", "用户管理").build()));
        PlatformBootstrapTask preflightTask = new StaticDeclarationPreflightTask(applicationCatalog, moduleCatalog);

        assertThatThrownBy(preflightTask::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("iam.user -> iam")
                .satisfies(failure -> {
                    var declared = (net.ximatai.muyun.spring.common.i18n.TextSourceAware) failure;
                    assertThat(declared.messageArgs()).containsEntry("moduleAlias", "iam.user").containsEntry("applicationAlias", "iam");
                    var loader = getClass().getClassLoader();
                    var resources = net.ximatai.muyun.spring.common.i18n.TextResources.load(loader,
                            net.ximatai.muyun.spring.common.i18n.LanguageCatalog.load(loader, "zh-CN"));
                    assertThat(resources.resolve(declared.textSource(), declared.messageArgs(), "zh-CN").text())
                            .isEqualTo("静态模块引用了未声明的静态应用：iam.user -> iam");
                    assertThat(resources.resolve(declared.textSource(), declared.messageArgs(), "en").text())
                            .isEqualTo(failure.getMessage());
                });
        verify(applicationService, never()).disable(org.mockito.ArgumentMatchers.anyString());
        verify(applicationService, never()).insert(org.mockito.ArgumentMatchers.any());
        verify(applicationService, never()).update(org.mockito.ArgumentMatchers.any());
    }

    @PlatformStaticApplication(alias = "education", title = "教学管理")
    static class EducationApplication {
    }
}
