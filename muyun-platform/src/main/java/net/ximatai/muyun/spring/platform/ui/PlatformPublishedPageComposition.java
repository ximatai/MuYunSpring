package net.ximatai.muyun.spring.platform.ui;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Selects the list and form configurations consumed by the standard module page runtime.
 *
 * <p>The selection order intentionally preserves the established runtime rule: among enabled,
 * published configurations for one client, the lowest sort order for each view kind wins. Keeping
 * this rule here prevents publishing validation from reasoning about a different form composition
 * than the runtime actually renders.</p>
 */
public record PlatformPublishedPageComposition(PlatformUiConfig listConfig, PlatformUiConfig formConfig) {
    public static PlatformPublishedPageComposition resolve(PlatformPageConfigSnapshot snapshot,
                                                           PlatformUiClientType clientType) {
        return resolve(snapshot.uiSets(), snapshot.uiConfigs(), clientType);
    }

    public static PlatformPublishedPageComposition resolve(List<PlatformUiSet> uiSets,
                                                           List<PlatformUiConfig> uiConfigs,
                                                           PlatformUiClientType clientType) {
        PlatformUiClientType targetClient = clientType == null ? PlatformUiClientType.WEB : clientType;
        Map<String, PlatformUiSet> setsById = (uiSets == null ? List.<PlatformUiSet>of() : uiSets).stream()
                .collect(Collectors.toMap(PlatformUiSet::getId, Function.identity(), (left, ignored) -> left));
        List<PlatformUiConfig> candidates = (uiConfigs == null ? List.<PlatformUiConfig>of() : uiConfigs).stream()
                .filter(config -> Boolean.TRUE.equals(config.getPublished()))
                .filter(config -> !Boolean.FALSE.equals(config.getEnabled()))
                .filter(config -> config.getClientType() == targetClient)
                .filter(config -> {
                    PlatformUiSet uiSet = setsById.get(config.getUiSetId());
                    return uiSet != null && Boolean.TRUE.equals(uiSet.getEnabled());
                })
                .sorted(Comparator.comparing(PlatformUiConfig::getSortOrder,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        return new PlatformPublishedPageComposition(
                firstOfType(candidates, setsById, PlatformUiSetType.LIST),
                firstOfType(candidates, setsById, PlatformUiSetType.FORM));
    }

    private static PlatformUiConfig firstOfType(List<PlatformUiConfig> candidates,
                                                Map<String, PlatformUiSet> setsById,
                                                PlatformUiSetType type) {
        return candidates.stream()
                .filter(config -> setsById.get(config.getUiSetId()).getSetType() == type)
                .findFirst()
                .orElse(null);
    }
}
