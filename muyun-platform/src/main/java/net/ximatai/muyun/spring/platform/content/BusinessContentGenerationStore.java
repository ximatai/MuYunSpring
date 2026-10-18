package net.ximatai.muyun.spring.platform.content;

import net.ximatai.muyun.database.core.orm.*;
import net.ximatai.muyun.spring.ability.*;
import net.ximatai.muyun.spring.common.id.Ids;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import org.springframework.stereotype.Repository;
import java.time.*;
import java.util.*;

/** Internal operational state. Every mutation runs in its caller's short transaction. */
@Repository
class BusinessContentGenerationStore {
    static final int MAX_ATTEMPTS = 3;
    // The current text gateway bounds connection/headers and body separately to 60s each.
    // A lease also fences longer replacement gateways; an expired call can never append a result.
    static final Duration LEASE = Duration.ofMinutes(5);
    static final Duration RETENTION = Duration.ofDays(30);
    private final BusinessContentGenerationDao jobs;

    BusinessContentGenerationStore(BusinessContentGenerationDao jobs) { this.jobs = Objects.requireNonNull(jobs); }

    BusinessContentGeneration latest(String slotKey) {
        return jobs.query(Criteria.of().eq("slotKey", slotKey), PageRequest.of(1, 1), Sort.desc("sequence"))
                .stream().findFirst().orElse(null);
    }

    BusinessContentGeneration insert(BusinessContentSource source, String slotKey, int sequence, String key, String request, Instant now) {
        BusinessContentStore.lockRecord(source);
        var row = new BusinessContentGeneration();
        row.setSourceKey(source.key()); row.setModuleAlias(source.moduleAlias()); row.setEntityKey(source.entityKey());
        row.setRecordId(source.recordId()); row.setFieldKey(source.fieldKey()); row.setRequestKey(key); row.setRequestJson(request);
        row.setSlotKey(slotKey); row.setSequence(sequence);
        row.setStatus("PENDING"); row.setAttempts(0); row.setAvailableAt(now);
        EntityLifecycle.prepareInsert(row, now); row.setTenantId(source.tenantId());
        jobs.insert(row);
        return row;
    }

    /** At most twenty candidates; competing workers use row-specific transaction locks and reread. */
    List<String> candidates(Instant now) {
        return jobs.query(Criteria.of()
                        .or(Criteria.of().in("status", List.of("PENDING", "RETRY")).lte("availableAt", now))
                        .or(Criteria.of().eq("status", "RUNNING").lte("leaseUntil", now)),
                PageRequest.of(1, 20), Sort.asc("availableAt"), Sort.asc("id"))
                .stream().map(BusinessContentGeneration::getId).toList();
    }

    BusinessContentGeneration claim(String id, Instant now) {
        var row = locked(id);
        if (row == null || !eligible(row, now)) return null;
        if (row.getAttempts() >= MAX_ATTEMPTS) {
            finish(row, "FAILED", "ATTEMPTS_EXHAUSTED", now);
            return null;
        }
        row.setStatus("RUNNING"); row.setAttempts(row.getAttempts() + 1); row.setLeaseToken(Ids.newId());
        row.setLeaseUntil(now.plus(LEASE)); row.setProblemCode(null); row.setFinishedAt(null);
        update(row, now);
        return row;
    }

    BusinessContentGeneration leased(String id, String token, Instant now) {
        var row = locked(id);
        return row != null && "RUNNING".equals(row.getStatus()) && Objects.equals(token, row.getLeaseToken())
                && row.getLeaseUntil() != null && now.isBefore(row.getLeaseUntil()) ? row : null;
    }

    void retry(BusinessContentGeneration row, String problem, Instant now) {
        if (row.getAttempts() >= MAX_ATTEMPTS) { finish(row, "FAILED", problem, now); return; }
        row.setStatus("RETRY"); row.setProblemCode(problem);
        row.setAvailableAt(now.plusSeconds(15L * row.getAttempts()));
        row.setLeaseToken(null); row.setLeaseUntil(null); update(row, now);
    }

    void finish(BusinessContentGeneration row, String status, String problem, Instant now) {
        row.setStatus(status); row.setProblemCode(problem); row.setFinishedAt(now);
        row.setLeaseToken(null); row.setLeaseUntil(null); update(row, now);
    }

    void purgeRecord(String tenant, String module, String entity, String record) {
        BusinessContentStore.lockRecord(new BusinessContentSource(tenant, module, entity, record, ""));
        var criteria = Criteria.of().eqNullable("tenantId", tenant).eq("moduleAlias", module)
                .eq("entityKey", entity).eq("recordId", record);
        while (true) {
            var rows = jobs.query(criteria, PageRequest.of(1, 200));
            if (rows.isEmpty()) return;
            rows.forEach(row -> jobs.deleteById(row.getId()));
        }
    }

    int purgeExpired(Instant now) {
        var rows = jobs.query(Criteria.of().lt("finishedAt", now.minus(RETENTION)),
                PageRequest.of(1, 200), Sort.asc("finishedAt"), Sort.asc("id"));
        int removed = 0;
        for (var candidate : rows) {
            var row = locked(candidate.getId());
            if (row != null && row.getFinishedAt() != null && row.getFinishedAt().isBefore(now.minus(RETENTION)))
                removed += jobs.deleteById(row.getId());
        }
        return removed;
    }

    private BusinessContentGeneration locked(String id) {
        if (!TransactionScopeSupport.isTransactionActive()) throw new IllegalStateException("Generation state requires a transaction");
        PlatformAbilityRuntime.lockMutationPartition("platform.business-content-generation", id);
        return jobs.findById(id);
    }
    private void update(BusinessContentGeneration row, Instant now) {
        Integer expected = row.getVersion(); EntityLifecycle.prepareUpdate(row, now);
        if (jobs.updateByIdAndVersion(row, expected) != 1) throw new OptimisticLockException("Translation generation changed");
    }
    private static boolean eligible(BusinessContentGeneration row, Instant now) {
        return List.of("PENDING", "RETRY").contains(row.getStatus()) && !row.getAvailableAt().isAfter(now)
                || "RUNNING".equals(row.getStatus()) && row.getLeaseUntil() != null && !row.getLeaseUntil().isAfter(now);
    }
}
