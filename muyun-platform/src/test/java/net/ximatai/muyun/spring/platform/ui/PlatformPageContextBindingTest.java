package net.ximatai.muyun.spring.platform.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class PlatformPageContextBindingTest {
    @Test
    void rejectsUnsupportedContextBindingsWhileParsingDynamicPageLayout() {
        for (String binding : java.util.List.of(
                "{\"source\":\"ROUTE\",\"sourceKey\":\"tenantId\",\"target\":\"LIST_QUERY\",\"targetKey\":\"tenantId\"}",
                "{\"source\":\"FORM_FIELD\",\"sourceKey\":\"tenantId\",\"target\":\"FORM_DEFAULT\",\"targetKey\":\"tenantId\"}",
                "{\"source\":\"SESSION\",\"sourceKey\":\"tenantId\",\"target\":\"MUTATION_CONSTRAINT\",\"targetKey\":\"tenantId\"}")) {
            PlatformUiConfig config = new PlatformUiConfig();
            config.setId("ui-list");
            config.setLayoutJson("{\"navigator\":{\"contextBindings\":[" + binding + "],\"levels\":[]}}");

            assertThatIllegalArgumentException().isThrownBy(() -> PlatformPageLayoutNavigator.navigator(config));
        }
    }
}
