package net.ximatai.muyun.spring.iam.logging;

import net.ximatai.muyun.spring.ability.logging.ActionLogDetails;
import net.ximatai.muyun.spring.ability.logging.ActionLogEvent;
import net.ximatai.muyun.spring.ability.logging.ActionLogStatistics;
import net.ximatai.muyun.spring.ability.logging.BusinessLogContext;
import net.ximatai.muyun.spring.ability.logging.BusinessLogEvent;
import net.ximatai.muyun.spring.ability.logging.BusinessLogPublisher;
import net.ximatai.muyun.spring.ability.logging.BusinessLogQuery;
import net.ximatai.muyun.spring.ability.logging.BusinessLogReadPage;
import net.ximatai.muyun.spring.ability.logging.BusinessLogReadScope;
import net.ximatai.muyun.spring.ability.logging.BusinessLogReadScopeResolver;
import net.ximatai.muyun.spring.ability.logging.BusinessLogStatisticsQuery;
import net.ximatai.muyun.spring.ability.logging.BusinessLogStatisticsReader;
import net.ximatai.muyun.spring.ability.logging.BusinessLogStore;
import net.ximatai.muyun.spring.ability.logging.BusinessLogWriteResult;
import net.ximatai.muyun.spring.ability.logging.LoginLogDetails;
import net.ximatai.muyun.spring.ability.logging.LoginLogEvent;
import net.ximatai.muyun.spring.ability.logging.PageAccessLogStatistics;
import net.ximatai.muyun.spring.platform.logging.BusinessLogGovernanceService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class LoginAuditGovernanceServiceTest {
    @Test
    void shouldResolveRangeFromExactActionAndForceLoginFacts() {
        LoginLogEvent login = new LoginLogEvent(context("login"), new LoginLogDetails(
                "password", LoginLogDetails.LoginOutcome.SUCCESS, null, null, "alice", "alice"));
        ActionLogEvent action = new ActionLogEvent(context("action"), new ActionLogDetails(
                ActionLogDetails.ActionOutcome.SUCCESS, "SERVICE", 1L, null, null, null));
        InMemoryStore store = new InMemoryStore(List.of(login, action));
        RecordingScopeResolver scopeResolver = new RecordingScopeResolver();
        LoginAuditGovernanceService service = new LoginAuditGovernanceService(
                new BusinessLogGovernanceService(store, new NoopStatisticsReader()), scopeResolver);

        BusinessLogReadPage page = service.query(BusinessLogQuery.newest(20));
        Optional<BusinessLogEvent> detail = service.findDetail("login");

        assertThat(page.events()).containsExactly(login);
        assertThat(detail).contains(login);
        assertThat(scopeResolver.actions).containsExactly(
                LoginAuditGovernanceService.MODULE_ALIAS + ":" + LoginAuditGovernanceService.QUERY_ACTION_CODE,
                LoginAuditGovernanceService.MODULE_ALIAS + ":" + LoginAuditGovernanceService.DETAIL_ACTION_CODE);
        assertThat(store.lastQuery.eventTypes()).containsExactly(login.eventType());
    }

    private static BusinessLogContext context(String eventId) {
        return new BusinessLogContext(eventId, Instant.parse("2026-09-12T00:00:00Z"), Instant.now(), null,
                "tenant-a", "user-1", "organization-a", "iam.user", "login");
    }

    private static final class RecordingScopeResolver implements BusinessLogReadScopeResolver {
        private final List<String> actions = new java.util.ArrayList<>();

        @Override
        public BusinessLogReadScope resolve(String moduleAlias, String actionCode) {
            actions.add(moduleAlias + ":" + actionCode);
            return BusinessLogReadScope.platform();
        }
    }

    private static final class InMemoryStore implements BusinessLogStore {
        private final List<BusinessLogEvent> events;
        private BusinessLogQuery lastQuery;

        private InMemoryStore(List<BusinessLogEvent> events) {
            this.events = List.copyOf(events);
        }

        @Override
        public BusinessLogWriteResult append(BusinessLogEvent event) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<BusinessLogWriteResult> appendAll(Collection<? extends BusinessLogEvent> events) {
            throw new UnsupportedOperationException();
        }

        @Override
        public BusinessLogReadPage read(BusinessLogQuery query) {
            lastQuery = query;
            return new BusinessLogReadPage(events.stream()
                    .filter(event -> query.eventTypes() == null || query.eventTypes().contains(event.eventType()))
                    .toList(), null);
        }

        @Override
        public Optional<BusinessLogEvent> findById(String eventId) {
            return events.stream().filter(event -> event.eventId().equals(eventId)).findFirst();
        }
    }

    private static final class NoopStatisticsReader implements BusinessLogStatisticsReader {
        @Override
        public ActionLogStatistics actionStatistics(BusinessLogStatisticsQuery query) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PageAccessLogStatistics pageAccessStatistics(BusinessLogStatisticsQuery query) {
            throw new UnsupportedOperationException();
        }
    }
}
