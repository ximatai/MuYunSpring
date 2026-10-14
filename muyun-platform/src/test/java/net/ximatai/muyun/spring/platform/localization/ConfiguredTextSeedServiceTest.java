package net.ximatai.muyun.spring.platform.localization;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ConfiguredTextSeedServiceTest {
    @Test void skipsEveryPreviouslyMaintainedIdentityWithoutPerRowSourceOrCommandReads() {
        var translations = mock(ConfigurationTranslationService.class);
        var source = ConfigurationTextSources.menuTitle("menu", "菜单");
        var maintained = new ConfigurationTranslation(); maintained.setSourceId(source.sourceId());
        maintained.setSourceVersion(source.sourceVersion()); maintained.setLanguageTag("en"); maintained.setDraftText("Maintained draft");
        when(translations.translations("platform.menu")).thenReturn(List.of(maintained));
        when(translations.currentSources("platform.menu")).thenReturn(List.of(source));
        var service = new ConfiguredTextSeedService(translations,
                new DefaultListableBeanFactory().getBeanProvider(ConfiguredTextSeedTargetProvider.class));
        var entry = new ConfiguredTextSeedResources.Entry("platform.menu", source.sourceId(), source.sourceVersion(), "en", "Menu");
        assertThat(service.seed(List.of(entry), null)).isZero();
        verify(translations).translations("platform.menu"); verify(translations).currentSources("platform.menu");
        verifyNoMoreInteractions(translations);
    }
}
