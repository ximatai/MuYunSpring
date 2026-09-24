package net.ximatai.muyun.spring.platform.deletion;

import net.ximatai.muyun.spring.ability.RecycleBinAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Purges only resources recorded by one successful source deletion tree. */
@Service
public class RecycleBinPurgeCoordinator {
    private final DeletionLogService deletionLogService;
    private final List<DeletionRecoveryResourceResolver> resourceResolvers;
    private final DeletionRecoveryExecutor recovery;

    public RecycleBinPurgeCoordinator(DeletionLogService deletionLogService,
                                      DeletionRecoveryExecutor recovery,
                                      List<DeletionRecoveryResourceResolver> resourceResolvers) {
        this.deletionLogService = Objects.requireNonNull(deletionLogService, "deletionLogService must not be null");
        this.resourceResolvers = resourceResolvers == null ? List.of() : List.copyOf(resourceResolvers);
        this.recovery = Objects.requireNonNull(recovery, "recovery must not be null");
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public PurgeReport purge(String sourceOperationId) {
        DeletionOperation source = deletionLogService.operation(sourceOperationId);
        if (source.getOperationType() != DeletionOperationType.DELETE
                || source.getStatus() != DeletionOperationStatus.SUCCEEDED) {
            throw new PlatformException("Purge source must be a successful delete operation: " + sourceOperationId);
        }
        List<DeletionEntry> sourceEntries = deletionLogService.operationEntries(sourceOperationId);
        if (sourceEntries.stream().noneMatch(entry -> entry.getParentEntryId() == null)) {
            throw new PlatformException("Purge source has no root entry: " + sourceOperationId);
        }
        String purgeOperationId = deletionLogService.startOperation(purgeOperation(source));
        Map<String, List<DeletionEntry>> children = childrenOf(sourceEntries);
        Map<String, String> purgeEntryIds = new HashMap<>();
        List<PurgeEntryResult> results = new ArrayList<>();
        sourceEntries.stream().filter(entry -> entry.getParentEntryId() == null)
                .forEach(entry -> purgeEntry(entry, children, purgeOperationId,
                        sourceOperationId, purgeEntryIds, results));
        results.sort(Comparator.comparingInt(result -> sourceEntryOrder(sourceEntries, result.sourceEntryId())));
        deletionLogService.completeOperation(purgeOperationId, status(results), message(results));
        return new PurgeReport(sourceOperationId, purgeOperationId, results);
    }

    private boolean purgeEntry(DeletionEntry source, Map<String, List<DeletionEntry>> children,
                               String operationId, String sourceOperationId,
                               Map<String, String> purgeEntryIds, List<PurgeEntryResult> results) {
        String entryId = startEntry(source, operationId, purgeEntryIds);
        if (source.getStatus() != DeletionEntryStatus.SUCCEEDED || source.getDeleteMode() != DeletionEntryMode.SOFT) {
            return skip(source, children, operationId, purgeEntryIds, results, entryId,
                    "resource was not soft-deleted by the source operation");
        }
        DeletionLifecycleEntry latest = deletionLogService.latestTerminalEntry(source.getResourceModuleAlias(),
                source.getResourceEntityAlias(), source.getResourceRecordId());
        if (DeletionLifecycleRetrySupport.completedByEarlierAttempt(
                latest, source, DeletionOperationType.PURGE, sourceOperationId)) {
            boolean descendantsComplete = true;
            for (DeletionEntry child : children.getOrDefault(source.getId(), List.of())) {
                descendantsComplete &= purgeEntry(child, children, operationId, sourceOperationId,
                        purgeEntryIds, results);
            }
            if (!descendantsComplete) {
                return skipCurrent(source, results, entryId,
                        "a descendant resource was not purged");
            }
            complete(entryId, DeletionEntryStatus.SUCCEEDED,
                    "resource was already purged by an earlier attempt");
            results.add(result(source, PurgeEntryResult.Status.PURGED,
                    "resource was already purged by an earlier attempt"));
            return true;
        }
        if (latest == null || !source.getId().equals(latest.entry().getId())) {
            return skip(source, children, operationId, purgeEntryIds, results, entryId,
                    "resource lifecycle changed after the source deletion");
        }
        SoftDeleteAbility<?> resolved;
        try {
            resolved = resolve(source);
        } catch (RuntimeException exception) {
            return failed(source, children, operationId, purgeEntryIds, results, entryId, exception.getMessage());
        }
        if (resolved == null) {
            return skip(source, children, operationId, purgeEntryIds, results, entryId,
                    "no deletion recovery resolver for this resource");
        }
        if (!(resolved instanceof RecycleBinAbility<?> ability)) {
            return skip(source, children, operationId, purgeEntryIds, results, entryId,
                    "recycle-bin purge is unavailable for this resource");
        }
        try {
            if (!ability.isRecycleBinPurgeEnabled()) {
                throw new UnsupportedOperationException(
                        "Recycle-bin purge is not enabled for " + ability.getModuleAlias());
            }
            ability.beforeRecycleBinPurge(source.getResourceRecordId());
        } catch (RuntimeException exception) {
            return failed(source, children, operationId, purgeEntryIds, results, entryId, exception.getMessage());
        }
        boolean descendantsComplete = true;
        for (DeletionEntry child : children.getOrDefault(source.getId(), List.of())) {
            descendantsComplete &= purgeEntry(child, children, operationId, sourceOperationId,
                    purgeEntryIds, results);
        }
        if (!descendantsComplete) {
            return skipCurrent(source, results, entryId, "a descendant resource was not purged");
        }
        try {
            if (recovery.purge(ability, source.getResourceRecordId(), entryId) <= 0) {
                return skipCurrent(source, results, entryId, "resource is no longer purgeable");
            }
        } catch (RuntimeException exception) {
            return failedCurrent(source, results, entryId, exception.getMessage());
        }
        results.add(result(source, PurgeEntryResult.Status.PURGED, null));
        return true;
    }

    private boolean skipCurrent(DeletionEntry source, List<PurgeEntryResult> results,
                                String entryId, String message) {
        complete(entryId, DeletionEntryStatus.SKIPPED, message);
        results.add(result(source, PurgeEntryResult.Status.SKIPPED, message));
        return false;
    }

    private boolean failedCurrent(DeletionEntry source, List<PurgeEntryResult> results,
                                  String entryId, String message) {
        String failure = message == null || message.isBlank() ? "resource purge failed" : message;
        complete(entryId, DeletionEntryStatus.FAILED, failure);
        results.add(result(source, PurgeEntryResult.Status.FAILED, failure));
        return false;
    }

    private boolean skip(DeletionEntry source, Map<String, List<DeletionEntry>> children, String operationId,
                         Map<String, String> ids, List<PurgeEntryResult> results, String entryId, String message) {
        complete(entryId, DeletionEntryStatus.SKIPPED, message);
        results.add(result(source, PurgeEntryResult.Status.SKIPPED, message));
        for (DeletionEntry child : children.getOrDefault(source.getId(), List.of())) {
            String childId = startEntry(child, operationId, ids);
            skip(child, children, operationId, ids, results, childId, "parent resource was not purged");
        }
        return false;
    }

    private boolean failed(DeletionEntry source, Map<String, List<DeletionEntry>> children, String operationId,
                           Map<String, String> ids, List<PurgeEntryResult> results, String entryId, String message) {
        String failure = message == null || message.isBlank() ? "resource purge failed" : message;
        complete(entryId, DeletionEntryStatus.FAILED, failure);
        results.add(result(source, PurgeEntryResult.Status.FAILED, failure));
        for (DeletionEntry child : children.getOrDefault(source.getId(), List.of())) {
            String childId = startEntry(child, operationId, ids);
            skip(child, children, operationId, ids, results, childId, "parent resource was not purged");
        }
        return false;
    }

    private SoftDeleteAbility<?> resolve(DeletionEntry entry) {
        List<DeletionRecoveryResourceResolver> matches = resourceResolvers.stream()
                .filter(resolver -> resolver.supports(entry))
                .toList();
        if (matches.size() > 1) {
            throw new IllegalStateException("Multiple deletion recovery resolvers support "
                    + resource(entry) + ": " + matches.stream()
                    .map(resolver -> resolver.getClass().getName()).toList());
        }
        return matches.isEmpty() ? null : matches.getFirst().resolve(entry).orElseThrow(() ->
                new IllegalStateException("Deletion recovery resolver claimed but could not resolve "
                        + resource(entry)));
    }

    private String startEntry(DeletionEntry source, String operationId, Map<String, String> ids) {
        DeletionEntry entry = new DeletionEntry();
        entry.setTenantId(source.getTenantId());
        entry.setOperationId(operationId);
        entry.setParentEntryId(ids.get(source.getParentEntryId()));
        entry.setSourceEntryId(source.getId());
        entry.setResourceModuleAlias(source.getResourceModuleAlias());
        entry.setResourceEntityAlias(source.getResourceEntityAlias());
        entry.setResourceRecordId(source.getResourceRecordId());
        entry.setTriggerType(source.getTriggerType());
        entry.setDeleteMode(source.getDeleteMode());
        entry.setResourceVersion(source.getResourceVersion());
        entry.setDisplaySnapshot(source.getDisplaySnapshot());
        String id = deletionLogService.startEntry(entry);
        ids.put(source.getId(), id);
        return id;
    }

    private void complete(String entryId, DeletionEntryStatus status, String message) {
        deletionLogService.completeEntry(entryId, status, message);
    }

    private DeletionOperation purgeOperation(DeletionOperation source) {
        DeletionOperation operation = new DeletionOperation();
        operation.setTenantId(source.getTenantId());
        operation.setOperationType(DeletionOperationType.PURGE);
        operation.setRootModuleAlias(source.getRootModuleAlias());
        operation.setRootEntityAlias(source.getRootEntityAlias());
        operation.setRootRecordId(source.getRootRecordId());
        operation.setSourceOperationId(source.getId());
        operation.setOperatorId(CurrentUserContext.currentUser().map(user -> user.userId()).orElse(null));
        return operation;
    }

    private PurgeEntryResult result(DeletionEntry entry, PurgeEntryResult.Status status, String message) {
        return new PurgeEntryResult(entry.getId(), entry.getResourceModuleAlias(), entry.getResourceEntityAlias(),
                entry.getResourceRecordId(), status, message);
    }

    private DeletionOperationStatus status(List<PurgeEntryResult> results) {
        boolean purged = results.stream().anyMatch(item -> item.status() == PurgeEntryResult.Status.PURGED);
        boolean failed = results.stream().anyMatch(item -> item.status() == PurgeEntryResult.Status.FAILED);
        boolean skipped = results.stream().anyMatch(item -> item.status() == PurgeEntryResult.Status.SKIPPED);
        return !purged && failed ? DeletionOperationStatus.FAILED : failed || skipped ? DeletionOperationStatus.PARTIALLY_SUCCEEDED : DeletionOperationStatus.SUCCEEDED;
    }

    private String message(List<PurgeEntryResult> results) {
        return "purged=" + results.stream().filter(item -> item.status() == PurgeEntryResult.Status.PURGED).count()
                + ", skipped=" + results.stream().filter(item -> item.status() == PurgeEntryResult.Status.SKIPPED).count()
                + ", failed=" + results.stream().filter(item -> item.status() == PurgeEntryResult.Status.FAILED).count();
    }

    private Map<String, List<DeletionEntry>> childrenOf(List<DeletionEntry> entries) {
        Map<String, List<DeletionEntry>> children = new HashMap<>();
        for (DeletionEntry entry : entries) {
            if (entry.getParentEntryId() != null) {
                children.computeIfAbsent(entry.getParentEntryId(), ignored -> new ArrayList<>()).add(entry);
            }
        }
        children.values().forEach(items -> items.sort(Comparator.comparing(DeletionEntry::getStartedAt,
                Comparator.nullsLast(Comparator.naturalOrder()))));
        return children;
    }

    private String resource(DeletionEntry entry) {
        return entry.getResourceModuleAlias() + "." + entry.getResourceEntityAlias() + "/" + entry.getResourceRecordId();
    }

    private int sourceEntryOrder(List<DeletionEntry> sourceEntries, String sourceEntryId) {
        for (int index = 0; index < sourceEntries.size(); index++) {
            if (sourceEntries.get(index).getId().equals(sourceEntryId)) {
                return index;
            }
        }
        return Integer.MAX_VALUE;
    }
}
