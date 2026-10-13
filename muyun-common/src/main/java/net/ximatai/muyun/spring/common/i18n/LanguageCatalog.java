package net.ximatai.muyun.spring.common.i18n;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Deployment resources register languages; business models do not enumerate them. */
public final class LanguageCatalog {
    public static final String RESOURCE = "META-INF/muyun/i18n/languages.properties";
    private final String defaultTag;
    private final Map<String, Language> languages;

    public record Language(String tag, String nativeName, String fallbackTag, String direction, boolean enabled) {
        public Language(String tag, String nativeName, String fallbackTag, String direction) {
            this(tag, nativeName, fallbackTag, direction, true);
        }
        public Language {
            tag = canonicalTag(tag);
            Objects.requireNonNull(nativeName, "nativeName");
            if (nativeName.isBlank()) throw new IllegalArgumentException("Language name must not be blank");
            fallbackTag = fallbackTag == null || fallbackTag.isBlank() ? null : canonicalTag(fallbackTag);
            direction = direction == null ? "ltr" : direction;
            if (!Set.of("ltr", "rtl").contains(direction)) throw new IllegalArgumentException("Invalid text direction");
        }
    }

    public LanguageCatalog(String defaultTag, Collection<Language> definitions) {
        this(defaultTag, definitions, null);
    }

    private LanguageCatalog(String defaultTag, Collection<Language> definitions, Collection<String> enabledTags) {
        this.defaultTag = canonicalTag(defaultTag);
        Map<String, Language> registered = new LinkedHashMap<>();
        for (Language language : definitions) {
            Language previous = registered.putIfAbsent(language.tag(), language);
            if (previous != null && !previous.equals(language)) throw new IllegalArgumentException("Conflicting language: " + language.tag());
        }
        if (enabledTags != null) {
            Set<String> selected = new HashSet<>();
            for (String tag : enabledTags) {
                String canonical = canonicalTag(tag);
                if (!registered.containsKey(canonical)) throw new IllegalArgumentException("Unregistered enabled language: " + canonical);
                selected.add(canonical);
            }
            registered.replaceAll((tag, language) -> new Language(tag, language.nativeName(), language.fallbackTag(),
                    language.direction(), selected.contains(tag)));
        }
        if (!registered.containsKey(this.defaultTag) || !registered.get(this.defaultTag).enabled()) throw new IllegalArgumentException("Default language is not registered");
        languages = Collections.unmodifiableMap(registered);
        for (String tag : languages.keySet()) fallbackTags(tag);
    }

    public static LanguageCatalog load(ClassLoader loader, String defaultTag) {
        return load(loader, defaultTag, null);
    }

    /** Resource defaults and deployment policy are combined before validating the effective default. */
    public static LanguageCatalog load(ClassLoader loader, String defaultTag, Collection<String> enabledTags) {
        List<Language> definitions = new ArrayList<>();
        try {
            for (var urls = loader.getResources(RESOURCE); urls.hasMoreElements();) {
                Properties properties = new Properties();
                try (var reader = new InputStreamReader(urls.nextElement().openStream(), StandardCharsets.UTF_8)) { properties.load(reader); }
                properties.stringPropertyNames().stream().filter(key -> key.endsWith(".name")).sorted().forEach(key -> {
                    String tag = key.substring(0, key.length() - 5);
                    definitions.add(new Language(tag, properties.getProperty(key), properties.getProperty(tag + ".fallback"), properties.getProperty(tag + ".direction", "ltr"), enabled(properties, tag)));
                });
            }
        } catch (IOException failure) { throw new IllegalStateException("Cannot load language catalog", failure); }
        return new LanguageCatalog(defaultTag, definitions, enabledTags);
    }

    /** Deployment policy changes selection eligibility while preserving registered fallback resources. */
    public LanguageCatalog withEnabledLanguages(Collection<String> tags) {
        return new LanguageCatalog(defaultTag, languages.values(), Objects.requireNonNull(tags, "enabledLanguages"));
    }

    private static boolean enabled(Properties properties, String tag) {
        String value = properties.getProperty(tag + ".enabled", "true").trim();
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value))
            throw new IllegalArgumentException("Invalid language enabled flag: " + tag);
        return Boolean.parseBoolean(value);
    }

    public String defaultTag() { return defaultTag; }
    public List<Language> languages() { return List.copyOf(languages.values()); }

    public String select(String acceptLanguage) {
        if (acceptLanguage == null || acceptLanguage.isBlank()) return defaultTag;
        try {
            var ranges = Locale.LanguageRange.parse(acceptLanguage);
            for (var range : ranges) {
                if (range.getWeight() == 0) continue;
                String selected = Locale.lookupTag(List.of(range), languages.values().stream().filter(Language::enabled).map(Language::tag).toList());
                if (selected != null) return selected;
            }
        } catch (IllegalArgumentException ignored) { /* Invalid client input falls back to the deployed default. */ }
        return defaultTag;
    }

    public List<String> fallbackTags(String requestedTag) {
        String current = languages.containsKey(requestedTag) ? requestedTag : select(requestedTag);
        LinkedHashSet<String> chain = new LinkedHashSet<>();
        while (current != null) {
            if (!chain.add(current)) throw new IllegalArgumentException("Language fallback cycle: " + current);
            Language language = languages.get(current);
            if (language == null) throw new IllegalArgumentException("Unregistered fallback language: " + current);
            current = language.fallbackTag();
        }
        chain.add(defaultTag);
        return List.copyOf(chain);
    }

    static String canonicalTag(String tag) {
        String canonical = new Locale.Builder().setLanguageTag(Objects.requireNonNull(tag, "languageTag")).build().toLanguageTag();
        if ("und".equals(canonical)) throw new IllegalArgumentException("Language tag must identify a language");
        return canonical;
    }
}
