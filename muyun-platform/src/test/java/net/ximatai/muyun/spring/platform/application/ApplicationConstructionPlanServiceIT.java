package net.ximatai.muyun.spring.platform.application;

import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.spring.ability.MutationTransactionOperator;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.support.PlatformPostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes = ApplicationConstructionPlanServiceIT.Application.class)
class ApplicationConstructionPlanServiceIT extends PlatformPostgresIntegrationTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }
    @Autowired ApplicationConstructionPlanService service;
    @Autowired DataSource source;
    @Autowired ApplicationConstructionAcceptanceDao acceptances;
    @Autowired PlatformTransactionManager transactions;
    JdbcTemplate jdbc;

    @BeforeEach void setup() {
        jdbc = new JdbcTemplate(source);
        PlatformAbilityRuntime.configureMutationTransactionOperator(new MutationTransactionOperator() {
            public <T> T execute(Supplier<T> work) { return new TransactionTemplate(transactions).execute(s -> work.get()); }
            public void lock(String scope, String key) {
                jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class, scope + key);
            }
        });
    }
    @AfterEach void cleanup() { PlatformAbilityRuntime.resetMutationTransactionOperator(); }

    private <T> T as(String user, String tenant, Supplier<T> operation) {
        try (var identity = CurrentUserContext.use(CurrentUser.tenantUser(user, user, tenant));
             var scope = TenantContext.use(tenant)) { return operation.get(); }
    }
    private String id() { return UUID.randomUUID().toString().replace("-", ""); }
    private ApplicationConstructionPlanContent content(String title) {
        return new ApplicationConstructionPlanContent(title, "管理订单", java.util.List.of("订单录入"), java.util.List.of("结算"),
            java.util.List.of(new ApplicationConstructionPlanContent.BusinessObject("order", "订单", "记录客户订购")),
            java.util.List.of(), java.util.List.of(), java.util.List.of("是否需要审批"), java.util.List.of(),
            java.util.List.of(new ApplicationConstructionPlanContent.Decision("先管理订单", ApplicationConstructionPlanContent.Source.RECOMMENDATION)),
            java.util.List.of("可以记录一张订单"), java.util.List.of());
    }
    private ApplicationConstructionPlanService.ConfirmCommand command(int revision, String title) {
        return new ApplicationConstructionPlanService.ConfirmCommand(UUID.randomUUID().toString(), revision, content(title));
    }
    @Test void revisionsAreImmutableAndConfirmationRetriesRemainIdempotent() {
        var planId = id(); var first = command(0, "一期");
        as("owner", "tenant", () -> {
            assertThat(service.confirm(planId, first).revision()).isEqualTo(1);
            assertThat(service.confirm(planId, command(1, "二期")).revision()).isEqualTo(2);
            assertThat(service.read(planId).revision()).isEqualTo(2);
            assertThat(service.confirm(planId, first).revision()).isEqualTo(1);
            assertThat(service.confirmation(planId, first.requestId()).content().title()).isEqualTo("一期");
            assertThat(service.history(planId)).extracting(ApplicationConstructionPlanService.Snapshot::revision).containsExactly(2, 1);
            assertThatThrownBy(() -> service.confirm(planId, new ApplicationConstructionPlanService.ConfirmCommand(first.requestId(), 0, content("篡改")))).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.confirm(planId, command(1, "过期"))).hasMessageContaining("方案已被修改");
            assertThatThrownBy(() -> service.confirm(planId, command(2, "二期"))).isInstanceOf(IllegalArgumentException.class);
            assertThat(service.read(planId).constructionStatus()).isEqualTo("NOT_STARTED");
            return null;
        });
        as("other", "tenant", () -> {
            assertThat(service.list()).noneMatch(plan -> plan.planId().equals(planId));
            assertThatThrownBy(() -> service.read(planId)).isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
            assertThatThrownBy(() -> service.confirm(planId, first)).isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
            assertThatThrownBy(() -> service.history(planId)).isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
            return null;
        });
        as("owner", "other", () -> {
            assertThatThrownBy(() -> service.confirmation(planId, first.requestId())).isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
            return null;
        });
    }
    @Test void concurrentRevisionsHaveExactlyOneWinner() throws Exception {
        String planId = id(); var winners = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(4)) {
            var tasks = java.util.stream.IntStream.range(0, 4).mapToObj(i -> pool.submit(() -> as("owner", "tenant", () -> {
                try { service.confirm(planId, command(0, "候选" + i)); winners.incrementAndGet(); }
                catch (net.ximatai.muyun.spring.ability.action.BusinessException exception) { /* stale version */ }
                return null;
            }))).toList();
            for (var task : tasks) task.get(10, TimeUnit.SECONDS);
        }
        assertThat(winners).hasValue(1);
        assertThat(as("owner", "tenant", () -> service.history(planId))).hasSize(1);
    }
    @Test void outerRollbackDiscardsHeadAndHistoryAndPageTenantDoesNotOwnPlan() {
        String planId = id(); var command = command(0, "一期");
        as("owner", "tenant", () -> {
            new TransactionTemplate(transactions).execute(status -> {
                service.confirm(planId, command); status.setRollbackOnly(); return null;
            });
            assertThat(service.confirmation(planId, command.requestId())).isNull();
            try (var pageTenant = TenantContext.use("different-page-tenant")) { service.confirm(planId, command); }
            assertThat(service.list()).anyMatch(plan -> plan.planId().equals(planId));
            return null;
        });
        assertThatThrownBy(() -> service.list()).isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
    }
    @Test void deliveredPlansRemainHistoricalAndCannotBeReopenedByRequirementEdits() {
        String planId = id(); var first = command(0, "已建业务");
        as("owner", "tenant", () -> {
            service.confirm(planId, first);
            var receipt = new ApplicationConstructionAcceptance();
            receipt.setId(id()); receipt.setPlanId(planId); receipt.setPlanRevision(1);
            receipt.setObjectKey("order"); receipt.setRequestId(UUID.randomUUID().toString());
            receipt.setBaseline("historical-baseline"); receipt.setRequestDigest("historical-request");
            net.ximatai.muyun.spring.common.model.EntityLifecycle.prepareInsert(receipt, java.time.Instant.now());
            acceptances.insert(receipt);
            var historical = service.read(planId);
            assertThat(historical.constructionStatus()).isEqualTo("DELIVERED");
            assertThat(historical.deliveredObjectKeys()).containsExactly("order");
            assertThatThrownBy(() -> historical.requireOpen("order")).hasMessageContaining("当前低代码治理配置");
            assertThatThrownBy(() -> service.confirm(planId, command(1, "按旧设计再改一版"))).hasMessageContaining("已交付");
            // The original confirmation remains queryable/idempotent after delivery.
            assertThat(service.confirm(planId, first).revision()).isEqualTo(1);
            assertThat(service.history(planId)).hasSize(1);
            return null;
        });
    }
    @Test void partialDeliveryFreezesAcceptedObjectAndRequirementMeaning() {
        String planId = id();
        var original = partialContent();
        as("owner", "tenant", () -> {
            service.confirm(planId, new ApplicationConstructionPlanService.ConfirmCommand(UUID.randomUUID().toString(), 0, original));
            acceptOrder(planId);
            var changedObjects = List.of(new ApplicationConstructionPlanContent.BusinessObject("order", "新订单", "改变用途"), original.objects().get(1));
            var changedBindings = new java.util.ArrayList<>(original.requirements());
            changedBindings.set(0, new ApplicationConstructionRequirement(ApplicationConstructionRequirement.Section.SCOPE,
                    0, "order", ApplicationConstructionRequirement.Mode.REQUIRED, "title", "订单录入", null));
            var withoutOrderBinding = original.requirements().stream().filter(requirement -> !requirement.objectKey().equals("order")).toList();
            var candidates = List.of(
                    revise(original, changedObjects, original.inScope(), original.rules(), original.requirements()),
                    revise(original, List.of(original.objects().get(1)), original.inScope(), original.rules(), withoutOrderBinding),
                    revise(original, original.objects(), List.of("订单必须新增审批", "客户录入"), original.rules(), original.requirements()),
                    revise(original, original.objects(), original.inScope(), List.of("订单编号不再必填"), original.requirements()),
                    revise(original, original.objects(), original.inScope(), original.rules(), changedBindings),
                    revise(original, original.objects(), original.inScope(), original.rules(), withoutOrderBinding));
            for (var candidate : candidates)
                assertThatThrownBy(() -> service.confirm(planId,
                        new ApplicationConstructionPlanService.ConfirmCommand(UUID.randomUUID().toString(), 1, candidate)))
                        .hasMessageContaining("已交付业务对象及其需求不能修改或移除");
            assertThat(service.read(planId).revision()).isEqualTo(1);
            assertThat(service.read(planId).constructionStatus()).isEqualTo("PARTIALLY_DELIVERED");
            return null;
        });
    }

    @Test void partialDeliveryAllowsRemainingWorkAndRequirementIndexReordering() {
        String planId = id();
        var original = partialContent();
        as("owner", "tenant", () -> {
            var first = new ApplicationConstructionPlanService.ConfirmCommand(UUID.randomUUID().toString(), 0, original);
            service.confirm(planId, first);
            acceptOrder(planId);
            var requirements = List.of(
                    new ApplicationConstructionRequirement(ApplicationConstructionRequirement.Section.SCOPE, 0,
                            "customer", ApplicationConstructionRequirement.Mode.REQUIRED, "name", "客户实名登记", null),
                    new ApplicationConstructionRequirement(ApplicationConstructionRequirement.Section.SCOPE, 1,
                            "order", ApplicationConstructionRequirement.Mode.FIELD, "title", "订单录入", null),
                    original.requirements().get(2));
            var candidate = revise(original, List.of(
                    new ApplicationConstructionPlanContent.BusinessObject("customer", "实名客户", "补齐客户登记"), original.objects().getFirst()),
                    List.of("客户实名录入", "订单录入"), original.rules(), requirements);
            var command = new ApplicationConstructionPlanService.ConfirmCommand(UUID.randomUUID().toString(), 1, candidate);
            var updated = service.confirm(planId, command);
            assertThat(updated.revision()).isEqualTo(2);
            assertThat(updated.constructionStatus()).isEqualTo("PARTIALLY_DELIVERED");
            assertThat(updated.deliveredObjectKeys()).containsExactly("order");
            assertThat(service.confirm(planId, command).revision()).isEqualTo(2);
            assertThat(service.confirm(planId, first).content()).isEqualTo(original);
            return null;
        });
    }

    private void acceptOrder(String planId) {
        var receipt = new ApplicationConstructionAcceptance();
        receipt.setId(id()); receipt.setPlanId(planId); receipt.setPlanRevision(1);
        receipt.setObjectKey("order"); receipt.setRequestId(UUID.randomUUID().toString());
        receipt.setBaseline("historical-baseline"); receipt.setRequestDigest("historical-request");
        net.ximatai.muyun.spring.common.model.EntityLifecycle.prepareInsert(receipt, java.time.Instant.now());
        acceptances.insert(receipt);
    }

    private ApplicationConstructionPlanContent partialContent() {
        var original = content("订单与客户");
        return revise(original, List.of(original.objects().getFirst(),
                new ApplicationConstructionPlanContent.BusinessObject("customer", "客户", "记录客户")),
                List.of("订单录入", "客户录入"), List.of("订单编号必填"), List.of(
                        new ApplicationConstructionRequirement(ApplicationConstructionRequirement.Section.SCOPE, 0,
                                "order", ApplicationConstructionRequirement.Mode.FIELD, "title", "订单录入", null),
                        new ApplicationConstructionRequirement(ApplicationConstructionRequirement.Section.SCOPE, 1,
                                "customer", ApplicationConstructionRequirement.Mode.FIELD, "name", "客户录入", null),
                        new ApplicationConstructionRequirement(ApplicationConstructionRequirement.Section.RULE, 0,
                                "order", ApplicationConstructionRequirement.Mode.REQUIRED, "number", "编号必须填写", null)));
    }

    private ApplicationConstructionPlanContent revise(ApplicationConstructionPlanContent original,
            List<ApplicationConstructionPlanContent.BusinessObject> objects, List<String> scope, List<String> rules,
            List<ApplicationConstructionRequirement> requirements) {
        return new ApplicationConstructionPlanContent(original.title(), original.goal(), scope, original.outOfScope(),
                objects, original.relationships(), rules, original.questions(), original.assumptions(), original.decisions(),
                original.acceptanceExamples(), requirements);
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableMuYunRepositories(basePackageClasses = ApplicationConstructionPlanDao.class)
    static class Application {
        @Bean DataSource dataSource() {
            return DataSourceBuilder.create().url(postgres.getJdbcUrl()).username(postgres.getUsername())
                .password(postgres.getPassword()).driverClassName(postgres.getDriverClassName()).build();
        }
        @Bean ApplicationConstructionPlanService plans(ApplicationConstructionPlanDao dao, ApplicationConstructionPlanRevisionDao revisions, ApplicationConstructionInitializationDao initializations, ApplicationConstructionFieldChangeDao fieldChanges, ApplicationConstructionDeliveryDao deliveries, ApplicationConstructionAcceptanceDao acceptances) { return new ApplicationConstructionPlanService(dao, revisions, initializations, fieldChanges, deliveries, acceptances); }
    }
}
