package net.ximatai.muyun.spring.platform.deletion;

import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.RecycleBinAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecycleBinPurgeCoordinatorTest {
    private final TestMemoryDao<DeletionOperation> operationDao = new TestMemoryDao<>();
    private final TestMemoryDao<DeletionEntry> entryDao = new TestMemoryDao<>();
    private final DeletionLogService logService = new DeletionLogService(operationDao, entryDao);
    private final DeletionRecoveryExecutor recovery = new DeletionRecoveryExecutor(logService);

    @Test
    void shouldPurgeWholeSourceTreeAndWriteAuditEntries() {
        SourceTree source = completedDeleteTree();
        PurgeAbility tenant = purgeableAbility("iam.tenant", "tenant", "tenant-1");
        PurgeAbility application = purgeableAbility("iam.tenantApplication", "tenantApplication", "application-1");

        PurgeReport report = new RecycleBinPurgeCoordinator(logService, recovery, resolver(tenant, application))
                .purge(source.operationId());

        assertThat(report.sourceOperationId()).isEqualTo(source.operationId());
        assertThat(report.purgeOperationId()).isNotBlank();
        assertThat(report.entries()).extracting(PurgeEntryResult::status)
                .containsExactly(PurgeEntryResult.Status.PURGED, PurgeEntryResult.Status.PURGED);
        assertThat(logService.operation(report.purgeOperationId()))
                .extracting(DeletionOperation::getOperationType, DeletionOperation::getStatus,
                        DeletionOperation::getSourceOperationId, DeletionOperation::getRootRecordId)
                .containsExactly(DeletionOperationType.PURGE, DeletionOperationStatus.SUCCEEDED,
                        source.operationId(), "tenant-1");
        assertThat(logService.operationEntries(report.purgeOperationId()))
                .extracting(DeletionEntry::getSourceEntryId, DeletionEntry::getParentEntryId,
                        DeletionEntry::getStatus)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(source.rootEntryId(), null, DeletionEntryStatus.SUCCEEDED),
                        org.assertj.core.groups.Tuple.tuple(source.childEntryId(),
                                logService.operationEntries(report.purgeOperationId()).getFirst().getId(),
                                DeletionEntryStatus.SUCCEEDED));
        assertThat(tenant.dao().findById("tenant-1")).isNull();
        assertThat(application.dao().findById("application-1")).isNull();
    }

    @Test
    void shouldFailRootAndSkipDescendantsWhenPurgeDenied() {
        SourceTree source = completedDeleteTree();
        PurgeAbility deniedTenant = deniedAbility("iam.tenant", "tenant", "tenant-1");
        PurgeAbility application = purgeableAbility("iam.tenantApplication", "tenantApplication", "application-1");

        PurgeReport report = new RecycleBinPurgeCoordinator(logService, recovery, resolver(deniedTenant, application))
                .purge(source.operationId());

        assertThat(report.entries()).extracting(PurgeEntryResult::status)
                .containsExactly(PurgeEntryResult.Status.FAILED, PurgeEntryResult.Status.SKIPPED);
        assertThat(report.entries().getFirst().message()).contains("not enabled");
        assertThat(logService.operation(report.purgeOperationId()).getStatus())
                .isEqualTo(DeletionOperationStatus.FAILED);
        assertThat(application.dao().findById("application-1")).isNotNull();
    }

    @Test
    void shouldAllowPurgeRetryAfterARejectedAttempt() {
        SourceTree source = completedDeleteTree();
        PurgeAbility deniedTenant = deniedAbility("iam.tenant", "tenant", "tenant-1");
        PurgeAbility application = purgeableAbility("iam.tenantApplication", "tenantApplication", "application-1");

        new RecycleBinPurgeCoordinator(logService, recovery, resolver(deniedTenant, application)).purge(source.operationId());
        PurgeReport retry = new RecycleBinPurgeCoordinator(logService, recovery,
                resolver(purgeableAbility("iam.tenant", "tenant", "tenant-1"), application)).purge(source.operationId());

        assertThat(retry.entries()).extracting(PurgeEntryResult::status)
                .containsExactly(PurgeEntryResult.Status.PURGED, PurgeEntryResult.Status.PURGED);
    }

    @Test
    void shouldNotMarkAnAllSkippedPurgeAsSucceeded() {
        SourceTree source = completedDeleteTree();

        PurgeReport report = new RecycleBinPurgeCoordinator(logService, recovery, List.of())
                .purge(source.operationId());

        assertThat(report.entries()).extracting(PurgeEntryResult::status)
                .containsExactly(PurgeEntryResult.Status.SKIPPED, PurgeEntryResult.Status.SKIPPED);
        assertThat(report.entries().getFirst().message()).contains("no deletion recovery resolver");
        assertThat(logService.operation(report.purgeOperationId()).getStatus())
                .isEqualTo(DeletionOperationStatus.PARTIALLY_SUCCEEDED);
    }

    @Test
    void shouldSkipResourceWhoseLifecycleChangedAfterSourceDeletion() {
        SourceTree source = completedDeleteTree();
        PurgeAbility tenant = purgeableAbility("iam.tenant", "tenant", "tenant-1");
        PurgeAbility application = purgeableAbility("iam.tenantApplication", "tenantApplication", "application-1");
        simulateRestore(source.childEntryId(), "iam.tenantApplication", "tenantApplication", "application-1");

        PurgeReport report = new RecycleBinPurgeCoordinator(logService, recovery, resolver(tenant, application))
                .purge(source.operationId());

        assertThat(report.entries()).extracting(PurgeEntryResult::status)
                .containsExactly(PurgeEntryResult.Status.SKIPPED, PurgeEntryResult.Status.SKIPPED);
        assertThat(report.entries().get(1).message()).contains("lifecycle changed");
        assertThat(logService.operation(report.purgeOperationId()).getStatus())
                .isEqualTo(DeletionOperationStatus.PARTIALLY_SUCCEEDED);
    }

    @Test
    void shouldRejectAmbiguousResolverMatches() {
        SourceTree source = completedDeleteTree();
        PurgeAbility tenant = purgeableAbility("iam.tenant", "tenant", "tenant-1");
        DeletionRecoveryResourceResolver first = resolver(tenant).getFirst();
        DeletionRecoveryResourceResolver second = resolver(tenant).getFirst();

        PurgeReport report = new RecycleBinPurgeCoordinator(logService, recovery, List.of(first, second))
                .purge(source.operationId());

        assertThat(report.entries()).extracting(PurgeEntryResult::status)
                .containsExactly(PurgeEntryResult.Status.FAILED, PurgeEntryResult.Status.SKIPPED);
        assertThat(report.entries().getFirst().message())
                .contains("Multiple deletion recovery resolvers support iam.tenant.tenant/tenant-1");
    }

    @Test
    void shouldSkipResourceThatIsNotRecycleBinCapable() {
        SourceTree source = completedDeleteTree();
        PurgeAbility tenant = purgeableAbility("iam.tenant", "tenant", "tenant-1");
        RestoreOnlyAbility application = new RestoreOnlyAbility("iam.tenantApplication", "tenantApplication", "application-1");

        PurgeReport report = new RecycleBinPurgeCoordinator(logService, recovery, resolver(tenant, application))
                .purge(source.operationId());

        assertThat(report.entries()).extracting(PurgeEntryResult::status)
                .containsExactly(PurgeEntryResult.Status.SKIPPED, PurgeEntryResult.Status.SKIPPED);
        assertThat(report.entries().get(1).message()).contains("purge is unavailable");
    }

    @Test
    void shouldResumeDescendantsWhenRootWasPurgedByAnEarlierAttempt() {
        SourceTree source = completedDeleteTree();
        simulatePurge(source.operationId(), source.rootEntryId(),
                "iam.tenant", "tenant", "tenant-1");
        PurgeAbility tenant = purgeableAbility("iam.tenant", "tenant", "tenant-1");
        tenant.dao().deleteById("tenant-1");
        PurgeAbility application = purgeableAbility(
                "iam.tenantApplication", "tenantApplication", "application-1");

        PurgeReport retry = new RecycleBinPurgeCoordinator(logService, recovery, resolver(tenant, application))
                .purge(source.operationId());

        assertThat(retry.entries()).extracting(PurgeEntryResult::status)
                .containsExactly(PurgeEntryResult.Status.PURGED, PurgeEntryResult.Status.PURGED);
        assertThat(retry.entries().getFirst().message()).contains("already purged");
        assertThat(application.dao().findById("application-1")).isNull();
        assertThat(logService.operation(retry.purgeOperationId()).getStatus())
                .isEqualTo(DeletionOperationStatus.SUCCEEDED);
    }

    // --- helpers ---

    private SourceTree completedDeleteTree() {
        DeletionOperation operation = new DeletionOperation();
        operation.setTenantId("tenant-1");
        operation.setOperationType(DeletionOperationType.DELETE);
        operation.setRootModuleAlias("iam.tenant");
        operation.setRootEntityAlias("tenant");
        operation.setRootRecordId("tenant-1");
        String operationId = logService.startOperation(operation);
        String rootEntryId = startEntry(operationId, null, "iam.tenant", "tenant", "tenant-1");
        String childEntryId = startEntry(operationId, rootEntryId, "iam.tenantApplication", "tenantApplication", "application-1");
        logService.completeEntry(rootEntryId, DeletionEntryStatus.SUCCEEDED, null);
        logService.completeEntry(childEntryId, DeletionEntryStatus.SUCCEEDED, null);
        logService.completeOperation(operationId, DeletionOperationStatus.SUCCEEDED, null);
        return new SourceTree(operationId, rootEntryId, childEntryId);
    }

    private void simulateRestore(String sourceEntryId, String moduleAlias, String entityAlias, String recordId) {
        DeletionOperation restoreOp = new DeletionOperation();
        restoreOp.setTenantId("tenant-1");
        restoreOp.setOperationType(DeletionOperationType.RESTORE);
        restoreOp.setRootModuleAlias(moduleAlias);
        restoreOp.setRootEntityAlias(entityAlias);
        restoreOp.setRootRecordId(recordId);
        restoreOp.setSourceOperationId(null);
        String restoreOpId = logService.startOperation(restoreOp);
        DeletionEntry restoreEntry = new DeletionEntry();
        restoreEntry.setTenantId("tenant-1");
        restoreEntry.setOperationId(restoreOpId);
        restoreEntry.setSourceEntryId(sourceEntryId);
        restoreEntry.setResourceModuleAlias(moduleAlias);
        restoreEntry.setResourceEntityAlias(entityAlias);
        restoreEntry.setResourceRecordId(recordId);
        restoreEntry.setTriggerType(DeletionEntryTrigger.DIRECT);
        restoreEntry.setDeleteMode(DeletionEntryMode.SOFT);
        String entryId = logService.startEntry(restoreEntry);
        logService.completeEntry(entryId, DeletionEntryStatus.SUCCEEDED, null);
        logService.completeOperation(restoreOpId, DeletionOperationStatus.SUCCEEDED, null);
    }

    private void simulatePurge(String sourceOperationId, String sourceEntryId,
                               String moduleAlias, String entityAlias, String recordId) {
        DeletionOperation purgeOperation = new DeletionOperation();
        purgeOperation.setTenantId("tenant-1");
        purgeOperation.setOperationType(DeletionOperationType.PURGE);
        purgeOperation.setRootModuleAlias(moduleAlias);
        purgeOperation.setRootEntityAlias(entityAlias);
        purgeOperation.setRootRecordId(recordId);
        purgeOperation.setSourceOperationId(sourceOperationId);
        String operationId = logService.startOperation(purgeOperation);
        DeletionEntry purgeEntry = new DeletionEntry();
        purgeEntry.setTenantId("tenant-1");
        purgeEntry.setOperationId(operationId);
        purgeEntry.setSourceEntryId(sourceEntryId);
        purgeEntry.setResourceModuleAlias(moduleAlias);
        purgeEntry.setResourceEntityAlias(entityAlias);
        purgeEntry.setResourceRecordId(recordId);
        purgeEntry.setTriggerType(DeletionEntryTrigger.DIRECT);
        purgeEntry.setDeleteMode(DeletionEntryMode.SOFT);
        String entryId = logService.startEntry(purgeEntry);
        logService.completeEntry(entryId, DeletionEntryStatus.SUCCEEDED, null);
        logService.completeOperation(operationId, DeletionOperationStatus.PARTIALLY_SUCCEEDED, null);
    }

    private String startEntry(String operationId, String parentEntryId, String moduleAlias, String entityAlias,
                              String recordId) {
        DeletionEntry entry = new DeletionEntry();
        entry.setTenantId("tenant-1");
        entry.setOperationId(operationId);
        entry.setParentEntryId(parentEntryId);
        entry.setResourceModuleAlias(moduleAlias);
        entry.setResourceEntityAlias(entityAlias);
        entry.setResourceRecordId(recordId);
        entry.setTriggerType(parentEntryId == null ? DeletionEntryTrigger.DIRECT : DeletionEntryTrigger.CASCADE);
        entry.setDeleteMode(DeletionEntryMode.SOFT);
        return logService.startEntry(entry);
    }

    private PurgeAbility purgeableAbility(String moduleAlias, String entityAlias, String id) {
        return new PurgeAbility(moduleAlias, entityAlias, id, false);
    }

    private PurgeAbility deniedAbility(String moduleAlias, String entityAlias, String id) {
        return new PurgeAbility(moduleAlias, entityAlias, id, true);
    }

    private List<DeletionRecoveryResourceResolver> resolver(SoftDeleteAbility<?>... abilities) {
        return List.of(new DeletionRecoveryResourceResolver() {
            @Override
            public boolean supports(DeletionEntry entry) {
                return java.util.Arrays.stream(abilities)
                        .anyMatch(ability -> ability.getModuleAlias().equals(entry.getResourceModuleAlias()));
            }

            @Override
            public java.util.Optional<SoftDeleteAbility<?>> resolve(DeletionEntry entry) {
                return java.util.Arrays.stream(abilities)
                        .filter(ability -> ability.getModuleAlias().equals(entry.getResourceModuleAlias()))
                        .findFirst()
                        .map(ability -> (SoftDeleteAbility<?>) ability);
            }
        });
    }

    private static final class PurgeAbility extends AbstractAbilityService<TestRecord>
            implements RecycleBinAbility<TestRecord> {
        private final String entityAlias;
        private final boolean denied;

        private PurgeAbility(String moduleAlias, String entityAlias, String id, boolean denied) {
            super(moduleAlias, TestRecord.class, new TestMemoryDao<>());
            this.entityAlias = entityAlias;
            this.denied = denied;
            TestRecord record = new TestRecord();
            record.setId(id);
            record.setTenantId("tenant-1");
            record.setVersion(1);
            record.setDeleted(true);
            getDao().insert(record);
        }

        TestMemoryDao<TestRecord> dao() {
            return (TestMemoryDao<TestRecord>) getDao();
        }

        @Override
        public boolean isRecycleBinPurgeEnabled() {
            return !denied;
        }

        @Override
        public void beforeRecycleBinPurge(String id) {
            if (denied) {
                throw new UnsupportedOperationException("Recycle-bin purge is not enabled for " + getModuleAlias());
            }
        }

        @Override
        public String getDeletionEntityAlias() {
            return entityAlias;
        }
    }

    private static final class RestoreOnlyAbility extends AbstractAbilityService<TestRecord>
            implements net.ximatai.muyun.spring.ability.deletion.DeletionRecoveryAbility<TestRecord> {
        private final String entityAlias;

        private RestoreOnlyAbility(String moduleAlias, String entityAlias, String id) {
            super(moduleAlias, TestRecord.class, new TestMemoryDao<>());
            this.entityAlias = entityAlias;
            TestRecord record = new TestRecord();
            record.setId(id);
            record.setTenantId("tenant-1");
            record.setVersion(1);
            record.setDeleted(true);
            getDao().insert(record);
        }

        @Override
        public String getDeletionEntityAlias() {
            return entityAlias;
        }
    }

    private static final class TestRecord extends StandardEntity {
    }

    private record SourceTree(String operationId, String rootEntryId, String childEntryId) {
    }
}
