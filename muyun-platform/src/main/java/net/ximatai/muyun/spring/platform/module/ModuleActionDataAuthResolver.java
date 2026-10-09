package net.ximatai.muyun.spring.platform.module;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.metadata.MetadataService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationService;
import net.ximatai.muyun.spring.platform.metadata.RelationRole;
import net.ximatai.muyun.spring.platform.reference.StaticAbilityCatalog;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** Resolves capability-dependent defaults without changing contributed intent or governance overrides. */
@Service
public class ModuleActionDataAuthResolver {
    private final PlatformModuleService modules;
    private final ModuleMetadataRelationService relations;
    private final MetadataService metadata;
    private final StaticAbilityCatalog staticAbilities;

    public ModuleActionDataAuthResolver(PlatformModuleService modules, ModuleMetadataRelationService relations,
                                      MetadataService metadata, StaticAbilityCatalog staticAbilities) {
        this.modules = Objects.requireNonNull(modules, "modules");
        this.relations = Objects.requireNonNull(relations, "relations");
        this.metadata = Objects.requireNonNull(metadata, "metadata");
        this.staticAbilities = Objects.requireNonNull(staticAbilities, "staticAbilities");
    }

    public boolean resolve(PlatformModuleAction action) {
        if (!usesCapabilityDefault(action)) return action.effectiveDataAuth();
        try (TenantContext.Scope ignored = TenantContext.system("resolve module action capability default")) {
            PlatformModule module = modules.select(action.getModuleAlias());
            if (module == null) throw new PlatformException("Module action requires existing module: " + action.getModuleAlias());
            if (module.getModuleKind() == ModuleKind.DYNAMIC) {
                Criteria criteria = Criteria.of().eq("moduleAlias", action.getModuleAlias());
                if (action.getEntityAlias() == null || action.getEntityAlias().isBlank()) {
                    criteria.eq("relationRole", RelationRole.MAIN);
                }
                var entity = relations.list(criteria, new PageRequest(0, Integer.MAX_VALUE), Sort.asc("sortOrder")).stream()
                        .map(relation -> metadata.select(relation.getMetadataId()))
                        .filter(Objects::nonNull)
                        .filter(value -> action.getEntityAlias() == null || action.getEntityAlias().isBlank()
                                || action.getEntityAlias().equals(value.getAlias()))
                        .findFirst().orElseThrow(() -> new PlatformException(
                                "Module action requires configured entity: " + action.getModuleAlias()));
                return resolve(action, Boolean.TRUE.equals(entity.getDataScopeEnabled()));
            }
            return resolve(action, staticAbilities.abilities().stream()
                    .filter(ability -> action.getModuleAlias().equals(ability.getModuleAlias()))
                    .anyMatch(DataScopeAbility.class::isInstance));
        }
    }

    public static boolean resolve(PlatformModuleAction action, boolean supportsDataScope) {
        return usesCapabilityDefault(action)
                ? Boolean.TRUE.equals(action.getDataAuth()) && supportsDataScope
                : action.effectiveDataAuth();
    }

    private static boolean usesCapabilityDefault(PlatformModuleAction action) {
        return Boolean.TRUE.equals(action.getSystemManaged())
                && action.getSourceType() == ModuleActionSourceType.WORKFLOW_RUNTIME
                && action.getDataAuthOverride() == null;
    }
}
