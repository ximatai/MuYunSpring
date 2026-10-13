package net.ximatai.muyun.spring.common.i18n;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Immutable code-owned language resources; supports named parameters and explicit fallback diagnostics. */
public final class TextResources {
    public static final String RESOURCE_PREFIX = "META-INF/muyun/i18n/messages_";
    private static final Pattern PARAMETER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_.-]*)}");
    private final LanguageCatalog catalog;
    private final Map<String, Map<String, String>> resources;

    public record Resolution(String text, String requestedTag, String resourceTag, boolean missing, boolean fallback) { }

    public TextResources(LanguageCatalog catalog, Map<String, Map<String, String>> resources) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        Map<String, Map<String, String>> immutable = new LinkedHashMap<>();
        resources.forEach((tag, messages) -> {
            String canonical = LanguageCatalog.canonicalTag(tag);
            if (catalog.languages().stream().noneMatch(language -> language.tag().equals(canonical)))
                throw new IllegalArgumentException("Unregistered resource language: " + tag);
            immutable.put(canonical, Map.copyOf(messages));
        });
        this.resources = Map.copyOf(immutable);
    }

    public static TextResources load(ClassLoader loader, LanguageCatalog catalog) {
        Map<String, Map<String, String>> resources = new LinkedHashMap<>();
        for (var language : catalog.languages()) {
            Map<String, String> messages = new LinkedHashMap<>();
            try {
                for (var urls = loader.getResources(RESOURCE_PREFIX + language.tag() + ".properties"); urls.hasMoreElements();) {
                    Properties properties = new Properties();
                    try (var reader = new InputStreamReader(urls.nextElement().openStream(), StandardCharsets.UTF_8)) { properties.load(reader); }
                    for (String key : properties.stringPropertyNames()) {
                        String value = properties.getProperty(key);
                        String previous = messages.putIfAbsent(key, value);
                        if (previous != null && !previous.equals(value)) throw new IllegalArgumentException("Conflicting language resource: " + language.tag() + "/" + key);
                    }
                }
            } catch (IOException failure) { throw new IllegalStateException("Cannot load language resources: " + language.tag(), failure); }
            resources.put(language.tag(), messages);
        }
        return new TextResources(catalog, resources);
    }

    public Resolution resolve(String key, String defaultText, Map<String, ?> arguments) {
        return resolve(key, defaultText, arguments, LanguageContext.currentTag());
    }

    public Resolution resolve(String key, String defaultText, Map<String, ?> arguments, String requestedTag) {
        String selected = catalog.select(requestedTag);
        for (String tag : catalog.fallbackTags(selected)) {
            String message = resources.getOrDefault(tag, Map.of()).get(key);
            if (message != null) return new Resolution(interpolate(message, arguments), selected, tag, false, !tag.equals(selected));
        }
        return new Resolution(interpolate(defaultText == null ? key : defaultText, arguments), selected, null, true, true);
    }

    private String interpolate(String text, Map<String, ?> arguments) {
        if (arguments == null || arguments.isEmpty()) return text;
        Matcher matcher = PARAMETER.matcher(text);
        return matcher.replaceAll(match -> arguments.containsKey(match.group(1))
                ? Matcher.quoteReplacement(Objects.toString(arguments.get(match.group(1)), "")) : Matcher.quoteReplacement(match.group()));
    }
}
