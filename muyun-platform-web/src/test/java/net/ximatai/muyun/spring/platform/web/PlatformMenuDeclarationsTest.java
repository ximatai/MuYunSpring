package net.ximatai.muyun.spring.platform.web;

import org.junit.jupiter.api.Test;
import net.ximatai.muyun.spring.common.platform.ActionAccessMode;
import static org.assertj.core.api.Assertions.*;

class PlatformMenuDeclarationsTest {
    @Test void rejectsAmbiguousEntryIdsAccessModesAndMalformedParametersAtScanTime() {
        assertThatThrownBy(() -> PlatformMenuDeclarations.of(MissingId.class)).hasMessageContaining("explicit ids");
        assertThatThrownBy(() -> PlatformMenuDeclarations.of(DuplicateId.class)).hasMessageContaining("Duplicate");
        assertThatThrownBy(() -> PlatformMenuDeclarations.of(MixedAccess.class)).hasMessageContaining("accessMode");
        for (var type : new Class<?>[]{InvalidJson.class, ArrayJson.class, TrailingJson.class})
            assertThatThrownBy(() -> PlatformMenuDeclarations.of(type)).hasMessageContaining("entryParamsJson");
    }

    @Test void retainsStandardPresentationDefaultsAndBusinessParametersAsDifferentFacts() {
        var entry = PlatformMenuDeclarations.of(Configured.class).getFirst();
        assertThat(entry.defaultUiConfigId()).isEqualTo("list-ui");
        assertThat(entry.defaultQueryTemplateId()).isEqualTo("current-query");
        assertThat(entry.entryParamsJson()).isEqualTo("{\"view\":\"current\"}");
    }

    @PlatformMenu(parent = "p") @PlatformMenu(id = "other", parent = "p") static class MissingId {}
    @PlatformMenu(id = "same", parent = "p", title = "A") @PlatformMenu(id = "same", parent = "p", title = "B") static class DuplicateId {}
    @PlatformMenu(id = "a", parent = "p") @PlatformMenu(id = "b", parent = "p", accessMode = ActionAccessMode.LOGIN_REQUIRED) static class MixedAccess {}
    @PlatformMenu(parent = "p", entryParamsJson = "not-json") static class InvalidJson {}
    @PlatformMenu(parent = "p", entryParamsJson = "[]") static class ArrayJson {}
    @PlatformMenu(parent = "p", entryParamsJson = "{} {}") static class TrailingJson {}
    @PlatformMenu(parent = "p", defaultUiConfigId = "list-ui", defaultQueryTemplateId = "current-query", entryParamsJson = "{\"view\":\"current\"}") static class Configured {}
}
