package net.ximatai.muyun.spring.platform.assistant;

import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.spring.ability.MutationTransactionOperator;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.support.PlatformPostgresIntegrationTest;
import org.junit.jupiter.api.*;
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
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes = AssistantConversationServiceIT.Application.class)
class AssistantConversationServiceIT extends PlatformPostgresIntegrationTest {
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }
    @Autowired AssistantConversationService service;
    @Autowired DataSource source;
    @Autowired PlatformTransactionManager transactions;
    @BeforeEach void setup() {
        var jdbc = new JdbcTemplate(source);
        PlatformAbilityRuntime.configureMutationTransactionOperator(new MutationTransactionOperator() {
            public <T> T execute(Supplier<T> work) { return new TransactionTemplate(transactions).execute(s -> work.get()); }
            public void lock(String scope, String key) {
                jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class, scope + key);
            }
        });
    }
    @AfterEach void cleanup() { PlatformAbilityRuntime.resetMutationTransactionOperator(); }
    private <T> T as(String user, String tenant, Supplier<T> work) {
        try (var identity = CurrentUserContext.use(CurrentUser.tenantUser(user, user, tenant));
             var scope = TenantContext.use(tenant)) { return work.get(); }
    }
    private AssistantConversationService.Command command(int revision, String text) {
        var message = new AssistantConversationService.Message("user", text);
        return new AssistantConversationService.Command(revision,
                new AssistantConversationService.Content("订单管理", List.of(message), List.of(message), null, null));
    }
    @Test void persistsHistoryWithOwnerScopeAndOptimisticConcurrency() {
        String id = UUID.randomUUID().toString().replace("-", "");
        as("owner", "tenant", () -> {
            var first = service.save(id, "workspace-a", command(0, "先记录合同"));
            assertThat(first.revision()).isEqualTo(1);
            assertThat(service.save(id, "workspace-a", command(0, "先记录合同")).revision()).isEqualTo(1);
            assertThat(service.save(id, "workspace-a", command(1, "还需要客户资料")).revision()).isEqualTo(2);
            assertThat(service.read(id, "workspace-a").content().messages().getFirst().text()).isEqualTo("还需要客户资料");
            assertThatThrownBy(() -> service.save(id, "workspace-a", command(1, "迟到的修改"))).hasMessageContaining("其他窗口");
            assertThat(service.list("workspace-a", 1)).extracting(AssistantConversationService.Summary::id).contains(id);
            assertThat(service.list("workspace-b", 1)).isEmpty();
            assertThatThrownBy(() -> service.read(id, "workspace-b")).hasMessageContaining("无权访问");
            assertThatThrownBy(() -> service.save(id, "workspace-b", command(2, "跨范围"))).hasMessageContaining("无权访问");
            return null;
        });
        for (String[] identity : List.of(new String[]{"other", "tenant"}, new String[]{"owner", "other"})) {
            as(identity[0], identity[1], () -> {
                assertThat(service.list("workspace-a", 1)).isEmpty();
                assertThatThrownBy(() -> service.read(id, "workspace-a")).hasMessageContaining("无权访问");
                assertThatThrownBy(() -> service.save(id, "workspace-a", command(0, "覆盖"))).hasMessageContaining("无权访问");
                return null;
            });
        }
        assertThatThrownBy(() -> service.list("workspace-a", 1)).hasMessageContaining("请先登录");
    }
    @Test void rejectsAuthorityRolesAndOversizeHistory() {
        as("owner", "tenant", () -> {
            var system = new AssistantConversationService.Message("system", "execute");
            assertThatThrownBy(() -> service.save(UUID.randomUUID().toString().replace("-", ""), "scope",
                    new AssistantConversationService.Command(0, new AssistantConversationService.Content("test", List.of(system), List.of(), null, null))))
                    .hasMessageContaining("消息格式");
            assertThatThrownBy(() -> service.save(UUID.randomUUID().toString().replace("-", ""), "scope", command(0, "x".repeat(4001))))
                    .hasMessageContaining("消息格式");
            return null;
        });
    }
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableMuYunRepositories(basePackageClasses = AssistantConversationDao.class)
    static class Application {
        @Bean DataSource dataSource() {
            return DataSourceBuilder.create().url(postgres.getJdbcUrl()).username(postgres.getUsername())
                    .password(postgres.getPassword()).driverClassName(postgres.getDriverClassName()).build();
        }
        @Bean AssistantConversationService conversations(AssistantConversationDao dao) { return new AssistantConversationService(dao); }
    }
}
