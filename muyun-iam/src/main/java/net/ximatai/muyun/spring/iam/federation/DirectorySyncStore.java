package net.ximatai.muyun.spring.iam.federation;

import com.fasterxml.jackson.databind.ObjectMapper;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.orm.*;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.OptimisticLockException;
import net.ximatai.muyun.spring.ability.TransactionScopeSupport;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.platform.job.*;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.Objects;

/** Topic persistence. Every writer is called inside its owning fenced job/configuration transaction. */
@Component
@ConditionalOnExternalIdentityFederation
final class DirectorySyncStore {
    static final ObjectMapper JSON = new ObjectMapper();
    final DirectorySyncRunDao runs;
    final DirectorySyncScopeDao scopes;
    final DirectoryDepartmentMappingDao mappings;
    final DirectorySyncSeenDao seen;
    final DirectorySyncDepartmentDao departments;
    final IDatabaseOperations<?> database;
    private final TenantIdentityPolicyDao policies;

    DirectorySyncStore(
            DirectorySyncRunDao runs,
            DirectorySyncScopeDao scopes,
            DirectoryDepartmentMappingDao mappings,
            DirectorySyncSeenDao seen,
            DirectorySyncDepartmentDao departments,
            IDatabaseOperations<?> database,
            TenantIdentityPolicyDao policies) {
        this.runs = Objects.requireNonNull(runs, "runs");
        this.scopes = Objects.requireNonNull(scopes, "scopes");
        this.mappings = Objects.requireNonNull(mappings, "mappings");
        this.seen = Objects.requireNonNull(seen, "seen");
        this.departments = Objects.requireNonNull(departments, "departments");
        this.database = Objects.requireNonNull(database, "database");
        this.policies = Objects.requireNonNull(policies, "policies");
    }

    void lock(Map<String, Object> input) {
        if (!TransactionScopeSupport.isTransactionActive())
            throw new IllegalStateException("Topic lock requires transaction");
        database.query(
                "select pg_advisory_xact_lock(hashtextextended(?, 0))",
                "iam.directory:" + input.get("tenantId") + ":" + input.get("providerId"));
    }

    TenantIdentityPolicy policy(String id) { return policies.findById(id); }

    void stateChanged(JobRun job) {
        var rows =
                runs.query(
                        Criteria.of().eq("tenantId", job.getTenantId()).eq("jobRunId", job.getId()),
                        new PageRequest(0, 1));
        if (rows.isEmpty()) return;
        var row = rows.getFirst();
        row.setStatus(job.getStatus());
        save(runs, row);
    }

    void planStateChanged(JobPlan plan) {
        var row = policies.findById(plan.getObjectKey());
        if (row != null
                && Objects.equals(row.getTenantId(), plan.getTenantId())
                && Objects.equals(row.getSyncPlanId(), plan.getId())) {
            row.setSyncEnabled(plan.getEnabled());
            save(policies, row);
        }
    }

    static <T extends StandardEntity> void insert(BaseDao<T, String> dao, T row) {
        EntityLifecycle.prepareInsert(row, Instant.now());
        dao.insert(row);
    }

    static <T extends StandardEntity> void save(BaseDao<T, String> dao, T row) {
        int version = row.getVersion();
        EntityLifecycle.prepareUpdate(row, Instant.now());
        if (dao.updateByIdAndVersion(row, version) != 1)
            throw new OptimisticLockException("Directory fact changed");
    }

    static String encode(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid directory fact", invalid);
        }
    }

    static <T> T decode(String value, Class<T> type) {
        try {
            return JSON.readValue(value, type);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid directory fact", invalid);
        }
    }

    DirectoryDepartmentMapping mapping(String tenant, String provider, String externalId) {
        return mappings
                .query(
                        Criteria.of()
                                .eq("tenantId", tenant)
                                .eq("providerId", provider)
                                .eq("externalDepartmentId", externalId),
                        new PageRequest(0, 1))
                .stream()
                .findFirst()
                .orElse(null);
    }
}
