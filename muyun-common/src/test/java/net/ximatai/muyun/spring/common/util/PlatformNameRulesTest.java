package net.ximatai.muyun.spring.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformNameRulesTest {
    @Test
    void declarationFailuresKeepOriginalsAndTechnicalArgumentsForBothLanguages() {
        var loader = getClass().getClassLoader();
        var resources = net.ximatai.muyun.spring.common.i18n.TextResources.load(loader,
                net.ximatai.muyun.spring.common.i18n.LanguageCatalog.load(loader, "zh-CN"));
        for (String value : java.util.Arrays.asList(null, "", "Invalid {name}")) {
            var failure = org.junit.jupiter.api.Assertions.assertThrows(
                    net.ximatai.muyun.spring.common.exception.DeclarationValidationException.class,
                    () -> PlatformNameRules.requireApplicationAlias(value));
            assertThat(failure.textSource().sourceKind()).isEqualTo(
                    net.ximatai.muyun.spring.common.i18n.TextReference.SourceKind.CODE);
            assertThat(failure.messageArgs()).containsEntry("name", "applicationAlias");
            for (String language : java.util.List.of("en", "zh-CN")) {
                var translated = resources.resolve(failure.textSource(), failure.messageArgs(), language);
                assertThat(translated.missing()).isFalse();
                assertThat(translated.fallback()).isFalse();
                assertThat(translated.text()).contains("applicationAlias");
                if (value != null && !value.isEmpty()) assertThat(translated.text()).contains(value);
            }
        }
    }

    @Test
    void shouldValidateApplicationAliasAndModuleAlias() {
        assertThat(PlatformNameRules.requireApplicationAlias("crm_app")).isEqualTo("crm_app");
        assertThat(PlatformNameRules.requireModuleAlias("crm.customer_profile")).isEqualTo("crm.customer_profile");
        assertThat(PlatformNameRules.requireModuleAlias("platform.code_rule")).isEqualTo("platform.code_rule");

        assertThatThrownBy(() -> PlatformNameRules.requireApplicationAlias("crm-app"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("applicationAlias");
        assertThatThrownBy(() -> PlatformNameRules.requireModuleAlias("crm"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("moduleAlias");
        assertThatThrownBy(() -> PlatformNameRules.requireModuleAlias("platform.codeRule"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("moduleAlias");
    }

    @Test
    void shouldValidateIdentifierCodeAndDatabaseName() {
        assertThat(PlatformNameRules.requireIdentifier("customer_status", "metadataAlias")).isEqualTo("customer_status");
        assertThat(PlatformNameRules.requireCode("active", "dictionaryItemCode")).isEqualTo("active");
        assertThat(PlatformNameRules.requireCode("1", "dictionaryItemCode")).isEqualTo("1");
        assertThat(PlatformNameRules.requireCode("A-01", "measureUnitCode")).isEqualTo("A-01");
        assertThat(PlatformNameRules.requireActionCode("item_create", "actionCode")).isEqualTo("item_create");
        assertThat(PlatformNameRules.requireDatabaseName("crm_customer", "tableName")).isEqualTo("crm_customer");

        assertThatThrownBy(() -> PlatformNameRules.requireIdentifier("Customer", "metadataAlias"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("metadataAlias");
        assertThatThrownBy(() -> PlatformNameRules.requireIdentifier(null, "metadataAlias"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("metadataAlias");
        assertThatThrownBy(() -> PlatformNameRules.requireCode("-1", "dictionaryItemCode"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dictionaryItemCode");
        assertThatThrownBy(() -> PlatformNameRules.requireCode("active.code", "dictionaryItemCode"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dictionaryItemCode");
        assertThatThrownBy(() -> PlatformNameRules.requireDatabaseName("crm.customer", "tableName"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tableName");
    }

    @Test
    void shouldValidateJavaStyleFieldNameSeparately() {
        assertThat(PlatformNameRules.requireFieldName("customerId", "fieldName")).isEqualTo("customerId");

        assertThatThrownBy(() -> PlatformNameRules.requireFieldName("customer_id", "fieldName"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fieldName");
    }
}
