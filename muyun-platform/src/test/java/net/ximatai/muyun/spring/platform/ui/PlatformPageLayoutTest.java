package net.ximatai.muyun.spring.platform.ui;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class PlatformPageLayoutTest {
    @Test
    void shouldDefaultLegacyLayoutToTheCurrentSchemaVersion() {
        PlatformUiConfig config = config("{}");

        assertThat(PlatformPageLayout.decode(config).schemaVersion()).isEqualTo(1);
    }

    @Test
    void shouldRejectUnsupportedSchemaVersionAndUnknownPageRootMembers() {
        assertThatIllegalArgumentException().isThrownBy(() -> PlatformPageLayout.decode(config("{\"schemaVersion\":2}")))
                .withMessageContaining("schemaVersion");
        assertThatIllegalArgumentException().isThrownBy(() -> PlatformPageLayout.decode(config("{\"schemaVersion\":1.5}")))
                .withMessageContaining("schemaVersion");
        PlatformUiConfig unknownMember = config("{\"unexpected\":true}");
        assertThatIllegalArgumentException().isThrownBy(() -> PlatformPageLayout.decode(unknownMember)
                .requireKnownPageRootMembers(unknownMember))
                .withMessageContaining("unexpected");
    }

    private static PlatformUiConfig config(String layoutJson) {
        PlatformUiConfig config = new PlatformUiConfig();
        config.setId("ui-layout");
        config.setLayoutJson(layoutJson);
        return config;
    }
}
