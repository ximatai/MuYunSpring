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
        clearInvocations(events);
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
    @Test void publishedMetadataFieldTranslationsResolveFromBothQueryAndQuickSearchSchemas() {
        var entity = net.ximatai.muyun.spring.dynamic.descriptor.DynamicEntityDescriptor.from(
                new net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition("order", "order", "订单",
                        List.of(net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition.string("title", "业务标题").queryable()), Set.of()));
        var schema = MetadataQuerySchemas.from("test.order", entity, List.of("title"));
        var source = ConfigurationTextSources.metadataFieldTitle("test.order", "order", "title", "业务标题");
        var previous = original.getAndSet(source);
        try (var user = CurrentUserContext.use(CurrentUser.systemUser("root", "root"));
             var scope = TenantContext.system("metadata query translation contract")) {
            var auditLabels = Map.of("createdBy", "Created by", "createdAt", "Created at", "updatedBy", "Updated by", "updatedAt", "Updated at");
            schema.fields().stream().filter(field -> auditLabels.containsKey(field.name())).forEach(field -> {
                assertThat(field.titleSource().sourceKind()).isEqualTo(TextReference.SourceKind.CODE);
                assertThat(resolver.resolve(field.titleSource(), Map.of(), "en").text()).isEqualTo(auditLabels.get(field.name()));
            });
            assertThat(schema.fields().getFirst().titleSource()).isEqualTo(source);
            assertThat(schema.quickSearch().fieldSchemas().getFirst().titleSource()).isEqualTo(source);
            var draft = translations.saveDraft("test.order", source.sourceId(), source.sourceVersion(), "en", "Business title", null);
            assertThat(resolver.resolve(schema.fields().getFirst().titleSource(), Map.of(), "en").text()).isEqualTo("业务标题");
            translations.publish(draft.getId(), source.sourceVersion(), draft.getVersion());
            assertThat(resolver.resolve(schema.fields().getFirst().titleSource(), Map.of(), "en").text()).isEqualTo("Business title");
            assertThat(resolver.resolve(schema.quickSearch().fieldSchemas().getFirst().titleSource(), Map.of(), "en").text()).isEqualTo("Business title");
            assertThat(schema.fields().getFirst().title()).isEqualTo("业务标题");
            assertThat(schema.fields().getFirst().name()).isEqualTo("title");
            assertThat(schema.quickSearch().fields()).containsExactly("title");
        } finally { original.set(previous); }
    }

    @Test void seedsOnlyUntouchedOriginalVersionsAndPreservesMaintainedDraftsAndPublications() {
        var source = TextReference.configured("config/test/seed/title", "初始标题");
        var previous = original.getAndSet(source);
        var beans = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        var seeds = new ConfiguredTextSeedService(translations, beans.getBeanProvider(ConfiguredTextSeedTargetProvider.class));
        var entry = new ConfiguredTextSeedResources.Entry("test.order", source.sourceId(), source.sourceVersion(), "en", "Initial title");
        try (var user = CurrentUserContext.use(CurrentUser.systemUser("root", "root"));
             var scope = TenantContext.system("deployed text seed contract")) {
            assertThat(seeds.seed(List.of(entry), null)).isEqualTo(1);
            var row = translations.published(List.of(source)).getFirst();
            int version = row.getVersion();
            assertThat(seeds.seed(List.of(entry), null)).isZero();
            assertThat(translations.published(List.of(source)).getFirst().getVersion()).isEqualTo(version);
            var maintained = translations.saveDraft("test.order", source.sourceId(), source.sourceVersion(), "en", "Maintained draft", version);
            assertThat(seeds.seed(List.of(entry), null)).isZero();
            assertThat(translations.translations("test.order").stream().filter(value -> row.getId().equals(value.getId())).findFirst().orElseThrow().getDraftText()).isEqualTo("Maintained draft");
            assertThat(resolver.resolve(source, Map.of(), "en").text()).isEqualTo("Initial title");
            translations.publish(row.getId(), source.sourceVersion(), maintained.getVersion());
            assertThat(seeds.seed(List.of(entry), null)).isZero();
            assertThat(resolver.resolve(source, Map.of(), "en").text()).isEqualTo("Maintained draft");
            var changed = TextReference.configured(source.sourceId(), "变化后的标题");
            original.set(changed);
            assertThat(seeds.seed(List.of(entry), null)).isZero();
            assertThat(resolver.resolve(changed, Map.of(), "en").text()).isEqualTo("变化后的标题");
            assertThat(seeds.seed(List.of(new ConfiguredTextSeedResources.Entry("test.order", changed.sourceId(), changed.sourceVersion(), "en", "Changed title")), null)).isEqualTo(1);
            var manual = TextReference.configured("config/test/manual-seed/title", "人工草稿");
            original.set(manual);
            var draft = translations.saveDraft("test.order", manual.sourceId(), manual.sourceVersion(), "en", "Unpublished manual draft", null);
            assertThat(seeds.seed(List.of(new ConfiguredTextSeedResources.Entry("test.order", manual.sourceId(), manual.sourceVersion(), "en", "Must not replace")), null)).isZero();
            assertThat(translations.select(draft.getId()).getPublishedText()).isNull();
            assertThat(translations.select(draft.getId()).getDraftText()).isEqualTo("Unpublished manual draft");
        } finally { original.set(previous); }
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
