package net.ximatai.muyun.spring.platform.deletion;

import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.PageResult;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.RecycleBinAbility;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Platform facade for the operator-facing part of an opted-in recycle bin.
 * Resource services retain ownership of visibility and authorization hooks;
 * source-tree validation and recovery execution stay in the platform.
 */
@Service
public class RecycleBinFacade {
    private final DeletionLogService deletionLogService;
    private final SoftDeleteRestoreCoordinator restoreCoordinator;
    private final RecycleBinPurgeCoordinator purgeCoordinator;

    public RecycleBinFacade(DeletionLogService deletionLogService,
                            SoftDeleteRestoreCoordinator restoreCoordinator,
                            RecycleBinPurgeCoordinator purgeCoordinator) {
        this.deletionLogService = Objects.requireNonNull(deletionLogService, "deletionLogService must not be null");
        this.restoreCoordinator = Objects.requireNonNull(restoreCoordinator, "restoreCoordinator must not be null");
        this.purgeCoordinator = Objects.requireNonNull(purgeCoordinator, "purgeCoordinator must not be null");
    }

    public <T extends EntityContract> List<RecycleBinItem<T>> list(RecycleBinAbility<T> ability,
                                                                      PageRequest pageRequest) {
        Objects.requireNonNull(ability, "recycleBinAbility must not be null");
        return ability.listRecycleBin(pageRequest).stream().map(record -> item(ability, record)).toList();
    }

    public <T extends EntityContract> PageResult<RecycleBinItem<T>> page(RecycleBinAbility<T> ability,
                                                                         Criteria criteria,
                                                                         PageRequest pageRequest,
                                                                         Sort... sorts) {
        Objects.requireNonNull(ability, "recycleBinAbility must not be null");
        PageRequest effectivePage = pageRequest == null ? PageRequest.of(1, 20) : pageRequest;
        PageResult<T> page = ability.pageRecycleBin(criteria, effectivePage, sorts);
        return PageResult.of(page.getRecords().stream().map(record -> item(ability, record)).toList(),
                page.getTotal(), effectivePage);
    }

    public RestoreReport restore(RecycleBinAbility<?> ability, String sourceDeleteOperationId) {
        return restoreWithSource(ability, sourceDeleteOperationId).report();
    }

    public <T extends EntityContract> RecycleBinActionOutcome<T, RestoreReport> restoreWithSource(
            RecycleBinAbility<T> ability,
            String sourceDeleteOperationId) {
        Objects.requireNonNull(ability, "recycleBinAbility must not be null");
        ability.beforeRecycleBinRestore();
        DeletionOperation source = requireAccessibleSource(ability, sourceDeleteOperationId, "restore");
        T record = ability.selectIgnoreSoftDelete(source.getRootRecordId());
        RestoreReport report = restoreCoordinator.restore(sourceDeleteOperationId);
        return new RecycleBinActionOutcome<>(source.getRootRecordId(), record, report);
    }

    public PurgeReport purge(RecycleBinAbility<?> ability, String sourceDeleteOperationId) {
        return purgeWithSource(ability, sourceDeleteOperationId).report();
    }

    public <T extends EntityContract> RecycleBinActionOutcome<T, PurgeReport> purgeWithSource(
            RecycleBinAbility<T> ability,
            String sourceDeleteOperationId) {
        Objects.requireNonNull(ability, "recycleBinAbility must not be null");
        if (!ability.isRecycleBinPurgeEnabled()) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.recycle-bin.recycle-bin-purge-is-not-enabled-for",
                    "Recycle-bin purge is not enabled for {value1}"), Map.of("value1", String.valueOf(ability.getModuleAlias())));
        }
        DeletionOperation source = requireAccessibleSource(ability, sourceDeleteOperationId, "purge");
        T record = ability.selectIgnoreSoftDelete(source.getRootRecordId());
        PurgeReport report = purgeCoordinator.purge(sourceDeleteOperationId);
        return new RecycleBinActionOutcome<>(source.getRootRecordId(), record, report);
    }

    private DeletionOperation requireAccessibleSource(RecycleBinAbility<?> ability,
                                                      String sourceDeleteOperationId,
                                                      String action) {
        DeletionOperation source = deletionLogService.operation(sourceDeleteOperationId);
        if (!isSuccessfulRootDelete(ability, source)) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.recycle-bin.recycle-bin-requires-a-successful-root-delete-operation-for",
                    "Recycle-bin {value1} requires a successful root delete operation for {value2}: {value3}"), Map.of("value1", String.valueOf(action), "value2", String.valueOf(ability.getModuleAlias()), "value3", String.valueOf(sourceDeleteOperationId)));
        }
        if (!ability.canAccessRecycleBinSourceRecord(source.getRootRecordId())) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.recycle-bin.recycle-bin-record-is-unavailable",
                    "Recycle-bin record is unavailable: {value1}"), Map.of("value1", String.valueOf(ability.getModuleAlias())));
        }
        return source;
    }

    private <T extends EntityContract> RecycleBinItem<T> item(RecycleBinAbility<T> ability, T record) {
        return item(ability, record, record.getId(), record.getDeletedAt());
    }

    public <T> RecycleBinItem<T> item(RecycleBinAbility<?> ability,
                                      T record,
                                      String recordId,
                                      java.time.Instant deletedAt) {
        DeletionLifecycleEntry lifecycle = deletionLogService.latestTerminalEntry(
                ability.getModuleAlias(), ability.getDeletionEntityAlias(), recordId);
        return item(ability, record, recordId, deletedAt, lifecycle);
    }

    /** Decorates one projected page with one batched deletion-lifecycle read. */
    public <T> List<RecycleBinItem<T>> items(RecycleBinAbility<?> ability,
                                             List<T> records,
                                             Function<T, String> recordId,
                                             Function<T, java.time.Instant> deletedAt) {
        Objects.requireNonNull(ability, "recycleBinAbility must not be null");
        Objects.requireNonNull(recordId, "recordId extractor must not be null");
        Objects.requireNonNull(deletedAt, "deletedAt extractor must not be null");
        List<T> safeRecords = records == null ? List.of() : List.copyOf(records);
        Map<String, DeletionLifecycleEntry> lifecycles = deletionLogService.latestTerminalEntries(
                ability.getModuleAlias(), ability.getDeletionEntityAlias(),
                safeRecords.stream().map(recordId).toList());
        return safeRecords.stream()
                .map(record -> {
                    String id = recordId.apply(record);
                    return item(ability, record, id, deletedAt.apply(record), lifecycles.get(id));
                })
                .toList();
    }

    private <T> RecycleBinItem<T> item(RecycleBinAbility<?> ability,
                                       T record,
                                       String recordId,
                                       java.time.Instant deletedAt,
                                       DeletionLifecycleEntry lifecycle) {
        if (lifecycle == null) {
            return new RecycleBinItem<>(record, null, deletedAt, false, false,
                    "deletion history is unavailable");
        }
        DeletionOperation operation = lifecycle.operation();
        DeletionEntry entry = lifecycle.entry();
        boolean restorable = operation.getOperationType() == DeletionOperationType.DELETE
                && operation.getStatus() == DeletionOperationStatus.SUCCEEDED
                && entry.getStatus() == DeletionEntryStatus.SUCCEEDED
                && entry.getDeleteMode() == DeletionEntryMode.SOFT
                && isRootResource(ability, operation, entry)
                && recordId.equals(operation.getRootRecordId());
        boolean purgeable = restorable && ability.isRecycleBinPurgeEnabled();
        return new RecycleBinItem<>(record, restorable ? operation.getId() : null, deletedAt, restorable, purgeable,
                restorable ? null : "resource lifecycle changed after deletion");
    }

    private boolean isSuccessfulRootDelete(RecycleBinAbility<?> ability, DeletionOperation operation) {
        if (operation.getOperationType() != DeletionOperationType.DELETE
                || operation.getStatus() != DeletionOperationStatus.SUCCEEDED
                || !ability.getModuleAlias().equals(operation.getRootModuleAlias())) {
            return false;
        }
        if (ability.getDeletionEntityAlias().equals(operation.getRootEntityAlias())) {
            return true;
        }
        if (operation.getRootEntityAlias() != null && !operation.getRootEntityAlias().isBlank()) {
            return false;
        }
        return deletionLogService.operationEntries(operation.getId()).stream()
                .anyMatch(entry -> isRootResource(ability, operation, entry));
    }

    private boolean isRootResource(RecycleBinAbility<?> ability,
                                   DeletionOperation operation,
                                   DeletionEntry entry) {
        if (ability.getDeletionEntityAlias().equals(operation.getRootEntityAlias())) {
            return true;
        }
        return (operation.getRootEntityAlias() == null || operation.getRootEntityAlias().isBlank())
                && entry != null
                && entry.getParentEntryId() == null
                && operation.getId().equals(entry.getOperationId())
                && ability.getModuleAlias().equals(entry.getResourceModuleAlias())
                && ability.getDeletionEntityAlias().equals(entry.getResourceEntityAlias())
                && operation.getRootRecordId().equals(entry.getResourceRecordId());
    }
}
