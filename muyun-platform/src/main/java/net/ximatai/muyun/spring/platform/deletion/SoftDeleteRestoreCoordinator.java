package net.ximatai.muyun.spring.platform.deletion;

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

/**
 * Restores a source delete tree through resource-owned soft-delete abilities.
 *
 * <p>It deliberately does not infer relations from tables. The source delete
 * entries are the only recovery tree; a hard-deleted or unavailable parent
 * stops its branch.</p>
 */
@Service
public class SoftDeleteRestoreCoordinator {
    private final DeletionLogService deletionLogService;
    private final List<DeletionRecoveryResourceResolver> resourceResolvers;
    private final DeletionRecoveryExecutor recovery;

    public SoftDeleteRestoreCoordinator(DeletionLogService deletionLogService,
                                        DeletionRecoveryExecutor recovery,
                                        List<DeletionRecoveryResourceResolver> resourceResolvers) {
        this.deletionLogService = Objects.requireNonNull(deletionLogService, "deletionLogService must not be null");
        this.resourceResolvers = resourceResolvers == null ? List.of() : List.copyOf(resourceResolvers);
        this.recovery = Objects.requireNonNull(recovery, "recovery must not be null");
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RestoreReport restore(String sourceOperationId) {
        return recovery.withSourceOperation(sourceOperationId, () -> restoreSource(sourceOperationId));
    }

    private RestoreReport restoreSource(String sourceOperationId) {
        DeletionOperation sourceOperation = deletionLogService.operation(sourceOperationId);
        if (sourceOperation.getOperationType() != DeletionOperationType.DELETE
                || sourceOperation.getStatus() != DeletionOperationStatus.SUCCEEDED) {
            throw new PlatformException("Restore source must be a successful delete operation: " + sourceOperationId);
        }
        List<DeletionEntry> sourceEntries = deletionLogService.operationEntries(sourceOperationId);
        if (sourceEntries.stream().noneMatch(entry -> entry.getParentEntryId() == null)) {
            throw new PlatformException("Restore source has no root entry: " + sourceOperationId);
        }
        Map<String, List<DeletionEntry>> children = childrenOf(sourceEntries);
        List<RestoreEntryResult> results = new ArrayList<>();
        String restoreOperationId = deletionLogService.startOperation(restoreOperation(sourceOperation));
        Map<String, String> restoreEntryIds = new HashMap<>();
        sourceEntries.stream().filter(entry -> entry.getParentEntryId() == null)
                .forEach(entry -> restoreEntry(entry, children, results, restoreOperationId,
                        sourceOperationId, restoreEntryIds));
        DeletionOperationStatus status = operationStatus(results);
        deletionLogService.completeOperation(restoreOperationId, status, resultMessage(results));
        return new RestoreReport(sourceOperationId, restoreOperationId, results);
    }

    private boolean restoreEntry(DeletionEntry entry, Map<String, List<DeletionEntry>> children,
                                 List<RestoreEntryResult> results, String restoreOperationId,
                                 String sourceOperationId, Map<String, String> restoreEntryIds) {
        String restoreEntryId = startRestoreEntry(entry, restoreOperationId, restoreEntryIds);
        if (entry.getStatus() != DeletionEntryStatus.SUCCEEDED || entry.getDeleteMode() != DeletionEntryMode.SOFT) {
            skipBranch(entry, children, results, restoreOperationId, restoreEntryIds,
                    restoreEntryId, "resource was not soft-deleted by the source operation");
            return false;
        }
        DeletionLifecycleEntry latest = deletionLogService.latestTerminalEntry(
                entry.getResourceModuleAlias(), entry.getResourceEntityAlias(), entry.getResourceRecordId());
        if (DeletionLifecycleRetrySupport.completedByEarlierAttempt(
                latest, entry, DeletionOperationType.RESTORE, sourceOperationId)) {
            completeRestoreEntry(restoreEntryId, DeletionEntryStatus.SUCCEEDED,
                    "resource was already restored by an earlier attempt");
            results.add(result(entry, RestoreEntryResult.Status.RESTORED,
                    "resource was already restored by an earlier attempt"));
            for (DeletionEntry child : children.getOrDefault(entry.getId(), List.of())) {
                restoreEntry(child, children, results, restoreOperationId, sourceOperationId, restoreEntryIds);
            }
            return true;
        }
        if (latest == null || !entry.getId().equals(latest.entry().getId())) {
            skipBranch(entry, children, results, restoreOperationId, restoreEntryIds,
                    restoreEntryId, "resource lifecycle changed after the source deletion");
            return false;
        }
        final SoftDeleteAbility<?> ability;
        try {
            ability = resolveAbility(entry);
        } catch (RuntimeException exception) {
            failedBranch(entry, children, results, restoreOperationId, restoreEntryIds, restoreEntryId,
                    exception.getMessage());
            return false;
        }
        if (ability == null) {
            skipBranch(entry, children, results, restoreOperationId, restoreEntryIds,
                    restoreEntryId, "soft-delete ability is unavailable");
            return false;
        }
        final int restored;
        try {
            restored = recovery.restore(ability, entry, restoreEntryId);
        } catch (RuntimeException exception) {
            failedBranch(entry, children, results, restoreOperationId, restoreEntryIds, restoreEntryId,
                    exception.getMessage());
            return false;
        }
        if (restored <= 0) {
            skipBranch(entry, children, results, restoreOperationId, restoreEntryIds,
                    restoreEntryId, "resource is no longer recoverable");
            return false;
        }
        results.add(result(entry, RestoreEntryResult.Status.RESTORED, null));
        for (DeletionEntry child : children.getOrDefault(entry.getId(), List.of())) {
            restoreEntry(child, children, results, restoreOperationId, sourceOperationId, restoreEntryIds);
        }
        return true;
    }

    private SoftDeleteAbility<?> resolveAbility(DeletionEntry entry) {
        List<DeletionRecoveryResourceResolver> matches = resourceResolvers.stream()
                .filter(resolver -> resolver.supports(entry))
                .toList();
        if (matches.size() > 1) {
            throw new IllegalStateException("Multiple deletion recovery resolvers support "
                    + entry.getResourceModuleAlias() + "." + entry.getResourceEntityAlias() + "/"
                    + entry.getResourceRecordId() + ": " + matches.stream()
                    .map(resolver -> resolver.getClass().getName()).toList());
        }
        return matches.isEmpty() ? null : matches.getFirst().resolve(entry).orElseThrow(() ->
                new IllegalStateException("Deletion recovery resolver claimed but could not resolve "
                        + entry.getResourceModuleAlias() + "." + entry.getResourceEntityAlias() + "/"
                        + entry.getResourceRecordId()));
    }

    private void skipBranch(DeletionEntry entry, Map<String, List<DeletionEntry>> children,
                            List<RestoreEntryResult> results, String restoreOperationId,
                            Map<String, String> restoreEntryIds, String restoreEntryId, String message) {
        completeRestoreEntry(restoreEntryId, DeletionEntryStatus.SKIPPED, message);
        results.add(result(entry, RestoreEntryResult.Status.SKIPPED, message));
        for (DeletionEntry child : children.getOrDefault(entry.getId(), List.of())) {
            String childRestoreEntryId = startRestoreEntry(child, restoreOperationId, restoreEntryIds);
            skipBranch(child, children, results, restoreOperationId, restoreEntryIds, childRestoreEntryId,
                    "parent resource was not restored");
        }
    }

    private void failedBranch(DeletionEntry entry, Map<String, List<DeletionEntry>> children,
                              List<RestoreEntryResult> results, String restoreOperationId,
                              Map<String, String> restoreEntryIds, String restoreEntryId, String message) {
        String failureMessage = message == null || message.isBlank() ? "resource restore failed" : message;
        completeRestoreEntry(restoreEntryId, DeletionEntryStatus.FAILED, failureMessage);
        results.add(result(entry, RestoreEntryResult.Status.FAILED, failureMessage));
        for (DeletionEntry child : children.getOrDefault(entry.getId(), List.of())) {
            String childRestoreEntryId = startRestoreEntry(child, restoreOperationId, restoreEntryIds);
            skipBranch(child, children, results, restoreOperationId, restoreEntryIds, childRestoreEntryId,
                    "parent resource was not restored");
        }
    }

    private String startRestoreEntry(DeletionEntry sourceEntry, String operationId,
                                     Map<String, String> restoreEntryIds) {
        DeletionEntry entry = new DeletionEntry();
        entry.setTenantId(sourceEntry.getTenantId());
        entry.setOperationId(operationId);
        entry.setParentEntryId(restoreEntryIds.get(sourceEntry.getParentEntryId()));
        entry.setSourceEntryId(sourceEntry.getId());
        entry.setResourceModuleAlias(sourceEntry.getResourceModuleAlias());
        entry.setResourceEntityAlias(sourceEntry.getResourceEntityAlias());
        entry.setResourceRecordId(sourceEntry.getResourceRecordId());
        entry.setTriggerType(sourceEntry.getTriggerType());
        entry.setDeleteMode(sourceEntry.getDeleteMode());
        entry.setResourceVersion(sourceEntry.getResourceVersion());
        entry.setDisplaySnapshot(sourceEntry.getDisplaySnapshot());
        String entryId = deletionLogService.startEntry(entry);
        restoreEntryIds.put(sourceEntry.getId(), entryId);
        return entryId;
    }

    private void completeRestoreEntry(String restoreEntryId, DeletionEntryStatus status, String message) {
        deletionLogService.completeEntry(restoreEntryId, status, message);
    }

    private DeletionOperation restoreOperation(DeletionOperation source) {
        DeletionOperation operation = new DeletionOperation();
        operation.setTenantId(source.getTenantId());
        operation.setOperationType(DeletionOperationType.RESTORE);
        operation.setRootModuleAlias(source.getRootModuleAlias());
        operation.setRootEntityAlias(source.getRootEntityAlias());
        operation.setRootRecordId(source.getRootRecordId());
        operation.setSourceOperationId(source.getId());
        operation.setOperatorId(CurrentUserContext.currentUser().map(user -> user.userId()).orElse(null));
        return operation;
    }

    private RestoreEntryResult result(DeletionEntry entry, RestoreEntryResult.Status status, String message) {
        return new RestoreEntryResult(entry.getId(), entry.getResourceModuleAlias(), entry.getResourceEntityAlias(), entry.getResourceRecordId(),
                status, message);
    }

    private String resultMessage(List<RestoreEntryResult> results) {
        long restored = results.stream().filter(result -> result.status() == RestoreEntryResult.Status.RESTORED).count();
        long skipped = results.stream().filter(result -> result.status() == RestoreEntryResult.Status.SKIPPED).count();
        long failed = results.stream().filter(result -> result.status() == RestoreEntryResult.Status.FAILED).count();
        return "restored=" + restored + ", skipped=" + skipped + ", failed=" + failed;
    }

    private DeletionOperationStatus operationStatus(List<RestoreEntryResult> results) {
        boolean restored = results.stream().anyMatch(result -> result.status() == RestoreEntryResult.Status.RESTORED);
        boolean skipped = results.stream().anyMatch(result -> result.status() == RestoreEntryResult.Status.SKIPPED);
        boolean failed = results.stream().anyMatch(result -> result.status() == RestoreEntryResult.Status.FAILED);
        if (!restored && results.stream().anyMatch(result -> result.status() == RestoreEntryResult.Status.FAILED)) {
            return DeletionOperationStatus.FAILED;
        }
        return skipped || failed ? DeletionOperationStatus.PARTIALLY_SUCCEEDED : DeletionOperationStatus.SUCCEEDED;
    }

    private Map<String, List<DeletionEntry>> childrenOf(List<DeletionEntry> entries) {
        Map<String, List<DeletionEntry>> children = new HashMap<>();
        for (DeletionEntry entry : entries) {
            if (entry.getParentEntryId() != null) {
                children.computeIfAbsent(entry.getParentEntryId(), ignored -> new ArrayList<>()).add(entry);
            }
        }
        children.values().forEach(value -> value.sort(Comparator.comparing(DeletionEntry::getStartedAt,
                Comparator.nullsLast(Comparator.naturalOrder()))));
        return children;
    }
}
