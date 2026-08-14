package net.ximatai.muyun.spring.platform.audit;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.spring.ability.event.RuntimeEvent;
import net.ximatai.muyun.spring.ability.event.RuntimeEventType;
import net.ximatai.muyun.spring.ability.event.RuntimeMutationSource;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.platform.support.PlatformPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = RuntimeAuditRecordRepositoryIT.TestApplication.class)
class RuntimeAuditRecordRepositoryIT extends PlatformPostgresIntegrationTest {

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }

    private final RuntimeAuditRecordService service;
    private final RuntimeAuditEventListener listener;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    RuntimeAuditRecordRepositoryIT(RuntimeAuditRecordService service,
                                   RuntimeAuditEventListener listener,
                                   PlatformTransactionManager transactionManager) {
        this.service = service;
        this.listener = listener;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Test
    void shouldPersistRuntimeAuditRecordThroughRepository() {
        listener.onRuntimeEvent(event("audit-it-event-1"));

        RuntimeAuditRecord record = service.list(Criteria.of().eq("eventId", "audit-it-event-1"),
                        PageRequest.of(1, 10))
                .getFirst();
        assertThat(record.getTenantId()).isEqualTo("tenant-it");
        assertThat(record.getEventType()).isEqualTo(RuntimeEventType.MODULE_REFRESHED);
        assertThat(record.getModuleAlias()).isEqualTo("sales.contract");
        assertThat(record.getEntityAlias()).isNull();
        assertThat(record.getPayloadText()).contains("changed=true");
        assertThat(record.getMutationSource()).isEqualTo(RuntimeMutationSource.SYSTEM);
        assertThat(record.getSystemContext()).isTrue();
        assertThat(record.getSystemReason()).isEqualTo("repository bootstrap");
    }

    @Test
    void shouldPersistActionResultColumnsThroughRepository() {
        listener.onRuntimeEvent(actionEvent("audit-it-action-event"));

        RuntimeAuditRecord record = service.list(Criteria.of().eq("eventId", "audit-it-action-event"),
                        PageRequest.of(1, 10))
                .getFirst();
        assertThat(record.getEventType()).isEqualTo(RuntimeEventType.ACTION_EXECUTED);
        assertThat(record.getActionCode()).isEqualTo("submit");
        assertThat(record.getExecutorType()).isEqualTo("SERVICE");
        assertThat(record.getActionLevel()).isEqualTo("RECORD");
        assertThat(record.getResultType()).isEqualTo("VALUE");
        assertThat(record.getResultMessage()).isEqualTo("提交成功");
        assertThat(record.getRefreshRequested()).isTrue();
        assertThat(record.getRedirectTo()).hasSizeGreaterThan(512);
        assertThat(record.getResultText()).isEqualTo("submitted");
    }

    @Test
    void shouldPersistActionFailureAuditWhenOuterTransactionRollsBack() {
        transactionTemplate.executeWithoutResult(status -> {
            listener.onRuntimeEvent(actionFailedEvent("audit-it-action-failed-rollback"));
            status.setRollbackOnly();
        });

        RuntimeAuditRecord record = service.list(Criteria.of().eq("eventId", "audit-it-action-failed-rollback"),
                        PageRequest.of(1, 10))
                .getFirst();
        assertThat(record.getEventType()).isEqualTo(RuntimeEventType.ACTION_FAILED);
        assertThat(record.getActionCode()).isEqualTo("submit");
        assertThat(record.getExecutorType()).isEqualTo("SERVICE");
        assertThat(record.getActionLevel()).isEqualTo("RECORD");
        assertThat(record.getFailureStage()).isEqualTo("execute");
        assertThat(record.getErrorMessage()).isEqualTo("submit failed");
        assertThat(record.getErrorType()).isEqualTo(IllegalStateException.class.getName());
    }

    @Test
    void shouldRejectDuplicateRuntimeEventIdThroughRepositoryService() {
        RuntimeEvent event = event("audit-it-event-duplicate");
        listener.onRuntimeEvent(event);

        assertThatThrownBy(() -> listener.onRuntimeEvent(event))
                .isInstanceOf(PlatformException.class)
                .hasMessageContaining("Runtime audit eventId must be unique");
    }

    private RuntimeEvent event(String eventId) {
        return new RuntimeEvent(
                eventId,
                "audit-it-trace",
                RuntimeEventType.MODULE_REFRESHED,
                "sales.contract",
                null,
                null,
                null,
                "tenant-it",
                true,
                "repository bootstrap",
                RuntimeMutationSource.SYSTEM,
                Map.of("changed", true),
                Instant.parse("2026-06-02T05:00:00Z")
        );
    }

    private RuntimeEvent actionEvent(String eventId) {
        return new RuntimeEvent(
                eventId,
                "audit-it-action-trace",
                RuntimeEventType.ACTION_EXECUTED,
                "sales.contract",
                "contract",
                "contract-it-1",
                "submit",
                "tenant-it",
                false,
                RuntimeMutationSource.ACTION,
                Map.of(
                        "executorType", "SERVICE",
                        "actionLevel", "RECORD",
                        "resultType", "VALUE",
                        "message", "提交成功",
                        "refresh", true,
                        "redirectTo", "/contracts/" + "x".repeat(600),
                        "result", "submitted"
                ),
                Instant.parse("2026-06-02T05:05:00Z")
        );
    }

    private RuntimeEvent actionFailedEvent(String eventId) {
        return new RuntimeEvent(
                eventId,
                "audit-it-action-failed-trace",
                RuntimeEventType.ACTION_FAILED,
                "sales.contract",
                "contract",
                "contract-it-2",
                "submit",
                "tenant-it",
                false,
                RuntimeMutationSource.ACTION,
                Map.of(
                        "executorType", "SERVICE",
                        "actionLevel", "RECORD",
                        "available", true,
                        "failureStage", "execute",
                        "errorMessage", "submit failed",
                        "errorType", IllegalStateException.class.getName()
                ),
                Instant.parse("2026-06-02T05:10:00Z")
        );
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableMuYunRepositories(basePackageClasses = RuntimeAuditRecordDao.class)
    static class TestApplication {
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
        RuntimeAuditRecordService runtimeAuditRecordService(RuntimeAuditRecordDao auditRecordDao) {
            return new RuntimeAuditRecordService(auditRecordDao);
        }

        @Bean
        RuntimeAuditEventListener runtimeAuditEventListener(RuntimeAuditRecordService service) {
            return new RuntimeAuditEventListener(service);
        }
    }
}
