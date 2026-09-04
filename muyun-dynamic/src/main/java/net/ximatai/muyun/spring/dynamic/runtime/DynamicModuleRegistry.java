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
    private final Map<String, Long> revisions = new LinkedHashMap<>();

    public DynamicModuleRegistry() {
        this(new ModuleDefinitionValidator());
    }

    public DynamicModuleRegistry(ModuleDefinitionValidator validator) {
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
    }

    public void register(ModuleDefinition module) {
        validator.validate(module);
        if (modules.containsKey(module.moduleAlias())) {
            throw new ModuleDefinitionException("duplicate module alias: " + module.moduleAlias());
        }
        modules.put(module.moduleAlias(), module);
        revisions.merge(module.moduleAlias(), 1L, Long::sum);
    }

    public void refresh(ModuleDefinition module) {
        validator.validate(module);
        modules.put(module.moduleAlias(), module);
        revisions.merge(module.moduleAlias(), 1L, Long::sum);
    }

    /** Removes a runtime definition when its source module no longer has a MAIN entity. */
    public Optional<ModuleDefinition> unregister(String moduleAlias) {
        revisions.remove(moduleAlias);
        return Optional.ofNullable(modules.remove(moduleAlias));
    }

    public Optional<ModuleDefinition> findModule(String moduleAlias) {
        return Optional.ofNullable(modules.get(moduleAlias));
    }

    public boolean containsModule(String moduleAlias) {
        return modules.containsKey(moduleAlias);
    }

    /** Monotonic per-module runtime revision, advanced only after a runtime install or refresh. */
    public long revision(String moduleAlias) {
        requireModule(moduleAlias);
        return revisions.getOrDefault(moduleAlias, 0L);
    }

    public ModuleDefinition requireModule(String moduleAlias) {
        return findModule(moduleAlias)
                .orElseThrow(() -> new ModuleDefinitionException("unknown module alias: " + moduleAlias));
    }

    public EntityDefinition requireEntity(String moduleAlias, String entityAlias) {
        return requireModule(moduleAlias).entities().stream()
                .filter(entity -> entity.alias().equals(entityAlias))
                .findFirst()
                .orElseThrow(() -> new ModuleDefinitionException("unknown entity: " + moduleAlias + "." + entityAlias));
    }

    public List<ModuleDefinition> modules() {
        return List.copyOf(modules.values());
    }

    public DynamicModuleDescriptor describe(String moduleAlias) {
        return DynamicModuleDescriptor.from(requireModule(moduleAlias));
    }
}
