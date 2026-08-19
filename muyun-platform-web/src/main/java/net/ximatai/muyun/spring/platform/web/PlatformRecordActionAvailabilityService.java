package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.DataScopeAbility;
import net.ximatai.muyun.spring.ability.PlatformManagedProtectionAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.platform.PlatformActionLevel;
import net.ximatai.muyun.spring.common.platform.RecordActionAvailabilityContributor;
import net.ximatai.muyun.spring.common.platform.RecordActionAvailabilityDecision;
import net.ximatai.muyun.spring.common.schema.StandardEntitySchema;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicActionDescriptor;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicActionAvailability;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecord;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordActionAvailability;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordService;
import net.ximatai.muyun.spring.platform.module.ModuleKind;
import net.ximatai.muyun.spring.web.TenantRequestScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PlatformRecordActionAvailabilityService {
    public static final int MAX_BATCH_RECORDS = 100;
    private final PlatformModuleRuntimeContextService runtimeContextService;
    private final DynamicRecordService dynamicRecordService;
    private final TenantRequestScope tenantRequestScope;
    private final List<CrudAbility<?>> crudAbilities;
    private final List<RecordActionAvailabilityContributor> availabilityContributors;

    @Autowired
    public PlatformRecordActionAvailabilityService(PlatformModuleRuntimeContextService runtimeContextService,
                                                   ObjectProvider<DynamicRecordService> dynamicRecordService,
                                                   ObjectProvider<TenantRequestScope> tenantRequestScope,
                                                   ObjectProvider<CrudAbility<?>> crudAbilities,
                                                   ObjectProvider<RecordActionAvailabilityContributor> availabilityContributors) {
        this(runtimeContextService,
                dynamicRecordService == null ? null : dynamicRecordService.getIfAvailable(),
                tenantRequestScope == null ? null : tenantRequestScope.getIfAvailable(),
                crudAbilities == null ? List.of() : crudAbilities.orderedStream().toList(),
                availabilityContributors == null ? List.of() : availabilityContributors.orderedStream().toList());
    }

    PlatformRecordActionAvailabilityService(PlatformModuleRuntimeContextService runtimeContextService,
                                            DynamicRecordService dynamicRecordService,
                                            TenantRequestScope tenantRequestScope,
                                            List<CrudAbility<?>> crudAbilities,
                                            List<RecordActionAvailabilityContributor> availabilityContributors) {
        this.runtimeContextService = runtimeContextService;
        this.dynamicRecordService = dynamicRecordService;
        this.tenantRequestScope = tenantRequestScope;
        this.crudAbilities = crudAbilities == null ? List.of() : List.copyOf(crudAbilities);
        this.availabilityContributors = availabilityContributors == null
                ? List.of()
                : List.copyOf(availabilityContributors);
    }

    public PlatformRecordActionAvailability recordActions(String moduleAlias, String recordId) {
        String validModuleAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        String validRecordId = requireRecordId(recordId);
        PlatformModuleRuntimeContext context = runtimeContextService.context(validModuleAlias);
        if (context.moduleKind() == ModuleKind.DYNAMIC) {
            return dynamicRecordActions(validModuleAlias, validRecordId, context);
        }
        return staticRecordActions(validModuleAlias, validRecordId, context);
    }

    /**
     * Bounded explorer projection. Existing contributors remain per-record so business modules
     * do not need a second optional batch contract just to participate in a shared UI surface.
     */
    public List<PlatformRecordActionAvailability> recordActions(String moduleAlias, List<String> recordIds) {
        if (recordIds == null || recordIds.isEmpty()) {
            return List.of();
        }
        List<String> validRecordIds = recordIds.stream()
                .map(this::requireRecordId)
                .distinct()
                .toList();
        if (validRecordIds.size() > MAX_BATCH_RECORDS) {
            throw new IllegalArgumentException("recordIds must contain at most " + MAX_BATCH_RECORDS + " items");
        }
        String validModuleAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        PlatformModuleRuntimeContext context = runtimeContextService.context(validModuleAlias);
        if (context.moduleKind() == ModuleKind.DYNAMIC) {
            return dynamicRecordActions(validModuleAlias, validRecordIds, context);
        }
        return staticRecordActions(validModuleAlias, validRecordIds, context);
    }

    private List<PlatformRecordActionAvailability> staticRecordActions(String moduleAlias,
                                                                        List<String> recordIds,
                                                                        PlatformModuleRuntimeContext context) {
        List<PlatformModuleRuntimeAction> actions = context.actions().stream()
                .filter(this::isStaticRecordAvailabilityAction)
                .toList();
        Map<String, Set<String>> dataScopedIdsByAction = new LinkedHashMap<>();
        Set<String> dataScopedUnion = new LinkedHashSet<>();
        for (PlatformModuleRuntimeAction action : actions) {
            Set<String> scopedIds = action.authorized()
                    ? dataScopedRecordIds(moduleAlias, policy(action), recordIds)
                    : Set.of();
            dataScopedIdsByAction.put(action.actionCode(), scopedIds);
            dataScopedUnion.addAll(scopedIds);
        }
        Map<String, EntityContract> records = dataScopedUnion.isEmpty()
                ? Map.of()
                : platformManagedRecords(moduleAlias, List.copyOf(dataScopedUnion));
        Map<String, List<PlatformRecordActionAvailability.Action>> actionsByRecordId = new LinkedHashMap<>();
        for (String recordId : recordIds) {
            actionsByRecordId.put(recordId, new java.util.ArrayList<>());
        }
        for (PlatformModuleRuntimeAction action : actions) {
            Set<String> dataScopedIds = dataScopedIdsByAction.get(action.actionCode());
            Map<String, Optional<RecordActionAvailabilityDecision>> businessDecisions = action.authorized()
                    ? businessAvailability(moduleAlias, action.actionCode(), List.copyOf(dataScopedIds))
                    : Map.of();
            for (String recordId : recordIds) {
                PlatformRecordActionAvailability.Action decision;
                if (!action.authorized()) {
                    decision = unavailable(action.actionCode(), "no action auth");
                } else if (!dataScopedIds.contains(recordId)) {
                    decision = unavailable(action.actionCode(), "no data auth");
                } else {
                    Optional<RecordActionAvailabilityDecision> business = businessDecisions.getOrDefault(recordId,
                            Optional.empty());
                    if (business.isPresent() && !business.get().available()) {
                        decision = unavailable(action.actionCode(), business.get().reason());
                    } else {
                        Optional<RecordActionAvailabilityDecision> protection = platformManagedAvailability(moduleAlias,
                                action.actionCode(), records.get(recordId));
                        decision = protection.filter(value -> !value.available())
                                .map(value -> unavailable(action.actionCode(), value.reason()))
                                .orElseGet(() -> new PlatformRecordActionAvailability.Action(action.actionCode(), true, null));
                    }
                }
                actionsByRecordId.get(recordId).add(decision);
            }
        }
        return recordIds.stream()
                .map(recordId -> new PlatformRecordActionAvailability(recordId, actionsByRecordId.get(recordId)))
                .toList();
    }

    private PlatformRecordActionAvailability staticRecordActions(String moduleAlias,
                                                                 String recordId,
                                                                 PlatformModuleRuntimeContext context) {
        List<PlatformRecordActionAvailability.Action> actions = context.actions().stream()
                .filter(this::isStaticRecordAvailabilityAction)
                .map(action -> staticRecordAction(moduleAlias, recordId, action))
                .toList();
        return new PlatformRecordActionAvailability(recordId, actions);
    }

    private PlatformRecordActionAvailability dynamicRecordActions(String moduleAlias,
                                                                 String recordId,
                                                                 PlatformModuleRuntimeContext context) {
        if (dynamicRecordService == null) {
            throw new PlatformException("dynamic record service is not configured");
        }
        if (tenantRequestScope == null) {
            throw new PlatformException("tenant request scope is not configured");
        }
        tenantRequestScope.requireActiveTenant(moduleAlias);
        String entityAlias = context.mainEntityAlias() == null || context.mainEntityAlias().isBlank()
                ? dynamicRecordService.mainEntityAlias(moduleAlias)
                : context.mainEntityAlias();
        Map<String, DynamicActionDescriptor> descriptors = dynamicRecordService.actions(moduleAlias).stream()
                .collect(Collectors.toMap(DynamicActionDescriptor::code, Function.identity(), (left, right) -> left));
        DynamicRecordHolder recordHolder = new DynamicRecordHolder();
        List<PlatformRecordActionAvailability.Action> actions = context.actions().stream()
                .filter(this::isDynamicRecordAvailabilityAction)
                .filter(action -> descriptors.containsKey(action.actionCode()))
                .map(action -> dynamicRecordAction(moduleAlias, entityAlias, recordId, recordHolder, action))
                .toList();
        return new PlatformRecordActionAvailability(recordId, actions);
    }

    private List<PlatformRecordActionAvailability> dynamicRecordActions(String moduleAlias,
                                                                          List<String> recordIds,
                                                                          PlatformModuleRuntimeContext context) {
        if (dynamicRecordService == null) {
            throw new PlatformException("dynamic record service is not configured");
        }
        if (tenantRequestScope == null) {
            throw new PlatformException("tenant request scope is not configured");
        }
        tenantRequestScope.requireActiveTenant(moduleAlias);
        String entityAlias = context.mainEntityAlias() == null || context.mainEntityAlias().isBlank()
                ? dynamicRecordService.mainEntityAlias(moduleAlias)
                : context.mainEntityAlias();
        Set<String> declaredActionCodes = dynamicRecordService.actions(moduleAlias).stream()
                .map(DynamicActionDescriptor::code)
                .collect(Collectors.toSet());
        List<PlatformModuleRuntimeAction> actions = context.actions().stream()
                .filter(this::isDynamicRecordAvailabilityAction)
                .filter(action -> declaredActionCodes.contains(action.actionCode()))
                .toList();
        Set<String> actionCodes = actions.stream().filter(PlatformModuleRuntimeAction::authorized)
                .map(PlatformModuleRuntimeAction::actionCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, DynamicRecordActionAvailability> byRecordId = actionCodes.isEmpty()
                ? Map.of()
                : dynamicRecordService.recordActionAvailability(moduleAlias, entityAlias, actionCodes, recordIds)
                        .stream().collect(Collectors.toMap(DynamicRecordActionAvailability::recordId, Function.identity()));
        return recordIds.stream().map(recordId -> {
            DynamicRecordActionAvailability availability = byRecordId.get(recordId);
            if (!actionCodes.isEmpty() && availability == null) {
                throw new IllegalArgumentException("dynamic record does not exist: " + recordId);
            }
            List<PlatformRecordActionAvailability.Action> resolved = actions.stream().map(action -> {
                if (!action.authorized()) {
                    return unavailable(action.actionCode(), "no action auth");
                }
                DynamicActionAvailability decision = availability.actions().get(action.actionCode());
                if (decision == null) {
                    return unavailable(action.actionCode(), "record action is not available");
                }
                return decision.available()
                        ? new PlatformRecordActionAvailability.Action(action.actionCode(), true, null)
                        : unavailable(action.actionCode(), normalizeReason(decision.message(), "record action is unavailable"));
            }).toList();
            return new PlatformRecordActionAvailability(recordId, resolved);
        }).toList();
    }

    private PlatformRecordActionAvailability.Action dynamicRecordAction(String moduleAlias,
                                                                        String entityAlias,
                                                                        String recordId,
                                                                        DynamicRecordHolder recordHolder,
                                                                        PlatformModuleRuntimeAction action) {
        if (!action.authorized()) {
            return unavailable(action.actionCode(), "no action auth");
        }
        DynamicActionAvailability authorization = dynamicRecordService.actionAuthorizationAvailability(
                moduleAlias, entityAlias, action.actionCode(), Set.of(recordId));
        if (!authorization.available()) {
            return unavailable(action.actionCode(), normalizeReason(authorization.message(), "no data auth"));
        }
        DynamicRecord record = recordHolder.record();
        if (record == null) {
            record = dynamicRecordService.select(moduleAlias, entityAlias, recordId);
            if (record == null) {
                throw new IllegalArgumentException("dynamic record does not exist: " + recordId);
            }
            recordHolder.record(record);
        }
        DynamicActionAvailability availability = dynamicRecordService.actionAvailability(moduleAlias,
                action.actionCode(), record);
        if (!availability.available()) {
            return unavailable(action.actionCode(), availability.message());
        }
        return new PlatformRecordActionAvailability.Action(action.actionCode(), true, null);
    }

    private PlatformRecordActionAvailability.Action staticRecordAction(String moduleAlias,
                                                                       String recordId,
                                                                       PlatformModuleRuntimeAction action) {
        if (!action.authorized()) {
            return unavailable(action.actionCode(), "no action auth");
        }
        ActionExecutionPolicy policy = policy(action);
        if (policy.requiresDataScope() && !hasRecordDataScope(moduleAlias, recordId, policy)) {
            return unavailable(action.actionCode(), "no data auth");
        }
        Optional<RecordActionAvailabilityDecision> businessDecision = businessAvailability(moduleAlias,
                action.actionCode(), recordId);
        if (businessDecision.isPresent() && !businessDecision.get().available()) {
            return unavailable(action.actionCode(), businessDecision.get().reason());
        }
        Optional<RecordActionAvailabilityDecision> protectionDecision = platformManagedAvailability(moduleAlias,
                action.actionCode(), recordId);
        if (protectionDecision.isPresent() && !protectionDecision.get().available()) {
            return unavailable(action.actionCode(), protectionDecision.get().reason());
        }
        return new PlatformRecordActionAvailability.Action(action.actionCode(), true, null);
    }

    private boolean hasRecordDataScope(String moduleAlias, String recordId, ActionExecutionPolicy policy) {
        CrudAbility<?> ability = crudAbility(moduleAlias);
        if (ability instanceof DataScopeAbility<?> dataScopeAbility) {
            try {
                dataScopeAbility.requireRecordScope(policy, List.of(recordId));
                return true;
            } catch (PlatformException | IllegalArgumentException ignored) {
                return false;
            }
        }
        return true;
    }

    private Optional<RecordActionAvailabilityDecision> businessAvailability(String moduleAlias,
                                                                           String actionCode,
                                                                           String recordId) {
        for (RecordActionAvailabilityContributor contributor : availabilityContributors) {
            Optional<RecordActionAvailabilityDecision> decision = contributor.availability(moduleAlias, actionCode,
                    recordId);
            if (decision.isPresent()) {
                return decision;
            }
        }
        return Optional.empty();
    }

    private Map<String, Optional<RecordActionAvailabilityDecision>> businessAvailability(String moduleAlias,
                                                                                           String actionCode,
                                                                                           List<String> recordIds) {
        Map<String, Optional<RecordActionAvailabilityDecision>> decisions = new LinkedHashMap<>();
        for (String recordId : recordIds) {
            decisions.put(recordId, Optional.empty());
        }
        for (RecordActionAvailabilityContributor contributor : availabilityContributors) {
            Map<String, Optional<RecordActionAvailabilityDecision>> contributed = contributor.availability(moduleAlias,
                    actionCode, recordIds);
            for (String recordId : recordIds) {
                if (decisions.get(recordId).isPresent()) {
                    continue;
                }
                Optional<RecordActionAvailabilityDecision> decision = contributed.get(recordId);
                if (decision != null && decision.isPresent()) {
                    decisions.put(recordId, decision);
                }
            }
        }
        return Map.copyOf(decisions);
    }

    private Set<String> dataScopedRecordIds(String moduleAlias,
                                             ActionExecutionPolicy policy,
                                             List<String> recordIds) {
        CrudAbility<?> ability = crudAbility(moduleAlias);
        if (!(ability instanceof DataScopeAbility<?> dataScopeAbility) || !policy.requiresDataScope()) {
            return Set.copyOf(recordIds);
        }
        try {
            var scope = dataScopeAbility.readScopeByPolicy(policy,
                    Criteria.of().in(StandardEntitySchema.ID_FIELD, recordIds));
            @SuppressWarnings({"rawtypes", "unchecked"})
            List<? extends EntityContract> visible = dataScopeAbility.withDataScopeTenant(scope,
                    () -> ((CrudAbility) dataScopeAbility).list(scope.criteria()));
            return visible.stream()
                    .map(EntityContract::getId)
                    .filter(recordIds::contains)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (PlatformException | IllegalArgumentException ignored) {
            return Set.of();
        }
    }

    private Map<String, EntityContract> platformManagedRecords(String moduleAlias, List<String> recordIds) {
        CrudAbility<?> ability = crudAbility(moduleAlias);
        if (!(ability instanceof PlatformManagedProtectionAbility<?>)) {
            return Map.of();
        }
        @SuppressWarnings({"rawtypes", "unchecked"})
        List<? extends EntityContract> records = ((CrudAbility) ability).list(
                Criteria.of().in(StandardEntitySchema.ID_FIELD, recordIds));
        return records.stream().collect(Collectors.toMap(EntityContract::getId, Function.identity(),
                (left, right) -> left, LinkedHashMap::new));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Optional<RecordActionAvailabilityDecision> platformManagedAvailability(String moduleAlias,
                                                                                   String actionCode,
                                                                                   EntityContract record) {
        CrudAbility<?> ability = crudAbility(moduleAlias);
        if (!(ability instanceof PlatformManagedProtectionAbility<?> protectionAbility)) {
            return Optional.empty();
        }
        return ((PlatformManagedProtectionAbility) protectionAbility)
                .ordinaryRecordActionAvailability(actionCode, record);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Optional<RecordActionAvailabilityDecision> platformManagedAvailability(String moduleAlias,
                                                                                   String actionCode,
                                                                                   String recordId) {
        CrudAbility<?> ability = crudAbility(moduleAlias);
        if (!(ability instanceof PlatformManagedProtectionAbility<?> protectionAbility)) {
            return Optional.empty();
        }
        EntityContract record = ability.select(recordId);
        return ((PlatformManagedProtectionAbility) protectionAbility)
                .ordinaryRecordActionAvailability(actionCode, record);
    }

    private CrudAbility<?> crudAbility(String moduleAlias) {
        return crudAbilities.stream()
                .filter(ability -> moduleAlias.equals(ability.getModuleAlias()))
                .findFirst()
                .orElse(null);
    }

    private boolean isStaticRecordAvailabilityAction(PlatformModuleRuntimeAction action) {
        return action.actionLevel() == PlatformActionLevel.RECORD
                || action.actionLevel() == PlatformActionLevel.ANY;
    }

    private boolean isDynamicRecordAvailabilityAction(PlatformModuleRuntimeAction action) {
        return action.actionLevel() == PlatformActionLevel.RECORD
                || action.actionLevel() == PlatformActionLevel.ANY;
    }

    private ActionExecutionPolicy policy(PlatformModuleRuntimeAction action) {
        return new ActionExecutionPolicy(
                action.actionCode(),
                action.actionLevel(),
                action.accessMode(),
                action.actionAuth(),
                action.dataAuth(),
                action.defaultGrantPolicy(),
                PlatformModuleRuntimeContextService.inheritActionCode(action.actionCode(),
                        action.permissionActionCode(), action.actionAuth())
        );
    }

    private PlatformRecordActionAvailability.Action unavailable(String actionCode, String reason) {
        return new PlatformRecordActionAvailability.Action(actionCode, false, reason);
    }

    private String requireRecordId(String recordId) {
        if (recordId == null || recordId.isBlank()) {
            throw new IllegalArgumentException("recordId must not be blank");
        }
        return recordId.trim();
    }

    private String normalizeReason(String reason, String fallback) {
        return reason == null || reason.isBlank() ? fallback : reason.trim();
    }

    private static final class DynamicRecordHolder {
        private DynamicRecord record;

        private DynamicRecord record() {
            return record;
        }

        private void record(DynamicRecord record) {
            this.record = record;
        }
    }
}
