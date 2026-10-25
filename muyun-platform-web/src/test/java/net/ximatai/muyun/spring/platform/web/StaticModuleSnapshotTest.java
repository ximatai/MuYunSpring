package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.platform.application.Application;
import net.ximatai.muyun.spring.platform.application.ApplicationService;
import net.ximatai.muyun.spring.platform.module.StaticModuleRegistrationSource;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;

class StaticModuleSnapshotTest {
    @Test void allRuntimeConsumersShareOneScanAndApplicationAvailabilityRemainsLive() {
        var scanner = mock(StaticModuleDefinitionScanner.class);
        when(scanner.scan()).thenReturn(List.of(
                StaticModuleDefinition.builder("platform", "platform.shared", "Shared").tenantAccessible(true).build(),
                StaticModuleDefinition.builder("platform", "platform.private", "Private").build()));
        var catalog = new StaticModuleDefinitionCatalog(List.of(), List.of(scanner));
        var app = new Application(); app.setEnabled(true);
        var service = new ApplicationService(mock(BaseDao.class)) {
            @Override public Application select(String id) { return app; }
        };
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean("scanner", StaticModuleDefinitionScanner.class, () -> scanner);
            context.registerBean("catalog", StaticModuleDefinitionCatalog.class, () -> catalog);
            context.registerBean("applications", ApplicationService.class, () -> service);
            context.refresh();
            assertThat(context.getBeansOfType(StaticModuleRegistrationSource.class).keySet()).containsExactly("catalog");
            for (int i = 0; i < 20; i++) {
                assertThat(service.isModuleAvailableWithoutOpening("platform.shared")).isTrue();
                assertThat(service.isModuleAvailableWithoutOpening("platform.private")).isFalse();
                assertThat(service.isModuleAvailableWithoutOpening("platform.unknown")).isFalse();
                assertThat(catalog.find("platform.shared")).isPresent();
                assertThat(catalog.definitions()).hasSize(2);
            }
            app.setEnabled(false);
            assertThat(service.isModuleAvailableWithoutOpening("platform.shared")).isFalse();
            verify(scanner, times(1)).scan();
        }
    }
}
