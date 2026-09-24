package net.ximatai.muyun.spring.platform.deletion;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.ability.deletion.DeletionContext;
import net.ximatai.muyun.spring.ability.deletion.DeletionLifecycleListener;
import net.ximatai.muyun.spring.ability.deletion.DeletionLifecycleSession;
import net.ximatai.muyun.spring.ability.deletion.DeletionMode;
import net.ximatai.muyun.spring.ability.deletion.DeletionNode;
import net.ximatai.muyun.spring.ability.deletion.DeletionResource;
import net.ximatai.muyun.spring.ability.deletion.DeletionTrigger;
import net.ximatai.muyun.spring.common.id.Ids;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;

/**
 * Creates one explicit operation journal for each shared Ability deletion tree.
 *
 * <p>The journal travels with {@link DeletionContext}; it is not reconstructed
 * from a thread-local store. This keeps recursive deletion observable and
 * correct even when execution boundaries change.</p>
 */
@Component
public class DeletionLogLifecycleListener implements DeletionLifecycleListener {
    private final DeletionLogService logService;

    public DeletionLogLifecycleListener(DeletionLogService logService) {
        this.logService = Objects.requireNonNull(logService, "logService must not be null");
    }

    @Override
    public DeletionLifecycleSession open(DeletionResource root) {
        return new Journal(logService, root);
    }

    private static final class Journal implements DeletionLifecycleSession {
        private final DeletionLogService logService;
        private final DeletionResource root;
        private final java.util.Map<String, DeletionEntry> entries = new java.util.LinkedHashMap<>();
        private DeletionOperation operation;

        private Journal(DeletionLogService logService, DeletionResource root) {
            this.logService = logService;
            this.root = root;
        }

        @Override
        public DeletionNode started(CrudAbility<?> ability,
                                    EntityContract entity,
                                    DeletionContext context,
                                    DeletionMode mode) {
            ensureOperation(context, entity, ability);
            String entryId = Ids.newId();
            DeletionEntry entry = new DeletionEntry();
            entry.setId(entryId);
            entry.setTenantId(entity.getTenantId());
            entry.setOperationId(context.operationId());
            entry.setParentEntryId(context.parentEntryId());
            entry.setResourceModuleAlias(ability.getModuleAlias());
            entry.setResourceEntityAlias(DeletionResourceIdentity.of(ability).entityAlias());
            entry.setResourceRecordId(entity.getId());
            entry.setTriggerType(context.trigger() == DeletionTrigger.CASCADE
                    ? DeletionEntryTrigger.CASCADE : DeletionEntryTrigger.DIRECT);
            entry.setDeleteMode(mode == DeletionMode.SOFT ? DeletionEntryMode.SOFT : DeletionEntryMode.HARD);
            entry.setResourceVersion(entity.getVersion());
            entry.setStatus(DeletionEntryStatus.IN_PROGRESS);
            entry.setStartedAt(Instant.now());
            entries.put(entryId, entry);
            return new DeletionNode(entryId, new DeletionResource(ability.getModuleAlias(), entity.getId()));
        }

        @Override
        public void succeeded(CrudAbility<?> ability,
                              EntityContract entity,
                              DeletionContext context,
                              DeletionNode node,
                              DeletionMode mode) {
            // Recovery binds to the retained version actually committed by this deletion.
            entries.get(node.entryId()).setResourceVersion(entity.getVersion());
            completeEntry(node.entryId(), DeletionEntryStatus.SUCCEEDED, null);
            if (context.trigger() == DeletionTrigger.DIRECT) {
                flush(context.operationId(), DeletionOperationStatus.SUCCEEDED, null);
            }
        }

        @Override
        public void failed(CrudAbility<?> ability,
                           EntityContract entity,
                           DeletionContext context,
                           DeletionNode node,
                           DeletionMode mode,
                           RuntimeException failure) {
            String message = failure == null ? null : failure.getMessage();
            completeEntry(node.entryId(), DeletionEntryStatus.FAILED, message);
            if (context.trigger() == DeletionTrigger.DIRECT) {
                flush(context.operationId(), DeletionOperationStatus.FAILED, message);
            }
        }

        private void ensureOperation(DeletionContext context, EntityContract entity, CrudAbility<?> ability) {
            if (operation != null) {
                return;
            }
            operation = new DeletionOperation();
            operation.setId(context.operationId());
            operation.setTenantId(entity.getTenantId());
            operation.setOperationType(DeletionOperationType.DELETE);
            operation.setStatus(DeletionOperationStatus.IN_PROGRESS);
            operation.setRootModuleAlias(root.moduleAlias());
            operation.setRootEntityAlias(DeletionResourceIdentity.of(ability).entityAlias());
            operation.setRootRecordId(root.recordId());
            operation.setOperatorId(CurrentUserContext.currentUser().map(user -> user.userId()).orElse(null));
            operation.setStartedAt(Instant.now());
        }

        private void completeEntry(String entryId, DeletionEntryStatus status, String message) {
            DeletionEntry entry = entries.get(entryId);
            if (entry == null || entry.getStatus() != DeletionEntryStatus.IN_PROGRESS) {
                return;
            }
            entry.setStatus(status);
            entry.setResultMessage(blankToNull(message));
            entry.setCompletedAt(Instant.now());
        }

        private void flush(String operationId, DeletionOperationStatus status, String message) {
            if (operation == null) {
                return;
            }
            logService.startOperation(operation);
            for (DeletionEntry entry : entries.values()) {
                DeletionEntryStatus terminalStatus = entry.getStatus();
                String terminalMessage = entry.getResultMessage();
                entry.setStatus(DeletionEntryStatus.IN_PROGRESS);
                entry.setResultMessage(null);
                entry.setCompletedAt(null);
                logService.startEntry(entry);
                if (terminalStatus == DeletionEntryStatus.IN_PROGRESS) {
                    terminalStatus = DeletionEntryStatus.SKIPPED;
                    terminalMessage = "Deletion operation ended before this resource completed";
                }
                logService.completeEntry(entry.getId(), terminalStatus, terminalMessage);
            }
            logService.completeOperation(operationId, status, blankToNull(message));
        }

        private String blankToNull(String text) {
            return text == null || text.isBlank() ? null : text;
        }
    }
}
