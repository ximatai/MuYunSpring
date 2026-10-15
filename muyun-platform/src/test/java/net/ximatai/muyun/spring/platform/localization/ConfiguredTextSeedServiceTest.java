package net.ximatai.muyun.spring.platform.localization;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ConfiguredTextSeedServiceTest {
    @Test void seedsOnlyCurrentVersionMatchedPresetTargetsWithoutInventingATemplateRecord() {
        var translations = mock(ConfigurationTranslationService.class);
        var baseline = net.ximatai.muyun.spring.common.i18n.TextReference.configured("config/preset/title", "预置");
        var target = ConfigurationTextSources.recordText("test.preset", "tenant-a", "title", "预置");
        var renamed = ConfigurationTextSources.recordText("test.preset", "renamed", "title", "用户原文");
        var hidden = ConfigurationTextSources.recordText("test.preset", "hidden", "title", "预置");
        when(translations.translations("test.preset")).thenReturn(List.of());
        when(translations.currentSources("test.preset")).thenReturn(List.of(target, renamed));
        when(translations.seedPublished("test.preset", target, "en", "Preset")).thenReturn(true);
        var factory = new DefaultListableBeanFactory();
        ConfiguredTextSeedTargetProvider provider = (alias, sources, tenant) -> List.of(
                new ConfiguredTextSeedTargetProvider.Target(baseline, target),
                new ConfiguredTextSeedTargetProvider.Target(baseline, renamed),
                new ConfiguredTextSeedTargetProvider.Target(baseline, hidden));
        factory.registerSingleton("presetTargets", provider);
        var service = new ConfiguredTextSeedService(translations, factory.getBeanProvider(ConfiguredTextSeedTargetProvider.class));
        var entry = new ConfiguredTextSeedResources.Entry("test.preset", baseline.sourceId(), baseline.sourceVersion(), "en", "Preset");
        assertThat(service.seed(List.of(entry), null)).isEqualTo(1);
        verify(translations).seedPublished("test.preset", target, "en", "Preset");
        verify(translations, never()).seedPublished(eq("test.preset"), eq(baseline), anyString(), anyString());
        verify(translations, never()).seedPublished(eq("test.preset"), eq(renamed), anyString(), anyString());
        verify(translations, never()).seedPublished(eq("test.preset"), eq(hidden), anyString(), anyString());
    }

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
