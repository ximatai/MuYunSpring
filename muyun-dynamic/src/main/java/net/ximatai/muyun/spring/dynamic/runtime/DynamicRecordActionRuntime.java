package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.spring.ability.TransactionScopeSupport;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.platform.ActionAuthorizationResult;
import net.ximatai.muyun.spring.common.platform.ActionExecutionContext;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicy;
import net.ximatai.muyun.spring.common.platform.ActionExecutionPolicyService;
import net.ximatai.muyun.spring.common.platform.DataScopeCriteriaResult;
import net.ximatai.muyun.spring.common.platform.EntityCapability;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.web.RequestTraceContext;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicActionDescriptor;
import net.ximatai.muyun.spring.dynamic.descriptor.DynamicEntityDescriptor;
import net.ximatai.muyun.spring.dynamic.metadata.EntityActionExecutorType;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Execution boundary for the dynamic action directory.
 *
 * <p>The record-service facade deliberately delegates here so action authorization, record scope,
 * availability, dialog protocol and after-commit event publication remain one coherent runtime.
 * CRUD mutation itself remains in {@link DynamicRecordMutationRuntime}; action handlers receive it
 * through {@link DynamicActionOperations}, rather than reaching into a second mutation path.</p>
 */
final class DynamicRecordActionRuntime {
    private final DynamicRecordService records;
    private final DynamicRecordRuntime runtime;
    private final DynamicRecordEventPublisher eventPublisher;
    private final ActionExecutionPolicyService policyService;

    DynamicRecordActionRuntime(DynamicRecordService records,
                               DynamicRecordRuntime runtime,
                               DynamicRecordEventPublisher eventPublisher,
                               ActionExecutionPolicyService policyService) {
        this.records = Objects.requireNonNull(records, "records must not be null");
        this.runtime = Objects.requireNonNull(runtime, "runtime must not be null");
        this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher must not be null");
        this.policyService = Objects.requireNonNull(policyService, "policyService must not be null");
    }

    DynamicActionAvailability actionAvailability(String moduleAlias, String actionCode, DynamicRecord record) {
        DynamicActionDescriptor action = records.actionDescriptor(moduleAlias, actionCode);
        return records.entityService(moduleAlias, records.actionEntityAlias(moduleAlias, actionCode))
                .actionAvailability(action.code(), record);
    }

    DynamicActionAvailability actionAvailability(String moduleAlias, String entityAlias, String actionCode,
                                                 DynamicRecord record) {
        DynamicActionDescriptor action = records.entityActionDescriptor(moduleAlias, entityAlias, actionCode);
        return records.entityService(moduleAlias, entityAlias).actionAvailability(action.code(), record);
    }

    List<DynamicRecordActionAvailability> recordActionAvailability(String moduleAlias, String entityAlias,
                                                                     Collection<String> actionCodes,
                                                                     Collection<String> recordIds) {
        Set<String> ids = records.normalizeActionRecordIds(recordIds);
        if (ids.isEmpty()) return List.of();
        DynamicEntityDescriptor entity = records.entityDescriptor(moduleAlias, entityAlias);
        Set<String> requested = actionCodes == null ? Set.of() : new LinkedHashSet<>(actionCodes);
        List<DynamicActionDescriptor> actions = entity.actions().stream()
                .filter(action -> requested.isEmpty() || requested.contains(action.code())).toList();
        Map<String, String> failures = new LinkedHashMap<>();
        for (DynamicActionDescriptor action : actions) failures.put(action.code(), authorizationFailure(moduleAlias, policy(action)));
        if (failures.values().stream().allMatch(Objects::nonNull)) {
            return ids.stream().map(id -> unavailable(id, actions, failures)).toList();
        }
        Map<String, Set<String>> visibleByAction = new LinkedHashMap<>();
        Set<String> visibleUnion = new LinkedHashSet<>();
        for (DynamicActionDescriptor action : actions) {
            Set<String> visible = failures.get(action.code()) == null
                    ? visibleRecordIds(moduleAlias, entityAlias, policy(action), ids) : Set.of();
            visibleByAction.put(action.code(), visible);
            visibleUnion.addAll(visible);
        }
        Map<String, DynamicRecord> persisted = visibleUnion.isEmpty() ? Map.of()
                : records.listSystem(moduleAlias, entityAlias, records.actionIdsCriteria(visibleUnion)).stream()
                .collect(java.util.stream.Collectors.toMap(DynamicRecord::getId, Function.identity()));
        if (persisted.size() != visibleUnion.size()) {
            throw new IllegalArgumentException("dynamic record does not exist in requested scope: " + moduleAlias);
        }
        return ids.stream().map(id -> {
            Map<String, DynamicActionAvailability> availability = new LinkedHashMap<>();
            for (DynamicActionDescriptor action : actions) {
                String failure = failures.get(action.code());
                availability.put(action.code(), failure != null
                        ? DynamicActionAvailability.unavailable(action.code(), failure)
                        : !visibleByAction.get(action.code()).contains(id)
                        ? DynamicActionAvailability.unavailable(action.code(), "no data auth")
                        : records.entityService(moduleAlias, entityAlias)
                        .actionAvailabilityPersisted(action.code(), persisted.get(id)));
            }
            return new DynamicRecordActionAvailability(id, availability);
        }).toList();
    }

    DynamicActionAvailability actionAuthorizationAvailability(String moduleAlias, String actionCode,
                                                              Collection<String> recordIds) {
        DynamicActionDescriptor action = records.actionDescriptor(moduleAlias, actionCode);
        return authorizationAvailability(moduleAlias, records.actionEntityAlias(moduleAlias, actionCode), action, recordIds);
    }

    DynamicActionAvailability actionAuthorizationAvailability(String moduleAlias, String entityAlias, String actionCode,
                                                              Collection<String> recordIds) {
        return authorizationAvailability(moduleAlias, entityAlias,
                records.entityActionDescriptor(moduleAlias, entityAlias, actionCode), recordIds);
    }

    DynamicActionAvailability httpOnlyCapabilityAuthorizationAvailability(String moduleAlias, PlatformAction action,
                                                                            Collection<String> recordIds) {
        Objects.requireNonNull(action, "action must not be null");
        String entityAlias = records.mainEntityAlias(moduleAlias);
        if (!records.supportsActionCapability(moduleAlias, entityAlias, action.group().capability())) {
            return DynamicActionAvailability.unavailable(action.code(), "dynamic entity does not support capability: "
                    + action.group().capability());
        }
        try {
            policyService.authorizeAction(moduleAlias, action.executionPolicy(), CurrentUserContext.currentUser());
            records.actionRecordDataScope(moduleAlias, entityAlias, action.executionPolicy(), recordIds);
            return DynamicActionAvailability.available(action.code());
        } catch (PlatformException e) {
            return DynamicActionAvailability.unavailable(action.code(), e.getMessage());
        }
    }

    DynamicActionExecutionResult executeAction(String moduleAlias, String actionCode,
                                               DynamicActionExecutionRequest request) {
        DynamicActionDescriptor action = records.actionDescriptor(moduleAlias, actionCode);
        return execute(moduleAlias, records.actionEntityAlias(moduleAlias, actionCode), action, request);
    }

    DynamicActionExecutionResult executeAction(String moduleAlias, String entityAlias, String actionCode,
                                               DynamicActionExecutionRequest request) {
        return execute(moduleAlias, entityAlias, records.entityActionDescriptor(moduleAlias, entityAlias, actionCode), request);
    }

    private DynamicActionExecutionResult execute(String moduleAlias, String entityAlias, DynamicActionDescriptor action,
                                                 DynamicActionExecutionRequest request) {
        DynamicActionExecutionRequest normalized = request == null ? DynamicActionExecutionRequest.empty() : request;
        ActionExecutionPolicy policy = policy(action);
        Set<String> recordIds = actionRecordIds(normalized);
        ActionAuthorizationResult authorization = policyService.authorize(ActionExecutionContext.ofPolicy(
                moduleAlias, policy, recordIds, CurrentUserContext.currentUser()));
        DataScopeCriteriaResult recordScope = records.actionRecordDataScope(moduleAlias, entityAlias, policy, recordIds);
        DataScopeCriteriaResult criteriaScope = records.actionCriteriaScope(moduleAlias, entityAlias, policy,
                normalized.criteria(), recordIds);
        DataScopeCriteriaResult scope = criteriaScope == null ? recordScope : criteriaScope;
        DynamicActionExecutionRequest scoped = criteriaScope == null ? normalized : normalized.withCriteria(criteriaScope.criteria());
        DynamicActionAvailability availability = records.withActionScope(scope, () -> actionAvailability(moduleAlias,
                entityAlias, action.code(), availabilityRecord(moduleAlias, entityAlias, scoped)));
        String traceId = traceId();
        DynamicActionExecutionContext context = context(moduleAlias, entityAlias, action, scoped, availability,
                null, traceId, authorization);
        if (!availability.available()) {
            eventPublisher.actionFailed(context, DynamicActionExecutionException.STAGE_AVAILABILITY, availability.message(), null);
            throw new DynamicActionExecutionException(availability.message(), context,
                    DynamicActionExecutionException.STAGE_AVAILABILITY, null);
        }
        DynamicActionExecutionResult result;
        try {
            result = records.withActionScope(scope, () -> runtime.actionTransactionOperator()
                    .executeResult(context, () -> {
                        if (action.executorType() != EntityActionExecutorType.DIALOG) validateBeforeExecute(moduleAlias, entityAlias, scoped, context);
                        DynamicActionResultBody body = executeValue(moduleAlias, entityAlias, action, scoped, context, traceId, policy);
                        return new DynamicActionExecutionResult(context(moduleAlias, entityAlias, action, scoped, availability,
                                body.value(), traceId, authorization), body.value(), body);
                    }));
        } catch (DynamicActionExecutionException e) {
            eventPublisher.actionFailed(context, e.failureStage(), e.getMessage(), e.getCause() == null ? e : e.getCause());
            throw e;
        } catch (RuntimeException e) {
            RuntimeException afterCommit = afterCommitFailure(e);
            if (afterCommit != null) throw afterCommit;
            eventPublisher.actionFailed(context, DynamicActionExecutionException.STAGE_EXECUTE, e.getMessage(), e);
            throw e;
        }
        eventPublisher.actionExecuted(result.context(), result.body());
        return result;
    }

    private DynamicActionResultBody executeValue(String moduleAlias, String entityAlias, DynamicActionDescriptor action,
                                                  DynamicActionExecutionRequest request, DynamicActionExecutionContext context,
                                                  String traceId, ActionExecutionPolicy policy) {
        return switch (action.executorType()) {
            case STANDARD -> new DynamicStandardActionExecutor(records, moduleAlias, entityAlias, traceId).execute(action.code(), request);
            case SERVICE, GENERATE -> registeredAction(moduleAlias, entityAlias, action, request, context, traceId, policy);
            case DIALOG -> DynamicActionResultBody.dialog(dialog(moduleAlias, action, request));
            default -> throw new DynamicActionExecutionException(
                    "dynamic action executor is not supported: " + action.executorType(), context);
        };
    }

    private DynamicActionResultBody registeredAction(String moduleAlias, String entityAlias, DynamicActionDescriptor action,
                                                     DynamicActionExecutionRequest request, DynamicActionExecutionContext context,
                                                     String traceId, ActionExecutionPolicy policy) {
        try {
            DynamicActionExecutor executor = runtime.actionExecutorRegistry().require(action.executorKey());
            Object value = executor.execute(context, request, operations(moduleAlias, entityAlias, traceId, policy));
            return value instanceof DynamicActionResultBody body ? body : DynamicActionResultBody.of(value);
        } catch (DynamicActionExecutionException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new DynamicActionExecutionException(e.getMessage(), context, e);
        }
    }

    private DynamicActionOperations operations(String moduleAlias, String entityAlias, String traceId, ActionExecutionPolicy policy) {
        return new DynamicActionOperations() {
            @Override public DynamicRecord newRecord() { return records.newRecord(moduleAlias, entityAlias); }
            @Override public DynamicRecord newRecord(String module, String entity) { return records.newRecord(module, entity); }
            @Override public DynamicRecord select(String id) { return records.select(moduleAlias, entityAlias, id); }
            @Override public DynamicRecord select(String module, String entity, String id) { return records.select(module, entity, id); }
            @Override public void requireAction(String module, PlatformAction action) { records.requireAction(module, action); }
            @Override public int update(DynamicRecord record) {
                DataScopeCriteriaResult scope = records.requireRecordActionScope(moduleAlias, entityAlias, policy,
                        normalizeIds(record == null ? null : record.getId()), CurrentUserContext.currentUser());
                return records.withActionScope(scope, () -> records.updateFromAction(moduleAlias, entityAlias, record, traceId));
            }
            @Override public int delete(String id) {
                DataScopeCriteriaResult scope = records.requireRecordActionScope(moduleAlias, entityAlias, policy,
                        normalizeIds(id), CurrentUserContext.currentUser());
                return records.withActionScope(scope, () -> records.deleteFromAction(moduleAlias, entityAlias, id, traceId));
            }
        };
    }

    private DynamicActionDialog dialog(String moduleAlias, DynamicActionDescriptor action, DynamicActionExecutionRequest request) {
        String key = requireText(action.executorKey(), "dialog executorKey");
        int separator = key.indexOf('#');
        String dialogKey = separator < 0 ? key : requireText(key.substring(0, separator), "dialog key");
        String submit = separator < 0 || separator == key.length() - 1 ? null : requireText(key.substring(separator + 1), "dialog submit actionCode");
        DynamicActionDescriptor submitAction = submit == null ? null : records.actionDescriptor(moduleAlias, submit);
        if (submitAction != null && submitAction.executorType() == EntityActionExecutorType.DIALOG) {
            throw new PlatformException("dialog submit action must not be DIALOG: " + submit);
        }
        String recordId = request == null ? null : firstText(request.recordId(), request.record() == null ? null : request.record().getId());
        String path = submitAction == null ? null : submitPath(moduleAlias, submitAction, recordId);
        return new DynamicActionDialog(dialogKey, action.title(), action.code(), submit, path, recordId, submit != null, null,
                submit == null ? DynamicActionRefreshStrategy.none() : DynamicActionRefreshStrategy.listAndDetail());
    }

    private String submitPath(String moduleAlias, DynamicActionDescriptor action, String recordId) {
        return switch (action.actionLevel()) {
            case RECORD -> recordId == null ? "/" + moduleAlias + "/" + action.code() + "/{recordId}"
                    : "/" + moduleAlias + "/" + action.code() + "/" + recordId;
            case BATCH -> "/" + moduleAlias + "/" + action.code() + "/batch";
            default -> "/" + moduleAlias + "/" + action.code();
        };
    }

    private void validateBeforeExecute(String moduleAlias, String entityAlias, DynamicActionExecutionRequest request,
                                       DynamicActionExecutionContext context) {
        DynamicRecord record = availabilityRecord(moduleAlias, entityAlias, request);
        if (record == null) return;
        DynamicFormulaRuntime formulas = new DynamicFormulaRuntime(moduleAlias, record.getEntity(), runtime.registry().requireModule(moduleAlias));
        if (!formulas.hasBeforeActionExecuteRules()) return;
        DynamicRecord existing = record.getId() != null && !record.getId().isBlank()
                && (record.explicitFieldCodes().isEmpty() || !record.getChildren().isEmpty())
                ? records.select(moduleAlias, entityAlias, record.getId()) : null;
        if (record.explicitFieldCodes().isEmpty() && record.getChildren().isEmpty() && existing != null) { record = existing; existing = null; }
        try { formulas.beforeActionExecute(record, existing); }
        catch (DynamicFormulaException e) { throw new DynamicActionExecutionException(e.getMessage(), context,
                DynamicActionExecutionException.STAGE_BEFORE_EXECUTE_RULE, e); }
    }

    private DynamicActionAvailability authorizationAvailability(String module, String entity, DynamicActionDescriptor action,
                                                                 Collection<String> ids) {
        try {
            policyService.authorizeAction(module, policy(action), CurrentUserContext.currentUser());
            records.actionRecordDataScope(module, entity, policy(action), ids);
            return DynamicActionAvailability.available(action.code());
        } catch (PlatformException e) { return DynamicActionAvailability.unavailable(action.code(), e.getMessage()); }
    }

    private String authorizationFailure(String module, ActionExecutionPolicy policy) {
        try { policyService.authorizeAction(module, policy, CurrentUserContext.currentUser()); return null; }
        catch (PlatformException e) { return e.getMessage() == null || e.getMessage().isBlank() ? "no action auth" : e.getMessage(); }
    }

    private Set<String> visibleRecordIds(String module, String entity, ActionExecutionPolicy policy, Set<String> ids) {
        if (!policy.requiresDataScope() || !records.supportsActionCapability(module, entity, EntityCapability.DATA_SCOPE)) return ids;
        try { return records.visibleActionRecordIds(module, entity, policy, ids); }
        catch (PlatformException | IllegalArgumentException ignored) { return Set.of(); }
    }

    private DynamicRecordActionAvailability unavailable(String id, List<DynamicActionDescriptor> actions, Map<String, String> failures) {
        Map<String, DynamicActionAvailability> availability = new LinkedHashMap<>();
        actions.forEach(action -> availability.put(action.code(), DynamicActionAvailability.unavailable(action.code(), failures.get(action.code()))));
        return new DynamicRecordActionAvailability(id, availability);
    }

    private DynamicRecord availabilityRecord(String module, String entity, DynamicActionExecutionRequest request) {
        if (request.record() != null) return request.record();
        if (request.recordId() == null || request.recordId().isBlank()) return null;
        DynamicRecord probe = records.newRecord(module, entity); probe.setId(request.recordId()); return probe;
    }
    private Set<String> actionRecordIds(DynamicActionExecutionRequest request) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        add(ids, request.recordId()); if (request.record() != null) add(ids, request.record().getId());
        request.ids().forEach(id -> add(ids, id)); request.orderedIds().forEach(id -> add(ids, id));
        add(ids, request.beforeId()); add(ids, request.afterId()); add(ids, request.parentId()); return Set.copyOf(ids);
    }
    private Set<String> normalizeIds(String id) { return id == null ? Set.of() : Set.of(id); }
    private void add(Set<String> ids, String id) { if (id != null && !id.isBlank()) ids.add(id.trim()); }
    private ActionExecutionPolicy policy(DynamicActionDescriptor action) { return records.actionPolicy(action); }
    private String traceId() { return RequestTraceContext.currentTraceId().orElseGet(() -> UUID.randomUUID().toString()); }
    private RuntimeException afterCommitFailure(RuntimeException error) { for (Throwable current = error; current != null; current = current.getCause()) if (current instanceof TransactionScopeSupport.AfterCommitActionException after) return after.unwrap(); return null; }
    private String requireText(String value, String field) { if (value == null || value.isBlank()) throw new IllegalArgumentException("dynamic action requires " + field); return value; }
    private String firstText(String... values) { for (String value : values) if (value != null && !value.isBlank()) return value.trim(); return null; }
    private DynamicActionExecutionContext context(String module, String entity, DynamicActionDescriptor action,
                                                  DynamicActionExecutionRequest request, DynamicActionAvailability availability,
                                                  Object value, String traceId, ActionAuthorizationResult authorization) {
        String recordId = firstText(request.recordId(), request.record() == null ? null : request.record().getId());
        if (recordId == null && PlatformAction.CREATE.matches(action.code()) && value instanceof String id) recordId = id;
        return new DynamicActionExecutionContext(module, entity, action.code(), action, recordId, traceId,
                TenantContext.currentTenantId().orElse(null), TenantContext.isSystem(), TenantContext.systemReason().orElse(null),
                authorization == null ? null : authorization.operatorId(), authorization == null ? null : authorization.operatorType(),
                authorization == null ? null : authorization.decision(), authorization == null ? null : authorization.permissionCode(),
                authorization == null ? null : authorization.permissionActionCode(), availability);
    }
}
