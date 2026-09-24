package net.ximatai.muyun.spring.platform.deletion;

import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.deletion.DeletionRecoveryAbility;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.platform.support.TestMemoryDao;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SoftDeleteRestoreCoordinatorTest {
    private final TestMemoryDao<DeletionOperation> operationDao = new TestMemoryDao<>();
    private final TestMemoryDao<DeletionEntry> entryDao = new TestMemoryDao<>();
    private final DeletionLogService logService = new DeletionLogService(operationDao, entryDao);
    private final DeletionRecoveryExecutor recovery = new DeletionRecoveryExecutor(logService);

    @Test
    void shouldWriteRestoreOperationAndLinkedEntriesForWholeSourceTree() {
        SourceTree source = completedDeleteTree();
        RestoreAbility tenant = softDeletedAbility("iam.tenant", "tenant-1");
        RestoreAbility application = softDeletedAbility("iam.tenantApplication", "application-1");

        RestoreReport report = new SoftDeleteRestoreCoordinator(logService, recovery, resolver(tenant, application))
                .restore(source.operationId());

        assertThat(report.sourceOperationId()).isEqualTo(source.operationId());
        assertThat(report.restoreOperationId()).isNotBlank();
        assertThat(report.entries()).extracting(RestoreEntryResult::status)
                .containsExactly(RestoreEntryResult.Status.RESTORED, RestoreEntryResult.Status.RESTORED);
        assertThat(logService.operation(report.restoreOperationId()))
                .extracting(DeletionOperation::getOperationType, DeletionOperation::getStatus,
                        DeletionOperation::getSourceOperationId, DeletionOperation::getRootRecordId)
                .containsExactly(DeletionOperationType.RESTORE, DeletionOperationStatus.SUCCEEDED,
                        source.operationId(), "tenant-1");
        assertThat(logService.operationEntries(report.restoreOperationId()))
                .extracting(DeletionEntry::getSourceEntryId, DeletionEntry::getParentEntryId,
                        DeletionEntry::getStatus)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(source.rootEntryId(), null, DeletionEntryStatus.SUCCEEDED),
                        org.assertj.core.groups.Tuple.tuple(source.childEntryId(),
                                logService.operationEntries(report.restoreOperationId()).getFirst().getId(),
                                DeletionEntryStatus.SUCCEEDED));
        assertThat(tenant.record.getDeleted()).isFalse();
        assertThat(application.record.getDeleted()).isFalse();
    }

    @Test
    void shouldAuditFailedRootAndSkippedDescendants() {
        SourceTree source = completedDeleteTree();
        RestoreAbility failingTenant = new RestoreAbility("iam.tenant", "tenant", "tenant-1", true);
        RestoreAbility application = softDeletedAbility("iam.tenantApplication", "application-1");

        RestoreReport report = new SoftDeleteRestoreCoordinator(logService, recovery, resolver(failingTenant, application))
                .restore(source.operationId());

        assertThat(report.entries()).extracting(RestoreEntryResult::status)
                .containsExactly(RestoreEntryResult.Status.FAILED, RestoreEntryResult.Status.SKIPPED);
        assertThat(logService.operation(report.restoreOperationId()).getStatus())
                .isEqualTo(DeletionOperationStatus.FAILED);
        assertThat(logService.operationEntries(report.restoreOperationId()))
                .extracting(DeletionEntry::getSourceEntryId, DeletionEntry::getStatus)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(source.rootEntryId(), DeletionEntryStatus.FAILED),
                        org.assertj.core.groups.Tuple.tuple(source.childEntryId(), DeletionEntryStatus.SKIPPED));
        assertThat(application.record.getDeleted()).isTrue();
    }

    @Test
    void shouldResumeDescendantsAfterRootWasRestoredByEarlierAttempt() {
        SourceTree source = completedDeleteTree();
        RestoreAbility tenant = softDeletedAbility("iam.tenant", "tenant-1");
        RestoreAbility failingApplication = new RestoreAbility(
                "iam.tenantApplication", "tenantApplication", "application-1", true);

        RestoreReport first = new SoftDeleteRestoreCoordinator(logService, recovery, resolver(tenant, failingApplication))
                .restore(source.operationId());
        RestoreAbility retryableApplication = softDeletedAbility("iam.tenantApplication", "application-1");
        RestoreReport retry = new SoftDeleteRestoreCoordinator(logService, recovery, resolver(tenant, retryableApplication))
                .restore(source.operationId());

        assertThat(first.entries()).extracting(RestoreEntryResult::status)
                .containsExactly(RestoreEntryResult.Status.RESTORED, RestoreEntryResult.Status.FAILED);
        assertThat(retry.entries()).extracting(RestoreEntryResult::status)
                .containsExactly(RestoreEntryResult.Status.RESTORED, RestoreEntryResult.Status.RESTORED);
        assertThat(retry.entries().getFirst().message()).contains("already restored");
        assertThat(logService.operation(retry.restoreOperationId()).getStatus())
                .isEqualTo(DeletionOperationStatus.SUCCEEDED);
        assertThat(retryableApplication.record.getDeleted()).isFalse();
    }

    @Test
    void shouldNotMarkAnAllSkippedRestoreAsSucceeded() {
        SourceTree source = completedDeleteTree();

        RestoreReport report = new SoftDeleteRestoreCoordinator(logService, recovery, List.of())
                .restore(source.operationId());

        assertThat(report.entries()).extracting(RestoreEntryResult::status)
                .containsExactly(RestoreEntryResult.Status.SKIPPED, RestoreEntryResult.Status.SKIPPED);
        assertThat(logService.operation(report.restoreOperationId()).getStatus())
                .isEqualTo(DeletionOperationStatus.PARTIALLY_SUCCEEDED);
    }

    @Test
    void shouldResolveStaticRecoveryResourcesByCompleteResourceKey() {
        RestoreAbility tenant = new RestoreAbility("iam.shared", "tenant", "tenant-1", false);
        RestoreAbility application = new RestoreAbility("iam.shared", "tenant_application", "application-1", false);
        StaticDeletionRecoveryResourceResolver resolver =
                new StaticDeletionRecoveryResourceResolver(List.of(tenant, application));
        DeletionEntry entry = new DeletionEntry();
        entry.setResourceModuleAlias("iam.shared");
        entry.setResourceEntityAlias("tenant_application");

        assertThat(resolver.supports(entry)).isTrue();
        assertThat(resolver.resolve(entry)).containsSame(application);
    }

    @Test
    void shouldRejectAmbiguousRecoveryResolverMatches() {
        SourceTree source = completedDeleteTree();
        RestoreAbility tenant = softDeletedAbility("iam.tenant", "tenant-1");
        DeletionRecoveryResourceResolver first = resolver(tenant).getFirst();
        DeletionRecoveryResourceResolver second = resolver(tenant).getFirst();

        RestoreReport report = new SoftDeleteRestoreCoordinator(logService, recovery, List.of(first, second))
                .restore(source.operationId());

        assertThat(report.entries()).extracting(RestoreEntryResult::status)
                .containsExactly(RestoreEntryResult.Status.FAILED, RestoreEntryResult.Status.SKIPPED);
        assertThat(report.entries().getFirst().message())
                .contains("Multiple deletion recovery resolvers support iam.tenant.tenant/tenant-1");
    }

    private SourceTree completedDeleteTree() {
        DeletionOperation operation = new DeletionOperation();
        operation.setTenantId("tenant-1");
        operation.setOperationType(DeletionOperationType.DELETE);
        operation.setRootModuleAlias("iam.tenant");
        operation.setRootRecordId("tenant-1");
        String operationId = logService.startOperation(operation);
        String rootEntryId = startEntry(operationId, null, "iam.tenant", "tenant", "tenant-1");
        String childEntryId = startEntry(operationId, rootEntryId, "iam.tenantApplication", "tenantApplication", "application-1");
        logService.completeEntry(rootEntryId, DeletionEntryStatus.SUCCEEDED, null);
        logService.completeEntry(childEntryId, DeletionEntryStatus.SUCCEEDED, null);
        logService.completeOperation(operationId, DeletionOperationStatus.SUCCEEDED, null);
        return new SourceTree(operationId, rootEntryId, childEntryId);
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
        entry.setResourceVersion(0);
        return logService.startEntry(entry);
    }

    private RestoreAbility softDeletedAbility(String moduleAlias, String id) {
        return new RestoreAbility(moduleAlias, moduleAlias.substring(moduleAlias.lastIndexOf('.') + 1), id, false);
    }

    private List<DeletionRecoveryResourceResolver> resolver(RestoreAbility... abilities) {
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

    private static final class RestoreAbility extends AbstractAbilityService<TestRecord>
            implements DeletionRecoveryAbility<TestRecord> {
        private final TestRecord record;
        private final boolean fails;
        private final String entityAlias;

        private RestoreAbility(String moduleAlias, String entityAlias, String id, boolean fails) {
            super(moduleAlias, TestRecord.class, new TestMemoryDao<>());
            this.entityAlias = entityAlias;
            this.fails = fails;
            this.record = new TestRecord();
            record.setId(id);
            record.setTenantId("tenant-1");
            record.setVersion(0);
            record.setDeleted(true);
            getDao().insert(record);
        }

        @Override
        public int restore(String id, Integer expectedVersion) {
            if (fails) {
                throw new IllegalStateException("restore rejected");
            }
            return DeletionRecoveryAbility.super.restore(id, expectedVersion);
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
