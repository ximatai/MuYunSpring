package net.ximatai.muyun.spring.platform.localization;

import net.ximatai.muyun.spring.common.i18n.LanguageCatalog;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ConfiguredTextSeedResourcesTest {
    @TempDir Path directory;

    @Test void acceptsAdditionalDeploymentLanguagesAndRejectsConflictingBundles() throws Exception {
        var catalog = new LanguageCatalog("en", List.of(new LanguageCatalog.Language("en", "English", null, "ltr"),
                new LanguageCatalog.Language("de", "Deutsch", "en", "ltr"),
                new LanguageCatalog.Language("fr", "Français", "en", "ltr", false)));
        String key = "test.module|config/test/title|" + TextReference.versionOf("标题");
        Path first = directory.resolve("first/META-INF/muyun/i18n");
        Path second = directory.resolve("second/META-INF/muyun/i18n");
        Files.createDirectories(first); Files.createDirectories(second);
        Files.writeString(first.resolve("configured_messages_de.properties"), key + "=Titel\n");
        Files.writeString(first.resolve("configured_messages_fr.properties"), key + "=Titre\n");
        try (var loader = new URLClassLoader(new java.net.URL[]{directory.resolve("first").toUri().toURL()}, null)) {
            assertThat(ConfiguredTextSeedResources.load(loader, catalog)).singleElement().satisfies(entry -> {
                assertThat(entry.languageTag()).isEqualTo("de");
                assertThat(entry.text()).isEqualTo("Titel");
                assertThat(entry.sourceVersion()).isEqualTo(TextReference.versionOf("标题"));
            });
        }
        Files.writeString(second.resolve("configured_messages_de.properties"), key + "=Anderer Titel\n");
        try (var loader = new URLClassLoader(new java.net.URL[]{directory.resolve("first").toUri().toURL(), directory.resolve("second").toUri().toURL()}, null)) {
            assertThatThrownBy(() -> ConfiguredTextSeedResources.load(loader, catalog)).hasMessageContaining("Conflicting configured text baseline");
        }
    }
}
