package net.ximatai.muyun.spring.platform.logging;

import net.ximatai.muyun.spring.ability.logging.ActionLogDetails;
import net.ximatai.muyun.spring.ability.logging.ActionLogEvent;
import net.ximatai.muyun.spring.ability.logging.ActionLogStatistics;
import net.ximatai.muyun.spring.ability.logging.BusinessLogContext;
import net.ximatai.muyun.spring.ability.logging.BusinessLogCursor;
import net.ximatai.muyun.spring.ability.logging.BusinessLogEvent;
import net.ximatai.muyun.spring.ability.logging.BusinessLogEventType;
import net.ximatai.muyun.spring.ability.logging.BusinessLogQuery;
import net.ximatai.muyun.spring.ability.logging.BusinessLogPageRequest;
import net.ximatai.muyun.spring.ability.logging.BusinessLogPageResult;
import net.ximatai.muyun.spring.ability.logging.BusinessLogOperatorCandidate;
import net.ximatai.muyun.spring.ability.logging.BusinessLogOperatorCandidatePage;
import net.ximatai.muyun.spring.ability.logging.BusinessLogOperatorCandidateQuery;
import net.ximatai.muyun.spring.ability.logging.BusinessLogOperatorNavigation;
import net.ximatai.muyun.spring.ability.logging.BusinessLogOperatorNavigationItem;
import net.ximatai.muyun.spring.ability.logging.BusinessLogReadPage;
import net.ximatai.muyun.spring.ability.logging.BusinessLogReadScope;
import net.ximatai.muyun.spring.ability.logging.BusinessLogStatisticsQuery;
import net.ximatai.muyun.spring.ability.logging.BusinessLogStatisticsReader;
import net.ximatai.muyun.spring.ability.logging.BusinessLogStore;
import net.ximatai.muyun.spring.ability.logging.BusinessLogWriteResult;
import net.ximatai.muyun.spring.ability.logging.LogText;
import net.ximatai.muyun.spring.ability.logging.PageAccessLogDetails;
import net.ximatai.muyun.spring.ability.logging.PageAccessLogEvent;
import net.ximatai.muyun.spring.ability.logging.PageAccessLogStatistics;
import net.ximatai.muyun.spring.ability.logging.RequestErrorLogDetails;
import net.ximatai.muyun.spring.ability.logging.RequestErrorLogEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BusinessLogGovernanceServiceTest {
    @Test
    void shouldForceBusinessActivityTypesAndKeepCallerFiltersNarrowing() {
        InMemoryStore store = new InMemoryStore(List.of(
                action("action", "tenant", "organization"),
                page("page", "tenant", "organization"),
                requestError("error", "tenant", "organization")
        ));
        CapturingStatisticsReader statistics = new CapturingStatisticsReader();
        BusinessLogGovernanceService service = new BusinessLogGovernanceService(store, statistics);

        BusinessLogReadPage page = service.queryBusinessActivities(new BusinessLogQuery(null, null, null,
                Set.of(BusinessLogEventType.ACTION, BusinessLogEventType.LOGIN), null, null, null,
                null, null, null, 20), BusinessLogReadScope.platform());

        assertThat(page.events()).extracting(BusinessLogEvent::eventId).containsExactly("action");
        assertThat(store.lastQuery.eventTypes()).containsExactly(BusinessLogEventType.ACTION);

        BusinessLogReadPage errors = service.queryRequestErrors(new BusinessLogQuery(null, null, null,
                Set.of(BusinessLogEventType.REQUEST_ERROR, BusinessLogEventType.PAGE_ACCESS), null, null,
                null, null, null, null, 20), BusinessLogReadScope.platform());

        assertThat(errors.events()).extracting(BusinessLogEvent::eventId).containsExactly("error");
        assertThat(store.lastQuery.eventTypes()).containsExactly(BusinessLogEventType.REQUEST_ERROR);
    }

    @Test
    void shouldRestrictOrganizationQueriesToRecordedOrganizationSnapshot() {
        InMemoryStore store = new InMemoryStore(List.of(
                action("visible", "tenant", "organization-a"),
                page("other-organization", "tenant", "organization-b"),
                action("missing-organization", "tenant", null),
                action("other-tenant", "tenant-other", "organization-a")
        ));
        BusinessLogGovernanceService service = new BusinessLogGovernanceService(store,
                new CapturingStatisticsReader());
        BusinessLogReadScope scope = BusinessLogReadScope.organization("tenant", Set.of("organization-a"));

        BusinessLogReadPage page = service.queryBusinessActivities(BusinessLogQuery.newest(20), scope);

        assertThat(page.events()).extracting(BusinessLogEvent::eventId).containsExactly("visible");
        assertThat(store.lastQuery.tenantId()).isEqualTo("tenant");
        assertThat(store.lastQuery.operatorOrganizationIds()).containsExactly("organization-a");
    }

    @Test
    void shouldReadAStandardPageThroughCursorStorageWithoutWideningScope() {
        InMemoryStore store = new InMemoryStore(List.of(
                action("first", "tenant", "organization-a"),
                action("second", "tenant", "organization-a"),
                action("other", "tenant", "organization-b")
        ));
        BusinessLogGovernanceService service = new BusinessLogGovernanceService(store,
                new CapturingStatisticsReader());

        BusinessLogPageResult page = service.queryBusinessActivitiesPage(BusinessLogQuery.newest(200),
                BusinessLogReadScope.organization("tenant", Set.of("organization-a")),
                new BusinessLogPageRequest(1, 2));

        assertThat(page.events()).extracting(BusinessLogEvent::eventId).containsExactly("first", "second");
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.totalKnown()).isTrue();
        assertThat(store.lastQuery.operatorOrganizationIds()).containsExactly("organization-a");
    }

    @Test
    void shouldDeriveOperatorCandidatesFromTheSameScopedActivityFacts() {
        InMemoryStore store = new InMemoryStore(List.of(
                action("visible", "tenant", "organization-a"),
                action("other", "tenant", "organization-b"),
                requestError("error", "tenant", "organization-a")
        ));
        BusinessLogGovernanceService service = new BusinessLogGovernanceService(store,
                new CapturingStatisticsReader());

        BusinessLogOperatorCandidatePage candidates = service.queryBusinessActivityOperatorCandidates(
                BusinessLogQuery.newest(200), BusinessLogReadScope.organization("tenant", Set.of("organization-a")),
                BusinessLogOperatorCandidateQuery.browse(null, new BusinessLogPageRequest(1, 20)));

        assertThat(candidates.candidates()).extracting(BusinessLogOperatorCandidate::operatorId)
                .containsExactly("operator");
        assertThat(store.lastCandidateQuery.eventTypes()).containsExactlyInAnyOrder(
                BusinessLogEventType.ACTION, BusinessLogEventType.PAGE_ACCESS);
        assertThat(store.lastCandidateQuery.tenantId()).isEqualTo("tenant");
        assertThat(store.lastCandidateQuery.operatorOrganizationIds()).containsExactly("organization-a");
    }

    @Test
    void shouldValidateProgressiveNavigationAgainstTheSameHistoricLogFacts() {
        InMemoryStore store = new InMemoryStore(List.of(
                action("a", "tenant-a", "organization-a"),
                action("b", "tenant-b", "organization-b")
        ));
        BusinessLogGovernanceService service = new BusinessLogGovernanceService(store,
                new CapturingStatisticsReader());

        var result = service.queryBusinessActivityOperatorNavigation(BusinessLogQuery.newest(200),
                BusinessLogReadScope.platform(), BusinessLogOperatorCandidateQuery.browse(null,
                        new BusinessLogPageRequest(1, 20)).withScope("tenant-a", "organization-a", null));

        assertThat(result.showTenantNavigation()).isTrue();
        assertThat(result.navigation().tenants()).extracting(BusinessLogOperatorNavigationItem::id)
                .containsExactlyInAnyOrder("tenant-a", "tenant-b");
        assertThatThrownBy(() -> service.queryBusinessActivityOperatorNavigation(BusinessLogQuery.newest(200),
                BusinessLogReadScope.platform(), BusinessLogOperatorCandidateQuery.browse(null,
                        new BusinessLogPageRequest(1, 20)).withScope("forged", null, null)))
                .isInstanceOf(SecurityException.class).hasMessageContaining("tenant scope");
        assertThatThrownBy(() -> service.queryBusinessActivityOperatorNavigation(BusinessLogQuery.newest(200),
                BusinessLogReadScope.tenant("tenant-a"), BusinessLogOperatorCandidateQuery.browse(null,
                        new BusinessLogPageRequest(1, 20)).withScope(null, "organization-b", null)))
                .isInstanceOf(SecurityException.class).hasMessageContaining("organization scope");
    }

    @Test
    void shouldShowTenantNavigationToPlatformAdministratorsEvenWithOneVisibleTenant() {
        InMemoryStore store = new InMemoryStore(List.of(action("only", "tenant-a", "organization-a")));
        BusinessLogGovernanceService service = new BusinessLogGovernanceService(store,
                new CapturingStatisticsReader());

        var platformResult = service.queryBusinessActivityOperatorNavigation(BusinessLogQuery.newest(200),
                BusinessLogReadScope.platform(), BusinessLogOperatorCandidateQuery.browse(null,
                        new BusinessLogPageRequest(1, 20)));
        var tenantResult = service.queryBusinessActivityOperatorNavigation(BusinessLogQuery.newest(200),
                BusinessLogReadScope.tenant("tenant-a"), BusinessLogOperatorCandidateQuery.browse(null,
                        new BusinessLogPageRequest(1, 20)));

        assertThat(platformResult.showTenantNavigation()).isTrue();
        assertThat(platformResult.navigation().tenants()).extracting(BusinessLogOperatorNavigationItem::id)
                .containsExactly("tenant-a");
        assertThat(tenantResult.showTenantNavigation()).isFalse();
    }

    @Test
    void shouldAdvanceToTheRequestedStandardPageThroughTheStorageCursor() {
        InMemoryStore store = new InMemoryStore(List.of(
                action("first", "tenant", "organization-a"),
                action("second", "tenant", "organization-a"),
                action("third", "tenant", "organization-a")
        ));
        BusinessLogGovernanceService service = new BusinessLogGovernanceService(store,
                new CapturingStatisticsReader());

        BusinessLogPageResult page = service.queryBusinessActivitiesPage(BusinessLogQuery.newest(200),
                BusinessLogReadScope.organization("tenant", Set.of("organization-a")),
                new BusinessLogPageRequest(2, 2));

        assertThat(page.events()).extracting(BusinessLogEvent::eventId).containsExactly("third");
        assertThat(page.total()).isEqualTo(3);
        assertThat(page.totalKnown()).isTrue();
    }

    @Test
    void shouldCheckScopeAndExpectedTypeAgainForDetailById() {
        InMemoryStore store = new InMemoryStore(List.of(
                action("visible", "tenant", "organization-a"),
                action("out-of-scope", "tenant", "organization-b"),
                requestError("error", "tenant", "organization-a")
        ));
        BusinessLogGovernanceService service = new BusinessLogGovernanceService(store,
                new CapturingStatisticsReader());
        BusinessLogReadScope scope = BusinessLogReadScope.organization("tenant", Set.of("organization-a"));

        assertThat(service.findBusinessActivityDetail("visible", scope)).isPresent();
        assertThat(service.findBusinessActivityDetail("out-of-scope", scope)).isEmpty();
        assertThat(service.findBusinessActivityDetail("error", scope)).isEmpty();
        assertThat(service.findRequestErrorDetail("error", scope)).isPresent();
    }

    @Test
    void shouldApplyScopeToBothStatisticsQueries() {
        InMemoryStore store = new InMemoryStore(List.of());
        CapturingStatisticsReader statistics = new CapturingStatisticsReader();
        BusinessLogGovernanceService service = new BusinessLogGovernanceService(store, statistics);
        BusinessLogReadScope scope = BusinessLogReadScope.organization("tenant", Set.of("organization-a"));

        service.actionStatistics(new BusinessLogStatisticsQuery(null, null, "tenant-other", null,
                Set.of("organization-b"), "sales.order", "submit", 100), scope);
        service.pageAccessStatistics(new BusinessLogStatisticsQuery(null, null, null, null,
                null, "sales.order", null, 100), scope);

        assertThat(statistics.actionQuery.tenantId()).isEqualTo("tenant");
        assertThat(statistics.actionQuery.operatorOrganizationIds()).isEmpty();
        assertThat(statistics.pageQuery.tenantId()).isEqualTo("tenant");
        assertThat(statistics.pageQuery.operatorOrganizationIds()).containsExactly("organization-a");
    }

    @Test
    void shouldExposeInternalRequestErrorDiagnosticsOnlyToPlatformScope() {
        InMemoryStore store = new InMemoryStore(List.of(requestError("error", "tenant", "organization-a")));
        BusinessLogGovernanceService service = new BusinessLogGovernanceService(store,
                new CapturingStatisticsReader());

        Optional<RequestErrorLogDetails> diagnostic = service.findRequestErrorDiagnostic("error",
                BusinessLogReadScope.platform());

        assertThat(diagnostic).isPresent();
        assertThat(diagnostic.orElseThrow().stackTrace().value()).contains("internal stack");
        assertThatThrownBy(() -> service.findRequestErrorDiagnostic("error",
                BusinessLogReadScope.organization("tenant", Set.of("organization-a"))))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("platform read scope");
    }

    private static ActionLogEvent action(String eventId, String tenantId, String organizationId) {
        return new ActionLogEvent(context(eventId, tenantId, organizationId),
                new ActionLogDetails(ActionLogDetails.ActionOutcome.SUCCESS, "SERVICE", 10L, 1L, null, null));
    }

    private static PageAccessLogEvent page(String eventId, String tenantId, String organizationId) {
        return new PageAccessLogEvent(context(eventId, tenantId, organizationId),
                new PageAccessLogDetails("sales.order:LIST", null, "menu", "MENU_BOOTSTRAP"));
    }

    private static RequestErrorLogEvent requestError(String eventId, String tenantId, String organizationId) {
        return new RequestErrorLogEvent(context(eventId, tenantId, organizationId), new RequestErrorLogDetails(
                "GET", "/orders", "sales.order/list", 10L, 500, "INTERNAL_ERROR",
                LogText.of("The request failed"), "IllegalStateException", LogText.of("internal message"),
                LogText.of("internal stack"), "CONTROLLER", true));
    }

    private static BusinessLogContext context(String eventId, String tenantId, String organizationId) {
        return new BusinessLogContext(eventId, Instant.parse("2026-09-12T00:00:00Z"), Instant.now(),
                "trace-" + eventId, tenantId, "operator", organizationId, "sales.order", "submit");
    }

    private static final class CapturingStatisticsReader implements BusinessLogStatisticsReader {
        private BusinessLogStatisticsQuery actionQuery;
        private BusinessLogStatisticsQuery pageQuery;

        @Override
        public ActionLogStatistics actionStatistics(BusinessLogStatisticsQuery query) {
            actionQuery = query;
            return new ActionLogStatistics(0, 0, 0, 0, 0, 0, 0, 0, true);
        }

        @Override
        public PageAccessLogStatistics pageAccessStatistics(BusinessLogStatisticsQuery query) {
            pageQuery = query;
            return new PageAccessLogStatistics(0, List.of(), true);
        }
    }

    private static final class InMemoryStore implements BusinessLogStore {
        private final List<BusinessLogEvent> events;
        private BusinessLogQuery lastQuery;
        private BusinessLogQuery lastCandidateQuery;

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
            List<BusinessLogEvent> visible = events.stream()
                    .filter(event -> query.eventTypes() == null || query.eventTypes().contains(event.eventType()))
                    .filter(event -> query.tenantId() == null || query.tenantId().equals(event.context().tenantId()))
                    .filter(event -> query.operatorOrganizationIds() == null
                            || (event.context().operatorOrganizationId() != null
                            && query.operatorOrganizationIds().contains(event.context().operatorOrganizationId())))
                    .toList();
            int start = query.cursor() == null ? 0 : cursorIndex(visible, query.cursor()) + 1;
            int end = Math.min(visible.size(), start + query.limit());
            List<BusinessLogEvent> page = start >= visible.size() ? List.of() : visible.subList(start, end);
            BusinessLogCursor next = end < visible.size() ? cursor(page.getLast()) : null;
            return new BusinessLogReadPage(page, next);
        }

        @Override
        public BusinessLogOperatorCandidatePage readOperatorCandidates(BusinessLogQuery query,
                                                                         BusinessLogOperatorCandidateQuery candidateQuery) {
            lastCandidateQuery = query;
            List<BusinessLogOperatorCandidate> candidates = events.stream()
                    .filter(event -> event.context().operatorId() != null)
                    .filter(event -> query.eventTypes() == null || query.eventTypes().contains(event.eventType()))
                    .filter(event -> query.tenantId() == null || query.tenantId().equals(event.context().tenantId()))
                    .filter(event -> query.operatorOrganizationIds() == null
                            || (event.context().operatorOrganizationId() != null
                            && query.operatorOrganizationIds().contains(event.context().operatorOrganizationId())))
                    .map(event -> new BusinessLogOperatorCandidate(event.context().tenantId(), event.context().operatorId()))
                    .distinct().toList();
            return new BusinessLogOperatorCandidatePage(candidates, candidates.size(),
                    candidateQuery.page().pageNum(), candidateQuery.page().pageSize());
        }

        @Override
        public BusinessLogOperatorNavigation readOperatorNavigation(BusinessLogQuery query) {
            List<BusinessLogEvent> visible = events.stream()
                    .filter(event -> query.eventTypes() == null || query.eventTypes().contains(event.eventType()))
                    .filter(event -> query.tenantId() == null || query.tenantId().equals(event.context().tenantId()))
                    .filter(event -> query.operatorOrganizationIds() == null
                            || query.operatorOrganizationIds().contains(event.context().operatorOrganizationId()))
                    .toList();
            return new BusinessLogOperatorNavigation(
                    visible.stream().map(event -> event.context().tenantId()).filter(java.util.Objects::nonNull).distinct()
                            .map(id -> new BusinessLogOperatorNavigationItem(null, id)).toList(),
                    visible.stream().filter(event -> event.context().operatorOrganizationId() != null)
                            .map(event -> new BusinessLogOperatorNavigationItem(event.context().tenantId(),
                                    event.context().operatorOrganizationId())).distinct().toList(),
                    visible.stream().filter(event -> event.context().operatorDepartmentId() != null)
                            .map(event -> new BusinessLogOperatorNavigationItem(event.context().tenantId(),
                                    event.context().operatorOrganizationId(), event.context().operatorDepartmentId()))
                            .distinct().toList());
        }

        private static int cursorIndex(List<BusinessLogEvent> events, BusinessLogCursor cursor) {
            return java.util.stream.IntStream.range(0, events.size())
                    .filter(index -> events.get(index).eventId().equals(cursor.eventId()))
                    .findFirst().orElse(events.size() - 1);
        }

        private static BusinessLogCursor cursor(BusinessLogEvent event) {
            return new BusinessLogCursor(event.context().occurredAt(), event.eventId());
        }

        @Override
        public Optional<BusinessLogEvent> findById(String eventId) {
            return events.stream().filter(event -> event.eventId().equals(eventId)).findFirst();
        }
    }
}
