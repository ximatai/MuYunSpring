package net.ximatai.muyun.spring.common.schema;

import net.ximatai.muyun.spring.common.i18n.LanguageCatalog;
import net.ximatai.muyun.spring.common.i18n.TextResources;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class PlatformFieldPolicyTest {
    @Test void standardLabelsHaveSharedLanguageResourcesWithoutChangingFieldEligibility() {
        var catalog = LanguageCatalog.load(getClass().getClassLoader(), "zh-CN");
        var resources = TextResources.load(getClass().getClassLoader(), catalog);
        for (String field : StandardEntitySchema.fieldNames()) {
            var source = PlatformFieldPolicy.titleSource(field);
            assertThat(source).isNotNull();
            for (String tag : java.util.List.of("zh-CN", "en", "fr"))
                assertThat(resources.resolve(source, Map.of(), tag).missing()).as(field + ":" + tag).isFalse();
        }
        assertThat(resources.resolve(PlatformFieldPolicy.titleSource("version"), Map.of(), "zh-CN").text()).isEqualTo("版本号");
        assertThat(resources.resolve(PlatformFieldPolicy.titleSource("version"), Map.of(), "en").text()).isEqualTo("Version");
        PlatformFieldPolicy.all().forEach(field -> assertThat(field.titleSource()).isEqualTo(PlatformFieldPolicy.titleSource(field.fieldName())));
        assertThat(PlatformFieldPolicy.find("version").composable()).isFalse();
        assertThat(PlatformFieldPolicy.find("createdBy").composable()).isTrue();
        assertThat(PlatformFieldPolicy.find("approvalStatus")).isNull();
        assertThat(PlatformFieldPolicy.titleSource("businessName")).isNull();
    }
}
