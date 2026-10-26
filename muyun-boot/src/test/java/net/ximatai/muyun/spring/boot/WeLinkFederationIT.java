package net.ximatai.muyun.spring.boot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import net.ximatai.muyun.database.core.orm.*;
import net.ximatai.muyun.spring.common.identity.*;
import net.ximatai.muyun.spring.common.tenant.TenantContext;
import net.ximatai.muyun.spring.iam.federation.*;
import net.ximatai.muyun.spring.iam.organization.*;
import net.ximatai.muyun.spring.iam.position.*;
import net.ximatai.muyun.spring.iam.tenant.*;
import net.ximatai.muyun.spring.iam.user.*;
import net.ximatai.muyun.spring.platform.job.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

/** Real vendor HTTP → directory job/IAM persistence → one-time login transaction/session. */
@Testcontainers
@SpringBootTest(classes = MuYunSpringApplication.class)
class WeLinkFederationIT {
    @Container static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
    static final HttpServer vendor;
    static final AtomicInteger exchanges = new AtomicInteger();
    static final AtomicBoolean departed = new AtomicBoolean();
    static {
        try {
            vendor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            vendor.createContext("/", request -> {
                String path = request.getRequestURI().getPath();
                String body = switch (path) {
                    case "/api/auth/v2/tickets" -> "{\"code\":0,\"access_token\":\"application-ticket\",\"expires_in\":7200}";
                    case "/api/contact/v2/department/detail" -> request.getRequestURI().getQuery().contains("deptCode=team")
                            ? "{\"code\":0,\"data\":{\"deptCode\":\"team\",\"parentCode\":\"root\",\"deptNameCn\":\"Team\"}}"
                            : "{\"code\":0,\"data\":{\"deptCode\":\"root\",\"deptNameCn\":\"Company\"}}";
                    case "/api/contact/v2/department/list" -> "{\"code\":0,\"hasMore\":0,\"data\":[{\"deptCode\":\"team\",\"parentCode\":\"root\",\"deptNameCn\":\"Team\"}]}";
                    case "/api/contact/v2/user/list" -> request.getRequestURI().getQuery().contains("deptCode=team")
                            ? "{\"code\":0,\"data\":[{\"userId\":\"alice\",\"userNameCn\":\"Alice\",\"employeeId\":\"WL001\",\"mainDeptCode\":\"team\",\"deptCodes\":[\"team\"],\"userStatus\":" + (departed.get() ? "6" : "3") + "}]}"
                            : "{\"code\":0,\"data\":[]}";
                    case "/api/oauth2/v1/token" -> exchanges.incrementAndGet() == 1
                            ? "{\"message\":\"success\",\"access_token\":\"user-token\"}" : "{\"message\":\"already redeemed\"}";
                    case "/api/auth/v1/userid" -> "{\"code\":0,\"userId\":\"alice\"}";
                    default -> "{\"code\":\"unknown\"}";
                };
                if (!path.endsWith("/tickets") && !path.endsWith("/token")) {
                    assertThat(request.getRequestHeaders().getFirst("x-wlk-Authorization"))
                            .isEqualTo(path.endsWith("/userid") ? "user-token" : "application-ticket");
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                request.sendResponseHeaders(200, bytes.length);
                request.getResponseBody().write(bytes);
                request.close();
            });
            vendor.start();
        } catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
    @AfterAll static void closeVendor() { vendor.stop(0); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", postgres::getJdbcUrl);
        p.add("spring.datasource.username", postgres::getUsername);
        p.add("spring.datasource.password", postgres::getPassword);
        p.add("muyun.database.repository-schema-mode", () -> "ENSURE");
        p.add("muyun.runtime.mode", () -> "development");
        p.add("muyun.iam.federation.enabled", () -> "true");
        p.add("muyun.iam.federation.welink.enabled", () -> "true");
        p.add("muyun.iam.federation.workbench-url", () -> "https://workbench.test/app/");
        p.add("muyun.iam.federation.api-base-url", () -> "https://platform.test/api/");
    }
    @Autowired net.ximatai.muyun.spring.starter.configuration.platform.JobNodeExecutor executor;
    @Autowired TenantService tenants;
    @Autowired IdentityProviderService providers;
    @Autowired TenantIdentityPolicyService policies;
    @Autowired OrganizationService organizations;
    @Autowired PositionCategoryService categories;
    @Autowired PositionService positions;
    @Autowired ExternalIdentityService identities;
    @Autowired ExternalLoginService logins;
    @Autowired JobService jobs;
    @Autowired JobRunDao jobRows;
    @Autowired DirectorySyncRunDao syncRows;
    @Autowired UserSessionDao sessions;
    @Autowired net.ximatai.muyun.spring.iam.department.DepartmentService departments;

    @Test void synchronizesThenLogsInAndRevokesAfterDeparture() throws Exception {
        executor.stop(); // Deterministic claims below; retain real job services, leases and handlers.
        try (var current = CurrentUserContext.use(CurrentUser.systemUser("welink-it", "welink-it"));
             var system = TenantContext.system("WeLink integration fixture")) {
            var tenant = new Tenant();
            tenant.setAlias("welink_it"); tenant.setTitle("WeLink tenant"); tenant.setEnabled(true);
            tenants.insert(tenant);
            var provider = new IdentityProvider();
            provider.setId("welink_it"); provider.setTitle("WeLink"); provider.setProviderType("welink");
            provider.setIdentityNamespace("welink-test-app"); provider.setExternalEnterpriseId("test-enterprise");
            provider.setClientId("test-client"); provider.setClientSecretEnvironmentVariable("MUYUN_WELINK_IT_SECRET");
            provider.setConfiguration(new ObjectMapper().writeValueAsString(Map.of("apiBaseUrl",
                    "http://127.0.0.1:" + vendor.getAddress().getPort(), "departedStatuses", "6")));
            provider.setEnabled(true); providers.insert(provider);
            try (var scope = TenantContext.use(tenant.getId())) {
                var org = new Organization(); org.setCode("HQ"); org.setTitle("Headquarters"); organizations.insert(org);
                var category = new PositionCategory(); category.setCode("directory"); category.setTitle("Directory"); categories.insert(category);
                var position = new Position(); position.setCode("member"); position.setTitle("Member"); position.setCategoryId(category.getId()); positions.insert(position);
                var policy = new TenantIdentityPolicy(); policy.setProviderId(provider.getId()); policy.setEnabled(true);
                policy.setLoginEnabled(true); policy.setSyncEnabled(true); policy.setSyncScopeConfirmed(true);
                policy.setSyncPositionId(position.getId()); policy.setSyncExpression("0 0 * * * *");
                policy.setSyncRoots(new ObjectMapper().writeValueAsString(List.of(new DirectorySyncConfiguration.Root("root", org.getId()))));
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                policies.insert(policy);
                String run = policies.synchronize(policy.getId(), false);
                assertThat(jobs.runOne(DirectorySyncJob.CHANNEL, "welink-it")).isTrue();
                assertThat(jobRows.findById(run).getResultCode())
                        .as("Sync diagnostics: %s", syncRows.query(Criteria.of().eq("jobRunId", run), new PageRequest(0, 1))
                                .stream().map(row -> row.getFailedPages() + " " + row.getStatistics()).toList())
                        .isEqualTo("DIRECTORY_COMPLETED");
                var binding = identities.list(Criteria.of().eq("providerId", provider.getId()), new PageRequest(0, 10)).getFirst();
                assertThat(binding.getExternalEnterpriseId()).isEqualTo("test-enterprise");
                assertThat(binding.getSourceStatus()).isEqualTo("active");
                var department = departments.listRaw(Criteria.of(), new PageRequest(0, 1)).getFirst();
                // Internal source commands must not turn ordinary background mutations into authorized writes.
                try (var background = TenantContext.tenantSystem(tenant.getId(), "test.other-job");
                     var noActor = CurrentUserContext.without()) {
                    assertThatThrownBy(() -> departments.update(department))
                            .isInstanceOf(net.ximatai.muyun.spring.common.exception.PlatformAccessDeniedException.class);
                }
                var start = logins.startLogin(tenant.getId(), provider.getId());
                assertThat(start.qrCode().providerType()).isEqualTo("welink");
                var completion = logins.complete(provider.getId(), start.state(), start.browserProof(),
                        Map.of("code", "one-time-code", "state", start.state()), "127.0.0.1", "WeLink IT");
                assertThat(completion.status()).isEqualTo(ExternalLoginStatus.SUCCEEDED);
                assertThat(completion.login().currentUser().userId()).isEqualTo(binding.getUserId());
                assertThatThrownBy(() -> logins.complete(provider.getId(), start.state(), start.browserProof(),
                        Map.of("code", "one-time-code", "state", start.state()), "127.0.0.1", "WeLink IT"));
                assertThat(exchanges).hasValue(1);
                String repeatedRun = policies.synchronize(policy.getId(), false);
                assertThat(jobs.runOne(DirectorySyncJob.CHANNEL, "welink-it")).isTrue();
                assertThat(jobRows.findById(repeatedRun).getResultCode()).isEqualTo("DIRECTORY_COMPLETED");
                assertThat(identities.list(Criteria.of().eq("providerId", provider.getId()), new PageRequest(0, 10)))
                        .hasSize(1);
                departed.set(true);
                String departureRun = policies.synchronize(policy.getId(), false);
                assertThat(jobs.runOne(DirectorySyncJob.CHANNEL, "welink-it")).isTrue();
                assertThat(jobRows.findById(departureRun).getResultCode())
                        .as("Departure diagnostics: %s", syncRows.query(Criteria.of().eq("jobRunId", departureRun), new PageRequest(0, 1))
                                .stream().map(row -> row.getFailedPages() + " " + row.getStatistics()).toList())
                        .isEqualTo("DIRECTORY_COMPLETED");
                assertThat(identities.select(binding.getId()).getSourceStatus()).isEqualTo("departed");
                assertThat(sessions.query(Criteria.of().eq("userId", binding.getUserId()), new PageRequest(0, 10)))
                        .allSatisfy(session -> assertThat(session.getRevokedAt()).isNotNull());
            }
        }
    }
}
