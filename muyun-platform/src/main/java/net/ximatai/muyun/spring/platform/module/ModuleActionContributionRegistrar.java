package net.ximatai.muyun.spring.platform.module;

import net.ximatai.muyun.spring.ability.PlatformManagedMutationContext;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
@Transactional
public class ModuleActionContributionRegistrar {
    private final PlatformModuleActionService actionService;

    public ModuleActionContributionRegistrar(PlatformModuleActionService actionService) {
        this.actionService = actionService;
    }

    public void register(ModuleActionContribution contribution) {
        if (contribution == null) {
            return;
        }
        registerAll(List.of(contribution));
    }

    public void registerAll(List<ModuleActionContribution> contributions) {
        registerAll(contributions, true);
    }

    /** Startup restores runtime projections after all declaration contributors have completed. */
    public void registerAllWithoutRuntimeRefresh(List<ModuleActionContribution> contributions) {
        registerAll(contributions, false);
    }

    private void registerAll(List<ModuleActionContribution> contributions, boolean refreshRuntime) {
        if (contributions == null || contributions.isEmpty()) {
            return;
        }
        List<ModuleActionContribution> validContributions = contributions.stream()
                .filter(Objects::nonNull)
                .toList();
        if (validContributions.isEmpty()) {
            return;
        }
        try (TenantContext.Scope ignored = TenantContext.system("register contributed module action")) {
            Set<String> changedModules = new LinkedHashSet<>();
            Map<ContributionSource, List<ModuleActionContribution>> bySource = validContributions.stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            contribution -> new ContributionSource(contribution.sourceType(), contribution.sourceId()),
                            LinkedHashMap::new,
                            java.util.stream.Collectors.toList()));
            actionService.runWithoutRuntimeRefresh(() -> PlatformManagedMutationContext.runAsPlatformManaged(() -> {
                for (List<ModuleActionContribution> sourceContributions : bySource.values()) {
                    sourceContributions.forEach(this::validateContribution);
                    disableStaleActions(sourceContributions, changedModules);
                    sourceContributions.forEach(contribution -> {
                        if (upsert(contribution)) changedModules.add(contribution.moduleAlias());
                    });
                }
            }));
            if (refreshRuntime) {
                // Module presentation can change even when its action declarations remain identical.
                validContributions.forEach(contribution -> changedModules.add(contribution.moduleAlias()));
                changedModules.forEach(actionService::refreshDynamicModuleRuntime);
            }
        }
    }

    private boolean upsert(ModuleActionContribution contribution) {
        PlatformModuleAction action = actionService.findByModuleAliasAndActionCode(
                contribution.moduleAlias(), contribution.actionCode());
        if (action == null) {
            action = new PlatformModuleAction();
            action.setModuleAlias(contribution.moduleAlias());
            action.setActionCode(contribution.actionCode());
        } else if (!sameContribution(action, contribution)) {
            throw new PlatformException("module action contribution conflicts with existing action: "
                    + contribution.moduleAlias() + "." + contribution.actionCode());
        } else if (contribution.equals(declaration(action))) {
            return false;
        }
        apply(action, contribution);
        if (action.getId() == null || action.getId().isBlank()) {
            actionService.insert(action);
        } else {
            actionService.update(action);
        }
        return true;
    }

    public void disableBySource(ModuleActionSourceType sourceType, String sourceId) {
        if (sourceType == null || sourceId == null || sourceId.isBlank()) {
            return;
        }
        try (TenantContext.Scope ignored = TenantContext.system("disable contributed module actions")) {
            PlatformManagedMutationContext.runAsPlatformManaged(() -> {
                for (PlatformModuleAction action : actionService.listBySource(sourceType, sourceId)) {
                    if (Boolean.FALSE.equals(action.getEnabled())) {
                        continue;
                    }
                    action.setEnabled(Boolean.FALSE);
                    actionService.update(action);
                }
            });
        }
    }

    /** Disables code-owned actions whose deployed executor no longer exists. */
    public void disableMissingDynamicActionExecutorActions(Set<String> executorKeys) {
        Set<String> availableKeys = executorKeys == null ? Set.of() : Set.copyOf(executorKeys);
        try (TenantContext.Scope ignored = TenantContext.system("reconcile contributed module actions")) {
            PlatformManagedMutationContext.runAsPlatformManaged(() -> {
                for (PlatformModuleAction action : actionService
                        .listSystemManagedActionsBySourceType(ModuleActionSourceType.CODE_EXTENSION)) {
                    String executorKey = action.getBindingType() == ModuleActionBindingType.DYNAMIC_ACTION_EXECUTOR
                            ? action.getBindingId()
                            : action.getExecutorKey();
                    if ((executorKey != null && availableKeys.contains(executorKey))
                            || Boolean.FALSE.equals(action.getEnabled())) {
                        continue;
                    }
                    action.setEnabled(Boolean.FALSE);
                    actionService.update(action);
                }
            });
        }
    }

    private void validateContribution(ModuleActionContribution contribution) {
        if (contribution.sourceType() == null || contribution.sourceId() == null || contribution.sourceId().isBlank()) {
            throw new PlatformException("module action contribution source must not be blank: "
                    + contribution.moduleAlias() + "." + contribution.actionCode());
        }
        if (contribution.bindingType() != null
                && (contribution.bindingId() == null || contribution.bindingId().isBlank()
                || contribution.bindingAlias() == null || contribution.bindingAlias().isBlank())) {
            throw new PlatformException("module action contribution binding must not be blank: "
                    + contribution.moduleAlias() + "." + contribution.actionCode());
        }
    }

    private void disableStaleActions(List<ModuleActionContribution> contributions, Set<String> changedModules) {
        if (contributions == null || contributions.isEmpty()) {
            return;
        }
        ModuleActionContribution first = contributions.getFirst();
        Set<String> currentActionCodes = contributions.stream()
                .map(ModuleActionContribution::actionCode)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (PlatformModuleAction action : actionService.listBySource(first.sourceType(), first.sourceId())) {
            if (currentActionCodes.contains(action.getActionCode()) || Boolean.FALSE.equals(action.getEnabled())) {
                continue;
            }
            action.setEnabled(Boolean.FALSE);
            actionService.update(action);
            changedModules.add(action.getModuleAlias());
        }
    }

    private ModuleActionContribution declaration(PlatformModuleAction action) {
        return new ModuleActionContribution(action.getModuleAlias(), action.getEntityAlias(), action.getActionCode(),
                action.getPermissionActionCode(), action.getTitle(), action.getCategory(), action.getActionLevel(),
                action.getAccessMode(), Boolean.TRUE.equals(action.getActionAuth()), Boolean.TRUE.equals(action.getDataAuth()),
                action.getDefaultGrantPolicy(), action.getAvailableExpression(), action.getUnavailableMessage(),
                action.getExecutorType(), action.getExecutorKey(), action.getSourceType(), action.getSourceId(),
                action.getSourceVersionId(), action.getBindingType(), action.getBindingId(), action.getBindingAlias(),
                Boolean.TRUE.equals(action.getEnabled()));
    }

    private boolean sameContribution(PlatformModuleAction action, ModuleActionContribution contribution) {
        return Boolean.TRUE.equals(action.getSystemManaged())
                && Objects.equals(action.getSourceType(), contribution.sourceType())
                && Objects.equals(action.getSourceId(), contribution.sourceId())
                && Objects.equals(action.getBindingType(), contribution.bindingType())
                && Objects.equals(action.getBindingId(), contribution.bindingId());
    }

    private void apply(PlatformModuleAction action, ModuleActionContribution contribution) {
        action.setEntityAlias(contribution.entityAlias());
        action.setPermissionActionCode(contribution.permissionActionCode());
        action.setTitle(contribution.title());
        action.setCategory(contribution.category());
        action.setActionLevel(contribution.actionLevel());
        action.setAccessMode(contribution.accessMode());
        action.setActionAuth(contribution.actionAuth());
        action.setDataAuth(contribution.dataAuth());
        action.setDefaultGrantPolicy(contribution.defaultGrantPolicy());
        action.setAvailableExpression(contribution.availableExpression());
        action.setUnavailableMessage(contribution.unavailableMessage());
        action.setExecutorType(contribution.executorType());
        action.setExecutorKey(contribution.executorKey());
        action.setSourceType(contribution.sourceType());
        action.setSourceId(contribution.sourceId());
        action.setSourceVersionId(contribution.sourceVersionId());
        action.setBindingType(contribution.bindingType());
        action.setBindingId(contribution.bindingId());
        action.setBindingAlias(contribution.bindingAlias());
        action.setSystemManaged(Boolean.TRUE);
        action.setEnabled(contribution.enabled());
    }

    private record ContributionSource(ModuleActionSourceType sourceType, String sourceId) {
    }
}
