package net.ximatai.muyun.spring.platform.deletion;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Comparator;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;

/**
 * Persistence boundary for the platform deletion lifecycle log.
 *
 * <p>This is intentionally not a deletion coordinator. Ability and domain
 * integrations will create and complete records through this service later;
 * this first version only provides a validated, append-only operation/entry
 * journal with terminal completion updates.</p>
 */
@Service
public class DeletionLogService {
    private final BaseDao<DeletionOperation, String> operationDao;
    private final BaseDao<DeletionEntry, String> entryDao;

    @Autowired
    public DeletionLogService(BaseDao<DeletionOperation, String> operationDao,
                              BaseDao<DeletionEntry, String> entryDao) {
        this.operationDao = Objects.requireNonNull(operationDao, "operationDao must not be null");
        this.entryDao = Objects.requireNonNull(entryDao, "entryDao must not be null");
    }

    @Transactional
    public String startOperation(DeletionOperation operation) {
        Objects.requireNonNull(operation, "operation must not be null");
        require(operation.getOperationType(), "operationType");
        requireText(operation.getRootModuleAlias(), "rootModuleAlias");
        requireText(operation.getRootRecordId(), "rootRecordId");
        if (operation.getStatus() == null) {
            operation.setStatus(DeletionOperationStatus.IN_PROGRESS);
        }
        if (operation.getStatus() != DeletionOperationStatus.IN_PROGRESS) {
            throw new PlatformException("Deletion operation must start in progress");
        }
        if (operation.getStartedAt() == null) {
            operation.setStartedAt(Instant.now());
        }
        if (operation.getCompletedAt() != null) {
            throw new PlatformException("Deletion operation completedAt must be null when starting");
        }
        EntityLifecycle.prepareInsert(operation, Instant.now());
        return operationDao.insert(operation);
    }

    @Transactional
    public String startEntry(DeletionEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        requireText(entry.getOperationId(), "operationId");
        requireOperationInProgress(entry.getOperationId());
        requireText(entry.getResourceModuleAlias(), "resourceModuleAlias");
        requireText(entry.getResourceRecordId(), "resourceRecordId");
        require(entry.getTriggerType(), "triggerType");
        if (entry.getStatus() == null) {
            entry.setStatus(DeletionEntryStatus.IN_PROGRESS);
        }
        if (entry.getStatus() != DeletionEntryStatus.IN_PROGRESS) {
            throw new PlatformException("Deletion entry must start in progress");
        }
        if (entry.getParentEntryId() != null) {
            DeletionEntry parent = entryDao.findById(entry.getParentEntryId());
            if (parent == null) {
                throw new PlatformException("Deletion entry parentEntryId does not exist: " + entry.getParentEntryId());
            }
            if (!entry.getOperationId().equals(parent.getOperationId())) {
                throw new PlatformException("Deletion entry parentEntryId belongs to another operation: "
                        + entry.getParentEntryId());
            }
        }
        if (entry.getStartedAt() == null) {
            entry.setStartedAt(Instant.now());
        }
        if (entry.getCompletedAt() != null) {
            throw new PlatformException("Deletion entry completedAt must be null when starting");
        }
        EntityLifecycle.prepareInsert(entry, Instant.now());
        return entryDao.insert(entry);
    }

    @Transactional
    public void completeOperation(String operationId, DeletionOperationStatus status, String resultMessage) {
        requireText(operationId, "operationId");
        requireTerminal(status, "operation status");
        DeletionOperation operation = requireOperation(operationId);
        if (operation.getStatus() != DeletionOperationStatus.IN_PROGRESS) {
            throw new PlatformException("Deletion operation is already completed: " + operationId);
        }
        operation.setStatus(status);
        operation.setResultMessage(blankToNull(resultMessage));
        operation.setCompletedAt(Instant.now());
        EntityLifecycle.prepareUpdate(operation, Instant.now());
        operationDao.updateById(operation);
    }

    @Transactional
    public void completeEntry(String entryId, DeletionEntryStatus status, String resultMessage) {
        requireText(entryId, "entryId");
        requireTerminal(status, "entry status");
        DeletionEntry entry = entryDao.findById(entryId);
        if (entry == null) {
            throw new PlatformException("Deletion entry does not exist: " + entryId);
        }
        if (entry.getStatus() != DeletionEntryStatus.IN_PROGRESS) {
            throw new PlatformException("Deletion entry is already completed: " + entryId);
        }
        entry.setStatus(status);
        entry.setResultMessage(blankToNull(resultMessage));
        entry.setCompletedAt(Instant.now());
        EntityLifecycle.prepareUpdate(entry, Instant.now());
        entryDao.updateById(entry);
    }

    public DeletionOperation operation(String operationId) {
        return requireOperation(operationId);
    }

    public DeletionEntry entry(String entryId) {
        requireText(entryId, "entryId");
        DeletionEntry entry = entryDao.findById(entryId);
        if (entry == null) {
            throw new PlatformException("Deletion entry does not exist: " + entryId);
        }
        return entry;
    }

    public Criteria operationEntriesCriteria(String operationId) {
        return Criteria.of().eq("operationId", requireText(operationId, "operationId"));
    }

    /**
     * Returns the persisted impact tree of one lifecycle operation.
     *
     * <p>This is a narrow read API for lifecycle coordinators. The log service
     * remains an audit repository rather than a general resource repository.</p>
     */
    public List<DeletionEntry> operationEntries(String operationId) {
        requireOperation(operationId);
        return entryDao.query(operationEntriesCriteria(operationId), PageRequest.of(1, Integer.MAX_VALUE));
    }

    public DeletionLifecycleEntry latestTerminalEntry(String moduleAlias, String recordId) {
        return latestTerminalEntry(moduleAlias, null, recordId);
    }

    public DeletionLifecycleEntry latestTerminalEntry(String moduleAlias, String entityAlias, String recordId) {
        return latestTerminalEntries(moduleAlias, entityAlias, List.of(recordId)).get(recordId);
    }

    /** Loads the latest effective lifecycle facts for one page of resource records. */
    public Map<String, DeletionLifecycleEntry> latestTerminalEntries(String moduleAlias,
                                                                     String entityAlias,
                                                                     Collection<String> recordIds) {
        LinkedHashSet<String> normalizedIds = new LinkedHashSet<>();
        if (recordIds != null) {
            recordIds.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(value -> !value.isEmpty())
                    .forEach(normalizedIds::add);
        }
        if (normalizedIds.isEmpty()) {
            return Map.of();
        }
        Criteria criteria = Criteria.of()
                .eq("resourceModuleAlias", requireText(moduleAlias, "moduleAlias"))
                .in("resourceRecordId", List.copyOf(normalizedIds));
        if (entityAlias != null && !entityAlias.isBlank()) {
            criteria.eq("resourceEntityAlias", entityAlias);
        }
        List<DeletionEntry> entries = entryDao.query(criteria,
                PageRequest.of(1, Integer.MAX_VALUE));
        LinkedHashSet<String> operationIds = new LinkedHashSet<>();
        entries.stream()
                .filter(entry -> entry.getStatus() == DeletionEntryStatus.SUCCEEDED)
                .map(DeletionEntry::getOperationId)
                .filter(Objects::nonNull)
                .forEach(operationIds::add);
        if (operationIds.isEmpty()) {
            return Map.of();
        }
        Map<String, DeletionOperation> operations = new LinkedHashMap<>();
        operationDao.query(Criteria.of().in("id", List.copyOf(operationIds)),
                        PageRequest.of(1, Integer.MAX_VALUE))
                .forEach(operation -> operations.put(operation.getId(), operation));
        if (operations.size() != operationIds.size()) {
            operationIds.stream()
                    .filter(operationId -> !operations.containsKey(operationId))
                    .findFirst()
                    .ifPresent(this::requireOperation);
        }

        Comparator<DeletionLifecycleEntry> recency = Comparator
                .comparing((DeletionLifecycleEntry item) -> item.entry().getCompletedAt(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                // A restore can start after the source delete but complete in the same clock tick.
                // Preserve that factual order before falling back to the opaque entry id.
                .thenComparing(item -> item.entry().getStartedAt(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(item -> item.entry().getId());
        Map<String, DeletionLifecycleEntry> latest = new LinkedHashMap<>();
        for (DeletionEntry entry : entries) {
            DeletionOperation operation = operations.get(entry.getOperationId());
            // Failed or skipped attempts remain audit facts, but do not change the resource lifecycle.
            if (entry.getStatus() != DeletionEntryStatus.SUCCEEDED || operation == null
                    || operation.getStatus() == DeletionOperationStatus.IN_PROGRESS) {
                continue;
            }
            DeletionLifecycleEntry candidate = new DeletionLifecycleEntry(operation, entry);
            latest.merge(entry.getResourceRecordId(), candidate,
                    (current, next) -> recency.compare(current, next) >= 0 ? current : next);
        }
        return Map.copyOf(latest);
    }

    private void requireOperationInProgress(String operationId) {
        if (requireOperation(operationId).getStatus() != DeletionOperationStatus.IN_PROGRESS) {
            throw new PlatformException("Deletion operation is already completed: " + operationId);
        }
    }

    private DeletionOperation requireOperation(String operationId) {
        requireText(operationId, "operationId");
        DeletionOperation operation = operationDao.findById(operationId);
        if (operation == null) {
            throw new PlatformException("Deletion operation does not exist: " + operationId);
        }
        return operation;
    }

    private void requireTerminal(DeletionOperationStatus status, String fieldName) {
        require(status, fieldName);
        if (status == DeletionOperationStatus.IN_PROGRESS) {
            throw new PlatformException("Deletion " + fieldName + " must be terminal");
        }
    }

    private void requireTerminal(DeletionEntryStatus status, String fieldName) {
        require(status, fieldName);
        if (status == DeletionEntryStatus.IN_PROGRESS) {
            throw new PlatformException("Deletion " + fieldName + " must be terminal");
        }
    }

    private void require(Object value, String fieldName) {
        if (value == null) {
            throw new PlatformException("Deletion " + fieldName + " must not be null");
        }
    }

    private String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new PlatformException("Deletion " + fieldName + " must not be blank");
        }
        return value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
