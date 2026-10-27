package net.ximatai.muyun.spring.starter.configuration.platform;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.job.*;
import net.ximatai.muyun.spring.platform.notification.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.LockSupport;
import java.util.function.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real node pools and database arbitration; only process suspension and persisted deadlines are controlled. */
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(classes = JobMultinodeIT.Application.class,
        properties = "muyun.database.repository-schema-mode=ENSURE")
class JobMultinodeIT {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @Autowired JobPlanDao plans;
    @Autowired JobRunDao runs;
    @Autowired IDatabaseOperations<?> db;
    @Autowired PlatformTransactionManager transactions;
    private final List<Node> nodes = new ArrayList<>();
    private final List<CountDownLatch> gates = new ArrayList<>();

    @BeforeEach void clean() {
        global(() -> {
            db.execute("delete from platform_job_run");
            db.execute("delete from platform_job_plan");
            db.execute("create table if not exists job_multinode_effect (id varchar primary key)");
            db.execute("delete from job_multinode_effect");
            return null;
        });
    }

    @AfterEach void stop() {
        // Unblock even failed assertions before stopping pools and before the next test deletes data.
        gates.forEach(gate -> { while (gate.getCount() > 0) gate.countDown(); });
        nodes.forEach(Node::close);
    }

    @Test void concurrentNodeScannersCreateOneRunForOneScheduledTrigger() {
        var entered = gate(1); var finish = gate(1); var scans = gate(2); var scanGate = gate(2);
        Function<JobContext, JobResult> handler = context -> {
            entered.countDown(); await(finish); return success();
        };
        var a = node(JobScope.PLATFORM, true, handler);
        var b = node(JobScope.PLATFORM, true, handler);
        for (var node : List.of(a, b)) doAnswer(call -> {
            scanGate.countDown(); await(scanGate);
            try { return call.callRealMethod(); } finally { scans.countDown(); }
        }).when(node.jobs).scan();
        a.executor.start(); b.executor.start();
        await(scans); await(entered);
        var rows = rows();
        assertThat(rows).hasSize(1);
        var run = rows.getFirst();
        assertThat(run.getTriggerType()).isEqualTo("SCHEDULE");
        assertThat(run.getScheduledAt()).isNotNull();
        assertThat(run.getAttempts()).isEqualTo(1);
        assertThat(global(() -> plans.findById(run.getPlanId()).getNextFireAt())).isNull();
        finish.countDown();
        eventually(() -> assertThat(row(run.getId()).getStatus()).isEqualTo("SUCCEEDED"));
    }

    @Test void competingWorkersKeepOneEffectiveLeaseAndRenewWhileHandlerIsBlocked() {
        var entered = gate(1); var finish = gate(1); var lostClaim = gate(1); var claimGate = gate(2);
        var active = new AtomicInteger(); var maximum = new AtomicInteger(); var calls = new AtomicInteger();
        Function<JobContext, JobResult> handler = context -> {
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            try { calls.incrementAndGet(); entered.countDown(); await(finish); return success(); }
            finally { active.decrementAndGet(); }
        };
        var a = node(JobScope.PLATFORM, false, handler); var b = node(JobScope.PLATFORM, false, handler);
        var first = enqueue(a, null, "v1"); var second = enqueue(b, null, "v2");
        for (var node : List.of(a, b)) {
            var firstClaim = new AtomicBoolean(true);
            doAnswer(call -> {
                if (firstClaim.getAndSet(false)) { claimGate.countDown(); await(claimGate); }
                boolean claimed = (boolean) call.callRealMethod();
                if (!claimed) lostClaim.countDown();
                return claimed;
            }).when(node.jobs).runOne(anyString(), anyString(), any(BooleanSupplier.class));
        }
        a.executor.start(); b.executor.start(); await(entered); await(lostClaim);
        assertThat(rows()).filteredOn(run -> "RUNNING".equals(run.getStatus())).hasSize(1);
        assertThat(calls).hasValue(1);
        var running = rows().stream().filter(run -> "RUNNING".equals(run.getStatus())).findFirst().orElseThrow();
        global(() -> { db.execute("update platform_job_run set lease_until=clock_timestamp()+interval '20 seconds' where id=?", running.getId()); return null; });
        var shortened = row(running.getId()).getLeaseUntil();
        eventually(() -> assertThat(row(running.getId()).getLeaseUntil()).isAfter(shortened));
        finish.countDown();
        eventually(() -> {
            assertThat(row(first.getId()).getStatus()).isEqualTo("SUCCEEDED");
            assertThat(row(second.getId()).getStatus()).isEqualTo("SUCCEEDED");
        });
        assertThat(calls).hasValue(2); assertThat(maximum).hasValue(1);
        assertThat(rows()).allSatisfy(run -> assertThat(run.getAttempts()).isEqualTo(1));
    }

    @Test void tenantsWithSameTopicAndDeduplicationKeyExecuteConcurrentlyWithoutLeakingRuns() throws Exception {
        var entered = gate(2); var finish = gate(1); var tenants = ConcurrentHashMap.<String>newKeySet();
        Function<JobContext, JobResult> handler = context -> {
            assertThat(TenantContext.currentTenantId()).contains(context.tenantId());
            tenants.add(context.tenantId()); entered.countDown(); await(finish); return success();
        };
        var a = node(JobScope.TENANT, false, handler); var b = node(JobScope.TENANT, false, handler);
        var duplicates = together(() -> enqueue(a, "tenant-a", "same:v1"), () -> enqueue(b, "tenant-a", "same:v1"));
        assertThat(duplicates.get(0).getId()).isEqualTo(duplicates.get(1).getId());
        assertThat(duplicates).allSatisfy(run -> assertThat(run.getTenantId()).isEqualTo("tenant-a"));
        var other = enqueue(b, "tenant-b", "same:v1");
        assertThat(other.getId()).isNotEqualTo(duplicates.getFirst().getId());
        assertThat(other.getTenantId()).isEqualTo("tenant-b");
        assertThat(enqueue(a, "tenant-b", "same:v1").getId()).isEqualTo(other.getId());
        a.executor.start(); b.executor.start(); await(entered);
        assertThat(tenants).containsExactlyInAnyOrder("tenant-a", "tenant-b");
        assertThat(rows()).hasSize(2).allSatisfy(run -> {
            assertThat(run.getStatus()).isEqualTo("RUNNING");
            assertThat(run.getAttempts()).isEqualTo(1);
        });
        assertThat(rows()).extracting(run -> nodePrefix(run.getNodeId())).doesNotHaveDuplicates();
        assertThat(rows()).extracting(JobRun::getLeaseToken).doesNotHaveDuplicates();
        finish.countDown();
        eventually(() -> assertThat(rows()).allSatisfy(run -> assertThat(run.getStatus()).isEqualTo("SUCCEEDED")));
    }

    @Test void suspendedNodeIsTakenOverAndItsResumedProtectedWriteAndCompletionAreFenced() {
        var entered = gate(1); var resume = gate(1); var oldReturned = gate(1); var heartbeat = gate(1);
        var staleFailure = new AtomicReference<Throwable>();
        var oldContext = new AtomicReference<JobContext>();
        var a = node(JobScope.PLATFORM, false, context -> {
            oldContext.set(context); entered.countDown(); await(resume);
            try { effect(context, "stale"); } catch (RuntimeException failure) { staleFailure.set(failure); }
            return JobResult.success("STALE_COMPLETION", Map.of());
        });
        // Simulate a process paused outside a business transaction: neither renewal nor release occurs.
        doAnswer(call -> { heartbeat.countDown(); return null; }).when(a.jobs).renewNode(anyString());
        doAnswer(call -> {
            boolean result = (boolean) call.callRealMethod();
            if (result) oldReturned.countDown();
            return result;
        }).when(a.jobs).runOne(anyString(), anyString(), any(BooleanSupplier.class));
        var queued = enqueue(a, null, "takeover"); a.executor.start(); await(entered); await(heartbeat);
        var old = row(queued.getId()); expire(old.getId());
        global(() -> {
            assertThatThrownBy(() -> effect(oldContext.get(), "expired"))
                    .isInstanceOf(JobFailure.class).hasMessageContaining("LEASE_LOST");
            return null;
        });
        var replacementEntered = gate(1); var replacementFinish = gate(1);
        var b = node(JobScope.PLATFORM, false, context -> {
            replacementEntered.countDown(); await(replacementFinish); effect(context, "replacement"); return success();
        });
        b.executor.start(); await(replacementEntered);
        var replacement = row(old.getId());
        assertThat(replacement.getAttempts()).isEqualTo(2);
        assertThat(replacement.getLeaseToken()).isNotEqualTo(old.getLeaseToken());
        assertThat(nodePrefix(replacement.getNodeId())).isNotEqualTo(nodePrefix(old.getNodeId()));
        resume.countDown(); await(oldReturned);
        assertThat(staleFailure.get()).isInstanceOf(JobFailure.class).hasMessageContaining("LEASE_LOST");
        assertThat(row(old.getId()).getLeaseToken()).isEqualTo(replacement.getLeaseToken());
        assertThat(row(old.getId()).getStatus()).isEqualTo("RUNNING");
        replacementFinish.countDown();
        eventually(() -> assertThat(row(old.getId()).getStatus()).isEqualTo("SUCCEEDED"));
        assertThat(row(old.getId()).getResultCode()).isEqualTo("COMPLETE");
        assertThat(db.query("select id from job_multinode_effect")).extracting(value -> value.get("id")).containsExactly("replacement");
    }

    @Test void gracefulStopReleasesBeforeInterruptAndNextNodeDoesNotConsumeAnotherAttempt() {
        var entered = gate(1); var interrupted = gate(1); var hold = gate(1);
        var staleFailure = new AtomicReference<Throwable>();
        var a = node(JobScope.PLATFORM, false, context -> {
            entered.countDown();
            try { hold.await(); }
            catch (InterruptedException stopped) {
                // Clear the interrupt so the database, rather than interruption, rejects the stale token.
                try { effect(context, "stopped"); } catch (RuntimeException failure) { staleFailure.set(failure); }
                finally { interrupted.countDown(); }
            }
            return success();
        });
        var queued = enqueue(a, null, "shutdown"); a.executor.start(); await(entered);
        var old = row(queued.getId()); a.close(); await(interrupted);
        var released = row(old.getId());
        assertThat(released.getStatus()).isEqualTo("PENDING"); assertThat(released.getAttempts()).isZero();
        assertThat(released.getLeaseToken()).isNull(); assertThat(released.getLeaseUntil()).isNull(); assertThat(released.getNodeId()).isNull();
        assertThat(staleFailure.get()).isInstanceOf(JobFailure.class).hasMessageContaining("LEASE_LOST");
        var b = node(JobScope.PLATFORM, false, context -> { effect(context, "resumed"); return success(); });
        b.executor.start();
        eventually(() -> assertThat(row(old.getId()).getStatus()).isEqualTo("SUCCEEDED"));
        assertThat(row(old.getId()).getAttempts()).isEqualTo(1);
        assertThat(db.query("select id from job_multinode_effect")).extracting(value -> value.get("id")).containsExactly("resumed");
    }

    private Node node(JobScope scope, boolean scheduled, Function<JobContext, JobResult> execution) {
        var handlerThreads = ConcurrentHashMap.<Thread>newKeySet();
        var registry = new JobRegistry(List.of(new JobHandler() {
            public JobDefinition definition() {
                return new JobDefinition("test.multinode", "test.name", scope,
                        scheduled ? JobSchedule.fixedDelay(Duration.ofHours(1), Duration.ZERO) : null,
                        "default", null, Duration.ofMinutes(5), 3, Duration.ZERO,
                        input -> "same-topic", false, 3, true, null);
            }
            public JobResult execute(JobContext context) {
                handlerThreads.add(Thread.currentThread());
                return execution.apply(context);
            }
        }));
        var beans = new DefaultListableBeanFactory();
        var service = spy(new JobService(plans, runs, db, transactions, registry,
                beans.getBeanProvider(JobFailureRecipientResolver.class), beans.getBeanProvider(BusinessNotificationDelivery.class),
                beans.getBeanProvider(JobManagementAccess.class), Duration.ofSeconds(30), 90));
        var properties = new MuYunSpringJobProperties();
        properties.setScheduledEnabled(scheduled); properties.setScanInterval(Duration.ofHours(1));
        properties.setShutdownGrace(Duration.ZERO);
        properties.getChannels().get("default").setThreads(1);
        properties.getChannels().get("default").setClaimInterval(Duration.ofMillis(100));
        var node = new Node(service, new JobNodeExecutor(service, registry, properties), handlerThreads); nodes.add(node); return node;
    }

    private final class Node implements AutoCloseable {
        final JobService jobs;
        final JobNodeExecutor executor;
        final Set<Thread> handlerThreads;
        Node(JobService jobs, JobNodeExecutor executor, Set<Thread> handlerThreads) {
            this.jobs = jobs; this.executor = executor; this.handlerThreads = handlerThreads;
        }
        public void close() {
            executor.stop();
            // stop() interrupts residual workers without joining; wait for their DB transactions before cleanup.
            eventually(() -> assertThat(handlerThreads).noneMatch(Thread::isAlive));
        }
    }
    private JobRun enqueue(Node node, String tenant, String key) {
        try (var scope = tenant == null ? TenantContext.system("test enqueue") : TenantContext.use(tenant)) {
            return node.jobs.enqueue("test.multinode", tenant, Map.of(), key);
        }
    }
    private void effect(JobContext context, String id) {
        context.transaction(() -> { db.execute("insert into job_multinode_effect values (?)", id); return null; });
    }
    private static JobResult success() { return JobResult.success("COMPLETE", Map.of()); }
    private List<JobRun> rows() { return global(() -> runs.query(Criteria.of(), PageRequest.of(1, 100))); }
    private JobRun row(String id) { return global(() -> runs.findById(id)); }
    private void expire(String id) { global(() -> { db.execute("update platform_job_run set lease_until=clock_timestamp()-interval '1 second' where id=?", id); return null; }); }
    private static String nodePrefix(String owner) { return owner.substring(0, owner.indexOf(':')); }
    private CountDownLatch gate(int count) { var gate = new CountDownLatch(count); gates.add(gate); return gate; }
    private static void await(CountDownLatch gate) {
        try { assertThat(gate.await(10, TimeUnit.SECONDS)).as("node synchronization").isTrue(); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
    }
    private static void eventually(Runnable assertion) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (true) {
            try { assertion.run(); return; }
            catch (AssertionError failure) {
                if (System.nanoTime() >= deadline) throw failure;
                LockSupport.parkNanos(Duration.ofMillis(10).toNanos());
            }
        }
    }
    private static <T> T global(Supplier<T> work) {
        try (var scope = TenantContext.system("multinode verification"); var bypass = TenantContext.bypassTenantFilter("multinode verification")) { return work.get(); }
    }
    private static <T> List<T> together(Supplier<T> a, Supplier<T> b) throws Exception {
        var gate = new CountDownLatch(2);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { gate.countDown(); await(gate); return a.get(); });
            var second = pool.submit(() -> { gate.countDown(); await(gate); return b.get(); });
            return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
    }
    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {net.ximatai.muyun.spring.starter.MuYunSpringAutoConfiguration.class,
            MuYunSpringBusinessLoggingConfiguration.class})
    @EnableMuYunRepositories(basePackageClasses = JobPlanDao.class)
    static class Application {
        @Bean net.ximatai.muyun.database.spring.boot.JdbiConfigurer bigIntegerJdbiConfigurer() {
            return jdbi -> jdbi.registerArgument(new org.jdbi.v3.core.argument.AbstractArgumentFactory<java.math.BigInteger>(java.sql.Types.BIGINT) {
                protected org.jdbi.v3.core.argument.Argument build(java.math.BigInteger value, org.jdbi.v3.core.config.ConfigRegistry config) {
                    return (position, statement, context) -> statement.setLong(position, value.longValueExact());
                }
            });
        }
        @Bean DataSource dataSource() {
            return DataSourceBuilder.create().url(postgres.getJdbcUrl()).username(postgres.getUsername())
                    .password(postgres.getPassword()).driverClassName(postgres.getDriverClassName()).build();
        }
    }
}
