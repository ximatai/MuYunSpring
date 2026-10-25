package net.ximatai.muyun.spring.platform.attachment;

import net.ximatai.muyun.database.spring.boot.sql.annotation.EnableMuYunRepositories;
import net.ximatai.muyun.spring.ability.MutationTransactionOperator;
import net.ximatai.muyun.spring.ability.PlatformAbilityRuntime;
import net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException;
import net.ximatai.muyun.spring.common.identity.CurrentUser;
import net.ximatai.muyun.spring.common.identity.CurrentUserContext;
import net.ximatai.muyun.spring.common.model.EntityLifecycle;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.platform.support.PlatformPostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real repository contracts for exclusive file claims, ticket races and bounded retention. */
@SpringBootTest(classes = FileReferenceUploadIT.Application.class)
class FileReferenceUploadIT extends PlatformPostgresIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("muyun.database.repository-schema-mode", () -> "ENSURE");
    }
    @Autowired FileReferenceUploadDao dao;
    @Autowired FileReferenceUploadClaimDao claims;
    @Autowired DataSource source;
    @Autowired PlatformTransactionManager transactions;
    private JdbcTemplate jdbc;
    private FileTransferClient client;
    private FileReferenceUploadService service;

    @BeforeEach void setup() {
        jdbc = new JdbcTemplate(source);
        jdbc.update("delete from platform_file_reference_upload");
        jdbc.update("delete from platform_file_reference_upload_claim");
        client = mock(FileTransferClient.class);
        var beans = new StaticListableBeanFactory();
        beans.addBean("client", client);
        service = new FileReferenceUploadService(dao, claims, beans.getBeanProvider(ManagedFileAssetService.class),
                beans.getBeanProvider(FileTransferClient.class), Clock.fixed(NOW, ZoneOffset.UTC));
        PlatformAbilityRuntime.configureMutationTransactionOperator(new MutationTransactionOperator() {
            public <T> T execute(Supplier<T> work) { return new TransactionTemplate(transactions).execute(status -> work.get()); }
            public void lock(String scope, String key) {
                jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class, scope + key);
            }
        });
    }

    @AfterEach void cleanup() {
        PlatformAbilityRuntime.resetMutationTransactionOperator();
        CurrentUserContext.clear();
        TenantContext.clear();
    }

    @Test void aFileCanOnlyBeClaimedOnceAcrossTenantsWhileUnboundTicketsCanCoexist() {
        var first = upload("tenant-a", "shared-file");
        dao.insert(first);
        var second = upload("tenant-b", "shared-file");
        assertThatThrownBy(() -> dao.insert(second)).hasStackTraceContaining("duplicate key");
        dao.insert(upload("tenant-a", null));
        dao.insert(upload("tenant-b", null));
        assertThat(jdbc.queryForObject("select count(*) from platform_file_reference_upload", Integer.class)).isEqualTo(3);
        assertThat(dao.findById(first.getId()).getTenantId()).isEqualTo("tenant-a");
    }

    @Test void concurrentBindingsToOneTicketHaveExactlyOneVersionWinner() throws Exception {
        var ticket = upload("tenant-a", null);
        dao.insert(ticket);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> claim(ticket.getId(), "first-file", ready, start));
            var second = pool.submit(() -> claim(ticket.getId(), "second-file", ready, start));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        } finally { start.countDown(); }
        var saved = dao.findById(ticket.getId());
        assertThat(saved.getVersion()).isEqualTo(ticket.getVersion() + 1);
        assertThat(saved.getFileId()).isIn("first-file", "second-file");
    }

    private int claim(String ticketId, String fileId, CountDownLatch ready, CountDownLatch start) throws Exception {
        var snapshot = dao.findById(ticketId);
        Integer version = snapshot.getVersion();
        snapshot.setFileId(fileId);
        EntityLifecycle.prepareUpdate(snapshot, NOW);
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("binding race did not start");
        return dao.updateByIdAndVersion(snapshot, version);
    }

    @Test void cleanupRemovesUsedAndExpiredRegistrationsAcrossTenantsInBatchesOfTwoHundred() {
        jdbc.update("""
                insert into platform_file_reference_upload
                (id, tenant_id, uploader_user_id, module_alias, field_name, expires_at, version, used_at)
                select md5('upload-retention-' || n), case when n % 2 = 0 then 'tenant-a' else 'tenant-b' end,
                'uploader', 'sales.document', 'fileId',
                case when n <= 150 then timestamp '2000-01-01' else timestamp '2100-01-01' end, 0,
                case when n > 150 then timestamp '2026-10-24' else null end
                from generate_series(1, 250) n
                """);
        var retained = upload("tenant-c", null);
        dao.insert(retained);
        try (var ignored = TenantContext.backgroundJob("platform.file-reference-upload-retention")) {
            assertThat(service.purgeExpiredBatch()).isEqualTo(200);
            assertThat(jdbc.queryForObject("select count(*) from platform_file_reference_upload", Integer.class)).isEqualTo(51);
            assertThat(jdbc.queryForObject("select count(*) from platform_file_reference_upload where used_at is not null", Integer.class)).isZero();
            assertThat(service.purgeExpiredBatch()).isEqualTo(50);
            assertThat(service.purgeExpiredBatch()).isZero();
        }
        assertThat(dao.findById(retained.getId())).isNotNull();
    }

    @Test void aDirectUploadCanBeRegisteredOnlyByTheTicketOwnerWhileStillTemporary() {
        String ticketId = as("uploader", () -> service.registerTicket("tenant-a", request(),
                new FileTransferAccess(FileTransferOperation.UPLOAD, null, "issued-token", "http://files/upload", NOW.plusSeconds(600))));
        var ticket = dao.findById(ticketId);
        assertThat(ticket.getTicketIdentifier()).hasSize(64).isNotEqualTo("issued-token");
        assertThat(ticket.getFileId()).isNull();
        assertThat(ticket.getUploaderUserId()).isEqualTo("uploader");
        when(client.readUploadFacts("file-1")).thenReturn(new FileTransferUploadFacts(metadata("file-1", true), "uploader", "tenant-a"));
        assertThatThrownBy(() -> as("other", () -> { bind(ticketId, "file-1"); return null; }))
                .isInstanceOf(PlatformAccessDeniedException.class);
        verifyNoInteractions(client);
        when(client.readUploadFacts("foreign-file")).thenReturn(new FileTransferUploadFacts(metadata("foreign-file", true), "other", "tenant-a"));
        when(client.readUploadFacts("foreign-tenant-file")).thenReturn(new FileTransferUploadFacts(metadata("foreign-tenant-file", true), "uploader", "tenant-b"));
        for (String foreignFile : java.util.List.of("foreign-file", "foreign-tenant-file")) {
            assertThatThrownBy(() -> as("uploader", () -> {
                bind(ticketId, foreignFile); return null;
            })).isInstanceOf(PlatformAccessDeniedException.class);
        }
        assertThat(dao.findById(ticketId).getFileId()).isNull();
        as("uploader", () -> {
            bind(ticketId, "file-1");
            bind(ticketId, "file-1");
            assertThat(service.ownsPending("tenant-a", new FileReferenceReadRequest("sales.document", null,
                    "fileId", Map.of("id", "draft-1"), "file-1", FileTransferOperation.PREVIEW))).isTrue();
            return null;
        });
        assertThat(dao.findById(ticketId).getFileId()).isEqualTo("file-1");
        when(client.readUploadFacts("file-1")).thenReturn(new FileTransferUploadFacts(metadata("file-1", false), "uploader", "tenant-a"));
        assertThatThrownBy(() -> as("uploader", () -> {
            bind(ticketId, "file-1"); return null;
        })).isInstanceOf(PlatformAccessDeniedException.class);
        assertThatThrownBy(() -> as("uploader", () -> service.ownsPending("tenant-a",
                new FileReferenceReadRequest("sales.document", null, "fileId", Map.of("id", "draft-1"),
                        "file-1", FileTransferOperation.DOWNLOAD))))
                .isInstanceOf(PlatformAccessDeniedException.class);
    }

    @Test void expiredTicketsIncludingTheExactDeadlineCannotAuthorizeBindingReadOrSave() {
        @SuppressWarnings("unchecked")
        var ability = (net.ximatai.muyun.spring.ability.CrudAbility<Document>) mock(net.ximatai.muyun.spring.ability.CrudAbility.class);
        when(ability.getModuleAlias()).thenReturn("sales.document");
        for (Instant deadline : java.util.List.of(NOW.minusSeconds(1), NOW)) {
            String fileId = "expired-file-" + deadline.getEpochSecond();
            var ticket = upload("tenant-a", fileId);
            ticket.setTicketIdentifier("issued-ticket-fingerprint");
            ticket.setExpiresAt(deadline);
            dao.insert(ticket);
            var incoming = new Document();
            incoming.setId("draft-1"); incoming.setTenantId("tenant-a"); incoming.fileId = fileId;
            as("uploader", () -> {
                assertThat(service.ownsPending("tenant-a", new FileReferenceReadRequest("sales.document", null,
                        "fileId", Map.of("id", "draft-1"), fileId, FileTransferOperation.PREVIEW))).isFalse();
                assertThatThrownBy(() -> bind(ticket.getId(), fileId))
                        .isInstanceOf(PlatformAccessDeniedException.class);
                assertThatThrownBy(() -> service.prepareValues(ability, null, incoming))
                        .isInstanceOf(PlatformAccessDeniedException.class);
                return null;
            });
        }
        verifyNoInteractions(client);
    }

    @Test void purgingAnExpiredRegistrationNeverReleasesItsPermanentFileClaim() {
        String first = ticket("first-token");
        when(client.readUploadFacts("exclusive-file")).thenReturn(new FileTransferUploadFacts(metadata("exclusive-file", true), "uploader", "tenant-a"));
        as("uploader", () -> { bind(first, "exclusive-file"); return null; });
        jdbc.update("update platform_file_reference_upload set expires_at = ? where id = ?", java.sql.Timestamp.from(NOW), first);
        try (var ignored = TenantContext.backgroundJob("platform.file-reference-upload-retention")) {
            assertThat(service.purgeExpiredBatch()).isEqualTo(1);
        }
        assertThat(dao.findById(first)).isNull();
        assertThat(claims.findById("exclusive-file")).isNotNull();
        String second = ticket("second-token");
        assertThatThrownBy(() -> as("uploader", () -> { bind(second, "exclusive-file"); return null; }))
                .isInstanceOf(PlatformAccessDeniedException.class);
        assertThat(dao.findById(second).getFileId()).isNull();
        assertThat(claims.findById("exclusive-file").getRegistrationId()).isEqualTo(first);
    }

    @Test void twoTicketsRacingForOneFileLeaveExactlyOnePermanentClaimAndBinding() throws Exception {
        String first = ticket("first-token");
        String second = ticket("second-token");
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        when(client.readUploadFacts("exclusive-file")).thenAnswer(invocation -> {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("claim race did not start");
            return new FileTransferUploadFacts(metadata("exclusive-file", true), "uploader", "tenant-a");
        });
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> attemptBinding(first));
            var two = pool.submit(() -> attemptBinding(second));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(one.get(10, TimeUnit.SECONDS) + two.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        } finally { start.countDown(); }
        String winner = claims.findById("exclusive-file").getRegistrationId();
        assertThat(winner).isIn(first, second);
        assertThat(dao.findById(winner).getFileId()).isEqualTo("exclusive-file");
        assertThat(dao.findById(winner.equals(first) ? second : first).getFileId()).isNull();
        assertThat(jdbc.queryForObject("select count(*) from platform_file_reference_upload_claim", Integer.class)).isEqualTo(1);
    }

    private int attemptBinding(String ticket) {
        try { return as("uploader", () -> { bind(ticket, "exclusive-file"); return 1; }); }
        catch (RuntimeException failure) {
            assertThat(failure).isInstanceOf(PlatformAccessDeniedException.class);
            return 0;
        }
    }

    private String ticket(String token) {
        return as("uploader", () -> service.registerTicket("tenant-a", request(),
                new FileTransferAccess(FileTransferOperation.UPLOAD, null, token, "http://files/upload", NOW.plusSeconds(600))));
    }

    private void bind(String ticket, String file) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> service.bindUploadedFile("sales.document", ticket, file));
    }

    private static class Document extends net.ximatai.muyun.spring.common.model.standard.StandardEntity {
        @net.ximatai.muyun.spring.common.model.file.FileReference
        private String fileId;
    }

    private FileReferenceUpload upload(String tenant, String file) {
        var row = new FileReferenceUpload();
        row.setTenantId(tenant); row.setFileId(file); row.setUploaderUserId("uploader");
        row.setModuleAlias("sales.document"); row.setFieldName("fileId"); row.setRecordId("draft-1");
        row.setExpiresAt(NOW.plusSeconds(600));
        EntityLifecycle.prepareInsert(row, NOW);
        return row;
    }

    private FileReferenceUploadRequest request() {
        return new FileReferenceUploadRequest("sales.document", null, "fileId", Map.of("id", "draft-1"),
                new FileReferenceUploadFile("manual.pdf", "application/pdf", 10), FileReferenceUploadIntent.CREATE);
    }

    private FileTransferFileMetadata metadata(String file, boolean temporary) {
        return new FileTransferFileMetadata(file, "manual.pdf", "pdf", "application/pdf", 10, null,
                "ACTIVE", temporary, NOW, null, null);
    }

    private <T> T as(String user, Supplier<T> work) {
        try (var actor = CurrentUserContext.use(CurrentUser.tenantUser(user, user, "tenant-a"));
             var tenant = TenantContext.use("tenant-a")) { return work.get(); }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableMuYunRepositories(basePackageClasses = FileReferenceUploadDao.class)
    static class Application {
        @Bean DataSource dataSource() {
            return DataSourceBuilder.create().url(postgres.getJdbcUrl()).username(postgres.getUsername())
                    .password(postgres.getPassword()).driverClassName(postgres.getDriverClassName()).build();
        }
    }
}
