package net.ximatai.muyun.spring.platform.localization;

import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.spring.ability.event.RuntimeEventPublisher;
import net.ximatai.muyun.spring.common.i18n.*;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.support.PlatformPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes = ConfigurationTranslationRepositoryIT.TestApplication.class)
class ConfigurationTranslationRepositoryIT extends PlatformPostgresIntegrationTest {
    @Autowired ConfigurationTranslationService translations;
    @Autowired PlatformTextResolver resolver;
    @Autowired AtomicReference<TextReference> original;
    @Autowired RuntimeEventPublisher events;
    @Autowired ConfigurationTranslationDao dao;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired DataSource dataSource;
    @BeforeEach void installTransactions() {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        net.ximatai.muyun.spring.ability.PlatformAbilityRuntime.configureMutationTransactionOperator(new net.ximatai.muyun.spring.ability.MutationTransactionOperator() {
            public <T> T execute(java.util.function.Supplier<T> work) { return new org.springframework.transaction.support.TransactionTemplate(transactions).execute(status -> work.get()); }
            public void lock(String scope, String key) { jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class, scope + key); }
        });
    }
    @AfterEach void resetTransactions() { net.ximatai.muyun.spring.ability.PlatformAbilityRuntime.resetMutationTransactionOperator(); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }
    @Test void keepsDraftsPrivateChecksBothVersionsAndPreservesPublishedOriginalVersions() {
        var source = original.get();
        try (var user = CurrentUserContext.use(CurrentUser.systemUser("root", "root"));
             var scope = TenantContext.system("translation publication contract")) {
            var draft = translations.saveDraft("test.order", source.sourceId(), source.sourceVersion(), "en", "Order", null);
            assertThat(draft.getTenantId()).isNull();
            assertThat(resolver.resolve(source, Map.of(), "en").text()).isEqualTo("订单");
            assertThatThrownBy(() -> translations.publish(draft.getId(), source.sourceVersion(), null)).hasMessageContaining("版本");
            var published = translations.publish(draft.getId(), source.sourceVersion(), draft.getVersion());
            assertThat(published.getPublishedRevision()).isEqualTo(1);
            assertThat(resolver.resolve(source, Map.of(), "en").text()).isEqualTo("Order");
            assertThat(resolver.resolve(source, Map.of(), "zh-CN").text()).isEqualTo("订单");
            assertThatThrownBy(() -> translations.saveDraft("test.order", source.sourceId(), source.sourceVersion(), "en", "New", draft.getVersion()))
                    .isInstanceOf(net.ximatai.muyun.spring.ability.OptimisticLockException.class);
            var corrected = translations.saveDraft("test.order", source.sourceId(), source.sourceVersion(), "en", "Sales order", published.getVersion());
            assertThat(resolver.resolve(source, Map.of(), "en").text()).isEqualTo("Order");
            corrected = translations.publish(corrected.getId(), source.sourceVersion(), corrected.getVersion());
            assertThat(resolver.resolve(source, Map.of(), "en").text()).isEqualTo("Sales order");
            original.set(TextReference.configured(source.sourceId(), "订单名称"));
            int storedVersion = corrected.getVersion();
            assertThatThrownBy(() -> translations.publish(draft.getId(), source.sourceVersion(), storedVersion)).hasMessageContaining("原文");
            assertThat(resolver.resolve(original.get(), Map.of(), "en").text()).isEqualTo("订单名称");
            assertThat(resolver.resolve(source, Map.of(), "en").text()).isEqualTo("Sales order");
            assertThat(dao.findById(draft.getId()).getOriginalText()).isEqualTo("订单");
            var forged = dao.findById(draft.getId());
            forged.setPublishedText("Bypass");
            assertThatThrownBy(() -> translations.update(forged)).hasMessageContaining("命令");
            verify(events, times(2)).publishAfterCommit(any());
        }
        try (var tenant = TenantContext.use("tenant-a")) {
            assertThat(resolver.resolve(source, Map.of(), "en").text()).isEqualTo("Sales order");
            assertThat(TenantContext.currentTenantId()).contains("tenant-a");
            assertThatThrownBy(() -> translations.saveDraft("test.order", source.sourceId(), source.sourceVersion(), "en", "Tenant override", null))
                    .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
        }
    }
    @SpringBootConfiguration @EnableAutoConfiguration
    @EnableMuYunRepositories(basePackageClasses = ConfigurationTranslationDao.class)
    @Import({ConfigurationTranslationService.class, PlatformTextResolver.class})
    static class TestApplication {
        @Bean DataSource dataSource() { return DataSourceBuilder.create().url(postgres.getJdbcUrl()).username(postgres.getUsername()).password(postgres.getPassword()).driverClassName(postgres.getDriverClassName()).build(); }
        @Bean AtomicReference<TextReference> original() { return new AtomicReference<>(TextReference.configured("config/ui/test.order/title", "订单")); }
        @Bean ConfiguredTextSourceProvider sources(AtomicReference<TextReference> original) { return alias -> alias.equals("test.order") ? List.of(original.get()) : List.of(); }
        @Bean RuntimeEventPublisher events() { return mock(RuntimeEventPublisher.class); }
        @Bean LanguageCatalog catalog() { return LanguageCatalog.load(getClass().getClassLoader(), "zh-CN"); }
        @Bean TextResources codeTexts(LanguageCatalog catalog) { return TextResources.load(getClass().getClassLoader(), catalog); }
    }
}
