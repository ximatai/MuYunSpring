package net.ximatai.muyun.spring.dynamic.runtime;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.common.platform.PlatformAction;
import net.ximatai.muyun.spring.dynamic.capability.CapabilityModuleRegistry;

final class DynamicStandardActionExecutor {
    private final DynamicRecordService service;
    private final String moduleAlias;
    private final String entityAlias;
    private final DynamicEntityOperations operations;
    private final String traceId;

    DynamicStandardActionExecutor(DynamicRecordService service, String moduleAlias, String entityAlias, String traceId) {
        this.service = service;
        this.moduleAlias = moduleAlias;
        this.entityAlias = entityAlias;
        this.operations = service.entity(moduleAlias, entityAlias);
        this.traceId = traceId;
    }

    DynamicActionResultBody execute(String actionCode, DynamicActionExecutionRequest request) {
        PlatformAction action = PlatformAction.fromCode(actionCode)
                .orElseThrow(() -> new IllegalArgumentException("unknown standard dynamic action: "
                        + moduleAlias + "." + entityAlias + "." + actionCode));
        var capabilityAction = CapabilityModuleRegistry.defaultRegistry().actionOwner(action);
        if (capabilityAction.isPresent()) {
            return countResult(DynamicCapabilityActionRuntimeAdapter.execute(capabilityAction.get(), action,
                    service, moduleAlias, entityAlias, requireRecordId(request, actionCode), traceId));
        }
        return switch (action) {
            case CREATE -> DynamicActionResultBody.createdRecordId(
                    service.createFromAction(moduleAlias, entityAlias, requireRecord(request, actionCode), traceId));
            case VIEW -> DynamicActionResultBody.of(operations.select(requireRecordId(request, actionCode)));
            case UPDATE -> countResult(service.updateFromAction(moduleAlias, entityAlias, requireRecord(request, actionCode), traceId));
            case DELETE -> countResult(service.deleteFromAction(moduleAlias, entityAlias, requireRecordId(request, actionCode), traceId));
            case BATCH_DELETE -> countResult(service.deleteBatchFromAction(moduleAlias, entityAlias, requireIds(request, actionCode), traceId));
            case QUERY -> DynamicActionResultBody.of(operations.page(criteria(request), requirePageRequest(request, actionCode), sorts(request)));
            case SORT -> {
                SortIntent intent = sortIntent(request, actionCode);
                if (intent == SortIntent.REORDER) {
                    service.reorderFromAction(moduleAlias, entityAlias, request.orderedIds(), traceId);
                    yield DynamicActionResultBody.refreshed();
                }
                if (intent == SortIntent.MOVE_BEFORE) {
                    service.moveBeforeFromAction(moduleAlias, entityAlias, requireRecordId(request, actionCode),
                            request.beforeId(), traceId);
                    yield DynamicActionResultBody.refreshed();
                }
                service.moveAfterFromAction(moduleAlias, entityAlias, requireRecordId(request, actionCode),
                        request.afterId(), traceId);
                yield DynamicActionResultBody.refreshed();
            }
            case MENU, TREE, REFERENCE, IMPORT, EXPORT,
                    RECYCLE_BIN_QUERY, RECYCLE_BIN_RESTORE, RECYCLE_BIN_PURGE -> throw new IllegalArgumentException(
                    "standard action is only exposed through web endpoint: " + actionCode);
            default -> throw new IllegalStateException("registered capability action was not dispatched: " + actionCode);
        };
    }

    private SortIntent sortIntent(DynamicActionExecutionRequest request, String actionCode) {
        int intents = 0;
        SortIntent intent = null;
        if (!request.orderedIds().isEmpty()) {
            intents++;
            intent = SortIntent.REORDER;
        }
        if (request.beforeId() != null && !request.beforeId().isBlank()) {
            intents++;
            intent = SortIntent.MOVE_BEFORE;
        }
        if (request.afterId() != null && !request.afterId().isBlank()) {
            intents++;
            intent = SortIntent.MOVE_AFTER;
        }
        if (intents != 1) {
            throw new IllegalArgumentException("dynamic action requires exactly one sort intent: " + actionCode);
        }
        return intent;
    }

    private DynamicActionResultBody countResult(int count) {
        return DynamicActionResultBody.changedCount(count);
    }

    private DynamicRecord requireRecord(DynamicActionExecutionRequest request, String actionCode) {
        if (request.record() == null) {
            throw new IllegalArgumentException("dynamic action requires record: " + actionCode);
        }
        return request.record();
    }

    private String requireRecordId(DynamicActionExecutionRequest request, String actionCode) {
        if (request.recordId() != null && !request.recordId().isBlank()) {
            return request.recordId();
        }
        if (request.record() != null && request.record().getId() != null && !request.record().getId().isBlank()) {
            return request.record().getId();
        }
        throw new IllegalArgumentException("dynamic action requires recordId: " + actionCode);
    }

    private java.util.Collection<String> requireIds(DynamicActionExecutionRequest request, String actionCode) {
        if (!request.ids().isEmpty()) {
            return request.ids();
        }
        throw new IllegalArgumentException("dynamic action requires ids: " + actionCode);
    }

    private Criteria criteria(DynamicActionExecutionRequest request) {
        return request.criteria() == null ? Criteria.of() : request.criteria();
    }

    private PageRequest requirePageRequest(DynamicActionExecutionRequest request, String actionCode) {
        if (request.pageRequest() == null) {
            throw new IllegalArgumentException("dynamic action requires pageRequest: " + actionCode);
        }
        return request.pageRequest();
    }

    private Sort[] sorts(DynamicActionExecutionRequest request) {
        return request.sorts().toArray(Sort[]::new);
    }

    private enum SortIntent {
        REORDER,
        MOVE_BEFORE,
        MOVE_AFTER
    }
}
