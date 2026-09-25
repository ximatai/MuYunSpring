package net.ximatai.muyun.spring.starter.configuration.platform;

import net.ximatai.muyun.spring.iam.tenant.Tenant;
import net.ximatai.muyun.spring.platform.attachment.*;
import net.ximatai.muyun.spring.platform.attachment.ManagedFileAssetService;
import net.ximatai.muyun.spring.iam.tenant.TenantDao;
import net.ximatai.muyun.spring.common.tenant.TenantCreationProvisioner;
import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.spring.ability.*;
import net.ximatai.muyun.spring.ability.child.ChildAbility;
import net.ximatai.muyun.spring.ability.child.ChildRelation;
import net.ximatai.muyun.spring.ability.child.ChildrenAbility;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.exception.PlatformErrorCodes;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.schema.StaticSchemaService;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.deletion.*;
import net.ximatai.muyun.spring.starter.MuYunSpringAutoConfiguration;
import net.ximatai.muyun.spring.starter.configuration.database.MuYunSpringDatabaseConfiguration;
import net.ximatai.muyun.spring.starter.configuration.platform.fixture.MutationContractDao;
import net.ximatai.muyun.spring.starter.configuration.platform.fixture.MutationContractRecord;
import net.ximatai.muyun.spring.iam.support.TenantServiceTestFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
    @Autowired TenantDao tenantDao;
    @Autowired ManagedFileAssetDao assetDao;
    @Autowired FileReferenceOwnershipDao fileOwnership;
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
        records.purgeGate = ignored -> {};
    }

    @Test
    void concurrentFileBindingsHaveOneOwnerBeforeAnyRemotePromotion() throws Exception {
        String fileId = "claim-" + UUID.randomUUID().toString().substring(0, 20);
        var client = org.mockito.Mockito.mock(FileTransferClient.class);
        var temporary = new FileTransferFileMetadata(fileId, "test.pdf", "pdf", "application/pdf", 1,
                "sha", "temporary", true, java.time.Instant.now(), null, null);
        var permanent = new FileTransferFileMetadata(fileId, "test.pdf", "pdf", "application/pdf", 1,
                "sha", "active", false, java.time.Instant.now(), null, null);
        org.mockito.Mockito.when(client.readMetadata(fileId)).thenReturn(temporary);
        org.mockito.Mockito.when(client.promote(fileId)).thenReturn(permanent);
        var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        beans.addBean("client", client);
        var bindings = new FileReferenceBindingService(beans.getBeanProvider(FileTransferClient.class), fileOwnership);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 2; i++) {
                String recordId = "owner-" + i;
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        new TransactionTemplate(transactions).executeWithoutResult(status ->
                                bindings.bind("file-owner", "test.document", recordId, "fileId", fileId,
                                        net.ximatai.muyun.spring.dynamic.metadata.FileReferenceDefinition.unrestricted()));
                        return true;
                    } catch (PlatformException conflict) {
                        assertThat(conflict.code()).isEqualTo(PlatformErrorCodes.FILE_REFERENCE_ALREADY_BOUND);
                        return false;
                    }
                }));
            }
            start.countDown();
            assertThat(List.of(futures.get(0).get(20, java.util.concurrent.TimeUnit.SECONDS),
                    futures.get(1).get(20, java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
        org.mockito.Mockito.verify(client, org.mockito.Mockito.times(1)).promote(fileId);
        assertThat(fileOwnership.query(Criteria.of().eq("id", fileId), PageRequest.of(1, 10)))
                .hasSize(1);
    }

    @Test
    void rolledBackFileOwnershipIsReleasedWithTheBusinessTransaction() {
        String fileId = "claim-" + UUID.randomUUID().toString().substring(0, 20);
        FileReferenceOwnership claim = new FileReferenceOwnership();
        claim.setId(fileId);
        claim.setModuleAlias("test.document");
        claim.setRecordId("rolled-back");
        claim.setFieldName("fileId");
        net.ximatai.muyun.spring.common.model.EntityLifecycle.prepareInsert(claim, java.time.Instant.now());
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            fileOwnership.insert(claim);
            status.setRollbackOnly();
        });
        claim.setRecordId("committed");
        new TransactionTemplate(transactions).executeWithoutResult(status -> fileOwnership.insert(claim));
        assertThat(fileOwnership.query(Criteria.of().eq("id", fileId), PageRequest.of(1, 10)))
                .singleElement().extracting(FileReferenceOwnership::getRecordId).isEqualTo("committed");
    }

    @Test
    void inlineImageFactsSurviveTheRealRepositoryRoundTrip() throws Exception {
        var image = new java.awt.image.BufferedImage(16, 8, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var content = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", content);
        var assets = new ManagedFileAssetService(assetDao);
        var asset = new TransactionTemplate(transactions).execute(status ->
                assets.createInline("image_facts", "logo.png", "image/png", content.toByteArray()));
        var stored = assets.readReferenceMetadata("image_facts", asset.getId());
        assertThat(stored.imageWidth()).isEqualTo(16);
        assertThat(stored.imageHeight()).isEqualTo(8);
        assertThat(stored.mimeType()).isEqualTo("image/png");
        assertThat(stored.sizeBytes()).isEqualTo(content.size());
    }

    @Test
    void directOrganizationProvisioningRollsBackWritesWhenAnExtensionFails() {
        var organization = new net.ximatai.muyun.spring.iam.organization.Organization();
        organization.setId("org-replay"); organization.setTenantId("tenant-replay");
        var orgDao = org.mockito.Mockito.mock(net.ximatai.muyun.spring.iam.organization.OrganizationDao.class);
        org.mockito.Mockito.when(orgDao.query(org.mockito.ArgumentMatchers.any(Criteria.class),
                        org.mockito.ArgumentMatchers.any(PageRequest.class),
                        org.mockito.ArgumentMatchers.any(net.ximatai.muyun.database.core.orm.Sort[].class)))
                .thenReturn(List.of(organization));
        var created = record("provision-" + UUID.randomUUID());
        var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        beans.addBean("extension", (net.ximatai.muyun.spring.common.tenant.OrganizationCreationProvisioner) (tenant, id) -> {
            records.insert(created);
            throw new IllegalArgumentException("reject provisioning");
        });
        var service = new net.ximatai.muyun.spring.iam.organization.OrganizationService(orgDao, tenant -> {},
                beans.getBeanProvider(net.ximatai.muyun.spring.common.tenant.OrganizationCreationProvisioner.class));
        try (var tenant = TenantContext.use("tenant-replay")) {
            assertThatThrownBy(() -> service.provisionOrganization(organization.getId())).hasMessage("reject provisioning");
            assertThat(dao.findById(created.getId())).isNull();
        }
    }

    @Test
    void directTenantProvisioningRollsBackEarlierExtensionWrites() {
        var tenant = new Tenant();
        tenant.setAlias("replay_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
        tenant.setTitle("Replay tenant");
        try (var ignored = TenantContext.system("create initialization target")) {
            TenantServiceTestFactory.create(tenantDao).insert(tenant);
        }
        var created = record("tenant-provision-" + UUID.randomUUID());
        var beans = new org.springframework.beans.factory.support.StaticListableBeanFactory();
        beans.addBean("extension", (TenantCreationProvisioner) id -> {
            records.insert(created);
            throw new IllegalArgumentException("reject tenant provisioning");
        });
        var service = TenantServiceTestFactory.create(tenantDao, beans.getBeanProvider(TenantCreationProvisioner.class));
        try (var ignored = TenantContext.system("initialize tenant")) {
            assertThatThrownBy(() -> service.provisionTenant(tenant.getId())).hasMessage("reject tenant provisioning");
            assertThat(dao.findById(created.getId())).isNull();
            tenant.setDeleted(true);
            tenantDao.updateById(tenant);
            assertThatThrownBy(() -> service.provisionTenant(tenant.getId())).hasMessageContaining("not active");
            assertThat(dao.findById(created.getId())).isNull();
        }
    }

    @Test
    void batchInsertRollsBackEarlierRowsWhenALaterRecordConflicts() {
        try (var tenant = TenantContext.use("batch-" + UUID.randomUUID())) {
            var first = record("duplicate");
            var second = record("duplicate");
            assertThatThrownBy(() -> records.insertBatch(List.of(first, second))).isInstanceOf(PlatformException.class);
            assertThat(dao.findById(first.getId())).isNull();
            assertThat(dao.findById(second.getId())).isNull();
        }
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void batchDeleteRollsBackEarlierRowsWhenALaterCallbackFails(boolean explicitContext) {
        try (var tenant = TenantContext.use("batch-" + UUID.randomUUID())) {
            var first = record("first");
            var second = record("second");
            var batch = new Records(dao, records.jdbc) {
                @Override public void afterDelete(String id, MutationContractRecord row, int deleted) {
                    if (id.equals(second.getId())) throw new IllegalArgumentException("reject batch delete");
                }
            };
            var ids = batch.insertBatch(List.of(first, second));
            assertThatThrownBy(() -> {
                if (explicitContext) batch.deleteBatch(ids, null);
                else batch.deleteBatch(ids);
            }).hasMessage("reject batch delete");
            assertThat(batch.select(first.getId())).isNotNull();
            assertThat(batch.select(second.getId())).isNotNull();
            assertThat(dao.findById(first.getId()).getVersion()).isEqualTo(first.getVersion());
        }
    }

    @Test
    void tenantLayerReadsRestrictStorageScopeWithoutChangingOrdinaryReads() {
        var layers = new LayerRecords(dao);
        String code = "layers-" + UUID.randomUUID();
        var global = record(code);
        var tenantRow = record(code);
        var other = record(code);
        records.insert(global);
        try (var tenant = TenantContext.use("tenant-layer-a")) { records.insert(tenantRow); }
        try (var tenant = TenantContext.use("tenant-layer-b")) { records.insert(other); }
        Criteria filter = Criteria.of().eq("code", code);
        assertThat(layers.listTenantAndGlobal(filter)).extracting(MutationContractRecord::getId)
                .containsExactly(global.getId());
        try (var tenant = TenantContext.use("tenant-layer-a")) {
            assertThat(layers.listTenantAndGlobal(filter)).extracting(MutationContractRecord::getId)
                    .containsExactly(tenantRow.getId(), global.getId());
            assertThat(layers.list(filter)).extracting(MutationContractRecord::getId).containsExactly(tenantRow.getId());
            assertThat(TenantContext.currentTenantId()).contains("tenant-layer-a");
            assertThat(TenantContext.tenantFilterBypassed()).isFalse();
            try (var bypass = TenantContext.bypassTenantFilter("test outer scope")) {
                assertThat(layers.listCurrentTenant(filter)).extracting(MutationContractRecord::getId)
                        .containsExactly(tenantRow.getId());
            }
        }
        try (var system = TenantContext.system("verify explicit global layer")) {
            assertThat(layers.listCurrentTenant(filter)).isEmpty();
            assertThat(layers.listGlobal(filter)).extracting(MutationContractRecord::getId).containsExactly(global.getId());
        }
        records.delete(global.getId());
        assertThat(layers.listGlobal(filter)).isEmpty();
    }

    @Test
    void globalIdentityIsIndependentOfTenantOverridesAndRetainedTenantRecords() {
        String code = "tenant-first-" + UUID.randomUUID();
        var tenantRow = record(code);
        try (var tenant = TenantContext.use("identity-tenant")) {
            records.insert(tenantRow);
            records.delete(tenantRow.getId());
        }
        var global = record(code);
        records.insert(global);
        assertThat(records.update(global)).isEqualTo(1);
        try (var tenant = TenantContext.use("identity-tenant")) {
            assertThatThrownBy(() -> records.insert(record(code)))
                    .isInstanceOfSatisfying(PlatformException.class, error -> {
                        assertThat(error.code()).isEqualTo(PlatformErrorCodes.RESOURCE_SOFT_DELETED_CONFLICT);
                        assertThat(error.details()).containsEntry("resourceRecordId", tenantRow.getId());
                    });
        }
        assertThatThrownBy(() -> records.insert(record(code)))
                .isInstanceOfSatisfying(PlatformException.class,
                        error -> assertThat(error.code()).isEqualTo(PlatformErrorCodes.CONFLICT_UNIQUE));
        records.delete(global.getId());
        assertThatThrownBy(() -> records.insert(record(code)))
                .isInstanceOfSatisfying(PlatformException.class,
                        error -> assertThat(error.details()).containsEntry("resourceRecordId", global.getId()));
    }

    @Test
    void declaredNullPartitionDoesNotIncludeTenantRecords() {
        var sorting = new LayerRecords(dao);
        String code = "partition-" + UUID.randomUUID();
        var global = record(code);
        var tenantRow = record(code);
        records.insert(global);
        try (var tenant = TenantContext.use("sort-layer")) { records.insert(tenantRow); }
        Criteria partition = SortPartitions.byFields("tenantId").criteriaFor(global).eq("code", code);
        assertThat(sorting.list(partition)).extracting(MutationContractRecord::getId).containsExactly(global.getId());
    }

    @Test
    void directReorderRollsBackEarlierRowsWhenALaterWriteFails() {
        try (var tenant = TenantContext.use("sort-" + UUID.randomUUID())) {
            SortingRecords sorting = new SortingRecords(dao);
            MutationContractRecord first = sorting.create("first", TreeAbility.ROOT_ID, 100);
            MutationContractRecord second = sorting.create("second", TreeAbility.ROOT_ID, 200);
            sorting.rejectUpdate = row -> row.getId().equals(first.getId());

            assertThatThrownBy(() -> sorting.reorder(List.of(second.getId(), first.getId())))
                    .hasMessageContaining("sort validation failed");
            assertThat(dao.findById(second.getId()).getSortOrder()).isEqualTo(200);
            assertThat(dao.findById(second.getId()).getVersion()).isEqualTo(second.getVersion());
            assertThat(dao.findById(first.getId()).getSortOrder()).isEqualTo(100);
            sorting.rejectUpdate = row -> false;
            sorting.reorder(List.of(second.getId(), first.getId()));
            assertThat(sorting.sortedList(Criteria.of())).extracting(MutationContractRecord::getId)
                    .containsExactly(second.getId(), first.getId());
        }
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void movingTreeParentAndSortingAreOneTransaction(boolean scoped, boolean rebalance) {
        try (var tenant = TenantContext.use("tree-" + UUID.randomUUID())) {
            SortingRecords sorting = new SortingRecords(dao);
            var oldParent = sorting.create("old-parent", TreeAbility.ROOT_ID, 100);
            var newParent = sorting.create("new-parent", TreeAbility.ROOT_ID, 200);
            var moving = sorting.create("moving", oldParent.getId(), 100);
            var previous = sorting.create("previous", newParent.getId(), rebalance ? 1 : 200);
            var next = sorting.create("next", newParent.getId(), rebalance ? 2 : 400);
            sorting.rejectUpdate = row -> row.getId().equals(moving.getId()) && row.getSortOrder() != 100;

            assertThatThrownBy(() -> {
                if (scoped) sorting.moveInTree(Criteria.of(), moving.getId(), previous.getId(), next.getId(), newParent.getId());
                else sorting.moveInTree(moving.getId(), previous.getId(), next.getId(), newParent.getId());
            }).hasMessageContaining("sort validation failed");
            assertThat(dao.findById(moving.getId()).getParentId()).isEqualTo(oldParent.getId());
            assertThat(dao.findById(moving.getId()).getVersion()).isEqualTo(moving.getVersion());
            assertThat(dao.findById(previous.getId()).getSortOrder()).isEqualTo(previous.getSortOrder());
            assertThat(dao.findById(previous.getId()).getVersion()).isEqualTo(previous.getVersion());
            assertThat(dao.findById(next.getId()).getVersion()).isEqualTo(next.getVersion());
        }
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
        recovery.restore(records, log.entry(source.rootEntryId()), success.getId());
        assertThat(log.operation(interrupted.getId()).getStatus()).isEqualTo(DeletionOperationStatus.IN_PROGRESS);
        assertThat(restores.restore(source.operationId()).entries()).extracting(RestoreEntryResult::status)
                .containsOnly(RestoreEntryResult.Status.RESTORED);
        assertThat(records.committed).containsExactly(source.root().getId(), source.child().getId());
    }

    @Test
    void sourceVersionChangesMustBlockRecoveryAndPurgeBeforeAnyDescendantWrite() {
        Source source = sourceTree();
        records.jdbc.update("update test_mutation_contract set version = version + 1 where id = ?", source.root().getId());
        assertThat(restores.restore(source.operationId()).entries())
                .extracting(RestoreEntryResult::recordId, RestoreEntryResult::status)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(source.root().getId(), RestoreEntryResult.Status.FAILED),
                        org.assertj.core.groups.Tuple.tuple(source.child().getId(), RestoreEntryResult.Status.SKIPPED));
        assertThat(purges.purge(source.operationId()).entries())
                .extracting(PurgeEntryResult::recordId, PurgeEntryResult::status)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(source.root().getId(), PurgeEntryResult.Status.FAILED),
                        org.assertj.core.groups.Tuple.tuple(source.child().getId(), PurgeEntryResult.Status.SKIPPED));
        assertThat(dao.findById(source.root().getId()).getDeleted()).isTrue();
        assertThat(dao.findById(source.child().getId()).getDeleted()).isTrue();
    }

    @Test
    void restoreAndPurgeMustSerializeOneSourceWhileOtherSourcesContinue() throws Exception {
        Source first = sourceTree(), independent = sourceTree();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var restoring = new java.util.concurrent.CountDownLatch(1);
        records.purgeGate = id -> {
            if (!id.equals(first.child().getId())) return;
            entered.countDown();
            try {
                if (!release.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("purge gate timed out");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        };
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(3)) {
            var purge = pool.submit(() -> purges.purge(first.operationId()));
            try {
                assertThat(entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var restore = pool.submit(() -> { restoring.countDown(); return restores.restore(first.operationId()); });
                assertThat(restoring.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThat(pool.submit(() -> restores.restore(independent.operationId())).get(10, java.util.concurrent.TimeUnit.SECONDS)
                        .entries()).extracting(RestoreEntryResult::status).containsOnly(RestoreEntryResult.Status.RESTORED);
                assertThat(restore.isDone()).isFalse();
                assertThat(dao.findById(first.root().getId()).getDeleted()).isTrue();
                release.countDown();
                assertThat(purge.get(10, java.util.concurrent.TimeUnit.SECONDS).entries())
                        .extracting(PurgeEntryResult::status).containsOnly(PurgeEntryResult.Status.PURGED);
                assertThat(restore.get(10, java.util.concurrent.TimeUnit.SECONDS).entries())
                        .extracting(RestoreEntryResult::status).containsOnly(RestoreEntryResult.Status.SKIPPED);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void aggregateRootPurgeMustIncludePlainSoftChildrenAndHonorTheirRetention() {
        try (var ignored = TenantContext.use("aggregate-purge-it")) {
            var aggregate = aggregateSource();
            aggregate.details().retainUntil = java.time.Instant.now().plusSeconds(3600);
            PurgeReport blocked = aggregate.facade().purge(aggregate.roots(), aggregate.operationId());
            assertThat(blocked.entries()).extracting(PurgeEntryResult::recordId, PurgeEntryResult::status)
                    .containsExactlyInAnyOrder(
                            tuple(aggregate.root().getId(), PurgeEntryResult.Status.SKIPPED),
                            tuple(aggregate.child().getId(), PurgeEntryResult.Status.FAILED));
            assertThat(dao.findById(aggregate.root().getId()).getDeleted()).isTrue();
            assertThat(dao.findById(aggregate.child().getId()).getDeleted()).isTrue();
            aggregate.details().retainUntil = java.time.Instant.EPOCH;
            assertThat(aggregate.facade().purge(aggregate.roots(), aggregate.operationId()).entries())
                    .extracting(PurgeEntryResult::status).containsOnly(PurgeEntryResult.Status.PURGED);
            assertThat(dao.findById(aggregate.root().getId())).isNull();
            assertThat(dao.findById(aggregate.child().getId())).isNull();
            assertThat(aggregate.details().purged).containsExactly(aggregate.child().getId());
        }
    }

    @Test
    void aggregatePurgeMustHonorAnOwnedResourcesExplicitRecycleBinDenial() {
        try (var ignored = TenantContext.use("aggregate-purge-it")) {
            var aggregate = aggregateSource(new DeniedRecycleDetails(dao));
            PurgeReport report = aggregate.facade().purge(aggregate.roots(), aggregate.operationId());
            assertThat(report.entries()).extracting(PurgeEntryResult::recordId, PurgeEntryResult::status)
                    .containsExactlyInAnyOrder(
                            tuple(aggregate.root().getId(), PurgeEntryResult.Status.SKIPPED),
                            tuple(aggregate.child().getId(), PurgeEntryResult.Status.FAILED));
            assertThat(dao.findById(aggregate.root().getId())).isNotNull();
            assertThat(dao.findById(aggregate.child().getId())).isNotNull();
        }
    }

    @Test
    void aggregatePurgeMustRejectCurrentForeignKeyDriftEvenWithoutAVersionIncrement() {
        try (var ignored = TenantContext.use("aggregate-purge-it")) {
            var aggregate = aggregateSource();
            records.jdbc.update("update test_mutation_contract set code = ? where id = ?",
                    "other-owner-" + UUID.randomUUID(), aggregate.child().getId());
            assertThat(aggregate.facade().purge(aggregate.roots(), aggregate.operationId()).entries())
                    .extracting(PurgeEntryResult::recordId, PurgeEntryResult::status)
                    .containsExactlyInAnyOrder(
                            tuple(aggregate.root().getId(), PurgeEntryResult.Status.SKIPPED),
                            tuple(aggregate.child().getId(), PurgeEntryResult.Status.FAILED));
            assertThat(dao.findById(aggregate.root().getId())).isNotNull();
            assertThat(dao.findById(aggregate.child().getId())).isNotNull();
            assertThat(aggregate.details().purged).isEmpty();
        }
    }

    private AggregateSource aggregateSource() {
        return aggregateSource(new AggregateDetails(dao));
    }

    private AggregateSource aggregateSource(AggregateDetails details) {
        AggregateRoots roots = new AggregateRoots(dao, details);
        var resolver = new StaticDeletionRecoveryResourceResolver(List.of(roots));
        var facade = new RecycleBinFacade(log,
                new SoftDeleteRestoreCoordinator(log, recovery, List.of(resolver)),
                new RecycleBinPurgeCoordinator(log, recovery, List.of(resolver)));
        MutationContractRecord root = record("aggregate-" + UUID.randomUUID());
        roots.insert(root);
        MutationContractRecord child = record(root.getId());
        details.insert(child);
        roots.delete(root.getId());
        var retained = roots.selectIgnoreSoftDelete(root.getId());
        var item = facade.item(roots, retained, retained.getId(), retained.getDeletedAt());
        assertThat(item.purgeable()).isTrue();
        assertThat(log.operationEntries(item.sourceDeleteOperationId()))
                .extracting(DeletionEntry::getResourceModuleAlias, DeletionEntry::getResourceEntityAlias)
                .containsExactlyInAnyOrder(tuple("test.aggregate_root", "aggregate_root"),
                        tuple("test.aggregate_detail", "aggregate_detail"));
        return new AggregateSource(roots, details, facade, root, child, item.sourceDeleteOperationId());
    }

    record AggregateSource(AggregateRoots roots, AggregateDetails details, RecycleBinFacade facade,
                           MutationContractRecord root, MutationContractRecord child, String operationId) {}

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
        entry.setResourceVersion(1);
        entry.setTriggerType(parentId == null ? DeletionEntryTrigger.DIRECT : DeletionEntryTrigger.CASCADE);
        return entry;
    }

    record Source(String operationId, String rootEntryId, MutationContractRecord root, MutationContractRecord child) {}

    static class LayerRecords extends AbstractAbilityService<MutationContractRecord>
            implements SoftDeleteAbility<MutationContractRecord>, TenantLayerAbility<MutationContractRecord> {
        LayerRecords(MutationContractDao dao) { super("test.layers", MutationContractRecord.class, dao); }
    }

    static class SortingRecords extends AbstractAbilityService<MutationContractRecord> implements TreeAbility<MutationContractRecord> {
        java.util.function.Predicate<MutationContractRecord> rejectUpdate = row -> false;
        SortingRecords(MutationContractDao dao) { super("test.sorting", MutationContractRecord.class, dao); }
        MutationContractRecord create(String code, String parentId, int sortOrder) {
            var record = record(code);
            record.setParentId(parentId);
            record.setSortOrder(sortOrder);
            insert(record);
            return record;
        }
        @Override public void beforeUpdate(MutationContractRecord row) {
            if (rejectUpdate.test(row)) throw new IllegalArgumentException("sort validation failed");
        }
    }

    static class Records extends AbstractAbilityService<MutationContractRecord> implements RecycleBinAbility<MutationContractRecord> {
        final Set<String> failRestore = new HashSet<>(), failPurge = new HashSet<>();
        final List<String> committed = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile java.util.function.Consumer<String> purgeGate = ignored -> {};
        final JdbcTemplate jdbc;
        Records(BaseDao<MutationContractRecord, String> dao, JdbcTemplate jdbc) {
            super("test.records", MutationContractRecord.class, dao); this.jdbc = jdbc;
        }
        @Override public boolean isRecycleBinPurgeEnabled() { return true; }
        @Override public void beforeRecycleBinPurge(String id) {}
        @Override public void beforeRetainedRecordPurge(String id) { purgeGate.accept(id); }
        @Override public void afterRestore(String id, MutationContractRecord record, int restored) {
            TransactionScopeSupport.afterCommitOrNow(() -> committed.add(id));
            if (failRestore.contains(id)) jdbc.execute("select 1 / 0");
        }
        @Override public void afterRecycleBinPurge(String id, MutationContractRecord record, int purged) {
            TransactionScopeSupport.afterCommitOrNow(() -> committed.add(id));
            if (failPurge.contains(id)) throw new IllegalArgumentException("reject purge after write");
        }
    }

    static class AggregateRoots extends AbstractAbilityService<MutationContractRecord>
            implements RecycleBinAbility<MutationContractRecord>, ChildrenAbility<MutationContractRecord> {
        private final AggregateDetails details;
        AggregateRoots(BaseDao<MutationContractRecord, String> dao, AggregateDetails details) {
            super("test.aggregate_root", MutationContractRecord.class, dao);
            this.details = details;
        }
        @Override public boolean isRecycleBinPurgeEnabled() { return true; }
        @Override public void beforeRecycleBinPurge(String id) {}
        @Override public boolean usesAutomaticChildRelations() { return false; }
        @Override public List<ChildRelation<? extends EntityContract, MutationContractRecord>> childRelations() {
            return List.of(new ChildRelation<MutationContractRecord, MutationContractRecord>("details", details,
                    MutationContractRecord::setCode, "code", parent -> List.of(), MutationContractRecord::getCode)
                    .cascadeOnParentUnavailable());
        }
    }

    static class DeniedRecycleDetails extends AggregateDetails implements RecycleBinAbility<MutationContractRecord> {
        DeniedRecycleDetails(BaseDao<MutationContractRecord, String> dao) { super(dao); }
        @Override public boolean isRecycleBinPurgeEnabled() { return false; }
    }

    static class AggregateDetails extends AbstractAbilityService<MutationContractRecord>
            implements SoftDeleteAbility<MutationContractRecord>, ChildAbility<MutationContractRecord> {
        java.time.Instant retainUntil = java.time.Instant.EPOCH;
        final List<String> purged = new ArrayList<>();
        AggregateDetails(BaseDao<MutationContractRecord, String> dao) {
            super("test.aggregate_detail", MutationContractRecord.class, dao);
        }
        @Override public void beforeRetainedRecordPurge(String id) {
            if (retainUntil.isAfter(java.time.Instant.now())) throw new IllegalStateException("retention period has not elapsed");
        }
        @Override public void afterRetainedRecordPurge(String id, MutationContractRecord entity, int count) {
            TransactionScopeSupport.afterCommitOrNow(() -> purged.add(id));
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
    @EnableMuYunRepositories(basePackageClasses = {MutationContractDao.class, DeletionOperationDao.class, TenantDao.class, ManagedFileAssetDao.class})
    @Import({MuYunSpringMutationConfiguration.class, MuYunSpringDatabaseConfiguration.class,
            DeletionRecoveryExecutor.class, SoftDeleteRestoreCoordinator.class, RecycleBinPurgeCoordinator.class,
            DeletionLogLifecycleListener.class})
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
