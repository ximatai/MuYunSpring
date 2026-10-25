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
    @Autowired ExternalLoginTransactionDao loginTransactions;
    @Autowired DepartmentDao departmentRows;
    @Autowired EmployeeDao employeeRows;
    @Autowired EmployeePositionDao positionRows;
    @Autowired UserAccountDao accountRows;
    @Autowired EmployeeAccountDao employeeAccountRows;
    @Autowired OrganizationDao organizationRows;
    @Autowired PositionDao jobPositionRows;
    @Autowired RoleDao roleRows;
    @Autowired EmploymentRoleGrantDao grantRows;
    @Autowired JobPlanDao jobPlans;
    @Autowired JobRunDao jobRuns;
    private final AtomicInteger departmentReads = new AtomicInteger();
    private volatile Runnable peopleHook = () -> {};
    private volatile Runnable departmentHook = () -> {};
    private JobService jobs;
    private TenantIdentityPolicyService policies;
    private EmployeePositionService positions;
    private IdentityProvider provider;
    private TenantIdentityPolicy policy;
    private final List<BusinessNotification> alerts = new ArrayList<>();
    private DirectorySyncJob handler;
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
                                    "iam_external_identity",
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
                        return mockDirectory.departments(p, root, cursor, limit);
                    }

                    public Page<String> departmentPeople(
                            IdentityProviderDescriptor p, String id, String cursor, int limit) {
                        outside();
                        peopleHook.run();
                        return mockDirectory.departmentPeople(p, id, cursor, limit);
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
        var identities = new ExternalIdentityService(identityRows, t -> {});
        var store =
                new DirectorySyncStore(
                        syncRuns, scopes, mappings, seen, observedDepartments, db, policyRows);
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
        handler =
                new DirectorySyncJob(
                        store,
                        providers,
                        config,
                        new DirectorySyncWriter(
                                store,
                                departments,
                                employees,
                                positions,
                                accounts,
                                employeeAccounts,
                                identities,
                                roles,
                                config));
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
        assertThat(policy.getSyncScopeId()).isNotEqualTo(oldScope);
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
                                "company", null, "Company", List.of()),
                        new ExternalDirectoryProtocol.Department(
                                "a", "company", "A", List.of("company")),
                        new ExternalDirectoryProtocol.Department(
                                "a1", "a", "A1", List.of("company", "a")),
                        new ExternalDirectoryProtocol.Department(
                                "b", "company", "B", List.of("company")));
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
