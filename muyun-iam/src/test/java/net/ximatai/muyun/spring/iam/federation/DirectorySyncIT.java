package net.ximatai.muyun.spring.iam.federation;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.orm.*;
import net.ximatai.muyun.database.spring.boot.JdbiConfigurer;
import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.spring.ability.*;
import net.ximatai.muyun.spring.common.identity.*;
import net.ximatai.muyun.spring.common.platform.*;
import net.ximatai.muyun.spring.common.platform.ReferenceDependencyScopeCatalogResolver;
import net.ximatai.muyun.spring.common.runtime.PlatformRuntimeModeProvider;
import net.ximatai.muyun.spring.common.tenant.*;
import net.ximatai.muyun.spring.iam.department.*;
import net.ximatai.muyun.spring.iam.employee.*;
import net.ximatai.muyun.spring.iam.organization.*;
import net.ximatai.muyun.spring.iam.position.*;
import net.ximatai.muyun.spring.iam.role.*;
import net.ximatai.muyun.spring.iam.support.UserAccountServiceTestFactory;
import net.ximatai.muyun.spring.iam.tenant.TenantApplicationService;
import net.ximatai.muyun.spring.iam.user.*;
import net.ximatai.muyun.spring.platform.job.*;
import net.ximatai.muyun.spring.platform.notification.*;

import org.jdbi.v3.core.argument.AbstractArgumentFactory;
import org.jdbi.v3.core.argument.Argument;
import org.jdbi.v3.core.config.ConfigRegistry;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.math.BigInteger;
import java.sql.Types;
import java.time.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import javax.sql.DataSource;

/** Full vendor → fenced page → real standard IAM services/DAOs path against PostgreSQL. */
@Testcontainers
@SpringBootTest(classes = DirectorySyncIT.Application.class)
class DirectorySyncIT {
    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry p) {
        p.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }

    @Autowired IDatabaseOperations<?> db;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DirectorySyncRunDao syncRuns;
    @Autowired DirectorySyncScopeDao scopes;
    @Autowired DirectoryDepartmentMappingDao mappings;
    @Autowired DirectorySyncSeenDao seen;
    @Autowired DirectorySyncDepartmentDao observedDepartments;
    @Autowired IdentityProviderDao providerRows;
    @Autowired TenantIdentityPolicyDao policyRows;
    @Autowired ExternalIdentityDao identityRows;
    @Autowired DirectoryRebindBlockDao rebindBlocks;
    @Autowired ExternalLoginTransactionDao loginTransactions;
    @Autowired DepartmentDao departmentRows;
    @Autowired EmployeeDao employeeRows;
    @Autowired EmployeePositionDao positionRows;
    @Autowired UserAccountDao accountRows;
    @Autowired UserSessionDao sessionRows;
    @Autowired EmployeeAccountDao employeeAccountRows;
    @Autowired OrganizationDao organizationRows;
    @Autowired PositionDao jobPositionRows;
    @Autowired RoleDao roleRows;
    @Autowired EmploymentRoleGrantDao grantRows;
    @Autowired JobPlanDao jobPlans;
    @Autowired JobRunDao jobRuns;
    private final AtomicInteger departmentReads = new AtomicInteger();
    private volatile Runnable peopleHook = () -> {};
    private volatile boolean outsideScopePage;
    private volatile Runnable departmentHook = () -> {};
    private JobService jobs;
    private TenantIdentityPolicyService policies;
    private EmployeePositionService positions;
    private IdentityProvider provider;
    private TenantIdentityPolicy policy;
    private final List<BusinessNotification> alerts = new ArrayList<>();
    private DirectorySyncJob handler;
    private DirectorySyncWriter writer;
    private ExternalDirectoryProtocol protocol;

    @BeforeEach
    void setup() {
        alerts.clear();
        departmentReads.set(0);
        peopleHook = () -> {};
        departmentHook = () -> {};
        global(
                () -> {
                    for (String table :
                            List.of(
                                    "iam_directory_sync_seen",
                                    "iam_directory_sync_department",
                                    "iam_directory_department_mapping",
                                    "iam_directory_sync_run",
                                    "iam_directory_sync_scope",
                                    "platform_job_run",
                                    "platform_job_plan",
                                    "iam_user_session",
                                    "iam_external_identity",
                                    "iam_directory_rebind_block",
                                    "iam_employment_role_grant",
                                    "iam_role",
                                    "iam_external_login_transaction",
                                    "iam_employee_account",
                                    "iam_employee_position",
                                    "iam_employee",
                                    "iam_user",
                                    "iam_department",
                                    "iam_organization",
                                    "iam_position",
                                    "iam_tenant_identity_policy",
                                    "iam_identity_provider")) db.execute("delete from " + table);
                    return null;
                });
        var tx = new TransactionTemplate(transactions);
        PlatformAbilityRuntime.configureMutationTransactionOperator(
                new MutationTransactionOperator() {
                    public <T> T execute(Supplier<T> work) {
                        return tx.execute(status -> work.get());
                    }

                    public void lock(String scope, String key) {
                        db.query(
                                "select pg_advisory_xact_lock(hashtextextended(?,0))",
                                scope + ":" + key);
                    }

                    public void lockShared(String scope, String key) {
                        lock(scope, key);
                    }
                });
        var mode = mock(PlatformRuntimeModeProvider.class);
        when(mode.isDevelopment()).thenReturn(true);
        outsideScopePage = false;
        var mockDirectory = new MockExternalDirectoryProtocol(mode);
        // Assert every provider call is outside a database transaction and outside the topic lock.
        protocol =
                new ExternalDirectoryProtocol() {
                    private void outside() {
                        assertThat(TransactionScopeSupport.isTransactionActive()).isFalse();
                    }

                    public String providerType() {
                        return "mock";
                    }

                    public Department department(IdentityProviderDescriptor p, String id) {
                        outside();
                        return mockDirectory.department(p, id);
                    }

                    public Page<Department> departments(
                            IdentityProviderDescriptor p, String root, String cursor, int limit) {
                        outside();
                        departmentReads.incrementAndGet();
                        departmentHook.run();
                        if (outsideScopePage) return new Page<>(List.of(mockDirectory.department(p, "b")), null);
                        return mockDirectory.departments(p, root, cursor, limit);
                    }

                    public Page<Person> departmentPeople(
                            IdentityProviderDescriptor p, String id, String cursor, int limit) {
                        outside();
                        peopleHook.run();
                        return mockDirectory.departmentPeople(p, id, cursor, limit);
                    }

                    public boolean supportsPersonLookup(IdentityProviderDescriptor p) {
                        return mockDirectory.supportsPersonLookup(p);
                    }

                    public Person person(IdentityProviderDescriptor p, String id) {
                        outside();
                        return mockDirectory.person(p, id);
                    }
                };
        var providers =
                new IdentityProviderService(
                        providerRows,
                        List.of(new MockExternalLoginProtocol(mode)),
                        List.of(protocol),
                        identityRows,
                        new ExternalIdentityAdmissionLocks(db));
        var beans = new DefaultListableBeanFactory();
        var organizations =
                new OrganizationService(
                        organizationRows,
                        t -> {},
                        beans.getBeanProvider(OrganizationCreationProvisioner.class));
        var departments =
                new DepartmentService(departmentRows, t -> {}) {
                    @Override
                    public DataScopeCriteriaService getDataScopeCriteriaService() {
                        return new AllowAllDataScopeCriteriaService();
                    }
                };
        var employees =
                new EmployeeService(employeeRows, t -> {}, departments) {
                    @Override
                    public DataScopeCriteriaService getDataScopeCriteriaService() {
                        return new AllowAllDataScopeCriteriaService();
                    }
                };
        positions = new EmployeePositionService(positionRows, t -> {}, employees);
        var accounts =
                UserAccountServiceTestFactory.create(
                        accountRows, t -> {}, mock(PasswordHashingService.class));
        var employeeAccounts =
                new EmployeeAccountService(employeeAccountRows, t -> {}, employees, accounts);
        var store =
                new DirectorySyncStore(
                        syncRuns, scopes, mappings, seen, observedDepartments, db, policyRows);
        var revocations = new UserSessionRevocationService(
                new UserSessionRecordService(sessionRows), event -> {}, Clock.systemUTC());
        var identities = new ExternalIdentityService(identityRows, t -> {}, revocations, rebindBlocks, store);
        var jobPositionService = new PositionService(jobPositionRows, t -> {});
        var roles =
                new RoleService(
                        roleRows,
                        mock(AccountRoleGrantDao.class),
                        grantRows,
                        mock(RoleActionDao.class),
                        t -> {},
                        RoleActionGrantVerifier.platformActionsOnly(),
                        accounts,
                        employees,
                        positions,
                        employeeAccounts,
                        organizations,
                        mock(RoleDataGrantActionDao.class),
                        mock(TenantApplicationService.class),
                        beans.getBeanProvider(ReferenceDependencyScopeCatalogResolver.class));
        // Resolve the real JobService lazily only while assembling the cyclic handler/registry
        // graph.
        var jobFacade = mock(JobService.class);
        when(jobFacade.registerManaged(anyString(), any(), any()))
                .thenAnswer(
                        c ->
                                jobs.registerManaged(
                                        c.getArgument(0), c.getArgument(1), c.getArgument(2)));
        doAnswer(
                        c -> {
                            jobs.deleteManaged(
                                    c.getArgument(0), c.getArgument(1), c.getArgument(2));
                            return null;
                        })
                .when(jobFacade)
                .deleteManaged(anyString(), anyString(), anyString());
        beans.registerSingleton("jobFacade", jobFacade);
        var config =
                new DirectorySyncConfiguration(
                        store,
                        providers,
                        List.of(protocol),
                        organizations,
                        jobPositionService,
                        roles,
                        beans.getBeanProvider(JobService.class));
        writer =
                spy(new DirectorySyncWriter(
                        store,
                        departments,
                        employees,
                        positions,
                        accounts,
                        employeeAccounts,
                        identities,
                        roles,
                        config,
                        new DirectorySourceLifecycle(
                                employeeAccounts,
                                positions,
                                roles,
                                revocations)));
        handler = new DirectorySyncJob(store, providers, config, writer);
        beans.registerSingleton("access", (JobManagementAccess) t -> {});
        beans.registerSingleton(
                "recipients",
                (JobFailureRecipientResolver)
                        t ->
                                new BusinessNotificationRecipients(
                                        false,
                                        List.of(),
                                        List.of(),
                                        List.of(),
                                        List.of(),
                                        List.of("admin")));
        beans.registerSingleton("alerts", (BusinessNotificationDelivery) alerts::add);
        jobs =
                new JobService(
                        jobPlans,
                        jobRuns,
                        db,
                        transactions,
                        new JobRegistry(
                                List.of(
                                        handler,
                                        new ExternalLoginTransactionRetentionJob(db),
                                        new DirectorySyncRetentionJob(db, store))),
                        beans.getBeanProvider(JobFailureRecipientResolver.class),
                        beans.getBeanProvider(BusinessNotificationDelivery.class),
                        beans.getBeanProvider(JobManagementAccess.class),
                        Duration.ofMinutes(1),
                        90);
        policies = new TenantIdentityPolicyService(policyRows, t -> {}, providers, config, jobs);
        provider = new IdentityProvider();
        provider.setId("mock");
        provider.setTitle("Mock");
        provider.setProviderType("mock");
        provider.setExternalEnterpriseId("enterprise");
        provider.setIdentityNamespace("mock-app");
        provider.setConfiguration(
                DirectorySyncStore.encode(Map.of("directory", fixture(List.of()))));
        global(
                () -> {
                    DirectorySyncStore.insert(providerRows, provider);
                    return null;
                });
        policy = tenant("tenant-a", () -> seedPolicy("tenant-a"));
    }

    @AfterEach
    void reset() {
        PlatformAbilityRuntime.resetMutationTransactionOperator();
    }

    private TenantIdentityPolicy seedPolicy(String tenant) {
        var org = new Organization();
        org.setTenantId(tenant);
        org.setCode("HQ");
        org.setTitle("HQ");
        DirectorySyncStore.insert(organizationRows, org);
        var position = new Position();
        position.setTenantId(tenant);
        position.setCategoryId("category");
        position.setCode("member");
        position.setTitle("Member");
        DirectorySyncStore.insert(jobPositionRows, position);
        var policy = new TenantIdentityPolicy();
        policy.setProviderId(provider.getId());
        policy.setSyncPositionId(position.getId());
        policy.setSyncScopeConfirmed(true);
        policy.setSyncRoots(
                DirectorySyncStore.encode(
                        List.of(
                                new DirectorySyncConfiguration.Root("a", org.getId()),
                                new DirectorySyncConfiguration.Root("b", org.getId()),
                                new DirectorySyncConfiguration.Root("a1", org.getId()),
                                new DirectorySyncConfiguration.Root("a", org.getId()))));
        var role = new Role();
        role.setTenantId(tenant);
        role.setTitle("Directory member");
        role.setOwnerScopeKey("tenant:");
        DirectorySyncStore.insert(roleRows, role);
        policy.setSyncRoleId(role.getId());
        policy.setSyncExpression("0 0 * * * *");
        policy.setSyncEnabled(true);
        policies.insert(policy);
        return policy;
    }

    @Test
    void overlapMultiDepartmentIdempotencyManualEmploymentAndLocalDisablement() {
        assertThat(DirectorySyncConfiguration.roots(policy.getSyncRoots()))
                .extracting(DirectorySyncConfiguration.Root::externalDepartmentId)
                .containsExactly("a", "b");
        String first = enqueue(false);
        execute();
        assertThat(jobRuns.findById(first).getStatus()).isEqualTo("SUCCEEDED");
        assertThat(jobRuns.findById(first).getProgress()).isEqualTo(100);
        tenant(
                "tenant-a",
                () -> {
                    assertThat(employeeRows.query(Criteria.of(), new PageRequest(0, 100)))
                            .hasSize(1);
                    assertThat(accountRows.query(Criteria.of(), new PageRequest(0, 100)))
                            .singleElement()
                            .satisfies(
                                    a -> {
                                        assertThat(a.getPasswordHash()).isNull();
                                        assertThat(a.getPasswordStatus())
                                                .isEqualTo(PasswordStatus.NONE);
                                    });
                    var employments = positionRows.query(Criteria.of(), new PageRequest(0, 100));
                    assertThat(employments).hasSize(2);
                    assertThat(grantRows.query(Criteria.of(), new PageRequest(0, 100)))
                            .singleElement()
                            .satisfies(
                                    grant -> {
                                        assertThat(grant.getEmployeePositionId())
                                                .isEqualTo(
                                                        employments.stream()
                                                                .filter(
                                                                        e ->
                                                                                Boolean.TRUE.equals(
                                                                                        e
                                                                                                .getPrimaryPosition()))
                                                                .findFirst()
                                                                .orElseThrow()
                                                                .getId());
                                    });
                    db.execute(
                            "update iam_employment_role_grant set enabled=false where tenant_id=?",
                            "tenant-a");
                    assertThat(
                                    employments.stream()
                                            .filter(
                                                    p ->
                                                            Boolean.TRUE.equals(
                                                                    p.getPrimaryPosition())))
                            .hasSize(1);
                    var secondary =
                            employments.stream()
                                    .filter(p -> !Boolean.TRUE.equals(p.getPrimaryPosition()))
                                    .findFirst()
                                    .orElseThrow();
                    var manual = new EmployeePosition();
                    manual.setEmployeeId(secondary.getEmployeeId());
                    manual.setOrganizationId(secondary.getOrganizationId());
                    manual.setDepartmentId(secondary.getDepartmentId());
                    manual.setPositionId(secondary.getPositionId());
                    positions.insert(manual);
                    db.execute("update iam_user set enabled=false where tenant_id=?", "tenant-a");
                    var edit = new EmployeePosition();
                    edit.setId(secondary.getId());
                    edit.setVersion(secondary.getVersion());
                    edit.setEmployeeId(secondary.getEmployeeId());
                    edit.setOrganizationId(secondary.getOrganizationId());
                    edit.setDepartmentId(secondary.getDepartmentId());
                    edit.setPositionId(secondary.getPositionId());
                    edit.setEnabled(false);
                    positions.update(edit);
                    assertThat(positions.selectActiveRaw(edit.getId()).getSource())
                            .isEqualTo("directory");
                    return null;
                });
        String second = enqueue(false);
        execute();
        assertThat(jobRuns.findById(second).getStatus()).isEqualTo("SUCCEEDED");
        tenant(
                "tenant-a",
                () -> {
                    assertThat(accountRows.query(Criteria.of(), new PageRequest(0, 100)))
                            .singleElement()
                            .satisfies(a -> assertThat(a.getEnabled()).isFalse());
                    assertThat(positionRows.query(Criteria.of(), new PageRequest(0, 100)))
                            .hasSize(3)
                            .anySatisfy(p -> assertThat(p.getSource()).isEqualTo("manual"));
                    assertThat(
                                    positionRows.query(
                                            Criteria.of().eq("enabled", false),
                                            new PageRequest(0, 100)))
                            .hasSize(1);
                    assertThat(grantRows.query(Criteria.of(), new PageRequest(0, 100)))
                            .singleElement()
                            .satisfies(grant -> assertThat(grant.getEnabled()).isFalse());
                    return null;
                });
        assertThat(syncRuns.query(Criteria.of().eq("jobRunId", second), new PageRequest(0, 1)))
                .singleElement()
                .satisfies(
                        r -> {
                            assertThat(r.getCompleted()).isTrue();
                            assertThat(r.getStatus()).isEqualTo("SUCCEEDED");
                        });
    }

    @Test
    void completeMissingRetiresSourceAndSessionsAndRepeatedFinalizationIsIdempotent() {
        enqueue(false);
        execute();
        String session = sessionFor("tenant-a");
        setPeople(List.of(), List.of());
        String id = enqueue(false);
        execute();
        tenant("tenant-a", () -> {
            assertThat(identityRows.query(Criteria.of(), new PageRequest(0, 10))).singleElement()
                    .satisfies(b -> assertThat(b.getSourceStatus()).isEqualTo("missing"));
            assertThat(sessionRows.findById(session).getRevokedAt()).isNotNull();
            assertThat(positionRows.query(Criteria.of(), new PageRequest(0, 10)))
                    .allSatisfy(p -> {
                        assertThat(p.getSourceStatus()).isEqualTo("missing");
                        assertThat(p.getEnabled()).isTrue();
                    });
            assertThat(grantRows.query(Criteria.of(), new PageRequest(0, 10))).isEmpty();
            assertThat(accountRows.query(Criteria.of(), new PageRequest(0, 10)).getFirst().getEnabled()).isTrue();
            assertThat(employeeRows.query(Criteria.of(), new PageRequest(0, 10)).getFirst().getEnabled()).isTrue();
            return null;
        });
        int version = global(() -> sessionRows.findById(session).getVersion());
        // Replay the already committed finalization phase under a new valid job lease.
        global(() -> {
            var run = syncRuns.query(Criteria.of().eq("jobRunId", id), new PageRequest(0, 1)).getFirst();
            var cursor = DirectorySyncStore.decode(run.getCursor(), DirectorySyncJob.Cursor.class);
            cursor.phase = "FINALIZING";
            cursor.bindingRow = null;
            run.setCursor(DirectorySyncStore.encode(cursor));
            DirectorySyncStore.save(syncRuns, run);
            db.execute("update platform_job_run set status='FAILED' where id=?", id);
            jobs.retry(id);
            return null;
        });
        execute();
        assertThat(global(() -> sessionRows.findById(session).getVersion())).isEqualTo(version);
    }

    @Test
    void unknownObservationsPreserveExistingIdentityEmploymentAndSessionsAndNeverProvision() {
        enqueue(false);
        execute();
        String session = sessionFor("tenant-a");
        var binding = tenant("tenant-a", () -> identityRows.query(Criteria.of(), new PageRequest(0, 10)).getFirst());
        var employment = tenant("tenant-a", () -> positionRows.query(Criteria.of(), new PageRequest(0, 10)));
        var employee = tenant("tenant-a", () -> employeeRows.query(Criteria.of(), new PageRequest(0, 10)).getFirst());
        // A different primary and an unbound person must not authorize any placement or account changes.
        setPeople(List.of(person("b", List.of("a1", "b"), "unknown"),
                new ExternalDirectoryProtocol.Person("new", "New", "002", "a1", List.of("a1"), "unknown", null, null)), List.of());
        for (boolean preview : List.of(true, false, false)) {
            String id = enqueue(preview);
            execute();
            assertThat(jobRuns.findById(id).getResultCode()).isEqualTo("DIRECTORY_UNKNOWN_STATUS");
            tenant("tenant-a", () -> {
                var run = syncRuns.query(Criteria.of().eq("jobRunId", id), new PageRequest(0, 1)).getFirst();
                var stats = DirectorySyncStore.decode(run.getStatistics(), DirectorySyncJob.Statistics.class);
                assertThat(stats.unknownStatus).isEqualTo(2);
                assertThat(stats.duplicates).isGreaterThan(0);
                assertThat(stats.conflicts).isZero();
                assertThat(run.getCompleted()).isTrue();
                assertThat(identityRows.query(Criteria.of(), new PageRequest(0, 10))).singleElement().satisfies(current -> {
                    assertThat(current.getVersion()).isEqualTo(binding.getVersion());
                    assertThat(current.getSourceStatus()).isEqualTo("active");
                    assertThat(current.getSourceCheckedAt()).isEqualTo(binding.getSourceCheckedAt());
                });
                assertThat(accountRows.query(Criteria.of(), new PageRequest(0, 10))).hasSize(1);
                assertThat(employeeRows.findById(employee.getId()).getVersion()).isEqualTo(employee.getVersion());
                assertThat(positionRows.query(Criteria.of(), new PageRequest(0, 10)))
                        .extracting(EmployeePosition::getId, EmployeePosition::getVersion, EmployeePosition::getSourceStatus)
                        .containsExactlyInAnyOrderElementsOf(employment.stream()
                                .map(p -> tuple(p.getId(), p.getVersion(), p.getSourceStatus())).toList());
                assertThat(sessionRows.findById(session).getRevokedAt()).isNull();
                assertThat(grantRows.query(Criteria.of(), new PageRequest(0, 10))).isNotEmpty();
                return null;
            });
        }
    }

    @Test
    void directoryLimitFailureIsVisibleAndCannotReclaimMissingPeople() {
        enqueue(false);
        execute();
        String session = sessionFor("tenant-a");
        departmentHook = () -> { throw new ExternalDirectoryProtocol.ReadLimitExceededException(); };
        String id = enqueue(false);
        execute();
        assertThat(jobRuns.findById(id).getResultCode()).isEqualTo("DIRECTORY_PARTIAL");
        assertThat(departmentReads).hasValue(4); // Two successful roots, then one permanent failure per root.
        tenant("tenant-a", () -> {
            var run = syncRuns.query(Criteria.of().eq("jobRunId", id), new PageRequest(0, 1)).getFirst();
            assertThat(run.getCompleted()).isFalse();
            assertThat(run.getFailedPages()).contains("departments-limit-select-narrower-roots");
            assertThat(identityRows.query(Criteria.of(), new PageRequest(0, 10)).getFirst().getSourceStatus()).isEqualTo("active");
            assertThat(sessionRows.findById(session).getRevokedAt()).isNull();
            return null;
        });
    }

    @Test
    void failedPageAndPreviewCannotInferMissing() {
        enqueue(false);
        execute();
        String session = sessionFor("tenant-a");
        setPeople(List.of(), List.of("people:a1:0"));
        String partial = enqueue(false);
        execute();
        assertThat(jobRuns.findById(partial).getResultCode()).isEqualTo("DIRECTORY_PARTIAL");
        setPeople(List.of(), List.of());
        enqueue(true);
        execute();
        tenant("tenant-a", () -> {
            assertThat(identityRows.query(Criteria.of(), new PageRequest(0, 10)).getFirst().getSourceStatus()).isEqualTo("active");
            assertThat(sessionRows.findById(session).getRevokedAt()).isNull();
            return null;
        });
    }

    @Test
    void departureRevokesOnlyThisTenantsSessions() {
        var other = tenant("tenant-b", () -> seedPolicy("tenant-b"));
        enqueue(false);
        tenant("tenant-b", () -> policies.synchronize(other.getId(), false));
        execute();
        execute();
        String a = sessionFor("tenant-a");
        String b = sessionFor("tenant-b");
        setPeople(List.of(person("a1", List.of("a1", "b"), "departed")), List.of());
        enqueue(false);
        execute();
        assertThat(global(() -> sessionRows.findById(a).getRevokedAt())).isNotNull();
        assertThat(global(() -> sessionRows.findById(b).getRevokedAt())).isNull();
        assertThat(global(() -> accountRows.query(Criteria.of(), new PageRequest(0, 10))))
                .allSatisfy(account -> assertThat(account.getEnabled()).isTrue());
    }

    @Test
    void primaryMoveRetiresOldDerivedGrantsButPreservesManualPositionsAndGrants() {
        enqueue(false);
        execute();
        String[] retained = tenant("tenant-a", () -> {
            var old = positionRows.query(Criteria.of().eq("primaryPosition", true), new PageRequest(0, 1)).getFirst();
            var manual = new EmployeePosition();
            manual.setEmployeeId(old.getEmployeeId());
            manual.setOrganizationId(old.getOrganizationId());
            manual.setDepartmentId(old.getDepartmentId());
            manual.setPositionId(old.getPositionId());
            positions.insert(manual);
            for (String position : List.of(old.getId(), manual.getId())) {
                var grant = new EmploymentRoleGrant();
                grant.setTenantId("tenant-a");
                grant.setRoleId(policy.getSyncRoleId());
                grant.setEmployeePositionId(position);
                DirectorySyncStore.insert(grantRows, grant);
            }
            return new String[]{old.getId(), manual.getId()};
        });
        setPeople(List.of(person("b", List.of("b"), "active")), List.of());
        String move = enqueue(false);
        execute();
        assertThat(jobRuns.findById(move).getResultCode()).isEqualTo("DIRECTORY_COMPLETED");
        tenant("tenant-a", () -> {
            assertThat(positionRows.findById(retained[0]).getSourceStatus()).isEqualTo("retired");
            assertThat(positionRows.findById(retained[0]).getEnabled()).isTrue();
            assertThat(positionRows.findById(retained[1]).getSource()).isEqualTo("manual");
            var grants = grantRows.query(Criteria.of(), new PageRequest(0, 10));
            assertThat(grants).filteredOn(g -> "manual".equals(g.getSource())).hasSize(2);
            assertThat(grants).filteredOn(g -> "directory".equals(g.getSource())).singleElement()
                    .satisfies(g -> assertThat(g.getEmployeePositionId()).isNotIn((Object[]) retained));
            assertThat(positionRows.query(Criteria.of().eq("primaryPosition", true), new PageRequest(0, 10)))
                    .singleElement().satisfies(p -> assertThat(p.getExternalDepartmentId()).isEqualTo("b"));
            return null;
        });
    }

    @Test
    void manualPrimarySelectionRemainsAnExplicitConflict() {
        enqueue(false);
        execute();
        tenant("tenant-a", () -> {
            var old = positionRows.query(Criteria.of().eq("primaryPosition", true), new PageRequest(0, 1)).getFirst();
            old.setPrimaryPosition(false);
            positions.update(old);
            var manual = new EmployeePosition();
            manual.setEmployeeId(old.getEmployeeId());
            manual.setOrganizationId(old.getOrganizationId());
            manual.setDepartmentId(old.getDepartmentId());
            manual.setPositionId(old.getPositionId());
            manual.setPrimaryPosition(true);
            positions.insert(manual);
            return null;
        });
        String id = enqueue(false);
        execute();
        assertThat(jobRuns.findById(id).getResultCode()).isEqualTo("DIRECTORY_PARTIAL");
        tenant("tenant-a", () -> {
            assertThat(positionRows.query(Criteria.of().eq("primaryPosition", true), new PageRequest(0, 10)))
                    .singleElement().satisfies(p -> assertThat(p.getSource()).isEqualTo("manual"));
            return null;
        });
    }

    @Test
    void missingRetirementTraversesMultiplePositionBatches() {
        enqueue(false);
        execute();
        tenant("tenant-a", () -> {
            var original = positionRows.query(Criteria.of(), new PageRequest(0, 1)).getFirst();
            for (int i = 0; i < 205; i++) {
                var position = new EmployeePosition();
                position.setTenantId("tenant-a");
                position.setEmployeeId(original.getEmployeeId());
                position.setOrganizationId(original.getOrganizationId());
                position.setDepartmentId(original.getDepartmentId());
                position.setPositionId("historical-position-" + i);
                position.setSource("directory");
                position.setSourceProviderId(provider.getId());
                position.setSourceStatus("active");
                DirectorySyncStore.insert(positionRows, position);
            }
            return null;
        });
        setPeople(List.of(), List.of());
        String id = enqueue(false);
        execute();
        assertThat(jobRuns.findById(id).getResultCode()).isEqualTo("DIRECTORY_COMPLETED");
        tenant("tenant-a", () -> {
            assertThat(positionRows.query(Criteria.of(), new PageRequest(0, 300))).hasSize(207)
                    .allSatisfy(p -> assertThat(p.getSourceStatus()).isEqualTo("missing"));
            return null;
        });
    }

    @Test
    void deletingBindingBetweenFinalizationBatchesDoesNotReuseItsPositionCursor() {
        setPeople(List.of(person("a1", List.of("a1"), "active"),
                new ExternalDirectoryProtocol.Person("bob", "Bob", "002", "a1", List.of("a1"), "active", null, null)), List.of());
        enqueue(false);
        execute();
        tenant("tenant-a", () -> {
            var bindings = identityRows.query(Criteria.of(), new PageRequest(0, 10), Sort.asc("id"));
            for (int i = 0; i < 2; i++) {
                var link = employeeAccountRows.query(Criteria.of().eq("userId", bindings.get(i).getUserId()),
                        new PageRequest(0, 1)).getFirst();
                var position = positionRows.query(Criteria.of().eq("employeeId", link.getEmployeeId()),
                        new PageRequest(0, 1)).getFirst();
                var extra = new EmployeePosition();
                extra.setId((i == 0 ? "z" : "a").repeat(32));
                extra.setTenantId("tenant-a");
                extra.setEmployeeId(link.getEmployeeId());
                extra.setOrganizationId(position.getOrganizationId());
                extra.setDepartmentId(position.getDepartmentId());
                extra.setPositionId("history");
                extra.setSource("directory");
                extra.setSourceProviderId(provider.getId());
                extra.setSourceStatus("active");
                DirectorySyncStore.insert(positionRows, extra);
            }
            var run = new DirectorySyncRun();
            run.setId("missing-round");
            run.setTenantId("tenant-a");
            var cursor = new DirectorySyncJob.Cursor();
            cursor.phase = "FINALIZING";
            var scope = scopes.findById(policy.getSyncScopeId());
            var tx = new TransactionTemplate(transactions);
            tx.executeWithoutResult(status -> writer.finalizeMissing(run, scope, cursor));
            assertThat(cursor.bindingPositionRow).isEqualTo("z".repeat(32));
            // The administrator's delete commits between two separately fenced finalization batches.
            identityRows.deleteById(bindings.getFirst().getId());
            while (!"FINISHED".equals(cursor.phase))
                tx.executeWithoutResult(status -> writer.finalizeMissing(run, scope, cursor));
            assertThat(positionRows.findById("a".repeat(32)).getSourceStatus()).isEqualTo("missing");
            return null;
        });
    }

    private String sessionFor(String tenantId) {
        return tenant(tenantId, () -> {
            var account = accountRows.query(Criteria.of(), new PageRequest(0, 1)).getFirst();
            var session = new UserSession();
            session.setTenantId(tenantId);
            session.setUserId(account.getId());
            session.setUsername(account.getUsername());
            session.setTokenHash(UUID.randomUUID().toString());
            session.setIssuedAt(Instant.now());
            session.setExpiresAt(Instant.now().plusSeconds(3600));
            session.setMaxExpiresAt(session.getExpiresAt());
            DirectorySyncStore.insert(sessionRows, session);
            return session.getId();
        });
    }

    private static ExternalDirectoryProtocol.Person person(String primary, List<String> departments, String status) {
        return new ExternalDirectoryProtocol.Person("alice", "Alice", "001", primary, departments, status, null, null);
    }

    private void setPeople(List<ExternalDirectoryProtocol.Person> people, List<String> failures) {
        global(() -> {
            var base = DirectorySyncStore.decode(fixture(List.of()), MockExternalDirectoryProtocol.Data.class);
            provider.setConfiguration(DirectorySyncStore.encode(Map.of("directory", DirectorySyncStore.encode(
                    new MockExternalDirectoryProtocol.Data(base.departments(), people, failures)))));
            DirectorySyncStore.save(providerRows, provider);
            return null;
        });
    }

    private void singleDepartmentView() {
        global(() -> {
            var config = new java.util.LinkedHashMap<String, String>(DirectorySyncStore.decode(provider.getConfiguration(), Map.class));
            config.put("directorySingleDepartmentView", "true");
            config.put("directoryPersonLookup", "false");
            provider.setConfiguration(DirectorySyncStore.encode(config));
            DirectorySyncStore.save(providerRows, provider);
            return null;
        });
    }

    private String primaryDepartment() {
        return tenant("tenant-a", () -> positionRows.query(Criteria.of().eq("primaryPosition", true),
                new PageRequest(0, 10)).getFirst().getExternalDepartmentId());
    }

    @Test
    void singleDepartmentObservationsAggregateAndInferStablePrimaryWithoutDetailLookup() {
        singleDepartmentView();
        String first = enqueue(false);
        execute();
        assertThat(jobRuns.findById(first).getResultCode()).isEqualTo("DIRECTORY_COMPLETED");
        String primary = primaryDepartment();
        tenant("tenant-a", () -> {
            assertThat(employeeRows.query(Criteria.of(), new PageRequest(0, 10))).hasSize(1);
            assertThat(positionRows.query(Criteria.of(), new PageRequest(0, 10)))
                    .hasSize(2).filteredOn(p -> Boolean.TRUE.equals(p.getPrimaryPosition()))
                    .singleElement().satisfies(p -> assertThat(p.getPrimaryDepartmentSource()).isEqualTo("inferred"));
            var fact = seen.query(Criteria.of(), new PageRequest(0, 10)).getFirst();
            var memberships = DirectorySyncStore.decode(fact.getDepartmentIds(), String[].class);
            assertThat(memberships).containsExactlyInAnyOrder("a1", "b");
            assertThat(primary).isEqualTo(memberships[0]);
            return null;
        });
        enqueue(false);
        execute();
        assertThat(primaryDepartment()).isEqualTo(primary);
        // Explicit vendor primary overrides inference, then remains stable without a vendor primary.
        String other = primary.equals("b") ? "a1" : "b";
        setPeople(List.of(person(other, List.of("a1", "b"), "active")), List.of());
        enqueue(false);
        execute();
        assertThat(primaryDepartment()).isEqualTo(other);
        tenant("tenant-a", () -> {
            assertThat(positionRows.query(Criteria.of().eq("primaryPosition", true), new PageRequest(0, 10)))
                    .singleElement().satisfies(p -> assertThat(p.getPrimaryDepartmentSource()).isEqualTo("vendor"));
            return null;
        });
        singleDepartmentView();
        enqueue(true);
        execute();
        tenant("tenant-a", () -> {
            assertThat(seen.query(Criteria.of().eq("primaryDepartmentSource", "inferred"), new PageRequest(0, 10)))
                    .allSatisfy(p -> assertThat(p.getPrimaryDepartmentId()).isIn(primary, other));
            return null;
        });
        enqueue(false);
        execute();
        assertThat(primaryDepartment()).isEqualTo(other);
        // Removing the old primary selects the remaining observation and retires the old placement.
        setPeople(List.of(person(null, List.of(primary), "active")), List.of());
        singleDepartmentView();
        enqueue(false);
        execute();
        assertThat(primaryDepartment()).isEqualTo(primary);
        tenant("tenant-a", () -> {
            assertThat(positionRows.query(Criteria.of().eq("externalDepartmentId", other), new PageRequest(0, 10)))
                    .singleElement().satisfies(p -> assertThat(p.getSourceStatus()).isEqualTo("retired"));
            return null;
        });
    }

    @Test
    void takeoverDuringObservationCollectionRetainsAllMembershipsAndWritesOnce() {
        singleDepartmentView();
        String id = enqueue(false);
        var calls = new AtomicInteger();
        peopleHook = () -> {
            if (calls.incrementAndGet() == 3) throw new AssertionError("process lost after first membership committed");
        };
        assertThatThrownBy(this::execute).isInstanceOf(AssertionError.class);
        tenant("tenant-a", () -> {
            assertThat(seen.query(Criteria.of(), new PageRequest(0, 10))).hasSize(1);
            assertThat(employeeRows.query(Criteria.of(), new PageRequest(0, 10))).isEmpty();
            return null;
        });
        peopleHook = () -> {};
        global(() -> {
            db.execute("update platform_job_run set lease_until=clock_timestamp()-interval '1 second' where id=?", id);
            return null;
        });
        execute();
        assertThat(jobRuns.findById(id).getAttempts()).isEqualTo(2);
        assertThat(jobRuns.findById(id).getResultCode()).isEqualTo("DIRECTORY_COMPLETED");
        tenant("tenant-a", () -> {
            assertThat(employeeRows.query(Criteria.of(), new PageRequest(0, 10))).hasSize(1);
            assertThat(positionRows.query(Criteria.of(), new PageRequest(0, 10)))
                    .extracting(EmployeePosition::getExternalDepartmentId).containsExactlyInAnyOrder("a1", "b");
            assertThat(positionRows.query(Criteria.of().eq("primaryPosition", true), new PageRequest(0, 10))).hasSize(1);
            return null;
        });
    }

    @Test
    void failedObservationPageCannotRetireUnobservedSecondaryEmployment() {
        singleDepartmentView();
        enqueue(false);
        execute();
        String primary = primaryDepartment();
        setPeople(List.of(person(null, List.of("a1", "b"), "active")), List.of("people:b:0"));
        singleDepartmentView();
        String partial = enqueue(false);
        execute();
        assertThat(jobRuns.findById(partial).getResultCode()).isEqualTo("DIRECTORY_PARTIAL");
        assertThat(primaryDepartment()).isEqualTo(primary);
        tenant("tenant-a", () -> {
            assertThat(positionRows.query(Criteria.of(), new PageRequest(0, 10))).hasSize(2)
                    .allSatisfy(p -> assertThat(p.getSourceStatus()).isEqualTo("active"));
            return null;
        });
    }

    @Test
    void trustedInactiveObservationStillRevokesSourceWhenAnotherDepartmentPageFails() {
        singleDepartmentView();
        enqueue(false);
        execute();
        String session = sessionFor("tenant-a");
        setPeople(List.of(person(null, List.of("a1", "b"), "departed")), List.of("people:b:0"));
        singleDepartmentView();
        String partial = enqueue(false);
        execute();
        assertThat(jobRuns.findById(partial).getResultCode()).isEqualTo("DIRECTORY_PARTIAL");
        tenant("tenant-a", () -> {
            assertThat(identityRows.query(Criteria.of(), new PageRequest(0, 10)))
                    .singleElement().satisfies(p -> assertThat(p.getSourceStatus()).isEqualTo("departed"));
            assertThat(positionRows.query(Criteria.of(), new PageRequest(0, 10)))
                    .allSatisfy(p -> assertThat(p.getSourceStatus()).isEqualTo("departed"));
            assertThat(sessionRows.findById(session).getRevokedAt()).isNotNull();
            return null;
        });
    }

    @Test
    void takeoverDuringPersonWritesResumesKeysetWithoutRereadingOrDuplicatingPeople() {
        setPeople(List.of(person(null, List.of("a1", "b"), "active"),
                new ExternalDirectoryProtocol.Person("bob", "Bob", "002", null, List.of("a1", "b"), "active", null, null)), List.of());
        singleDepartmentView();
        var writes = new AtomicInteger();
        doAnswer(call -> {
            if (writes.incrementAndGet() == 2) throw new AssertionError("process lost after one person committed");
            return call.callRealMethod();
        }).when(writer).person(any(), any(), any(), any(), anyString());
        String id = enqueue(false);
        assertThatThrownBy(this::execute).isInstanceOf(AssertionError.class);
        tenant("tenant-a", () -> {
            assertThat(seen.query(Criteria.of(), new PageRequest(0, 10))).hasSize(2);
            assertThat(employeeRows.query(Criteria.of(), new PageRequest(0, 10))).hasSize(1);
            var run = syncRuns.query(Criteria.of().eq("jobRunId", id), new PageRequest(0, 1)).getFirst();
            var cursor = DirectorySyncStore.decode(run.getCursor(), DirectorySyncJob.Cursor.class);
            assertThat(cursor.phase).isEqualTo("WRITING_PEOPLE");
            assertThat(cursor.personRow).isNotNull();
            return null;
        });
        peopleHook = () -> { throw new AssertionError("must resume writing without vendor reads"); };
        doCallRealMethod().when(writer).person(any(), any(), any(), any(), anyString());
        global(() -> {
            db.execute("update platform_job_run set lease_until=clock_timestamp()-interval '1 second' where id=?", id);
            return null;
        });
        execute();
        assertThat(jobRuns.findById(id).getResultCode()).isEqualTo("DIRECTORY_COMPLETED");
        tenant("tenant-a", () -> {
            assertThat(employeeRows.query(Criteria.of(), new PageRequest(0, 10))).hasSize(2);
            assertThat(accountRows.query(Criteria.of(), new PageRequest(0, 10))).hasSize(2);
            assertThat(positionRows.query(Criteria.of(), new PageRequest(0, 10))).hasSize(4);
            assertThat(positionRows.query(Criteria.of().eq("primaryPosition", true), new PageRequest(0, 10))).hasSize(2);
            return null;
        });
    }

    @Test
    void repeatedActionsDeduplicateByModeAndScopeUntilTerminal() {
        String sync = enqueue(false);
        assertThat(enqueue(false)).isEqualTo(sync);
        String preview = enqueue(true);
        assertThat(preview).isNotEqualTo(sync);
        assertThat(enqueue(true)).isEqualTo(preview);
        tenant(
                "tenant-a",
                () -> {
                    policy.setSyncRoleId(null);
                    policies.update(policy);
                    return null;
                });
        String revised = enqueue(false);
        assertThat(revised).isNotEqualTo(sync);
        execute();
        execute();
        execute();
        assertThat(enqueue(false)).isNotEqualTo(revised);
    }

    @Test
    void scheduleUsesConfiguredOrPlatformDefaultZoneAndRejectsInvalidZone() {
        tenant(
                "tenant-a",
                () -> {
                    policy.setSyncZoneId("Asia/Shanghai");
                    policies.update(policy);
                    return null;
                });
        assertThat(jobPlans.findById(policy.getSyncPlanId()).getZoneId())
                .isEqualTo("Asia/Shanghai");
        tenant(
                "tenant-a",
                () -> {
                    policy.setSyncZoneId(" ");
                    policies.update(policy);
                    return null;
                });
        assertThat(jobPlans.findById(policy.getSyncPlanId()).getZoneId())
                .isEqualTo(PlatformAbilityRuntime.timeService().resolveZoneId(null).getId());
        tenant(
                "tenant-a",
                () -> {
                    policy.setSyncEnabled(false);
                    policy.setSyncZoneId("invalid/zone");
                    assertThatThrownBy(() -> policies.update(policy))
                            .isInstanceOf(DateTimeException.class);
                    return null;
                });
        assertThat(jobPlans.findById(policy.getSyncPlanId()).getEnabled()).isTrue();
    }

    @Test
    void previewWritesOnlyTopicFactsAndFailurePagesArePartial() {
        String preview = enqueue(true);
        execute();
        assertThat(jobRuns.findById(preview).getStatus()).isEqualTo("SUCCEEDED");
        assertThat(
                        global(
                                () ->
                                        db.query(
                                                "select id from iam_user union all select id from iam_employee union all select id from iam_department union all select id from iam_external_identity")))
                .isEmpty();
        var previewFact =
                syncRuns.query(Criteria.of().eq("jobRunId", preview), new PageRequest(0, 1))
                        .getFirst();
        assertThat(
                        DirectorySyncStore.decode(
                                        previewFact.getStatistics(),
                                        DirectorySyncJob.Statistics.class)
                                .people)
                .isEqualTo(1);
        global(
                () -> {
                    provider.setConfiguration(
                            DirectorySyncStore.encode(
                                    Map.of("directory", fixture(List.of("people:b:0")))));
                    DirectorySyncStore.save(providerRows, provider);
                    return null;
                });
        String partial = enqueue(false);
        execute();
        assertThat(jobRuns.findById(partial).getStatus()).isEqualTo("PARTIAL");
        assertThat(jobRuns.findById(partial).getResultCode()).isEqualTo("DIRECTORY_PARTIAL");
        assertThat(syncRuns.query(Criteria.of().eq("jobRunId", partial), new PageRequest(0, 1)))
                .singleElement()
                .satisfies(
                        r -> {
                            assertThat(r.getCompleted()).isFalse();
                            assertThat(r.getFailedPages()).contains("people:b:0");
                        });
    }

    @Test
    void managedScheduleTracksPolicyAndExpiryCleanupIsBoundedAcrossTenants() {
        String planId = policy.getSyncPlanId();
        global(
                () -> {
                    jobs.pause(planId);
                    return null;
                });
        assertThat(policyRows.findById(policy.getId()).getSyncEnabled()).isFalse();
        global(
                () -> {
                    jobs.resume(planId);
                    return null;
                });
        assertThat(policyRows.findById(policy.getId()).getSyncEnabled()).isTrue();
        policy = policyRows.findById(policy.getId());
        assertThat(jobPlans.findById(planId).getManagedBy())
                .isEqualTo(TenantIdentityPolicyService.MODULE_ALIAS);
        tenant(
                "tenant-a",
                () -> {
                    policy.setSyncEnabled(false);
                    policies.update(policy);
                    return null;
                });
        assertThat(jobPlans.findById(planId)).isNull();
        tenant(
                "tenant-a",
                () -> {
                    policy.setSyncEnabled(true);
                    policies.update(policy);
                    return null;
                });
        assertThat(jobPlans.findById(policy.getSyncPlanId())).isNotNull();
        tenant(
                "tenant-a",
                () -> {
                    policies.delete(policy.getId());
                    return null;
                });
        assertThat(jobPlans.findById(policy.getSyncPlanId())).isNull();
        for (String tenant : List.of("tenant-a", "tenant-b"))
            tenant(
                    tenant,
                    () -> {
                        for (int i = 0; i < 2; i++) {
                            var row = new ExternalLoginTransaction();
                            row.setStateHash(tenant + i);
                            row.setBrowserProofHash("hash");
                            row.setPurpose(ExternalLoginPurpose.LOGIN);
                            row.setProviderId("mock");
                            row.setStatus(ExternalLoginStatus.PENDING);
                            row.setExpiresAt(Instant.now().plusSeconds(i == 0 ? -60 : 600));
                            DirectorySyncStore.insert(loginTransactions, row);
                        }
                        return null;
                    });
        String run =
                global(
                        () ->
                                jobs.enqueue(
                                                ExternalLoginTransactionRetentionJob.TYPE,
                                                null,
                                                Map.of(),
                                                null)
                                        .getId());
        jobs.runOne("default", "retention");
        assertThat(jobRuns.findById(run).getResultCode()).isEqualTo("LOGIN_TRANSACTIONS_CLEANED");
        assertThat(global(() -> loginTransactions.query(Criteria.of(), new PageRequest(0, 100))))
                .hasSize(2);
    }

    @Test
    void topicRetentionKeepsLatestCompleteSyncAndLaterRoundsPerTenantAndProvider() {
        String old = enqueue(false);
        execute();
        String anchor = enqueue(false);
        execute();
        String preview = enqueue(true);
        execute();
        var other = tenant("tenant-b", () -> seedPolicy("tenant-b"));
        String otherAnchor = tenant("tenant-b", () -> policies.synchronize(other.getId(), false));
        execute();
        global(
                () -> {
                    // A separate provider has no complete round: its old preview can expire.
                    var scope = new DirectorySyncScope();
                    scope.setTenantId("tenant-a");
                    scope.setProviderId("other-provider");
                    scope.setPolicyId("deleted-policy");
                    scope.setRoots("[]");
                    scope.setPositionId("position");
                    DirectorySyncStore.insert(scopes, scope);
                    var row = new DirectorySyncRun();
                    row.setTenantId("tenant-a");
                    row.setJobRunId("old-preview");
                    row.setPolicyId(scope.getPolicyId());
                    row.setCursor("{}");
                    row.setStatistics("{}");
                    row.setScopeId(scope.getId());
                    row.setMode("PREVIEW");
                    row.setStatus("SUCCEEDED");
                    row.setCompleted(true);
                    DirectorySyncStore.insert(syncRuns, row);
                    db.execute(
                            "update iam_directory_sync_run set updated_at=clock_timestamp()-interval '31 days'");
                    return null;
                });
        cleanTopic();
        assertThat(syncRuns.query(Criteria.of(), new PageRequest(0, 100)))
                .extracting(DirectorySyncRun::getJobRunId)
                .containsExactlyInAnyOrder(anchor, preview, otherAnchor);
        assertThat(syncRuns.query(Criteria.of().eq("jobRunId", old), new PageRequest(0, 1)))
                .isEmpty();
        assertThat(
                        global(
                                () ->
                                        db.query(
                                                "select id from iam_directory_sync_seen where run_id not in (select id from iam_directory_sync_run)")))
                .isEmpty();
        assertThat(
                        global(
                                () ->
                                        db.query(
                                                "select id from iam_directory_sync_department where run_id not in (select id from iam_directory_sync_run)")))
                .isEmpty();
    }

    @Test
    void retentionUsesCompletionOrderWhenAnOlderRoundFinishesAfterANewerRound() {
        String expired = enqueue(true);
        execute();
        String resumed = enqueue(false);
        execute();
        String newer = enqueue(false);
        execute();
        global(
                () -> {
                    // A manually resumed older round can complete after a newer full round.
                    db.execute(
                            "update iam_directory_sync_run set updated_at=clock_timestamp()-interval '40 days' where job_run_id=?",
                            expired);
                    db.execute(
                            "update iam_directory_sync_run set updated_at=clock_timestamp()-interval '32 days' where job_run_id=?",
                            newer);
                    db.execute(
                            "update iam_directory_sync_run set updated_at=clock_timestamp()-interval '31 days' where job_run_id=?",
                            resumed);
                    return null;
                });
        cleanTopic();
        assertThat(syncRuns.query(Criteria.of(), new PageRequest(0, 100)))
                .extracting(DirectorySyncRun::getJobRunId)
                .containsExactlyInAnyOrder(resumed, newer);
    }

    @Test
    void topicRetentionIsBoundedAndProtectsActiveAndQueuedContinuation() {
        String original = enqueue(false);
        peopleHook =
                () -> {
                    throw new AssertionError("simulated process loss");
                };
        assertThatThrownBy(this::execute).isInstanceOf(AssertionError.class);
        global(
                () -> {
                    db.execute(
                            "update iam_directory_sync_run set updated_at=clock_timestamp()-interval '31 days'");
                    return null;
                });
        cleanTopic();
        assertThat(syncRuns.query(Criteria.of(), new PageRequest(0, 100))).hasSize(1);
        global(
                () -> {
                    db.execute(
                            "update platform_job_run set started_at=clock_timestamp()-interval '2 hours' where id=?",
                            original);
                    return null;
                });
        jobs.renewNode("node");
        String retry = global(() -> jobs.retry(original).getId());
        global(
                () -> {
                    db.execute(
                            "update iam_directory_sync_run set updated_at=clock_timestamp()-interval '31 days'");
                    return null;
                });
        cleanTopic();
        assertThat(syncRuns.query(Criteria.of(), new PageRequest(0, 100))).hasSize(1);
        global(
                () -> {
                    jobs.cancel(retry);
                    return null;
                });
        var fact = syncRuns.query(Criteria.of(), new PageRequest(0, 1)).getFirst();
        global(
                () -> {
                    for (int i = 0; i < 2001; i++) {
                        var row = new DirectorySyncSeen();
                        row.setTenantId("tenant-a");
                        row.setRunId(fact.getId());
                        row.setExternalUserId("retained-" + i);
                        DirectorySyncStore.insert(seen, row);
                    }
                    return null;
                });
        String cleanup = cleanTopic();
        assertThat(jobRuns.findById(cleanup).getResultCode()).isEqualTo("BATCH_LIMIT_REACHED");
        assertThat(syncRuns.findById(fact.getId()).getRetentionStarted()).isTrue();
        assertThatThrownBy(() -> global(() -> jobs.retry(original)))
                .isInstanceOf(JobFailure.class)
                .hasMessageContaining("DIRECTORY_CONTINUATION_EXPIRED");
        assertThat(
                        global(
                                () ->
                                        seen.query(
                                                Criteria.of().eq("runId", fact.getId()),
                                                new PageRequest(0, 100))))
                .hasSize(1);
        cleanTopic();
        assertThat(syncRuns.findById(fact.getId())).isNull();
    }

    private String cleanTopic() {
        String id =
                global(
                        () ->
                                jobs.enqueue(DirectorySyncRetentionJob.TYPE, null, Map.of(), null)
                                        .getId());
        assertThat(jobs.runOne("default", "topic-retention")).isTrue();
        return id;
    }

    @Test
    void independentTenantKeysAndFrozenScopeVersions() {
        var other = tenant("tenant-b", () -> seedPolicy("tenant-b"));
        String first = enqueue(false);
        String second = tenant("tenant-b", () -> policies.synchronize(other.getId(), true));
        assertThat(jobRuns.findById(first).getMutexKey())
                .isNotEqualTo(jobRuns.findById(second).getMutexKey());
        String oldScope = policy.getSyncScopeId();
        tenant(
                "tenant-a",
                () -> {
                    policy.setSyncRoleId(null);
                    policy.setSyncRoots("[]");
                    policy.setSyncScopeConfirmed(true);
                    policies.update(policy);
                    return null;
                });
        assertThat(policy.getSyncScopeId()).isEqualTo(oldScope);
        assertThat(policy.getPendingSyncScopeId()).isNotNull();
        assertThat(DirectorySyncStore.decode(jobPlans.findById(policy.getSyncPlanId()).getParametersJson(), Map.class))
                .containsEntry("scopeId", oldScope);
        assertThat(scopes.findById(oldScope)).isNotNull();
        execute();
        execute();
        assertThat(jobRuns.findById(first).getStatus()).isEqualTo("SUCCEEDED");
        assertThat(jobRuns.findById(second).getStatus()).isEqualTo("SUCCEEDED");
        String empty = enqueue(true);
        execute();
        var fact =
                syncRuns.query(Criteria.of().eq("jobRunId", empty), new PageRequest(0, 1))
                        .getFirst();
        assertThat(
                        DirectorySyncStore.decode(
                                        fact.getStatistics(), DirectorySyncJob.Statistics.class)
                                .people)
                .isZero();
        assertThat(fact.getCompleted()).isTrue();
        assertThat(fact.getMode()).isEqualTo("SCOPE_PREVIEW");
        tenant("tenant-a", () -> {
            var report = policies.scopeImpact(policy.getId());
            assertThat(((Map<?, ?>) report.get("counts")).get("departments")).isEqualTo(3);
            String cleanup = policies.confirmScope(policy.getId(), (String) report.get("scopeId"), (String) report.get("runId"));
            assertThat(jobRuns.findById(cleanup).getStatus()).isEqualTo("PENDING");
            var latest = policyRows.findById(policy.getId());
            assertThat(latest.getSyncScopeId()).isNotEqualTo(oldScope);
            assertThat(latest.getPendingSyncScopeId()).isNull();
            assertThat(latest.getSyncPlanId()).isNull();
            return null;
        });
        execute();
        tenant("tenant-a", () -> {
            assertThat(identityRows.query(Criteria.of(), new PageRequest(0, 10)))
                    .singleElement().satisfies(i -> assertThat(i.getSourceStatus()).isEqualTo("missing"));
            return null;
        });
    }

    @Test
    void scopeRemovalNeedsSuccessfulImpactReportAndKeepsOldPlanUntilConfirmation() {
        enqueue(false);
        execute();
        String oldScope = policy.getSyncScopeId();
        String organization = DirectorySyncConfiguration.roots(policy.getSyncRoots()).getFirst().organizationId();
        tenant("tenant-a", () -> {
            policy.setSyncRoots(DirectorySyncStore.encode(List.of(new DirectorySyncConfiguration.Root("b", organization))));
            policies.update(policy);
            assertThat(policy.getSyncScopeId()).isEqualTo(oldScope);
            assertThatThrownBy(() -> policies.confirmScope(policy.getId(), policy.getPendingSyncScopeId(), null))
                    .isInstanceOf(IllegalArgumentException.class);
            return null;
        });
        var pending = policy.getPendingSyncScopeId();
        String unchanged = enqueue(false);
        assertThat(DirectorySyncStore.decode(jobRuns.findById(unchanged).getParametersJson(), Map.class))
                .containsEntry("scopeId", oldScope);
        execute();
        global(() -> {
            provider.setConfiguration(DirectorySyncStore.encode(Map.of("directory", fixture(List.of("people:b:0")))));
            DirectorySyncStore.save(providerRows, provider);
            return null;
        });
        String failed = enqueue(true);
        execute();
        assertThat(jobRuns.findById(failed).getResultCode()).isEqualTo("DIRECTORY_PARTIAL");
        tenant("tenant-a", () -> {
            assertThat(policies.scopeImpact(policy.getId())).isEmpty();
            assertThatThrownBy(() -> policies.confirmScope(policy.getId(), pending, null)).isInstanceOf(IllegalArgumentException.class);
            return null;
        });
        global(() -> {
            provider.setConfiguration(DirectorySyncStore.encode(Map.of("directory", fixture(List.of()))));
            DirectorySyncStore.save(providerRows, provider);
            return null;
        });
        enqueue(true);
        execute();
        String[] obsolete = new String[1];
        tenant("tenant-a", () -> {
            var report = policies.scopeImpact(policy.getId());
            var counts = (Map<?, ?>) report.get("counts");
            assertThat(counts.get("departments")).isEqualTo(2);
            assertThat(counts.get("people")).isEqualTo(1);
            assertThat(counts.get("positions")).isEqualTo(1);
            assertThat(counts.get("grants")).isEqualTo(1);
            assertThatThrownBy(() -> policies.confirmScope(policy.getId(), "stale-proposal", (String) report.get("runId"))).isInstanceOf(IllegalArgumentException.class);
            obsolete[0] = policies.synchronize(policy.getId(), false);
            assertThatThrownBy(() -> policies.confirmScope(policy.getId(), pending, "stale-report"))
                    .isInstanceOf(IllegalArgumentException.class);
            policies.confirmScope(policy.getId(), pending, (String) report.get("runId"));
            policy = policyRows.findById(policy.getId());
            assertThat(policy.getSyncScopeId()).isEqualTo(pending);
            assertThat(policy.getPendingSyncScopeId()).isNull();
            assertThat(DirectorySyncStore.decode(jobPlans.findById(policy.getSyncPlanId()).getParametersJson(), Map.class))
                    .containsEntry("scopeId", pending);
            return null;
        });
        execute();
        assertThat(jobRuns.findById(obsolete[0]).getResultCode()).isEqualTo("DIRECTORY_SCOPE_CHANGED");
        String applied = enqueue(false);
        execute();
        assertThat(jobRuns.findById(applied).getResultCode()).isEqualTo("DIRECTORY_COMPLETED");
        tenant("tenant-a", () -> {
            assertThat(positionRows.query(Criteria.of().eq("externalDepartmentId", "a1"), new PageRequest(0, 1)))
                    .singleElement().satisfies(p -> assertThat(p.getSourceStatus()).isNotEqualTo("active"));
            assertThat(positionRows.query(Criteria.of().eq("externalDepartmentId", "b"), new PageRequest(0, 1)))
                    .singleElement().satisfies(p -> assertThat(p.getSourceStatus()).isEqualTo("active"));
            return null;
        });
    }

    @Test
    void scopeNarrowingCountsReparentedDepartmentsWithinTheSameOrganization() {
        enqueue(false);
        execute();
        String organization = DirectorySyncConfiguration.roots(policy.getSyncRoots()).getFirst().organizationId();
        tenant("tenant-a", () -> {
            policy.setSyncRoots(DirectorySyncStore.encode(List.of(
                    new DirectorySyncConfiguration.Root("a1", organization),
                    new DirectorySyncConfiguration.Root("b", organization))));
            policies.update(policy);
            return null;
        });
        enqueue(true);
        execute();
        tenant("tenant-a", () -> {
            var report = policies.scopeImpact(policy.getId());
            var counts = (Map<?, ?>) report.get("counts");
            // A leaves the range; A1 becomes a root and is detached from A in the local tree.
            assertThat(counts.get("departments")).isEqualTo(2);
            assertThat(counts.get("people")).isEqualTo(1);
            assertThat(counts.get("positions")).isEqualTo(0);
            policies.confirmScope(policy.getId(), (String) report.get("scopeId"), (String) report.get("runId"));
            return null;
        });
        execute();
        tenant("tenant-a", () -> {
            var mapping = mappings.query(Criteria.of().eq("externalDepartmentId", "a1"), new PageRequest(0, 1)).getFirst();
            assertThat(mapping.getRootId()).isEqualTo("a1");
            assertThat(departmentRows.findById(mapping.getDepartmentId()).getParentId()).isEqualTo(TreeAbility.ROOT_ID);
            return null;
        });
    }

    @Test
    void scopeRemappingReportsOnlyOwnedEmploymentAndRequiresExplicitConfirmation() {
        enqueue(false);
        execute();
        String originalOrganization = DirectorySyncConfiguration.roots(policy.getSyncRoots()).getFirst().organizationId();
        var organization = new Organization();
        organization.setTenantId("tenant-a");
        organization.setCode("second");
        organization.setTitle("Second");
        tenant("tenant-a", () -> {
            DirectorySyncStore.insert(organizationRows, organization);
            policy.setSyncRoots(DirectorySyncStore.encode(List.of(
                    new DirectorySyncConfiguration.Root("a", organization.getId()),
                    new DirectorySyncConfiguration.Root("b", organization.getId()))));
            policies.update(policy);
            return null;
        });
        enqueue(true);
        execute();
        tenant("tenant-a", () -> {
            var report = policies.scopeImpact(policy.getId());
            var counts = (Map<?, ?>) report.get("counts");
            assertThat(counts.get("departments")).isEqualTo(3);
            assertThat(counts.get("people")).isEqualTo(1);
            assertThat(counts.get("positions")).isEqualTo(2);
            assertThat(counts.get("grants")).isEqualTo(1);
            policies.confirmScope(policy.getId(), (String) report.get("scopeId"), (String) report.get("runId"));
            policy = policyRows.findById(policy.getId());
            return null;
        });
        String moved = enqueue(false);
        execute();
        assertThat(jobRuns.findById(moved).getResultCode()).isEqualTo("DIRECTORY_COMPLETED");
        tenant("tenant-a", () -> {
            assertThat(mappings.query(Criteria.of(), new PageRequest(0, 100)))
                    .allSatisfy(m -> assertThat(m.getOrganizationId()).isEqualTo(organization.getId()));
            assertThat(employeeRows.query(Criteria.of(), new PageRequest(0, 10)))
                    .singleElement().satisfies(e -> assertThat(e.getOrganizationId()).isEqualTo(organization.getId()));
            policy.setSyncRoots(DirectorySyncStore.encode(List.of(
                    new DirectorySyncConfiguration.Root("a", originalOrganization),
                    new DirectorySyncConfiguration.Root("b", originalOrganization))));
            policies.update(policy);
            return null;
        });
        enqueue(true);
        execute();
        tenant("tenant-a", () -> {
            var report = policies.scopeImpact(policy.getId());
            policies.confirmScope(policy.getId(), (String) report.get("scopeId"), (String) report.get("runId"));
            policy = policyRows.findById(policy.getId());
            return null;
        });
        String returned = enqueue(false);
        execute();
        assertThat(jobRuns.findById(returned).getResultCode()).isEqualTo("DIRECTORY_COMPLETED");
        String repeated = enqueue(false);
        execute();
        assertThat(jobRuns.findById(repeated).getResultCode()).isEqualTo("DIRECTORY_COMPLETED");
        tenant("tenant-a", () -> {
            assertThat(mappings.query(Criteria.of(), new PageRequest(0, 100)))
                    .allSatisfy(m -> assertThat(m.getOrganizationId()).isEqualTo(originalOrganization));
            assertThat(employeeRows.query(Criteria.of(), new PageRequest(0, 10)))
                    .singleElement().satisfies(e -> assertThat(e.getOrganizationId()).isEqualTo(originalOrganization));
            return null;
        });
    }

    @Test
    void scopeAdditionReportsReclaimedPrimaryGrantEvenWhenOldEmploymentRemains() {
        String originalRoots = policy.getSyncRoots();
        String organization = DirectorySyncConfiguration.roots(originalRoots).getFirst().organizationId();
        tenant("tenant-a", () -> {
            policy.setSyncRoots(DirectorySyncStore.encode(List.of(new DirectorySyncConfiguration.Root("b", organization))));
            policies.update(policy);
            return null;
        });
        enqueue(true);
        execute();
        tenant("tenant-a", () -> {
            var report = policies.scopeImpact(policy.getId());
            policies.confirmScope(policy.getId(), (String) report.get("scopeId"), (String) report.get("runId"));
            policy = policyRows.findById(policy.getId());
            return null;
        });
        execute(); // Confirmation queued the initial b-only synchronization.
        String oldPosition = tenant("tenant-a", () -> positionRows.query(Criteria.of(), new PageRequest(0, 10)).getFirst().getId());
        tenant("tenant-a", () -> {
            policy.setSyncRoots(originalRoots);
            policies.update(policy);
            return null;
        });
        enqueue(true);
        execute();
        tenant("tenant-a", () -> {
            var report = policies.scopeImpact(policy.getId());
            var counts = (Map<?, ?>) report.get("counts");
            assertThat(counts.get("departments")).isEqualTo(2);
            assertThat(counts.get("people")).isEqualTo(1);
            assertThat(counts.get("positions")).isEqualTo(0);
            assertThat(counts.get("grants")).isEqualTo(1);
            policies.confirmScope(policy.getId(), (String) report.get("scopeId"), (String) report.get("runId"));
            return null;
        });
        execute();
        tenant("tenant-a", () -> {
            assertThat(positionRows.findById(oldPosition).getSourceStatus()).isEqualTo("active");
            assertThat(positionRows.findById(oldPosition).getPrimaryPosition()).isFalse();
            assertThat(grantRows.query(Criteria.of().eq("employeePositionId", oldPosition), new PageRequest(0, 10))).isEmpty();
            assertThat(grantRows.query(Criteria.of(), new PageRequest(0, 10))).singleElement().satisfies(g ->
                    assertThat(positionRows.findById(g.getEmployeePositionId()).getExternalDepartmentId()).isEqualTo("a1"));
            return null;
        });
    }

    @Test
    void authorityChangeDuringProviderReadFailsBeforeAnyIamPageIsWritten() {
        String id = enqueue(false);
        departmentHook =
                () -> {
                    departmentHook = () -> {};
                    global(
                            () -> {
                                provider.setExternalEnterpriseId("changed-authority");
                                DirectorySyncStore.save(providerRows, provider);
                                return null;
                            });
                };
        execute();
        assertThat(jobRuns.findById(id).getStatus()).isEqualTo("FAILED");
        assertThat(jobRuns.findById(id).getResultCode())
                .isEqualTo("DIRECTORY_CONFIGURATION_CHANGED");
        assertThat(
                        global(
                                () ->
                                        db.query(
                                                "select id from iam_user union all select id from iam_employee union all select id from iam_department")))
                .isEmpty();
        assertThat(syncRuns.query(Criteria.of().eq("jobRunId", id), new PageRequest(0, 1)))
                .singleElement()
                .satisfies(row -> assertThat(row.getStatus()).isEqualTo("FAILED"));
    }

    @Test
    void parentOnlyHierarchyRejectsOutOfScopePagesWithoutIamWrites() {
        tenant("tenant-a", () -> {
            var root = DirectorySyncConfiguration.roots(policy.getSyncRoots()).getFirst();
            policy.setSyncRoots(DirectorySyncStore.encode(List.of(new DirectorySyncConfiguration.Root("a", root.organizationId()))));
            policies.update(policy);
            var pending = scopes.findById(policy.getPendingSyncScopeId());
            // Test the frozen proposal directly; no confirmation or IAM writes are involved in preview.
            assertThat(pending).isNotNull();
            return null;
        });
        outsideScopePage = true;
        String preview = enqueue(true);
        execute();
        assertThat(jobRuns.findById(preview).getResultCode()).isEqualTo("DIRECTORY_PARTIAL");
        tenant("tenant-a", () -> {
            assertThat(observedDepartments.query(Criteria.of(), new PageRequest(0, 10))).isEmpty();
            assertThat(departmentRows.query(Criteria.of(), new PageRequest(0, 10))).isEmpty();
            return null;
        });
    }

    @Test
    void unsafeScopesAndManagementDefaultsAreRejectedBeforeChangingManagedPlans() {
        String roots = policy.getSyncRoots();
        String plan = policy.getSyncPlanId();
        var root = DirectorySyncConfiguration.roots(roots).getFirst();
        tenant(
                "tenant-a",
                () -> {
                    policy.setSyncScopeConfirmed(false);
                    policy.setSyncRoots("[]");
                    assertThatThrownBy(() -> policies.update(policy))
                            .isInstanceOf(IllegalArgumentException.class);
                    policy.setSyncRoots(
                            DirectorySyncStore.encode(
                                    List.of(
                                            new DirectorySyncConfiguration.Root(
                                                    "company", root.organizationId()))));
                    assertThatThrownBy(() -> policies.update(policy))
                            .isInstanceOf(IllegalArgumentException.class);
                    policy.setSyncRoots(roots);
                    policy.setSyncScopeConfirmed(true);
                    var role = roleRows.findById(policy.getSyncRoleId());
                    role.setSystemPurpose(RoleSystemPurpose.TENANT_ADMIN);
                    DirectorySyncStore.save(roleRows, role);
                    assertThatThrownBy(() -> policies.update(policy))
                            .isInstanceOf(IllegalArgumentException.class);
                    assertThat(jobPlans.findById(plan).getId()).isEqualTo(plan);
                    return null;
                });
    }

    @Test
    void lostLeaseTakeoverResumesCommittedCursorWithoutRepeatingDepartments() {
        String id = enqueue(false);
        peopleHook =
                () -> {
                    throw new AssertionError("simulated process loss");
                };
        assertThatThrownBy(() -> execute()).isInstanceOf(AssertionError.class);
        int reads = departmentReads.get();
        assertThat(reads).isEqualTo(2);
        assertThat(jobRuns.findById(id).getProgress()).isEqualTo(50);
        assertThat(jobRuns.findById(id).getStatus()).isEqualTo("RUNNING");
        peopleHook = () -> {};
        global(
                () -> {
                    db.execute(
                            "update platform_job_run set lease_until=clock_timestamp()-interval '1 second' where id=?",
                            id);
                    return null;
                });
        execute();
        assertThat(jobRuns.findById(id).getStatus()).isEqualTo("SUCCEEDED");
        assertThat(jobRuns.findById(id).getAttempts()).isEqualTo(2);
        assertThat(departmentReads.get()).isEqualTo(reads);
        assertThat(jobRuns.findById(id).getProgress()).isEqualTo(100);
        assertThat(syncRuns.query(Criteria.of(), new PageRequest(0, 10))).hasSize(1);
    }

    @Test
    void finalFailureNotifiesWithoutAutomaticRetryAndManualRetryContinuesTopic() {
        String original = enqueue(false);
        peopleHook =
                () -> {
                    throw new AssertionError("simulated process loss");
                };
        assertThatThrownBy(() -> execute()).isInstanceOf(AssertionError.class);
        int reads = departmentReads.get();
        global(
                () -> {
                    db.execute(
                            "update platform_job_run set started_at=clock_timestamp()-interval '2 hours' where id=?",
                            original);
                    return null;
                });
        jobs.renewNode("node");
        assertThat(jobRuns.findById(original).getStatus()).isEqualTo("FAILED");
        assertThat(alerts).hasSize(1);
        assertThat(jobs.runOne(DirectorySyncJob.CHANNEL, "node")).isFalse();
        peopleHook = () -> {};
        String retry = global(() -> jobs.retry(original).getId());
        execute();
        assertThat(jobRuns.findById(retry).getStatus()).isEqualTo("SUCCEEDED");
        assertThat(departmentReads.get()).isEqualTo(reads);
        assertThat(syncRuns.query(Criteria.of(), new PageRequest(0, 10)))
                .singleElement()
                .satisfies(row -> assertThat(row.getJobRunId()).isEqualTo(retry));
    }

    @Test
    void sameTenantPreviewWaitsWhileOtherTenantExecutesConcurrently() throws Exception {
        var other = tenant("tenant-b", () -> seedPolicy("tenant-b"));
        String first = enqueue(false);
        String waiting = enqueue(true);
        String independent = tenant("tenant-b", () -> policies.synchronize(other.getId(), true));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        departmentHook =
                () -> {
                    if (!"tenant-a".equals(TenantContext.currentTenantId().orElse(null))) return;
                    entered.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS))
                            throw new AssertionError("directory fixture timed out");
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                };
        try (var workers = Executors.newSingleThreadExecutor()) {
            var running =
                    workers.submit(() -> jobs.runOne(DirectorySyncJob.CHANNEL, "tenant-a-node"));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(jobs.runOne(DirectorySyncJob.CHANNEL, "tenant-b-node")).isTrue();
                assertThat(jobRuns.findById(first).getStatus()).isEqualTo("RUNNING");
                assertThat(enqueue(false)).isEqualTo(first);
                assertThat(enqueue(true)).isEqualTo(waiting);
                assertThat(jobRuns.findById(waiting).getStatus()).isEqualTo("PENDING");
                assertThat(jobRuns.findById(independent).getStatus()).isEqualTo("SUCCEEDED");
            } finally {
                release.countDown();
            }
            assertThat(running.get(10, TimeUnit.SECONDS)).isTrue();
        }
        execute();
        assertThat(jobRuns.findById(waiting).getStatus()).isEqualTo("SUCCEEDED");
    }

    private String enqueue(boolean preview) {
        return tenant("tenant-a", () -> policies.synchronize(policy.getId(), preview));
    }

    private void execute() {
        assertThat(jobs.runOne(DirectorySyncJob.CHANNEL, "node")).isTrue();
    }

    private static String fixture(List<String> failures) {
        var departments =
                List.of(
                        new ExternalDirectoryProtocol.Department(
                                "company", null, "Company"),
                        new ExternalDirectoryProtocol.Department(
                                "a", "company", "A"),
                        new ExternalDirectoryProtocol.Department(
                                "a1", "a", "A1"),
                        new ExternalDirectoryProtocol.Department(
                                "b", "company", "B"));
        var person =
                new ExternalDirectoryProtocol.Person(
                        "alice", "Alice", "001", "a1", List.of("a1", "b"), "active", null, null);
        return DirectorySyncStore.encode(
                new MockExternalDirectoryProtocol.Data(departments, List.of(person), failures));
    }

    private static <T> T tenant(String id, Supplier<T> work) {
        try (var scope = TenantContext.use(id)) {
            return work.get();
        }
    }

    private static <T> T global(Supplier<T> work) {
        try (var scope = TenantContext.system("test");
                var bypass = TenantContext.bypassTenantFilter("test")) {
            return work.get();
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableMuYunRepositories(
            basePackageClasses = {
                IdentityProviderDao.class,
                JobPlanDao.class,
                DepartmentDao.class,
                EmployeeDao.class,
                UserAccountDao.class,
                OrganizationDao.class,
                PositionDao.class,
                RoleDao.class
            })
    static class Application {
        @Bean
        DataSource dataSource() {
            return DataSourceBuilder.create()
                    .url(postgres.getJdbcUrl())
                    .username(postgres.getUsername())
                    .password(postgres.getPassword())
                    .driverClassName(postgres.getDriverClassName())
                    .build();
        }

        @Bean
        JdbiConfigurer bigIntegers() {
            return jdbi ->
                    jdbi.registerArgument(
                            new AbstractArgumentFactory<BigInteger>(Types.BIGINT) {
                                @Override
                                protected Argument build(BigInteger value, ConfigRegistry config) {
                                    return (position, statement, context) ->
                                            statement.setLong(position, value.longValueExact());
                                }
                            });
        }
    }
}
