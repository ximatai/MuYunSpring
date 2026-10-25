package net.ximatai.muyun.spring.starter.configuration.platform;

import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

/** Exercises the installed starter operator, including its key encoding and outer transaction lifetime. */
@Testcontainers(disabledWithoutDocker = true)
class ReferenceTargetLockRepositoryIT {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final ReferenceTarget TARGET = ReferenceTarget.of("test.target", "entry");

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void sharedSavesCoexistAndDeletionWaitsUntilCommitOrRollback(boolean rollback) {
        withHost((transaction) -> {
            var attempted = new CountDownLatch(1);
            try (var pool = Executors.newFixedThreadPool(2)) {
                Future<?>[] deletion = new Future<?>[1];
                transaction.executeWithoutResult(status -> {
                    PlatformAbilityRuntime.lockReferenceTargetForRead(TARGET, "target");
                    // A nested standard save returning must not release the outer transaction's lock.
                    transaction.executeWithoutResult(nested -> PlatformAbilityRuntime.lockReferenceTargetForRead(TARGET, "target"));
                    Future<?> save = pool.submit(() -> transaction.executeWithoutResult(other ->
                            PlatformAbilityRuntime.lockReferenceTargetForRead(TARGET, "target")));
                    assertThatCode(() -> save.get(5, TimeUnit.SECONDS)).doesNotThrowAnyException();
                    deletion[0] = pool.submit(() -> transaction.executeWithoutResult(other -> {
                        attempted.countDown();
                        PlatformAbilityRuntime.lockMutationPartition("reference-target:" + TARGET.qualifiedName(), "target");
                    }));
                    assertThatCode(() -> assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue()).doesNotThrowAnyException();
                    assertThatThrownBy(() -> deletion[0].get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                    if (rollback) status.setRollbackOnly();
                });
                assertThatCode(() -> deletion[0].get(5, TimeUnit.SECONDS)).doesNotThrowAnyException();
            }
        });
    }

    @Test
    void deletionExcludesSavesAndSharedLocksRequireTransaction() {
        withHost(transaction -> {
            assertThatThrownBy(() -> PlatformAbilityRuntime.lockReferenceTargetForRead(TARGET, "target"))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("database-bound transaction");
            var attempted = new CountDownLatch(1);
            try (var pool = Executors.newSingleThreadExecutor()) {
                Future<?>[] save = new Future<?>[1];
                transaction.executeWithoutResult(status -> {
                    PlatformAbilityRuntime.lockReferenceTargetForDelete(TARGET, "target");
                    save[0] = pool.submit(() -> transaction.executeWithoutResult(other -> {
                        attempted.countDown();
                        PlatformAbilityRuntime.lockReferenceTargetForRead(TARGET, "target");
                    }));
                    assertThatCode(() -> assertThat(attempted.await(5, TimeUnit.SECONDS)).isTrue()).doesNotThrowAnyException();
                    assertThatThrownBy(() -> save[0].get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                });
                assertThatCode(() -> save[0].get(5, TimeUnit.SECONDS)).doesNotThrowAnyException();
            }
        });
    }

    private void withHost(java.util.function.Consumer<TransactionTemplate> work) {
        DataSource source = DataSourceBuilder.create().url(postgres.getJdbcUrl()).username(postgres.getUsername())
                .password(postgres.getPassword()).driverClassName(postgres.getDriverClassName()).build();
        var manager = new DataSourceTransactionManager(source);
        new ApplicationContextRunner().withUserConfiguration(MuYunSpringMutationConfiguration.class)
                .withBean(DataSource.class, () -> source)
                .withBean(org.springframework.transaction.PlatformTransactionManager.class, () -> manager)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    work.accept(new TransactionTemplate(manager));
                });
    }
}
