package net.ximatai.muyun.spring.platform.runtime;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.OptimisticLockException;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.TransactionScopeSupport;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordRuntime;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicRuntimePublication;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationService;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataOrchestrationService;
import net.ximatai.muyun.spring.ability.event.RuntimeEvent;
import net.ximatai.muyun.spring.ability.event.RuntimeEventType;
import net.ximatai.muyun.spring.ability.event.RuntimeEventPublisher;
import net.ximatai.muyun.spring.ability.event.RuntimeMutationSource;
import net.ximatai.muyun.spring.platform.metadata.RelationRole;
import net.ximatai.muyun.spring.platform.ui.PublishedPageExecutionCoordinator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.concurrent.ConcurrentHashMap;

/** Commits activation intent with configuration, then installs current committed projections in a new transaction. */
@Service
public class DynamicRuntimeActivationService {
    private static final Logger LOG = LoggerFactory.getLogger(DynamicRuntimeActivationService.class);
    private final DynamicRuntimePublication publication;
    private final DynamicRuntimeActivationDao dao;
    private final PlatformDynamicRuntimeRefresher refresher;
    private final ObjectProvider<ModuleMetadataRelationService> relations;
    private final ObjectProvider<PublishedPageExecutionCoordinator> pages;
    private final ObjectProvider<ModuleMetadataOrchestrationService> orchestration;
    private final ObjectProvider<RuntimeEventPublisher> events;
    private final TransactionTemplate required;
    private final TransactionTemplate independent;
    private final Map<String, Integer> installed = new ConcurrentHashMap<>();

    public DynamicRuntimeActivationService(DynamicRuntimeActivationDao dao, PlatformDynamicRuntimeRefresher refresher,
            ObjectProvider<ModuleMetadataRelationService> relations, ObjectProvider<PublishedPageExecutionCoordinator> pages,
            ObjectProvider<ModuleMetadataOrchestrationService> orchestration,
            ObjectProvider<RuntimeEventPublisher> events, PlatformTransactionManager transactions,
            DynamicRecordRuntime runtime) {
        this.publication = runtime.publication();
        this.dao = dao;
        this.refresher = refresher;
        this.relations = relations;
        this.pages = pages;
        this.orchestration = orchestration;
        this.events = events;
        required = new TransactionTemplate(transactions);
        independent = new TransactionTemplate(transactions);
        independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Must be called before the configuration transaction commits. Repeated calls coalesce by desired revision. */
    public void schedule(String moduleAlias) {
        String alias = PlatformNameRules.requireModuleAlias(moduleAlias);
        required.executeWithoutResult(tx -> {
            lock(alias);
            DynamicRuntimeActivation state = find(alias);
            if (state == null) {
                state = new DynamicRuntimeActivation();
                state.setModuleAlias(alias);
                state.setDesiredRevision(1);
                state.setStatus("PENDING");
                EntityLifecycle.prepareInsert(state, Instant.now());
                state.setTenantId(null);
                dao.insert(state);
            } else {
                state.setDesiredRevision(Math.addExact(state.getDesiredRevision(), 1));
                state.setStatus("PENDING");
                state.setFailureMessage(null);
                save(state);
            }
            int revision = state.getDesiredRevision();
            TransactionScopeSupport.afterCommitOrNow(() -> attempt(alias, revision));
        });
    }

    public Status status(String moduleAlias) {
        String alias = PlatformNameRules.requireModuleAlias(moduleAlias);
        DynamicRuntimeActivation state = find(alias);
        return state == null ? new Status(alias, null, null, null, "UNTRACKED", null, null)
                : new Status(alias, state.getDesiredRevision(), state.getActiveRevision(), installed.get(alias),
                        state.getStatus(), state.getFailureMessage(), state.getAttemptedAt());
    }

    public Status retry(String moduleAlias, int expectedRevision) {
        String alias = PlatformNameRules.requireModuleAlias(moduleAlias);
        // User retries reject stale requests. Commit callbacks quietly skip superseded revisions.
        attempt(alias, expectedRevision, true);
        return status(alias);
    }

    /** In-memory projections must be rebuilt even when durable history says a previous process succeeded. */
    public void restoreAtStartup(String moduleAlias) {
        DynamicRuntimeActivation state = find(moduleAlias);
        if (state == null) schedule(moduleAlias);
        else attempt(moduleAlias, state.getDesiredRevision());
    }

    private void attempt(String alias, int revision) { attempt(alias, revision, false); }

    private void attempt(String alias, int revision, boolean rejectStale) {
        try (var installedScope = publication.publication();
             var ignored = TenantContext.system("activate committed dynamic configuration")) {
            AtomicReference<Integer> attemptedVersion = new AtomicReference<>();
            try {
                independent.executeWithoutResult(tx -> {
                    lock(alias);
                    DynamicRuntimeActivation state = find(alias);
                    if (state == null || state.getDesiredRevision() != revision) {
                        if (rejectStale) throw new OptimisticLockException("配置版本已变化，请刷新后重试激活");
                        return;
                    }
                    attemptedVersion.set(state.getVersion());
                    installed.remove(alias);
                    try {
                        boolean hasMain = !relations.getObject().list(Criteria.of().eq("moduleAlias", alias)
                                .eq("relationRole", RelationRole.MAIN), PageRequest.of(1, 1)).isEmpty();
                        if (hasMain) {
                            orchestration.getObject().reconcileChildSystemFields(alias);
                            refresher.activateNow(alias);
                        } else refresher.deactivateNow(alias);
                        pages.orderedStream().forEach(page -> {
                            if (hasMain) page.installCurrentPublishedConfiguration(alias);
                            else page.removeInstalledConfiguration(alias);
                        });
                        state.setActiveRevision(revision);
                        state.setStatus(hasMain ? "ACTIVE" : "INACTIVE");
                        state.setFailureMessage(null);
                        state.setAttemptedAt(Instant.now());
                        save(state);
                        // Installed means entity and page projections have both completed in this process.
                        installed.put(alias, revision);
                        if (hasMain) TransactionScopeSupport.afterCommitOrNow(() -> publishActivated(alias, revision));
                    } catch (RuntimeException failure) {
                        installed.remove(alias);
                        try { withdrawRuntime(alias); }
                        catch (RuntimeException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
                        throw failure;
                    }
                });
            } catch (RuntimeException failure) {
                if (rejectStale && attemptedVersion.get() == null && failure instanceof OptimisticLockException) throw failure;
                LOG.warn("Committed configuration activation failed for {} revision {}", alias, revision, failure);
                try {
                    independent.executeWithoutResult(tx -> {
                        lock(alias);
                        DynamicRuntimeActivation state = find(alias);
                        if (state == null || state.getDesiredRevision() != revision
                                || !Objects.equals(state.getVersion(), attemptedVersion.get())) return;
                        // A commit failure can occur after the complete pair was installed.
                        if (installed.remove(alias) != null) withdrawRuntime(alias);
                        state.setStatus("FAILED");
                        state.setFailureMessage("运行态激活失败，请查看服务日志并重试（" + failure.getClass().getSimpleName() + "）");
                        state.setAttemptedAt(Instant.now());
                        save(state);
                    });
                } catch (RuntimeException recordingFailure) {
                    // The durable PENDING intent survives even when diagnostics cannot be written.
                    LOG.error("Could not record activation failure for {}", alias, recordingFailure);
                }
            }
        }
    }

    private void withdrawRuntime(String alias) {
        // The committed schema may no longer fit the previous runtime. Never keep a mixed entity/page pair executable.
        try { refresher.deactivateNow(alias); }
        finally { pages.orderedStream().forEach(page -> page.removeInstalledConfiguration(alias)); }
    }

    private void publishActivated(String alias, int revision) {
        try {
            events.getIfAvailable(RuntimeEventPublisher::noop).publish(RuntimeEvent.of(
                    RuntimeEventType.MODULE_REFRESHED, alias, null, null, null, null,
                    true, "activate committed dynamic configuration", RuntimeMutationSource.SYSTEM,
                    Map.of("activationRevision", revision)));
        } catch (RuntimeException failure) {
            // Projections and their result are committed. A subscriber failure must not turn them into FAILED.
            LOG.error("Activation event delivery failed for {} revision {}", alias, revision, failure);
        }
    }

    public List<String> trackedModuleAliases() {
        return dao.query(Criteria.of().isNull("tenantId"), new PageRequest(0, Integer.MAX_VALUE))
                .stream().map(DynamicRuntimeActivation::getModuleAlias).toList();
    }

    private DynamicRuntimeActivation find(String alias) {
        return dao.query(Criteria.of().eq("moduleAlias", alias).isNull("tenantId"), PageRequest.of(1, 1))
                .stream().findFirst().orElse(null);
    }

    private void lock(String alias) {
        PlatformAbilityRuntime.lockMutationPartition("platform.dynamic-runtime-activation", alias);
    }

    private void save(DynamicRuntimeActivation state) {
        int version = state.getVersion();
        EntityLifecycle.prepareUpdate(state, Instant.now(), version + 1);
        if (dao.updateByIdAndVersion(state, version) != 1) throw new OptimisticLockException("运行态激活状态已变化");
    }

    public record Status(String moduleAlias, Integer desiredRevision, Integer lastSuccessfulRevision,
                         Integer installedRevision, String status, String failureMessage, Instant attemptedAt) {}
}
