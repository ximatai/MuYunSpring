package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionException;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinitionValidator;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicModuleDescriptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public class DynamicModuleRegistry {
    private final ModuleDefinitionValidator validator;
    private final Map<String, ModuleDefinition> modules = new LinkedHashMap<>();
    private Map<String, ModuleDefinition> snapshot = Map.of();
    private final Map<String, Long> revisions = new LinkedHashMap<>();

    public DynamicModuleRegistry() {
        this(new ModuleDefinitionValidator());
    }

    public DynamicModuleRegistry(ModuleDefinitionValidator validator) {
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
    }

    public synchronized void register(ModuleDefinition module) {
        validator.validate(module);
        if (modules.containsKey(module.moduleAlias())) {
            throw new ModuleDefinitionException("duplicate module alias: " + module.moduleAlias());
        }
        modules.put(module.moduleAlias(), module);
        snapshot = Map.copyOf(modules);
        revisions.merge(module.moduleAlias(), 1L, Long::sum);
    }

    public synchronized void refresh(ModuleDefinition module) {
        validator.validate(module);
        modules.put(module.moduleAlias(), module);
        snapshot = Map.copyOf(modules);
        revisions.merge(module.moduleAlias(), 1L, Long::sum);
    }

    /** Removes a runtime definition when its source module no longer has a MAIN entity. */
    public synchronized Optional<ModuleDefinition> unregister(String moduleAlias) {
        ModuleDefinition removed = modules.remove(moduleAlias);
        snapshot = Map.copyOf(modules);
        return Optional.ofNullable(removed);
    }

    public synchronized Optional<ModuleDefinition> findModule(String moduleAlias) {
        return Optional.ofNullable(modules.get(moduleAlias));
    }

    public synchronized boolean containsModule(String moduleAlias) {
        return modules.containsKey(moduleAlias);
    }

    /** Monotonic per-module runtime revision, advanced only after a runtime install or refresh. */
    public synchronized long revision(String moduleAlias) {
        requireModule(moduleAlias);
        return revisions.getOrDefault(moduleAlias, 0L);
    }

    public synchronized ModuleDefinition requireModule(String moduleAlias) {
        return findModule(moduleAlias)
                .orElseThrow(() -> new ModuleDefinitionException("unknown module alias: " + moduleAlias));
    }

    public synchronized EntityDefinition requireEntity(String moduleAlias, String entityAlias) {
        return requireModule(moduleAlias).entities().stream()
                .filter(entity -> entity.alias().equals(entityAlias))
                .findFirst()
                .orElseThrow(() -> new ModuleDefinitionException("unknown entity: " + moduleAlias + "." + entityAlias));
    }

    /** Immutable definitions for one operation, including its nested references and children. */
    synchronized Map<String, ModuleDefinition> snapshot() { return snapshot; }

    public synchronized List<ModuleDefinition> modules() {
        return List.copyOf(modules.values());
    }

    public synchronized DynamicModuleDescriptor describe(String moduleAlias) {
        return DynamicModuleDescriptor.from(requireModule(moduleAlias));
    }
}
