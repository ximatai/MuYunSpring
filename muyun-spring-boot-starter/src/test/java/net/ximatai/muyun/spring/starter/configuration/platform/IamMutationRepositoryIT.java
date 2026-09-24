package net.ximatai.muyun.spring.starter.configuration.platform;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.spring.ability.*;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.common.platform.*;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.iam.department.DepartmentService;
import net.ximatai.muyun.spring.iam.employee.*;
import net.ximatai.muyun.spring.iam.role.AccountRoleGrantDao;
import net.ximatai.muyun.spring.iam.user.*;
import net.ximatai.muyun.spring.platform.module.PlatformModuleActionService;
import net.ximatai.muyun.spring.starter.MuYunSpringAutoConfiguration;
import net.ximatai.muyun.spring.starter.configuration.database.MuYunSpringDatabaseConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = IamMutationRepositoryIT.Application.class, properties = {
        "muyun.database.repository-schema-mode=ENSURE", "muyun.runtime.mode=development"
})
class IamMutationRepositoryIT {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    private static final String TENANT = "iam-mutation-it";
    private static final CurrentUser ACTOR = CurrentUser.tenantUser("operator", "Operator", TENANT);
    private static final ActionExecutionPolicy POLICY = new ActionExecutionPolicy("employeeAccounts",
            PlatformActionLevel.RECORD, ActionAccessMode.AUTH_REQUIRED, true, true,
            ActionDefaultGrantPolicy.NONE, null);

    @Autowired EmployeeDao employees;
    @Autowired EmployeePositionDao positionDao;
    @Autowired EmployeePositionService positions;
    @Autowired EmployeeAccountDao bindingDao;
    @Autowired EmployeeAccountService bindings;
    @Autowired UserAccountDao userDao;
    @Autowired Users users;
    @Autowired Access access;
    @Autowired PlatformTransactionManager transactions;

    @BeforeEach void resetHooks() {
        access.denied = false;
        access.hiddenEmployee = null;
        access.last = null;
        users.configure(false, () -> {});
    }

    @Test void concurrentPrimaryInsertAndEnableAllowOnlyOneWinner() throws Exception {
        Employee employee = employee();
        List<Boolean> inserted = concurrent(
                () -> positions.insert(position(employee, true)),
                () -> positions.insert(position(employee, true)));
        assertThat(inserted).containsExactlyInAnyOrder(true, false);
        assertOnePrimary(employee);

        Employee another = employee();
        EmployeePosition first = position(another, true);
        EmployeePosition second = position(another, true);
        first.setEnabled(false);
        second.setEnabled(false);
        asActor(() -> { positions.insert(first); return positions.insert(second); });
        assertThat(concurrent(() -> positions.enable(first.getId()), () -> positions.enable(second.getId())))
                .containsExactlyInAnyOrder(true, false);
        assertOnePrimary(another);
    }

    @Test void concurrentSwitchesSerializeBeforeReadingCurrentPrimary() throws Exception {
        Employee employee = employee();
        EmployeePosition first = position(employee, false);
        EmployeePosition second = position(employee, false);
        asActor(() -> { positions.insert(first); return positions.insert(second); });
        assertThat(concurrent(() -> positions.makePrimaryPosition(employee.getId(), first.getId()),
                () -> positions.makePrimaryPosition(employee.getId(), second.getId())))
                .containsOnly(true).hasSize(2);
        assertOnePrimary(employee);
    }

    @Test void restoreAndAggregateReplacementPreservePrimaryConstraintAndRollback() {
        Employee employee = employee();
        EmployeePosition original = position(employee, true);
        asActor(() -> positions.insert(original));
        EmployeePosition replacement = position(employee, true);
        replace(employee, List.of(replacement));
        assertThat(positionDao.findById(original.getId()).getDeleted()).isTrue();
        assertOnePrimary(employee);
        asActor(() -> {
            assertThatThrownBy(() -> positions.restore(original.getId())).hasMessageContaining("已有主岗位");
            return null;
        });
        assertThat(positionDao.findById(original.getId()).getDeleted()).isTrue();
        EmployeePosition next = position(employee, true);
        EmployeePosition invalid = position(employee, false);
        invalid.setPositionId(" ");
        assertThatThrownBy(() -> replace(employee, List.of(next, invalid))).hasMessageContaining("positionId");
        assertThat(positionDao.findById(replacement.getId()).getDeleted()).isFalse();
        assertThat(positionDao.findById(next.getId())).isNull();
        assertOnePrimary(employee);
    }

    @Test void partitionInvariantsRejectSnapshotIsolationBeforeAnyMutation() {
        Employee employee = employee();
        var transaction = new TransactionTemplate(transactions);
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThatThrownBy(() -> asActor(() -> transaction.execute(tx -> positions.insert(position(employee, true)))))
                .hasMessageContaining("READ_COMMITTED");
        assertThat(positionDao.query(Criteria.of().eq("employeeId", employee.getId()), new PageRequest(0, 10))).isEmpty();
    }

    @Test void partitionLockIsHeldUntilOuterTransactionEndsButDoesNotBlockOtherEmployees() throws Exception {
        Employee employee = employee();
        Employee other = employee();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(3)) {
            Future<?> holder = executor.submit(() -> asActor(() -> new TransactionTemplate(transactions).execute(status -> {
                positions.lockParentMutation(employee.getId());
                locked.countDown();
                await(release);
                return null;
            })));
            await(locked);
            Future<?> same = executor.submit(() -> asActor(() -> positions.insert(position(employee, true))));
            try {
                executor.submit(() -> asActor(() -> positions.insert(position(other, true)))).get(10, TimeUnit.SECONDS);
                assertThatThrownBy(() -> same.get(150, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally { release.countDown(); }
            holder.get(10, TimeUnit.SECONDS);
            same.get(10, TimeUnit.SECONDS);
        }
    }

    @Test void relatedRemovalUsesRealActorAndProxiedTargetLifecycleWithoutGrantingOrdinaryDelete() {
        Employee employee = employee();
        EmployeeAccount binding = binding(employee, user(TENANT));
        assertThat(AopUtils.isCglibProxy(users)).isTrue();
        asActor(() -> {
            assertThatThrownBy(() -> users.delete(binding.getUserId())).isInstanceOf(PlatformAccessDeniedException.class);
            assertThat(bindings.removeAccount(employee.getId())).isEqualTo(1);
            assertThat(CurrentUserContext.currentUser()).contains(ACTOR);
            assertThatThrownBy(() -> users.delete(user(TENANT).getId())).isInstanceOf(PlatformAccessDeniedException.class);
            return null;
        });
        assertThat(bindingDao.findById(binding.getId())).isNull();
        assertThat(userDao.findById(binding.getUserId())).isNull();
        assertThat(users.observedActor()).isEqualTo(ACTOR);
        assertThat(access.last.currentUser()).contains(ACTOR);
        assertThat(access.last.actionPolicy()).isEqualTo(POLICY);
        assertThat(access.last.recordIds()).containsExactly(employee.getId());
    }

    @Test void relatedRemovalRollsBackBindingAndTargetOnDownstreamFailureOrVersionChange() {
        Employee employee = employee();
        EmployeeAccount binding = binding(employee, user(TENANT));
        users.configure(true, () -> {});
        asActor(() -> {
            assertThatThrownBy(() -> bindings.removeAccount(employee.getId())).hasMessage("target hook failed");
            return null;
        });
        assertThat(bindingDao.findById(binding.getId())).isNotNull();
        assertThat(userDao.findById(binding.getUserId())).isNotNull();

        users.configure(false, () -> VersionedRecordMutation.update(userDao,
                () -> userDao.findById(binding.getUserId()), row -> row.setTitle("changed")));
        asActor(() -> {
            assertThatThrownBy(() -> bindings.removeAccount(employee.getId())).isInstanceOf(OptimisticLockException.class);
            return null;
        });
        assertThat(bindingDao.findById(binding.getId())).isNotNull();
        assertThat(userDao.findById(binding.getUserId()).getVersion()).isZero();
    }

    @Test void relatedRemovalDeniesInvisibleOrUnauthorizedSourceAndCrossTenantTarget() {
        Employee employee = employee();
        EmployeeAccount binding = binding(employee, user(TENANT));
        asActor(() -> {
            access.denied = true;
            assertThatThrownBy(() -> bindings.removeAccount(employee.getId())).isInstanceOf(PlatformAccessDeniedException.class);
            access.denied = false;
            access.hiddenEmployee = employee.getId();
            assertThatThrownBy(() -> bindings.removeAccount(employee.getId())).isInstanceOf(PlatformAccessDeniedException.class);
            access.hiddenEmployee = null;
            return null;
        });
        assertThat(bindingDao.findById(binding.getId())).isNotNull();
        Employee other = employee();
        EmployeeAccount crossTenant = binding(other, user("other-tenant"));
        asActor(() -> {
            assertThatThrownBy(() -> bindings.removeAccount(other.getId())).isInstanceOf(PlatformAccessDeniedException.class);
            return null;
        });
        assertThat(bindingDao.findById(crossTenant.getId())).isNotNull();
    }

    @Test void danglingOrAbsentBindingStillRequiresSourceAuthorization() {
        Employee employee = employee();
        EmployeeAccount binding = binding(employee, user(TENANT));
        userDao.deleteById(binding.getUserId());
        asActor(() -> {
            assertThat(bindings.removeAccount(employee.getId())).isEqualTo(1);
            assertThat(bindings.removeAccount(employee.getId())).isZero();
            access.denied = true;
            assertThatThrownBy(() -> bindings.removeAccount(employee.getId())).isInstanceOf(PlatformAccessDeniedException.class);
            return null;
        });
        assertThat(bindingDao.findById(binding.getId())).isNull();
    }

    private void replace(Employee employee, List<EmployeePosition> incoming) {
        employee.setPositions(incoming);
        asActor(() -> new TransactionTemplate(transactions).execute(status -> {
            positions.toChildRelation(EmployeePosition::setEmployeeId, "employeeId", Employee::getPositions)
                    .replaceChildren(employee.getId(), employee);
            return null;
        }));
    }

    private void assertOnePrimary(Employee employee) {
        asActor(() -> {
            assertThat(positions.count(Criteria.of().eq("employeeId", employee.getId())
                    .eq("enabled", true).eq("primaryPosition", true))).isEqualTo(1);
            return null;
        });
    }

    private List<Boolean> concurrent(Supplier<?> first, Supplier<?> second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> futures = List.of(first, second).stream().map(work -> executor.submit(() -> {
                await(start);
                try { asActor(work); return true; }
                catch (net.ximatai.muyun.spring.common.exception.PlatformException conflict) {
                    assertThat(conflict.getMessage()).contains("已有主岗位");
                    return false;
                }
            })).toList();
            start.countDown();
            return List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS));
        }
    }

    private Employee employee() {
        Employee row = prepare(new Employee(), TENANT);
        row.setTitle("Employee"); row.setEmployeeNo(row.getId());
        row.setOrganizationId("organization"); row.setDepartmentId("department");
        employees.insert(row);
        return row;
    }

    private EmployeePosition position(Employee employee, boolean primary) {
        EmployeePosition row = new EmployeePosition();
        row.setEmployeeId(employee.getId()); row.setOrganizationId(employee.getOrganizationId());
        row.setDepartmentId(employee.getDepartmentId()); row.setPositionId(UUID.randomUUID().toString().replace("-", ""));
        row.setPrimaryPosition(primary);
        return row;
    }

    private UserAccount user(String tenant) {
        UserAccount row = prepare(new UserAccount(), tenant);
        row.setUsername(row.getId()); row.setTitle("User"); row.setPasswordHash("hash");
        userDao.insert(row);
        return row;
    }

    private EmployeeAccount binding(Employee employee, UserAccount user) {
        EmployeeAccount row = prepare(new EmployeeAccount(), TENANT);
        row.setEmployeeId(employee.getId()); row.setUserId(user.getId());
        bindingDao.insert(row);
        return row;
    }

    private static <T extends EntityContract> T prepare(T row, String tenant) {
        row.setTenantId(tenant);
        EntityLifecycle.prepareInsert(row, Instant.now());
        return row;
    }

    private static <T> T asActor(Supplier<T> work) {
        try (var ignored = TenantContext.use(TENANT); var actor = CurrentUserContext.use(ACTOR)) { return work.get(); }
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(20, TimeUnit.SECONDS)) throw new AssertionError("barrier timed out"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }

    static class Access extends AllowAllDataScopeCriteriaService implements ActionExecutionPolicyService {
        boolean denied;
        String hiddenEmployee;
        ActionExecutionContext last;
        @Override public void requireAuthorized(ActionExecutionContext context) {
            last = context;
            if (denied || context.currentUser().isEmpty()) throw new PlatformAccessDeniedException("source denied");
        }
        @Override public DataScopeCriteriaResult resolveReadScope(String module, ActionExecutionPolicy policy,
                                                                  Criteria criteria, Optional<CurrentUser> actor) {
            if (UserAccountService.MODULE_ALIAS.equals(module)) {
                throw new PlatformAccessDeniedException("direct account mutation denied");
            }
            if (hiddenEmployee != null) criteria.ne("id", hiddenEmployee);
            return DataScopeCriteriaResult.restricted(criteria);
        }
    }

    static class Users extends UserAccountService {
        private boolean fail;
        private Runnable before = () -> {};
        private CurrentUser observed;
        Users(UserAccountDao dao, Access access) {
            super(dao, tenant -> {}, new PasswordHashingService(), mock(AccountRoleGrantDao.class),
                    new UserAccountSecurityServices(Optional.empty(), UserSecurityEventPublisher.NOOP,
                            mock(UserSessionRevocationService.class), mock(UserSessionPresenceService.class)));
            this.access = access;
        }
        private final Access access;
        @Override public DataScopeCriteriaService getDataScopeCriteriaService() { return access; }
        void configure(boolean fail, Runnable before) { this.fail = fail; this.before = before; this.observed = null; }
        CurrentUser observedActor() { return observed; }
        @Override @Transactional public int delete(String id, Integer expectedVersion) {
            before.run();
            return super.delete(id, expectedVersion);
        }
        @Override public void afterDelete(String id, UserAccount entity, int deleted) {
            super.afterDelete(id, entity, deleted);
            observed = CurrentUserContext.currentUser().orElseThrow();
            if (fail) throw new IllegalStateException("target hook failed");
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {MuYunSpringAutoConfiguration.class, MuYunSpringBusinessLoggingConfiguration.class})
    @EnableTransactionManagement(proxyTargetClass = true)
    @EnableMuYunRepositories(basePackageClasses = {EmployeeDao.class, UserAccountDao.class})
    @Import({MuYunSpringMutationConfiguration.class, MuYunSpringDatabaseConfiguration.class,
            net.ximatai.muyun.spring.platform.deletion.RelatedRecordDeletionService.class})
    static class Application {
        @Bean DataSource dataSource() {
            return DataSourceBuilder.create().url(postgres.getJdbcUrl()).username(postgres.getUsername())
                    .password(postgres.getPassword()).driverClassName(postgres.getDriverClassName()).build();
        }
        @Bean Access access() { return new Access(); }
        @Bean EmployeeService employees(EmployeeDao dao, Access access) {
            return new EmployeeService(dao, tenant -> {}, mock(DepartmentService.class)) {
                @Override public DataScopeCriteriaService getDataScopeCriteriaService() { return access; }
            };
        }
        @Bean EmployeePositionService positions(EmployeePositionDao dao, EmployeeService employees) {
            return new EmployeePositionService(dao, tenant -> {}, employees);
        }
        @Bean Users users(UserAccountDao dao, Access access) { return new Users(dao, access); }
        @Bean EmployeeAccountService bindings(EmployeeAccountDao dao, EmployeeService employees, Users users) {
            return new EmployeeAccountService(dao, tenant -> {}, employees, users);
        }
        @Bean PlatformModuleActionService actions() {
            PlatformModuleActionService service = mock(PlatformModuleActionService.class);
            when(service.requireExecutionPolicy(EmployeeService.MODULE_ALIAS, POLICY.actionCode())).thenReturn(POLICY);
            return service;
        }
    }
}
