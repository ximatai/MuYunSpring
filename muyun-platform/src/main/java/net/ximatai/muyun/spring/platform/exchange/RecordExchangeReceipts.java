package net.ximatai.muyun.spring.platform.exchange;

import net.ximatai.muyun.database.core.orm.*;
import net.ximatai.muyun.spring.ability.*;
import net.ximatai.muyun.spring.common.id.Ids;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import net.ximatai.muyun.spring.common.tenant.TenantContext;

import org.springframework.stereotype.Repository;

import java.time.*;
import java.util.*;

@Repository
public class RecordExchangeReceipts {
    public static final Duration RETENTION = Duration.ofDays(30);
    private final RecordExchangeReceiptDao dao;

    public RecordExchangeReceipts(RecordExchangeReceiptDao dao) {
        this.dao = Objects.requireNonNull(dao);
    }

    public RecordExchangeReceipt document(String module, String purpose, String language) {
        var row = new RecordExchangeReceipt();
        row.setId(Ids.newId());
        row.setDocumentId(row.getId());
        row.setModuleAlias(module);
        row.setPurpose(purpose);
        row.setLanguageTag(language);
        initialize(row);
        dao.insert(row);
        return row;
    }

    public RecordExchangeReceipt find(String module, String id) {
        if (id == null || !id.matches("[a-zA-Z0-9-]{16,64}"))
            throw new IllegalArgumentException("File identity is invalid");
        CurrentUserContext.currentUser()
                .orElseThrow(
                        () -> new IllegalStateException("Exchange requires an authenticated user"));
        var row = dao.findById(id);
        if (row == null
                || !Objects.equals(row.getTenantId(), TenantContext.currentTenantId().orElse(null))
                || !module.equals(row.getModuleAlias())
                || !row.getExpiresAt().isAfter(Instant.now()))
            throw new IllegalArgumentException(
                    "File identity is unavailable or expired; export a new file");
        return row;
    }

    public RecordExchangeReceipt row(RecordExchangeReceipt document, String id, String entity) {
        var row = find(document.getModuleAlias(), id);
        if (!document.getId().equals(row.getDocumentId())
                || !entity.equals(row.getEntityAlias())
                || !document.getPurpose().equals(row.getPurpose()))
            throw new IllegalArgumentException("File row identity conflicts with its document");
        return row;
    }

    public RecordExchangeReceipt createRow(
            RecordExchangeReceipt document,
            String id,
            String entity,
            String parent,
            RecordExchangeAccess.Row record,
            String baseline) {
        var row = new RecordExchangeReceipt();
        row.setId(id == null ? Ids.newId() : id);
        row.setDocumentId(document.getId());
        row.setModuleAlias(document.getModuleAlias());
        row.setPurpose(document.getPurpose());
        row.setEntityAlias(entity);
        row.setParentId(parent);
        row.setBaselineJson(baseline);
        if (record != null) bind(row, record);
        initialize(row);
        row.setExpiresAt(document.getExpiresAt());
        dao.insert(row);
        return row;
    }

    public RecordExchangeReceipt newRowIfPresent(
            RecordExchangeReceipt document, String id, String entity) {
        var row = dao.findById(id);
        return row == null ? null : row(document, id, entity);
    }

    public void committed(
            RecordExchangeReceipt row, RecordExchangeAccess.Row saved, String digest) {
        Integer version = row.getVersion();
        bind(row, saved);
        row.setCommittedDigest(digest);
        EntityLifecycle.prepareUpdate(row, Instant.now());
        if (dao.updateByIdAndVersion(row, version) != 1)
            throw new OptimisticLockException("File row changed");
    }

    public void reviewed(RecordExchangeReceipt row, String operation) {
        Integer version = row.getVersion();
        row.setPlannedOperation(operation);
        EntityLifecycle.prepareUpdate(row, Instant.now());
        if (dao.updateByIdAndVersion(row, version) != 1)
            throw new OptimisticLockException("File review changed");
    }

    public void lock(RecordExchangeReceipt document, String rowKey) {
        if (!TransactionScopeSupport.isTransactionActive())
            throw new IllegalStateException("Exchange receipts require a transaction");
        PlatformAbilityRuntime.lockMutationPartition(
                "platform.record-exchange", document.getId() + ":" + rowKey);
    }

    public int purgeExpired(Instant now) {
        int removed = 0;
        for (var row :
                dao.query(
                        Criteria.of().lt("expiresAt", now),
                        PageRequest.of(1, 200),
                        Sort.asc("expiresAt"))) removed += dao.deleteById(row.getId());
        return removed;
    }

    private void initialize(RecordExchangeReceipt row) {
        CurrentUserContext.currentUser()
                .orElseThrow(
                        () -> new IllegalStateException("Exchange requires an authenticated user"));
        EntityLifecycle.prepareInsert(row, Instant.now());
        row.setTenantId(TenantContext.currentTenantId().orElse(null));
        row.setExpiresAt(Instant.now().plus(RETENTION));
    }

    private static void bind(RecordExchangeReceipt row, RecordExchangeAccess.Row record) {
        row.setRecordId(record.id());
        row.setRecordVersion(record.version());
        row.setRecordCreatedAt(record.createdAt());
    }
}
