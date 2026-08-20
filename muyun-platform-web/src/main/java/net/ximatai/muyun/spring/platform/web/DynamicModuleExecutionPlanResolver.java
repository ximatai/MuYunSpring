package net.ximatai.muyun.spring.platform.web;

import java.util.Optional;
import java.util.List;

/** Resolves freshly compiled published dynamic execution facts for one module alias. */
@FunctionalInterface
public interface DynamicModuleExecutionPlanResolver {
    Optional<ModuleExecutionPlan> resolve(String moduleAlias);

    /** Dynamic module aliases visible when the delivery runtime starts. */
    default List<String> moduleAliases() {
        return List.of();
    }
}
