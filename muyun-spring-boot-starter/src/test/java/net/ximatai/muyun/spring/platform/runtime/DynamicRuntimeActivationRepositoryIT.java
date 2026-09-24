package net.ximatai.muyun.spring.platform.runtime;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.spring.ability.OptimisticLockException;
import net.ximatai.muyun.spring.dynamic.metadata.EntityDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.FieldDefinition;
import net.ximatai.muyun.spring.dynamic.metadata.ModuleDefinition;
import net.ximatai.muyun.spring.dynamic.runtime.DynamicSchemaGovernanceFacts;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelation;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataOrchestrationService;
import net.ximatai.muyun.spring.ability.event.RuntimeEventPublisher;
import net.ximatai.muyun.spring.platform.metadata.ModuleMetadataRelationService;
import net.ximatai.muyun.spring.platform.ui.PublishedPageExecutionCoordinator;
import net.ximatai.muyun.spring.starter.MuYunSpringAutoConfiguration;
import net.ximatai.muyun.spring.starter.configuration.database.MuYunSpringDatabaseConfiguration;
import net.ximatai.muyun.spring.starter.configuration.platform.MuYunSpringMutationConfiguration;
import net.ximatai.muyun.spring.starter.configuration.platform.MuYunSpringBusinessLoggingConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = DynamicRuntimeActivationRepositoryIT.Application.class, properties = {
        "muyun.database.repository-schema-mode=ENSURE", "muyun.runtime.mode=development"})
class DynamicRuntimeActivationRepositoryIT {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @Autowired DynamicRuntimeActivationService activation;
    @Autowired net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordRuntime runtime;
    @Autowired DynamicRuntimeActivationDao dao;
    @Autowired PlatformDynamicRuntimeRefresher refresh;
    @Autowired ModuleMetadataRelationService relations;
    @Autowired PublishedPageExecutionCoordinator pages;
    @Autowired ObjectProvider<ModuleMetadataRelationService> relationProvider;
    @Autowired ObjectProvider<PublishedPageExecutionCoordinator> pageProvider;
    @Autowired ObjectProvider<ModuleMetadataOrchestrationService> orchestrationProvider;
    @Autowired ObjectProvider<RuntimeEventPublisher> eventProvider;
    @Autowired ModuleMetadataOrchestrationService orchestration;
    @Autowired RuntimeEventPublisher events;
    @Autowired PlatformTransactionManager manager;
    @Autowired DataSource dataSource;

    @BeforeEach void resetCollaborators() {
        reset(refresh, relations, pages, orchestration, events);
        when(relations.list(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of(new ModuleMetadataRelation()));
    }

    @Test void rollbackRemovesIntentAndNeverInstallsRuntime() {
        String alias = alias();
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            activation.schedule(alias);
            assertThat(activation.status(alias).status()).isEqualTo("PENDING");
            verifyNoInteractions(refresh);
            tx.setRollbackOnly();
        });
        assertThat(activation.status(alias).status()).isEqualTo("UNTRACKED");
        verifyNoInteractions(refresh, pages);
    }

    @Test void committedFailureIsQueryableAndRetryDoesNotRepeatPublicationOrDdl() {
        String alias = alias();
        when(refresh.activateNow(alias)).thenThrow(new IllegalStateException("install failed"));
        assertThatCode(() -> activation.schedule(alias)).doesNotThrowAnyException();
        assertThat(activation.status(alias).status()).isEqualTo("FAILED");
        assertThat(activation.status(alias).desiredRevision()).isEqualTo(1);
        assertThat(activation.status(alias).installedRevision()).isNull();
        doReturn(null).when(refresh).activateNow(alias);
        assertThat(activation.retry(alias, 1).status()).isEqualTo("ACTIVE");
        assertThat(activation.status(alias).installedRevision()).isEqualTo(1);
        assertThatThrownBy(() -> activation.retry(alias, 0)).isInstanceOf(OptimisticLockException.class);
        verify(refresh, times(2)).activateNow(alias);
        verify(refresh, never()).prepareSchema(anyString(), any());
        verify(pages).installCurrentPublishedConfiguration(alias);
    }

    @Test void oneModuleFailureDoesNotPreventOtherModuleActivation() {
        String broken = alias(), healthy = alias();
        when(refresh.activateNow(broken)).thenThrow(new IllegalStateException("broken model"));
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            activation.schedule(broken);
            activation.schedule(healthy);
        });
        assertThat(activation.status(broken).status()).isEqualTo("FAILED");
        assertThat(activation.status(healthy).status()).isEqualTo("ACTIVE");
    }

    @Test void lateOldCommitCallbackCannotReplaceANewerInstalledRevision() throws Exception {
        String alias = alias();
        CountDownLatch committed = new CountDownLatch(1), continueOld = new CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var older = executor.submit(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public int getOrder() { return Integer.MIN_VALUE; }
                    @Override public void afterCommit() {
                        committed.countDown();
                        try { assertThat(continueOld.await(10, TimeUnit.SECONDS)).isTrue(); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                    }
                });
                activation.schedule(alias);
            }));
            try {
                assertThat(committed.await(10, TimeUnit.SECONDS)).isTrue();
                activation.schedule(alias);
                assertThat(activation.status(alias).installedRevision()).isEqualTo(2);
            } finally { continueOld.countDown(); }
            older.get(10, TimeUnit.SECONDS);
        }
        assertThat(activation.status(alias).installedRevision()).isEqualTo(2);
        verify(refresh).activateNow(alias);
        verify(pages).installCurrentPublishedConfiguration(alias);
    }

    @Test void startupRebuildsSuccessfulHistoryAndClearsRemovedMainProjections() {
        String alias = alias();
        activation.schedule(alias);
        DynamicRuntimeActivationService restarted = new DynamicRuntimeActivationService(dao, refresh,
                relationProvider, pageProvider, orchestrationProvider, eventProvider, manager, runtime);
        assertThat(restarted.status(alias).installedRevision()).isNull();
        restarted.restoreAtStartup(alias);
        assertThat(restarted.status(alias).installedRevision()).isEqualTo(1);
        verify(refresh, times(2)).activateNow(alias);
        when(relations.list(any(Criteria.class), any(PageRequest.class))).thenReturn(List.of());
        restarted.schedule(alias);
        assertThat(restarted.status(alias).status()).isEqualTo("INACTIVE");
        verify(refresh).deactivateNow(alias);
        verify(pages).removeInstalledConfiguration(alias);
    }

    @Test void pageInstallFailureDoesNotClaimTheOldCompleteRuntimeIsStillInstalled() {
        String alias = alias();
        activation.schedule(alias);
        doThrow(new IllegalStateException("page install failed")).when(pages).installCurrentPublishedConfiguration(alias);
        activation.schedule(alias);
        assertThat(activation.status(alias).lastSuccessfulRevision()).isEqualTo(1);
        assertThat(activation.status(alias).installedRevision()).isNull();
        assertThat(activation.status(alias).status()).isEqualTo("FAILED");
    }

    @Test void reconciliationFailureIsDiagnosedAndRetryUsesTheSamePreparation() {
        String alias = alias();
        doThrow(new IllegalStateException("invalid child system field"))
                .when(orchestration).reconcileChildSystemFields(alias);
        activation.restoreAtStartup(alias);
        assertThat(activation.status(alias).status()).isEqualTo("FAILED");
        verify(refresh, never()).activateNow(alias);
        verify(refresh).deactivateNow(alias);
        verify(pages).removeInstalledConfiguration(alias);
        verifyNoInteractions(events);
        doNothing().when(orchestration).reconcileChildSystemFields(alias);
        assertThat(activation.retry(alias, 1).status()).isEqualTo("ACTIVE");
        var order = inOrder(orchestration, refresh, pages, events);
        order.verify(orchestration, times(2)).reconcileChildSystemFields(alias);
        order.verify(refresh).activateNow(alias);
        order.verify(pages).installCurrentPublishedConfiguration(alias);
        order.verify(events).publish(argThat(event -> event.payload().get("activationRevision").equals(1)));
    }

    @Test void earlierFailedAttemptCannotWithdrawALaterSuccessfulRetryOfTheSameRevision() throws Exception {
        String alias = alias();
        activation.schedule(alias);
        clearInvocations(refresh, pages);
        CountDownLatch rolledBack = new CountDownLatch(1), recordFailure = new CountDownLatch(1);
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(invocation -> {
            if (attempts.getAndIncrement() != 0) return null;
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) {
                    rolledBack.countDown();
                    try { assertThat(recordFailure.await(10, TimeUnit.SECONDS)).isTrue(); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                }
            });
            throw new IllegalStateException("earlier install failed");
        }).when(refresh).activateNow(alias);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var failed = executor.submit(() -> activation.retry(alias, 1));
            try {
                assertThat(rolledBack.await(10, TimeUnit.SECONDS)).isTrue();
                var retry = executor.submit(() -> activation.retry(alias, 1));
                assertThatThrownBy(() -> retry.get(100, TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
                recordFailure.countDown();
                failed.get(10, TimeUnit.SECONDS);
                assertThat(retry.get(10, TimeUnit.SECONDS).status()).isEqualTo("ACTIVE");
            } finally { recordFailure.countDown(); }
        }
        assertThat(activation.status(alias).installedRevision()).isEqualTo(1);
        verify(refresh, times(1)).deactivateNow(alias);
        verify(pages, times(1)).removeInstalledConfiguration(alias);
    }

    @Test void subscriberFailureDoesNotChangeSuccessfulActivationIntoFailedPublication() {
        String alias = alias();
        doThrow(new IllegalStateException("subscriber failed")).when(events).publish(any());
        assertThatCode(() -> activation.schedule(alias)).doesNotThrowAnyException();
        assertThat(activation.status(alias).status()).isEqualTo("ACTIVE");
        assertThat(activation.status(alias).installedRevision()).isEqualTo(1);
    }

    @Test void physicalSchemaFactsMustNotWaitForPublicationWhileHoldingATableLock() throws Exception {
        String alias = alias();
        String table = "activation_scope_" + UUID.randomUUID().toString().replace("-", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("create table public." + table + " (id text primary key, title text)");
        jdbc.update("insert into public." + table + " (id, title) values (?, ?)", "record-1", "Record");
        runtime.register(new ModuleDefinition(alias, "Publication scope", List.of(new EntityDefinition(
                "record", table, "Record", List.of(FieldDefinition.string("title", "Title"))))));
        DynamicSchemaGovernanceFacts facts = new DynamicSchemaGovernanceFacts(runtime);
        assertThat(facts.countPhysicalRecords(alias, "record", Criteria.of())).isEqualTo(1);

        CountDownLatch tableLocked = new CountDownLatch(1), countAllowed = new CountDownLatch(1);
        CountDownLatch countCompleted = new CountDownLatch(1), releaseTable = new CountDownLatch(1);
        CountDownLatch readerStarted = new CountDownLatch(1);
        AtomicInteger schemaPid = new AtomicInteger(), readerPid = new AtomicInteger();
        AtomicReference<Thread> publisherThread = new AtomicReference<>();
        // Daemon workers and database statement timeouts keep a lock-order regression from hanging the test JVM.
        var executor = java.util.concurrent.Executors.newFixedThreadPool(3, task -> {
            Thread thread = new Thread(task, "activation-lock-order");
            thread.setDaemon(true);
            return thread;
        });
        try {
            var schemaChange = executor.submit(() -> new TransactionTemplate(manager).executeWithoutResult(tx -> {
                jdbc.execute("set local lock_timeout = '8s'");
                schemaPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                assertThat(facts.lockExistingTableForSchemaMutation("public", table)).isTrue();
                tableLocked.countDown();
                awaitLatch(countAllowed);
                // This is the real destructive-schema read path: entityService must capture metadata
                // without acquiring a new execution lock behind the waiting publisher.
                assertThat(facts.countPhysicalRecords(alias, "record", Criteria.of())).isEqualTo(1);
                countCompleted.countDown();
                awaitLatch(releaseTable);
            }));
            assertThat(tableLocked.await(10, TimeUnit.SECONDS)).isTrue();
            var reader = executor.submit(() -> {
                try (var ignored = runtime.publication().execution()) {
                    return new TransactionTemplate(manager).execute(tx -> {
                        jdbc.execute("set local statement_timeout = '8s'");
                        readerPid.set(jdbc.queryForObject("select pg_backend_pid()", Integer.class));
                        readerStarted.countDown();
                        return jdbc.queryForObject("select count(*) from public." + table, Long.class);
                    });
                }
            });
            assertThat(readerStarted.await(10, TimeUnit.SECONDS)).isTrue();
            awaitCondition(() -> Boolean.TRUE.equals(jdbc.queryForObject(
                    "select ? = any(pg_blocking_pids(?))", Boolean.class, schemaPid.get(), readerPid.get())));
            var publisher = executor.submit(() -> {
                publisherThread.set(Thread.currentThread());
                activation.schedule(alias);
            });
            awaitCondition(() -> parkedInPublication(publisherThread.get()));

            countAllowed.countDown();
            assertThat(countCompleted.await(2, TimeUnit.SECONDS))
                    .as("physical count completes while the reader holds execution and publication is queued")
                    .isTrue();
            assertThat(reader.isDone()).isFalse();
            assertThat(publisher.isDone()).isFalse();
            verify(refresh, never()).activateNow(alias);

            releaseTable.countDown();
            schemaChange.get(10, TimeUnit.SECONDS);
            assertThat(reader.get(10, TimeUnit.SECONDS)).isEqualTo(1L);
            publisher.get(10, TimeUnit.SECONDS);
            assertThat(activation.status(alias).status()).isEqualTo("ACTIVE");
        } finally {
            countAllowed.countDown();
            releaseTable.countDown();
            executor.shutdownNow();
            boolean terminated = executor.awaitTermination(15, TimeUnit.SECONDS);
            if (terminated) {
                runtime.deactivate(alias);
                jdbc.execute("drop table public." + table);
            }
            assertThat(terminated).as("all publication and database waiters were released").isTrue();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedCommitWithdrawsInstalledProjectionsEvenWhenDiagnosticsCannotStart(boolean diagnosticsUnavailable) {
        String alias = alias();
        activation.schedule(alias);
        clearInvocations(refresh, pages);
        AtomicInteger opened = new AtomicInteger();
        PlatformTransactionManager failingCommit = new PlatformTransactionManager() {
            @Override public org.springframework.transaction.TransactionStatus getTransaction(
                    org.springframework.transaction.TransactionDefinition definition) {
                if (opened.incrementAndGet() == 2 && diagnosticsUnavailable) {
                    throw new org.springframework.transaction.CannotCreateTransactionException("database unavailable");
                }
                return manager.getTransaction(definition);
            }
            @Override public void commit(org.springframework.transaction.TransactionStatus transaction) {
                if (opened.get() == 1) {
                    manager.rollback(transaction);
                    throw new org.springframework.transaction.TransactionSystemException("activation commit failed");
                }
                manager.commit(transaction);
            }
            @Override public void rollback(org.springframework.transaction.TransactionStatus transaction) {
                manager.rollback(transaction);
            }
        };
        var restarted = new DynamicRuntimeActivationService(dao, refresh, relationProvider, pageProvider,
                orchestrationProvider, eventProvider, failingCommit, runtime);

        var status = restarted.retry(alias, 1);

        assertThat(status.installedRevision()).isNull();
        assertThat(status.status()).isEqualTo(diagnosticsUnavailable ? "ACTIVE" : "FAILED");
        assertThat(status.lastSuccessfulRevision()).isEqualTo(1);
        verify(refresh).activateNow(alias);
        verify(pages).installCurrentPublishedConfiguration(alias);
        verify(refresh).deactivateNow(alias);
        verify(pages).removeInstalledConfiguration(alias);
        // The failed attempt released its transaction and publication locks; ordinary retry still works.
        assertThat(activation.retry(alias, 1).installedRevision()).isEqualTo(1);
    }

    @Test void newExecutionWaitsUntilTheActivationTransactionHasCommitted() throws Exception {
        String alias = alias();
        CountDownLatch committing = new CountDownLatch(1), allowCommit = new CountDownLatch(1);
        when(refresh.activateNow(alias)).thenAnswer(invocation -> {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) {
                    committing.countDown();
                    awaitLatch(allowCommit);
                }
            });
            return null;
        });
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2, task -> {
            Thread thread = new Thread(task, "activation-commit-boundary");
            thread.setDaemon(true);
            return thread;
        });
        try {
            var publisher = executor.submit(() -> activation.schedule(alias));
            assertThat(committing.await(10, TimeUnit.SECONDS)).isTrue();
            CountDownLatch readerStarted = new CountDownLatch(1);
            var reader = executor.submit(() -> {
                readerStarted.countDown();
                try (var ignored = runtime.publication().execution()) {
                    return activation.status(alias).status();
                }
            });
            assertThat(readerStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> reader.get(100, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            // The new entity and page projections exist, but their activation transaction is still uncommitted.
            verify(pages).installCurrentPublishedConfiguration(alias);
            assertThat(activation.status(alias).status()).isEqualTo("PENDING");
            allowCommit.countDown();
            publisher.get(10, TimeUnit.SECONDS);
            assertThat(reader.get(10, TimeUnit.SECONDS)).isEqualTo("ACTIVE");
        } finally {
            allowCommit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).as("concurrent test phase was released").isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(10);
        assertThat(condition.getAsBoolean()).as("concurrent test phase became observable").isTrue();
    }

    private static boolean parkedInPublication(Thread thread) {
        return thread != null && thread.getState() == Thread.State.WAITING
                && java.util.Arrays.stream(thread.getStackTrace()).anyMatch(frame ->
                frame.getClassName().equals("net.ximatai.muyun.spring.dynamic.runtime.DynamicRuntimePublication")
                        && frame.getMethodName().equals("publication"));
    }

    private String alias() { return "test.m" + UUID.randomUUID().toString().replace("-", ""); }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {MuYunSpringAutoConfiguration.class, MuYunSpringBusinessLoggingConfiguration.class})
    @EnableMuYunRepositories(basePackageClasses = DynamicRuntimeActivationDao.class)
    @Import({MuYunSpringMutationConfiguration.class, MuYunSpringDatabaseConfiguration.class, DynamicRuntimeActivationService.class})
    static class Application {
        @Bean DataSource dataSource() {
            return DataSourceBuilder.create().url(postgres.getJdbcUrl()).username(postgres.getUsername())
                    .password(postgres.getPassword()).driverClassName(postgres.getDriverClassName()).build();
        }
        @Bean net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordRuntime runtime(IDatabaseOperations<?> operations) {
            return new net.ximatai.muyun.spring.dynamic.runtime.DynamicRecordRuntime(operations);
        }
        @Bean PlatformDynamicRuntimeRefresher refresh() { return mock(PlatformDynamicRuntimeRefresher.class); }
        @Bean ModuleMetadataRelationService relations() { return mock(ModuleMetadataRelationService.class); }
        @Bean ModuleMetadataOrchestrationService orchestration() { return mock(ModuleMetadataOrchestrationService.class); }
        @Bean RuntimeEventPublisher events() { return mock(RuntimeEventPublisher.class); }
        @Bean PublishedPageExecutionCoordinator pages() { return mock(PublishedPageExecutionCoordinator.class); }
    }
}
