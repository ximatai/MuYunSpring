package net.ximatai.muyun.spring.platform.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformPublishedPageCompositionTest {
    @Test
    void shouldSelectTheSameLowestSortedPublishedViewForEachRuntimeSlot() {
        PlatformUiSet listSet = uiSet("set-list", PlatformUiSetType.LIST);
        PlatformUiSet primaryFormSet = uiSet("set-form-primary", PlatformUiSetType.FORM);
        PlatformUiSet alternateFormSet = uiSet("set-form-alternate", PlatformUiSetType.FORM);

        PlatformPublishedPageComposition composition = PlatformPublishedPageComposition.resolve(
                List.of(listSet, primaryFormSet, alternateFormSet),
                List.of(config("list", "set-list", 20), config("primary-form", "set-form-primary", 10),
                        config("alternate-form", "set-form-alternate", 30)),
                PlatformUiClientType.WEB);

        assertThat(composition.listConfig().getId()).isEqualTo("list");
        assertThat(composition.formConfig().getId()).isEqualTo("primary-form");
    }

    private PlatformUiSet uiSet(String id, PlatformUiSetType type) {
        PlatformUiSet uiSet = new PlatformUiSet();
        uiSet.setId(id);
        uiSet.setSetType(type);
        uiSet.setEnabled(Boolean.TRUE);
        return uiSet;
    }

    private PlatformUiConfig config(String id, String uiSetId, int sortOrder) {
        PlatformUiConfig config = new PlatformUiConfig();
        config.setId(id);
        config.setUiSetId(uiSetId);
        config.setClientType(PlatformUiClientType.WEB);
        config.setPublished(Boolean.TRUE);
        config.setEnabled(Boolean.TRUE);
        config.setSortOrder(sortOrder);
        return config;
    }
}
