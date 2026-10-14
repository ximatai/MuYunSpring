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
                var sources = translations.currentSources(alias);
                Map<String, TextReference> current = new HashMap<>();
                sources.forEach(source -> current.put(source.sourceId(), source));
                var derived = targets.orderedStream().flatMap(provider -> provider.derivedTargets(alias, sources, tenantId).stream()).toList();
                for (var entry : entries) {
                    if (!entry.moduleAlias().equals(alias)) continue;
                    var original = current.get(entry.sourceId());
                    if (original == null || !original.sourceVersion().equals(entry.sourceVersion())) continue;
                    var destinations = new LinkedHashSet<TextReference>();
                    if (tenantId == null) destinations.add(original);
                    derived.stream().filter(target -> target.baseline().equals(original))
                            .map(ConfiguredTextSeedTargetProvider.Target::target)
                            .filter(target -> target.equals(current.get(target.sourceId())))
                            .filter(target -> target.sourceVersion().equals(original.sourceVersion()) && target.originalText().equals(original.originalText()))
                            .forEach(destinations::add);
                    for (var destination : destinations)
                        if (translations.seedPublished(alias, destination, entry.languageTag(), entry.text())) installed++;
                }
            }
        }
        return installed;
    }
}
