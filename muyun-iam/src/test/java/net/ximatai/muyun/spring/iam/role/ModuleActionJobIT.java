package net.ximatai.muyun.spring.iam.role;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.database.core.IDatabaseOperations;
import net.ximatai.muyun.database.core.orm.*;
import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.spring.ability.event.*;
import net.ximatai.muyun.spring.common.identity.*;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import net.ximatai.muyun.spring.common.platform.*;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.dynamic.metadata.*;
import net.ximatai.muyun.spring.dynamic.runtime.*;
import net.ximatai.muyun.spring.dynamic.schema.DynamicSchemaService;
import net.ximatai.muyun.spring.iam.user.*;
import net.ximatai.muyun.spring.platform.job.*;
import net.ximatai.muyun.spring.platform.audit.*;
import net.ximatai.muyun.spring.platform.module.*;
import net.ximatai.muyun.spring.platform.notification.*;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real PostgreSQL job state, live IAM account lookup, action policy, data scope and dynamic runtime. */
@Testcontainers
@SpringBootTest(classes = ModuleActionJobIT.Application.class)
class ModuleActionJobIT {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) {
        properties.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }
    @Autowired JobPlanDao jobPlans;
    @Autowired JobRunDao runs;
    @Autowired ScheduledActionPlanDao topicPlans;
    @Autowired SystemActionWhitelistDao whitelistRows;
    @Autowired PlatformModuleDao moduleRows;
    @Autowired PlatformModuleActionDao actionRows;
    @Autowired UserAccountDao accounts;
    @Autowired RuntimeAuditRecordDao auditRows;
    @Autowired RuntimeAuditEventListener auditListener;
    @Autowired IDatabaseOperations<?> db;
    @Autowired PlatformTransactionManager transactions;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final List<RuntimeEvent> events = new ArrayList<>();
    private final List<BusinessNotification> alerts = new ArrayList<>();
    private final AtomicInteger staticCalls = new AtomicInteger();
    private JobService jobs;
    private ScheduledActionPlanService plans;
    private SystemActionWhitelistService whitelist;
    private DynamicRecordRuntime runtime;
    private DynamicRecordService records;
    private RoleService roles;
    private UserAccount account;

    @BeforeEach void setup() {
        global(() -> { for (String table : List.of("platform_job_run", "platform_job_plan", "platform_scheduled_action_plan",
                "platform_runtime_audit_record", "platform_system_action_whitelist", "platform_module_action", "platform_module", "iam_user")) db.execute("delete from " + table); return null; });
        events.clear(); alerts.clear(); staticCalls.set(0);
        var beans = new DefaultListableBeanFactory();
        RuntimeEventPublisher publisher = event -> { events.add(event); auditListener.onRuntimeEvent(event); };
        whitelist = new SystemActionWhitelistService(whitelistRows); beans.registerSingleton("whitelist", whitelist);
        roles = mock(RoleService.class);
        when(roles.hasActionPermission(anyString(), anyString(), anyString())).thenReturn(true);
        var grant = new RoleAction(); grant.setDataScopePolicy(DataScopePolicy.OWNER);
        when(roles.effectiveActionGrantsWithContext(anyString(), anyString(), anyString())).thenReturn(List.of(new EffectiveRoleActionGrant(grant, null)));
        var policies = new RoleActionExecutionPolicyService(roles); policies.jobSystemPolicies(beans.getBeanProvider(JobSystemActionPolicy.class));
        var scopes = new RoleDataScopeCriteriaService(roles, mock(TenantAdminImplicitGrantPolicy.class), Optional.empty(), Optional.empty(), Optional.empty());
        scopes.jobSystemPolicies(beans.getBeanProvider(JobSystemActionPolicy.class));
        var accountService = mock(UserAccountService.class); when(accountService.getDao()).thenReturn(accounts);
        when(accountService.passwordChangeRequired(any(), any())).thenAnswer(call -> ((UserAccount) call.getArgument(0)).getPasswordStatus() == PasswordStatus.RESET_REQUIRED);
        var identities = new IamTrustedExecutionIdentityResolver(accountService, tenant -> { }, UserSessionCollaborators.empty(),
                beans.getBeanProvider(net.ximatai.muyun.spring.iam.employee.EmployeeDelegationService.class), Clock.systemUTC());
        beans.registerSingleton("identity", identities);
        var entity = new EntityDefinition("entry", "job_action_fixture", "Entries", List.of(new FieldDefinition("title", "title", FieldType.STRING, "Title")))
                .withCapabilities(EntityCapability.CRUD, EntityCapability.DATA_SCOPE);
        var action = new EntityActionDefinition("entry", "execute", "Execute", true, EntityActionLevel.LIST, EntityActionCategory.CUSTOM,
                EntityActionAccessMode.AUTH_REQUIRED, true, true, null, null, null, EntityActionExecutorType.SERVICE, "test.execute");
        var module = ModuleDefinition.builder("test.target", "Target").entities(List.of(entity)).actions(List.of(action)).build();
        var executor = new DynamicActionExecutor() {
            public String executorKey() { return "test.execute"; }
            public Object execute(DynamicActionExecutionContext context, DynamicActionExecutionRequest request) { throw new UnsupportedOperationException(); }
            @Override public Object execute(DynamicActionExecutionContext context, DynamicActionExecutionRequest request, DynamicActionOperations operations) {
                if (request.payload().get("foreignId") instanceof String foreignId) {
                    var foreign = runtime.newRecord("test.target", "entry"); foreign.setId(foreignId); foreign.setValue("title", "forbidden");
                    operations.update(foreign);
                }
                var visible = runtime.entityService("test.target", "entry").list(request.criteria());
                for (var row : visible) { row.setValue("title", request.payload().get("title")); operations.update(row); }
                return visible.size();
            }
        };
        runtime = DynamicRecordRuntime.builder(db).eventPublisher(publisher).actionExecutorRegistry(new DynamicActionExecutorRegistry(List.of(executor))).build().register(module);
        global(() -> { new DynamicSchemaService(db).ensureModule(module); db.execute("delete from job_action_fixture"); return null; });
        records = new DynamicRecordService(runtime, policies, scopes); beans.registerSingleton("records", records);
        var modules = mock(PlatformModuleService.class); when(modules.select(anyString())).thenAnswer(call -> moduleRows.findById(call.getArgument(0)));
        var actions = mock(PlatformModuleActionService.class);
        when(actions.findByModuleAliasAndActionCode(anyString(), anyString())).thenAnswer(call -> lookup(call.getArgument(0), call.getArgument(1)));
        when(actions.list(any(Criteria.class), any(PageRequest.class), any(Sort.class))).thenAnswer(call ->
                global(() -> actionRows.query(call.getArgument(0), call.getArgument(1), call.getArgument(2, Sort.class))));
        when(actions.requireExecutionPolicy(anyString(), anyString())).thenAnswer(call -> lookup(call.getArgument(0), call.getArgument(1)).executionPolicy());
        beans.registerSingleton("static", new ScheduledModuleAction() {
            public String moduleAlias() { return "test.static"; } public String actionCode() { return "execute"; }
            public Object execute(Map<String, Object> input) { staticCalls.incrementAndGet(); return null; }
        });
        var invoker = new ModuleActionInvoker(modules, actions, policies, beans.getBeanProvider(DynamicRecordService.class), beans.getBeanProvider(ScheduledModuleAction.class), publisher);
        beans.registerSingleton("invoker", invoker);
        var handler = new ModuleActionJob(JobScope.TENANT, beans.getBeanProvider(ModuleActionInvoker.class), topicPlans, whitelist, beans.getBeanProvider(TrustedExecutionIdentityResolver.class), json);
        beans.registerSingleton("access", (JobManagementAccess) tenant -> { });
        beans.registerSingleton("recipients", (JobFailureRecipientResolver) tenant -> new BusinessNotificationRecipients(false, List.of(), List.of(), List.of(), List.of(), List.of("admin")));
        beans.registerSingleton("notifications", (BusinessNotificationDelivery) alerts::add);
        jobs = new JobService(jobPlans, runs, db, transactions, new JobRegistry(List.of(handler)), beans.getBeanProvider(JobFailureRecipientResolver.class),
                beans.getBeanProvider(BusinessNotificationDelivery.class), beans.getBeanProvider(JobManagementAccess.class), Duration.ofMinutes(1), 90);
        plans = new ScheduledActionPlanService(topicPlans, jobs, invoker, beans.getBeanProvider(TrustedExecutionIdentityResolver.class), whitelist, tenant -> { }, json, tenant -> { });
        global(() -> {
            for (String alias : List.of("test.target", "test.static")) {
                var row = new PlatformModule(); row.setAlias(alias); row.setTitle(alias); row.setApplicationAlias("test"); row.setEnabled(true);
                row.setModuleKind(alias.endsWith("static") ? ModuleKind.STATIC : ModuleKind.DYNAMIC); EntityLifecycle.prepareInsert(row, Instant.now()); moduleRows.insert(row);
                var a = new PlatformModuleAction(); a.setModuleAlias(alias); a.setActionCode("execute"); a.setTitle("Execute"); a.setEnabled(true);
                a.setActionLevel(EntityActionLevel.LIST); a.setExecutorType(EntityActionExecutorType.SERVICE); a.setExecutorKey("test.execute"); a.setSchedulable(true); a.setDataAuth(true);
                EntityLifecycle.prepareInsert(a, Instant.now()); actionRows.insert(a);
            }
            account = new UserAccount(); account.setId("initiator"); account.setUsername("initiator"); account.setTitle("Initiator"); account.setTenantId("tenant-a"); account.setEnabled(true); account.setPasswordStatus(PasswordStatus.NORMAL);
            EntityLifecycle.prepareInsert(account, Instant.now()); accounts.insert(account); return null;
        });
        seed("tenant-a", "initiator", "own"); seed("tenant-a", "other", "colleague"); seed("tenant-b", "other", "other-tenant"); events.clear();
        global(() -> { db.execute("delete from platform_runtime_audit_record"); return null; });
    }
    @AfterEach void close() { if (runtime != null) runtime.close(); }

    @Test void initiatorUsesLivePermissionsDataScopeAndFrozenParametersWithAudit() {
        var plan = initiatorPlan("test.target");
        jobs.scan();
        try (var user = CurrentUserContext.use(CurrentUser.tenantUser("initiator", "initiator", "tenant-a")); var tenant = TenantContext.use("tenant-a")) {
            plan.setArgumentsJson("{\"title\":\"new-plan\"}"); plans.update(plan);
        }
        jobs.runOne("default", "node");
        assertThat(latest().getStatus()).isEqualTo("SUCCEEDED"); assertThat(latest().getIdentityMode()).isEqualTo("INITIATOR");
        assertThat(titles("tenant-a")).containsExactlyInAnyOrder("frozen", "colleague"); assertThat(titles("tenant-b")).containsExactly("other-tenant");
        assertThat(audits()).singleElement().satisfies(audit -> {
            assertThat(audit.getAuthorizationDecision()).isEqualTo("ROLE_GRANTED"); assertThat(audit.getOperatorId()).isEqualTo("initiator");
            assertThat(audit.getPayloadText()).contains("INITIATOR", latest().getId());
        });
        assertThat(events.stream().filter(e -> e.eventType() == RuntimeEventType.ACTION_EXECUTED)).singleElement().satisfies(event -> {
            assertThat(event.authorizationDecision()).isEqualTo("ROLE_GRANTED"); assertThat(event.operatorId()).isEqualTo("initiator"); assertThat(event.auditContext()).containsValue("INITIATOR");
        });
    }
    @Test void withdrawnAccountAndPermissionFailWithoutDeletingPlanAndNotifyInitiator() {
        var plan = initiatorPlan("test.target"); jobs.scan();
        global(() -> { db.execute("update iam_user set enabled=false where id='initiator'"); return null; });
        jobs.runOne("default", "node"); assertThat(latest().getStatus()).isEqualTo("FAILED");
        assertThat(global(() -> jobPlans.findById(plan.getJobPlanId()))).isNotNull();
        assertThat(alerts).singleElement().satisfies(alert -> assertThat(alert.recipients().userIds()).contains("initiator", "admin"));
        global(() -> { db.execute("update iam_user set enabled=true where id='initiator'"); return null; });
        when(roles.hasActionPermission(anyString(), anyString(), anyString())).thenReturn(false);
        global(() -> jobs.runNow(plan.getJobPlanId())); jobs.runOne("default", "node"); assertThat(latest().getStatus()).isEqualTo("FAILED");
    }
    @Test void systemWhitelistIsRecheckedAndTenantIsolationSurvivesUnrestrictedDataScope() {
        whitelist("execute"); whitelist("update");
        ScheduledActionPlan plan;
        try (var admin = CurrentUserContext.use(CurrentUser.systemUser("admin", "admin")); var tenant = TenantContext.system("test")) {
            plan = row("test.target"); plans.insert(plan);
        }
        jobs.scan(); global(() -> { db.execute("delete from platform_system_action_whitelist where action_code='execute'"); return null; });
        jobs.runOne("default", "node"); assertThat(latest().getStatus()).isEqualTo("FAILED");
        whitelist("execute"); global(() -> jobs.retry(latest().getId())); jobs.runOne("default", "node");
        assertThat(latest().getStatus()).isEqualTo("SUCCEEDED"); assertThat(titles("tenant-a")).containsOnly("frozen"); assertThat(titles("tenant-b")).containsExactly("other-tenant");
        assertThat(audits()).singleElement().satisfies(audit -> {
            assertThat(audit.getAuthorizationDecision()).isEqualTo(JobSystemActionPolicy.DECISION);
            assertThat(audit.getOperatorType()).isEqualTo("BACKGROUND_JOB"); assertThat(audit.getPayloadText()).contains("SYSTEM", latest().getId());
        });
        assertThat(events.stream().filter(e -> e.eventType() == RuntimeEventType.ACTION_EXECUTED)).singleElement().satisfies(event -> {
            assertThat(event.authorizationDecision()).isEqualTo(JobSystemActionPolicy.DECISION); assertThat(event.operatorType()).isEqualTo("BACKGROUND_JOB"); assertThat(event.tenantId()).isEqualTo("tenant-a");
        });
    }
    @Test void deletedOrPasswordRestrictedAccountFailsOnEveryLiveResolution() {
        var plan = initiatorPlan("test.target"); jobs.scan();
        global(() -> { db.execute("update iam_user set password_status='resetRequired' where id='initiator'"); return null; });
        jobs.runOne("default", "node"); assertThat(latest().getStatus()).isEqualTo("FAILED");
        global(() -> { db.execute("update iam_user set password_status='normal',deleted=true where id='initiator'"); return null; });
        global(() -> jobs.runNow(plan.getJobPlanId())); jobs.runOne("default", "node"); assertThat(latest().getStatus()).isEqualTo("FAILED");
        assertThat(titles("tenant-a")).containsExactlyInAnyOrder("own", "colleague");
    }
    @Test void tenantSystemActionCannotWriteAnExplicitForeignTenantRecord() {
        whitelist("execute"); whitelist("update");
        String foreignId;
        try (var tenant = TenantContext.use("tenant-b")) { foreignId = runtime.entityService("test.target", "entry").list(Criteria.of()).getFirst().getId(); }
        try (var admin = CurrentUserContext.use(CurrentUser.systemUser("admin", "admin")); var tenant = TenantContext.system("test")) {
            var plan = row("test.target"); plan.setArgumentsJson("{\"foreignId\":\"" + foreignId + "\"}"); plans.insert(plan);
        }
        jobs.scan(); jobs.runOne("default", "node");
        assertThat(latest().getStatus()).isEqualTo("FAILED"); assertThat(titles("tenant-b")).containsExactly("other-tenant");
        assertThat(titles("tenant-a")).containsExactlyInAnyOrder("own", "colleague");
    }
    @Test void removedOrNoLongerSchedulableActionFailsAndKeepsPlan() {
        var plan = initiatorPlan("test.target"); jobs.scan();
        global(() -> { db.execute("update platform_module_action set schedulable=false where module_alias='test.target'"); return null; });
        jobs.runOne("default", "node"); assertThat(latest().getStatus()).isEqualTo("FAILED");
        global(() -> jobs.runNow(plan.getJobPlanId())); global(() -> { db.execute("delete from platform_module_action where module_alias='test.target'"); return null; });
        jobs.runOne("default", "node"); assertThat(latest().getStatus()).isEqualTo("FAILED"); assertThat(global(() -> jobPlans.findById(plan.getJobPlanId()))).isNotNull();
    }
    @Test void explicitStaticContributionExecutesAndPauseMirrorsTopicState() {
        var plan = initiatorPlan("test.static"); jobs.scan(); jobs.runOne("default", "node");
        assertThat(latest().getStatus()).isEqualTo("SUCCEEDED"); assertThat(staticCalls.get()).isEqualTo(1);
        global(() -> { jobs.pause(plan.getJobPlanId()); return null; });
        assertThat(global(() -> topicPlans.findById(plan.getId()).getEnabled())).isFalse();
        assertThat(events).anySatisfy(event -> { assertThat(event.moduleAlias()).isEqualTo("test.static"); assertThat(event.eventType()).isEqualTo(RuntimeEventType.ACTION_EXECUTED); });
    }
    @Test void candidateCatalogUsesLiveAuthorizationIdentityAndSystemWhitelist() {
        try (var user = CurrentUserContext.use(CurrentUser.tenantUser("initiator", "initiator", "tenant-a"));
             var tenant = TenantContext.use("tenant-a")) {
            assertThat(plans.scheduledTargets(null)).extracting(ModuleActionInvoker.ScheduledTargetDescriptor::moduleAlias)
                    .containsExactlyInAnyOrder("test.static", "test.target");
            assertThat(plans.scheduledTargets(null)).anySatisfy(target -> {
                assertThat(target.moduleAlias()).isEqualTo("test.static");
                assertThat(target.source()).isEqualTo(ModuleKind.STATIC);
                assertThat(target.actionTitle().originalText()).isEqualTo("Execute");
                assertThat(target.moduleTitle().originalText()).isEqualTo("test.static");
                assertThat(target.parametersRequired()).isFalse();
            });
            when(roles.hasActionPermission(eq("initiator"), eq("test.target"), anyString())).thenReturn(false);
            assertThat(plans.scheduledTargets(null)).extracting(ModuleActionInvoker.ScheduledTargetDescriptor::moduleAlias)
                    .containsExactly("test.static");
            assertThatThrownBy(() -> plans.scheduledTargets("tenant-b"))
                    .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
            global(() -> { db.execute("update iam_user set enabled=false where id='initiator'"); return null; });
            assertThat(plans.scheduledTargets(null)).isEmpty();
        }
        try (var admin = CurrentUserContext.use(CurrentUser.systemUser("admin", "admin")); var tenant = TenantContext.system("test")) {
            assertThat(plans.scheduledTargets("tenant-a")).isEmpty();
            whitelist("execute");
            assertThat(plans.scheduledTargets("tenant-a"))
                    .extracting(ModuleActionInvoker.ScheduledTargetDescriptor::moduleAlias).containsExactly("test.target");
        }
    }

    @Test void candidatesShareSaveTargetRulesAndNeverExposeForeignTenantModules() {
        try (var user = CurrentUserContext.use(CurrentUser.tenantUser("initiator", "initiator", "tenant-a"));
             var tenant = TenantContext.use("tenant-a")) {
            for (String mutation : List.of("schedulable=false", "form_supported=true", "action_level='RECORD'",
                    "executor_type='DIALOG'", "enabled=false")) {
                global(() -> { db.execute("update platform_module_action set " + mutation + " where module_alias='test.target'"); return null; });
                assertThat(plans.scheduledTargets(null)).extracting(ModuleActionInvoker.ScheduledTargetDescriptor::moduleAlias)
                        .doesNotContain("test.target");
                assertThatThrownBy(() -> plans.insert(row("test.target")))
                        .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
                global(() -> { db.execute("update platform_module_action set schedulable=true,form_supported=false,action_level='LIST',executor_type='SERVICE',enabled=true where module_alias='test.target'"); return null; });
            }
            global(() -> { db.execute("update platform_module set tenant_id='tenant-b' where id='test.target'"); return null; });
            assertThat(plans.scheduledTargets(null)).extracting(ModuleActionInvoker.ScheduledTargetDescriptor::moduleAlias)
                    .containsExactly("test.static");
            assertThatThrownBy(() -> plans.insert(row("test.target")))
                    .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
            global(() -> { db.execute("update platform_module_action set available_expression='true' where module_alias='test.static'"); return null; });
            assertThat(plans.scheduledTargets(null)).isEmpty();
        }
        try (var user = CurrentUserContext.use(CurrentUser.tenantUser("other", "other", "tenant-b"));
             var tenant = TenantContext.use("tenant-b")) {
            // A valid target still requires a capturable account in the execution tenant.
            assertThat(plans.scheduledTargets(null)).isEmpty();
        }
    }

    private ScheduledActionPlan initiatorPlan(String module) {
        try (var user = CurrentUserContext.use(CurrentUser.tenantUser("initiator", "initiator", "tenant-a")); var tenant = TenantContext.use("tenant-a")) {
            var plan = row(module); plans.insert(plan); return plan;
        }
    }
    private ScheduledActionPlan row(String module) { var row = new ScheduledActionPlan(); row.setExecutionTenantId("tenant-a"); row.setModuleAlias(module); row.setActionCode("execute"); row.setArgumentsJson("{\"title\":\"frozen\"}"); row.setInitialDelayMillis(0L); return row; }
    private void whitelist(String code) { try (var admin = CurrentUserContext.use(CurrentUser.systemUser("admin", "admin")); var tenant = TenantContext.system("test")) { var row = new SystemActionWhitelist(); row.setModuleAlias("test.target"); row.setActionCode(code); whitelist.insert(row); } }
    private PlatformModuleAction lookup(String module, String code) { return global(() -> actionRows.query(Criteria.of().eq("moduleAlias", module).eq("actionCode", code), PageRequest.of(1, 1)).stream().findFirst().orElse(null)); }
    private void seed(String tenant, String owner, String title) { try (var scope = TenantContext.use(tenant); var user = CurrentUserContext.use(CurrentUser.tenantUser(owner, owner, tenant))) { var row = runtime.newRecord("test.target", "entry"); row.setValue("title", title); runtime.entityService("test.target", "entry").insert(row); } }
    private List<String> titles(String tenant) { try (var scope = TenantContext.use(tenant)) { return runtime.entityService("test.target", "entry").list(Criteria.of()).stream().map(row -> (String) row.getValue("title")).toList(); } }
    private List<RuntimeAuditRecord> audits() { return global(() -> auditRows.query(Criteria.of().eq("eventType", RuntimeEventType.ACTION_EXECUTED), PageRequest.of(1, 100))); }
    private JobRun latest() { return global(() -> runs.query(Criteria.of(), PageRequest.of(1, 1), Sort.desc("createdAt")).getFirst()); }
    private static <T> T global(Supplier<T> work) { try (var scope = TenantContext.system("test"); var bypass = TenantContext.bypassTenantFilter("test")) { return work.get(); } }

    @SpringBootConfiguration @EnableAutoConfiguration
    @org.springframework.transaction.annotation.EnableTransactionManagement
    @EnableMuYunRepositories(basePackageClasses = {JobPlanDao.class, PlatformModuleDao.class, UserAccountDao.class, RuntimeAuditRecordDao.class})
    static class Application {
        @Bean RuntimeAuditRecordService runtimeAuditRecords(RuntimeAuditRecordDao rows) { return new RuntimeAuditRecordService(rows); }
        @Bean RuntimeAuditEventListener runtimeAuditListener(RuntimeAuditRecordService records) { return new RuntimeAuditEventListener(records); }
        @Bean DataSource dataSource() { return DataSourceBuilder.create().url(postgres.getJdbcUrl()).username(postgres.getUsername()).password(postgres.getPassword()).driverClassName(postgres.getDriverClassName()).build(); }
        @Bean net.ximatai.muyun.database.spring.boot.JdbiConfigurer bigIntegers() {
            return jdbi -> jdbi.registerArgument(new org.jdbi.v3.core.argument.AbstractArgumentFactory<java.math.BigInteger>(java.sql.Types.BIGINT) {
                @Override protected org.jdbi.v3.core.argument.Argument build(java.math.BigInteger value, org.jdbi.v3.core.config.ConfigRegistry config) {
                    return (position, statement, context) -> statement.setLong(position, value.longValueExact());
                }
            });
        }
    }
}
