package net.ximatai.muyun.spring.starter.configuration.platform;

import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.*;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.schema.StaticSchemaService;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.deletion.*;
import net.ximatai.muyun.spring.starter.MuYunSpringAutoConfiguration;
import net.ximatai.muyun.spring.starter.configuration.database.MuYunSpringDatabaseConfiguration;
import net.ximatai.muyun.spring.starter.configuration.platform.fixture.MutationContractDao;
import net.ximatai.muyun.spring.starter.configuration.platform.fixture.MutationContractRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = StandardMutationRepositoryIT.Application.class, properties = {
        "muyun.database.repository-schema-mode=ENSURE", "muyun.runtime.mode=development"
})
class StandardMutationRepositoryIT {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @Autowired MutationContractDao dao;
    @Autowired Records records;
    @Autowired FailingLogService log;
    @Autowired SoftDeleteRestoreCoordinator restores;
    @Autowired RecycleBinPurgeCoordinator purges;
    @Autowired DeletionRecoveryExecutor recovery;
    @Autowired PlatformTransactionManager transactions;
    @Autowired IDatabaseOperations<?> database;

    @BeforeEach
    void prepare() {
        new StaticSchemaService(database).ensureTable(MutationContractRecord.class);
        records.failRestore.clear();
        records.failPurge.clear();
        records.committed.clear();
        log.rejectSuccessFor(null);
    }

    @Test
    void concurrentInsertConflictMustBeTranslatedAfterStatementRollback() {
        assertConcurrentConflict(false, false);
    }

    @Test
    void globalReadsMustComposeCacheAndRecycleBinWithoutLosingTenantIsolationElsewhere() {
        GlobalRecords global = new GlobalRecords(dao, records.jdbc);
        MutationContractRecord record = record("global-" + UUID.randomUUID());
        try (var ignored = TenantContext.system("global fixture")) {
            global.insert(record);
        }
        try (var ignored = TenantContext.use("another-tenant")) {
            assertThat(records.select(record.getId())).isNull();
            assertThat(global.select(record.getId())).isNotNull();
            assertThat(global.select(record.getId())).isNotNull();
            global.delete(record.getId());
            assertThat(global.select(record.getId())).isNull();
            assertThat(global.selectIgnoreSoftDelete(record.getId()).getDeleted()).isTrue();
            Criteria onlyRecord = Criteria.of().eq("id", record.getId());
            assertThat(global.pageRecycleBin(onlyRecord, PageRequest.of(1, 20)).getRecords())
                    .extracting(MutationContractRecord::getId).containsExactly(record.getId());
            assertThat(records.pageRecycleBin(onlyRecord, PageRequest.of(1, 20)).getRecords()).isEmpty();
            assertThat(global.canAccessRecycleBinSourceRecord(record.getId())).isTrue();
            assertThat(records.canAccessRecycleBinSourceRecord(record.getId())).isFalse();
            global.restore(record.getId());
            assertThat(global.select(record.getId()).getDeleted()).isFalse();
            assertThat(records.select(record.getId())).isNull();
        } finally {
            global.clearCache();
        }
    }

    @Test
    void concurrentUpdateConflictMustBeTranslatedWithoutChangingTheOriginalRecord() {
        assertConcurrentConflict(true, false);
    }

    @Test
    void concurrentRetainedIdentityMustStillOfferRecovery() {
        assertConcurrentConflict(false, true);
    }

    private void assertConcurrentConflict(boolean update, boolean retained) {
        try (var ignored = TenantContext.use("mutation-it")) {
            MutationContractRecord incoming = record("original-" + UUID.randomUUID());
            if (update) records.insert(incoming);
            String originalCode = incoming.getCode();
            String conflictingCode = "race-" + UUID.randomUUID();
            incoming.setCode(conflictingCode);
            MutationContractRecord winner = record(conflictingCode);
            TransactionTemplate competing = new TransactionTemplate(transactions);
            competing.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            Records racing = new Records(interceptWrite(update ? "updateByIdAndVersion" : "insert", () ->
                    competing.executeWithoutResult(status -> {
                        records.insert(winner);
                        if (retained) records.delete(winner.getId());
                    })), records.jdbc);

            assertThatThrownBy(() -> {
                if (update) racing.update(incoming); else racing.insert(incoming);
            }).isInstanceOfSatisfying(PlatformException.class, error -> {
                assertThat(error.httpStatus()).isEqualTo(409);
                assertThat(error.code()).isEqualTo(retained ? PlatformErrorCodes.RESOURCE_SOFT_DELETED_CONFLICT
                        : PlatformErrorCodes.CONFLICT_UNIQUE);
                if (retained) assertThat(error.details()).containsEntry("resourceRecordId", winner.getId())
                        .containsEntry("recoveryAvailable", true);
                else assertThat(error.getMessage()).isEqualTo("code already exists");
            });
            assertThat(dao.findById(winner.getId()).getCode()).isEqualTo(conflictingCode);
            if (update) assertThat(dao.findById(incoming.getId()).getCode()).isEqualTo(originalCode);
            else assertThat(dao.findById(incoming.getId())).isNull();
        }
    }

    @SuppressWarnings("unchecked")
    private BaseDao<MutationContractRecord, String> interceptWrite(String methodName, Runnable beforeWrite) {
        AtomicBoolean first = new AtomicBoolean(true);
        return (BaseDao<MutationContractRecord, String>) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{BaseDao.class}, (proxy, method, args) -> {
                    if (method.getName().equals(methodName) && first.compareAndSet(true, false)) beforeWrite.run();
                    try { return method.invoke(dao, args); }
                    catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
    }

    @Test
    void nestedStandardWritesMustRollbackTogether() {
        MutationContractRecord parent = record("parent-" + UUID.randomUUID());
        MutationContractRecord child = record("child-" + UUID.randomUUID());
        Records aggregate = new Records(dao, records.jdbc) {
            @Override public void afterInsert(String id, MutationContractRecord entity) {
                records.insert(child);
                throw new IllegalArgumentException("reject aggregate");
            }
        };
        assertThatThrownBy(() -> aggregate.insert(parent)).hasMessage("reject aggregate");
        assertThat(dao.findById(parent.getId())).isNull();
        assertThat(dao.findById(child.getId())).isNull();
    }

    @Test
    void restoreMustCommitSuccessfulNodesAndRollbackSqlFailedNodeBeforeLoggingFailure() {
        Source source = sourceTree();
        records.failRestore.add(source.child().getId());
        RestoreReport report = restores.restore(source.operationId());
        assertThat(report.entries()).extracting(RestoreEntryResult::status)
                .containsExactly(RestoreEntryResult.Status.RESTORED, RestoreEntryResult.Status.FAILED);
        assertThat(dao.findById(source.root().getId()).getDeleted()).isFalse();
        assertThat(dao.findById(source.child().getId()).getDeleted()).isTrue();
        assertThat(records.committed).containsExactly(source.root().getId());
        assertThat(log.operation(report.restoreOperationId()).getStatus()).isEqualTo(DeletionOperationStatus.PARTIALLY_SUCCEEDED);
        assertThat(log.operationEntries(report.restoreOperationId())).extracting(DeletionEntry::getStatus)
                .containsExactlyInAnyOrder(DeletionEntryStatus.SUCCEEDED, DeletionEntryStatus.FAILED);

        records.failRestore.clear();
        assertThat(restores.restore(source.operationId()).entries()).extracting(RestoreEntryResult::status)
                .containsOnly(RestoreEntryResult.Status.RESTORED);
        assertThat(records.committed).containsExactly(source.root().getId(), source.child().getId());
    }

    @Test
    void purgeMustRetainFailedParentAndContinueFromPreviouslyPurgedChild() {
        Source source = sourceTree();
        records.failPurge.add(source.root().getId());
        PurgeReport report = purges.purge(source.operationId());
        assertThat(report.entries()).extracting(PurgeEntryResult::status)
                .containsExactlyInAnyOrder(PurgeEntryResult.Status.FAILED, PurgeEntryResult.Status.PURGED);
        assertThat(dao.findById(source.root().getId()).getDeleted()).isTrue();
        assertThat(dao.findById(source.child().getId())).isNull();
        assertThat(records.committed).containsExactly(source.child().getId());
        assertThat(log.operation(report.purgeOperationId()).getStatus()).isEqualTo(DeletionOperationStatus.PARTIALLY_SUCCEEDED);

        records.failPurge.clear();
        assertThat(purges.purge(source.operationId()).entries()).extracting(PurgeEntryResult::status)
                .containsOnly(PurgeEntryResult.Status.PURGED);
        assertThat(dao.findById(source.root().getId())).isNull();
        assertThat(records.committed).containsExactly(source.child().getId(), source.root().getId());
    }

    @Test
    void successJournalFailureMustRollbackResourceAndSuppressCommitCallbacks() {
        Source source = sourceTree();
        log.rejectSuccessFor(source.root().getId());
        RestoreReport report = restores.restore(source.operationId());
        assertThat(report.entries()).extracting(RestoreEntryResult::status)
                .containsExactly(RestoreEntryResult.Status.FAILED, RestoreEntryResult.Status.SKIPPED);
        assertThat(dao.findById(source.root().getId()).getDeleted()).isTrue();
        assertThat(records.committed).isEmpty();
        assertThat(log.operationEntries(report.restoreOperationId())).extracting(DeletionEntry::getStatus)
                .containsExactlyInAnyOrder(DeletionEntryStatus.FAILED, DeletionEntryStatus.SKIPPED);
    }

    @Test
    void callerRollbackMustNotUndoAnExplicitBestEffortRecovery() {
        Source source = sourceTree();
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            restores.restore(source.operationId());
            status.setRollbackOnly();
        });
        assertThat(dao.findById(source.root().getId()).getDeleted()).isFalse();
        assertThat(dao.findById(source.child().getId()).getDeleted()).isFalse();
    }

    @Test
    void interruptedReportMustNotHideAlreadyCommittedRecoveryNodes() {
        Source source = sourceTree();
        DeletionOperation interrupted = operation(DeletionOperationType.RESTORE, source.root().getId());
        interrupted.setSourceOperationId(source.operationId());
        log.startOperation(interrupted);
        DeletionEntry success = entry(interrupted.getId(), null, source.root().getId());
        success.setSourceEntryId(source.rootEntryId());
        log.startEntry(success);
        recovery.restore(records, source.root().getId(), success.getId());
        assertThat(log.operation(interrupted.getId()).getStatus()).isEqualTo(DeletionOperationStatus.IN_PROGRESS);
        assertThat(restores.restore(source.operationId()).entries()).extracting(RestoreEntryResult::status)
                .containsOnly(RestoreEntryResult.Status.RESTORED);
        assertThat(records.committed).containsExactly(source.root().getId(), source.child().getId());
    }

    private Source sourceTree() {
        MutationContractRecord root = record("root-" + UUID.randomUUID());
        MutationContractRecord child = record("child-" + UUID.randomUUID());
        records.insert(root); records.insert(child);
        records.delete(root.getId()); records.delete(child.getId());
        DeletionOperation operation = operation(DeletionOperationType.DELETE, root.getId());
        log.startOperation(operation);
        String rootEntry = log.startEntry(entry(operation.getId(), null, root.getId()));
        String childEntry = log.startEntry(entry(operation.getId(), rootEntry, child.getId()));
        log.completeEntry(rootEntry, DeletionEntryStatus.SUCCEEDED, null);
        log.completeEntry(childEntry, DeletionEntryStatus.SUCCEEDED, null);
        log.completeOperation(operation.getId(), DeletionOperationStatus.SUCCEEDED, null);
        return new Source(operation.getId(), rootEntry, root, child);
    }

    private static MutationContractRecord record(String code) {
        MutationContractRecord record = new MutationContractRecord(); record.setCode(code); return record;
    }

    private static DeletionOperation operation(DeletionOperationType type, String rootId) {
        DeletionOperation operation = new DeletionOperation();
        operation.setOperationType(type); operation.setRootModuleAlias("test.records"); operation.setRootRecordId(rootId);
        return operation;
    }

    private static DeletionEntry entry(String operationId, String parentId, String recordId) {
        DeletionEntry entry = new DeletionEntry();
        entry.setOperationId(operationId); entry.setParentEntryId(parentId);
        entry.setResourceModuleAlias("test.records"); entry.setResourceRecordId(recordId);
        entry.setDeleteMode(DeletionEntryMode.SOFT);
        entry.setTriggerType(parentId == null ? DeletionEntryTrigger.DIRECT : DeletionEntryTrigger.CASCADE);
        return entry;
    }

    record Source(String operationId, String rootEntryId, MutationContractRecord root, MutationContractRecord child) {}

    static class Records extends AbstractAbilityService<MutationContractRecord> implements RecycleBinAbility<MutationContractRecord> {
        final Set<String> failRestore = new HashSet<>(), failPurge = new HashSet<>();
        final List<String> committed = new ArrayList<>();
        final JdbcTemplate jdbc;
        Records(BaseDao<MutationContractRecord, String> dao, JdbcTemplate jdbc) {
            super("test.records", MutationContractRecord.class, dao); this.jdbc = jdbc;
        }
        @Override public boolean isRecycleBinPurgeEnabled() { return true; }
        @Override public void beforeRecycleBinPurge(String id) {}
        @Override public void afterRestore(String id, MutationContractRecord record, int restored) {
            TransactionScopeSupport.afterCommitOrNow(() -> committed.add(id));
            if (failRestore.contains(id)) jdbc.execute("select 1 / 0");
        }
        @Override public void afterRecycleBinPurge(String id, MutationContractRecord record, int purged) {
            TransactionScopeSupport.afterCommitOrNow(() -> committed.add(id));
            if (failPurge.contains(id)) throw new IllegalArgumentException("reject purge after write");
        }
    }

    static class GlobalRecords extends Records implements GlobalScopedAbility<MutationContractRecord>, CacheAbility<MutationContractRecord> {
        GlobalRecords(BaseDao<MutationContractRecord, String> dao, JdbcTemplate jdbc) { super(dao, jdbc); }
    }

    static class FailingLogService extends DeletionLogService {
        private String rejectSuccessId;
        FailingLogService(DeletionOperationDao operations, DeletionEntryDao entries) { super(operations, entries); }
        public void rejectSuccessFor(String id) { rejectSuccessId = id; }
        @Override @Transactional public void completeEntry(String id, DeletionEntryStatus status, String message) {
            super.completeEntry(id, status, message);
            if (status == DeletionEntryStatus.SUCCEEDED && entry(id).getResourceRecordId().equals(rejectSuccessId)) {
                throw new IllegalArgumentException("journal rejected");
            }
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {MuYunSpringAutoConfiguration.class, MuYunSpringBusinessLoggingConfiguration.class})
    @EnableTransactionManagement(proxyTargetClass = true)
    @EnableMuYunRepositories(basePackageClasses = {MutationContractDao.class, DeletionOperationDao.class})
    @Import({MuYunSpringMutationConfiguration.class, MuYunSpringDatabaseConfiguration.class,
            DeletionRecoveryExecutor.class, SoftDeleteRestoreCoordinator.class, RecycleBinPurgeCoordinator.class})
    static class Application {
        @Bean DataSource dataSource() {
            return DataSourceBuilder.create().url(postgres.getJdbcUrl()).username(postgres.getUsername())
                    .password(postgres.getPassword()).driverClassName(postgres.getDriverClassName()).build();
        }
        @Bean Records records(MutationContractDao dao, JdbcTemplate jdbc) { return new Records(dao, jdbc); }
        @Bean FailingLogService log(DeletionOperationDao operations, DeletionEntryDao entries) {
            return new FailingLogService(operations, entries);
        }
        @Bean DeletionRecoveryResourceResolver resolver(Records records) {
            return new DeletionRecoveryResourceResolver() {
                public boolean supports(DeletionEntry entry) { return records.getModuleAlias().equals(entry.getResourceModuleAlias()); }
                public Optional<SoftDeleteAbility<?>> resolve(DeletionEntry entry) { return Optional.of(records); }
            };
        }
    }
}
