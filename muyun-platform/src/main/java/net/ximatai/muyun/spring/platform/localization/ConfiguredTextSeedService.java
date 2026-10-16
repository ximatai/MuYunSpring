package net.ximatai.muyun.spring.platform.localization;

import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import java.util.*;

/** Applies version-matched baselines through ordinary draft/publication commands, preserving maintained rows. */
@Service
public class ConfiguredTextSeedService {
    private final ConfigurationTranslationService translations;
    private final ObjectProvider<ConfiguredTextSeedTargetProvider> targets;

    public ConfiguredTextSeedService(ConfigurationTranslationService translations, ObjectProvider<ConfiguredTextSeedTargetProvider> targets) {
        this.translations = translations;
        this.targets = targets;
    }

    public int seedDeployed() {
        return seed(ConfiguredTextSeedResources.load(getClass().getClassLoader(), translations.languageCatalog()), null);
    }

    public int seedTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) throw new IllegalArgumentException("tenantId must not be blank");
        return seed(ConfiguredTextSeedResources.load(getClass().getClassLoader(), translations.languageCatalog()), tenantId);
    }

    int seed(List<ConfiguredTextSeedResources.Entry> entries, String tenantId) {
        int installed = 0;
        try (var scope = TenantContext.system("deployed configuration text baselines")) {
            for (String alias : entries.stream().map(ConfiguredTextSeedResources.Entry::moduleAlias).distinct().toList()) {
                var existing = new HashSet<SeedIdentity>();
                translations.translations(alias).forEach(row -> existing.add(
                        new SeedIdentity(row.getSourceId(), row.getSourceVersion(), row.getLanguageTag())));
                var sources = translations.currentSources(alias);
                Map<String, TextReference> current = new HashMap<>();
                sources.forEach(source -> current.put(source.sourceId(), source));
                var derived = targets.orderedStream().flatMap(provider -> provider.derivedTargets(alias, sources, tenantId).stream()).toList();
                for (var entry : entries) {
                    if (!entry.moduleAlias().equals(alias)) continue;
                    var original = current.get(entry.sourceId());
                    // Presets may have a producer-declared baseline without a persisted template record.
                    if (original == null) original = derived.stream().map(ConfiguredTextSeedTargetProvider.Target::baseline)
                            .filter(source -> source.sourceKind() == TextReference.SourceKind.CONFIG
                                    && source.sourceId().equals(entry.sourceId()) && source.sourceVersion().equals(entry.sourceVersion()))
                            .findFirst().orElse(null);
                    if (original == null || !original.sourceVersion().equals(entry.sourceVersion())) continue;
                    final var baseline = original;
                    var destinations = new LinkedHashSet<TextReference>();
                    if (tenantId == null && baseline.equals(current.get(baseline.sourceId()))) destinations.add(baseline);
                    derived.stream().filter(target -> target.baseline().equals(baseline))
                            .map(ConfiguredTextSeedTargetProvider.Target::target)
                            .filter(target -> target.equals(current.get(target.sourceId())))
                            .filter(target -> target.sourceVersion().equals(baseline.sourceVersion()) && target.originalText().equals(baseline.originalText()))
                            .forEach(destinations::add);
                    for (var destination : destinations) {
                        var identity = new SeedIdentity(destination.sourceId(), destination.sourceVersion(), entry.languageTag());
                        if (!existing.add(identity)) continue;
                        if (translations.seedPublished(alias, destination, entry.languageTag(), entry.text())) installed++;
                    }
                }
            }
        }
        return installed;
    }
    private record SeedIdentity(String sourceId, String sourceVersion, String languageTag) { }
}
