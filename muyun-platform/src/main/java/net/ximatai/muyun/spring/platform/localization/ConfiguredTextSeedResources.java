package net.ximatai.muyun.spring.platform.localization;

import net.ximatai.muyun.spring.common.i18n.LanguageCatalog;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Deployed configuration baselines are addressed by module, source identity and exact original version. */
public final class ConfiguredTextSeedResources {
    private ConfiguredTextSeedResources() { }

    public record Entry(String moduleAlias, String sourceId, String sourceVersion, String languageTag, String text) { }

    public static List<Entry> load(ClassLoader loader, LanguageCatalog catalog) {
        Map<String, Entry> entries = new LinkedHashMap<>();
        try {
            for (var language : catalog.languages()) {
                if (!language.enabled()) continue;
                var urls = loader.getResources("META-INF/muyun/i18n/configured_messages_" + language.tag() + ".properties");
                while (urls.hasMoreElements()) {
                    var url = urls.nextElement();
                    var properties = new Properties();
                    try (var input = new InputStreamReader(url.openStream(), StandardCharsets.UTF_8)) { properties.load(input); }
                    for (String key : properties.stringPropertyNames().stream().sorted().toList()) {
                        String[] identity = key.split("\\|", -1);
                        String text = properties.getProperty(key);
                        if (identity.length != 3 || identity[1].isBlank() || identity[2].isBlank() || text.isBlank())
                            throw new IllegalStateException("Invalid configured text baseline: " + key);
                        var entry = new Entry(PlatformNameRules.requireModuleAlias(identity[0]), identity[1], identity[2], language.tag(), text);
                        var previous = entries.putIfAbsent(language.tag() + "|" + key, entry);
                        if (previous != null && !previous.equals(entry))
                            throw new IllegalStateException("Conflicting configured text baseline: " + key);
                    }
                }
            }
        } catch (java.io.IOException failure) { throw new IllegalStateException("Cannot load configured text baselines", failure); }
        return List.copyOf(entries.values());
    }
}
