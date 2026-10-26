package net.ximatai.muyun.spring.platform.application;

import net.ximatai.muyun.spring.common.exception.DeclarationStateException;
import net.ximatai.muyun.spring.common.i18n.DiagnosticText;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Aggregates explicit and scanned static application declarations into one validated catalog. */
public class StaticApplicationDefinitionCatalog {
    private final List<StaticApplicationDefinition> definitions;
    private final List<StaticApplicationDefinitionScanner> scanners;
    private volatile List<StaticApplicationDefinition> cachedDefinitions;
    private volatile Map<String, StaticApplicationDefinition> cachedDefinitionMap;

    public StaticApplicationDefinitionCatalog(List<StaticApplicationDefinition> definitions) {
        this(definitions, List.of());
    }

    public StaticApplicationDefinitionCatalog(List<StaticApplicationDefinition> definitions,
                                              List<StaticApplicationDefinitionScanner> scanners) {
        this.definitions = definitions == null ? List.of() : List.copyOf(definitions);
        this.scanners = scanners == null ? List.of() : List.copyOf(scanners);
    }

    public List<StaticApplicationDefinition> definitions() {
        List<StaticApplicationDefinition> current = cachedDefinitions;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (cachedDefinitions == null) {
                cachedDefinitions = loadDefinitions();
            }
            return cachedDefinitions;
        }
    }

    public Optional<StaticApplicationDefinition> find(String applicationAlias) {
        if (applicationAlias == null || applicationAlias.isBlank()) {
            return Optional.empty();
        }
        Map<String, StaticApplicationDefinition> current = cachedDefinitionMap;
        if (current == null) {
            synchronized (this) {
                if (cachedDefinitionMap == null) {
                    Map<String, StaticApplicationDefinition> byAlias = new HashMap<>();
                    for (StaticApplicationDefinition definition : definitions()) {
                        byAlias.put(definition.alias(), definition);
                    }
                    cachedDefinitionMap = Map.copyOf(byAlias);
                }
                current = cachedDefinitionMap;
            }
        }
        return Optional.ofNullable(current.get(applicationAlias.trim()));
    }

    public boolean hasScanners() {
        return !scanners.isEmpty();
    }

    private List<StaticApplicationDefinition> loadDefinitions() {
        ArrayList<StaticApplicationDefinition> all = new ArrayList<>(definitions);
        for (StaticApplicationDefinitionScanner scanner : scanners) {
            all.addAll(scanner.scan());
        }
        validateDefinitions(all);
        return List.copyOf(all);
    }

    private void validateDefinitions(List<StaticApplicationDefinition> definitions) {
        Set<String> aliases = new HashSet<>();
        for (StaticApplicationDefinition definition : definitions) {
            if (!aliases.add(definition.alias())) {
                throw new DeclarationStateException(DiagnosticText.code("platform.static.diagnostic.duplicate-static-application-definition",
                    "duplicate static application definition: {alias}", Map.of("alias", String.valueOf(definition.alias()))));
            }
        }
    }
}
