package net.ximatai.muyun.spring.platform.dictionary;

import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.spring.common.option.OptionQuery;
import net.ximatai.muyun.spring.common.option.OptionSelectionMode;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldType;
import net.ximatai.muyun.spring.platform.support.PlatformPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes = DictionaryOptionScopeRepositoryIT.TestApplication.class)
class DictionaryOptionScopeRepositoryIT extends PlatformPostgresIntegrationTest {
    @Autowired DictionaryCategoryService categories;
    @Autowired DictionaryItemService items;
    @Autowired DictionaryFieldValueValidator validator;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }

    @Test
    void candidatesAndWritesShareGlobalFallbackWithoutCrossingOtherTenants() {
        String alias = "state_" + UUID.randomUUID().toString().replace("-", "");
        try (var scope = TenantContext.system("seed global dictionary")) {
            String category = category(alias);
            item(category, "global", true);
            item(category, "disabled", false);
        }
        try (var scope = TenantContext.use("other")) {
            item(category(alias), "foreign", true);
        }
        var source = new DictionaryOptionSource("crm", alias, items);
        var field = FieldDefinition.of("state", FieldType.STRING, "State")
                .dictionary("crm", alias, OptionSelectionMode.SINGLE);
        try (var scope = TenantContext.use("consumer")) {
            assertThat(source.options(OptionQuery.enabledOnly())).extracting(option -> option.code())
                    .containsExactly("global");
            assertThat(source.resolve("global").title()).isEqualTo("原文 global");
            assertThat(source.resolve("foreign")).isNull();
            assertThatCode(() -> validator.validate(field, "global")).doesNotThrowAnyException();
            assertThatThrownBy(() -> validator.validate(field, "disabled")).hasMessageContaining("invalid dictionary code");
            assertThatThrownBy(() -> validator.validate(field, "foreign")).hasMessageContaining("invalid dictionary code");
            assertThat(TenantContext.currentTenantId()).contains("consumer");
            assertThat(TenantContext.tenantFilterBypassed()).isFalse();
            String local = category(alias);
            item(local, "local", true);
            assertThat(source.options(OptionQuery.enabledOnly())).extracting(option -> option.code())
                    .containsExactly("local");
            assertThat(source.resolve("global")).isNull();
            assertThatCode(() -> validator.validate(field, "local")).doesNotThrowAnyException();
            assertThatThrownBy(() -> validator.validate(field, "global")).hasMessageContaining("invalid dictionary code");
            var disabled = categories.select(local); disabled.setEnabled(false); categories.update(disabled);
            assertThatThrownBy(() -> source.options(OptionQuery.enabledOnly())).hasMessageContaining("disabled");
            assertThatThrownBy(() -> validator.validate(field, "local")).hasMessageContaining("disabled");
            assertThat(TenantContext.tenantFilterBypassed()).isFalse();
        }
    }

    @Test
    void missingGlobalCategoryNeverUsesAnotherTenantsCategory() {
        String alias = "private_" + UUID.randomUUID().toString().replace("-", "");
        try (var scope = TenantContext.use("owner")) {
            item(category(alias), "private", true);
        }
        try (var scope = TenantContext.use("consumer")) {
            var source = new DictionaryOptionSource("crm", alias, items);
            assertThatThrownBy(() -> source.options(OptionQuery.enabledOnly()))
                    .hasMessageContaining("existing global category");
            assertThatThrownBy(() -> source.resolve("private")).hasMessageContaining("existing global category");
            assertThatThrownBy(() -> source.containsEnabledCode("private"))
                    .hasMessageContaining("existing global category");
            assertThat(TenantContext.currentTenantId()).contains("consumer");
            assertThat(TenantContext.tenantFilterBypassed()).isFalse();
        }
    }

    private String category(String alias) {
        var category = new DictionaryCategory();
        category.setApplicationAlias("crm"); category.setAlias(alias); category.setTitle(alias);
        category.setCategoryKind(DictionaryCategoryKind.DICTIONARY); category.setParentId("root");
        category.setEnabled(true);
        return categories.insert(category);
    }

    private void item(String categoryId, String code, boolean enabled) {
        var item = new DictionaryItem();
        item.setCategoryId(categoryId); item.setCode(code); item.setTitle("原文 " + code);
        item.setParentId("root"); item.setEnabled(enabled);
        items.insert(item);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableMuYunRepositories(basePackageClasses = {DictionaryCategoryDao.class, DictionaryItemDao.class})
    @Import({DictionaryCategoryService.class, DictionaryItemService.class, DictionaryFieldValueValidator.class})
    static class TestApplication {
        @Bean DataSource dataSource() {
            return DataSourceBuilder.create().url(postgres.getJdbcUrl()).username(postgres.getUsername())
                    .password(postgres.getPassword()).driverClassName(postgres.getDriverClassName()).build();
        }
    }
}
