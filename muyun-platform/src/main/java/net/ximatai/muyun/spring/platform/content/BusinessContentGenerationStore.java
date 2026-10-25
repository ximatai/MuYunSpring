package net.ximatai.muyun.spring.platform.content;

import net.ximatai.muyun.database.core.orm.*;
import net.ximatai.muyun.spring.ability.*;
import net.ximatai.muyun.spring.platform.job.*;
import org.springframework.beans.factory.ObjectProvider;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import org.springframework.stereotype.Repository;
import java.time.*;
import java.util.*;

/** Internal topic state; execution mechanics belong to platform jobs. Every mutation runs in its caller's short transaction. */
@Repository
class BusinessContentGenerationStore {
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();
    static final Duration RETENTION = Duration.ofDays(30);
    private final BusinessContentGenerationDao jobs;
    private final ObjectProvider<JobService> queue;
    private final JobRunDao runs;

    BusinessContentGenerationStore(BusinessContentGenerationDao jobs, ObjectProvider<JobService> queue, JobRunDao runs) {
        this.jobs = Objects.requireNonNull(jobs); this.queue = Objects.requireNonNull(queue); this.runs = Objects.requireNonNull(runs);
    }
    BusinessContentGeneration find(String id) { return jobs.findById(id); }
    JobRun run(BusinessContentGeneration row) { return row.getRunId() == null ? null : runs.findById(row.getRunId()); }

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
        row.setStatus("PENDING");
        EntityLifecycle.prepareInsert(row, now); row.setTenantId(source.tenantId());
        jobs.insert(row);
        var run = queue.getObject().enqueue(BusinessContentGenerationJob.type(source.tenantId()), source.tenantId(),
                Map.of("generationId", row.getId(), "slot", slotKey), key);
        row.setRunId(run.getId()); update(row, now);
        return row;
    }

    void lock(String id) {
        var row = jobs.findById(id);
        if (row != null) BusinessContentStore.lockRecord(source(row));
    }

    /** Platform transitions are mirrored so topic history remains authoritative after run retention. */
    void stateChanged(JobRun run) {
        String id = (String) readParameters(run).get("generationId");
        var row = jobs.findById(id);
        if (row == null || !Objects.equals(run.getId(), row.getRunId())
                || !List.of("PENDING", "RUNNING", "RETRY").contains(row.getStatus())) return;
        row.setStatus(run.getStatus());
        row.setProblemCode(List.of("PENDING", "RUNNING").contains(run.getStatus()) ? null : run.getResultCode());
        row.setFinishedAt(run.getFinishedAt()); update(row, run.getUpdatedAt());
    }
    private static Map<String, Object> readParameters(JobRun run) {
        try { return JSON.readValue(run.getParametersJson(),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { }); }
        catch (Exception invalid) { throw new IllegalStateException("Invalid generation reference", invalid); }
    }

    void finish(BusinessContentGeneration row, String status, String problem, Instant now) {
        row.setStatus(status); row.setProblemCode(problem); row.setFinishedAt(now);
        update(row, now);
    }

    void purgeRecord(String tenant, String module, String entity, String record) {
        BusinessContentStore.lockRecord(new BusinessContentSource(tenant, module, entity, record, ""));
        var criteria = Criteria.of().eqNullable("tenantId", tenant).eq("moduleAlias", module)
                .eq("entityKey", entity).eq("recordId", record);
        while (true) {
            var rows = jobs.query(criteria, PageRequest.of(1, 200));
            if (rows.isEmpty()) return;
            rows.forEach(row -> {
                if (row.getRunId() != null) queue.getObject().purgeUnfinished(
                        BusinessContentGenerationJob.type(row.getTenantId()), row.getTenantId(), row.getRunId());
                jobs.deleteById(row.getId());
            });
        }
    }

    List<String> expired(Instant now) {
        return jobs.query(Criteria.of().lt("finishedAt", now.minus(RETENTION)),
                PageRequest.of(1, 200), Sort.asc("finishedAt"), Sort.asc("id"))
                .stream().map(BusinessContentGeneration::getId).toList();
    }

    int purgeExpired(String id, Instant now) {
        var row = jobs.findById(id);
        return row != null && row.getFinishedAt() != null && row.getFinishedAt().isBefore(now.minus(RETENTION))
                ? jobs.deleteById(row.getId()) : 0;
    }

    private void update(BusinessContentGeneration row, Instant now) {
        Integer expected = row.getVersion(); EntityLifecycle.prepareUpdate(row, now);
        if (jobs.updateByIdAndVersion(row, expected) != 1) throw new OptimisticLockException("Translation generation changed");
    }
    static BusinessContentSource source(BusinessContentGeneration row) {
        return new BusinessContentSource(row.getTenantId(), row.getModuleAlias(), row.getEntityKey(), row.getRecordId(), row.getFieldKey());
    }
}
